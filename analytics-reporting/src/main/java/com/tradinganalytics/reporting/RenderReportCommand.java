package com.tradinganalytics.reporting;

import java.nio.file.Path;
import java.util.List;

/** Standalone, intentionally unregistered port of the CLI in {@code tools/render-report.mjs}. */
public final class RenderReportCommand {
    private static final String USAGE = "usage: ./bin/analytics render-report <report.json> --mode full|summary [--out reports/<stem>.md]\n";

    private RenderReportCommand() {}

    public static ReportingCommandResult run(List<String> args, Path workingDirectory, Path repositoryRoot) {
        String input = args.isEmpty() ? null : args.get(0);
        int modeIndex = args.indexOf("--mode");
        String mode = modeIndex >= 0 && modeIndex + 1 < args.size() ? args.get(modeIndex + 1) : "full";
        int outIndex = args.indexOf("--out");
        Path out = outIndex >= 0 && outIndex + 1 < args.size()
                ? ReportingFiles.resolve(repositoryRoot, args.get(outIndex + 1)) : null;
        if (input == null || !("full".equals(mode) || "summary".equals(mode))) {
            return ReportingCommandResult.failure("", USAGE);
        }
        ReportContract.LoadedReport loaded;
        Path inputPath = ReportingFiles.resolve(workingDirectory, input);
        try {
            loaded = ReportContract.loadAndValidateReport(inputPath);
        } catch (Exception exception) {
            return ReportingCommandResult.failure("", "FAIL — " + ReportingFiles.message(exception) + "\n");
        }
        if (!loaded.ok()) {
            StringBuilder stderr = new StringBuilder();
            loaded.errors().forEach(error -> stderr.append("ERROR ").append(error).append('\n'));
            stderr.append("FAIL — ").append(loaded.errors().size()).append(" validation error(s)\n");
            return ReportingCommandResult.failure("", stderr.toString());
        }
        boolean swing = ReportContract.REPORT_MACHINE_V3.equals(ReportingJson.text(loaded.report(), "schema"));
        String rendered = swing
                ? ("summary".equals(mode) ? ReportRenderer.renderSwingSummary(loaded.report()) : ReportRenderer.renderSwingFull(loaded.report()))
                : ("summary".equals(mode) ? ReportRenderer.renderSummary(loaded.report()) : ReportRenderer.renderFull(loaded.report()));
        if ("summary".equals(mode) && out != null) {
            return ReportingCommandResult.failure("", "FAIL — summary mode writes to stdout; omit --out\n");
        }
        if ("full".equals(mode) && out == null) return ReportingCommandResult.success(rendered + "\n", "");
        if ("summary".equals(mode)) return ReportingCommandResult.success(rendered, "");
        if (!ReportContract.isInsideReports(out, repositoryRoot) || !out.toString().endsWith(".md")) {
            return ReportingCommandResult.failure("", "FAIL — refusing to write outside reports/ or to a non-Markdown path: " + out + "\n");
        }
        if (!out.getFileName().toString().equals(ReportContract.reportStem(input) + ".md")) {
            return ReportingCommandResult.failure("", "FAIL — output filename " + out.getFileName() + " does not pair with " + Path.of(input).getFileName() + "\n");
        }
        try {
            ReportingFiles.atomicWrite(out, rendered, ".tmp-" + ProcessHandle.current().pid());
        } catch (Exception exception) {
            return ReportingCommandResult.failure("", "FAIL — atomic write failed: " + ReportingFiles.message(exception) + "\n");
        }
        return ReportingCommandResult.success("RENDERED " + out + "\n", "");
    }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), cwd, ReportingFiles.repositoryRoot(cwd)).emitAndExit();
    }
}
