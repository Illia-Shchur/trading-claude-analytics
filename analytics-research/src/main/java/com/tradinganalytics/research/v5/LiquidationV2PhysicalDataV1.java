package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Physical six-role Parquet boundary for the shorter-history v002 profile. */
public final class LiquidationV2PhysicalDataV1 {
    public static final String PLAN_SCHEMA = "liquidation-v2-experiment-plan/1";
    public static final String MANIFEST_SCHEMA = "liquidation-v2-physical-manifest/1";
    private static final List<String> ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");
    private static final Map<String, String> BINANCE_SYMBOLS = Map.of(
            "BTC", "BTCUSDT", "ETH", "ETHUSDT", "SOL", "SOLUSDT", "AAVE", "AAVEUSDT");
    private static final Map<String, String> COINALYZE_SYMBOLS = Map.of(
            "BTC", "BTCUSDT_PERP.A", "ETH", "ETHUSDT_PERP.A", "SOL", "SOLUSDT_PERP.A", "AAVE", "AAVEUSDT_PERP.A");
    private static final List<String> ROLES = List.of("feature", "label", "execution", "mark", "funding", "metadata");
    private static final Map<String, List<String>> ROLE_FIELDS = Map.of(
            "feature", List.of("asset", "symbol", "series_id", "side", "timeframe", "event_time", "availability_time", "open", "high", "low", "close", "base_volume", "value"),
            "label", List.of("asset", "episode_id", "decision_time", "label", "outcome"),
            "execution", List.of("asset", "open_time", "open", "high", "low", "close", "base_volume"),
            "mark", List.of("asset", "timestamp", "open", "high", "low", "close"),
            "funding", List.of("asset", "settlement_time", "event_id", "funding_rate", "mark_price", "funding_interval_hours"),
            "metadata", List.of("asset", "tier_index", "effective_from", "effective_until", "lot_size", "minimum_notional", "taker_fee_rate", "slippage_rate", "liquidation_fee_rate", "tier_notional_cap", "maintenance_margin_rate", "maintenance_deduction", "terminal_tier"));
    private static final Set<String> FEATURE_FIELDS = Set.of("asset", "symbol", "series_id", "side", "timeframe",
            "event_time", "availability_time", "open", "high", "low", "close", "base_volume", "value");
    private static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ROLE_READ_ROWS = 1_000_000;
    private static final long FUNDING_SLOT_TOLERANCE_MILLIS = 15_000L;
    private static final String DAILY_GAP_AUDIT_SHA256 = "f0acba10a0a28b738046af8ce445e75681150a8af9b4d0fc1d550243dab883d0";
    private static final List<LocalDate> FROZEN_AAVE_DAILY_GAPS = List.of(
            LocalDate.parse("2022-10-12"), LocalDate.parse("2022-12-09"), LocalDate.parse("2022-12-10"),
            LocalDate.parse("2022-12-18"), LocalDate.parse("2022-12-24"), LocalDate.parse("2022-12-31"),
            LocalDate.parse("2023-05-13"));
    private static final String NORMALIZER_COPY_ID = "liquidation-v2-role-jsonl-copy/1";
    private static final String NORMALIZER_OI_BASE_ID = "liquidation-v2-binance-oi-base-map/1";
    private static final String NORMALIZER_COPY_SHA = JsonHashes.sha256("liquidation-v2-role-jsonl-copy/1|strict-jsonl|frozen-role-schema|canonical-fields|sort-by-identity|explicit-null-columns");
    private static final String NORMALIZER_OI_BASE_SHA = JsonHashes.sha256("liquidation-v2-binance-oi-base-map/1|strict-jsonl|source.sum_open_interest-to-feature.value|reject-usd-sum_open_interest_value|frozen-feature-schema");
    public static final String MACRO_AVAILABILITY_BASIS = "MODELED_NEXT_COMPLETED_NYSE_SESSION";
    public static final String SYNTHETIC_MACRO_AVAILABILITY_BASIS = "SYNTHETIC_NEXT_COMPLETED_NYSE_SESSION";

    private LiquidationV2PhysicalDataV1() {}

    /** Frozen execution identity bound into every physical normalization receipt. */
    public static ObjectNode frozenPhysicalIdentity() {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("venue", "BINANCE")
                .put("market", "USD_M").put("contract", "LINEAR_PERPETUAL")
                .put("quote_asset", "USDT").put("collateral", "USDT").put("margin_mode", "ISOLATED");
        ArrayNode instruments = value.putArray("instruments");
        for (String asset : ASSETS) instruments.addObject().put("asset", asset)
                .put("binance_symbol", BINANCE_SYMBOLS.get(asset)).put("coinalyze_symbol", COINALYZE_SYMBOLS.get(asset));
        value.putObject("context_identity").put("asset", "SP500").put("symbol", "SP500")
                .put("context_only", true).put("tradable", false);
        return value;
    }

    /** Builds the only currently recomputable normalization receipt from an already normalized role JSONL file. */
    public static ObjectNode createRoleJsonlCopyReceipt(String role, String normalizedPath, byte[] normalizedBytes,
            String sourcePath, byte[] sourceBytes, String windowStart, String windowEndExclusive, String sourceClass) {
        if (!ROLES.contains(role)) throw failure("unsupported v002 normalization role " + role);
        List<ObjectNode> normalizedRows = parseJsonl(normalizedBytes, role);
        validateRows(role, normalizedRows);
        List<ObjectNode> canonicalRows = normalizeRows(role, sortRows(role, normalizedRows));
        if (!java.util.Arrays.equals(toJsonl(canonicalRows), normalizedBytes)) throw failure("normalized JSONL must use canonical v002 role serialization");
        long start = epochMillis(windowStart, role + " window_start"), end = epochMillis(windowEndExclusive, role + " window_end_exclusive");
        if (end <= start) throw failure("normalization window must have positive duration");
        validatePartitionWindow(role, normalizedRows, start, end);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-normalization-receipt/1").put("version", 1)
                .put("role", role).put("normalized_path", normalizedPath)
                .put("normalized_byte_sha256", JsonHashes.sha256(normalizedBytes))
                .put("source_path", sourcePath).put("source_byte_sha256", JsonHashes.sha256(sourceBytes))
                .put("row_count", normalizedRows.size()).put("window_start", Instant.ofEpochMilli(start).toString())
                .put("window_end_exclusive", Instant.ofEpochMilli(end).toString())
                .put("normalizer_id", NORMALIZER_COPY_ID).put("normalizer_sha256", NORMALIZER_COPY_SHA)
                .put("source_class", sourceClass).put("verified", false).put("authoritative", false)
                .put("historical_availability_proven", false).put("historical_revision_proven", false);
        ArrayNode seriesScope = seriesScope(role, normalizedRows);
        receipt.set("series_scope", seriesScope);
        receipt.put("availability_basis", availabilityBasis(role, seriesScope, sourceClass));
        ObjectNode identity = frozenPhysicalIdentity();
        receipt.set("physical_identity", identity);
        receipt.put("physical_identity_sha256", JsonHashes.canonicalSha256(identity));
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    /** Canonical schema serialization shared by fixture builders and physical role importers. */
    public static byte[] canonicalRoleRowsJsonl(String role, List<ObjectNode> rows) {
        validateRows(role, rows);
        return toJsonl(normalizeRows(role, sortRows(role, rows)));
    }

    /**
     * Returns true only when a previously reopened physical feature artifact binds this
     * S&P close timestamp to the frozen next-session availability assumption. This is
     * a modeled development receipt, never point-in-time proof.
     */
    public static boolean macroAvailabilityReceiptBound(VerifiedDevelopmentSession session, long closeEpochMillis) {
        ObjectNode manifest = session.manifest();
        if (!JsonHashes.ownHash(manifest).equals(manifest.path("content_sha256").asText())) return false;
        ObjectNode feature = (ObjectNode) manifest.path("artifacts").path("feature");
        if ("SYNTHETIC_DEVELOPMENT_ONLY".equals(manifest.path("source_mode").asText())) {
            ObjectNode plan = (ObjectNode) manifest.path("plan");
            long start = epochMillis(requiredText(plan, "source_start"), "synthetic macro source start");
            long end = epochMillis(requiredText(plan, "execution_end_exclusive"), "synthetic macro execution end");
            return containsSeries(feature.path("series_scope"), "sp500_close")
                    && SYNTHETIC_MACRO_AVAILABILITY_BASIS.equals(feature.path("availability_basis").asText())
                    && closeEpochMillis >= start && closeEpochMillis < end
                    && JsonHashes.isSha256(feature.path("input_byte_sha256").asText());
        }
        if (!"PROXY_DISCLOSED_DEVELOPMENT_ONLY".equals(manifest.path("source_mode").asText())) return false;
        JsonNode partitions = feature.path("partitions");
        if (!partitions.isArray()) return false;
        for (JsonNode partition : partitions) {
            if (!containsSeries(partition.path("series_scope"), "sp500_close")
                    || !MACRO_AVAILABILITY_BASIS.equals(partition.path("availability_basis").asText())
                    || !"PUBLIC_ARCHIVE".equals(partition.path("source_class").asText())
                    || partition.path("normalization_receipt_path").asText().isBlank()
                    || !JsonHashes.isSha256(partition.path("normalization_receipt_byte_sha256").asText())) continue;
            ObjectNode partitionObject = (ObjectNode) partition;
            long start = epochMillis(requiredText(partitionObject, "window_start"), "macro receipt window start");
            long end = epochMillis(requiredText(partitionObject, "window_end_exclusive"), "macro receipt window end");
            if (closeEpochMillis >= start && closeEpochMillis < end) return true;
        }
        return false;
    }

    private static boolean containsSeries(JsonNode scope, String seriesId) {
        if (!scope.isArray()) return false;
        for (JsonNode series : scope) if (seriesId.equals(series.asText())) return true;
        return false;
    }

    /** The plan is generated from the frozen v002 profile; no horizon or scope knobs are exposed. */
    public static ObjectNode frozenPlan(ObjectNode profile) {
        LiquidationDailyStressProfileV1.validate(profile);
        ObjectNode plan = JsonHashes.mapper().createObjectNode().put("schema", PLAN_SCHEMA).put("version", 1)
                .put("status", "FROZEN").put("profile_sha256", profile.path("content_sha256").asText())
                .put("precommit_sha256", profile.path("precommit_content_sha256").asText())
                .put("source_start", "2022-08-11T00:00:00Z").put("decision_start", "2022-11-11T00:00:00Z")
                .put("decision_end_exclusive", "2026-07-15T00:00:00Z")
                .put("execution_end_exclusive", "2026-09-20T00:00:00Z")
                .put("max_lifecycle_days", 60).put("max_setup_wait_days", 6)
                .put("daily_percentile_warmup_days", 90).put("post_decision_hydration_days", 67)
                .put("minimum_contiguous_hydration_days", 157).put("minimum_outer_purge_days", 67)
                .put("minimum_inner_purge_days", 67).put("embargo_days", 7)
                .put("actual_outcome_overlap_removal", true).put("envelope_frozen_before_outcome_read", true)
                .put("funding_timestamp_offset_tolerance_seconds", FUNDING_SLOT_TOLERANCE_MILLIS / 1_000L)
                .put("funding_interval_contract", "PRESERVE_EACH_EVENT_INTERVAL_AND_ACTUAL_SETTLEMENT_TIME")
                .put("feature_gap_policy", "BLOCK_AFFECTED_DAILY_LOOKBACK_WINDOWS_NO_FILL")
                .put("daily_feature_event_offset_from_decision_days", 2)
                .put("daily_gap_audit_schema", "daily-stress-diagnostic-summary/1")
                .put("daily_gap_audit_sha256", DAILY_GAP_AUDIT_SHA256)
                .put("initial_opportunity_end_exclusive", "2026-07-15T00:00:00Z")
                .put("lifecycle_addition_and_4h_trailing_end_exclusive", "2026-09-20T00:00:00Z")
                .put("all_mechanically_eligible_windows_required", true)
                .put("candidate_count_frozen", true).put("outcome_selection_or_optimization", false)
                .put("authoritative_evaluation_permitted", false);
        plan.set("assets", JsonHashes.mapper().valueToTree(ASSETS));
        plan.set("daily_feature_gap_exclusions", dailyFeatureGapExclusions());
        plan.set("core_variants", profile.path("core_variants").deepCopy());
        plan.set("deadline_exit_execution", profile.path("lifecycle_contract").path("deadline_exit_execution").deepCopy());
        plan.set("physical_roles", JsonHashes.mapper().valueToTree(ROLES));
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        return plan;
    }

    /** Converts hash-bound development inputs to separate physical roles; no input can claim VERIFIED source status. */
    public static ObjectNode buildSyntheticDevelopment(ObjectNode options) {
        ObjectNode profile = object(options, "profile");
        LiquidationDailyStressProfileV1.validate(profile);
        Path root = Path.of(requiredText(options, "root")).toAbsolutePath().normalize();
        requireDirectory(root, "v002 data root");
        ObjectNode inputs = object(options, "inputs");
        ObjectNode plan = frozenPlan(profile);
        ObjectNode artifacts = JsonHashes.mapper().createObjectNode();
        Set<String> uniqueOutputPaths = new HashSet<>();
        for (String role : ROLES) {
            ObjectNode reference = object(inputs, role);
            String inputPath = requiredText(reference, "path");
            byte[] bytes = PathConfinement.readSinglyLinkedFile(root, inputPath, role + " JSONL input");
            if (bytes.length > MAX_INPUT_BYTES) throw failure(role + " JSONL input exceeds the bounded size");
            String byteSha = JsonHashes.sha256(bytes);
            if (!byteSha.equals(requiredText(reference, "sha256"))) throw failure(role + " JSONL input hash mismatch");
            List<ObjectNode> rows = parseJsonl(bytes, role);
            validateRows(role, rows);
            List<ObjectNode> normalizedRows = normalizeRows(role, sortRows(role, rows));
            String relativeOutput = "parquet/liquidation-v2/" + role + "-" + byteSha + ".parquet";
            if (!uniqueOutputPaths.add(relativeOutput)) throw failure("v002 Parquet output path is reused");
            Path output = root.resolve(relativeOutput);
            safeDirectories(root, "parquet/liquidation-v2");
            try { Files.createDirectories(output.getParent()); }
            catch (IOException error) { throw failure("cannot create v002 Parquet output directory: " + error.getMessage()); }
            byte[] stagingBytes = toJsonl(normalizedRows);
            String stagedSha = JsonHashes.sha256(stagingBytes);
            Path staging = root.resolve("staging/liquidation-v2/" + role + "-" + stagedSha + ".jsonl");
            try {
                safeDirectories(root, "staging/liquidation-v2");
                Files.createDirectories(staging.getParent());
                if (Files.exists(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    PathConfinement.resolve(root, "staging/liquidation-v2/" + staging.getFileName(), "v002 staged JSONL", PathConfinement.ExpectedType.FILE);
                    if (!JsonHashes.sha256(staging).equals(stagedSha)) throw failure("immutable v002 staging path already contains different bytes");
                }
                if (!Files.exists(staging)) Files.write(staging, stagingBytes);
            } catch (IOException error) { throw failure("cannot persist v002 staging bytes: " + error.getMessage()); }
            if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.resolve(root, relativeOutput, "v002 Parquet output", PathConfinement.ExpectedType.FILE);
                if (!JsonHashes.sha256(output).equals(JsonHashes.sha256(staging))) {
                    // Existing role output is immutable; a different file at a content-addressed target is rejected.
                    if (!ResearchData.queryParquet(output).stream().map(JsonHashes::canonicalSha256).toList()
                            .equals(rows.stream().map(JsonHashes::canonicalSha256).toList())) throw failure("immutable v002 Parquet path contains different rows");
                }
            }
            if (!Files.exists(output)) ResearchData.writeParquet(staging, output);
            List<ObjectNode> reopened = ResearchData.queryParquet(output);
            List<ObjectNode> normalizedReopened = normalizeRows(role, sortRows(role, reopened));
            if (reopened.size() != rows.size() || !JsonHashes.canonicalSha256(normalizedReopened).equals(JsonHashes.canonicalSha256(normalizedRows))) {
                throw failure("DuckDB Parquet round trip changed " + role + " role rows");
            }
            ObjectNode artifact = JsonHashes.mapper().createObjectNode().put("role", role.toUpperCase(Locale.ROOT))
                    .put("input_path", inputPath).put("input_byte_sha256", byteSha)
                    .put("input_content_sha256", JsonHashes.canonicalSha256(normalizedRows))
                    .put("path", relativeOutput).put("format", "PARQUET")
                    .put("storage_role", "DEVELOPMENT_SYNTHETIC").put("authoritative", false)
                    .put("sha256", JsonHashes.sha256(output)).put("bytes", FilesSize(output))
                    .put("row_count", reopened.size()).put("rows_sha256", JsonHashes.canonicalSha256(normalizedReopened))
                    .put("schema_sha256", parquetSchemaSha(output));
            ArrayNode scope = seriesScope(role, normalizedRows);
            artifact.set("series_scope", scope);
            artifact.put("availability_basis", availabilityBasis(role, scope, "SYNTHETIC_FIXTURE"));
            artifacts.set(role, artifact);
        }
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", MANIFEST_SCHEMA).put("version", 1)
                .put("status", "DEVELOPMENT_SYNTHETIC").put("format", "PARQUET")
                .put("storage_role", "DEVELOPMENT_SYNTHETIC").put("authoritative", false)
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("precommit_sha256", profile.path("precommit_content_sha256").asText())
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("source_qualification_permitted", false)
                .put("authoritative_evaluation_permitted", false)
                .put("development_replay_permitted", false)
                .put("coverage_scope", "SYNTHETIC_FIXTURE_ONLY")
                .put("full_historical_hydration_claimed", false)
                .put("signal_reader_role", "feature")
                .put("outcome_roles_signal_readable", false);
        value.set("plan", plan); value.set("artifacts", artifacts);
        ObjectNode datasetIdentity = JsonHashes.mapper().createObjectNode();
        for (String role : ROLES) datasetIdentity.set(role, artifacts.path(role).deepCopy());
        value.put("dataset_root_sha256", JsonHashes.canonicalSha256(datasetIdentity));
        value.put("content_sha256", JsonHashes.ownHash(value));
        return value;
    }

    /** Reopens every staged input and every DuckDB Parquet role; rejects role, plan, or byte tampering. */
    public static ObjectNode verifySyntheticDevelopment(ObjectNode options) {
        ObjectNode profile = object(options, "profile"); ObjectNode manifest = object(options, "manifest");
        LiquidationDailyStressProfileV1.validate(profile);
        if (!MANIFEST_SCHEMA.equals(manifest.path("schema").asText()) || manifest.path("version").asInt(-1) != 1
                || !"DEVELOPMENT_SYNTHETIC".equals(manifest.path("status").asText())
                || !"SYNTHETIC_DEVELOPMENT_ONLY".equals(manifest.path("source_mode").asText())
                || !"SYNTHETIC_FIXTURE_ONLY".equals(manifest.path("coverage_scope").asText())
                || manifest.path("full_historical_hydration_claimed").asBoolean(true)
                || manifest.path("authoritative").asBoolean(true)
                || manifest.path("authoritative_evaluation_permitted").asBoolean(true)
                || manifest.path("development_replay_permitted").asBoolean(true)
                || !profile.path("content_sha256").asText().equals(manifest.path("profile_sha256").asText())
                || !profile.path("precommit_content_sha256").asText().equals(manifest.path("precommit_sha256").asText())
                || !JsonHashes.ownHash(manifest).equals(manifest.path("content_sha256").asText())) {
            throw failure("unsupported, modified, or falsely authoritative v002 physical manifest");
        }
        ObjectNode expectedPlan = frozenPlan(profile);
        if (!JsonHashes.canonicalSha256(expectedPlan).equals(JsonHashes.canonicalSha256(manifest.path("plan")))) {
            throw failure("v002 plan is not the frozen 60-day/67-day/7-day capability-bound plan");
        }
        if (!rolesExactly(manifest.path("artifacts"))) throw failure("v002 role set must be complete and exact");
        Path root = Path.of(requiredText(options, "root")).toAbsolutePath().normalize();
        ObjectNode datasetIdentity = JsonHashes.mapper().createObjectNode();
        Set<String> rolePaths = new HashSet<>();
        for (String role : ROLES) {
            ObjectNode artifact = (ObjectNode) manifest.path("artifacts").path(role);
            String inputPath = requiredText(artifact, "input_path"); String outputPath = requiredText(artifact, "path");
            if (!rolePaths.add(outputPath)) throw failure("v002 role paths are not distinct");
            if (!role.toUpperCase(Locale.ROOT).equals(artifact.path("role").asText())
                    || !"PARQUET".equals(artifact.path("format").asText())
                    || !"DEVELOPMENT_SYNTHETIC".equals(artifact.path("storage_role").asText())
                    || artifact.path("authoritative").asBoolean(true)) throw failure("invalid v002 role metadata: " + role);
            byte[] inputBytes = PathConfinement.readSinglyLinkedFile(root, inputPath, role + " JSONL input");
            if (!JsonHashes.sha256(inputBytes).equals(artifact.path("input_byte_sha256").asText())) throw failure("v002 source input changed: " + role);
            List<ObjectNode> sourceRows = parseJsonl(inputBytes, role); validateRows(role, sourceRows);
            List<ObjectNode> normalizedSourceRows = normalizeRows(role, sortRows(role, sourceRows));
            ArrayNode expectedScope = seriesScope(role, normalizedSourceRows);
            if (!JsonHashes.canonicalSha256(normalizedSourceRows).equals(artifact.path("input_content_sha256").asText())
                    || !JsonHashes.canonicalSha256(expectedScope).equals(JsonHashes.canonicalSha256(artifact.path("series_scope")))
                    || !availabilityBasis(role, expectedScope, "SYNTHETIC_FIXTURE").equals(artifact.path("availability_basis").asText())) {
                throw failure("v002 input rows or receipt-bound availability inventory changed: " + role);
            }
            Path physical = PathConfinement.resolve(root, outputPath, role + " Parquet role", PathConfinement.ExpectedType.FILE).absolute();
            if (!JsonHashes.sha256(physical).equals(artifact.path("sha256").asText())
                    || FilesSize(physical) != artifact.path("bytes").asLong(-1)) throw failure("v002 Parquet bytes changed: " + role);
            List<ObjectNode> reopened = ResearchData.queryParquet(physical);
            List<ObjectNode> normalizedReopened = normalizeRows(role, sortRows(role, reopened));
            if (reopened.size() != artifact.path("row_count").asInt(-1)
                    || !JsonHashes.canonicalSha256(normalizedReopened).equals(artifact.path("rows_sha256").asText())
                    || !JsonHashes.canonicalSha256(normalizedReopened).equals(JsonHashes.canonicalSha256(normalizedSourceRows))
                    || !parquetSchemaSha(physical).equals(artifact.path("schema_sha256").asText())) {
                throw failure("v002 physical role fails source/Parquet reopen: " + role);
            }
            datasetIdentity.set(role, artifact.deepCopy());
        }
        if (!JsonHashes.canonicalSha256(datasetIdentity).equals(manifest.path("dataset_root_sha256").asText())) {
            throw failure("v002 physical dataset root hash mismatch");
        }
        return JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-physical-manifest-verification/1")
                .put("status", "REOPENED_DEVELOPMENT_ONLY").put("manifest_sha256", manifest.path("content_sha256").asText())
                .put("dataset_root_sha256", manifest.path("dataset_root_sha256").asText())
                .put("role_count", ROLES.size()).put("authoritative", false)
                .put("content_sha256", JsonHashes.ownHash(JsonHashes.mapper().createObjectNode()
                        .put("schema", "liquidation-v2-physical-manifest-verification/1")
                        .put("status", "REOPENED_DEVELOPMENT_ONLY").put("manifest_sha256", manifest.path("content_sha256").asText())
                        .put("dataset_root_sha256", manifest.path("dataset_root_sha256").asText())
                        .put("role_count", ROLES.size()).put("authoritative", false)));
    }

    /** Reads only the feature role after physical verification; labels and executions cannot leak into routing. */
    public static List<ObjectNode> readFeatureRows(ObjectNode options) {
        verifySyntheticDevelopment(options);
        ObjectNode manifest = object(options, "manifest"); Path root = Path.of(requiredText(options, "root"));
        String path = requiredText((ObjectNode) manifest.path("artifacts").path("feature"), "path");
        return ResearchData.queryParquet(PathConfinement.resolve(root, path, "v002 FEATURE Parquet role", PathConfinement.ExpectedType.FILE).absolute());
    }

    /**
     * Builds a partitioned, source-reopened development manifest. Each partition is bounded
     * independently, so multi-year 1m data can be stitched by the replay without materializing
     * the complete history in memory. Source labels only select a disclosed development class;
     * this method never accepts or emits VERIFIED/PIT/authoritative evidence.
     *
     * Input role entry shape: {"partitions":[{"path","sha256","source_path",
     * "source_sha256","normalization_receipt_path","normalization_receipt_sha256",
     * "window_start","window_end_exclusive", optional "assumption_path" and
     * "assumption_sha256"}]}. Normalization receipts bind source bytes to normalized JSONL bytes.
     */
    public static ObjectNode buildDevelopment(ObjectNode options) {
        ObjectNode profile = object(options, "profile");
        LiquidationDailyStressProfileV1.validate(profile);
        Path root = Path.of(requiredText(options, "root")).toAbsolutePath().normalize();
        requireDirectory(root, "v002 data root");
        ObjectNode inputs = object(options, "inputs");
        ObjectNode plan = frozenPlan(profile);
        ObjectNode artifacts = JsonHashes.mapper().createObjectNode();
        ArrayNode allGroups = JsonHashes.mapper().createArrayNode();
        Set<String> normalizedPaths = new HashSet<>(), receiptPaths = new HashSet<>(), parquetPaths = new HashSet<>();
        Set<String> sourceClasses = new HashSet<>();
        boolean allTransformationsRecomputed = true;
        int partitionTotal = 0;
        for (String role : ROLES) {
            ObjectNode roleInput = object(inputs, role);
            JsonNode partitionsNode = roleInput.path("partitions");
            if (!partitionsNode.isArray() || partitionsNode.isEmpty()) throw failure("each v002 role requires at least one physical partition: " + role);
            if ((partitionTotal += partitionsNode.size()) > 10_000) throw failure("v002 partition inventory exceeds its bounded limit");
            ArrayNode partitions = JsonHashes.mapper().createArrayNode();
            int partitionIndex = 0;
            for (JsonNode partitionNode : partitionsNode) {
                if (!partitionNode.isObject()) throw failure(role + " partition references must be objects");
                ObjectNode partitionInput = (ObjectNode) partitionNode;
                String normalizedPath = requiredText(partitionInput, "path");
                String sourcePath = requiredText(partitionInput, "source_path");
                String receiptPath = requiredText(partitionInput, "normalization_receipt_path");
                if (normalizedPath.equals(sourcePath) || normalizedPath.equals(receiptPath) || sourcePath.equals(receiptPath)) {
                    throw failure("raw source, normalized JSONL, and normalization receipt must be distinct physical files");
                }
                if (!normalizedPaths.add(normalizedPath) || !receiptPaths.add(receiptPath)) {
                    throw failure("v002 normalized and normalization receipt paths must be globally distinct");
                }
                byte[] normalizedBytes = readBounded(root, normalizedPath, role + " normalized JSONL", MAX_INPUT_BYTES);
                byte[] sourceBytes = readBounded(root, sourcePath, role + " raw source", MAX_INPUT_BYTES);
                byte[] receiptBytes = readBounded(root, receiptPath, role + " normalization receipt", 8L * 1024 * 1024);
                String normalizedSha = JsonHashes.sha256(normalizedBytes), sourceSha = JsonHashes.sha256(sourceBytes);
                if (!normalizedSha.equals(requiredText(partitionInput, "sha256"))
                        || !sourceSha.equals(requiredText(partitionInput, "source_sha256"))) {
                    throw failure(role + " source or normalized partition byte hash mismatch");
                }
                if (!JsonHashes.sha256(receiptBytes).equals(requiredText(partitionInput, "normalization_receipt_sha256"))) {
                    throw failure(role + " normalization receipt byte hash mismatch");
                }
                List<ObjectNode> rows = parseJsonl(normalizedBytes, role);
                validateRows(role, rows);
                List<ObjectNode> normalizedRows = normalizeRows(role, sortRows(role, rows));
                long windowStart = epochMillis(requiredText(partitionInput, "window_start"), role + " partition window_start");
                long windowEnd = epochMillis(requiredText(partitionInput, "window_end_exclusive"), role + " partition window_end_exclusive");
                if (windowEnd <= windowStart) throw failure(role + " partition window must have positive duration");
                validatePartitionWindow(role, rows, windowStart, windowEnd);
                ObjectNode receipt = parseStrictObject(receiptBytes, role + " normalization receipt");
                validateNormalizationReceipt(receipt, role, normalizedPath, normalizedSha, sourcePath, sourceSha,
                        rows.size(), windowStart, windowEnd, rows);
                NormalizationResult derivation = deriveNormalizedRows(role, sourceBytes, receipt);
                if (derivation.recomputed() && !java.util.Arrays.equals(toJsonl(derivation.rows()), normalizedBytes)) {
                    throw failure(role + " normalized rows do not match deterministic derivation from reopened source bytes");
                }
                if (!derivation.recomputed()) allTransformationsRecomputed = false;
                String sourceClass = requiredText(receipt, "source_class");
                validateRolePartitionClass(role, sourceClass, rows);
                sourceClasses.add(sourceClass);

                ObjectNode assumption = null;
                String assumptionPath = null, assumptionSha = null;
                if ("metadata".equals(role)) {
                    assumptionPath = requiredText(partitionInput, "assumption_path");
                    assumptionSha = requiredText(partitionInput, "assumption_sha256");
                    byte[] assumptionBytes = readBounded(root, assumptionPath, "metadata assumption receipt", 8L * 1024 * 1024);
                    if (!JsonHashes.sha256(assumptionBytes).equals(assumptionSha)) throw failure("metadata assumption receipt byte hash mismatch");
                    assumption = parseStrictObject(assumptionBytes, "metadata assumption receipt");
                    validateMetadataAssumption(assumption, sourcePath, sourceSha, windowStart, windowEnd);
                    String assumptionClass = requiredText(assumption, "source_class");
                    sourceClasses.add(assumptionClass);
                } else if (partitionInput.has("assumption_path") || partitionInput.has("assumption_sha256")) {
                    throw failure("metadata assumptions may only bind the metadata role");
                }

                ObjectNode coverage = partitionCoverage(role, normalizedRows);
                String partitionKey = role + "-" + String.format(Locale.ROOT, "%05d", partitionIndex++) + "-" + normalizedSha;
                String outputPath = "parquet/liquidation-v2/partitions/" + partitionKey + ".parquet";
                if (!parquetPaths.add(outputPath)) throw failure("v002 Parquet partition path collision");
                Path output = root.resolve(outputPath);
                safeDirectories(root, "parquet/liquidation-v2/partitions");
                try { Files.createDirectories(output.getParent()); }
                catch (IOException error) { throw failure("cannot create v002 Parquet partition directory: " + error.getMessage()); }
                byte[] normalizedJsonl = toJsonl(normalizedRows);
                String stagedSha = JsonHashes.sha256(normalizedJsonl);
                String stagingPath = "staging/liquidation-v2/partitions/" + partitionKey + "-" + stagedSha + ".jsonl";
                Path staging = root.resolve(stagingPath);
                try {
                    safeDirectories(root, "staging/liquidation-v2/partitions");
                    Files.createDirectories(staging.getParent());
                    writeNewOrCheck(root, stagingPath, staging, normalizedJsonl, "v002 staged partition");
                } catch (IOException error) { throw failure("cannot persist v002 staged partition: " + error.getMessage()); }
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    PathConfinement.resolve(root, outputPath, "v002 Parquet partition", PathConfinement.ExpectedType.FILE);
                    List<ObjectNode> existing = normalizeRows(role, sortRows(role, ResearchData.queryParquet(output)));
                    if (!JsonHashes.canonicalSha256(existing).equals(JsonHashes.canonicalSha256(normalizedRows))) {
                        throw failure("immutable v002 Parquet partition path contains different normalized rows");
                    }
                } else ResearchData.writeParquet(staging, output);
                List<ObjectNode> reopened = ResearchData.queryParquet(output);
                List<ObjectNode> normalizedReopened = normalizeRows(role, sortRows(role, reopened));
                String rowsSha = JsonHashes.canonicalSha256(normalizedRows);
                if (reopened.size() != rows.size() || !JsonHashes.canonicalSha256(normalizedReopened).equals(rowsSha)) {
                    throw failure("DuckDB Parquet round trip changed " + role + " partition rows");
                }
                ObjectNode item = partitions.addObject().put("path", outputPath).put("format", "PARQUET")
                        .put("sha256", JsonHashes.sha256(output)).put("bytes", FilesSize(output))
                        .put("row_count", reopened.size()).put("rows_sha256", rowsSha)
                        .put("schema_sha256", parquetSchemaSha(output))
                        .put("normalized_path", normalizedPath).put("normalized_byte_sha256", normalizedSha)
                        .put("source_path", sourcePath).put("source_byte_sha256", sourceSha)
                        .put("normalization_receipt_path", receiptPath)
                        .put("normalization_receipt_byte_sha256", JsonHashes.sha256(receiptBytes))
                        .put("source_class", sourceClass).put("normalizer_id", receipt.path("normalizer_id").asText())
                        .put("normalizer_sha256", receipt.path("normalizer_sha256").asText())
                        .put("availability_basis", receipt.path("availability_basis").asText())
                        .put("transformation_status", derivation.status())
                        .put("window_start", java.time.Instant.ofEpochMilli(windowStart).toString())
                        .put("window_end_exclusive", java.time.Instant.ofEpochMilli(windowEnd).toString());
                item.set("series_scope", receipt.path("series_scope").deepCopy());
                if (assumption != null) item.put("assumption_path", assumptionPath).put("assumption_byte_sha256", assumptionSha)
                        .put("assumption_content_sha256", requiredText(assumption, "content_sha256"))
                        .put("assumption_source_class", requiredText(assumption, "source_class"));
                item.set("coverage", coverage);
                appendGroups(allGroups, coverage.path("groups"));
            }
            ObjectNode roleArtifact = JsonHashes.mapper().createObjectNode().put("role", role.toUpperCase(Locale.ROOT))
                    .put("format", "PARQUET").put("partition_count", partitions.size());
            roleArtifact.set("partitions", partitions);
            artifacts.set(role, roleArtifact);
        }
        ObjectNode coverage = aggregateCoverage(allGroups);
        coverage = addReadiness(coverage, artifacts, plan);
        boolean hydration = coverage.path("readiness").path("frozen_envelope_covered").asBoolean(false);
        boolean inventoryComplete = coverage.path("readiness").path("coverage_inventory_complete").asBoolean(false);
        boolean replayPermitted = inventoryComplete && allTransformationsRecomputed;
        boolean allSynthetic = sourceClasses.equals(Set.of("SYNTHETIC_FIXTURE"));
        String mode = allSynthetic ? "SYNTHETIC_DEVELOPMENT_ONLY" : "PROXY_DISCLOSED_DEVELOPMENT_ONLY";
        validateSourceMode(mode, sourceClasses);
        validateRoleSourceClasses(mode, artifacts);
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", MANIFEST_SCHEMA).put("version", 1)
                .put("status", allSynthetic ? "DEVELOPMENT_SYNTHETIC" : "DEVELOPMENT_PROXY_DISCLOSED")
                .put("format", "PARQUET").put("storage_role", mode).put("authoritative", false)
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("precommit_sha256", profile.path("precommit_content_sha256").asText())
                .put("source_mode", mode).put("source_qualification_permitted", false)
                .put("authoritative_evaluation_permitted", false)
                .put("coverage_scope", "BOUNDED_PHYSICAL_PARTITIONS")
                .put("full_historical_hydration_claimed", false)
                .put("minimum_contiguous_hydration_proven", hydration)
                .put("coverage_inventory_complete", inventoryComplete)
                .put("all_source_transformations_recomputed", allTransformationsRecomputed)
                .put("development_replay_permitted", replayPermitted)
                .put("signal_reader_role", "feature").put("outcome_roles_signal_readable", false);
        value.set("plan", plan); value.set("artifacts", artifacts); value.set("coverage", coverage);
        value.put("source_receipt_count", partitionTotal);
        ObjectNode datasetIdentity = JsonHashes.mapper().createObjectNode();
        for (String role : ROLES) datasetIdentity.set(role, artifacts.path(role).deepCopy());
        value.put("dataset_root_sha256", JsonHashes.canonicalSha256(datasetIdentity));
        value.put("content_sha256", JsonHashes.ownHash(value));
        return value;
    }

    /** Reopens source, normalized bytes, assumption receipts, every Parquet partition and the derived coverage inventory. */
    public static ObjectNode verifyDevelopment(ObjectNode options) {
        ObjectNode profile = object(options, "profile"), manifest = object(options, "manifest");
        LiquidationDailyStressProfileV1.validate(profile);
        if (!MANIFEST_SCHEMA.equals(manifest.path("schema").asText()) || manifest.path("version").asInt(-1) != 1
                || !Set.of("DEVELOPMENT_SYNTHETIC", "DEVELOPMENT_PROXY_DISCLOSED").contains(manifest.path("status").asText())
                || manifest.path("authoritative").asBoolean(true)
                || manifest.path("source_qualification_permitted").asBoolean(true)
                || manifest.path("authoritative_evaluation_permitted").asBoolean(true)
                || manifest.path("full_historical_hydration_claimed").asBoolean(true)
                || !profile.path("content_sha256").asText().equals(manifest.path("profile_sha256").asText())
                || !profile.path("precommit_content_sha256").asText().equals(manifest.path("precommit_sha256").asText())
                || !JsonHashes.ownHash(manifest).equals(manifest.path("content_sha256").asText())) {
            throw failure("unsupported, modified, or falsely authoritative v002 development manifest");
        }
        ObjectNode plan = frozenPlan(profile);
        if (!plan.path("content_sha256").asText().equals(manifest.path("plan_sha256").asText())
                || !plan.path("content_sha256").asText().equals(manifest.path("plan").path("content_sha256").asText())
                || !JsonHashes.canonicalSha256(plan).equals(JsonHashes.canonicalSha256(manifest.path("plan")))) {
            throw failure("v002 development manifest is not bound to the frozen physical plan");
        }
        ObjectNode artifacts = object(manifest, "artifacts");
        if (!rolesExactly(artifacts)) throw failure("v002 development manifest must have all six physical roles");
        Path root = Path.of(requiredText(options, "root")).toAbsolutePath().normalize();
        ObjectNode datasetIdentity = JsonHashes.mapper().createObjectNode(); ArrayNode groups = JsonHashes.mapper().createArrayNode();
        Set<String> normalizedPaths = new HashSet<>(), receiptPaths = new HashSet<>(), parquetPaths = new HashSet<>();
        Set<String> sourceClasses = new HashSet<>(); int count = 0; boolean allTransformationsRecomputed = true;
        for (String role : ROLES) {
            ObjectNode roleArtifact = object(artifacts, role);
            JsonNode partitions = roleArtifact.path("partitions");
            if (!role.toUpperCase(Locale.ROOT).equals(roleArtifact.path("role").asText())
                    || !"PARQUET".equals(roleArtifact.path("format").asText()) || !partitions.isArray() || partitions.isEmpty()
                    || roleArtifact.path("partition_count").asInt(-1) != partitions.size()) throw failure("invalid v002 role partition inventory: " + role);
            for (JsonNode entryNode : partitions) {
                ObjectNode entry = (ObjectNode) entryNode;
                if (++count > 10_000) throw failure("v002 partition inventory exceeds its bounded limit");
                String outputPath = requiredText(entry, "path"), normalizedPath = requiredText(entry, "normalized_path");
                String sourcePath = requiredText(entry, "source_path"), receiptPath = requiredText(entry, "normalization_receipt_path");
                if (normalizedPath.equals(sourcePath) || normalizedPath.equals(receiptPath) || sourcePath.equals(receiptPath)
                        || !parquetPaths.add(outputPath) || !normalizedPaths.add(normalizedPath)
                        || !receiptPaths.add(receiptPath)) throw failure("v002 physical partition paths are not distinct");
                byte[] normalizedBytes = readBounded(root, normalizedPath, role + " normalized JSONL", MAX_INPUT_BYTES);
                byte[] sourceBytes = readBounded(root, sourcePath, role + " raw source", MAX_INPUT_BYTES);
                byte[] receiptBytes = readBounded(root, receiptPath, role + " normalization receipt", 8L * 1024 * 1024);
                if (!JsonHashes.sha256(normalizedBytes).equals(entry.path("normalized_byte_sha256").asText())
                        || !JsonHashes.sha256(sourceBytes).equals(entry.path("source_byte_sha256").asText())
                        || !JsonHashes.sha256(receiptBytes).equals(entry.path("normalization_receipt_byte_sha256").asText())) {
                    throw failure(role + " source, normalized, or normalization receipt bytes changed");
                }
                List<ObjectNode> sourceRows = parseJsonl(normalizedBytes, role); validateRows(role, sourceRows);
                List<ObjectNode> canonicalSourceRows = normalizeRows(role, sortRows(role, sourceRows));
                long windowStart = epochMillis(requiredText(entry, "window_start"), role + " partition window_start");
                long windowEnd = epochMillis(requiredText(entry, "window_end_exclusive"), role + " partition window_end_exclusive");
                if (windowEnd <= windowStart) throw failure(role + " partition window must have positive duration");
                validatePartitionWindow(role, sourceRows, windowStart, windowEnd);
                ObjectNode receipt = parseStrictObject(receiptBytes, role + " normalization receipt");
                validateNormalizationReceipt(receipt, role, normalizedPath, JsonHashes.sha256(normalizedBytes),
                        sourcePath, JsonHashes.sha256(sourceBytes), sourceRows.size(), windowStart, windowEnd, sourceRows);
                NormalizationResult derivation = deriveNormalizedRows(role, sourceBytes, receipt);
                if (derivation.recomputed() && !java.util.Arrays.equals(toJsonl(derivation.rows()), normalizedBytes)) {
                    throw failure(role + " normalized rows do not match deterministic derivation from reopened source bytes");
                }
                if (!derivation.recomputed()) allTransformationsRecomputed = false;
                String sourceClass = requiredText(receipt, "source_class");
                if (!sourceClass.equals(entry.path("source_class").asText())
                        || !receipt.path("normalizer_id").asText().equals(entry.path("normalizer_id").asText())
                        || !receipt.path("normalizer_sha256").asText().equals(entry.path("normalizer_sha256").asText())
                        || !receipt.path("availability_basis").asText().equals(entry.path("availability_basis").asText())
                        || !JsonHashes.canonicalSha256(receipt.path("series_scope")).equals(JsonHashes.canonicalSha256(entry.path("series_scope")))
                        || !derivation.status().equals(entry.path("transformation_status").asText())) throw failure("source classification or transformation differs from reopened normalization receipt");
                validateRolePartitionClass(role, sourceClass, sourceRows);
                sourceClasses.add(sourceClass);
                if ("metadata".equals(role)) {
                    String assumptionPath = requiredText(entry, "assumption_path"), assumptionSha = requiredText(entry, "assumption_byte_sha256");
                    byte[] assumptionBytes = readBounded(root, assumptionPath, "metadata assumption receipt", 8L * 1024 * 1024);
                    if (!JsonHashes.sha256(assumptionBytes).equals(assumptionSha)) throw failure("metadata assumption receipt bytes changed");
                    ObjectNode assumption = parseStrictObject(assumptionBytes, "metadata assumption receipt");
                    validateMetadataAssumption(assumption, sourcePath, JsonHashes.sha256(sourceBytes), windowStart, windowEnd);
                    if (!requiredText(assumption, "content_sha256").equals(entry.path("assumption_content_sha256").asText())) throw failure("metadata assumption content changed");
                    String assumptionClass = requiredText(assumption, "source_class");
                    if (!assumptionClass.equals(entry.path("assumption_source_class").asText())) throw failure("metadata assumption classification changed");
                    sourceClasses.add(assumptionClass);
                }
                Path parquet = PathConfinement.resolve(root, outputPath, role + " Parquet partition", PathConfinement.ExpectedType.FILE).absolute();
                if (!JsonHashes.sha256(parquet).equals(entry.path("sha256").asText())
                        || FilesSize(parquet) != entry.path("bytes").asLong(-1)) throw failure("v002 Parquet partition bytes changed");
                List<ObjectNode> reopened = ResearchData.queryParquet(parquet);
                List<ObjectNode> canonicalParquetRows = normalizeRows(role, sortRows(role, reopened));
                if (reopened.size() != entry.path("row_count").asInt(-1)
                        || !JsonHashes.canonicalSha256(canonicalParquetRows).equals(entry.path("rows_sha256").asText())
                        || !JsonHashes.canonicalSha256(canonicalParquetRows).equals(JsonHashes.canonicalSha256(canonicalSourceRows))
                        || !parquetSchemaSha(parquet).equals(entry.path("schema_sha256").asText())) {
                    throw failure("v002 Parquet partition fails canonical source reopen: " + role + "/" + outputPath);
                }
                ObjectNode recomputedCoverage = partitionCoverage(role, canonicalParquetRows);
                if (!JsonHashes.canonicalSha256(recomputedCoverage).equals(JsonHashes.canonicalSha256(entry.path("coverage")))) {
                    throw failure("v002 physical coverage differs from reopened rows: " + role);
                }
                appendGroups(groups, recomputedCoverage.path("groups"));
            }
            datasetIdentity.set(role, roleArtifact.deepCopy());
        }
        if (!JsonHashes.canonicalSha256(datasetIdentity).equals(manifest.path("dataset_root_sha256").asText())) throw failure("v002 partition dataset root hash mismatch");
        validateSourceMode(manifest.path("source_mode").asText(), sourceClasses);
        validateRoleSourceClasses(manifest.path("source_mode").asText(), artifacts);
        ObjectNode coverage = aggregateCoverage(groups);
        coverage = addReadiness(coverage, artifacts, plan);
        if (!JsonHashes.canonicalSha256(coverage).equals(JsonHashes.canonicalSha256(manifest.path("coverage")))) throw failure("v002 coverage summary does not match reopened physical rows");
        boolean hydration = coverage.path("readiness").path("frozen_envelope_covered").asBoolean(false);
        boolean inventoryComplete = coverage.path("readiness").path("coverage_inventory_complete").asBoolean(false);
        boolean replayPermitted = inventoryComplete && allTransformationsRecomputed;
        if (hydration != manifest.path("minimum_contiguous_hydration_proven").asBoolean(!hydration)
                || inventoryComplete != manifest.path("coverage_inventory_complete").asBoolean(!inventoryComplete)
                || allTransformationsRecomputed != manifest.path("all_source_transformations_recomputed").asBoolean(!allTransformationsRecomputed)
                || replayPermitted != manifest.path("development_replay_permitted").asBoolean(!replayPermitted)) throw failure("v002 replay readiness is not derived from reopened coverage and source transforms");
        return JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-physical-manifest-verification/1")
                .put("status", "REOPENED_DEVELOPMENT_ONLY").put("manifest_sha256", manifest.path("content_sha256").asText())
                .put("dataset_root_sha256", manifest.path("dataset_root_sha256").asText())
                .put("role_count", ROLES.size()).put("partition_count", count).put("source_mode", manifest.path("source_mode").asText())
                .put("minimum_contiguous_hydration_proven", hydration).put("all_source_transformations_recomputed", allTransformationsRecomputed)
                .put("coverage_inventory_complete", inventoryComplete)
                .put("development_replay_permitted", replayPermitted)
                .put("authoritative", false).put("content_sha256", JsonHashes.ownHash(JsonHashes.mapper().createObjectNode()
                        .put("schema", "liquidation-v2-physical-manifest-verification/1").put("status", "REOPENED_DEVELOPMENT_ONLY")
                        .put("manifest_sha256", manifest.path("content_sha256").asText()).put("dataset_root_sha256", manifest.path("dataset_root_sha256").asText())
                        .put("role_count", ROLES.size()).put("partition_count", count).put("source_mode", manifest.path("source_mode").asText())
                        .put("minimum_contiguous_hydration_proven", hydration).put("all_source_transformations_recomputed", allTransformationsRecomputed)
                        .put("coverage_inventory_complete", inventoryComplete)
                        .put("development_replay_permitted", replayPermitted).put("authoritative", false)));
    }

    /** Reads a role from selected bounded time partitions; callers stitch windows explicitly. */
    public static List<ObjectNode> readRoleRows(ObjectNode options, String role, String fromInclusive, String toExclusive, List<String> assets) {
        return readRoleRows(openVerifiedDevelopment(options), role, fromInclusive, toExclusive, assets);
    }

    /** Opens and verifies all physical evidence once for a multi-role chronological replay. */
    public static VerifiedDevelopmentSession openVerifiedDevelopment(ObjectNode options) {
        ObjectNode manifest = object(options, "manifest");
        boolean partitioned = manifest.path("artifacts").path("feature").path("partitions").isArray();
        if (partitioned) verifyDevelopment(options); else verifySyntheticDevelopment(options);
        return new VerifiedDevelopmentSession(Path.of(requiredText(options, "root")).toAbsolutePath().normalize(),
                object(options, "profile"), manifest, partitioned);
    }

    /** Reads only intersecting partitions from a previously reopened handle, checking selected Parquet bytes. */
    public static List<ObjectNode> readRoleRows(VerifiedDevelopmentSession session, String role,
            String fromInclusive, String toExclusive, List<String> assets) {
        if (!ROLES.contains(role)) throw failure("unsupported v002 physical role " + role);
        LiquidationDailyStressProfileV1.validate(session.profile());
        ObjectNode manifest = session.manifest(); Path root = session.root();
        if (!JsonHashes.ownHash(manifest).equals(manifest.path("content_sha256").asText())) throw failure("verified development handle manifest changed");
        Long from = fromInclusive == null ? null : epochMillis(fromInclusive, "role read start");
        Long to = toExclusive == null ? null : epochMillis(toExclusive, "role read end");
        if (from != null && to != null && to <= from) throw failure("role read time range must be positive");
        List<ObjectNode> rows = new ArrayList<>();
        if (session.partitioned()) {
            for (JsonNode partition : manifest.path("artifacts").path(role).path("partitions")) {
                long windowStart = epochMillis(partition.path("window_start").asText(), "partition window start");
                long windowEnd = epochMillis(partition.path("window_end_exclusive").asText(), "partition window end");
                if (!"metadata".equals(role) && ((to != null && windowStart >= to) || (from != null && windowEnd <= from))) continue;
                Path path = PathConfinement.resolve(root, partition.path("path").asText(), "v002 " + role + " Parquet partition", PathConfinement.ExpectedType.FILE).absolute();
                if (!JsonHashes.sha256(path).equals(partition.path("sha256").asText())) throw failure("selected v002 Parquet partition changed after verification");
                rows.addAll(queryParquetWindow(path, role, from, to, assets, MAX_ROLE_READ_ROWS - rows.size()));
            }
        } else {
            ObjectNode artifact = object(object(manifest, "artifacts"), role);
            Path path = PathConfinement.resolve(root, requiredText(artifact, "path"), "v002 synthetic " + role + " Parquet role", PathConfinement.ExpectedType.FILE).absolute();
            if (!JsonHashes.sha256(path).equals(artifact.path("sha256").asText())) throw failure("selected synthetic v002 Parquet role changed after verification");
            rows.addAll(queryParquetWindow(path, role, from, to, assets, MAX_ROLE_READ_ROWS - rows.size()));
        }
        rows.sort(java.util.Comparator.comparingLong((ObjectNode row) -> rowTime(role, row)).thenComparing(row -> rowSortKey(role, row)));
        return List.copyOf(rows);
    }

    public static List<ObjectNode> readVerifiedRoleRows(VerifiedDevelopmentSession session, String role,
            String fromInclusive, String toExclusive, List<String> assets) {
        return readRoleRows(session, role, fromInclusive, toExclusive, assets);
    }

    private static List<ObjectNode> queryParquetWindow(Path parquet, String role, Long from, Long to, List<String> assets,
            int selectedRowLimit) {
        if (selectedRowLimit < 0 || selectedRowLimit > MAX_ROLE_READ_ROWS) throw failure("bounded v002 role row limit is invalid");
        String timeField = switch (role) {
            case "feature" -> "event_time";
            case "label" -> "decision_time";
            case "execution" -> "open_time";
            case "mark" -> "timestamp";
            case "funding" -> "settlement_time";
            case "metadata" -> "effective_from";
            default -> throw failure("unsupported v002 role read " + role);
        };
        if (assets != null) for (String asset : assets) {
            if (!(ASSETS.contains(asset) || "feature".equals(role) && "SP500".equals(asset))) {
                throw failure("role read requested an asset outside the frozen identity map");
            }
        }
        String escapedPath = parquet.toAbsolutePath().normalize().toString().replace("'", "''");
        StringBuilder sql = new StringBuilder("SELECT * FROM read_parquet('").append(escapedPath).append("') WHERE TRUE");
        if ("metadata".equals(role)) {
            if (to != null) sql.append(" AND effective_from < ?");
            if (from != null) sql.append(" AND effective_until > ?");
        } else {
            if (from != null) sql.append(" AND ").append(timeField).append(" >= ?");
            if (to != null) sql.append(" AND ").append(timeField).append(" < ?");
        }
        List<String> selectedAssets = assets == null ? List.of() : assets.stream().distinct().sorted().toList();
        if (!selectedAssets.isEmpty()) sql.append(" AND asset IN (")
                .append(String.join(",", java.util.Collections.nCopies(selectedAssets.size(), "?"))).append(")");
        sql.append(" LIMIT ").append(selectedRowLimit + 1);
        List<ObjectNode> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:"); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            if ("metadata".equals(role)) {
                if (to != null) statement.setLong(parameter++, to);
                if (from != null) statement.setLong(parameter++, from);
            } else {
                if (from != null) statement.setLong(parameter++, from);
                if (to != null) statement.setLong(parameter++, to);
            }
            for (String asset : selectedAssets) statement.setString(parameter++, asset);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData(); int columns = metadata.getColumnCount();
                while (result.next()) {
                    if (rows.size() == selectedRowLimit) throw failure("bounded v002 role scan exceeds one million selected rows; narrow the time window");
                    ObjectNode row = JsonHashes.mapper().createObjectNode();
                    for (int column = 1; column <= columns; column++) {
                        String field = metadata.getColumnLabel(column); Object item = result.getObject(column);
                        if (item == null) row.putNull(field);
                        else if (item instanceof java.sql.Timestamp timestamp) row.put(field, timestamp.toInstant().toEpochMilli());
                        else row.set(field, JsonHashes.mapper().valueToTree(item));
                    }
                    rows.add(row);
                }
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw failure("DuckDB could not read bounded v002 " + role + " partition: " + error.getMessage()); }
        return rows;
    }

    public static List<ObjectNode> readRoleRows(ObjectNode options, String role) {
        return readRoleRows(options, role, null, null, List.of());
    }

    public static List<ObjectNode> readRoleRows(VerifiedDevelopmentSession session, String role) {
        return readRoleRows(session, role, null, null, List.of());
    }

    private static ArrayNode seriesScope(String role, List<ObjectNode> rows) {
        ArrayNode scope = JsonHashes.mapper().createArrayNode();
        if (!"feature".equals(role)) return scope;
        java.util.SortedSet<String> series = new java.util.TreeSet<>();
        for (ObjectNode row : rows) series.add(requiredText(row, "series_id"));
        series.forEach(scope::add);
        return scope;
    }

    private static String availabilityBasis(String role, ArrayNode scope, String sourceClass) {
        boolean hasMacro = "feature".equals(role);
        boolean containsMacro = false;
        for (JsonNode value : scope) if ("sp500_close".equals(value.asText())) containsMacro = true;
        if (!hasMacro || !containsMacro) return "NOT_APPLICABLE";
        return "SYNTHETIC_FIXTURE".equals(sourceClass)
                ? SYNTHETIC_MACRO_AVAILABILITY_BASIS : MACRO_AVAILABILITY_BASIS;
    }

    private static void validateNormalizationReceipt(ObjectNode receipt, String role, String normalizedPath,
            String normalizedSha, String sourcePath, String sourceSha, int rowCount, long windowStart, long windowEnd,
            List<ObjectNode> normalizedRows) {
        if (!"liquidation-v2-normalization-receipt/1".equals(receipt.path("schema").asText())
                || receipt.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(receipt).equals(receipt.path("content_sha256").asText())
                || !role.equals(receipt.path("role").asText())
                || !normalizedPath.equals(receipt.path("normalized_path").asText())
                || !normalizedSha.equals(receipt.path("normalized_byte_sha256").asText())
                || !sourcePath.equals(receipt.path("source_path").asText())
                || !sourceSha.equals(receipt.path("source_byte_sha256").asText())
                || receipt.path("row_count").asInt(-1) != rowCount
                || epochMillis(requiredText(receipt, "window_start"), "normalization receipt window_start") != windowStart
                || epochMillis(requiredText(receipt, "window_end_exclusive"), "normalization receipt window_end_exclusive") != windowEnd
                || !JsonHashes.isSha256(receipt.path("normalizer_sha256").asText())
                || requiredText(receipt, "normalizer_id").isBlank()
                || !JsonHashes.canonicalSha256(seriesScope(role, normalizedRows)).equals(JsonHashes.canonicalSha256(receipt.path("series_scope")))
                || !JsonHashes.canonicalSha256(frozenPhysicalIdentity()).equals(JsonHashes.canonicalSha256(receipt.path("physical_identity")))
                || !JsonHashes.canonicalSha256(frozenPhysicalIdentity()).equals(receipt.path("physical_identity_sha256").asText())) {
            throw failure("normalization receipt does not bind the reopened " + role + " source and normalized rows");
        }
        String sourceClass = requiredText(receipt, "source_class");
        if (!availabilityBasis(role, seriesScope(role, normalizedRows), sourceClass).equals(receipt.path("availability_basis").asText())) {
            throw failure("normalization receipt availability basis does not match its reopened feature-series scope");
        }
        if (!Set.of("SYNTHETIC_FIXTURE", "COINALYZE_PROXY", "PUBLIC_ARCHIVE", "DERIVED_OUTCOME_LABEL",
                "HISTORICAL_APPROXIMATION").contains(sourceClass)) {
            throw failure("unsupported normalization source class " + sourceClass);
        }
        if (receipt.path("verified").asBoolean(false) || receipt.path("authoritative").asBoolean(false)
                || receipt.path("historical_availability_proven").asBoolean(false)
                || receipt.path("historical_revision_proven").asBoolean(false)
                || "VERIFIED".equals(receipt.path("status").asText())
                || "VERIFIED".equals(receipt.path("qualification_status").asText())) {
            throw failure("normalization receipt cannot assert PIT or authoritative provenance");
        }
    }

    private static NormalizationResult deriveNormalizedRows(String role, byte[] sourceBytes, ObjectNode receipt) {
        String normalizerId = requiredText(receipt, "normalizer_id");
        String normalizerSha = requiredText(receipt, "normalizer_sha256");
        if (NORMALIZER_COPY_ID.equals(normalizerId)) {
            if (!NORMALIZER_COPY_SHA.equals(normalizerSha)) throw failure("role JSONL copy normalizer hash is not the frozen implementation identity");
            List<ObjectNode> rawRows = parseJsonl(sourceBytes, role + " raw source");
            validateRows(role, rawRows);
            return new NormalizationResult(true, normalizeRows(role, sortRows(role, rawRows)), "RECOMPUTED_ROLE_JSONL_COPY");
        }
        if (NORMALIZER_OI_BASE_ID.equals(normalizerId)) {
            if (!"feature".equals(role) || !NORMALIZER_OI_BASE_SHA.equals(normalizerSha)) throw failure("base-OI normalizer is bound only to its frozen feature mapping");
            List<ObjectNode> rawRows = parseJsonl(sourceBytes, "raw Binance open-interest metrics");
            List<ObjectNode> mapped = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (ObjectNode raw : rawRows) {
                Set<String> allowed = Set.of("asset", "symbol", "series_id", "timeframe", "event_time", "availability_time", "sum_open_interest", "sum_open_interest_value");
                java.util.Iterator<String> fields = raw.fieldNames();
                while (fields.hasNext()) if (!allowed.contains(fields.next())) throw failure("raw open-interest metrics row contains an unsupported or ambiguous field");
                if (!"open_interest_base".equals(requiredText(raw, "series_id")) || !"5m".equals(requiredText(raw, "timeframe"))) {
                    throw failure("base-OI source rows must identify the frozen five-minute OI metric");
                }
                double baseQuantity = finite(raw, "sum_open_interest");
                double quoteNotional = finite(raw, "sum_open_interest_value");
                if (baseQuantity <= 0 || quoteNotional < 0) throw failure("raw Binance OI fields have invalid units/values");
                ObjectNode normalized = JsonHashes.mapper().createObjectNode().put("asset", requiredText(raw, "asset"))
                        .put("symbol", requiredText(raw, "symbol")).put("series_id", "open_interest_base").put("timeframe", "5m")
                        .put("event_time", canonicalTime(raw.path("event_time"), role, "event_time"))
                        .put("availability_time", canonicalTime(raw.path("availability_time"), role, "availability_time"))
                        .put("value", baseQuantity);
                String identity = requiredText(normalized, "asset") + "/" + normalized.path("event_time").asLong();
                if (!identities.add(identity)) throw failure("raw base OI metrics contain duplicate asset/timestamp rows");
                mapped.add(normalized);
            }
            validateRows("feature", mapped);
            return new NormalizationResult(true, normalizeRows("feature", sortRows("feature", mapped)), "RECOMPUTED_BINANCE_BASE_OI_MAPPING");
        }
        // Unknown transformations remain byte-bound diagnostic evidence, never a replay-ready input.
        if (!JsonHashes.isSha256(normalizerSha)) throw failure("unknown normalizer identity lacks a valid hash");
        return new NormalizationResult(false, List.of(), "UNVERIFIED_TRANSFORMATION");
    }

    private static void validateMetadataAssumption(ObjectNode assumption, String sourcePath, String sourceSha,
            long windowStart, long windowEnd) {
        if (!"liquidation-v2-metadata-assumption/1".equals(assumption.path("schema").asText())
                || assumption.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(assumption).equals(assumption.path("content_sha256").asText())
                || !sourcePath.equals(assumption.path("source_path").asText())
                || !sourceSha.equals(assumption.path("source_byte_sha256").asText())
                || epochMillis(requiredText(assumption, "window_start"), "metadata assumption window_start") != windowStart
                || epochMillis(requiredText(assumption, "window_end_exclusive"), "metadata assumption window_end_exclusive") != windowEnd
                || !Set.of("HISTORICAL_APPROXIMATION", "SYNTHETIC_FIXTURE").contains(assumption.path("source_class").asText())
                || assumption.path("historical_availability_proven").asBoolean(true)
                || assumption.path("historical_revision_proven").asBoolean(true)
                || assumption.path("authoritative").asBoolean(true)
                || requiredText(assumption, "assumption_description").length() < 24) {
            throw failure("metadata requires a reopened, hash-bound historical-approximation assumption receipt");
        }
    }

    private static void validatePartitionWindow(String role, List<ObjectNode> rows, long start, long end) {
        for (ObjectNode row : rows) {
            if ("metadata".equals(role) || "label".equals(role)) continue;
            long timestamp = rowTime(role, row);
            if (timestamp < start || timestamp >= end) throw failure(role + " row falls outside its declared half-open partition window");
        }
    }

    static ObjectNode partitionCoverage(String role, List<ObjectNode> rows) {
        Map<String, CoverageGroup> groups = new TreeMap<>();
        for (ObjectNode row : rows) {
            long time = rowTime(role, row);
            String asset = row.path("asset").asText();
            String series = "", timeframe = "", side = "";
            long cadence = 0;
            if ("feature".equals(role)) {
                series = row.path("series_id").asText(); timeframe = row.path("timeframe").asText(); side = row.path("side").asText("");
                cadence = switch (series) {
                    case "price_ohlc" -> cadenceMillis(timeframe);
                    case "open_interest_base" -> 300_000L;
                    case "daily_liquidation_usd" -> 86_400_000L;
                    case "sp500_close" -> 0L; // Actual NYSE closes vary with DST/early sessions; each timestamp is calendar-validated.
                    default -> 0L;
                };
            } else if ("execution".equals(role) || "mark".equals(role)) cadence = 60_000L;
            String key = coverageKey(role, asset, series, timeframe, side);
            CoverageGroup group = groups.get(key);
            if (group == null) { group = new CoverageGroup(role, asset, series, timeframe, side, cadence); groups.put(key, group); }
            group.rowCount++;
            group.times.add(time);
            if ("funding".equals(role) && row.hasNonNull("funding_interval_hours")) {
                long intervalMillis = Math.multiplyExact((long) finite(row, "funding_interval_hours"), 3_600_000L);
                group.fundingExpectedEnds.put(time, Math.addExact(time, intervalMillis));
            }
            if ("metadata".equals(role)) group.addEffectiveInterval(time,
                    canonicalTime(row.path("effective_until"), role, "effective_until"));
        }
        ArrayNode entries = JsonHashes.mapper().createArrayNode();
        groups.values().forEach(group -> entries.add(group.toJson()));
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("row_count", rows.size());
        result.set("groups", entries);
        return result;
    }

    private static void appendGroups(ArrayNode into, JsonNode source) {
        if (!source.isArray()) throw failure("v002 coverage groups are malformed");
        source.forEach(node -> into.add(node.deepCopy()));
    }

    static ObjectNode aggregateCoverage(ArrayNode partitionGroups) {
        Map<String, AggregateGroup> aggregates = new TreeMap<>();
        for (JsonNode group : partitionGroups) {
            String key = requiredText((ObjectNode) group, "key");
            AggregateGroup aggregate = aggregates.computeIfAbsent(key, ignored -> new AggregateGroup(group));
            if (aggregate.cadence != group.path("cadence_ms").asLong()) throw failure("coverage cadence changed between physical partitions");
            aggregate.rows += group.path("row_count").asLong();
            JsonNode segments = group.path("segments");
            if (!segments.isArray()) throw failure("coverage segment inventory is malformed");
            for (JsonNode segment : segments) aggregate.segments.add(new Segment(segment.path(0).asLong(), segment.path(1).asLong(), segment.path(2).asLong()));
            JsonNode intervals = group.path("effective_intervals");
            if (intervals.isArray()) for (JsonNode interval : intervals) {
                long from = interval.path(0).asLong(), until = interval.path(1).asLong();
                Long prior = aggregate.intervals.putIfAbsent(from, until);
                if (prior != null && prior.longValue() != until) throw failure("maintenance tiers/partitions disagree on effective interval bounds");
            }
            JsonNode fundingSlots = group.path("funding_expected_slots");
            if (fundingSlots.isArray()) for (JsonNode slot : fundingSlots) {
                long actual = slot.path("actual_settlement_time_ms").asLong(Long.MIN_VALUE);
                long expectedEnd = slot.path("expected_next_slot_time_ms").asLong(Long.MIN_VALUE);
                if (actual == Long.MIN_VALUE || expectedEnd <= actual) throw failure("funding expected-slot inventory is malformed");
                if (aggregate.fundingExpectedEnds.putIfAbsent(actual, expectedEnd) != null) throw failure("funding partitions repeat an actual settlement timestamp");
            }
        }
        ArrayNode summaries = JsonHashes.mapper().createArrayNode();
        for (AggregateGroup aggregate : aggregates.values()) summaries.add(aggregate.toJson());
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-observed-coverage/1");
        value.set("groups", summaries);
        value.put("content_sha256", JsonHashes.ownHash(value));
        return value;
    }

    private static ObjectNode addReadiness(ObjectNode coverage, ObjectNode artifacts, ObjectNode plan) {
        coverage.remove("content_sha256");
        ArrayNode gaps = readinessGaps(coverage, artifacts, plan);
        ArrayNode declaredFeatureGaps = plan.path("daily_feature_gap_exclusions").isArray()
                ? (ArrayNode) plan.path("daily_feature_gap_exclusions").deepCopy() : JsonHashes.mapper().createArrayNode();
        boolean inventoryComplete = gaps.isEmpty();
        boolean continuous = inventoryComplete && declaredFeatureGaps.isEmpty();
        ObjectNode readiness = JsonHashes.mapper().createObjectNode()
                .put("status", !inventoryComplete ? "BLOCKED_INCOMPLETE_PHYSICAL_COVERAGE"
                        : continuous ? "CONTINUOUSLY_COVERED" : "AUDITED_WITH_DECLARED_FEATURE_GAPS")
                .put("frozen_envelope_covered", continuous)
                .put("continuous_observations_complete", continuous)
                .put("coverage_inventory_complete", inventoryComplete)
                .put("minimum_contiguous_hydration_days_required", plan.path("minimum_contiguous_hydration_days").asInt())
                .put("warmup_start", plan.path("source_start").asText())
                .put("decision_start", plan.path("decision_start").asText())
                .put("decision_end_exclusive", plan.path("decision_end_exclusive").asText())
                .put("execution_end_exclusive", plan.path("execution_end_exclusive").asText())
                .put("replay_authority", "DEVELOPMENT_ONLY");
        readiness.set("missing_coverage", gaps);
        readiness.set("declared_feature_gap_exclusions", declaredFeatureGaps);
        coverage.set("readiness", readiness);
        coverage.put("content_sha256", JsonHashes.ownHash(coverage));
        return coverage;
    }

    private static ArrayNode dailyFeatureGapExclusions() {
        LocalDate decisionStart = LocalDate.parse("2022-11-11");
        LocalDate decisionEnd = LocalDate.parse("2026-07-15");
        Set<LocalDate> suppressed = new java.util.TreeSet<>();
        ArrayNode missingDays = JsonHashes.mapper().createArrayNode();
        for (LocalDate missing : FROZEN_AAVE_DAILY_GAPS) {
            missingDays.add(missing.toString());
            // The frozen t+48h availability rule maps decision date d to the source bucket d-2.
            // One missing bucket blocks the current-bucket decision and the following 90 prior windows.
            for (LocalDate decision = missing.plusDays(2); !decision.isAfter(missing.plusDays(92)); decision = decision.plusDays(1)) {
                if (!decision.isBefore(decisionStart) && decision.isBefore(decisionEnd)) suppressed.add(decision);
            }
        }
        ArrayNode ranges = JsonHashes.mapper().createArrayNode();
        LocalDate rangeStart = null, previous = null;
        for (LocalDate decision : suppressed) {
            if (rangeStart == null) { rangeStart = decision; previous = decision; continue; }
            if (!decision.equals(previous.plusDays(1))) {
                ranges.addObject().put("from_inclusive", rangeStart.toString()).put("to_exclusive", previous.plusDays(1).toString());
                rangeStart = decision;
            }
            previous = decision;
        }
        if (rangeStart != null) ranges.addObject().put("from_inclusive", rangeStart.toString())
                .put("to_exclusive", previous.plusDays(1).toString());
        ObjectNode exclusion = JsonHashes.mapper().createObjectNode().put("asset", "AAVE")
                .put("series_id", "daily_liquidation_usd").put("side_scope", "LONG_AND_SHORT")
                .put("lookback_days", 90).put("current_observation_required", true)
                .put("missing_policy", "BLOCK_AFFECTED_LOOKBACK_WINDOWS_NO_ZERO_FILL_OR_FORWARD_FILL")
                .put("source_audit_schema", "daily-stress-diagnostic-summary/1")
                .put("source_audit_sha256", DAILY_GAP_AUDIT_SHA256)
                .put("suppressed_decision_days", suppressed.size());
        exclusion.set("missing_event_days_utc", missingDays);
        exclusion.set("suppressed_decision_ranges_utc", ranges);
        return JsonHashes.mapper().createArrayNode().add(exclusion);
    }

    private static ArrayNode readinessGaps(ObjectNode coverage, ObjectNode artifacts, ObjectNode plan) {
        ArrayNode gaps = JsonHashes.mapper().createArrayNode();
        long sourceStart = epochMillis(plan.path("source_start").asText(), "plan source_start");
        long decisionStart = epochMillis(plan.path("decision_start").asText(), "plan decision_start");
        long decisionEnd = epochMillis(plan.path("decision_end_exclusive").asText(), "plan decision_end_exclusive");
        long executionEnd = epochMillis(plan.path("execution_end_exclusive").asText(), "plan execution_end_exclusive");
        long dailyEnd = decisionEnd - 2L * 86_400_000L;
        for (String asset : ASSETS) {
            requireCovered(gaps, coverage, coverageKey("feature", asset, "price_ohlc", "4h", ""), sourceStart,
                    executionEnd - 14_400_000L, 0, "four-hour price plus completed-bar availability through final position lifecycle");
            requireCovered(gaps, coverage, coverageKey("feature", asset, "price_ohlc", "1h", ""), sourceStart,
                    executionEnd - 3_600_000L, 0, "one-hour price plus completed-bar availability for lifecycle additions");
            requireCovered(gaps, coverage, coverageKey("feature", asset, "open_interest_base", "5m", ""), sourceStart,
                    decisionEnd - 300_000L, 0, "base-quantity open interest and endpoint availability");
            for (String side : List.of("LONG", "SHORT")) {
                String key = coverageKey("feature", asset, "daily_liquidation_usd", "1d", side);
                boolean dailyCoverageMatches = "AAVE".equals(asset)
                        ? coverageMatchesDeclaredDailyGaps(coverage, key, sourceStart, dailyEnd, plan)
                        : coverageCovers(coverage, key, sourceStart, dailyEnd, 0);
                if (!dailyCoverageMatches) gaps.add(key + " does not match the frozen daily-observation envelope or its declared AAVE exclusions");
            }
            requireCovered(gaps, coverage, coverageKey("execution", asset, "", "", ""), decisionStart, executionEnd, 0,
                    "one-minute traded execution bars through lifecycle/execution margin");
            requireCovered(gaps, coverage, coverageKey("mark", asset, "", "", ""), decisionStart, executionEnd, 0,
                    "one-minute mark bars through lifecycle/execution margin");
            requireCovered(gaps, coverage, coverageKey("funding", asset, "", "", ""), decisionStart, executionEnd,
                    FUNDING_SLOT_TOLERANCE_MILLIS,
                    "actual funding settlements and disclosed event intervals through lifecycle/execution margin");
            for (JsonNode group : coverage.path("groups")) {
                if (coverageKey("funding", asset, "", "", "").equals(group.path("key").asText())
                        && !group.path("funding_schedule_valid").asBoolean(false)) {
                    gaps.add(asset + ":funding actual settlements do not match their supplied expected slots within the frozen tolerance");
                }
            }
            if (!metadataCoversPlan(coverage, asset, plan)) gaps.add(asset + ":metadata missing continuous effective contract/tier coverage from frozen source start through execution end");
        }
        for (String role : ROLES) if (artifacts.path(role).path("partition_count").asInt(0) <= 0) gaps.add(role + ":mandatory physical role missing");
        return gaps;
    }

    private static void requireCovered(ArrayNode gaps, ObjectNode coverage, String key, long start, long end,
            long boundaryToleranceMillis, String description) {
        if (!coverageCovers(coverage, key, start, end, boundaryToleranceMillis)) {
            gaps.add(key + " missing contiguous " + description + " over [" + java.time.Instant.ofEpochMilli(start)
                    + "," + java.time.Instant.ofEpochMilli(end) + ")");
        }
    }

    private static boolean coverageCovers(ObjectNode coverage, String key, long requiredStart, long requiredEnd,
            long boundaryToleranceMillis) {
        if (requiredEnd <= requiredStart) return false;
        List<long[]> segments = coverageSegments(coverage, key);
        long cursor = requiredStart;
        for (long[] segment : segments) {
            if (segment[1] <= cursor) continue;
            if (segment[0] > cursor + boundaryToleranceMillis) return false;
            if (segment[0] < cursor - boundaryToleranceMillis && cursor > requiredStart) return false;
            cursor = Math.max(cursor, segment[1]);
            if (cursor >= requiredEnd) return true;
        }
        return false;
    }

    static boolean coverageMatchesDeclaredDailyGaps(ObjectNode coverage, String key, long requiredStart,
            long requiredEnd, ObjectNode plan) {
        if (requiredEnd <= requiredStart || requiredStart % 86_400_000L != 0 || requiredEnd % 86_400_000L != 0) return false;
        JsonNode group = null;
        for (JsonNode candidate : coverage.path("groups")) if (key.equals(candidate.path("key").asText())) { group = candidate; break; }
        if (group == null) return false;
        List<Long> expectedMissingDays = new ArrayList<>();
        if (key.startsWith("feature/AAVE/daily_liquidation_usd/1d/")) {
            for (JsonNode exclusion : plan.path("daily_feature_gap_exclusions")) {
                if (!"AAVE".equals(exclusion.path("asset").asText())
                        || !"daily_liquidation_usd".equals(exclusion.path("series_id").asText())) continue;
                for (JsonNode value : exclusion.path("missing_event_days_utc")) {
                    LocalDate date = LocalDate.parse(value.asText());
                    long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    if (start >= requiredStart && start < requiredEnd) expectedMissingDays.add(start);
                }
            }
        }
        expectedMissingDays = expectedMissingDays.stream().distinct().sorted().toList();
        List<long[]> expectedGaps = new ArrayList<>();
        for (long missingStart : expectedMissingDays) {
            if (!expectedGaps.isEmpty() && expectedGaps.get(expectedGaps.size() - 1)[1] == missingStart) {
                expectedGaps.get(expectedGaps.size() - 1)[1] += 86_400_000L;
            } else expectedGaps.add(new long[] { missingStart, missingStart + 86_400_000L });
        }
        long expectedRows = ChronoUnit.DAYS.between(Instant.ofEpochMilli(requiredStart), Instant.ofEpochMilli(requiredEnd)) - expectedMissingDays.size();
        if (group.path("row_count").asLong(-1) != expectedRows) return false;
        List<long[]> observed = coverageSegments(coverage, key);
        if (observed.isEmpty()) return false;
        List<long[]> actualGaps = new ArrayList<>(); long cursor = requiredStart, observedRows = 0;
        for (long[] segment : observed) {
            if (segment[1] <= requiredStart || segment[0] >= requiredEnd) continue;
            long segmentStart = Math.max(segment[0], requiredStart), segmentEnd = Math.min(segment[1], requiredEnd);
            if (segmentStart < cursor || segmentStart % 86_400_000L != 0 || segmentEnd % 86_400_000L != 0
                    || segmentEnd <= segmentStart) return false;
            if (segmentStart > cursor) actualGaps.add(new long[] { cursor, segmentStart });
            observedRows += (segmentEnd - segmentStart) / 86_400_000L;
            cursor = segmentEnd;
        }
        if (cursor < requiredEnd) actualGaps.add(new long[] { cursor, requiredEnd });
        if (cursor > requiredEnd || observedRows != expectedRows || actualGaps.size() != expectedGaps.size()) return false;
        for (int index = 0; index < actualGaps.size(); index++) {
            if (actualGaps.get(index)[0] != expectedGaps.get(index)[0]
                    || actualGaps.get(index)[1] != expectedGaps.get(index)[1]) return false;
        }
        return true;
    }

    private static List<long[]> coverageSegments(ObjectNode coverage, String key) {
        for (JsonNode group : coverage.path("groups")) if (key.equals(group.path("key").asText())) {
            List<long[]> result = new ArrayList<>();
            for (JsonNode segment : group.path("segments")) result.add(new long[] { segment.path(0).asLong(), segment.path(1).asLong() });
            return result;
        }
        return List.of();
    }


    private static boolean metadataCoversPlan(ObjectNode coverage, String asset, ObjectNode plan) {
        long requiredStart = epochMillis(plan.path("source_start").asText(), "plan source_start");
        long requiredEnd = epochMillis(plan.path("execution_end_exclusive").asText(), "plan execution_end_exclusive");
        List<long[]> intervals = new ArrayList<>();
        for (JsonNode group : coverage.path("groups")) if (asset.equals(group.path("asset").asText()) && "metadata".equals(group.path("role").asText())) {
            for (JsonNode interval : group.path("effective_intervals")) intervals.add(new long[] { interval.path(0).asLong(), interval.path(1).asLong() });
        }
        intervals.sort(java.util.Comparator.comparingLong(interval -> interval[0]));
        long cursor = requiredStart;
        for (long[] interval : intervals) {
            if (interval[0] > cursor) break;
            cursor = Math.max(cursor, interval[1]);
            if (cursor >= requiredEnd) return true;
        }
        return false;
    }

    private static String coverageKey(String role, String asset, String series, String timeframe, String side) {
        return String.join("/", role, asset, series, timeframe, side);
    }

    private static long rowTime(String role, ObjectNode row) {
        String field = switch (role) {
            case "feature" -> "event_time";
            case "execution" -> "open_time";
            case "mark" -> "timestamp";
            case "funding" -> "settlement_time";
            case "metadata" -> "effective_from";
            case "label" -> "decision_time";
            default -> throw failure("unsupported coverage time role " + role);
        };
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return 0L;
        return canonicalTime(value, role, field);
    }

    private static byte[] readBounded(Path root, String relative, String label, long limit) {
        PathConfinement.ResolvedPath resolved = PathConfinement.resolve(root, relative, label, PathConfinement.ExpectedType.FILE);
        if (resolved.attributes().size() > limit) throw failure(label + " exceeds the bounded input size");
        byte[] bytes = PathConfinement.readSinglyLinkedFile(resolved.absolute(), label);
        if (bytes.length > limit) throw failure(label + " exceeds the bounded input size");
        return bytes;
    }

    private static ObjectNode parseStrictObject(byte[] bytes, String label) {
        try {
            JsonNode node = JsonHashes.mapper().readTree(bytes);
            if (!(node instanceof ObjectNode object)) throw failure(label + " must be a JSON object");
            return object;
        } catch (IOException error) { throw failure("invalid " + label + ": " + error.getMessage()); }
    }

    private static long epochMillis(String value, String label) {
        try {
            long epoch = Long.parseLong(value);
            return epoch;
        } catch (NumberFormatException ignored) {
            try {
                java.time.Instant instant = java.time.Instant.parse(value);
                if (instant.getNano() % 1_000_000 != 0) throw failure(label + " cannot contain sub-millisecond precision");
                return instant.toEpochMilli();
            } catch (RuntimeException error) {
                if (error instanceof IllegalArgumentException illegal) throw illegal;
                throw failure(label + " must be an integral epoch millisecond or ISO UTC timestamp");
            }
        }
    }

    private static void writeNewOrCheck(Path root, String relative, Path target, byte[] bytes, String label) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            PathConfinement.resolve(root, relative, label, PathConfinement.ExpectedType.FILE);
            if (!JsonHashes.sha256(target).equals(JsonHashes.sha256(bytes))) throw failure("immutable " + label + " path contains different bytes");
            return;
        }
        try (var output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            output.write(bytes);
        }
        PathConfinement.validateSinglyLinkedFile(target, label);
    }

    private static void validateSourceModeInputs(ObjectNode artifacts) {
        boolean onlySynthetic = true;
        for (String role : ROLES) for (JsonNode partition : artifacts.path(role).path("partitions")) {
            if (!"SYNTHETIC_FIXTURE".equals(partition.path("source_class").asText())) onlySynthetic = false;
            if ("metadata".equals(role) && !"SYNTHETIC_FIXTURE".equals(partition.path("assumption_source_class").asText())) onlySynthetic = false;
        }
        if (!onlySynthetic) validateSourceMode("PROXY_DISCLOSED_DEVELOPMENT_ONLY", sourceClasses(artifacts));
    }

    private static Set<String> sourceClasses(ObjectNode artifacts) {
        Set<String> result = new HashSet<>();
        for (String role : ROLES) for (JsonNode partition : artifacts.path(role).path("partitions")) result.add(partition.path("source_class").asText());
        for (JsonNode partition : artifacts.path("metadata").path("partitions")) result.add(partition.path("assumption_source_class").asText());
        return result;
    }

    private static void validateSourceMode(String mode, Set<String> classes) {
        if ("SYNTHETIC_DEVELOPMENT_ONLY".equals(mode)) {
            if (!classes.equals(Set.of("SYNTHETIC_FIXTURE"))) throw failure("synthetic development manifest has non-synthetic source receipts");
            return;
        }
        if (!"PROXY_DISCLOSED_DEVELOPMENT_ONLY".equals(mode)) throw failure("unsupported v002 physical source mode");
        if (classes.contains("SYNTHETIC_FIXTURE")) throw failure("proxy development mode cannot mix synthetic fixture partitions");
        if (!classes.contains("COINALYZE_PROXY") || !classes.contains("PUBLIC_ARCHIVE")
                || !classes.contains("DERIVED_OUTCOME_LABEL") || !classes.contains("HISTORICAL_APPROXIMATION")) {
            throw failure("proxy development replay requires reopened Coinalyze, public-archive, derived-label, and metadata-assumption evidence");
        }
    }

    private static void validateRolePartitionClass(String role, String sourceClass, List<ObjectNode> rows) {
        if ("SYNTHETIC_FIXTURE".equals(sourceClass)) return;
        switch (role) {
            case "feature" -> {
                if (!Set.of("COINALYZE_PROXY", "PUBLIC_ARCHIVE").contains(sourceClass)) throw failure("feature partition source class is unsupported");
                for (ObjectNode row : rows) {
                    if ("COINALYZE_PROXY".equals(sourceClass) != "daily_liquidation_usd".equals(row.path("series_id").asText())) {
                        throw failure("feature partitions must keep Coinalyze daily liquidation proxy rows separate from public archive rows");
                    }
                }
            }
            case "label" -> { if (!"DERIVED_OUTCOME_LABEL".equals(sourceClass)) throw failure("proxy outcome labels must be derived after the replay"); }
            case "execution", "mark", "funding" -> {
                if (!"PUBLIC_ARCHIVE".equals(sourceClass)) throw failure(role + " partition must be backed by reopened public archive bytes");
                if ("funding".equals(role) && rows.stream().anyMatch(row -> !row.hasNonNull("funding_interval_hours"))) {
                    throw failure("funding source partitions must preserve each actual event interval");
                }
            }
            case "metadata" -> { if (!Set.of("PUBLIC_ARCHIVE", "HISTORICAL_APPROXIMATION").contains(sourceClass)) throw failure("metadata source class is unsupported"); }
            default -> throw failure("unsupported v002 role " + role);
        }
    }

    private static void validateRoleSourceClasses(String mode, ObjectNode artifacts) {
        if ("SYNTHETIC_DEVELOPMENT_ONLY".equals(mode)) {
            for (String role : ROLES) for (JsonNode partition : artifacts.path(role).path("partitions")) {
                if (!"SYNTHETIC_FIXTURE".equals(partition.path("source_class").asText())) throw failure("synthetic manifest contains non-synthetic rows");
                if ("metadata".equals(role) && !"SYNTHETIC_FIXTURE".equals(partition.path("assumption_source_class").asText())) throw failure("synthetic metadata assumption class changed");
            }
            return;
        }
        requirePartitionClass(artifacts.path("feature"), "COINALYZE_PROXY");
        requirePartitionClass(artifacts.path("feature"), "PUBLIC_ARCHIVE");
        requirePartitionClass(artifacts.path("label"), "DERIVED_OUTCOME_LABEL");
        for (String role : List.of("execution", "mark", "funding")) requirePartitionClass(artifacts.path(role), "PUBLIC_ARCHIVE");
        boolean metadataAssumption = false;
        for (JsonNode partition : artifacts.path("metadata").path("partitions")) {
            if (!"HISTORICAL_APPROXIMATION".equals(partition.path("assumption_source_class").asText())) continue;
            metadataAssumption = true;
        }
        if (!metadataAssumption) throw failure("proxy development metadata requires a disclosed historical-approximation assumption receipt");
    }

    private static void requirePartitionClass(JsonNode role, String sourceClass) {
        for (JsonNode partition : role.path("partitions")) if (sourceClass.equals(partition.path("source_class").asText())) return;
        throw failure("required physical partition source class is missing: " + sourceClass);
    }

    private static List<ObjectNode> parseJsonl(byte[] bytes, String role) {
        String text = new String(bytes, StandardCharsets.UTF_8); List<ObjectNode> rows = new ArrayList<>();
        if (text.isBlank()) throw failure(role + " JSONL cannot be empty");
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = JsonHashes.mapper().readTree(line);
                if (!(node instanceof ObjectNode object)) throw failure(role + " JSONL rows must be objects");
                rows.add(object);
            } catch (JsonProcessingException error) { throw failure("invalid " + role + " JSONL: " + error.getMessage()); }
        }
        if (rows.isEmpty()) throw failure(role + " JSONL has no records");
        return rows;
    }

    private static List<ObjectNode> normalizeRows(String role, List<ObjectNode> rows) {
        List<String> fields = ROLE_FIELDS.get(role);
        if (fields == null) throw failure("unsupported v002 normalization role " + role);
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode row : rows) {
            ObjectNode normalized = JsonHashes.mapper().createObjectNode();
            for (String field : fields) {
                JsonNode value = row.get(field);
                if (value == null || value.isNull()) normalized.putNull(field);
                else if (isTimeField(role, field)) normalized.put(field, canonicalTime(value, role, field));
                else if ("metadata".equals(role) && "tier_index".equals(field)) normalized.put(field, canonicalTierIndex(row));
                else if (value.isNumber()) {
                    try {
                        normalized.set(field, JsonHashes.mapper().getNodeFactory().numberNode(value.decimalValue().stripTrailingZeros()));
                    } catch (NumberFormatException error) { throw failure(role + " has a non-canonical number in " + field); }
                } else normalized.set(field, value.deepCopy());
            }
            result.add(normalized);
        }
        return result;
    }
    private static boolean isTimeField(String role, String field) {
        return switch (role) {
            case "feature" -> Set.of("event_time", "availability_time").contains(field);
            case "label" -> "decision_time".equals(field);
            case "execution" -> "open_time".equals(field);
            case "mark" -> "timestamp".equals(field);
            case "funding" -> "settlement_time".equals(field);
            case "metadata" -> Set.of("effective_from", "effective_until").contains(field);
            default -> false;
        };
    }

    private static List<ObjectNode> sortRows(String role, List<ObjectNode> rows) {
        return rows.stream().sorted(java.util.Comparator.comparing(row -> rowSortKey(role, row))).toList();
    }
    private static String rowSortKey(String role, ObjectNode row) {
        String asset = row.path("asset").asText();
        String time = switch (role) {
            case "feature" -> requireTime(row, "event_time", role);
            case "label" -> row.path("episode_id").asText();
            case "execution" -> requireTime(row, "open_time", role);
            case "mark" -> requireTime(row, "timestamp", role);
            case "funding" -> requireTime(row, "settlement_time", role);
            case "metadata" -> requireTime(row, "effective_from", role) + "/" + canonicalTierIndex(row);
            default -> throw failure("unsupported v002 role sort " + role);
        };
        return asset + "/" + String.format(Locale.ROOT, "%020d", parseMillis(time)) + "/" + row.path("timeframe").asText()
                + "/" + row.path("series_id").asText() + "/" + row.path("side").asText() + "/" + row.path("episode_id").asText()
                + "/" + row.path("event_id").asText();
    }
    private static long parseMillis(String value) {
        try { return Long.parseLong(value.contains("/") ? value.substring(0, value.indexOf('/')) : value); }
        catch (NumberFormatException error) { return 0; }
    }
    private static byte[] toJsonl(List<ObjectNode> rows) {
        StringBuilder result = new StringBuilder();
        for (ObjectNode row : rows) result.append(JsonHashes.canonicalString(row)).append('\n');
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void validateRows(String role, List<ObjectNode> rows) {
        Set<String> identities = new HashSet<>();
        Set<String> fundingSlots = new HashSet<>();
        for (ObjectNode row : rows) {
            Set<String> allowedFields = Set.copyOf(ROLE_FIELDS.getOrDefault(role, List.of()));
            java.util.Iterator<String> declaredFields = row.fieldNames();
            while (declaredFields.hasNext()) {
                String field = declaredFields.next();
                if (!allowedFields.contains(field)) throw failure(role + " role contains an undeclared field: " + field);
            }
            String identity; switch (role) {
                case "feature" -> {
                    String assetKey = requiredText(row, "asset");
                    String series = requiredText(row, "series_id");
                    String timeframe = requiredText(row, "timeframe");
                    if (!(ASSETS.contains(assetKey) || "SP500".equals(assetKey))) throw failure("feature asset must be a frozen instrument or SP500 context");
                    String symbol = row.path("symbol").asText("");
                    if (!symbol.isEmpty() && !symbol.matches("[A-Z0-9._:-]{2,32}")) throw failure("feature symbol is not canonical");
                    if (symbol.isEmpty()) throw failure("feature rows require an explicit source symbol bound to the frozen instrument map");
                    identity = assetKey + "/" + timeframe + "/" + series + "/" + row.path("side").asText("")
                            + "/" + requireTime(row, "event_time", role);
                    requireTime(row, "availability_time", role);
                    if (!row.path("event_time").isNumber() || !row.path("availability_time").isNumber()) throw failure("feature event and availability times must be epoch milliseconds");
                    long event = row.path("event_time").asLong(), available = row.path("availability_time").asLong();
                    if (available < event) throw failure("feature observation cannot be available before its event time");
                    switch (series) {
                        case "price_ohlc" -> {
                            if (!ASSETS.contains(assetKey) || !Set.of("1m", "1h", "4h").contains(timeframe)) throw failure("price bars require a frozen instrument and one of the specified trading clocks");
                            requireSymbol(symbol, BINANCE_SYMBOLS.get(assetKey), "Binance price feature");
                            validateOhlc(row, "price feature");
                            requireAligned(event, cadenceMillis(timeframe), "price feature event_time");
                            if (available < event + cadenceMillis(timeframe)) throw failure("price feature availability must be no earlier than completed-bar time");
                            if (row.has("base_volume")) positive(row, "base_volume", true);
                        }
                        case "open_interest_base" -> {
                            if (!ASSETS.contains(assetKey) || !"5m".equals(timeframe)) throw failure("open interest must be base quantity at five-minute cadence");
                            requireSymbol(symbol, BINANCE_SYMBOLS.get(assetKey), "Binance open-interest feature");
                            requireAligned(event, 300_000L, "open interest event_time");
                            positive(row, "value", false);
                        }
                        case "daily_liquidation_usd" -> {
                            if (!ASSETS.contains(assetKey) || !"1d".equals(timeframe) || !Set.of("LONG", "SHORT").contains(requiredText(row, "side"))) throw failure("daily liquidation must preserve asset and position side");
                            requireSymbol(symbol, COINALYZE_SYMBOLS.get(assetKey), "Coinalyze liquidation feature");
                            if (event % 86_400_000L != 0 || available < event + 172_800_000L) throw failure("daily stress is unavailable before its frozen t+48h assumption");
                            positive(row, "value", true);
                        }
                        case "sp500_close" -> {
                            if (!"SP500".equals(assetKey) || !"1d".equals(timeframe)) throw failure("S&P context must be a completed daily session series");
                            requireSymbol(symbol, "SP500", "S&P context feature");
                            LiquidationStructureRouterV1.validateMacroClose(Instant.ofEpochMilli(event), Instant.ofEpochMilli(available));
                            positive(row, "close", false);
                        }
                        default -> throw failure("feature series is not a frozen underlying input: " + series);
                    }
                }
                case "label" -> {
                    identity = requireAsset(row) + "/" + requiredText(row, "episode_id");
                    if (!row.has("label") && !row.has("outcome")) throw failure("label role requires an explicit label/outcome field");
                    requireTime(row, "decision_time", role);
                }
                case "execution" -> {
                    long minute = Long.parseLong(requireTime(row, "open_time", role));
                    if (minute % 60_000 != 0) throw failure("execution bar open_time must be aligned to UTC minute");
                    identity = requireAsset(row) + "/" + minute;
                    for (String field : List.of("open", "high", "low", "close")) positive(row, field, false);
                    positive(row, "base_volume", true);
                    double open = row.path("open").asDouble(), high = row.path("high").asDouble();
                    double low = row.path("low").asDouble(), close = row.path("close").asDouble();
                    if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) throw failure("execution OHLC bounds are inconsistent");
                    if (row.has("fill_price") || row.has("fill_quantity") || row.has("order_id") || row.has("trade_id"))
                        throw failure("execution input must contain market bars, not caller-authored fills");
                }
                case "mark" -> {
                    long minute = Long.parseLong(requireTime(row, "timestamp", role));
                    if (minute % 60_000 != 0) throw failure("mark OHLC timestamp must be aligned to UTC minute");
                    identity = requireAsset(row) + "/" + minute;
                    for (String field : List.of("open", "high", "low", "close")) positive(row, field, false);
                    double open = row.path("open").asDouble(), high = row.path("high").asDouble();
                    double low = row.path("low").asDouble(), close = row.path("close").asDouble();
                    if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) throw failure("mark OHLC bounds are inconsistent");
                }
                case "funding" -> {
                    String eventId = requiredText(row, "event_id");
                    String asset = requireAsset(row);
                    String settlement = requireTime(row, "settlement_time", role);
                    identity = asset + "/" + settlement + "/" + eventId;
                    if (!fundingSlots.add(asset + "/" + settlement)) throw failure("funding has duplicate observed settlement slots for one asset");
                    if (!eventId.matches("[A-Za-z0-9._:-]{1,128}")) throw failure("funding event_id is not canonical");
                    finite(row, "funding_rate"); positive(row, "mark_price", false);
                    if (row.hasNonNull("funding_interval_hours")) {
                        double hours = finite(row, "funding_interval_hours");
                        if (hours < 1 || hours > 24 || hours != Math.rint(hours)) throw failure("funding interval must be an integer number of hours in [1,24]");
                    }
                }
                case "metadata" -> {
                    identity = requireAsset(row) + "/" + requireTime(row, "effective_from", role) + "/" + canonicalTierIndex(row);
                    requireTime(row, "effective_until", role);
                    positive(row, "lot_size", false); positive(row, "minimum_notional", false);
                    positive(row, "taker_fee_rate", true); positive(row, "slippage_rate", true);
                    positive(row, "liquidation_fee_rate", true);
                    positive(row, "tier_notional_cap", false); positive(row, "maintenance_margin_rate", true);
                    positive(row, "maintenance_deduction", true);
                    if (row.path("maintenance_margin_rate").asDouble(-1) >= 1.0) throw failure("maintenance margin rate must be less than one");
                    if (row.hasNonNull("terminal_tier") && !row.path("terminal_tier").isBoolean()) throw failure("metadata terminal_tier must be boolean");
                }
                default -> throw failure("unsupported v002 role " + role);
            }
            if (!identities.add(identity)) throw failure(role + " role contains duplicate identity " + identity);
        }
        if ("metadata".equals(role)) validateMetadataSchedule(rows);
    }

    private static void validateMetadataSchedule(List<ObjectNode> rows) {
        Map<String, Map<Long, MetadataInterval>> byAsset = new java.util.TreeMap<>();
        for (ObjectNode row : rows) {
            String asset = requireAsset(row); long start = Long.parseLong(requireTime(row, "effective_from", "metadata"));
            long end = Long.parseLong(requireTime(row, "effective_until", "metadata"));
            if (end <= start) throw failure("metadata effective interval must be positive");
            int tierIndex = canonicalTierIndex(row);
            MetadataInterval interval = byAsset.computeIfAbsent(asset, ignored -> new java.util.TreeMap<>())
                    .computeIfAbsent(start, ignored -> new MetadataInterval(end));
            if (interval.end != end) throw failure("metadata tiers in one effective interval disagree on end time");
            if (interval.tiers.put(tierIndex, row) != null) throw failure("metadata has duplicate maintenance tier index");
        }
        for (Map.Entry<String, Map<Long, MetadataInterval>> asset : byAsset.entrySet()) {
            long previousEnd = Long.MIN_VALUE;
            for (Map.Entry<Long, MetadataInterval> period : asset.getValue().entrySet()) {
                long start = period.getKey(); MetadataInterval interval = period.getValue();
                if (start < previousEnd) throw failure("historical metadata effective intervals overlap for " + asset.getKey());
                previousEnd = interval.end;
                if (interval.tiers.isEmpty()) throw failure("metadata interval has no maintenance tiers");
                int firstIndex = interval.tiers.firstKey(); int expected = firstIndex;
                if (firstIndex != 1) throw failure("maintenance tier indexes must begin at one");
                double previousCap = 0, previousRate = -1;
                ObjectNode first = interval.tiers.firstEntry().getValue();
                for (Map.Entry<Integer, ObjectNode> tier : interval.tiers.entrySet()) {
                    ObjectNode row = tier.getValue();
                    if (tier.getKey() != expected++) throw failure("maintenance tier indexes must be contiguous");
                    for (String field : List.of("lot_size", "minimum_notional", "taker_fee_rate", "slippage_rate", "liquidation_fee_rate"))
                        if (row.path(field).asDouble() != first.path(field).asDouble()) throw failure("contract costs/filters differ between maintenance tiers");
                    double cap = row.path("tier_notional_cap").asDouble(); double rate = row.path("maintenance_margin_rate").asDouble();
                    if (cap <= previousCap || rate < previousRate) throw failure("maintenance tiers must have increasing notional caps and nondecreasing rates");
                    boolean declaredTerminal = row.path("terminal_tier").asBoolean(false);
                    if (declaredTerminal != (tier.getKey().equals(interval.tiers.lastKey()))) throw failure("exactly the last maintenance tier must declare terminal cap coverage");
                    previousCap = cap; previousRate = rate;
                }
            }
        }
    }

    private static long cadenceMillis(String timeframe) {
        return switch (timeframe) {
            case "1m" -> 60_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1d" -> 86_400_000L;
            default -> throw failure("unsupported frozen timeframe " + timeframe);
        };
    }
    private static void requireAligned(long timestamp, long cadence, String label) {
        if (timestamp % cadence != 0) throw failure(label + " must align to its declared UTC cadence");
    }

    private static String requireTime(ObjectNode row, String field, String role) {
        JsonNode value = row.get(field); if (value == null || value.isNull()) throw failure(role + " requires " + field);
        return Long.toString(canonicalTime(value, role, field));
    }
    private static long canonicalTime(JsonNode value, String role, String field) {
        if (value.isNumber()) {
            if (!value.isIntegralNumber()) throw failure(role + " " + field + " must be integral epoch milliseconds");
            return value.longValue();
        }
        String text = value.asText();
        if (!text.endsWith("Z")) throw failure(role + " " + field + " must be UTC ISO text ending in Z or integral epoch milliseconds");
        try {
            java.time.Instant instant = java.time.Instant.parse(text);
            if (instant.getNano() % 1_000_000 != 0) throw failure(role + " " + field + " cannot contain sub-millisecond precision");
            return instant.toEpochMilli();
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            throw failure(role + " " + field + " must be UTC ISO text ending in Z or integral epoch milliseconds");
        }
    }
    private static void positive(ObjectNode row, String field, boolean allowZero) {
        double value = finite(row, field); if (allowZero ? value < 0 : value <= 0) throw failure(field + " has an invalid value");
    }
    private static double finite(ObjectNode row, String field) {
        JsonNode value = row.get(field); if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) throw failure(field + " must be finite numeric data"); return value.asDouble();
    }
    private static boolean rolesExactly(JsonNode value) {
        if (!value.isObject()) return false; Set<String> keys = new HashSet<>(); value.fieldNames().forEachRemaining(keys::add); return keys.equals(Set.copyOf(ROLES));
    }
    private static String requireAsset(ObjectNode row) {
        String asset = requiredText(row, "asset");
        if (!ASSETS.contains(asset)) throw failure("asset must use the canonical uppercase frozen instrument id");
        return asset;
    }
    private static int canonicalTierIndex(ObjectNode row) {
        JsonNode value = row.get("tier_index");
        if (value == null || value.isNull()) throw failure("metadata requires tier_index");
        try {
            int tier;
            if (value.isIntegralNumber() && value.canConvertToInt()) tier = value.intValue();
            else if (value.isTextual() && value.asText().matches("[1-9][0-9]{0,8}")) tier = Integer.parseInt(value.asText());
            else throw failure("metadata tier_index must be a canonical positive integer");
            if (tier < 1) throw failure("metadata tier_index must be a canonical positive integer");
            return tier;
        } catch (NumberFormatException error) {
            throw failure("metadata tier_index is outside the supported positive integer range");
        }
    }
    private static void validateOhlc(ObjectNode row, String label) {
        for (String field : List.of("open", "high", "low", "close")) positive(row, field, false);
        double open = row.path("open").asDouble(), high = row.path("high").asDouble();
        double low = row.path("low").asDouble(), close = row.path("close").asDouble();
        if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) throw failure(label + " OHLC bounds are inconsistent");
    }
    private static void requireSymbol(String actual, String expected, String label) {
        if (expected == null || !expected.equals(actual)) throw failure(label + " symbol does not match the frozen asset mapping");
    }
    private static ObjectNode object(ObjectNode value, String field) {
        JsonNode result = value.path(field); if (!result.isObject()) throw failure(field + " must be an object"); return (ObjectNode) result;
    }
    private static String requiredText(ObjectNode value, String field) {
        JsonNode result = value.get(field); if (result == null || !result.isTextual() || result.asText().isBlank()) throw failure(field + " must be non-empty text"); return result.asText();
    }
    private static long FilesSize(Path path) {
        try { return Files.size(path); } catch (IOException error) { throw failure("cannot read physical file size: " + error.getMessage()); }
    }
    private static void requireDirectory(Path root, String label) {
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) throw failure(label + " must be an existing non-symlink directory");
        } catch (SecurityException error) { throw failure(label + " cannot be inspected"); }
    }
    private static void safeDirectories(Path root, String relative) {
        Path cursor = root;
        for (String component : relative.split("/")) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw failure("v002 write path contains a symlink or non-directory component");
            }
        }
    }
    private static String parquetSchemaSha(Path parquet) {
        String path = parquet.toAbsolutePath().normalize().toString().replace("'", "''"); ArrayNode rows = JsonHashes.mapper().createArrayNode();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:"); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + path + "')")) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) { ArrayNode row = rows.addArray(); for (int index = 1; index <= metadata.getColumnCount(); index++) { Object item = result.getObject(index); if (item == null) row.addNull(); else row.add(String.valueOf(item)); } }
            return JsonHashes.canonicalSha256(rows);
        } catch (Exception error) { throw failure("DuckDB could not reopen v002 Parquet schema: " + error.getMessage()); }
    }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
    public static final class VerifiedDevelopmentSession {
        private final Path root;
        private final ObjectNode profile, manifest;
        private final boolean partitioned;
        private VerifiedDevelopmentSession(Path root, ObjectNode profile, ObjectNode manifest, boolean partitioned) {
            this.root = root.toAbsolutePath().normalize(); this.profile = profile.deepCopy(); this.manifest = manifest.deepCopy();
            this.partitioned = partitioned;
        }
        public Path root() { return root; }
        public ObjectNode profile() { return profile.deepCopy(); }
        public ObjectNode manifest() { return manifest.deepCopy(); }
        public boolean partitioned() { return partitioned; }
    }
    private record NormalizationResult(boolean recomputed, List<ObjectNode> rows, String status) {}
    private static final class MetadataInterval {
        final long end; final java.util.SortedMap<Integer, ObjectNode> tiers = new java.util.TreeMap<>();
        MetadataInterval(long end) { this.end = end; }
    }

    private record Segment(long start, long end, long count) {}

    private static final class CoverageGroup {
        final String role, asset, series, timeframe, side;
        final long cadence;
        long rowCount;
        final java.util.SortedSet<Long> times = new java.util.TreeSet<>();
        final java.util.SortedMap<Long, Long> effectiveIntervals = new java.util.TreeMap<>();
        final java.util.SortedMap<Long, Long> fundingExpectedEnds = new java.util.TreeMap<>();
        CoverageGroup(String role, String asset, String series, String timeframe, String side, long cadence) {
            this.role = role; this.asset = asset; this.series = series; this.timeframe = timeframe; this.side = side; this.cadence = cadence;
        }
        void addEffectiveInterval(long start, long end) {
            Long prior = effectiveIntervals.putIfAbsent(start, end);
            if (prior != null && prior.longValue() != end) throw failure("metadata tiers disagree on their effective interval");
        }
        ObjectNode toJson() {
            ArrayNode segments = JsonHashes.mapper().createArrayNode();
            if (!times.isEmpty()) {
                long segmentStart = times.first(), previous = segmentStart, count = 1;
                boolean first = true;
                for (long time : times) {
                    if (first) { first = false; continue; }
                    if ("funding".equals(role)) {
                        long expected = fundingExpectedEnds.getOrDefault(previous, previous + 1);
                        if (Math.abs(time - expected) <= 15_000L) { previous = time; count++; }
                        else {
                            addSegment(segments, segmentStart, expected, count);
                            segmentStart = previous = time; count = 1;
                        }
                    } else if (cadence > 0 && time == previous + cadence) { previous = time; count++; }
                    else {
                        addSegment(segments, segmentStart, previous + (cadence > 0 ? cadence : 1), count);
                        segmentStart = previous = time; count = 1;
                    }
                }
                long finalEnd = "funding".equals(role) ? fundingExpectedEnds.getOrDefault(previous, previous + 1)
                        : previous + (cadence > 0 ? cadence : 1);
                addSegment(segments, segmentStart, finalEnd, count);
            }
            ObjectNode value = JsonHashes.mapper().createObjectNode().put("key", coverageKey(role, asset, series, timeframe, side))
                    .put("role", role).put("asset", asset).put("series_id", series).put("timeframe", timeframe)
                    .put("side", side).put("cadence_ms", cadence).put("row_count", rowCount);
            value.set("segments", segments);
            if ("funding".equals(role)) {
                ArrayNode expectedSlots = value.putArray("funding_expected_slots");
                for (Map.Entry<Long, Long> event : fundingExpectedEnds.entrySet()) {
                    long actual = event.getKey(), interval = event.getValue() - actual;
                    expectedSlots.addObject().put("actual_settlement_time_ms", actual)
                            .put("funding_interval_hours", interval / 3_600_000L)
                            .put("expected_next_slot_time_ms", event.getValue());
                }
            }
            if (!effectiveIntervals.isEmpty()) {
                ArrayNode intervals = value.putArray("effective_intervals");
                effectiveIntervals.forEach((start, end) -> intervals.addArray().add(start).add(end));
            }
            return value;
        }
    }

    private static final class AggregateGroup {
        final String key, role, asset, series, timeframe, side;
        final long cadence;
        long rows;
        final List<Segment> segments = new ArrayList<>();
        final java.util.SortedMap<Long, Long> intervals = new java.util.TreeMap<>();
        final java.util.SortedMap<Long, Long> fundingExpectedEnds = new java.util.TreeMap<>();
        AggregateGroup(JsonNode source) {
            key = source.path("key").asText(); role = source.path("role").asText(); asset = source.path("asset").asText();
            series = source.path("series_id").asText(); timeframe = source.path("timeframe").asText(); side = source.path("side").asText();
            cadence = source.path("cadence_ms").asLong();
        }
        ObjectNode toJson() {
            segments.sort(java.util.Comparator.comparingLong(Segment::start));
            List<Segment> merged = new ArrayList<>();
            for (Segment segment : segments) {
                if (merged.isEmpty()) { merged.add(segment); continue; }
                Segment previous = merged.get(merged.size() - 1);
                long delta = segment.start() - previous.end();
                if (cadence > 0 && delta < 0) throw failure("physical coverage partitions duplicate overlapping market timestamps");
                if ("funding".equals(role) && delta < -FUNDING_SLOT_TOLERANCE_MILLIS) throw failure("funding partitions duplicate overlapping settlement slots");
                if ((cadence > 0 && delta == 0) || ("funding".equals(role) && Math.abs(delta) <= FUNDING_SLOT_TOLERANCE_MILLIS)) {
                    merged.set(merged.size() - 1, new Segment(previous.start(), Math.max(previous.end(), segment.end()), previous.count() + segment.count()));
                } else merged.add(segment);
            }
            long maxDays = 0;
            ArrayNode segmentNodes = JsonHashes.mapper().createArrayNode();
            for (Segment segment : merged) {
                addSegment(segmentNodes, segment.start(), segment.end(), segment.count());
                if (cadence > 0 || "funding".equals(role)) maxDays = Math.max(maxDays,
                        java.util.concurrent.TimeUnit.MILLISECONDS.toDays(segment.end() - segment.start()));
            }
            ObjectNode value = JsonHashes.mapper().createObjectNode().put("key", key).put("role", role).put("asset", asset)
                    .put("series_id", series).put("timeframe", timeframe).put("side", side).put("cadence_ms", cadence)
                    .put("row_count", rows).put("max_contiguous_days", maxDays);
            value.set("segments", segmentNodes);
            if ("funding".equals(role)) {
                ArrayNode expectedSlots = value.putArray("funding_expected_slots");
                boolean validSchedule = true;
                Long expectedSlot = null;
                for (Map.Entry<Long, Long> event : fundingExpectedEnds.entrySet()) {
                    long actual = event.getKey(), interval = event.getValue() - actual;
                    if (expectedSlot == null) expectedSlot = actual;
                    long offset = actual - expectedSlot;
                    boolean withinTolerance = Math.abs(offset) <= FUNDING_SLOT_TOLERANCE_MILLIS;
                    validSchedule &= withinTolerance;
                    long alignedSlot = withinTolerance ? expectedSlot : actual;
                    expectedSlots.addObject().put("expected_settlement_slot_time_ms", expectedSlot)
                            .put("actual_settlement_time_ms", actual).put("offset_ms", offset)
                            .put("funding_interval_hours", interval / 3_600_000L)
                            .put("expected_next_slot_time_ms", Math.addExact(alignedSlot, interval))
                            .put("within_frozen_tolerance", withinTolerance);
                    expectedSlot = Math.addExact(alignedSlot, interval);
                }
                value.put("funding_schedule_valid", validSchedule && !fundingExpectedEnds.isEmpty())
                        .put("funding_slot_tolerance_ms", FUNDING_SLOT_TOLERANCE_MILLIS);
            }
            if (!intervals.isEmpty()) {
                ArrayNode intervalNodes = value.putArray("effective_intervals");
                intervals.forEach((start, end) -> intervalNodes.addArray().add(start).add(end));
            }
            return value;
        }
    }

    private static void addSegment(ArrayNode target, long start, long end, long count) {
        target.addArray().add(start).add(end).add(count);
    }
}
