package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationChronologicalPolicyV1Test {
    private static final Instant DECISION_START = Instant.parse("2022-11-11T00:00:00Z");
    private static final Instant EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void buildsExactlyEightQuarterlyOuterFoldsAndContainedTrainingOnlyInnerSplits() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        List<LiquidationChronologicalPolicyV1.Observation> observations = trainingHistory();
        for (int index = 0; index < 8; index++) {
            Instant start = quarterStart(index);
            Instant firstEligible = start.plus(Duration.ofDays(7));
            observations = append(observations, completed("test-" + index, firstEligible,
                    firstEligible.plus(Duration.ofMinutes(1)), firstEligible.plus(Duration.ofDays(2))));
        }
        observations = append(observations, LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade(
                "resolved-zero", Instant.parse("2023-07-01T00:00:00Z"), Instant.parse("2023-07-02T00:00:00Z")));
        Instant q1 = quarterStart(0);
        observations = append(observations, LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade(
                "oos-zero", q1.plus(Duration.ofDays(15)), q1.plus(Duration.ofDays(16))));
        observations = append(observations, LiquidationChronologicalPolicyV1.Observation.openTrade(
                "oos-open", q1.plus(Duration.ofDays(20)), q1.plus(Duration.ofDays(20)).plus(Duration.ofMinutes(1))));
        observations = append(observations, LiquidationChronologicalPolicyV1.Observation.unresolved(
                "oos-unresolved", q1.plus(Duration.ofDays(21))));
        observations = append(observations, completed("outcome-at-execution-cutoff",
                Instant.parse("2025-12-20T00:00:00Z"), Instant.parse("2025-12-20T00:01:00Z"), EXECUTION_END));

        LiquidationChronologicalPolicyV1.Inventory inventory =
                LiquidationChronologicalPolicyV1.buildInventory(profile, observations);
        assertEquals(8, inventory.folds().size());
        assertEquals("2024-01-01T00:00:00Z", inventory.folds().get(0).rawTestStart().toString());
        assertEquals("2024-01-08T00:00:00Z", inventory.folds().get(0).testStart().toString());
        assertEquals("2024-04-01T00:00:00Z", inventory.folds().get(0).testEndExclusive().toString());
        assertEquals("2025-10-01T00:00:00Z", inventory.folds().get(7).rawTestStart().toString());
        assertEquals("2026-01-01T00:00:00Z", inventory.folds().get(7).testEndExclusive().toString());

        for (LiquidationChronologicalPolicyV1.Fold outer : inventory.folds()) {
            assertEquals(2, outer.innerFolds().size());
            assertFalse(outer.trainingIds().isEmpty());
            int foldNumber = Integer.parseInt(outer.foldId().substring("outer-".length()));
            assertTrue(outer.testIds().contains("test-" + (foldNumber - 1)));
            if (foldNumber == 1) assertTrue(outer.trainingIds().contains("resolved-zero"));
            if (foldNumber == 1) {
                assertTrue(outer.testIds().contains("oos-zero"));
                assertEquals("OUTCOME_UNRESOLVED", outer.excludedIds().get("oos-open"));
                assertEquals("OUTCOME_UNRESOLVED", outer.excludedIds().get("oos-unresolved"));
            }
            if (foldNumber == 8) assertEquals("OUTCOME_AT_OR_AFTER_FROZEN_EXECUTION_CUTOFF",
                    outer.excludedIds().get("outcome-at-execution-cutoff"));
            for (LiquidationChronologicalPolicyV1.InnerFold inner : outer.innerFolds()) {
                assertEquals("CHRONOLOGICAL_SPLIT", inner.status());
                assertTrue(inner.validationStart().isBefore(inner.validationEndExclusive()));
                assertFalse(inner.validationEndExclusive().isAfter(outer.trainingDecisionCutoffExclusive()));
                assertTrue(outer.trainingIds().containsAll(inner.fitIds()));
                assertTrue(outer.trainingIds().containsAll(inner.validationIds()));
                for (String id : inner.fitIds()) {
                    LiquidationChronologicalPolicyV1.Observation row = observation(observations, id);
                    assertTrue(row.decisionTime().isBefore(inner.fitDecisionCutoffExclusive()));
                    assertFalse(row.outcomeAvailableTime().isAfter(inner.fitOutcomeAvailableBy()));
                    assertFalse(row.outcomeAvailableTime().isAfter(outer.trainingOutcomeAvailableBy()));
                }
            }
        }
        assertTrue(LiquidationChronologicalPolicyV1.validateInventory(profile, observations, inventory.toJson()));
    }

    @Test
    void excludesActualExitOverlapEvenWhenTheTrainingDecisionIsOlderThanThePurge() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant q1 = quarterStart(0);
        List<LiquidationChronologicalPolicyV1.Observation> observations = new ArrayList<>(trainingHistory());
        observations.add(completed("safe-old", Instant.parse("2023-01-01T00:00:00Z"),
                Instant.parse("2023-01-01T00:01:00Z"), Instant.parse("2023-01-03T00:00:00Z")));
        observations.add(completed("crosses-oos-fill", Instant.parse("2023-06-01T00:00:00Z"),
                Instant.parse("2023-06-01T00:01:00Z"), q1.plus(Duration.ofDays(9))));
        Instant oosDecision = q1.plus(Duration.ofDays(7));
        observations.add(completed("oos-position", oosDecision, oosDecision.plus(Duration.ofMinutes(1)),
                oosDecision.plus(Duration.ofDays(2))));
        Instant sharedExecutionInstant = oosDecision.plus(Duration.ofMinutes(1));
        observations.add(completed("zero-duration-shared-instant", Instant.parse("2023-08-01T00:00:00Z"),
                sharedExecutionInstant, sharedExecutionInstant));
        observations.add(completed("label-after-test-start", Instant.parse("2023-06-02T00:00:00Z"),
                Instant.parse("2023-06-02T00:01:00Z"), q1.plus(Duration.ofDays(6))));

        LiquidationChronologicalPolicyV1.Fold first =
                LiquidationChronologicalPolicyV1.buildInventory(profile, observations).folds().get(0);
        assertTrue(first.trainingIds().contains("safe-old"));
        assertEquals("ACTUAL_OUTCOME_INTERVAL_OVERLAP", first.excludedIds().get("crosses-oos-fill"));
        assertEquals("ACTUAL_OUTCOME_INTERVAL_OVERLAP", first.excludedIds().get("zero-duration-shared-instant"));
        assertEquals("TRAIN_LABEL_NOT_AVAILABLE_BY_TEST_BOUNDARY", first.excludedIds().get("label-after-test-start"));
        assertTrue(first.actualOverlapIds().contains("crosses-oos-fill"));
        assertTrue(Duration.between(observation(observations, "crosses-oos-fill").decisionTime(), q1).toDays() > 67);
    }

    @Test
    void keepsTheFrozenPurgeAndSevenDayEmbargoEdgesExplicit() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant q1 = quarterStart(0);
        List<LiquidationChronologicalPolicyV1.Observation> observations = new ArrayList<>(trainingHistory());
        observations.add(completed("before-purge", q1.minus(Duration.ofDays(68)),
                q1.minus(Duration.ofDays(68)).plus(Duration.ofMinutes(1)), q1.minus(Duration.ofDays(66))));
        observations.add(completed("purge-edge", q1.minus(Duration.ofDays(67)),
                q1.minus(Duration.ofDays(67)).plus(Duration.ofMinutes(1)), q1.minus(Duration.ofDays(65))));
        observations.add(completed("embargo-start", q1,
                q1.plus(Duration.ofMinutes(1)), q1.plus(Duration.ofDays(1))));
        observations.add(completed("inside-embargo", q1.plus(Duration.ofDays(6)),
                q1.plus(Duration.ofDays(6)).plus(Duration.ofMinutes(1)), q1.plus(Duration.ofDays(7))));
        observations.add(completed("embargo-end", q1.plus(Duration.ofDays(7)),
                q1.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(1)), q1.plus(Duration.ofDays(8))));

        LiquidationChronologicalPolicyV1.Fold first =
                LiquidationChronologicalPolicyV1.buildInventory(profile, observations).folds().get(0);
        assertTrue(first.trainingIds().contains("before-purge"));
        assertEquals("EMBARGOED_7D", first.excludedIds().get("embargo-start"));
        assertEquals("EMBARGOED_7D", first.excludedIds().get("inside-embargo"));
        assertTrue(first.testIds().contains("embargo-end"));
        assertEquals("PURGED_67D", first.excludedIds().get("purge-edge"));
    }

    @Test
    void rejectsTamperedFrozenPolicyAndRehashedButForgedInventory() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode tamperedProfile = profile.deepCopy();
        ((ObjectNode) tamperedProfile.path("fold_contract")).put("outer_and_inner_purge_days", 30);
        tamperedProfile.put("content_sha256", JsonHashes.ownHash(tamperedProfile));
        assertThrows(IllegalArgumentException.class, () -> LiquidationChronologicalPolicyV1
                .buildInventory(tamperedProfile, trainingHistory()));

        List<LiquidationChronologicalPolicyV1.Observation> observations = trainingHistory();
        ObjectNode forged = LiquidationChronologicalPolicyV1.buildInventory(profile, observations).toJson();
        ((ObjectNode) forged.withArray("folds").get(0)).withArray("training_ids").add("future-or-forged-id");
        forged.put("content_sha256", JsonHashes.ownHash(forged));
        assertThrows(IllegalArgumentException.class, () -> LiquidationChronologicalPolicyV1
                .validateInventory(profile, observations, forged));
    }

    @Test
    void synchronizedMarketTimeBlockSamplesAreDeterministicAndInputOrderInvariant() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Instant start = Instant.parse("2022-01-01T00:00:00Z").plus(Duration.ofDays(70L * index));
            blocks.add(new LiquidationChronologicalPolicyV1.MarketTimeBlock("market-block-" + index, start,
                    start.plus(Duration.ofDays(70)), index == 3 ? List.of() : List.of("btc-candidate-a-" + index,
                    "eth-candidate-b-" + index, "sol-candidate-c-" + index)));
        }
        LiquidationChronologicalPolicyV1.SynchronizedBlockSample forward =
                LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(profile, blocks, 64, 20260920L);
        Collections.reverse(blocks);
        LiquidationChronologicalPolicyV1.SynchronizedBlockSample reversed =
                LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(profile, blocks, 64, 20260920L);

        assertEquals(JsonHashes.canonicalSha256(forward.toJson()), JsonHashes.canonicalSha256(reversed.toJson()));
        assertTrue(forward.draws().stream().allMatch(draw -> draw.sampledBlockIds().size() == blocks.size()));
        ObjectNode defaultSample = LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(profile, blocks).toJson();
        assertEquals(10_000, defaultSample.path("draw_count").asInt());
        assertEquals(20_260_920L, defaultSample.path("seed").asLong());
        assertEquals("SHARED_MARKET_TIME_BLOCK_IDS_ACROSS_ASSETS_AND_CANDIDATES",
                defaultSample.path("synchronization_axis").asText());
        assertFalse(defaultSample.path("metrics_emitted").asBoolean());
        assertFalse(defaultSample.path("statistical_significance_claimed").asBoolean());
        assertTrue(blocks.get(0).synchronizedObservationIds().isEmpty()
                || blocks.stream().anyMatch(block -> block.synchronizedObservationIds().isEmpty()));
    }

    private static List<LiquidationChronologicalPolicyV1.Observation> trainingHistory() {
        ArrayList<LiquidationChronologicalPolicyV1.Observation> rows = new ArrayList<>();
        Instant end = Instant.parse("2023-10-01T00:00:00Z");
        for (Instant decision = DECISION_START; decision.isBefore(end); decision = decision.plus(Duration.ofDays(1))) {
            rows.add(completed("history-" + decision.toString().substring(0, 10), decision,
                    decision.plus(Duration.ofMinutes(1)), decision.plus(Duration.ofDays(2))));
        }
        return rows;
    }

    private static List<LiquidationChronologicalPolicyV1.Observation> append(
            List<LiquidationChronologicalPolicyV1.Observation> rows,
            LiquidationChronologicalPolicyV1.Observation value) {
        ArrayList<LiquidationChronologicalPolicyV1.Observation> copy = new ArrayList<>(rows);
        copy.add(value); return copy;
    }

    private static LiquidationChronologicalPolicyV1.Observation completed(String id, Instant decision,
            Instant firstFill, Instant exit) {
        return LiquidationChronologicalPolicyV1.Observation.completed(id, decision, firstFill, exit);
    }

    private static LiquidationChronologicalPolicyV1.Observation observation(
            List<LiquidationChronologicalPolicyV1.Observation> rows, String id) {
        return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    private static Instant quarterStart(int index) {
        int year = 2024 + index / 4;
        int month = 1 + index % 4 * 3;
        return LocalDate.of(year, month, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
