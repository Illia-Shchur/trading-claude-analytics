package com.tradinganalytics.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NodePrettyJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void matchesNodeTwoSpaceFormattingAndPreservesInsertionOrder() throws Exception {
        assertThat(NodePrettyJson.write(JSON.readTree("{\"z\":1,\"a\":[true,{\"x\":\"é\\n\"}],\"n\":null}")))
                .isEqualTo("""
                        {
                          "z": 1,
                          "a": [
                            true,
                            {
                              "x": "é\\n"
                            }
                          ],
                          "n": null
                        }
                        """);
    }

    @Test
    void rendersEmptyContainersOnOneLine() throws Exception {
        assertThat(NodePrettyJson.write(JSON.readTree("{\"a\":[],\"b\":{}}")))
                .isEqualTo("{\n  \"a\": [],\n  \"b\": {}\n}\n");
    }

    @Test
    void usesEcmaScriptScalarRenderingInsteadOfBigDecimalScientificNotation() throws Exception {
        assertThat(NodePrettyJson.write(JSON.readTree(
                "{\"integer\":3e5,\"price\":6.42e4,\"large\":1.6175e11,\"decimal\":1.25,\"escaped\":\"a\\\"b\\n\"}")))
                .isEqualTo("""
                        {
                          "integer": 300000,
                          "price": 64200,
                          "large": 161750000000,
                          "decimal": 1.25,
                          "escaped": "a\\\"b\\n"
                        }
                        """);
    }
}
