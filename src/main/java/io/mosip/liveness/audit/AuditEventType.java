package io.mosip.liveness.audit;

/** Structured audit event types emitted for every pipeline decision. */
public enum AuditEventType {
    SESSION_STARTED,
    SESSION_CLOSED,
    SESSION_ABORTED,
    FRAME_REJECTED,
    FRAME_SCORED,
    PASSIVE_PASSED,
    LIVENESS_FAILED,
    ESCALATED_TO_ACTIVE,
    CHALLENGE_ISSUED,
    CHALLENGE_PASSED,
    CHALLENGE_FAILED,
    CHALLENGE_TIMEOUT,
    MAX_RETRIES_EXCEEDED,
    REPEATED_FAILURE_ACTION,
    PAD_BLOCKED,
    /** Runtime policy edit via PUT /api/v1/config/{workflowType} (no session). */
    CONFIG_CHANGED,
    INTERNAL_ERROR
}
