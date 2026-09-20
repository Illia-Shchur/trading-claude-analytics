package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;

/**
 * Public command boundary for the additive corrected successor worker.
 *
 * <p>The frozen successor command remains owned by
 * {@link StrategyOperatingCharacteristicsSuccessorV1}.  This boundary makes
 * the accounting version explicit at the command and schema level, so a
 * corrected plan cannot accidentally route through the frozen V5 evaluator.</p>
 */
public final class StrategyOperatingCharacteristicsCorrectedSuccessorV1 {
    public static final String EVALUATOR_ID = StrategyFixedBaselineCorrectedV1.EVALUATOR_ID;
    public static final String ACCOUNTING_VERSION =
            StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION;
    public static final String PLAN_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-successor-plan/1";
    public static final String PREFLIGHT_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-successor-preflight/1";
    public static final String RESULT_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-successor-result/1";

    private StrategyOperatingCharacteristicsCorrectedSuccessorV1() { }

    public static ObjectNode preflight(ObjectNode plan) {
        requireCorrectedPlanBinding(plan);
        return StrategyOperatingCharacteristicsSuccessorV1.correctedPreflight(plan);
    }

    public static ObjectNode run(ObjectNode options) {
        return StrategyOperatingCharacteristicsSuccessorV1.runCorrected(options);
    }

    /** Keep the successor's shared validator from accepting frozen or retagged plans. */
    private static void requireCorrectedPlanBinding(ObjectNode plan) {
        if (plan == null || !PLAN_SCHEMA.equals(plan.path("schema").asText())
                || plan.path("version").asInt(-1) != 1
                || !EVALUATOR_ID.equals(plan.path("binding_fixed_evaluator").asText())
                || !EVALUATOR_ID.equals(plan.path("corrected_evaluator_identity").asText())
                || !ACCOUNTING_VERSION.equals(plan.path("accounting_version").asText())
                || !plan.path("content_sha256").asText().equals(JsonHashes.ownHash(plan))) {
            throw new IllegalArgumentException("corrected successor plan is not self-bound to corrected accounting");
        }
    }
}
