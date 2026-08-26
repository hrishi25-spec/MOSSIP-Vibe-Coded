package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RepeatedFailureAction;

import java.util.Set;

/**
 * Per-workflow policy override. Every field is optional (null = inherit from
 * the base {@link LivenessConfig}). Fluent setters return {@code this}.
 */
public final class LivenessPolicy {

    private Boolean livenessEnabled;
    private Boolean activeLivenessEnabled;
    private Double passiveThreshold;
    private Double minFaceQuality;
    private Integer passiveMinFrames;
    private Integer passiveWindowFrames;
    private Integer minChallengeCount;
    private Integer maxRetries;
    private Long challengeTimeoutMs;
    private Set<ChallengeType> allowedChallenges;
    private RepeatedFailureAction onRepeatedFailure;

    public LivenessPolicy withLivenessEnabled(boolean v) { this.livenessEnabled = v; return this; }
    public LivenessPolicy withActiveLivenessEnabled(boolean v) { this.activeLivenessEnabled = v; return this; }
    public LivenessPolicy withPassiveThreshold(double v) { this.passiveThreshold = v; return this; }
    public LivenessPolicy withMinFaceQuality(double v) { this.minFaceQuality = v; return this; }
    public LivenessPolicy withPassiveMinFrames(int v) { this.passiveMinFrames = v; return this; }
    public LivenessPolicy withPassiveWindowFrames(int v) { this.passiveWindowFrames = v; return this; }
    public LivenessPolicy withMinChallengeCount(int v) { this.minChallengeCount = v; return this; }
    public LivenessPolicy withMaxRetries(int v) { this.maxRetries = v; return this; }
    public LivenessPolicy withChallengeTimeoutMs(long v) { this.challengeTimeoutMs = v; return this; }
    public LivenessPolicy withAllowedChallenges(Set<ChallengeType> v) { this.allowedChallenges = Set.copyOf(v); return this; }
    public LivenessPolicy withOnRepeatedFailure(RepeatedFailureAction v) { this.onRepeatedFailure = v; return this; }

    public Boolean livenessEnabled() { return livenessEnabled; }
    public Boolean activeLivenessEnabled() { return activeLivenessEnabled; }
    public Double passiveThreshold() { return passiveThreshold; }
    public Double minFaceQuality() { return minFaceQuality; }
    public Integer passiveMinFrames() { return passiveMinFrames; }
    public Integer passiveWindowFrames() { return passiveWindowFrames; }
    public Integer minChallengeCount() { return minChallengeCount; }
    public Integer maxRetries() { return maxRetries; }
    public Long challengeTimeoutMs() { return challengeTimeoutMs; }
    public Set<ChallengeType> allowedChallenges() { return allowedChallenges; }
    public RepeatedFailureAction onRepeatedFailure() { return onRepeatedFailure; }

    /** @return the first non-null override value, else the base default. */
    static <T> T coalesce(T override, T base) {
        return override != null ? override : base;
    }
}
