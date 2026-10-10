package io.mosip.liveness.models;

import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-level regression guard.
 *
 * <p>The controller tests mock the repositories, so they cannot catch a broken
 * column mapping. These tests write through JPA, clear the persistence context,
 * and read the rows back — which is what the API does on a real request.</p>
 *
 * <p>Flyway is disabled here so Hibernate generates the schema from the entity
 * mappings; the Flyway migration is PostgreSQL-targeted and exercised
 * separately.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EntityPersistenceTest {

    @Autowired private TestEntityManager em;
    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private AuditLogRepository auditRepo;
    @Autowired private LivenessSessionRepository sessionRepo;

    @Test
    void configPolicy_challengeTypes_surviveARoundTrip() {
        configRepo.saveAndFlush(ConfigPolicy.builder()
                .workflowType(WorkflowType.RESIDENT)
                .challengeTypes(List.of("blink", "turn_left"))
                .onRepeatedFailure(FailurePolicy.ESCALATE)
                .maxRetryCount(5)
                .build());

        em.clear(); // force a real read rather than a persistence-context hit

        ConfigPolicy reloaded = configRepo.findByWorkflowType(WorkflowType.RESIDENT).orElseThrow();
        assertThat(reloaded.getChallengeTypes()).containsExactly("blink", "turn_left");
        assertThat(reloaded.getOnRepeatedFailure()).isEqualTo(FailurePolicy.ESCALATE);
        assertThat(reloaded.getMaxRetryCount()).isEqualTo(5);
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void session_timestampsAndDefaults_areApplied() {
        LivenessSession saved = sessionRepo.saveAndFlush(LivenessSession.builder()
                .workflowType(WorkflowType.OPERATOR)
                .deviceId("L1-CAM-01")
                .build());

        em.clear();

        LivenessSession reloaded = sessionRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(reloaded.getCurrentStage()).isEqualTo(LivenessStage.PASSIVE);
        assertThat(reloaded.getRetryCount()).isZero();
        assertThat(reloaded.getOnline()).isTrue();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void auditLog_details_surviveARoundTrip() {
        LivenessSession session = sessionRepo.saveAndFlush(
                LivenessSession.builder()
                        .workflowType(WorkflowType.SUPERVISOR)
                        .deviceId("L0-CAM-07")
                        .closedAt(OffsetDateTime.now())
                        .build());

        em.clear();

        AuditLog saved = auditRepo.saveAndFlush(AuditLog.builder()
                .session(session)
                .eventType("PAD_REJECTED")
                .details(Map.of("attackType", "PRINTED_PHOTO", "confidence", 0.87))
                .build());

        em.clear();

        AuditLog reloaded = auditRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()).get(0);
        assertThat(reloaded.getEventType()).isEqualTo("PAD_REJECTED");
        assertThat(reloaded.getDetails())
                .containsEntry("attackType", "PRINTED_PHOTO")
                .containsEntry("confidence", 0.87);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
    }
}
