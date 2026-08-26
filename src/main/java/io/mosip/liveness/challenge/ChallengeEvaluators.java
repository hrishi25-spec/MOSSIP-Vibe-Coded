package io.mosip.liveness.challenge;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.FaceSignals;

import java.util.List;
import java.util.Optional;

/**
 * Pure rule-based evaluators that confirm a requested action was performed by
 * a live subject, from the landmark signal sequence captured during the
 * challenge window. No extra ML model required — signals come from the
 * backend's landmark extraction.
 */
public final class ChallengeEvaluators {

    public static final double DEFAULT_BLINK_CLOSE_EAR = 0.22;
    public static final double DEFAULT_BLINK_OPEN_EAR = 0.27;
    public static final double DEFAULT_SMILE_THRESHOLD = 0.55;
    public static final double DEFAULT_TURN_DEGREES = 12.0;
    public static final double DEFAULT_GAZE_TOLERANCE = 0.35;

    private ChallengeEvaluators() { }

    /** Evaluate whether the sequence of signals satisfies the challenge. */
    public static boolean evaluate(Challenge challenge, List<FaceSignals> sequence) {
        if (sequence == null || sequence.isEmpty()) return false;
        return switch (challenge.type()) {
            case BLINK -> blinked(sequence);
            case SMILE -> smiled(sequence);
            case TURN_HEAD_LEFT -> turned(sequence, -DEFAULT_TURN_DEGREES);
            case TURN_HEAD_RIGHT -> turned(sequence, DEFAULT_TURN_DEGREES);
            case LOOK_DIRECTION -> looked(sequence,
                    challenge.parameters().getOrDefault("dirX", 1.0),
                    challenge.parameters().getOrDefault("dirY", 0.0));
        };
    }

    /**
     * Blink: EAR must dip below the close threshold and then recover above the
     * open threshold within the window — a static photo cannot do this.
     */
    static boolean blinked(List<FaceSignals> seq) {
        Double minEar = null;
        int minIdx = -1;
        for (int i = 0; i < seq.size(); i++) {
            var ear = seq.get(i).minEyeAspectRatio();
            if (ear.isPresent() && (minEar == null || ear.get() < minEar)) {
                minEar = ear.get();
                minIdx = i;
            }
        }
        if (minIdx < 0 || minEar > DEFAULT_BLINK_CLOSE_EAR) return false;
        for (int j = minIdx + 1; j < seq.size(); j++) {
            var ear = seq.get(j).minEyeAspectRatio();
            if (ear.isPresent() && ear.get() > DEFAULT_BLINK_OPEN_EAR) return true;
        }
        return false;
    }

    /** Smile: smile score must exceed the threshold at least once in the window. */
    static boolean smiled(List<FaceSignals> seq) {
        return seq.stream()
                .map(FaceSignals::smileScore)
                .flatMap(Optional::stream)
                .anyMatch(s -> s > DEFAULT_SMILE_THRESHOLD);
    }

    /** Head turn: yaw must reach the target angle (negative = left, positive = right). */
    static boolean turned(List<FaceSignals> seq, double targetDegrees) {
        return seq.stream()
                .map(FaceSignals::yawDegrees)
                .flatMap(Optional::stream)
                .anyMatch(targetDegrees < 0 ? y -> y <= targetDegrees : y -> y >= targetDegrees);
    }

    /**
     * Gaze direction: normalized gaze vector must stay within tolerance of the
     * engine-selected target direction for at least half the frames.
     */
    static boolean looked(List<FaceSignals> seq, double dirX, double dirY) {
        long matches = seq.stream()
                .filter(s -> s.gazeX().isPresent())
                .filter(s -> Math.abs(s.gazeX().get() - dirX) <= DEFAULT_GAZE_TOLERANCE)
                .filter(s -> s.gazeY().isPresent())
                .filter(s -> Math.abs(s.gazeY().get() - dirY) <= DEFAULT_GAZE_TOLERANCE)
                .count();
        return matches >= Math.max(1, seq.size() / 2);
    }
}
