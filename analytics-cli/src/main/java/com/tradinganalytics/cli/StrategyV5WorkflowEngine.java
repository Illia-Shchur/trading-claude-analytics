package com.tradinganalytics.cli;

import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

import static com.tradinganalytics.cli.StrategyV5WorkflowPaths.flags;

/** Dispatches the mode tail after the Picocli adapter has captured it verbatim. */
final class StrategyV5WorkflowEngine {
    public static final String USAGE = "usage: ./bin/analytics strategy-v5-prospective-workflow "
            + "capture-settings|verify-bundle|hydrate|drift|cycle|require-cycle|"
            + "early-audit|final-audit|no-op-audit|sign-attestation|verify-preflight|"
            + "blocked-attestation|hydrate-delta|verify-snapshot|tree|archive|snapshot-root [options]";
    /** Process-boundary entry point used by the Picocli adapter and focused tests. */
    public static int run(String[] args, PrintStream stdout, PrintStream stderr,
                          Map<String, String> environment, Path workingDirectory) {
        Map<String, String> env = environment == null ? Map.of() : Map.copyOf(environment);
        try {
            Path work = PathConfinement.requireRealDirectory(
                    workingDirectory == null ? Path.of("") : workingDirectory,
                    "workflow working directory");
            String mode = args == null || args.length == 0 ? "" : args[0];
            Map<String, String> flags = flags(args, 1);
            if (mode.isBlank() || "--help".equals(mode) || "-h".equals(mode)) {
                stderr.println(USAGE);
                return 1;
            }
            return switch (mode) {
                case "capture-settings" -> StrategyV5SettingsWorkflow.captureSettings(env, work);
                case "verify-bundle" -> StrategyV5SettingsWorkflow.verifyBundle(flags, work, stdout);
                case "hydrate" -> StrategyV5SettingsWorkflow.hydrate(flags, work, stdout);
                case "drift" -> StrategyV5SettingsWorkflow.drift(flags, work, stdout);
                case "cycle" -> StrategyV5WorkflowLifecycle.cycle(flags, env, work, stdout);
                case "require-cycle" -> StrategyV5WorkflowLifecycle.requireCycle(flags, work, stdout);
                case "early-audit", "final-audit" -> StrategyV5WorkflowLifecycle.audit(mode, flags, env, work, stdout);
                case "no-op-audit" -> StrategyV5WorkflowLifecycle.noOpAudit(flags, work, stdout);
                case "sign-attestation" -> StrategyV5WorkflowLifecycle.signAttestation(env, work, stdout);
                case "blocked-attestation" -> StrategyV5WorkflowLifecycle.blockedAttestation(flags, work, stdout);
                case "verify-preflight" -> StrategyV5SnapshotWorkflow.verifyPreflight(flags, work, stdout);
                case "hydrate-delta" -> StrategyV5SnapshotWorkflow.hydrateDelta(flags, work, stdout);
                case "verify-snapshot" -> StrategyV5SnapshotWorkflow.verifySnapshot(flags, work, stdout);
                case "tree" -> StrategyV5SnapshotWorkflow.verifyTree(flags, work, stdout);
                case "archive" -> StrategyV5SnapshotWorkflow.verifyArchive(flags, work, stdout);
                case "snapshot-root" -> StrategyV5SnapshotWorkflow.verifySnapshotRoot(flags, work, stdout);
                default -> throw new IllegalArgumentException(USAGE);
            };
        } catch (Exception error) {
            stderr.println(CliMessages.rootCauseMessage(error));
            return 1;
        }
    }
}
