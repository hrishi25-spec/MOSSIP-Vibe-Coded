package io.mosip.liveness.config;

import java.util.Set;

/**
 * Validates a fully-resolved {@link EffectivePolicy} before it is used — at
 * session creation (where the policy is frozen as a snapshot) and when a policy
 * is updated through {@code PUT /api/v1/config/{workflowType}}.
 *
 * <p>These are the cross-field invariants that per-field bean validation cannot
 * express. A single source of truth here prevents a config row that is
 * individually valid but internally contradictory, e.g. a supervisor workflow
 * demanding two challenges from a one-type pool, or a zero passive threshold
 * that would pass every frame.</p>
 *
 * <p>Violations throw {@link IllegalArgumentException}, which the global handler
 * surfaces as HTTP 400 with a precise message.</p>
 */
public final class EffectivePolicyValidator {

    private EffectivePolicyValidator() {
    }

    public static void validate(EffectivePolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("effective policy must not be null");
        }

        // (0,1] — not [0,1]: a threshold of exactly 0 would accept every frame,
        // and exactly 1 is unreachable in practice.
        require(policy.passiveThreshold() > 0.0 && policy.passiveThreshold() <= 1.0,
                "passiveThreshold must be greater than 0 and at most 1.0, got " + policy.passiveThreshold());

        require(policy.maxRetries() >= 0,
                "maxRetries must be >= 0, got " + policy.maxRetries());

        require(policy.challengeTimeoutMs() >= LivenessConfig.MIN_CHALLENGE_WINDOW_MS,
                "challengeTimeoutMs must be >= " + LivenessConfig.MIN_CHALLENGE_WINDOW_MS
                        + "ms, got " + policy.challengeTimeoutMs());

        if (policy.activeLivenessEnabled()) {
            Set<?> challenges = policy.allowedChallenges();
            require(challenges != null && !challenges.isEmpty(),
                    "at least one challenge type is required when active liveness is enabled");
            require(policy.minChallengeCount() >= 1,
                    "minChallengeCount must be >= 1 when active liveness is enabled, got "
                            + policy.minChallengeCount());
            require(policy.minChallengeCount() <= challenges.size(),
                    "minChallengeCount (" + policy.minChallengeCount()
                            + ") must not exceed the number of configured challenge types ("
                            + challenges.size() + ")");
        } else {
            require(policy.minChallengeCount() >= 0,
                    "minChallengeCount must be >= 0, got " + policy.minChallengeCount());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
