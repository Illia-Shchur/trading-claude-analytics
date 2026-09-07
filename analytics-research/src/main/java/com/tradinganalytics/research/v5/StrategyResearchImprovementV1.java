package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small, independently testable contracts added by the research improvement
 * plan. It delegates physical feature/label/execution evaluation to the
 * existing v5 evaluator; these methods freeze policy, select outcome-blind
 * controls, classify evidence, and reconcile account currency.
 */
public final class StrategyResearchImprovementV1 {
    public static final List<String> STAGES = List.of(
            "FIXED_BASELINE", "FROZEN_REFINEMENT", "ADAPTIVE_CONFIRMATION");

    private StrategyResearchImprovementV1() {}

    /**
     * Computes the repository's Java/JCS hashes for a batch of JSON role files.
     * This is an assembly primitive only: it reads bytes and hashes caller
     * files, but does not attest to their provenance or authorize evidence.
     */
    public static ObjectNode canonicalHashBatch(ObjectNode options) {
        String listPath = text(options == null ? object() : options, "paths_file");
        if (listPath.isBlank()) throw failure("canonical hash batch requires paths_file");
        ArrayNode files = array();
        try {
            for (String rawPath : java.nio.file.Files.readAllLines(java.nio.file.Path.of(listPath))) {
                if (rawPath == null || rawPath.isBlank()) continue;
                java.nio.file.Path path = java.nio.file.Path.of(rawPath).toAbsolutePath().normalize();
                byte[] bytes = java.nio.file.Files.readAllBytes(path);
                JsonNode value = JsonHashes.mapper().readTree(bytes);
                ObjectNode row = object().put("path", path.toString())
                        .put("byte_sha256", JsonHashes.sha256(bytes)).put("bytes", bytes.length)
                        .put("content_sha256", JsonHashes.ownHash(value));
                if (value.isArray() || (value.isObject() && options.path("include_rows").asBoolean(false))) {
                    row.put("rows_sha256", JsonHashes.ownHash(value));
                }
                files.add(row);
            }
        } catch (java.io.IOException error) {
            throw failure("cannot hash JSON role files: " + error.getMessage());
        }
        return object().put("schema", "strategy-json-hash-batch/1").set("files", files);
    }

    public static ObjectNode freezeStagePolicy(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        if (input.has("content_sha256") && !input.path("content_sha256").asText().equals(JsonHashes.ownHash(input))) {
            throw failure("refinement input content hash is invalid");
        }
        String stage = text(input, "stage").toUpperCase(Locale.ROOT);
        if (!STAGES.contains(stage)) throw failure("unsupported research stage " + stage);
        String family = text(input, "hypothesis_family");
        if (family.isEmpty()) family = text(input, "family");
        if (!family.matches("[a-z0-9][a-z0-9._-]*")) {
            throw failure("stage policy requires a lowercase canonical hypothesis family");
        }
        String predecessor = text(input, "predecessor_sha256");
        if (!predecessor.matches("[a-f0-9]{64}")) {
            throw failure("stage policy requires predecessor_sha256");
        }
        String exposure = text(input, "exposure_head_sha256");
        if (!exposure.matches("[a-f0-9]{64}")) {
            throw failure("stage policy requires the repository-anchored exposure head");
        }
        int budget = input.path("budget").path("max_attempts").asInt(-1);
        if (budget < 1) throw failure("stage policy requires a positive max_attempts budget");
        if ("FIXED_BASELINE".equals(stage)) {
            if (!"NONE".equalsIgnoreCase(text(input.path("budget"), "optimizer"))) {
                throw failure("fixed baseline cannot invoke an optimizer");
            }
            if (budget != 1) throw failure("fixed baseline budget is exactly one attempt");
        }
        if (input.path("reset_exposure").asBoolean(false)) {
            throw failure("cumulative family exposure cannot be reset");
        }
        ObjectNode result = object();
        result.put("schema", "strategy-stage-policy/1").put("version", 1);
        result.put("stage", stage).put("hypothesis_family", family);
        result.put("predecessor_sha256", predecessor).put("exposure_head_sha256", exposure);
        result.put("max_attempts", budget);
        result.put("optimizer", "FIXED_BASELINE".equals(stage) ? "NONE" : text(input.path("budget"), "optimizer"));
        result.put("evidence_phase", "DIAGNOSTIC");
        result.put("promotion_eligible", false);
        result.put("activation_authorized", false);
        result.put("shared_physical_evaluator", true);
        result.put("cumulative_exposure", "APPEND_ONLY");
        return withHash(result);
    }

    /**
     * Freeze the small, supported refinement inventory without opening any
     * outcomes.  This is a diagnostic stage receipt: each member must use
     * the same physical evaluator and the fixed family/control contract.
     */
    public static ObjectNode freezeRefinementInventory(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        String family = text(input, "hypothesis_family");
        if (!family.matches("[a-z0-9][a-z0-9._-]*")) throw failure("refinement requires canonical hypothesis_family");
        if (input.path("reset_exposure").asBoolean(false)
                || input.path("activation_authorized").asBoolean(false)
                || input.path("promotion_eligible").asBoolean(false)
                || input.path("outcomes_opened").asBoolean(false)) {
            throw failure("refinement cannot reset exposure, activate, promote, or open outcomes");
        }
        JsonNode budget = input.path("budget");
        if (!budget.isMissingNode() && (!budget.isObject()
                || budget.path("candidate_count").asInt(-1) != 3
                || budget.path("max_attempts").asInt(-1) != 3
                || !"NONE".equalsIgnoreCase(budget.path("optimizer").asText()))) {
            throw failure("refinement budget must be exactly three fixed non-optimizer members");
        }
        for (String field : List.of("baseline_sha256", "control_spec_sha256", "experiment_sha256",
                "predecessor_sha256", "exposure_head_sha256")) {
            if (!text(input, field).matches("[a-f0-9]{64}")) throw failure("refinement requires " + field);
        }
        JsonNode raw = input.path("members");
        if (!raw.isArray() || raw.size() != 3) throw failure("refinement inventory is frozen to exactly three members");
        ArrayNode members = array();
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode member : raw) {
            if (!member.isObject()) throw failure("refinement member must be an object");
            String id = text(member, "member_id");
            double expectedShock = switch (id) {
                case "FK-DELEVERAGING-V002-R1" -> -0.08D;
                case "FK-DELEVERAGING-V002-R2" -> -0.10D;
                case "FK-DELEVERAGING-V002-R3" -> -0.12D;
                default -> Double.NaN;
            };
            if (!ids.add(id) || !Double.isFinite(expectedShock)) throw failure("unsupported refinement member id");
            double shock = numberOrNaN(member, "shock_threshold");
            double volume = numberOrNaN(member, "volume_multiple_threshold");
            double volatility = numberOrNaN(member, "volatility_threshold");
            if (!Set.of(-0.08, -0.10, -0.12).contains(shock)
                    || Double.compare(expectedShock, shock) != 0 || volume != 2.0 || volatility != 0.015) {
                throw failure("refinement member changes an unsupported frozen dimension");
            }
            members.add(member.deepCopy());
        }
        ObjectNode result = object().put("schema", "strategy-fixed-refinement/1").put("version", 1)
                .put("stage", "FROZEN_REFINEMENT").put("hypothesis_family", family)
                .put("baseline_sha256", text(input, "baseline_sha256"))
                .put("control_spec_sha256", text(input, "control_spec_sha256"))
                .put("experiment_sha256", text(input, "experiment_sha256"))
                .put("predecessor_sha256", text(input, "predecessor_sha256"))
                .put("exposure_head_sha256", text(input, "exposure_head_sha256"))
                .put("candidate_count", members.size()).put("max_attempts", members.size())
                .put("optimizer", "NONE").put("outcomes_opened", false)
                .put("evidence_phase", "DIAGNOSTIC").put("promotion_eligible", false)
                .put("activation_authorized", false).put("shared_physical_evaluator", true)
                .put("cumulative_exposure", "APPEND_ONLY")
                .put("prior_data_exposure", "DEVELOPMENT_BASELINE_OUTCOMES_ALREADY_OPENED");
        result.set("members", members);
        return withHash(result);
    }

    /** Selects the nearest eligible historical control using only pre-decision fields. */
    public static ObjectNode selectOutcomeBlindControl(ObjectNode event, ArrayNode pool, ObjectNode calipers) {
        if (event == null || pool == null) throw failure("event and control pool are required");
        Set<String> forbidden = Set.of("future_return", "label", "exit_price", "net_r", "pnl",
                "trade_outcome", "realized_volatility_after_decision");
        List<ObjectNode> matches = new ArrayList<>();
        for (JsonNode raw : pool) {
            if (!(raw instanceof ObjectNode candidate)) continue;
            if (!candidate.path("eligible").asBoolean(true) || containsAny(candidate, forbidden)) continue;
            if (!text(event, "asset").equalsIgnoreCase(text(candidate, "asset"))) continue;
            if (!timeEligible(event, candidate, calipers)) continue;
            if (candidate.path("qualifying_shock").asBoolean(false)
                    || candidate.path("open_position").asBoolean(false)
                    || !candidate.path("position_state").asText("").equalsIgnoreCase("FLAT")) continue;
            double downside = numberOrNaN(candidate, "completed_return");
            double minimumDownside = numberOrNaN(calipers, "downside_return_min");
            double maximumDownside = numberOrNaN(calipers, "downside_return_max");
            if (!Double.isFinite(downside) || !Double.isFinite(minimumDownside)
                    || !Double.isFinite(maximumDownside)
                    || downside < minimumDownside || downside > maximumDownside) continue;
            double distance = standardizedDistance(event, candidate, calipers);
            if (!Double.isFinite(distance)) continue;
            ObjectNode copy = candidate.deepCopy();
            copy.put("matching_distance", distance);
            matches.add(copy);
        }
        matches.sort(Comparator.comparingDouble((ObjectNode row) -> row.path("matching_distance").asDouble())
                .thenComparing(row -> text(row, "event_time"))
                .thenComparing(row -> text(row, "episode_id")));
        ObjectNode result = object();
        result.put("schema", "strategy-control-selection/1");
        result.put("outcome_blind", true).put("candidate_count", matches.size());
        if (matches.isEmpty()) result.putNull("control");
        else result.set("control", matches.getFirst());
        return withHash(result);
    }

    /** Reconciles account-currency PnL; raw R is diagnostic only and never added to equity. */
    public static ObjectNode reconcilePortfolio(ArrayNode trades) {
        double gross = 0, costs = 0, net = 0;
        int count = 0;
        ArrayNode rows = array();
        if (trades != null) for (JsonNode raw : trades) {
            if (!(raw instanceof ObjectNode trade)) continue;
            double quantity = number(trade, "quantity"), entry = number(trade, "entry_price");
            double exit = number(trade, "exit_price");
            double entryFee = numberOrZero(trade, "entry_fee");
            double exitFee = numberOrZero(trade, "exit_fee");
            double slippage = numberOrZero(trade, "slippage_cost");
            if (!(quantity > 0) || !(entry > 0) || !(exit > 0)) {
                throw failure("trade lacks positive quantity/prices");
            }
            boolean shortSide = "short".equalsIgnoreCase(text(trade, "direction"));
            double tradeGross = (shortSide ? entry - exit : exit - entry) * quantity;
            double tradeCosts = entryFee + exitFee + slippage;
            double tradeNet = tradeGross - tradeCosts;
            gross += tradeGross;
            costs += tradeCosts;
            net += tradeNet;
            count++;
            rows.add(object().put("signal_id", text(trade, "signal_id"))
                    .put("gross_pnl", tradeGross).put("costs", tradeCosts).put("net_pnl", tradeNet));
        }
        ObjectNode result = object();
        result.put("schema", "strategy-portfolio-reconciliation/1");
        result.put("trade_count", count).put("gross_pnl", gross).put("costs", costs).put("net_pnl", net);
        result.put("account_currency", "USDT").put("raw_r_excluded_from_equity", true);
        result.set("trades", rows);
        return withHash(result);
    }

    /** Maps simultaneous evidence failures without collapsing missing data into economic failure. */
    public static ObjectNode disposition(ObjectNode flags) {
        ObjectNode input = flags == null ? object() : flags;
        List<String> reasons = new ArrayList<>();
        if (input.path("invalid_evidence").asBoolean(false)) reasons.add("INVALID_EVIDENCE");
        if (input.path("insufficient_evidence").asBoolean(false)) reasons.add("INSUFFICIENT_EVIDENCE");
        if (input.path("economic_failure").asBoolean(false)) reasons.add("ECONOMIC_FAILURE");
        if (input.path("compute_incomplete").asBoolean(false)) reasons.add("COMPUTE_INCOMPLETE");
        if (reasons.isEmpty() && input.path("eligible").asBoolean(false)) reasons.add("ELIGIBLE");
        if (reasons.isEmpty()) reasons.add("INSUFFICIENT_EVIDENCE");
        String primary = reasons.contains("INVALID_EVIDENCE") ? "INVALID_EVIDENCE"
                : reasons.contains("COMPUTE_INCOMPLETE") ? "COMPUTE_INCOMPLETE"
                : reasons.contains("INSUFFICIENT_EVIDENCE") ? "INSUFFICIENT_EVIDENCE"
                : reasons.contains("ECONOMIC_FAILURE") ? "ECONOMIC_FAILURE" : "ELIGIBLE";
        ObjectNode result = object();
        result.put("schema", "strategy-evidence-disposition/1");
        ArrayNode reasonArray = array();
        reasons.forEach(reasonArray::add);
        result.set("reasons", reasonArray);
        result.put("primary_reason", primary);
        result.put("next_action", switch (primary) {
            case "INVALID_EVIDENCE" -> "repair_or_reacquire_pit_inputs";
            case "COMPUTE_INCOMPLETE" -> "resume_within_frozen_budget";
            case "INSUFFICIENT_EVIDENCE" -> "collect_independent_episodes_without_resetting_exposure";
            case "ECONOMIC_FAILURE" -> "record_falsification_and_stop_refinement";
            default -> "continue_governed_stage_review";
        });
        result.put("promotion_eligible", "ELIGIBLE".equals(primary) && reasons.size() == 1);
        return withHash(result);
    }

    private static double standardizedDistance(JsonNode a, JsonNode b, JsonNode calipers) {
        double sum = 0;
        int dimensions = 0;
        for (String field : List.of("prior_30_bar_return", "prior_30_bar_realized_volatility",
                "prior_30_bar_volume_zscore")) {
            double av = numberOrNaN(a, field), bv = numberOrNaN(b, field);
            if (!Double.isFinite(av) || !Double.isFinite(bv)) return Double.NaN;
            double cap = Math.abs(numberOrZero(calipers, field + "_abs"));
            if (cap <= 0 || Math.abs(av - bv) > cap) return Double.NaN;
            sum += Math.pow((av - bv) / cap, 2);
            dimensions++;
        }
        for (String field : List.of("hour_of_day", "day_of_week")) {
            if (!a.has(field) || !b.has(field) || a.path(field).asInt() != b.path(field).asInt()) {
                return Double.NaN;
            }
            dimensions++;
        }
        return dimensions == 0 ? Double.NaN : Math.sqrt(sum / dimensions);
    }

    private static boolean timeEligible(JsonNode event, JsonNode candidate, JsonNode calipers) {
        try {
            Instant eventTime = Instant.parse(text(event, "event_time"));
            Instant candidateTime = Instant.parse(text(candidate, "event_time"));
            Instant eventAvailability = Instant.parse(text(event, "availability_time"));
            Instant candidateAvailability = Instant.parse(text(candidate, "availability_time"));
            if (eventAvailability.isAfter(eventTime) || candidateAvailability.isAfter(candidateTime)
                    || candidateAvailability.isAfter(eventTime)) return false;
            long minimumLag = Math.round(numberOrZero(calipers, "minimum_prior_lag_hours") * 3_600_000d);
            long maximumLookback = Math.round(numberOrZero(calipers, "maximum_prior_lookback_days") * 86_400_000d);
            long distance = Duration.between(candidateTime, eventTime).toMillis();
            long lifecycle = Math.round(numberOrZero(calipers, "maximum_lifecycle_hours") * 3_600_000d);
            if (lifecycle <= 0) lifecycle = 240L * 3_600_000L;
            return distance >= minimumLag && distance <= maximumLookback
                    && candidateTime.plusMillis(lifecycle).compareTo(eventTime.minusMillis(lifecycle)) <= 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean containsAny(ObjectNode value, Set<String> fields) {
        for (String field : fields) if (value.has(field)) return true;
        return false;
    }

    private static ObjectNode withHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove("content_sha256");
        copy.put("content_sha256", JsonHashes.canonicalSha256(copy));
        return copy;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
    private static String text(JsonNode value, String field) {
        return value == null ? "" : value.path(field).asText("");
    }
    private static double number(JsonNode value, String field) {
        double n = numberOrNaN(value, field);
        if (!Double.isFinite(n)) throw failure("invalid " + field);
        return n;
    }
    private static double numberOrNaN(JsonNode value, String field) {
        return value != null && value.has(field) ? value.path(field).asDouble(Double.NaN) : Double.NaN;
    }
    private static double numberOrZero(JsonNode value, String field) {
        return value != null && value.has(field) ? value.path(field).asDouble(0) : 0;
    }
    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

}
