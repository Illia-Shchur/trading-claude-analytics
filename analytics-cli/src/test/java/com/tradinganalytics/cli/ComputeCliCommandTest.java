package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradinganalytics.core.compute.ComputeCommand;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ComputeCliCommandTest {
    @Test
    void forwardsModeDependentFlagsAndValuesInOriginalOrder() {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        CommandLine line = new CommandLine(new ComputeCliCommand(new ComputeCommand()))
                .setOut(new PrintWriter(stdout, true))
                .setErr(new PrintWriter(stderr, true));

        int exit = line.execute("round", "12.5", "--asset", "btc");

        assertThat(exit).isZero();
        assertThat(stderr.toString()).isEmpty();
        assertThat(stdout.toString()).isEqualTo("""
                {
                  "raw": 12.5,
                  "convention": "half-up",
                  "adjusted": 13
                }
                """);
    }

    @Test
    void preservesComputeFailureExitAndDiagnostic() {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        CommandLine line = new CommandLine(new ComputeCliCommand(new ComputeCommand()))
                .setOut(new PrintWriter(stdout, true))
                .setErr(new PrintWriter(stderr, true));

        int exit = line.execute("thresholds", "0");

        assertThat(exit).isEqualTo(1);
        assertThat(stdout.toString()).isEmpty();
        assertThat(stderr.toString()).contains("active denominator must be an integer 1–9");
    }
}
