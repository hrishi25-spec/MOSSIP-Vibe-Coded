package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.ChallengeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response for a single challenge.
 * Maps to the Python framework's ChallengeResponse schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChallengeResponse {

    private UUID id;
    private UUID sessionId;
    private ChallengeType challengeType;
    private ChallengeStatus status;
    private Integer attemptNumber;
    private Integer timeoutMs;
    private OffsetDateTime issuedAt;
    private OffsetDateTime completedAt;
}
