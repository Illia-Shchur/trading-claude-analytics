package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Public, synthetic-only integration for frozen staged anchors and their sixty-day account lifecycle. */
class LiquidationStagedPortfolioReplayV1Test {
    private static final String ASSET = "BTC";
    private static final String BINANCE = "BTCUSDT";
    private static final String COINALYZE = "BTCUSDT_PERP.A";
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant FEATURE_START = Instant.parse("2023-10-01T00:00:00Z");
    private static final Instant DECISION_START = Instant.parse("2024-01-03T00:00:00Z");
    private static final Instant DECISION_END = Instant.parse("2024-01-04T00:00:00Z");
    private static final Instant FULL_EXECUTION_END = Instant.parse("2024-03-05T00:00:00Z");
    private static final Instant STRESS_START = Instant.parse("2024-01-01T08:00:00Z");
    private static final Instant MODEL_AVAILABLE = Instant.parse("2024-01-03T00:00:00Z");
    private static final Instant CORE_FILL_TIME = Instant.parse("2024-01-03T09:01:00Z");
    private static final double FLAT_PRICE = 99.45;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    @Test
    void publicStagedRunnerKeepsAnchorsComparesMacroCausallyAndClosesFromFirstFillSixtyDayClock() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("staged-sixty-day"));
        ObjectNode freeze = buildFreeze(root);
        ObjectNode request = replayRequest();

        ObjectNode corePass = LiquidationPortfolioReplayV1.runAndEvaluate(runAndEvaluateOptions(
                freeze, request, "core-replay.json", "core-evidence.json"));
        assertEquals("liquidation-v2-run-evaluate-receipt/1", corePass.path("schema").asText());
        assertTrue(corePass.path("single_runner_pass").asBoolean());
        assertFalse(corePass.path("authoritative").asBoolean(true));
        ObjectNode coreReplay = readObject(root.resolve("core-replay.json"));
        ObjectNode coreEvidence = readObject(root.resolve("core-evidence.json"));
        assertEquals(coreEvidence.path("status").asText(), corePass.path("status").asText());
        assertEquals(coreReplay.path("content_sha256").asText(), corePass.path("replay_sha256").asText());
        assertEquals(coreEvidence.path("content_sha256").asText(), corePass.path("evidence_sha256").asText());
        assertEquals(coreReplay.path("content_sha256").asText(), JsonHashes.ownHash(coreReplay));
        assertEquals(coreEvidence.path("content_sha256").asText(), JsonHashes.ownHash(coreEvidence));
        ObjectNode inventory = (ObjectNode) freeze.path("candidate_inventory").deepCopy();
        ObjectNode noMacroPlan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                coreReplay, coreEvidence, inventory);
        assertFrozenPlan(noMacroPlan, "direct no-macro freeze");
        assertEquals(1, noMacroPlan.path("decision_anchor_count").asInt());
        ObjectNode adapterPlan = invokeNoMacroPlanAdapter(root, coreReplay, coreEvidence, inventory);
        assertFrozenPlan(adapterPlan, "no-macro adapter output");
        assertEquals(noMacroPlan.path("content_sha256").asText(), adapterPlan.path("content_sha256").asText());
        assertEquals(JsonHashes.canonicalSha256(noMacroPlan), JsonHashes.canonicalSha256(adapterPlan),
                "the public CLI adapter dispatch produces the same frozen no-macro plan as the direct API");

        ObjectNode noMacroOptions = stagedRunOptions(
                freeze, noMacroPlan, coreReplay, coreEvidence, inventory, null, null, null,
                request, "staged-no-macro.json");
        assertFrozenPlan((ObjectNode) noMacroOptions.path("staged_plan"), "no-macro runner option");
        ObjectNode noMacroReplay = LiquidationPortfolioReplayV1.runStaged(noMacroOptions);
        // Check the core staged mechanics before the additional evidence recomputations below.
        // This keeps a lifecycle defect attributable to the first public staged run.
        JsonNode earlyNoMacroOutcome = onlyOutcome(noMacroReplay);
        JsonNode earlyNoMacroPosition = position(noMacroReplay);
        assertEquals("CLOSED_TRADE", earlyNoMacroOutcome.path("outcome_state").asText());
        assertEquals("SHORT", earlyNoMacroOutcome.path("direction").asText(),
                "the Jan 1 down-shock and two in-range H1 confirmations freeze a short continuation anchor");
        assertEquals(List.of(1, 2, 3), fillStages(earlyNoMacroPosition));
        assertFullPositionRisk(earlyNoMacroOutcome);
        assertEquals(CORE_FILL_TIME.toString(), earlyNoMacroOutcome.path("first_fill_time").asText());
        assertEquals(CORE_FILL_TIME.plusSeconds(60L * 86_400L).toEpochMilli(),
                earlyNoMacroOutcome.path("deadline_order_time").asLong());
        Instant earlyDeadlineFill = CORE_FILL_TIME.plusSeconds(60L * 86_400L + 60L);
        assertEquals(earlyDeadlineFill.toString(), earlyNoMacroOutcome.path("exit_time").asText());
        assertEquals(60_000L, earlyNoMacroOutcome.path("deadline_execution_overrun_ms").asLong());

        ObjectNode syntheticEvidence = LiquidationPortfolioReplayV1.evaluateStaged(evaluationStagedOptions(
                noMacroOptions, noMacroReplay, "staged-no-macro-evidence.json"));
        LiquidationV2StagedStatisticsV1.Evaluation noMacroEvaluation = LiquidationV2StagedStatisticsV1.recompute(
                freeze, noMacroPlan, coreReplay, noMacroReplay, null, null, null);
        ObjectNode rebuiltSyntheticEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                noMacroPlan, noMacroReplay, noMacroEvaluation);
        String evaluatedEvidenceHash = JsonHashes.canonicalSha256(syntheticEvidence);
        String rebuiltEvidenceHash = JsonHashes.canonicalSha256(rebuiltSyntheticEvidence);
        assertEquals(evaluatedEvidenceHash, rebuiltEvidenceHash,
                () -> "the public staged evaluator evidence reopens from the same complete statistics result; first differing non-hash pointer: "
                        + firstDifferentPointer(syntheticEvidence, rebuiltSyntheticEvidence));
        ObjectNode expectedMacroPlan = LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                noMacroPlan, noMacroReplay, syntheticEvidence, noMacroEvaluation);
        ObjectNode macroPlan = invokeMacroPlanAdapter(root, freeze, noMacroPlan, coreReplay, coreEvidence,
                noMacroReplay, syntheticEvidence);
        assertFrozenPlan(macroPlan, "macro adapter output");
        assertEquals(JsonHashes.canonicalSha256(expectedMacroPlan), JsonHashes.canonicalSha256(macroPlan),
                "the budgeted public adapter must freeze the same plan as the direct evaluator");
        ObjectNode persistedMacroPlan = readObject(root.resolve("adapter-macro-plan.json"));
        assertTrue(java.util.Arrays.equals(JsonHashes.canonicalBytes(macroPlan),
                        Files.readAllBytes(root.resolve("adapter-macro-plan.json"))),
                "the immutable --out bytes reopen as the exact returned macro plan");
        assertEquals(JsonHashes.canonicalSha256(macroPlan), JsonHashes.canonicalSha256(persistedMacroPlan));
        ObjectNode macroOptions = stagedRunOptions(
                freeze, macroPlan, coreReplay, coreEvidence, inventory,
                noMacroPlan, noMacroReplay, syntheticEvidence, request, "staged-macro.json");
        assertFrozenPlan((ObjectNode) macroOptions.path("staged_plan"), "macro runner option");
        ObjectNode macroReplay = LiquidationPortfolioReplayV1.runStaged(macroOptions);

        assertEquals(noMacroPlan.path("anchor_inventory_sha256").asText(),
                macroPlan.path("anchor_inventory_sha256").asText());
        assertEquals(JsonHashes.canonicalSha256(noMacroPlan.path("decision_anchors")),
                JsonHashes.canonicalSha256(macroPlan.path("decision_anchors")));
        assertEquals(noMacroPlan.path("decision_anchors").path(0).path("pair_id").asText(),
                macroPlan.path("decision_anchors").path(0).path("pair_id").asText());
        assertEquals(LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                noMacroReplay.path("mode_id").asText());
        assertEquals(LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_MACRO,
                macroReplay.path("mode_id").asText());
        assertEquals("SYNTHETIC_DEVELOPMENT_ONLY", noMacroReplay.path("source_mode").asText());
        assertFalse(syntheticEvidence.path("survival_claim").asBoolean(true));
        assertFalse(syntheticEvidence.path("promotion_permitted").asBoolean(true));
        assertFalse(macroPlan.path("survival_claim").asBoolean(true));
        assertFalse(macroPlan.path("promotion_permitted").asBoolean(true));

        JsonNode noMacroOutcome = onlyOutcome(noMacroReplay);
        JsonNode macroOutcome = onlyOutcome(macroReplay);
        assertEquals(noMacroOutcome.path("pair_id").asText(), macroOutcome.path("pair_id").asText());
        assertEquals("CLOSED_TRADE", noMacroOutcome.path("outcome_state").asText());
        assertEquals("CLOSED_TRADE", macroOutcome.path("outcome_state").asText());
        assertEquals("ROUTED_REVERSAL_CONTINUATION", noMacroOutcome.path("variant").asText());
        assertEquals(noMacroPlan.path("candidate_id").asText(), noMacroOutcome.path("candidate_id").asText());
        assertEquals(macroPlan.path("candidate_id").asText(), macroOutcome.path("candidate_id").asText());
        assertEquals(1, noMacroReplay.path("opportunities").size(), "one outcome is retained per frozen anchor");
        assertEquals(1, macroReplay.path("opportunities").size(), "macro policy cannot change the anchor denominator");
        assertNotNull(noMacroOutcome.path("position_episode_id").textValue());
        assertNotNull(macroOutcome.path("position_episode_id").textValue());

        JsonNode noMacroPosition = position(noMacroReplay);
        JsonNode macroPosition = position(macroReplay);
        assertEquals(List.of(1, 2, 3), fillStages(noMacroPosition));
        assertEquals(List.of(1), fillStages(macroPosition));
        assertFundingSettlement(noMacroPosition);
        assertFundingSettlement(macroPosition);
        assertEquals(List.of(2, 3), filledAttemptStages(noMacroReplay));
        assertTrue(macroReplay.path("stage_attempts").toString().contains("MACRO_OPPOSING_BLOCKS_STAGE_2"),
                () -> "the counterfactual addition is retained with its causal opposing-macro reason; attempts: "
                        + macroReplay.path("stage_attempts"));

        assertFullPositionRisk(noMacroOutcome);
        assertFullPositionRisk(macroOutcome);
        assertEquals(CORE_FILL_TIME.toString(), noMacroOutcome.path("first_fill_time").asText());
        assertEquals(CORE_FILL_TIME.toString(), macroOutcome.path("first_fill_time").asText());
        assertEquals(CORE_FILL_TIME.plusSeconds(60L * 86_400L).toEpochMilli(),
                noMacroOutcome.path("deadline_order_time").asLong());
        assertEquals(CORE_FILL_TIME.plusSeconds(60L * 86_400L).toEpochMilli(),
                macroOutcome.path("deadline_order_time").asLong());
        Instant expectedDeadlineFill = CORE_FILL_TIME.plusSeconds(60L * 86_400L + 60L);
        assertEquals(expectedDeadlineFill.toString(), noMacroOutcome.path("exit_time").asText());
        assertEquals(expectedDeadlineFill.toString(), macroOutcome.path("exit_time").asText());
        assertEquals(expectedDeadlineFill.toEpochMilli(), noMacroOutcome.path("deadline_fill_time").asLong());
        assertEquals(expectedDeadlineFill.toEpochMilli(), macroOutcome.path("deadline_fill_time").asLong());
        assertEquals(60_000L, noMacroOutcome.path("deadline_execution_overrun_ms").asLong());
        assertEquals(60_000L, macroOutcome.path("deadline_execution_overrun_ms").asLong());
        assertTrue(noMacroOutcome.path("exit_reason").asText().contains("SIXTY_DAY_LIFECYCLE"));
        assertTrue(macroOutcome.path("exit_reason").asText().contains("SIXTY_DAY_LIFECYCLE"));
        assertEquals("KNOWN_AT_OR_COMPLETED_BAR_TIME; NOT_TICK_FILL_TIME",
                noMacroOutcome.path("exit_time_semantics").asText());
        assertEquals("KNOWN_AT_OR_COMPLETED_BAR_TIME; NOT_TICK_FILL_TIME",
                macroOutcome.path("exit_time_semantics").asText());

        assertAccountReconciles(noMacroReplay, noMacroOutcome, noMacroPosition);
        assertAccountReconciles(macroReplay, macroOutcome, macroPosition);
    }

    static ObjectNode buildFreeze(Path root) throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        List<ObjectNode> features = featureRows();
        putInput(root, inputs, "feature", features);
        putInput(root, inputs, "label", List.of(JsonHashes.mapper().createObjectNode()
                .put("asset", ASSET).put("episode_id", "opaque-staged-fixture-outcome")
                .put("decision_time", CORE_FILL_TIME.toEpochMilli()).put("label", "UNINSPECTED")));
        putInput(root, inputs, "execution", minuteRows(true, features));
        putInput(root, inputs, "mark", minuteRows(false, features));
        putInput(root, inputs, "funding", fundingRows());
        putInput(root, inputs, "metadata", metadataRows());

        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        physicalOptions.set("profile", profile.deepCopy());
        ObjectNode buildOptions = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        buildOptions.set("profile", profile.deepCopy());
        buildOptions.set("inputs", inputs);
        physicalOptions.set("manifest", LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(buildOptions));
        ObjectNode freezeOptions = JsonHashes.mapper().createObjectNode().put("project_root", projectRoot().toString());
        freezeOptions.set("profile", profile.deepCopy());
        freezeOptions.set("physical_options", physicalOptions);
        return LiquidationPortfolioReplayV1.freeze(freezeOptions);
    }

    static ObjectNode replayRequest() {
        return JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", FEATURE_START.toString())
                .put("replay_start", DECISION_START.toString())
                .put("decision_end_exclusive", DECISION_END.toString())
                .put("execution_end_exclusive", FULL_EXECUTION_END.toString());
    }

    static ObjectNode runAndEvaluateOptions(ObjectNode freeze, ObjectNode request,
            String replayFileName, String evidenceFileName) {
        Path root = Path.of(freeze.path("physical_root").asText());
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("replay_out", root.resolve(replayFileName).toString())
                .put("evidence_out", root.resolve(evidenceFileName).toString());
        options.set("freeze", freeze.deepCopy());
        options.set("replay_request", request.deepCopy());
        return options;
    }

    private static ObjectNode readObject(Path path) throws Exception {
        JsonNode parsed = JsonHashes.mapper().readTree(Files.readAllBytes(path));
        assertTrue(parsed instanceof ObjectNode, "runner output must be a JSON object: " + path.getFileName());
        return (ObjectNode) parsed;
    }

    private static void assertFrozenPlan(ObjectNode plan, String label) {
        assertEquals(LiquidationV2StagedCandidateInventoryV1.SCHEMA, plan.path("schema").asText(),
                label + " schema");
        assertEquals(JsonHashes.ownHash(plan), plan.path("content_sha256").asText(),
                label + " self-hash");
    }

    private static ObjectNode invokeNoMacroPlanAdapter(Path root, ObjectNode coreReplay,
            ObjectNode coreEvidence, ObjectNode inventory) throws Exception {
        Path replayFile = writeJson(root, "adapter-core-replay.json", coreReplay);
        Path evidenceFile = writeJson(root, "adapter-core-evidence.json", coreEvidence);
        Path inventoryFile = writeJson(root, "adapter-candidate-inventory.json", inventory);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = StrategyResearchV5CommandAdapter.run(new String[] {
                    "liquidation-v2-staged-plan-no-macro",
                    "--core_replay", replayFile.toString(),
                    "--core_evidence", evidenceFile.toString(),
                    "--candidate_inventory", inventoryFile.toString()
            }, out, err);
        }
        assertEquals(0, status, "the staged plan CLI adapter should accept the serialized public inputs");
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        JsonNode parsed = JsonHashes.mapper().readTree(stdout.toByteArray());
        assertTrue(parsed instanceof ObjectNode, "adapter stdout is the canonical plan JSON object");
        ObjectNode plan = (ObjectNode) parsed;
        assertEquals(plan.path("content_sha256").asText(), JsonHashes.ownHash(plan));
        return plan;
    }

    private static ObjectNode invokeMacroPlanAdapter(Path root, ObjectNode freeze, ObjectNode noMacroPlan,
            ObjectNode coreReplay, ObjectNode coreEvidence, ObjectNode noMacroReplay,
            ObjectNode noMacroEvidence) throws Exception {
        Path freezeFile = writeJson(root, "adapter-freeze.json", freeze);
        Path noMacroPlanFile = writeJson(root, "adapter-no-macro-plan.json", noMacroPlan);
        Path coreReplayFile = writeJson(root, "adapter-macro-core-replay.json", coreReplay);
        Path coreEvidenceFile = writeJson(root, "adapter-macro-core-evidence.json", coreEvidence);
        Path noMacroReplayFile = writeJson(root, "adapter-no-macro-replay.json", noMacroReplay);
        Path noMacroEvidenceFile = writeJson(root, "adapter-no-macro-evidence.json", noMacroEvidence);
        Path output = root.resolve("adapter-macro-plan.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = StrategyResearchV5CommandAdapter.run(new String[] {
                    "liquidation-v2-staged-plan-macro",
                    "--freeze", freezeFile.toString(),
                    "--no_macro_plan", noMacroPlanFile.toString(),
                    "--core_replay", coreReplayFile.toString(),
                    "--core_evidence", coreEvidenceFile.toString(),
                    "--no_macro_replay", noMacroReplayFile.toString(),
                    "--no_macro_evidence", noMacroEvidenceFile.toString(),
                    "--out", output.toString()
            }, out, err);
        }
        assertEquals(0, status, "macro-plan adapter accepts hash-bound public predecessor artifacts");
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        JsonNode parsed = JsonHashes.mapper().readTree(stdout.toByteArray());
        assertTrue(parsed instanceof ObjectNode, "macro plan adapter emits one JSON object");
        return (ObjectNode) parsed;
    }

    private static String firstDifferentPointer(JsonNode left, JsonNode right) {
        return firstDifferentPointer(left, right, "");
    }

    private static String firstDifferentPointer(JsonNode left, JsonNode right, String pointer) {
        if (left == null || right == null) return pointer.isEmpty() ? "/" : pointer;
        if (left.isObject() && right.isObject()) {
            java.util.TreeSet<String> fields = new java.util.TreeSet<>();
            left.fieldNames().forEachRemaining(fields::add);
            right.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if ("content_sha256".equals(field)) continue;
                String child = pointer + "/" + field.replace("~", "~0").replace("/", "~1");
                if (!left.has(field) || !right.has(field)) return child;
                String difference = firstDifferentPointer(left.get(field), right.get(field), child);
                if (difference != null) return difference;
            }
            return null;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return pointer + "/length";
            for (int index = 0; index < left.size(); index++) {
                String difference = firstDifferentPointer(left.get(index), right.get(index), pointer + "/" + index);
                if (difference != null) return difference;
            }
            return null;
        }
        return JsonHashes.canonicalString(left).equals(JsonHashes.canonicalString(right)) ? null
                : pointer.isEmpty() ? "/" : pointer;
    }

    private static Path writeJson(Path root, String name, ObjectNode value) throws Exception {
        Path path = root.resolve(name);
        Files.write(path, JsonHashes.canonicalBytes(value));
        return path;
    }

    private static ObjectNode evaluationStagedOptions(ObjectNode runOptions, ObjectNode replay, String fileName) {
        ObjectNode options = runOptions.deepCopy();
        options.put("out", Path.of(options.path("freeze").path("physical_root").asText()).resolve(fileName).toString());
        options.set("replay", replay.deepCopy());
        return options;
    }

    private static ObjectNode stagedRunOptions(ObjectNode freeze, ObjectNode plan, ObjectNode coreReplay,
            ObjectNode coreEvidence, ObjectNode inventory, ObjectNode predecessorPlan,
            ObjectNode predecessorReplay, ObjectNode predecessorEvidence,
            ObjectNode request, String fileName) {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("out", Path.of(freeze.path("physical_root").asText()).resolve(fileName).toString());
        options.set("freeze", freeze.deepCopy());
        options.set("staged_plan", plan.deepCopy());
        options.set("core_replay", coreReplay.deepCopy());
        options.set("core_evidence", coreEvidence.deepCopy());
        options.set("core_candidate_inventory", inventory.deepCopy());
        options.set("replay_request", request.deepCopy());
        if (predecessorPlan != null) options.set("predecessor_plan", predecessorPlan.deepCopy());
        if (predecessorReplay != null) options.set("predecessor_replay", predecessorReplay.deepCopy());
        if (predecessorEvidence != null) options.set("predecessor_evidence", predecessorEvidence.deepCopy());
        return options;
    }

    private static JsonNode onlyOutcome(ObjectNode replay) {
        assertEquals(1, replay.path("opportunities").size());
        return replay.path("opportunities").get(0);
    }

    private static JsonNode position(ObjectNode replay) {
        JsonNode accounts = replay.path("ledger").path("accounts");
        assertEquals(1, accounts.size());
        for (JsonNode value : accounts.get(0).path("account").path("positions")) {
            if (ASSET.equals(value.path("asset").asText())) return value;
        }
        throw new AssertionError("BTC position missing from staged replay account");
    }

    private static List<Integer> fillStages(JsonNode position) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode fill : position.path("fills")) values.add(fill.path("stage").asInt());
        return values;
    }

    private static List<Integer> filledAttemptStages(ObjectNode replay) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode attempt : replay.path("stage_attempts")) {
            if ("STAGE_FILLED".equals(attempt.path("outcome_state").asText())) values.add(attempt.path("stage").asInt());
        }
        return values;
    }

    private static void assertFullPositionRisk(JsonNode outcome) {
        BigDecimal equity = outcome.path("first_fill_reference_equity_usdt").decimalValue();
        BigDecimal expectedRisk = equity.multiply(new BigDecimal("0.05"));
        assertEquals(0, expectedRisk.compareTo(outcome.path("full_position_reference_risk_usdt").decimalValue()));
        assertEquals(0, expectedRisk.compareTo(outcome.path("reference_risk_usdt").decimalValue()));
        assertEquals("FULL_POSITION_5_PERCENT_OF_FIRST_FILL_REFERENCE_EQUITY",
                outcome.path("full_position_reference_risk_basis").asText());
    }

    private static void assertFundingSettlement(JsonNode position) {
        long settlementTime = Instant.parse("2024-01-03T16:00:00.007Z").toEpochMilli();
        String settlementId = "staged-funding-BTC-" + settlementTime;
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode event : position.path("funding_events")) {
            if (settlementId.equals(event.path("event_id").asText())) matching.add(event);
        }
        assertEquals(1, matching.size(), "the physical settlement is applied once to the live staged position");
        JsonNode event = matching.get(0);
        assertEquals(settlementTime, event.path("settlement_time").asLong());
        assertEquals("SETTLED", event.path("status").asText());
        BigDecimal quantity = event.path("charged_quantity_at_settlement").decimalValue();
        assertEquals(0, quantity.compareTo(position.path("fills").get(0).path("quantity").decimalValue()),
                "no later staged addition is present at this settlement");
        assertEquals(99.45, event.path("mark_price").asDouble(), 1e-8);
        assertEquals(0.001, event.path("rate").asDouble(), 1e-12);
        BigDecimal directionSign = "LONG".equals(position.path("direction").asText())
                ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        BigDecimal expectedAmount = quantity.multiply(event.path("mark_price").decimalValue())
                .multiply(event.path("rate").decimalValue()).multiply(directionSign);
        assertEquals(0, expectedAmount.compareTo(event.path("amount_usdt").decimalValue()),
                "the signed charge uses the open quantity, mark and side at settlement time");
        assertEquals(0, expectedAmount.compareTo(position.path("funding_pnl_usdt").decimalValue()),
                "zero-rate settlements are retained without changing the cumulative funding amount");
    }

    private static void assertAccountReconciles(ObjectNode replay, JsonNode outcome, JsonNode position) {
        JsonNode account = replay.path("ledger").path("accounts").get(0).path("account");
        assertEquals(0, account.path("reconciliation_delta_usdt").decimalValue().signum());
        BigDecimal expectedEquity = account.path("initial_equity_usdt").decimalValue()
                .add(account.path("realized_gross_pnl_usdt").decimalValue())
                .add(account.path("funding_pnl_usdt").decimalValue())
                .subtract(account.path("entry_costs_usdt").decimalValue())
                .subtract(account.path("exit_costs_usdt").decimalValue());
        assertWithinOneBinary64Ulp(expectedEquity, account.path("mark_to_market_equity_usdt"),
                "serialized account equity may differ by at most one binary64 ULP");
        BigDecimal reconciledPositionPnl = position.path("realized_gross_pnl_usdt").decimalValue()
                .add(position.path("funding_pnl_usdt").decimalValue())
                .subtract(position.path("entry_costs_usdt").decimalValue())
                .subtract(position.path("exit_costs_usdt").decimalValue());
        assertWithinOneBinary64Ulp(reconciledPositionPnl, outcome.path("net_pnl_usdt"),
                "serialized position attribution may differ by at most one binary64 ULP");
        assertTrue(position.path("exits").isArray() && position.path("exits").size() == 1,
                "all stage fills close as one position episode");
        assertTrue(outcome.path("tranche_attribution").path("reconciled").asBoolean());
    }

    private static void assertWithinOneBinary64Ulp(BigDecimal expected, JsonNode serialized, String message) {
        BigDecimal actual = serialized.decimalValue();
        BigDecimal oneUlp = BigDecimal.valueOf(Math.ulp(serialized.asDouble()));
        assertTrue(expected.subtract(actual).abs().compareTo(oneUlp) <= 0,
                message + "; expected=" + expected + ", actual=" + actual + ", oneUlp=" + oneUlp);
    }

    static List<ObjectNode> featureRows() {
        List<ObjectNode> rows = new ArrayList<>();
        for (int lag = LiquidationStructureRouterV1.DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
            LocalDate date = STRESS_DAY.minusDays(lag);
            long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            rows.add(dailyRow("LONG", start, 1));
            rows.add(dailyRow("SHORT", start, 1));
        }
        long stressDayStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        rows.add(dailyRow("LONG", stressDayStart, 2));
        rows.add(dailyRow("SHORT", stressDayStart, 2));
        rows.addAll(opposingMacroRows());

        for (Instant start = FEATURE_START; start.isBefore(FULL_EXECUTION_END);
                start = start.plusSeconds(4L * 3_600L)) {
            double open = 99.8, high = 100.1, low = 99.6, close = 99.7;
            if (start.isBefore(STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant())) {
                open = 100; high = 100.5; low = 99.5; close = 100;
            }
            if (start.equals(STRESS_START)) { open = 100; high = 100.2; low = 96.7; close = 97; }
            if (start.equals(MODEL_AVAILABLE.minusSeconds(4L * 3_600L))) { open = 98.5; high = 100.2; low = 97.5; close = 99.0; }
            if (start.equals(MODEL_AVAILABLE)) { open = 99.0; high = 100.2; low = 98.4; close = 99.2; }
            if (start.equals(MODEL_AVAILABLE.plusSeconds(4L * 3_600L))) { open = 99.2; high = 100.2; low = 98.6; close = 99.4; }
            rows.add(priceRow("4h", start, start.plusSeconds(4L * 3_600L), open, high, low, close));
        }
        for (Instant start = FEATURE_START; start.isBefore(FULL_EXECUTION_END); start = start.plusSeconds(3_600L)) {
            rows.add(priceRow("1h", start, start.plusSeconds(3_600L), 99.8, 100.0, 99.6, 99.8));
        }
        Instant oiStart = STRESS_START.minusSeconds(5L * 60L);
        Instant oiEnd = STRESS_START.plusSeconds(4L * 3_600L - 5L * 60L);
        rows.add(oiRow(oiStart, oiStart.plusSeconds(1), 100));
        rows.add(oiRow(oiEnd, oiEnd.plusSeconds(1), 90));

        addHour(rows, MODEL_AVAILABLE.plusSeconds(2L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(3L * 3_600L), 100, 100.2, 99.8, 100);
        setPrice(rows, "1h", MODEL_AVAILABLE.plusSeconds(4L * 3_600L), 99.7, 99.8, 99.4, FLAT_PRICE);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(5L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(6L * 3_600L), 100, 100.2, 99.8, 100);
        addHour(rows, MODEL_AVAILABLE.plusSeconds(7L * 3_600L), 100, 100.2, 99.8, 100);
        setPrice(rows, "1h", MODEL_AVAILABLE.plusSeconds(8L * 3_600L), 99.7, 99.8, 99.4, FLAT_PRICE);
        addPostInitialFillBars(rows);
        return rows;
    }

    private static void addPostInitialFillBars(List<ObjectNode> rows) {
        double[][] first = {{99.4, 99.8, 99.2, 99.6}, {99.6, 99.9, 99.2, 99.7},
                {99.7, 100.2, 99.2, 99.8}, {99.8, 99.8, 99.1, 99.5}, {99.5, 99.8, 99.0, 99.4}};
        Instant firstStart = MODEL_AVAILABLE.plusSeconds(8L * 3_600L);
        for (int i = 0; i < first.length; i++) {
            double[] value = first[i];
            Instant start = firstStart.plusSeconds(4L * 3_600L * i);
            setPrice(rows, "4h", start, value[0], value[1], value[2], value[3]);
        }
        Instant favorable = firstStart.plusSeconds(20L * 3_600L);
        setPrice(rows, "4h", favorable, 99.4, 100.1, 98.8, 99.0);

        addHour(rows, utc(LocalDate.of(2024, 1, 4), 6), 100, 100.1, 99.9, 100);
        addHour(rows, utc(LocalDate.of(2024, 1, 4), 7), 99.9, 100, 99.5, 99.6);

        double[][] afterStageTwo = {{99.5, 99.8, 99.2, 99.4}, {99.4, 99.9, 99.1, 99.3},
                {99.3, 100.1, 99.0, 99.2}, {99.2, 99.8, 98.9, 99.1}, {99.1, 99.9, 98.8, 99.0}};
        Instant secondStart = utc(LocalDate.of(2024, 1, 5), 0);
        for (int i = 0; i < afterStageTwo.length; i++) {
            double[] value = afterStageTwo[i];
            Instant start = secondStart.plusSeconds(4L * 3_600L * i);
            setPrice(rows, "4h", start, value[0], value[1], value[2], value[3]);
        }
        addHour(rows, utc(LocalDate.of(2024, 1, 4), 20), 100, 100.1, 99.95, 100);
        addHour(rows, utc(LocalDate.of(2024, 1, 4), 21), 100, 100.1, 99.6, 99.7);
        addHour(rows, utc(LocalDate.of(2024, 1, 8), 20), 99.8, 100.0, 99.8, 99.9);
        addHour(rows, utc(LocalDate.of(2024, 1, 8), 21), 99.9, 100.1, 99.6, 99.7);
    }

    private static List<ObjectNode> opposingMacroRows() {
        LocalDate[] dates = {LocalDate.of(2023, 12, 22), LocalDate.of(2023, 12, 26), LocalDate.of(2023, 12, 27),
                LocalDate.of(2023, 12, 28), LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 5)};
        double[] closes = {100, 100.2, 100.4, 100.6, 100.8, 101, 101.1, 101.2, 101.3};
        List<ObjectNode> rows = new ArrayList<>();
        for (int index = 0; index < dates.length; index++) {
            LocalDate date = dates[index];
            Instant close = date.atTime(16, 0).atZone(NEW_YORK).toInstant();
            Instant available = nextNySession(date).atTime(16, 0).atZone(NEW_YORK).toInstant();
            rows.add(JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                    .put("series_id", "sp500_close").put("timeframe", "1d")
                    .put("event_time", close.toEpochMilli()).put("availability_time", available.toEpochMilli())
                    .put("close", closes[index]));
        }
        return rows;
    }

    private static LocalDate nextNySession(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (next.getDayOfWeek().getValue() >= 6 || next.equals(LocalDate.of(2023, 12, 25))
                || next.equals(LocalDate.of(2024, 1, 1))) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static List<ObjectNode> minuteRows(boolean execution, List<ObjectNode> features) {
        List<ObjectNode> rows = new ArrayList<>();
        NavigableMap<Long, ObjectNode> h4 = new TreeMap<>();
        for (ObjectNode row : features) if ("price_ohlc".equals(row.path("series_id").asText())
                && "4h".equals(row.path("timeframe").asText())) {
            h4.put(row.path("event_time").asLong(), row);
        }
        for (Instant time = Instant.parse("2024-01-01T00:00:00Z"); time.isBefore(FULL_EXECUTION_END);
                time = time.plusSeconds(60)) {
            long timeMillis = time.toEpochMilli();
            var h4Entry = h4.floorEntry(timeMillis);
            if (h4Entry == null) throw new AssertionError("synthetic feature history lacks an enclosing 4h bar");
            ObjectNode source = h4Entry.getValue();
            long offsetMinutes = (timeMillis - h4Entry.getKey()) / 60_000L;
            double sourceOpen = source.path("open").asDouble(), sourceHigh = source.path("high").asDouble();
            double sourceLow = source.path("low").asDouble(), sourceClose = source.path("close").asDouble();
            double fraction = Math.min(1.0, offsetMinutes / 239.0);
            double open = sourceOpen + (sourceClose - sourceOpen) * fraction;
            double close = offsetMinutes == 239L ? sourceClose
                    : sourceOpen + (sourceClose - sourceOpen) * Math.min(1.0, (offsetMinutes + 1) / 239.0);
            double high = Math.max(open, close) + 0.001, low = Math.min(open, close) - 0.001;
            if (offsetMinutes == 0L) {
                open = sourceOpen; close = sourceOpen; high = sourceHigh; low = sourceLow;
            }
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("asset", ASSET)
                    .put("open", open).put("high", high).put("low", low).put("close", close);
            if (execution) row.put("open_time", timeMillis).put("base_volume", 100_000);
            else row.put("timestamp", timeMillis);
            rows.add(row);
        }
        return rows;
    }

    private static List<ObjectNode> metadataRows() {
        List<ObjectNode> rows = new ArrayList<>();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            rows.add(JsonHashes.mapper().createObjectNode().put("asset", asset).put("tier_index", 1)
                    .put("effective_from", FEATURE_START.toEpochMilli()).put("effective_until", FULL_EXECUTION_END.toEpochMilli())
                    .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.0005)
                    .put("slippage_rate", 0.0001).put("liquidation_fee_rate", 0.01)
                    .put("tier_notional_cap", 1_000_000_000).put("maintenance_margin_rate", 0.005)
                    .put("maintenance_deduction", 0).put("terminal_tier", true));
        }
        return rows;
    }

    private static ObjectNode dailyRow(String side, long eventTime, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("symbol", COINALYZE)
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", side)
                .put("event_time", eventTime).put("availability_time", eventTime + 172_800_000L).put("value", value);
    }

    private static ObjectNode priceRow(String timeframe, Instant eventTime, Instant availableAt,
            double open, double high, double low, double close) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("symbol", BINANCE)
                .put("series_id", "price_ohlc").put("timeframe", timeframe)
                .put("event_time", eventTime.toEpochMilli()).put("availability_time", availableAt.toEpochMilli())
                .put("open", open).put("high", high).put("low", low).put("close", close).put("base_volume", 100);
    }

    private static ObjectNode oiRow(Instant observed, Instant available, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", ASSET).put("symbol", BINANCE)
                .put("series_id", "open_interest_base").put("timeframe", "5m")
                .put("event_time", observed.toEpochMilli()).put("availability_time", available.toEpochMilli()).put("value", value);
    }

    private static void addHour(List<ObjectNode> rows, Instant start, double open, double high, double low, double close) {
        setPrice(rows, "1h", start, open, high, low, close);
    }

    private static void setPrice(List<ObjectNode> rows, String timeframe, Instant start,
            double open, double high, double low, double close) {
        long epoch = start.toEpochMilli();
        for (ObjectNode row : rows) if ("price_ohlc".equals(row.path("series_id").asText())
                && timeframe.equals(row.path("timeframe").asText())
                && row.path("event_time").asLong() == epoch) {
            row.put("open", open).put("high", high).put("low", low).put("close", close);
            return;
        }
        throw new AssertionError("synthetic fixture is missing " + timeframe + " bar at " + start);
    }

    private static List<ObjectNode> fundingRows() {
        List<ObjectNode> rows = new ArrayList<>();
        Instant target = Instant.parse("2024-01-03T16:00:00.007Z");
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            for (Instant settlement = Instant.parse("2024-01-01T00:00:00.007Z");
                    settlement.isBefore(FULL_EXECUTION_END); settlement = settlement.plusSeconds(8L * 3_600L)) {
                double rate = "BTC".equals(asset) && target.equals(settlement) ? 0.001 : 0.0;
                rows.add(JsonHashes.mapper().createObjectNode().put("asset", asset)
                        .put("settlement_time", settlement.toEpochMilli())
                        .put("event_id", "staged-funding-" + asset + "-" + settlement.toEpochMilli())
                        .put("funding_rate", rate).put("mark_price", FLAT_PRICE).put("funding_interval_hours", 8));
            }
        }
        return rows;
    }

    private static Instant utc(LocalDate date, int hour) { return date.atTime(hour, 0).toInstant(ZoneOffset.UTC); }

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
        throw new IllegalStateException("cannot locate the repository precommit for staged replay freeze");
    }
}
