package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Null calibration aggregation and immutable rate/record validation coverage. */
final class StrategyStatisticalV5NullCalibrationMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final List<String> METHODS = List.of("block_permuted_labels", "timestamp_shifted_outcomes",
            "frequency_matched_random_intents", "winners_curse_selection");

    @Test
    void calibrationAggregatesEverySeedAndFixtureIntoAHashBoundDecision() {
        ObjectNode result = StrategyStatisticalV5.calibrateNullControlsV5(calibrationArgs(), replayZero());
        assertThat(result.path("mode").asText()).isEqualTo("FIXTURE_CALIBRATION");
        assertThat(result.path("seeds").toString()).isEqualTo("[11,23,47]");
        assertThat(result.path("null_case_count").asInt()).isEqualTo(3);
        assertThat(result.path("planted_case_count").asInt()).isEqualTo(3);
        assertThat(result.path("records")).hasSize(6);
        assertThat(result.path("null_rejections").asInt()).isZero();
        assertThat(result.path("planted_passes").asInt()).isEqualTo(3);
        assertThat(result.path("null_rejection_rate").asDouble()).isZero();
        assertThat(result.path("power").asDouble()).isEqualTo(1d);
        assertThat(result.path("pass").asBoolean()).isTrue();
        assertThat(StrategyStatisticalV5.validateContractSchema(result)).isTrue();
    }

    @Test
    void calibrationRejectsMismatchedRatesAndCountsAfterContentRehash() {
        ObjectNode result = StrategyStatisticalV5.calibrateNullControlsV5(calibrationArgs(), replayZero());
        final ObjectNode badRate = StrategyStatisticalV5.withHash(result.deepCopy().put("null_rejection_rate", .123));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badRate))
                .hasMessage("null calibration rates or decision are inconsistent");

        final ObjectNode badCount = StrategyStatisticalV5.withHash(result.deepCopy().put("null_case_count", 99));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badCount))
                .hasMessage("null calibration artifact is incomplete");
    }

    @Test
    void calibrationRejectsMalformedRecordsAndInvalidProbabilityBounds() {
        ObjectNode result = StrategyStatisticalV5.calibrateNullControlsV5(calibrationArgs(), replayZero());
        ObjectNode badRecordDraft = result.deepCopy();
        ((ObjectNode) badRecordDraft.path("records").get(0)).put("kind", "UNKNOWN");
        final ObjectNode badRecord = StrategyStatisticalV5.withHash(badRecordDraft);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badRecord))
                .hasMessage("null calibration record is malformed");

        final ObjectNode badAlpha = StrategyStatisticalV5.withHash(result.deepCopy().put("alpha", 0));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badAlpha))
                .hasMessage("null calibration artifact is incomplete");

        final ObjectNode badMode = StrategyStatisticalV5.withHash(result.deepCopy().put("mode", "ACTIVE"));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateContractSchema(badMode))
                .hasMessage("null calibration artifact is incomplete");
    }

    @Test
    void calibrationAdmissionRequiresBothFixtureKindsAndThreeDistinctSeeds() {
        ObjectNode noEdgeMissing = calibrationArgs();
        noEdgeMissing.remove("noEdgeFixtures");
        assertThatThrownBy(() -> StrategyStatisticalV5.calibrateNullControlsV5(noEdgeMissing, replayZero()))
                .hasMessage("null calibration requires repeated no-edge and planted-edge fixtures");

        ObjectNode tooFewSeeds = calibrationArgs();
        tooFewSeeds.putArray("seeds").add(11).add(11);
        assertThatThrownBy(() -> StrategyStatisticalV5.calibrateNullControlsV5(tooFewSeeds, replayZero()))
                .hasMessage("null calibration seed inventory is invalid");
    }

    private static ObjectNode calibrationArgs() {
        ObjectNode fixture = object().put("selectedCandidateId", "candidate-1").put("fixtureId", "calibration-fixture");
        fixture.set("artifact", artifact(0));
        ObjectNode planted = object().put("selectedCandidateId", "candidate-1").put("fixtureId", "planted-fixture");
        planted.set("artifact", artifact(.2));
        ObjectNode args = object();
        args.putArray("noEdgeFixtures").add(fixture);
        args.putArray("plantedEdgeFixtures").add(planted);
        args.set("selectionBudget", selectionBudget());
        args.putArray("seeds").add(11).add(23).add(47);
        args.put("iterations", 4).put("alpha", .5).put("typeICeiling", .10).put("minPower", .80);
        return args;
    }

    private static ObjectNode artifact(double base) {
        String dataset = hash("calibration-data"); String alias = hash("calibration-alias");
        ObjectNode headArgs = object().put("hypothesisFamily", "calibration").put("datasetSha256", dataset);
        headArgs.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
        ObjectNode args = object().set("exposureHead", head);
        ObjectNode lineage = args.putObject("lineage");
        for (String key : List.of("dataset_sha256", "candidate_set_sha256", "feature_set_sha256",
                "label_set_sha256", "execution_set_sha256")) lineage.put(key, hash("calibration-" + key));
        lineage.put("dataset_sha256", dataset);
        args.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", alias);
        ArrayNode episodes = args.putArray("episodes");
        for (int index = 1; index <= 3; index++) {
            String time = "2026-0" + index + "-01T00:00:00Z";
            episodes.addObject().put("episode_id", "e" + index).put("asset", "btc").put("decision_time", time)
                    .put("resolution_time", time.replace("T00:00:00Z", "T00:01:00Z")).put("eligible", true)
                    .putObject("candidate_returns").putObject("candidate-1").put("net_r", .2 * index)
                    .put("traded", true);
            if (base == 0) {
                ObjectNode returns = (ObjectNode) episodes.get(episodes.size() - 1).path("candidate_returns").path("candidate-1");
                returns.put("net_r", 0).put("traded", false);
            } else {
                ((ObjectNode) episodes.get(episodes.size() - 1).path("candidate_returns").path("candidate-1"))
                        .put("net_r", base * index);
            }
        }
        return StrategyStatisticalV5.makeStatisticalArtifactSet(args);
    }

    private static StrategyStatisticalV5.NullReplaySuite replayZero() {
        Map<String, StrategyStatisticalV5.NullReplayMethod> methods = new LinkedHashMap<>();
        for (String method : METHODS) methods.put(method, args -> {
            ObjectNode artifact = (ObjectNode) args.path("artifact").deepCopy();
            for (JsonNode episode : artifact.path("episodes")) {
                JsonNode returns = episode.path("candidate_returns").path("candidate-1");
                if (returns.isObject()) ((ObjectNode) returns).put("net_r", 0).put("traded", false);
            }
            return StrategyStatisticalV5.withHash(artifact);
        });
        return new StrategyStatisticalV5.NullReplaySuite(methods);
    }

    private static ObjectNode selectionBudget() {
        ObjectNode value = object().put("population", 2).put("generations", 1);
        value.putArray("seeds").add(11).add(23).add(47); return value;
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
