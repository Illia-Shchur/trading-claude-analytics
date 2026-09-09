package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.SafeTreeVerifier;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Spring boundary for the operational modes in strategy-v5-workflow-security.mjs. */
@Component
@Command(name = "strategy-v5-workflow-security",
        description = "Verify an evidence tree or prospective source bundle")
public final class WorkflowSecurityCliCommand implements Callable<Integer> {
    private static final String USAGE = "usage: strategy-v5-workflow-security.mjs "
            + "tree <label> <path> | bundle <root> <path> | archive <label> <path> | "
            + "snapshot-root <diff> <expected-root> | snapshot <proposed> <trusted-base> "
            + "<registry> <fingerprint> [now-ms]";

    @Parameters(arity = "0..*", hidden = true)
    private List<String> arguments = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        try {
            if (arguments.isEmpty()) throw new IllegalArgumentException(USAGE);
            switch (arguments.get(0)) {
                case "tree" -> {
                    requireSize(3);
                    WorkflowSecurityV5.verifySafeTree(
                            Path.of(arguments.get(2)), arguments.get(1),
                            SafeTreeVerifier.Options.EVIDENCE);
                }
                case "bundle" -> {
                    requireSize(3);
                    WorkflowSecurityV5.verifyProspectiveSourceBundle(
                            Path.of(arguments.get(1)), arguments.get(2));
                }
                case "archive" -> {
                    requireSize(3);
                    WorkflowSecurityV5.verifyTarArchive(
                            Path.of(arguments.get(2)), arguments.get(1),
                            SafeTreeVerifier.Options.EVIDENCE);
                }
                case "snapshot-root" -> verifySnapshotRoot();
                case "snapshot" -> verifySnapshot();
                default -> throw new IllegalArgumentException(USAGE);
            }
            return 0;
        } catch (Exception error) {
            spec.commandLine().getErr().println(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            return 1;
        }
    }

    private void verifySnapshotRoot() throws Exception {
        requireSize(3);
        List<String> changed = Files.readAllLines(Path.of(arguments.get(1))).stream()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\\t", -1))
                .filter(parts -> parts.length > 1 && !parts[1].isBlank())
                .map(parts -> parts[1])
                .toList();
        String actual = WorkflowSecurityV5.requireSingleProspectiveSnapshotRootV5(changed);
        if (!actual.equals(arguments.get(2))) {
            throw new IllegalArgumentException(
                    "proposed snapshot root does not match the additive diff");
        }
        spec.commandLine().getOut().println(actual);
    }

    private void verifySnapshot() throws Exception {
        if (arguments.size() < 5 || arguments.size() > 6) {
            throw new IllegalArgumentException(USAGE);
        }
        String fingerprint = arguments.get(4).isBlank() ? null : arguments.get(4);
        long now = arguments.size() == 6
                ? Long.parseLong(arguments.get(5)) : System.currentTimeMillis();
        WorkflowSecurityV5.ProspectiveSnapshotVerification result =
                WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                        new WorkflowSecurityV5.ProspectiveSnapshotOptions(
                                Path.of(arguments.get(1)), Path.of(arguments.get(2)),
                                Path.of(arguments.get(3)), fingerprint, now));
        ObjectNode output = JsonHashes.mapper().createObjectNode()
                .put("verified_artifacts", result.verified())
                .put("ledger_snapshots", 1);
        if (result.trustedBaseSequence() == null) output.putNull("trusted_base_latest_sequence");
        else output.put("trusted_base_latest_sequence", result.trustedBaseSequence());
        output.put("proposed_sequence", result.sequence());
        spec.commandLine().getOut().println(JsonHashes.mapper().writeValueAsString(output));
    }

    private void requireSize(int expected) {
        if (arguments.size() != expected) throw new IllegalArgumentException(USAGE);
    }
}
