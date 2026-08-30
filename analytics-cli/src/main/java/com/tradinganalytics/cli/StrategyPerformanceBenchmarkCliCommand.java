package com.tradinganalytics.cli;

import com.tradinganalytics.research.v5.StrategyPerformanceV5Benchmark;
import java.io.PrintStream;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** Spring/Picocli adapter for {@code strategy-research-v5-performance-benchmark.mjs}. */
@Component
@Command(name = "strategy-research-v5-performance-benchmark",
        description = "Run the deterministic v5 performance/data-plane benchmark")
public final class StrategyPerformanceBenchmarkCliCommand extends PortedToolCliCommands.StreamAdapter {
    @Override
    protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
        return StrategyPerformanceV5Benchmark.run(arguments, stdout, stderr);
    }
}
