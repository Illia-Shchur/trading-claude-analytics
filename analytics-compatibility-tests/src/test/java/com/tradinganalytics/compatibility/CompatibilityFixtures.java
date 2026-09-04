package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shared accessors for the frozen Node compatibility fixtures. */
final class CompatibilityFixtures {
    private static final String ORACLE_ROOT = "/oracles/";

    private CompatibilityFixtures() {
    }

    static JsonNode readJson(ObjectMapper mapper, String resource) throws IOException {
        try (InputStream stream = open(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return mapper.readTree(stream);
        }
    }

    static String readText(String resource) throws IOException {
        try (InputStream stream = open(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void assertWireEqual(JsonNode expected, JsonNode actual) {
        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }

    private static InputStream open(String resource) {
        return CompatibilityFixtures.class.getResourceAsStream(ORACLE_ROOT + resource);
    }
}
