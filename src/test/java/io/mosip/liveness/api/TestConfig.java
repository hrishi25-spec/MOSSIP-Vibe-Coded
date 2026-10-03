package io.mosip.liveness.api;

import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.ChallengeSelectorService;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import io.mosip.liveness.services.ConfigService;
import io.mosip.liveness.services.LivenessEngineService;
import io.mosip.liveness.services.PadEngineService;
import io.mosip.liveness.services.ThresholdCalibrationService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared test configuration providing all mock beans.
 * Prevents Spring from loading real services (which depend on OpenCV native libs)
 * by providing mock alternatives for every dependency.
 */
@TestConfiguration
public class TestConfig {

    @Bean @Primary
    public LivenessSessionRepository sessionRepo() {
        return Mockito.mock(LivenessSessionRepository.class);
    }

    @Bean @Primary
    public FrameEventRepository frameEventRepo() {
        return Mockito.mock(FrameEventRepository.class);
    }

    @Bean @Primary
    public ChallengeRepository challengeRepo() {
        return Mockito.mock(ChallengeRepository.class);
    }

    @Bean @Primary
    public AuditLogRepository auditLogRepo() {
        return Mockito.mock(AuditLogRepository.class);
    }

    @Bean @Primary
    public ConfigPolicyRepository configRepo() {
        return Mockito.mock(ConfigPolicyRepository.class);
    }

    @Bean @Primary
    public ImageUtils imageUtils() {
        return Mockito.mock(ImageUtils.class);
    }

    @Bean @Primary
    public LivenessEngineService livenessEngine() {
        return Mockito.mock(LivenessEngineService.class);
    }

    @Bean @Primary
    public PadEngineService padEngine() {
        return Mockito.mock(PadEngineService.class);
    }

    @Bean @Primary
    public ChallengeSelectorService challengeSelector() {
        return Mockito.mock(ChallengeSelectorService.class);
    }

    @Bean @Primary
    public DecisionEngineService decisionEngine() {
        return Mockito.mock(DecisionEngineService.class);
    }

    @Bean @Primary
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }

    @Bean @Primary
    public ThresholdCalibrationService thresholdCalibrationService() {
        return Mockito.mock(ThresholdCalibrationService.class);
    }
}
