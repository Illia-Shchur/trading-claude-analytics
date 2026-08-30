package com.tradinganalytics.research.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Native standalone adapter for {@code tools/calib-run.mjs}. */
public final class CalibrationRunCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USAGE = "usage: ./bin/analytics calib-run <init|plan|collect|status> ...\n";

    private CalibrationRunCommand() {}

    public static CalibrationCommandResult run(List<String> args, Path workingDirectory, Path repositoryRoot) {
        return run(args, workingDirectory, repositoryRoot, Instant::now, null);
    }

    /** Deterministic clock/suffix seam used by byte-for-byte Node compatibility tests. */
    public static CalibrationCommandResult run(List<String> args, Path workingDirectory, Path repositoryRoot,
                                                Supplier<Instant> now, Supplier<String> randomSuffix) {
        StringBuilder stderr = new StringBuilder();
        CalibrationRun engine = randomSuffix == null
                ? new CalibrationRun(repositoryRoot, now, () -> defaultSuffix(), stderr::append)
                : new CalibrationRun(repositoryRoot, now, randomSuffix, stderr::append);
        String command = args.isEmpty() ? null : args.get(0);
        try {
            if ("init".equals(command)) {
                CalibrationRun.InitResult result = engine.cmdInit(new CalibrationRun.InitOptions(
                        option(args, "--corpus", null), option(args, "--mode", "full"),
                        option(args, "--scope-items", null), option(args, "--scope-skipped", null),
                        option(args, "--position", null), option(args, "--anchors", null),
                        option(args, "--registry", null), option(args, "--prior-calibrations", null),
                        option(args, "--skill-dir", null), option(args, "--target-skills", null),
                        option(args, "--out", null), option(args, "--run-id", null), null, null));
                for (String warning : result.warnings()) stderr.append("WARNING — ").append(warning).append('\n');
                stderr.append("initialized run ").append(result.run().path("run_id").asText())
                        .append(" at ").append(result.runDir()).append(" (mode=")
                        .append(result.run().path("mode").asText()).append(")\n");
                stderr.append("next: ./bin/analytics calib-run plan extract --run ").append(result.runDir()).append('\n');
                return CalibrationCommandResult.success("", stderr.toString());
            }
            if ("plan".equals(command)) {
                String phase = args.size() > 1 ? args.get(1) : null;
                String runValue = option(args, "--run", null);
                if (phase == null || runValue == null) throw new IllegalArgumentException("usage: plan <phase> --run <dir>");
                Path runDir = CalibrationPaths.resolve(workingDirectory, runValue);
                List<ObjectNode> tasks = engine.cmdPlan(runDir, phase);
                stderr.append("planned ").append(phase).append(": ").append(tasks.size()).append(" task(s)\n");
                for (ObjectNode task : tasks) stderr.append("  [").append(task.path("model").asText()).append("] ")
                        .append(task.path("task_id").asText()).append(" -> ").append(task.path("prompt").asText()).append('\n');
                return CalibrationCommandResult.success("", stderr.toString());
            }
            if ("collect".equals(command)) {
                String phase = args.size() > 1 ? args.get(1) : null;
                String runValue = option(args, "--run", null);
                if (phase == null || runValue == null) throw new IllegalArgumentException("usage: collect <phase> --run <dir>");
                Path runDir = CalibrationPaths.resolve(workingDirectory, runValue);
                ObjectNode result = engine.cmdCollect(runDir, phase);
                if (!result.path("ok").asBoolean()) {
                    stderr.append("FAIL — ").append(result.path("failed").size()).append(" task(s) incomplete:\n");
                    for (var failure : result.path("failed")) stderr.append("  - ")
                            .append(failure.path("task_id").asText()).append(": ")
                            .append(failure.path("reason").asText()).append('\n');
                    return CalibrationCommandResult.failure("", stderr.toString());
                }
                stderr.append("collected ").append(phase).append(": ")
                        .append(JSON.writeValueAsString(result)).append('\n');
                return CalibrationCommandResult.success("", stderr.toString());
            }
            if ("status".equals(command) || "next".equals(command)) {
                String runValue = option(args, "--run", null);
                if (runValue == null) throw new IllegalArgumentException("usage: status --run <dir>");
                ObjectNode status = engine.cmdStatus(CalibrationPaths.resolve(workingDirectory, runValue));
                stderr.append("run ").append(status.path("run_id").asText()).append(" (mode=")
                        .append(status.path("mode").asText()).append(")\n");
                for (String phase : CalibrationRun.PHASES) stderr.append("  ").append(padEnd(phase, 12)).append(' ')
                        .append(status.path("phases").path(phase).path("status").asText("pending")).append('\n');
                return CalibrationCommandResult.success("", stderr.toString());
            }
            return CalibrationCommandResult.failure("", USAGE);
        } catch (Exception exception) {
            stderr.append("ERROR — ").append(CalibrationPaths.message(exception)).append('\n');
            return CalibrationCommandResult.failure("", stderr.toString());
        }
    }

    private static String option(List<String> args, String name, String fallback) {
        int index = args.indexOf(name);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback;
    }

    private static String padEnd(String value, int length) {
        return value + " ".repeat(Math.max(0, length - value.length()));
    }

    private static String defaultSuffix() {
        String value = Long.toString(Math.abs(Double.doubleToLongBits(Math.random())), 36);
        return value.substring(0, Math.min(4, value.length()));
    }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), cwd, CalibrationPaths.repositoryRoot(cwd)).emitAndExit();
    }
}
