package com.tradinganalytics.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StrictJsonTest {
    @ParameterizedTest(name = "accepts strict JSON: {0}")
    @MethodSource("validJson")
    void acceptsExactlyStrictJson(String input) {
        assertThat(StrictJson.parse(input, "fixture")).isNotNull();
    }

    @Test
    void parsesEveryJsonRootType() {
        assertThat(StrictJson.parse("null")).isEqualTo(NullNode.instance);
        assertThat(StrictJson.parse("true").booleanValue()).isTrue();
        assertThat(StrictJson.parse("-12.5e+2").doubleValue()).isEqualTo(-1250.0);
        assertThat(StrictJson.parse("\"text\"").textValue()).isEqualTo("text");
        assertThat(StrictJson.parse("[1,2]")).hasSize(2);
        assertThat(StrictJson.parse("{\"a\":1}").get("a").intValue()).isOne();
        assertThat(StrictJson.parseStrictJSON("{\"a\":1}")).isEqualTo(StrictJson.parse("{\"a\":1}"));
        assertThat(StrictJson.parseStrictJSON("{\"a\":1}", "document"))
                .isEqualTo(StrictJson.parse("{\"a\":1}"));
    }

    @ParameterizedTest(name = "rejects non-strict JSON: {0}")
    @MethodSource("invalidJson")
    void rejectsCommentsTrailingCommasExtensionsAndMalformedInput(String input) {
        assertThatThrownBy(() -> StrictJson.parse(input, "fixture"))
                .isInstanceOf(StrictJsonException.class)
                .hasMessageStartingWith("fixture: invalid strict JSON at offset ");
    }

    @Test
    void rejectsDuplicateKeysAtAnyDepthUsingDecodedKeyIdentity() {
        for (String input : new String[] {
                "{\"a\":1,\"a\":2}",
                "{\"outer\":{\"x\":1,\"x\":2}}",
                "{\"a\":1,\"\\u0061\":2}"
        }) {
            assertThatThrownBy(() -> StrictJson.parse(input, "document"))
                    .isInstanceOfSatisfying(StrictJsonException.class, exception -> {
                        assertThat(exception.offset()).isZero();
                        assertThat(exception.detail()).startsWith("duplicate key ");
                    });
        }
    }

    @Test
    void duplicateNamesInDifferentObjectsRemainValid() {
        assertThat(StrictJson.parse("[{\"a\":1},{\"a\":2}]")).hasSize(2);
    }

    @Test
    void acceptsUtf8BytesAndRejectsMalformedUtf8() {
        byte[] valid = "{\"currency\":\"€\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(StrictJson.parse(valid).get("currency").textValue()).isEqualTo("€");

        assertThatThrownBy(() -> StrictJson.parse(new byte[] {(byte) 0xc3, 0x28}, "bytes"))
                .isInstanceOf(StrictJsonException.class)
                .hasMessage("bytes: input must be UTF-8 text");
    }

    @Test
    void nullInputUsesTheNodeCompatibleTypeFailure() {
        assertThatThrownBy(() -> StrictJson.parse((String) null, "payload"))
                .isInstanceOf(StrictJsonException.class)
                .hasMessage("payload: input must be UTF-8 text");
        assertThatThrownBy(() -> StrictJson.parse((byte[]) null, "payload"))
                .isInstanceOf(StrictJsonException.class)
                .hasMessage("payload: input must be UTF-8 text");
    }

    @Test
    void blankLabelFallsBackToJson() {
        assertThatThrownBy(() -> StrictJson.parse("", " "))
                .hasMessageStartingWith("JSON: invalid strict JSON");
        assertThatThrownBy(() -> StrictJson.parse("", null))
                .hasMessageStartingWith("JSON: invalid strict JSON");
    }

    private static Stream<String> validJson() {
        return Stream.of(
                "{}",
                "[]",
                " null \n",
                "0",
                "-0",
                "1e30",
                "\"escaped \\\" \\\\ \\b \\f \\n \\r \\t\"",
                "{\"unicode\":\"€😀\",\"nested\":[true,false,null]}",
                "{\"loneSurrogateIsAcceptedByJsonParse\":\"\\uD800\"}");
    }

    private static Stream<Arguments> invalidJson() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("{\"a\":1,}"),
                Arguments.of("[1,]"),
                Arguments.of("// comment\n{}"),
                Arguments.of("{/* comment */\"a\":1}"),
                Arguments.of("{} {}"),
                Arguments.of("{\"a\":01}"),
                Arguments.of("{\"a\":NaN}"),
                Arguments.of("{\"a\":Infinity}"),
                Arguments.of("{'a':1}"),
                Arguments.of("{a:1}"),
                Arguments.of("[1,,2]"),
                Arguments.of("{\"a\":.5}"),
                Arguments.of("{\"a\":1.}"),
                Arguments.of("{\"a\":+1}"),
                Arguments.of("{\"a\":\"\\x41\"}"),
                Arguments.of("{\"a\":\"\u0001\"}"),
                Arguments.of("\ufeff{}"));
    }
}
