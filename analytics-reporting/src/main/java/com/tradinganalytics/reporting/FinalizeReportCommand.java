package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Standalone, intentionally unregistered port of {@code tools/finalize-report.mjs}. */
public final class FinalizeReportCommand {
    private static final String USAGE = "usage: ./bin/analytics finalize-report <draft.json> [--out reports/<report_id>.json]\n";

    private FinalizeReportCommand() {}

    public static ReportingCommandResult run(List<String> args, Path workingDirectory, Path repositoryRoot) {
        String draftPath = args.isEmpty() ? null : args.get(0);
        int outFlag = args.indexOf("--out");
        Path outPath = outFlag >= 0 && outFlag + 1 < args.size()
                ? ReportingFiles.resolve(repositoryRoot, args.get(outFlag + 1)) : null;
        if (draftPath == null) return ReportingCommandResult.failure("", USAGE);

        JsonNode report;
        ReportContract.ValidationResult validation;
        try {
            Path draft = ReportingFiles.resolve(workingDirectory, draftPath);
            String raw = Files.readString(draft, StandardCharsets.UTF_8);
            report = ReportContract.parseStrictJSON(raw, Path.of(draftPath).getFileName().toString());
            validation = ReportContract.REPORT_MACHINE_V3.equals(ReportingJson.text(report, "schema"))
                    ? ReportContract.validateReportMachine3(report) : ReportContract.validateReportMachine2(report);
        } catch (Exception exception) {
            return ReportingCommandResult.failure("", "FAIL — " + ReportingFiles.message(exception) + "\n");
        }
        StringBuilder stderr = new StringBuilder();
        if (!validation.ok()) {
            validation.warnings().forEach(warning -> stderr.append("WARN  ").append(warning).append('\n'));
            validation.errors().forEach(error -> stderr.append("ERROR ").append(error).append('\n'));
            stderr.append("FAIL — ").append(validation.errors().size()).append(" validation error(s)\n");
            return ReportingCommandResult.failure("", stderr.toString());
        }
        if (ReportContract.REPORT_MACHINE_V3.equals(ReportingJson.text(report, "schema"))) {
            List<String> activationErrors = ReportContract.verifySwingActivationArtifact(report, repositoryRoot);
            if (!activationErrors.isEmpty()) {
                activationErrors.forEach(error -> stderr.append("ERROR ").append(error).append('\n'));
                stderr.append("FAIL — ").append(activationErrors.size()).append(" activation artifact error(s)\n");
                return ReportingCommandResult.failure("", stderr.toString());
            }
        }

        String filename = ReportingJson.text(ReportingJson.object(report, "identity"), "filename");
        if (filename == null || ReportContract.reportJsonIdentity(filename).isEmpty()) {
            return ReportingCommandResult.failure("", "FAIL — identity.filename is not a valid report filename: " + filename + "\n");
        }
        Path target = outPath != null ? outPath : repositoryRoot.resolve("reports").resolve(filename).toAbsolutePath().normalize();
        if (!ReportContract.isInsideReports(target, repositoryRoot) || !target.toString().endsWith(".json")) {
            return ReportingCommandResult.failure("", "FAIL — refusing to write outside reports/ or to a non-JSON path: " + target + "\n");
        }
        if (!target.getFileName().toString().equals(filename)) {
            return ReportingCommandResult.failure("", "FAIL — output filename " + target.getFileName() + " does not match identity.filename " + filename + "\n");
        }
        try {
            ReportingFiles.atomicWrite(target, ReportContract.canonicalReportJSON(report), ".tmp-" + ProcessHandle.current().pid());
        } catch (Exception exception) {
            return ReportingCommandResult.failure("", "FAIL — atomic write failed: " + ReportingFiles.message(exception) + "\n");
        }
        validation.warnings().forEach(warning -> stderr.append("WARN  ").append(warning).append('\n'));
        return ReportingCommandResult.success("FINALIZED " + target + "\n", stderr.toString());
    }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), cwd, ReportingFiles.repositoryRoot(cwd)).emitAndExit();
    }
}
