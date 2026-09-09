package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradinganalytics.cli.LegacyFlagOptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LegacyFlagOptionsParityTest {
    record Case(List<String> arguments, Map<String, Object> expected) {}

    static Stream<Case> argumentCases() {
        return Stream.of(
                new Case(List.of(), Map.of()),
                new Case(List.of("run", "input.json"), Map.of()),
                new Case(List.of("--strict"), Map.of("strict", true)),
                new Case(List.of("--rate-limit-ms", "250", "--strict"), linked(
                        "rate-limit-ms", "250", "rate_limit_ms", "250", "strict", true)),
                new Case(List.of("--phase", "A", "ignored", "--phase", "B"), Map.of("phase", "B")),
                new Case(List.of("--", "--next", "value"), linked("", true, "next", "value")),
                new Case(List.of("--a-b-c", "false", "--empty", ""), linked(
                        "a-b-c", "false", "a_b_c", "false", "empty", "")));
    }

    @ParameterizedTest
    @MethodSource("argumentCases")
    void javaParserMatchesFrozenCompatibilityVector(Case testCase) {
        assertThat(LegacyFlagOptions.parse(testCase.arguments())).isEqualTo(testCase.expected());
    }

    @Test
    void hyphenatedKeysRetainRawAndSnakeAliases() {
        assertThat(LegacyFlagOptions.parse(List.of("--raw-key", "value")))
                .isEqualTo(linked("raw-key", "value", "raw_key", "value"));
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
