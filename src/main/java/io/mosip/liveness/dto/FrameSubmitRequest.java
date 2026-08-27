package io.mosip.liveness.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to submit a single face frame.
 * Maps to the Python framework's FrameSubmitRequest schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FrameSubmitRequest {

    @NotBlank(message = "frame_base64 is required")
    private String frameBase64;
}
