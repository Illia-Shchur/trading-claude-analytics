package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import java.util.Set;

/** Capability-bound, additive execution/research policy for liquidation-daily-stress-v002. */
public final class LiquidationDailyStressProfileV1 {
    public static final String SCHEMA = "liquidation-daily-stress-profile/1";
    private static final String PRECOMMIT_SHA = "cfae45fac7678a913610c37c156b044ed733e3ecd5e45673b01e412f8eaca904";
    private static final List<String> ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");
    private static final List<String> ROLES = List.of("feature", "label", "execution", "mark", "funding", "metadata");

    private LiquidationDailyStressProfileV1() {}

    /** Builds only the frozen policy. The caller cannot override scope, lifecycle or evidence class. */
    public static ObjectNode frozenContract() {
        ObjectNode profile = JsonHashes.mapper().createObjectNode()
                .put("schema", SCHEMA).put("version", 1)
                .put("profile_id", "liquidation-daily-stress-binance-isolated-usdm-v1")
                .put("precommit_id", "liquidation-daily-stress-v002")
                .put("precommit_content_sha256", PRECOMMIT_SHA)
                .put("strategy_family", "liquidation-structure")
                .put("evidence_tier", "DEVELOPMENT_ONLY")
                .put("authoritative_wfo_permitted", false)
                .put("sealed_confirmation_permitted", false)
                .put("prospective_live_permitted", false)
                .put("trade_authorization_permitted", false)
                .put("candidate_returns_inspected", false);
        profile.set("assets", JsonHashes.mapper().valueToTree(ASSETS));
        ObjectNode instrument = profile.putObject("instrument_scope");
        instrument.put("venue", "BINANCE").put("contract", "USD_M_LINEAR_PERPETUAL")
                .put("quote_asset", "USDT").put("collateral", "USDT").put("margin_mode", "ISOLATED")
                .put("directions", "LONG_AND_SHORT").put("one_common_position_per_asset", true)
                .put("opposite_positions_across_assets_allowed", true).put("risk_netting_credit", false);
        ObjectNode clocks = profile.putObject("clocks");
        clocks.put("daily_seconds", 86_400).put("daily_assumed_available_after_bucket_start_seconds", 172_800)
                .put("structure_seconds", 14_400).put("decision_seconds", 3_600).put("execution_seconds", 60)
                .put("oi_endpoint_availability_lag_seconds", 300).put("oi_max_staleness_seconds", 600);
        ObjectNode windows = profile.putObject("windows");
        windows.put("source_start", "2022-08-11T00:00:00Z").put("decision_start", "2022-11-11T00:00:00Z")
                .put("decision_end_exclusive", "2026-07-15T00:00:00Z")
                .put("execution_end_exclusive", "2026-09-20T00:00:00Z")
                .put("maximum_lifecycle_days", 60).put("setup_and_entry_wait_days", 6)
                .put("minimum_outer_and_inner_purge_days", 67).put("embargo_days", 7)
                .put("outcome_overlap_removal", "REQUIRED_BY_ACTUAL_FIRST_FILL_AND_EXIT_TIMES");
        ObjectNode data = profile.putObject("physical_data_contract");
        data.put("format", "PARQUET").put("query_runtime", "PINNED_DUCKDB_JDBC")
                .put("plan_schema", LiquidationV2PhysicalDataV1.PLAN_SCHEMA)
                .put("manifest_schema", LiquidationV2PhysicalDataV1.MANIFEST_SCHEMA)
                .put("require_physical_parquet_roles", true).put("require_bound_plan_precommit_and_source", true)
                .put("require_distinct_role_paths", true).put("label_rows_may_enter_signal_predicates", false)
                .put("outcome_or_execution_rows_may_enter_signal_predicates", false)
                .put("opportunity_envelope_frozen_before_outcome_read", true)
                .put("hydration_requires_warmup_setup_wait_full_lifecycle_and_execution_margin", true)
                .put("daily_percentile_warmup_calendar_days", 90)
                .put("post_decision_setup_hold_and_execution_margin_days", 67)
                .put("minimum_contiguous_hydration_span_days", 157)
                .put("synthetic_fixture_can_claim_full_historical_hydration", false);
        data.set("required_roles", JsonHashes.mapper().valueToTree(ROLES));
        data.set("required_assets", JsonHashes.mapper().valueToTree(ASSETS));
        ObjectNode risk = profile.putObject("capital_and_risk");
        risk.put("starting_equity_usdt", 20_000).put("margin_fraction_cap", 1.0)
                .put("position_risk_fraction_cap", 0.05).put("portfolio_risk_fraction_cap", 0.10)
                .put("risk_netting_credit", false).put("risk_reference", "EQUITY_AT_FIRST_FILL")
                .put("position_risk_includes_paid_fees_known_funding_and_estimated_close_costs", true)
                .put("no_automatic_margin_top_up", true);
        risk.set("tranche_risk_fractions", JsonHashes.mapper().valueToTree(List.of(0.01, 0.015, 0.025)));
        risk.set("leverage_by_stage", JsonHashes.mapper().valueToTree(List.of(2, 2, 3)));
        risk.set("asset_tie_order", JsonHashes.mapper().valueToTree(ASSETS));
        ObjectNode lifecycle = profile.putObject("lifecycle_contract");
        lifecycle.put("position_state", "FLAT_ARMED_INITIAL_SECOND_THIRD_CLOSED_OR_CANCELLED")
                .put("one_entry_mode_supported", true).put("three_stage_mode_supported", true)
                .put("common_position_stop_and_exit", true).put("first_fill_clock_never_resets", true)
                .put("funding_once_per_actual_settlement_on_open_quantity", true)
                .put("ambiguous_1m_stop_target_order", "STOP_FIRST")
                .put("liquidation_priority", "BEFORE_STOP_OR_TARGET")
                .put("deadline_exit", "ORDER_AT_60_DAYS_RETAIN_EXPOSURE_THROUGH_OUTAGE_UNTIL_FIRST_PERMITTED_FILL")
                .put("macro_scope", "ADDITIONS_ONLY_STAGE2_NEUTRAL_OR_SUPPORTIVE_STAGE3_SUPPORTIVE")
                .put("reversal_additions_require_cost_adjusted_minimum_1R_to_unchanged_target", true)
                .put("reversal_addition_blocked_after_target_reached", true)
                .put("funding_shortfall_current_rule", "AVAILABLE_BALANCE_FIRST_THEN_POSITION_MARGIN")
                .put("funding_shortfall_historical_qualification", "UNKNOWN_PROXY_ONLY");
        ObjectNode deadlineExit = lifecycle.putObject("deadline_exit_execution");
        deadlineExit.put("order_type", "MARKET")
                .put("order_due_at", "FIRST_FILL_PLUS_60_DAYS")
                .put("fill_rule", "FIRST_PRESENT_ELIGIBLE_1M_OPEN_STRICTLY_AFTER_DEADLINE")
                .put("eligible_open_latency_count", 1)
                .put("missing_open_rule", "RETAIN_EXPOSURE_RECORD_OVERRUN_UNTIL_FIRST_PRESENT_OPEN");
        ObjectNode fold = profile.putObject("fold_contract");
        fold.put("schema", "liquidation-v2-chronological-fold-policy/1")
                .put("outer_and_inner_purge_days", 67).put("embargo_days", 7)
                .put("actual_overlap_removal", true).put("legacy_default_changed", false)
                .put("legacy_30_day_and_eight_asset_contracts_unchanged", true);
        ArrayNode variants = profile.putArray("core_variants");
        variants.add("ROUTED_REVERSAL_CONTINUATION");
        variants.add("ALWAYS_CONTINUATION_CONTROL");
        variants.add("ALWAYS_REVERSAL_CONTROL");
        variants.add("PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        profile.put("declared_core_variant_count", 4).put("variant_selection", "FROZEN_NO_OPTIMIZATION");
        profile.set("qualification_requirements", qualificationRequirements());
        profile.put("executor_capability", "liquidation-v2-physical-parquet-v1")
                .put("executor_identity_must_bind", "SOURCE_BYTES_POLICY_DATA_MANIFEST_ROLE_PARTITIONS_AND_RUNTIME")
                .put("caller_supplied_fills_or_verified_flags_authoritative", false);
        profile.put("content_sha256", JsonHashes.ownHash(profile));
        return profile;
    }

    public static boolean validate(ObjectNode profile) {
        if (!SCHEMA.equals(profile.path("schema").asText()) || profile.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(profile).equals(profile.path("content_sha256").asText())) {
            throw failure("unsupported or modified v002 profile contract");
        }
        ObjectNode frozen = frozenContract();
        if (!JsonHashes.canonicalSha256(frozen).equals(JsonHashes.canonicalSha256(profile))) {
            throw failure("v002 profile differs from the frozen capability contract");
        }
        return true;
    }

    public static ObjectNode assessPhysicalManifest(ObjectNode profile, ObjectNode options) {
        validate(profile);
        ObjectNode manifest = object(options, "manifest");
        ObjectNode verification = manifest.path("artifacts").path("feature").path("partitions").isArray()
                ? LiquidationV2PhysicalDataV1.verifyDevelopment(options)
                : LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(options);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-physical-manifest-assessment/1")
                .put("status", "REOPENED_DEVELOPMENT_ONLY")
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("data_manifest_sha256", verification.path("manifest_sha256").asText())
                .put("data_root_sha256", verification.path("dataset_root_sha256").asText())
                .put("format", "PARQUET").put("role_count", verification.path("role_count").asInt())
                .put("duration_days", 60).put("authoritative_evaluation_permitted", false);
        result.set("physical_verification", verification);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ArrayNode qualificationRequirements() {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (String id : List.of("daily_liquidations", "open_interest_base", "price_4h", "price_1h",
                "trades_marks_1m", "funding_settlements", "contract_execution_metadata")) {
            result.addObject().put("series_id", id).put("required_for_execution", true)
                    .put("required_for_authoritative_evidence", true);
        }
        result.addObject().put("series_id", "sp500_context").put("required_for_execution", false)
                .put("required_for_authoritative_evidence", true).put("required_when_macro_stage_tested", true);
        return result;
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw failure(field + " must be an object");
        return (ObjectNode) value;
    }

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}
