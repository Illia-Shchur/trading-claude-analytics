package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class JsonHashesTest {
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
