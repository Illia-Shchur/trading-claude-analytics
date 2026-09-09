package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PositionCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-08-28T12:00:00Z").toEpochMilli();

    @TempDir
    Path temporary;

    @Test
    void newestDatedSnapshotIsSelectedAndFillsAreBounded() throws Exception {
        Files.writeString(temporary.resolve("position-snapshot-2026-08-27_00-00-00Z.json"), snapshot("0.5"));
        Files.writeString(temporary.resolve("position-snapshot-2026-08-28_00-00-00-001Z.json"), snapshot("1.5"));

        Invocation invocation = execute("btc", "--file", temporary.toString(), "--fills", "1");
        assertThat(invocation.exitCode()).isZero();
        JsonNode result = JSON.readTree(invocation.stdout());
        assertThat(result.path("position").path("qty").asText()).isEqualTo("1.5");
        assertThat(result.path("fills").path("fills")).hasSize(1);
        assertThat(result.path("file").asText()).endsWith("position-snapshot-2026-08-28_00-00-00-001Z.json");
    }

    @Test
    void notCoveredAndMissingHaveDistinctExitContracts() throws Exception {
        Path snapshot = temporary.resolve("snapshot.json");
        Files.writeString(snapshot, snapshot("1"));

        Invocation uncovered = execute("silver", "--file", snapshot.toString());
        assertThat(uncovered.exitCode()).isEqualTo(2);
        assertThat(JSON.readTree(uncovered.stdout()).path("reason").asText()).isEqualTo("not_tracked");

        Invocation missing = execute("btc", "--file", temporary.resolve("absent.json").toString());
        assertThat(missing.exitCode()).isOne();
        JsonNode failure = JSON.readTree(missing.stdout());
        assertThat(failure.path("band").asText()).isEqualTo("EXPIRED");
        assertThat(failure.path("error").asText()).isEqualTo("snapshot file not found");
    }

    @Test
    void allReturnsLedgerBlocksWithoutAssetProjection() throws Exception {
        Path snapshot = temporary.resolve("snapshot.json");
        Files.writeString(snapshot, snapshot("1"));
        Invocation invocation = execute("all", "--file", snapshot.toString());
        assertThat(invocation.exitCode()).isZero();
        JsonNode result = JSON.readTree(invocation.stdout());
        assertThat(result.path("positions")).hasSize(1);
        assertThat(result.path("deals").path("open_count").asInt()).isOne();
        assertThat(result.has("asset")).isFalse();
    }

    private Invocation execute(String... arguments) {
        PositionCommand command = new PositionCommand(temporary, temporary, null, JSON, () -> NOW);
        CommandLine line = new CommandLine(command);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        int exitCode = line.execute(arguments);
        return new Invocation(exitCode, out.toString(), err.toString());
    }

    private static String snapshot(String quantity) {
        return """
                {
                  "schema":"position-snapshot/1",
                  "generated_at":"2026-08-28T11:59:00Z",
                  "source":{"holdings_as_of":"2026-08-28T11:59:00Z"},
                  "portfolio":{},"dry_powder":{},
                  "positions":[{"asset":"BTC","qty":"%s","basis_reliable":true,
                    "qty_reconciliation_status":"RECONCILED","short_qty":null}],
                  "futures":{"open_positions":[],"funding_by_symbol":[],"funding_by_asset":[]},
                  "trades":{"by_asset":[{"asset":"BTC","fill_count_total":2,
                    "fills":[{"price":"2"},{"price":"1"}]}]},
                  "deals":{"open_count":1,"closed_count":0,"open":[{"asset":"BTC","tag":"FK-P1A"}],"closed":[]},
                  "performance":{"overall":{},"by_tag_prefix":[],"by_tag":[]},
                  "coverage":{"assets_not_tracked":["SILVER"]}
                }
                """.formatted(quantity);
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }
}
