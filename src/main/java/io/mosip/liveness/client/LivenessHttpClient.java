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
 */
public class LivenessHttpClient implements LivenessClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LivenessHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
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
            JsonNode json = objectMapper.readTree(response.body());

            return new SessionInfo(
                    UUID.fromString(json.get("id").asText()),
                    json.get("workflowType").asText(),
                    json.get("status").asText()
            );
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
            JsonNode json = objectMapper.readTree(response.body());

            return new ChallengeResult(
                    sessionId,
                    json.has("challenge") && json.get("challenge").has("id")
                            ? json.get("challenge").get("id").asText() : null,
                    json.get("passed").asBoolean(),
                    json.get("action").asText(),
                    json.get("message").asText()
            );
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

    private FrameResult parseFrameResult(JsonNode json) {
        String challengeId = null, challengeType = null;
        Integer timeoutMs = null, attemptNumber = null;

        if (json.has("challenge") && json.get("challenge") != null && !json.get("challenge").isNull()) {
            JsonNode ch = json.get("challenge");
            challengeId = ch.has("challengeId") ? ch.get("challengeId").asText() : null;
            challengeType = ch.has("challengeType") ? ch.get("challengeType").asText() : null;
            timeoutMs = ch.has("timeoutMs") ? ch.get("timeoutMs").asInt() : null;
            attemptNumber = ch.has("attemptNumber") ? ch.get("attemptNumber").asInt() : null;
        }

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
                json.has("padAttackType") ? json.get("padAttackType").asText() : null,
                json.get("action").asText(),
                challengeId,
                challengeType,
                timeoutMs,
                attemptNumber,
                json.has("message") ? json.get("message").asText() : null
        );
    }

    // ---- Internal request body records ----

    @SuppressWarnings("unused")
    private record CreateSessionBody(String workflowType, String deviceId) {}
    @SuppressWarnings("unused")
    private record SubmitFrameBody(String frameBase64) {}
    @SuppressWarnings("unused")
    private record ValidateChallengeBody(String challengeId, List<String> framesBase64) {}
}
