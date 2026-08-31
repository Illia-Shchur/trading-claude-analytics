package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Focused process-boundary tests for the Java-only prospective workflow. */
class StrategyV5ProspectiveWorkflowCliCommandTest {
    @TempDir
    Path temporary;

    @Test
    void missingModeIsFailClosedAndDoesNotWriteOutput() {
        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[0], streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isOne();
        assertThat(streams.outText()).isEmpty();
        assertThat(streams.errText()).contains("strategy-v5-prospective-workflow");
    }

    @Test
    void picocliPreservesTheModeDependentFlagTail() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(
                new StrategyV5ProspectiveWorkflowCliCommand(temporary));
        command.setOut(new PrintWriter(output, true));
        command.setErr(new PrintWriter(error, true));

        assertThat(command.execute("cycle", "--receipt",
                "picocli-receipt.json")).isOne();
        assertThat(temporary.resolve("picocli-receipt.json")).exists();
        assertThat(error.toString()).doesNotContain("Unknown option");
        assertThat(output.toString()).contains("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED");
    }

    @Test
    void absentLiveSourceWritesAnHonestInactiveBlockedReceipt() throws Exception {
        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[] {"cycle", "--receipt", "receipt.json"},
                streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isOne();
        ObjectNode receipt = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(temporary.resolve("receipt.json")), "receipt");
        assertThat(receipt.path("schema").asText())
                .isEqualTo("strategy-v5-authoritative-command-receipt/1");
        assertThat(receipt.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(receipt.path("details").path("active").asBoolean()).isFalse();
        assertThat(receipt.path("limitations").toString())
                .contains("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED");
        assertThat(receipt.path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(receipt));
        assertThat(streams.errText()).isEmpty();
    }

    @Test
    void noOpAuditIsAHashBoundNoTransitionAndCannotBecomeActive() throws Exception {
        ObjectNode early = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-deployment-audit/1").put("version", 1);
        early.putObject("checks").put("repository_private", false);
        early.put("shadow_append_eligible", false).put("activation_eligible", false)
                .put("blocked", true).put("reason", "blocked");
        early.putArray("exact_external_verification_required").add("custody");
        early.put("blocked_until_external_prerequisites", true);
        early.put("content_sha256", JsonHashes.ownHash(early));
        Files.writeString(temporary.resolve("early.json"),
                JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(early) + "\n");

        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[] {"no-op-audit", "--early", "early.json", "--out", "audit.json"},
                streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isZero();
        ObjectNode audit = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(temporary.resolve("audit.json")), "audit");
        assertThat(audit.path("checks").path("no_new_completed_bar").asBoolean()).isTrue();
        assertThat(audit.path("shadow_append_eligible").asBoolean()).isFalse();
        assertThat(audit.path("activation_eligible").asBoolean()).isFalse();
        assertThat(audit.path("blocked").asBoolean()).isFalse();
        assertThat(audit.path("blocked_until_external_prerequisites").asBoolean()).isFalse();
        assertThat(audit.path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(audit));
    }

    @Test
    void treeModeUsesTheTrustedEvidenceCustodyOwner() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(root.resolve("receipt.json"), "{}\n");
        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[] {"tree", "--label", "fixture evidence", "--path", "evidence"},
                streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isZero();
        assertThat(streams.outText()).contains("\"files\": 1");
        assertThat(streams.errText()).isEmpty();
    }

    @Test
    void requireCycleRejectsActiveClaimsEvenWhenTheRetainedHashIsValid() throws Exception {
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-authoritative-command-receipt/1").put("version", 1)
                .put("command", "prospective-runner").put("status", "COMPLETE");
        receipt.putArray("inputs");
        receipt.putArray("outputs");
        receipt.putArray("limitations");
        receipt.putObject("details").put("active", true);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        Files.writeString(temporary.resolve("active.json"),
                JsonHashes.mapper().writeValueAsString(receipt) + "\n");

        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[] {"require-cycle", "--receipt", "active.json"},
                streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isOne();
        assertThat(streams.errText()).contains("/details/active: must be the constant value 'false'");
    }

    @Test
    void rejectsSensitiveOptionNamesBeforeAnyModeWork() {
        for (String option : new String[] {"--private-key", "--private_key", "--PRIVATEKEY", "--SeCrEt=value", "-secret"}) {
            Streams streams = new Streams();
            int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                    new String[] {"cycle", option}, streams.out, streams.err, Map.of(), temporary);
            assertThat(status).as(option).isOne();
            assertThat(streams.errText()).as(option).contains("sensitive option names");
            assertThat(Files.exists(temporary.resolve("v5-shadow-cycle-receipt.json"))).isFalse();
        }
    }

    @Test
    void rejectsAbsoluteTraversalLinkedAndHardLinkedPaths() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        Files.writeString(real.resolve("input.json"), "{}\n");
        Files.createSymbolicLink(temporary.resolve("linked"), real);
        Files.createLink(temporary.resolve("hardlinked.json"), real.resolve("input.json"));
        for (String value : new String[] {
                temporary.resolve("real/input.json").toString(), "../outside.json",
                "linked/input.json", "hardlinked.json"}) {
            Streams streams = new Streams();
            int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                    new String[] {"tree", "--path", value}, streams.out, streams.err, Map.of(), temporary);
            assertThat(status).as(value).isOne();
        }
    }

    @Test
    void rejectsSymlinkOutputParentAndHardLinkedOutput() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real-output"));
        Files.createSymbolicLink(temporary.resolve("output-link"), real);
        Path existing = Files.writeString(temporary.resolve("existing.json"), "{}\n");
        Files.createLink(temporary.resolve("output-hardlink.json"), existing);
        for (String output : new String[] {"output-link/receipt.json", "output-hardlink.json"}) {
            Streams streams = new Streams();
            int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                    new String[] {"cycle", "--receipt", output},
                    streams.out, streams.err, Map.of(), temporary);
            assertThat(status).as(output).isOne();
        }
    }

    @Test
    void hydrateDeltaRejectsTraversalEventReferences() throws Exception {
        Path ledger = Files.createDirectories(temporary.resolve(".v5-preflight/.v5-ledger"));
        ObjectNode head = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-prospective-ledger-index/1").put("version", 1)
                .put("lineage_sha256", "a".repeat(64)).put("sequence", 1)
                .put("head_sha256", "b".repeat(64));
        head.putArray("event_refs").addObject().put("sequence", 1)
                .put("event_sha256", "b".repeat(64)).put("byte_sha256", "c".repeat(64))
                .put("path", "../escape.json");
        head.put("content_sha256", JsonHashes.ownHash(head));
        Files.writeString(ledger.resolve("HEAD.json"), JsonHashes.mapper().writeValueAsString(head) + "\n");

        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[] {"hydrate-delta", "--target", "target"},
                streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isOne();
        assertThat(temporary.resolve("target")).doesNotExist();
    }

    @Test
    void blockedAttestationReceiptUsesACommandEnumAndSchemaValidation() throws Exception {
        Streams streams = new Streams();
        int status = StrategyV5ProspectiveWorkflowCliCommand.run(
                new String[] {"blocked-attestation", "--out", "blocked.json"},
                streams.out, streams.err, Map.of(), temporary);

        assertThat(status).isOne();
        ObjectNode receipt = (ObjectNode) JsonHashes.parse(
                Files.readAllBytes(temporary.resolve("blocked.json")), "receipt");
        assertThat(receipt.path("command").asText()).isEqualTo("prospective-runner");
        assertThat(receipt.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(receipt.path("details").path("active").asBoolean()).isFalse();
        assertThat(receipt.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(receipt));
    }

    private static final class Streams {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);

        private String outText() { return stdout.toString(StandardCharsets.UTF_8); }
        private String errText() { return stderr.toString(StandardCharsets.UTF_8); }
    }
}
