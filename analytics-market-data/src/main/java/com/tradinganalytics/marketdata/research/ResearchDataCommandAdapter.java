package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Unregistered command adapter mirroring every {@code research-data.mjs} mode. */
public final class ResearchDataCommandAdapter {
    private static final List<String> LAYERS =
            List.of("raw", "normalized", "features", "labels", "quality", "manifests");

    private ResearchDataCommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? null : args[0];
        Map<String, String> options = flags(args, 1);
        try {
            switch (command == null ? "" : command) {
                case "init" -> initialize(options, out);
                case "snapshot", "ingest", "build-features" -> snapshot(command, options, out);
                case "build-labels" -> buildLabels(options, out);
                case "validate" -> validate(options, out);
                case "query" -> query(options, out);
                case "catalog-rebuild" -> print(out,
                        catalog(options.getOrDefault("root", options.getOrDefault("out",
                                "data/research-lake"))));
                case "diff" -> diff(options, out);
                case "pack" -> pack(options, out);
                case "restore" -> restore(options, out);
                default -> out.print("usage: research-data.mjs init|snapshot|ingest|build-features|"
                        + "build-labels|validate|query|diff|pack|restore --input/--manifest ...\n");
            }
            return 0;
        } catch (RuntimeException | IOException error) {
            err.println(rootMessage(error));
            return 1;
        }
    }

    private static void initialize(Map<String, String> options, PrintStream out) throws IOException {
        Path requestedRoot = Path.of(options.getOrDefault("out",
                options.getOrDefault("root", "data/research-lake"))).toAbsolutePath().normalize();
        Files.createDirectories(requestedRoot);
        Path root = PathConfinement.requireRealDirectory(requestedRoot, "research lake root");
        for (String layer : LAYERS) ensureParents(root, root.resolve(layer));
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("root", root.toString());
        value.set("layers", JsonHashes.mapper().valueToTree(LAYERS));
        value.put("authoritative_format", "parquet");
        value.put("duckdb_image", ResearchData.DUCKDB_IMAGE);
        print(out, value);
    }

    private static void snapshot(
            String command, Map<String, String> options, PrintStream out) {
        String input = required(options, "input");
        ObjectNode horizon = null;
        if (options.containsKey("horizon_bars")) {
            horizon = JsonHashes.mapper().createObjectNode();
            horizon.put("bars", Integer.parseInt(options.get("horizon_bars")));
            horizon.put("unit", options.getOrDefault("horizon_unit", "bars"));
        }
        ResearchData.SnapshotResult result = ResearchData.snapshot(new ResearchData.SnapshotOptions(
                Path.of(input), Path.of(options.getOrDefault("out",
                        options.getOrDefault("output", "data/research-lake"))),
                options.getOrDefault("dataset", options.get("dataset_id")),
                options.get("asset"), options.get("venue"), options.get("instrument"),
                options.getOrDefault("pit_tier", options.getOrDefault("pit", "UNVERIFIED")),
                "build-features".equals(command) ? "FEATURE" : options.getOrDefault("role", "FEATURE"),
                options.getOrDefault("format", "parquet"), options.getOrDefault("source", "public"),
                !"false".equals(options.get("public_source")), null, null, null, null,
                horizon, options.get("label_code_sha256")));
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("snapshot_id", result.snapshotId());
        value.put("root", result.root().toString());
        value.put("manifest", result.manifest().toString());
        if (result.featureSet() == null) value.putNull("feature_set");
        else value.put("feature_set", result.featureSet().toString());
        if (result.labelSet() == null) value.putNull("label_set");
        else value.put("label_set", result.labelSet().toString());
        value.set("feature", result.feature() == null
                ? JsonHashes.mapper().nullNode() : result.feature());
        value.set("labels", result.labels() == null
                ? JsonHashes.mapper().nullNode() : result.labels());
        print(out, value);
    }

    private static void buildLabels(Map<String, String> options, PrintStream out) throws IOException {
        Path manifestPath = Path.of(required(options, "manifest")).toAbsolutePath().normalize();
        PathConfinement.validateSinglyLinkedFile(manifestPath, "research lake manifest");
        ObjectNode manifest = object(Files.readAllBytes(manifestPath));
        String codeHash = options.getOrDefault("label_code_sha256",
                JsonHashes.sha256(options.getOrDefault("code", "labels-v1")));
        ObjectNode horizon = JsonHashes.mapper().createObjectNode();
        horizon.put("bars", Integer.parseInt(options.getOrDefault("horizon_bars", "1")));
        horizon.put("unit", "bars");
        ArrayNode partitions = JsonHashes.mapper().createArrayNode();
        if (manifest.path("feature_store").path("labels").isObject()) {
            partitions.add(manifest.path("feature_store").path("labels"));
        }
        ObjectNode labelSet = ResearchData.buildLabelSet(new ResearchData.LabelSetOptions(
                options.get("id"), manifest.path("content_sha256").asText(), codeHash,
                horizon, partitions));
        Path destination = Path.of(options.getOrDefault("out", ".research-run/"
                + labelSet.path("label_set_id").asText() + ".json")).toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Files.write(destination, pretty(labelSet), java.nio.file.StandardOpenOption.CREATE_NEW);
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("path", destination.toString());
        result.set("label_set", labelSet);
        print(out, result);
    }

    private static void validate(Map<String, String> options, PrintStream out) throws IOException {
        Path path = Path.of(required(options, "manifest")).toAbsolutePath().normalize();
        ObjectNode manifest = object(Files.readAllBytes(path));
        List<String> assets = options.containsKey("assets")
                ? List.of(options.get("assets").split(",")) : List.of();
        boolean valid = ResearchData.validateManifest(manifest, new ResearchData.ValidationOptions(
                options.getOrDefault("phase", "DEVELOPMENT"), assets,
                path.getParent().getParent()));
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("valid", valid);
        result.put("schema", manifest.path("schema").asText());
        print(out, result);
    }

    private static void query(Map<String, String> options, PrintStream out) {
        Path input = Path.of(required(options, "input"));
        if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".parquet")) {
            throw new IllegalArgumentException(
                    "authoritative query requires Parquet; JSONL/CSV are staging/debug only");
        }
        Long from = options.containsKey("from") ? timestamp(options.get("from")) : null;
        Long to = options.containsKey("to") ? timestamp(options.get("to")) : null;
        List<String> assets = options.containsKey("asset")
                ? List.of(options.get("asset").toLowerCase(Locale.ROOT).split(",")) : null;
        print(out, JsonHashes.mapper().valueToTree(ResearchData.queryParquet(
                input, new ResearchData.QueryOptions(from, to, assets))));
    }

    private static ObjectNode catalog(String root) {
        ResearchData.CatalogResult result = ResearchData.rebuildCatalog(Path.of(root));
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("path", result.path().toString());
        value.set("catalog", result.catalog());
        return value;
    }

    private static void diff(Map<String, String> options, PrintStream out) throws IOException {
        ObjectNode left = object(Files.readAllBytes(Path.of(required(options, "left"))));
        ObjectNode right = object(Files.readAllBytes(Path.of(required(options, "right"))));
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("left", left.path("content_sha256").asText());
        result.put("right", right.path("content_sha256").asText());
        result.put("same", left.path("content_sha256").asText()
                .equals(right.path("content_sha256").asText()));
        ArrayNode changes = result.putArray("dataset_changes");
        Map<String, JsonNode> old = new LinkedHashMap<>();
        left.path("datasets").forEach(row -> old.put(row.path("dataset_id").asText(), row));
        right.path("datasets").forEach(row -> {
            if (!row.equals(old.get(row.path("dataset_id").asText()))) {
                changes.add(row.path("dataset_id").asText());
            }
        });
        print(out, result);
    }

    private static void pack(Map<String, String> options, PrintStream out) throws IOException {
        Path manifestPath = Path.of(required(options, "manifest")).toAbsolutePath().normalize();
        PathConfinement.validateSinglyLinkedFile(manifestPath, "research lake manifest");
        ObjectNode manifest = object(Files.readAllBytes(manifestPath));
        ResearchData.validateManifest(manifest);
        Path lakeRoot = manifestPath.getParent().getParent();
        List<Path> files = files(lakeRoot);
        ObjectNode pack = JsonHashes.mapper().createObjectNode();
        pack.put("schema", "research-lake-pack/1");
        pack.put("pack_version", 1);
        pack.set("manifest", manifest);
        pack.put("root_name", lakeRoot.getFileName().toString());
        ArrayNode embedded = pack.putArray("files");
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            ObjectNode row = embedded.addObject();
            row.put("path", portable(lakeRoot.relativize(file)));
            row.put("sha256", JsonHashes.sha256(bytes));
            row.put("bytes_base64", Base64.getEncoder().encodeToString(bytes));
        }
        Path destination = Path.of(options.getOrDefault("out",
                manifest.path("manifest_id").asText() + ".pack.json")).toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Files.write(destination, pretty(pack), java.nio.file.StandardOpenOption.CREATE_NEW);
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("path", destination.toString());
        result.put("files", files.size());
        result.put("manifest_sha256", manifest.path("content_sha256").asText());
        result.put("embedded", true);
        print(out, result);
    }

    private static void restore(Map<String, String> options, PrintStream out) throws IOException {
        ObjectNode pack = object(Files.readAllBytes(Path.of(required(options, "pack"))));
        if (!"research-lake-pack/1".equals(pack.path("schema").asText())
                || pack.path("pack_version").asInt() != 1) {
            throw new IllegalArgumentException("unsupported lake pack");
        }
        Path requestedRoot = Path.of(options.getOrDefault("out", ".")).toAbsolutePath().normalize();
        Files.createDirectories(requestedRoot);
        Path root = PathConfinement.requireRealDirectory(requestedRoot, "restore root");
        int restored = 0;
        for (JsonNode file : pack.path("files")) {
            String name = file.path("path").asText();
            Path relative = safeRelative(name);
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(file.path("bytes_base64").asText()); }
            catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("embedded pack content hash mismatch: " + name);
            }
            if (!JsonHashes.sha256(bytes).equals(file.path("sha256").asText())) {
                throw new IllegalArgumentException("embedded pack content hash mismatch: " + name);
            }
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("unsafe pack path: " + name);
            ensureParents(root, target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(target, "restored lake file");
                if (!JsonHashes.sha256(target).equals(file.path("sha256").asText())) {
                    throw new IllegalArgumentException(
                            "restore collision with mismatched file: " + name);
                }
            } else Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            PathConfinement.validateSinglyLinkedFile(target, "restored lake file");
            restored++;
        }
        Path restoredManifest = root.resolve("manifests/dataset-manifest.json");
        if (!Files.exists(restoredManifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("pack omitted dataset manifest");
        }
        ObjectNode manifest = object(Files.readAllBytes(restoredManifest));
        if (!manifest.path("content_sha256").asText()
                .equals(pack.path("manifest").path("content_sha256").asText())) {
            throw new IllegalArgumentException("restored manifest hash mismatch");
        }
        ResearchData.validateManifest(manifest,
                new ResearchData.ValidationOptions("DEVELOPMENT", List.of(), root));
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("restored", restored);
        result.put("manifest_sha256", manifest.path("content_sha256").asText());
        result.put("complete", true);
        print(out, result);
    }

    private static List<Path> files(Path root) throws IOException {
        List<Path> output = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink()) {
                    throw new IllegalArgumentException("research lake contains a symlink: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IllegalArgumentException("research lake contains a non-regular file: " + file);
                }
                PathConfinement.validateSinglyLinkedFile(file, "research lake file");
                output.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        output.sort(Comparator.naturalOrder());
        return output;
    }

    private static void ensureParents(Path root, Path directory) throws IOException {
        Path cursor = root;
        for (Path component : root.relativize(directory)) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)
                        || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                            "unsafe pack path parent: " + portable(root.relativize(cursor)));
                }
            } else Files.createDirectory(cursor);
        }
    }

    private static Path safeRelative(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")) {
            throw new IllegalArgumentException("unsafe pack path: " + name);
        }
        Path path = Path.of(name);
        if (path.isAbsolute() || path.normalize().startsWith("..") || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("unsafe pack path: " + name);
        }
        return path;
    }

    private static Map<String, String> flags(String[] args, int start) {
        Map<String, String> output = new LinkedHashMap<>();
        for (int index = start; index < args.length; index++) {
            if (!args[index].startsWith("--")) continue;
            String key = args[index].substring(2).replace('-', '_');
            String value = index + 1 >= args.length || args[index + 1].startsWith("--")
                    ? "true" : args[++index];
            output.put(key, value);
        }
        return output;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static long timestamp(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) {
            try { return Instant.parse(value).toEpochMilli(); }
            catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("invalid timestamp " + value);
            }
        }
    }

    private static ObjectNode object(byte[] bytes) {
        JsonNode value = JsonHashes.parse(bytes, "research data CLI JSON");
        if (!value.isObject()) throw new IllegalArgumentException("research data CLI JSON must be an object");
        return ((ObjectNode) value).deepCopy();
    }

    private static void print(PrintStream output, JsonNode value) {
        try { output.print(JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"); }
        catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }

    private static byte[] pretty(JsonNode value) throws IOException {
        return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getMessage() == null ? error.getMessage() : cursor.getMessage();
    }
}
