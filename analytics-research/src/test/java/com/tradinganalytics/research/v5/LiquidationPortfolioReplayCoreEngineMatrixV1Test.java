package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Short public-engine integration over the same causal synthetic fixture used by the staged replay. */
class LiquidationPortfolioReplayCoreEngineMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void publicRunAndEvaluateProcessesShortBoundedEngineWindowAndPersistsHashBoundOutputs() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("short-core-engine"));
        ObjectNode freeze = LiquidationStagedPortfolioReplayV1Test.buildFreeze(root);
        ObjectNode request = LiquidationStagedPortfolioReplayV1Test.replayRequest();
        request.put("execution_end_exclusive", "2024-01-05T00:00:00Z");
        ObjectNode options = LiquidationStagedPortfolioReplayV1Test.runAndEvaluateOptions(
                freeze, request, "short-core-replay.json", "short-core-evidence.json");

        ObjectNode receipt = LiquidationPortfolioReplayV1.runAndEvaluate(options);

        assertEquals("liquidation-v2-run-evaluate-receipt/1", receipt.path("schema").asText());
        assertTrue(receipt.path("single_runner_pass").asBoolean());
        assertFalse(receipt.path("authoritative").asBoolean(true));
        ObjectNode replay = readObject(root.resolve("short-core-replay.json"));
        ObjectNode evidence = readObject(root.resolve("short-core-evidence.json"));
        assertEquals(evidence.path("status").asText(), receipt.path("status").asText());
        assertEquals(JsonHashes.ownHash(replay), replay.path("content_sha256").asText());
        assertEquals(JsonHashes.ownHash(evidence), evidence.path("content_sha256").asText());
        assertEquals(replay.path("content_sha256").asText(), receipt.path("replay_sha256").asText());
        assertEquals(evidence.path("content_sha256").asText(), receipt.path("evidence_sha256").asText());
        assertFalse(evidence.path("promotion_permitted").asBoolean(true));
        assertEquals("2024-01-05T00:00:00Z",
                replay.path("execution_request").path("execution_end_exclusive").asText());
        assertTrue(replay.path("opportunities").isArray());
        assertEquals(3, replay.path("opportunities").size(),
                "one routed anchor and its two paired control arms share one decision pair");
        Set<String> pairs = new HashSet<>();
        Map<String, String> states = new HashMap<>();
        for (JsonNode opportunity : replay.path("opportunities")) {
            pairs.add(opportunity.path("pair_id").asText());
            states.put(opportunity.path("candidate_id").asText(), opportunity.path("outcome_state").asText());
            assertTrue(opportunity.path("outcome_state").isTextual());
        }
        assertEquals(1, pairs.size());
        assertEquals(Map.of(
                "liquidation-v2-core-routed-one-entry", "OPEN_UNRESOLVED",
                "liquidation-v2-core-always-continuation-one-entry", "OPEN_UNRESOLVED",
                "liquidation-v2-core-always-reversal-one-entry", "CLOSED_TRADE"), states);
        assertTrue(replay.path("ledger").path("accounts").isArray());
        assertEquals(3, replay.path("ledger").path("accounts").size());
        assertTrue(replay.path("account_curve").isArray());
        assertTrue(Instant.parse(replay.path("execution_request").path("execution_end_exclusive").asText())
                .isAfter(Instant.parse(replay.path("execution_request").path("decision_end_exclusive").asText())));
    }

    private static ObjectNode readObject(Path path) throws Exception {
        JsonNode node = JsonHashes.mapper().readTree(Files.readAllBytes(path));
        assertTrue(node instanceof ObjectNode, "runner artifact must be a JSON object: " + path.getFileName());
        return (ObjectNode) node;
    }
}
