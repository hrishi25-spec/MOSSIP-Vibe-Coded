package io.mosip.liveness.engine;

import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.testing.MutableClock;
import io.mosip.liveness.testing.RecordingAuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineEscalationTest {

    private MockLivenessBackend backend;
    private RecordingAuditLogger audit;
    private MutableClock clock;
    private FaceLivenessEngine engine;
    private int frameSeq;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        audit = new RecordingAuditLogger();
        clock = new MutableClock(1_760_000_000_000L);
        engine = new FaceLivenessEngine(LivenessConfig.builder()
                .passiveMinFrames(2)
                .minChallengeCount(2)
                .challengeTimeoutMs(5_000)
                .maxRetries(1)
                .build(), backend, audit, new MetricsCollector(), clock);
        backend.subject().livenessScore = 0.40;   // below default threshold -> escalation
        backend.subject().scoreNoise = 0.0;
    }

    private Frame frame() {
        return Frame.of(new byte[64], 8, 8, Frame.Format.RGB_GRAY, clock.millis(), frameSeq++);
    }

    private static FaceSignals liveSignals(double ear, double smile, double yaw, double gazeX, double gazeY) {
        return FaceSignals.live(1, 0.92, ear, smile, yaw, 0, gazeX, gazeY);
    }

    private List<Frame> framesFor(Challenge challenge) {
        backend.clearSignalOverrides();
        List<Frame> frames = new ArrayList<>();
        switch (challenge.type()) {
            case BLINK -> {
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0.05, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.08, 0.05, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.08, 0.05, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0.05, 0, 0, 0));
            }
            case SMILE -> {
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0.10, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0.85, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0.85, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0.10, 0, 0, 0));
            }
            case TURN_HEAD_LEFT -> {
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, -10, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, -18, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, -18, 0, 0));
            }
            case TURN_HEAD_RIGHT -> {
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 10, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 18, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 18, 0, 0));
            }
            case LOOK_DIRECTION -> {
                double dx = challenge.parameters().getOrDefault("dirX", 1.0);
                double dy = challenge.parameters().getOrDefault("dirY", 0.0);
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 0, dx, dy));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 0, dx, dy));
                frames.add(frame());
                backend.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
            }
        }
        return frames;
    }

    @Test
    void lowScoreEscalatesToActiveStage() {
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        engine.pushFrame(sid, frame());
        FrameAssessment a = engine.pushFrame(sid, frame());

        assertEquals(AssessmentStatus.ESCALATED_TO_ACTIVE, a.status());
        assertTrue(audit.contains(AuditEventType.ESCALATED_TO_ACTIVE));
        assertEquals(SessionSummary.Outcome.ABORTED, engine.closeSession(sid).outcome());
    }

    @Test
    void fullActiveFlowPassesAfterMinChallenges() {
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        pushUntilEscalated(sid);

        ValidationResult r1 = completeOneChallenge(sid);
        assertTrue(r1.passed());
        assertEquals(1, r1.challengesRemaining());

        ValidationResult r2 = completeOneChallenge(sid);
        assertTrue(r2.passed());
        assertEquals(0, r2.challengesRemaining());

        SessionSummary s = engine.closeSession(sid);
        assertEquals(SessionSummary.Outcome.PASSED_ACTIVE, s.outcome());
        assertEquals(2, s.challengesPassed());
        assertTrue(audit.contains(AuditEventType.CHALLENGE_ISSUED));
        assertTrue(audit.contains(AuditEventType.CHALLENGE_PASSED));
    }

    @Test
    void failedChallengeConsumesRetryBudgetThenLocksOut() {
        // Subject never performs any action -> every challenge fails.
        backend.subject().resetPose();
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        pushUntilEscalated(sid);

        // attempt 1 fails -> retry available (maxRetries=1)
        engine.requestChallenge(sid);
        ValidationResult r1 = engine.validateChallenge(sid,
                List.of(frame(), frame(), frame()));
        assertFalse(r1.passed());
        assertFalse(r1.hardFailure());

        // request a new challenge (attempt 2) and fail again -> hard failure
        engine.requestChallenge(sid);
        ValidationResult r2 = engine.validateChallenge(sid, neutralFrames());
        assertFalse(r2.passed());
        assertTrue(r2.hardFailure());

        SessionSummary s = engine.closeSession(sid);
        assertEquals(SessionSummary.Outcome.FAILED_MAX_RETRIES_LOCKED_OUT, s.outcome());
        assertTrue(audit.contains(AuditEventType.MAX_RETRIES_EXCEEDED));
        assertTrue(audit.contains(AuditEventType.REPEATED_FAILURE_ACTION));
    }

    @Test
    void challengeTimeoutConsumesRetryBudgetAndEventuallyFails() {
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        pushUntilEscalated(sid);

        engine.requestChallenge(sid);
        clock.advanceMillis(6_000);          // past the 5s deadline

        // Next interaction must observe the timeout
        FrameAssessment during = engine.pushFrame(sid, frame());
        // timeout consumed retry #1; session moved to ESCALATED for next attempt
        assertEquals(AssessmentStatus.ESCALATED_TO_ACTIVE, during.status());
        assertTrue(audit.contains(AuditEventType.CHALLENGE_TIMEOUT));

        engine.requestChallenge(sid);
        clock.advanceMillis(6_000);
        engine.pushFrame(sid, frame());      // second timeout -> budget exhausted

        SessionSummary s = engine.closeSession(sid);
        assertEquals(SessionSummary.Outcome.FAILED_MAX_RETRIES_LOCKED_OUT, s.outcome());
    }

    @Test
    void fallbackActionReflectedInSummary() {
        FaceLivenessEngine fallbackEngine = new FaceLivenessEngine(
                LivenessConfig.builder()
                        .passiveMinFrames(1)
                        .maxRetries(0)
                        .onRepeatedFailure(io.mosip.liveness.core.RepeatedFailureAction.FALLBACK)
                        .build(),
                backend, audit, new MetricsCollector(), clock);
        String sid = fallbackEngine.initSession(io.mosip.liveness.core.WorkflowType.SUPERVISOR_AUTH);
        fallbackEngine.pushFrame(sid, frame());
        fallbackEngine.requestChallenge(sid);
        ValidationResult r = fallbackEngine.validateChallenge(sid, List.of(frame(), frame()));
        assertTrue(r.hardFailure());
        assertEquals(SessionSummary.Outcome.FAILED_MAX_RETRIES_FALLBACK,
                fallbackEngine.closeSession(sid).outcome());
    }

    @Test
    void requestingChallengeBeforeEscalationIsInvalidState() {
        backend.subject().livenessScore = 0.95;
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        assertThrows(io.mosip.liveness.core.LivenessException.class,
                () -> engine.requestChallenge(sid));
    }

    @Test
    void validatingWithoutOpenChallengeIsInvalidState() {
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        pushUntilEscalated(sid);
        assertThrows(io.mosip.liveness.core.LivenessException.class,
                () -> engine.validateChallenge(sid, List.of(frame())));
    }

    private void pushUntilEscalated(String sid) {
        for (int i = 0; i < 5; i++) {
            FrameAssessment a = engine.pushFrame(sid, frame());
            if (a.status() == AssessmentStatus.ESCALATED_TO_ACTIVE) return;
        }
        throw new AssertionError("never escalated");
    }

    /** Requests + validates one challenge with a genuine performance. */
    private ValidationResult completeOneChallenge(String sid) {
        Challenge c = engine.requestChallenge(sid);
        return engine.validateChallenge(sid, framesFor(c));
    }

    /** Neutral signals (eyes open, no smile, no yaw, gaze center) satisfy no challenge. */
    private List<Frame> neutralFrames() {
        return List.of(frame(), frame(), frame());
    }
}
