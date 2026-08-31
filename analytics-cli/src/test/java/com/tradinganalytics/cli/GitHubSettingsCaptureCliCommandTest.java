package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class GitHubSettingsCaptureCliCommandTest {
    @TempDir Path temporary;

    @Test
    void rootRegistersCaptureCommand() {
        assertThat(new CommandLine(new AnalyticsCommand()).getSubcommands())
                .containsKey("capture-github-settings");
    }

    @Test
    void verifiedCaptureWritesBothArtifactsWithoutNarratedOutput() throws Exception {
        Path capturePath = temporary.resolve("capture.json");
        Path receiptPath = temporary.resolve("receipt.json");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("V5_SETTINGS_OUT", capturePath.toString());
        env.put("V5_SETTINGS_RECEIPT_OUT", receiptPath.toString());
        ObjectNode capture = JsonHashes.mapper().createObjectNode().put("verified", true);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode().put("verified", true);
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new GitHubSettingsCaptureCliCommand(
                () -> env, ignored -> new GitHubSettingsCaptureV5.Result(capture, receipt)));
        command.setOut(new PrintWriter(output, true));
        command.setErr(new PrintWriter(error, true));

        assertThat(command.execute()).isZero();
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString()).isEmpty();
        assertThat(JsonHashes.parse(Files.readAllBytes(capturePath), "capture").path("verified").asBoolean())
                .isTrue();
        assertThat(JsonHashes.parse(Files.readAllBytes(receiptPath), "receipt").path("verified").asBoolean())
                .isTrue();
    }

    @Test
    void blockedCaptureStillWritesEvidenceAndReturnsOne() {
        Path capturePath = temporary.resolve("blocked-capture.json");
        Path receiptPath = temporary.resolve("blocked-receipt.json");
        Map<String, String> env = Map.of(
                "V5_SETTINGS_OUT", capturePath.toString(),
                "V5_SETTINGS_RECEIPT_OUT", receiptPath.toString());
        ObjectNode capture = JsonHashes.mapper().createObjectNode().put("verified", false);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode().put("verified", false);
        CommandLine command = new CommandLine(new GitHubSettingsCaptureCliCommand(
                () -> env, ignored -> new GitHubSettingsCaptureV5.Result(capture, receipt)));

        assertThat(command.execute()).isOne();
        assertThat(capturePath).exists();
        assertThat(receiptPath).exists();
    }

    @Test
    void configurationFailuresNeverEchoUnrelatedSecretsOrStackTraces() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new GitHubSettingsCaptureCliCommand(
                () -> Map.of("UNRELATED_SECRET", "never-print-me"),
                ignored -> { throw new IllegalArgumentException("GITHUB_REPOSITORY is required"); }));
        command.setOut(new PrintWriter(output, true));
        command.setErr(new PrintWriter(error, true));

        assertThat(command.execute()).isOne();
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString()).contains("GITHUB_REPOSITORY is required")
                .doesNotContain("never-print-me")
                .doesNotContain("Exception");
    }
}
