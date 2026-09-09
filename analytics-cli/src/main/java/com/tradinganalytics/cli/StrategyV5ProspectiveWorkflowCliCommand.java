package com.tradinganalytics.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/** Picocli adapter for the fail-closed v5 prospective workflow engine. */
@Component
@Command(name = "strategy-v5-prospective-workflow",
        description = "Run fail-closed v5 prospective workflow custody operations")
public final class StrategyV5ProspectiveWorkflowCliCommand implements Callable<Integer> {
    public static final String USAGE = StrategyV5WorkflowEngine.USAGE;

    @Unmatched
    private List<String> arguments = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    private final Path workingDirectory;

    public StrategyV5ProspectiveWorkflowCliCommand() {
        this(Path.of(""));
    }

    StrategyV5ProspectiveWorkflowCliCommand(Path workingDirectory) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    @Override
    public Integer call() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            int status;
            try (PrintStream stdout = new PrintStream(output, true, StandardCharsets.UTF_8);
                 PrintStream stderr = new PrintStream(error, true, StandardCharsets.UTF_8)) {
                status = run(arguments.toArray(String[]::new), stdout, stderr,
                        System.getenv(), workingDirectory);
            }
            spec.commandLine().getOut().print(output.toString(StandardCharsets.UTF_8));
            spec.commandLine().getErr().print(error.toString(StandardCharsets.UTF_8));
            return status;
        } catch (RuntimeException error) {
            spec.commandLine().getErr().println(CliMessages.rootCauseMessage(error));
            return 1;
        }
    }

    /** Process-boundary entry point used by the Spring command and focused tests. */
    public static int run(String[] args, PrintStream stdout, PrintStream stderr,
                          Map<String, String> environment, Path workingDirectory) {
        return StrategyV5WorkflowEngine.run(args, stdout, stderr, environment, workingDirectory);
    }
}
