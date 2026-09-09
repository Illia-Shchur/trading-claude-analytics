package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class VerifiedLoaderLifecycleTrustTest {
    @Test
    void loaderTokenHashesAndIdentityMatchNodeAndEveryReopenIsReverified() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<LifecycleTrustService.LoaderReopen> current = new AtomicReference<>(
                new LifecycleTrustService.LoaderReopen(fixture.receipts(), fixture.values()));
        LifecycleTrustService service = new LifecycleTrustService();
        LifecycleTrustService.Token token = service.createVerifiedLoaderLifecycleTrustV5(
                "authoritative-parquet:" + "9".repeat(64), fixture.receipts(), fixture.values(),
                fixture.lineage(), current::get);
        JsonNode oracle = frozenToken();
        assertThat(token.bundleSha256()).isEqualTo(oracle.path("bundle_sha256").asText());
        assertThat(token.contentSha256()).isEqualTo(oracle.path("content_sha256").asText());
        assertThat(service.isTrusted(token)).isTrue();

        var reopened = service.reopen(token, Map.of("bars", fixture.values().get("bars")));
        assertThat(reopened.values()).containsOnlyKeys(
                "contract_spec", "execution_model", "capacity", "bars", "funding");
        assertThat(reopened.bundleSha256()).isEqualTo(token.bundleSha256());

        Map<String, LifecycleTrustService.ReceiptReference> changedReceipt =
                new LinkedHashMap<>(fixture.receipts());
        var original = changedReceipt.get("bars");
        changedReceipt.put("bars", new LifecycleTrustService.ReceiptReference(
                original.path(), original.contentSha256(), JsonHashes.sha256("different bytes"),
                original.bytes(), original.rowsSha256(), original.schema()));
        current.set(new LifecycleTrustService.LoaderReopen(changedReceipt, fixture.values()));
        assertThatThrownBy(() -> service.reopen(token))
                .hasMessage("lifecycle trust: bars verified loader receipt identity changed");

        Map<String, JsonNode> changedValues = copies(fixture.values());
        ((ObjectNode) changedValues.get("contract_spec")).put("contract", "mutated");
        current.set(new LifecycleTrustService.LoaderReopen(fixture.receipts(), changedValues));
        assertThatThrownBy(() -> service.reopen(token))
                .hasMessage("lifecycle trust: contract_spec verified loader content changed");

        current.set(new LifecycleTrustService.LoaderReopen(fixture.receipts(), fixture.values()));
        ObjectNode wrongBars = JsonHashes.mapper().createObjectNode();
        wrongBars.putArray("rows").addObject().put("bar", 999);
        assertThatThrownBy(() -> service.reopen(token, Map.of("bars", wrongBars)))
                .hasMessage("lifecycle trust: bars input does not match its physical receipt");
        assertThatThrownBy(() -> service.reopen(token, Map.of("marks", wrongBars)))
                .hasMessage("lifecycle trust: marks input is present but has no physical receipt");
    }

    @Test
    void loaderFactoryRejectsMissingRolesValuesLineageAndReopenFailures() {
        Fixture fixture = fixture();
        LifecycleTrustService service = new LifecycleTrustService();
        assertThatThrownBy(() -> service.createVerifiedLoaderLifecycleTrustV5(
                null, fixture.receipts(), fixture.values(), fixture.lineage(), null))
                .hasMessage("lifecycle trust: verified loader lifecycle capability requires a reopen verifier");

        Map<String, LifecycleTrustService.ReceiptReference> missing =
                new LinkedHashMap<>(fixture.receipts());
        missing.remove("bars");
        assertThatThrownBy(() -> service.createVerifiedLoaderLifecycleTrustV5(
                null, missing, fixture.values(), fixture.lineage(),
                () -> new LifecycleTrustService.LoaderReopen(missing, fixture.values())))
                .hasMessage("lifecycle trust: verified loader bars receipt is required");

        Map<String, JsonNode> missingValue = copies(fixture.values());
        missingValue.remove("capacity");
        assertThatThrownBy(() -> service.createVerifiedLoaderLifecycleTrustV5(
                null, fixture.receipts(), missingValue, fixture.lineage(),
                () -> new LifecycleTrustService.LoaderReopen(fixture.receipts(), missingValue)))
                .hasMessage("lifecycle trust: verified loader capacity value is missing");

        assertThatThrownBy(() -> service.createVerifiedLoaderLifecycleTrustV5(
                " ", fixture.receipts(), fixture.values(), fixture.lineage(),
                () -> new LifecycleTrustService.LoaderReopen(fixture.receipts(), fixture.values())))
                .hasMessage("lifecycle trust: verified loader rootReference must be a portable label or null");

        ObjectNode invalidLineage = fixture.lineage().deepCopy();
        invalidLineage.put("manifest_sha256", "invalid");
        assertThatThrownBy(() -> service.createVerifiedLoaderLifecycleTrustV5(
                null, fixture.receipts(), fixture.values(), invalidLineage,
                () -> new LifecycleTrustService.LoaderReopen(fixture.receipts(), fixture.values())))
                .hasMessage("lifecycle trust: lineage manifest_sha256 is not a valid hash");

        LifecycleTrustService.Token token = service.createVerifiedLoaderLifecycleTrustV5(
                null, fixture.receipts(), fixture.values(), fixture.lineage(),
                () -> { throw new IllegalStateException("physical loader offline"); });
        assertThatThrownBy(() -> service.reopen(token))
                .hasMessage("lifecycle trust: verified loader physical receipt reopen failed: physical loader offline");
    }

    private static Fixture fixture() {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        Map<String, LifecycleTrustService.ReceiptReference> receipts = new LinkedHashMap<>();
        for (String role : new String[] {
                "contract_spec", "execution_model", "capacity", "funding"}) {
            ObjectNode value = JsonHashes.mapper().createObjectNode();
            value.put("role", role);
            value.put("content_sha256", JsonHashes.ownHash(value));
            values.put(role, value);
            receipts.put(role, new LifecycleTrustService.ReceiptReference(
                    role + ".json", value.path("content_sha256").asText(),
                    JsonHashes.sha256(role + " physical bytes"), 100L + role.length(), null, null));
        }
        ObjectNode bars = JsonHashes.mapper().createObjectNode();
        ArrayNode rows = bars.putArray("rows");
        rows.addObject().put("bar", 1);
        rows.addObject().put("bar", 2);
        bars.put("content_sha256", JsonHashes.ownHash(bars));
        values.put("bars", bars);
        receipts.put("bars", new LifecycleTrustService.ReceiptReference(
                "bars.json", bars.path("content_sha256").asText(),
                JsonHashes.sha256("bars physical bytes"), 222L,
                JsonHashes.canonicalSha256(rows), null));
        // Preserve the JavaScript insertion order only for human-readable oracle payloads;
        // RFC 8785 hashing remains key-order independent.
        Map<String, LifecycleTrustService.ReceiptReference> ordered = new LinkedHashMap<>();
        for (String role : new String[] {
                "contract_spec", "execution_model", "capacity", "bars", "funding"}) {
            ordered.put(role, receipts.get(role));
        }
        Map<String, JsonNode> orderedValues = new LinkedHashMap<>();
        ordered.keySet().forEach(role -> orderedValues.put(role, values.get(role)));
        ObjectNode lineage = JsonHashes.mapper().createObjectNode();
        lineage.put("manifest_sha256", "8".repeat(64));
        lineage.put("loader", "PARQUET");
        return new Fixture(ordered, orderedValues, lineage);
    }

    private static JsonNode frozenToken() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                VerifiedLoaderLifecycleTrustTest.class.getResourceAsStream(
                        "/oracles/strategy-v5-lifecycle-trust-v1.json"),
                "frozen lifecycle-trust oracle is missing")) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private static Map<String, JsonNode> copies(Map<String, JsonNode> values) {
        Map<String, JsonNode> copies = new LinkedHashMap<>();
        values.forEach((key, value) -> copies.put(key, value.deepCopy()));
        return copies;
    }

    private record Fixture(
            Map<String, LifecycleTrustService.ReceiptReference> receipts,
            Map<String, JsonNode> values,
            ObjectNode lineage) {}
}
