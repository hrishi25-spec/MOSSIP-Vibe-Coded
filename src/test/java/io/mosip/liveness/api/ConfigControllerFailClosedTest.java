package io.mosip.liveness.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.dto.ConfigPolicyUpdate;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed guard: when MOSIP_ADMIN_API_KEY is NOT configured, PUT
 * /api/v1/config/* must be refused rather than left open — an unauthenticated
 * caller could otherwise set passiveThreshold=0 and defeat PAD for every
 * session.
 */
@WebMvcTest(ConfigController.class)
@Import(TestConfig.class)
class ConfigControllerFailClosedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConfigPolicyRepository configRepo;

    @Test
    void setPolicy_withoutConfiguredKey_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(ConfigController.ADMIN_API_KEY_HEADER, "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        verify(configRepo, never()).save(any());
    }

    @Test
    void setPolicy_emptyKeyAttempt_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.1).build())))
                .andExpect(status().isForbidden());
        verify(configRepo, never()).save(any());
    }
}
