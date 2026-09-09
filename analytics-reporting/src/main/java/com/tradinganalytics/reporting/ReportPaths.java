package com.tradinganalytics.reporting;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Pattern;

/** Path and identity helpers preserved from {@code tools/report-contract.mjs}. */
public final class ReportPaths {
    public static final String REPORT_MACHINE_V2 = "report-machine/2";
    public static final String REPORT_MACHINE_V3 = "report-machine/3";
    public static final String REPORT_MARKDOWN_V1 = "report-markdown/1";
    public static final String REPORT_PHASE_REGISTRY_V2 = "report-phase-registry/2";
    public static final Pattern REPORT_REPORT_ID_RE = Pattern.compile(
            "^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\\d{8})_(\\d{4})$");
    public static final Pattern REPORT_ID = REPORT_REPORT_ID_RE;
    public static final Set<String> REPORT_STATUSES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "AVAILABLE", "UNKNOWN", "STALE", "EXPIRED", "NOT_COVERED", "DATA_LIMITED", "NOT_APPLICABLE")));

    private ReportPaths() {
    }

    public static Optional<ReportIdentity> reportJsonIdentity(String path) {
        String filename = basename(path);
        String stem = filename.endsWith(".json")
                ? filename.substring(0, filename.length() - ".json".length())
                : filename;
        return REPORT_REPORT_ID_RE.matcher(stem).matches()
                ? Optional.of(new ReportIdentity(stem, filename))
                : Optional.empty();
    }

    public static String reportStem(String path) {
        return basename(path).replaceFirst("\\.(json|md)$", "");
    }

    public static boolean isV2Path(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                && reportJsonIdentity(path).isPresent();
    }

    public static boolean isInsideReports(Path path, Path repositoryRoot) {
        Path reports = repositoryRoot.resolve("reports").toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        return target.equals(reports) || target.startsWith(reports);
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public record ReportIdentity(String stem, String filename) {
    }
}
