package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public, temp-scoped check that failed bounded work consumes the one durable family budget. */
class LiquidationPortfolioReplayBudgetIntegrationMatrixV1Test {
    private static final long TWO_HOURS_MILLIS = 2L * 60L * 60L * 1_000L;
    private static final long FAMILY_BUDGET_MILLIS = 24L * 60L * 60L * 1_000L;

    @TempDir Path temporary;

    @Test
    void failedPublicInitializationDebitsFullReservationsAcrossChangedTargetsAndStopsAtFamilyLimit() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("bounded-budget-family"));
        Path outputs = Files.createDirectories(root.resolve("outputs"));
        ObjectNode freeze = LiquidationStagedPortfolioReplayV1Test.buildFreeze(root);
        ObjectNode invalidRequest = LiquidationStagedPortfolioReplayV1Test.replayRequest()
                .put("feature_warmup_start", "2023-12-01T00:00:00Z");
        String runId = freeze.path("precommit").path("precommit_id").asText();
        Path eventsDir = root.resolve(".liquidation-v2-replay-budget-v1")
                .resolve(JsonHashes.sha256(runId)).resolve("events");
        String familyScope = null;
        Set<String> runBindings = new HashSet<>();

        for (int attempt = 1; attempt <= 12; attempt++) {
            Path replayPath = outputs.resolve("invalid-attempt-" + attempt + ".json");
            ObjectNode options = runOptions(freeze, invalidRequest, replayPath);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationPortfolioReplayV1.runResumable(options));
            assertTrue(failure.getMessage().contains("synthetic smoke needs UTC-day-aligned feature warmup"),
                    failure::getMessage);
            assertFalse(Files.exists(replayPath), "failed initialization cannot publish economic output");

            JsonNode[] events = readEvents(eventsDir);
            long consumed = 0;
            int starts = 0;
            int aborts = 0;
            for (int eventIndex = 0; eventIndex < events.length; eventIndex++) {
                JsonNode event = events[eventIndex];
                assertEquals(eventIndex, event.path("sequence").asInt(),
                        "the durable event chain remains contiguous while the family ledger grows");
                if (familyScope == null) familyScope = event.path("scope_sha256").asText();
                assertEquals(familyScope, event.path("scope_sha256").asText());
                if ("RUN_START".equals(event.path("event_type").asText())) {
                    starts++;
                    runBindings.add(event.path("binding_sha256").asText());
                }
                if ("ABORT".equals(event.path("event_type").asText())) {
                    aborts++;
                    consumed = event.path("consumed_compute_millis").asLong();
                }
            }
            assertEquals(attempt, starts, "each output-bound retry opens one run in the same family scope");
            assertEquals(attempt, aborts, "each failed initialization closes its reservation with an abort debit");
            assertEquals(attempt, runBindings.size(), "output target is bound to each run, not to family budget scope");
            assertEquals(attempt * TWO_HOURS_MILLIS, consumed,
                    "failed bounded initialization consumes its full two-hour reservation");
        }

        Path finalReplayPath = outputs.resolve("valid-after-budget-exhaustion.json");
        ObjectNode finalReceipt = LiquidationPortfolioReplayV1.runResumable(
                runOptions(freeze, LiquidationStagedPortfolioReplayV1Test.replayRequest(), finalReplayPath));
        assertEquals(LiquidationV2ReplayCheckpointV1.COMPUTE_INCOMPLETE, finalReceipt.path("status").asText());
        assertEquals(FAMILY_BUDGET_MILLIS, finalReceipt.path("budget_session").path("consumed_compute_millis").asLong());
        assertEquals(0, finalReceipt.path("budget_session").path("remaining_compute_millis").asLong());
        assertEquals(0, finalReceipt.path("budget_session").path("reserved_compute_millis").asLong());
        assertEquals(JsonHashes.ownHash(finalReceipt), finalReceipt.path("content_sha256").asText());
        assertTrue(finalReceipt.path("replay_output_path").isNull());
        assertFalse(Files.exists(finalReplayPath), "budget exhaustion must not publish a replay artifact");
        assertFalse(Files.exists(Path.of(finalReceipt.path("checkpoint_path").asText())),
                "a run that cannot reserve setup time has no durable economic prefix checkpoint");
        String receiptPath = finalReceipt.path("operational_receipt_path").asText();
        assertNotNull(receiptPath);
        ObjectNode persistedReceipt = readObject(Path.of(receiptPath));
        assertEquals(JsonHashes.canonicalSha256(finalReceipt), JsonHashes.canonicalSha256(persistedReceipt));
    }

    private static ObjectNode runOptions(ObjectNode freeze, ObjectNode request, Path replayPath) {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", replayPath.toString());
        options.set("freeze", freeze.deepCopy());
        options.set("replay_request", request.deepCopy());
        return options;
    }

    private static JsonNode[] readEvents(Path eventsDir) throws Exception {
        try (var paths = Files.list(eventsDir)) {
            return paths.filter(path -> path.getFileName().toString().matches("[0-9]{8}\\.json"))
                    .sorted().map(path -> {
                try {
                    return JsonHashes.mapper().readTree(Files.readAllBytes(path));
                } catch (Exception error) {
                    throw new IllegalStateException("cannot read durable budget event", error);
                }
            }).toArray(JsonNode[]::new);
        }
    }

    private static ObjectNode readObject(Path path) throws Exception {
        JsonNode node = JsonHashes.mapper().readTree(Files.readAllBytes(path));
        assertTrue(node instanceof ObjectNode, "operational receipt must be a JSON object");
        return (ObjectNode) node;
    }
}
