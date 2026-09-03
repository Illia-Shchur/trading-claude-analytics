package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CiBurnTagCliCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMIT = "a".repeat(40);

    @TempDir Path temporary;

    @Test
    void burnsTagThenPublishesAContentHashedReceipt() throws Exception {
        Path reservation = reservation("seal-1", COMMIT);
        List<List<String>> calls = new ArrayList<>();
        CiBurnTagCliCommand.GitRunner git = (directory, arguments) -> {
            calls.add(List.of(arguments));
            if (List.of(arguments).equals(List.of("rev-parse", "HEAD"))) return COMMIT + "\n";
            if (List.of(arguments).equals(List.of(
                    "rev-parse", "--verify", "refs/tags/research-seal/seal-1^{commit}"))) {
                throw new CiBurnTagCliCommand.MissingGitRefException();
            }
            return "";
        };

        Invocation result = execute(git, reservation.getFileName().toString(), "receipt.json");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(calls).containsExactly(
                List.of("rev-parse", "HEAD"),
                List.of("rev-parse", "--verify", "refs/tags/research-seal/seal-1^{commit}"),
                List.of("tag", "research-seal/seal-1"),
                List.of("push", "origin", "refs/tags/research-seal/seal-1"));
        JsonNode response = JSON.readTree(result.stdout());
        JsonNode receipt = JSON.readTree(Path.of(response.path("receipt").asText()).toFile());
        ObjectNode identity = ((ObjectNode) receipt).deepCopy(); identity.remove("receipt_sha256");
        assertThat(receipt.path("receipt_sha256").asText()).isEqualTo(Sha256.canonicalHex(identity));
        assertThat(receipt.path("ref").asText()).isEqualTo("refs/tags/research-seal/seal-1");
    }

    @Test
    void refusesMismatchedHeadAndTagsBoundToAnotherCommitWithoutMutatingRemote() throws Exception {
        Path reservation = reservation("seal-2", COMMIT);
        List<List<String>> mismatchCalls = new ArrayList<>();
        Invocation mismatch = execute((directory, arguments) -> {
            mismatchCalls.add(List.of(arguments)); return "b".repeat(40) + "\n";
        }, reservation.getFileName().toString());
        assertThat(mismatch.exitCode()).isOne();
        assertThat(mismatch.stderr()).contains("current HEAD does not match");
        assertThat(mismatchCalls).containsExactly(List.of("rev-parse", "HEAD"));

        List<List<String>> wrongTagCalls = new ArrayList<>();
        Invocation wrongTag = execute((directory, arguments) -> {
            wrongTagCalls.add(List.of(arguments));
            return List.of(arguments).equals(List.of("rev-parse", "HEAD"))
                    ? COMMIT + "\n" : "b".repeat(40) + "\n";
        }, reservation.getFileName().toString());
        assertThat(wrongTag.exitCode()).isOne();
        assertThat(wrongTag.stderr()).contains("different commit");
        assertThat(wrongTagCalls).hasSize(2);
    }

    @Test
    void rejectsUnsafeSealAndNonReservedInput() throws Exception {
        Invocation unsafe = execute((directory, arguments) -> "", reservation("bad seal", COMMIT).getFileName().toString());
        assertThat(unsafe.exitCode()).isOne();
        assertThat(unsafe.stderr()).contains("unsafe seal tag");

        ObjectNode invalid = JSON.createObjectNode().put("seal_id", "safe").put("status", "DONE")
                .put("commit_sha", COMMIT);
        Files.writeString(temporary.resolve("invalid.json"), JSON.writeValueAsString(invalid));
        Invocation status = execute((directory, arguments) -> "", "invalid.json");
        assertThat(status.exitCode()).isOne();
        assertThat(status.stderr()).contains("RESERVED reservation");
    }

    @Test
    void rejectsTamperedOrRepositoryMismatchedReservationsBeforeCallingGit() throws Exception {
        Path reservation = reservation("seal-3", COMMIT);
        ObjectNode tampered = (ObjectNode) JSON.readTree(reservation.toFile());
        tampered.put("output", "tampered.json");
        Files.writeString(reservation, JSON.writeValueAsString(tampered));
        List<List<String>> calls = new ArrayList<>();

        Invocation invalidHash = execute((directory, arguments) -> {
            calls.add(List.of(arguments));
            return "";
        }, reservation.getFileName().toString());

        assertThat(invalidHash.exitCode()).isOne();
        assertThat(invalidHash.stderr()).contains("content hash mismatch");
        assertThat(calls).isEmpty();

        Path valid = reservation("seal-4", COMMIT);
        Invocation wrongRepository = execute(
                Map.of("GITHUB_REPOSITORY", "another/repository"),
                (directory, arguments) -> {
                    calls.add(List.of(arguments));
                    return "";
                }, valid.getFileName().toString());
        assertThat(wrongRepository.exitCode()).isOne();
        assertThat(wrongRepository.stderr()).contains("repository mismatch");
        assertThat(calls).isEmpty();
    }

    @Test
    void retriesACorrectlyBoundExistingTagAndRefusesAnExistingReceiptBeforeGit() throws Exception {
        Path reservation = reservation("seal-5", COMMIT);
        List<List<String>> calls = new ArrayList<>();
        Invocation recovery = execute((directory, arguments) -> {
            calls.add(List.of(arguments));
            return List.of(arguments).equals(List.of("rev-parse", "HEAD")) ? COMMIT + "\n"
                    : List.of(arguments).equals(List.of("rev-parse", "--verify",
                            "refs/tags/research-seal/seal-5^{commit}")) ? COMMIT + "\n" : "";
        }, reservation.getFileName().toString(), "recovered-receipt.json");

        assertThat(recovery.exitCode()).isZero();
        assertThat(calls).containsExactly(
                List.of("rev-parse", "HEAD"),
                List.of("rev-parse", "--verify", "refs/tags/research-seal/seal-5^{commit}"),
                List.of("push", "origin", "refs/tags/research-seal/seal-5"));

        Files.writeString(temporary.resolve("occupied.json"), "keep\n");
        calls.clear();
        Invocation occupied = execute((directory, arguments) -> {
            calls.add(List.of(arguments));
            return "";
        }, reservation.getFileName().toString(), "occupied.json");
        assertThat(occupied.exitCode()).isOne();
        assertThat(occupied.stderr()).contains("burn receipt already exists");
        assertThat(calls).isEmpty();
    }

    private Path reservation(String seal, String commit) throws Exception {
        Path workflow = temporary.resolve(".github/workflows/strategy-confirmation.yml");
        Files.createDirectories(workflow.getParent());
        Files.writeString(workflow, "name: confirmation\n");
        ObjectNode value = JSON.createObjectNode();
        value.put("schema", LegacyResearchV3.RESERVATION_SCHEMA);
        value.put("seal_id", seal);
        value.put("status", "RESERVED");
        value.put("repository", "owner/repository");
        value.put("commit_sha", commit);
        value.put("workflow_sha256", LegacyResearchV3.hash(Files.readAllBytes(workflow)));
        for (String field : List.of("precommit_sha256", "definition_sha256", "experiment_sha256",
                "candidate_set_sha256", "data_root_sha256", "acceptance_contract_sha256",
                "container_sha256", "executor_sha256")) {
            value.put(field, "b".repeat(64));
        }
        value.put("experiment_path", "strategy-research/experiment.json");
        value.put("data_path", "strategy-research/data.json");
        value.put("output", "confirmation-evidence.json");
        value.put("created_at", "2026-09-03T00:00:00.000Z");
        value = LegacyResearchV3.withHash(value);
        Path path = temporary.resolve("reservation-" + Math.abs(seal.hashCode()) + ".json");
        Files.writeString(path, JSON.writeValueAsString(value));
        return path;
    }

    private Invocation execute(CiBurnTagCliCommand.GitRunner git, String... arguments) {
        return execute(Map.of(), git, arguments);
    }

    private Invocation execute(
            Map<String, String> environment,
            CiBurnTagCliCommand.GitRunner git,
            String... arguments) {
        CommandLine line = new CommandLine(new CiBurnTagCliCommand(temporary, environment, git));
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return new Invocation(line.execute(arguments), out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
