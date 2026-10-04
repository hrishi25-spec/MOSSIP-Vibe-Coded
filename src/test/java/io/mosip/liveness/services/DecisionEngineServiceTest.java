package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.core.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Patience semantics for active challenges.
 *
 * <p>A challenge must stay open for a whole window (15s floor) while every
 * submitted burst is evaluated, passing the moment the action is recognised. A
 * miss only counts as a failure once the window has elapsed. Previously a single
 * short burst decided the outcome and burned a retry, so a person who simply
 * needed a moment longer failed the check.</p>
 */
class DecisionEngineServiceTest {

    private LivenessEngineService livenessEngine;
    private PadEngineService padEngine;
    private ChallengeSelectorService challengeSelector;
    private ConfigService configService;
    private FrameEventRepository frameEventRepo;
    private ChallengeRepository challengeRepo;
    private AuditLogRepository auditLogRepo;
    private LivenessSessionRepository sessionRepo;
    private PassiveScoringService passiveScorer;

    private DecisionEngineService service;
    private ImageUtils imageUtils;
    private LivenessSession session;
    private ChallengeEntity challenge;
    private List<Mat> frames;

    private static final io.mosip.liveness.models.enums.ChallengeType DB_TURN_LEFT =
            io.mosip.liveness.models.enums.ChallengeType.TURN_LEFT;

    @BeforeEach
    void setUp() {
        livenessEngine = mock(LivenessEngineService.class);
        padEngine = mock(PadEngineService.class);
        challengeSelector = mock(ChallengeSelectorService.class);
        configService = mock(ConfigService.class);
        frameEventRepo = mock(FrameEventRepository.class);
        challengeRepo = mock(ChallengeRepository.class);
        auditLogRepo = mock(AuditLogRepository.class);
        sessionRepo = mock(LivenessSessionRepository.class);
        passiveScorer = mock(PassiveScoringService.class);
        imageUtils = mock(ImageUtils.class);

        // Default: the session row lock finds nothing (detached test session),
        // so the service falls back to the instance passed in.
        when(sessionRepo.findByIdForUpdate(any())).thenReturn(java.util.Optional.empty());

        when(configService.toCoreWorkflow(any())).thenReturn(WorkflowType.RESIDENT_REGISTRATION);
        when(configService.toDbChallenge(any())).thenReturn(DB_TURN_LEFT);
        when(challengeSelector.selectChallenge(anyList(), any())).thenReturn(DB_TURN_LEFT);
        when(padEngine.detect(any(Mat.class), any())).thenReturn(PadVerdict.bonaFide(0.9));
        when(challengeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(0L);
        when(challengeRepo.countBySessionId(any())).thenReturn(0L);

        service = new DecisionEngineService(livenessEngine, padEngine, challengeSelector,
                configService, frameEventRepo, challengeRepo, auditLogRepo,
                sessionRepo, passiveScorer);

        session = LivenessSession.builder()
                .id(UUID.randomUUID()).workflowType(io.mosip.liveness.models.enums.WorkflowType.RESIDENT)
                .deviceId("WEB-CAM").status(SessionStatus.ACTIVE).currentStage(LivenessStage.ACTIVE)
                .retryCount(0).online(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();

        challenge = ChallengeEntity.builder()
                .id(UUID.randomUUID()).session(session)
                .challengeType(DB_TURN_LEFT).status(ChallengeStatus.ISSUED)
                .attemptNumber(1).timeoutMs(60_000)
                .issuedAt(OffsetDateTime.now())
                .build();

        frames = List.of(mock(Mat.class), mock(Mat.class));
    }

    private EffectivePolicy policy(int minChallengeCount, int maxRetries) {
        return policy(minChallengeCount, maxRetries, RepeatedFailureAction.LOCK_OUT);
    }

    private EffectivePolicy policy(int minChallengeCount, int maxRetries,
                                   RepeatedFailureAction onRepeatedFailure) {
        return new EffectivePolicy(true, true, 0.80, 0.50, 5, 7,
                minChallengeCount, maxRetries, 60_000L,
                EnumSet.of(ChallengeType.TURN_HEAD_LEFT), onRepeatedFailure,
                -1.0, 30_000L, 1, 10, 0.6, 0.4);
    }

    /** Exhaust the retry budget (maxRetries=1) and return the terminal verdict. */
    private Map<String, Object> exhaustRetries(RepeatedFailureAction action) {
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(61));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 1, action));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);
        return service.processChallengeValidation(session, challenge, frames, imageUtils);
    }

    @Test
    void lockOutOnRetryExhaustionIsTerminalAndNotRetryable() {
        Map<String, Object> result = exhaustRetries(RepeatedFailureAction.LOCK_OUT);

        assertEquals("locked", result.get("action"));
        assertEquals(Boolean.FALSE, result.get("mayRetrySession"));
        assertEquals("max_retries_exceeded:locked_out", session.getFailureReason());
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertNull(result.get("challenge"), "a failure action must never grant a challenge");
    }

    @Test
    void escalateOnRetryExhaustionSignalsTheOperatorAndDoesNotRetry() {
        Map<String, Object> result = exhaustRetries(RepeatedFailureAction.ESCALATE_TO_OPERATOR);

        assertEquals("escalate_to_operator", result.get("action"));
        assertEquals(Boolean.FALSE, result.get("mayRetrySession"));
        assertEquals("max_retries_exceeded:escalation_required", session.getFailureReason());
        assertNull(result.get("challenge"));
    }

    @Test
    void allowRetryOnRetryExhaustionFailsButPermitsAFreshSession() {
        Map<String, Object> result = exhaustRetries(RepeatedFailureAction.FALLBACK);

        assertEquals("failed", result.get("action"));
        assertEquals(Boolean.TRUE, result.get("mayRetrySession"));
        assertEquals("max_retries_exceeded", session.getFailureReason());
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertNull(result.get("challenge"));
    }

    @Test
    void aMissInsideTheWindowKeepsTheSameChallengeOpen() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("continue", result.get("action"));
        assertFalse((Boolean) result.get("passed"));
        assertEquals(ChallengeStatus.ISSUED, challenge.getStatus(), "challenge must stay open");
        assertNull(challenge.getCompletedAt());
        assertEquals(0, session.getRetryCount(), "a poll must not burn retry budget");
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        verify(challengeRepo, never()).save(any());
    }

    @Test
    void aShortConfiguredTimeoutStillHonoursTheFifteenSecondFloor() {
        // Legacy DB row with an 8s timeout, 10s in: still inside the 15s floor.
        challenge.setTimeoutMs(8_000);
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(10));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("continue", result.get("action"));
        assertEquals(ChallengeStatus.ISSUED, challenge.getStatus());
        assertEquals(0, session.getRetryCount());
    }

    @Test
    void aMissAfterTheWindowElapsesCountsAndIssuesTheNextChallenge() {
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(61));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("retry_challenge", result.get("action"));
        assertEquals(ChallengeStatus.FAILED, challenge.getStatus());
        assertNotNull(challenge.getCompletedAt());
        assertEquals(1, session.getRetryCount());
        assertNotNull(result.get("challenge"), "a fresh challenge must be issued");
    }

    @Test
    void repeatedPollsInsideTheWindowNeverExhaustTheRetryBudget() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        for (int i = 0; i < 20; i++) {
            Map<String, Object> result =
                    service.processChallengeValidation(session, challenge, frames, imageUtils);
            assertEquals("continue", result.get("action"), "poll " + i + " should stay open");
        }

        assertEquals(0, session.getRetryCount());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
    }

    @Test
    void passingStopsTheChallengeImmediately() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(true);
        // The repository query flushes the just-passed challenge, so this pass is
        // the one counted towards minChallengeCount.
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(1L);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("proceed", result.get("action"));
        assertTrue((Boolean) result.get("passed"));
        assertEquals(ChallengeStatus.PASSED, challenge.getStatus());
        assertNotNull(challenge.getCompletedAt());
        assertEquals(SessionStatus.PASSED, session.getStatus());
        assertEquals(0, session.getRetryCount());
    }

    @Test
    void twoChallengeWorkflowNeedsASecondChallengeBeforeProceeding() {
        // Supervisor default: minChallengeCount = 2. The first challenge passing
        // must ask for another, not complete the session.
        when(configService.getEffectivePolicy(any())).thenReturn(policy(2, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(true);
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(1L);

        Map<String, Object> result =
                service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("retry_challenge", result.get("action"));
        assertNotNull(result.get("challenge"), "a second challenge must be issued");
        assertNotEquals(SessionStatus.PASSED, session.getStatus());
    }

    @Test
    void aPresentationAttackDuringAnAttemptStillRejectsImmediately() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(padEngine.detect(any(Mat.class), any()))
                .thenReturn(PadVerdict.attack(PadAttackType.PRINTED_PHOTO, 0.9));

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("reject", result.get("action"));
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(ChallengeStatus.FAILED, challenge.getStatus());
    }
}
