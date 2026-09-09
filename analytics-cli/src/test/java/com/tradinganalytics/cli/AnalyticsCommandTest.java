package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AnalyticsCommandTest {
    @Test
    void bareCommandPrintsUsageAndSucceeds() {
        var output = new StringWriter();
        var commandLine = new CommandLine(new AnalyticsCommand());
        commandLine.setOut(new PrintWriter(output));

        assertThat(commandLine.execute()).isZero();
        assertThat(output.toString()).contains("Deterministic trading analytics toolchain");
    }

    @Test
    void versionIsAvailableWithoutStartingSpring() {
        var output = new StringWriter();
        var commandLine = new CommandLine(new AnalyticsCommand());
        commandLine.setOut(new PrintWriter(output));

        assertThat(commandLine.execute("--version")).isZero();
        assertThat(output.toString()).startsWith("trading-analytics ");
    }
}
