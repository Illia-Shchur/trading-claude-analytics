package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportContractKnownAnswerTest {
    private static final Map<String, String> V2_HASHES = hashes();

    @Test
    void allTenPublishedV2SidecarsMatchTheNodeOracle() throws IOException {
        for (Map.Entry<String, String> vector : V2_HASHES.entrySet()) {
            Path path = repositoryRoot().resolve("reports").resolve(vector.getKey());
            ReportContract.LoadedReport loaded = ReportContract.loadAndValidateReport(path);
            assertThat(loaded.ok()).as(vector.getKey() + ": " + loaded.errors()).isTrue();
            assertThat(loaded.errors()).isEmpty();
            assertThat(loaded.warnings()).isEmpty();
            assertThat(loaded.schema()).isEqualTo(ReportContract.REPORT_MACHINE_V2);
            assertThat(ReportContract.reportHash(loaded.report())).isEqualTo(vector.getValue());
        }
    }

    @Test
    void v3SampleAndCanonicalBytesMatchTheNodeOracle() throws IOException {
        String raw = Files.readString(repositoryRoot().resolve("tools/fixtures/report-machine-3.sample.json"));
        JsonNode report = ReportContract.parseStrictJSON(raw, "v3 sample");

        ReportContract.ValidationResult result = ReportContract.validateReportMachine3(report);

        assertThat(result.ok()).as(result.errors().toString()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.schema()).isEqualTo(ReportContract.REPORT_MACHINE_V3);
        assertThat(ReportContract.reportHash(report))
                .isEqualTo("c2d0364abef435064b29ccd24b1e58e1d90b863b5ebdfeb10328e874b20b3640");
        assertThat(ReportContract.canonicalReportJSON(report))
                .isEqualTo(ReportContract.canonicalReportPayload(report) + "\n");
    }

    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !(Files.exists(candidate.resolve("pom.xml"))
                && Files.exists(candidate.resolve("docs/java-source-map.json")))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("cannot locate repository root");
        }
        return candidate;
    }

    private static Map<String, String> hashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("btc_fallen_knives_20260822_0346.json", "bcfc9d8186069560f7d071df4f871cd0fce13f3d179093b612d6da10297233cc");
        hashes.put("btc_flying_rocket_20260819_1222.json", "cb2265364390780cfff2d865d39b6cd745fa8f9b41b461c872144041b244c813");
        hashes.put("btc_flying_rocket_20260820_1640.json", "1d4932de203253779475e8d2ef010850e46b772e302c941f4c261b6a232eb2c4");
        hashes.put("btc_flying_rocket_20260821_0457.json", "97d88d33c0d2356504b3fadd69980c5c959a941874965ca92e02e23857008db7");
        hashes.put("eth_fallen_knives_20260822_0346.json", "1355c1919759877d897b25213dcbf607a36db331eb7f9644ae86c6040b55b8cc");
        hashes.put("eth_flying_rocket_20260819_1223.json", "5e76adf2230fe0fc2416769d7c1895e7218fdde6c12acba348b8ae3cd9e8a05f");
        hashes.put("eth_flying_rocket_20260820_1640.json", "c96260f86192d8c29854409e10470e8a61765c660eff582ba989136e6372bd56");
        hashes.put("eth_flying_rocket_20260821_0457.json", "e7567caee49c8572b42ecee3c1255986b820b06b6c80a7fed88644c18598aa0f");
        hashes.put("gold_fallen_knives_20260815_1210.json", "f3bbb45bb9783877a7c7af0ad772768e10e62e5cec1cd6eec9d41a56e94e71b5");
        hashes.put("sp500_flying_rocket_20260820_1640.json", "60a1c7795dcc1fd2b8e74422765bd03b7884cad70fe7dcbc13f12a7e98c6534a");
        return Map.copyOf(hashes);
    }
}
