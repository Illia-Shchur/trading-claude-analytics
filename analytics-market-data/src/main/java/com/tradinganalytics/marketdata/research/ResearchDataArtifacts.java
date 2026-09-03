package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Owns immutable artifact files and authoritative Parquet access. */
final class ResearchDataArtifacts {
    private ResearchDataArtifacts() {
    }

    static ResearchData.ParquetArtifact writeParquet(Path stagingJsonl, Path parquetPath) {
        Path input = stagingJsonl.toAbsolutePath().normalize();
        Path output = parquetPath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(output.getParent());
            String digest = JsonHashes.sha256(input);
            Path candidate = output.resolveSibling("." + output.getFileName() + "."
                    + digest.substring(0, 16) + ".tmp");
            Files.deleteIfExists(candidate);
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                    Statement statement = connection.createStatement()) {
                String sql = "COPY (SELECT * FROM read_json_auto('" + sql(input)
                        + "', union_by_name=true)) TO '" + sql(candidate)
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                statement.execute(sql);
            }
            String candidateHash = JsonHashes.sha256(candidate);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(output, "immutable Parquet artifact");
                if (!JsonHashes.sha256(output).equals(candidateHash)) {
                    throw failure("immutable Parquet artifact collision: " + output);
                }
                Files.delete(candidate);
            } else {
                moveNoReplace(candidate, output);
            }
            return new ResearchData.ParquetArtifact(parquetPath, candidateHash, Files.size(output));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw failure("DuckDB Parquet conversion failed; JSONL staging remains at "
                    + stagingJsonl + ": " + error.getMessage(), error);
        }
    }

    static List<ObjectNode> queryParquet(Path parquetPath, ResearchData.QueryOptions options) {
        Path input = parquetPath.toAbsolutePath().normalize();
        if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".parquet")) {
            throw failure("authoritative query requires a Parquet path; JSONL is staging/debug only");
        }
        if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("Parquet input does not exist: " + input);
        }
        PathConfinement.validateSinglyLinkedFile(input, "Parquet input");
        options = options == null ? ResearchData.QueryOptions.all() : options;
        Set<String> columns = parquetColumns(input);
        String timeColumn = columns.contains("event_time") ? "event_time"
                : columns.contains("decision_time") ? "decision_time" : null;
        if ((options.from() != null || options.to() != null) && timeColumn == null) {
            throw failure("authoritative Parquet query requires event_time or decision_time for time bounds");
        }
        List<String> clauses = new ArrayList<>();
        if (options.from() != null) {
            clauses.add(timeColumn + " >= " + options.from());
        }
        if (options.to() != null) {
            clauses.add(timeColumn + " <= " + options.to());
        }
        if (options.assets() != null && !options.assets().isEmpty()) {
            clauses.add("lower(cast(asset as varchar)) IN (" + options.assets().stream()
                    .map(asset -> "'" + asset.toLowerCase(Locale.ROOT).replace("'", "''") + "'")
                    .reduce((left, right) -> left + "," + right).orElse("") + ")");
        }
        String query = "SELECT * FROM read_parquet('" + sql(input) + "')"
                + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + (timeColumn == null ? "" : " ORDER BY " + timeColumn);
        List<ObjectNode> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                ObjectNode row = JsonHashes.mapper().createObjectNode();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    putJdbc(row, metadata.getColumnLabel(index), result.getObject(index));
                }
                rows.add(row);
            }
            return rows;
        } catch (Exception error) {
            throw failure("authoritative Parquet query requires pinned DuckDB ("
                    + error.getMessage() + ")", error);
        }
    }

    static ObjectNode writeLayer(Path root, String datasetId, String layer,
                                 List<ObjectNode> rows, String format) throws IOException {
        if (rows.isEmpty()) {
            return null;
        }
        Path stage = root.resolve(layer).resolve(datasetId + ".jsonl");
        ObjectNode jsonl = writeJsonl(stage, rows);
        if ("jsonl".equals(format)) {
            ObjectNode portable = jsonl.deepCopy();
            portable.put("path", relative(root, stage));
            portable.put("format", "jsonl");
            portable.put("row_count", rows.size());
            portable.remove("rows");
            return portable;
        }
        if (!"parquet".equals(format)) {
            throw failure("unsupported snapshot format " + format + "; use parquet or jsonl");
        }
        Path parquet = root.resolve(layer).resolve(datasetId + ".parquet");
        ResearchData.ParquetArtifact artifact = writeParquet(stage, parquet);
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("path", relative(root, parquet));
        value.put("sha256", artifact.sha256());
        value.put("bytes", artifact.bytes());
        value.put("format", "parquet");
        value.put("row_count", rows.size());
        return value;
    }

    static ArrayNode writePartitions(Path root, String datasetId, String layer,
                                     List<ObjectNode> rows, String format) throws IOException {
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            Instant time = Instant.ofEpochMilli(row.path("event_time").asLong());
            String key = String.join("/", pathSegment(row.path("dataset_version").asText(
                            row.path("dataset_id").asText(datasetId))), pathSegment(row.path("asset").asText("unknown")),
                    pathSegment(row.path("venue").asText("unknown")), pathSegment(row.path("instrument").asText("unknown")),
                    pathSegment(row.path("timeframe").asText("4h")),
                    pathSegment(String.valueOf(time.atZone(ZoneOffset.UTC).getYear())),
                    pathSegment(String.format("%02d", time.atZone(ZoneOffset.UTC).getMonthValue())));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        ArrayNode artifacts = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
            Path directory = root.resolve(layer).resolve(entry.getKey());
            Path stage = directory.resolve("data.jsonl");
            ObjectNode jsonl = writeJsonl(stage, entry.getValue());
            ObjectNode artifact = artifacts.addObject();
            if ("parquet".equals(format)) {
                Path parquet = directory.resolve("data.parquet");
                ResearchData.ParquetArtifact value = writeParquet(stage, parquet);
                artifact.put("path", relative(root, parquet));
                artifact.put("sha256", value.sha256());
                artifact.put("bytes", value.bytes());
                artifact.put("format", "parquet");
            } else {
                artifact.put("path", relative(root, stage));
                artifact.put("sha256", jsonl.path("sha256").asText());
                artifact.put("format", "jsonl");
            }
            artifact.put("row_count", entry.getValue().size());
            artifact.put("rows", entry.getValue().size());
        }
        return artifacts;
    }

    static void validateArtifacts(JsonNode store, Path root, boolean development) {
        List<ObjectNode> artifacts = new ArrayList<>();
        if (store.isObject() && store.hasNonNull("path")) {
            artifacts.add((ObjectNode) store);
        }
        addArtifact(artifacts, store.get("labels"));
        addArtifacts(artifacts, store.get("partitions"));
        addArtifacts(artifacts, store.get("label_partitions"));
        addAuditArtifact(artifacts, store, "raw_path", "raw_sha256");
        addAuditArtifact(artifacts, store, "normalized_path", "normalized_sha256");
        addAuditArtifact(artifacts, store, "quality_path", "quality_sha256");
        for (ObjectNode artifact : artifacts) {
            String relative = artifact.path("path").asText();
            Path resolved;
            try {
                resolved = PathConfinement.resolve(root, relative, "manifest artifact",
                        PathConfinement.ExpectedType.FILE).absolute();
            } catch (RuntimeException error) {
                if (error.getMessage() != null && (error.getMessage().contains("singly-linked")
                        || error.getMessage().contains("symlink"))) {
                    throw failure(error.getMessage(), error);
                }
                throw failure("manifest artifact path is not repository-relative: "
                        + (relative.isEmpty() ? "?" : relative), error);
            }
            if (!artifact.path("sha256").asText().equals(JsonHashes.sha256(resolved))) {
                throw failure("manifest artifact hash mismatch: " + relative);
            }
            if (!development && !artifact.path("audit").asBoolean(false)
                    && !"parquet".equalsIgnoreCase(artifact.path("format").asText())) {
                throw failure("authoritative manifest artifact is not Parquet: " + relative);
            }
        }
    }

    static void validateExistingTree(Path root) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                    Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink()) {
                    throw failure("research snapshot contains a symlink: " + directory);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink()) {
                    throw failure("research snapshot contains a symlink: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw failure("research snapshot contains a non-regular file: " + file);
                }
                PathConfinement.requireSingleLink(file, "research snapshot file");
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    static ObjectNode writeJsonl(Path path, List<? extends JsonNode> rows) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder content = new StringBuilder();
        for (JsonNode row : rows) {
            content.append(JsonHashes.mapper().writeValueAsString(row)).append('\n');
        }
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        String digest = JsonHashes.sha256(bytes);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            PathConfinement.validateSinglyLinkedFile(path, "immutable artifact");
            if (!JsonHashes.sha256(path).equals(digest)) {
                throw failure("immutable artifact collision: " + path);
            }
        } else {
            writeNew(path, bytes);
        }
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("path", path.toString());
        result.put("sha256", digest);
        result.put("rows", rows.size());
        return result;
    }

    static void writeImmutableJson(Path path, ObjectNode value) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            PathConfinement.validateSinglyLinkedFile(path, "immutable manifest");
            ObjectNode previous = parseObject(Files.readAllBytes(path));
            String actual = ResearchData.DATASET_MANIFEST_SCHEMA.equals(previous.path("schema").asText())
                    ? ResearchDataManifests.manifestOwnHash(previous) : ResearchData.canonicalHash(previous);
            if (!previous.path("content_sha256").asText().equals(actual)) {
                throw failure("immutable artifact retained-hash tampering: " + path);
            }
            if (!previous.path("content_sha256").asText().equals(value.path("content_sha256").asText())) {
                throw failure("immutable manifest collision: " + path);
            }
        } else {
            writeNew(path, prettyBytes(value));
        }
    }

    static void atomicReplace(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + "."
                + JsonHashes.sha256(bytes) + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                && !JsonHashes.sha256(temporary).equals(JsonHashes.sha256(bytes))) {
            Files.delete(temporary);
        }
        if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            writeNew(temporary, bytes);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void moveNoReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static byte[] prettyBytes(JsonNode value) {
        try {
            return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException error) {
            throw failure(error.getOriginalMessage(), error);
        }
    }

    static ObjectNode parseObject(byte[] bytes) {
        JsonNode value = JsonHashes.parse(bytes, "research data JSON");
        if (!value.isObject()) {
            throw failure("research data JSON must be an object");
        }
        return ((ObjectNode) value).deepCopy();
    }

    static String relative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    static String pathSegment(String value) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "s-" + (encoded.isEmpty() ? "empty" : encoded);
    }

    private static Set<String> parquetColumns(Path input) {
        Set<String> columns = new java.util.LinkedHashSet<>();
        String query = "DESCRIBE SELECT * FROM read_parquet('" + sql(input) + "')";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                columns.add(result.getString("column_name"));
            }
            return columns;
        } catch (Exception error) {
            throw failure("authoritative Parquet schema requires pinned DuckDB ("
                    + error.getMessage() + ")", error);
        }
    }

    private static void putJdbc(ObjectNode row, String name, Object value) {
        if (value == null) {
            row.putNull(name);
        } else if (value instanceof Boolean bool) {
            row.put(name, bool);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            row.put(name, ((Number) value).longValue());
        } else if (value instanceof Float || value instanceof Double) {
            row.put(name, ((Number) value).doubleValue());
        } else if (value instanceof BigDecimal decimal) {
            row.put(name, decimal);
        } else if (value instanceof byte[] bytes) {
            row.put(name, Base64.getEncoder().encodeToString(bytes));
        } else {
            row.put(name, String.valueOf(value));
        }
    }

    private static void addArtifact(List<ObjectNode> output, JsonNode node) {
        if (node instanceof ObjectNode object && object.hasNonNull("path")) {
            output.add(object.deepCopy());
        }
    }

    private static void addArtifacts(List<ObjectNode> output, JsonNode nodes) {
        if (nodes != null && nodes.isArray()) {
            nodes.forEach(node -> addArtifact(output, node));
        }
    }

    private static void addAuditArtifact(List<ObjectNode> output, JsonNode store,
                                         String pathField, String hashField) {
        if (store.hasNonNull(pathField)) {
            ObjectNode artifact = JsonHashes.mapper().createObjectNode();
            artifact.put("path", store.path(pathField).asText());
            artifact.put("sha256", store.path(hashField).asText());
            artifact.put("format", "jsonl");
            artifact.put("audit", true);
            output.add(artifact);
        }
    }

    private static String sql(Path path) {
        return path.toString().replace("'", "''");
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
