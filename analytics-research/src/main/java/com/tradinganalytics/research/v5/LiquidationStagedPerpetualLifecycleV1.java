package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** One-position hand-fixture adapter; all arithmetic is performed by the shared account engine. */
public final class LiquidationStagedPerpetualLifecycleV1 {
    private LiquidationStagedPerpetualLifecycleV1() {}

    /** Test-only boundary; public replay inputs are assembled from reopened physical rows and router events. */
    static ObjectNode accountSyntheticFixture(ObjectNode request) {
        return LiquidationPortfolioAccountingV1.singlePositionFixtureResult(request);
    }
}
