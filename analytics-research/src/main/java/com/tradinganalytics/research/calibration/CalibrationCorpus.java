package com.tradinganalytics.research.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic report slicing and event-preserving sampling from {@code calib-corpus.mjs}. */
public final class CalibrationCorpus {
    public static final Pattern MACHINE_BLOCK_RE = Pattern.compile(
            "\\n?---\\s*\\n\\n```json machine\\s*\\n([\\s\\S]*?)```\\s*$");
    public static final Pattern VERIFIED_DATA_HEADING_RE = Pattern.compile(
            "^##\\s+\\d+\\.\\s+Verified Live Data(?: Points)?\\b.*$", Pattern.MULTILINE);
    public static final Pattern COMPOSITE_SCORE_HEADING_RE = Pattern.compile(
            "^##\\s+\\d+[a-z]?\\.\\s+(?:Fallen Knives|Flying Rocket)\\s+Composite Score\\b.*$",
            Pattern.MULTILINE);
    public static final Pattern NEXT_TOP_HEADING_RE = Pattern.compile("^##\\s+\\d+\\.", Pattern.MULTILINE);
    private static final List<Double> FK_UNLOCK = List.of(8.0, 11.0, 15.0, 17.0);
    private static final List<Double> FR_UNLOCK = List.of(11.0, 13.0, 15.0, 19.0);

    private final ObjectMapper json;

    public CalibrationCorpus(ObjectMapper json) {
        this.json = json;
    }

    public DropResult dropMachineBlock(String text) {
        Matcher matcher = MACHINE_BLOCK_RE.matcher(text);
        if (!matcher.find()) {
            return new DropResult(text, null);
        }
        String matched = matcher.group();
        String raw = matcher.group(1);
        String sliced = text.substring(0, matcher.start()) + "\n";
        return new DropResult(sliced, new Dropped(
                matched.getBytes(StandardCharsets.UTF_8).length,
                Sha256.hex(raw.getBytes(StandardCharsets.UTF_8)), null, raw));
    }

    public DropResult dropVerifiedDataSection(String text) {
        return dropSectionByHeading(text, VERIFIED_DATA_HEADING_RE);
    }

    public DropResult dropCompositeScoreSection(String text) {
        return dropSectionByHeading(text, COMPOSITE_SCORE_HEADING_RE);
    }

    public ObjectNode projectDigest(String raw) {
        JsonNode block;
        try {
            block = json.readTree(raw);
        } catch (Exception exception) {
            ObjectNode failure = json.createObjectNode();
            failure.put("ok", false);
            failure.put("error", "unparseable machine block: " + CalibrationPaths.jsonParseMessage(raw, exception));
            return failure;
        }
        ObjectNode output = json.createObjectNode();
        output.put("ok", true);
        nullable(output, "schema", block.get("schema"));
        nullable(output, "framework", block.get("framework"));
        nullable(output, "asset", block.get("asset"));
        nullable(output, "date", block.get("date"));
        nullable(output, "spot", path(block, "spot", "value"));

        ObjectNode score = output.putObject("score");
        nullable(score, "legs", path(block, "score", "legs"));
        nullable(score, "discretionary", path(block, "score", "discretionary"));
        nullable(score, "mechanical", path(block, "score", "mechanical"));
        nullable(score, "raw", path(block, "score", "raw"));
        nullable(score, "adjusted", path(block, "score", "adjusted"));

        ObjectNode gates = output.putObject("gates");
        nullable(gates, "active", path(block, "gates", "active"));
        nullable(gates, "na", path(block, "gates", "na"));
        nullable(gates, "passed", path(block, "gates", "passed"));

        ObjectNode ev = output.putObject("ev");
        ArrayNode scenarios = ev.putArray("scenarios");
        JsonNode sourceScenarios = path(block, "ev", "scenarios");
        if (sourceScenarios.isArray()) {
            for (JsonNode source : sourceScenarios) {
                ObjectNode scenario = scenarios.addObject();
                optional(scenario, "name", source.get("name"));
                optional(scenario, "p", source.get("p"));
                optional(scenario, "low", source.get("low"));
                optional(scenario, "high", source.get("high"));
            }
        }
        nullable(ev, "stated_ev", path(block, "ev", "stated_ev"));
        nullable(ev, "vs_spot_pct", path(block, "ev", "vs_spot_pct"));

        ObjectNode deployment = output.putObject("deployment");
        nullable(deployment, "deployed_pct", path(block, "deployment", "deployed_pct"));
        nullable(deployment, "dry_pct", path(block, "deployment", "dry_pct"));
        ArrayNode tranches = deployment.putArray("tranches");
        JsonNode sourceTranches = path(block, "deployment", "tranches");
        if (sourceTranches.isArray()) {
            for (JsonNode source : sourceTranches) {
                ObjectNode tranche = tranches.addObject();
                optional(tranche, "phase", source.get("phase"));
                optional(tranche, "pct", source.get("pct"));
                nullable(tranche, "discretionary", source.get("discretionary"));
                JsonNode deployed = source.get("deployed");
                if (deployed == null || deployed.isNull()) {
                    JsonNode entryPrice = source.get("entry_price");
                    deployed = entryPrice != null && entryPrice.isNumber()
                            ? json.getNodeFactory().booleanNode(true) : NullNode.instance;
                }
                tranche.set("deployed", deployed.deepCopy());
                JsonNode entryPrice = source.get("entry_price");
                tranche.set("entry_price", entryPrice != null && entryPrice.isNumber()
                        ? entryPrice.deepCopy() : NullNode.instance);
                JsonNode entry = source.get("entry");
                if (entry == null) {
                    // JSON.stringify omits an undefined entry_note.
                } else if (entry.isTextual()) {
                    tranche.put("entry_note", truncate(entry.textValue(), 160, "see slice prose"));
                } else {
                    tranche.set("entry_note", entry.deepCopy());
                }
            }
        }

        ObjectNode stops = output.putObject("stops");
        nullable(stops, "catastrophic", path(block, "stops", "catastrophic"));
        nullable(stops, "deepest_zone_floor", path(block, "stops", "deepest_zone_floor"));
        nullable(stops, "compound", path(block, "stops", "compound"));
        JsonNode checkpointSource = path(block, "stops", "checkpoint");
        if (truthy(checkpointSource)) {
            ObjectNode checkpoint = stops.putObject("checkpoint");
            optional(checkpoint, "date", checkpointSource.get("date"));
            optional(checkpoint, "line", checkpointSource.get("line"));
            optional(checkpoint, "condition", checkpointSource.get("condition"));
        } else {
            stops.set("checkpoint", NullNode.instance);
        }
        nullableCompanion(output, "companion_fr", block.get("companion_fr"), true);
        nullableCompanion(output, "companion_fk", block.get("companion_fk"), false);
        JsonNode positionSource = block.get("position");
        if (truthy(positionSource)) {
            ObjectNode position = output.putObject("position");
            nullable(position, "band", positionSource.get("band"));
            nullable(position, "cold_start", positionSource.get("cold_start"));
        } else {
            output.set("position", NullNode.instance);
        }
        JsonNode verdict = block.get("verdict");
        if (verdict == null) {
            // An undefined property exists in the JS object but is omitted by JSON.stringify.
        } else if (verdict.isTextual()) {
            output.put("verdict_note", truncate(verdict.textValue(), 220, "see slice prose"));
        } else {
            output.set("verdict_note", verdict.deepCopy());
        }
        return output;
    }

    public ObjectNode projectV2Digest(JsonNode report) {
        ObjectNode output = json.createObjectNode();
        output.put("ok", true);
        output.set("schema", report.path("schema").deepCopy());
        output.set("framework", path(report, "identity", "framework").deepCopy());
        output.set("asset", path(report, "identity", "asset").deepCopy());
        output.set("date", path(report, "identity", "date").deepCopy());
        output.set("report_id", report.path("report_id").deepCopy());
        output.set("spot", path(report, "market", "spot", "value").deepCopy());
        ObjectNode score = output.putObject("score");
        score.set("legs", path(report, "score", "legs").deepCopy());
        score.set("discretionary", path(report, "score", "discretion").deepCopy());
        score.set("mechanical", path(report, "score", "mechanical").deepCopy());
        score.set("raw", path(report, "score", "raw").deepCopy());
        score.set("adjusted", path(report, "score", "adjusted").deepCopy());
        ObjectNode gates = output.putObject("gates");
        gates.set("active", path(report, "gates", "active").deepCopy());
        gates.set("na", path(report, "gates", "na").deepCopy());
        gates.set("passed", path(report, "gates", "passed").deepCopy());
        ObjectNode ev = output.putObject("ev");
        ArrayNode scenarios = ev.putArray("scenarios");
        for (JsonNode source : iterable(path(report, "ev", "scenarios"))) {
            ObjectNode row = scenarios.addObject();
            for (String key : List.of("name", "low", "high", "mid")) optional(row, key, source.get(key));
            optional(row, "p", source.get("probability"));
        }
        ev.set("stated_ev", path(report, "ev", "stated_ev").deepCopy());
        ev.set("vs_spot_pct", path(report, "ev", "vs_spot_pct").deepCopy());
        ObjectNode deployment = output.putObject("deployment");
        deployment.set("deployed_pct", path(report, "deployment", "deployed_pct").deepCopy());
        deployment.set("dry_pct", path(report, "deployment", "dry_pct").deepCopy());
        ArrayNode tranches = deployment.putArray("tranches");
        for (JsonNode source : iterable(path(report, "deployment", "tranches"))) {
            ObjectNode row = tranches.addObject();
            for (String key : List.of("phase", "pct", "deployed", "entry_price", "stop")) optional(row, key, source.get(key));
        }
        ObjectNode position = output.putObject("position");
        for (String key : List.of("status", "quantity", "custody", "basis")) {
            optional(position, key, path(report, "position", key));
        }
        String statement = path(report, "verdict", "statement").asText();
        output.put("verdict_note", truncate(statement, 220, "see canonical JSON"));
        return output;
    }

    public boolean isEventReport(JsonNode report, JsonNode previous) {
        JsonNode digest = report == null ? null : report.get("digest");
        if (digest == null || digest.isNull() || digest.path("ok").isBoolean() && !digest.path("ok").asBoolean()) {
            return true;
        }
        JsonNode prior = previous == null ? null : previous.get("digest");
        if (prior == null || prior.isNull() || prior.path("ok").isBoolean() && !prior.path("ok").asBoolean()) {
            return true;
        }
        if (!jsStrictEqual(nullish(path(digest, "gates", "passed")), nullish(path(prior, "gates", "passed")))) {
            return true;
        }
        if (!trancheKeys(digest).equals(trancheKeys(prior))) {
            return true;
        }
        Double currentScore = scoreOf(digest);
        Double previousScore = scoreOf(prior);
        if (currentScore != null && previousScore != null) {
            if (Math.abs(currentScore - previousScore) > 1) {
                return true;
            }
            List<Double> ladder = "fallen_knives".equals(report.path("t").asText()) ? FK_UNLOCK : FR_UNLOCK;
            for (double line : ladder) {
                if ((currentScore >= line) != (previousScore >= line)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Selection selectWithCap(List<JsonNode> reports, int cap) {
        int size = reports.size();
        if (size <= cap) {
            LinkedHashSet<Integer> all = new LinkedHashSet<>();
            for (int index = 0; index < size; index++) all.add(index);
            return new Selection(all, List.of(), false);
        }
        LinkedHashSet<Integer> events = new LinkedHashSet<>();
        for (int index = 0; index < size; index++) {
            if (index == 0 || index == size - 1 || isEventReport(reports.get(index), reports.get(index - 1))) {
                events.add(index);
            }
        }
        if (events.size() >= cap) {
            return new Selection(events, filenamesOutside(reports, events), true);
        }
        List<Integer> nonEvents = new ArrayList<>();
        for (int index = 0; index < size; index++) if (!events.contains(index)) nonEvents.add(index);
        int slots = cap - events.size();
        double stride = (double) nonEvents.size() / slots;
        LinkedHashSet<Integer> kept = new LinkedHashSet<>(events);
        for (int index = 0; index < slots; index++) {
            kept.add(nonEvents.get(Math.min(nonEvents.size() - 1, (int) Math.round(index * stride))));
        }
        return new Selection(kept, filenamesOutside(reports, kept), false);
    }

    public record Dropped(int bytes, String sha256, String heading, String raw) {
    }

    public record DropResult(String text, Dropped dropped) {
    }

    public record Selection(Set<Integer> keptIndexes, List<String> sampledOut, boolean capExceededByEvents) {
    }

    private DropResult dropSectionByHeading(String text, Pattern headingPattern) {
        Matcher heading = headingPattern.matcher(text);
        if (!heading.find()) return new DropResult(text, null);
        int start = heading.start();
        int end = text.length();
        String rest = text.substring(heading.end());
        Matcher next = NEXT_TOP_HEADING_RE.matcher(rest);
        if (next.find()) end = heading.end() + next.start();
        String removed = text.substring(start, end);
        String sliced = text.substring(0, start) + text.substring(end);
        return new DropResult(sliced, new Dropped(
                removed.getBytes(StandardCharsets.UTF_8).length, null, heading.group().trim(), null));
    }

    private void nullableCompanion(ObjectNode target, String key, JsonNode source, boolean channel) {
        if (!truthy(source)) {
            target.set(key, NullNode.instance);
            return;
        }
        ObjectNode value = target.putObject(key);
        nullable(value, "score", source.get("score"));
        if (channel) nullable(value, "channel", source.get("channel"));
    }

    private static String truncate(String value, int max, String pointer) {
        return value.length() > max
                ? value.substring(0, max) + "…[" + value.length() + "ch, " + pointer + "]" : value;
    }

    private static JsonNode path(JsonNode value, String... fields) {
        JsonNode cursor = value;
        for (String field : fields) {
            if (cursor == null) return NullNode.instance;
            cursor = cursor.get(field);
        }
        return cursor == null ? NullNode.instance : cursor;
    }

    private static void nullable(ObjectNode target, String key, JsonNode value) {
        target.set(key, value == null || value.isNull() || value.isMissingNode()
                ? NullNode.instance : value.deepCopy());
    }

    private static void optional(ObjectNode target, String key, JsonNode value) {
        if (value != null && !value.isMissingNode()) target.set(key, value.deepCopy());
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asDouble() != 0 && !Double.isNaN(value.asDouble());
        if (value.isTextual()) return !value.asText().isEmpty();
        return true;
    }

    private static JsonNode nullish(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? NullNode.instance : value;
    }

    private static boolean jsStrictEqual(JsonNode left, JsonNode right) {
        if (left.isContainerNode() || right.isContainerNode()) return left == right;
        if (left.isNumber() && right.isNumber()) return left.doubleValue() == right.doubleValue();
        if (left.isTextual() && right.isTextual()) return left.textValue().equals(right.textValue());
        if (left.isBoolean() && right.isBoolean()) return left.booleanValue() == right.booleanValue();
        return left.isNull() && right.isNull();
    }

    private static String trancheKeys(JsonNode digest) {
        List<String> keys = new ArrayList<>();
        for (JsonNode tranche : iterable(path(digest, "deployment", "tranches"))) {
            JsonNode entry = tranche.get("entry_price");
            String entryValue = entry == null || entry.isNull() ? "" : entry.asText();
            keys.add(tranche.path("phase").asText() + ":" + (truthy(tranche.get("deployed")) ? "1" : "0") + ":" + entryValue);
        }
        try {
            return new ObjectMapper().writeValueAsString(keys);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Double scoreOf(JsonNode digest) {
        JsonNode adjusted = path(digest, "score", "adjusted");
        JsonNode mechanical = path(digest, "score", "mechanical");
        JsonNode chosen = adjusted.isNull() || adjusted.isMissingNode() ? mechanical : adjusted;
        return chosen.isNumber() ? chosen.asDouble() : null;
    }

    private static Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private static List<String> filenamesOutside(List<JsonNode> reports, Set<Integer> kept) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < reports.size(); index++) {
            if (!kept.contains(index)) result.add(reports.get(index).path("f").asText());
        }
        return List.copyOf(result);
    }

}
