package io.mosip.liveness.engine;

import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErrorConditionsTest {

    private MockLivenessBackend backend;
    private FaceLivenessEngine engine;
    private int seq;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        engine = new FaceLivenessEngine(LivenessConfig.builder().passiveMinFrames(3).build(),
                backend, io.mosip.liveness.audit.AuditLogger.noop(),
                new io.mosip.liveness.audit.MetricsCollector(), new MutableClock(1_760_000_000_000L));
        backend.subject().livenessScore = 0.95;
        backend.subject().scoreNoise = 0.0;
    }

    private Frame frame() {
        return Frame.of(new byte[64], 8, 8, Frame.Format.RGB_GRAY, 0, seq++);
    }

    @Test
    void nullFramePayloadRejected() {
        assertThrows(LivenessException.class,
                () -> Frame.of(null, 8, 8, Frame.Format.RGB_GRAY, 0, 1));
        LivenessException ex = assertThrows(LivenessException.class,
                () -> Frame.of(new byte[0], 8, 8, Frame.Format.RGB_GRAY, 0, 1));
        assertEquals(LivenessErrorCode.INVALID_FRAME_DATA, ex.errorCode());
    }

    @Test
    void invalidDimensionsRejected() {
        LivenessException ex = assertThrows(LivenessException.class,
                () -> Frame.of(new byte[64], 0, 8, Frame.Format.RGB_GRAY, 0, 1));
        assertEquals(LivenessErrorCode.INVALID_FRAME_DATA, ex.errorCode());
    }

    @Test
    void unknownSessionRejected() {
        LivenessException ex = assertThrows(LivenessException.class,
                () -> engine.pushFrame("nope", frame()));
        assertEquals(LivenessErrorCode.INVALID_SESSION, ex.errorCode());

        assertThrows(LivenessException.class, () -> engine.closeSession("nope"));
    }

    @Test
    void closedSessionBecomesInvalid() {
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        engine.closeSession(sid);
        assertThrows(LivenessException.class, () -> engine.pushFrame(sid, frame()));
    }

    @Test
    void faceNotDetectedIsRetryableNotFatal() {
        backend.subject().facePresent = false;
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        FrameAssessment a = engine.pushFrame(sid, frame());
        assertEquals(AssessmentStatus.RETRYABLE_ERROR, a.status());
        assertEquals(LivenessErrorCode.FACE_NOT_DETECTED, a.errorCode());

        // recover on the next frame
        backend.subject().facePresent = true;
        assertEquals(AssessmentStatus.SCORING, engine.pushFrame(sid, frame()).status());
    }

    @Test
    void multipleFacesRejected() {
        backend.subject().faceCount = 2;
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        FrameAssessment a = engine.pushFrame(sid, frame());
        assertEquals(AssessmentStatus.RETRYABLE_ERROR, a.status());
        assertEquals(LivenessErrorCode.MULTIPLE_FACES_DETECTED, a.errorCode());
    }

    @Test
    void poorFaceQualityRejected() {
        backend.subject().quality = 0.2;   // below default minFaceQuality 0.5
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        FrameAssessment a = engine.pushFrame(sid, frame());
        assertEquals(AssessmentStatus.RETRYABLE_ERROR, a.status());
        assertEquals(LivenessErrorCode.POOR_FACE_QUALITY, a.errorCode());
    }

    @Test
    void emptyChallengeFrameSetRejected() {
        backend.subject().livenessScore = 0.3;
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);
        engine.pushFrame(sid, frame());   // escalates (passiveMinFrames=3? no)
        // ensure escalation with passiveMinFrames=3 needs 3 frames
        engine.pushFrame(sid, frame());
        FrameAssessment a = engine.pushFrame(sid, frame());
        assertEquals(AssessmentStatus.ESCALATED_TO_ACTIVE, a.status());
        engine.requestChallenge(sid);
        LivenessException ex = assertThrows(LivenessException.class,
                () -> engine.validateChallenge(sid, List.of()));
        assertEquals(LivenessErrorCode.INVALID_FRAME_DATA, ex.errorCode());
    }

    @Test
    void backendInitializationFailureMapsToDeviceConnectionError() {
        MockLivenessBackend bad = new MockLivenessBackend() {
            @Override
            public void initialize(java.util.Map<String, String> options) {
                throw new IllegalStateException("model load failed");
            }
        };
        LivenessException ex = assertThrows(LivenessException.class,
                () -> new FaceLivenessEngine(LivenessConfig.builder().build(), bad));
        assertEquals(LivenessErrorCode.DEVICE_CONNECTION_FAILURE, ex.errorCode());
    }

    @Test
    void backendRuntimeFailureMapsToEngineInternalError() {
        MockLivenessBackend broken = new MockLivenessBackend() {
            @Override
            public io.mosip.liveness.core.FaceSignals analyzeFrame(Frame f) {
                throw new NullPointerException("boom");
            }
        };
        FaceLivenessEngine e = new FaceLivenessEngine(
                LivenessConfig.builder().build(), broken,
                io.mosip.liveness.audit.AuditLogger.noop(),
                new io.mosip.liveness.audit.MetricsCollector(), new MutableClock(1));
        String sid = e.initSession(io.mosip.liveness.core.WorkflowType.OPERATOR_AUTH);
        LivenessException ex = assertThrows(LivenessException.class, () -> e.pushFrame(sid, frame()));
        assertEquals(LivenessErrorCode.ENGINE_INTERNAL_ERROR, ex.errorCode());
    }
}
