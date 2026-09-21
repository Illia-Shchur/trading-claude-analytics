package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public staged-output verifier matrix over a structurally valid zero-anchor no-macro plan. */
class LiquidationPortfolioReplayStagedOutputVerificationMatrixV1Test {
    private static final String ENVELOPE = "staged replay is not self-hashed or is detached from its frozen candidate, plan, source, or account";
    private static final String RETAINED_PLAN = "staged replay does not retain the exact frozen plan";
    private static final String LEDGER_OBJECT = "ledger must be a JSON object";
    private static final String OUTCOME_PAIR = "staged output has an extra or duplicate frozen predecessor pair";
    private static final String ATTEMPT = "staged replay has malformed or duplicate addition-attempt rows";
    private static final long SETUP_RESERVATION_MS = 7_200_000L;

    @TempDir Path temporary;

    @Test
    void publicRunnerAcceptsValidStagedEnvelopeThenRejectsIndependentFieldsBeforeOutcomes() throws Exception {
        Path sourceRoot = Files.createDirectories(temporary.resolve("staged-source"));
        ObjectNode sourceFreeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(sourceRoot);
        ObjectNode inventory = LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(
                (ObjectNode) sourceFreeze.path("profile"));
        ObjectNode coreReplay = syntheticCoreReplay(inventory);
        ObjectNode coreEvidence = syntheticCoreEvidence(coreReplay);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(coreReplay, coreEvidence, inventory);
        assertEquals(0, plan.path("decision_anchor_count").asInt());

        ObjectNode request = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                .put("execution_end_exclusive", "2024-01-04T00:00:00Z");
        ObjectNode accepted = stagedReplay(sourceFreeze, plan, request);
        assertTrue(JsonHashes.ownHash(accepted).equals(accepted.path("content_sha256").asText()));
        assertTrue(JsonHashes.ownHash(accepted.path("ledger")).equals(accepted.path("ledger").path("content_sha256").asText()));
        assertEquals(0, LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan,
                plan.path("mode_id").asText(), (ArrayNode) accepted.path("opportunities"))
                .path("position_outcome_count").asInt());

        Path acceptedCase = temporary.resolve("accepted-envelope");
        LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.cloneTree(sourceRoot, acceptedCase);
        ObjectNode acceptedFreeze = rebindFreeze(sourceFreeze, acceptedCase);
        ObjectNode acceptedWithValidAttempt = accepted.deepCopy();
        acceptedWithValidAttempt.putArray("stage_attempts").addObject()
                .put("candidate_id", plan.path("candidate_id").asText()).put("stage", 2)
                .put("attempt_id", "accepted-stage-two-attempt");
        reseal(acceptedWithValidAttempt);
        IllegalArgumentException downstream = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(options(acceptedCase, acceptedFreeze,
                        request, plan, coreReplay, coreEvidence, acceptedWithValidAttempt, true)));
        assertEquals("replay output is not self-hashed and bound to the frozen development inputs", downstream.getMessage(),
                "the valid staged envelope and valid stage-two attempt must pass the staged verifier before predecessor reopening");
        assertFalse(Files.exists(acceptedCase.resolve("accepted-replay.json")));
        assertSetupAbort(acceptedCase, acceptedFreeze);

        List<Mutation> mutations = mutations(plan);
        assertTrue(mutations.size() >= 29, "matrix must cover the independent staged public envelope clauses");
        for (int index = 0; index < mutations.size(); index++) {
            Mutation mutation = mutations.get(index);
            Path caseRoot = temporary.resolve(String.format("staged-case-%02d", index));
            LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.cloneTree(sourceRoot, caseRoot);
            ObjectNode freeze = rebindFreeze(sourceFreeze, caseRoot);
            ObjectNode expected = accepted.deepCopy();
            mutation.apply().accept(expected);
            if (mutation.resealLedger()) resealLedger(expected);
            if (mutation.resealOuter()) reseal(expected);

            ObjectNode options = options(caseRoot, freeze, request, plan, coreReplay, coreEvidence,
                    expected, false);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationPortfolioReplayV1.runResumable(options), mutation.label());
            assertEquals(mutation.message(), failure.getMessage(), mutation.label());
            assertFalse(Files.exists(caseRoot.resolve("must-not-publish-staged-replay.json")),
                    mutation.label() + " must fail before economic output publication");
            assertSetupAbort(caseRoot, freeze);
        }
    }

    private static List<Mutation> mutations(ObjectNode plan) {
        ArrayList<Mutation> rows = new ArrayList<>();
        rows.add(new Mutation("schema", replay -> replay.put("schema", "liquidation-v2-replay-result/99"), ENVELOPE));
        rows.add(new Mutation("version", replay -> replay.put("version", 2), ENVELOPE));
        rows.add(new Mutation("stale outer hash", replay -> replay.put("event_count", 1), ENVELOPE, false, false));
        rows.add(new Mutation("ledger wrong type", replay -> replay.put("ledger", "not-an-object"), LEDGER_OBJECT));
        rows.add(new Mutation("ledger self-hash", replay -> ((ObjectNode) replay.path("ledger")).put("mutated", true), ENVELOPE, true, false));
        rows.add(new Mutation("ledger reference", replay -> replay.put("ledger_sha256", "a".repeat(64)), ENVELOPE));
        rows.add(new Mutation("plan reference", replay -> replay.put("plan_sha256", "b".repeat(64)), ENVELOPE));
        rows.add(new Mutation("anchor inventory reference", replay -> replay.put("anchor_inventory_sha256", "c".repeat(64)), ENVELOPE));
        rows.add(new Mutation("candidate id", replay -> replay.put("candidate_id", "invented-candidate"), ENVELOPE));
        rows.add(new Mutation("mode id", replay -> replay.put("mode_id", "INVENTED_MODE"), ENVELOPE));
        rows.add(new Mutation("macro policy", replay -> replay.put("macro_gate_policy", "UNFROZEN"), ENVELOPE));
        rows.add(new Mutation("source mode", replay -> replay.put("source_mode", "PROXY_RETROSPECTIVE_DIAGNOSTIC"), ENVELOPE));
        rows.add(new Mutation("manifest binding", replay -> replay.put("manifest_sha256", "d".repeat(64)), ENVELOPE));
        rows.add(new Mutation("opportunities wrong type", replay -> replay.put("opportunities", "not-an-array"), ENVELOPE));
        rows.add(new Mutation("attempts wrong type", replay -> replay.put("stage_attempts", "not-an-array"), ENVELOPE));
        rows.add(new Mutation("candidate inventory wrong type", replay -> replay.put("evaluated_candidates", "not-an-array"), ENVELOPE));
        rows.add(new Mutation("candidate inventory empty", replay -> replay.putArray("evaluated_candidates"), ENVELOPE));
        rows.add(new Mutation("candidate inventory wrong candidate", replay ->
                ((ObjectNode) replay.path("evaluated_candidates").get(0)).put("candidate_id", "invented-candidate"), ENVELOPE));
        rows.add(new Mutation("account list wrong type", replay -> ((ObjectNode) replay.path("ledger"))
                .put("accounts", "not-an-array"), ENVELOPE, true, true));
        rows.add(new Mutation("account list empty", replay -> ((ObjectNode) replay.path("ledger"))
                .putArray("accounts"), ENVELOPE, true, true));
        rows.add(new Mutation("execution request missing", replay -> replay.remove("execution_request"), ENVELOPE));
        rows.add(new Mutation("retained plan differs", replay -> ((ObjectNode) replay.path("staged_plan"))
                .put("fixture_mutation", true), RETAINED_PLAN));
        rows.add(new Mutation("retained plan null", replay -> replay.putNull("staged_plan"), RETAINED_PLAN));
        rows.add(new Mutation("retained plan omitted", replay -> replay.remove("staged_plan"),
                "JSON value is not canonicalizable"));
        rows.add(new Mutation("extra outcome pair", replay -> ((ArrayNode) replay.path("opportunities"))
                .addObject().put("pair_id", "not-a-frozen-pair"), OUTCOME_PAIR));
        rows.add(new Mutation("attempt candidate", replay -> addAttempt(replay, "wrong-candidate", 2, "attempt-1"), ATTEMPT));
        rows.add(new Mutation("attempt stage nonintegral", replay -> addAttempt(replay, plan.path("candidate_id").asText(), 2.5, "attempt-1"), ATTEMPT));
        rows.add(new Mutation("attempt stage before additions", replay -> addAttempt(replay, plan.path("candidate_id").asText(), 1, "attempt-1"), ATTEMPT));
        rows.add(new Mutation("attempt id blank", replay -> addAttempt(replay, plan.path("candidate_id").asText(), 2, "  "), ATTEMPT));
        rows.add(new Mutation("duplicate attempts", replay -> {
            ArrayNode attempts = replay.putArray("stage_attempts");
            attempts.addObject().put("candidate_id", plan.path("candidate_id").asText()).put("stage", 2).put("attempt_id", "same-attempt");
            attempts.addObject().put("candidate_id", plan.path("candidate_id").asText()).put("stage", 3).put("attempt_id", "same-attempt");
        }, ATTEMPT));
        return List.copyOf(rows);
    }

    private static ObjectNode syntheticCoreReplay(ObjectNode inventory) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        replay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        replay.putArray("opportunities"); replay.putArray("route_audit");
        reseal(replay);
        return replay;
    }

    private static ObjectNode syntheticCoreEvidence(ObjectNode replay) {
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        reseal(evidence);
        return evidence;
    }

    private static ObjectNode stagedReplay(ObjectNode freeze, ObjectNode plan, ObjectNode request) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText())
                .put("macro_gate_policy", plan.path("macro_gate_policy").asText())
                .put("source_mode", plan.path("source_mode").asText())
                .put("manifest_sha256", freeze.path("manifest_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.putObject("ledger").putArray("accounts").addObject().put("account_id", "routed");
        resealLedger(replay);
        replay.putArray("opportunities"); replay.putArray("stage_attempts");
        replay.putArray("evaluated_candidates").add(LiquidationV2StagedCandidateInventoryV1.candidate(
                plan, plan.path("mode_id").asText()));
        replay.set("execution_request", request.deepCopy());
        replay.set("staged_plan", plan.deepCopy());
        reseal(replay);
        return replay;
    }

    private static ObjectNode options(Path root, ObjectNode freeze, ObjectNode request, ObjectNode plan,
            ObjectNode coreReplay, ObjectNode coreEvidence, ObjectNode expected, boolean validControl) {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("out", root.resolve(validControl ? "accepted-replay.json" : "must-not-publish-staged-replay.json").toString());
        options.set("freeze", freeze.deepCopy());
        options.set("replay_request", request.deepCopy());
        options.set("staged_plan", plan.deepCopy());
        options.set("core_replay", coreReplay.deepCopy());
        options.set("core_evidence", coreEvidence.deepCopy());
        options.set("expected_replay", expected.deepCopy());
        return options;
    }

    private static ObjectNode rebindFreeze(ObjectNode source, Path root) {
        ObjectNode freeze = source.deepCopy().put("physical_root", root.toString());
        reseal(freeze);
        return freeze;
    }

    private static void addAttempt(ObjectNode replay, String candidate, Number stage, String attemptId) {
        ObjectNode row = replay.putArray("stage_attempts").addObject().put("candidate_id", candidate);
        if (stage.doubleValue() == stage.intValue()) row.put("stage", stage.intValue());
        else row.put("stage", stage.doubleValue());
        row.put("attempt_id", attemptId);
    }

    private static void resealLedger(ObjectNode replay) {
        ObjectNode ledger = (ObjectNode) replay.path("ledger");
        reseal(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
    }

    private static void assertSetupAbort(Path root, ObjectNode freeze) throws Exception {
        Path eventsDirectory = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                freeze.path("precommit").path("precommit_id").asText("liquidation-daily-stress-v002"),
                freeze.path("precommit_sha256").asText(), freeze.path("profile_sha256").asText(),
                freeze.path("manifest_sha256").asText(), Path.of(freeze.path("physical_root").asText()).toRealPath())
                .ledgerDirectory().resolve("events");
        assertTrue(Files.isDirectory(eventsDirectory), "the isolated synthetic root must own its durable event chain");
        List<ObjectNode> events;
        try (var stream = Files.list(eventsDirectory)) {
            events = stream.filter(path -> path.getFileName().toString().matches("[0-9]{8}\\.json"))
                    .sorted().map(path -> readUnchecked(path)).toList();
        }
        List<ObjectNode> reservations = events.stream().filter(row -> "RESERVATION".equals(row.path("event_type").asText())).toList();
        List<ObjectNode> aborts = events.stream().filter(row -> "ABORT".equals(row.path("event_type").asText())).toList();
        assertEquals(1, reservations.size());
        assertEquals(1, aborts.size());
        assertEquals("INITIAL_PHYSICAL_OPEN_AND_ENGINE_SETUP", aborts.get(0).path("segment_id").asText());
        assertEquals(SETUP_RESERVATION_MS, aborts.get(0).path("reserved_millis").asLong());
        assertEquals(SETUP_RESERVATION_MS, aborts.get(0).path("charged_millis").asLong());
        assertTrue(events.get(events.size() - 1).path("consumed_compute_millis").asLong() >= SETUP_RESERVATION_MS);
    }

    private static ObjectNode readUnchecked(Path path) {
        try { return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path)); }
        catch (Exception error) { throw new AssertionError("cannot read durable event " + path, error); }
    }

    private static void reseal(ObjectNode node) {
        node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node));
    }

    private record Mutation(String label, Consumer<ObjectNode> apply, String message,
            boolean resealOuter, boolean resealLedger) {
        Mutation(String label, Consumer<ObjectNode> apply, String message) { this(label, apply, message, true, false); }
        Mutation(String label, Consumer<ObjectNode> apply, String message, boolean resealOuter, boolean resealLedger) {
            this.label = label; this.apply = apply; this.message = message;
            this.resealOuter = resealOuter; this.resealLedger = resealLedger;
        }
    }
}
