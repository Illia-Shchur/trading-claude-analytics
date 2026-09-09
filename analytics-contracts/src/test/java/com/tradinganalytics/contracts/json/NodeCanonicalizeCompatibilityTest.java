package com.tradinganalytics.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.hash.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Known answers captured from the repository-pinned npm canonicalize 4.0.0 oracle. */
class NodeCanonicalizeCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "npm vector {index}")
    @MethodSource("nodeVectors")
    void matchesPinnedNodePayloadAndBothByteHashes(
            String source,
            String expectedCanonical,
            String expectedSha256,
            String expectedNewlineSha256) {
        var value = StrictJson.parse(source);

        assertThat(CanonicalJson.canonicalize(value)).isEqualTo(expectedCanonical);
        assertThat(CanonicalJson.canonicalReportPayload(value)).isEqualTo(expectedCanonical);
        assertThat(CanonicalJson.canonicalBytes(value))
                .isEqualTo(expectedCanonical.getBytes(StandardCharsets.UTF_8));
        assertThat(Sha256.canonicalHex(value)).isEqualTo(expectedSha256);
        assertThat(Sha256.canonicalJsonHex(value)).isEqualTo(expectedNewlineSha256);
        assertThat(CanonicalJson.canonicalJson(value)).isEqualTo(expectedCanonical + "\n");
        assertThat(CanonicalJson.canonicalReportJson(value)).isEqualTo(expectedCanonical + "\n");
        assertThat(CanonicalJson.canonicalReportJSON(value)).isEqualTo(expectedCanonical + "\n");
        assertThat(CanonicalJson.canonicalJsonBytes(value))
                .isEqualTo((expectedCanonical + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "ECMAScript number {0}")
    @MethodSource("numberVectors")
    void matchesEcmascriptNumberSerialization(String source, String expected) {
        assertThat(CanonicalJson.canonicalizeJson(source)).isEqualTo(expected);
    }

    @Test
    void sortsObjectNamesByUtf16CodeUnitsWithoutUnicodeNormalization() {
        String source = "{\"1\":\"One\",\"\\r\":\"Carriage Return\",\"€\":\"Euro Sign\","
                + "\"😀\":\"Grinning Face\",\"ö\":\"Latin Small Letter O With Diaeresis\"}";
        assertThat(CanonicalJson.canonicalizeJson(source)).isEqualTo(
                "{\"\\r\":\"Carriage Return\",\"1\":\"One\","
                        + "\"ö\":\"Latin Small Letter O With Diaeresis\","
                        + "\"€\":\"Euro Sign\",\"😀\":\"Grinning Face\"}");

        assertThat(CanonicalJson.canonicalizeJson("[\"é\",\"é\"]"))
                .isEqualTo("[\"é\",\"é\"]");
    }

    @Test
    void rejectsNonFiniteNumbersAndLoneSurrogatesLikeNodeCanonicalize() {
        assertThatThrownBy(() -> CanonicalJson.canonicalize(DoubleNode.valueOf(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
        assertThatThrownBy(() -> CanonicalJson.canonicalize(DoubleNode.valueOf(Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Infinity");

        assertThatThrownBy(() -> CanonicalJson.canonicalize("\uD800"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lone surrogate");
        assertThatThrownBy(() -> CanonicalJson.canonicalize("\uD800x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lone surrogate");
        ObjectNode object = MAPPER.createObjectNode().put("\uDC00", 1);
        assertThatThrownBy(() -> CanonicalJson.canonicalize(object))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lone surrogate");
        assertThatThrownBy(() -> CanonicalJson.canonicalize(MissingNode.getInstance()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize(new POJONode(new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawAndCanonicalSha256HelpersHaveUnambiguousSemantics() {
        assertThat(Sha256.hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Sha256.hex("abc"));
        assertThat(Sha256.hash("abc")).isEqualTo(Sha256.hex("abc"));
        assertThat(Sha256.hash("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(Sha256.hex("abc"));
        assertThat(Sha256.hash(StrictJson.parse("{\"b\":1,\"a\":2}")))
                .isEqualTo(Sha256.hex("{\"a\":2,\"b\":1}"));
        assertThat(Sha256.isLowercaseHexDigest(Sha256.hex("abc"))).isTrue();
        assertThat(Sha256.isLowercaseHexDigest(Sha256.hex("abc").toUpperCase())).isFalse();
        assertThat(Sha256.isLowercaseHexDigest(null)).isFalse();
    }

    @Test
    void reportsNullAndUnsupportedBinaryInputsClearly() {
        assertThat(CanonicalJson.canonicalize(null)).isEqualTo("null");
        assertThatThrownBy(() -> CanonicalJson.canonicalize(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not canonicalizable");
        assertThatThrownBy(() -> Sha256.hex((String) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sha256.hex((byte[]) null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> nodeVectors() {
        return Stream.of(
                Arguments.of(
                        "null",
                        "null",
                        "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
                        "38e0b9de817f645c4bec37c0d4a3e58baecccb040f5718dc069a72c7385a0bed"),
                Arguments.of(
                        "true",
                        "true",
                        "b5bea41b6c623f7c09f1bf24dcae58ebab3c0cdd90ad966bc43a45b44867e12b",
                        "a17fcf0a2f50e2d495e4f90ce263410edc183add6c62699a2facbccf60410f74"),
                Arguments.of(
                        "false",
                        "false",
                        "fcbcf165908dd18a9e49f7ff27810176db8e9f63b4352213741664245224f8aa",
                        "2ed27c1421e6928dbe13dbfdb5c59e1045b30341fe7ebe05700006bc5ac572c0"),
                Arguments.of(
                        "{\"b\":1,\"a\":2}",
                        "{\"a\":2,\"b\":1}",
                        "d3626ac30a87e6f7a6428233b3c68299976865fa5508e4267c5415c76af7a772",
                        "81103aa69250ea56e887eaab3cd9bf363d341563f05d0676be389c3e40a72871"),
                Arguments.of(
                        "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,1e-27],"
                                + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\","
                                + "\"literals\":[null,true,false]}",
                        "{\"literals\":[null,true,false],"
                                + "\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                                + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}",
                        "2d5e01a318d0f0879ab568c4be289c8b1f64ef8921a53c6277d5e069978baacb",
                        "a7942e8aadd23087c351ebd1bfe3dec020285ade4719c095369fe99777d9b9e2"),
                Arguments.of(
                        "{\"negativeZero\":-0,\"min\":5e-324,\"max\":1.7976931348623157e308,"
                                + "\"belowFixed\":1e-7,\"fixed\":1e-6,"
                                + "\"belowExp\":999999999999999900000,\"exp\":1e21}",
                        "{\"belowExp\":999999999999999900000,\"belowFixed\":1e-7,"
                                + "\"exp\":1e+21,\"fixed\":0.000001,\"max\":1.7976931348623157e+308,"
                                + "\"min\":5e-324,\"negativeZero\":0}",
                        "f2a245a4f28eba4c4adec5bc879912f7b15093b1e12e388bcd02c81a33499e2e",
                        "e1737669132317f38b477c2f408968b0b629f0aaca21d70ba9dabf9e6078a4d2"));
    }

    private static Stream<Arguments> numberVectors() {
        return Stream.of(
                Arguments.of("0", "0"),
                Arguments.of("-0", "0"),
                Arguments.of("1", "1"),
                Arguments.of("-1", "-1"),
                Arguments.of("1.5", "1.5"),
                Arguments.of("5e-324", "5e-324"),
                Arguments.of("2.2250738585072014e-308", "2.2250738585072014e-308"),
                Arguments.of("1.7976931348623157e308", "1.7976931348623157e+308"),
                Arguments.of("1e-7", "1e-7"),
                Arguments.of("1e-6", "0.000001"),
                Arguments.of("0.0000012345678901234567", "0.0000012345678901234567"),
                Arguments.of("999999999999999900000", "999999999999999900000"),
                Arguments.of("1e21", "1e+21"),
                Arguments.of("1e30", "1e+30"),
                Arguments.of("-1e30", "-1e+30"),
                Arguments.of("333333333.33333329", "333333333.3333333"),
                Arguments.of("4.50", "4.5"),
                Arguments.of("2e-3", "0.002"),
                Arguments.of("1e-27", "1e-27"));
    }
}
