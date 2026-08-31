package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportPathsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesCanonicalJsonIdentity() {
        assertThat(ReportPaths.reportJsonIdentity("reports/btc_fallen_knives_20260828_0930.json"))
                .contains(new ReportPaths.ReportIdentity(
                        "btc_fallen_knives_20260828_0930",
                        "btc_fallen_knives_20260828_0930.json"));
        assertThat(ReportPaths.reportJsonIdentity("eth_flying_rocket_20260828_1730"))
                .contains(new ReportPaths.ReportIdentity(
                        "eth_flying_rocket_20260828_1730",
                        "eth_flying_rocket_20260828_1730"));
    }

    @Test
    void rejectsNonCanonicalIdentity() {
        assertThat(ReportPaths.reportJsonIdentity("BTC_fallen_knives_20260828_0930.json")).isEmpty();
        assertThat(ReportPaths.reportJsonIdentity("btc_other_framework_20260828_0930.json")).isEmpty();
        assertThat(ReportPaths.reportJsonIdentity("btc_fallen_knives_20260828_930.json")).isEmpty();
    }

    @Test
    void preservesLegacyStemAndV2PathRules() {
        assertThat(ReportPaths.reportStem("reports/btc_fallen_knives_20260828_0930.md"))
                .isEqualTo("btc_fallen_knives_20260828_0930");
        assertThat(ReportPaths.isV2Path("reports/btc_fallen_knives_20260828_0930.JSON")).isFalse();
        assertThat(ReportPaths.isV2Path("reports/btc_fallen_knives_20260828_0930.json")).isTrue();
    }

    @Test
    void confinesResolvedPathsToReportsDirectory() {
        Path repository = temporaryDirectory.resolve("repo");
        assertThat(ReportPaths.isInsideReports(repository.resolve("reports/a.json"), repository)).isTrue();
        assertThat(ReportPaths.isInsideReports(repository.resolve("reports"), repository)).isTrue();
        assertThat(ReportPaths.isInsideReports(repository.resolve("reports/../outside.json"), repository)).isFalse();
        assertThat(ReportPaths.isInsideReports(repository.resolve("reports-copy/a.json"), repository)).isFalse();
    }
}
