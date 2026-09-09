package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unregistered command adapter mirroring the four {@code swing-engine.mjs} modes. */
public final class SwingEngineCommand {
    public static final String USAGE = "Usage: swing-engine.mjs build-cache --input features.json --out store.json | build-cache --assets btc,eth --years 3 --out store.json [--cache-dir data/swing-calibration/cache] | run --cache store.json --candidates candidates.json [--candidate-ids id1,id2] --out run.json [--summary summary.md] | benchmark --cache store.json [--candidate-count 1000] | inspect-trades --run run.json";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SwingEngineCommand() {}

    @FunctionalInterface
    public interface BackfillProvider {
        JsonNode backfill(String asset, int years, Path cacheDirectory) throws Exception;
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        Clock clock = Clock.systemUTC();
        return run(arguments, stdout, stderr,
                (asset, years, cache) -> SwingBackfill.backfillAsset(asset,
                        new SwingBackfill.Options(years, cache, clock.millis())), clock);
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr, BackfillProvider backfill) {
        return run(arguments, stdout, stderr, backfill, Clock.systemUTC());
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr, BackfillProvider backfill, Clock clock) {
        try {
            Parsed args = parse(arguments);
            switch (args.command()) {
                case "help" -> stdout.println(USAGE);
                case "build-cache" -> buildCache(args, stdout, backfill, clock);
                case "run" -> runResearch(args, stdout, clock);
                case "inspect-trades" -> inspectTrades(args, stdout);
                case "benchmark" -> benchmark(args, stdout);
                default -> throw new IllegalArgumentException("unknown command " + args.command());
            }
            return 0;
        } catch (Exception exception) {
            stderr.println("FAIL — " + exception.getMessage());
            return 1;
        }
    }

    private static void buildCache(Parsed args, PrintStream stdout, BackfillProvider provider, Clock clock) throws Exception {
        String output = args.string("out");
        if (output == null) throw new IllegalArgumentException("build-cache requires --out");
        JsonNode input; String source;
        if (args.string("input") != null) {
            input = readJson(Path.of(args.string("input"))); source = args.string("input");
        } else if (args.string("assets") != null) {
            List<String> assets = commaList(args.string("assets"), true);
            if (assets.isEmpty()) throw new IllegalArgumentException("--assets must contain at least one asset");
            int years = (int) Math.max(1, Math.min(3, number(args.stringOr("years", "3"))));
            Path cacheDirectory = Path.of(args.stringOr("cache_dir", "data/swing-calibration/cache"));
            if (provider == null) throw new IllegalArgumentException("build-cache --assets requires a configured backfill provider");
            ArrayNode datasets = JSON.arrayNode();
            for (String asset : assets) {
                JsonNode result = provider.backfill(asset, years, cacheDirectory);
                for (JsonNode dataset : array(result.get("datasets"))) datasets.add(dataset.deepCopy());
            }
            input = JSON.objectNode().put("point_in_time_safe", false).set("datasets", datasets);
            source = "backfillAsset:" + String.join(",", assets) + ':' + years + 'y';
        } else throw new IllegalArgumentException("build-cache requires --input or --assets");
        ObjectNode options = JSON.objectNode().put("source", source)
                .put("pointInTimeSafe", input.path("point_in_time_safe").asBoolean(false));
        ObjectNode store = SwingEngine.buildFeatureStore(input, options, clock);
        SwingEngine.FeatureStoreWrite write = SwingEngine.writeFeatureStore(Path.of(output), store);
        ObjectNode info = write.toJson().put("rows", store.path("row_count").asLong())
                .put("features_sha256", store.path("features_sha256").asText());
        stdout.print(NodePrettyJson.write(info));
    }

    private static void runResearch(Parsed args, PrintStream stdout, Clock clock) throws IOException {
        String cachePath = args.string("cache"), output = args.string("out");
        if (cachePath == null || output == null) throw new IllegalArgumentException("run requires --cache and --out");
        ObjectNode store = SwingEngine.readFeatureStoreArtifact(Path.of(cachePath));
        JsonNode candidatePayload = args.string("candidates") == null ? SwingEngine.defaultCandidates() : readJson(Path.of(args.string("candidates")));
        ArrayNode candidates = candidatePayload.isArray() ? (ArrayNode) candidatePayload : array(candidatePayload.get("candidates"));
        if (args.string("candidate_ids") != null) {
            List<String> ids = commaList(args.string("candidate_ids"), false);
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode candidate : candidates) byId.put(candidate.path("id").asText(), candidate);
            List<String> missing = ids.stream().filter(id -> !byId.containsKey(id)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("candidate ids not found: " + String.join(",", missing));
            ArrayNode selected = JSON.arrayNode(); ids.forEach(id -> selected.add(byId.get(id).deepCopy())); candidates = selected;
        }
        ObjectNode options = JSON.objectNode().put("feature_store_sha256", store.path("features_sha256").asText());
        putNumber(options, "minTrades", number(args.stringOr("min_trades", "10")));
        putNumber(options, "minExpectancyR", number(args.stringOr("min_expectancy_r", "0")));
        putNumber(options, "maxDrawdown", number(args.stringOr("max_drawdown", "0.35")));
        putNumber(options, "minRegimes", number(args.stringOr("min_regimes", "2")));
        options.put("same_bar_collision", args.stringOr("same_bar_collision", "stop-first"));
        ObjectNode result = SwingEngine.runResearch(SwingEngine.decodeFeatureStore(store), candidates, options, clock);
        writePretty(Path.of(output), result);
        String summary = args.string("summary");
        if (summary != null) writeText(Path.of(summary), SwingEngine.renderSummary(result));
        ObjectNode response = JSON.objectNode().put("out", Path.of(output).toAbsolutePath().normalize().toString());
        response.set("summary", summary == null ? NullNode.instance : JSON.textNode(Path.of(summary).toAbsolutePath().normalize().toString()));
        response.put("run_sha256", result.path("run_sha256").asText()).put("candidates", result.path("candidates_declared").asInt());
        ArrayNode series = JSON.arrayNode();
        for (JsonNode item : result.path("series")) { ObjectNode summaryNode = JSON.objectNode().set("series", item.get("series")); summaryNode.set("rows", item.get("rows")); series.add(summaryNode); }
        response.set("series", series); stdout.print(NodePrettyJson.write(response));
    }

    private static void inspectTrades(Parsed args, PrintStream stdout) throws IOException {
        if (args.string("run") == null) throw new IllegalArgumentException("inspect-trades requires --run");
        JsonNode result = readJson(Path.of(args.string("run")));
        if (!SwingEngine.verifyRunHash(result)) throw new IllegalArgumentException("run artifact hash mismatch; refuse to inspect tampered artifact");
        ArrayNode trades = JSON.arrayNode();
        for (JsonNode series : array(result.get("series")))
            for (JsonNode trade : array(series.path("validation").path("holdout").path("report").get("trades"))) trades.add(trade.deepCopy());
        ObjectNode response = JSON.objectNode().put("run_sha256", result.path("run_sha256").asText())
                .put("completed_trades", trades.size()).set("trades", trades);
        stdout.print(NodePrettyJson.write(response));
    }

    private static void benchmark(Parsed args, PrintStream stdout) throws IOException {
        if (args.string("cache") == null) throw new IllegalArgumentException("benchmark requires --cache");
        ObjectNode store = SwingEngine.readFeatureStoreArtifact(Path.of(args.string("cache")));
        ArrayNode rows = SwingEngine.decodeFeatureStore(store);
        int count = (int) Math.max(1, number(args.stringOr("candidate_count", "1000")));
        ArrayNode base = SwingEngine.defaultCandidates(), candidates = JSON.arrayNode();
        for (int index = 0; index < count; index++) {
            ObjectNode candidate = ((ObjectNode) base.get(index % base.size())).deepCopy().put("id", "benchmark-" + index)
                    .put("threshold_offset", index % 7 - 3).put("min_flow_aligned", index % 6);
            candidates.add(candidate);
        }
        long start = System.nanoTime();
        ObjectNode options = JSON.objectNode().put("feature_store_sha256", store.path("features_sha256").asText())
                .put("minTrades", 1).put("minRegimes", 1).put("skip_validation", true).put("bootstrap_rounds", 100);
        SwingEngine.runResearch(rows, candidates, options);
        double elapsed = (System.nanoTime() - start) / 1_000_000d;
        double memory = Math.round((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024d / 1024d * 10) / 10d;
        ObjectNode output = JSON.objectNode().put("engine", SwingEngine.ENGINE_VERSION).put("candidates", count).put("rows", rows.size());
        putNumber(output, "elapsed_ms", elapsed); putNumber(output, "memory_mb", memory); output.put("feature_store_sha256", store.path("features_sha256").asText());
        if (args.string("out") != null) writePretty(Path.of(args.string("out")), output);
        ObjectNode response = output.deepCopy();
        response.set("out", args.string("out") == null ? NullNode.instance : JSON.textNode(Path.of(args.string("out")).toAbsolutePath().normalize().toString()));
        stdout.print(NodePrettyJson.write(response));
    }

    private record Parsed(String command, Map<String, Object> options) {
        String string(String name) { Object value = options.get(name); return value instanceof String text ? text : null; }
        String stringOr(String name, String fallback) { String value = string(name); return value == null || value.isEmpty() ? fallback : value; }
    }

    private static Parsed parse(String[] arguments) {
        Map<String, Object> options = new LinkedHashMap<>(); List<String> positional = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!argument.startsWith("--")) positional.add(argument);
            else {
                String key = argument.substring(2).replace('-', '_');
                if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")) options.put(key, Boolean.TRUE);
                else options.put(key, arguments[++index]);
            }
        }
        return new Parsed(positional.isEmpty() ? "help" : positional.get(0), options);
    }

    private static JsonNode readJson(Path path) throws IOException { return MAPPER.readTree(Files.readString(path.toAbsolutePath().normalize())); }
    private static void writePretty(Path path, JsonNode value) throws IOException { writeText(path, NodePrettyJson.write(value)); }
    private static void writeText(Path path, String value) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(); if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static List<String> commaList(String value, boolean lowercase) {
        List<String> out = new ArrayList<>(); for (String item : value.split(",")) { String normalized = item.trim(); if (!normalized.isEmpty()) out.add(lowercase ? normalized.toLowerCase() : normalized); } return out;
    }
    private static ArrayNode array(JsonNode node) { return node != null && node.isArray() ? (ArrayNode) node : JSON.arrayNode(); }
    private static double number(String value) { try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return Double.NaN; } }
    private static void putNumber(ObjectNode object, String field, double value) { if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) object.put(field, (long) value); else object.put(field, value); }
}
