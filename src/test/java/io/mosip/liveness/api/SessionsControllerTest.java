package io.mosip.liveness.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.dto.SessionCreateRequest;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SessionsController.class)
@Import(TestConfig.class)
class SessionsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private FrameEventRepository frameEventRepo;
    @Autowired private ChallengeRepository challengeRepo;

    private UUID sessionId;
    private LivenessSession session;

    @BeforeEach
    void setUp() {
        reset(sessionRepo, frameEventRepo, challengeRepo);
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01")
                .status(SessionStatus.ACTIVE).currentStage(LivenessStage.PASSIVE)
                .retryCount(0).online(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void createSession_returns201() throws Exception {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            LivenessSession s = inv.getArgument(0);
            s.setId(sessionId); s.setCreatedAt(OffsetDateTime.now()); s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });
        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01").online(true).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowType").value("RESIDENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(sessionRepo).save(any());
    }

    @Test
    void createSession_missingWorkflowType_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"L1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getSession_existingSession_returns200() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        mockMvc.perform(get("/api/v1/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getSession_nonExistentSession_returns404() throws Exception {
        when(sessionRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void closeSession_activeSession_returns200() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(frameEventRepo.countBySessionId(sessionId)).thenReturn(10L);
        when(challengeRepo.countBySessionId(sessionId)).thenReturn(2L);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFramesEvaluated").value(10))
                .andExpect(jsonPath("$.totalChallengesIssued").value(2));
    }

    @Test
    void closeSession_alreadyClosed_doesNotUpdate() throws Exception {
        session.setStatus(SessionStatus.PASSED);
        session.setClosedAt(OffsetDateTime.now().minusMinutes(5));
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(frameEventRepo.countBySessionId(sessionId)).thenReturn(5L);
        when(challengeRepo.countBySessionId(sessionId)).thenReturn(1L);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
        verify(sessionRepo, never()).save(any());
    }
}
