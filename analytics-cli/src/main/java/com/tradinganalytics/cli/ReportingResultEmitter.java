package com.tradinganalytics.cli;

import com.tradinganalytics.reporting.ReportingCommandResult;
import picocli.CommandLine.Model.CommandSpec;

final class ReportingResultEmitter {
    private ReportingResultEmitter() {}

    static int emit(ReportingCommandResult result, CommandSpec spec) {
        spec.commandLine().getOut().print(result.stdout());
        spec.commandLine().getErr().print(result.stderr());
        return result.exitCode();
    }
}
