package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.research.calibration.CalibrationCorpus;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationCorpusParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CalibrationCorpus java = new CalibrationCorpus(JSON);
    private int dropIndex;
    private int digestIndex;

    @Test
    void reportSlicesMatchNodeExactly() throws Exception {
        String report = "# résumé\n\n## 7. Verified Live Data Points — BTC\nsecret\n\n"
                + "## 8a. Fallen Knives Composite Score\nscore\n\n## 9. Verdict\nkeep\n"
                + "\n---\n\n```json machine\n{\"schema\":\"report-machine/1\"}\n```\n";
        assertDrop("machine", report, java.dropMachineBlock(report));
        assertDrop("live", report, java.dropVerifiedDataSection(report));
        assertDrop("score", report, java.dropCompositeScoreSection(report));
        assertDrop("machine", "no block", java.dropMachineBlock("no block"));
    }

    @Test
    void digestProjectionMatchesNodeExactly() throws Exception {
        for (String raw : List.of(
                "{}",
                "{\"schema\":\"report-machine/1\",\"framework\":\"fallen_knives\",\"asset\":\"BTC\",\"date\":\"2026-01-01\",\"spot\":{\"value\":100},\"score\":{\"legs\":{\"flow\":5},\"mechanical\":11,\"adjusted\":11.5},\"gates\":{\"passed\":5},\"ev\":{\"scenarios\":[{\"name\":\"base\",\"p\":0.5,\"low\":90,\"high\":120}]},\"deployment\":{\"tranches\":[{\"phase\":\"1A\",\"pct\":10,\"entry_price\":99,\"entry\":\"" + "e".repeat(170) + "\"}]},\"stops\":{\"checkpoint\":{\"date\":\"2026-02-01\",\"line\":80}},\"companion_fr\":{\"score\":3,\"channel\":\"A\"},\"position\":{\"band\":\"FRESH\"},\"verdict\":\"" + "v".repeat(230) + "\"}")) {
            ObjectNode args = JSON.createObjectNode().put("op", "digest").put("raw", raw);
            assertThat(java.projectDigest(raw)).isEqualTo(frozen().path("digests").path(digestIndex++));
        }
        // Jackson and V8 intentionally use different parser diagnostics; the
        // compatibility contract here is fail-open classification, not wording.
        ObjectNode invalidArgs = JSON.createObjectNode().put("op", "digest").put("raw", "{");
        assertThat(frozen().path("invalid").path("ok").asBoolean()).isFalse();
        assertThat(java.projectDigest("{").path("error").asText())
                .startsWith("unparseable machine block:");
    }

    @Test
    void eventAndCapSelectionMatchNodeExactly() throws Exception {
        List<JsonNode> reports = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            reports.add(JSON.readTree("""
                    {"f":"r%d","t":"fallen_knives","digest":{"ok":true,"score":{"adjusted":%s},
                     "gates":{"passed":1},"deployment":{"tranches":[{"phase":"1A","deployed":false,"entry_price":null}]}}}
                    """.formatted(index, 8 + index * 0.1)));
        }
        ObjectNode eventArgs = JSON.createObjectNode().put("op", "event");
        eventArgs.set("report", reports.get(1));
        eventArgs.set("previous", reports.get(0));
        assertThat(java.isEventReport(reports.get(1), reports.get(0)))
                .isEqualTo(frozen().path("event").asBoolean());

        ObjectNode capArgs = JSON.createObjectNode().put("op", "cap").put("cap", 4);
        capArgs.set("reports", JSON.valueToTree(reports));
        CalibrationCorpus.Selection selected = java.selectWithCap(reports, 4);
        ObjectNode actual = JSON.createObjectNode();
        actual.set("keptIdx", JSON.valueToTree(selected.keptIndexes()));
        actual.set("sampledOut", JSON.valueToTree(selected.sampledOut()));
        actual.put("capExceededByEvents", selected.capExceededByEvents());
        assertThat(actual).isEqualTo(frozen().path("selection"));
    }

    private void assertDrop(String operation, String text, CalibrationCorpus.DropResult result) throws Exception {
        ObjectNode args = JSON.createObjectNode().put("op", operation).put("text", text);
        ObjectNode actual = JSON.createObjectNode().put("text", result.text());
        if (result.dropped() == null) {
            actual.putNull("dropped");
        } else {
            ObjectNode dropped = actual.putObject("dropped");
            dropped.put("bytes", result.dropped().bytes());
            if (result.dropped().sha256() != null) dropped.put("sha256", result.dropped().sha256());
            if (result.dropped().heading() != null) dropped.put("heading", result.dropped().heading());
            if (result.dropped().raw() != null) dropped.put("raw", result.dropped().raw());
        }
        assertThat(actual).isEqualTo(frozen().path("drops").path(dropIndex++));
    }

    private JsonNode frozen() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/oracles/calibration-corpus-v1.json")) {
            assertThat(stream).isNotNull(); return JSON.readTree(stream);
        }
    }
}
