package com.tradinganalytics.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PrettyCanonicalJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sortsEveryObjectWhilePreservingArrayOrder() throws Exception {
        var value = JSON.readTree("""
                {"z":1,"a":{"d":4,"b":2},"list":[{"y":2,"x":1},3]}
                """);
        assertThat(PrettyCanonicalJson.write(value)).isEqualTo("""
                {
                  "a": {
                    "b": 2,
                    "d": 4
                  },
                  "list": [
                    {
                      "x": 1,
                      "y": 2
                    },
                    3
                  ],
                  "z": 1
                }
                """);
    }

    @Test
    void rendersEmptyAndPrimitiveValuesWithOneTrailingLineFeed() throws Exception {
        assertThat(PrettyCanonicalJson.write(JSON.readTree("{}"))).isEqualTo("{}\n");
        assertThat(PrettyCanonicalJson.write(JSON.readTree("[]"))).isEqualTo("[]\n");
        assertThat(PrettyCanonicalJson.write(JSON.readTree("-0"))).isEqualTo("0\n");
        assertThat(PrettyCanonicalJson.write(JSON.readTree("\"line\\ntext\"")))
                .isEqualTo("\"line\\ntext\"\n");
    }
}
