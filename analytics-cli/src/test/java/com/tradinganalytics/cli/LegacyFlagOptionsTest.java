package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyFlagOptionsTest {
    @Test
    void keepsRawAndSnakeCaseAliases() {
        assertThat(LegacyFlagOptions.parse("--rate-limit-ms", "250"))
                .containsEntry("rate-limit-ms", "250")
                .containsEntry("rate_limit_ms", "250");
    }

    @Test
    void representsValuelessFlagsAsTrue() {
        assertThat(LegacyFlagOptions.parse("--strict", "--out", "result.json"))
                .containsEntry("strict", true)
                .containsEntry("out", "result.json");
    }

    @Test
    void ignoresPositionalArgumentsAndUsesLastDuplicate() {
        assertThat(LegacyFlagOptions.parse(List.of("run", "--phase", "A", "input.json", "--phase", "B")))
                .containsExactlyEntriesOf(java.util.Map.of("phase", "B"));
    }

    @Test
    void preservesEmptyRawKeyLikeTheNodeParser() {
        assertThat(LegacyFlagOptions.parse("--"))
                .containsExactlyEntriesOf(java.util.Map.of("", true));
    }
}
