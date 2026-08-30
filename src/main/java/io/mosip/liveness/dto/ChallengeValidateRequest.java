package io.mosip.liveness.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request to validate challenge frames.
 * Maps to the Python framework's ChallengeValidateRequest schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChallengeValidateRequest {

    @NotBlank(message = "challenge_id is required")
    private UUID challengeId;

    @NotEmpty(message = "frames_base64 must contain at least one frame")
    private List<String> framesBase64;
}
