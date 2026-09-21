package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Accepted synthetic receipt-shape baseline plus typed, independently resealed frozen-contract failures. */
class LiquidationQualificationReceiptTypeMatrixV1Test {
    private static final String FROZEN_FAILURE = "unsupported liquidation input qualification receipt";

    @Test
    void validTypedReceiptIsAcceptedBeforeResealedShapeAndDomainMutations() {
        assertDoesNotThrow(() -> LiquidationInputQualificationV1.validateReceipt(receipt()));

        reject(FROZEN_FAILURE, row -> row.put("schema", "liquidation-input-qualification/0"));
        reject(FROZEN_FAILURE, row -> row.put("version", 2));
        reject(FROZEN_FAILURE, row -> row.put("strategy_version", "liquidation-daily-stress-v001"));
        reject(FROZEN_FAILURE, row -> row.put("status", "READY"));
        reject(FROZEN_FAILURE, row -> row.set("asset_scope", JsonHashes.mapper().createObjectNode()));
        reject(FROZEN_FAILURE, row -> row.putArray("asset_scope"));
        reject(FROZEN_FAILURE, row -> ((ArrayNode) row.path("asset_scope")).remove(3));
        reject(FROZEN_FAILURE, row -> ((ArrayNode) row.path("asset_scope")).set(2,
                JsonHashes.mapper().getNodeFactory().textNode("DOGE")));
        reject(FROZEN_FAILURE, row -> row.put("source_window_start", "2022-08-12"));
        reject(FROZEN_FAILURE, row -> row.put("source_window_end_exclusive", "2026-09-19"));
        reject(FROZEN_FAILURE, row -> row.put("decision_window_start", "2022-11-12T00:00:00Z"));
        reject(FROZEN_FAILURE, row -> row.put("decision_window_end_exclusive", "2026-07-16T00:00:00Z"));
        reject(FROZEN_FAILURE, row -> row.put("execution_window_end_exclusive", "2026-09-19T00:00:00Z"));
    }

    @Test
    void receiptHashAndNonAuthoritativeEvidenceClaimsAreIndependentGates() {
        ObjectNode stale = receipt();
        stale.put("content_sha256", sha("stale-receipt-hash"));
        expect("qualification receipt content hash mismatch", stale);

        reject("v002 qualification cannot claim verified vintages or authoritative evidence",
                row -> row.put("historical_availability_proven", true));
        reject("v002 qualification cannot claim verified vintages or authoritative evidence",
                row -> row.put("historical_revision_proven", true));
        reject("v002 qualification cannot claim verified vintages or authoritative evidence",
                row -> row.put("verified_input_count", 1));
        reject("v002 qualification cannot claim verified vintages or authoritative evidence",
                row -> row.put("authoritative_wfo_permitted", true));
        reject("v002 qualification cannot claim verified vintages or authoritative evidence",
                row -> row.put("sealed_evidence_permitted", true));
    }

    @Test
    void requiredSeriesAndOptionalDiagnosticsMustRetainTheirExactTypedInventory() {
        reject("qualification receipt inputs must match the frozen series inventory", row ->
                row.set("inputs", JsonHashes.mapper().createObjectNode()));
        reject("qualification receipt inputs must match the frozen series inventory", row -> row.putArray("inputs"));

        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 0).put("series_id", "daily_liquidations_other"));
        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 0).put("required", false));
        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 0).put("qualification_status", "VERIFIED"));
        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 0).put("availability_status", "KNOWN"));
        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 0).put("revision_status", "IMMUTABLE"));
        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 1).put("required", false));
        reject("qualification receipt has a changed frozen qualification", row ->
                input(row, 7).put("required", true));
        reject("no v002 source coverage is yet qualified for its full intended use", row ->
                input(row, 0).put("coverage_sufficient_for_intended_use", true));
        reject("no v002 source coverage is yet qualified for its full intended use", row ->
                input(row, 8).put("coverage_sufficient_for_intended_use", true));
    }

    @Test
    void acquisitionReferenceRequiresObjectAndTwoLowercaseSha256Bindings() {
        reject("acquisition_manifest_ref must be an object", row ->
                row.set("acquisition_manifest_ref", JsonHashes.mapper().getNodeFactory().textNode("unbound")));
        reject("byte_sha256 is required", row -> ref(row).remove("byte_sha256"));
        reject("content_sha256 is required", row -> ref(row).remove("content_sha256"));
        reject("acquisition reference is not hash-bound", row -> ref(row).put("byte_sha256", "A".repeat(64)));
        reject("acquisition reference is not hash-bound", row -> ref(row).put("content_sha256", "z".repeat(64)));
    }

    private void reject(String message, Consumer<ObjectNode> mutation) {
        ObjectNode changed = receipt();
        mutation.accept(changed);
        if (!"qualification receipt content hash mismatch".equals(message)) {
            changed.put("content_sha256", JsonHashes.ownHash(changed));
        }
        expect(message, changed);
    }

    private void expect(String message, ObjectNode changed) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.validateReceipt(changed));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
    }

    private static ObjectNode receipt() {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        ObjectNode precommit = JsonHashes.mapper().createObjectNode().put("precommit_id", "liquidation-daily-stress-v002")
                .put("content_sha256", "a".repeat(64));
        ObjectNode acquisition = JsonHashes.mapper().createObjectNode().put("captured_at", "2026-09-20T09:00:00Z")
                .put("content_sha256", "b".repeat(64));
        byte[] precommitBytes = JsonHashes.canonicalBytes(precommit);
        byte[] acquisitionBytes = JsonHashes.canonicalBytes(acquisition);
        return LiquidationInputQualificationV1.createReceipt(precommit, Path.of("precommit.json"), precommitBytes,
                acquisition, Path.of("acquisition.json"), acquisitionBytes, rows);
    }

    private static ObjectNode input(ObjectNode receipt, int index) {
        return (ObjectNode) receipt.path("inputs").get(index);
    }

    private static ObjectNode ref(ObjectNode receipt) {
        return (ObjectNode) receipt.path("acquisition_manifest_ref");
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
}
