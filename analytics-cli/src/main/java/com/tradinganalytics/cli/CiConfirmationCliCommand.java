package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Fail-closed Spring port of the intentionally unavailable CI confirmation runner. */
@Component
@Command(name = "ci-confirmation", description = "Validate the frozen CI confirmation boundary")
public class CiConfirmationCliCommand implements Callable<Integer> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Option(names = "--reservation") private String reservation;
    @Option(names = "--result") private String result;
    @Option(names = "--trades") private String trades;
    @Option(names = "--metrics") private String metrics;
    @Option(names = "--preflight") private boolean preflight;
    @Spec private CommandSpec spec;

    private final ConfirmationValidator validator;

    public CiConfirmationCliCommand() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        this.validator = new ProductionValidator(cwd, System.getenv(), CiConfirmationCliCommand::runGit);
    }

    CiConfirmationCliCommand(ConfirmationValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public Integer call() {
        try {
            if (reservation == null) throw new IllegalArgumentException(
                    "confirmation evaluator requires --reservation");
            validator.validate(reservation);
            if (result != null || trades != null || metrics != null) {
                throw new IllegalArgumentException(
                        "caller-authored result/trades/metrics are forbidden for CI confirmation");
            }
            if (preflight) {
                throw new IllegalStateException("CONFIRMATION_RUNNER_UNAVAILABLE: public unseen-data custody/fetch "
                        + "and frozen authoritative evaluation are not implemented; preflight failed before any burn action");
            }
            throw new IllegalStateException("CONFIRMATION_RUNNER_UNAVAILABLE: public unseen-data custody/fetch "
                    + "and frozen authoritative evaluation are not implemented; no CI_ATTESTED_CONFIRMATION can be produced");
        } catch (Exception exception) {
            spec.commandLine().getErr().println(message(exception));
            return 1;
        }
    }

    @FunctionalInterface
    interface ConfirmationValidator {
        JsonNode validate(String reservationPath) throws Exception;
    }

    @FunctionalInterface
    interface GitRunner {
        String run(Path directory, String... arguments) throws Exception;
    }

    private record ProductionValidator(Path workingDirectory, Map<String, String> environment, GitRunner git)
            implements ConfirmationValidator {
        private ProductionValidator {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
            environment = Map.copyOf(environment);
        }

        @Override
        public JsonNode validate(String reservationInput) throws Exception {
            Path path = resolve(workingDirectory, reservationInput);
            JsonNode value = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (value == null || !value.isObject()) throw new IllegalArgumentException(
                    "confirmation reservation must contain a JSON object");
            String tracked = git.run(workingDirectory, "ls-files", "--error-unmatch", "--", reservationInput).trim();
            if (tracked.isEmpty()) throw new IllegalArgumentException(
                    "confirmation reservation must be committed/tracked");
            String currentCommit = environment.get("GITHUB_SHA");
            if (currentCommit == null || currentCommit.isBlank()) {
                try { currentCommit = git.run(workingDirectory, "rev-parse", "HEAD").trim(); }
                catch (Exception ignored) { currentCommit = null; }
            }
            String repository = environment.get("GITHUB_REPOSITORY");
            if (repository == null || repository.isBlank()) repository = value.path("repository").asText(null);
            String workflowInput = environment.getOrDefault(
                    "GITHUB_WORKFLOW_PATH", ".github/workflows/strategy-confirmation.yml");
            ObjectNode options = JSON.createObjectNode();
            if (currentCommit != null) options.put("currentCommit", currentCommit);
            if (repository != null) options.put("repository", repository);
            options.put("workflowPath", resolve(workingDirectory, workflowInput).toString());
            options.put("reservationPath", path.toString());
            LegacyResearchV3.validateConfirmationReservation(value, options);
            return value;
        }
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    private static String runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(stderr.trim());
        return stdout;
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
