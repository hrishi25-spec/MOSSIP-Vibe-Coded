package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.enums.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChallengeRepository extends JpaRepository<ChallengeEntity, UUID> {

    long countBySessionId(UUID sessionId);

    long countBySessionIdAndStatus(UUID sessionId, ChallengeStatus status);

    List<ChallengeEntity> findBySessionIdOrderByIssuedAtDesc(UUID sessionId);

    long countByStatus(ChallengeStatus status);
}
