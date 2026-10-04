package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.liveness.models.enums.LivenessStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Result of processing a single frame through the pipeline.
 * Maps to the Python framework's FrameProcessResult schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FrameProcessResult {

    private UUID sessionId;
    private LivenessStage stage;
    private Boolean faceDetected;
    private Boolean multipleFaces;
    private Double faceQuality;
    private Double livenessScore;
    private Boolean padFlag;
    private String padAttackType;
    // proceed | escalate_to_active | retry_passive | reject | locked |
    // escalate_to_operator | failed
    private String action;
    private ChallengeInfo challenge;
    /**
     * True only when a session ended but the client may open a fresh one
     * (the ALLOW_RETRY repeated-failure action). False/null for lock-out or
     * escalation. Never grants a further challenge in this session.
     */
    private Boolean mayRetrySession;
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChallengeInfo {
        private UUID challengeId;
        private String challengeType;
        private Integer timeoutMs;
        private Integer attemptNumber;
    }
}
