package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Additive diagnosis and pre-outcome boundary for the successor operating-characteristics
 * evaluation.  This class never changes or calls the frozen v004 generator.  Diagnosis
 * reopens retained v004 output, while the attempt ledger records every successor attempt,
 * including failures and incomplete work, before any aggregate can be interpreted.
 */
public final class StrategyOperatingCharacteristicsSuccessorV1 {
    public static final String DIAGNOSIS_SCHEMA = "strategy-evaluator-operating-characteristics-diagnosis/1";
    public static final String PLAN_SCHEMA = "strategy-evaluator-operating-characteristics-successor-plan/1";
    public static final String LEDGER_SCHEMA = "strategy-evaluator-operating-characteristics-attempt-ledger/1";
    private static final String FIXED_EVALUATOR =
            "StrategyFixedBaselineV5+TradeLifecycleV5+StrategyResearchImprovementV1.disposition";
    private static final double Z95 = 1.959963984540054;
    private static final Set<String> COMPACT_ROW_FIELDS = Set.of("cell", "effect_size", "replication", "seed",
            "status", "decision", "event_count", "paired_count", "independent_units",
            "economic_semantic_sha256", "generator_input_sha256", "disposition", "metrics", "portfolio_summary");

    private StrategyOperatingCharacteristicsSuccessorV1() { }

    /**
     * Reconciles raw v004 replications against its display-only compact projection and
     * recomputes Wilson intervals plus each component of the joint decision.  A compact
     * projection can therefore never silently become the statistical source of record.
     */
    public static ObjectNode diagnose(ObjectNode options) {
        String rawPath = text(options, "raw");
        String compactPath = text(options, "compact");
        ObjectNode raw = readObject(rawPath, "raw result");
        ObjectNode compact = readObject(compactPath, "compact result");
        requireOwnHash(raw, "raw result");
        requireOwnHash(compact, "compact result");
        byteBinding(raw, compact, rawPath);

        ArrayNode rawRows = array(raw, "replications");
        ArrayNode compactRows = array(compact.path("summary"), "replications");
        if (rawRows.size() != 200 || compactRows.size() != 200
                || raw.path("planned_replications").asInt(-1) != 200
                || raw.path("replication_count").asInt(-1) != 200) {
            throw new IllegalArgumentException("raw and compact replication counts differ");
        }
        int exactRows = 0;
        int decisionMismatches = 0;
        Map<String, List<JsonNode>> cells = new LinkedHashMap<>();
        for (int i = 0; i < rawRows.size(); i++) {
            JsonNode rawRow = rawRows.get(i);
            JsonNode compactRow = compactRows.get(i);
            if (!COMPACT_ROW_FIELDS.equals(fieldSet(compactRow))) {
                throw new IllegalArgumentException("compact projection has incomplete fields at replication " + i);
            }
            if (!projectedRowEquals(rawRow, compactRow)) {
                throw new IllegalArgumentException("compact projection differs at replication " + i);
            }
            if (!"COMPLETE".equals(rawRow.path("status").asText())
                    || rawRow.path("metrics").path("paired_analysis_insufficient").asBoolean(true)
                    || rawRow.path("metrics").path("independent_market_episode_count").asInt(0) < 30
                    || rawRow.path("metrics").path("paired_tested_cluster_count").asInt(0) < 30) {
                throw new IllegalArgumentException("v004 diagnosis contains an incomplete or inadequate replication");
            }
            validateNumericFalsifier(rawRow);
            exactRows++;
            String key = rawRow.path("cell").asText() + ":" + rawRow.path("effect_size").asDouble();
            cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rawRow);
            if (jointDecision(rawRow) != rawRow.path("decision").asBoolean(false)) decisionMismatches++;
        }

        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", DIAGNOSIS_SCHEMA).put("version", 1)
                .put("status", "COMPLETE_RECONCILIATION")
                .put("scope", "CONDITIONAL_FIXED_BASELINE_SYNTHETIC_DIAGNOSTIC")
                .put("raw_result_path", rawPath).put("compact_result_path", compactPath)
                .put("raw_result_content_sha256", raw.path("content_sha256").asText())
                .put("raw_result_byte_sha256", JsonHashes.sha256(bytes(rawPath)))
                .put("compact_result_content_sha256", compact.path("content_sha256").asText())
                .put("projection_exact", exactRows == rawRows.size())
                .put("replications_reconciled", exactRows)
                .put("decision_mismatches", decisionMismatches)
                .put("joint_rule_recomputed", decisionMismatches == 0)
                .put("shared_physical_evaluator", raw.path("shared_physical_evaluator").asBoolean(false))
                .put("toy_statistic", raw.path("toy_statistic").asBoolean(true))
                .put("promotion_eligible", false).put("activation_authorized", false);
        ArrayNode summaries = result.putArray("cells");
        for (Map.Entry<String, List<JsonNode>> entry : cells.entrySet()) {
            if (entry.getValue().size() != 50) throw new IllegalArgumentException("v004 cell does not contain 50 replications: " + entry.getKey());
            Set<Integer> replications = new LinkedHashSet<>();
            for (JsonNode row : entry.getValue()) if (!replications.add(row.path("replication").asInt(-1))) {
                throw new IllegalArgumentException("duplicate v004 replication slot: " + entry.getKey());
            }
            summaries.add(cellSummary(entry.getKey(), entry.getValue()));
        }
        if (cells.size() != 4) throw new IllegalArgumentException("v004 diagnosis requires exactly four declared cells");
        ObjectNode interpretation = result.putObject("interpretation");
        interpretation.put("monte_carlo_uncertainty", "50 replications per v004 cell leave non-zero Wilson uncertainty; the no-edge result does not establish FPR upper bound <= 10%.");
        interpretation.put("design_power", "The 0.02 effect has low conditional decision power (14/50 joint), so adding repetitions alone cannot repair a weak fixed-stage design. The 0.04 effect is near the target but its lower bound still misses 0.80 (45/50).");
        interpretation.put("joint_rule", "The predeclared joint event-and-paired rule is retained. Component pass counts are reported to expose whether failure comes from event, paired, p-value, or p20 criteria.");
        interpretation.put("scope_boundary", "This is a retained synthetic conditional diagnostic, not adaptive-selection calibration, PIT market evidence, observed fills, promotion, or activation evidence.");
        result.set("build_identity", BuildIdentityService.describe(StrategyOperatingCharacteristicsSuccessorV1.class));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /** Validates an immutable successor plan before outcome generation is opened. */
    public static ObjectNode preflight(ObjectNode plan) {
        validatePlan(plan);
        ObjectNode budget = (ObjectNode) plan.path("resource_budget");
        long replications = plan.path("replications").asLong(-1);
        long cells = plan.path("cell_count").asLong(-1);
        long episodes = plan.path("episodes_per_replication").asLong(-1);
        long series = plan.path("lifecycle_series_per_replication").asLong(-1);
        if (replications <= 0 || cells <= 0 || episodes <= 0 || series <= 0) {
            throw new IllegalArgumentException("successor plan has non-positive workload");
        }
        long expected = replications * cells * series * plan.path("horizon_minutes").asLong(-1);
        if (expected <= 0 || expected != budget.path("expected_generated_minute_bars").asLong(-1)) {
            throw new IllegalArgumentException("successor plan minute-bar budget is not a frozen product");
        }
        long baselineRows = 288_000_000L;
        double workloadRatio = expected / (double) baselineRows;
        long estimatedWall = Math.round(19_641D * workloadRatio);
        long estimatedRss = Math.round(6_638_043_136D * (plan.path("episodes_per_replication").asDouble() / 50D));
        boolean resourcePass = estimatedWall <= budget.path("max_wall_minutes").asLong(-1) * 60L
                && estimatedRss <= budget.path("max_rss_bytes").asLong(-1);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-successor-preflight/1")
                .put("version", 1).put("status", resourcePass ? "READY_PRE_OUTCOME" : "BLOCKED_RESOURCE_ESTIMATE")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("binding_fixed_evaluator", FIXED_EVALUATOR)
                .put("shared_physical_evaluator", true).put("toy_statistic", false)
                .put("outcomes_opened", false).put("promotion_eligible", false)
                .put("activation_authorized", false)
                .put("planned_attempts", replications * cells)
                .put("expected_generated_minute_bars", expected)
                .put("max_wall_minutes", budget.path("max_wall_minutes").asLong(-1))
                .put("max_rss_bytes", budget.path("max_rss_bytes").asLong(-1))
                .put("max_disk_bytes", budget.path("max_disk_bytes").asLong(-1))
                .put("baseline_runtime_seconds", 19_641D)
                .put("baseline_peak_rss_bytes", 6_638_043_136L)
                .put("workload_ratio", workloadRatio)
                .put("estimated_wall_seconds", estimatedWall)
                .put("estimated_peak_rss_bytes", estimatedRss)
                .put("resource_budget_pass", resourcePass)
                .put("resource_estimate_method", "conservative linear DEVELOPMENT estimate from retained v004 full run; must be replaced by a measured exact-geometry prefix before any full run");
        if (!resourcePass) result.put("blocker", "SUCCESSOR_FULL_GEOMETRY_EXCEEDS_DECLARED_8GIB_12H_ENVELOPE");
        result.set("build_identity", BuildIdentityService.describe(StrategyOperatingCharacteristicsSuccessorV1.class));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /**
     * Executes the frozen successor through the production fixed evaluator.  The full
     * schedule is refused when preflight fails its declared machine envelope; the bounded
     * two-replication prefix is available only for executor/resource measurement and uses
     * disjoint prefix seeds recorded in the plan.
     */
    public static ObjectNode run(ObjectNode options) {
        Path ledgerPath = Path.of(text(options, "ledger")).toAbsolutePath().normalize();
        Path runLockPath = ledgerPath.resolveSibling(ledgerPath.getFileName() + ".run.lock");
        try (FileChannel runLockChannel = FileChannel.open(runLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = runLockChannel.lock()) {
            return runUnlocked(options);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot acquire successor runner lock", error);
        }
    }

    private static ObjectNode runUnlocked(ObjectNode options) {
        ObjectNode plan = readObject(text(options, "plan"), "successor plan");
        validatePlan(plan);
        ObjectNode preflight = preflight(plan);
        boolean prefix = options.path("prefix").asBoolean(false);
        if (!prefix && !"READY_PRE_OUTCOME".equals(preflight.path("status").asText())) {
            throw new IllegalArgumentException("successor full run is blocked by its frozen resource preflight");
        }
        ObjectNode baseline = readObject(text(options, "baseline"), "baseline");
        ObjectNode controls = readObject(text(options, "controls"), "controls");
        ObjectNode experiment = readObject(text(options, "experiment"), "experiment");
        requireOwnHash(baseline, "baseline"); requireOwnHash(controls, "controls"); requireOwnHash(experiment, "experiment");
        if (!plan.path("baseline_sha256").asText().equals(baseline.path("content_sha256").asText())
                || !plan.path("control_spec_sha256").asText().equals(controls.path("content_sha256").asText())
                || !plan.path("experiment_sha256").asText().equals(experiment.path("content_sha256").asText())) {
            throw new IllegalArgumentException("successor evaluator inputs do not bind to the frozen plan");
        }
        int replications = prefix ? plan.path("resource_preflight").path("replications").asInt(2)
                : plan.path("replications").asInt();
        int episodes = prefix ? plan.path("resource_preflight").path("episodes_per_replication").asInt(10)
                : plan.path("episodes_per_replication").asInt();
        validateRuntimeExecutor(plan);
        String mode = prefix ? "PREFIX" : "FULL";
        List<CellSpec> cells = new ArrayList<>();
        for (JsonNode node : plan.path("cells")) {
            if (!prefix || "NO_EDGE".equals(node.path("scenario").asText())) {
                cells.add(new CellSpec(node.path("scenario").asText(), node.path("effect_size").asDouble(),
                        (ArrayNode) node.path(prefix ? "prefix_seeds" : "seeds")));
            }
        }
        long maxWall = plan.path("resource_budget").path("max_wall_minutes").asLong() * 60L * 1_000_000_000L;
        long deadline = System.nanoTime() + maxWall;
        long maxRss = plan.path("resource_budget").path("max_rss_bytes").asLong();
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        long started = System.nanoTime(); long peakRss = 0;
        String ledgerOption = text(options, "ledger");
        Path ledgerPath = Path.of(ledgerOption).toAbsolutePath().normalize();
        ObjectNode existingLedger = Files.exists(ledgerPath) ? readObject(ledgerPath.toString(), "successor attempt ledger") : null;
        if (existingLedger != null) validateLedger(plan, existingLedger);
        for (CellSpec cell : cells) {
            for (int replication = 0; replication < replications; replication++) {
                long seed = cell.seeds().get(replication).asLong();
                JsonNode prior = findAttempt(existingLedger, mode, cell.scenario(), cell.effect(), replication);
                if (prior != null) {
                    JsonNode priorRow = prior.path("result_row");
                    if ("COMPLETE".equals(prior.path("status").asText()) || "FAILED".equals(prior.path("status").asText())
                            || "COMPUTE_INCOMPLETE".equals(prior.path("status").asText()) || "INVALID".equals(prior.path("status").asText())) {
                        if (!priorRow.isObject()) throw new IllegalArgumentException("successor ledger attempt lacks durable result row");
                        rows.add(priorRow.deepCopy()); continue;
                    }
                }
                checkSuccessorResource(deadline, maxRss);
                // A prior STARTED reservation is the durable crash marker.  Reuse it on
                // resume rather than appending a second reservation for the same slot.
                if (prior == null) {
                    ObjectNode startedAttempt = attemptFor(plan, mode, cell, replication, seed, "STARTED", null);
                    appendInternal(plan, options, ledgerPath, startedAttempt);
                }
                ObjectNode row = runReplication(plan, baseline, controls, experiment, mode, cell, replication, seed,
                        episodes, deadline, maxRss);
                rows.add(row);
                ObjectNode attempt = attemptFor(plan, mode, cell, replication, seed,
                        row.path("status").asText("COMPUTE_INCOMPLETE"), row);
                appendInternal(plan, options, ledgerPath, attempt);
                existingLedger = readObject(ledgerPath.toString(), "successor attempt ledger");
                peakRss = Math.max(peakRss, StrategyOperatingCharacteristicsV4.probeRssBytes());
            }
        }
        boolean incomplete = false;
        boolean adequate = true;
        for (JsonNode row : rows) {
            if (!"COMPLETE".equals(row.path("status").asText())) incomplete = true;
            if (!statisticallyAdequate(row)) adequate = false;
        }
        boolean measured = !prefix && !incomplete && adequate;
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-successor-result/1")
                .put("version", 1).put("status", incomplete ? "COMPUTE_INCOMPLETE" : (prefix ? "PREFIX_COMPLETE" : "COMPLETE"))
                .put("scope", "CONDITIONAL_FIXED_BASELINE_SYNTHETIC_DIAGNOSTIC")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("preflight_sha256", preflight.path("content_sha256").asText())
                .put("binding_fixed_evaluator", FIXED_EVALUATOR).put("shared_physical_evaluator", true)
                .put("toy_statistic", false).put("promotion_eligible", false).put("activation_authorized", false)
                .put("pit_valid", false).put("observed_exchange_fills", false).put("outcomes_opened", true)
                .put("execution_complete", !incomplete).put("measured", measured)
                .put("prefix_only", prefix).put("replication_count", rows.size())
                .put("runtime_seconds", (System.nanoTime() - started) / 1_000_000_000D)
                .put("peak_rss_bytes", peakRss).put("planned_replications", replications * cells.size());
        result.set("replications", rows);
        result.set("build_identity", BuildIdentityService.describe(StrategyOperatingCharacteristicsSuccessorV1.class));
        result.put("content_sha256", JsonHashes.ownHash(result));
        String output = options.path("out").asText("");
        if (!output.isBlank()) writeImmutable(Path.of(output).toAbsolutePath().normalize(), result);
        return result;
    }

    private static ObjectNode runReplication(ObjectNode plan, ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment, String mode, CellSpec cell, int replication, long seed, int episodes,
            long deadline, long maxRss) {
        Path root;
        try { root = Files.createTempDirectory("strategy-successor-v1-"); }
        catch (IOException error) { throw new IllegalArgumentException("cannot create successor role root", error); }
        try {
            ArrayList<ObjectNode> rawSetup = new ArrayList<>();
            ArrayList<List<ObjectNode>> setupSeriesRows = new ArrayList<>();
            ArrayList<ObjectNode> labels = new ArrayList<>();
            ArrayList<ObjectNode> executions = new ArrayList<>();
            ArrayList<ObjectNode> setupAudit = new ArrayList<>();
            Map<String, StrategyFixedBaselineV5.Role> roles = new LinkedHashMap<>();
            invokeV4("writeAssetRoles", new Class<?>[] {Path.class, Map.class}, root, roles);
            List<EpisodeSpec> specs = buildEpisodes(episodes, seed, replication);
            Map<Integer, Object> common = new LinkedHashMap<>(), controlCommon = new LinkedHashMap<>();
            for (EpisodeSpec episode : specs) {
                checkSuccessorResource(deadline, maxRss);
                common.computeIfAbsent(episode.cluster(), ignored -> invokeV4("commonPath",
                        new Class<?>[] {SplittableRandom.class, int.class}, new SplittableRandom(seed + episode.cluster() * 97_531L), 14_400));
                controlCommon.computeIfAbsent(episode.cluster(), ignored -> invokeV4("commonPath",
                        new Class<?>[] {SplittableRandom.class, int.class}, new SplittableRandom(seed + 7_000_001L + episode.cluster() * 97_531L), 14_400));
                Object eventSetup = invokeV4("buildSetupSeries",
                        new Class<?>[] {String.class, String.class, String.class, long.class, boolean.class, SplittableRandom.class},
                        episode.asset(), episode.symbol(), episode.eventId(), episode.eventDecision(), true,
                        new SplittableRandom(seed + episode.eventIndex() * 101_003L));
                Object controlSetup = invokeV4("buildSetupSeries",
                        new Class<?>[] {String.class, String.class, String.class, long.class, boolean.class, SplittableRandom.class},
                        episode.asset(), episode.symbol(), episode.controlId(), episode.controlDecision(), false,
                        new SplittableRandom(seed + episode.eventIndex() * 101_003L));
                ArrayNode eventBars = (ArrayNode) accessor(eventSetup, "bars");
                ArrayNode controlBars = (ArrayNode) accessor(controlSetup, "bars");
                rawSetup.addAll(toRows(controlBars)); rawSetup.addAll(toRows(eventBars));
                setupSeriesRows.add(toRows(controlBars)); setupSeriesRows.add(toRows(eventBars));
                if (setupAudit.size() < 2) {
                    setupAudit.addAll(toRows((ArrayNode) accessor(controlSetup, "audit")));
                    setupAudit.addAll(toRows((ArrayNode) accessor(eventSetup, "audit")));
                }
                invokeV4("addOutcomeRoles", new Class<?>[] {Path.class, Map.class, List.class, List.class,
                                String.class, String.class, String.class, long.class, double.class, boolean.class, double.class,
                                common.get(episode.cluster()).getClass(), SplittableRandom.class, long.class, long.class},
                        root, roles, labels, executions, episode.asset(), episode.symbol(), episode.eventId(), episode.eventDecision(),
                        ((Number) accessor(eventSetup, "finalClose")).doubleValue(), true, cell.effect(), common.get(episode.cluster()),
                        new SplittableRandom(seed ^ episode.eventIndex() * 17_003L), deadline, maxRss);
                invokeV4("addOutcomeRoles", new Class<?>[] {Path.class, Map.class, List.class, List.class,
                                String.class, String.class, String.class, long.class, double.class, boolean.class, double.class,
                                controlCommon.get(episode.cluster()).getClass(), SplittableRandom.class, long.class, long.class},
                        root, roles, labels, executions, episode.asset(), episode.symbol(), episode.controlId(), episode.controlDecision(),
                        ((Number) accessor(controlSetup, "finalClose")).doubleValue(), false, 0D, controlCommon.get(episode.cluster()),
                        new SplittableRandom(seed ^ (episode.eventIndex() + 10_000L) * 17_003L), deadline, maxRss);
            }
            List<ObjectNode> features = new ArrayList<>();
            for (List<ObjectNode> series : setupSeriesRows) {
                checkSuccessorResource(deadline, maxRss); features.addAll(StrategyFixedBaselineV5.deriveFeatures(series, List.of()));
            }
            ArrayNode signalRows = JsonHashes.mapper().createArrayNode(); rawSetup.forEach(signalRows::add);
            invokeV4("writeRole", new Class<?>[] {Path.class, Map.class, String.class, JsonNode.class}, root, roles, "signal_bars", signalRows);
            ObjectNode marker = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-physical-input/1")
                    .put("status", "SYNTHETIC_DIAGNOSTIC").put("source", "synthetic-ohlcv-successor-generator/1")
                    .put("plan_sha256", plan.path("content_sha256").asText());
            marker.put("content_sha256", JsonHashes.ownHash(marker));
            ObjectNode syntheticInput = JsonHashes.mapper().createObjectNode().put("plan_sha256", plan.path("content_sha256").asText())
                    .put("mode", mode)
                    .put("replication", replication).put("cell", cell.scenario()).put("effect_size", cell.effect()).put("seed", seed)
                    .put("episodes", episodes);
            StrategyFixedBaselineV5.PhysicalInput physical = new StrategyFixedBaselineV5.PhysicalInput(root, "synthetic-successor-v1",
                    JsonHashes.canonicalSha256(syntheticInput), roles, features, labels, executions, marker);
            ObjectNode exposure = JsonHashes.mapper().createObjectNode().put("content_sha256", JsonHashes.sha256("synthetic-successor-exposure:" + seed));
            ObjectNode lineage = JsonHashes.mapper().createObjectNode().put("status", "SYNTHETIC_DIAGNOSTIC")
                    .put("family_history", "successor synthetic only; no canonical exposure append").put("promotion_eligible", false);
            ObjectNode portfolio = JsonHashes.mapper().createObjectNode().put("starting_cash_usdt", 10_000)
                    .put("content_sha256", JsonHashes.sha256("synthetic-successor-portfolio"));
            ObjectNode evaluated = StrategyFixedBaselineV5.evaluate(JsonHashes.mapper().createObjectNode(), baseline, controls,
                    experiment, portfolio, physical, exposure, root.resolve("successor-exposure-head.json"), lineage, "", Double.NaN);
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("cell", cell.scenario()).put("effect_size", cell.effect())
                    .put("replication", replication).put("seed", seed).put("status", evaluated.path("status").asText())
                    .put("decision", "ELIGIBLE".equals(evaluated.path("disposition").path("primary_reason").asText()))
                    .put("event_count", evaluated.path("event_count").asInt()).put("paired_count", evaluated.path("matched_control_count").asInt())
                    .put("independent_units", evaluated.path("independent_market_episode_count").asInt())
                    .put("economic_semantic_sha256", evaluated.path("economic_semantic_sha256").asText())
                    .put("generator_input_sha256", physical.contentSha256());
            row.set("disposition", evaluated.path("disposition").deepCopy()); row.set("metrics", evaluated.path("metrics").deepCopy());
            row.set("portfolio_summary", evaluated.path("portfolio").deepCopy());
            row.set("evaluator_receipt", evaluatorReceipt(evaluated, physical.contentSha256()));
            row.set("representative_setup_events", firstRows(evaluated.path("setup_events"), 2));
            row.set("representative_control_selections", firstRows(evaluated.path("control_selections"), 2));
            return row;
        } catch (RuntimeException error) {
            return JsonHashes.mapper().createObjectNode().put("cell", cell.scenario()).put("effect_size", cell.effect())
                    .put("replication", replication).put("seed", seed).put("status", "COMPUTE_INCOMPLETE")
                    .put("decision", false).put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally { deleteTree(root); }
    }

    /**
     * Appends one immutable attempt record to a content-addressed checkpoint.  The ledger
     * is deliberately independent of the fixed-v004 exposure head: successor synthetic
     * attempts are development exposure and cannot advance a trading family.
     */
    public static ObjectNode recordAttempt(ObjectNode options) {
        ObjectNode plan = readObject(text(options, "plan"), "successor plan");
        validatePlan(plan);
        ObjectNode attempt = readObject(text(options, "attempt"), "successor attempt");
        if ("COMPLETE".equals(attempt.path("status").asText())) {
            throw new IllegalArgumentException("caller-authored COMPLETE attempts are not accepted; use the in-process evaluator runner");
        }
        validateAttempt(plan, attempt, false);
        Path ledgerPath = Path.of(text(options, "ledger")).toAbsolutePath().normalize();
        return appendAttempt(plan, attempt, ledgerPath, false);
    }

    /** Internal append used only after the in-process fixed evaluator has produced a row. */
    private static ObjectNode appendInternal(ObjectNode plan, ObjectNode options, Path ledgerPath,
            ObjectNode attempt) {
        return appendAttempt(plan, attempt, ledgerPath, true);
    }

    private static ObjectNode appendAttempt(ObjectNode plan, ObjectNode attempt, Path ledgerPath,
            boolean internal) {
        validateAttempt(plan, attempt, internal);
        String mode = attempt.path("mode").asText("FULL");
        synchronized (ledgerPath.toString().intern()) {
            Path lockPath = ledgerPath.resolveSibling(ledgerPath.getFileName() + ".lock");
            try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                ObjectNode ledger = Files.exists(ledgerPath)
                        ? readObject(ledgerPath.toString(), "attempt ledger") : newLedger(plan, mode);
                validateLedger(plan, ledger);
                if (!mode.equals(ledger.path("mode").asText())) {
                    throw new IllegalArgumentException("successor attempt mode differs from ledger mode");
                }
                validateSlot(plan, ledger, attempt);
                ArrayNode attempts = (ArrayNode) ledger.withArray("attempts");
                String id = attempt.path("attempt_id").asText();
                for (JsonNode existing : attempts) {
                    if (id.equals(existing.path("attempt_id").asText())
                            && !"STARTED".equals(existing.path("status").asText())) {
                        throw new IllegalArgumentException("successor attempt already finalized: " + id);
                    }
                }
                attempts.add(attempt.deepCopy());
                recount(ledger, attempts, plan);
                ledger.put("content_sha256", JsonHashes.ownHash(ledger));
                writeAtomic(ledgerPath, ledger);
                return ledger;
            } catch (IOException error) {
                throw new IllegalArgumentException("cannot lock successor attempt ledger", error);
            }
        }
    }

    private static void validatePlan(ObjectNode plan) {
        if (!PLAN_SCHEMA.equals(plan.path("schema").asText()) || plan.path("version").asInt(-1) != 1
                || !plan.path("frozen_before_outcomes").asBoolean(false)
                || plan.path("outcomes_opened").asBoolean(true)
                || plan.path("promotion_eligible").asBoolean(true)
                || plan.path("activation_authorized").asBoolean(true)
                || !plan.path("content_sha256").asText().equals(JsonHashes.ownHash(plan))) {
            throw new IllegalArgumentException("successor plan is not immutable and pre-outcome self-bound");
        }
        if (!FIXED_EVALUATOR.equals(plan.path("binding_fixed_evaluator").asText())
                || !plan.path("development_exposure").asBoolean(false)) {
            throw new IllegalArgumentException("successor plan is not bound to the production fixed evaluator");
        }
        requireHash(plan, "predecessor_diagnosis_sha256");
        requireHash(plan, "baseline_sha256");
        requireHash(plan, "control_spec_sha256");
        requireHash(plan, "experiment_sha256");
        requireHash(plan, "lineage_inventory_sha256");
        requireHash(plan, "executor_source_sha256");
        requireHash(plan, "executor_identity_sha256");
        JsonNode cells = plan.path("cells");
        if (!cells.isArray() || cells.size() != plan.path("cell_count").asInt(-1) || cells.size() < 2) {
            throw new IllegalArgumentException("successor plan cells are incomplete");
        }
        if (plan.path("cell_count").asInt(-1) != 4 || plan.path("replications").asInt(-1) != 75
                || plan.path("episodes_per_replication").asInt(-1) != 450
                || plan.path("cluster_count").asInt(-1) != 288
                || plan.path("paired_cluster_count").asInt(-1) != 162
                || plan.path("lifecycle_series_per_replication").asInt(-1) != 900) {
            throw new IllegalArgumentException("successor plan does not preserve the frozen 288-cluster geometry");
        }
        Set<String> cellKeys = new LinkedHashSet<>();
        Set<String> seedKeys = new LinkedHashSet<>();
        Set<String> prefixSeedKeys = new LinkedHashSet<>();
        for (JsonNode cell : cells) {
            if (!cell.isObject() || !cell.path("scenario").isTextual() || !cell.path("effect_size").isNumber()) {
                throw new IllegalArgumentException("successor cell lacks frozen scenario/effect");
            }
            String cellKey = cell.path("scenario").asText() + ":"
                    + java.math.BigDecimal.valueOf(cell.path("effect_size").asDouble()).stripTrailingZeros().toPlainString();
            if (!Set.of("NO_EDGE:0", "PLANTED_EDGE:0", "PLANTED_EDGE:0.02", "PLANTED_EDGE:0.04").contains(cellKey)
                    || !cellKeys.add(cellKey)) throw new IllegalArgumentException("successor cells are not the four frozen scenario/effect slots");
            ArrayNode seeds = array(cell, "seeds");
            if (seeds.size() != plan.path("replications").asInt(-1)) {
                throw new IllegalArgumentException("successor cell seed count differs from replications");
            }
            for (JsonNode seed : seeds) if (!seed.isIntegralNumber() || !seedKeys.add(seed.asText())) {
                throw new IllegalArgumentException("successor seeds are not unique across cells");
            }
            ArrayNode prefixSeeds = array(cell, "prefix_seeds");
            int prefixCount = plan.path("resource_preflight").path("replications").asInt(-1);
            if (prefixSeeds.size() != prefixCount) throw new IllegalArgumentException("successor prefix seed count is not frozen");
            for (JsonNode seed : prefixSeeds) if (!seed.isIntegralNumber()
                    || !prefixSeedKeys.add(seed.asText()) || seedKeys.contains(seed.asText())) {
                throw new IllegalArgumentException("successor prefix seeds overlap or are not unique");
            }
        }
        if (!plan.path("acceptance").path("preserve_v004_standards").asBoolean(false)
                || !plan.path("acceptance").path("confidence_interval").asText().equals("WILSON_95_PERCENT")
                || plan.path("acceptance").path("false_positive_upper_bound").asDouble(-1) != .10D
                || plan.path("acceptance").path("power_lower_bound").asDouble(-1) != .80D) {
            throw new IllegalArgumentException("successor plan relaxes v004 acceptance standards");
        }
        if (!plan.path("incomplete_run_policy").path("count_in_denominator").asBoolean(false)
                || !plan.path("incomplete_run_policy").path("retain_all_attempts").asBoolean(false)
                || !plan.path("stopping").path("fixed_no_optional_stopping").asBoolean(false)) {
            throw new IllegalArgumentException("successor plan does not freeze incomplete/stopping policy");
        }
    }

    private static ObjectNode attemptFor(ObjectNode plan, String mode, CellSpec cell, int replication,
            long seed, String status, ObjectNode row) {
        String effectKey = java.math.BigDecimal.valueOf(cell.effect()).stripTrailingZeros().toPlainString();
        ObjectNode attempt = JsonHashes.mapper().createObjectNode()
                .put("attempt_id", mode + "|" + cell.scenario() + "|" + effectKey + ":" + replication)
                .put("plan_sha256", plan.path("content_sha256").asText()).put("mode", mode)
                .put("scenario", cell.scenario()).put("effect_size", cell.effect())
                .put("replication", replication).put("seed", seed).put("status", status)
                .put("outcomes_opened", true).put("promotion_eligible", false)
                .put("internal_evaluator", true)
                .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText());
        String input = row == null ? expectedInputSha(plan, mode, cell, replication, seed) : row.path("generator_input_sha256").asText("");
        if (!input.matches("[a-f0-9]{64}")) input = expectedInputSha(plan, mode, cell, replication, seed);
        attempt.put("input_sha256", input);
        if (row != null) {
            attempt.set("result_row", row.deepCopy());
            if ("COMPLETE".equals(status)) {
                JsonNode receipt = row.path("evaluator_receipt");
                if (receipt.isObject()) {
                    attempt.set("evaluator_output", receipt.deepCopy());
                    attempt.put("evaluator_output_sha256", receipt.path("content_sha256").asText());
                }
            }
        }
        return attempt;
    }

    private static String expectedInputSha(ObjectNode plan, String mode, CellSpec cell, int replication, long seed) {
        int episodes = "PREFIX".equals(mode) ? plan.path("resource_preflight").path("episodes_per_replication").asInt(10)
                : plan.path("episodes_per_replication").asInt(450);
        ObjectNode input = JsonHashes.mapper().createObjectNode().put("plan_sha256", plan.path("content_sha256").asText())
                .put("mode", mode).put("replication", replication).put("cell", cell.scenario())
                .put("effect_size", cell.effect()).put("seed", seed).put("episodes", episodes);
        return JsonHashes.canonicalSha256(input);
    }

    private static void validateRuntimeExecutor(ObjectNode plan) {
        ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsSuccessorV1.class);
        String actual = identity.path("executable").path("sha256").asText("");
        if (!"KNOWN".equals(identity.path("status").asText())
                || !"JAR".equals(identity.path("executable").path("kind").asText())
                || !actual.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("successor requires a packaged executable identity");
        }
        if (!actual.equals(plan.path("executor_identity_sha256").asText())) {
            throw new IllegalArgumentException("successor packaged executor differs from frozen plan");
        }
    }

    private static void validateAttempt(ObjectNode plan, ObjectNode attempt, boolean internal) {
        if (!attempt.path("attempt_id").isTextual() || attempt.path("attempt_id").asText().isBlank()) {
            throw new IllegalArgumentException("successor attempt_id is required");
        }
        if (!plan.path("content_sha256").asText().equals(attempt.path("plan_sha256").asText())
                || !attempt.path("seed").isIntegralNumber() || !attempt.path("scenario").isTextual()
                || !attempt.path("status").isTextual()
                || !Set.of("STARTED", "COMPLETE", "FAILED", "COMPUTE_INCOMPLETE", "INVALID").contains(attempt.path("status").asText())
                || !Set.of("PREFIX", "FULL").contains(attempt.path("mode").asText())) {
            throw new IllegalArgumentException("successor attempt is not bound or has invalid status");
        }
        if (!plan.path("executor_identity_sha256").asText().equals(attempt.path("executor_identity_sha256").asText())) {
            throw new IllegalArgumentException("successor attempt executor differs from frozen plan");
        }
        requireHash(attempt, "executor_identity_sha256");
        requireHash(attempt, "input_sha256");
        if (!attempt.path("outcomes_opened").asBoolean(false)
                || !attempt.path("promotion_eligible").isBoolean()
                || attempt.path("promotion_eligible").asBoolean(true)) {
            throw new IllegalArgumentException("successor attempt lacks diagnostic outcome boundary");
        }
        if ("COMPLETE".equals(attempt.path("status").asText())) {
            if (!internal || !attempt.path("internal_evaluator").asBoolean(false)) {
                throw new IllegalArgumentException("COMPLETE successor attempts require the in-process evaluator");
            }
            JsonNode output = attempt.path("evaluator_output");
            if (!output.isObject() || !"strategy-evaluator-operating-characteristics-successor-evaluator-receipt/1".equals(output.path("schema").asText())
                    || !"SHARED_FIXED_EVALUATOR_IN_PROCESS".equals(output.path("origin").asText())
                    || !"strategy-fixed-baseline-result/1".equals(output.path("result_schema").asText())
                    || !output.path("content_sha256").asText().equals(JsonHashes.ownHash(output))
                    || !attempt.path("evaluator_output_sha256").asText().equals(output.path("content_sha256").asText())
                    || !output.path("metrics").isObject()
                    || !attempt.path("executor_identity_sha256").asText().equals(output.path("executor_identity_sha256").asText())
                    || !attempt.path("input_sha256").asText().equals(output.path("source_input_sha256").asText())
                    || !attempt.path("result_row").path("generator_input_sha256").asText().equals(output.path("source_input_sha256").asText())
                    || !attempt.path("result_row").path("metrics").equals(output.path("metrics"))) {
                throw new IllegalArgumentException("COMPLETE successor attempt lacks an authoritative evaluator result");
            }
        }
        if (!"STARTED".equals(attempt.path("status").asText()) && !attempt.path("result_row").isObject()) {
            throw new IllegalArgumentException("successor attempt lacks durable result row");
        }
    }

    private static ObjectNode newLedger(ObjectNode plan, String mode) {
        int planned = "PREFIX".equals(mode) ? plan.path("resource_preflight").path("replications").asInt()
                : plan.path("replications").asInt() * plan.path("cell_count").asInt();
        ObjectNode ledger = JsonHashes.mapper().createObjectNode().put("schema", LEDGER_SCHEMA).put("version", 1)
                .put("plan_sha256", plan.path("content_sha256").asText()).put("append_only", true)
                .put("mode", mode)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .set("attempts", JsonHashes.mapper().createArrayNode());
        ledger.put("attempt_count", 0).put("planned_attempts", planned).put("complete_count", 0).put("failed_count", 0)
                .put("incomplete_count", 0).put("invalid_count", 0).put("started_count", 0).put("measured", false)
                .put("content_sha256", JsonHashes.ownHash(ledger));
        return ledger;
    }

    private static void validateLedger(ObjectNode plan, ObjectNode ledger) {
        if (!LEDGER_SCHEMA.equals(ledger.path("schema").asText()) || ledger.path("version").asInt(-1) != 1
                || !ledger.path("append_only").asBoolean(false)
                || !plan.path("content_sha256").asText().equals(ledger.path("plan_sha256").asText())
                || !ledger.path("content_sha256").asText().equals(JsonHashes.ownHash(ledger))) {
            throw new IllegalArgumentException("successor attempt ledger is invalid or bound to another plan");
        }
    }

    private static JsonNode findAttempt(ObjectNode ledger, String mode, String scenario, double effect, int replication) {
        if (ledger == null) return null;
        JsonNode found = null;
        for (JsonNode attempt : ledger.path("attempts")) {
            if (mode.equals(attempt.path("mode").asText()) && scenario.equals(attempt.path("scenario").asText())
                    && Double.compare(effect, attempt.path("effect_size").asDouble(Double.NaN)) == 0
                    && replication == attempt.path("replication").asInt(-1)) found = attempt;
        }
        return found;
    }

    private static void validateSlot(ObjectNode plan, ObjectNode ledger, ObjectNode attempt) {
        String scenario = attempt.path("scenario").asText("");
        int replication = attempt.path("replication").asInt(-1);
        String mode = attempt.path("mode").asText("");
        boolean prefix = "PREFIX".equals(mode);
        int replicationLimit = prefix ? plan.path("resource_preflight").path("replications").asInt(-1)
                : plan.path("replications").asInt(-1);
        if (scenario.isBlank() || !Set.of("PREFIX", "FULL").contains(mode)
                || replication < 0 || replication >= replicationLimit) {
            throw new IllegalArgumentException("successor attempt slot is outside the frozen plan");
        }
        JsonNode cell = null;
        for (JsonNode candidate : plan.path("cells")) {
            if (scenario.equals(candidate.path("scenario").asText(""))
                    && Double.compare(attempt.path("effect_size").asDouble(Double.NaN), candidate.path("effect_size").asDouble(Double.NaN)) == 0) {
                cell = candidate; break;
            }
        }
        String seedField = prefix ? "prefix_seeds" : "seeds";
        if (cell == null || cell.path(seedField).size() <= replication
                || cell.path(seedField).get(replication).asLong() != attempt.path("seed").asLong()) {
            throw new IllegalArgumentException("successor attempt seed does not match frozen scenario slot");
        }
        String effectKey = java.math.BigDecimal.valueOf(attempt.path("effect_size").asDouble()).stripTrailingZeros().toPlainString();
        String expectedId = mode + "|" + scenario + "|" + effectKey + ":" + replication;
        if (!expectedId.equals(attempt.path("attempt_id").asText())) {
            throw new IllegalArgumentException("successor attempt_id must be the frozen scenario slot");
        }
        JsonNode latest = null;
        for (JsonNode existing : ledger.path("attempts")) {
            if (mode.equals(existing.path("mode").asText()) && scenario.equals(existing.path("scenario").asText())
                    && Double.compare(attempt.path("effect_size").asDouble(Double.NaN), existing.path("effect_size").asDouble(Double.NaN)) == 0
                    && replication == existing.path("replication").asInt(-1)) {
                latest = existing;
            }
        }
        if (latest != null && !("STARTED".equals(latest.path("status").asText())
                && !"STARTED".equals(attempt.path("status").asText()))) {
            throw new IllegalArgumentException("successor scenario/effect slot already finalized or reserved");
        }
    }

    private static void recount(ObjectNode ledger, ArrayNode attempts, ObjectNode plan) {
        int complete = 0, failed = 0, incomplete = 0, invalid = 0, started = 0;
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        for (JsonNode attempt : attempts) {
            latest.put(attempt.path("attempt_id").asText(), attempt);
            switch (attempt.path("status").asText()) {
                case "STARTED" -> started++;
                case "COMPLETE" -> complete++;
                case "FAILED" -> failed++;
                case "COMPUTE_INCOMPLETE" -> incomplete++;
                case "INVALID" -> invalid++;
                default -> throw new IllegalArgumentException("unknown attempt status");
            }
        }
        int planned = "PREFIX".equals(ledger.path("mode").asText()) ? plan.path("resource_preflight").path("replications").asInt()
                : plan.path("replications").asInt() * plan.path("cell_count").asInt();
        int finalized = 0; boolean allComplete = true; boolean allAdequate = true;
        for (JsonNode attempt : latest.values()) {
            if (!"STARTED".equals(attempt.path("status").asText())) finalized++;
            if (!Set.of("COMPLETE").contains(attempt.path("status").asText())) allComplete = false;
            if (!statisticallyAdequate(attempt.path("result_row"))) allAdequate = false;
        }
        ledger.put("planned_attempts", planned).put("attempt_count", attempts.size()).put("complete_count", complete)
                .put("failed_count", failed).put("incomplete_count", incomplete).put("invalid_count", invalid)
                .put("started_count", started)
                .put("measured", "FULL".equals(ledger.path("mode").asText()) && finalized == planned && allComplete && allAdequate);
    }

    private static boolean statisticallyAdequate(JsonNode row) {
        return row != null && row.isObject() && "COMPLETE".equals(row.path("status").asText())
                && !row.path("metrics").path("paired_analysis_insufficient").asBoolean(true)
                && row.path("metrics").path("independent_market_episode_count").asInt(0) >= 30
                && row.path("metrics").path("paired_tested_cluster_count").asInt(0) >= 30;
    }

    private static ObjectNode cellSummary(String key, List<JsonNode> rows) {
        int decisions = 0, incomplete = 0, invalid = 0, insufficient = 0;
        int eventP20 = 0, pairedP20 = 0, eventP = 0, pairedP = 0;
        for (JsonNode row : rows) {
            String status = row.path("status").asText();
            if ("COMPUTE_INCOMPLETE".equals(status)) incomplete++;
            if ("INVALID".equals(status)) invalid++;
            if (row.path("metrics").path("paired_analysis_insufficient").asBoolean(false)) insufficient++;
            if (row.path("decision").asBoolean(false)) decisions++;
            JsonNode f = row.path("metrics").path("falsifier");
            if (f.path("event_p20_pass").asBoolean(false)) eventP20++;
            if (f.path("paired_p20_pass").asBoolean(false)) pairedP20++;
            if (f.path("event_p_value_pass").asBoolean(false)) eventP++;
            if (f.path("paired_p_value_pass").asBoolean(false)) pairedP++;
        }
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("cell", key)
                .put("planned", rows.size()).put("decisions", decisions).put("incomplete", incomplete)
                .put("invalid", invalid).put("insufficient", insufficient)
                .put("event_p20_passes", eventP20).put("paired_p20_passes", pairedP20)
                .put("event_p_value_passes", eventP).put("paired_p_value_passes", pairedP)
                .put("joint_decision_passes", decisions).put("measured", incomplete == 0 && invalid == 0 && insufficient == 0);
        if (out.path("measured").asBoolean(false)) {
            double[] bounds = wilson(decisions, rows.size());
            out.put("decision_rate", decisions / (double) rows.size()).put("wilson95_lower", bounds[0]).put("wilson95_upper", bounds[1]);
        } else {
            out.putNull("decision_rate").putNull("wilson95_lower").putNull("wilson95_upper");
        }
        return out;
    }

    private static ObjectNode evaluatorReceipt(ObjectNode evaluated, String sourceInputSha256) {
        ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsSuccessorV1.class);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-successor-evaluator-receipt/1")
                .put("version", 1).put("origin", "SHARED_FIXED_EVALUATOR_IN_PROCESS")
                .put("result_schema", evaluated.path("schema").asText())
                .put("result_content_sha256", evaluated.path("content_sha256").asText())
                .put("economic_semantic_sha256", evaluated.path("economic_semantic_sha256").asText())
                .put("status", evaluated.path("status").asText())
                .put("executor_identity_sha256", identity.path("executable").path("sha256").asText())
                .put("source_input_sha256", sourceInputSha256);
        receipt.set("metrics", evaluated.path("metrics").deepCopy());
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    private static void validateNumericFalsifier(JsonNode row) {
        JsonNode falsifier = row.path("metrics").path("falsifier");
        double floor = falsifier.path("p20_expectancy_r_min").asDouble(Double.NaN);
        double ceiling = falsifier.path("max_statistic_p_value").asDouble(Double.NaN);
        if (!Double.isFinite(floor) || !Double.isFinite(ceiling)) throw new IllegalArgumentException("v004 falsifier cutoffs are missing");
        checkPass(falsifier, "event_p20_pass", falsifier.path("event_p20_expectancy_r").asDouble(Double.NaN) >= floor);
        checkPass(falsifier, "paired_p20_pass", falsifier.path("paired_p20_expectancy_r").asDouble(Double.NaN) >= floor);
        checkPass(falsifier, "event_p_value_pass", falsifier.path("event_statistic_p_value").asDouble(Double.NaN) <= ceiling);
        checkPass(falsifier, "paired_p_value_pass", falsifier.path("paired_statistic_p_value").asDouble(Double.NaN) <= ceiling);
    }

    private static void checkPass(JsonNode node, String field, boolean expected) {
        if (!node.path(field).isBoolean() || node.path(field).asBoolean() != expected) {
            throw new IllegalArgumentException("v004 asserted boolean disagrees with numeric metric: " + field);
        }
    }

    private static Object accessor(Object value, String name) {
        try {
            Method method = value.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("successor generator cannot read v004 " + name, error);
        }
    }

    private static Object invokeV4(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = StrategyOperatingCharacteristicsV4.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("successor v004 generator invocation failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("successor generator cannot bind v004 method " + name, error);
        }
    }

    private static List<ObjectNode> toRows(ArrayNode rows) {
        List<ObjectNode> result = new ArrayList<>();
        rows.forEach(row -> result.add((ObjectNode) row));
        return result;
    }

    private static ArrayNode firstRows(JsonNode value, int limit) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        if (value != null && value.isArray()) {
            for (int index = 0; index < Math.min(limit, value.size()); index++) result.add(value.get(index).deepCopy());
        }
        return result;
    }

    private static List<EpisodeSpec> buildEpisodes(int requested, long seed, int replication) {
        int clusters = requested == 450 ? 288 : requested == 10 ? 8 : 0;
        int pairedClusters = requested == 450 ? 162 : requested == 10 ? 2 : 0;
        if (clusters <= 0) throw new IllegalArgumentException("successor episode count is outside frozen geometry");
        long origin = java.time.Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
        long seedOffsetDays = Math.floorMod(seed, 365L);
        List<EpisodeSpec> result = new ArrayList<>();
        int eventIndex = 0;
        for (int cluster = 0; cluster < clusters; cluster++) {
            long eventDecision = origin + (60L + seedOffsetDays + cluster * 42L) * 86_400_000L;
            int members = cluster < pairedClusters ? 2 : 1;
            for (int member = 0; member < members; member++) {
                String asset = members == 2 ? (member == 0 ? "btc" : "eth") : V4_ASSETS[(cluster + member) % V4_ASSETS.length];
                String symbol = asset.toUpperCase() + "USDT";
                String prefix = "d-successor-r" + replication + "-c" + cluster + "-" + member;
                result.add(new EpisodeSpec(cluster, eventIndex++, asset, symbol, prefix + "-e", prefix + "-c",
                        eventDecision, eventDecision - 21L * 86_400_000L));
            }
        }
        return result;
    }

    private static final String[] V4_ASSETS = {"btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave"};

    private static void checkSuccessorResource(long deadline, long maxRss) {
        if (System.nanoTime() > deadline) throw new IllegalArgumentException("RESOURCE_WALL_DEADLINE_EXCEEDED");
        long rss = StrategyOperatingCharacteristicsV4.probeRssBytes();
        if (rss < 0) throw new IllegalArgumentException("RESOURCE_RSS_UNAVAILABLE");
        if (rss > maxRss) throw new IllegalArgumentException("RESOURCE_RSS_BUDGET_EXCEEDED");
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static boolean jointDecision(JsonNode row) {
        JsonNode f = row.path("metrics").path("falsifier");
        return f.path("event_p20_pass").asBoolean(false) && f.path("paired_p20_pass").asBoolean(false)
                && f.path("event_p_value_pass").asBoolean(false) && f.path("paired_p_value_pass").asBoolean(false);
    }

    private static double[] wilson(int successes, int total) {
        if (total <= 0) return new double[] {Double.NaN, Double.NaN};
        double n = total, p = successes / n, z2 = Z95 * Z95, denominator = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denominator;
        double radius = Z95 * Math.sqrt((p * (1 - p) / n) + z2 / (4 * n * n)) / denominator;
        return new double[] {Math.max(0, center - radius), Math.min(1, center + radius)};
    }

    private static boolean projectedRowEquals(JsonNode raw, JsonNode compact) {
        if (!compact.isObject() || !raw.isObject()) return false;
        var fields = compact.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!raw.has(field) || !raw.get(field).equals(compact.get(field))) return false;
        }
        return true;
    }

    private static Set<String> fieldSet(JsonNode value) {
        Set<String> fields = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static void byteBinding(ObjectNode raw, ObjectNode compact, String rawPath) {
        JsonNode source = compact.path("source_result");
        if (!source.path("byte_sha256").asText().equals(JsonHashes.sha256(bytes(rawPath)))
                || !source.path("content_sha256").asText().equals(raw.path("content_sha256").asText())) {
            throw new IllegalArgumentException("compact source binding does not match raw result");
        }
    }

    private static ArrayNode array(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isArray()) throw new IllegalArgumentException("required array missing: " + field);
        return (ArrayNode) value;
    }

    private static void requireHash(JsonNode value, String field) {
        if (!value.path(field).asText().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("missing or invalid hash: " + field);
        }
    }

    private static void requireOwnHash(ObjectNode value, String label) {
        if (!value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))) {
            throw new IllegalArgumentException(label + " content hash is invalid");
        }
    }

    private static String text(ObjectNode value, String field) {
        String result = value.path(field).asText("");
        if (result.isBlank()) throw new IllegalArgumentException("missing --" + field);
        return result;
    }

    private static byte[] bytes(String path) {
        try { return Files.readAllBytes(Path.of(path)); }
        catch (IOException error) { throw new IllegalArgumentException("cannot read " + path, error); }
    }

    private static ObjectNode readObject(String path, String label) {
        try {
            JsonNode value = JsonHashes.mapper().readTree(bytes(path));
            if (!value.isObject()) throw new IllegalArgumentException(label + " must be an object");
            return (ObjectNode) value;
        } catch (IOException error) { throw new IllegalArgumentException("cannot parse " + label, error); }
    }

    private static void writeAtomic(Path path, ObjectNode value) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
            Files.writeString(temporary, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) { throw new IllegalArgumentException("cannot atomically write attempt ledger", error); }
    }

    private static void writeImmutable(Path path, ObjectNode value) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                ObjectNode prior = readObject(path.toString(), "immutable successor result");
                if (!prior.path("content_sha256").asText().equals(value.path("content_sha256").asText())) {
                    throw new IllegalArgumentException("immutable successor result already exists with different content");
                }
                return;
            }
            String serialized = JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
            try {
                Files.writeString(path, serialized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                ObjectNode prior = readObject(path.toString(), "immutable successor result");
                if (!prior.path("content_sha256").asText().equals(value.path("content_sha256").asText())) {
                    throw new IllegalArgumentException("immutable successor result was concurrently created with different content");
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot create immutable successor result", error);
        }
    }

    private record CellSpec(String scenario, double effect, ArrayNode seeds) { }
    private record EpisodeSpec(int cluster, int eventIndex, String asset, String symbol,
            String eventId, String controlId, long eventDecision, long controlDecision) { }
}
