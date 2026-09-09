package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Directly scoped contract coverage for the non-fixture evaluator result validator. */
final class StrategyStatisticalV5EvaluatorContractMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final String FOLD = "fold-1";
    private static final Method VALIDATOR = validator();

    @Test
    void authoritativeEvaluatorResultAcceptsEachSupportedPhaseContract() {
        Fixture fixture = fixture();
        for (Phase phase : List.of(
                new Phase("OUTER_OOS", null, null, null, "UNWEIGHTED_OOS"),
            new Phase("TRAIN_ONLY", "2026-01-03T00:00:00Z", "2026-01-01T00:00:00Z",
                        "2026-01-03T00:00:00Z", "TRAIN_HALF_LIFE"),
                new Phase("INNER_VALIDATION", "2026-03-01T00:00:00Z", "2026-01-01T00:00:00Z",
                        "2026-03-01T00:00:00Z", "UNWEIGHTED_VALIDATION"))) {
            ObjectNode result = result(fixture, phase);
            ObjectNode metrics = invoke(result, fixture.artifact);
            assertThat(metrics.path("expectancy_r").asDouble()).isEqualTo(.25);
            assertThat(metrics.path("traded_count").asInt()).isEqualTo(1);
        }
    }

    @Test
    void authoritativeEvaluatorResultRejectsEachLineageAndPhaseBindingDrift() {
        Fixture fixture = fixture();
        Phase outer = new Phase("OUTER_OOS", null, null, null, "UNWEIGHTED_OOS");
        ObjectNode baseline = result(fixture, outer);

        ObjectNode wrongSource = baseline.deepCopy().put("source_artifact_sha256", hash("wrong-source"));
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongSource), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluation artifact lineage/scope/cutoff binding mismatch");

        ObjectNode wrongIds = baseline.deepCopy();
        wrongIds.putArray("episode_ids").add("outside");
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongIds), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluation artifact lineage/scope/cutoff binding mismatch");

        ObjectNode wrongPhase = baseline.deepCopy().put("phase", "INNER_VALIDATION");
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongPhase), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluation artifact lineage/scope/cutoff binding mismatch");

        ObjectNode wrongFold = baseline.deepCopy().put("fold_id", "fold-2");
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongFold), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluation artifact lineage/scope/cutoff binding mismatch");

        ObjectNode wrongWeighting = baseline.deepCopy().put("weighting", "TRAIN_HALF_LIFE");
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongWeighting), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluation artifact lineage/scope/cutoff binding mismatch");
    }

    @Test
    void authoritativeEvaluatorResultEnforcesPhaseSpecificCutoffAndWeightingRules() {
        Fixture fixture = fixture();
        Phase inner = new Phase("INNER_VALIDATION", "2026-03-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "2026-03-01T00:00:00Z", "UNWEIGHTED_VALIDATION");
        ObjectNode sameCutoff = result(fixture, inner);
        sameCutoff.put("evaluation_cutoff", inner.fitCutoff);
        sameCutoff = rehashWithLineage(sameCutoff, inner, node(inner.fitCutoff), node(inner.fitCutoff));
        ObjectNode invalidSameCutoff = sameCutoff;
        assertThatThrownBy(() -> invokeAgainst(invalidSameCutoff, fixture.artifact, invalidSameCutoff, definition()))
                .hasMessage("outer OOS inner validation is missing a later evaluation cutoff or is weighted");

        ObjectNode weightedInner = result(fixture, inner).put("weighting", "TRAIN_HALF_LIFE");
        weightedInner = rehashWithLineage(weightedInner,
                new Phase(inner.phase, inner.cutoff, inner.fitCutoff, inner.evaluationCutoff, "TRAIN_HALF_LIFE"),
                node(inner.fitCutoff), node(inner.evaluationCutoff));
        ObjectNode invalidWeightedInner = weightedInner;
        assertThatThrownBy(() -> invokeAgainst(invalidWeightedInner, fixture.artifact, invalidWeightedInner,
                definition())).hasMessage("outer OOS inner validation is missing a later evaluation cutoff or is weighted");

        Phase outer = new Phase("OUTER_OOS", null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "UNWEIGHTED_OOS");
        ObjectNode cutOuter = result(fixture,
                new Phase("OUTER_OOS", null, null, null, "UNWEIGHTED_OOS"))
                .put("fit_cutoff", outer.fitCutoff).put("evaluation_cutoff", outer.evaluationCutoff);
        cutOuter = rehashWithLineage(cutOuter, outer, node(outer.fitCutoff), node(outer.fitCutoff));
        ObjectNode invalidCutOuter = cutOuter;
        assertThatThrownBy(() -> invokeAgainst(invalidCutOuter, fixture.artifact, invalidCutOuter, definition()))
                .hasMessage("outer OOS outer OOS must have null cutoffs and unweighted metrics");

        ObjectNode weightedOuter = result(fixture,
                new Phase("OUTER_OOS", null, null, null, "UNWEIGHTED_OOS"))
                .put("weighting", "TRAIN_HALF_LIFE");
        weightedOuter = rehashWithLineage(weightedOuter,
                new Phase("OUTER_OOS", null, null, null, "TRAIN_HALF_LIFE"),
                NullNode.instance, NullNode.instance);
        ObjectNode invalidWeightedOuter = weightedOuter;
        assertThatThrownBy(() -> invokeAgainst(invalidWeightedOuter, fixture.artifact, invalidWeightedOuter, definition()))
                .hasMessage("outer OOS outer OOS must have null cutoffs and unweighted metrics");
    }

    @Test
    void authoritativeEvaluatorResultRejectsInventoryMetricAndSemanticCorruption() {
        Fixture fixture = fixture();
        Phase outer = new Phase("OUTER_OOS", null, null, null, "UNWEIGHTED_OOS");
        ObjectNode baseline = result(fixture, outer);

        ObjectNode incomplete = baseline.deepCopy();
        incomplete.with("candidate_returns").remove("e1");
        assertThatThrownBy(() -> invokeAgainst(rehash(incomplete), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluator returned incomplete episode inventory");

        ObjectNode extraIntent = baseline.deepCopy();
        ((ObjectNode) extraIntent.withArray("signal_intent_vector").get(0)).put("net_r", .25);
        assertThatThrownBy(() -> invokeAgainst(rehash(extraIntent), fixture.artifact, baseline, definition()))
                .hasMessage("signal intent vector contains outcome fields");

        ObjectNode missingMetric = baseline.deepCopy();
        missingMetric.with("metrics").remove("cost_r");
        assertThatThrownBy(() -> invokeAgainst(rehash(missingMetric), fixture.artifact, baseline, definition()))
                .hasMessage("hard metric cost_r is missing");

        ObjectNode wrongDefinition = baseline.deepCopy();
        wrongDefinition.with("candidate_definition").with("execution").put("threshold", 99);
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongDefinition), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS evaluation is missing an exact candidate definition binding");

        ObjectNode wrongAlias = baseline.deepCopy().put("behavior_alias_sha256", hash("wrong-alias"));
        assertThatThrownBy(() -> invokeAgainst(rehash(wrongAlias), fixture.artifact, baseline, definition()))
                .hasMessage("outer OOS semantic behavior/evaluation-vector binding is missing or inconsistent");
    }

    private static ObjectNode invoke(ObjectNode result, ObjectNode artifact) {
        return invokeAgainst(result, artifact, result, result.path("candidate_definition"));
    }

    private static ObjectNode invokeAgainst(ObjectNode result, ObjectNode artifact, ObjectNode contract,
            JsonNode expectedDefinition) {
        try {
            return (ObjectNode) VALIDATOR.invoke(null, result, artifact, Set.of("e1"), "outer OOS",
                    "AUTHORITATIVE", contract.path("phase").asText(), contract.path("fold_id"),
                    contract.path("cutoff"), contract.path("fit_cutoff"), contract.path("evaluation_cutoff"),
                    null, expectedDefinition);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IllegalArgumentException illegal) throw illegal;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException(error);
        }
    }

    private static ObjectNode result(Fixture fixture, Phase phase) {
        ObjectNode signal = object().put("schema", "strategy-v5-statistical-signal-view/1")
                .put("source_artifact_sha256", fixture.artifact.path("content_sha256").asText());
        signal.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc")
                .put("decision_time", "2025-01-01T00:00:00Z");
        ObjectNode options = object(); options.set("signalArtifact", signal);
        options.putArray("episodeIds").add("e1"); options.put("phase", phase.phase).put("foldId", FOLD);
        if (phase.cutoff == null) options.putNull("cutoff"); else options.put("cutoff", phase.cutoff);
        if (phase.fitCutoff != null) options.put("fitCutoff", phase.fitCutoff);
        options.put("weighting", phase.weighting);
        options.set("candidateDefinition", definition());
        options.putObject("candidateReturns").putObject("e1").put("net_r", .25).put("traded", true);
        options.putArray("signalIntentVector").addObject().put("episode_id", "e1").put("intent", true);
        options.putObject("metrics").put("cost_r", .01).put("coverage_fraction", 1)
                .put("capacity_pass", true).put("max_drawdown_r", 0).put("profit_factor", 2)
                .put("turnover", 1).put("complexity", 1);
        return StrategyStatisticalV5.makeEvaluationArtifact(options);
    }

    private static ObjectNode rehash(ObjectNode value) {
        return StrategyStatisticalV5.withHash(value.deepCopy());
    }

    private static ObjectNode rehashWithLineage(ObjectNode value, Phase phase, JsonNode fit, JsonNode evaluation) {
        ObjectNode lineage = lineage(value, phase, phase.cutoff == null ? NullNode.instance
                : MAPPER.valueToTree(phase.cutoff), fit, evaluation, phase.weighting);
        ObjectNode copy = value.deepCopy(); copy.put("lineage_sha256", StrategyStatisticalV5.hash(lineage));
        return StrategyStatisticalV5.withHash(copy);
    }

    private static ObjectNode lineage(ObjectNode value, Phase phase, JsonNode cutoff, JsonNode fit,
            JsonNode evaluation) {
        return lineage(value, phase, cutoff, fit, evaluation, phase.weighting);
    }

    private static ObjectNode lineage(ObjectNode value, Phase phase, JsonNode cutoff, JsonNode fit,
            JsonNode evaluation, String weighting) {
        ObjectNode lineage = object(); lineage.put("source_artifact_sha256", value.path("source_artifact_sha256").asText());
        ArrayNode episodeIds = MAPPER.createArrayNode().add("e1");
        lineage.set("episode_ids", episodeIds); lineage.put("phase", phase.phase);
        lineage.put("fold_id", FOLD); lineage.set("cutoff", cutoff); lineage.set("fit_cutoff", fit);
        lineage.set("evaluation_cutoff", evaluation); lineage.put("weighting", weighting);
        return lineage;
    }

    private static ObjectNode definition() {
        ObjectNode definition = object();
        definition.putObject("execution").put("threshold", 2);
        return definition;
    }

    private static Fixture fixture() {
        String dataset = hash("evaluator-contract-data"); String alias = hash("evaluator-contract-behavior");
        ObjectNode headOptions = object().put("hypothesisFamily", "evaluator-contract")
                .put("datasetSha256", dataset);
        headOptions.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headOptions);
        ObjectNode input = object().set("exposureHead", head);
        ObjectNode lineage = input.putObject("lineage");
        lineage.put("dataset_sha256", dataset);
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256")) lineage.put(key, hash("evaluator-contract-" + key));
        input.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .put("behavior_sha256", alias);
        input.putArray("episodes").addObject().put("episode_id", "e1").put("asset", "btc")
                .put("decision_time", "2025-01-01T00:00:00Z").put("resolution_time", "2025-01-02T00:00:00Z")
                .put("eligible", true).putObject("candidate_returns").putObject("candidate-1")
                .put("net_r", .25).put("traded", true);
        return new Fixture(StrategyStatisticalV5.makeStatisticalArtifactSet(input));
    }

    private static Method validator() {
        try {
            Method method = StrategyStatisticalV5.class.getDeclaredMethod("validateEvaluatorResult",
                    JsonNode.class, JsonNode.class, Set.class, String.class, String.class, String.class,
                    JsonNode.class, JsonNode.class, JsonNode.class, JsonNode.class, String.class, JsonNode.class);
            method.setAccessible(true); return method;
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static String hash(String value) { return JsonHashes.sha256(value); }
    private static JsonNode node(String value) { return value == null ? NullNode.instance : MAPPER.valueToTree(value); }
    private record Fixture(ObjectNode artifact) {}
    private record Phase(String phase, String cutoff, String fitCutoff, String evaluationCutoff, String weighting) {}
}
