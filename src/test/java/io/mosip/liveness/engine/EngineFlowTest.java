package io.mosip.liveness.engine;

import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.testing.MutableClock;
import io.mosip.liveness.testing.RecordingAuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFlowTest {

    private MockLivenessBackend backend;
    private RecordingAuditLogger audit;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        audit = new RecordingAuditLogger();
        clock = new MutableClock(1_760_000_000_000L);
    }

    private FaceLivenessEngine newEngine(LivenessConfig config) {
        return new FaceLivenessEngine(config, backend, audit, new MetricsCollector(), clock);
    }

    private static Frame frame(int seq) {
        return Frame.of(new byte[64], 8, 8, Frame.Format.RGB_GRAY, 0, seq);
    }

    @Test
    void passivePassWhenMedianAboveThreshold() {
        backend.subject().livenessScore = 0.95;
        backend.subject().scoreNoise = 0.0;
        FaceLivenessEngine engine = newEngine(LivenessConfig.builder()
                .passiveMinFrames(3).build());

        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        FrameAssessment a1 = engine.pushFrame(sid, frame(1));
        assertEquals(AssessmentStatus.SCORING, a1.status());
        assertEquals(0.95, a1.livenessScore(), 1e-9);

        engine.pushFrame(sid, frame(2));
        FrameAssessment a3 = engine.pushFrame(sid, frame(3));
        assertEquals(AssessmentStatus.PASSED, a3.status());

        SessionSummary summary = engine.closeSession(sid);
        assertEquals(SessionSummary.Outcome.PASSED_PASSIVE, summary.outcome());
        assertTrue(summary.passed());
        assertFalse(summary.escalatedToActive());
        assertTrue(audit.contains(AuditEventType.PASSIVE_PASSED));
    }

    @Test
    void additionalFramesAfterPassAreIdempotent() {
        backend.subject().livenessScore = 0.95;
        backend.subject().scoreNoise = 0.0;
        FaceLivenessEngine engine = newEngine(LivenessConfig.builder().passiveMinFrames(2).build());
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);

        engine.pushFrame(sid, frame(1));
        engine.pushFrame(sid, frame(2));
        FrameAssessment extra = engine.pushFrame(sid, frame(3));
        assertEquals(AssessmentStatus.PASSED, extra.status());
    }

    @Test
    void livenessDisabledBypassesScoring() {
        FaceLivenessEngine engine = newEngine(LivenessConfig.builder()
                .livenessEnabled(false).build());
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.OPERATOR_AUTH);
        FrameAssessment a = engine.pushFrame(sid, frame(1));

        assertEquals(AssessmentStatus.PASSED, a.status());
        SessionSummary summary = engine.closeSession(sid);
        assertEquals(SessionSummary.Outcome.BYPASSED, summary.outcome());
    }

    @Test
    void summaryTracksCounters() {
        backend.subject().livenessScore = 0.95;
        backend.subject().scoreNoise = 0.0;
        FaceLivenessEngine engine = newEngine(LivenessConfig.builder().passiveMinFrames(4).build());
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.SUPERVISOR_AUTH);
        for (int i = 1; i <= 5; i++) {
            engine.pushFrame(sid, frame(i));
        }
        SessionSummary s = engine.closeSession(sid);
        assertEquals(0, s.challengesIssued());
        assertTrue(s.durationMs() >= 0);
        assertEquals(0.95, s.finalMedianScore(), 1e-9);
    }
}
