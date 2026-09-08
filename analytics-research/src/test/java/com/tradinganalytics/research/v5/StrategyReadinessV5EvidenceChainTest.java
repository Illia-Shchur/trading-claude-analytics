package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Valid local activation-chain coverage plus independent physical-fault rejection checks. */
final class StrategyReadinessV5EvidenceChainTest {
    @TempDir Path temporary;

    @Test
    void validPortableActivationChainVerifiesButRemainsUnauthorised() throws Exception {
        ObjectNode options = fixture("valid");

        ObjectNode result = StrategyReadinessV5.verifyActivationBundleV5(options);

        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(result.path("activation").asText())
                .isEqualTo("VERIFIED_BUT_NO_STRATEGY_AUTHORIZATION");
        assertThat(result.path("strategy_authorization").asText()).isEqualTo("REQUIRED");
        assertThat(result.path("publication").path("verified").asBoolean()).isTrue();
    }

    @Test
    void identityAndHashFaultsRejectThePhysicalCapture() throws Exception {
        ObjectNode options = fixture("identity");
        Path capturePath = Path.of(options.path("githubCapturePath").asText());
        ObjectNode capture = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(capturePath));
        capture.put("repository_id", 999L);
        capture.put("content_sha256", StrategyReadinessV5.ownHash(capture));
        Files.write(capturePath, compact(capture));
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(options))
                .hasMessageMatching(".*(capture|binding|repository|hash).*" );

        ObjectNode hashOptions = fixture("hash");
        Path apiPath = Path.of(hashOptions.path("githubApiReceiptPath").asText());
        Files.write(apiPath, "tampered-api".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(hashOptions))
                .hasMessageContaining("API receipt byte hash mismatch");
    }

    @Test
    void scopeTimeReplayApprovalPermissionAndLedgerFaultsReject() throws Exception {
        ObjectNode scope = fixture("scope");
        ((ObjectNode) scope.path("publication")).remove("evidence");
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(scope))
                .hasMessageContaining("publication evidence");

        ObjectNode expired = fixture("expired");
        expired.put("nowAt", expired.path("publication").path("lease_expires_at").asText());
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(expired))
                .hasMessageMatching(".*(freshness|nonce|lease|expired).*" );

        ObjectNode replay = fixture("replay");
        Path replayEntry = Path.of(replay.path("replayPath").asText())
                .resolve("entries/000000000001-853c264dc3a54f410b62e555c8e038fb19125e2d5548bdf4920dd84813ecd733.json");
        Files.write(replayEntry, "tampered-replay".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(replay))
                .hasMessageMatching(".*(replay|hash|entry).*" );

        ObjectNode approvals = fixture("approvals");
        ((ObjectNode) approvals.path("publication").path("asset_approval")).put("signature", "bad");
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(approvals))
                .hasMessageMatching(".*(approval|signature|publication).*" );

        ObjectNode permissions = fixture("permissions");
        ObjectNode capture = (ObjectNode) permissions.path("githubCapture");
        capture.with("actions_permissions").put("verified", false);
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(permissions))
                .hasMessageContaining("physical GitHub settings capture is required");

        ObjectNode ledger = fixture("ledger");
        Path ledgerEvent = Path.of(ledger.path("ledgerPath").asText()).resolve(
                "events/000000000001-5e55419a3ddc65ed3140a20c9af2ef9be79fa51b6818edc3442a40d24ca4b23d.json");
        ObjectNode event = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(ledgerEvent));
        event.with("payload").put("signal_state", "TAMPERED");
        Files.write(ledgerEvent, compact(event));
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(ledger))
                .hasMessage("physical source byte hash mismatch");
    }

    private ObjectNode fixture(String label) throws Exception {
        JsonNode encoded;
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream("/oracles/strategy-readiness-v5-activation.json"))) {
            encoded = JsonHashes.mapper().readTree(input);
        }
        byte[] compressed = Base64.getDecoder().decode(encoded.path("payload").asText());
        ObjectNode value;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            value = (ObjectNode) JsonHashes.mapper().readTree(gzip).path("options").deepCopy();
        }
        Path root = temporary.resolve(label);
        Files.createDirectories(root);
        JsonNode all;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            all = JsonHashes.mapper().readTree(gzip);
        }
        all.path("files").fields().forEachRemaining(entry -> writeEncoded(root, entry));
        replaceRoot(value, "<TEMP_ROOT>", root.toString());
        return value;
    }

    private static void writeEncoded(Path root, Map.Entry<String, JsonNode> entry) {
        try {
            Path path = root.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, Base64.getDecoder().decode(entry.getValue().asText()));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void replaceRoot(JsonNode node, String from, String to) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) replaceRoot(fields.next().getValue(), from, to);
        } else if (node.isArray()) {
            node.forEach(value -> replaceRoot(value, from, to));
        } else if (node.isTextual() && node instanceof com.fasterxml.jackson.databind.node.ValueNode value) {
            // Jackson scalar nodes are immutable; callers only need path replacement below.
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && entry.getValue().asText().contains(from)) {
                    ((ObjectNode) node).put(entry.getKey(), entry.getValue().asText().replace(from, to));
                }
            });
        }
    }

    private static byte[] compact(JsonNode value) throws Exception {
        return (JsonHashes.mapper().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
