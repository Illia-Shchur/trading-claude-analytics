package com.tradinganalytics.contracts.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.tradinganalytics.contracts.json.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;

/**
 * Draft 2020-12 registry for the canonical research contracts under {@code schemas/}.
 * Unknown schemas intentionally pass through the permissive entry point, matching the
 * legacy Node adapter; authoritative boundaries use {@link #validateKnownContractSchema(Object)}.
 */
public final class ResearchSchemaRegistry {
    private static final String SCHEMA_ROOT = "schemas";
    private static final String SCHEMA_SUFFIX = ".schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // The Node registry uses JSON.parse for schema documents, whose duplicate-key rule is
    // last-value-wins. Keep that behavior separate from StrictJson's trust-boundary parser.
    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final List<SchemaDocument> documents;
    private final Map<String, Schema> schemasById;
    private final List<String> schemaIds;

    public ResearchSchemaRegistry() {
        this(defaultClassLoader());
    }

    public ResearchSchemaRegistry(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try {
            List<String> resourceNames = discoverSchemaResources(classLoader);
            if (resourceNames.isEmpty()) {
                throw new IllegalStateException("schema registry found no classpath resources under " + SCHEMA_ROOT);
            }

            Map<String, String> sourceById = new TreeMap<>();
            List<SchemaDocument> loadedDocuments = new ArrayList<>(resourceNames.size());
            for (String resourceName : resourceNames) {
                JsonNode document = readDocument(classLoader, resourceName);
                if (!document.isObject()) {
                    throw new IllegalStateException(resourceName + " must contain a JSON Schema object");
                }
                TreeSet<String> documentIds = new TreeSet<>();
                collectSchemaIds(document, resourceName, sourceById, documentIds);
                String topLevelId = textId(document);
                if (topLevelId == null) {
                    throw new IllegalStateException(resourceName + " has no top-level $id");
                }
                loadedDocuments.add(new SchemaDocument(
                        resourceName.substring((SCHEMA_ROOT + "/").length()),
                        topLevelId,
                        List.copyOf(documentIds)));
            }

            SchemaRegistryConfig configuration = SchemaRegistryConfig.builder()
                    .failFast(false)
                    .formatAssertionsEnabled(true)
                    // The repository intentionally uses stable relative contract IDs such as
                    // strategy-run/3; AJV accepts them and they are the public lookup keys.
                    .schemaIdValidator((id, root, schemaLocation, evaluationPath, context) -> true)
                    .build();
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaRegistryConfig(configuration).schemas(sourceById));

            Map<String, Schema> compiled = new LinkedHashMap<>();
            for (String schemaId : sourceById.keySet()) {
                try {
                    compiled.put(schemaId, registry.getSchema(SchemaLocation.of(schemaId)));
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("schema registry cannot compile " + schemaId, exception);
                }
            }
            this.documents = List.copyOf(loadedDocuments);
            this.schemasById = Collections.unmodifiableMap(compiled);
            this.schemaIds = List.copyOf(compiled.keySet());
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("cannot initialize research schema registry", exception);
        }
    }

    public static ResearchSchemaRegistry defaultRegistry() {
        return DefaultHolder.INSTANCE;
    }

    public boolean hasContractSchema(String schemaId) {
        return schemaId != null && schemasById.containsKey(schemaId);
    }

    /** Matches validateContractSchema: missing and unknown schema IDs are intentionally ignored. */
    public boolean validateContractSchema(Object value) {
        JsonNode node = toTree(value);
        String schemaId = schemaId(node);
        if (!hasContractSchema(schemaId)) {
            return true;
        }
        validate(schemaId, node);
        return true;
    }

    /** Fails closed when a value does not identify a schema owned by this registry. */
    public boolean validateKnownContractSchema(Object value) {
        JsonNode node = toTree(value);
        String schemaId = schemaId(node);
        if (!hasContractSchema(schemaId)) {
            throw new IllegalArgumentException("schema registry does not recognize "
                    + (schemaId == null || schemaId.isEmpty() ? "?" : schemaId));
        }
        validate(schemaId, node);
        return true;
    }

    public boolean validateContractJson(String strictJson) {
        return validateContractSchema(StrictJson.parse(strictJson, "contract JSON"));
    }

    public boolean validateKnownContractJson(String strictJson) {
        return validateKnownContractSchema(StrictJson.parse(strictJson, "contract JSON"));
    }

    public List<String> listContractSchemas() {
        return schemaIds;
    }

    public List<SchemaDocument> listSchemaDocuments() {
        return documents;
    }

    public List<Boolean> validateAllSchemas(Iterable<?> values) {
        List<Boolean> results = new ArrayList<>();
        for (Object value : values) {
            results.add(validateContractSchema(value));
        }
        return List.copyOf(results);
    }

    private void validate(String schemaId, JsonNode value) {
        List<Error> errors = schemasById.get(schemaId).validate(value.toString(), InputFormat.JSON);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream().map(Error::toString).sorted().toList();
            throw new ContractSchemaValidationException(schemaId, messages);
        }
    }

    private static JsonNode toTree(Object value) {
        if (value instanceof JsonNode node) {
            return node;
        }
        try {
            return MAPPER.valueToTree(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("contract value cannot be represented as JSON", exception);
        }
    }

    private static String schemaId(JsonNode value) {
        if (!value.isObject()) {
            return null;
        }
        JsonNode schema = value.get("schema");
        return schema != null && schema.isTextual() ? schema.textValue() : null;
    }

    private static void collectSchemaIds(
            JsonNode node,
            String resourceName,
            Map<String, String> sourceById,
            Set<String> documentIds) {
        if (node.isObject()) {
            String id = textId(node);
            if (id != null) {
                String previous = sourceById.putIfAbsent(id, node.toString());
                if (previous != null) {
                    throw new IllegalStateException("duplicate schema $id " + id + " in " + resourceName);
                }
                documentIds.add(id);
            }
            node.elements().forEachRemaining(child -> collectSchemaIds(child, resourceName, sourceById, documentIds));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectSchemaIds(child, resourceName, sourceById, documentIds));
        }
    }

    private static String textId(JsonNode node) {
        JsonNode id = node.get("$id");
        return id != null && id.isTextual() ? id.textValue() : null;
    }

    private static JsonNode readDocument(ClassLoader classLoader, String resourceName) throws IOException {
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("missing schema resource " + resourceName);
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try {
                JsonNode document = SCHEMA_MAPPER.readTree(text);
                if (document == null) {
                    throw new IOException("empty schema resource " + resourceName);
                }
                return document;
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IOException("invalid schema resource " + resourceName, exception);
            }
        }
    }

    private static List<String> discoverSchemaResources(ClassLoader classLoader)
            throws IOException, URISyntaxException {
        TreeSet<String> resources = new TreeSet<>();
        Enumeration<URL> roots = classLoader.getResources(SCHEMA_ROOT);
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if ("file".equals(root.getProtocol())) {
                Path directory = Path.of(root.toURI());
                try (var children = Files.list(directory)) {
                    children.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(SCHEMA_SUFFIX))
                            .map(name -> SCHEMA_ROOT + "/" + name)
                            .forEach(resources::add);
                }
            } else if ("jar".equals(root.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) root.openConnection();
                connection.setUseCaches(false);
                Enumeration<JarEntry> entries = connection.getJarFile().entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith(SCHEMA_ROOT + "/") && name.endsWith(SCHEMA_SUFFIX)
                            && name.indexOf('/', SCHEMA_ROOT.length() + 1) < 0) {
                        resources.add(name);
                    }
                }
            }
        }
        return List.copyOf(resources);
    }

    private static ClassLoader defaultClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? ResearchSchemaRegistry.class.getClassLoader() : context;
    }

    public record SchemaDocument(String filename, String topLevelId, List<String> schemaIds) {
        public SchemaDocument {
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(topLevelId, "topLevelId");
            schemaIds = List.copyOf(schemaIds);
        }
    }

    private static final class DefaultHolder {
        private static final ResearchSchemaRegistry INSTANCE = new ResearchSchemaRegistry();
    }
}
