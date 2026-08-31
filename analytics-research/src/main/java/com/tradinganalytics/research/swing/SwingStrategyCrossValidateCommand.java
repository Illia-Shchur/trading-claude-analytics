package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Unregistered command adapter for {@code tools/swing-strategy-cross-validate.mjs}. */
public final class SwingStrategyCrossValidateCommand {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SwingStrategyCrossValidateCommand() {}

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        return run(arguments, stdout, stderr, Clock.systemUTC());
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr, Clock clock) {
        try {
            Map<String, String> options = parse(arguments);
            for (String name : new String[] {"cache", "strategy", "precommit", "feature_seal", "out"}) {
                if (!options.containsKey(name)) throw new IllegalArgumentException("--" + name.replace('_', '-') + " is required");
            }
            Path output = Path.of(options.get("out")).toAbsolutePath().normalize();
            if (Files.exists(output)) throw new IllegalArgumentException("one-time output already exists: " + output);
            ObjectNode store = SwingEngine.readFeatureStoreArtifact(Path.of(options.get("cache")));
            ObjectNode result = SwingStrategyCrossValidator.validateFrozenStrategy(SwingEngine.decodeFeatureStore(store),
                    read(Path.of(options.get("strategy"))), read(Path.of(options.get("precommit"))),
                    store.path("features_sha256").asText(), read(Path.of(options.get("feature_seal"))), clock);
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.writeString(output, NodePrettyJson.write(result), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            ObjectNode summary = JSON.objectNode().put("output", output.toString()).put("decision", result.path("decision").asText());
            for (String field : new String[] {"checks", "metrics", "stressed_metrics", "calendar_years", "chronological_blocks", "coverage"})
                summary.set(field, result.get(field).deepCopy());
            stdout.print(NodePrettyJson.write(summary));
            return 0;
        } catch (Exception exception) {
            // The Node entry point lets the exception escape. Preserve its stable
            // terminal diagnostic without copying runtime-specific stack frames.
            stderr.println("Error: " + exception.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parse(String[] arguments) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            if (!arguments[index].startsWith("--")) continue;
            String key = arguments[index].substring(2).replace('-', '_');
            out.put(key, index + 1 >= arguments.length || arguments[index + 1].startsWith("--") ? "true" : arguments[++index]);
        }
        return out;
    }

    private static JsonNode read(Path path) throws Exception { return MAPPER.readTree(Files.readString(path.toAbsolutePath().normalize())); }
}
