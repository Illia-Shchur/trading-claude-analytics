package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradinganalytics.cli.TripwireCommand;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class TripwireCommandParityTest {
    @Test
    void fileSelectionMetadataAndTripwireOutputMatchCapturedNodeOracle() throws Exception {
        Path repository = RepositoryRoot.find();
        Path temporary = Files.createTempDirectory(repository.resolve("data"), "java-tripwire-parity-");
        try {
            writeRun(temporary, "20260828-1000-aaaaaaaa", "old", 49, 6, 80);
            writeRun(temporary, "20260828-1100-bbbbbbbb", "new", 61, 7, 87);
            String checkpoints = "{\"btc\":{\"line\":90}}";
            String expected;
            try (InputStream input = getClass().getResourceAsStream("/oracles/tripwire-v1.json")) {
                assertThat(input).as("frozen tripwire oracle").isNotNull();
                expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }

            StringWriter stdout = new StringWriter();
            StringWriter stderr = new StringWriter();
            CommandLine line = new CommandLine(new TripwireCommand(repository,
                    new com.fasterxml.jackson.databind.ObjectMapper()))
                    .setOut(new PrintWriter(stdout, true)).setErr(new PrintWriter(stderr, true));
            int status = line.execute("--dir", temporary.toString(), "--checkpoints", checkpoints);

            assertThat(status).isZero();
            assertThat(stderr.toString()).isEmpty();
            assertThat(stdout.toString()).isEqualTo(expected);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void writeRun(Path root, String directory, String runId, int sentiment,
                                 int streak, int spot) throws Exception {
        Path target = root.resolve(directory);
        Files.createDirectories(target);
        Files.writeString(target.resolve("snapshot.json"), """
                {"run_id":"%s","fetched_at":"2026-08-28T10:00:00.000Z","snapshot":{"btc":{
                  "spot":{"canonical":%d},"sentiment":{"avg_3d":%d,"streaks_daily_prints":{"le15":%d}},
                  "daily":{"adr5":{"adr":4}}
                }}}
                """.formatted(runId, spot, sentiment, streak));
    }
}
