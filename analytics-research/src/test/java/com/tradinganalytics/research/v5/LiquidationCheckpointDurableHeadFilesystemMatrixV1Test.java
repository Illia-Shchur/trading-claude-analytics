package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Durable-head reset/truncation refusal and the one-event genesis/head crash recovery. */
class LiquidationCheckpointDurableHeadFilesystemMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void missingHeadAfterCommittedRunAndUnsafeHeadTargetsFailClosed() throws Exception {
        Fixture missing = fixture("missing-head");
        Files.delete(missing.head());
        assertFailure(missing, "durable budget chain head is missing or unsafe; refusing truncation/reset");

        Fixture linked = fixture("symlink-head");
        Path saved = linked.head().resolveSibling("saved-chain-head.json");
        byte[] sentinel = Files.readAllBytes(linked.head());
        Files.move(linked.head(), saved);
        Files.createSymbolicLink(linked.head(), saved);
        assertFailure(linked, "durable budget chain head is missing or unsafe; refusing truncation/reset");
        assertArrayEquals(sentinel, Files.readAllBytes(saved),
                "rejecting a symlinked high-water head must leave the target bytes untouched");

        Fixture directory = fixture("directory-head");
        Files.delete(directory.head());
        Files.createDirectory(directory.head());
        assertFailure(directory, "durable budget chain head is missing or unsafe; refusing truncation/reset");
    }

    @Test
    void exactGenesisOnlyCrashRepairsMissingHeadButEmptyChainWithHeadCannotReset() throws Exception {
        Fixture genesisOnly = fixture("genesis-only");
        Files.delete(genesisOnly.events().resolve("00000001.json"));
        Files.delete(genesisOnly.head());
        assertDoesNotThrow(() -> {
            try (var recovered = LiquidationV2ReplayCheckpointV1.openNewRun(
                    genesisOnly.scope(), genesisOnly.binding(), () -> 2_000_000L)) {
                assertTrue(Files.isRegularFile(genesisOnly.head()));
                assertTrue(recovered.remainingComputeMillis() > 0);
            }
        }, "a receipt-validated single genesis event can safely restore its lost high-water head");

        Fixture emptyWithHead = fixture("empty-events-with-head");
        try (var files = Files.list(emptyWithHead.events())) {
            for (Path event : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                Files.delete(event);
            }
        }
        Files.delete(emptyWithHead.scope().ledgerDirectory().resolve("chain-genesis-init.json"));
        assertFailure(emptyWithHead, "budget chain head exists without event files; refusing reset");
    }

    private Fixture fixture(String id) throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve(id));
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        var scope = LiquidationV2ReplayCheckpointV1.FamilyScope.of("durable-head-" + id,
                sha("precommit"), sha("profile"), sha("manifest"), root);
        var binding = LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze"), sha("request"), project,
                physical, sha("manifest"), sha("executor"), physical.resolve("replay.json"),
                physical.resolve("checkpoint.json"));
        try (var ignored = LiquidationV2ReplayCheckpointV1.openNewRun(scope, binding, () -> 1_000_000L)) { }
        Path ledger = scope.ledgerDirectory();
        return new Fixture(scope, binding, ledger.resolve("events"), ledger.resolve("chain-head.json"));
    }

    private static void assertFailure(Fixture fixture, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.openNewRun(fixture.scope(), fixture.binding(), () -> 2_000_000L));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
    private record Fixture(LiquidationV2ReplayCheckpointV1.FamilyScope scope,
            LiquidationV2ReplayCheckpointV1.RunBinding binding, Path events, Path head) {}
}
