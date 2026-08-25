package io.mosip.liveness.engine;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.WorkflowType;

/** Audit record returned by {@code closeSession}. */
public record SessionSummary(
        String sessionId,
        WorkflowType workflow,
        Outcome outcome,
        boolean escalatedToActive,
        double finalMedianScore,
        PadAttackType padAttackType,
        int challengesIssued,
        int challengesPassed,
        int challengesFailed,
        long durationMs) {

    public enum Outcome {
        PASSED_PASSIVE,
        PASSED_ACTIVE,
        BYPASSED,
        PAD_BLOCKED,
        FAILED_LIVENESS,
        FAILED_MAX_RETRIES_LOCKED_OUT,
        FAILED_MAX_RETRIES_FALLBACK,
        FAILED_MAX_RETRIES_OPERATOR_ESCALATION,
        ABORTED
    }

    public boolean passed() {
        return outcome == Outcome.PASSED_PASSIVE || outcome == Outcome.PASSED_ACTIVE
                || outcome == Outcome.BYPASSED;
    }
}
