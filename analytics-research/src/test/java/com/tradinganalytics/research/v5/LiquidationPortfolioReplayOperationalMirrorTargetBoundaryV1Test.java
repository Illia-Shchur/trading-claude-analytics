package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Operational checkpoint mirrors refuse an existing non-file after the setup lease is opened. */
class LiquidationPortfolioReplayOperationalMirrorTargetBoundaryV1Test {
    @TempDir Path temporary;

    @Test
    void existingDirectoryCannotBeReplacedByTheOperationalCheckpointMirror() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("physical"));
        ObjectNode freeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(root);
        Path replay = root.resolve("economic-replay.json");
        Path checkpointDirectory = Files.createDirectory(root.resolve("checkpoint-target"));
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                .put("execution_end_exclusive", "2024-01-04T00:00:00Z");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", replay.toString())
                .put("checkpoint_out", checkpointDirectory.toString())
                .put("operational_out", root.resolve("operational-receipt.json").toString())
                .put("stop_after_boundary_exclusive", "2023-10-01T00:00:00Z");
        options.set("freeze", freeze);
        options.set("replay_request", request);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(options));

        assertEquals("checkpoint_target must not be a symlink or non-file", failure.getMessage());
        assertTrue(Files.isDirectory(checkpointDirectory));
        try (var children = Files.list(checkpointDirectory)) {
            assertEquals(0, children.count(), "refusing the unsafe mirror must preserve the existing directory");
        }
        assertFalse(Files.exists(replay), "an operational mirror error cannot publish economic output");
        assertFalse(Files.exists(root.resolve("operational-receipt.json.receipt.json")));
    }
}
