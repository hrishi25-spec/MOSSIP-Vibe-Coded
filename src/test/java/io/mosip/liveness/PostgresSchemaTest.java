package io.mosip.liveness;

import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema gate: boots the real application against a real PostgreSQL, lets
 * Flyway build the schema from the migrations, and has Hibernate
 * {@code ddl-auto=validate} compare that schema against the entities.
 *
 * <p>This exists because nothing else does that. The dev profile and every
 * {@code @DataJpaTest} run on H2 with {@code ddl-auto: create-drop}, so
 * Hibernate invents the schema from the entities and Flyway never runs. That
 * combination hides a whole class of drift: a column the entity declares but
 * no migration creates, a {@code VARCHAR(16)} that Postgres will happily
 * accept for a few hundred rows and then reject, an enum stored as a number,
 * a {@code NOT NULL} the migration is missing. Green CI, broken production —
 * and the production target is PostgreSQL, which is the one database that
 * never gets tested.</p>
 *
 * <p>Runs only when {@code MOSIP_PG_TEST=true}. CI sets that on the
 * {@code schema} job, which also provides the database. Everywhere else it is
 * skipped, so a plain {@code ./mvnw test} still needs no services.</p>
 *
 * <p>The database is configured through the same {@code POSTGRES_*} variables
 * {@code application.yml} already uses — no test-only datasource properties,
 * so the test exercises the real production configuration.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "MOSIP_PG_TEST", matches = "true")
class PostgresSchemaTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LivenessSessionRepository sessions;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("is talking to a real PostgreSQL, not the H2 stand-in")
    void isRealPostgres() throws Exception {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        }
        // Without this guard the test could pass on H2 if someone pointed
        // POSTGRES_HOST somewhere unexpected, which is the exact failure mode
        // this whole test exists to rule out.
        assertTrue(product.toLowerCase().contains("postgresql"),
                "expected PostgreSQL but connected to " + product
                        + " — this gate is meaningless against any other database");
    }

    @Test
    @DisplayName("validates the schema instead of creating it")
    void ddlAutoIsValidate() {
        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");
        assertEquals("validate", ddlAuto,
                "Hibernate must only validate; create/update would mask entity-vs-migration drift "
                        + "by rebuilding the schema from the entities");
        assertTrue(environment.getProperty("spring.flyway.enabled", Boolean.class, false),
                "Flyway must own the schema for this test to mean anything");
    }

    @Test
    @DisplayName("applies every committed migration")
    void flywayAppliedAllMigrations() {
        // Flyway builds the schema; if it silently no-opped, `validate` would
        // be checking an empty database and would fail loudly — but this makes
        // the reason legible instead of mysterious.
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(applied);
        assertTrue(applied >= 4,
                "expected at least V1-V4 to be applied, found " + applied);

        String latest = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class);
        assertEquals("4", latest, "latest applied migration should be V4");
    }

    @Test
    @DisplayName("round-trips an entity through the migrated schema")
    void entityRoundTrips() {
        // Proves the mapping is not merely "no missing table": the row actually
        // inserts and reads back with its enum columns and timestamptz intact.
        LivenessSession saved = sessions.saveAndFlush(LivenessSession.builder()
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("schema-gate")
                .subjectRef("subject-42")
                .status(SessionStatus.ACTIVE)
                .currentStage(LivenessStage.PASSIVE)
                .build());

        assertNotNull(saved.getId());
        // OffsetDateTime against a TIMESTAMPTZ column is the classic
        // H2-passes/Postgres-fails mapping difference, so assert on it.
        assertNotNull(saved.getCreatedAt(), "@PrePersist must populate created_at");

        LivenessSession reloaded = sessions.findById(saved.getId()).orElseThrow();
        assertEquals(SessionStatus.ACTIVE, reloaded.getStatus());
        assertEquals(WorkflowType.RESIDENT, reloaded.getWorkflowType());
        assertEquals("subject-42", reloaded.getSubjectRef());

        sessions.deleteById(saved.getId());
        sessions.flush();
        assertTrue(sessions.findById(saved.getId()).isEmpty());
        assertEquals(UUID.class, reloaded.getId().getClass());
    }
}