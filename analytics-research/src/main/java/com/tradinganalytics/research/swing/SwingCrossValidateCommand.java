package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Unregistered command adapter for {@code tools/swing-cross-validate.mjs}. */
public final class SwingCrossValidateCommand {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SwingCrossValidateCommand() {}

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        return run(arguments, stdout, stderr, Clock.systemUTC());
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr, Clock clock) {
        try {
            Map<String, String> args = parse(arguments);
            for (String name : new String[] {"cache", "candidates", "precommit", "out"}) {
                if (!args.containsKey(name)) throw new IllegalArgumentException("requires --cache, --candidates, --precommit, and --out");
            }
            Path output = Path.of(args.get("out")).toAbsolutePath().normalize();
            if (Files.exists(output)) throw new IllegalArgumentException("validation output already exists; refuse to reopen");
            JsonNode payload = read(Path.of(args.get("candidates")));
            JsonNode candidates = payload.isArray() ? payload : payload.get("candidates");
            JsonNode precommit = read(Path.of(args.get("precommit")));
            ObjectNode store = SwingEngine.readFeatureStoreArtifact(Path.of(args.get("cache")));
            ArrayNode rows = SwingEngine.decodeFeatureStore(store);
            JsonNode seal = args.containsKey("feature_seal") ? read(Path.of(args.get("feature_seal"))) : null;
            ObjectNode result = SwingCrossValidator.validateCrossAsset(rows, candidates, precommit,
                    store.path("features_sha256").asText(), seal, clock);
            write(output, result);
            ObjectNode summary = JSON.objectNode().put("out", output.toString()).put("verdict", result.path("verdict").asText())
                    .put("primary_accepted", result.path("primary_accepted").asBoolean());
            ArrayNode reports = JSON.arrayNode();
            for (JsonNode report : result.path("reports")) {
                ObjectNode item = JSON.objectNode().put("id", report.path("candidate").path("id").asText())
                        .put("accepted", report.path("accepted").asBoolean())
                        .set("trades", report.path("metrics").get("completed_trades"));
                item.set("expectancy_r", report.path("metrics").get("expectancy_r"));
                item.set("profit_factor", report.path("metrics").get("profit_factor"));
                reports.add(item);
            }
            summary.set("reports", reports);
            stdout.print(NodePrettyJson.write(summary));
            return 0;
        } catch (Exception exception) {
            stderr.println("FAIL — " + exception.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parse(String[] arguments) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index++) if (arguments[index].startsWith("--")) {
            String name = arguments[index].substring(2).replace('-', '_');
            out.put(name, index + 1 >= arguments.length || arguments[index + 1].startsWith("--") ? "true" : arguments[++index]);
        }
        return out;
    }

    private static JsonNode read(Path path) throws Exception { return MAPPER.readTree(Files.readString(path.toAbsolutePath().normalize())); }
    private static void write(Path path, JsonNode value) throws Exception {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, NodePrettyJson.write(value), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
