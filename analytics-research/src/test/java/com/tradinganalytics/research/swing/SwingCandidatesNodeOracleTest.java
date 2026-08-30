package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SwingCandidatesNodeOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void frozenCatalogMatchesCapturedNodeOracle() throws Exception {
        JsonNode oracle;
        try (InputStream input = getClass().getResourceAsStream("/oracles/swing-candidates-v1.json")) {
            assertThat(input).as("frozen Swing candidate oracle").isNotNull();
            oracle = MAPPER.readTree(input);
        }
        assertThat(CanonicalJson.canonicalize(SwingCandidates.catalog()))
                .isEqualTo(CanonicalJson.canonicalize(oracle));
        assertThat(SwingCandidates.marketContextCandidates()).hasSize(35);
    }
}
