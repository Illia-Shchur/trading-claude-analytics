package com.tradinganalytics.core.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.PrettyCanonicalJson;
import com.tradinganalytics.core.compute.ComputeMath;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-cutting deterministic helpers from the non-market-data tail of
 * {@code tools/lib.mjs}.  These contracts are shared by reporting, corpus and
 * snapshot commands and intentionally contain no filesystem or network I/O.
 */
public final class ToolchainSupport {
    public static final Map<String, String> EPOCHS = orderedMap(
            Map.entry("machineBlock", "2026-07-11"),
            Map.entry("discretionAndTwoChannel", "2026-07-27"),
            Map.entry("entryPrice", "2026-07-29"),
            Map.entry("companionFR", "2026-08-03"),
            Map.entry("nonCryptoSchema", "2026-08-05"),
            Map.entry("gateMeasurement", "2026-08-07"));
    public static final String MACHINE_BLOCK_EPOCH = "2026-07-11";
    public static final String DISCRETION_EPOCH = "2026-07-27";
    public static final String ENTRY_PRICE_EPOCH = "2026-07-29";
    public static final String COMPANION_FR_EPOCH = "2026-08-03";
    public static final String NONCRYPTO_SCHEMA_EPOCH = "2026-08-05";
    public static final String GATE_MEASUREMENT_EPOCH = "2026-08-07";
    public static final Map<Integer, String> FR_B_GATE_BASIS = orderedMap(
            Map.entry(1, "current_session_high"),
            Map.entry(2, "low_to_current"),
            Map.entry(5, "bounce_window"));
    public static final String REPORT_ZONE = "America/New_York";
    public static final Pattern REPORT_FILE_RE = Pattern.compile(
            "^([a-z0-9]+)_(fallen_knives|flying_rocket)_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})\\.md$");
    public static final Set<String> US_MARKET_HOLIDAYS = Set.of(
            "2025-01-01", "2025-01-20", "2025-02-17", "2025-04-18", "2025-05-26", "2025-06-19",
            "2025-07-04", "2025-09-01", "2025-11-27", "2025-12-25",
            "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19",
            "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
            "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31", "2027-06-18",
            "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24");

    public static final Map<String, String> ROUNDING = ComputeMath.ROUNDING;
    public static final List<Integer> FK_V_GATES = List.of(1, 2, 3, 4, 7, 8);
    public static final Map<String, String> FR_NONCRYPTO_CLASS = orderedMap(
            Map.entry("spx", "equity_index"), Map.entry("sp500", "equity_index"),
            Map.entry("ndx", "equity_index"), Map.entry("nasdaq", "equity_index"),
            Map.entry("gold", "metals"), Map.entry("xau", "metals"), Map.entry("paxg", "metals"),
            Map.entry("silver", "metals"), Map.entry("xag", "metals"));
    public static final Map<String, List<Integer>> FR_NONCRYPTO_NA = orderedMap(
            Map.entry("equity_index", List.of(4, 6, 9)), Map.entry("metals", List.of(4, 6, 9)));
    public static final Map<String, Integer> FK_SCORE_UNLOCK = orderedMap(
            Map.entry("p1a", 8), Map.entry("p1b", 11), Map.entry("p2", 15), Map.entry("p3", 17));
    public static final Map<String, Number> FK_DISCRETION = orderedMap(
            Map.entry("max", 2), Map.entry("step", 0.5));
    public static final int FK_D5_MAX_STOP_DISTANCE_PCT = 15;
    public static final Map<String, Integer> FR_SCORE_UNLOCK = orderedMap(
            Map.entry("p1a", 11), Map.entry("p1b", 13), Map.entry("p2", 15), Map.entry("p3", 19));
    public static final Map<String, Integer> FR_SCORE_UNLOCK_B = orderedMap(
            Map.entry("p1a", 13), Map.entry("p1b", 15), Map.entry("p2", 17));
    public static final Map<String, Integer> FR_GATE_FLOORS = orderedMap(
            Map.entry("p1a", 3), Map.entry("p1b", 5), Map.entry("p2", 6), Map.entry("p3", 8));
    public static final Map<String, Map<String, Integer>> FR_MECH_STOP_PCT = orderedMap(
            Map.entry("A", orderedMap(Map.entry("1a", 8), Map.entry("1b", 10),
                    Map.entry("2", 12), Map.entry("3", 15))),
            Map.entry("B", orderedMap(Map.entry("1a", 6), Map.entry("1b", 6), Map.entry("2", 8))));
    public static final double FR_MIN_STOP_ADR_MULT = 1.5;
    public static final int FR_MAX_PER_ASSET_PCT = 30;
    public static final Map<String, Number> FR_DISCRETION = FK_DISCRETION;
    public static final Map<String, Integer> FR_S5 = orderedMap(
            Map.entry("maxStopPct", 6), Map.entry("maxTimeStopDays", 14), Map.entry("maxBookPct", 20));
    public static final Map<String, Object> FR_CHANNEL_B = orderedMap(
            Map.entry("maxBookPct", 30),
            Map.entry("maxTimeStopDays", orderedMap(
                    Map.entry("p1a", 21), Map.entry("p1b", 21), Map.entry("p2", 28))));
    public static final String POSITION_SNAPSHOT_SCHEMA = "position-snapshot/1";
    public static final Map<String, Integer> POSITION_FRESHNESS = orderedMap(
            Map.entry("stale", 720), Map.entry("expired", 4320));
    public static final Map<String, Map<String, String>> LEDGER_ASSET_ALIASES = Map.of(
            "GOLD", orderedMap(
                    Map.entry("ledger", "PAXG"),
                    Map.entry("note", "Position read from PAXG, tokenized gold. PAXG is a PROXY for spot gold — fully backed and tracking XAU ~1:1, but carrying issuer/custody counterparty risk that spot gold does not, and able to trade at a premium or discount. Quantity and cost basis are real; treat the instrument as PAXG, not bullion. Canonical gold SPOT still comes from Hard Rule 1 sources, never from this mark.")));
    public static final String SIGNAL_FEED_SCHEMA = "signal-feed/1";
    public static final String REPORT_PHASE_REGISTRY_SCHEMA = "report-phase-registry/1";
    public static final int REPORT_PHASE_REGISTRY_VERSION = 1;
    public static final List<String> REPORT_PHASE_DECISIONS =
            List.of("AUTHORIZED", "LOCKED", "STAND_DOWN", "UNVERIFIED");
    public static final List<String> REPORT_PHASE_INSTRUMENT_CLASSES =
            List.of("crypto", "non_crypto_derivative", "non_crypto_cash");

    /** Exact-member facades for the three object exports and the private test hook in {@code lib.mjs}. */
    public static final FkBands fk = new FkBands();
    public static final FrBands fr = new FrBands();
    public static final FrBBands frB = new FrBBands();
    public static final Internal _internal = new Internal();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern TIME = Pattern.compile("^(\\d{2}):(\\d{2})$");
    private static final Map<String, List<LegSpec>> LEG_SPECS = legSpecs();
    private static final Pattern FILL_NEGATIVE = Pattern.compile(
            "\\b(unfilled|dry|frozen|prospective|armed|staged|not filled|no fill)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL_BARE = Pattern.compile(
            "^\\s*~?\\s*\\$?\\s*[\\d,]+(?:\\.\\d+)?\\s*(?:\\(|$)");
    private static final Pattern FILL_MTM = Pattern.compile("\\b(MTM|blended)\\b", Pattern.CASE_INSENSITIVE);

    private ToolchainSupport() {
    }

    public static String localToUtcISO(String date, String time) {
        return localToUtcISO(date, time, REPORT_ZONE);
    }

    /** Exact fixed-point conversion used by the Node implementation. */
    public static String localToUtcISO(String date, String time, String zone) {
        Matcher dateMatch = DATE.matcher(String.valueOf(date));
        Matcher timeMatch = TIME.matcher(String.valueOf(time));
        if (!dateMatch.matches() || !timeMatch.matches()) return null;
        try {
            int year = Integer.parseInt(dateMatch.group(1));
            int month = Integer.parseInt(dateMatch.group(2));
            int day = Integer.parseInt(dateMatch.group(3));
            int hour = Integer.parseInt(timeMatch.group(1));
            int minute = Integer.parseInt(timeMatch.group(2));
            if (hour > 23 || minute > 59) return null;
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute);
            Instant target = local.toInstant(java.time.ZoneOffset.UTC);
            Instant cursor = target;
            ZoneId zoneId = ZoneId.of(zone);
            for (int iteration = 0; iteration < 3; iteration++) {
                int offsetSeconds = zoneId.getRules().getOffset(cursor).getTotalSeconds();
                Instant next = target.minusSeconds(offsetSeconds);
                if (next.equals(cursor)) break;
                cursor = next;
            }
            return DateTimeFormatter.ISO_INSTANT.format(cursor);
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    public static String schemaEpochOf(String date) {
        String value = String.valueOf(date);
        if (value.compareTo(DISCRETION_EPOCH) >= 0) return "discretion_and_two_channel";
        if (value.compareTo(MACHINE_BLOCK_EPOCH) >= 0) return "machine_block";
        return "pre_machine_block";
    }

    public static ObjectNode reportFileMeta(String name) {
        String file = String.valueOf(name);
        Matcher matcher = REPORT_FILE_RE.matcher(file);
        if (!matcher.matches()) {
            ObjectNode failed = NODES.objectNode();
            failed.put("ok", false);
            failed.put("file", file);
            failed.put("reason", "filename does not match <asset>_<framework>_YYYYMMDD_HHMM.md");
            return failed;
        }
        String asset = matcher.group(1);
        String framework = matcher.group(2);
        String date = matcher.group(3) + "-" + matcher.group(4) + "-" + matcher.group(5);
        String localTime = matcher.group(6) + ":" + matcher.group(7);
        String atUtc = localToUtcISO(date, localTime);
        if (atUtc == null) {
            ObjectNode failed = NODES.objectNode();
            failed.put("ok", false);
            failed.put("file", file);
            failed.put("reason", "filename encodes an impossible date/time (" + date + " " + localTime + ")");
            return failed;
        }
        ObjectNode output = NODES.objectNode();
        output.put("ok", true);
        output.put("file", file);
        output.put("asset", asset.toUpperCase(Locale.ROOT));
        output.put("framework", framework);
        output.put("date", date);
        output.put("local_time", localTime);
        output.put("zone", REPORT_ZONE);
        output.put("at_utc", atUtc);
        output.put("schema_epoch", schemaEpochOf(date));
        return output;
    }

    public static String signalRubric(String framework, String channel) {
        if ("fallen_knives".equals(framework)) return "FK/1";
        if (!"flying_rocket".equals(framework)) return null;
        return "B".equals(channel) ? "FR-B/1" : "FR-A/1";
    }

    public static ArrayNode legSpec(String rubric) {
        ArrayNode output = NODES.arrayNode();
        for (LegSpec leg : LEG_SPECS.getOrDefault(rubric, List.of())) {
            ObjectNode value = output.addObject();
            value.put("ordinal", leg.ordinal());
            value.put("block_key", leg.blockKey());
            value.put("rubric_name", leg.rubricName());
            value.put("min", leg.min());
            value.put("max", leg.max());
        }
        return output;
    }

    public static ObjectNode inferChannel(String framework, String channel, String date) {
        ObjectNode output = NODES.objectNode();
        if ("fallen_knives".equals(framework)) {
            output.set("channel", NullNode.instance);
            output.put("inferred", false);
            output.put("basis", "Fallen Knives has no channel dimension");
            return output;
        }
        if ("A".equals(channel) || "B".equals(channel) || "none".equals(channel)) {
            output.put("channel", channel);
            output.put("inferred", false);
            output.put("basis", "declared in the machine block");
            return output;
        }
        if (String.valueOf(date).compareTo(DISCRETION_EPOCH) < 0) {
            output.put("channel", "A");
            output.put("inferred", true);
            output.put("basis", "report dated " + date + " predates the two-channel architecture ("
                    + DISCRETION_EPOCH + "); Channel B did not exist, so the score was computed under the §4A rubric");
            return output;
        }
        output.set("channel", NullNode.instance);
        output.put("inferred", false);
        String renderedChannel = channel == null ? "undefined" : PrettyCanonicalJson.write(JSON.valueToTree(channel)).trim();
        output.put("basis", "channel is required on/after " + DISCRETION_EPOCH
                + " but is absent or invalid (" + renderedChannel + ") — lint-report.mjs errors on this");
        return output;
    }

    public static ObjectNode inferDiscretion(JsonNode score, String date) {
        JsonNode value = score == null || !score.isObject() ? NODES.objectNode() : score;
        boolean pre = String.valueOf(date).compareTo(DISCRETION_EPOCH) < 0;
        JsonNode discretionary = value.get("discretionary");
        JsonNode mechanical = value.get("mechanical");
        JsonNode raw = value.get("raw");
        boolean hasDiscretion = discretionary != null && discretionary.isNumber();
        boolean hasMechanical = mechanical != null && mechanical.isNumber();
        boolean hasRaw = raw != null && raw.isNumber();
        ObjectNode output = NODES.objectNode();
        output.set("discretionary", hasDiscretion ? discretionary.deepCopy()
                : pre ? NODES.numberNode(0) : NullNode.instance);
        output.put("discretionary_inferred", !hasDiscretion && pre);
        output.set("mechanical", hasMechanical ? mechanical.deepCopy()
                : pre && hasRaw ? raw.deepCopy() : NullNode.instance);
        output.put("mechanical_inferred", !hasMechanical && pre && hasRaw);
        String historicalBasis = "report dated " + date + " predates the Analyst Discretion Layer ("
                + DISCRETION_EPOCH + "); no discretionary term existed, so discretion was structurally 0 and mechanical = raw";
        output.put("basis", (!hasDiscretion || !hasMechanical) && pre
                ? historicalBasis : "declared in the machine block");
        return output;
    }

    /** Validate the mandatory half-point analyst-discretion term (FK D1 / FR S1). */
    public static ObjectNode discretionValid(Object value) {
        Double number = finiteNumber(value);
        if (number == null) {
            return validation(false, "missing or non-numeric (write 0 when no adjustment was taken)");
        }
        if (Math.abs(number) > FK_DISCRETION.get("max").doubleValue()) {
            return validation(false, "|" + numberText(number) + "| exceeds the ±"
                    + numberText(FK_DISCRETION.get("max").doubleValue()) + " bound (D1)");
        }
        double scaled = number / FK_DISCRETION.get("step").doubleValue();
        if (Math.abs(scaled - Math.round(scaled)) > 1e-9) {
            return validation(false, numberText(number) + " is not on the "
                    + numberText(FK_DISCRETION.get("step").doubleValue()) + " step (D1)");
        }
        return validation(true, null);
    }

    public static List<String> fkPhasesUnlockedByScore(double adjusted) {
        return fkPhasesUnlockedByScore(adjusted, adjusted);
    }

    public static List<String> fkPhasesUnlockedByScore(double adjusted, double mechanical) {
        return phasesUnlocked(FK_SCORE_UNLOCK, adjusted, mechanical);
    }

    public static int mechanicalScore(double legSum, String convention) {
        return ComputeMath.roundScore(legSum, convention);
    }

    /** FK D5 stop-distance rule. Inputs intentionally use JS's number-type check, not coercion. */
    public static ObjectNode d5StopCheck(Object fillValue, Object stopValue) {
        Double fill = numberType(fillValue);
        Double stop = numberType(stopValue);
        if (fill == null || stop == null) return passValidation(false, "fill and stop must both be numbers");
        double floor = ComputeMath.round2(fill * (1 - FK_D5_MAX_STOP_DISTANCE_PCT / 100.0));
        double distancePercent = ComputeMath.round2((1 - stop / fill) * 100);
        ObjectNode output = NODES.objectNode();
        output.put("pass", false);
        putNumber(output, "floor", floor);
        putNumber(output, "distance_pct", distancePercent);
        if (stop >= fill) {
            output.put("reason", "stop is at or above the fill");
            return output;
        }
        boolean pass = stop >= floor;
        output.put("pass", pass);
        if (pass) output.set("reason", NullNode.instance);
        else output.put("reason", "stop sits " + numberText(distancePercent)
                + "% below fill — deeper than the " + FK_D5_MAX_STOP_DISTANCE_PCT + "% D5 limit");
        return output;
    }

    public static ObjectNode ratchetCheck(Object oldValue, Object newValue) {
        return ratchetCheck(oldValue, newValue, false, "stop");
    }

    /** FK D6 long-side ratchet, including its sole named-zone exception. */
    public static ObjectNode ratchetCheck(
            Object oldValue, Object newValue, boolean priorNamedZone, String tier) {
        Double oldNumber = numberType(oldValue);
        Double newNumber = numberType(newValue);
        if (oldNumber == null || newNumber == null) return passValidation(false, "both values must be numbers");
        ObjectNode output = NODES.objectNode();
        if (newNumber >= oldNumber) {
            output.put("pass", true);
            output.put("direction", newNumber.doubleValue() == oldNumber.doubleValue()
                    ? "unchanged" : "toward price");
            output.set("reason", NullNode.instance);
            return output;
        }
        if (priorNamedZone && "catastrophic".equals(tier)) {
            output.put("pass", true);
            output.put("direction", "away from price (permitted exception)");
            output.put("reason", "catastrophic re-anchor onto a prior-report-named deeper zone — must be atomic and cited");
            return output;
        }
        output.put("pass", false);
        output.put("direction", "away from price");
        output.put("reason", "D6 ratchet: " + String.valueOf(tier) + " " + numberText(oldNumber)
                + " → " + numberText(newNumber) + " widens the stop — prohibited, not merely disclosable");
        return output;
    }

    public static Map<String, Integer> frUnlockLadder(String channel) {
        return "B".equals(channel) ? FR_SCORE_UNLOCK_B : FR_SCORE_UNLOCK;
    }

    public static ObjectNode frStopBand(Object fillValue) {
        return frStopBand(fillValue, null, "A", "1a");
    }

    /** FR mechanical stop ceiling and optional 1.5x ADR floor. */
    public static ObjectNode frStopBand(Object fillValue, Object adr5Value, String channel, String phase) {
        String resolvedChannel = channel == null ? "A" : channel;
        String resolvedPhase = phase == null ? "1a" : phase;
        Map<String, Integer> phaseBands = FR_MECH_STOP_PCT.getOrDefault(resolvedChannel, FR_MECH_STOP_PCT.get("A"));
        Integer ceilingPercent = phaseBands.get(resolvedPhase);
        ObjectNode output = NODES.objectNode();
        if (ceilingPercent == null) {
            output.put("ok", false);
            output.put("reason", "phase " + resolvedPhase + " is unreachable in Channel " + resolvedChannel);
            return output;
        }
        double fill = jsArithmeticNumber(fillValue);
        double ceiling = ComputeMath.round2(fill * (1 + ceilingPercent / 100.0));
        output.put("ok", true);
        putNumber(output, "ceiling", ceiling);
        output.put("ceiling_pct", ceilingPercent);
        if (adr5Value == null || adr5Value instanceof JsonNode node && node.isNull()) {
            output.set("floor", NullNode.instance);
            output.set("floor_pct", NullNode.instance);
            output.put("reason", "ADR(5) not supplied — minimum-distance rule not checkable");
            return output;
        }
        double adr5 = jsArithmeticNumber(adr5Value);
        double floorPercent = ComputeMath.round2((FR_MIN_STOP_ADR_MULT * adr5 / fill) * 100);
        if (floorPercent > ceilingPercent) {
            output.put("ok", false);
            putNumber(output, "floor_pct", floorPercent);
            output.remove("floor");
            output.put("reason", "1.5×ADR(5) = " + numberText(floorPercent) + "% exceeds the "
                    + ceilingPercent + "% phase ceiling — tape too volatile for this phase, no trade");
            return output;
        }
        putNumber(output, "floor", ComputeMath.round2(fill * (1 + floorPercent / 100.0)));
        putNumber(output, "floor_pct", floorPercent);
        output.set("reason", NullNode.instance);
        return output;
    }

    public static List<String> frPhasesUnlockedByScore(double adjusted) {
        return frPhasesUnlockedByScore(adjusted, adjusted);
    }

    public static List<String> frPhasesUnlockedByScore(double adjusted, double mechanical) {
        return phasesUnlocked(FR_SCORE_UNLOCK, adjusted, mechanical);
    }

    /** FR S5 discretionary-stop tax. */
    public static ObjectNode s5StopCheck(Object fillValue, Object stopValue) {
        Double fill = numberType(fillValue);
        Double stop = numberType(stopValue);
        if (fill == null || stop == null) return passValidation(false, "fill and stop must both be numbers");
        int maxStopPercent = FR_S5.get("maxStopPct");
        double ceiling = ComputeMath.round2(fill * (1 + maxStopPercent / 100.0));
        double distancePercent = ComputeMath.round2((stop / fill - 1) * 100);
        ObjectNode output = NODES.objectNode();
        output.put("pass", false);
        putNumber(output, "ceiling", ceiling);
        putNumber(output, "distance_pct", distancePercent);
        if (stop <= fill) {
            output.put("reason", "stop is at or below the fill — a short stop sits ABOVE entry");
            return output;
        }
        boolean pass = stop <= ceiling;
        output.put("pass", pass);
        if (pass) output.set("reason", NullNode.instance);
        else output.put("reason", "stop sits " + numberText(distancePercent)
                + "% above fill — wider than the " + maxStopPercent + "% S5 limit");
        return output;
    }

    public static ObjectNode frRatchetCheck(Object oldValue, Object newValue) {
        return frRatchetCheck(oldValue, newValue, "stop");
    }

    /** FR S6 short-side ratchet; unlike FK D6, there is no widening exception. */
    public static ObjectNode frRatchetCheck(Object oldValue, Object newValue, String tier) {
        Double oldNumber = numberType(oldValue);
        Double newNumber = numberType(newValue);
        if (oldNumber == null || newNumber == null) return passValidation(false, "both values must be numbers");
        ObjectNode output = NODES.objectNode();
        if (newNumber <= oldNumber) {
            output.put("pass", true);
            output.put("direction", newNumber.doubleValue() == oldNumber.doubleValue()
                    ? "unchanged" : "toward price");
            output.set("reason", NullNode.instance);
            return output;
        }
        output.put("pass", false);
        output.put("direction", "away from price");
        output.put("reason", "S6 ratchet: " + String.valueOf(tier) + " " + numberText(oldNumber)
                + " → " + numberText(newNumber) + " widens the stop — prohibited, not merely disclosable");
        return output;
    }

    public static int gateMask(JsonNode passed) {
        int mask = 0;
        if (passed == null || !passed.isArray()) return mask;
        for (JsonNode gate : passed) {
            if (gate.isIntegralNumber()) {
                int value = gate.intValue();
                if (value >= 1 && value <= 9) mask |= 1 << (value - 1);
            }
        }
        return mask;
    }

    public static ObjectNode unlockFor(String framework, String channel, Double adjusted, Double mechanical) {
        String ladderName = "fallen_knives".equals(framework) ? "FK" : "B".equals(channel) ? "FR-B" : "FR-A";
        Map<String, Integer> ladder = "fallen_knives".equals(framework)
                ? FK_SCORE_UNLOCK : frUnlockLadder(channel);
        Double mechanicalRead = mechanical != null ? mechanical : adjusted;
        String highest = null;
        for (String phase : List.of("p1a", "p1b", "p2", "p3")) {
            Integer line = ladder.get(phase);
            if (line == null) continue;
            Double read = "p3".equals(phase) ? mechanicalRead : adjusted;
            if (read != null && read >= line) highest = phase;
        }
        ObjectNode output = NODES.objectNode();
        output.put("ladder", ladderName);
        for (String phase : List.of("p1a", "p1b", "p2", "p3")) {
            Integer value = ladder.get(phase);
            if (value == null) output.set(phase, NullNode.instance); else output.put(phase, value);
        }
        output.put("p3_note", ladder.get("p3") == null
                ? "Phase 3 is unreachable in Channel B at any score (§4B/§6)"
                : "Phase 3 reads the MECHANICAL score — no analyst channel reaches it");
        if (highest == null) output.set("highest_phase_unlocked_by_score", NullNode.instance);
        else output.put("highest_phase_unlocked_by_score", highest);
        return output;
    }

    public static String canonicalJSON(JsonNode value) {
        return PrettyCanonicalJson.write(value);
    }

    public static ObjectNode feedChanged(String previousText, JsonNode next) {
        if (previousText == null || previousText.isEmpty()) return change(true, "no existing feed");
        JsonNode previous;
        try {
            previous = JSON.readTree(previousText);
        } catch (Exception exception) {
            return change(true, "existing feed is not valid JSON");
        }
        boolean same = canonicalJSON(withoutGeneratedAt(previous)).equals(canonicalJSON(withoutGeneratedAt(next)));
        return change(!same, same ? "identical except generated_at" : "content differs");
    }

    public static String snapshotDigestPayload(JsonNode snapshot) {
        ObjectNode output = NODES.objectNode();
        if (snapshot != null && snapshot.isObject()) {
            List<String> keys = new ArrayList<>();
            snapshot.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) {
                JsonNode block = snapshot.get(key);
                if (block != null && block.isObject()) {
                    ObjectNode copy = ((ObjectNode) block).deepCopy();
                    copy.remove(List.of("fetched_at", "errors"));
                    output.set(key, copy);
                } else {
                    output.set(key, block == null ? NullNode.instance : block.deepCopy());
                }
            }
        }
        return canonicalJSON(output);
    }

    /** Numeric tranche fill, preferring entry_price over the legacy numeric entry field. */
    public static Double fillPrice(JsonNode tranche) {
        if (tranche == null || tranche.isNull()) return null;
        Double entryPrice = finiteNumber(tranche.get("entry_price"));
        if (entryPrice != null) return entryPrice;
        return finiteNumber(tranche.get("entry"));
    }

    public static boolean trancheFilled(JsonNode tranche) {
        JsonNode deployed = tranche == null ? null : tranche.get("deployed");
        return deployed != null && deployed.isBoolean() && deployed.booleanValue() || fillPrice(tranche) != null;
    }

    /** Conservative prose-fill detector used to make mechanical checks reachable. */
    public static ObjectNode entryLooksLikeFill(Object entryValue) {
        String entry = entryValue instanceof String text ? text
                : entryValue instanceof JsonNode node && node.isTextual() ? node.textValue() : null;
        if (entry == null) return fillLook(false, "entry is not prose");
        Matcher negative = FILL_NEGATIVE.matcher(entry);
        if (negative.find()) {
            return fillLook(false, "staged/placeholder language (\"" + negative.group(1) + "\")");
        }
        if (FILL_BARE.matcher(entry).find()) {
            return fillLook(true, "entry opens with a single price, not a range");
        }
        Matcher mtm = FILL_MTM.matcher(entry);
        if (mtm.find()) {
            return fillLook(true, "entry says \"" + mtm.group(1)
                    + "\", which only has meaning against a real position");
        }
        return fillLook(false, "no fill signature");
    }

    public static String weekdayOf(String date) {
        return LocalDate.parse(date).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
    }

    public static boolean isTradingDay(String date, String assetClass) {
        if ("crypto".equals(assetClass)) return true;
        LocalDate parsed = LocalDate.parse(date);
        switch (parsed.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> { return false; }
            default -> { return !US_MARKET_HOLIDAYS.contains(date); }
        }
    }

    public static List<String> nextNTradingDays(String fromDate, int count, String assetClass) {
        List<String> output = new ArrayList<>();
        LocalDate cursor = LocalDate.parse(fromDate);
        while (output.size() < count) {
            cursor = cursor.plusDays(1);
            String value = cursor.toString();
            if (isTradingDay(value, assetClass)) output.add(value);
        }
        return output;
    }

    public static int tradingDaysBetween(String fromDate, String toDate, String assetClass) {
        if (toDate.compareTo(fromDate) <= 0) return 0;
        int count = 0;
        LocalDate cursor = LocalDate.parse(fromDate);
        LocalDate end = LocalDate.parse(toDate);
        while (true) {
            cursor = cursor.plusDays(1);
            if (!cursor.isBefore(end)) break;
            if (isTradingDay(cursor.toString(), assetClass)) count++;
        }
        return count;
    }

    public static final class FkBands {
        private FkBands() {}

        public int sentimentBand(double value) { return ComputeMath.fkSentimentBand(value); }
        public ObjectNode momentumBand(double rsi) { return ComputeMath.fkMomentumBand(rsi, false); }
        public ObjectNode momentumBand(double rsi, boolean lowConfidence) {
            return ComputeMath.fkMomentumBand(rsi, lowConfidence);
        }
        public int mvrvZBand(double value) { return ComputeMath.fkMvrvBand(value); }
        public int drawdownBand(double value) { return ComputeMath.fkDrawdownBand(value); }
        public int goldLowVolBand(double value) { return ComputeMath.fkGoldLowVolBand(value, false); }
        public int goldLowVolBand(double value, boolean cotFlushConfirmed) {
            return ComputeMath.fkGoldLowVolBand(value, cotFlushConfirmed);
        }
    }

    public static final class FrBands {
        private FrBands() {}

        public int euphoriaBand(double value) { return ComputeMath.frEuphoriaBand(value); }
        public int momentumBand(double value) { return ComputeMath.frMomentumBand(value); }
        public int mvrvZBand(double value) { return ComputeMath.frMvrvBand(value); }
        public int athDistanceBand(double value) { return ComputeMath.frAthDistanceBand(value); }
        public int distributionBand(double value) { return ComputeMath.frDistributionBand(value); }
        public int vulnerabilityBand(double value) { return ComputeMath.frVulnerabilityBand(value); }
        public Integer phaseCycleCap(double value) { return ComputeMath.frPhaseCycleCap(value); }
        public double annualizedFunding(double value) { return ComputeMath.frAnnualizedFunding(value); }
        public ObjectNode squeezeTrapPenalty(
                double annualizedFundingPercent,
                boolean sustainedThreeIntervals,
                boolean oiWithinFivePercentOfNinetyDayHigh,
                boolean singleIntervalBelowMinusSeven) {
            return ComputeMath.squeezeTrapPenalty(annualizedFundingPercent, sustainedThreeIntervals,
                    oiWithinFivePercentOfNinetyDayHigh, singleIntervalBelowMinusSeven);
        }
    }

    public static final class FrBBands {
        private FrBBands() {}

        public int rallyBand(double value) { return ComputeMath.frBRallyBand(value); }
        public int momentumBand(double dailyRsi, Double weeklyRsi) {
            return ComputeMath.frBMomentumBand(dailyRsi, weeklyRsi);
        }
        public int resistanceBand(double value) { return ComputeMath.frBResistanceBand(value); }
        public int structureBand(double value) { return ComputeMath.frBStructureBand(value); }
        public int sentimentBand(double value) { return ComputeMath.frBSentimentBand(value); }
        public int maturityPenalty(double bounceSessions) { return ComputeMath.frBMaturityPenalty(bounceSessions); }
    }

    public static final class Internal {
        private Internal() {}

        public double round2(double value) { return ComputeMath.round2(value); }
    }

    private static ObjectNode withoutGeneratedAt(JsonNode value) {
        if (value == null || !value.isObject()) return NODES.objectNode();
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove("generated_at");
        return copy;
    }

    private static ObjectNode change(boolean changed, String reason) {
        ObjectNode output = NODES.objectNode();
        output.put("changed", changed);
        output.put("reason", reason);
        return output;
    }

    private static ObjectNode validation(boolean ok, String reason) {
        ObjectNode output = NODES.objectNode();
        output.put("ok", ok);
        if (reason == null) output.set("reason", NullNode.instance); else output.put("reason", reason);
        return output;
    }

    private static ObjectNode passValidation(boolean pass, String reason) {
        ObjectNode output = NODES.objectNode();
        output.put("pass", pass);
        if (reason == null) output.set("reason", NullNode.instance); else output.put("reason", reason);
        return output;
    }

    private static ObjectNode fillLook(boolean fillLike, String reason) {
        ObjectNode output = NODES.objectNode();
        output.put("fill_like", fillLike);
        output.put("reason", reason);
        return output;
    }

    private static List<String> phasesUnlocked(
            Map<String, Integer> ladder, double adjusted, double mechanical) {
        List<String> output = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ladder.entrySet()) {
            double read = "p3".equals(entry.getKey()) ? mechanical : adjusted;
            if (read >= entry.getValue()) output.add(entry.getKey());
        }
        return List.copyOf(output);
    }

    /** Java representation of a JavaScript value whose {@code typeof} is {@code number}. */
    private static Double numberType(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof JsonNode node && node.isNumber()) return node.doubleValue();
        return null;
    }

    private static Double finiteNumber(Object value) {
        Double number = numberType(value);
        return number != null && Double.isFinite(number) ? number : null;
    }

    private static double jsArithmeticNumber(Object value) {
        if (value instanceof JsonNode node) return ComputeMath.jsNumber(node);
        if (value == null) return 0.0;
        return ComputeMath.jsNumber(value);
    }

    private static String numberText(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        return CanonicalJson.canonicalize(NODES.numberNode(value));
    }

    private static void putNumber(ObjectNode output, String key, double value) {
        output.set(key, ComputeMath.normalizedNumberNode(value));
    }

    @SafeVarargs
    private static <K, V> Map<K, V> orderedMap(Map.Entry<? extends K, ? extends V>... entries) {
        Map<K, V> output = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : entries) {
            output.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(output);
    }

    private static Map<String, List<LegSpec>> legSpecs() {
        Map<String, List<LegSpec>> values = new LinkedHashMap<>();
        values.put("FK/1", List.of(
                new LegSpec(1, "sentiment", "sentiment", 0, 5),
                new LegSpec(2, "momentum", "momentum", 0, 4),
                new LegSpec(3, "valuation", "valuation", -2, 5),
                new LegSpec(4, "capitulation", "capitulation", 0, 3),
                new LegSpec(5, "holder", "holder_behavior", 0, 3)));
        values.put("FR-A/1", List.of(
                new LegSpec(1, "euphoria", "euphoria", 0, 5),
                new LegSpec(2, "momentum", "momentum", 0, 4),
                new LegSpec(3, "valuation", "valuation", 0, 5),
                new LegSpec(4, "distribution", "distribution", 0, 3),
                new LegSpec(5, "vulnerability", "vulnerability", 0, 3)));
        values.put("FR-B/1", List.of(
                new LegSpec(1, "euphoria", "rally_extension", 0, 5),
                new LegSpec(2, "momentum", "local_exhaustion", 0, 4),
                new LegSpec(3, "valuation", "resistance_confluence", 0, 5),
                new LegSpec(4, "distribution", "bear_structure_integrity", 0, 3),
                new LegSpec(5, "vulnerability", "relative_sentiment", 0, 3)));
        return Map.copyOf(values);
    }

    private record LegSpec(int ordinal, String blockKey, String rubricName, int min, int max) {
    }
}
