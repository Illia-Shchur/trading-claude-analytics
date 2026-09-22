package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LiquidationStructureRouterV1Test {
    private static final LocalDate STRESS_DAY = LocalDate.of(2024, 1, 1);
    private static final Instant STRESS_START = utc(STRESS_DAY, 8);
    private static final Instant MODEL_AVAILABLE = utc(STRESS_DAY.plusDays(2), 0);
    private static final String PRICE = "btc-4h-v1";
    private static final String HOUR = "btc-1h-v1";
    private static final String OI = "btc-oi-v1";

    @Test
    void dailyAvailabilityCoverageUsesExclusiveUpperBucketBoundary() {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        for (Observation observation : sorted(fixture(true, OiBoundary.VALID, true))) router.accept(observation);

        Instant decisionStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode snapshot = router.entryRuleAuditSnapshot(decisionStart, MODEL_AVAILABLE.plusSeconds(1));
        ObjectNode btc = (ObjectNode) snapshot.path("daily_gate_counts_by_asset").path("BTC");

        assertEquals(91, btc.path("daily_rows").asInt());
        assertEquals(91, btc.path("observed_coverage_days_in_decision_window").asInt());
        assertEquals(0, btc.path("missing_daily_days_inside_observed_asset_span").asInt());
        assertEquals(1, btc.path("setup_admitted").asInt());
        assertEquals(1, btc.path("unique_liquidation_stress_days").asInt());
        assertEquals("SETUP_ADMITTED", snapshot.path("liquidation_stress_event_trace").path(0).path("disposition").asText());
        ObjectNode exactExclusiveEnd = router.entryRuleAuditSnapshot(decisionStart, MODEL_AVAILABLE);
        ObjectNode beforeStressDay = (ObjectNode) exactExclusiveEnd.path("daily_gate_counts_by_asset").path("BTC");
        assertEquals(90, beforeStressDay.path("daily_rows").asInt());
        assertEquals(90, beforeStressDay.path("observed_coverage_days_in_decision_window").asInt());
        assertEquals(0, beforeStressDay.path("missing_daily_days_inside_observed_asset_span").asInt());
        assertEquals(0, exactExclusiveEnd.path("stress_event_trace_count").asInt());
        assertThrows(IllegalArgumentException.class,
                () -> router.entryRuleAuditSnapshot(decisionStart, decisionStart));
    }

    @Test
    void entryAuditSnapshotSeparatesUnavailableNoStressAndRejectedGeometryRowsAndHonorsItsWindow() {
        List<Observation> observations = new ArrayList<>(fixture(true, OiBoundary.TOO_FRESH, true));
        LocalDate extraWarmupDay = STRESS_DAY.minusDays(91);
        Instant extraWarmupStart = extraWarmupDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        observations.add(new DailyLiquidation("BTC", extraWarmupDay, 1, 1,
                extraWarmupStart.plus(Duration.ofHours(24)), "daily-v1"));
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        for (Observation observation : sorted(observations)) router.accept(observation);

        Instant start = STRESS_DAY.minusDays(91).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode all = router.entryRuleAuditSnapshot(start, MODEL_AVAILABLE.plusSeconds(1));
        ObjectNode btc = (ObjectNode) all.path("daily_gate_counts_by_asset").path("BTC");
        assertTrue(btc.path("window_unavailable").asInt() > 0);
        assertTrue(btc.path("no_liquidation_stress").asInt() > 0);
        assertEquals(1, btc.path("long_stress_days").asInt());
        assertEquals(1, btc.path("short_stress_days").asInt());
        assertEquals(1, btc.path("geometry_rejected").asInt());
        assertEquals(0, btc.path("setup_admitted").asInt());
        assertEquals("GEOMETRY_REJECTED_NO_FAST_OR_SLOW_MATCH",
                all.path("liquidation_stress_event_trace").path(0).path("disposition").asText());

        ObjectNode onlyStress = router.entryRuleAuditSnapshot(MODEL_AVAILABLE, MODEL_AVAILABLE.plusNanos(1));
        assertEquals(1, onlyStress.path("daily_gate_counts_by_asset").path("BTC").path("daily_rows").asInt());
        assertEquals(0, onlyStress.path("daily_gate_counts_by_asset").path("BTC").path("no_liquidation_stress").asInt());
        ObjectNode afterWindow = router.entryRuleAuditSnapshot(MODEL_AVAILABLE.plusNanos(1), MODEL_AVAILABLE.plusSeconds(3600));
        assertEquals(0, afterWindow.path("daily_gate_counts_by_asset").path("BTC").path("daily_rows").asInt());
        assertEquals(0, afterWindow.path("stress_event_trace_count").asInt());
    }

    @Test
    void initialEntryAuditCountsMissingThreeHourWindowAndRepeatedQualifyingBarsWhileIntentIsPending() {
        List<Observation> incomplete = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        Instant missingThirdHour = MODEL_AVAILABLE.plus(Duration.ofHours(6));
        incomplete.removeIf(value -> value instanceof Bar bar && bar.timeframe() == Timeframe.ONE_HOUR
                && bar.startTime().equals(missingThirdHour));
        Router missingRouter = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        for (Observation observation : sorted(incomplete)) missingRouter.accept(observation);
        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode missingAudit = missingRouter.entryRuleAuditSnapshot(auditStart, MODEL_AVAILABLE.plus(Duration.ofHours(10)));
        JsonNode missingEntry = missingAudit.path("liquidation_stress_event_trace").path(0).path("initial_entry");
        assertEquals(0, missingEntry.path("intent_count").asInt());
        assertEquals(1, missingEntry.path("all_failed_gate_counts")
                .path("THREE_CONSECUTIVE_H1_CONFIRMATION_WINDOW_UNAVAILABLE").asInt());

        List<Observation> repeated = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        Instant nextHour = MODEL_AVAILABLE.plus(Duration.ofHours(9));
        repeated.add(bar("BTC", Timeframe.ONE_HOUR, nextHour,
                99.4, 99.5, 99.0, 99.2, nextHour.plus(Duration.ofHours(1)), HOUR));
        Router pendingRouter = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        List<ConfirmedIntent> intents = new ArrayList<>();
        for (Observation observation : sorted(repeated)) intents.addAll(pendingRouter.accept(observation).intents());
        assertEquals(1, intents.size(), "later confirmation bars cannot duplicate an unacknowledged first-stage intent");
        ObjectNode pendingAudit = pendingRouter.entryRuleAuditSnapshot(auditStart, MODEL_AVAILABLE.plus(Duration.ofHours(11)));
        assertEquals(1, pendingAudit.path("liquidation_stress_event_trace").path(0).path("initial_entry")
                .path("first_failure_counts").path("STAGE_ONE_INTENT_ALREADY_PENDING").asInt());
    }

    @Test
    void initialEntryAuditExpiresAfterEveryConfirmedIntentWasRejected() {
        List<Observation> observations = sorted(fixture(true, OiBoundary.VALID, true));
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        ConfirmedIntent initial = null;
        for (Observation observation : observations) {
            RouteResult result = router.accept(observation);
            if (!result.intents().isEmpty()) initial = result.intents().get(0);
        }
        assertNotNull(initial);

        Instant rejectionTime = initial.requestedExecutionAfter().plusNanos(1);
        router.onNoFill(new NoFillAck(initial.intentId(), initial.setupId(), initial.asset(), 1,
                rejectionTime, "CAPACITY_REJECTED"));

        Instant firstHourAfterEntryDeadline = MODEL_AVAILABLE.plus(Duration.ofHours(81));
        router.accept(bar("BTC", Timeframe.ONE_HOUR, firstHourAfterEntryDeadline,
                99.4, 99.5, 99.0, 99.2, firstHourAfterEntryDeadline.plus(Duration.ofHours(1)), HOUR));

        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode audit = router.entryRuleAuditSnapshot(auditStart, firstHourAfterEntryDeadline.plus(Duration.ofHours(1)));
        JsonNode event = audit.path("liquidation_stress_event_trace").path(0);
        assertEquals(1, event.path("execution").path("no_fills").size());
        assertEquals("CONFIRMED_STAGE_ONE_INTENTS_NOT_FILLED_BEFORE_ENTRY_WINDOW_END",
                event.path("terminal_reason").asText());
        assertEquals(firstHourAfterEntryDeadline.plus(Duration.ofHours(1)).toString(),
                event.path("terminal_time").asText());
    }

    @Test
    void branchAuditCountsCandidateResetBeforeAnyBranchIsSelected() {
        List<Observation> observations = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        Instant secondStart = MODEL_AVAILABLE.plus(Duration.ofHours(4));
        observations.removeIf(value -> value instanceof Bar bar && bar.timeframe() == Timeframe.FOUR_HOUR
                && bar.startTime().equals(secondStart));
        observations.add(bar("BTC", Timeframe.FOUR_HOUR, secondStart,
                100.5, 101.0, 100.4, 100.8, secondStart.plus(Duration.ofHours(4)), PRICE));
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        for (Observation observation : sorted(observations)) router.accept(observation);

        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode audit = router.entryRuleAuditSnapshot(auditStart, MODEL_AVAILABLE.plus(Duration.ofHours(9)));
        JsonNode route = audit.path("liquidation_stress_event_trace").path(0).path("route");
        assertEquals(1, route.path("branch_candidate_reset_bar_count").asInt());
        assertEquals("WAITING_FOR_TWO_H4_CONFIRMATIONS", route.path("branch_status").asText());
    }

    @Test
    void routedAndForcedControlsShareTheSameConfirmedOpportunityAndKeepTheForcedDirection() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        ConfirmedIntent routed = onlyIntent(Variant.ROUTED_REVERSAL_CONTINUATION, observations);
        ConfirmedIntent continued = onlyIntent(Variant.ALWAYS_CONTINUATION_CONTROL, observations);
        ConfirmedIntent reversed = onlyIntent(Variant.ALWAYS_REVERSAL_CONTROL, observations);

        assertEquals(routed.pairId(), continued.pairId());
        assertEquals(routed.pairId(), reversed.pairId());
        assertEquals(routed.decisionTime(), continued.decisionTime());
        assertEquals(routed.decisionTime(), reversed.decisionTime());
        assertEquals(Branch.CONTINUATION, routed.branch());
        assertEquals(Branch.CONTINUATION, continued.branch());
        assertEquals(Branch.CONTINUATION, reversed.routedBranch());
        assertEquals(Branch.REVERSAL, reversed.branch());
        assertEquals(Direction.SHORT, continued.direction());
        assertEquals(Direction.LONG, reversed.direction());
        assertNull(continued.reversalTarget());
        assertNotNull(reversed.reversalTarget());
        assertEquals(1, routed.stage());
        assertEquals(routed.decisionTime().plusNanos(1), routed.requestedExecutionAfter());
        assertEquals(STRESS_START.plus(Duration.ofHours(4)),
                routed.sourceEvidence().stream().filter(e -> e.role().equals("PRICE_EVENT"))
                        .map(SourceEvidence::eventTime).findFirst().orElseThrow());

        PairedDecisionContext context = onlyPairedContext(observations);
        assertEquals(100.0, context.immutableRecoveryTarget());
        assertEquals(routed, context.routedAnchor());
        assertEquals(3, context.lastThreeCompletedHours().size());
        PairedControlResult paired = LiquidationStructureRouterV1.derivePairedControlArms(context);
        assertEquals(routed.pairId(), paired.pairId());
        assertEquals(routed.decisionTime(), paired.decisionTime());
        assertEquals(List.of(Variant.ALWAYS_CONTINUATION_CONTROL, Variant.ALWAYS_REVERSAL_CONTROL),
                paired.arms().stream().map(ControlArmOpportunity::variant).toList());
        ConfirmedIntent pairedContinue = paired.arms().get(0).intent();
        ConfirmedIntent pairedReverse = paired.arms().get(1).intent();
        assertEquals(routed.decisionTime(), pairedContinue.decisionTime());
        assertEquals(routed.requestedExecutionAfter(), pairedContinue.requestedExecutionAfter());
        assertEquals(routed.decisionTime(), pairedReverse.decisionTime());
        assertEquals(routed.requestedExecutionAfter(), pairedReverse.requestedExecutionAfter());
        assertEquals(Direction.SHORT, pairedContinue.direction());
        assertEquals(Direction.LONG, pairedReverse.direction());
        assertNull(pairedContinue.reversalTarget());
        assertEquals(100.0, pairedReverse.reversalTarget());
        assertEquals(3, pairedContinue.sourceEvidence().stream()
                .filter(e -> e.role().equals("PAIRED_CONTROL_STOP_SOURCE")).count());
    }

    @Test
    void pairedControlsRetainMissingTargetAndIncompleteGeometryAsDecisionTimeRejections() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        PairedDecisionContext context = onlyPairedContext(observations);
        PairedDecisionContext missingMidpoint = new PairedDecisionContext(context.routedAnchor(), null,
                context.lastThreeCompletedHours());
        PairedControlResult missingTarget = LiquidationStructureRouterV1.derivePairedControlArms(missingMidpoint);
        assertNotNull(missingTarget.arms().get(0).intent());
        assertNull(missingTarget.arms().get(0).rejection());
        assertNull(missingTarget.arms().get(1).intent());
        assertTrue(missingTarget.arms().get(1).rejection().reasonCode().contains("MISSING_SHARED_RECOVERY_TARGET"));
        assertEquals(context.routedAnchor().decisionTime(), missingTarget.arms().get(1).rejection().decisionTime());

        PairedDecisionContext incomplete = new PairedDecisionContext(context.routedAnchor(),
                context.immutableRecoveryTarget(), context.lastThreeCompletedHours().subList(0, 2));
        PairedControlResult noSharedGeometry = LiquidationStructureRouterV1.derivePairedControlArms(incomplete);
        assertTrue(noSharedGeometry.arms().stream().allMatch(arm -> arm.intent() == null
                && arm.rejection() != null
                && arm.rejection().reasonCode().contains("THREE_COMPLETED_HOURLY_BARS")));
    }

    @Test
    void earlierStopSourceHourTouchDoesNotAddControlOnlyMidpointVeto() {
        PairedDecisionContext context = onlyPairedContext(fixture(true, OiBoundary.VALID, true));
        List<Bar> bars = context.lastThreeCompletedHours();
        double midpoint = context.immutableRecoveryTarget();
        assertTrue(bars.subList(0, 2).stream().anyMatch(bar -> bar.high() >= midpoint));
        assertTrue(bars.get(2).high() < midpoint,
                "the routed baseline only rejects when the current confirmation bar reaches the target");

        ControlArmOpportunity reversal = LiquidationStructureRouterV1.derivePairedControlArms(context).arms().get(1);
        assertNotNull(reversal.intent());
        assertNull(reversal.rejection());
    }

    @Test
    void strictDailyAvailabilityAndIncompleteLookbackFailClosed() {
        List<Observation> complete = fixture(true, OiBoundary.VALID, true);
        List<Observation> missingCalendarDay = fixture(false, OiBoundary.VALID, true);
        List<ConfirmedIntent> good = intents(Variant.ROUTED_REVERSAL_CONTINUATION, complete);
        assertEquals(1, good.size());
        assertTrue(intents(Variant.ROUTED_REVERSAL_CONTINUATION, missingCalendarDay).isEmpty());
        assertEquals(1, qualifiedEvents(Variant.ROUTED_REVERSAL_CONTINUATION, complete).size());
        assertTrue(qualifiedEvents(Variant.ROUTED_REVERSAL_CONTINUATION, missingCalendarDay).isEmpty());

        // The qualifying 4h close at the exact t+48h publication boundary is present in every fixture.
        ConfirmedIntent intent = good.get(0);
        assertTrue(intent.decisionTime().isAfter(MODEL_AVAILABLE.plus(Duration.ofHours(8))));
    }

    @Test
    void unarmedBranchStillExpiresAtItsOwnSeventyTwoHourDeadline() {
        List<Observation> observations = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        Instant secondQualifyingStart = MODEL_AVAILABLE.plus(Duration.ofHours(4));
        observations.removeIf(value -> value instanceof Bar bar && bar.timeframe() == Timeframe.FOUR_HOUR
                && bar.startTime().equals(secondQualifyingStart));
        Instant afterBranchDeadline = MODEL_AVAILABLE.plus(Duration.ofHours(72));
        observations.add(bar("BTC", Timeframe.FOUR_HOUR, afterBranchDeadline,
                96.8, 97.2, 95.8, 96.5, afterBranchDeadline.plus(Duration.ofHours(4)), PRICE));
        assertTrue(intents(Variant.ROUTED_REVERSAL_CONTINUATION, observations).isEmpty());
    }

    @Test
    void everyOiEndpointMustPassItsOwnFiveToTenMinuteAndStrictAvailabilityWindow() {
        for (OiBoundary boundary : List.of(OiBoundary.TOO_FRESH, OiBoundary.STALE, OiBoundary.AVAILABLE_AT_ENDPOINT)) {
            assertTrue(intents(Variant.ROUTED_REVERSAL_CONTINUATION,
                    fixture(true, boundary, true)).isEmpty(), boundary.toString());
            assertTrue(qualifiedEvents(Variant.ROUTED_REVERSAL_CONTINUATION,
                    fixture(true, boundary, true)).isEmpty(), "unqualified OI must not emit an event: " + boundary);
        }
        assertEquals(1, intents(Variant.ROUTED_REVERSAL_CONTINUATION,
                fixture(true, OiBoundary.VALID, true)).size());
    }

    @Test
    void laterDailyStressEventIsEmittedWithCausalGeometryEvenWhileAssetPositionIsOpen() {
        List<Observation> observations = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        LocalDate secondStressDay = STRESS_DAY.plusDays(1);
        Instant secondFastStart = STRESS_START.plus(Duration.ofDays(1));
        observations.removeIf(value -> value instanceof Bar bar && bar.timeframe() == Timeframe.FOUR_HOUR
                && bar.startTime().equals(secondFastStart));
        observations.add(bar("BTC", Timeframe.FOUR_HOUR, secondFastStart,
                100, 100.2, 95.8, 96.0, secondFastStart.plus(Duration.ofHours(4)), PRICE));
        addOiPair(observations, secondFastStart, secondFastStart.plus(Duration.ofHours(4)), OiBoundary.VALID);
        Instant bucketStart = secondStressDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        observations.add(new DailyLiquidation("BTC", secondStressDay, 2, 2,
                bucketStart.plus(Duration.ofHours(24)), "daily-v1"));

        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        List<QualifiedDailyStressEvent> events = new ArrayList<>();
        ConfirmedIntent initial = null;
        RouteResult secondDayResult = null;
        for (Observation observation : sorted(observations)) {
            RouteResult result = router.accept(observation);
            events.addAll(result.qualifiedDailyStressEvents());
            if (result.intents().stream().anyMatch(intent -> intent.stage() == 1)) {
                initial = result.intents().stream().filter(intent -> intent.stage() == 1).findFirst().orElseThrow();
                router.onFill(new FillAck(initial.intentId(), initial.setupId(), initial.asset(), 1,
                        initial.requestedExecutionAfter().plusNanos(1), initial.confirmationClose(), initial.initialStop()));
            }
            if (observation instanceof DailyLiquidation daily && daily.bucketStart().equals(secondStressDay)) {
                secondDayResult = result;
            }
        }

        assertNotNull(initial, "the first qualified event must establish an occupied position for the test");
        assertNotNull(secondDayResult);
        assertTrue(secondDayResult.intents().isEmpty(), "occupied asset cannot open a second setup");
        assertEquals(2, events.size(), "both independent market events remain in the blackout inventory");
        QualifiedDailyStressEvent second = events.get(1);
        assertEquals("BTC", second.asset());
        assertEquals(secondStressDay, second.bucketStart());
        assertEquals(bucketStart, second.bucketEventTime());
        assertEquals(secondStressDay.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(), second.availableAt());
        assertEquals(Direction.SHORT, second.shockDirection());
        assertTrue(second.sourceEvidence().stream().anyMatch(source -> source.role().equals("DAILY_STRESS_CURRENT")));
        assertTrue(second.sourceEvidence().stream().anyMatch(source -> source.role().equals("OI_START")));
        assertTrue(second.sourceEvidence().stream().anyMatch(source -> source.role().equals("OI_END")));
        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode audit = router.entryRuleAuditSnapshot(auditStart, second.availableAt().plusNanos(1));
        ObjectNode btc = (ObjectNode) audit.path("daily_gate_counts_by_asset").path("BTC");
        assertEquals(1, btc.path("setup_admitted").asInt());
        assertEquals(1, btc.path("setup_suppressed").asInt());
        assertEquals("SETUP_SUPPRESSED_ASSET_OCCUPIED",
                audit.path("liquidation_stress_event_trace").path(1).path("disposition").asText());
    }

    @Test
    void entryAuditIdentifiesAStressSetupThatArrivesAtThePriorPositionClose() {
        List<Observation> observations = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        LocalDate secondStressDay = STRESS_DAY.plusDays(1);
        Instant secondFastStart = STRESS_START.plus(Duration.ofDays(1));
        observations.removeIf(value -> value instanceof Bar bar && bar.timeframe() == Timeframe.FOUR_HOUR
                && bar.startTime().equals(secondFastStart));
        observations.add(bar("BTC", Timeframe.FOUR_HOUR, secondFastStart,
                100, 100.2, 95.8, 96.0, secondFastStart.plus(Duration.ofHours(4)), PRICE));
        addOiPair(observations, secondFastStart, secondFastStart.plus(Duration.ofHours(4)), OiBoundary.VALID);
        Instant bucketStart = secondStressDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant secondAvailable = bucketStart.plus(Duration.ofHours(48));
        observations.add(new DailyLiquidation("BTC", secondStressDay, 2, 2,
                bucketStart.plus(Duration.ofHours(24)), "daily-v1"));

        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        ConfirmedIntent first = null;
        for (Observation observation : sorted(observations)) {
            if (observation instanceof DailyLiquidation daily && daily.bucketStart().equals(secondStressDay)) {
                assertNotNull(first);
                router.onClose(new CloseAck(first.setupId(), first.asset(), secondAvailable, CloseReason.STOP));
            }
            RouteResult result = router.accept(observation);
            if (first == null && result.intents().stream().anyMatch(intent -> intent.stage() == 1)) {
                first = result.intents().stream().filter(intent -> intent.stage() == 1).findFirst().orElseThrow();
                router.onFill(new FillAck(first.intentId(), first.setupId(), first.asset(), 1,
                        first.requestedExecutionAfter().plusNanos(1), first.confirmationClose(), first.initialStop()));
            }
        }

        assertNotNull(first);
        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode audit = router.entryRuleAuditSnapshot(auditStart, secondAvailable.plusNanos(1));
        assertEquals("SETUP_SUPPRESSED_NOT_AFTER_PRIOR_CLOSE",
                audit.path("liquidation_stress_event_trace").path(1).path("disposition").asText());
        assertEquals(secondAvailable.toString(), audit.path("liquidation_stress_event_trace").path(1).path("prior_close_time").asText());
    }

    @Test
    void closeAuditReportsTheFirstStagingBlockerForAnUnadvancedFilledSetup() {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        ConfirmedIntent first = null;
        for (Observation observation : sorted(fixture(true, OiBoundary.VALID, true))) {
            RouteResult result = router.accept(observation);
            if (first == null && result.intents().stream().anyMatch(intent -> intent.stage() == 1)) {
                first = result.intents().stream().filter(intent -> intent.stage() == 1).findFirst().orElseThrow();
            }
        }
        assertNotNull(first);
        router.onFill(new FillAck(first.intentId(), first.setupId(), first.asset(), first.stage(),
                first.requestedExecutionAfter().plusNanos(1), first.confirmationClose(), first.initialStop()));
        Instant closeTime = first.requestedExecutionAfter().plus(Duration.ofHours(1));
        router.onClose(new CloseAck(first.setupId(), first.asset(), closeTime, CloseReason.STOP));

        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode audit = router.entryRuleAuditSnapshot(auditStart, closeTime.plusNanos(1));
        JsonNode trace = audit.path("liquidation_stress_event_trace").path(0);
        assertEquals("POSITION_CLOSED_STOP", trace.path("terminal_reason").asText());
        assertEquals(closeTime.toString(), trace.path("terminal_time").asText());
        assertEquals("NO_FAVORABLE_H4_AFTER_FILL", trace.path("staging").path("terminal_addition_blocker").asText());
    }

    @Test
    void replayIsPermutationDeterministicAndLaterBarsCannotRewriteAnEarlierDecision() {
        List<Observation> original = fixture(true, OiBoundary.VALID, true);
        List<RouteResult> expected = Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION, original);
        assertEquals(expected, Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION,
                MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION, original));
        List<Observation> shuffled = new ArrayList<>(original);
        Collections.shuffle(shuffled, new Random(124));
        assertEquals(expected, Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION, shuffled));

        ConfirmedIntent first = onlyIntent(Variant.ROUTED_REVERSAL_CONTINUATION, original);
        List<Observation> withFuture = new ArrayList<>(original);
        withFuture.add(bar("BTC", Timeframe.ONE_HOUR, utc(STRESS_DAY.plusDays(2), 9),
                99.4, 110, 90, 105, utc(STRESS_DAY.plusDays(2), 10), HOUR));
        ConfirmedIntent stillFirst = onlyIntent(Variant.ROUTED_REVERSAL_CONTINUATION, withFuture);
        assertEquals(first, stillFirst);
    }

    @Test
    void lateOutOfOrderBarIsStoredForAuditButCannotRewindTheSignalPath() {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        List<Observation> observations = sorted(fixture(true, OiBoundary.VALID, true));
        ConfirmedIntent emitted = null;
        for (Observation observation : observations) {
            RouteResult result = router.accept(observation);
            if (!result.intents().isEmpty()) emitted = result.intents().get(0);
        }
        assertNotNull(emitted);
        ConfirmedIntent accepted = emitted;
        Bar delayedOlder = bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(1)),
                100, 101, 99, 99.2, utc(STRESS_DAY.plusDays(2), 11), HOUR);
        assertTrue(router.accept(delayedOlder).intents().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(accepted.intentId(),
                accepted.setupId(), accepted.asset(), 1, accepted.requestedExecutionAfter(),
                accepted.confirmationClose(), accepted.initialStop())));
    }

    @Test
    void exactDeadlineConfirmationSurvivesAnInterveningObservationUntilFillAcknowledgement() {
        Instant deadline = MODEL_AVAILABLE.plus(Duration.ofHours(8 + 72));
        List<Observation> observations = exactDeadlineFixture();

        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        ConfirmedIntent emitted = null;
        for (Observation observation : sorted(observations)) {
            RouteResult result = router.accept(observation);
            if (!result.intents().isEmpty()) emitted = result.intents().get(0);
        }
        assertNotNull(emitted, "a valid confirmation available at the exact 72-hour deadline is accepted");
        ConfirmedIntent accepted = emitted;
        assertEquals(deadline, accepted.decisionTime());

        Instant interveningAvailability = deadline.plus(Duration.ofMinutes(1));
        assertTrue(router.accept(new OpenInterest("BTC", interveningAvailability, interveningAvailability, 90, OI))
                .intents().isEmpty());
        assertDoesNotThrow(() -> router.onFill(new FillAck(accepted.intentId(), accepted.setupId(), accepted.asset(),
                accepted.stage(), accepted.requestedExecutionAfter().plusNanos(1), accepted.confirmationClose(), accepted.initialStop())));
    }

    @Test
    void laterFourHourInvalidationCancelsAnOutstandingStageOneIntentBeforeFill() {
        Instant deadline = MODEL_AVAILABLE.plus(Duration.ofHours(8 + 72));
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        ConfirmedIntent accepted = null;
        for (Observation observation : sorted(exactDeadlineFixture())) {
            RouteResult result = router.accept(observation);
            if (!result.intents().isEmpty()) accepted = result.intents().get(0);
        }
        assertNotNull(accepted);
        ConfirmedIntent pending = accepted;
        assertEquals(deadline, pending.decisionTime());

        Instant invalidationAvailable = deadline.plusSeconds(30);
        Bar invalidation = bar("BTC", Timeframe.FOUR_HOUR, deadline.minus(Duration.ofHours(12)),
                100, 100.2, 99.8, 100, invalidationAvailable, PRICE);
        RouteResult result = router.accept(invalidation);
        assertEquals(1, result.pendingCancellations().size());
        PendingCancellation cancellation = result.pendingCancellations().get(0);
        assertEquals(pending.intentId(), cancellation.intentId());
        assertEquals("BRANCH_INVALIDATED_BEFORE_FILL", cancellation.reasonCode());
        assertEquals(invalidationAvailable, cancellation.cancellationTime());
        assertTrue(cancellation.sourceEvidence().stream()
                .anyMatch(source -> source.role().equals("PRE_FILL_STRUCTURAL_INVALIDATION")));
        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(pending.intentId(),
                pending.setupId(), pending.asset(), pending.stage(), deadline.plus(Duration.ofMinutes(1)),
                pending.confirmationClose(), pending.initialStop())));
        assertDoesNotThrow(() -> router.onNoFill(new NoFillAck(cancellation.intentId(), cancellation.setupId(),
                pending.asset(), cancellation.stage(), cancellation.cancellationTime(), cancellation.reasonCode())));
    }

    private static List<Observation> exactDeadlineFixture() {
        Instant armTime = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        Instant deadline = armTime.plus(Duration.ofHours(72));
        List<Observation> observations = new ArrayList<>(fixture(true, OiBoundary.VALID, true));
        observations.removeIf(value -> value instanceof Bar bar && bar.timeframe() == Timeframe.ONE_HOUR
                && bar.startTime().equals(armTime));
        // This valid continuation bar arrives after the original unarmed-branch deadline, but
        // before the separate 72-hour initial-zone deadline measured from armTime.
        observations.add(bar("BTC", Timeframe.FOUR_HOUR, deadline.minus(Duration.ofHours(8)),
                96.8, 97.2, 95.8, 96.5, deadline.minus(Duration.ofHours(4)), PRICE));
        addOneHour(observations, deadline.minus(Duration.ofHours(3)), 100, 100.2, 99.8, 100.0);
        addOneHour(observations, deadline.minus(Duration.ofHours(2)), 100, 100.2, 99.8, 100.0);
        observations.add(bar("BTC", Timeframe.ONE_HOUR, deadline.minus(Duration.ofHours(1)),
                99.7, 99.8, 99.4, 99.45, deadline, HOUR));
        return observations;
    }

    @Test
    void boundedMacroCalendarAppliesObservedHolidaysEarlyClosesAndOneFullSessionLag() {
        ZoneId ny = ZoneId.of("America/New_York");
        Instant jan8Close = LocalDate.of(2025, 1, 8).atTime(16, 0).atZone(ny).toInstant();
        Instant jan10Close = LocalDate.of(2025, 1, 10).atTime(16, 0).atZone(ny).toInstant();
        assertDoesNotThrow(() -> macro(jan8Close, jan10Close)); // Jan 9, 2025 was an exchange closure.
        assertThrows(IllegalArgumentException.class, () -> macro(jan8Close,
                LocalDate.of(2025, 1, 9).atTime(16, 0).atZone(ny).toInstant()));

        Instant juneteenth = LocalDate.of(2025, 6, 19).atTime(16, 0).atZone(ny).toInstant();
        assertThrows(IllegalArgumentException.class, () -> macro(juneteenth,
                LocalDate.of(2025, 6, 20).atTime(16, 0).atZone(ny).toInstant()));
        Instant thanksgivingEarlyClose = LocalDate.of(2024, 11, 29).atTime(13, 0).atZone(ny).toInstant();
        assertDoesNotThrow(() -> macro(thanksgivingEarlyClose,
                LocalDate.of(2024, 12, 2).atTime(16, 0).atZone(ny).toInstant()));
        assertThrows(IllegalArgumentException.class, () -> macro(
                LocalDate.of(2024, 11, 29).atTime(16, 0).atZone(ny).toInstant(),
                LocalDate.of(2024, 12, 2).atTime(16, 0).atZone(ny).toInstant()));
        assertThrows(IllegalArgumentException.class, () -> macro(
                LocalDate.of(2027, 2, 1).atTime(16, 0).atZone(ny).toInstant(),
                LocalDate.of(2027, 2, 2).atTime(16, 0).atZone(ny).toInstant()));
    }

    @Test
    void macroThresholdBoundariesAreNeutralWithoutFloatingPointDrift() {
        assertEquals(MacroState.NEUTRAL, stageTwoMacroState(100.5));
        assertEquals(MacroState.NEUTRAL, stageTwoMacroState(99.5));
        assertEquals(MacroState.OPPOSING, stageTwoMacroState(100.5001));
        assertEquals(MacroState.SUPPORTIVE, stageTwoMacroState(99.4999));
    }

    @Test
    void realFillsThenCausalTwoRightPivotsAndFreshMacroConfirmationsProduceStagesTwoAndThree() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        observations.addAll(macroPath());
        observations.addAll(postInitialFillBars());
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        List<ConfirmedIntent> allIntents = new ArrayList<>();
        List<RejectedOpportunity> allRejections = new ArrayList<>();
        for (Observation observation : sorted(observations)) {
            RouteResult result = router.accept(observation);
            allIntents.addAll(result.intents());
            allRejections.addAll(result.rejections());
            for (ConfirmedIntent intent : result.intents()) {
                // Synthetic acknowledgement only exercises the state contract; accounting owns
                // executable fills in production.
                router.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), intent.stage(),
                        intent.requestedExecutionAfter().plusNanos(1), intent.confirmationClose(), intent.initialStop()));
            }
        }

        assertEquals(List.of(1, 2, 3), allIntents.stream().map(ConfirmedIntent::stage).toList());
        ConfirmedIntent stageTwo = allIntents.get(1), stageThree = allIntents.get(2);
        assertEquals(MacroState.NEUTRAL, stageTwo.macroState());
        assertEquals(MacroState.SUPPORTIVE, stageThree.macroState());
        assertEquals(Direction.SHORT, stageThree.direction());
        assertTrue(stageThree.pivotConfirmedAt().isBefore(stageThree.confirmationBarStart()));
        assertEquals(utc(LocalDate.of(2024, 1, 4), 4), stageTwo.pivotConfirmedAt());
        assertTrue(stageTwo.sourceEvidence().stream().anyMatch(e -> e.role().equals("FAVORABLE_AFTER_FILL")));
        assertTrue(stageTwo.initialStop() == allIntents.get(0).initialStop());
        assertTrue(stageThree.initialStop() == allIntents.get(0).initialStop());
        assertTrue(allRejections.isEmpty());
        Instant closeTime = stageThree.requestedExecutionAfter().plus(Duration.ofHours(1));
        router.onClose(new CloseAck(stageThree.setupId(), stageThree.asset(), closeTime,
                CloseReason.STOP));
        Instant auditStart = STRESS_DAY.minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48));
        ObjectNode audit = router.entryRuleAuditSnapshot(auditStart, closeTime.plusNanos(1));
        JsonNode event = audit.path("liquidation_stress_event_trace").path(0);
        assertFalse(event.path("staging").has("terminal_addition_blocker"),
                "once stage three is filled, closing the position has no next-tranche blocker");
    }

    @Test
    void stagedFillAcknowledgementUsesTheAlreadyRatchetTightenedCommonStop() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        observations.addAll(macroPath());
        observations.addAll(postInitialFillBars());
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        List<ConfirmedIntent> intents = new ArrayList<>();
        double commonStop = Double.NaN;
        boolean checkedStaleStageStop = false;

        for (Observation observation : sorted(observations)) {
            RouteResult result = router.accept(observation);
            intents.addAll(result.intents());
            for (ConfirmedIntent intent : result.intents()) {
                Instant fillTime = intent.requestedExecutionAfter().plusNanos(1);
                if (intent.stage() == 1) {
                    router.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), 1,
                            fillTime, intent.confirmationClose(), intent.initialStop()));
                    commonStop = intent.initialStop();
                } else {
                    if (!checkedStaleStageStop) {
                        // The stage intent snapshots the current stop at decision time. A tighter
                        // account-side ratchet can be acknowledged before its next-minute fill.
                        commonStop = intent.direction() == Direction.LONG
                                ? intent.initialStop() + 0.01 : intent.initialStop() - 0.01;
                        router.onStopUpdate(new StopAck(intent.setupId(), intent.asset(),
                                intent.requestedExecutionAfter(), commonStop));
                        assertThrows(IllegalArgumentException.class, () -> router.onFill(new FillAck(
                                intent.intentId(), intent.setupId(), intent.asset(), intent.stage(), fillTime,
                                intent.confirmationClose(), intent.initialStop())));
                        checkedStaleStageStop = true;
                    }
                    router.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), intent.stage(),
                            fillTime, intent.confirmationClose(), commonStop));
                }
            }
        }

        assertTrue(checkedStaleStageStop, "fixture must emit a staged addition after the stop ratchet");
        assertEquals(List.of(1, 2, 3), intents.stream().map(ConfirmedIntent::stage).toList());
    }

    @Test
    void structureOnlyMacroPolicyPreservesAllStructureAndLevelsWhenMacroWouldOtherwisePass() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        observations.addAll(macroPath());
        observations.addAll(postInitialFillBars());
        List<ConfirmedIntent> gated = filledIntents(observations, MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION);
        List<ConfirmedIntent> structural = filledIntents(observations, MacroGatePolicy.STRUCTURE_ONLY);
        assertEquals(List.of(1, 2, 3), gated.stream().map(ConfirmedIntent::stage).toList());
        assertEquals(gated.stream().map(ConfirmedIntent::decisionTime).toList(),
                structural.stream().map(ConfirmedIntent::decisionTime).toList());
        for (int index = 0; index < gated.size(); index++) {
            ConfirmedIntent expected = gated.get(index), actual = structural.get(index);
            assertEquals(expected.branch(), actual.branch());
            assertEquals(expected.direction(), actual.direction());
            assertEquals(expected.zoneCenter(), actual.zoneCenter());
            assertEquals(expected.zoneLower(), actual.zoneLower());
            assertEquals(expected.zoneUpper(), actual.zoneUpper());
            assertEquals(expected.pivotPrice(), actual.pivotPrice());
            assertEquals(expected.pivotTime(), actual.pivotTime());
            assertEquals(expected.pivotConfirmedAt(), actual.pivotConfirmedAt());
            assertEquals(expected.preEventAtr(), actual.preEventAtr());
            assertEquals(expected.initialStop(), actual.initialStop());
            assertEquals(expected.reversalTarget(), actual.reversalTarget());
            assertEquals(expected.trancheRiskFraction(), actual.trancheRiskFraction());
            assertEquals(expected.proposedLeverage(), actual.proposedLeverage());
            assertEquals(expected.maximumHoldingDays(), actual.maximumHoldingDays());
            if (actual.stage() == 1) assertEquals(MacroState.NOT_REQUIRED, actual.macroState());
            else {
                assertEquals(MacroState.NOT_REQUIRED, actual.macroState());
                assertTrue(actual.macroEligible());
                assertTrue(actual.sourceEvidence().stream().noneMatch(source -> source.role().equals("SP500_MACRO")));
            }
        }
        assertEquals(MacroState.NEUTRAL, gated.get(1).macroState());
        assertEquals(MacroState.SUPPORTIVE, gated.get(2).macroState());
    }

    @Test
    void structureOnlyPolicyBypassesAnOpposingMacroWithoutInventingSupportiveEvidence() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        observations.addAll(macroPathWithOpposingStageTwo());
        observations.addAll(postInitialFillBars());
        List<ConfirmedIntent> gated = filledIntents(observations, MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION);
        List<ConfirmedIntent> structural = filledIntents(observations, MacroGatePolicy.STRUCTURE_ONLY);
        assertEquals(List.of(1), gated.stream().map(ConfirmedIntent::stage).toList());
        assertEquals(List.of(1, 2, 3), structural.stream().map(ConfirmedIntent::stage).toList());
        ConfirmedIntent stageTwo = structural.get(1);
        assertEquals(utc(LocalDate.of(2024, 1, 4), 22), stageTwo.decisionTime());
        assertEquals(MacroState.NOT_REQUIRED, stageTwo.macroState());
        assertTrue(stageTwo.macroEligible());
        assertTrue(stageTwo.sourceEvidence().stream().noneMatch(source -> source.role().equals("SP500_MACRO")));
        assertTrue(stageTwo.pivotConfirmedAt().isBefore(stageTwo.confirmationBarStart()));
        assertEquals(3, structural.get(2).stage());
    }

    @Test
    void predecessorAnchorsCarryHashBoundSetupSeedAndInjectedReplayUnlocksOnlyOnItsOwnFill() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        observations.addAll(macroPath());
        observations.addAll(postInitialFillBars());
        List<Observation> ordered = sorted(observations);
        StageOneAnchorContext anchor = onlyStageOneAnchor(ordered);
        ObjectNode frozenIntent = LiquidationStructureRouterV1.stageOneAnchorJson(anchor);
        assertEquals(JsonHashes.ownHash(frozenIntent), frozenIntent.path("content_sha256").asText());
        assertEquals(JsonHashes.ownHash(frozenIntent.path("setup_seed")),
                frozenIntent.path("setup_seed").path("content_sha256").asText());
        assertEquals(anchor.setupSeed().recoveryTarget(), frozenIntent.path("setup_seed").path("recovery_target").asDouble());

        Router staged = Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY);
        int qualifiedBeforeAnchor = 0;
        for (Observation observation : ordered) {
            if (processingTime(observation).isAfter(anchor.intent().decisionTime())) continue;
            RouteResult result = staged.accept(observation);
            assertTrue(result.intents().isEmpty(), "anchored mode must load history without autonomous entry selection");
            qualifiedBeforeAnchor += result.qualifiedDailyStressEvents().size();
        }
        assertTrue(qualifiedBeforeAnchor > 0, "qualified daily outage events remain independent of initial-entry mode");

        RouteResult injected = staged.acceptFrozenInitialAnchor(frozenIntent);
        assertEquals(List.of(anchor.intent()), injected.intents());
        assertTrue(injected.rejections().isEmpty());
        assertEquals(0, staged.acceptFrozenInitialAnchor(frozenIntent).intents().size());
        assertEquals("FROZEN_ANCHOR_DECISION_ALREADY_SEEN",
                staged.acceptFrozenInitialAnchor(frozenIntent).rejections().get(0).reasonCode());

        List<Integer> stages = new ArrayList<>(); stages.add(1);
        ConfirmedIntent initial = injected.intents().get(0);
        staged.onFill(new FillAck(initial.intentId(), initial.setupId(), initial.asset(), 1,
                initial.requestedExecutionAfter().plusNanos(1), initial.confirmationClose(), initial.initialStop()));
        for (Observation observation : ordered) {
            if (!processingTime(observation).isAfter(anchor.intent().decisionTime())) continue;
            RouteResult result = staged.accept(observation);
            for (ConfirmedIntent intent : result.intents()) {
                stages.add(intent.stage());
                staged.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), intent.stage(),
                        intent.requestedExecutionAfter().plusNanos(1), intent.confirmationClose(), intent.initialStop()));
            }
        }
        assertEquals(List.of(1, 2, 3), stages,
                "the exact injected predecessor stage-one decision and actual fill unlock the normal later-stage router");
    }

    @Test
    void anchorInjectionRetainsOccupiedAndClosedAfterCloseZerosWithoutStateMutation() {
        List<Observation> ordered = sorted(fixture(true, OiBoundary.VALID, true));
        StageOneAnchorContext anchor = onlyStageOneAnchor(ordered);
        ObjectNode frozenIntent = LiquidationStructureRouterV1.stageOneAnchorJson(anchor);
        Router staged = Router.anchoredStaged(MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION);
        loadThroughDecision(staged, ordered, anchor.intent().decisionTime());
        assertEquals(1, staged.acceptFrozenInitialAnchor(frozenIntent).intents().size());

        ObjectNode distinctWhileBusy = rekey(frozenIntent, "busy-setup", "busy-pair", "busy-intent");
        RouteResult occupied = staged.acceptFrozenInitialAnchor(distinctWhileBusy);
        assertEquals("FROZEN_ANCHOR_ASSET_OCCUPIED", occupied.rejections().get(0).reasonCode());
        assertTrue(occupied.intents().isEmpty());

        ConfirmedIntent intent = anchor.intent();
        Instant fill = intent.requestedExecutionAfter().plusNanos(1);
        staged.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), 1, fill,
                intent.confirmationClose(), intent.initialStop()));
        staged.onClose(new CloseAck(intent.setupId(), intent.asset(), fill.plusSeconds(1), CloseReason.OTHER));
        ObjectNode laterIdentityButOldDecision = rekey(frozenIntent, "after-close-setup", "after-close-pair", "after-close-intent");
        RouteResult afterClose = staged.acceptFrozenInitialAnchor(laterIdentityButOldDecision);
        assertEquals("FROZEN_ANCHOR_NOT_AFTER_PRIOR_CLOSE", afterClose.rejections().get(0).reasonCode());
        assertTrue(afterClose.intents().isEmpty());

        ObjectNode distinctSetupAtClose = rekey(redateSameSetup(frozenIntent, fill.plusSeconds(1),
                "same-close-pair", "same-close-intent"), "same-close-setup", "same-close-pair", "same-close-intent");
        ObjectNode reopenedFilledSetup = redateSameSetup(frozenIntent, fill.plusSeconds(2),
                "same-filled-setup-later-pair", "same-filled-setup-later-intent");
        RouteResult alreadyFilled = staged.acceptFrozenInitialAnchor(reopenedFilledSetup);
        assertEquals("FROZEN_ANCHOR_SETUP_ALREADY_FILLED", alreadyFilled.rejections().get(0).reasonCode());
        assertTrue(alreadyFilled.intents().isEmpty());

        RouteResult sameClose = staged.acceptFrozenInitialAnchor(distinctSetupAtClose);
        assertEquals(1, sameClose.intents().size(),
                "exit-before-entry scheduling permits a distinct frozen setup at the close timestamp");
        assertTrue(sameClose.rejections().isEmpty());
    }

    @Test
    void anchorInjectionRejectsTamperingAndCannotRewindTheLoadedMarketClock() {
        List<Observation> ordered = sorted(fixture(true, OiBoundary.VALID, true));
        StageOneAnchorContext anchor = onlyStageOneAnchor(ordered);
        ObjectNode frozenIntent = LiquidationStructureRouterV1.stageOneAnchorJson(anchor);
        Router atDecision = Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY);
        loadThroughDecision(atDecision, ordered, anchor.intent().decisionTime());
        ObjectNode tampered = frozenIntent.deepCopy().put("initial_stop", frozenIntent.path("initial_stop").asDouble() + 1);
        assertThrows(IllegalArgumentException.class, () -> atDecision.acceptFrozenInitialAnchor(tampered));

        Router afterDecision = Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY);
        for (Observation observation : ordered) afterDecision.accept(observation);
        afterDecision.accept(bar("BTC", Timeframe.ONE_HOUR, anchor.intent().decisionTime(),
                100, 100.2, 99.8, 100, anchor.intent().decisionTime().plus(Duration.ofHours(1)), HOUR));
        assertThrows(IllegalArgumentException.class, () -> afterDecision.acceptFrozenInitialAnchor(frozenIntent));
    }

    @Test
    void anchoredNoFillDoesNotConsumeLaterFrozenHourlyPairOrSelectAnOffInventoryRetry() {
        List<Observation> ordered = sorted(fixture(true, OiBoundary.VALID, true));
        StageOneAnchorContext anchor = onlyStageOneAnchor(ordered);
        ObjectNode frozenIntent = LiquidationStructureRouterV1.stageOneAnchorJson(anchor);
        Router staged = Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY);
        loadThroughDecision(staged, ordered, anchor.intent().decisionTime());
        ConfirmedIntent initial = staged.acceptFrozenInitialAnchor(frozenIntent).intents().get(0);
        staged.onNoFill(new NoFillAck(initial.intentId(), initial.setupId(), initial.asset(), 1,
                initial.requestedExecutionAfter().plusNanos(1), "SYNTHETIC_NO_FILL"));

        Instant laterStart = anchor.intent().confirmationBarStart().plus(Duration.ofHours(1));
        Bar offInventoryTouch = bar("BTC", Timeframe.ONE_HOUR, laterStart,
                99.7, 99.8, 99.4, 99.45, laterStart.plus(Duration.ofHours(1)), HOUR);
        assertTrue(staged.accept(offInventoryTouch).intents().isEmpty(),
                "anchored runner cannot autonomously rediscover a later entry after no-fill");

        ObjectNode nextFrozenPair = redateSameSetup(frozenIntent,
                anchor.intent().decisionTime().plus(Duration.ofHours(1)), "later-hour-pair", "later-hour-intent");
        RouteResult retried = staged.acceptFrozenInitialAnchor(nextFrozenPair);
        assertEquals(1, retried.intents().size(), "a distinct pre-frozen hourly pair on the same setup remains eligible");
        assertEquals(anchor.intent().setupId(), retried.intents().get(0).setupId());
        assertEquals("later-hour-pair", retried.intents().get(0).pairId());
    }

    @Test
    void macroDeteriorationBlocksOnlyAddsAndAReconfirmationIsNeededAfterUnblock() {
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        observations.addAll(macroPathWithOpposingStageTwo());
        observations.addAll(postInitialFillBars());
        // The synthetic stage-2 touch at Jan 4 21Z is blocked; a fresh same-zone hour after
        // Jan 5's close becomes available is required before another request may be emitted.
        observations.add(bar("BTC", Timeframe.ONE_HOUR, utc(LocalDate.of(2024, 1, 8), 22),
                100, 100.1, 99.6, 99.7, utc(LocalDate.of(2024, 1, 8), 23), HOUR));
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        List<ConfirmedIntent> intents = new ArrayList<>();
        List<RejectedOpportunity> rejected = new ArrayList<>();
        for (Observation observation : sorted(observations)) {
            RouteResult result = router.accept(observation);
            intents.addAll(result.intents()); rejected.addAll(result.rejections());
            for (ConfirmedIntent intent : result.intents()) {
                router.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), intent.stage(),
                        intent.requestedExecutionAfter().plusNanos(1), intent.confirmationClose(), intent.initialStop()));
            }
        }
        assertEquals(1, intents.stream().filter(intent -> intent.stage() == 1).count());
        assertEquals(1, intents.stream().filter(intent -> intent.stage() == 2).count(),
                "the blocked stage-2 hour cannot be replayed, but a fresh post-unblock hour can confirm");
        assertTrue(intents.stream().filter(intent -> intent.stage() == 2).findFirst().orElseThrow()
                .decisionTime().isAfter(utc(LocalDate.of(2024, 1, 8), 21)));
        assertFalse(rejected.isEmpty());
        assertTrue(rejected.stream().anyMatch(value -> value.reasonCode().startsWith("MACRO_")));
    }

    static List<Observation> fixture(boolean completeCalendar, OiBoundary oiBoundary, boolean bothSidesStress) {
        List<Observation> result = new ArrayList<>();
        for (int lag = DAILY_LOOKBACK_DAYS; lag >= 1; lag--) {
            LocalDate date = STRESS_DAY.minusDays(lag);
            if (!completeCalendar && date.equals(STRESS_DAY.minusDays(37))) continue;
            Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            result.add(new DailyLiquidation("BTC", date, 1, 1, start.plus(Duration.ofHours(24)), "daily-v1"));
        }
        Instant dayStart = STRESS_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        result.add(new DailyLiquidation("BTC", STRESS_DAY, 2, bothSidesStress ? 2 : 1,
                dayStart.plus(Duration.ofHours(24)), "daily-v1"));

        Instant firstH4 = STRESS_START.minus(Duration.ofHours(4L * 180));
        Instant h4End = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        for (Instant start = firstH4; start.isBefore(h4End); start = start.plus(Duration.ofHours(4))) {
            double open = 100, high = 100.5, low = 99.5, close = 100;
            if (start.equals(STRESS_START)) { open = 100; high = 100.2; low = 96.7; close = 97; }
            if (start.equals(MODEL_AVAILABLE.minus(Duration.ofHours(4)))) { open = 98; high = 98.5; low = 96.5; close = 97; }
            if (start.equals(MODEL_AVAILABLE)) { open = 97; high = 97.5; low = 96; close = 96.8; }
            if (start.equals(MODEL_AVAILABLE.plus(Duration.ofHours(4)))) { open = 96.8; high = 97.2; low = 95.8; close = 96.5; }
            result.add(bar("BTC", Timeframe.FOUR_HOUR, start, open, high, low, close,
                    start.plus(Duration.ofHours(4)), PRICE));
        }
        addOiPair(result, STRESS_START, STRESS_START.plus(Duration.ofHours(4)), oiBoundary);

        // An apparently qualifying touch immediately after the signal timestamp must not arm
        // the route until two later completed 4h closes have confirmed the branch.
        addOneHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(2)), 100, 100.2, 99.8, 100);
        addOneHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(3)), 100, 100.2, 99.8, 100);
        // This qualifying hourly bar is after the first, but not the second, branch close.
        result.add(bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(4)),
                99.7, 99.8, 99.4, 99.45, MODEL_AVAILABLE.plus(Duration.ofHours(5)), HOUR));
        addOneHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(5)), 100, 100.2, 99.8, 100);
        addOneHour(result, MODEL_AVAILABLE.plus(Duration.ofHours(6)), 100, 100.2, 99.8, 100);
        Bar prior = bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(7)),
                100, 100.2, 99.8, 100, MODEL_AVAILABLE.plus(Duration.ofHours(8)), HOUR);
        result.add(prior);
        // Branch arms at t+8h. This bar begins at arm time; its previous-hour low is 99.8.
        Bar confirmed = bar("BTC", Timeframe.ONE_HOUR, MODEL_AVAILABLE.plus(Duration.ofHours(8)),
                99.7, 99.8, 99.4, 99.45, MODEL_AVAILABLE.plus(Duration.ofHours(9)), HOUR);
        result.add(confirmed);
        return result;
    }

    private static List<ConfirmedIntent> filledIntents(List<Observation> observations, MacroGatePolicy policy) {
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION, policy);
        List<ConfirmedIntent> result = new ArrayList<>();
        for (Observation observation : sorted(observations)) {
            RouteResult route = router.accept(observation);
            result.addAll(route.intents());
            for (ConfirmedIntent intent : route.intents()) {
                router.onFill(new FillAck(intent.intentId(), intent.setupId(), intent.asset(), intent.stage(),
                        intent.requestedExecutionAfter().plusNanos(1), intent.confirmationClose(), intent.initialStop()));
            }
        }
        return List.copyOf(result);
    }

    private static void addOiPair(List<Observation> out, Instant start, Instant end, OiBoundary mode) {
        Duration observedLag = switch (mode) {
            case TOO_FRESH -> Duration.ofMinutes(4);
            case STALE -> Duration.ofMinutes(11);
            default -> Duration.ofMinutes(7);
        };
        Instant startObserved = start.minus(observedLag);
        Instant endObserved = end.minus(observedLag);
        Instant startAvailable = mode == OiBoundary.AVAILABLE_AT_ENDPOINT ? start : startObserved.plusSeconds(1);
        Instant endAvailable = mode == OiBoundary.AVAILABLE_AT_ENDPOINT ? end : endObserved.plusSeconds(1);
        out.add(new OpenInterest("BTC", startObserved, startAvailable, 100, OI));
        out.add(new OpenInterest("BTC", endObserved, endAvailable, 90, OI));
    }

    private static List<Observation> postInitialFillBars() {
        List<Observation> result = new ArrayList<>();
        double[][] h4 = {
                {96, 99.4, 94, 97}, {97, 99.5, 94.5, 98}, {98, 99.8, 94, 99},
                {99, 99.5, 93.5, 99.2}, {99.2, 99.4, 92.5, 99.3}
        };
        Instant firstStart = MODEL_AVAILABLE.plus(Duration.ofHours(8));
        for (int i = 0; i < h4.length; i++) {
            double[] v = h4[i];
            Instant start = firstStart.plus(Duration.ofHours(4L * i));
            result.add(bar("BTC", Timeframe.FOUR_HOUR, start, v[0], v[1], v[2], v[3],
                    start.plus(Duration.ofHours(4)), PRICE));
        }
        Instant favorableAfterPivot = firstStart.plus(Duration.ofHours(20));
        result.add(bar("BTC", Timeframe.FOUR_HOUR, favorableAfterPivot,
                99.3, 99.6, 98.5, 98.9, favorableAfterPivot.plus(Duration.ofHours(4)), PRICE));
        // A geometrically valid H1 signal starts before both the pivot and favorable-bar
        // prerequisites are available. It must not create a stage-2 request retroactively.
        addOneHour(result, utc(LocalDate.of(2024, 1, 4), 6), 100, 100.1, 99.9, 100);
        result.add(bar("BTC", Timeframe.ONE_HOUR, utc(LocalDate.of(2024, 1, 4), 7),
                99.9, 100, 99.5, 99.6, utc(LocalDate.of(2024, 1, 4), 8), HOUR));
        double[][] afterStageTwo = {
                {93, 98.9, 91.5, 92}, {92, 99, 91, 91}, {91, 100.2, 90, 90},
                {90, 99.5, 89, 89}, {89, 99, 88, 88}
        };
        Instant secondStart = utc(STRESS_DAY.plusDays(4), 0);
        for (int i = 0; i < afterStageTwo.length; i++) {
            double[] v = afterStageTwo[i];
            Instant start = secondStart.plus(Duration.ofHours(4L * i));
            result.add(bar("BTC", Timeframe.FOUR_HOUR, start, v[0], v[1], v[2], v[3],
                    start.plus(Duration.ofHours(4)), PRICE));
        }
        // First staged confirmation is neutral; it arrives after the pivot was known.
        addOneHour(result, utc(LocalDate.of(2024, 1, 4), 20), 100, 100.1, 99.95, 100);
        result.add(bar("BTC", Timeframe.ONE_HOUR, utc(LocalDate.of(2024, 1, 4), 21),
                100, 100.1, 99.6, 99.7, utc(LocalDate.of(2024, 1, 4), 22), HOUR));
        // The second pivot becomes available on Jan 5 at 20Z; stage 3 awaits fresh support.
        addOneHour(result, utc(LocalDate.of(2024, 1, 8), 20), 100.4, 100.5, 100.0, 100.3);
        result.add(bar("BTC", Timeframe.ONE_HOUR, utc(LocalDate.of(2024, 1, 8), 21),
                100.2, 100.3, 99.8, 99.9, utc(LocalDate.of(2024, 1, 8), 22), HOUR));
        return result;
    }

    private static List<Observation> macroPath() {
        return macroPath(false);
    }

    private static List<Observation> macroPathWithOpposingStageTwo() {
        return macroPath(true);
    }

    private static List<Observation> macroPath(boolean opposing) {
        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate[] dates = {LocalDate.of(2023, 12, 26), LocalDate.of(2023, 12, 27), LocalDate.of(2023, 12, 28),
                LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3),
                LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 5)};
        double[] values = opposing
                ? new double[] {100, 100.5, 101, 101, 101, 101, 101, 100}
                : new double[] {100, 100, 100, 100, 100, 99.8, 99.5, 99};
        List<Observation> result = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            LocalDate date = dates[i];
            LocalDate next = nextTradingDate(date);
            Instant close = date.atTime(16, 0).atZone(ny).toInstant();
            Instant available = next.atTime(16, 0).atZone(ny).toInstant();
            result.add(new MacroClose(close, available, values[i], true, "sp500-v1"));
        }
        return result;
    }

    private static MacroState stageTwoMacroState(double jan3Close) {
        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate[] dates = {LocalDate.of(2023, 12, 26), LocalDate.of(2023, 12, 27),
                LocalDate.of(2023, 12, 28), LocalDate.of(2023, 12, 29),
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)};
        List<Observation> observations = fixture(true, OiBoundary.VALID, true);
        double[] values = {100, 100, 100, 100, 100, jan3Close};
        for (int i = 0; i < dates.length; i++) {
            LocalDate next = nextTradingDate(dates[i]);
            observations.add(new MacroClose(dates[i].atTime(16, 0).atZone(ny).toInstant(),
                    next.atTime(16, 0).atZone(ny).toInstant(), values[i], true, "sp500-threshold"));
        }
        observations.addAll(postInitialFillBars());
        Instant cutoff = utc(LocalDate.of(2024, 1, 4), 22);
        Router router = new Router(Variant.ROUTED_REVERSAL_CONTINUATION);
        MacroState found = null;
        for (Observation observation : sorted(observations)) {
            if (processingTime(observation).isAfter(cutoff)) continue;
            RouteResult result = router.accept(observation);
            if (!result.intents().isEmpty()) {
                for (ConfirmedIntent intent : result.intents()) {
                    if (intent.stage() == 1) router.onFill(new FillAck(intent.intentId(), intent.setupId(),
                            intent.asset(), 1, intent.requestedExecutionAfter().plusNanos(1),
                            intent.confirmationClose(), intent.initialStop()));
                    else found = intent.macroState();
                }
            }
            if (!result.rejections().isEmpty() && result.rejections().get(0).stage() == 2) {
                found = result.rejections().get(0).macroState();
            }
        }
        return found;
    }

    private static LocalDate nextTradingDate(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (next.getDayOfWeek().getValue() > 5 || next.equals(LocalDate.of(2024, 1, 1))) next = next.plusDays(1);
        return next;
    }

    private static void addOneHour(List<Observation> out, Instant start, double open, double high, double low, double close) {
        out.add(bar("BTC", Timeframe.ONE_HOUR, start, open, high, low, close,
                start.plus(Duration.ofHours(1)), HOUR));
    }

    private static Bar bar(String asset, Timeframe timeframe, Instant start,
            double open, double high, double low, double close, Instant available, String series) {
        return new Bar(asset, timeframe, start, available, open, high, low, close, series);
    }

    private static Instant utc(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
    }

    private static MacroClose macro(Instant close, Instant available) {
        return new MacroClose(close, available, 100, true, "sp500-test");
    }

    private static List<Observation> sorted(List<Observation> observations) {
        return observations.stream().sorted(Comparator
                .comparing(LiquidationStructureRouterV1Test::processingTime)
                .thenComparing(Observation::eventTime)
                .thenComparingInt(LiquidationStructureRouterV1Test::priority)
                .thenComparing(Observation::asset)
                .thenComparing(Observation::seriesId)).toList();
    }

    private static Instant processingTime(Observation observation) {
        return observation instanceof DailyLiquidation daily ? daily.modeledAvailableAt() : observation.availableAt();
    }

    private static int priority(Observation observation) {
        if (observation instanceof DailyLiquidation) return 0;
        if (observation instanceof MacroClose) return 1;
        if (observation instanceof OpenInterest) return 2;
        return ((Bar) observation).timeframe() == Timeframe.FOUR_HOUR ? 3 : 4;
    }

    private static List<ConfirmedIntent> intents(Variant variant, List<Observation> observations) {
        return Router.replay(variant, observations).stream().flatMap(result -> result.intents().stream()).toList();
    }

    private static List<QualifiedDailyStressEvent> qualifiedEvents(Variant variant, List<Observation> observations) {
        return Router.replay(variant, observations).stream()
                .flatMap(result -> result.qualifiedDailyStressEvents().stream()).toList();
    }

    private static ConfirmedIntent onlyIntent(Variant variant, List<Observation> observations) {
        List<ConfirmedIntent> intents = intents(variant, observations);
        assertEquals(1, intents.size(), "one confirmed intent expected for " + variant + ", got " + intents);
        return intents.get(0);
    }

    private static PairedDecisionContext onlyPairedContext(List<Observation> observations) {
        List<PairedDecisionContext> contexts = Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION,
                observations).stream().flatMap(result -> result.pairedDecisionContexts().stream()).toList();
        assertEquals(1, contexts.size(), "one paired decision context expected, got " + contexts);
        return contexts.get(0);
    }

    private static StageOneAnchorContext onlyStageOneAnchor(List<Observation> observations) {
        List<StageOneAnchorContext> contexts = Router.replay(Variant.ROUTED_REVERSAL_CONTINUATION,
                observations).stream().flatMap(result -> result.stageOneAnchorContexts().stream()).toList();
        assertEquals(1, contexts.size(), "one routed stage-one anchor seed expected");
        return contexts.get(0);
    }

    private static void loadThroughDecision(Router router, List<Observation> observations, Instant decision) {
        for (Observation observation : observations) {
            if (!processingTime(observation).isAfter(decision)) router.accept(observation);
        }
    }

    private static ObjectNode rekey(ObjectNode source, String setupId, String pairId, String intentId) {
        ObjectNode result = source.deepCopy();
        result.put("setup_id", setupId).put("pair_id", pairId).put("intent_id", intentId);
        ObjectNode seed = (ObjectNode) result.path("setup_seed");
        seed.remove("content_sha256"); seed.put("setup_id", setupId); seed.put("content_sha256", JsonHashes.ownHash(seed));
        result.remove("content_sha256"); result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode redateSameSetup(ObjectNode source, Instant decision, String pairId, String intentId) {
        ObjectNode result = source.deepCopy();
        result.put("pair_id", pairId).put("intent_id", intentId)
                .put("decision_time", decision.toString()).put("requested_execution_after", decision.plusNanos(1).toString())
                .put("confirmation_bar_start", decision.minus(Duration.ofHours(1)).toString());
        for (var sourceEvidence : (com.fasterxml.jackson.databind.node.ArrayNode) result.path("source_evidence")) {
            if ("CONFIRMATION".equals(sourceEvidence.path("role").asText())) {
                ((ObjectNode) sourceEvidence).put("event_time", decision.toString())
                        .put("available_at", decision.toString());
            }
        }
        result.remove("content_sha256"); result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    enum OiBoundary { VALID, TOO_FRESH, STALE, AVAILABLE_AT_ENDPOINT }
}
