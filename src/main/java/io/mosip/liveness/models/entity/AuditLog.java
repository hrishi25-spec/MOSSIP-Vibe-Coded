package io.mosip.liveness.models.entity;

import io.mosip.liveness.models.converter.StringObjectMapJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single structured audit-trail entry, as stored in {@code audit_logs}.
 *
 * <p>{@code details} is persisted as JSON text through an explicit converter so
 * the mapping behaves identically on PostgreSQL and on H2 (the {@code dev}
 * profile).</p>
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private LivenessSession session;

    /** Machine-readable event name, e.g. {@code SESSION_PASSED}, {@code PAD_REJECTED}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Convert(converter = StringObjectMapJsonConverter.class)
    @Column(name = "details", nullable = false, columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
