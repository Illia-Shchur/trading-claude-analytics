package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.PrettyCanonicalJson;
import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import com.tradinganalytics.research.calibration.CalibrationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "calib-registry", description = "Inspect and update calibration-registry/1 history")
public class CalibrationRegistryCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1")
    private String operation;

    @Parameters(index = "1", arity = "0..1")
    private String argument;

    @Option(names = "--framework")
    private String framework;

    @Option(names = "--verdict")
    private String verdict;

    @Option(names = "--since")
    private String since;

    @Option(names = "--json")
    private boolean jsonOutput;

    @Spec
    private CommandSpec spec;

    private final Path repositoryRoot;
    private final Path registryPath;
    private final ObjectMapper json;
    private final CalibrationRegistry registry;

    public CalibrationRegistryCommand() {
        this(RepositoryLayout.locate(), new ObjectMapper());
    }

    CalibrationRegistryCommand(Path repositoryRoot, ObjectMapper json) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.registryPath = this.repositoryRoot.resolve("reports/calibration-registry.json");
        this.json = json;
        this.registry = new CalibrationRegistry(json);
    }

    @Override
    public Integer call() throws Exception {
        if ("validate".equals(operation)) {
            return validate();
        }
        if ("list".equals(operation)) {
            return list();
        }
        if ("append".equals(operation)) {
            return append();
        }
        if ("match".equals(operation)) {
            return match();
        }
        spec.commandLine().getErr().println("usage: analytics calib-registry <list|validate|append|match> ...");
        return 1;
    }

    private int validate() throws Exception {
        ObjectNode value = registry.load(registryPath);
        CalibrationRegistry.ValidationResult result = registry.validate(value);
        if (result.ok()) {
            spec.commandLine().getErr().printf("OK — %d entries, schema valid%n", value.withArray("entries").size());
            return 0;
        }
        spec.commandLine().getErr().printf("FAIL — %d error(s):%n", result.errors().size());
        result.errors().forEach(error -> spec.commandLine().getErr().println("  - " + error));
        return 1;
    }

    private int list() throws Exception {
        ObjectNode value = registry.load(registryPath);
        ArrayNode all = value.withArray("entries");
        ArrayNode selected = json.createArrayNode();
        for (JsonNode entry : all) {
            String entryFramework = entry.path("framework").asText();
            if (framework != null && !(entryFramework.equals(framework) || entryFramework.equals("both"))) {
                continue;
            }
            if (verdict != null && !entry.path("verdict").asText().equals(verdict)) {
                continue;
            }
            if (since != null && entry.path("date").asText().compareTo(since) < 0) {
                continue;
            }
            selected.add(entry);
        }
        if (jsonOutput) {
            spec.commandLine().getOut().print(PrettyCanonicalJson.write(selected));
            return 0;
        }
        for (JsonNode entry : selected) {
            spec.commandLine().getOut().printf("%s  %-13s %-24s %s%n",
                    entry.path("date").asText(), entry.path("framework").asText(),
                    entry.path("verdict").asText(), entry.path("name").asText());
        }
        spec.commandLine().getErr().printf("%n%d of %d entries%n", selected.size(), all.size());
        return 0;
    }

    private int append() throws Exception {
        if (argument == null) {
            spec.commandLine().getErr().println(
                    "usage: analytics calib-registry append <payload.json> (array of entries)");
            return 1;
        }
        Path payload = repositoryRoot.resolve(argument).normalize();
        JsonNode incoming = json.readTree(Files.readString(payload));
        ObjectNode value = registry.append(registry.load(registryPath), incoming);
        CalibrationRegistry.ValidationResult validation = registry.validate(value);
        if (!validation.ok()) {
            spec.commandLine().getErr().println("FAIL — appended entries would break validation:");
            validation.errors().forEach(error -> spec.commandLine().getErr().println("  - " + error));
            return 1;
        }
        int count = incoming.isArray() ? incoming.size() : 1;
        // The Node writer currently appends one LF to canonicalJSON(), which already
        // carries an LF. Preserve those bytes until a versioned contract changes them.
        Files.writeString(registryPath, PrettyCanonicalJson.write(value) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        int total = value.withArray("entries").size();
        spec.commandLine().getErr().printf("appended %d entr%s; registry now has %d%n",
                count, count == 1 ? "y" : "ies", total);
        return 0;
    }

    private int match() throws Exception {
        if (argument == null) {
            spec.commandLine().getErr().println(
                    "usage: analytics calib-registry match \"<tune name or keywords>\" [--framework <t>]");
            return 1;
        }
        var hits = registry.matchRejections(argument, registry.load(registryPath), framework);
        if (hits.isEmpty()) {
            spec.commandLine().getErr().println("no keyword overlap with any rejected/withheld entry");
            return 0;
        }
        for (CalibrationRegistry.Match hit : hits) {
            JsonNode entry = hit.entry();
            spec.commandLine().getOut().printf("[%d] %s %s: %s%n    why: %s%n%n",
                    hit.score(), entry.path("date").asText(), entry.path("verdict").asText(),
                    entry.path("name").asText(), entry.path("why").asText());
        }
        return 0;
    }
}
