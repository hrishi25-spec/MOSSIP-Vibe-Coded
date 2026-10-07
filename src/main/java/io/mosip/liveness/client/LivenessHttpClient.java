package io.mosip.liveness.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLContext;

/**
 * HTTP client implementation of {@link LivenessClient}.
 *
 * <p>Calls the Spring Boot REST API endpoints. Suitable for both Desktop
 * (Java 11+ HttpClient) and Android (OkHttp or similar — adapt the
 * transport layer). The JSON mapping uses Jackson's {@link ObjectMapper}.</p>
 *
 * <h3>Usage from Desktop (JavaFX):</h3>
 * <pre>
 *   LivenessClient client = new LivenessHttpClient("http://localhost:8000");
 *   SessionInfo session = client.createSession("RESIDENT", "L1-CAM-01");
 *   FrameResult result = client.submitFrame(session.id(), jpegBytes);
 * </pre>
 *
 * <h3>Usage from Android (via Pigeon bridge):</h3>
 * <pre>
 *   // In HostApiModule.java (native Java side):
 *   LivenessClient client = new LivenessHttpClient("http://10.0.2.2:8000");
 *   // Feed results back to Dart via Pigeon response objects
 * </pre>
 *
 * <p>For environments requiring custom TLS configuration (e.g., certificate pinning),
 * use the {@link #LivenessHttpClient(String, SSLContext)} overload.</p>
 */
public class LivenessHttpClient implements LivenessClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Creates an instance with default JDK TLS settings. */
    public LivenessHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates an instance with the provided {@code SSLContext}, allowing custom
     * trust stores, certificate pinning, or other TLS adjustments.
     *
     * @param baseUrl   the base URL of the liveness service (trailing slash optional)
     * @param sslContext the SSLContext to use for HTTPS connections
     */
    public LivenessHttpClient(String baseUrl, SSLContext sslContext) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ---- Session lifecycle ----

    @Override
    public SessionInfo createSession(String workflowType, String deviceId) {
        try {
            String body = objectMapper.writeValueAsString(new CreateSessionBody(workflowType, deviceId));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseSessionInfo(objectMapper.readTree(response.body()));
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to create session", e);
        }
    }

    @Override
    public SessionSummary closeSession(UUID sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/sessions/" + sessionId + "/close"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());

            return new SessionSummary(
                    UUID.fromString(json.get("id").asText()),
                    json.get("workflowType").asText(),
                    json.get("status").asText(),
                    json.has("finalResult") && !json.get("finalResult").isNull()
                            ? json.get("finalResult").asBoolean() : null,
                    json.has("failureReason") && !json.get("failureReason").isNull()
                            ? json.get("failureReason").asText() : null,
                    json.has("totalFramesEvaluated") ? json.get("totalFramesEvaluated").asInt() : 0,
                    json.has("totalChallengesIssued") ? json.get("totalChallengesIssued").asInt() : 0,
                    json.has("retryCount") ? json.get("retryCount").asInt() : 0,
                    json.has("durationMs") ? json.get("durationMs").asLong() : 0L
            );
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to close session", e);
        }
    }

    // ---- Frame submission ----

    @Override
    public FrameResult submitFrame(UUID sessionId, byte[] frameJpeg) {
        try {
            String frameBase64 = Base64.getEncoder().encodeToString(frameJpeg);
            String body = objectMapper.writeValueAsString(new SubmitFrameBody(frameBase64));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/sessions/" + sessionId + "/frames"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());

            return parseFrameResult(json);
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to submit frame", e);
        }
    }

    // ---- Challenge validation ----

    @Override
    public ChallengeResult validateChallenge(UUID sessionId, UUID challengeId, List<byte[]> challengeJpegFrames) {
        try {
            List<String> framesBase64 = challengeJpegFrames.stream()
                    .map(f -> Base64.getEncoder().encodeToString(f))
                    .toList();

            String body = objectMapper.writeValueAsString(
                    new ValidateChallengeBody(challengeId.toString(), framesBase64));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/sessions/" + sessionId + "/challenges/validate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseChallengeResult(sessionId, objectMapper.readTree(response.body()));
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to validate challenge", e);
        }
    }

    // ---- Config check ----

    @Override
    public boolean isLivenessEnabled(String workflowType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/config/" + workflowType.toUpperCase()))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());
            return json.has("livenessEnabled") && json.get("livenessEnabled").asBoolean();
        } catch (IOException | InterruptedException e) {
            // Default to enabled if config service is unreachable
            return true;
        }
    }

    // ---- JSON parsing helpers ----
    //
    // Package-private and static: the response shapes are the part of this client
    // worth pinning in a test, and they can be pinned from a JSON literal without
    // standing up a server.

    static SessionInfo parseSessionInfo(JsonNode json) {
        return new SessionInfo(
                UUID.fromString(json.get("id").asText()),
                json.get("workflowType").asText(),
                json.get("status").asText(),
                parsePolicy(json.get("policy"))
        );
    }

    /**
     * The frozen policy is {@code null} on legacy sessions; every field is read
     * defensively so a policy that later grows cannot break an older client.
     */
    static Policy parsePolicy(JsonNode policy) {
        if (policy == null || policy.isNull() || !policy.isObject()) {
            return null;
        }
        return new Policy(
                policy.has("minChallengeCount") ? policy.get("minChallengeCount").asInt() : 0,
                policy.has("maxRetries") ? policy.get("maxRetries").asInt() : 0,
                policy.has("challengeTimeoutMs") ? policy.get("challengeTimeoutMs").asLong() : 0L,
                policy.has("onRepeatedFailure") && !policy.get("onRepeatedFailure").isNull()
                        ? policy.get("onRepeatedFailure").asText() : null
        );
    }

    static FrameResult parseFrameResult(JsonNode json) {
        return new FrameResult(
                UUID.fromString(json.get("sessionId").asText()),
                json.get("stage").asText(),
                json.has("faceDetected") && json.get("faceDetected").asBoolean(),
                json.has("multipleFaces") && json.get("multipleFaces").asBoolean(),
                json.has("faceQuality") && !json.get("faceQuality").isNull()
                        ? json.get("faceQuality").asDouble() : null,
                json.has("livenessScore") && !json.get("livenessScore").isNull()
                        ? json.get("livenessScore").asDouble() : null,
                json.has("padFlag") && json.get("padFlag").asBoolean(),
                json.has("padAttackType") && !json.get("padAttackType").isNull()
                        ? json.get("padAttackType").asText() : null,
                json.get("action").asText(),
                parseChallenge(json.get("challenge")),
                json.has("message") && !json.get("message").isNull()
                        ? json.get("message").asText() : null
        );
    }

    static ChallengeResult parseChallengeResult(UUID sessionId, JsonNode json) {
        return new ChallengeResult(
                sessionId,
                json.has("passed") && json.get("passed").asBoolean(),
                json.get("action").asText(),
                json.has("message") && !json.get("message").isNull()
                        ? json.get("message").asText() : null,
                parseChallenge(json.get("challenge"))
        );
    }

    /**
     * The nested challenge object, or {@code null} when the response carries none.
     * Frame responses name the id {@code challengeId} and validation responses
     * ({@code ChallengeResponse}) name it {@code id}; both are accepted here so the
     * client does not depend on which endpoint produced the object.
     */
    static Challenge parseChallenge(JsonNode challenge) {
        if (challenge == null || challenge.isNull() || !challenge.isObject()) {
            return null;
        }
        return new Challenge(
                text(challenge, "challengeId", "id"),
                text(challenge, "challengeType"),
                integer(challenge, "timeoutMs"),
                integer(challenge, "attemptNumber")
        );
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.has(name) && !node.get(name).isNull() && !node.get(name).asText().isEmpty()) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node, String name) {
        return node.has(name) && !node.get(name).isNull() ? node.get(name).asInt() : null;
    }

    // ---- Internal request body records ----

    @SuppressWarnings("unused")
    private record CreateSessionBody(String workflowType, String deviceId) {}
    @SuppressWarnings("unused")
    private record SubmitFrameBody(String frameBase64) {}
    @SuppressWarnings("unused")
    private record ValidateChallengeBody(String challengeId, List<String> framesBase64) {}
}
