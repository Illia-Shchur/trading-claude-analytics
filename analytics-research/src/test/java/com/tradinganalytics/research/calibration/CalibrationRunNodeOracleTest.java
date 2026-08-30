package com.tradinganalytics.research.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalibrationRunNodeOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    @TempDir Path temporaryDirectory;
    private Path corpusDirectory;
    private Path runDirectory;
    private Path registry;
    private Path anchors;
    private Path position;
    private Path skill;

    @BeforeEach
    void prepareInputs() throws Exception {
        corpusDirectory = temporaryDirectory.resolve("corpus"); runDirectory = temporaryDirectory.resolve("run");
        registry = temporaryDirectory.resolve("missing-registry.json"); anchors = temporaryDirectory.resolve("anchors.txt");
        position = temporaryDirectory.resolve("position.json"); skill = temporaryDirectory.resolve("skills/fallen/SKILL.md");
        Files.createDirectories(corpusDirectory); Files.createDirectories(skill.getParent());
        String file = "btc_fallen_knives_20260101_0000.md";
        Files.writeString(corpusDirectory.resolve(file + ".slice.md"), "# sliced report\n");
        // Literal null is a real pre-machine-epoch edge: Node treats it exactly
        // like a missing digest and omits score/EV fields from later slim prompts.
        Files.writeString(corpusDirectory.resolve(file + ".digest.json"), "null\n");
        Files.writeString(corpusDirectory.resolve("corpus.json"), """
                {"schema":"calib-corpus/1","filters":{"since":"2026-01-01"},"reports":[{"f":"btc_fallen_knives_20260101_0000.md","a":"BTC","t":"fallen_knives","d":"2026-01-01","at_utc":"2026-01-01T00:00:00.000Z","schema_epoch":"report_machine_1"}]}
                """);
        Files.writeString(anchors, "BTC path low 90, close 105\n");
        Files.writeString(position, "{\"band\":\"FRESH\",\"fills\":[]}\n");
        Files.writeString(skill, "# Framework\n");
    }

    @Test
    void exportedConstantsSchemasAndPureFunctionsMatchNode() throws Exception {
        ObjectNode actualConstants = JSON.createObjectNode();
        actualConstants.set("PHASES", JSON.valueToTree(CalibrationRun.PHASES));
        ObjectNode models = actualConstants.putObject("DEFAULT_MODELS");
        CalibrationRun.PHASES.forEach(phase -> models.put(phase, CalibrationRun.DEFAULT_MODELS.get(phase)));
        ArrayNode dimensions = actualConstants.putArray("DIMENSIONS");
        for (CalibrationRun.Dimension dimension : CalibrationRun.DIMENSIONS) {
            ObjectNode row = dimensions.addObject(); row.put("key", dimension.key()); row.put("focus", dimension.focus());
        }
        actualConstants.set("SOLO_PANEL_DIMENSIONS", JSON.valueToTree(CalibrationRun.SOLO_PANEL_DIMENSIONS));
        ObjectNode schemas = actualConstants.putObject("SCHEMAS");
        CalibrationRun.SCHEMAS.forEach(schemas::set);
        assertThat(actualConstants).isEqualTo(oracle("constants", JSON.createObjectNode()));

        Map<String, JsonNode> vectors = validSchemaVectors();
        for (Map.Entry<String, JsonNode> vector : vectors.entrySet()) {
            ObjectNode input = JSON.createObjectNode(); input.put("schema", vector.getKey()); input.set("value", vector.getValue());
            assertThat(CalibrationRun.validateSchema(vector.getValue(), CalibrationRun.SCHEMAS.get(vector.getKey())))
                    .as(vector.getKey()).containsExactlyElementsOf(textList(oracle("validate", input)));
            JsonNode broken = vector.getValue().deepCopy();
            if (broken.isObject()) ((ObjectNode) broken).remove(CalibrationRun.SCHEMAS.get(vector.getKey()).path("required").get(0).asText());
            input.set("value", broken);
            assertThat(CalibrationRun.validateSchema(broken, CalibrationRun.SCHEMAS.get(vector.getKey())))
                    .as(vector.getKey() + " invalid").containsExactlyElementsOf(textList(oracle("validate", input)));
        }
        ObjectNode arrayInput = JSON.createObjectNode(); arrayInput.put("schema", "GRADE"); arrayInput.set("value", JSON.createArrayNode());
        assertThat(CalibrationRun.validateSchema(arrayInput.path("value"), CalibrationRun.SCHEMAS.get("GRADE")))
                .containsExactlyElementsOf(textList(oracle("validate", arrayInput)));

        assertPure("revisionLogPaths", JSON.readTree("{\"targetSkills\":[\"a/SKILL.md\",\"b/other.md\"]}"));
        assertPure("postCalibrationBoundary", JSON.readTree("{\"corpus\":[{\"d\":\"2026-06-01\"},{\"d\":\"2026-05-01\"}],\"prior\":[{\"date\":\"2026-04-01\"},{\"date\":\"2026-05-01b\"},{\"date\":\"2026-08-01\"}]}"));
        assertPure("zeroTuneDiagnoses", JSON.readTree("{\"diagnoses\":[{\"dimension\":\"a\",\"proposed_tunes\":[]},{\"dimension\":\"b\",\"proposed_tunes\":[{}]},{\"dimension\":\"c\"}]}"));
        assertPure("mergeStrictestWins", JSON.readTree("{\"votes\":[{\"recommendation\":\"adopt\"},{\"recommendation\":\"adopt_with_modification\"}]}"));
        assertPure("mergeStrictestWins", JSON.readTree("{\"votes\":[{\"recommendation\":\"adopt\"},{\"recommendation\":\"reject\"}]}"));
        assertPure("applyTriageClusters", JSON.readTree("""
                {"tunes":[
                  {"name":"keep","framework":"fallen_knives","merged_from":[]},
                  {"name":"merge","framework":"fallen_knives","merged_from":[]},
                  {"name":"cross","framework":"flying_rocket","merged_from":[]},
                  {"name":"untouched","framework":"fallen_knives","merged_from":[]}],
                 "clusters":[{"keep":"keep","merge":["merge","cross","missing","keep"],"reason":"same"}]}
                """));
    }

    @Test
    void fullCliStateMachineMatchesNodeStreamsPromptsStateAndFileBytes() throws Exception {
        Instant createdAt = Instant.EPOCH;
        Path firstRun = runDirectory;
        List<CalibrationCommandResult> expected = executeJavaPipeline(createdAt);
        Map<String, byte[]> expectedFiles = snapshot(firstRun, firstRun);
        runDirectory = temporaryDirectory.resolve("run-repeat");
        clear(runDirectory);

        List<CalibrationCommandResult> actual = executeJavaPipeline(createdAt);
        assertThat(actual).hasSameSizeAs(expected);
        for (int index = 0; index < expected.size(); index++) {
            assertThat(actual.get(index).exitCode()).as("command " + index).isEqualTo(expected.get(index).exitCode());
            assertThat(actual.get(index).stdout().replace(runDirectory.toString(), "<RUN>"))
                    .as("command " + index + " stdout")
                    .isEqualTo(expected.get(index).stdout().replace(firstRun.toString(), "<RUN>"));
            assertThat(actual.get(index).stderr().replace(runDirectory.toString(), "<RUN>"))
                    .as("command " + index + " stderr")
                    .isEqualTo(expected.get(index).stderr().replace(firstRun.toString(), "<RUN>"));
        }
        assertTreeBytes(expectedFiles, snapshot(runDirectory, runDirectory));
    }

    @Test
    void cliUsageValidationPhaseBarrierAndUnknownPhaseMatchNode() throws Exception {
        int expectedIndex = 0;
        for (List<String> args : List.of(
                List.<String>of(), List.of("wat"), List.of("init"), List.of("plan"),
                List.of("collect"), List.of("status"), List.of("next"))) assertCommand(args);

        Path emptyModeRun = temporaryDirectory.resolve("empty-mode-run");
        CalibrationCommandResult emptyMode = javaCommand(List.of("init", "--corpus", corpusDirectory.toString(), "--mode", "", "--registry", registry.toString(),
                "--out", emptyModeRun.toString(), "--run-id", "empty-mode"), Instant.EPOCH);
        assertThat(emptyMode.exitCode()).isZero();
        assertThat(Files.exists(emptyModeRun.resolve("run.json"))).isTrue();
        assertCommand(List.of("init", "--corpus", corpusDirectory.toString(), "--mode", "scoped", "--scope-skipped", "",
                "--registry", registry.toString(), "--out", temporaryDirectory.resolve("bad-scope").toString(), "--run-id", "bad-scope"));

        Path malformedRun = temporaryDirectory.resolve("malformed-run");
        Files.createDirectories(malformedRun);
        for (String raw : List.of("", "{")) {
            Files.writeString(malformedRun.resolve("run.json"), raw);
            assertCommand(List.of("status", "--run", malformedRun.toString()));
        }

        Path malformedCorpus = temporaryDirectory.resolve("malformed-corpus");
        Files.createDirectories(malformedCorpus);
        for (String raw : List.of("", "{")) {
            Files.writeString(malformedCorpus.resolve("corpus.json"), raw);
            assertCommand(List.of("init", "--corpus", malformedCorpus.toString(), "--registry", registry.toString(),
                    "--out", temporaryDirectory.resolve("malformed-output").toString(), "--run-id", "malformed"));
        }

        Instant instant = Instant.EPOCH;
        CalibrationCommandResult javaInit = javaCommand(initArgs(), instant);
        assertThat(javaInit.exitCode()).isZero();
        assertThat(javaInit.stderr()).contains("initialized run oracle-run at " + runDirectory)
                .contains("next: ./bin/analytics calib-run plan extract --run " + runDirectory);

        for (List<String> args : List.of(
                List.of("plan", "grade", "--run", runDirectory.toString()),
                List.of("plan", "unknown", "--run", runDirectory.toString()),
                List.of("collect", "unknown", "--run", runDirectory.toString()))) {
            CalibrationCommandResult actual = javaCommand(args, instant);
            assertThat(actual.exitCode()).as(args.toString()).isEqualTo(1);
            assertThat(actual.stderr()).as(args.toString()).isNotBlank();
        }
    }

    private List<CalibrationCommandResult> executeJavaPipeline(Instant instant) throws Exception {
        List<CalibrationCommandResult> results = new ArrayList<>();
        results.add(javaCommand(initArgs(), instant));
        results.add(javaCommand(List.of("status", "--run", runDirectory.toString()), instant));
        results.add(javaCommand(plan("extract"), instant));
        results.add(javaCommand(collect("extract"), instant));
        Path extractOut = extractOut(); Files.writeString(extractOut, ""); results.add(javaCommand(collect("extract"), instant)); Files.delete(extractOut);
        Files.writeString(extractOut, "{"); results.add(javaCommand(collect("extract"), instant)); Files.delete(extractOut);
        writeAgentOutputs("extract"); results.add(javaCommand(collect("extract"), instant));
        results.add(javaCommand(plan("grade"), instant)); writeAgentOutputs("grade"); results.add(javaCommand(collect("grade"), instant));
        results.add(javaCommand(plan("diagnose"), instant)); writeAgentOutputs("diagnose"); results.add(javaCommand(collect("diagnose"), instant));
        results.add(javaCommand(plan("diagnose"), instant)); results.add(javaCommand(plan("verify"), instant));
        writeAgentOutputs("null-adversary"); results.add(javaCommand(collect("diagnose"), instant));
        results.add(javaCommand(plan("verify"), instant)); writeAgentOutputs("verify-triage"); results.add(javaCommand(collect("verify"), instant));
        results.add(javaCommand(plan("verify"), instant)); writeAgentOutputs("verify-panels"); results.add(javaCommand(collect("verify"), instant));
        results.add(javaCommand(plan("verify"), instant)); writeAgentOutputs("verify-preapply"); results.add(javaCommand(collect("verify"), instant));
        results.add(javaCommand(plan("synthesize"), instant)); writeAgentOutputs("synthesize"); results.add(javaCommand(collect("synthesize"), instant));
        results.add(javaCommand(List.of("next", "--run", runDirectory.toString()), instant));
        return results;
    }

    private void writeAgentOutputs(String phase) throws Exception {
        switch (phase) {
            case "extract" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("01-extract/plan.json")));
                for (JsonNode task : plan.path("tasks")) write(task.path("out").asText(), """
                        {"extracts":[{"file":"btc_fallen_knives_20260101_0000.md","stance":"hold","probability_scenarios":[],"pattern_predictions":[],"falsifiable_claims":[],"declined_actions":[],"notable":"none"}]}
                        """);
            }
            case "grade" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("02-grade/plan.json")));
                for (JsonNode task : plan.path("tasks")) {
                    if ("crossval".equals(task.path("kind").asText())) write(task.path("out").asText(), "{\"crossval\":\"computed companion was coherent\"}");
                    else write(task.path("out").asText(), """
                            {"asset":"BTC","realized_path":[{"date":"2026-01-01","price":100,"score":8}],"prediction_grades":[],"ev_calibration":"neutral","deployment_quality":"sound","stop_analysis":"sound","realized_pnl_note":"none","overall":"adequate"}
                            """);
                }
            }
            case "diagnose" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("03-diagnose/plan.json")));
                int taskIndex = 0;
                for (JsonNode task : plan.path("tasks")) {
                    String dimension = task.path("dimension").asText();
                    ObjectNode output = JSON.createObjectNode(); output.put("dimension", dimension); output.putArray("flaws");
                    ArrayNode proposed = output.putArray("proposed_tunes");
                    if (taskIndex++ > 0) for (int tuneIndex = 1; tuneIndex <= 3; tuneIndex++) {
                        ObjectNode tune = proposed.addObject(); tune.put("name", "Tune " + dimension + " " + tuneIndex);
                        tune.put("before", "old"); tune.put("after", "new"); tune.put("rationale", "observed path");
                    }
                    write(task.path("out").asText(), JSON.writeValueAsString(output));
                }
            }
            case "null-adversary" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("03b-null-adversary/plan.json")));
                for (JsonNode task : plan.path("tasks")) {
                    ObjectNode output = JSON.createObjectNode(); output.put("dimension", task.path("dimension").asText());
                    output.putArray("flaws"); output.putArray("proposed_tunes");
                    write(task.path("out").asText(), JSON.writeValueAsString(output));
                }
            }
            case "verify-triage" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("04-verify/plan_triage.json")));
                write(plan.path("tasks").get(0).path("out").asText(), """
                        {"clusters":[{"keep":"Tune capital-deployment 1","merge":["Tune capital-deployment 2"],"reason":"near duplicate"}]}
                        """);
            }
            case "verify-panels" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("04-verify/plan_panels.json")));
                for (JsonNode task : plan.path("tasks")) {
                    String kind = task.path("kind").asText();
                    if ("edit_audit".equals(kind)) { write(task.path("out").asText(), "{\"editAudit\":\"operative text checked\"}"); continue; }
                    if ("batch".equals(kind)) {
                        ObjectNode result = JSON.createObjectNode(); ArrayNode verdicts = result.putArray("verdicts");
                        int index = 0;
                        for (JsonNode name : task.path("group")) verdicts.add(verdict(name.asText(), index++ == 0 ? "adopt" : "reject"));
                        write(task.path("out").asText(), JSON.writeValueAsString(result));
                    } else write(task.path("out").asText(), JSON.writeValueAsString(verdict(task.path("tuneName").asText(), "reject")));
                }
            }
            case "verify-preapply" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("04-verify/plan_preapply.json")));
                ObjectNode result = JSON.createObjectNode(); ArrayNode tunes = result.putArray("tunes");
                JsonNode adopted = JSON.readTree(Files.readString(runDirectory.resolve("04-verify/verdicts.json"))).path("adoptedSet");
                for (JsonNode item : adopted) {
                    ObjectNode tune = tunes.addObject(); tune.put("name", item.path("tune").path("name").asText());
                    tune.put("apply_ok", true); tune.put("final_text", "final rule"); tune.put("flags", "none");
                    tune.put("toolchain_edit_required", "none");
                }
                result.put("overall", "safe to apply");
                write(plan.path("tasks").get(0).path("out").asText(), JSON.writeValueAsString(result));
            }
            case "synthesize" -> {
                JsonNode plan = JSON.readTree(Files.readString(runDirectory.resolve("05-synthesize/plan.json")));
                write(plan.path("tasks").get(0).path("out").asText(), "# Calibration memo\n\nAll evidence reviewed.\n");
            }
            default -> throw new IllegalArgumentException(phase);
        }
    }

    private static ObjectNode verdict(String name, String recommendation) {
        ObjectNode value = JSON.createObjectNode(); value.put("tune_name", name); value.put("holds", false);
        value.put("refutation_attempt", recommendation.equals("reject") ? "counterexample" : "none found");
        value.put("overfit_risk", "low"); value.put("unintended_consequences", "none");
        value.put("recommendation", recommendation); value.put("counterfactual", "tested"); value.put("toolchain_coupling", "none");
        return value;
    }

    private void assertPure(String operation, JsonNode input) throws Exception {
        JsonNode expected = oracle(operation, input), actual;
        switch (operation) {
            case "revisionLogPaths" -> actual = JSON.valueToTree(CalibrationRun.revisionLogPaths(textList(input.path("targetSkills"))));
            case "postCalibrationBoundary" -> {
                CalibrationRun.Boundary value = CalibrationRun.postCalibrationBoundary(input.path("corpus"), input.path("prior"));
                ObjectNode object = JSON.createObjectNode(); object.put("boundary", value.boundary()); object.put("target", value.target()); actual = object;
            }
            case "zeroTuneDiagnoses" -> actual = JSON.valueToTree(CalibrationRun.zeroTuneDiagnoses(input.path("diagnoses")));
            case "mergeStrictestWins" -> actual = JSON.valueToTree(CalibrationRun.mergeStrictestWins(input.path("votes")));
            case "applyTriageClusters" -> {
                CalibrationRun.TriageResult value = CalibrationRun.applyTriageClusters((ArrayNode) input.path("tunes").deepCopy(), input.path("clusters"));
                ObjectNode object = JSON.createObjectNode(); object.set("tunes", value.tunes()); object.put("mergedCount", value.mergedCount()); actual = object;
            }
            default -> throw new IllegalArgumentException(operation);
        }
        assertThat(actual).as(operation).isEqualTo(expected);
    }

    private static JsonNode oracle(String operation, JsonNode input) throws Exception {
        return switch (operation) {
            case "constants" -> {
                ObjectNode value = JSON.createObjectNode();
                value.set("PHASES", JSON.valueToTree(CalibrationRun.PHASES));
                ObjectNode models = value.putObject("DEFAULT_MODELS");
                CalibrationRun.PHASES.forEach(phase -> models.put(phase, CalibrationRun.DEFAULT_MODELS.get(phase)));
                ArrayNode dimensions = value.putArray("DIMENSIONS");
                for (CalibrationRun.Dimension dimension : CalibrationRun.DIMENSIONS)
                    dimensions.addObject().put("key", dimension.key()).put("focus", dimension.focus());
                value.set("SOLO_PANEL_DIMENSIONS", JSON.valueToTree(CalibrationRun.SOLO_PANEL_DIMENSIONS));
                ObjectNode schemas = value.putObject("SCHEMAS"); CalibrationRun.SCHEMAS.forEach(schemas::set);
                yield value;
            }
            case "validate" -> JSON.valueToTree(CalibrationRun.validateSchema(
                    input.path("value"), CalibrationRun.SCHEMAS.get(input.path("schema").asText())));
            case "revisionLogPaths" -> JSON.valueToTree(CalibrationRun.revisionLogPaths(textList(input.path("targetSkills"))));
            case "postCalibrationBoundary" -> {
                CalibrationRun.Boundary boundary = CalibrationRun.postCalibrationBoundary(input.path("corpus"), input.path("prior"));
                yield JSON.createObjectNode().put("boundary", boundary.boundary()).put("target", boundary.target());
            }
            case "zeroTuneDiagnoses" -> JSON.valueToTree(CalibrationRun.zeroTuneDiagnoses(input.path("diagnoses")));
            case "mergeStrictestWins" -> JSON.valueToTree(CalibrationRun.mergeStrictestWins(input.path("votes")));
            case "applyTriageClusters" -> {
                CalibrationRun.TriageResult triage = CalibrationRun.applyTriageClusters(
                        (ArrayNode) input.path("tunes").deepCopy(), input.path("clusters"));
                ObjectNode value = JSON.createObjectNode(); value.set("tunes", triage.tunes()); value.put("mergedCount", triage.mergedCount()); yield value;
            }
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private void assertCommand(List<String> args) {
        CalibrationCommandResult actual = javaCommand(args, Instant.EPOCH);
        assertThat(actual.exitCode()).as(args.toString()).isEqualTo(1);
        assertThat(actual.stdout()).as(args + " stdout").isEmpty();
        assertThat(actual.stderr()).as(args + " stderr").isNotBlank();
    }

    private CalibrationCommandResult javaCommand(List<String> args, Instant instant) {
        return CalibrationRunCommand.run(args, ROOT, ROOT, () -> instant, () -> "abcd");
    }

    private List<String> initArgs() {
        return List.of("init", "--corpus", corpusDirectory.toString(), "--mode", "full", "--anchors", anchors.toString(),
                "--position", position.toString(), "--registry", registry.toString(), "--skill-dir", skill.getParent().toString(),
                "--target-skills", skill.toString(), "--out", runDirectory.toString(), "--run-id", "oracle-run");
    }
    private List<String> plan(String phase) { return List.of("plan", phase, "--run", runDirectory.toString()); }
    private List<String> collect(String phase) { return List.of("collect", phase, "--run", runDirectory.toString()); }
    private Path extractOut() throws Exception { return Path.of(JSON.readTree(Files.readString(runDirectory.resolve("01-extract/plan.json"))).path("tasks").get(0).path("out").asText()); }

    private static Map<String, JsonNode> validSchemaVectors() throws Exception {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("CHUNK_EXTRACT", JSON.readTree("{\"extracts\":[{\"file\":\"f\",\"stance\":\"s\",\"probability_scenarios\":[{\"scenario\":\"x\",\"probability\":1,\"target_range\":\"x\",\"trigger\":\"x\"}],\"pattern_predictions\":[\"p\"],\"falsifiable_claims\":[\"c\"]}]}"));
        values.put("GRADE", JSON.readTree("{\"asset\":\"BTC\",\"realized_path\":[{\"date\":\"d\",\"price\":1,\"score\":1}],\"prediction_grades\":[{\"prediction\":\"p\",\"source_date\":\"d\",\"verdict\":\"correct\",\"evidence\":\"e\"}],\"ev_calibration\":\"e\",\"deployment_quality\":\"d\",\"stop_analysis\":\"s\",\"realized_pnl_note\":\"r\",\"overall\":\"o\"}"));
        values.put("PRIOR_GRADE", JSON.readTree("{\"tunes\":[{\"name\":\"n\",\"verdict\":\"validated\",\"evidence\":\"e\"}],\"resolved_untested\":[{\"prediction\":\"p\",\"verdict\":\"correct\",\"evidence\":\"e\"}],\"overall\":\"o\"}"));
        values.put("DIAGNOSE", JSON.readTree("{\"dimension\":\"d\",\"flaws\":[{\"flaw\":\"f\",\"evidence\":\"e\",\"severity\":\"high\"}],\"proposed_tunes\":[{\"name\":\"n\",\"before\":\"b\",\"after\":\"a\",\"rationale\":\"r\"}]}"));
        values.put("TRIAGE", JSON.readTree("{\"clusters\":[{\"keep\":\"a\",\"merge\":[],\"reason\":\"r\"}]}"));
        values.put("VERDICT", JSON.readTree("{\"tune_name\":\"n\",\"holds\":true,\"refutation_attempt\":\"r\",\"overfit_risk\":\"o\",\"unintended_consequences\":\"u\",\"recommendation\":\"adopt\"}"));
        values.put("BATCH_VERDICT", JSON.readTree("{\"verdicts\":[]}"));
        values.put("PREAPPLY", JSON.readTree("{\"tunes\":[{\"name\":\"n\",\"apply_ok\":true,\"final_text\":\"f\",\"flags\":\"x\"}],\"overall\":\"o\"}"));
        return values;
    }

    private static void write(String path, String value) throws Exception { Files.writeString(Path.of(path), value, StandardCharsets.UTF_8); }
    private static List<String> textList(JsonNode values) { List<String> result = new ArrayList<>(); values.forEach(value -> result.add(value.asText())); return result; }
    private static Map<String, byte[]> snapshot(Path root, Path normalizedRoot) throws Exception { Map<String, byte[]> result = new LinkedHashMap<>(); try (var stream = Files.walk(root)) { for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) { byte[] bytes = Files.readAllBytes(file); String text = new String(bytes, StandardCharsets.UTF_8); result.put(root.relativize(file).toString(), text.replace(normalizedRoot.toString(), "<RUN>").getBytes(StandardCharsets.UTF_8)); } } return result; }
    private static void assertTreeBytes(Map<String, byte[]> expected, Map<String, byte[]> actual) { assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet()); expected.forEach((file, bytes) -> assertThat(actual.get(file)).as(file).containsExactly(bytes)); }
    private static void clear(Path root) throws Exception { if (!Files.exists(root)) return; try (var stream = Files.walk(root)) { for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
}
