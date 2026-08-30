package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LivenessSessionRepository extends JpaRepository<LivenessSession, UUID> {

    long countByStatus(SessionStatus status);

    long countByWorkflowType(WorkflowType workflowType);

    @Query("SELECT COALESCE(SUM(s.retryCount), 0) FROM LivenessSession s")
    long sumRetryCount();

    @Query("SELECT COUNT(s) FROM LivenessSession s WHERE s.failureReason LIKE :pattern")
    long countByFailureReasonLike(@Param("pattern") String pattern);
}
