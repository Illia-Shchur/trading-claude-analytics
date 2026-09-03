package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Thin live market-data facade replacing {@code tools/fetch.mjs}. */
public final class LiveMarketFetchService implements MarketFetchOperations {
    private final MarketDataEndpoints endpoints;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;
    private final boolean coinglassConfigured;
    private final AssetSnapshotAssembler assetAssembler;
    private final MacroSnapshotAssembler macroAssembler;

    public LiveMarketFetchService(MarketDataEndpoints endpoints, ObjectMapper json,
                                  java.util.function.LongSupplier clock, boolean coinglassConfigured) {
        this.endpoints = endpoints;
        this.json = json == null ? new ObjectMapper() : json;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.coinglassConfigured = coinglassConfigured;
        this.assetAssembler = new AssetSnapshotAssembler(endpoints, this.json, coinglassConfigured);
        this.macroAssembler = new MacroSnapshotAssembler(this.json);
    }

    @Override
    public ObjectNode fetchAsset(String key, boolean includeSeries) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        MarketFetchSupport.AssetConfig asset = MarketFetchSupport.ASSETS.get(normalized);
        if (asset == null) {
            throw new IllegalArgumentException("unknown asset \"" + normalized + "\"");
        }
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        long now = clock.getAsLong();
        Map<String, MarketFetchExecutor.Task> tasks = AssetFetchPlan.create(
                endpoints, normalized, asset, coinglassConfigured);
        Map<String, JsonNode> fetched = MarketFetchExecutor.run(tasks, errors);
        return assetAssembler.assemble(normalized, asset, includeSeries, fetched, now, errors);
    }

    /** Fetches the asset-agnostic macro backbone used by both frameworks. */
    @Override
    public ObjectNode fetchMacro() {
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        long now = clock.getAsLong();
        Map<String, MarketFetchExecutor.Task> tasks = MacroFetchPlan.create(endpoints);
        Map<String, JsonNode> fetched = MarketFetchExecutor.run(tasks, errors);
        return macroAssembler.assemble(fetched, now, errors);
    }
}
