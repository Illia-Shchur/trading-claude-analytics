package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Legacy top-level request fields retain their documented defaulting in the public resumable runner. */
class LiquidationPortfolioReplayLegacyResumableRequestDefaultsV1Test {
    @TempDir Path temporary;

    @Test
    void omittedWarmupAndSeparateEndsUseFrozenWarmupAndLegacyEndFallback() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("physical"));
        ObjectNode freeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(root);
        String frozenSourceStart = freeze.path("plan").path("source_start").asText();
        Path replayOutput = root.resolve("legacy-resumable-replay.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", replayOutput.toString())
                .put("synthetic_smoke", true)
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("replay_end_exclusive", "2024-01-04T00:00:00Z")
                .put("stop_after_boundary_exclusive", frozenSourceStart);
        options.set("freeze", freeze);

        ObjectNode receipt = LiquidationPortfolioReplayV1.runResumable(options);

        assertEquals("RESUMABLE", receipt.path("status").asText());
        assertEquals(JsonHashes.ownHash(receipt), receipt.path("content_sha256").asText());
        assertEquals(root.resolve("legacy-resumable-replay.json.checkpoint.json").toRealPath().toString(),
                Path.of(receipt.path("operational_checkpoint_path").asText()).toRealPath().toString());
        assertTrue(Files.isRegularFile(root.resolve("legacy-resumable-replay.json.checkpoint.json")));
        assertTrue(Files.isRegularFile(root.resolve("legacy-resumable-replay.json.checkpoint.json.receipt.json")));
        assertTrue(receipt.path("replay_output_path").isNull());
        assertFalse(Files.exists(replayOutput), "an initial-prefix receipt must not publish a replay result");
    }
}
