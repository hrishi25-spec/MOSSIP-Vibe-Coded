package io.mosip.liveness.api;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.dto.ConfigPolicyResponse;
import io.mosip.liveness.dto.ConfigPolicyUpdate;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.services.ConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Config policy endpoints.
 * Maps to the Python framework's routes/config.py.
 *
 * Runtime updates to the config take effect immediately — the
 * DecisionEngineService reads fresh config from the DB on every call.
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigPolicyRepository configRepo;
    private final ConfigService configService;

    @GetMapping("/{workflowType}")
    public ConfigPolicyResponse getPolicy(@PathVariable WorkflowType workflowType) {
        ConfigPolicy policy = getOrCreatePolicy(workflowType);
        return toResponse(policy);
    }

    /**
     * Get the effective (computed) policy as the engine sees it.
     * Shows the merged result of DB config + engine defaults.
     */
    @GetMapping("/{workflowType}/effective")
    public EffectivePolicy getEffectivePolicy(@PathVariable WorkflowType workflowType) {
        return configService.getEffectivePolicy(configService.toCoreWorkflow(workflowType));
    }

    @PutMapping("/{workflowType}")
    public ConfigPolicyResponse setPolicy(
            @PathVariable WorkflowType workflowType,
            @Valid @RequestBody ConfigPolicyUpdate update) {
        ConfigPolicy policy = getOrCreatePolicy(workflowType);

        // Validate before applying
        validate(update);

        if (update.getLivenessEnabled() != null) policy.setLivenessEnabled(update.getLivenessEnabled());
        if (update.getPassiveThreshold() != null) policy.setPassiveThreshold(update.getPassiveThreshold());
        if (update.getActiveLivenessEnabled() != null) policy.setActiveLivenessEnabled(update.getActiveLivenessEnabled());
        if (update.getMinChallengeCount() != null) policy.setMinChallengeCount(update.getMinChallengeCount());
        if (update.getChallengeTypes() != null) policy.setChallengeTypes(update.getChallengeTypes());
        if (update.getChallengeTimeoutMs() != null) policy.setChallengeTimeoutMs(update.getChallengeTimeoutMs());
        if (update.getMaxRetryCount() != null) policy.setMaxRetryCount(update.getMaxRetryCount());
        if (update.getOnRepeatedFailure() != null) {
            policy.setOnRepeatedFailure(FailurePolicy.valueOf(update.getOnRepeatedFailure()));
        }

        configRepo.save(policy);
        return toResponse(policy);
    }

    private void validate(ConfigPolicyUpdate update) {
        if (update.getPassiveThreshold() != null &&
                (update.getPassiveThreshold() < 0.0 || update.getPassiveThreshold() > 1.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "passiveThreshold must be between 0.0 and 1.0");
        }
        if (update.getMinChallengeCount() != null && update.getMinChallengeCount() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minChallengeCount must be >= 1");
        }
        if (update.getChallengeTimeoutMs() != null && update.getChallengeTimeoutMs() < 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "challengeTimeoutMs must be >= 1000");
        }
        if (update.getMaxRetryCount() != null && update.getMaxRetryCount() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxRetryCount must be >= 0");
        }
        if (update.getOnRepeatedFailure() != null) {
            try {
                FailurePolicy.valueOf(update.getOnRepeatedFailure());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "onRepeatedFailure must be one of: LOCK, ESCALATE, ALLOW_RETRY");
            }
        }
        if (update.getChallengeTypes() != null && update.getChallengeTypes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "challengeTypes must not be empty when activeLiveness is enabled");
        }
    }

    private ConfigPolicy getOrCreatePolicy(WorkflowType workflowType) {
        return configRepo.findByWorkflowType(workflowType).orElseGet(() -> {
            ConfigPolicy policy = ConfigPolicy.builder()
                    .workflowType(workflowType)
                    .livenessEnabled(true)
                    .passiveThreshold(io.mosip.liveness.config.LivenessConfig.DEFAULT_PASSIVE_THRESHOLD)
                    .activeLivenessEnabled(true)
                    .minChallengeCount(1)
                    .challengeTypes(List.of("blink", "smile", "turn_left", "turn_right"))
                    .challengeTimeoutMs((int) io.mosip.liveness.config.LivenessConfig.DEFAULT_CHALLENGE_TIMEOUT_MS)
                    .maxRetryCount(3)
                    .onRepeatedFailure(FailurePolicy.LOCK)
                    .build();
            return configRepo.save(policy);
        });
    }

    private ConfigPolicyResponse toResponse(ConfigPolicy p) {
        return ConfigPolicyResponse.builder()
                .id(p.getId())
                .workflowType(p.getWorkflowType())
                .livenessEnabled(p.getLivenessEnabled())
                .passiveThreshold(p.getPassiveThreshold())
                .activeLivenessEnabled(p.getActiveLivenessEnabled())
                .minChallengeCount(p.getMinChallengeCount())
                .challengeTypes(p.getChallengeTypes())
                .challengeTimeoutMs(p.getChallengeTimeoutMs())
                .maxRetryCount(p.getMaxRetryCount())
                .onRepeatedFailure(p.getOnRepeatedFailure())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
