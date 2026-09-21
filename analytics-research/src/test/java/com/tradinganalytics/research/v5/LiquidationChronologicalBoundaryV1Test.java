package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises lifecycle-state and synchronized-clock boundaries without outcome-driven selection. */
class LiquidationChronologicalBoundaryV1Test {
    private static final ObjectNode PROFILE = LiquidationDailyStressProfileV1.frozenContract();
    private static final Instant BASE = Instant.parse("2023-01-01T00:00:00Z");

    @Test
    void compatibilityObservationConstructorClassifiesEverySupportedStateAndRejectsInvalidIntervals() {
        LiquidationChronologicalPolicyV1.Observation unresolved =
                new LiquidationChronologicalPolicyV1.Observation("u", BASE, null, null);
        assertEquals(LiquidationChronologicalPolicyV1.OutcomeState.UNRESOLVED_NO_FILL, unresolved.outcomeState());

        Instant fill = BASE.plusSeconds(60);
        LiquidationChronologicalPolicyV1.Observation open =
                new LiquidationChronologicalPolicyV1.Observation("open", BASE, fill, null);
        assertEquals(LiquidationChronologicalPolicyV1.OutcomeState.OPEN_TRADE, open.outcomeState());
        LiquidationChronologicalPolicyV1.Observation closedZeroDuration =
                new LiquidationChronologicalPolicyV1.Observation("closed", BASE, fill, fill);
        assertEquals(LiquidationChronologicalPolicyV1.OutcomeState.CLOSED_TRADE, closedZeroDuration.outcomeState());
        assertThrows(IllegalArgumentException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("exit-only", BASE, null, fill));

        List<Runnable> invalid = List.of(
                () -> new LiquidationChronologicalPolicyV1.Observation(" ", BASE, null, null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-unresolved", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.UNRESOLVED_NO_FILL, fill, null, null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-open-missing-fill", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.OPEN_TRADE, null, null, null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-open-before-decision", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.OPEN_TRADE, null, BASE.minusSeconds(1), null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-zero-no-resolution", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.RESOLVED_NO_TRADE, null, null, null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-zero-before-decision", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.RESOLVED_NO_TRADE, BASE.minusSeconds(1), null, null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-zero-with-fill", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.RESOLVED_NO_TRADE, fill, fill, null),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-closed-before-decision", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.CLOSED_TRADE, fill, BASE.minusSeconds(1), fill),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-closed-reversed", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.CLOSED_TRADE, fill, fill, fill.minusSeconds(1)),
                () -> new LiquidationChronologicalPolicyV1.Observation("bad-closed-label-early", BASE,
                        LiquidationChronologicalPolicyV1.OutcomeState.CLOSED_TRADE, fill.minusSeconds(1), fill, fill));
        for (Runnable action : invalid) assertThrows(IllegalArgumentException.class, action::run);
        assertThrows(NullPointerException.class,
                () -> new LiquidationChronologicalPolicyV1.Observation("null-decision", null, null, null),
                "a null decision timestamp is rejected as a non-null record precondition");
    }

    @Test
    void undersizedChronologicalTrainingSetIsReportedInsteadOfRelaxingTheSplit() {
        Instant second = Instant.parse("2023-10-01T00:00:00Z");
        Instant q1 = Instant.parse("2024-01-01T00:00:00Z");
        List<LiquidationChronologicalPolicyV1.Observation> rows = List.of(
                LiquidationChronologicalPolicyV1.Observation.completed("early", BASE,
                        BASE.plusSeconds(60), BASE.plus(Duration.ofDays(2))),
                LiquidationChronologicalPolicyV1.Observation.completed("late-training", second,
                        second.plusSeconds(60), second.plus(Duration.ofDays(2))),
                LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade("oos-zero", q1.plus(Duration.ofDays(7)),
                        q1.plus(Duration.ofDays(8))));

        LiquidationChronologicalPolicyV1.Inventory inventory =
                LiquidationChronologicalPolicyV1.buildInventory(PROFILE, rows);
        assertEquals(8, inventory.folds().size());
        assertEquals("INSUFFICIENT_CHRONOLOGICAL_MARKET_TIMES", inventory.folds().get(0).innerFolds().get(0).status());
        assertEquals("INSUFFICIENT_CHRONOLOGICAL_MARKET_TIMES", inventory.folds().get(0).innerFolds().get(1).status());

        ArrayList<LiquidationChronologicalPolicyV1.Observation> duplicateIds = new ArrayList<>(rows);
        duplicateIds.add(LiquidationChronologicalPolicyV1.Observation.unresolved("early", second));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.buildInventory(PROFILE, duplicateIds));
        ArrayList<LiquidationChronologicalPolicyV1.Observation> nullRow = new ArrayList<>(rows);
        nullRow.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.buildInventory(PROFILE, nullRow));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.buildInventory(PROFILE, null));
    }

    @Test
    void synchronizedBlocksRetainEmptyMarketTimesAndRejectInvalidJointInventories() {
        Instant start = Instant.parse("2022-01-01T00:00:00Z");
        Instant end = start.plus(Duration.ofDays(67));
        LiquidationChronologicalPolicyV1.MarketTimeBlock empty =
                new LiquidationChronologicalPolicyV1.MarketTimeBlock("quiet-market-time", start, end, List.of());
        LiquidationChronologicalPolicyV1.SynchronizedBlockSample sample =
                LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(empty), 1, 7L);
        assertEquals(List.of("quiet-market-time"), sample.draws().get(0).sampledBlockIds(),
                "an all-asset zero-activity market time is still one synchronized resampling block");

        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(), 1, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(empty), 0, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(empty), 100_001, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, Arrays.asList((LiquidationChronologicalPolicyV1.MarketTimeBlock) null), 1, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(empty,
                        new LiquidationChronologicalPolicyV1.MarketTimeBlock("overlap", start.plus(Duration.ofDays(66)), end.plus(Duration.ofDays(66)), List.of())), 1, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(empty,
                        new LiquidationChronologicalPolicyV1.MarketTimeBlock("quiet-market-time", end, end.plus(Duration.ofDays(67)), List.of())), 1, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(empty,
                        new LiquidationChronologicalPolicyV1.MarketTimeBlock("second", end, end.plus(Duration.ofDays(67)), List.of("same-observation")),
                        new LiquidationChronologicalPolicyV1.MarketTimeBlock("third", end.plus(Duration.ofDays(67)), end.plus(Duration.ofDays(134)), List.of("same-observation"))), 1, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(PROFILE, List.of(
                        new LiquidationChronologicalPolicyV1.MarketTimeBlock("short", start, start.plus(Duration.ofDays(66)), List.of())), 1, 7L));
    }
}
