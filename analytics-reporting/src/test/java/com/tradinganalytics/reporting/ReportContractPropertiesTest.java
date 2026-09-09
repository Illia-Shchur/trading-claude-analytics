package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.json.StrictJsonException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class ReportContractPropertiesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 500)
    void canonicalIdentityRoundTripsForEveryRegexAcceptedStem(
            @ForAll("assets") String asset,
            @ForAll("frameworks") String framework,
            @ForAll("dates") String date,
            @ForAll("times") String time,
            @ForAll("prefixes") String prefix) {
        String stem = asset + '_' + framework + '_' + date + '_' + time;
        String path = prefix + '/' + stem + ".json";

        assertThat(ReportContract.reportJsonIdentity(path))
                .contains(new ReportPaths.ReportIdentity(stem, stem + ".json"));
        assertThat(ReportContract.reportStem(path)).isEqualTo(stem);
        assertThat(ReportContract.isV2Path(path)).isTrue();
        assertThat(ReportContract.isV2Path(prefix + '/' + stem + ".JSON")).isFalse();
    }

    @Property(tries = 300)
    void anyUppercaseAssetBreaksThePinnedIdentityGrammar(
            @ForAll("uppercaseAssets") String asset,
            @ForAll("frameworks") String framework,
            @ForAll("dates") String date,
            @ForAll("times") String time) {
        String path = asset + '_' + framework + '_' + date + '_' + time + ".json";
        assertThat(ReportContract.reportJsonIdentity(path)).isEmpty();
    }

    @Property(tries = 400)
    void facadeCanonicalBytesAndHashesIgnoreObjectInsertionOrder(@ForAll("smallMaps") Map<String, Long> values) {
        LinkedHashMap<String, Long> forward = new LinkedHashMap<>(values);
        List<Map.Entry<String, Long>> reversedEntries = new ArrayList<>(values.entrySet());
        Collections.reverse(reversedEntries);
        LinkedHashMap<String, Long> reverse = new LinkedHashMap<>();
        reversedEntries.forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));

        assertThat(ReportContract.canonicalReportPayload(forward))
                .isEqualTo(ReportContract.canonicalReportPayload(reverse));
        assertThat(ReportContract.reportHash(forward)).isEqualTo(ReportContract.reportHash(reverse));
    }

    @Property(tries = 300)
    void facadeStrictParserRejectsEveryDuplicateProperty(@ForAll("propertyNames") String property)
            throws JsonProcessingException {
        String key = MAPPER.writeValueAsString(property);
        String duplicate = '{' + key + ":1," + key + ":2}";

        assertThatThrownBy(() -> ReportContract.parseStrictJSON(duplicate, "report"))
                .isInstanceOf(StrictJsonException.class)
                .hasMessageContaining("duplicate key " + property);
    }

    @Provide
    Arbitrary<String> assets() {
        return Arbitraries.strings().withChars('a', 'z').numeric().ofMinLength(1).ofMaxLength(12);
    }

    @Provide
    Arbitrary<String> uppercaseAssets() {
        return Arbitraries.strings().withChars('A', 'Z').ofMinLength(1).ofMaxLength(12);
    }

    @Provide
    Arbitrary<String> frameworks() {
        return Arbitraries.of("fallen_knives", "flying_rocket");
    }

    @Provide
    Arbitrary<String> dates() {
        return Arbitraries.strings().numeric().ofLength(8);
    }

    @Provide
    Arbitrary<String> times() {
        return Arbitraries.strings().numeric().ofLength(4);
    }

    @Provide
    Arbitrary<String> prefixes() {
        return Arbitraries.strings().withChars('a', 'z').ofMinLength(1).ofMaxLength(16);
    }

    @Provide
    Arbitrary<Map<String, Long>> smallMaps() {
        return Arbitraries.maps(
                        Arbitraries.strings().withChars('a', 'z').ofMinLength(1).ofMaxLength(12),
                        Arbitraries.longs().between(-9_007_199_254_740_991L, 9_007_199_254_740_991L))
                .ofMaxSize(20);
    }

    @Provide
    Arbitrary<String> propertyNames() {
        return Arbitraries.strings().withChars('a', 'z').ofMinLength(1).ofMaxLength(20);
    }
}
