package com.tradinganalytics.research.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalibrationRegistryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CalibrationRegistry registry = new CalibrationRegistry(json);

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingRegistryLoadsAsCanonicalEmptyShape() throws Exception {
        assertThat(registry.load(temporaryDirectory.resolve("missing.json")).toString())
                .isEqualTo("{\"schema\":\"calibration-registry/1\",\"note\":\"\",\"entries\":[]}");
    }

    @Test
    void validatesCompleteEntriesAndSameDaySuffix() throws Exception {
        var value = json.readTree("""
                {"schema":"calibration-registry/1","entries":[{
                  "date":"2026-08-28b","run_id":"run-2","framework":"both",
                  "surface":"risk","name":"retain hard funding veto","verdict":"withheld","why":"safety",
                  "revalidations":[{"date":"2026-09-01","verdict":"withheld"}]
                }]}
                """);
        assertThat(registry.validate(value)).isEqualTo(
                new CalibrationRegistry.ValidationResult(true, java.util.List.of()));
    }

    @Test
    void reportsEveryStructuralErrorInStableOrder() throws Exception {
        var value = json.readTree("""
                {"schema":"wrong","entries":[{
                  "date":"28-08-2026","run_id":"","framework":"both","surface":"risk",
                  "name":"test","verdict":"invented","why":"x",
                  "revalidations":[{"date":"","verdict":""}]
                }]}
                """);
        assertThat(registry.validate(value).errors()).containsExactly(
                "schema must be \"calibration-registry/1\", got \"wrong\"",
                "entries[0] missing \"run_id\"",
                "entries[0] verdict \"invented\" not one of adopted|adopted_with_modification|rejected|withheld|unadjudicated",
                "entries[0] date \"28-08-2026\" not YYYY-MM-DD (or YYYY-MM-DDb for a same-day second run)",
                "entries[0] revalidation missing date/verdict: {\"date\":\"\",\"verdict\":\"\"}");
    }

    @Test
    void rejectionMatchingRequiresTwoSignificantSharedTokens() throws Exception {
        var value = json.readTree("""
                {"schema":"calibration-registry/1","entries":[
                  {"date":"2026-08-01","run_id":"1","framework":"both","surface":"funding threshold","name":"Relax funding veto threshold","verdict":"rejected","why":"unsafe"},
                  {"date":"2026-08-02","run_id":"2","framework":"fallen_knives","surface":"score","name":"Momentum score cap","verdict":"adopted","why":"evidence"},
                  {"date":"2026-08-03","run_id":"3","framework":"flying_rocket","surface":"funding controls","name":"Funding carry relaxation","verdict":"withheld","why":"needs data"}
                ]}
                """);

        assertThat(registry.matchRejections("relax funding threshold", value, "fallen_knives"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.score()).isEqualTo(3);
                    assertThat(match.overlap()).containsExactly("relax", "funding", "threshold");
                });
        assertThat(registry.matchRejections("funding", value, null)).isEmpty();
    }
}
