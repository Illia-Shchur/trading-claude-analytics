package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class ReportRendererPropertiesTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final JsonNode V2 = load(ROOT.resolve("reports/btc_fallen_knives_20260822_0346.json"));
    private static final JsonNode V3 = load(ROOT.resolve("tools/fixtures/report-machine-3.sample.json"));

    @Property(tries = 150)
    void allRenderExportsArePureAndDeterministic(@ForAll("prose") String prose) {
        JsonNode v2 = V2.deepCopy();
        ((ObjectNode) v2.path("verdict")).put("statement", prose);
        ((ObjectNode) v2.path("narrative")).put("summary", prose);
        String v2Before = ReportContract.canonicalReportPayload(v2);
        String v2Full = ReportRenderer.renderFull(v2);
        String v2Summary = ReportRenderer.renderSummary(v2);
        assertThat(ReportRenderer.renderFull(v2)).isEqualTo(v2Full);
        assertThat(ReportRenderer.renderSummary(v2)).isEqualTo(v2Summary);
        assertThat(ReportContract.canonicalReportPayload(v2)).isEqualTo(v2Before);

        JsonNode v3 = V3.deepCopy();
        ((ObjectNode) v3.path("verdict")).put("statement", prose);
        String v3Before = ReportContract.canonicalReportPayload(v3);
        String v3Full = ReportRenderer.renderSwingFull(v3);
        String v3Summary = ReportRenderer.renderSwingSummary(v3);
        assertThat(ReportRenderer.renderSwingFull(v3)).isEqualTo(v3Full);
        assertThat(ReportRenderer.renderSwingSummary(v3)).isEqualTo(v3Summary);
        assertThat(ReportContract.canonicalReportPayload(v3)).isEqualTo(v3Before);
    }

    @Property(tries = 150)
    void swingTablesEscapePipesNewlinesAndCodeFences(@ForAll("tableFragments") String fragment) {
        String unsafe = fragment + "|pipe\n```fence```\r\n" + fragment;
        JsonNode report = V3.deepCopy();
        ((ObjectNode) report.withArray("vetoes").get(0)).put("reason", unsafe);

        String escaped = unsafe.replace("```", "`\\`\\`")
                .replaceAll("\\r?\\n", "<br>")
                .replace("|", "\\|");
        assertThat(ReportRenderer.renderSwingFull(report)).contains(escaped);
    }

    @Provide
    Arbitrary<String> prose() {
        return Arbitraries.strings().withChars(' ', '~').ofMaxLength(120);
    }

    @Provide
    Arbitrary<String> tableFragments() {
        return Arbitraries.strings().withChars('a', 'z').numeric().ofMaxLength(30);
    }

    private static JsonNode load(Path path) {
        try { return ReportContract.parseStrictJSON(Files.readString(path), path.getFileName().toString()); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
