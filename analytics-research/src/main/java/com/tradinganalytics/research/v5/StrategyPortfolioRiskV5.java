package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Java 21 port of tools/strategy-portfolio-risk-v5.mjs. */
public final class StrategyPortfolioRiskV5 {
    public static final String METADATA_SCHEMA = "strategy-v5-metadata-receipt/1";
    public static final String EXECUTION_SCHEMA = "strategy-execution-fill-artifact/1";
    public static final String SELECTED_SCHEMA = "strategy-selected-trades/1";
    public static final String EVALUATION_SCHEMA = "strategy-selected-evaluation/1";
    private static final String MARK_SCHEMA = "strategy-mark-artifact/1";
    private static final String RISK_SCHEMA = "strategy-portfolio-risk/1";
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> CRYPTO = Set.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    private static final Set<String> MARK_SERIES = Set.of("TRADE_MARK", "RISK_REFERENCE", "COLLATERAL_FX", "LIQUIDATION_MARK", "FUNDING_MARK");
    private static final Set<String> DERIVATIVE = Set.of("perpetual", "perp", "dated_future", "future", "futures");

    private StrategyPortfolioRiskV5() {}

    public static String hash(JsonNode value) { return JsonHashes.canonicalSha256(finiteJson(value)); }
    public static String hash(String value) { return JsonHashes.sha256(value); }
    public static String hash(byte[] value) { return JsonHashes.sha256(value); }
    public static String hash(Object value) { return value instanceof byte[] b ? hash(b) : value instanceof String s ? hash(s) : hash(MAPPER.valueToTree(value)); }

    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }
    public static String ownHash(JsonNode value, String field) {
        JsonNode copy = value == null ? NullNode.instance : finiteJson(value.deepCopy());
        if (copy instanceof ObjectNode object) object.remove(field);
        return hash(copy);
    }

    public static ObjectNode evaluatePortfolioRiskV5(ObjectNode request) { return evaluatePortfolioRiskV5((JsonNode) request); }
    public static ObjectNode evaluatePortfolioRiskV5(JsonNode request) {
        ObjectNode r = objectOrEmpty(request); ObjectNode policy = objectOrEmpty(r.get("policy"));
        ObjectNode artifact = loadMarkForEvaluation(r, policy);
        ObjectNode metadata = loadMetadata(r.get("metadata"), strictTrue(policy.get("allow_fixture_metadata")));
        boolean fixtureRun = strictTrue(policy.get("execution_fixture")) || strictTrue(policy.get("allow_fixture_metadata"));
        if (!fixtureRun) { var fields = metadata.fields(); while (fields.hasNext()) { JsonNode item = fields.next().getValue(); if (strictTrue(item.get("fixture_only"))) { fixtureRun = true; break; } } }
        ArrayNode trades = arrayOrEmpty(r.get("trades")); ObjectNode execution = loadExecution(r, policy, fixtureRun);
        ObjectNode selected = loadSelected(r, fixtureRun), evaluation = loadEvaluation(r, fixtureRun), stress = loadStress(r);
        if (!fixtureRun && (selected == null || evaluation == null || stress == null)) throw error("authoritative portfolio risk requires physical selected-trade, outer-evaluation, and stress artifacts");
        if (!fixtureRun && trades.size() > 0) throw error("authoritative portfolio risk does not accept caller trade inventory");
        if (selected != null && evaluation != null && !text(selected.get("evaluation_sha256")).equals(text(evaluation.get("content_sha256")))) throw error("selected-trade/evaluation lineage mismatch");
        if (selected != null && evaluation != null && !text(evaluation.get("selected_trades_sha256")).equals(hash(selected.path("rows")))) throw error("outer evaluation does not bind exact selected-trade rows");
        if (!fixtureRun && selected != null) trades = arrayOrEmpty(selected.get("rows"));
        return evaluateTrades(trades, artifact, execution, metadata, r, policy, selected, evaluation, stress, fixtureRun);
    }

    private static ObjectNode evaluateTrades(ArrayNode trades, ObjectNode artifact, ObjectNode execution, ObjectNode metadata, ObjectNode request, ObjectNode policy, ObjectNode selected, ObjectNode evaluation, ObjectNode stress, boolean fixtureRun) {
        String accountCurrency = textOr(first(request, "accountCurrency", "account_currency"), "USDT").toLowerCase(Locale.ROOT); Set<String> required = new LinkedHashSet<>(); for (JsonNode n : arrayOrEmpty(first(request, "requiredAssets", "required_assets"))) required.add(text(n).toLowerCase(Locale.ROOT));
        List<ObjectNode> accepted = new ArrayList<>(); ArrayNode rejected = MAPPER.createArrayNode(); List<String> failures = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < trades.size(); i++) { ObjectNode trade = objectOrEmpty(trades.get(i)); String id = textOr(trade.get("signal_id"), textOr(trade.get("trade_id"), "trade-" + (i + 1))); if (!seen.add(id)) throw error("duplicate trade id " + id); ArrayList<String> reasons = new ArrayList<>(); if (hasForbiddenField(trade)) reasons.add("CALLER_PRECOMPUTED_RISK_REJECTED"); long entry, exit; try { entry = millis(trade.get("entry_time")); exit = millis(trade.get("exit_time")); } catch (RuntimeException ex) { ObjectNode invalid = object().put("signal_id", id); invalid.set("reasons", strings(List.of("INVALID_LIFECYCLE", ex.getMessage()))); rejected.add(invalid); continue; }
            String asset = text(trade.get("asset")).toLowerCase(Locale.ROOT), symbol = text(trade.get("symbol")).toUpperCase(Locale.ROOT), direction = text(trade.get("direction")).toLowerCase(Locale.ROOT); double quantity = number(trade.get("quantity")); if (!(entry < exit)) reasons.add("INVALID_LIFECYCLE"); if (!direction.equals("long") && !direction.equals("short")) reasons.add("INVALID_DIRECTION"); if (!(quantity > 0)) reasons.add("INVALID_QUANTITY");
            ObjectNode er = findExecution(execution, id), em = exactMark(artifact, "TRADE_MARK", asset, symbol, entry), xm = exactMark(artifact, "TRADE_MARK", asset, symbol, exit); if (er == null && !strictTrue(policy.get("execution_fixture")) && (em == null || xm == null)) reasons.add("EXACT_ENTRY_OR_EXIT_TRADE_MARK_MISSING"); if (er == null && !strictTrue(policy.get("execution_fixture"))) reasons.add("EXECUTION_FILL_ARTIFACT_MISSING"); double ep = er == null ? number(em == null ? null : em.get("price")) : number(er.get("entry_price")), xp = er == null ? number(xm == null ? null : xm.get("price")) : number(er.get("exit_price")); if (!(ep > 0) || !(xp > 0)) reasons.add("EXACT_ENTRY_OR_EXIT_FILL_MISSING");
            TradeMeta meta = validateTradeMeta(trade, artifact, metadata, policy, entry, exit, ep, xp, fixtureRun); reasons.addAll(meta.failures); if (!reasons.isEmpty()) { ObjectNode rejectedTrade = object().put("signal_id", id).put("asset", asset); rejectedTrade.set("reasons", strings(new ArrayList<>(new LinkedHashSet<>(reasons)))); rejected.add(rejectedTrade); failures.addAll(reasons); continue; }
            ObjectNode out = trade.deepCopy(); out.put("signal_id", id).put("asset", asset).put("symbol", symbol).put("venue", text(trade.get("venue")).toLowerCase(Locale.ROOT)).put("instrument_type", text(trade.get("instrument_type")).toLowerCase(Locale.ROOT)).put("direction", direction).put("quantity", quantity).put("entry_time", entry).put("exit_time", exit).put("entry_fill_price", ep).put("exit_fill_price", xp).put("entry_price", ep).put("exit_price", xp).put("entry_mark_price", em == null ? ep : number(em.get("price"))).put("exit_mark_price", xm == null ? xp : number(xm.get("price"))).put("multiplier", meta.multiplier).put("entry_notional", meta.entryNotional).put("exit_notional", meta.exitNotional).put("notional", meta.entryNotional).put("entry_fees", meta.entryFees).put("exit_fees", meta.exitFees).put("fees", meta.expectedFees).put("funding_pnl", meta.fundingTotal);
            out.set("funding_rows", meta.fundingRows); out.put("collateral_account", meta.collateralAccount).put("collateral_asset", meta.collateralAsset).putNull("liquidation_price"); out.set("liquidation_path", meta.liquidationPath); out.put("maintenance_margin_ratio", meta.maintenance).put("margin_mode", meta.marginMode).put("risk_amount", meta.riskAmount); out.set("contract_expiry", nullable(meta.expiry)); out.set("contract_settlement", nullable(meta.settlement)); out.put("fee_artifact_sha256", nullableHash(metadata, "fee")).put("contract_artifact_sha256", nullableHash(metadata, "contract")).put("funding_artifact_sha256", nullableHash(metadata, "funding")); accepted.add(out);
        }
        return finishRisk(accepted, rejected, failures, artifact, metadata, request, policy, required, selected, evaluation, execution, stress, fixtureRun, accountCurrency);
    }

    public static ObjectNode withHash(ObjectNode value) { return withHash(value, "content_sha256"); }
    public static ObjectNode withHash(ObjectNode value, String field) {
        ObjectNode copy = (ObjectNode) finiteJson(value.deepCopy());
        copy.put(field, ownHash(copy, field));
        return copy;
    }
    public static JsonNode withHash(JsonNode value, String field) { return withHash((ObjectNode) value, field); }

    public static ObjectNode readBoundMarkArtifact(ObjectNode options) { return readBoundMarkArtifact((JsonNode) options); }
    public static ObjectNode readBoundMarkArtifact(JsonNode options) {
        ObjectNode o = objectOrEmpty(options);
        String path = text(o.get("path"));
        String supplied = text(o.get("sha256"));
        if (path.isEmpty() || !Files.isRegularFile(resolve(path))) throw error("physical artifact is missing");
        requireHash(supplied, "artifact byte hash");
        byte[] bytes = readBytes(resolve(path));
        if (!hash(bytes).equals(supplied)) throw error("artifact byte hash mismatch");
        ObjectNode raw = parseObject(bytes, "physical artifact");
        if (!ownHash(raw).equals(text(raw.get("content_sha256")))) throw error("physical artifact content hash is invalid");
        String schema = text(raw.get("schema"));
        JsonNode allowed = o.get("schemas");
        if (allowed != null && allowed.isArray() && !contains(allowed, schema)) throw error("unsupported physical artifact schema: " + schema);
        SCHEMAS.validateContractSchema(raw);
        if (!MARK_SCHEMA.equals(schema)) throw error("unsupported physical artifact schema: " + schema);
        String venue = text(raw.get("venue"));
        if (venue.isEmpty() || !(number(raw.get("interval_ms")) > 0) || !raw.path("rows").isArray() || raw.path("rows").isEmpty()) throw error("mark artifact requires venue, interval_ms and rows");
        boolean allowFixture = truth(o.get("allowFixture"), o.get("allow_fixture"));
        String provenance = text(raw.get("provenance"));
        if ("FIXTURE".equals(provenance) && !allowFixture) throw error("fixture mark artifact is not admissible for authoritative portfolio risk");
        if (!("FIXTURE".equals(provenance) || "AUTHORITATIVE_RECOMPUTED".equals(provenance))) throw error("mark artifact provenance is invalid");
        if ("AUTHORITATIVE_RECOMPUTED".equals(provenance)) verifyAuthoritativeBinding(raw, raw);
        double interval = number(raw.get("interval_ms"));
        String expectedVenue = text(first(o, "expectedVenue", "expected_venue"));
        if (!expectedVenue.isEmpty() && !venue.equalsIgnoreCase(expectedVenue)) throw error("mark artifact venue mismatch");
        JsonNode expectedInterval = first(o, "expectedIntervalMs", "expected_interval_ms");
        if (expectedInterval != null && !expectedInterval.isNull() && interval != number(expectedInterval)) throw error("mark artifact cadence mismatch");
        JsonNode cutoffValue = first(o, "consumingCutoff", "consuming_cutoff", "asOf", "as_of");
        Long cutoff = cutoffValue == null || cutoffValue.isNull() ? null : millis(cutoffValue);
        ArrayList<ObjectNode> normalized = new ArrayList<>();
        int index = 0;
        for (JsonNode row : raw.path("rows")) {
            ObjectNode n = markSeries((ObjectNode) row, index++);
            if (cutoff != null && millis(n.get("availability_time")) > cutoff) throw error("mark artifact contains data after as-of cutoff");
            normalized.add(n);
        }
        normalized.sort(Comparator.comparingLong((ObjectNode r) -> millis(r.get("event_time")))
                .thenComparing(r -> text(r.get("asset"))).thenComparing(r -> text(r.get("series_type"))).thenComparing(r -> text(r.get("symbol"))));
        Set<String> seen = new HashSet<>(); Map<String, List<ObjectNode>> series = new LinkedHashMap<>();
        for (ObjectNode row : normalized) {
            String key = text(row.get("series_type")) + "|" + text(row.get("asset")) + "|" + text(row.get("symbol")) + "|" + text(row.get("event_time"));
            if (!seen.add(key)) throw error("duplicate mark " + key);
            String seriesKey = text(row.get("series_type")) + "|" + text(row.get("asset")) + "|" + text(row.get("symbol"));
            series.computeIfAbsent(seriesKey, ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<ObjectNode>> e : series.entrySet()) {
            ArrayList<Long> consumed = new ArrayList<>(); e.getValue().forEach(r -> consumed.add(millis(r.get("availability_time")))); consumed.sort(Long::compareTo);
            for (int i = 1; i < consumed.size(); i++) {
                if (consumed.get(i).equals(consumed.get(i - 1))) throw error("mark series " + e.getKey() + " has duplicate consumption timestamp");
                if (consumed.get(i) - consumed.get(i - 1) != (long) interval) throw error("mark series " + e.getKey() + " is not dense at its consumption cadence");
            }
        }
        ObjectNode out = raw.deepCopy(); out.put("venue", venue.toLowerCase(Locale.ROOT)); out.set("interval_ms", numberNode(interval)); ArrayNode rows = out.putArray("rows"); normalized.forEach(rows::add); out.put("path", resolve(path).toString()); out.put("byte_sha256", supplied); return out;
    }

    public static ObjectNode writeMarkArtifact(String path, ObjectNode options) { return writeMarkArtifact(Path.of(path), options); }
    public static ObjectNode writeMarkArtifact(Path path, ObjectNode options) {
        ObjectNode o = objectOrEmpty(options); String provenance = textOr(o.get("provenance"), "FIXTURE");
        ArrayNode inputRows = arrayOrEmpty(o.get("rows")); ArrayNode normalized = MAPPER.createArrayNode(); int i = 0; for (JsonNode row : inputRows) normalized.add(markSeries((ObjectNode) row, i++));
        ObjectNode value = object().put("schema", MARK_SCHEMA).put("version", 1).put("provenance", provenance);
        copyNullable(value, "source_manifest_sha256", o, "sourceManifestSha256", "source_manifest_sha256"); copyNullable(value, "source_manifest_path", o, "sourceManifestPath", "source_manifest_path"); copyNullable(value, "source_receipt_sha256", o, "sourceReceiptSha256", "source_receipt_sha256"); copyNullable(value, "source_receipt_path", o, "sourceReceiptPath", "source_receipt_path"); copyNullable(value, "source_command_receipt_sha256", o, "sourceCommandReceiptSha256", "source_command_receipt_sha256"); copyNullable(value, "source_command_receipt_path", o, "sourceCommandReceiptPath", "source_command_receipt_path"); copyNullable(value, "source_code_sha256", o, "sourceCodeSha256", "source_code_sha256"); copyNullable(value, "source_code_path", o, "sourceCodePath", "source_code_path"); copyNullable(value, "lineage_sha256", o, "lineageSha256", "lineage_sha256");
        value.put("venue", textOr(first(o, "venue"), "binance").toLowerCase(Locale.ROOT)); value.set("interval_ms", numberNode(numberOr(first(o, "intervalMs", "interval_ms"), 3_600_000))); value.set("rows", normalized);
        if ("AUTHORITATIVE_RECOMPUTED".equals(provenance)) { ObjectNode lineage = object(); JsonNode manifest = first(o, "sourceManifestSha256", "source_manifest_sha256"), receipt = first(o, "sourceReceiptSha256", "source_receipt_sha256"), command = first(o, "sourceCommandReceiptSha256", "source_command_receipt_sha256"), code = first(o, "sourceCodeSha256", "source_code_sha256"); lineage.set("source_manifest_sha256", nullableCopy(manifest)); lineage.set("source_receipt_sha256", nullableCopy(receipt)); lineage.set("command_receipt_sha256", nullableCopy(command)); lineage.set("source_code_sha256", nullableCopy(code)); String expected = hash(lineage); if (!expected.equals(text(first(o, "lineageSha256", "lineage_sha256")))) throw error("authoritative mark artifact lineage must bind physical manifest, receipt, command and code"); verifyAuthoritativeBinding(value, o); }
        value = withHash(value); validateSchema(value); return writeNew(path, value);
    }

    private static void verifyAuthoritativeBinding(ObjectNode mark, ObjectNode options) {
        String[] paths = {text(first(options, "sourceManifestPath", "source_manifest_path")), text(first(options, "sourceReceiptPath", "source_receipt_path")), text(first(options, "sourceCommandReceiptPath", "source_command_receipt_path"))};
        String[] hashes = {text(first(options, "sourceManifestSha256", "source_manifest_sha256")), text(first(options, "sourceReceiptSha256", "source_receipt_sha256")), text(first(options, "sourceCommandReceiptSha256", "source_command_receipt_sha256"))};
        String[] labels = {"source manifest", "source receipt", "command receipt"}; ObjectNode[] values = new ObjectNode[3];
        for (int i = 0; i < 3; i++) { if (paths[i].isEmpty() || !Files.isRegularFile(resolve(paths[i])) || !HASH.matcher(hashes[i]).matches()) throw error("authoritative " + labels[i] + " physical binding is incomplete"); byte[] bytes = readBytes(resolve(paths[i])); if (!hash(bytes).equals(hashes[i])) throw error("authoritative " + labels[i] + " byte hash mismatch"); values[i] = parseObject(bytes, "authoritative " + labels[i]); if (!ownHash(values[i]).equals(text(values[i].get("content_sha256")))) throw error("authoritative " + labels[i] + " content hash is invalid"); }
        Set<String> manifestSchemas = Set.of("strategy-v5-separated-artifacts/1", "strategy-v5-parquet-conversion/1", "strategy-v5-authoritative-stage-artifact/1"); String manifestStatus = text(values[0].get("status")); boolean manifestOk = manifestSchemas.contains(text(values[0].get("schema"))) && (strictTrue(values[0].get("authoritative")) || Set.of("AUTHORITATIVE_PARQUET", "AUTHORITATIVE_RECOMPUTED", "COMPLETE").contains(manifestStatus)); boolean receiptOk = strictTrue(values[1].get("authoritative")) && Set.of("PUBLIC_OBSERVED", "USER_BOUND", "AUTHORITATIVE_PARQUET", "COMPLETE").contains(text(values[1].get("status")).toUpperCase(Locale.ROOT)); boolean commandOk = "strategy-v5-authoritative-command-receipt/1".equals(text(values[2].get("schema"))) && "COMPLETE".equals(text(values[2].get("status"))) && values[2].path("details").path("active").isBoolean() && !values[2].path("details").path("active").booleanValue(); if (!manifestOk || !receiptOk || !commandOk) throw error("authoritative source manifest/receipt/command provenance is not verified");
        String codePath = text(first(options, "sourceCodePath", "source_code_path")), codeHash = text(first(options, "sourceCodeSha256", "source_code_sha256")); if (codePath.isEmpty() || !Files.isRegularFile(resolve(codePath)) || !HASH.matcher(codeHash).matches() || !hash(readBytes(resolve(codePath))).equals(codeHash)) throw error("authoritative source transformation code is not physically bound");
    }

    public static ObjectNode writeExecutionFillArtifact(String path, ObjectNode options) { return writeExecutionFillArtifact(Path.of(path), options); }
    public static ObjectNode writeExecutionFillArtifact(Path path, ObjectNode options) {
        ObjectNode o = objectOrEmpty(options); ObjectNode value = object().put("schema", EXECUTION_SCHEMA).put("version", 1).put("venue", textOr(first(o, "venue"), "binance").toLowerCase(Locale.ROOT)); value.set("rows", arrayOrEmpty(o.get("rows"))); value.set("lineage", nullableCopy(o.get("lineage"))); value = withHash(value); validateSchema(value); return writeNew(path, value);
    }

    public static ObjectNode writeSelectedTradeArtifact(String path, ObjectNode options) { return writeSelectedTradeArtifact(Path.of(path), options); }
    public static ObjectNode writeSelectedTradeArtifact(Path path, ObjectNode options) {
        ObjectNode o = objectOrEmpty(options); String lineage = text(first(o, "lineageSha256", "lineage_sha256")); String evaluation = text(first(o, "evaluationSha256", "evaluation_sha256")); requireHash(lineage, "selected-trade lineage"); requireHash(evaluation, "selected-trade evaluation"); boolean fixture = truth(first(o, "fixture")); ObjectNode value = object().put("schema", SELECTED_SCHEMA).put("version", 1).put("status", fixture ? "FIXTURE" : "SELECTED").put("lineage_sha256", lineage).put("evaluation_sha256", evaluation); value.set("rows", arrayOrEmpty(o.get("rows"))); value = withHash(value); validateSchema(value); return writeNew(path, value);
    }

    public static ObjectNode writeEvaluationArtifact(String path, ObjectNode options) { return writeEvaluationArtifact(Path.of(path), options); }
    public static ObjectNode writeEvaluationArtifact(Path path, ObjectNode options) {
        ObjectNode o = objectOrEmpty(options); String selected = text(first(o, "selectedTradesSha256", "selected_trades_sha256")); String outer = text(first(o, "outerFoldSha256", "outer_fold_sha256")); String lineage = text(first(o, "lineageSha256", "lineage_sha256")); requireHash(selected, "selected trades"); requireHash(outer, "outer fold"); requireHash(lineage, "evaluation lineage"); boolean fixture = truth(first(o, "fixture")); ObjectNode value = object().put("schema", EVALUATION_SCHEMA).put("version", 1).put("status", fixture ? "FIXTURE" : "AUTHORITATIVE").put("selected_trades_sha256", selected).put("outer_fold_sha256", outer).put("lineage_sha256", lineage); value = withHash(value); validateSchema(value); return writeNew(path, value);
    }

    public static ObjectNode writeMetadataArtifact(String path, ObjectNode options) { return writeMetadataArtifact(Path.of(path), options); }
    public static ObjectNode writeMetadataArtifact(Path path, ObjectNode options) {
        ObjectNode o = objectOrEmpty(options); String kind = text(o.get("kind")); String captured = iso(first(o, "capturedAt", "captured_at"), Instant.now().toEpochMilli()); boolean fixtureOnly = o.get("fixtureOnly") == null ? true : truth(o.get("fixtureOnly")); String status = textOr(o.get("status"), "PUBLIC_OBSERVED"); ArrayNode records = MAPPER.createArrayNode(); int i = 0; for (JsonNode row : arrayOrEmpty(o.get("records"))) records.add(metadataRecord((ObjectNode) row, captured));
        ObjectNode source = o.has("source") && !o.get("source").isNull() ? (ObjectNode) o.get("source").deepCopy() : object().put("provider", fixtureOnly ? "FIXTURE_ONLY" : "BOUND_SOURCE").put("kind", kind);
        String sourceReceipt = textOr(first(o, "sourceReceiptSha256", "source_receipt_sha256"), fixtureOnly ? hash(object().put("kind", kind).set("source", source)) : null); JsonNode sourceByte = first(o, "sourceByteSha256", "source_byte_sha256"); if (sourceByte == null || sourceByte.isNull()) { if (fixtureOnly) { ArrayNode bytes = MAPPER.createArrayNode().add(hash(records)); sourceByte = bytes; } else sourceByte = NullNode.instance; }
        boolean modeled = "CONSERVATIVE_MODEL".equals(status); ObjectNode value = object().put("schema", METADATA_SCHEMA).put("version", 1).put("kind", kind).put("status", status).put("captured_at", captured); value.set("source", source); value.set("source_receipt_sha256", modeled && !fixtureOnly ? NullNode.instance : nullable(sourceReceipt)); value.set("source_byte_sha256", modeled && !fixtureOnly ? NullNode.instance : sourceByte); copyNullable(value, "model_sha256", o, "modelSha256", "model_sha256"); copyNullable(value, "model_path", o, "modelPath", "model_path"); copyNullable(value, "model_code_sha256", o, "modelCodeSha256", "model_code_sha256"); copyNullable(value, "model_code_path", o, "modelCodePath", "model_code_path"); copyNullable(value, "model_config_sha256", o, "modelConfigSha256", "model_config_sha256"); copyNullable(value, "model_config_path", o, "modelConfigPath", "model_config_path"); copyNullable(value, "precommit_sha256", o, "precommitSha256", "precommit_sha256"); copyNullable(value, "precommit_path", o, "precommitPath", "precommit_path"); value.put("provenance_mode", fixtureOnly ? "FIXTURE_ONLY" : modeled ? "MODEL_BOUND" : "UNAVAILABLE".equals(status) ? "UNAVAILABLE" : "BOUND_SOURCE"); value.set("records", records); if ("FUNDING_IDENTITY".equals(kind)) { JsonNode coverage = o.get("coverage"); if (coverage == null || coverage.isNull()) coverage = object().put("complete", false).putNull("cadence_ms").putNull("anchor_time").set("cadence_segments", MAPPER.createArrayNode()); value.set("coverage", coverage.deepCopy()); } else value.set("coverage", NullNode.instance); ArrayNode limitations = value.putArray("limitations"); if (fixtureOnly) limitations.add("FIXTURE_ONLY"); value.put("authoritative", !fixtureOnly && !"UNAVAILABLE".equals(status)); value = withHash(value); validateSchema(value); return writeNew(path, value);
    }

    private static ObjectNode loadMarkForEvaluation(ObjectNode request, ObjectNode policy) {
        String path = textOr(request.get("markPath"), textOr(request.get("mark_path"), "")); String sha = textOr(request.get("markSha256"), textOr(request.get("mark_sha256"), "")); ObjectNode direct = objectOrEmpty(request.get("markArtifact"));
        if (!path.isEmpty()) { ObjectNode options = object().put("path", path).put("sha256", sha).put("expectedVenue", textOr(policy.get("venue"), "")); options.set("expectedIntervalMs", numberOrNode(policy.get("interval_ms"))); options.set("asOf", nullable(policy.get("asOf"))); options.set("consumingCutoff", nullable(policy.get("consuming_cutoff"))); options.put("allowFixture", strictTrue(policy.get("execution_fixture")) || strictTrue(policy.get("allow_fixture_metadata"))); return readBoundMarkArtifact(options); }
        if (direct.has("path") && direct.has("byte_sha256")) return readBoundMarkArtifact(object().put("path", text(direct.get("path"))).put("sha256", text(direct.get("byte_sha256"))).put("allowFixture", strictTrue(policy.get("execution_fixture")) || strictTrue(policy.get("allow_fixture_metadata"))));
        throw error("authoritative portfolio risk requires a physical mark path and byte hash");
    }

    private static ObjectNode loadMetadata(JsonNode value, boolean allowFixture) {
        ObjectNode result = object();
        if (value == null || !value.isObject()) return result;
        ObjectNode supplied = (ObjectNode) value;
        List<String[]> bindings = List.of(
                new String[]{"fee", "feeArtifactPath", "feeArtifactSha256", "FEE_SCHEDULE"},
                new String[]{"contract", "contractArtifactPath", "contractArtifactSha256", "CONTRACT_SPEC"},
                new String[]{"margin", "marginArtifactPath", "marginArtifactSha256", "MARGIN"},
                new String[]{"liquidation", "liquidationArtifactPath", "liquidationArtifactSha256", "LIQUIDATION"},
                new String[]{"expiry", "expiryArtifactPath", "expiryArtifactSha256", "EXPIRY"},
                new String[]{"funding", "fundingArtifactPath", "fundingArtifactSha256", "FUNDING_IDENTITY"},
                new String[]{"execution_model", "executionModelArtifactPath", "executionModelArtifactSha256", "EXECUTION_MODEL"});
        for (String[] binding : bindings) {
            JsonNode direct = supplied.get(binding[0]);
            if (direct != null && direct.isObject() && direct.has("records")) {
                result.set(binding[0], direct.deepCopy());
                continue;
            }
            String path = text(first(supplied, binding[1], snake(binding[1])));
            if (path.isEmpty()) continue;
            String sha = text(first(supplied, binding[2], snake(binding[2])));
            ObjectNode artifact = readBoundJson(path, sha, METADATA_SCHEMA);
            if (!binding[3].equals(text(artifact.get("kind")))) throw error(binding[3] + " metadata artifact identity is invalid");
            if ("UNAVAILABLE".equals(text(artifact.get("status"))) || "UNAVAILABLE".equals(text(artifact.get("provenance_mode")))) throw error(binding[3] + " metadata is unavailable");
            boolean fixture = "FIXTURE_ONLY".equals(text(artifact.get("provenance_mode"))) || !strictTrue(artifact.get("authoritative"));
            if (fixture && !allowFixture && !strictTrue(supplied.get("execution_fixture"))) throw error(binding[3] + " fixture metadata is not admissible for authoritative portfolio risk");
            if (!fixture && strictTrue(artifact.get("authoritative")) && "CONSERVATIVE_MODEL".equals(text(artifact.get("status"))) && !"EXECUTION_MODEL".equals(binding[3])) throw error(binding[3] + " modeled metadata is stress-only and is not admissible for base portfolio risk");
            artifact.put("fixture_only", fixture);
            result.set(binding[0], artifact);
        }
        return result;
    }

    private static String snake(String camel) { return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    private static ObjectNode loadExecution(ObjectNode request, ObjectNode policy, boolean fixture) { JsonNode value = request.get("executionArtifact"); if (value != null && value.isObject()) return (ObjectNode) value.deepCopy(); String path = textOr(request.get("executionArtifactPath"), textOr(request.get("execution_artifact_path"), "")); if (path.isEmpty()) return null; return readBoundJson(path, textOr(request.get("executionArtifactSha256"), textOr(request.get("execution_artifact_sha256"), "")), EXECUTION_SCHEMA); }
    private static ObjectNode loadSelected(ObjectNode request, boolean fixture) { String path = textOr(request.get("selectedTradeArtifactPath"), textOr(request.get("selected_trade_artifact_path"), "")); return path.isEmpty() ? null : readBoundJson(path, textOr(request.get("selectedTradeArtifactSha256"), textOr(request.get("selected_trade_artifact_sha256"), "")), SELECTED_SCHEMA); }
    private static ObjectNode loadEvaluation(ObjectNode request, boolean fixture) { String path = textOr(request.get("evaluationArtifactPath"), textOr(request.get("evaluation_artifact_path"), "")); return path.isEmpty() ? null : readBoundJson(path, textOr(request.get("evaluationArtifactSha256"), textOr(request.get("evaluation_artifact_sha256"), "")), EVALUATION_SCHEMA); }
    private static ObjectNode loadStress(ObjectNode request) { String path = textOr(request.get("stressArtifactPath"), textOr(request.get("stress_artifact_path"), "")); return path.isEmpty() ? null : readBoundJson(path, textOr(request.get("stressArtifactSha256"), textOr(request.get("stress_artifact_sha256"), "")), null); }

    private static ObjectNode finishRisk(List<ObjectNode> accepted, ArrayNode rejected, List<String> failures, ObjectNode artifact, ObjectNode metadata, ObjectNode request, ObjectNode policy, Set<String> required, ObjectNode selected, ObjectNode evaluation, ObjectNode execution, ObjectNode stress, boolean fixtureRun, String accountCurrency) {
        double equity = number(policy.get("current_equity")); if (!(equity > 0)) failures.add("CURRENT_EQUITY_MISSING"); ArrayNode assets = MAPPER.createArrayNode(); Set<String> allAssets = new LinkedHashSet<>(required); accepted.forEach(t -> allAssets.add(text(t.get("asset")))); rejected.forEach(t -> allAssets.add(text(t.get("asset")))); allAssets.stream().filter(s -> !s.isEmpty()).sorted().forEach(assets::add);
        ObjectNode pnl = alignedPnl(accepted, artifact, Math.max(30, (int) numberOr(policy.get("min_common_timestamps"), 30))); ObjectNode market = marketDiagnostics(artifact, accepted, policy); EventResult events = eventRiskPath(accepted, artifact, pnl, equity, policy); failures.addAll(events.failures); ObjectNode mrc = covarianceMrc(pnl); if (!"MEASURED".equals(text(mrc.get("status")))) failures.add("PNL_MRC_UNAVAILABLE"); ArrayNode pnlCovariance = covarianceMatrix(pnl);
        ArrayNode decisions = MAPPER.createArrayNode(); for (JsonNode asset : assets) { String a = text(asset); int ac = (int) accepted.stream().filter(t -> a.equals(text(t.get("asset")))).count(); List<String> own = new ArrayList<>(); rejected.forEach(t -> { if (a.equals(text(t.get("asset"))) && t.path("reasons").isArray()) t.path("reasons").forEach(x -> own.add(text(x))); }); String status = fixtureRun ? "FIXTURE" : ac > 0 && own.isEmpty() ? "PASS" : !own.isEmpty() ? "REJECTED" : "NOT_SELECTED"; if (fixtureRun) own.add("FIXTURE_INPUT_NOT_AUTHORITATIVE"); ObjectNode decision = object().put("asset", a).put("status", status).put("selected_trade_count", ac).put("rejected_trade_count", own.size()); decision.set("failures", strings(own)); decisions.add(decision); }
        if (fixtureRun) failures.add("FIXTURE_INPUT_NOT_AUTHORITATIVE"); if (accepted.isEmpty()) failures.add("NO_ACCEPTED_TRADES"); List<String> unique = new ArrayList<>(new LinkedHashSet<>(failures)); String status = unique.isEmpty() ? "PASS" : "REJECTED";
        ObjectNode metadataHashes = object(); var metadataFields = metadata == null ? object().fields() : metadata.fields(); while (metadataFields.hasNext()) { var entry = metadataFields.next(); JsonNode artifactValue = entry.getValue(); if (artifactValue != null && artifactValue.isObject()) { ObjectNode hashPair = object(); hashPair.set("content_sha256", nullableCopy(artifactValue.get("content_sha256"))); hashPair.set("byte_sha256", nullableCopy(artifactValue.get("byte_sha256"))); metadataHashes.set(entry.getKey(), hashPair); } else metadataHashes.putNull(entry.getKey()); }
        String selectedHash = selected == null ? null : text(selected.get("content_sha256")); String evaluationHash = evaluation == null ? null : text(evaluation.get("content_sha256")); String executionHash = execution == null ? null : text(execution.get("byte_sha256")); String stressHash = stress == null ? null : text(stress.get("byte_sha256"));
        ObjectNode lineage = object().put("marks_sha256", text(artifact.get("byte_sha256"))).put("selected_trades_sha256", selectedHash).put("evaluation_sha256", evaluationHash).put("execution_fills_sha256", executionHash); lineage.set("metadata", metadataHashes); lineage.put("policy_sha256", hash(policy));
        ObjectNode portfolioDecision = object().put("status", status); portfolioDecision.set("failures", strings(unique)); portfolioDecision.put("max_concurrency", (int) numberOr(policy.get("max_concurrent"), 1)); portfolioDecision.set("concurrency_violations", MAPPER.createArrayNode());
        ObjectNode result = object().put("schema", RISK_SCHEMA).put("version", 1).put("provenance", fixtureRun ? "FIXTURE" : "AUTHORITATIVE_RECOMPUTED").put("fixture_run", fixtureRun).put("selected_trades_sha256", selectedHash).put("evaluation_sha256", evaluationHash).put("lineage_sha256", hash(lineage)); result.set("lineage", lineage); result.set("asset_decisions", decisions); result.set("portfolio_decision", portfolioDecision); result.set("aligned_pnl", pnl); result.set("aligned_returns", market); result.set("covariance_by_asset", pnlCovariance); result.set("pnl_covariance_by_asset", pnlCovariance); result.set("btc_betas", market.path("btc_betas")); result.set("market_diagnostics", market); result.set("accepted_trades", array(accepted)); result.set("rejected_trades", rejected); result.set("exposure", events.exposure); result.set("event_risk_path", events.out); result.set("marginal_risk_contribution", mrc); result.put("account_currency", accountCurrency).put("pass", "PASS".equals(status)); result.set("failures", strings(unique)); result.put("mark_artifact_sha256", text(artifact.get("content_sha256"))).put("mark_bytes_sha256", text(artifact.get("byte_sha256"))).put("execution_fills_sha256", executionHash).put("stress_artifact_sha256", stressHash); result.set("metadata_artifacts", metadataHashes); result = withHash(result); validateSchema(result); return result;
    }

    private static final class TradeMeta {
        final List<String> failures = new ArrayList<>(); double multiplier = 1, collateralAccount = 0, maintenance = 0, riskAmount = Double.NaN, entryNotional, exitNotional, entryFees = Double.NaN, exitFees = Double.NaN, expectedFees = Double.NaN, fundingTotal = 0; String collateralAsset = "", marginMode = ""; Long expiry, settlement; ArrayNode fundingRows = MAPPER.createArrayNode(), liquidationPath = MAPPER.createArrayNode();
    }

    private static TradeMeta validateTradeMeta(ObjectNode trade, ObjectNode artifact, ObjectNode metadata, ObjectNode policy, long entry, long exit, double entryFill, double exitFill, boolean fixture) {
        TradeMeta m = new TradeMeta();
        String type = text(trade.get("instrument_type")).toLowerCase(Locale.ROOT);
        String venue = text(trade.get("venue")).toLowerCase(Locale.ROOT);
        String symbol = text(trade.get("symbol")).toUpperCase(Locale.ROOT);
        String asset = text(trade.get("asset")).toLowerCase(Locale.ROOT);
        boolean derivative = DERIVATIVE.contains(type);
        if (!(type.equals("spot") || derivative)) m.failures.add("UNSUPPORTED_INSTRUMENT_TYPE");
        if (type.equals("spot") && text(trade.get("direction")).equalsIgnoreCase("short")) m.failures.add("SPOT_SHORT_NOT_SUPPORTED");
        if (trade.has("legs") || trade.has("option_type") || trade.has("strike_price") || trade.has("expiry_style") || strictTrue(trade.get("hft")) || number(trade.get("timeframe_ms")) < 60_000) m.failures.add("UNSUPPORTED_NONLINEAR_OR_HFT_INSTRUMENT");
        if (!CRYPTO.contains(asset) || venue.isEmpty() || symbol.isEmpty()) m.failures.add("MISSING_CRYPTO_INSTRUMENT_IDENTITY");
        if (!venue.equals(text(artifact.get("venue")))) m.failures.add("TRADE_VENUE_MISMATCH");

        ObjectNode contract = artifactMetadata(metadata, "contract");
        ObjectNode margin = artifactMetadata(metadata, "margin");
        ObjectNode liquidation = artifactMetadata(metadata, "liquidation");
        JsonNode contractRow = null, marginRow = null, liquidationRow = null, expiryRow = null;
        if (derivative) {
            try { contractRow = coveredRecord(contract, venue, symbol, entry, exit, "CONTRACT_SPEC"); }
            catch (RuntimeException ex) { m.failures.add(ex.getMessage()); }
            if (artifactMetadata(metadata, "execution_model") == null) m.failures.add("EXECUTION_MODEL_METADATA_MISSING");
            try { marginRow = coveredRecord(margin, venue, symbol, entry, exit, "MARGIN"); }
            catch (RuntimeException ex) { m.failures.add(ex.getMessage()); }
            try { liquidationRow = coveredRecord(liquidation, venue, symbol, entry, exit, "LIQUIDATION"); }
            catch (RuntimeException ex) { m.failures.add(ex.getMessage()); }
            if (liquidationRow != null && !"LIQUIDATION_MARK".equals(text(liquidationRow.get("mark_series_type")))) m.failures.add("LIQUIDATION_MARK_SOURCE_NOT_BOUND");
            if (Set.of("dated_future", "future", "futures").contains(type)) {
                try { expiryRow = coveredRecord(artifactMetadata(metadata, "expiry"), venue, symbol, entry, exit, "EXPIRY"); }
                catch (RuntimeException ex) { m.failures.add(ex.getMessage()); }
            }
            m.multiplier = number(contractRow == null ? null : contractRow.get("contract_multiplier"));
            if (!(m.multiplier > 0)) m.failures.add("BOUND_CONTRACT_MULTIPLIER_MISSING");
            m.maintenance = number(marginRow == null ? null : marginRow.get("maintenance_margin_ratio"));
            if (!(m.maintenance >= 0)) m.failures.add("BOUND_MAINTENANCE_MARGIN_MISSING");
            JsonNode fallbackContract = contractRow == null ? null : contractRow.get("margin_mode");
            m.marginMode = textOr(marginRow == null ? null : marginRow.get("margin_mode"), textOr(fallbackContract, "")).toUpperCase(Locale.ROOT);
            if (m.marginMode.equals("CROSS")) m.failures.add("CROSS_MARGIN_ENGINE_NOT_IMPLEMENTED");
            if (!(m.marginMode.equals("CROSS") || m.marginMode.equals("ISOLATED"))) m.failures.add("MARGIN_MODE_NOT_BOUND");
            m.collateralAsset = textOr(marginRow == null ? null : marginRow.get("collateral_asset"), textOr(contractRow == null ? null : contractRow.get("collateral_asset"), "")).toLowerCase(Locale.ROOT);
            if (m.collateralAsset.isEmpty()) m.failures.add("COLLATERAL_ASSET_NOT_BOUND");
            double leverage = number(first(marginRow, "leverage")); if (!(leverage > 0)) leverage = number(first(contractRow, "leverage"));
            if (!(leverage > 0)) m.failures.add("LEVERAGE_NOT_BOUND");
            m.collateralAccount = m.marginMode.equals("CROSS") ? number(policy.get("cross_collateral_account")) : number(first(trade, "collateral_used", "collateral"));
            if (!(m.collateralAccount > 0)) m.failures.add(m.marginMode.equals("CROSS") ? "CROSS_COLLATERAL_ACCOUNT_MISSING" : "ISOLATED_COLLATERAL_MISSING");
            if (Set.of("dated_future", "future", "futures").contains(type)) {
                m.expiry = millisOrNull(first(expiryRow, "expiry")); if (m.expiry == null) m.expiry = millisOrNull(first(contractRow, "expiry"));
                m.settlement = millisOrNull(first(expiryRow, "settlement_time")); if (m.settlement == null) m.settlement = millisOrNull(first(contractRow, "settlement_time"));
                if (m.expiry == null || exit > m.expiry) m.failures.add("EXPIRY_NOT_BOUND_OR_EXIT_AFTER_EXPIRY");
                if (m.settlement == null || exit > m.settlement) m.failures.add("DATED_SETTLEMENT_NOT_BOUND_OR_EXIT_AFTER_SETTLEMENT");
            }
            String accountCurrency = textOr(first(policy, "account_currency", "accountCurrency"), "usdt").toLowerCase(Locale.ROOT);
            if (!m.collateralAsset.isEmpty() && !m.collateralAsset.equals(accountCurrency)) {
                String fxSymbol = textOr(first(marginRow, "collateral_symbol"), textOr(first(contractRow, "collateral_symbol"), m.collateralAsset.toUpperCase(Locale.ROOT) + "USDT")).toUpperCase(Locale.ROOT);
                ObjectNode fx = exactMark(artifact, "COLLATERAL_FX", m.collateralAsset, fxSymbol, entry);
                if (fx == null) m.failures.add("COLLATERAL_FX_ENTRY_MARK_MISSING"); else m.collateralAccount *= number(fx.get("price"));
            }
            if (type.equals("perpetual") || type.equals("perp")) {
                ObjectNode funding = artifactMetadata(metadata, "funding");
                if (funding == null) m.failures.add("BOUND_FUNDING_ARTIFACT_MISSING");
                else try { FundingResult result = fundingForTrade(trade, artifact, funding, m.multiplier, contractRow, fixture, entry, exit); m.fundingTotal = result.total; m.fundingRows = result.rows; }
                catch (RuntimeException ex) { m.failures.add(ex.getMessage()); }
            } else {
                ObjectNode funding = artifactMetadata(metadata, "funding");
                if (funding != null && hasFundingBetween(funding, venue, symbol, entry, exit)) m.failures.add("FUNDING_FORBIDDEN_FOR_NONPERPETUAL");
            }
            // Liquidation marks consume the same fee-adjusted equity as the
            // final lifecycle result.  Populate entry fees before constructing
            // this path; the final fee block below recomputes and validates it.
            ObjectNode preFee = artifactMetadata(metadata, "fee");
            if (preFee != null) try {
                double preEntryNotional = entryFill * number(trade.get("quantity")) * m.multiplier;
                double preRate = feeRate(preFee, venue, symbol, entry, "ENTRY_FEE_SCHEDULE");
                m.entryFees = preEntryNotional * preRate;
            } catch (RuntimeException ignored) { /* final fee validation records the contract failure */ }
            List<ObjectNode> timeline = rowsForSeries(artifact, "LIQUIDATION_MARK", asset, symbol).stream().filter(row -> { long t = millis(row.get("availability_time")); return t >= entry && t <= exit; }).toList();
            if (timeline.isEmpty() || timeline.stream().anyMatch(row -> !row.has("low") || !row.has("high"))) m.failures.add("LIQUIDATION_MARK_INTRABAR_DATA_MISSING");
            else for (ObjectNode row : timeline) { double mark = text(trade.get("direction")).equalsIgnoreCase("long") ? number(row.get("low")) : number(row.get("high")); double equity = m.collateralAccount + signed(trade) * number(trade.get("quantity")) * m.multiplier * (mark - entryFill) - m.entryFees + accrued(m.fundingRows, millis(row.get("availability_time"))); double maintenance = Math.abs(number(trade.get("quantity")) * m.multiplier * mark) * m.maintenance; ObjectNode state = object().put("mark_price", mark).put("equity", equity).put("maintenance", maintenance).put("margin_excess", equity - maintenance).put("timestamp", iso(millis(row.get("availability_time")))); m.liquidationPath.add(state); if (equity - maintenance <= 0) m.failures.add("LIQUIDATION_LEVEL_CROSSED"); }
        } else { m.multiplier = 1; m.collateralAccount = numberOr(first(trade, "collateral_used", "collateral"), 0); }

        double quantity = number(trade.get("quantity")); m.entryNotional = entryFill * quantity * m.multiplier; m.exitNotional = exitFill * quantity * m.multiplier;
        if (!(m.entryNotional > 0) || !(m.exitNotional > 0)) m.failures.add("INVALID_NOTIONAL");
        if (trade.has("notional") && !near(number(trade.get("notional")), m.entryNotional)) m.failures.add("SUPPLIED_NOTIONAL_MISMATCH_BOUND_FILL");
        ObjectNode fee = artifactMetadata(metadata, "fee");
        double entryRate = Double.NaN, exitRate = Double.NaN;
        if (fee == null) m.failures.add("FEE_SCHEDULE_METADATA_MISSING");
        else try { entryRate = feeRate(fee, venue, symbol, entry, "ENTRY_FEE_SCHEDULE"); exitRate = feeRate(fee, venue, symbol, exit, "EXIT_FEE_SCHEDULE"); }
        catch (RuntimeException ex) { m.failures.add(ex.getMessage()); }
        m.entryFees = m.entryNotional * entryRate; m.exitFees = m.exitNotional * exitRate; m.expectedFees = m.entryFees + m.exitFees;
        if (trade.has("fees") && !near(number(trade.get("fees")), m.expectedFees)) m.failures.add("SUPPLIED_FEES_MISMATCH_BOUND_SCHEDULE");
        double stop = number(trade.get("stop_price")); m.riskAmount = text(trade.get("direction")).equalsIgnoreCase("long") ? (entryFill - stop) * quantity * m.multiplier : (stop - entryFill) * quantity * m.multiplier;
        if (!(stop > 0) || !(m.riskAmount > 0)) m.failures.add("STOP_RISK_RESERVATION_MISSING_OR_INVALID");
        if (trade.has("risk_amount") && !near(number(trade.get("risk_amount")), m.riskAmount)) m.failures.add("SUPPLIED_RISK_AMOUNT_MISMATCH");
        return m;
    }

    private static JsonNode coveredRecord(ObjectNode artifact, String venue, String symbol, long start, long end, String type) {
        if (artifact == null) throw error(type + " metadata has 0 exact effective records for " + venue + "/" + symbol);
        ArrayList<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : artifact.path("records")) {
            if (venue.equalsIgnoreCase(text(row.get("venue"))) && symbol.equalsIgnoreCase(textOr(row.get("symbol"), row.get("instrument"))) && covers(row, start) && covers(row, end)) matches.add(row);
        }
        if (matches.size() != 1) throw error(type + " metadata has " + matches.size() + " exact effective records for " + venue + "/" + symbol);
        return matches.get(0);
    }

    private static Long millisOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        try { return millis(value); } catch (RuntimeException ignored) { return null; }
    }

    private static boolean hasFundingBetween(ObjectNode artifact, String venue, String symbol, long start, long end) {
        for (JsonNode row : artifact.path("records")) {
            if (venue.equalsIgnoreCase(text(row.get("venue"))) && symbol.equalsIgnoreCase(textOr(row.get("symbol"), row.get("instrument"))) && row.has("settlement_time")) {
                long timestamp = millis(row.get("settlement_time")); if (timestamp > start && timestamp <= end) return true;
            }
        }
        return false;
    }

    private static double accrued(ArrayNode rows, long timestamp) {
        double total = 0; for (JsonNode row : rows) if (millis(row.get("settlement_time")) <= timestamp) total += number(row.get("amount")); return total;
    }

    private static final class FundingResult {
        final double total; final ArrayNode rows;
        FundingResult(double total, ArrayNode rows) { this.total = total; this.rows = rows; }
    }

    private static FundingResult fundingForTrade(ObjectNode trade, ObjectNode artifact, ObjectNode fundingArtifact, double multiplier, JsonNode contract, boolean fixture, long entry, long exit) {
        JsonNode coverage = fundingArtifact.get("coverage");
        if (coverage == null || !strictTrue(coverage.get("complete"))) throw error("FUNDING_COVERAGE_NOT_COMPLETE");
        ArrayList<ObjectNode> segments = new ArrayList<>();
        if (coverage.get("cadence_segments") != null && coverage.get("cadence_segments").isArray()) for (JsonNode value : coverage.get("cadence_segments")) segments.add((ObjectNode) value);
        if (segments.isEmpty()) {
            if (!fixture) throw error("FUNDING_CADENCE_SEGMENTS_MISSING");
            double cadence = number(first(coverage, "cadence_ms")); Long anchor = millisOrNull(first(coverage, "anchor_time"));
            double contractCadence = number(contract == null ? null : contract.get("funding_interval_ms")); if (!(cadence > 0)) cadence = contractCadence;
            if (!(cadence > 0) || anchor == null) throw error("FUNDING_CADENCE_SEGMENTS_MISSING");
            ObjectNode segment = object().put("effective_from", iso(Math.min(anchor, entry))).put("effective_to", iso(exit)).put("cadence_ms", cadence).put("origin_at", iso(anchor)); segments.add(segment);
        }
        ArrayList<Long> slots = new ArrayList<>(); ArrayList<Double> cadences = new ArrayList<>();
        for (ObjectNode segment : segments) {
            Long from = millisOrNull(segment.get("effective_from")), to = millisOrNull(segment.get("effective_to")), origin = millisOrNull(first(segment, "origin_at", "effective_from")); double cadence = number(segment.get("cadence_ms"));
            if (from == null || to == null || origin == null || !(cadence > 0) || to < from) throw error("FUNDING_CADENCE_SEGMENT_INVALID");
            long firstSlot = origin + (long) Math.ceil((Math.max(from, entry + 1L) - origin) / cadence) * (long) cadence;
            for (long timestamp = firstSlot; timestamp <= Math.min(to, exit); timestamp += (long) cadence) if (timestamp > entry && timestamp <= exit) { slots.add(timestamp); cadences.add(cadence); if (timestamp > Long.MAX_VALUE - (long) cadence) break; }
        }
        if (new HashSet<>(slots).size() != slots.size()) throw error("FUNDING_CADENCE_SEGMENTS_OVERLAP");
        ArrayList<ObjectNode> physical = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonNode value : fundingArtifact.path("records")) if (venueEquals(value, trade) && symbolEquals(value, trade) && value.has("settlement_time")) { long timestamp = millis(value.get("settlement_time")); String id = textOr(value.get("event_id"), text(value.get("id"))); if (!id.isEmpty() && !ids.add(id)) throw error("bound funding partition contains duplicate or missing event ids"); if (timestamp > entry && timestamp <= exit) physical.add((ObjectNode) value); }
        double tolerance = numberOr(first(coverage, "slot_tolerance_ms"), 60_000); Map<Long,ObjectNode> assigned = new LinkedHashMap<>(); Map<Long,Long> jitter = new HashMap<>();
        for (ObjectNode row : physical) { long timestamp = millis(row.get("settlement_time")); long best = Long.MIN_VALUE; double distance = Double.POSITIVE_INFINITY; for (Long slot : slots) if (Math.abs((double) slot - timestamp) < distance) { best = slot; distance = Math.abs((double) slot - timestamp); } if (best == Long.MIN_VALUE || distance > tolerance) throw error("funding event is outside canonical cadence slot tolerance"); if (assigned.put(best, row) != null) throw error("funding lifecycle contains duplicate settlement slot"); jitter.put(best, timestamp); }
        for (Long slot : slots) if (!assigned.containsKey(slot)) throw error("funding lifecycle coverage has missing settlement");
        JsonNode supplied = trade.get("funding_settlements"); Map<String,JsonNode> suppliedById = new HashMap<>(); if (supplied != null && supplied.isArray()) { if (supplied.size() != physical.size()) throw error("supplied funding partition does not match bound funding records"); for (JsonNode row : supplied) { String id = text(row.get("event_id")); if (id.isEmpty() || suppliedById.put(id, row) != null) throw error("supplied funding partition contains duplicate or missing event ids"); } }
        double total = 0; ArrayNode rows = MAPPER.createArrayNode(); int slotIndex = 0;
        for (Long slot : slots) { ObjectNode row = assigned.get(slot); String id = textOr(row.get("event_id"), text(row.get("id"))); if (!HASH.matcher(text(row.get("source_receipt_sha256"))).matches() || (row.has("source_byte_sha256") && !row.get("source_byte_sha256").isNull() && !HASH.matcher(text(row.get("source_byte_sha256"))).matches())) throw error("funding record lacks physical source identity"); ObjectNode mark = exactMark(artifact, "FUNDING_MARK", text(trade.get("asset")), text(trade.get("symbol")), slot); if (mark == null && fixture) mark = exactMark(artifact, "TRADE_MARK", text(trade.get("asset")), text(trade.get("symbol")), slot); if (mark == null) throw error("funding settlement mark is missing from bound derivative mark series"); double markPrice = number(mark.get("price")), rate = number(first(row, "rate", "funding_rate")); if (!Double.isFinite(rate)) throw error("funding rate missing from physical record"); JsonNode boundMark = first(row, "settlement_mark_price", "mark_price"); if (boundMark != null && number(boundMark) > 0 && (!HASH.matcher(text(row.get("settlement_mark_sha256"))).matches() || !text(row.get("settlement_mark_sha256")).equals(hash(mark)))) throw error("funding settlement mark identity is not bound"); if (!fixture && (!text(row.get("settlement_mark_sha256")).equals(hash(mark)) || !HASH.matcher(text(row.get("settlement_mark_source_sha256"))).matches() || !text(row.get("mark_series_type")).equals("FUNDING_MARK") || !text(row.get("settlement_mark_event_id")).equals(id))) throw error("funding settlement mark identity is not bound"); double amount = -signed(trade) * Math.abs(number(trade.get("quantity")) * multiplier * markPrice) * rate; JsonNode suppliedRow = suppliedById.get(id); if (supplied != null && (suppliedRow == null || !near(number(first(suppliedRow, "amount", "pnl")), amount))) throw error("supplied funding amount mismatches bound rate/mark arithmetic"); if (suppliedRow != null && !text(suppliedRow.get("source_receipt_sha256")).equals(text(row.get("source_receipt_sha256")))) throw error("supplied funding receipt mismatch"); total += amount; ObjectNode expected = object().put("event_id", id).put("settlement_time", iso(millis(row.get("settlement_time")))).put("canonical_slot_time", iso(slot)).put("jitter_ms", jitter.get(slot) - slot).put("cadence_ms", cadences.get(slotIndex++)).put("rate", rate).put("mark_price", markPrice).put("amount", amount).put("source_receipt_sha256", text(row.get("source_receipt_sha256"))); expected.set("source_byte_sha256", nullableCopy(row.get("source_byte_sha256"))); expected.put("settlement_mark_sha256", hash(mark)); rows.add(expected); }
        if (supplied != null) for (String id : suppliedById.keySet()) if (physical.stream().noneMatch(row -> id.equals(textOr(row.get("event_id"), text(row.get("id"))))) ) throw error("supplied funding contains unknown event");
        return new FundingResult(total, rows);
    }

    private static boolean venueEquals(JsonNode row, ObjectNode trade) { return text(trade.get("venue")).equalsIgnoreCase(text(row.get("venue"))); }
    private static boolean symbolEquals(JsonNode row, ObjectNode trade) { return text(trade.get("symbol")).equalsIgnoreCase(textOr(row.get("symbol"), row.get("instrument"))); }

    private static ObjectNode alignedPnl(List<ObjectNode> accepted, ObjectNode artifact, int minCommon) {
        ArrayList<String> assets = new ArrayList<>(); accepted.forEach(t -> { String a = text(t.get("asset")); if (!assets.contains(a)) assets.add(a); }); assets.sort(String::compareTo);
        ObjectNode empty = object(); empty.set("assets", strings(assets)); empty.set("timestamps", MAPPER.createArrayNode()); empty.set("vectors", object()); empty.set("matrix", object()); empty.set("increments", MAPPER.createArrayNode()); empty.put("common_count", 0); empty.put("minCommon", minCommon);
        if (accepted.isEmpty()) return empty;
        long start = accepted.stream().mapToLong(t -> (long) number(t.get("entry_time"))).min().orElse(0);
        long end = accepted.stream().mapToLong(t -> (long) number(t.get("exit_time"))).max().orElse(0);
        ArrayList<String> instruments = new ArrayList<>();
        Map<String,List<ObjectNode>> instrumentRows = new LinkedHashMap<>();
        for (ObjectNode trade : accepted) {
            String key = text(trade.get("asset")) + "|" + text(trade.get("symbol"));
            if (!instruments.contains(key)) instruments.add(key);
        }
        instruments.sort(String::compareTo);
        for (String key : instruments) {
            String[] split = key.split("\\|", 2); List<ObjectNode> rows = rowsForSeries(artifact, "TRADE_MARK", split[0], split[1]);
            rows.removeIf(row -> { long t = millis(row.get("availability_time")); return t < start || t > end; });
            if (rows.isEmpty()) throw error("selected instrument mark series missing for " + key);
            instrumentRows.put(key, rows);
        }
        List<ObjectNode> firstRows = instrumentRows.get(instruments.get(0)); ArrayList<Long> times = new ArrayList<>(); for (ObjectNode row : firstRows) times.add(millis(row.get("availability_time")));
        long cadence = (long) number(artifact.get("interval_ms"));
        if (times.size() < 2 || times.get(0) > start + cadence || times.get(times.size() - 1) < end) throw error("selected PnL grid does not cover the selected lifecycle window");
        for (int i = 1; i < times.size(); i++) if (times.get(i) - times.get(i - 1) != cadence) throw error("selected PnL grid is not cadence-aligned");
        for (Map.Entry<String,List<ObjectNode>> entry : instrumentRows.entrySet()) { ArrayList<Long> other = new ArrayList<>(); entry.getValue().forEach(row -> other.add(millis(row.get("availability_time")))); if (!other.equals(times)) throw error("selected instrument mark grid is not an exact common intersection for " + entry.getKey()); }
        ObjectNode vectors = object(), matrix = object(); ArrayNode increments = MAPPER.createArrayNode();
        for (String asset : assets) {
            ArrayNode values = MAPPER.createArrayNode();
            for (Long time : times) { double total = 0; for (ObjectNode trade : accepted) if (asset.equals(text(trade.get("asset")))) total += tradePnlAt(trade, time, artifact); values.add(total); }
            vectors.set(asset, values); ArrayNode points = MAPPER.createArrayNode(); for (int i = 0; i < times.size(); i++) { ObjectNode point = object().put("time", times.get(i)); point.put("value", values.get(i).asDouble()); points.add(point); } matrix.set(asset, points);
            ArrayNode delta = MAPPER.createArrayNode(); for (int i = 1; i < values.size(); i++) delta.add(values.get(i).asDouble() - values.get(i - 1).asDouble()); increments.add(delta);
        }
        ArrayNode timestamps = MAPPER.createArrayNode(); times.forEach(t -> timestamps.add(iso(t))); ObjectNode out = object(); out.set("assets", strings(assets)); out.set("timestamps", timestamps); out.set("vectors", vectors); out.set("matrix", matrix); out.set("increments", increments); out.put("common_count", times.size()); out.put("minCommon", minCommon); out.set("window", object().put("start", iso(start)).put("end", iso(end))); out.set("instruments", strings(instruments)); return out;
    }

    private static double tradePnlAt(ObjectNode trade, long timestamp, ObjectNode artifact) { long entry = (long) number(trade.get("entry_time")), exit = (long) number(trade.get("exit_time")); double q = number(trade.get("quantity")), mult = numberOr(trade.get("multiplier"), 1), ep = number(trade.get("entry_fill_price")), xp = number(trade.get("exit_fill_price")), fees = number(trade.get("fees")), funding = number(trade.get("funding_pnl")); if (timestamp >= exit) return signed(trade) * q * mult * (xp - ep) - fees + funding; if (timestamp < entry) return 0; ObjectNode mark = exactMark(artifact, "TRADE_MARK", text(trade.get("asset")), text(trade.get("symbol")), timestamp); if (mark == null) throw error("intralifecycle trade mark missing for " + text(trade.get("signal_id"))); return signed(trade) * q * mult * (number(mark.get("price")) - ep) - number(trade.get("entry_fees")); }

    private static ObjectNode marketDiagnostics(ObjectNode artifact, List<ObjectNode> accepted, ObjectNode policy) {
        ArrayList<String> required = new ArrayList<>(); required.add("btc"); accepted.forEach(t -> { String a = text(t.get("asset")); if (!required.contains(a)) required.add(a); }); required.sort(String::compareTo);
        long start = Long.MIN_VALUE, end = Long.MAX_VALUE, cadence = (long) number(artifact.get("interval_ms"));
        if (!accepted.isEmpty()) { start = accepted.stream().mapToLong(t -> (long) number(t.get("entry_time"))).min().orElse(0); end = accepted.stream().mapToLong(t -> (long) number(t.get("exit_time"))).max().orElse(0); }
        Map<String,List<ObjectNode>> grouped = new LinkedHashMap<>(); for (JsonNode n : artifact.path("rows")) if ("RISK_REFERENCE".equals(text(n.get("series_type")))) { ObjectNode row = (ObjectNode) n; long time = millis(row.get("availability_time")); if (start != Long.MIN_VALUE && (time < start - cadence || time > end)) continue; String key = text(row.get("asset")) + "|" + text(row.get("symbol")); grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row); }
        ObjectNode requested = objectOrEmpty(first(policy, "risk_reference_symbols", "benchmark_symbols")); ObjectNode symbols = object(), vectors = object(); Map<String,List<Long>> timesByAsset = new LinkedHashMap<>();
        for (String asset : required) {
            ArrayList<String> candidates = new ArrayList<>(); for (String key : grouped.keySet()) if (key.startsWith(asset + "|")) candidates.add(key); candidates.sort(String::compareTo); if (candidates.isEmpty()) throw error("RISK_REFERENCE series missing for " + asset);
            String requestedSymbol = text(requested.get(asset)); if (requestedSymbol.isEmpty() && candidates.size() == 1) requestedSymbol = candidates.get(0).substring(asset.length() + 1); if (requestedSymbol.isEmpty()) throw error("RISK_REFERENCE benchmark identity is ambiguous for " + asset); String chosen = asset + "|" + requestedSymbol.toUpperCase(Locale.ROOT); if (!grouped.containsKey(chosen)) throw error("RISK_REFERENCE benchmark identity is not present for " + asset); symbols.put(asset, requestedSymbol.toUpperCase(Locale.ROOT));
            List<ObjectNode> rows = grouped.get(chosen); rows.sort(Comparator.comparingLong(r -> millis(r.get("availability_time")))); ArrayList<Long> times = new ArrayList<>(); for (ObjectNode row : rows) times.add(millis(row.get("availability_time"))); timesByAsset.put(asset, times); Map<Long,Double> priceByTime = new LinkedHashMap<>(); for (ObjectNode row : rows) priceByTime.put(millis(row.get("availability_time")), number(row.get("price"))); ArrayList<Long> used = new ArrayList<>(); ArrayList<Double> returns = new ArrayList<>(); for (Long time : times) { if (start != Long.MIN_VALUE && (time < start - cadence || time > end)) continue; Double previous = priceByTime.get(time - cadence); if (previous == null) continue; used.add(time); returns.add(priceByTime.get(time) / previous - 1); } timesByAsset.put(asset, used); ArrayNode values = MAPPER.createArrayNode(); returns.forEach(values::add); vectors.set(asset, values);
        }
        ArrayList<Long> common = new ArrayList<>(); for (Long t : timesByAsset.get(required.get(0))) { boolean keep = start == Long.MIN_VALUE || (t >= start + cadence && t <= end); for (String asset : required) keep &= timesByAsset.get(asset).contains(t); if (keep) common.add(t); } common.sort(Long::compareTo); if (common.size() < 2) throw error("RISK_REFERENCE series have no exact synchronized prior-mark intersection");
        ObjectNode aligned = object(); ObjectNode covariance = object(); ArrayNode covarianceArray = MAPPER.createArrayNode(); Map<String,List<Double>> alignedReturns = new LinkedHashMap<>();
        for (String asset : required) { List<ObjectNode> rows = grouped.get(asset + "|" + text(symbols.get(asset))); Map<Long,Double> prices = new HashMap<>(); for (ObjectNode row : rows) prices.put(millis(row.get("availability_time")), number(row.get("price"))); ArrayList<Double> vals = new ArrayList<>(); for (Long t : common) vals.add(prices.get(t) / prices.get(t - cadence) - 1); alignedReturns.put(asset, vals); ArrayNode arr = MAPPER.createArrayNode(); vals.forEach(arr::add); aligned.set(asset, arr); }
        for (String left : required) { ArrayNode row = MAPPER.createArrayNode(); for (String right : required) row.add(covariance(alignedReturns.get(left), alignedReturns.get(right))); covarianceArray.add(row); }
        List<Double> btc = alignedReturns.get("btc"); double btcVariance = variance(btc); ObjectNode betas = object(); for (String asset : required) betas.set(asset, (btcVariance > 0 && Double.isFinite(btcVariance)) ? nullable(covariance(alignedReturns.get(asset), btc) / btcVariance) : NullNode.instance);
        ArrayNode timestamps = MAPPER.createArrayNode(); common.forEach(t -> timestamps.add(iso(t))); ObjectNode out = object(); out.set("assets", strings(required)); out.set("symbols", symbols); out.set("timestamps", timestamps); out.set("vectors", aligned); out.set("covariance_by_asset", covarianceArray); out.set("btc_betas", betas); out.put("common_count", common.size()); out.put("minCommon", Math.max(30, (int) numberOr(policy.get("min_common_timestamps"), 30))); if (start != Long.MIN_VALUE) out.set("window", object().put("start", iso(start)).put("end", iso(end))); return out;
    }

    private static EventResult eventRiskPath(List<ObjectNode> accepted, ObjectNode artifact, ObjectNode pnl, double equity, ObjectNode policy) {
        List<Long> times = new ArrayList<>(); for (JsonNode value : arrayOrEmpty(pnl.get("timestamps"))) times.add(millis(value));
        ArrayList<String> assets = new ArrayList<>(); accepted.forEach(t -> { String a = text(t.get("asset")); if (!assets.contains(a)) assets.add(a); }); assets.sort(String::compareTo);
        EquityResult equityPath = mtmEquityPath(pnl, equity, policy); ArrayList<String> failures = new ArrayList<>(equityPath.failures); ArrayNode path = MAPPER.createArrayNode(); double maxGross = 0, maxNetAbs = 0, maxBetaGross = 0, maxRiskFraction = 0, maxCollateralFraction = 0, maxMaintenance = 0, maxConcurrency = 0, maxShare = 0, maxHhi = 0;
        for (int i = 0; i < times.size(); i++) {
            long timestamp = times.get(i); List<ObjectNode> open = accepted.stream().filter(t -> (long) number(t.get("entry_time")) <= timestamp && timestamp < (long) number(t.get("exit_time"))).toList(); ObjectNode byAsset = object(), grossByAsset = object(); assets.forEach(asset -> { byAsset.put(asset, 0); grossByAsset.put(asset, 0); }); ArrayNode components = MAPPER.createArrayNode(); double crossCollateral = 0, isolatedCollateral = 0, maintenance = 0; Set<String> crossAccounts = new HashSet<>();
            for (ObjectNode trade : open) { ObjectNode mark = exactMark(artifact, "TRADE_MARK", text(trade.get("asset")), text(trade.get("symbol")), timestamp); if (mark == null) { failures.add("INTRALIFECYCLE_TRADE_MARK_MISSING:" + text(trade.get("signal_id"))); continue; } double signedNotional = signed(trade) * number(trade.get("quantity")) * number(trade.get("multiplier")) * number(mark.get("price")); double grossNotional = Math.abs(signedNotional); byAsset.put(text(trade.get("asset")), numberOr(byAsset.get(text(trade.get("asset"))), 0) + signedNotional); grossByAsset.put(text(trade.get("asset")), numberOr(grossByAsset.get(text(trade.get("asset"))), 0) + grossNotional); ObjectNode component = object().put("signal_id", text(trade.get("signal_id"))).put("asset", text(trade.get("asset"))).put("symbol", text(trade.get("symbol"))).put("instrument_type", text(trade.get("instrument_type"))).put("direction", text(trade.get("direction"))).put("signed_notional", signedNotional).put("gross_notional", grossNotional); components.add(component); maintenance += grossNotional * number(trade.get("maintenance_margin_ratio")); if ("CROSS".equals(text(trade.get("margin_mode")))) { String key = text(trade.get("margin_mode")) + "|" + text(trade.get("collateral_asset")) + "|" + text(trade.get("venue")); if (crossAccounts.add(key)) crossCollateral += number(trade.get("collateral_account")); } else isolatedCollateral += number(trade.get("collateral_account")); }
            ObjectNode beta = objectOrEmpty(pnl.get("btc_betas")); ObjectNode concentration = concentration(byAsset, beta, grossByAsset, components); JsonNode equityRow = i < arrayOrEmpty(equityPath.curve).size() ? equityPath.curve.get(i) : object().put("timestamp", iso(timestamp)).put("equity", equity).put("peak_equity", equity).put("drawdown", 0).put("drawdown_pct", 0).put("underwater", false).put("underwater_duration_ms", 0); double current = number(equityRow.get("equity")), reserved = open.stream().mapToDouble(t -> number(t.get("risk_amount"))).sum(), collateral = crossCollateral + isolatedCollateral; ObjectNode row = object().put("timestamp", iso(timestamp)).put("open_trade_count", open.size()); row.set("by_asset", byAsset); row.set("gross_by_asset", grossByAsset); row.set("gross_components", components); row.put("gross", number(concentration.get("gross"))).put("net", number(concentration.get("net"))).put("beta_gross", number(concentration.get("beta_gross"))).put("beta_net", number(concentration.get("beta_net"))).put("max_share", number(concentration.get("max_share"))).put("hhi", number(concentration.get("hhi"))).put("reserved_risk", reserved).put("risk_fraction", current > 0 ? reserved / current : 0).put("collateral_reserved", collateral).put("collateral_fraction", current > 0 ? collateral / current : 0).put("maintenance_margin", maintenance).put("current_equity", current).put("peak_equity", number(equityRow.get("peak_equity"))).put("drawdown", number(equityRow.get("drawdown"))).put("drawdown_pct", number(equityRow.get("drawdown_pct"))).put("underwater", strictTrue(equityRow.get("underwater"))).put("underwater_duration_ms", number(equityRow.get("underwater_duration_ms"))); row.set("concentration", concentration); path.add(row);
            maxGross = Math.max(maxGross, number(row.get("gross"))); maxNetAbs = Math.max(maxNetAbs, Math.abs(number(row.get("net")))); maxBetaGross = Math.max(maxBetaGross, number(row.get("beta_gross"))); maxRiskFraction = Math.max(maxRiskFraction, number(row.get("risk_fraction"))); maxCollateralFraction = Math.max(maxCollateralFraction, number(row.get("collateral_fraction"))); maxMaintenance = Math.max(maxMaintenance, maintenance); maxConcurrency = Math.max(maxConcurrency, open.size()); maxShare = Math.max(maxShare, number(row.get("max_share"))); maxHhi = Math.max(maxHhi, number(row.get("hhi")));
            if (open.size() > (int) numberOr(policy.get("max_concurrent"), 1)) failures.add("CONCURRENCY_CAP"); if (!(current > 0)) failures.add("CURRENT_EQUITY_NONPOSITIVE"); if (finite(policy.get("max_gross_exposure")) && number(row.get("gross")) > number(policy.get("max_gross_exposure"))) failures.add("MAX_GROSS_EXPOSURE_EXCEEDED"); if (finite(policy.get("max_net_exposure")) && Math.abs(number(row.get("net"))) > number(policy.get("max_net_exposure"))) failures.add("MAX_NET_EXPOSURE_EXCEEDED"); if (finite(policy.get("max_reserved_fraction")) && number(row.get("risk_fraction")) > number(policy.get("max_reserved_fraction"))) failures.add("CURRENT_EQUITY_RISK_RESERVATION_EXCEEDED"); if (finite(policy.get("max_collateral_fraction")) && number(row.get("collateral_fraction")) > number(policy.get("max_collateral_fraction"))) failures.add("COLLATERAL_RESERVATION_CAP_EXCEEDED"); if (finite(policy.get("max_asset_share")) && number(row.get("max_share")) > number(policy.get("max_asset_share"))) failures.add("MAX_ASSET_SHARE_EXCEEDED"); if (finite(policy.get("max_hhi")) && number(row.get("hhi")) > number(policy.get("max_hhi"))) failures.add("MAX_HHI_EXCEEDED"); if (finite(policy.get("max_beta_gross")) && number(row.get("beta_gross")) > number(policy.get("max_beta_gross"))) failures.add("MAX_BETA_GROSS_EXCEEDED"); if (finite(policy.get("max_beta_net")) && Math.abs(number(row.get("beta_net"))) > number(policy.get("max_beta_net"))) failures.add("MAX_BETA_NET_EXCEEDED"); if (finite(policy.get("max_maintenance_margin")) && maintenance > number(policy.get("max_maintenance_margin"))) failures.add("MAINTENANCE_MARGIN_CAP_EXCEEDED");
        }
        ObjectNode maxima = object().put("gross", maxGross).put("net_abs", maxNetAbs).put("beta_gross", maxBetaGross).put("risk_fraction", maxRiskFraction).put("collateral_fraction", maxCollateralFraction).put("maintenance_margin", maxMaintenance).put("concurrency", maxConcurrency).put("asset_share", maxShare).put("hhi", maxHhi); maxima.put("maximum_drawdown", number(equityPath.diagnostics.get("maximum_drawdown"))).put("maximum_drawdown_pct", number(equityPath.diagnostics.get("maximum_drawdown_pct"))).put("maximum_underwater_duration_ms", number(equityPath.diagnostics.get("maximum_underwater_duration_ms")));
        ObjectNode out = object(); out.set("path", path); out.set("maxima", maxima); out.set("failures", strings(new ArrayList<>(new LinkedHashSet<>(failures)))); out.set("equity_curve", equityPath.curve); out.set("equity_diagnostics", equityPath.diagnostics); out.set("policy_limits", equityPath.policy); ObjectNode exposure = object(); JsonNode last = path.isEmpty() ? null : path.get(path.size() - 1); if (last == null) { exposure.set("by_asset", object()); exposure.set("gross_by_asset", object()); exposure.set("gross_components", MAPPER.createArrayNode()); exposure.put("gross", 0).put("net", 0).put("current_equity", equity).put("reservation_risk", 0).put("reservation_fraction", 0).put("collateral_reserved", 0).put("collateral_fraction", 0); exposure.set("maxima", maxima); exposure.set("path", path); exposure.set("concentration", concentration(object(), object(), object(), MAPPER.createArrayNode())); } else { exposure.set("by_asset", last.get("by_asset")); exposure.set("gross_by_asset", last.get("gross_by_asset")); exposure.set("gross_components", last.get("gross_components")); exposure.put("gross", number(last.get("gross"))).put("net", number(last.get("net"))).put("current_equity", number(last.get("current_equity"))).put("reservation_risk", number(last.get("reserved_risk"))).put("reservation_fraction", number(last.get("risk_fraction"))).put("collateral_reserved", number(last.get("collateral_reserved"))).put("collateral_fraction", number(last.get("collateral_fraction"))); exposure.set("maxima", maxima); exposure.set("path", path); exposure.set("concentration", last.get("concentration")); }
        return new EventResult(out, exposure, new ArrayList<>(new LinkedHashSet<>(failures)));
    }

    private static final class EquityResult { final ArrayNode curve; final ObjectNode diagnostics, policy; final List<String> failures; EquityResult(ArrayNode curve, ObjectNode diagnostics, ObjectNode policy, List<String> failures) { this.curve = curve; this.diagnostics = diagnostics; this.policy = policy; this.failures = failures; } }

    private static EquityResult mtmEquityPath(ObjectNode pnl, double currentEquity, ObjectNode limits) {
        ArrayList<Long> timestamps = new ArrayList<>(); for (JsonNode value : arrayOrEmpty(pnl.get("timestamps"))) timestamps.add(millis(value)); ArrayNode curve = MAPPER.createArrayNode(); double peak = currentEquity, minimum = currentEquity, maxDrawdown = 0, maxDrawdownPct = 0, totalUnderwater = 0, maxUnderwater = 0; Long peakAt = timestamps.isEmpty() ? null : timestamps.get(0), underwaterStart = null, lastRecovery = null;
        ArrayNode vectors = arrayOrEmpty(pnl.get("vectors")); ObjectNode vectorObject = pnl.has("vectors") && pnl.get("vectors").isObject() ? (ObjectNode) pnl.get("vectors") : object();
        for (int i = 0; i < timestamps.size(); i++) { long time = timestamps.get(i); double pnlValue = 0; var fields = vectorObject.fields(); while (fields.hasNext()) { JsonNode values = fields.next().getValue(); if (values.isArray() && i < values.size()) pnlValue += number(values.get(i)); } double eq = currentEquity + pnlValue; if (eq > peak) { if (underwaterStart != null) { long duration = Math.max(0, time - underwaterStart); totalUnderwater += duration; maxUnderwater = Math.max(maxUnderwater, duration); lastRecovery = time; } peak = eq; peakAt = time; underwaterStart = null; } else if (eq < peak) underwaterStart = underwaterStart == null ? (peakAt == null ? time : peakAt) : underwaterStart; else if (underwaterStart != null) { long duration = Math.max(0, time - underwaterStart); totalUnderwater += duration; maxUnderwater = Math.max(maxUnderwater, duration); lastRecovery = time; underwaterStart = null; } double drawdown = Math.max(0, peak - eq), drawdownPct = peak > 0 ? drawdown / peak * 100 : Double.POSITIVE_INFINITY, underwater = eq < peak && underwaterStart != null ? Math.max(0, time - underwaterStart) : 0; maxDrawdown = Math.max(maxDrawdown, drawdown); maxDrawdownPct = Math.max(maxDrawdownPct, drawdownPct); minimum = Math.min(minimum, eq); curve.add(object().put("timestamp", iso(time)).put("equity", eq).put("peak_equity", peak).put("drawdown", drawdown).put("drawdown_pct", drawdownPct).put("underwater", eq < peak).put("underwater_duration_ms", underwater)); }
        if (underwaterStart != null && !timestamps.isEmpty()) { long duration = Math.max(0, timestamps.get(timestamps.size() - 1) - underwaterStart); totalUnderwater += duration; maxUnderwater = Math.max(maxUnderwater, duration); }
        ObjectNode policy = normalizeEquityPolicy(limits); if (policy.has("equity_floor") && !policy.get("equity_floor").isNull() && number(policy.get("equity_floor")) > currentEquity) ((ArrayNode) policy.withArray("invalid")).add("equity_floor"); if (policy.has("ruin_equity_floor") && !policy.get("ruin_equity_floor").isNull() && number(policy.get("ruin_equity_floor")) > currentEquity) ((ArrayNode) policy.withArray("invalid")).add("ruin_equity_floor"); Double equityFloor = nullableNumber(policy.get("equity_floor")), ruinFloor = nullableNumber(policy.get("ruin_equity_floor")), minCurrent = nullableNumber(policy.get("minimum_current_equity")); double finalEquity = curve.isEmpty() ? currentEquity : number(curve.get(curve.size() - 1).get("equity")); boolean floorBreached = equityFloor != null && minimum < equityFloor, ruin = ruinFloor != null && minimum <= ruinFloor, currentFloor = minCurrent != null && finalEquity < minCurrent; ArrayList<String> failures = new ArrayList<>(); ArrayNode invalid = arrayOrEmpty(policy.get("invalid")), missing = arrayOrEmpty(policy.get("missing")); if (!invalid.isEmpty()) failures.add("EQUITY_POLICY_LIMITS_INVALID"); if (!strictTrue(policy.get("fixture")) && !missing.isEmpty()) failures.add("EQUITY_POLICY_LIMITS_UNBOUND"); if (finite(policy.get("max_drawdown_amount")) && maxDrawdown > number(policy.get("max_drawdown_amount"))) failures.add("MAX_MARK_TO_MARKET_DRAWDOWN_EXCEEDED"); if (finite(policy.get("max_drawdown_pct")) && maxDrawdownPct > number(policy.get("max_drawdown_pct"))) failures.add("MAX_MARK_TO_MARKET_DRAWDOWN_PCT_EXCEEDED"); if (finite(policy.get("max_underwater_duration_ms")) && maxUnderwater > number(policy.get("max_underwater_duration_ms"))) failures.add("MAX_UNDERWATER_DURATION_EXCEEDED"); if (floorBreached) failures.add("EQUITY_FLOOR_BREACHED"); if (ruin) failures.add("RUIN_EQUITY_THRESHOLD_BREACHED"); if (currentFloor) failures.add("CURRENT_EQUITY_BELOW_FLOOR"); ObjectNode diagnostics = object().put("start_equity", currentEquity).put("final_equity", finalEquity).put("current_equity", finalEquity).put("peak_equity", curve.isEmpty() ? currentEquity : Math.max(currentEquity, number(curve.get(curve.size() - 1).get("peak_equity")))).put("minimum_equity", minimum).put("maximum_drawdown", maxDrawdown).put("maximum_drawdown_pct", maxDrawdownPct).put("total_underwater_duration_ms", totalUnderwater).put("current_underwater", !curve.isEmpty() && strictTrue(curve.get(curve.size() - 1).get("underwater"))).put("current_underwater_duration_ms", curve.isEmpty() ? 0 : number(curve.get(curve.size() - 1).get("underwater_duration_ms"))).put("maximum_underwater_duration_ms", maxUnderwater); diagnostics.set("last_recovery_at", lastRecovery == null ? NullNode.instance : MAPPER.getNodeFactory().textNode(iso(lastRecovery))); diagnostics.set("equity_floor", equityFloor == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(equityFloor)); diagnostics.set("ruin_equity_floor", ruinFloor == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(ruinFloor)); diagnostics.put("current_equity_floor_breached", currentFloor).put("equity_floor_breached", floorBreached).put("ruin", ruin).put("gate_pass", failures.isEmpty()); return new EquityResult(curve, diagnostics, policy, failures);
    }

    private static Double nullableNumber(JsonNode value) { return value == null || value.isNull() ? null : number(value); }
    private static boolean finite(JsonNode value) { return value != null && !value.isNull() && Double.isFinite(number(value)); }

    private static Double covariance(List<Double> left, List<Double> right) { if (left == null || right == null || left.size() != right.size() || left.size() < 2) return null; double lm = left.stream().mapToDouble(Double::doubleValue).average().orElse(0), rm = right.stream().mapToDouble(Double::doubleValue).average().orElse(0); double sum = 0; for (int i = 0; i < left.size(); i++) sum += (left.get(i) - lm) * (right.get(i) - rm); return sum / (left.size() - 1); }
    private static double variance(List<Double> values) { Double result = covariance(values, values); return result == null ? Double.NaN : result; }

    private static ObjectNode normalizeEquityPolicy(ObjectNode limits) {
        ObjectNode source = limits == null ? object() : limits;
        ObjectNode out = object();
        JsonNode fraction = first(source, "max_drawdown_fraction", "maximum_drawdown_fraction"); JsonNode pct = first(source, "max_drawdown_pct", "maximum_drawdown_pct");
        out.set("max_drawdown_amount", numberOrNull(first(source, "max_drawdown_amount", "maximum_drawdown_amount", "max_drawdown_usd")));
        if (pct != null) out.set("max_drawdown_pct", numberOrNull(pct)); else if (fraction != null) { Double n = nullableNumber(fraction); out.set("max_drawdown_pct", n == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(n * 100)); } else out.putNull("max_drawdown_pct");
        out.set("max_underwater_duration_ms", numberOrNull(first(source, "max_underwater_duration_ms", "maximum_underwater_duration_ms", "max_time_underwater_ms", "max_underwater_ms")));
        out.set("equity_floor", numberOrNull(first(source, "equity_floor", "minimum_equity", "min_equity"))); out.set("ruin_equity_floor", numberOrNull(first(source, "ruin_equity_floor", "ruin_floor", "ruin_boundary_equity"))); out.set("minimum_current_equity", numberOrNull(first(source, "minimum_current_equity", "min_current_equity"))); out.put("mark_to_market_required", true);
        ArrayNode invalid = MAPPER.createArrayNode(); ArrayNode missing = MAPPER.createArrayNode(); out.set("invalid", invalid); out.set("missing", missing); boolean fixture = strictTrue(source.get("execution_fixture")) || strictTrue(source.get("allow_fixture_metadata")) || "FIXTURE".equals(text(source.get("provenance"))); out.put("fixture", fixture);
        for (String key : List.of("max_drawdown_amount", "max_drawdown_pct", "max_underwater_duration_ms", "equity_floor", "ruin_equity_floor", "minimum_current_equity")) { JsonNode value = out.get(key); if (value != null && !value.isNull() && !Double.isFinite(number(value))) invalid.add(key); }
        if (finite(out.get("max_drawdown_amount")) && number(out.get("max_drawdown_amount")) < 0) invalid.add("max_drawdown_amount"); if (finite(out.get("max_drawdown_pct")) && (number(out.get("max_drawdown_pct")) < 0 || number(out.get("max_drawdown_pct")) >= 100)) invalid.add("max_drawdown_pct"); if (finite(out.get("max_underwater_duration_ms")) && number(out.get("max_underwater_duration_ms")) < 0) invalid.add("max_underwater_duration_ms"); for (String key : List.of("equity_floor", "ruin_equity_floor", "minimum_current_equity")) if (finite(out.get(key)) && number(out.get(key)) < 0) invalid.add(key); if (finite(out.get("equity_floor")) && finite(out.get("ruin_equity_floor")) && number(out.get("ruin_equity_floor")) > number(out.get("equity_floor"))) invalid.add("ruin_equity_floor");
        if (!finite(out.get("max_drawdown_amount")) && !finite(out.get("max_drawdown_pct"))) missing.add("maximum drawdown"); if (!finite(out.get("max_underwater_duration_ms"))) missing.add("maximum underwater duration"); if (!finite(out.get("equity_floor"))) missing.add("equity floor"); if (!finite(out.get("ruin_equity_floor"))) missing.add("ruin equity floor"); out.put("binding_status", fixture ? "FIXTURE_DEFAULTS" : (!invalid.isEmpty() || !missing.isEmpty() ? "UNBOUND" : "FROZEN")); return out;
    }

    private static JsonNode numberOrNull(JsonNode value) { Double n = nullableNumber(value); return n == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(n); }

    private static ObjectNode concentration(ObjectNode exposure, ObjectNode beta, ObjectNode grossByAsset, ArrayNode grossComponents) {
        ArrayList<String> assets = new ArrayList<>(); exposure.fieldNames().forEachRemaining(name -> { if (!name.startsWith("__")) assets.add(name); }); if (grossByAsset != null) grossByAsset.fieldNames().forEachRemaining(name -> { if (!name.startsWith("__") && !assets.contains(name)) assets.add(name); }); assets.sort(String::compareTo); ArrayNode rows = MAPPER.createArrayNode(); double gross = 0, net = 0, betaGross = 0, betaNet = 0; ArrayList<Double> shares = new ArrayList<>(), betaShares = new ArrayList<>();
        for (String asset : assets) { double signed = numberOr(exposure.get(asset), 0), grossValue = grossByAsset == null ? Math.abs(signed) : numberOr(grossByAsset.get(asset), 0); Double b = nullableNumber(beta == null ? null : beta.get(asset)); ObjectNode row = object().put("asset", asset).put("exposure", signed).put("gross_exposure", grossValue); row.set("beta", b == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(b)); row.set("beta_exposure", b == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(signed * b)); row.set("beta_gross_exposure", b == null ? NullNode.instance : MAPPER.getNodeFactory().numberNode(grossValue * Math.abs(b))); rows.add(row); gross += grossValue; net += signed; if (b != null) { betaGross += grossValue * Math.abs(b); betaNet += signed * b; } }
        for (JsonNode row : rows) { shares.add(gross > 0 ? number(row.get("gross_exposure")) / gross : 0); shares.set(shares.size() - 1, shares.get(shares.size() - 1)); betaShares.add(betaGross > 0 && row.get("beta_gross_exposure") != null && !row.get("beta_gross_exposure").isNull() ? number(row.get("beta_gross_exposure")) / betaGross : 0); }
        double hhi = shares.stream().mapToDouble(x -> x * x).sum(), betaHhi = betaShares.stream().mapToDouble(x -> x * x).sum(); ObjectNode grossMap = object(); for (JsonNode row : rows) grossMap.put(text(row.get("asset")), number(row.get("gross_exposure"))); ObjectNode out = object(); out.set("rows", rows); out.put("gross", gross).put("net", net); out.set("gross_by_asset", grossMap); out.set("gross_components", grossComponents == null ? MAPPER.createArrayNode() : grossComponents); out.put("beta_gross", betaGross).put("beta_net", betaNet).put("max_share", shares.stream().mapToDouble(Double::doubleValue).max().orElse(0)).put("hhi", hhi).put("beta_max_share", betaShares.stream().mapToDouble(Double::doubleValue).max().orElse(0)).put("beta_hhi", betaHhi); return out;
    }

    private static ObjectNode covarianceMrc(ObjectNode pnl) { ArrayNode matrix = covarianceMatrix(pnl); int common = (int) number(pnl.get("common_count")), min = (int) numberOr(pnl.get("minCommon"), 30); ArrayNode weights = MAPPER.createArrayNode(), components = MAPPER.createArrayNode(); ObjectNode result = object(); result.put("common_timestamps", common); result.set("covariance_by_asset", matrix); if (common < min || matrix.isEmpty()) { result.put("status", "UNAVAILABLE"); result.set("aggregation_weights", weights); result.putNull("portfolio_volatility"); result.set("components", components); result.put("component_sum", 0).put("component_sum_matches_portfolio", false); return result; } int n = matrix.size(); double[] sigmaW = new double[n]; for (int i = 0; i < n; i++) { weights.add(1); for (int j = 0; j < n; j++) sigmaW[i] += number(matrix.get(i).get(j)); } double variance = 0; for (double value : sigmaW) variance += value; double volatility = Math.sqrt(Math.max(0, variance)); double sum = 0; ArrayNode assets = arrayOrEmpty(pnl.get("assets")); for (int i = 0; i < n; i++) { double contribution = volatility > 0 ? sigmaW[i] / volatility : 0; ObjectNode component = object().put("asset", i < assets.size() ? text(assets.get(i)) : "").put("aggregation_weight", 1); component.set("marginal_contribution", volatility > 0 ? MAPPER.getNodeFactory().numberNode(sigmaW[i] / volatility) : NullNode.instance); component.set("component_contribution", volatility > 0 ? MAPPER.getNodeFactory().numberNode(sigmaW[i] / volatility) : NullNode.instance); components.add(component); sum += contribution; } result.put("status", "MEASURED"); result.set("aggregation_weights", weights); result.put("portfolio_volatility", volatility); result.set("components", components); result.put("component_sum", sum); result.put("component_sum_matches_portfolio", Math.abs(sum - volatility) <= 1e-10); return result; }
    private static ArrayNode covarianceMatrix(ObjectNode pnl) { ArrayNode increments = arrayOrEmpty(pnl.get("increments")); if (increments.isEmpty()) return MAPPER.createArrayNode(); int n = increments.size(); ArrayNode matrix = MAPPER.createArrayNode(); for (int i = 0; i < n; i++) { ArrayNode row = MAPPER.createArrayNode(); List<Double> left = new ArrayList<>(); for (JsonNode value : arrayOrEmpty(increments.get(i))) left.add(number(value)); for (int j = 0; j < n; j++) { List<Double> right = new ArrayList<>(); for (JsonNode value : arrayOrEmpty(increments.get(j))) right.add(number(value)); Double value = covariance(left, right); if (value == null) row.add(NullNode.instance); else row.add(value); } matrix.add(row); } return matrix; }

    private static final class EventResult { final ObjectNode out, exposure; final List<String> failures; EventResult(ObjectNode out, ObjectNode exposure, List<String> failures) { this.out = out; this.exposure = exposure; this.failures = failures; } }

    private static ObjectNode artifactMetadata(ObjectNode metadata, String key) { JsonNode n = metadata == null ? null : metadata.get(key); return n != null && n.isObject() ? (ObjectNode) n : null; }
    private static double feeRate(ObjectNode artifact, String venue, String symbol, long timestamp, String label) { for (JsonNode row : artifact.path("records")) if (venue.equalsIgnoreCase(text(row.get("venue"))) && symbol.equalsIgnoreCase(textOr(row.get("symbol"), row.get("instrument"))) && covers(row, timestamp)) { double rate = number(first(row, "taker_rate", "taker_fee_rate")); if (!(rate >= 0) || !Double.isFinite(rate)) throw error("BOUND_TAKER_RATE_MISSING"); return rate; } throw error(label + " metadata has 0 exact effective records for " + venue + "/" + symbol); }
    private static JsonNode recordFor(ObjectNode artifact, String venue, String symbol, String field) { for (JsonNode row : artifact.path("records")) if (venue.equalsIgnoreCase(text(row.get("venue"))) && symbol.equalsIgnoreCase(textOr(row.get("symbol"), row.get("instrument")))) return row.get(field); return NullNode.instance; }
    private static boolean covers(JsonNode row, long t) { return (!row.has("effective_from") || millis(row.get("effective_from")) <= t) && (!row.has("effective_to") || millis(row.get("effective_to")) >= t); }
    private static ObjectNode findExecution(ObjectNode execution, String id) { if (execution == null) return null; for (JsonNode row : execution.path("rows")) if (id.equals(text(row.get("signal_id")))) return (ObjectNode) row; return null; }
    private static String nullableHash(ObjectNode metadata, String key) { ObjectNode value = artifactMetadata(metadata, key); return value == null ? null : text(value.get("content_sha256")); }
    private static boolean hasForbiddenField(JsonNode value) { if (value == null || !value.isObject()) return false; var it = value.fields(); while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); if (e.getKey().matches("(?i)(^|_)(pnl|net_r|gross_r|fee_r|slippage_r|funding_pnl|metrics|risk|stress|portfolio|wfo|performance|equity)(_|$)") || hasForbiddenField(e.getValue())) return true; } return false; }

    private static ObjectNode readBoundJson(String path, String sha, String schema) { if (path.isEmpty()) throw error("physical artifact is missing"); requireHash(sha, "artifact byte hash"); Path p = resolve(path); if (!Files.isRegularFile(p)) throw error("physical artifact is missing"); byte[] bytes = readBytes(p); if (!hash(bytes).equals(sha)) throw error("artifact byte hash mismatch"); ObjectNode value = parseObject(bytes, "physical artifact"); if (!ownHash(value).equals(text(value.get("content_sha256")))) throw error("physical artifact content hash is invalid"); if (schema != null && !schema.equals(text(value.get("schema")))) throw error("unsupported physical artifact schema: " + text(value.get("schema"))); SCHEMAS.validateContractSchema(value); value.put("path", p.toString()).put("byte_sha256", sha); return value; }
    private static ObjectNode markSeries(ObjectNode input, int index) { ObjectNode row = input.deepCopy(); String type = textOr(row.get("series_type"), "TRADE_MARK").toUpperCase(Locale.ROOT); if (!MARK_SERIES.contains(type)) throw error("mark " + index + " has unsupported series_type"); String asset = text(row.get("asset")).toLowerCase(Locale.ROOT), symbol = textOr(row.get("symbol"), text(row.get("instrument"))).toUpperCase(Locale.ROOT); if (asset.isEmpty() || symbol.isEmpty()) throw error("mark " + index + " lacks instrument identity"); if (!"COLLATERAL_FX".equals(type) && !CRYPTO.contains(asset)) throw error("mark " + index + " is outside the crypto universe"); long event = millis(first(row, "event_time", "time")), available = millis(first(row, "availability_time", "available_at")); double price = number(first(row, "price", "close")); if (!(price > 0) || available < event) throw error("mark " + index + " has invalid price/availability"); row.put("asset", asset).put("symbol", symbol).put("series_type", type).put("event_time", iso(event)).put("availability_time", iso(available)); row.set("price", numberNode(price)); for (String f : List.of("open", "high", "low", "close")) if (row.has(f) && !row.get(f).isNull()) row.set(f, numberNode(number(row.get(f)))); if (row.has("high") && row.has("low") && (number(row.get("high")) < number(row.get("low")) || price < number(row.get("low")) || price > number(row.get("high")))) throw error("mark " + index + " has inconsistent intrabar range"); return row; }
    private static ObjectNode metadataRecord(ObjectNode input, String captured) { ObjectNode row = input.deepCopy(); String symbol = textOr(row.get("symbol"), text(row.get("instrument"))).toUpperCase(Locale.ROOT), asset = textOr(row.get("asset"), symbol.replaceAll("(USDT|USDC|USD)$", "")).toLowerCase(Locale.ROOT), instrument = textOr(row.get("instrument"), symbol); row.put("asset", asset).put("instrument", instrument).put("venue", text(row.get("venue")).toLowerCase(Locale.ROOT)).put("symbol", symbol).put("effective_from", iso(first(row, "effective_from", "settlement_time"), millis(captured))).put("effective_to", iso(first(row, "effective_to", "settlement_time"), millis(captured))); return row; }
    private static ObjectNode writeNew(Path path, ObjectNode artifact) { try { Path absolute = path.toAbsolutePath().normalize(); if (absolute.getParent() != null) Files.createDirectories(absolute.getParent()); byte[] bytes = nodePrettyJson(artifact).getBytes(StandardCharsets.UTF_8); Files.write(absolute, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); return object().put("path", absolute.toString()).put("sha256", hash(bytes)).set("artifact", artifact); } catch (IOException ex) { throw new IllegalArgumentException(ex.getMessage(), ex); } }
    private static String nodePrettyJson(JsonNode value) { try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value).replace(" :", ":"); } catch (Exception ex) { throw error("cannot serialize artifact"); } }
    private static void validateSchema(JsonNode value) { SCHEMAS.validateContractSchema(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static JsonNode finiteJson(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode() || value.isPojo() || value.isBinary()) return NullNode.instance; if (value.isNumber() && !Double.isFinite(value.doubleValue())) return NullNode.instance; if (value.isArray()) { ArrayNode out = MAPPER.createArrayNode(); value.forEach(child -> out.add(finiteJson(child))); return out; } if (value.isObject()) { ObjectNode out = object(); value.fields().forEachRemaining(entry -> out.set(entry.getKey(), finiteJson(entry.getValue()))); return out; } return value; }
    private static ObjectNode objectOrEmpty(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value : object(); }
    private static ArrayNode arrayOrEmpty(JsonNode value) { return value != null && value.isArray() ? (ArrayNode) value : MAPPER.createArrayNode(); }
    private static ArrayNode strings(List<String> values) { ArrayNode result = MAPPER.createArrayNode(); values.forEach(result::add); return result; }
    private static ArrayNode array(List<? extends JsonNode> values) { ArrayNode result = MAPPER.createArrayNode(); values.forEach(result::add); return result; }
    private static ObjectNode parseObject(byte[] bytes, String label) { try { JsonNode n = MAPPER.readTree(bytes); if (!n.isObject()) throw error(label + " is not JSON"); return (ObjectNode) n; } catch (IOException ex) { throw error(label + " is not JSON"); } }
    private static byte[] readBytes(Path path) { try { return Files.readAllBytes(path); } catch (IOException ex) { throw error("physical artifact is missing"); } }
    private static Path resolve(String value) { return Path.of(value).toAbsolutePath().normalize(); }
    private static IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }
    private static String requireHash(String value, String name) { if (!HASH.matcher(value == null ? "" : value).matches()) throw error(name + " must be a SHA-256 hash"); return value; }
    private static JsonNode first(JsonNode object, String... names) { if (object == null) return null; for (String name : names) { JsonNode n = object.get(name); if (n != null && !n.isNull() && !n.isMissingNode()) return n; } return null; }
    private static JsonNode path(JsonNode object, String... names) { JsonNode n = object; for (String name : names) { n = n == null ? null : n.get(name); } return n; }
    private static String text(JsonNode value) { return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText(); }
    private static String textOr(JsonNode value, String fallback) { String s = text(value); return s.isEmpty() ? fallback : s; }
    private static String textOr(JsonNode value, JsonNode fallback) { return textOr(value, text(fallback)); }
    private static double number(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return Double.NaN; if (value.isNumber()) return value.doubleValue(); try { return Double.parseDouble(value.asText().trim()); } catch (RuntimeException ex) { return Double.NaN; } }
    private static JsonNode numberNode(double value) { return Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) <= Long.MAX_VALUE ? MAPPER.getNodeFactory().numberNode((long) value) : MAPPER.getNodeFactory().numberNode(value); }
    private static double numberOr(JsonNode value, double fallback) { double n = number(value); return Double.isFinite(n) ? n : fallback; }
    private static JsonNode numberOrNode(JsonNode value) { return value == null ? NullNode.instance : value; }
    private static boolean strictTrue(JsonNode value) { return value != null && value.isBoolean() && value.booleanValue(); }
    private static boolean truth(JsonNode... values) { for (JsonNode n : values) if (n != null && ((n.isBoolean() && n.booleanValue()) || n.isNumber() && n.doubleValue() != 0 || n.isTextual() && !n.textValue().isEmpty() || n.isContainerNode())) return true; return false; }
    private static boolean contains(JsonNode array, String value) { for (JsonNode n : array) if (value.equals(text(n))) return true; return false; }
    private static JsonNode nullable(Object value) { if (value == null) return NullNode.instance; if (value instanceof Long l) return MAPPER.getNodeFactory().numberNode(l); return value instanceof JsonNode n ? n : MAPPER.valueToTree(value); }
    private static JsonNode nullableCopy(JsonNode value) { return value == null || value.isNull() ? NullNode.instance : value.deepCopy(); }
    private static void copyNullable(ObjectNode target, String out, ObjectNode source, String... candidates) { JsonNode n = first(source, candidates); target.set(out, nullableCopy(n)); }
    private static long millis(JsonNode value) { if (value == null || value.isNull()) throw error("invalid timestamp: " + text(value)); if (value.isNumber()) return (long) value.doubleValue(); String raw = text(value); try { return Instant.parse(raw).toEpochMilli(); } catch (DateTimeParseException ignored) {} try { return OffsetDateTime.parse(raw).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) {} try { return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (DateTimeParseException ignored) {} try { return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) {} throw error("invalid timestamp: " + raw); }
    private static long millis(String value) { return millis(MAPPER.getNodeFactory().textNode(value)); }
    private static String iso(JsonNode value) { return iso(value, null); }
    private static String iso(JsonNode value, Long fallback) { return iso(value == null || value.isNull() ? fallback : value.isNumber() ? value.doubleValue() : millis(value)); }
    private static String iso(long millis) { return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis)); }
    private static String iso(double millis) { return iso((long) millis); }
    private static boolean near(double a, double b) { return Math.abs(a - b) <= Math.max(1e-8, Math.max(Math.abs(a), Math.abs(b)) * 1e-8); }
    private static int signed(ObjectNode trade) { return "long".equalsIgnoreCase(text(trade.get("direction"))) ? 1 : -1; }
    private static ObjectNode exactMark(ObjectNode artifact, String series, String asset, String symbol, long timestamp) { for (JsonNode n : artifact.path("rows")) if (series.equals(text(n.get("series_type"))) && asset.equals(text(n.get("asset"))) && symbol.equals(text(n.get("symbol"))) && millis(n.get("availability_time")) == timestamp) return (ObjectNode) n; return null; }
    private static List<ObjectNode> rowsForSeries(ObjectNode artifact, String series, String asset, String symbol) { List<ObjectNode> out = new ArrayList<>(); for (JsonNode n : artifact.path("rows")) if (series.equals(text(n.get("series_type"))) && asset.equals(text(n.get("asset"))) && (symbol.isEmpty() || symbol.equals(text(n.get("symbol"))))) out.add((ObjectNode) n); out.sort((left, right) -> Long.compare(millis(left.get("availability_time")), millis(right.get("availability_time")))); return out; }
    private static String nullableHash(JsonNode metadata, String key) { return metadata != null && metadata.has(key) ? text(metadata.get(key).get("content_sha256")) : null; }
}
