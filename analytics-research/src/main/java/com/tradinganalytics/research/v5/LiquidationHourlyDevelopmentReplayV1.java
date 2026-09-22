package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/** Hourly OHLC approximation adapter for frozen v003 DEVELOPMENT research only. */
public final class LiquidationHourlyDevelopmentReplayV1 {
    public static final String INPUT_SCHEMA = "liquidation-exploratory-input-manifest/1";
    public static final String PREFLIGHT_SCHEMA = "liquidation-exploratory-input-preflight/1";
    public static final String RESULT_SCHEMA = "liquidation-hourly-exploratory-result/1";
    public static final long HOUR_MS = 3_600_000L;
    private static final long FOUR_HOURS_MS = 4L * HOUR_MS;
    private static final List<String> LEGACY_ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");
    private static final List<String> V004_ASSETS = List.of("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX");
    private static final List<String> V003_VARIANTS = List.of("CORE_ONE_ENTRY", "STAGED_NO_MACRO", "STAGED_MACRO");
    private static final List<String> V005_VARIANTS = List.of("BASELINE_V004", "POST_SHOCK_ENTRY", "H4_STRUCTURAL_STOP",
            "REFRESHED_STAGING", "DAILY_RSI_CONTEXT", "DAILY_MA_CONTEXT", "DAILY_BOTH_CONTEXT");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final long[] RESPONSE_HORIZONS_DAYS = {1, 3, 7};

    private LiquidationHourlyDevelopmentReplayV1() {}

    /** Coverage, syntax, hashes and source gaps only. This method computes no strategy outcome. */
    public static ObjectNode preflight(ObjectNode options) {
        Path manifestPath = requiredPath(options, "input");
        Input input = loadInput(manifestPath);
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", PREFLIGHT_SCHEMA)
                .put("status", input.preflightBlockers.isEmpty() ? "INPUTS_REOPENED_DEVELOPMENT_ONLY" : "INPUT_COVERAGE_LIMITED")
                .put("historical_outcomes_computed", false)
                .put("input_manifest", manifestPath.toString())
                .put("input_manifest_byte_sha256", sha256(manifestPath));
        ObjectNode series = result.putObject("series");
        input.coverage.fields().forEachRemaining(entry -> series.set(entry.getKey(), entry.getValue().deepCopy()));
        ArrayNode blockers = result.putArray("blockers"); input.preflightBlockers.forEach(blockers::add);
        ArrayNode fileHashes = result.putArray("source_files");
        input.fileHashes.forEach((name, hash) -> fileHashes.addObject().put("path", name).put("sha256", hash));
        result.put("content_sha256", JsonHashes.ownHash(result));
        writeOptionalOutput(options, result);
        return result;
    }

    /** Runs only against frozen v003 policy, frozen source bytes, and an immutable executable JAR. */
    public static ObjectNode run(ObjectNode options) {
        Instant started = Instant.now();
        Path inputPath = requiredPath(options, "input");
        Path policyPath = requiredPath(options, "policy");
        Path policyFreezePath = requiredPath(options, "policy_freeze");
        Path dataFreezePath = requiredPath(options, "data_freeze");
        String expectedPolicyBytes = requiredHash(options, "expected_policy_byte_sha256");
        String expectedInputBytes = requiredHash(options, "expected_input_byte_sha256");
        String expectedDataFreezeBytes = requiredHash(options, "expected_data_freeze_byte_sha256");
        String expectedExecutorBytes = requiredHash(options, "expected_executor_byte_sha256");
        String policyBytes = sha256(policyPath), inputBytes = sha256(inputPath), dataFreezeBytes = sha256(dataFreezePath);
        if (!policyBytes.equals(expectedPolicyBytes)) throw failure("frozen v003 policy byte hash differs from the supplied pre-run hash");
        if (!inputBytes.equals(expectedInputBytes)) throw failure("input manifest byte hash differs from its pre-run freeze");
        if (!dataFreezeBytes.equals(expectedDataFreezeBytes)) throw failure("data freeze byte hash differs from its pre-run freeze");
        Path executable = executableJarPath();
        String executorBytes = sha256(executable);
        if (!executorBytes.equals(expectedExecutorBytes)) throw failure("running executable JAR byte hash differs from its immutable run receipt");
        ObjectNode policy = readObject(policyPath);
        verifyPolicy(policy, policyBytes, policyFreezePath);
        ObjectNode dataFreeze = readObject(dataFreezePath);
        Input input = loadInput(inputPath);
        if ("liquidation-exploratory-v005".equals(policy.path("id").asText()) && !input.dailyContextWarmupVerified) {
            throw failure("v005 requires its exact verified supplemental daily-context warmup bundle before simulation");
        }
        verifyDataFreeze(input, dataFreeze, dataFreezePath.getParent());
        if (!input.preflightBlockers.isEmpty()) throw failure("required source-input gaps prevent this run: " + String.join("; ", input.preflightBlockers));
        Path runs = requiredPath(options, "run_root");
        String rerunReason = options.path("rerun_reason").asText("").trim();
        if (Files.exists(runs) && hasRunDirectories(runs) && rerunReason.isBlank()) {
            throw failure("a new historical run needs a fresh directory and a documented rerun_reason");
        }
        String runId = started.toString().replace(':', '-').replace('.', '-') + "-" + UUID.randomUUID();
        Path runDir = runs.resolve(runId);
        try {
            Files.createDirectories(runs);
            Files.createDirectory(runDir);
        } catch (IOException e) {
            throw failure("cannot reserve a fresh output directory: " + e.getMessage());
        }
        ObjectNode attempt = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-run-attempt/1")
                .put("run_id", runId).put("status", "RUNNING")
                .put("started_at", started.toString()).put("policy_byte_sha256", policyBytes)
                .put("input_manifest_byte_sha256", inputBytes).put("data_freeze_byte_sha256", dataFreezeBytes)
                .put("executor_jar_byte_sha256", executorBytes).put("variant_count", policy.path("variants").size())
                .put("rerun_reason", rerunReason);
        attempt.set("source_hashes", sourceHashRows(input.fileHashes));
        try {
            writeNewDurable(runDir.resolve("attempt.json"), attempt);
        } catch (IOException e) {
            throw failure("cannot durably write run attempt receipt: " + e.getMessage());
        }

        try {
            ObjectNode result = replay(input, policy);
            Instant finished = Instant.now();
            result.put("run_id", runId).put("started_at", started.toString()).put("finished_at", finished.toString())
                    .put("elapsed_millis", Duration.between(started, finished).toMillis())
                    .put("policy_byte_sha256", policyBytes).put("input_manifest_byte_sha256", inputBytes)
                    .put("data_freeze_byte_sha256", dataFreezeBytes).put("executor_jar_byte_sha256", executorBytes)
                    .put("rerun_reason", rerunReason).put("run_directory", runDir.toString());
            result.put("content_sha256", JsonHashes.ownHash(result));
            writeNewDurable(runDir.resolve("result.json"), result);
            ObjectNode completed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-run-completion/1")
                    .put("run_id", runId).put("status", "COMPLETED_DEVELOPMENT_ONLY")
                    .put("finished_at", finished.toString()).put("result_byte_sha256", sha256(runDir.resolve("result.json")))
                    .put("content_sha256", "");
            completed.put("content_sha256", JsonHashes.ownHash(completed));
            writeNewDurable(runDir.resolve("completion.json"), completed);
            return result;
        } catch (RuntimeException | IOException error) {
            ObjectNode failed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-run-failure/1")
                    .put("run_id", runId).put("status", "FAILED_BEFORE_COMPLETION")
                    .put("failed_at", Instant.now().toString()).put("error", error.getMessage() == null ? error.getClass().getName() : error.getMessage());
            failed.put("content_sha256", JsonHashes.ownHash(failed));
            try { writeNewDurable(runDir.resolve("failure.json"), failed); }
            catch (IOException | RuntimeException ignored) { /* Preserve the original failure. */ }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw failure(error.getMessage());
        }
    }

    private static void verifyPolicy(ObjectNode policy, String actualBytes, Path freezePath) {
        ObjectNode manifest = readObject(freezePath);
        if (!"liquidation-exploratory-freeze/1".equals(manifest.path("schema").asText())
                || manifest.path("outcomes_viewed").asBoolean(true)
                || !actualBytes.equals(manifest.path("files").path("exploratory-policy.json").asText())) {
            throw failure("policy freeze manifest is invalid, outcome-exposed, or bound to different policy bytes");
        }
        String policyId = policy.path("id").asText("");
        List<String> expectedAssets = switch (policyId) {
            case "liquidation-exploratory-v003" -> LEGACY_ASSETS;
            case "liquidation-exploratory-v004" -> V004_ASSETS;
            case "liquidation-exploratory-v005" -> V004_ASSETS;
            default -> List.of();
        };
        if (!"liquidation-exploratory-policy/1".equals(policy.path("schema").asText())
                || expectedAssets.isEmpty() || !policyAssetOrder(policy).equals(expectedAssets)
                || !"DEVELOPMENT".equals(policy.path("evidence_phase").asText())
                || policy.path("promotion_allowed").asBoolean(true)
                || policy.path("statistics").path("minimum_groups_to_run").asInt(-1) != 0
                || policy.path("statistics").path("reference_minimum_groups").asInt(-1) != 30
                || policy.path("execution").path("funding").asText("").equals("0")) {
            throw failure("frozen policy does not match a supported exploratory-only asset boundary");
        }
        List<String> expectedVariants = switch (policyId) {
            case "liquidation-exploratory-v003", "liquidation-exploratory-v004" -> V003_VARIANTS;
            case "liquidation-exploratory-v005" -> V005_VARIANTS;
            default -> List.of();
        };
        if (!expectedVariants.equals(orderedTextArray(policy.path("variants")))) {
            throw failure("frozen policy variant order differs from its immutable inventory");
        }
        Path repository = repositoryRoot();
        boolean v005 = "liquidation-exploratory-v005".equals(policyId);
        if (v005 && !actualBytes.equals(sha256(repository.resolve("docs/research/liquidation-exploratory-v005/exploratory-policy.json")))) {
            throw failure("v005 policy bytes do not equal the repository's frozen source policy");
        }
        Path parentPolicyPath = v005
                ? repository.resolve("docs/research/liquidation-exploratory-v004/frozen-precommit.json")
                : repository.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json");
        ObjectNode parent = readObject(parentPolicyPath);
        String parentId = v005 ? "liquidation-exploratory-v004" : "liquidation-daily-stress-v002";
        String actualParentId = parent.path("precommit_id").asText();
        if (!parentId.equals(actualParentId)
                || !policy.path("parent_precommit").path("byte_sha256").asText().equals(sha256(parentPolicyPath))
                || !policy.path("parent_precommit").path("content_sha256").asText().equals(parent.path("content_sha256").asText())) {
            throw failure("policy is detached from its immutable predecessor");
        }
        if ("liquidation-exploratory-v004".equals(policyId)) {
            JsonNode audit = policy.path("entry_rule_audit");
            if (!audit.isObject() || !audit.path("diagnostic_only").asBoolean(false)
                    || audit.path("rule_changes").asBoolean(true) || audit.path("outcome_optimization").asBoolean(true)) {
                throw failure("v004 entry-rule audit must be diagnostic-only and cannot change or optimize the frozen rules");
            }
            JsonNode predecessor = policy.path("predecessor_experiment");
            Path v003Root = repository.resolve("docs/research/liquidation-exploratory-v003");
            if (!"liquidation-exploratory-v003".equals(predecessor.path("id").asText())
                    || !predecessor.path("outcomes_exposed").asBoolean(false)
                    || !sha256(v003Root.resolve("exploratory-policy.json")).equals(predecessor.path("policy_byte_sha256").asText())
                    || !sha256(v003Root.resolve("FREEZE-MANIFEST.json")).equals(predecessor.path("freeze_byte_sha256").asText())
                    || !sha256(v003Root.resolve("results.json")).equals(predecessor.path("compact_results_byte_sha256").asText())) {
                throw failure("v004 predecessor binding does not match exposed v003 policy, freeze and compact results");
            }
        }
        if (v005) {
            JsonNode audit = policy.path("entry_rule_audit");
            JsonNode predecessor = policy.path("predecessor_experiment");
            Path v004Root = repository.resolve("docs/research/liquidation-exploratory-v004");
            if (!audit.isObject() || !audit.path("diagnostic_only").asBoolean(false)
                    || !audit.path("rule_changes").asBoolean(false) || audit.path("outcome_optimization").asBoolean(true)
                    || !"liquidation-exploratory-v004".equals(predecessor.path("id").asText())
                    || !predecessor.path("outcomes_exposed").asBoolean(false)
                    || !sha256(v004Root.resolve("exploratory-policy.json")).equals(predecessor.path("policy_byte_sha256").asText())
                    || !sha256(v004Root.resolve("FREEZE-MANIFEST.json")).equals(predecessor.path("freeze_byte_sha256").asText())
                    || !sha256(v004Root.resolve("results.json")).equals(predecessor.path("compact_results_byte_sha256").asText())) {
                throw failure("v005 entry-rule audit or exposed v004 predecessor binding is invalid");
            }
            if (!policy.path("daily_context").path("warmup").asText().contains("Apr1,2022-Aug11,2022")) {
                throw failure("v005 daily-context warmup interval differs from the frozen supplemental scope");
            }
            verifyV005RuleDefinitions(policy);
        }
    }

    private static void verifyV005RuleDefinitions(ObjectNode policy) {
        JsonNode definitions = policy.path("variant_definitions");
        if (!definitions.isObject() || definitions.size() != V005_VARIANTS.size()) {
            throw failure("v005 policy must define every fixed profile exactly once");
        }
        List<String> predecessors = Arrays.asList(null, "BASELINE_V004", "POST_SHOCK_ENTRY", "H4_STRUCTURAL_STOP",
                "REFRESHED_STAGING", "REFRESHED_STAGING", "REFRESHED_STAGING");
        List<String> stages = List.of("CORE_PREMISE", "ENTRY_TIMING", "RISK_LIFECYCLE", "RISK_LIFECYCLE",
                "INDEPENDENT_CONTEXT", "INDEPENDENT_CONTEXT", "INDEPENDENT_CONTEXT");
        for (int i = 0; i < V005_VARIANTS.size(); i++) {
            String id = V005_VARIANTS.get(i);
            JsonNode definition = definitions.path(id);
            LiquidationStructureRouterV1.RuleConfig config = LiquidationStructureRouterV1.RuleConfig.forProfile(id);
            if (!definition.isObject() || !stages.get(i).equals(definition.path("stage").asText())
                    || (predecessors.get(i) == null ? !definition.path("predecessor_variant").isNull()
                            : !predecessors.get(i).equals(definition.path("predecessor_variant").asText()))
                    || definition.path("post_shock_entry").asBoolean(!config.initialEntryRule().equals(
                            LiquidationStructureRouterV1.InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING))
                            != config.initialEntryRule().equals(LiquidationStructureRouterV1.InitialEntryRule.POST_SHOCK_CONFIRMED_H4_SWING)
                    || definition.path("h4_initial_stop").asBoolean(false)
                            != config.initialStopRule().equals(LiquidationStructureRouterV1.InitialStopRule.H4_THREE_BAR)
                    || definition.path("refresh_invalidated_pivot").asBoolean(false)
                            != config.pivotRefreshRule().equals(LiquidationStructureRouterV1.PivotRefreshRule.REFRESH_AFTER_STOP_INVALIDATION)
                    || definition.path("daily_rsi_context").asBoolean(false) != config.dailyRsiAdditionGate()
                    || definition.path("daily_sma200_context").asBoolean(false) != config.dailySma200AdditionGate()
                    || !definition.path("sp500_addition_gate").asBoolean(false)) {
                throw failure("v005 router implementation differs from frozen profile definition: " + id);
            }
        }
        if (policy.path("daily_context").path("rsi").path("period_days").asInt(-1) != 14
                || policy.path("daily_context").path("sma").path("period_days").asInt(-1) != 200) {
            throw failure("v005 daily RSI/SMA implementation periods differ from the frozen contract");
        }
    }

    private static void verifyDataFreeze(Input input, ObjectNode freeze, Path freezeRoot) {
        if (!"liquidation-exploratory-input-freeze/1".equals(freeze.path("schema").asText())
                || !freeze.path("files").isObject() || freezeRoot == null) {
            throw failure("data freeze must list immutable input bytes");
        }
        if (!JsonHashes.ownHash(freeze).equals(freeze.path("content_sha256").asText())) {
            throw failure("data freeze content hash does not match its canonical contents");
        }
        JsonNode files = freeze.path("files");
        String manifestRelative = "input-manifest.json";
        JsonNode manifestHash = files.path(manifestRelative);
        if (!manifestHash.isObject() || !input.manifestByteSha256.equals(manifestHash.path("sha256").asText())) {
            throw failure("data freeze does not bind the exact input manifest bytes");
        }
        files.fields().forEachRemaining(entry -> {
            JsonNode frozen = entry.getValue();
            long expectedBytes = frozen.path("bytes").asLong(-1);
            String expectedSha = frozen.path("sha256").asText("");
            if (!frozen.isObject() || expectedBytes < 0 || !expectedSha.matches("[0-9a-f]{64}")) {
                throw failure("data freeze contains an invalid byte receipt: " + entry.getKey());
            }
            Path file = safeResolve(freezeRoot, entry.getKey());
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || expectedBytes != fileSize(file)
                    || !expectedSha.equals(sha256(file))) {
                throw failure("source changed or disappeared after byte freeze: " + entry.getKey());
            }
        });
        for (Map.Entry<String, String> entry : input.fileHashes.entrySet()) {
            JsonNode frozen = files.path(entry.getKey());
            if (!frozen.isObject() || !entry.getValue().equals(frozen.path("sha256").asText())) {
                throw failure("data freeze is missing or differs from loaded source bytes: " + entry.getKey());
            }
        }
    }

    private static ObjectNode replay(Input input, ObjectNode policy) {
        if ("liquidation-exploratory-v005".equals(policy.path("id").asText()) && !input.dailyContextWarmupVerified) {
            throw failure("v005 cannot simulate without its verified supplemental context bundle");
        }
        if (!policyAssetOrder(policy).equals(input.assets)) {
            throw failure("policy asset order must exactly match the normalized input manifest asset order");
        }
        if (!policyAssetOrder(policy).equals(orderedTextArray(policy.path("account").path("asset_tie_order")))) {
            throw failure("policy account asset tie order must equal the frozen asset order");
        }
        Map<String, VariantResult> variants = new LinkedHashMap<>();
        ObjectNode costSensitivity = JsonHashes.mapper().createObjectNode();
        for (JsonNode raw : policy.path("variants")) {
            String id = raw.asText();
            VariantResult baseline = simulateVariant(input, policy, id, 1.0);
            variants.put(id, baseline);
            VariantResult doubled = simulateVariant(input, policy, id, 2.0);
            ObjectNode sensitivity = accountSummary(doubled.account);
            sensitivity.put("fee_multiplier", 2).put("slippage_multiplier", 2).put("same_predeclared_strategy_variant", id)
                    .set("funnel_counts", doubled.funnel.toJson());
            sensitivity.put("status", "DEVELOPMENT_ONLY");
            sensitivity.set("pnl_reconciliation", pnlReconciliation(doubled.account));
            sensitivity.put("content_sha256", JsonHashes.ownHash(sensitivity));
            costSensitivity.set(id, sensitivity);
        }
        return assembleResult(input, policy, variants, costSensitivity);
    }

    private static VariantResult simulateVariant(Input input, ObjectNode policy, String variantId, double costMultiplier) {
        String decisionStartText = policy.path("decision_start").asText();
        String decisionEndText = policy.path("decision_end_exclusive").asText();
        LiquidationStructureRouterV1.MacroGatePolicy macroPolicy = "STAGED_NO_MACRO".equals(variantId)
                ? LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY
                : LiquidationStructureRouterV1.MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION;
        LiquidationStructureRouterV1.Router router = "liquidation-exploratory-v005".equals(policy.path("id").asText())
                ? new LiquidationStructureRouterV1.Router(LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                        macroPolicy, LiquidationStructureRouterV1.RuleConfig.forProfile(variantId), input.dailyPriceContexts)
                : new LiquidationStructureRouterV1.Router(
                        LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION, macroPolicy);
        LiquidationPortfolioAccountingV1.AccountSession account = LiquidationPortfolioAccountingV1
                .startHourlyDevelopmentSession(accountRequest(input, policy, costMultiplier));
        NavigableMap<Instant, List<LiquidationStructureRouterV1.Observation>> observations = observationsByTime(input.features);
        Map<Instant, List<HourBar>> starts = input.barsByStart;
        TreeSet<Instant> timeline = new TreeSet<>(); timeline.addAll(observations.keySet()); timeline.addAll(starts.keySet());
        HashMap<String, PendingFill> pending = new HashMap<>();
        Map<String, Instant> firstFills = new HashMap<>();
        Map<String, LiquidationStructureRouterV1.ConfirmedIntent> firstDecisions = new HashMap<>();
        Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> stressEvents = new LinkedHashMap<>();
        ArrayNode routeRejections = JsonHashes.mapper().createArrayNode();
        Map<String, HourBar> byAssetStart = input.barsByAssetAndStart;
        Funnel funnel = new Funnel(input.dailyRows, input.validDailyWindows, input.longStressFlags, input.shortStressFlags,
                input.missingOiEndpoints);

        for (Instant at : timeline) {
            // The prior hour's completed path is applied before same-time features or entries.
            for (String asset : input.assets) {
                HourBar prior = byAssetStart.get(key(asset, at.minusMillis(HOUR_MS)));
                if (prior != null) {
                    boolean wasOpen = account.hasOpenPosition(asset);
                    String setup = wasOpen ? account.activeSetupId(asset) : "";
                    ObjectNode event = barEvent("BAR_POST", prior, at);
                    ObjectNode record = account.accept(event);
                    handleAccountClose(record, wasOpen, setup, asset, at, router, pending, firstFills);
                }
            }
            for (LiquidationStructureRouterV1.Observation observation : observations.getOrDefault(at, List.of())) {
                LiquidationStructureRouterV1.RouteResult routed = router.accept(observation);
                collectEvents(routed, stressEvents, policy.path("decision_start").asText(),
                        policy.path("decision_end_exclusive").asText());
                collectDecisions(routed, firstDecisions, policy.path("decision_start").asText(),
                        policy.path("decision_end_exclusive").asText());
                processRouteResult(routed, router, account, pending, routeRejections, at, variantId, input, funnel,
                        policy.path("decision_start").asText(), policy.path("decision_end_exclusive").asText());
            }
            List<HourBar> current = starts.getOrDefault(at, List.of()).stream()
                    .sorted(Comparator.comparingInt(bar -> assetRank(bar.asset))).toList();
            for (HourBar bar : current) {
                boolean wasOpen = account.hasOpenPosition(bar.asset);
                String setup = wasOpen ? account.activeSetupId(bar.asset) : "";
                ObjectNode record = account.accept(barEvent("BAR_PRE", bar, at));
                handleAccountClose(record, wasOpen, setup, bar.asset, at, router, pending, firstFills);
            }
            if (!current.isEmpty()) {
                for (HourBar bar : current) {
                    Instant firstFill = firstFills.get(bar.asset);
                    if (firstFill != null && !at.isBefore(firstFill.plus(Duration.ofDays(60)))
                            && account.hasOpenPosition(bar.asset)) {
                        String setup = account.activeSetupId(bar.asset);
                        ObjectNode timeout = JsonHashes.mapper().createObjectNode().put("type", "EXIT")
                                .put("asset", bar.asset).put("time", at.toEpochMilli())
                                .put("price", bar.open).put("reason", "SIXTY_DAY_LIFECYCLE");
                        ObjectNode record = account.accept(timeout);
                        handleAccountClose(record, true, setup, bar.asset, at, router, pending, firstFills);
                    }
                }
                List<PendingFill> due = pending.values().stream().filter(value -> value.fillAt.equals(at))
                        .sorted(Comparator.comparing((PendingFill value) -> value.intent.decisionTime())
                                .thenComparingInt(value -> assetRank(value.intent.asset()))
                                .thenComparingInt(value -> value.intent.stage())).toList();
                for (PendingFill instruction : due) executeFill(instruction, current, input, variantId,
                        account, router, pending, firstFills, funnel);
            }
        }
        ObjectNode accountSnapshot = account.snapshot();
        funnel.qualifiedEvents = stressEvents.size();
        funnel.closedPositions = countClosedPositions(accountSnapshot);
        ObjectNode entryRuleAudit = Double.compare(costMultiplier, 1.0) == 0
                ? router.entryRuleAuditSnapshot(Instant.parse(decisionStartText), Instant.parse(decisionEndText))
                : JsonHashes.mapper().createObjectNode();
        return new VariantResult(variantId, accountSnapshot, stressEvents, firstDecisions, routeRejections,
                funnel, entryRuleAudit);
    }

    private static void processRouteResult(LiquidationStructureRouterV1.RouteResult result,
            LiquidationStructureRouterV1.Router router, LiquidationPortfolioAccountingV1.AccountSession account,
            Map<String, PendingFill> pending, ArrayNode rejections, Instant at, String variantId, Input input,
            Funnel funnel, String decisionStartText, String decisionEndText) {
        for (LiquidationStructureRouterV1.RejectedOpportunity rejection : result.rejections()) {
            if (rejection.decisionTime().isBefore(Instant.parse(decisionStartText))
                    || !rejection.decisionTime().isBefore(Instant.parse(decisionEndText))) {
                funnel.outsideWindowRouterRejections++;
                continue;
            }
            rejections.addObject().put("variant", variantId).put("asset", rejection.asset())
                    .put("stage", rejection.stage()).put("decision_time", rejection.decisionTime().toString())
                    .put("reason", rejection.reasonCode()).put("macro_state", rejection.macroState().name());
            funnel.routerRejections++;
        }
        for (LiquidationStructureRouterV1.PendingCancellation cancellation : result.pendingCancellations()) {
            PendingFill removed = pending.remove(cancellation.intentId());
            router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(cancellation.intentId(), cancellation.setupId(),
                    cancellation.asset(), cancellation.stage(), cancellation.cancellationTime(), cancellation.reasonCode()));
        }
        for (LiquidationStructureRouterV1.StopUpdateIntent update : result.stopUpdates()) {
            if (!account.hasOpenPosition(update.asset()) || !account.activeSetupId(update.asset()).equals(update.setupId())) continue;
            long sourceClose = update.sourceEvidence().stream().mapToLong(value -> value.eventTime().toEpochMilli())
                    .max().orElse(at.toEpochMilli());
            ObjectNode fields = JsonHashes.mapper().createObjectNode().put("setup_id", update.setupId())
                    .put("source_bar_close_time", sourceClose).put("source_available_at", update.activationTime().toEpochMilli())
                    .put("stop", update.proposedStop());
            ObjectNode record = account.accept(JsonHashes.mapper().createObjectNode().put("type", "STOP_UPDATE")
                    .put("asset", update.asset()).put("time", update.activationTime().toEpochMilli()).setAll(fields));
            if ("STOP_UPDATED".equals(record.path("type").asText())) {
                router.onStopUpdate(new LiquidationStructureRouterV1.StopAck(update.setupId(), update.asset(),
                        update.activationTime(), update.proposedStop()));
            }
        }
        for (LiquidationStructureRouterV1.ConfirmedIntent intent : result.intents()) {
            boolean inDecisionWindow = !intent.decisionTime().isBefore(Instant.parse(decisionStartText))
                    && intent.decisionTime().isBefore(Instant.parse(decisionEndText));
            if (inDecisionWindow) {
                funnel.confirmedIntents++;
                if (intent.stage() == 1) {
                    funnel.stageOneConfirmationIntents++;
                    funnel.uniqueStageOneSetups.add(intent.setupId());
                }
            }
            if (intent.stage() > 1 && "CORE_ONE_ENTRY".equals(variantId)) {
                router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                        intent.asset(), intent.stage(), at, "CORE_ONE_ENTRY_SUPPRESSES_ADDITIONS"));
                continue;
            }
            if (!inDecisionWindow) {
                router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                        intent.asset(), intent.stage(), at, "OUTSIDE_FROZEN_DECISION_WINDOW"));
                funnel.outsideWindowSuppressed++;
                continue;
            }
            HourBar fillBar = firstHourlyOpenAfter(intent, at, input);
            if (fillBar == null) {
                router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                        intent.asset(), intent.stage(), at, "NO_LATER_HOURLY_OPEN_IN_INPUT"));
                continue;
            }
            pending.put(intent.intentId(), new PendingFill(intent, fillBar.start, fillBar));
            funnel.submittedFillAttempts++;
        }
    }

    static HourBar firstHourlyOpenAfter(LiquidationStructureRouterV1.ConfirmedIntent intent,
            Instant now, Input input) {
        Instant executionOpen = intent.decisionTime().plusMillis(HOUR_MS);
        if (now.isAfter(executionOpen)) return null;
        return input.barsByAssetAndStart.get(key(intent.asset(), executionOpen));
    }

    static boolean hasCompletePrior90DayWindow(LocalDate day, Set<LocalDate> observedDays) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(observedDays, "observedDays");
        for (int lag = 1; lag <= 90; lag++) if (!observedDays.contains(day.minusDays(lag))) return false;
        return true;
    }

    private static void executeFill(PendingFill instruction, List<HourBar> current, Input input, String variantId,
            LiquidationPortfolioAccountingV1.AccountSession account, LiquidationStructureRouterV1.Router router,
            Map<String, PendingFill> pending, Map<String, Instant> firstFills, Funnel funnel) {
        LiquidationStructureRouterV1.ConfirmedIntent intent = instruction.intent;
        pending.remove(intent.intentId());
        HourBar bar = current.stream().filter(value -> value.asset.equals(intent.asset()) && value.start.equals(instruction.fillAt))
                .findFirst().orElse(instruction.bar);
        HourBar prior = input.barsByAssetAndStart.get(key(bar.asset, bar.start.minusMillis(HOUR_MS)));
        String rejection = null;
        if (prior == null) rejection = "NO_PRIOR_COMPLETED_HOURLY_VOLUME_FOR_CAPACITY";
        else if (Math.abs(bar.open - intent.zoneCenter()) > intent.maxChaseDistance()) rejection = "MAXIMUM_CHASE_DISTANCE_EXCEEDED";
        else if (intent.stage() == 1 && account.hasOpenPosition(intent.asset())) rejection = "ASSET_ALREADY_OCCUPIED_AT_HOURLY_OPEN";
        else if (intent.stage() > 1 && (!account.hasOpenPosition(intent.asset())
                || !account.activeSetupId(intent.asset()).equals(intent.setupId()))) rejection = "NO_MATCHING_OPEN_POSITION_FOR_ADDITION";
        if (rejection != null) {
            router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(), intent.asset(),
                    intent.stage(), bar.start, rejection));
            funnel.fillRejections++;
            return;
        }
        ObjectNode fields = JsonHashes.mapper().createObjectNode().put("stage", intent.stage())
                .put("setup_id", intent.setupId()).put("intent_id", intent.intentId())
                .put("decision_time", intent.decisionTime().toEpochMilli()).put("price", bar.open)
                .put("mark_price", bar.open).put("decision_mark", intent.confirmationClose())
                .put("previous_completed_minute_base_volume", prior.volume).put("mode", intent.branch().name())
                .put("direction", intent.direction().name()).put("common_stop", intent.initialStop());
        if (intent.reversalTarget() == null) fields.putNull("recovery_target"); else fields.put("recovery_target", intent.reversalTarget());
        ObjectNode record = account.accept(JsonHashes.mapper().createObjectNode().put("type", "ADD")
                .put("asset", intent.asset()).put("time", bar.start.toEpochMilli()).setAll(fields));
        if (!"ADD_FILLED".equals(record.path("type").asText())) {
            router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(), intent.asset(),
                    intent.stage(), bar.start, record.path("reason").asText("ACCOUNT_REJECTED")));
            funnel.fillRejections++;
            return;
        }
        funnel.filledTranches++;
        if (intent.stage() == 1) firstFills.put(intent.asset(), bar.start);
        double stop = account.activeStopPrice(intent.asset());
        router.onFill(new LiquidationStructureRouterV1.FillAck(intent.intentId(), intent.setupId(), intent.asset(),
                intent.stage(), bar.start, bar.open, stop));
    }

    private static void handleAccountClose(ObjectNode record, boolean wasOpen, String setup, String asset, Instant at,
            LiquidationStructureRouterV1.Router router, Map<String, PendingFill> pending, Map<String, Instant> firstFills) {
        if (!wasOpen || !isClosedAccountEvent(record)) return;
        pending.values().stream().filter(value -> value.intent.asset().equals(asset)
                && value.intent.setupId().equals(setup)).toList().forEach(value -> {
                    pending.remove(value.intent.intentId());
                    router.onNoFill(new LiquidationStructureRouterV1.NoFillAck(value.intent.intentId(), setup, asset,
                            value.intent.stage(), at, "POSITION_CLOSED_BEFORE_HOURLY_ADDITION_FILL"));
                });
        LiquidationStructureRouterV1.CloseReason reason = record.path("liquidated").asBoolean(false)
                ? LiquidationStructureRouterV1.CloseReason.LIQUIDATION
                : record.path("exit_reason").asText("").contains("SIXTY_DAY")
                    ? LiquidationStructureRouterV1.CloseReason.TIMEOUT
                    : record.path("exit_reason").asText("").contains("RECOVERY_TARGET")
                        ? LiquidationStructureRouterV1.CloseReason.TARGET : LiquidationStructureRouterV1.CloseReason.STOP;
        router.onClose(new LiquidationStructureRouterV1.CloseAck(setup, asset, at, reason));
        firstFills.remove(asset);
    }

    private static boolean isClosedAccountEvent(ObjectNode record) {
        return record.hasNonNull("exit_reason") || record.path("liquidated").asBoolean(false)
                || record.path("type").asText("").equals("EXIT_FILLED");
    }

    private static ObjectNode accountRequest(Input input, ObjectNode policy, double costMultiplier) {
        ObjectNode request = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v3-hourly-development-account/1")
                .put("execution_timeframe", "H1_OHLC_APPROXIMATION")
                .put("initial_equity_usdt", policy.path("account").path("initial_equity").asDouble())
                .put("reserved_costs_usdt", 0);
        ArrayNode positions = request.putArray("positions");
        for (String asset : input.assets) {
            HourBar mark = input.barsByAsset.get(asset).stream().findFirst()
                    .orElseThrow(() -> failure("no hourly input for " + asset));
            ObjectNode spec = positions.addObject().put("asset", asset).put("direction", "UNCONFIGURED")
                    .put("lot_size", policy.path("execution").path("lot_steps").path(asset).asDouble())
                    .put("minimum_notional", policy.path("execution").path("minimum_notional_usdt").asDouble())
                    .put("taker_fee_rate", policy.path("execution").path("fee_per_side").asDouble() * costMultiplier)
                    .put("slippage_rate", policy.path("execution").path("slippage_per_side").asDouble() * costMultiplier)
            .put("liquidation_fee_rate", policy.path("execution").path("liquidation_fee_rate").asDouble())
                    .put("initial_mark_price", mark.open);
            spec.putArray("maintenance_tiers").addObject().put("effective_from", 0)
                    .put("effective_until", Long.MAX_VALUE).put("tier_notional_cap", 1.0e15)
                    .put("maintenance_margin_rate", policy.path("execution").path("maintenance_fraction").asDouble())
                    .put("maintenance_deduction", 0);
        }
        return request;
    }

    private static ObjectNode barEvent(String type, HourBar bar, Instant eventTime) {
        return JsonHashes.mapper().createObjectNode().put("type", type).put("asset", bar.asset)
                .put("time", eventTime.toEpochMilli()).put("bar_start_time", bar.start.toEpochMilli())
                .put("bar_duration_ms", HOUR_MS).put("trade_open", bar.open).put("trade_high", bar.high)
                .put("trade_low", bar.low).put("trade_close", bar.close).put("mark_open", bar.open)
                .put("mark_high", bar.high).put("mark_low", bar.low).put("mark_close", bar.close);
    }

    private static void collectEvents(LiquidationStructureRouterV1.RouteResult routed,
            Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> target,
            String decisionStartText, String decisionEndText) {
        Instant start = Instant.parse(decisionStartText), end = Instant.parse(decisionEndText);
        for (LiquidationStructureRouterV1.QualifiedDailyStressEvent event : routed.qualifiedDailyStressEvents()) {
            if (!event.availableAt().isBefore(start) && event.availableAt().isBefore(end)) {
                target.putIfAbsent(event.eventId(), event);
            }
        }
    }

    private static void collectDecisions(LiquidationStructureRouterV1.RouteResult routed,
            Map<String, LiquidationStructureRouterV1.ConfirmedIntent> target,
            String decisionStartText, String decisionEndText) {
        Instant start = Instant.parse(decisionStartText), end = Instant.parse(decisionEndText);
        for (LiquidationStructureRouterV1.ConfirmedIntent intent : routed.intents()) {
            if (intent.stage() == 1 && !intent.diagnosticOnly()
                    && !intent.decisionTime().isBefore(start) && intent.decisionTime().isBefore(end)) {
                target.putIfAbsent(intent.setupId(), intent);
            }
        }
    }

    private static NavigableMap<Instant, List<LiquidationStructureRouterV1.Observation>> observationsByTime(
            List<LiquidationStructureRouterV1.Observation> observations) {
        TreeMap<Instant, List<LiquidationStructureRouterV1.Observation>> result = new TreeMap<>();
        observations.stream().sorted(OBSERVATION_ORDER).forEach(row -> result.computeIfAbsent(processingTime(row), ignored -> new ArrayList<>()).add(row));
        return result;
    }

    private static Instant processingTime(LiquidationStructureRouterV1.Observation row) {
        return row instanceof LiquidationStructureRouterV1.DailyLiquidation daily ? daily.modeledAvailableAt() : row.availableAt();
    }

    private static final Comparator<LiquidationStructureRouterV1.Observation> OBSERVATION_ORDER =
            Comparator.comparing(LiquidationHourlyDevelopmentReplayV1::processingTime)
                    .thenComparingInt(row -> row instanceof LiquidationStructureRouterV1.MacroClose ? -1 : assetRank(row.asset()))
                    .thenComparingInt(row -> row instanceof LiquidationStructureRouterV1.DailyLiquidation ? 1 : 0)
                    .thenComparing(LiquidationStructureRouterV1.Observation::eventTime)
                    .thenComparingInt(row -> row instanceof LiquidationStructureRouterV1.MacroClose ? 0
                            : row instanceof LiquidationStructureRouterV1.OpenInterest ? 1
                            : row instanceof LiquidationStructureRouterV1.Bar bar && bar.timeframe() == LiquidationStructureRouterV1.Timeframe.FOUR_HOUR ? 2 : 3)
                    .thenComparing(LiquidationStructureRouterV1.Observation::asset)
                    .thenComparing(LiquidationStructureRouterV1.Observation::seriesId);

    private static ObjectNode assembleResult(Input input, ObjectNode policy, Map<String, VariantResult> variants,
            ObjectNode costSensitivity) {
        ObjectNode output = JsonHashes.mapper().createObjectNode().put("schema", RESULT_SCHEMA)
                .put("status", "DEVELOPMENT_ONLY_EXPLORATORY")
                .put("authoritative", false).put("promotion_allowed", false)
                .put("precommit_id", policy.path("id").asText())
                .put("source_start", policy.path("source_start").asText())
                .put("source_end_exclusive", policy.path("source_end_exclusive").asText())
                .put("decision_start", policy.path("decision_start").asText())
                .put("decision_end_exclusive", policy.path("decision_end_exclusive").asText())
                .put("funding_treatment", "EXCLUDED_NOT_OBSERVED_ZERO")
                .put("mark_treatment", "TRADE_OHLC_PROXY")
                .put("hourly_execution_approximation", true)
                .put("independent_episode_claim", false);
        ObjectNode variantRows = output.putObject("variants");
        ObjectNode auditByVariant = output.putObject("entry_rule_audit").put("schema", "liquidation-entry-rule-audit-set/1")
                .put("diagnostic_only", true)
                .put("rule_changes", "liquidation-exploratory-v005".equals(policy.path("id").asText()))
                .putObject("variants");
        Map<String, ObjectNode> accounts = new LinkedHashMap<>(), funnels = new LinkedHashMap<>();
        List<LiquidationHourlyDiagnosticsV1.Intent> diagnosticIntents = new ArrayList<>();
        List<LiquidationHourlyDiagnosticsV1.Position> diagnosticPositions = new ArrayList<>();
        for (VariantResult row : variants.values()) {
            ObjectNode summary = accountSummary(row.account);
            summary.put("status", "DEVELOPMENT_ONLY").put("entry_decisions", row.firstDecisions.size())
                    .put("qualified_events_seen", row.stressEvents.size()).put("router_rejection_count", row.rejections.size());
            summary.set("pnl_reconciliation", pnlReconciliation(row.account));
            summary.set("router_rejections", row.rejections.deepCopy());
            summary.set("funnel_counts", row.funnel.toJson());
            summary.put("content_sha256", JsonHashes.ownHash(summary));
            variantRows.set(row.id, summary);
            auditByVariant.set(row.id, row.entryRuleAudit.deepCopy());
            accounts.put(row.id, row.account);
            funnels.put(row.id, row.funnel.toJson());
            for (LiquidationStructureRouterV1.ConfirmedIntent intent : row.firstDecisions.values()) {
                diagnosticIntents.add(new LiquidationHourlyDiagnosticsV1.Intent(row.id, intent.setupId(),
                        intent.asset(), intent.setupId(), intent.decisionTime(), intent.direction(), intent.branch()));
            }
            diagnosticPositions.addAll(diagnosticPositions(row, policy));
        }
        Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> events = mergeEvents(variants,
                "liquidation-exploratory-v005".equals(policy.path("id").asText()));
        ArrayNode inventory = output.putArray("qualified_event_inventory");
        events.values().stream().sorted(Comparator.comparing(LiquidationStructureRouterV1.QualifiedDailyStressEvent::availableAt)
                .thenComparing(LiquidationStructureRouterV1.QualifiedDailyStressEvent::asset)
                .thenComparing(LiquidationStructureRouterV1.QualifiedDailyStressEvent::eventId))
                .forEach(event -> inventory.add(eventJson(event)));
        List<LiquidationHourlyDiagnosticsV1.Event> diagnosticEvents = events.values().stream().map(event -> {
            ObjectNode row = eventJson(event);
            Instant start = row.path("selected_geometry_available").asBoolean(false)
                    ? Instant.parse(row.path("selected_geometry_start").asText()) : null;
            Instant end = row.path("selected_geometry_available").asBoolean(false)
                    ? Instant.parse(row.path("selected_geometry_end").asText()) : null;
            return new LiquidationHourlyDiagnosticsV1.Event(event.eventId(), event.asset(), start, end,
                    event.availableAt(), event.shockDirection());
        }).toList();
        ObjectNode diagnostics = LiquidationHourlyDiagnosticsV1.build(policy, input.pricesByAssetCloseTime,
                diagnosticEvents, diagnosticIntents, diagnosticPositions, accounts, funnels, input.coverage);
        for (String field : List.of("market_wide_shock_clusters", "response_diagnostics", "position_overlap_components",
                "calendar_block_sensitivity", "per_completed_position_metrics", "lagged_daily_portfolio_return_correlations",
                "funnel_counts_by_variant")) {
            output.set(field, diagnostics.path(field).deepCopy());
        }
        output.put("market_wide_shock_cluster_count", diagnostics.path("market_wide_shock_cluster_count").asInt())
                .put("qualified_events_without_price_geometry", diagnostics.path("unclustered_geometry_event_count").asInt())
                .put("cluster_count_status", diagnostics.path("cluster_count_status").asText())
                .put("cluster_count_warning_nonblocking", diagnostics.path("cluster_count_warning_nonblocking").asBoolean())
                .set("cost_sensitivity_doubled_fees_slippage", costSensitivity);
        output.set("data_coverage", input.coverage.deepCopy());
        ArrayNode gaps = output.putArray("source_limitations");
        gaps.add("Coinalyze Binance-specific daily liquidation aggregate is trusted as the frozen proxy; revisions/publication latency are not independently qualified.");
        gaps.add("Hourly trade OHLC is also the mark-price proxy; intrahour path ordering and exact exchange liquidation mechanics are unknown.");
        gaps.add("Funding is excluded from all return figures; results are before funding and cannot establish net perpetual profitability.");
        gaps.add("Contract sizes, fees, slippage, maintenance and liquidation fees are approximations frozen for this development run.");
        gaps.add("S&P 500 close timing uses a receipt-bound next-NYSE-session-close proxy; historical release vintages are not verified.");
        gaps.add("OI endpoint sampling uses only the exact five-minute observation at H4 close minus ten minutes, with five-minute publication lag; absent endpoints are not filled.");
        gaps.add("Market shock clusters and overlapping holding components summarize dependence; neither count proves statistical independence.");
        gaps.add("This is a full-sample exposed diagnostic, not walk-forward, sealed confirmation, or live authorization.");
        output.put("content_sha256", JsonHashes.ownHash(output));
        return output;
    }

    static List<LiquidationHourlyDiagnosticsV1.Position> diagnosticPositions(VariantResult variant, ObjectNode policy) {
        ArrayList<LiquidationHourlyDiagnosticsV1.Position> result = new ArrayList<>();
        LinkedHashMap<String, JsonNode> bySetup = new LinkedHashMap<>();
        for (JsonNode raw : variant.account.path("closed_episodes")) {
            String setup = raw.path("active_setup_id").asText("");
            if (!setup.isBlank()) bySetup.put(raw.path("asset").asText() + "|" + setup, raw);
        }
        for (JsonNode raw : variant.account.path("positions")) {
            String setup = raw.path("active_setup_id").asText("");
            if (!setup.isBlank()) bySetup.putIfAbsent(raw.path("asset").asText() + "|" + setup, raw);
        }
        for (JsonNode raw : bySetup.values()) {
            String status = raw.path("status").asText("NO_POSITION");
            if (!"OPEN".equals(status) && !"CLOSED".equals(status)) continue;
            String setupId = raw.path("active_setup_id").asText("");
            LiquidationStructureRouterV1.ConfirmedIntent intent = variant.firstDecisions.get(setupId);
            if (intent == null) throw failure("filled position has no in-window first-entry intent: " + setupId);
            long firstFillMillis = raw.path("first_fill_time").asLong(-1);
            if (firstFillMillis < 0) throw failure("position record is missing its first fill timestamp");
            Instant firstFill = Instant.ofEpochMilli(firstFillMillis);
            JsonNode exits = raw.path("exits");
            boolean closed = "CLOSED".equals(status);
            Instant exit = null;
            if (closed) {
                if (!exits.isArray() || exits.isEmpty()) throw failure("closed position is missing its terminal exit event");
                exit = Instant.ofEpochMilli(exits.get(exits.size() - 1).path("time").asLong());
            }
            BigDecimal pnl = decimal(raw.path("realized_gross_pnl_usdt"))
                    .subtract(decimal(raw.path("entry_costs_usdt")))
                    .subtract(decimal(raw.path("exit_costs_usdt")));
            if (!closed) pnl = pnl.add(decimal(raw.path("gross_unrealized_pnl_usdt")));
            result.add(new LiquidationHourlyDiagnosticsV1.Position(variant.id, raw.path("asset").asText(), setupId,
                    intent.decisionTime(), firstFill, exit, pnl, closed));
        }
        return List.copyOf(result);
    }

    static ObjectNode pnlReconciliation(ObjectNode account) {
        BigDecimal initial = decimal(account.path("initial_equity_usdt"));
        BigDecimal terminal = decimal(account.path("mark_to_market_equity_usdt"));
        LinkedHashMap<String, JsonNode> episodes = new LinkedHashMap<>();
        for (String field : List.of("closed_episodes", "positions")) {
            JsonNode rows = account.path(field);
            if (!rows.isArray()) continue;
            for (JsonNode row : rows) {
                String status = row.path("status").asText("NO_POSITION");
                if (!"CLOSED".equals(status) && !"OPEN".equals(status)) continue;
                String setup = row.path("active_setup_id").asText("");
                String asset = row.path("asset").asText("");
                if (setup.isBlank() || asset.isBlank()) throw failure("account trade ledger is missing an asset or setup id");
                episodes.putIfAbsent(asset + "|" + setup, row);
            }
        }
        BigDecimal aggregateNetPnl = BigDecimal.ZERO;
        int closed = 0, open = 0;
        for (JsonNode row : episodes.values()) {
            boolean isOpen = "OPEN".equals(row.path("status").asText());
            aggregateNetPnl = aggregateNetPnl
                    .add(decimal(row.path("realized_gross_pnl_usdt")))
                    .subtract(decimal(row.path("entry_costs_usdt")))
                    .subtract(decimal(row.path("exit_costs_usdt")));
            if (isOpen) {
                open++;
                aggregateNetPnl = aggregateNetPnl.add(decimal(row.path("gross_unrealized_pnl_usdt")));
            } else closed++;
        }
        BigDecimal funding = decimal(account.path("funding_pnl_usdt"));
        if (funding.signum() != 0) throw failure("hourly replay unexpectedly contains funding PnL despite funding being excluded");
        BigDecimal equityChange = terminal.subtract(initial);
        BigDecimal delta = aggregateNetPnl.subtract(equityChange);
        if (delta.abs().compareTo(new BigDecimal("0.00000001")) > 0) {
            throw failure("closed/open position ledger does not reconcile to terminal mark-to-market equity: " + delta);
        }
        return JsonHashes.mapper().createObjectNode().put("status", "RECONCILED_EX_FUNDING")
                .put("deduplicated_position_count", episodes.size()).put("closed_position_count", closed)
                .put("open_position_count", open).put("aggregate_position_net_pnl_ex_funding_usdt", aggregateNetPnl.toPlainString())
                .put("equity_change_ex_funding_usdt", equityChange.toPlainString()).put("difference_usdt", delta.toPlainString())
                .put("unpaid_account_liability_usdt", account.path("unpaid_funding_liability_usdt").asText("0"))
                .put("liability_treatment", "REPORTED_SEPARATELY;ACCOUNT_MARK_TO_MARKET_EQUITY_ALREADY_INCLUDES_ALL_POSITION_PNL");
    }

    private static ObjectNode accountSummary(ObjectNode raw) {
        ObjectNode account = raw.deepCopy();
        account.remove(List.of("event_order", "intraminute_order_assumption", "marked_drawdown_assumption", "deadline_exit_policy"));
        account.put("bar_path_assumption", "HOURLY_OHLC;TRADE_BAR_USED_AS_MARK_PROXY;ADVERSE_EXTREME_LIQUIDATION_FIRST;STOP_BEFORE_TARGET;GAP_USES_WORSE_OPEN");
        account.put("deadline_exit_assumption", "AT_OR_AFTER_60_DAYS_EXIT_AT_FIRST_PRESENT_HOURLY_OPEN;MISSING_BARS_RETAIN_EXPOSURE");
        account.put("capacity_assumption", "ONE_PERCENT_OF_PRIOR_COMPLETED_HOUR_BASE_VOLUME;CAPACITY_PROXY_NOT_INSTANTANEOUS_LIQUIDITY");
        account.put("funding_assumption", "EXCLUDED_NOT_OBSERVED_ZERO");
        account.set("unpaid_account_liability_usdt", account.path("unpaid_funding_liability_usdt").deepCopy());
        account.remove(List.of("funding_pnl_usdt", "unpaid_funding_liability_usdt"));
        for (String field : List.of("positions", "closed_episodes")) {
            for (JsonNode rawPosition : account.path(field)) {
                if (rawPosition instanceof ObjectNode position) {
                    position.remove(List.of("funding_pnl_usdt", "funding_debits_for_risk_headroom_usdt", "funding_events"));
                }
            }
        }
        return account;
    }

    private static Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> mergeEvents(
            Map<String, VariantResult> variants, boolean requireExactAcrossProfiles) {
        LinkedHashMap<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> merged = new LinkedHashMap<>();
        if (requireExactAcrossProfiles && !variants.isEmpty()) {
            List<Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent>> inventories = variants.values().stream()
                    .map(VariantResult::stressEvents).toList();
            return verifyIdenticalEventInventories(inventories);
        }
        for (VariantResult row : variants.values()) row.stressEvents.forEach(merged::putIfAbsent);
        return merged;
    }

    static Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> verifyIdenticalEventInventories(
            List<Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent>> inventories) {
        if (inventories.isEmpty()) return Map.of();
        Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> canonical = inventories.get(0);
        for (int profile = 1; profile < inventories.size(); profile++) {
            Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> candidate = inventories.get(profile);
            if (candidate.size() != canonical.size() || !candidate.keySet().equals(canonical.keySet())) {
                throw failure("v005 qualified event inventory differs across profiles");
            }
            for (Map.Entry<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> event : canonical.entrySet()) {
                if (!event.getValue().equals(candidate.get(event.getKey()))) {
                    throw failure("v005 qualified event geometry, direction, availability, or evidence differs across profiles: "
                            + event.getKey());
                }
            }
        }
        return Map.copyOf(canonical);
    }

    private static ObjectNode eventJson(LiquidationStructureRouterV1.QualifiedDailyStressEvent event) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("event_id", event.eventId()).put("asset", event.asset())
                .put("bucket_start", event.bucketStart().toString()).put("bucket_event_time", event.bucketEventTime().toString())
                .put("available_at", event.availableAt().toString()).put("shock_direction", event.shockDirection().name());
        List<Instant> priceCloses = event.sourceEvidence().stream()
                .filter(source -> "PRICE_EVENT".equals(source.role()))
                .map(LiquidationStructureRouterV1.SourceEvidence::eventTime).sorted().toList();
        if (priceCloses.isEmpty()) {
            row.put("selected_geometry_available", false).put("geometry_unavailable_reason", "NO_PRICE_EVENT_EVIDENCE");
        } else {
            Instant start = priceCloses.get(0).minusMillis(FOUR_HOURS_MS);
            Instant end = priceCloses.get(priceCloses.size() - 1);
            row.put("selected_geometry_available", true).put("selected_geometry_start", start.toString())
                    .put("selected_geometry_end", end.toString())
                    .put("dependence_interval_start_inclusive", start.toString())
                    .put("dependence_interval_end_inclusive", end.plus(Duration.ofHours(72)).toString());
        }
        ArrayNode evidence = row.putArray("source_evidence");
        for (LiquidationStructureRouterV1.SourceEvidence source : event.sourceEvidence()) {
            evidence.addObject().put("role", source.role()).put("asset", source.asset()).put("series_id", source.seriesId())
                    .put("event_time", source.eventTime().toString()).put("available_at", source.availableAt().toString());
        }
        return row;
    }

    private static Input loadInput(Path manifestPath) {
        ObjectNode manifest = readObject(manifestPath);
        if (!INPUT_SCHEMA.equals(manifest.path("schema").asText())
                || !"normalized".equals(manifest.path("root").asText())
                || !manifest.path("files").isObject()) throw failure("input manifest schema or root is unsupported");
        List<String> inputAssets = manifestAssetOrder(manifest);
        boolean v005ContextInput = manifest.has("daily_context_warmup");
        if (v005ContextInput && !V004_ASSETS.equals(inputAssets)) {
            throw failure("daily context warmup input is supported only for the exact frozen nine-asset scope");
        }
        Path runRoot = manifestPath.toAbsolutePath().normalize().getParent();
        Path normalizedRoot = safeResolve(runRoot, manifest.path("root").asText());
        ObjectNode coverage = readObject(safeResolve(runRoot, "coverage.json"));
        Map<String, String> fileHashes = new TreeMap<>();
        String manifestRelative = runRoot.relativize(manifestPath.toAbsolutePath().normalize()).toString().replace('\\', '/');
        fileHashes.put(manifestRelative, sha256(manifestPath));
        fileHashes.put("coverage.json", sha256(safeResolve(runRoot, "coverage.json")));

        ArrayList<String> blockers = new ArrayList<>();
        ArrayList<LiquidationStructureRouterV1.Observation> features = new ArrayList<>();
        TreeMap<String, List<HourBar>> barsByAsset = new TreeMap<>();
        TreeMap<String, HourBar> barsByAssetStart = new TreeMap<>();
        TreeMap<String, HourBar> barsByAssetAndStart = new TreeMap<>();
        TreeMap<Instant, List<HourBar>> barsByStart = new TreeMap<>();
        TreeMap<String, NavigableMap<Instant, Double>> pricesByAsset = new TreeMap<>();
        Map<String, LocalDate[]> dailyByAsset = new TreeMap<>();
        Map<String, Map<LocalDate, DailyRow>> dailyRowsByAsset = new TreeMap<>();
        Map<String, Set<Instant>> oiEndpointsByAsset = new TreeMap<>();
        for (String asset : inputAssets) {
            barsByAsset.put(asset, new ArrayList<>());
            pricesByAsset.put(asset, new TreeMap<>());
            dailyRowsByAsset.put(asset, new TreeMap<>());
            oiEndpointsByAsset.put(asset, new HashSet<>());
        }

        Path dailyPath = mappedPath(manifest, normalizedRoot, "daily_liquidations");
        recordHash(runRoot, dailyPath, fileHashes);
        CsvHeader dailyHeader = csvHeader(dailyPath, Set.of("asset", "symbol", "day_start_utc", "long_liquidations_usd", "short_liquidations_usd"));
        readCsv(dailyPath, dailyHeader, fields -> {
            String asset = fields[dailyHeader.index("asset")].toUpperCase(Locale.ROOT);
            if (!inputAssets.contains(asset)) throw failure("daily liquidation contains an unfrozen asset: " + asset);
            Instant dayStart = Instant.parse(fields[dailyHeader.index("day_start_utc")]);
            LocalDate day = dayStart.atZone(ZoneOffset.UTC).toLocalDate();
            if (!dayStart.equals(day.atStartOfDay(ZoneOffset.UTC).toInstant())) throw failure("daily liquidation timestamp is not exact UTC midnight");
            if (!(asset + "USDT_PERP.A").equals(fields[dailyHeader.index("symbol")])) throw failure("daily liquidation symbol does not match its asset: " + asset);
            DailyRow row = new DailyRow(asset, fields[dailyHeader.index("symbol")], day,
                    finiteNonnegative(fields[dailyHeader.index("long_liquidations_usd")], "long_liquidations_usd"),
                    finiteNonnegative(fields[dailyHeader.index("short_liquidations_usd")], "short_liquidations_usd"));
            if (dailyRowsByAsset.get(asset).putIfAbsent(day, row) != null) throw failure("duplicate daily liquidation date for " + asset + " " + day);
        });
        int dailyRows = 0, validDailyWindows = 0, longStressFlags = 0, shortStressFlags = 0;
        ObjectNode dailyCoverage = coverage.putObject("executor_daily_inputs");
        for (String asset : inputAssets) {
            Map<LocalDate, DailyRow> rows = dailyRowsByAsset.get(asset);
            dailyRows += rows.size();
            int valid = 0, longStress = 0, shortStress = 0;
            for (DailyRow current : rows.values()) {
                ArrayList<Double> priorLong = new ArrayList<>(90), priorShort = new ArrayList<>(90);
                boolean complete = hasCompletePrior90DayWindow(current.day, rows.keySet());
                if (complete) for (int lag = 90; lag >= 1; lag--) {
                    DailyRow prior = rows.get(current.day.minusDays(lag));
                    priorLong.add(prior.longLiquidationsUsd); priorShort.add(prior.shortLiquidationsUsd);
                }
                if (complete) {
                    valid++; double longP95 = nearestRank95(priorLong), shortP95 = nearestRank95(priorShort);
                    if (current.longLiquidationsUsd > 0 && current.longLiquidationsUsd > longP95) longStress++;
                    if (current.shortLiquidationsUsd > 0 && current.shortLiquidationsUsd > shortP95) shortStress++;
                }
                features.add(new LiquidationStructureRouterV1.DailyLiquidation(asset, current.day,
                        current.longLiquidationsUsd, current.shortLiquidationsUsd,
                        current.day.atStartOfDay(ZoneOffset.UTC).toInstant(), "coinalyze-daily-v002-" + asset));
            }
            validDailyWindows += valid; longStressFlags += longStress; shortStressFlags += shortStress;
            dailyCoverage.putObject(asset).put("daily_rows", rows.size()).put("valid_90_day_windows", valid)
                    .put("long_liquidation_stress_flags", longStress).put("short_liquidation_stress_flags", shortStress);
        }

        for (String asset : inputAssets) {
            Path barsPath = mappedAssetPath(manifest, normalizedRoot, "hourly_bars", asset);
            recordHash(runRoot, barsPath, fileHashes);
            CsvHeader header = csvHeader(barsPath, Set.of("open_time", "symbol", "open", "high", "low", "close", "base_volume"));
            List<HourBar> bars = barsByAsset.get(asset);
            readCsv(barsPath, header, fields -> {
                Instant start = Instant.parse(fields[header.index("open_time")]);
                if (!(asset + "USDT").equals(fields[header.index("symbol")])) throw failure("hourly kline symbol does not match its asset: " + asset);
                HourBar bar = new HourBar(asset, start, finitePositive(fields[header.index("open")], "open"),
                        finitePositive(fields[header.index("high")], "high"), finitePositive(fields[header.index("low")], "low"),
                        finitePositive(fields[header.index("close")], "close"), finiteNonnegative(fields[header.index("base_volume")], "base_volume"));
                if (bar.high < Math.max(bar.open, bar.close) || bar.low > Math.min(bar.open, bar.close) || bar.low > bar.high
                        || start.toEpochMilli() % HOUR_MS != 0) throw failure("invalid H1 OHLC or timestamp for " + asset + " at " + start);
                if (!bars.isEmpty() && !start.isAfter(bars.get(bars.size() - 1).start)) throw failure("H1 bars are duplicated or unsorted for " + asset);
                bars.add(bar);
            });
            if (bars.isEmpty()) blockers.add("no hourly price bars for " + asset);
            if (inputAssets.equals(LEGACY_ASSETS)) {
                if (bars.size() != 36_024) blockers.add("hourly bar count differs from expected complete source envelope for " + asset + ": " + bars.size());
                if (!bars.isEmpty() && (!Instant.parse("2022-08-11T00:00:00Z").equals(bars.get(0).start)
                        || !Instant.parse("2026-09-20T00:00:00Z").equals(bars.get(bars.size() - 1).start.plusMillis(HOUR_MS)))) {
                    blockers.add("hourly price bounds differ from frozen common source window for " + asset);
                }
            } else {
                JsonNode seriesCoverage = coverage.path("datasets").path(asset).path("klines_1h");
                long expectedRows = seriesCoverage.path("expected_rows").asLong(-1);
                long missingRows = seriesCoverage.path("missing_rows").asLong(-1);
                if (expectedRows < 0 || missingRows < 0 || expectedRows - missingRows != bars.size()) {
                    blockers.add("nine-asset hourly row count does not reconcile to retained per-asset coverage for " + asset);
                }
            }
            for (int i = 0; i < bars.size(); i++) {
                HourBar bar = bars.get(i); String k = key(asset, bar.start);
                if (barsByAssetStart.putIfAbsent(k, bar) != null || barsByAssetAndStart.putIfAbsent(k, bar) != null) {
                    throw failure("duplicate H1 asset/open timestamp: " + k);
                }
                barsByStart.computeIfAbsent(bar.start, ignored -> new ArrayList<>()).add(bar);
                pricesByAsset.get(asset).put(bar.start.plusMillis(HOUR_MS), bar.close);
                features.add(new LiquidationStructureRouterV1.Bar(asset, LiquidationStructureRouterV1.Timeframe.ONE_HOUR,
                        bar.start, bar.start.plusMillis(HOUR_MS), bar.open, bar.high, bar.low, bar.close, "binance-usdm-h1-" + asset));
            }
            List<HourBar> h4 = aggregateFourHour(asset, bars);
            if (h4.isEmpty()) blockers.add("no complete H4 aggregates for " + asset);
            for (HourBar bar : h4) {
                String k = key(asset, bar.start);
                features.add(new LiquidationStructureRouterV1.Bar(asset, LiquidationStructureRouterV1.Timeframe.FOUR_HOUR,
                        bar.start, bar.start.plusMillis(FOUR_HOURS_MS), bar.open, bar.high, bar.low, bar.close, "binance-usdm-h1-aggregated-h4-" + asset));
            }
        }

        final int[] oiRowsExamined = {0}, oiSnapshotsSelected = {0};
        ObjectNode oiCoverage = coverage.putObject("executor_oi_endpoints");
        for (String asset : inputAssets) {
            Path oiPath = mappedAssetPath(manifest, normalizedRoot, "oi_5m", asset);
            recordHash(runRoot, oiPath, fileHashes);
            CsvHeader header = csvHeader(oiPath, Set.of("time", "symbol", "sum_open_interest"));
            Set<Instant> endpoints = oiEndpointsByAsset.get(asset);
            readCsv(oiPath, header, fields -> {
                Instant observed = Instant.parse(fields[header.index("time")]);
                if (!(asset + "USDT").equals(fields[header.index("symbol")])) throw failure("open-interest symbol does not match its asset: " + asset);
                if (observed.toEpochMilli() % Duration.ofMinutes(5).toMillis() != 0) throw failure("OI timestamp is not on a 5-minute UTC boundary");
                double baseOi = finitePositive(fields[header.index("sum_open_interest")], "sum_open_interest");
                oiRowsExamined[0]++;
                long withinFourHours = Math.floorMod(observed.toEpochMilli(), FOUR_HOURS_MS);
                if (withinFourHours != FOUR_HOURS_MS - Duration.ofMinutes(10).toMillis()) return;
                Instant endpoint = observed.plus(Duration.ofMinutes(10));
                endpoints.add(endpoint);
                features.add(new LiquidationStructureRouterV1.OpenInterest(asset, observed,
                        observed.plus(Duration.ofMinutes(5)), baseOi, "binance-usdm-oi-5m-" + asset));
                oiSnapshotsSelected[0]++;
            });
            int expectedEndpoints = 0;
            for (HourBar bar : barsByAsset.get(asset)) {
                if (bar.start.toEpochMilli() % FOUR_HOURS_MS == 0) expectedEndpoints++;
            }
            long missing = Math.max(0, expectedEndpoints - endpoints.size());
            oiCoverage.putObject(asset).put("expected_h4_endpoints", expectedEndpoints)
                    .put("usable_oi_snapshots", endpoints.size()).put("missing_oi_endpoints", missing)
                    .put("window_policy", "exact_5m_snapshot_at_h4_endpoint_minus_10m;available_at_endpoint_minus_5m;no_fill_forward");
        }
        int missingOiEndpoints = inputAssets.stream().mapToInt(asset -> oiCoverage.path(asset).path("missing_oi_endpoints").asInt()).sum();

        Path spPath = mappedPath(manifest, normalizedRoot, "sp500");
        recordHash(runRoot, spPath, fileHashes);
        CsvHeader spHeader = csvHeader(spPath, Set.of("observation_date", "SP500"));
        String spSeries = "fred-sp500-vintage-research-snapshot-" + sha256(spPath);
        final int[] spCount = {0};
        readCsv(spPath, spHeader, fields -> {
            String raw = fields[spHeader.index("SP500")].trim();
            if (raw.isEmpty() || ".".equals(raw)) return;
            LocalDate day = LocalDate.parse(fields[spHeader.index("observation_date")]);
            Instant close = day.atTime(LiquidationStructureRouterV1.sessionClose(day)).atZone(NEW_YORK).toInstant();
            features.add(new LiquidationStructureRouterV1.MacroClose(close,
                    LiquidationStructureRouterV1.modeledNextSessionClose(close), finitePositive(raw, "SP500"), true, spSeries));
            spCount[0]++;
        });
        int spRows = spCount[0];
        if (spRows == 0) blockers.add("no S&P 500 macro rows were loaded");

        features.sort(OBSERVATION_ORDER);
        Instant commonEnd = null;
        ObjectNode barCoverage = coverage.putObject("executor_hourly_bars");
        for (String asset : inputAssets) {
            List<HourBar> bars = barsByAsset.get(asset);
            if (bars.isEmpty()) continue;
            Instant end = bars.get(bars.size() - 1).start.plusMillis(HOUR_MS);
            commonEnd = commonEnd == null || end.isBefore(commonEnd) ? end : commonEnd;
            long missing = 0;
            for (int i = 1; i < bars.size(); i++) if (!bars.get(i).start.equals(bars.get(i - 1).start.plusMillis(HOUR_MS))) missing++;
            barCoverage.putObject(asset).put("rows", bars.size()).put("missing_hour_gaps", missing)
                    .put("first_open", bars.get(0).start.toString()).put("last_completed_close", end.toString());
            if (missing > 0) blockers.add("hourly price gaps for " + asset + " prevent continuous exact response paths: " + missing);
        }
        if (commonEnd == null) blockers.add("no common hourly endpoint exists");
        else coverage.put("analysis_coverage_end_exclusive", commonEnd.toString());
        coverage.put("executor_oi_rows_examined", oiRowsExamined[0]).put("executor_oi_snapshots_selected", oiSnapshotsSelected[0])
                .put("executor_sp500_rows", spRows).put("executor_feature_observation_count", features.size())
                .put("source_vintages_point_in_time_verified", false);
        List<LiquidationStructureRouterV1.DailyPriceContext> dailyPriceContexts = v005ContextInput
                ? loadDailyContextWarmup(manifest, runRoot, inputAssets, features, fileHashes, coverage)
                : List.of();
        return new Input(inputAssets, features, barsByAsset, barsByStart, barsByAssetStart, barsByAssetAndStart, pricesByAsset, coverage,
                fileHashes, blockers, dailyRows, validDailyWindows, longStressFlags, shortStressFlags,
                missingOiEndpoints, sha256(manifestPath), manifestPath.toAbsolutePath().normalize(), dailyPriceContexts,
                v005ContextInput);
    }

    private static List<LiquidationStructureRouterV1.DailyPriceContext> loadDailyContextWarmup(ObjectNode inputManifest,
            Path runRoot, List<String> inputAssets, List<LiquidationStructureRouterV1.Observation> retainedFeatures,
            Map<String, String> fileHashes, ObjectNode coverage) {
        return loadDailyContextWarmup(inputManifest, runRoot, inputAssets, retainedFeatures, fileHashes, coverage,
                repositoryRoot().resolve(".research-run/liquidation-exploratory-v004"));
    }

    static List<LiquidationStructureRouterV1.DailyPriceContext> loadDailyContextWarmup(ObjectNode inputManifest,
            Path runRoot, List<String> inputAssets, List<LiquidationStructureRouterV1.Observation> retainedFeatures,
            Map<String, String> fileHashes, ObjectNode coverage, Path retainedV004Root) {
        JsonNode binding = inputManifest.path("daily_context_warmup");
        if (!"liquidation-context-warmup-input/1".equals(binding.path("schema").asText())) {
            throw failure("v005 input manifest daily context warmup schema is unsupported");
        }
        String freezeRelative = text(binding, "freeze_path");
        String manifestRelative = text(binding, "manifest_path");
        validateWarmupBindingPaths(binding);
        Path freezePath = safeResolve(runRoot, freezeRelative);
        Path contextManifestPath = safeResolve(runRoot, manifestRelative);
        String expectedFreezeSha = digest(binding, "freeze_byte_sha256");
        String expectedManifestSha = digest(binding, "manifest_byte_sha256");
        if (!expectedFreezeSha.equals(sha256(freezePath)) || !expectedManifestSha.equals(sha256(contextManifestPath))) {
            throw failure("daily context warmup nested freeze or manifest differs from the outer input binding");
        }
        ObjectNode nestedFreeze = readObject(freezePath);
        ObjectNode contextManifest = readObject(contextManifestPath);
        if (!nestedFreeze.path("files").isObject()
                || !JsonHashes.ownHash(nestedFreeze).equals(nestedFreeze.path("content_sha256").asText())) {
            throw failure("daily context warmup nested freeze contents are invalid");
        }
        JsonNode frozenManifest = nestedFreeze.path("files").path("context-warmup-manifest.json");
        if (!frozenManifest.isObject() || !expectedManifestSha.equals(frozenManifest.path("sha256").asText())) {
            throw failure("daily context warmup nested freeze does not bind its exact manifest bytes");
        }
        Path contextRoot = freezePath.getParent();
        nestedFreeze.path("files").fields().forEachRemaining(entry -> {
            String relative = entry.getKey();
            JsonNode receipt = entry.getValue();
            long expectedBytes = receipt.path("bytes").asLong(-1);
            String expectedSha = receipt.path("sha256").asText("");
            if (!receipt.isObject() || expectedBytes < 0 || !expectedSha.matches("[0-9a-f]{64}")) {
                throw failure("daily context warmup freeze has an invalid file receipt: " + relative);
            }
            Path file = safeResolve(contextRoot, relative);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || fileSize(file) != expectedBytes
                    || !expectedSha.equals(sha256(file))) {
                throw failure("daily context warmup source changed or disappeared after freeze: " + relative);
            }
            fileHashes.put("context-warmup/" + relative, expectedSha);
        });
        fileHashes.put(freezeRelative, expectedFreezeSha);
        fileHashes.put(manifestRelative, expectedManifestSha);

        validateDailyContextWarmupMetadata(contextManifest, nestedFreeze, inputAssets);
        verifyRetainedV004Binding(contextManifest, inputManifest, runRoot, retainedV004Root);

        JsonNode mappedFiles = contextManifest.path("files");
        if (!mappedFiles.isObject() || mappedFiles.size() != V004_ASSETS.size()) {
            throw failure("daily context warmup must map exactly one normalized H1 file for each frozen asset");
        }
        ArrayList<LiquidationStructureRouterV1.Bar> supplementalBars = new ArrayList<>();
        ObjectNode perAssetCoverage = JsonHashes.mapper().createObjectNode();
        Instant startBound = Instant.parse("2022-04-01T00:00:00Z");
        Instant endBound = Instant.parse("2022-08-11T00:00:00Z");
        int totalRows = 0, totalMissingRows = 0, totalMissingIntervals = 0, totalInternalGapCount = 0;
        int expectedWarmupRows = (int) Duration.between(startBound, endBound).toHours();
        for (String asset : inputAssets) {
            String relative = text(mappedFiles, asset);
            if (!relative.startsWith("normalized/") || !relative.equals("normalized/klines_1h_" + asset + ".csv")) {
                throw failure("daily context warmup normalized path is outside its fixed per-asset mapping: " + asset);
            }
            if (!nestedFreeze.path("files").path(relative).isObject()) {
                throw failure("daily context warmup normalized CSV lacks an explicit nested freeze receipt: " + asset);
            }
            Path path = safeResolve(contextRoot, relative);
            CsvHeader header = csvHeader(path, Set.of("open_time", "symbol", "open", "high", "low", "close", "base_volume"));
            ArrayList<Instant> starts = new ArrayList<>();
            readCsv(path, header, fields -> {
                Instant start = Instant.parse(fields[header.index("open_time")]);
                if (!start.isBefore(endBound) || start.isBefore(startBound)
                        || start.toEpochMilli() % HOUR_MS != 0
                        || !(asset + "USDT").equals(fields[header.index("symbol")])) {
                    throw failure("daily context warmup bar is outside the hourly source bounds or has a wrong symbol: " + asset);
                }
                HourBar bar = new HourBar(asset, start, finitePositive(fields[header.index("open")], "open"),
                        finitePositive(fields[header.index("high")], "high"), finitePositive(fields[header.index("low")], "low"),
                        finitePositive(fields[header.index("close")], "close"), finiteNonnegative(fields[header.index("base_volume")], "base_volume"));
                if (bar.high < Math.max(bar.open, bar.close) || bar.low > Math.min(bar.open, bar.close) || bar.low > bar.high
                        || (!starts.isEmpty() && !start.isAfter(starts.get(starts.size() - 1)))) {
                    throw failure("daily context warmup bars are invalid, duplicated, or unsorted for " + asset);
                }
                starts.add(start);
                supplementalBars.add(new LiquidationStructureRouterV1.Bar(asset, LiquidationStructureRouterV1.Timeframe.ONE_HOUR,
                        start, start.plusMillis(HOUR_MS), bar.open, bar.high, bar.low, bar.close,
                        "binance-usdm-h1-context-warmup-" + asset));
            });
            long internalGaps = 0;
            for (int i = 1; i < starts.size(); i++) if (!starts.get(i).equals(starts.get(i - 1).plusMillis(HOUR_MS))) internalGaps++;
            int missingRows = expectedWarmupRows - starts.size();
            int missingIntervals = 0, cursor = 0;
            boolean insideMissingInterval = false;
            for (Instant expected = startBound; expected.isBefore(endBound); expected = expected.plusMillis(HOUR_MS)) {
                boolean observed = cursor < starts.size() && starts.get(cursor).equals(expected);
                if (observed) {
                    cursor++;
                    insideMissingInterval = false;
                } else {
                    if (!insideMissingInterval) missingIntervals++;
                    insideMissingInterval = true;
                }
            }
            totalRows += starts.size(); totalMissingRows += missingRows;
            totalMissingIntervals += missingIntervals; totalInternalGapCount += internalGaps;
            ObjectNode assetCoverage = perAssetCoverage.putObject(asset).put("expected_hourly_rows", expectedWarmupRows)
                    .put("hourly_rows", starts.size()).put("missing_hourly_rows", missingRows)
                    .put("missing_hourly_intervals", missingIntervals).put("internal_hourly_gaps", internalGaps);
            if (starts.isEmpty()) assetCoverage.putNull("first_open").putNull("last_open");
            else assetCoverage.put("first_open", starts.get(0).toString()).put("last_open", starts.get(starts.size() - 1).toString());
        }
        ArrayList<LiquidationStructureRouterV1.Bar> retainedBars = new ArrayList<>();
        for (LiquidationStructureRouterV1.Observation observation : retainedFeatures) {
            if (observation instanceof LiquidationStructureRouterV1.Bar bar
                    && bar.timeframe() == LiquidationStructureRouterV1.Timeframe.ONE_HOUR) retainedBars.add(bar);
        }
        List<LiquidationStructureRouterV1.DailyPriceContext> rows = LiquidationDailyPriceContextV1.build(retainedBars, supplementalBars);
        int rsiRows = 0, smaRows = 0;
        for (LiquidationStructureRouterV1.DailyPriceContext row : rows) {
            if (row.rsi14() != null) rsiRows++;
            if (row.sma200() != null) smaRows++;
        }
        ObjectNode contextCoverage = coverage.putObject("daily_price_context").put("schema", "liquidation-daily-price-context-coverage/1")
                .put("source", "BINANCE_USDT_PERPETUAL_H1;SUPPLEMENTAL_WARMUP_USED_ONLY_BY_CONTEXT_CALCULATOR")
                .put("supplemental_expected_hourly_rows", expectedWarmupRows * inputAssets.size())
                .put("supplemental_hourly_rows", totalRows).put("supplemental_missing_hourly_rows", totalMissingRows)
                .put("supplemental_missing_hourly_intervals", totalMissingIntervals)
                .put("supplemental_internal_hourly_gaps", totalInternalGapCount)
                .put("supplemental_start_inclusive", startBound.toString()).put("supplemental_end_exclusive", endBound.toString())
                .put("complete_daily_context_rows", rows.size()).put("rsi14_available_rows", rsiRows)
                .put("sma200_available_rows", smaRows).put("historical_outcomes_computed", false);
        contextCoverage.set("by_asset", perAssetCoverage);
        return rows;
    }

    static void validateWarmupBindingPaths(JsonNode binding) {
        if (!"liquidation-context-warmup-input/1".equals(binding.path("schema").asText())
                || !"context-warmup/data-freeze.json".equals(binding.path("freeze_path").asText())
                || !"context-warmup/context-warmup-manifest.json".equals(binding.path("manifest_path").asText())
                || !binding.path("freeze_byte_sha256").asText().matches("[0-9a-f]{64}")
                || !binding.path("manifest_byte_sha256").asText().matches("[0-9a-f]{64}")) {
            throw failure("daily context warmup binding schema, exact paths, or byte digests are invalid");
        }
    }

    static void validateDailyContextWarmupMetadata(ObjectNode contextManifest, ObjectNode nestedFreeze,
            List<String> inputAssets) {
        if (!"liquidation-context-warmup-freeze/1".equals(nestedFreeze.path("schema").asText())
                || !"liquidation-context-warmup/1".equals(contextManifest.path("schema").asText())
                || !nestedFreeze.path("files").isObject()
                || !V004_ASSETS.equals(inputAssets)
                || !V004_ASSETS.equals(orderedTextArray(contextManifest.path("assets")))
                || !V004_ASSETS.equals(orderedTextArray(nestedFreeze.path("assets")))
                || !"2022-04-01".equals(contextManifest.path("warmup_window").path("start_inclusive").asText())
                || !"2022-08-11".equals(contextManifest.path("warmup_window").path("end_exclusive").asText())
                || !"2022-08-11".equals(contextManifest.path("retained_hourly_window").path("start_inclusive").asText())
                || !"2026-09-20".equals(contextManifest.path("retained_hourly_window").path("end_exclusive").asText())
                || contextManifest.path("outcome_calculation_performed").asBoolean(true)
                || !"2022-11-11".equals(contextManifest.path("decision_window").path("start_inclusive").asText())
                || !"2026-07-15".equals(contextManifest.path("decision_window").path("end_exclusive").asText())) {
            throw failure("daily context warmup asset scope, date windows, or no-outcomes receipt differs from the frozen contract");
        }
    }

    private static void verifyRetainedV004Binding(ObjectNode contextManifest, ObjectNode inputManifest, Path runRoot,
            Path v004Root) {
        String retainedArchiveManifestSha = contextManifest.path("retained_v004").path("archive_manifest_sha256").asText("");
        if (!retainedArchiveManifestSha.matches("[0-9a-f]{64}")
                || !retainedArchiveManifestSha.equals(sha256(v004Root.resolve("archive-manifest.json")))) {
            throw failure("daily context warmup is detached from the retained v004 archive manifest");
        }
        JsonNode retained = contextManifest.path("retained_v004");
        Path retainedFreezePath = v004Root.resolve("data-freeze.json");
        ObjectNode retainedFreeze = readObject(retainedFreezePath);
        if (!retained.path("data_freeze_sha256").asText().equals(sha256(retainedFreezePath))
                || !retained.path("data_freeze_content_sha256").asText().equals(retainedFreeze.path("content_sha256").asText())
                || !JsonHashes.ownHash(retainedFreeze).equals(retainedFreeze.path("content_sha256").asText())
                || !retained.path("input_manifest_sha256").asText().equals(sha256(v004Root.resolve("input-manifest.json")))
                || !"2022-08-11".equals(retained.path("window_start").asText())
                || !"2026-09-20".equals(retained.path("window_end_exclusive").asText())
                || !V004_ASSETS.equals(orderedTextArray(retained.path("assets")))) {
            throw failure("daily context warmup is detached from retained v004 input or freeze identity");
        }
        Path currentNormalizedRoot = safeResolve(runRoot, inputManifest.path("root").asText());
        JsonNode assets = contextManifest.path("retained_v004").path("august_archives");
        for (String asset : V004_ASSETS) {
            JsonNode receipt = assets.path(asset);
            String retainedSha = receipt.path("normalized_v004_sha256").asText("");
            String path = receipt.path("normalized_v004_path").asText("");
            if (!retainedSha.matches("[0-9a-f]{64}") || !path.equals("normalized/klines_1h_" + asset + ".csv")
                    || !retainedSha.equals(sha256(v004Root.resolve(path)))
                    || !retainedSha.equals(sha256(currentNormalizedRoot.resolve("klines_1h_" + asset + ".csv")))) {
                throw failure("daily context warmup retained H1 identity differs from immutable v004 input for " + asset);
            }
        }
    }

    static List<HourBar> aggregateFourHour(String asset, List<HourBar> oneHour) {
        Map<Instant, HourBar[]> buckets = new TreeMap<>();
        for (HourBar bar : oneHour) {
            long aligned = Math.floorDiv(bar.start.toEpochMilli(), FOUR_HOURS_MS) * FOUR_HOURS_MS;
            Instant start = Instant.ofEpochMilli(aligned);
            int index = (int) (Duration.between(start, bar.start).toMillis() / HOUR_MS);
            if (index < 0 || index > 3) continue;
            buckets.computeIfAbsent(start, ignored -> new HourBar[4])[index] = bar;
        }
        ArrayList<HourBar> result = new ArrayList<>();
        for (Map.Entry<Instant, HourBar[]> entry : buckets.entrySet()) {
            HourBar[] parts = entry.getValue(); boolean complete = true;
            for (HourBar part : parts) if (part == null) { complete = false; break; }
            if (!complete) continue;
            double high = Arrays.stream(parts).mapToDouble(bar -> bar.high).max().orElseThrow();
            double low = Arrays.stream(parts).mapToDouble(bar -> bar.low).min().orElseThrow();
            double volume = Arrays.stream(parts).mapToDouble(bar -> bar.volume).sum();
            result.add(new HourBar(asset, entry.getKey(), parts[0].open, high, low, parts[3].close, volume));
        }
        return List.copyOf(result);
    }

    private static double nearestRank95(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
    }

    private static Path mappedPath(ObjectNode manifest, Path normalizedRoot, String key) {
        JsonNode value = manifest.path("files").path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw failure("input manifest is missing files." + key);
        return safeResolve(normalizedRoot, value.asText());
    }

    private static Path mappedAssetPath(ObjectNode manifest, Path normalizedRoot, String key, String asset) {
        JsonNode value = manifest.path("files").path(key).path(asset);
        if (!value.isTextual() || value.asText().isBlank()) throw failure("input manifest is missing files." + key + "." + asset);
        return safeResolve(normalizedRoot, value.asText());
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw failure("required nonblank text field is missing: " + field);
        return value.asText();
    }

    private static String digest(JsonNode object, String field) {
        String value = text(object, field);
        if (!value.matches("[0-9a-f]{64}")) throw failure("required SHA-256 field is invalid: " + field);
        return value;
    }

    private static Path safeResolve(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw failure("input path escapes its frozen root: " + relative);
        return target;
    }

    private static void recordHash(Path runRoot, Path file, Map<String, String> hashes) {
        if (!Files.isRegularFile(file)) throw failure("normalized input is missing: " + file);
        hashes.put(runRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'), sha256(file));
    }

    private static CsvHeader csvHeader(Path file, Set<String> requiredColumns) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null) throw failure("empty CSV input: " + file);
            String[] fields = line.replace("\uFEFF", "").split(",", -1);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < fields.length; i++) columns.put(fields[i].trim(), i);
            for (String required : requiredColumns) if (!columns.containsKey(required)) throw failure("CSV missing required column " + required + ": " + file);
            return new CsvHeader(columns, fields.length, line);
        } catch (IOException error) { throw failure("cannot read CSV header " + file + ": " + error.getMessage()); }
    }

    private static void readCsv(Path file, CsvHeader header, Consumer<String[]> consumer) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line; long row = 1;
            while ((line = reader.readLine()) != null) {
                row++;
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length != header.width) throw failure("CSV column count mismatch at " + file + ":" + row);
                consumer.accept(fields);
            }
        } catch (IOException error) { throw failure("cannot read CSV " + file + ": " + error.getMessage()); }
    }

    private static double finitePositive(String raw, String label) {
        double value = parseDouble(raw, label);
        if (!(value > 0.0)) throw failure(label + " must be positive");
        return value;
    }

    private static double finiteNonnegative(String raw, String label) {
        double value = parseDouble(raw, label);
        if (value < 0.0) throw failure(label + " cannot be negative");
        return value;
    }

    private static double parseDouble(String raw, String label) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) throw failure(label + " must be finite");
            return value;
        } catch (NumberFormatException error) { throw failure("invalid numeric " + label + ": " + raw); }
    }

    private static ObjectNode readObject(Path path) {
        try {
            JsonNode value = JsonHashes.mapper().readTree(Files.readAllBytes(path));
            if (!(value instanceof ObjectNode object)) throw failure("expected JSON object: " + path);
            return object;
        } catch (IOException error) { throw failure("cannot read JSON " + path + ": " + error.getMessage()); }
    }

    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("pom.xml"))
                    && Files.isRegularFile(path.resolve("docs/research/liquidation-exploratory-v005/exploratory-policy.json"))) {
                return path;
            }
        }
        throw failure("repository root with the frozen v005 policy cannot be located");
    }

    private static String sha256(Path file) {
        try { return JsonHashes.sha256(file); }
        catch (RuntimeException error) { throw failure("cannot hash file " + file + ": " + error.getMessage()); }
    }

    private static long fileSize(Path file) {
        try { return Files.size(file); }
        catch (IOException error) { throw failure("cannot read file size " + file + ": " + error.getMessage()); }
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull()) return BigDecimal.ZERO;
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try { return new BigDecimal(value.asText()); }
            catch (NumberFormatException error) { throw failure("invalid account decimal " + value.asText()); }
        }
        throw failure("expected a numeric account field");
    }

    private static int countClosedPositions(ObjectNode account) {
        HashSet<String> closed = new HashSet<>();
        for (String field : List.of("closed_episodes", "positions")) {
            for (JsonNode row : account.path(field)) {
                if ("CLOSED".equals(row.path("status").asText())) {
                    closed.add(row.path("asset").asText() + "|" + row.path("active_setup_id").asText());
                }
            }
        }
        return closed.size();
    }

    private static String requiredHash(ObjectNode options, String key) {
        JsonNode value = options.get(key);
        if (value == null || !value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) throw failure("--" + key + " must be a lowercase SHA-256 digest");
        return value.asText();
    }

    private static Path requiredPath(ObjectNode options, String key) {
        JsonNode value = options.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw failure("--" + key + " is required");
        return Path.of(value.asText()).toAbsolutePath().normalize();
    }

    private static RuntimeException failure(String message) { return new IllegalArgumentException(message); }

    private static void writeOptionalOutput(ObjectNode options, ObjectNode output) {
        if (!options.hasNonNull("out")) return;
        Path target = requiredPath(options, "out");
        try { writeNewDurable(target, output); }
        catch (IOException error) { throw failure("cannot write output " + target + ": " + error.getMessage()); }
    }

    private static void writeNewDurable(Path path, ObjectNode value) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] bytes = JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static Path executableJarPath() {
        String classPath = System.getProperty("java.class.path", "");
        String launchCommand = System.getProperty("sun.java.command", "");
        try (var classBytes = LiquidationHourlyDevelopmentReplayV1.class.getResourceAsStream("LiquidationHourlyDevelopmentReplayV1.class")) {
            if (classBytes == null) throw failure("cannot read the loaded diagnostic class bytes for JAR binding");
            return resolveExecutableJar(classPath, launchCommand, classBytes.readAllBytes());
        } catch (IOException error) { throw failure("cannot inspect the loaded diagnostic class: " + error.getMessage()); }
    }

    static Path resolveExecutableJar(String classPath, String launchCommand, byte[] loadedClassBytes) {
        String[] entries = classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1);
        if (entries.length != 1 || entries[0].isBlank()) {
            throw failure("historical replay requires a single physical JAR launched with java -jar");
        }
        String launchPath = entries[0];
        if (!(launchCommand.equals(launchPath) || launchCommand.startsWith(launchPath + " "))) {
            throw failure("historical replay launch command is not java -jar on the classpath JAR");
        }
        Path path = Path.of(launchPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
            throw failure("historical replay requires a physical immutable executable JAR, not a mutable class directory");
        }
        String classResource = LiquidationHourlyDevelopmentReplayV1.class.getName().replace('.', '/') + ".class";
        try (JarFile archive = new JarFile(path.toFile())) {
            var manifest = archive.getManifest();
            String mainClass = manifest == null ? "" : manifest.getMainAttributes().getValue("Main-Class");
            if (mainClass == null || !mainClass.startsWith("org.springframework.boot.loader.")) {
                throw failure("historical replay JAR is not the packaged Spring Boot executable");
            }
            JarEntry applicationClass = archive.getJarEntry("BOOT-INF/classes/" + classResource);
            if (applicationClass != null) {
                try (var input = archive.getInputStream(applicationClass)) {
                    if (Arrays.equals(loadedClassBytes, input.readAllBytes())) return path;
                }
            }
            var entriesInJar = archive.entries();
            while (entriesInJar.hasMoreElements()) {
                JarEntry entry = entriesInJar.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("BOOT-INF/lib/analytics-research-") || !name.endsWith(".jar")) continue;
                try (JarInputStream nested = new JarInputStream(archive.getInputStream(entry))) {
                    JarEntry nestedEntry;
                    while ((nestedEntry = nested.getNextJarEntry()) != null) {
                        if (classResource.equals(nestedEntry.getName())) {
                            if (Arrays.equals(loadedClassBytes, nested.readAllBytes())) return path;
                            throw failure("loaded diagnostic class bytes differ from the executable JAR's research module");
                        }
                    }
                }
            }
        } catch (IOException error) { throw failure("cannot verify the executable JAR's bundled research module: " + error.getMessage()); }
        throw failure("executable JAR does not contain the exact loaded hourly diagnostic class");
    }

    private static boolean hasRunDirectories(Path root) {
        if (!Files.isDirectory(root)) return false;
        try (var stream = Files.list(root)) { return stream.anyMatch(Files::isDirectory); }
        catch (IOException error) { throw failure("cannot inspect prior run directory: " + error.getMessage()); }
    }

    private static ArrayNode sourceHashRows(Map<String, String> hashes) {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        hashes.forEach((path, digest) -> rows.addObject().put("path", path).put("sha256", digest));
        return rows;
    }

    private static String key(String asset, Instant time) { return asset + "|" + time.toEpochMilli(); }

    private static int assetRank(String asset) {
        int index = LiquidationStructureRouterV1.SUPPORTED_ASSET_ORDER.indexOf(asset);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static List<String> policyAssetOrder(ObjectNode policy) {
        return orderedTextArray(policy.path("assets"));
    }

    private static List<String> orderedTextArray(JsonNode assets) {
        if (!assets.isArray()) return List.of();
        ArrayList<String> result = new ArrayList<>();
        for (JsonNode asset : assets) {
            if (!asset.isTextual()) return List.of();
            result.add(asset.asText());
        }
        return List.copyOf(result);
    }

    private static List<String> manifestAssetOrder(ObjectNode manifest) {
        JsonNode raw = manifest.path("assets");
        if (raw.isMissingNode()) return LEGACY_ASSETS;
        if (!raw.isArray()) throw failure("input manifest assets must be an ordered array");
        ArrayList<String> assets = new ArrayList<>();
        for (JsonNode value : raw) {
            if (!value.isTextual()) throw failure("input manifest assets must contain only symbols");
            assets.add(value.asText());
        }
        List<String> result = List.copyOf(assets);
        if (!result.equals(LEGACY_ASSETS) && !result.equals(V004_ASSETS)) {
            throw failure("input manifest assets must equal the exact frozen legacy-four or nine-asset order");
        }
        return result;
    }

    private record CsvHeader(Map<String, Integer> columns, int width, String raw) {
        int index(String name) { return columns.get(name); }
    }
    private record DailyRow(String asset, String symbol, LocalDate day, double longLiquidationsUsd, double shortLiquidationsUsd) {}
    private record PendingFill(LiquidationStructureRouterV1.ConfirmedIntent intent, Instant fillAt, HourBar bar) {}
    private record VariantResult(String id, ObjectNode account,
            Map<String, LiquidationStructureRouterV1.QualifiedDailyStressEvent> stressEvents,
            Map<String, LiquidationStructureRouterV1.ConfirmedIntent> firstDecisions,
            ArrayNode rejections, Funnel funnel, ObjectNode entryRuleAudit) {}

    static final class Input {
        final List<String> assets;
        final List<LiquidationStructureRouterV1.Observation> features;
        final Map<String, List<HourBar>> barsByAsset;
        final NavigableMap<Instant, List<HourBar>> barsByStart;
        final Map<String, HourBar> barsByAssetStart, barsByAssetAndStart;
        final Map<String, NavigableMap<Instant, Double>> pricesByAssetCloseTime;
        final ObjectNode coverage;
        final Map<String, String> fileHashes;
        final List<String> preflightBlockers;
        final int dailyRows, validDailyWindows, longStressFlags, shortStressFlags, missingOiEndpoints;
        final String manifestByteSha256;
        final Path manifestPath;
        final List<LiquidationStructureRouterV1.DailyPriceContext> dailyPriceContexts;
        final boolean dailyContextWarmupVerified;
        Input(List<LiquidationStructureRouterV1.Observation> features, Map<String, List<HourBar>> barsByAsset,
                NavigableMap<Instant, List<HourBar>> barsByStart, Map<String, HourBar> barsByAssetStart,
                Map<String, HourBar> barsByAssetAndStart, Map<String, NavigableMap<Instant, Double>> pricesByAssetCloseTime,
                ObjectNode coverage, Map<String, String> fileHashes, List<String> preflightBlockers,
                int dailyRows, int validDailyWindows, int longStressFlags, int shortStressFlags, int missingOiEndpoints,
                String manifestByteSha256, Path manifestPath) {
            this(LEGACY_ASSETS, features, barsByAsset, barsByStart, barsByAssetStart, barsByAssetAndStart,
                    pricesByAssetCloseTime, coverage, fileHashes, preflightBlockers, dailyRows, validDailyWindows,
                    longStressFlags, shortStressFlags, missingOiEndpoints, manifestByteSha256, manifestPath, List.of());
        }
        Input(List<String> assets, List<LiquidationStructureRouterV1.Observation> features, Map<String, List<HourBar>> barsByAsset,
                NavigableMap<Instant, List<HourBar>> barsByStart, Map<String, HourBar> barsByAssetStart,
                Map<String, HourBar> barsByAssetAndStart, Map<String, NavigableMap<Instant, Double>> pricesByAssetCloseTime,
                ObjectNode coverage, Map<String, String> fileHashes, List<String> preflightBlockers,
                int dailyRows, int validDailyWindows, int longStressFlags, int shortStressFlags, int missingOiEndpoints,
                String manifestByteSha256, Path manifestPath) {
            this(assets, features, barsByAsset, barsByStart, barsByAssetStart, barsByAssetAndStart,
                    pricesByAssetCloseTime, coverage, fileHashes, preflightBlockers, dailyRows, validDailyWindows,
                    longStressFlags, shortStressFlags, missingOiEndpoints, manifestByteSha256, manifestPath, List.of(), false);
        }
        Input(List<String> assets, List<LiquidationStructureRouterV1.Observation> features, Map<String, List<HourBar>> barsByAsset,
                NavigableMap<Instant, List<HourBar>> barsByStart, Map<String, HourBar> barsByAssetStart,
                Map<String, HourBar> barsByAssetAndStart, Map<String, NavigableMap<Instant, Double>> pricesByAssetCloseTime,
                ObjectNode coverage, Map<String, String> fileHashes, List<String> preflightBlockers,
                int dailyRows, int validDailyWindows, int longStressFlags, int shortStressFlags, int missingOiEndpoints,
                String manifestByteSha256, Path manifestPath,
                List<LiquidationStructureRouterV1.DailyPriceContext> dailyPriceContexts) {
            this(assets, features, barsByAsset, barsByStart, barsByAssetStart, barsByAssetAndStart,
                    pricesByAssetCloseTime, coverage, fileHashes, preflightBlockers, dailyRows, validDailyWindows,
                    longStressFlags, shortStressFlags, missingOiEndpoints, manifestByteSha256, manifestPath,
                    dailyPriceContexts, false);
        }
        Input(List<String> assets, List<LiquidationStructureRouterV1.Observation> features, Map<String, List<HourBar>> barsByAsset,
                NavigableMap<Instant, List<HourBar>> barsByStart, Map<String, HourBar> barsByAssetStart,
                Map<String, HourBar> barsByAssetAndStart, Map<String, NavigableMap<Instant, Double>> pricesByAssetCloseTime,
                ObjectNode coverage, Map<String, String> fileHashes, List<String> preflightBlockers,
                int dailyRows, int validDailyWindows, int longStressFlags, int shortStressFlags, int missingOiEndpoints,
                String manifestByteSha256, Path manifestPath,
                List<LiquidationStructureRouterV1.DailyPriceContext> dailyPriceContexts, boolean dailyContextWarmupVerified) {
            this.assets = List.copyOf(assets); this.features = List.copyOf(features); this.barsByAsset = Map.copyOf(barsByAsset);
            this.barsByStart = java.util.Collections.unmodifiableNavigableMap(barsByStart);
            this.barsByAssetStart = Map.copyOf(barsByAssetStart); this.barsByAssetAndStart = Map.copyOf(barsByAssetAndStart);
            this.pricesByAssetCloseTime = Map.copyOf(pricesByAssetCloseTime); this.coverage = coverage;
            this.dailyPriceContexts = List.copyOf(dailyPriceContexts);
            this.fileHashes = Map.copyOf(fileHashes); this.preflightBlockers = List.copyOf(preflightBlockers);
            this.dailyRows = dailyRows; this.validDailyWindows = validDailyWindows;
            this.longStressFlags = longStressFlags; this.shortStressFlags = shortStressFlags;
            this.missingOiEndpoints = missingOiEndpoints; this.manifestByteSha256 = manifestByteSha256; this.manifestPath = manifestPath;
            this.dailyContextWarmupVerified = dailyContextWarmupVerified;
        }
    }

    static final class HourBar {
        final String asset; final Instant start; final double open, high, low, close, volume;
        HourBar(String asset, Instant start, double open, double high, double low, double close, double volume) {
            this.asset = asset; this.start = start; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
        }
    }

    private static final class Funnel {
        final int dailyRows, validDailyWindows, longStressFlags, shortStressFlags, missingOiEndpoints;
        int qualifiedEvents, confirmedIntents, stageOneConfirmationIntents, routerRejections,
                outsideWindowSuppressed, outsideWindowRouterRejections, submittedFillAttempts, filledTranches,
                fillRejections, closedPositions;
        final Set<String> uniqueStageOneSetups = new HashSet<>();
        Funnel(int dailyRows, int validDailyWindows, int longStressFlags, int shortStressFlags, int missingOiEndpoints) {
            this.dailyRows = dailyRows; this.validDailyWindows = validDailyWindows;
            this.longStressFlags = longStressFlags; this.shortStressFlags = shortStressFlags;
            this.missingOiEndpoints = missingOiEndpoints;
        }
        ObjectNode toJson() {
            return JsonHashes.mapper().createObjectNode().put("daily_rows", dailyRows)
                    .put("valid_90_day_windows", validDailyWindows).put("long_liquidation_stress_flags", longStressFlags)
                    .put("short_liquidation_stress_flags", shortStressFlags).put("qualified_source_events", qualifiedEvents)
                    .put("stage_one_confirmation_intents", stageOneConfirmationIntents)
                    .put("unique_stage_one_setups", uniqueStageOneSetups.size()).put("all_confirmation_intents", confirmedIntents)
                    .put("in_window_router_rejections", routerRejections).put("outside_window_router_rejections", outsideWindowRouterRejections)
                    .put("outside_window_intents_suppressed", outsideWindowSuppressed).put("submitted_fill_attempts", submittedFillAttempts)
                    .put("filled_tranches", filledTranches).put("fill_rejections", fillRejections)
                    .put("missing_h4_oi_endpoints", missingOiEndpoints).put("completed_positions", closedPositions);
        }
    }
}
