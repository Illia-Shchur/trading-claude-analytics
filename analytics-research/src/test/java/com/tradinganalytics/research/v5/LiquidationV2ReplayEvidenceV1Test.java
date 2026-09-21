package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationV2ReplayEvidenceV1Test {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";

    @Test
    void bindsInputsEmitsMatchedStatisticsAndRetainsThePartial67DayTail() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        Instant tail = Instant.parse("2026-07-14T23:59:59Z");
        ObjectNode replay = replay(frozen, List.of(
                pair("pair-early", Instant.parse("2023-01-01T00:00:00Z"), State.CLOSED),
                pair("pair-tail", tail, State.CLOSED),
                diagnostic("diag-early", Instant.parse("2023-01-01T00:00:00Z"))));

        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
        assertTrue(LiquidationV2ReplayEvidenceV1.validate(replay, frozen, evidence));
        assertEquals("BLOCKED", evidence.path("status").asText());
        assertEquals(4, evidence.path("current_frozen_candidate_count").asInt());
        assertEquals(4, evidence.path("current_evaluated_candidate_count").asInt());
        assertEquals("REQUIRE_MACRO_CONFIRMATION", evidence.path("current_candidate_inventory")
                .path("default_stage_add_macro_gate_policy").asText());
        assertTrue(evidence.path("parent_family_lineage").path("reopened").asBoolean());
        assertTrue(evidence.path("cumulative_effective_candidate_count").isNull());
        assertEquals("BLOCKED_PRIOR_FAMILY_EXPOSURE_MISSING",
                evidence.path("candidate_multiplicity_status").asText());
        assertEquals(2, evidence.path("paired_core_decision_count").asInt());
        assertEquals(1, evidence.path("diagnostic_audit_row_count").asInt());
        assertEquals(2, evidence.path("effective_independent_episode_count").asInt());
        assertEquals(21, evidence.path("maximum_possible_67d_dependence_components_in_frozen_window").asInt());
        assertFalse(evidence.path("minimum_67d_episode_floor_feasible_in_frozen_window").asBoolean());
        assertTrue(evidence.path("advancement_blockers").toString()
                .contains("FROZEN_WINDOW_CANNOT_SUPPORT_MINIMUM_67D_EPISODE_FLOOR_MAX_POSSIBLE_21"));
        assertTrue(evidence.path("descriptive_72h_episode_cluster_count").asInt() <= 2);
        assertEquals(8, evidence.path("chronological_inventory").path("folds").size());
        assertEquals(8, evidence.path("outer_fold_metrics").size());
        assertEquals(10_000, evidence.path("synchronized_block_indices").path("draw_count").asInt());
        assertEquals(20_260_920L, evidence.path("synchronized_block_indices").path("seed").asLong());
        assertEquals(10_000, evidence.path("synchronized_block_indices").path("draws").size());
        assertEquals("AUDIT_ONLY_EXCLUDED_FROM_TRADED_ZERO_ARM",
                evidence.path("opportunity_disposition").get(0).path("policy_disposition").asText());
        assertFalse(evidence.path("promotion_permitted").asBoolean());
        assertFalse(evidence.path("trade_authorization_permitted").asBoolean());
        assertEquals("SYNCHRONIZED_67D_MARKET_TIME_BLOCKS_WITH_REPLACEMENT",
                evidence.path("paired_core_statistics").path("resampling_unit").asText());
        assertEquals(0.0, evidence.path("paired_core_statistics").path("incremental_p20_vs_continuation_r").asDouble());
        assertEquals(1.0, evidence.path("paired_core_statistics")
                .path("centered_max_statistic_adjusted_p_vs_continuation").asDouble());
        assertEquals(1.0, evidence.path("paired_core_statistics")
                .path("centered_max_statistic_adjusted_p_vs_reversal").asDouble());
        assertFalse(evidence.path("acceptance_gates").path("incremental_p20_positive_vs_continuation").asBoolean());
        assertFalse(evidence.path("acceptance_gates").path("centered_max_statistic_adjusted_p_le_0_05_vs_continuation").asBoolean());
        assertTrue(evidence.path("advancement_blockers").toString().contains("FROZEN_ACCEPTANCE_GATE_FAILED_ADJUSTED_P_VS_CONTINUATION_GATE"));

        ArrayNode blocks = (ArrayNode) evidence.path("market_time_blocks");
        JsonNode last = blocks.get(blocks.size() - 1);
        assertEquals("2026-07-15T00:00:00Z", last.path("end_exclusive").asText());
        Set<String> retained = new HashSet<>();
        for (JsonNode block : blocks) for (JsonNode id : block.path("synchronized_observation_ids")) {
            assertTrue(retained.add(id.asText()), "observation assigned to more than one block");
        }
        assertEquals(6, retained.size());
        assertTrue(blocks.toString().contains("pair-tail"), "final partial window's opportunities must be retained");
    }

    @Test
    void coreAccountNetEvidenceUsesCanonicalJsonNumbersAndDeclaresPboNotApplicable() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode inMemoryReplay = replay(frozen, List.of(pair("decimal-account", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ObjectNode summary = inMemoryReplay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1")
                .put("ledger_sha256", inMemoryReplay.path("ledger_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02)
                .put("starting_equity_usdt", 20000);
        summary.put("ending_marked_equity_usdt", new BigDecimal("20312.123456789123456789"));
        summary.put("content_sha256", JsonHashes.ownHash(summary));
        inMemoryReplay.put("content_sha256", JsonHashes.ownHash(inMemoryReplay));
        ObjectNode reopenedReplay = (ObjectNode) JsonHashes.mapper().readTree(JsonHashes.canonicalBytes(inMemoryReplay));

        ObjectNode inMemoryEvidence = LiquidationV2ReplayEvidenceV1.build(inMemoryReplay, frozen);
        ObjectNode reopenedEvidence = LiquidationV2ReplayEvidenceV1.build(reopenedReplay, frozen);
        double expectedNet = reopenedReplay.path("account_path_summary").path("ending_marked_equity_usdt")
                .decimalValue().subtract(reopenedReplay.path("account_path_summary")
                        .path("starting_equity_usdt").decimalValue()).doubleValue();
        assertEquals(expectedNet, inMemoryEvidence.path("account_path_metrics").path("base_net_pnl_usdt").asDouble(), 0.0);
        assertEquals(JsonHashes.canonicalSha256(inMemoryEvidence), JsonHashes.canonicalSha256(reopenedEvidence));
        JsonNode overfit = inMemoryEvidence.path("overfit_selection_diagnostics");
        assertFalse(overfit.path("selection_search_performed").asBoolean(true));
        assertEquals("NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION", overfit.path("pbo_status").asText());
        assertTrue(overfit.path("pbo_value").isNull());
        assertEquals("FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX", overfit.path("pbo_reason").asText());
        assertEquals("UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE", overfit.path("dsr_status").asText());
        assertTrue(overfit.path("dsr_value").isNull());
    }

    @Test
    void overlappingPositionIntervalsMergeAdjacentDependenceBlocks() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        Instant start = Instant.parse(frozen.profile().path("windows").path("decision_start").asText());
        Instant boundary = start.plus(Duration.ofDays(67));
        ObjectNode replay = replay(frozen, List.of(
                pair("cross-left", boundary.minus(Duration.ofDays(1)), State.CLOSED),
                pair("cross-right", boundary.plus(Duration.ofDays(1)), State.CLOSED)));
        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
        int nominal = Math.toIntExact(Duration.between(start,
                Instant.parse(frozen.profile().path("windows").path("decision_end_exclusive").asText())).toDays() / 67);
        assertTrue(evidence.path("market_time_blocks").size() < nominal,
                "a completed fill-to-exit interval crossing a nominal boundary must merge the synchronized blocks");
        assertTrue(evidence.path("market_time_blocks").toString().contains("cross-left"));
        assertTrue(evidence.path("market_time_blocks").toString().contains("cross-right"));
    }

    @Test
    void noTradeZerosArePairedOutcomesButDoNotInflateDependenceAwareTradeEpisodes() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        List<PairSpec> pairs = new ArrayList<>();
        Instant first = Instant.parse("2023-01-01T00:00:00Z");
        for (int index = 0; index < 30; index++) {
            pairs.add(pair("resolved-zero-" + index, first.plus(Duration.ofDays(index * 4L)), State.RESOLVED_NO_TRADE));
        }
        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay(frozen, pairs), frozen);
        assertEquals(30, evidence.path("paired_core_statistics").path("complete_paired_decision_count").asInt());
        assertEquals(0, evidence.path("effective_independent_episode_count").asInt());
        assertEquals(0.0, evidence.path("paired_core_statistics").path("routed_net_expectancy_p20_r").asDouble());
        assertEquals(8, evidence.path("chronological_inventory").path("folds").size());
        assertTrue(evidence.path("acceptance_gates").path("minimum_67d_dependence_aware_episodes").isBoolean());
        assertFalse(evidence.path("acceptance_gates").path("minimum_67d_dependence_aware_episodes").asBoolean());
        assertEquals("BLOCKED", evidence.path("status").asText());
    }

    @Test
    void runnerEvaluatedInventoryCountsFrozenArmsEvenWhenTheyHaveZeroOpportunityRows() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode replay = replay(frozen, List.of());

        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
        assertEquals(4, evidence.path("current_frozen_candidate_count").asInt());
        assertEquals(4, evidence.path("current_evaluated_candidate_count").asInt());
        assertEquals(0, evidence.path("paired_core_decision_count").asInt());
        assertEquals(4, evidence.path("current_evaluated_candidate_ids").size());
        assertTrue(evidence.path("evaluated_candidate_inventory").get(3).path("audit_only").asBoolean());
        assertEquals(0, evidence.path("evaluated_candidate_inventory").get(3).path("opportunity_count").asInt());
        assertEquals("SYNTHETIC_FIXTURE_EXECUTED", evidence.path("candidate_evaluation_scopes").get(0).asText());
        assertEquals("BLOCKED", evidence.path("status").asText());
    }

    @Test
    void evaluatedCandidateExposureMustMatchFrozenInventoryScopeAndOpportunityCounts() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode replay = replay(frozen, List.of(pair("exposure-pair", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));

        ObjectNode wrongCount = replay.deepCopy();
        ((ObjectNode) wrongCount.path("evaluated_candidates").get(0)).put("opportunity_count", 0);
        wrongCount.put("content_sha256", JsonHashes.ownHash(wrongCount));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(wrongCount, frozen));

        ObjectNode missingCandidate = replay.deepCopy();
        ((ObjectNode) missingCandidate.path("evaluated_candidates").get(0)).put("candidate_id", "not-frozen");
        missingCandidate.put("content_sha256", JsonHashes.ownHash(missingCandidate));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(missingCandidate, frozen));

        ObjectNode wrongScope = replay.deepCopy();
        ((ObjectNode) wrongScope.path("evaluated_candidates").get(0)).put("execution_scope", "HISTORICAL_PIT_QUALIFIED");
        wrongScope.put("content_sha256", JsonHashes.ownHash(wrongScope));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(wrongScope, frozen));
    }

    @Test
    void unresolvedOpenAndCoverageBlockedPairsBlockAdvancementAndStayInExclusionInventory() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("PROXY_DISCLOSED_DEVELOPMENT_ONLY", false);
        ObjectNode replay = replay(frozen, List.of(
                pair("open-pair", Instant.parse("2024-01-20T00:00:00Z"), State.OPEN),
                pair("blocked-pair", Instant.parse("2024-02-01T00:00:00Z"), State.BLOCKED),
                pair("unresolved-pair", Instant.parse("2024-02-10T00:00:00Z"), State.UNRESOLVED),
                diagnostic("diag-no-trade", Instant.parse("2024-02-10T00:00:00Z"))));

        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
        assertEquals("BLOCKED", evidence.path("status").asText());
        assertEquals(1, evidence.path("diagnostic_audit_row_count").asInt());
        List<String> dispositions = evidence.path("opportunity_disposition").findValuesAsText("policy_disposition");
        assertTrue(dispositions.contains("RETAINED_OPEN_UNRESOLVED"));
        assertTrue(dispositions.contains("RETAINED_COVERAGE_BLOCKED"));
        assertTrue(dispositions.contains("RETAINED_UNRESOLVED_NO_FILL"));
        assertTrue(evidence.path("advancement_blockers").toString().contains("OPEN_UNRESOLVED_OR_COVERAGE_BLOCKED"));
        assertTrue(evidence.path("chronological_inventory").toString().contains("OUTCOME_UNRESOLVED"));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.validate(
                replay, frozen, tamperEvidence(evidence)));
    }

    @Test
    void forgedHashesMismatchedPairsAndUnknownVariantsFailClosed() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode forgedLedger = replay(frozen, List.of(pair("pair-hash", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        forgedLedger.put("ledger_sha256", "f".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(forgedLedger, frozen));

        ObjectNode forgedCurve = replay(frozen, List.of(pair("pair-curve", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        forgedCurve.put("account_curve_sha256", "0".repeat(64));
        forgedCurve.put("content_sha256", JsonHashes.ownHash(forgedCurve));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(forgedCurve, frozen));

        ObjectNode mismatch = replay(frozen, List.of(pair("pair-mismatch", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ((ObjectNode) mismatch.path("opportunities").get(1)).put("pair_id", "pair-other");
        mismatch.put("content_sha256", JsonHashes.ownHash(mismatch));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(mismatch, frozen));

        ObjectNode unknown = replay(frozen, List.of(pair("pair-unknown", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ((ObjectNode) unknown.path("opportunities").get(0)).put("variant", "ALWAYS_LONG_CONTROL");
        unknown.put("content_sha256", JsonHashes.ownHash(unknown));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(unknown, frozen));
    }

    @Test
    void strictFillAndIntegerTimeBoundariesAreValidatedBeforeCalculations() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode sameTimeFill = replay(frozen, List.of(pair("same-time", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ObjectNode routed = findVariant(sameTimeFill, "ROUTED_REVERSAL_CONTINUATION");
        routed.put("first_fill_time", routed.path("decision_time").asText());
        sameTimeFill.put("content_sha256", JsonHashes.ownHash(sameTimeFill));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(sameTimeFill, frozen));

        ObjectNode fractionalCount = replay(frozen, List.of(pair("fractional-count", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        fractionalCount.put("event_count", 25.5);
        fractionalCount.put("content_sha256", JsonHashes.ownHash(fractionalCount));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(fractionalCount, frozen));

        ObjectNode fractionalCurveTime = replay(frozen, List.of(pair("fractional-time", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ((ObjectNode) fractionalCurveTime.path("account_curve").get(0)).put("time", 1_700_000_000_000.5);
        fractionalCurveTime.put("account_curve_sha256", JsonHashes.canonicalSha256(fractionalCurveTime.path("account_curve")));
        fractionalCurveTime.put("content_sha256", JsonHashes.ownHash(fractionalCurveTime));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(fractionalCurveTime, frozen));

        ObjectNode sameTimeResolution = replay(frozen, List.of(pair("same-time-resolution",
                Instant.parse("2024-01-20T00:00:00Z"), State.RESOLVED_NO_TRADE)));
        ObjectNode noTrade = findVariant(sameTimeResolution, "ROUTED_REVERSAL_CONTINUATION");
        noTrade.put("outcome_available_time", Instant.parse(noTrade.path("decision_time").asText()).minusNanos(1).toString());
        sameTimeResolution.put("content_sha256", JsonHashes.ownHash(sameTimeResolution));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(sameTimeResolution, frozen));
        noTrade.put("outcome_available_time", noTrade.path("decision_time").asText());
        sameTimeResolution.put("content_sha256", JsonHashes.ownHash(sameTimeResolution));
        ObjectNode sameTimeResolvedEvidence = LiquidationV2ReplayEvidenceV1.build(sameTimeResolution, frozen);
        assertEquals(1, sameTimeResolvedEvidence.path("paired_core_statistics")
                .path("complete_paired_decision_count").asInt());

        ObjectNode overflowingR = replay(frozen, List.of(pair("overflowing-r",
                Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ObjectNode tinyRisk = findVariant(overflowingR, "ROUTED_REVERSAL_CONTINUATION");
        tinyRisk.put("net_pnl_usdt", 1.0e308).put("reference_risk_usdt", 1.0e-308);
        overflowingR.put("content_sha256", JsonHashes.ownHash(overflowingR));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayEvidenceV1.build(overflowingR, frozen));
    }

    @Test
    void accountPathAndStressGatesRequireBoundRunnerEvidenceAndRecomputeThresholds() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode replay = replay(frozen, List.of(pair("stress-pair", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        ObjectNode account = replay.putObject("account_path_summary").put("schema", "liquidation-v2-account-path-summary/1")
                .put("ledger_sha256", replay.path("ledger_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.12)
                .put("starting_equity_usdt", "20000.00").put("ending_marked_equity_usdt", "20500.00");
        account.put("content_sha256", JsonHashes.ownHash(account));
        JsonNode required = frozen.precommit().path("experiment").path("acceptance").path("stress").path("required_scenarios");
        ObjectNode stress = validStressEvaluation(replay, frozen, required);
        stress.put("content_sha256", JsonHashes.ownHash(stress));
        replay.put("content_sha256", JsonHashes.ownHash(replay));

        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
        assertTrue(evidence.path("acceptance_gates").path("marked_equity_drawdown_at_most_30_percent").asBoolean());
        assertTrue(evidence.path("acceptance_gates").path("base_net_pnl_positive").asBoolean());
        assertTrue(evidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean());

        ObjectNode forgedStress = replay.deepCopy();
        ObjectNode evaluation = forgedStress.putObject("stress_evaluation").put("frozen_scenario_policy_sha256", "c".repeat(64))
                .put("schema", "liquidation-v2-stress-evaluation/1").put("base_ledger_sha256", replay.path("ledger_sha256").asText());
        evaluation.putArray("scenario_results").addObject()
                .put("scenario_id", "fee_slippage").put("completed_observations", 999)
                .put("expectancy_r", 99.0).put("affected_position_count", 99);
        evaluation.put("content_sha256", JsonHashes.ownHash(evaluation));
        forgedStress.put("content_sha256", JsonHashes.ownHash(forgedStress));
        ObjectNode forgedEvidence = LiquidationV2ReplayEvidenceV1.build(forgedStress, frozen);
        assertFalse(forgedEvidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean());
    }

    @Test
    void frozenRobustTradeGatesUseCumulativeNetRDrawdownAndLedgerMatchedPaidCostR() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        Instant start = Instant.parse("2023-01-01T00:00:00Z");
        ObjectNode replay = replay(frozen, List.of(
                pair("robust-1", start, State.CLOSED),
                pair("robust-2", start.plus(Duration.ofDays(100)), State.CLOSED),
                pair("robust-3", start.plus(Duration.ofDays(200)), State.CLOSED),
                pair("robust-4", start.plus(Duration.ofDays(300)), State.CLOSED)));
        bindRobustTradeLedger(replay, new double[] {1, -2, 3, -4}, new double[] {.1, .3, .2, .4}, false);

        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
        JsonNode statistics = evidence.path("robust_trade_statistics");
        assertEquals(-4.0, statistics.path("maximum_drawdown_r").asDouble(),
                "drawdown follows the ordered cumulative net-R path, not drawdown dollars divided by summed budgets");
        assertEquals(.25, statistics.path("mean_cost_r").asDouble(), 1e-12,
                "each position's paid costs are normalized by its own R then averaged by position count");
        assertTrue(evidence.path("acceptance_gates").path("maximum_drawdown_r").asBoolean());
        assertTrue(evidence.path("acceptance_gates").path("maximum_cost_r").asBoolean());

        ObjectNode breached = replay(frozen, List.of(
                pair("breach-1", start, State.CLOSED),
                pair("breach-2", start.plus(Duration.ofDays(100)), State.CLOSED)));
        bindRobustTradeLedger(breached, new double[] {1, -22}, new double[] {.6, .6}, false);
        ObjectNode breachedEvidence = LiquidationV2ReplayEvidenceV1.build(breached, frozen);
        assertEquals(-22.0, breachedEvidence.path("robust_trade_statistics").path("maximum_drawdown_r").asDouble());
        assertFalse(breachedEvidence.path("acceptance_gates").path("maximum_drawdown_r").asBoolean());
        assertFalse(breachedEvidence.path("acceptance_gates").path("maximum_cost_r").asBoolean());

        ObjectNode missingCosts = replay(frozen, List.of(pair("missing-cost", start, State.CLOSED)));
        bindRobustTradeLedger(missingCosts, new double[] {1}, new double[] {.1}, true);
        ObjectNode missingEvidence = LiquidationV2ReplayEvidenceV1.build(missingCosts, frozen);
        assertTrue(missingEvidence.path("robust_trade_statistics").path("mean_cost_r").isNull(),
                "an absent paid funding-debit field is unavailable evidence, not zero cost");
        assertTrue(missingEvidence.path("acceptance_gates").path("maximum_cost_r").isNull());
        assertTrue(missingEvidence.path("advancement_blockers").toString()
                .contains("FROZEN_ACCEPTANCE_GATE_UNAVAILABLE_MEAN_POSITION_COST_R_GATE"));
    }

    private static void bindRobustTradeLedger(ObjectNode replay, double[] netR, double[] costR,
            boolean omitFinalFundingDebit) {
        ArrayNode positions = JsonHashes.mapper().createArrayNode();
        int index = 0;
        for (JsonNode value : replay.path("opportunities")) {
            if (!ROUTED.equals(value.path("candidate_id").asText())) continue;
            ObjectNode row = (ObjectNode) value;
            String setupId = "robust-setup-" + index;
            Instant fill = Instant.parse(row.path("decision_time").asText()).plusSeconds(60);
            Instant exit = Instant.parse(row.path("decision_time").asText()).plus(Duration.ofDays(2));
            row.put("setup_id", setupId).put("first_fill_time", fill.toString())
                    .put("exit_time", exit.toString()).put("outcome_available_time", exit.toString())
                    .put("reference_risk_usdt", 200.0).put("net_pnl_usdt", netR[index] * 200.0);
            double totalCost = costR[index] * 200.0;
            ObjectNode position = positions.addObject().put("status", "CLOSED")
                    .put("active_setup_id", setupId).put("asset", "BTC").put("first_fill_time", fill.toEpochMilli())
                    .put("entry_costs_usdt", totalCost * .25).put("exit_costs_usdt", totalCost * .25)
                    .put("funding_pnl_usdt", 10_000.0);
            if (!(omitFinalFundingDebit && index == netR.length - 1)) {
                position.put("funding_debits_for_risk_headroom_usdt", totalCost * .5);
            }
            position.putArray("exits").addObject().put("exit_time", exit.toString());
            index++;
        }
        if (index != netR.length || netR.length != costR.length) throw new IllegalArgumentException("fixture arrays must match routed trades");
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes");
        account.set("positions", positions);
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", ROUTED).set("account", account);
        ledger.put("content_sha256", JsonHashes.ownHash(ledger));
        String ledgerSha = ledger.path("content_sha256").asText();
        replay.put("ledger_sha256", ledgerSha);
        ObjectNode path = replay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1").put("ledger_sha256", ledgerSha)
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", .05)
                .put("starting_equity_usdt", "20000.00").put("ending_marked_equity_usdt", "21000.00");
        path.put("content_sha256", JsonHashes.ownHash(path));
        replay.put("content_sha256", JsonHashes.ownHash(replay));
    }

    @Test
    void anyUnresolvedOrCoverageBlockedStressScenarioFailsItsGate() throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode base = replay(frozen, List.of(pair("stress-pair", Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        JsonNode required = frozen.precommit().path("experiment").path("acceptance").path("stress").path("required_scenarios");

        for (String blockedField : List.of("open_unresolved_count", "coverage_blocked_count")) {
            ObjectNode replay = base.deepCopy();
            ObjectNode stress = validStressEvaluation(replay, frozen, required);
            ObjectNode scenario = (ObjectNode) stress.path("scenario_results").get(0);
            ObjectNode routed = findStressCandidate(scenario, "liquidation-v2-core-routed-one-entry");
            routed.put(blockedField, 1).put("unresolved_count", 1);
            scenario.put("unresolved_count", 1)
                    .put("coverage_blocked_count", "coverage_blocked_count".equals(blockedField) ? 1 : 0)
                    .put("candidate_gate_status", "BLOCKED_UNRESOLVED_OR_COVERAGE");
            stress.put("content_sha256", JsonHashes.ownHash(stress));
            replay.put("content_sha256", JsonHashes.ownHash(replay));

            ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, frozen);
            assertFalse(evidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean(), blockedField);
            assertFalse(evidence.path("acceptance_gates").path("five_frozen_stress_gates")
                    .path("fee_slippage").asBoolean(), blockedField);
        }
    }

    private static ObjectNode validStressEvaluation(ObjectNode replay,
            LiquidationV2ReplayEvidenceV1.FreezeInputs frozen, JsonNode required) {
        ObjectNode stress = replay.putObject("stress_evaluation").put("schema", "liquidation-v2-stress-evaluation/1")
                .put("status", "RUNNER_EXECUTED_DEVELOPMENT_ONLY")
                .put("base_ledger_sha256", replay.path("ledger_sha256").asText())
                .put("frozen_scenario_policy_sha256", JsonHashes.canonicalSha256(required))
                .put("stress_policy_sha256", LiquidationV2StressPolicyV1.reopen(frozen.precommit()).contentSha256())
                .put("all_scenarios_rerun_from_physical_observations", true)
                .put("no_posthoc_pnl_adjustment", true);
        ArrayNode results = stress.putArray("scenario_results");
        for (JsonNode source : required) {
            String id = source.path("id").asText();
            ObjectNode row = results.addObject().put("scenario_id", id)
                    .put("runner_result_sha256", "d".repeat(64))
                    .put("scenario_run_content_sha256", "f".repeat(64))
                    .put("runner_ledger_sha256", "b".repeat(64))
                    .put("runner_event_stream_sha256", "c".repeat(64))
                    .put("transform_count", 1).put("transform_chain_sha256", "e".repeat(64))
                    .put("completed_observations", 30).put("expectancy_r", 0.01)
                    .put("unresolved_count", 0).put("coverage_blocked_count", 0)
                    .put("affected_position_count", "venue_outage_blackout".equals(id) ? 2 : 0)
                    .put("minimum_observations", source.path("minimum_observations").asInt())
                    .put("minimum_expectancy_r", source.path("minimum_expectancy_r").asDouble())
                    .put("development_only", true)
                    .put("candidate_gate_status", "CANDIDATE_THRESHOLDS_MET_DEVELOPMENT_ONLY");
            row.putObject("scenario_run_artifact").put("relative_path", "scenario-runs/" + id + ".json")
                    .put("sha256", "d".repeat(64)).put("schema", "liquidation-v2-replay-result/1");
            ArrayNode byCandidate = row.putArray("by_candidate");
            for (String candidate : List.of("liquidation-v2-core-routed-one-entry",
                    "liquidation-v2-core-always-continuation-one-entry",
                    "liquidation-v2-core-always-reversal-one-entry")) {
                byCandidate.addObject().put("candidate_id", candidate).put("completed_observations", 30)
                        .put("expectancy_r", 0.01).put("open_unresolved_count", 0)
                        .put("coverage_blocked_count", 0).put("unresolved_count", 0)
                        .put("affected_position_count", "venue_outage_blackout".equals(id) ? 2 : 0);
            }
        }
        return stress;
    }

    @Test
    void scenarioRunArtifactsAreReopenedAndBothByteAndContentHashesAreVerified(@TempDir Path physicalRoot) throws Exception {
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = frozenInputs("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode replay = replay(frozen, List.of(pair("stress-artifact-pair",
                Instant.parse("2024-01-20T00:00:00Z"), State.CLOSED)));
        JsonNode required = frozen.precommit().path("experiment").path("acceptance").path("stress").path("required_scenarios");
        ObjectNode stress = validStressEvaluation(replay, frozen, required);
        String policyHash = stress.path("stress_policy_sha256").asText();
        for (JsonNode value : stress.path("scenario_results")) {
            ObjectNode row = (ObjectNode) value;
            ObjectNode run = JsonHashes.mapper().createObjectNode()
                    .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                    .put("ledger_sha256", "b".repeat(64)).put("event_stream_sha256", "c".repeat(64));
            run.putObject("scenario_execution").put("scenario_id", row.path("scenario_id").asText())
                    .put("stress_policy_sha256", policyHash).put("transform_count", 1)
                    .put("transform_chain_sha256", "e".repeat(64));
            run.put("content_sha256", JsonHashes.ownHash(run));
            byte[] bytes = JsonHashes.canonicalBytes(run);
            Path relative = Path.of("scenario-runs", row.path("scenario_id").asText() + ".json");
            Path target = physicalRoot.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            String byteHash = JsonHashes.sha256(bytes);
            row.put("runner_result_sha256", byteHash)
                    .put("scenario_run_content_sha256", run.path("content_sha256").asText());
            row.putObject("scenario_run_artifact").put("relative_path", relative.toString())
                    .put("sha256", byteHash).put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA);
        }

        LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(replay, physicalRoot);

        ObjectNode firstScenario = (ObjectNode) stress.path("scenario_results").get(0);
        ObjectNode firstArtifact = (ObjectNode) firstScenario.path("scenario_run_artifact");
        Path firstFile = physicalRoot.resolve(firstArtifact.path("relative_path").asText());
        ObjectNode changedRun = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(firstFile));
        ((ObjectNode) changedRun.path("scenario_execution")).put("transform_count", 2);
        byte[] changedContent = JsonHashes.canonicalBytes(changedRun);
        Files.write(firstFile, changedContent);
        String changedByteHash = JsonHashes.sha256(changedContent);
        firstScenario.put("runner_result_sha256", changedByteHash);
        firstArtifact.put("sha256", changedByteHash);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(replay, physicalRoot));

        Files.writeString(firstFile, "{}");
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(replay, physicalRoot));
    }

    private static ObjectNode findStressCandidate(ObjectNode scenario, String candidateId) {
        for (JsonNode candidate : scenario.path("by_candidate")) {
            if (candidateId.equals(candidate.path("candidate_id").asText())) return (ObjectNode) candidate;
        }
        throw new IllegalArgumentException("stress candidate fixture row not found: " + candidateId);
    }

    private static ObjectNode tamperEvidence(ObjectNode evidence) {
        ObjectNode changed = evidence.deepCopy();
        changed.put("status", "SHADOW");
        changed.put("content_sha256", JsonHashes.ownHash(changed));
        return changed;
    }

    private static LiquidationV2ReplayEvidenceV1.FreezeInputs frozenInputs(String mode, boolean replayPermitted) throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode precommit = readFrozenPrecommit();
        ObjectNode manifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-physical-manifest/1").put("version", 1)
                .put("status", "SYNTHETIC_DEVELOPMENT_ONLY".equals(mode)
                        ? "DEVELOPMENT_SYNTHETIC" : "DEVELOPMENT_PROXY_DISCLOSED")
                .put("source_mode", mode)
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("authoritative", false).put("authoritative_evaluation_permitted", false);
        if ("PROXY_DISCLOSED_DEVELOPMENT_ONLY".equals(mode)) manifest.put("development_replay_permitted", replayPermitted);
        manifest.put("content_sha256", JsonHashes.ownHash(manifest));
        ObjectNode executor = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EXECUTOR_IDENTITY_SCHEMA).put("version", 1)
                .put("capability", profile.path("executor_capability").asText())
                .put("default_stage_add_macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        executor.put("content_sha256", JsonHashes.ownHash(executor));
        ObjectNode inventory = LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(profile);
        Path root = repositoryRoot();
        ObjectNode parent = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(root.resolve(
                "docs/research/liquidation-structure-v001/frozen-precommit.json")));
        Path freezeManifestPath = root.resolve("docs/research/liquidation-structure-v001/FREEZE-MANIFEST.json");
        String freezeManifestBytes = Files.readString(freezeManifestPath);
        ObjectNode parentFreezeManifest = (ObjectNode) JsonHashes.mapper().readTree(freezeManifestBytes);
        String feasibility = Files.readString(root.resolve("docs/research/liquidation-structure-v001/FEASIBILITY.md"));
        return new LiquidationV2ReplayEvidenceV1.FreezeInputs(manifest, precommit, profile, executor, inventory,
                null, parent, parentFreezeManifest, freezeManifestBytes, feasibility);
    }

    private static ObjectNode readFrozenPrecommit() throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repositoryRoot().resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root is unavailable to frozen precommit test");
        return cursor;
    }

    private static ObjectNode replay(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen, List<PairSpec> pairs) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1);
        ObjectNode refs = replay.putObject("freeze");
        refs.put("manifest_sha256", frozen.manifest().path("content_sha256").asText())
                .put("precommit_sha256", frozen.precommit().path("content_sha256").asText())
                .put("profile_sha256", frozen.profile().path("content_sha256").asText())
                .put("executor_sha256", frozen.executorIdentity().path("content_sha256").asText())
                .put("candidate_inventory_sha256", frozen.candidateInventory().path("content_sha256").asText())
                .put("source_mode", frozen.manifest().path("source_mode").asText())
                .put("parent_precommit_sha256", frozen.parentPrecommit().path("content_sha256").asText())
                .put("parent_freeze_manifest_byte_sha256", JsonHashes.sha256(frozen.parentFreezeManifestBytes()))
                .put("parent_feasibility_byte_sha256", JsonHashes.sha256(frozen.parentFeasibilityMarkdown()));
        replay.put("event_stream_sha256", "a".repeat(64)).put("event_count", 25);
        ArrayNode curve = replay.putArray("account_curve");
        curve.addObject().put("time", 1_700_000_000_000L).put("equity_usdt", "20000.00");
        curve.addObject().put("time", 1_700_000_060_000L).put("equity_usdt", "20010.00");
        replay.put("account_curve_sha256", JsonHashes.canonicalSha256(curve))
                .put("ledger_sha256", "b".repeat(64)).put("account_curve_scope", "DAILY_MARKS_ONLY");
        ArrayNode opportunities = replay.putArray("opportunities");
        for (PairSpec pair : pairs) {
            if (pair.state() == State.DIAGNOSTIC) addDiagnostic(opportunities, pair);
            else {
                addOpportunity(opportunities, pair.id(), ROUTED, "ROUTED_REVERSAL_CONTINUATION", pair.decision(), pair.state());
                addOpportunity(opportunities, pair.id(), CONTINUE, "ALWAYS_CONTINUATION_CONTROL", pair.decision(), pair.state());
                addOpportunity(opportunities, pair.id(), REVERSE, "ALWAYS_REVERSAL_CONTROL", pair.decision(), pair.state());
            }
        }
        ArrayNode evaluated = replay.putArray("evaluated_candidates");
        String scope = "SYNTHETIC_DEVELOPMENT_ONLY".equals(frozen.manifest().path("source_mode").asText())
                ? "SYNTHETIC_FIXTURE_EXECUTED" : "PROXY_RETROSPECTIVE_DIAGNOSTIC_EXECUTED";
        for (JsonNode candidate : frozen.candidateInventory().path("current_candidates")) {
            String candidateId = candidate.path("candidate_id").asText();
            long opportunityCount = 0;
            for (JsonNode row : opportunities) if (candidateId.equals(row.path("candidate_id").asText())) opportunityCount++;
            evaluated.addObject().put("candidate_id", candidateId)
                    .put("variant", candidate.path("variant").asText())
                    .put("audit_only", candidate.path("audit_only").asBoolean())
                    .put("execution_scope", scope).put("opportunity_count", opportunityCount).put("executed", true);
        }
        replay.put("content_sha256", JsonHashes.ownHash(replay));
        return replay;
    }

    private static ObjectNode findVariant(ObjectNode replay, String variant) {
        for (JsonNode row : replay.path("opportunities")) if (variant.equals(row.path("variant").asText())) return (ObjectNode) row;
        throw new IllegalArgumentException("variant fixture row not found");
    }

    private static PairSpec pair(String id, Instant decision, State state) { return new PairSpec(id, decision, state); }
    private static PairSpec diagnostic(String id, Instant decision) { return new PairSpec(id, decision, State.DIAGNOSTIC); }

    private static void addOpportunity(ArrayNode into, String pairId, String candidateId, String variant,
            Instant decision, State state) {
        ObjectNode row = into.addObject().put("opportunity_id", pairId + "::" + candidateId)
                .put("pair_id", pairId).put("candidate_id", candidateId).put("variant", variant)
                .put("asset", "BTC").put("decision_time", decision.toString())
                .put("branch", "ALWAYS_CONTINUATION_CONTROL".equals(variant) ? "CONTINUATION"
                        : "ALWAYS_REVERSAL_CONTROL".equals(variant) ? "REVERSAL" : "CONTINUATION")
                .put("direction", "LONG");
        switch (state) {
            case CLOSED -> row.put("outcome_state", "CLOSED_TRADE")
                    .put("outcome_available_time", decision.plus(Duration.ofDays(2)).toString())
                    .put("first_fill_time", decision.plus(Duration.ofMinutes(1)).toString())
                    .put("exit_time", decision.plus(Duration.ofDays(2)).toString())
                    .put("net_pnl_usdt", 12.5).put("reference_risk_usdt", 200.0);
            case RESOLVED_NO_TRADE -> row.put("outcome_state", "RESOLVED_NO_TRADE")
                    .put("outcome_available_time", decision.plus(Duration.ofDays(1)).toString())
                    .putNull("first_fill_time").putNull("exit_time").put("net_pnl_usdt", 0.0).put("reference_risk_usdt", 0.0);
            case OPEN -> row.put("outcome_state", "OPEN_UNRESOLVED").putNull("outcome_available_time")
                    .put("first_fill_time", decision.plus(Duration.ofMinutes(1)).toString()).putNull("exit_time")
                    .putNull("net_pnl_usdt").put("reference_risk_usdt", 200.0);
            case UNRESOLVED -> row.put("outcome_state", "UNRESOLVED_NO_FILL").putNull("outcome_available_time")
                    .putNull("first_fill_time").putNull("exit_time").putNull("net_pnl_usdt").putNull("reference_risk_usdt");
            case BLOCKED -> row.put("outcome_state", "COVERAGE_BLOCKED").putNull("outcome_available_time")
                    .putNull("first_fill_time").putNull("exit_time").putNull("net_pnl_usdt").putNull("reference_risk_usdt");
            case DIAGNOSTIC -> throw new IllegalArgumentException("use addDiagnostic for audit rows");
        }
        if (state == State.BLOCKED) row.putArray("reason_codes").add("COVERAGE_GAP");
        else row.putArray("reason_codes");
    }

    private static void addDiagnostic(ArrayNode into, PairSpec pair) {
        into.addObject().put("opportunity_id", pair.id() + "::" + DIAGNOSTIC)
                .put("pair_id", pair.id()).put("candidate_id", DIAGNOSTIC)
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC").put("asset", "BTC")
                .put("decision_time", pair.decision().toString()).put("outcome_state", "COVERAGE_BLOCKED")
                .putNull("outcome_available_time").putNull("first_fill_time").putNull("exit_time")
                .putNull("net_pnl_usdt").putNull("reference_risk_usdt")
                .putArray("reason_codes").add("AUDIT_ONLY");
    }

    private enum State { CLOSED, RESOLVED_NO_TRADE, OPEN, UNRESOLVED, BLOCKED, DIAGNOSTIC }
    private record PairSpec(String id, Instant decision, State state) {}
}
