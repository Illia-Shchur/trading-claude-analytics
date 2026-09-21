package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public evaluate boundaries reject malformed persisted artifacts under the setup lease, before outcomes rerun. */
class LiquidationPortfolioReplayExpectedArtifactBoundaryMatrixV1Test {
    private static final long FAILED_SETUP_RESERVATION_MS = 7_200_000L;

    @TempDir Path temporary;

    @Test
    void coreAndStagedEvaluatorsAbortBeforeOutcomeReplayOnResealedArtifactMutations() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("expected-artifact-boundary"));
        ObjectNode freeze = LiquidationStagedPortfolioReplayV1Test.buildFreeze(root);
        ObjectNode request = LiquidationStagedPortfolioReplayV1Test.replayRequest();
        request.put("execution_end_exclusive", "2024-01-05T00:00:00Z");
        ObjectNode runOptions = LiquidationStagedPortfolioReplayV1Test.runAndEvaluateOptions(
                freeze, request, "accepted-core-replay.json", "accepted-core-evidence.json");
        ObjectNode runReceipt = LiquidationPortfolioReplayV1.runAndEvaluate(runOptions);
        assertEquals("BLOCKED", runReceipt.path("status").asText());
        assertTrue(runReceipt.path("single_runner_pass").asBoolean());
        ObjectNode acceptedCore = read(root.resolve("accepted-core-replay.json"));
        ObjectNode acceptedEvidence = read(root.resolve("accepted-core-evidence.json"));
        assertEquals(JsonHashes.ownHash(acceptedCore), acceptedCore.path("content_sha256").asText());
        assertEquals(JsonHashes.ownHash(acceptedEvidence), acceptedEvidence.path("content_sha256").asText());
        assertEquals(acceptedCore.path("content_sha256").asText(), runReceipt.path("replay_sha256").asText());
        assertEquals(acceptedEvidence.path("content_sha256").asText(), runReceipt.path("evidence_sha256").asText());

        List<Mutation> coreMutations = List.of(
                new Mutation("stale outer replay hash", row -> row.put("event_count", row.path("event_count").asLong() + 1),
                        "replay output is not self-hashed and bound to the frozen development inputs", false),
                new Mutation("nested freeze reference", row -> ((ObjectNode) row.path("freeze"))
                        .put("profile_sha256", "0".repeat(64)),
                        "replay freeze references do not match the reopened frozen inputs", true),
                new Mutation("ledger self-hash", row -> ((ObjectNode) row.path("ledger")).put("fixture_mutation", true),
                        "replay ledger is not bound by its own content hash", true),
                new Mutation("event-stream identity", row -> ((ObjectNode) row.path("event_stream_identity").get(0))
                        .put("event_count", row.path("event_stream_identity").get(0).path("event_count").asLong() + 1),
                        "event stream identity/count changed after replay", true),
                new Mutation("candidate inventory", row -> ((ObjectNode) row.path("evaluated_candidates").get(0))
                        .put("variant", "CALLER_INVENTED"),
                        "replay evaluated-candidate inventory differs from frozen candidate exposure", true),
                new Mutation("opportunity lifecycle", row -> ((ObjectNode) row.path("opportunities").get(0))
                        .remove("net_pnl_usdt"),
                        "opportunity rows must explicitly carry net_pnl_usdt and reference_risk_usdt", true),
                new Mutation("paired opportunity identity", row -> ((ObjectNode) row.path("opportunities").get(1))
                        .put("pair_id", "detached-pair"),
                        "paired decision does not contain exactly three traded variants", true));

        int attempt = 0;
        for (Mutation mutation : coreMutations) {
            ObjectNode expected = acceptedCore.deepCopy();
            mutation.apply().accept(expected);
            if (mutation.reseal()) reseal(expected);
            assertRejectedCore(root, freeze, expected, mutation.expectedMessage(), mutation.label(), attempt++);
        }

        ObjectNode inventory = (ObjectNode) freeze.path("candidate_inventory");
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                acceptedCore, acceptedEvidence, inventory);
        ObjectNode stagedAccepted = stagedReplay(plan, acceptedCore);
        assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan,
                LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                (ArrayNode) stagedAccepted.path("opportunities")));

        List<Mutation> stagedMutations = List.of(
                new Mutation("staged ledger envelope", row -> row.put("ledger_sha256", "f".repeat(64)),
                        "staged replay is not self-hashed or is detached from its frozen candidate, plan, source, or account", true),
                new Mutation("staged frozen anchor", row -> ((ObjectNode) row.path("opportunities").get(0))
                        .put("initial_intent_sha256", "0".repeat(64)),
                        "staged output changed its frozen initial decision, branch, direction, or full intent geometry", true),
                new Mutation("staged execution attempt", row -> row.putArray("stage_attempts").addObject()
                        .put("candidate_id", plan.path("candidate_id").asText()).put("stage", 1).put("attempt_id", "bad-stage-one"),
                        "staged replay has malformed or duplicate addition-attempt rows", true));
        for (Mutation mutation : stagedMutations) {
            ObjectNode expected = stagedAccepted.deepCopy();
            mutation.apply().accept(expected);
            if (mutation.reseal()) reseal(expected);
            assertRejectedStaged(root, freeze, plan, acceptedCore, acceptedEvidence, inventory,
                    expected, mutation.expectedMessage(), mutation.label(), attempt++);
        }

        ObjectNode malformedExpected = JsonHashes.mapper().createObjectNode().put("out",
                root.resolve("malformed-expected-output.json").toString());
        malformedExpected.set("freeze", freeze.deepCopy());
        malformedExpected.set("replay_request", request.deepCopy());
        malformedExpected.put("expected_replay", "not-a-json-object");
        IllegalArgumentException malformedFailure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(malformedExpected));
        assertTrue(malformedFailure.getMessage().contains("expected replay must be a JSON object"),
                malformedFailure.getMessage());
        assertFalse(Files.exists(root.resolve("malformed-expected-output.json")));

        List<ObjectNode> events = durableEvents(freeze, root);
        List<ObjectNode> aborts = events.stream().filter(event -> "ABORT".equals(event.path("event_type").asText())).toList();
        assertEquals(coreMutations.size() + stagedMutations.size() + 1, aborts.size(),
                "each malformed persisted artifact is rejected inside one durable setup reservation");
        for (ObjectNode abort : aborts) {
            assertEquals("INITIAL_PHYSICAL_OPEN_AND_ENGINE_SETUP", abort.path("segment_id").asText());
            assertEquals(FAILED_SETUP_RESERVATION_MS, abort.path("reserved_millis").asLong());
            assertEquals(FAILED_SETUP_RESERVATION_MS, abort.path("charged_millis").asLong());
        }
    }

    private static void assertRejectedCore(Path root, ObjectNode freeze, ObjectNode expected,
            String message, String label, int index) {
        String suffix = expected.path("content_sha256").asText();
        Path replayOutput = root.resolve("recomputed-evaluations").resolve("replay-" + suffix + ".json");
        Path evidenceOutput = root.resolve("rejected-core-evidence-" + index + ".json");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", evidenceOutput.toString());
        options.set("freeze", freeze.deepCopy());
        options.set("replay", expected.deepCopy());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.evaluate(options), label);
        assertTrue(failure.getMessage().contains(message), label + ": " + failure.getMessage());
        assertFalse(Files.exists(replayOutput), label + " must fail before publishing a replay");
        assertFalse(Files.exists(evidenceOutput), label + " must fail before publishing evidence");
    }

    private static void assertRejectedStaged(Path root, ObjectNode freeze, ObjectNode plan,
            ObjectNode coreReplay, ObjectNode coreEvidence, ObjectNode inventory, ObjectNode expected,
            String message, String label, int index) {
        Path replayOutput = root.resolve("recomputed-evaluations").resolve(
                "staged-replay-" + expected.path("content_sha256").asText() + ".json");
        Path evidenceOutput = root.resolve("rejected-staged-evidence-" + index + ".json");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", evidenceOutput.toString());
        options.set("freeze", freeze.deepCopy());
        options.set("staged_plan", plan.deepCopy());
        options.set("core_replay", coreReplay.deepCopy());
        options.set("core_evidence", coreEvidence.deepCopy());
        options.set("core_candidate_inventory", inventory.deepCopy());
        options.set("replay", expected.deepCopy());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.evaluateStaged(options), label);
        assertTrue(failure.getMessage().contains(message), label + ": " + failure.getMessage());
        assertFalse(Files.exists(replayOutput), label + " must fail before publishing a staged replay");
        assertFalse(Files.exists(evidenceOutput), label + " must fail before publishing staged evidence");
    }

    private static ObjectNode stagedReplay(ObjectNode plan, ObjectNode coreReplay) {
        JsonNode anchor = plan.path("decision_anchors").get(0);
        ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText())
                .put("macro_gate_policy", plan.path("macro_gate_policy").asText())
                .put("source_mode", plan.path("source_mode").asText())
                .put("manifest_sha256", coreReplay.path("manifest_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("execution_request", coreReplay.path("execution_request").deepCopy());
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("account_id", "fixture-account");
        reseal(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        replay.putArray("stage_attempts");
        replay.putArray("evaluated_candidates").addObject().put("candidate_id", plan.path("candidate_id").asText());
        ObjectNode row = replay.putArray("opportunities").addObject()
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                .put("pair_id", anchor.path("pair_id").asText()).put("asset", anchor.path("asset").asText())
                .put("decision_time", anchor.path("decision_time").asText())
                .put("branch", intent.path("branch").asText()).put("direction", intent.path("direction").asText())
                .put("initial_intent_sha256", anchor.path("initial_intent_sha256").asText())
                .put("outcome_state", "UNRESOLVED_NO_FILL");
        row.set("initial_intent", intent.deepCopy());
        row.putNull("outcome_available_time").putNull("first_fill_time").putNull("exit_time")
                .putNull("net_pnl_usdt").putNull("reference_risk_usdt").putArray("reason_codes").add("FIXTURE_NO_FILL");
        replay.set("staged_plan", plan.deepCopy());
        reseal(replay);
        return replay;
    }

    private static List<ObjectNode> durableEvents(ObjectNode freeze, Path physicalRoot) throws Exception {
        Path eventRoot = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                freeze.path("precommit").path("precommit_id").asText("liquidation-daily-stress-v002"),
                freeze.path("precommit_sha256").asText(), freeze.path("profile_sha256").asText(),
                freeze.path("manifest_sha256").asText(), physicalRoot)
                .ledgerDirectory().resolve("events");
        try (var files = Files.list(eventRoot)) {
            return files.filter(path -> path.getFileName().toString().matches("[0-9]{8}\\.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> readUnchecked(path)).toList();
        }
    }

    private static ObjectNode readUnchecked(Path path) {
        try { return read(path); }
        catch (Exception error) { throw new AssertionError("cannot read durable event " + path, error); }
    }

    private static ObjectNode read(Path path) throws Exception {
        JsonNode node = JsonHashes.mapper().readTree(Files.readAllBytes(path));
        assertTrue(node instanceof ObjectNode, "artifact should be an object: " + path);
        return (ObjectNode) node;
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private record Mutation(String label, Consumer<ObjectNode> apply, String expectedMessage, boolean reseal) {}
}
