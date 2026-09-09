package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Canonical candidate/episode/vector inventory boundaries for statistical artifacts. */
final class StrategyStatisticalV5InputArtifactBoundaryMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void artifactRequiresChronologicallyOrderedUniqueEpisodes() {
        ObjectNode unordered = validInput();
        ArrayNode episodes = unordered.withArray("episodes");
        JsonNode first = episodes.remove(0); episodes.add(first);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(unordered))
                .hasMessage("episode records must be chronologically ordered");

        ObjectNode duplicate = validInput();
        duplicate.withArray("episodes").add(duplicate.withArray("episodes").get(2).deepCopy());
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(duplicate))
                .hasMessageContaining("duplicate episode_id");

        ObjectNode empty = validInput(); empty.putArray("episodes");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(empty))
                .hasMessage("statistical artifact requires canonical episode records");
    }

    @Test
    void artifactEnforcesCanonicalAssetsTimesAndEligibility() {
        ObjectNode uppercase = validInput();
        ((ObjectNode) uppercase.withArray("episodes").get(0)).put("asset", "BTC");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(uppercase))
                .hasMessageContaining("asset must be lowercase canonical crypto");

        ObjectNode unsupported = validInput();
        ((ObjectNode) unsupported.withArray("episodes").get(0)).put("asset", "doge");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(unsupported))
                .hasMessageContaining("outside the crypto universe");

        ObjectNode reversed = validInput();
        ((ObjectNode) reversed.withArray("episodes").get(0)).put("resolution_time", "2025-01-01T00:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(reversed))
                .hasMessageContaining("resolution must follow decision");

        ObjectNode missingEligible = validInput();
        ((ObjectNode) missingEligible.withArray("episodes").get(0)).remove("eligible");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(missingEligible))
                .hasMessageContaining("eligible must be boolean");
    }

    @Test
    void artifactChecksOptionalAvailabilityTimesAndNonOverlappingEligibleIntervals() {
        ObjectNode badLabel = validInput();
        ((ObjectNode) badLabel.withArray("episodes").get(0)).put("label_availability_time", "tomorrow");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(badLabel))
                .hasMessageContaining("label_availability_time must be an ISO-8601 UTC timestamp");

        ObjectNode badExecution = validInput();
        ((ObjectNode) badExecution.withArray("episodes").get(0)).put("execution_availability_time", "tomorrow");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(badExecution))
                .hasMessageContaining("execution_availability_time must be an ISO-8601 UTC timestamp");

        ObjectNode overlap = validInput();
        ((ObjectNode) overlap.withArray("episodes").get(1)).put("decision_time", "2025-01-01T12:00:00Z");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(overlap))
                .hasMessage("overlapping eligible episodes for btc");
    }

    @Test
    void artifactBindsReturnInventoryAndZeroSemanticsForEachEpisode() {
        ObjectNode missingReturn = validInput();
        ((ObjectNode) missingReturn.withArray("episodes").get(0).path("candidate_returns")).remove("candidate-1");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(missingReturn))
                .hasMessageContaining("candidate return inventory is incomplete");

        ObjectNode extraReturn = validInput();
        ((ObjectNode) extraReturn.withArray("episodes").get(0).path("candidate_returns"))
                .putObject("unexpected").put("net_r", .1).put("traded", true);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(extraReturn))
                .hasMessageContaining("candidate return inventory is incomplete");

        ObjectNode ineligible = validInput();
        ObjectNode episode = (ObjectNode) ineligible.withArray("episodes").get(0);
        episode.put("eligible", false);
        ((ObjectNode) episode.path("candidate_returns").path("candidate-1")).put("net_r", .1).put("traded", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(ineligible))
                .hasMessageContaining("ineligible episode");

        ObjectNode untraded = validInput();
        ((ObjectNode) untraded.withArray("episodes").get(0).path("candidate_returns").path("candidate-1"))
                .put("net_r", .1).put("traded", false);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(untraded))
                .hasMessageContaining("untraded eligible episode");
    }

    @Test
    void candidateInventoryRequiresUniqueCurrentAndExposedBehaviorAliases() {
        ObjectNode duplicateId = validInput();
        duplicateId.withArray("candidates").add(duplicateId.withArray("candidates").get(0).deepCopy());
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(duplicateId))
                .hasMessage("candidate IDs must be unique strings");

        ObjectNode duplicateAlias = validInput();
        duplicateAlias.withArray("candidates").addObject().put("candidate_id", "candidate-2")
                .put("behavior_sha256", duplicateAlias.withArray("candidates").get(0).path("behavior_sha256").asText());
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(duplicateAlias))
                .hasMessage("candidate behavior aliases must be unique in the current candidate set");

        ObjectNode absent = validInput();
        ((ObjectNode) absent.withArray("candidates").get(0)).put("behavior_sha256", JsonHashes.sha256("unexposed"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalArtifactSet(absent))
                .hasMessageContaining("is absent from the verified exposure head");
    }

    @Test
    void vectorInventoryRequiresExactlyHeadAliasesAndOrderedCompleteRows() {
        ObjectNode input = validInput(); ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(input);
        ObjectNode head = head(); String alias = head.path("entries").get(0).path("behavior_sha256").asText();
        ObjectNode vector = object().set("exposureHead", head); ArrayNode ids = vector.putArray("episodeIds");
        ObjectNode vectors = vector.putObject("vectors"); ArrayNode rows = vectors.putArray(alias);
        for (JsonNode episode : artifact.path("episodes")) {
            ids.add(episode.path("episode_id").asText()); rows.addObject().put("episode_id", episode.path("episode_id").asText())
                    .put("net_r", episode.path("candidate_returns").path("candidate-1").path("net_r").asDouble())
                    .put("traded", episode.path("candidate_returns").path("candidate-1").path("traded").asBoolean())
                    .put("eligible", true);
        }
        ObjectNode inventory = StrategyStatisticalV5.makeVectorInventory(vector);
        assertThat(StrategyStatisticalV5.validateVectorInventory(inventory, head, ids)).isTrue();

        ObjectNode duplicateAlias = vector.deepCopy(); duplicateAlias.with("vectors").putArray(JsonHashes.sha256("extra"));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeVectorInventory(duplicateAlias))
                .hasMessageContaining("aliases must exactly equal the exposure head");

        ObjectNode duplicateId = vector.deepCopy();
        ArrayNode duplicateRows = duplicateId.with("vectors").withArray(alias);
        ((ObjectNode) duplicateRows.get(2)).put("episode_id", duplicateRows.get(0).path("episode_id").asText());
        assertThatThrownBy(() -> StrategyStatisticalV5.makeVectorInventory(duplicateId))
                .hasMessageContaining("has duplicate episode IDs");
    }

    private static ObjectNode validInput() {
        ObjectNode head = head(); String alias = head.path("entries").get(0).path("behavior_sha256").asText();
        ObjectNode input = object(); ObjectNode lineage = input.putObject("lineage");
        lineage.put("dataset_sha256", JsonHashes.sha256("artifact-boundary-data"));
        for (String key : List.of("candidate_set_sha256", "feature_set_sha256", "label_set_sha256", "execution_set_sha256")) {
            lineage.put(key, JsonHashes.sha256("artifact-boundary-" + key));
        }
        input.putArray("candidates").addObject().put("candidate_id", "candidate-1").put("behavior_sha256", alias);
        input.set("exposureHead", head); ArrayNode episodes = input.putArray("episodes");
        episode(episodes, "e1", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z", .2, true);
        episode(episodes, "e2", "2025-02-01T00:00:00Z", "2025-02-02T00:00:00Z", 0, false);
        episode(episodes, "e3", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z", .1, true);
        return input;
    }

    private static ObjectNode head() {
        String dataset = JsonHashes.sha256("artifact-boundary-data"); String alias = JsonHashes.sha256("artifact-boundary-alias");
        ObjectNode options = object().put("hypothesisFamily", "artifact-boundary").put("datasetSha256", dataset);
        options.putArray("entries").addObject().put("behavior_sha256", alias).put("dataset_sha256", dataset);
        return StrategyStatisticalV5.makeExposureHead(options);
    }

    private static void episode(ArrayNode rows, String id, String decision, String resolution, double net, boolean traded) {
        rows.addObject().put("episode_id", id).put("asset", "btc").put("decision_time", decision)
                .put("resolution_time", resolution).put("eligible", true).putObject("candidate_returns")
                .putObject("candidate-1").put("net_r", net).put("traded", traded);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
