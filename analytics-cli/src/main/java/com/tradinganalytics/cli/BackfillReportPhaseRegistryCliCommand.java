package com.tradinganalytics.cli;

import com.tradinganalytics.reporting.ReportingCliFacade;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Component
@Command(name = "backfill-report-phase-registry", description = "Backfill canonical report phase registries")
public class BackfillReportPhaseRegistryCliCommand implements Callable<Integer> {
    @Option(names = "--check") private boolean check;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        return ReportingResultEmitter.emit(
                ReportingCliFacade.backfillReportPhaseRegistry(check ? List.of("--check") : List.of()), spec);
    }
}
