package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Publication controls remain strict while noncanonical archive payloads are excluded. */
final class StrategyRetentionArchivePublicationReviewTest {
    @TempDir Path temporary;

    @Test
    void transactionControlInsideMarkedArchiveCannotBeHidden() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("records"));
        Path evidence = Files.createDirectories(root.resolve("evidence"));
        Files.copy(repoFile("strategy-research/v5-records/evidence/.retention-archive"),
                evidence.resolve(".retention-archive"));
        Path transactions = Files.createDirectories(evidence.resolve("transactions"));
        Files.writeString(transactions.resolve("unexpected.json"), "{}\n");
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("root", root.toString())
                .put("out", temporary.resolve("index.json").toString())
                .put("record_root", temporary.resolve("receipts").toString());

        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli("index", options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publication transaction");
    }

    private static Path repoFile(String relative) {
        Path path = Path.of(relative);
        return (Files.exists(path) ? path : Path.of("..", relative)).toAbsolutePath().normalize();
    }
}
