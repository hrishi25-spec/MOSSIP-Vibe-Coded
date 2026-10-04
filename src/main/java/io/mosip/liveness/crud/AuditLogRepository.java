package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.enums.WorkflowType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Operator-level audit feed: newest first. {@code session IS NULL} keeps
     * these out of every session trail while still being one table (and one
     * timestamp ordering) for operators to read.
     *
     * <p>{@code Pageable} is the page size (always a single page) so the limit
     * is applied by the query — the feed must never pull the whole audit table
     * to return the newest few entries.</p>
     */
    List<AuditLog> findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(String eventType, Pageable pageable);

    /**
     * The same feed, narrowed to one workflow in the database rather than in
     * the browser. This is the query that makes the column worth having: the
     * audit table grows one row per frame decision, so returning every row to
     * filter client-side means paging through the whole pipeline history to
     * answer a question about policy edits.
     *
     * <p>Backed by {@code idx_audit_logs_config_feed}.</p>
     */
    List<AuditLog> findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc(
            String eventType, WorkflowType workflowType, Pageable pageable);

    /**
     * The newest entry that is part of a hash chain, for linking the next one
     * onto it. Entries written before {@code V6} have no hash and are skipped,
     * so the chain continues across the migration boundary instead of
     * restarting on a NULL.
     */
    Optional<AuditLog> findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(String eventType);

    /**
     * The whole chain, oldest first, for verification. Paged on purpose: this
     * walk must never be truncated, or a break past the page boundary would
     * read as "chain intact".
     */
    List<AuditLog> findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(String eventType);
}
