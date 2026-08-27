package io.mosip.liveness.services;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * Shared helpers for decoding and quality-checking incoming frames.
 * Maps to the Python framework's image_utils.py.
 */
@Service
public class ImageUtils {

    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    // Lazy-load cascades so Mockito can proxy this class without triggering native lib loading
    private volatile CascadeClassifier faceCascade;
    private volatile CascadeClassifier eyeCascade;

    private CascadeClassifier getFaceCascade() {
        if (faceCascade == null) {
            synchronized (this) {
                if (faceCascade == null) {
                    try {
                        faceCascade = new CascadeClassifier("haarcascade_frontalface_default.xml");
                    } catch (Exception e) {
                        log.warn("Could not load face cascade", e);
                        faceCascade = new CascadeClassifier(); // empty, .empty() returns true
                    }
                }
            }
        }
        return faceCascade;
    }

    private CascadeClassifier getEyeCascade() {
        if (eyeCascade == null) {
            synchronized (this) {
                if (eyeCascade == null) {
                    try {
                        eyeCascade = new CascadeClassifier("haarcascade_eye.xml");
                    } catch (Exception e) {
                        log.warn("Could not load eye cascade", e);
                        eyeCascade = new CascadeClassifier();
                    }
                }
            }
        }
        return eyeCascade;
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
