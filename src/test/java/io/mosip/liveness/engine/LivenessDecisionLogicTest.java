package io.mosip.liveness.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessDecisionLogicTest {

    @Test
    void medianOddCountReturnsMiddleValue() {
        assertEquals(0.5, LivenessDecisionLogic.median(List.of(0.1, 0.5, 0.9)), 1e-9);
    }

    @Test
    void medianEvenCountAveragesMiddleTwo() {
        // sorted: [0.1, 0.2, 0.4, 0.5] -> median = (0.2 + 0.4) / 2 = 0.3
        assertEquals(0.3, LivenessDecisionLogic.median(List.of(0.4, 0.5, 0.1, 0.2)), 1e-9);
    }

    @Test
    void medianIsOrderIndependent() {
        double a = LivenessDecisionLogic.median(List.of(0.9, 0.2, 0.6));
        double b = LivenessDecisionLogic.median(List.of(0.2, 0.6, 0.9));
        assertEquals(a, b, 1e-12);
    }

    @Test
    void medianRejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> LivenessDecisionLogic.median(List.of()));
    }

    @ParameterizedTest
    @CsvSource({
            "0.95, 0.80, PROCEED_PASSIVE",
            "0.80, 0.80, PROCEED_PASSIVE",   // >= threshold passes
            "0.79, 0.80, ESCALATE_ACTIVE"
    })
    void decidePassiveRespectsThreshold(double median, double threshold, String expected) {
        var outcome = LivenessDecisionLogic.decidePassive(median, threshold, false);
        assertEquals(expected, outcome.name());
    }

    @Test
    void padFlagAlwaysBlocksRegardlessOfScore() {
        assertEquals(LivenessDecisionLogic.PassiveOutcome.PAD_BLOCK,
                LivenessDecisionLogic.decidePassive(0.99, 0.5, true));
        assertTrue(LivenessDecisionLogic.decidePassive(0.99, 0.5, true)
                == LivenessDecisionLogic.PassiveOutcome.PAD_BLOCK);
    }
}
