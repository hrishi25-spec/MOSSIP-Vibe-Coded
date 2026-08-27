package io.mosip.liveness.api;

import io.mosip.liveness.dto.ChallengeValidateRequest;
import io.mosip.liveness.dto.ChallengeValidationResult;
import io.mosip.liveness.dto.ChallengeResponse;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.opencv.core.Mat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Challenge validation endpoints.
 * Maps to the Python framework's routes/challenges.py.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/challenges")
@RequiredArgsConstructor
public class ChallengesController {

    private final LivenessSessionRepository sessionRepo;
    private final ChallengeRepository challengeRepo;
    private final DecisionEngineService decisionEngine;
    private final ImageUtils imageUtils;

    @PostMapping("/validate")
    public ChallengeValidationResult validateChallenge(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChallengeValidateRequest req) {

        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is not active (status=" + session.getStatus() + ")");
        }

        ChallengeEntity challenge = challengeRepo.findById(req.getChallengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Challenge not found for this session"));

        if (!challenge.getSession().getId().equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Challenge not found for this session");
        }
        if (challenge.getStatus() != ChallengeStatus.ISSUED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Challenge is not awaiting validation (status=" + challenge.getStatus() + ")");
        }

        List<Mat> frames = new ArrayList<>();
        try {
            for (String frameBase64 : req.getFramesBase64()) {
                frames.add(imageUtils.decodeBase64Frame(frameBase64));
            }

            Map<String, Object> result = decisionEngine.processChallengeValidation(
                    session, challenge, frames, imageUtils);
            challengeRepo.save(challenge);
            sessionRepo.save(session);

            ChallengeResponse challengeResp = ChallengeResponse.builder()
                    .id(challenge.getId())
                    .sessionId(sessionId)
                    .challengeType(challenge.getChallengeType())
                    .status(challenge.getStatus())
                    .attemptNumber(challenge.getAttemptNumber())
                    .timeoutMs(challenge.getTimeoutMs())
                    .issuedAt(challenge.getIssuedAt())
                    .completedAt(challenge.getCompletedAt())
                    .build();

            return ChallengeValidationResult.builder()
                    .sessionId(sessionId)
                    .challenge(challengeResp)
                    .passed((Boolean) result.get("passed"))
                    .action((String) result.get("action"))
                    .message((String) result.get("message"))
                    .build();
        } finally {
            frames.forEach(Mat::release);
        }
    }
}
