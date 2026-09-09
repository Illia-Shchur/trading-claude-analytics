package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradinganalytics.reporting.ReportPaths;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReportPathsParityTest {
    record Case(String path, String stem, boolean v2, Map<String, String> identity) {}

    static Stream<Case> paths() {
        return Stream.of(
                valid("reports/btc_fallen_knives_20260828_0930.json", "btc_fallen_knives_20260828_0930", true),
                valid("eth_flying_rocket_20260828_1730", "eth_flying_rocket_20260828_1730", false),
                valid("btc_fallen_knives_20260230_0930.json", "btc_fallen_knives_20260230_0930", true),
                new Case("BTC_fallen_knives_20260828_0930.json", "BTC_fallen_knives_20260828_0930", false, null),
                new Case("btc_fallen_knives_20260828_0930.JSON", "btc_fallen_knives_20260828_0930.JSON", false, null),
                new Case("nested.with.dots/report.md", "report", false, null));
    }

    @ParameterizedTest
    @MethodSource("paths")
    void pathHelpersMatchFrozenCompatibilityVectors(Case testCase) {
        var identity = ReportPaths.reportJsonIdentity(testCase.path())
                .map(value -> java.util.Map.of("stem", value.stem(), "filename", value.filename()))
                .orElse(null);
        assertThat(identity).isEqualTo(testCase.identity());
        assertThat(ReportPaths.reportStem(testCase.path())).isEqualTo(testCase.stem());
        assertThat(ReportPaths.isV2Path(testCase.path())).isEqualTo(testCase.v2());
    }

    private static Case valid(String path, String stem, boolean v2) {
        return new Case(path, stem, v2, Map.of("stem", stem, "filename", path.substring(path.lastIndexOf('/') + 1)));
    }
}
