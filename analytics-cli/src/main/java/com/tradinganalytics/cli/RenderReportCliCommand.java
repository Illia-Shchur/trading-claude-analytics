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
@Command(name = "render-report", description = "Render a canonical report as full or summary Markdown")
public class RenderReportCliCommand implements Callable<Integer> {
    @Parameters(arity = "0..1", paramLabel = "<report.json>") private String report;
    @Option(names = "--mode", defaultValue = "full") private String mode;
    @Option(names = "--out", paramLabel = "<report.md>") private String output;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        List<String> arguments = new ArrayList<>();
        if (report == null) {
            return ReportingResultEmitter.emit(ReportingCliFacade.renderReport(arguments), spec);
        }
        arguments.add(report);
        arguments.addAll(List.of("--mode", mode));
        if (output != null) { arguments.add("--out"); arguments.add(output); }
        return ReportingResultEmitter.emit(ReportingCliFacade.renderReport(arguments), spec);
    }
}
