package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import com.tradinganalytics.marketdata.SnapshotPanels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Read-only two-snapshot boundary tripwire, replacing {@code tools/tripwire.mjs}. */
@Component
@Command(name = "tripwire", description = "Compare the two newest stored market snapshots")
public class TripwireCommand implements Callable<Integer> {
    @Option(names = "--dir", defaultValue = "data/runs")
    private Path runsDirectory;

    @Option(names = "--checkpoints")
    private String checkpointsArgument;

    @Spec
    private CommandSpec spec;

    private final Path repositoryRoot;
    private final ObjectMapper json;

    public TripwireCommand() {
        this(RepositoryLayout.locate(), new ObjectMapper());
    }

    public TripwireCommand(Path repositoryRoot, ObjectMapper json) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.json = json;
    }

    @Override
    public Integer call() {
        Path dataRoot = repositoryRoot.resolve("data").normalize();
        Path runs = (runsDirectory == null ? repositoryRoot.resolve("data/runs")
                : repositoryRoot.resolve(runsDirectory)).toAbsolutePath().normalize();
        if (!runs.equals(dataRoot) && !runs.startsWith(dataRoot)) {
            return fail("refusing to read outside data/: " + runs);
        }

        ObjectNode checkpoints;
        try {
            if (checkpointsArgument == null) {
                checkpoints = json.createObjectNode();
            } else {
                String raw = checkpointsArgument.startsWith("@")
                        ? Files.readString(Path.of(checkpointsArgument.substring(1))) : checkpointsArgument;
                JsonNode parsed = json.readTree(raw);
                if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("checkpoints must be a JSON object");
                checkpoints = (ObjectNode) parsed;
            }
        } catch (Exception exception) {
            return fail(exception.getMessage());
        }

        List<Path> entries;
        try (var stream = Files.list(runs)) {
            entries = stream.filter(Files::isDirectory)
                    .map(path -> path.resolve("snapshot.json"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getParent().getFileName().toString()))
                    .toList();
        } catch (Exception exception) {
            return fail("cannot read " + runs + ": " + exception.getMessage());
        }

        if (entries.size() < 2) {
            ObjectNode output = json.createObjectNode();
            output.putArray("crossings");
            output.put("n_crossings", 0);
            output.put("note", "need ≥2 stored snapshots in " + runs + " to diff — found " + entries.size()
                    + ". Run tools/snapshot.mjs at least twice.");
            emit(output);
            return 0;
        }

        try {
            JsonNode previous = json.readTree(Files.readString(entries.get(entries.size() - 2)));
            JsonNode next = json.readTree(Files.readString(entries.get(entries.size() - 1)));
            ObjectNode diff = SnapshotPanels.tripwireDiff(asObject(previous.get("snapshot")),
                    asObject(next.get("snapshot")), checkpoints);
            ObjectNode output = json.createObjectNode();
            copy(output, "prev_run_id", previous.get("run_id"));
            copy(output, "next_run_id", next.get("run_id"));
            copy(output, "prev_fetched_at", previous.get("fetched_at"));
            copy(output, "next_fetched_at", next.get("fetched_at"));
            output.setAll(diff);
            emit(output);
            return 0;
        } catch (Exception exception) {
            return fail(exception.getMessage());
        }
    }

    private static ObjectNode asObject(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : JsonNodeFactoryHolder.EMPTY.deepCopy();
    }

    private void emit(JsonNode value) {
        spec.commandLine().getOut().print(NodePrettyJson.write(value));
    }

    private int fail(String message) {
        spec.commandLine().getErr().println("error: " + message);
        return 1;
    }

    private static void copy(ObjectNode target, String name, JsonNode value) {
        target.set(name, value == null ? com.fasterxml.jackson.databind.node.NullNode.instance : value.deepCopy());
    }

    private static final class JsonNodeFactoryHolder {
        private static final ObjectNode EMPTY = new ObjectMapper().createObjectNode();
    }
}
