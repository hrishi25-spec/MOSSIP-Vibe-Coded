package io.mosip.liveness.services;

import io.mosip.liveness.models.enums.ChallengeType;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direction polarity for head-turn challenges.
 *
 * <p>Frames reach the engine as raw, un-mirrored camera pixels, where the
 * subject's own left appears on the image's right. A person turning their head to
 * <em>their own left</em> therefore moves the face box toward <b>larger x</b>.</p>
 *
 * <p>These assertions were inverted in the original implementation, so a user
 * following the on-screen "turn left" instruction was measured as turning right
 * and failed. A mirrored (selfie-style) preview is a display concern only and must
 * never change this mapping.</p>
 */
class LivenessEngineServiceTest {

    private final LivenessEngineService engine = new LivenessEngineService();

    /** Runs a turn/gaze challenge over two frames whose face box moves startX -> endX. */
    private boolean movesFaceBox(int startX, int endX, ChallengeType type) {
        ImageUtils utils = mock(ImageUtils.class);
        Mat first = mock(Mat.class);
        Mat last = mock(Mat.class);
        when(utils.detectFaces(first)).thenReturn(new Rect[]{new Rect(startX, 100, 80, 80)});
        when(utils.detectFaces(last)).thenReturn(new Rect[]{new Rect(endX, 100, 80, 80)});
        return engine.validateActive(type, List.of(first, last), utils);
    }

    @Test
    void turningToYourOwnLeft_movesTheFaceBoxTowardLargerX() {
        assertTrue(movesFaceBox(120, 180, ChallengeType.TURN_LEFT),
                "the subject turning to their own left must satisfy TURN_LEFT");
        assertFalse(movesFaceBox(120, 180, ChallengeType.TURN_RIGHT));
    }

    @Test
    void turningToYourOwnRight_movesTheFaceBoxTowardSmallerX() {
        assertTrue(movesFaceBox(180, 120, ChallengeType.TURN_RIGHT),
                "the subject turning to their own right must satisfy TURN_RIGHT");
        assertFalse(movesFaceBox(180, 120, ChallengeType.TURN_LEFT));
    }

    @Test
    void gazeLeftAndRightFollowTheSamePolarity() {
        assertTrue(movesFaceBox(120, 180, ChallengeType.LOOK_LEFT));
        assertTrue(movesFaceBox(180, 120, ChallengeType.LOOK_RIGHT));
    }

    @Test
    void movementBelowThresholdSatisfiesNothing() {
        assertFalse(movesFaceBox(120, 126, ChallengeType.TURN_LEFT));
        assertFalse(movesFaceBox(120, 126, ChallengeType.TURN_RIGHT));
    }

    @Test
    void sidewaysMovementDoesNotSatisfyAVerticalGazeChallenge() {
        assertFalse(movesFaceBox(120, 220, ChallengeType.LOOK_UP));
        assertFalse(movesFaceBox(120, 220, ChallengeType.LOOK_DOWN));
    }

    @Test
    void lookDirectionIsDirectionAgnostic() {
        assertTrue(movesFaceBox(120, 190, ChallengeType.LOOK_DIRECTION));
        assertTrue(movesFaceBox(190, 120, ChallengeType.LOOK_DIRECTION));
    }
}
