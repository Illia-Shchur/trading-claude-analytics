package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class LegacyNodeOracle {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private LegacyNodeOracle() {}

    static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual))
                .isEqualTo(CanonicalJson.canonicalize(expected));
    }

    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static ArrayNode array() { return MAPPER.createArrayNode(); }

    static Path write(Path path, JsonNode value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                com.tradinganalytics.contracts.json.NodePrettyJson.write(value));
        return path;
    }

}
