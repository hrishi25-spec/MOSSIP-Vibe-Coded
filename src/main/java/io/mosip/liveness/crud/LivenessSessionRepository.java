package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LivenessSessionRepository extends JpaRepository<LivenessSession, UUID> {

    long countByStatus(SessionStatus status);

    long countByWorkflowType(WorkflowType workflowType);

    @Query("SELECT COALESCE(SUM(s.retryCount), 0) FROM LivenessSession s")
    long sumRetryCount();

    @Query("SELECT COUNT(s) FROM LivenessSession s WHERE s.failureReason LIKE :pattern")
    long countByFailureReasonLike(@Param("pattern") String pattern);

    /**
     * Row lock held for the duration of one frame/challenge decision
     * ({@code SELECT ... FOR UPDATE}). Two concurrent submissions for the same
     * session would otherwise both observe the passive stage and race: one
     * marking the session PASSED while the other issues a challenge — leaving a
     * challenge ISSUED on a session that already passed, or a pass recorded
     * without the required active verification. Must be called inside a
     * transaction (the decision methods are {@code @Transactional}); callers
     * fall back to the already-loaded instance when the row is unknown (unit
     * tests with detached sessions).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LivenessSession s WHERE s.id = :id")
    Optional<LivenessSession> findByIdForUpdate(@Param("id") UUID id);
}
