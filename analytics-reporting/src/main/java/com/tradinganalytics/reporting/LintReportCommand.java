package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Standalone, intentionally unregistered port of {@code tools/lint-report.mjs}. */
public final class LintReportCommand {
    private static final String USAGE = "usage: ./bin/analytics lint-report <report.md> [--legacy]\n";
    private static final Pattern MACHINE = Pattern.compile("```json machine\\s*\\n([\\s\\S]*?)\\n```");

    private LintReportCommand() {}

    public static ReportingCommandResult run(List<String> args, Path workingDirectory, Path repositoryRoot) {
        String file = args.isEmpty() ? null : args.get(0);
        boolean legacy = args.contains("--legacy");
        if (file == null) return ReportingCommandResult.failure("", USAGE);
        Path input = ReportingFiles.resolve(workingDirectory, file);
        String extension = extension(file).toLowerCase(Locale.ROOT);
        String companion = ".md".equals(extension) ? file.replaceFirst("\\.md$", ".json") : null;
        Path v2Path = ".json".equals(extension) ? input
                : companion != null && Files.exists(ReportingFiles.resolve(workingDirectory, companion))
                ? ReportingFiles.resolve(workingDirectory, companion) : null;
        if (v2Path != null) return lintSidecar(args, file, input, v2Path, workingDirectory, repositoryRoot);
        return LegacyReportLinter.lint(input, legacy, repositoryRoot);
    }

    private static ReportingCommandResult lintSidecar(List<String> args, String file, Path input, Path v2Path,
                                                       Path workingDirectory, Path repositoryRoot) {
        ReportContract.LoadedReport loaded;
        try { loaded = ReportContract.loadAndValidateReport(v2Path); }
        catch (Exception exception) {
            loaded = new ReportContract.LoadedReport(false, List.of(ReportingFiles.message(exception)), List.of(), null, null, null);
        }
        List<String> errors = new ArrayList<>(loaded.errors()), warnings = new ArrayList<>(loaded.warnings());
        if (loaded.ok() && ReportContract.REPORT_MACHINE_V3.equals(ReportingJson.text(loaded.report(), "schema")))
            errors.addAll(ReportContract.verifySwingActivationArtifact(loaded.report(), repositoryRoot));
        int markdownFlag = args.indexOf("--markdown");
        Path markdown = markdownFlag >= 0 && markdownFlag + 1 < args.size()
                ? ReportingFiles.resolve(workingDirectory, args.get(markdownFlag + 1))
                : ".md".equals(extension(file).toLowerCase(Locale.ROOT)) ? input
                : Path.of(v2Path.toString().replaceFirst("\\.json$", ".md"));
        if (loaded.ok() && Files.exists(markdown)) {
            try {
                String view = Files.readString(markdown, StandardCharsets.UTF_8);
                if (ReportContract.REPORT_MACHINE_V3.equals(ReportingJson.text(loaded.report(), "schema"))) {
                    if (Pattern.compile("```json machine\\s*\\n", Pattern.CASE_INSENSITIVE).matcher(view).find())
                        errors.add("report-machine/3 Markdown must not embed a canonical machine payload");
                    for (String heading : List.of("Market, evidence, and data quality", "Substitutions, source register, and provenance", "Phase registry and canonical tags", "Canonical machine payload"))
                        if (Pattern.compile("^#+\\s+" + Pattern.quote(heading), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(view).find())
                            errors.add("report-machine/3 Markdown contains removed section: " + heading);
                    String hash = ReportContract.reportHash(loaded.report()).substring(0, 16);
                    String filename = loaded.report().path("identity").path("filename").asText();
                    Pattern footer = Pattern.compile("Audit: LIVE · as-of .* · coverage (?:COMPLETE|PARTIAL) · canonical "
                            + Pattern.quote(filename) + " sha256:" + hash + " · lint PASS");
                    if (!footer.matcher(view).find()) errors.add("report-machine/3 Markdown audit footer does not match canonical sidecar hash");
                    if (!ReportContract.reportStem(markdown.toString()).equals(ReportContract.reportStem(file))) errors.add("Markdown/JSON pair stems differ");
                } else {
                    var matcher = MACHINE.matcher(view); List<String> blocks = new ArrayList<>(); while (matcher.find()) blocks.add(matcher.group(1));
                    if (blocks.size() != 1) errors.add("Markdown pair must contain exactly one json machine block (found " + blocks.size() + ")");
                    else try {
                        JsonNode embedded = ReportContract.parseStrictJSON(blocks.get(0), markdown.getFileName().toString());
                        if (!ReportContract.canonicalReportPayload(embedded).equals(ReportContract.canonicalReportPayload(loaded.report())))
                            errors.add("Markdown machine block is not canonically equal to the standalone JSON");
                        if (!ReportContract.reportStem(markdown.toString()).equals(ReportContract.reportStem(file))) errors.add("Markdown/JSON pair stems differ");
                    } catch (Exception exception) { errors.add("Markdown machine block: " + ReportingFiles.message(exception)); }
                }
            } catch (Exception exception) { errors.add(ReportingFiles.message(exception)); }
        } else if (loaded.ok() && markdownFlag >= 0) errors.add("Markdown pair not found: " + markdown);
        return finish(errors, warnings, Path.of(file).getFileName().toString(), v2Path.getFileName().toString());
    }

    static ReportingCommandResult finish(List<String> errors, List<String> warnings, String failureName, String successName) {
        StringBuilder stdout = new StringBuilder();
        warnings.forEach(warning -> stdout.append("WARN  ").append(warning).append('\n'));
        errors.forEach(error -> stdout.append("ERROR ").append(error).append('\n'));
        if (!errors.isEmpty()) {
            stdout.append("\nFAIL — ").append(errors.size()).append(" error(s), ").append(warnings.size()).append(" warning(s): ").append(failureName).append('\n');
            return ReportingCommandResult.failure(stdout.toString(), "");
        }
        stdout.append("PASS — 0 errors, ").append(warnings.size()).append(" warning(s): ").append(successName).append('\n');
        return ReportingCommandResult.success(stdout.toString(), "");
    }

    private static String extension(String path) { int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); int dot = path.lastIndexOf('.'); return dot > slash ? path.substring(dot) : ""; }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), cwd, ReportingFiles.repositoryRoot(cwd)).emitAndExit();
    }
}
