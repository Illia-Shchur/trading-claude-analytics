package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone historical-corpus adapter for {@code tools/backfill-report-phase-registry.mjs}. */
public final class BackfillReportPhaseRegistryCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MACHINE = Pattern.compile("```json machine\\s*\\n([\\s\\S]*?)```");

    private BackfillReportPhaseRegistryCommand() {}

    public static ReportingCommandResult run(List<String> args, Path repositoryRoot) {
        boolean check = args.contains("--check");
        Path reports = repositoryRoot.resolve("reports").toAbsolutePath().normalize();
        int changed = 0, machine = 0, prose = 0;
        try {
            List<Path> files;
            try (var stream = Files.list(reports)) {
                files = stream.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
            }
            for (Path path : files) {
                String file = path.getFileName().toString();
                ObjectNode meta = ToolchainSupport.reportFileMeta(file);
                if (!meta.path("ok").asBoolean()) continue;
                String text = Files.readString(path, StandardCharsets.UTF_8);
                Matcher block = MACHINE.matcher(text);
                if (!block.find()) { prose++; continue; }
                machine++;
                JsonNode body = JSON.readTree(block.group(1));
                ObjectNode inferred = ToolchainSupport.inferChannel(meta.path("framework").asText(),
                        body.path("channel").isTextual() ? body.path("channel").asText() : null, meta.path("date").asText());
                String channel = inferred.path("channel").isNull() ? null : inferred.path("channel").asText(null);
                ArrayNode tranches = body.path("deployment").path("tranches").isArray()
                        ? (ArrayNode) body.path("deployment").path("tranches") : ReportingJson.NODES.arrayNode();
                List<String> applicable = ReportPhaseRegistry.applicableReportPhases(meta.path("framework").asText(), channel);
                boolean wholeStandDown = Pattern.compile("\\bstand\\s*down\\b", Pattern.CASE_INSENSITIVE).matcher(verdictText(body.get("verdict"))).find();
                Map<String, String> decisions = new LinkedHashMap<>();
                if (wholeStandDown) applicable.forEach(phase -> decisions.put(phase, "STAND_DOWN"));
                else for (JsonNode tranche : tranches) {
                    String phase = phaseKey(tranche.get("phase"));
                    if (phase != null) decisions.put(phase, decisionFor(tranche, body, text));
                }
                String instrumentClass = ReportPhaseRegistry.frNonCryptoClass(meta.path("asset").asText()) != null
                        || "GOLD".equals(meta.path("asset").asText()) ? "non_crypto_derivative" : "crypto";
                ObjectNode registry = ReportPhaseRegistry.build(meta, meta.path("framework").asText(), channel, instrumentClass, decisions);
                String next = replaceTagging(text, body, registry);
                if (!next.equals(text)) {
                    changed++;
                    if (!check) Files.writeString(path, next, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception exception) {
            return ReportingCommandResult.failure("", ReportingFiles.message(exception) + "\n");
        }
        StringBuilder stderr = new StringBuilder();
        stderr.append(check ? "would update" : "updated").append(' ').append(changed).append(" machine reports; ")
                .append(machine).append(" machine-block reports, ").append(prose).append(" prose-only reports unchanged\n");
        if (machine != 67 || prose != 66) {
            stderr.append("FAIL expected 67 machine + 66 prose-only reports, got ").append(machine).append(" + ").append(prose).append('\n');
            return ReportingCommandResult.failure("", stderr.toString());
        }
        return ReportingCommandResult.success("", stderr.toString());
    }

    static String replaceTagging(String text, JsonNode body, ObjectNode registry) throws Exception {
        ObjectNode existing = body.path("tagging").isObject() ? ((ObjectNode) body.path("tagging")).deepCopy() : ReportingJson.NODES.objectNode();
        existing.put("mode", "phase_registry");
        existing.set("registry", registry);
        existing.set("instrument_class", registry.get("instrument_class"));
        existing.set("report_file", registry.get("report_file"));
        existing.set("report_version", registry.get("report_version"));
        existing.set("framework", registry.get("framework"));
        existing.set("channel", registry.get("channel") == null ? NullNode.instance : registry.get("channel"));
        existing.set("report_asset", registry.get("asset"));
        existing.set("report_date", registry.get("report_date"));
        existing.set("report_local_time", registry.get("report_local_time"));
        existing.set("active_tags", ReportingJson.NODES.arrayNode());
        ArrayNode reserved = ReportingJson.NODES.arrayNode();
        registry.path("entries").forEach(entry -> reserved.add(entry.path("canonical_tag").asText()));
        existing.set("reserved_tags", reserved);
        existing.put("status", "REGISTERED");

        Matcher match = MACHINE.matcher(text);
        if (!match.find()) throw new IllegalArgumentException("machine block missing");
        String block = match.group(1);
        String withoutTagging = block.replaceFirst("\\n  \\\"tagging\\\": \\{[\\s\\S]*?\\n  \\},?(?=\\n  \\\"[^\\\"\\n]+\\\":|\\n\\})", "");
        String bodyText = withoutTagging.stripTrailing().replaceFirst("}\\s*$", "").stripTrailing().replaceFirst(",\\s*$", "");
        String pretty = pretty(existing, 0);
        String[] renderedLines = pretty.split("\\n", -1);
        List<String> indented = new ArrayList<>();
        for (int index = 0; index < renderedLines.length; index++) indented.add(index == 0 ? "  \"tagging\": " + renderedLines[index] : "  " + renderedLines[index]);
        String nextBlock = bodyText + ",\n" + String.join("\n", indented) + "\n}";
        String nextFence = "```json machine\n" + nextBlock + "\n```";
        String nextText = text.substring(0, match.start()) + nextFence + text.substring(match.end());
        Pattern visible = Pattern.compile("### Immutable report-phase registry[\\s\\S]*?(?=```json machine)");
        Matcher old = visible.matcher(nextText);
        if (old.find()) return old.replaceFirst(Matcher.quoteReplacement(visibleRegistry(registry)));
        return nextText.replaceFirst("\\n```json machine", Matcher.quoteReplacement("\n" + visibleRegistry(registry) + "```json machine"));
    }

    static String visibleRegistry(ObjectNode registry) {
        List<String> rows = new ArrayList<>(List.of("### Immutable report-phase registry", "", "| Phase | Canonical tag | Decision | Instrument class |", "|---|---|---|---|"));
        for (JsonNode entry : registry.withArray("entries")) rows.add("| " + entry.path("phase").asText() + " | " + entry.path("canonical_tag").asText()
                + " | " + entry.path("decision").asText() + " | " + entry.path("instrument_class").asText() + " |");
        rows.add("");
        rows.add("Registry schema: " + registry.path("schema").asText() + "; version: " + registry.path("version").asInt()
                + "; origin: " + registry.path("report_file").asText() + " (" + registry.path("report_version").asText() + ").");
        rows.add("");
        return String.join("\n", rows);
    }

    static String verdictText(JsonNode verdict) {
        if (verdict != null && verdict.isTextual()) return verdict.textValue();
        if (verdict != null && verdict.isObject()) return verdict.toString();
        return "";
    }

    static String phaseKey(JsonNode phase) {
        String value = phase == null || phase.isNull() ? "" : phase.asText().toLowerCase().replaceFirst("^phase\\s*", "");
        return List.of("1a", "1b", "2", "3").contains(value) ? value.toUpperCase() : null;
    }

    static String decisionFor(JsonNode entry, JsonNode body, String wholeText) {
        String verdict = verdictText(body.get("verdict"));
        if (Pattern.compile("\\bstand\\s*down\\b", Pattern.CASE_INSENSITIVE).matcher(verdict).find()) return "STAND_DOWN";
        if (entry == null || entry.isMissingNode()) return "UNVERIFIED";
        String local = raw(entry.get("phase")) + " " + raw(entry.get("entry")) + " " + raw(entry.get("status")) + " " + raw(entry.get("reason"));
        if (Pattern.compile("(last[- ]confirmed|prior reports? narrat|\\bfilled\\b|\\bdeployed\\b|\\bconfirmed\\b|\\bblend(?:ed)?\\b|\\bMTM\\b|~\\s*\\$?[0-9])", Pattern.CASE_INSENSITIVE).matcher(local).find()) return "UNVERIFIED";
        if (Pattern.compile("(double[- ]blocked|score[- ]blocked|gate[- ]blocked|blocked|not eligible|frozen|dry\\b|no 1a base|no analyst channel|<\\s*\\d|short\\s+one|declined)", Pattern.CASE_INSENSITIVE).matcher(local).find()) return "LOCKED";
        if (Pattern.compile("(unlock conditions? met|genuinely unlocked|eligible(?:\\s*\\+\\s*armed)?|\\barmed\\b|score condition met|score clears|authorization|authorized)", Pattern.CASE_INSENSITIVE).matcher(local).find()) return "AUTHORIZED";
        String markerText = raw(entry.get("phase")).replaceAll("[^0-9A-Za-z]", "");
        Matcher marker = Pattern.compile("phase\\s*" + Pattern.quote(markerText) + "[^\\n]{0,180}", Pattern.CASE_INSENSITIVE).matcher(wholeText);
        String context = marker.find() ? marker.group() : "";
        if (Pattern.compile("(unlock conditions? met|genuinely unlocked|eligible|armed|authorized)", Pattern.CASE_INSENSITIVE).matcher(context).find()
                && !Pattern.compile("(blocked|frozen|not eligible|dry\\b|declined)", Pattern.CASE_INSENSITIVE).matcher(context).find()) return "AUTHORIZED";
        return "UNVERIFIED";
    }

    private static String raw(JsonNode value) { return value == null || value.isNull() ? "" : value.isTextual() ? value.textValue() : value.toString(); }

    private static String pretty(JsonNode value, int depth) throws Exception {
        if (value == null || value.isNull() || value.isMissingNode()) return "null";
        if (!value.isContainerNode()) return JSON.writeValueAsString(value);
        String indent = "  ".repeat(depth), childIndent = "  ".repeat(depth + 1);
        if (value.isArray()) {
            if (value.isEmpty()) return "[]";
            List<String> items = new ArrayList<>();
            for (JsonNode item : value) items.add(childIndent + pretty(item, depth + 1));
            return "[\n" + String.join(",\n", items) + "\n" + indent + "]";
        }
        if (value.isEmpty()) return "{}";
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : ReportingJson.entries(value))
            fields.add(childIndent + JSON.writeValueAsString(field.getKey()) + ": " + pretty(field.getValue(), depth + 1));
        return "{\n" + String.join(",\n", fields) + "\n" + indent + "}";
    }

    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        run(List.of(args), ReportingFiles.repositoryRoot(cwd)).emitAndExit();
    }
}
