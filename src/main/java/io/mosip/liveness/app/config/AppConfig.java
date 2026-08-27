package io.mosip.liveness.app.config;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.StructuredAuditLogger;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RepeatedFailureAction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;
import java.util.Set;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Value("${mosip.liveness.passive-threshold:0.80}")
    private double passiveThreshold;

    @Value("${mosip.liveness.min-face-quality:0.50}")
    private double minFaceQuality;

    @Value("${mosip.liveness.passive-min-frames:5}")
    private int passiveMinFrames;

    @Value("${mosip.liveness.passive-window-frames:7}")
    private int passiveWindowFrames;

    @Value("${mosip.liveness.min-challenge-count:2}")
    private int minChallengeCount;

    @Value("${mosip.liveness.challenge-timeout-ms:10000}")
    private long challengeTimeoutMs;

    @Value("${mosip.liveness.max-retries:2}")
    private int maxRetries;

    @PostConstruct
    void init() {
        // Load OpenCV native library if available on classpath
        try {
            nu.pattern.OpenCV.loadLocally();
            log.info("OpenCV native library loaded successfully");
        } catch (Exception e) {
            log.warn("OpenCV native library not available on classpath; image processing will use mock heuristics", e);
        }
    }

    @Bean
    public LivenessConfig livenessConfig() {
        Set<ChallengeType> challenges = EnumSet.of(
                ChallengeType.BLINK,
                ChallengeType.SMILE,
                ChallengeType.TURN_HEAD_LEFT,
                ChallengeType.TURN_HEAD_RIGHT
        );
        return LivenessConfig.builder()
                .passiveThreshold(passiveThreshold)
                .minFaceQuality(minFaceQuality)
                .passiveMinFrames(passiveMinFrames)
                .passiveWindowFrames(passiveWindowFrames)
                .minChallengeCount(minChallengeCount)
                .challengeTimeoutMs(challengeTimeoutMs)
                .maxRetries(maxRetries)
                .supportedChallengeTypes(challenges)
                .onRepeatedFailure(RepeatedFailureAction.LOCK_OUT)
                .build();
    }

    @Bean
    public LivenessBackend livenessBackend() {
        return new MockLivenessBackend();
    }

    @Bean
    public AuditLogger auditLogger() {
        return StructuredAuditLogger.toStdout();
    }

    @Bean
    public MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("*")
                        .allowedMethods("*")
                        .allowedHeaders("*");
            }
        };
    }
}
