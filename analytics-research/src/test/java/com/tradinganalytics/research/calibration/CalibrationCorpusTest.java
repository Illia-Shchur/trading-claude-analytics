package com.tradinganalytics.research.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationCorpusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CalibrationCorpus corpus = new CalibrationCorpus(JSON);

    @Test
    void machineBlockIsRemovedOnlyAtTheEndAndUsesUtf8ByteAccounting() {
        String prose = "# résumé\n\nbody";
        String raw = "{\"schema\":\"report-machine/1\"}\n";
        String report = prose + "\n\n---\n\n```json machine\n" + raw + "```\n";
        CalibrationCorpus.DropResult result = corpus.dropMachineBlock(report);
        assertThat(result.text()).isEqualTo(prose + "\n\n");
        assertThat(result.dropped().raw()).isEqualTo(raw);
        assertThat(result.dropped().bytes()).isEqualTo(
                report.substring(prose.length() + 1).getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.dropped().sha256()).hasSize(64);

        assertThat(corpus.dropMachineBlock(report + "tail").dropped()).isNull();
    }

    @Test
    void headingDropIsTextBasedAndFailOpen() {
        String report = "# title\n\n## 7. Verified Live Data Points — BTC\nsecret\n\n"
                + "## 8. Fallen Knives Composite Score\nscore\n\n## 9. Verdict\nkeep\n";
        CalibrationCorpus.DropResult live = corpus.dropVerifiedDataSection(report);
        assertThat(live.text()).doesNotContain("secret").contains("Composite Score", "Verdict");
        assertThat(live.dropped().heading()).isEqualTo("## 7. Verified Live Data Points — BTC");
        CalibrationCorpus.DropResult score = corpus.dropCompositeScoreSection(live.text());
        assertThat(score.text()).doesNotContain("score").contains("Verdict", "keep");
        assertThat(corpus.dropVerifiedDataSection("## 1. Something Else\nkeep").dropped()).isNull();
    }

    @Test
    void legacyDigestProjectionKeepsNumbersAndBoundsLongProse() throws Exception {
        String verdict = "x".repeat(230);
        JsonNode result = corpus.projectDigest("""
                {"schema":"report-machine/1","framework":"fallen_knives","asset":"BTC","date":"2026-01-01",
                 "spot":{"value":100},"score":{"legs":{"flow":5},"mechanical":11,"adjusted":11.5},
                 "gates":{"active":6,"na":1,"passed":5},
                 "ev":{"scenarios":[{"name":"base","p":0.5,"low":90,"high":120}],"stated_ev":105},
                 "deployment":{"deployed_pct":10,"dry_pct":90,"tranches":[{"phase":"1A","pct":10,"entry_price":99,"entry":"%s"}]},
                 "stops":{"catastrophic":80},"position":{"band":"FRESH","cold_start":false},"verdict":"%s"}
                """.formatted("e".repeat(170), verdict));
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("deployment").path("tranches").get(0).path("deployed").asBoolean()).isTrue();
        assertThat(result.path("deployment").path("tranches").get(0).path("entry_note").asText())
                .endsWith("…[170ch, see slice prose]");
        assertThat(result.path("verdict_note").asText()).endsWith("…[230ch, see slice prose]");
        assertThat(result.path("companion_fr").isNull()).isTrue();

        assertThat(corpus.projectDigest("{").path("error").asText())
                .startsWith("unparseable machine block:");
    }

    @Test
    void eventDetectionCoversGateTrancheScoreAndUnlockCrossings() throws Exception {
        JsonNode first = report("a", "fallen_knives", 7.5, 1, false);
        JsonNode same = report("b", "fallen_knives", 8.0, 1, false);
        assertThat(corpus.isEventReport(same, first)).isTrue();

        JsonNode quiet = report("c", "fallen_knives", 8.5, 1, false);
        assertThat(corpus.isEventReport(quiet, same)).isFalse();
        JsonNode gate = report("d", "fallen_knives", 8.5, 2, false);
        assertThat(corpus.isEventReport(gate, quiet)).isTrue();
        JsonNode tranche = report("e", "fallen_knives", 8.5, 2, true);
        assertThat(corpus.isEventReport(tranche, gate)).isTrue();
        assertThat(corpus.isEventReport(JSON.readTree("{\"digest\":null}"), tranche)).isTrue();
    }

    @Test
    void capAlwaysKeepsEndpointsAndNeverDropsEventsToMeetCap() throws Exception {
        List<JsonNode> quiet = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            quiet.add(report("r" + index, "fallen_knives", 8.0 + index * 0.1, 1, false));
        }
        CalibrationCorpus.Selection sampled = corpus.selectWithCap(quiet, 4);
        assertThat(sampled.keptIndexes()).contains(0, 7).hasSize(4);
        assertThat(sampled.sampledOut()).hasSize(4);
        assertThat(sampled.capExceededByEvents()).isFalse();

        List<JsonNode> events = List.of(
                report("a", "fallen_knives", 7, 1, false),
                report("b", "fallen_knives", 8, 2, false),
                report("c", "fallen_knives", 11, 3, true));
        CalibrationCorpus.Selection over = corpus.selectWithCap(events, 2);
        assertThat(over.capExceededByEvents()).isTrue();
        assertThat(over.keptIndexes()).hasSize(3);
    }

    private static JsonNode report(String filename, String framework, double score, int gates, boolean deployed)
            throws Exception {
        return JSON.readTree("""
                {"f":"%s","t":"%s","digest":{"ok":true,"score":{"adjusted":%s},
                 "gates":{"passed":%d},"deployment":{"tranches":[{"phase":"1A","deployed":%s,"entry_price":%s}]}}}
                """.formatted(filename, framework, score, gates, deployed, deployed ? "100" : "null"));
    }
}
