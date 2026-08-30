package com.tradinganalytics.cli;

import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.research.legacy.ResearchSmokeV3;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Spring application entry point replacing {@code tools/research-smoke.mjs}. */
@Component
@Command(name = "research-smoke", description = "Run the hermetic v3 research contract smoke test")
public class ResearchSmokeCliCommand implements Callable<Integer> {
    @Spec private CommandSpec spec;

    private final Path repositoryRoot;
    private final Clock clock;

    public ResearchSmokeCliCommand() {
        this(ResearchSmokeV3.repositoryRoot(Path.of("")), Clock.systemUTC());
    }

    ResearchSmokeCliCommand(Path repositoryRoot, Clock clock) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public Integer call() {
        try {
            spec.commandLine().getOut().print(NodePrettyJson.write(ResearchSmokeV3.run(repositoryRoot, clock)));
            return 0;
        } catch (Exception exception) {
            spec.commandLine().getErr().println(message(exception));
            return 1;
        }
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
