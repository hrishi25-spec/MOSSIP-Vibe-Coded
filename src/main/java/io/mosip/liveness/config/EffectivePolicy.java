package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RepeatedFailureAction;

import java.util.Set;

/** Fully-resolved policy for one session (base config + workflow override merged). */
public record EffectivePolicy(
        boolean livenessEnabled,
        boolean activeLivenessEnabled,
        double passiveThreshold,
        double minFaceQuality,
        int passiveMinFrames,
        int passiveWindowFrames,
        int minChallengeCount,
        int maxRetries,
        long challengeTimeoutMs,
        Set<ChallengeType> allowedChallenges,
        RepeatedFailureAction onRepeatedFailure) {

    public String summary() {
        return "threshold=" + passiveThreshold
                + " active=" + activeLivenessEnabled
                + " minChallenges=" + minChallengeCount
                + " timeout=" + challengeTimeoutMs + "ms"
                + " retries=" + maxRetries
                + " onFailure=" + onRepeatedFailure;
    }
}
