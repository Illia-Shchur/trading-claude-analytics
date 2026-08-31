package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import static com.tradinganalytics.research.legacy.LegacyResearchSupport.*;

/** Exact Java counterpart of {@code tools/strategy-research-v3.mjs}. */
public final class LegacyResearchV3 {
    public static final String EXPERIMENT_V3_SCHEMA = "strategy-experiment/3";
    public static final String EVIDENCE_BUNDLE_V2_SCHEMA = "strategy-evidence-bundle/2";
    public static final String RUN_V3_SCHEMA = "strategy-run/3";
    public static final String DATA_MANIFEST_V2_SCHEMA = "strategy-data-manifest/2";
    public static final String ACCEPTANCE_CONTRACT_SCHEMA = "strategy-acceptance-contract/1";
    public static final String ATTESTATION_SCHEMA = "strategy-attestation/1";
    public static final String RESERVATION_SCHEMA = "strategy-confirmation-reservation/1";
    public static final List<String> V3_PHASES = List.of("DEVELOPMENT", "WALK_FORWARD_OOS", "EXPOSED_CONFIRMATION", "CI_ATTESTED_CONFIRMATION", "SEALED_CONFIRMATION", "PROSPECTIVE_LIVE");
    public static final List<String> DECISIONS = List.of("REJECTED", "SHADOW", "CANDIDATE_REVIEW");
    public static final List<String> CORE_UNIVERSE = List.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    public static final List<String> STAGE_CHAIN = List.of("CORE_PREMISE", "ENTRY_TIMING", "RISK_LIFECYCLE", "INDEPENDENT_CONTEXT", "COMPOSITE_SCORE");
    public static final String CONFIRMATION_RESERVATION_DIR = "strategy-research/confirmations";
    public static final String TRAINING_SELECTION_POLICY_SCHEMA = "strategy-training-selection-policy/1";
    public static final List<String> REQUIRED_STRESS_SCENARIOS = List.of("DOUBLED_FEES_SLIPPAGE", "DOUBLED_FUNDING", "ADVERSE_GAP", "LIQUIDITY_CAPACITY", "VENUE_OUTAGE");
    public static final ObjectNode DEFAULT_STRESS_PARAMETERS = defaultStressParameters();
    public static final ObjectNode BALANCED_SWING_V1 = balancedSwingV1();

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[a-f0-9]{40}$");
    private static final Pattern REPOSITORY = Pattern.compile("^[^/\\s]+/[^/\\s]+$");
    private static final Set<String> LATE_PHASES = Set.of("WALK_FORWARD_OOS", "EXPOSED_CONFIRMATION", "CI_ATTESTED_CONFIRMATION", "PROSPECTIVE_LIVE");
    private static final DateTimeFormatter JS_ISO_INSTANT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private LegacyResearchV3() {}

    public static String stable(JsonNode value) { return LegacyResearchSupport.stable(value); }
    public static String hash(JsonNode value) { return LegacyResearchSupport.hash(value); }
    public static String hash(String value) { return LegacyResearchSupport.hash(value); }
    public static String hash(byte[] value) { return LegacyResearchSupport.hash(value); }
    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }
    public static String ownHash(JsonNode value, String field) { return LegacyResearchSupport.ownHash(value, field); }
    public static ObjectNode withHash(JsonNode value) { return withHash(value, "content_sha256"); }
    public static ObjectNode withHash(JsonNode value, String field) { return LegacyResearchSupport.withHash(value, field); }

    private static ObjectNode defaultStressParameters() {
        ObjectNode out = JSON.objectNode();
        out.set("DOUBLED_FEES_SLIPPAGE", JSON.objectNode().put("multiplier", 2).put("minimum_observations", 1).put("minimum_expectancy_r", 0));
        out.set("DOUBLED_FUNDING", JSON.objectNode().put("multiplier", 2).put("minimum_observations", 1).put("minimum_expectancy_r", 0));
        out.set("ADVERSE_GAP", JSON.objectNode().put("debit_r", .25).put("gap_model", "declared_gap_or_observed_mae").put("minimum_observations", 1).put("minimum_expectancy_r", 0));
        out.set("LIQUIDITY_CAPACITY", JSON.objectNode().put("capacity_model", "venue_available_liquidity_notional").put("maximum_participation_rate", .05).put("minimum_observations", 1).put("minimum_expectancy_r", 0));
        ArrayNode windows = JSON.arrayNode().add(JSON.objectNode().put("venue", "*").put("start_time", "2020-03-12T00:00:00Z").put("end_time", "2020-03-20T00:00:00Z"));
        out.set("VENUE_OUTAGE", JSON.objectNode().put("outage_rule", "declared_blackout_windows").set("blackout_windows", windows));
        ((ObjectNode) out.get("VENUE_OUTAGE")).put("minimum_observations", 1).put("minimum_expectancy_r", 0);
        return out;
    }

    private static ObjectNode balancedSwingV1() {
        ObjectNode out = JSON.objectNode();
        out.put("minimum_independent_episodes", 30).put("minimum_completed_episodes", 30)
                .put("minimum_expectancy_r", 0).put("minimum_search_adjusted_expectancy_r", 0)
                .put("minimum_r_profit_factor", 1.1).put("minimum_account_profit_factor", 1.1)
                .put("minimum_total_return", 0).put("minimum_bootstrap_p20_expectancy_r", 0)
                .put("maximum_candidate_set_p_value", .10).put("maximum_drawdown_pct", 5)
                .put("maximum_drawdown_r", 10).put("maximum_cost_r", .25)
                .put("minimum_positive_years", 2).put("minimum_episodes_per_positive_year", 6)
                .put("minimum_positive_blocks", 2).put("maximum_negative_block_expectancy_r", -.10)
                .put("minimum_doubled_cost_expectancy_r", 0).put("minimum_doubled_cost_account_profit_factor", 1)
                .put("minimum_coverage_fraction", .95).put("maximum_undeclared_gap_bars", 2)
                .put("minimum_wfo_oos_episodes", 20).put("minimum_wfo_positive_folds", 3);
        return out;
    }

    public static ObjectNode makeTrainingSelectionPolicy() {
        return makeTrainingSelectionPolicy(JSON.objectNode());
    }

    public static ObjectNode makeTrainingSelectionPolicy(JsonNode options) {
        JsonNode input = options == null ? JSON.objectNode() : options;
        ObjectNode value = JSON.objectNode().put("schema", TRAINING_SELECTION_POLICY_SCHEMA)
                .put("minimum_completed_trades", optionNumber(input, "minimumCompletedTrades", 1))
                .put("minimum_expectancy_r", optionNumber(input, "minimumExpectancyR", 0))
                .put("objective", optionText(input, "objective", "expectancy_r_desc"))
                .put("tie_break", optionText(input, "tieBreak", "candidate_id_asc"))
                .put("nested_search_control", optionText(input, "nestedSearchControl", "WFO_NESTED_SELECTION"));
        return withHash(value);
    }

    public static boolean validateTrainingSelectionPolicy(JsonNode policy) {
        if (policy == null || !TRAINING_SELECTION_POLICY_SCHEMA.equals(text(policy.get("schema")))
                || !text(policy.get("content_sha256")).equals(ownHash(policy))) {
            throw new IllegalArgumentException("WFO requires a valid hashed training selection policy");
        }
        Set<String> allowed = Set.of("schema", "minimum_completed_trades", "minimum_expectancy_r", "objective", "tie_break", "nested_search_control", "content_sha256");
        rejectUnknown(policy, allowed, "training selection policy unknown field: ");
        double completed = jsNumber(policy.get("minimum_completed_trades"));
        double expectancy = jsNumber(policy.get("minimum_expectancy_r"));
        if (completed != Math.rint(completed) || completed < 1 || !Double.isFinite(expectancy)) throw new IllegalArgumentException("training selection policy thresholds are invalid");
        if (!"expectancy_r_desc".equals(text(policy.get("objective"))) || !"candidate_id_asc".equals(text(policy.get("tie_break")))
                || !"WFO_NESTED_SELECTION".equals(text(policy.get("nested_search_control")))) {
            throw new IllegalArgumentException("training selection policy objective/tie-break/search control is not frozen");
        }
        return true;
    }

    public static ObjectNode makeAcceptanceContract() { return makeAcceptanceContract(JSON.objectNode()); }

    public static ObjectNode makeAcceptanceContract(JsonNode options) {
        JsonNode input = options == null ? JSON.objectNode() : options;
        String contractId = optionText(input, "contractId", "balanced-swing-v1");
        String profile = optionText(input, "profile", "balanced-swing-v1");
        JsonNode gates = input.has("gates") ? input.get("gates") : BALANCED_SWING_V1;
        JsonNode scenarios = input.has("stressScenarios") ? input.get("stressScenarios") : strings(REQUIRED_STRESS_SCENARIOS);
        ArrayNode stressRows = JSON.arrayNode();
        for (JsonNode row : rows(scenarios)) {
            if (row.isTextual()) {
                String name = row.textValue();
                stressRows.add(JSON.objectNode().put("name", name).put("required", true)
                        .set("parameters", DEFAULT_STRESS_PARAMETERS.has(name) ? cloneNode(DEFAULT_STRESS_PARAMETERS.get(name)) : JSON.objectNode()));
            } else {
                ObjectNode item = JSON.objectNode();
                item.set("name", cloneNode(row.get("name")));
                item.set("required", cloneNode(row.get("required")));
                if (row.has("parameters")) item.set("parameters", cloneNode(row.get("parameters")));
                stressRows.add(item);
            }
        }
        ObjectNode contract = JSON.objectNode().put("schema", ACCEPTANCE_CONTRACT_SCHEMA).put("contract_id", contractId).put("profile", profile);
        contract.set("gates", cloneNode(gates));
        contract.set("stress_scenarios", stressRows);
        return withHash(contract);
    }

    public static boolean validateAcceptanceContract(JsonNode contract) {
        if (contract == null || !ACCEPTANCE_CONTRACT_SCHEMA.equals(text(contract.get("schema")))) throw new IllegalArgumentException("strategy-acceptance-contract/1 is required");
        if (!text(contract.get("content_sha256")).equals(ownHash(contract))) throw new IllegalArgumentException("acceptance contract content hash mismatch");
        rejectUnknown(contract, Set.of("schema", "contract_id", "profile", "gates", "stress_scenarios", "content_sha256"), "acceptance contract unknown field: ");
        if (!bool(contract.get("contract_id")) || !bool(contract.get("profile")) || contract.get("gates") == null || !contract.get("gates").isObject()) throw new IllegalArgumentException("acceptance contract is incomplete");
        Set<String> gateKeys = new LinkedHashSet<>(); BALANCED_SWING_V1.fieldNames().forEachRemaining(gateKeys::add);
        contract.get("gates").fieldNames().forEachRemaining(key -> { if (!gateKeys.contains(key)) throw new IllegalArgumentException("acceptance gate unknown field: " + key); });
        Set<String> optional = Set.of("maximum_drawdown_r", "maximum_cost_r");
        for (String key : gateKeys) {
            JsonNode value = contract.get("gates").get(key);
            if (!optional.contains(key) && !Double.isFinite(jsNumber(value))) throw new IllegalArgumentException("acceptance gate " + key + " is required");
            if (optional.contains(key) && value != null && (!Double.isFinite(jsNumber(value)) || jsNumber(value) < 0)) throw new IllegalArgumentException("acceptance gate " + key + " must be a non-negative number when declared");
        }
        List<JsonNode> stressRows = rows(contract.get("stress_scenarios"));
        List<String> names = stressRows.stream().map(row -> text(row.get("name"))).toList();
        if (names.size() != REQUIRED_STRESS_SCENARIOS.size() || new LinkedHashSet<>(names).size() != names.size() || !names.containsAll(REQUIRED_STRESS_SCENARIOS)
                || stressRows.stream().anyMatch(row -> !row.isObject() || !row.path("required").asBoolean(false) || !row.path("parameters").isObject())) {
            throw new IllegalArgumentException("acceptance contract must declare exactly five fully parameterized stress scenarios");
        }
        Map<String, Set<String>> allowed = Map.of(
                "DOUBLED_FEES_SLIPPAGE", Set.of("multiplier", "minimum_observations", "minimum_expectancy_r"),
                "DOUBLED_FUNDING", Set.of("multiplier", "minimum_observations", "minimum_expectancy_r"),
                "ADVERSE_GAP", Set.of("debit_r", "gap_model", "minimum_observations", "minimum_expectancy_r"),
                "LIQUIDITY_CAPACITY", Set.of("capacity_model", "maximum_participation_rate", "minimum_observations", "minimum_expectancy_r"),
                "VENUE_OUTAGE", Set.of("outage_rule", "blackout_windows", "minimum_observations", "minimum_expectancy_r"));
        for (JsonNode row : stressRows) {
            String name = text(row.get("name")); JsonNode parameters = row.get("parameters");
            parameters.fieldNames().forEachRemaining(key -> { if (!allowed.get(name).contains(key)) throw new IllegalArgumentException(name + " stress parameters contain unknown fields"); });
            DEFAULT_STRESS_PARAMETERS.get(name).fieldNames().forEachRemaining(key -> { if (!parameters.has(key)) throw new IllegalArgumentException(name + " stress parameter " + key + " is required"); });
            double minimum = jsNumber(parameters.get("minimum_expectancy_r")); double observations = jsNumber(parameters.get("minimum_observations"));
            if (!Double.isFinite(minimum) || observations != Math.rint(observations) || observations < 1) throw new IllegalArgumentException(name + " stress observation/expectancy thresholds are invalid");
            if ((name.equals("DOUBLED_FEES_SLIPPAGE") || name.equals("DOUBLED_FUNDING")) && !(jsNumber(parameters.get("multiplier")) >= 1 && Double.isFinite(jsNumber(parameters.get("multiplier"))))) throw new IllegalArgumentException(name + " multiplier is invalid");
            if (name.equals("ADVERSE_GAP") && (!(jsNumber(parameters.get("debit_r")) >= 0 && Double.isFinite(jsNumber(parameters.get("debit_r")))) || !"declared_gap_or_observed_mae".equals(text(parameters.get("gap_model"))))) throw new IllegalArgumentException("ADVERSE_GAP parameters are invalid");
            if (name.equals("LIQUIDITY_CAPACITY") && (!"venue_available_liquidity_notional".equals(text(parameters.get("capacity_model"))) || !(jsNumber(parameters.get("maximum_participation_rate")) > 0 && jsNumber(parameters.get("maximum_participation_rate")) <= 1))) throw new IllegalArgumentException("LIQUIDITY_CAPACITY parameters are invalid");
            if (name.equals("VENUE_OUTAGE")) {
                if (!"declared_blackout_windows".equals(text(parameters.get("outage_rule"))) || !parameters.path("blackout_windows").isArray() || parameters.path("blackout_windows").isEmpty()) throw new IllegalArgumentException("VENUE_OUTAGE requires declared blackout windows");
                for (JsonNode window : parameters.path("blackout_windows")) {
                    Set<String> keys = new LinkedHashSet<>(); window.fieldNames().forEachRemaining(keys::add);
                    if (!window.isObject() || !Set.of("venue", "start_time", "end_time").containsAll(keys) || text(window.get("venue")).isEmpty()) throw new IllegalArgumentException("VENUE_OUTAGE blackout window is invalid");
                    long start = timestamp(window.get("start_time"), "VENUE_OUTAGE start_time"); long end = timestamp(window.get("end_time"), "VENUE_OUTAGE end_time");
                    if (!(start < end)) throw new IllegalArgumentException("VENUE_OUTAGE blackout window must have start_time < end_time");
                }
            }
        }
        return true;
    }

    public static ObjectNode frozenSelectionByAsset(JsonNode experiment) {
        if (!"EXPOSED_CONFIRMATION".equals(text(experiment == null ? null : experiment.get("evidence_phase")))) return null;
        List<String> assets = rows(experiment.get("required_assets")).stream().map(item -> lower(item.isTextual() ? item : item.get("asset"))).toList();
        JsonNode chronology = experiment.path("chronology");
        if (!chronology.path("frozen_selection").asBoolean(false) || !isSha(text(experiment.get("parent_evidence_sha256")))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION requires frozen selection and parent WFO evidence lineage");
        ObjectNode out = JSON.objectNode(); JsonNode explicit = chronology.get("frozen_candidate_by_asset");
        if (explicit != null) {
            if (!explicit.isObject()) throw new IllegalArgumentException("EXPOSED_CONFIRMATION frozen_candidate_by_asset must be an object");
            List<String> keys = new ArrayList<>(); explicit.fieldNames().forEachRemaining(key -> keys.add(key.toLowerCase(Locale.ROOT))); Collections.sort(keys);
            List<String> expected = new ArrayList<>(assets); Collections.sort(expected);
            if (!keys.equals(expected)) throw new IllegalArgumentException("EXPOSED_CONFIRMATION must freeze exactly one candidate per required asset");
            for (String asset : assets) {
                JsonNode candidate = explicit.get(asset);
                if (candidate == null || !candidate.isTextual() || candidate.textValue().isEmpty()) throw new IllegalArgumentException("EXPOSED_CONFIRMATION frozen candidate ids must be non-empty strings");
                out.put(asset, candidate.textValue());
            }
            return out;
        }
        JsonNode ids = chronology.get("frozen_candidate_ids");
        if (ids == null || !ids.isArray() || ids.size() != assets.size()) throw new IllegalArgumentException("EXPOSED_CONFIRMATION must freeze exactly one candidate id per required asset");
        for (int i = 0; i < assets.size(); i++) {
            JsonNode id = ids.get(i); if (!id.isTextual() || id.textValue().isEmpty()) throw new IllegalArgumentException("EXPOSED_CONFIRMATION must freeze exactly one candidate id per required asset");
            out.put(assets.get(i), id.textValue());
        }
        return out;
    }

    public static boolean validateExperimentV3(JsonNode experiment) { return validateExperimentV3(experiment, null, null); }

    public static boolean validateExperimentV3(JsonNode experiment, JsonNode acceptance, JsonNode requiredAssets) {
        if (experiment == null || !EXPERIMENT_V3_SCHEMA.equals(text(experiment.get("schema")))) throw new IllegalArgumentException("strategy-experiment/3 is required");
        rejectUnknown(experiment, Set.of("schema", "experiment_id", "created_at", "stage", "predecessor_stage", "predecessor_sha256", "parent_evidence_sha256", "evidence_phase", "precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256", "feature_set_sha256", "label_set_sha256", "executor_sha256", "acceptance_contract_sha256", "acceptance_contract", "candidate_accounting", "required_assets", "chronology", "portfolio_policy", "training_selection_policy", "training_selection_policy_sha256", "content_sha256"), "strategy-experiment/3 unknown field: ");
        String phase = text(experiment.get("evidence_phase"));
        if (!bool(experiment.get("experiment_id")) || !bool(experiment.get("created_at")) || !V3_PHASES.contains(phase)) throw new IllegalArgumentException("experiment v3 identity/evidence_phase is invalid");
        if ("SEALED_CONFIRMATION".equals(phase)) throw new IllegalArgumentException("SEALED_CONFIRMATION is an external read-only label; local v3 constructors cannot mint or validate it");
        for (String key : List.of("precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256", "acceptance_contract_sha256")) if (!isSha(text(experiment.get(key)))) throw new IllegalArgumentException("experiment." + key + " is required");
        JsonNode chronology = experiment.get("chronology");
        if (chronology == null || !bool(chronology.get("timezone")) || !bool(chronology.get("bar_convention")) || !chronology.path("seeds").isArray() || chronology.path("seeds").isEmpty()) throw new IllegalArgumentException("experiment chronology must freeze timezone, bar convention and seeds");
        String stage = text(experiment.get("stage")); if (!STAGE_CHAIN.contains(stage)) throw new IllegalArgumentException("experiment stage must be one of " + String.join(", ", STAGE_CHAIN));
        int stageIndex = STAGE_CHAIN.indexOf(stage);
        if (stageIndex == 0 && (bool(experiment.get("predecessor_sha256")) || bool(experiment.get("parent_evidence_sha256")) || bool(experiment.get("predecessor_stage")))) throw new IllegalArgumentException("CORE_PREMISE cannot have a predecessor");
        if (stageIndex > 0) {
            String predecessor = bool(experiment.get("predecessor_sha256")) ? text(experiment.get("predecessor_sha256")) : text(experiment.get("parent_evidence_sha256"));
            if (!isSha(predecessor)) throw new IllegalArgumentException("non-core stage requires a SHA-256 predecessor evidence hash");
            if (!STAGE_CHAIN.get(stageIndex - 1).equals(text(experiment.get("predecessor_stage")))) throw new IllegalArgumentException(stage + " must directly follow " + STAGE_CHAIN.get(stageIndex - 1));
        }
        JsonNode assetsNode = experiment.has("required_assets") ? experiment.get("required_assets") : requiredAssets;
        List<JsonNode> assets = rows(assetsNode);
        if (assets.isEmpty() || assets.stream().anyMatch(item -> !cryptoAsset(text(item.isTextual() ? item : item.get("asset"))) || (!item.isTextual() && !"crypto".equals(lower(item.has("asset_class") ? item.get("asset_class") : JSON.textNode("crypto")))))) throw new IllegalArgumentException("experiment required_assets must be crypto-only tradable instruments");
        if (acceptance != null) validateAcceptanceContract(acceptance);
        if ("WALK_FORWARD_OOS".equals(phase) && (!chronology.path("folds").isArray() || chronology.path("folds").isEmpty())) throw new IllegalArgumentException("WALK_FORWARD_OOS requires chronological folds");
        if (LATE_PHASES.contains(phase)) {
            validateTrainingSelectionPolicy(experiment.get("training_selection_policy"));
            if (!text(experiment.get("training_selection_policy_sha256")).equals(text(experiment.path("training_selection_policy").get("content_sha256")))) throw new IllegalArgumentException("experiment training selection policy lineage mismatch");
        }
        if (Set.of("EXPOSED_CONFIRMATION", "CI_ATTESTED_CONFIRMATION", "SEALED_CONFIRMATION", "PROSPECTIVE_LIVE").contains(phase) && !chronology.path("frozen_selection").asBoolean(false)) throw new IllegalArgumentException(phase + " requires frozen selection");
        if ("EXPOSED_CONFIRMATION".equals(phase)) frozenSelectionByAsset(experiment);
        if (LATE_PHASES.contains(phase) && (!isSha(text(experiment.get("feature_set_sha256"))) || !isSha(text(experiment.get("label_set_sha256"))))) throw new IllegalArgumentException(phase + " requires feature_set_sha256 and label_set_sha256");
        if (!text(experiment.get("content_sha256")).equals(ownHash(experiment))) throw new IllegalArgumentException("experiment v3 content hash mismatch");
        return true;
    }

    public static ObjectNode makeExperimentV3(JsonNode options) { return makeExperimentV3(options, Clock.systemUTC()); }

    public static ObjectNode makeExperimentV3(JsonNode options, Clock clock) {
        JsonNode input = options == null ? JSON.objectNode() : options;
        ObjectNode acceptance = input.has("acceptanceContract") ? objectCopy(input.get("acceptanceContract"), "acceptanceContract") : makeAcceptanceContract();
        ObjectNode policy = input.has("trainingSelectionPolicy") ? objectCopy(input.get("trainingSelectionPolicy"), "trainingSelectionPolicy") : makeTrainingSelectionPolicy();
        validateAcceptanceContract(acceptance); validateTrainingSelectionPolicy(policy);
        ObjectNode value = JSON.objectNode().put("schema", EXPERIMENT_V3_SCHEMA)
                .set("experiment_id", cloneNode(input.get("experimentId")));
        value.put("created_at", optionText(input, "createdAt", jsIsoInstant(clock)));
        value.put("stage", optionText(input, "stage", "CORE_PREMISE"));
        putNullable(value, "predecessor_stage", input.get("predecessorStage")); putNullable(value, "predecessor_sha256", input.get("predecessorSha256")); putNullable(value, "parent_evidence_sha256", input.get("parentEvidenceSha256"));
        value.put("evidence_phase", optionText(input, "evidencePhase", "DEVELOPMENT"));
        for (String[] mapping : List.of(new String[]{"precommit_sha256", "precommitSha256"}, new String[]{"definition_sha256", "definitionSha256"}, new String[]{"candidate_set_sha256", "candidateSetSha256"}, new String[]{"data_manifest_sha256", "dataManifestSha256"})) putNullable(value, mapping[0], input.get(mapping[1]));
        putNullable(value, "feature_set_sha256", input.get("featureSetSha256")); putNullable(value, "label_set_sha256", input.get("labelSetSha256"));
        value.put("executor_sha256", optionText(input, "executorSha256", hash("swing-engine/1")));
        value.put("acceptance_contract_sha256", acceptance.path("content_sha256").asText());
        JsonNode required = input.has("requiredAssets") ? input.get("requiredAssets") : strings(CORE_UNIVERSE); ArrayNode normalized = JSON.arrayNode();
        for (JsonNode item : rows(required)) normalized.add(item.isTextual() ? JSON.objectNode().put("asset", lower(item)).put("asset_class", "crypto").put("instrument", "spot") : cloneNode(item));
        value.set("required_assets", normalized);
        value.set("chronology", input.has("chronology") ? cloneNode(input.get("chronology")) : JSON.objectNode().put("timezone", "UTC").put("bar_convention", "completed-bar-next-open").set("seeds", JSON.arrayNode().add(1)));
        value.set("portfolio_policy", input.has("portfolioPolicy") ? cloneNode(input.get("portfolioPolicy")) : JSON.objectNode());
        value.set("training_selection_policy", policy); value.put("training_selection_policy_sha256", policy.path("content_sha256").asText()); value.set("acceptance_contract", acceptance);
        ObjectNode result = withHash(value); validateExperimentV3(result, acceptance, null); return result;
    }

    public static ObjectNode blockBootstrap(JsonNode values) { return blockBootstrap(values, JSON.objectNode()); }

    public static ObjectNode blockBootstrap(JsonNode values, JsonNode options) {
        List<Double> source = new ArrayList<>(); for (JsonNode item : rows(values)) { double number = jsNumber(item); if (Double.isFinite(number)) source.add(number); }
        int seed = (int) optionNumber(options, "seed", 1); int requested = (int) optionNumber(options, "iterations", 2000);
        if (source.isEmpty()) return JSON.objectNode().putNull("p20").put("seed", seed).put("iterations", 0).put("block_length", 0).set("samples", JSON.arrayNode());
        int requestedLength = options != null && options.hasNonNull("blockLength") ? (int) jsNumber(options.get("blockLength")) : 0;
        int length = Math.max(1, Math.min(source.size(), requestedLength != 0 ? requestedLength : (int) Math.ceil(Math.sqrt(source.size()))));
        SeededRng random = new SeededRng(seed); List<Double> samples = new ArrayList<>();
        for (int iteration = 0; iteration < Math.max(1, requested); iteration++) {
            List<Double> draw = new ArrayList<>();
            while (draw.size() < source.size()) { int start = (int) Math.floor(random.next() * source.size()); for (int offset = 0; offset < length && draw.size() < source.size(); offset++) draw.add(source.get((start + offset) % source.size())); }
            samples.add(jsMean(draw));
        }
        ObjectNode out = JSON.objectNode().put("p20", quantile(samples, .2)).put("seed", seed).put("iterations", samples.size()).put("block_length", length); ArrayNode sampleRows = out.putArray("samples"); samples.forEach(sampleRows::add); return out;
    }

    public static ObjectNode centredCandidateSetMaxStatistic(JsonNode candidateEpisodes) { return centredCandidateSetMaxStatistic(candidateEpisodes, JSON.objectNode()); }

    public static ObjectNode centredCandidateSetMaxStatistic(JsonNode candidateEpisodes, JsonNode options) {
        List<String> names = new ArrayList<>(); if (candidateEpisodes != null && candidateEpisodes.isObject()) candidateEpisodes.fieldNames().forEachRemaining(names::add); Collections.sort(names);
        List<LinkedHashMap<String, Double>> maps = names.stream().map(name -> alignedEpisodeSeries(candidateEpisodes.get(name))).toList();
        int seed = (int) optionNumber(options, "seed", 1); int requested = (int) optionNumber(options, "iterations", 2000);
        if (maps.isEmpty() || maps.stream().anyMatch(Map::isEmpty)) return maxFailure("INCOMPLETE_CANDIDATE_ACCOUNTING", seed, names.size(), 0, null);
        List<String> episodeIds = new ArrayList<>(maps.get(0).keySet()); episodeIds.removeIf(id -> maps.stream().anyMatch(series -> !series.containsKey(id))); Collections.sort(episodeIds);
        boolean explicit = names.stream().anyMatch(name -> hasExplicitEpisodeIds(candidateEpisodes.get(name)));
        if (episodeIds.isEmpty() && explicit) return maxFailure("NO_SHARED_EPISODE_ACCOUNTING", seed, names.size(), 0, null);
        Set<String> union = new LinkedHashSet<>(); maps.forEach(map -> union.addAll(map.keySet()));
        if ((explicit || !episodeIds.isEmpty()) && episodeIds.size() != union.size()) return maxFailure("INCOMPLETE_SHARED_EPISODE_ACCOUNTING", seed, names.size(), episodeIds.size(), episodeIds);
        boolean explicitAlignment = explicit || !episodeIds.isEmpty();
        if (!explicitAlignment) { int count = maps.stream().mapToInt(Map::size).min().orElse(0); episodeIds = new ArrayList<>(); for (int i = 0; i < count; i++) episodeIds.add(String.valueOf(i)); }
        int count = episodeIds.size(); List<List<Double>> aligned = new ArrayList<>();
        for (Map<String, Double> map : maps) { List<Double> values = explicitAlignment ? episodeIds.stream().map(map::get).toList() : new ArrayList<>(map.values()).subList(0, count); aligned.add(new ArrayList<>(values)); }
        List<Double> observed = aligned.stream().map(LegacyResearchSupport::jsMean).toList(); List<List<Double>> centred = new ArrayList<>();
        for (int i = 0; i < aligned.size(); i++) { List<Double> values = new ArrayList<>(); for (double value : aligned.get(i)) values.add(value - observed.get(i)); centred.add(values); }
        int requestedLength = options != null && options.hasNonNull("blockLength") ? (int) jsNumber(options.get("blockLength")) : 0; int length = Math.max(1, Math.min(count, requestedLength != 0 ? requestedLength : (int) Math.ceil(Math.sqrt(count))));
        SeededRng random = new SeededRng(seed); List<Double> maxima = new ArrayList<>();
        for (int iteration = 0; iteration < Math.max(1, requested); iteration++) {
            List<List<Double>> draws = new ArrayList<>(); for (int i = 0; i < names.size(); i++) draws.add(new ArrayList<>());
            while (draws.get(0).size() < count) { int start = (int) Math.floor(random.next() * count); for (int offset = 0; offset < length && draws.get(0).size() < count; offset++) for (int i = 0; i < names.size(); i++) draws.get(i).add(centred.get(i).get((start + offset) % count)); }
            maxima.add(draws.stream().mapToDouble(values -> Math.abs(jsMean(values))).max().orElse(0));
        }
        double observedMax = observed.stream().mapToDouble(Math::abs).max().orElse(0); long atLeast = maxima.stream().filter(value -> value >= observedMax).count();
        ObjectNode out = JSON.objectNode().put("p_value", (atLeast + 1d) / (maxima.size() + 1d)).put("observed_max_statistic", observedMax).put("seed", seed).put("iterations", maxima.size()).put("block_length", length).put("K", names.size()).put("episode_count", count);
        out.set("episode_ids", strings(episodeIds)); out.put("method", "centred_shared_event_time_block_max_statistic"); return out;
    }

    private static ObjectNode maxFailure(String failure, int seed, int candidates, int episodes, List<String> ids) {
        ObjectNode out = JSON.objectNode().putNull("p_value").put("seed", seed).put("iterations", 0).put("K", candidates).put("episode_count", episodes);
        if (ids != null) out.set("episode_ids", strings(ids)); out.put("method", "centred_shared_event_time_block_max_statistic").put("failure", failure); return out;
    }

    private static LinkedHashMap<String, Double> alignedEpisodeSeries(JsonNode value) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>(); int index = 0;
        if (value != null && value.isArray()) for (JsonNode item : value) {
            String id = item.isObject() ? text(first(item, "episode_id", "event_id", "market_episode_id")) : String.valueOf(index);
            if (id.isEmpty()) id = String.valueOf(index); JsonNode raw = item.isObject() ? first(item, "value", "net_r", "r", "return_r") : item; double number = jsNumber(raw);
            if (Double.isFinite(number)) result.merge(id, number, (left, right) -> (left + right) / 2); index++;
        } else if (value != null && value.isObject()) value.fields().forEachRemaining(field -> { JsonNode item = field.getValue(); JsonNode raw = item.isObject() ? first(item, "value", "net_r", "r", "return_r") : item; double number = jsNumber(raw); if (Double.isFinite(number)) result.merge(field.getKey(), number, (left, right) -> (left + right) / 2); });
        return result;
    }

    private static boolean hasExplicitEpisodeIds(JsonNode value) {
        if (value != null && value.isObject()) return true;
        if (value != null && value.isArray()) for (JsonNode item : value) if (item.isObject() && (item.has("episode_id") || item.has("event_id") || item.has("market_episode_id"))) return true;
        return false;
    }

    public static ObjectNode computeCandidateMetrics(JsonNode trades) { return computeCandidateMetrics(trades, JSON.objectNode()); }

    public static ObjectNode computeCandidateMetrics(JsonNode tradesNode, JsonNode options) {
        ArrayNode trades = array(tradesNode); JsonNode allTradesNode = options != null && options.has("allTrades") ? options.get("allTrades") : trades;
        List<String> candidateIds = options != null && options.path("candidateIds").isArray()
                ? rows(options.get("candidateIds")).stream().map(LegacyResearchSupport::text).distinct().toList() : null;
        int candidateCount = (int) optionNumber(options, "candidateCount", candidateIds == null ? 1 : candidateIds.size());
        String candidateId = optionText(options, "candidateId", "candidate");
        Set<String> observed = new LinkedHashSet<>();
        for (JsonNode trade : rows(allTradesNode)) {
            String id = optionText(trade, "candidate_id", candidateId);
            if (id.isEmpty()) id = "candidate";
            observed.add(id);
        }
        observed.add(candidateId);
        int effective = candidateIds == null ? observed.size() : candidateIds.size();
        if (candidateIds != null && candidateCount != effective) throw new IllegalArgumentException("candidate accounting declared/effective K mismatch");
        if (candidateIds == null && candidateCount != effective) throw new IllegalArgumentException("candidate accounting mismatch: declared K=" + candidateCount + ", effective K=" + effective);
        if (candidateIds != null) {
            List<String> missing = candidateIds.stream().filter(id -> !observed.contains(id)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("candidate accounting missing episode outcomes for: " + String.join(", ", missing));
        }

        List<JsonNode> complete = rows(trades).stream().filter(row -> row.has("exit_time") || row.has("close_time")).toList();
        List<Double> values = returnsFromTrades(complete); List<Double> episodes = groupMeans(complete);
        int wins = (int) values.stream().filter(value -> value > 0).count(); int losses = (int) values.stream().filter(value -> value < 0).count();
        Double mean = values.isEmpty() ? null : jsMean(values);
        int seed = (int) optionNumber(options, "seed", 1); int iterations = (int) optionNumber(options, "bootstrapIterations", 2000);
        ArrayNode episodeArray = JSON.arrayNode(); episodes.forEach(episodeArray::add); ObjectNode bootstrap = blockBootstrap(episodeArray, JSON.objectNode().put("seed", seed).put("iterations", iterations));
        Double p = profitFactor(complete, "net_r"); Double accountPf = profitFactor(complete, "net_pnl");
        List<JsonNode> doubled = new ArrayList<>();
        for (JsonNode trade : complete) {
            ObjectNode copy = objectCopy(trade, "trade");
            double debit = Math.abs(finiteOrZero(trade.get("fee_r"))) + Math.abs(finiteOrZero(trade.get("slippage_r"))) + Math.max(0, finiteOrZero(trade.get("funding_debit_r")));
            double risk = Math.abs(firstFiniteOrZero(trade, "risk_dollars", "risk_amount"));
            copy.put("net_r", firstFiniteOrZero(trade, "net_r", "r", "return_r") - debit);
            copy.put("net_pnl", finiteOrZero(trade.get("net_pnl")) - debit * risk); doubled.add(copy);
        }
        Map<Integer, List<JsonNode>> years = new LinkedHashMap<>(); List<JsonNode> ordered = new ArrayList<>(complete);
        ordered.sort(Comparator.comparingLong(row -> timestamp(first(row, "exit_time", "close_time", "time"), "trade time")));
        for (JsonNode trade : complete) {
            int year = Instant.ofEpochMilli(timestamp(first(trade, "exit_time", "close_time", "time"), "trade time")).atZone(ZoneOffset.UTC).getYear();
            years.computeIfAbsent(year, ignored -> new ArrayList<>()).add(trade);
        }
        List<List<JsonNode>> blocks = new ArrayList<>(); int blockSize = Math.max(1, (int) Math.ceil(ordered.size() / 3d));
        for (int index = 0; index < ordered.size(); index += blockSize) blocks.add(new ArrayList<>(ordered.subList(index, Math.min(ordered.size(), index + blockSize))));
        List<Double> holding = new ArrayList<>(), mae = new ArrayList<>(), mfe = new ArrayList<>();
        for (JsonNode row : complete) {
            if (bool(row.get("entry_time")) && (bool(row.get("exit_time")) || bool(row.get("close_time")))) holding.add((double) (timestamp(first(row, "exit_time", "close_time"), "trade exit_time") - timestamp(row.get("entry_time"), "trade entry_time")));
            addFinite(mae, first(row, "mae_r", "mae_pct")); addFinite(mfe, first(row, "mfe_r", "mfe_pct"));
        }
        List<Integer> lossRuns = new ArrayList<>(); int run = 0; for (double value : values) { if (value < 0) run++; else if (run > 0) { lossRuns.add(run); run = 0; } } if (run > 0) lossRuns.add(run);
        double initialEquity = optionNumber(options, "initialEquity", 100000); AccountPath account = accountPath(complete, initialEquity);
        JsonNode coverage = options == null ? null : options.get("coverage"); Double featureCoverage = firstFinite(coverage, "price_fraction", "feature_fraction"); Double derivativeCoverage = firstFinite(coverage, "derivatives_fraction", "funding_fraction");
        double observedCoverage = featureCoverage != null ? featureCoverage : (complete.isEmpty() ? 0 : complete.stream().filter(row -> !row.has("coverage_ok") || row.path("coverage_ok").asBoolean()).count() / (double) complete.size());
        boolean unboundedR = p == null && wins > 0; boolean unboundedAccount = accountPf == null && complete.stream().anyMatch(row -> finiteOrZero(row.get("net_pnl")) > 0);
        ObjectNode candidateEpisodes = candidateEpisodeSeries(allTradesNode, candidateId); if (!candidateEpisodes.has(candidateId)) { ArrayNode fallback = candidateEpisodes.putArray(candidateId); for (int i = 0; i < episodes.size(); i++) fallback.add(JSON.objectNode().put("episode_id", "episode-" + i).put("value", episodes.get(i))); }

        ObjectNode metrics = JSON.objectNode().put("schema", "strategy-candidate-metrics/1"); putNullable(metrics, "candidate_id", options != null && options.has("candidateId") ? options.get("candidateId") : NullNode.instance);
        JsonNode assetNode = options != null && options.hasNonNull("asset") ? options.get("asset") : complete.isEmpty() ? NullNode.instance : complete.get(0).get("asset"); putNullable(metrics, "asset", assetNode);
        metrics.put("selected", false).put("attempted_entries", trades.size()).put("opened_trades", complete.size()).put("completed_trades", complete.size()).put("wins", wins).put("losses", losses).put("breakeven", values.size() - wins - losses);
        putDoubleOrNull(metrics, "win_rate", values.isEmpty() ? null : wins / (double) values.size()); metrics.set("win_rate_wilson_95", wilson(wins, values.size()));
        putDoubleOrNull(metrics, "expectancy_r", mean); putDoubleOrNull(metrics, "search_adjusted_expectancy_r", mean == null ? null : mean - Math.sqrt(2 * Math.log(Math.max(1, candidateCount)) / Math.max(1, episodes.size())));
        putDoubleOrNull(metrics, "profit_factor_r", p); putDoubleOrNull(metrics, "profit_factor_r_value", p); metrics.put("profit_factor_r_unbounded", unboundedR); putDoubleOrNull(metrics, "r_profit_factor", p);
        putDoubleOrNull(metrics, "profit_factor_account", accountPf); putDoubleOrNull(metrics, "account_profit_factor_value", accountPf); metrics.put("account_profit_factor_unbounded", unboundedAccount); putDoubleOrNull(metrics, "account_currency_profit_factor", accountPf); putDoubleOrNull(metrics, "profit_factor", accountPf);
        putDoubleOrNull(metrics, "total_return", account.totalReturn); putDoubleOrNull(metrics, "annualized_return", account.annualized); putLongOrNull(metrics, "annualized_return_window_ms", account.windowMs);
        metrics.put("max_drawdown_pct", account.maxDrawdown * 100).put("drawdown_duration_bars", account.maxDrawdownBars).put("time_underwater_ms", account.underwaterMs).set("equity_curve", account.series);

        ObjectNode robust = JSON.objectNode(); putDoubleOrNull(robust, "bootstrap_p20_expectancy_r", bootstrap.hasNonNull("p20") ? bootstrap.get("p20").doubleValue() : null); putDoubleOrNull(robust, "bootstrap_p20", bootstrap.hasNonNull("p20") ? bootstrap.get("p20").doubleValue() : null);
        Set<String> episodeIds = new LinkedHashSet<>(); complete.forEach(row -> episodeIds.add(episodeId(row))); List<String> sortedEpisodeIds = new ArrayList<>(episodeIds); Collections.sort(sortedEpisodeIds);
        robust.set("bootstrap", JSON.objectNode().put("method", "seeded_event_time_block").put("seed", bootstrap.path("seed").asInt()).put("iterations", bootstrap.path("iterations").asInt()).put("block_length", bootstrap.path("block_length").asInt()).set("episode_ids", strings(sortedEpisodeIds))); ((ObjectNode) robust.get("bootstrap")).put("K", episodes.size());
        robust.put("effective_independent_episode_count", episodes.size()).put("independent_episode_count", episodes.size()).put("candidate_set_max_statistic_p_value", 1);
        robust.set("candidate_set_max_statistic", JSON.objectNode().put("method", "centred_candidate_set_max_statistic").put("p_value", 1).put("K", candidateCount).put("seed", seed).put("iterations", bootstrap.path("iterations").asInt()));
        ObjectNode tails = moments(values); putDoubleOrNull(tails, "p05", percentileOrNull(values, .05)); putDoubleOrNull(tails, "p95", percentileOrNull(values, .95));
        Double p05 = percentileOrNull(values, .05); Double shortfall = null; if (!values.isEmpty()) { List<Double> tail = values.stream().filter(value -> value <= (p05 == null ? Double.NEGATIVE_INFINITY : p05)).toList(); shortfall = tail.stream().mapToDouble(Double::doubleValue).sum() / Math.max(1, tail.size()); } putDoubleOrNull(tails, "expected_shortfall_05", shortfall); robust.set("tails", tails);
        ArrayNode runRows = JSON.arrayNode(); lossRuns.forEach(runRows::add); robust.set("loss_runs", JSON.objectNode().put("maximum", lossRuns.stream().mapToInt(Integer::intValue).max().orElse(0)).set("distribution", runRows));
        ObjectNode maeMfe = JSON.objectNode(); putDoubleOrNull(maeMfe, "mae", averageOrNull(mae)); putDoubleOrNull(maeMfe, "mfe", averageOrNull(mfe)); robust.set("mae_mfe", maeMfe);
        ObjectNode holdingTime = JSON.objectNode(); putDoubleOrNull(holdingTime, "median", percentileOrNull(holding, .5)); putDoubleOrNull(holdingTime, "p95", percentileOrNull(holding, .95)); robust.set("holding_time_ms", holdingTime);
        ObjectNode doubledCost = JSON.objectNode(); putDoubleOrNull(doubledCost, "expectancy_r", doubled.isEmpty() ? null : jsMean(returnsFromTrades(doubled))); putDoubleOrNull(doubledCost, "profit_factor_r", profitFactor(doubled, "net_r")); putDoubleOrNull(doubledCost, "profit_factor_account", profitFactor(doubled, "net_pnl")); robust.set("doubled_cost", doubledCost);
        ObjectNode yearRows = robust.putObject("years"); years.forEach((year, group) -> { List<Double> returns = returnsFromTrades(group); ObjectNode row = JSON.objectNode().put("episodes", groupMeans(group).size()); row.put("expectancy_r", returns.isEmpty() ? 0 : jsMean(returns)); yearRows.set(String.valueOf(year), row); });
        ArrayNode blockRows = robust.putArray("chronological_blocks"); blocks.forEach(group -> { List<Double> returns = returnsFromTrades(group); blockRows.add(JSON.objectNode().put("episodes", groupMeans(group).size()).put("expectancy_r", returns.isEmpty() ? 0 : jsMean(returns))); });
        robust.put("coverage_fraction", observedCoverage); putDoubleOrNull(robust, "price_coverage_fraction", featureCoverage); putDoubleOrNull(robust, "derivatives_coverage_fraction", derivativeCoverage);
        robust.put("undeclared_gap_bars", complete.stream().mapToDouble(row -> Math.max(0, finiteOrZero(row.get("undeclared_gap_bars")))).max().orElse(0)); robust.put("funding_processed", options != null && options.path("fundingProcessed").asBoolean(false)); robust.put("turnover", complete.stream().mapToDouble(row -> Math.abs(finiteOrZero(row.get("notional"))) * 2).sum()).put("all_candidate_count", candidateCount);
        metrics.set("robust_stats", robust);

        // v3 wrapper additions, including the source's intentionally sparse top-level doubled_cost.
        metrics.set("mae_mfe", maeMfe.deepCopy()); metrics.put("accounting_basis", "chronological_net_pnl_or_explicit_return_fraction");
        metrics.set("doubled_cost", JSON.objectNode().put("profit_factor_account_unbounded", false));
        ObjectNode maxStatistic = centredCandidateSetMaxStatistic(candidateEpisodes, JSON.objectNode().put("seed", seed).put("iterations", iterations)); robust.set("candidate_set_max_statistic_p_value", cloneNode(maxStatistic.get("p_value"))); robust.set("candidate_set_max_statistic", maxStatistic);
        metrics.set("tails", tails); robust.remove("tails");
        return metrics;
    }

    private static List<Double> returnsFromTrades(List<? extends JsonNode> trades) {
        List<Double> values = new ArrayList<>(); trades.forEach(row -> addFinite(values, first(row, "net_r", "r", "return_r"))); return values;
    }

    private static String episodeId(JsonNode trade) {
        JsonNode explicit = first(trade, "episode_id", "event_id", "market_episode_id"); if (explicit != null) return text(explicit);
        return text(trade.get("asset")) + "|" + text(first(trade, "entry_time", "signal_time", "time"));
    }

    private static List<Double> groupMeans(List<? extends JsonNode> trades) {
        List<JsonNode> ordered = new ArrayList<>(trades); ordered.sort(Comparator.comparingLong(row -> timestamp(first(row, "exit_time", "close_time", "entry_time", "signal_time", "time"), "episode time")));
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>(); ordered.forEach(trade -> groups.computeIfAbsent(episodeId(trade), ignored -> new ArrayList<>()).add(trade));
        List<Double> result = new ArrayList<>(); for (List<JsonNode> group : groups.values()) { List<Double> values = new ArrayList<>(); for (JsonNode row : group) values.add(firstFiniteOrZero(row, "net_r", "r", "return_r")); result.add(jsMean(values)); } return result;
    }

    private static ObjectNode candidateEpisodeSeries(JsonNode trades, String fallback) {
        Map<String, Map<String, List<Double>>> grouped = new LinkedHashMap<>();
        for (JsonNode trade : rows(trades)) { String candidate = optionText(trade, "candidate_id", fallback); Double value = firstFinite(trade, "net_r", "r", "return_r"); if (value == null) continue; grouped.computeIfAbsent(candidate, ignored -> new LinkedHashMap<>()).computeIfAbsent(episodeId(trade), ignored -> new ArrayList<>()).add(value); }
        ObjectNode out = JSON.objectNode(); grouped.forEach((candidate, episodes) -> { ArrayNode values = out.putArray(candidate); episodes.forEach((episode, items) -> values.add(JSON.objectNode().put("episode_id", episode).put("value", jsMean(items)))); }); return out;
    }

    private static Double profitFactor(List<? extends JsonNode> trades, String field) {
        double wins = 0, losses = 0; for (JsonNode row : trades) { double value = finiteOrZero(row.get(field)); wins += Math.max(0, value); losses += Math.min(0, value); }
        losses = Math.abs(losses);
        if (losses != 0) return wins / losses;
        if (wins > 0) return null;
        return 0d;
    }

    private static AccountPath accountPath(List<? extends JsonNode> trades, double initialEquity) {
        List<JsonNode> ordered = new ArrayList<>(trades); ordered.sort(Comparator.comparingLong(row -> timestamp(first(row, "exit_time", "close_time", "time"), "trade time")));
        double equity = initialEquity, peak = equity, maxDrawdown = 0; int drawdownBars = 0, maxDrawdownBars = 0; long underwater = 0; Long underwaterStart = null, previous = null;
        ArrayNode series = JSON.arrayNode(); ObjectNode first = JSON.objectNode(); if (ordered.isEmpty()) first.putNull("time"); else first.put("time", timestamp(first(ordered.get(0), "entry_time", "exit_time", "close_time", "time"), "trade time")); first.put("equity", equity); series.add(first);
        for (JsonNode trade : ordered) { long time = timestamp(first(trade, "exit_time", "close_time", "time"), "trade time"); Double pnlValue = finiteValue(trade.get("net_pnl")); Double returnValue = firstFinite(trade, "equity_return_fraction", "return_fraction"); double pnl = pnlValue != null ? pnlValue : returnValue != null ? equity * returnValue : 0; equity += pnl; if (equity > peak) { peak = equity; drawdownBars = 0; underwaterStart = null; } else if (peak > 0) { drawdownBars++; maxDrawdownBars = Math.max(maxDrawdownBars, drawdownBars); if (underwaterStart == null) underwaterStart = previous == null ? time : previous; underwater += previous == null ? 0 : Math.max(0, time - previous); } maxDrawdown = Math.max(maxDrawdown, peak > 0 ? (peak - equity) / peak : 0); series.add(JSON.objectNode().put("time", time).put("equity", equity)); previous = time; }
        JsonNode startNode = series.get(0).get("time"), endNode = series.get(series.size() - 1).get("time"); Long window = startNode == null || startNode.isNull() || endNode == null || endNode.isNull() ? null : Math.max(0, endNode.longValue() - startNode.longValue()); Double annualized = window != null && window > 0 && equity > 0 && initialEquity > 0 ? Math.pow(equity / initialEquity, 365.25 * 86_400_000d / window) - 1 : null; Double total = initialEquity > 0 ? (equity - initialEquity) / initialEquity : null;
        return new AccountPath(equity, series, total, annualized, maxDrawdown, maxDrawdownBars, underwater, window);
    }

    private static ObjectNode wilson(int wins, int total) {
        ObjectNode out = JSON.objectNode(); if (total == 0) return out.putNull("low").putNull("high"); double z = 1.96, p = wins / (double) total, denominator = 1 + z * z / total, centre = (p + z * z / (2 * total)) / denominator, radius = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator; return out.put("low", Math.max(0, centre - radius)).put("high", Math.min(1, centre + radius));
    }

    private static ObjectNode moments(List<Double> values) {
        ObjectNode out = JSON.objectNode(); if (values.size() < 3) return out.putNull("skew").putNull("excess_kurtosis"); double mean = jsMean(values), varianceSum = 0; for (double value : values) varianceSum += Math.pow(value - mean, 2); double sd = Math.sqrt(varianceSum / values.size()); if (!(sd > 0)) return out.put("skew", 0).put("excess_kurtosis", 0); double skew = 0, kurtosis = 0; for (double value : values) { double normalized = (value - mean) / sd; skew += Math.pow(normalized, 3); kurtosis += Math.pow(normalized, 4); } out.put("skew", skew / values.size()); out.put("excess_kurtosis", kurtosis / values.size() - 3); return out;
    }

    private static void addFinite(List<Double> values, JsonNode value) { Double number = finiteValue(value); if (number != null) values.add(number); }
    private static Double finiteValue(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return null; double number = jsNumber(value); return Double.isFinite(number) ? number : null; }
    private static double finiteOrZero(JsonNode value) { Double number = finiteValue(value); return number == null ? 0 : number; }
    private static Double firstFinite(JsonNode value, String... keys) { if (value == null) return null; for (String key : keys) { Double number = finiteValue(value.get(key)); if (number != null) return number; } return null; }
    private static double firstFiniteOrZero(JsonNode value, String... keys) { Double number = firstFinite(value, keys); return number == null ? 0 : number; }
    private static Double averageOrNull(List<Double> values) { return values.isEmpty() ? null : jsMean(values); }
    private static Double percentileOrNull(List<Double> values, double probability) { return values.isEmpty() ? null : quantile(values, probability); }
    private static void putDoubleOrNull(ObjectNode out, String key, Double value) { if (value == null || !Double.isFinite(value)) out.putNull(key); else out.put(key, value); }
    private static void putLongOrNull(ObjectNode out, String key, Long value) { if (value == null) out.putNull(key); else out.put(key, value); }
    private record AccountPath(double equity, ArrayNode series, Double totalReturn, Double annualized, double maxDrawdown, int maxDrawdownBars, long underwaterMs, Long windowMs) {}

    public static ObjectNode evaluateAcceptance(JsonNode metrics) { return evaluateAcceptance(metrics, makeAcceptanceContract(), JSON.objectNode()); }
    public static ObjectNode evaluateAcceptance(JsonNode metrics, JsonNode contract) { return evaluateAcceptance(metrics, contract, JSON.objectNode()); }

    public static ObjectNode evaluateAcceptance(JsonNode metrics, JsonNode contract, JsonNode options) {
        validateAcceptanceContract(contract); JsonNode gate = contract.get("gates"); List<String> failures = new ArrayList<>();
        String phase = optionText(options, "phase", "DEVELOPMENT"); JsonNode robust = metrics == null ? null : metrics.path("robust_stats");
        check(failures, finiteAtLeast(robust.get("effective_independent_episode_count"), gate.get("minimum_independent_episodes")), "MINIMUM_INDEPENDENT_EPISODES");
        check(failures, finiteAtLeast(metrics.get("completed_trades"), gate.get("minimum_completed_episodes")), "MINIMUM_COMPLETED_EPISODES");
        check(failures, finiteGreater(metrics.get("expectancy_r"), gate.get("minimum_expectancy_r")), "EXPECTANCY");
        check(failures, finiteGreater(metrics.get("search_adjusted_expectancy_r"), gate.get("minimum_search_adjusted_expectancy_r")), "SEARCH_ADJUSTED_EXPECTANCY");
        check(failures, metrics.path("profit_factor_r_unbounded").asBoolean(false) || finiteGreater(metrics.get("profit_factor_r"), gate.get("minimum_r_profit_factor")), "R_PROFIT_FACTOR");
        check(failures, metrics.path("account_profit_factor_unbounded").asBoolean(false) || finiteGreater(metrics.get("profit_factor_account"), gate.get("minimum_account_profit_factor")), "ACCOUNT_PROFIT_FACTOR");
        check(failures, finiteGreater(metrics.get("total_return"), gate.get("minimum_total_return")), "TOTAL_RETURN");
        check(failures, finiteGreater(robust.get("bootstrap_p20_expectancy_r"), gate.get("minimum_bootstrap_p20_expectancy_r")), "BOOTSTRAP_P20");
        if (!LATE_PHASES.contains(phase)) check(failures, finiteValue(robust.get("candidate_set_max_statistic_p_value")) != null && jsNumber(robust.get("candidate_set_max_statistic_p_value")) <= jsNumber(gate.get("maximum_candidate_set_p_value")), "MAX_STATISTIC_P_VALUE");
        check(failures, finiteValue(metrics.get("max_drawdown_pct")) != null && jsNumber(metrics.get("max_drawdown_pct")) <= jsNumber(gate.get("maximum_drawdown_pct")), "MAX_DRAWDOWN");
        long positiveYears = rows(metrics.get("years")).stream().filter(row -> jsNumber(row.get("expectancy_r")) > 0 && jsNumber(row.get("episodes")) >= jsNumber(gate.get("minimum_episodes_per_positive_year"))).count();
        check(failures, positiveYears >= jsNumber(gate.get("minimum_positive_years")), "POSITIVE_YEARS");
        List<JsonNode> blocks = rows(metrics.get("chronological_blocks")); check(failures, blocks.stream().filter(row -> jsNumber(row.get("expectancy_r")) > 0).count() >= jsNumber(gate.get("minimum_positive_blocks")), "POSITIVE_BLOCKS");
        check(failures, blocks.stream().noneMatch(row -> jsNumber(row.get("expectancy_r")) <= jsNumber(gate.get("maximum_negative_block_expectancy_r"))), "NEGATIVE_BLOCK");
        check(failures, finiteGreater(metrics.path("doubled_cost").get("expectancy_r"), gate.get("minimum_doubled_cost_expectancy_r")), "DOUBLED_COST_EXPECTANCY");
        check(failures, metrics.path("doubled_cost").path("profit_factor_account_unbounded").asBoolean(false) || finiteGreater(metrics.path("doubled_cost").get("profit_factor_account"), gate.get("minimum_doubled_cost_account_profit_factor")), "DOUBLED_COST_ACCOUNT_PF");
        JsonNode coverage = options == null ? null : options.get("coverage"); Double coverageFraction = firstFinite(coverage, "price_fraction", "feature_fraction"); if (coverageFraction == null) coverageFraction = finiteValue(metrics.get("coverage_fraction"));
        check(failures, coverageFraction != null && coverageFraction >= jsNumber(gate.get("minimum_coverage_fraction")), "COVERAGE");
        boolean derivativesRequired = metrics.path("derivatives_required").asBoolean(false) || (coverage != null && coverage.path("derivatives_required").asBoolean(false)) || "derivative".equals(text(metrics.get("instrument_class")));
        if (derivativesRequired) { Double derivative = firstFinite(coverage, "derivatives_fraction", "funding_fraction"); if (derivative == null) derivative = finiteValue(metrics.get("derivatives_coverage_fraction")); check(failures, derivative != null && derivative >= jsNumber(gate.get("minimum_coverage_fraction")), "DERIVATIVES_COVERAGE"); }
        check(failures, finiteValue(metrics.get("undeclared_gap_bars")) != null && jsNumber(metrics.get("undeclared_gap_bars")) <= jsNumber(gate.get("maximum_undeclared_gap_bars")), "UNDECLARED_GAP");

        JsonNode wfo = options == null ? null : options.get("wfo");
        if (LATE_PHASES.contains(phase)) {
            if (wfo == null || wfo.isNull()) failures.add("MISSING_WFO_EVIDENCE");
            else {
                check(failures, finiteAtLeast(wfo.get("oos_episodes"), gate.get("minimum_wfo_oos_episodes")), "WFO_OOS_EPISODES");
                check(failures, finiteAtLeast(wfo.get("positive_folds"), gate.get("minimum_wfo_positive_folds")), "WFO_POSITIVE_FOLDS");
                JsonNode aggregate = wfo.get("aggregate_oos_metrics"); String mapHash = null;
                if (wfo.path("final_selection_policy").isObject() && wfo.path("final_selection_by_asset").isObject() && wfo.path("final_selection_metrics_by_asset").isObject()) {
                    ObjectNode payload = JSON.objectNode(); payload.set("policy", cloneNode(wfo.get("final_selection_policy"))); payload.set("selection_by_asset", cloneNode(wfo.get("final_selection_by_asset"))); payload.set("selection_metrics_by_asset", cloneNode(wfo.get("final_selection_metrics_by_asset"))); mapHash = hash(payload);
                }
                boolean selectionMapValid = selectionMapValid(wfo); boolean candidateAccountingValid = candidateAccountingValid(wfo);
                boolean aggregateValid = aggregate != null && finiteValue(aggregate.get("expectancy_r")) != null && finiteValue(aggregate.get("search_adjusted_expectancy_r")) != null && finiteValue(aggregate.get("bootstrap_p20_expectancy_r")) != null;
                boolean complete = aggregateValid && wfo.path("fold_hashes").isArray() && !wfo.path("fold_hashes").isEmpty() && wfo.path("winner_lineage").isArray() && !wfo.path("winner_lineage").isEmpty()
                        && wfo.has("effective_k") && wfo.path("selection_policy").path("train_only").asBoolean(false) && bool(wfo.path("selection_policy").get("policy_sha256"))
                        && text(wfo.get("training_selection_policy_sha256")).equals(text(wfo.path("selection_policy").get("policy_sha256")))
                        && wfo.path("final_selection_policy").path("train_only").asBoolean(false) && bool(wfo.path("final_selection_policy").get("policy_sha256"))
                        && selectionMapValid && candidateAccountingValid && bool(wfo.get("final_selection_sha256")) && Objects.equals(mapHash, text(wfo.get("final_selection_sha256")));
                if (!complete) failures.add("MISSING_WFO_AGGREGATE_EVIDENCE");
            }
            JsonNode stress = options.get("stress"), portfolio = options.get("portfolio"); List<String> requiredStress = rows(contract.get("stress_scenarios")).stream().map(row -> row.isTextual() ? text(row) : text(row.get("name"))).toList();
            if (stress == null || stress.isNull()) failures.add("MISSING_STRESS_EVIDENCE");
            else {
                check(failures, "AUTHORITATIVE_RECOMPUTED".equals(text(stress.get("provenance"))), "STRESS_NOT_RECOMPUTED"); check(failures, isSha(text(stress.get("suite_sha256"))), "STRESS_SUITE_UNBOUND"); check(failures, stress.path("pass").asBoolean(false), "STRESS");
                JsonNode stressValue = first(stress, "scenarios", "results", "by_scenario"); List<JsonNode> stressRows = rows(stressValue); List<String> observed = stressRows.stream().map(row -> row.isTextual() ? text(row) : optionText(row, "name", text(row.get("scenario")))).toList();
                check(failures, observed.containsAll(requiredStress), "MISSING_REQUIRED_STRESS_SCENARIO");
                check(failures, stressRows.stream().filter(row -> !row.isTextual()).allMatch(row -> row.path("pass").asBoolean(false) && rows(row.get("missing_model_inputs")).isEmpty() && row.path("model_completeness").asBoolean(false)), "FAILED_STRESS_SCENARIO");
            }
            if (portfolio == null || portfolio.isNull()) failures.add("MISSING_PORTFOLIO_EVIDENCE"); else check(failures, portfolio.path("pass").asBoolean(false), "PORTFOLIO");
            if (derivativesRequired && !(options.path("funding").asBoolean(false) || metrics.path("funding_processed").asBoolean(false))) failures.add("MISSING_FUNDING_EVIDENCE");
            if (coverage == null || !coverage.path("verified").asBoolean(false)) failures.add("MISSING_VERIFIED_COVERAGE");
        }
        if ("CI_ATTESTED_CONFIRMATION".equals(phase)) failures.add("CI_ATTESTED_REQUIRES_PROSPECTIVE_REVIEW");
        if ("PROSPECTIVE_LIVE".equals(phase)) { JsonNode prospective = options.get("prospective"); if (prospective == null || !prospective.path("pass").asBoolean(false) || !prospective.path("frozen").asBoolean(false)) failures.add("MISSING_PROSPECTIVE_MONITORING"); }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(failures)); ObjectNode out = JSON.objectNode().put("pass", unique.isEmpty()); out.set("failures", strings(unique)); out.put("phase", phase).put("decision", unique.isEmpty() ? ("PROSPECTIVE_LIVE".equals(phase) ? "CANDIDATE_REVIEW" : "SHADOW") : "REJECTED"); return out;
    }

    public static boolean validateResearchDecision(JsonNode value) {
        String status = text(value == null ? null : value.get("status")); if (!DECISIONS.contains(status)) throw new IllegalArgumentException("invalid research decision " + status);
        if ("CANDIDATE_REVIEW".equals(status) && "EXTERNAL_EXPOSED".equals(text(value.get("provenance")))) throw new IllegalArgumentException("EXTERNAL_EXPOSED cannot reach CANDIDATE_REVIEW"); return true;
    }

    public static ObjectNode makeEvidenceBundle(JsonNode options) {
        JsonNode experiment = options.get("experiment"); JsonNode metrics = options.has("metrics") ? options.get("metrics") : JSON.arrayNode(); JsonNode trades = options.has("trades") ? options.get("trades") : JSON.arrayNode();
        JsonNode stress = options.get("stress"), portfolio = options.get("portfolio"), wfo = options.get("wfo"); JsonNode decision = options.has("decision") ? options.get("decision") : JSON.objectNode().put("status", "SHADOW"); JsonNode decisions = options.get("decisions");
        String provenance = optionText(options, "provenance", "AUTHORITATIVE_RECOMPUTED"); JsonNode acceptanceResult = options.get("acceptanceResult"), prospective = options.get("prospective"), candidateAccounting = options.get("candidateAccounting"); String acceptanceBasis = nullableText(options.get("acceptanceBasis")); JsonNode parentEvidence = options.get("parentEvidence");
        String phase = text(experiment.get("evidence_phase"));
        if ("SEALED_CONFIRMATION".equals(phase)) throw new IllegalArgumentException("local v3 evaluator cannot mint SEALED_CONFIRMATION; use an externally governed attestation");
        if ("CI_ATTESTED_CONFIRMATION".equals(phase)) throw new IllegalArgumentException("local v3 evidence cannot mint CI_ATTESTED_CONFIRMATION; use the unavailable public-unseen-data custody runner");
        if ("EXPOSED_CONFIRMATION".equals(phase)) { validateExposedParentEvidence(parentEvidence, experiment); if (!"FROZEN_PARENT_WFO_SELECTION".equals(acceptanceBasis)) throw new IllegalArgumentException("EXPOSED_CONFIRMATION evidence must bind the frozen parent-WFO selection basis"); }
        if ("ACTIVE".equals(text(decision.get("status"))) || "ACTIVE".equals(text(decision.get("activation")))) throw new IllegalArgumentException("ACTIVE is impossible in strategy research");
        if ("CANDIDATE_REVIEW".equals(text(decision.get("status")))) {
            if (!"PROSPECTIVE_LIVE".equals(phase)) throw new IllegalArgumentException("CANDIDATE_REVIEW is only allowed for PROSPECTIVE_LIVE");
            if (acceptanceResult == null || !"AUTHORITATIVE_RECOMPUTED".equals(text(acceptanceResult.get("provenance"))) || !acceptanceResult.path("pass").asBoolean(false) || !"CANDIDATE_REVIEW".equals(text(acceptanceResult.get("decision"))) || !"PROSPECTIVE_LIVE".equals(text(acceptanceResult.get("phase")))) throw new IllegalArgumentException("CANDIDATE_REVIEW requires a validated acceptance result");
            validateProspectiveProof(prospective, experiment); if (!text(acceptanceResult.get("prospective_monitoring_sha256")).equals(text(prospective.get("content_sha256")))) throw new IllegalArgumentException("acceptance result is not bound to prospective monitoring proof");
            if (rows(metrics).isEmpty() || rows(trades).isEmpty() || stress == null || stress.isNull() || portfolio == null || portfolio.isNull()) throw new IllegalArgumentException("CANDIDATE_REVIEW requires non-empty metrics/trades/stress/portfolio evidence");
        }
        validateDecisionAccounting(decisions, experiment, "v3 evidence bundle"); if ("AUTHORITATIVE_RECOMPUTED".equals(provenance) && (candidateAccounting == null || candidateAccounting.isNull())) throw new IllegalArgumentException("authoritative evidence requires compact candidate accounting digest");
        if (candidateAccounting != null && !candidateAccounting.isNull() && !text(candidateAccounting.get("content_sha256")).equals(ownHash(candidateAccounting))) throw new IllegalArgumentException("candidate accounting content hash mismatch");
        if (!text(decision.get("status")).equals(text(decisions.path("portfolio").get("status")))) throw new IllegalArgumentException("evidence bundle decision must equal portfolio decision");
        ObjectNode decisionWithProvenance = objectCopy(decision, "decision"); decisionWithProvenance.put("provenance", provenance); validateResearchDecision(decisionWithProvenance);
        ObjectNode identity = JSON.objectNode(); identity.set("experiment", cloneNode(experiment)); identity.set("metrics", cloneNode(metrics)); identity.set("trades", cloneNode(trades)); putNullable(identity, "stress", stress); putNullable(identity, "portfolio", portfolio); putNullable(identity, "wfo", wfo); identity.set("decision", cloneNode(decision)); identity.set("decisions", cloneNode(decisions)); putNullable(identity, "candidateAccounting", candidateAccounting); putNullable(identity, "acceptanceBasis", options.get("acceptanceBasis"));
        ObjectNode bundle = JSON.objectNode().put("schema", EVIDENCE_BUNDLE_V2_SCHEMA).put("bundle_id", hash(identity)).put("evidence_phase", phase).put("experiment_sha256", optionText(experiment, "content_sha256", ownHash(experiment)));
        for (String key : List.of("precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256", "feature_set_sha256", "label_set_sha256", "executor_sha256")) putNullable(bundle, key, experiment.get(key));
        putNullable(bundle, "container_sha256", experiment.get("container_sha256")); putNullable(bundle, "acceptance_contract_sha256", experiment.get("acceptance_contract_sha256")); bundle.put("portfolio_policy_sha256", hash(experiment.has("portfolio_policy") ? experiment.get("portfolio_policy") : JSON.objectNode()));
        bundle.set("metrics", cloneNode(metrics)); bundle.set("trades", cloneNode(trades)); putNullable(bundle, "stress", stress); putNullable(bundle, "portfolio", portfolio); putNullable(bundle, "wfo", wfo);
        bundle.put("metrics_sha256", hash(metrics)).put("trades_sha256", hash(trades)); putNullable(bundle, "stress_sha256", stress == null || stress.isNull() ? null : JSON.textNode(hash(stress))); putNullable(bundle, "portfolio_sha256", portfolio == null || portfolio.isNull() ? null : JSON.textNode(hash(portfolio))); putNullable(bundle, "wfo_sha256", wfo == null || wfo.isNull() ? null : JSON.textNode(hash(wfo)));
        putNullable(bundle, "candidate_accounting", candidateAccounting); putNullable(bundle, "candidate_accounting_sha256", candidateAccounting == null || candidateAccounting.isNull() ? null : JSON.textNode(ownHash(candidateAccounting))); putNullable(bundle, "acceptance_basis", options.get("acceptanceBasis"));
        bundle.set("decision", cloneNode(decision)); bundle.set("decisions", cloneNode(decisions)); bundle.put("provenance", provenance);
        if (acceptanceResult != null && !acceptanceResult.isNull()) { bundle.set("acceptance_result", cloneNode(acceptanceResult)); bundle.put("acceptance_result_sha256", hash(acceptanceResult)); putNullable(bundle, "prospective_monitoring", prospective); putNullable(bundle, "prospective_monitoring_sha256", prospective == null || prospective.isNull() ? null : JSON.textNode(hash(prospective))); }
        return withHash(bundle);
    }

    public static boolean validateEvidenceBundleV2(JsonNode bundle) { return validateEvidenceBundleV2(bundle, null); }

    public static boolean validateEvidenceBundleV2(JsonNode bundle, JsonNode experiment) {
        if (bundle == null || !EVIDENCE_BUNDLE_V2_SCHEMA.equals(text(bundle.get("schema")))) throw new IllegalArgumentException("strategy-evidence-bundle/2 is required");
        rejectUnknown(bundle, Set.of("schema", "bundle_id", "evidence_phase", "experiment_sha256", "precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256", "feature_set_sha256", "label_set_sha256", "executor_sha256", "container_sha256", "acceptance_contract_sha256", "portfolio_policy_sha256", "metrics", "trades", "stress", "portfolio", "wfo", "metrics_sha256", "trades_sha256", "stress_sha256", "portfolio_sha256", "wfo_sha256", "candidate_accounting", "candidate_accounting_sha256", "acceptance_basis", "decision", "decisions", "provenance", "acceptance_result", "acceptance_result_sha256", "prospective_monitoring", "prospective_monitoring_sha256", "content_sha256"), "strategy-evidence-bundle/2 unknown field: ");
        if ("SEALED_CONFIRMATION".equals(text(bundle.get("evidence_phase")))) throw new IllegalArgumentException("local v3 evidence cannot claim SEALED_CONFIRMATION");
        validateDecisionAccounting(bundle.get("decisions"), experiment, "strategy-evidence-bundle/2");
        if (!text(bundle.get("content_sha256")).equals(ownHash(bundle))) throw new IllegalArgumentException("evidence bundle v3 content hash mismatch");
        for (String key : List.of("metrics_sha256", "trades_sha256")) if (!text(bundle.get(key)).equals(hash(bundle.get(key.replace("_sha256", ""))))) throw new IllegalArgumentException("evidence bundle " + key + " mismatch");
        if (present(bundle.get("stress")) && !text(bundle.get("stress_sha256")).equals(hash(bundle.get("stress")))) throw new IllegalArgumentException("evidence bundle stress hash mismatch");
        if (experiment != null && present(bundle.get("stress"))) { JsonNode stress = bundle.get("stress"); if (!text(stress.get("experiment_sha256")).equals(optionText(experiment, "content_sha256", ownHash(experiment))) || !text(stress.get("contract_sha256")).equals(text(experiment.get("acceptance_contract_sha256"))) || !"AUTHORITATIVE_RECOMPUTED".equals(text(stress.get("provenance"))) || !isSha(text(stress.get("suite_sha256")))) throw new IllegalArgumentException("evidence bundle stress lineage mismatch or non-authoritative stress result"); }
        if (present(bundle.get("portfolio")) && !text(bundle.get("portfolio_sha256")).equals(hash(bundle.get("portfolio")))) throw new IllegalArgumentException("evidence bundle portfolio hash mismatch");
        if (present(bundle.get("wfo")) && !text(bundle.get("wfo_sha256")).equals(hash(bundle.get("wfo")))) throw new IllegalArgumentException("evidence bundle WFO hash mismatch");
        if (present(bundle.get("candidate_accounting")) && !text(bundle.get("candidate_accounting_sha256")).equals(ownHash(bundle.get("candidate_accounting")))) throw new IllegalArgumentException("evidence bundle candidate accounting hash mismatch");
        if ("AUTHORITATIVE_RECOMPUTED".equals(text(bundle.get("provenance"))) && (!present(bundle.get("candidate_accounting")) || !bool(bundle.get("candidate_accounting_sha256")))) throw new IllegalArgumentException("authoritative evidence requires compact candidate accounting digest");
        if (present(bundle.get("acceptance_result")) && !text(bundle.get("acceptance_result_sha256")).equals(hash(bundle.get("acceptance_result")))) throw new IllegalArgumentException("evidence bundle acceptance result hash mismatch");
        if (experiment != null && !text(bundle.get("experiment_sha256")).equals(optionText(experiment, "content_sha256", ownHash(experiment)))) throw new IllegalArgumentException("evidence bundle experiment lineage mismatch");
        if (experiment != null && "EXPOSED_CONFIRMATION".equals(text(experiment.get("evidence_phase")))) { ObjectNode frozen = frozenSelectionByAsset(experiment); if (!"FROZEN_PARENT_WFO_SELECTION".equals(text(bundle.get("acceptance_basis")))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION evidence must bind the frozen parent-WFO selection basis"); if (!present(bundle.get("wfo")) || !text(bundle.path("wfo").get("parent_evidence_sha256")).equals(text(experiment.get("parent_evidence_sha256"))) || !stable(bundle.path("wfo").get("final_selection_by_asset")).equals(stable(frozen))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION evidence is not bound to the validated parent WFO selection"); }
        if (experiment != null) for (String key : List.of("precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256", "feature_set_sha256", "label_set_sha256", "executor_sha256", "acceptance_contract_sha256")) if (!Objects.equals(text(bundle.get(key)), text(experiment.get(key)))) throw new IllegalArgumentException("evidence bundle " + key + " lineage mismatch");
        if (experiment != null && LATE_PHASES.contains(text(bundle.get("evidence_phase")))) for (String key : List.of("feature_set_sha256", "label_set_sha256", "executor_sha256")) if (!isSha(text(bundle.get(key)))) throw new IllegalArgumentException("evidence bundle " + key + " is required for " + text(bundle.get("evidence_phase")));
        if (experiment != null && !text(bundle.get("portfolio_policy_sha256")).equals(hash(experiment.has("portfolio_policy") ? experiment.get("portfolio_policy") : JSON.objectNode()))) throw new IllegalArgumentException("evidence bundle portfolio policy lineage mismatch");
        if ("ACTIVE".equals(text(bundle.path("decision").get("status"))) || "ACTIVE".equals(text(bundle.path("decision").get("activation")))) throw new IllegalArgumentException("ACTIVE is impossible in v3 evidence");
        if ("CI_ATTESTED_CONFIRMATION".equals(text(bundle.get("evidence_phase"))) && !"SHADOW".equals(text(bundle.path("decision").get("status")))) throw new IllegalArgumentException("CI_ATTESTED_CONFIRMATION is always SHADOW");
        if ("CANDIDATE_REVIEW".equals(text(bundle.path("decision").get("status")))) {
            if (!"PROSPECTIVE_LIVE".equals(text(bundle.get("evidence_phase")))) throw new IllegalArgumentException("CANDIDATE_REVIEW is only allowed for PROSPECTIVE_LIVE"); JsonNode acceptance = bundle.get("acceptance_result"), proof = bundle.get("prospective_monitoring");
            if (acceptance == null || !"AUTHORITATIVE_RECOMPUTED".equals(text(acceptance.get("provenance"))) || !acceptance.path("pass").asBoolean(false) || !"CANDIDATE_REVIEW".equals(text(acceptance.get("decision"))) || !text(acceptance.get("prospective_monitoring_sha256")).equals(text(proof == null ? null : proof.get("content_sha256")))) throw new IllegalArgumentException("bundle lacks validated, monitoring-bound acceptance result");
            validateProspectiveProof(proof, experiment); if (rows(bundle.get("metrics")).isEmpty() || rows(bundle.get("trades")).isEmpty() || !present(bundle.get("stress")) || !present(bundle.get("portfolio"))) throw new IllegalArgumentException("candidate review bundle lacks required evidence");
        }
        if (!text(bundle.path("decision").get("status")).equals(text(bundle.path("decisions").path("portfolio").get("status")))) throw new IllegalArgumentException("evidence bundle decision does not reconcile to portfolio decision");
        ObjectNode decision = objectCopy(bundle.get("decision"), "decision"); decision.put("provenance", text(bundle.get("provenance"))); validateResearchDecision(decision); return true;
    }

    public static JsonNode validateExposedParentEvidence(JsonNode parentEvidence, JsonNode experiment) {
        if (parentEvidence == null || !EVIDENCE_BUNDLE_V2_SCHEMA.equals(text(parentEvidence.get("schema")))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION requires a parent strategy-evidence-bundle/2");
        if (!text(parentEvidence.get("content_sha256")).equals(ownHash(parentEvidence))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION parent evidence retained-hash tampering"); validateEvidenceBundleV2(parentEvidence);
        if (!"WALK_FORWARD_OOS".equals(text(parentEvidence.get("evidence_phase")))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION parent evidence must be WALK_FORWARD_OOS");
        if (!bool(experiment.get("parent_evidence_sha256")) || !text(parentEvidence.get("content_sha256")).equals(text(experiment.get("parent_evidence_sha256")))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION parent evidence hash mismatch");
        for (String key : List.of("precommit_sha256", "definition_sha256", "candidate_set_sha256", "data_manifest_sha256", "feature_set_sha256", "label_set_sha256", "executor_sha256", "acceptance_contract_sha256")) if (!text(parentEvidence.get(key)).equals(text(experiment.get(key)))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION parent evidence lineage mismatch: " + key);
        JsonNode wfo = parentEvidence.get("wfo"); boolean accounting = wfo != null && wfo.path("candidate_accounting").isArray() && isSha(text(wfo.get("candidate_accounting_sha256"))) && text(wfo.get("candidate_accounting_sha256")).equals(hash(wfo.get("candidate_accounting")));
        if (wfo == null || !accounting || !wfo.path("selection_policy").path("train_only").asBoolean(false) || !wfo.path("final_selection_policy").path("train_only").asBoolean(false) || !bool(wfo.path("final_selection_policy").get("policy_sha256")) || !text(wfo.path("final_selection_policy").get("experiment_sha256")).equals(text(parentEvidence.get("experiment_sha256"))) || !text(wfo.get("training_selection_policy_sha256")).equals(text(experiment.get("training_selection_policy_sha256")))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION parent WFO selection policy or accounting is missing or mismatched");
        ObjectNode frozen = frozenSelectionByAsset(experiment); if (!stable(wfo.get("final_selection_by_asset")).equals(stable(frozen)) || !selectionMapValid(wfo)) throw new IllegalArgumentException("EXPOSED_CONFIRMATION frozen candidate map does not match parent WFO selection");
        ObjectNode payload = JSON.objectNode(); payload.set("policy", cloneNode(wfo.get("final_selection_policy"))); payload.set("selection_by_asset", cloneNode(wfo.get("final_selection_by_asset"))); payload.set("selection_metrics_by_asset", cloneNode(wfo.get("final_selection_metrics_by_asset"))); if (!text(wfo.get("final_selection_sha256")).equals(hash(payload))) throw new IllegalArgumentException("EXPOSED_CONFIRMATION parent WFO selection hash mismatch"); return wfo;
    }

    public static ObjectNode makeRunV3(JsonNode options) {
        JsonNode experiment = options.get("experiment"), evidence = options.get("evidenceBundle"), decisions = options.has("decisions") ? options.get("decisions") : JSON.objectNode(); String provenance = optionText(options, "provenance", "AUTHORITATIVE_RECOMPUTED"); String phase = text(experiment.get("evidence_phase"));
        if ("SEALED_CONFIRMATION".equals(phase)) throw new IllegalArgumentException("local v3 run cannot mint SEALED_CONFIRMATION"); if ("CI_ATTESTED_CONFIRMATION".equals(phase)) throw new IllegalArgumentException("local v3 run cannot mint CI_ATTESTED_CONFIRMATION; use the unavailable public-unseen-data custody runner");
        ArrayNode perAsset = arrayOf(rows(decisions.get("per_asset"))); for (JsonNode item : perAsset) { ObjectNode value = objectCopy(item, "decision"); value.put("provenance", provenance); validateResearchDecision(value); } if (present(decisions.get("portfolio"))) { ObjectNode value = objectCopy(decisions.get("portfolio"), "decision"); value.put("provenance", provenance); validateResearchDecision(value); }
        boolean review = rows(perAsset).stream().anyMatch(item -> "CANDIDATE_REVIEW".equals(text(item.get("status")))) || "CANDIDATE_REVIEW".equals(text(decisions.path("portfolio").get("status"))); if (review && !present(evidence)) throw new IllegalArgumentException("CANDIDATE_REVIEW run requires an evidence bundle");
        validateDecisionAccounting(decisions, experiment, "v3 run"); if (present(evidence)) validateEvidenceBundleV2(evidence, experiment);
        if (present(evidence)) { validateDecisionAccounting(evidence.get("decisions"), experiment, "run evidence-bundle decisions"); if (!stable(perAsset).equals(stable(evidence.path("decisions").get("per_asset"))) || !stable(decisions.has("portfolio") ? decisions.get("portfolio") : NullNode.instance).equals(stable(evidence.path("decisions").get("portfolio")))) throw new IllegalArgumentException("run decisions do not reconcile exactly to evidence bundle per-asset/portfolio decisions"); }
        ObjectNode payload = JSON.objectNode().put("schema", RUN_V3_SCHEMA).put("experiment_sha256", optionText(experiment, "content_sha256", ownHash(experiment))); putNullable(payload, "evidence_bundle_sha256", present(evidence) ? evidence.get("content_sha256") : null); payload.put("provenance", provenance).put("evidence_phase", phase);
        ObjectNode decisionRows = JSON.objectNode(); decisionRows.set("per_asset", perAsset); decisionRows.set("portfolio", decisions.has("portfolio") ? cloneNode(decisions.get("portfolio")) : JSON.objectNode().put("status", "SHADOW")); payload.set("decisions", decisionRows); payload.set("activation", JSON.objectNode().put("authorized", false).put("status", "RESEARCH_ONLY")); String id = hash(payload); payload.put("run_id", id).put("content_sha256", id); return payload;
    }

    public static boolean validateRunV3(JsonNode run) {
        if (run == null || !RUN_V3_SCHEMA.equals(text(run.get("schema")))) throw new IllegalArgumentException("strategy-run/3 is required"); rejectUnknown(run, Set.of("schema", "run_id", "experiment_sha256", "evidence_bundle_sha256", "provenance", "evidence_phase", "decisions", "activation", "content_sha256"), "strategy-run/3 unknown field: ");
        if ("SEALED_CONFIRMATION".equals(text(run.get("evidence_phase")))) throw new IllegalArgumentException("local v3 run cannot validate SEALED_CONFIRMATION"); if (run.path("activation").path("authorized").asBoolean(true) || !"RESEARCH_ONLY".equals(text(run.path("activation").get("status")))) throw new IllegalArgumentException("v3 run cannot authorize activation");
        if (!run.path("decisions").path("per_asset").isArray() || run.path("decisions").path("per_asset").isEmpty() || !bool(run.path("decisions").path("portfolio").get("status"))) throw new IllegalArgumentException("strategy-run/3 requires complete per-asset and portfolio decisions");
        boolean review = rows(run.path("decisions").get("per_asset")).stream().anyMatch(item -> "CANDIDATE_REVIEW".equals(text(item.get("status")))) || "CANDIDATE_REVIEW".equals(text(run.path("decisions").path("portfolio").get("status"))); if (review && !bool(run.get("evidence_bundle_sha256"))) throw new IllegalArgumentException("CANDIDATE_REVIEW run requires a lineage-bound evidence bundle");
        ObjectNode copy = objectCopy(run, "run"); copy.remove(List.of("run_id", "content_sha256")); String expected = hash(copy); if (!expected.equals(text(run.get("run_id"))) || !expected.equals(text(run.get("content_sha256")))) throw new IllegalArgumentException("run v3 run_id/content hash mismatch");
        List<JsonNode> all = new ArrayList<>(rows(run.path("decisions").get("per_asset"))); all.add(run.path("decisions").get("portfolio")); for (JsonNode item : all) { ObjectNode value = objectCopy(item, "decision"); value.put("provenance", text(run.get("provenance"))); validateResearchDecision(value); }
        if ("CI_ATTESTED_CONFIRMATION".equals(text(run.get("evidence_phase"))) && all.stream().anyMatch(item -> !"SHADOW".equals(text(item.get("status"))))) throw new IllegalArgumentException("CI_ATTESTED_CONFIRMATION is always SHADOW"); return true;
    }

    private static boolean validateProspectiveProof(JsonNode proof, JsonNode experiment) {
        if (proof == null || !"prospective-monitoring/1".equals(text(proof.get("schema"))) || !"AUTHORITATIVE_RECOMPUTED".equals(text(proof.get("provenance"))) || !"FROZEN_PROSPECTIVE_MONITOR".equals(text(proof.get("execution_mode"))) || !proof.path("pass").asBoolean(false) || !proof.path("frozen").asBoolean(false) || !proof.path("observations").isArray() || proof.path("observations").isEmpty() || !isSha(text(proof.get("run_id"))) || !isSha(text(proof.get("monitoring_contract_sha256"))) || !text(proof.get("observations_sha256")).equals(hash(proof.get("observations"))) || !text(proof.get("content_sha256")).equals(ownHash(proof))) throw new IllegalArgumentException("CANDIDATE_REVIEW requires a validated, lineage-bound prospective-monitoring/1 proof");
        for (String key : List.of("experiment_sha256", "data_manifest_sha256", "feature_set_sha256", "label_set_sha256", "executor_sha256", "acceptance_contract_sha256")) if (experiment != null && !text(proof.get(key)).equals(text(experiment.get(key)))) throw new IllegalArgumentException("prospective monitoring lineage mismatch: " + key); return true;
    }

    private static boolean validateDecisionAccounting(JsonNode decisions, JsonNode experiment, String label) {
        if (decisions == null || !decisions.path("per_asset").isArray() || !bool(decisions.path("portfolio").get("status"))) throw new IllegalArgumentException(label + " requires complete per-asset and portfolio decisions"); if (decisions.path("per_asset").isEmpty()) throw new IllegalArgumentException(label + " requires at least one per-asset decision");
        if (experiment == null || rows(experiment.get("required_assets")).isEmpty()) return true; List<String> required = rows(experiment.get("required_assets")).stream().map(item -> lower(item.isTextual() ? item : item.get("asset"))).sorted().toList(); List<String> actual = rows(decisions.get("per_asset")).stream().map(item -> lower(item.get("asset"))).sorted().toList();
        if (!required.equals(actual) || new LinkedHashSet<>(actual).size() != actual.size()) throw new IllegalArgumentException(label + " must account for every required crypto asset exactly once"); return true;
    }

    private static boolean selectionMapValid(JsonNode wfo) {
        JsonNode map = wfo == null ? null : wfo.get("final_selection_by_asset"), metrics = wfo == null ? null : wfo.get("final_selection_metrics_by_asset"); if (map == null || !map.isObject() || map.isEmpty() || metrics == null || !metrics.isObject() || metrics.size() != map.size()) return false;
        var fields = map.fields(); while (fields.hasNext()) { var field = fields.next(); if (!field.getValue().isTextual() || field.getValue().textValue().isEmpty() || !field.getValue().textValue().equals(text(metrics.path(field.getKey()).get("candidate_id"))) || !isSha(text(metrics.path(field.getKey()).get("metrics_sha256")))) return false; } return true;
    }

    private static boolean candidateAccountingValid(JsonNode wfo) {
        JsonNode accounting = wfo == null ? null : wfo.get("candidate_accounting"); if (accounting == null || !accounting.isArray() || !isSha(text(wfo.get("candidate_accounting_sha256"))) || !text(wfo.get("candidate_accounting_sha256")).equals(hash(accounting))) return false;
        for (JsonNode row : accounting) if (!bool(row.get("phase")) || !bool(row.get("fold_id")) || !present(row.get("window")) || !row.path("candidate_id").isTextual() || !row.path("asset").isTextual() || !integer(row.get("actual_trade_count")) || !integer(row.get("zero_episode_count"))) return false; return true;
    }

    private static void check(List<String> failures, boolean condition, String code) { if (!condition) failures.add(code); }
    private static boolean finiteAtLeast(JsonNode value, JsonNode threshold) { return finiteValue(value) != null && jsNumber(value) >= jsNumber(threshold); }
    private static boolean finiteGreater(JsonNode value, JsonNode threshold) { return finiteValue(value) != null && jsNumber(value) > jsNumber(threshold); }
    private static boolean integer(JsonNode value) { Double number = finiteValue(value); return number != null && number == Math.rint(number); }
    private static boolean present(JsonNode value) { return value != null && !value.isNull() && !value.isMissingNode(); }

    public static boolean validateWfoFolds(JsonNode folds) { return validateWfoFolds(folds, JSON.objectNode()); }

    public static boolean validateWfoFolds(JsonNode folds, JsonNode options) {
        if (folds == null || !folds.isArray() || folds.isEmpty()) throw new IllegalArgumentException("WFO requires at least one fold"); long previousTestEnd = Long.MIN_VALUE;
        for (int index = 0; index < folds.size(); index++) {
            JsonNode fold = folds.get(index); long trainStart = timestamp(coalesce(fold.get("train_start"), fold.path("train").get("start")), "fold " + (index + 1) + " train_start"); long trainEnd = timestamp(coalesce(fold.get("train_end"), fold.path("train").get("end")), "fold " + (index + 1) + " train_end"); long testStart = timestamp(coalesce(fold.get("test_start"), fold.path("test").get("start")), "fold " + (index + 1) + " test_start"); long testEnd = timestamp(coalesce(fold.get("test_end"), fold.path("test").get("end")), "fold " + (index + 1) + " test_end");
            double bar = valueOrOption(fold, "bar_duration_ms", options, "barDurationMs"); double purge = valueOrOption(fold, "purge_bars", options, "purgeBars", 0); double embargo = valueOrOption(fold, "embargo_bars", options, "embargoBars", 0);
            if (!(trainStart < trainEnd && trainEnd < testStart && testStart < testEnd)) throw new IllegalArgumentException("fold " + (index + 1) + " bounds must be chronological and non-overlapping");
            if (!(Double.isFinite(bar) && bar > 0 && purge == Math.rint(purge) && purge >= 0 && embargo == Math.rint(embargo) && embargo >= 0)) throw new IllegalArgumentException("fold " + (index + 1) + " must freeze bar_duration_ms, purge_bars and embargo_bars");
            if (testStart - trainEnd < (purge + embargo) * bar) throw new IllegalArgumentException("fold " + (index + 1) + " violates purge/embargo gap"); if (testStart < previousTestEnd) throw new IllegalArgumentException("WFO test windows overlap at fold " + (index + 1)); previousTestEnd = testEnd;
        }
        return true;
    }

    @FunctionalInterface
    public interface WfoEvaluator { JsonNode evaluate(JsonNode candidate, JsonNode fold, int index); }

    /** Serialized callbacks are intentionally rejected, matching the Node CLI's fail-closed branch. */
    public static ObjectNode walkForwardV3(JsonNode serializedConfig) {
        throw new IllegalArgumentException("authoritative WFO requires executable train/test evaluators; serialized callbacks are not an authority");
    }

    public static ObjectNode walkForwardV3(JsonNode candidatesNode, JsonNode folds, WfoEvaluator evaluateTrain, WfoEvaluator evaluateTest, JsonNode acceptance, JsonNode options) {
        JsonNode contract = acceptance == null ? makeAcceptanceContract() : acceptance; validateAcceptanceContract(contract); validateWfoFolds(folds, options);
        if (evaluateTrain == null || evaluateTest == null) throw new IllegalArgumentException("authoritative WFO requires executable train/test evaluators; serialized callbacks are not an authority");
        JsonNode policy = options == null ? null : options.get("trainingSelectionPolicy"); validateTrainingSelectionPolicy(policy);
        List<JsonNode> candidates = rows(candidatesNode); List<String> assets = new ArrayList<>(); for (JsonNode item : rows(options == null ? null : options.get("requiredAssets"))) { String asset = lower(item.isTextual() ? item : item.get("asset")); if (!asset.isEmpty() && !assets.contains(asset)) assets.add(asset); }
        List<ObjectNode> records = new ArrayList<>(), accounting = new ArrayList<>(); List<ObjectNode> oosTrades = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>(); int positiveFolds = 0;
        for (int index = 0; index < folds.size(); index++) {
            JsonNode fold = folds.get(index); String foldId = optionText(fold, "fold_id", "fold-" + (index + 1)); ObjectNode trainWindow = window(fold, "train"), testWindow = window(fold, "test"); List<Evaluation> trainRows = new ArrayList<>(); for (JsonNode candidate : candidates) trainRows.add(normalizeEvaluation(evaluateTrain.evaluate(candidate, fold, index), candidate, foldId, "TRAIN", trainWindow));
            LinkedHashMap<String, String> winnerByAsset = new LinkedHashMap<>(); ObjectNode selectionMetrics = JSON.objectNode(); List<String> selectionAssets = assets.isEmpty() ? List.of("__pooled__") : assets;
            for (String asset : selectionAssets) {
                List<MetricChoice> eligible = new ArrayList<>();
                for (Evaluation row : trainRows) { JsonNode metric = "__pooled__".equals(asset) ? row.metrics : metricDeclaredForAsset(row.byAsset, asset); if (metric != null && trainingThreshold(metric, policy)) eligible.add(new MetricChoice(row, metric)); }
                eligible.sort(Comparator.comparingDouble((MetricChoice choice) -> -jsNumber(choice.metric.get("expectancy_r"))).thenComparing(choice -> choice.row.candidateId));
                if (eligible.isEmpty()) throw new IllegalArgumentException("WFO fold " + foldId + " has no eligible train candidate" + ("__pooled__".equals(asset) ? "" : " for required asset: " + asset));
                MetricChoice selected = eligible.get(0); winnerByAsset.put(asset, selected.row.candidateId); ObjectNode selection = JSON.objectNode().put("candidate_id", selected.row.candidateId); if (!"__pooled__".equals(asset)) selection.put("asset", asset); selection.put("metrics_sha256", hash(selected.metric)).put("completed_trades", firstFiniteOrZero(selected.metric, "completed_trades", "completed_episodes")).put("expectancy_r", jsNumber(selected.metric.get("expectancy_r"))); selectionMetrics.set(asset, selection);
            }
            for (Evaluation row : trainRows) for (String asset : selectionAssets) {
                JsonNode metric = "__pooled__".equals(asset) ? row.metrics : metricForAsset(row, asset, foldId, "TRAIN", trainWindow); addAccounting(accounting, "TRAIN", foldId, trainWindow, row.candidateId, asset, row.trades, metric, Objects.equals(winnerByAsset.get(asset), row.candidateId));
            }
            List<String> selectedIds = new ArrayList<>(new LinkedHashSet<>(winnerByAsset.values())); List<Evaluation> testRows = new ArrayList<>();
            for (String id : selectedIds) { JsonNode candidate = candidates.stream().filter(item -> candidateId(item).equals(id)).findFirst().orElse(NullNode.instance); testRows.add(normalizeEvaluation(evaluateTest.evaluate(candidate, fold, index), candidate, foldId, "OOS", testWindow)); }
            List<ObjectNode> foldTrades = new ArrayList<>();
            for (Evaluation row : testRows) for (JsonNode tradeNode : row.trades) {
                ObjectNode trade = objectCopy(tradeNode, "trade"); String asset = lower(trade.get("asset")); boolean selected = assets.isEmpty() ? row.candidateId.equals(winnerByAsset.get("__pooled__")) : assets.contains(asset) && row.candidateId.equals(winnerByAsset.get(asset)); if (!selected || !seen.add(episodeId(trade))) continue; trade.put("selected_from_train", winnerByAsset.getOrDefault(asset, winnerByAsset.get("__pooled__"))); foldTrades.add(trade); oosTrades.add(trade.deepCopy());
            }
            ArrayNode testMetricsByAsset = JSON.arrayNode();
            for (String asset : assets) { String id = winnerByAsset.get(asset); Evaluation row = testRows.stream().filter(item -> item.candidateId.equals(id)).findFirst().orElse(new Evaluation(id, JSON.objectNode(), JSON.arrayNode(), JSON.arrayNode(), testWindow, NullNode.instance)); JsonNode metric = metricForAsset(row, asset, foldId, "OOS", testWindow); testMetricsByAsset.add(metric); addAccounting(accounting, "OOS", foldId, testWindow, id, asset, row.trades, metric, true); }
            if (assets.isEmpty()) { String id = winnerByAsset.get("__pooled__"); Evaluation row = testRows.stream().filter(item -> item.candidateId.equals(id)).findFirst().orElse(new Evaluation(id, JSON.objectNode(), JSON.arrayNode(), JSON.arrayNode(), testWindow, NullNode.instance)); addAccounting(accounting, "OOS", foldId, testWindow, id, "__pooled__", row.trades, row.metrics, true); }
            ArrayNode aggregateTrades = JSON.arrayNode(); for (ObjectNode trade : foldTrades) { ObjectNode copy = trade.deepCopy(); copy.put("candidate_id", "__fold_oos__"); aggregateTrades.add(copy); }
            ObjectNode aggregateTestOptions = JSON.objectNode().put("candidateId", "__fold_oos__").put("candidateCount", 1);
            aggregateTestOptions.set("candidateIds", JSON.arrayNode().add("__fold_oos__"));
            aggregateTestOptions.set("allTrades", aggregateTrades);
            aggregateTestOptions.put("seed", index + 1).put("bootstrapIterations", 512);
            JsonNode aggregateTest = aggregateTrades.isEmpty() ? (testRows.isEmpty() ? JSON.objectNode() : testRows.get(0).metrics) : computeCandidateMetrics(aggregateTrades, aggregateTestOptions);
            Double expectancy = finiteValue(aggregateTest.get("expectancy_r")); if (expectancy != null && expectancy > 0) positiveFolds++;
            ArrayNode trainCandidates = JSON.arrayNode(); for (Evaluation row : trainRows) { ObjectNode item = JSON.objectNode(); item.set("candidate", present(row.candidate) ? cloneNode(row.candidate) : candidates.stream().filter(candidate -> candidateId(candidate).equals(row.candidateId)).findFirst().map(LegacyResearchSupport::cloneNode).orElse(NullNode.instance)); item.set("metrics", row.metrics); item.set("candidate_asset_metrics", row.byAsset); trainCandidates.add(item); }
            ObjectNode winnerMap = JSON.objectNode(); winnerByAsset.forEach(winnerMap::put); ObjectNode record = JSON.objectNode().put("fold_id", foldId); record.set("train_window", trainWindow); record.set("test_window", testWindow); ObjectNode train = JSON.objectNode(); train.set("candidates", trainCandidates); putNullable(train, "winner", winnerByAsset.containsKey("__pooled__") ? JSON.textNode(winnerByAsset.get("__pooled__")) : null); train.set("winner_by_asset", winnerMap); train.set("selection_metrics_by_asset", selectionMetrics); record.set("train", train);
            ObjectNode test = JSON.objectNode(); putNullable(test, "candidate_id", winnerByAsset.containsKey("__pooled__") ? JSON.textNode(winnerByAsset.get("__pooled__")) : null); test.set("candidate_by_asset", winnerMap.deepCopy()); test.set("metrics", cloneNode(aggregateTest)); test.set("candidate_asset_metrics", testMetricsByAsset); test.set("trades", arrayOf(foldTrades)); record.set("test", test); records.add(record);
        }
        if (oosTrades.isEmpty()) throw new IllegalArgumentException("WFO missing aggregate OOS evidence: no winner/test trades were produced");
        if (oosTrades.stream().anyMatch(trade -> firstFinite(trade, "net_r", "r", "return_r") == null && firstFinite(trade, "equity_return_fraction", "return_fraction") == null)) throw new IllegalArgumentException("WFO missing aggregate OOS evidence: every OOS trade needs a return fraction or net_r");
        ArrayNode aggregateTrades = JSON.arrayNode(); for (ObjectNode trade : oosTrades) { ObjectNode copy = trade.deepCopy(); copy.put("candidate_id", "__aggregate_oos__"); aggregateTrades.add(copy); }
        ObjectNode aggregateOptions = JSON.objectNode().put("candidateId", "__aggregate_oos__").put("candidateCount", 1);
        aggregateOptions.set("candidateIds", JSON.arrayNode().add("__aggregate_oos__"));
        aggregateOptions.set("allTrades", aggregateTrades);
        aggregateOptions.put("seed", Math.max(1, folds.size())).put("bootstrapIterations", 2000);
        ObjectNode aggregate = computeCandidateMetrics(aggregateTrades, aggregateOptions);
        for (String key : List.of("expectancy_r", "search_adjusted_expectancy_r", "bootstrap_p20_expectancy_r")) { JsonNode value = aggregate.has(key) ? aggregate.get(key) : aggregate.path("robust_stats").get(key); if (finiteValue(value) == null) throw new IllegalArgumentException("WFO missing aggregate OOS metric: " + key); }
        ObjectNode finalRecord = records.get(records.size() - 1), finalSelection = JSON.objectNode(), finalMetrics = JSON.objectNode();
        for (String asset : selectionAssets(assets)) {
            List<MetricChoiceNode> eligible = new ArrayList<>(); for (JsonNode row : finalRecord.path("train").path("candidates")) { JsonNode metric = "__pooled__".equals(asset) ? row.get("metrics") : metricDeclaredForAsset(row.get("candidate_asset_metrics"), asset); if (metric != null && trainingThreshold(metric, policy)) eligible.add(new MetricChoiceNode(row, metric)); }
            eligible.sort(Comparator.comparingDouble((MetricChoiceNode choice) -> -jsNumber(choice.metric.get("expectancy_r"))).thenComparing(choice -> candidateId(choice.row.get("candidate")))); if (eligible.isEmpty()) throw new IllegalArgumentException("WFO final selection has no eligible train candidate for required asset: " + asset);
            MetricChoiceNode selected = eligible.get(0); String id = candidateId(selected.row.get("candidate")); finalSelection.put(asset, id); ObjectNode row = JSON.objectNode().put("fold_id", text(finalRecord.get("fold_id"))).put("candidate_id", id); if (!"__pooled__".equals(asset)) row.put("asset", asset); row.put("metrics_sha256", hash(selected.metric)).put("completed_trades", firstFiniteOrZero(selected.metric, "completed_trades", "completed_episodes")).put("expectancy_r", jsNumber(selected.metric.get("expectancy_r"))); finalMetrics.set(asset, row);
        }
        ArrayNode foldHashes = JSON.arrayNode(), winnerLineage = JSON.arrayNode(); for (ObjectNode record : records) { foldHashes.add(hash(record)); ObjectNode lineage = JSON.objectNode().put("fold_id", text(record.get("fold_id"))); putNullable(lineage, "winner", record.path("train").get("winner")); lineage.set("winner_by_asset", cloneNode(record.path("train").get("winner_by_asset"))); lineage.put("train_metrics_hash", hash(record.path("train").get("candidates"))).put("selection_metrics_sha256", hash(record.path("train").get("selection_metrics_by_asset"))); winnerLineage.add(lineage); }
        String experimentSha = optionText(options, "experimentSha256", ""); ObjectNode finalPolicy = JSON.objectNode().put("name", "LAST_TRAIN_FOLD_WINNER_PER_ASSET").put("train_only", true).put("policy_sha256", text(policy.get("content_sha256"))); putNullable(finalPolicy, "experiment_sha256", experimentSha.isEmpty() ? null : JSON.textNode(experimentSha)); finalPolicy.put("basis", "deterministic last chronological train fold; independent per-asset thresholds/objective/tie-break; no test or future confirmation observations");
        ObjectNode selectionPayload = JSON.objectNode(); selectionPayload.set("policy", finalPolicy); selectionPayload.set("selection_by_asset", finalSelection); selectionPayload.set("selection_metrics_by_asset", finalMetrics); ArrayNode accountingRows = arrayOf(accounting);
        ObjectNode notApplicable = JSON.objectNode().put("status", "NOT_APPLICABLE_NESTED_WFO").put("reason", "nested train-only selection controls search bias").put("declared_k", candidates.size()); ObjectNode aggregateOutput = aggregate.deepCopy(); ObjectNode robust = aggregateOutput.path("robust_stats").isObject() ? (ObjectNode) aggregateOutput.get("robust_stats") : aggregateOutput.putObject("robust_stats"); robust.set("candidate_set_max_statistic", notApplicable); robust.putNull("candidate_set_max_statistic_p_value"); aggregateOutput.set("candidate_set_max_statistic", notApplicable.deepCopy()); aggregateOutput.putNull("candidate_set_max_statistic_p_value");
        ObjectNode selectionPolicy = objectCopy(policy, "trainingSelectionPolicy"); selectionPolicy.put("policy_sha256", text(policy.get("content_sha256"))).put("train_only", true); putNullable(selectionPolicy, "experiment_sha256", experimentSha.isEmpty() ? null : JSON.textNode(experimentSha)); selectionPolicy.put("chronology", "purge/embargo are timestamp boundaries");
        ObjectNode out = JSON.objectNode().put("schema", "strategy-wfo-result/1"); out.set("folds", arrayOf(records)); out.set("oos_trades", arrayOf(oosTrades)); out.put("oos_episodes", oosTrades.stream().map(LegacyResearchV3::episodeId).distinct().count()).put("positive_folds", positiveFolds).put("effective_k", candidates.size()); out.set("fold_hashes", foldHashes); out.set("winner_lineage", winnerLineage); out.set("aggregate_oos_metrics", aggregateOutput); out.set("selection_policy", selectionPolicy); out.put("training_selection_policy_sha256", text(policy.get("content_sha256"))); out.set("final_selection_policy", finalPolicy); out.set("final_selection_by_asset", finalSelection); out.set("final_selection_metrics_by_asset", finalMetrics); out.put("final_selection_sha256", hash(selectionPayload)); out.set("candidate_accounting", accountingRows); out.put("candidate_accounting_sha256", hash(accountingRows)); return out;
    }

    public static boolean validateAuthoritativeData(JsonNode manifest, String phase, List<String> requiredAssets) {
        return ResearchData.validateManifest(object(manifest, "manifest"), new ResearchData.ValidationOptions(phase, requiredAssets, null));
    }

    private static Evaluation normalizeEvaluation(JsonNode evaluated, JsonNode candidate, String foldId, String phase, ObjectNode window) {
        JsonNode raw = evaluated != null && (evaluated.has("metrics") || evaluated.path("trades").isArray() || evaluated.path("by_asset").isArray() || evaluated.path("candidate_asset_metrics").isArray()) ? evaluated : JSON.objectNode().set("metrics", cloneNode(evaluated)); String id = candidateId(candidate); ArrayNode trades = JSON.arrayNode(); int index = 0;
        for (JsonNode tradeNode : rows(raw.get("trades"))) { ObjectNode trade = objectCopy(tradeNode, "trade"); trade.put("candidate_id", optionText(trade, "candidate_id", id)).put("fold_id", foldId).put("phase", phase).put("trade_id", optionText(trade, "trade_id", id + "|" + phase + "|" + foldId + "|" + index)).put("episode_id", optionText(trade, "episode_id", episodeId(trade))); trades.add(trade); index++; }
        JsonNode byAsset = raw.has("by_asset") ? raw.get("by_asset") : raw.has("candidate_asset_metrics") ? raw.get("candidate_asset_metrics") : JSON.arrayNode(); return new Evaluation(id, raw.has("metrics") ? cloneNode(raw.get("metrics")) : JSON.objectNode(), trades, arrayOf(rows(byAsset)), window, candidate);
    }

    private static JsonNode metricForAsset(Evaluation row, String asset, String foldId, String phase, ObjectNode window) {
        JsonNode declared = metricDeclaredForAsset(row.byAsset, asset); if (declared != null) { ObjectNode out = objectCopy(declared, "metric"); out.put("candidate_id", row.candidateId).put("asset", asset).put("phase", phase).put("fold_id", foldId); out.set("window", window); return out; }
        ArrayNode scoped = JSON.arrayNode();
        for (JsonNode trade : row.trades) if (asset.equals(lower(trade.get("asset")))) scoped.add(cloneNode(trade));
        ObjectNode options = JSON.objectNode().put("candidateId", row.candidateId).put("candidateCount", 1);
        options.set("candidateIds", JSON.arrayNode().add(row.candidateId));
        options.set("allTrades", scoped);
        options.put("seed", 1).put("bootstrapIterations", 256);
        ObjectNode out = computeCandidateMetrics(scoped, options); out.put("candidate_id", row.candidateId).put("asset", asset).put("phase", phase).put("fold_id", foldId).set("window", window); return out;
    }

    private static JsonNode metricDeclaredForAsset(JsonNode rows, String asset) { for (JsonNode metric : LegacyResearchSupport.rows(rows)) if (asset.equals(lower(metric.get("asset")))) return metric; return null; }
    private static boolean trainingThreshold(JsonNode metric, JsonNode policy) { return firstFiniteOrZero(metric, "completed_trades", "completed_episodes") >= jsNumber(policy.get("minimum_completed_trades")) && jsNumber(metric.get("expectancy_r")) >= jsNumber(policy.get("minimum_expectancy_r")); }
    private static void addAccounting(List<ObjectNode> accounting, String phase, String foldId, ObjectNode window, String candidate, String asset, JsonNode trades, JsonNode metric, boolean selected) { List<String> ids = new ArrayList<>(); for (JsonNode trade : LegacyResearchSupport.rows(trades)) if (asset.equals("__pooled__") || asset.equals(lower(trade.get("asset")))) ids.add(optionText(trade, "trade_id", optionText(trade, "episode_id", episodeId(trade)))); Collections.sort(ids); boolean zero = ids.isEmpty(); ObjectNode digest = JSON.objectNode().put("phase", phase).put("fold_id", foldId); digest.set("window", window); digest.put("candidate_id", candidate).put("asset", asset).set("trade_ids", strings(ids)); digest.put("metric_sha256", hash(metric == null ? JSON.objectNode() : metric)); ObjectNode row = JSON.objectNode().put("phase", phase).put("fold_id", foldId); row.set("window", window); row.put("candidate_id", candidate).put("asset", asset).put("selected", selected).put("actual_trade_count", ids.size()).put("zero_trade", zero).put("zero_episode_count", zero ? 1 : 0).put("outcome_digest_sha256", hash(digest)); accounting.add(row); }
    private static ObjectNode window(JsonNode fold, String kind) { ObjectNode out = JSON.objectNode(); putNullable(out, "start", coalesce(fold.get(kind + "_start"), fold.path(kind).get("start"))); putNullable(out, "end", coalesce(fold.get(kind + "_end"), fold.path(kind).get("end"))); return out; }
    private static String candidateId(JsonNode candidate) { return optionText(candidate, "candidate_id", optionText(candidate, "id", "")); }
    private static List<String> selectionAssets(List<String> assets) { return assets.isEmpty() ? List.of("__pooled__") : assets; }
    private static JsonNode coalesce(JsonNode left, JsonNode right) { return left != null && !left.isNull() ? left : right; }
    private static double valueOrOption(JsonNode value, String key, JsonNode options, String option) { return valueOrOption(value, key, options, option, Double.NaN); }
    private static double valueOrOption(JsonNode value, String key, JsonNode options, String option, double fallback) { if (value != null && value.hasNonNull(key)) return jsNumber(value.get(key)); if (options != null && options.hasNonNull(option)) return jsNumber(options.get(option)); return fallback; }
    private record Evaluation(String candidateId, JsonNode metrics, ArrayNode trades, ArrayNode byAsset, ObjectNode window, JsonNode candidate) {}
    private record MetricChoice(Evaluation row, JsonNode metric) {}
    private record MetricChoiceNode(JsonNode row, JsonNode metric) {}

    public static ObjectNode generateEd25519KeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519"); KeyPair pair = generator.generateKeyPair();
            return JSON.objectNode().put("publicKey", pem("PUBLIC KEY", pair.getPublic().getEncoded())).put("privateKey", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        } catch (Exception error) { throw new IllegalStateException("Ed25519 key generation failed", error); }
    }

    public static ObjectNode makeConfirmationReservation(JsonNode options) { return makeConfirmationReservation(options, Clock.systemUTC()); }

    public static ObjectNode makeConfirmationReservation(JsonNode options, Clock clock) {
        String seal = optionText(options, "sealId", ""); if (seal.isEmpty() || !seal.matches("^[A-Za-z0-9._-]+$")) throw new IllegalArgumentException("seal_id is required and must be safe");
        ObjectNode reservation = JSON.objectNode().put("schema", RESERVATION_SCHEMA).put("seal_id", seal).put("status", "RESERVED");
        for (String[] field : List.of(new String[]{"repository", "repository"}, new String[]{"commit_sha", "commitSha"}, new String[]{"workflow_sha256", "workflowSha256"}, new String[]{"precommit_sha256", "precommitSha256"}, new String[]{"definition_sha256", "definitionSha256"}, new String[]{"experiment_sha256", "experimentSha256"}, new String[]{"candidate_set_sha256", "candidateSetSha256"}, new String[]{"data_root_sha256", "dataRootSha256"}, new String[]{"acceptance_contract_sha256", "acceptanceContractSha256"}, new String[]{"container_sha256", "containerSha256"}, new String[]{"executor_sha256", "executorSha256"}, new String[]{"experiment_path", "experimentPath"}, new String[]{"data_path", "dataPath"})) putNullable(reservation, field[0], options.get(field[1]));
        reservation.put("output", optionText(options, "output", "confirmation-evidence.json")).put("created_at", jsIsoInstant(clock)); reservation = withHash(reservation);
        ObjectNode validation = JSON.objectNode(); if (options.has("workflowPath")) putNullable(validation, "workflowPath", options.get("workflowPath")); validateConfirmationReservation(reservation, validation); return reservation;
    }

    public static boolean validateConfirmationReservation(JsonNode reservation) { return validateConfirmationReservation(reservation, JSON.objectNode()); }

    public static boolean validateConfirmationReservation(JsonNode reservation, JsonNode options) {
        if (reservation == null || !RESERVATION_SCHEMA.equals(text(reservation.get("schema"))) || !"RESERVED".equals(text(reservation.get("status")))) throw new IllegalArgumentException("confirmation reservation must be strategy-confirmation-reservation/1 RESERVED");
        if (!bool(reservation.get("seal_id")) || !REPOSITORY.matcher(text(reservation.get("repository"))).matches() || !COMMIT_SHA.matcher(text(reservation.get("commit_sha"))).matches()) throw new IllegalArgumentException("reservation repository and exact 40-character commit_sha are required");
        String repository = nullableText(options.get("repository")), currentCommit = nullableText(options.get("currentCommit")); if (repository != null && !repository.equals(text(reservation.get("repository")))) throw new IllegalArgumentException("reservation repository mismatch"); if (currentCommit != null && !currentCommit.equals(text(reservation.get("commit_sha")))) throw new IllegalArgumentException("reservation commit is not the current commit");
        for (String key : List.of("workflow_sha256", "precommit_sha256", "definition_sha256", "experiment_sha256", "candidate_set_sha256", "data_root_sha256", "acceptance_contract_sha256", "container_sha256", "executor_sha256")) if (!isSha(text(reservation.get(key)))) throw new IllegalArgumentException("reservation." + key + " must be a SHA-256 hash");
        if (!bool(reservation.get("experiment_path")) || !bool(reservation.get("data_path"))) throw new IllegalArgumentException("reservation must declare frozen experiment/data paths");
        for (String key : List.of("experiment_path", "data_path")) { String value = text(reservation.get(key)); if (Path.of(value).isAbsolute() || value.startsWith("/") || value.startsWith("\\") || value.split("[/\\\\]").length == 0 || Path.of(value).normalize().startsWith("..")) throw new IllegalArgumentException("reservation." + key + " must be a repository-relative path"); }
        String reservationPath = nullableText(options.get("reservationPath")); if (reservationPath != null && !Path.of(CONFIRMATION_RESERVATION_DIR).toAbsolutePath().normalize().equals(Path.of(reservationPath).toAbsolutePath().normalize()) && !Path.of(reservationPath).toAbsolutePath().normalize().startsWith(Path.of(CONFIRMATION_RESERVATION_DIR).toAbsolutePath().normalize())) throw new IllegalArgumentException("reservation path must be under strategy-research/confirmations");
        String workflow = options.has("workflowPath") ? nullableText(options.get("workflowPath")) : ".github/workflows/strategy-confirmation.yml";
        if (workflow != null) { Path path = Path.of(workflow).toAbsolutePath().normalize(); if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("reservation workflow path does not exist: " + workflow); byte[] bytes = com.tradinganalytics.infrastructure.security.PathConfinement.readSinglyLinkedFile(path, "reservation workflow"); if (!hash(bytes).equals(text(reservation.get("workflow_sha256")))) throw new IllegalArgumentException("reservation workflow bytes do not match workflow_sha256"); }
        if (!text(reservation.get("content_sha256")).equals(ownHash(reservation))) throw new IllegalArgumentException("reservation content hash mismatch"); return true;
    }

    public static Path burnReservation(JsonNode reservation) { return burnReservation(reservation, Path.of(".research-run/burn")); }

    public static Path burnReservation(JsonNode reservation, Path burnRoot) {
        if (reservation == null || !RESERVATION_SCHEMA.equals(text(reservation.get("schema"))) || !"RESERVED".equals(text(reservation.get("status")))) throw new IllegalArgumentException("reservation must be RESERVED");
        if (!text(reservation.get("content_sha256")).equals(ownHash(reservation))) throw new IllegalArgumentException("reservation hash mismatch"); Path path = burnRoot.toAbsolutePath().normalize().resolve(text(reservation.get("seal_id")) + ".burn"); if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("confirmation seal already burned: " + text(reservation.get("seal_id"))); writeExclusive(path, (text(reservation.get("content_sha256")) + "\n").getBytes(StandardCharsets.UTF_8)); return path;
    }

    public static ObjectNode signAttestation(JsonNode options) { return signAttestation(options, Clock.systemUTC()); }

    public static ObjectNode signAttestation(JsonNode options, Clock clock) {
        JsonNode reservation = options.get("reservation"), result = options.get("result"); String privatePem = nullableText(options.get("privateKeyPem")), repository = optionText(options, "repository", ""), commit = optionText(options, "commitSha", ""), workflowSha = optionText(options, "workflowSha", "");
        if (!REPOSITORY.matcher(repository).matches() || !COMMIT_SHA.matcher(commit).matches() || !isSha(workflowSha)) throw new IllegalArgumentException("attestation requires repository, exact 40-character commit_sha and workflow SHA-256"); if (privatePem == null || result == null) throw new IllegalArgumentException("attestation requires a private key and authoritative result");
        String workflowPath = options.has("workflowPath") ? nullableText(options.get("workflowPath")) : ".github/workflows/strategy-confirmation.yml"; if (workflowPath == null) throw new IllegalArgumentException("attestation requires the frozen workflow path for byte validation");
        String resultDecision = bool(result.path("decision").get("status")) ? text(result.path("decision").get("status")) : text(result.path("decisions").path("portfolio").get("status"));
        if (!text(result.get("data_root_sha256")).equals(text(reservation == null ? null : reservation.get("data_root_sha256")))) throw new IllegalArgumentException("attestation result must bind the reserved data root");
        if (!"CI_ATTESTED_CONFIRMATION".equals(text(result.get("evidence_phase"))) || !"SHADOW".equals(resultDecision)) throw new IllegalArgumentException("CI attestation result must be CI_ATTESTED_CONFIRMATION/SHADOW");
        if ("SEALED_CONFIRMATION".equals(text(result.get("evidence_phase"))) || "ACTIVE".equals(text(result.get("status"))) || Set.of("ACTIVE", "CANDIDATE_REVIEW").contains(resultDecision) || "ACTIVE".equals(text(result.get("activation")))) throw new IllegalArgumentException("CI attestation cannot claim SEALED_CONFIRMATION, ACTIVE, or CANDIDATE_REVIEW");
        ObjectNode validation = JSON.objectNode().put("currentCommit", commit).put("repository", repository).put("workflowPath", workflowPath); if (options.has("reservationPath")) putNullable(validation, "reservationPath", options.get("reservationPath")); validateConfirmationReservation(reservation, validation);
        String runId = optionText(options, "runId", ""); if (runId.isEmpty()) throw new IllegalArgumentException("attestation run_id is required"); if (optionNumber(options, "runAttempt", 1) != 1) throw new IllegalArgumentException("confirmation reruns are rejected");
        if (!repository.equals(text(reservation.get("repository")))) throw new IllegalArgumentException("attestation repository does not match reservation"); if (!commit.equals(text(reservation.get("commit_sha")))) throw new IllegalArgumentException("attestation commit does not match reservation"); if (!workflowSha.equals(text(reservation.get("workflow_sha256")))) throw new IllegalArgumentException("attestation workflow does not match reservation");
        JsonNode burn = options.get("burnReceipt"); if (burn == null || burn.isNull()) throw new IllegalArgumentException("durable remote burn receipt is required before signing"); ObjectNode receipt = objectCopy(burn, "burn receipt");
        if (!("refs/tags/research-seal/" + text(reservation.get("seal_id"))).equals(text(receipt.get("ref"))) || !text(receipt.get("reservation_sha256")).equals(text(reservation.get("content_sha256"))) || !text(receipt.get("commit_sha")).equals(text(reservation.get("commit_sha"))) || !"BURNED".equals(text(receipt.get("status")))) throw new IllegalArgumentException("burn receipt does not bind reservation/tag/commit"); if (!bool(receipt.get("receipt_sha256"))) receipt.put("receipt_sha256", hash(receipt));
        ObjectNode payload = JSON.objectNode().put("schema", ATTESTATION_SCHEMA).put("attestation_type", "CI_ATTESTED_CONFIRMATION").put("provider", optionText(options, "provider", "GITHUB_CI_SECRET")).put("seal_id", text(reservation.get("seal_id"))).put("reservation_sha256", text(reservation.get("content_sha256"))).put("repository", repository).put("commit_sha", commit).put("workflow_sha", workflowSha).put("run_id", runId).put("run_attempt", 1);
        for (String key : List.of("precommit_sha256", "definition_sha256", "experiment_sha256", "candidate_set_sha256", "data_root_sha256", "acceptance_contract_sha256", "container_sha256", "executor_sha256")) payload.set(key, cloneNode(reservation.get(key)));
        payload.set("burn_receipt", receipt);
        payload.put("result_sha256", hash(result));
        payload.set("result", cloneNode(result));
        payload.put("issued_at", jsIsoInstant(clock));
        payload.put("signature", sign(payload, privatePem)); payload.put("content_sha256", hash(payload)); return payload;
    }

    public static ObjectNode verifyAttestation(JsonNode attestation, JsonNode options) {
        if (attestation == null || !ATTESTATION_SCHEMA.equals(text(attestation.get("schema")))) throw new IllegalArgumentException("strategy-attestation/1 is required"); if (!"CI_ATTESTED_CONFIRMATION".equals(text(attestation.get("attestation_type")))) throw new IllegalArgumentException("attestation must be CI_ATTESTED_CONFIRMATION"); ObjectNode content = objectCopy(attestation, "attestation"); content.remove("content_sha256"); if (!text(attestation.get("content_sha256")).equals(hash(content))) throw new IllegalArgumentException("attestation content hash mismatch"); if (jsNumber(attestation.get("run_attempt")) != 1) throw new IllegalArgumentException("attestation rerun is invalid");
        JsonNode reservation = options.get("reservation"); if (reservation == null || reservation.isNull()) throw new IllegalArgumentException("reservation is required to verify CI attestation lineage"); String workflowPath = options.has("workflowPath") ? nullableText(options.get("workflowPath")) : ".github/workflows/strategy-confirmation.yml";
        for (String[] expected : List.of(new String[]{"expectedRepository", "repository", "attestation repository mismatch"}, new String[]{"expectedCommitSha", "commit_sha", "attestation commit mismatch"}, new String[]{"expectedRunId", "run_id", "attestation run mismatch"})) if (options.hasNonNull(expected[0]) && !text(options.get(expected[0])).equals(text(attestation.get(expected[1])))) throw new IllegalArgumentException(expected[2]);
        if (!text(attestation.get("reservation_sha256")).equals(text(reservation.get("content_sha256"))) || !text(attestation.get("seal_id")).equals(text(reservation.get("seal_id")))) throw new IllegalArgumentException("attestation reservation mismatch");
        ObjectNode validation = JSON.objectNode().put("currentCommit", text(attestation.get("commit_sha"))).put("repository", text(attestation.get("repository"))); putNullable(validation, "workflowPath", workflowPath == null ? null : JSON.textNode(workflowPath)); if (options.has("reservationPath")) putNullable(validation, "reservationPath", options.get("reservationPath")); validateConfirmationReservation(reservation, validation);
        for (String key : List.of("precommit_sha256", "definition_sha256", "experiment_sha256", "candidate_set_sha256", "data_root_sha256", "acceptance_contract_sha256", "container_sha256", "executor_sha256")) if (!text(attestation.get(key)).equals(text(reservation.get(key)))) throw new IllegalArgumentException("attestation " + key + " mismatch");
        JsonNode receipt = attestation.get("burn_receipt"); if (present(receipt)) { ObjectNode copy = objectCopy(receipt, "burn receipt"); copy.remove("receipt_sha256"); if (!hash(copy).equals(text(receipt.get("receipt_sha256")))) throw new IllegalArgumentException("burn receipt hash mismatch"); }
        if (!present(receipt) || !text(receipt.get("reservation_sha256")).equals(text(attestation.get("reservation_sha256"))) || !text(receipt.get("commit_sha")).equals(text(attestation.get("commit_sha"))) || !("refs/tags/research-seal/" + text(attestation.get("seal_id"))).equals(text(receipt.get("ref"))) || !"BURNED".equals(text(receipt.get("status")))) throw new IllegalArgumentException("durable burn receipt is required and must bind the immutable research-seal tag");
        String publicPem = nullableText(options.get("publicKeyPem")); if (publicPem == null || !verify(attestation, publicPem)) throw new IllegalArgumentException("attestation signature invalid"); if (!text(attestation.get("result_sha256")).equals(hash(attestation.get("result")))) throw new IllegalArgumentException("attestation result hash mismatch");
        JsonNode result = attestation.get("result"); String decision = bool(result.path("decision").get("status")) ? text(result.path("decision").get("status")) : text(result.path("decisions").path("portfolio").get("status")); if (!text(result.get("data_root_sha256")).equals(text(attestation.get("data_root_sha256")))) throw new IllegalArgumentException("attestation result data root mismatch"); if (!"CI_ATTESTED_CONFIRMATION".equals(text(result.get("evidence_phase"))) || !"SHADOW".equals(decision)) throw new IllegalArgumentException("CI attestation result must be CI_ATTESTED_CONFIRMATION/SHADOW"); if ("SEALED_CONFIRMATION".equals(text(result.get("evidence_phase"))) || "ACTIVE".equals(text(result.get("status"))) || Set.of("ACTIVE", "CANDIDATE_REVIEW").contains(decision) || "ACTIVE".equals(text(result.get("activation")))) throw new IllegalArgumentException("CI attestation cannot claim SEALED_CONFIRMATION, ACTIVE, or CANDIDATE_REVIEW");
        ObjectNode out = JSON.objectNode().put("valid", true).put("label", "CI_ATTESTED_CONFIRMATION").put("seal_id", text(attestation.get("seal_id"))); out.set("result", cloneNode(result)); return out;
    }

    public static ObjectNode importAttestation(JsonNode attestation, JsonNode options) { return importAttestation(attestation, options, Clock.systemUTC()); }

    public static ObjectNode importAttestation(JsonNode attestation, JsonNode options, Clock clock) {
        ObjectNode verified = verifyAttestation(attestation, options); ObjectNode base = JSON.objectNode().put("schema", "strategy-attestation-import/1"); merge(base, verified); base.put("attestation_sha256", hash(attestation)).put("imported_at", jsIsoInstant(clock)).put("status", "CONSUMED"); ObjectNode out = base.deepCopy(); out.put("content_sha256", ownHash(base));
        Path root = Path.of(CONFIRMATION_RESERVATION_DIR, "imports").toAbsolutePath().normalize(); Path target = options.hasNonNull("out") ? Path.of(text(options.get("out"))).toAbsolutePath().normalize() : root.resolve(text(attestation.get("seal_id")) + ".json"); if (!target.startsWith(root)) throw new IllegalArgumentException("attestation import record must be under strategy-research/confirmations/imports"); secureParents(root);
        try (var stream = Files.list(root)) { for (Path path : stream.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) { JsonNode prior; try { prior = readJson(path); if (!text(prior.get("content_sha256")).equals(ownHash(prior))) throw new IllegalArgumentException(); } catch (RuntimeException error) { throw new IllegalArgumentException("invalid existing attestation import record: " + path.getFileName()); } if (text(prior.get("attestation_sha256")).equals(text(out.get("attestation_sha256")))) throw new IllegalArgumentException("attestation replay import is already recorded"); } } catch (java.io.IOException error) { throw new IllegalArgumentException("attestation import directory cannot be read", error); }
        writeExclusive(target, jsonBytes(out)); out.put("path", target.toString()); return out;
    }

    private static String pem(String type, byte[] bytes) { String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes); return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n"; }
    private static byte[] pemBytes(String pem) { return Base64.getMimeDecoder().decode(pem.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "")); }
    private static String sign(ObjectNode payload, String privatePem) { try { PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pemBytes(privatePem))); Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key); signer.update(stable(payload).getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(signer.sign()); } catch (Exception error) { throw new IllegalArgumentException("attestation signature failed", error); } }
    private static boolean verify(JsonNode attestation, String publicPem) { try { PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pemBytes(publicPem))); Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key); ObjectNode payload = objectCopy(attestation, "attestation"); payload.remove(List.of("signature", "content_sha256")); verifier.update(stable(payload).getBytes(StandardCharsets.UTF_8)); return verifier.verify(Base64.getDecoder().decode(text(attestation.get("signature")))); } catch (Exception error) { return false; } }

    private static double optionNumber(JsonNode options, String key, double fallback) { return options != null && options.hasNonNull(key) ? jsNumber(options.get(key)) : fallback; }
    private static String optionText(JsonNode options, String key, String fallback) { return options != null && options.hasNonNull(key) ? text(options.get(key)) : fallback; }
    private static String jsIsoInstant(Clock clock) { return JS_ISO_INSTANT.format(clock.instant()); }
    private static void putNullable(ObjectNode value, String key, JsonNode item) { value.set(key, item == null ? NullNode.instance : cloneNode(item)); }
    private static boolean isSha(String value) { return SHA256.matcher(value == null ? "" : value).matches(); }
    private static boolean cryptoAsset(String asset) { String value = asset == null ? "" : asset.toLowerCase(Locale.ROOT); return !"doge".equals(value) && (CORE_UNIVERSE.contains(value) || value.matches("^[a-z0-9-]+$")); }
    private static void rejectUnknown(JsonNode value, Set<String> allowed, String prefix) { value.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new IllegalArgumentException(prefix + key); }); }
    private static long timestamp(JsonNode value, String label) {
        if (value != null && value.isNumber()) return value.longValue(); String raw = text(value);
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (DateTimeParseException ignored) { try { return OffsetDateTime.parse(raw).toInstant().toEpochMilli(); } catch (DateTimeParseException error) { throw new IllegalArgumentException(label + " must be a valid timestamp"); } }
    }

    private static final class SeededRng {
        private int state;
        SeededRng(int seed) { state = seed == 0 ? 1 : seed; }
        double next() { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return Integer.toUnsignedLong(state) / 4_294_967_296d; }
    }
}
