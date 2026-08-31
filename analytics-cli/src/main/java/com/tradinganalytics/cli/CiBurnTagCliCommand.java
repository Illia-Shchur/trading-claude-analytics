package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
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
    private final GitRunner git;

    public CiBurnTagCliCommand() {
        this(Path.of("").toAbsolutePath().normalize(), CiBurnTagCliCommand::runGit);
    }

    CiBurnTagCliCommand(Path workingDirectory, GitRunner git) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
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
            String head = git.run(workingDirectory, "rev-parse", "HEAD").trim();
            if (!head.equals(commit)) throw new IllegalArgumentException(
                    "burn must tag the reservation commit; current HEAD does not match reservation.commit_sha");
            try {
                git.run(workingDirectory, "rev-parse", "--verify", "refs/tags/" + tag);
                throw new IllegalArgumentException("confirmation seal tag already exists: " + tag);
            } catch (MissingGitRefException expected) {
                // Exact absent-ref case; every other Git failure remains fatal.
            }
            git.run(workingDirectory, "tag", tag);
            git.run(workingDirectory, "push", "origin", "refs/tags/" + tag);

            ObjectNode receipt = JSON.createObjectNode();
            receipt.put("ref", "refs/tags/" + tag);
            receipt.set("reservation_sha256", reservation.get("content_sha256"));
            receipt.put("commit_sha", commit);
            receipt.put("status", "BURNED");
            receipt.put("receipt_sha256", Sha256.canonicalHex(receipt));
            Path output = resolve(outputPath == null ? ".research-run/burn-receipt.json" : outputPath);
            Files.createDirectories(output.getParent());
            Files.writeString(output, pretty(receipt), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ObjectNode response = JSON.createObjectNode();
            response.put("tag", tag);
            response.put("receipt", output.toString());
            spec.commandLine().getOut().println(JSON.writeValueAsString(response));
            return 0;
        } catch (Exception exception) {
            spec.commandLine().getErr().println(message(exception));
            return 1;
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
        List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        if (status != 0) {
            if (arguments.length >= 2 && "rev-parse".equals(arguments[0]) && "--verify".equals(arguments[1])) {
                throw new MissingGitRefException();
            }
            throw new IllegalStateException(stderr.trim());
        }
        return stdout;
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    @FunctionalInterface
    interface GitRunner {
        String run(Path directory, String... arguments) throws Exception;
    }

    static final class MissingGitRefException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
