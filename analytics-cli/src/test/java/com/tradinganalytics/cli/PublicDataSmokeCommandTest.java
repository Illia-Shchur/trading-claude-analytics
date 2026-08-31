package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.PublicDataSmokeService;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class PublicDataSmokeCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hermeticModePrintsRoutingProofAndSucceeds() throws Exception {
        Invocation result = execute(service((uri, headers) -> {
            throw new AssertionError("network must not be called");
        }));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(JSON.readTree(result.stdout()).path("pass").asBoolean()).isTrue();
        assertThat(JSON.readTree(result.stdout()).path("results")).hasSize(8);
    }

    @Test
    void networkModeReturnsOneWhenAnyRouteIsUnavailable() throws Exception {
        Invocation result = execute(service((uri, headers) -> new PublicDataAdapters.FetchResponse(
                503, "{}".getBytes(StandardCharsets.UTF_8), Map.of())), "--network");

        assertThat(result.exitCode()).isOne();
        assertThat(result.stderr()).isEmpty();
        assertThat(JSON.readTree(result.stdout()).path("pass").asBoolean()).isFalse();
    }

    private static PublicDataSmokeService service(PublicDataAdapters.InjectableHttpClient getter) {
        return new PublicDataSmokeService(
                new MarketHttpClient(getter, null, millis -> { }, JSON), JSON, () -> 1_750_000_000_000L);
    }

    private static Invocation execute(PublicDataSmokeService service, String... arguments) {
        CommandLine line = new CommandLine(new PublicDataSmokeCommand(service));
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return new Invocation(line.execute(arguments), out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
