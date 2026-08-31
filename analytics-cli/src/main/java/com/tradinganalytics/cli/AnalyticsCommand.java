package com.tradinganalytics.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Component
@Command(
        name = "analytics",
        description = "Deterministic trading analytics toolchain",
        mixinStandardHelpOptions = true,
        versionProvider = AnalyticsVersionProvider.class,
        subcommands = {CalibrationRegistryCommand.class, PositionCommand.class, ComputeCliCommand.class,
                FetchCommand.class, SnapshotCommand.class, TripwireCommand.class,
                PublicDataSmokeCommand.class, FinalizeReportCliCommand.class,
                RenderReportCliCommand.class, LintReportCliCommand.class,
                ExportSignalsCliCommand.class, BackfillReportPhaseRegistryCliCommand.class,
                ResearchSmokeCliCommand.class, StrategyAttestationCliCommand.class,
                CiConfirmationCliCommand.class, CiBurnTagCliCommand.class,
                WriterInstallationCliCommand.class, GitHubAttestationCliCommand.class,
                GitHubSettingsCaptureCliCommand.class,
                WorkflowSecurityCliCommand.class,
                StrategyV5ProspectiveWorkflowCliCommand.class,
                PortedToolCliCommands.CalibrationCorpusCli.class,
                PortedToolCliCommands.CalibrationRunCli.class,
                PortedToolCliCommands.PublicDataAdaptersCli.class,
                PortedToolCliCommands.ResearchDataCli.class,
                PortedToolCliCommands.LegacyResearchMigrationV3Cli.class,
                PortedToolCliCommands.LegacyStrategyResearchCli.class,
                PortedToolCliCommands.LegacyStrategyResearchNextCli.class,
                PortedToolCliCommands.StrategyProspectiveRunnerCli.class,
                StrategyResearchV5CliCommand.class,
                StrategyPerformanceBenchmarkCliCommand.class,
                PortedToolCliCommands.SwingCandidatesCli.class,
                PortedToolCliCommands.SwingCalibrationCli.class,
                PortedToolCliCommands.SwingCalibrationLintCli.class,
                PortedToolCliCommands.SwingCrossValidateCli.class,
                PortedToolCliCommands.SwingEngineCli.class,
                PortedToolCliCommands.SwingStrategyCrossValidateCli.class}
)
public class AnalyticsCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
