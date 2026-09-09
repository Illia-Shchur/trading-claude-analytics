package com.tradinganalytics.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class StrictJsonPropertiesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 300)
    void acceptsEveryJsonEncodedString(@ForAll("safeStrings") String value) throws JsonProcessingException {
        String encoded = MAPPER.writeValueAsString(value);
        assertThat(StrictJson.parse(encoded).textValue()).isEqualTo(value);
    }

    @Property(tries = 300)
    void rejectsEveryRepeatedPropertyName(@ForAll("propertyNames") String name) throws JsonProcessingException {
        String key = MAPPER.writeValueAsString(name);
        String duplicate = "{" + key + ":1," + key + ":2}";
        assertThatThrownBy(() -> StrictJson.parse(duplicate))
                .isInstanceOf(StrictJsonException.class)
                .hasMessageContaining("duplicate key " + name);
    }

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .withChars(' ', '~')
                .ofMaxLength(80);
    }

    @Provide
    Arbitrary<String> propertyNames() {
        return Arbitraries.strings()
                .withChars('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20);
    }
}
