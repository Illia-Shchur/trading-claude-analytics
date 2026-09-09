package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent checks of the declared search budget and public stage boundary. */
final class StrategyRefinementReviewTest {
    @TempDir Path temporary;

    @Test
    void theDeclaredInventoryFreezesWithoutChangingItsBudget() throws Exception {
        ObjectNode result = StrategyResearchImprovementV1.freezeRefinementInventory(input());
        assertThat(result.path("candidate_count").asInt()).isEqualTo(3);
        assertThat(result.path("max_attempts").asInt()).isEqualTo(3);
        assertThat(result.path("promotion_eligible").asBoolean()).isFalse();
    }

    @Test
    void aSmallerDeclaredBudgetCannotBeSilentlyExpanded() throws Exception {
        ObjectNode value = input();
        ((ObjectNode) value.path("budget")).put("max_attempts", 2);
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(rehash(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFamilyResetRequestCannotBeHiddenByTheFreezer() throws Exception {
        ObjectNode value = input().put("reset_exposure", true);
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(rehash(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activationCannotBeSilentlyRewrittenAsADiagnosticFreeze() throws Exception {
        ObjectNode value = input().put("activation_authorized", true);
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(rehash(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void threeLabelsDoNotSubstituteForThreeFrozenBehaviors() throws Exception {
        ObjectNode value = input();
        ((ObjectNode) value.path("members").get(1)).put("shock_threshold", -.08);
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(rehash(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memberLabelsCannotBeReassignedToDifferentThresholds() throws Exception {
        ObjectNode value = input();
        ((ObjectNode) value.path("members").get(0)).put("shock_threshold", -.10);
        ((ObjectNode) value.path("members").get(1)).put("shock_threshold", -.08);
        assertThatThrownBy(() -> StrategyResearchImprovementV1.freezeRefinementInventory(rehash(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicBaselineCannotSmuggleAnUnaccountedThresholdOverride() {
        ObjectNode args = arguments().put("refinement_shock_threshold", -.001);
        assertThatThrownBy(() -> StrategyFixedBaselineV5.run(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?is).*(refinement|override).*" );
    }

    @Test
    void publicBaselineCannotSmuggleANamedUnverifiedMember() {
        ObjectNode args = arguments().put("refinement_shock_threshold", -.10)
                .put("refinement_member_id", "FK-DELEVERAGING-V002-R2")
                .put("refinement_plan_sha256", "0".repeat(64));
        assertThatThrownBy(() -> StrategyFixedBaselineV5.run(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("(?is).*(refinement|override).*" );
    }

    @Test
    void malformedLastMemberCannotLeaveEarlierEvaluationOutputs() throws Exception {
        ObjectNode frozen = StrategyResearchImprovementV1.freezeRefinementInventory(input());
        ((ObjectNode) frozen.path("members").get(2)).put("volatility_threshold", .5);
        Path plan = temporary.resolve("invalid-plan.json");
        Files.write(plan, JsonHashes.mapper().writeValueAsBytes(rehash(frozen)));
        Path output = temporary.resolve("outputs");
        ObjectNode args = arguments().put("refinement", plan.toString()).put("out_dir", output.toString());
        assertThatThrownBy(() -> StrategyFixedBaselineV5.runRefinement(args))
                .isInstanceOf(IllegalArgumentException.class);
        if (Files.exists(output)) {
            try (var files = Files.list(output)) { assertThat(files.count()).isZero(); }
        }
    }

    @Test
    void rehashedDisplaySummaryCannotSubstituteForAFullPredecessor() throws Exception {
        ObjectNode plan = StrategyResearchImprovementV1.freezeRefinementInventory(input());
        ObjectNode physical = rehash(JsonHashes.mapper().createObjectNode().put("schema", "review-physical/1"));
        ObjectNode exposure = head("a".repeat(64), physical.path("content_sha256").asText(), 7);
        ObjectNode predecessor = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-research-evidence-summary/1")
                .put("display_only", true).put("stage", "FIXED_BASELINE")
                .put("hypothesis_family", plan.path("hypothesis_family").asText())
                .put("physical_input_sha256", physical.path("content_sha256").asText());
        for (String field : new String[]{"baseline_sha256", "control_spec_sha256", "experiment_sha256"}) {
            predecessor.set(field, plan.get(field));
        }
        rehash(predecessor);
        plan.put("predecessor_sha256", predecessor.path("content_sha256").asText());
        plan.put("exposure_head_sha256", exposure.path("content_sha256").asText());
        rehash(plan);
        Path planPath = temporary.resolve("summary-bound-plan.json");
        Path physicalPath = temporary.resolve("physical.json");
        Path headPath = temporary.resolve("head.json");
        Path predecessorPath = temporary.resolve("summary.json");
        Files.write(planPath, JsonHashes.mapper().writeValueAsBytes(plan));
        Files.write(physicalPath, JsonHashes.mapper().writeValueAsBytes(physical));
        Files.write(headPath, JsonHashes.mapper().writeValueAsBytes(exposure));
        Files.write(predecessorPath, JsonHashes.mapper().writeValueAsBytes(predecessor));
        byte[] before = Files.readAllBytes(headPath);
        Path output = temporary.resolve("must-not-exist");
        ObjectNode args = arguments().put("refinement", planPath.toString())
                .put("physical_input", physicalPath.toString()).put("exposure_head", headPath.toString())
                .put("starting_exposure_head", headPath.toString()).put("predecessor", predecessorPath.toString())
                .put("out_dir", output.toString());
        assertThatThrownBy(() -> StrategyFixedBaselineV5.runRefinement(args))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("predecessor must use strategy-fixed-baseline-result/1");
        assertThat(Files.readAllBytes(headPath)).isEqualTo(before);
        assertThat(output).doesNotExist();
    }

    @Test
    void ownAppendsRemainDescendantsOfTheFrozenStartingHead() {
        ObjectNode start = head("a".repeat(64), "c".repeat(64), 7);
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("datasetSha256", "c".repeat(64));
        append.set("prior", start); append.putArray("behaviorAliases").add("b".repeat(64));
        ObjectNode current = StrategyStatisticalV5.appendExposureHead(append);
        assertThat(current.path("content_sha256").asText()).isNotEqualTo(start.path("content_sha256").asText());
        validateDescendant(start, current);
    }

    @Test
    void anotherDatasetAppendDoesNotChangeTheRefinementsFrozenDatasetBinding() {
        ObjectNode start = head("a".repeat(64), "c".repeat(64), 7);
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("datasetSha256", "d".repeat(64));
        append.set("prior", start); append.putArray("behaviorAliases").add("b".repeat(64));
        validateDescendant(start, StrategyStatisticalV5.appendExposureHead(append));
        ObjectNode wrongStart = head("a".repeat(64), "d".repeat(64), 7);
        assertThatThrownBy(() -> validateDescendant(wrongStart, wrongStart)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLowerAttemptCounterIsNotAValidResumeHead() {
        ObjectNode start = head("a".repeat(64), "c".repeat(64), 7);
        ObjectNode reset = head("a".repeat(64), "c".repeat(64), 6);
        assertThatThrownBy(() -> validateDescendant(start, reset)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRehashedDivergentHistoryIsNotAValidResumeHead() {
        ObjectNode start = head("a".repeat(64), "c".repeat(64), 7);
        ObjectNode divergent = head("b".repeat(64), "c".repeat(64), 8);
        assertThatThrownBy(() -> validateDescendant(start, divergent)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewDatasetExposureCountsOnceWithoutIncreasingBehaviorK() {
        String behavior = "a".repeat(64), dataA = "c".repeat(64), dataB = "d".repeat(64);
        ObjectNode start = head(behavior, dataA, 1);
        Path path = temporary.resolve("head.json");
        ObjectNode initialize = JsonHashes.mapper().createObjectNode().put("filePath", path.toString());
        initialize.set("head", start); StrategyStatisticalV5.initializeExposureHeadFile(initialize);
        ObjectNode same = appendAttempt(path, start, dataA, behavior);
        assertThat(same.path("exposure_attempt_k").asLong()).isEqualTo(1);
        ObjectNode next = appendAttempt(path, same, dataB, behavior);
        assertThat(next.path("cumulative_k").asLong()).isEqualTo(1);
        assertThat(next.path("exposure_attempt_k").asLong()).isEqualTo(2);
        ObjectNode retry = appendAttempt(path, next, dataB, behavior);
        assertThat(retry.path("content_sha256").asText()).isEqualTo(next.path("content_sha256").asText());
    }

    @Test
    void anotherBehaviorOnANewDatasetDoesNotHideThisBehaviorsNewExposure() {
        String first = "a".repeat(64), second = "b".repeat(64);
        String dataA = "c".repeat(64), dataB = "d".repeat(64);
        ObjectNode start = head(first, dataA, 1);
        Path path = temporary.resolve("interleaved-head.json");
        ObjectNode initialize = JsonHashes.mapper().createObjectNode().put("filePath", path.toString());
        initialize.set("head", start); StrategyStatisticalV5.initializeExposureHeadFile(initialize);
        ObjectNode other = appendAttempt(path, start, dataB, second);
        ObjectNode next = appendAttempt(path, other, dataB, first);
        assertThat(next.path("cumulative_k").asLong()).isEqualTo(2);
        assertThat(next.path("exposure_attempt_k").asLong()).isEqualTo(3);
        ObjectNode retry = appendAttempt(path, next, dataB, first);
        assertThat(retry.path("content_sha256").asText()).isEqualTo(next.path("content_sha256").asText());
    }

    @Test
    void ordinaryExposureAppendsPreservePreviouslyRecordedFixedPairs() {
        String behavior = "a".repeat(64), dataA = "c".repeat(64), dataB = "d".repeat(64);
        ObjectNode start = head(behavior, dataA, 1);
        Path path = temporary.resolve("mixed-stage-head.json");
        ObjectNode initialize = JsonHashes.mapper().createObjectNode().put("filePath", path.toString());
        initialize.set("head", start); StrategyStatisticalV5.initializeExposureHeadFile(initialize);
        ObjectNode fixed = appendAttempt(path, start, dataB, behavior);
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("filePath", path.toString())
                .put("expectedHeadSha256", fixed.path("content_sha256").asText())
                .put("datasetSha256", "e".repeat(64)).put("exposureAttemptCount", 1);
        append.putArray("behaviorAliases").add("b".repeat(64));
        ObjectNode ordinary = StrategyStatisticalV5.appendExposureHeadFile(append);
        ObjectNode retry = appendAttempt(path, ordinary, dataB, behavior);
        assertThat(retry.path("content_sha256").asText()).isEqualTo(ordinary.path("content_sha256").asText());
        assertThat(retry.path("exposure_attempt_k").asLong()).isEqualTo(3);
    }

    @Test
    void aCompetingWriterCannotSplitTheAttemptFromItsHeadOrLoseItsLock() throws Exception {
        ObjectNode start = head("a".repeat(64), "c".repeat(64), 1);
        Path path = temporary.resolve("locked-head.json");
        ObjectNode initialize = JsonHashes.mapper().createObjectNode().put("filePath", path.toString());
        initialize.set("head", start); StrategyStatisticalV5.initializeExposureHeadFile(initialize);
        byte[] before = Files.readAllBytes(path);
        Path lock = Path.of(path + ".lock"); Files.writeString(lock, "another writer");
        assertThatThrownBy(() -> appendAttempt(path, start, "d".repeat(64), "a".repeat(64)))
                .isInstanceOf(RuntimeException.class);
        assertThat(Files.readAllBytes(path)).isEqualTo(before);
        assertThat(Files.readString(lock)).isEqualTo("another writer");
        Files.delete(lock);
        ObjectNode next = appendAttempt(path, start, "d".repeat(64), "a".repeat(64));
        assertThat(next.path("exposure_attempt_k").asLong()).isEqualTo(2);
        assertThat(next.path("fixed_attempt_pairs")).hasSize(1);
        assertThat(JsonHashes.mapper().readTree(Files.readAllBytes(path))).isEqualTo(next);
        assertThat(Files.exists(Path.of(path + ".fixed-attempt-ledger.json"))).isFalse();
    }

    @Test
    void aBoundLegacyPairMigratesOnceWithoutAnotherAttemptOrRewritingItsReceipt() throws Exception {
        String behavior = "a".repeat(64), dataset = "d".repeat(64);
        ObjectNode start = rehash(head(behavior, "c".repeat(64), 2).put("dataset_sha256", dataset));
        Path path = temporary.resolve("migration-head.json");
        ObjectNode initialize = JsonHashes.mapper().createObjectNode().put("filePath", path.toString());
        initialize.set("head", start); StrategyStatisticalV5.initializeExposureHeadFile(initialize);
        ObjectNode legacy = JsonHashes.mapper().createObjectNode().put("schema", "strategy-fixed-attempt-ledger/1")
                .put("family", "review-family").put("head_sha256", start.path("content_sha256").asText());
        legacy.putArray("pairs").addObject().put("behavior_sha256", behavior).put("dataset_sha256", dataset);
        rehash(legacy);
        Path sidecar = Path.of(path + ".fixed-attempt-ledger.json");
        Files.writeString(sidecar, legacy.toString()); byte[] oldBytes = Files.readAllBytes(sidecar);
        ObjectNode migrated = appendAttempt(path, start, dataset, behavior);
        assertThat(migrated.path("exposure_attempt_k").asLong()).isEqualTo(2);
        assertThat(migrated.path("fixed_attempt_ledger_migration_sha256").asText())
                .isEqualTo(legacy.path("content_sha256").asText());
        assertThat(Files.readAllBytes(sidecar)).isEqualTo(oldBytes);
        ObjectNode next = appendAttempt(path, migrated, "e".repeat(64), behavior);
        assertThat(next.path("exposure_attempt_k").asLong()).isEqualTo(3);
        assertThat(appendAttempt(path, next, dataset, behavior)).isEqualTo(next);
    }

    @Test
    void aPairForAnUnknownBehaviorCannotBeAddedByRehashingTheHead() {
        ObjectNode corrupt = head("a".repeat(64), "c".repeat(64), 2);
        corrupt.putArray("fixed_attempt_pairs").addObject()
                .put("behavior_sha256", "b".repeat(64)).put("dataset_sha256", "d".repeat(64));
        rehash(corrupt);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateExposureHead(corrupt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resumingCannotDropPreviouslyFrozenFixedPairs() {
        ObjectNode start = head("a".repeat(64), "c".repeat(64), 2);
        start.putArray("fixed_attempt_pairs").addObject()
                .put("behavior_sha256", "a".repeat(64)).put("dataset_sha256", "d".repeat(64));
        rehash(start);
        ObjectNode reset = head("a".repeat(64), "c".repeat(64), 3);
        assertThatThrownBy(() -> validateDescendant(start, reset)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode head(String behavior, String dataset, int attempts) {
        ObjectNode args = JsonHashes.mapper().createObjectNode().put("hypothesisFamily", "review-family")
                .put("datasetSha256", dataset).put("exposureAttemptK", attempts);
        args.putArray("entries").addObject().put("behavior_sha256", behavior);
        return StrategyStatisticalV5.makeExposureHead(args);
    }

    private static void validateDescendant(ObjectNode start, ObjectNode current) {
        ObjectNode plan = JsonHashes.mapper().createObjectNode()
                .put("exposure_head_sha256", start.path("content_sha256").asText());
        ObjectNode physical = JsonHashes.mapper().createObjectNode().put("content_sha256", "c".repeat(64));
        invoke("validateRefinementExposureLineage",
                new Class<?>[]{ObjectNode.class, ObjectNode.class, ObjectNode.class, ObjectNode.class, String.class},
                start, current, plan, physical, "review-family");
    }

    private static ObjectNode appendAttempt(Path path, ObjectNode prior, String dataset, String behavior) {
        return (ObjectNode) invoke("appendAttempt",
                new Class<?>[]{Path.class, ObjectNode.class, String.class, String.class, String.class, String.class},
                path, prior, dataset, "review-family", behavior, "FK-DELEVERAGING-V002-R2");
    }

    private static Object invoke(String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = StrategyFixedBaselineV5.class.getDeclaredMethod(name, types);
            method.setAccessible(true); return method.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update reviewer adapter after extraction", error);
        }
    }

    private ObjectNode arguments() {
        return JsonHashes.mapper().createObjectNode()
                .put("baseline", repoFile("strategy-research/definitions/fk-deleveraging-absorption/v002.json").toString())
                .put("controls", repoFile("strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json").toString())
                .put("experiment", repoFile("strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json").toString())
                .put("physical_input", temporary.resolve("unused-physical.json").toString())
                .put("exposure_head", temporary.resolve("unused-head.json").toString());
    }

    private static ObjectNode input() throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(repoFile(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/refinement-input-v001.json")));
    }

    private static ObjectNode rehash(ObjectNode value) {
        value.remove("content_sha256"); value.put("content_sha256", JsonHashes.ownHash(value)); return value;
    }

    private static Path repoFile(String relative) {
        Path file = Path.of(relative);
        return (Files.exists(file) ? file : Path.of("..", relative)).toAbsolutePath().normalize();
    }
}
