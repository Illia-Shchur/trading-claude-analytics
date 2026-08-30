package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.MarketFetchOperations;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class FetchCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void routesAssetMacroAndSpotModes() throws Exception {
        AtomicBoolean series = new AtomicBoolean();
        MarketFetchOperations fetcher = new MarketFetchOperations() {
            @Override public ObjectNode fetchAsset(String asset, boolean includeSeries) {
                series.set(includeSeries);
                ObjectNode output = JSON.createObjectNode(); output.put("asset", asset);
                output.putObject("spot").put("canonical", 123); return output;
            }
            @Override public ObjectNode fetchMacro() {
                return JSON.createObjectNode().put("scope", "macro");
            }
        };

        Invocation asset = execute(fetcher, "btc", "--series");
        assertThat(asset.exitCode()).isZero();
        assertThat(JSON.readTree(asset.stdout()).path("asset").asText()).isEqualTo("btc");
        assertThat(series).isTrue();

        Invocation spot = execute(fetcher, "spot", "btc");
        assertThat(JSON.readTree(spot.stdout()).path("canonical").asInt()).isEqualTo(123);
        assertThat(series).isFalse();

        Invocation macro = execute(fetcher, "macro");
        assertThat(JSON.readTree(macro.stdout()).path("scope").asText()).isEqualTo("macro");
    }

    @Test
    void invalidTargetUsesJavaLauncherDiagnosticAndExitCode() {
        MarketFetchOperations unused = new MarketFetchOperations() {
            @Override public ObjectNode fetchAsset(String asset, boolean includeSeries) { throw new AssertionError(); }
            @Override public ObjectNode fetchMacro() { throw new AssertionError(); }
        };
        Invocation result = execute(unused, "doge");
        assertThat(result.exitCode()).isOne();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo(
                "usage: ./bin/analytics fetch <btc|eth|sol|gold|spx|ndx|macro|spot <asset>> [--series]\n");
    }

    private static Invocation execute(MarketFetchOperations fetcher, String... arguments) {
        CommandLine line = new CommandLine(new FetchCommand(fetcher));
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true)); line.setErr(new PrintWriter(err, true));
        int code = line.execute(arguments);
        return new Invocation(code, out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
