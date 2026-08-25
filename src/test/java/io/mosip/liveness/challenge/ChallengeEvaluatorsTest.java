package io.mosip.liveness.challenge;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FaceSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeEvaluatorsTest {

    private static FaceSignals live(double ear, double smile, double yaw, double gazeX, double gazeY) {
        return FaceSignals.live(1, 0.9, ear, smile, yaw, 0, gazeX, gazeY);
    }

    private static boolean eval(ChallengeType type, List<FaceSignals> seq) {
        return ChallengeEvaluators.evaluate(new Challenge(type, 1000, Map.of()), seq);
    }

    @Test
    void blinkPassesWhenEarDipsThenRecovers() {
        assertTrue(eval(ChallengeType.BLINK, List.of(
                live(0.34, 0.05, 0, 0, 0),
                live(0.08, 0.05, 0, 0, 0),
                live(0.34, 0.05, 0, 0, 0))));
    }

    @Test
    void blinkFailsWhenEyeNeverCloses() {
        assertFalse(eval(ChallengeType.BLINK, List.of(
                live(0.34, 0.05, 0, 0, 0),
                live(0.30, 0.05, 0, 0, 0))));
    }

    @Test
    void blinkFailsWhenEyeNeverReopens() {
        assertFalse(eval(ChallengeType.BLINK, List.of(
                live(0.34, 0.05, 0, 0, 0),
                live(0.08, 0.05, 0, 0, 0))));
    }

    @Test
    void smilePassesAboveThreshold() {
        assertTrue(eval(ChallengeType.SMILE, List.of(
                live(0.34, 0.10, 0, 0, 0),
                live(0.30, 0.80, 0, 0, 0))));
    }

    @Test
    void smileFailsOnNeutralFace() {
        assertFalse(eval(ChallengeType.SMILE, List.of(
                live(0.34, 0.10, 0, 0, 0),
                live(0.34, 0.20, 0, 0, 0))));
    }

    @Test
    void headTurnLeftRequiresNegativeYaw() {
        assertTrue(eval(ChallengeType.TURN_HEAD_LEFT, List.of(
                live(0.34, 0, 0, 0, 0), live(0.34, 0, -18, 0, 0))));
        assertFalse(eval(ChallengeType.TURN_HEAD_LEFT, List.of(
                live(0.34, 0, 5, 0, 0), live(0.34, 0, 18, 0, 0))),
                "turning right must not satisfy TURN_HEAD_LEFT");
    }

    @Test
    void headTurnRightRequiresPositiveYaw() {
        assertTrue(eval(ChallengeType.TURN_HEAD_RIGHT, List.of(
                live(0.34, 0, 0, 0, 0), live(0.34, 0, 15, 0, 0))));
        assertFalse(eval(ChallengeType.TURN_HEAD_RIGHT, List.of(
                live(0.34, 0, -15, 0, 0))));
    }

    @Test
    void lookDirectionRequiresSustainedGazeAtTarget() {
        List<FaceSignals> seq = List.of(
                live(0.34, 0, 0, 0, 0),
                live(0.34, 0, 0, 1.0, 0.0),
                live(0.34, 0, 0, 0.9, 0.1),
                live(0.34, 0, 0, 0, 0));
        Challenge c = new Challenge(ChallengeType.LOOK_DIRECTION, 1000, Map.of("dirX", 1.0, "dirY", 0.0));
        assertTrue(ChallengeEvaluators.evaluate(c, seq));
    }

    @Test
    void emptySignalSequenceNeverPasses() {
        for (ChallengeType t : ChallengeType.values()) {
            assertFalse(eval(t, List.of()));
        }
    }
}
