package io.mosip.liveness.api;

import io.mosip.liveness.dto.AuditLogEntry;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.crud.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Audit trail endpoints.
 * Maps to the Python framework's routes/audit.py.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepo;

    @GetMapping
    public List<AuditLogEntry> getAuditTrail(@PathVariable UUID sessionId) {
        List<AuditLog> entries = auditLogRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return entries.stream()
                .map(e -> AuditLogEntry.builder()
                        .id(e.getId())
                        .eventType(e.getEventType())
                        .details(e.getDetails())
                        .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                        .build())
                .toList();
    }
}
