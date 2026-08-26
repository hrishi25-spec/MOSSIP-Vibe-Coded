package io.mosip.liveness.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pure, unit-testable decision functions for the passive stage:
 * temporal median voting over a sliding window + threshold decision +
 * passive-to-active escalation rule.
 */
public final class LivenessDecisionLogic {

    public enum PassiveOutcome { PROCEED_PASSIVE, ESCALATE_ACTIVE, PAD_BLOCK }

    private LivenessDecisionLogic() { }

    /** Median of the collection; even sizes average the two middle values. */
    public static double median(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("median requires non-empty values");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * Passive-stage decision for one assessment point.
     * @param medianScore temporal median of recent frame scores
     * @param threshold configured confidence threshold
     * @param padFlagged true when any windowed frame was flagged as an attack
     */
    public static PassiveOutcome decidePassive(double medianScore, double threshold, boolean padFlagged) {
        if (padFlagged) return PassiveOutcome.PAD_BLOCK;
        return medianScore >= threshold ? PassiveOutcome.PROCEED_PASSIVE : PassiveOutcome.ESCALATE_ACTIVE;
    }
}
