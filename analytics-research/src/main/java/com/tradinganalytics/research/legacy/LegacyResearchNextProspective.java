package com.tradinganalytics.research.legacy;

import static com.tradinganalytics.research.legacy.LegacyResearchSupport.JSON;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.cloneNode;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.jsNumber;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.objectCopy;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.rows;
import static com.tradinganalytics.research.legacy.LegacyResearchSupport.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable future-only reservation, event-chain, eligibility, and drift controls. */
final class LegacyResearchNextProspective {
    private static final Set<String> LINEAGE_KEYS = Set.of(
            "stack_sha256", "candidate_sha256", "data_manifest_sha256", "precommit_sha256",
            "strategy_sha256", "strategy", "execution_policy_sha256", "risk_policy_sha256");
    private static final Set<String> SIGNAL_KEYS = Set.of(
            "signal_id", "asset", "direction", "decision", "entry_time", "horizon_ms",
            "horizon_end_time", "availability_receipt_sha256", "capture_time", "lineage_sha256");
    private static final Set<String> OUTCOME_KEYS = Set.of(
            "signal_id", "asset", "entry_time", "exit_time", "net_pnl", "net_r", "slippage_r",
            "funding_pnl", "availability_receipt_sha256", "capture_time", "lineage_sha256");

    private LegacyResearchNextProspective() {}

    static ObjectNode makeProspectiveReservation(JsonNode options) {
        JsonNode frozenNode = LegacyResearchNext.first(options, "frozenAt", "frozen_at");
        long frozen = LegacyResearchNext.timestamp(frozenNode == null
                ? JSON.textNode(LegacyResearchNext.iso(System.currentTimeMillis())) : frozenNode, "frozenAt");
        long start = LegacyResearchNext.timestamp(LegacyResearchNext.first(options, "startAt", "start_at"), "startAt");
        if (!(start > frozen)) throw new IllegalArgumentException("prospective start must be strictly after the freeze timestamp");
        int minimumDays = (int) LegacyResearchNext.optionDouble(options, "minimumDays", "minimum_days", 60);
        int minimumPortfolioTrades = (int) LegacyResearchNext.optionDouble(options, "minimumPortfolioTrades", "minimum_portfolio_trades", 25);
        int minimumTradesPerAsset = (int) LegacyResearchNext.optionDouble(options, "minimumTradesPerAsset", "minimum_trades_per_asset", 8);
        if (minimumDays < 60) throw new IllegalArgumentException("prospective minimum_days cannot be below 60");
        if (minimumPortfolioTrades < 25 || minimumTradesPerAsset < 8) throw new IllegalArgumentException("prospective trade minimums cannot be below 25 total and 8 per proposed asset");
        JsonNode lineage = options == null || options.get("lineage") == null ? JSON.objectNode() : options.get("lineage");
        validateLineage(lineage);
        List<String> proposed = LegacyResearchNext.stringList(LegacyResearchNext.first(options, "proposedAssets", "proposed_assets"), LegacyResearchNext.UNIVERSE);
        Set<String> normalized = new LinkedHashSet<>(); proposed.stream().map(LegacyResearchNext::asset).forEach(normalized::add);
        List<String> assets = normalized.stream().sorted().toList();
        if (assets.isEmpty()) throw new IllegalArgumentException("prospective reservation requires proposed assets");
        ObjectNode reservation = JSON.objectNode().put("frozen_at", LegacyResearchNext.iso(frozen))
                .put("start_at", LegacyResearchNext.iso(start)).put("minimum_days", minimumDays)
                .put("minimum_portfolio_trades", minimumPortfolioTrades)
                .put("minimum_trades_per_asset", minimumTradesPerAsset);
        reservation.set("proposed_assets", LegacyResearchNext.strings(assets));
        reservation.set("lineage", cloneNode(lineage));
        reservation.put("lineage_sha256", LegacyResearchNext.hash(lineage));
        JsonNode monitoring = LegacyResearchNext.first(options, "monitoringContract", "monitoring_contract");
        reservation.set("monitoring_contract", cloneNode(monitoring == null ? JSON.objectNode() : monitoring));
        reservation.put("status", "FROZEN");
        return LegacyResearchNext.withHash(reservation);
    }

    static ObjectNode makeProspectiveLedger(JsonNode reservation) {
        if (reservation == null || !"FROZEN".equals(text(reservation.get("status")))
                || !LegacyResearchNext.ownHash(reservation).equals(text(reservation.get("content_sha256")))) {
            throw new IllegalArgumentException("prospective reservation is not frozen and hashed");
        }
        validateLineage(reservation.get("lineage"));
        if (!text(reservation.get("lineage_sha256")).equals(LegacyResearchNext.hash(reservation.get("lineage")))) {
            throw new IllegalArgumentException("prospective reservation lineage hash mismatch");
        }
        ObjectNode ledger = JSON.objectNode().put("schema", LegacyResearchNext.PROSPECTIVE_SCHEMA);
        ledger.set("reservation", cloneNode(reservation)); ledger.set("events", JSON.arrayNode());
        ledger.put("head_sha256", LegacyResearchNext.hash("GENESIS"));
        ledger = LegacyResearchNext.withHash(ledger); LegacyResearchNext.validateSchema(ledger); return ledger;
    }

    static ObjectNode appendProspectiveEvent(JsonNode ledgerNode, JsonNode options) {
        validateProspectiveLedger(ledgerNode);
        String kind = text(options == null ? null : options.get("kind"));
        JsonNode payload = options == null || options.get("payload") == null ? JSON.objectNode() : options.get("payload");
        if (!List.of("SIGNAL", "OUTCOME").contains(kind)) throw new IllegalArgumentException("unsupported prospective event kind; only SIGNAL and OUTCOME are recordable");
        for (JsonNode row : rows(ledgerNode.get("events"))) {
            if (kind.equals(text(row.get("kind"))) && text(row.path("payload").get("signal_id")).equals(text(payload.get("signal_id")))) {
                throw new IllegalArgumentException("SIGNAL".equals(kind) ? "duplicate SIGNAL signal_id" : "duplicate OUTCOME resolution");
            }
        }
        long decision = LegacyResearchNext.timestamp(LegacyResearchNext.first(options, "decisionTime", "decision_time"), "decisionTime");
        long start = LegacyResearchNext.timestamp(ledgerNode.path("reservation").get("start_at"), "prospective start");
        if (decision < start) throw new IllegalArgumentException("pre-freeze prospective signal is not eligible");
        ObjectNode event = JSON.objectNode().put("sequence", ledgerNode.path("events").size()).put("kind", kind)
                .put("decision_time", LegacyResearchNext.iso(decision));
        // Node sequence is one-based.
        event.put("sequence", ledgerNode.path("events").size() + 1);
        JsonNode outcomeNode = LegacyResearchNext.first(options, "outcomeTime", "outcome_time");
        if (outcomeNode == null || outcomeNode.isNull()) {
            if ("OUTCOME".equals(kind)) event.put("outcome_time", LegacyResearchNext.iso(
                    LegacyResearchNext.timestamp(payload.get("exit_time"), "OUTCOME exit_time")));
            else event.set("outcome_time", NullNode.instance);
        } else event.put("outcome_time", LegacyResearchNext.iso(LegacyResearchNext.timestamp(outcomeNode, "outcomeTime")));
        event.set("payload", cloneNode(payload)); event.put("previous_sha256", text(ledgerNode.get("head_sha256")));
        validatePayload(kind, event.get("payload"), ledgerNode, event);
        event.put("event_sha256", LegacyResearchNext.hash(event));
        ObjectNode next = objectCopy(ledgerNode, "ledger"); ((ArrayNode) next.get("events")).add(event);
        next.put("head_sha256", text(event.get("event_sha256"))); next.put("content_sha256", LegacyResearchNext.ownHash(next));
        validateProspectiveLedger(next); return next;
    }

    static boolean validateProspectiveLedger(JsonNode ledger) {
        if (ledger == null || !LegacyResearchNext.PROSPECTIVE_SCHEMA.equals(text(ledger.get("schema")))
                || !LegacyResearchNext.ownHash(ledger).equals(text(ledger.get("content_sha256")))) {
            throw new IllegalArgumentException("prospective ledger hash/schema is invalid");
        }
        JsonNode reservation = ledger.get("reservation");
        if (reservation == null || !"FROZEN".equals(text(reservation.get("status")))
                || !LegacyResearchNext.ownHash(reservation).equals(text(reservation.get("content_sha256")))
                || !LegacyResearchNext.hash(reservation.get("lineage")).equals(text(reservation.get("lineage_sha256")))) {
            throw new IllegalArgumentException("prospective reservation or lineage tampering");
        }
        validateLineage(reservation.get("lineage")); String head = LegacyResearchNext.hash("GENESIS"); int index = 0;
        for (JsonNode event : rows(ledger.get("events"))) {
            index++; ObjectNode unhashed = objectCopy(event, "prospective event"); unhashed.remove("event_sha256");
            if (event.path("sequence").asInt() != index || !head.equals(text(event.get("previous_sha256")))
                    || !LegacyResearchNext.hash(unhashed).equals(text(event.get("event_sha256")))) {
                throw new IllegalArgumentException("prospective ledger hash chain is broken");
            }
            validatePayload(text(event.get("kind")), event.get("payload"), ledger, event);
            head = text(event.get("event_sha256"));
        }
        if (!head.equals(text(ledger.get("head_sha256")))) throw new IllegalArgumentException("prospective ledger head mismatch");
        return true;
    }

    static ObjectNode prospectiveEligibility(JsonNode ledger, JsonNode options) {
        validateProspectiveLedger(ledger); long now = now(options, "now", "eligibility now");
        List<JsonNode> signals = rows(ledger.get("events")).stream().filter(row -> "SIGNAL".equals(text(row.get("kind")))).toList();
        List<JsonNode> outcomes = rows(ledger.get("events")).stream().filter(row -> "OUTCOME".equals(text(row.get("kind")))).toList();
        long start = LegacyResearchNext.timestamp(ledger.path("reservation").get("start_at"), "timestamp");
        double days = Math.max(0, (now - start) / 86_400_000d); Set<String> signalIds = new LinkedHashSet<>(); signals.forEach(row -> signalIds.add(text(row.path("payload").get("signal_id"))));
        List<JsonNode> completed = outcomes.stream().filter(row -> LegacyResearchNext.present(row.get("outcome_time")) && LegacyResearchNext.timestamp(row.get("outcome_time"), "timestamp") <= now && signalIds.contains(text(row.path("payload").get("signal_id")))).toList();
        Map<String, Integer> byAsset = new LinkedHashMap<>(); for (JsonNode row : rows(ledger.path("reservation").get("proposed_assets"))) byAsset.put(text(row), 0); for (JsonNode event : completed) { String asset = text(event.path("payload").get("asset")).toLowerCase(Locale.ROOT); byAsset.put(asset, byAsset.getOrDefault(asset, 0) + 1); }
        int requiredDays = (int) Math.max(60, Math.max(LegacyResearchNext.optionDouble(options, "minDays", "min_days", 60), ledger.path("reservation").path("minimum_days").asInt(60)));
        int requiredTotal = (int) Math.max(25, Math.max(LegacyResearchNext.optionDouble(options, "minPortfolioTrades", "min_portfolio_trades", 25), ledger.path("reservation").path("minimum_portfolio_trades").asInt(25)));
        int requiredPerAsset = (int) Math.max(8, Math.max(LegacyResearchNext.optionDouble(options, "minTradesPerAsset", "min_trades_per_asset", 8), ledger.path("reservation").path("minimum_trades_per_asset").asInt(8)));
        JsonNode evidence = LegacyResearchNext.first(options, "evidenceArtifacts", "evidence_artifacts");
        ObjectNode gates = JSON.objectNode();
        for (String key : List.of("monitoring", "statistical", "stress", "portfolio")) {
            JsonNode artifact = evidence == null ? null : evidence.get(key);
            if (artifact == null) artifact = contractedEvidence(ledger.path("reservation").path("monitoring_contract").get("evidence"), key);
            String schema = "monitoring".equals(key) ? "prospective-monitoring/2" : "portfolio".equals(key) ? "strategy-portfolio-result/1" : "strategy-prospective-gate/1";
            boolean schemaOk = artifact != null && schema.equals(text(artifact.get("schema"))) && (!("statistical".equals(key) || "stress".equals(key)) || key.equals(text(artifact.get("gate"))));
            boolean marksOk = !"portfolio".equals(key) || artifact != null && artifact.path("marks_bound").asBoolean(false);
            boolean monitoringOk = !"monitoring".equals(key) || artifact != null && artifact.path("fail_closed_on_drift").asBoolean(false) && "CANDIDATE_REVIEW".equals(text(artifact.get("decision")));
            boolean verified = artifact != null && schemaOk && text(artifact.get("content_sha256")).equals(LegacyResearchNext.ownHash(artifact))
                    && text(artifact.get("ledger_head_sha256")).equals(text(ledger.get("head_sha256")))
                    && text(artifact.get("lineage_sha256")).equals(text(ledger.path("reservation").get("lineage_sha256")))
                    && artifact.path("pass").asBoolean(false) && marksOk && monitoringOk;
            gates.put(key, verified);
        }
        boolean pass = days >= requiredDays && completed.size() >= requiredTotal && byAsset.values().stream().allMatch(value -> value >= requiredPerAsset) && rows(gates).stream().allMatch(JsonNode::asBoolean);
        ObjectNode minimums = JSON.objectNode().put("minDays", requiredDays).put("minPortfolioTrades", requiredTotal).put("minTradesPerAsset", requiredPerAsset);
        ObjectNode trades = JSON.objectNode(); byAsset.forEach(trades::put);
        ObjectNode result = JSON.objectNode().put("pass", pass).put("days", days).put("signal_count", signals.size())
                .put("completed_outcome_count", completed.size()).put("completed_portfolio_trades", completed.size());
        result.set("trades_by_asset", trades); result.set("gates", gates); result.set("minimums", minimums);
        if (pass) result.set("reason", NullNode.instance); else result.put("reason", "FAST_OR_AUTHORITATIVE_GATES_NOT_MET"); return result;
    }

    static ObjectNode monitorProspective(JsonNode options) {
        JsonNode ledger = options == null ? null : options.get("ledger"); validateProspectiveLedger(ledger);
        long now = now(options, "now", "monitor now"); JsonNode expected = options == null || options.get("expected") == null ? JSON.objectNode() : options.get("expected"); JsonNode tolerances = options == null || options.get("tolerances") == null ? JSON.objectNode() : options.get("tolerances"); double threshold = LegacyResearchNext.optionDouble(options, "cusumThreshold", "cusum_threshold", 4);
        List<JsonNode> observations = rows(ledger.get("events")).stream().filter(row -> "OUTCOME".equals(text(row.get("kind"))) && LegacyResearchNext.present(row.get("outcome_time")) && LegacyResearchNext.timestamp(row.get("outcome_time"), "timestamp") <= now).toList();
        List<Double> returns = observations.stream().map(row -> jsNumber(LegacyResearchNext.first(row.path("payload"), "net_r", "net_pnl", "r"))).filter(Double::isFinite).toList(); long wins = returns.stream().filter(value -> value > 0).count(); Double mean = returns.isEmpty() ? null : returns.stream().mapToDouble(Double::doubleValue).sum() / returns.size(); double frequency = observations.size() / Math.max(1, (now - LegacyResearchNext.timestamp(ledger.path("reservation").get("start_at"), "timestamp")) / 86_400_000d);
        List<Double> slippages = observations.stream().map(row -> jsNumber(row.path("payload").get("slippage_r"))).filter(Double::isFinite).toList(); Double averageSlippage = averageOrNull(slippages); List<Double> holdings = new ArrayList<>(); for (JsonNode row : observations) { double opened = jsNumber(row.path("payload").get("entry_time")), closed = jsNumber(row.path("payload").get("exit_time")); if (opened > 0 && closed > opened) holdings.add((closed - opened) / 3_600_000); } Double averageHolding = averageOrNull(holdings);
        ObjectNode actual = JSON.objectNode().put("completed_trades", observations.size()).put("frequency_per_day", frequency); if (returns.isEmpty()) actual.set("win_rate", NullNode.instance); else actual.put("win_rate", wins / (double) returns.size()); if (mean == null) actual.set("expectancy", NullNode.instance); else actual.put("expectancy", mean); putNullable(actual, "average_slippage", averageSlippage); putNullable(actual, "average_holding_hours", averageHolding);
        double expectedReturn = jsNumber(LegacyResearchNext.first(expected, "expectancy", "expectancy_r")), cusum = 0; boolean expectedFinite = Double.isFinite(expectedReturn); for (double value : returns) { double baseline = expectedFinite ? expectedReturn : 0; cusum = Math.min(0, cusum + value - baseline); }
        boolean expectancyDrift = expectedFinite && mean != null && mean < expectedReturn - LegacyResearchNext.optionDouble(tolerances, "expectancy", "expectancy", 0); double expectedWin = jsNumber(expected.get("win_rate")); boolean winDrift = Double.isFinite(expectedWin) && actual.get("win_rate") != null && actual.get("win_rate").isNumber() && jsNumber(actual.get("win_rate")) < expectedWin - LegacyResearchNext.optionDouble(tolerances, "win_rate", "win_rate", 0); boolean cusumDrift = Math.abs(cusum) >= threshold;
        ObjectNode drift = JSON.objectNode().put("expectancy", expectancyDrift).put("win_rate", winDrift).put("cusum", cusumDrift); boolean any = expectancyDrift || winDrift || cusumDrift; ObjectNode controls = JSON.objectNode().put("cusum", cusum).put("cusum_threshold", threshold).put("control_chart", "one-sided lower-tail CUSUM"); controls.set("drift", drift);
        ObjectNode result = JSON.objectNode().put("schema", "prospective-monitoring/2").put("ledger_head_sha256", text(ledger.get("head_sha256"))).put("lineage_sha256", text(ledger.path("reservation").get("lineage_sha256"))).put("pass", !any).put("decision", any ? "SHADOW" : "CANDIDATE_REVIEW").put("fail_closed_on_drift", true); result.set("expected", cloneNode(expected)); result.set("actual", actual); result.set("controls", controls); result = LegacyResearchNext.withHash(result); LegacyResearchNext.validateSchema(result); return result;
    }

    private static void validateLineage(JsonNode lineage) {
        if (lineage == null || !lineage.isObject() || lineage.isEmpty()) throw new IllegalArgumentException("prospective reservation requires exact frozen lineage");
        lineage.fields().forEachRemaining(entry -> { if (!LINEAGE_KEYS.contains(entry.getKey())) throw new IllegalArgumentException("unsupported prospective lineage key " + entry.getKey()); if (!LegacyResearchNext.isSha(entry.getValue())) throw new IllegalArgumentException("lineage." + entry.getKey() + " must be a SHA-256 hash"); });
    }

    private static String validatePayload(String kind, JsonNode payload, JsonNode ledger, JsonNode event) {
        Set<String> allowed = "SIGNAL".equals(kind) ? SIGNAL_KEYS : "OUTCOME".equals(kind) ? OUTCOME_KEYS : null; if (allowed == null) throw new IllegalArgumentException("unsupported prospective event kind " + kind); if (payload == null || !payload.isObject()) payload = JSON.objectNode(); for (String key : LegacyResearchNext.fieldNames(payload)) if (!allowed.contains(key)) throw new IllegalArgumentException(kind + " payload contains unsupported field " + key);
        if ("SIGNAL".equals(kind)) { String id = LegacyResearchNext.requiredText(payload.get("signal_id"), "SIGNAL signal_id"); String asset = LegacyResearchNext.asset(payload.get("asset")); if (!LegacyResearchNext.stringList(ledger.path("reservation").get("proposed_assets"), List.of()).contains(asset)) throw new IllegalArgumentException("SIGNAL asset " + asset + " is not in frozen proposed_assets"); if (!List.of("long", "short").contains(text(payload.get("direction")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("SIGNAL direction is required"); if (!LegacyResearchNext.DECISIONS.contains(text(payload.get("decision")))) throw new IllegalArgumentException("SIGNAL decision must be REJECTED, SHADOW, or CANDIDATE_REVIEW"); requireHash(payload.get("availability_receipt_sha256"), "SIGNAL availability_receipt_sha256"); requireHash(payload.get("lineage_sha256"), "SIGNAL lineage_sha256"); if (!text(payload.get("lineage_sha256")).equals(text(ledger.path("reservation").get("lineage_sha256")))) throw new IllegalArgumentException("SIGNAL lineage does not match frozen reservation"); if (!validTime(payload.get("capture_time")) || LegacyResearchNext.timestamp(payload.get("capture_time"), "capture_time") < LegacyResearchNext.timestamp(event.get("decision_time"), "decision_time")) throw new IllegalArgumentException("SIGNAL capture_time must follow signal decision time"); if (!LegacyResearchNext.present(payload.get("horizon_ms")) && !LegacyResearchNext.present(payload.get("horizon_end_time"))) throw new IllegalArgumentException("SIGNAL declared horizon is required"); long duplicates = rows(ledger.get("events")).stream().filter(row -> "SIGNAL".equals(text(row.get("kind"))) && id.equals(text(row.path("payload").get("signal_id")))).count(); if (duplicates > 1) throw new IllegalArgumentException("duplicate SIGNAL signal_id"); return id; }
        String id = LegacyResearchNext.requiredText(payload.get("signal_id"), "OUTCOME signal_id"); JsonNode signal = rows(ledger.get("events")).stream().filter(row -> "SIGNAL".equals(text(row.get("kind"))) && id.equals(text(row.path("payload").get("signal_id")))).findFirst().orElse(null); if (signal == null) throw new IllegalArgumentException("OUTCOME requires one matching prior SIGNAL"); long duplicates = rows(ledger.get("events")).stream().filter(row -> "OUTCOME".equals(text(row.get("kind"))) && id.equals(text(row.path("payload").get("signal_id")))).count(); if (duplicates > 1) throw new IllegalArgumentException("duplicate OUTCOME resolution"); long outcome = LegacyResearchNext.timestamp(LegacyResearchNext.first(event, "outcome_time", "payload.exit_time"), "OUTCOME resolution"), signalTime = LegacyResearchNext.timestamp(signal.get("decision_time"), "matching SIGNAL decision_time"), eventDecision = LegacyResearchNext.present(event.get("decision_time")) ? LegacyResearchNext.timestamp(event.get("decision_time"), "OUTCOME decision_time") : signalTime, horizon = LegacyResearchNext.present(signal.path("payload").get("horizon_end_time")) ? LegacyResearchNext.timestamp(signal.path("payload").get("horizon_end_time"), "SIGNAL horizon_end_time") : signalTime + (long) jsNumber(signal.path("payload").get("horizon_ms")); if (!(eventDecision >= signalTime && outcome > signalTime && outcome >= horizon)) throw new IllegalArgumentException("OUTCOME must resolve after signal and declared horizon"); String asset = LegacyResearchNext.asset(payload.get("asset")); if (!asset.equals(text(signal.path("payload").get("asset")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("OUTCOME asset does not match SIGNAL"); if (!Double.isFinite(jsNumber(LegacyResearchNext.first(payload, "net_pnl", "net_r")))) throw new IllegalArgumentException("OUTCOME requires numeric net_pnl or net_r"); requireHash(payload.get("availability_receipt_sha256"), "OUTCOME availability_receipt_sha256"); requireHash(payload.get("lineage_sha256"), "OUTCOME lineage_sha256"); if (!text(payload.get("lineage_sha256")).equals(text(ledger.path("reservation").get("lineage_sha256")))) throw new IllegalArgumentException("OUTCOME lineage does not match frozen reservation"); if (!validTime(payload.get("capture_time")) || LegacyResearchNext.timestamp(payload.get("capture_time"), "capture_time") < outcome) throw new IllegalArgumentException("OUTCOME capture_time must follow resolution time"); return id;
    }

    private static void requireHash(JsonNode value, String name) { if (!LegacyResearchNext.isSha(value)) throw new IllegalArgumentException(name + " must be a SHA-256 hash"); }
    private static boolean validTime(JsonNode value) { try { LegacyResearchNext.timestamp(value, "timestamp"); return LegacyResearchNext.present(value); } catch (RuntimeException ignored) { return false; } }
    private static long now(JsonNode options, String key, String name) { JsonNode value = options == null ? null : options.get(key); return value == null ? System.currentTimeMillis() : LegacyResearchNext.timestamp(value, name); }
    private static JsonNode contractedEvidence(JsonNode values, String key) { if (values != null && values.isArray()) for (JsonNode row : values) if (key.equals(text(row.get("key")))) return row.get("artifact"); return null; }
    private static Double averageOrNull(List<Double> values) { if (values.isEmpty()) return null; double average = values.stream().mapToDouble(Double::doubleValue).sum() / values.size(); return average == 0 ? null : average; }
    private static void putNullable(ObjectNode node, String key, Double value) { if (value == null) node.set(key, NullNode.instance); else node.put(key, value); }
}
