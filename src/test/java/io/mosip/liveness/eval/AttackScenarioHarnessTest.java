package io.mosip.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the full pipeline against scripted bona fide and attack presentations
 * and reports ISO/IEC 30107-3 metrics (APCER / BPCER / ACER) plus the
 * passive-to-active escalation rate.
 *
 * With MockLivenessBackend these numbers characterize the decision logic, not
 * a trained model; swap in a recorded-capture backend to evaluate a real one.
 */
class AttackScenarioHarnessTest {

    private static final int PRESENTATIONS_PER_LABEL = 60;
    private static final long SEED = 20260825L;

    @Test
    void reportMetricsWithinAcceptanceBounds() {
        AttackScenarioHarness harness = new AttackScenarioHarness(
                io.mosip.liveness.config.LivenessConfig.builder().build(), SEED);

        long t0 = System.nanoTime();
        List<AttackScenarioHarness.PresentationResult> results = harness.run(PRESENTATIONS_PER_LABEL);
        double wallMs = (System.nanoTime() - t0) / 1_000_000.0;

        ScenarioReport report = AttackScenarioHarness.report(results);

        System.out.println("==== ISO/IEC 30107-3 style evaluation (mock backend) ====");
        System.out.println(report);
        System.out.printf("wall time: %.0f ms for %d presentations (%.2f ms/presentation)%n",
                wallMs, results.size(), wallMs / results.size());

        Map<PresentationLabel, Integer> byLabel = new EnumMap<>(PresentationLabel.class);
        results.forEach(r -> byLabel.merge(r.label(), 1, Integer::sum));
        System.out.println("presentations per label: " + byLabel);

        // Bounds reflect simulated classifier error rates (3% attack miss, 1% bona fide FP)
        // plus statistical slack for 60 presentations per label.
        assertTrue(report.apcer() <= 0.08, "APCER too high: " + report.apcer());
        assertTrue(report.bpcer() <= 0.08, "BPCER too high: " + report.bpcer());
        assertTrue(report.acer() <= 0.05, "ACER too high: " + report.acer());

        // The escalation path must be exercised (30% of genuine users are low-quality).
        // With 60 bona fide out of 240 total presentations, max bona-fide-only
        // escalation rate is 30% × 60/240 ≈ 7.5%; attacks that evade PAD also
        // escalate if their liveness score is low.
        assertTrue(report.escalationRate() >= 0.05,
                "expected meaningful passive->active escalation rate, got " + report.escalationRate());
    }
}
