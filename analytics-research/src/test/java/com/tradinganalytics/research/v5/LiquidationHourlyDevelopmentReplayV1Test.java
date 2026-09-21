package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationHourlyDevelopmentReplayV1Test {
    private static final long HOUR_MS = 3_600_000L;
    private static final long T = 1_700_000_000_000L;

    @Test
    void ninetyPriorDailyObservationsQualifyTheNextRowWithoutAnExtraWarmup() {
        LocalDate first = LocalDate.of(2024, 1, 1);
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>();
        for (int day = 0; day < 91; day++) dates.add(first.plusDays(day));

        long qualified = dates.stream().filter(day -> LiquidationHourlyDevelopmentReplayV1
                .hasCompletePrior90DayWindow(day, dates)).count();

        assertEquals(1, qualified);
        dates.remove(first.plusDays(40));
        assertFalse(LiquidationHourlyDevelopmentReplayV1.hasCompletePrior90DayWindow(first.plusDays(90), dates));
    }

    @Test
    void fourHourlyBarsCreateOneCompletedFourHourFeatureWhileExecutionBarsRemainHourly() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        List<LiquidationHourlyDevelopmentReplayV1.HourBar> hourly = List.of(
                bar("BTC", start, 100, 105, 98, 102, 10),
                bar("BTC", start.plusMillis(HOUR_MS), 102, 108, 101, 104, 11),
                bar("BTC", start.plusMillis(2 * HOUR_MS), 104, 106, 99, 100, 12),
                bar("BTC", start.plusMillis(3 * HOUR_MS), 100, 103, 97, 101, 13));

        List<LiquidationHourlyDevelopmentReplayV1.HourBar> fourHour = LiquidationHourlyDevelopmentReplayV1
                .aggregateFourHour("BTC", hourly);

        assertEquals(1, fourHour.size());
        assertEquals(start, fourHour.get(0).start);
        assertEquals(100, fourHour.get(0).open, 0);
        assertEquals(108, fourHour.get(0).high, 0);
        assertEquals(97, fourHour.get(0).low, 0);
        assertEquals(101, fourHour.get(0).close, 0);
        assertEquals(46, fourHour.get(0).volume, 0);
        assertEquals(4, hourly.size(), "execution inputs remain the original four H1 bars");
    }

    @Test
    void firstEntryUsesOnlyTheExactOneHourOpenAndNeverDefersToALaterBar() {
        Instant decision = Instant.parse("2024-01-01T12:00:00Z");
        Instant scheduled = decision.plusMillis(HOUR_MS);
        LiquidationStructureRouterV1.ConfirmedIntent intent = intent(decision);
        LiquidationHourlyDevelopmentReplayV1.HourBar later = bar("BTC", scheduled.plusMillis(HOUR_MS), 101, 102, 100, 101, 1);
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> bars = new HashMap<>();
        bars.put("BTC|" + later.start.toEpochMilli(), later);
        LiquidationHourlyDevelopmentReplayV1.Input input = input(bars);

        assertNull(LiquidationHourlyDevelopmentReplayV1.firstHourlyOpenAfter(intent, decision, input),
                "a missing scheduled open cancels the entry instead of filling on the next available hour");
        assertNull(LiquidationHourlyDevelopmentReplayV1.firstHourlyOpenAfter(intent, scheduled.plusMillis(1), input),
                "an observation arriving after the scheduled open cannot be retroactively filled");
        bars.put("BTC|" + scheduled.toEpochMilli(), bar("BTC", scheduled, 100, 102, 99, 101, 1));
        input = input(bars);
        assertEquals(scheduled, LiquidationHourlyDevelopmentReplayV1.firstHourlyOpenAfter(intent, decision, input).start);
    }

    @Test
    void archivedRoundTripsAndCurrentPositionReconcileToMarkedEquityAfterAllCosts() {
        ObjectNode ledger = LiquidationPortfolioAccountingV1.replayFixture(accountWithThreeEpisodes());
        ObjectNode reconciliation = LiquidationHourlyDevelopmentReplayV1.pnlReconciliation(ledger);

        assertEquals("RECONCILED_EX_FUNDING", reconciliation.path("status").asText());
        assertEquals(3, reconciliation.path("deduplicated_position_count").asInt());
        assertEquals(2, reconciliation.path("closed_position_count").asInt());
        assertEquals(1, reconciliation.path("open_position_count").asInt());
        assertEquals(0, Double.parseDouble(reconciliation.path("difference_usdt").asText()), 1e-8);
        assertEquals(0, ledger.path("reconciliation_delta_usdt").asDouble(), 1e-8);

        ledger.put("funding_pnl_usdt", 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LiquidationHourlyDevelopmentReplayV1.pnlReconciliation(ledger));
    }

    @Test
    void hourlyAccountBoundaryAcceptsExplicitHourlyRowsButProductionStillRejectsThem() {
        ObjectNode request = hourlyAccount();
        var hourly = LiquidationPortfolioAccountingV1.startHourlyDevelopmentSession(request);
        ObjectNode add = addEvent("hourly-setup", "hourly-intent", T);
        ObjectNode result = hourly.accept(add);
        assertEquals("ADD_FILLED", result.path("type").asText());
        assertEquals(90, hourly.activeStopPrice("BTC"), 0);
        ObjectNode hourlyBar = JsonHashes.mapper().createObjectNode().put("type", "BAR_PRE").put("asset", "BTC")
                .put("time", T + HOUR_MS).put("bar_start_time", T + HOUR_MS).put("bar_duration_ms", HOUR_MS)
                .put("trade_open", 100).put("trade_high", 101).put("trade_low", 99).put("trade_close", 100)
                .put("mark_open", 100).put("mark_high", 101).put("mark_low", 99).put("mark_close", 100);
        assertEquals("BAR_OPEN_PROCESSED", hourly.accept(hourlyBar).path("type").asText());

        ObjectNode publicRequest = accountWithThreeEpisodes();
        ObjectNode row = publicRequest.withArray("events").addObject().put("type", "BAR_PRE").put("asset", "BTC")
                .put("time", T + HOUR_MS).put("bar_start_time", T + HOUR_MS).put("bar_duration_ms", "3600000")
                .put("trade_open", 100).put("trade_high", 101).put("trade_low", 99).put("trade_close", 100)
                .put("mark_open", 100).put("mark_high", 101).put("mark_low", 99).put("mark_close", 100);
        assertTrue(row.path("bar_duration_ms").isTextual());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(publicRequest));
    }

    @Test
    void validatesSpringBootOuterJarAndItsBundledResearchClassBytes(@TempDir Path directory) throws IOException {
        String classResource = LiquidationHourlyDevelopmentReplayV1.class.getName().replace('.', '/') + ".class";
        byte[] loadedBytes;
        try (var stream = LiquidationHourlyDevelopmentReplayV1.class.getResourceAsStream("LiquidationHourlyDevelopmentReplayV1.class")) {
            loadedBytes = stream.readAllBytes();
        }
        ByteArrayOutputStream nestedBytes = new ByteArrayOutputStream();
        try (JarOutputStream nested = new JarOutputStream(nestedBytes)) {
            nested.putNextEntry(new JarEntry(classResource));
            nested.write(loadedBytes);
            nested.closeEntry();
        }
        Path executable = directory.resolve("analytics-cli-exec.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        try (JarOutputStream outer = new JarOutputStream(Files.newOutputStream(executable), manifest)) {
            outer.putNextEntry(new JarEntry("BOOT-INF/lib/analytics-research-test.jar"));
            outer.write(nestedBytes.toByteArray());
            outer.closeEntry();
        }

        String jarPath = executable.toAbsolutePath().toString();
        assertEquals(executable.toAbsolutePath(), LiquidationHourlyDevelopmentReplayV1
                .resolveExecutableJar(jarPath, jarPath + " strategy-research-v5 liquidation-hourly-run", loadedBytes));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LiquidationHourlyDevelopmentReplayV1.resolveExecutableJar(jarPath, "mutable-class-dir Main", loadedBytes));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LiquidationHourlyDevelopmentReplayV1.resolveExecutableJar(jarPath, jarPath + " Main", new byte[] {1, 2, 3}));
    }

    @Test
    void loaderKeepsAllDailyWarmupRowsAndSeparatesH1ExecutionFromH4Features(@TempDir Path directory) throws Exception {
        LiquidationHourlyDevelopmentReplayV1.Input input = loadInput(writeSmallInputFixture(directory));

        assertEquals(364, input.dailyRows);
        assertEquals(4, input.validDailyWindows);
        assertEquals(4, input.longStressFlags);
        assertEquals(0, input.shortStressFlags);
        assertEquals(0, input.missingOiEndpoints);
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            assertEquals(8, input.barsByAsset.get(asset).size());
            assertEquals(2, input.coverage.path("executor_oi_endpoints").path(asset).path("usable_oi_snapshots").asInt());
        }
        assertEquals(32, input.barsByAssetAndStart.size(), "only 32 H1 execution bars belong in the execution map");
        assertEquals(8, input.features.stream().filter(row -> row instanceof LiquidationStructureRouterV1.Bar bar
                && bar.timeframe() == LiquidationStructureRouterV1.Timeframe.FOUR_HOUR).count());
        assertEquals(8, input.features.stream().filter(row -> row instanceof LiquidationStructureRouterV1.OpenInterest).count());
        assertTrue(input.preflightBlockers.stream().anyMatch(row -> row.contains("hourly bar count differs")));
        assertTrue(input.preflightBlockers.stream().noneMatch(row -> row.contains("hourly price gaps")));
    }

    @Test
    void preflightWritesCoverageOnlyAndNeverComputesHistoricalOutcomes(@TempDir Path directory) throws Exception {
        Path manifest = writeSmallInputFixture(directory);
        Path outputPath = directory.resolve("preflight.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("input", manifest.toString()).put("out", outputPath.toString());

        ObjectNode output = LiquidationHourlyDevelopmentReplayV1.preflight(options);

        assertFalse(output.path("historical_outcomes_computed").asBoolean());
        assertEquals("liquidation-exploratory-input-preflight/1", output.path("schema").asText());
        assertTrue(Files.isRegularFile(outputPath));
        JsonNode disk = JsonHashes.mapper().readTree(Files.readString(outputPath));
        assertEquals(output.path("content_sha256").asText(), disk.path("content_sha256").asText());
    }

    @Test
    void syntheticInputRunsAllThreeVariantsAndFixedDoubledCostSensitivityWithoutMarketOutcomes(@TempDir Path directory) throws Exception {
        LiquidationHourlyDevelopmentReplayV1.Input input = loadInput(writeSmallInputFixture(directory));
        Path policyPath = findRepositoryRoot().resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json");
        ObjectNode policy = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(policyPath));
        var method = LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethod("replay",
                LiquidationHourlyDevelopmentReplayV1.Input.class, ObjectNode.class);
        method.setAccessible(true);

        ObjectNode result;
        try { result = (ObjectNode) method.invoke(null, input, policy); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }

        assertEquals("DEVELOPMENT_ONLY_EXPLORATORY", result.path("status").asText());
        assertFalse(result.path("authoritative").asBoolean());
        assertEquals(3, result.path("variants").size());
        assertEquals(3, result.path("cost_sensitivity_doubled_fees_slippage").size());
        for (String variant : List.of("CORE_ONE_ENTRY", "STAGED_NO_MACRO", "STAGED_MACRO")) {
            assertEquals("RECONCILED_EX_FUNDING", result.path("variants").path(variant)
                    .path("pnl_reconciliation").path("status").asText());
            assertEquals("DEVELOPMENT_ONLY", result.path("cost_sensitivity_doubled_fees_slippage")
                    .path(variant).path("status").asText());
        }
    }

    @Test
    void syntheticRouterIntentExecutesAtExactNextHourlyOpenAndClosesOnStop() throws Exception {
        List<LiquidationStructureRouterV1.Observation> features = routedStressFeatures();
        LiquidationStructureRouterV1.Router router = new LiquidationStructureRouterV1.Router(
                LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY);
        LiquidationStructureRouterV1.ConfirmedIntent firstIntent = null;
        for (LiquidationStructureRouterV1.Observation row : sortedRouterFeatures(features)) {
            LiquidationStructureRouterV1.RouteResult routed = router.accept(row);
            firstIntent = routed.intents().stream().filter(intent -> intent.stage() == 1).findFirst().orElse(firstIntent);
        }
        assertNotNull(firstIntent, "synthetic liquidation/OI/price structure should produce one routed stage-one intent");

        LiquidationHourlyDevelopmentReplayV1.Input input = hourlyInputForIntent(features, firstIntent);
        ObjectNode policy = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(findRepositoryRoot()
                .resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json")));
        var method = LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethod("replay",
                LiquidationHourlyDevelopmentReplayV1.Input.class, ObjectNode.class);
        method.setAccessible(true);
        ObjectNode result;
        try { result = (ObjectNode) method.invoke(null, input, policy); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }

        JsonNode noMacro = result.path("variants").path("STAGED_NO_MACRO");
        assertTrue(noMacro.path("funnel_counts").path("submitted_fill_attempts").asInt() > 0,
                () -> "expected an eligible fill request: " + noMacro.path("funnel_counts"));
        assertTrue(noMacro.path("funnel_counts").path("filled_tranches").asInt() > 0,
                () -> "expected exact next-hour fill: " + noMacro.path("funnel_counts"));
        assertTrue(noMacro.path("funnel_counts").path("completed_positions").asInt() > 0,
                () -> "the synthetic stop touch should close the filled position: " + noMacro.path("funnel_counts"));
        assertEquals("RECONCILED_EX_FUNDING", noMacro.path("pnl_reconciliation").path("status").asText());
    }

    @Test
    void hourlyFillAdapterRejectsMissingCapacityChaseOccupiedAndAccountCapacityCases() throws Exception {
        ObjectNode missingPrior = directFillAttempt(false, 100_000, null, false);
        ObjectNode excessiveChase = directFillAttempt(true, 100_000, "CHASE", false);
        ObjectNode occupied = directFillAttempt(true, 100_000, null, true);
        ObjectNode accountCapacity = directFillAttempt(true, 0, null, false);

        assertEquals(1, missingPrior.path("funnel").path("fill_rejections").asInt());
        assertEquals(1, excessiveChase.path("funnel").path("fill_rejections").asInt());
        assertEquals(1, occupied.path("funnel").path("fill_rejections").asInt());
        assertEquals(1, accountCapacity.path("funnel").path("fill_rejections").asInt());
        assertFalse(missingPrior.path("account").path("positions").get(0).path("quantity").decimalValue().signum() > 0);
        assertFalse(excessiveChase.path("account").path("positions").get(0).path("quantity").decimalValue().signum() > 0);
        assertTrue(occupied.path("account").path("positions").get(0).path("quantity").decimalValue().signum() > 0,
                "the rejected stage-one signal must leave the pre-existing position intact");
        assertFalse(accountCapacity.path("account").path("positions").get(0).path("quantity").decimalValue().signum() > 0);
    }

    @Test
    void openStageOnePositionExitsAtTheFirstHourlyOpenExactlySixtyDaysAfterItsFill() throws Exception {
        List<LiquidationStructureRouterV1.Observation> allFeatures = routedStressFeatures();
        LiquidationStructureRouterV1.ConfirmedIntent stageOne = firstStageOneIntent(allFeatures);
        Instant decision = stageOne.decisionTime();
        List<LiquidationStructureRouterV1.Observation> throughDecision = allFeatures.stream()
                .filter(feature -> processingTime(feature).compareTo(decision) <= 0).toList();
        LiquidationHourlyDevelopmentReplayV1.Input input = hourlyInputForIntent(throughDecision, stageOne);

        Instant fillAt = decision.plusMillis(HOUR_MS);
        input = addSafeExecutionBar(input, stageOne, fillAt);
        Instant deadline = fillAt.plus(Duration.ofDays(60));
        double deadlinePrice = stageOne.reversalTarget() == null ? stageOne.zoneCenter()
                : (stageOne.zoneCenter() + stageOne.reversalTarget()) / 2.0;
        input = addSafeExecutionBar(input, stageOne, deadline, deadlinePrice);
        input.coverage.put("analysis_coverage_end_exclusive", deadline.plusMillis(HOUR_MS).toString());

        ObjectNode policy = frozenPolicy();
        ObjectNode result = replaySynthetic(input, policy);
        JsonNode noMacro = result.path("variants").path("STAGED_NO_MACRO");
        assertEquals(1, noMacro.path("funnel_counts").path("filled_tranches").asInt());
        assertEquals(1, noMacro.path("funnel_counts").path("completed_positions").asInt());
        JsonNode btc = java.util.stream.StreamSupport.stream(noMacro.path("positions").spliterator(), false)
                .filter(position -> "BTC".equals(position.path("asset").asText())).findFirst().orElseThrow();
        assertEquals("CLOSED", btc.path("status").asText());
        JsonNode exits = btc.path("exits");
        assertEquals(1, exits.size());
        assertEquals("SIXTY_DAY_LIFECYCLE", exits.get(0).path("reason").asText());
        assertEquals(fillAt.plus(Duration.ofDays(60)).toEpochMilli(), exits.get(0).path("time").asLong());
    }

    @Test
    void stagedExecutorAddsConfirmedTranchesAndAppliesRouterStopRatchets() throws Exception {
        List<LiquidationStructureRouterV1.Observation> features = completeStagedRouterFixture();
        List<LiquidationStructureRouterV1.ConfirmedIntent> intents = discoverStagedIntents(features);
        assertEquals(List.of(1, 2, 3), intents.stream().map(LiquidationStructureRouterV1.ConfirmedIntent::stage).toList());

        LiquidationStructureRouterV1.ConfirmedIntent stageOne = intents.get(0);
        LiquidationStructureRouterV1.ConfirmedIntent stageTwo = intents.get(1);
        LiquidationStructureRouterV1.Router router = routerAwaitingStageTwo(features, stageOne, stageTwo);
        LiquidationHourlyDevelopmentReplayV1.Input input = stagedExecutionInput(features, intents);
        LiquidationPortfolioAccountingV1.AccountSession account = LiquidationPortfolioAccountingV1
                .startHourlyDevelopmentSession(hourlyAccount());
        Instant stageOneFill = stageOne.decisionTime().plusMillis(HOUR_MS);
        ObjectNode firstFill = addEventFromIntent(stageOne, stageOneFill);
        assertEquals("ADD_FILLED", account.accept(firstFill).path("type").asText());
        Map<String, Object> pending = new HashMap<>();
        Map<String, Instant> firstFills = new HashMap<>(Map.of(stageOne.asset(), stageOneFill));
        ArrayNode rejections = JsonHashes.mapper().createArrayNode();
        Object funnel = newFunnel();
        LiquidationStructureRouterV1.RouteResult stageTwoRoute = new LiquidationStructureRouterV1.RouteResult(
                List.of(stageTwo), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        invokeProcessRouteResult(stageTwoRoute, router, account, pending, rejections, stageTwo.decisionTime(), input, funnel,
                "2022-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
        Object pendingFill = pending.get(stageTwo.intentId());
        assertNotNull(pendingFill, "the confirmed stage-two intent should be scheduled at its exact hourly open");
        Instant stageTwoFill = stageTwo.decisionTime().plusMillis(HOUR_MS);
        invokeExecuteFill(pendingFill, input.barsByStart.getOrDefault(stageTwoFill, List.of()), input,
                "STAGED_NO_MACRO", account, router, pending, firstFills, funnel);

        double priorStop = account.activeStopPrice(stageTwo.asset());
        double ratchetedStop = stageTwo.direction() == LiquidationStructureRouterV1.Direction.SHORT
                ? priorStop - 0.01 : priorStop + 0.01;
        Instant stopAt = stageTwoFill.plusMillis(HOUR_MS);
        LiquidationStructureRouterV1.StopUpdateIntent update = new LiquidationStructureRouterV1.StopUpdateIntent(
                "fixture-stop-ratchet", stageTwo.setupId(), stageTwo.asset(), stopAt, priorStop, ratchetedStop,
                stageTwo.direction(), true, List.of());
        LiquidationStructureRouterV1.RouteResult stopRoute = new LiquidationStructureRouterV1.RouteResult(
                List.of(), List.of(), List.of(update), List.of(), List.of(), List.of(), List.of());
        invokeProcessRouteResult(stopRoute, router, account, pending, rejections, stopAt, input, funnel,
                "2022-01-01T00:00:00Z", "2025-01-01T00:00:00Z");

        ObjectNode accountSnapshot = account.snapshot();
        ObjectNode funnelSnapshot = (ObjectNode) invokeFunnelJson(funnel);
        assertEquals(1, funnelSnapshot.path("filled_tranches").asInt(),
                () -> "the staged adapter should fill stage two: " + funnelSnapshot);
        JsonNode position = java.util.stream.StreamSupport.stream(accountSnapshot.path("positions").spliterator(), false)
                .filter(row -> "BTC".equals(row.path("asset").asText())).findFirst().orElseThrow();
        assertEquals("OPEN", position.path("status").asText());
        assertEquals(3, position.path("next_stage").asInt());
        assertEquals(2, position.path("fills").size());
        assertEquals(ratchetedStop, position.path("common_stop").asDouble(), 1e-8,
                "the staged adapter should apply the tighter stop and acknowledge it to the router");
        assertEquals("RECONCILED_EX_FUNDING", LiquidationHourlyDevelopmentReplayV1.pnlReconciliation(accountSnapshot)
                .path("status").asText());
    }

    private static LiquidationStructureRouterV1.ConfirmedIntent firstStageOneIntent(
            List<LiquidationStructureRouterV1.Observation> features) {
        LiquidationStructureRouterV1.Router router = new LiquidationStructureRouterV1.Router(
                LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY);
        for (LiquidationStructureRouterV1.Observation feature : sortedRouterFeatures(features)) {
            LiquidationStructureRouterV1.RouteResult route = router.accept(feature);
            var candidate = route.intents().stream().filter(intent -> intent.stage() == 1).findFirst();
            if (candidate.isPresent()) return candidate.get();
        }
        throw new AssertionError("synthetic fixture did not produce a stage-one intent");
    }

    private static Instant processingTime(LiquidationStructureRouterV1.Observation feature) {
        return feature instanceof LiquidationStructureRouterV1.DailyLiquidation daily
                ? daily.modeledAvailableAt() : feature.availableAt();
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input addSafeExecutionBar(LiquidationHourlyDevelopmentReplayV1.Input input,
            LiquidationStructureRouterV1.ConfirmedIntent intent, Instant start) {
        return addSafeExecutionBar(input, intent, start, intent.zoneCenter());
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input addSafeExecutionBar(LiquidationHourlyDevelopmentReplayV1.Input input,
            LiquidationStructureRouterV1.ConfirmedIntent intent, Instant start, double open) {
        Map<String, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> barsByAsset = new HashMap<>();
        input.barsByAsset.forEach((asset, bars) -> barsByAsset.put(asset, new ArrayList<>(bars)));
        TreeMap<Instant, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> barsByStart = new TreeMap<>();
        input.barsByStart.forEach((time, bars) -> barsByStart.put(time, new ArrayList<>(bars)));
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> barsByAssetStart = new HashMap<>(input.barsByAssetStart);
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> barsByAssetAndStart = new HashMap<>(input.barsByAssetAndStart);
        Map<String, NavigableMap<Instant, Double>> pricesByAsset = new HashMap<>();
        input.pricesByAssetCloseTime.forEach((asset, prices) -> pricesByAsset.put(asset, new TreeMap<>(prices)));
        double high = intent.direction() == LiquidationStructureRouterV1.Direction.SHORT
                ? Math.min(open + 0.01, intent.initialStop() - 0.01) : open + 0.01;
        double low = intent.direction() == LiquidationStructureRouterV1.Direction.LONG
                ? Math.max(open - 0.01, intent.initialStop() + 0.01) : open - 0.01;
        if (intent.reversalTarget() != null) {
            if (intent.direction() == LiquidationStructureRouterV1.Direction.LONG) high = Math.min(high, intent.reversalTarget() - 0.01);
            else low = Math.max(low, intent.reversalTarget() + 0.01);
        }
        LiquidationHourlyDevelopmentReplayV1.HourBar bar = new LiquidationHourlyDevelopmentReplayV1.HourBar(
                intent.asset(), start, open, high, low, open, 100_000);
        List<LiquidationHourlyDevelopmentReplayV1.HourBar> perAsset = barsByAsset.get(intent.asset());
        perAsset.removeIf(existing -> existing.start.equals(start));
        perAsset.add(bar);
        perAsset.sort(Comparator.comparing(existing -> existing.start));
        String key = intent.asset() + "|" + start.toEpochMilli();
        barsByAssetStart.put(key, bar);
        barsByAssetAndStart.put(key, bar);
        List<LiquidationHourlyDevelopmentReplayV1.HourBar> sameStart = barsByStart.computeIfAbsent(start,
                ignored -> new ArrayList<>());
        sameStart.removeIf(existing -> existing.asset.equals(intent.asset()));
        sameStart.add(bar);
        pricesByAsset.get(intent.asset()).put(start.plusMillis(HOUR_MS), open);
        return new LiquidationHourlyDevelopmentReplayV1.Input(input.features, barsByAsset, barsByStart,
                barsByAssetStart, barsByAssetAndStart, pricesByAsset, input.coverage.deepCopy(), input.fileHashes,
                input.preflightBlockers, input.dailyRows, input.validDailyWindows, input.longStressFlags,
                input.shortStressFlags, input.missingOiEndpoints, input.manifestByteSha256, input.manifestPath);
    }

    private static ObjectNode frozenPolicy() throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(findRepositoryRoot()
                .resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json")));
    }

    private static List<LiquidationStructureRouterV1.Observation> completeStagedRouterFixture() throws Exception {
        Class<?> fixtureType = Class.forName(LiquidationStructureRouterV1Test.class.getName());
        Class<?> oiBoundaryType = Class.forName(fixtureType.getName() + "$OiBoundary");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object validBoundary = Enum.valueOf((Class<? extends Enum>) oiBoundaryType, "VALID");
        var fixtureMethod = fixtureType.getDeclaredMethod("fixture", boolean.class, oiBoundaryType, boolean.class);
        fixtureMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        ArrayList<LiquidationStructureRouterV1.Observation> features = new ArrayList<>(
                (List<LiquidationStructureRouterV1.Observation>) fixtureMethod.invoke(null, true, validBoundary, true));
        for (String methodName : List.of("macroPath", "postInitialFillBars")) {
            var method = fixtureType.getDeclaredMethod(methodName);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<LiquidationStructureRouterV1.Observation> rows =
                    (List<LiquidationStructureRouterV1.Observation>) method.invoke(null);
            features.addAll(rows);
        }
        return List.copyOf(features);
    }

    private static List<LiquidationStructureRouterV1.ConfirmedIntent> discoverStagedIntents(
            List<LiquidationStructureRouterV1.Observation> features) {
        LiquidationStructureRouterV1.Router router = new LiquidationStructureRouterV1.Router(
                LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY);
        ArrayList<LiquidationStructureRouterV1.ConfirmedIntent> intents = new ArrayList<>();
        for (LiquidationStructureRouterV1.Observation feature : sortedRouterFeatures(features)) {
            LiquidationStructureRouterV1.RouteResult route = router.accept(feature);
            for (LiquidationStructureRouterV1.ConfirmedIntent intent : route.intents()) {
                intents.add(intent);
                Instant fillAt = intent.requestedExecutionAfter().plusNanos(1);
                router.onFill(new LiquidationStructureRouterV1.FillAck(intent.intentId(), intent.setupId(), intent.asset(),
                        intent.stage(), fillAt, intent.zoneCenter(), intent.initialStop()));
            }
        }
        return List.copyOf(intents);
    }

    private static LiquidationStructureRouterV1.Router routerAwaitingStageTwo(
            List<LiquidationStructureRouterV1.Observation> features,
            LiquidationStructureRouterV1.ConfirmedIntent stageOne,
            LiquidationStructureRouterV1.ConfirmedIntent stageTwo) {
        LiquidationStructureRouterV1.Router router = new LiquidationStructureRouterV1.Router(
                LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY);
        for (LiquidationStructureRouterV1.Observation feature : sortedRouterFeatures(features)) {
            LiquidationStructureRouterV1.RouteResult route = router.accept(feature);
            for (LiquidationStructureRouterV1.ConfirmedIntent intent : route.intents()) {
                if (intent.intentId().equals(stageTwo.intentId())) return router;
                if (intent.intentId().equals(stageOne.intentId())) {
                    router.onFill(new LiquidationStructureRouterV1.FillAck(intent.intentId(), intent.setupId(), intent.asset(),
                            intent.stage(), intent.requestedExecutionAfter().plusNanos(1), intent.zoneCenter(), intent.initialStop()));
                }
            }
        }
        throw new AssertionError("router fixture did not reach the pending stage-two intent");
    }

    private static ObjectNode addEventFromIntent(LiquidationStructureRouterV1.ConfirmedIntent intent, Instant fillAt) {
        ObjectNode event = JsonHashes.mapper().createObjectNode().put("type", "ADD").put("asset", intent.asset())
                .put("time", fillAt.toEpochMilli()).put("stage", intent.stage()).put("setup_id", intent.setupId())
                .put("intent_id", intent.intentId()).put("decision_time", intent.decisionTime().toEpochMilli())
                .put("direction", intent.direction().name()).put("mode", intent.branch().name())
                .put("common_stop", intent.initialStop()).put("price", intent.zoneCenter())
                .put("mark_price", intent.zoneCenter()).put("decision_mark", intent.confirmationClose())
                .put("previous_completed_minute_base_volume", 100_000);
        if (intent.reversalTarget() == null) event.putNull("recovery_target");
        else event.put("recovery_target", intent.reversalTarget());
        return event;
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input stagedExecutionInput(
            List<LiquidationStructureRouterV1.Observation> features,
            List<LiquidationStructureRouterV1.ConfirmedIntent> intents) {
        Map<String, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> byAsset = new HashMap<>();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) byAsset.put(asset, new ArrayList<>());
        for (LiquidationStructureRouterV1.ConfirmedIntent intent : intents) {
            Instant fillAt = intent.decisionTime().plusMillis(HOUR_MS);
            double open = intent.zoneCenter();
            if (intent.direction() == LiquidationStructureRouterV1.Direction.SHORT && open >= intent.initialStop()) {
                open = Math.max(intent.zoneCenter() - intent.maxChaseDistance() / 2, intent.initialStop() - 0.01);
            } else if (intent.direction() == LiquidationStructureRouterV1.Direction.LONG && open <= intent.initialStop()) {
                open = Math.min(intent.zoneCenter() + intent.maxChaseDistance() / 2, intent.initialStop() + 0.01);
            }
            double high = intent.direction() == LiquidationStructureRouterV1.Direction.SHORT
                    ? Math.min(open + 0.01, intent.initialStop() - 0.001) : open + 0.01;
            double low = intent.direction() == LiquidationStructureRouterV1.Direction.LONG
                    ? Math.max(open - 0.01, intent.initialStop() + 0.001) : open - 0.01;
            if (intent.reversalTarget() != null) {
                if (intent.direction() == LiquidationStructureRouterV1.Direction.LONG) high = Math.min(high, intent.reversalTarget() - 0.001);
                else low = Math.max(low, intent.reversalTarget() + 0.001);
            }
            if (high < open) high = open;
            if (low > open) low = open;
            addUniqueExecutionBar(byAsset.get(intent.asset()), new LiquidationHourlyDevelopmentReplayV1.HourBar(
                    intent.asset(), fillAt.minusMillis(HOUR_MS), open, open + 0.01, open - 0.01, open, 100_000));
            addUniqueExecutionBar(byAsset.get(intent.asset()), new LiquidationHourlyDevelopmentReplayV1.HourBar(
                    intent.asset(), fillAt, open, high, low, open, 100_000));
        }
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            if (byAsset.get(asset).isEmpty()) byAsset.get(asset).add(new LiquidationHourlyDevelopmentReplayV1.HourBar(
                    asset, Instant.parse("2024-01-01T00:00:00Z"), 100, 100.1, 99.9, 100, 100_000));
        }
        TreeMap<Instant, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> byStart = new TreeMap<>();
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> byAssetStart = new HashMap<>();
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> byAssetAndStart = new HashMap<>();
        for (List<LiquidationHourlyDevelopmentReplayV1.HourBar> rows : byAsset.values()) {
            rows.sort(Comparator.comparing(row -> row.start));
            for (LiquidationHourlyDevelopmentReplayV1.HourBar row : rows) {
                String key = row.asset + "|" + row.start.toEpochMilli();
                byAssetStart.put(key, row); byAssetAndStart.put(key, row);
                byStart.computeIfAbsent(row.start, ignored -> new ArrayList<>()).add(row);
            }
        }
        Map<String, NavigableMap<Instant, Double>> prices = new HashMap<>();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            TreeMap<Instant, Double> closes = new TreeMap<>();
            for (Instant at = Instant.parse("2024-01-01T00:00:00Z"); at.isBefore(Instant.parse("2024-01-15T00:00:00Z"));
                    at = at.plus(Duration.ofHours(1))) closes.put(at, 100.0);
            prices.put(asset, closes);
        }
        ObjectNode coverage = JsonHashes.mapper().createObjectNode()
                .put("analysis_coverage_end_exclusive", "2024-01-15T00:00:00Z");
        return new LiquidationHourlyDevelopmentReplayV1.Input(features, byAsset, byStart, byAssetStart, byAssetAndStart,
                prices, coverage, Map.of(), List.of(), 91, 1, 1, 1, 0, "", null);
    }

    private static void addUniqueExecutionBar(List<LiquidationHourlyDevelopmentReplayV1.HourBar> rows,
            LiquidationHourlyDevelopmentReplayV1.HourBar bar) {
        rows.removeIf(existing -> existing.start.equals(bar.start));
        rows.add(bar);
    }

    private static ObjectNode replaySynthetic(LiquidationHourlyDevelopmentReplayV1.Input input,
            ObjectNode policy) throws Exception {
        var method = LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethod("replay",
                LiquidationHourlyDevelopmentReplayV1.Input.class, ObjectNode.class);
        method.setAccessible(true);
        try { return (ObjectNode) method.invoke(null, input, policy); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private static ObjectNode accountWithThreeEpisodes() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        request.putArray("positions").addObject().put("asset", "BTC").put("direction", "LONG")
                .put("common_stop", 90).putNull("recovery_target").put("lot_size", 0.01).put("minimum_notional", 5)
                .put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0005).put("liquidation_fee_rate", 0.005)
                .put("initial_mark_price", 100).putArray("maintenance_tiers");
        request.putArray("events");
        request.withArray("events").add(addEvent("setup-1", "intent-1", T));
        request.withArray("events").add(exitEvent(T + 60_000, 105));
        request.withArray("events").add(addEvent("setup-2", "intent-2", T + 120_000));
        request.withArray("events").add(exitEvent(T + 180_000, 95));
        request.withArray("events").add(addEvent("setup-3", "intent-3", T + 240_000));
        request.withArray("events").add(JsonHashes.mapper().createObjectNode().put("type", "MARK").put("asset", "BTC")
                .put("time", T + 300_000).put("price", 101));
        return request;
    }

    private static List<LiquidationStructureRouterV1.Observation> routedStressFeatures() {
        LocalDate stressDay = LocalDate.of(2024, 1, 1);
        Instant stressStart = stressDay.atTime(8, 0).toInstant(java.time.ZoneOffset.UTC);
        Instant modelAvailable = Instant.parse("2024-01-03T00:00:00Z");
        ArrayList<LiquidationStructureRouterV1.Observation> result = new ArrayList<>();
        for (int lag = 90; lag >= 1; lag--) {
            LocalDate date = stressDay.minusDays(lag);
            Instant start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            result.add(new LiquidationStructureRouterV1.DailyLiquidation("BTC", date, 1, 1,
                    start.plus(Duration.ofHours(24)), "daily-v1"));
        }
        Instant dayStart = stressDay.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        result.add(new LiquidationStructureRouterV1.DailyLiquidation("BTC", stressDay, 2, 2,
                dayStart.plus(Duration.ofHours(24)), "daily-v1"));

        Instant firstH4 = stressStart.minus(Duration.ofHours(4L * 180));
        Instant h4End = modelAvailable.plus(Duration.ofHours(8));
        for (Instant start = firstH4; start.isBefore(h4End); start = start.plus(Duration.ofHours(4))) {
            double open = 100, high = 100.5, low = 99.5, close = 100;
            if (start.equals(stressStart)) { open = 100; high = 100.2; low = 96.7; close = 97; }
            if (start.equals(modelAvailable.minus(Duration.ofHours(4)))) { open = 98; high = 98.5; low = 96.5; close = 97; }
            if (start.equals(modelAvailable)) { open = 97; high = 97.5; low = 96; close = 96.8; }
            if (start.equals(modelAvailable.plus(Duration.ofHours(4)))) { open = 96.8; high = 97.2; low = 95.8; close = 96.5; }
            result.add(new LiquidationStructureRouterV1.Bar("BTC", LiquidationStructureRouterV1.Timeframe.FOUR_HOUR,
                    start, start.plus(Duration.ofHours(4)), open, high, low, close, "price-4h-v1"));
        }
        Instant observedStart = stressStart.minus(Duration.ofMinutes(7));
        Instant observedEnd = stressStart.plus(Duration.ofHours(4)).minus(Duration.ofMinutes(7));
        result.add(new LiquidationStructureRouterV1.OpenInterest("BTC", observedStart, observedStart.plusSeconds(1), 100, "oi-v1"));
        result.add(new LiquidationStructureRouterV1.OpenInterest("BTC", observedEnd, observedEnd.plusSeconds(1), 90, "oi-v1"));

        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(2)), 100, 100.2, 99.8, 100);
        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(3)), 100, 100.2, 99.8, 100);
        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(4)), 99.7, 99.8, 99.4, 99.45);
        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(5)), 100, 100.2, 99.8, 100);
        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(6)), 100, 100.2, 99.8, 100);
        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(7)), 100, 100.2, 99.8, 100);
        addHourlyFeature(result, modelAvailable.plus(Duration.ofHours(8)), 99.7, 99.8, 99.4, 99.45);
        return List.copyOf(result);
    }

    private static void addHourlyFeature(List<LiquidationStructureRouterV1.Observation> rows,
            Instant start, double open, double high, double low, double close) {
        rows.add(new LiquidationStructureRouterV1.Bar("BTC", LiquidationStructureRouterV1.Timeframe.ONE_HOUR,
                start, start.plus(Duration.ofHours(1)), open, high, low, close, "price-1h-v1"));
    }

    private static List<LiquidationStructureRouterV1.Observation> sortedRouterFeatures(
            List<LiquidationStructureRouterV1.Observation> rows) {
        return rows.stream().sorted(Comparator
                .comparing((LiquidationStructureRouterV1.Observation row) -> row instanceof LiquidationStructureRouterV1.DailyLiquidation daily
                        ? daily.modeledAvailableAt() : row.availableAt())
                .thenComparing(LiquidationStructureRouterV1.Observation::eventTime)
                .thenComparingInt(row -> row instanceof LiquidationStructureRouterV1.DailyLiquidation ? 0
                        : row instanceof LiquidationStructureRouterV1.MacroClose ? 1
                        : row instanceof LiquidationStructureRouterV1.OpenInterest ? 2
                        : ((LiquidationStructureRouterV1.Bar) row).timeframe() == LiquidationStructureRouterV1.Timeframe.FOUR_HOUR ? 3 : 4)
                .thenComparing(LiquidationStructureRouterV1.Observation::asset)
                .thenComparing(LiquidationStructureRouterV1.Observation::seriesId)).toList();
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input hourlyInputForIntent(
            List<LiquidationStructureRouterV1.Observation> features,
            LiquidationStructureRouterV1.ConfirmedIntent intent) {
        Map<String, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> byAsset = new HashMap<>();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) byAsset.put(asset, new ArrayList<>());
        for (LiquidationStructureRouterV1.Observation feature : features) {
            if (feature instanceof LiquidationStructureRouterV1.Bar bar
                    && bar.timeframe() == LiquidationStructureRouterV1.Timeframe.ONE_HOUR) {
                byAsset.get(bar.asset()).add(new LiquidationHourlyDevelopmentReplayV1.HourBar(bar.asset(), bar.startTime(),
                        bar.open(), bar.high(), bar.low(), bar.close(), 100_000));
            }
        }
        Instant decision = intent.decisionTime();
        double fillPrice = intent.zoneCenter();
        addExecutionBar(byAsset.get("BTC"), new LiquidationHourlyDevelopmentReplayV1.HourBar("BTC", decision,
                fillPrice, fillPrice + 0.1, fillPrice - 0.1, fillPrice, 100_000));
        Instant fillAt = decision.plusMillis(HOUR_MS);
        double high = intent.direction() == LiquidationStructureRouterV1.Direction.SHORT
                ? Math.max(fillPrice + 0.1, intent.initialStop() + 0.1) : fillPrice + 0.1;
        double low = intent.direction() == LiquidationStructureRouterV1.Direction.LONG
                ? Math.min(fillPrice - 0.1, intent.initialStop() - 0.1) : fillPrice - 0.1;
        addExecutionBar(byAsset.get("BTC"), new LiquidationHourlyDevelopmentReplayV1.HourBar("BTC", fillAt,
                fillPrice, high, low, fillPrice, 100_000));
        Instant afterFill = fillAt.plusMillis(HOUR_MS);
        addExecutionBar(byAsset.get("BTC"), new LiquidationHourlyDevelopmentReplayV1.HourBar("BTC", afterFill,
                fillPrice, fillPrice + 0.1, fillPrice - 0.1, fillPrice, 100_000));
        Instant firstStart = Instant.parse("2024-01-03T02:00:00Z");
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            if (byAsset.get(asset).isEmpty()) byAsset.get(asset).add(new LiquidationHourlyDevelopmentReplayV1.HourBar(
                    asset, firstStart, 100, 100.1, 99.9, 100, 100_000));
            byAsset.get(asset).sort(Comparator.comparing(bar -> bar.start));
        }

        TreeMap<Instant, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> byStart = new TreeMap<>();
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> byAssetStart = new HashMap<>();
        for (List<LiquidationHourlyDevelopmentReplayV1.HourBar> bars : byAsset.values()) {
            for (LiquidationHourlyDevelopmentReplayV1.HourBar bar : bars) {
                String key = bar.asset + "|" + bar.start.toEpochMilli();
                byAssetStart.put(key, bar);
                byStart.computeIfAbsent(bar.start, ignored -> new ArrayList<>()).add(bar);
            }
        }
        Map<String, NavigableMap<Instant, Double>> prices = new HashMap<>();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            TreeMap<Instant, Double> closes = new TreeMap<>();
            for (Instant at = Instant.parse("2024-01-01T00:00:00Z"); at.isBefore(Instant.parse("2024-01-13T00:00:00Z"));
                    at = at.plus(Duration.ofHours(1))) closes.put(at, 100.0);
            prices.put(asset, closes);
        }
        ObjectNode coverage = JsonHashes.mapper().createObjectNode()
                .put("analysis_coverage_end_exclusive", "2024-01-13T00:00:00Z");
        return new LiquidationHourlyDevelopmentReplayV1.Input(features, byAsset, byStart, byAssetStart, byAssetStart,
                prices, coverage, Map.of(), List.of(), 91, 1, 1, 1, 0, "", null);
    }

    private static void addExecutionBar(List<LiquidationHourlyDevelopmentReplayV1.HourBar> bars,
            LiquidationHourlyDevelopmentReplayV1.HourBar next) {
        bars.removeIf(value -> value.start.equals(next.start));
        bars.add(next);
    }

    private static ObjectNode directFillAttempt(boolean includePrior, double priorVolume,
            String fillPriceMode, boolean alreadyOccupied) throws Exception {
        List<LiquidationStructureRouterV1.Observation> features = routedStressFeatures();
        LiquidationStructureRouterV1.Router router = new LiquidationStructureRouterV1.Router(
                LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY);
        LiquidationStructureRouterV1.ConfirmedIntent intent = null;
        LiquidationStructureRouterV1.RouteResult route = null;
        for (LiquidationStructureRouterV1.Observation row : sortedRouterFeatures(features)) {
            LiquidationStructureRouterV1.RouteResult candidate = router.accept(row);
            var stageOne = candidate.intents().stream().filter(value -> value.stage() == 1).findFirst().orElse(null);
            if (stageOne != null) { intent = stageOne; route = candidate; break; }
        }
        assertNotNull(intent);

        Instant fillAt = intent.decisionTime().plusMillis(HOUR_MS);
        double fillPrice = "CHASE".equals(fillPriceMode)
                ? intent.zoneCenter() + intent.maxChaseDistance() + 1 : intent.zoneCenter();
        LiquidationHourlyDevelopmentReplayV1.HourBar fill = new LiquidationHourlyDevelopmentReplayV1.HourBar(
                intent.asset(), fillAt, fillPrice, fillPrice + 0.1, fillPrice - 0.1, fillPrice, 100_000);
        TreeMap<Instant, List<LiquidationHourlyDevelopmentReplayV1.HourBar>> byStart = new TreeMap<>();
        byStart.put(fillAt, List.of(fill));
        Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> byAssetAndStart = new HashMap<>();
        byAssetAndStart.put(intent.asset() + "|" + fillAt.toEpochMilli(), fill);
        if (includePrior) {
            Instant priorAt = fillAt.minusMillis(HOUR_MS);
            LiquidationHourlyDevelopmentReplayV1.HourBar prior = new LiquidationHourlyDevelopmentReplayV1.HourBar(
                    intent.asset(), priorAt, fillPrice, fillPrice + 0.1, fillPrice - 0.1, fillPrice, priorVolume);
            byStart.put(priorAt, List.of(prior));
            byAssetAndStart.put(intent.asset() + "|" + priorAt.toEpochMilli(), prior);
        }
        LiquidationHourlyDevelopmentReplayV1.Input input = new LiquidationHourlyDevelopmentReplayV1.Input(features,
                Map.of(intent.asset(), List.of(fill)), byStart, byAssetAndStart, byAssetAndStart,
                Map.of(), JsonHashes.mapper().createObjectNode(), Map.of(), List.of(), 91, 1, 1, 1, 0, "", null);
        LiquidationPortfolioAccountingV1.AccountSession account = LiquidationPortfolioAccountingV1
                .startHourlyDevelopmentSession(hourlyAccount());
        if (alreadyOccupied) account.accept(addEvent("occupied-setup", "occupied-intent", T));
        Map<String, Object> pending = new HashMap<>();
        ArrayNode rejections = JsonHashes.mapper().createArrayNode();
        Object funnel = newFunnel();
        invokeProcessRouteResult(route, router, account, pending, rejections, intent.decisionTime(), input, funnel,
                "2022-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
        Object pendingFill = pending.values().iterator().next();
        invokeExecuteFill(pendingFill, List.of(fill), input, "STAGED_NO_MACRO", account, router, pending,
                new HashMap<>(), funnel);

        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.set("account", account.snapshot());
        result.set("funnel", (ObjectNode) invokeFunnelJson(funnel));
        return result;
    }

    private static Object newFunnel() throws Exception {
        Class<?> type = Class.forName(LiquidationHourlyDevelopmentReplayV1.class.getName() + "$Funnel");
        var constructor = type.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(91, 1, 1, 1, 0);
    }

    private static void invokeProcessRouteResult(LiquidationStructureRouterV1.RouteResult route,
            LiquidationStructureRouterV1.Router router, LiquidationPortfolioAccountingV1.AccountSession account,
            Map<String, Object> pending, ArrayNode rejections, Instant at,
            LiquidationHourlyDevelopmentReplayV1.Input input, Object funnel,
            String start, String end) throws Exception {
        var method = java.util.Arrays.stream(LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("processRouteResult")).findFirst().orElseThrow();
        method.setAccessible(true);
        try { method.invoke(null, route, router, account, pending, rejections, at, "STAGED_NO_MACRO", input,
                funnel, start, end); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private static void invokeExecuteFill(Object pendingFill,
            List<LiquidationHourlyDevelopmentReplayV1.HourBar> current,
            LiquidationHourlyDevelopmentReplayV1.Input input, String variant,
            LiquidationPortfolioAccountingV1.AccountSession account, LiquidationStructureRouterV1.Router router,
            Map<String, Object> pending, Map<String, Instant> firstFills, Object funnel) throws Exception {
        var method = java.util.Arrays.stream(LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("executeFill")).findFirst().orElseThrow();
        method.setAccessible(true);
        try { method.invoke(null, pendingFill, current, input, variant, account, router, pending, firstFills, funnel); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private static Object invokeFunnelJson(Object funnel) throws Exception {
        var method = funnel.getClass().getDeclaredMethod("toJson");
        method.setAccessible(true);
        return method.invoke(funnel);
    }

    private static ObjectNode hourlyAccount() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v3-hourly-development-account/1")
                .put("execution_timeframe", "H1_OHLC_APPROXIMATION").put("initial_equity_usdt", 20_000)
                .put("reserved_costs_usdt", 0);
        request.putArray("positions").addObject().put("asset", "BTC").put("direction", "UNCONFIGURED")
                .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.0005)
                .put("slippage_rate", 0.0005).put("liquidation_fee_rate", 0.005).put("initial_mark_price", 100)
                .putArray("maintenance_tiers").addObject().put("effective_from", 0).put("effective_until", Long.MAX_VALUE)
                .put("tier_notional_cap", 1.0e15).put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0);
        return request;
    }

    private static ObjectNode addEvent(String setup, String intent, long time) {
        return JsonHashes.mapper().createObjectNode().put("type", "ADD").put("asset", "BTC").put("time", time)
                .put("stage", 1).put("setup_id", setup).put("intent_id", intent).put("decision_time", time)
                .put("direction", "LONG").put("mode", "CONTINUATION").put("common_stop", 90).putNull("recovery_target")
                .put("price", 100).put("mark_price", 100).put("decision_mark", 100)
                .put("previous_completed_minute_base_volume", 100_000);
    }

    private static ObjectNode exitEvent(long time, double price) {
        return JsonHashes.mapper().createObjectNode().put("type", "EXIT").put("asset", "BTC").put("time", time)
                .put("price", price).put("reason", "FIXTURE_EXIT");
    }

    private static LiquidationHourlyDevelopmentReplayV1.HourBar bar(String asset, Instant start,
            double open, double high, double low, double close, double volume) {
        return new LiquidationHourlyDevelopmentReplayV1.HourBar(asset, start, open, high, low, close, volume);
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input input(Map<String, LiquidationHourlyDevelopmentReplayV1.HourBar> byStart) {
        return new LiquidationHourlyDevelopmentReplayV1.Input(List.of(), Map.of(), new TreeMap<>(), byStart, byStart,
                Map.of(), JsonHashes.mapper().createObjectNode(), Map.of(), List.of(), 0, 0, 0, 0, 0, "", null);
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input loadInput(Path manifest) throws Exception {
        var method = LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethod("loadInput", Path.class);
        method.setAccessible(true);
        try { return (LiquidationHourlyDevelopmentReplayV1.Input) method.invoke(null, manifest); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private static Path writeSmallInputFixture(Path directory) throws IOException {
        Path normalized = Files.createDirectories(directory.resolve("normalized"));
        StringBuilder daily = new StringBuilder("asset,symbol,day_start_utc,long_liquidations_usd,short_liquidations_usd\n");
        LocalDate firstDay = LocalDate.of(2022, 8, 11);
        Instant firstHour = Instant.parse("2022-08-11T00:00:00Z");
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            for (int lag = 0; lag <= 90; lag++) {
                LocalDate day = firstDay.plusDays(lag);
                double stress = lag == 90 ? 100 : 1;
                daily.append(asset).append(',').append(asset).append("USDT_PERP.A,")
                        .append(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()).append(',')
                        .append(stress).append(",0\n");
            }
            StringBuilder bars = new StringBuilder("open_time,symbol,open,high,low,close,base_volume\n");
            for (int hour = 0; hour < 8; hour++) {
                double price = 100 + hour;
                bars.append(firstHour.plusMillis(hour * HOUR_MS)).append(',').append(asset).append("USDT,")
                        .append(price).append(',').append(price + 1).append(',').append(price - 1).append(',')
                        .append(price + 0.5).append(",100\n");
            }
            Files.writeString(normalized.resolve("klines_1h_" + asset + ".csv"), bars);
            StringBuilder oi = new StringBuilder("time,symbol,sum_open_interest\n");
            oi.append(firstHour.plusSeconds(3 * 3600 + 50 * 60)).append(',').append(asset).append("USDT,100\n")
                    .append(firstHour.plusSeconds(7 * 3600 + 50 * 60)).append(',').append(asset).append("USDT,90\n");
            Files.writeString(normalized.resolve("oi_5m_" + asset + ".csv"), oi);
        }
        Files.writeString(normalized.resolve("daily_liquidations_v002.csv"), daily);
        Path sp = normalized.resolve("fred-sp500.csv");
        Files.writeString(sp, "observation_date,SP500\n2022-08-11,4110\n2022-08-12,.\n2022-08-15,4140\n");
        Files.writeString(directory.resolve("coverage.json"), "{}\n");
        ObjectNode manifest = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-input-manifest/1")
                .put("root", "normalized");
        ObjectNode files = manifest.putObject("files").put("daily_liquidations", "daily_liquidations_v002.csv")
                .put("sp500", "fred-sp500.csv");
        ObjectNode bars = files.putObject("hourly_bars"), oi = files.putObject("oi_5m");
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            bars.put(asset, "klines_1h_" + asset + ".csv");
            oi.put(asset, "oi_5m_" + asset + ".csv");
        }
        Path manifestPath = directory.resolve("input-manifest.json");
        Files.write(manifestPath, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        return manifestPath;
    }

    private static Path findRepositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("pom.xml"))
                    && Files.isRegularFile(path.resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json"))) return path;
        }
        throw new IllegalStateException("repository policy fixture is not available from this test environment");
    }

    private static LiquidationStructureRouterV1.ConfirmedIntent intent(Instant decision) {
        return new LiquidationStructureRouterV1.ConfirmedIntent("intent", "setup", "pair", "BTC",
                LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                LiquidationStructureRouterV1.Branch.CONTINUATION, LiquidationStructureRouterV1.Branch.CONTINUATION,
                LiquidationStructureRouterV1.Direction.LONG, LiquidationStructureRouterV1.Direction.LONG, 1,
                decision, decision.plusMillis(1), decision, 100, 100, 99, 101, null, null, null,
                1, 90, null, 20, 0.01, 2, 60, LiquidationStructureRouterV1.MacroState.NOT_REQUIRED, true,
                false, List.of(), List.of());
    }
}
