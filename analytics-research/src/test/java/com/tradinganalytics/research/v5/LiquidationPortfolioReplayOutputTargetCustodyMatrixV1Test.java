package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public replay target custody guards, evaluated before any run reservation or publication. */
class LiquidationPortfolioReplayOutputTargetCustodyMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void everyPublishedOutputTargetMustStayBelowTheFrozenPhysicalRoot() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("physical"));
        ObjectNode freeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(root);
        Path outside = Files.createDirectories(temporary.resolve("outside"));

        assertRejected(options(freeze, outside.resolve("outside-replay.json")),
                "replay output must remain under the frozen physical root");
        assertRejected(options(freeze, temporary.resolve("missing-parent").resolve("replay.json")),
                "replay output must remain under the frozen physical root");
        assertRejected(options(freeze, root),
                "replay output must remain below the frozen physical root");

        Path link = root.resolve("outside-link");
        Files.createSymbolicLink(link, outside);
        assertRejected(options(freeze, link.resolve("linked-replay.json")),
                "replay output path must not traverse symlinks");

        ObjectNode evidence = options(freeze, root.resolve("valid-replay.json"));
        evidence.put("evidence_out", outside.resolve("outside-evidence.json").toString());
        assertRejected(evidence, "evidence output must remain under the frozen physical root");

        ObjectNode checkpoint = options(freeze, root.resolve("valid-replay.json"));
        checkpoint.put("checkpoint_out", outside.resolve("outside-checkpoint.json").toString());
        assertRejected(checkpoint, "checkpoint output must remain under the frozen physical root");

        ObjectNode operational = options(freeze, root.resolve("valid-replay.json"));
        operational.put("checkpoint_out", root.resolve("valid-checkpoint.json").toString())
                .put("operational_out", outside.resolve("outside-operational.json").toString());
        assertRejected(operational, "operational receipt must remain under the frozen physical root");
    }

    @Test
    void canonicalPhysicalRootAliasStillRejectsTheRootItselfAsAnOutputTarget() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("physical-alias-target"));
        ObjectNode freeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(root);
        Path alias = temporary.resolve("frozen-root-alias");
        Files.createSymbolicLink(alias, root);
        ObjectNode aliasFreeze = freeze.deepCopy();
        aliasFreeze.put("physical_root", alias.toString());
        aliasFreeze.put("content_sha256", JsonHashes.ownHash(aliasFreeze));

        // The frozen path is a permitted OS alias. Supplying the resolved physical root
        // exercises the canonical-root branch and must still refuse the root as a file.
        assertRejected(options(aliasFreeze, root.toRealPath()),
                "replay output must remain below the frozen physical root");
    }

    private static ObjectNode options(ObjectNode freeze, Path output) {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", output.toString());
        options.set("freeze", freeze.deepCopy());
        options.set("replay_request", JsonHashes.mapper().createObjectNode());
        return options;
    }

    private static void assertRejected(ObjectNode options, String expectedMessage) {
        Path replayOutput = Path.of(options.path("out").asText());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(options), expectedMessage);
        assertEquals(expectedMessage, error.getMessage());
        if (!Files.isDirectory(replayOutput)) {
            assertFalse(Files.exists(replayOutput), "a rejected target must not receive economic output");
        }
    }
}
