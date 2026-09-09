package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent publication-boundary checks used by the bounded PIT contract. */
class ExportSignalsPublicationMutationTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    @TempDir Path temporaryDirectory;

    @Test
    void strictSuccessWritesAFeedAndAcceptsZeroMismatches() throws Exception {
        Path repository = temporaryDirectory.resolve("success");
        Path reports = validV2Reports(repository);
        Path output = repository.resolve("exports/signal-feed.json");
        Files.writeString(reports.resolve("README.md"), "ignored by the report filename contract\n");

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--out", output.toString(), "--strict"),
                repository, Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).contains("wrote ");
        assertThat(result.stderr()).contains("README.md");
        assertThat(result.stderr()).contains("every skip predates the machine-block epoch");
        assertThat(Files.isRegularFile(output)).isTrue();
        JsonNode feed = ReportContract.parseStrictJSON(
                Files.readString(output), output.getFileName().toString());
        assertThat(feed.path("schema").asText()).isEqualTo("signal-feed/1");
        assertThat(feed.path("counts").path("signals").asInt()).isEqualTo(1);
        assertThat(feed.path("counts").path("mismatched_v2_pairs").asInt()).isZero();
    }

    @Test
    void strictMissingMachineBlockPreservesExistingFeedAndCreatesNoNewFeed() throws Exception {
        Path repository = temporaryDirectory.resolve("missing");
        Path reports = repository.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("btc_fallen_knives_20260828_0101.md"), "# prose-only\n");
        assertStrictFailurePreservesSeed(repository, reports);
    }

    @Test
    void strictEpochBoundaryTreatsMissingMachineBlockAsARealGap() throws Exception {
        Path repository = temporaryDirectory.resolve("epoch-boundary");
        Path reports = repository.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("btc_fallen_knives_20260711_0101.md"), "# prose-only at the machine-block epoch\n");
        assertStrictFailurePreservesSeed(repository, reports);
    }

    @Test
    void dryRunStrictFailureRetainsDiagnosticCountsWithoutWriting() throws Exception {
        Path repository = temporaryDirectory.resolve("dry-run");
        Path reports = repository.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("btc_fallen_knives_20260828_0101.md"), "# prose-only\n");

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--dry-run", "--strict"),
                repository, Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("\"signals\": 0");
        assertThat(result.stderr()).contains("report(s) dated");
        assertThat(repository.resolve("exports/signal-feed.json")).doesNotExist();
    }

    @Test
    void outputOutsideExportsIsRejectedBeforeProjection() throws Exception {
        Path repository = temporaryDirectory.resolve("outside");
        Path reports = validV2Reports(repository);
        Path output = repository.resolve("outside.json");

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--out", output.toString()),
                repository, Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("refusing to write outside exports/");
        assertThat(output).doesNotExist();
    }

    @Test
    void strictMalformedMachineBlockPreservesExistingFeedAndCreatesNoNewFeed() throws Exception {
        Path repository = temporaryDirectory.resolve("malformed");
        Path reports = repository.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("btc_fallen_knives_20260828_0102.md"),
                "```json machine\n{\n```\n");
        assertStrictFailurePreservesSeed(repository, reports);
    }

    @Test
    void strictMismatchedPairPreservesExistingFeedAndCreatesNoNewFeed() throws Exception {
        Path repository = temporaryDirectory.resolve("mismatch");
        Path reports = validV2Reports(repository);
        Path markdown = reports.resolve("btc_fallen_knives_20260822_0346.md");
        JsonNode canonical = ReportContract.parseStrictJSON(
                Files.readString(reports.resolve("btc_fallen_knives_20260822_0346.json")), "fixture.json");
        JsonNode altered = canonical.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("narrative"))
                .put("summary", "strict publication rejects the altered sidecar");
        Files.writeString(markdown, ReportRenderer.renderFull(altered));
        assertStrictFailurePreservesSeed(repository, reports);
    }

    @Test
    void strictOutputDirectoryFailureIsReported() throws Exception {
        Path repository = temporaryDirectory.resolve("write-failure");
        Path reports = validV2Reports(repository);
        Path output = repository.resolve("exports/signal-feed.json");
        Files.createDirectories(output);

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--out", output.toString(), "--strict"),
                repository, Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("write failed:");
        assertThat(Files.isDirectory(output)).isTrue();

        Path innerFailureRepository = temporaryDirectory.resolve("write-failure-inner");
        Path innerReports = validV2Reports(innerFailureRepository);
        Path innerOutput = innerFailureRepository.resolve("exports/signal-feed.json");
        Files.createDirectories(innerOutput.getParent());
        Files.createDirectories(Path.of(innerOutput.toString() + ".tmp"));

        ReportingCommandResult innerFailure = ExportSignalsCommand.run(
                List.of("--reports", innerReports.toString(), "--out", innerOutput.toString(), "--strict"),
                innerFailureRepository, Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(innerFailure.exitCode()).isEqualTo(1);
        assertThat(innerFailure.stderr()).contains("write failed:");
        assertThat(innerOutput).doesNotExist();
    }

    @Test
    void defaultTimestampOverloadRetainsStrictValidationResult() throws Exception {
        Path repository = temporaryDirectory.resolve("default-timestamp");
        Path reports = validV2Reports(repository);

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--dry-run", "--strict"), repository);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("\"signals\": 1");
        assertThat(repository.resolve("exports/signal-feed.json")).doesNotExist();
    }

    @Test
    void missingReportsDirectoryFailsBeforePublication() throws Exception {
        Path repository = temporaryDirectory.resolve("missing-reports");
        Path reports = repository.resolve("reports");

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--strict"), repository,
                Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("ENOENT");
        assertThat(repository.resolve("exports/signal-feed.json")).doesNotExist();
    }

    private void assertStrictFailurePreservesSeed(Path repository, Path reports) throws Exception {
        Path output = repository.resolve("exports/signal-feed.json");
        byte[] previous = "LAST_KNOWN_GOOD\n".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(output.getParent());
        Files.write(output, previous);

        ReportingCommandResult result = ExportSignalsCommand.run(
                List.of("--reports", reports.toString(), "--out", output.toString(), "--strict"),
                repository, Instant.parse("2026-08-28T00:00:00Z"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readAllBytes(output)).isEqualTo(previous);
        assertThat(result.stderr()).doesNotContain("wrote ");
    }

    private Path validV2Reports(Path repository) throws Exception {
        Path reports = repository.resolve("reports");
        Files.createDirectories(reports);
        Path source = ROOT.resolve("reports/btc_fallen_knives_20260822_0346.json");
        JsonNode canonical = ReportContract.parseStrictJSON(
                Files.readString(source), source.getFileName().toString());
        String filename = canonical.path("identity").path("filename").asText();
        Files.writeString(reports.resolve(filename), ReportContract.canonicalReportJSON(canonical));
        Files.writeString(reports.resolve(filename.replace(".json", ".md")), ReportRenderer.renderFull(canonical));
        return reports;
    }
}
