package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the passive -> active liveness/PAD decision flow.
 * Reads effective config from the database on every call so that runtime
 * updates via the config API take effect immediately.
 *
 * Maps to the Python framework's decision_engine.py.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionEngineService {

    private final LivenessEngineService livenessEngine;
    private final PadEngineService padEngine;
    private final ChallengeSelectorService challengeSelector;
    private final ConfigService configService;
    private final FrameEventRepository frameEventRepo;
    private final ChallengeRepository challengeRepo;
    private final AuditLogRepository auditLogRepo;

    /**
     * Process a single frame through the passive liveness + PAD pipeline.
     * Config is read fresh from the DB for each call.
     */
    @Transactional
    public FrameProcessResult processFrame(LivenessSession session, Mat frame,
                                           ImageUtils imageUtils) {
        // Read effective policy from DB (updates via API take effect immediately)
        EffectivePolicy policy = configService.getEffectivePolicy(
                configService.toCoreWorkflow(session.getWorkflowType()));
        double passiveThreshold = policy.passiveThreshold();
        long challengeTimeoutMs = policy.challengeTimeoutMs();

        // Face observation
        LivenessEngineService.FaceObservation observation = livenessEngine.observeFace(frame, imageUtils);

        // PAD check (runs on every frame)
        PadVerdict padResult = padEngine.detect(frame, imageUtils);

        // Face-quality gate
        if (!observation.faceDetected()) {
            saveFrameEvent(session, false, false, null, null, false, null, null);
            return FrameProcessResult.builder()
                    .sessionId(session.getId())
                    .stage(session.getCurrentStage())
                    .faceDetected(false)
                    .multipleFaces(false)
                    .padFlag(padResult.attackDetected())
                    .padAttackType(padResult.attackDetected() ? padResult.attackType().name() : null)
                    .action("retry_passive")
                    .message("No face detected. Please position your face in the frame.")
                    .build();
        }

        if (observation.multipleFaces()) {
            saveFrameEvent(session, true, true, null, null, padResult.attackDetected(),
                    padResult.attackDetected() ? padResult.attackType().name() : null, padResult.confidence());
            return FrameProcessResult.builder()
                    .sessionId(session.getId())
                    .stage(session.getCurrentStage())
                    .faceDetected(true)
                    .multipleFaces(true)
                    .padFlag(padResult.attackDetected())
                    .padAttackType(padResult.attackDetected() ? padResult.attackType().name() : null)
                    .action("retry_passive")
                    .message("Multiple faces detected. Only one person may be captured at a time.")
                    .build();
        }

        // PAD gate: hard reject on attack
        if (padResult.attackDetected()) {
            saveFrameEvent(session, true, false, observation.faceQuality(), null,
                    true, padResult.attackType().name(), padResult.confidence());
            logAudit(session, "PAD_REJECTED", Map.of(
                    "attackType", padResult.attackType().name(),
                    "confidence", padResult.confidence(),
                    "stage", session.getCurrentStage().name()));
            session.setStatus(SessionStatus.FAILED);
            session.setFailureReason("presentation_attack:" + padResult.attackType().name());
            session.setCurrentStage(LivenessStage.COMPLETED);
            session.setClosedAt(OffsetDateTime.now());
            return FrameProcessResult.builder()
                    .sessionId(session.getId())
                    .stage(LivenessStage.COMPLETED)
                    .faceDetected(true)
                    .faceQuality(observation.faceQuality())
                    .padFlag(true)
                    .padAttackType(padResult.attackType().name())
                    .action("reject")
                    .message("Face verification could not be completed. Please try again.")
                    .build();
        }

        // Passive liveness score
        double livenessScore = livenessEngine.scorePassive(frame, observation, imageUtils);
        saveFrameEvent(session, true, false, observation.faceQuality(), livenessScore,
                false, null, padResult.confidence());

        // Score >= threshold -> proceed
        if (livenessScore >= passiveThreshold) {
            session.setFinalResult(true);
            session.setStatus(SessionStatus.PASSED);
            session.setCurrentStage(LivenessStage.COMPLETED);
            session.setClosedAt(OffsetDateTime.now());
            logAudit(session, "SESSION_PASSED", Map.of("via", "passive"));
            return FrameProcessResult.builder()
                    .sessionId(session.getId())
                    .stage(LivenessStage.COMPLETED)
                    .faceDetected(true)
                    .faceQuality(observation.faceQuality())
                    .livenessScore(livenessScore)
                    .padFlag(false)
                    .action("proceed")
                    .message("Liveness verified.")
                    .build();
        }

        // Score < threshold -> escalate to active (if enabled)
        if (!policy.activeLivenessEnabled()) {
            session.setFinalResult(false);
            session.setStatus(SessionStatus.FAILED);
            session.setFailureReason("passive_liveness_below_threshold");
            session.setCurrentStage(LivenessStage.COMPLETED);
            session.setClosedAt(OffsetDateTime.now());
            return FrameProcessResult.builder()
                    .sessionId(session.getId())
                    .stage(LivenessStage.COMPLETED)
                    .faceDetected(true)
                    .faceQuality(observation.faceQuality())
                    .livenessScore(livenessScore)
                    .padFlag(false)
                    .action("reject")
                    .message("We could not verify face liveness. Please try again.")
                    .build();
        }

        session.setCurrentStage(LivenessStage.ACTIVE);

        // Select challenge from configured allowed types
        String previousType = null; // first escalation
        io.mosip.liveness.models.enums.ChallengeType dbChallengeType = challengeSelector.selectChallenge(
                policy.allowedChallenges().stream()
                        .map(ct -> configService.toDbChallenge(ct).name().toLowerCase())
                        .collect(Collectors.toList()),
                previousType);

        // Persist challenge with configured timeout
        ChallengeEntity challenge = ChallengeEntity.builder()
                .session(session)
                .challengeType(dbChallengeType)
                .status(ChallengeStatus.ISSUED)
                .attemptNumber(1)
                .timeoutMs((int) challengeTimeoutMs)
                .issuedAt(OffsetDateTime.now())
                .build();
        challengeRepo.save(challenge);

        logAudit(session, "CHALLENGE_ISSUED", Map.of(
                "challengeType", dbChallengeType.name(),
                "attemptNumber", 1,
                "timeoutMs", challengeTimeoutMs));

        return FrameProcessResult.builder()
                .sessionId(session.getId())
                .stage(LivenessStage.ACTIVE)
                .faceDetected(true)
                .faceQuality(observation.faceQuality())
                .livenessScore(livenessScore)
                .padFlag(false)
                .action("escalate_to_active")
                .message("Please " + dbChallengeType.name().toLowerCase().replace("_", " ") + ".")
                .challenge(FrameProcessResult.ChallengeInfo.builder()
                        .challengeId(challenge.getId())
                        .challengeType(dbChallengeType.name())
                        .timeoutMs((int) challengeTimeoutMs)
                        .attemptNumber(1)
                        .build())
                .build();
    }

    /**
     * Process challenge validation across a set of frames.
     * Config is read fresh from the DB for each call.
     */
    @Transactional
    public Map<String, Object> processChallengeValidation(LivenessSession session,
                                                          ChallengeEntity challenge,
                                                          java.util.List<Mat> frames,
                                                          ImageUtils imageUtils) {
        // Read effective policy from DB
        EffectivePolicy policy = configService.getEffectivePolicy(
                configService.toCoreWorkflow(session.getWorkflowType()));
        int maxRetry = policy.maxRetries();
        int minChallengeCount = policy.minChallengeCount();

        // Re-run PAD across challenge frames
        for (Mat frame : frames) {
            PadVerdict padResult = padEngine.detect(frame, imageUtils);
            if (padResult.attackDetected()) {
                challenge.setStatus(ChallengeStatus.FAILED);
                challenge.setCompletedAt(OffsetDateTime.now());
                logAudit(session, "PAD_REJECTED", Map.of(
                        "attackType", padResult.attackType().name(),
                        "confidence", padResult.confidence(),
                        "stage", "active"));
                session.setStatus(SessionStatus.FAILED);
                session.setFailureReason("presentation_attack:" + padResult.attackType().name());
                session.setCurrentStage(LivenessStage.COMPLETED);
                session.setClosedAt(OffsetDateTime.now());
                return Map.of(
                        "passed", false,
                        "action", "reject",
                        "message", "Face verification could not be completed. Please try again.");
            }
        }

        boolean passed = livenessEngine.validateActive(challenge.getChallengeType(), frames, imageUtils);
        challenge.setCompletedAt(OffsetDateTime.now());

        if (passed) {
            challenge.setStatus(ChallengeStatus.PASSED);
            logAudit(session, "CHALLENGE_PASSED", Map.of("challengeType", challenge.getChallengeType().name()));

            long challengesCompleted = challengeRepo.countBySessionIdAndStatus(
                    session.getId(), ChallengeStatus.PASSED);
            if (challengesCompleted >= minChallengeCount) {
                session.setFinalResult(true);
                session.setStatus(SessionStatus.PASSED);
                session.setCurrentStage(LivenessStage.COMPLETED);
                session.setClosedAt(OffsetDateTime.now());
                return Map.of(
                        "passed", true,
                        "action", "proceed",
                        "message", "Liveness verified.");
            }
            return Map.of(
                    "passed", true,
                    "action", "retry_challenge",
                    "message", "Action detected. One more check required.",
                    "challenge", issueNextChallenge(session, policy, challenge.getChallengeType()));
        }

        // Failed
        challenge.setStatus(ChallengeStatus.FAILED);
        logAudit(session, "CHALLENGE_FAILED", Map.of("challengeType", challenge.getChallengeType().name()));

        session.setRetryCount(session.getRetryCount() + 1);
        if (session.getRetryCount() >= maxRetry) {
            session.setStatus(SessionStatus.FAILED);
            session.setFailureReason("max_retries_exceeded");
            session.setCurrentStage(LivenessStage.COMPLETED);
            session.setClosedAt(OffsetDateTime.now());
            return Map.of(
                    "passed", false,
                    "action", "reject",
                    "message", "We could not verify face liveness. Please try again.");
        }

        return Map.of(
                "passed", false,
                "action", "retry_challenge",
                "message", "We could not verify that action. Let's try a different one.",
                "challenge", issueNextChallenge(session, policy, challenge.getChallengeType()));
    }

    /**
     * Issues and persists the next challenge, avoiding an immediate repeat of
     * {@code previousType}.
     *
     * <p>A {@code retry_challenge} verdict previously handed the client back the
     * very challenge that had just been resolved, so its follow-up validation was
     * rejected with 409 (that challenge was already PASSED or FAILED). A freshly
     * ISSUED challenge is required instead.</p>
     */
    private ChallengeEntity issueNextChallenge(LivenessSession session, EffectivePolicy policy,
                                               io.mosip.liveness.models.enums.ChallengeType previousType) {
        io.mosip.liveness.models.enums.ChallengeType nextType = challengeSelector.selectChallenge(
                policy.allowedChallenges().stream()
                        .map(ct -> configService.toDbChallenge(ct).name().toLowerCase())
                        .collect(Collectors.toList()),
                previousType != null ? previousType.name() : null);

        int attempt = (int) challengeRepo.countBySessionId(session.getId()) + 1;
        ChallengeEntity next = ChallengeEntity.builder()
                .session(session)
                .challengeType(nextType)
                .status(ChallengeStatus.ISSUED)
                .attemptNumber(attempt)
                .timeoutMs((int) policy.challengeTimeoutMs())
                .issuedAt(OffsetDateTime.now())
                .build();
        challengeRepo.save(next);

        logAudit(session, "CHALLENGE_ISSUED", Map.of(
                "challengeType", nextType.name(),
                "attemptNumber", attempt,
                "timeoutMs", next.getTimeoutMs()));
        return next;
    }

    private void saveFrameEvent(LivenessSession session, boolean faceDetected, boolean multipleFaces,
                                 Double faceQuality, Double livenessScore, boolean padFlag,
                                 String padAttackType, Double padConfidence) {
        FrameEvent event = FrameEvent.builder()
                .session(session)
                .stage(session.getCurrentStage())
                .faceDetected(faceDetected)
                .multipleFaces(multipleFaces)
                .faceQuality(faceQuality)
                .livenessScore(livenessScore)
                .padFlag(padFlag)
                .padAttackType(padAttackType)
                .padConfidence(padConfidence)
                .build();
        frameEventRepo.save(event);
    }

    private void logAudit(LivenessSession session, String eventType, Map<String, Object> details) {
        AuditLog audit = AuditLog.builder()
                .session(session)
                .eventType(eventType)
                .details(details != null ? new HashMap<>(details) : new HashMap<>())
                .build();
        auditLogRepo.save(audit);
    }
}
