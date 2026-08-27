package io.mosip.liveness.api;

import io.mosip.liveness.dto.OperationalMetrics;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metrics endpoints.
 * Maps to the Python framework's routes/metrics.py.
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final LivenessSessionRepository sessionRepo;
    private final FrameEventRepository frameEventRepo;
    private final ChallengeRepository challengeRepo;

    @GetMapping
    public OperationalMetrics getOperationalMetrics() {
        long totalSessions = sessionRepo.count();
        long passed = sessionRepo.countByStatus(SessionStatus.PASSED);
        long failed = sessionRepo.countByStatus(SessionStatus.FAILED);
        long active = sessionRepo.countByStatus(SessionStatus.ACTIVE);

        long escalated = frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE);
        long totalFrames = frameEventRepo.count();
        long totalChallenges = challengeRepo.count();
        long failedChallenges = challengeRepo.countByStatus(ChallengeStatus.FAILED);
        long totalRetries = sessionRepo.sumRetryCount();
        long padRejections = sessionRepo.countByFailureReasonLike("presentation_attack:%");

        return OperationalMetrics.builder()
                .totalSessions((int) totalSessions)
                .passedSessions((int) passed)
                .failedSessions((int) failed)
                .activeSessions((int) active)
                .passRate(safeDiv(passed, totalSessions))
                .avgPassiveToActiveEscalationRate(safeDiv(escalated, totalSessions))
                .avgFramesPerSession(safeDiv(totalFrames, totalSessions))
                .avgChallengesPerSession(safeDiv(totalChallenges, totalSessions))
                .livenessRetryRate(safeDiv(totalRetries, totalSessions))
                .livenessFailureRate(safeDiv(failed, totalSessions))
                .padRejectionRate(safeDiv(padRejections, totalSessions))
                .build();
    }

    private double safeDiv(long a, long b) {
        return b == 0 ? 0.0 : Math.round(((double) a / b) * 10000.0) / 10000.0;
    }
}
