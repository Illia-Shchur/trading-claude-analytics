package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class GitHubAttestationCliCommandTest {
    @Test
    void rootRegistersSigner() {
        assertThat(new CommandLine(new AnalyticsCommand()).getSubcommands())
                .containsKey("sign-github-attestation");
    }

    @Test
    void missingProtectedInputsFailWithoutStackTraceOrSecretEcho() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(
                new GitHubAttestationCliCommand(() -> Map.of("UNRELATED_SECRET", "never-print-me")));
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute()).isOne();
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString()).contains("attestation key registry is required")
                .doesNotContain("never-print-me").doesNotContain("Exception");
    }
}
