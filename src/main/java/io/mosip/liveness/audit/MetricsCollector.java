package io.mosip.liveness.audit;

/**
 * Thread-safe collector for anonymized operational metrics:
 * avg processing time, challenge completion time, retry rate, failure rate,
 * passive-to-active escalation rate, PAD block count.
 */
public final class MetricsCollector {

    private long framesProcessed;
    private double totalProcessingMs;
    private long challengesCompleted;
    private double totalChallengeMs;
    private long retries;
    private long sessionsEnded;
    private long sessionsFailed;
    private long escalations;
    private long padBlocks;

    public synchronized void recordProcessingTime(double ms) {
        framesProcessed++;
        totalProcessingMs += ms;
    }

    public synchronized void recordChallengeCompletion(double ms) {
        challengesCompleted++;
        totalChallengeMs += ms;
    }

    public synchronized void recordRetry() { retries++; }

    public synchronized void recordEscalation() { escalations++; }

    public synchronized void recordPadBlock() { padBlocks++; }

    public synchronized void recordSessionEnd(boolean failed) {
        sessionsEnded++;
        if (failed) sessionsFailed++;
    }

    public synchronized MetricsSnapshot snapshot() {
        long s = Math.max(1, sessionsEnded);
        return new MetricsSnapshot(
                sessionsEnded,
                framesProcessed,
                framesProcessed == 0 ? 0 : totalProcessingMs / framesProcessed,
                challengesCompleted == 0 ? 0 : totalChallengeMs / challengesCompleted,
                (double) retries / s,
                (double) sessionsFailed / s,
                (double) escalations / s,
                padBlocks);
    }
}
