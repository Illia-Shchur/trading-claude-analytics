package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyOperatingCharacteristicsSuccessorV1Test {
    @TempDir Path temporary;

    @Test
    void diagnosisReconcilesAllRetainedRowsAndExposesJointComponentAttrition() throws Exception {
        ObjectNode result = diagnoseFixture(temporary, false, false);
        assertThat(result.path("projection_exact").asBoolean()).isTrue();
        assertThat(result.path("replications_reconciled").asInt()).isEqualTo(200);
        assertThat(result.path("decision_mismatches").asInt()).isZero();
        assertThat(result.path("joint_rule_recomputed").asBoolean()).isTrue();
        assertThat(result.path("cells")).anySatisfy(cell -> {
            if (cell.path("cell").asText().equals("PLANTED_EDGE:0.02")) {
                assertThat(cell.path("joint_decision_passes").asInt()).isEqualTo(14);
                assertThat(cell.path("event_p20_passes").asInt()).isEqualTo(27);
                assertThat(cell.path("paired_p_value_passes").asInt()).isEqualTo(19);
            }
        });
    }

    @Test
    void diagnosisRejectsForgedNumericBooleansAndEmptyCompactRows() throws Exception {
        assertThatThrownBy(() -> diagnoseFixture(temporary, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> diagnoseFixture(temporary, false, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("incomplete fields");
    }

    @Test
    void successorPreflightRetainsStandardsAndBlocksNineFoldClusterGeometryInTheMachineEnvelope() {
        ObjectNode plan = plan();
        ObjectNode receipt = StrategyOperatingCharacteristicsSuccessorV1.preflight(plan);
        assertThat(receipt.path("status").asText()).isEqualTo("BLOCKED_RESOURCE_ESTIMATE");
        assertThat(receipt.path("resource_budget_pass").asBoolean()).isFalse();
        assertThat(receipt.path("estimated_peak_rss_bytes").asLong()).isGreaterThan(8L * 1024 * 1024 * 1024);
        assertThat(receipt.path("estimated_wall_seconds").asLong()).isGreaterThan(12L * 60 * 60);
        plan.with("acceptance").put("power_lower_bound", .70);
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsSuccessorV1.preflight(plan))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acceptance");
    }

    @Test
    void successorPreflightRejectsPrefixSeedOverlappingAFullSeedInALaterCell() {
        ObjectNode plan = plan();
        long laterFullSeed = plan.path("cells").get(1).path("seeds").get(0).asLong();
        ((ArrayNode) plan.path("cells").get(0).path("prefix_seeds")).set(0,
                JsonHashes.mapper().getNodeFactory().numberNode(laterFullSeed));
        plan.put("content_sha256", JsonHashes.ownHash(plan));

        assertThatThrownBy(() -> StrategyOperatingCharacteristicsSuccessorV1.preflight(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix seeds overlap full seeds");
    }

    @Test
    void optimizedReplicationMatchesTheEagerRawRowWhenPackagedExecutorIsAvailable() throws Exception {
        ObjectNode identity = BuildIdentityService.describe(StrategyFixedBaselineV5.class);
        Assumptions.assumeTrue("JAR".equals(identity.path("executable").path("kind").asText()),
                "the fixed evaluator intentionally requires a packaged executor identity");
        ObjectNode baseline = readObject(repoFile("strategy-research/definitions/fk-deleveraging-absorption/v002.json"));
        ObjectNode controls = readObject(repoFile("strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json"));
        ObjectNode experiment = readObject(repoFile("strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json"));
        ObjectNode plan = JsonHashes.mapper().createObjectNode().put("test", "bounded-repetition");
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        long seed = 202609080001L;

        ObjectNode optimized = StrategyOperatingCharacteristicsSuccessorV1.evaluateOptimizedReplication(
                baseline, controls, experiment, plan, "PREFIX", "NO_EDGE", 0D, 0, seed, 10,
                Long.MAX_VALUE, Long.MAX_VALUE);

        Class<?> cellType = Class.forName(StrategyOperatingCharacteristicsSuccessorV1.class.getName() + "$CellSpec");
        Constructor<?> constructor = cellType.getDeclaredConstructor(String.class, double.class, ArrayNode.class);
        constructor.setAccessible(true);
        Object cell = constructor.newInstance("NO_EDGE", 0D, null);
        Method eagerMethod = StrategyOperatingCharacteristicsSuccessorV1.class.getDeclaredMethod("runReplication",
                ObjectNode.class, ObjectNode.class, ObjectNode.class, ObjectNode.class, String.class,
                cellType, int.class, long.class, int.class, long.class, long.class);
        eagerMethod.setAccessible(true);
        ObjectNode eager = (ObjectNode) eagerMethod.invoke(null, plan, baseline, controls, experiment,
                "PREFIX", cell, 0, seed, 10, Long.MAX_VALUE, Long.MAX_VALUE);

        ObjectNode optimizedRaw = validateRawEvaluatorResult(optimized, "optimized");
        ObjectNode eagerRaw = validateRawEvaluatorResult(eager, "eager");
        assertThat(optimized.path("economic_semantic_sha256").asText())
                .as("bounded and eager economic semantic hash")
                .isEqualTo(eager.path("economic_semantic_sha256").asText());
        // These are the authoritative economic projections: metrics, the
        // outcome-blind selected controls, attempts (including trade costs),
        // and the reconciled portfolio. The complete normalized raw result is
        // compared below after only the evaluator's documented audit metadata
        // exclusions.
        for (String field : List.of("metrics", "control_selections", "attempts", "portfolio")) {
            assertThat(optimizedRaw.path(field)).as("bounded/eager raw field %s", field)
                    .isEqualTo(eagerRaw.path(field));
        }
        assertThat(normalizedRawEvaluatorResult(optimizedRaw))
                .as("bounded/eager raw evaluator result")
                .isEqualTo(normalizedRawEvaluatorResult(eagerRaw));
    }

    private static ObjectNode validateRawEvaluatorResult(ObjectNode row, String label) throws Exception {
        assertThat(row.path("raw_evaluator_result").isObject())
                .as("%s raw evaluator result is present", label).isTrue();
        ObjectNode raw = (ObjectNode) row.path("raw_evaluator_result");
        String content = raw.path("content_sha256").asText();
        assertThat(content).as("%s raw content hash", label).isEqualTo(JsonHashes.ownHash(raw));
        assertThat(row.path("raw_evaluator_result_content_sha256").asText())
                .as("%s row raw content hash binding", label).isEqualTo(content);
        assertThat(row.path("raw_evaluator_result_canonical_sha256").asText())
                .as("%s raw canonical hash", label).isEqualTo(JsonHashes.canonicalSha256(raw));
        assertThat(row.path("raw_evaluator_result_byte_sha256").asText())
                .as("%s raw serialized hash", label)
                .isEqualTo(JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(raw)));
        return raw;
    }

    private static ObjectNode normalizedRawEvaluatorResult(ObjectNode raw) {
        ObjectNode copy = raw.deepCopy();
        // Match StrategyFixedBaselineV5.economicSemanticHash exactly. These
        // fields identify the executable, attempt, exposure custody path, or
        // derived JSON/hash envelope; they are audit metadata rather than
        // economic output. Every other raw field remains in this comparison.
        for (String field : List.of("content_sha256", "semantic_sha256", "economic_semantic_sha256",
                "build_identity", "exposure_head_sha256", "exposure_head_path",
                "executor_identity_sha256", "attempt_identity_sha256", "legacy_exposure_migration")) {
            copy.remove(field);
        }
        stripCustodyPaths(copy);
        return copy;
    }

    private static void stripCustodyPaths(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("path");
            object.remove("physical_root_reference");
            List<String> fields = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) stripCustodyPaths(object.path(field));
        } else if (node.isArray()) {
            for (JsonNode child : node) stripCustodyPaths(child);
        }
    }

    @Test
    void attemptLedgerRequiresFrozenScenarioSlotAndEvaluatorReceipt() throws Exception {
        ObjectNode plan = plan();
        Path planPath = temporary.resolve("plan.json");
        Files.writeString(planPath, JsonHashes.mapper().writeValueAsString(plan));
        Path attempt = temporary.resolve("attempt.json");
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("cell", "NO_EDGE").put("replication", 0);
        ObjectNode attemptValue = JsonHashes.mapper().createObjectNode().put("attempt_id", "FULL|NO_EDGE|0:0")
                .put("plan_sha256", plan.path("content_sha256").asText()).put("scenario", "NO_EDGE")
                .put("mode", "FULL").put("effect_size", 0).put("replication", 0).put("seed", plan.path("cells").get(0).path("seeds").get(0).asLong())
                .put("status", "COMPUTE_INCOMPLETE").put("outcomes_opened", true).put("promotion_eligible", false)
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText())
                .put("input_sha256", "b".repeat(64)).set("result_row", row);
        Files.writeString(attempt, JsonHashes.mapper().writeValueAsString(attemptValue));
        ObjectNode ledger = StrategyOperatingCharacteristicsSuccessorV1.recordAttempt(JsonHashes.mapper().createObjectNode()
                .put("plan", planPath.toString()).put("attempt", attempt.toString()).put("ledger", temporary.resolve("ledger.json").toString()));
        assertThat(ledger.path("attempt_count").asInt()).isEqualTo(1);
        assertThat(ledger.path("incomplete_count").asInt()).isEqualTo(1);
        ObjectNode bad = attemptValue.deepCopy().put("attempt_id", "FULL|NO_EDGE|0:1").put("replication", 1)
                .put("seed", 999L);
        Files.writeString(attempt, JsonHashes.mapper().writeValueAsString(bad));
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsSuccessorV1.recordAttempt(JsonHashes.mapper().createObjectNode()
                .put("plan", planPath.toString()).put("attempt", attempt.toString()).put("ledger", temporary.resolve("ledger.json").toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seed");
    }

    @Test
    void attemptLedgerRetainsStartedReservationAndAllowsOneFinalRetry() throws Exception {
        ObjectNode plan = plan();
        Path planPath = temporary.resolve("resume-plan.json");
        Files.writeString(planPath, JsonHashes.mapper().writeValueAsString(plan));
        Path ledgerPath = temporary.resolve("resume-ledger.json");
        long seed = plan.path("cells").get(0).path("prefix_seeds").get(0).asLong();
        ObjectNode started = attempt(plan, "PREFIX", "NO_EDGE", 0, seed, "STARTED", null);
        record(planPath, temporary.resolve("started.json"), ledgerPath, started);
        ObjectNode retry = attempt(plan, "PREFIX", "NO_EDGE", 0, seed, "COMPUTE_INCOMPLETE",
                JsonHashes.mapper().createObjectNode().put("status", "COMPUTE_INCOMPLETE"));
        ObjectNode ledger = record(planPath, temporary.resolve("retry.json"), ledgerPath, retry);
        assertThat(ledger.path("attempt_count").asInt()).isEqualTo(2);
        assertThat(ledger.path("started_count").asInt()).isEqualTo(1);
        assertThat(ledger.path("incomplete_count").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> record(planPath, temporary.resolve("complete.json"), ledgerPath,
                attempt(plan, "PREFIX", "NO_EDGE", 0, seed, "COMPLETE", retry.path("result_row"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("caller-authored COMPLETE");
    }

    private static ObjectNode attempt(ObjectNode plan, String mode, String scenario, double effect, long seed,
            String status, JsonNode row) {
        String effectKey = java.math.BigDecimal.valueOf(effect).stripTrailingZeros().toPlainString();
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("attempt_id", mode + "|" + scenario + "|" + effectKey + ":0")
                .put("plan_sha256", plan.path("content_sha256").asText()).put("mode", mode).put("scenario", scenario)
                .put("effect_size", effect).put("replication", 0).put("seed", seed).put("status", status)
                .put("outcomes_opened", true).put("promotion_eligible", false)
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText()).put("input_sha256", "b".repeat(64));
        if (row != null) value.set("result_row", row.deepCopy());
        return value;
    }

    private ObjectNode record(Path plan, Path attemptPath, Path ledger, ObjectNode attempt) throws Exception {
        Files.writeString(attemptPath, JsonHashes.mapper().writeValueAsString(attempt));
        return StrategyOperatingCharacteristicsSuccessorV1.recordAttempt(JsonHashes.mapper().createObjectNode()
                .put("plan", plan.toString()).put("attempt", attemptPath.toString()).put("ledger", ledger.toString()));
    }

    private static ObjectNode plan() {
        ObjectNode p = JsonHashes.mapper().createObjectNode().put("schema", StrategyOperatingCharacteristicsSuccessorV1.PLAN_SCHEMA)
                .put("version", 1).put("stage_scope", "FIXED_BASELINE_OPERATING_CHARACTERISTICS_SUCCESSOR")
                .put("evidence_phase", "DEVELOPMENT").put("decision", "DIAGNOSTIC_ONLY")
                .put("development_exposure", true).put("promotion_eligible", false).put("activation_authorized", false)
                .put("outcomes_opened", false)
                .put("binding_fixed_evaluator", "StrategyFixedBaselineV5+TradeLifecycleV5+StrategyResearchImprovementV1.disposition")
                .put("predecessor_diagnosis_sha256", "a".repeat(64)).put("baseline_sha256", "b".repeat(64))
                .put("control_spec_sha256", "c".repeat(64)).put("experiment_sha256", "d".repeat(64))
                .put("lineage_inventory_sha256", "e".repeat(64)).put("control_design_sha256", "g".repeat(64))
                .put("executor_source_sha256", "f".repeat(64))
                .put("executor_identity_sha256", "1".repeat(64)).put("cell_count", 4).put("replications", 75)
                .put("episodes_per_replication", 450).put("lifecycle_series_per_replication", 900)
                .put("horizon_minutes", 14_400).put("cluster_count", 288).put("paired_cluster_count", 162)
                .put("frozen_before_outcomes", true);
        ArrayNode cells = p.putArray("cells");
        addCell(cells, "NO_EDGE", 0, 202609070000L);
        addCell(cells, "PLANTED_EDGE", 0, 202609080000L);
        addCell(cells, "PLANTED_EDGE", .02, 202609090000L);
        addCell(cells, "PLANTED_EDGE", .04, 202609100000L);
        p.putObject("resource_budget").put("max_wall_minutes", 720).put("max_rss_bytes", 8L * 1024 * 1024 * 1024)
                .put("max_disk_bytes", 20L * 1024 * 1024 * 1024).put("expected_generated_minute_bars", 3_888_000_000L);
        p.putObject("acceptance").put("preserve_v004_standards", true).put("confidence_interval", "WILSON_95_PERCENT")
                .put("false_positive_upper_bound", .10).put("power_lower_bound", .80);
        p.putObject("stopping").put("fixed_no_optional_stopping", true);
        p.putObject("incomplete_run_policy").put("count_in_denominator", true).put("retain_all_attempts", true);
        p.putObject("resource_preflight").put("replications", 2).put("episodes_per_replication", 10);
        return p.put("content_sha256", JsonHashes.ownHash(p));
    }

    private static ObjectNode readObject(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static Path repoFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative).normalize();
    }

    private static void addCell(ArrayNode cells, String scenario, double effect, long start) {
        ObjectNode cell = cells.addObject().put("scenario", scenario).put("effect_size", effect);
        ArrayNode seeds = cell.putArray("seeds");
        for (int i = 0; i < 75; i++) seeds.add(start + i);
        ArrayNode prefixSeeds = cell.putArray("prefix_seeds");
        // Keep the fixture's ordinary prefix namespace disjoint from every
        // full-seed range; the dedicated overlap test mutates one prefix to a
        // later full seed and still exercises the production guard.
        for (int i = 0; i < 2; i++) prefixSeeds.add(start + 1_000_000L + i);
    }

    private static ObjectNode diagnoseFixture(Path dir, boolean forgeBoolean, boolean emptyCompact) throws Exception {
        ObjectNode raw = JsonHashes.mapper().createObjectNode().put("schema", "strategy-evaluator-operating-characteristics-result/1")
                .put("planned_replications", 200).put("replication_count", 200)
                .put("shared_physical_evaluator", true).put("toy_statistic", false);
        ArrayNode rawRows = raw.putArray("replications");
        String[] cells = {"NO_EDGE:0", "PLANTED_EDGE:0", "PLANTED_EDGE:0.02", "PLANTED_EDGE:0.04"};
        for (int i = 0; i < 200; i++) {
            String key = cells[i / 50];
            String[] pieces = key.split(":");
            double effect = Double.parseDouble(pieces[1]);
            boolean eventP20 = false, pairedP20 = false, eventP = false, pairedP = false;
            if (effect == .02) {
                eventP20 = i % 50 < 27;
                pairedP = i % 50 < 14 || (i % 50 >= 27 && i % 50 < 32);
                eventP = i % 50 < 14;
                pairedP20 = i % 50 < 14;
            } else if (effect == .04) {
                eventP20 = pairedP20 = eventP = pairedP = true;
            }
            ObjectNode falsifier = JsonHashes.mapper().createObjectNode()
                    .put("p20_expectancy_r_min", .05).put("max_statistic_p_value", .10)
                    .put("event_p20_expectancy_r", eventP20 ? .06 : .04)
                    .put("paired_p20_expectancy_r", pairedP20 ? .06 : .04)
                    .put("event_statistic_p_value", eventP ? .05 : .20)
                    .put("paired_statistic_p_value", pairedP ? .05 : .20)
                    .put("event_p20_pass", eventP20).put("paired_p20_pass", pairedP20)
                    .put("event_p_value_pass", eventP).put("paired_p_value_pass", pairedP);
            ObjectNode metrics = JsonHashes.mapper().createObjectNode()
                    .put("paired_analysis_insufficient", false).put("independent_market_episode_count", 30)
                    .put("paired_tested_cluster_count", 30).set("falsifier", falsifier);
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("cell", pieces[0]).put("effect_size", effect).put("replication", i % 50)
                    .put("seed", 50_000L + i).put("status", "COMPLETE")
                    .put("decision", eventP20 && pairedP20 && eventP && pairedP).put("event_count", 30)
                    .put("paired_count", 30).put("independent_units", 30)
                    .put("economic_semantic_sha256", "a".repeat(64)).put("generator_input_sha256", "b".repeat(64));
            row.putObject("disposition").put("primary_reason", "DIAGNOSTIC");
            row.putObject("portfolio_summary");
            row.set("metrics", metrics);
            rawRows.add(row);
        }
        raw.put("content_sha256", JsonHashes.ownHash(raw));
        Path rawPath = dir.resolve("diagnosis-raw-" + forgeBoolean + "-" + emptyCompact + ".json");
        byte[] rawBytes = JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(raw);
        Files.write(rawPath, rawBytes);
        ObjectNode compact = JsonHashes.mapper().createObjectNode().put("schema", "strategy-evaluator-operating-characteristics-summary/1")
                .set("source_result", JsonHashes.mapper().createObjectNode().put("byte_sha256", JsonHashes.sha256(rawBytes))
                        .put("content_sha256", raw.path("content_sha256").asText()));
        ArrayNode compactRows = compact.putObject("summary").putArray("replications");
        List<String> fields = List.of("cell", "effect_size", "replication", "seed", "status", "decision", "event_count",
                "paired_count", "independent_units", "economic_semantic_sha256", "generator_input_sha256", "disposition", "metrics", "portfolio_summary");
        ensureCanonicalTree(rawRows.get(0), "raw.row0");
        for (JsonNode source : rawRows) {
            ObjectNode projection = JsonHashes.mapper().createObjectNode();
            for (String field : fields) {
                JsonNode value = source.path(field);
                if (value.isMissingNode()) throw new IllegalStateException("raw missing " + field + " fields=" + source.fieldNames());
                projection.set(field, value.deepCopy());
            }
            compactRows.add(projection);
        }
        if (forgeBoolean) {
            ObjectNode forged = (ObjectNode) rawRows.get(100).path("metrics").path("falsifier");
            forged.put("event_p20_pass", !forged.path("event_p20_pass").asBoolean());
            raw.put("content_sha256", JsonHashes.ownHash(raw));
            rawBytes = JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(raw);
            Files.write(rawPath, rawBytes);
            ((ObjectNode) compact.path("source_result")).put("byte_sha256", JsonHashes.sha256(rawBytes))
                    .put("content_sha256", raw.path("content_sha256").asText());
            ObjectNode forgedProjection = JsonHashes.mapper().createObjectNode();
            for (String field : fields) forgedProjection.set(field, rawRows.get(100).path(field).deepCopy());
            compactRows.set(100, forgedProjection);
        }
        if (emptyCompact) compactRows.set(100, JsonHashes.mapper().createObjectNode());
        String debugJson = JsonHashes.mapper().writeValueAsString(compact);
        if (debugJson.contains("NaN") || debugJson.contains("Infinity")) throw new IllegalStateException("non-finite fixture: " + debugJson.substring(0, Math.min(200, debugJson.length())));
        ensureCanonicalTree(compact, "compact");
        compact.put("content_sha256", JsonHashes.ownHash(compact));
        Path compactPath = dir.resolve("diagnosis-compact-" + forgeBoolean + "-" + emptyCompact + ".json");
        Files.writeString(compactPath, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(compact));
        return StrategyOperatingCharacteristicsSuccessorV1.diagnose(JsonHashes.mapper().createObjectNode()
                .put("raw", rawPath.toString()).put("compact", compactPath.toString()));
    }

    private static void ensureCanonicalTree(JsonNode node, String path) {
        if (node.isMissingNode() || node.isPojo() || node.isBinary()) throw new IllegalStateException("bad fixture node " + path + " " + node);
        if (node.isObject()) node.fields().forEachRemaining(entry -> ensureCanonicalTree(entry.getValue(), path + "." + entry.getKey()));
        if (node.isArray()) for (int i = 0; i < node.size(); i++) ensureCanonicalTree(node.get(i), path + "[" + i + "]");
    }
}
