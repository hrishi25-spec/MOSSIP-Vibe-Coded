package io.mosip.liveness.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Haar cascade data files.
 *
 * <p>OpenCV's Java bindings do not ship these XMLs. When they are missing the
 * service does not fail loudly — face detection simply returns nothing and every
 * frame is reported as "no face detected", so no session can ever pass. These
 * tests fail instead of letting that regress silently.</p>
 */
class ImageUtilsCascadeTest {

    @BeforeAll
    static void loadOpenCvNatives() {
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t) {
            Assumptions.assumeTrue(false,
                    "OpenCV native library unavailable on this platform: " + t);
        }
    }

    @Test
    void cascadeFilesAreOnTheClasspath() {
        ClassLoader cl = ImageUtilsCascadeTest.class.getClassLoader();
        assertNotNull(cl.getResourceAsStream(ImageUtils.FACE_CASCADE),
                ImageUtils.FACE_CASCADE + " must be bundled in src/main/resources/");
        assertNotNull(cl.getResourceAsStream(ImageUtils.EYE_CASCADE),
                ImageUtils.EYE_CASCADE + " must be bundled in src/main/resources/");
    }

    @Test
    void cascadesLoadIntoUsableClassifiers() {
        ImageUtils utils = new ImageUtils();
        assertTrue(utils.cascadesAvailable(),
                "Haar cascades did not load; face detection would be silently disabled");
    }

    // ---- frame hardening: payload/dimension caps and analysis downscale ----

    @Test
    void oversizedFramePayload_isRejected() {
        ImageUtils utils = new ImageUtils();
        byte[] bomb = new byte[ImageUtils.MAX_FRAME_BYTES + 1];
        String b64 = Base64.getEncoder().encodeToString(bomb);
        assertThrows(ImageUtils.InvalidFrameError.class, () -> utils.decodeBase64Frame(b64),
                "payloads over MAX_FRAME_BYTES must be rejected before decode");
    }

    @Test
    void decompressionBomb_isRejectedByDimensionCap() {
        ImageUtils utils = new ImageUtils();
        String b64 = encodePng(Mat.zeros(9000, 200, CvType.CV_8UC3));
        assertThrows(ImageUtils.InvalidFrameError.class, () -> utils.decodeBase64Frame(b64),
                "an image wider than MAX_FRAME_DIMENSION must be rejected");
    }

    @Test
    void largeFrame_isDownscaledToAnalysisCap() {
        ImageUtils utils = new ImageUtils();
        String b64 = encodePng(Mat.zeros(1080, 1920, CvType.CV_8UC3));
        Mat decoded = utils.decodeBase64Frame(b64);
        try {
            assertEquals(ImageUtils.ANALYSIS_MAX_DIMENSION, decoded.cols(),
                    "1920px-wide input must be scaled to the analysis cap");
            assertEquals(720, decoded.rows(),
                    "aspect ratio must be preserved by the downscale");
        } finally {
            decoded.release();
        }
    }

    @Test
    void smallFrame_passesThroughUnchanged() {
        ImageUtils utils = new ImageUtils();
        String b64 = encodePng(Mat.zeros(480, 640, CvType.CV_8UC3));
        Mat decoded = utils.decodeBase64Frame(b64);
        try {
            assertEquals(640, decoded.cols(),
                    "frames at or below the analysis cap must not be resized");
            assertEquals(480, decoded.rows());
        } finally {
            decoded.release();
        }
    }

    private static String encodePng(Mat mat) {
        MatOfByte buf = new MatOfByte();
        try {
            Imgcodecs.imencode(".png", mat, buf);
            return Base64.getEncoder().encodeToString(buf.toArray());
        } finally {
            buf.release();
            mat.release();
        }
    }
}
