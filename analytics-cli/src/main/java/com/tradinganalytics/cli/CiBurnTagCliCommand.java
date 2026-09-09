package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Spring port of the one-way {@code tools/ci-burn-tag.mjs} operation. */
@Component
@Command(name = "ci-burn-tag", description = "Burn and push an immutable research confirmation tag")
public class CiBurnTagCliCommand implements Callable<Integer> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern SAFE_TAG = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final Pattern COMMIT = Pattern.compile("^[a-f0-9]{40}$");

    @Parameters(index = "0", arity = "0..1", paramLabel = "<reservation.json>")
    private String reservationPath;
    @Parameters(index = "1", arity = "0..1", paramLabel = "<receipt.json>")
    private String outputPath;
    @Spec private CommandSpec spec;

    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final GitRunner git;

    public CiBurnTagCliCommand() {
        this(Path.of("").toAbsolutePath().normalize(), System.getenv(), CiBurnTagCliCommand::runGit);
    }

    CiBurnTagCliCommand(Path workingDirectory, GitRunner git) {
        this(workingDirectory, Map.of(), git);
    }

    CiBurnTagCliCommand(Path workingDirectory, Map<String, String> environment, GitRunner git) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.git = Objects.requireNonNull(git, "git");
    }

    @Override
    public Integer call() {
        try {
            if (reservationPath == null) throw new IllegalArgumentException(
                    "ci-burn-tag requires <reservation.json>");
            ObjectNode reservation = read(resolve(reservationPath));
            String tag = "research-seal/" + reservation.path("seal_id").asText();
            if (!SAFE_TAG.matcher(tag).matches()) throw new IllegalArgumentException("unsafe seal tag");
            String commit = reservation.path("commit_sha").asText();
            if (!"RESERVED".equals(reservation.path("status").asText()) || !COMMIT.matcher(commit).matches()) {
                throw new IllegalArgumentException(
                        "burn requires a RESERVED reservation with an exact commit SHA");
            }
            validateReservation(reservation);

            ObjectNode receipt = JSON.createObjectNode();
            receipt.put("ref", "refs/tags/" + tag);
            receipt.set("reservation_sha256", reservation.get("content_sha256"));
            receipt.put("commit_sha", commit);
            receipt.put("status", "BURNED");
            receipt.put("receipt_sha256", Sha256.canonicalHex(receipt));
            String receiptText = pretty(receipt);
            Path output = resolve(outputPath == null ? ".research-run/burn-receipt.json" : outputPath);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("burn receipt already exists: " + output);
            }

            String head = git.run(workingDirectory, "rev-parse", "HEAD").trim();
            if (!head.equals(commit)) throw new IllegalArgumentException(
                    "burn must tag the reservation commit; current HEAD does not match reservation.commit_sha");
            boolean createTag = false;
            try {
                String taggedCommit = git.run(workingDirectory, "rev-parse", "--verify",
                        "refs/tags/" + tag + "^{commit}").trim();
                if (!commit.equals(taggedCommit)) {
                    throw new IllegalArgumentException(
                            "confirmation seal tag exists at a different commit: " + tag);
                }
            } catch (MissingGitRefException expected) {
                createTag = true;
            }

            Path stagedReceipt = stageReceipt(output, receiptText);
            try {
                if (createTag) git.run(workingDirectory, "tag", tag);
                // Pushing an existing, correctly-bound local tag is intentional: it makes a
                // retry recover after the remote mutation succeeded but receipt publication did not.
                git.run(workingDirectory, "push", "origin", "refs/tags/" + tag);
                publishReceipt(stagedReceipt, output);
            } catch (Exception error) {
                try {
                    Files.deleteIfExists(stagedReceipt);
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
            ObjectNode response = JSON.createObjectNode();
            response.put("tag", tag);
            response.put("receipt", output.toString());
            spec.commandLine().getOut().println(JSON.writeValueAsString(response));
            return 0;
        } catch (Exception exception) {
            spec.commandLine().getErr().println(CliMessages.message(exception));
            return 1;
        }
    }

    private void validateReservation(ObjectNode reservation) {
        ObjectNode options = JSON.createObjectNode();
        String expectedRepository = environment.get("GITHUB_REPOSITORY");
        if (expectedRepository != null && !expectedRepository.isBlank()) {
            options.put("repository", expectedRepository);
        }
        Path workflow = resolve(environment.getOrDefault(
                "GITHUB_WORKFLOW_PATH", ".github/workflows/strategy-confirmation.yml"));
        options.put("workflowPath", workflow.toString());
        LegacyResearchV3.validateConfirmationReservation(reservation, options);
    }

    private static Path stageReceipt(Path output, String receipt) throws Exception {
        Path parent = output.getParent();
        if (parent == null) throw new IllegalArgumentException("burn receipt path has no parent");
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".burn-receipt-", ".tmp");
        try {
            Files.writeString(staged, receipt, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("burn receipt already exists: " + output);
            }
            return staged;
        } catch (Exception error) {
            try {
                Files.deleteIfExists(staged);
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private static void publishReceipt(Path staged, Path output) throws Exception {
        try {
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, output);
        }
    }

    private static ObjectNode read(Path path) throws Exception {
        JsonNode value = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        if (value == null || !value.isObject()) throw new IllegalArgumentException(
                "reservation must contain a JSON object");
        return (ObjectNode) value;
    }

    private Path resolve(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private static String pretty(JsonNode value) throws Exception {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
    }

    private static String runGit(Path directory, String... arguments) throws Exception {
        try {
            return CliProcessSupport.runGit(directory, arguments);
        } catch (CliProcessSupport.GitCommandFailure error) {
            if (arguments.length >= 2 && "rev-parse".equals(arguments[0]) && "--verify".equals(arguments[1])) {
                throw new MissingGitRefException();
            }
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    @FunctionalInterface
    interface GitRunner {
        String run(Path directory, String... arguments) throws Exception;
    }

    static final class MissingGitRefException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
