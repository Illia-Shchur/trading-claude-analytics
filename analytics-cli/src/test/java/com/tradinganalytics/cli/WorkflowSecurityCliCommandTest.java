package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class WorkflowSecurityCliCommandTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesAConfinedJsonEvidenceTreeWithoutDirectSystemOutput() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("receipt.json"), "{}\n");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new WorkflowSecurityCliCommand());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("tree", "fixture evidence", evidence.toString())).isZero();
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString()).isEmpty();
    }

    @Test
    void failsClosedForMissingArgumentsAndHostileTreeContent() throws Exception {
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new WorkflowSecurityCliCommand());
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("tree")).isOne();
        assertThat(error.toString()).contains("usage: strategy-v5-workflow-security.mjs");

        error.getBuffer().setLength(0);
        Path evidence = Files.createDirectory(temporary.resolve("hostile"));
        Files.writeString(evidence.resolve("secret.txt"), "not-json\n");
        assertThat(commandLine.execute("tree", "hostile evidence", evidence.toString())).isOne();
        assertThat(error.toString()).contains("contains non-JSON/raw evidence: secret.txt");
    }

    @Test
    void verifiesEveryPhysicalRoleThroughTheBundleMode() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("bundle-root"));
        ObjectNode bundle = JsonHashes.mapper().createObjectNode();
        bundle.put("schema", "strategy-prospective-source-bundle/1")
                .put("version", 1).put("status", "FROZEN").put("decision", "SHADOW")
                .put("lineage_sha256", "a".repeat(64))
                .put("expected_head_sha256", "b".repeat(64))
                .put("ledger_path", "ledger");
        for (String role : WorkflowSecurityV5.SOURCE_BUNDLE_ROLES) {
            byte[] bytes = ("{\"role\":\"" + role + "\"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(root.resolve(role + ".json"), bytes);
            bundle.putObject(role).put("path", role + ".json")
                    .put("byte_sha256", JsonHashes.sha256(bytes));
        }
        Path ledger = Files.createDirectory(root.resolve("ledger"));
        Files.writeString(ledger.resolve("HEAD.json"), "{}\n");
        bundle.put("content_sha256", JsonHashes.ownHash(bundle));
        Files.write(root.resolve("bundle.json"), JsonHashes.mapper().writeValueAsBytes(bundle));
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new WorkflowSecurityCliCommand());
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("bundle", root.toString(), "bundle.json")).isZero();
        assertThat(error.toString()).isEmpty();
    }

    @Test
    void verifiesTheSingleAdditiveSnapshotRootFromTheGitDiff() throws Exception {
        String root = "evidence/prospective-v5/" + "a".repeat(64);
        Path diff = temporary.resolve("custody.diff");
        Files.writeString(diff, "A\t" + root + "/v5-shadow-cycle.json\n"
                + "A\t" + root + "/ledger/HEAD.json\n");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new WorkflowSecurityCliCommand());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("snapshot-root", diff.toString(), root)).isZero();
        assertThat(output).hasToString(root + "\n");
        assertThat(error).hasToString("");

        output.getBuffer().setLength(0);
        assertThat(commandLine.execute("snapshot-root", diff.toString(),
                "evidence/prospective-v5/" + "b".repeat(64))).isOne();
        assertThat(error.toString()).contains(
                "proposed snapshot root does not match the additive diff");
    }

    @Test
    void archiveAndSnapshotModesFailClosedBeforeProducingSuccessOutput() throws Exception {
        Path invalidArchive = temporary.resolve("invalid.tar");
        Files.writeString(invalidArchive, "not a tar archive");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new WorkflowSecurityCliCommand());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));

        assertThat(commandLine.execute("archive", "fixture archive",
                invalidArchive.toString())).isOne();
        assertThat(output).hasToString("");
        assertThat(error.toString()).isNotBlank();

        error.getBuffer().setLength(0);
        assertThat(commandLine.execute("snapshot", temporary.resolve("missing").toString(),
                temporary.toString(), temporary.resolve("registry.json").toString(), "")).isOne();
        assertThat(output).hasToString("");
        assertThat(error.toString()).contains("prospective evidence snapshot");
    }
}
