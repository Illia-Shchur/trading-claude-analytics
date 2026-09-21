package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Existing target objects are checked without following links or replacing caller data. */
class LiquidationCheckpointTargetCustodyBoundaryV1Test {
    @TempDir Path temporary;

    @Test
    void runBindingRejectsExistingSymlinkAndDirectoryTargets() throws Exception {
        Path root = temporary.toRealPath();
        Path physical = Files.createDirectories(root.resolve("physical"));
        Path project = Files.createDirectories(root.resolve("project"));
        Path external = Files.writeString(root.resolve("external.json"), "protected");

        Path linkedOutput = physical.resolve("result.json");
        Files.createSymbolicLink(linkedOutput, external);
        IllegalArgumentException symlink = assertThrows(IllegalArgumentException.class,
                () -> binding(project, physical, linkedOutput, physical.resolve("checkpoint.json")));
        assertTrue(symlink.getMessage().contains("symlink"));
        assertTrue(Files.readString(external).equals("protected"), "rejection must not follow or mutate the target");

        Path directoryTarget = Files.createDirectory(physical.resolve("checkpoint-directory"));
        IllegalArgumentException directory = assertThrows(IllegalArgumentException.class,
                () -> binding(project, physical, physical.resolve("result-ok.json"), directoryTarget));
        assertTrue(directory.getMessage().contains("symlink or non-file"));
    }

    private static LiquidationV2ReplayCheckpointV1.RunBinding binding(Path project, Path physical,
            Path output, Path checkpoint) {
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze"), sha("request"), project, physical,
                sha("manifest"), sha("executor"), output, checkpoint);
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
}
