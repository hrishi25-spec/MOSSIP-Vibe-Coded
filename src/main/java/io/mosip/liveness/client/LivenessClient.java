package io.mosip.liveness.client;

import java.util.List;
import java.util.UUID;

/**
 * Platform-agnostic interface for Registration Client integration.
 *
 * <p>Both Desktop (JavaFX) and Android (Flutter via Pigeon bridge) use this
 * interface to drive the liveness/PAD pipeline. The concrete implementation
 * is either an HTTP client (calling this Spring Boot service) or a direct
 * in-process engine call (Option A from the integration plan).</p>
 *
 * <h3>Typical call sequence:</h3>
 * <pre>
 *   UUID sessionId = client.createSession("RESIDENT", "L1-CAM-01");
 *   FrameResult result = client.submitFrame(sessionId, frameJpeg);
 *   // if result.action == "escalate_to_active":
 *   //   show prompt from result.challengeType to the user
 *   //   capture N frames during the challenge
 *   ChallengeResult cResult = client.validateChallenge(sessionId, challengeId, challengeFrames);
 *   // if cResult.action == "retry_challenge": repeat
 *   // if cResult.action == "proceed": capture accepted
 *   SessionSummary summary = client.closeSession(sessionId);
 * </pre>
 */
public interface LivenessClient {

    // ---- Session lifecycle ----

    /**
     * Start a new liveness verification session.
     *
     * @param workflowType one of "RESIDENT", "OPERATOR", "SUPERVISOR"
     * @param deviceId     identifier of the capture device (e.g. "L1-CAM-01")
     * @return session response with the new session ID
     */
    SessionInfo createSession(String workflowType, String deviceId);

    /**
     * Close a session and get the audit summary.
     */
    SessionSummary closeSession(UUID sessionId);

    // ---- Frame submission ----

    /**
     * Submit a single JPEG frame for passive liveness + PAD evaluation.
     *
     * @param sessionId  active session ID
     * @param frameJpeg  raw JPEG bytes of the face frame
     * @return processing result — check {@code action} field
     */
    FrameResult submitFrame(UUID sessionId, byte[] frameJpeg);

    // ---- Challenge validation ----

    /**
     * Validate challenge frames captured during an active liveness challenge.
     *
     * @param sessionId     active session ID
     * @param challengeId   challenge ID from the escalation response
     * @param challengeJpegFrames  list of JPEG frames captured during the challenge window
     * @return validation result — check {@code action} field
     */
    ChallengeResult validateChallenge(UUID sessionId, UUID challengeId, List<byte[]> challengeJpegFrames);

    // ---- Config (read-only for clients) ----

    /**
     * Check whether liveness is enabled for a given workflow.
     */
    boolean isLivenessEnabled(String workflowType);

    // ---- Response DTOs (inner records) ----

    record SessionInfo(
            UUID id,
            String workflowType,
            String status
    ) {}

    record FrameResult(
            UUID sessionId,
            String stage,
            boolean faceDetected,
            boolean multipleFaces,
            Double faceQuality,
            Double livenessScore,
            boolean padFlag,
            String padAttackType,
            String action,          // proceed | escalate_to_active | reject | retry_passive
            String challengeId,     // non-null when action == escalate_to_active
            String challengeType,   // BLINK | SMILE | TURN_LEFT | TURN_RIGHT | LOOK_DIRECTION
            Integer timeoutMs,
            Integer attemptNumber,
            String message
    ) {}

    record ChallengeResult(
            UUID sessionId,
            String challengeId,
            boolean passed,
            String action,          // proceed | retry_challenge | reject
            String message
    ) {}

    record SessionSummary(
            UUID id,
            String workflowType,
            String status,
            Boolean finalResult,
            String failureReason,
            Integer totalFramesEvaluated,
            Integer totalChallengesIssued,
            Integer retryCount,
            Long durationMs
    ) {}
}
