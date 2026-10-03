package io.mosip.liveness.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
