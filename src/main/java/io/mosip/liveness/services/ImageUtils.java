package io.mosip.liveness.services;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * Shared helpers for decoding and quality-checking incoming frames.
 * Maps to the Python framework's image_utils.py.
 *
 * <p>Haar cascade data files are resolved from the classpath first (drop them in
 * {@code src/main/resources/}) and then from the filesystem, so the same build
 * works when the service is launched from any working directory.</p>
 */
@Service
public class ImageUtils {

    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    static final String FACE_CASCADE = "haarcascade_frontalface_default.xml";
    static final String EYE_CASCADE = "haarcascade_eye.xml";

    // Lazy-load cascades so Mockito can proxy this class without triggering native lib loading
    private volatile CascadeClassifier faceCascade;
    private volatile CascadeClassifier eyeCascade;

    private CascadeClassifier getFaceCascade() {
        if (faceCascade == null) {
            synchronized (this) {
                if (faceCascade == null) {
                    faceCascade = loadCascade(FACE_CASCADE);
                }
            }
        }
        return faceCascade;
    }

    private CascadeClassifier getEyeCascade() {
        if (eyeCascade == null) {
            synchronized (this) {
                if (eyeCascade == null) {
                    eyeCascade = loadCascade(EYE_CASCADE);
                }
            }
        }
        return eyeCascade;
    }

    /**
     * Resolve a Haar cascade from the classpath, then the filesystem.
     *
     * <p>These XML files are not shipped with OpenCV's Java bindings, so a build
     * without them cannot detect faces and every frame will be reported as
     * "no face detected". An empty classifier is returned in that case (rather
     * than throwing) so the rest of the service still serves traffic.</p>
     */
    private CascadeClassifier loadCascade(String resourceName) {
        // 1) classpath — the preferred location; survives packaging and any CWD
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in != null) {
                Path tmp = Files.createTempFile("cascade-", ".xml");
                try {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    // CascadeClassifier reads the whole document at construction,
                    // so the temp file can be removed immediately afterwards.
                    CascadeClassifier classifier = new CascadeClassifier(tmp.toString());
                    if (!classifier.empty()) {
                        log.debug("Loaded cascade '{}' from classpath", resourceName);
                        return classifier;
                    }
                } finally {
                    // Best-effort: on Windows a file still held open cannot be
                    // removed, which must not turn a successful load into a failure.
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception ignored) {
                        tmp.toFile().deleteOnExit();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load cascade '{}' from the classpath", resourceName, e);
        }

        // 2) filesystem — absolute path, or relative to the current working directory
        try {
            CascadeClassifier classifier = new CascadeClassifier(resourceName);
            if (!classifier.empty()) {
                log.debug("Loaded cascade '{}' from the filesystem", resourceName);
                return classifier;
            }
        } catch (Exception e) {
            log.warn("Failed to load cascade '{}' from the filesystem", resourceName, e);
        }

        log.warn("Haar cascade '{}' was not found on the classpath or filesystem. Face "
                + "detection is DISABLED, so no frame can pass passive liveness. Add the "
                + "file to src/main/resources/ (OpenCV data directory) to enable it.",
                resourceName);
        return new CascadeClassifier(); // empty; .empty() returns true
    }

    /**
     * True when both Haar cascades loaded and face/eye detection is usable.
     * Package-private so tests can assert the cascade data is present.
     */
    boolean cascadesAvailable() {
        CascadeClassifier face = getFaceCascade();
        CascadeClassifier eye = getEyeCascade();
        return face != null && !face.empty() && eye != null && !eye.empty();
    }

    public static class InvalidFrameError extends RuntimeException {
        public InvalidFrameError(String message) {
            super(message);
        }
    }

    public Mat decodeBase64Frame(String frameBase64) {
        try {
            if (frameBase64.length() > 64 && frameBase64.contains(",")) {
                frameBase64 = frameBase64.substring(frameBase64.indexOf(',') + 1);
            }
            byte[] raw = Base64.getDecoder().decode(frameBase64);
            if (raw.length == 0) {
                throw new InvalidFrameError("Empty frame payload");
            }
            MatOfByte matOfByte = new MatOfByte(raw);
            Mat image = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) {
                throw new InvalidFrameError("Frame could not be decoded as an image");
            }
            return image;
        } catch (InvalidFrameError e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFrameError("Frame is not valid base64 data: " + e.getMessage());
        }
    }

    public Rect[] detectFaces(Mat image) {
        CascadeClassifier cascade = getFaceCascade();
        if (cascade == null || cascade.empty()) {
            return new Rect[0];
        }
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        MatOfRect faces = new MatOfRect();
        cascade.detectMultiScale(gray, faces, 1.1, 5, 0, new Size(60, 60));
        Rect[] result = faces.toArray();
        gray.release();
        return result;
    }

    public Rect[] detectEyes(Mat faceGrayRoi) {
        CascadeClassifier cascade = getEyeCascade();
        if (cascade == null || cascade.empty()) {
            return new Rect[0];
        }
        MatOfRect eyes = new MatOfRect();
        cascade.detectMultiScale(faceGrayRoi, eyes, 1.1, 8);
        return eyes.toArray();
    }

    public double sharpnessScore(Mat image) {
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        Mat laplacian = new Mat();
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(laplacian, new MatOfDouble(), stddev);
        double variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
        gray.release();
        laplacian.release();
        stddev.release();
        return Math.max(0.0, Math.min(1.0, variance / 500.0));
    }

    public double brightnessScore(Mat image) {
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        Scalar mean = Core.mean(gray);
        double meanVal = mean.val[0];
        gray.release();
        return Math.max(0.0, Math.min(1.0, 1.0 - Math.abs(meanVal - 128.0) / 128.0));
    }
}
