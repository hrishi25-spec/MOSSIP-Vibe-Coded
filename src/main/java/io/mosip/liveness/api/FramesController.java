package io.mosip.liveness.api;

import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.dto.FrameSubmitRequest;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.opencv.core.Mat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frame submission endpoints.
 * Maps to the Python framework's routes/frames.py.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/frames")
@RequiredArgsConstructor
public class FramesController {

    private final LivenessSessionRepository sessionRepo;
    private final DecisionEngineService decisionEngine;
    private final ImageUtils imageUtils;

    @PostMapping
    public FrameProcessResult submitFrame(
            @PathVariable java.util.UUID sessionId,
            @Valid @RequestBody FrameSubmitRequest req) {

        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is not active (status=" + session.getStatus() + ")");
        }

        Mat frame = imageUtils.decodeBase64Frame(req.getFrameBase64());
        try {
            return decisionEngine.processFrame(session, frame, imageUtils);
        } finally {
            frame.release();
            sessionRepo.save(session);
        }
    }
}
