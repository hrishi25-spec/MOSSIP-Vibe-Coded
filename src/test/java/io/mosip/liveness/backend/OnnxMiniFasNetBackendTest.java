package io.mosip.liveness.backend;

import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadVerdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled MiniFASNet ONNX backend: provenance (checksum), load/unload,
 * face-count gating and a full inference smoke test.
 */
class OnnxMiniFasNetBackendTest {

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Frame noiseFrame() {
        byte[] data = new byte[96 * 96 * 3];
        new Random(42).nextBytes(data);
        return Frame.of(data, 96, 96, Frame.Format.RGB_888, System.currentTimeMillis(), 1);
    }

    @Test
    void bundledModelMatchesItsPublishedSha256() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(OnnxMiniFasNetBackend.DEFAULT_MODEL_RESOURCE)) {
            assertNotNull(in, "bundled model must exist on the classpath");
            byte[] model = in.readAllBytes();
            assertEquals(1_744_116, model.length, "size published in the model card");
            assertEquals(OnnxMiniFasNetBackend.DEFAULT_MODEL_SHA256,
                    OnnxMiniFasNetBackend.sha256(model));
        }
    }

    @Test
    void bundledModelInitializesAndReportsReady() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        assertTrue(OnnxMiniFasNetBackend.isRuntimeAvailable(), "ONNX Runtime must be on the classpath");
        backend.initialize(Map.of());
        try {
            assertTrue(backend.isReady());
            assertEquals("onnx-minifasnet-v2", backend.id());
        } finally {
            backend.shutdown();
        }
        assertTrue(!backend.isReady(), "shutdown must clear readiness");
    }

    @Test
    void missingModelPathFailsWithModelIntegrity() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        LivenessException ex = assertThrows(LivenessException.class,
                () -> backend.initialize(Map.of(
                        OnnxMiniFasNetBackend.OPTION_MODEL_PATH, "/nonexistent/model.onnx")));
        assertEquals(LivenessErrorCode.MODEL_INTEGRITY, ex.errorCode());
    }

    @Test
    void invalidModelSourceFailsWithModelIntegrity() throws Exception {
        // A file that is definitely not the published model.
        java.nio.file.Path bogus = java.nio.file.Files.createTempFile("bogus", ".onnx");
        java.nio.file.Files.writeString(bogus, "not a model at all");
        try {
            OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
            LivenessException ex = assertThrows(LivenessException.class, () -> backend.initialize(Map.of(
                    OnnxMiniFasNetBackend.OPTION_MODEL_RESOURCE, bogus.toString(),
                    OnnxMiniFasNetBackend.OPTION_EXPECTED_SHA256,
                    OnnxMiniFasNetBackend.DEFAULT_MODEL_SHA256)));
            assertEquals(LivenessErrorCode.MODEL_INTEGRITY, ex.errorCode());
        } finally {
            java.nio.file.Files.deleteIfExists(bogus);
        }
    }

    @Test
    void softmaxIsNormalizedAndOrderPreserving() {
        double[] p = OnnxMiniFasNetBackend.softmax(new float[]{1.0f, 3.0f, -2.0f});
        double sum = p[0] + p[1] + p[2];
        assertEquals(1.0, sum, 1e-6);
        assertTrue(p[1] > p[0] && p[0] > p[2]);
    }

    @Test
    void faceCountGatesBothScoreAndPad() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Frame frame = noiseFrame();

            // No face (per the caller's signals) -> no score, no fabricated attack.
            assertEquals(0.0, backend.scorePassiveLiveness(frame, FaceSignals.noFace(0.0)), 1e-12);
            PadVerdict verdict = backend.assessPad(frame, FaceSignals.noFace(0.0));
            assertTrue(!verdict.attackDetected());

            // A frame the detector cannot analyse yields a neutral score in [0,1].
            double score = backend.scorePassiveLiveness(frame,
                    new FaceSignals(1, 0.8, null, null, null, null, null, null, null));
            assertTrue(score >= 0.0 && score <= 1.0, "score out of range: " + score);
        } finally {
            backend.shutdown();
        }
    }

    @Test
    void packNchwKeepsRawByteRangeAndBgrPlanes() {
        // One pixel: B=10, G=128, R=255. The model expects raw 0..255, so the
        // packed floats must be exactly those values (a /255 regression would
        // make these ~0.04/0.5/1.0 and silently break every score).
        float[] nchw = OnnxMiniFasNetBackend.packNchw(new byte[]{10, (byte) 128, (byte) 255}, 1);
        assertEquals(10.0f, nchw[0], 1e-6f, "B plane");
        assertEquals(128.0f, nchw[1], 1e-6f, "G plane");
        assertEquals(255.0f, nchw[2], 1e-6f, "R plane");
    }

    @Test
    void cropRectStaysInsideTheFrameAndKeepsTheMargin() {
        // Centred face with room to spare -> the full 2.7x margin, no clamping.
        Rect centred = OnnxMiniFasNetBackend.cropRect(new Rect(220, 160, 100, 100), 640, 480);
        assertEquals(270, centred.width);
        assertEquals(270, centred.height);

        // Large face on the top edge -> clamped into the frame rather than
        // black-padded (the device-specific SCREEN_REPLAY false-positive cause).
        Rect edge = OnnxMiniFasNetBackend.cropRect(new Rect(180, 0, 300, 300), 640, 480);
        assertTrue(edge.x >= 0 && edge.y >= 0, "crop origin must be inside the frame");
        assertTrue(edge.x + edge.width <= 640, "crop must fit the frame width");
        assertTrue(edge.y + edge.height <= 480, "crop must fit the frame height");
        assertTrue(edge.width >= 300, "crop must not be tighter than the face");
    }

    @Test
    void genuineFaceScoresAsLive() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Mat bgr = Imgcodecs.imread(
                    "src/test/resources/fixtures/real-face.jpg");
            assertTrue(!bgr.empty(), "fixture image must load");
            Mat rgb = new Mat();
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
            rgb.get(0, 0, data);
            Frame frame = Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                    System.currentTimeMillis(), 0);

            FaceSignals signals = backend.analyzeFrame(frame);
            assertEquals(1, signals.faceCount(), "the fixture must contain a detectable face");

            double score = backend.scorePassiveLiveness(frame, signals);
            assertTrue(score > 0.7,
                    "a genuine, well-lit face must score well above chance, was " + score);
            PadVerdict verdict = backend.assessPad(frame, signals);
            assertTrue(!verdict.attackDetected(),
                    "a genuine face must not be flagged as an attack: " + verdict);
        } finally {
            backend.shutdown();
        }
    }

    @Test
    void analyzeFrameReportsDetectedFaceCount() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            FaceSignals signals = backend.analyzeFrame(noiseFrame());
            assertNotNull(signals);
            assertTrue(signals.faceCount() >= 0, "face count must be a plain detection result");
        } finally {
            backend.shutdown();
        }
    }
}
