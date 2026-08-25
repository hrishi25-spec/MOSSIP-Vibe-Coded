package io.mosip.liveness.engine;

import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.testing.MutableClock;
import io.mosip.liveness.testing.RecordingAuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadBlockingTest {

    private MockLivenessBackend backend;
    private RecordingAuditLogger audit;
    private FaceLivenessEngine engine;
    private int seq;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        audit = new RecordingAuditLogger();
        engine = new FaceLivenessEngine(LivenessConfig.builder().passiveMinFrames(2).build(),
                backend, audit, new MetricsCollector(), new MutableClock(1_760_000_000_000L));
        // High liveness score: proves PAD blocks even when quality/liveness look great.
        backend.subject().livenessScore = 0.97;
        backend.subject().scoreNoise = 0.0;
    }

    private Frame frame() {
        return Frame.of(new byte[64], 8, 8, Frame.Format.RGB_GRAY, 0, seq++);
    }

    @Test
    void padHitBlocksImmediatelyEvenWithPerfectScore() {
        backend.subject().attack(PadAttackType.PRINTED_PHOTO, 0.98);
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);

        FrameAssessment a = engine.pushFrame(sid, frame());
        assertEquals(AssessmentStatus.PAD_BLOCKED, a.status());
        assertTrue(a.padFlagged());
        assertEquals(PadAttackType.PRINTED_PHOTO, a.padAttackType());

        SessionSummary s = engine.closeSession(sid);
        assertEquals(SessionSummary.Outcome.PAD_BLOCKED, s.outcome());
        assertEquals(PadAttackType.PRINTED_PHOTO, s.padAttackType());
        assertFalse(s.passed());
    }

    @Test
    void padFailureIsTerminalNoRetryPath() {
        backend.subject().attack(PadAttackType.SCREEN_REPLAY, 0.97);
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        engine.pushFrame(sid, frame());

        LivenessException ex = assertThrows(LivenessException.class,
                () -> engine.pushFrame(sid, frame()));
        assertEquals(LivenessErrorCode.INVALID_STATE, ex.errorCode());
    }

    @Test
    void userMessageIsGenericNeverRevealsDetectionMethod() {
        backend.subject().attack(PadAttackType.VIDEO_REPLAY, 0.99);
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        FrameAssessment a = engine.pushFrame(sid, frame());

        assertNotNull(a.userMessage());
        String msg = a.userMessage().toLowerCase();
        for (String leak : new String[]{"printed", "replay", "screen", "photo", "attack", "spoof", "pad"}) {
            assertTrue(!msg.contains(leak), "user message leaked detection method: " + msg);
        }
        assertEquals(LivenessErrorCode.PAD_FAILURE, a.errorCode());
    }

    @Test
    void padEventIsDistinctFromLivenessMissInAudit() {
        backend.subject().attack(PadAttackType.PRINTED_PHOTO, 0.95);
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        engine.pushFrame(sid, frame());

        assertTrue(audit.contains(AuditEventType.PAD_BLOCKED));
        assertEquals(1, audit.count(AuditEventType.PAD_BLOCKED));
        assertEquals(0, audit.count(AuditEventType.LIVENESS_FAILED),
                "PAD failure must not be logged as a liveness-quality miss");
        var evt = audit.events().stream()
                .filter(e -> e.type() == AuditEventType.PAD_BLOCKED).findFirst().orElseThrow();
        assertEquals("PRINTED_PHOTO", evt.fields().get("attackType"));
    }

    @Test
    void padDuringActiveChallengeAlsoBlocks() throws Exception {
        // First pass passively to reach challenge flow? No — force escalation with low score,
        // then flip PAD on mid-challenge.
        MockLivenessBackend b2 = new MockLivenessBackend();
        RecordingAuditLogger a2 = new RecordingAuditLogger();
        FaceLivenessEngine e2 = new FaceLivenessEngine(
                LivenessConfig.builder().passiveMinFrames(1).minChallengeCount(1).build(),
                b2, a2, new MetricsCollector(), new MutableClock(1_760_000_000_000L));
        b2.subject().livenessScore = 0.30;
        b2.subject().scoreNoise = 0.0;

        String sid = e2.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        FrameAssessment esc = e2.pushFrame(sid, frame());
        assertEquals(AssessmentStatus.ESCALATED_TO_ACTIVE, esc.status());
        e2.requestChallenge(sid);

        b2.subject().resetPose();
        b2.subject().smileBig();   // would satisfy SMILE if issued
        b2.subject().blink();      // would satisfy BLINK
        b2.subject().attack(PadAttackType.VIDEO_REPLAY, 0.93);

        List<Frame> frames = List.of(frame(), frame());
        ValidationResult r = e2.validateChallenge(sid, frames);
        assertFalse(r.passed());
        assertTrue(r.hardFailure());
        assertTrue(a2.contains(AuditEventType.PAD_BLOCKED));
        assertEquals(SessionSummary.Outcome.PAD_BLOCKED, e2.closeSession(sid).outcome());
    }
}
