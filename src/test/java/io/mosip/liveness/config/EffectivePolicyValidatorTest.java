package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RepeatedFailureAction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-field invariants that per-field bean validation cannot express — the
 * cases the session-start snapshot must reject rather than freeze silently.
 */
class EffectivePolicyValidatorTest {

    private static EffectivePolicy policy(boolean activeLiveness, double threshold,
                                          int minChallengeCount, long timeoutMs,
                                          java.util.Set<ChallengeType> challenges) {
        return new EffectivePolicy(
                true, activeLiveness, threshold, 0.50, 5, 7,
                minChallengeCount, 3, timeoutMs, challenges,
                RepeatedFailureAction.LOCK_OUT, -1.0, 30_000L, 1, 10, 0.6, 0.4);
    }

    @Test
    void acceptsAValidPolicy() {
        assertDoesNotThrow(() -> EffectivePolicyValidator.validate(
                policy(true, 0.80, 1, 15_000L, EnumSet.of(ChallengeType.BLINK))));
    }

    @Test
    void rejectsAZeroThresholdThatWouldPassEveryFrame() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EffectivePolicyValidator.validate(
                        policy(true, 0.0, 1, 15_000L, EnumSet.of(ChallengeType.BLINK))));
        assertTrue(ex.getMessage().contains("passiveThreshold"));
    }

    @Test
    void rejectsMinChallengeCountLargerThanTheChallengePool() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EffectivePolicyValidator.validate(
                        policy(true, 0.85, 2, 15_000L, EnumSet.of(ChallengeType.BLINK))));
        assertTrue(ex.getMessage().contains("minChallengeCount"));
    }

    @Test
    void rejectsAChallengeWindowBelowTheEngineFloor() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EffectivePolicyValidator.validate(
                        policy(true, 0.80, 1, 8_000L, EnumSet.of(ChallengeType.BLINK))));
        assertTrue(ex.getMessage().contains("challengeTimeoutMs"));
    }

    @Test
    void rejectsAnEmptyChallengePoolWhenActiveLivenessIsEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> EffectivePolicyValidator.validate(
                        policy(true, 0.80, 0, 15_000L, EnumSet.noneOf(ChallengeType.class))));
    }

    @Test
    void acceptsLivenessDisabledWithNoChallenges() {
        assertDoesNotThrow(() -> EffectivePolicyValidator.validate(
                policy(false, 0.80, 0, 15_000L, EnumSet.noneOf(ChallengeType.class))));
    }
}
