package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

/** Outcome-independent dependence, price-response, and low-count diagnostics for v003. */
public final class LiquidationHourlyDiagnosticsV1 {
    public static final String SCHEMA = "liquidation-hourly-diagnostics/1";
    private static final List<String> VARIANTS = List.of("CORE_ONE_ENTRY", "STAGED_NO_MACRO", "STAGED_MACRO");
    private static final List<Integer> HORIZONS_DAYS = List.of(1, 3, 7);
    private static final int BOOTSTRAP_DRAWS = 10_000;
    private static final long BOOTSTRAP_SEED = 20_260_921L;
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration CLUSTER_EXTENSION = Duration.ofHours(72);
    private static final int REFERENCE_GROUP_COUNT = 30;
    private static final int CALENDAR_BLOCK_DAYS = 67;

    private LiquidationHourlyDiagnosticsV1() {}

    public record Event(String id, String asset, Instant geometryStart, Instant geometryEnd,
            Instant availableAt, LiquidationStructureRouterV1.Direction shockDirection) {
        public Event {
            id = required(id, "event id");
            asset = canonicalAsset(asset);
            Objects.requireNonNull(availableAt, "availableAt");
            Objects.requireNonNull(shockDirection, "shockDirection");
            if ((geometryStart == null) != (geometryEnd == null)
                    || (geometryStart != null && geometryEnd.isBefore(geometryStart))) {
                throw new IllegalArgumentException("event geometry must be both absent or an ordered interval");
            }
            if (geometryEnd != null && availableAt.isBefore(geometryEnd)) {
                throw new IllegalArgumentException("event availability must not precede the selected geometry end");
            }
        }
    }

    public record Intent(String variant, String id, String asset, String setupId, Instant decisionTime,
            LiquidationStructureRouterV1.Direction direction, LiquidationStructureRouterV1.Branch branch) {
        public Intent {
            variant = required(variant, "variant");
            id = required(id, "intent event id");
            asset = canonicalAsset(asset);
            setupId = required(setupId, "setup id");
            Objects.requireNonNull(decisionTime, "decisionTime");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(branch, "branch");
        }
    }

    public record Position(String variant, String asset, String setupId, Instant decisionTime,
            Instant firstFill, Instant exit, BigDecimal netPnl, boolean closed) {
        public Position {
            variant = required(variant, "variant");
            asset = canonicalAsset(asset);
            setupId = required(setupId, "setup id");
            Objects.requireNonNull(decisionTime, "decisionTime");
            if (firstFill != null && firstFill.isBefore(decisionTime)) {
                throw new IllegalArgumentException("position fill must not precede its decision");
            }
            if (closed && (firstFill == null || exit == null || exit.isBefore(firstFill))) {
                throw new IllegalArgumentException("closed position needs an ordered fill and exit");
            }
            if (exit != null && firstFill != null && exit.isBefore(firstFill)) {
                throw new IllegalArgumentException("position exit must not precede its fill");
            }
            if (!closed && exit != null) throw new IllegalArgumentException("open position cannot carry a completed exit");
        }
    }

    public record Price(String asset, Instant closeTime, double close) {
        public Price {
            asset = canonicalAsset(asset);
            Objects.requireNonNull(closeTime, "closeTime");
            if (!Double.isFinite(close) || close <= 0.0) throw new IllegalArgumentException("close must be finite and positive");
        }
    }

    /** Builds deterministic diagnostics from replay metadata and completed hourly closes. */
    public static ObjectNode build(ObjectNode policy,
            Map<String, NavigableMap<Instant, Double>> pricesByAssetCloseTime,
            List<Event> events, List<Intent> intents, List<Position> positions,
            Map<String, ObjectNode> accounts, Map<String, ObjectNode> funnelByVariant,
            ObjectNode coverage) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(pricesByAssetCloseTime, "pricesByAssetCloseTime");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(intents, "intents");
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(funnelByVariant, "funnelByVariant");
        Objects.requireNonNull(coverage, "coverage");
        validatePolicy(policy);

        Map<String, NavigableMap<Instant, Double>> prices = canonicalPrices(pricesByAssetCloseTime);
        ClusterResult clusterResult = clusterEvents(events);
        Map<String, Intent> firstIntents = firstIntents(intents, events);
        ArrayNode response = responseDiagnostics(policy, prices, events, firstIntents, clusterResult);
        OverlapResult overlaps = positionOverlap(positions, firstIntents, clusterResult,
                analysisEnd(policy, prices, coverage));
        ObjectNode completed = completedPositionMetrics(positions, firstIntents);
        DailySeries daily = dailyPortfolioReturns(policy, accounts, positions);
        ObjectNode blockSensitivity = calendarBlockSensitivity(policy, positions, daily);
        ObjectNode correlations = laggedDailyCorrelations(daily);

        ObjectNode output = JsonHashes.mapper().createObjectNode().put("schema", SCHEMA)
                .put("status", "DEVELOPMENT_ONLY")
                .put("authoritative", false)
                .put("promotion_allowed", false)
                .put("independent_sample_claim", false);
        output.set("market_wide_shock_clusters", clusterResult.rows.deepCopy());
        output.put("market_wide_shock_cluster_count", clusterResult.clusters.size());
        output.put("unclustered_geometry_event_count", clusterResult.unclusteredCount);
        output.put("cluster_count_status", countStatus(clusterResult.clusters.size()));
        output.put("cluster_count_warning_nonblocking", clusterResult.clusters.size() < REFERENCE_GROUP_COUNT);
        output.set("response_diagnostics", response);
        output.set("position_overlap_components", overlaps.toJson());
        output.set("calendar_block_sensitivity", blockSensitivity);
        output.set("per_completed_position_metrics", completed);
        output.set("lagged_daily_portfolio_return_correlations", correlations);
        ObjectNode funnelCopy = JsonHashes.mapper().createObjectNode();
        for (String variant : VARIANTS) {
            JsonNode row = funnelByVariant.get(variant);
            if (row != null) funnelCopy.set(variant, row.deepCopy());
        }
        output.set("funnel_counts_by_variant", funnelCopy);
        output.set("coverage", coverage.deepCopy());
        return output;
    }

    private static void validatePolicy(ObjectNode policy) {
        if (!"liquidation-exploratory-policy/1".equals(policy.path("schema").asText())
                || !"liquidation-exploratory-v003".equals(policy.path("id").asText())
                || !"DEVELOPMENT".equals(policy.path("evidence_phase").asText())
                || policy.path("promotion_allowed").asBoolean(true)) {
            throw fail("diagnostics require the frozen v003 DEVELOPMENT policy");
        }
        ArrayNode variants = array(policy, "variants");
        if (variants.size() != VARIANTS.size()) throw fail("v003 must retain its three frozen variants");
        for (int i = 0; i < VARIANTS.size(); i++) {
            if (!VARIANTS.get(i).equals(variants.path(i).asText())) throw fail("v003 variant order differs from its frozen inventory");
        }
        JsonNode stats = policy.path("statistics");
        if (stats.path("bootstrap_draws").asInt(-1) != BOOTSTRAP_DRAWS
                || stats.path("seed").asLong(-1) != BOOTSTRAP_SEED
                || stats.path("purge_days").asInt(-1) != CALENDAR_BLOCK_DAYS
                || stats.path("minimum_groups_to_run").asInt(-1) != 0
                || stats.path("reference_minimum_groups").asInt(-1) != REFERENCE_GROUP_COUNT) {
            throw fail("v003 bootstrap, block, and low-count policy differs from the frozen values");
        }
        ArrayNode horizons = array(policy.path("response_diagnostics").path("horizons_days"), "response_diagnostics.horizons_days");
        if (horizons.size() != HORIZONS_DAYS.size()) throw fail("v003 must report every frozen response horizon");
        for (int i = 0; i < HORIZONS_DAYS.size(); i++) {
            if (horizons.path(i).asInt(-1) != HORIZONS_DAYS.get(i)) throw fail("v003 response horizon differs from the frozen value");
        }
    }

    private static Map<String, NavigableMap<Instant, Double>> canonicalPrices(
            Map<String, NavigableMap<Instant, Double>> input) {
        TreeMap<String, NavigableMap<Instant, Double>> result = new TreeMap<>();
        for (Map.Entry<String, NavigableMap<Instant, Double>> entry : input.entrySet()) {
            String asset = canonicalAsset(entry.getKey());
            TreeMap<Instant, Double> rows = new TreeMap<>();
            if (entry.getValue() != null) {
                for (Map.Entry<Instant, Double> price : entry.getValue().entrySet()) {
                    Instant closeTime = Objects.requireNonNull(price.getKey(), "price close time");
                    Double close = price.getValue();
                    if (close == null || !Double.isFinite(close) || close <= 0.0) continue;
                    if (!isExactUtcHour(closeTime)) continue;
                    rows.put(closeTime, close);
                }
            }
            result.put(asset, rows);
        }
        return result;
    }

    private static ClusterResult clusterEvents(List<Event> events) {
        HashSet<String> ids = new HashSet<>();
        ArrayList<ClusterEvent> sorted = new ArrayList<>();
        int unclustered = 0;
        for (Event event : events) {
            if (!ids.add(event.id())) throw fail("qualified event id is duplicated: " + event.id());
            if (event.geometryStart() == null) {
                unclustered++;
                continue;
            }
            sorted.add(new ClusterEvent(event, event.geometryStart(), event.geometryEnd().plus(CLUSTER_EXTENSION)));
        }
        sorted.sort(Comparator.comparing(ClusterEvent::start).thenComparing(row -> row.event.id())
                .thenComparing(row -> row.event.asset()));
        ArrayList<Cluster> clusters = new ArrayList<>();
        HashMap<String, Cluster> byEventId = new HashMap<>();
        Cluster current = null;
        for (ClusterEvent row : sorted) {
            if (current == null || row.start.isAfter(current.end)) {
                current = new Cluster(String.format(Locale.ROOT, "shock-%04d", clusters.size() + 1), row.start, row.end);
                clusters.add(current);
            } else if (row.end.isAfter(current.end)) {
                current.end = row.end;
            }
            current.events.add(row.event);
            byEventId.put(row.event.id(), current);
        }
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (Cluster cluster : clusters) {
            ObjectNode row = rows.addObject().put("cluster_id", cluster.id).put("start", cluster.start.toString())
                    .put("end", cluster.end.toString()).put("event_count", cluster.events.size())
                    .put("asset_count", cluster.events.stream().map(Event::asset).distinct().count())
                    .put("independence_claim", false);
            ArrayNode memberRows = row.putArray("events");
            cluster.events.stream().sorted(Comparator.comparing(Event::id)).forEach(event -> memberRows.addObject()
                    .put("event_id", event.id()).put("asset", event.asset())
                    .put("geometry_start", event.geometryStart().toString()).put("geometry_end", event.geometryEnd().toString())
                    .put("dependence_interval_end", event.geometryEnd().plus(CLUSTER_EXTENSION).toString()));
        }
        return new ClusterResult(List.copyOf(clusters), Map.copyOf(byEventId), rows, unclustered);
    }

    private static Map<String, Intent> firstIntents(List<Intent> intents, List<Event> events) {
        HashMap<String, Event> eventById = new HashMap<>();
        for (Event event : events) eventById.put(event.id(), event);
        LinkedHashMap<String, Intent> result = new LinkedHashMap<>();
        ArrayList<Intent> sorted = new ArrayList<>(intents);
        sorted.sort(Comparator.comparing(Intent::decisionTime).thenComparing(Intent::variant)
                .thenComparing(Intent::asset).thenComparing(Intent::setupId));
        for (Intent intent : sorted) {
            if (!VARIANTS.contains(intent.variant())) throw fail("intent has a variant outside the frozen v003 inventory");
            Event event = eventById.get(intent.id());
            if (event == null || !event.asset().equals(intent.asset())) continue;
            if (!intent.setupId().equals(intent.id())) throw fail("v003 intent setup id must equal its qualified event id");
            if (intent.decisionTime().isBefore(event.availableAt())) throw fail("routed decision precedes qualified event availability");
            result.putIfAbsent(intentKey(intent.variant(), intent.id()), intent);
        }
        return Map.copyOf(result);
    }

    private static ArrayNode responseDiagnostics(ObjectNode policy,
            Map<String, NavigableMap<Instant, Double>> prices, List<Event> events,
            Map<String, Intent> firstIntents, ClusterResult clusters) {
        TreeMap<ResponseKey, ArrayList<ResponseObservation>> grouped = new TreeMap<>();
        HashMap<PathKey, PathResult> pathCache = new HashMap<>();
        for (String variant : VARIANTS) {
            for (int horizon : HORIZONS_DAYS) {
                grouped.put(new ResponseKey("FIRST_OBSERVABLE_EVENT_AVAILABILITY", variant, "SIGNED_SHOCK", horizon), new ArrayList<>());
                grouped.put(new ResponseKey("FIRST_OBSERVABLE_EVENT_AVAILABILITY", variant, "OPPOSITE_SHOCK", horizon), new ArrayList<>());
                grouped.put(new ResponseKey("FIRST_ROUTED_ENTRY_DECISION", variant, "SIGNED_SHOCK", horizon), new ArrayList<>());
                grouped.put(new ResponseKey("FIRST_ROUTED_ENTRY_DECISION", variant, "OPPOSITE_SHOCK", horizon), new ArrayList<>());
                grouped.put(new ResponseKey("FIRST_ROUTED_ENTRY_DECISION", variant, "ROUTED_DIRECTION", horizon), new ArrayList<>());
            }
        }
        for (Event event : events.stream().sorted(Comparator.comparing(Event::availableAt).thenComparing(Event::asset).thenComparing(Event::id)).toList()) {
            Cluster cluster = clusters.byEventId.get(event.id());
            for (String variant : VARIANTS) {
                Intent intent = firstIntents.get(intentKey(variant, event.id()));
                addResponseRows(grouped, pathCache, prices, event, variant, "FIRST_OBSERVABLE_EVENT_AVAILABILITY",
                        event.availableAt(), "UNKNOWN_AT_EVENT_AVAILABILITY", cluster, null, false);
                if (intent != null) {
                    addResponseRows(grouped, pathCache, prices, event, variant, "FIRST_ROUTED_ENTRY_DECISION",
                            intent.decisionTime(), intent.branch().name(), cluster, intent, true);
                }
            }
        }
        int clusterCount = clusters.clusters.size();
        int draws = policy.path("statistics").path("bootstrap_draws").asInt(BOOTSTRAP_DRAWS);
        long seed = policy.path("statistics").path("seed").asLong(BOOTSTRAP_SEED);
        List<String> sharedClusterUniverse = clusters.clusters.stream().map(cluster -> cluster.id).toList();
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<ResponseKey, ArrayList<ResponseObservation>> entry : grouped.entrySet()) {
            ResponseSummary overall = summarizeResponses(entry.getValue(), sharedClusterUniverse, draws, seed);
            ObjectNode row = rows.addObject().put("anchor", entry.getKey().anchor)
                    .put("variant", entry.getKey().variant).put("direction", entry.getKey().direction)
                    .put("horizon_days", entry.getKey().horizonDays)
                    .put("observation_count", overall.observationCount)
                    .put("valid_response_count", overall.validCount)
                    .put("cluster_observation_count", overall.clusterCount)
                    .put("missing_anchor_count", overall.missingAnchor)
                    .put("missing_endpoint_count", overall.missingEndpoint)
                    .put("missing_intervening_hour_count", overall.missingIntervening)
                    .put("non_hourly_anchor_count", overall.nonHourlyAnchor)
                    .put("unclustered_geometry_count", overall.unclusteredGeometry);
            row.set("mean_directional_return_fraction", nullable(overall.mean));
            row.set("p20_directional_return_fraction", nullable(overall.p20));
            row.set("ci95_low_directional_return_fraction", nullable(overall.ciLow));
            row.set("ci95_high_directional_return_fraction", nullable(overall.ciHigh));
            row.put("bootstrap_valid_draw_count", overall.bootstrapValidDraws)
                    .put("bootstrap_draws", draws).put("bootstrap_seed", seed)
                    .put("bootstrap_resampling_unit", "SHARED_MARKET_WIDE_SHOCK_CLUSTER")
                    .put("independence_claim", false)
                    .put("intent_conditioned", "FIRST_ROUTED_ENTRY_DECISION".equals(entry.getKey().anchor))
                    .put("direction_was_known_at_anchor", true)
                    .put("event_anchor_metrics_shared_across_variants", "FIRST_OBSERVABLE_EVENT_AVAILABILITY".equals(entry.getKey().anchor))
                    .put("under_30_cluster_warning_nonblocking", overall.clusterCount < REFERENCE_GROUP_COUNT);
            row.set("asset_breakdown", breakdown(entry.getValue(), true));
            row.set("branch_breakdown", breakdown(entry.getValue(), false));
        }
        return rows;
    }

    private static void addResponseRows(Map<ResponseKey, ArrayList<ResponseObservation>> grouped,
            Map<PathKey, PathResult> cache, Map<String, NavigableMap<Instant, Double>> prices,
            Event event, String variant, String anchor, Instant anchorTime, String branch,
            Cluster cluster, Intent intent, boolean includeRoutedDirection) {
        String[] directionNames = includeRoutedDirection
                ? new String[] {"SIGNED_SHOCK", "OPPOSITE_SHOCK", "ROUTED_DIRECTION"}
                : new String[] {"SIGNED_SHOCK", "OPPOSITE_SHOCK"};
        LiquidationStructureRouterV1.Direction[] directions = includeRoutedDirection
                ? new LiquidationStructureRouterV1.Direction[] {event.shockDirection(), event.shockDirection().opposite(), intent.direction()}
                : new LiquidationStructureRouterV1.Direction[] {event.shockDirection(), event.shockDirection().opposite()};
        for (int horizon : HORIZONS_DAYS) {
            PathKey pathKey = new PathKey(event.asset(), anchorTime, horizon);
            PathResult path = cache.computeIfAbsent(pathKey, ignored -> measurePath(prices.get(event.asset()), anchorTime, horizon));
            for (int directionIndex = 0; directionIndex < directionNames.length; directionIndex++) {
                if (directions[directionIndex] == null) continue;
                double raw = path.rawReturn;
                Double aligned = path.reason == null && cluster != null ? raw * directions[directionIndex].sign() : null;
                String reason = cluster == null ? "NO_CLUSTER_GEOMETRY" : path.reason;
                ResponseKey key = new ResponseKey(anchor, variant, directionNames[directionIndex], horizon);
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new ResponseObservation(
                        event.id(), event.asset(), cluster == null ? null : cluster.id, branch,
                        aligned, reason, path.missingAnchor, path.missingEndpoint, path.missingIntervening,
                        "NON_UTC_HOURLY_ANCHOR".equals(path.reason), cluster == null));
            }
        }
    }

    private static PathResult measurePath(NavigableMap<Instant, Double> prices, Instant anchor, int days) {
        if (!isExactUtcHour(anchor)) return new PathResult(Double.NaN, "NON_UTC_HOURLY_ANCHOR", false, false, false);
        if (prices == null || !prices.containsKey(anchor)) return new PathResult(Double.NaN, "MISSING_ANCHOR_CLOSE", true, false, false);
        Double anchorClose = prices.get(anchor);
        if (anchorClose == null || !Double.isFinite(anchorClose) || anchorClose <= 0.0) {
            return new PathResult(Double.NaN, "INVALID_ANCHOR_CLOSE", true, false, false);
        }
        Instant end = anchor.plus(Duration.ofDays(days));
        boolean missingEndpoint = false, missingIntervening = false;
        for (Instant time = anchor.plus(HOUR); !time.isAfter(end); time = time.plus(HOUR)) {
            Double close = prices.get(time);
            if (close == null || !Double.isFinite(close) || close <= 0.0) {
                if (time.equals(end)) missingEndpoint = true;
                else missingIntervening = true;
            }
        }
        if (missingEndpoint || missingIntervening) {
            return new PathResult(Double.NaN, missingEndpoint ? "MISSING_ENDPOINT_CLOSE" : "MISSING_INTERVENING_HOURLY_CLOSE",
                    false, missingEndpoint, missingIntervening);
        }
        return new PathResult(prices.get(end) / anchorClose - 1.0, null, false, false, false);
    }

    private static ResponseSummary summarizeResponses(List<ResponseObservation> observations,
            List<String> sharedClusterUniverse, int draws, long seed) {
        LinkedHashMap<String, ArrayList<Double>> byCluster = new LinkedHashMap<>();
        int valid = 0, missingAnchor = 0, missingEndpoint = 0, missingIntervening = 0, nonHourly = 0, unclustered = 0;
        for (ResponseObservation observation : observations) {
            if (observation.directionalReturn != null) {
                valid++;
                byCluster.computeIfAbsent(observation.clusterId, ignored -> new ArrayList<>()).add(observation.directionalReturn);
            }
            if (observation.missingAnchor) missingAnchor++;
            if (observation.missingEndpoint) missingEndpoint++;
            if (observation.missingIntervening) missingIntervening++;
            if (observation.nonHourly) nonHourly++;
            if (observation.unclusteredGeometry) unclustered++;
        }
        TreeMap<String, Double> clusterMeans = new TreeMap<>();
        for (Map.Entry<String, ArrayList<Double>> entry : byCluster.entrySet()) {
            clusterMeans.put(entry.getKey(), mean(entry.getValue()));
        }
        Double mean = clusterMeans.isEmpty() ? null : mean(new ArrayList<>(clusterMeans.values()));
        BootstrapSummary bootstrap = bootstrapMeanByIds(sharedClusterUniverse, clusterMeans, draws, seed);
        return new ResponseSummary(observations.size(), valid, clusterMeans.size(), missingAnchor, missingEndpoint,
                missingIntervening, nonHourly, unclustered, mean, bootstrap.p20, bootstrap.low, bootstrap.high,
                bootstrap.validDrawCount);
    }

    private static ArrayNode breakdown(List<ResponseObservation> observations, boolean byAsset) {
        TreeMap<String, ArrayList<ResponseObservation>> groups = new TreeMap<>();
        for (ResponseObservation observation : observations) {
            String key = byAsset ? observation.asset : observation.branch;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(observation);
        }
        ArrayNode out = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<String, ArrayList<ResponseObservation>> entry : groups.entrySet()) {
            ResponseSummary summary = summarizeWithoutBootstrap(entry.getValue());
            ObjectNode row = out.addObject().put(byAsset ? "asset" : "branch", entry.getKey())
                    .put("observation_count", summary.observationCount).put("valid_response_count", summary.validCount)
                    .put("cluster_observation_count", summary.clusterCount);
            row.set("mean_directional_return_fraction", nullable(summary.mean));
            row.put("missing_anchor_count", summary.missingAnchor).put("missing_endpoint_count", summary.missingEndpoint)
                    .put("missing_intervening_hour_count", summary.missingIntervening)
                    .put("unclustered_geometry_count", summary.unclusteredGeometry);
        }
        return out;
    }

    private static ResponseSummary summarizeWithoutBootstrap(List<ResponseObservation> observations) {
        TreeMap<String, ArrayList<Double>> byCluster = new TreeMap<>();
        int valid = 0, missingAnchor = 0, missingEndpoint = 0, missingIntervening = 0, nonHourly = 0, unclustered = 0;
        for (ResponseObservation observation : observations) {
            if (observation.directionalReturn != null) {
                valid++;
                byCluster.computeIfAbsent(observation.clusterId, ignored -> new ArrayList<>()).add(observation.directionalReturn);
            }
            if (observation.missingAnchor) missingAnchor++;
            if (observation.missingEndpoint) missingEndpoint++;
            if (observation.missingIntervening) missingIntervening++;
            if (observation.nonHourly) nonHourly++;
            if (observation.unclusteredGeometry) unclustered++;
        }
        ArrayList<Double> clusterMeans = new ArrayList<>();
        byCluster.values().forEach(values -> clusterMeans.add(mean(values)));
        return new ResponseSummary(observations.size(), valid, byCluster.size(), missingAnchor, missingEndpoint,
                missingIntervening, nonHourly, unclustered, clusterMeans.isEmpty() ? null : mean(clusterMeans),
                null, null, null, 0);
    }

    private static OverlapResult positionOverlap(List<Position> positions, Map<String, Intent> firstIntents,
            ClusterResult clusters, Instant analysisEnd) {
        ArrayList<PositionNode> nodes = new ArrayList<>();
        int noFill = 0;
        HashSet<String> seen = new HashSet<>();
        for (Position position : positions) {
            if (!VARIANTS.contains(position.variant())) throw fail("position has a variant outside the frozen v003 inventory");
            String key = position.variant() + "\n" + position.asset() + "\n" + position.setupId();
            if (!seen.add(key)) throw fail("position setup is duplicated within a variant: " + position.setupId());
            if (position.firstFill() == null) { noFill++; continue; }
            Intent intent = firstIntents.get(intentKey(position.variant(), position.setupId()));
            Cluster cluster = clusters.byEventId.get(position.setupId());
            String clusterId = cluster == null ? null : cluster.id;
            Instant effectiveExit = position.exit() == null ? analysisEnd : position.exit();
            nodes.add(new PositionNode(position, intent == null ? "UNKNOWN" : intent.branch().name(),
                    clusterId, effectiveExit, position.exit() == null));
        }
        nodes.sort(Comparator.comparing((PositionNode row) -> row.position.firstFill())
                .thenComparing(row -> row.position.variant()).thenComparing(row -> row.position.asset())
                .thenComparing(row -> row.position.setupId()));
        int size = nodes.size();
        int[] parent = new int[size];
        for (int i = 0; i < size; i++) parent[i] = i;
        int actualOverlapEdges = 0, sameClusterEdges = 0;
        for (int i = 0; i < size; i++) {
            PositionNode left = nodes.get(i);
            for (int j = i + 1; j < size; j++) {
                PositionNode right = nodes.get(j);
                boolean sameCluster = left.clusterId != null && left.clusterId.equals(right.clusterId);
                boolean overlaps = intervalsOverlap(left.position.firstFill(), left.effectiveExit,
                        right.position.firstFill(), right.effectiveExit);
                if (sameCluster || overlaps) {
                    union(parent, i, j);
                    if (sameCluster) sameClusterEdges++;
                    if (overlaps) actualOverlapEdges++;
                }
            }
        }
        TreeMap<Integer, ArrayList<PositionNode>> byRoot = new TreeMap<>();
        for (int i = 0; i < size; i++) byRoot.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(nodes.get(i));
        ArrayNode components = JsonHashes.mapper().createArrayNode();
        int index = 0;
        for (ArrayList<PositionNode> component : byRoot.values()) {
            TreeSet<String> clustersInComponent = new TreeSet<>();
            boolean hasActualOverlap = false;
            ArrayNode members = JsonHashes.mapper().createArrayNode();
            for (PositionNode node : component) {
                if (node.clusterId != null) clustersInComponent.add(node.clusterId);
                for (PositionNode other : component) {
                    if (node != other && intervalsOverlap(node.position.firstFill(), node.effectiveExit,
                            other.position.firstFill(), other.effectiveExit)) hasActualOverlap = true;
                }
                ObjectNode member = members.addObject().put("variant", node.position.variant())
                        .put("asset", node.position.asset()).put("setup_id", node.position.setupId())
                        .put("branch", node.branch).put("closed", node.position.closed())
                        .put("holding_end_is_coverage_bound", node.openAtEnd);
                member.put("decision_time", node.position.decisionTime().toString())
                        .put("first_fill", node.position.firstFill().toString())
                        .put("overlap_end", node.effectiveExit.toString());
                if (node.position.exit() != null) member.put("exit", node.position.exit().toString()); else member.putNull("exit");
                if (node.clusterId != null) member.put("shock_cluster_id", node.clusterId); else member.putNull("shock_cluster_id");
            }
            ObjectNode row = components.addObject().put("component_id", String.format(Locale.ROOT, "holding-%04d", ++index))
                    .put("position_count", component.size()).put("shock_cluster_count", clustersInComponent.size())
                    .put("actual_holding_overlap_present", hasActualOverlap).put("independence_claim", false);
            ArrayNode clusterRows = row.putArray("shock_cluster_ids"); clustersInComponent.forEach(clusterRows::add);
            row.set("positions", members);
        }
        ObjectNode summary = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-position-overlap-components/1")
                .put("position_count", nodes.size()).put("positions_without_fill_excluded_count", noFill)
                .put("component_count", byRoot.size()).put("actual_holding_overlap_edge_count", actualOverlapEdges)
                .put("same_market_cluster_edge_count", sameClusterEdges)
                .put("analysis_end_exclusive_used_for_open_positions", analysisEnd.toString())
                .put("independence_claim", false)
                .put("under_30_warning_nonblocking", byRoot.size() < REFERENCE_GROUP_COUNT);
        summary.set("components", components);
        return new OverlapResult(summary);
    }

    private static ObjectNode completedPositionMetrics(List<Position> positions, Map<String, Intent> firstIntents) {
        TreeMap<String, ArrayList<Position>> variants = new TreeMap<>();
        for (String variant : VARIANTS) variants.put(variant, new ArrayList<>());
        for (Position position : positions) if (position.closed()) variants.get(position.variant()).add(position);
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-completed-position-metrics/1")
                .put("weighting", "EQUAL_COMPLETED_POSITION;SEPARATE_FROM_EQUAL_CLUSTER_RESPONSE_MEAN")
                .put("expectancy_claim", "MEAN_NET_PNL_PER_COMPLETED_POSITION")
                .put("independence_claim", false);
        ObjectNode byVariant = out.putObject("by_variant");
        for (String variant : VARIANTS) {
            List<Position> rows = variants.get(variant);
            TreeMap<String, ArrayList<Position>> byAsset = new TreeMap<>();
            TreeMap<String, ArrayList<Position>> byBranch = new TreeMap<>();
            for (Position position : rows) {
                byAsset.computeIfAbsent(position.asset(), ignored -> new ArrayList<>()).add(position);
                Intent intent = firstIntents.get(intentKey(position.variant(), position.setupId()));
                byBranch.computeIfAbsent(intent == null ? "UNKNOWN" : intent.branch().name(), ignored -> new ArrayList<>()).add(position);
            }
            ObjectNode variantRow = byVariant.putObject(variant);
            fillPositionSummary(variantRow, rows);
            ObjectNode assetRows = variantRow.putObject("by_asset");
            byAsset.forEach((asset, positionsForAsset) -> fillPositionSummary(assetRows.putObject(asset), positionsForAsset));
            ObjectNode branchRows = variantRow.putObject("by_branch");
            byBranch.forEach((branch, positionsForBranch) -> fillPositionSummary(branchRows.putObject(branch), positionsForBranch));
        }
        out.put("completed_position_count", positions.stream().filter(Position::closed).count());
        return out;
    }

    private static void fillPositionSummary(ObjectNode target, List<Position> positions) {
        ArrayList<Double> pnl = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        int missing = 0;
        for (Position position : positions) {
            if (position.netPnl() == null) { missing++; continue; }
            double value = position.netPnl().doubleValue();
            if (!Double.isFinite(value)) { missing++; continue; }
            pnl.add(value); sum = sum.add(position.netPnl());
        }
        target.put("completed_position_count", positions.size()).put("net_pnl_observation_count", pnl.size())
                .put("missing_net_pnl_count", missing).put("total_net_pnl_usdt", sum);
        target.set("mean_net_pnl_usdt", nullable(pnl.isEmpty() ? null : mean(pnl)));
        target.set("median_net_pnl_usdt", nullable(pnl.isEmpty() ? null
                : percentile(pnl.stream().mapToDouble(Double::doubleValue).sorted().toArray(), 0.5)));
    }

    private static DailySeries dailyPortfolioReturns(ObjectNode policy, Map<String, ObjectNode> accounts,
            List<Position> positions) {
        LocalDate start = LocalDate.parse(policy.path("source_start").asText().substring(0, 10));
        LocalDate endExclusive = LocalDate.parse(policy.path("source_end_exclusive").asText().substring(0, 10));
        TreeMap<String, TreeMap<LocalDate, Double>> returnsByVariant = new TreeMap<>();
        TreeMap<String, Set<LocalDate>> missingDatesByVariant = new TreeMap<>();
        TreeMap<String, Set<LocalDate>> nonpositiveDatesByVariant = new TreeMap<>();
        TreeMap<String, TreeMap<LocalDate, Double>> closeByVariant = new TreeMap<>();
        double initialEquity = policy.path("account").path("initial_equity").asDouble(20_000.0);
        for (String variant : VARIANTS) {
            ObjectNode account = accounts.get(variant);
            TreeMap<LocalDate, Double> observed = new TreeMap<>();
            if (account != null && account.path("marked_equity_curve").isArray()) {
                for (JsonNode row : account.path("marked_equity_curve")) {
                    if (!row.path("time").canConvertToLong()) continue;
                    Double equity = numberOrNull(row.path("equity_usdt"));
                    if (equity == null || !Double.isFinite(equity)) continue;
                    LocalDate date = Instant.ofEpochMilli(row.path("time").asLong()).atZone(ZoneOffset.UTC).toLocalDate();
                    observed.merge(date, equity, (a, b) -> b);
                }
            }
            TreeMap<LocalDate, Double> closes = new TreeMap<>(), variantReturns = new TreeMap<>();
            HashSet<LocalDate> missing = new HashSet<>();
            HashSet<LocalDate> nonpositive = new HashSet<>();
            Double lastEquity = initialEquity;
            LocalDate previousDate = start.minusDays(1);
            boolean ruined = false;
            for (LocalDate day = start; day.isBefore(endExclusive); day = day.plusDays(1)) {
                if (ruined) { nonpositive.add(day); continue; }
                Double actual = observed.get(day);
                boolean active = hasPositionDuring(positions, variant, day);
                if (lastEquity == null) {
                    if (actual == null) {
                        missing.add(day);
                        continue;
                    }
                    closes.put(day, actual);
                    if (actual <= 0.0) {
                        nonpositive.add(day);
                        ruined = true;
                    }
                    lastEquity = actual;
                    previousDate = day;
                    continue;
                }
                if (actual == null && active) {
                    missing.add(day);
                    lastEquity = null;
                    previousDate = null;
                    continue;
                }
                double close = actual == null ? (lastEquity == null ? initialEquity : lastEquity) : actual;
                closes.put(day, close);
                if (previousDate != null && previousDate.equals(day.minusDays(1)) && lastEquity != null) {
                    variantReturns.put(day, close / lastEquity - 1.0);
                } else if (previousDate == null && actual != null && day.equals(start)) {
                    variantReturns.put(day, close / initialEquity - 1.0);
                }
                if (close <= 0.0) {
                    nonpositive.add(day);
                    ruined = true;
                }
                lastEquity = close;
                previousDate = day;
            }
            returnsByVariant.put(variant, variantReturns);
            missingDatesByVariant.put(variant, Set.copyOf(missing));
            nonpositiveDatesByVariant.put(variant, Set.copyOf(nonpositive));
            closeByVariant.put(variant, closes);
        }
        return new DailySeries(start, endExclusive, returnsByVariant, missingDatesByVariant,
                nonpositiveDatesByVariant, closeByVariant, initialEquity);
    }

    private static boolean hasPositionDuring(List<Position> positions, String variant, LocalDate day) {
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = start.plus(Duration.ofDays(1));
        for (Position position : positions) {
            if (!position.variant().equals(variant) || position.firstFill() == null) continue;
            Instant positionEnd = position.exit() == null ? Instant.MAX : position.exit();
            if (!position.firstFill().isBefore(end)) continue;
            if (!positionEnd.isBefore(start)) return true;
        }
        return false;
    }

    private static ObjectNode calendarBlockSensitivity(ObjectNode policy, List<Position> positions, DailySeries daily) {
        ArrayList<CalendarBlock> blocks = new ArrayList<>();
        CalendarBlock partialTail = null;
        for (LocalDate start = daily.start; start.isBefore(daily.endExclusive); start = start.plusDays(CALENDAR_BLOCK_DAYS)) {
            LocalDate end = start.plusDays(CALENDAR_BLOCK_DAYS);
            if (end.isAfter(daily.endExclusive)) {
                partialTail = new CalendarBlock("partial-tail-excluded", start, daily.endExclusive);
                break;
            }
            blocks.add(new CalendarBlock(String.format(Locale.ROOT, "block-%04d", blocks.size() + 1), start, end));
        }
        TreeMap<String, TreeMap<String, Double>> variantReturns = new TreeMap<>();
        TreeMap<String, TreeMap<String, String>> variantStates = new TreeMap<>();
        TreeMap<String, Integer> internalZeroDays = new TreeMap<>();
        TreeMap<String, Integer> invalidBlocks = new TreeMap<>();
        for (String variant : VARIANTS) {
            TreeMap<String, Double> values = new TreeMap<>();
            TreeMap<String, String> states = new TreeMap<>();
            for (CalendarBlock block : blocks) {
                double compounded = 1.0;
                boolean complete = true;
                boolean hasNonpositive = false;
                int daysCounted = 0, zeroDays = 0;
                for (LocalDate day = block.start; day.isBefore(block.end); day = day.plusDays(1)) {
                    if (daily.nonpositiveDatesByVariant.get(variant).contains(day)) {
                        complete = false;
                        hasNonpositive = true;
                        continue;
                    }
                    Double dailyReturn = daily.returnsByVariant.get(variant).get(day);
                    if (dailyReturn == null) {
                        if (daily.missingDatesByVariant.get(variant).contains(day)) {
                            complete = false;
                            continue;
                        }
                        if (!hasPositionDuring(positions, variant, day)) {
                            dailyReturn = 0.0;
                            zeroDays++;
                        } else {
                            complete = false;
                            continue;
                        }
                    }
                    compounded *= 1.0 + dailyReturn;
                    daysCounted++;
                }
                if (complete && daysCounted > 0) {
                    values.put(block.id, compounded - 1.0);
                    states.put(block.id, "COMPLETE");
                } else if (complete && daysCounted == 0 && zeroDays == block.days()) {
                    values.put(block.id, 0.0);
                    states.put(block.id, "INTERNAL_ZERO_NO_POSITION_OR_ACTIVITY");
                } else {
                    states.put(block.id, hasNonpositive ? "EXCLUDED_NONPOSITIVE_EQUITY_AFTER_RUIN"
                            : "EXCLUDED_OPEN_EXPOSURE_MARK_MISSING");
                    invalidBlocks.merge(variant, 1, Integer::sum);
                }
                if (zeroDays > 0) internalZeroDays.merge(variant, zeroDays, Integer::sum);
            }
            variantReturns.put(variant, values);
            variantStates.put(variant, states);
        }
        ArrayList<String> commonBlockIds = new ArrayList<>();
        for (CalendarBlock block : blocks) {
            boolean common = VARIANTS.stream().allMatch(variant -> variantReturns.get(variant).containsKey(block.id));
            if (common) commonBlockIds.add(block.id);
        }
        ObjectNode byVariant = JsonHashes.mapper().createObjectNode();
        for (String variant : VARIANTS) {
            ArrayList<Double> blockValues = new ArrayList<>();
            for (String id : commonBlockIds) blockValues.add(variantReturns.get(variant).get(id));
            BootstrapSummary bootstrap = bootstrapMeanByIds(commonBlockIds, variantReturns.get(variant), BOOTSTRAP_DRAWS, BOOTSTRAP_SEED);
            ObjectNode row = byVariant.putObject(variant).put("complete_block_count", variantReturns.get(variant).size())
                    .put("excluded_open_exposure_mark_missing_block_count", invalidBlocks.getOrDefault(variant, 0))
                    .put("internal_zero_day_count", internalZeroDays.getOrDefault(variant, 0))
                    .put("common_block_count", commonBlockIds.size())
                    .put("bootstrap_valid_draw_count", bootstrap.validDrawCount)
                    .put("bootstrap_draws", BOOTSTRAP_DRAWS).put("bootstrap_seed", BOOTSTRAP_SEED)
                    .put("bootstrap_resampling_unit", "SHARED_67_DAY_CALENDAR_BLOCK")
                    .put("under_30_block_warning_nonblocking", commonBlockIds.size() < REFERENCE_GROUP_COUNT);
            row.set("common_block_mean_portfolio_return_fraction", nullable(blockValues.isEmpty() ? null : mean(blockValues)));
            row.set("p20_block_mean_portfolio_return_fraction", nullable(bootstrap.p20));
            row.set("ci95_low_block_mean_portfolio_return_fraction", nullable(bootstrap.low));
            row.set("ci95_high_block_mean_portfolio_return_fraction", nullable(bootstrap.high));
            ArrayNode blockRows = row.putArray("blocks");
            for (CalendarBlock block : blocks) {
                ObjectNode detail = blockRows.addObject().put("block_id", block.id).put("start_date", block.start.toString())
                        .put("end_date_exclusive", block.end.toString())
                        .put("state", variantStates.get(variant).get(block.id));
                Double value = variantReturns.get(variant).get(block.id);
                detail.set("portfolio_return_fraction", nullable(value));
                detail.put("in_common_universe", commonBlockIds.contains(block.id));
            }
        }
        ObjectNode assetVariant = assetVariantBlockCells(policy, positions, blocks);
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-67-day-calendar-block-sensitivity/1")
                .put("block_days", CALENDAR_BLOCK_DAYS).put("block_count", blocks.size())
                .put("analysis_calendar_day_count", (int) ChronoUnit.DAYS.between(daily.start, daily.endExclusive))
                .put("common_complete_block_count", commonBlockIds.size())
                .put("shared_bootstrap_draws", BOOTSTRAP_DRAWS).put("seed", BOOTSTRAP_SEED)
                .put("internal_absence_policy", "ZERO_ONLY_WHEN_FLAT_AND_NO_ACTIVITY;MISSING_HELD_MARKS_OR_POST_RUIN_DAYS_EXCLUDED")
                .put("includes_internal_zero_absences", true).put("independence_claim", false)
                .put("under_30_warning_nonblocking", commonBlockIds.size() < REFERENCE_GROUP_COUNT);
        out.set("by_variant", byVariant);
        out.set("asset_variant_position_cells", assetVariant);
        ObjectNode tail = out.putObject("partial_tail_excluded_from_67_day_bootstrap");
        if (partialTail == null) {
            tail.put("present", false).put("calendar_days", 0);
        } else {
            tail.put("present", true).put("start_date", partialTail.start.toString())
                    .put("end_date_exclusive", partialTail.end.toString()).put("calendar_days", partialTail.days())
                    .put("reason", "INCOMPLETE_67_DAY_CALENDAR_BLOCK;EXCLUDED_FROM_SHARED_BOOTSTRAP;RETAINED_IN_FULL_SAMPLE_RUN");
        }
        return out;
    }

    private static ObjectNode assetVariantBlockCells(ObjectNode policy, List<Position> positions, List<CalendarBlock> blocks) {
        TreeSet<String> assets = new TreeSet<>();
        for (JsonNode asset : policy.path("assets")) assets.add(canonicalAsset(asset.asText()));
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("weighting", "COMMON_ASSET_X_VARIANT_X_67_DAY_BLOCK_UNIVERSE")
                .put("missing_activity_absence", "ZERO_CLOSED_POSITION_PNL_WHEN_NO_POSITION_CLOSES_IN_CELL")
                .put("return_claim", false);
        ArrayNode cells = out.putArray("cells");
        for (String variant : VARIANTS) {
            for (String asset : assets) {
                for (CalendarBlock block : blocks) {
                    int closedCount = 0, activeCount = 0;
                    BigDecimal pnl = BigDecimal.ZERO;
                    int missingPnl = 0;
                    Instant blockStart = block.start.atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant blockEnd = block.end.atStartOfDay(ZoneOffset.UTC).toInstant();
                    for (Position position : positions) {
                        if (!position.variant().equals(variant) || !position.asset().equals(asset) || position.firstFill() == null) continue;
                        if (position.firstFill().isBefore(blockEnd) && (position.exit() == null || !position.exit().isBefore(blockStart))) activeCount++;
                        if (position.closed() && position.exit() != null && inBlock(position.exit(), block)) {
                            closedCount++;
                            if (position.netPnl() == null) missingPnl++; else pnl = pnl.add(position.netPnl());
                        }
                    }
                    cells.addObject().put("variant", variant).put("asset", asset).put("block_id", block.id)
                            .put("closed_position_count", closedCount).put("active_position_overlap_count", activeCount)
                            .put("closed_position_net_pnl_sum_usdt", pnl).put("missing_net_pnl_count", missingPnl)
                            .put("internal_zero_absence", closedCount == 0 && activeCount == 0);
                }
            }
        }
        out.put("cell_count", cells.size());
        return out;
    }

    private static ObjectNode laggedDailyCorrelations(DailySeries daily) {
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-daily-return-lag-correlations/1")
                .put("return_sampling", "LAST_OBSERVED_ACCOUNT_CURVE_POINT_PER_UTC_DATE;NOT_NECESSARILY_00:00_CLOSE;NO_INTERPOLATION_ACROSS_HELD_MARK_GAPS")
                .put("effective_sample_size_claim", false);
        ObjectNode byVariant = out.putObject("by_variant");
        for (String variant : VARIANTS) {
            ObjectNode variantRow = byVariant.putObject(variant);
            variantRow.put("daily_return_observation_count", daily.returnsByVariant.get(variant).size())
                    .put("held_mark_gap_day_count", daily.missingDatesByVariant.get(variant).size())
                    .put("nonpositive_equity_day_count", daily.nonpositiveDatesByVariant.get(variant).size());
            ArrayNode lags = variantRow.putArray("lags");
            for (int lag : List.of(1, 7, 30)) {
                ArrayList<Double> left = new ArrayList<>(), right = new ArrayList<>();
                TreeMap<LocalDate, Double> returns = daily.returnsByVariant.get(variant);
                for (Map.Entry<LocalDate, Double> row : returns.entrySet()) {
                    Double lagged = returns.get(row.getKey().minusDays(lag));
                    if (lagged != null) { left.add(row.getValue()); right.add(lagged); }
                }
                Double correlation = pearson(left, right);
                ObjectNode lagRow = lags.addObject().put("lag_days", lag).put("pair_count", left.size());
                lagRow.set("correlation", nullable(correlation));
                lagRow.put("defined", correlation != null);
            }
        }
        return out;
    }

    private static Instant analysisEnd(ObjectNode policy, Map<String, NavigableMap<Instant, Double>> prices,
            ObjectNode coverage) {
        String explicit = coverage.path("analysis_coverage_end_exclusive").asText("");
        if (!explicit.isBlank()) return Instant.parse(explicit);
        Instant max = null;
        for (NavigableMap<Instant, Double> rows : prices.values()) if (rows != null && !rows.isEmpty()) {
            Instant last = rows.lastKey();
            if (max == null || last.isAfter(max)) max = last;
        }
        if (max != null) return max;
        return Instant.parse(policy.path("source_end_exclusive").asText()).minus(HOUR);
    }

    private static BootstrapSummary bootstrapMeanByIds(List<String> universe, Map<String, Double> values, int draws, long seed) {
        if (universe.isEmpty() || values.isEmpty() || draws <= 0) return new BootstrapSummary(null, null, null, 0);
        ArrayList<Double> bootstrap = new ArrayList<>(draws);
        for (int replicate = 0; replicate < draws; replicate++) {
            SplittableRandom random = new SplittableRandom(seed + replicate * 0x9E3779B97F4A7C15L);
            double sum = 0.0;
            int count = 0;
            for (int sample = 0; sample < universe.size(); sample++) {
                String id = universe.get(random.nextInt(universe.size()));
                Double value = values.get(id);
                if (value != null && Double.isFinite(value)) { sum += value; count++; }
            }
            if (count > 0) bootstrap.add(sum / count);
        }
        if (bootstrap.isEmpty()) return new BootstrapSummary(null, null, null, 0);
        double[] sorted = bootstrap.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new BootstrapSummary(percentile(sorted, 0.20), percentile(sorted, 0.025), percentile(sorted, 0.975), sorted.length);
    }

    private static Double pearson(List<Double> x, List<Double> y) {
        if (x.size() < 3 || x.size() != y.size()) return null;
        double meanX = mean(x), meanY = mean(y), covariance = 0.0, varianceX = 0.0, varianceY = 0.0;
        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i) - meanX, dy = y.get(i) - meanY;
            covariance += dx * dy; varianceX += dx * dx; varianceY += dy * dy;
        }
        if (varianceX <= 0.0 || varianceY <= 0.0) return null;
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    private static boolean intervalsOverlap(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    private static boolean inBlock(Instant time, CalendarBlock block) {
        return !time.isBefore(block.start.atStartOfDay(ZoneOffset.UTC).toInstant())
                && time.isBefore(block.end.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static boolean isExactUtcHour(Instant instant) {
        return instant.getNano() == 0 && instant.getEpochSecond() % 3600L == 0;
    }

    private static int find(int[] parent, int value) {
        if (parent[value] != value) parent[value] = find(parent, parent[value]);
        return parent[value];
    }

    private static void union(int[] parent, int left, int right) {
        int a = find(parent, left), b = find(parent, right);
        if (a != b) parent[b] = a;
    }

    private static ArrayNode array(ObjectNode object, String field) { return array(object.path(field), field); }
    private static ArrayNode array(JsonNode value, String field) {
        if (!value.isArray()) throw fail("policy field is missing or malformed: " + field);
        return (ArrayNode) value;
    }

    private static String canonicalAsset(String value) {
        String asset = required(value, "asset").trim().toUpperCase(Locale.ROOT);
        if (asset.isEmpty() || !asset.matches("[A-Z0-9]{2,16}")) throw new IllegalArgumentException("asset is invalid");
        return asset;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String intentKey(String variant, String id) { return variant + "\n" + id; }

    private static Double numberOrNull(JsonNode node) {
        if (node.isNumber()) return node.asDouble();
        if (node.isTextual()) {
            try { return Double.parseDouble(node.asText()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static JsonNode nullable(Double value) {
        return value == null || !Double.isFinite(value) ? NullNode.getInstance() : DoubleNode.valueOf(value);
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) throw fail("cannot calculate percentile without values");
        double position = percentile * (sorted.length - 1);
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        if (low == high) return sorted[low];
        double weight = position - low;
        return sorted[low] * (1.0 - weight) + sorted[high] * weight;
    }

    private static String countStatus(int count) {
        return count < REFERENCE_GROUP_COUNT ? "INSUFFICIENT_EVIDENCE_WARNING_COMPUTATION_ALLOWED" : "REFERENCE_COUNT_REACHED";
    }

    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }

    private static final class Cluster {
        private final String id;
        private final Instant start;
        private Instant end;
        private final ArrayList<Event> events = new ArrayList<>();
        private Cluster(String id, Instant start, Instant end) { this.id = id; this.start = start; this.end = end; }
    }

    private record ClusterEvent(Event event, Instant start, Instant end) {}
    private record ClusterResult(List<Cluster> clusters, Map<String, Cluster> byEventId, ArrayNode rows, int unclusteredCount) {}
    private record PathKey(String asset, Instant anchor, int horizon) {}
    private record PathResult(double rawReturn, String reason, boolean missingAnchor, boolean missingEndpoint,
            boolean missingIntervening) {}
    private record ResponseKey(String anchor, String variant, String direction, int horizonDays) implements Comparable<ResponseKey> {
        @Override public int compareTo(ResponseKey other) {
            int compare = anchor.compareTo(other.anchor); if (compare != 0) return compare;
            compare = variant.compareTo(other.variant); if (compare != 0) return compare;
            compare = direction.compareTo(other.direction); if (compare != 0) return compare;
            return Integer.compare(horizonDays, other.horizonDays);
        }
    }
    private record ResponseObservation(String eventId, String asset, String clusterId, String branch,
            Double directionalReturn, String reason, boolean missingAnchor, boolean missingEndpoint,
            boolean missingIntervening, boolean nonHourly, boolean unclusteredGeometry) {}
    private record ResponseSummary(int observationCount, int validCount, int clusterCount, int missingAnchor,
            int missingEndpoint, int missingIntervening, int nonHourlyAnchor, int unclusteredGeometry,
            Double mean, Double p20, Double ciLow, Double ciHigh, int bootstrapValidDraws) {}
    private record BootstrapSummary(Double p20, Double low, Double high, int validDrawCount) {}
    private record PositionNode(Position position, String branch, String clusterId, Instant effectiveExit, boolean openAtEnd) {}
    private record OverlapResult(ObjectNode summary) { ObjectNode toJson() { return summary.deepCopy(); } }
    private record CalendarBlock(String id, LocalDate start, LocalDate end) {
        int days() { return (int) ChronoUnit.DAYS.between(start, end); }
    }
    private record DailySeries(LocalDate start, LocalDate endExclusive,
            Map<String, TreeMap<LocalDate, Double>> returnsByVariant,
            Map<String, Set<LocalDate>> missingDatesByVariant,
            Map<String, Set<LocalDate>> nonpositiveDatesByVariant,
            Map<String, TreeMap<LocalDate, Double>> closeByVariant, double initialEquity) {}
}
