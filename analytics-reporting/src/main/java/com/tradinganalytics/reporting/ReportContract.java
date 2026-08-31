package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete Java port of {@code tools/report-contract.mjs}. */
public final class ReportContract {
    public static final String REPORT_MACHINE_V2 = ReportPaths.REPORT_MACHINE_V2;
    public static final String REPORT_MACHINE_V3 = ReportPaths.REPORT_MACHINE_V3;
    public static final String REPORT_MARKDOWN_V1 = ReportPaths.REPORT_MARKDOWN_V1;
    public static final String REPORT_PHASE_REGISTRY_V2 = ReportPaths.REPORT_PHASE_REGISTRY_V2;
    public static final Pattern REPORT_REPORT_ID_RE = ReportPaths.REPORT_REPORT_ID_RE;
    public static final Set<String> REPORT_STATUSES = ReportPaths.REPORT_STATUSES;

    private ReportContract() {
    }

    public static JsonNode parseStrictJSON(String text) {
        return StrictJson.parseStrictJSON(text);
    }

    public static JsonNode parseStrictJSON(String text, String label) {
        return StrictJson.parseStrictJSON(text, label);
    }

    public static String canonicalReportPayload(Object value) {
        return CanonicalJson.canonicalReportPayload(value);
    }

    public static String canonicalReportJSON(Object value) {
        return CanonicalJson.canonicalReportJSON(value);
    }

    public static ValidationResult validateReportMachine3(JsonNode report) {
        return validateReportMachine3(report, ValidationOptions.DEFAULT);
    }

    public static ValidationResult validateReportMachine3(JsonNode report, ValidationOptions options) {
        List<String> errors = new ArrayList<>(ReportSchemaValidator.validateV3(report));
        List<String> warnings = new ArrayList<>();
        if (errors.isEmpty()) {
            SemanticIssues issues = ReportSemanticValidator.semanticIssues3(report, normalize(options));
            errors.addAll(issues.errors());
            warnings.addAll(issues.warnings());
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings, REPORT_MACHINE_V3);
    }

    public static ValidationResult validateReportMachine2(JsonNode report) {
        return validateReportMachine2(report, ValidationOptions.DEFAULT);
    }

    public static ValidationResult validateReportMachine2(JsonNode report, ValidationOptions options) {
        List<String> errors = new ArrayList<>(ReportSchemaValidator.validateV2(report));
        List<String> warnings = new ArrayList<>();
        if (errors.isEmpty()) {
            SemanticIssues issues = ReportSemanticValidator.semanticIssues2(report, normalize(options));
            errors.addAll(issues.errors());
            warnings.addAll(issues.warnings());
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings, REPORT_MACHINE_V2);
    }

    public static LoadedReport loadAndValidateReport(Path path) throws IOException {
        return loadAndValidateReport(path, ValidationOptions.DEFAULT);
    }

    public static LoadedReport loadAndValidateReport(Path path, ValidationOptions options) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String raw = new String(bytes, StandardCharsets.UTF_8);
        String label = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        JsonNode report = parseStrictJSON(raw, label);
        ValidationOptions withFilename = new ValidationOptions(path.toString());
        ValidationResult result = REPORT_MACHINE_V3.equals(text(report, "schema"))
                ? validateReportMachine3(report, withFilename)
                : validateReportMachine2(report, withFilename);
        return new LoadedReport(result.ok(), result.errors(), result.warnings(), result.schema(), report, raw);
    }

    public static Optional<ReportPaths.ReportIdentity> reportJsonIdentity(String path) {
        return ReportPaths.reportJsonIdentity(path);
    }

    public static String reportHash(Object report) {
        return Sha256.canonicalHex(report);
    }

    public static List<String> verifySwingActivationArtifact(JsonNode report) {
        return verifySwingActivationArtifact(report, Path.of("").toAbsolutePath());
    }

    public static List<String> verifySwingActivationArtifact(JsonNode report, Path repositoryRoot) {
        return SwingActivationVerifier.verify(report, repositoryRoot);
    }

    public static String reportStem(String path) {
        return ReportPaths.reportStem(path);
    }

    public static boolean isV2Path(String path) {
        return ReportPaths.isV2Path(path);
    }

    public static boolean isInsideReports(Path path, Path repositoryRoot) {
        return ReportPaths.isInsideReports(path, repositoryRoot);
    }

    private static ValidationOptions normalize(ValidationOptions options) {
        return options == null ? ValidationOptions.DEFAULT : options;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    public record ValidationOptions(String filename) {
        public static final ValidationOptions DEFAULT = new ValidationOptions(null);
    }

    public record ValidationResult(boolean ok, List<String> errors, List<String> warnings, String schema) {
        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }

    public record LoadedReport(
            boolean ok,
            List<String> errors,
            List<String> warnings,
            String schema,
            JsonNode report,
            String raw) {
        public LoadedReport {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}

record SemanticIssues(List<String> errors, List<String> warnings) {
    SemanticIssues {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }
}
