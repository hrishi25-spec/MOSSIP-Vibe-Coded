package io.mosip.liveness.app.config;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.StructuredAuditLogger;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.metrics.PipelineTimers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.time.Clock;

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
    /**
     * Volatile because the load now runs on a background thread: the fast path
     * in {@link #ensureOpenCvLoaded()} reads it outside the lock, and a plain
     * boolean would let a caller on the main thread observe a stale "not yet
     * attempted" and re-enter the lock pointlessly. Re-entering is only a
     * wasted monitor acquisition, but {@code openCvAvailable} must never be
     * reported before the attempt has settled.
     */
    private static volatile boolean openCvAttempted;
    private static volatile Thread openCvWarmupThread;
    /** Wall-clock cost of the native load, for the startup log line. */
    private static volatile long openCvLoadMs = -1;
    private static volatile Throwable openCvFailure;
    private static boolean openCvOutcomeReported;

    /**
     * Kicks off the native-library load on a background thread so it overlaps
     * the rest of context refresh instead of running serially in front of it.
     *
     * <p>openpnp extracts a ~65 MB {@code libopencv_java490.so} into a temp
     * directory and {@code System.load}s it; that measured ~6.1 s, and it used
     * to run on the {@code main} thread before any other bean was created.
     * Most of it is file I/O, so it overlaps cleanly with Hibernate DDL and
     * Tomcat initialisation on another core.</p>
     *
     * <p>This changes <em>when</em> the load starts, never <em>what</em> it
     * returns. {@link #ensureOpenCvLoaded()} still blocks until the attempt has
     * settled, so any bean that genuinely needs the library
     * ({@code PassiveScoringService}, which builds a Haar cascade while loading
     * the MiniFASNet model) waits exactly as it did before. Because that bean is
     * a mandatory singleton, the flag is settled before the context finishes
     * refreshing, so {@link #isOpenCvAvailable()} — a plain, non-blocking read,
     * as it has always been — can never be observed mid-warm by a request.</p>
     *
     * <p>Idempotent, and safe to call from anywhere, including before the
     * context exists: {@link PadLivenessApplication} calls it first thing so the
     * warm covers the whole of {@code SpringApplication.run}, while
     * {@link #init()} repeats it for any embedding that starts the context
     * directly.</p>
     */
    public static void warmOpenCvAsync() {
        Thread existing = openCvWarmupThread;
        if (existing != null) {
            return;
        }
        synchronized (OPEN_CV_LOCK) {
            if (openCvWarmupThread != null) {
                return;
            }
            Thread warmup = new Thread(() -> {
                // Deliberately does NOT log. Logback drops events emitted before
                // Spring Boot initialises its logging system, and this thread
                // starts before SpringApplication.run(), so a message logged here
                // would simply vanish from the packaged app's output. The
                // outcome is reported once, from ensureOpenCvLoaded()'s caller,
                // where the logging context is live.
                loadOpenCvOnce();
            }, "opencv-warmup");
            // Daemon: a slow or stuck extraction must never hold up JVM exit.
            warmup.setDaemon(true);
            openCvWarmupThread = warmup;
            warmup.start();
        }
    }

    @PostConstruct
    void init() {
        warmOpenCvAsync();
        // Returns instantly when the warm already settled the load, and blocks
        // otherwise. Either way this call is what emits the outcome log line,
        // from a thread whose logging is configured.
        ensureOpenCvLoaded();
    }

    /**
     * Loads the OpenCV native library <b>exactly once per JVM</b>, no matter
     * which bean asks first, and returns only once the attempt has settled.
     *
     * <p>Safe to call concurrently from several threads: whoever gets there
     * first performs the load while the rest block on the monitor, so this is
     * both the "warm" entry point and the synchronous barrier that callers
     * depend on.
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
        boolean result = loadOpenCvOnce();
        reportOpenCvOutcomeOnce();
        return result;
    }

    /**
     * Performs the load exactly once per JVM. Returns without logging so it is
     * safe to call from the warm-up thread, before logging is configured.
     */
    private static boolean loadOpenCvOnce() {
        if (openCvAttempted) {
            return openCvAvailable;
        }
        synchronized (OPEN_CV_LOCK) {
            if (openCvAttempted) {
                return openCvAvailable;
            }
            long began = System.currentTimeMillis();
            try {
                nu.pattern.OpenCV.loadLocally();
                openCvAvailable = true;
            } catch (Throwable t) {
                openCvAvailable = false;
                openCvFailure = t;
            }
            openCvLoadMs = System.currentTimeMillis() - began;
            openCvAttempted = true;
        }
        return openCvAvailable;
    }

    /**
     * Emits the load outcome exactly once, on whichever thread first calls
     * {@link #ensureOpenCvLoaded()} after the attempt has settled — in the app
     * that is always a Spring-managed thread with logging initialised, so the
     * line cannot be swallowed by the pre-boot logging gap.
     */
    private static void reportOpenCvOutcomeOnce() {
        synchronized (OPEN_CV_LOCK) {
            if (openCvOutcomeReported) {
                return;
            }
            openCvOutcomeReported = true;
        }
        if (openCvAvailable) {
            log.info("OpenCV native library loaded successfully ({} ms)", openCvLoadMs);
        } else {
            log.warn("OpenCV native library could not be loaded; frame-processing "
                    + "endpoints will return 503 until it is available", openCvFailure);
        }
    }

    /** True when the OpenCV native library loaded and frame processing is usable. */
    public static boolean isOpenCvAvailable() {
        return openCvAvailable;
    }

    /**
     * Backing store for the per-frame pipeline timers.
     *
     * <p>A plain {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry}
     * rather than the actuator one: this service exposes its own
     * {@code /api/v1/metrics}, so the registry only has to be a place to
     * accumulate. Declaring it here (instead of letting the static default in
     * {@code PipelineTimers} stand) means the registry is a Spring bean, so
     * swapping in a Prometheus-backed one later is a one-line change.</p>
     */
    @Bean
    public MeterRegistry meterRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PipelineTimers.use(registry);
        return registry;
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

    /**
     * The rate limiter's time source. Real time in production; tests replace it
     * with a movable clock to exercise window rollover without sleeping out a
     * 60-second window.
     */
    @Bean
    public Clock rateLimitClock() {
        return Clock.systemUTC();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // allowedOriginPatterns (not allowedOrigins) because only
                // patterns support wildcard ports — allowedOrigins("http://localhost:*")
                // never matches any real origin, so cross-origin dev servers were
                // silently rejected. Credentials stay off: nothing here uses cookies.
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                        .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                        .allowedHeaders("Content-Type", "Authorization", "X-Admin-API-Key")
                        // So a console on another dev port can read the limiter's
                        // budget headers (a browser hides unlisted headers).
                        .exposedHeaders("Retry-After", "X-RateLimit-Bucket", "X-RateLimit-Limit",
                                "X-RateLimit-Remaining", "X-RateLimit-Reset")
                        .allowCredentials(false);
            }
        };
    }
}
