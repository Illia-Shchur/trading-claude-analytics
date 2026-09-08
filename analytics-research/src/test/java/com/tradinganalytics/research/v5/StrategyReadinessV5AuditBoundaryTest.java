package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public audit output, timestamp, and write-once contract boundaries. */
final class StrategyReadinessV5AuditBoundaryTest {
    private static final long NOW = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final String GENERATED_AT = "2026-01-01T00:00:00.000Z";

    @Test
    void nullInputStillProducesAClosedAuditWithAllReadinessDimensions() {
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(null);

        assertThat(audit.path("schema").asText()).isEqualTo("strategy-readiness-audit/2");
        assertThat(audit.path("basis").asText()).isEqualTo("EVIDENCE_DERIVED_OPERATIONAL");
        assertThat(audit.path("generated_at").asText()).isNotBlank();
        assertThat(audit.path("dimensions")).hasSize(8);
        assertThat(audit.path("strategy_testing_readiness").path("status").asText()).isEqualTo("BLOCKED");
        assertThat(audit.path("activation").path("ready").asBoolean()).isFalse();
        assertThat(audit.path("content_sha256").asText()).isEqualTo(StrategyReadinessV5.ownHash(audit));
    }

    @Test
    void suppliedTimestampFormsAreParsedAndNonObjectEvidenceFallsBackToEmpty() {
        ObjectNode options = object().put("now", "2026-01-01T02:00:00+02:00")
                .put("generatedAt", GENERATED_AT);
        options.set("evidence", array().addNull().add(object()));

        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertThat(audit.path("generated_at").asText()).isEqualTo(GENERATED_AT);
        assertThat(audit.path("artifact_verification")).isEmpty();
        assertThat(audit.path("limitations")).isNotEmpty();

        ObjectNode invalid = options.deepCopy().put("now", "not-a-timestamp");
        assertThatThrownBy(() -> StrategyReadinessV5.buildReadinessAuditV5(invalid))
                .hasMessageContaining("now must be a valid timestamp");
    }

    @Test
    void markdownRejectsStaleOrWrongSchemaAudits() {
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(
                object().put("now", NOW).put("generatedAt", GENERATED_AT));
        assertThat(StrategyReadinessV5.renderReadinessMarkdown(audit))
                .contains("# Strategy readiness audit", "## Verified artifacts", "## Aggregate limitations");

        ObjectNode stale = audit.deepCopy().put("content_sha256", "c".repeat(64));
        assertThatThrownBy(() -> StrategyReadinessV5.renderReadinessMarkdown(stale))
                .hasMessageContaining("invalid readiness audit");
        ObjectNode wrongSchema = audit.deepCopy().put("schema", "strategy-readiness-audit/1");
        wrongSchema.put("content_sha256", StrategyReadinessV5.ownHash(wrongSchema));
        assertThatThrownBy(() -> StrategyReadinessV5.renderReadinessMarkdown(wrongSchema))
                .hasMessageContaining("invalid readiness audit");
    }

    @Test
    void writeReadinessAuditPersistsCanonicalBytesAndIsCreateNew(@TempDir Path root) throws Exception {
        Path path = root.resolve("readiness.json");
        ObjectNode options = object().put("now", NOW).put("generatedAt", GENERATED_AT);
        ObjectNode written = StrategyReadinessV5.writeReadinessAudit(path, options);

        byte[] bytes = Files.readAllBytes(path);
        JsonNode reopened = JsonHashes.mapper().readTree(bytes);
        assertThat(reopened.path("schema").asText()).isEqualTo(written.path("schema").asText());
        assertThat(reopened.path("content_sha256").asText()).isEqualTo(written.path("content_sha256").asText());
        assertThat(reopened.path("dimensions")).hasSize(8);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(NodePrettyJson.write(written));
        assertThatThrownBy(() -> StrategyReadinessV5.writeReadinessAudit(path, options))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
