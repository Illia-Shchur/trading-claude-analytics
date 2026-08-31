package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native standalone port of {@code tools/export-signals.mjs}. */
public final class ExportSignalsCommand {
    public static final String SIGNAL_FEED_SCHEMA = "signal-feed/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PAIR_BLOCK = Pattern.compile("```json machine\\s*\\n([\\s\\S]*?)\\n```", Pattern.MULTILINE);
    private static final Pattern LEGACY_BLOCK = Pattern.compile("```json machine\\s*\\n([\\s\\S]*?)```");
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final String AT_UTC_NOTE = "derived by interpreting the filename's HHMM in " + ToolchainSupport.REPORT_ZONE
            + " (the repo's Output Convention) — the machine block carries a date but no time field, and (asset, framework, date) collides "
            + "(btc/eth × fallen_knives on 2026-07-14 and 2026-07-18), so report_file is the primary key";
    private static final String FILL_DETECTION = "deployed === true || typeof entry_price === \"number\" || typeof entry === \"number\"";
    private static final String FILL_CAVEAT = "before the entry_price epoch (" + ToolchainSupport.ENTRY_PRICE_EPOCH
            + ") no tranche in this corpus carried deployed:true or a numeric entry — 152/152 tranches across 39 reports encode `entry` as prose — so filled_tranche_count is "
            + "structurally 0 on every pre-epoch signal. It is an artifact of the old schema, not an observation that nothing was filled.";

    private ExportSignalsCommand() {}

    public static ReportingCommandResult run(List<String> args, Path repositoryRoot) {
        return run(args, repositoryRoot, Instant.now());
    }

    public static ReportingCommandResult run(List<String> args, Path repositoryRoot, Instant generatedAt) {
        boolean dryRun = args.contains("--dry-run"), strict = args.contains("--strict");
        Path reportsDir = ReportingFiles.resolve(repositoryRoot, option(args, "--reports", "reports"));
        Path outPath = ReportingFiles.resolve(repositoryRoot, option(args, "--out", "exports/signal-feed.json"));
        Path exportsDir = repositoryRoot.resolve("exports").toAbsolutePath().normalize();
        if (!(outPath.equals(exportsDir) || outPath.startsWith(exportsDir))) {
            return ReportingCommandResult.failure("", "refusing to write outside exports/: " + outPath + "\n");
        }

        Projection projection;
        try { projection = project(reportsDir, generatedAt); }
        catch (Exception exception) { return ReportingCommandResult.failure("", ReportingFiles.message(exception) + "\n"); }
        ObjectNode feed = projection.feed();
        ObjectNode counts = (ObjectNode) feed.get("counts");
        StringBuilder stderr = new StringBuilder();
        stderr.append("scanned ").append(counts.path("files_in_reports_dir").asInt()).append(" files in ").append(reportsDir).append('\n');
        stderr.append("  ").append(counts.path("framework_reports").asInt()).append(" framework reports, ")
                .append(counts.path("non_framework_files_ignored").asInt()).append(" ignored by filename (")
                .append(projection.ignoredNames().isEmpty() ? "none" : String.join(", ", projection.ignoredNames())).append(")\n");
        stderr.append("  ").append(counts.path("signals").asInt()).append(" signals, ")
                .append(counts.path("skipped_no_machine_block").asInt()).append(" skipped (no machine block), ")
                .append(counts.path("skipped_unparseable").asInt()).append(" unparseable\n");
        int postEpoch = counts.path("skipped_post_epoch_missing_block").asInt();
        if (postEpoch > 0) stderr.append("  ").append(postEpoch).append(" report(s) dated ≥ ")
                .append(ToolchainSupport.MACHINE_BLOCK_EPOCH).append(" carry NO machine block — a real gap\n");
        else stderr.append("  every skip predates the machine-block epoch (").append(ToolchainSupport.MACHINE_BLOCK_EPOCH)
                .append(") — expected, not a failure\n");

        String stdout = "";
        if (dryRun) stdout = pretty(counts, 0) + "\n";
        else {
            try {
                Files.createDirectories(outPath.getParent());
                String previous = Files.exists(outPath) ? Files.readString(outPath, StandardCharsets.UTF_8) : null;
                ObjectNode change = ToolchainSupport.feedChanged(previous, feed);
                if (!change.path("changed").asBoolean()) {
                    stderr.append("unchanged — ").append(outPath.getFileName()).append(" left untouched (")
                            .append(change.path("reason").asText()).append(")\n");
                } else {
                    try {
                        ReportingFiles.atomicWrite(outPath, ToolchainSupport.canonicalJSON(feed), ".tmp");
                    } catch (Exception exception) {
                        stderr.append("write failed: ").append(ReportingFiles.message(exception)).append('\n');
                        return ReportingCommandResult.failure(stdout, stderr.toString());
                    }
                    stderr.append("wrote ").append(outPath).append(" (").append(change.path("reason").asText()).append(")\n");
                }
            } catch (Exception exception) {
                stderr.append("write failed: ").append(ReportingFiles.message(exception)).append('\n');
                return ReportingCommandResult.failure(stdout, stderr.toString());
            }
        }
        if (strict && postEpoch > 0) {
            stderr.append("\nFAIL (--strict) — ").append(postEpoch).append(" report(s) on/after ")
                    .append(ToolchainSupport.MACHINE_BLOCK_EPOCH).append(" lack a machine block\n");
            return ReportingCommandResult.failure(stdout, stderr.toString());
        }
        int unparseable = counts.path("skipped_unparseable").asInt();
        if (strict && unparseable > 0) {
            stderr.append("\nFAIL (--strict) — ").append(unparseable).append(" machine block(s) failed to parse or project\n");
            return ReportingCommandResult.failure(stdout, stderr.toString());
        }
        int mismatches = feed.withArray("mismatched_v2_pairs").size();
        if (strict && mismatches > 0) {
            stderr.append("\nFAIL (--strict) — ").append(mismatches).append(" v2 JSON/Markdown pair(s) are not canonically equal\n");
            return ReportingCommandResult.failure(stdout, stderr.toString());
        }
        return ReportingCommandResult.success(stdout, stderr.toString());
    }

    /** Pure-ish projection API used by compatibility tests; only reads {@code reportsDir}. */
    public static Projection project(Path reportsDir, Instant generatedAt) throws Exception {
        List<String> allReportFiles;
        try (var stream = Files.list(reportsDir)) {
            allReportFiles = stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md") || name.endsWith(".json")).sorted().toList();
        }
        Set<String> stemSet = new LinkedHashSet<>();
        for (String file : allReportFiles) stemSet.add(file.replaceFirst("\\.(?:md|json)$", ""));
        List<String> stems = stemSet.stream().sorted().toList();
        ArrayNode signals = ReportingJson.NODES.arrayNode(), skipped = ReportingJson.NODES.arrayNode(), ignored = ReportingJson.NODES.arrayNode();
        ArrayNode orphaned = ReportingJson.NODES.arrayNode(), mismatched = ReportingJson.NODES.arrayNode();
        int unparseable = 0, postEpochMissing = 0;

        for (String stem : stems) {
            String mdFile = stem + ".md", jsonFile = stem + ".json";
            boolean jsonExists = Files.exists(reportsDir.resolve(jsonFile)) && ReportContract.reportJsonIdentity(jsonFile).isPresent();
            boolean mdExists = Files.exists(reportsDir.resolve(mdFile));
            if (jsonExists) {
                ReportContract.LoadedReport loaded;
                try { loaded = ReportContract.loadAndValidateReport(reportsDir.resolve(jsonFile)); }
                catch (Exception exception) {
                    unparseable++;
                    skipped.add(skip(jsonFile, null, "invalid_report_machine_2", ReportingFiles.message(exception)));
                    continue;
                }
                if (!loaded.ok()) {
                    unparseable++;
                    skipped.add(skip(jsonFile, null, "invalid_report_machine_2", String.join("; ", loaded.errors())));
                    continue;
                }
                JsonNode report = loaded.report();
                if (!mdExists) orphaned.add(two("file", jsonFile, "reason", "canonical JSON has no Markdown view"));
                else comparePair(reportsDir.resolve(mdFile), mdFile, jsonFile, report, mismatched);
                ObjectNode identity = (ObjectNode) report.path("identity");
                ObjectNode meta = ReportingJson.NODES.objectNode();
                meta.put("ok", true); meta.put("file", mdFile); meta.put("canonical_file", jsonFile);
                if (mdExists) meta.put("view_file", mdFile); else meta.set("view_file", NullNode.instance);
                copyText(meta, "asset", identity.get("asset")); copyText(meta, "framework", identity.get("framework"));
                copyText(meta, "date", identity.get("date")); copyText(meta, "local_time", identity.get("local_time"));
                copyText(meta, "zone", identity.get("timezone")); copyText(meta, "at_utc", report.path("timestamps").get("report_at"));
                meta.put("schema_epoch", "report-machine/3".equals(report.path("schema").asText()) ? "report_machine_3" : "report_machine_2");
                meta.put("stem", stem);
                try {
                    String contentSha = Sha256.hex(ReportContract.canonicalReportPayload(report));
                    signals.add("report-machine/3".equals(report.path("schema").asText())
                            ? toV3Signal(meta, report, contentSha) : toV2Signal(meta, report, contentSha));
                } catch (Exception exception) {
                    unparseable++;
                    skipped.add(skip(jsonFile, meta.path("date").asText(), "projection_failed", ReportingFiles.message(exception)));
                }
                continue;
            }

            ObjectNode meta = ToolchainSupport.reportFileMeta(mdFile);
            if (!meta.path("ok").asBoolean()) {
                ignored.add(two("file", mdExists ? mdFile : jsonFile, "reason", meta.path("reason").asText()));
                continue;
            }
            String markdown = Files.readString(reportsDir.resolve(mdFile), StandardCharsets.UTF_8);
            Matcher block = LEGACY_BLOCK.matcher(markdown);
            if (!block.find()) {
                boolean post = meta.path("date").asText().compareTo(ToolchainSupport.MACHINE_BLOCK_EPOCH) >= 0;
                if (post) postEpochMissing++;
                skipped.add(skip(mdFile, meta.path("date").asText(), "no_machine_block", post
                        ? "dated on/after the machine-block epoch (" + ToolchainSupport.MACHINE_BLOCK_EPOCH + ") but carries no block — this is a real gap"
                        : "dated before the machine-block epoch (" + ToolchainSupport.MACHINE_BLOCK_EPOCH + ") — prose-only report, expected"));
                continue;
            }
            JsonNode body;
            try { body = JSON.readTree(block.group(1)); }
            catch (Exception exception) {
                unparseable++;
                skipped.add(skip(mdFile, meta.path("date").asText(), "unparseable_machine_block", ReportingFiles.message(exception)));
                continue;
            }
            try {
                meta.set("canonical_file", NullNode.instance); meta.put("view_file", mdFile);
                signals.add(toSignal(meta, body, Sha256.hex(block.group(1))));
            } catch (Exception exception) {
                unparseable++;
                skipped.add(skip(mdFile, meta.path("date").asText(), "projection_failed", ReportingFiles.message(exception)));
            }
        }

        List<JsonNode> sortedSignals = ReportingJson.elements(signals);
        sortedSignals.sort(Comparator.comparing((JsonNode value) -> value.path("report_at_utc").asText())
                .thenComparing(value -> value.path("report_file").asText()));
        signals.removeAll(); sortedSignals.forEach(signals::add);
        sortByFile(skipped); sortByFile(ignored);

        ObjectNode counts = ReportingJson.NODES.objectNode();
        counts.put("files_in_reports_dir", stems.size());
        counts.put("framework_reports", signals.size() + skipped.size());
        counts.put("non_framework_files_ignored", ignored.size());
        counts.put("signals", signals.size());
        counts.put("skipped_no_machine_block", countReason(skipped, "no_machine_block"));
        counts.put("skipped_unparseable", unparseable);
        counts.put("skipped_post_epoch_missing_block", postEpochMissing);
        counts.put("v2_signals", countField(signals, "source_schema", "report-machine/2"));
        counts.put("v3_signals", countField(signals, "source_schema", "report-machine/3"));
        counts.put("orphaned_v2", orphaned.size());
        counts.put("mismatched_v2_pairs", mismatched.size());

        ObjectNode epochs = ReportingJson.NODES.objectNode();
        epochs.put("machine_block", ToolchainSupport.MACHINE_BLOCK_EPOCH);
        epochs.put("discretion_and_two_channel", ToolchainSupport.DISCRETION_EPOCH);
        epochs.put("entry_price", ToolchainSupport.ENTRY_PRICE_EPOCH);
        epochs.put("report_phase_registry", ReportPhaseRegistry.SCHEMA);
        ObjectNode feed = ReportingJson.NODES.objectNode();
        feed.put("schema", SIGNAL_FEED_SCHEMA);
        feed.put("generated_at", generatedAt.truncatedTo(ChronoUnit.SECONDS).toString());
        feed.put("generated_by", "tools/export-signals.mjs");
        feed.set("epochs", epochs);
        feed.put("encoding", "decimal quantities (prices, scores, percentages, EV) are JSON strings in plain decimal notation; counts, gate numbers, booleans and enums are native");
        feed.set("counts", counts); feed.set("skipped", skipped); feed.set("ignored_files", ignored);
        feed.set("orphaned_v2", orphaned); feed.set("mismatched_v2_pairs", mismatched); feed.set("signals", signals);
        List<String> ignoredNames = new ArrayList<>(); ignored.forEach(value -> ignoredNames.add(value.path("file").asText()));
        return new Projection(feed, ignoredNames);
    }

    private static void comparePair(Path markdownPath, String mdFile, String jsonFile, JsonNode report, ArrayNode mismatched) throws Exception {
        String view = Files.readString(markdownPath, StandardCharsets.UTF_8);
        Matcher matcher = PAIR_BLOCK.matcher(view); List<String> blocks = new ArrayList<>(); while (matcher.find()) blocks.add(matcher.group(1));
        if ("report-machine/3".equals(report.path("schema").asText())) {
            if (!blocks.isEmpty()) mismatched.add(pair(jsonFile, mdFile, "report-machine/3 must not embed a machine block"));
            String hash = Sha256.hex(ReportContract.canonicalReportPayload(report)).substring(0, 16);
            if (!Pattern.compile("sha256:" + hash + " \\u00b7 lint PASS").matcher(view).find()) mismatched.add(pair(jsonFile, mdFile, "report-machine/3 audit footer hash mismatch"));
        } else if (blocks.size() != 1) mismatched.add(pair(jsonFile, mdFile, "expected one machine block, found " + blocks.size()));
        else {
            try {
                JsonNode embedded = ReportContract.parseStrictJSON(blocks.get(0), mdFile);
                if (!ReportContract.canonicalReportPayload(embedded).equals(ReportContract.canonicalReportPayload(report))) mismatched.add(pair(jsonFile, mdFile, "machine block differs from canonical JSON"));
            } catch (Exception exception) { mismatched.add(pair(jsonFile, mdFile, ReportingFiles.message(exception))); }
        }
    }

    private static ObjectNode legacyTaggingFromV2(JsonNode tagging, JsonNode meta, JsonNode report) {
        ArrayNode entries = ReportingJson.NODES.arrayNode();
        for (JsonNode source : ReportingJson.array(tagging, "entries")) {
            ObjectNode entry = entries.addObject(); copy(entry, source, "phase", "canonical_tag", "decision", "instrument_class");
            entry.put("report_file", meta.path("file").asText()); entry.put("report_version", "report-machine/2");
            entry.put("asset", report.path("identity").path("asset").asText()); entry.put("report_date", report.path("identity").path("date").asText());
            entry.put("report_local_time", report.path("identity").path("local_time").asText());
        }
        ObjectNode registry = ReportingJson.NODES.objectNode();
        registry.put("schema", ReportPhaseRegistry.SCHEMA); registry.put("version", 1); registry.put("report_file", meta.path("file").asText());
        registry.put("report_version", "report-machine/2"); registry.put("framework", report.path("identity").path("framework").asText());
        setNullable(registry, "channel", report.get("channel")); registry.put("asset", report.path("identity").path("asset").asText());
        registry.put("report_date", report.path("identity").path("date").asText()); registry.put("report_local_time", report.path("identity").path("local_time").asText());
        registry.put("report_zone", meta.path("zone").asText()); copyField(registry, "instrument_class", tagging.get("instrument_class")); registry.set("entries", entries);
        ObjectNode output = ReportingJson.NODES.objectNode();
        output.put("mode", "phase_registry"); output.set("registry", registry); copyField(output, "instrument_class", tagging.get("instrument_class"));
        output.put("report_file", meta.path("file").asText()); output.put("report_version", "report-machine/2"); output.put("framework", report.path("identity").path("framework").asText());
        setNullable(output, "channel", report.get("channel")); output.put("report_asset", report.path("identity").path("asset").asText());
        output.put("report_date", report.path("identity").path("date").asText()); output.put("report_local_time", report.path("identity").path("local_time").asText());
        copyField(output, "active_tags", tagging.get("active_tags")); copyField(output, "reserved_tags", tagging.get("reserved_tags")); copyField(output, "status", tagging.get("status"));
        return output;
    }

    private static ObjectNode toV2Signal(ObjectNode meta, JsonNode report, String contentSha) {
        JsonNode score = report.path("score");
        ObjectNode legacy = ReportingJson.NODES.objectNode();
        legacy.put("schema", "report-machine/1"); legacy.put("framework", report.path("identity").path("framework").asText()); legacy.put("asset", report.path("identity").path("asset").asText());
        legacy.put("date", report.path("identity").path("date").asText()); setNullable(legacy, "channel", report.get("channel"));
        ObjectNode spot = legacy.putObject("spot"); copyField(spot, "value", report.path("market").path("spot").get("value"));
        spot.put("source", join(report.path("market").path("spot").path("source_ids"), ","));
        ObjectNode legacyScore = legacy.putObject("score"); copyField(legacyScore, "legs", score.get("legs")); copyField(legacyScore, "discretionary", score.get("discretion"));
        copyField(legacyScore, "mechanical", score.get("mechanical")); copyField(legacyScore, "raw", score.get("raw")); copyField(legacyScore, "adjusted", score.get("adjusted")); copyField(legacyScore, "rounding", score.get("rounding"));
        double penalty = 0; for (JsonNode value : score.path("penalties")) penalty += value.asDouble(); putJsNumber(legacyScore, "penalty", penalty);
        ObjectNode gates = legacy.putObject("gates"); copy(gates, report.path("gates"), "active", "na", "passed");
        ObjectNode ev = legacy.putObject("ev"); putNumberOrNull(ev, "stated_ev", report.path("ev").get("stated_ev")); putNumberOrNull(ev, "vs_spot_pct", report.path("ev").get("vs_spot_pct"));
        ArrayNode scenarios = ev.putArray("scenarios"); for (JsonNode source : report.path("ev").path("scenarios")) {
            ObjectNode scenario = scenarios.addObject(); copyField(scenario, "name", source.get("name")); putJsNumber(scenario, "p", source.path("probability").asDouble() * 100);
            putJsNumber(scenario, "low", jsNumber(source.get("low"))); putJsNumber(scenario, "high", jsNumber(source.get("high"))); putJsNumber(scenario, "mid", jsNumber(source.get("mid")));
        }
        ObjectNode deployment = legacy.putObject("deployment"); putJsNumber(deployment, "deployed_pct", jsNumber(report.path("deployment").get("deployed_pct")));
        putJsNumber(deployment, "dry_pct", jsNumber(report.path("deployment").get("dry_pct"))); copyField(deployment, "throttle_released", report.path("deployment").get("throttle_released"));
        ArrayNode tranches = deployment.putArray("tranches"); for (JsonNode source : report.path("deployment").path("tranches")) {
            ObjectNode tranche = source.deepCopy(); putJsNumber(tranche, "pct", jsNumber(source.get("pct")));
            putNumberOrNull(tranche, "entry_price", source.get("entry_price")); putNumberOrNull(tranche, "stop", source.get("stop")); tranches.add(tranche);
        }
        legacy.set("tagging", legacyTaggingFromV2(report.path("tagging"), meta, report)); copyField(legacy, "verdict", report.path("verdict").get("statement"));
        ObjectNode signal = toSignal(meta, legacy, contentSha);
        signal.put("source_schema", "report-machine/2"); copyField(signal, "canonical_file", meta.get("canonical_file")); copyField(signal, "view_file", meta.get("view_file"));
        signal.put("canonical_sha256", contentSha); signal.set("tagging_v2", decDeep(report.get("tagging"))); signal.set("position", decDeep(report.get("position")));
        signal.set("position_controls", decDeep(report.get("position_controls"))); signal.set("evidence", decDeep(report.get("evidence")));
        return signal;
    }

    private static ObjectNode toV3Signal(ObjectNode meta, JsonNode report, String contentSha) {
        JsonNode setup = report.path("setup"), trigger = report.path("trigger");
        ArrayNode vetoes = report.path("vetoes").isArray() ? (ArrayNode) report.path("vetoes") : ReportingJson.NODES.arrayNode();
        boolean vetoActive = false; for (JsonNode veto : vetoes) if (veto.path("active").asBoolean()) vetoActive = true;
        JsonNode threshold = presentOrNull(setup.get("phase_threshold")), mechanical = presentOrNull(setup.get("mechanical_score"));
        Double mechanicalNumber = numberConversion(mechanical), thresholdNumber = numberConversion(threshold);
        boolean scorePass = mechanicalNumber != null && thresholdNumber != null && mechanicalNumber >= thresholdNumber;
        String riskStatus = report.path("risk_budget").path("status").asText(null); boolean authorized = setup.path("entry_authorized").asBoolean(false);
        ObjectNode signal = ReportingJson.NODES.objectNode();
        copy(signal, meta, "file", "canonical_file", "view_file"); rename(signal, "file", "report_file");
        copyRenamed(signal, meta, "date", "report_date", "local_time", "report_local_time", "zone", "report_zone", "at_utc", "report_at_utc");
        signal.put("content_sha256", contentSha); signal.put("canonical_sha256", contentSha); signal.put("source_schema", "report-machine/3"); signal.put("schema_epoch", "report_machine_3");
        copy(signal, meta, "framework", "asset"); setNullable(signal, "channel", setup.get("channel"));
        setOrNull(signal, "model_activation", decDeep(report.get("model_activation"))); setOrNull(signal, "setup", decDeep(setup)); setOrNull(signal, "features", decDeep(report.get("features")));
        setOrNull(signal, "trigger", decDeep(trigger)); signal.set("vetoes", decDeep(vetoes)); setOrNull(signal, "risk_budget", decDeep(report.get("risk_budget")));
        setOrNull(signal, "expectancy_r", decDeep(report.get("expectancy_r")));
        ObjectNode score = signal.putObject("score"); putTextOrNull(score, "mechanical", dec(mechanical)); putTextOrNull(score, "adjusted", dec(setup.get("score"))); putTextOrNull(score, "impulse", dec(setup.get("impulse"))); score.set("legs", decDeep(setup.path("legs")));
        signal.set("gates", NullNode.instance);
        ObjectNode entryState = signal.putObject("entry_state"); setNullable(entryState, "phase", setup.get("phase")); putTextOrNull(entryState, "mechanical_score", dec(mechanical));
        putTextOrNull(entryState, "phase_threshold", dec(threshold)); entryState.put("score_pass", scorePass); setNullable(entryState, "trigger_status", trigger.get("status"));
        JsonNode completedBar = trigger.get("completed_bar");
        boolean fresh = "VALID".equals(trigger.path("status").asText()) && "4h".equals(trigger.path("timeframe").asText())
                && trigger.path("completed_bar_required").asBoolean()
                && !(completedBar != null && completedBar.isBoolean() && !completedBar.booleanValue());
        if (fresh && trigger.hasNonNull("age_bars")) fresh = trigger.path("age_bars").asDouble() <= trigger.path("window_bars").asDouble();
        entryState.put("trigger_fresh", fresh); entryState.put("veto_active", vetoActive); putTextOrNull(entryState, "risk_status", riskStatus); entryState.put("authorized", authorized);
        ObjectNode deployment = signal.putObject("deployment"); setNullable(deployment, "phase", setup.get("phase")); deployment.put("authorized", authorized);
        JsonNode verdict = firstTruthy(report.path("verdict").get("statement"), report.path("narrative").get("summary")); setNullable(signal, "verdict", verdict);
        setOrNull(signal, "audit", decDeep(report.get("audit"))); setOrNull(signal, "position", decDeep(report.get("position"))); setOrNull(signal, "tags", decDeep(report.get("tags")));
        return signal;
    }

    private static ObjectNode toSignal(ObjectNode meta, JsonNode body, String contentSha) {
        JsonNode scoreSource = body.path("score"); String framework = meta.path("framework").asText();
        ObjectNode channel = ToolchainSupport.inferChannel(framework, body.path("channel").isTextual() ? body.path("channel").asText() : null, meta.path("date").asText());
        String channelValue = channel.path("channel").isNull() ? null : channel.path("channel").asText(null);
        String rubric = ToolchainSupport.signalRubric(framework, channelValue); ObjectNode discretion = ToolchainSupport.inferDiscretion(scoreSource, meta.path("date").asText());
        JsonNode gatesSource = body.path("gates"), evSource = body.path("ev"), deploymentSource = body.path("deployment");
        ArrayNode passed = sortedNumbers(gatesSource.get("passed")), na = sortedNumbers(gatesSource.get("na"));
        ArrayNode tranches = deploymentSource.path("tranches").isArray() ? (ArrayNode) deploymentSource.path("tranches") : ReportingJson.NODES.arrayNode();
        ArrayNode legs = ReportingJson.NODES.arrayNode();
        for (JsonNode spec : ToolchainSupport.legSpec(rubric)) {
            ObjectNode leg = legs.addObject(); copy(leg, spec, "ordinal", "block_key", "rubric_name");
            putTextOrNull(leg, "value", dec(scoreSource.path("legs").get(spec.path("block_key").asText()))); copy(leg, spec, "min", "max");
        }
        int filledCount = 0; for (JsonNode tranche : tranches) if (tranche.path("deployed").asBoolean(false)
                || tranche.has("entry_price") && !tranche.get("entry_price").isNull() || tranche.path("entry").isNumber()) filledCount++;
        ObjectNode signal = ReportingJson.NODES.objectNode();
        copyRenamed(signal, meta, "file", "report_file", "date", "report_date", "local_time", "report_local_time", "zone", "report_zone", "at_utc", "report_at_utc");
        signal.put("report_at_derivation", AT_UTC_NOTE); signal.put("content_sha256", contentSha);
        if (meta.path("canonical_file").isTextual()) {
            copyField(signal, "canonical_file", meta.get("canonical_file")); copyField(signal, "view_file", meta.path("view_file").isTextual() ? meta.get("view_file") : meta.get("file"));
            signal.put("canonical_sha256", contentSha); signal.put("source_schema", body.path("schema").asText("report-machine/2"));
        }
        copy(signal, meta, "framework", "asset", "schema_epoch"); setOrNull(signal, "tagging", body.has("tagging") ? decDeep(body.get("tagging")) : NullNode.instance);
        setNullable(signal, "channel", channel.get("channel")); signal.put("channel_inferred", channel.path("inferred").asBoolean()); signal.put("channel_inference_basis", channel.path("basis").asText()); signal.put("rubric", rubric);
        if (truthy(body.get("regime"))) {
            ObjectNode regime = signal.putObject("regime"); putTextOrNull(regime, "pct_below_1y_ath", dec(body.path("regime").get("pct_below_1y_ath")));
            setNullable(regime, "ma200_falling", body.path("regime").get("ma200_falling")); setNullable(regime, "price_below_ma200", body.path("regime").get("price_below_ma200"));
        } else signal.set("regime", NullNode.instance);
        putTextOrNull(signal, "spot_usd", dec(body.path("spot").get("value"))); setNullable(signal, "spot_source", body.path("spot").get("source"));
        ObjectNode score = signal.putObject("score"); putTextOrNull(score, "mechanical", dec(discretion.get("mechanical"))); score.put("mechanical_inferred", discretion.path("mechanical_inferred").asBoolean());
        putTextOrNull(score, "discretionary", dec(discretion.get("discretionary"))); score.put("discretionary_inferred", discretion.path("discretionary_inferred").asBoolean());
        score.put("inference_basis", discretion.path("basis").asText()); putTextOrNull(score, "raw", dec(scoreSource.get("raw"))); putTextOrNull(score, "adjusted", dec(scoreSource.get("adjusted")));
        putTextOrNull(score, "penalty", dec(scoreSource.get("penalty"))); score.put("cap_applied", scoreSource.path("cap").path("applied").asBoolean(false));
        putTextOrNull(score, "cap_value", dec(scoreSource.path("cap").get("value"))); setNullable(score, "rounding", scoreSource.get("rounding")); score.set("legs", legs);
        ObjectNode gates = signal.putObject("gates"); if (gatesSource.path("active").isNumber()) copyField(gates, "active", gatesSource.get("active")); else gates.set("active", NullNode.instance);
        gates.set("passed", passed); gates.put("passed_count", passed.size()); gates.put("passed_mask", ToolchainSupport.gateMask(passed)); gates.set("na", na);
        signal.set("unlock", ToolchainSupport.unlockFor(framework, channelValue, numberIfNumber(scoreSource.get("adjusted")), numberIfNumber(discretion.get("mechanical"))));
        ObjectNode ev = signal.putObject("ev"); putTextOrNull(ev, "stated_ev_usd", dec(evSource.get("stated_ev"))); putTextOrNull(ev, "vs_spot_pct", dec(evSource.get("vs_spot_pct")));
        ArrayNode scenarios = ev.putArray("scenarios"); if (evSource.path("scenarios").isArray()) for (JsonNode source : evSource.path("scenarios")) {
            ObjectNode scenario = scenarios.addObject(); setNullable(scenario, "name", source.get("name"));
            for (String field : List.of("p", "low", "high", "mid")) putTextOrNull(scenario, field, dec(source.get(field)));
        }
        ObjectNode deployment = signal.putObject("deployment"); putTextOrNull(deployment, "deployed_pct", dec(deploymentSource.get("deployed_pct"))); putTextOrNull(deployment, "dry_pct", dec(deploymentSource.get("dry_pct")));
        deployment.put("throttle_released", deploymentSource.path("throttle_released").asBoolean(false)); deployment.put("tranche_count", tranches.size()); deployment.put("filled_tranche_count", filledCount);
        deployment.put("filled_detection", FILL_DETECTION); deployment.put("filled_detection_caveat", FILL_CAVEAT);
        ArrayNode projectedTranches = deployment.putArray("tranches"); for (JsonNode source : tranches) {
            ObjectNode tranche = projectedTranches.addObject(); setNullable(tranche, "phase", source.get("phase")); putTextOrNull(tranche, "pct", dec(source.get("pct")));
            if (source.path("entry").isNumber()) tranche.set("entry", NullNode.instance); else setNullable(tranche, "entry", source.get("entry"));
            JsonNode entryPrice = source.path("entry_price").isNumber() ? source.get("entry_price") : source.path("entry").isNumber() ? source.get("entry") : null;
            putTextOrNull(tranche, "entry_price", dec(entryPrice)); boolean deployed = source.path("deployed").asBoolean(false); tranche.put("deployed", deployed);
            tranche.put("filled", deployed || source.path("entry_price").isNumber() || source.path("entry").isNumber());
            for (String field : List.of("stop", "prior_stop")) putTextOrNull(tranche, field, dec(source.get(field)));
            for (String field : List.of("time_stop", "prior_time_stop", "channel", "channel_regime")) setNullable(tranche, field, source.get(field));
            tranche.put("discretionary", source.path("discretionary").asBoolean(false));
        }
        setOrNull(signal, "stops", body.has("stops") ? decDeep(body.get("stops")) : NullNode.instance); setNullable(signal, "verdict", body.get("verdict"));
        return signal;
    }

    private static JsonNode decDeep(JsonNode value) {
        if (value == null || value.isMissingNode()) return NullNode.instance;
        if (value.isArray()) { ArrayNode output = ReportingJson.NODES.arrayNode(); value.forEach(item -> output.add(decDeep(item))); return output; }
        if (value.isObject()) { ObjectNode output = ReportingJson.NODES.objectNode(); for (Map.Entry<String, JsonNode> field : ReportingJson.entries(value)) output.set(field.getKey(), decDeep(field.getValue())); return output; }
        if (value.isNumber()) { String decimal = dec(value); return decimal == null ? NullNode.instance : ReportingJson.NODES.textNode(decimal); }
        return value.deepCopy();
    }

    private static String dec(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isNumber() && Double.isFinite(value.doubleValue())) return com.tradinganalytics.contracts.json.CanonicalJson.canonicalize(value);
        if (value.isTextual() && PLAIN_DECIMAL.matcher(value.textValue()).matches()) return value.textValue();
        return null;
    }

    private static String option(List<String> args, String name, String fallback) { int index = args.indexOf(name); return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback; }
    private static ObjectNode skip(String file, String date, String reason, String detail) { ObjectNode value = ReportingJson.NODES.objectNode(); value.put("file", file); if (date != null) value.put("date", date); value.put("reason", reason); value.put("detail", detail); return value; }
    private static ObjectNode pair(String json, String markdown, String reason) { ObjectNode value = ReportingJson.NODES.objectNode(); value.put("json", json); value.put("markdown", markdown); value.put("reason", reason); return value; }
    private static ObjectNode two(String k1, String v1, String k2, String v2) { ObjectNode value = ReportingJson.NODES.objectNode(); value.put(k1, v1); value.put(k2, v2); return value; }
    private static void sortByFile(ArrayNode values) { List<JsonNode> list = ReportingJson.elements(values); list.sort(Comparator.comparing(value -> value.path("file").asText())); values.removeAll(); list.forEach(values::add); }
    private static int countReason(ArrayNode values, String reason) { int count = 0; for (JsonNode value : values) if (reason.equals(value.path("reason").asText())) count++; return count; }
    private static int countField(ArrayNode values, String field, String expected) { int count = 0; for (JsonNode value : values) if (expected.equals(value.path(field).asText())) count++; return count; }
    private static void copy(ObjectNode target, JsonNode source, String... fields) { for (String field : fields) copyField(target, field, source == null ? null : source.get(field)); }
    private static void copyField(ObjectNode target, String field, JsonNode value) { target.set(field, value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy()); }
    private static void copyText(ObjectNode target, String field, JsonNode value) { if (value == null || value.isNull()) target.set(field, NullNode.instance); else target.put(field, value.asText()); }
    private static void copyRenamed(ObjectNode target, JsonNode source, String... pairs) { for (int i = 0; i < pairs.length; i += 2) copyField(target, pairs[i + 1], source.get(pairs[i])); }
    private static void rename(ObjectNode target, String from, String to) { JsonNode value = target.remove(from); target.set(to, value); }
    private static void setNullable(ObjectNode target, String field, JsonNode value) { target.set(field, value == null || value.isMissingNode() || value.isNull() ? NullNode.instance : value.deepCopy()); }
    private static void setOrNull(ObjectNode target, String field, JsonNode value) { target.set(field, value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy()); }
    private static void putTextOrNull(ObjectNode target, String field, String value) { if (value == null) target.set(field, NullNode.instance); else target.put(field, value); }
    private static void putNumberOrNull(ObjectNode target, String field, JsonNode value) { if (value == null || value.isNull()) target.set(field, NullNode.instance); else putJsNumber(target, field, jsNumber(value)); }
    private static void putJsNumber(ObjectNode target, String field, double value) { target.put(field, value); }
    private static double jsNumber(JsonNode value) { if (value == null || value.isNull()) return 0; if (value.isNumber()) return value.doubleValue(); return Double.parseDouble(value.asText()); }
    private static Double numberIfNumber(JsonNode value) { return value != null && value.isNumber() ? value.doubleValue() : null; }
    private static Double numberConversion(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return 0d; try { return value.isNumber() ? value.doubleValue() : Double.valueOf(value.asText()); } catch (Exception ignored) { return null; } }
    private static JsonNode presentOrNull(JsonNode value) { return value == null || value.isMissingNode() ? NullNode.instance : value; }
    private static JsonNode firstTruthy(JsonNode... values) { for (JsonNode value : values) if (truthy(value)) return value; return NullNode.instance; }
    private static boolean truthy(JsonNode value) { return ReportingJson.truthy(value); }
    private static ArrayNode sortedNumbers(JsonNode value) { ArrayNode output = ReportingJson.NODES.arrayNode(); List<Integer> values = new ArrayList<>(); if (value != null && value.isArray()) value.forEach(item -> values.add(item.asInt())); values.sort(Integer::compareTo); values.forEach(output::add); return output; }
    private static String join(JsonNode values, String delimiter) { List<String> output = new ArrayList<>(); if (values != null && values.isArray()) values.forEach(value -> output.add(value.asText())); return String.join(delimiter, output); }

    private static String pretty(JsonNode value, int depth) {
        if (value == null || value.isNull() || value.isMissingNode()) return "null";
        if (!value.isContainerNode()) { try { return JSON.writeValueAsString(value); } catch (Exception exception) { throw new IllegalArgumentException(exception); } }
        String indent = "  ".repeat(depth), child = "  ".repeat(depth + 1);
        if (value.isArray()) { if (value.isEmpty()) return "[]"; List<String> items = new ArrayList<>(); value.forEach(item -> items.add(child + pretty(item, depth + 1))); return "[\n" + String.join(",\n", items) + "\n" + indent + "]"; }
        if (value.isEmpty()) return "{}"; List<String> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : ReportingJson.entries(value)) { try { fields.add(child + JSON.writeValueAsString(field.getKey()) + ": " + pretty(field.getValue(), depth + 1)); } catch (Exception exception) { throw new IllegalArgumentException(exception); } }
        return "{\n" + String.join(",\n", fields) + "\n" + indent + "}";
    }

    public record Projection(ObjectNode feed, List<String> ignoredNames) { public Projection { ignoredNames = List.copyOf(ignoredNames); } }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), ReportingFiles.repositoryRoot(cwd)).emitAndExit();
    }
}
