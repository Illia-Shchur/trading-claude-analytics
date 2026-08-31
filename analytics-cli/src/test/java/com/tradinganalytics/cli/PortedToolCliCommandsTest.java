package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.spring.PicocliSpringFactory;

class PortedToolCliCommandsTest {
    private static final Set<String> REGISTERED_COMMANDS = Set.of(
            "backfill-report-phase-registry", "calib-corpus", "calib-registry", "calib-run",
            "capture-github-settings", "ci-burn-tag", "ci-confirmation", "compute",
            "export-signals", "fetch", "finalize-report", "lint-report",
            "lint-swing-calibration", "migrate-research-v3", "position", "public-data-adapters",
            "render-report", "research-data", "research-smoke", "sign-github-attestation",
            "snapshot", "strategy-attestation", "strategy-prospective-runner",
            "strategy-public-smoke", "strategy-research", "strategy-research-next",
            "strategy-research-v5",
            "strategy-research-v5-performance-benchmark", "strategy-v5-workflow-security",
            "strategy-v5-prospective-workflow",
            "swing-calibrate", "swing-candidates", "swing-cross-validate", "swing-engine",
            "swing-strategy-cross-validate", "tripwire", "verify-evidence-writer-installation");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rootRegistersEveryPreviouslyUnreachablePortedCommand() {
        CommandLine commandLine = new CommandLine(new AnalyticsCommand());

        assertThat(commandLine.getSubcommands().keySet())
                .containsExactlyInAnyOrderElementsOf(REGISTERED_COMMANDS);
    }

    @Test
    void springDiscoversAndConstructsEveryRegisteredPassthroughCommand() {
        try (var context = new AnnotationConfigApplicationContext(TradingAnalyticsConfiguration.class)) {
            CommandLine commandLine = new CommandLine(
                    context.getBean(AnalyticsCommand.class), new PicocliSpringFactory(context));

            assertThat(commandLine.getSubcommands().keySet())
                    .containsExactlyInAnyOrderElementsOf(REGISTERED_COMMANDS);
        }
    }

    @Test
    void passthroughRetainsUnknownOptionsValuesAndOrder() {
        AtomicReference<List<String>> observed = new AtomicReference<>();
        CommandLine commandLine = new CommandLine(new RecordingAdapter(observed));

        assertThat(commandLine.execute("run", "--cache", "fixture.json", "--flag", "tail")).isZero();
        assertThat(observed.get()).containsExactly("run", "--cache", "fixture.json", "--flag", "tail");
    }

    @Test
    void calibrationCorpusReceivesLegacyFlagsThroughPicocli() {
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(
                new PortedToolCliCommands.CalibrationCorpusCli(temporaryDirectory));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("--since", "2020-01-01")).isOne();
        assertThat(error.toString()).contains("reports dir not found").doesNotContain("usage:");
    }

    @Test
    void streamAdaptersUsePicocliStreamsAndDoNotWriteDirectlyToSystemStreams() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new PortedToolCliCommands.ResearchDataCli());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(output.toString()).contains("usage: research-data.mjs");
        assertThat(error.toString()).isEmpty();
    }

    @Test
    void frozenSwingCandidatesAreReachableFromSpringCommand() {
        StringWriter output = new StringWriter();
        CommandLine commandLine = new CommandLine(new PortedToolCliCommands.SwingCandidatesCli());
        commandLine.setOut(new PrintWriter(output, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(output.toString()).contains("\"schema\": \"swing-candidates/1\"")
                .contains("\"candidates\"");
    }

    @Test
    void legacyResearchUsageIsRoutedThroughPicocliStreams() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new PortedToolCliCommands.LegacyStrategyResearchCli());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(output.toString()).startsWith("usage: strategy-research.mjs");
        assertThat(error.toString()).isEmpty();
    }

    @Test
    void nextResearchUsageAndDelegationGapsAreRoutedThroughPicocliStreams() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(
                new PortedToolCliCommands.LegacyStrategyResearchNextCli());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(output.toString()).startsWith("usage: strategy-research-next.mjs");
        assertThat(error.toString()).isEmpty();

        output.getBuffer().setLength(0);
        error.getBuffer().setLength(0);
        assertThat(commandLine.execute("data-backfill")).isOne();
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString()).contains("authoritative v5 command is not part of")
                .doesNotContain("Exception");
    }

    @Test
    void prospectiveRunnerUsageAndErrorsUsePicocliStreams() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new PortedToolCliCommands.StrategyProspectiveRunnerCli());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute()).isOne();
        assertThat(output).hasToString("");
        assertThat(error).hasToString("usage: strategy-prospective-runner.mjs preflight|append|eligibility --ledger <path>\n");
    }

    @Test
    void strategyResearchV5UsageUsesPicocliStreams() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new StrategyResearchV5CliCommand());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(output).hasToString(
                "usage: strategy-research-v5.mjs data-backfill|data-raw-replay|feature-build|"
                        + "metadata-build|opportunity-envelope|artifact-build|research-init|"
                        + "experiment-freeze|search-genetic|research-run|overfit-audit|"
                        + "prospective-runner|readiness-audit|deployment-audit|validate|index [options]\n");
        assertThat(error).hasToString("");
    }

    @Test
    void performanceBenchmarkErrorsUsePicocliStreams() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new StrategyPerformanceBenchmarkCliCommand());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("--full")).isOne();
        assertThat(output).hasToString("");
        assertThat(error).hasToString(
                "--full requires frozen plan, acquisition manifest, and Parquet manifest inputs\n");
    }

    @Test
    void legacyMigrationWritesOnlyItsExplicitDestination() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("legacy-root"));
        Path outputPath = temporaryDirectory.resolve("migration.json");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(
                new PortedToolCliCommands.LegacyResearchMigrationV3Cli());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute(root.toString(), outputPath.toString())).isZero();
        assertThat(Files.readString(outputPath)).contains("strategy-research-v3-migration/1");
        assertThat(output.toString()).contains(outputPath.toString()).contains("\"runs\": 0");
        assertThat(error.toString()).isEmpty();
    }

    @Command(name = "recording")
    static final class RecordingAdapter extends PortedToolCliCommands.StreamAdapter {
        private final AtomicReference<List<String>> observed;

        RecordingAdapter(AtomicReference<List<String>> observed) {
            this.observed = observed;
        }

        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            observed.set(List.of(arguments));
            return 0;
        }
    }
}
