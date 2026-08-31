package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.json.PrettyCanonicalJson;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PrettyCanonicalJsonParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Case(String source, String expected) {}

    static Stream<Case> values() {
        return Stream.of(
                new Case("{}", "{}\n"),
                new Case("[]", "[]\n"),
                new Case("{\"z\":1,\"a\":{\"d\":4,\"b\":2},\"list\":[{\"y\":2,\"x\":1},3]}",
                        "{\n  \"a\": {\n    \"b\": 2,\n    \"d\": 4\n  },\n  \"list\": [\n    {\n      \"x\": 1,\n      \"y\": 2\n    },\n    3\n  ],\n  \"z\": 1\n}\n"),
                new Case("{\"unicode\":\"€😀\",\"escaped\":\"line\\ntext\",\"negativeZero\":-0}",
                        "{\n  \"escaped\": \"line\\ntext\",\n  \"negativeZero\": 0,\n  \"unicode\": \"€😀\"\n}\n"),
                new Case("[1e-7,1e20,1e21,0.000001,null,true,false]",
                        "[\n  1e-7,\n  100000000000000000000,\n  1e+21,\n  0.000001,\n  null,\n  true,\n  false\n]\n"));
    }

    @ParameterizedTest
    @MethodSource("values")
    void outputMatchesFrozenNodeBytes(Case testCase) throws Exception {
        JsonNode value = JSON.readTree(testCase.source());
        assertThat(PrettyCanonicalJson.write(value)).isEqualTo(testCase.expected());
    }
}
