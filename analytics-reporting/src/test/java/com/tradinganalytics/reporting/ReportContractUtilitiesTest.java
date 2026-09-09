package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradinganalytics.contracts.json.StrictJsonException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportContractUtilitiesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesEveryPinnedConstantAndStableStatusInsertionOrder() {
        assertThat(ReportContract.REPORT_MACHINE_V2).isEqualTo("report-machine/2");
        assertThat(ReportContract.REPORT_MACHINE_V3).isEqualTo("report-machine/3");
        assertThat(ReportContract.REPORT_MARKDOWN_V1).isEqualTo("report-markdown/1");
        assertThat(ReportContract.REPORT_PHASE_REGISTRY_V2).isEqualTo("report-phase-registry/2");
        assertThat(ReportContract.REPORT_REPORT_ID_RE.pattern())
                .isEqualTo("^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\\d{8})_(\\d{4})$");
        assertThat(new ArrayList<>(ReportContract.REPORT_STATUSES)).containsExactly(
                "AVAILABLE", "UNKNOWN", "STALE", "EXPIRED", "NOT_COVERED", "DATA_LIMITED", "NOT_APPLICABLE");
        assertThatThrownBy(() -> ReportContract.REPORT_STATUSES.add("OTHER"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void strictFacadeRejectsDuplicateKeysCommentsTrailingCommasAndNullText() {
        assertThat(ReportContract.parseStrictJSON("{\"x\":1}").path("x").intValue()).isEqualTo(1);
        assertThatExceptionOfType(StrictJsonException.class)
                .isThrownBy(() -> ReportContract.parseStrictJSON("{\"x\":1,\"x\":2}", "sidecar"))
                .withMessage("sidecar: invalid strict JSON at offset 0: duplicate key x");
        assertThatExceptionOfType(StrictJsonException.class)
                .isThrownBy(() -> ReportContract.parseStrictJSON("{/*x*/\"x\":1}"));
        assertThatExceptionOfType(StrictJsonException.class)
                .isThrownBy(() -> ReportContract.parseStrictJSON("{\"x\":1,}"));
        assertThatExceptionOfType(StrictJsonException.class)
                .isThrownBy(() -> ReportContract.parseStrictJSON(null, "payload"))
                .withMessage("payload: input must be UTF-8 text");
    }

    @Test
    void canonicalAndHashFacadesAreDeterministicAndNewlineExact() {
        JsonNode value = ReportContract.parseStrictJSON("{\"z\":-0.0,\"a\":1e-7,\"text\":\"é\"}");

        assertThat(ReportContract.canonicalReportPayload(value))
                .isEqualTo("{\"a\":1e-7,\"text\":\"é\",\"z\":0}");
        assertThat(ReportContract.canonicalReportJSON(value))
                .isEqualTo("{\"a\":1e-7,\"text\":\"é\",\"z\":0}\n");
        assertThat(ReportContract.reportHash(value))
                .isEqualTo("c6665db2ceb40bc2e18044d0ae6f92db69f3ac1ae0b4f3e6b5c3fe50b2819cbb");
        assertThatThrownBy(() -> ReportContract.canonicalReportPayload(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadPreservesRawTextRoutesUnknownSchemasToV2AndPinsFilename() throws IOException {
        String raw = Files.readString(ReportContractKnownAnswerTest.repositoryRoot()
                .resolve("tools/fixtures/report-machine-3.sample.json"));
        Path wrongName = temporaryDirectory.resolve("wrong.json");
        Files.writeString(wrongName, raw);

        ReportContract.LoadedReport loaded = ReportContract.loadAndValidateReport(wrongName);

        assertThat(loaded.raw()).isEqualTo(raw);
        assertThat(loaded.schema()).isEqualTo("report-machine/3");
        assertThat(loaded.errors())
                .contains("filename wrong.json does not match identity.filename btc_fallen_knives_20260822_1200.json");

        JsonNode unknown = ((com.fasterxml.jackson.databind.node.ObjectNode) loaded.report().deepCopy())
                .put("schema", "report-machine/future");
        Path unknownPath = temporaryDirectory.resolve("future.json");
        Files.writeString(unknownPath, ReportContract.canonicalReportJSON(unknown));
        assertThat(ReportContract.loadAndValidateReport(unknownPath).schema()).isEqualTo("report-machine/2");
    }

    @Test
    void validationIsTotalForNullAndReturnsImmutableDiagnostics() {
        ReportContract.ValidationResult result = ReportContract.validateReportMachine2(null);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThatThrownBy(() -> result.errors().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ReportContract.validateReportMachine3(null).ok()).isFalse();
    }

    @Test
    void identityStemExtensionAndPosixBasenameRulesMatchNode() {
        assertThat(ReportContract.reportJsonIdentity("prefix/btc_fallen_knives_20260828_0930.json"))
                .contains(new ReportPaths.ReportIdentity(
                        "btc_fallen_knives_20260828_0930", "btc_fallen_knives_20260828_0930.json"));
        assertThat(ReportContract.reportJsonIdentity("prefix\\btc_fallen_knives_20260828_0930.json")).isEmpty();
        assertThat(ReportContract.reportJsonIdentity("btc_fallen_knives_20260828_0930.JSON")).isEmpty();
        assertThat(ReportContract.reportStem("a/btc_fallen_knives_20260828_0930.json"))
                .isEqualTo("btc_fallen_knives_20260828_0930");
        assertThat(ReportContract.reportStem("a/btc_fallen_knives_20260828_0930.JSON"))
                .isEqualTo("btc_fallen_knives_20260828_0930.JSON");
        assertThat(ReportContract.isV2Path("btc_fallen_knives_20260828_0930.json")).isTrue();
        assertThat(ReportContract.isV2Path("btc_fallen_knives_20260828_0930.JSON")).isFalse();
    }

    @Test
    void reportsContainmentIsLexicalAndSegmentAware() {
        Path repository = temporaryDirectory.resolve("repo");
        assertThat(ReportContract.isInsideReports(repository.resolve("reports/a.json"), repository)).isTrue();
        assertThat(ReportContract.isInsideReports(repository.resolve("reports"), repository)).isTrue();
        assertThat(ReportContract.isInsideReports(repository.resolve("reports/../a.json"), repository)).isFalse();
        assertThat(ReportContract.isInsideReports(repository.resolve("reports-copy/a.json"), repository)).isFalse();
    }
}
