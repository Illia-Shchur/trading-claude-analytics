package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure computation and validation primitives used by {@link ComputeCommand}. */
public final class ComputeMath {

    public static final Map<String, String> ROUNDING = Collections.unmodifiableMap(new LinkedHashMap<>(Map.ofEntries(
            Map.entry("btc", "half-up"),
            Map.entry("gold", "half-up"),
            Map.entry("eth", "half-down"),
            Map.entry("spx", "half-down"),
            Map.entry("sp500", "half-down"),
            Map.entry("ndx", "half-down"),
            Map.entry("nasdaq", "half-down")
    )));

    public static final Set<String> US_MARKET_HOLIDAYS = Set.of(
            "2025-01-01", "2025-01-20", "2025-02-17", "2025-04-18", "2025-05-26", "2025-06-19",
            "2025-07-04", "2025-09-01", "2025-11-27", "2025-12-25",
            "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19",
            "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
            "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31", "2027-06-18",
            "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24"
    );

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern DERIBIT_INSTRUMENT = Pattern.compile(
            "^([A-Z]+)-(\\d{1,2})([A-Z]{3})(\\d{2})-(\\d+)-([CP])$");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

    private ComputeMath() {
    }

    public static ObjectNode wilderRsi(List<Double> closes, int period) {
        int count = closes == null ? 0 : closes.size();
        ObjectNode out = object();
        if (closes == null || closes.size() < period + 1) {
            out.set("rsi", NullNode.getInstance());
            out.put("closes_used", count);
            out.put("confidence", "insufficient");
            out.put("note", "need ≥" + (period + 1)
                    + " closes for a seed, ≥15 for a low-confidence read, ≥30 for unflagged (FK momentum input rule)");
            return out;
        }
        double gain = 0.0;
        double loss = 0.0;
        for (int i = 1; i <= period; i++) {
            double difference = closes.get(i) - closes.get(i - 1);
            if (difference >= 0.0) {
                gain += difference;
            } else {
                loss -= difference;
            }
        }
        double averageGain = gain / period;
        double averageLoss = loss / period;
        for (int i = period + 1; i < closes.size(); i++) {
            double difference = closes.get(i) - closes.get(i - 1);
            averageGain = (averageGain * (period - 1) + Math.max(difference, 0.0)) / period;
            averageLoss = (averageLoss * (period - 1) + Math.max(-difference, 0.0)) / period;
        }
        double rsi = averageLoss == 0.0 ? 100.0 : 100.0 - 100.0 / (1.0 + averageGain / averageLoss);
        putNumber(out, "rsi", round2(rsi));
        out.put("closes_used", closes.size());
        out.put("period", period);
        out.put("confidence", closes.size() >= 30 ? "ok" : "low");
        return out;
    }

    public static Double sma(List<Double> values, int n) {
        if (values == null || values.size() < n || n <= 0) {
            return null;
        }
        double sum = 0.0;
        for (int i = values.size() - n; i < values.size(); i++) {
            sum += values.get(i);
        }
        return sum / n;
    }

    public static double drawdownPct(double spot, double ath) {
        return round2((1.0 - spot / ath) * 100.0);
    }

    public static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    public static Double sampleStdev(List<Double> values) {
        if (values == null || values.size() < 2) {
            return null;
        }
        // Deliberately use a plain left-to-right sum. DoubleStream.sum uses a
        // compensated algorithm and can differ from Array.reduce by one ULP;
        // the JavaScript implementation uses reduce and its result is part of
        // the JSON contract.
        double total = 0.0;
        for (double value : values) total += value;
        double mean = total / values.size();
        double squares = 0.0;
        for (double value : values) squares += Math.pow(value - mean, 2);
        return Math.sqrt(squares / (values.size() - 1));
    }

    public static Double percentileRank(List<Double> values, double x) {
        List<Double> clean = finiteValues(values);
        if (clean.isEmpty() || !Double.isFinite(x)) {
            return null;
        }
        int below = 0;
        int equal = 0;
        for (double value : clean) {
            if (value < x) {
                below++;
            } else if (value == x) {
                equal++;
            }
        }
        return jsRound(((below + equal / 2.0) / clean.size()) * 10_000.0) / 100.0;
    }

    public static ObjectNode distributionStats(List<Double> values) {
        List<Double> clean = finiteValues(values);
        ObjectNode out = object();
        out.put("n", clean.size());
        if (clean.isEmpty()) {
            out.set("min", NullNode.getInstance());
            out.set("max", NullNode.getInstance());
            out.set("median", NullNode.getInstance());
            out.set("mean", NullNode.getInstance());
            out.set("stdev", NullNode.getInstance());
            return out;
        }
        double total = 0.0;
        for (double value : clean) total += value;
        double mean = total / clean.size();
        putNumber(out, "min", clean.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
        putNumber(out, "max", clean.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
        putNumber(out, "median", median(clean));
        putNumber(out, "mean", jsRound(mean * 10_000.0) / 10_000.0);
        putNumber(out, "stdev", sampleStdev(clean));
        return out;
    }

    public static List<Double> logReturns(List<Double> closes) {
        List<Double> out = new ArrayList<>();
        if (closes == null) {
            return out;
        }
        for (int i = 1; i < closes.size(); i++) {
            Double first = closes.get(i - 1);
            Double second = closes.get(i);
            if (first == null || second == null || first <= 0.0 || second <= 0.0) {
                continue;
            }
            out.add(Math.log(second / first));
        }
        return out;
    }

    public static Double realizedVol(List<Double> closes, int window, int annualize) {
        if (closes == null || closes.size() <= window) {
            return null;
        }
        List<Double> returns = logReturns(closes.subList(closes.size() - window - 1, closes.size()));
        if (returns.size() < 2) {
            return null;
        }
        Double standardDeviation = sampleStdev(returns);
        return standardDeviation == null ? null
                : jsRound(standardDeviation * Math.sqrt(annualize) * 10_000.0) / 100.0;
    }

    public static ObjectNode realizedVolBlock(List<Double> closes, int annualize) {
        ObjectNode out = object();
        putNumber(out, "rv10", realizedVol(closes, 10, annualize));
        putNumber(out, "rv30", realizedVol(closes, 30, annualize));
        putNumber(out, "rv90", realizedVol(closes, 90, annualize));
        out.put("annualize_convention", annualize);
        return out;
    }

    public static List<Double> rollingRealizedVol(List<Double> closes, int window, int annualize) {
        List<Double> out = new ArrayList<>();
        if (closes == null) {
            return out;
        }
        for (int i = window + 1; i <= closes.size(); i++) {
            Double value = realizedVol(closes.subList(0, i), window, annualize);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    public static ObjectNode ceilThresholds(int active) {
        validateActiveDenominator(active);
        ObjectNode out = object();
        out.put("active", active);
        out.put("p1a", (int) Math.ceil(active / 3.0));
        out.put("p1b", (int) Math.ceil(5.0 * active / 9.0));
        out.put("p2", (int) Math.ceil(2.0 * active / 3.0));
        out.put("p3", (int) Math.ceil(7.0 * active / 9.0));
        ObjectNode floors = object();
        floors.put("p1a", 2);
        floors.put("p1b", 3);
        floors.put("p2", 3);
        floors.put("p3", 4);
        out.set("v_floor", floors);
        return out;
    }

    public static ObjectNode frThresholds(int active) {
        validateActiveDenominator(active);
        ObjectNode out = object();
        out.put("active", active);
        out.put("p1a", (int) Math.ceil(3.0 / 9.0 * active));
        out.put("p1b", (int) Math.ceil(5.0 / 9.0 * active));
        out.put("p2", (int) Math.ceil(6.0 / 9.0 * active));
        out.put("p3", (int) Math.ceil(8.0 / 9.0 * active));
        return out;
    }

    public static int roundScore(double raw, String convention) {
        if ("half-up".equals(convention)) {
            return (int) Math.floor(raw + 0.5);
        }
        if ("half-down".equals(convention)) {
            return (int) Math.ceil(raw - 0.5);
        }
        throw new ComputeValidationException("unknown rounding convention \"" + convention
                + "\" — declare half-up or half-down (FK SKILL §4)");
    }

    public static int fkSentimentBand(double value) {
        return value <= 10 ? 5 : value <= 15 ? 4 : value <= 25 ? 3 : value <= 35 ? 2 : value <= 50 ? 1 : 0;
    }

    public static ObjectNode fkMomentumBand(double rsi, boolean lowConfidence) {
        int result = fkMomentumBaseBand(rsi);
        boolean edgeApplied = false;
        if (lowConfidence) {
            for (double edge : List.of(30.0, 35.0, 40.0, 45.0)) {
                if (Math.abs(rsi - edge) <= 2.0) {
                    int lower = fkMomentumBaseBand(edge + 1e-9);
                    if (lower < result) {
                        result = lower;
                        edgeApplied = true;
                    }
                }
            }
        }
        ObjectNode out = object();
        out.put("band", result);
        out.put("low_confidence_edge_rule_applied", edgeApplied);
        return out;
    }

    public static int fkMvrvBand(double value) {
        return value < 0.1 ? 5 : value <= 0.5 ? 4 : value <= 2 ? 3 : value <= 3 ? 2 : value <= 5 ? 0 : -2;
    }

    public static int fkDrawdownBand(double value) {
        return value >= 70 ? 5 : value >= 60 ? 4 : value >= 50 ? 3 : value >= 40 ? 2 : value >= 30 ? 1 : 0;
    }

    public static int fkGoldLowVolBand(double value, boolean cotFlushConfirmed) {
        if (value >= 45) {
            return cotFlushConfirmed ? 3 : 2;
        }
        return value >= 36 ? 2 : value >= 28 ? 2 : value >= 20 ? 2 : value >= 12 ? 1 : 0;
    }

    public static int frEuphoriaBand(double value) {
        return value >= 90 ? 5 : value >= 80 ? 4 : value >= 70 ? 3 : value >= 60 ? 2 : value >= 50 ? 1 : 0;
    }

    public static int frMomentumBand(double value) {
        return value > 75 ? 4 : value > 70 ? 3 : value > 65 ? 2 : value > 60 ? 1 : 0;
    }

    public static int frMvrvBand(double value) {
        return value > 5 ? 5 : value > 3 ? 4 : value > 2 ? 3 : value > 1 ? 1 : 0;
    }

    public static int frAthDistanceBand(double value) {
        return value < 5 ? 5 : value < 15 ? 3 : value < 30 ? 1 : 0;
    }

    public static int frDistributionBand(double value) {
        return Math.max(0, Math.min(3, toInt32(value)));
    }

    public static int frVulnerabilityBand(double value) {
        return Math.max(0, Math.min(3, toInt32(value)));
    }

    public static Integer frPhaseCycleCap(double percentBelowOneYearAth) {
        if (percentBelowOneYearAth > 20) return 8;
        if (percentBelowOneYearAth >= 10) return 14;
        return null;
    }

    public static double frAnnualizedFunding(double perEightHoursPercent) {
        return round2(perEightHoursPercent * 3.0 * 365.0);
    }

    public static ObjectNode squeezeTrapPenalty(
            double fundingAnnualizedPercent,
            boolean sustainedThreeIntervals,
            boolean oiWithinFivePercent,
            boolean singleIntervalBelowMinusSeven) {
        boolean base = fundingAnnualizedPercent < -5.0 && sustainedThreeIntervals;
        boolean immediate = singleIntervalBelowMinusSeven && oiWithinFivePercent;
        ObjectNode out = object();
        if (base && oiWithinFivePercent || immediate) {
            out.put("raw_penalty", -2);
            out.put("gate_surcharge", 2);
            out.put("tier", "escalated");
        } else if (base) {
            out.put("raw_penalty", -2);
            out.put("gate_surcharge", 1);
            out.put("tier", "base");
        } else {
            out.put("raw_penalty", 0);
            out.put("gate_surcharge", 0);
            out.put("tier", "none");
        }
        return out;
    }

    public static ObjectNode weightedEv(ArrayNode scenarios) {
        ArrayNode components = array();
        double ev = 0.0;
        double probabilitySum = 0.0;
        for (JsonNode scenario : scenarios) {
            double probability = jsNumber(scenario.get("p"));
            JsonNode midNode = scenario.get("mid");
            double mid = midNode != null && !midNode.isNull()
                    ? jsNumber(midNode)
                    : (jsNumber(scenario.get("low")) + jsNumber(scenario.get("high"))) / 2.0;
            double roundedMid = round2(mid);
            double contribution = round2(probability / 100.0 * mid);
            ObjectNode component = object();
            copyDefined(component, "name", scenario.get("name"));
            putNumber(component, "p", probability);
            putNumber(component, "mid", roundedMid);
            putNumber(component, "contribution", contribution);
            components.add(component);
            ev += contribution;
            probabilitySum += probability;
        }
        double roundedEv = round2(ev);
        double roundedProbability = round2(probabilitySum);
        ObjectNode out = object();
        putNumber(out, "ev", roundedEv);
        putNumber(out, "prob_sum", roundedProbability);
        out.put("prob_sum_ok", Math.abs(roundedProbability - 100.0) <= 0.5);
        out.set("components", components);
        return out;
    }

    public static ObjectNode evCheck(double statedEv, ArrayNode scenarios, Double spot, double tolerancePercent) {
        ObjectNode weighted = weightedEv(scenarios);
        double recomputed = numericValue(weighted.get("ev"));
        Double relativeDifference = recomputed == 0.0 ? null
                : round2(Math.abs(statedEv - recomputed) / Math.abs(recomputed) * 100.0);
        JsonNode rally = null;
        for (JsonNode scenario : scenarios) {
            String name = scenario.path("name").asText("");
            if (name.toLowerCase(Locale.ROOT).contains("rally")) {
                rally = scenario;
                break;
            }
        }
        ObjectNode out = object();
        putNumber(out, "recomputed_ev", recomputed);
        putNumber(out, "stated_ev", statedEv);
        putNumber(out, "rel_diff_pct", relativeDifference);
        out.put("within_tolerance", relativeDifference != null && relativeDifference <= tolerancePercent);
        out.set("prob_sum", weighted.get("prob_sum"));
        out.set("prob_sum_ok", weighted.get("prob_sum_ok"));
        out.put("rally_cap_ok", rally == null || jsNumber(rally.get("p")) <= 50.0);
        putNumber(out, "vs_spot_pct", spot != null && truthy(spot)
                ? round2((recomputed / spot - 1.0) * 100.0) : null);
        out.set("components", weighted.get("components"));
        return out;
    }

    public static ObjectNode stopCoherence(double catastrophic, double deepestZoneFloor) {
        ObjectNode out = object();
        out.put("pass", catastrophic < deepestZoneFloor);
        putNumber(out, "catastrophic", catastrophic);
        putNumber(out, "deepest_zone_floor", deepestZoneFloor);
        out.put("rule", "catastrophic stop must sit STRICTLY below the deepest active buy-zone floor (the compound line may sit inside a band by design)");
        return out;
    }

    public static ObjectNode adr(ArrayNode sessions, int n, List<String> exclude) {
        Set<String> excluded = new HashSet<>(exclude);
        List<JsonNode> usable = new ArrayList<>();
        sessions.forEach(session -> {
            if (!excluded.contains(session.path("date").asText())
                    && isDefined(session.get("high")) && isDefined(session.get("low"))) {
                usable.add(session);
            }
        });
        List<JsonNode> used = usable.subList(Math.max(0, usable.size() - n), usable.size());
        ObjectNode out = object();
        if (used.size() < n) {
            out.set("adr", NullNode.getInstance());
            out.put("note", "only " + used.size() + " usable sessions (need " + n + ")");
            out.set("used", JSON.valueToTree(used));
            out.set("excluded", JSON.valueToTree(exclude));
            return out;
        }
        double total = 0.0;
        for (JsonNode session : used) total += jsNumber(session.get("high")) - jsNumber(session.get("low"));
        putNumber(out, "adr", round2(total / n));
        ArrayNode usedOutput = array();
        for (JsonNode session : used) {
            ObjectNode row = object();
            copyDefined(row, "date", session.get("date"));
            putNumber(row, "range", round2(jsNumber(session.get("high")) - jsNumber(session.get("low"))));
            usedOutput.add(row);
        }
        out.set("used", usedOutput);
        out.set("excluded", JSON.valueToTree(exclude));
        return out;
    }

    public static int fngStreak(List<Double> newestFirst, double threshold) {
        int streak = 0;
        for (double value : newestFirst) {
            if (value <= threshold) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    public static ObjectNode dailyTrend(ArrayNode sessions, Double spot, int fast, int slow, int slopeN, int lowN) {
        int needed = slow + slopeN;
        if (sessions == null || sessions.size() < needed) {
            ObjectNode insufficient = object();
            insufficient.put("insufficient", "need ≥" + needed + " daily sessions for a " + slow
                    + "dma + " + slopeN + "-session slope, got " + (sessions == null ? 0 : sessions.size()));
            return insufficient;
        }
        List<Double> closes = new ArrayList<>();
        sessions.forEach(session -> closes.add(jsNumber(session.get("close"))));
        double price = spot != null ? spot : closes.get(closes.size() - 1);
        ObjectNode rsi = wilderRsi(closes, 14);
        Double movingFast = sma(closes, fast);
        Double movingSlow = sma(closes, slow);
        Double slope = smaSlope(closes, slow, slopeN);
        Boolean slowFalling = slope == null ? null : slope < 0.0;
        Boolean belowSlow = movingSlow == null ? null : price < movingSlow;
        Boolean fastBelowSlow = movingFast == null || movingSlow == null ? null : movingFast < movingSlow;

        List<Double> past = closes.subList(0, closes.size() - slopeN);
        Double pastFast = sma(past, fast);
        Double pastSlow = sma(past, slow);
        Double gapNow = movingFast == null || movingSlow == null || movingSlow == 0.0
                ? null : Math.abs(movingFast - movingSlow) / movingSlow * 100.0;
        Double gapPast = pastFast == null || pastSlow == null || pastSlow == 0.0
                ? null : Math.abs(pastFast - pastSlow) / pastSlow * 100.0;
        Boolean gapNarrowed = gapNow == null || gapPast == null ? null : gapNow < gapPast;
        boolean structureB = Boolean.TRUE.equals(fastBelowSlow) && Boolean.TRUE.equals(gapNarrowed);
        Boolean withinSlow = withinPercent(price, movingSlow, 3.0);
        Boolean withinFastFromBelow = movingFast == null ? null
                : price <= movingFast && Boolean.TRUE.equals(withinPercent(price, movingFast, 3.0));

        int start = Math.max(0, sessions.size() - lowN);
        List<JsonNode> lowWindow = new ArrayList<>();
        for (int i = start; i < sessions.size(); i++) {
            lowWindow.add(sessions.get(i));
        }
        double low = lowWindow.stream().mapToDouble(row -> jsNumber(row.get("low"))).min().orElse(Double.POSITIVE_INFINITY);
        int lowIndex = 0;
        for (int i = 0; i < lowWindow.size(); i++) {
            if (jsNumber(lowWindow.get(i).get("low")) == low) {
                lowIndex = i;
                break;
            }
        }
        Double bounce = low == 0.0 ? null : round2((price / low - 1.0) * 100.0);
        int bounceAge = lowWindow.size() - 1 - lowIndex;
        int sessionsLowToHigh = 0;
        double highAfterLow = Double.NEGATIVE_INFINITY;
        for (int i = lowIndex; i < lowWindow.size(); i++) {
            double high = jsNumber(lowWindow.get(i).get("high"));
            if (high > highAfterLow) {
                highAfterLow = high;
                sessionsLowToHigh = i - lowIndex;
            }
        }

        ObjectNode out = object();
        out.set("insufficient", NullNode.getInstance());
        out.set("rsi14", rsi.get("rsi"));
        out.set("rsi14_confidence", rsi.get("confidence"));
        putNumber(out, "ma50", roundedNullable(movingFast));
        putNumber(out, "ma200", roundedNullable(movingSlow));
        putNumber(out, "ma200_slope20_pct", slope);
        putBoolean(out, "ma200_falling", slowFalling);
        putBoolean(out, "price_below_ma200", belowSlow);
        putBoolean(out, "ma50_below_ma200", fastBelowSlow);
        putNumber(out, "gap_now_pct", roundedNullable(gapNow));
        putBoolean(out, "gap_narrowed_20", gapNarrowed);
        out.put("structure_b", structureB);
        putBoolean(out, "within_3pct_of_ma200", withinSlow);
        putBoolean(out, "within_3pct_of_ma50_from_below", withinFastFromBelow);
        putNumber(out, "low_40s", round2(low));
        putNumber(out, "bounce_pct", bounce);
        out.put("bounce_age_sessions", bounceAge);
        out.put("sessions_low_to_high", sessionsLowToHigh);
        return out;
    }

    public static ObjectNode frStallConfirmation(Double close, Double priorClose, Double high, Double bounceHigh) {
        if (close == null || priorClose == null || high == null || bounceHigh == null) {
            return null;
        }
        boolean failedNewHigh = high < bounceHigh;
        boolean closedDown = close <= priorClose;
        ObjectNode out = object();
        out.put("confirmed", failedNewHigh && closedDown);
        out.put("failed_new_high", failedNewHigh);
        out.put("closed_down", closedDown);
        return out;
    }

    public static ObjectNode frComposite(
            ObjectNode legs,
            double penalty,
            double discretionary,
            String rounding,
            String channel,
            ObjectNode cap) {
        double legSum = 0.0;
        if (legs != null) {
            var fields = legs.fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                double numeric = jsNumber(value);
                legSum += truthy(value) ? numeric : 0.0;
            }
        }
        double raw = round2(legSum + penalty + discretionary);
        int mechanicalUnrounded = roundScore(legSum + penalty, rounding);
        int adjustedUnrounded = roundScore(raw, rounding);
        int mechanicalClamped = Math.max(0, Math.min(20, mechanicalUnrounded));
        int adjustedClamped = Math.max(0, Math.min(20, adjustedUnrounded));
        boolean capApplied = cap != null && truthy(cap.get("applied"));
        Double capValue = cap == null ? null : jsNumber(cap.get("value"));
        double mechanical = capApplied ? Math.min(mechanicalClamped, capValue) : mechanicalClamped;
        double adjusted = capApplied ? Math.min(adjustedClamped, capValue) : adjustedClamped;

        ObjectNode out = object();
        putNumber(out, "leg_sum", legSum);
        putNumber(out, "penalty", penalty);
        putNumber(out, "mechanical", mechanical);
        putNumber(out, "raw", raw);
        putNumber(out, "adjusted", adjusted);
        out.put("cap_applied", capApplied);
        putNumber(out, "cap_value", capValue);
        out.put("clamped", mechanicalClamped != mechanicalUnrounded || adjustedClamped != adjustedUnrounded);
        out.put("channel", channel);
        return out;
    }

    public static ObjectNode frCompanion(ObjectNode market, ObjectNode counts, String rounding) {
        ObjectNode safeMarket = market == null ? object() : market;
        ObjectNode safeCounts = counts == null ? object() : counts;
        String channel = frChannel(
                nullableFinite(safeMarket.get("pct_below_1y_ath")),
                strictBoolean(safeMarket.get("ma200_falling")),
                strictBoolean(safeMarket.get("price_below_ma200")));
        boolean useB = "B".equals(channel);
        List<String> countKeys = useB
                ? List.of("resistance_count", "structure_count", "sentiment_count")
                : List.of("mvrv_z", "distribution_count", "vulnerability_count");
        List<String> missing = countKeys.stream().filter(key -> nullish(safeCounts.get(key))).toList();
        ObjectNode legs = companionLegs(safeMarket, safeCounts, useB, missing, false);

        boolean oiUnknown = nullish(safeMarket.get("oi_within_5pct_of_90d_high"));
        ObjectNode squeeze = squeezeTrapPenalty(
                valueOr(safeMarket.get("funding_annualized_pct"), 0.0),
                truthy(safeMarket.get("sustained_3_intervals")),
                oiUnknown || strictBoolean(safeMarket.get("oi_within_5pct_of_90d_high")),
                truthy(safeMarket.get("single_interval_below_minus_7")));
        int maturity = useB && valueOr(safeMarket.get("bounce_age_sessions"), 999.0) < 8 ? -2 : 0;
        int penalty = Math.max(-4, squeeze.path("raw_penalty").asInt() + maturity);
        Double percentBelow = nullableFinite(safeMarket.get("pct_below_1y_ath"));
        Integer capValue = percentBelow == null ? null : frPhaseCycleCap(percentBelow);
        ObjectNode cap = object();
        cap.put("applied", "A".equals(channel) && capValue != null);
        putNumber(cap, "value", capValue == null ? null : capValue.doubleValue());
        ObjectNode composite = frComposite(legs, penalty, 0.0, rounding, channel, cap);

        Double scoreFloor = null;
        Double scoreCeiling = null;
        boolean dischargeable = true;
        if (!missing.isEmpty()) {
            scoreFloor = numericValue(composite.get("adjusted"));
            ObjectNode ceilingLegs = companionLegs(safeMarket, safeCounts, useB, missing, true);
            scoreCeiling = numericValue(frComposite(ceilingLegs, penalty, 0.0, rounding, channel, cap).get("adjusted"));
            dischargeable = !straddles(scoreFloor, scoreCeiling, 9.0)
                    && !straddles(scoreFloor, scoreCeiling, 12.0);
        }

        ObjectNode score = object();
        score.set("legs", legs);
        score.put("penalty", penalty);
        score.set("mechanical", composite.get("mechanical"));
        score.set("adjusted", composite.get("adjusted"));
        score.put("rounding", rounding);
        ObjectNode squeezeOutput = object();
        squeezeOutput.set("tier", squeeze.get("tier"));
        squeezeOutput.set("gate_surcharge", squeeze.get("gate_surcharge"));

        ObjectNode out = object();
        out.put("channel", channel);
        out.set("score", score);
        out.set("cap", cap);
        out.set("squeeze", squeezeOutput);
        out.set("inputs_missing", JSON.valueToTree(missing));
        out.put("confidence", missing.isEmpty() ? "full" : "partial");
        putNumber(out, "score_floor", scoreFloor);
        putNumber(out, "score_ceiling", scoreCeiling);
        out.put("hard_rule_5_dischargeable", dischargeable);
        if (oiUnknown) {
            out.set("oi_within_5pct_of_90d_high", NullNode.getInstance());
        } else {
            out.set("oi_within_5pct_of_90d_high", safeMarket.get("oi_within_5pct_of_90d_high"));
        }
        out.put("standalone_report_owed", numericValue(composite.get("adjusted")) >= 9.0);
        return out;
    }

    public static ObjectNode correlationFromCloses(ArrayNode seriesA, ArrayNode seriesB, Integer window) {
        Map<String, Double> secondByDate = new HashMap<>();
        seriesB.forEach(row -> secondByDate.put(row.path("date").asText(), jsNumber(row.get("close"))));
        List<String> dates = new ArrayList<>();
        List<Double> first = new ArrayList<>();
        List<Double> second = new ArrayList<>();
        int droppedA = 0;
        Set<String> firstDates = new HashSet<>();
        for (JsonNode row : seriesA) {
            String date = row.path("date").asText();
            firstDates.add(date);
            if (secondByDate.containsKey(date)) {
                dates.add(date);
                first.add(jsNumber(row.get("close")));
                second.add(secondByDate.get(date));
            } else {
                droppedA++;
            }
        }
        int droppedB = 0;
        for (JsonNode row : seriesB) {
            if (!firstDates.contains(row.path("date").asText())) {
                droppedB++;
            }
        }
        if (window != null && dates.size() > window) {
            int start = dates.size() - window;
            dates = new ArrayList<>(dates.subList(start, dates.size()));
            first = new ArrayList<>(first.subList(start, first.size()));
            second = new ArrayList<>(second.subList(start, second.size()));
        }
        List<Double> returnsFirst = logReturns(first);
        List<Double> returnsSecond = logReturns(second);
        Double correlation = returnsFirst.size() >= 2 && returnsSecond.size() >= 2
                ? pearson(returnsFirst, returnsSecond) : null;
        ObjectNode out = correlationRegime(correlation);
        out.put("method", "pearson_daily_log_returns");
        out.put("n_aligned_sessions", dates.size());
        out.put("n_return_observations", returnsFirst.size());
        ObjectNode dropped = object();
        dropped.put("a", droppedA);
        dropped.put("b", droppedB);
        out.set("dropped", dropped);
        putString(out, "window_start", dates.isEmpty() ? null : dates.get(0));
        putString(out, "window_end", dates.isEmpty() ? null : dates.get(dates.size() - 1));
        return out;
    }

    public static ObjectNode alignSeries(ArrayNode firstSeries, ArrayNode secondSeries) {
        Map<String, JsonNode> secondByDate = new HashMap<>();
        for (JsonNode row : secondSeries) secondByDate.put(row.path("date").asText(), row.get("value"));
        ArrayNode dates = array();
        ArrayNode first = array();
        ArrayNode second = array();
        int droppedFirst = 0;
        Set<String> firstDates = new HashSet<>();
        for (JsonNode row : firstSeries) {
            String date = row.path("date").asText();
            firstDates.add(date);
            if (secondByDate.containsKey(date)) {
                dates.add(date);
                JsonNode firstValue = row.get("value");
                JsonNode secondValue = secondByDate.get(date);
                first.add(firstValue == null ? NullNode.getInstance() : firstValue.deepCopy());
                second.add(secondValue == null ? NullNode.getInstance() : secondValue.deepCopy());
            } else {
                droppedFirst++;
            }
        }
        int droppedSecond = 0;
        for (JsonNode row : secondSeries) {
            if (!firstDates.contains(row.path("date").asText())) droppedSecond++;
        }
        ObjectNode output = object();
        output.set("dates", dates);
        output.set("xs", first);
        output.set("ys", second);
        ObjectNode dropped = output.putObject("dropped");
        dropped.put("a", droppedFirst);
        dropped.put("b", droppedSecond);
        return output;
    }

    public static boolean corrSurcharge(Double correlation) {
        return correlation != null && correlation > 0.7;
    }

    public static ObjectNode basisBlock(Double mark, Double index, Double fundingAnnualizedPercent, Double riskFreePercent) {
        if (mark == null || index == null || index == 0.0) {
            ObjectNode unavailable = object();
            unavailable.put("available", false);
            unavailable.put("reason", "mark/index price unavailable");
            unavailable.put("note", "DISCLOSED CONTEXT ONLY — not a scored leg or gate");
            return unavailable;
        }
        double basis = round2((mark / index - 1.0) * 100.0);
        Double versusRiskFree = fundingAnnualizedPercent != null && riskFreePercent != null
                ? round2(fundingAnnualizedPercent - riskFreePercent) : null;
        String label = fundingAnnualizedPercent == null ? "not computed"
                : fundingAnnualizedPercent > 0.0 ? "positive (longs pay shorts)"
                : fundingAnnualizedPercent < 0.0 ? "negative (shorts pay longs)" : "flat";
        ObjectNode out = object();
        out.put("available", true);
        putNumber(out, "perp_basis_pct", basis);
        putNumber(out, "annualized_carry_pct", fundingAnnualizedPercent);
        putNumber(out, "vs_risk_free_pp", versusRiskFree);
        out.put("label", label);
        out.put("sign_convention", "POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026) — identical convention to fr.annualizedFunding()");
        out.put("note", "DISCLOSED CONTEXT ONLY — not a scored leg or gate; label is descriptive, consequence-free (mirrors the correlation label-ladder discipline)");
        return out;
    }

    public static ObjectNode positioningBlock(ArrayNode longShortRows, ArrayNode takerRows, ArrayNode oiRows) {
        List<Double> longShort = numericField(longShortRows, "longShortRatio");
        List<Double> taker = numericField(takerRows, "buySellRatio");
        List<Double> openInterest = numericField(oiRows, "sumOpenInterest");
        ObjectNode out = object();
        out.set("long_short_account_ratio", positioningSeries(longShort, false));
        out.set("taker_buy_sell_ratio", positioningSeries(taker, false));
        out.set("open_interest", positioningSeries(openInterest, true));
        out.put("history_days", Math.max(longShort.size(), Math.max(taker.size(), openInterest.size())));
        out.put("scope_note", "Binance-ACCOUNT-weighted, SINGLE-VENUE series (not market-wide OI, not a cross-exchange measure); history capped at ~30 days by the endpoint itself, never treated as 90d");
        out.put("note", "DISCLOSED CONTEXT ONLY — not a scored leg or gate");
        return out;
    }

    public static ObjectNode netLiquidity(Double walclMillions, Double rrpBillions, Double tgaMillions) {
        if (walclMillions == null || rrpBillions == null || tgaMillions == null) {
            ObjectNode out = object();
            out.put("available", false);
            out.put("reason", "WALCL/RRPONTSYD/WTREGEN unavailable");
            out.put("note", "DISCLOSED CONTEXT ONLY — not a scored leg or gate");
            return out;
        }
        double netMillions = round2(walclMillions - rrpBillions * 1000.0 - tgaMillions);
        ObjectNode components = object();
        putNumber(components, "walcl_usd_millions", walclMillions);
        putNumber(components, "rrpontsyd_usd_billions", rrpBillions);
        putNumber(components, "wtregen_usd_millions", tgaMillions);
        ObjectNode out = object();
        out.put("available", true);
        putNumber(out, "net_liquidity_usd_millions", netMillions);
        putNumber(out, "net_liquidity_usd_trillions", round2(netMillions / 1e6));
        out.set("components", components);
        out.put("cadence_note", "WALCL/WTREGEN publish WEEKLY (Thursdays) — this is a weekly figure, not daily; a stale mid-week read must not be mistaken for fresh");
        out.put("note", "DISCLOSED CONTEXT ONLY — not a scored leg or gate");
        return out;
    }

    public static ObjectNode borrowBlock(JsonNode ticker) {
        String note = "DISCLOSED CONTEXT ONLY — not a scored leg or gate. Bitfinex margin-funding book: a SINGLE VENUE, and a LENDING book — not necessarily the venue a short is actually borrowed on. Frequently THIN (see bid_size/ask_size) — a single large quote can move the headline rate; check the size before reading the rate as a real market.";
        if (ticker == null || !ticker.isArray() || ticker.size() < 7 || !ticker.get(0).isNumber()) {
            ObjectNode out = object();
            out.put("available", false);
            out.put("reason", "malformed or missing Bitfinex funding ticker");
            out.put("note", note);
            return out;
        }
        double frr = ticker.get(0).doubleValue();
        ObjectNode out = object();
        out.put("available", true);
        putNumber(out, "daily_rate_pct", round8(frr * 100.0));
        putNumber(out, "annualized_pct", round8(frr * 100.0 * 365.0));
        putNumber(out, "bid_pct", numericNode(ticker.get(1)) ? round8(ticker.get(1).doubleValue() * 100.0) : null);
        putNumber(out, "bid_period_days", numericNode(ticker.get(2)) ? ticker.get(2).doubleValue() : null);
        putNumber(out, "bid_size", numericNode(ticker.get(3)) ? round2(ticker.get(3).doubleValue()) : null);
        putNumber(out, "ask_pct", numericNode(ticker.get(4)) ? round8(ticker.get(4).doubleValue() * 100.0) : null);
        putNumber(out, "ask_period_days", numericNode(ticker.get(5)) ? ticker.get(5).doubleValue() : null);
        putNumber(out, "ask_size", numericNode(ticker.get(6)) ? round2(ticker.get(6).doubleValue()) : null);
        out.put("scope_note", "single-venue Bitfinex margin-FUNDING (lending) book, not necessarily the short's actual borrow venue; ~daily FRR annualized ×365 (simple, matching the fr-funding annualization convention)");
        out.put("note", note);
        return out;
    }

    public static ObjectNode stablecoinBlock(JsonNode rows) {
        List<StablecoinPoint> clean = new ArrayList<>();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                JsonNode totals = row == null ? null : row.get("totalCirculatingUSD");
                double value = totals != null && truthy(totals)
                        ? jsNumber(totals.get("peggedUSD")) : Double.NaN;
                if (Double.isFinite(value)) {
                    clean.add(new StablecoinPoint(row.get("date"), value));
                }
            }
        }
        if (clean.isEmpty()) {
            ObjectNode out = object();
            out.put("available", false);
            out.put("reason", "no usable rows");
            out.put("note", "DISCLOSED CONTEXT ONLY — third-party aggregation, subject to back-revision");
            return out;
        }
        List<Double> values = clean.stream().map(StablecoinPoint::value).toList();
        double latest = values.get(values.size() - 1);
        ObjectNode out = object();
        out.put("available", true);
        putNumber(out, "total_circulating_usd", jsRound(latest));
        putNumber(out, "net_change_30d_pct", netChange(values, latest, 30));
        putNumber(out, "net_change_90d_pct", netChange(values, latest, 90));
        putNumber(out, "percentile_vs_history", percentileRank(values.subList(0, values.size() - 1), latest));
        out.put("n_days_history", values.size());
        copyDefined(out, "as_of", clean.get(clean.size() - 1).date());
        out.put("note", "DISCLOSED CONTEXT ONLY — third-party cross-chain aggregation (DefiLlama), subject to back-revision; a capital-flow tell, not a settled figure");
        return out;
    }

    public static ObjectNode shortEv(Double directionalEv, Double annualizedFunding, Double holdDays, Double targetGain) {
        String convention = "POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026)";
        if (directionalEv == null || annualizedFunding == null || holdDays == null) {
            ObjectNode out = object();
            out.put("available", false);
            out.put("reason", "directionalEV/fundingAnnualizedPct/holdDays required");
            out.put("sign_convention", convention);
            return out;
        }
        double trueCarry = round2(annualizedFunding * (holdDays / 365.0));
        double flooredCarry = Math.min(trueCarry, 0.0);
        double totalTrue = round2(directionalEv + trueCarry);
        double totalForGates = round2(directionalEv + flooredCarry);
        boolean hasTarget = targetGain != null && targetGain > 0.0;
        Double percentTarget = hasTarget ? round2(Math.abs(flooredCarry) / targetGain * 100.0) : null;
        ObjectNode out = object();
        out.put("available", true);
        putNumber(out, "directional_ev_pct", round2(directionalEv));
        putNumber(out, "carry_ev_pct_true", trueCarry);
        putNumber(out, "carry_ev_pct_floored", flooredCarry);
        out.put("carry_floor_applied", trueCarry != flooredCarry);
        putNumber(out, "total_short_ev_true", totalTrue);
        putNumber(out, "total_short_ev_for_gates", totalForGates);
        out.put("passes_min_edge_filter", totalForGates > 3.0);
        putNumber(out, "carry_pct_of_target", percentTarget);
        if (percentTarget == null) {
            out.set("carry_veto", NullNode.getInstance());
        } else {
            out.put("carry_veto", percentTarget > 40.0);
        }
        out.put("sign_convention", convention + " — identical convention to fr.annualizedFunding()/basisBlock()");
        out.put("ledger_note", "the position snapshot's funding_usd is ACCOUNT CASHFLOW (negative = paid OUT of the account, whichever side the position is on) and INVERTS against this MARKET-RATE convention — use the ledger figure ONLY to fill the realized Carry-Cost-Ledger column (SKILL §6), never here");
        return out;
    }

    public static ObjectNode deribitVolBlock(ArrayNode bookRows, ArrayNode dvolCandles, Double rv30, long nowMillis) {
        JsonNode dvol = dvolCandles != null && !dvolCandles.isEmpty()
                ? dvolCandles.get(dvolCandles.size() - 1).get(4) : NullNode.getInstance();
        String note = "DISCLOSED CONTEXT ONLY — not a scored leg or gate. Skew is MONEYNESS-based (|strike/spot-1| buckets), NOT a true 25-delta risk reversal (book summary carries no per-instrument delta).";
        List<OptionQuote> parsed = new ArrayList<>();
        if (bookRows != null) {
            for (JsonNode row : bookRows) {
                OptionInstrument instrument = parseDeribitInstrument(row.path("instrument_name").asText(""));
                if (instrument != null && isDefined(row.get("mark_iv")) && isDefined(row.get("underlying_price"))) {
                    parsed.add(new OptionQuote(instrument, jsNumber(row.get("mark_iv")), jsNumber(row.get("underlying_price")), 0.0));
                }
            }
        }
        if (parsed.isEmpty()) {
            return unavailableVol("no usable option quotes — empty or illiquid book", dvol, rv30, note);
        }
        List<OptionQuote> inWindow = parsed.stream()
                .map(quote -> quote.withDays((quote.instrument().expiryMillis() - nowMillis) / 86_400_000.0))
                .filter(quote -> quote.daysOut() >= 7.0 && quote.daysOut() <= 45.0)
                .toList();
        if (inWindow.isEmpty()) {
            return unavailableVol("no expiry 7-45 days out", dvol, rv30, note);
        }
        double targetDays = 26.0;
        OptionQuote nearest = inWindow.get(0);
        for (OptionQuote quote : inWindow) {
            if (Math.abs(quote.daysOut() - targetDays) < Math.abs(nearest.daysOut() - targetDays)) {
                nearest = quote;
            }
        }
        final long expiry = nearest.instrument().expiryMillis();
        List<OptionQuote> chain = inWindow.stream()
                .filter(quote -> quote.instrument().expiryMillis() == expiry).toList();
        double spot = median(chain.stream().map(OptionQuote::underlyingPrice).toList());
        OptionQuote atmCall = closestByStrike(chain, spot, "C");
        OptionQuote atmPut = closestByStrike(chain, spot, "P");
        List<Double> atmValues = new ArrayList<>();
        if (atmCall != null) atmValues.add(atmCall.markIv());
        if (atmPut != null) atmValues.add(atmPut.markIv());
        double atmTotal = 0.0;
        for (double value : atmValues) atmTotal += value;
        Double atmIv = atmValues.isEmpty() ? null : round2(atmTotal / atmValues.size());
        OptionQuote putLeg = closestByStrike(chain, spot * 0.9, "P");
        OptionQuote callLeg = closestByStrike(chain, spot * 1.1, "C");
        Double skew = putLeg != null && callLeg != null ? round2(putLeg.markIv() - callLeg.markIv()) : null;
        Double vrp = atmIv != null && rv30 != null ? round2(atmIv - rv30) : null;

        ObjectNode legs = object();
        putNumber(legs, "put_strike", putLeg == null ? null : putLeg.instrument().strike());
        putNumber(legs, "call_strike", callLeg == null ? null : callLeg.instrument().strike());
        ObjectNode out = object();
        out.put("available", true);
        out.set("dvol", dvol == null ? NullNode.getInstance() : dvol.deepCopy());
        out.put("expiry_used", Instant.ofEpochMilli(expiry).atOffset(ZoneOffset.UTC).toLocalDate().toString());
        putNumber(out, "days_out", round2(nearest.daysOut()));
        putNumber(out, "spot_used", round2(spot));
        putNumber(out, "atm_iv_pct", atmIv);
        putNumber(out, "skew_90_110_moneyness_pct", skew);
        out.put("skew_sign_convention", "POSITIVE = the ~10% OTM put is richer (higher IV) than the ~10% OTM call = a downside hedging bid. A distribution blow-off shows this skew COMPRESSING toward zero or INVERTING (calls bid), not rising — same discipline as fundingBlock.sign_convention and basisBlock.sign_convention: stated in the output because this repo has already been bitten twice by an inverted sign (the Jul-2026 funding correction; the ETH backtest's \"funding cuts against shorts\" error).");
        out.set("skew_legs", legs);
        putNumber(out, "rv30_pct", rv30);
        putNumber(out, "vrp_pct", vrp);
        out.put("note", note);
        return out;
    }

    public static String weekdayOf(String date) {
        return LocalDate.parse(date).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
    }

    public static boolean isTradingDay(String date, String assetClass) {
        if ("crypto".equals(assetClass)) {
            return true;
        }
        LocalDate parsed = LocalDate.parse(date);
        return parsed.getDayOfWeek() != DayOfWeek.SATURDAY
                && parsed.getDayOfWeek() != DayOfWeek.SUNDAY
                && !US_MARKET_HOLIDAYS.contains(date);
    }

    public static List<String> nextNTradingDays(String fromDate, int count, String assetClass) {
        List<String> out = new ArrayList<>();
        LocalDate current = LocalDate.parse(fromDate);
        while (out.size() < count) {
            current = current.plusDays(1);
            String date = current.toString();
            if (isTradingDay(date, assetClass)) {
                out.add(date);
            }
        }
        return out;
    }

    public static double round2(double value) {
        return jsRound(value * 100.0) / 100.0;
    }

    public static double jsNumber(JsonNode value) {
        if (value == null || value.isMissingNode()) return Double.NaN;
        if (value.isNull()) return 0.0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1.0 : 0.0;
        if (value.isTextual()) return jsNumber(value.textValue());
        if (value.isArray()) {
            if (value.isEmpty()) return 0.0;
            if (value.size() == 1) {
                JsonNode only = value.get(0);
                return jsNumber(only == null || only.isNull() ? "" : jsString(only));
            }
            return Double.NaN;
        }
        return Double.NaN;
    }

    public static double jsNumber(Object value) {
        if (value == null) return Double.NaN;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        if (value instanceof JsonNode node) return jsNumber(node);
        return jsNumber(String.valueOf(value));
    }

    public static double jsNumber(String raw) {
        String text = raw == null ? "undefined" : raw.trim();
        if (text.isEmpty()) return 0.0;
        if ("Infinity".equals(text) || "+Infinity".equals(text)) return Double.POSITIVE_INFINITY;
        if ("-Infinity".equals(text)) return Double.NEGATIVE_INFINITY;
        try {
            if (text.matches("0[xX][0-9a-fA-F]+")) return new BigInteger(text.substring(2), 16).doubleValue();
            if (text.matches("0[bB][01]+")) return new BigInteger(text.substring(2), 2).doubleValue();
            if (text.matches("0[oO][0-7]+")) return new BigInteger(text.substring(2), 8).doubleValue();
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    public static boolean truthy(Object value) {
        if (value == null || value instanceof MissingNode || value instanceof NullNode) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0.0 && !Double.isNaN(number.doubleValue());
        if (value instanceof String text) return !text.isEmpty();
        if (value instanceof JsonNode node) {
            if (node.isMissingNode() || node.isNull()) return false;
            if (node.isBoolean()) return node.booleanValue();
            if (node.isNumber()) return node.doubleValue() != 0.0 && !Double.isNaN(node.doubleValue());
            if (node.isTextual()) return !node.textValue().isEmpty();
            return true;
        }
        return true;
    }

    public static JsonNode normalizedNumberNode(double value) {
        if (!Double.isFinite(value)) return NullNode.getInstance();
        if (value == 0.0) return NODES.numberNode(0);
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            return NODES.numberNode(BigDecimal.valueOf(value).toBigIntegerExact());
        }
        return NODES.numberNode(BigDecimal.valueOf(value).stripTrailingZeros());
    }

    private static ObjectNode companionLegs(
            ObjectNode market,
            ObjectNode counts,
            boolean useB,
            List<String> missing,
            boolean ceiling) {
        Function<String, Double> at = key -> {
            JsonNode supplied = counts.get(key);
            if (!nullish(supplied)) return jsNumber(supplied);
            if (!ceiling || !missing.contains(key)) return 0.0;
            return switch (key) {
                case "mvrv_z" -> 6.0;
                case "resistance_count" -> 4.0;
                default -> 3.0;
            };
        };
        ObjectNode legs = object();
        if (useB) {
            legs.put("euphoria", frBRallyBand(valueOr(market.get("bounce_pct"), 0.0)));
            legs.put("momentum", frBMomentumBand(valueOr(market.get("daily_rsi"), 0.0),
                    nullableFinite(market.get("weekly_rsi"))));
            legs.put("valuation", frBResistanceBand(at.apply("resistance_count")));
            legs.put("distribution", frDistributionBand(at.apply("structure_count")));
            legs.put("vulnerability", frVulnerabilityBand(at.apply("sentiment_count")));
        } else {
            legs.put("euphoria", frEuphoriaBand(valueOr(market.get("fng_avg_3d"), 0.0)));
            legs.put("momentum", frMomentumBand(valueOr(market.get("weekly_rsi"), 0.0)));
            legs.put("valuation", frMvrvBand(at.apply("mvrv_z")));
            legs.put("distribution", frDistributionBand(at.apply("distribution_count")));
            legs.put("vulnerability", frVulnerabilityBand(at.apply("vulnerability_count")));
        }
        return legs;
    }

    public static String frChannel(Double percentBelow, boolean slowFalling, boolean priceBelowSlow) {
        if (percentBelow == null || !Double.isFinite(percentBelow)) return "none";
        if (percentBelow <= 20.0) return "A";
        return slowFalling && priceBelowSlow ? "B" : "none";
    }

    public static int frBRallyBand(double percent) {
        return percent > 35 ? 5 : percent > 25 ? 4 : percent > 18 ? 3 : percent > 12 ? 2 : percent > 8 ? 1 : 0;
    }

    public static int frBMomentumBand(double dailyRsi, Double weeklyRsi) {
        if (weeklyRsi != null && weeklyRsi >= 50.0) return 0;
        return dailyRsi > 65 ? 4 : dailyRsi > 58 ? 3 : dailyRsi > 52 ? 2 : dailyRsi > 45 ? 1 : 0;
    }

    public static int frBResistanceBand(double count) {
        return count >= 4 ? 5 : count == 3 ? 4 : count == 2 ? 3 : count == 1 ? 1 : 0;
    }

    public static int frBStructureBand(double count) {
        return Math.max(0, Math.min(3, toInt32(count)));
    }

    public static int frBSentimentBand(double count) {
        return Math.max(0, Math.min(3, toInt32(count)));
    }

    public static int frBMaturityPenalty(double bounceSessions) {
        return bounceSessions < 8.0 ? -2 : 0;
    }

    private static int fkMomentumBaseBand(double rsi) {
        return rsi < 30 ? 4 : rsi <= 35 ? 3 : rsi <= 40 ? 2 : rsi <= 45 ? 1 : 0;
    }

    private static Double smaSlope(List<Double> values, int n, int lookback) {
        if (values == null || values.size() < n + lookback) return null;
        Double now = sma(values, n);
        Double past = sma(values.subList(0, values.size() - lookback), n);
        return now == null || past == null || past == 0.0 ? null : round2((now / past - 1.0) * 100.0);
    }

    private static Boolean withinPercent(double first, Double second, double percent) {
        return second == null || second == 0.0 ? null : Math.abs(first / second - 1.0) * 100.0 <= percent;
    }

    public static Double pearson(List<Double> first, List<Double> second) {
        if (first.size() != second.size()) throw new ComputeValidationException(
                "pearson: length mismatch (" + first.size() + " vs " + second.size() + ")");
        if (first.size() < 2) return null;
        double totalFirst = 0.0;
        double totalSecond = 0.0;
        for (int i = 0; i < first.size(); i++) {
            totalFirst += first.get(i);
            totalSecond += second.get(i);
        }
        double meanFirst = totalFirst / first.size();
        double meanSecond = totalSecond / second.size();
        double cross = 0.0, squareFirst = 0.0, squareSecond = 0.0;
        for (int i = 0; i < first.size(); i++) {
            double deltaFirst = first.get(i) - meanFirst;
            double deltaSecond = second.get(i) - meanSecond;
            cross += deltaFirst * deltaSecond;
            squareFirst += deltaFirst * deltaFirst;
            squareSecond += deltaSecond * deltaSecond;
        }
        return squareFirst == 0.0 || squareSecond == 0.0
                ? null : cross / Math.sqrt(squareFirst * squareSecond);
    }

    public static ObjectNode correlationRegime(Double correlation) {
        ObjectNode out = object();
        if (correlation == null) {
            out.set("corr", NullNode.getInstance());
            out.put("label", "not computed");
            out.put("surcharge_applied", false);
            out.put("phase2_corr_condition", true);
            out.put("note", "correlation not computed (insufficient data or zero variance) — surcharge OFF, Phase 2 condition satisfied, by SKILL default");
            return out;
        }
        putNumber(out, "corr", correlation);
        out.put("label", correlation < 0 ? "inverse" : correlation < 0.2 ? "decoupled" : correlation <= 0.7 ? "mild" : "risk-on");
        out.put("surcharge_applied", correlation > 0.7);
        out.put("phase2_corr_condition", correlation < 0.8);
        out.set("note", NullNode.getInstance());
        return out;
    }

    private static ObjectNode positioningSeries(List<Double> history, boolean openInterest) {
        if (history.isEmpty()) return null;
        double current = history.get(history.size() - 1);
        ObjectNode out = object();
        putNumber(out, "latest", current);
        putNumber(out, "percentile_vs_history", percentileRank(history.subList(0, history.size() - 1), current));
        putString(out, "direction", direction(history, current));
        if (openInterest) {
            out.put("oi_90d_high_available", false);
            out.set("oi_within_5pct_of_90d_high", NullNode.getInstance());
        }
        return out;
    }

    private static List<Double> numericField(ArrayNode rows, String field) {
        List<Double> out = new ArrayList<>();
        if (rows == null) return out;
        for (JsonNode row : rows) {
            double value = row == null || row.isNull() ? 0.0 : jsNumber(row.get(field));
            if (Double.isFinite(value)) out.add(value);
        }
        return out;
    }

    private static String direction(List<Double> history, double current) {
        if (history.size() < 2) return null;
        double prior = history.get(history.size() - 2);
        return current > prior ? "rising" : current < prior ? "falling" : "flat";
    }

    private static OptionInstrument parseDeribitInstrument(String name) {
        Matcher matcher = DERIBIT_INSTRUMENT.matcher(name == null ? "" : name);
        if (!matcher.matches()) return null;
        Integer month = MONTHS.get(matcher.group(3));
        if (month == null) return null;
        long expiry = LocalDate.of(2000 + Integer.parseInt(matcher.group(4)), month,
                Integer.parseInt(matcher.group(2))).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        return new OptionInstrument(matcher.group(1), expiry,
                Double.parseDouble(matcher.group(5)), matcher.group(6));
    }

    private static OptionQuote closestByStrike(List<OptionQuote> chain, double target, String type) {
        OptionQuote best = null;
        for (OptionQuote quote : chain) {
            if (!quote.instrument().type().equals(type)) continue;
            if (best == null || Math.abs(quote.instrument().strike() - target)
                    < Math.abs(best.instrument().strike() - target)) {
                best = quote;
            }
        }
        return best;
    }

    private static ObjectNode unavailableVol(String reason, JsonNode dvol, Double rv30, String note) {
        ObjectNode out = object();
        out.put("available", false);
        out.put("reason", reason);
        out.set("dvol", dvol == null ? NullNode.getInstance() : dvol.deepCopy());
        putNumber(out, "rv30", rv30);
        out.put("note", note);
        return out;
    }

    private static Double netChange(List<Double> values, double latest, int days) {
        if (values.size() <= days) return null;
        double past = values.get(values.size() - 1 - days);
        return past == 0.0 ? null : round2((latest / past - 1.0) * 100.0);
    }

    private static double round8(double value) {
        return jsRound(value * 1e8) / 1e8;
    }

    private static boolean straddles(double floor, double ceiling, double threshold) {
        return floor < threshold && ceiling >= threshold;
    }

    private static Double nullableFinite(JsonNode node) {
        if (nullish(node) || !node.isNumber() || !Double.isFinite(node.doubleValue())) return null;
        return node.doubleValue();
    }

    private static boolean strictBoolean(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static double valueOr(JsonNode node, double fallback) {
        return nullish(node) ? fallback : jsNumber(node);
    }

    private static boolean nullish(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static int toInt32(double value) {
        if (!Double.isFinite(value) || value == 0.0) return 0;
        long truncated = (long) value;
        long unsigned = Math.floorMod(truncated, 1L << 32);
        return unsigned >= (1L << 31) ? (int) (unsigned - (1L << 32)) : (int) unsigned;
    }

    private static void validateActiveDenominator(int active) {
        if (active < 1 || active > 9) {
            throw new ComputeValidationException("active denominator must be an integer 1–9");
        }
    }

    private static List<Double> finiteValues(List<Double> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && Double.isFinite(value)).toList();
    }

    private static Double roundedNullable(Double value) {
        return value == null ? null : round2(value);
    }

    private static boolean numericNode(JsonNode node) {
        return node != null && node.isNumber();
    }

    private static double numericValue(JsonNode node) {
        return node == null || node.isNull() ? Double.NaN : node.doubleValue();
    }

    private static String jsString(JsonNode node) {
        if (node == null || node.isMissingNode()) return "";
        if (node.isNull()) return "null";
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return Boolean.toString(node.booleanValue());
        if (node.isNumber()) return node.asText();
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.isNull() ? "" : jsString(value)));
            return String.join(",", values);
        }
        return "[object Object]";
    }

    private static boolean isDefined(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    private static ObjectNode object() {
        return NODES.objectNode();
    }

    private static ArrayNode array() {
        return NODES.arrayNode();
    }

    private static void putNumber(ObjectNode target, String key, Number value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            target.set(key, NullNode.getInstance());
        } else {
            target.set(key, normalizedNumberNode(value.doubleValue()));
        }
    }

    private static void putBoolean(ObjectNode target, String key, Boolean value) {
        if (value == null) target.set(key, NullNode.getInstance());
        else target.put(key, value);
    }

    private static void putString(ObjectNode target, String key, String value) {
        if (value == null) target.set(key, NullNode.getInstance());
        else target.put(key, value);
    }

    private static void copyDefined(ObjectNode target, String key, JsonNode value) {
        if (value != null && !value.isMissingNode()) target.set(key, value.deepCopy());
    }

    private static double jsRound(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) return value;
        double floor = Math.floor(value);
        double result = value - floor < 0.5 ? floor : floor + 1.0;
        return result == 0.0 && value < 0.0 ? -0.0 : result;
    }

    private record StablecoinPoint(JsonNode date, double value) {
    }

    private record OptionInstrument(String currency, long expiryMillis, double strike, String type) {
    }

    private record OptionQuote(OptionInstrument instrument, double markIv, double underlyingPrice, double daysOut) {
        private OptionQuote withDays(double value) {
            return new OptionQuote(instrument, markIv, underlyingPrice, value);
        }
    }

    public static final class ComputeValidationException extends IllegalArgumentException {
        public ComputeValidationException(String message) {
            super(message);
        }
    }
}
