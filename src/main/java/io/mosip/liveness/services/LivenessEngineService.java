package io.mosip.liveness.services;

import io.mosip.liveness.models.enums.ChallengeType;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Face liveness engine service (passive scoring + active challenge validation).
 * Maps to the Python framework's liveness_engine.py.
 */
@Service
public class LivenessEngineService {

    /**
     * Observe face in frame: detect face, check quality, return observation.
     */
    public FaceObservation observeFace(Mat frame, ImageUtils imageUtils) {
        Rect[] faces = imageUtils.detectFaces(frame);
        if (faces.length == 0) {
            return new FaceObservation(false, false, null, null);
        }
        if (faces.length > 1) {
            return new FaceObservation(true, true, null, null);
        }
        Rect face = faces[0];
        double quality = 0.5 * imageUtils.sharpnessScore(frame) + 0.5 * imageUtils.brightnessScore(frame);
        return new FaceObservation(true, false, quality,
                new int[]{face.x, face.y, face.width, face.height});
    }

    /**
     * Score passive liveness for a frame (higher = more alive).
     */
    public double scorePassive(Mat frame, FaceObservation observation, ImageUtils imageUtils) {
        if (!observation.faceDetected() || observation.multipleFaces()) {
            return 0.0;
        }
        int[] bbox = observation.bbox();
        Rect roi = new Rect(bbox[0], bbox[1], bbox[2], bbox[3]);
        Mat gray = new Mat();
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
        Mat faceRoi = gray.submat(roi);
        int eyeCount = imageUtils.detectEyes(faceRoi).length;
        double eyeSymmetryScore = eyeCount >= 2 ? 1.0 : 0.4;
        double score = 0.4 * (observation.faceQuality() != null ? observation.faceQuality() : 0.0)
                + 0.4 * eyeSymmetryScore
                + 0.2 * imageUtils.sharpnessScore(frame);
        gray.release();
        faceRoi.release();
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Validate active challenge (blink, smile, turn, etc.) across a sequence of frames.
     */
    public boolean validateActive(ChallengeType challengeType, List<Mat> frames, ImageUtils imageUtils) {
        if (frames.size() < 2) return false;

        // Get bounding boxes for all frames
        List<int[]> boxes = new ArrayList<>();
        for (Mat f : frames) {
            FaceObservation obs = observeFace(f, imageUtils);
            if (obs.bbox() == null) return false;
            boxes.add(obs.bbox());
        }

        return switch (challengeType) {
            case BLINK -> checkBlink(frames, boxes, imageUtils);
            case TURN_LEFT -> {
                double delta = boxes.get(boxes.size() - 1)[0] - boxes.get(0)[0];
                yield delta < -15;
            }
            case TURN_RIGHT -> {
                double delta = boxes.get(boxes.size() - 1)[0] - boxes.get(0)[0];
                yield delta > 15;
            }
            case SMILE -> checkSmile(frames, boxes, imageUtils);
            default -> {
                double delta = boxes.get(boxes.size() - 1)[0] - boxes.get(0)[0];
                yield Math.abs(delta) > 10;
            }
        };
    }

    private boolean checkBlink(List<Mat> frames, List<int[]> boxes, ImageUtils imageUtils) {
        boolean hadClosed = false;
        for (int i = 0; i < frames.size(); i++) {
            int[] b = boxes.get(i);
            Rect roi = new Rect(b[0], b[1], b[2], b[3]);
            Mat gray = new Mat();
            Imgproc.cvtColor(frames.get(i), gray, Imgproc.COLOR_BGR2GRAY);
            Mat faceRoi = gray.submat(roi);
            int eyeCount = imageUtils.detectEyes(faceRoi).length;
            gray.release();
            faceRoi.release();
            if (eyeCount == 0) {
                hadClosed = true;
            } else if (hadClosed) {
                return true;
            }
        }
        return false;
    }

    private boolean checkSmile(List<Mat> frames, List<int[]> boxes, ImageUtils imageUtils) {
        double minVar = Double.MAX_VALUE;
        double maxVar = Double.MIN_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            int[] b = boxes.get(i);
            int mouthY = b[1] + (int)(b[3] * 0.6);
            int mouthH = (int)(b[3] * 0.4);
            if (mouthY + mouthH > frames.get(i).rows()) continue;

            Mat gray = new Mat();
            Imgproc.cvtColor(frames.get(i), gray, Imgproc.COLOR_BGR2GRAY);
            Mat mouthRoi = gray.submat(new Rect(b[0], mouthY, b[2], mouthH));
            Mat laplacian = new Mat();
            Imgproc.Laplacian(mouthRoi, laplacian, CvType.CV_64F);
            MatOfDouble stddev = new MatOfDouble();
            Core.meanStdDev(laplacian, new MatOfDouble(), stddev);
            double variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
            minVar = Math.min(minVar, variance);
            maxVar = Math.max(maxVar, variance);
            gray.release();
            mouthRoi.release();
            laplacian.release();
            stddev.release();
        }
        return (maxVar - minVar) > 50;
    }

    // ---- observation data class ----

    public record FaceObservation(
            boolean faceDetected,
            boolean multipleFaces,
            Double faceQuality,
            int[] bbox  // [x, y, w, h]
    ) {}
}
