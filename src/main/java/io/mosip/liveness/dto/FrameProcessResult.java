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
    private String action;  // proceed | escalate_to_active | reject | retry_passive
    private ChallengeInfo challenge;
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
