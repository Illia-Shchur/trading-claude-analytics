package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwingActivationVerifierTest {
    private static final String RELATIVE = "calibrations/active.json";
    private static final List<String> REQUIRED_SERIES = List.of(
            "btc:fallen_knives",
            "btc:flying_rocket:A",
            "btc:flying_rocket:B",
            "eth:fallen_knives",
            "eth:flying_rocket:A",
            "eth:flying_rocket:B");

    @TempDir
    Path repository;

    @Test
    void acceptsACompleteCanonicalActiveArtifact() throws IOException {
        Fixture fixture = writeValidArtifact();

        assertThat(ReportContract.verifySwingActivationArtifact(fixture.report(), repository)).isEmpty();
    }

    @Test
    void nonActiveReportsDoNotTouchTheFilesystem() {
        ObjectNode report = ReportContract.parseStrictJSON("{\"model_activation\":{\"status\":\"SHADOW\"}}").deepCopy();

        assertThat(ReportContract.verifySwingActivationArtifact(report, repository)).isEmpty();
    }

    @Test
    void rejectsUnsafeMissingAndMalformedArtifactPaths() throws IOException {
        ObjectNode report = activeReport("../outside.json", "0".repeat(64));
        assertThat(ReportContract.verifySwingActivationArtifact(report, repository))
                .containsExactly("ACTIVE swing model artifact must be a relative calibrations/<file>.json path");

        report = activeReport("calibrations/missing.json", "0".repeat(64));
        assertThat(ReportContract.verifySwingActivationArtifact(report, repository))
                .containsExactly("ACTIVE swing model artifact is missing: calibrations/missing.json");

        Files.createDirectories(repository.resolve("calibrations"));
        Files.writeString(repository.resolve("calibrations/bad.json"), "{\"x\":1,}");
        report = activeReport("calibrations/bad.json", "0".repeat(64));
        assertThat(ReportContract.verifySwingActivationArtifact(report, repository))
                .singleElement().asString()
                .startsWith("ACTIVE swing model artifact is invalid JSON: calibrations/bad.json: invalid strict JSON");
    }

    @Test
    void reportsEveryArtifactMetadataAndPolicyFailureWithoutShortCircuiting() throws IOException {
        Fixture fixture = writeValidArtifact();
        ObjectNode artifact = fixture.artifact().deepCopy();
        artifact.put("schema", "wrong");
        artifact.put("activation", "SHADOW");
        object(artifact, "model_activation").put("status", "SHADOW")
                .put("artifact", "calibrations/other.json").put("sha256", "f".repeat(64));
        object(artifact, "artifact").put("path", "calibrations/other.json").put("sha256", "e".repeat(64));
        artifact.put("point_in_time_safe", false);
        object(artifact, "activation_policy").put("point_in_time_safe_required", false)
                .put("proxy_inputs_accepted", false);
        object(artifact, "proxy_contract").put("accepted", false);
        array(object(artifact, "activation_policy"), "required_series").remove(0);
        object(array(artifact, "datasets").get(0)).put("holdout_pass", false);
        write(artifact);

        assertThat(ReportContract.verifySwingActivationArtifact(fixture.report(), repository)).containsExactly(
                "ACTIVE swing model artifact has the wrong calibration/model schema",
                "ACTIVE swing model artifact is not ACTIVE",
                "ACTIVE report artifact path does not match calibration artifact metadata",
                "ACTIVE report and calibration artifact SHA-256 differ",
                "ACTIVE swing model artifact SHA-256 does not match its canonical payload",
                "ACTIVE calibration convenience artifact metadata is inconsistent",
                "ACTIVE swing model calibration is not point-in-time safe",
                "ACTIVE swing model calibration lacks accepted proxy policy",
                "ACTIVE swing model calibration policy missing btc:fallen_knives",
                "ACTIVE swing model calibration holdout failed or missing: btc:fallen_knives");
    }

    @Test
    void auditsAllSixDeclaredAndPassingSeriesInStableOrder() throws IOException {
        Fixture fixture = writeValidArtifact();
        ObjectNode artifact = fixture.artifact().deepCopy();
        array(object(artifact, "activation_policy"), "required_series").removeAll();
        array(artifact, "datasets").removeAll();
        write(artifact);

        List<String> errors = ReportContract.verifySwingActivationArtifact(fixture.report(), repository);

        assertThat(errors).containsSubsequence(REQUIRED_SERIES.stream()
                .map(series -> "ACTIVE swing model calibration policy missing " + series).toList());
        assertThat(errors).containsSubsequence(REQUIRED_SERIES.stream()
                .map(series -> "ACTIVE swing model calibration holdout failed or missing: " + series).toList());
    }

    @Test
    void strictJsonPrimitivesFailClosedInsteadOfCrashing() throws IOException {
        Files.createDirectories(repository.resolve("calibrations"));
        Files.writeString(repository.resolve(RELATIVE), "42\n");
        ObjectNode report = activeReport(RELATIVE, "0".repeat(64));

        assertThat(ReportContract.verifySwingActivationArtifact(report, repository)).contains(
                "ACTIVE swing model artifact has the wrong calibration/model schema",
                "ACTIVE swing model artifact is not ACTIVE",
                "ACTIVE report artifact path does not match calibration artifact metadata",
                "ACTIVE swing model artifact SHA-256 does not match its canonical payload",
                "ACTIVE calibration convenience artifact metadata is inconsistent");
    }

    private Fixture writeValidArtifact() throws IOException {
        ObjectNode payload = ReportContract.parseStrictJSON("{}").deepCopy();
        payload.put("schema", "swing-calibration/1");
        payload.put("model", "swing-score/1");
        payload.put("activation", "ACTIVE");
        payload.put("point_in_time_safe", true);
        payload.putObject("proxy_contract").put("accepted", true);
        ObjectNode policy = payload.putObject("activation_policy");
        policy.put("point_in_time_safe_required", true).put("proxy_inputs_accepted", true);
        ArrayNode required = policy.putArray("required_series");
        REQUIRED_SERIES.forEach(required::add);
        ArrayNode datasets = payload.putArray("datasets");
        for (String series : REQUIRED_SERIES) {
            String[] parts = series.split(":");
            ObjectNode dataset = datasets.addObject().put("asset", parts[0]).put("framework", parts[1])
                    .put("holdout_pass", true);
            if (parts.length == 3) dataset.put("channel", parts[2]);
        }
        payload.putObject("model_activation").put("status", "ACTIVE")
                .putNull("artifact").putNull("sha256").putNull("activated_at");
        String digest = ReportContract.reportHash(payload);

        ObjectNode artifact = payload.deepCopy();
        object(artifact, "model_activation").put("artifact", RELATIVE).put("sha256", digest)
                .put("activated_at", "2026-08-22T15:00:00Z");
        artifact.putObject("artifact").put("path", RELATIVE).put("sha256", digest);
        write(artifact);
        return new Fixture(activeReport(RELATIVE, digest), artifact);
    }

    private void write(ObjectNode artifact) throws IOException {
        Files.createDirectories(repository.resolve("calibrations"));
        Files.writeString(repository.resolve(RELATIVE), ReportContract.canonicalReportJSON(artifact));
    }

    private static ObjectNode activeReport(String artifact, String sha) {
        ObjectNode report = ReportContract.parseStrictJSON("{}").deepCopy();
        report.putObject("model_activation").put("status", "ACTIVE")
                .put("artifact", artifact).put("sha256", sha).put("activated_at", "2026-08-22T15:00:00Z");
        return report;
    }

    private static ObjectNode object(com.fasterxml.jackson.databind.JsonNode parent, String field) {
        return (ObjectNode) parent.get(field);
    }

    private static ObjectNode object(com.fasterxml.jackson.databind.JsonNode node) {
        return (ObjectNode) node;
    }

    private static ArrayNode array(com.fasterxml.jackson.databind.JsonNode parent, String field) {
        return (ArrayNode) parent.get(field);
    }

    private record Fixture(ObjectNode report, ObjectNode artifact) { }
}
