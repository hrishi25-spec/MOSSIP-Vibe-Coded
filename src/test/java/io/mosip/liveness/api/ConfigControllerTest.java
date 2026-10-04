package io.mosip.liveness.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.dto.ConfigPolicyUpdate;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.services.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConfigController.class)
@Import(TestConfig.class)
class ConfigControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private ConfigService configService;

    private ConfigPolicy policy;

    @BeforeEach
    void setUp() {
        reset(configRepo, configService);
        // ConfigController delegates enum conversion and effective-policy mapping
        // to ConfigService; run the real (dependency-free) methods so lazily-seeded
        // defaults and the merged-policy validation behave as in production.
        when(configService.toCoreWorkflow(any())).thenCallRealMethod();
        when(configService.toDbWorkflow(any())).thenCallRealMethod();
        when(configService.toDbChallenge(any())).thenCallRealMethod();
        when(configService.toDbFailure(any())).thenCallRealMethod();
        when(configService.mapToEffectivePolicy(any())).thenCallRealMethod();
        policy = ConfigPolicy.builder()
                .id(UUID.randomUUID()).workflowType(WorkflowType.RESIDENT)
                .livenessEnabled(true).passiveThreshold(0.75).activeLivenessEnabled(true)
                .minChallengeCount(1).challengeTypes(List.of("blink","smile","turn_left","turn_right"))
                .challengeTimeoutMs(15000).maxRetryCount(3).onRepeatedFailure(FailurePolicy.LOCK)
                .updatedAt(OffsetDateTime.now()).build();
    }

    @Test
    void getPolicy_existingPolicy_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        mockMvc.perform(get("/api/v1/config/RESIDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passiveThreshold").value(0.75))
                .andExpect(jsonPath("$.maxRetryCount").value(3));
    }

    @Test
    void getPolicy_noExistingPolicy_seedsDefaults() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.OPERATOR)).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(inv -> {
            ConfigPolicy p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        mockMvc.perform(get("/api/v1/config/OPERATOR"))
                .andExpect(status().isOk())
                // A lazily-seeded row carries the workflow's own default
                // (WorkflowPolicyDefaults): OPERATOR = 0.82, not the shared 0.80.
                .andExpect(jsonPath("$.passiveThreshold").value(0.82))
                .andExpect(jsonPath("$.onRepeatedFailure").value("ALLOW_RETRY"));
        verify(configRepo).save(any());
    }

    @Test
    void setPolicy_updateThreshold_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder().passiveThreshold(0.85).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passiveThreshold").value(0.85));
    }

    @Test
    void setPolicy_updateChallengeTypes_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.SUPERVISOR)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().challengeTypes(List.of("blink","turn_left")).maxRetryCount(5).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeTypes.length()").value(2))
                .andExpect(jsonPath("$.maxRetryCount").value(5));
    }

    @Test
    void setPolicy_updateFailurePolicy_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder().onRepeatedFailure("ESCALATE").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onRepeatedFailure").value("ESCALATE"));
    }

    @Test
    void setPolicy_disableLiveness_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder().livenessEnabled(false).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livenessEnabled").value(false));
    }
}
