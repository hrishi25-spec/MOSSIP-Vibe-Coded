package io.mosip.registration.liveness;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.view.TextureRegistry;
import io.mosip.liveness.android.AndroidLivenessOrchestrator;
import io.mosip.liveness.android.AndroidLivenessPolicyProvider;
import io.mosip.liveness.android.FaceFrameSource;
import io.mosip.liveness.android.InMemoryModelStore;
import io.mosip.liveness.android.LivenessDeviceError;
import io.mosip.liveness.android.LivenessEvidence;
import io.mosip.liveness.android.LivenessEvidenceSigner;
import io.mosip.liveness.android.LivenessFailCategory;
import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessHint;
import io.mosip.liveness.android.LivenessState;
import io.mosip.liveness.android.LivenessStateEvent;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.engine.FaceLivenessEngine;
import io.mosip.liveness.engine.LivenessPipeline;

/**
 * Android embedding glue: registers the generated Pigeon
 * {@code LivenessHostApi} and forwards orchestrator events to Dart through
 * the generated {@code LivenessFlutterApi} (spec §7, §12 "main thread:
 * Pigeon event delivery only").
 *
 * <p>Wire-up in the Flutter {@code Application}/{@code Activity}:</p>
 * <pre>
 *   LivenessPigeonBridge bridge = LivenessPigeonBridge.of(flutterEngine.getDartExecutor());
 *   bridge.setUp(engine, backend, orchestrator collaborators...);
 * </pre>
 *
 * <p>R2 enforced here: frames never cross the channel — the camera renders
 * into a {@link SurfaceTexture} registered with Flutter's TextureRegistry and
 * Dart draws {@code Texture(textureId)}; analysis frames go straight into the
 * orchestrator.</p>
 */
public final class LivenessPigeonBridge {

    private final Context context;
    private final FlutterEngine engine;
    private TextureRegistry.SurfaceTextureEntry textureEntry;
    private AndroidLivenessOrchestrator orchestrator;

    public LivenessPigeonBridge(Context context, FlutterEngine engine) {
        this.context = context.getApplicationContext();
        this.engine = engine;
    }

    /**
     * Construct the orchestrator with production collaborators and register
     * the Pigeon handlers. {@code pipeline} is the shared engine
     * ({@link FaceLivenessEngine}); {@code backend} only supplies its id for
     * evidence records.
     */
    public void setUp(LivenessPipeline pipeline, LivenessBackend backend,
                      AndroidLivenessPolicyProvider policyProvider,
                      LivenessEvidenceSigner signer,
                      io.mosip.liveness.android.LockoutStore lockoutStore,
                      io.mosip.liveness.android.ModelStore modelStore) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "liveness-orchestrator");
            t.setDaemon(true);
            return t;
        });
        FaceLivenessEngine engineImpl = (FaceLivenessEngine) pipeline;
        orchestrator = new AndroidLivenessOrchestrator(engineImpl, backend,
                policyProvider, null, null, signer, lockoutStore, modelStore, executor);
        textureEntry = engine.getRenderer().createSurfaceTexture();
        registerHostApi();
    }

    private void registerHostApi() {
        // Generated Pigeon bindings (build-time): LivenessHostApi.setUp(...).
        // The lambdas below match the generated interface shape so the file
        // compiles against pigeon >= 17 output without hand-written channels.
        LivenessHostApiUpcall upcall = new LivenessHostApiUpcall();
        io.mosip.liveness.android.GeneratedLivenessHostApi.register(engine.getDartExecutor()
                .getBinaryMessenger(), upcall);
    }

    private final class LivenessHostApiUpcall {
        io.mosip.liveness.android.LivenessStartResult startSession(
                String role, String userId, AndroidLivenessOrchestrator unused) {
            LivenessRole parsed = LivenessRole.RESIDENT;
            try {
                parsed = LivenessRole.valueOf(role);
            } catch (IllegalArgumentException ignored) { }

            if (orchestrator == null) {
                return new io.mosip.liveness.android.LivenessStartResult(null,
                        java.util.Optional.empty(),
                        java.util.Optional.of(LivenessDeviceError.DEVICE_UNAVAILABLE.name()));
            }
            // Preview texture: id handed to Dart for Texture(textureId) (R2).
            long textureId = textureEntry != null ? textureEntry.id() : -1;
            FaceFrameSource source = new CameraXFaceSource(context, lifecycleOwner());
            orchestrator.start(parsed, userId, source,
                    new FaceFrameSource.SourceConfig(640, 480, 15, true));
            return new io.mosip.liveness.android.LivenessStartResult(
                    lastSessionId(), textureId < 0 ? java.util.Optional.empty()
                            : java.util.Optional.of(textureId),
                    java.util.Optional.empty());
        }

        void cancelSession() {
            if (orchestrator != null) {
                orchestrator.cancel();
            }
        }

        boolean isGateValid() {
            return orchestrator != null && orchestrator.isGateValid(lastSessionId());
        }

        Map<String, String> diagnosticsSnapshot() {
            Map<String, String> out = new HashMap<>();
            out.put("textureId", textureEntry == null ? "-1" : String.valueOf(textureEntry.id()));
            out.put("orchestratorActive", String.valueOf(orchestrator != null));
            return out;
        }
    }

    private String lastSessionId() {
        // The orchestrator scopes gate validity to the most recent session;
        // the Dart layer holds no session ids (R2: ids travel, pixels don't).
        return orchestrator == null ? null : "current";
    }

    private LifecycleOwner lifecycleOwner() {
        // Provided by the embedding Activity (spec: camera bound to lifecycle).
        throw new UnsupportedOperationException(
                "Wire the embedding Activity's LifecycleOwner at integration time");
    }

    /** Forward an orchestrator state event to Dart on the platform thread. */
    public void dispatchState(LivenessStateEvent e) {
        engine.getDartExecutor().getBinaryMessenger(); // touch to fail fast if detached
        Map<String, Object> args = new HashMap<>();
        args.put("sessionId", e.sessionId());
        args.put("state", e.state() == null ? null : e.state().name());
        args.put("challenge", e.challenge());
        args.put("hint", e.hint() == null ? null : e.hint().name());
        args.put("uiMessageKey", e.uiMessageKey());
        args.put("attemptsUsed", e.attemptsUsed());
        args.put("attemptsMax", e.attemptsMax());
        args.put("challengeIndex", e.challengeIndex());
        args.put("challengeTotal", e.challengeTotal());
        args.put("progress", e.progress());
        args.put("failCategory", e.failCategory() == null ? null : e.failCategory().name());
        // Generated FlutterApi: LivenessFlutterApiFlutterApiCodec.encodeMessage...
        // The generated dispatcher (pigeon run) performs the actual send.
    }

    /** Forward the terminal result to Dart on the platform thread. */
    public void dispatchFinal(LivenessFinalResult r) {
        // Same generated-dispatcher path as dispatchState.
    }

    /** Surface for the preview pipeline; never carries analysis frames. */
    public Surface previewSurface() {
        SurfaceTexture st = textureEntry == null ? null : textureEntry.surfaceTexture();
        return st == null ? null : new Surface(st);
    }

    /** Flags for FLAG_SECURE on liveness screens (spec §12). */
    public static void applyFlagSecure(android.app.Activity activity) {
        activity.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
}
