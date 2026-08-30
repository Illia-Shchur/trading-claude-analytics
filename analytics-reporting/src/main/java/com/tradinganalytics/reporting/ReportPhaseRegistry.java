package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Report-machine/1 immutable phase-registry helpers from {@code tools/lib.mjs}. */
public final class ReportPhaseRegistry {
    public static final String SCHEMA = "report-phase-registry/1";
    public static final int VERSION = 1;
    public static final List<String> DECISIONS = List.of("AUTHORIZED", "LOCKED", "STAND_DOWN", "UNVERIFIED");
    public static final List<String> INSTRUMENT_CLASSES = List.of("crypto", "non_crypto_derivative", "non_crypto_cash");

    private ReportPhaseRegistry() {}

    public static List<String> applicableReportPhases(String framework, String channel) {
        if ("fallen_knives".equals(framework)) return List.of("1A", "1B", "2", "3");
        if (!"flying_rocket".equals(framework)) return List.of();
        return "B".equals(channel) ? List.of("1A", "1B", "2") : List.of("1A", "1B", "2", "3");
    }

    public static String frNonCryptoClass(String asset) {
        return switch (asset == null ? "" : asset.toLowerCase(Locale.ROOT)) {
            case "spx", "sp500", "ndx", "nasdaq" -> "equity_index";
            case "gold", "xau", "paxg", "silver", "xag" -> "metals";
            default -> null;
        };
    }

    public static String reportTagChannel(String framework, String channel) {
        if ("fallen_knives".equals(framework)) return null;
        return "B".equals(channel) ? "B" : "A";
    }

    public static String reportPhaseTagPrefix(String framework, String channel, String phase) {
        String normalized = phase == null ? "" : phase.toUpperCase(Locale.ROOT);
        if (!applicableReportPhases(framework, channel).contains(normalized)) return null;
        return "fallen_knives".equals(framework) ? "FK-P" + normalized + "-"
                : "FR-" + reportTagChannel(framework, channel) + "-" + normalized + "-";
    }

    public static String canonicalReportPhaseTag(JsonNode meta, String framework, String channel, String phase) {
        if (meta == null || !meta.path("ok").asBoolean()) return null;
        String resolvedFramework = framework != null ? framework : meta.path("framework").asText();
        String prefix = reportPhaseTagPrefix(resolvedFramework, channel, phase);
        if (prefix == null) return null;
        String identity = meta.path("asset").asText() + "-" + meta.path("date").asText().replace("-", "")
                + "-" + meta.path("local_time").asText().replace(":", "");
        String tag = prefix + identity;
        return tag.length() <= 64 ? tag : null;
    }

    public static ObjectNode build(JsonNode meta, String framework, String channel, String instrumentClass,
                                   Map<String, String> decisions) {
        return build(meta, framework, channel, instrumentClass, decisions, "report-machine/1");
    }

    public static ObjectNode build(JsonNode meta, String framework, String channel, String instrumentClass,
                                   Map<String, String> decisions, String reportVersion) {
        if (meta == null || !meta.path("ok").asBoolean()) throw new IllegalArgumentException("report identity is invalid");
        String resolvedFramework = framework != null ? framework : meta.path("framework").asText();
        String resolvedChannel = "fallen_knives".equals(resolvedFramework) ? null
                : ("A".equals(channel) || "B".equals(channel) || "none".equals(channel)) ? channel : "A";
        if (!INSTRUMENT_CLASSES.contains(instrumentClass)) throw new IllegalArgumentException("invalid instrument class: " + instrumentClass);
        ArrayNode entries = ReportingJson.NODES.arrayNode();
        for (String phase : applicableReportPhases(resolvedFramework, resolvedChannel)) {
            String decision = decisions == null ? null : decisions.get(phase);
            if (decision == null || !DECISIONS.contains(decision)) decision = "UNVERIFIED";
            ObjectNode entry = entries.addObject();
            entry.put("phase", phase);
            entry.put("canonical_tag", canonicalReportPhaseTag(meta, resolvedFramework, resolvedChannel, phase));
            entry.put("decision", decision);
            entry.put("instrument_class", instrumentClass);
            entry.put("report_file", meta.path("file").asText());
            entry.put("report_version", reportVersion);
            entry.put("asset", meta.path("asset").asText());
            entry.put("report_date", meta.path("date").asText());
            entry.put("report_local_time", meta.path("local_time").asText());
        }
        ObjectNode registry = ReportingJson.NODES.objectNode();
        registry.put("schema", SCHEMA);
        registry.put("version", VERSION);
        registry.put("report_file", meta.path("file").asText());
        registry.put("report_version", reportVersion);
        registry.put("framework", resolvedFramework);
        if (resolvedChannel == null) registry.set("channel", NullNode.instance); else registry.put("channel", resolvedChannel);
        registry.put("asset", meta.path("asset").asText());
        registry.put("report_date", meta.path("date").asText());
        registry.put("report_local_time", meta.path("local_time").asText());
        registry.put("report_zone", meta.path("zone").asText());
        registry.put("instrument_class", instrumentClass);
        registry.set("entries", entries);
        return registry;
    }

    public static List<String> issues(JsonNode registry, JsonNode meta, String framework, String channel) {
        List<String> issues = new ArrayList<>();
        if (registry == null || !registry.isObject()) return List.of("tagging.registry is missing");
        if (!SCHEMA.equals(registry.path("schema").asText(null))) issues.add("tagging.registry.schema must be " + SCHEMA);
        if (!registry.path("version").isInt() || registry.path("version").intValue() != VERSION) issues.add("tagging.registry.version must be " + VERSION);
        boolean metaOk = meta != null && meta.path("ok").asBoolean();
        if (!metaOk) issues.add("report filename identity is invalid");
        String resolvedFramework = framework != null ? framework : metaOk ? meta.path("framework").asText() : null;
        String resolvedChannel = "fallen_knives".equals(resolvedFramework) ? null
                : channel != null ? channel : registry.path("channel").isTextual() ? registry.path("channel").asText() : "A";
        if (!equalsNullable(registry.get("framework"), resolvedFramework)) issues.add("tagging.registry.framework=" + json(registry.get("framework")) + " does not match " + json(resolvedFramework));
        if (!equalsNullable(registry.get("channel"), resolvedChannel)) issues.add("tagging.registry.channel=" + json(registry.get("channel")) + " does not match " + json(resolvedChannel));
        if (metaOk) {
            for (String key : List.of("report_file", "asset", "report_date", "report_local_time")) {
                String source = switch (key) { case "report_file" -> "file"; case "report_date" -> "date"; case "report_local_time" -> "local_time"; default -> key; };
                String want = meta.path(source).asText();
                if (!equalsNullable(registry.get(key), want)) issues.add("tagging.registry." + key + "=" + json(registry.get(key)) + " does not match filename-derived " + json(want));
            }
        }
        String instrumentClass = registry.path("instrument_class").asText(null);
        if (instrumentClass == null || !INSTRUMENT_CLASSES.contains(instrumentClass)) issues.add("tagging.registry.instrument_class=" + json(registry.get("instrument_class")) + " is invalid");
        JsonNode entriesNode = registry.get("entries");
        ArrayNode entries = entriesNode != null && entriesNode.isArray() ? (ArrayNode) entriesNode : ReportingJson.NODES.arrayNode();
        if (entriesNode == null || !entriesNode.isArray()) issues.add("tagging.registry.entries must be an array");
        List<String> expected = applicableReportPhases(resolvedFramework, resolvedChannel);
        if (entries.size() != expected.size()) issues.add("tagging.registry.entries must contain exactly " + expected.size() + " applicable phases");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode entry : entries) {
            if (!entry.isObject()) { issues.add("tagging.registry.entries contains a non-object"); continue; }
            String phase = entry.path("phase").asText(null);
            if (seen.contains(phase)) issues.add("tagging.registry contains duplicate phase " + json(entry.get("phase")));
            seen.add(phase);
            if (phase == null || !expected.contains(phase)) issues.add("tagging.registry contains invalid phase " + json(entry.get("phase")) + " (FR-B has no Phase 3)");
            String wantedTag = metaOk ? canonicalReportPhaseTag(meta, resolvedFramework, resolvedChannel, phase) : null;
            if (wantedTag == null || !equalsNullable(entry.get("canonical_tag"), wantedTag)) issues.add("phase " + phase + ": canonical tag " + json(entry.get("canonical_tag")) + " does not match " + json(wantedTag));
            if (entry.path("canonical_tag").isTextual() && entry.path("canonical_tag").asText().length() > 64) issues.add("phase " + phase + ": canonical tag exceeds 64 characters");
            String decision = entry.path("decision").asText(null);
            if (decision == null || !DECISIONS.contains(decision)) issues.add("phase " + phase + ": missing or invalid decision " + json(entry.get("decision")));
            for (String key : List.of("instrument_class", "report_file", "report_version", "asset", "report_date", "report_local_time"))
                if (!java.util.Objects.equals(entry.get(key), registry.get(key))) issues.add("phase " + phase + ": " + key + " does not match the immutable registry identity");
        }
        for (String phase : expected) if (!seen.contains(phase)) issues.add("tagging.registry is missing applicable phase " + phase);
        return issues;
    }

    private static boolean equalsNullable(JsonNode node, String value) {
        return value == null ? node == null || node.isNull() : node != null && node.isTextual() && value.equals(node.textValue());
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof JsonNode node) return node.toString();
        return ReportingJson.NODES.textNode(String.valueOf(value)).toString();
    }
}
