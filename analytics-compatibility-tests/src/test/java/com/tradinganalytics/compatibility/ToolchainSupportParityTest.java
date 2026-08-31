package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.lib.ToolchainSelftestContract;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolchainSupportParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();




    @Test
    void pureToolchainTailMatchesNodeOracle() throws Exception {
        JsonNode expected = frozen("/oracles/toolchain-support-main-v1.json");

        ObjectNode actual = JSON.createObjectNode();
        ArrayNode metadata = actual.putArray("metadata");
        metadata.add(ToolchainSupport.reportFileMeta("eth_flying_rocket_20260728_0540.md"));
        metadata.add(ToolchainSupport.reportFileMeta("calibration_ledger.md"));
        metadata.add(ToolchainSupport.reportFileMeta("btc_fallen_knives_20260230_1030.md"));
        ArrayNode times = actual.putArray("times");
        addNullable(times, ToolchainSupport.localToUtcISO("2026-07-11", "10:30"));
        addNullable(times, ToolchainSupport.localToUtcISO("2026-01-15", "10:30"));
        addNullable(times, ToolchainSupport.localToUtcISO("x", "x"));
        ArrayNode epochs = actual.putArray("epochs");
        for (String date : List.of("2026-07-10", "2026-07-11", "2026-07-27")) epochs.add(ToolchainSupport.schemaEpochOf(date));
        ArrayNode rubrics = actual.putArray("rubrics");
        addNullable(rubrics, ToolchainSupport.signalRubric("fallen_knives", null));
        addNullable(rubrics, ToolchainSupport.signalRubric("flying_rocket", "A"));
        addNullable(rubrics, ToolchainSupport.signalRubric("flying_rocket", "B"));
        addNullable(rubrics, ToolchainSupport.signalRubric("x", null));
        actual.set("legs", ToolchainSupport.legSpec("FR-B/1"));
        ArrayNode channels = actual.putArray("channels");
        channels.add(ToolchainSupport.inferChannel("flying_rocket", null, "2026-07-14"));
        channels.add(ToolchainSupport.inferChannel("flying_rocket", null, "2026-07-28"));
        channels.add(ToolchainSupport.inferChannel("fallen_knives", null, "2026-07-14"));
        ArrayNode discretion = actual.putArray("discretion");
        discretion.add(ToolchainSupport.inferDiscretion(JSON.readTree("{\"raw\":12,\"adjusted\":12}"), "2026-07-14"));
        discretion.add(ToolchainSupport.inferDiscretion(JSON.readTree("{\"raw\":11,\"adjusted\":11}"), "2026-07-28"));
        ArrayNode masks = actual.putArray("masks");
        masks.add(ToolchainSupport.gateMask(JSON.readTree("[1,2,3,4,6,7,8]")));
        masks.add(ToolchainSupport.gateMask(JSON.readTree("[0,1,10,\"x\"]")));
        masks.add(ToolchainSupport.gateMask(JSON.readTree("[]")));
        ArrayNode unlocks = actual.putArray("unlocks");
        unlocks.add(ToolchainSupport.unlockFor("flying_rocket", "B", 17.0, 17.0));
        unlocks.add(ToolchainSupport.unlockFor("fallen_knives", null, 17.0, 15.0));
        JsonNode feed = JSON.readTree("{\"schema\":\"feed/1\",\"generated_at\":\"a\",\"rows\":[1]}");
        JsonNode rerun = JSON.readTree("{\"schema\":\"feed/1\",\"generated_at\":\"b\",\"rows\":[1]}");
        ArrayNode changes = actual.putArray("changes");
        changes.add(ToolchainSupport.feedChanged(ToolchainSupport.canonicalJSON(feed), rerun));
        changes.add(ToolchainSupport.feedChanged(null, feed));
        changes.add(ToolchainSupport.feedChanged("{bad", feed));
        actual.put("digest", ToolchainSupport.snapshotDigestPayload(JSON.readTree(
                "{\"z\":{\"fetched_at\":\"now\",\"errors\":[\"x\"],\"value\":2},\"a\":{\"value\":1}}")));
        ArrayNode weekdays = actual.putArray("weekdays");
        weekdays.add(ToolchainSupport.weekdayOf("2026-01-19"));
        weekdays.add(ToolchainSupport.weekdayOf("2026-07-03"));
        ArrayNode calendar = actual.putArray("calendar");
        calendar.add(ToolchainSupport.isTradingDay("2026-07-03", "equity"));
        calendar.add(ToolchainSupport.isTradingDay("2026-07-03", "crypto"));
        calendar.add(JSON.valueToTree(ToolchainSupport.nextNTradingDays("2026-12-30", 3, "equity")));
        calendar.add(ToolchainSupport.tradingDaysBetween("2026-07-01", "2026-07-06", "equity"));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void scoringStopsAndFillDetectionMatchNodeOracle() throws Exception {
        JsonNode expected = frozen("/oracles/toolchain-support-tail-v1.json");
        ObjectNode actual = JSON.createObjectNode();

        ObjectNode constants = actual.putObject("constants");
        constants.set("EPOCHS", JSON.valueToTree(ToolchainSupport.EPOCHS));
        constants.set("FK_V_GATES", JSON.valueToTree(ToolchainSupport.FK_V_GATES));
        constants.set("FR_NONCRYPTO_CLASS", JSON.valueToTree(ToolchainSupport.FR_NONCRYPTO_CLASS));
        constants.set("FR_NONCRYPTO_NA", JSON.valueToTree(ToolchainSupport.FR_NONCRYPTO_NA));
        constants.set("FK_SCORE_UNLOCK", JSON.valueToTree(ToolchainSupport.FK_SCORE_UNLOCK));
        constants.set("FK_DISCRETION", JSON.valueToTree(ToolchainSupport.FK_DISCRETION));
        constants.put("FK_D5_MAX_STOP_DISTANCE_PCT", ToolchainSupport.FK_D5_MAX_STOP_DISTANCE_PCT);
        constants.set("FR_SCORE_UNLOCK", JSON.valueToTree(ToolchainSupport.FR_SCORE_UNLOCK));
        constants.set("FR_SCORE_UNLOCK_B", JSON.valueToTree(ToolchainSupport.FR_SCORE_UNLOCK_B));
        constants.set("FR_GATE_FLOORS", JSON.valueToTree(ToolchainSupport.FR_GATE_FLOORS));
        constants.set("FR_MECH_STOP_PCT", JSON.valueToTree(ToolchainSupport.FR_MECH_STOP_PCT));
        constants.put("FR_MIN_STOP_ADR_MULT", ToolchainSupport.FR_MIN_STOP_ADR_MULT);
        constants.put("FR_MAX_PER_ASSET_PCT", ToolchainSupport.FR_MAX_PER_ASSET_PCT);
        constants.set("FR_DISCRETION", JSON.valueToTree(ToolchainSupport.FR_DISCRETION));
        constants.set("FR_S5", JSON.valueToTree(ToolchainSupport.FR_S5));
        constants.set("FR_CHANNEL_B", JSON.valueToTree(ToolchainSupport.FR_CHANNEL_B));
        constants.put("POSITION_SNAPSHOT_SCHEMA", ToolchainSupport.POSITION_SNAPSHOT_SCHEMA);
        constants.set("POSITION_FRESHNESS", JSON.valueToTree(ToolchainSupport.POSITION_FRESHNESS));
        constants.set("LEDGER_ASSET_ALIASES", JSON.valueToTree(ToolchainSupport.LEDGER_ASSET_ALIASES));
        constants.put("SIGNAL_FEED_SCHEMA", ToolchainSupport.SIGNAL_FEED_SCHEMA);
        constants.put("REPORT_PHASE_REGISTRY_SCHEMA", ToolchainSupport.REPORT_PHASE_REGISTRY_SCHEMA);
        constants.put("REPORT_PHASE_REGISTRY_VERSION", ToolchainSupport.REPORT_PHASE_REGISTRY_VERSION);
        constants.set("REPORT_PHASE_DECISIONS", JSON.valueToTree(ToolchainSupport.REPORT_PHASE_DECISIONS));
        constants.set("REPORT_PHASE_INSTRUMENT_CLASSES",
                JSON.valueToTree(ToolchainSupport.REPORT_PHASE_INSTRUMENT_CLASSES));
        constants.set("FR_B_GATE_BASIS", JSON.valueToTree(ToolchainSupport.FR_B_GATE_BASIS));

        ArrayNode discretion = actual.putArray("discretion");
        for (Object value : new Object[]{null, NullNode.getInstance(), "0", -2.5, -2.0, -1.5, -0.25,
                0.0, 1.5, 2.0, 2.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            discretion.add(ToolchainSupport.discretionValid(value));
        }
        ArrayNode scoreAxes = actual.putArray("scoreAxes");
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.fkPhasesUnlockedByScore(17, 15)));
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.fkPhasesUnlockedByScore(17)));
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.frPhasesUnlockedByScore(19, 17)));
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.frPhasesUnlockedByScore(19)));
        scoreAxes.add(ToolchainSupport.mechanicalScore(10.5, "half-up"));
        scoreAxes.add(ToolchainSupport.mechanicalScore(10.5, "half-down"));
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.frUnlockLadder("A")));
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.frUnlockLadder("B")));
        scoreAxes.add(JSON.valueToTree(ToolchainSupport.frUnlockLadder("none")));

        ArrayNode stops = actual.putArray("stops");
        stops.add(ToolchainSupport.d5StopCheck("100", 90));
        stops.add(ToolchainSupport.d5StopCheck(100, 100));
        stops.add(ToolchainSupport.d5StopCheck(100, 85));
        stops.add(ToolchainSupport.d5StopCheck(100, 84.99));
        stops.add(ToolchainSupport.s5StopCheck("100", 105));
        stops.add(ToolchainSupport.s5StopCheck(100, 100));
        stops.add(ToolchainSupport.s5StopCheck(100, 106));
        stops.add(ToolchainSupport.s5StopCheck(100, 106.01));
        stops.add(ToolchainSupport.frStopBand(100));
        stops.add(ToolchainSupport.frStopBand(100, 3, "A", "1a"));
        stops.add(ToolchainSupport.frStopBand(100, 6, "B", "1a"));
        stops.add(ToolchainSupport.frStopBand(100, 3, "B", "3"));
        stops.add(ToolchainSupport.frStopBand("100", "3", "X", "2"));

        ArrayNode ratchets = actual.putArray("ratchets");
        ratchets.add(ToolchainSupport.ratchetCheck("100", 101));
        ratchets.add(ToolchainSupport.ratchetCheck(100, 101));
        ratchets.add(ToolchainSupport.ratchetCheck(100, 100));
        ratchets.add(ToolchainSupport.ratchetCheck(100, 90));
        ratchets.add(ToolchainSupport.ratchetCheck(100, 90, true, "catastrophic"));
        ratchets.add(ToolchainSupport.frRatchetCheck("100", 99));
        ratchets.add(ToolchainSupport.frRatchetCheck(100, 99));
        ratchets.add(ToolchainSupport.frRatchetCheck(100, 100));
        ratchets.add(ToolchainSupport.frRatchetCheck(100, 101, "time stop"));

        ArrayNode trancheResults = actual.putArray("tranches");
        List<JsonNode> tranches = new ArrayList<>();
        tranches.add(NullNode.getInstance());
        tranches.add(JSON.createObjectNode());
        tranches.add(JSON.createObjectNode().put("deployed", true));
        tranches.add(JSON.createObjectNode().put("deployed", "true"));
        tranches.add(JSON.createObjectNode().put("entry_price", 65000));
        tranches.add(JSON.createObjectNode().put("entry_price", "65000"));
        tranches.add(JSON.createObjectNode().put("entry", 64000));
        tranches.add(JSON.createObjectNode().put("entry_price", Double.NaN).put("entry", 63000));
        for (JsonNode tranche : tranches) {
            ObjectNode result = trancheResults.addObject();
            Double fill = ToolchainSupport.fillPrice(tranche);
            if (fill == null) result.set("fill", NullNode.instance); else putJsNumber(result, "fill", fill);
            result.put("live", ToolchainSupport.trancheFilled(tranche));
        }
        ArrayNode prose = actual.putArray("prose");
        for (Object value : new Object[]{12, "", "~65000 (MTM -1.2%)", "$4,650", "1640-1730 armed",
                "~65000 unfilled", "blended basis 65000", "zone only"}) {
            prose.add(ToolchainSupport.entryLooksLikeFill(value));
        }
        ArrayNode facades = actual.putArray("facades");
        facades.add(ToolchainSupport.fk.sentimentBand(10));
        facades.add(ToolchainSupport.fk.momentumBand(31, true));
        facades.add(ToolchainSupport.fr.mvrvZBand(5));
        addJsNumber(facades, ToolchainSupport.fr.annualizedFunding(.01));
        facades.add(ToolchainSupport.frB.rallyBand(35));
        facades.add(ToolchainSupport.frB.momentumBand(66, 50.0));
        addJsNumber(facades, ToolchainSupport._internal.round2(1.005));

        assertThat(jsonEquivalent(actual, expected))
                .as("Node and Java JSON differ at %s%nexpected: %s%nactual: %s",
                        firstJsonDifference(actual, expected, "$"), expected, actual)
                .isTrue();
    }

    @Test
    void deterministicPropertyGridMatchesNodeForEveryScoreAndStopBoundary() throws Exception {
        String expectedDigest = frozen("/oracles/toolchain-support-property-digest-v1.json").asText();
        ArrayNode actual = JSON.createArrayNode();
        String[] phases = {"1a", "1b", "2", "3", "x"};
        String[] channels = {"A", "B", "X"};
        for (int index = 0; index <= 240; index++) {
            double adjusted = (index - 120) / 4.0;
            double mechanical = ((index * 37) % 161 - 40) / 4.0;
            double fill = 100 + index;
            double longStop = fill * (80 + index % 31) / 100.0;
            double shortStop = fill * (95 + index % 21) / 100.0;
            String channel = channels[index % channels.length];
            String phase = phases[index % phases.length];
            ObjectNode row = actual.addObject();
            row.set("discretion", ToolchainSupport.discretionValid(adjusted));
            row.set("fk", JSON.valueToTree(ToolchainSupport.fkPhasesUnlockedByScore(adjusted, mechanical)));
            row.set("fr", JSON.valueToTree(ToolchainSupport.frPhasesUnlockedByScore(adjusted, mechanical)));
            row.set("d5", ToolchainSupport.d5StopCheck(fill, longStop));
            row.set("s5", ToolchainSupport.s5StopCheck(fill, shortStop));
            row.set("longRatchet", ToolchainSupport.ratchetCheck(fill, longStop,
                    index % 7 == 0, index % 2 == 1 ? "stop" : "catastrophic"));
            row.set("shortRatchet", ToolchainSupport.frRatchetCheck(fill, shortStop,
                    index % 2 == 1 ? "stop" : "time stop"));
            row.set("band", ToolchainSupport.frStopBand(fill, index % 10 + .25, channel, phase));
        }
        String actualDigest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ToolchainSupport.canonicalJSON(actual).getBytes(StandardCharsets.UTF_8)));
        assertThat(actualDigest).isEqualTo(expectedDigest);
    }

    @Test
    void mechanicalGateInputsFailClosedAgainstStringAndPlaceholderSpoofing() {
        ObjectNode spoofed = JSON.createObjectNode()
                .put("deployed", "true").put("entry_price", "65000").put("entry", "~65000 armed");
        assertThat(ToolchainSupport.fillPrice(spoofed)).isNull();
        assertThat(ToolchainSupport.trancheFilled(spoofed)).isFalse();
        assertThat(ToolchainSupport.entryLooksLikeFill(spoofed.get("entry")).path("fill_like").asBoolean()).isFalse();
        assertThat(ToolchainSupport.d5StopCheck("65000", 60000).path("pass").asBoolean()).isFalse();
        assertThat(ToolchainSupport.s5StopCheck(65000, "66000").path("pass").asBoolean()).isFalse();
        assertThat(ToolchainSupport.discretionValid("0").path("ok").asBoolean()).isFalse();

        assertThatThrownBy(() -> ToolchainSupport.FK_SCORE_UNLOCK.put("p3", 0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ToolchainSupport.FR_MECH_STOP_PCT.get("A").put("3", 100))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ToolchainSupport.FR_NONCRYPTO_NA.get("metals").add(1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dynamicallyInventoriesEveryLibExportAndEveryExecutedSelftestVector() throws Exception {
        JsonNode surface = frozen("/oracles/toolchain-support-surface-v1.json");
        Set<String> names = new java.util.LinkedHashSet<>();
        int functions = 0;
        for (JsonNode entry : surface) {
            names.add(entry.path("name").asText());
            if ("function".equals(entry.path("type").asText())) functions++;
        }
        assertThat(names).containsExactlyInAnyOrderElementsOf(ToolchainSelftestContract.LIB_EXPORT_NAMES);
        assertThat(names).hasSize(ToolchainSelftestContract.LIB_EXPORT_COUNT);
        assertThat(functions).isEqualTo(ToolchainSelftestContract.LIB_FUNCTION_EXPORT_COUNT);
        assertThat(surface.size() - functions).isEqualTo(ToolchainSelftestContract.LIB_VALUE_EXPORT_COUNT);
        assertThat(ToolchainSelftestContract.EXPLICIT_FACADE_ALIASES.keySet()).isSubsetOf(names);
        assertThat(ToolchainSelftestContract.REPOSITORY_FACADE_OWNERS).hasSize(10).doesNotHaveDuplicates();

        JsonNode inventory = frozen("/oracles/toolchain-selftest-inventory-v1.json");
        assertThat(inventory.path("eq").asInt()).isEqualTo(ToolchainSelftestContract.SELFTEST_EQ_COUNT);
        assertThat(inventory.path("ok").asInt()).isEqualTo(ToolchainSelftestContract.SELFTEST_OK_COUNT);
        assertThat(inventory.path("total").asInt()).isEqualTo(ToolchainSelftestContract.SELFTEST_VECTOR_COUNT);
        assertThat(inventory.path("unique").asInt()).isEqualTo(ToolchainSelftestContract.SELFTEST_UNIQUE_NAME_COUNT);
        assertThat(inventory.path("duplicates")).hasSize(ToolchainSelftestContract.SELFTEST_DUPLICATE_NAMES.size());
        for (JsonNode duplicate : inventory.path("duplicates")) {
            assertThat(ToolchainSelftestContract.SELFTEST_DUPLICATE_NAMES)
                    .containsEntry(duplicate.path("name").asText(), duplicate.path("count").asInt());
        }
    }

    private static JsonNode frozen(String resource) throws Exception {
        try (InputStream stream = ToolchainSupportParityTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return JSON.readTree(stream);
        }
    }

    private static void putJsNumber(ObjectNode object, String key, double value) {
        if (value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            object.put(key, (int) value);
        } else if (value == Math.rint(value) && Math.abs(value) < 1e21) object.put(key, (long) value);
        else object.put(key, value);
    }

    private static void addJsNumber(ArrayNode array, double value) {
        if (value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            array.add((int) value);
        } else if (value == Math.rint(value) && Math.abs(value) < 1e21) array.add((long) value);
        else array.add(value);
    }

    private static boolean jsonEquivalent(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!jsonEquivalent(left.get(index), right.get(index))) return false;
            }
            return true;
        }
        if (left.isObject() && right.isObject()) {
            Set<String> leftNames = new java.util.HashSet<>();
            Set<String> rightNames = new java.util.HashSet<>();
            left.fieldNames().forEachRemaining(leftNames::add);
            right.fieldNames().forEachRemaining(rightNames::add);
            if (!leftNames.equals(rightNames)) return false;
            for (String name : leftNames) if (!jsonEquivalent(left.get(name), right.get(name))) return false;
            return true;
        }
        return left.equals(right);
    }

    private static String firstJsonDifference(JsonNode left, JsonNode right, String path) {
        if (left == null || right == null) return left == right ? null : path + " (null mismatch)";
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0 ? null
                    : path + " (number " + left + " != " + right + ")";
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return path + " (array size " + left.size() + " != " + right.size() + ")";
            for (int index = 0; index < left.size(); index++) {
                String difference = firstJsonDifference(left.get(index), right.get(index), path + "[" + index + "]");
                if (difference != null) return difference;
            }
            return null;
        }
        if (left.isObject() && right.isObject()) {
            Set<String> names = new java.util.HashSet<>();
            left.fieldNames().forEachRemaining(names::add);
            Set<String> otherNames = new java.util.HashSet<>();
            right.fieldNames().forEachRemaining(otherNames::add);
            if (!names.equals(otherNames)) return path + " (fields " + names + " != " + otherNames + ")";
            for (String name : names) {
                String difference = firstJsonDifference(left.get(name), right.get(name), path + "." + name);
                if (difference != null) return difference;
            }
            return null;
        }
        return left.equals(right) ? null : path + " (" + left.getNodeType() + " " + left
                + " != " + right.getNodeType() + " " + right + ")";
    }

    private static void addNullable(ArrayNode array, String value) {
        if (value == null) array.addNull(); else array.add(value);
    }
}
