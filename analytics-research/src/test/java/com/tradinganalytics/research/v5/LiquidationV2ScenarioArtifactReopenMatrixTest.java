package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reopens scenario artifacts through a validated physical root and each retained execution binding. */
class LiquidationV2ScenarioArtifactReopenMatrixTest {
    private static final String SCENARIO = "fee_slippage";
    private static final String POLICY = "a".repeat(64);
    private static final String LEDGER = "b".repeat(64);
    private static final String EVENTS = "c".repeat(64);
    private static final String CHAIN = "d".repeat(64);
    @TempDir Path temporary;

    @Test
    void exactArtifactReopensAndMalformedEnvelopeOrPhysicalPathsFailClosed() throws Exception {
        Fixture fixture = fixture(temporary.resolve("inside"));
        assertDoesNotThrow(() -> validate(fixture));
        assertThrows(NullPointerException.class, () -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(null, fixture.root()));
        assertThrows(NullPointerException.class, () -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(fixture.replay(), null));

        ObjectNode absent = JsonHashes.mapper().createObjectNode();
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(absent, fixture.root()));
        ObjectNode explicitNull = JsonHashes.mapper().createObjectNode().putNull("stress_evaluation");
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(explicitNull, fixture.root()));
        expectMalformed(fixture.replay(), fixture.root(), "not-an-object");
        ObjectNode nonArray = JsonHashes.mapper().createObjectNode();
        nonArray.putObject("stress_evaluation").put("scenario_results", "rows");
        reject(nonArray, fixture.root(), "stress evaluation scenario inventory is malformed");

        ObjectNode badReference = fixture.replay().deepCopy();
        ((ObjectNode) badReference.path("stress_evaluation").path("scenario_results").get(0))
                .putObject("scenario_run_artifact").put("relative_path", "../outside.json")
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("sha256", "e".repeat(64));
        reject(badReference, fixture.root(), "scenario run artifact reference is malformed or not hash-bound");

        for (String badPath : List.of("/absolute/run.json", "scenario-runsx/run.json", "scenario-runs\\run.json",
                "scenario-runs/C:/run.json", "scenario-runs/../run.json", "scenario-runs/child/../run.json",
                String.valueOf((char) 0))) {
            ObjectNode changed = fixture.replay().deepCopy();
            ((ObjectNode) artifactRow(changed).path("scenario_run_artifact")).put("relative_path", badPath);
            reject(changed, fixture.root(), "scenario run artifact reference is malformed or not hash-bound");
        }
        for (String malformed : List.of("missing", "schema", "digest", "runner-binding")) {
            ObjectNode changed = fixture.replay().deepCopy();
            ObjectNode reference = (ObjectNode) artifactRow(changed).path("scenario_run_artifact");
            switch (malformed) {
                case "missing" -> artifactRow(changed).remove("scenario_run_artifact");
                case "schema" -> reference.put("schema", "other");
                case "digest" -> reference.put("sha256", "not-a-hash");
                case "runner-binding" -> reference.put("sha256", "e".repeat(64));
                default -> throw new IllegalArgumentException(malformed);
            }
            reject(changed, fixture.root(), "scenario run artifact reference is malformed or not hash-bound");
        }

        ObjectNode missing = fixture.replay().deepCopy();
        artifactRow(missing).putObject("scenario_run_artifact").put("relative_path", "scenario-runs/missing.json")
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("sha256", artifactRow(missing).path("runner_result_sha256").asText());
        reject(missing, fixture.root(), "scenario run artifact is missing or escapes the frozen physical root");

        Path missingRoot = temporary.resolve("does-not-exist");
        reject(fixture.replay(), missingRoot, "frozen physical root is unavailable for scenario artifact reopening");
    }

    @Test
    void bytesHashJsonSelfHashAndEveryRecordedRunnerBindingAreIndependentlyChecked() throws Exception {
        for (String mutation : new String[] {"bytes", "invalid-json", "schema", "version", "self-hash",
                "content-ref", "ledger", "events", "scenario", "policy", "run-transform-type",
                "row-transform-type", "transform-value", "transform-chain"}) {
            Path root = temporary.resolve("case-" + mutation);
            Fixture fixture = fixture(root);
            if ("bytes".equals(mutation)) {
                Files.writeString(fixture.file(), "different bytes");
                reject(fixture.replay(), root, "scenario run artifact bytes differ from their retained SHA-256");
                continue;
            }
            if ("invalid-json".equals(mutation)) {
                byte[] bytes = "{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(fixture.file(), bytes);
                ObjectNode row = artifactRow(fixture.replay());
                row.put("runner_result_sha256", JsonHashes.sha256(bytes));
                ObjectNode artifact = (ObjectNode) row.path("scenario_run_artifact");
                artifact.put("sha256", JsonHashes.sha256(bytes));
                reject(fixture.replay(), root, "scenario run artifact is not valid replay-result JSON");
                continue;
            }
            Consumer<ObjectNode> edit = run -> { };
            boolean leaveInnerHashStale = false;
            String message = "reopened scenario run differs from its retained ledger, event, policy, or transform bindings";
            switch (mutation) {
                case "schema" -> edit = run -> run.put("schema", "other");
                case "version" -> edit = run -> run.put("version", 2);
                case "self-hash" -> { edit = run -> run.put("unused", true); leaveInnerHashStale = true; }
                case "content-ref" -> edit = run -> run.put("unused", true);
                case "ledger" -> edit = run -> run.put("ledger_sha256", "e".repeat(64));
                case "events" -> edit = run -> run.put("event_stream_sha256", "e".repeat(64));
                case "scenario" -> edit = run -> ((ObjectNode) run.path("scenario_execution")).put("scenario_id", "other");
                case "policy" -> edit = run -> ((ObjectNode) run.path("scenario_execution")).put("stress_policy_sha256", "e".repeat(64));
                case "run-transform-type" -> edit = run -> ((ObjectNode) run.path("scenario_execution")).put("transform_count", 1.5);
                case "row-transform-type" -> artifactRow(fixture.replay()).put("transform_count", 1.5);
                case "transform-value" -> edit = run -> ((ObjectNode) run.path("scenario_execution")).put("transform_count", 2);
                case "transform-chain" -> edit = run -> ((ObjectNode) run.path("scenario_execution")).put("transform_chain_sha256", "e".repeat(64));
                default -> throw new IllegalArgumentException(mutation);
            }
            ObjectNode run = fixture.run();
            edit.accept(run);
            if (!leaveInnerHashStale) rehash(run);
            if ("content-ref".equals(mutation)) writeArtifactWithoutUpdatingContentRef(fixture, run);
            else writeBoundRun(fixture, run);
            reject(fixture.replay(), root, message);
        }
    }

    @Test
    void symlinkResolutionCannotEscapePhysicalRoot() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("symlink-root"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path outsideFile = writeRun(outside.resolve("run.json"), run());
        Path alias = root.resolve("scenario-runs");
        Files.createSymbolicLink(alias, outside);
        Fixture fixture = fixtureWithPath(root, "scenario-runs/run.json", Files.readAllBytes(outsideFile));
        reject(fixture.replay(), root, "scenario run artifact resolves outside the frozen physical root");
    }

    private static void expectMalformed(ObjectNode replay, Path root, String evaluationValue) {
        ObjectNode changed = JsonHashes.mapper().createObjectNode().put("stress_evaluation", evaluationValue);
        reject(changed, root, "stress evaluation scenario inventory is malformed");
    }

    private static void validate(Fixture fixture) {
        LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(fixture.replay(), fixture.root());
    }

    private static void reject(ObjectNode replay, Path root, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(replay, root));
        assertTrue(error.getMessage().contains(expected),
                () -> "expected '" + expected + "', got '" + error.getMessage() + "'");
    }

    private Fixture fixture(Path root) throws Exception {
        ObjectNode run = run();
        byte[] bytes = JsonHashes.canonicalBytes(run);
        Path file = root.resolve("scenario-runs/run.json");
        Files.createDirectories(file.getParent()); Files.write(file, bytes);
        return fixtureWithPath(root, "scenario-runs/run.json", bytes);
    }

    private Fixture fixtureWithPath(Path root, String relative, byte[] bytes) throws Exception {
        ObjectNode run = (ObjectNode) JsonHashes.mapper().readTree(bytes);
        String sha = JsonHashes.sha256(bytes);
        ObjectNode row = row(relative, sha, run);
        ObjectNode evaluation = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-stress-evaluation/1")
                .put("stress_policy_sha256", POLICY);
        evaluation.putArray("scenario_results").add(row);
        ObjectNode replay = JsonHashes.mapper().createObjectNode().set("stress_evaluation", evaluation);
        Path file = root.resolve(relative);
        if (Files.isRegularFile(file)) { /* Existing in-root artifact. */ }
        else if (!Files.isSymbolicLink(file.getParent())) { Files.createDirectories(file.getParent()); Files.write(file, bytes); }
        return new Fixture(root, replay, run, file);
    }

    private static ObjectNode run() {
        ObjectNode run = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA)
                .put("version", 1).put("ledger_sha256", LEDGER).put("event_stream_sha256", EVENTS);
        run.putObject("scenario_execution").put("scenario_id", SCENARIO).put("stress_policy_sha256", POLICY)
                .put("transform_count", 1).put("transform_chain_sha256", CHAIN);
        rehash(run); return run;
    }

    private static ObjectNode row(String relative, String sha, ObjectNode run) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("scenario_id", SCENARIO)
                .put("runner_result_sha256", sha).put("scenario_run_content_sha256", run.path("content_sha256").asText())
                .put("runner_ledger_sha256", LEDGER).put("runner_event_stream_sha256", EVENTS)
                .put("transform_count", 1).put("transform_chain_sha256", CHAIN);
        row.putObject("scenario_run_artifact").put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA)
                .put("relative_path", relative).put("sha256", sha);
        return row;
    }

    private static ObjectNode artifactRow(ObjectNode replay) {
        return (ObjectNode) replay.path("stress_evaluation").path("scenario_results").get(0);
    }

    private static void writeBoundRun(Fixture fixture, ObjectNode run) throws IOException {
        byte[] bytes = JsonHashes.canonicalBytes(run);
        Files.write(fixture.file(), bytes);
        ObjectNode row = artifactRow(fixture.replay());
        String sha = JsonHashes.sha256(bytes);
        row.put("runner_result_sha256", sha).put("scenario_run_content_sha256", run.path("content_sha256").asText());
        ((ObjectNode) row.path("scenario_run_artifact")).put("sha256", sha);
    }

    private static void writeArtifactWithoutUpdatingContentRef(Fixture fixture, ObjectNode run) throws IOException {
        byte[] bytes = JsonHashes.canonicalBytes(run);
        Files.write(fixture.file(), bytes);
        String sha = JsonHashes.sha256(bytes);
        ObjectNode row = artifactRow(fixture.replay());
        row.put("runner_result_sha256", sha);
        ((ObjectNode) row.path("scenario_run_artifact")).put("sha256", sha);
    }

    private static Path writeRun(Path file, ObjectNode run) throws IOException {
        Files.createDirectories(file.getParent()); Files.write(file, JsonHashes.canonicalBytes(run)); return file;
    }

    private static void rehash(ObjectNode value) { value.remove("content_sha256"); value.put("content_sha256", JsonHashes.ownHash(value)); }
    private record Fixture(Path root, ObjectNode replay, ObjectNode run, Path file) { }
}
