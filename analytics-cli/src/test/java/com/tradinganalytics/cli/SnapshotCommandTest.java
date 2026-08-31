package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.MarketFetchOperations;
import com.tradinganalytics.marketdata.MarketSnapshotStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class SnapshotCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-08-28T12:34:00Z").toEpochMilli();

    @TempDir
    Path temporary;

    @Test
    void fetchesRequestedInputsWritesRecordAndReplaysIt() throws Exception {
        MarketFetchOperations fetcher = fetcher();
        Invocation created = execute(fetcher, NOW, "btc,eth", "--macro");

        assertThat(created.exitCode()).isZero();
        ObjectNode record = (ObjectNode) JSON.readTree(created.stdout());
        assertThat(record.path("assets").findValuesAsText("")).isEmpty();
        assertThat(record.path("assets").get(0).asText()).isEqualTo("btc");
        assertThat(record.path("snapshot").path("eth").path("asset").asText()).isEqualTo("eth");
        assertThat(record.path("snapshot").path("macro").path("scope").asText()).isEqualTo("macro");
        Path file = temporary.resolve("data/runs").resolve(record.path("run_id").asText()).resolve("snapshot.json");
        assertThat(file).exists();

        Invocation replay = execute(fetcher, NOW + 31 * 60_000L,
                "--reuse", record.path("run_id").asText());
        assertThat(replay.exitCode()).isZero();
        assertThat(JSON.readTree(replay.stdout()).path("age_min").asInt()).isEqualTo(31);
    }

    @Test
    void validatesAssetsEmptyInputAndWriteBoundaryBeforeFetching() {
        Invocation unknown = execute(fetcher(), NOW, "doge");
        assertThat(unknown.exitCode()).isOne();
        assertThat(unknown.stderr()).contains("unknown asset \"doge\"").contains("btc, eth, sol, gold, spx, ndx");

        Invocation empty = execute(fetcher(), NOW);
        assertThat(empty.exitCode()).isOne();
        assertThat(empty.stderr()).contains("pass an asset list");

        Invocation outside = execute(fetcher(), NOW, "btc", "--out", temporary.getParent().toString());
        assertThat(outside.exitCode()).isOne();
        assertThat(outside.stderr()).startsWith("refusing to write outside data/:");
        assertThat(outside.stderr()).doesNotStartWith("error:");
    }

    @Test
    void missingReplayFailsClosed() {
        Invocation result = execute(fetcher(), NOW, "--reuse", "missing");
        assertThat(result.exitCode()).isOne();
        assertThat(result.stderr()).contains("error: no stored snapshot at");
    }

    private MarketFetchOperations fetcher() {
        return new MarketFetchOperations() {
            @Override public ObjectNode fetchAsset(String asset, boolean includeSeries) {
                return JSON.createObjectNode().put("asset", asset).put("fetched_at", "volatile");
            }
            @Override public ObjectNode fetchMacro() {
                return JSON.createObjectNode().put("scope", "macro").put("fetched_at", "volatile");
            }
        };
    }

    private Invocation execute(MarketFetchOperations fetcher, long now, String... arguments) {
        MarketSnapshotStore store = new MarketSnapshotStore(temporary, JSON);
        CommandLine line = new CommandLine(new SnapshotCommand(fetcher, store, JSON, () -> now));
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true)); line.setErr(new PrintWriter(err, true));
        int code = line.execute(arguments);
        return new Invocation(code, out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
