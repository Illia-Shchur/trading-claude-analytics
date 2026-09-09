package com.tradinganalytics.cli;

import com.tradinganalytics.infrastructure.github.GitHubAttestationSignerV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Spring entry point for the protected v5 GitHub attestation signer. */
@Component
@Command(name = "sign-github-attestation",
        description = "Sign one run-scoped prospective SHADOW receipt")
public final class GitHubAttestationCliCommand implements Callable<Integer> {
    private final Supplier<Map<String, String>> environment;

    @Spec
    private CommandSpec spec;

    public GitHubAttestationCliCommand() {
        this(() -> System.getenv());
    }

    GitHubAttestationCliCommand(Supplier<Map<String, String>> environment) {
        this.environment = environment;
    }

    @Override
    public Integer call() {
        try {
            GitHubAttestationSignerV5.Result result = GitHubAttestationSignerV5.sign(environment.get());
            spec.commandLine().getOut().println(JsonHashes.mapper().writeValueAsString(result.summary()));
            return 0;
        } catch (Exception error) {
            spec.commandLine().getErr().println(rootMessage(error));
            return 1;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
