package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwingCalibrationLinterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temporary;

    @Test
    void shadowArtifactFromHarnessPassesSchemaAndCustomChecks() throws Exception {
        ObjectNode report = SwingCalibration.calibrate(MAPPER.createArrayNode(), null, SwingCalibration.defaultCandidates(),
                SwingCalibration.Options.defaults(), FIXED);
        assertThat(SwingCalibrationLinter.lint(report, temporary)).isEmpty();
    }

    @Test
    void activeArtifactDigestAndCommittedCopyAreVerified() throws Exception {
        ObjectNode report = SwingCalibration.calibrate(MAPPER.createArrayNode(), null, SwingCalibration.defaultCandidates(),
                SwingCalibration.Options.defaults(), FIXED);
        report.put("activation", "ACTIVE").put("point_in_time_safe", true);
        ((ObjectNode) report.path("activation_policy")).put("point_in_time_safe_required", true).put("proxy_inputs_accepted", true)
                .set("required_series", MAPPER.createArrayNode());
        ((ObjectNode) report.path("proxy_contract")).put("accepted", true);
        Path artifact = temporary.resolve("active.json"); ObjectNode activation = MAPPER.createObjectNode().put("status", "ACTIVE")
                .put("artifact", artifact.toString()).putNull("sha256").put("activated_at", report.path("generated_at").asText());
        report.set("model_activation", activation); String digest = SwingEngine.sha256(SwingCalibrationLinter.canonicalPayload(report)); activation.put("sha256", digest);
        Files.writeString(artifact, NodePrettyJson.write(report)); assertThat(SwingCalibrationLinter.lint(report, temporary)).isEmpty();
        ((ObjectNode) report.path("costs")).put("fee_pct_one_way", 99);
        assertThat(SwingCalibrationLinter.lint(report, temporary)).contains("ACTIVE calibration SHA-256 does not match canonical payload",
                "calibration report and committed artifact differ");
    }
}
