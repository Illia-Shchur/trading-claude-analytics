package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import com.tradinganalytics.marketdata.LiveMarketFetchService;
import com.tradinganalytics.marketdata.MarketDataEndpoints;
import com.tradinganalytics.marketdata.MarketFetchOperations;
import com.tradinganalytics.marketdata.MarketFetchSupport;
import com.tradinganalytics.marketdata.MarketSnapshotStore;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Content-addressed fetch cache, replacing {@code tools/snapshot.mjs}. */
@Component
@Command(name = "snapshot", description = "Fetch and persist one auditable market-data snapshot")
public class SnapshotCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "asset,asset")
    private String assetsArgument;

    @Option(names = "--macro")
    private boolean includeMacro;

    @Option(names = "--reuse")
    private String reuseId;

    @Option(names = "--out")
    private Path outputDirectory;

    @Spec
    private CommandSpec spec;

    private final MarketFetchOperations fetcher;
    private final MarketSnapshotStore store;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;

    public SnapshotCommand() {
        this(defaultDependencies());
    }

    private SnapshotCommand(Dependencies dependencies) {
        this(dependencies.fetcher(), dependencies.store(), dependencies.json(), System::currentTimeMillis);
    }

    SnapshotCommand(MarketFetchOperations fetcher, MarketSnapshotStore store,
                    ObjectMapper json, java.util.function.LongSupplier clock) {
        this.fetcher = fetcher; this.store = store; this.json = json; this.clock = clock;
    }

    @Override
    public Integer call() {
        try {
            Instant now = Instant.ofEpochMilli(clock.getAsLong());
            if (reuseId != null) {
                emit(store.replay(reuseId, outputDirectory, now));
                return 0;
            }
            List<String> assets = parseAssets(assetsArgument);
            for (String asset : assets) {
                if (!MarketFetchSupport.ASSETS.containsKey(asset)) {
                    return fail("unknown asset \"" + asset + "\" — one of "
                            + String.join(", ", MarketFetchSupport.ASSETS.keySet()));
                }
            }
            if (assets.isEmpty() && !includeMacro) {
                return fail("pass an asset list (btc,eth,gold) and/or --macro");
            }
            ObjectNode snapshot = fetchAll(assets, includeMacro);
            emit(store.create(snapshot, assets, includeMacro, outputDirectory, now));
            return 0;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (message.startsWith("refusing to write outside data/:")) {
                spec.commandLine().getErr().println(message);
                return 1;
            }
            return fail(message);
        }
    }

    private ObjectNode fetchAll(List<String> assets, boolean macro) throws Exception {
        Map<String, Future<ObjectNode>> futures = new LinkedHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String asset : assets) futures.put(asset,
                    executor.submit(() -> fetcher.fetchAsset(asset, false)));
            if (macro) futures.put("macro", executor.submit(fetcher::fetchMacro));
            ObjectNode output = json.createObjectNode();
            for (Map.Entry<String, Future<ObjectNode>> entry : futures.entrySet()) {
                output.set(entry.getKey(), entry.getValue().get());
            }
            return output;
        }
    }

    private static List<String> parseAssets(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> output = new ArrayList<>();
        for (String item : value.split(",", -1)) {
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) output.add(normalized);
        }
        return List.copyOf(output);
    }

    private void emit(ObjectNode value) {
        spec.commandLine().getOut().print(NodePrettyJson.write(value));
    }

    private int fail(String message) {
        spec.commandLine().getErr().println("error: " + message);
        return 1;
    }

    private static Dependencies defaultDependencies() {
        ObjectMapper json = new ObjectMapper();
        Path root = RepositoryLayout.locate();
        String key = System.getenv("COINGLASS_API_KEY");
        MarketFetchOperations fetcher = new LiveMarketFetchService(
                new MarketDataEndpoints(MarketHttpClient.production(), json, System::currentTimeMillis, key),
                json, System::currentTimeMillis, key != null && !key.isBlank());
        return new Dependencies(fetcher, new MarketSnapshotStore(root, json), json);
    }

    private record Dependencies(MarketFetchOperations fetcher, MarketSnapshotStore store, ObjectMapper json) { }
}
