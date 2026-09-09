package com.tradinganalytics.cli;

import com.tradinganalytics.research.v5.StrategyResearchV5CommandAdapter;
import java.io.PrintStream;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** Spring/Picocli process boundary for the strategy-research-v5 facade. */
@Component
@Command(name = "strategy-research-v5",
        description = "Run the deterministic v5 strategy-research command surface")
public final class StrategyResearchV5CliCommand extends PortedToolCliCommands.StreamAdapter {
    @Override
    protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
        return StrategyResearchV5CommandAdapter.run(arguments, stdout, stderr);
    }
}
