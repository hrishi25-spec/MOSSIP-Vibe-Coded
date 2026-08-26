package io.mosip.liveness.audit;

/** Anonymized operational metrics snapshot (no PII, no raw model data). */
public record MetricsSnapshot(
        long sessionsEnded,
        long framesProcessed,
        double avgProcessingTimeMs,
        double avgChallengeCompletionMs,
        double retryRate,
        double failureRate,
        double escalationRate,
        long padBlocks) {
}
