package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public resumable-run stop boundary behavior over isolated tiny synthetic inputs. */
class LiquidationPortfolioReplayStopBoundaryMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void initialWarmupBoundaryReturnsOperationalResumeReceiptWithoutPublishingEconomicOutput() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        ObjectNode sourceFreeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(source);
        Path runRoot = cloneFreeze(sourceFreeze, "initial-boundary");
        Path replay = runRoot.resolve("replay.json"), checkpoint = runRoot.resolve("checkpoint.json"), operational = runRoot.resolve("operational.json");
        ObjectNode receipt = LiquidationPortfolioReplayV1.runResumable(options(runRoot, sourceFreeze,
                "2023-10-01T00:00:00Z", replay, checkpoint, operational));

        assertEquals("RESUMABLE", receipt.path("status").asText());
        assertEquals(JsonHashes.ownHash(receipt), receipt.path("content_sha256").asText());
        assertEquals(checkpoint.toRealPath().toString(), Path.of(receipt.path("operational_checkpoint_path").asText()).toRealPath().toString());
        assertTrue(Files.isRegularFile(checkpoint));
        Path receiptPath = Path.of(receipt.path("operational_receipt_path").asText());
        assertTrue(Files.isRegularFile(receiptPath));
        JsonNode persistedReceipt = JsonHashes.mapper().readTree(Files.readAllBytes(receiptPath));
        assertEquals(JsonHashes.canonicalSha256(receipt), JsonHashes.canonicalSha256(persistedReceipt));
        assertTrue(receipt.path("replay_output_path").isNull());
        assertFalse(Files.exists(replay), "a prefix receipt must not be presented as completed economic output");
        ObjectNode persistedCheckpoint = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(checkpoint));
        assertEquals(JsonHashes.ownHash(persistedCheckpoint), persistedCheckpoint.path("content_sha256").asText());
    }

    @Test
    void invalidStopBoundariesFailAfterSetupWithoutPublishingReplay(@TempDir Path cases) throws Exception {
        Path source = Files.createDirectories(cases.resolve("source"));
        ObjectNode sourceFreeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(source);
        for (String stop : new String[] {
                "2023-10-01T12:00:00Z", // not a UTC day boundary
                "2023-09-30T00:00:00Z", // before the reconstructed initial prefix
                "2024-01-05T00:00:00Z"  // after this request's execution end
        }) {
            Path runRoot = cloneFreeze(sourceFreeze, "invalid-" + stop.replace(':', '-'));
            Path replay = runRoot.resolve("must-not-exist.json"), checkpoint = runRoot.resolve("checkpoint.json");
            var failure = assertThrows(IllegalArgumentException.class, () -> LiquidationPortfolioReplayV1.runResumable(
                    options(runRoot, sourceFreeze, stop, replay, checkpoint, runRoot.resolve("operational.json"))));
            assertEquals("stop_after_boundary_exclusive must be a UTC midnight between the saved prefix and execution end",
                    failure.getMessage());
            assertFalse(Files.exists(replay), "invalid stop boundary cannot publish economic output: " + stop);
            assertTrue(Files.isRegularFile(checkpoint), "the already-verified setup prefix remains durable: " + stop);
        }
    }

    private ObjectNode options(Path runRoot, ObjectNode sourceFreeze, String stop,
            Path replay, Path checkpoint, Path operational) throws Exception {
        ObjectNode freeze = sourceFreeze.deepCopy();
        freeze.put("physical_root", runRoot.toString());
        freeze.put("content_sha256", JsonHashes.ownHash(freeze));
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                .put("execution_end_exclusive", "2024-01-04T00:00:00Z");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", replay.toString())
                .put("checkpoint_out", checkpoint.toString()).put("operational_out", operational.toString())
                .put("stop_after_boundary_exclusive", stop);
        options.set("freeze", freeze);
        options.set("replay_request", request);
        return options;
    }

    private Path cloneFreeze(ObjectNode sourceFreeze, String leaf) throws Exception {
        Path original = Path.of(sourceFreeze.path("physical_root").asText());
        Path target = temporary.resolve(leaf);
        LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.cloneTree(original, target);
        return target;
    }
}
