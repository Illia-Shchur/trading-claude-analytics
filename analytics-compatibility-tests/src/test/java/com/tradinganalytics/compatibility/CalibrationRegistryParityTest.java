package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.research.calibration.CalibrationRegistry;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class CalibrationRegistryParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CalibrationRegistry javaRegistry = new CalibrationRegistry(JSON);

    @Test
    void validationErrorsMatchFrozenContractExactly() throws Exception {
        JsonNode registry = JSON.readTree("""
                {"schema":"wrong","entries":[{
                  "date":"bad","run_id":"","framework":"both","surface":"risk",
                  "name":"test","verdict":"invented","why":"why",
                  "revalidations":[{"date":"","verdict":""}]
                }]}
                """);
        JsonNode actual = JSON.valueToTree(javaRegistry.validate(registry));
        assertThat(actual).isEqualTo(frozen().path("validation"));
    }

    @Test
    void rejectionMatchesAndStableTieOrderMatchFrozenContractExactly() throws Exception {
        JsonNode registry = JSON.readTree("""
                {"schema":"calibration-registry/1","entries":[
                  {"date":"2026-08-01","run_id":"1","framework":"both","surface":"funding threshold","name":"Relax funding veto threshold","verdict":"rejected","why":"unsafe"},
                  {"date":"2026-08-02","run_id":"2","framework":"flying_rocket","surface":"funding controls","name":"Funding threshold relaxation","verdict":"withheld","why":"needs data"},
                  {"date":"2026-08-03","run_id":"3","framework":"both","surface":"funding threshold","name":"Relax funding threshold","verdict":"adopted","why":"accepted"}
                ]}
                """);
        JsonNode actual = JSON.valueToTree(javaRegistry.matchRejections(
                "relax funding threshold", registry, "flying_rocket"));
        assertThat(actual).isEqualTo(frozen().path("matches"));
    }

    private JsonNode frozen() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/oracles/calibration-registry-v1.json")) {
            assertThat(stream).isNotNull();
            return JSON.readTree(stream);
        }
    }
}
