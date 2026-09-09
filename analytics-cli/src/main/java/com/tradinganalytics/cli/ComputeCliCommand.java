package com.tradinganalytics.cli;

import com.tradinganalytics.core.compute.ComputeCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;
import picocli.CommandLine.Model.CommandSpec;

/** Picocli boundary for the exact legacy {@code tools/compute.mjs} grammar. */
@Component
@Command(
        name = "compute",
        description = "Run deterministic scoring and market calculations")
public class ComputeCliCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "MODE")
    private String mode;

    /**
     * Compute has a deliberately mode-dependent flag grammar. Capturing the
     * tail verbatim keeps its mature parser as the single source of truth.
     */
    @Unmatched
    private List<String> tail = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    private final ComputeCommand compute;

    public ComputeCliCommand() {
        this(new ComputeCommand());
    }

    ComputeCliCommand(ComputeCommand compute) {
        this.compute = compute;
    }

    @Override
    public Integer call() {
        List<String> arguments = new ArrayList<>();
        if (mode != null) arguments.add(mode);
        arguments.addAll(tail);
        ComputeCommand.Result result = compute.execute(arguments.toArray(String[]::new));
        spec.commandLine().getOut().print(result.stdout());
        spec.commandLine().getErr().print(result.stderr());
        return result.exitCode();
    }
}
