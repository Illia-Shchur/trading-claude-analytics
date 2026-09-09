package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class TripwireCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void comparesTheTwoLexicallyNewestRunDirectories() throws Exception {
        writeRun("20260828-1000-aaaa", "old", 49, 6);
        writeRun("20260828-1100-bbbb", "middle", 61, 7);
        writeRun("20260828-1200-cccc", "new", 61, 7);

        Invocation invocation = execute();

        assertThat(invocation.exitCode()).isZero();
        assertThat(invocation.stderr()).isEmpty();
        JsonNode output = JSON.readTree(invocation.stdout());
        assertThat(output.path("prev_run_id").asText()).isEqualTo("middle");
        assertThat(output.path("next_run_id").asText()).isEqualTo("new");
        assertThat(output.path("n_crossings").asInt()).isZero();
    }

    @Test
    void emitsHelpfulZeroResultUntilTwoRunsExist() throws Exception {
        writeRun("20260828-1000-aaaa", "only", 49, 6);
        Invocation invocation = execute();
        assertThat(invocation.exitCode()).isZero();
        assertThat(JSON.readTree(invocation.stdout()).path("note").asText())
                .contains("need ≥2 stored snapshots").contains("found 1");
    }

    @Test
    void refusesPathsOutsideRepositoryDataBoundary() {
        Invocation invocation = execute("--dir", temporary.getParent().toString());
        assertThat(invocation.exitCode()).isOne();
        assertThat(invocation.stderr()).contains("refusing to read outside data/");
    }

    @Test
    void acceptsInlineCheckpointJsonAndEmitsCrossing() throws Exception {
        writeRun("20260828-1000-aaaa", "old", 49, 6, 80);
        writeRun("20260828-1100-bbbb", "new", 49, 6, 87);
        Invocation invocation = execute("--checkpoints", "{\"btc\":{\"line\":90}}");
        JsonNode output = JSON.readTree(invocation.stdout());
        assertThat(invocation.exitCode()).isZero();
        assertThat(output.path("crossings").findValuesAsText("type"))
                .contains("checkpoint_adr_distance");
    }

    private void writeRun(String directory, String runId, int sentiment, int streak) throws Exception {
        writeRun(directory, runId, sentiment, streak, 80);
    }

    private void writeRun(String directory, String runId, int sentiment, int streak, int spot) throws Exception {
        Path target = temporary.resolve("data/runs").resolve(directory);
        Files.createDirectories(target);
        Files.writeString(target.resolve("snapshot.json"), """
                {"run_id":"%s","fetched_at":"2026-08-28T10:00:00.000Z","snapshot":{"btc":{
                  "spot":{"canonical":%d},"sentiment":{"avg_3d":%d,"streaks_daily_prints":{"le15":%d}},
                  "daily":{"adr5":{"adr":4}}
                }}}
                """.formatted(runId, spot, sentiment, streak));
    }

    private Invocation execute(String... arguments) {
        CommandLine line = new CommandLine(new TripwireCommand(temporary, JSON));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        int exitCode = line.execute(arguments);
        return new Invocation(exitCode, out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }
}
