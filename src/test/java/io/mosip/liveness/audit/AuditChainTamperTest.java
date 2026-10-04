package io.mosip.liveness.audit;

import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the config audit chain actually detects tampering — the whole point of
 * storing the hashes.
 *
 * <p>Tampering is done through {@link JdbcTemplate} rather than the repository
 * on purpose: going through JPA would re-derive the hash and "repair" the row,
 * which would test nothing. Raw SQL is what an attacker with a database
 * connection has, and it is the only way to produce the broken state.
 *
 * <p>Runs on H2, whose schema Hibernate generates from the entities, so these
 * tests cannot prove the V6 PostgreSQL triggers — only the chain logic, which
 * is database-independent. The triggers are validated by the {@code schema} CI
 * job.</p>
 */
@DataJpaTest(properties = {
        // The V1-V6 migrations are Postgres SQL; on H2 the schema comes from the
        // entities, which is what EntityPersistenceTest does too.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class AuditChainTamperTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.mosip.liveness.crud.AuditLogRepository repo;

    private static java.time.OffsetDateTime lastTimestamp = java.time.OffsetDateTime.now();

    /** Strictly increasing, so ordering by created_at is unambiguous. */
    private static java.time.OffsetDateTime nextTimestamp() {
        lastTimestamp = lastTimestamp.plusNanos(1_000_000L);   // +1 ms
        return lastTimestamp;
    }

    /** Builds and persists one chained entry, the way ConfigController does. */
    private AuditLog append(String previousHash, String threshold) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", 0.80);
        change.put("to", Double.parseDouble(threshold));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowType", "RESIDENT");
        details.put("action", "UPDATED");
        details.put("changes", change);

        AuditLog entry = AuditLog.builder()
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(details)
                .prevHash(previousHash)
                .createdAt(nextTimestamp())
                .build();
        entry.setEntryHash(AuditChain.hashOf(entry));
        return repo.saveAndFlush(entry);
    }

    private String seedChain(int entries) {
        String previous = AuditChain.GENESIS;
        for (int i = 0; i < entries; i++) {
            previous = append(previous, "0." + (70 + i)).getEntryHash();
        }
        return previous;
    }

    private List<AuditLog> chain() {
        return repo.findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(
                AuditEventType.CONFIG_CHANGED.name());
    }

    @Test
    @DisplayName("an untouched chain verifies")
    void intactChainVerifies() {
        seedChain(4);
        assertEquals(4, chain().size());
        assertTrue(AuditChain.verify(chain()).isEmpty(),
                "a chain written normally must verify");
    }

    @Test
    @DisplayName("hashes are stable across a database round-trip")
    void hashSurvivesTheRoundTrip() throws Exception {
        seedChain(2);

        // The converter rebuilds details as a plain HashMap, so its iteration
        // order differs from the LinkedHashMap that was hashed on write. If the
        // canonical form were not order-independent, every verification of a
        // real row would fail for reasons unrelated to tampering.
        repo.flush();
        jdbc.execute("select 1");
        entityManagerClear();

        List<AuditLog> reloaded = chain();
        assertEquals(2, reloaded.size());
        for (AuditLog entry : reloaded) {
            assertEquals(entry.getEntryHash(), AuditChain.hashOf(entry),
                    "entry " + entry.getId() + " must reproduce its own hash after a round-trip");
        }
        assertTrue(AuditChain.verify(reloaded).isEmpty());
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private void entityManagerClear() {
        entityManager.clear();
    }

    @Test
    @DisplayName("an EDITED row is detected")
    void editedRowIsDetected() {
        seedChain(3);
        assertTrue(AuditChain.verify(chain()).isEmpty());

        // Exactly what an attacker covering their tracks does: rewrite the value
        // the audit says was set, keeping every other column intact.
        AuditLog target = chain().get(1);
        jdbc.update("UPDATE audit_logs SET details = ? WHERE id = ?",
                "{\"workflowType\":\"RESIDENT\",\"action\":\"UPDATED\",\"changes\":{\"passiveThreshold\":{\"from\":0.8,\"to\":0.01}}}",
                target.getId());
        entityManagerClear();

        AuditChain.Break broken = AuditChain.verify(chain()).orElseThrow(
                () -> new AssertionError("an edited audit row must break the chain"));

        assertTrue(broken.contentChanged(),
                "the row's own content no longer hashes to its stored entry_hash");
        assertEquals(target.getId(), broken.id());
        assertEquals(1, broken.index(), "the second entry is the one that was altered");
        assertEquals(target.getEntryHash(), broken.storedHash(),
                "the stored hash is untouched — only the content changed");
        assertNotNull(broken.recomputedHash());
    }

    @Test
    @DisplayName("a DELETED row is detected")
    void deletedRowIsDetected() {
        seedChain(4);

        AuditLog removed = chain().get(1);
        jdbc.update("DELETE FROM audit_logs WHERE id = ?", removed.getId());
        entityManagerClear();

        List<AuditLog> remaining = chain();
        assertEquals(3, remaining.size(), "the row is really gone from the table");

        AuditChain.Break broken = AuditChain.verify(remaining).orElseThrow(
                () -> new AssertionError("deleting an audit row must break the chain"));

        // The surviving rows are each internally consistent — their own content
        // still hashes to their stored value. What is broken is the link: the
        // successor still names the hash of a row that no longer exists.
        assertFalse(broken.contentChanged(),
                "the rows that remain are untouched; only the linkage is wrong");
        assertEquals(removed.getEntryHash(), broken.storedPrevHash(),
                "the dangling reference is in the row's stored prev_hash");
        // The walk expected the last row it could actually verify — the one
        // before the break — rather than the hash of the row that is gone.
        assertEquals(remaining.get(broken.index() - 1).getEntryHash(), broken.expectedPrevHash(),
                "the walk anchored on the previous surviving row, not the deleted one");
    }

    @Test
    @DisplayName("a re-inserted row with the original hash still fails once the content moved on")
    void rehashingAloneDoesNotRepairTheChain() {
        seedChain(3);
        AuditLog middle = chain().get(1);
        jdbc.update("DELETE FROM audit_logs WHERE id = ?", middle.getId());
        entityManagerClear();

        assertTrue(AuditChain.verify(chain()).isPresent());

        // Putting the row back with its old hash cannot fix anything: the
        // successor is chained to a hash that no longer exists, so the walk
        // still cannot get past the gap.
        repo.saveAndFlush(AuditLog.builder()
                .id(middle.getId())
                .eventType(middle.getEventType())
                .workflowType(middle.getWorkflowType())
                .details(middle.getDetails())
                .prevHash(middle.getPrevHash())
                .entryHash(middle.getEntryHash())
                .createdAt(middle.getCreatedAt())
                .build());
        entityManagerClear();

        assertTrue(AuditChain.verify(chain()).isEmpty(),
                "restoring the exact row repairs the chain — detection is of the gap, not a permanent mark");
    }

    @Test
    @DisplayName("the first entry is anchored to GENESIS, not to a dangling hash")
    void chainStartsAtGenesis() {
        String head = seedChain(1);
        AuditLog first = chain().get(0);
        assertEquals(AuditChain.GENESIS, first.getPrevHash());
        assertEquals(head, first.getEntryHash());
        assertTrue(AuditChain.verify(chain()).isEmpty());
    }

    @Test
    @DisplayName("the repository reports the newest chained entry as the link target")
    void headLookupSkipsUnchainedLegacyRows() {
        // A legacy row: no hashes at all, as written before V6.
        repo.saveAndFlush(AuditLog.builder()
                .id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.OPERATOR)
                .details(Map.of("workflowType", "OPERATOR"))
                .createdAt(java.time.OffsetDateTime.now())
                .build());

        seedChain(2);
        Optional<AuditLog> head = repo
                .findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(
                        AuditEventType.CONFIG_CHANGED.name());
        assertTrue(head.isPresent());
        assertNotNull(head.get().getEntryHash(), "the legacy NULL-hash row must not be chosen as head");
    }
}