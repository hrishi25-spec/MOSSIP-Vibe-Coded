package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.enums.LivenessStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FrameEventRepository extends JpaRepository<FrameEvent, UUID> {

    long countBySessionId(UUID sessionId);

    @Query("SELECT COUNT(DISTINCT f.session.id) FROM FrameEvent f WHERE f.stage = :stage")
    long countDistinctSessionsByStage(@Param("stage") LivenessStage stage);
}
