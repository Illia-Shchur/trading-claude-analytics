package com.tradinganalytics.cli;

import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Prints the compiled executable and input identity without mutating state. */
@Component
@Command(name = "build-identity", aliases = {"version"},
        description = "Print the actual executable, build-input and runtime identity")
public final class BuildIdentityCommand implements Callable<Integer> {
    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getOut().println(NodePrettyJson.write(BuildIdentity.describe(BuildIdentityCommand.class)));
        return 0;
    }
}
