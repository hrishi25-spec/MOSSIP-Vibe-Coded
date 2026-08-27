package io.mosip.liveness.api;

import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MetricsController.class)
@Import(TestConfig.class)
class MetricsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private FrameEventRepository frameEventRepo;
    @Autowired private ChallengeRepository challengeRepo;

    @Test
    void getMetrics_emptyDatabase_returnsZeros() throws Exception {
        when(sessionRepo.count()).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.count()).thenReturn(0L);
        when(challengeRepo.count()).thenReturn(0L);
        when(sessionRepo.sumRetryCount()).thenReturn(0L);
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSessions").value(0))
                .andExpect(jsonPath("$.passRate").value(0.0));
    }

    @Test
    void getMetrics_withData_returnsCorrectRates() throws Exception {
        when(sessionRepo.count()).thenReturn(100L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(80L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(15L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(5L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(20L);
        when(frameEventRepo.count()).thenReturn(500L);
        when(challengeRepo.count()).thenReturn(30L);
        when(sessionRepo.sumRetryCount()).thenReturn(25L);
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSessions").value(100))
                .andExpect(jsonPath("$.passRate").value(0.8))
                .andExpect(jsonPath("$.livenessFailureRate").value(0.15))
                .andExpect(jsonPath("$.avgFramesPerSession").value(5.0));
    }
}
