package com.tradinganalytics.cli;

import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Spring/Picocli entry point for the physical GitHub settings capture. */
@Component
@Command(name = "capture-github-settings",
        description = "Capture and verify GitHub deployment settings evidence")
public final class GitHubSettingsCaptureCliCommand implements Callable<Integer> {
    @FunctionalInterface
    interface CaptureOperation {
        GitHubSettingsCaptureV5.Result capture(Map<String, String> environment);
    }

    private final Supplier<Map<String, String>> environment;
    private final CaptureOperation operation;

    @Spec
    private CommandSpec spec;

    public GitHubSettingsCaptureCliCommand() {
        this(System::getenv, GitHubSettingsCaptureCliCommand::captureProduction);
    }

    GitHubSettingsCaptureCliCommand(
            Supplier<Map<String, String>> environment,
            CaptureOperation operation) {
        this.environment = environment;
        this.operation = operation;
    }

    @Override
    public Integer call() {
        Map<String, String> env = environment.get();
        try {
            GitHubSettingsCaptureV5.Result result = operation.capture(env);
            GitHubSettingsCaptureV5.writeArtifacts(result, env);
            return result.verified() ? 0 : 1;
        } catch (Exception failure) {
            spec.commandLine().getErr().println(rootMessage(failure));
            return 1;
        }
    }

    private static GitHubSettingsCaptureV5.Result captureProduction(Map<String, String> environment) {
        String root = environment.getOrDefault("GITHUB_API_URL", "https://api.github.com");
        GitHubSettingsCaptureV5.HttpTransport transport = new GitHubSettingsCaptureV5.HttpTransport(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                URI.create(root));
        return GitHubSettingsCaptureV5.capture(environment, transport, Clock.systemUTC());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
