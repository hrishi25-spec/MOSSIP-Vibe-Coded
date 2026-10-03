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

    @Value("${mosip.liveness.challenge-timeout-ms:15000}")
    private long challengeTimeoutMs;

    @Value("${mosip.liveness.max-retries:2}")
    private int maxRetries;

    private static volatile boolean openCvAvailable;
    private static final Object OPEN_CV_LOCK = new Object();
    private static boolean openCvAttempted;

    @PostConstruct
    void init() {
        ensureOpenCvLoaded();
    }

    /**
     * Loads the OpenCV native library <b>exactly once per JVM</b>, no matter
     * which bean asks first.
     *
     * <p>Bean instantiation order is not guaranteed, so a bean created before
     * this configuration class (e.g. {@code PassiveScoringService}, which
     * builds a Haar cascade while initializing the liveness model) would
     * otherwise hit an {@code UnsatisfiedLinkError} on
     * {@code CascadeClassifier_1(String)} and silently degrade. Asking here
     * makes the load idempotent: the first caller performs it, everyone else
     * gets the flag.</p>
     *
     * <p>openpnp extracts the native binary into a per-JVM temp directory and
     * deletes stale directories on startup; that extraction can transiently
     * race with another JVM (or a leftover directory) and fail. A failure
     * surfaces as an Error (UnsatisfiedLinkError / ExceptionInInitializerError),
     * not an Exception, so catching Exception alone would abort application
     * startup. Degrade instead, and report the state through /health.</p>
     *
     * @return true when frame processing is usable
     */
    public static boolean ensureOpenCvLoaded() {
        if (openCvAttempted) {
            return openCvAvailable;
        }
        synchronized (OPEN_CV_LOCK) {
            if (openCvAttempted) {
                return openCvAvailable;
            }
            try {
                nu.pattern.OpenCV.loadLocally();
                openCvAvailable = true;
                log.info("OpenCV native library loaded successfully");
            } catch (Throwable t) {
                openCvAvailable = false;
                log.warn("OpenCV native library could not be loaded; frame-processing "
                        + "endpoints will return 503 until it is available", t);
            }
            openCvAttempted = true;
        }
        return openCvAvailable;
    }

    /** True when the OpenCV native library loaded and frame processing is usable. */
    public static boolean isOpenCvAvailable() {
        return openCvAvailable;
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
                        .allowedOrigins("http://localhost:*", "http://127.0.0.1:*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "Authorization")
                        .allowCredentials(false);
            }
        };
    }
}
