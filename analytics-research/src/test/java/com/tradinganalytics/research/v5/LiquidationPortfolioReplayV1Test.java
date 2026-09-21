package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationPortfolioReplayV1Test {
    private static final String ASSET = "BTC";
    private static final String BINANCE = "BTCUSDT";
    private static final String COINALYZE = "BTCUSDT_PERP.A";
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant REPLAY_START = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant REPLAY_END = Instant.parse("2024-01-04T00:00:00Z");
    private static final Instant STRESS_START = Instant.parse("2024-01-01T08:00:00Z");
    private static final Instant MODEL_AVAILABLE = Instant.parse("2024-01-03T00:00:00Z");
    private static final Instant FILL_TIME = Instant.parse("2024-01-03T09:01:00Z");
    private static final Instant STOP_BAR_START = Instant.parse("2024-01-03T09:02:00Z");
    // BAR_POST observes the 09:02 OHLC touch only when that one-minute bar completes at 09:03.
    private static final Instant STOP_TIME = Instant.parse("2024-01-03T09:03:00Z");
    private static final Instant FUNDING_TIME = Instant.parse("2024-01-03T16:00:00.007Z");
    private static final Instant FUNDING_EXIT_BAR_START = Instant.parse("2024-01-03T16:01:00Z");
    private static final Instant FUNDING_EXIT_KNOWN_AT = Instant.parse("2024-01-03T16:02:00Z");
    private static final Instant OUTAGE_TRIGGER_BAR = Instant.parse("2024-01-03T00:30:00Z");
    private static final Instant OUTAGE_TRIGGER_KNOWN_AT = Instant.parse("2024-01-03T00:31:00Z");
    private static final Instant OUTAGE_RESUME = Instant.parse("2024-01-03T01:00:00Z");
    private static final Instant FEATURE_START = Instant.parse("2023-10-01T00:00:00Z");

    @TempDir Path temporary;

    @Test
    void publicSyntheticReplayReopensFreezeFillsAndClosesAllPairedArmsDeterministically() throws Exception {
        Scenario scenario = scenario(temporary.resolve("filled"), 99.45, false);
        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", scenario.root().toString());
        physicalOptions.set("profile", scenario.freeze().path("profile").deepCopy());
        physicalOptions.set("manifest", scenario.freeze().path("physical_manifest").deepCopy());
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession physical =
                LiquidationV2PhysicalDataV1.openVerifiedDevelopment(physicalOptions);
        long macroClose = Instant.parse("2024-01-02T21:00:00Z").toEpochMilli();
        assertTrue(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(physical, macroClose));

        ObjectNode replayOptions = replayOptions(scenario, "result-a.json");
        ObjectNode first = LiquidationPortfolioReplayV1.run(replayOptions);
        ObjectNode second = LiquidationPortfolioReplayV1.run(replayOptions(scenario, "result-b.json"));

        assertEquals("liquidation-v2-replay-result/1", first.path("schema").asText());
        assertEquals("SYNTHETIC_DEVELOPMENT_ONLY", first.path("status").asText());
        assertFalse(first.path("authoritative").asBoolean(true));
        assertFalse(first.path("wfo_permitted").asBoolean(true));
        assertEquals(JsonHashes.canonicalSha256(first), JsonHashes.canonicalSha256(second));
        assertEquals(first.path("content_sha256").asText(), JsonHashes.ownHash(first));
        assertEquals(Files.readString(scenario.root().resolve("result-a.json")),
                Files.readString(scenario.root().resolve("result-b.json")));
        JsonNode persisted = JsonHashes.mapper().readTree(scenario.root().resolve("result-a.json").toFile());
        assertEquals(JsonHashes.canonicalSha256(first), JsonHashes.canonicalSha256(persisted));

        assertRunnerStressEvidence(first, scenario.freeze(), scenario.root());

        ArrayNode opportunities = (ArrayNode) first.path("opportunities");
        assertEquals(3, opportunities.size());
        Set<String> pairIds = new HashSet<>(), candidates = new HashSet<>();
        for (JsonNode opportunity : opportunities) {
            pairIds.add(opportunity.path("pair_id").asText());
            candidates.add(opportunity.path("candidate_id").asText());
            assertEquals("CLOSED_TRADE", opportunity.path("outcome_state").asText());
            assertNotNull(opportunity.path("first_fill_time").textValue());
            assertEquals(FILL_TIME.toString(), opportunity.path("first_fill_time").asText());
            assertEquals(STOP_TIME.toString(), opportunity.path("exit_time").asText());
            assertEquals(STOP_TIME.toString(), opportunity.path("exit_known_at").asText());
            assertEquals(STOP_BAR_START.toString(), opportunity.path("exit_fill_time_lower_bound").asText());
            assertEquals(STOP_TIME.toString(), opportunity.path("exit_fill_time_upper_bound").asText());
            assertEquals("MODELED_STOP_OR_TARGET_FROM_COMPLETED_1M_OHLC", opportunity.path("exit_price_basis").asText());
            assertTrue(opportunity.path("modeled_exit_price_usdt").isNumber());
            assertTrue(opportunity.path("exit_reason").asText().contains("STOP"));
            assertTrue(opportunity.path("net_pnl_usdt").isNumber());
            assertTrue(opportunity.path("first_fill_reference_equity_usdt").isNumber());
            assertEquals(0.05 * opportunity.path("first_fill_reference_equity_usdt").asDouble(),
                    opportunity.path("full_position_reference_risk_usdt").asDouble(), 1e-8,
                    "full-position reference risk uses the first-fill account equity denominator");
        }
        assertEquals(1, pairIds.size());
        assertEquals(Set.of("liquidation-v2-core-routed-one-entry",
                "liquidation-v2-core-always-continuation-one-entry",
                "liquidation-v2-core-always-reversal-one-entry"), candidates);

        ObjectNode validEvaluateOptions = JsonHashes.mapper().createObjectNode()
                .put("out", scenario.root().resolve("valid-evidence.json").toString());
        validEvaluateOptions.set("freeze", scenario.freeze().deepCopy());
        validEvaluateOptions.set("replay", first.deepCopy());
        ObjectNode validEvidence = LiquidationPortfolioReplayV1.evaluate(validEvaluateOptions);
        assertEquals("liquidation-v2-replay-evidence/1", validEvidence.path("schema").asText());
        assertFalse(validEvidence.path("promotion_permitted").asBoolean(true));

        ObjectNode forgedReplay = forgePnlAndDrawdown(first);
        ObjectNode evaluateOptions = JsonHashes.mapper().createObjectNode()
                .put("out", scenario.root().resolve("forged-evidence.json").toString());
        evaluateOptions.set("freeze", scenario.freeze().deepCopy());
        evaluateOptions.set("replay", forgedReplay);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.evaluate(evaluateOptions),
                "the public evaluator must reject a rehashed result whose PnL and drawdown no longer match physical replay");
        assertFalse(Files.exists(scenario.root().resolve("forged-evidence.json")));

        Scenario gap = scenario(temporary.resolve("gap-through-stop"), 99.45, false, true);
        ObjectNode gapReplay = LiquidationPortfolioReplayV1.run(replayOptions(gap, "gap-through-stop.json"));
        int barPreGapExits = 0;
        for (JsonNode opportunity : gapReplay.path("opportunities")) {
            assertEquals("CLOSED_TRADE", opportunity.path("outcome_state").asText());
            if ("LONG".equals(opportunity.path("direction").asText())) {
                barPreGapExits++;
                assertEquals(STOP_BAR_START.toString(), opportunity.path("exit_time").asText());
                assertEquals(STOP_BAR_START.toString(), opportunity.path("exit_fill_time_lower_bound").asText());
                assertEquals(STOP_BAR_START.toString(), opportunity.path("exit_fill_time_upper_bound").asText());
                assertEquals("MODELED_MINUTE_OPEN_GAP_FILL", opportunity.path("exit_price_basis").asText());
            } else {
                assertEquals("SHORT", opportunity.path("direction").asText());
                assertEquals(STOP_TIME.toString(), opportunity.path("exit_time").asText());
                assertEquals(STOP_BAR_START.toString(), opportunity.path("exit_fill_time_lower_bound").asText());
                assertEquals(STOP_TIME.toString(), opportunity.path("exit_fill_time_upper_bound").asText());
                assertEquals("MODELED_STOP_OR_TARGET_FROM_COMPLETED_1M_OHLC", opportunity.path("exit_price_basis").asText());
            }
        }
        assertTrue(barPreGapExits > 0, "at least one long control must exercise the BAR_PRE stop-gap path");
    }

    private static ObjectNode forgePnlAndDrawdown(ObjectNode original) {
        ObjectNode forged = original.deepCopy();
        ObjectNode ledger = (ObjectNode) forged.path("ledger");
        ObjectNode firstOpportunity = (ObjectNode) forged.path("opportunities").path(0);
        firstOpportunity.put("net_pnl_usdt", firstOpportunity.path("net_pnl_usdt").asDouble() + 123.45);
        ((ObjectNode) ledger.path("opportunities").path(0)).put("net_pnl_usdt", firstOpportunity.path("net_pnl_usdt").asDouble());

        ObjectNode accountPath = (ObjectNode) forged.path("account_path_summary");
        accountPath.put("maximum_adverse_mark_drawdown_fraction", 0.987654321);
        ledger.put("content_sha256", JsonHashes.ownHash(ledger));
        forged.put("ledger_sha256", ledger.path("content_sha256").asText());
        accountPath.put("ledger_sha256", ledger.path("content_sha256").asText());
        accountPath.put("content_sha256", JsonHashes.ownHash(accountPath));
        forged.put("content_sha256", JsonHashes.ownHash(forged));
        return forged;
    }

    private static void assertRunnerStressEvidence(ObjectNode replay, ObjectNode freeze, Path physicalRoot) throws Exception {
        JsonNode evaluation = replay.path("stress_evaluation");
        assertEquals("liquidation-v2-stress-evaluation/1", evaluation.path("schema").asText());
        assertEquals("RUNNER_EXECUTED_DEVELOPMENT_ONLY", evaluation.path("status").asText());
        assertEquals(evaluation.path("content_sha256").asText(), JsonHashes.ownHash(evaluation));
        assertEquals(replay.path("ledger_sha256").asText(), evaluation.path("base_ledger_sha256").asText());
        JsonNode required = freeze.path("precommit").path("experiment").path("acceptance")
                .path("stress").path("required_scenarios");
        assertEquals(List.of("fee_slippage", "funding_carry", "adverse_execution_gap",
                "liquidity_capacity", "venue_outage_blackout"),
                required.findValuesAsText("id"));
        assertEquals(JsonHashes.canonicalSha256(required),
                evaluation.path("frozen_scenario_policy_sha256").asText());
        assertEquals(LiquidationV2StressPolicyV1.reopen((ObjectNode) freeze.path("precommit")).contentSha256(),
                evaluation.path("stress_policy_sha256").asText());
        assertTrue(evaluation.path("all_scenarios_rerun_from_physical_observations").asBoolean());
        assertTrue(evaluation.path("no_posthoc_pnl_adjustment").asBoolean());

        ArrayNode results = (ArrayNode) evaluation.path("scenario_results");
        assertEquals(5, results.size());
        List<Integer> expectedTransformCounts = List.of(4, 0, 3, 3, 3);
        Set<String> resultHashes = new HashSet<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode result = results.get(index);
            String scenarioId = required.get(index).path("id").asText();
            assertEquals(scenarioId, result.path("scenario_id").asText());
            assertTrue(result.path("runner_result_sha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(result.path("runner_ledger_sha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(result.path("runner_event_stream_sha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(resultHashes.add(result.path("runner_result_sha256").asText()),
                    "each frozen scenario has a separately hashed runner result");
            assertEquals(expectedTransformCounts.get(index).longValue(), result.path("transform_count").asLong(),
                    "fixture transform inventory for " + scenarioId);
            assertTrue(result.path("transform_chain_sha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(result.path("development_only").asBoolean());
            assertTrue(result.path("completed_observations").asInt()
                    < result.path("minimum_observations").asInt(),
                    "the tiny synthetic fixture must remain below the frozen performance sample floor");
            ObjectNode scenarioRun = reopenScenarioRun(result, physicalRoot);
            assertEquals(result.path("runner_result_sha256").asText(), JsonHashes.canonicalSha256(scenarioRun));
            assertEquals(result.path("scenario_run_content_sha256").asText(),
                    scenarioRun.path("content_sha256").asText());
            assertEquals(result.path("runner_result_sha256").asText(),
                    result.path("scenario_run_artifact").path("sha256").asText());
            assertEquals(result.path("runner_ledger_sha256").asText(), scenarioRun.path("ledger_sha256").asText());
            assertEquals(result.path("runner_event_stream_sha256").asText(), scenarioRun.path("event_stream_sha256").asText());
            assertEquals(scenarioId, scenarioRun.path("scenario_execution").path("scenario_id").asText());
            assertEquals(result.path("transform_count").asLong(),
                    scenarioRun.path("scenario_execution").path("transform_count").asLong());
        }

        JsonNode capacityPolicy = required.get(3);
        JsonNode capacityResult = results.get(3);
        assertEquals(0.01, capacityPolicy.path("maximum_participation_rate").asDouble(), 0.0);
        assertEquals(3, capacityResult.path("transform_count").asInt(),
                "the shared three-arm pair records the existing one-percent capacity transform once per fill");
        assertTrue(capacityResult.path("capacity_policy_matches_existing_account_limit").asBoolean());
        assertTrue(capacityResult.path("no_incremental_capacity_tightening").asBoolean());
    }

    @Test
    void publicSyntheticReplayPreservesResolvedNoTradeAsZeroAndMissingOpenAsUnresolved() throws Exception {
        Scenario chase = scenario(temporary.resolve("chase"), 150, false);
        ObjectNode noTrade = LiquidationPortfolioReplayV1.run(replayOptions(chase, "no-trade.json"));
        assertEquals(3, noTrade.path("opportunities").size());
        Set<String> chasePairs = new HashSet<>();
        for (JsonNode opportunity : noTrade.path("opportunities")) {
            chasePairs.add(opportunity.path("pair_id").asText());
            assertEquals("RESOLVED_NO_TRADE", opportunity.path("outcome_state").asText());
            assertEquals(0, opportunity.path("net_pnl_usdt").asDouble());
            assertTrue(opportunity.path("reason_codes").toString().contains("MAXIMUM_CHASE_DISTANCE_EXCEEDED"));
        }
        assertEquals(1, chasePairs.size());

        Scenario missing = scenario(temporary.resolve("missing"), 99.45, true);
        ObjectNode blocked = LiquidationPortfolioReplayV1.run(replayOptions(missing, "missing-open.json"));
        assertEquals(3, blocked.path("opportunities").size());
        Set<String> blockedPairs = new HashSet<>();
        for (JsonNode opportunity : blocked.path("opportunities")) {
            blockedPairs.add(opportunity.path("pair_id").asText());
            assertEquals("COVERAGE_BLOCKED", opportunity.path("outcome_state").asText());
            assertTrue(opportunity.path("outcome_available_time").isNull());
            assertTrue(opportunity.path("net_pnl_usdt").isNull());
            assertTrue(opportunity.path("reason_codes").toString().contains("MISSING_EXACT_NEXT_MINUTE_EXECUTION_OPEN"));
        }
        assertEquals(1, blockedPairs.size());
    }

    @Test
    void fundingSettlesOnceOnLivePairedQuantityAndReconcilesSignedPnl() throws Exception {
        Scenario scenario = scenario(temporary.resolve("held-through-funding"), 99.45, false, false,
                FUNDING_EXIT_BAR_START, List.of(fundingRow(FUNDING_TIME, "held-funding", 0.001)));
        ObjectNode replay = LiquidationPortfolioReplayV1.run(replayOptions(scenario, "held-through-funding.json"));
        ArrayNode opportunities = (ArrayNode) replay.path("opportunities");
        ArrayNode accounts = (ArrayNode) replay.path("ledger").path("accounts");
        assertEquals(3, opportunities.size());
        assertEquals(3, accounts.size());
        assertEquals(1, replay.path("ledger").path("funding_rows_read").asInt());

        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(
                (ObjectNode) scenario.freeze().path("precommit"));
        boolean sawDebit = false, sawCredit = false;
        Set<String> retainedSettlementKeys = new HashSet<>();
        Set<String> retainedSettlementIds = new HashSet<>();
        for (int index = 0; index < accounts.size(); index++) {
            JsonNode account = accounts.get(index).path("account");
            JsonNode position = position(account, ASSET);
            JsonNode opportunity = opportunity(opportunities, accounts.get(index).path("candidate_id").asText());
            assertEquals("CLOSED_TRADE", opportunity.path("outcome_state").asText());
            assertEquals(FILL_TIME.toString(), opportunity.path("first_fill_time").asText());
            ArrayNode fundingEvents = (ArrayNode) position.path("funding_events");
            assertEquals(1, fundingEvents.size(), "settlement must appear once in the account position ledger");
            JsonNode funding = fundingEvents.get(0);
            assertEquals("held-funding", funding.path("event_id").asText());
            assertEquals(FUNDING_TIME.toEpochMilli(), funding.path("settlement_time").asLong());
            assertEquals("SETTLED", funding.path("status").asText());
            assertTrue(retainedSettlementKeys.add(accounts.get(index).path("candidate_id").asText()
                    + "/" + funding.path("event_id").asText()),
                    "each paired account retains this settlement at most once");
            retainedSettlementIds.add(funding.path("event_id").asText());
            assertTrue(funding.path("quantity_open").decimalValue().signum() > 0,
                    "the position must be live at the settlement timestamp");
            assertEquals(0, funding.path("quantity_open").decimalValue()
                    .compareTo(funding.path("charged_quantity_at_settlement").decimalValue()));

            BigDecimal quantity = funding.path("charged_quantity_at_settlement").decimalValue();
            BigDecimal mark = funding.path("mark_price").decimalValue();
            BigDecimal rate = funding.path("rate").decimalValue();
            BigDecimal sideSign = "LONG".equals(position.path("direction").asText())
                    ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            BigDecimal expectedAccountAmount = quantity.multiply(mark).multiply(rate).multiply(sideSign);
            BigDecimal actualAmount = funding.path("amount_usdt").decimalValue();
            assertEquals(0, expectedAccountAmount.compareTo(actualAmount), "signed funding follows open side and quantity");
            assertEquals(10.0, quantity.doubleValue(), 1e-8, "the prior-minute 1% capacity allows ten units");
            assertEquals(99.45, mark.doubleValue(), 1e-8);
            assertEquals(0.001, rate.doubleValue(), 1e-12);
            assertEquals("LONG".equals(position.path("direction").asText()) ? -0.9945 : 0.9945,
                    actualAmount.doubleValue(), 1e-8);
            assertEquals(0, actualAmount.compareTo(position.path("funding_pnl_usdt").decimalValue()));
            sawDebit |= actualAmount.signum() < 0;
            sawCredit |= actualAmount.signum() > 0;

            LiquidationV2StressPolicyV1.PositionSide side = "LONG".equals(position.path("direction").asText())
                    ? LiquidationV2StressPolicyV1.PositionSide.LONG : LiquidationV2StressPolicyV1.PositionSide.SHORT;
            JsonNode fundingTransform = policy.stressFunding(quantity.doubleValue(), mark.doubleValue(), rate.doubleValue(), side).toJson();
            assertEquals(0, fundingTransform.path("source_signed_cost_usdt").decimalValue().compareTo(actualAmount.negate()));
            if (actualAmount.signum() < 0) {
                assertTrue(fundingTransform.path("is_debit").asBoolean());
                assertEquals(0, fundingTransform.path("stressed_signed_cost_usdt").decimalValue()
                        .compareTo(fundingTransform.path("source_signed_cost_usdt").decimalValue().multiply(BigDecimal.valueOf(2))));
            } else {
                assertFalse(fundingTransform.path("is_debit").asBoolean());
                assertEquals(0, fundingTransform.path("stressed_signed_cost_usdt").decimalValue()
                        .compareTo(fundingTransform.path("source_signed_cost_usdt").decimalValue()),
                        "funding credits keep the frozen 1x multiplier");
            }

            assertEquals(FUNDING_EXIT_KNOWN_AT.toString(), opportunity.path("exit_time").asText());
            assertEquals(FUNDING_EXIT_BAR_START.toString(), opportunity.path("exit_fill_time_lower_bound").asText());
            assertEquals(FUNDING_EXIT_KNOWN_AT.toString(), opportunity.path("exit_fill_time_upper_bound").asText());
            assertEquals("KNOWN_AT_OR_COMPLETED_BAR_TIME; NOT_TICK_FILL_TIME",
                    opportunity.path("exit_time_semantics").asText());
            assertEquals("MODELED_STOP_OR_TARGET_FROM_COMPLETED_1M_OHLC", opportunity.path("exit_price_basis").asText());
            assertTrue(opportunity.path("exit_reason").asText().contains("STOP"));
            BigDecimal reconciled = position.path("realized_gross_pnl_usdt").decimalValue()
                    .add(position.path("funding_pnl_usdt").decimalValue())
                    .subtract(position.path("entry_costs_usdt").decimalValue())
                    .subtract(position.path("exit_costs_usdt").decimalValue());
            assertMoneyClose(reconciled, opportunity.path("net_pnl_usdt").decimalValue(),
                    "closed outcome reconciles gross, funding, and both cost ledgers");
            assertEquals("LONG".equals(position.path("direction").asText()) ? -3.687 : -8.704,
                    opportunity.path("net_pnl_usdt").asDouble(), 1e-8,
                    "funded stop path matches independent separate-fee/slippage and signed-carry arithmetic");
        }
        assertEquals(3, retainedSettlementKeys.size(),
                "the retained position ledgers record one application in each of the three paired accounts");
        assertEquals(Set.of("held-funding"), retainedSettlementIds,
                "the paired ledgers reference one unique physical settlement id");
        assertTrue(sawDebit, "paired fixture must include a long-side funding debit");
        assertTrue(sawCredit, "paired fixture must include a short-side funding credit");

        JsonNode stress = replay.path("stress_evaluation");
        JsonNode fundingStress = stress.path("scenario_results").get(1);
        assertEquals("funding_carry", fundingStress.path("scenario_id").asText());
        assertEquals(3, fundingStress.path("transform_count").asInt(),
                "the runner must apply the frozen funding transform once for each live paired account");
        assertTrue(fundingStress.path("runner_ledger_sha256").asText().matches("[0-9a-f]{64}"));
        assertFalse(fundingStress.path("runner_ledger_sha256").asText()
                .equals(replay.path("ledger_sha256").asText()),
                "live debit stress changes the freshly rerun account ledger");

        ObjectNode fundingStressRun = reopenScenarioRun(fundingStress, scenario.root());
        assertEquals(fundingStress.path("runner_result_sha256").asText(),
                JsonHashes.canonicalSha256(fundingStressRun));
        assertEquals(fundingStress.path("scenario_run_content_sha256").asText(),
                fundingStressRun.path("content_sha256").asText());
        assertEquals(fundingStress.path("runner_result_sha256").asText(),
                fundingStress.path("scenario_run_artifact").path("sha256").asText());
        assertEquals(fundingStress.path("runner_ledger_sha256").asText(), fundingStressRun.path("ledger_sha256").asText());
        for (JsonNode baseAccountRow : accounts) {
            String candidateId = baseAccountRow.path("candidate_id").asText();
            JsonNode basePosition = position(baseAccountRow.path("account"), ASSET);
            JsonNode stressedAccountRow = accountRow((ArrayNode) fundingStressRun.path("ledger").path("accounts"), candidateId);
            JsonNode stressedPosition = position(stressedAccountRow.path("account"), ASSET);
            JsonNode baseFunding = basePosition.path("funding_events").get(0);
            JsonNode stressedFunding = stressedPosition.path("funding_events").get(0);
            assertEquals(0, baseFunding.path("charged_quantity_at_settlement").decimalValue()
                    .compareTo(stressedFunding.path("charged_quantity_at_settlement").decimalValue()));
            assertEquals(baseFunding.path("settlement_time").asLong(), stressedFunding.path("settlement_time").asLong());
            assertEquals(0, baseFunding.path("mark_price").decimalValue()
                    .compareTo(stressedFunding.path("mark_price").decimalValue()));
            BigDecimal baseFundingPnl = baseFunding.path("amount_usdt").decimalValue();
            BigDecimal expectedStressedFundingPnl = baseFundingPnl.signum() < 0
                    ? baseFundingPnl.multiply(BigDecimal.valueOf(2)) : baseFundingPnl;
            assertEquals(0, expectedStressedFundingPnl.compareTo(stressedFunding.path("amount_usdt").decimalValue()),
                    "funding stress doubles the actual debit and leaves the credit unchanged");
            BigDecimal expectedStressedRate = baseFunding.path("rate").decimalValue()
                    .multiply(baseFundingPnl.signum() < 0 ? BigDecimal.valueOf(2) : BigDecimal.ONE);
            assertEquals(0, expectedStressedRate.compareTo(stressedFunding.path("rate").decimalValue()));
            assertEquals("LONG".equals(basePosition.path("direction").asText()) ? -1.989 : 0.9945,
                    stressedFunding.path("amount_usdt").asDouble(), 1e-8,
                    "reopened runner ledger carries twice the long debit and the unchanged short credit");
            assertEquals(0, expectedStressedFundingPnl.compareTo(stressedPosition.path("funding_pnl_usdt").decimalValue()));
            JsonNode baseOpportunity = opportunity(opportunities, candidateId);
            JsonNode stressedOpportunity = opportunity((ArrayNode) fundingStressRun.path("opportunities"), candidateId);
            BigDecimal expectedStressedNet = baseOpportunity.path("net_pnl_usdt").decimalValue()
                    .add(expectedStressedFundingPnl.subtract(baseFundingPnl));
            assertEquals(0, expectedStressedNet.compareTo(stressedOpportunity.path("net_pnl_usdt").decimalValue()),
                    "funding-carry scenario changes only the signed settlement amount for this fixture");
        }
    }

    @Test
    void outageLatchRetainsSecondQualifiedEventAndClosesAtFirstPermittedOpen() throws Exception {
        Scenario scenario = outageScenario(temporary.resolve("outage-lifecycle"));
        ObjectNode replay = LiquidationPortfolioReplayV1.run(replayOptions(scenario, "outage-lifecycle.json"));
        JsonNode outageResult = null;
        for (JsonNode result : replay.path("stress_evaluation").path("scenario_results")) {
            if ("venue_outage_blackout".equals(result.path("scenario_id").asText())) outageResult = result;
        }
        assertNotNull(outageResult);
        ObjectNode outageRun = reopenScenarioRun(outageResult, scenario.root());
        ArrayNode qualifiedEvents = (ArrayNode) outageRun.path("scenario_execution").path("qualified_daily_stress_events");
        assertEquals(2, qualifiedEvents.size(), "both adjacent daily stress buckets remain in the common event inventory");
        Set<String> qualifiedAvailability = new HashSet<>();
        for (JsonNode event : qualifiedEvents) qualifiedAvailability.add(event.path("available_at").asText());
        assertEquals(Set.of("2024-01-02T00:00:00Z", "2024-01-03T00:00:00Z"), qualifiedAvailability);

        ArrayNode outageIntervals = (ArrayNode) outageRun.path("scenario_execution").path("outage_intervals");
        assertEquals(2, outageIntervals.size(), "the frozen rule creates one interval per qualified daily event");
        int jan3OutageCount = 0;
        for (JsonNode interval : outageIntervals) {
            if ("2024-01-03T00:00:00Z".equals(interval.path("interval_start_inclusive").asText())) {
                jan3OutageCount++;
                assertEquals("2024-01-03T01:00:00Z", interval.path("interval_end_exclusive").asText());
            }
        }
        assertEquals(1, jan3OutageCount, "the second bucket creates exactly one blackout covering the live positions");

        ArrayNode exposures = (ArrayNode) outageRun.path("scenario_execution").path("outage_position_exposures");
        assertTrue(exposures.size() >= 3, "open paired positions retain exposure during the second event outage");
        assertTrue(exposures.findValuesAsText("interval_start_inclusive").contains("2024-01-03T00:00:00Z"));
        assertTrue(exposures.findValuesAsText("interval_end_exclusive").contains("2024-01-03T01:00:00Z"));

        ArrayNode opportunities = (ArrayNode) outageRun.path("opportunities");
        Set<String> firstPair = new HashSet<>();
        int firstEventCloses = 0;
        for (JsonNode opportunity : opportunities) {
            if (!"2024-01-02T09:01:00Z".equals(opportunity.path("first_fill_time").asText())) {
                if (opportunity.hasNonNull("first_fill_time")) {
                    Instant fill = Instant.parse(opportunity.path("first_fill_time").asText());
                    assertTrue(fill.isBefore(Instant.parse("2024-01-03T00:00:00Z"))
                                    || !fill.isBefore(Instant.parse("2024-01-03T01:00:00Z")),
                            "no stale entry may fill inside the blackout interval");
                }
                continue;
            }
            firstPair.add(opportunity.path("pair_id").asText());
            assertEquals("CLOSED_TRADE", opportunity.path("outcome_state").asText());
            assertEquals(OUTAGE_RESUME.toString(), opportunity.path("exit_time").asText());
            assertEquals(OUTAGE_TRIGGER_KNOWN_AT.toEpochMilli(), opportunity.path("outage_trigger_observed_time").asLong());
            assertEquals(OUTAGE_RESUME.toEpochMilli(), opportunity.path("outage_execution_resume_time").asLong());
            assertTrue(opportunity.path("outage_execution_delay_ms").asLong() > 0);
            assertEquals("MODELED_FIRST_PERMITTED_MINUTE_OPEN_AFTER_BLACKOUT", opportunity.path("exit_price_basis").asText());
            String triggerReason = opportunity.path("outage_trigger_reason").asText();
            assertEquals("STOP", triggerReason, "the latched economic cause is a stop");
            assertEquals("STOP_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT",
                    opportunity.path("outage_trigger_status").asText(),
                    "the stop cause is separately identified as latched during blackout");
            firstEventCloses++;
        }
        assertEquals(1, firstPair.size(), "the first qualifying event remains one paired decision");
        assertEquals(3, firstEventCloses, "routed and both paired control positions close from the same latched stop");

        ArrayNode accounts = (ArrayNode) outageRun.path("ledger").path("accounts");
        for (JsonNode accountRow : accounts) {
            JsonNode account = accountRow.path("account");
            JsonNode position = position(account, ASSET);
            ArrayNode fundingEvents = (ArrayNode) position.path("funding_events");
            Set<String> settlementIds = new HashSet<>();
            Set<String> appliedSettlementIds = new HashSet<>();
            int zeroPositionRows = 0;
            int targetedSettlementCount = 0;
            for (JsonNode event : fundingEvents) {
                settlementIds.add(event.path("event_id").asText());
                if (event.path("quantity_open").decimalValue().signum() == 0) {
                    zeroPositionRows++;
                    assertEquals("NO_POSITION_AT_SETTLEMENT", event.path("status").asText(),
                            "physical settlements without an open position remain explicit zero rows");
                    continue;
                }
                assertTrue(appliedSettlementIds.add(event.path("event_id").asText()),
                        "each physical settlement is applied only once while this position is open");
                if ("outage-funding".equals(event.path("event_id").asText())) {
                    targetedSettlementCount++;
                    assertEquals(Instant.parse("2024-01-03T00:00:00.007Z").toEpochMilli(),
                            event.path("settlement_time").asLong());
                    BigDecimal funding = event.path("amount_usdt").decimalValue();
                    assertEquals("LONG".equals(position.path("direction").asText()) ? -0.9945 : 0.9945,
                            funding.doubleValue(), 1e-8);
                } else {
                    assertEquals("outage-funding-" + Instant.parse("2024-01-02T16:00:00.007Z").toEpochMilli(),
                            event.path("event_id").asText(),
                            "the only other positive-quantity settlement precedes the blackout while the position is live");
                    assertEquals(0.0, event.path("amount_usdt").asDouble(), 1e-12,
                            "the zero-rate settlement is retained without changing PnL");
                }
            }
            assertEquals(Set.of("outage-funding-" + Instant.parse("2024-01-02T16:00:00.007Z").toEpochMilli(),
                    "outage-funding"), appliedSettlementIds);
            assertTrue(zeroPositionRows > 0, "out-of-position settlements stay auditable instead of disappearing");
            assertTrue(settlementIds.size() >= appliedSettlementIds.size());
            assertEquals(1, targetedSettlementCount, "the nonzero blackout settlement is applied exactly once");
        }
        assertEquals(outageResult.path("runner_result_sha256").asText(), JsonHashes.canonicalSha256(outageRun));
        assertEquals(outageResult.path("scenario_run_artifact").path("sha256").asText(),
                outageResult.path("runner_result_sha256").asText());
    }

    @Test
    void publicResumableReplayReconstructsOpenPositionAndValidatesCheckpointMirrors() throws Exception {
        Instant extendedExecutionEnd = Instant.parse("2024-01-05T00:00:00Z");
        Scenario scenario = outageScenario(temporary.resolve("resumable-outage"), extendedExecutionEnd);

        ObjectNode uninterruptedOptions = replayOptions(scenario, "uninterrupted.json", extendedExecutionEnd);
        ObjectNode uninterruptedReceipt = LiquidationPortfolioReplayV1.runResumable(uninterruptedOptions);
        assertEquals("COMPLETE", uninterruptedReceipt.path("status").asText());
        Path uninterruptedPath = scenario.root().resolve("uninterrupted.json");
        byte[] uninterruptedBytes = Files.readAllBytes(uninterruptedPath);
        ObjectNode uninterruptedReplay = (ObjectNode) JsonHashes.mapper().readTree(uninterruptedBytes);
        assertEquals(JsonHashes.sha256(uninterruptedBytes), JsonHashes.sha256(JsonHashes.canonicalBytes(uninterruptedReplay)));
        assertCheckpointFixtureContainsLiveBoundaryPosition(uninterruptedReplay, scenario.root());

        ObjectNode evaluatorOptions = JsonHashes.mapper().createObjectNode()
                .put("out", scenario.root().resolve("uninterrupted-evidence.json").toString());
        evaluatorOptions.set("freeze", scenario.freeze().deepCopy());
        evaluatorOptions.set("replay", uninterruptedReplay.deepCopy());
        ObjectNode positiveEvidence = LiquidationPortfolioReplayV1.evaluate(evaluatorOptions);
        assertEquals("liquidation-v2-replay-evidence/1", positiveEvidence.path("schema").asText());
        assertFalse(positiveEvidence.path("promotion_permitted").asBoolean(true));

        ObjectNode pauseOptions = replayOptions(scenario, "resumed.json", extendedExecutionEnd);
        pauseOptions.put("stop_after_boundary_exclusive", "2024-01-03T00:00:00Z");
        Path checkpointPath = scenario.root().resolve("durable-checkpoint.json");
        Path operationalPath = scenario.root().resolve("operational-receipt.json");
        pauseOptions.put("checkpoint_out", checkpointPath.toString()).put("operational_out", operationalPath.toString());
        ObjectNode pausedReceipt = LiquidationPortfolioReplayV1.runResumable(pauseOptions);
        assertEquals("RESUMABLE", pausedReceipt.path("status").asText());
        assertEquals(checkpointPath.toRealPath(), Path.of(pausedReceipt.path("operational_checkpoint_path").asText()).toRealPath(),
                "the receipt names the durable checkpoint target when operational output is separate");
        assertTrue(Files.exists(operationalPath.resolveSibling(operationalPath.getFileName() + ".receipt.json")));
        byte[] olderMirror = Files.readAllBytes(checkpointPath);
        JsonNode olderCheckpoint = JsonHashes.mapper().readTree(olderMirror).path("checkpoint");
        assertEquals("2024-01-03T00:00:00Z", olderCheckpoint.path("boundary_exclusive").asText());

        Files.delete(checkpointPath);
        ObjectNode missingMirrorOptions = pauseOptions.deepCopy();
        missingMirrorOptions.put("resume", true);
        ObjectNode missingMirrorReceipt = LiquidationPortfolioReplayV1.runResumable(missingMirrorOptions);
        assertEquals("RESUMABLE", missingMirrorReceipt.path("status").asText(),
                "a missing local mirror is rebuilt after hash-chain prefix reconstruction");
        assertTrue(Files.exists(checkpointPath));
        olderMirror = Files.readAllBytes(checkpointPath);
        assertEquals("2024-01-03T00:00:00Z",
                JsonHashes.mapper().readTree(olderMirror).path("checkpoint").path("boundary_exclusive").asText());

        ObjectNode advanceOptions = pauseOptions.deepCopy();
        advanceOptions.put("resume", true).put("stop_after_boundary_exclusive", "2024-01-04T00:00:00Z");
        ObjectNode advancedReceipt = LiquidationPortfolioReplayV1.runResumable(advanceOptions);
        assertEquals("RESUMABLE", advancedReceipt.path("status").asText());
        JsonNode latestCheckpoint = JsonHashes.mapper().readTree(Files.readAllBytes(checkpointPath)).path("checkpoint");
        assertEquals("2024-01-04T00:00:00Z", latestCheckpoint.path("boundary_exclusive").asText());

        ObjectNode forgedFutureMirror = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(checkpointPath));
        ((ObjectNode) forgedFutureMirror.path("checkpoint")).put("boundary_exclusive", "2024-01-05T00:00:00Z");
        forgedFutureMirror.put("content_sha256", JsonHashes.ownHash(forgedFutureMirror));
        Files.write(checkpointPath, JsonHashes.canonicalBytes(forgedFutureMirror));
        ObjectNode completeOptions = pauseOptions.deepCopy();
        completeOptions.put("resume", true);
        completeOptions.remove("stop_after_boundary_exclusive");
        IllegalArgumentException futureRejected = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(completeOptions));
        assertTrue(futureRejected.getMessage().contains("operational checkpoint file does not match"));

        // The local mirror is only an operational view. An older mirror already committed in the
        // hash chain remains valid; resume reconstructs and verifies the chain's later prefix.
        Files.write(checkpointPath, olderMirror);
        ObjectNode resumedReceipt = LiquidationPortfolioReplayV1.runResumable(completeOptions);
        assertEquals("COMPLETE", resumedReceipt.path("status").asText());
        byte[] resumedBytes = Files.readAllBytes(scenario.root().resolve("resumed.json"));
        assertArrayEquals(uninterruptedBytes, resumedBytes,
                "restart reconstructs the identical full canonical result; economic/account fields are compared verbatim");
        ObjectNode resumedReplay = (ObjectNode) JsonHashes.mapper().readTree(resumedBytes);
        assertEquals(JsonHashes.canonicalSha256(uninterruptedReplay), JsonHashes.canonicalSha256(resumedReplay));
        assertCheckpointFixtureContainsLiveBoundaryPosition(resumedReplay, scenario.root());
    }

    private static void assertCheckpointFixtureContainsLiveBoundaryPosition(ObjectNode replay, Path root) throws Exception {
        JsonNode outageResult = null;
        for (JsonNode result : replay.path("stress_evaluation").path("scenario_results")) {
            if ("venue_outage_blackout".equals(result.path("scenario_id").asText())) outageResult = result;
        }
        assertNotNull(outageResult);
        ObjectNode outageRun = reopenScenarioRun(outageResult, root);
        JsonNode position = position(outageRun.path("ledger").path("accounts").get(0).path("account"), ASSET);
        assertEquals(Instant.parse("2024-01-02T09:01:00Z").toEpochMilli(), position.path("first_fill_time").asLong(),
                "the fixture position is already open at the Jan 3 UTC checkpoint boundary");
        assertEquals(Instant.parse("2024-01-03T01:00:00Z").toEpochMilli(), position.path("exits").get(0).path("time").asLong(),
                "the position remains exposed after the checkpoint until the outage latch permits an open");
        Instant checkpointBoundary = Instant.parse("2024-01-03T00:00:00Z");
        assertTrue(Instant.ofEpochMilli(position.path("first_fill_time").asLong()).isBefore(checkpointBoundary)
                        && checkpointBoundary.isBefore(Instant.ofEpochMilli(position.path("exits").get(0).path("time").asLong())),
                "the Jan 3 checkpoint boundary falls inside the filled position episode");
        assertTrue(outageRun.path("scenario_execution").path("outage_position_exposures").toString()
                .contains("2024-01-03T00:00:00Z"));
    }

    private static void assertMoneyClose(BigDecimal expected, BigDecimal actual, String message) {
        BigDecimal delta = expected.subtract(actual).abs();
        assertTrue(delta.compareTo(new BigDecimal("0.00000001")) <= 0,
                message + "; delta=" + delta + ", expected=" + expected + ", actual=" + actual);
    }

    private static ObjectNode reopenScenarioRun(JsonNode scenarioResult, Path physicalRoot) throws Exception {
        JsonNode artifact = scenarioResult.path("scenario_run_artifact");
        assertEquals("liquidation-v2-replay-result/1", artifact.path("schema").asText());
        Path root = physicalRoot.toAbsolutePath().normalize();
        Path path = root.resolve(artifact.path("relative_path").asText()).normalize();
        assertTrue(path.startsWith(root), "scenario artifact must remain beneath the frozen physical root");
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(artifact.path("sha256").asText(), JsonHashes.sha256(bytes));
        JsonNode parsed = JsonHashes.mapper().readTree(bytes);
        assertTrue(parsed instanceof ObjectNode);
        ObjectNode run = (ObjectNode) parsed;
        assertEquals(run.path("content_sha256").asText(), JsonHashes.ownHash(run));
        return run;
    }

    private static JsonNode accountRow(ArrayNode accounts, String candidateId) {
        for (JsonNode account : accounts) {
            if (candidateId.equals(account.path("candidate_id").asText())) return account;
        }
        throw new AssertionError("scenario ledger has no account for " + candidateId);
    }

    private static JsonNode position(JsonNode account, String asset) {
        for (JsonNode position : account.path("positions")) {
            if (asset.equals(position.path("asset").asText())) return position;
        }
        throw new AssertionError("account snapshot has no position for " + asset);
    }

    private static JsonNode opportunity(ArrayNode opportunities, String candidateId) {
        for (JsonNode opportunity : opportunities) {
            if (candidateId.equals(opportunity.path("candidate_id").asText())) return opportunity;
        }
        throw new AssertionError("replay has no opportunity for " + candidateId);
    }

    @Test
    void replayRejectsRehashedFreezeWithAlteredLoadedExecutorBytecodeIdentity() throws Exception {
        Scenario scenario = scenario(temporary.resolve("forged-freeze"), 99.45, false);
        ObjectNode forgedFreeze = scenario.freeze().deepCopy();
        ObjectNode executor = (ObjectNode) forgedFreeze.path("executor_identity");
        ArrayNode loadedResources = (ArrayNode) executor.path("loaded_class_resources");
        ObjectNode engineClass = null;
        for (JsonNode resource : loadedResources) {
            if ("com.tradinganalytics.research.v5.LiquidationPortfolioReplayV1$Engine"
                    .equals(resource.path("class_name").asText())) {
                engineClass = (ObjectNode) resource;
                break;
            }
        }
        assertNotNull(engineClass, "executor identity must bind the loaded replay Engine classfile");
        engineClass.put("class_bytecode_sha256", "0".repeat(64));
        executor.put("content_sha256", JsonHashes.ownHash(executor));
        forgedFreeze.put("content_sha256", JsonHashes.ownHash(forgedFreeze));

        ObjectNode options = replayOptions(scenario, "rejected-forged-freeze.json");
        options.set("freeze", forgedFreeze);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.run(options));
        assertTrue(rejected.getMessage().contains("executor source files changed after freeze"));
        assertFalse(Files.exists(scenario.root().resolve("rejected-forged-freeze.json")));
    }

    @Test
    void syntheticSmokeRejectsDecisionWindowPastFrozenDecisionEndBeforeReplay() throws Exception {
        Scenario scenario = scenario(temporary.resolve("past-decision-end"), 99.45, false);
        ObjectNode options = replayOptions(scenario, "past-decision-end.json")
                .put("feature_warmup_start", "2026-04-12T00:00:00Z")
                .put("replay_start", "2026-07-13T00:00:00Z")
                .put("replay_end_exclusive", "2026-07-17T00:00:00Z");

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.run(options));
        assertTrue(rejected.getMessage().contains("within the frozen decision/execution scope"));
        assertFalse(Files.exists(scenario.root().resolve("past-decision-end.json")));
    }

    private Scenario scenario(Path root, double ordinaryMinuteOpen, boolean omitExactFillOpen) throws Exception {
        return scenario(root, ordinaryMinuteOpen, omitExactFillOpen, false);
    }

    private Scenario scenario(Path root, double ordinaryMinuteOpen, boolean omitExactFillOpen, boolean gapAtStopOpen) throws Exception {
        return scenario(root, ordinaryMinuteOpen, omitExactFillOpen, gapAtStopOpen, STOP_BAR_START,
                List.of(fundingRow(Instant.ofEpochMilli(REPLAY_START.toEpochMilli() + 7), "synthetic-funding", 0.0)));
    }

    private Scenario scenario(Path root, double ordinaryMinuteOpen, boolean omitExactFillOpen, boolean gapAtStopOpen,
            Instant stopBarStart, List<ObjectNode> fundingRows) throws Exception {
        return scenario(root, ordinaryMinuteOpen, omitExactFillOpen, gapAtStopOpen, stopBarStart,
                fundingRows, featureRows(), null);
    }

    private Scenario outageScenario(Path root) throws Exception {
        return outageScenario(root, REPLAY_END);
    }

    private Scenario outageScenario(Path root, Instant replayEnd) throws Exception {
        return scenario(root, 99.45, false, false, STOP_BAR_START,
                outageFundingRows(replayEnd), outageFeatureRows(replayEnd), OUTAGE_TRIGGER_BAR, replayEnd);
    }

    private Scenario scenario(Path root, double ordinaryMinuteOpen, boolean omitExactFillOpen, boolean gapAtStopOpen,
            Instant stopBarStart, List<ObjectNode> fundingRows, List<ObjectNode> featureRows,
            Instant additionalStopBarStart) throws Exception {
        return scenario(root, ordinaryMinuteOpen, omitExactFillOpen, gapAtStopOpen, stopBarStart,
                fundingRows, featureRows, additionalStopBarStart, REPLAY_END);
    }

    private Scenario scenario(Path root, double ordinaryMinuteOpen, boolean omitExactFillOpen, boolean gapAtStopOpen,
            Instant stopBarStart, List<ObjectNode> fundingRows, List<ObjectNode> featureRows,
            Instant additionalStopBarStart, Instant replayEnd) throws Exception {
        Files.createDirectories(root);
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();

        putInput(root, inputs, "feature", featureRows);
        putInput(root, inputs, "label", List.of(JsonHashes.mapper().createObjectNode().put("asset", ASSET)
                .put("episode_id", "opaque-fixture-outcome").put("decision_time", FILL_TIME.toEpochMilli())
                .put("label", "UNINSPECTED")));
        putInput(root, inputs, "execution", minuteRows(ordinaryMinuteOpen, omitExactFillOpen, true,
                gapAtStopOpen, stopBarStart, additionalStopBarStart, replayEnd));
        putInput(root, inputs, "mark", minuteRows(ordinaryMinuteOpen, false, false,
                false, STOP_BAR_START, null, replayEnd));
        putInput(root, inputs, "funding", fundingRows);
        putInput(root, inputs, "metadata", metadataRows(replayEnd));

        ObjectNode buildOptions = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        buildOptions.set("profile", profile);
        buildOptions.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(buildOptions);

        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        physicalOptions.set("profile", profile.deepCopy());
        physicalOptions.set("manifest", manifest);
        ObjectNode freezeOptions = JsonHashes.mapper().createObjectNode()
                .put("project_root", projectRoot().toString());
        freezeOptions.set("profile", profile.deepCopy());
        freezeOptions.set("physical_options", physicalOptions);
        ObjectNode freeze = LiquidationPortfolioReplayV1.freeze(freezeOptions);
        return new Scenario(root, freeze);
    }

    private ObjectNode replayOptions(Scenario scenario, String outputName) {
        return replayOptions(scenario, outputName, REPLAY_END);
    }

    private ObjectNode replayOptions(Scenario scenario, String outputName, Instant replayEnd) {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("synthetic_smoke", true)
                .put("feature_warmup_start", FEATURE_START.toString())
                .put("replay_start", REPLAY_START.toString())
                .put("replay_end_exclusive", replayEnd.toString())
                .put("out", scenario.root().resolve(outputName).toString());
        options.set("freeze", scenario.freeze().deepCopy());
        return options;
    }

    private static List<ObjectNode> featureRows() {
        return featureRows(REPLAY_END);
    }

    private static List<ObjectNode> featureRows(Instant replayEnd) {
        List<ObjectNode> rows = new ArrayList<>();
        for (int lag = LiquidationStructureRouterV1.DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
            LocalDate date = STRESS_DAY.minusDays(lag);
            long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            rows.add(dailyRow("LONG", start, 1));
            rows.add(dailyRow("SHORT", start, 1));
        }
        long stressStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        rows.add(dailyRow("LONG", stressStart, 2));
        rows.add(dailyRow("SHORT", stressStart, 2));
        long macroClose = Instant.parse("2024-01-02T21:00:00Z").toEpochMilli();
        rows.add(JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                .put("series_id", "sp500_close").put("timeframe", "1d").put("event_time", macroClose)
                .put("availability_time", Instant.parse("2024-01-03T21:00:00Z").toEpochMilli()).put("close", 4_700));

        Instant firstH4 = STRESS_START.minusSeconds(4L * 3_600L * 180);
        Instant h4End = replayEnd;
        for (Instant start = firstH4; start.isBefore(h4End); start = start.plusSeconds(4L * 3_600L)) {
            double open = 100, high = 100.5, low = 99.5, close = 100;
            if (start.equals(STRESS_START)) { open = 100; high = 100.2; low = 96.7; close = 97; }
            if (start.equals(MODEL_AVAILABLE.minusSeconds(4L * 3_600L))) { open = 98; high = 98.5; low = 96.5; close = 97; }
            if (start.equals(MODEL_AVAILABLE)) { open = 97; high = 97.5; low = 96; close = 96.8; }
            if (start.equals(MODEL_AVAILABLE.plusSeconds(4L * 3_600L))) { open = 96.8; high = 97.2; low = 95.8; close = 96.5; }
            rows.add(priceRow("4h", start.toEpochMilli(), start.plusSeconds(4L * 3_600L).toEpochMilli(),
                    open, high, low, close));
        }

        Instant oiStart = STRESS_START.minusSeconds(5L * 60L);
        Instant oiEnd = STRESS_START.plusSeconds(4L * 3_600L - 5L * 60L);
        rows.add(oiRow(oiStart.toEpochMilli(), oiStart.plusSeconds(1).toEpochMilli(), 100));
        rows.add(oiRow(oiEnd.toEpochMilli(), oiEnd.plusSeconds(1).toEpochMilli(), 90));

        addHour(rows, MODEL_AVAILABLE.plusSeconds(2L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(3L * 3_600L), 100, 100.2, 99.8, 100);
        rows.add(priceRow("1h", MODEL_AVAILABLE.plusSeconds(4L * 3_600L).toEpochMilli(),
                MODEL_AVAILABLE.plusSeconds(5L * 3_600L).toEpochMilli(), 99.7, 99.8, 99.4, 99.45));
        addHour(rows, MODEL_AVAILABLE.plusSeconds(5L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(6L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(7L * 3_600L), 100, 100.2, 99.8, 100);
        rows.add(priceRow("1h", MODEL_AVAILABLE.plusSeconds(8L * 3_600L).toEpochMilli(),
                MODEL_AVAILABLE.plusSeconds(9L * 3_600L).toEpochMilli(), 99.7, 99.8, 99.4, 99.45));
        for (Instant start = MODEL_AVAILABLE.plusSeconds(9L * 3_600L); start.isBefore(replayEnd);
                start = start.plusSeconds(3_600L)) {
            rows.add(priceRow("1h", start.toEpochMilli(), start.plusSeconds(3_600L).toEpochMilli(),
                    99.45, 99.50, 99.40, 99.45));
        }
        return rows;
    }

    private static List<ObjectNode> outageFeatureRows() {
        return outageFeatureRows(REPLAY_END);
    }

    private static List<ObjectNode> outageFeatureRows(Instant replayEnd) {
        List<ObjectNode> rows = featureRows(replayEnd);
        Instant firstEventDay = Instant.parse("2023-12-31T00:00:00Z");
        Instant secondEventDay = Instant.parse("2024-01-01T00:00:00Z");
        long additionalWarmupDay = Instant.parse("2023-10-02T00:00:00Z").toEpochMilli();
        rows.add(dailyRow("LONG", additionalWarmupDay, 1));
        rows.add(dailyRow("SHORT", additionalWarmupDay, 1));
        for (ObjectNode row : rows) {
            if ("daily_liquidation_usd".equals(row.path("series_id").asText())) {
                if (firstEventDay.toEpochMilli() == row.path("event_time").asLong()) row.put("value", 2);
                if (secondEventDay.toEpochMilli() == row.path("event_time").asLong()) row.put("value", 3);
            }
        }
        for (LocalDate date = LocalDate.of(2024, 1, 2);
                date.atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(replayEnd); date = date.plusDays(1)) {
            long eventTime = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            rows.add(dailyRow("LONG", eventTime, 0));
            rows.add(dailyRow("SHORT", eventTime, 0));
        }
        // Keep the first event outside the second day's 24-hour pre-event range so both
        // independently qualify while the first position is already open.
        Instant firstStressStart = Instant.parse("2023-12-31T00:00:00Z");
        setPrice(rows, firstStressStart, 100, 100.2, 96.7, 97);
        rows.add(oiRow(firstStressStart.minusSeconds(5L * 60L).toEpochMilli(),
                firstStressStart.minusSeconds(5L * 60L).plusSeconds(1).toEpochMilli(), 100));
        Instant firstOiEnd = firstStressStart.plusSeconds(4L * 3_600L - 5L * 60L);
        rows.add(oiRow(firstOiEnd.toEpochMilli(), firstOiEnd.plusSeconds(1).toEpochMilli(), 90));

        Instant firstAvailable = Instant.parse("2024-01-02T00:00:00Z");
        setPrice(rows, firstAvailable.minusSeconds(4L * 3_600L), 98, 98.5, 96.5, 97);
        setPrice(rows, firstAvailable, 97, 97.5, 96, 96.8);
        setPrice(rows, firstAvailable.plusSeconds(4L * 3_600L), 96.8, 97.2, 95.8, 96.5);
        addHour(rows, firstAvailable.plusSeconds(2L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, firstAvailable.plusSeconds(3L * 3_600L), 100, 100.2, 99.8, 100);
        rows.add(priceRow("1h", firstAvailable.plusSeconds(4L * 3_600L).toEpochMilli(),
                firstAvailable.plusSeconds(5L * 3_600L).toEpochMilli(), 99.7, 99.8, 99.4, 99.45));
        addHour(rows, firstAvailable.plusSeconds(5L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, firstAvailable.plusSeconds(6L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, firstAvailable.plusSeconds(7L * 3_600L), 100, 100.2, 99.8, 100);
        rows.add(priceRow("1h", firstAvailable.plusSeconds(8L * 3_600L).toEpochMilli(),
                firstAvailable.plusSeconds(9L * 3_600L).toEpochMilli(), 99.7, 99.8, 99.4, 99.45));

        rows.add(JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                .put("series_id", "sp500_close").put("timeframe", "1d")
                .put("event_time", Instant.parse("2023-12-28T21:00:00Z").toEpochMilli())
                .put("availability_time", Instant.parse("2023-12-29T21:00:00Z").toEpochMilli()).put("close", 4_780));
        return rows;
    }

    private static void setPrice(List<ObjectNode> rows, Instant eventTime,
            double open, double high, double low, double close) {
        for (ObjectNode row : rows) if ("price_ohlc".equals(row.path("series_id").asText())
                && "4h".equals(row.path("timeframe").asText())
                && row.path("event_time").asLong() == eventTime.toEpochMilli()) {
            row.put("open", open).put("high", high).put("low", low).put("close", close);
            return;
        }
        throw new AssertionError("fixture is missing the expected 4h bar at " + eventTime);
    }

    private static List<ObjectNode> minuteRows(double ordinaryOpen, boolean omitExactFillOpen, boolean execution) {
        return minuteRows(ordinaryOpen, omitExactFillOpen, execution, false, REPLAY_END);
    }

    private static List<ObjectNode> minuteRows(double ordinaryOpen, boolean omitExactFillOpen, boolean execution, boolean gapAtStopOpen) {
        return minuteRows(ordinaryOpen, omitExactFillOpen, execution, gapAtStopOpen, STOP_BAR_START, REPLAY_END);
    }

    private static List<ObjectNode> minuteRows(double ordinaryOpen, boolean omitExactFillOpen, boolean execution,
            boolean gapAtStopOpen, Instant stopBarStart) {
        return minuteRows(ordinaryOpen, omitExactFillOpen, execution, gapAtStopOpen, stopBarStart, null, REPLAY_END);
    }

    private static List<ObjectNode> minuteRows(double ordinaryOpen, boolean omitExactFillOpen, boolean execution,
            boolean gapAtStopOpen, Instant stopBarStart, Instant additionalStopBarStart) {
        return minuteRows(ordinaryOpen, omitExactFillOpen, execution, gapAtStopOpen, stopBarStart,
                additionalStopBarStart, REPLAY_END);
    }

    private static List<ObjectNode> minuteRows(double ordinaryOpen, boolean omitExactFillOpen, boolean execution,
            boolean gapAtStopOpen, Instant stopBarStart, Instant additionalStopBarStart, Instant replayEnd) {
        List<ObjectNode> rows = new ArrayList<>();
        for (Instant time = REPLAY_START; time.isBefore(replayEnd); time = time.plusSeconds(60)) {
            if (execution && omitExactFillOpen && time.equals(FILL_TIME)) continue;
            double open = execution && gapAtStopOpen && time.equals(stopBarStart) ? 97 : ordinaryOpen;
            double high = open + 0.05, low = open - 0.05, close = open;
            if (execution && (time.equals(stopBarStart) || time.equals(additionalStopBarStart))) {
                high = Math.max(102, open);
                low = Math.min(97, open);
            }
            if (execution) {
                rows.add(JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("open_time", time.toEpochMilli())
                        .put("open", open).put("high", high).put("low", low).put("close", close).put("base_volume", 1_000));
            } else {
                rows.add(JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("timestamp", time.toEpochMilli())
                        .put("open", open).put("high", high).put("low", low).put("close", close));
            }
        }
        return rows;
    }

    private static ObjectNode fundingRow(Instant settlementTime, String eventId, double rate) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET)
                .put("settlement_time", settlementTime.toEpochMilli()).put("event_id", eventId)
                .put("funding_rate", rate).put("mark_price", 99.45).put("funding_interval_hours", 8);
    }

    private static List<ObjectNode> outageFundingRows(Instant replayEnd) {
        List<ObjectNode> rows = new ArrayList<>();
        Instant target = Instant.parse("2024-01-03T00:00:00.007Z");
        for (Instant slot = REPLAY_START; slot.isBefore(replayEnd); slot = slot.plusSeconds(8L * 3_600L)) {
            Instant settlement = slot.plusMillis(7);
            boolean targetSettlement = settlement.equals(target);
            rows.add(fundingRow(settlement,
                    targetSettlement ? "outage-funding" : "outage-funding-" + settlement.toEpochMilli(),
                    targetSettlement ? 0.001 : 0.0));
        }
        return rows;
    }

    private static List<ObjectNode> metadataRows() {
        return metadataRows(REPLAY_END);
    }

    private static List<ObjectNode> metadataRows(Instant replayEnd) {
        List<ObjectNode> rows = new ArrayList<>();
        long start = FEATURE_START.toEpochMilli(), end = replayEnd.toEpochMilli();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            rows.add(JsonHashes.mapper().createObjectNode().put("asset", asset).put("tier_index", 1)
                    .put("effective_from", start).put("effective_until", end).put("lot_size", 0.001)
                    .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                    .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                    .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0)
                    .put("terminal_tier", true));
        }
        return rows;
    }

    private static ObjectNode dailyRow(String side, long eventTime, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("symbol", COINALYZE)
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", side)
                .put("event_time", eventTime).put("availability_time", eventTime + 172_800_000L).put("value", value);
    }

    private static ObjectNode priceRow(String timeframe, long eventTime, long availableTime,
            double open, double high, double low, double close) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("symbol", BINANCE)
                .put("series_id", "price_ohlc").put("timeframe", timeframe)
                .put("event_time", eventTime).put("availability_time", availableTime)
                .put("open", open).put("high", high).put("low", low).put("close", close)
                .put("base_volume", 100);
    }

    private static ObjectNode oiRow(long eventTime, long availableTime, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("symbol", BINANCE)
                .put("series_id", "open_interest_base").put("timeframe", "5m")
                .put("event_time", eventTime).put("availability_time", availableTime).put("value", value);
    }

    private static void addHour(List<ObjectNode> rows, Instant start, double open, double high, double low, double close) {
        rows.add(priceRow("1h", start.toEpochMilli(), start.plusSeconds(3_600L).toEpochMilli(), open, high, low, close));
    }

    private static void putInput(Path root, ObjectNode inputs, String role, List<ObjectNode> rows) throws Exception {
        byte[] bytes = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows);
        String relative = "input/" + role + ".jsonl";
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        inputs.putObject(role).put("path", relative).put("sha256", JsonHashes.sha256(bytes));
    }

    private static Path projectRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) return cursor;
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("cannot locate the repository precommit for replay freeze");
    }

    private record Scenario(Path root, ObjectNode freeze) {}
}
