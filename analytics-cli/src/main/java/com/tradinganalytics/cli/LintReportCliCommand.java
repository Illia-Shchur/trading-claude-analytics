package com.tradinganalytics.cli;

import com.tradinganalytics.reporting.ReportingCliFacade;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Component
@Command(name = "lint-report", description = "Lint a report and its canonical JSON/Markdown pair")
public class LintReportCliCommand implements Callable<Integer> {
    @Parameters(arity = "0..1", paramLabel = "<report.md|report.json>") private String report;
    @Option(names = "--legacy") private boolean legacy;
    @Option(names = "--markdown", paramLabel = "<report.md>") private String markdown;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        List<String> arguments = new ArrayList<>();
        if (report != null) arguments.add(report);
        if (legacy) arguments.add("--legacy");
        if (markdown != null) { arguments.add("--markdown"); arguments.add(markdown); }
        return ReportingResultEmitter.emit(ReportingCliFacade.lintReport(arguments), spec);
    }
}
