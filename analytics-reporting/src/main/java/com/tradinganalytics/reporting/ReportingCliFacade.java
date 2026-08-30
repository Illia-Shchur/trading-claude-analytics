package com.tradinganalytics.reporting;

import java.nio.file.Path;
import java.util.List;

/** Public bridge used by the Spring CLI while retaining the standalone command contracts. */
public final class ReportingCliFacade {
    private ReportingCliFacade() {}

    public static ReportingCommandResult finalizeReport(List<String> arguments) {
        Path cwd = currentDirectory();
        return FinalizeReportCommand.run(arguments, cwd, ReportingFiles.repositoryRoot(cwd));
    }

    public static ReportingCommandResult renderReport(List<String> arguments) {
        Path cwd = currentDirectory();
        return RenderReportCommand.run(arguments, cwd, ReportingFiles.repositoryRoot(cwd));
    }

    public static ReportingCommandResult lintReport(List<String> arguments) {
        Path cwd = currentDirectory();
        return LintReportCommand.run(arguments, cwd, ReportingFiles.repositoryRoot(cwd));
    }

    public static ReportingCommandResult exportSignals(List<String> arguments) {
        Path cwd = currentDirectory();
        return ExportSignalsCommand.run(arguments, ReportingFiles.repositoryRoot(cwd));
    }

    public static ReportingCommandResult backfillReportPhaseRegistry(List<String> arguments) {
        Path cwd = currentDirectory();
        return BackfillReportPhaseRegistryCommand.run(arguments, ReportingFiles.repositoryRoot(cwd));
    }

    private static Path currentDirectory() {
        return Path.of("").toAbsolutePath().normalize();
    }
}
