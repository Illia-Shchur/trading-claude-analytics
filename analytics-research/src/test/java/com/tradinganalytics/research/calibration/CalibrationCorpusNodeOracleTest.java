package com.tradinganalytics.research.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalibrationCorpusNodeOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final JsonNode ORACLE = loadOracle();
    private final CalibrationCorpus corpus = new CalibrationCorpus(JSON);
    private final Map<String, Integer> oracleIndexes = new LinkedHashMap<>();
    @TempDir Path temporaryDirectory;

    @Test
    void everyPureExportMatchesNodeKnownAnswerVectors() throws Exception {
        for (String text : List.of(
                "plain text",
                "# résumé\n\nbody\n---\n\n```json machine\n{\"x\":1}\n```\n",
                "## 1. Verified Live Data\nsecret\n## 2. Other\nkeep\n",
                "## 7. Verified Live Data Points — BTC\nsecret\n## 8a. Fallen Knives Composite Score\nscore\n## 9. Verdict\nkeep\n")) {
            assertThat(dropNode(corpus.dropMachineBlock(text))).isEqualTo(oracle("dropMachineBlock", JSON.valueToTree(text)));
            assertThat(dropNode(corpus.dropVerifiedDataSection(text))).isEqualTo(oracle("dropVerifiedDataSection", JSON.valueToTree(text)));
            assertThat(dropNode(corpus.dropCompositeScoreSection(text))).isEqualTo(oracle("dropCompositeScoreSection", JSON.valueToTree(text)));
        }

        List<String> machineBlocks = List.of(
                "{}",
                "{\"schema\":\"report-machine/1\",\"score\":{\"adjusted\":9},\"deployment\":{\"tranches\":[{\"phase\":\"1A\",\"entry_price\":100,\"entry\":\"" + "x".repeat(170) + "\"}]}}",
                "{\"ev\":{\"scenarios\":[{\"name\":\"base\",\"p\":0.5,\"low\":1}]},\"stops\":{\"checkpoint\":{\"date\":\"2026-01-01\"}},\"verdict\":\"" + "v".repeat(230) + "\"}",
                "{");
        for (String raw : machineBlocks)
            assertThat(corpus.projectDigest(raw)).isEqualTo(oracle("projectDigest", JSON.valueToTree(raw)));

        Path v2File = ROOT.resolve("reports/btc_fallen_knives_20260822_0346.json");
        JsonNode v2 = JSON.readTree(Files.readString(v2File));
        assertThat(corpus.projectV2Digest(v2)).isEqualTo(oracle("projectV2Digest", v2));

        ArrayNode reportPairs = (ArrayNode) JSON.readTree("""
                [
                  [{"f":"b","t":"fallen_knives","digest":{"ok":true,"gates":{"passed":1},"score":{"adjusted":8.2},"deployment":{"tranches":[]}}},
                   {"f":"a","t":"fallen_knives","digest":{"ok":true,"gates":{"passed":1},"score":{"adjusted":8.1},"deployment":{"tranches":[]}}}],
                  [{"f":"b","t":"fallen_knives","digest":{"ok":true,"gates":{"passed":2},"score":{"adjusted":8.1},"deployment":{"tranches":[]}}},
                   {"f":"a","t":"fallen_knives","digest":{"ok":true,"gates":{"passed":1},"score":{"adjusted":8.1},"deployment":{"tranches":[]}}}],
                  [{"f":"b","t":"flying_rocket","digest":{"ok":true,"gates":{"passed":1},"score":{"adjusted":13},"deployment":{"tranches":[]}}},
                   {"f":"a","t":"flying_rocket","digest":{"ok":true,"gates":{"passed":1},"score":{"adjusted":12.9},"deployment":{"tranches":[]}}}],
                  [{"f":"b","t":"fallen_knives","digest":null},{"f":"a","t":"fallen_knives","digest":null}]
                ]
                """);
        for (JsonNode pair : reportPairs) {
            ObjectNode input = JSON.createObjectNode(); input.set("report", pair.get(0)); input.set("previous", pair.get(1));
            assertThat(corpus.isEventReport(pair.get(0), pair.get(1)))
                    .isEqualTo(oracle("isEventReport", input).asBoolean());
        }

        ArrayNode series = JSON.createArrayNode();
        for (int index = 0; index < 10; index++) series.add(quietReport("r" + index, 8.2 + index / 100d));
        ObjectNode capInput = JSON.createObjectNode(); capInput.set("reports", series); capInput.put("cap", 5);
        CalibrationCorpus.Selection selection = corpus.selectWithCap(toList(series), 5);
        ObjectNode actualSelection = JSON.createObjectNode();
        selection.keptIndexes().forEach(actualSelection.putArray("keptIdx")::add);
        actualSelection.set("sampledOut", strings(selection.sampledOut()));
        actualSelection.put("capExceededByEvents", selection.capExceededByEvents());
        assertThat(actualSelection).isEqualTo(oracle("selectWithCap", capInput));
    }

    @Test
    void corpusCliOutputStreamsAndEveryEmittedByteMatchNode() throws Exception {
        Path out = temporaryDirectory.resolve("corpus");
        List<String> args = List.of("--since", "2026-08-22", "--max-per-series", "2", "--out", out.toString());
        JsonNode expected = ORACLE.path("cli").path("success");
        Instant generatedAt = Instant.parse(expected.path("generated_at").asText());
        clear(out);

        CalibrationCommandResult actual = CalibrationCorpusCommand.run(args, ROOT, generatedAt);
        assertThat(actual.exitCode()).isEqualTo(expected.path("exit").asInt());
        assertThat(normalize(actual.stdout(), generatedAt)).isEqualTo(expected.path("stdout").asText());
        assertThat(normalize(actual.stderr(), generatedAt)).isEqualTo(expected.path("stderr").asText());
        assertTreeBytes(frozenFiles(expected.path("files")), snapshot(out, generatedAt));
    }

    @Test
    void corpusCliFailClosedBranchesMatchNodeExactly() throws Exception {
        int index = 0;
        for (List<String> args : List.of(
                List.<String>of(),
                List.of("--since", "2026-01-01", "--framework", "bad"),
                List.of("--since", "2999-01-01"))) {
            JsonNode expected = ORACLE.path("cli").path("failures").get(index++);
            CalibrationCommandResult actual = CalibrationCorpusCommand.run(args, ROOT, Instant.EPOCH);
            assertThat(actual.exitCode()).as(args.toString()).isEqualTo(expected.path("exit").asInt());
            assertThat(normalize(actual.stdout(), Instant.EPOCH)).as(args.toString()).isEqualTo(expected.path("stdout").asText());
            assertThat(normalize(actual.stderr(), Instant.EPOCH)).as(args.toString()).isEqualTo(expected.path("stderr").asText());
        }
    }

    private static ObjectNode quietReport(String file, double score) {
        ObjectNode report = JSON.createObjectNode(); report.put("f", file); report.put("t", "fallen_knives");
        ObjectNode digest = report.putObject("digest"); digest.put("ok", true); digest.putObject("gates").put("passed", 3);
        digest.putObject("score").put("adjusted", score); digest.putObject("deployment").putArray("tranches"); return report;
    }

    private static JsonNode dropNode(CalibrationCorpus.DropResult result) {
        ObjectNode value = JSON.createObjectNode(); value.put("text", result.text());
        if (result.dropped() == null) value.set("dropped", NullNode.instance);
        else {
            ObjectNode dropped = value.putObject("dropped"); dropped.put("bytes", result.dropped().bytes());
            if (result.dropped().sha256() != null) dropped.put("sha256", result.dropped().sha256());
            if (result.dropped().heading() != null) dropped.put("heading", result.dropped().heading());
            if (result.dropped().raw() != null) dropped.put("raw", result.dropped().raw());
        }
        return value;
    }

    private JsonNode oracle(String operation, JsonNode input) throws Exception {
        JsonNode values = ORACLE.path("pure").path(operation);
        int index = oracleIndexes.merge(operation, 1, Integer::sum) - 1;
        JsonNode expected = values.get(index);
        assertThat(expected).as("missing frozen corpus oracle %s[%s] for %s", operation, index, input).isNotNull();
        return expected.deepCopy();
    }

    private Map<String, byte[]> snapshot(Path root, Instant generatedAt) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList())
                result.put(root.relativize(file).toString(), normalize(Files.readString(file), generatedAt)
                        .replace("$TMP/corpus", "$TMP/out")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Map<String, byte[]> frozenFiles(JsonNode files) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        files.fields().forEachRemaining(entry -> result.put(entry.getKey(), Base64.getDecoder().decode(entry.getValue().asText())));
        return result;
    }

    private String normalize(String value, Instant generatedAt) {
        return value.replace(temporaryDirectory.toString(), "$TMP")
                .replace("$TMP/corpus/", "$TMP/out/")
                .replace(generatedAt.toString(), "$TIME");
    }

    private static void assertTreeBytes(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
        expected.forEach((file, bytes) -> assertThat(actual.get(file)).as(file).containsExactly(bytes));
    }

    private static void clear(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static List<JsonNode> toList(ArrayNode array) { List<JsonNode> result = new ArrayList<>(); array.forEach(result::add); return result; }
    private static ArrayNode strings(List<String> values) { ArrayNode result = JSON.createArrayNode(); values.forEach(result::add); return result; }
    private static JsonNode loadOracle() {
        try (InputStream input = Objects.requireNonNull(
                CalibrationCorpusNodeOracleTest.class.getResourceAsStream("/oracles/calibration-corpus-v1.json"),
                "frozen calibration corpus oracle is missing")) {
            return JSON.readTree(input);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
