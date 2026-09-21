package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Draft chronology vectors for mutually exclusive retention/exclusion precedence. */
class LiquidationChronologicalInventoryBoundaryMatrixV1Test {
    private static final Instant Q1 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant DECISION_START = Instant.parse("2022-11-11T00:00:00Z");
    private static final Instant DECISION_END = Instant.parse("2026-07-15T00:00:00Z");
    private static final Instant EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void outerInventoryDistinguishesWindowPurgeEmbargoOutcomeAndQuarterEdges() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        List<LiquidationChronologicalPolicyV1.Observation> observations = new ArrayList<>();
        observations.add(done("predecision", DECISION_START.minusSeconds(60), DECISION_START.minusSeconds(30),
                DECISION_START.minusSeconds(20)));
        observations.add(done("safe-training", Q1.minus(Duration.ofDays(90)), Q1.minus(Duration.ofDays(89)),
                Q1.minus(Duration.ofDays(88))));
        observations.add(done("purge-edge", Q1.minus(Duration.ofDays(67)), Q1.minus(Duration.ofDays(66)),
                Q1.minus(Duration.ofDays(65))));
        observations.add(LiquidationChronologicalPolicyV1.Observation.unresolved("unresolved-training",
                Q1.minus(Duration.ofDays(90)).minusSeconds(1)));
        observations.add(LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade("embargo-first", Q1,
                Q1.plus(Duration.ofDays(1))));
        observations.add(done("embargo-last", Q1.plus(Duration.ofDays(6)), Q1.plus(Duration.ofDays(6)).plusSeconds(1),
                Q1.plus(Duration.ofDays(6)).plusSeconds(2)));
        observations.add(LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade("oos-zero", Q1.plus(Duration.ofDays(7)),
                Q1.plus(Duration.ofDays(8))));
        Instant quarterEnd = Instant.parse("2024-04-01T00:00:00Z");
        observations.add(done("oos-at-quarter-end-minus-one", quarterEnd.minusSeconds(1),
                quarterEnd.minusSeconds(1).plusSeconds(1), quarterEnd.plusSeconds(1)));
        observations.add(LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade("at-quarter-end", quarterEnd,
                quarterEnd.plusSeconds(1)));
        observations.add(done("after-decision-end", DECISION_END, DECISION_END.plusSeconds(1), DECISION_END.plusSeconds(2)));
        Instant finalFoldDecision = Instant.parse("2025-10-01T00:00:00Z").plus(Duration.ofDays(8));
        observations.add(LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade("at-execution-cutoff",
                finalFoldDecision, EXECUTION_END));

        LiquidationChronologicalPolicyV1.Inventory inventory = LiquidationChronologicalPolicyV1
                .buildInventory(profile, observations);
        LiquidationChronologicalPolicyV1.Fold fold = inventory.folds().get(0);
        assertTrue(fold.trainingIds().contains("safe-training"));
        assertEquals("OUTSIDE_FROZEN_DECISION_WINDOW", fold.excludedIds().get("predecision"));
        assertEquals("PURGED_67D", fold.excludedIds().get("purge-edge"));
        assertEquals("OUTCOME_UNRESOLVED", fold.excludedIds().get("unresolved-training"));
        assertEquals("EMBARGOED_7D", fold.excludedIds().get("embargo-first"));
        assertEquals("EMBARGOED_7D", fold.excludedIds().get("embargo-last"));
        assertTrue(fold.testIds().contains("oos-zero"));
        assertTrue(fold.testIds().contains("oos-at-quarter-end-minus-one"));
        assertEquals("AFTER_OUTER_TEST_WINDOW", fold.excludedIds().get("at-quarter-end"));
        assertEquals("OUTSIDE_FROZEN_DECISION_WINDOW", fold.excludedIds().get("after-decision-end"));
        assertEquals("OUTCOME_AT_OR_AFTER_FROZEN_EXECUTION_CUTOFF",
                inventory.folds().get(7).excludedIds().get("at-execution-cutoff"));
    }

    @Test
    void innerSplitUsesOverlapFirstWhenOldPositionExitCrossesValidationAndRecordsLaterTrainingRows() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        List<LiquidationChronologicalPolicyV1.Observation> observations = new ArrayList<>();
        Instant nov = Instant.parse("2022-11-15T00:00:00Z");
        Instant jan = Instant.parse("2023-01-15T00:00:00Z");
        Instant feb = Instant.parse("2023-02-15T00:00:00Z");
        Instant march = Instant.parse("2023-03-01T00:00:00Z");
        Instant june = Instant.parse("2023-06-01T00:00:00Z");
        Instant september = Instant.parse("2023-09-15T00:00:00Z");
        observations.add(done("old-crossing-exit", nov, nov.plusSeconds(60), march.plus(Duration.ofDays(2))));
        observations.add(done("ordinary-old-fit", jan, jan.plusSeconds(60), jan.plus(Duration.ofDays(2))));
        observations.add(done("market-time-feb", feb, feb.plusSeconds(60), feb.plus(Duration.ofDays(2))));
        observations.add(done("validation-march", march, march.plusSeconds(60), march.plus(Duration.ofDays(4))));
        observations.add(done("market-time-june", june, june.plusSeconds(60), june.plus(Duration.ofDays(2))));
        observations.add(done("later-training-row", september, september.plusSeconds(60), september.plus(Duration.ofDays(2))));

        LiquidationChronologicalPolicyV1.Fold outer = LiquidationChronologicalPolicyV1
                .buildInventory(profile, observations).folds().get(0);
        LiquidationChronologicalPolicyV1.InnerFold inner = outer.innerFolds().get(0);
        assertEquals("ACTUAL_OUTCOME_INTERVAL_OVERLAP", inner.excludedIds().get("old-crossing-exit"));
        assertTrue(inner.actualOverlapIds().contains("old-crossing-exit"));
        assertEquals("AFTER_INNER_VALIDATION_WINDOW", inner.excludedIds().get("later-training-row"));
        assertTrue(inner.validationIds().contains("validation-march"));
        assertTrue(inner.fitIds().isEmpty() || inner.fitIds().stream().allMatch(id -> {
            Instant decision = observations.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow().decisionTime();
            return decision.isBefore(inner.fitDecisionCutoffExclusive());
        }));
        assertFalse(inner.validationIds().contains("ordinary-old-fit"));
    }

    @Test
    void inventoriesRemainInputOrderInvariantAndRejectDistinctMalformedPolicyShapes() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        List<LiquidationChronologicalPolicyV1.Observation> forward = List.of(
                done("one", DECISION_START, DECISION_START.plusSeconds(1), DECISION_START.plusSeconds(2)),
                LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade("two", Q1.plus(Duration.ofDays(7)),
                        Q1.plus(Duration.ofDays(8))));
        List<LiquidationChronologicalPolicyV1.Observation> reversed = List.of(forward.get(1), forward.get(0));
        ObjectNode first = LiquidationChronologicalPolicyV1.buildInventory(profile, forward).toJson();
        ObjectNode second = LiquidationChronologicalPolicyV1.buildInventory(profile, reversed).toJson();
        assertEquals(JsonHashes.canonicalSha256(first), JsonHashes.canonicalSha256(second));

        ObjectNode wrongSchema = first.deepCopy().put("schema", "folds/0");
        wrongSchema.put("content_sha256", JsonHashes.ownHash(wrongSchema));
        assertThrows(IllegalArgumentException.class, () -> LiquidationChronologicalPolicyV1
                .validateInventory(profile, forward, wrongSchema));
        ObjectNode wrongVersion = first.deepCopy().put("version", 2);
        wrongVersion.put("content_sha256", JsonHashes.ownHash(wrongVersion));
        assertThrows(IllegalArgumentException.class, () -> LiquidationChronologicalPolicyV1
                .validateInventory(profile, forward, wrongVersion));
        assertTrue(LiquidationChronologicalPolicyV1.validateInventory(profile, reversed, second));
    }

    private static LiquidationChronologicalPolicyV1.Observation done(String id, Instant decision,
            Instant fill, Instant exit) {
        return LiquidationChronologicalPolicyV1.Observation.completed(id, decision, fill, exit);
    }
}
