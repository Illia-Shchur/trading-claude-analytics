package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportRendererNodeOracleTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final ObjectNode ORACLE = loadOracle();

    @Test
    void allFourExportsMatchNodeAcrossPublishedV2AndSwingV3Documents() throws Exception {
        Map<String, JsonNode> expectedReports = new LinkedHashMap<>();
        ORACLE.path("reports").forEach(row -> expectedReports.put(row.path("file").asText(), row));
        // Keep renderer parity tied to the reviewed corpus. New report files
        // require their own oracle entry rather than changing this contract.
        for (Map.Entry<String, JsonNode> entry : expectedReports.entrySet()) {
            Path file = ROOT.resolve("reports").resolve(entry.getKey());
            JsonNode expected = entry.getValue();
            assertThat(Files.isRegularFile(file)).as("frozen renderer corpus member %s", file).isTrue();
            JsonNode report;
            try {
                report = ReportContract.parseStrictJSON(Files.readString(file), file.getFileName().toString());
            } catch (Exception error) {
                throw new AssertionError("frozen renderer corpus member is not valid: " + file, error);
            }
            assertThat(report.path("schema").asText()).as("frozen renderer schema for %s", file)
                    .isEqualTo("report-machine/2");
            assertThat(JsonHashes.sha256(Files.readAllBytes(file)))
                    .isEqualTo(expected.path("input_sha256").asText());
            assertExport(expected, "full", ReportRenderer.renderFull(report));
            assertExport(expected, "summary", ReportRenderer.renderSummary(report));
        }
        Path swing = ROOT.resolve("tools/fixtures/report-machine-3.sample.json");
        JsonNode report = ReportContract.parseStrictJSON(Files.readString(swing), swing.getFileName().toString());
        JsonNode expectedSwing = ORACLE.path("swing");
        assertThat(JsonHashes.sha256(Files.readAllBytes(swing)))
                .isEqualTo(expectedSwing.path("input_sha256").asText());
        assertExport(expectedSwing, "full", ReportRenderer.renderSwingFull(report));
        assertExport(expectedSwing, "summary", ReportRenderer.renderSwingSummary(report));
    }

    private static void assertExport(JsonNode expected, String kind, String actual) {
        assertThat(actual.length()).as(kind + " character count")
                .isEqualTo(expected.path(kind + "_chars").asInt());
        assertThat(JsonHashes.sha256(actual)).as(kind + " frozen Node renderer hash")
                .isEqualTo(expected.path(kind + "_sha256").asText());
    }

    private static ObjectNode loadOracle() {
        try (var input = ReportRendererNodeOracleTest.class.getResourceAsStream(
                "/oracles/report-renderer-v1.json")) {
            if (input == null) throw new IllegalStateException("frozen renderer oracle is missing");
            return (ObjectNode) JsonHashes.mapper().readTree(input);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
