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
@Command(name = "finalize-report", description = "Validate and atomically finalize a canonical report JSON")
public class FinalizeReportCliCommand implements Callable<Integer> {
    @Parameters(arity = "0..1", paramLabel = "<draft.json>") private String draft;
    @Option(names = "--out", paramLabel = "<report.json>") private String output;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        List<String> arguments = new ArrayList<>();
        if (draft != null) arguments.add(draft);
        if (output != null) { arguments.add("--out"); arguments.add(output); }
        return ReportingResultEmitter.emit(ReportingCliFacade.finalizeReport(arguments), spec);
    }
}
