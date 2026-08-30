package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/** Spring/Picocli port of {@code tools/strategy-attestation.mjs}. */
@Component
@Command(name = "strategy-attestation", description = "Manage v3 confirmation attestations")
public class StrategyAttestationCliCommand implements Callable<Integer> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String USAGE = "usage: strategy-attestation.mjs keygen|reserve|burn|sign|verify|import\n";
    private static final Set<PosixFilePermission> MODE_0600 = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> MODE_0644 = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>")
    private String action;

    @Unmatched
    private List<String> rawOptions = new ArrayList<>();

    @Spec private CommandSpec spec;

    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final Clock clock;
    private final GitRunner git;

    public StrategyAttestationCliCommand() {
        this(Path.of("").toAbsolutePath().normalize(), System.getenv(), Clock.systemUTC(),
                StrategyAttestationCliCommand::runGit);
    }

    StrategyAttestationCliCommand(Path workingDirectory, Map<String, String> environment,
                                  Clock clock, GitRunner git) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.git = Objects.requireNonNull(git, "git");
    }

    @Override
    public Integer call() {
        try {
            if (action == null || !Set.of("keygen", "reserve", "freeze-confirmation", "burn", "sign",
                    "verify", "verify-attestation", "import", "import-attestation").contains(action)) {
                spec.commandLine().getOut().print(USAGE);
                return 0;
            }
            Map<String, Object> options = LegacyFlagOptions.parse(rawOptions);
            ObjectNode result = switch (action) {
                case "keygen" -> keygen(options);
                case "reserve", "freeze-confirmation" -> reserve(options);
                case "burn" -> burn(options);
                case "sign" -> sign(options);
                case "verify", "verify-attestation" -> verify(options, false);
                case "import", "import-attestation" -> verify(options, true);
                default -> throw new IllegalStateException("unreachable action");
            };
            spec.commandLine().getOut().print(NodePrettyJson.write(result));
            return 0;
        } catch (Exception exception) {
            spec.commandLine().getErr().println(message(exception));
            return 1;
        }
    }

    private ObjectNode keygen(Map<String, Object> options) throws Exception {
        String privateOut = text(options, "private_out"), publicOut = text(options, "public_out");
        if (privateOut == null || publicOut == null) {
            throw new IllegalArgumentException(
                    "keygen requires --private-out and --public-out; private key is never printed");
        }
        ObjectNode pair = LegacyResearchV3.generateEd25519KeyPair();
        Path privatePath = resolve(privateOut), publicPath = resolve(publicOut);
        writeExclusive(privatePath, pair.path("privateKey").asText(), MODE_0600);
        writeExclusive(publicPath, pair.path("publicKey").asText(), MODE_0644);
        ObjectNode output = JSON.createObjectNode();
        output.put("private_key_path", privatePath.toString());
        output.put("public_key_path", publicPath.toString());
        output.put("warning", "Store private key only in a GitHub secret; public key may be committed.");
        return output;
    }

    private ObjectNode reserve(Map<String, Object> options) throws Exception {
        ObjectNode input = JSON.createObjectNode();
        put(input, "sealId", first(options, "seal_id", "seal"));
        put(input, "repository", options.get("repository"));
        put(input, "commitSha", first(options, "commit_sha", "commit"));
        put(input, "workflowSha256", first(options, "workflow_sha256", "workflow"));
        put(input, "precommitSha256", options.get("precommit_sha256"));
        put(input, "definitionSha256", options.get("definition_sha256"));
        put(input, "experimentSha256", options.get("experiment_sha256"));
        put(input, "candidateSetSha256", options.get("candidate_set_sha256"));
        put(input, "dataRootSha256", options.get("data_root_sha256"));
        put(input, "acceptanceContractSha256", options.get("acceptance_contract_sha256"));
        put(input, "containerSha256", options.get("container_sha256"));
        put(input, "executorSha256", options.get("executor_sha256"));
        put(input, "experimentPath", options.get("experiment_path"));
        put(input, "dataPath", options.get("data_path"));
        put(input, "output", options.get("output"));
        Path workflow = resolve(textOr(options, "workflow", ".github/workflows/strategy-confirmation.yml"));
        input.put("workflowPath", workflow.toString());
        ObjectNode reservation = LegacyResearchV3.makeConfirmationReservation(input, clock);
        String targetText = text(options, "out");
        if (targetText == null) targetText = "strategy-research/confirmations/"
                + reservation.path("seal_id").asText() + ".json";
        Path target = resolve(targetText);
        Path reservationRoot = resolve("strategy-research/confirmations");
        if (!target.startsWith(reservationRoot)) {
            throw new IllegalArgumentException("reservation output must be under strategy-research/confirmations");
        }
        String currentCommit = environment.get("GITHUB_SHA");
        if (currentCommit == null || currentCommit.isBlank()) currentCommit = git.run(workingDirectory, "rev-parse", "HEAD").trim();
        ObjectNode validation = JSON.createObjectNode();
        validation.put("currentCommit", currentCommit);
        put(validation, "repository", options.get("repository"));
        validation.put("workflowPath", workflow.toString());
        validation.put("reservationPath", target.toString());
        LegacyResearchV3.validateConfirmationReservation(reservation, validation);
        writeExclusive(target, NodePrettyJson.write(reservation), MODE_0644);
        ObjectNode output = JSON.createObjectNode();
        output.put("path", target.toString());
        output.set("reservation", reservation);
        return output;
    }

    private ObjectNode burn(Map<String, Object> options) throws Exception {
        ObjectNode reservation = readRequired(options, "reservation");
        Path burnRoot = resolve(textOr(options, "burn_root", ".research-run/burn"));
        Path burned = LegacyResearchV3.burnReservation(reservation, burnRoot);
        return JSON.createObjectNode().put("burned", burned.toString());
    }

    private ObjectNode sign(Map<String, Object> options) throws Exception {
        ObjectNode reservation = readRequired(options, "reservation");
        ObjectNode result = readRequired(options, "result");
        Path workflow = resolve(textOr(options, "workflow", ".github/workflows/strategy-confirmation.yml"));
        String burnPath = text(options, "burn_receipt");
        if (burnPath == null) throw new IllegalArgumentException(
                "sign requires durable --burn-receipt from the remote immutable tag/ref");
        ObjectNode burnReceipt = read(resolve(burnPath));
        String repository = firstText(text(options, "repository"), environment.get("GITHUB_REPOSITORY"));
        String commit = firstText(text(options, "commit_sha"), environment.get("GITHUB_SHA"));
        if (repository == null || commit == null) {
            throw new IllegalArgumentException("sign requires repository and exact current commit SHA");
        }
        String privateKey = environment.get("RESEARCH_ATTESTATION_PRIVATE_KEY");
        if (privateKey == null) {
            String keyPath = text(options, "private_key");
            if (keyPath == null) throw new IllegalArgumentException("sign requires --private-key or RESEARCH_ATTESTATION_PRIVATE_KEY");
            privateKey = Files.readString(resolve(keyPath), StandardCharsets.UTF_8);
        }
        String workflowSha = firstText(text(options, "workflow_sha"), environment.get("GITHUB_WORKFLOW_SHA"));
        if (workflowSha == null) workflowSha = LegacyResearchV3.hash(Files.readString(workflow, StandardCharsets.UTF_8));
        ObjectNode input = JSON.createObjectNode();
        input.set("reservation", reservation);
        input.set("result", result);
        input.set("burnReceipt", burnReceipt);
        input.put("reservationPath", resolve(text(options, "reservation")).toString());
        input.put("workflowPath", workflow.toString());
        input.put("privateKeyPem", privateKey);
        input.put("repository", repository);
        input.put("commitSha", commit);
        input.put("workflowSha", workflowSha);
        String runId = firstText(text(options, "run_id"), environment.get("GITHUB_RUN_ID"));
        if (runId != null) input.put("runId", runId);
        Object attempt = options.get("run_attempt");
        if (attempt == null) attempt = environment.getOrDefault("GITHUB_RUN_ATTEMPT", "1");
        input.put("runAttempt", Double.parseDouble(String.valueOf(attempt)));
        ObjectNode attestation = LegacyResearchV3.signAttestation(input, clock);
        String targetText = text(options, "out");
        if (targetText == null) targetText = ".research-run/attestations/"
                + reservation.path("seal_id").asText() + ".json";
        Path target = resolve(targetText);
        writeExclusive(target, NodePrettyJson.write(attestation), MODE_0644);
        ObjectNode summary = attestation.deepCopy();
        summary.remove("result");
        ObjectNode output = JSON.createObjectNode();
        output.put("path", target.toString());
        output.set("attestation", summary);
        return output;
    }

    private ObjectNode verify(Map<String, Object> options, boolean importing) throws Exception {
        ObjectNode attestation = readRequired(options, "attestation");
        String publicKeyPath = text(options, "public_key");
        if (publicKeyPath == null) throw new IllegalArgumentException("--public-key is required");
        ObjectNode input = JSON.createObjectNode();
        input.put("publicKeyPem", Files.readString(resolve(publicKeyPath), StandardCharsets.UTF_8));
        String reservationPath = text(options, "reservation");
        if (reservationPath != null) {
            input.set("reservation", read(resolve(reservationPath)));
            input.put("reservationPath", resolve(reservationPath).toString());
        }
        put(input, "expectedRepository", options.get("repository"));
        put(input, "expectedCommitSha", options.get("commit_sha"));
        put(input, "expectedRunId", options.get("run_id"));
        input.put("workflowPath", resolve(textOr(options, "workflow_path",
                ".github/workflows/strategy-confirmation.yml")).toString());
        input.put("burnRoot", resolve(textOr(options, "burn_root", ".research-run/burn")).toString());
        if (importing) {
            String out = text(options, "out");
            if (out != null) input.put("out", resolve(out).toString());
            return LegacyResearchV3.importAttestation(attestation, input, clock);
        }
        return LegacyResearchV3.verifyAttestation(attestation, input);
    }

    private ObjectNode readRequired(Map<String, Object> options, String key) throws Exception {
        String value = text(options, key);
        if (value == null) throw new IllegalArgumentException("--" + key.replace('_', '-') + " is required");
        return read(resolve(value));
    }

    private static ObjectNode read(Path path) throws Exception {
        JsonNode value = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        if (value == null || !value.isObject()) throw new IllegalArgumentException(path + " must contain a JSON object");
        return (ObjectNode) value;
    }

    private Path resolve(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private static void writeExclusive(Path path, String text, Set<PosixFilePermission> mode) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try { Files.setPosixFilePermissions(path, mode); }
        catch (UnsupportedOperationException ignored) { /* Windows */ }
    }

    private static String runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(stderr.trim());
        return stdout;
    }

    private static Object first(Map<String, Object> options, String... keys) {
        for (String key : keys) if (options.get(key) != null) return options.get(key);
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String text(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value == null || Boolean.TRUE.equals(value) ? null : String.valueOf(value);
    }

    private static String textOr(Map<String, Object> options, String key, String fallback) {
        String value = text(options, key); return value == null ? fallback : value;
    }

    private static void put(ObjectNode target, String key, Object value) {
        if (value == null || Boolean.TRUE.equals(value)) return;
        target.put(key, String.valueOf(value));
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    @FunctionalInterface
    interface GitRunner {
        String run(Path directory, String... arguments) throws Exception;
    }
}
