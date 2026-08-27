package io.mosip.liveness.api;

import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.crud.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.*;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
@Import(TestConfig.class)
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditLogRepository auditLogRepo;

    private UUID sessionId;
    private LivenessSession session;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT)
                .deviceId("L1-CAM-01").status(SessionStatus.ACTIVE).build();
    }

    @Test
    void getAuditTrail_empty_returnsEmptyList() throws Exception {
        when(auditLogRepo.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getAuditTrail_withEntries_returnsOrderedTrail() throws Exception {
        when(auditLogRepo.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                AuditLog.builder().id(UUID.randomUUID()).session(session).eventType("SESSION_CREATED")
                        .details(Map.of("workflowType","RESIDENT")).createdAt(OffsetDateTime.now().minusMinutes(10)).build(),
                AuditLog.builder().id(UUID.randomUUID()).session(session).eventType("FRAME_SCORED")
                        .details(Map.of("median","0.82")).createdAt(OffsetDateTime.now().minusMinutes(5)).build(),
                AuditLog.builder().id(UUID.randomUUID()).session(session).eventType("SESSION_PASSED")
                        .details(Map.of("reason","passive")).createdAt(OffsetDateTime.now()).build()
        ));
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventType").value("SESSION_CREATED"))
                .andExpect(jsonPath("$[2].eventType").value("SESSION_PASSED"));
    }
}
