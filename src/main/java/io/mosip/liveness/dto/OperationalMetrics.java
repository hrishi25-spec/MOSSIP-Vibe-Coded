package io.mosip.liveness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anonymized operational metrics.
 * Maps to the Python framework's OperationalMetrics schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationalMetrics {

    private Integer totalSessions;
    private Integer passedSessions;
    private Integer failedSessions;
    private Integer activeSessions;
    private Double passRate;
    private Double avgPassiveToActiveEscalationRate;
    private Double avgFramesPerSession;
    private Double avgChallengesPerSession;
    private Double livenessRetryRate;
    private Double livenessFailureRate;
    private Double padRejectionRate;
}
