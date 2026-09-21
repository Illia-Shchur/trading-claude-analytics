package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.CoinalyzeDailyData;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Typed source qualification for liquidation-structure/v002. Local hashes prove
 * which bytes were assessed; they do not prove that those bytes were available
 * at their historical event times.
 */
public final class LiquidationInputQualificationV1 {
    public static final String SCHEMA = "liquidation-input-qualification/1";
    public static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final String FROZEN_PRECOMMIT_SHA = "cfae45fac7678a913610c37c156b044ed733e3ecd5e45673b01e412f8eaca904";
    private static final String PINNED_ACQUISITION_BYTES_SHA = "b52d919d1dbaac0e5ad38a5b373b5ed76e981e9fcbb74e6e5d8266d61da04a6e";
    private static final String PINNED_ACQUISITION_CONTENT_SHA = "fbafcfb11c6ef19a8f0d4030ca0bc33d58e8c6a4b5442e7d334e1ab19df584ea";
    private static final Set<String> ASSETS = Set.of("BTC", "ETH", "SOL", "AAVE");
    private static final List<InputSpec> SPECS = List.of(
            new InputSpec("daily_liquidations", true, "USD notional by long/short side", "PROXY_DISCLOSED"),
            new InputSpec("open_interest_base", true, "base-quantity OI snapshots", "UNAVAILABLE"),
            new InputSpec("price_4h", true, "completed Binance USDT perpetual 4h OHLCV", "UNAVAILABLE"),
            new InputSpec("price_1h", true, "completed Binance USDT perpetual 1h OHLCV", "UNAVAILABLE"),
            new InputSpec("trades_marks_1m", true, "eligible 1m trade and mark paths", "UNAVAILABLE"),
            new InputSpec("funding_settlements", true, "actual signed funding events and marks", "UNAVAILABLE"),
            new InputSpec("contract_execution_metadata", true, "historically effective contract, fee and maintenance rules", "UNKNOWN"),
            new InputSpec("sp500_context", false, "completed S&P 500 sessions with release/vintage times", "UNKNOWN"),
            new InputSpec("positioning_ratios", false, "ratio definition and timestamped coverage", "UNAVAILABLE"));

    private LiquidationInputQualificationV1() {}

    /** Reopens the frozen premise and immutable acquisition, then writes a hash-bound audit receipt. */
    public static ObjectNode audit(ObjectNode options) {
        Path precommitPath = requiredPath(options, "precommit");
        Path acquisitionPath = requiredPath(options, "acquisition");
        Path outputPath = requiredPath(options, "out");
        if (outputPath.equals(precommitPath) || outputPath.equals(acquisitionPath)) {
            throw failure("qualification output must not overwrite a bound input");
        }
        byte[] precommitBytes = readBounded(precommitPath, "frozen precommit");
        byte[] acquisitionBytes = readBounded(acquisitionPath, "Coinalyze acquisition manifest");
        ObjectNode precommit = readObject(precommitBytes, "frozen precommit");
        ObjectNode acquisition = readObject(acquisitionBytes, "Coinalyze acquisition manifest");
        validatePrecommit(precommit);
        validateAcquisition(acquisition);
        if (!PINNED_ACQUISITION_BYTES_SHA.equals(JsonHashes.sha256(acquisitionBytes))) {
            throw failure("acquisition is not the retained immutable v002 Coinalyze receipt vintage");
        }

        final ObjectNode normalized;
        try {
            normalized = CoinalyzeDailyData.reopenAndQualify(acquisitionPath);
        } catch (IOException error) {
            throw failure("cannot reopen immutable Coinalyze acquisition: " + error.getMessage());
        }
        ObjectNode receipt = createReceipt(precommit, precommitPath, precommitBytes,
                acquisition, acquisitionPath, acquisitionBytes, normalized.path("rows"));
        writeNew(outputPath, JsonHashes.canonicalBytes(receipt));
        return receipt;
    }

    /** Validates the receipt and reopens every local evidence reference. */
    public static ObjectNode verify(ObjectNode options) {
        Path receiptPath = requiredPath(options, "receipt");
        byte[] bytes = readBounded(receiptPath, "qualification receipt");
        ObjectNode receipt = readObject(bytes, "qualification receipt");
        Reopened reopened = reopenAndCompare(receipt);
        ObjectNode summary = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-input-qualification-verification/1")
                .put("status", "PHYSICAL_BYTES_REOPENED_PROVENANCE_UNRESOLVED")
                .put("receipt_content_sha256", requiredText(receipt, "content_sha256"))
                .put("precommit_byte_sha256", JsonHashes.sha256(reopened.precommitBytes()))
                .put("acquisition_manifest_byte_sha256", JsonHashes.sha256(reopened.acquisitionBytes()))
                .put("historical_availability_proven", false)
                .put("historical_revision_status_proven", false);
        summary.put("content_sha256", JsonHashes.ownHash(summary));
        return summary;
    }

    /**
     * Fail-closed evidence tier mapping. Caller assertions of VERIFIED are never
     * accepted as proof; the current v002 source receipt has no verified inputs.
     */
    public static ObjectNode assessUse(ObjectNode receipt, String requestedUse) {
        reopenAndCompare(receipt);
        if (!Set.of("DAILY_STRESS_DIAGNOSTIC", "DEVELOPMENT_REPLAY", "WALK_FORWARD_OOS",
                "SEALED_CONFIRMATION", "PROSPECTIVE_LIVE").contains(requestedUse)) {
            throw failure("unsupported evidence use " + requestedUse);
        }
        boolean authoritativeRequest = !Set.of("DAILY_STRESS_DIAGNOSTIC", "DEVELOPMENT_REPLAY").contains(requestedUse);
        ArrayNode gaps = JsonHashes.mapper().createArrayNode();
        for (JsonNode input : receipt.path("inputs")) {
            if (!input.path("required").asBoolean(false)) continue;
            String status = input.path("qualification_status").asText();
            if (!"VERIFIED".equals(status)) gaps.add(input.path("series_id").asText() + ":" + status);
        }
        boolean physicallyMissing = false;
        for (JsonNode input : receipt.path("inputs")) {
            if (input.path("required").asBoolean(false)
                    && "UNAVAILABLE".equals(input.path("qualification_status").asText())) physicallyMissing = true;
        }
        boolean permitted = !authoritativeRequest && (!physicallyMissing || "DAILY_STRESS_DIAGNOSTIC".equals(requestedUse));
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-input-evidence-use/1")
                .put("requested_use", requestedUse)
                .put("permitted", permitted)
                .put("evidence_tier", permitted ? "DEVELOPMENT" : "BLOCKED")
                .put("authoritative", false)
                .put("reason", authoritativeRequest
                        ? "No verified historical source-vintage or execution receipts are present."
                        : physicallyMissing && !"DAILY_STRESS_DIAGNOSTIC".equals(requestedUse)
                                ? "A required physical series is unavailable; do not synthesize or silently omit it."
                                : "Permitted diagnostic use only; historical availability and revision provenance remain unresolved.");
        result.set("unresolved_required_inputs", gaps);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /**
     * Reopens a physical development manifest and derives the only execution
     * qualification available to v002. Proxy inputs may qualify for explicitly
     * disclosed development replay; historical vintages and authoritative
     * evidence remain false regardless of manifest assertions.
     */
    public static ObjectNode assessPhysicalDevelopment(ObjectNode physicalOptions) {
        ObjectNode profile = object(physicalOptions, "profile");
        ObjectNode manifest = object(physicalOptions, "manifest");
        LiquidationDailyStressProfileV1.validate(profile);
        ObjectNode verification = manifest.path("artifacts").path("feature").path("partitions").isArray()
                ? LiquidationV2PhysicalDataV1.verifyDevelopment(physicalOptions)
                : LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(physicalOptions);
        String sourceMode = manifest.path("source_mode").asText("SYNTHETIC_DEVELOPMENT_ONLY");
        boolean proxyMode = "PROXY_DISCLOSED_DEVELOPMENT_ONLY".equals(sourceMode);
        boolean transformationsRecomputed = verification.path("all_source_transformations_recomputed").asBoolean(false);
        boolean coverageInventoryComplete = verification.path("coverage_inventory_complete").asBoolean(false);
        boolean replayPermitted = proxyMode && transformationsRecomputed && coverageInventoryComplete
                && verification.path("development_replay_permitted").asBoolean(false);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-physical-development-qualification/1")
                .put("version", 1).put("status", replayPermitted ? "PROXY_DISCLOSED_DEVELOPMENT_REPLAY_PERMITTED"
                        : "DEVELOPMENT_REPLAY_BLOCKED")
                .put("evidence_tier", replayPermitted ? "PROXY_DISCLOSED_DEVELOPMENT_ONLY" : "BLOCKED")
                .put("source_mode", sourceMode)
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("manifest_sha256", verification.path("manifest_sha256").asText())
                .put("dataset_root_sha256", verification.path("dataset_root_sha256").asText())
                .put("physical_verification_sha256", verification.path("content_sha256").asText())
                .put("coverage_inventory_complete", coverageInventoryComplete)
                .put("all_source_transformations_recomputed", transformationsRecomputed)
                .put("development_replay_permitted", replayPermitted)
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative_wfo_permitted", false).put("sealed_evidence_permitted", false)
                .put("prospective_live_permitted", false).put("trade_authorization_permitted", false)
                .put("reason", replayPermitted
                        ? "Physical inputs were reopened and bounded development coverage was recomputed; historical availability and revision provenance remain unproven."
                        : "Physical development replay remains blocked by incomplete coverage, an unrecomputed transformation, or synthetic-only source mode.");
        receipt.set("physical_verification", verification);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    /** Reopens all physical inputs again and rejects an edited or stale qualification assessment. */
    public static ObjectNode verifyPhysicalDevelopmentAssessment(ObjectNode physicalOptions, ObjectNode assessment) {
        ObjectNode reopened = assessPhysicalDevelopment(physicalOptions);
        if (!JsonHashes.canonicalSha256(reopened).equals(JsonHashes.canonicalSha256(assessment))) {
            throw failure("physical development qualification does not match reopened source and Parquet bytes");
        }
        return JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-physical-development-qualification-verification/1")
                .put("status", "REOPENED_DEVELOPMENT_ONLY")
                .put("qualification_sha256", reopened.path("content_sha256").asText())
                .put("manifest_sha256", reopened.path("manifest_sha256").asText())
                .put("dataset_root_sha256", reopened.path("dataset_root_sha256").asText())
                .put("development_replay_permitted", reopened.path("development_replay_permitted").asBoolean(false))
                .put("historical_availability_proven", false).put("authoritative_wfo_permitted", false)
                .put("content_sha256", JsonHashes.ownHash(JsonHashes.mapper().createObjectNode()
                        .put("schema", "liquidation-v2-physical-development-qualification-verification/1")
                        .put("status", "REOPENED_DEVELOPMENT_ONLY").put("qualification_sha256", reopened.path("content_sha256").asText())
                        .put("manifest_sha256", reopened.path("manifest_sha256").asText())
                        .put("dataset_root_sha256", reopened.path("dataset_root_sha256").asText())
                        .put("development_replay_permitted", reopened.path("development_replay_permitted").asBoolean(false))
                        .put("historical_availability_proven", false).put("authoritative_wfo_permitted", false)));
    }

    static ObjectNode createReceipt(ObjectNode precommit, Path precommitPath, byte[] precommitBytes,
            ObjectNode acquisition, Path acquisitionPath, byte[] acquisitionBytes, JsonNode rows) {
        Map<String, Map<LocalDate, JsonNode>> byAsset = dailyCoverage(rows);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", SCHEMA).put("version", 1)
                .put("status", "BLOCKED_FOR_AUTHORITATIVE_RESEARCH")
                .put("strategy_family", "liquidation-structure")
                .put("strategy_version", "liquidation-daily-stress-v002")
                .put("precommit_id", requiredText(precommit, "precommit_id"))
                .put("precommit_content_sha256", requiredText(precommit, "content_sha256"))
                .put("precommit_byte_sha256", JsonHashes.sha256(precommitBytes))
                .put("assessed_at", Instant.now().toString())
                .put("source_window_start", "2022-08-11")
                .put("source_window_end_exclusive", "2026-09-20")
                .put("decision_window_start", "2022-11-11T00:00:00Z")
                .put("decision_window_end_exclusive", "2026-07-15T00:00:00Z")
                .put("execution_window_end_exclusive", "2026-09-20T00:00:00Z")
                .put("historical_availability_proven", false)
                .put("historical_revision_proven", false)
                .put("verified_input_count", 0)
                .put("authoritative_wfo_permitted", false)
                .put("sealed_evidence_permitted", false);
        receipt.set("asset_scope", JsonHashes.mapper().valueToTree(List.of("BTC", "ETH", "SOL", "AAVE")));
        receipt.set("precommit_ref", localRef(precommitPath, precommitBytes, requiredText(precommit, "content_sha256")));
        receipt.set("acquisition_manifest_ref", localRef(acquisitionPath, acquisitionBytes, requiredText(acquisition, "content_sha256")));
        ArrayNode inputs = receipt.putArray("inputs");
        for (InputSpec spec : SPECS) {
            ObjectNode input = inputs.addObject().put("series_id", spec.id()).put("required", spec.required())
                    .put("units", spec.units()).put("qualification_status", spec.status())
                    .put("availability_status", "UNKNOWN").put("revision_status", "UNKNOWN")
                    .put("coverage_sufficient_for_intended_use", false)
                    .put("permitted_evidence_use", spec.id().equals("daily_liquidations")
                            ? "DAILY_STRESS_DIAGNOSTIC_AND_DEVELOPMENT_ONLY"
                            : spec.required() ? "BLOCKED_UNTIL_PHYSICALLY_ACQUIRED_AND_QUALIFIED" : "DIAGNOSTIC_ONLY");
            input.put("event_time_contract", spec.id().equals("daily_liquidations")
                    ? "UTC day [t,t+24h); assumed usable at t+48h; assumption only"
                    : "Not established by currently retained physical receipts");
            input.put("documented_availability_at", (String) null);
            input.put("verified_availability_at", (String) null);
            input.put("retrieval_at", spec.id().equals("daily_liquidations")
                    ? requiredText(acquisition, "captured_at") : null);
            input.put("observed_value_vintage", spec.id().equals("daily_liquidations")
                    ? "Current retrospective API response captured once; vintage cannot be reconstructed"
                    : "No physical observations retained for this required/optional series");
            input.set("coverage", spec.id().equals("daily_liquidations")
                    ? coverageNode(byAsset) : JsonHashes.mapper().createObjectNode()
                            .put("observations", 0).put("missing_ranges", "Not measured; series not acquired"));
            input.set("evidence_refs", JsonHashes.mapper().createArrayNode());
            if (spec.id().equals("daily_liquidations")) {
                input.withArray("evidence_refs").addObject()
                        .put("kind", "PHYSICAL_ACQUISITION")
                        .put("path", acquisitionPath.toAbsolutePath().normalize().toString())
                        .put("byte_sha256", JsonHashes.sha256(acquisitionBytes))
                        .put("content_sha256", requiredText(acquisition, "content_sha256"))
                        .put("source", "Coinalyze API")
                        .put("claim", "Retrieved historical rows and integrity only; first-publication time and historical vintage remain unknown.");
                input.withArray("evidence_refs").addObject()
                        .put("kind", "PUBLIC_DOCUMENTATION")
                        .put("url", "https://coinalyze.net/faq/")
                        .put("claim", "Historical retention does not establish the time an exact historical value first became observable.");
            }
        }
        ArrayNode limits = receipt.putArray("limitations");
        limits.add("The t+48h daily-liquidation availability rule is a disclosed development assumption, not historical publication evidence.");
        limits.add("Binance public archives may arrive later and be updated; archive checksums bind acquired bytes, not original event-time availability or vintage.");
        limits.add("No retained physical base-quantity OI endpoint archive, 4h/1h bars, 1m trade/mark path, funding ledger, or historical execution metadata supports a core execution replay.");
        limits.add("A current API response or caller-supplied VERIFIED label cannot establish historical point-in-time provenance.");
        return withHash(receipt);
    }

    public static void validateReceipt(ObjectNode receipt) {
        if (!SCHEMA.equals(receipt.path("schema").asText()) || receipt.path("version").asInt(-1) != 1
                || !"liquidation-daily-stress-v002".equals(receipt.path("strategy_version").asText())
                || !"BLOCKED_FOR_AUTHORITATIVE_RESEARCH".equals(receipt.path("status").asText())
                || !exactStrings(receipt.path("asset_scope"), List.of("BTC", "ETH", "SOL", "AAVE"))
                || !"2022-08-11".equals(receipt.path("source_window_start").asText())
                || !"2026-09-20".equals(receipt.path("source_window_end_exclusive").asText())
                || !"2022-11-11T00:00:00Z".equals(receipt.path("decision_window_start").asText())
                || !"2026-07-15T00:00:00Z".equals(receipt.path("decision_window_end_exclusive").asText())
                || !"2026-09-20T00:00:00Z".equals(receipt.path("execution_window_end_exclusive").asText())) {
            throw failure("unsupported liquidation input qualification receipt");
        }
        if (!requiredText(receipt, "content_sha256").equals(JsonHashes.ownHash(receipt))) {
            throw failure("qualification receipt content hash mismatch");
        }
        if (receipt.path("historical_availability_proven").asBoolean(true)
                || receipt.path("historical_revision_proven").asBoolean(true)
                || receipt.path("verified_input_count").asInt(-1) != 0
                || receipt.path("authoritative_wfo_permitted").asBoolean(true)
                || receipt.path("sealed_evidence_permitted").asBoolean(true)) {
            throw failure("v002 qualification cannot claim verified vintages or authoritative evidence");
        }
        if (!receipt.path("inputs").isArray() || receipt.path("inputs").size() != SPECS.size()) {
            throw failure("qualification receipt inputs must match the frozen series inventory");
        }
        Set<String> found = new HashSet<>();
        for (int index = 0; index < receipt.path("inputs").size(); index++) {
            JsonNode input = receipt.path("inputs").get(index);
            InputSpec spec = SPECS.get(index);
            String id = input.path("series_id").asText();
            if (!spec.id().equals(id) || !found.add(id)
                    || input.path("required").asBoolean(!spec.required()) != spec.required()
                    || !spec.status().equals(input.path("qualification_status").asText())
                    || !"UNKNOWN".equals(input.path("availability_status").asText())
                    || !"UNKNOWN".equals(input.path("revision_status").asText())) {
                throw failure("qualification receipt has a changed frozen qualification for " + id);
            }
            if (input.path("coverage_sufficient_for_intended_use").asBoolean(true)) {
                throw failure("no v002 source coverage is yet qualified for its full intended use");
            }
        }
        if (found.size() != SPECS.size()) throw failure("qualification receipt omits a frozen required or diagnostic series");
        ObjectNode acquisitionRef = object(receipt, "acquisition_manifest_ref");
        if (!validSha(requiredText(acquisitionRef, "byte_sha256"))
                || !validSha(requiredText(acquisitionRef, "content_sha256"))) {
            throw failure("acquisition reference is not hash-bound");
        }
    }

    private static Map<String, Map<LocalDate, JsonNode>> dailyCoverage(JsonNode rows) {
        Map<String, Map<LocalDate, JsonNode>> result = new HashMap<>();
        for (String asset : ASSETS) result.put(asset, new TreeMap<>());
        if (!rows.isArray()) throw failure("reopened Coinalyze rows are not an array");
        for (JsonNode row : rows) {
            String asset = row.path("asset").asText();
            if (!ASSETS.contains(asset)) throw failure("unexpected asset in daily liquidation rows");
            LocalDate day = Instant.parse(row.path("day_start_utc").asText()).atZone(ZoneOffset.UTC).toLocalDate();
            if (result.get(asset).put(day, row) != null) throw failure("duplicate daily liquidation bucket in reopened rows");
        }
        return result;
    }

    private static ObjectNode coverageNode(Map<String, Map<LocalDate, JsonNode>> byAsset) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("source", "Coinalyze API").put("symbol_scope", "Binance USDT perpetual proxy per acquisition manifest")
                .put("window_start", "2022-08-11").put("window_end_exclusive", "2026-09-20");
        ArrayNode assets = result.putArray("by_asset");
        LocalDate from = LocalDate.parse("2022-08-11"), to = LocalDate.parse("2026-09-20");
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            Map<LocalDate, JsonNode> rows = byAsset.get(asset);
            List<String> missing = new ArrayList<>();
            for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) if (!rows.containsKey(day)) missing.add(day.toString());
            ObjectNode summary = assets.addObject().put("asset", asset)
                    .put("observed_rows", rows.size()).put("expected_days", ChronoUnit.DAYS.between(from, to))
                    .put("missing_days", missing.size())
                    .put("first_observed", rows.isEmpty() ? "" : rows.keySet().stream().min(LocalDate::compareTo).orElseThrow().toString())
                    .put("last_observed", rows.isEmpty() ? "" : rows.keySet().stream().max(LocalDate::compareTo).orElseThrow().toString());
            ArrayNode missingRanges = summary.putArray("missing_dates");
            missing.forEach(missingRanges::add);
        }
        return result;
    }

    private static ObjectNode localRef(Path path, byte[] bytes, String contentSha) {
        return JsonHashes.mapper().createObjectNode().put("path", path.toAbsolutePath().normalize().toString())
                .put("byte_sha256", JsonHashes.sha256(bytes)).put("content_sha256", contentSha).put("bytes", bytes.length);
    }

    private static void validatePrecommit(ObjectNode value) {
        if (!"strategy-precommit/1".equals(value.path("schema").asText())
                || !"liquidation-daily-stress-v002".equals(value.path("precommit_id").asText())
                || !"FROZEN".equals(value.path("status").asText())
                || !FROZEN_PRECOMMIT_SHA.equals(requiredText(value, "content_sha256"))
                || !requiredText(value, "content_sha256").equals(JsonHashes.ownHash(value))
                || !"2022-08-11T00:00:00Z".equals(value.path("research_window").path("source_start").asText())
                || !"2026-09-20T00:00:00Z".equals(value.path("research_window").path("source_end_exclusive").asText())
                || !"2022-11-11T00:00:00Z".equals(value.path("research_window").path("decision_start").asText())
                || !"2026-07-15T00:00:00Z".equals(value.path("research_window").path("decision_end_exclusive").asText())
                || !"2026-09-20T00:00:00Z".equals(value.path("research_window").path("execution_end_exclusive").asText())
                || value.path("research_window").path("minimum_purge_days").asInt(-1) != 67
                || value.path("research_window").path("embargo_days").asInt(-1) != 7
                || value.path("holding_horizon").path("max").asInt(-1) != 60
                || value.path("daily_stress_contract").path("modeled_available_after_bucket_start_seconds").asInt(-1) != 172800
                || value.path("daily_stress_contract").path("prior_contiguous_days").asInt(-1) != 90
                || value.path("daily_stress_contract").path("percentile").asDouble(-1) != 0.95
                || !exactStrings(value.path("trade_assets"), List.of("btc", "eth", "sol", "aave"))
                || value.path("user_constraints").path("starting_equity_usdt").asDouble(-1) != 20000
                || value.path("user_constraints").path("position_risk_fraction_cap").asDouble(-1) != 0.05
                || value.path("user_constraints").path("portfolio_risk_fraction_cap").asDouble(-1) != 0.10
                || value.path("user_constraints").path("max_holding_days").asInt(-1) != 60
                || !value.path("tradable_instrument_contract").path("instruments").isArray()
                || value.path("tradable_instrument_contract").path("instruments").size() != 4) {
            throw failure("precommit must be the hash-valid frozen liquidation-daily-stress-v002 artifact");
        }
        for (JsonNode instrument : value.path("tradable_instrument_contract").path("instruments")) {
            if (!"perpetual".equals(instrument.path("instrument_type").asText())
                    || !"binance".equals(instrument.path("venue").asText())
                    || !"USDT".equals(instrument.path("collateral").asText())
                    || !"ISOLATED".equals(instrument.path("margin_mode").asText())) {
                throw failure("frozen precommit instruments must remain Binance USDT isolated perpetuals");
            }
        }
    }

    private static void validateAcquisition(ObjectNode value) {
        if (!"coinalyze-daily-acquisition/1".equals(value.path("schema").asText())
                || !value.path("immutable").asBoolean(false) || !value.path("diagnostic").asBoolean(false)
                || value.path("pit_verified").asBoolean(true)
                || !"RETROSPECTIVE_UNKNOWN".equals(value.path("source_vintage_status").asText())
                || !"2022-08-11".equals(value.path("from_inclusive").asText())
                || !"2026-09-20".equals(value.path("to_exclusive").asText())
                || !"2026-09-20T00:00:00Z".equals(value.path("as_of").asText())
                || !PINNED_ACQUISITION_CONTENT_SHA.equals(requiredText(value, "content_sha256"))
                || !requiredText(value, "content_sha256").equals(JsonHashes.ownHash(value))) {
            throw failure("daily liquidation acquisition must remain a hash-valid retrospective diagnostic");
        }
    }

    private static Reopened reopenAndCompare(ObjectNode receipt) {
        validateReceipt(receipt);
        ObjectNode precommitRef = object(receipt, "precommit_ref");
        ObjectNode acquisitionRef = object(receipt, "acquisition_manifest_ref");
        Path precommitPath = Path.of(requiredText(precommitRef, "path")).toAbsolutePath().normalize();
        Path acquisitionPath = Path.of(requiredText(acquisitionRef, "path")).toAbsolutePath().normalize();
        byte[] precommitBytes = readBounded(precommitPath, "frozen precommit");
        byte[] acquisitionBytes = readBounded(acquisitionPath, "Coinalyze acquisition manifest");
        if (!JsonHashes.sha256(precommitBytes).equals(requiredText(precommitRef, "byte_sha256"))
                || !JsonHashes.sha256(acquisitionBytes).equals(requiredText(acquisitionRef, "byte_sha256"))
                || !PINNED_ACQUISITION_BYTES_SHA.equals(JsonHashes.sha256(acquisitionBytes))) {
            throw failure("a frozen physical source byte identity changed");
        }
        ObjectNode precommit = readObject(precommitBytes, "frozen precommit");
        ObjectNode acquisition = readObject(acquisitionBytes, "Coinalyze acquisition manifest");
        validatePrecommit(precommit);
        validateAcquisition(acquisition);
        if (!requiredText(precommit, "content_sha256").equals(requiredText(precommitRef, "content_sha256"))
                || !requiredText(acquisition, "content_sha256").equals(requiredText(acquisitionRef, "content_sha256"))) {
            throw failure("a frozen physical source content identity changed");
        }
        final ObjectNode normalized;
        try { normalized = CoinalyzeDailyData.reopenAndQualify(acquisitionPath); }
        catch (IOException error) { throw failure("Coinalyze raw response receipt cannot be reopened: " + error.getMessage()); }
        ObjectNode expected = createReceipt(precommit, precommitPath, precommitBytes, acquisition,
                acquisitionPath, acquisitionBytes, normalized.path("rows"));
        expected.put("assessed_at", requiredText(receipt, "assessed_at"));
        expected.put("content_sha256", JsonHashes.ownHash(expected));
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(receipt))) {
            throw failure("qualification content does not match the reopened frozen source receipts");
        }
        return new Reopened(precommitBytes, acquisitionBytes);
    }

    private static boolean exactStrings(JsonNode array, List<String> expected) {
        if (!array.isArray() || array.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(array.get(index).asText())) return false;
        }
        return true;
    }

    private static ObjectNode withHash(ObjectNode value) { value.put("content_sha256", JsonHashes.ownHash(value)); return value; }
    private static ObjectNode readObject(byte[] bytes, String label) {
        try {
            JsonNode value = JsonHashes.mapper().readTree(bytes);
            if (value == null || !value.isObject()) throw failure(label + " must be a JSON object");
            return (ObjectNode) value;
        } catch (IOException error) { throw failure("cannot parse " + label + ": " + error.getMessage()); }
    }
    private static byte[] readBounded(Path path, String label) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(absolute) > MAX_JSON_BYTES) throw failure(label + " must be a bounded regular file");
            return PathConfinement.readSinglyLinkedFile(absolute, label);
        } catch (IOException error) { throw failure("cannot read " + label + ": " + error.getMessage()); }
    }
    private static void writeNew(Path path, byte[] bytes) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw failure("qualification output already exists");
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (IOException error) { throw failure("cannot write qualification receipt: " + error.getMessage()); }
    }
    private static Path requiredPath(ObjectNode object, String field) {
        return Path.of(requiredText(object, field)).toAbsolutePath().normalize();
    }
    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw failure("" + field + " is required");
        return value.asText();
    }
    private static ObjectNode object(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw failure(field + " must be an object");
        return (ObjectNode) value;
    }
    private static boolean validSha(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }

    private record InputSpec(String id, boolean required, String units, String status) {}
    private record Reopened(byte[] precommitBytes, byte[] acquisitionBytes) {}
}
