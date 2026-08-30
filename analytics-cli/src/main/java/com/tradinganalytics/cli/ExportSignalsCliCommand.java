package com.tradinganalytics.cli;

import com.tradinganalytics.reporting.ReportingCliFacade;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Component
@Command(name = "export-signals", description = "Regenerate the deterministic signal-feed projection")
public class ExportSignalsCliCommand implements Callable<Integer> {
    @Option(names = "--dry-run") private boolean dryRun;
    @Option(names = "--strict") private boolean strict;
    @Option(names = "--reports", paramLabel = "<directory>") private String reports;
    @Option(names = "--out", paramLabel = "<signal-feed.json>") private String output;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        List<String> arguments = new ArrayList<>();
        if (dryRun) arguments.add("--dry-run");
        if (strict) arguments.add("--strict");
        if (reports != null) { arguments.add("--reports"); arguments.add(reports); }
        if (output != null) { arguments.add("--out"); arguments.add(output); }
        return ReportingResultEmitter.emit(ReportingCliFacade.exportSignals(arguments), spec);
    }
}
