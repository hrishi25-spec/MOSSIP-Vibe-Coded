package io.mosip.liveness.audit;

import java.io.PrintStream;
import java.time.Instant;

/**
 * Writes key=value structured audit lines to a PrintStream (file or stdout).
 * Format: ts=... event=... session=... workflow=... k=v ...
 * Never emits raw frames or model internals — only decisions and scores.
 */
public final class StructuredAuditLogger implements AuditLogger {

    private final PrintStream out;
    private final Object lock = new Object();

    public StructuredAuditLogger(PrintStream out) {
        this.out = out;
    }

    @Override
    public void log(AuditEvent event) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("ts=").append(Instant.ofEpochMilli(event.timestampMillis()))
          .append(" event=").append(event.type())
          .append(" session=").append(event.sessionId())
          .append(" workflow=").append(event.workflow());
        event.fields().forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        synchronized (lock) {
            out.println(sb);
        }
    }
}
