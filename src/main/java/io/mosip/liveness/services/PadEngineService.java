package io.mosip.liveness.services;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.metrics.PipelineTimers;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

/**
 * Presentation Attack Detection (PAD) engine service.
 * Maps to the Python framework's pad_engine.py.
 * Uses cheap OpenCV heuristics as placeholders for a real ISO/IEC 30107-3 model.
 */
@Service
public class PadEngineService {

    private static final double FREQ_ENERGY_ATTACK_THRESHOLD = 0.35;
    private static final double TEXTURE_VARIANCE_ATTACK_THRESHOLD = 15.0;

    /**
     * Analyse a single frame and return a presentation-attack verdict.
     */
    public PadVerdict detect(Mat frame, ImageUtils imageUtils) {
        return PipelineTimers.timed(PipelineTimers.PAD_HEURISTIC, () -> detectInternal(frame, imageUtils));
    }

    private PadVerdict detectInternal(Mat frame, ImageUtils imageUtils) {
        Mat gray = new Mat();
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
        Mat grayFloat = new Mat();
        gray.convertTo(grayFloat, CvType.CV_32F);

        // Screen-replay / moire proxy via high-frequency FFT energy
        Mat fft = new Mat();
        Core.dft(grayFloat, fft);
        Mat magnitude = new Mat();
        Core.magnitude(fft, fft, magnitude);

        // Print-attack proxy via local texture variance
        Mat laplacian = new Mat();
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(laplacian, mean, stddev);
        double textureVariance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];

        // Brightness check
        double brightness = imageUtils.brightnessScore(frame);

        gray.release();
        grayFloat.release();
        fft.release();
        magnitude.release();
        laplacian.release();
        mean.release();
        stddev.release();

        // Evaluate thresholds
        // For FFT-based detection, use a simplified energy ratio
        // In production, this would use proper frequency analysis
        double freqRatio = 0.1; // Placeholder — real FFT analysis goes here

        if (freqRatio > FREQ_ENERGY_ATTACK_THRESHOLD) {
            return PadVerdict.attack(PadAttackType.SCREEN_REPLAY, Math.min(1.0, freqRatio));
        }
        if (textureVariance < TEXTURE_VARIANCE_ATTACK_THRESHOLD) {
            double confidence = Math.min(1.0, 1.0 - (textureVariance / TEXTURE_VARIANCE_ATTACK_THRESHOLD));
            return PadVerdict.attack(PadAttackType.PRINTED_PHOTO, confidence);
        }
        if (brightness < 0.15) {
            return PadVerdict.attack(PadAttackType.VIDEO_REPLAY, 0.6);
        }

        // Bona fide
        double margin = Math.min(1.0,
                (FREQ_ENERGY_ATTACK_THRESHOLD - freqRatio) / FREQ_ENERGY_ATTACK_THRESHOLD);
        return PadVerdict.bonaFide(Math.max(0.5, margin));
    }
}
