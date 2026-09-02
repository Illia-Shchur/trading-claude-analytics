package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyV5WorkflowSupportTest {
    @TempDir
    Path temporary;

    @Test
    void modeTailParserSupportsEqualsValuesAndBooleanFlags() {
        Map<String, String> flags = StrategyV5WorkflowPaths.flags(new String[] {
                "tree", "--label=fixture evidence", "--path", "evidence", "-v", "--repository"
        }, 1);

        assertThat(flags).containsExactlyInAnyOrderEntriesOf(Map.of(
                "label", "fixture evidence", "path", "evidence", "repository", "true"));
    }

    @Test
    void modeTailParserRejectsSensitiveNamesBeforeAnyModeWork() {
        assertThatThrownBy(() -> StrategyV5WorkflowPaths.flags(
                new String[] {"cycle", "--private_key=not-accepted"}, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sensitive option names are not accepted: --private_key");
    }

    @Test
    void receiptBuilderSortsUniqueLimitationsAndForcesInactiveDetails() {
        ObjectNode receipt = StrategyV5WorkflowReceipts.commandReceipt(
                "prospective-runner", "BLOCKED", List.of(),
                List.of("z-limit", "a-limit", "z-limit"),
                StrategyV5WorkflowJson.object().put("active", true));

        assertThat(receipt.path("limitations").toString())
                .isEqualTo("[\"a-limit\",\"z-limit\"]");
        assertThat(receipt.path("details").path("active").asBoolean()).isFalse();
        assertThat(receipt.path("content_sha256").asText())
                .isEqualTo(StrategyProspectiveV5.ownHash(receipt));
    }

    @Test
    void immutableWriteIsIdempotentButRejectsDifferentBytes() throws Exception {
        Path output = temporary.toRealPath().resolve("nested/output.json");
        byte[] original = "original\n".getBytes(StandardCharsets.UTF_8);

        StrategyV5WorkflowPaths.writeExclusive(output, original);
        StrategyV5WorkflowPaths.writeExclusive(output, original);

        assertThat(Files.readAllBytes(output)).isEqualTo(original);
        assertThatThrownBy(() -> StrategyV5WorkflowPaths.writeExclusive(
                output, "replacement\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable output collision");
    }
}
