package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Boundary vectors for the frozen qualification receipt and its physical-byte reopen gate. */
class LiquidationInputQualificationBoundaryV1Test {
    @TempDir Path temporary;

    @Test
    void validatesFrozenReceiptInventoryAgainstIndependentlyRehashedMutations() {
        LiquidationInputQualificationV1.validateReceipt(fixtureReceipt());

        reject(row -> row.put("schema", "liquidation-input-qualification/0"));
        reject(row -> row.put("version", 2));
        reject(row -> row.put("strategy_version", "liquidation-daily-stress-v001"));
        reject(row -> row.put("status", "VERIFIED"));
        reject(row -> ((ArrayNode) row.path("asset_scope")).set(0, JsonHashes.mapper().getNodeFactory().textNode("DOGE")));
        reject(row -> row.put("source_window_start", "2022-08-12"));
        reject(row -> row.put("decision_window_end_exclusive", "2026-07-16T00:00:00Z"));
        reject(row -> row.put("historical_availability_proven", true));
        reject(row -> row.put("historical_revision_proven", true));
        reject(row -> row.put("verified_input_count", 1));
        reject(row -> row.put("authoritative_wfo_permitted", true));
        reject(row -> row.put("sealed_evidence_permitted", true));
        reject(row -> row.putArray("inputs"));
        reject(row -> ((ObjectNode) row.path("inputs").get(0)).put("series_id", "unrecognized-series"));
        reject(row -> ((ObjectNode) row.path("inputs").get(1)).put("required", false));
        reject(row -> ((ObjectNode) row.path("inputs").get(1)).put("qualification_status", "VERIFIED"));
        reject(row -> ((ObjectNode) row.path("inputs").get(1)).put("availability_status", "KNOWN"));
        reject(row -> ((ObjectNode) row.path("inputs").get(1)).put("revision_status", "IMMUTABLE"));
        reject(row -> ((ObjectNode) row.path("inputs").get(1)).put("coverage_sufficient_for_intended_use", true));
        reject(row -> ((ObjectNode) row.path("acquisition_manifest_ref")).put("byte_sha256", "not-a-sha"));
    }

    @Test
    void recordsObservedDailyCoverageAndRejectsUnexpectedOrDuplicateBuckets() {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        rows.addObject().put("asset", "BTC").put("day_start_utc", "2022-08-11T00:00:00Z");
        ObjectNode receipt = createReceipt(rows);
        LiquidationInputQualificationV1.validateReceipt(receipt);
        JsonNode btc = receipt.path("inputs").get(0).path("coverage").path("by_asset").get(0);
        assertEquals(1, btc.path("observed_rows").asInt());
        assertEquals(1_501, btc.path("expected_days").asInt());
        assertEquals(1_500, btc.path("missing_days").asInt());
        assertEquals("2022-08-11", btc.path("first_observed").asText());
        assertEquals("2022-08-11", btc.path("last_observed").asText());

        ArrayNode unknownAsset = JsonHashes.mapper().createArrayNode();
        unknownAsset.addObject().put("asset", "DOGE").put("day_start_utc", "2022-08-11T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> createReceipt(unknownAsset));

        ArrayNode duplicateDay = JsonHashes.mapper().createArrayNode();
        duplicateDay.addObject().put("asset", "ETH").put("day_start_utc", "2022-08-11T00:00:00Z");
        duplicateDay.addObject().put("asset", "ETH").put("day_start_utc", "2022-08-11T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> createReceipt(duplicateDay));
        assertThrows(IllegalArgumentException.class,
                () -> createReceipt(JsonHashes.mapper().createObjectNode()));
    }

    @Test
    void auditAndVerifyBoundariesReopenFilesAndFailClosedWithoutPinnedRawAcquisition() throws Exception {
        Path precommit = frozenPrecommitPath();
        Path acquisition = temporary.resolve("acquisition.json");
        ObjectNode acquisitionView = JsonHashes.mapper().createObjectNode()
                .put("schema", "coinalyze-daily-acquisition/1")
                .put("immutable", true).put("diagnostic", true).put("pit_verified", false)
                .put("source_vintage_status", "RETROSPECTIVE_UNKNOWN")
                .put("from_inclusive", "2022-08-11").put("to_exclusive", "2026-09-20")
                .put("as_of", "2026-09-20T00:00:00Z").put("content_sha256", "0".repeat(64));
        Files.write(acquisition, JsonHashes.canonicalBytes(acquisitionView));
        Path output = temporary.resolve("receipt.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("precommit", precommit.toString()).put("acquisition", acquisition.toString())
                .put("out", output.toString());
        IllegalArgumentException pinnedAcquisition = assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.audit(options));
        assertTrue(pinnedAcquisition.getMessage().contains("daily liquidation acquisition must remain"));
        assertFalse(Files.exists(output), "an unpinned acquisition cannot produce a qualification receipt");

        ObjectNode collidingOutput = options.deepCopy().put("out", precommit.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.audit(collidingOutput)).getMessage().contains("must not overwrite"));

        Path malformed = temporary.resolve("malformed.json");
        Files.writeString(malformed, "{not-json");
        ObjectNode malformedOptions = options.deepCopy().put("precommit", malformed.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.audit(malformedOptions)).getMessage().contains("cannot parse frozen precommit"));

        Path receiptPath = temporary.resolve("receipt-view.json");
        Files.write(receiptPath, JsonHashes.canonicalBytes(fixtureReceipt()));
        ObjectNode verifyOptions = JsonHashes.mapper().createObjectNode().put("receipt", receiptPath.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.verify(verifyOptions)).getMessage().contains("frozen precommit must be a bounded regular file"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.assessUse(fixtureReceipt(), "DEVELOPMENT_REPLAY"))
                .getMessage().contains("frozen precommit must be a bounded regular file"));

        ObjectNode unsupported = fixtureReceipt();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.assessUse(unsupported, "SHADOW"))
                .getMessage().contains("frozen precommit must be a bounded regular file"),
                "the evidence-use policy is reached only after its local source references reopen");
    }

    @Test
    void auditChecksForRequiredPathsAndRejectsMissingOrUnboundedEvidenceFiles() throws Exception {
        ObjectNode missingInput = JsonHashes.mapper().createObjectNode().put("acquisition", "unused").put("out", "unused");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.audit(missingInput)).getMessage().contains("precommit is required"));

        ObjectNode missing = JsonHashes.mapper().createObjectNode()
                .put("precommit", temporary.resolve("absent.json").toString())
                .put("acquisition", temporary.resolve("acquisition.json").toString())
                .put("out", temporary.resolve("out.json").toString());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.audit(missing)).getMessage().contains("bounded regular file"));

        Path oversized = temporary.resolve("oversized.json");
        try (var channel = java.nio.channels.FileChannel.open(oversized,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(LiquidationInputQualificationV1.MAX_JSON_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        ObjectNode tooLarge = missing.deepCopy().put("precommit", oversized.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.audit(tooLarge)).getMessage().contains("bounded regular file"));

        Path malformedReceipt = temporary.resolve("bad-receipt.json");
        Files.writeString(malformedReceipt, "[]");
        ObjectNode verify = JsonHashes.mapper().createObjectNode().put("receipt", malformedReceipt.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.verify(verify)).getMessage().contains("must be a JSON object"));
    }

    private void reject(Consumer<ObjectNode> mutation) {
        ObjectNode forged = fixtureReceipt();
        mutation.accept(forged);
        forged.put("content_sha256", JsonHashes.ownHash(forged));
        assertThrows(IllegalArgumentException.class, () -> LiquidationInputQualificationV1.validateReceipt(forged));
    }

    private ObjectNode fixtureReceipt() {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        return createReceipt(rows);
    }

    private ObjectNode createReceipt(JsonNode rows) {
        ObjectNode precommit = JsonHashes.mapper().createObjectNode()
                .put("precommit_id", "liquidation-daily-stress-v002")
                .put("content_sha256", "a".repeat(64));
        ObjectNode acquisition = JsonHashes.mapper().createObjectNode()
                .put("captured_at", "2026-09-20T09:53:53Z").put("content_sha256", "b".repeat(64));
        byte[] precommitBytes = JsonHashes.canonicalBytes(precommit);
        byte[] acquisitionBytes = JsonHashes.canonicalBytes(acquisition);
        return LiquidationInputQualificationV1.createReceipt(precommit, temporary.resolve("frozen-precommit.json"),
                precommitBytes, acquisition, temporary.resolve("acquisition.json"), acquisitionBytes, rows);
    }

    private static Path frozenPrecommitPath() throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json");
            if (Files.isRegularFile(candidate)) return candidate.toRealPath();
            cursor = cursor.getParent();
        }
        throw new AssertionError("tracked v002 frozen precommit is not present above the test working directory");
    }
}
