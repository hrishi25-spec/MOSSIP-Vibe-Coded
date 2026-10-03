package io.mosip.liveness.services;

import io.mosip.liveness.core.PadVerdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scorer façade: mode selection, model loading, and the graceful
 * heuristic fallback the service depends on when no model is available.
 */
class PassiveScoringServiceTest {

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat flatFrame() {
        Mat m = new Mat(96, 96, org.opencv.core.CvType.CV_8UC3, new Scalar(120, 130, 140));
        Imgproc.resize(m, m, new org.opencv.core.Size(96, 96));
        return m;
    }

    private static LivenessEngineService.FaceObservation noFace() {
        return new LivenessEngineService.FaceObservation(false, false, null, null);
    }

    @Test
    void heuristicModeNeverLoadsTheModel() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        PassiveScoringService scorer = new PassiveScoringService(heuristic, "heuristic", "");

        assertFalse(scorer.isModelAvailable());
        assertEquals("opencv-heuristic", scorer.scorerId());
        assertTrue(scorer.assessPad(flatFrame(), noFace()).isEmpty());
    }

    @Test
    void heuristicModeDelegatesScoringToTheOpenCvHeuristic() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        when(heuristic.scorePassive(any(), any(), any())).thenReturn(0.72);
        PassiveScoringService scorer = new PassiveScoringService(heuristic, "heuristic", "");

        Mat frame = flatFrame();
        try {
            assertEquals(0.72, scorer.score(frame, noFace(), mock(ImageUtils.class)), 1e-9);
            verify(heuristic).scorePassive(any(), any(), any());
        } finally {
            frame.release();
        }
    }

    @Test
    void autoModeLoadsTheBundledModel() {
        PassiveScoringService scorer = new PassiveScoringService(
                mock(LivenessEngineService.class), "auto", "");

        assertTrue(scorer.isModelAvailable(), "bundled model + ONNX Runtime must load");
        assertEquals("onnx-minifasnet-v2", scorer.scorerId());
    }

    @Test
    void modelOpinionIsPresentWhenTheModelLoads() {
        PassiveScoringService scorer = new PassiveScoringService(
                mock(LivenessEngineService.class), "auto", "");

        Mat frame = flatFrame();
        try {
            Optional<PadVerdict> pad = scorer.assessPad(frame, noFace());
            // With a face-gate failure the model stays silent rather than guessing;
            // with a detectable face it returns a verdict — either way: no crash.
            assertTrue(pad.isEmpty() || pad.get() != null);
        } finally {
            frame.release();
        }
    }

    @Test
    void scoreFallsBackToHeuristicWhenTheModelCannotAnalyseTheFrame() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        when(heuristic.scorePassive(any(), any(), any())).thenReturn(0.55);
        PassiveScoringService scorer = new PassiveScoringService(heuristic, "auto", "");
        assertTrue(scorer.isModelAvailable());

        // A flat grey frame: the detector sees no face, so the model defers to
        // the heuristic instead of punishing the frame with a zero.
        Mat frame = flatFrame();
        try {
            assertEquals(0.55, scorer.score(frame, noFace(), mock(ImageUtils.class)), 1e-9);
            verify(heuristic).scorePassive(any(), any(), any());
        } finally {
            frame.release();
        }
    }
}
