package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LifecycleTrustServiceTest {
    @TempDir Path temporary;

    @Test
    void portableTwinsMintTheSameDigestAndEveryUseReopensExactBytes() throws IOException {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path twin = Files.createDirectory(temporary.resolve("twin"));
        Fixture firstFixture = fixture(first);
        Fixture twinFixture = fixture(twin);
        ObjectNode lineage = JsonHashes.mapper().createObjectNode()
                .put("precommit_sha256", JsonHashes.sha256("precommit"))
                .put("evaluator_spec_sha256", JsonHashes.sha256("spec"));
        LifecycleTrustService service = new LifecycleTrustService();

        LifecycleTrustService.Token token = service.open(
                first, "same-dataset", firstFixture.receipts(), lineage);
        LifecycleTrustService.Token twinToken = service.open(
                twin, "same-dataset", twinFixture.receipts(), lineage);

        assertThat(token.bundleSha256()).isEqualTo(twinToken.bundleSha256());
        assertThat(token.contentSha256()).isEqualTo(twinToken.contentSha256());
        assertThat(token.rootReference()).isEqualTo("same-dataset");
        assertThat(token.provenance()).isEqualTo("AUTHORITATIVE");
        assertThat(service.isTrusted(token)).isTrue();
        assertThat(service.receiptHash(token, "execution_model"))
                .isEqualTo(firstFixture.receipts().get("execution_model").contentSha256());

        LifecycleTrustService.ReopenedTrust reopened = service.reopen(
                token, Map.of("bars", firstFixture.bars()));
        assertThat(reopened.provenance()).isEqualTo("AUTHORITATIVE");
        assertThat(reopened.bundleSha256()).isEqualTo(token.bundleSha256());
        assertThat(reopened.receipts().get("bars").byteSha256())
                .isEqualTo(firstFixture.receipts().get("bars").byteSha256());

        Files.writeString(first.resolve("model.json"), "{\"fee_rate\":0.002}\n");
        assertThatThrownBy(() -> service.reopen(token))
                .isInstanceOf(CustodyException.class)
                .hasMessageMatching("(?i).*(tampered|changed).*" );
    }

    @Test
    void tokenAuthorityIsServiceIdentityNotCallerControlledFields() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("identity"));
        Fixture fixture = fixture(root);
        LifecycleTrustService issuer = new LifecycleTrustService();
        LifecycleTrustService verifier = new LifecycleTrustService();
        LifecycleTrustService.Token token = issuer.open(root, null, fixture.receipts(), Map.of());

        assertThat(verifier.isTrusted(token)).isFalse();
        assertThatThrownBy(() -> verifier.reopen(token))
                .isInstanceOf(CustodyException.class).hasMessageContaining("token");
        assertThatThrownBy(() -> issuer.reopen(null))
                .isInstanceOf(CustodyException.class).hasMessageContaining("token");
        assertThatThrownBy(() -> issuer.receiptHash(token, "unknown"))
                .isInstanceOf(CustodyException.class).hasMessageContaining("unknown");
    }

    @Test
    void receiptOpeningRejectsTraversalSymlinkHardlinkAndSymlinkRoot() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("physical"));
        Path outsideRoot = Files.createDirectory(temporary.resolve("outside"));
        Fixture fixture = fixture(root);
        Fixture outside = fixture(outsideRoot);
        LifecycleTrustService service = new LifecycleTrustService();

        Map<String, LifecycleTrustService.ReceiptReference> traversal = new LinkedHashMap<>(fixture.receipts());
        traversal.put("bars", withPath(outside.receipts().get("bars"), "../outside/bars.json"));
        assertThatThrownBy(() -> service.open(root, null, traversal, Map.of()))
                .isInstanceOf(CustodyException.class)
                .hasMessageMatching("(?i).*(relative|traversal).*" );

        Files.createSymbolicLink(root.resolve("symlink-bars.json"), outsideRoot.resolve("bars.json"));
        Map<String, LifecycleTrustService.ReceiptReference> symlink = new LinkedHashMap<>(fixture.receipts());
        symlink.put("bars", withPath(outside.receipts().get("bars"), "symlink-bars.json"));
        assertThatThrownBy(() -> service.open(root, null, symlink, Map.of()))
                .isInstanceOf(CustodyException.class).hasMessageContaining("symlink");

        Files.createLink(root.resolve("hardlink-contract.json"), root.resolve("contract.json"));
        Map<String, LifecycleTrustService.ReceiptReference> hardlink = new LinkedHashMap<>(fixture.receipts());
        hardlink.put("contract_spec", withPath(fixture.receipts().get("contract_spec"), "hardlink-contract.json"));
        assertThatThrownBy(() -> service.open(root, null, hardlink, Map.of()))
                .isInstanceOf(CustodyException.class).hasMessageContaining("singly-linked");

        Path rootLink = temporary.resolve("physical-link");
        Files.createSymbolicLink(rootLink, root);
        assertThatThrownBy(() -> service.open(rootLink, null, fixture.receipts(), Map.of()))
                .isInstanceOf(CustodyException.class).hasMessageContaining("real directory");
    }

    @Test
    void receiptMetadataAndSuppliedValuesAreBoundIndependently() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("metadata"));
        Fixture fixture = fixture(root);
        LifecycleTrustService service = new LifecycleTrustService();

        var wrongLength = new LinkedHashMap<>(fixture.receipts());
        var model = wrongLength.get("execution_model");
        wrongLength.put("execution_model", new LifecycleTrustService.ReceiptReference(
                model.path(), model.contentSha256(), model.byteSha256(), model.bytes() + 1,
                model.rowsSha256(), model.schema()));
        assertThatThrownBy(() -> service.open(root, null, wrongLength, Map.of()))
                .hasMessageContaining("byte length");

        var wrongSchema = new LinkedHashMap<>(fixture.receipts());
        wrongSchema.put("execution_model", new LifecycleTrustService.ReceiptReference(
                model.path(), model.contentSha256(), model.byteSha256(), model.bytes(),
                model.rowsSha256(), "wrong/schema"));
        assertThatThrownBy(() -> service.open(root, null, wrongSchema, Map.of()))
                .hasMessageContaining("schema");

        var wrongRows = new LinkedHashMap<>(fixture.receipts());
        var bars = wrongRows.get("bars");
        wrongRows.put("bars", new LifecycleTrustService.ReceiptReference(
                bars.path(), bars.contentSha256(), bars.byteSha256(), bars.bytes(),
                JsonHashes.sha256("wrong rows"), bars.schema()));
        assertThatThrownBy(() -> service.open(root, null, wrongRows, Map.of()))
                .hasMessageContaining("row-set");

        LifecycleTrustService.Token token = service.open(root, null, fixture.receipts(), Map.of());
        ArrayNode changedBars = fixture.bars().deepCopy();
        ((ObjectNode) changedBars.get(0)).put("close", 999);
        assertThatThrownBy(() -> service.reopen(token, Map.of("bars", changedBars)))
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> service.reopen(token, Map.of("funding", fixture.bars())))
                .hasMessageContaining("no physical receipt");
    }

    @Test
    void requiredRolesRootReferenceAndLineageFailClosed() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("required"));
        Fixture fixture = fixture(root);
        LifecycleTrustService service = new LifecycleTrustService();

        for (String role : new String[] {"contract_spec", "execution_model", "capacity", "bars"}) {
            Map<String, LifecycleTrustService.ReceiptReference> missing = new LinkedHashMap<>(fixture.receipts());
            missing.remove(role);
            assertThatThrownBy(() -> service.open(root, null, missing, Map.of()))
                    .as(role).isInstanceOf(CustodyException.class)
                    .hasMessageContaining("required");
        }
        Map<String, LifecycleTrustService.ReceiptReference> withoutBars = new LinkedHashMap<>(fixture.receipts());
        withoutBars.remove("bars");
        assertThat(service.open(root, null, withoutBars, Map.of(), false).receipts())
                .doesNotContainKey("bars");
        assertThatThrownBy(() -> service.open(root, " ", fixture.receipts(), Map.of()))
                .hasMessageContaining("rootReference");
        assertThatThrownBy(() -> service.open(root, null, fixture.receipts(), Map.of("bad_sha256", "no")))
                .hasMessageContaining("valid hash");
        assertThatThrownBy(() -> service.open(root, null, fixture.receipts(), java.util.List.of("not", "object")))
                .hasMessageContaining("lineage");
    }

    private static Fixture fixture(Path root) throws IOException {
        ObjectNode contract = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-contract-spec-fixture/1")
                .put("contract_multiplier", 1);
        ObjectNode model = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-execution-model-fixture/1")
                .put("fee_rate", 0.001);
        ObjectNode capacity = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-capacity-fixture/1")
                .put("available_liquidity_usd", 1_000_000);
        ArrayNode bars = JsonHashes.mapper().createArrayNode();
        bars.addObject().put("event_time", "2026-01-01T00:00:00Z").put("close", 100);
        bars.addObject().put("event_time", "2026-01-01T00:01:00Z").put("close", 101);

        Map<String, LifecycleTrustService.ReceiptReference> receipts = new LinkedHashMap<>();
        receipts.put("contract_spec", write(root, "contract.json", contract, null));
        receipts.put("execution_model", write(root, "model.json", model, null));
        receipts.put("capacity", write(root, "capacity.json", capacity, null));
        receipts.put("bars", write(root, "bars.json", bars, JsonHashes.canonicalSha256(bars)));
        return new Fixture(receipts, bars);
    }

    private static LifecycleTrustService.ReceiptReference write(
            Path root, String name, JsonNode value, String rowsHash) throws IOException {
        byte[] bytes = JsonHashes.mapper().writeValueAsBytes(value);
        Files.write(root.resolve(name), bytes);
        String schema = value.isObject() && value.has("schema") ? value.path("schema").asText() : null;
        return new LifecycleTrustService.ReceiptReference(
                name, JsonHashes.ownHash(value), JsonHashes.sha256(bytes), (long) bytes.length, rowsHash, schema);
    }

    private static LifecycleTrustService.ReceiptReference withPath(
            LifecycleTrustService.ReceiptReference reference, String path) {
        return new LifecycleTrustService.ReceiptReference(path, reference.contentSha256(),
                reference.byteSha256(), reference.bytes(), reference.rowsSha256(), reference.schema());
    }

    private record Fixture(
            Map<String, LifecycleTrustService.ReceiptReference> receipts,
            ArrayNode bars) {}
}
