package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Frozen v004 benchmark boundary.  This preflight owns no outcome statistic. */
public final class StrategyOperatingCharacteristicsV4 {
    private static final long MINUTE_MS = 60_000L;
    private static final long FOUR_HOUR_MS = 4L * 60L * MINUTE_MS;
    private static final long HORIZON_MS = 14_400L * MINUTE_MS;
    private static final int HORIZON_MINUTES = 14_400;
    private static final String[] ASSETS = {"btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave"};
    private static final Map<String, Double> BASE_PRICE = Map.of(
            "BTC", 30_000D, "ETH", 2_000D, "SOL", 30D, "BNB", 250D,
            "XRP", .5D, "ADA", .3D, "LINK", 7D, "AAVE", 70D);
    private static final Map<String, Double> BASE_VOLUME = Map.of(
            "BTC", 100_000_000D, "ETH", 50_000_000D, "SOL", 10_000_000D, "BNB", 12_000_000D,
            "XRP", 8_000_000D, "ADA", 6_000_000D, "LINK", 5_000_000D, "AAVE", 2_500_000D);
    private static final Map<String, Double> ASSET_SIGMA_MULTIPLIER = Map.of(
            "BTC", 1D, "ETH", 1.15D, "SOL", 1.4D, "BNB", 1.1D,
            "XRP", 1.35D, "ADA", 1.4D, "LINK", 1.25D, "AAVE", 1.55D);

    private StrategyOperatingCharacteristicsV4() {}

    /**
     * Validates the immutable v004 generator contract and returns the exact
     * preflight workload.  Outcome generation is deliberately a separate
     * bounded operation; this method cannot report power from a toy statistic.
     */
    public static ObjectNode preflight(ObjectNode plan) {
        if (plan == null || !"strategy-evaluator-operating-characteristics-plan/4".equals(plan.path("schema").asText())
                || plan.path("version").asInt(-1) != 4
                || !plan.path("frozen_before_outcomes").asBoolean(false)
                || plan.path("outcomes_opened").asBoolean(true)
                || plan.path("promotion_eligible").asBoolean(true)
                || !plan.path("content_sha256").asText().equals(JsonHashes.ownHash(plan))) {
            throw new IllegalArgumentException("operating-characteristics v004 plan is not frozen and self-bound");
        }
        JsonNode generator = plan.path("generator");
        JsonNode raw = generator.path("raw_counts");
        JsonNode geometry = generator.path("cluster_geometry");
        if (raw.path("raw_event_count").asInt(-1) != 50 || raw.path("raw_control_target_count").asInt(-1) != 50
                || geometry.path("event_block_count").asInt(geometry.path("cluster_count").asInt(-1)) != 32
                || geometry.path("minimum_block_spacing_days").asInt(-1) != 42
                || plan.path("targets").path("minimum_independent_units_per_replication").asInt(-1) != 30
                || plan.path("replications").asInt(-1) != 50 || plan.path("cell_count").asInt(-1) != 4) {
            throw new IllegalArgumentException("operating-characteristics v004 workload differs from frozen design");
        }
        if (generator.path("common_market_shocks").path("factor_weight").asDouble(Double.NaN) != .35D
                || generator.path("common_market_shocks").path("scale_by_regime").path("CALM").asDouble(Double.NaN) != .00055D
                || generator.path("common_market_shocks").path("scale_by_regime").path("STRESSED").asDouble(Double.NaN) != .0012D
                || generator.path("common_market_shocks").path("regime_transition").path("CALM_TO_STRESSED").asDouble(Double.NaN) != .003D
                || generator.path("common_market_shocks").path("regime_transition").path("STRESSED_TO_CALM").asDouble(Double.NaN) != .08D
                || generator.path("asset_specific_noise").path("ar1_phi").asDouble(Double.NaN) != .2D
                || generator.path("setup_shock").path("treated_volume_multiple").asDouble(Double.NaN) != 2.4D
                || generator.path("setup_shock").path("control_volume_multiple_range").path(0).asDouble(Double.NaN) != 1.2D
                || generator.path("setup_shock").path("control_return_range").path(0).asDouble(Double.NaN) != -.04D) {
            throw new IllegalArgumentException("operating-characteristics v004 generator semantics differ from frozen design");
        }
        int preflightSeriesPerReplication = 20;
        long preflightMinuteBars = 576_000L;
        long fullMinuteBars = plan.path("resource_budget").path("expected_generated_minute_bars").asLong(-1);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-preflight/1")
                .put("version", 1).put("status", "READY_PRE_OUTCOME")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("binding_fixed_evaluator", "StrategyFixedBaselineV5+TradeLifecycleV5+StrategyResearchImprovementV1.disposition")
                .put("shared_physical_evaluator", true).put("toy_statistic", false)
                .put("series_per_replication", preflightSeriesPerReplication)
                .put("preflight_replications", 2).put("preflight_minute_bars", preflightMinuteBars)
                .put("full_expected_minute_bars", fullMinuteBars)
                .put("outcomes_opened", false).put("promotion_eligible", false)
                .set("build_identity", BuildIdentityService.describe(StrategyOperatingCharacteristicsV4.class));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /**
     * Runs the frozen v004 generator through the existing fixed evaluator for
     * either its declared performance prefix or its full four-cell budget.
     * Synthetic output is permanently diagnostic: it has no PIT or observed
     * fill standing and never writes the real family exposure head.
     */
    public static ObjectNode run(ObjectNode options) {
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options;
        ObjectNode plan = readObject(input, "plan");
        ObjectNode preflight = preflight(plan);
        ObjectNode baseline = readObject(input, "baseline");
        ObjectNode controls = readObject(input, "controls");
        ObjectNode experiment = readObject(input, "experiment");
        validateBindings(plan, baseline, controls, experiment);
        ObjectNode sourceFreeze = readObject(input, "source_freeze");
        validateSourceFreeze(plan, sourceFreeze);
        boolean full = input.path("full").asBoolean(false);
        int replications = input.path("replications").asInt(full
                ? plan.path("replications").asInt(-1)
                : plan.path("preflight_benchmark").path("replications").asInt(2));
        int episodes = input.path("episodes_per_replication").asInt(full
                ? plan.path("episodes_per_replication").asInt(-1)
                : plan.path("preflight_benchmark").path("episodes_per_replication").asInt(10));
        int expectedReplications = full ? plan.path("replications").asInt(-1) : 2;
        int expectedEpisodes = full ? plan.path("episodes_per_replication").asInt(-1) : 10;
        if (replications != expectedReplications || episodes != expectedEpisodes) {
            throw new IllegalArgumentException("synthetic v004 run must use the frozen full budget or exact preflight prefix");
        }
        if (!full && (replications != 2 || episodes != 10)) {
            throw new IllegalArgumentException("synthetic preflight is frozen to 2 replications and 10 event/control series");
        }
        List<Cell> cells = full ? List.of(new Cell("NO_EDGE", 0), new Cell("PLANTED_EDGE", 0),
                new Cell("PLANTED_EDGE", .02), new Cell("PLANTED_EDGE", .04))
                : List.of(new Cell("NO_EDGE", 0));
        long started = System.nanoTime();
        ArrayNode repRows = JsonHashes.mapper().createArrayNode();
        int planned = cells.size() * replications;
        long deadlineNanos = started + (full ? 720L : 20L) * 60L * 1_000_000_000L;
        long maxRssBytes = (full ? 8L : 6L) * 1024L * 1024L * 1024L;
        boolean resourceAborted = false;
        String resourceAbortReason = "";
        long peakRssBytes = rssBytes();
        outer:
        for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
            Cell cell = cells.get(cellIndex);
            for (int replication = 0; replication < replications; replication++) {
                if (System.nanoTime() > deadlineNanos || rssBytes() > maxRssBytes) {
                    resourceAborted = true;
                    resourceAbortReason = System.nanoTime() > deadlineNanos ? "WALL_DEADLINE_EXCEEDED" : "RSS_BUDGET_EXCEEDED";
                    for (int remainingCell = cellIndex; remainingCell < cells.size(); remainingCell++) {
                        int startReplication = remainingCell == cellIndex ? replication : 0;
                        for (int remaining = startReplication; remaining < replications; remaining++) {
                            Cell missing = cells.get(remainingCell);
                            repRows.add(incompleteRow(missing, remaining, "resource budget aborted before replication"));
                        }
                    }
                    break outer;
                }
                int scenarioIndex = "NO_EDGE".equals(cell.name()) ? 0 : 1;
                long seed = replicateSeed(plan, scenarioIndex, cell.effect(), replication);
                ObjectNode row = runReplication(plan, baseline, controls, experiment, cell, scenarioIndex,
                        replication, seed, episodes, deadlineNanos, maxRssBytes);
                repRows.add(row);
                peakRssBytes = Math.max(peakRssBytes, rssBytes());
                if ("COMPUTE_INCOMPLETE".equals(row.path("status").asText())
                        && row.path("error").asText("").startsWith("RESOURCE_")) {
                    resourceAborted = true;
                    resourceAbortReason = row.path("error").asText();
                    for (int remainingCell = cellIndex; remainingCell < cells.size(); remainingCell++) {
                        int startReplication = remainingCell == cellIndex ? replication + 1 : 0;
                        for (int remaining = startReplication; remaining < replications; remaining++) {
                            Cell missing = cells.get(remainingCell);
                            repRows.add(incompleteRow(missing, remaining, "resource budget aborted during replication"));
                        }
                    }
                    break outer;
                }
            }
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000D;
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-result/1")
                .put("version", 1).put("status", full ? "COMPLETE" : "PREFIX_COMPLETE")
                .put("scope", "CONDITIONAL_FIXED_BASELINE_SYNTHETIC_DIAGNOSTIC")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("preflight_sha256", preflight.path("content_sha256").asText())
                .put("generator_schema", "synthetic-ohlc-episode-generator/4")
                .put("source_frozen_before_outcomes", true)
                .put("prior_draft_outcomes_opened", sourceFreeze.path("prior_draft_outcomes_opened").asBoolean(false))
                .put("binding_fixed_evaluator", preflight.path("binding_fixed_evaluator").asText())
                .put("shared_physical_evaluator", true).put("toy_statistic", false)
                .put("synthetic_source", "GENERATED_OHLCV_DEVELOPMENT_INPUT")
                .put("pit_valid", false).put("observed_exchange_fills", false)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("outcomes_opened", true).put("optimizer_invoked", false)
                .put("planned_replications", planned).put("replication_count", repRows.size())
                .put("episodes_per_replication", episodes).put("runtime_seconds", seconds)
                .put("budget_mode", full ? "FULL_FROZEN_V004" : "PREDECLARED_PERFORMANCE_PREFIX");
        if (resourceAborted) result.put("status", "COMPUTE_INCOMPLETE");
        result.put("resource_aborted", resourceAborted).put("max_rss_bytes", maxRssBytes)
                .put("max_wall_minutes", full ? 720 : 20).put("peak_rss_bytes", peakRssBytes)
                .put("heap_used_bytes", usedHeapBytes());
        if (resourceAborted) result.put("resource_abort_reason", resourceAbortReason);
        result.putObject("frozen_input_bindings")
                .put("baseline_sha256", baseline.path("content_sha256").asText())
                .put("control_spec_sha256", controls.path("content_sha256").asText())
                .put("experiment_sha256", experiment.path("content_sha256").asText());
        ObjectNode buildIdentity = BuildIdentityService.describe(StrategyFixedBaselineV5.class);
        result.put("executor_identity_sha256", buildIdentity.path("executable").path("sha256").asText());
        result.set("build_identity", buildIdentity);
        result.set("source_freeze", sourceFreeze);
        result.set("replications", repRows);
        result.set("decision_summary", summarize(repRows, cells));
        result.put("content_sha256", JsonHashes.ownHash(result));
        String out = input.path("out").asText("");
        if (!out.isBlank()) writeImmutable(Path.of(out), result);
        return result;
    }

    private static void validateBindings(ObjectNode plan, ObjectNode baseline,
            ObjectNode controls, ObjectNode experiment) {
        requireOwnHash(baseline, "baseline");
        requireOwnHash(controls, "controls");
        requireOwnHash(experiment, "experiment");
        requireBinding(plan, "binding_baseline_sha256", baseline.path("content_sha256").asText());
        requireBinding(plan, "binding_control_spec_sha256", controls.path("content_sha256").asText());
        requireBinding(plan, "binding_experiment_sha256", experiment.path("content_sha256").asText());
        if (!baseline.path("content_sha256").asText().equals(experiment.path("baseline_sha256").asText())
                || !controls.path("content_sha256").asText().equals(experiment.path("controls_sha256").asText())) {
            throw new IllegalArgumentException("operating-characteristics inputs do not bind to the frozen experiment");
        }
        ObjectNode build = BuildIdentityService.describe(StrategyOperatingCharacteristicsV4.class);
        if (!"KNOWN".equals(build.path("status").asText())
                || !"JAR".equals(build.path("executable").path("kind").asText())
                || !build.path("executable").path("sha256").asText().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("operating-characteristics requires a packaged executable identity");
        }
    }

    private static void validateSourceFreeze(ObjectNode plan, ObjectNode sourceFreeze) {
        if (!"strategy-evaluator-operating-characteristics-source-freeze/1".equals(sourceFreeze.path("schema").asText())
                || !sourceFreeze.path("frozen_before_outcomes").asBoolean(false)
                || !sourceFreeze.path("content_sha256").asText().equals(JsonHashes.ownHash(sourceFreeze))
                || !plan.path("content_sha256").asText().equals(sourceFreeze.path("plan_sha256").asText())) {
            throw new IllegalArgumentException("synthetic D source freeze is not self-bound to v004 plan");
        }
        ObjectNode build = BuildIdentityService.describe(StrategyOperatingCharacteristicsV4.class);
        if (!build.path("executable").path("sha256").asText()
                .equals(sourceFreeze.path("executor_identity_sha256").asText())
                || !build.path("compiled").path("input_fingerprint").asText()
                        .equals(sourceFreeze.path("build_input_fingerprint").asText())) {
            throw new IllegalArgumentException("synthetic D executable differs from frozen source receipt");
        }
        if (!sourceFreeze.path("generator_source_sha256").asText().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("synthetic D source freeze lacks generator source hash");
        }
    }

    private static void requireOwnHash(ObjectNode value, String label) {
        if (!value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))) {
            throw new IllegalArgumentException(label + " content hash is invalid");
        }
    }

    private static void requireBinding(ObjectNode plan, String field, String actual) {
        if (!actual.equals(plan.path(field).asText())) {
            throw new IllegalArgumentException("frozen plan " + field + " does not bind supplied input");
        }
    }

    private static ObjectNode runReplication(ObjectNode plan, ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment, Cell cell, int cellIndex, int replication, long seed, int episodes,
            long deadlineNanos, long maxRssBytes) {
        Path root;
        try { root = Files.createTempDirectory("strategy-d-v004-"); }
        catch (IOException error) { throw new IllegalArgumentException("cannot create synthetic role root", error); }
        try {
            ArrayList<ObjectNode> rawSetup = new ArrayList<>();
            ArrayList<List<ObjectNode>> setupSeriesRows = new ArrayList<>();
            ArrayList<ObjectNode> labels = new ArrayList<>();
            ArrayList<ObjectNode> executions = new ArrayList<>();
            ArrayList<ObjectNode> setupAudit = new ArrayList<>();
            Map<String, StrategyFixedBaselineV5.Role> roles = new LinkedHashMap<>();
            List<Episode> episodeSpecs = buildEpisodes(episodes, seed, replication);
            Map<Integer, CommonPath> commonByCluster = new LinkedHashMap<>();
            Map<Integer, CommonPath> controlCommonByCluster = new LinkedHashMap<>();
            for (Episode episode : episodeSpecs) {
                checkResource(deadlineNanos, maxRssBytes);
                commonByCluster.computeIfAbsent(episode.cluster(), ignored ->
                        commonPath(new SplittableRandom(seed + episode.cluster() * 97_531L), HORIZON_MINUTES));
                controlCommonByCluster.computeIfAbsent(episode.cluster(), ignored ->
                        commonPath(new SplittableRandom(seed + 7_000_001L + episode.cluster() * 97_531L), HORIZON_MINUTES));
                long setupSeed = seed + episode.eventIndex() * 101_003L;
                SetupSeries controlSetup = buildSetupSeries(episode.asset(), episode.symbol(), episode.controlId(),
                        episode.controlDecision(), false, new SplittableRandom(setupSeed));
                SetupSeries eventSetup = buildSetupSeries(episode.asset(), episode.symbol(), episode.eventId(),
                        episode.eventDecision(), true, new SplittableRandom(setupSeed));
                controlSetup.bars().forEach(row -> rawSetup.add((ObjectNode) row));
                eventSetup.bars().forEach(row -> rawSetup.add((ObjectNode) row));
                setupSeriesRows.add(toObjectRows(controlSetup.bars()));
                setupSeriesRows.add(toObjectRows(eventSetup.bars()));
                if (setupAudit.size() < 2) {
                    setupAudit.addAll(toObjectRows(controlSetup.audit()));
                    setupAudit.addAll(toObjectRows(eventSetup.audit()));
                }
                addOutcomeRoles(root, roles, labels, executions, episode.asset(), episode.symbol(), episode.eventId(),
                        episode.eventDecision(), eventSetup.finalClose(), true, cell.effect(),
                        commonByCluster.get(episode.cluster()), new SplittableRandom(seed ^ episode.eventIndex() * 17_003L),
                        deadlineNanos, maxRssBytes);
                addOutcomeRoles(root, roles, labels, executions, episode.asset(), episode.symbol(), episode.controlId(),
                        episode.controlDecision(), controlSetup.finalClose(), false, 0,
                        controlCommonByCluster.get(episode.cluster()),
                        new SplittableRandom(seed ^ (episode.eventIndex() + 10_000L) * 17_003L),
                        deadlineNanos, maxRssBytes);
            }
            List<ObjectNode> features = new ArrayList<>();
            for (List<ObjectNode> series : setupSeriesRows) {
                checkResource(deadlineNanos, maxRssBytes);
                features.addAll(StrategyFixedBaselineV5.deriveFeatures(series, List.of()));
            }
            writeRole(root, roles, "signal_bars", arrayNode(rawSetup));
            ObjectNode physicalMarker = JsonHashes.mapper().createObjectNode()
                    .put("schema", "strategy-fixed-baseline-physical-input/1")
                    .put("status", "SYNTHETIC_DIAGNOSTIC")
                    .put("source", "synthetic-ohlc-episode-generator/4")
                    .put("plan_sha256", plan.path("content_sha256").asText());
            physicalMarker.put("content_sha256", JsonHashes.ownHash(physicalMarker));
            writeAssetRoles(root, roles);
            ObjectNode syntheticInput = JsonHashes.mapper().createObjectNode()
                    .put("plan_sha256", plan.path("content_sha256").asText()).put("replication", replication)
                    .put("cell", cell.name()).put("effect_size", cell.effect()).put("seed", seed)
                    .put("generator", "synthetic-ohlc-episode-generator/4");
            StrategyFixedBaselineV5.PhysicalInput physical = new StrategyFixedBaselineV5.PhysicalInput(
                    root, "synthetic-d-v004", JsonHashes.canonicalSha256(syntheticInput), roles, features,
                    labels, executions, physicalMarker);
            ObjectNode exposure = JsonHashes.mapper().createObjectNode()
                    .put("content_sha256", JsonHashes.sha256("synthetic-exposure:" + seed));
            ObjectNode lineage = JsonHashes.mapper().createObjectNode().put("status", "SYNTHETIC_DIAGNOSTIC")
                    .put("family_history", "synthetic-only; no canonical exposure append")
                    .put("promotion_eligible", false);
            ObjectNode portfolio = JsonHashes.mapper().createObjectNode().put("starting_cash_usdt", 10_000)
                    .put("content_sha256", JsonHashes.sha256("synthetic-portfolio-v004"));
            ObjectNode evaluated = StrategyFixedBaselineV5.evaluate(JsonHashes.mapper().createObjectNode(), baseline,
                    controls, experiment, portfolio, physical, exposure, root.resolve("synthetic-exposure-head.json"),
                    lineage, "", Double.NaN);
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("cell", cell.name()).put("effect_size", cell.effect()).put("replication", replication)
                    .put("seed", seed).put("status", evaluated.path("status").asText())
                    .put("decision", "ELIGIBLE".equals(evaluated.path("disposition").path("primary_reason").asText()))
                    .put("event_count", evaluated.path("event_count").asInt())
                    .put("paired_count", evaluated.path("matched_control_count").asInt())
                    .put("independent_units", evaluated.path("independent_market_episode_count").asInt())
                    .put("economic_semantic_sha256", evaluated.path("economic_semantic_sha256").asText())
                    .put("generator_input_sha256", physical.contentSha256());
            row.set("disposition", evaluated.path("disposition").deepCopy());
            row.set("setup_minute_audit", arrayNode(setupAudit));
            row.set("representative_setup_events", firstRows(evaluated.path("setup_events"), 2));
            row.set("representative_control_selections", firstRows(evaluated.path("control_selections"), 2));
            row.set("representative_attempts", firstRows(evaluated.path("attempts"), 2));
            row.set("representative_independent_market_episodes",
                    firstRows(evaluated.path("independent_market_episodes"), 8));
            row.set("metrics", evaluated.path("metrics").deepCopy());
            ObjectNode portfolioSummary = JsonHashes.mapper().createObjectNode();
            for (String field : List.of("account_currency", "trade_count", "net_pnl_usdt", "max_drawdown_usdt",
                    "ending_equity_usdt", "ending_minus_starting_equals_net", "sum_check")) {
                if (evaluated.path("portfolio").has(field)) portfolioSummary.set(field,
                        evaluated.path("portfolio").get(field).deepCopy());
            }
            row.set("portfolio_summary", portfolioSummary);
            return row;
        } catch (RuntimeException error) {
            return JsonHashes.mapper().createObjectNode().put("cell", cell.name()).put("effect_size", cell.effect())
                    .put("replication", replication).put("seed", seed).put("status", "COMPUTE_INCOMPLETE")
                    .put("decision", false).put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            deleteTree(root);
        }
    }

    /**
     * Builds the 32 completed setup bars from their 240 one-minute children.
     * Keeping this method's small signature is intentional: the review suite
     * calls it directly to recompute the setup predicate without opening any
     * future outcome rows.
     */
    private static ArrayNode setupSeries(String asset, String symbol, String id, long decision,
            boolean treated, SplittableRandom random) {
        return buildSetupSeries(asset, symbol, id, decision, treated, random).bars();
    }

    private static SetupSeries buildSetupSeries(String asset, String symbol, String id, long decision,
            boolean treated, SplittableRandom random) {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        ArrayNode audit = JsonHashes.mapper().createArrayNode();
        double price = BASE_PRICE.getOrDefault(asset.toUpperCase(), 100D);
        double baseVolume = BASE_VOLUME.getOrDefault(asset.toUpperCase(), 1_000_000D);
        double setupLogSigma = .02D / Math.sqrt(6D);
        double[] minuteVolumeMultipliers = new double[240];
        for (int minute = 0; minute < minuteVolumeMultipliers.length; minute++) {
            minuteVolumeMultipliers[minute] = Math.exp(.35D * normal(random));
        }
        for (int index = 0; index < 32; index++) {
            long event = decision - (32L - index) * FOUR_HOUR_MS;
            double logReturn = index == 31
                    ? Math.log(treated ? .91D : .96D)
                    : (index % 2 == 0 ? setupLogSigma : -setupLogSigma);
            double open = price;
            ArrayNode minutes = JsonHashes.mapper().createArrayNode();
            double minuteOpen = open;
            double minuteLogReturn = logReturn / 240D;
            double aggregateHigh = open;
            double aggregateLow = open;
            double aggregateVolume = 0;
            for (int minute = 0; minute < 240; minute++) {
                long minuteTime = event + minute * MINUTE_MS;
                double minuteClose = minuteOpen * Math.exp(minuteLogReturn);
                double intrabar = .00035D * Math.abs(normal(random));
                double high = Math.max(minuteOpen, minuteClose) * Math.exp(intrabar);
                double low = Math.min(minuteOpen, minuteClose) * Math.exp(-intrabar);
                double volume = baseVolume / 240D * minuteVolumeMultipliers[minute]
                        * (index == 31 ? (treated ? 2.4D : 1.2D) : 1D);
                ObjectNode minuteRow = JsonHashes.mapper().createObjectNode()
                        .put("episode_id", id).put("asset", asset).put("symbol", symbol)
                        .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                        .put("event_time", Instant.ofEpochMilli(minuteTime).toString())
                        .put("close_time", Instant.ofEpochMilli(minuteTime + MINUTE_MS).toString())
                        .put("availability_time", Instant.ofEpochMilli(minuteTime + MINUTE_MS).toString())
                        .put("open", minuteOpen).put("high", high).put("low", low)
                        .put("close", minuteClose).put("volume", volume);
                minutes.add(minuteRow);
                aggregateHigh = Math.max(aggregateHigh, high);
                aggregateLow = Math.min(aggregateLow, low);
                aggregateVolume += volume;
                minuteOpen = minuteClose;
            }
            price = minuteOpen;
            ObjectNode bar = JsonHashes.mapper().createObjectNode()
                    .put("episode_id", id).put("asset", asset).put("symbol", symbol)
                    .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                    .put("event_time", Instant.ofEpochMilli(event).toString())
                    .put("close_time", Instant.ofEpochMilli(event + FOUR_HOUR_MS).toString())
                    .put("availability_time", Instant.ofEpochMilli(event + FOUR_HOUR_MS).toString())
                    .put("open", open).put("high", aggregateHigh).put("low", aggregateLow)
                    .put("close", price).put("volume", aggregateVolume);
            rows.add(bar);
            if (index == 31 || (id.endsWith("-0") && index == 0)) {
                ObjectNode sample = JsonHashes.mapper().createObjectNode()
                        .put("episode_id", id).put("setup_index", index)
                        .set("bars", minutes);
                audit.add(sample);
            }
        }
        return new SetupSeries(rows, audit, price);
    }

    private static List<ObjectNode> toObjectRows(ArrayNode rows) {
        List<ObjectNode> result = new ArrayList<>();
        rows.forEach(row -> result.add((ObjectNode) row));
        return result;
    }

    private static List<Episode> buildEpisodes(int requested, long seed, int replication) {
        if (requested != 10 && requested != 50) {
            throw new IllegalArgumentException("v004 generator supports only 10-prefix or 50-full raw events");
        }
        int clusters = requested == 50 ? 32 : 8;
        int pairedClusters = requested == 50 ? 18 : 2;
        long origin = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
        long seedOffsetDays = Math.floorMod(seed, 365L);
        List<Episode> result = new ArrayList<>();
        int eventIndex = 0;
        int singletonAsset = 2;
        for (int cluster = 0; cluster < clusters; cluster++) {
            int members = cluster < pairedClusters ? 2 : 1;
            long eventDecision = origin + (60L + seedOffsetDays + cluster * 42L) * 86_400_000L;
            for (int member = 0; member < members; member++) {
                String asset;
                if (members == 2) asset = member == 0 ? "btc" : "eth";
                else { asset = ASSETS[singletonAsset % ASSETS.length]; singletonAsset++; }
                String symbol = asset.toUpperCase() + "USDT";
                String prefix = "d-v004-r" + replication + "-c" + cluster + "-" + member;
                result.add(new Episode(cluster, eventIndex++, asset, symbol, prefix + "-e", prefix + "-c",
                        eventDecision, eventDecision - 21L * 86_400_000L));
            }
        }
        if (result.size() != requested) throw new IllegalStateException("v004 cluster geometry generated " + result.size());
        return result;
    }

    private static CommonPath commonPath(SplittableRandom random, int length) {
        double[] result = new double[length];
        double[] scales = new double[length];
        boolean stressed = false;
        for (int index = 0; index < length; index++) {
            if (!stressed && random.nextDouble() < .003D) stressed = true;
            else if (stressed && random.nextDouble() < .08D) stressed = false;
            double regimeScale = stressed ? .0012D : .00055D;
            scales[index] = regimeScale;
            result[index] = regimeScale * normal(random);
        }
        return new CommonPath(result, scales);
    }

    private static void addOutcomeRoles(Path root, Map<String, StrategyFixedBaselineV5.Role> roles,
            List<ObjectNode> labels, List<ObjectNode> executions, String asset, String symbol,
            String id, long decision, double startingPrice, boolean treated, double effect,
            CommonPath sharedCommon, SplittableRandom random, long deadlineNanos, long maxRssBytes) {
        ArrayNode bars = JsonHashes.mapper().createArrayNode();
        double price = startingPrice;
        double priorAssetInnovation = 0;
        double sigmaMultiplier = ASSET_SIGMA_MULTIPLIER.getOrDefault(asset.toUpperCase(), 1D);
        for (int index = 0; index < HORIZON_MINUTES; index++) {
            if ((index & 255) == 0) checkResource(deadlineNanos, maxRssBytes);
            long time = decision + index * MINUTE_MS;
            double regimeScale;
            double common;
            if (sharedCommon == null) {
                regimeScale = random.nextDouble() < .003D ? .0012D : .00055D;
                common = normal(random) * regimeScale;
            } else {
                regimeScale = sharedCommon.scales()[index];
                common = sharedCommon.values()[index];
            }
            double innovation = .20D * priorAssetInnovation + Math.sqrt(1D - .20D * .20D) * normal(random);
            priorAssetInnovation = innovation;
            double assetNoise = sigmaMultiplier * regimeScale * innovation;
            double drift = treated ? effect / HORIZON_MINUTES : 0;
            double open = price;
            price = open * Math.exp(.35D * common + .65D * assetNoise + drift);
            double intrabar = Math.abs(normal(random)) * .00035;
            ObjectNode bar = JsonHashes.mapper().createObjectNode()
                    .put("asset", asset).put("symbol", symbol).put("venue", "BINANCE")
                    .put("instrument", "BINANCE_SPOT").put("event_time", Instant.ofEpochMilli(time).toString())
                    .put("open", open).put("close", price)
                    .put("high", Math.max(open, price) * Math.exp(intrabar))
                    .put("low", Math.min(open, price) * Math.exp(-intrabar))
                    .put("volume", BASE_VOLUME.getOrDefault(asset.toUpperCase(), 1_000_000D)
                            / 240D * Math.exp(.35D * normal(random)));
            bars.add(bar);
        }
        String roleName = "bars:" + id;
        writeRole(root, roles, roleName, bars);
        String featureId = asset + ":" + Instant.ofEpochMilli(decision).toString();
        labels.add(JsonHashes.mapper().createObjectNode().put("episode_id", featureId).put("asset", asset)
                .put("symbol", symbol).put("resolution_ceiling_time", Instant.ofEpochMilli(decision + HORIZON_MS).toString())
                .put("resolution_time", Instant.ofEpochMilli(decision + HORIZON_MS).toString())
                .put("availability_time", Instant.ofEpochMilli(decision + HORIZON_MS).toString()));
        executions.add(JsonHashes.mapper().createObjectNode().put("episode_id", featureId).put("asset", asset)
                .put("symbol", symbol).put("bars_role", roleName)
                .put("contract_spec_role", "contract_spec:" + asset)
                .put("execution_model_role", "execution_model:" + asset)
                .put("capacity_role", "capacity:" + asset));
    }

    // Stable package-private test seam: the generator review can exercise the
    // planted-effect units without inheriting a run's wall/RSS budget object.
    private static void addOutcomeRoles(Path root, Map<String, StrategyFixedBaselineV5.Role> roles,
            List<ObjectNode> labels, List<ObjectNode> executions, String asset, String symbol,
            String id, long decision, double startingPrice, boolean treated, double effect,
            CommonPath sharedCommon, SplittableRandom random) {
        addOutcomeRoles(root, roles, labels, executions, asset, symbol, id, decision, startingPrice,
                treated, effect, sharedCommon, random, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    private static void writeAssetRoles(Path root, Map<String, StrategyFixedBaselineV5.Role> roles) {
        for (String lower : ASSETS) {
            String asset = lower.toUpperCase();
            String symbol = asset + "USDT";
            ObjectNode contract = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-contract/1")
                    .put("asset", lower).put("symbol", symbol)
                    .put("step_size", 0.000001).put("min_qty", 0.000001).put("min_notional", 10)
                    .put("max_notional", 1_000_000_000D).put("contract_multiplier", 1);
            ObjectNode model = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-execution-model/1")
                    .put("asset", lower).put("symbol", symbol)
                    .put("taker_fee_rate", .001).put("slippage_bps", 5);
            ObjectNode capacity = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-capacity/1")
                    .put("asset", lower).put("symbol", symbol)
                    .put("order_notional_usd", 1000).put("available_liquidity_usd", 100_000_000D)
                    .put("participation_cap", .1);
            writeRole(root, roles, "contract_spec:" + lower, contract);
            writeRole(root, roles, "execution_model:" + lower, model);
            writeRole(root, roles, "capacity:" + lower, capacity);
        }
        // These common roles make the synthetic bundle explicit about shared
        // fee assumptions while each lifecycle still resolves an asset-bound
        // filter/model/capacity receipt above.
        ObjectNode commonContract = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-contract/1")
                .put("step_size", .000001).put("min_qty", .000001).put("min_notional", 10)
                .put("max_notional", 1_000_000_000D).put("contract_multiplier", 1);
        ObjectNode commonModel = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-execution-model/1")
                .put("taker_fee_rate", .001)
                .put("slippage_bps", 5);
        ObjectNode commonCapacity = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-baseline-capacity/1")
                .put("order_notional_usd", 1000)
                .put("available_liquidity_usd", 100_000_000D).put("participation_cap", .1);
        writeRole(root, roles, "contract_spec", commonContract);
        writeRole(root, roles, "execution_model", commonModel);
        writeRole(root, roles, "capacity", commonCapacity);
    }

    private static void writeRole(Path root, Map<String, StrategyFixedBaselineV5.Role> roles,
            String name, JsonNode value) {
        try {
            String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
            Path path = root.resolve(safe + ".json");
            byte[] bytes = (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            String rows = value.isArray() ? JsonHashes.canonicalSha256(value) : null;
            LifecycleTrustService.ReceiptReference reference = new LifecycleTrustService.ReceiptReference(
                    path.getFileName().toString(), JsonHashes.ownHash(value), JsonHashes.sha256(bytes),
                    (long) bytes.length, rows, value.isObject() ? value.path("schema").asText(null) : null);
            roles.put(name, new StrategyFixedBaselineV5.Role(value, reference));
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot write synthetic role " + name, error);
        }
    }

    private static ArrayNode arrayNode(List<ObjectNode> rows) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        rows.forEach(result::add);
        return result;
    }

    private static ObjectNode summarize(ArrayNode rows, List<Cell> cells) {
        ObjectNode summary = JsonHashes.mapper().createObjectNode();
        for (Cell cell : cells) {
            int total = 0, decisions = 0, incomplete = 0, insufficient = 0, invalid = 0;
            for (JsonNode row : rows) if (cell.name().equals(row.path("cell").asText())
                    && Double.compare(cell.effect(), row.path("effect_size").asDouble()) == 0) {
                total++; if (row.path("decision").asBoolean(false)
                        && "COMPLETE".equals(row.path("status").asText())) decisions++;
                if ("COMPUTE_INCOMPLETE".equals(row.path("status").asText())) incomplete++;
                String primary = row.path("disposition").path("primary_reason").asText("");
                if ("INSUFFICIENT_EVIDENCE".equals(primary)) insufficient++;
                if ("INVALID_EVIDENCE".equals(primary)) invalid++;
            }
            double[] interval = wilson(decisions, total);
            boolean noEdge = "NO_EDGE".equals(cell.name()) && Double.compare(cell.effect(), 0D) == 0;
            boolean measured = total == 50 && incomplete == 0 && insufficient == 0 && invalid == 0;
            boolean target = noEdge ? interval[1] <= .10D
                    : (!noEdge && cell.effect() > 0 && interval[0] >= .80D);
            ObjectNode cellSummary = JsonHashes.mapper().createObjectNode().put("planned", total)
                    .put("decisions", decisions).put("incomplete", incomplete)
                    .put("insufficient", insufficient).put("invalid", invalid)
                    .put("denominator_includes_all_planned_replications", true)
                    .put("measured", measured).put("target_met", measured && target)
                    .put("target_rule", noEdge ? "upper_wilson_95<=0.10" : cell.effect() > 0
                            ? "lower_wilson_95>=0.80" : "NULL_CHECK_ONLY")
                    .put("uncertainty_scope", total == 50 ? "WILSON_95_REPLICATION_DECISIONS" : "PREFIX_DESCRIPTIVE_ONLY");
            if (measured) {
                cellSummary.put("success_rate", total == 0 ? 0D : (double) decisions / total);
                cellSummary.put("wilson95_lower", interval[0]).put("wilson95_upper", interval[1]);
            } else {
                cellSummary.putNull("success_rate").putNull("wilson95_lower").putNull("wilson95_upper");
            }
            summary.set(cell.name() + ":" + Double.toString(cell.effect()), cellSummary);
        }
        return summary;
    }

    private static double[] wilson(int successes, int total) {
        if (total <= 0) return new double[] {Double.NaN, Double.NaN};
        double z = 1.959963984540054D;
        double n = total;
        double p = successes / n;
        double denominator = 1D + z * z / n;
        double centre = p + z * z / (2D * n);
        double spread = z * Math.sqrt(p * (1D - p) / n + z * z / (4D * n * n));
        return new double[] {(centre - spread) / denominator, (centre + spread) / denominator};
    }

    private static ObjectNode incompleteRow(Cell cell, int replication, String reason) {
        ObjectNode disposition = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evidence-disposition/1")
                .put("primary_reason", "COMPUTE_INCOMPLETE").put("promotion_eligible", false);
        return JsonHashes.mapper().createObjectNode().put("cell", cell.name())
                .put("effect_size", cell.effect()).put("replication", replication)
                .put("status", "COMPUTE_INCOMPLETE").put("decision", false).put("error", reason)
                .set("disposition", disposition);
    }

    private static ArrayNode firstRows(JsonNode value, int limit) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        if (value != null && value.isArray()) {
            for (int index = 0; index < Math.min(limit, value.size()); index++) result.add(value.get(index).deepCopy());
        }
        return result;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long rssBytes() {
        try {
            String status = Files.readString(Path.of("/proc/self/status"));
            for (String line : status.split("\\R")) if (line.startsWith("VmRSS:")) {
                String number = line.replaceAll("[^0-9]", "");
                if (!number.isBlank()) return Long.parseLong(number) * 1024L;
            }
        } catch (Exception ignored) { }
        try {
            Process process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            if (!output.isBlank()) return Long.parseLong(output.replaceAll("[^0-9]", "")) * 1024L;
        } catch (Exception ignored) { }
        return 0L;
    }

    private static void checkResource(long deadlineNanos, long maxRssBytes) {
        if (System.nanoTime() > deadlineNanos) throw new ResourceLimit("RESOURCE_WALL_DEADLINE_EXCEEDED");
        long rss = rssBytes();
        if (rss > 0 && rss > maxRssBytes) throw new ResourceLimit("RESOURCE_RSS_BUDGET_EXCEEDED");
    }

    private static long replicateSeed(ObjectNode plan, int cellIndex, double effect, int replication) {
        ArrayNode seeds = (ArrayNode) plan.path("replicate_seed_mapping").path("base_seeds");
        long base = seeds.get(replication % seeds.size()).asLong();
        int effectIndex = Double.compare(effect, 0) == 0 ? 0 : effect < .03 ? 1 : 2;
        return base + (long) cellIndex * 1_000_000L + (long) effectIndex * 10_000L + replication;
    }

    private static ObjectNode readObject(ObjectNode options, String key) {
        String path = options.path(key).asText("");
        if (path.isBlank()) throw new IllegalArgumentException("synthetic D requires --" + key);
        try {
            JsonNode value = JsonHashes.mapper().readTree(Files.readString(Path.of(path)));
            if (!value.isObject()) throw new IllegalArgumentException(key + " is not a JSON object");
            return (ObjectNode) value;
        } catch (IOException error) { throw new IllegalArgumentException("cannot read " + key + ": " + error.getMessage(), error); }
    }

    private static void writeImmutable(Path path, ObjectNode value) {
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            if (Files.exists(path)) {
                ObjectNode existing = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(path));
                if (!existing.path("content_sha256").asText().equals(value.path("content_sha256").asText())) {
                    throw new IllegalArgumentException("synthetic D output already contains different bytes");
                }
                return;
            }
            Files.writeString(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) { throw new IllegalArgumentException("cannot write synthetic D output", error); }
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static double normal(SplittableRandom random) {
        double u1 = Math.max(Double.MIN_VALUE, random.nextDouble());
        double u2 = random.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    private record Cell(String name, double effect) { }
    private record CommonPath(double[] values, double[] scales) { }
    private record Episode(int cluster, int eventIndex, String asset, String symbol,
            String eventId, String controlId, long eventDecision, long controlDecision) { }
    private record SetupSeries(ArrayNode bars, ArrayNode audit, double finalClose) { }
    private static final class ResourceLimit extends RuntimeException {
        ResourceLimit(String message) { super(message); }
    }
}
