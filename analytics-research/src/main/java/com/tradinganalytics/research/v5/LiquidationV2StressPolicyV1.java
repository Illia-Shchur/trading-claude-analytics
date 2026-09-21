package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reopens the frozen v002 stress policy and exposes deterministic policy transforms only.
 * It does not replay trades, apply fills, or create scenario outcome rows; the accounting
 * engine must invoke each transform while rerunning the scenario before any stress can pass.
 */
public final class LiquidationV2StressPolicyV1 {
    public static final String POLICY_SCHEMA = "liquidation-v2-stress-policy/1";
    public static final String TRANSFORM_SCHEMA = "liquidation-v2-stress-policy-transform/1";
    public static final String FROZEN_PRECOMMIT_SHA256 =
            "cfae45fac7678a913610c37c156b044ed733e3ecd5e45673b01e412f8eaca904";
    public static final int REQUIRED_SCENARIO_COUNT = 5;
    public static final Duration OUTAGE_INTERVAL = Duration.ofHours(1);
    public static final String OUTAGE_WINDOW_RULE =
            "First 1h after every qualifying event, applied uniformly before outcomes; zero affected trades fails as no-op";

    private static final List<String> REQUIRED_IDS = List.of("fee_slippage", "funding_carry",
            "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout");

    private LiquidationV2StressPolicyV1() {}

    public enum PositionSide {
        LONG(1), SHORT(-1);
        private final int fundingCostSign;
        PositionSide(int fundingCostSign) { this.fundingCostSign = fundingCostSign; }
    }

    public sealed interface ScenarioPolicy permits FeeSlippagePolicy, FundingCarryPolicy,
            AdverseExecutionGapPolicy, LiquidityCapacityPolicy, VenueOutagePolicy {
        String scenarioId();
        double minimumExpectancyR();
        int minimumObservations();
    }

    public record FeeSlippagePolicy(double costMultiplier, double minimumExpectancyR,
            int minimumObservations) implements ScenarioPolicy {
        @Override public String scenarioId() { return "fee_slippage"; }
    }

    /** Positive signed costs are debits; negative signed costs are credits. */
    public record FundingCarryPolicy(double debitMultiplier, double creditMultiplier,
            double minimumExpectancyR, int minimumObservations) implements ScenarioPolicy {
        @Override public String scenarioId() { return "funding_carry"; }
    }

    public record AdverseExecutionGapPolicy(double debitR, double minimumExpectancyR,
            int minimumObservations) implements ScenarioPolicy {
        @Override public String scenarioId() { return "adverse_execution_gap"; }
    }

    public record LiquidityCapacityPolicy(double maximumParticipationRate,
            double minimumExpectancyR, int minimumObservations) implements ScenarioPolicy {
        @Override public String scenarioId() { return "liquidity_capacity"; }
    }

    /**
     * The outage window is half-open: an entry is blocked at the qualifying event instant and
     * until, but not including, eventTime + interval. The runner supplies the frozen eligible
     * event timestamps and must retain mark, funding and exposure economics during outages.
     */
    public record VenueOutagePolicy(Duration intervalAfterEvent, String windowRule,
            double minimumExpectancyR, int minimumObservations) implements ScenarioPolicy {
        @Override public String scenarioId() { return "venue_outage_blackout"; }
    }

    /** A validated, immutable view of the self-hashed frozen policy. */
    public static final class Policy {
        private final String precommitSha256;
        private final FeeSlippagePolicy feeSlippage;
        private final FundingCarryPolicy fundingCarry;
        private final AdverseExecutionGapPolicy adverseExecutionGap;
        private final LiquidityCapacityPolicy liquidityCapacity;
        private final VenueOutagePolicy venueOutage;
        private final ObjectNode json;
        private final String contentSha256;

        private Policy(String precommitSha256, FeeSlippagePolicy feeSlippage,
                FundingCarryPolicy fundingCarry, AdverseExecutionGapPolicy adverseExecutionGap,
                LiquidityCapacityPolicy liquidityCapacity, VenueOutagePolicy venueOutage) {
            this.precommitSha256 = precommitSha256;
            this.feeSlippage = feeSlippage;
            this.fundingCarry = fundingCarry;
            this.adverseExecutionGap = adverseExecutionGap;
            this.liquidityCapacity = liquidityCapacity;
            this.venueOutage = venueOutage;
            this.json = policyJson(precommitSha256, feeSlippage, fundingCarry, adverseExecutionGap,
                    liquidityCapacity, venueOutage);
            this.contentSha256 = JsonHashes.ownHash(json);
            this.json.put("content_sha256", contentSha256);
        }

        public String precommitSha256() { return precommitSha256; }
        public String contentSha256() { return contentSha256; }
        public String status() { return "POLICY_ONLY_NOT_RUN"; }
        public FeeSlippagePolicy feeSlippage() { return feeSlippage; }
        public FundingCarryPolicy fundingCarry() { return fundingCarry; }
        public AdverseExecutionGapPolicy adverseExecutionGap() { return adverseExecutionGap; }
        public LiquidityCapacityPolicy liquidityCapacity() { return liquidityCapacity; }
        public VenueOutagePolicy venueOutage() { return venueOutage; }
        public List<ScenarioPolicy> scenarios() {
            return List.of(feeSlippage, fundingCarry, adverseExecutionGap, liquidityCapacity, venueOutage);
        }
        public ObjectNode toJson() { return json.deepCopy(); }

        public boolean validate() {
            if (!contentSha256.equals(JsonHashes.ownHash(json))
                    || !FROZEN_PRECOMMIT_SHA256.equals(precommitSha256)) {
                throw new IllegalArgumentException("stress policy self-hash or frozen parent reference is invalid");
            }
            return true;
        }

        /** Applies the multiplier to fee and slippage components separately before accounting reruns. */
        public Transform scaleTradingCosts(double feeCostUsdt, double slippageCostUsdt) {
            requireFiniteNonnegative(feeCostUsdt, "fee cost");
            requireFiniteNonnegative(slippageCostUsdt, "slippage cost");
            double fee = feeCostUsdt * feeSlippage.costMultiplier();
            double slippage = slippageCostUsdt * feeSlippage.costMultiplier();
            requireFinite(fee, "stressed fee"); requireFinite(slippage, "stressed slippage");
            return transform("fee_slippage", precommitSha256, contentSha256)
                    .put("source_fee_cost_usdt", feeCostUsdt)
                    .put("source_slippage_cost_usdt", slippageCostUsdt)
                    .put("fee_multiplier", feeSlippage.costMultiplier())
                    .put("slippage_multiplier", feeSlippage.costMultiplier())
                    .put("stressed_fee_cost_usdt", fee)
                    .put("stressed_slippage_cost_usdt", slippage)
                    .finish();
        }

        /** Doubles the frozen metadata fee/slippage rates before fills and account sizing are rerun. */
        public Transform scaleTradingCostRates(double feeRate, double slippageRate) {
            requireFiniteNonnegative(feeRate, "fee rate");
            requireFiniteNonnegative(slippageRate, "slippage rate");
            double stressedFeeRate = feeRate * feeSlippage.costMultiplier();
            double stressedSlippageRate = slippageRate * feeSlippage.costMultiplier();
            requireFinite(stressedFeeRate, "stressed fee rate");
            requireFinite(stressedSlippageRate, "stressed slippage rate");
            return transform("fee_slippage", precommitSha256, contentSha256)
                    .put("source_fee_rate", feeRate)
                    .put("fee_rate_unit", "DECIMAL_RATE")
                    .put("fee_rate_multiplier", feeSlippage.costMultiplier())
                    .put("stressed_fee_rate", stressedFeeRate)
                    .put("source_slippage_rate", slippageRate)
                    .put("slippage_rate_unit", "DECIMAL_RATE")
                    .put("slippage_rate_multiplier", feeSlippage.costMultiplier())
                    .put("stressed_slippage_rate", stressedSlippageRate)
                    .finish();
        }

        /**
         * Builds signed funding cost from the actual open base quantity, mark, settlement rate,
         * and side. Only positive debits are multiplied by two; negative credits remain unchanged.
         */
        public Transform stressFunding(double openBaseQuantity, double settlementMark,
                double signedFundingRate, PositionSide side) {
            requireFinitePositive(openBaseQuantity, "open base quantity");
            requireFinitePositive(settlementMark, "settlement mark");
            requireFinite(signedFundingRate, "signed funding rate");
            Objects.requireNonNull(side, "side");
            double sourceCost = openBaseQuantity * settlementMark * signedFundingRate * side.fundingCostSign;
            requireFinite(sourceCost, "source signed funding cost");
            boolean debit = sourceCost > 0.0;
            double multiplier = debit ? fundingCarry.debitMultiplier() : fundingCarry.creditMultiplier();
            double stressedCost = sourceCost * multiplier;
            requireFinite(stressedCost, "stressed signed funding cost");
            return transform("funding_carry", precommitSha256, contentSha256)
                    .put("open_base_quantity", openBaseQuantity)
                    .put("settlement_mark", settlementMark)
                    .put("signed_funding_rate", signedFundingRate)
                    .put("position_side", side.name())
                    .put("source_signed_cost_usdt", sourceCost)
                    .put("is_debit", debit)
                    .put("applied_multiplier", multiplier)
                    .put("debit_multiplier", fundingCarry.debitMultiplier())
                    .put("credit_multiplier", fundingCarry.creditMultiplier())
                    .put("stressed_signed_cost_usdt", stressedCost)
                    .finish();
        }

        /** Applies the frozen gap debit once to the supplied position-level reference risk. */
        public Transform adverseGapDebit(double positionReferenceRiskUsdt) {
            requireFinitePositive(positionReferenceRiskUsdt, "position reference risk");
            double debitUsdt = positionReferenceRiskUsdt * adverseExecutionGap.debitR();
            requireFinite(debitUsdt, "adverse gap debit");
            return transform("adverse_execution_gap", precommitSha256, contentSha256)
                    .put("position_reference_risk_usdt", positionReferenceRiskUsdt)
                    .put("debit_r", adverseExecutionGap.debitR())
                    .put("position_gap_debit_usdt", debitUsdt)
                    .put("application_scope", "ONCE_PER_POSITION")
                    .finish();
        }

        /** Caps quantity from the last completed minute's base volume, never the fill minute's volume. */
        public Transform capacityLimit(double lastCompletedMinuteBaseVolume) {
            requireFiniteNonnegative(lastCompletedMinuteBaseVolume, "last completed minute base volume");
            double maximumBaseQuantity = lastCompletedMinuteBaseVolume * liquidityCapacity.maximumParticipationRate();
            requireFinite(maximumBaseQuantity, "maximum capacity quantity");
            return transform("liquidity_capacity", precommitSha256, contentSha256)
                    .put("last_completed_minute_base_volume", lastCompletedMinuteBaseVolume)
                    .put("maximum_participation_rate", liquidityCapacity.maximumParticipationRate())
                    .put("maximum_base_quantity", maximumBaseQuantity)
                    .finish();
        }

        /** Evaluates one candidate entry time against one eligible outage-triggering event. */
        public Transform outageDecision(String qualifyingEventId, Instant qualifyingEventTime,
                Instant requestedEntryTime) {
            if (qualifyingEventId == null || qualifyingEventId.isBlank()) {
                throw new IllegalArgumentException("qualifying event ID is required");
            }
            Objects.requireNonNull(qualifyingEventTime, "qualifyingEventTime");
            Objects.requireNonNull(requestedEntryTime, "requestedEntryTime");
            Instant intervalEnd = qualifyingEventTime.plus(venueOutage.intervalAfterEvent());
            boolean blocked = !requestedEntryTime.isBefore(qualifyingEventTime)
                    && requestedEntryTime.isBefore(intervalEnd);
            return transform("venue_outage_blackout", precommitSha256, contentSha256)
                    .put("qualifying_event_id", qualifyingEventId)
                    .put("qualifying_event_time", qualifyingEventTime.toString())
                    .put("requested_entry_time", requestedEntryTime.toString())
                    .put("interval_start_inclusive", qualifyingEventTime.toString())
                    .put("interval_end_exclusive", intervalEnd.toString())
                    .put("blocked", blocked)
                    .put("window_rule", venueOutage.windowRule())
                    .put("economics_retained_during_outage", true)
                    .finish();
        }
    }

    /** Self-hashed transform input/output record, not an outcome or stress-evaluation row. */
    public static final class Transform {
        private final ObjectNode json;
        private Transform(ObjectNode json) {
            this.json = json;
            this.json.put("content_sha256", JsonHashes.ownHash(this.json));
        }
        public String contentSha256() { return json.path("content_sha256").asText(); }
        public boolean validate() {
            if (!contentSha256().equals(JsonHashes.ownHash(json))
                    || !TRANSFORM_SCHEMA.equals(json.path("schema").asText())
                    || json.path("outcome_row_emitted").asBoolean(true)) {
                throw new IllegalArgumentException("stress transform self-hash or policy-only status is invalid");
            }
            return true;
        }
        public ObjectNode toJson() { return json.deepCopy(); }
    }

    public static Policy reopen(Path frozenPrecommitPath) {
        Objects.requireNonNull(frozenPrecommitPath, "frozenPrecommitPath");
        try {
            JsonNode parsed = JsonHashes.mapper().readTree(frozenPrecommitPath.toFile());
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalArgumentException("frozen precommit must be a JSON object");
            }
            return reopen(object);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot reopen frozen v002 precommit", error);
        }
    }

    public static Policy reopen(ObjectNode frozenPrecommit) {
        Objects.requireNonNull(frozenPrecommit, "frozenPrecommit");
        if (!"strategy-precommit/1".equals(frozenPrecommit.path("schema").asText())
                || !"liquidation-daily-stress-v002".equals(frozenPrecommit.path("precommit_id").asText())
                || !FROZEN_PRECOMMIT_SHA256.equals(frozenPrecommit.path("content_sha256").asText())
                || !FROZEN_PRECOMMIT_SHA256.equals(JsonHashes.ownHash(frozenPrecommit))) {
            throw new IllegalArgumentException("expected exact self-hashed frozen v002 precommit");
        }
        JsonNode requiredScenarios = frozenPrecommit.path("experiment").path("acceptance")
                .path("stress").path("required_scenarios");
        validateRequiredScenarios(requiredScenarios);
        List<ScenarioPolicy> policies = policiesFrom(requiredScenarios);
        return new Policy(FROZEN_PRECOMMIT_SHA256,
                (FeeSlippagePolicy) policies.get(0), (FundingCarryPolicy) policies.get(1),
                (AdverseExecutionGapPolicy) policies.get(2), (LiquidityCapacityPolicy) policies.get(3),
                (VenueOutagePolicy) policies.get(4));
    }

    private static void validateRequiredScenarios(JsonNode scenarios) {
        if (!scenarios.isArray() || scenarios.size() != REQUIRED_SCENARIO_COUNT) {
            throw new IllegalArgumentException("frozen stress policy must contain exactly five required scenarios");
        }
        for (int index = 0; index < REQUIRED_SCENARIO_COUNT; index++) {
            JsonNode scenario = scenarios.get(index);
            if (!scenario.isObject() || !REQUIRED_IDS.get(index).equals(scenario.path("id").asText())) {
                throw new IllegalArgumentException("frozen stress scenario inventory/order is invalid at index " + index);
            }
            if (number(scenario, "minimum_expectancy_r").compareTo(BigDecimal.ZERO) != 0
                    || integer(scenario, "minimum_observations") != 30) {
                throw new IllegalArgumentException("frozen stress observation/expectancy gate changed for " + REQUIRED_IDS.get(index));
            }
            switch (REQUIRED_IDS.get(index)) {
                case "fee_slippage" -> {
                    exactFields(scenario, "id", "multiplier", "minimum_expectancy_r", "minimum_observations");
                    requireNumber(scenario, "multiplier", "2");
                }
                case "funding_carry" -> {
                    exactFields(scenario, "id", "multiplier", "credit_multiplier", "minimum_expectancy_r", "minimum_observations");
                    requireNumber(scenario, "multiplier", "2");
                    requireNumber(scenario, "credit_multiplier", "1");
                }
                case "adverse_execution_gap" -> {
                    exactFields(scenario, "id", "debit_r", "minimum_expectancy_r", "minimum_observations");
                    requireNumber(scenario, "debit_r", "0.25");
                }
                case "liquidity_capacity" -> {
                    exactFields(scenario, "id", "maximum_participation_rate", "minimum_expectancy_r", "minimum_observations");
                    requireNumber(scenario, "maximum_participation_rate", "0.01");
                }
                case "venue_outage_blackout" -> {
                    exactFields(scenario, "id", "window_rule", "minimum_expectancy_r", "minimum_observations");
                    if (!OUTAGE_WINDOW_RULE.equals(scenario.path("window_rule").asText())) {
                        throw new IllegalArgumentException("frozen venue outage interval rule changed");
                    }
                }
                default -> throw new IllegalArgumentException("unknown frozen stress scenario");
            }
        }
    }

    private static List<ScenarioPolicy> policiesFrom(JsonNode scenarios) {
        List<ScenarioPolicy> policies = new ArrayList<>(REQUIRED_SCENARIO_COUNT);
        for (JsonNode scenario : scenarios) {
            double minimumExpectancy = number(scenario, "minimum_expectancy_r").doubleValue();
            int minimumObservations = integer(scenario, "minimum_observations");
            switch (scenario.path("id").asText()) {
                case "fee_slippage" -> policies.add(new FeeSlippagePolicy(number(scenario, "multiplier").doubleValue(),
                        minimumExpectancy, minimumObservations));
                case "funding_carry" -> policies.add(new FundingCarryPolicy(number(scenario, "multiplier").doubleValue(),
                        number(scenario, "credit_multiplier").doubleValue(), minimumExpectancy, minimumObservations));
                case "adverse_execution_gap" -> policies.add(new AdverseExecutionGapPolicy(
                        number(scenario, "debit_r").doubleValue(), minimumExpectancy, minimumObservations));
                case "liquidity_capacity" -> policies.add(new LiquidityCapacityPolicy(
                        number(scenario, "maximum_participation_rate").doubleValue(), minimumExpectancy, minimumObservations));
                case "venue_outage_blackout" -> policies.add(new VenueOutagePolicy(OUTAGE_INTERVAL,
                        scenario.path("window_rule").asText(), minimumExpectancy, minimumObservations));
                default -> throw new IllegalArgumentException("unknown frozen stress scenario");
            }
        }
        return List.copyOf(policies);
    }

    private static ObjectNode policyJson(String precommitSha256, FeeSlippagePolicy fee,
            FundingCarryPolicy funding, AdverseExecutionGapPolicy gap, LiquidityCapacityPolicy capacity,
            VenueOutagePolicy outage) {
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", POLICY_SCHEMA).put("version", 1)
                .put("source_precommit_sha256", precommitSha256).put("status", "POLICY_ONLY_NOT_RUN")
                .put("outcome_rows_emitted", false);
        ArrayNode scenarios = result.putArray("scenarios");
        scenarios.addObject().put("id", fee.scenarioId()).put("cost_multiplier", fee.costMultiplier())
                .put("minimum_expectancy_r", fee.minimumExpectancyR()).put("minimum_observations", fee.minimumObservations());
        scenarios.addObject().put("id", funding.scenarioId()).put("debit_multiplier", funding.debitMultiplier())
                .put("credit_multiplier", funding.creditMultiplier()).put("minimum_expectancy_r", funding.minimumExpectancyR())
                .put("minimum_observations", funding.minimumObservations());
        scenarios.addObject().put("id", gap.scenarioId()).put("debit_r", gap.debitR())
                .put("application_scope", "ONCE_PER_POSITION").put("minimum_expectancy_r", gap.minimumExpectancyR())
                .put("minimum_observations", gap.minimumObservations());
        scenarios.addObject().put("id", capacity.scenarioId()).put("maximum_participation_rate", capacity.maximumParticipationRate())
                .put("volume_source", "LAST_COMPLETED_MINUTE_BASE_VOLUME")
                .put("minimum_expectancy_r", capacity.minimumExpectancyR()).put("minimum_observations", capacity.minimumObservations());
        scenarios.addObject().put("id", outage.scenarioId()).put("interval_seconds", outage.intervalAfterEvent().toSeconds())
                .put("window_rule", outage.windowRule()).put("interval_convention", "HALF_OPEN_EVENT_TIME_INCLUSIVE_END_EXCLUSIVE")
                .put("retain_open_exposure_economics", true).put("minimum_expectancy_r", outage.minimumExpectancyR())
                .put("minimum_observations", outage.minimumObservations());
        return result;
    }

    private static TransformBuilder transform(String scenarioId, String precommitSha256, String policySha256) {
        return new TransformBuilder(JsonHashes.mapper().createObjectNode()
                .put("schema", TRANSFORM_SCHEMA).put("version", 1).put("status", "POLICY_TRANSFORM_ONLY_NOT_EXECUTED")
                .put("scenario_id", scenarioId).put("source_precommit_sha256", precommitSha256)
                .put("source_policy_sha256", policySha256).put("outcome_row_emitted", false));
    }

    private static final class TransformBuilder {
        private final ObjectNode json;
        private TransformBuilder(ObjectNode json) { this.json = json; }
        private TransformBuilder put(String key, String value) { json.put(key, value); return this; }
        private TransformBuilder put(String key, double value) { json.put(key, value); return this; }
        private TransformBuilder put(String key, long value) { json.put(key, value); return this; }
        private TransformBuilder put(String key, boolean value) { json.put(key, value); return this; }
        private Transform finish() { return new Transform(json); }
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) throw new IllegalArgumentException("frozen stress field must be numeric: " + field);
        return value.decimalValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("frozen stress field must be an integer: " + field);
        }
        return value.intValue();
    }

    private static void requireNumber(JsonNode node, String field, String expected) {
        if (number(node, field).compareTo(new BigDecimal(expected)) != 0) {
            throw new IllegalArgumentException("frozen stress parameter changed: " + field);
        }
    }

    private static void exactFields(JsonNode node, String... fields) {
        if (!node.isObject() || node.size() != fields.length) {
            throw new IllegalArgumentException("frozen stress scenario contains missing or unexpected fields");
        }
        for (String field : fields) if (!node.has(field)) {
            throw new IllegalArgumentException("frozen stress scenario is missing " + field);
        }
    }

    private static void requireFinite(double value, String label) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(label + " must be finite");
    }
    private static void requireFiniteNonnegative(double value, String label) {
        requireFinite(value, label);
        if (value < 0.0) throw new IllegalArgumentException(label + " must be nonnegative");
    }
    private static void requireFinitePositive(double value, String label) {
        requireFinite(value, label);
        if (value <= 0.0) throw new IllegalArgumentException(label + " must be positive");
    }
}
