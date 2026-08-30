package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CiBurnTagCliCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMIT = "a".repeat(40);

    @TempDir Path temporary;

    @Test
    void burnsTagThenPushesBeforeWritingAContentHashedReceipt() throws Exception {
        Path reservation = reservation("seal-1", COMMIT);
        List<List<String>> calls = new ArrayList<>();
        CiBurnTagCliCommand.GitRunner git = (directory, arguments) -> {
            calls.add(List.of(arguments));
            if (List.of(arguments).equals(List.of("rev-parse", "HEAD"))) return COMMIT + "\n";
            if (List.of(arguments).equals(List.of("rev-parse", "--verify", "refs/tags/research-seal/seal-1"))) {
                throw new CiBurnTagCliCommand.MissingGitRefException();
            }
            return "";
        };

        Invocation result = execute(git, reservation.getFileName().toString(), "receipt.json");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(calls).containsExactly(
                List.of("rev-parse", "HEAD"),
                List.of("rev-parse", "--verify", "refs/tags/research-seal/seal-1"),
                List.of("tag", "research-seal/seal-1"),
                List.of("push", "origin", "refs/tags/research-seal/seal-1"));
        JsonNode response = JSON.readTree(result.stdout());
        JsonNode receipt = JSON.readTree(Path.of(response.path("receipt").asText()).toFile());
        ObjectNode identity = ((ObjectNode) receipt).deepCopy(); identity.remove("receipt_sha256");
        assertThat(receipt.path("receipt_sha256").asText()).isEqualTo(Sha256.canonicalHex(identity));
        assertThat(receipt.path("ref").asText()).isEqualTo("refs/tags/research-seal/seal-1");
    }

    @Test
    void refusesMismatchedHeadAndExistingTagsWithoutMutatingRemote() throws Exception {
        Path reservation = reservation("seal-2", COMMIT);
        List<List<String>> mismatchCalls = new ArrayList<>();
        Invocation mismatch = execute((directory, arguments) -> {
            mismatchCalls.add(List.of(arguments)); return "b".repeat(40) + "\n";
        }, reservation.getFileName().toString());
        assertThat(mismatch.exitCode()).isOne();
        assertThat(mismatch.stderr()).contains("current HEAD does not match");
        assertThat(mismatchCalls).containsExactly(List.of("rev-parse", "HEAD"));

        List<List<String>> duplicateCalls = new ArrayList<>();
        Invocation duplicate = execute((directory, arguments) -> {
            duplicateCalls.add(List.of(arguments));
            return List.of(arguments).equals(List.of("rev-parse", "HEAD")) ? COMMIT + "\n" : COMMIT + "\n";
        }, reservation.getFileName().toString());
        assertThat(duplicate.exitCode()).isOne();
        assertThat(duplicate.stderr()).contains("already exists");
        assertThat(duplicateCalls).hasSize(2);
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

    private Path reservation(String seal, String commit) throws Exception {
        ObjectNode value = JSON.createObjectNode();
        value.put("seal_id", seal);
        value.put("status", "RESERVED");
        value.put("commit_sha", commit);
        value.put("content_sha256", Sha256.canonicalHex(value));
        Path path = temporary.resolve("reservation-" + Math.abs(seal.hashCode()) + ".json");
        Files.writeString(path, JSON.writeValueAsString(value));
        return path;
    }

    private Invocation execute(CiBurnTagCliCommand.GitRunner git, String... arguments) {
        CommandLine line = new CommandLine(new CiBurnTagCliCommand(temporary, git));
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return new Invocation(line.execute(arguments), out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
