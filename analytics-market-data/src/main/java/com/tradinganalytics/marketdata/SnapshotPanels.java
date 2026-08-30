package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure proximity and snapshot-to-snapshot tripwire panels from {@code tools/lib.mjs}. */
public final class SnapshotPanels {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String PROXIMITY_NOTE = "DISCLOSED CONTEXT ONLY — not a scored input and NOT a trigger. Proximity to a boundary is a heads-up that the NEXT report may route differently; it never authorizes a deployment on its own. Adds no new rubric: every threshold is read from the classifiers the frameworks already score with.";
    private static final Map<String, Double> NEAR_BANDS = new LinkedHashMap<>();

    static {
        NEAR_BANDS.put("percentage points", 5.0);
        NEAR_BANDS.put("percentage points of 20-session slope", 0.5);
        NEAR_BANDS.put("RSI points", 3.0);
        NEAR_BANDS.put("index points", 3.0);
    }

    private SnapshotPanels() {
    }

    public static ObjectNode proximityPanel(JsonNode snapshot) {
        List<ObjectNode> items = new ArrayList<>();
        if (snapshot == null || snapshot.isNull() || snapshot.isMissingNode()) {
            ObjectNode empty = NODES.objectNode();
            empty.put("note", "no snapshot");
            empty.putArray("items");
            return empty;
        }
        Double spot = firstPresentNumber(at(snapshot, "spot", "canonical"), at(snapshot, "spot", "canonical_median"));

        Double percentBelow = presentNumber(at(snapshot, "high_1y", "pct_below"));
        Double high = presentNumber(at(snapshot, "high_1y", "value"));
        if (percentBelow != null && high != null && high != 0.0) {
            addHighItem(items, spot, percentBelow, high, 20.0, "fr_channel_A_eligibility",
                    "FR routes Channel A instead of B/stand-down (frChannel)");
            addHighItem(items, spot, percentBelow, high, 20.0, "fr_phase_cycle_cap_8_to_14",
                    "FR phase-of-cycle cap loosens from 8% to 14% (fr.phaseCycleCap)");
            addHighItem(items, spot, percentBelow, high, 10.0, "fr_phase_cycle_cap_14_to_uncapped",
                    "FR phase-of-cycle cap lifts entirely (fr.phaseCycleCap -> null)");
        }

        Double distance = presentNumber(at(snapshot, "weekly", "sma_200w", "pct_vs_spot"));
        if (distance != null) {
            boolean inBand = Math.abs(distance) <= 8.0;
            double edge = distance >= 0.0 ? 8.0 : -8.0;
            ObjectNode item = item("fk_gate6_200w_band", "weekly.sma_200w.pct_vs_spot", distance, edge,
                    ComputeMath.round2(Math.abs(distance) - 8.0), "percentage points",
                    inBand ? "inside the band" : distance > 0.0 ? "needs price DOWN" : "needs price UP");
            Double average = presentNumber(at(snapshot, "weekly", "sma_200w", "value"));
            putNullable(item, "price_move_required_pct", spot != null && spot != 0.0 && average != null
                    ? ComputeMath.round2((average * (1.0 + edge / 100.0) / spot - 1.0) * 100.0) : null);
            item.put("crossed", inBand);
            item.put("consequence", "FK gate 6 (200-week MA proximity) lights");
            push(items, item);
        }

        JsonNode trend = at(snapshot, "trend");
        Double slope = presentNumber(at(trend, "ma200_slope20_pct"));
        if (trend != null && !ComputeMath.truthy(at(trend, "insufficient")) && slope != null) {
            ObjectNode item = item("ma200_slope_sign_flip", "trend.ma200_slope20_pct", slope, 0.0,
                    ComputeMath.round2(Math.abs(slope)), "percentage points of 20-session slope",
                    slope > 0.0 ? "flattening toward a FALLING 200dma" : "steepening away from the flip");
            item.set("price_move_required_pct", NullNode.instance);
            item.put("crossed", slope < 0.0);
            item.put("consequence", "ma200_falling flips -> FR Channel B structure input, frChannel routing");
            push(items, item);
        }

        Double weeklyRsi = presentNumber(at(snapshot, "weekly", "rsi14", "rsi"));
        if (weeklyRsi != null) {
            Double edge = List.of(30.0, 35.0, 40.0, 45.0).stream().filter(value -> value >= weeklyRsi)
                    .findFirst().orElse(null);
            if (edge != null) {
                int fromBand = ComputeMath.fkMomentumBand(weeklyRsi, false).path("band").asInt();
                int toBand = ComputeMath.fkMomentumBand(edge + 1e-9, false).path("band").asInt();
                ObjectNode item = item("fk_momentum_band_edge", "weekly.rsi14.rsi", weeklyRsi, edge,
                        ComputeMath.round2(edge - weeklyRsi), "RSI points",
                        "needs RSI ABOVE this edge to LOSE a band (an exact edge keeps the higher band)");
                item.set("price_move_required_pct", NullNode.instance);
                item.put("crossed", false);
                item.put("consequence", "FK momentum leg drops from " + fromBand + " to " + toBand);
                push(items, item);
            }
            ObjectNode qualifier = item("frb_weekly_rsi50_qualifier", "weekly.rsi14.rsi", weeklyRsi, 50.0,
                    ComputeMath.round2(50.0 - weeklyRsi), "RSI points",
                    weeklyRsi < 50.0 ? "needs RSI UP" : "already at/above");
            qualifier.set("price_move_required_pct", NullNode.instance);
            qualifier.put("crossed", weeklyRsi >= 50.0);
            qualifier.put("consequence", "FR-B momentum leg forced to 0 (weekly RSI >= 50 hard qualifier)");
            push(items, qualifier);
        }

        Double sentiment = presentNumber(at(snapshot, "sentiment", "avg_3d"));
        if (sentiment != null) {
            Double edge = List.of(10.0, 15.0, 25.0, 35.0, 50.0).stream()
                    .filter(value -> value >= sentiment).findFirst().orElse(null);
            if (edge != null && !edge.equals(sentiment)) {
                ObjectNode item = item("fk_sentiment_band_edge", "sentiment.avg_3d", sentiment, edge,
                        ComputeMath.round2(edge - sentiment), "index points", "needs F&G UP to LOSE a band");
                item.set("price_move_required_pct", NullNode.instance);
                item.put("crossed", false);
                item.put("consequence", "FK sentiment leg drops from " + ComputeMath.fkSentimentBand(sentiment)
                        + " to " + ComputeMath.fkSentimentBand(edge));
                push(items, item);
            }
        }

        for (ObjectNode item : items) {
            Double band = NEAR_BANDS.get(item.path("gap_units").asText());
            boolean near = !item.path("crossed").asBoolean() && band != null
                    && Math.abs(item.path("gap").asDouble()) <= band;
            item.put("near", near);
            putNullable(item, "near_band", band);
        }
        items.sort(Comparator.comparingDouble(value -> Math.abs(value.path("gap").asDouble())));
        ObjectNode output = NODES.objectNode();
        output.put("note", PROXIMITY_NOTE);
        ObjectNode bands = output.putObject("near_bands");
        NEAR_BANDS.forEach((name, value) -> putNumber(bands, name, value));
        putNullable(output, "nearest", items.isEmpty() ? null : items.get(0).path("id").asText());
        output.put("near_count", items.stream().filter(value -> value.path("near").asBoolean()).count());
        ArrayNode array = output.putArray("items");
        items.forEach(array::add);
        return output;
    }

    public static ObjectNode tripwireDiff(ObjectNode previousSnapshot, ObjectNode nextSnapshot,
                                          ObjectNode checkpoints) {
        ArrayNode crossings = NODES.arrayNode();
        if (nextSnapshot != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = nextSnapshot.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String asset = field.getKey();
                if ("macro".equals(asset) || previousSnapshot == null || !previousSnapshot.has(asset)) continue;
                JsonNode previous = previousSnapshot.get(asset);
                JsonNode next = field.getValue();
                compareAsset(crossings, asset.toUpperCase(Locale.ROOT), previous, next,
                        checkpoints == null ? null : checkpoints.get(asset));
            }
        }
        ObjectNode output = NODES.objectNode();
        output.set("crossings", crossings);
        output.put("n_crossings", crossings.size());
        return output;
    }

    private static void compareAsset(ArrayNode crossings, String asset, JsonNode previous, JsonNode next,
                                     JsonNode checkpoint) {
        Double previousSentiment = presentNumber(at(previous, "sentiment", "avg_3d"));
        Double nextSentiment = presentNumber(at(next, "sentiment", "avg_3d"));
        if (previousSentiment != null && nextSentiment != null) {
            addNumericCrossing(crossings, asset, "fk_sentiment_band",
                    ComputeMath.fkSentimentBand(previousSentiment), ComputeMath.fkSentimentBand(nextSentiment),
                    previousSentiment, nextSentiment);
            JsonNode previousStreak = at(previous, "sentiment", "streaks_daily_prints");
            JsonNode nextStreak = at(next, "sentiment", "streaks_daily_prints");
            if (ComputeMath.truthy(previousStreak) && ComputeMath.truthy(nextStreak)) {
                Double fromValue = presentNumber(at(previousStreak, "le15"));
                Double toValue = presentNumber(at(nextStreak, "le15"));
                if (fromValue != null && toValue != null) {
                    addBooleanCrossing(crossings, asset, "fk_gate1_streak_le15_ge7",
                            fromValue >= 7.0, toValue >= 7.0, fromValue, toValue);
                }
            }
        }

        Double previousWeeklyRsi = presentNumber(at(previous, "weekly", "rsi14", "rsi"));
        Double nextWeeklyRsi = presentNumber(at(next, "weekly", "rsi14", "rsi"));
        if (previousWeeklyRsi != null && nextWeeklyRsi != null) {
            addNumericCrossing(crossings, asset, "fk_momentum_band",
                    ComputeMath.fkMomentumBand(previousWeeklyRsi, false).path("band").asInt(),
                    ComputeMath.fkMomentumBand(nextWeeklyRsi, false).path("band").asInt(),
                    previousWeeklyRsi, nextWeeklyRsi);
        }

        JsonNode previousSma = at(previous, "weekly", "sma_200w");
        JsonNode nextSma = at(next, "weekly", "sma_200w");
        JsonNode previousWithin = at(previousSma, "within_8pct");
        JsonNode nextWithin = at(nextSma, "within_8pct");
        if (previousWithin != null && previousWithin.isBoolean() && nextWithin != null && nextWithin.isBoolean()
                && previousWithin.booleanValue() != nextWithin.booleanValue()) {
            ObjectNode crossing = baseCrossing(asset, "gate6_within_8pct");
            crossing.put("from", previousWithin.booleanValue());
            crossing.put("to", nextWithin.booleanValue());
            crossings.add(crossing);
        }

        JsonNode previousTrend = at(previous, "trend");
        JsonNode nextTrend = at(next, "trend");
        Double previousBelow = presentNumber(at(previous, "high_1y", "pct_below"));
        Double nextBelow = presentNumber(at(next, "high_1y", "pct_below"));
        if (previousTrend != null && nextTrend != null && !ComputeMath.truthy(at(previousTrend, "insufficient"))
                && !ComputeMath.truthy(at(nextTrend, "insufficient")) && previousBelow != null && nextBelow != null) {
            String fromChannel = ComputeMath.frChannel(previousBelow,
                    isTrue(at(previousTrend, "ma200_falling")), isTrue(at(previousTrend, "price_below_ma200")));
            String toChannel = ComputeMath.frChannel(nextBelow,
                    isTrue(at(nextTrend, "ma200_falling")), isTrue(at(nextTrend, "price_below_ma200")));
            if (!fromChannel.equals(toChannel)) {
                ObjectNode crossing = baseCrossing(asset, "fr_channel_routing");
                crossing.put("from", fromChannel);
                crossing.put("to", toChannel);
                crossings.add(crossing);
            }
            Integer fromCap = ComputeMath.frPhaseCycleCap(previousBelow);
            Integer toCap = ComputeMath.frPhaseCycleCap(nextBelow);
            if (!java.util.Objects.equals(fromCap, toCap)) {
                ObjectNode crossing = baseCrossing(asset, "fr_phase_cycle_cap");
                putNullable(crossing, "from", fromCap);
                putNullable(crossing, "to", toCap);
                putNumber(crossing, "prev_value", previousBelow);
                putNumber(crossing, "next_value", nextBelow);
                crossings.add(crossing);
            }
        }

        Double previousFunding = presentNumber(at(previous, "funding", "mean_annualized_pct"));
        Double nextFunding = presentNumber(at(next, "funding", "mean_annualized_pct"));
        if (previousFunding != null && nextFunding != null) {
            addNumericCrossing(crossings, asset, "funding_sign", signNumber(previousFunding), signNumber(nextFunding),
                    previousFunding, nextFunding);
        }

        if (previousSentiment != null && nextSentiment != null) {
            addNumericCrossing(crossings, asset, "fr_euphoria_band", ComputeMath.frEuphoriaBand(previousSentiment),
                    ComputeMath.frEuphoriaBand(nextSentiment), previousSentiment, nextSentiment);
        }
        if (previousWeeklyRsi != null && nextWeeklyRsi != null) {
            addNumericCrossing(crossings, asset, "fr_momentum_band", ComputeMath.frMomentumBand(previousWeeklyRsi),
                    ComputeMath.frMomentumBand(nextWeeklyRsi), previousWeeklyRsi, nextWeeklyRsi);
            addBooleanCrossing(crossings, asset, "frb_weekly_rsi50_qualifier", previousWeeklyRsi >= 50.0,
                    nextWeeklyRsi >= 50.0, previousWeeklyRsi, nextWeeklyRsi);
        }

        if (previousTrend != null && nextTrend != null && !ComputeMath.truthy(at(previousTrend, "insufficient"))
                && !ComputeMath.truthy(at(nextTrend, "insufficient"))) {
            Double previousBounce = presentNumber(at(previousTrend, "bounce_pct"));
            Double nextBounce = presentNumber(at(nextTrend, "bounce_pct"));
            if (previousBounce != null && nextBounce != null) {
                addNumericCrossing(crossings, asset, "frb_rally_band", ComputeMath.frBRallyBand(previousBounce),
                        ComputeMath.frBRallyBand(nextBounce), previousBounce, nextBounce);
            }
            Double previousDailyRsi = presentNumber(at(previousTrend, "rsi14"));
            Double nextDailyRsi = presentNumber(at(nextTrend, "rsi14"));
            if (previousDailyRsi != null && nextDailyRsi != null
                    && previousWeeklyRsi != null && nextWeeklyRsi != null) {
                addNumericCrossing(crossings, asset, "frb_momentum_band",
                        ComputeMath.frBMomentumBand(previousDailyRsi, previousWeeklyRsi),
                        ComputeMath.frBMomentumBand(nextDailyRsi, nextWeeklyRsi), previousDailyRsi, nextDailyRsi);
            }
            Double previousAge = presentNumber(at(previousTrend, "bounce_age_sessions"));
            Double nextAge = presentNumber(at(nextTrend, "bounce_age_sessions"));
            if (previousAge != null && nextAge != null) {
                addNumericCrossing(crossings, asset, "frb_maturity_penalty",
                        ComputeMath.frBMaturityPenalty(previousAge), ComputeMath.frBMaturityPenalty(nextAge),
                        previousAge, nextAge);
            }
        }

        JsonNode previousSustained = at(previous, "funding", "sustained3_below_minus5");
        JsonNode nextSustained = at(next, "funding", "sustained3_below_minus5");
        if (previousSustained != null && previousSustained.isBoolean()
                && nextSustained != null && nextSustained.isBoolean()
                && previousSustained.booleanValue() != nextSustained.booleanValue()) {
            ObjectNode crossing = baseCrossing(asset, "fr_gate8_sustained_negative");
            crossing.put("from", previousSustained.booleanValue());
            crossing.put("to", nextSustained.booleanValue());
            crossings.add(crossing);
        }

        Double line = presentNumber(at(checkpoint, "line"));
        Double previousSpot = presentNumber(at(previous, "spot", "canonical"));
        Double nextSpot = presentNumber(at(next, "spot", "canonical"));
        Double previousAdr = presentNumber(at(previous, "daily", "adr5", "adr"));
        Double nextAdr = presentNumber(at(next, "daily", "adr5", "adr"));
        if (line != null && previousSpot != null && nextSpot != null && previousAdr != null && nextAdr != null
                && previousAdr != 0.0 && nextAdr != 0.0) {
            double fromDistance = Math.abs(previousSpot - line) / previousAdr;
            double toDistance = Math.abs(nextSpot - line) / nextAdr;
            if (Math.floor(fromDistance) != Math.floor(toDistance)) {
                ObjectNode crossing = baseCrossing(asset, "checkpoint_adr_distance");
                putNumber(crossing, "from", ComputeMath.round2(fromDistance));
                putNumber(crossing, "to", ComputeMath.round2(toDistance));
                copy(crossing, "line", at(checkpoint, "line"));
                crossings.add(crossing);
            }
        }
    }

    private static void addHighItem(List<ObjectNode> items, Double spot, double percentBelow, double high,
                                    double threshold, String id, String consequence) {
        double target = high * (1.0 - threshold / 100.0);
        ObjectNode item = item(id, "high_1y.pct_below", percentBelow, threshold,
                ComputeMath.round2(percentBelow - threshold), "percentage points",
                percentBelow > threshold ? "needs price UP" : "already past — currently on the far side");
        putNullable(item, "price_move_required_pct", spot != null && spot != 0.0
                ? ComputeMath.round2((target / spot - 1.0) * 100.0) : null);
        item.put("crossed", percentBelow <= threshold);
        item.put("consequence", consequence);
        push(items, item);
    }

    private static ObjectNode item(String id, String metric, double value, double threshold, double gap,
                                   String units, String direction) {
        ObjectNode item = NODES.objectNode();
        item.put("id", id);
        item.put("metric", metric);
        putNumber(item, "value", value);
        putNumber(item, "threshold", threshold);
        putNumber(item, "gap", gap);
        item.put("gap_units", units);
        item.put("direction", direction);
        return item;
    }

    private static void push(List<ObjectNode> items, ObjectNode item) {
        Double gap = presentNumber(item.get("gap"));
        if (gap != null) items.add(item);
    }

    private static void addNumericCrossing(ArrayNode crossings, String asset, String type,
                                           int from, int to, double previousValue, double nextValue) {
        if (from == to) return;
        ObjectNode crossing = baseCrossing(asset, type);
        crossing.put("from", from);
        crossing.put("to", to);
        putNumber(crossing, "prev_value", previousValue);
        putNumber(crossing, "next_value", nextValue);
        crossings.add(crossing);
    }

    private static void addBooleanCrossing(ArrayNode crossings, String asset, String type,
                                           boolean from, boolean to, double previousValue, double nextValue) {
        if (from == to) return;
        ObjectNode crossing = baseCrossing(asset, type);
        crossing.put("from", from);
        crossing.put("to", to);
        putNumber(crossing, "prev_value", previousValue);
        putNumber(crossing, "next_value", nextValue);
        crossings.add(crossing);
    }

    private static ObjectNode baseCrossing(String asset, String type) {
        ObjectNode crossing = NODES.objectNode();
        crossing.put("asset", asset);
        crossing.put("type", type);
        return crossing;
    }

    private static int signNumber(double value) {
        return value > 0.0 ? 1 : value < 0.0 ? -1 : 0;
    }

    private static boolean isTrue(JsonNode value) {
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static JsonNode at(JsonNode root, String... path) {
        JsonNode value = root;
        for (String segment : path) {
            if (value == null || !value.isObject()) return null;
            value = value.get(segment);
        }
        return value;
    }

    private static Double presentNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        double number = ComputeMath.jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static Double firstPresentNumber(JsonNode first, JsonNode second) {
        Double value = presentNumber(first);
        return value != null ? value : presentNumber(second);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) target.set(key, NullNode.instance); else putNumber(target, key, value);
    }

    private static void putNullable(ObjectNode target, String key, Integer value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void putNullable(ObjectNode target, String key, String value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void copy(ObjectNode target, String key, JsonNode value) {
        target.set(key, value == null ? NullNode.instance : value.deepCopy());
    }
}
