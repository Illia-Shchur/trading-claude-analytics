package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketSnapshotStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void createsContentAddressedRecordAndReplaysItWithAge() throws Exception {
        MarketSnapshotStore store = new MarketSnapshotStore(temporary, JSON);
        ObjectNode snapshot = (ObjectNode) JSON.readTree("""
                {"btc":{"asset":"BTC","fetched_at":"volatile","errors":["timeout"],"spot":{"canonical":100}}}
                """);
        Instant createdAt = Instant.parse("2026-08-28T12:34:00Z");

        ObjectNode created = store.create(snapshot, List.of("btc"), true, null, createdAt);

        assertThat(created.path("run_id").asText()).startsWith("20260828-1234-");
        assertThat(created.path("sha256").asText()).hasSize(64);
        assertThat(created.path("fetched_at").asText()).isEqualTo("2026-08-28T12:34:00.000Z");
        Path file = temporary.resolve("data/runs").resolve(created.path("run_id").asText()).resolve("snapshot.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).endsWith("\n");

        ObjectNode replayed = store.replay(created.path("run_id").asText(), null,
                Instant.parse("2026-08-28T13:04:29Z"));
        assertThat(replayed.path("replayed_from").asText()).isEqualTo(created.path("run_id").asText());
        assertThat(replayed.path("age_min").asLong()).isEqualTo(30);
    }

    @Test
    void volatileFetchMetadataDoesNotChangeDigestOrRunId() throws Exception {
        MarketSnapshotStore store = new MarketSnapshotStore(temporary, JSON);
        ObjectNode first = (ObjectNode) JSON.readTree("{\"btc\":{\"fetched_at\":\"one\",\"errors\":[\"x\"],\"value\":1}}");
        ObjectNode second = (ObjectNode) JSON.readTree("{\"btc\":{\"fetched_at\":\"two\",\"errors\":[],\"value\":1}}");
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        ObjectNode firstRecord = store.create(first, List.of("btc"), false, null, now);
        ObjectNode secondRecord = store.create(second, List.of("btc"), false, null, now);
        assertThat(secondRecord.path("sha256").asText()).isEqualTo(firstRecord.path("sha256").asText());
        assertThat(secondRecord.path("run_id").asText()).isEqualTo(firstRecord.path("run_id").asText());
    }

    @Test
    void refusesWritesOutsideDataAndMissingReplay() {
        MarketSnapshotStore store = new MarketSnapshotStore(temporary, JSON);
        assertThatThrownBy(() -> store.guardedOutputDirectory(temporary.resolve("elsewhere")))
                .hasMessageContaining("refusing to write outside data/");
        assertThatThrownBy(() -> store.replay("missing", null, Instant.EPOCH))
                .hasMessageContaining("no stored snapshot");
    }
}
