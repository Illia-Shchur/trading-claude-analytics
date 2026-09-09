package com.tradinganalytics.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
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

class CanonicalJsonPropertiesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 400)
    void objectInsertionOrderCannotChangeCanonicalBytes(@ForAll("smallMaps") Map<String, Long> values) {
        LinkedHashMap<String, Long> forward = new LinkedHashMap<>(values);
        List<Map.Entry<String, Long>> entries = new ArrayList<>(values.entrySet());
        Collections.reverse(entries);
        LinkedHashMap<String, Long> reverse = new LinkedHashMap<>();
        entries.forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));

        assertThat(CanonicalJson.canonicalBytes(forward)).isEqualTo(CanonicalJson.canonicalBytes(reverse));
        assertThat(Sha256.canonicalHex(forward)).isEqualTo(Sha256.canonicalHex(reverse));
    }

    @Property(tries = 400)
    void canonicalizationIsIdempotentAcrossStrictParsing(
            @ForAll("propertyNames") String key,
            @ForAll long integer,
            @ForAll boolean flag,
            @ForAll("safeStrings") String text) {
        ObjectNode value = MAPPER.createObjectNode();
        ArrayNode nested = value.putArray(key);
        nested.add(integer);
        nested.add(flag);
        nested.add(text);
        nested.addNull();

        String first = CanonicalJson.canonicalize(value);
        String second = CanonicalJson.canonicalize(StrictJson.parse(first));
        assertThat(second).isEqualTo(first);
    }

    @Provide
    Arbitrary<Map<String, Long>> smallMaps() {
        return Arbitraries.maps(
                        Arbitraries.strings().withChars(' ', '~').ofMinLength(1).ofMaxLength(12),
                        Arbitraries.longs().between(-9_007_199_254_740_991L, 9_007_199_254_740_991L))
                .ofMaxSize(20);
    }

    @Provide
    Arbitrary<String> propertyNames() {
        return Arbitraries.strings().withChars('a', 'z').ofMinLength(1).ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings().withChars(' ', '~').ofMaxLength(40);
    }
}
