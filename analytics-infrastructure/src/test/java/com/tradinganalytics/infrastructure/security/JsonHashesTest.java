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
