package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public runner checks each frozen source component before it can publish an outcome. */
class LiquidationPortfolioReplayFreezeValidationMatrixV1Test {
    @TempDir Path temporary;
    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    void runnerRejectsIndependentFrozenObjectAndReferenceMutations() throws Exception {
        ObjectNode sourceFreeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test
                .buildSmallFreeze(temporary.resolve("source-physical"));

        expectObjectFailure(sourceFreeze, freeze -> freeze.put("status", "OUTCOMES_READ"),
                "freeze hash/status is invalid");
        expectObjectFailure(sourceFreeze, freeze -> ((ObjectNode) freeze.path("profile")).put("schema", "profile/99"),
                "unsupported or modified v002 profile contract");
        expectObjectFailure(sourceFreeze, freeze -> freeze.put("precommit_sha256", "0".repeat(64)),
                "frozen precommit reference is inconsistent");
        expectObjectFailure(sourceFreeze, freeze -> freeze.put("dataset_root_sha256", "f".repeat(64)),
                "physical manifest hash is inconsistent");
        expectObjectFailure(sourceFreeze, freeze -> ((ObjectNode) freeze.path("candidate_inventory"))
                .withArray("current_candidates").remove(0), "candidate inventory changed after freeze");
        expectObjectFailure(sourceFreeze, freeze -> ((ObjectNode) freeze.path("executor_identity"))
                .put("java_version", "forged-runtime"), "executor source files changed after freeze");
        expectObjectFailure(sourceFreeze, freeze -> ((ObjectNode) freeze.path("parent_lineage"))
                .put("parent_feasibility_markdown", "invented parent evidence"),
                "v001 family lineage bytes changed after replay freeze");
    }

    @Test
    void runnerReopensFrozenProjectBytesWithoutFollowingMissingOrChangedCustody() throws Exception {
        ObjectNode sourceFreeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test
                .buildSmallFreeze(temporary.resolve("source-physical"));

        expectProjectFailure(sourceFreeze, project -> {
            Path precommit = project.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json");
            ObjectNode changed = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(precommit));
            changed.put("matrix_mutation", true);
            changed.put("content_sha256", JsonHashes.ownHash(changed));
            Files.write(precommit, JsonHashes.canonicalBytes(changed));
        }, "checked-in frozen precommit bytes changed after replay freeze");

        expectProjectFailure(sourceFreeze, project -> Files.writeString(
                project.resolve("docs/research/liquidation-structure-v001/FEASIBILITY.md"), "changed lineage bytes"),
                "v001 family lineage bytes changed after replay freeze");

        expectProjectFailure(sourceFreeze, project -> Files.delete(project.resolve(
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2ReplayCheckpointV1.java")),
                "executor source file is missing or unsafe: analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2ReplayCheckpointV1.java");

        expectProjectFailure(sourceFreeze, project -> Files.delete(project.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json")),
                "frozen precommit file is absent or symlinked");

        expectProjectFailure(sourceFreeze, project -> Files.delete(project.resolve(
                "docs/research/liquidation-structure-v001/FEASIBILITY.md")),
                "required lineage document is absent or unsafe: docs/research/liquidation-structure-v001/FEASIBILITY.md");
    }

    private void expectObjectFailure(ObjectNode sourceFreeze, FreezeMutation mutation, String expected) throws Exception {
        runExpectedFailure(sourceFreeze, null, mutation, expected);
    }

    private void expectProjectFailure(ObjectNode sourceFreeze, ProjectMutation mutation, String expected) throws Exception {
        runExpectedFailure(sourceFreeze, mutation, ignored -> {}, expected);
    }

    private void runExpectedFailure(ObjectNode sourceFreeze, ProjectMutation projectMutation,
            FreezeMutation freezeMutation, String expected) throws Exception {
        Path physicalRoot = temporary.resolve("physical-" + sequence.incrementAndGet());
        Path originalPhysical = Path.of(sourceFreeze.path("physical_root").asText());
        Path projectRoot = null;
        try {
            LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.cloneTree(originalPhysical, physicalRoot);
            ObjectNode freeze = sourceFreeze.deepCopy();
            freeze.put("physical_root", physicalRoot.toString());
            if (projectMutation != null) {
                projectRoot = cloneBoundProject(sourceFreeze, "project-" + sequence.incrementAndGet());
                freeze.put("project_root", projectRoot.toString());
                projectMutation.apply(projectRoot);
            }
            freezeMutation.apply(freeze);
            freeze.put("content_sha256", JsonHashes.ownHash(freeze));
            ObjectNode request = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                    .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                    .put("replay_start", "2024-01-03T00:00:00Z")
                    .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                    .put("execution_end_exclusive", "2024-01-04T00:00:00Z");
            Path out = physicalRoot.resolve("must-not-be-published.json");
            ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", out.toString());
            options.set("freeze", freeze);
            options.set("replay_request", request);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationPortfolioReplayV1.runResumable(options));
            assertEquals(expected, failure.getMessage());
            assertFalse(Files.exists(out), "invalid frozen inputs must fail before replay publication");
        } finally {
            LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.deleteTree(physicalRoot);
            if (projectRoot != null) LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.deleteTree(projectRoot);
        }
    }

    private Path cloneBoundProject(ObjectNode freeze, String leaf) throws Exception {
        Path source = Path.of(freeze.path("project_root").asText());
        Path target = temporary.resolve(leaf);
        for (JsonNode sourceRow : freeze.path("executor_identity").path("sources")) {
            copyRelative(source, target, sourceRow.path("path").asText());
        }
        JsonNode lineage = freeze.path("parent_lineage");
        for (String field : new String[] {"parent_precommit_path", "parent_freeze_manifest_path", "parent_feasibility_path"}) {
            copyRelative(source, target, lineage.path(field).asText());
        }
        copyRelative(source, target, "docs/research/liquidation-daily-stress-v002/frozen-precommit.json");
        return target;
    }

    private static void copyRelative(Path source, Path target, String relative) throws Exception {
        Path from = source.resolve(relative).normalize();
        Path to = target.resolve(relative).normalize();
        if (!from.startsWith(source) || Files.isSymbolicLink(from) || !Files.isRegularFile(from)) {
            throw new AssertionError("bound project input is not a regular file: " + relative);
        }
        Files.createDirectories(to.getParent());
        if (!Files.exists(to)) Files.copy(from, to);
    }

    @FunctionalInterface private interface FreezeMutation { void apply(ObjectNode freeze) throws Exception; }
    @FunctionalInterface private interface ProjectMutation { void apply(Path projectRoot) throws Exception; }
}
