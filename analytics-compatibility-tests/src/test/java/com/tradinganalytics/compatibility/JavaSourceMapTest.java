package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Proves the retired JavaScript inventory stays mapped and cannot return after cutover. */
class JavaSourceMapTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IMPLEMENTATIONS =
            Set.of("PORTED", "PARTIAL", "PENDING", "ABSORBED");
    private static final Set<String> SPRING =
            Set.of("REGISTERED", "PENDING", "NOT_REQUIRED");
    private static final Set<String> TEST_STATUS = Set.of("MAPPED", "PARTIAL", "PENDING", "RETIRED");

    @Test
    void inventoryExactlyMatchesEveryProductionMjsAndHasEvidenceForCompletedRows() throws Exception {
        Path root = RepositoryRoot.find();
        JsonNode map = JSON.readTree(root.resolve("docs/java-source-map.json").toFile());
        assertThat(map.path("schema").asText()).isEqualTo("java-source-map/1");
        assertThat(map.path("entries").isArray()).isTrue();

        Set<String> actual = new TreeSet<>();
        try (var files = Files.list(root.resolve("tools"))) {
            files.filter(path -> path.getFileName().toString().endsWith(".mjs"))
                    .map(path -> "tools/" + path.getFileName())
                    .forEach(actual::add);
        }
        Set<String> declared = new TreeSet<>();
        Set<String> duplicates = new HashSet<>();
        Set<String> registeredCommands = new TreeSet<>();
        for (JsonNode entry : map.path("entries")) {
            String source = entry.path("source").asText();
            if (!declared.add(source)) duplicates.add(source);
            assertThat(IMPLEMENTATIONS).as(source).contains(entry.path("implementation").asText());
            assertThat(SPRING).as(source).contains(entry.path("spring_entrypoint").asText());
            if (Set.of("PORTED", "ABSORBED").contains(entry.path("implementation").asText())) {
                assertExisting(root, entry, "java_owner", source);
                assertExisting(root, entry, "test_owner", source);
            }
            if ("REGISTERED".equals(entry.path("spring_entrypoint").asText())) {
                assertThat(entry.path("implementation").asText()).as(source).isEqualTo("PORTED");
                registeredCommands.add(source.substring("tools/".length(), source.length() - ".mjs".length()));
            }
        }
        assertThat(duplicates).isEmpty();
        assertThat(actual).as("all ledger-tracked JavaScript sources are retired after Java cutover").isEmpty();
        assertThat(declared).hasSize(map.path("entries").size());
        for (JsonNode entry : map.path("workflow_commands")) {
            String source = entry.path("source").asText();
            assertThat(entry.path("status").asText()).as(source).isEqualTo("PORTED");
            assertThat(Files.isRegularFile(root.resolve(source))).as(source + " exists").isTrue();
            assertExisting(root, entry, "java_owner", source);
            assertExisting(root, entry, "test_owner", source);
            assertThat(entry.path("spring_command").asText()).as(source + " spring command").isNotBlank();
            assertThat(registeredCommands.add(entry.path("spring_command").asText()))
                    .as("duplicate Spring command for " + source).isTrue();
        }
        assertThat(springCommandNames(root)).containsExactlyElementsOf(registeredCommands);

        assertTestInventory(root, map.path("test_entries"));
        assertIgnoredInventory(root, map.path("ignored_entries"));
        assertWorkflowInventory(root, map.path("workflow_entries"));
    }

    @Test
    void cutoverHasNoJavaScriptRuntimeOrNodePackageSurface() throws Exception {
        Path root = RepositoryRoot.find();
        assertThat(Files.exists(root.resolve("package.json"))).isFalse();
        assertThat(Files.exists(root.resolve("package-lock.json"))).isFalse();

        Set<String> scripts = new TreeSet<>();
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        return !relative.startsWith(".git/")
                                && !relative.startsWith(".claude/worktrees/")
                                && !relative.contains("/target/")
                                && !relative.startsWith("node_modules/")
                                && (relative.endsWith(".js")
                                        || relative.endsWith(".mjs")
                                        || relative.endsWith(".cjs"));
                    })
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .forEach(scripts::add);
        }
        assertThat(scripts).as("current-checkout JavaScript sources").isEmpty();

        Pattern runtime = Pattern.compile("(?m)^\\s*(?:run:\\s*)?(?:node\\s+|npm\\s+(?:ci|install|run|test)\\b)");
        Set<String> activeRuntimeReferences = new TreeSet<>();
        for (String directory : Set.of(".github/workflows", ".agents/skills", ".claude/skills")) {
            Path start = root.resolve(directory);
            if (!Files.isDirectory(start)) continue;
            try (var files = Files.walk(start)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (runtime.matcher(Files.readString(file)).find()) {
                        activeRuntimeReferences.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(activeRuntimeReferences).as("active Node/npm runtime references").isEmpty();
    }

    private static void assertIgnoredInventory(Path root, JsonNode entries) throws Exception {
        Set<String> declared = new TreeSet<>();
        for (JsonNode entry : entries) {
            String source = entry.path("source").asText();
            assertThat(declared.add(source)).as("duplicate " + source).isTrue();
            assertThat(IMPLEMENTATIONS).as(source)
                    .contains(entry.path("implementation").asText());
            if (Set.of("PORTED", "ABSORBED").contains(entry.path("implementation").asText())) {
                assertExisting(root, entry, "java_owner", source);
                assertExisting(root, entry, "test_owner", source);
            }
        }

        Set<String> actual = new TreeSet<>();
        Path ignoredRoot = root.resolve(".report-run");
        if (Files.isDirectory(ignoredRoot)) {
            try (var files = Files.walk(ignoredRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".js")
                                || path.toString().endsWith(".mjs")
                                || path.toString().endsWith(".cjs"))
                        .map(path -> root.relativize(path).toString().replace('\\', '/'))
                        .forEach(actual::add);
            }
        }
        assertThat(actual).as("absorbed historical JavaScript is retired after Java cutover").isEmpty();
        assertThat(declared).hasSize(entries.size());
    }

    private static void assertTestInventory(Path root, JsonNode entries) throws Exception {
        Set<String> actual = new TreeSet<>();
        try (var files = Files.list(root.resolve("test"))) {
            files.filter(path -> path.getFileName().toString().endsWith(".mjs"))
                    .map(path -> "test/" + path.getFileName()).forEach(actual::add);
        }
        Set<String> declared = new TreeSet<>(), inventory = new TreeSet<>();
        for (JsonNode entry : entries) {
            String source = entry.path("source").asText();
            assertThat(declared.add(source)).as("duplicate " + source).isTrue();
            assertThat(TEST_STATUS).as(source).contains(entry.path("status").asText());
            if (Set.of("MAPPED", "RETIRED").contains(entry.path("status").asText())) {
                assertExisting(root, entry, "java_test", source);
                assertExistingPaths(root, entry.path("companion_tests"), source + " companion_tests");
            }
            if (!"RETIRED".equals(entry.path("status").asText())) inventory.add(source);
        }
        assertThat(inventory).containsExactlyElementsOf(actual);
    }

    private static void assertWorkflowInventory(Path root, JsonNode entries) throws Exception {
        Set<String> expected = new TreeSet<>();
        for (String skillRoot : Set.of(".agents/skills", ".claude/skills")) {
            Path directory = root.resolve(skillRoot);
            if (!Files.isDirectory(directory)) continue;
            try (var files = Files.walk(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".js")
                                || path.toString().endsWith(".mjs")
                                || path.toString().endsWith(".cjs"))
                        .map(path -> root.relativize(path).toString().replace('\\', '/'))
                        .forEach(expected::add);
            }
        }
        Set<String> declared = new TreeSet<>(), inventory = new TreeSet<>();
        for (JsonNode entry : entries) {
            String source = entry.path("source").asText();
            assertThat(declared.add(source)).as("duplicate " + source).isTrue();
            assertThat(TEST_STATUS).as(source).contains(entry.path("status").asText());
            if (Set.of("MAPPED", "RETIRED").contains(entry.path("status").asText())) {
                assertExisting(root, entry, "java_owner", source);
                assertExisting(root, entry, "test_owner", source);
            }
            if (!"RETIRED".equals(entry.path("status").asText())) inventory.add(source);
        }
        assertThat(inventory).containsExactlyElementsOf(expected);
    }

    private static void assertExisting(Path root, JsonNode entry, String field, String source) {
        assertThat(entry.path(field).isTextual()).as(source + " " + field).isTrue();
        assertThat(Files.isRegularFile(root.resolve(entry.path(field).asText())))
                .as(source + " " + field + " exists").isTrue();
    }

    private static void assertExistingPaths(Path root, JsonNode paths, String label) {
        if (paths.isMissingNode()) return;
        assertThat(paths.isArray()).as(label).isTrue();
        for (JsonNode path : paths) {
            assertThat(path.isTextual()).as(label).isTrue();
            assertThat(Files.isRegularFile(root.resolve(path.asText())))
                    .as(label + " " + path.asText() + " exists").isTrue();
        }
    }

    private static Set<String> springCommandNames(Path root) throws Exception {
        Pattern annotation = Pattern.compile("@Command\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
        Pattern declaration = Pattern.compile("(?:public\\s+)?(?:static\\s+)?(?:final\\s+)?class\\s+([A-Za-z0-9_]+)");
        Set<String> names = new TreeSet<>();
        Path sourceRoot = root.resolve("analytics-cli/src/main/java");
        String rootCommand = Files.readString(sourceRoot.resolve(
                "com/tradinganalytics/cli/AnalyticsCommand.java"));
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher matcher = annotation.matcher(source);
                while (matcher.find()) {
                    Matcher classMatcher = declaration.matcher(source.substring(matcher.end()));
                    if (classMatcher.find()
                            && rootCommand.contains(classMatcher.group(1) + ".class")) {
                        names.add(matcher.group(1));
                    }
                }
            }
        }
        return names;
    }
}
