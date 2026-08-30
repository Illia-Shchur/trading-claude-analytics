package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Injectable boundary shared by the live fetch and snapshot CLI adapters. */
public interface MarketFetchOperations {
    ObjectNode fetchAsset(String asset, boolean includeSeries);

    ObjectNode fetchMacro();
}
