package io.mosip.liveness.services;

import io.mosip.liveness.backend.OnnxMiniFasNetBackend;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.metrics.PipelineTimers;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the passive liveness score and (optional) model PAD verdict for
 * the HTTP decision path.
 *
 * <p>Two scorers, one façade:</p>
 * <ul>
 *   <li><b>{@code auto}</b> (default) — MiniFASNet-V2 via
 *       {@link OnnxMiniFasNetBackend} when the runtime and model load; the
 *       score is then the model's live-class probability, i.e. an actual
 *       liveness confidence.</li>
 *   <li><b>{@code heuristic}</b> — the original OpenCV quality formula
 *       (sharpness + brightness + eye symmetry), kept as an explicit fallback
 *       so tests, model-less CI and a missing/corrupt model degrade to
 *       today's behaviour instead of failing startup.</li>
 * </ul>
 *
 * <p>Configured by {@code mosip.liveness.backend} ({@code auto|heuristic}) and
 * {@code mosip.liveness.model-path} (optional filesystem override of the
 * bundled classpath model).</p>
 *
 * <p><b>Nothing loads at construction.</b> The OpenCV natives (~6 s of
 * extraction) and the ONNX model used to load in the constructor, which made
 * every boot — and every test context — pay for a model the first frame may
 * never need. Both now settle on the first caller that asks for a verdict
 * ({@link #score}, {@link #assessPad}, {@link #isModelAvailable()},
 * {@link #scorerId()}), exactly once, behind {@link #resolveScorer()}.</p>
 */
@Service
public class PassiveScoringService {

    private static final Logger log = LoggerFactory.getLogger(PassiveScoringService.class);

    /** Use the model when it loads; fall back to the heuristic otherwise. */
    public static final String MODE_AUTO = "auto";
    /** Never load the model — always use the OpenCV heuristic. */
    public static final String MODE_HEURISTIC = "heuristic";

    private final LivenessEngineService heuristicScorer;
    private final String requestedMode;
    private final String modelPath;

    /** The resolved model backend; null until {@link #resolveScorer()} runs. */
    private volatile OnnxMiniFasNetBackend model;
    private volatile boolean modelAvailable;
    /** Volatile so the per-frame fast path can skip the monitor entirely. */
    private volatile boolean resolved;

    public PassiveScoringService(LivenessEngineService heuristicScorer,
                                 @Value("${mosip.liveness.backend:auto}") String mode,
                                 @Value("${mosip.liveness.model-path:}") String modelPath) {
        this.heuristicScorer = heuristicScorer;
        this.requestedMode = mode;
        this.modelPath = modelPath;
        // Deliberately loads nothing here: bean creation must stay cheap,
        // and bean order is not guaranteed, so a constructor load would
        // also race the AppConfig warm-up. First use pays instead.
    }

    /**
     * Resolves the scorer exactly once, on the first caller that needs it.
     *
     * <p>Safe to call concurrently: the first thread performs the OpenCV
     * barrier and the model load while the rest block on the monitor, and
     * {@code resolved} is flipped only after both fields are set, so no
     * caller ever observes a half-initialised scorer.</p>
     *
     * <p>A failed resolution is final — the same one-shot semantics the
     * constructor used to have — so a missing model degrades to the
     * heuristic instead of retrying (and re-paying the load) per frame.</p>
     */
    private void resolveScorer() {
        if (resolved) {
            return;
        }
        synchronized (this) {
            if (resolved) {
                return;
            }
            // The native library is the one hard dependency: the model
            // backend builds a Haar cascade, and without the natives that
            // is an UnsatisfiedLinkError. AppConfig's guard makes the load
            // idempotent JVM-wide, so this stays the service's only OpenCV
            // entry point, and it blocks only if the background warm-up
            // has not settled yet.
            boolean opencvReady = io.mosip.liveness.app.config.AppConfig.ensureOpenCvLoaded();

            OnnxMiniFasNetBackend candidate = null;
            if (!opencvReady) {
                log.warn("OpenCV natives unavailable; not attempting to load the liveness model");
            } else if (MODE_HEURISTIC.equalsIgnoreCase(String.valueOf(requestedMode).trim())) {
                log.info("Passive liveness model disabled by config (mosip.liveness.backend={})", requestedMode);
            } else {
                candidate = tryLoadModel(modelPath);
            }
            this.model = candidate;
            this.modelAvailable = candidate != null && candidate.isReady();
            this.resolved = true;
            log.info("Passive liveness scorer: {} (requested mode '{}')",
                    modelAvailable ? model.id() : "opencv-heuristic", requestedMode);
        }
    }

    private OnnxMiniFasNetBackend tryLoadModel(String modelPath) {
        if (!OnnxMiniFasNetBackend.isRuntimeAvailable()) {
            log.warn("ONNX Runtime is not on the classpath; falling back to the heuristic scorer");
            return null;
        }
        try {
            OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
            Map<String, String> options = new HashMap<>();
            if (modelPath != null && !modelPath.isBlank()) {
                options.put(OnnxMiniFasNetBackend.OPTION_MODEL_PATH, modelPath.trim());
            }
            backend.initialize(options);
            return backend;
        } catch (LivenessException e) {
            log.warn("Liveness model unavailable ({}); falling back to the heuristic scorer",
                    e.getMessage());
            return null;
        } catch (Throwable t) {
            log.warn("Liveness model failed to load ({}); falling back to the heuristic scorer",
                    t.toString());
            return null;
        }
    }

    /**
     * True when the MiniFASNet model is loaded and producing real liveness
     * confidences. Triggers the one-time resolution, so the first call may
     * block on the native load and the model.
     */
    public boolean isModelAvailable() {
        resolveScorer();
        return modelAvailable;
    }

    /**
     * Stable identifier for audit records: which scorer produced a score.
     * Triggers the one-time resolution like {@link #isModelAvailable()}.
     */
    public String scorerId() {
        resolveScorer();
        return modelAvailable ? model.id() : "opencv-heuristic";
    }

    /**
     * Passive liveness score in [0,1] for one frame.
     *
     * <p>The first call also resolves the scorer (native library + model),
     * so it can be slow; every subsequent call is the steady-state
     * pipeline.
     *
     * <p>With the model: live-class probability of MiniFASNet-V2 on the face
     * crop. Without: the OpenCV quality heuristic. If the model cannot analyse
     * the frame (no/multiple faces seen at model level even though the HTTP
     * face gate accepted it, or an inference error), this frame is scored by
     * the heuristic rather than being punished with a zero.</p>
     */
    public double score(Mat frame, LivenessEngineService.FaceObservation observation, ImageUtils imageUtils) {
        resolveScorer();
        if (!modelAvailable) {
            return PipelineTimers.timed(PipelineTimers.HEURISTIC_SCORE,
                    () -> heuristicScorer.scorePassive(frame, observation, imageUtils));
        }
        // The model and the fallback are timed separately: they are different
        // pipelines with very different costs, and merging them would make an
        // `auto` deployment that has silently fallen back look like it is
        // paying ONNX prices for heuristic work.
        return PipelineTimers.timed(PipelineTimers.ONNX_SCORE, () -> {
            try {
                Frame coreFrame = toCoreFrame(frame);
                FaceSignals signals = toSignals(observation);
                if (model.analyzeFrame(coreFrame).faceCount() != 1) {
                    return heuristicScorer.scorePassive(frame, observation, imageUtils);
                }
                return model.scorePassiveLiveness(coreFrame, signals);
            } catch (RuntimeException e) {
                log.debug("Model scoring failed, using heuristic for this frame: {}", e.toString());
                return heuristicScorer.scorePassive(frame, observation, imageUtils);
            }
        });
    }

    /**
     * Model PAD opinion for one frame, empty when no model is loaded.
     * Triggers the one-time resolution like {@link #score}.
     *
     * <p>MiniFASNet classifies a live class (index 1) plus two attack classes
     * (index 0 = print, 2 = replay) from the same inference the liveness score
     * comes from, so the decision path ORs this
     * with the FFT/texture heuristics in {@link PadEngineService}: either
     * source flagging an attack is terminal.</p>
     */
    public Optional<PadVerdict> assessPad(Mat frame, LivenessEngineService.FaceObservation observation) {
        resolveScorer();
        if (!modelAvailable) {
            return Optional.empty();
        }
        return PipelineTimers.timed(PipelineTimers.PAD_ONNX, () -> {
            try {
                Frame coreFrame = toCoreFrame(frame);
                FaceSignals signals = toSignals(observation);
                if (model.analyzeFrame(coreFrame).faceCount() != 1) {
                    return Optional.empty();
                }
                return Optional.of(model.assessPad(coreFrame, signals));
            } catch (RuntimeException e) {
                log.debug("Model PAD failed, heuristic PAD only for this frame: {}", e.toString());
                return Optional.empty();
            }
        });
    }

    /** The HTTP path's face gate has already accepted exactly one face. */
    private static FaceSignals toSignals(LivenessEngineService.FaceObservation observation) {
        double quality = observation.faceQuality() != null ? observation.faceQuality() : 0.0;
        return new FaceSignals(observation.multipleFaces() ? 2 : (observation.faceDetected() ? 1 : 0),
                quality, null, null, null, null, null, null, null);
    }

    /**
     * OpenCV Mat (BGR, as produced by {@code Imgcodecs.imdecode}) &rarr; the
     * platform-neutral {@link Frame} (RGB_888) the backend SPI speaks. The
     * backend converts back to BGR for the model, keeping the SPI contract
     * correct for any implementation, not just this one.
     */
    private static Frame toCoreFrame(Mat frame) {
        Mat rgb = new Mat();
        try {
            Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_BGR2RGB);
            byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
            rgb.get(0, 0, data);   // cvtColor output is freshly allocated ⇒ continuous
            return Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                    System.currentTimeMillis(), 0);
        } finally {
            rgb.release();
        }
    }
}
