package io.mosip.liveness.core;

/** Behavior applied when the retry budget for active challenges is exhausted. */
public enum RepeatedFailureAction {
    /** Hard-fail the session; the operator/device is locked out of further attempts. */
    LOCK_OUT,
    /** Hard-fail the session and fall back to an alternate capture flow. */
    FALLBACK,
    /** Hard-fail the session and flag for supervisor/operator escalation. */
    ESCALATE_TO_OPERATOR
}
