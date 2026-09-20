package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JsonHashesTest {
    @TempDir Path temporary;

    @Test
    void hashesBytesAndCanonicalJsonCompatibly() {
        assertThat(JsonHashes.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        ObjectNode first = JsonHashes.mapper().createObjectNode().put("z", 1).put("a", 2);
        ObjectNode second = JsonHashes.mapper().createObjectNode().put("a", 2).put("z", 1);
        assertThat(JsonHashes.canonicalString(first)).isEqualTo("{\"a\":2,\"z\":1}");
        assertThat(JsonHashes.canonicalSha256(first)).isEqualTo(JsonHashes.canonicalSha256(second));
    }

    @Test
    void streamingCanonicalHashesMatchMaterializedJcsBytes() throws Exception {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("😀", "é\n\u000f")
                .put("negative_zero", -0D)
                .put("small_exponent", 1e-7D)
                .put("fixed_number", 1e-6D);
        value.putArray("rows").addObject().put("z", 2).put("a", true);
        value.putObject("nested").put("path", "/tmp/retained").put("value", 1.5D);
        String materialized = JsonHashes.sha256(JsonHashes.canonicalBytes(value));

        assertThat(JsonHashes.canonicalSha256Streaming(value)).isEqualTo(materialized);
        assertThat(JsonHashes.canonicalSha256(value)).isEqualTo(materialized);
        assertThat(JsonHashes.ownHashStreaming(value)).isEqualTo(materialized);
        assertThat(JsonHashes.serializedSha256Streaming(value))
                .isEqualTo(JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(value)));
        assertThat(JsonHashes.canonicalSha256(null))
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(null)));
        assertThat(JsonHashes.canonicalSha256(NullNode.getInstance()))
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(NullNode.getInstance())));

        value.put("content_sha256", "stale");
        ObjectNode withoutContent = value.deepCopy();
        withoutContent.remove("content_sha256");
        assertThat(JsonHashes.ownHashStreaming(value))
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(withoutContent)));
        assertThatThrownBy(() -> JsonHashes.ownHashStreaming(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JsonHashes.canonicalSha256(value.put("nan", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NaN");
        assertThatThrownBy(() -> JsonHashes.canonicalSha256(
                JsonHashes.mapper().createObjectNode().put("\uD800", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Lone surrogate");
    }

    @Test
    void ownHashExcludesOnlyTheDeclaredTopLevelField() {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", "fixture/1");
        String expected = JsonHashes.ownHash(value);
        value.put("content_sha256", expected);
        assertThat(JsonHashes.ownHash(value)).isEqualTo(expected);
        value.putObject("nested").put("content_sha256", "retained");
        assertThat(JsonHashes.ownHash(value)).isNotEqualTo(expected);
    }

    @Test
    void ownHashLeavesNestedFieldsAndInputUnchanged() {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", "fixture/1")
                .put("content_sha256", "outer");
        value.putObject("nested").put("content_sha256", "inner");
        value.putArray("items").addObject().put("content_sha256", "array-inner");
        ObjectNode expected = JsonHashes.mapper().createObjectNode()
                .put("schema", "fixture/1");
        expected.set("nested", value.get("nested"));
        expected.set("items", value.get("items"));
        String before = JsonHashes.canonicalString(value);

        assertThat(JsonHashes.ownHash(value)).isEqualTo(JsonHashes.canonicalSha256(expected));
        assertThat(JsonHashes.canonicalString(value)).isEqualTo(before);
        assertThat(value.path("nested").path("content_sha256").asText()).isEqualTo("inner");
        assertThat(value.path("items").path(0).path("content_sha256").asText())
                .isEqualTo("array-inner");
    }

    @Test
    void ownHashRejectsJavaNullButAcceptsJsonNullNode() {
        assertThatThrownBy(() -> JsonHashes.ownHash(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(JsonHashes.ownHash(NullNode.getInstance()))
                .isEqualTo(JsonHashes.canonicalSha256(NullNode.getInstance()));
    }

    @Test
    void fileHashStreamsEmptyAndLargeBinaryFilesWithByteHashParity() throws IOException {
        Path empty = temporary.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        byte[] large = new byte[256 * 1024 + 37];
        for (int index = 0; index < large.length; index++) {
            large[index] = (byte) (index * 31 + 7);
        }
        Path largeFile = temporary.resolve("large.bin");
        Files.write(largeFile, large);

        assertThat(JsonHashes.sha256(empty)).isEqualTo(JsonHashes.sha256(new byte[0]));
        assertThat(JsonHashes.sha256(largeFile)).isEqualTo(JsonHashes.sha256(large));
    }

    @Test
    void strictParserRejectsDuplicateKeysAndTrailingTokens() {
        assertThatThrownBy(() -> JsonHashes.parse(
                "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8), "fixture"))
                .isInstanceOf(CustodyException.class);
        assertThatThrownBy(() -> JsonHashes.parse(
                "{} {}".getBytes(StandardCharsets.UTF_8), "fixture"))
                .isInstanceOf(CustodyException.class);
        assertThatThrownBy(() -> JsonHashes.requireSha256("no", "digest"))
                .isInstanceOf(CustodyException.class);
        assertThat(JsonHashes.isSha256("a".repeat(64))).isTrue();
        assertThat(JsonHashes.isSha256("A".repeat(64))).isFalse();
    }
}
