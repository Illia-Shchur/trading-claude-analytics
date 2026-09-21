package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the immutable genesis bootstrap receipt and its exact event-chain binding. */
class LiquidationCheckpointGenesisReceiptBoundaryV1Test {
    @TempDir Path temporary;

    @Test
    void validGenesisReopensAndMalformedScopeHashOrEventProjectionCannotReplaceIt() throws Exception {
        Fixture valid = initialize("valid-genesis");
        ObjectNode reopened = JsonHashes.mapper().readTree(Files.readAllBytes(valid.receiptPath())) instanceof ObjectNode node
                ? node : throwAssertion("genesis receipt must be an object");
        assertEquals(LiquidationV2ReplayCheckpointV1.EVENT_SCHEMA,
                reopened.path("genesis_event").path("schema").asText());
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                valid.family(), binding("valid-genesis", valid.root()), () -> 2_000_000_000L);
        assertEquals(0L, session.consumedComputeMillis());
        session.close();

        mutateAndReject("bad-schema", value -> value.put("schema", "unknown-genesis-receipt/0"),
                "immutable budget genesis initialization receipt is invalid");
        mutateAndReject("bad-scope", value -> value.put("scope_sha256", sha("different-scope")),
                "immutable budget genesis initialization receipt is invalid");
        mutateAndReject("bad-event-hash-link", value -> value.put("genesis_event_sha256", sha("different-event")),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        mutateAndReject("wrong-genesis-projection", value -> {
            ObjectNode genesis = (ObjectNode) value.path("genesis_event");
            genesis.put("event_type", "RUN_START");
            genesis.put("event_sha256", JsonHashes.ownHash(genesis, "event_sha256"));
            value.put("genesis_event_sha256", genesis.path("event_sha256").asText());
        }, "immutable budget genesis receipt does not bind the exact chain genesis");

        Fixture malformed = initialize("malformed-genesis-receipt");
        Files.writeString(malformed.receiptPath(), "[]");
        assertFailure(malformed, "immutable budget genesis initialization receipt is invalid");
    }

    @Test
    void checkpointJsonRequiresFrozenSchemaTimestampAndThreeDigests() {
        var expected = new LiquidationV2ReplayCheckpointV1.Checkpoint(
                java.time.Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
        assertEquals(expected, LiquidationV2ReplayCheckpointV1.checkpointFromJson(expected.toJson()));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.checkpointFromJson(JsonHashes.mapper().createArrayNode()));

        ObjectNode wrongSchema = expected.toJson().put("schema", "checkpoint/0");
        assertFailure(wrongSchema, "schema is missing or unsupported");
        ObjectNode badTime = expected.toJson().put("boundary_exclusive", "not-a-time");
        assertFailure(badTime, "fields are invalid");
        ObjectNode badDigest = expected.toJson().put("ledger_sha256", "not-a-sha");
        assertFailure(badDigest, "fields are invalid");
    }

    private void mutateAndReject(String name, Consumer<ObjectNode> mutation, String expectedMessage) throws Exception {
        Fixture fixture = initialize(name);
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(fixture.receiptPath()));
        mutation.accept(receipt);
        receipt.put("init_receipt_sha256", JsonHashes.ownHash(receipt, "init_receipt_sha256"));
        Files.write(fixture.receiptPath(), JsonHashes.canonicalBytes(receipt));
        assertFailure(fixture, expectedMessage);
    }

    private void assertFailure(Fixture fixture, String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.openNewRun(fixture.family(), binding("next-" + fixture.name(), fixture.root()),
                        () -> 2_000_000_000L));
        assertTrue(failure.getMessage().contains(expectedMessage),
                "expected diagnostic to contain [" + expectedMessage + "], got [" + failure.getMessage() + "]");
    }

    private static void assertFailure(JsonNode value, String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.checkpointFromJson(value));
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
    }

    private Fixture initialize(String name) throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve(name));
        var family = LiquidationV2ReplayCheckpointV1.FamilyScope.of("checkpoint-genesis-" + name,
                sha("precommit"), sha("profile"), sha("dataset"), root);
        try (var session = LiquidationV2ReplayCheckpointV1.openNewRun(family, binding(name, root), () -> 2_000_000_000L)) {
            assertEquals(0L, session.consumedComputeMillis());
        }
        return new Fixture(name, root, family, family.ledgerDirectory().resolve("chain-genesis-init.json"));
    }

    private static LiquidationV2ReplayCheckpointV1.RunBinding binding(String name, Path root) throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze-" + name), sha("request-" + name),
                project, physical, sha("manifest-" + name), sha("executor-" + name),
                physical.resolve("replay.json"), physical.resolve("checkpoint.json"));
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
    private static ObjectNode throwAssertion(String message) { throw new AssertionError(message); }

    private record Fixture(String name, Path root, LiquidationV2ReplayCheckpointV1.FamilyScope family,
            Path receiptPath) {}
}
