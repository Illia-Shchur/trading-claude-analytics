package com.tradinganalytics.research.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.core.lib.ToolchainSupport;
import com.tradinganalytics.reporting.ReportContract;
import com.tradinganalytics.reporting.ReportRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native standalone adapter for {@code tools/calib-corpus.mjs}. */
public final class CalibrationCorpusCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter NODE_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String USAGE = "usage: ./bin/analytics calib-corpus --since YYYY-MM-DD [--until YYYY-MM-DD] [--framework fallen_knives|flying_rocket] [--asset btc,eth,gold] [--max-per-series N] [--out .calib-run/<dir>]\n";

    private CalibrationCorpusCommand() {}

    public static CalibrationCommandResult run(List<String> args, Path repositoryRoot) {
        return run(args, repositoryRoot, Instant.now());
    }

    public static CalibrationCommandResult run(List<String> args, Path repositoryRoot, Instant generatedAt) {
        String since = option(args, "--since", null);
        String until = option(args, "--until", null);
        String framework = option(args, "--framework", null);
        List<String> assets = commaList(option(args, "--asset", null), true);
        Path out = CalibrationPaths.resolve(repositoryRoot,
                option(args, "--out", ".calib-run/" + (since == null ? "all" : since)));
        int maxPerSeries = parseInt(option(args, "--max-per-series", null), 12);
        if (since == null) return CalibrationCommandResult.failure("", USAGE);
        if (framework != null && !(framework.equals("fallen_knives") || framework.equals("flying_rocket")))
            return CalibrationCommandResult.failure("", "--framework must be fallen_knives or flying_rocket, got \"" + framework + "\"\n");

        Path reports = repositoryRoot.resolve("reports").toAbsolutePath().normalize();
        if (!Files.exists(reports))
            return CalibrationCommandResult.failure("", "reports dir not found: " + reports + "\n");
        try {
            return execute(since, until, framework, assets, out, maxPerSeries, reports, generatedAt);
        } catch (Exception exception) {
            return CalibrationCommandResult.failure("", CalibrationPaths.message(exception) + "\n");
        }
    }

    private static CalibrationCommandResult execute(String since, String until, String framework, List<String> assets,
                                                     Path out, int maxPerSeries, Path reports, Instant generatedAt) throws Exception {
        CalibrationCorpus operations = new CalibrationCorpus(JSON);
        List<String> reportFiles;
        try (var stream = Files.list(reports)) {
            reportFiles = stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md") || name.endsWith(".json")).sorted().toList();
        }
        Set<String> stemSet = new LinkedHashSet<>();
        for (String file : reportFiles) stemSet.add(file.replaceFirst("\\.(?:md|json)$", ""));
        List<String> stems = stemSet.stream().sorted().toList();
        List<Candidate> candidates = new ArrayList<>();
        ArrayNode ignored = JSON.createArrayNode();

        for (String stem : stems) {
            String jsonFile = stem + ".json", mdFile = stem + ".md";
            if (Files.exists(reports.resolve(jsonFile)) && ReportContract.reportJsonIdentity(jsonFile).isPresent()) {
                ReportContract.LoadedReport loaded;
                try { loaded = ReportContract.loadAndValidateReport(reports.resolve(jsonFile)); }
                catch (Exception exception) {
                    ignored.add(ignored(jsonFile, "invalid report-machine/2: " + CalibrationPaths.message(exception)));
                    continue;
                }
                if (!loaded.ok()) {
                    ignored.add(ignored(jsonFile, String.join("; ", loaded.errors())));
                    continue;
                }
                JsonNode report = loaded.report();
                String asset = text(report, "identity", "asset");
                if ("COMBINED".equals(asset)) asset = "MULTI";
                String date = text(report, "identity", "date");
                String reportFramework = text(report, "identity", "framework");
                if (!matches(date, reportFramework, asset, since, until, framework, assets)) continue;
                String canonical = ReportContract.canonicalReportPayload(report);
                candidates.add(new Candidate(jsonFile, asset, reportFramework, date,
                        text(report, "timestamps", "report_at"), "report_machine_2", null,
                        operations.projectV2Digest(report), true, canonical, ReportRenderer.renderSummary(report),
                        Files.exists(reports.resolve(mdFile)) ? mdFile : null));
                continue;
            }

            if (!Files.exists(reports.resolve(mdFile))) continue;
            ObjectNode meta = ToolchainSupport.reportFileMeta(mdFile);
            if (!meta.path("ok").asBoolean()) {
                ignored.add(ignored(mdFile, meta.path("reason").asText()));
                continue;
            }
            String asset = meta.path("asset").asText();
            if ("COMBINED".equals(asset)) asset = "MULTI";
            String date = meta.path("date").asText(), reportFramework = meta.path("framework").asText();
            if (!matches(date, reportFramework, asset, since, until, framework, assets)) continue;
            String raw = Files.readString(reports.resolve(mdFile), StandardCharsets.UTF_8);
            CalibrationCorpus.DropResult machine = operations.dropMachineBlock(raw);
            JsonNode digest = machine.dropped() == null ? null : operations.projectDigest(machine.dropped().raw());
            candidates.add(new Candidate(mdFile, asset, reportFramework, date, meta.path("at_utc").asText(),
                    meta.path("schema_epoch").asText(), raw, digest, false, null, null, null));
        }
        candidates.sort(java.util.Comparator.comparing(candidate -> candidate.date));

        Map<String, List<Candidate>> bySeries = new LinkedHashMap<>();
        for (Candidate candidate : candidates)
            bySeries.computeIfAbsent(candidate.framework + "|" + candidate.asset, ignoredKey -> new ArrayList<>()).add(candidate);
        List<Sampled> sampled = new ArrayList<>();
        List<String> capExceeded = new ArrayList<>();
        Set<Candidate> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, List<Candidate>> seriesEntry : bySeries.entrySet()) {
            List<JsonNode> values = seriesEntry.getValue().stream().map(Candidate::selectionNode).toList();
            CalibrationCorpus.Selection selection = operations.selectWithCap(values, maxPerSeries);
            for (int index : selection.keptIndexes()) kept.add(seriesEntry.getValue().get(index));
            for (String file : selection.sampledOut()) sampled.add(new Sampled(file, seriesEntry.getKey()));
            if (selection.capExceededByEvents()) capExceeded.add(seriesEntry.getKey());
        }
        List<Candidate> selected = candidates.stream().filter(kept::contains).toList();
        if (selected.isEmpty()) {
            String message = "no reports matched --since " + since
                    + (until == null ? "" : " --until " + until)
                    + (framework == null ? "" : " --framework " + framework)
                    + (assets.isEmpty() ? "" : " --asset " + String.join(",", assets)) + "\n";
            return CalibrationCommandResult.failure("", message);
        }

        ArrayNode corpus = JSON.createArrayNode();
        long bytesTotal = 0, bytesSliced = 0;
        int withMachine = 0, withoutMachine = 0, sectionFailures = 0;
        StringBuilder stderr = new StringBuilder();
        Files.createDirectories(out);
        for (Candidate candidate : selected) {
            if (candidate.v2) {
                long total = bytes(candidate.canonical);
                String slice = "<!-- calib-corpus v2 summary for " + candidate.file
                        + "; canonical JSON remains authoritative and the Markdown view is optional. -->\n\n" + candidate.summary;
                long sliced = bytes(slice);
                bytesTotal += total; bytesSliced += sliced; withMachine++;
                ObjectNode entry = identity(candidate);
                entry.put("source_schema", "report-machine/2"); entry.put("canonical_file", candidate.file);
                if (candidate.viewFile == null) entry.set("view_file", NullNode.instance); else entry.put("view_file", candidate.viewFile);
                entry.put("bytes_total", total); entry.put("bytes_slice", sliced);
                ObjectNode machine = entry.putObject("machine_block");
                machine.put("present", true); machine.put("standalone_json", true); machine.put("bytes", total);
                machine.put("sha256", Sha256.hex(candidate.canonical));
                entry.set("verified_data_section", note(false, "v2 summary generated from structured JSON"));
                entry.set("composite_score_section", note(false, "v2 summary generated from structured JSON"));
                entry.put("bytes_dropped", 0); entry.put("reduction_pct", 0); entry.put("byte_reconciliation_ok", true);
                corpus.add(entry);
                Files.writeString(out.resolve(candidate.file + ".slice.md"), slice, StandardCharsets.UTF_8);
                Files.writeString(out.resolve(candidate.file + ".digest.json"), ToolchainSupport.canonicalJSON(candidate.digest) + "\n", StandardCharsets.UTF_8);
                continue;
            }

            long total = bytes(candidate.raw); bytesTotal += total;
            CalibrationCorpus.DropResult machine = operations.dropMachineBlock(candidate.raw);
            CalibrationCorpus.DropResult verified = operations.dropVerifiedDataSection(machine.text());
            CalibrationCorpus.DropResult composite = operations.dropCompositeScoreSection(verified.text());
            if (machine.dropped() == null) withoutMachine++; else withMachine++;
            if (verified.dropped() == null) sectionFailures++;
            String sliceText = composite.text();
            long sliced = bytes(sliceText); bytesSliced += sliced;
            long dropped = droppedBytes(machine) + droppedBytes(verified) + droppedBytes(composite);
            boolean reconciled = Math.abs(sliceText.getBytes(StandardCharsets.UTF_8).length + dropped - total) <= 8;
            ObjectNode entry = identity(candidate);
            entry.put("bytes_total", total); entry.put("bytes_slice", sliced);
            if (machine.dropped() == null) entry.set("machine_block", present(false));
            else {
                ObjectNode value = present(true); value.put("bytes", machine.dropped().bytes()); value.put("sha256", machine.dropped().sha256());
                entry.set("machine_block", value);
            }
            if (verified.dropped() == null)
                entry.set("verified_data_section", note(false, "no matching heading found — nothing dropped from this section, report passed through further than usual"));
            else entry.set("verified_data_section", section(verified.dropped()));
            entry.set("composite_score_section", composite.dropped() == null ? present(false) : section(composite.dropped()));
            entry.put("bytes_dropped", dropped);
            entry.put("reduction_pct", total == 0 ? 0 : round1((double) dropped / total * 100));
            entry.put("byte_reconciliation_ok", reconciled);
            corpus.add(entry);

            String header = "<!-- calib-corpus slice of " + candidate.file + ". Dropped: "
                    + (machine.dropped() == null ? "no machine block" : "machine block (" + machine.dropped().bytes() + "B)")
                    + (verified.dropped() == null ? ", no verified-data section matched"
                    : ", \"" + verified.dropped().heading() + "\" section (" + verified.dropped().bytes() + "B)")
                    + (composite.dropped() == null ? "" : ", \"" + composite.dropped().heading() + "\" section (" + composite.dropped().bytes() + "B)")
                    + " -->\n\n";
            Files.writeString(out.resolve(candidate.file + ".slice.md"), header + sliceText, StandardCharsets.UTF_8);
            if (candidate.digest != null)
                Files.writeString(out.resolve(candidate.file + ".digest.json"), ToolchainSupport.canonicalJSON(candidate.digest) + "\n", StandardCharsets.UTF_8);
            if (!reconciled) stderr.append("WARNING — byte reconciliation failed for ").append(candidate.file)
                    .append(": total=").append(total).append(" slice=").append(sliced).append(" dropped=").append(dropped).append('\n');
        }

        ObjectNode filters = JSON.createObjectNode(); filters.put("since", since);
        if (until == null) filters.set("until", NullNode.instance); else filters.put("until", until);
        if (framework == null) filters.set("framework", NullNode.instance); else filters.put("framework", framework);
        if (assets.isEmpty()) filters.set("asset", NullNode.instance); else filters.set("asset", strings(assets));
        ObjectNode corpusFile = JSON.createObjectNode(); corpusFile.put("schema", "calib-corpus/1");
        corpusFile.set("filters", filters); corpusFile.set("reports", corpus);
        Files.writeString(out.resolve("corpus.json"), ToolchainSupport.canonicalJSON(corpusFile) + "\n", StandardCharsets.UTF_8);

        ObjectNode manifest = JSON.createObjectNode(); manifest.put("schema", "calib-corpus-manifest/1");
        manifest.put("generated_at", NODE_ISO.format(generatedAt));
        ObjectNode manifestFilters = filters.deepCopy(); manifestFilters.put("max_per_series", maxPerSeries);
        manifest.set("filters", manifestFilters); manifest.put("out_dir", out.toString());
        ObjectNode counts = manifest.putObject("counts"); counts.put("reports_selected", corpus.size());
        counts.put("with_machine_block", withMachine); counts.put("without_machine_block", withoutMachine);
        counts.put("verified_data_section_not_matched", sectionFailures); counts.put("files_ignored_non_report", ignored.size());
        counts.put("sampled_out_by_cap", sampled.size());
        ObjectNode byteCounts = manifest.putObject("bytes"); byteCounts.put("total", bytesTotal); byteCounts.put("sliced", bytesSliced);
        byteCounts.put("dropped", bytesTotal - bytesSliced);
        double reduction = bytesTotal == 0 ? 0 : round1((double) (bytesTotal - bytesSliced) / bytesTotal * 100);
        byteCounts.put("reduction_pct", reduction);
        ArrayNode reconciliation = manifest.putArray("byte_reconciliation_failures");
        for (JsonNode entry : corpus) if (!entry.path("byte_reconciliation_ok").asBoolean()) reconciliation.add(entry.path("f").asText());
        ArrayNode ignoredSample = manifest.putArray("ignored_files_sample");
        for (int index = 0; index < Math.min(10, ignored.size()); index++) ignoredSample.add(ignored.get(index));
        ArrayNode sampledJson = manifest.putArray("sampled_out");
        sampled.forEach(value -> { ObjectNode row = sampledJson.addObject(); row.put("file", value.file); row.put("series", value.series); });
        manifest.set("cap_exceeded_by_events", strings(capExceeded));
        Files.writeString(out.resolve("manifest.json"), ToolchainSupport.canonicalJSON(manifest) + "\n", StandardCharsets.UTF_8);

        stderr.append("calib-corpus: ").append(corpus.size()).append(" report(s) selected, ").append(withMachine)
                .append(" with machine block, ").append(withoutMachine).append(" without.\n");
        stderr.append("  bytes: ").append(bytesTotal).append(" -> ").append(bytesSliced).append(" (")
                .append(jsNumber(reduction)).append("% reduction)\n");
        if (sectionFailures > 0) stderr.append("  WARNING — ").append(sectionFailures)
                .append(" report(s) had no matching \"Verified Live Data\" heading — passed through further than usual\n");
        if (!reconciliation.isEmpty()) stderr.append("  WARNING — byte reconciliation failed for: ")
                .append(joinText(reconciliation)).append('\n');
        if (!sampled.isEmpty()) stderr.append("  --max-per-series ").append(maxPerSeries).append(": sampled out ")
                .append(sampled.size()).append(" report(s) — see manifest.json \"sampled_out\"\n");
        if (!capExceeded.isEmpty()) stderr.append("  --max-per-series ").append(maxPerSeries)
                .append(": cap non-binding (event reports alone exceeded it) for: ").append(String.join(",", capExceeded)).append('\n');
        stderr.append("  wrote ").append(out).append("/corpus.json, manifest.json, and per-report .slice.md")
                .append(withMachine > 0 ? "/.digest.json" : "").append('\n');
        return CalibrationCommandResult.success("", stderr.toString());
    }

    private static boolean matches(String date, String reportFramework, String asset, String since, String until,
                                   String framework, List<String> assets) {
        return date.compareTo(since) >= 0 && (until == null || date.compareTo(until) <= 0)
                && (framework == null || framework.equals(reportFramework))
                && (assets.isEmpty() || assets.contains(asset) || "MULTI".equals(asset));
    }

    private static ObjectNode identity(Candidate candidate) {
        ObjectNode value = JSON.createObjectNode(); value.put("f", candidate.file); value.put("a", candidate.asset);
        value.put("t", candidate.framework); value.put("d", candidate.date); value.put("at_utc", candidate.atUtc);
        value.put("schema_epoch", candidate.schemaEpoch); return value;
    }

    private static ObjectNode ignored(String file, String reason) { ObjectNode value = JSON.createObjectNode(); value.put("file", file); value.put("reason", reason); return value; }
    private static ObjectNode present(boolean value) { ObjectNode result = JSON.createObjectNode(); result.put("present", value); return result; }
    private static ObjectNode note(boolean present, String note) { ObjectNode result = present(present); result.put("note", note); return result; }
    private static ObjectNode section(CalibrationCorpus.Dropped dropped) { ObjectNode result = present(true); result.put("bytes", dropped.bytes()); result.put("heading", dropped.heading()); return result; }
    private static long droppedBytes(CalibrationCorpus.DropResult value) { return value.dropped() == null ? 0 : value.dropped().bytes(); }
    private static long bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static double round1(double value) { return Math.round(value * 10) / 10d; }
    private static String jsNumber(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }
    private static String joinText(ArrayNode values) { List<String> result = new ArrayList<>(); values.forEach(value -> result.add(value.asText())); return String.join(",", result); }
    private static ArrayNode strings(List<String> values) { ArrayNode result = JSON.createArrayNode(); values.forEach(result::add); return result; }
    private static String text(JsonNode value, String... fields) { JsonNode cursor = value; for (String field : fields) cursor = cursor.path(field); return cursor.asText(); }
    private static String option(List<String> args, String name, String fallback) { int index = args.indexOf(name); return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback; }
    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        var matcher = java.util.regex.Pattern.compile("^[+-]?\\d+").matcher(value.stripLeading());
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group()); }
        catch (NumberFormatException overflow) { return matcher.group().startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE; }
    }
    private static List<String> commaList(String value, boolean upper) { if (value == null || value.isEmpty()) return List.of(); List<String> result = new ArrayList<>(); for (String part : value.split(",", -1)) { String item = part.trim(); if (!item.isEmpty()) result.add(upper ? item.toUpperCase(java.util.Locale.ROOT) : item); } return List.copyOf(result); }

    private record Sampled(String file, String series) {}
    private record Candidate(String file, String asset, String framework, String date, String atUtc,
                             String schemaEpoch, String raw, JsonNode digest, boolean v2, String canonical,
                             String summary, String viewFile) {
        JsonNode selectionNode() {
            ObjectNode value = JSON.createObjectNode(); value.put("f", file); value.put("t", framework);
            if (digest == null) value.set("digest", NullNode.instance); else value.set("digest", digest);
            return value;
        }
    }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), CalibrationPaths.repositoryRoot(cwd)).emitAndExit();
    }
}
