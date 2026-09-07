package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * One fixed, outcome-blind baseline attempt.  This is deliberately a small
 * orchestrator around the production lifecycle and custody services, rather
 * than a second simulator or a search implementation.
 *
 * <p>The input is a content-addressed physical role bundle.  Setup rows are
 * read and controls are selected before any child bars or labels are opened.
 * A missing child-bar receipt is therefore an explicit failed evaluation; it
 * cannot silently reduce the denominator or manufacture a zero return.</p>
 */
public final class StrategyFixedBaselineV5 {
    public static final String INPUT_SCHEMA = "strategy-fixed-baseline-input/1";
    public static final String RESULT_SCHEMA = "strategy-fixed-baseline-result/1";
    public static final String REFINEMENT_MEMBER_RESULT_SCHEMA = "strategy-fixed-refinement-member-result/1";
    private static final long FOUR_HOURS_MS = 14_400_000L;
    private static final long LIFECYCLE_MS = 240L * 3_600_000L;
    private static final double FIXED_STARTING_CASH_USDT = 10_000D;
    private static final String PORTFOLIO_CAPITAL_POLICY = "FIXED_INITIAL_CASH_USDT_10000_V001";
    private static final Path VERIFIED_PARQUET_MANIFEST = Path.of("strategy-research", "v5-records",
            "data-raw-replay", "parquet-31f85e351861f05edc66794ec57369c0a7fafe67e236676aab5c6cc7bec4cfc6.json");
    private static final Path VERIFIED_PARQUET_ROOT = Path.of("strategy-research", "v5-data",
            "backfill-20260825-v7-final-local", "parquet");
    private static final Set<String> FUTURE_FIELDS = Set.of(
            "future_return", "label", "exit_price", "exit_time", "net_r", "pnl",
            "trade_outcome", "realized_volatility_after_decision", "resolution_time",
            "resolution_ceiling_time", "outcome", "forward_return", "forward_pnl");

    private StrategyFixedBaselineV5() {}

    /**
     * Materialize the setup producer's role directly from the immutable
     * Parquet conversion manifest.  This is intentionally separate from the
     * result writer: the role is a physical receipt, while the fixed runner
     * still requires labels, execution bars, costs, and filters before it can
     * evaluate an episode.
     *
     * <p>The method verifies the manifest's own hash, every selected spot
     * partition's byte hash/size/row count, and the completed-bar producer
     * invariants before writing the JSON role consumed by {@link #run}.</p>
     */
    public static ObjectNode buildSignalBarsFromVerifiedParquet(ObjectNode options) {
        Path manifestPath = requiredPath(options, "manifest", "parquet_manifest");
        Path parquetRoot = requiredPath(options, "root", "parquet_root");
        Path output = optionalPath(options, "out", "output");
        ObjectNode manifest = readObject(manifestPath, "Parquet conversion manifest");
        if (!"strategy-v5-parquet-conversion/1".equals(manifest.path("schema").asText())
                || !"AUTHORITATIVE_PARQUET".equals(manifest.path("status").asText())
                || !manifest.path("authoritative").asBoolean(false)) {
            throw new ExternalPrerequisite("PARQUET_MANIFEST_UNAVAILABLE",
                    "signal producer requires an authoritative Parquet conversion manifest");
        }
        if (!manifest.path("content_sha256").asText().equals(JsonHashes.ownHash(manifest))) {
            throw new ExternalPrerequisite("PARQUET_MANIFEST_UNAVAILABLE", "Parquet conversion manifest hash is invalid");
        }
        Path root = parquetRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ExternalPrerequisite("PARQUET_ROOT_UNAVAILABLE", "Parquet root is missing: " + root);
        }
        List<ObjectNode> rows = new ArrayList<>();
        Set<String> partitions = new HashSet<>();
        for (JsonNode rawCapture : manifest.path("captures")) {
            if (!(rawCapture instanceof ObjectNode capture)
                    || !"4h".equals(capture.path("interval").asText())
                    || !"signal_bars".equals(capture.path("series_type").asText())
                    || !"PRICE".equals(capture.path("series_role").asText())
                    || !"BINANCE_SPOT".equals(capture.path("instrument").asText())) continue;
            ObjectNode partition = capture.path("partition").isObject()
                    ? (ObjectNode) capture.path("partition") : null;
            if (partition == null || !partition.path("authoritative").asBoolean(false)
                    || !"PARQUET".equals(partition.path("format").asText())) {
                throw new ExternalPrerequisite("PARQUET_PARTITION_UNAVAILABLE",
                        "spot signal capture lacks an authoritative Parquet partition: " + capture.path("asset").asText());
            }
            String relative = partition.path("path").asText("");
            Path file = root.resolve(relative).normalize();
            try {
                Path rootReal = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
                Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!fileReal.startsWith(rootReal) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("partition escapes root or is missing");
                }
                String partitionSha = partition.path("sha256").asText();
                if (!partitionSha.matches("[a-f0-9]{64}") || !partitionSha.equals(JsonHashes.sha256(file))) {
                    throw new IOException("partition byte hash differs from manifest");
                }
                if (partition.path("bytes").asLong(-1) != Files.size(file)) {
                    throw new IOException("partition byte count differs from manifest");
                }
                if (!partitions.add(relative)) continue;
                List<ObjectNode> reopened = ResearchData.queryParquet(file);
                if (partition.path("row_count").asInt(-1) != reopened.size()) {
                    throw new IOException("partition row count differs from manifest");
                }
                for (ObjectNode raw : reopened) rows.add(normalizeSignalBar(raw, capture));
            } catch (IOException error) {
                throw new ExternalPrerequisite("PARQUET_PARTITION_UNAVAILABLE",
                        capture.path("asset").asText("unknown") + ": " + error.getMessage());
            }
        }
        if (rows.isEmpty()) throw new ExternalPrerequisite("PARQUET_PARTITION_UNAVAILABLE",
                "manifest has no authoritative Binance spot 4h signal-bar captures");
        rows.sort(Comparator.comparing((ObjectNode row) -> row.path("asset").asText())
                .thenComparing(row -> row.path("event_time").asText()));
        // Run the same producer validation used by the evaluator, including
        // complete 4h cadence and every 31-bar dependency's PIT boundary.
        deriveFeatures(rows, List.of());
        ArrayNode role = JsonHashes.mapper().createArrayNode(); rows.forEach(role::add);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-signal-bars/1").put("version", 1)
                .put("status", "VERIFIED_PHYSICAL").put("source_manifest_sha256", manifest.path("content_sha256").asText())
                .put("row_count", role.size()).put("role_content_sha256", JsonHashes.ownHash(role));
        if (output != null) {
            writeImmutableArray(output, role);
            try {
                result.put("path", output.toString()).put("byte_sha256", JsonHashes.sha256(Files.readAllBytes(output)))
                        .put("bytes", Files.size(output));
            } catch (IOException error) {
                throw new ExternalPrerequisite("SIGNAL_ROLE_UNAVAILABLE", error.getMessage());
            }
        }
        return withHash(result);
    }

    private static ObjectNode normalizeSignalBar(ObjectNode raw, ObjectNode capture) {
        ObjectNode row = raw.deepCopy();
        row.put("asset", capture.path("asset").asText().toLowerCase());
        row.put("venue", capture.path("venue").asText());
        row.put("instrument", capture.path("instrument").asText());
        row.put("symbol", capture.path("symbol").asText());
        row.put("timeframe", capture.path("interval").asText());
        for (String field : List.of("event_time", "close_time", "availability_time")) {
            if (row.path(field).isMissingNode() || row.path(field).asText("").isBlank()) {
                throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "signal bar lacks " + field);
            }
        }
        double open = row.path("open").asDouble(Double.NaN), high = row.path("high").asDouble(Double.NaN);
        double low = row.path("low").asDouble(Double.NaN), close = row.path("close").asDouble(Double.NaN);
        double volume = row.path("volume").asDouble(Double.NaN);
        if (!(open > 0) || !(high > 0) || !(low > 0) || !(close > 0) || !Double.isFinite(volume) || volume < 0
                || high < Math.max(open, close) || low > Math.min(open, close)) {
            throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "signal bar OHLCV is invalid");
        }
        return row;
    }

    /** Executes one fixed attempt without invoking a genetic optimizer. */
    public static ObjectNode run(ObjectNode options) {
        if (options != null && (options.has("refinement_member_id")
                || options.has("refinement_shock_threshold") || options.has("refinement_plan_sha256"))) {
            throw new IllegalArgumentException("fixed-baseline does not accept refinement overrides; use fixed-baseline-refinement");
        }
        return runInternal(options, "", Double.NaN, "");
    }

    private static ObjectNode runInternal(ObjectNode options, String refinementMember,
            double refinementThreshold, String refinementPlanSha256) {
        ObjectNode args = options == null ? JsonHashes.mapper().createObjectNode() : options;
        Path baselinePath = requiredPath(args, "baseline", "baseline_spec");
        Path controlsPath = requiredPath(args, "controls", "control_spec");
        Path experimentPath = requiredPath(args, "experiment");
        Path physicalPath = requiredPath(args, "physical_input", "input");
        Path exposurePath = requiredPath(args, "exposure_head");
        Path outputPath = optionalPath(args, "out", "output");
        if (outputPath != null && Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode previous = readObject(outputPath, "existing fixed baseline result");
            validateExistingResult(previous, baselinePath, controlsPath, experimentPath, physicalPath, exposurePath,
                    refinementMember, refinementThreshold, refinementPlanSha256);
            return previous;
        }

        ObjectNode result;
        try {
            ObjectNode baseline = readContract(baselinePath, "baseline", "strategy-baseline-spec/1");
            ObjectNode controls = readContract(controlsPath, "controls", "strategy-control-spec/1");
            ObjectNode experiment = readContract(experimentPath, "experiment", INPUT_EXPERIMENT_SCHEMA);
            PhysicalInput physical = readPhysicalInput(physicalPath);
            ObjectNode portfolioPolicy = readPortfolioPolicy();
            ObjectNode exposure = readOrInitializeExposure(exposurePath,
                    baseline.path("hypothesis_family").asText(), physical.contentSha256);
            ObjectNode exposureLineage = auditExposureLineage(exposure,
                    baseline.path("hypothesis_family").asText());
            validateFrozenContracts(baseline, controls, experiment, baselinePath, controlsPath);
            validatePhysicalCosts(physical, baseline);
            validateBuildIdentity();
            String behaviorAlias = behaviorDefinitionAlias(baseline.path("content_sha256").asText(), refinementMember,
                    refinementThreshold);
            ObjectNode nextExposure = appendAttempt(exposurePath, exposure, physical.contentSha256,
                    baseline.path("hypothesis_family").asText(), behaviorAlias,
                    refinementMember);
            result = evaluate(args, baseline, controls, experiment, portfolioPolicy, physical, nextExposure,
                    exposurePath, exposureLineage, refinementMember, refinementThreshold);
        } catch (ExternalPrerequisite error) {
            result = blockedResult(error.reason, error.detail, args, baselinePath, controlsPath,
                    experimentPath, physicalPath, exposurePath);
        }
        if (outputPath != null) writeImmutable(outputPath, result);
        return result;
    }

    /**
     * Executes the exact three-member frozen refinement inventory through the
     * same physical input, control selector, lifecycle, portfolio and
     * disposition path as the fixed baseline.  Members get distinct immutable
     * output/attempt identities, while the shared physical roles are opened by
     * each member run.  A blocked member is retained as a measured decision;
     * it is never silently removed from the budget or converted into a pass.
     */
    public static ObjectNode runRefinement(ObjectNode options) {
        ObjectNode args = options == null ? JsonHashes.mapper().createObjectNode() : options;
        Path refinementPath = requiredPath(args, "refinement", "refinement_input");
        ObjectNode refinement = readContract(refinementPath, "refinement", "strategy-fixed-refinement/1");
        if (!"FROZEN_REFINEMENT".equals(refinement.path("stage").asText())
                || refinement.path("outcomes_opened").asBoolean(true)
                || refinement.path("promotion_eligible").asBoolean(true)
                || refinement.path("candidate_count").asInt(-1) != 3
                || refinement.path("max_attempts").asInt(-1) != 3
                || !"NONE".equalsIgnoreCase(refinement.path("optimizer").asText())) {
            throw new IllegalArgumentException("refinement inventory is not the exact frozen three-member diagnostic");
        }
        JsonNode membersNode = refinement.path("members");
        if (!membersNode.isArray() || membersNode.size() != 3) {
            throw new IllegalArgumentException("refinement inventory must contain exactly three members");
        }
        validateRefinementMembers(membersNode);
        Path baselinePath = requiredPath(args, "baseline", "baseline_spec");
        Path controlsPath = requiredPath(args, "controls", "control_spec");
        Path experimentPath = requiredPath(args, "experiment");
        Path physicalPath = requiredPath(args, "physical_input", "input");
        Path exposurePath = requiredPath(args, "exposure_head");
        Path predecessorPath = requiredPath(args, "predecessor", "predecessor_result");
        Path startingExposurePath = requiredPath(args, "starting_exposure_head", "starting_head");
        ObjectNode baseline = readContract(baselinePath, "baseline", "strategy-baseline-spec/1");
        ObjectNode controls = readContract(controlsPath, "controls", "strategy-control-spec/1");
        ObjectNode experiment = readContract(experimentPath, "experiment", INPUT_EXPERIMENT_SCHEMA);
        ObjectNode physical = readObject(physicalPath, "fixed baseline physical input");
        ObjectNode exposure = readObject(exposurePath, "canonical exposure head");
        ObjectNode startingExposure = readObject(startingExposurePath, "refinement starting exposure head");
        ObjectNode predecessor = readObject(predecessorPath, "refinement predecessor result");
        if (!physical.path("content_sha256").asText().equals(JsonHashes.ownHash(physical))
                || !exposure.path("content_sha256").asText().equals(JsonHashes.ownHash(exposure))
                || !predecessor.path("content_sha256").asText().equals(JsonHashes.ownHash(predecessor))) {
            throw new IllegalArgumentException("refinement lineage input has an invalid content hash");
        }
        if (!RESULT_SCHEMA.equals(predecessor.path("schema").asText())) {
            throw new IllegalArgumentException("refinement predecessor must use " + RESULT_SCHEMA);
        }
        requireHashBinding(refinement, "baseline_sha256", baseline.path("content_sha256").asText());
        requireHashBinding(refinement, "control_spec_sha256", controls.path("content_sha256").asText());
        requireHashBinding(refinement, "experiment_sha256", experiment.path("content_sha256").asText());
        requireHashBinding(refinement, "exposure_head_sha256", startingExposure.path("content_sha256").asText());
        requireHashBinding(refinement, "predecessor_sha256", predecessor.path("content_sha256").asText());
        if (!baseline.path("hypothesis_family").asText().equals(refinement.path("hypothesis_family").asText())
                || !baseline.path("hypothesis_family").asText().equals(predecessor.path("hypothesis_family").asText())) {
            throw new IllegalArgumentException("refinement family lineage does not match the frozen predecessor");
        }
        Path canonicalExposure = StrategyResearchAuthoritativeV5.canonicalExposureHeadPath(
                baseline.path("hypothesis_family").asText()).toAbsolutePath().normalize();
        if (!canonicalExposure.equals(exposurePath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("refinement must use the canonical family exposure head");
        }
        if (!physical.path("content_sha256").asText().equals(predecessor.path("physical_input_sha256").asText())
                || !baseline.path("content_sha256").asText().equals(predecessor.path("baseline_sha256").asText())
                || !controls.path("content_sha256").asText().equals(predecessor.path("control_spec_sha256").asText())
                || !experiment.path("content_sha256").asText().equals(predecessor.path("experiment_sha256").asText())
                || !"FIXED_BASELINE".equals(predecessor.path("stage").asText())) {
            throw new IllegalArgumentException("refinement predecessor is not the supplied fixed baseline");
        }
        validateRefinementExposureLineage(startingExposure, exposure, refinement, physical,
                baseline.path("hypothesis_family").asText());
        Path outputDir = optionalPath(args, "out_dir", "output_dir");
        Path aggregatePath = optionalPath(args, "out", "output");
        if (outputDir == null) {
            if (aggregatePath == null) throw new IllegalArgumentException("fixed-baseline-refinement requires --out-dir or --out");
            outputDir = aggregatePath.toAbsolutePath().normalize().getParent();
        }
        if (outputDir == null) throw new IllegalArgumentException("refinement output directory is unavailable");
        try { Files.createDirectories(outputDir); } catch (IOException error) {
            throw new IllegalArgumentException("cannot create refinement output directory: " + error.getMessage(), error);
        }

        ArrayNode memberResults = JsonHashes.mapper().createArrayNode();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode rawMember : membersNode) {
            if (!(rawMember instanceof ObjectNode member)) throw new IllegalArgumentException("refinement member is not an object");
            String memberId = member.path("member_id").asText("");
            double threshold = member.path("shock_threshold").asDouble(Double.NaN);
            double expectedThreshold = switch (memberId) {
                case "FK-DELEVERAGING-V002-R1" -> -0.08D;
                case "FK-DELEVERAGING-V002-R2" -> -0.10D;
                case "FK-DELEVERAGING-V002-R3" -> -0.12D;
                default -> Double.NaN;
            };
            if (!ids.add(memberId) || !Double.isFinite(expectedThreshold)
                    || !Double.isFinite(threshold) || !Set.of(-0.08D, -0.10D, -0.12D).contains(threshold)
                    || Double.compare(expectedThreshold, threshold) != 0
                    || member.path("volume_multiple_threshold").asDouble(Double.NaN) != 2.0D
                    || member.path("volatility_threshold").asDouble(Double.NaN) != 0.015D) {
                throw new IllegalArgumentException("refinement member changes an unsupported frozen dimension: " + memberId);
            }
            ObjectNode memberArgs = args.deepCopy();
            memberArgs.remove("refinement"); memberArgs.remove("refinement_input");
            memberArgs.put("refinement_member_id", memberId).put("refinement_shock_threshold", threshold);
            memberArgs.put("refinement_plan_sha256", refinement.path("content_sha256").asText());
            memberArgs.put("out", outputDir.resolve(memberId + ".json").toString());
            ObjectNode result = runInternal(memberArgs, memberId, threshold,
                    refinement.path("content_sha256").asText());
            ObjectNode summary = JsonHashes.mapper().createObjectNode()
                    .put("member_id", memberId).put("shock_threshold", threshold)
                    .put("result_path", outputDir.resolve(memberId + ".json").toString())
                    .put("result_sha256", result.path("content_sha256").asText())
                    .put("status", result.path("status").asText())
                    .put("evaluation_scope", result.path("evaluation_scope").asText())
                    .put("economic_semantic_sha256", result.path("economic_semantic_sha256").asText());
            summary.set("disposition", result.path("disposition").deepCopy());
            memberResults.add(summary);
        }
        ObjectNode aggregate = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-refinement-result/1").put("version", 1)
                .put("stage", "FROZEN_REFINEMENT").put("evidence_phase", "DIAGNOSTIC")
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("outcomes_opened", true).put("optimizer_invoked", false)
                .put("candidate_count", memberResults.size()).put("max_attempts", 3)
                .put("refinement_plan_sha256", refinement.path("content_sha256").asText())
                .put("baseline_sha256", refinement.path("baseline_sha256").asText())
                .put("control_spec_sha256", refinement.path("control_spec_sha256").asText())
                .put("experiment_sha256", refinement.path("experiment_sha256").asText())
                .put("predecessor_sha256", refinement.path("predecessor_sha256").asText())
                .put("exposure_head_sha256", refinement.path("exposure_head_sha256").asText())
                .put("shared_physical_evaluator", true)
                .put("decision_scope", "FIXED_BASELINE_SHARED_PHYSICAL_LIFECYCLE")
                .put("promotion_blocked_reason", "DIAGNOSTIC_REFINEMENT_CANNOT_ADVANCE_FAMILY");
        aggregate.set("members", memberResults);
        aggregate.put("status", memberResults.size() == 3 ? "COMPLETE" : "COMPUTE_INCOMPLETE");
        aggregate = withHash(aggregate);
        if (aggregatePath != null) writeImmutable(aggregatePath, aggregate);
        return aggregate;
    }

    private static void requireHashBinding(ObjectNode object, String field, String actual) {
        if (!actual.equals(object.path(field).asText())) {
            throw new IllegalArgumentException("refinement " + field + " does not bind the supplied input");
        }
    }

    private static void validateRefinementExposureLineage(ObjectNode starting, ObjectNode current,
            ObjectNode refinement, ObjectNode physical, String family) {
        if (!"strategy-v5-statistical-exposure-head/1".equals(starting.path("schema").asText())
                || !"strategy-v5-statistical-exposure-head/1".equals(current.path("schema").asText())
                || !refinement.path("exposure_head_sha256").asText().equals(starting.path("content_sha256").asText())
                || !starting.path("content_sha256").asText().equals(JsonHashes.ownHash(starting))
                || !current.path("content_sha256").asText().equals(JsonHashes.ownHash(current))) {
            throw new IllegalArgumentException("refinement exposure head hash or schema is invalid");
        }
        StrategyStatisticalV5.validateExposureHead(starting);
        StrategyStatisticalV5.validateExposureHead(current);
        if (!family.equals(starting.path("hypothesis_family").asText())
                || !family.equals(current.path("hypothesis_family").asText())
                || !physical.path("content_sha256").asText().equals(starting.path("dataset_sha256").asText())
                || current.path("cumulative_k").asLong(-1) < starting.path("cumulative_k").asLong(-1)
                || current.path("exposure_attempt_k").asLong(-1) < starting.path("exposure_attempt_k").asLong(-1)) {
            throw new IllegalArgumentException("refinement exposure head is not a non-decreasing family descendant");
        }
        JsonNode startEntries = starting.path("entries");
        JsonNode currentEntries = current.path("entries");
        if (!startEntries.isArray() || !currentEntries.isArray() || currentEntries.size() < startEntries.size()) {
            throw new IllegalArgumentException("refinement exposure head lost starting entries");
        }
        for (int index = 0; index < startEntries.size(); index++) {
            if (!JsonHashes.canonicalSha256(startEntries.get(index))
                    .equals(JsonHashes.canonicalSha256(currentEntries.get(index)))) {
                throw new IllegalArgumentException("refinement exposure head rewrote a prior entry");
            }
        }
        JsonNode startingPairs = starting.path("fixed_attempt_pairs");
        JsonNode currentPairs = current.path("fixed_attempt_pairs");
        if (startingPairs.size() > currentPairs.size()) {
            throw new IllegalArgumentException("refinement exposure head lost fixed attempt pairs");
        }
        for (int index = 0; index < startingPairs.size(); index++) {
            if (!startingPairs.get(index).equals(currentPairs.get(index))) {
                throw new IllegalArgumentException("refinement exposure head rewrote a fixed attempt pair");
            }
        }
        if (starting.has("fixed_attempt_ledger_migration_sha256")
                && !starting.get("fixed_attempt_ledger_migration_sha256")
                        .equals(current.get("fixed_attempt_ledger_migration_sha256"))) {
            throw new IllegalArgumentException("refinement exposure head lost its fixed attempt migration receipt");
        }
    }

    private static void validateRefinementMembers(JsonNode membersNode) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode rawMember : membersNode) {
            if (!(rawMember instanceof ObjectNode member)) throw new IllegalArgumentException("refinement member is not an object");
            String memberId = member.path("member_id").asText("");
            double expectedThreshold = switch (memberId) {
                case "FK-DELEVERAGING-V002-R1" -> -0.08D;
                case "FK-DELEVERAGING-V002-R2" -> -0.10D;
                case "FK-DELEVERAGING-V002-R3" -> -0.12D;
                default -> Double.NaN;
            };
            double threshold = member.path("shock_threshold").asDouble(Double.NaN);
            if (!ids.add(memberId) || !Double.isFinite(expectedThreshold)
                    || Double.compare(expectedThreshold, threshold) != 0
                    || member.path("volume_multiple_threshold").asDouble(Double.NaN) != 2.0D
                    || member.path("volatility_threshold").asDouble(Double.NaN) != 0.015D) {
                throw new IllegalArgumentException("refinement member changes an unsupported frozen dimension: " + memberId);
            }
        }
        if (!ids.equals(Set.of("FK-DELEVERAGING-V002-R1", "FK-DELEVERAGING-V002-R2", "FK-DELEVERAGING-V002-R3"))) {
            throw new IllegalArgumentException("refinement inventory must contain R1, R2, and R3 exactly once");
        }
    }

    private static final String INPUT_EXPERIMENT_SCHEMA = "strategy-fixed-baseline-experiment/1";

    private static void validateExistingResult(ObjectNode previous, Path baselinePath, Path controlsPath,
            Path experimentPath, Path physicalPath, Path exposurePath, String refinementMember,
            double refinementThreshold, String refinementPlanSha256) {
        String expectedSchema = refinementMember.isBlank() ? RESULT_SCHEMA : REFINEMENT_MEMBER_RESULT_SCHEMA;
        if (!expectedSchema.equals(previous.path("schema").asText())
                || !previous.path("content_sha256").asText().equals(JsonHashes.ownHash(previous))) {
            throw new IllegalArgumentException("immutable result path contains an invalid or different contract");
        }
        ObjectNode baseline = readContract(baselinePath, "baseline", "strategy-baseline-spec/1");
        ObjectNode controls = readContract(controlsPath, "controls", "strategy-control-spec/1");
        ObjectNode experiment = readContract(experimentPath, "experiment", INPUT_EXPERIMENT_SCHEMA);
        ObjectNode physical = readObject(physicalPath, "fixed baseline physical input");
        if (!physical.path("content_sha256").asText().equals(JsonHashes.ownHash(physical))) {
            throw new IllegalArgumentException("physical input content hash is invalid");
        }
        if (!baseline.path("content_sha256").asText().equals(previous.path("baseline_sha256").asText())
                || !controls.path("content_sha256").asText().equals(previous.path("control_spec_sha256").asText())
                || !experiment.path("content_sha256").asText().equals(previous.path("experiment_sha256").asText())
                || !physical.path("content_sha256").asText().equals(previous.path("physical_input_sha256").asText())
                || !baseline.path("hypothesis_family").asText().equals(previous.path("hypothesis_family").asText())) {
            throw new IllegalArgumentException("immutable result path is bound to different frozen/source inputs");
        }
        if (!refinementMember.equals(previous.path("refinement_member_id").asText(""))
                || (refinementMember.isBlank() && previous.has("refinement_shock_threshold"))
                || (!refinementMember.isBlank()
                    && Double.compare(refinementThreshold, previous.path("refinement_shock_threshold").asDouble(Double.NaN)) != 0)) {
            throw new IllegalArgumentException("immutable result path is bound to a different refinement member");
        }
        if (!refinementMember.isBlank() && !refinementPlanSha256.isBlank()
                && !refinementPlanSha256.equals(previous.path("refinement_plan_sha256").asText())) {
            throw new IllegalArgumentException("immutable result is bound to a different refinement plan");
        }
        if (!behaviorDefinitionAlias(previous.path("baseline_sha256").asText(), refinementMember, refinementThreshold)
                .equals(previous.path("behavior_definition_sha256").asText())) {
            throw new IllegalArgumentException("immutable result behavior alias is invalid");
        }
        String expectedAttempt = attemptIdentity(previous.path("baseline_sha256").asText(),
                previous.path("control_spec_sha256").asText(), previous.path("experiment_sha256").asText(),
                previous.path("physical_input_sha256").asText(),
                previous.path("executor_identity_sha256").asText(), previous.path("stage").asText("FIXED_BASELINE"),
                refinementMember);
        if (!expectedAttempt.equals(previous.path("attempt_identity_sha256").asText())) {
            throw new IllegalArgumentException("immutable result attempt identity is invalid");
        }
        String currentExecutor = currentExecutorIdentitySha256();
        if (!currentExecutor.equals(previous.path("executor_identity_sha256").asText())) {
            throw new IllegalArgumentException("immutable result was produced by a different executable identity");
        }
        if (previous.has("portfolio_policy_sha256")
                && !readPortfolioPolicy().path("content_sha256").asText()
                        .equals(previous.path("portfolio_policy_sha256").asText())) {
            throw new IllegalArgumentException("immutable result is bound to a different portfolio policy");
        }
        Path canonical = StrategyResearchAuthoritativeV5.canonicalExposureHeadPath(
                baseline.path("hypothesis_family").asText()).toAbsolutePath().normalize();
        if (!canonical.equals(exposurePath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("immutable result path is bound to a non-canonical exposure head");
        }
        if (!previous.path("economic_semantic_sha256").asText().equals(economicSemanticHash(previous))
                || !previous.path("semantic_sha256").asText().equals(semanticHash(previous))) {
            throw new IllegalArgumentException("immutable result semantic hash is invalid");
        }
    }

    static ObjectNode evaluate(ObjectNode args, ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment, ObjectNode portfolioPolicy, PhysicalInput physical, ObjectNode exposure,
            Path exposurePath, ObjectNode exposureLineage, String refinementMember,
            double refinementThreshold) {
        int maxAttempts = experiment.path("declared_budget").path("max_attempts").asInt(-1);
        if (maxAttempts != 1 || experiment.path("declared_budget").path("candidate_count").asInt(-1) != 1
                || !"NONE".equalsIgnoreCase(experiment.path("declared_budget").path("optimizer").asText())) {
            throw new IllegalArgumentException("fixed baseline budget must be exactly one non-optimizer attempt");
        }
        ObjectNode lifecycleTimingPolicy = readLifecycleTimingPolicy();
        if (!lifecycleTimingPolicy.path("raw_definition_sha256").asText()
                .equals(baseline.path("content_sha256").asText())) {
            throw new IllegalArgumentException("fixed lifecycle timing amendment is bound to a different baseline definition");
        }
        ObjectNode body = JsonHashes.mapper().createObjectNode();
        body.put("schema", refinementMember.isBlank() ? RESULT_SCHEMA : REFINEMENT_MEMBER_RESULT_SCHEMA)
                .put("version", 1).put("status", "COMPLETE");
        String stage = refinementMember.isBlank() ? "FIXED_BASELINE" : "FROZEN_REFINEMENT";
        body.put("stage", stage).put("evidence_phase", "DEVELOPMENT");
        body.put("promotion_eligible", false).put("activation_authorized", false);
        body.put("optimizer_invoked", false).put("max_attempts", maxAttempts);
        body.put("hypothesis_family", baseline.path("hypothesis_family").asText());
        body.put("baseline_sha256", baseline.path("content_sha256").asText());
        body.put("control_spec_sha256", controls.path("content_sha256").asText());
        body.put("experiment_sha256", experiment.path("content_sha256").asText());
        body.put("physical_input_sha256", physical.contentSha256);
        body.put("behavior_definition_sha256",
                behaviorDefinitionAlias(baseline.path("content_sha256").asText(), refinementMember,
                        refinementThreshold));
        String executorIdentity = currentExecutorIdentitySha256();
        body.put("executor_identity_sha256", executorIdentity);
        body.put("attempt_identity_sha256", attemptIdentity(baseline.path("content_sha256").asText(),
                controls.path("content_sha256").asText(), experiment.path("content_sha256").asText(),
                physical.contentSha256, executorIdentity, stage, refinementMember));
        if (!refinementMember.isBlank()) {
            body.put("refinement_member_id", refinementMember)
                    .put("refinement_shock_threshold", refinementThreshold)
                    .put("refinement_plan_sha256", args.path("refinement_plan_sha256").asText());
        }
        body.put("portfolio_policy_sha256", portfolioPolicy.path("content_sha256").asText());
        body.put("portfolio_policy_path", portfolioPolicyPath().toString());
        body.put("portfolio_policy_post_slice_amendment", portfolioPolicy.path("post_slice_amendment").asBoolean(false));
        body.put("lifecycle_timing_policy_sha256", lifecycleTimingPolicy.path("content_sha256").asText());
        body.put("lifecycle_timing_policy_path", lifecycleTimingPolicyPath().toString());
        body.put("exposure_head_sha256", exposure.path("content_sha256").asText());
        body.put("exposure_head_path", exposurePath.toString());
        body.set("legacy_exposure_migration", exposureLineage);
        body.set("physical_source_producer", physical.producerReceipt);
        body.set("build_identity", BuildIdentityService.describe(StrategyFixedBaselineV5.class));
        body.putObject("evaluator").put("name", "TradeLifecycleV5")
                .put("production", true).put("physical_receipt_custody", true)
                .put("shared_fixed_rules", true);
        body.set("frozen_parameters", frozenParameters(baseline, controls, refinementThreshold));
        body.set("control_book_policy", controlBookPolicy());

        List<ObjectNode> features = physical.features;
        Set<String> observedAssets = new LinkedHashSet<>();
        for (ObjectNode feature : features) {
            String asset = feature.path("asset").asText("").toLowerCase();
            if (!asset.isBlank()) observedAssets.add(asset);
        }
        ArrayNode requiredAssets = JsonHashes.mapper().createArrayNode();
        ArrayNode missingAssets = JsonHashes.mapper().createArrayNode();
        for (JsonNode rawAsset : experiment.path("required_assets")) {
            String asset = rawAsset.asText("").toLowerCase();
            if (!asset.isBlank()) {
                requiredAssets.add(asset);
                if (!observedAssets.contains(asset)) missingAssets.add(asset);
            }
        }
        boolean fullDeclaredScope = missingAssets.isEmpty() && requiredAssets.size() > 0;
        body.set("required_assets", requiredAssets);
        body.set("observed_assets", JsonHashes.mapper().valueToTree(observedAssets));
        body.set("missing_assets", missingAssets);
        body.put("evaluation_scope", fullDeclaredScope ? "FULL_DECLARED_SCOPE" : "INTEGRATION_SLICE");
        body.put("full_experiment_complete", fullDeclaredScope);
        List<ObjectNode> eventRows = new ArrayList<>();
        for (ObjectNode feature : features) {
            validateDecisionRow(feature, "feature");
            if (containsFutureField(feature)) throw new IllegalArgumentException(
                    "feature role contains outcome data for " + id(feature));
            if (qualifies(feature, baseline, refinementThreshold)) {
                ObjectNode event = feature.deepCopy();
                event.put("eligible", true);
                event.put("event_time", event.path("decision_time").asText());
                eventRows.add(event);
            }
        }
        eventRows.sort(Comparator.comparing((ObjectNode row) -> row.path("event_time").asText())
                .thenComparing(StrategyFixedBaselineV5::id));

        ArrayNode attempts = JsonHashes.mapper().createArrayNode();
        List<ObjectNode> successfulTrades = new ArrayList<>();
        List<ObjectNode> intervalRows = new ArrayList<>();
        int unresolved = 0;
        int controlsMatched = 0;
        Set<String> usedControls = new HashSet<>();
        Map<String, List<long[]>> selectedControlIntervals = new HashMap<>();
        ArrayNode setupEvents = JsonHashes.mapper().createArrayNode();
        ArrayNode controlSelections = JsonHashes.mapper().createArrayNode();
        ArrayNode skippedEvents = JsonHashes.mapper().createArrayNode();
        Map<String, List<ObjectNode>> poolByAsset = new HashMap<>();
        for (ObjectNode feature : features) {
            ObjectNode candidate = feature.deepCopy();
            candidate.put("event_time", candidate.path("decision_time").asText());
            candidate.put("eligible", true);
            candidate.put("qualifying_shock", qualifies(candidate, baseline, refinementThreshold));
            poolByAsset.computeIfAbsent(candidate.path("asset").asText().toLowerCase(),
                    ignored -> new ArrayList<>()).add(candidate);
        }

        List<SelectedPair> selectedPairs = new ArrayList<>();
        // Roles are opened as content-addressed physical inputs before setup
        // selection, but no label/child value is inspected until after the
        // event has passed the outcome-blind control decision.  The prior
        // admitted event's actual lifecycle exit may release its asset early;
        // an unresolved event keeps the conservative maximum horizon lock.
        Map<String, ObjectNode> labelsById = index(physical.labels, "label");
        Map<String, ObjectNode> executionById = index(physical.executions, "execution");
        Map<String, ObjectNode> eventOutcomesById = new LinkedHashMap<>();
        Map<String, Long> occupiedUntil = new HashMap<>();
        for (ObjectNode event : eventRows) {
            String eventId = id(event);
            long decision = parseTime(event.path("decision_time").asText());
            long occupied = occupiedUntil.getOrDefault(event.path("asset").asText().toLowerCase(), Long.MIN_VALUE);
            if (decision < occupied) {
                ObjectNode skipped = event.deepCopy().put("status", "SKIPPED_OPEN_POSITION")
                        .put("occupied_until", Instant.ofEpochMilli(occupied).toString());
                skippedEvents.add(skipped);
                ObjectNode skippedSelection = JsonHashes.mapper().createObjectNode()
                        .put("schema", "strategy-control-selection/1").put("outcome_blind", true)
                        .put("candidate_count", 0).putNull("control").put("event_id", eventId)
                        .put("status", "SKIPPED_OPEN_POSITION");
                controlSelections.add(withHash(skippedSelection));
                continue;
            }
            occupiedUntil.put(event.path("asset").asText().toLowerCase(), decision + LIFECYCLE_MS);
            setupEvents.add(event.deepCopy());
            ObjectNode calipers = controls.path("calipers").deepCopy();
            calipers.put("maximum_lifecycle_hours", LIFECYCLE_MS / 3_600_000L);
            ArrayNode availablePool = JsonHashes.mapper().createArrayNode();
            for (ObjectNode candidate : poolByAsset.getOrDefault(event.path("asset").asText().toLowerCase(), List.of())) {
                String candidateAsset = candidate.path("asset").asText().toLowerCase();
                long candidateTime = parseTime(candidate.path("event_time").asText());
                List<long[]> controlWindows = selectedControlIntervals.getOrDefault(candidateAsset, List.of());
                List<long[]> candidateWindow = List.of(new long[] {candidateTime, candidateTime + LIFECYCLE_MS});
                if (!usedControls.contains(id(candidate)) && !windowsOverlap(candidateWindow, controlWindows)) {
                    ObjectNode counterfactual = ((ObjectNode) candidate).deepCopy()
                            .put("position_state", "FLAT")
                            .put("position_state_source", "FIXED_COUNTERFACTUAL_CONTROL_BOOK_INITIAL_FLAT_V001");
                    availablePool.add(counterfactual);
                }
            }
            ObjectNode selected = StrategyResearchImprovementV1.selectOutcomeBlindControl(
                    event, availablePool, calipers);
            ObjectNode selection = selected.deepCopy();
            selection.put("event_id", eventId);
            // The event binding is part of the selection receipt. Recompute
            // the receipt hash after adding it so a replay cannot accept a
            // selector hash that covered a different event association.
            controlSelections.add(withHash(selection));
            ObjectNode control = selection.path("control").isObject()
                    ? (ObjectNode) selection.path("control") : null;
            if (control != null) {
                usedControls.add(id(control));
                long controlStart = parseTime(control.path("event_time").asText());
                selectedControlIntervals.computeIfAbsent(control.path("asset").asText().toLowerCase(),
                        ignored -> new ArrayList<>()).add(new long[] {controlStart, controlStart + LIFECYCLE_MS});
                controlsMatched++;
            }
            selectedPairs.add(new SelectedPair(event, control));
            ObjectNode eventOutcome = resolveOutcome(event, labelsById.get(eventId), executionById.get(eventId),
                    baseline, physical, eventId);
            eventOutcomesById.put(eventId, eventOutcome);
            long release = decision + LIFECYCLE_MS;
            if (eventOutcome.path("ok").asBoolean(false)) {
                try {
                    release = parseTime(eventOutcome.path("trade").path("lifecycle").path("exits").path(0)
                            .path("availability_time").asText());
                } catch (RuntimeException ignored) {
                    // A malformed exit remains conservatively occupied through
                    // the declared maximum lifecycle and is reported below.
                }
            }
            occupiedUntil.put(event.path("asset").asText().toLowerCase(), release);
        }

        // Only after setup and control selection are immutable do we open labels
        // and child execution bars.  This prevents future coverage from entering
        // eligibility or the matching denominator.
        List<ObjectNode> eventTrades = new ArrayList<>();
        List<ObjectNode> controlTrades = new ArrayList<>();
        for (SelectedPair pair : selectedPairs) {
            ObjectNode event = pair.event();
            ObjectNode control = pair.control();
            String eventId = id(event);
            ObjectNode attempt = JsonHashes.mapper().createObjectNode()
                    .put("event_id", eventId).put("status", "UNRESOLVED");
            ObjectNode eventOutcome = eventOutcomesById.getOrDefault(eventId,
                    JsonHashes.mapper().createObjectNode().put("ok", false).put("reason", "MISSING_EVENT_OUTCOME"));
            ObjectNode controlOutcome = control == null
                    ? JsonHashes.mapper().createObjectNode().put("ok", false).put("reason", "NO_OUTCOME_BLIND_CONTROL")
                    : resolveOutcome(control, labelsById.get(id(control)), executionById.get(id(control)),
                    baseline, physical, id(control));
            if (eventOutcome.path("ok").asBoolean(false) && controlOutcome.path("ok").asBoolean(false)) {
                ObjectNode eventTrade = (ObjectNode) eventOutcome.path("trade");
                ObjectNode controlTrade = (ObjectNode) controlOutcome.path("trade");
                eventTrades.add(eventTrade.deepCopy());
                controlTrades.add(controlTrade.deepCopy());
                successfulTrades.add(eventTrade.deepCopy());
                successfulTrades.add(controlTrade.deepCopy());
                addPairedInterval(intervalRows, event, control, eventTrade, controlTrade);
                attempt.put("status", "COMPLETE");
                attempt.set("event_trade", eventTrade);
                attempt.set("control_trade", controlTrade);
                attempt.put("paired_net_pnl_usdt", eventTrade.path("net_pnl_usdt").asDouble()
                        - controlTrade.path("net_pnl_usdt").asDouble());
            } else {
                if (eventOutcome.path("ok").asBoolean(false)) {
                    ObjectNode eventTrade = (ObjectNode) eventOutcome.path("trade");
                    eventTrades.add(eventTrade.deepCopy());
                    successfulTrades.add(eventTrade.deepCopy());
                    addInterval(intervalRows, event, eventTrade, "EVENT");
                    attempt.set("event_trade", eventTrade);
                    attempt.put("status", "EVENT_COMPLETE_CONTROL_UNRESOLVED");
                } else {
                    attempt.put("reason", firstFailure(eventOutcome, controlOutcome));
                    unresolved++;
                }
            }
            attempts.add(attempt);
        }

        ObjectNode portfolio = JsonHashes.mapper().createObjectNode().put("account_currency", "USDT");
        portfolio.set("event_book", reconcile(eventTrades, portfolioPolicy));
        portfolio.set("control_book", reconcile(controlTrades, portfolioPolicy));
        portfolio.put("net_pnl_usdt", portfolio.path("event_book").path("net_pnl_usdt").asDouble()
                + portfolio.path("control_book").path("net_pnl_usdt").asDouble());
        portfolio.put("raw_r_excluded_from_equity", true);
        ArrayNode independent = collapseLifecycleEpisodes(intervalRows);
        body.set("setup_events", setupEvents);
        body.set("control_selections", controlSelections);
        body.set("skipped_events", skippedEvents);
        body.set("attempts", attempts);
        body.put("event_count", eventRows.size()).put("matched_control_count", controlsMatched)
                .put("admitted_event_count", selectedPairs.size())
                .put("skipped_open_position_count", skippedEvents.size())
                .put("unresolved_execution_count", unresolved)
                .put("trade_count", successfulTrades.size());
        body.put("market_episode_count", intervalRows.size())
                .put("independent_market_episode_count", independent.size());
        body.set("independent_market_episodes", independent);
        body.set("portfolio", portfolio);
        ObjectNode metrics = metrics(attempts, independent, baseline);
        body.set("metrics", metrics);
        boolean legacyExposureUnresolved = "UNRESOLVED_LEGACY_HISTORY"
                .equals(exposureLineage.path("status").asText());
        boolean invalid = unresolved > 0 || legacyExposureUnresolved;
        boolean pairedCoverageInsufficient = metrics.path("paired_count").asInt(0) == 0;
        int minimumIndependent = baseline.path("market_episode_clustering")
                .path("minimum_independent_episodes").asInt(30);
        boolean eventMinimumMet = metrics.path("event_tested_cluster_count").asInt(0) >= minimumIndependent;
        boolean pairedMinimumMet = metrics.path("paired_tested_cluster_count").asInt(0) >= minimumIndependent;
        boolean minimumsMet = eventMinimumMet && pairedMinimumMet
                && metrics.path("tested_trade_count").asInt(0) >= 30
                && !pairedCoverageInsufficient;
        boolean insufficient = !fullDeclaredScope || !minimumsMet || successfulTrades.isEmpty();
        boolean falsifierPassed = metrics.path("falsifier").path("p20_pass").asBoolean(false)
                && metrics.path("falsifier").path("p_value_pass").asBoolean(false);
        boolean economicFailure = !invalid && !insufficient && !falsifierPassed;
        ObjectNode flags = JsonHashes.mapper().createObjectNode().put("invalid_evidence", invalid)
                .put("legacy_exposure_unresolved", legacyExposureUnresolved)
                .put("insufficient_evidence", insufficient).put("economic_failure", economicFailure)
                .put("eligible", !invalid && !insufficient && !economicFailure);
        body.set("disposition", StrategyResearchImprovementV1.disposition(flags));
        body.put("minimum_independent_episodes", minimumIndependent)
                .put("unconditional_event_minimum_met", eventMinimumMet)
                .put("paired_minimum_met", pairedMinimumMet);
        body.put("status", invalid ? "BLOCKED" : "COMPLETE");
        body.put("diagnostic_only", true).put("promotion_eligible", false);
        body.put("shared_physical_evaluator", true);
        body.set("physical_limitations", physicalLimitations(physical));
        body.put("economic_semantic_sha256", economicSemanticHash(body));
        body.put("semantic_sha256", semanticHash(body));
        return withHash(body);
    }

    private static ObjectNode resolveOutcome(ObjectNode row, ObjectNode label, ObjectNode execution,
            ObjectNode baseline, PhysicalInput physical, String id) {
        ObjectNode error = JsonHashes.mapper().createObjectNode().put("ok", false);
        if (label == null || execution == null) {
            return error.put("reason", "MISSING_PHYSICAL_EXECUTION_ROLE").put("episode_id", id);
        }
        if (!receiptMatchesAsset(label, row) || !receiptMatchesAsset(execution, row)
                || (label.has("episode_id") && !id.equals(label.path("episode_id").asText()))
                || (execution.has("episode_id") && !id.equals(execution.path("episode_id").asText()))) {
            return error.put("reason", "OUTCOME_METADATA_ASSET_OR_EPISODE_MISMATCH").put("episode_id", id);
        }
        try {
            long decision = parseTime(row.path("decision_time").asText());
            long ceiling = parseTime(label.path("resolution_ceiling_time").asText());
            long resolved = parseTime(label.path("resolution_time").asText());
            long available = parseTime(label.path("availability_time").asText());
            if (ceiling != decision + LIFECYCLE_MS || resolved < ceiling || available < resolved) {
                return error.put("reason", "OUTCOME_NOT_MATURE_OR_MISBOUND").put("episode_id", id);
            }
        } catch (RuntimeException malformedLabel) {
            return error.put("reason", "OUTCOME_NOT_MATURE_OR_MISBOUND").put("episode_id", id);
        }
        String barsRole = execution.path("bars_role").asText("");
        if (barsRole.isBlank() || !physical.roles.containsKey(barsRole)) {
            return error.put("reason", "MISSING_PHYSICAL_1M_BARS_RECEIPT").put("episode_id", id);
        }
        try {
            Role barsRoleValue = physical.roles.get(barsRole);
            Role contractRole = roleFor(physical, execution, row, "contract_spec");
            Role modelRole = roleFor(physical, execution, row, "execution_model");
            Role capacityRole = roleFor(physical, execution, row, "capacity");
            Role nonTradingRole = physical.roles.get("non_trading_intervals");
            if (contractRole == null || modelRole == null || capacityRole == null) {
                return error.put("reason", "MISSING_ASSET_BOUND_COST_OR_FILTER_RECEIPT").put("episode_id", id);
            }
            validateResolvedPhysicalCosts(contractRole, modelRole, capacityRole, baseline);
            JsonNode barsValue = barsRoleValue.value;
            if (!barsValue.isArray()) throw new IllegalArgumentException("bars receipt is not an array");
            for (JsonNode bar : barsValue) {
                if (!bar.isObject()) throw new IllegalArgumentException("bars receipt contains a non-object row");
                if (!receiptMatchesAsset(bar, row)) {
                    throw new IllegalArgumentException("child bar asset or symbol differs from the setup row");
                }
            }
            if (execution.has("child_bars") && !JsonHashes.canonicalSha256(execution.path("child_bars"))
                    .equals(JsonHashes.canonicalSha256(barsValue))) {
                throw new IllegalArgumentException("execution child bars differ from the bound bars receipt");
            }
            ObjectNode lifecycle = JsonHashes.mapper().createObjectNode()
                    .put("max_lifecycle_ms", LIFECYCLE_MS).put("gap_policy", "OPEN")
                    .put("fill_availability_policy", readLifecycleTimingPolicy().path("policy_id").asText());
            lifecycle.putObject("stop").put("type", "PERCENT").put("value",
                    baseline.path("execution").path("stop").path("distance").asDouble(0.06));
            lifecycle.putObject("sizing").put("mode", "FIXED_NOTIONAL")
                    .put("notional_usd", baseline.path("execution").path("sizing")
                            .path("notional_usdt").asDouble(1000));
            ObjectNode intent = JsonHashes.mapper().createObjectNode().put("direction", "long")
                    .put("instrument_type", "SPOT").put("instrument", "BINANCE_SPOT")
                    .put("venue", "BINANCE").put("asset", row.path("asset").asText())
                    .put("symbol", row.path("symbol").asText())
                    .put("decision_time", row.path("decision_time").asText());
            intent.set("lifecycle", lifecycle);
            ObjectNode request = JsonHashes.mapper().createObjectNode().put("interval_ms", 60_000);
            request.set("intent", intent);
            request.set("bars", barsValue.deepCopy());
            ObjectNode requestExecution = JsonHashes.mapper().createObjectNode();
            // The trust receipt covers the complete frozen policy. Validate
            // the row-specific projection, then pass the full interval list
            // so TradeLifecycleV5 can compare the caller input to the bound
            // receipt byte-for-byte before it permits any gap.
            nonTradingIntervalsFor(nonTradingRole, row);
            if (nonTradingRole != null) {
                requestExecution.set("allowed_non_trading_intervals",
                        nonTradingRole.value.path("intervals").deepCopy());
            }
            request.set("execution", requestExecution);
            Map<String, LifecycleTrustService.ReceiptReference> refs = new LinkedHashMap<>();
            refs.put("contract_spec", contractRole.reference);
            refs.put("execution_model", modelRole.reference);
            refs.put("capacity", capacityRole.reference);
            refs.put("bars", barsRoleValue.reference);
            if (nonTradingRole != null) refs.put("non_trading_intervals", nonTradingRole.reference);
            ObjectNode lineage = JsonHashes.mapper().createObjectNode()
                    .put("lifecycle_spec_sha256", JsonHashes.canonicalSha256(lifecycle))
                    .put("baseline_sha256", baseline.path("content_sha256").asText());
            if (nonTradingRole != null) {
                lineage.put("non_trading_intervals_sha256", nonTradingRole.reference.contentSha256());
            }
            LifecycleTrustService trust = new LifecycleTrustService();
            LifecycleTrustService.Token token = trust.openLifecycleTrustV5(
                    physical.root, physical.rootReference, refs, lineage, true);
            ObjectNode result = new TradeLifecycleV5(trust).normalizeTradeLifecycleV5(request, token);
            ObjectNode trade = JsonHashes.mapper().createObjectNode().put("episode_id", id)
                    .put("entry_price", result.path("entry_price").asDouble())
                    .put("exit_price", result.path("exits").path(0).path("price").asDouble())
                    .put("quantity", result.path("quantity").asDouble())
                    .put("gross_pnl_usdt", result.path("gross_pnl_usd").asDouble())
                    .put("fees_usdt", result.path("fees_usd").asDouble())
                    .put("slippage_usdt", result.path("slippage_usd").asDouble())
                    .put("capacity_debit_usdt", result.path("capacity_debit_usd").asDouble())
                    .put("net_pnl_usdt", result.path("net_pnl_usd").asDouble())
                    .put("account_currency", "USDT");
            trade.set("lifecycle", result);
            trade.set("portfolio_mark_points", portfolioMarkPoints(barsValue, result));
            return error.put("ok", true).set("trade", trade);
        } catch (RuntimeException failure) {
            return error.put("reason", "PHYSICAL_LIFECYCLE_FAILURE:" + failure.getMessage())
                    .put("episode_id", id);
        }
    }

    private static ArrayNode nonTradingIntervalsFor(Role role, ObjectNode row) {
        ArrayNode selected = JsonHashes.mapper().createArrayNode();
        if (role == null) return selected;
        JsonNode policy = role.value;
        boolean typedV1 = policy.isObject()
                && "strategy-fixed-baseline-non-trading/1".equals(policy.path("schema").asText())
                && policy.path("version").asInt(-1) == 1
                && "NON_TRADING_CLOSURE_V001".equals(policy.path("policy_id").asText());
        boolean typedV2 = policy.isObject()
                && "strategy-fixed-baseline-non-trading/2".equals(policy.path("schema").asText())
                && policy.path("version").asInt(-1) == 2
                && "NON_TRADING_CLOSURE_V002_ERRATUM".equals(policy.path("policy_id").asText());
        if (!(typedV1 || typedV2)
                || !"FROZEN".equals(policy.path("status").asText())
                || !"BINANCE".equals(policy.path("venue").asText())
                || !"BINANCE_SPOT".equals(policy.path("instrument").asText())
                || !"EXPLICIT_INTERVAL_ASSETS_ONLY".equals(policy.path("asset_scope").asText())
                || !policy.path("intervals").isArray()) {
            throw new IllegalArgumentException("non-trading interval policy is not the frozen v001 contract");
        }
        for (JsonNode interval : policy.path("intervals")) {
            if (!interval.isObject()) throw new IllegalArgumentException("non-trading interval is not an object");
            String asset = interval.path("asset").asText("");
            String symbol = interval.path("symbol").asText("");
            long start = interval.path("start_ms").asLong(Long.MIN_VALUE);
            long end = interval.path("end_ms").asLong(Long.MIN_VALUE);
            if (asset.isBlank() || symbol.isBlank() || start == Long.MIN_VALUE || end <= start
                    || !"NON_TRADING".equals(interval.path("reason").asText())
                    || !"BINANCE".equals(interval.path("venue").asText())
                    || !"BINANCE_SPOT".equals(interval.path("instrument").asText())
                    || start % 60_000L != 0 || end % 60_000L != 0
                    || !interval.path("notice_url").asText("").startsWith("https://")) {
                throw new IllegalArgumentException("non-trading interval is incomplete");
            }
            if (asset.equalsIgnoreCase(row.path("asset").asText())
                    && symbol.equalsIgnoreCase(row.path("symbol").asText())) {
                selected.add(interval.deepCopy());
            }
        }
        return selected;
    }

    private static ObjectNode metrics(ArrayNode attempts, ArrayNode independent, ObjectNode baseline) {
        Map<String, Observation> eventObservations = new LinkedHashMap<>();
        Map<String, Observation> pairedObservations = new LinkedHashMap<>();
        double paired = 0, eventTotal = 0; int pairedCount = 0, eventCount = 0, controlsUnresolved = 0;
        double stopDistance = baseline.path("execution").path("stop").path("distance").asDouble(Double.NaN);
        for (JsonNode attempt : attempts) {
            JsonNode eventTrade = attempt.path("event_trade");
            if (eventTrade.isObject()) {
                double eventNet = eventTrade.path("net_pnl_usdt").asDouble(Double.NaN);
                double risk = tradeRisk(eventTrade, stopDistance);
                if (Double.isFinite(eventNet) && Double.isFinite(risk) && risk > 0) {
                    String eventId = attempt.path("event_id").asText(eventTrade.path("episode_id").asText());
                    eventObservations.put(eventId, new Observation(eventNet, risk));
                    eventTotal += eventNet; eventCount++;
                }
            }
            if ("EVENT_COMPLETE_CONTROL_UNRESOLVED".equals(attempt.path("status").asText())) controlsUnresolved++;
            if ("COMPLETE".equals(attempt.path("status").asText())) {
                JsonNode controlTrade = attempt.path("control_trade");
                double delta = attempt.path("paired_net_pnl_usdt").asDouble(Double.NaN);
                double risk = tradeRisk(eventTrade, stopDistance);
                if (controlTrade.isObject() && Double.isFinite(delta) && Double.isFinite(risk) && risk > 0) {
                    String pairId = attempt.path("event_id").asText() + "::" + controlTrade.path("episode_id").asText();
                    eventObservations.put(pairId, new Observation(eventTrade.path("net_pnl_usdt").asDouble(), risk));
                    pairedObservations.put(pairId, new Observation(delta, risk));
                    paired += delta; pairedCount++;
                }
            }
        }
        List<Observation> eventClusters = clusterObservations(independent, eventObservations);
        List<Observation> pairedClusters = clusterObservations(independent, pairedObservations);
        List<Double> eventR = eventClusters.stream().map(Observation::r).toList();
        List<Double> pairedR = pairedClusters.stream().map(Observation::r).toList();
        double eventP20 = bootstrapMeanQuantile(eventR, 0.20, 20260906L);
        double pairedP20 = bootstrapMeanQuantile(pairedR, 0.20, 20260906L);
        double eventPValue = bootstrapNonPositiveMeanPValue(eventR, 20260906L);
        double pairedPValue = bootstrapNonPositiveMeanPValue(pairedR, 20260906L);
        double p20 = pairedCount > 0 ? pairedP20 : eventP20;
        double pValue = pairedCount > 0 ? pairedPValue : eventPValue;
        boolean eventP20Pass = Double.isFinite(eventP20) && eventP20 >= 0.05;
        boolean pairedP20Pass = Double.isFinite(pairedP20) && pairedP20 >= 0.05;
        boolean eventPValuePass = Double.isFinite(eventPValue) && eventPValue <= 0.10;
        boolean pairedPValuePass = Double.isFinite(pairedPValue) && pairedPValue <= 0.10;
        boolean p20Pass = pairedCount > 0 ? eventP20Pass && pairedP20Pass : eventP20Pass;
        boolean pValuePass = pairedCount > 0 ? eventPValuePass && pairedPValuePass : eventPValuePass;
        ObjectNode falsifier = JsonHashes.mapper().createObjectNode()
                .put("null", "paired event-minus-control expectancy <= 0 or event expectancy <= 0 after costs")
                .put("test_basis", pairedCount > 0 ? "JOINT_EVENT_AND_PAIRED" : "UNCONDITIONAL_EVENT")
                .put("estimand", "cluster-level net P&L divided by aggregate fixed-stop risk; bootstrap unit is one merged lifecycle cluster")
                .put("p20_expectancy_r_min", 0.05)
                .put("p20_pass", p20Pass).put("max_statistic_p_value", 0.1)
                .put("p_value_pass", pValuePass)
                .put("bootstrap_iterations", 10_000).put("bootstrap_seed", 20260906L)
                .put("bootstrap_unit", "MERGED_LIFECYCLE_INTERVAL")
                .put("minimum_independent_episodes", baseline.path("market_episode_clustering")
                        .path("minimum_independent_episodes").asInt(30))
                .put("minimum_tested_trades", 30)
                .put("failure_disposition", "ECONOMIC_FAILURE only after valid full-scope minimums; otherwise INSUFFICIENT_EVIDENCE");
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("paired_count", pairedCount)
                .put("paired_mean_net_pnl_usdt", pairedCount == 0 ? 0 : paired / pairedCount)
                .put("unconditional_event_count", eventCount).put("unconditional_event_mean_net_pnl_usdt",
                        eventCount == 0 ? 0 : eventTotal / eventCount)
                .put("unmatched_control_or_control_unresolved_count", controlsUnresolved)
                .put("tested_trade_count", pairedCount > 0 ? pairedCount : eventCount)
                .put("tested_cluster_count", pairedCount > 0 ? pairedClusters.size() : eventClusters.size())
                .put("event_tested_cluster_count", eventClusters.size())
                .put("paired_tested_cluster_count", pairedClusters.size())
                .put("independent_market_episode_count", independent.size())
                .put("uncertainty_unit", "MERGED_LIFECYCLE_INTERVAL")
                .put("paired_analysis_insufficient", pairedCount == 0
                        || pairedClusters.size() < baseline.path("market_episode_clustering")
                                .path("minimum_independent_episodes").asInt(30));
        putFinite(falsifier, "p20_expectancy_r", p20);
        putFinite(falsifier, "statistic_p_value", pValue);
        putFinite(falsifier, "event_p20_expectancy_r", eventP20);
        putFinite(falsifier, "paired_p20_expectancy_r", pairedP20);
        putFinite(falsifier, "event_statistic_p_value", eventPValue);
        putFinite(falsifier, "paired_statistic_p_value", pairedPValue);
        falsifier.put("event_p20_pass", eventP20Pass).put("paired_p20_pass", pairedP20Pass)
                .put("event_p_value_pass", eventPValuePass).put("paired_p_value_pass", pairedPValuePass)
                .put("joint_p20_pass", p20Pass).put("joint_p_value_pass", pValuePass);
        putFinite(result, "p20_expectancy_r", p20);
        result.set("falsifier", falsifier);
        return result;
    }

    private static void putFinite(ObjectNode object, String field, double value) {
        if (Double.isFinite(value)) object.put(field, value); else object.putNull(field);
    }

    private static double tradeRisk(JsonNode trade, double stopDistance) {
        double entry = trade.path("entry_price").asDouble(Double.NaN);
        double quantity = trade.path("quantity").asDouble(Double.NaN);
        return Math.abs(entry * quantity * stopDistance);
    }

    private static List<Observation> clusterObservations(ArrayNode clusters, Map<String, Observation> observations) {
        List<Observation> result = new ArrayList<>();
        for (JsonNode cluster : clusters) {
            double net = 0, risk = 0;
            for (JsonNode id : cluster.path("source_episode_ids")) {
                Observation observation = observations.get(id.asText());
                if (observation != null) { net += observation.net(); risk += observation.risk(); }
            }
            if (Double.isFinite(net) && Double.isFinite(risk) && risk > 0) result.add(new Observation(net, risk));
        }
        return result;
    }

    private static double bootstrapMeanQuantile(List<Double> values, double probability, long seed) {
        if (values.isEmpty()) return Double.NaN;
        final int iterations = 10_000;
        SplittableRandom random = new SplittableRandom(seed);
        double[] means = new double[iterations];
        for (int iteration = 0; iteration < iterations; iteration++) {
            double sum = 0;
            for (int i = 0; i < values.size(); i++) sum += values.get(random.nextInt(values.size()));
            means[iteration] = sum / values.size();
        }
        java.util.Arrays.sort(means);
        return means[Math.max(0, Math.min(iterations - 1,
                (int) Math.floor(probability * (iterations - 1))))];
    }

    /** Deterministic null-centred block bootstrap over independent episodes. */
    private static double bootstrapNonPositiveMeanPValue(List<Double> values, long seed) {
        if (values.isEmpty()) return Double.NaN;
        final int iterations = 10_000;
        double observed = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        if (!Double.isFinite(observed)) return Double.NaN;
        double center = observed;
        double[] centered = values.stream().mapToDouble(value -> value - center).toArray();
        SplittableRandom random = new SplittableRandom(seed);
        int nonPositive = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            double sum = 0;
            for (int i = 0; i < centered.length; i++) sum += centered[random.nextInt(centered.length)];
            if (sum / centered.length >= observed) nonPositive++;
        }
        return (nonPositive + 1D) / (iterations + 1D);
    }

    private record Observation(double net, double risk) {
        double r() { return net / risk; }
    }

    private static void addInterval(List<ObjectNode> target, ObjectNode setup, ObjectNode trade, String kind) {
        long start = parseTime(setup.path("decision_time").asText());
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("episode_id", id(setup))
                .put("kind", kind).put("asset", setup.path("asset").asText())
                .put("start_ms", start).put("end_ms", start + LIFECYCLE_MS)
                .put("net_pnl_usdt", trade.path("net_pnl_usdt").asDouble());
        row.putArray("window_intervals").add(JsonHashes.mapper().createObjectNode()
                .put("start_ms", start).put("end_ms", start + LIFECYCLE_MS)
                .put("asset", setup.path("asset").asText()));
        target.add(row);
    }

    private static void addPairedInterval(List<ObjectNode> target, ObjectNode event, ObjectNode control,
            ObjectNode eventTrade, ObjectNode controlTrade) {
        long eventStart = parseTime(event.path("decision_time").asText());
        long controlStart = parseTime(control.path("decision_time").asText());
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("episode_id", id(event) + "::" + id(control)).put("kind", "PAIRED")
                .put("asset", event.path("asset").asText())
                .put("start_ms", Math.min(eventStart, controlStart))
                .put("end_ms", Math.max(eventStart, controlStart) + LIFECYCLE_MS)
                .put("net_pnl_usdt", eventTrade.path("net_pnl_usdt").asDouble()
                        - controlTrade.path("net_pnl_usdt").asDouble())
                .put("event_episode_id", id(event)).put("control_episode_id", id(control));
        row.putArray("window_intervals")
                .add(JsonHashes.mapper().createObjectNode().put("start_ms", eventStart)
                        .put("end_ms", eventStart + LIFECYCLE_MS).put("asset", event.path("asset").asText()))
                .add(JsonHashes.mapper().createObjectNode().put("start_ms", controlStart)
                        .put("end_ms", controlStart + LIFECYCLE_MS).put("asset", control.path("asset").asText()));
        target.add(row);
    }

    private static ArrayNode collapseLifecycleEpisodes(List<ObjectNode> rows) {
        /*
         * A matched pair is one statistical observation, but its historical
         * control and treated lifecycles can be far apart.  Union rows only
         * when their actual lifecycle windows overlap; never turn the empty
         * interval between pair endpoints into exposure and accidentally
         * merge unrelated market episodes.
         */
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        int count = rows.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        for (int left = 0; left < count; left++) {
            List<long[]> leftWindows = lifecycleWindows(rows.get(left));
            for (int right = left + 1; right < count; right++) {
                if (windowsOverlap(leftWindows, lifecycleWindows(rows.get(right)))) union(parent, left, right);
            }
        }
        Map<Integer, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(rows.get(i));
        List<List<ObjectNode>> sortedGroups = new ArrayList<>(groups.values());
        sortedGroups.sort(Comparator.comparingLong(group -> lifecycleWindows(group.get(0)).stream()
                .mapToLong(window -> window[0]).min().orElse(Long.MAX_VALUE)));
        for (List<ObjectNode> group : sortedGroups) {
            long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
            ArrayNode members = JsonHashes.mapper().createArrayNode();
            ArrayNode windows = JsonHashes.mapper().createArrayNode();
            for (ObjectNode row : group) {
                members.add(row.path("episode_id").asText());
                for (long[] window : lifecycleWindows(row)) {
                    start = Math.min(start, window[0]); end = Math.max(end, window[1]);
                    windows.add(JsonHashes.mapper().createObjectNode().put("start_ms", window[0]).put("end_ms", window[1])
                            .put("source_episode_id", row.path("episode_id").asText()));
                }
            }
            ObjectNode cluster = JsonHashes.mapper().createObjectNode().put("start_ms", start).put("end_ms", end)
                    .put("episode_id", members.path(0).asText());
            cluster.set("source_episode_ids", members); cluster.set("window_intervals", windows);
            cluster.put("cluster_id", JsonHashes.canonicalSha256(cluster));
            cluster.put("uncertainty_unit", "MERGED_LIFECYCLE_INTERVAL");
            result.add(cluster);
        }
        return result;
    }

    private static List<long[]> lifecycleWindows(ObjectNode row) {
        List<long[]> windows = new ArrayList<>();
        JsonNode declared = row.path("window_intervals");
        if (declared.isArray() && !declared.isEmpty()) for (JsonNode value : declared) {
            long start = value.path("start_ms").asLong(Long.MIN_VALUE);
            long end = value.path("end_ms").asLong(Long.MIN_VALUE);
            if (start != Long.MIN_VALUE && end >= start) windows.add(new long[] {start, end});
        }
        if (windows.isEmpty()) windows.add(new long[] {row.path("start_ms").asLong(), row.path("end_ms").asLong()});
        return windows;
    }

    private static boolean windowsOverlap(List<long[]> left, List<long[]> right) {
        for (long[] a : left) for (long[] b : right) if (a[0] < b[1] && b[0] < a[1]) return true;
        return false;
    }

    private static int find(int[] parent, int value) {
        while (parent[value] != value) { parent[value] = parent[parent[value]]; value = parent[value]; }
        return value;
    }

    private static void union(int[] parent, int left, int right) {
        int a = find(parent, left), b = find(parent, right);
        if (a != b) parent[b] = a;
    }

    /**
     * Reconcile one counterfactual book in account currency.  Realized P&L is
     * posted at the lifecycle exit, never at the entry timestamp.  Between
     * those timestamps the position is carried at its raw entry notional; the
     * lifecycle receipt is the source of the executable exit mark.  This is a
     * conservative mark for a diagnostic book and is explicit in the result.
     */
    private static ObjectNode reconcile(List<ObjectNode> trades, ObjectNode portfolioPolicy) {
        double gross = 0, fees = 0, slippage = 0, capacity = 0, net = 0;
        List<ObjectNode> ordered = trades.stream().sorted(Comparator
                .comparing((ObjectNode trade) -> trade.path("lifecycle").path("entry_time").asText())
                .thenComparing(StrategyFixedBaselineV5::id)).toList();
        double startingCapital = portfolioPolicy.path("starting_cash_usdt").asDouble(FIXED_STARTING_CASH_USDT);
        double cash = startingCapital, holdingsAtEntryCost = 0, realized = 0;
        double peak = startingCapital, maxDrawdown = 0;
        ArrayNode curve = JsonHashes.mapper().createArrayNode();
        List<LedgerEvent> events = new ArrayList<>();
        for (ObjectNode trade : ordered) {
            ObjectNode lifecycle = trade.path("lifecycle").isObject()
                    ? (ObjectNode) trade.path("lifecycle") : null;
            if (lifecycle == null || !lifecycle.path("exits").isArray() || lifecycle.path("exits").isEmpty()) {
                throw new IllegalArgumentException("trade lifecycle lacks an executable exit for reconciliation");
            }
            ObjectNode exit = (ObjectNode) lifecycle.path("exits").get(lifecycle.path("exits").size() - 1);
            events.add(new LedgerEvent(lifecycle.path("entry_time").asText(), 0, trade, exit));
            for (JsonNode mark : trade.path("portfolio_mark_points")) {
                events.add(new LedgerEvent(mark.path("time").asText(), 1, trade, exit,
                        mark.path("price").asDouble(Double.NaN)));
            }
            events.add(new LedgerEvent(exitAvailabilityTime(exit), 2, trade, exit,
                    exit.path("price").asDouble(Double.NaN)));
            gross += trade.path("gross_pnl_usdt").asDouble(); fees += trade.path("fees_usdt").asDouble();
            slippage += trade.path("slippage_usdt").asDouble(); capacity += trade.path("capacity_debit_usdt").asDouble();
            net += trade.path("net_pnl_usdt").asDouble();
        }
        // A close-derived exit becomes available at the close boundary.  If
        // another event is admitted at that same instant, release the old
        // position before charging the new entry; otherwise a valid
        // close-to-close handoff can manufacture a transient cash deficit.
        events.sort(Comparator.comparing(LedgerEvent::time).thenComparingInt(StrategyFixedBaselineV5::ledgerEventOrder)
                .thenComparing(event -> id(event.trade())));
        Map<String, Double> markedHoldings = new LinkedHashMap<>();
        for (LedgerEvent event : events) {
            ObjectNode trade = event.trade(); ObjectNode lifecycle = (ObjectNode) trade.path("lifecycle");
            double notional = trade.path("entry_price").asDouble() * trade.path("quantity").asDouble();
            double totalFees = trade.path("fees_usdt").asDouble();
            double totalSlippage = trade.path("slippage_usdt").asDouble();
            double exitFees = event.exit().path("fees_usd").asDouble(0);
            double exitSlippage = event.exit().path("slippage_usd").asDouble(0);
            if (event.kind() == 0) {
                double entryFees = totalFees - exitFees, entrySlippage = totalSlippage - exitSlippage;
                double capacityDebit = trade.path("capacity_debit_usdt").asDouble();
                cash -= notional + entryFees + entrySlippage + capacityDebit;
                holdingsAtEntryCost += notional;
                markedHoldings.put(id(trade), notional);
                if (cash < -1e-9) throw new IllegalArgumentException("fixed portfolio exhausted its declared starting cash at entry");
                double equity = cash + markedHoldings.values().stream().mapToDouble(Double::doubleValue).sum();
                peak = Math.max(peak, equity);
                maxDrawdown = Math.max(maxDrawdown, peak - equity);
                addLedgerPoint(curve, event, cash, holdingsAtEntryCost, equity,
                        peak - equity, "ENTRY", 0, notional);
            } else if (event.kind() == 1) {
                if (Double.isFinite(event.markPrice())) {
                    markedHoldings.put(id(trade), event.markPrice() * trade.path("quantity").asDouble());
                }
                double equity = cash + markedHoldings.values().stream().mapToDouble(Double::doubleValue).sum();
                peak = Math.max(peak, equity); maxDrawdown = Math.max(maxDrawdown, peak - equity);
                addLedgerPoint(curve, event, cash, holdingsAtEntryCost, equity, peak - equity,
                        "MARK", 0, notional);
            } else {
                double exitValue = event.exit().path("price").asDouble() * trade.path("quantity").asDouble();
                cash += exitValue - exitFees - exitSlippage;
                holdingsAtEntryCost -= notional;
                markedHoldings.remove(id(trade));
                double tradeNet = trade.path("net_pnl_usdt").asDouble();
                realized += tradeNet;
                double equity = cash + markedHoldings.values().stream().mapToDouble(Double::doubleValue).sum();
                peak = Math.max(peak, equity); maxDrawdown = Math.max(maxDrawdown, peak - equity);
                addLedgerPoint(curve, event, cash, holdingsAtEntryCost, equity, peak - equity,
                        "EXIT", tradeNet, notional);
            }
        }
        if (cash < -1e-9) throw new IllegalArgumentException("fixed portfolio exhausted its declared starting cash");
        if (Math.abs((cash + holdingsAtEntryCost) - (startingCapital + net)) >= 1e-9) {
            throw new IllegalArgumentException("portfolio ending equity does not reconcile to starting cash plus net P&L");
        }
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("account_currency", "USDT")
                .put("starting_equity_usdt", startingCapital).put("starting_capital_policy", PORTFOLIO_CAPITAL_POLICY)
                .put("trade_count", ordered.size()).put("gross_pnl_usdt", gross).put("fees_usdt", fees)
                .put("slippage_usdt", slippage).put("capacity_debit_usdt", capacity).put("net_pnl_usdt", net)
                .put("realized_pnl_usdt", realized).put("ending_equity_usdt", cash + holdingsAtEntryCost)
                .put("max_drawdown_usdt", maxDrawdown)
                .put("mark_method", "PHYSICAL_1M_CLOSES_AT_60M_SAMPLES;MAX_DRAWDOWN_IS_SAMPLED")
                .put("realized_pnl_posted_at_exit", true).put("negative_cash", cash < 0)
                .put("ending_minus_starting_equals_net", Math.abs((cash + holdingsAtEntryCost)
                        - (startingCapital + net)) < 1e-9)
                .put("sum_check", Math.abs(net - (gross - fees - slippage - capacity)) < 1e-9)
                .put("raw_r_excluded_from_equity", true);
        ArrayNode rows = JsonHashes.mapper().createArrayNode(); ordered.forEach(rows::add); result.set("trades", rows);
        result.set("equity_curve", curve);
        return result;
    }

    private static ObjectNode readPortfolioPolicy() {
        ObjectNode policy = readContract(portfolioPolicyPath(), "portfolio policy",
                "strategy-fixed-baseline-portfolio-policy/1");
        if (!PORTFOLIO_CAPITAL_POLICY.equals(policy.path("policy_id").asText())
                || policy.path("starting_cash_usdt").asDouble(Double.NaN) != FIXED_STARTING_CASH_USDT
                || policy.path("max_concurrent_slots").asInt(-1) != 8
                || !policy.path("post_slice_amendment").asBoolean(false)) {
            throw new ExternalPrerequisite("PORTFOLIO_POLICY_UNAVAILABLE", "portfolio capital/mark policy is not the frozen additive policy");
        }
        return policy;
    }

    private static Path portfolioPolicyPath() {
        return Path.of("strategy-research", "experiments", "fk-deleveraging-baseline-v002",
                "portfolio-policy-v001.json").toAbsolutePath().normalize();
    }

    private static Path lifecycleTimingPolicyPath() {
        return Path.of("strategy-research", "experiments", "fk-deleveraging-baseline-v002",
                "lifecycle-timing-v001.json").toAbsolutePath().normalize();
    }

    private static ObjectNode readLifecycleTimingPolicy() {
        ObjectNode policy = readContract(lifecycleTimingPolicyPath(), "lifecycle timing policy",
                "strategy-fixed-baseline-lifecycle-timing/1");
        if (!"FIXED_LIFECYCLE_TIMING_V001".equals(policy.path("policy_id").asText())
                || !"fk-deleveraging-absorption-baseline-v002".equals(policy.path("baseline_id").asText())
                || !"4d2563ed94eae50e076f75f010adea3232b66ef5bd058f0cd1967051f1f16176"
                        .equals(policy.path("raw_definition_sha256").asText())) {
            throw new ExternalPrerequisite("LIFECYCLE_TIMING_POLICY_UNAVAILABLE",
                    "lifecycle timing policy is not bound to the frozen v002 definition");
        }
        return policy;
    }

    private static String exitAvailabilityTime(ObjectNode exit) {
        String availability = exit.path("availability_time").asText("");
        if (!availability.isBlank()) return availability;
        String raw = exit.path("time").asText("");
        if (raw.isBlank()) throw new IllegalArgumentException("lifecycle exit lacks time/availability_time");
        return raw;
    }

    private static int ledgerEventOrder(LedgerEvent event) {
        return event.kind() == 2 ? 0 : event.kind() == 0 ? 1 : 2;
    }

    private static void addLedgerPoint(ArrayNode curve, LedgerEvent event, double cash, double holdings,
            double equity, double drawdown, String type, double realized, double notional) {
        curve.add(JsonHashes.mapper().createObjectNode().put("episode_id", id(event.trade()))
                .put("event_time", event.time()).put("event_type", type).put("cash_usdt", cash)
                .put("holdings_at_entry_cost_usdt", holdings).put("equity_usdt", equity)
                .put("drawdown_usdt", drawdown).put("realized_pnl_usdt", realized)
                .put("entry_notional_usdt", notional));
    }

    private static ArrayNode portfolioMarkPoints(JsonNode bars, ObjectNode lifecycle) {
        ArrayNode marks = JsonHashes.mapper().createArrayNode();
        if (!bars.isArray()) return marks;
        long entry = parseTime(lifecycle.path("entry_time").asText());
        long exit = parseTime(exitAvailabilityTime((ObjectNode) lifecycle.path("exits").path(0)));
        int index = 0;
        for (JsonNode bar : bars) {
            String timeText = firstTime((ObjectNode) bar, "close_time", "open_time", "event_time");
            if (timeText.isBlank()) continue;
            long time = parseTime(timeText);
            if (time < entry || time > exit) { index++; continue; }
            if (index % 60 == 0 || time >= exit - 60_000L) {
                double close = bar.path("close").asDouble(Double.NaN);
                if (Double.isFinite(close) && close > 0) marks.add(JsonHashes.mapper().createObjectNode()
                        .put("time", timeText).put("price", close).put("source", "PHYSICAL_1M_CLOSE"));
            }
            index++;
        }
        return marks;
    }

    private static ObjectNode frozenParameters(ObjectNode baseline, ObjectNode controls, double refinementThreshold) {
        ObjectNode frozen = JsonHashes.mapper().createObjectNode();
        frozen.set("shock_rule", baseline.path("shock_rule").deepCopy());
        if (Double.isFinite(refinementThreshold)) {
            frozen.with("shock_rule").with("return").put("threshold", refinementThreshold);
            frozen.put("effective_shock_threshold", refinementThreshold);
        }
        frozen.set("execution", baseline.path("execution").deepCopy());
        frozen.set("controls", controls.deepCopy());
        frozen.put("frozen_before_outcome_read", true);
        frozen.put("control_max_lifecycle_ms", LIFECYCLE_MS);
        frozen.put("selection_future_coverage_independent", true);
        frozen.set("control_book_policy", controlBookPolicy());
        return frozen;
    }

    private static ObjectNode controlBookPolicy() {
        return JsonHashes.mapper().createObjectNode()
                .put("policy_id", "FIXED_COUNTERFACTUAL_CONTROL_BOOK_FLAT_V001")
                .put("initial_position_state", "FLAT")
                .put("position_state_source", "SIMULATED_RESEARCH_BOOK;NOT_PERSONAL_ACCOUNT_LEDGER")
                .put("admission", "CONSERVATIVE_MAX_LIFECYCLE_FROM_CANDIDATE_DECISION")
                .put("without_replacement", true)
                .put("same_asset_scheduled_overlap_excluded", true)
                .put("outcome_blind", true);
    }

    private static boolean qualifies(ObjectNode row, ObjectNode baseline) {
        return qualifies(row, baseline, Double.NaN);
    }

    private static boolean qualifies(ObjectNode row, ObjectNode baseline, double refinementThreshold) {
        if (!row.path("signal_eligible").asBoolean(true)) return false;
        if (row.path("setup_bar_count").asInt(0) < baseline.path("data_contract")
                .path("warmup_completed_signal_bars").asInt(31)) return false;
        double ret = number(row, "shock_return", Double.NaN);
        double vol = number(row, "volume_multiple", Double.NaN);
        double realized = number(row, "realized_volatility", Double.NaN);
        double returnThreshold = Double.isFinite(refinementThreshold)
                ? refinementThreshold
                : baseline.path("shock_rule").path("return").path("threshold").asDouble();
        return Double.isFinite(ret) && Double.isFinite(vol) && Double.isFinite(realized)
                && ret <= returnThreshold
                && vol >= baseline.path("shock_rule").path("volume").path("threshold").asDouble()
                && realized >= baseline.path("shock_rule").path("volatility").path("threshold").asDouble();
    }

    private static void validateDecisionRow(ObjectNode row, String role) {
        String decision = row.path("decision_time").asText("");
        if (decision.isBlank() || row.path("availability_time").asText("").isBlank()
                || row.path("asset").asText("").isBlank()) throw new IllegalArgumentException(
                role + " lacks decision_time/availability_time/asset: " + id(row));
        long decisionMs = parseTime(decision), availableMs = parseTime(row.path("availability_time").asText());
        if (availableMs > decisionMs || decisionMs % FOUR_HOURS_MS != 0) throw new IllegalArgumentException(
                role + " is not available at the completed 4h decision boundary: " + id(row));
    }

    private static void validateFrozenContracts(ObjectNode baseline, ObjectNode controls, ObjectNode experiment,
            Path baselinePath, Path controlsPath) {
        if (!"FROZEN".equals(baseline.path("status").asText())
                || !"FROZEN".equals(experiment.path("status").asText())) throw new IllegalArgumentException(
                "fixed baseline requires FROZEN baseline and experiment");
        validateSupportedFixedContract(baseline, controls, experiment);
        if (baseline.path("activation").path("authorized").asBoolean(true)
                || !"NONE_FROM_BASELINE_ALONE".equals(baseline.path("activation")
                        .path("promotion_eligibility").asText())) throw new IllegalArgumentException(
                "baseline activation contract is not fail-closed");
        if (!baseline.path("baseline_id").asText().equals(controls.path("baseline_id").asText())) {
            throw new IllegalArgumentException("control lineage does not identify the frozen baseline");
        }
        if (!controls.path("outcome_blind").asBoolean(false) || !controls.path("decision_time_only").asBoolean(false)) {
            throw new IllegalArgumentException("control spec must be outcome-blind and decision-time-only");
        }
        if (!baseline.path("content_sha256").asText().equals(experiment.path("baseline_sha256").asText())
                || !controls.path("content_sha256").asText().equals(experiment.path("controls_sha256").asText())) {
            throw new IllegalArgumentException("experiment is not hash-bound to the supplied frozen definitions");
        }
        if (controls.path("calipers").path("minimum_prior_lag_hours").asDouble(0) < 240
                || controls.path("calipers").path("maximum_prior_lookback_days").asDouble(0) <= 0) {
            throw new IllegalArgumentException("control chronology is not frozen with a feasible prior window");
        }
        String baseFile = baselinePath.getFileName().toString();
        if (!experiment.path("baseline_path").asText().contains(baseFile)
                || !experiment.path("controls_path").asText().contains(controlsPath.getFileName().toString())) {
            throw new IllegalArgumentException("experiment is not linked to the supplied typed definitions");
        }
    }

    /**
     * This evaluator is deliberately a fixed FK v002 implementation.  Keep
     * the supported numeric/formula surface explicit so a rehashed contract
     * cannot silently select a different lookback, horizon, or execution
     * convention while still entering this production path.  A new strategy
     * family must register a separate evaluator rather than inheriting these
     * assumptions by accident.
     */
    private static void validateSupportedFixedContract(ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment) {
        if (!"fk-deleveraging-absorption-baseline-v002".equals(baseline.path("baseline_id").asText())
                || !"fk-deleveraging-absorption".equals(baseline.path("hypothesis_family").asText())
                || !"FIXED_BASELINE".equals(experiment.path("stage").asText())
                || !"NO_SELECTION_SEARCH".equals(experiment.path("selection").asText())) {
            throw new IllegalArgumentException("fixed evaluator only supports the frozen FK v002 contract");
        }
        ObjectNode data = baseline.path("data_contract").isObject()
                ? (ObjectNode) baseline.path("data_contract") : null;
        if (data == null || !"4h".equals(data.path("signal_timeframe").asText())
                || !"1m".equals(data.path("execution_timeframe").asText())
                || !data.path("completed_bar_only").asBoolean(false)
                || data.path("warmup_completed_signal_bars").asInt(-1) != 31) {
            throw new IllegalArgumentException("unsupported fixed evaluator data contract");
        }
        ObjectNode shock = baseline.path("shock_rule").isObject()
                ? (ObjectNode) baseline.path("shock_rule") : null;
        ObjectNode ret = shock == null || !shock.path("return").isObject()
                ? null : (ObjectNode) shock.path("return");
        ObjectNode volume = shock == null || !shock.path("volume").isObject()
                ? null : (ObjectNode) shock.path("volume");
        ObjectNode volatility = shock == null || !shock.path("volatility").isObject()
                ? null : (ObjectNode) shock.path("volatility");
        if (ret == null || volume == null || volatility == null
                || !"completed_bar_close_to_close_return".equals(ret.path("name").asText())
                || !"close_t / close_(t-1) - 1".equals(ret.path("formula").asText())
                || ret.path("threshold").asDouble(Double.NaN) != -0.08
                || !"trailing_volume_multiple_excluding_current_bar".equals(volume.path("name").asText())
                || volume.path("threshold").asDouble(Double.NaN) != 2.0
                || volume.path("lookback_completed_bars").asInt(-1) != 30
                || !volume.path("current_bar_excluded").asBoolean(false)
                || !"trailing_realized_volatility".equals(volatility.path("name").asText())
                || volatility.path("threshold").asDouble(Double.NaN) != 0.015
                || volatility.path("lookback_completed_bars").asInt(-1) != 30
                || !volatility.path("current_bar_excluded").asBoolean(false)) {
            throw new IllegalArgumentException("unsupported fixed evaluator shock formula or threshold");
        }
        ObjectNode execution = baseline.path("execution").isObject()
                ? (ObjectNode) baseline.path("execution") : null;
        ObjectNode timeout = execution == null || !execution.path("timeout").isObject()
                ? null : (ObjectNode) execution.path("timeout");
        ObjectNode fees = execution == null || !execution.path("fees").isObject()
                ? null : (ObjectNode) execution.path("fees");
        ObjectNode slippage = execution == null || !execution.path("slippage").isObject()
                ? null : (ObjectNode) execution.path("slippage");
        ObjectNode sizing = execution == null || !execution.path("sizing").isObject()
                ? null : (ObjectNode) execution.path("sizing");
        if (execution == null || timeout == null || fees == null || slippage == null || sizing == null
                || timeout.path("bars").asInt(-1) != 14_400
                || timeout.path("horizon_hours").asInt(-1) != 240
                || fees.path("entry_rate").asDouble(Double.NaN) != 0.001
                || fees.path("exit_rate").asDouble(Double.NaN) != 0.001
                || slippage.path("entry_rate").asDouble(Double.NaN) != 0.00050
                || slippage.path("exit_rate").asDouble(Double.NaN) != 0.00050
                || sizing.path("notional_usdt").asDouble(Double.NaN) != 1000.0
                || !"FIXED_NOTIONAL_USDT".equals(sizing.path("mode").asText())
                || execution.path("stop").path("distance").asDouble(Double.NaN) != 0.06
                || !execution.path("overlap").path("per_asset").asText().startsWith("one_open_position")) {
            throw new IllegalArgumentException("unsupported fixed evaluator execution contract");
        }
        ArrayNode matching = controls.path("matching_variables").isArray()
                ? (ArrayNode) controls.path("matching_variables") : null;
        List<String> expectedMatching = List.of("prior_30_bar_return", "prior_30_bar_realized_volatility",
                "prior_30_bar_volume_zscore", "hour_of_day", "day_of_week");
        List<String> actualMatching = new ArrayList<>();
        if (matching != null) for (JsonNode value : matching) actualMatching.add(value.asText());
        if (matching == null || matching.size() != expectedMatching.size()
                || !expectedMatching.equals(actualMatching)) {
            throw new IllegalArgumentException("unsupported fixed evaluator control matching variables");
        }
        ObjectNode calipers = controls.path("calipers").isObject()
                ? (ObjectNode) controls.path("calipers") : null;
        if (calipers == null || calipers.path("minimum_prior_lag_hours").asDouble(Double.NaN) != 480.0
                || calipers.path("maximum_prior_lookback_days").asDouble(Double.NaN) != 365.0
                || calipers.path("maximum_lifecycle_hours").asDouble(Double.NaN) != 240.0) {
            throw new IllegalArgumentException("unsupported fixed evaluator control chronology");
        }
    }

    private static ObjectNode readExposure(Path path, String family) {
        try {
            Path canonical = StrategyResearchAuthoritativeV5.canonicalExposureHeadPath(family)
                    .toAbsolutePath().normalize();
            if (!canonical.equals(path.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("exposure head is not the canonical family path: " + canonical);
            }
            ObjectNode value = readObject(path, "exposure head");
            StrategyStatisticalV5.validateExposureHead(value);
            if (!family.equals(value.path("hypothesis_family").asText()) && value.has("hypothesis_family")) {
                throw new IllegalArgumentException("exposure head belongs to another hypothesis family");
            }
            return value;
        } catch (RuntimeException error) {
            throw new ExternalPrerequisite("EXPOSURE_HEAD_UNAVAILABLE", error.getMessage());
        }
    }

    /**
     * Open the canonical family HEAD, or create its genesis only after the
     * legacy-family boundary has been checked.  A missing HEAD is an
     * orchestration gap, while a recoverable v1-v4 family record is a custody
     * boundary: the runner must stop rather than reset cumulative K to zero.
     */
    private static ObjectNode readOrInitializeExposure(Path path, String family, String datasetSha256) {
        Path canonical = StrategyResearchAuthoritativeV5.canonicalExposureHeadPath(family)
                .toAbsolutePath().normalize();
        if (!canonical.equals(path.toAbsolutePath().normalize())) {
            throw new ExternalPrerequisite("EXPOSURE_HEAD_UNAVAILABLE", "exposure head is not the canonical family path: " + canonical);
        }
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) return readExposure(canonical, family);
        try {
            ObjectNode lineage = auditExposureLineage(null, family);
            if ("UNRESOLVED_LEGACY_HISTORY".equals(lineage.path("status").asText())) {
                throw new IllegalArgumentException("legacy family exposure history is unresolved; genesis is not permitted");
            }
            ObjectNode migration = JsonHashes.mapper().createObjectNode()
                    .put("recordRoot", Path.of("strategy-research", "runs").toAbsolutePath().normalize().toString())
                    .put("family", family);
            migration.set("exposureHead", JsonHashes.mapper().createObjectNode().put("cumulative_k", 0));
            if (!StrategyResearchAuthoritativeV5.assertLegacyFamilyMigrationBoundary(migration)) {
                throw new IllegalArgumentException("legacy family migration boundary did not pass");
            }
            ObjectNode make = JsonHashes.mapper().createObjectNode().put("hypothesisFamily", family)
                    .put("datasetSha256", datasetSha256);
            make.putArray("entries");
            ObjectNode head = StrategyStatisticalV5.makeExposureHead(make);
            ObjectNode initialize = JsonHashes.mapper().createObjectNode().put("filePath", canonical.toString());
            initialize.set("head", head);
            try {
                return StrategyStatisticalV5.initializeExposureHeadFile(initialize);
            } catch (RuntimeException raced) {
                if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) return readExposure(canonical, family);
                throw raced;
            }
        } catch (RuntimeException error) {
            throw new ExternalPrerequisite("EXPOSURE_HEAD_UNAVAILABLE", error.getMessage());
        }
    }

    /**
     * Re-audits lineage even when a canonical HEAD already exists.  A HEAD
     * with a nonzero K is not proof that the old v1-v4 family history was
     * migrated: the legacy runs live beside v5 records and many retained
     * candidate rows are JSONL.  We preserve the HEAD and expose this audit
     * as a diagnostic custody limit instead of resetting K or claiming a
     * fresh family.
     */
    private static ObjectNode auditExposureLineage(ObjectNode exposure, String family) {
        ObjectNode audit = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-exposure-lineage/1")
                .put("family", family)
                .put("diagnostic_only", true)
                .put("promotion_blocked", false)
                .put("search_root", Path.of("strategy-research", "runs").toAbsolutePath().normalize().toString());
        ArrayNode searchRoots = JsonHashes.mapper().createArrayNode()
                .add(Path.of("strategy-research", "runs").toAbsolutePath().normalize().toString());
        audit.set("search_roots", searchRoots);
        if (exposure != null) {
            audit.put("canonical_head_sha256", exposure.path("content_sha256").asText())
                    .put("canonical_cumulative_k", exposure.path("cumulative_k").asLong(0))
                    .put("canonical_exposure_attempt_k", exposure.path("exposure_attempt_k").asLong(0));
        }
        ArrayNode matches = JsonHashes.mapper().createArrayNode();
        Path root = Path.of("strategy-research", "runs").toAbsolutePath().normalize();
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.walk(root)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.toString().endsWith(".json") || path.toString().endsWith(".jsonl"))
                        .sorted().forEach(path -> collectLegacyFamilyMatches(path, family, matches));
            } catch (IOException error) {
                audit.put("scan_error", error.getMessage());
            }
        }
        audit.set("matches", matches);
        boolean unresolved = !matches.isEmpty();
        audit.put("recoverable_legacy_record_count", matches.size())
                .put("status", unresolved ? "UNRESOLVED_LEGACY_HISTORY" : "NO_RECOVERABLE_LEGACY_HISTORY")
                .put("promotion_blocked", unresolved);
        return withHash(audit);
    }

    private static void collectLegacyFamilyMatches(Path path, String family, ArrayNode matches) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            int rows = 0;
            if (path.toString().endsWith(".jsonl")) {
                for (String line : text.split("\\R")) {
                    if (line.isBlank()) continue;
                    JsonNode value = JsonHashes.mapper().readTree(line);
                    if (containsFamilyIdentity(value, family)) rows++;
                }
            } else {
                JsonNode value = JsonHashes.mapper().readTree(bytes);
                String schema = value.path("schema").asText("");
                if (!isLegacyExposureSchema(schema) || !containsFamilyIdentity(value, family)) return;
                rows = 1;
            }
            if (rows > 0) matches.add(JsonHashes.mapper().createObjectNode()
                    .put("path", path.toString())
                    .put("byte_sha256", JsonHashes.sha256(bytes))
                    .put("matching_record_count", rows));
        } catch (Exception ignored) {
            // An unreadable retained artifact is itself not a recoverable
            // exposure vector; the physical run records it elsewhere.
        }
    }

    private static boolean isLegacyExposureSchema(String schema) {
        return !schema.isBlank() && !schema.startsWith("strategy-fixed-baseline-")
                && !schema.startsWith("strategy-baseline-spec/")
                && !schema.startsWith("strategy-control-spec/")
                && !schema.startsWith("strategy-v5-") && !schema.startsWith("strategy-research-index/");
    }

    private static boolean containsFamilyIdentity(JsonNode node, String family) {
        if (node == null) return false;
        if (node.isTextual() && sameFamilyIdentity(family, node.asText())) return true;
        if (node.isArray()) { for (JsonNode child : node) if (containsFamilyIdentity(child, family)) return true; }
        if (node.isObject()) { var fields = node.fields(); while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().toLowerCase().contains("family") && containsFamilyIdentity(field.getValue(), family)) return true;
            if (containsFamilyIdentity(field.getValue(), family)) return true;
        }}
        return false;
    }

    private static boolean sameFamilyIdentity(String left, String right) {
        return normalizeFamilyIdentity(left).equals(normalizeFamilyIdentity(right));
    }

    private static String normalizeFamilyIdentity(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static void validateBuildIdentity() {
        ObjectNode identity = BuildIdentityService.describe(StrategyFixedBaselineV5.class);
        if (!"KNOWN".equals(identity.path("status").asText())
                || !"JAR".equals(identity.path("executable").path("kind").asText())
                || !identity.path("executable").path("sha256").asText().matches("[a-f0-9]{64}")) {
            throw new ExternalPrerequisite("BUILD_IDENTITY_UNAVAILABLE", "fixed evidence requires a packaged build marker");
        }
    }

    private static ObjectNode appendAttempt(Path path, ObjectNode prior, String datasetSha256, String family,
            String behaviorDefinitionSha256, String refinementMember) {
        if (!family.equals(prior.path("hypothesis_family").asText())) {
            throw new IllegalArgumentException("fixed exposure head belongs to another family");
        }
        ObjectNode args = JsonHashes.mapper().createObjectNode().put("filePath", path.toString())
                .put("expectedHeadSha256", prior.path("content_sha256").asText())
                .put("datasetSha256", datasetSha256).put("exposureAttemptCount", 1).put("fixedAttempt", true)
                .put("source", refinementMember.isBlank()
                        ? "FIXED_BASELINE_DIAGNOSTIC" : "FIXED_REFINEMENT_DIAGNOSTIC")
                .put("observedAt", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                        .withZone(ZoneOffset.UTC).format(Instant.now()));
        args.putArray("behaviorAliases").add(behaviorDefinitionSha256);
        args.putObject("behaviorDefinitions").put(behaviorDefinitionSha256, behaviorDefinitionSha256);
        try {
            ObjectNode next = StrategyStatisticalV5.appendExposureHeadFile(args);
            if (!family.equals(next.path("hypothesis_family").asText())) {
                throw new IllegalArgumentException("appended exposure head changed family");
            }
            return next;
        } catch (RuntimeException error) {
            throw new ExternalPrerequisite("EXPOSURE_APPEND_UNAVAILABLE", error.getMessage());
        }
    }

    private static void validatePhysicalCosts(PhysicalInput physical, ObjectNode baseline) {
        Role model = physical.roles.get("execution_model");
        Role capacity = physical.roles.get("capacity");
        Role contract = physical.roles.get("contract_spec");
        if (model == null || capacity == null || contract == null
                || !model.value.isObject() || !capacity.value.isObject() || !contract.value.isObject()) {
            throw new ExternalPrerequisite("PHYSICAL_COST_RECEIPTS_UNAVAILABLE",
                    "contract_spec, execution_model, and capacity receipts are required");
        }
        double fee = baseline.path("execution").path("fees").path("entry_rate").asDouble(Double.NaN);
        double exitFee = baseline.path("execution").path("fees").path("exit_rate").asDouble(Double.NaN);
        double slippage = baseline.path("execution").path("slippage").path("entry_rate").asDouble(Double.NaN);
        double modelFee = model.value.path("taker_fee_rate").asDouble(Double.NaN);
        double modelSlip = model.value.path("slippage_bps").asDouble(Double.NaN) / 10_000D;
        if (!Double.isFinite(fee) || !Double.isFinite(exitFee) || !Double.isFinite(slippage)
                || Math.abs(fee - exitFee) > 1e-12 || !Double.isFinite(modelFee)
                || Math.abs(modelFee - fee) > 1e-12 || !Double.isFinite(modelSlip)
                || Math.abs(modelSlip - slippage) > 1e-12) {
            throw new ExternalPrerequisite("PHYSICAL_COST_RECEIPTS_MISMATCH",
                    "physical fee/slippage metadata does not equal frozen baseline costs");
        }
        double notional = baseline.path("execution").path("sizing").path("notional_usdt")
                .asDouble(baseline.path("execution").path("sizing").path("notional_usd").asDouble(Double.NaN));
        double physicalNotional = capacity.value.path("order_notional_usd").asDouble(Double.NaN);
        if (!Double.isFinite(notional) || !Double.isFinite(physicalNotional)
                || Math.abs(physicalNotional - notional) > 1e-9) {
            throw new ExternalPrerequisite("PHYSICAL_CAPACITY_RECEIPT_MISMATCH",
                    "physical capacity order notional does not equal frozen baseline sizing");
        }
        if (!(contract.value.path("step_size").asDouble(0) > 0)
                || !(contract.value.path("min_qty").asDouble(0) > 0)
                || !(contract.value.path("min_notional").asDouble(0) > 0)
                || !(contract.value.path("max_notional").asDouble(0) > 0)) {
            throw new ExternalPrerequisite("PHYSICAL_FILTER_RECEIPT_UNAVAILABLE",
                    "decision-time exchange quantity, min/max quantity and notional filters are required");
        }
        if (!(capacity.value.path("available_liquidity_usd").asDouble(0) > 0)
                || !(capacity.value.path("participation_cap").asDouble(0) > 0)
                || capacity.value.path("participation_cap").asDouble(0) > 1) {
            throw new ExternalPrerequisite("PHYSICAL_CAPACITY_RECEIPT_UNAVAILABLE",
                    "capacity receipt must bind available liquidity and participation cap");
        }
        for (Map.Entry<String, Role> entry : physical.roles.entrySet()) {
            String roleName = entry.getKey(); JsonNode value = entry.getValue().value;
            if (!value.isObject() || !(roleName.startsWith("execution_model:")
                    || roleName.startsWith("capacity:") || roleName.startsWith("contract_spec:"))) continue;
            if (roleName.startsWith("execution_model:")) {
                double feeForAsset = value.path("taker_fee_rate").asDouble(Double.NaN);
                double slipForAsset = value.path("slippage_bps").asDouble(Double.NaN) / 10_000D;
                if (!Double.isFinite(feeForAsset) || !Double.isFinite(slipForAsset)
                        || Math.abs(feeForAsset - fee) > 1e-12 || Math.abs(slipForAsset - slippage) > 1e-12) {
                    throw new ExternalPrerequisite("PHYSICAL_COST_RECEIPTS_MISMATCH",
                            roleName + " does not equal the frozen baseline costs");
                }
            } else if (roleName.startsWith("capacity:")) {
                double orderNotional = value.path("order_notional_usd").asDouble(Double.NaN);
                double liquidity = value.path("available_liquidity_usd").asDouble(Double.NaN);
                double participation = value.path("participation_cap").asDouble(Double.NaN);
                if (!Double.isFinite(orderNotional) || !Double.isFinite(liquidity) || liquidity <= 0
                        || !Double.isFinite(participation) || participation <= 0 || participation > 1
                        || Math.abs(orderNotional - notional) > 1e-9) {
                    throw new ExternalPrerequisite("PHYSICAL_CAPACITY_RECEIPT_UNAVAILABLE",
                            roleName + " has invalid or non-finite capacity metadata");
                }
            } else {
                double step = value.path("step_size").asDouble(Double.NaN);
                double minQty = value.path("min_qty").asDouble(Double.NaN);
                double minNotional = value.path("min_notional").asDouble(Double.NaN);
                double maxNotional = value.path("max_notional").asDouble(Double.NaN);
                if (!Double.isFinite(step) || step <= 0 || !Double.isFinite(minQty) || minQty <= 0
                        || !Double.isFinite(minNotional) || minNotional <= 0 || !Double.isFinite(maxNotional)
                        || maxNotional <= 0) {
                    throw new ExternalPrerequisite("PHYSICAL_FILTER_RECEIPT_UNAVAILABLE",
                            roleName + " has invalid or non-finite exchange filters");
                }
            }
        }
    }

    /** Resolve asset-specific receipts first, then a deliberately common role. */
    private static Role roleFor(PhysicalInput physical, ObjectNode execution, ObjectNode row, String base) {
        String asset = row.path("asset").asText("").toLowerCase();
        String configured = execution.path(base + "_role").asText("");
        String key = configured.isBlank() ? base + ":" + asset : configured;
        Role selected = physical.roles.get(key);
        if (selected == null && configured.isBlank()) selected = physical.roles.get(base);
        if (selected == null || !receiptMatchesAsset(selected.value, row)) return null;
        return selected;
    }

    private static void validateResolvedPhysicalCosts(Role contract, Role model, Role capacity,
            ObjectNode baseline) {
        if (!contract.value.isObject() || !model.value.isObject() || !capacity.value.isObject()) {
            throw new IllegalArgumentException("resolved physical cost/filter roles must be objects");
        }
        double fee = baseline.path("execution").path("fees").path("entry_rate").asDouble(Double.NaN);
        double slippage = baseline.path("execution").path("slippage").path("entry_rate").asDouble(Double.NaN);
        double modelFee = model.value.path("taker_fee_rate").asDouble(Double.NaN);
        double modelSlip = model.value.path("slippage_bps").asDouble(Double.NaN) / 10_000D;
        double notional = baseline.path("execution").path("sizing").path("notional_usdt")
                .asDouble(baseline.path("execution").path("sizing").path("notional_usd").asDouble(Double.NaN));
        double orderNotional = capacity.value.path("order_notional_usd").asDouble(Double.NaN);
        if (!Double.isFinite(fee) || !Double.isFinite(slippage) || !Double.isFinite(modelFee)
                || !Double.isFinite(modelSlip) || Math.abs(modelFee - fee) > 1e-12
                || Math.abs(modelSlip - slippage) > 1e-12) {
            throw new IllegalArgumentException("resolved execution model differs from frozen fee/slippage costs");
        }
        if (!Double.isFinite(notional) || !Double.isFinite(orderNotional)
                || Math.abs(orderNotional - notional) > 1e-9) {
            throw new IllegalArgumentException("resolved capacity differs from frozen notional");
        }
        double step = contract.value.path("step_size").asDouble(Double.NaN);
        double minQty = contract.value.path("min_qty").asDouble(Double.NaN);
        double minNotional = contract.value.path("min_notional").asDouble(Double.NaN);
        double maxNotional = contract.value.path("max_notional").asDouble(Double.NaN);
        double liquidity = capacity.value.path("available_liquidity_usd").asDouble(Double.NaN);
        double participation = capacity.value.path("participation_cap").asDouble(Double.NaN);
        if (!Double.isFinite(step) || step <= 0 || !Double.isFinite(minQty) || minQty <= 0
                || !Double.isFinite(minNotional) || minNotional <= 0 || !Double.isFinite(maxNotional)
                || maxNotional <= 0 || !Double.isFinite(liquidity) || liquidity <= 0
                || !Double.isFinite(participation) || participation <= 0 || participation > 1) {
            throw new IllegalArgumentException("resolved physical filters/capacity are invalid");
        }
    }

    private static boolean receiptMatchesAsset(JsonNode receipt, ObjectNode row) {
        String receiptAsset = receipt.path("asset").asText("");
        String receiptSymbol = receipt.path("symbol").asText("");
        String rowAsset = row.path("asset").asText("");
        String rowSymbol = row.path("symbol").asText("");
        return (receiptAsset.isBlank() || receiptAsset.equalsIgnoreCase(rowAsset))
                && (receiptSymbol.isBlank() || receiptSymbol.equalsIgnoreCase(rowSymbol));
    }

    private static ObjectNode blockedResult(String reason, String detail, ObjectNode args,
            Path baseline, Path controls, Path experiment, Path physical, Path exposure) {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", RESULT_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("stage", "FIXED_BASELINE").put("evidence_phase", "DEVELOPMENT")
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("optimizer_invoked", false).put("reason", reason).put("detail", detail)
                .put("diagnostic_only", true);
        value.putObject("inputs").put("baseline", baseline.toString()).put("controls", controls.toString())
                .put("experiment", experiment.toString()).put("physical_input", physical.toString())
                .put("exposure_head", exposure.toString());
        copyIdentityIfPresent(value, baseline, "baseline_sha256");
        copyIdentityIfPresent(value, controls, "control_spec_sha256");
        copyIdentityIfPresent(value, experiment, "experiment_sha256");
        try {
            ObjectNode physicalInput = readObject(physical, "blocked fixed baseline physical input");
            copyIdentityIfPresent(value, physicalInput, "physical_input_sha256");
            String family = readObject(baseline, "blocked fixed baseline baseline").path("hypothesis_family").asText("");
            if (!family.isBlank()) value.put("hypothesis_family", family);
        } catch (RuntimeException ignored) {
            // The original failure remains the durable disposition when an
            // input itself is malformed; such a result cannot be resumed.
        }
        value.set("build_identity", BuildIdentityService.describe(StrategyFixedBaselineV5.class));
        ObjectNode flags = JsonHashes.mapper().createObjectNode().put("invalid_evidence", true);
        value.set("disposition", StrategyResearchImprovementV1.disposition(flags));
        value.put("economic_semantic_sha256", economicSemanticHash(value));
        value.put("semantic_sha256", semanticHash(value));
        return withHash(value);
    }

    private static void copyIdentityIfPresent(ObjectNode target, Path source, String field) {
        try { copyIdentityIfPresent(target, readObject(source, field), field); }
        catch (RuntimeException ignored) { }
    }

    private static void copyIdentityIfPresent(ObjectNode target, ObjectNode source, String field) {
        String value = source.path("content_sha256").asText("");
        if (!value.isBlank()) target.put(field, value);
    }

    private static ArrayNode physicalLimitations(PhysicalInput physical) {
        ArrayNode limitations = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<String, Role> entry : physical.roles.entrySet()) {
            String role = entry.getKey();
            if (!(role.equals("contract_spec") || role.equals("execution_model") || role.equals("capacity")
                    || role.startsWith("contract_spec:") || role.startsWith("execution_model:")
                    || role.startsWith("capacity:"))) continue;
            Role value = entry.getValue();
            if (value == null || !value.value.isObject()) continue;
            String filter = value.value.path("filter_status").asText("");
            String cost = value.value.path("cost_provenance").asText("");
            if (!filter.isBlank() && filter.toUpperCase().contains("USER_BOUND")) {
                limitations.add("USER_BOUND_HISTORICAL_FILTERS:" + role);
            }
            if (!cost.isBlank() && cost.toUpperCase().contains("USER_BOUND")) {
                limitations.add("USER_BOUND_RETROSPECTIVE_COSTS:" + role);
            }
        }
        return limitations;
    }

    private static PhysicalInput readPhysicalInput(Path path) {
        try {
            ObjectNode input = readObject(path, "fixed baseline physical input");
            ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(input);
            if (!INPUT_SCHEMA.equals(input.path("schema").asText())
                    || !"AUTHORITATIVE_PHYSICAL".equals(input.path("status").asText())) {
                throw new IllegalArgumentException("physical input is not an authoritative fixed-baseline bundle");
            }
            if (!input.path("content_sha256").asText().equals(JsonHashes.ownHash(input))) {
                throw new IllegalArgumentException("physical input content hash is invalid");
            }
            Path root = Path.of(input.path("root").asText()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("physical root is missing");
            ObjectNode producerReceipt = readBoundProducerReceipt(input, root);
            Map<String, Role> roles = new LinkedHashMap<>();
            input.path("roles").fields().forEachRemaining(entry -> roles.put(entry.getKey(), readRole(root, entry.getValue(), entry.getKey())));
            if (!roles.containsKey("signal_bars")) {
                throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                        "authoritative fixed baseline requires signal_bars derived from verified completed 4h bars; caller feature rows are not an accepted producer");
            }
            List<ObjectNode> features = deriveFeatures(rows(roles, "signal_bars"), rows(roles, "features"));
            List<ObjectNode> labels = rows(roles, "labels");
            List<ObjectNode> executions = rows(roles, "executions");
            return new PhysicalInput(root, input.path("root_reference").asText("fixed-baseline-input"),
                    input.path("content_sha256").asText(), roles, features, labels, executions, producerReceipt);
        } catch (ExternalPrerequisite error) { throw error;
        } catch (Exception error) { throw new ExternalPrerequisite("PHYSICAL_INPUT_UNAVAILABLE", error.getMessage()); }
    }

    /** Verify the signal role's typed producer receipt and declared scope. */
    private static ObjectNode readBoundProducerReceipt(ObjectNode input, Path root) {
        JsonNode raw = input.path("producer_receipt");
        JsonNode signalRaw = input.path("roles").path("signal_bars");
        if (!raw.isObject() || !signalRaw.isObject()) {
            throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                    "physical input lacks a typed signal producer receipt");
        }
        String receiptPathText = raw.path("path").asText("");
        if (receiptPathText.isBlank()) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "producer receipt path is missing");
        Path receiptPath = root.resolve(receiptPathText).normalize();
        try {
            Path rootReal = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path receiptReal = receiptPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!receiptReal.startsWith(rootReal)) throw new IOException("producer receipt escapes physical root");
            ObjectNode receipt = readObject(receiptPath, "signal producer receipt");
            if (!"strategy-fixed-baseline-signal-producer-receipt/1".equals(receipt.path("schema").asText())
                    || !"VERIFIED_PHYSICAL".equals(receipt.path("status").asText())
                    || !receipt.path("content_sha256").asText().equals(JsonHashes.ownHash(receipt))) {
                throw new IOException("producer receipt schema/status/hash is invalid");
            }
            if (!receipt.path("content_sha256").asText().equals(raw.path("content_sha256").asText())) {
                throw new IOException("producer receipt binding differs from physical input");
            }
            String manifestPathText = receipt.path("source_manifest_path").asText("");
            Path manifestPath = root.resolve(manifestPathText).normalize();
            Path manifestReal = manifestPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!manifestReal.startsWith(rootReal)) throw new IOException("source manifest escapes physical root");
            ObjectNode manifest = readObject(manifestPath, "signal source manifest");
            if (!"strategy-v5-parquet-conversion/1".equals(manifest.path("schema").asText())
                    || !manifest.path("authoritative").asBoolean(false)
                    || !manifest.path("content_sha256").asText().equals(JsonHashes.ownHash(manifest))
                    || !manifest.path("content_sha256").asText().equals(receipt.path("source_manifest_sha256").asText())) {
                throw new IOException("source manifest is not the bound authoritative manifest");
            }
            // A caller-authored receipt is insufficient custody. Reopen the
            // frozen manifest and physical Parquet partitions and run the
            // same producer used to create signal_bars. The role is accepted
            // only when its content equals that recomputation.
            ObjectNode canonicalManifest = readObject(VERIFIED_PARQUET_MANIFEST,
                    "canonical signal source manifest");
            if (!canonicalManifest.path("content_sha256").asText()
                    .equals(receipt.path("source_manifest_sha256").asText())) {
                throw new IOException("source manifest is not the frozen canonical Parquet manifest");
            }
            ObjectNode produced = buildSignalBarsFromVerifiedParquet(JsonHashes.mapper().createObjectNode()
                    .put("manifest", VERIFIED_PARQUET_MANIFEST.toString())
                    .put("root", VERIFIED_PARQUET_ROOT.toString()));
            if (!produced.path("role_content_sha256").asText()
                    .equals(signalRaw.path("content_sha256").asText())) {
                throw new IOException("signal role differs from recomputed verified Parquet producer output");
            }
            if (!"strategy-fixed-baseline-signal-bars/1".equals(receipt.path("producer_schema").asText())
                    || !receipt.path("signal_role_content_sha256").asText()
                            .equals(signalRaw.path("content_sha256").asText())
                    || !receipt.path("source_manifest_sha256").asText()
                            .equals(signalRaw.path("source_manifest_sha256").asText())) {
                throw new IOException("signal role is not bound to the producer receipt");
            }
            ArrayNode roleRows = readRoleRowsForBinding(root, signalRaw, "signal_bars");
            Set<String> assets = new LinkedHashSet<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (JsonNode row : roleRows) {
                String asset = row.path("asset").asText("").toLowerCase();
                if (asset.isBlank()) throw new IOException("signal producer row lacks asset");
                assets.add(asset); counts.merge(asset, 1, Integer::sum);
                long event = parseTime(firstTime((ObjectNode) row, "event_time", "open_time"));
                min = Math.min(min, event); max = Math.max(max, event);
            }
            Set<String> declaredAssets = new LinkedHashSet<>();
            for (JsonNode asset : receipt.path("declared_assets")) declaredAssets.add(asset.asText().toLowerCase());
            if (!assets.equals(declaredAssets)
                    || !JsonHashes.mapper().valueToTree(counts).equals(receipt.path("asset_counts"))
                    || min != receipt.path("coverage_start_ms").asLong(Long.MIN_VALUE)
                    || max != receipt.path("coverage_end_ms").asLong(Long.MIN_VALUE)) {
                throw new IOException("signal producer coverage differs from its declared scope");
            }
            return receipt;
        } catch (ExternalPrerequisite error) { throw error;
        } catch (Exception error) {
            throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", error.getMessage());
        }
    }

    private static ArrayNode readRoleRowsForBinding(Path root, JsonNode raw, String name) {
        Path file = root.resolve(raw.path("path").asText()).normalize();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (!JsonHashes.sha256(bytes).equals(raw.path("byte_sha256").asText())) throw new IOException(name + " bytes changed");
            JsonNode value = JsonHashes.mapper().readTree(bytes);
            if (!value.isArray() || !JsonHashes.ownHash(value).equals(raw.path("content_sha256").asText())) {
                throw new IOException(name + " role content changed");
            }
            return (ArrayNode) value;
        } catch (IOException error) { throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", error.getMessage()); }
    }

    private static Role readRole(Path root, JsonNode raw, String name) {
        if (!raw.isObject()) throw new ExternalPrerequisite("PHYSICAL_INPUT_UNAVAILABLE", "role " + name + " is not an object");
        Path file = root.resolve(raw.path("path").asText()).normalize();
        try {
            Path rootReal = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!fileReal.startsWith(rootReal) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("missing role file or role escapes physical root");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (!JsonHashes.sha256(bytes).equals(raw.path("byte_sha256").asText())) throw new IOException("role bytes changed");
            JsonNode value = JsonHashes.mapper().readTree(bytes);
            if (!JsonHashes.ownHash(value).equals(raw.path("content_sha256").asText())) throw new IOException("role content changed");
            String rowsHash = value.isArray() ? JsonHashes.canonicalSha256(value) : null;
            if (raw.has("rows_sha256") && !raw.path("rows_sha256").asText().equals(rowsHash)) throw new IOException("role rows changed");
            LifecycleTrustService.ReceiptReference ref = new LifecycleTrustService.ReceiptReference(
                    root.relativize(file).toString(), raw.path("content_sha256").asText(),
                    raw.path("byte_sha256").asText(), (long) bytes.length, rowsHash, raw.path("role_schema").asText(null));
            return new Role(value, ref);
        } catch (IOException error) { throw new ExternalPrerequisite("PHYSICAL_INPUT_UNAVAILABLE", name + ": " + error.getMessage()); }
    }

    private static List<ObjectNode> rows(Map<String, Role> roles, String name) {
        Role role = roles.get(name);
        if (role == null || !role.value.isArray()) throw new ExternalPrerequisite("PHYSICAL_INPUT_UNAVAILABLE", name + " role must be an array");
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode row : role.value) {
            if (!row.isObject()) throw new ExternalPrerequisite("PHYSICAL_INPUT_UNAVAILABLE",
                    name + " role contains a non-object row");
            result.add((ObjectNode) row);
        }
        return result;
    }

    /** Derives every setup and matching field from the verified completed 4h bars. */
    /**
     * Derive the frozen setup fields from completed bars.  Package visibility
     * is intentional: the review suite exercises this producer directly with
     * gapped, duplicated, and late dependency fixtures, without opening any
     * future labels or execution receipts.
     */
    static List<ObjectNode> deriveFeatures(List<ObjectNode> rawBars, List<ObjectNode> metadata) {
        Map<String, ObjectNode> metadataById = indexMetadata(metadata);
        Map<String, List<ObjectNode>> byAsset = new LinkedHashMap<>();
        for (ObjectNode bar : rawBars) {
            String asset = bar.path("asset").asText().toLowerCase();
            if (asset.isBlank()) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "signal bar lacks asset");
            String event = firstTime(bar, "event_time", "open_time");
            if (event.isBlank()) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "signal bar lacks event_time");
            ObjectNode copy = bar.deepCopy();
            copy.put("__event_ms", parseTime(event));
            byAsset.computeIfAbsent(asset, ignored -> new ArrayList<>()).add(copy);
        }
        List<ObjectNode> result = new ArrayList<>();
        for (Map.Entry<String, List<ObjectNode>> entry : byAsset.entrySet()) {
            List<ObjectNode> bars = entry.getValue();
            bars.sort(Comparator.comparingLong(row -> row.path("__event_ms").asLong()));
            long priorEvent = Long.MIN_VALUE;
            for (ObjectNode bar : bars) {
                long event = bar.path("__event_ms").asLong();
                if (priorEvent != Long.MIN_VALUE && event - priorEvent != FOUR_HOURS_MS) {
                    throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                            "signal bars are duplicated or not a complete 4h grid for " + entry.getKey());
                }
                if (event == priorEvent) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                        "signal bars contain duplicate event_time for " + entry.getKey());
                priorEvent = event;
            }
            for (int i = 31; i < bars.size(); i++) {
                ObjectNode current = bars.get(i), prior = bars.get(i - 1);
                String decisionText = firstTime(current, "decision_time");
                if (decisionText.isBlank()) decisionText = Instant.ofEpochMilli(
                        current.path("__event_ms").asLong() + FOUR_HOURS_MS).toString();
                long decision = parseTime(decisionText);
                long expectedDecision = current.path("__event_ms").asLong() + FOUR_HOURS_MS;
                if (decision != expectedDecision || decision % FOUR_HOURS_MS != 0) {
                    throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                            "signal bar decision boundary is not its completed close: " + entry.getKey());
                }
                // The matching return and oldest denominator use i-31.  It
                // is therefore a PIT dependency even though the rolling
                // volume loop below starts at i-30.
                for (int j = i - 31; j <= i; j++) {
                    ObjectNode dependency = bars.get(j);
                    long dependencyEvent = dependency.path("__event_ms").asLong();
                    long dependencyClose = parseTime(firstTime(dependency, "close_time"));
                    long dependencyAvailable = parseTime(firstTime(dependency, "availability_time"));
                    if (dependencyClose > decision || dependencyAvailable > decision) {
                        throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                                "setup dependency is unavailable at decision boundary: " + entry.getKey());
                    }
                    if (dependencyClose < dependencyEvent || dependencyAvailable < dependencyClose) {
                        throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE",
                                "setup dependency chronology is invalid: " + entry.getKey());
                    }
                }
                long available = parseTime(firstTime(current, "availability_time"));
                double close = current.path("close").asDouble(Double.NaN);
                double previousClose = prior.path("close").asDouble(Double.NaN);
                double volume = current.path("volume").asDouble(Double.NaN);
                if (!(close > 0) || !(previousClose > 0) || !(volume >= 0)) {
                    throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "signal bar has invalid OHLCV");
                }
                double volumeSum = 0, volumeSquare = 0, logSquare = 0;
                for (int j = i - 30; j < i; j++) {
                    double v = bars.get(j).path("volume").asDouble(Double.NaN);
                    if (!Double.isFinite(v) || v < 0) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "prior volume is invalid");
                    volumeSum += v; volumeSquare += v * v;
                    double c = bars.get(j).path("close").asDouble(Double.NaN);
                    double p = bars.get(j - 1).path("close").asDouble(Double.NaN);
                    if (!(c > 0) || !(p > 0)) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "prior close is invalid");
                    double log = Math.log(c / p); logSquare += log * log;
                }
                double mean = volumeSum / 30D;
                double variance = Math.max(0, volumeSquare / 30D - mean * mean);
                double priorVolumeSum = 0, priorVolumeSquare = 0;
                for (int j = i - 31; j < i - 1; j++) {
                    double v = bars.get(j).path("volume").asDouble(Double.NaN);
                    if (!Double.isFinite(v) || v < 0) throw new ExternalPrerequisite("PIT_SETUP_UNAVAILABLE", "prior matching volume is invalid");
                    priorVolumeSum += v; priorVolumeSquare += v * v;
                }
                double priorVolumeMean = priorVolumeSum / 30D;
                double priorVolumeVariance = Math.max(0,
                        priorVolumeSquare / 30D - priorVolumeMean * priorVolumeMean);
                double priorVolume = bars.get(i - 1).path("volume").asDouble(Double.NaN);
                ObjectNode feature = JsonHashes.mapper().createObjectNode()
                        .put("episode_id", entry.getKey() + ":" + decisionText)
                        .put("signal_id", entry.getKey() + ":" + decisionText)
                        .put("asset", entry.getKey()).put("venue", "BINANCE")
                        .put("instrument", "BINANCE_SPOT").put("symbol", current.path("symbol").asText())
                        .put("decision_time", decisionText).put("event_time", decisionText)
                        .put("availability_time", Instant.ofEpochMilli(available).toString())
                        .put("setup_bar_count", i + 1).put("signal_eligible", true)
                        .put("shock_return", close / previousClose - 1D)
                        .put("completed_return", close / previousClose - 1D)
                        .put("volume_multiple", mean > 0 ? volume / mean : Double.NaN)
                        .put("realized_volatility", Math.sqrt(logSquare / 30D) * Math.sqrt(6D))
                        .put("prior_30_bar_return", bars.get(i - 1).path("close").asDouble() /
                                bars.get(i - 31).path("close").asDouble() - 1D)
                        .put("prior_30_bar_realized_volatility", Math.sqrt(logSquare / 30D) * Math.sqrt(6D))
                        .put("prior_30_bar_volume_zscore", priorVolumeVariance > 0
                                ? (priorVolume - priorVolumeMean) / Math.sqrt(priorVolumeVariance) : 0D)
                        .put("hour_of_day", ZonedDateTime.ofInstant(Instant.ofEpochMilli(decision), ZoneOffset.UTC).getHour())
                        .put("day_of_week", ZonedDateTime.ofInstant(Instant.ofEpochMilli(decision), ZoneOffset.UTC).getDayOfWeek().getValue());
                ObjectNode metadataRow = metadataById.get(feature.path("episode_id").asText());
                if (metadataRow != null) {
                    metadataRow.fields().forEachRemaining(field -> {
                        if (!FUTURE_FIELDS.contains(field.getKey()) && !feature.has(field.getKey())) feature.set(field.getKey(), field.getValue().deepCopy());
                    });
                }
                result.add(feature);
            }
        }
        return result;
    }

    private static Map<String, ObjectNode> indexMetadata(List<ObjectNode> rows) {
        Map<String, ObjectNode> result = new HashMap<>();
        for (ObjectNode row : rows) result.put(id(row), row);
        return result;
    }

    private static String firstTime(ObjectNode row, String... keys) {
        for (String key : keys) if (row.has(key) && !row.path(key).asText().isBlank()) return row.path(key).asText();
        return "";
    }

    private static ObjectNode readContract(Path path, String label, String schema) {
        ObjectNode value = readObject(path, label);
        if (!schema.equals(value.path("schema").asText())) throw new IllegalArgumentException(label + " schema is not " + schema);
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
        if (!value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))) throw new IllegalArgumentException(label + " content hash is invalid");
        return value;
    }

    private static ObjectNode readObject(Path path, String label) {
        try { JsonNode value = JsonHashes.mapper().readTree(Files.readAllBytes(path));
            if (!value.isObject()) throw new IllegalArgumentException(label + " must be an object");
            return (ObjectNode) value;
        } catch (IOException error) { throw new IllegalArgumentException("cannot read " + label + ": " + error.getMessage(), error); }
    }

    private static ObjectNode withHash(ObjectNode value) { ObjectNode copy = value.deepCopy(); copy.put("content_sha256", JsonHashes.ownHash(copy)); return copy; }
    private static String semanticHash(ObjectNode value) { ObjectNode copy = value.deepCopy(); copy.remove("content_sha256"); copy.remove("build_identity"); copy.remove("economic_semantic_sha256"); copy.remove("semantic_sha256"); return JsonHashes.canonicalSha256(copy); }
    private static String attemptIdentity(String baseline, String controls, String experiment, String physical) {
        return attemptIdentity(baseline, controls, experiment, physical, "");
    }
    private static String attemptIdentity(String baseline, String controls, String experiment, String physical,
            String executorIdentity) {
        return attemptIdentity(baseline, controls, experiment, physical, executorIdentity, "FIXED_BASELINE", "");
    }
    private static String attemptIdentity(String baseline, String controls, String experiment, String physical,
            String executorIdentity, String stage, String refinementMember) {
        ObjectNode identity = JsonHashes.mapper().createObjectNode().put("stage", stage)
                .put("baseline_sha256", baseline).put("control_spec_sha256", controls)
                .put("experiment_sha256", experiment).put("physical_input_sha256", physical);
        if (!executorIdentity.isBlank()) identity.put("executor_identity_sha256", executorIdentity);
        if (!refinementMember.isBlank()) identity.put("refinement_member_id", refinementMember);
        return JsonHashes.canonicalSha256(identity);
    }
    private static String behaviorDefinitionAlias(String baseline, String refinementMember,
            double refinementThreshold) {
        if (refinementMember == null || refinementMember.isBlank()) return baseline;
        // R1 is exactly the frozen baseline parameterization.  The stricter
        // members bind their effective numerical rule, rather than an
        // arbitrary caller label, into the exposure alias.
        if (Double.compare(refinementThreshold, -0.08D) == 0) return baseline;
        ObjectNode alias = JsonHashes.mapper().createObjectNode().put("baseline_sha256", baseline)
                .put("stage", "FROZEN_REFINEMENT")
                .put("shock_threshold", refinementThreshold)
                .put("volume_multiple_threshold", 2.0D)
                .put("volatility_threshold", 0.015D);
        return JsonHashes.canonicalSha256(alias);
    }
    private static String currentExecutorIdentitySha256() {
        ObjectNode identity = BuildIdentityService.describe(StrategyFixedBaselineV5.class);
        if (!"KNOWN".equals(identity.path("status").asText())
                || !"JAR".equals(identity.path("executable").path("kind").asText())
                || !identity.path("executable").path("sha256").asText("").matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("packaged executor identity is unavailable");
        }
        return identity.path("executable").path("sha256").asText();
    }
    private static String economicSemanticHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove("content_sha256"); copy.remove("semantic_sha256"); copy.remove("economic_semantic_sha256");
        copy.remove("build_identity"); copy.remove("exposure_head_sha256"); copy.remove("exposure_head_path");
        copy.remove("executor_identity_sha256"); copy.remove("attempt_identity_sha256");
        // Exposure lineage, timestamps, and retained custody paths are audit
        // metadata. They must not make an independently recomputed economic
        // result differ after an idempotent attempt is accounted.
        copy.remove("legacy_exposure_migration");
        stripCustodyPaths(copy);
        return JsonHashes.canonicalSha256(copy);
    }
    private static void stripCustodyPaths(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("path"); object.remove("physical_root_reference");
            List<String> names = new ArrayList<>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) stripCustodyPaths(object.get(name));
        } else if (node instanceof ArrayNode array) {
            array.forEach(StrategyFixedBaselineV5::stripCustodyPaths);
        }
    }
    private static Map<String, ObjectNode> index(List<ObjectNode> rows, String role) { Map<String, ObjectNode> result = new LinkedHashMap<>(); for (ObjectNode row : rows) { String id = id(row); if (result.putIfAbsent(id, row) != null) throw new IllegalArgumentException("duplicate " + role + " episode " + id); } return result; }
    private static String firstFailure(ObjectNode a, ObjectNode b) { return a.path("reason").asText(b.path("reason").asText("UNRESOLVED_EXECUTION")); }
    private static String id(JsonNode row) { return row.path("episode_id").asText(row.path("signal_id").asText(row.path("decision_time").asText(""))); }
    private static boolean containsFutureField(ObjectNode row) { for (String key : FUTURE_FIELDS) if (row.has(key)) return true; return false; }
    private static double number(JsonNode row, String key, double fallback) { return row.has(key) && row.path(key).isNumber() ? row.path(key).asDouble() : fallback; }
    private static long parseTime(String value) { try { return Instant.parse(value).toEpochMilli(); } catch (RuntimeException error) { try { return Long.parseLong(value); } catch (RuntimeException ignored) { throw new IllegalArgumentException("invalid timestamp " + value); } } }
    private static Path requiredPath(ObjectNode args, String... keys) { Path path = optionalPath(args, keys); if (path == null) throw new IllegalArgumentException("missing --" + keys[0]); return path; }
    private static Path optionalPath(ObjectNode args, String... keys) { for (String key : keys) if (args.has(key) && args.path(key).isTextual()) return Path.of(args.path(key).asText()).toAbsolutePath().normalize(); return null; }
    record Role(JsonNode value, LifecycleTrustService.ReceiptReference reference) {}
    private record SelectedPair(ObjectNode event, ObjectNode control) {}
    private record LedgerEvent(String time, int kind, ObjectNode trade, ObjectNode exit, double markPrice) {
        LedgerEvent(String time, int kind, ObjectNode trade, ObjectNode exit) {
            this(time, kind, trade, exit, Double.NaN);
        }
    }
    record PhysicalInput(Path root, String rootReference, String contentSha256,
            Map<String, Role> roles, List<ObjectNode> features, List<ObjectNode> labels,
            List<ObjectNode> executions, ObjectNode producerReceipt) {}
    private static final class ExternalPrerequisite extends RuntimeException { final String reason, detail; ExternalPrerequisite(String reason, String detail) { super(detail); this.reason = reason; this.detail = detail == null ? "" : detail; } }
    private static void writeImmutableArray(Path path, ArrayNode value) {
        try {
            Path target = path.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            String encoded = JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!JsonHashes.sha256(Files.readAllBytes(target)).equals(JsonHashes.sha256(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                    throw new IllegalArgumentException("immutable signal-bars role already contains different bytes");
                }
                return;
            }
            Files.writeString(target, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot write immutable signal-bars role: " + error.getMessage(), error);
        }
    }
    private static void writeImmutable(Path path, ObjectNode value) { try { Files.createDirectories(path.toAbsolutePath().normalize().getParent()); if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { ObjectNode old = readObject(path, "existing result"); if (!old.path("content_sha256").asText().equals(value.path("content_sha256").asText())) throw new IllegalArgumentException("immutable result path already contains a different attempt"); return; } Files.writeString(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); } catch (IOException error) { throw new IllegalArgumentException("cannot write fixed baseline result: " + error.getMessage(), error); } }
}
