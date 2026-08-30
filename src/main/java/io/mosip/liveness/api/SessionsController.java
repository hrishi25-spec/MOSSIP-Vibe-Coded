package io.mosip.liveness.api;

import io.mosip.liveness.dto.SessionCreateRequest;
import io.mosip.liveness.dto.SessionResponse;
import io.mosip.liveness.dto.SessionSummary;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * Session lifecycle endpoints.
 * Maps to the Python framework's routes/sessions.py.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionsController {

    private final LivenessSessionRepository sessionRepo;
    private final FrameEventRepository frameEventRepo;
    private final ChallengeRepository challengeRepo;

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@Valid @RequestBody SessionCreateRequest req) {
        LivenessSession session = LivenessSession.builder()
                .workflowType(req.getWorkflowType())
                .deviceId(req.getDeviceId())
                .subjectRef(req.getSubjectRef())
                .online(req.getOnline() != null ? req.getOnline() : true)
                .status(SessionStatus.ACTIVE)
                .retryCount(0)
                .build();
        sessionRepo.save(session);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable java.util.UUID sessionId) {
        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        return ResponseEntity.ok(toResponse(session));
    }

    @PostMapping("/{sessionId}/close")
    public ResponseEntity<SessionSummary> closeSession(@PathVariable java.util.UUID sessionId) {
        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getStatus() == SessionStatus.ACTIVE) {
            session.setStatus(SessionStatus.EXPIRED);
            session.setClosedAt(OffsetDateTime.now());
            sessionRepo.save(session);
        }

        long durationMs = 0;
        if (session.getClosedAt() != null && session.getCreatedAt() != null) {
            durationMs = java.time.Duration.between(session.getCreatedAt(), session.getClosedAt()).toMillis();
        }

        return ResponseEntity.ok(SessionSummary.builder()
                .id(session.getId())
                .workflowType(session.getWorkflowType())
                .status(session.getStatus())
                .finalResult(session.getFinalResult())
                .failureReason(session.getFailureReason())
                .totalFramesEvaluated((int) frameEventRepo.countBySessionId(sessionId))
                .totalChallengesIssued((int) challengeRepo.countBySessionId(sessionId))
                .retryCount(session.getRetryCount())
                .durationMs(durationMs)
                .build());
    }

    private SessionResponse toResponse(LivenessSession s) {
        return SessionResponse.builder()
                .id(s.getId())
                .workflowType(s.getWorkflowType())
                .deviceId(s.getDeviceId())
                .status(s.getStatus())
                .currentStage(s.getCurrentStage())
                .retryCount(s.getRetryCount())
                .online(s.getOnline())
                .finalResult(s.getFinalResult())
                .failureReason(s.getFailureReason())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .closedAt(s.getClosedAt())
                .build();
    }
}
