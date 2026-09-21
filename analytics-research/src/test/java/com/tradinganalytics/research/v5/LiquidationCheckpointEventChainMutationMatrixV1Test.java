package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

/** Mutates resealed chain members so loader tests reach semantic validators, not just byte hashes. */
class LiquidationCheckpointEventChainMutationMatrixV1Test {
    private static final String HEAD_SCHEMA = "liquidation-v2-replay-budget-chain-head/1";
    @TempDir Path temporary;
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);

    @Test
    void acceptedRunOpensAndResealedEventEnvelopeMutationsAreRejected() throws Exception {
        Fixture positive = fixture("event-positive");
        assertDoesNotThrow(() -> {
            try (LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                    positive.scope(), positive.binding(), clock::get)) {
                assertTrue(session.toJson().path("remaining_compute_millis").asLong() > 0);
            }
        });

        assertBadRunEvent("event-schema", event -> event.put("schema", "liquidation-v2-replay-budget-event/0"),
                "budget event schema, sequence, or family scope is invalid");
        assertBadRunEvent("event-sequence", event -> event.put("sequence", 7),
                "budget event schema, sequence, or family scope is invalid");
        assertBadRunEvent("event-scope", event -> event.put("scope_sha256", sha("other-family")),
                "budget event schema, sequence, or family scope is invalid");
        assertBadRunEvent("event-predecessor", event -> event.put("previous_event_sha256", sha("wrong-genesis")),
                "budget event predecessor hash mismatch");
        assertBadRunEvent("event-type", event -> event.put("event_type", "UNKNOWN_EVENT"),
                "unsupported budget event type: UNKNOWN_EVENT");
        assertBadRunEvent("genesis-after-zero", event -> event.put("event_type", "GENESIS"),
                "budget genesis appears after event zero");

        Fixture unsealed = fixture("unsealed-event");
        Path event = eventPath(unsealed, 1);
        ObjectNode bytes = readObject(event); bytes.put("event_sha256", sha("stale-hash")); writeCanonical(event, bytes);
        failOpen(unsealed, "budget event hash mismatch at 00000001.json");
    }

    @Test
    void runStartBindingAndCumulativeClockFieldsAreValidatedIndependently() throws Exception {
        assertBadRunEvent("missing-run-binding", event -> event.remove("run_binding"),
                "budget event run_binding must be an object");
        assertBadRunEvent("bad-run-binding-sha", event -> event.put("binding_sha256", sha("different")),
                "run start binding or cumulative compute is invalid");
        assertBadRunEvent("bad-run-start-consumed", event -> event.put("consumed_compute_millis", 1),
                "run start binding or cumulative compute is invalid");

        Fixture invalidHash = fixture("invalid-freeze-hash");
        editChain(invalidHash, (index, event) -> {
            if (index != 1) return;
            ObjectNode binding = (ObjectNode) event.path("run_binding");
            binding.put("freeze_sha256", "not-a-hash");
            binding.put("binding_sha256", JsonHashes.ownHash(binding, "binding_sha256"));
            event.put("binding_sha256", binding.path("binding_sha256").asText());
        });
        failOpen(invalidHash, "freeze_sha256 must be a lowercase SHA-256");
    }

    @Test
    void reservationsMustBindOneActiveRunAndAValidPositiveCumulativeLease() throws Exception {
        assertBadReservation("blank-segment", event -> event.put("segment_id", ""),
                "segment reservation duration or cumulative compute is invalid");
        assertBadReservation("zero-duration", event -> event.put("reserved_millis", 0),
                "segment reservation duration or cumulative compute is invalid");
        assertBadReservation("over-budget", event -> event.put("reserved_millis",
                LiquidationV2ReplayCheckpointV1.COMPUTE_BUDGET_MILLIS + 1),
                "segment reservation duration or cumulative compute is invalid");
        assertBadReservation("bad-reservation-run", event -> event.put("run_start_event_sha256", sha("other-run")),
                "segment reservation has no active run or duplicates an open reservation");
        assertBadReservation("bad-reservation-binding", event -> event.put("binding_sha256", sha("other-binding")),
                "segment reservation has no active run or duplicates an open reservation");
        assertBadReservation("bad-reservation-clock", event -> event.put("consumed_compute_millis", 1),
                "segment reservation duration or cumulative compute is invalid");
    }

    @Test
    void checkpointCompletionValidatesReservationElapsedAndCanonicalCheckpointPayload() throws Exception {
        assertBadCheckpoint("wrong-reservation", event -> event.put("reservation_event_sha256", sha("wrong-reservation")),
                "checkpoint does not complete the current reservation");
        assertBadCheckpoint("elapsed-over-reservation", event -> event.put("measured_elapsed_millis", 1_001),
                "checkpoint elapsed compute is invalid");
        assertBadCheckpoint("cumulative-mismatch", event -> event.put("consumed_compute_millis", 1),
                "checkpoint elapsed compute is invalid");
        assertBadCheckpoint("missing-payload", event -> event.remove("checkpoint"),
                "checkpoint schema is missing or unsupported");
        assertBadCheckpoint("bad-payload-schema", event -> ((ObjectNode) event.path("checkpoint")).put("schema", "old"),
                "checkpoint schema is missing or unsupported");
        assertBadCheckpoint("bad-payload-boundary", event -> ((ObjectNode) event.path("checkpoint")).put("boundary_exclusive", "bad-time"),
                "checkpoint fields are invalid");
    }

    @Test
    void orderlyAbortAndRecoveryDebitCannotUnderstateReservationOrCumulativeSpend() throws Exception {
        assertBadAbort("abort-undercharge", event -> event.put("charged_millis", 99),
                "recovery debit/abort duration or cumulative compute is invalid");
        assertBadAbort("abort-understates-observed", event -> event.put("observed_elapsed_millis", 101),
                "recovery debit/abort duration or cumulative compute is invalid");
        assertBadAbort("abort-cumulative", event -> event.put("consumed_compute_millis", 101),
                "recovery debit/abort duration or cumulative compute is invalid");

        Fixture recovery = fixture("recovery-debit");
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                recovery.scope(), recovery.binding(), clock::get);
        session.reserveSegment("interrupted", 250L);
        try (LiquidationV2ReplayCheckpointV1.Session ignored = LiquidationV2ReplayCheckpointV1.openNewRun(
                recovery.scope(), recovery.binding("candidate-b"), clock::get)) {
            // The second open records a recovery debit and starts a new run.
        }
        editChain(recovery, (index, event) -> {
            if (index == 4) event.put("consumed_compute_millis", 249);
        });
        failOpen(recovery, "recovery debit/abort duration or cumulative compute is invalid");
        assertTrue(session.toJson().isObject());
    }

    @Test
    void durableHeadAndGenesisReceiptRejectResealedScopeHashSequenceAndExactGenesisDrift() throws Exception {
        assertBadHead("head-schema", head -> head.put("schema", "old"), "durable budget chain high-water head is invalid");
        assertBadHead("head-scope", head -> head.put("scope_sha256", sha("other")), "durable budget chain high-water head is invalid");
        assertBadHead("head-seal", head -> head.put("head_sha256", sha("other")), "durable budget chain high-water head is invalid");
        assertBadHead("head-tail", head -> head.put("last_event_sha256", sha("other")), "durable budget chain high-water head does not match the immutable event tail");
        assertBadHead("head-sequence", head -> head.put("last_sequence", 4), "durable budget chain high-water head does not match the immutable event tail");

        assertBadGenesisReceipt("receipt-schema", receipt -> receipt.put("schema", "old"),
                "immutable budget genesis initialization receipt is invalid");
        assertBadGenesisReceipt("receipt-scope", receipt -> receipt.put("scope_sha256", sha("other")),
                "immutable budget genesis initialization receipt is invalid");
        assertBadGenesisReceipt("receipt-payload", receipt -> receipt.set("genesis_event", JsonHashes.mapper().getNodeFactory().textNode("bad")),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-event-sha", receipt -> receipt.put("genesis_event_sha256", sha("other")),
                "immutable budget genesis receipt does not bind the exact chain genesis");

        Fixture badGenesis = fixture("genesis-consumed");
        editChain(badGenesis, (index, event) -> {
            if (index == 0) event.put("consumed_compute_millis", 1);
        });
        failOpen(badGenesis, "budget family genesis is missing or mismatched");
    }

    @Test
    void genesisReceiptReopensCanonicalBytesAndBindsEachNestedGenesisField() throws Exception {
        assertBadGenesisReceipt("receipt-genesis-schema", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("schema", "old"),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-genesis-sequence", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("sequence", 1),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-genesis-type", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("event_type", "RUN_START"),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-genesis-predecessor", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("previous_event_sha256", sha("prior")),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-genesis-scope", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("scope_sha256", sha("other-scope")),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-genesis-consumed", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("consumed_compute_millis", 1),
                "immutable budget genesis receipt does not bind the exact chain genesis");
        assertBadGenesisReceipt("receipt-genesis-event-hash", receipt ->
                ((ObjectNode) receipt.path("genesis_event")).put("event_sha256", sha("wrong-event-hash")),
                "immutable budget genesis receipt does not bind the exact chain genesis");

        Fixture missing = fixture("receipt-missing");
        Files.delete(missing.scope().ledgerDirectory().resolve("chain-genesis-init.json"));
        failOpen(missing, "immutable budget genesis initialization receipt is missing or unsafe");

        Fixture nonCanonical = fixture("receipt-noncanonical");
        Path receiptPath = nonCanonical.scope().ledgerDirectory().resolve("chain-genesis-init.json");
        ObjectNode receipt = readObject(receiptPath);
        Files.writeString(receiptPath, " " + JsonHashes.canonicalString(receipt));
        failOpen(nonCanonical, "immutable budget genesis initialization receipt is invalid");
    }

    @Test
    void genesisEventChecksEventTypePreviousLinkAndFamilyScopeBody() throws Exception {
        assertBadGenesisEvent("genesis-type", (index, event) -> {
            if (index == 0) event.put("event_type", "NOT_GENESIS");
        });
        assertBadGenesisEvent("genesis-predecessor", (index, event) -> {
            if (index == 0) event.put("previous_event_sha256", sha("not-null"));
        });
        assertBadGenesisEvent("genesis-scope-body", (index, event) -> {
            if (index == 0) ((ObjectNode) event.path("family_scope")).put("research_run_id", "other");
        });
    }

    private void assertBadRunEvent(String label, Consumer<ObjectNode> mutation, String message) throws Exception {
        Fixture fixture = fixture(label);
        editChain(fixture, (index, event) -> { if (index == 1) mutation.accept(event); });
        failOpen(fixture, message);
    }

    private void assertBadReservation(String label, Consumer<ObjectNode> mutation, String message) throws Exception {
        Fixture fixture = fixture(label);
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                fixture.scope(), fixture.binding(), clock::get);
        session.reserveSegment("lease", 1_000L);
        // The second run start follows the fixture's genesis run; its reservation is sequence 3.
        editChain(fixture, (index, event) -> { if (index == 3) mutation.accept(event); });
        failOpen(fixture, message);
    }

    private void assertBadCheckpoint(String label, Consumer<ObjectNode> mutation, String message) throws Exception {
        Fixture fixture = fixture(label);
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                fixture.scope(), fixture.binding(), clock::get);
        session.reserveSegment("lease", 1_000L).checkpoint(Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
        editChain(fixture, (index, event) -> { if (index == 4) mutation.accept(event); });
        failOpen(fixture, message);
    }

    private void assertBadAbort(String label, Consumer<ObjectNode> mutation, String message) throws Exception {
        Fixture fixture = fixture(label);
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                fixture.scope(), fixture.binding(), clock::get);
        session.reserveSegment("lease", 100L).abort();
        editChain(fixture, (index, event) -> { if (index == 4) mutation.accept(event); });
        failOpen(fixture, message);
    }

    private void assertBadHead(String label, Consumer<ObjectNode> mutation, String message) throws Exception {
        Fixture fixture = fixture(label);
        Path path = fixture.scope().ledgerDirectory().resolve("chain-head.json");
        ObjectNode head = readObject(path); mutation.accept(head);
        if (!"head-seal".equals(label)) head.put("head_sha256", JsonHashes.ownHash(head, "head_sha256"));
        writeCanonical(path, head); failOpen(fixture, message);
    }

    private void assertBadGenesisReceipt(String label, Consumer<ObjectNode> mutation, String message) throws Exception {
        Fixture fixture = fixture(label);
        Path path = fixture.scope().ledgerDirectory().resolve("chain-genesis-init.json");
        ObjectNode receipt = readObject(path); mutation.accept(receipt);
        receipt.put("init_receipt_sha256", JsonHashes.ownHash(receipt, "init_receipt_sha256"));
        writeCanonical(path, receipt); failOpen(fixture, message);
    }

    private void assertBadGenesisEvent(String label, ChainMutation mutation) throws Exception {
        Fixture fixture = fixture(label);
        editChain(fixture, mutation);
        failOpen(fixture, "budget family genesis is missing or mismatched");
    }

    private Fixture fixture(String id) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(id)).toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        LiquidationV2ReplayCheckpointV1.FamilyScope scope = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                "family-" + id, sha("precommit"), sha("profile"), sha("dataset"), root);
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding(project, physical, id, "candidate-a");
        try (LiquidationV2ReplayCheckpointV1.Session ignored = LiquidationV2ReplayCheckpointV1.openNewRun(
                scope, binding, clock::get)) { }
        return new Fixture(scope, binding, project, physical);
    }

    private LiquidationV2ReplayCheckpointV1.RunBinding binding(Path project, Path physical, String id, String candidate) {
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze-" + candidate), sha("request-" + candidate),
                project, physical, sha("manifest"), sha("executor"), physical.resolve(id + ".json"), physical.resolve(id + ".checkpoint.json"));
    }

    private void failOpen(Fixture fixture, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.openNewRun(fixture.scope(), fixture.binding(), clock::get));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
    }

    private void editChain(Fixture fixture, ChainMutation mutation) throws Exception {
        Path directory = fixture.scope().ledgerDirectory().resolve("events");
        String previous = null;
        int sequence = 0;
        while (Files.exists(directory.resolve(String.format(java.util.Locale.ROOT, "%08d.json", sequence)))) {
            Path path = directory.resolve(String.format(java.util.Locale.ROOT, "%08d.json", sequence));
            ObjectNode event = readObject(path);
            if (sequence == 0) event.putNull("previous_event_sha256");
            else event.put("previous_event_sha256", previous);
            mutation.edit(sequence, event);
            event.put("event_sha256", JsonHashes.ownHash(event, "event_sha256"));
            previous = event.path("event_sha256").asText();
            writeCanonical(path, event);
            sequence++;
        }
        Path headPath = fixture.scope().ledgerDirectory().resolve("chain-head.json");
        ObjectNode head = readObject(headPath).put("last_sequence", sequence - 1).put("last_event_sha256", previous);
        head.put("head_sha256", JsonHashes.ownHash(head, "head_sha256"));
        writeCanonical(headPath, head);
    }

    private static Path eventPath(Fixture fixture, int sequence) {
        return fixture.scope().ledgerDirectory().resolve("events/" + String.format(java.util.Locale.ROOT, "%08d.json", sequence));
    }

    private static ObjectNode readObject(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static void writeCanonical(Path path, ObjectNode value) throws Exception {
        Files.write(path, JsonHashes.canonicalBytes(value));
    }

    private static String sha(String text) { return JsonHashes.sha256(text); }
    @FunctionalInterface private interface ChainMutation { void edit(int index, ObjectNode event); }
    private record Fixture(LiquidationV2ReplayCheckpointV1.FamilyScope scope,
            LiquidationV2ReplayCheckpointV1.RunBinding originalBinding, Path project, Path physical) {
        LiquidationV2ReplayCheckpointV1.RunBinding binding() { return originalBinding; }
        LiquidationV2ReplayCheckpointV1.RunBinding binding(String candidate) {
            return LiquidationCheckpointEventChainMutationMatrixV1Test.thisBinding(project, physical, "unused", candidate);
        }
    }

    private static LiquidationV2ReplayCheckpointV1.RunBinding thisBinding(Path project, Path physical, String id, String candidate) {
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze-" + candidate), sha("request-" + candidate),
                project, physical, sha("manifest"), sha("executor"), physical.resolve(id + ".json"), physical.resolve(id + ".checkpoint.json"));
    }
}
