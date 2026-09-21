package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Fast router-only proof that the staged fixture's actual macro series opposes its routed anchor. */
class LiquidationStagedMacroPreflightV1Test {
    private static final Instant FIRST_FILL = Instant.parse("2024-01-03T09:01:00Z");

    @Test
    void exactStagedFeatureFixtureRoutesAnchorAndRetainsTheCausalOpposingMacroRejection() {
        List<Observation> observations = observations(LiquidationStagedPortfolioReplayV1Test.featureRows());
        List<Observation> ordered = observations.stream().sorted(OBSERVATION_ORDER).toList();

        List<StageOneAnchorContext> anchors = Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION,
                        MacroGatePolicy.STRUCTURE_ONLY, ordered).stream()
                .flatMap(result -> result.stageOneAnchorContexts().stream()).toList();
        assertEquals(1, anchors.size(), "the staged feature fixture has one routed stage-one anchor");
        StageOneAnchorContext anchor = anchors.get(0);
        String anchorProjection = JsonHashes.canonicalString(LiquidationStructureRouterV1.stageOneAnchorJson(anchor));
        assertEquals(Branch.CONTINUATION, anchor.intent().branch(), "actual routed anchor: " + anchorProjection);
        assertEquals(Direction.SHORT, anchor.intent().direction(), "actual routed anchor: " + anchorProjection);
        assertEquals(anchor.intent().shockDirection(), anchor.intent().direction(),
                "continuation direction must equal the observed shock direction; actual routed anchor: " + anchorProjection);

        Router macroGated = Router.anchoredStaged(MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION);
        for (Observation observation : ordered) {
            if (processingTime(observation).isAfter(anchor.intent().decisionTime())) continue;
            assertTrue(macroGated.accept(observation).intents().isEmpty(),
                    "anchored mode loads the same causal history without choosing another stage-one entry");
        }
        RouteResult injected = macroGated.acceptFrozenInitialAnchor(LiquidationStructureRouterV1.stageOneAnchorJson(anchor));
        assertEquals(List.of(anchor.intent()), injected.intents());
        assertTrue(injected.rejections().isEmpty());
        assertTrue(FIRST_FILL.isAfter(anchor.intent().requestedExecutionAfter()));

        List<RejectedOpportunity> stageTwoRejections = new ArrayList<>();
        List<ConfirmedIntent> stageTwoIntents = new ArrayList<>();
        for (Observation observation : ordered) {
            Instant available = processingTime(observation);
            if (!available.isAfter(anchor.intent().decisionTime()) || !available.isBefore(FIRST_FILL)) continue;
            RouteResult beforeFill = macroGated.accept(observation);
            stageTwoIntents.addAll(beforeFill.intents().stream().filter(intent -> intent.stage() == 2).toList());
            stageTwoRejections.addAll(beforeFill.rejections().stream().filter(row -> row.stage() == 2).toList());
        }
        macroGated.onFill(new FillAck(anchor.intent().intentId(), anchor.intent().setupId(), anchor.intent().asset(), 1,
                FIRST_FILL, anchor.intent().confirmationClose(), anchor.intent().initialStop()));
        for (Observation observation : ordered) {
            Instant available = processingTime(observation);
            if (available.isBefore(FIRST_FILL)) continue;
            RouteResult result = macroGated.accept(observation);
            stageTwoIntents.addAll(result.intents().stream().filter(intent -> intent.stage() == 2).toList());
            stageTwoRejections.addAll(result.rejections().stream().filter(row -> row.stage() == 2).toList());
        }

        RejectedOpportunity opposing = stageTwoRejections.stream()
                .filter(row -> "MACRO_OPPOSING_BLOCKS_STAGE_2".equals(row.reasonCode())
                        && row.macroState() == MacroState.OPPOSING)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "fixture must produce an OPPOSING stage-two rejection; actual anchor=" + anchorProjection
                                + "; emitted stage-two intents=" + stageTwoIntents
                                + "; stage-two rejections=" + stageTwoRejections));
        assertTrue(stageTwoIntents.isEmpty(), "opposing macro must retain a rejected attempt and block its entry intent");
        assertEquals(anchor.intent().setupId(), opposing.setupId());
        assertEquals(anchor.intent().direction(), opposing.direction());
        assertTrue(opposing.sourceEvidence().stream().anyMatch(source -> "SP500_MACRO".equals(source.role())),
                "the rejection retains the actual S&P observations used by the macro gate");
    }

    private static List<Observation> observations(List<ObjectNode> rows) {
        Map<LocalDate, DailySides> daily = new TreeMap<>();
        List<Observation> out = new ArrayList<>();
        for (ObjectNode row : rows) {
            String series = row.path("series_id").asText();
            Instant event = Instant.ofEpochMilli(row.path("event_time").asLong());
            Instant available = Instant.ofEpochMilli(row.path("availability_time").asLong());
            if ("daily_liquidation_usd".equals(series)) {
                LocalDate date = event.atZone(ZoneOffset.UTC).toLocalDate();
                DailySides value = daily.computeIfAbsent(date, ignored -> new DailySides(available));
                value.availableAt = value.availableAt.isAfter(available) ? value.availableAt : available;
                if ("LONG".equals(row.path("side").asText())) value.longUsd = row.path("value").asDouble();
                else if ("SHORT".equals(row.path("side").asText())) value.shortUsd = row.path("value").asDouble();
                else throw new AssertionError("unknown daily liquidation side in fixture");
            } else if ("price_ohlc".equals(series)) {
                Timeframe timeframe = switch (row.path("timeframe").asText()) {
                    case "4h" -> Timeframe.FOUR_HOUR;
                    case "1h" -> Timeframe.ONE_HOUR;
                    default -> throw new AssertionError("unexpected staged fixture timeframe");
                };
                out.add(new Bar(row.path("asset").asText(), timeframe, event, available,
                        row.path("open").asDouble(), row.path("high").asDouble(), row.path("low").asDouble(),
                        row.path("close").asDouble(), "staged-" + row.path("asset").asText() + "-" + timeframe));
            } else if ("open_interest_base".equals(series)) {
                out.add(new OpenInterest(row.path("asset").asText(), event, available,
                        row.path("value").asDouble(), "staged-oi-" + row.path("asset").asText()));
            } else if ("sp500_close".equals(series)) {
                out.add(new MacroClose(event, available, row.path("close").asDouble(), true, "staged-sp500"));
            }
        }
        daily.forEach((date, value) -> {
            if (!Double.isFinite(value.longUsd) || !Double.isFinite(value.shortUsd)) {
                throw new AssertionError("staged fixture must contain both liquidation sides for " + date);
            }
            out.add(new DailyLiquidation("BTC", date, value.longUsd, value.shortUsd,
                    value.availableAt, "staged-daily-BTC"));
        });
        return List.copyOf(out);
    }

    private static Instant processingTime(Observation observation) {
        return observation instanceof DailyLiquidation daily ? daily.modeledAvailableAt() : observation.availableAt();
    }

    private static final Comparator<Observation> OBSERVATION_ORDER = Comparator.comparing(
                    LiquidationStagedMacroPreflightV1Test::processingTime)
            .thenComparing(Observation::eventTime)
            .thenComparingInt(LiquidationStagedMacroPreflightV1Test::priority)
            .thenComparing(Observation::asset)
            .thenComparing(Observation::seriesId);

    private static int priority(Observation observation) {
        if (observation instanceof DailyLiquidation) return 0;
        if (observation instanceof MacroClose) return 1;
        if (observation instanceof OpenInterest) return 2;
        return ((Bar) observation).timeframe() == Timeframe.FOUR_HOUR ? 3 : 4;
    }

    private static final class DailySides {
        double longUsd = Double.NaN;
        double shortUsd = Double.NaN;
        Instant availableAt;
        DailySides(Instant availableAt) { this.availableAt = availableAt; }
    }
}
