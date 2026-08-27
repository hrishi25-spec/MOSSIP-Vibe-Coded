package io.mosip.liveness.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to update config policy.
 * Maps to the Python framework's ConfigPolicyUpdate schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigPolicyUpdate {

    private Boolean livenessEnabled;

    @Min(0) @Max(1)
    private Double passiveThreshold;

    private Boolean activeLivenessEnabled;

    @Min(1) @Max(5)
    private Integer minChallengeCount;

    private List<String> challengeTypes;

    @Min(1000) @Max(60000)
    private Integer challengeTimeoutMs;

    @Min(0) @Max(10)
    private Integer maxRetryCount;

    private String onRepeatedFailure;  // LOCK, ESCALATE, ALLOW_RETRY
}
