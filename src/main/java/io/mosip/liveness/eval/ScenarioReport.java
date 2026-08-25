package io.mosip.liveness.eval;

/** Aggregated results of one attack-scenario evaluation run. */
public record ScenarioReport(
        double apcer,
        double bpcer,
        double acer,
        double livenessFar,
        double livenessFrr,
        double escalationRate,
        double avgLatencyMs,
        long totalPresentations) {

    @Override
    public String toString() {
        return String.format(
                "ScenarioReport[presentations=%d  APCER=%.4f  BPCER=%.4f  ACER=%.4f  pipelineFAR=%.4f  "
                        + "pipelineFRR=%.4f  escalationRate=%.4f  avgLatencyMs=%.1f]",
                totalPresentations, apcer, bpcer, acer, livenessFar, livenessFrr, escalationRate, avgLatencyMs);
    }
}
