package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.FundingAnalytics;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class FundingAnalyticsParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void fundingWindowMatchesFrozenWireContract() throws Exception {
        String fixture = """
                {"n":4,"rows":[
                  {"fundingTime":0,"fundingRate":"-0.00001"},
                  {"fundingTime":28800000,"fundingRate":"-0.00005"},
                  {"fundingTime":57600000,"fundingRate":"-0.00005"},
                  {"fundingTime":86400000,"fundingRate":"-0.00008"},
                  {"fundingTime":115200000,"fundingRate":"0.00001"}]}
                """;
        JsonNode input = JSON.readTree(fixture);
        JsonNode actual = FundingAnalytics.fundingBlock((ArrayNode) input.path("rows"), input.path("n").asInt());
        try (InputStream stream = getClass().getResourceAsStream("/oracles/funding-analytics-v1.json")) {
            assertThat(stream).isNotNull();
            assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(JSON.readTree(stream)));
        }
    }
}
