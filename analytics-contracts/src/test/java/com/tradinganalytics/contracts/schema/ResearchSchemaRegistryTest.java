package com.tradinganalytics.contracts.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.contracts.json.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResearchSchemaRegistryTest {
    private static final String HASH = "a".repeat(64);
    private static ResearchSchemaRegistry registry;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadRegistry() {
        registry = new ResearchSchemaRegistry();
    }

    @Test
    void loadsAndCompilesTheExactNodeSchemaCorpusIncludingEmbeddedIds() {
        assertThat(registry.listSchemaDocuments()).hasSize(127);
        assertThat(registry.listContractSchemas()).hasSize(144).isSorted().doesNotHaveDuplicates();

        String filenames = registry.listSchemaDocuments().stream()
                .map(ResearchSchemaRegistry.SchemaDocument::filename)
                .sorted()
                .reduce("", (left, right) -> left + right + "\n");
        String ids = String.join("\n", registry.listContractSchemas()) + "\n";
        assertThat(Sha256.hex(filenames))
                .isEqualTo("181465220adaeecedc8767912d427e0328f641ec62e7e915c1efb7bb9ee8fda3");
        assertThat(Sha256.hex(ids))
                .isEqualTo("22053aa442186d61491d38ba7d767d2a55d84e432a8897b848bd720c15917e98");

        assertThat(registry.listSchemaDocuments())
                .allSatisfy(document -> {
                    assertThat(document.filename()).endsWith(".schema.json");
                    assertThat(document.schemaIds()).contains(document.topLevelId());
                    assertThat(document.schemaIds()).allMatch(registry::hasContractSchema);
                });
        assertThat(registry.listContractSchemas())
                .contains("https://schemas.local/strategy-v5-statistical-contracts/1")
                .contains("strategy-v5-statistical-input/1")
                .contains("strategy-v5-statistical-genetic-checkpoint/1");
    }

    @Test
    void defaultRegistryIsAStableSingleton() {
        assertThat(ResearchSchemaRegistry.defaultRegistry())
                .isSameAs(ResearchSchemaRegistry.defaultRegistry());
    }

    @Test
    void mirrorsNodePermissiveAndFailClosedBoundaries() {
        assertThat(registry.validateContractSchema(null)).isTrue();
        assertThat(registry.validateContractSchema(StrictJson.parse("{}"))).isTrue();
        assertThat(registry.validateContractSchema(StrictJson.parse("{\"schema\":\"unknown/1\"}"))).isTrue();
        assertThat(registry.hasContractSchema(null)).isFalse();
        assertThat(registry.hasContractSchema("unknown/1")).isFalse();

        assertThatThrownBy(() -> registry.validateKnownContractSchema(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schema registry does not recognize ?");
        assertThatThrownBy(() -> registry.validateKnownContractJson("{\"schema\":\"unknown/1\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schema registry does not recognize unknown/1");
    }

    @Test
    void validatesKnownAnswersCapturedFromAjv2020() {
        String valid = "{\"schema\":\"strategy-gene-space/1\",\"genes\":[{}],"
                + "\"content_sha256\":\"" + HASH + "\"}";
        assertThat(registry.validateKnownContractJson(valid)).isTrue();
        assertThat(registry.validateContractJson(valid)).isTrue();

        assertThatThrownBy(() -> registry.validateKnownContractJson(
                "{\"schema\":\"strategy-gene-space/1\",\"genes\":[],"
                        + "\"content_sha256\":\"" + HASH + "\"}"))
                .isInstanceOfSatisfying(ContractSchemaValidationException.class, exception -> {
                    assertThat(exception.schemaId()).isEqualTo("strategy-gene-space/1");
                    assertThat(exception.validationErrors()).isNotEmpty();
                });
        assertThatThrownBy(() -> registry.validateKnownContractJson(
                "{\"schema\":\"strategy-gene-space/1\",\"genes\":[{}],"
                        + "\"content_sha256\":\"" + HASH + "\",\"extra\":true}"))
                .isInstanceOf(ContractSchemaValidationException.class);
    }

    @Test
    void enablesAjvFormatsEquivalentDateTimeAssertions() {
        String template = "{\"schema\":\"strategy-prospective-reservation/1\",\"version\":1,"
                + "\"status\":\"FROZEN\",\"decision\":\"SHADOW\","
                + "\"lineage_sha256\":\"" + HASH + "\","
                + "\"frozen_start\":\"%s\",\"frozen_end\":\"2026-08-29T00:00:00Z\","
                + "\"content_sha256\":\"" + HASH + "\"}";

        assertThat(registry.validateKnownContractJson(template.formatted("2026-08-28T00:00:00Z"))).isTrue();
        assertThatThrownBy(() -> registry.validateKnownContractJson(template.formatted("not-a-date")))
                .isInstanceOf(ContractSchemaValidationException.class)
                .hasMessageContaining("date-time");
    }

    @Test
    void embeddedIdsResolveTheirParentsSharedDefinitions() {
        String value = "{\"schema\":\"strategy-v5-statistical-input/1\",\"version\":1,"
                + "\"lineage\":{\"dataset_sha256\":\"" + HASH + "\","
                + "\"candidate_set_sha256\":\"" + HASH + "\","
                + "\"feature_set_sha256\":\"" + HASH + "\","
                + "\"label_set_sha256\":\"" + HASH + "\","
                + "\"execution_set_sha256\":\"" + HASH + "\"},"
                + "\"candidates\":[],\"episodes\":[{\"episode_id\":\"e\",\"asset\":\"btc\","
                + "\"decision_time\":\"2026-08-28T00:00:00Z\","
                + "\"resolution_time\":\"2026-08-29T00:00:00Z\",\"eligible\":true,"
                + "\"candidate_returns\":{}}],\"exposure_head_sha256\":\"" + HASH + "\","
                + "\"metadata\":{\"artifact_role\":\"GENESIS\"},"
                + "\"content_sha256\":\"" + HASH + "\"}";
        assertThat(registry.validateKnownContractJson(value)).isTrue();
    }

    @Test
    void validatesCollectionsInInputOrder() {
        List<Object> values = new ArrayList<>();
        values.add(null);
        values.add(StrictJson.parse("{}"));
        values.add(StrictJson.parse("{\"schema\":\"unknown/1\"}"));
        assertThat(registry.validateAllSchemas(values)).containsExactly(true, true, true);
    }

    @Test
    void discoversSchemasFromPackagedJarResources() throws Exception {
        String schema = minimalSchema("jar-contract/1");
        Path jar = temporaryDirectory.resolve("contracts.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("schemas/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("schemas/jar-contract-1.schema.json"));
            output.write(schema.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            ResearchSchemaRegistry jarRegistry = new ResearchSchemaRegistry(loader);
            assertThat(jarRegistry.listSchemaDocuments()).hasSize(1);
            assertThat(jarRegistry.validateKnownContractJson("{\"schema\":\"jar-contract/1\"}"))
                    .isTrue();
        }
    }

    @Test
    void failsFastForMalformedSchemaCorpora() throws Exception {
        assertThatThrownBy(() -> temporaryRegistry(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found no classpath resources");
        assertThatThrownBy(() -> temporaryRegistry(Map.of("primitive.schema.json", "1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain a JSON Schema object");
        assertThatThrownBy(() -> temporaryRegistry(Map.of("missing-id.schema.json", "{\"type\":\"object\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no top-level $id");
        assertThatThrownBy(() -> temporaryRegistry(Map.of("numeric-id.schema.json", "{\"$id\":1}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no top-level $id");
        assertThatThrownBy(() -> temporaryRegistry(Map.of(
                        "a.schema.json", minimalSchema("same/1"),
                        "b.schema.json", minimalSchema("same/1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate schema $id same/1");
        assertThatThrownBy(() -> temporaryRegistry(Map.of(
                        "nested-duplicate.schema.json",
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                + "\"$id\":\"outer/1\",\"$defs\":{\"child\":{\"$id\":\"outer/1\"}}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate schema $id outer/1");
        assertThatThrownBy(() -> temporaryRegistry(Map.of("invalid-json.schema.json", "{")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot initialize research schema registry");
        assertThatThrownBy(() -> temporaryRegistry(Map.of("empty.schema.json", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain a JSON Schema object");
    }

    @Test
    void wrapsClasspathAndMissingResourceIoFailures() throws Exception {
        ClassLoader brokenDiscovery = new ClassLoader(null) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("synthetic discovery failure");
            }
        };
        assertThatThrownBy(() -> new ResearchSchemaRegistry(brokenDiscovery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot initialize research schema registry");

        Path root = createSchemaDirectory(Map.of("missing.schema.json", minimalSchema("missing/1")));
        try (URLClassLoader missingStream = new URLClassLoader(new URL[] {root.toUri().toURL()}, null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        }) {
            assertThatThrownBy(() -> new ResearchSchemaRegistry(missingStream))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot initialize research schema registry");
        }
    }

    @Test
    void supportsNullContextClassLoaderAndDefensivelyCopiesDocumentMetadata() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);
            assertThat(new ResearchSchemaRegistry().listSchemaDocuments()).hasSize(127);
        } finally {
            thread.setContextClassLoader(original);
        }

        List<String> mutableIds = new ArrayList<>(List.of("one/1"));
        var document = new ResearchSchemaRegistry.SchemaDocument("one.schema.json", "one/1", mutableIds);
        mutableIds.add("two/1");
        assertThat(document.schemaIds()).containsExactly("one/1");
        assertThatThrownBy(() -> new ResearchSchemaRegistry.SchemaDocument(null, "one/1", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResearchSchemaRegistry.SchemaDocument("one.schema.json", null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResearchSchemaRegistry.SchemaDocument("one.schema.json", "one/1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResearchSchemaRegistry(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void handlesNonTextSchemaDiscriminatorsLikeOptionalJavascriptProperties() {
        assertThat(registry.validateContractJson("{\"schema\":1}")).isTrue();
        assertThatThrownBy(() -> registry.validateKnownContractJson("{\"schema\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schema registry does not recognize ?");
        assertThatThrownBy(() -> registry.validateKnownContractJson("{\"schema\":\"\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schema registry does not recognize ?");
    }

    private ResearchSchemaRegistry temporaryRegistry(Map<String, String> files) throws Exception {
        Path root = createSchemaDirectory(files);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            return new ResearchSchemaRegistry(loader);
        }
    }

    private Path createSchemaDirectory(Map<String, String> files) throws IOException {
        Path root = Files.createTempDirectory(temporaryDirectory, "schema-corpus-");
        Path schemas = Files.createDirectories(root.resolve("schemas"));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Files.writeString(schemas.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }

    private static String minimalSchema(String id) {
        return "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"$id\":\"" + id + "\",\"type\":\"object\","
                + "\"required\":[\"schema\"],\"properties\":{\"schema\":{\"const\":\""
                + id + "\"}},\"additionalProperties\":false}";
    }
}
