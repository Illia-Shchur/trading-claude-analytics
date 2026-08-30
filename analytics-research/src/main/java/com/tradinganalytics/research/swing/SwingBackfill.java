package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Complete Java port of {@code tools/swing-backfill.mjs}. */
public final class SwingBackfill {
    public static final long BAR_MS = 4L * 60 * 60 * 1000;
    public static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final String DATA_VISION_BASE = "https://data.binance.vision/data/futures/um/daily/metrics";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private SwingBackfill() {}

    public record Options(double years, Path cacheDirectory, long now) {
        public Options { if (cacheDirectory == null) cacheDirectory = Path.of("data/swing-calibration/cache"); }
        public static Options defaults() { return new Options(3, Path.of("data/swing-calibration/cache"), System.currentTimeMillis()); }
    }

    /** Injectable provider boundary; the default implementation is cache-aware HTTP. */
    public interface HistoricalSource {
        ArrayNode klines(String asset, long start, long end, boolean futures, Path cacheDirectory) throws Exception;
        ArrayNode funding(String asset, long start, long end, Path cacheDirectory) throws Exception;
        ArrayNode metrics(String asset, long start, long end, Path cacheDirectory) throws Exception;
        ArrayNode macro(long start, long end, Path cacheDirectory) throws Exception;
        ArrayNode sentiment(long start, long end, Path cacheDirectory) throws Exception;
        ArrayNode valuation(String asset, long start, long end, Path cacheDirectory) throws Exception;
    }

    public static ObjectNode backfillAsset(String asset) throws Exception {
        return backfillAsset(asset, Options.defaults());
    }

    public static ObjectNode backfillAsset(String asset, Options options) throws Exception {
        return backfillAsset(asset, options, new HttpHistoricalSource());
    }

    public static ObjectNode backfillAsset(String assetInput, Options options, HistoricalSource source) throws Exception {
        String asset = String.valueOf(assetInput).toLowerCase(Locale.ROOT);
        long end = Math.floorDiv(options.now(), BAR_MS) * BAR_MS;
        long start = (long) (end - options.years() * 365.25 * DAY_MS);
        long warmupStart = start - 365L * DAY_MS;
        Path cache = options.cacheDirectory();
        ArrayNode spot = dedupeSort(source.klines(asset, warmupStart, end, false, cache));
        ArrayNode futures = dedupeSort(source.klines(asset, warmupStart, end, true, cache));
        ArrayNode funding = dedupeSort(source.funding(asset, warmupStart, end, cache));
        ArrayNode oi = dedupeSort(source.metrics(asset, warmupStart, end, cache));
        ArrayNode macro = source.macro(warmupStart, end, cache);
        ArrayNode sentiment = source.sentiment(warmupStart, end, cache);
        ArrayNode valuation = source.valuation(asset, warmupStart, end, cache);
        ArrayNode benchmark = "btc".equals(asset) ? spot : dedupeSort(source.klines("btc", warmupStart, end, false, cache));
        ArrayNode allLabels = labelsForBars(spot), labels = JSON.arrayNode(), requestedBars = JSON.arrayNode();
        for (JsonNode label : allLabels) if (number(label.get("time")) >= start && number(label.get("time")) < end) labels.add(label.deepCopy());
        for (JsonNode row : spot) if (number(row.get("time")) >= start && number(row.get("time")) < end) requestedBars.add(row.deepCopy());
        ArrayNode datasets = JSON.arrayNode();
        for (Spec spec : List.of(new Spec("fallen_knives", null, 1), new Spec("flying_rocket", "A", -1), new Spec("flying_rocket", "B", -1))) {
            ArrayNode generated = setupRows(asset, spot, futures, oi, funding, macro, sentiment, valuation, benchmark,
                    spec.direction(), spec.framework(), spec.channel(), labels);
            ArrayNode features = JSON.arrayNode(); Set<Long> featureTimes = new LinkedHashSet<>();
            for (JsonNode feature : generated) if (number(feature.get("time")) >= start && number(feature.get("time")) < end) {
                features.add(feature.deepCopy()); featureTimes.add((long) number(feature.get("time")));
            }
            int alignedLabels = 0; for (JsonNode label : labels) if (featureTimes.contains((long) number(label.get("time")))) alignedLabels++;
            ObjectNode dataset = JSON.objectNode().put("asset", asset).put("symbol", symbolFor(asset)).put("framework", spec.framework());
            dataset.set("channel", spec.channel() == null ? NullNode.instance : JSON.textNode(spec.channel()));
            dataset.set("labels", labels.deepCopy()); dataset.set("features", features); dataset.put("bars", spot.size());
            dataset.put("coverage", alignedLabels == labels.size() ? "COMPLETE" : !features.isEmpty() ? "PARTIAL" : "HISTORICAL_PROXY");
            ObjectNode coverage = coverageMeta(start, end, warmupStart, spot, benchmark, futures, funding, oi, macro,
                    sentiment, valuation, requestedBars, labels, features, alignedLabels); dataset.set("coverage_meta", coverage);
            ObjectNode provenance = JSON.objectNode()
                    .put("spot", "Binance public spot klines")
                    .put("benchmark_spot", "Binance BTCUSDT public spot klines synchronized by completed 4h bar")
                    .put("futures", "Binance USD-M public futures klines")
                    .put("funding", "Binance /fapi/v1/fundingRate")
                    .put("open_interest", "Binance Data Vision daily metrics; OI samples grouped only within each 4h bucket")
                    .put("macro", "FRED DTWEXBGS and DFII10 latest-revised history; prior completed observation; vintage risk")
                    .put("sentiment", "Alternative.me Fear & Greed daily API; prior completed observation")
                    .put("valuation", "Coin Metrics Community CapMVRVCur daily; price-derived 1y/200w proxies when unavailable");
            dataset.set("provenance", provenance); datasets.add(dataset);
        }
        ObjectNode result = JSON.objectNode().put("asset", asset).put("symbol", symbolFor(asset)).put("interval", "4h")
                .put("start", start).put("end", end).put("warmup_start", warmupStart).put("warmup_days", 365);
        result.set("bars", requestedBars); result.set("labels", labels); result.set("datasets", datasets);
        result.put("source", "Binance/FRED/Coin Metrics/Alternative.me historical feature backfill")
                .put("coverage", "ALIGNED_MULTI_SOURCE").put("point_in_time_safe", false);
        result.set("proxy_contract", JSON.objectNode().put("status", "UNACCEPTED").put("accepted", false)
                .put("note", "Historical macro/sentiment/valuation/structure are proxies and do not reproduce every live ETF/on-chain/reserve/stablecoin input."));
        return result;
    }

    /** Source-order first row for each numeric timestamp. */
    public static LinkedHashMap<Long, ObjectNode> firstByTime(JsonNode rowsNode) {
        LinkedHashMap<Long, ObjectNode> index = new LinkedHashMap<>();
        for (JsonNode row : array(rowsNode)) {
            double time = number(row.get("time"));
            if (!Double.isNaN(time)) index.putIfAbsent((long) time, (ObjectNode) row);
        }
        return index;
    }

    /** Latest observation whose availability is strictly before the requested time. */
    public static ObjectNode latestPrior(JsonNode rowsNode, long beforeTime) {
        ObjectNode latest = null; double latestTime = Double.NEGATIVE_INFINITY;
        for (JsonNode row : array(rowsNode)) {
            double available = finiteNumber(row.get("available_at"));
            if (Double.isFinite(available) && available < beforeTime && available >= latestTime) {
                latest = (ObjectNode) row; latestTime = available;
            }
        }
        return latest;
    }

    public static ObjectNode mechanicalTrigger(ArrayNode rows, int index, String framework, String channel,
            double ema20Value, double rsiValue) {
        JsonNode row = rows.get(index), previous = rows.get(index - 1);
        double[] closes = doubles(rows, value -> number(value.get("close")));
        double previousEma = ema(closes, 20)[Math.max(0, index - 1)];
        ArrayNode prior = slice(rows, Math.max(0, index - 30), index);
        double support = prior.isEmpty() ? Double.NaN : min(prior, "low");
        double resistance = prior.isEmpty() ? Double.NaN : max(prior, "high");
        String regime = regimeAt(rows, index); boolean valid = false; String kind = "NONE", reason = "setup conditions not met";
        if ("fallen_knives".equals(framework)) {
            boolean reversal = Double.isFinite(previousEma) && number(previous.get("close")) <= previousEma && number(row.get("close")) > ema20Value;
            boolean reclaim = Double.isFinite(support) && number(row.get("low")) < support && number(row.get("close")) > support
                    && number(row.get("close")) > number(previous.get("close"));
            valid = reversal || reclaim; if (valid) { kind = reversal ? "FK_REVERSAL_RECLAIM" : "FK_SUPPORT_RECLAIM";
                reason = "completed 4h reversal/reclaim through EMA20 or prior support"; }
        } else if ("A".equals(channel)) {
            valid = !"TREND_DOWN".equals(regime) && Double.isFinite(resistance) && number(row.get("high")) >= resistance
                    && number(row.get("close")) < resistance && rsiValue >= 55;
            if (valid) { kind = "FR_A_EUPHORIA_REJECTION"; reason = "completed 4h euphoria/distribution rejection at prior resistance"; }
        } else if ("B".equals(channel)) {
            valid = "TREND_DOWN".equals(regime) && Double.isFinite(previousEma) && number(previous.get("close")) > previousEma
                    && number(row.get("close")) < ema20Value && number(row.get("high")) >= ema20Value;
            if (valid) { kind = "FR_B_BEAR_RALLY_FAILURE"; reason = "completed 4h bear-rally failure below EMA20 in down regime"; }
        }
        ObjectNode output = JSON.objectNode().put("valid", valid).put("kind", kind).put("reason", reason).put("regime", regime)
                .put("timeframe", "4h").put("completed_bar", true).put("completed_bar_required", true).put("age_bars", 0)
                .put("window_bars", 2).put("created_at", iso((long) number(row.get("time")) + BAR_MS)).put("level", number(row.get("close")))
                .put("source", "mechanical completed-bar trigger; no analyst discretion");
        return output;
    }

    public static ObjectNode setupFamiliesAt(ArrayNode rows, int index, String framework, String channel,
            JsonNode trigger, JsonNode context) {
        JsonNode row = index >= 0 && index < rows.size() ? rows.get(index) : null;
        JsonNode previous = index - 1 >= 0 && index - 1 < rows.size() ? rows.get(index - 1) : null;
        ArrayNode prior = slice(rows, Math.max(0, index - 30), Math.max(0, index));
        ArrayNode baseline = slice(rows, Math.max(0, index - 30), Math.max(0, index - 3));
        if (row == null || previous == null || prior.size() < 3) { ObjectNode empty = JSON.objectNode().put("primary", "UNSPECIFIED");
            empty.set("families", JSON.arrayNode().add("UNSPECIFIED")); empty.set("flags", JSON.objectNode()); return empty; }
        double support = baseline.isEmpty() ? Double.NaN : min(baseline, "low"), resistance = baseline.isEmpty() ? Double.NaN : max(baseline, "high");
        JsonNode priorLowRow = rows.get(Math.max(0, index - 6)); double priorSwingLow = finiteNumber(priorLowRow.get("low"));
        double priorSwingHigh = finiteNumber(priorLowRow.get("high"));
        String triggerKind = trigger != null && truthy(trigger.get("kind")) && !"NONE".equals(trigger.get("kind").asText()) ? trigger.get("kind").asText() : null;
        JsonNode factors = context != null && context.path("factors").isObject() ? context.path("factors") : JSON.objectNode();
        JsonNode derivatives = factors.path("derivatives"), sentiment = factors.path("sentiment"), technical = factors.path("technical");
        double return24h = finiteNumber(technical.get("return_24h")), funding3d = finiteNumber(derivatives.get("funding_mean_3d"));
        double oi3d = finiteNumber(derivatives.get("oi_change_3d_pct")), spotCvd = finiteNumber(derivatives.get("spot_cvd_24h_usd"));
        double futuresCvd = finiteNumber(derivatives.get("futures_cvd_24h_usd")), sentimentLevel = finiteNumber(sentiment.get("fear_greed"));
        double sentimentDelta = finiteNumber(sentiment.get("fear_greed_3d_change")), ret4h = finiteNumber(technical.get("return_4h"));
        double ret24n = finiteNumber(technical.get("return_24h_normalized")), ret3n = finiteNumber(technical.get("return_3d_normalized"));
        double closeLocation = finiteNumber(technical.get("close_location")), volumeZ = finiteNumber(technical.get("volume_z_90d"));
        double spotZ = finiteNumber(derivatives.get("spot_cvd_24h_z")), futuresZ = finiteNumber(derivatives.get("futures_cvd_24h_z"));
        double divergenceZ = finiteNumber(derivatives.get("spot_futures_divergence_z")), oiZ = finiteNumber(derivatives.get("oi_change_24h_z"));
        double fundingZ = finiteNumber(derivatives.get("funding_mean_24h_z")), positioningZ = finiteNumber(derivatives.get("top_vs_global_positioning_z"));
        double returnPercentile = finiteNumber(technical.get("return_3d_prior_percentile"));
        double relative4h = finiteNumber(factors.path("relative").get("return_4h_vs_btc"));
        double e20 = finiteNumber(technical.get("ema20")), e50 = finiteNumber(technical.get("ema50"));
        ObjectNode flags = JSON.objectNode();
        if ("fallen_knives".equals(framework)) {
            flags.put("FK_REVERSAL_RECLAIM", "FK_REVERSAL_RECLAIM".equals(triggerKind));
            flags.put("FK_SUPPORT_RECLAIM", "FK_SUPPORT_RECLAIM".equals(triggerKind) || Double.isFinite(support)
                    && number(row.get("low")) < support && number(row.get("close")) > support);
            flags.put("FK_HIGHER_LOW", Double.isFinite(priorSwingLow) && number(row.get("low")) > priorSwingLow && number(row.get("close")) > number(previous.get("close")));
            flags.put("FK_DELEVERAGING_REVERSAL", finite(row.get("oi_open")) && finite(row.get("oi_close"))
                    && number(row.get("oi_close")) < number(row.get("oi_open")) && number(row.get("close")) > number(previous.get("close")));
            flags.put("FK_DERIVATIVES_WASHOUT", finiteAll(funding3d, oi3d) && funding3d < 0 && oi3d < -.01 && number(row.get("close")) > number(previous.get("close")));
            flags.put("FK_ABSORPTION_DIVERGENCE", finiteAll(spotCvd, futuresCvd, return24h) && spotCvd < 0 && futuresCvd < 0 && return24h > 0);
            flags.put("FK_SENTIMENT_REVERSAL", finiteAll(sentimentLevel, sentimentDelta) && sentimentLevel <= 35 && sentimentDelta > 0 && number(row.get("close")) > number(previous.get("close")));
            flags.put("FK_DELEVERAGING_ABSORPTION", finiteAll(ret3n, oiZ, ret4h, closeLocation) && ret3n <= -.75 && oiZ <= -.5 && ret4h > 0 && closeLocation >= .55
                    && (Double.isFinite(futuresZ) && futuresZ <= 0 || Double.isFinite(divergenceZ) && divergenceZ >= .5));
            flags.put("FK_POSITIONING_DIVERGENCE_RECLAIM", finiteAll(ret3n, oiZ, positioningZ, ret4h, closeLocation) && ret3n <= -.5 && oiZ <= 0 && positioningZ >= .5 && ret4h > 0 && closeLocation >= .55);
            flags.put("FK_SENTIMENT_DELEVERAGING_TURN", finiteAll(ret3n, oiZ, sentimentLevel, sentimentDelta, ret4h, closeLocation) && ret3n <= -.5 && oiZ <= 0 && sentimentLevel <= 45 && sentimentDelta > 0 && ret4h > 0 && closeLocation >= .55);
            flags.put("FK_CONTEXTUAL_DELEVERAGING_RECLAIM", finiteAll(ret3n, oiZ, ret4h, closeLocation) && ret3n <= -.5 && oiZ <= 0 && ret4h > 0 && closeLocation >= .55
                    && (Double.isFinite(positioningZ) && positioningZ >= 0 || finiteAll(sentimentLevel, sentimentDelta) && sentimentLevel <= 45 && sentimentDelta > 0));
            flags.put("FK_RELATIVE_DELEVERAGING_RECLAIM_V1", finiteAll(returnPercentile, oiZ, relative4h) && returnPercentile <= .2 && oiZ <= 0
                    && number(row.get("close")) > number(previous.get("high")) && relative4h > 0);
            flags.put("FK_FUNDING_FLUSH_RECLAIM", finiteAll(fundingZ, ret24n, ret4h, closeLocation) && fundingZ <= -1 && ret24n <= -.25 && ret4h > 0 && closeLocation >= .55);
            flags.put("FK_SPOT_ABSORPTION", finiteAll(return24h, futuresZ, divergenceZ, ret4h, closeLocation) && return24h < 0 && futuresZ <= -.5 && divergenceZ >= .5 && ret4h > 0 && closeLocation >= .55);
            flags.put("FK_VOLATILITY_EXHAUSTION", finiteAll(ret3n, volumeZ, ret4h, closeLocation) && ret3n <= -1 && volumeZ >= .5 && ret4h > 0 && closeLocation >= .65);
        } else if ("A".equals(channel)) {
            flags.put("FR_A_EUPHORIA_REJECTION", "FR_A_EUPHORIA_REJECTION".equals(triggerKind) || Double.isFinite(resistance) && number(row.get("high")) >= resistance && number(row.get("close")) < resistance);
            flags.put("FR_A_DISTRIBUTION", Double.isFinite(resistance) && number(row.get("high")) >= resistance * .995 && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_A_FAILED_BREAKOUT", Double.isFinite(resistance) && number(previous.get("high")) >= resistance && number(row.get("high")) >= resistance && number(row.get("close")) < resistance);
            flags.put("FR_A_DERIVATIVES_CROWDING", finiteAll(funding3d, oi3d) && funding3d > 0 && oi3d > .01 && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_A_DISTRIBUTION_DIVERGENCE", finiteAll(spotCvd, futuresCvd, return24h) && spotCvd < 0 && futuresCvd < 0 && return24h > 0);
            flags.put("FR_A_SENTIMENT_ROLLOVER", finiteAll(sentimentLevel, sentimentDelta) && sentimentLevel >= 65 && sentimentDelta < 0 && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_A_LEVERAGED_REJECTION", finiteAll(ret3n, fundingZ, oiZ, ret4h, closeLocation) && ret3n >= .75 && fundingZ >= .5 && oiZ >= .25 && ret4h < 0 && closeLocation <= .45);
            flags.put("FR_A_CVD_DISTRIBUTION", finiteAll(return24h, futuresZ, divergenceZ, ret4h, closeLocation) && return24h > 0 && futuresZ >= .5 && divergenceZ <= -.5 && ret4h < 0 && closeLocation <= .5);
            flags.put("FR_A_TOP_CROWDING", finiteAll(resistance, fundingZ, oiZ, ret4h, closeLocation) && number(row.get("close")) >= resistance * .97 && fundingZ >= .5 && oiZ >= 0 && ret4h < 0 && closeLocation <= .4);
        } else {
            double[] e = ema(doubles(rows, value -> number(value.get("close"))), 20); double previousEma = e[index - 1]; String regime = regimeAt(rows, index);
            flags.put("FR_B_BEAR_RALLY_FAILURE", "FR_B_BEAR_RALLY_FAILURE".equals(triggerKind) || "TREND_DOWN".equals(regime) && Double.isFinite(previousEma)
                    && Double.isFinite(e[index]) && number(previous.get("close")) > previousEma && number(row.get("close")) < e[index] && number(row.get("high")) >= e[index]);
            flags.put("FR_B_LOWER_HIGH", Double.isFinite(priorSwingHigh) && number(row.get("high")) < priorSwingHigh && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_B_BREAKDOWN_RETEST", Double.isFinite(support) && number(previous.get("close")) < support && number(row.get("high")) >= support && number(row.get("close")) < support);
            flags.put("FR_B_DERIVATIVES_RELOAD_FAILURE", "TREND_DOWN".equals(regime) && finiteAll(funding3d, oi3d) && funding3d > 0 && oi3d > .01 && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_B_FLOW_REJECTION", "TREND_DOWN".equals(regime) && finiteAll(futuresCvd, return24h) && futuresCvd > 0 && return24h > 0 && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_B_SENTIMENT_RELIEF_FAILURE", "TREND_DOWN".equals(regime) && Double.isFinite(sentimentDelta) && sentimentDelta > 0 && number(row.get("close")) < number(previous.get("close")));
            flags.put("FR_B_RALLY_FAILURE", finiteAll(e20, e50, ret24n, ret4h, futuresZ) && e20 < e50 && ret24n >= .25 && ret4h < 0 && number(row.get("close")) < e20 && futuresZ <= 0);
            flags.put("FR_B_BREAKDOWN_EXPANSION", finiteAll(e20, e50, support, volumeZ, futuresZ, closeLocation) && e20 < e50 && number(row.get("close")) < support && volumeZ >= .5 && futuresZ <= -.5 && closeLocation <= .45);
            flags.put("FR_B_WEAK_SPOT_RETEST", finiteAll(e20, e50, return24h, spotZ, ret4h, closeLocation, fundingZ) && e20 < e50 && return24h > 0 && spotZ <= 0 && ret4h < 0 && closeLocation <= .5 && fundingZ >= -1);
        }
        ArrayNode families = JSON.arrayNode(); flags.fields().forEachRemaining(entry -> { if (entry.getValue().asBoolean()) families.add(entry.getKey()); });
        if (triggerKind != null && !contains(families, triggerKind)) families.insert(0, triggerKind);
        ObjectNode result = JSON.objectNode().put("primary", families.isEmpty() ? "UNSPECIFIED" : families.get(0).asText());
        result.set("families", families.isEmpty() ? JSON.arrayNode().add("UNSPECIFIED") : dedupeStrings(families)); result.set("flags", flags); return result;
    }

    public static ArrayNode labelsForBars(ArrayNode rows) {
        ArrayNode result = JSON.arrayNode(); int horizon = 180;
        for (int index = 120; index < rows.size() - horizon; index++) {
            double total = 0; for (int i = index - 119; i <= index; i++) total += trueRange(rows.get(i), rows.get(i - 1));
            double unit = total / 120; if (!Double.isFinite(unit) || unit <= 0) continue;
            JsonNode current = rows.get(index); double close = number(current.get("close"));
            double longFav = close + 1.5 * unit, longBad = close - unit, shortFav = close - 1.5 * unit, shortBad = close + unit;
            Integer lf = null, lb = null, sf = null, sb = null;
            for (int next = index + 1; next <= index + horizon; next++) {
                JsonNode row = rows.get(next); if (lf == null && number(row.get("high")) >= longFav) lf = next;
                if (lb == null && number(row.get("low")) <= longBad) lb = next; if (sf == null && number(row.get("low")) <= shortFav) sf = next;
                if (sb == null && number(row.get("high")) >= shortBad) sb = next;
            }
            long time = (long) number(current.get("time")); Instant instant = Instant.ofEpochMilli(time);
            ObjectNode label = JSON.objectNode().put("time", time).put("month", instant.atZone(ZoneOffset.UTC).getYear() * 12 + instant.atZone(ZoneOffset.UTC).getMonthValue() - 1)
                    .put("close", close).put("atr_20d", unit).put("long", lf != null && (lb == null || lf < lb)).put("short", sf != null && (sb == null || sf < sb));
            label.set("long_favorable_bars", lf == null ? NullNode.instance : JSON.numberNode(lf - index)); label.set("short_favorable_bars", sf == null ? NullNode.instance : JSON.numberNode(sf - index));
            label.put("long_early_capture", lf != null && lf - index <= 45).put("short_early_capture", sf != null && sf - index <= 45).put("early_window_bars", 45)
                    .put("long_resolution_bars", minOr(lf, lb, horizon)).put("short_resolution_bars", minOr(sf, sb, horizon)); result.add(label);
        }
        return result;
    }

    /** Cache-compatible network implementation of the six historical sources. */
    public static final class HttpHistoricalSource implements HistoricalSource {
        private final HttpClient client;
        public HttpHistoricalSource() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()); }
        public HttpHistoricalSource(HttpClient client) { this.client = client; }

        @Override public ArrayNode klines(String asset, long start, long end, boolean futures, Path cache) throws Exception {
            String symbol = symbolFor(asset), endpoint = futures ? "https://fapi.binance.com/fapi/v1/klines" : "https://api.binance.com/api/v3/klines";
            ArrayNode rows = JSON.arrayNode(); long cursor = start;
            while (cursor < end) {
                String key = (futures ? "futures" : "spot") + '-' + symbol + "-4h-" + cursor + '-' + end;
                String query = query(Map.of("symbol", symbol, "interval", "4h", "startTime", Long.toString(cursor), "endTime", Long.toString(end), "limit", "1000"),
                        List.of("symbol", "interval", "startTime", "endTime", "limit"));
                JsonNode batch = cachedJson(endpoint + '?' + query, cache, key); if (!batch.isArray() || batch.isEmpty()) break;
                for (JsonNode value : batch) { long time = value.get(0).asLong(); if (time + BAR_MS > end) continue;
                    double quote = value.get(7).asDouble(), buy = value.get(10).asDouble(); ObjectNode row = JSON.objectNode().put("time", time)
                            .put("open", value.get(1).asDouble()).put("high", value.get(2).asDouble()).put("low", value.get(3).asDouble()).put("close", value.get(4).asDouble())
                            .put("volume", value.get(5).asDouble()).put("quote_volume", quote).put("taker_buy_quote", buy).put("taker_sell_quote", quote - buy)
                            .put("source", futures ? "Binance USD-M futures klines" : "Binance spot klines"); rows.add(row); }
                long last = batch.get(batch.size() - 1).get(0).asLong(), next = last + BAR_MS; if (next <= cursor) break; cursor = next;
                if (batch.size() >= 1000) Thread.sleep(80);
            }
            return dedupeSort(rows);
        }

        @Override public ArrayNode funding(String asset, long start, long end, Path cache) throws Exception {
            String symbol = symbolFor(asset); ArrayNode rows = JSON.arrayNode(); long cursor = start;
            while (cursor < end) {
                String key = "funding-" + symbol + '-' + cursor + '-' + end;
                String query = query(Map.of("symbol", symbol, "startTime", Long.toString(cursor), "endTime", Long.toString(end), "limit", "1000"),
                        List.of("symbol", "startTime", "endTime", "limit"));
                JsonNode batch = cachedJson("https://fapi.binance.com/fapi/v1/fundingRate?" + query, cache, key); if (!batch.isArray() || batch.isEmpty()) break;
                for (JsonNode value : batch) { long time = value.path("fundingTime").asLong(); double rate = value.path("fundingRate").asDouble(Double.NaN);
                    if (Double.isFinite(rate) && time >= start && time < end) rows.add(JSON.objectNode().put("time", time).put("rate", rate)); }
                long last = batch.get(batch.size() - 1).path("fundingTime").asLong(), next = last + 1; if (next <= cursor) break; cursor = next;
                if (batch.size() >= 1000) Thread.sleep(80);
            }
            return dedupeSort(rows);
        }

        @Override public ArrayNode metrics(String asset, long start, long end, Path cache) throws Exception {
            String symbol = symbolFor(asset); ArrayNode rows = JSON.arrayNode();
            for (String date : utcDates(start, end)) {
                String key = "metrics-" + symbol + '-' + date; Path zip = cacheFile(cache, "zip", key, "zip"); byte[] bytes;
                if (zip != null && Files.exists(zip)) bytes = Files.readAllBytes(zip); else {
                    HttpResponse<byte[]> response = fetchBytes(DATA_VISION_BASE + '/' + symbol + '/' + symbol + "-metrics-" + date + ".zip");
                    if (response.statusCode() < 200 || response.statusCode() >= 300) continue; bytes = response.body();
                    if (zip != null) Files.write(zip, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                try { rows.addAll(normalizeMetricCsv(unzip(bytes), date)); } catch (RuntimeException | IOException ignored) { }
            }
            return dedupeSort(rows);
        }

        @Override public ArrayNode macro(long start, long end, Path cache) throws Exception {
            String text = cachedText("https://fred.stlouisfed.org/graph/fredgraph.csv?id=DTWEXBGS,DFII10", cache, "fred-dxy-real-yield");
            ArrayNode output = JSON.arrayNode(); for (Map<String, String> row : parseCsv(text)) {
                String date = row.get("observation_date"); if (!DATE.matcher(String.valueOf(date)).matches()) continue;
                double dxy = ".".equals(row.get("DTWEXBGS")) ? Double.NaN : parse(row.get("DTWEXBGS"));
                double real = ".".equals(row.get("DFII10")) ? Double.NaN : parse(row.get("DFII10"));
                long time = parseInstant(date + "T16:00:00Z"); if (time >= start && time < end && Double.isFinite(dxy) && Double.isFinite(real))
                    output.add(JSON.objectNode().put("date", date).put("dxy", dxy).put("real_yield", real).put("available_at", time + DAY_MS));
            } return output;
        }

        @Override public ArrayNode sentiment(long start, long end, Path cache) throws Exception {
            JsonNode payload = cachedJson("https://api.alternative.me/fng/?limit=0", cache, "alternative-fng-all"); ArrayNode output = JSON.arrayNode();
            for (JsonNode row : array(payload.get("data"))) { long epoch = (long) (parse(row.path("timestamp").asText()) * 1000); String date = iso(epoch).substring(0, 10);
                double value = parse(row.path("value").asText()); long day = parseInstant(date + "T00:00:00Z"); if (day >= start && day < end && Double.isFinite(value)) {
                    ObjectNode item = JSON.objectNode().put("date", date).put("value", value); item.set("classification", truthy(row.get("value_classification")) ? row.get("value_classification").deepCopy() : NullNode.instance);
                    item.put("available_at", epoch + DAY_MS).put("source", "Alternative.me Fear & Greed"); output.add(item); } }
            sortArray(output, Comparator.comparing(node -> node.path("date").asText())); return output;
        }

        @Override public ArrayNode valuation(String asset, long start, long end, Path cache) throws Exception {
            if (!Set.of("btc", "eth").contains(asset)) return JSON.arrayNode(); String startDate = iso(start).substring(0, 10), endDate = iso(end).substring(0, 10);
            LinkedHashMap<String, String> params = new LinkedHashMap<>(); params.put("assets", asset); params.put("metrics", "CapMVRVCur"); params.put("start_time", startDate);
            params.put("end_time", endDate); params.put("frequency", "1d"); params.put("page_size", "5000");
            JsonNode payload = cachedJson("https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?" + query(params, new ArrayList<>(params.keySet())), cache,
                    "coinmetrics-" + asset + '-' + startDate + '-' + endDate + "-page5000"); ArrayNode output = JSON.arrayNode();
            for (JsonNode row : array(payload.get("data"))) { long available = parseInstant(row.path("time").asText()) + DAY_MS; double mvrv = parse(row.path("CapMVRVCur").asText());
                if (Double.isFinite(mvrv) && available >= start && available < end) output.add(JSON.objectNode().put("date", row.path("time").asText().substring(0, 10))
                        .put("available_at", available).put("mvrv", mvrv).put("source", "Coin Metrics Community CapMVRVCur")); }
            return output;
        }

        private JsonNode cachedJson(String url, Path cache, String key) throws Exception { Path path = cacheFile(cache, "json", key, "json");
            if (path != null && Files.exists(path)) return MAPPER.readTree(Files.readString(path)); JsonNode value = MAPPER.readTree(fetchText(url));
            if (path != null) Files.writeString(path, MAPPER.writeValueAsString(value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); return value; }
        private String cachedText(String url, Path cache, String key) throws Exception { Path path = cacheFile(cache, "text", key, "csv");
            if (path != null && Files.exists(path)) return Files.readString(path); String value = fetchText(url);
            if (path != null) Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); return value; }
        private String fetchText(String url) throws Exception { HttpResponse<String> response = client.send(request(url), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException(response.statusCode() + " " + url); return response.body(); }
        private HttpResponse<byte[]> fetchBytes(String url) throws Exception { return client.send(request(url), HttpResponse.BodyHandlers.ofByteArray()); }
        private HttpRequest request(String url) { return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "trading-codex-swing-score/1").GET().build(); }
    }

    private record Spec(String framework, String channel, int direction) {}

    private static ArrayNode setupRows(String asset, ArrayNode spot, ArrayNode futures, ArrayNode oi, ArrayNode funding,
            ArrayNode macro, ArrayNode sentiment, ArrayNode valuation, ArrayNode benchmarkSpot, int direction,
            String framework, String channel, ArrayNode labels) {
        Map<Long, ArrayNode> oiBuckets = bucketSamples(oi, BAR_MS);
        LinkedHashMap<Long, ObjectNode> benchmarkByTime = firstByTime(benchmarkSpot), futuresByTime = firstByTime(futures);
        LinkedHashMap<Long, ObjectNode> byTime = new LinkedHashMap<>();
        for (int index = 0; index < spot.size(); index++) {
            ObjectNode current = ((ObjectNode) spot.get(index)).deepCopy(); long time = current.path("time").asLong();
            JsonNode prior = index > 0 ? spot.get(index - 1) : null; ObjectNode benchmark = benchmarkByTime.get(time), benchmarkPrior = benchmarkByTime.get(time - BAR_MS);
            double ownReturn = prior != null && prior.path("time").asLong() == time - BAR_MS && number(prior.get("close")) > 0 && number(current.get("close")) > 0
                    ? Math.log(number(current.get("close")) / number(prior.get("close"))) : Double.NaN;
            double benchmarkReturn = benchmark != null && benchmarkPrior != null && number(benchmark.get("close")) > 0 && number(benchmarkPrior.get("close")) > 0
                    ? Math.log(number(benchmark.get("close")) / number(benchmarkPrior.get("close"))) : Double.NaN;
            current.set("futures", futuresByTime.containsKey(time) ? futuresByTime.get(time).deepCopy() : NullNode.instance);
            putFiniteOrNull(current, "benchmark_return_4h", benchmarkReturn);
            putFiniteOrNull(current, "relative_return_4h_vs_btc", finiteAll(ownReturn, benchmarkReturn) ? ownReturn - benchmarkReturn : Double.NaN);
            byTime.put(time, current);
        }
        ArrayNode rows = JSON.arrayNode();
        for (Map.Entry<Long, ObjectNode> entry : byTime.entrySet()) {
            long time = entry.getKey(); ObjectNode base = entry.getValue(); if (!base.path("futures").isObject()) continue;
            ArrayNode samples = oiBuckets.getOrDefault(time, JSON.arrayNode()); if (samples.size() < 4) continue;
            double oiClose = number(samples.get(samples.size() - 1).get("value")), oiOpen = number(samples.get(0).get("value"));
            JsonNode fundingEvent = null; for (JsonNode event : funding) if (number(event.get("time")) <= time + BAR_MS) fundingEvent = event; else break;
            double fundingRate = fundingEvent == null ? Double.NaN : finiteNumber(fundingEvent.get("rate"));
            if (!finiteAll(oiClose, oiOpen, fundingRate)) continue;
            ObjectNode merged = base.deepCopy(); JsonNode future = base.get("futures");
            merged.put("spot_taker_delta", number(base.get("taker_buy_quote")) - number(base.get("taker_sell_quote")))
                    .put("futures_taker_delta", number(future.get("taker_buy_quote")) - number(future.get("taker_sell_quote")))
                    .put("oi_open", oiOpen).put("oi_close", oiClose).put("oi_sample_count", samples.size());
            putFiniteOrNull(merged, "top_trader_account_ratio", sampleMean(samples, "top_trader_account_ratio"));
            putFiniteOrNull(merged, "top_trader_position_ratio", sampleMean(samples, "top_trader_position_ratio"));
            putFiniteOrNull(merged, "global_account_ratio", sampleMean(samples, "global_account_ratio"));
            putFiniteOrNull(merged, "taker_long_short_ratio", sampleMean(samples, "taker_long_short_ratio"));
            merged.put("funding_rate", fundingRate).put("funding_event_time", (long) number(fundingEvent.get("time"))); rows.add(merged);
        }
        sortArray(rows, Comparator.comparingDouble(node -> number(node.get("time")))); Precomputed precomputed = precompute(rows);
        ArrayNode output = JSON.arrayNode();
        for (int index = 252; index < rows.size(); index++) {
            ObjectNode panel = flowAt(rows, index, direction); if (panel == null) continue;
            ComponentResult component = makeComponents(rows, index, direction, framework, channel, macro, sentiment, valuation, precomputed);
            if (component == null) continue;
            ObjectNode components = component.components(); ObjectNode legs = JSON.objectNode().put("flow", 0)
                    .put("technical", componentTotal(components, "technical")).put("macro", componentTotal(components, "macro"))
                    .put("sentiment", componentTotal(components, "sentiment")).put("valuation", componentTotal(components, "valuation"))
                    .put("structure", componentTotal(components, "structure"));
            SwingScore.FlowAssessment assessment = SwingScore.assessFlowPanel(panel, new SwingScore.FlowOptions((double) direction, "COMPLETE"));
            legs.put("flow", assessment.score()); double funding3d = finiteNumber(panel.path("oi_weighted_funding").get("mean_3d"));
            double annualized = Double.isFinite(funding3d) ? funding3d * 3 * 365 * 100 : Double.NaN;
            boolean fundingVeto = direction == -1 && Double.isFinite(annualized) && annualized < -5, carryVeto = fundingVeto;
            ObjectNode factors = component.factors().deepCopy(), derivatives = factors.path("derivatives").isObject()
                    ? ((ObjectNode) factors.path("derivatives")).deepCopy() : JSON.objectNode();
            putFiniteOrNull(derivatives, "spot_cvd_24h_usd", finiteNumber(panel.path("spot_cvd").get("delta_24h_usd")));
            putFiniteOrNull(derivatives, "spot_cvd_3d_usd", finiteNumber(panel.path("spot_cvd").get("delta_3d_usd")));
            putFiniteOrNull(derivatives, "futures_cvd_24h_usd", finiteNumber(panel.path("futures_bid_ask_delta").get("delta_24h_usd")));
            putFiniteOrNull(derivatives, "futures_cvd_3d_usd", finiteNumber(panel.path("futures_bid_ask_delta").get("delta_3d_usd")));
            putFiniteOrNull(derivatives, "oi_change_24h_pct", finiteNumber(panel.path("open_interest").get("change_24h_pct")));
            putFiniteOrNull(derivatives, "oi_change_3d_pct", finiteNumber(panel.path("open_interest").get("change_3d_pct")));
            putFiniteOrNull(derivatives, "funding_mean_24h", finiteNumber(panel.path("oi_weighted_funding").get("mean_24h")));
            putFiniteOrNull(derivatives, "funding_mean_3d", funding3d); putFiniteOrNull(derivatives, "funding_annualized_pct", annualized);
            for (String field : List.of("top_trader_account_ratio", "top_trader_position_ratio", "global_account_ratio", "taker_long_short_ratio"))
                putFiniteOrNull(derivatives, field, finiteNumber(rows.get(index).get(field)));
            factors.set("derivatives", derivatives); ObjectNode setup = setupFamiliesAt(rows, index, framework, channel, component.trigger(), JSON.objectNode().set("factors", factors));
            JsonNode row = rows.get(index); ObjectNode feature = JSON.objectNode().put("time", (long) number(row.get("time")))
                    .put("timestamp", iso((long) number(row.get("time")) + BAR_MS)).put("open", number(row.get("open"))).put("high", number(row.get("high")))
                    .put("low", number(row.get("low"))).put("close", number(row.get("close"))).put("volume", number(row.get("volume")))
                    .put("quote_volume", number(row.get("quote_volume"))).put("atr_20d", component.atr())
                    .put("funding_rate", number(row.get("funding_rate"))).put("funding_event_time", (long) number(row.get("funding_event_time")));
            feature.set("factors", factors); feature.set("setup_family", setup.get("primary")); feature.set("setup_families", setup.get("families"));
            feature.set("setup_flags", setup.get("flags")); feature.set("patterns", setup.get("flags").deepCopy()); feature.set("legs", legs);
            feature.set("leg_components", components); feature.set("flow_panel", panel);
            ObjectNode panels = JSON.objectNode(); panels.set(direction == 1 ? "long" : "short", panel.deepCopy()); feature.set("flow_panels", panels);
            feature.put("flow_coverage", "COMPLETE").set("trigger", component.trigger()); feature.put("equity_usd", 1_000_000);
            feature.put("stop_distance_pct", Math.max(1, Math.min(15, 100 * component.atr() / number(row.get("close")))));
            feature.set("protective_controls", JSON.objectNode().put("stop_valid", true).put("time_stop_valid", true).put("ratchet_valid", true).put("carry_veto", carryVeto));
            feature.put("book_pct", 0).set("veto_flags", JSON.objectNode().put("funding", fundingVeto).put("carry", carryVeto));
            ObjectNode controls = JSON.objectNode(); putFiniteOrNull(controls, "funding_annualized_pct", annualized);
            controls.put("funding_veto_derived", true).put("carry_veto_derived", true).put("normalized_equity_usd", true).put("normalized_book_pct", true)
                    .put("synthetic_control_assumption", "equity/book/stop controls are normalized calibration inputs, not realized account evidence"); feature.set("historical_controls", controls);
            feature.put("regime", component.trigger().path("regime").asText());
            feature.set("source_coverage", JSON.objectNode().put("spot", true).put("futures", true).put("open_interest", true).put("funding", true)
                    .put("macro", true).put("sentiment", true).put("valuation", true).put("funding_availability", "latest_settled_event_state_carry")
                    .put("macro_availability", "prior_completed_observation").put("no_forward_fill", false).put("point_in_time_safe", false).put("revision_vintage_risk", true));
            feature.set("_flow_snapshot", MAPPER.valueToTree(assessment)); output.add(feature);
        }
        double[] included = new double[output.size()];
        for (int index = 0; index < output.size(); index++) { JsonNode legs = output.get(index).path("legs"); double sum = 0;
            for (String name : List.of("flow", "technical", "sentiment", "structure")) sum += number(legs.get(name)); included[index] = sum * 20 / 14; }
        for (int index = 0; index < output.size(); index++) { int start = Math.max(0, index - 540); double[] prior = java.util.Arrays.copyOfRange(included, start, index);
            ObjectNode strategy = JSON.objectNode(); putFiniteOrNull(strategy, "included_score_no_macro_valuation", included[index]);
            putFiniteOrNull(strategy, "included_score_prior_percentile_540", prior.length >= 180 ? priorPercentile(included[index], prior) : Double.NaN);
            strategy.put("prior_observations", prior.length).put("current_excluded_from_percentile", true); ((ObjectNode) output.get(index).path("factors")).set("strategy", strategy); }
        return output;
    }

    private static ComponentResult makeComponents(ArrayNode rows, int index, int direction, String framework, String channel,
            ArrayNode macroRows, ArrayNode sentimentRows, ArrayNode valuationRows, Precomputed p) {
        JsonNode row = rows.get(index); double e20 = p.ema20[index], e50 = p.ema50[index], e50d = p.ema50d[index], e200d = p.ema200d[index];
        double rsi = p.rsi14[index], unit = p.atr120[index]; JsonNode previous = rows.get(Math.max(0, index - 6));
        double priorHigh = max(slice(rows, Math.max(0, index - 30), index), "high");
        if (!finiteAll(e20, e50, rsi, unit)) return null; boolean isLong = direction == 1;
        ObjectNode technicalState = pointChecks(List.of(
                check(isLong ? "price_below_ema20" : "price_above_ema20", isLong ? number(row.get("close")) < e20 : number(row.get("close")) > e20),
                check(isLong ? "ema20_below_ema50_bear_regime" : "ema20_above_ema50_bull_regime", isLong ? e20 < e50 : e20 > e50),
                check("rsi_regime", isLong ? rsi < 45 : rsi > 55),
                check("range_position", isLong ? number(row.get("close")) <= min(slice(rows, Math.max(0, index - 30), index + 1), "close") * 1.03
                        : number(row.get("close")) >= max(slice(rows, Math.max(0, index - 30), index + 1), "close") * .97)), 2);
        JsonNode priorBar = rows.get(Math.max(0, index - 1)); double ret4h = pct(number(priorBar.get("close")), number(row.get("close")));
        double ret = pct(number(previous.get("close")), number(row.get("close"))), ret3d = index >= 18 ? pct(number(rows.get(index - 18).get("close")), number(row.get("close"))) : Double.NaN;
        double atrPct = unit / number(row.get("close")), rsiDelta = rsi - (Double.isFinite(p.rsi14[index - 6]) ? p.rsi14[index - 6] : rsi), avgVol = p.averageVolume20[index];
        double priorLow = min(slice(rows, Math.max(0, index - 30), index), "low");
        ObjectNode technicalImpulse = pointChecks(List.of(check("six_bar_return", isLong ? ret > 0 : ret < 0), check("rsi_impulse", isLong ? rsiDelta > 0 : rsiDelta < 0),
                check("volume_confirmation", Double.isFinite(avgVol) && number(row.get("volume")) > avgVol && (isLong ? ret > 0 : ret < 0)),
                check(isLong ? "failed_break_retest_prior_support" : "failed_break_retest_prior_resistance", isLong
                        ? Double.isFinite(priorLow) && number(row.get("low")) < priorLow && number(row.get("close")) > number(previous.get("close"))
                        : Double.isFinite(priorHigh) && number(row.get("high")) > priorHigh && number(row.get("close")) < number(previous.get("close")))), 2);

        ObjectNode macro = latestPrior(macroRows, (long) number(row.get("time"))); if (macro == null) return null;
        ArrayNode macroEligible = eligiblePrior(macroRows, (long) number(row.get("time")), 21); ObjectNode macroPrior = macroEligible.size() >= 4
                ? (ObjectNode) macroEligible.get(macroEligible.size() - 4) : macro;
        double dxySlope = pct(number(macroPrior.get("dxy")), number(macro.get("dxy"))), realSlope = number(macro.get("real_yield")) - number(macroPrior.get("real_yield"));
        double meanDxy = mean(macroEligible, "dxy"), meanReal = mean(macroEligible, "real_yield");
        ObjectNode macroState = pointChecks(List.of(check("dxy_regime", isLong ? number(macro.get("dxy")) <= meanDxy : number(macro.get("dxy")) >= meanDxy),
                check("real_yield_regime", isLong ? number(macro.get("real_yield")) <= meanReal : number(macro.get("real_yield")) >= meanReal), check("macro_breadth", false)), 1.5);
        ObjectNode macroImpulse = pointChecks(List.of(check("dxy_three_day_impulse", isLong ? dxySlope < 0 : dxySlope > 0),
                check("real_yield_three_day_impulse", isLong ? realSlope < 0 : realSlope > 0),
                check("joint_macro_impulse", isLong ? dxySlope < 0 && realSlope < 0 : dxySlope > 0 && realSlope > 0)), 1.5);

        ArrayNode sentimentEligible = eligiblePrior(sentimentRows, (long) number(row.get("time")), Integer.MAX_VALUE); if (sentimentEligible.isEmpty()) return null;
        JsonNode sentiment = sentimentEligible.get(sentimentEligible.size() - 1); ArrayNode sentimentHistory = tail(sentimentEligible, 30);
        double sentimentPrior3d = sentimentHistory.size() >= 4 ? number(sentimentHistory.get(sentimentHistory.size() - 4).get("value")) : number(sentiment.get("value"));
        ArrayNode sentimentPrior = slice(sentimentEligible, 0, Math.max(0, sentimentEligible.size() - 1)); sentimentPrior = tail(sentimentPrior, 90);
        double sentimentOneDay = sentimentEligible.size() >= 2 ? number(sentimentEligible.get(sentimentEligible.size() - 2).get("value")) : number(sentiment.get("value"));
        ObjectNode sentimentState = pointChecks(List.of(check("fear_or_greed_level", isLong ? number(sentiment.get("value")) <= 35 : number(sentiment.get("value")) >= 65),
                check("extreme_band", isLong ? number(sentiment.get("value")) <= 20 : number(sentiment.get("value")) >= 80),
                check("thirty_day_extreme", isLong ? number(sentiment.get("value")) <= min(sentimentHistory, "value") + 5 : number(sentiment.get("value")) >= max(sentimentHistory, "value") - 5)), 1.5);
        ObjectNode sentimentImpulse = pointChecks(List.of(check("three_day_sentiment_impulse", isLong ? number(sentiment.get("value")) < sentimentPrior3d : number(sentiment.get("value")) > sentimentPrior3d),
                check("sentiment_price_divergence", isLong ? number(sentiment.get("value")) < sentimentPrior3d && ret > 0 : number(sentiment.get("value")) > sentimentPrior3d && ret < 0),
                check("funding_crowding_proxy", isLong ? number(row.get("funding_rate")) < 0 : number(row.get("funding_rate")) > 0)), 1.5);

        ObjectNode valuationProxy = valuationAt(rows, index); if (valuationProxy == null) return null;
        ArrayNode valuationEligible = eligiblePrior(valuationRows, (long) number(row.get("time")), Integer.MAX_VALUE);
        JsonNode mvrv = valuationEligible.isEmpty() ? null : valuationEligible.get(valuationEligible.size() - 1);
        double mvrvPrior = valuationEligible.size() >= 4 ? finiteNumber(valuationEligible.get(valuationEligible.size() - 4).get("mvrv")) : Double.NaN;
        double mvrvValue = mvrv == null ? Double.NaN : finiteNumber(mvrv.get("mvrv"));
        ObjectNode valuationState = pointChecks(List.of(check("mvrv_extreme", Double.isFinite(mvrvValue) && (isLong ? mvrvValue <= .5 : mvrvValue >= 5)),
                check("one_year_high_distance", isLong ? number(valuationProxy.get("distance_from_1y_high")) <= -.30 : number(valuationProxy.get("distance_from_1y_high")) >= -.10),
                check("two_hundred_week_multiple", finite(valuationProxy.get("price_to_200w")) && (isLong ? number(valuationProxy.get("price_to_200w")) <= 0 : number(valuationProxy.get("price_to_200w")) >= 1))), 2);
        ObjectNode valuationImpulse = pointChecks(List.of(check("mvrv_three_day_impulse", finiteAll(mvrvValue, mvrvPrior) && (isLong ? mvrvValue < mvrvPrior : mvrvValue > mvrvPrior)),
                check("distance_impulse", isLong ? number(valuationProxy.get("distance_from_1y_high")) < -.30 : number(valuationProxy.get("distance_from_1y_high")) > -.10)), 1);

        ArrayNode lookback = slice(rows, Math.max(0, index - 180), index + 1); double return30d = index >= 180 && contiguous(rows, index - 180, index)
                ? pct(number(rows.get(index - 180).get("close")), number(row.get("close"))) : Double.NaN;
        double return30n = Double.isFinite(return30d) && atrPct > 0 ? return30d / (atrPct * Math.sqrt(180)) : Double.NaN;
        double low30 = min(lookback, "low"), high30 = max(lookback, "high");
        ObjectNode structureState = pointChecks(List.of(check("thirty_day_structure", isLong ? number(row.get("close")) <= low30 * 1.05 : number(row.get("close")) >= high30 * .95),
                check("flow_price_divergence", isLong ? number(row.get("futures_taker_delta")) < 0 && ret > 0 : number(row.get("futures_taker_delta")) > 0 && ret < 0)), 1);
        ObjectNode structureImpulse = pointChecks(List.of(check("three_day_break_or_reclaim", isLong ? number(row.get("close")) > number(lookback.get(0).get("close")) : number(row.get("close")) < number(lookback.get(0).get("close"))),
                check("range_expansion", number(row.get("high")) - number(row.get("low")) > unit)), 1);
        ObjectNode trigger = mechanicalTrigger(rows, index, framework, channel, e20, rsi);

        ObjectNode components = JSON.objectNode(); components.set("technical", component(technicalState, technicalImpulse, "Binance spot 4h OHLCV"));
        components.set("macro", component(macroState, macroImpulse, "FRED DTWEXBGS + DFII10 latest-revised history; prior completed observation with next-UTC-day availability lag"));
        components.set("sentiment", component(sentimentState, sentimentImpulse, "Alternative.me daily Fear & Greed"));
        components.set("valuation", component(valuationState, valuationImpulse, mvrv != null ? "Coin Metrics Community CapMVRVCur + price-derived 1y/200w proxies" : "price-derived 1y high distance + 200-week multiple proxies"));
        components.set("structure", component(structureState, structureImpulse, "Binance price/flow structural proxies"));
        ObjectNode factors = JSON.objectNode(); ObjectNode technical = JSON.objectNode();
        putFiniteOrNull(technical, "close_vs_ema20_pct", pct(e20, number(row.get("close")))); putFiniteOrNull(technical, "ema20_vs_ema50_pct", pct(e50, e20));
        putFiniteOrNull(technical, "rsi14", rsi); putFiniteOrNull(technical, "ema20", e20); putFiniteOrNull(technical, "ema50", e50); putFiniteOrNull(technical, "prior_30_bar_high", priorHigh);
        putFiniteOrNull(technical, "return_4h", ret4h); putFiniteOrNull(technical, "return_24h", ret); putFiniteOrNull(technical, "return_3d", ret3d);
        putFiniteOrNull(technical, "return_24h_normalized", atrPct > 0 ? ret / (atrPct * Math.sqrt(6)) : Double.NaN);
        putFiniteOrNull(technical, "return_3d_normalized", atrPct > 0 ? ret3d / (atrPct * Math.sqrt(18)) : Double.NaN);
        putFiniteOrNull(technical, "return_3d_prior_percentile", p.return3dPriorPercentile[index]); putFiniteOrNull(technical, "rsi_24h_change", rsiDelta);
        putFiniteOrNull(technical, "volume_ratio_20", Double.isFinite(avgVol) && avgVol > 0 ? number(row.get("volume")) / avgVol : Double.NaN);
        putFiniteOrNull(technical, "volume_z_90d", p.volume90dZ[index]); putFiniteOrNull(technical, "atr_pct", atrPct);
        putFiniteOrNull(technical, "close_location", number(row.get("high")) > number(row.get("low"))
                ? (number(row.get("close")) - number(row.get("low"))) / (number(row.get("high")) - number(row.get("low"))) : Double.NaN); factors.set("technical", technical);
        ObjectNode macroFactor = JSON.objectNode(); for (String field : List.of("dxy", "real_yield")) putFiniteOrNull(macroFactor, field, finiteNumber(macro.get(field)));
        putFiniteOrNull(macroFactor, "dxy_3d_change_pct", dxySlope); putFiniteOrNull(macroFactor, "real_yield_3d_change", realSlope); putFiniteOrNull(macroFactor, "available_at", finiteNumber(macro.get("available_at"))); factors.set("macro", macroFactor);
        ObjectNode sentimentFactor = JSON.objectNode(); putFiniteOrNull(sentimentFactor, "fear_greed", finiteNumber(sentiment.get("value")));
        putFiniteOrNull(sentimentFactor, "fear_greed_1d_change", number(sentiment.get("value")) - sentimentOneDay); putFiniteOrNull(sentimentFactor, "fear_greed_3d_change", number(sentiment.get("value")) - sentimentPrior3d);
        putFiniteOrNull(sentimentFactor, "fear_greed_90d_percentile", priorPercentile(number(sentiment.get("value")), fieldValues(sentimentPrior, "value")));
        putFiniteOrNull(sentimentFactor, "available_at", finiteNumber(sentiment.get("available_at"))); sentimentFactor.put("price_divergence", isLong ? number(sentiment.get("value")) < sentimentPrior3d && ret > 0 : number(sentiment.get("value")) > sentimentPrior3d && ret < 0); factors.set("sentiment", sentimentFactor);
        ObjectNode valuationFactor = JSON.objectNode(); putFiniteOrNull(valuationFactor, "mvrv", mvrvValue); putFiniteOrNull(valuationFactor, "mvrv_3d_change", finiteAll(mvrvValue, mvrvPrior) ? mvrvValue - mvrvPrior : Double.NaN);
        ArrayNode valuationPriorRows = tail(slice(valuationEligible, 0, Math.max(0, valuationEligible.size() - 1)), 365);
        putFiniteOrNull(valuationFactor, "mvrv_365d_percentile", priorPercentile(mvrvValue, fieldValues(valuationPriorRows, "mvrv")));
        putFiniteOrNull(valuationFactor, "available_at", mvrv == null ? Double.NaN : finiteNumber(mvrv.get("available_at")));
        putFiniteOrNull(valuationFactor, "distance_from_1y_high", finiteNumber(valuationProxy.get("distance_from_1y_high")));
        putFiniteOrNull(valuationFactor, "price_to_200w", finiteNumber(valuationProxy.get("price_to_200w"))); factors.set("valuation", valuationFactor);
        ObjectNode structure = JSON.objectNode(); putFiniteOrNull(structure, "range_low", low30); putFiniteOrNull(structure, "range_high", high30);
        putFiniteOrNull(structure, "range_position", high30 > low30 ? (number(row.get("close")) - low30) / (high30 - low30) : Double.NaN);
        putFiniteOrNull(structure, "return_30d", return30d); putFiniteOrNull(structure, "return_30d_normalized", return30n); putFiniteOrNull(structure, "ema50d", e50d); putFiniteOrNull(structure, "ema200d", e200d);
        putFiniteOrNull(structure, "close_vs_ema200d_pct", Double.isFinite(e200d) && e200d > 0 ? number(row.get("close")) / e200d - 1 : Double.NaN);
        putFiniteOrNull(structure, "ema50d_vs_ema200d_pct", finiteAll(e50d, e200d) && e200d > 0 ? e50d / e200d - 1 : Double.NaN); factors.set("structure", structure);
        ObjectNode relative = JSON.objectNode().put("benchmark", "BTCUSDT"); putFiniteOrNull(relative, "return_4h_vs_btc", finiteNumber(row.get("relative_return_4h_vs_btc")));
        relative.put("benchmark_completed_bar", finite(row.get("benchmark_return_4h"))); factors.set("relative", relative);
        ObjectNode derivatives = JSON.objectNode();
        putFiniteOrNull(derivatives, "spot_cvd_24h_z", p.spotCvd24Z[index]); putFiniteOrNull(derivatives, "futures_cvd_24h_z", p.futuresCvd24Z[index]);
        putFiniteOrNull(derivatives, "spot_futures_divergence_z", finiteAll(p.spotCvd24Z[index], p.futuresCvd24Z[index]) ? p.spotCvd24Z[index] - p.futuresCvd24Z[index] : Double.NaN);
        putFiniteOrNull(derivatives, "oi_change_24h_z", p.oiChange24Z[index]); putFiniteOrNull(derivatives, "funding_mean_24h_z", p.funding24Z[index]);
        putFiniteOrNull(derivatives, "top_trader_account_z", p.topTraderAccountZ[index]); putFiniteOrNull(derivatives, "top_trader_position_z", p.topTraderPositionZ[index]);
        putFiniteOrNull(derivatives, "global_account_z", p.globalAccountZ[index]); putFiniteOrNull(derivatives, "taker_long_short_z", p.takerLongShortZ[index]);
        putFiniteOrNull(derivatives, "top_vs_global_positioning_z", p.positioningDivergenceZ[index]); factors.set("derivatives", derivatives);
        ObjectNode evidence = JSON.objectNode().put("macro_date", text(macro.get("date"))).put("sentiment_date", text(sentiment.get("date")));
        evidence.set("valuation_date", mvrv == null ? NullNode.instance : copyOrNull(mvrv.get("date")));
        evidence.put("valuation_metric", mvrv != null ? "Coin Metrics CapMVRVCur" : "price-derived proxy").put("valuation_proxy", mvrv == null)
                .put("valuation_200w_available", valuationProxy.path("200w_available").asBoolean(false)).put("no_lookahead", false).put("no_future_timestamp_read", true);
        evidence.set("availability", JSON.objectNode().put("macro", "prior_completed_observation").put("sentiment", "prior_completed_observation")
                .put("valuation", mvrv != null ? "prior_completed_observation" : "unavailable").put("funding", "latest_settled_event_state_carry"));
        evidence.set("revision_vintage_risk", JSON.objectNode().put("fred", true).put("coinmetrics", mvrv != null).put("alternative_me", false));
        return new ComponentResult(unit, components, factors, evidence, trigger);
    }

    private record ComponentResult(double atr, ObjectNode components, ObjectNode factors, ObjectNode evidence, ObjectNode trigger) {}

    private static ObjectNode flowAt(ArrayNode rows, int index, int direction) {
        JsonNode row = rows.get(index); LinkedHashMap<String, Horizon> horizons = new LinkedHashMap<>();
        for (HorizonSpec spec : List.of(new HorizonSpec("24h", 6), new HorizonSpec("3d", 18))) {
            int start = index - spec.length() + 1; if (!contiguous(rows, start, index)) return null;
            double spot = 0, futures = 0, funding = 0;
            for (int i = start; i <= index; i++) { spot += truthy(rows.get(i).get("spot_taker_delta")) ? number(rows.get(i).get("spot_taker_delta")) : 0;
                futures += truthy(rows.get(i).get("futures_taker_delta")) ? number(rows.get(i).get("futures_taker_delta")) : 0;
                if (!finite(rows.get(i).get("funding_rate"))) return null; funding += number(rows.get(i).get("funding_rate")); }
            double price = pct(number(rows.get(start).get("close")), number(row.get("close")));
            double oi = pct(number(rows.get(start).get("oi_close")), number(row.get("oi_close"))); if (!finiteAll(price, oi)) return null;
            double meanFunding = funding / spec.length(); int oiSign = sign(oi), priceSign = sign(price), futureSign = sign(futures);
            String signal = oiSign != 0 && priceSign != 0 && oiSign == priceSign && (futureSign == 0 || futureSign == priceSign) ? "aligned"
                    : oiSign != 0 && priceSign != 0 && oiSign != priceSign ? "opposing" : "neutral";
            String interpretation = "aligned".equals(signal) ? (direction == 1 ? (priceSign < 0 ? "long_deleveraging" : "fresh_long_build")
                    : (priceSign > 0 ? "leveraged_long_rally" : "long_deleveraging")) : "opposing".equals(signal) ? "price_OI_divergence" : "mixed_or_flat";
            horizons.put(spec.name(), new Horizon(spot, futures, directionRow(spot), directionRow(futures), price, oi, meanFunding, interpretation));
        }
        Horizon h24 = horizons.get("24h"), h3d = horizons.get("3d"); ObjectNode panel = JSON.objectNode().put("schema", "market-flow/1")
                .put("interval_hours", 4).put("coverage", "COMPLETE").put("completed_through", iso((long) number(row.get("time")) + BAR_MS))
                .put("scope", "Binance single-venue historical aggregate");
        panel.set("spot_cvd", JSON.objectNode().put("available", true).put("direction_24h", h24.spotDirection()).put("direction_3d", h3d.spotDirection())
                .put("delta_24h_usd", h24.spot()).put("delta_3d_usd", h3d.spot()));
        panel.set("futures_bid_ask_delta", JSON.objectNode().put("available", true).put("direction_24h", h24.futuresDirection()).put("direction_3d", h3d.futuresDirection())
                .put("delta_24h_usd", h24.futures()).put("delta_3d_usd", h3d.futures()));
        panel.set("futures_cvd", JSON.objectNode().put("available", true).put("direction_24h", h24.futuresDirection()).put("direction_3d", h3d.futuresDirection()));
        ObjectNode oi = JSON.objectNode().put("available", true).put("setup_signal_24h", setupSignal(h24.interpretation())).put("setup_signal_3d", setupSignal(h3d.interpretation()))
                .put("change_24h_pct", h24.oi()).put("change_3d_pct", h3d.oi()).put("interpretation_24h", h24.interpretation()).put("interpretation_3d", h3d.interpretation()); panel.set("open_interest", oi);
        ObjectNode funding = JSON.objectNode().put("available", true).put("setup_signal_24h", alignment(direction * h24.funding()))
                .put("setup_signal_3d", alignment(direction * h3d.funding())).put("mean_24h", h24.funding()).put("mean_3d", h3d.funding())
                .put("sign_convention", "positive funding = longs pay shorts; setup-relative sign is inverted for FK/FR"); panel.set("oi_weighted_funding", funding);
        panel.set("provenance", JSON.objectNode().put("spot_cvd", "Binance spot 4h klines: quote volume - taker-buy quote volume")
                .put("futures_cvd", "Binance USD-M futures 4h klines: taker-buy quote volume - taker-sell quote volume")
                .put("open_interest", "Binance Data Vision daily metrics samples aggregated inside each completed 4h bar")
                .put("funding", "Binance USD-M fundingRate events, latest settled event at or before bar close")); return panel;
    }

    private static Precomputed precompute(ArrayNode rows) {
        int size = rows.size(); double[] closes = doubles(rows, row -> number(row.get("close"))), volumes = doubles(rows, row -> number(row.get("volume")));
        double[] e20 = ema(closes, 20), e50 = ema(closes, 50), e50d = ema(closes, 300), e200d = ema(closes, 1200), rsi = rsi(closes, 14), atr = atr(rows, 120);
        double[] spot24 = map(rolling(rows, "spot_taker_delta", 6, false), SwingBackfill::signedLog);
        double[] futures24 = map(rolling(rows, "futures_taker_delta", 6, false), SwingBackfill::signedLog);
        double[] funding24 = rolling(rows, "funding_rate", 6, true), oiChange = fill(size), volumeLog = fill(size), positioning = fill(size), return3d = fill(size);
        for (int i = 0; i < size; i++) {
            if (i >= 5 && contiguous(rows, i - 5, i)) oiChange[i] = pct(number(rows.get(i - 5).get("oi_close")), number(rows.get(i).get("oi_close")));
            double quote = finiteNumber(rows.get(i).get("quote_volume")); if (quote > 0) volumeLog[i] = Math.log(quote);
            double top = finiteNumber(rows.get(i).get("top_trader_position_ratio")), global = finiteNumber(rows.get(i).get("global_account_ratio"));
            if (top > 0 && global > 0) positioning[i] = Math.log(top) - Math.log(global);
            if (i >= 18 && contiguous(rows, i - 18, i)) return3d[i] = pct(number(rows.get(i - 18).get("close")), number(rows.get(i).get("close")));
        }
        double[] percentile = fill(size); for (int i = 0; i < size; i++) { double[] prior = finiteSlice(return3d, Math.max(0, i - 540), i);
            if (Double.isFinite(return3d[i]) && prior.length >= 180) percentile[i] = priorPercentile(return3d[i], prior); }
        double[] avgVolume = fill(size); for (int i = 0; i < size; i++) { double[] prior = finiteSlice(volumes, Math.max(0, i - 20), i); if (prior.length > 0) avgVolume[i] = average(prior); }
        return new Precomputed(closes, volumes, e20, e50, e50d, e200d, rsi, atr, avgVolume, zSeries(spot24), zSeries(futures24),
                zSeries(oiChange), zSeries(funding24), zSeries(volumeLog), zSeries(doubles(rows, row -> finiteNumber(row.get("top_trader_account_ratio")))),
                zSeries(doubles(rows, row -> finiteNumber(row.get("top_trader_position_ratio")))), zSeries(doubles(rows, row -> finiteNumber(row.get("global_account_ratio")))),
                zSeries(doubles(rows, row -> finiteNumber(row.get("taker_long_short_ratio")))), zSeries(positioning), percentile);
    }

    private static ObjectNode valuationAt(ArrayNode rows, int index) {
        long end = (long) number(rows.get(index).get("time")), beginning = end - 365L * DAY_MS; ArrayNode oneYear = JSON.arrayNode();
        for (JsonNode row : rows) if (number(row.get("time")) >= beginning && number(row.get("time")) <= end) oneYear.add(row);
        if (oneYear.isEmpty() || end - (long) number(oneYear.get(0).get("time")) < 365L * DAY_MS) return null;
        double high = max(oneYear, "close"), distance = number(rows.get(index).get("close")) / high - 1; ObjectNode output = JSON.objectNode().put("distance_from_1y_high", distance);
        output.set("price_to_200w", NullNode.instance); output.put("200w_available", false); return output;
    }

    private static ObjectNode coverageMeta(long start, long end, long warmupStart, ArrayNode spot, ArrayNode benchmark, ArrayNode futures,
            ArrayNode funding, ArrayNode oi, ArrayNode macro, ArrayNode sentiment, ArrayNode valuation, ArrayNode requested,
            ArrayNode labels, ArrayNode features, int alignedLabels) {
        ObjectNode coverage = JSON.objectNode().put("requested_from", iso(start)).put("requested_to", iso(end)).put("warmup_from", iso(warmupStart))
                .put("warmup_days", 365).put("bars", requested.size()).put("fetched_bars_with_warmup", spot.size())
                .put("aligned_price_bars", Math.min(requested.size(), futures.size())).put("label_bars", labels.size())
                .put("eligible_feature_bars", features.size()).put("aligned_label_feature_bars", alignedLabels).put("excluded_label_bars", labels.size() - alignedLabels)
                .put("feature_bar_coverage_ratio", requested.isEmpty() ? 0 : (double) features.size() / requested.size())
                .put("price_bar_coverage_ratio", labels.isEmpty() ? 0 : (double) labels.size() / Math.max(1, requested.size()));
        coverage.set("source_rows", JSON.objectNode().put("spot", spot.size()).put("benchmark_spot_btc", benchmark.size()).put("futures", futures.size())
                .put("funding", funding.size()).put("open_interest", oi.size()).put("macro", macro.size()).put("sentiment", sentiment.size()).put("valuation", valuation.size()));
        List<String> dates = utcDates(start, end); ObjectNode missing = JSON.objectNode().put("data_vision_metric_days", missingDates(dates, oi))
                .put("fred_macro_days", missingDates(dates, macro)).put("sentiment_days", missingDates(dates, sentiment)).put("valuation_days", missingDates(dates, valuation))
                .put("excluded_label_bars", labels.size() - alignedLabels)
                .put("note", "Missing periods remain in the full OHLC label denominator; no value is fabricated for an excluded feature bar."); coverage.set("missing_periods", missing);
        coverage.put("source_scope", "Binance asset + synchronized BTC spot, Binance USD-M futures (single venue), Binance Data Vision OI, FRED, Coin Metrics, Alternative.me")
                .put("no_forward_fill", false).put("availability_model", "funding latest-settled event-state carry; macro/sentiment/valuation prior-completed observation")
                .put("excluded_periods_are_not_labels", false).put("denominator_contract", "full eligible OHLC label universe; feature coverage is measured against all labels")
                .put("point_in_time_safe", false);
        coverage.set("revision_vintage_risk", JSON.objectNode().put("fred", true).put("coinmetrics", true).put("alternative_me", false).put("binance", false)); return coverage;
    }

    private record HorizonSpec(String name, int length) {}
    private record Horizon(double spot, double futures, String spotDirection, String futuresDirection, double price, double oi, double funding, String interpretation) {}
    private record Check(String name, boolean pass) {}
    private record Precomputed(double[] closes, double[] volumes, double[] ema20, double[] ema50, double[] ema50d, double[] ema200d,
            double[] rsi14, double[] atr120, double[] averageVolume20, double[] spotCvd24Z, double[] futuresCvd24Z,
            double[] oiChange24Z, double[] funding24Z, double[] volume90dZ, double[] topTraderAccountZ, double[] topTraderPositionZ,
            double[] globalAccountZ, double[] takerLongShortZ, double[] positioningDivergenceZ, double[] return3dPriorPercentile) {}

    private static ObjectNode pointChecks(List<Check> checks, double max) { ArrayNode awarded = JSON.arrayNode(), raw = JSON.arrayNode();
        for (Check check : checks) { if (check.pass()) awarded.add(check.name()); raw.add(JSON.objectNode().put("name", check.name()).put("pass", check.pass())); }
        ObjectNode out = JSON.objectNode().put("points", Math.min(max, awarded.size() * .5)); out.set("awarded", awarded); out.set("checks", raw); return out; }
    private static Check check(String name, boolean pass) { return new Check(name, pass); }
    private static ObjectNode component(ObjectNode state, ObjectNode impulse, String source) { ObjectNode out = JSON.objectNode().put("state", number(state.get("points"))).put("impulse", number(impulse.get("points")));
        ObjectNode checks = JSON.objectNode(); checks.set("state", state); checks.set("impulse", impulse); out.set("checks", checks); out.put("source", source); return out; }
    private static double componentTotal(ObjectNode components, String name) { return number(components.path(name).get("state")) + number(components.path(name).get("impulse")); }

    private static ArrayNode eligiblePrior(ArrayNode rows, long time, int limit) { List<JsonNode> values = new ArrayList<>();
        for (JsonNode row : rows) if (finite(row.get("available_at")) && number(row.get("available_at")) < time) values.add(row);
        values.sort(Comparator.comparingDouble(row -> number(row.get("available_at")))); int start = Math.max(0, values.size() - limit); ArrayNode out = JSON.arrayNode();
        for (int i = start; i < values.size(); i++) out.add(values.get(i)); return out; }
    private static Map<Long, ArrayNode> bucketSamples(ArrayNode rows, long width) { LinkedHashMap<Long, ArrayNode> out = new LinkedHashMap<>();
        for (JsonNode row : rows) { long time = (long) Math.floor(number(row.get("time")) / width) * width; out.computeIfAbsent(time, ignored -> JSON.arrayNode()).add(row); } return out; }
    private static double[] rolling(ArrayNode rows, String field, int length, boolean mean) { double[] out = fill(rows.size());
        for (int i = 0; i < rows.size(); i++) { int start = i - length + 1; if (!contiguous(rows, start, i)) continue; double total = 0; boolean valid = true;
            for (int j = start; j <= i; j++) { double value = finiteNumber(rows.get(j).get(field)); if (!Double.isFinite(value)) { valid = false; break; } total += value; }
            if (valid) out[i] = mean ? total / length : total; } return out; }
    private static double[] zSeries(double[] values) { double[] out = fill(values.length); for (int i = 0; i < values.length; i++) out[i] = priorZ(values, i, 540, 180); return out; }
    private static double priorZ(double[] values, int index, int window, int min) { if (!Double.isFinite(values[index])) return Double.NaN;
        double[] prior = finiteSlice(values, Math.max(0, index - window), index); if (prior.length < min) return Double.NaN; double mean = average(prior), variance = 0;
        for (double value : prior) variance += (value - mean) * (value - mean); variance /= Math.max(1, prior.length - 1); double deviation = Math.sqrt(variance); return deviation > 0 ? (values[index] - mean) / deviation : 0; }
    private static double[] ema(double[] values, int length) { double[] out = fill(values.length); if (values.length < length) return out; double value = 0;
        for (int i = 0; i < length; i++) value += values[i]; value /= length; out[length - 1] = value; double k = 2d / (length + 1);
        for (int i = length; i < values.length; i++) { value = values[i] * k + value * (1 - k); out[i] = value; } return out; }
    private static double[] rsi(double[] values, int length) { double[] out = fill(values.length); if (values.length <= length) return out; double gain = 0, loss = 0;
        for (int i = 1; i <= length; i++) { double delta = values[i] - values[i - 1]; gain += Math.max(0, delta); loss += Math.max(0, -delta); }
        double ratio = loss == 0 ? Double.POSITIVE_INFINITY : gain / loss; out[length] = 100 - 100 / (1 + ratio);
        for (int i = length + 1; i < values.length; i++) { double delta = values[i] - values[i - 1]; gain = (gain * (length - 1) + Math.max(0, delta)) / length;
            loss = (loss * (length - 1) + Math.max(0, -delta)) / length; ratio = loss == 0 ? Double.POSITIVE_INFINITY : gain / loss; out[i] = 100 - 100 / (1 + ratio); } return out; }
    private static double[] atr(ArrayNode rows, int length) { double[] out = fill(rows.size()); for (int i = length; i < rows.size(); i++) { double total = 0;
        for (int j = i - length + 1; j <= i; j++) total += trueRange(rows.get(j), rows.get(j - 1)); out[i] = total / length; } return out; }
    private static boolean contiguous(ArrayNode rows, int start, int end) { if (start < 0 || end >= rows.size()) return false;
        for (int i = start + 1; i <= end; i++) if ((long) number(rows.get(i).get("time")) - (long) number(rows.get(i - 1).get("time")) != BAR_MS) return false; return true; }

    private static ArrayNode normalizeMetricCsv(String csv, String date) { ArrayNode out = JSON.arrayNode(); for (Map<String, String> row : parseCsv(csv)) {
        long time = parseInstant(row.getOrDefault("create_time", "").replace(' ', 'T') + "Z"); double value = parse(row.get("sum_open_interest_value")); if (time == Long.MIN_VALUE || !Double.isFinite(value)) continue;
        ObjectNode item = JSON.objectNode().put("time", time).put("value", value).put("oi", parse(row.get("sum_open_interest")));
        putFiniteOrNull(item, "top_trader_account_ratio", parse(row.get("count_toptrader_long_short_ratio"))); putFiniteOrNull(item, "top_trader_position_ratio", parse(row.get("sum_toptrader_long_short_ratio")));
        putFiniteOrNull(item, "global_account_ratio", parse(row.get("count_long_short_ratio"))); putFiniteOrNull(item, "taker_long_short_ratio", parse(row.get("sum_taker_long_short_vol_ratio")));
        item.put("source", "Binance Data Vision daily metrics").put("date", date); out.add(item); } return out; }
    private static List<Map<String, String>> parseCsv(String text) { String normalized = text == null ? "" : text.trim(); if (normalized.isEmpty()) return List.of(); String[] lines = normalized.split("\\r?\\n");
        if (lines.length < 2) return List.of(); String[] headers = lines[0].split(",", -1); List<Map<String, String>> out = new ArrayList<>();
        for (int line = 1; line < lines.length; line++) { String[] cells = lines[line].split(",", -1); LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++) row.put(headers[i].trim(), i < cells.length ? cells[i].trim() : ""); out.add(row); } return out; }
    private static String unzip(byte[] bytes) throws IOException { try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        ZipEntry entry = zip.getNextEntry(); if (entry == null) return ""; zip.transferTo(out); return out.toString(StandardCharsets.UTF_8); } }
    private static Path cacheFile(Path cache, String prefix, String key, String extension) throws IOException { if (cache == null) return null; Files.createDirectories(cache);
        String safe = key.replaceAll("[^A-Za-z0-9_.-]", "_"); return cache.resolve(prefix + '-' + safe + '.' + extension); }
    private static String query(Map<String, String> params, List<String> order) { List<String> values = new ArrayList<>(); for (String key : order)
        values.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + '=' + URLEncoder.encode(params.get(key), StandardCharsets.UTF_8)); return String.join("&", values); }

    private static ObjectNode dedupeRow(JsonNode row) { return row == null || !row.isObject() ? null : (ObjectNode) row; }
    private static ArrayNode dedupeSort(ArrayNode rows) { LinkedHashMap<Long, ObjectNode> map = new LinkedHashMap<>(); for (JsonNode node : rows) { ObjectNode row = dedupeRow(node);
        if (row != null && finite(row.get("time"))) map.put((long) number(row.get("time")), row); } List<ObjectNode> values = new ArrayList<>(map.values());
        values.sort(Comparator.comparingDouble(row -> number(row.get("time")))); ArrayNode out = JSON.arrayNode(); values.forEach(out::add); return out; }
    private static <T extends JsonNode> void sortArray(ArrayNode array, Comparator<JsonNode> comparator) { List<JsonNode> values = new ArrayList<>(); array.forEach(values::add); values.sort(comparator); array.removeAll(); values.forEach(array::add); }
    private static ArrayNode slice(ArrayNode rows, int start, int end) { ArrayNode out = JSON.arrayNode(); int from = Math.max(0, start), to = Math.min(rows.size(), Math.max(from, end)); for (int i = from; i < to; i++) out.add(rows.get(i)); return out; }
    private static ArrayNode tail(ArrayNode rows, int count) { return slice(rows, Math.max(0, rows.size() - count), rows.size()); }
    private static ArrayNode array(JsonNode node) { return node != null && node.isArray() ? (ArrayNode) node : JSON.arrayNode(); }
    private static ArrayNode dedupeStrings(ArrayNode values) { LinkedHashSet<String> set = new LinkedHashSet<>(); values.forEach(node -> set.add(node.asText())); ArrayNode out = JSON.arrayNode(); set.forEach(out::add); return out; }
    private static boolean contains(ArrayNode values, String value) { for (JsonNode node : values) if (value.equals(node.asText())) return true; return false; }
    private static double[] doubles(ArrayNode rows, ToDoubleFunction<JsonNode> mapper) { double[] out = new double[rows.size()]; for (int i = 0; i < rows.size(); i++) out[i] = mapper.applyAsDouble(rows.get(i)); return out; }
    private static double[] map(double[] values, java.util.function.DoubleUnaryOperator mapper) { double[] out = new double[values.length]; for (int i = 0; i < values.length; i++) out[i] = mapper.applyAsDouble(values[i]); return out; }
    private static double[] fill(int length) { double[] out = new double[length]; java.util.Arrays.fill(out, Double.NaN); return out; }
    private static double[] finiteSlice(double[] values, int start, int end) { double[] temp = new double[Math.max(0, end - start)]; int count = 0;
        for (int i = start; i < end; i++) if (Double.isFinite(values[i])) temp[count++] = values[i]; return java.util.Arrays.copyOf(temp, count); }
    private static double[] fieldValues(ArrayNode rows, String field) { double[] temp = new double[rows.size()]; int count = 0; for (JsonNode row : rows) { double value = finiteNumber(row.get(field)); if (Double.isFinite(value)) temp[count++] = value; } return java.util.Arrays.copyOf(temp, count); }
    private static double average(double[] values) { double sum = 0; for (double value : values) sum += value; return sum / values.length; }
    private static double mean(ArrayNode rows, String field) { double total = 0; for (JsonNode row : rows) total += number(row.get(field)); return total / rows.size(); }
    private static double sampleMean(ArrayNode rows, String field) { double total = 0; int count = 0; for (JsonNode row : rows) { double value = finiteNumber(row.get(field)); if (Double.isFinite(value)) { total += value; count++; } } return count == 0 ? Double.NaN : total / count; }
    private static double min(ArrayNode rows, String field) { double out = Double.POSITIVE_INFINITY; for (JsonNode row : rows) out = Math.min(out, number(row.get(field))); return out; }
    private static double max(ArrayNode rows, String field) { double out = Double.NEGATIVE_INFINITY; for (JsonNode row : rows) out = Math.max(out, number(row.get(field))); return out; }
    private static double trueRange(JsonNode row, JsonNode prior) { return Math.max(number(row.get("high")) - number(row.get("low")), Math.max(Math.abs(number(row.get("high")) - number(prior.get("close"))), Math.abs(number(row.get("low")) - number(prior.get("close"))))); }
    private static double pct(double from, double to) { return Double.isFinite(from) && Double.isFinite(to) && from != 0 ? to / from - 1 : Double.NaN; }
    private static double signedLog(double value) { return Double.isFinite(value) ? Math.signum(value) * Math.log1p(Math.abs(value)) : Double.NaN; }
    private static double priorPercentile(double value, double[] prior) { if (!Double.isFinite(value) || prior.length == 0) return Double.NaN; int below = 0, equal = 0;
        for (double item : prior) { if (item < value) below++; else if (item == value) equal++; } return (below + .5 * equal) / prior.length; }
    private static int sign(double value) { return !Double.isFinite(value) || value == 0 ? 0 : value > 0 ? 1 : -1; }
    private static int minOr(Integer a, Integer b, int fallback) { if (a == null && b == null) return fallback; if (a == null) return b; if (b == null) return a; return Math.min(a, b); }
    private static String regimeAt(ArrayNode rows, int index) { JsonNode lookback = rows.get(Math.max(0, index - 42)); double move = pct(number(lookback.get("close")), number(rows.get(index).get("close")));
        return move > .10 ? "TREND_UP" : move < -.10 ? "TREND_DOWN" : "RANGE"; }
    private static String directionRow(double value) { return value > 0 ? "positive" : value < 0 ? "negative" : "flat"; }
    private static String setupSignal(String interpretation) { return "mixed_or_flat".equals(interpretation) ? "neutral" : "price_OI_divergence".equals(interpretation) ? "opposing" : "aligned"; }
    private static String alignment(double value) { return value < 0 ? "aligned" : value > 0 ? "opposing" : "neutral"; }
    private static boolean finite(JsonNode node) { if (node == null || node.isNull() || node.isMissingNode() || node.isBoolean()) return false;
        if (node.isTextual() && node.asText().isEmpty()) return false; return Double.isFinite(parse(node.asText())); }
    private static double finiteNumber(JsonNode node) { return finite(node) ? parse(node.asText()) : Double.NaN; }
    private static boolean finiteAll(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
    private static boolean truthy(JsonNode node) { return SwingCrossValidator.truthy(node); }
    private static double number(JsonNode node) { if (node == null || node.isNull() || node.isMissingNode()) return 0; return node.asDouble(); }
    private static double parse(String value) { try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return Double.NaN; } }
    private static long parseInstant(String value) { try { return Instant.parse(value).toEpochMilli(); } catch (RuntimeException ignored) { return Long.MIN_VALUE; } }
    private static String text(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(); }
    private static String iso(long time) { return JS_ISO.format(Instant.ofEpochMilli(time)); }
    private static String symbolFor(String asset) { return switch (asset) { case "btc" -> "BTCUSDT"; case "eth" -> "ETHUSDT"; default -> asset.toUpperCase(Locale.ROOT) + "USDT"; }; }
    private static List<String> utcDates(long start, long end) { List<String> dates = new ArrayList<>(); long first = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (long time = first; time < end; time += DAY_MS) dates.add(iso(time).substring(0, 10)); return dates; }
    private static int missingDates(List<String> dates, ArrayNode rows) { Set<String> observed = new LinkedHashSet<>(); rows.forEach(row -> { if (row.has("date")) observed.add(row.get("date").asText()); }); int missing = 0; for (String date : dates) if (!observed.contains(date)) missing++; return missing; }
    private static JsonNode copyOrNull(JsonNode node) { return node == null || node.isMissingNode() ? NullNode.instance : node.deepCopy(); }
    private static void putFiniteOrNull(ObjectNode out, String field, double value) { if (Double.isFinite(value)) {
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) out.put(field, (long) value); else out.put(field, value); } else out.set(field, NullNode.instance); }
}
