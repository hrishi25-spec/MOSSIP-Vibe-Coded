package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.engine.LivenessDecisionLogic;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Minimum wall-clock window a challenge stays open, regardless of the stored
     * config. A stored value below this is treated as this value.
     *
     * <p>Challenge attempts are not one-shot: while the window is open every
     * submitted burst is evaluated and the challenge passes the moment the action
     * is recognised. A failure is only counted once the whole window has elapsed
     * without a pass. This replaced an all-or-nothing model where a single short
     * burst decided the outcome, which failed people who simply took a moment
     * longer to read and perform the instruction.</p>
     *
     * <p>Lowered from 60s to 15s at the product's request, matching
     * {@link io.mosip.liveness.config.LivenessConfig#DEFAULT_CHALLENGE_TIMEOUT_MS}.</p>
     *
     * <p>This is the default of {@link #minChallengeWindowMs}; production never
     * lowers it, so the effective window is 15s unless an operator raises it.</p>
     */
    public static final long MIN_CHALLENGE_WINDOW_MS = 15_000L;

    /**
     * Hard lower bound for the configured floor. The config API already refuses a
     * {@code challengeTimeoutMs} under 1000ms, so letting the floor go below that
     * would be worse than useless: a sub-second challenge window turns a person
     * who needed a moment into a certain failure. Configured values below this
     * are treated as this value.
     */
    static final long ABSOLUTE_MIN_CHALLENGE_WINDOW_MS = 1_000L;

    /**
     * Number of consecutive frames that must agree on a PAD attack before the
     * session is terminally rejected. The ONNX PAD model is single-frame and
     * flips to an attack class on ordinary capture conditions (motion blur,
     * exposure shifts, a large or edge face crop); a genuine presentation attack
     * is consistent across frames. Requiring agreement keeps screen-replay
     * detection strict while removing device-specific single-frame false
     * positives that terminally failed honest sessions.
     */
    public static final int PAD_CONFIRM_FRAMES = 2;

    /**
     * The floor a stored {@code challengeTimeoutMs} is raised to, i.e. how long a
     * challenge really stays open. Defaults to {@link #MIN_CHALLENGE_WINDOW_MS}.
     *
     * <p>ponytail: an operator/test knob, not a per-workflow policy field — it is
     * the anti-footgun guard for the whole decision engine and must never be
     * lowered below {@link #ABSOLUTE_MIN_CHALLENGE_WINDOW_MS}. Lowering it is only
     * sane in tests, where it turns the real-time timeout path (two window
     * waits) into seconds instead of half a minute.</p>
     */
    @Value("${mosip.liveness.min-challenge-window-ms:" + MIN_CHALLENGE_WINDOW_MS + "}")
    private long minChallengeWindowMs = MIN_CHALLENGE_WINDOW_MS;

    private final LivenessEngineService livenessEngine;
    private final PadEngineService padEngine;
    private final ChallengeSelectorService challengeSelector;
    private final ConfigService configService;
    private final FrameEventRepository frameEventRepo;
    private final ChallengeRepository challengeRepo;
    private final AuditLogRepository auditLogRepo;
    private final LivenessSessionRepository sessionRepo;
    private final PassiveScoringService passiveScorer;

    /**
     * Process a single frame through the passive liveness + PAD pipeline.
     * Config is read fresh from the DB for each call.
     */
    @Transactional
    public FrameProcessResult processFrame(LivenessSession in, Mat frame,
                                           ImageUtils imageUtils) {
        // Serialize concurrent decisions for this session: the stage guard below
        // must be atomic with the transitions it protects, or two racing frames
        // could both observe the passive stage — one marking the session PASSED
        // while the other issues a challenge.
        LivenessSession session = sessionRepo.findByIdForUpdate(in.getId()).orElse(in);

        // Read effective policy from DB (updates via API take effect immediately)
        EffectivePolicy policy = configService.getEffectivePolicy(
                configService.toCoreWorkflow(session.getWorkflowType()));
        double passiveThreshold = policy.passiveThreshold();

        // ---- Challenge lock -----------------------------------------------------
        // Passive scoring stops deciding once a challenge is open: a
        // late-arriving high-score frame must not mark an ACTIVE session PASSED
        // without the requested action ever being performed, and no second
        // challenge may be issued for the same stage. Re-serve the open
        // challenge instead (idempotent for the client), self-healing by
        // issuing one if that state was somehow lost.
        if (session.getCurrentStage() == LivenessStage.ACTIVE) {
            ChallengeEntity open = challengeRepo.findFirstBySessionIdAndStatusOrderByIssuedAtDesc(
                    session.getId(), ChallengeStatus.ISSUED);
            if (open == null) {
                open = issueChallenge(session, policy);
            }
            return challengeResult(session, open, "escalate_to_active", null, null);
        }

        // Face observation
        LivenessEngineService.FaceObservation observation = livenessEngine.observeFace(frame, imageUtils);

        // PAD check (runs on every frame): FFT/texture/brightness heuristics
        // OR'd with the MiniFASNet model's verdict — either source flagging an
        // attack is terminal (PAD stays fail-closed even when the heuristics
        // miss a well-lit screen replay).
        PadVerdict padResult = detectPad(frame, observation, imageUtils);

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

        // PAD gate: reject on a *confirmed* attack. The current frame is recorded
        // first, then the verdict is checked against the preceding frame: a
        // genuine attack is consistent and confirms on the very next frame, while
        // a one-off model flip (the usual cause of device-specific false rejects)
        // does not.
        if (padResult.attackDetected()) {
            saveFrameEvent(session, true, false, observation.faceQuality(), null,
                    true, padResult.attackType().name(), padResult.confidence());
            if (!padAttackConfirmed(session)) {
                return FrameProcessResult.builder()
                        .sessionId(session.getId())
                        .stage(session.getCurrentStage())
                        .faceDetected(true)
                        .faceQuality(observation.faceQuality())
                        .padFlag(true)
                        .padAttackType(padResult.attackType().name())
                        .action("retry_passive")
                        .message("Checking face liveness...")
                        .build();
            }
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

        // Passive liveness score: the MiniFASNet model's live-class confidence
        // when available, the OpenCV quality heuristic otherwise.
        double livenessScore = passiveScorer.score(frame, observation, imageUtils);
        saveFrameEvent(session, true, false, observation.faceQuality(), livenessScore,
                false, null, padResult.confidence());

        // ---- Median-window decision ---------------------------------------------
        // The passive stage used to compare this single frame's score with the
        // threshold, so one blurry or half-lit frame escalated a session (and one
        // lucky frame passed it). The decision is now the median of the last
        // passiveWindowFrames scores — the median rejects outlier frames without
        // a second model — and it is only made once at least passiveMinFrames
        // scores exist. Until then the client keeps sending frames: the session
        // stays PASSIVE and every response is a non-terminal "retry_passive".
        // See LivenessDecisionLogic.decidePassiveWindow for the cold-start rule.
        java.util.List<Double> recentScores = frameEventRepo.findScoresBySessionAndStage(
                session.getId(), LivenessStage.PASSIVE);
        java.util.Optional<LivenessDecisionLogic.PassiveOutcome> decision =
                LivenessDecisionLogic.decidePassiveWindow(recentScores,
                        policy.passiveMinFrames(), policy.passiveWindowFrames(), passiveThreshold);

        if (decision.isEmpty()) {
            return FrameProcessResult.builder()
                    .sessionId(session.getId())
                    .stage(session.getCurrentStage())
                    .faceDetected(true)
                    .faceQuality(observation.faceQuality())
                    .livenessScore(livenessScore)
                    .padFlag(false)
                    .action("retry_passive")
                    .message("Checking face liveness...")
                    .build();
        }

        if (decision.get() == LivenessDecisionLogic.PassiveOutcome.PROCEED_PASSIVE) {
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

        // PAD_BLOCK never reaches here — the terminal PAD gate above runs before
        // scoring — so the remaining outcome is ESCALATE_ACTIVE.

        // Median below threshold -> escalate to active (if enabled)
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

        // Automatic initiation of active liveness verification.
        session.setCurrentStage(LivenessStage.ACTIVE);
        ChallengeEntity challenge = issueChallenge(session, policy);
        return challengeResult(session, challenge, "escalate_to_active",
                livenessScore, observation.faceQuality());
    }

    /**
     * Combines the heuristic PAD (FFT energy, texture variance, brightness) with
     * the model PAD (MiniFASNet print/replay classes) into one verdict. Either
     * source detecting an attack wins — defence in depth, and the model covers
     * the well-lit screen replays the frequency heuristic cannot see.
     */
    private PadVerdict detectPad(Mat frame, LivenessEngineService.FaceObservation observation,
                                 ImageUtils imageUtils) {
        PadVerdict heuristic = padEngine.detect(frame, imageUtils);
        if (heuristic.attackDetected()) {
            return heuristic;
        }
        return passiveScorer.assessPad(frame, observation).orElse(heuristic);
    }

    /**
     * Result for a session that is in (or entering) the active stage: carries
     * the challenge the client must prompt for. Scores are only supplied when
     * this frame made the decision — a re-served lock response did not score.
     */
    private FrameProcessResult challengeResult(LivenessSession session, ChallengeEntity challenge,
                                               String action, Double livenessScore, Double faceQuality) {
        return FrameProcessResult.builder()
                .sessionId(session.getId())
                .stage(LivenessStage.ACTIVE)
                .faceDetected(true)
                .faceQuality(faceQuality)
                .livenessScore(livenessScore)
                .padFlag(false)
                .action(action)
                .message("Please " + challenge.getChallengeType().name().toLowerCase().replace("_", " ") + ".")
                .challenge(FrameProcessResult.ChallengeInfo.builder()
                        .challengeId(challenge.getId())
                        .challengeType(challenge.getChallengeType().name())
                        .timeoutMs(challenge.getTimeoutMs())
                        .attemptNumber(challenge.getAttemptNumber())
                        .build())
                .build();
    }

    /**
     * Process challenge validation across a set of frames.
     * Config is read fresh from the DB for each call.
     */
    @Transactional
    public Map<String, Object> processChallengeValidation(LivenessSession in,
                                                          ChallengeEntity challenge,
                                                          java.util.List<Mat> frames,
                                                          ImageUtils imageUtils) {
        // Same row lock as processFrame: challenge validation mutates session
        // state (retry budget, final verdict) and must not interleave with a
        // frame submission deciding on the same session.
        LivenessSession session = sessionRepo.findByIdForUpdate(in.getId()).orElse(in);

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

        if (passed) {
            // Resolved immediately: the challenge stops as soon as the action is seen.
            challenge.setStatus(ChallengeStatus.PASSED);
            challenge.setCompletedAt(OffsetDateTime.now());
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
                    "challenge", issueChallenge(session, policy));
        }

        // Not detected yet. Stay patient: keep this very challenge open until its
        // window has genuinely elapsed, and return a non-terminal "continue" so the
        // client can keep the same challenge id and try again. No retry budget is
        // consumed and the challenge is not marked FAILED yet.
        long floor = minWindowMs();
        long windowMs = challenge.getTimeoutMs() != null
                ? Math.max(challenge.getTimeoutMs(), floor)
                : floor;
        long elapsedMs = elapsedSince(challenge.getIssuedAt());
        if (elapsedMs < windowMs) {
            long remainingSec = Math.max(1L, (windowMs - elapsedMs + 999L) / 1000L);
            return Map.of(
                    "passed", false,
                    "action", "continue",
                    "message", "Not detected yet — take your time and keep trying. "
                            + remainingSec + "s left for this action.",
                    "remainingMs", windowMs - elapsedMs);
        }

        // The window elapsed without a pass: this attempt really did fail.
        challenge.setStatus(ChallengeStatus.FAILED);
        challenge.setCompletedAt(OffsetDateTime.now());
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
                "challenge", issueChallenge(session, policy));
    }

    /**
     * The configured floor, clamped to the absolute minimum.
     */
    private long minWindowMs() {
        return Math.max(minChallengeWindowMs, ABSOLUTE_MIN_CHALLENGE_WINDOW_MS);
    }

    /**
     * Issues and persists the next challenge for the session, avoiding an
     * immediate repeat of the most recently issued one (the challenge that just
     * passed or timed out — derived from the repository so this single helper
     * serves both the first escalation and every retry).
     *
     * <p>A {@code retry_challenge} verdict previously handed the client back the
     * very challenge that had just been resolved, so its follow-up validation was
     * rejected with 409 (that challenge was already PASSED or FAILED). A freshly
     * ISSUED challenge is required instead.</p>
     */
    private ChallengeEntity issueChallenge(LivenessSession session, EffectivePolicy policy) {
        String previousType = null;
        java.util.List<ChallengeEntity> issued =
                challengeRepo.findBySessionIdOrderByIssuedAtDesc(session.getId());
        if (issued != null && !issued.isEmpty() && issued.get(0).getChallengeType() != null) {
            previousType = issued.get(0).getChallengeType().name();
        }

        io.mosip.liveness.models.enums.ChallengeType nextType = challengeSelector.selectChallenge(
                policy.allowedChallenges().stream()
                        .map(ct -> configService.toDbChallenge(ct).name().toLowerCase())
                        .collect(Collectors.toList()),
                previousType);

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

    /**
     * True once the last {@link #PAD_CONFIRM_FRAMES} recorded frames (including
     * the one just saved) are all PAD-positive — so a single noisy frame cannot
     * terminally reject a session.
     */
    private boolean padAttackConfirmed(LivenessSession session) {
        java.util.List<Boolean> recent = frameEventRepo.findRecentPadFlags(
                session.getId(), LivenessStage.PASSIVE,
                org.springframework.data.domain.PageRequest.of(0, PAD_CONFIRM_FRAMES));
        if (recent == null || recent.size() < PAD_CONFIRM_FRAMES) {
            return false;
        }
        for (Boolean flag : recent) {
            if (!Boolean.TRUE.equals(flag)) {
                return false;
            }
        }
        return true;
    }

    /** Milliseconds since a challenge was issued; 0 when the timestamp is missing. */
    private static long elapsedSince(OffsetDateTime issuedAt) {
        if (issuedAt == null) return 0L;
        return Math.max(0L, java.time.Duration.between(issuedAt, OffsetDateTime.now()).toMillis());
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
                // Same column the config feed filters on, populated here too so
                // the table is never half-NULL: any future "everything for
                // OPERATOR" query covers pipeline events, not just policy edits.
                .workflowType(session.getWorkflowType())
                .details(details != null ? new HashMap<>(details) : new HashMap<>())
                .build();
        auditLogRepo.save(audit);
    }
}
