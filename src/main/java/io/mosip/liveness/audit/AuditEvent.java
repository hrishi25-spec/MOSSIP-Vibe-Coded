package io.mosip.liveness.audit;

import io.mosip.liveness.core.WorkflowType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable structured audit record for one pipeline decision. */
public final class AuditEvent {

    private final long timestampMillis;
    private final String sessionId;
    private final WorkflowType workflow;
    private final AuditEventType type;
    private final Map<String, String> fields;

    public AuditEvent(long timestampMillis, String sessionId, WorkflowType workflow, AuditEventType type) {
        this.timestampMillis = timestampMillis;
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.type = type;
        this.fields = new LinkedHashMap<>();
    }

    public static AuditEvent of(long tsMillis, String sessionId, WorkflowType workflow, AuditEventType type) {
        return new AuditEvent(tsMillis, sessionId, workflow, type);
    }

    /** Returns a copy with the extra field (immutable chaining). */
    public AuditEvent field(String key, Object value) {
        AuditEvent copy = new AuditEvent(timestampMillis, sessionId, workflow, type);
        copy.fields.putAll(this.fields);
        if (value != null) {
            // sanitize: single line, no control characters in audit values
            copy.fields.put(key, value.toString().replaceAll("[\\p{Cntrl}]", " "));
        }
        return copy;
    }

    public long timestampMillis() { return timestampMillis; }
    public String sessionId() { return sessionId; }
    public WorkflowType workflow() { return workflow; }
    public AuditEventType type() { return type; }
    public Map<String, String> fields() { return Map.copyOf(fields); }
}
