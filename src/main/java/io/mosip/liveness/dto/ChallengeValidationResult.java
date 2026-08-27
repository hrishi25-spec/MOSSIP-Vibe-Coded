package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Result of validating challenge frames.
 * Maps to the Python framework's ChallengeValidationResult schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChallengeValidationResult {

    private UUID sessionId;
    private ChallengeResponse challenge;
    private Boolean passed;
    private String action;  // proceed | retry_challenge | reject
    private String message;
}
