package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.LiveMarketFetchService;
import com.tradinganalytics.marketdata.MarketDataEndpoints;
import com.tradinganalytics.marketdata.MarketFetchOperations;
import com.tradinganalytics.marketdata.MarketFetchSupport;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Network-only market backbone, replacing {@code tools/fetch.mjs}. */
@Component
@Command(name = "fetch", description = "Fetch a live asset or macro market-data backbone")
public class FetchCommand implements Callable<Integer> {
    @Parameters(arity = "0..*", paramLabel = "<asset|macro|spot asset>")
    private List<String> arguments = new ArrayList<>();

    @Option(names = "--series")
    private boolean includeSeries;

    @Spec
    private CommandSpec spec;

    private final MarketFetchOperations fetcher;

    public FetchCommand() {
        ObjectMapper json = new ObjectMapper();
        String key = System.getenv("COINGLASS_API_KEY");
        this.fetcher = new LiveMarketFetchService(
                new MarketDataEndpoints(MarketHttpClient.production(), json, System::currentTimeMillis, key),
                json, System::currentTimeMillis, key != null && !key.isBlank());
    }

    FetchCommand(MarketFetchOperations fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Integer call() {
        String first = arguments.isEmpty() ? "" : arguments.get(0).toLowerCase(Locale.ROOT);
        try {
            ObjectNode result;
            if ("spot".equals(first) && arguments.size() == 2) {
                String asset = arguments.get(1).toLowerCase(Locale.ROOT);
                if (!MarketFetchSupport.ASSETS.containsKey(asset)) return usageFailure();
                result = asObject(fetcher.fetchAsset(asset, false).get("spot"));
            } else if ("macro".equals(first) && arguments.size() == 1) {
                result = fetcher.fetchMacro();
            } else if (arguments.size() == 1 && MarketFetchSupport.ASSETS.containsKey(first)) {
                result = fetcher.fetchAsset(first, includeSeries);
            } else {
                return usageFailure();
            }
            spec.commandLine().getOut().print(NodePrettyJson.write(result));
            return 0;
        } catch (Exception exception) {
            spec.commandLine().getErr().println("error: " + message(exception));
            return 1;
        }
    }

    private int usageFailure() {
        spec.commandLine().getErr().println("usage: ./bin/analytics fetch <"
                + String.join("|", MarketFetchSupport.ASSETS.keySet())
                + "|macro|spot <asset>> [--series]");
        return 1;
    }

    private static ObjectNode asObject(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalStateException("spot block unavailable");
        return (ObjectNode) value;
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
