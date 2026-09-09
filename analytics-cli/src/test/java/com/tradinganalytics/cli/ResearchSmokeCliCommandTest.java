package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.research.legacy.ResearchSmokeV3;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ResearchSmokeCliCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void springCommandEmitsTheSmokeResult() throws Exception {
        Path root = ResearchSmokeV3.repositoryRoot(Path.of(""));
        ResearchSmokeCliCommand command = new ResearchSmokeCliCommand(root,
                Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC));
        CommandLine line = new CommandLine(command);
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));

        assertThat(line.execute()).isZero();
        assertThat(err.toString()).isEmpty();
        JsonNode value = JSON.readTree(out.toString());
        assertThat(value.path("ok").asBoolean()).isTrue();
        assertThat(value.path("zero_trade").path("decision").asText()).isEqualTo("REJECTED");
    }
}
