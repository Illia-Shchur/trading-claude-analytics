package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

/** Content-addressed market snapshot persistence from {@code tools/snapshot.mjs}. */
public final class MarketSnapshotStore {
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final Path repositoryRoot;
    private final Path dataRoot;
    private final ObjectMapper json;

    public MarketSnapshotStore(Path repositoryRoot, ObjectMapper json) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.dataRoot = this.repositoryRoot.resolve("data").normalize();
        this.json = json;
    }

    public ObjectNode create(ObjectNode snapshot, List<String> requestedAssets, boolean macro,
                             Path requestedOutputDirectory, Instant now) throws IOException {
        Path outputDirectory = guardedOutputDirectory(requestedOutputDirectory);
        String digestPayload = ToolchainSupport.snapshotDigestPayload(snapshot);
        String sha256 = Sha256.hex(digestPayload);
        String runId = RUN_STAMP.format(now) + "-" + sha256.substring(0, 8);
        ObjectNode record = json.createObjectNode();
        record.put("run_id", runId);
        record.put("sha256", sha256);
        record.put("fetched_at", ISO_MILLIS.format(now));
        record.set("assets", json.valueToTree(requestedAssets));
        record.put("macro", macro);
        record.set("snapshot", snapshot.deepCopy());
        Path runDirectory = outputDirectory.resolve(runId);
        Files.createDirectories(runDirectory);
        Files.writeString(runDirectory.resolve("snapshot.json"), NodePrettyJson.write(record));
        return record;
    }

    public ObjectNode replay(String runId, Path requestedOutputDirectory, Instant now) throws IOException {
        Path outputDirectory = guardedOutputDirectory(requestedOutputDirectory);
        Path file = outputDirectory.resolve(runId).resolve("snapshot.json").normalize();
        if (!file.startsWith(outputDirectory) || !Files.exists(file)) {
            throw new IllegalArgumentException("no stored snapshot at " + file);
        }
        JsonNode parsed = json.readTree(Files.readString(file));
        if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("stored snapshot is not an object: " + file);
        ObjectNode record = ((ObjectNode) parsed).deepCopy();
        Instant fetchedAt = Instant.parse(record.path("fetched_at").asText());
        long ageMinutes = Math.round((now.toEpochMilli() - fetchedAt.toEpochMilli()) / 60_000.0);
        record.put("replayed_from", runId);
        record.put("age_min", ageMinutes);
        return record;
    }

    public Path guardedOutputDirectory(Path requested) {
        Path output = requested == null ? repositoryRoot.resolve("data/runs")
                : repositoryRoot.resolve(requested);
        output = output.toAbsolutePath().normalize();
        if (!output.equals(dataRoot) && !output.startsWith(dataRoot)) {
            throw new IllegalArgumentException("refusing to write outside data/: " + output);
        }
        return output;
    }
}
