package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local qualification inputs are opened as bounded non-symlink files. */
class LiquidationQualificationCustodyBoundaryV1Test {
    @TempDir Path temporary;

    @Test
    void verificationDoesNotFollowSymlinkOrAcceptDirectoryAsReceipt() throws Exception {
        Path source = Files.write(temporary.resolve("source.json"), JsonHashes.canonicalBytes(
                JsonHashes.mapper().createObjectNode().put("schema", "liquidation-input-qualification/1")));
        Path alias = temporary.resolve("receipt-link.json");
        Files.createSymbolicLink(alias, source);
        ObjectNodeOptions options = new ObjectNodeOptions(alias);
        IllegalArgumentException symlinkError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.verify(options.json()));
        assertTrue(symlinkError.getMessage().contains("bounded regular file"));

        Path directory = Files.createDirectory(temporary.resolve("receipt-directory"));
        IllegalArgumentException directoryError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationInputQualificationV1.verify(new ObjectNodeOptions(directory).json()));
        assertTrue(directoryError.getMessage().contains("bounded regular file"));
    }

    private record ObjectNodeOptions(Path receipt) {
        com.fasterxml.jackson.databind.node.ObjectNode json() {
            return JsonHashes.mapper().createObjectNode().put("receipt", receipt.toString());
        }
    }
}
