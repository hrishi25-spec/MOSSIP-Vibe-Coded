package io.mosip.liveness.client;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Desktop (JavaFX) adapter that bridges the MOSIP Registration Client's
 * {@code Streamer} frame stream to the liveness engine.
 *
 * <h3>Integration with Streamer.java:</h3>
 * <pre>
 *   // In Streamer.java — add a frame listener field:
 *   private Consumer&lt;byte[]&gt; frameListener;
 *
 *   public void setFrameListener(Consumer&lt;byte[]&gt; listener) {
 *       this.frameListener = listener;
 *   }
 *
 *   // In the frame decode loop, after imageBytes = retrieveNextImage(urlStream):
 *   if (frameListener != null) {
 *       frameListener.accept(imageBytes);
 *   }
 * </pre>
 *
 * <h3>Usage in BiometricFxControl.java:</h3>
 * <pre>
 *   DesktopLivenessAdapter adapter = new DesktopLivenessAdapter(
 *       () -&gt; livenessClient,
 *       () -&gt; sessionContext.getCurrentWorkflow(),
 *       () -&gt; sessionContext.getDeviceId()
 *   );
 *
 *   // Subscribe to Streamer frames
 *   streamer.setFrameListener(adapter::onFrame);
 *
 *   // When face capture is triggered:
 *   adapter.startSession("RESIDENT", "L1-CAM-01");
 *   // Frames flow automatically via the listener
 *   // When liveness passes or fails, the callback fires
 * </pre>
 *
 * <h3>Integration with SessionContext.validateFace():</h3>
 * <pre>
 *   // Before calling authService.authValidator():
 *   DesktopLivenessAdapter authAdapter = new DesktopLivenessAdapter(
 *       () -&gt; livenessClient, () -&gt; "OPERATOR", () -&gt; deviceId
 *   );
 *   authAdapter.startSession("OPERATOR", deviceId);
 *   // ... frames flow, liveness evaluated ...
 *   if (authAdapter.isLivenessPassed()) {
 *       authService.authValidator(biometrics);
 *   }
 * </pre>
 */
public class DesktopLivenessAdapter {

    /**
     * Callback interface for liveness state changes.
     */
    public interface LivenessStateCallback {
        /** Called when a frame is processed and the UI should update. */
        void onFrameProcessed(LivenessClient.FrameResult result);

        /** Called when a challenge is issued — show prompt to user. */
        void onChallengeIssued(String challengeType, int timeoutMs, UUID challengeId);

        /** Called when liveness passes — accept the capture. */
        void onLivenessPassed(LivenessClient.SessionSummary summary);

        /** Called when liveness fails — show error and retry. */
        void onLivenessFailed(String message, boolean padAttack);
    }

    private final Supplier<LivenessClient> clientSupplier;
    private final Supplier<String> workflowSupplier;
    private final Supplier<String> deviceSupplier;

    private UUID currentSessionId;
    private boolean livenessPassed;
    private boolean livenessFailed;
    private LivenessStateCallback callback;

    public DesktopLivenessAdapter(
            Supplier<LivenessClient> clientSupplier,
            Supplier<String> workflowSupplier,
            Supplier<String> deviceSupplier) {
        this.clientSupplier = clientSupplier;
        this.workflowSupplier = workflowSupplier;
        this.deviceSupplier = deviceSupplier;
    }

    public void setCallback(LivenessStateCallback callback) {
        this.callback = callback;
    }

    /** Start a new liveness session for the current workflow. */
    public void startSession() {
        LivenessClient client = clientSupplier.get();
        String workflow = workflowSupplier.get();
        String device = deviceSupplier.get();

        LivenessClient.SessionInfo session = client.createSession(workflow, device);
        this.currentSessionId = session.id();
        this.livenessPassed = false;
        this.livenessFailed = false;
    }

    /**
     * Called by Streamer's frame listener on every decoded frame.
     * Routes the frame to the liveness engine and dispatches the result.
     */
    public void onFrame(byte[] jpegBytes) {
        if (currentSessionId == null || livenessPassed || livenessFailed) {
            return; // no active session or already decided
        }

        LivenessClient client = clientSupplier.get();
        LivenessClient.FrameResult result = client.submitFrame(currentSessionId, jpegBytes);

        if (callback != null) {
            callback.onFrameProcessed(result);
        }

        switch (result.action()) {
            case "proceed" -> {
                livenessPassed = true;
                LivenessClient.SessionSummary summary = client.closeSession(currentSessionId);
                if (callback != null) callback.onLivenessPassed(summary);
            }
            case "escalate_to_active" -> {
                if (callback != null && result.challengeId() != null) {
                    callback.onChallengeIssued(
                            result.challengeType(),
                            result.timeoutMs() != null ? result.timeoutMs() : 10000,
                            UUID.fromString(result.challengeId()));
                }
            }
            case "reject" -> {
                livenessFailed = true;
                boolean padAttack = result.padFlag();
                if (callback != null) callback.onLivenessFailed(result.message(), padAttack);
                client.closeSession(currentSessionId);
            }
            // "retry_passive" → frame processed, wait for next frame
        }
    }

    /**
     * Called when the user completes an active challenge.
     * Submit the captured challenge frames for validation.
     */
    public void submitChallenge(UUID challengeId, List<byte[]> challengeFrames) {
        if (currentSessionId == null || livenessPassed || livenessFailed) {
            return;
        }

        LivenessClient client = clientSupplier.get();
        LivenessClient.ChallengeResult result = client.validateChallenge(
                currentSessionId, challengeId, challengeFrames);

        if (callback != null) {
            switch (result.action()) {
                case "proceed" -> {
                    livenessPassed = true;
                    LivenessClient.SessionSummary summary = client.closeSession(currentSessionId);
                    callback.onLivenessPassed(summary);
                }
                case "retry_challenge" -> {
                    // New challenge will be issued on next frame submission
                }
                case "reject" -> {
                    livenessFailed = true;
                    callback.onLivenessFailed(result.message(), false);
                    client.closeSession(currentSessionId);
                }
            }
        }
    }

    /** Stop the current session without completing it. */
    public void stopSession() {
        if (currentSessionId != null) {
            clientSupplier.get().closeSession(currentSessionId);
            currentSessionId = null;
            livenessPassed = false;
            livenessFailed = false;
        }
    }

    public boolean isLivenessPassed() { return livenessPassed; }
    public boolean isLivenessFailed() { return livenessFailed; }
    public UUID getCurrentSessionId() { return currentSessionId; }
}
