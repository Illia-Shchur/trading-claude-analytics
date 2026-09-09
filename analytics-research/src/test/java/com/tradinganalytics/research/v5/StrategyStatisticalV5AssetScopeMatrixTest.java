package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure internal asset-scope normalization contracts used by authoritative WFO admission. */
final class StrategyStatisticalV5AssetScopeMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final Method NORMALIZER = normalizer();

    @Test
    void fixtureModeDerivesSortedDeduplicatedObservedCryptoScope() {
        ObjectNode normalized = invoke(NullNode.instance, assets("eth", "btc", "btc"), "FIXTURE");

        assertThat(normalized.path("schema").asText()).isEqualTo("strategy-v5-statistical-asset-scope/1");
        assertThat(texts(normalized.path("trade_assets"))).containsExactly("btc", "eth");
        assertThat(normalized.path("replication_assets")).isEmpty();
        assertThat(normalized.path("context_assets")).isEmpty();
        assertThat(normalized.path("source_sha256").isNull()).isTrue();
        assertThat(normalized.path("content_sha256").asText()).matches("[a-f0-9]{64}");
    }

    @Test
    void authoritativeModeRequiresAnImmutableScopeAndExplicitScopeIsCanonicalized() {
        assertThatThrownBy(() -> invoke(NullNode.instance, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("authoritative WFO requires an immutable precommitted asset scope");

        ObjectNode supplied = scope().put("source_sha256", hash("precommit-scope"));
        supplied.putArray("trade_assets").add("ETH").add("btc");
        supplied.putArray("replication_assets").add("sol");
        supplied.putArray("context_assets").add("macro");
        ObjectNode normalized = invoke(supplied, assets("btc", "eth", "sol"), "AUTHORITATIVE");

        assertThat(texts(normalized.path("trade_assets"))).containsExactly("btc", "eth");
        assertThat(texts(normalized.path("replication_assets"))).containsExactly("sol");
        assertThat(texts(normalized.path("context_assets"))).containsExactly("macro");
        assertThat(normalized.path("source_sha256").asText()).isEqualTo(hash("precommit-scope"));
        assertThat(normalized.path("content_sha256").asText()).isEqualTo(
                StrategyStatisticalV5.hash(normalized.without("content_sha256")));
    }

    @Test
    void scopeRejectsShapeSchemaAndListContractViolations() {
        assertThatThrownBy(() -> invoke(MAPPER.createArrayNode(), assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope must be an object");

        ObjectNode unknown = scope().put("unexpected", true);
        assertThatThrownBy(() -> invoke(unknown, assets("btc"), "AUTHORITATIVE"))
                .hasMessageContaining("asset scope contains unknown caller fields: unexpected");

        ObjectNode badSchema = scope().put("schema", "wrong/1");
        assertThatThrownBy(() -> invoke(badSchema, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope schema/version is invalid");

        ObjectNode badVersion = scope().put("version", 2);
        assertThatThrownBy(() -> invoke(badVersion, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope schema/version is invalid");

        ObjectNode missingTrade = scope();
        missingTrade.remove("trade_assets");
        assertThatThrownBy(() -> invoke(missingTrade, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope trade_assets must be an array");

        ObjectNode badReplication = scope();
        badReplication.put("replication_assets", "sol");
        assertThatThrownBy(() -> invoke(badReplication, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope replication_assets must be an array");

        ObjectNode badContext = scope();
        badContext.put("context_assets", "macro");
        assertThatThrownBy(() -> invoke(badContext, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope context_assets must be an array");
    }

    @Test
    void scopeRejectsDuplicatesOverlapsAndMissingCanonicalAssets() {
        ObjectNode duplicate = scope(); duplicate.putArray("trade_assets").add("btc").add("BTC");
        assertThatThrownBy(() -> invoke(duplicate, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope trade_assets contains duplicate assets");

        ObjectNode empty = scope(); empty.putArray("trade_assets");
        assertThatThrownBy(() -> invoke(empty, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope trade_assets must be non-empty");

        ObjectNode overlap = scope();
        overlap.putArray("trade_assets").add("btc");
        overlap.putArray("replication_assets").add("btc");
        assertThatThrownBy(() -> invoke(overlap, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope overlaps trade_assets and replication_assets: btc");

        ObjectNode absent = scope(); absent.putArray("trade_assets").add("sol");
        assertThatThrownBy(() -> invoke(absent, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope declares a trade asset absent from the canonical artifact");

        ObjectNode omitted = scope(); omitted.putArray("trade_assets").add("btc");
        assertThatThrownBy(() -> invoke(omitted, assets("btc", "eth"), "AUTHORITATIVE"))
                .hasMessage("asset scope omits canonical artifact asset(s): eth");
    }

    @Test
    void scopeRejectsInvalidIdentifiersSourceHashAndContentHash() {
        ObjectNode nonCrypto = scope(); nonCrypto.putArray("trade_assets").add("macro");
        assertThatThrownBy(() -> invoke(nonCrypto, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset macro is outside the crypto universe");

        ObjectNode emptyContext = scope(); emptyContext.putArray("context_assets").add("");
        assertThatThrownBy(() -> invoke(emptyContext, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope context_assets contains an empty identifier");

        ObjectNode badSource = scope().put("source_sha256", "not-a-hash");
        assertThatThrownBy(() -> invoke(badSource, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope source_sha256 must be a SHA-256 hash");

        ObjectNode badContent = StrategyStatisticalV5.withHash(scope()).put("content_sha256", hash("wrong"));
        assertThatThrownBy(() -> invoke(badContent, assets("btc"), "AUTHORITATIVE"))
                .hasMessage("asset scope content hash is invalid");
    }

    private static ObjectNode invoke(JsonNode rawScope, JsonNode artifactAssets, String mode) {
        try {
            return (ObjectNode) NORMALIZER.invoke(null, rawScope, artifactAssets, mode);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IllegalArgumentException illegal) throw illegal;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException(error);
        }
    }

    private static Method normalizer() {
        try {
            Method method = StrategyStatisticalV5.class.getDeclaredMethod("normalizeAssetScope",
                    JsonNode.class, JsonNode.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static ObjectNode scope() {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("schema", "strategy-v5-statistical-asset-scope/1").put("version", 1);
        value.putArray("trade_assets").add("btc");
        value.putArray("replication_assets"); value.putArray("context_assets");
        value.putNull("source_sha256");
        return value;
    }

    private static ArrayNode assets(String... values) {
        ArrayNode output = MAPPER.createArrayNode();
        for (String value : values) output.add(value);
        return output;
    }

    private static List<String> texts(JsonNode values) {
        List<String> output = new ArrayList<>();
        values.forEach(value -> output.add(value.asText()));
        return output;
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
}
