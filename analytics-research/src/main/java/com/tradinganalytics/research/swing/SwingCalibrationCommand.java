package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unregistered command adapter for {@code tools/swing-calibrate.mjs}. */
public final class SwingCalibrationCommand {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SwingCalibrationCommand() {}

    @FunctionalInterface
    public interface BackfillProvider {
        JsonNode backfill(String asset, double years, Path cacheDirectory, long now) throws Exception;
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        Clock clock = Clock.systemUTC();
        return run(arguments, stdout, stderr,
                (asset, years, cache, now) -> SwingBackfill.backfillAsset(asset,
                        new SwingBackfill.Options(years, cache, now)), clock, Path.of("").toAbsolutePath().normalize());
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr, BackfillProvider provider,
            Clock clock, Path workingDirectory) {
        try {
            Map<String, String> args = parse(arguments);
            List<String> assets = commaList(args.getOrDefault("assets", "btc,eth"));
            double years = Math.max(1, Math.min(3, numberOr(args.getOrDefault("years", "3"), 3)));
            String defaultOut = "data/swing-calibration/" + clock.instant().atZone(ZoneOffset.UTC).toLocalDate() + ".json";
            Path out = resolve(workingDirectory, args.getOrDefault("out", defaultOut));
            double cost = number(args.getOrDefault("cost_pct", "0.20"));
            double slippage = number(args.getOrDefault("slippage_pct", "0.10"));
            double minHoldout = Math.max(1, numberOr(args.getOrDefault("min_holdout_signals", "5"), 5));
            double minCoverage = Math.min(1, Math.max(0, numberOr(args.getOrDefault("min_coverage", "0.80"), .8)));
            double minRegimes = Math.max(1, numberOr(args.getOrDefault("min_regimes", "3"), 3));
            double minTrain = Math.max(1, numberOr(args.getOrDefault("min_train_signals", "3"), 3));
            Path cache = resolve(workingDirectory, args.getOrDefault("cache_dir", "data/swing-calibration/cache"));
            ArrayNode sharedCandidates = args.containsKey("candidates")
                    ? SwingCrossValidator.array(read(resolve(workingDirectory, args.get("candidates")))).deepCopy()
                    : SwingCalibration.defaultCandidates();
            ArrayNode datasets = JSON.arrayNode(); JsonNode inputContract = null;
            if (args.containsKey("input")) {
                JsonNode supplied = read(resolve(workingDirectory, args.get("input")));
                if (supplied.has("candidates") && !supplied.get("candidates").isNull()) sharedCandidates = SwingCrossValidator.array(supplied.get("candidates")).deepCopy();
                inputContract = supplied;
                if (supplied.path("datasets").isArray()) datasets.addAll((ArrayNode) supplied.path("datasets"));
                else for (String asset : assets) { ObjectNode dataset = JSON.objectNode().put("asset", asset); if (supplied.isObject()) dataset.setAll((ObjectNode) supplied); datasets.add(dataset); }
            } else {
                if (provider == null) throw new IllegalArgumentException("historical backfill provider is not configured");
                for (String asset : assets) {
                    try {
                        JsonNode backfill = provider.backfill(asset, years, cache, clock.millis());
                        for (JsonNode dataset : SwingCrossValidator.array(backfill.get("datasets"))) datasets.add(dataset.deepCopy());
                    } catch (Exception exception) {
                        for (Spec spec : List.of(new Spec("fallen_knives", null, 1), new Spec("flying_rocket", "A", -1), new Spec("flying_rocket", "B", -1))) {
                            ObjectNode failed = JSON.objectNode().put("asset", asset).put("symbol", symbol(asset)).put("framework", spec.framework());
                            failed.set("channel", spec.channel() == null ? NullNode.instance : JSON.textNode(spec.channel()));
                            failed.put("direction", spec.direction()).set("bars", JSON.arrayNode()); failed.set("labels", JSON.arrayNode()); failed.set("features", JSON.arrayNode());
                            failed.put("coverage", "FETCH_FAILED").put("error", exception.getMessage()); datasets.add(failed);
                        }
                    }
                }
            }
            SwingCalibration.Options options = new SwingCalibration.Options(years, cost, slippage, minHoldout,
                    minCoverage, minRegimes, minTrain, .40);
            ObjectNode output = SwingCalibration.calibrate(datasets, inputContract, sharedCandidates, options, clock);
            write(out, output);
            if ("ACTIVE".equals(output.path("activation").asText())) write(workingDirectory.resolve("calibrations/swing-btc-eth.json"), output);
            ObjectNode summary = JSON.objectNode().put("out", out.toString()).put("activation", output.path("activation").asText());
            ArrayNode summaries = JSON.arrayNode();
            for (JsonNode dataset : output.path("datasets")) {
                ObjectNode item = JSON.objectNode(); item.set("asset", dataset.get("asset")); item.set("bars", dataset.get("bars"));
                item.set("labels", dataset.get("labels")); item.set("coverage", dataset.get("feature_coverage")); summaries.add(item);
            }
            summary.set("datasets", summaries); stdout.print(NodePrettyJson.write(summary)); return 0;
        } catch (Exception exception) {
            stderr.println("FAIL — " + exception.getMessage()); return 1;
        }
    }

    private static Map<String, String> parse(String[] arguments) { Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i++) if (arguments[i].startsWith("--")) { String key = arguments[i].substring(2).replace('-', '_');
            out.put(key, i + 1 >= arguments.length || arguments[i + 1].startsWith("--") ? "true" : arguments[++i]); } return out; }
    private static List<String> commaList(String value) { List<String> out = new ArrayList<>(); for (String item : value.split(",")) {
        String normalized = item.trim().toLowerCase(); if (!normalized.isEmpty()) out.add(normalized); } return out; }
    private static String symbol(String asset) { return switch (asset) { case "btc" -> "BTCUSDT"; case "eth" -> "ETHUSDT"; default -> asset.toUpperCase() + "USDT"; }; }
    private static double number(String value) { try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return Double.NaN; } }
    private static double numberOr(String value, double fallback) { double number = number(value); return !Double.isFinite(number) || number == 0 ? fallback : number; }
    private static Path resolve(Path root, String value) { Path path = Path.of(value); return path.isAbsolute() ? path.normalize() : root.resolve(path).toAbsolutePath().normalize(); }
    private static JsonNode read(Path path) throws Exception { return MAPPER.readTree(Files.readString(path)); }
    private static void write(Path path, JsonNode value) throws Exception { if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, NodePrettyJson.write(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
    private record Spec(String framework, String channel, int direction) {}
}
