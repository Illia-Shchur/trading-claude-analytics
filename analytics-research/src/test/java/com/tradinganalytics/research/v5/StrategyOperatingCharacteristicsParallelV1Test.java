package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyOperatingCharacteristicsParallelV1Test {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final StrategyOperatingCharacteristicsParallelV1.ResourceProbe TEST_RESOURCE_PROBE =
            resourceProbe(28, 32L * GIB, 256L * GIB);

    @TempDir Path temporary;

    @Test
    void profileCapsAnUndersizedHostAndPreflightKeepsFullExecutionGated() {
        ObjectNode profile = StrategyOperatingCharacteristicsParallelV1.executionProfile(JsonHashes.mapper().createObjectNode()
                .put("requested_workers", 8), resourceProbe(10, 16L * GIB, 256L * GIB));
        assertThat(profile.path("target_qualified").asBoolean()).isFalse();
        assertThat(profile.path("effective_workers").asInt()).isEqualTo(2);
        ObjectNode plan = plan("FULL", 75, false);
        ObjectNode preflightOptions = JsonHashes.mapper().createObjectNode();
        preflightOptions.set("plan", plan); preflightOptions.set("profile", profile);
        preflightOptions.put("internal_test_seam", true);
        ObjectNode preflight = StrategyOperatingCharacteristicsParallelV1.preflight(preflightOptions);
        assertThat(preflight.path("status").asText()).isEqualTo("BLOCKED_RESOURCE");
        assertThat(preflight.path("measured").asBoolean()).isFalse();
    }

    @Test
    void schedulerUsesBoundedConcurrencyAndDeterministicSlotOrdering() throws Exception {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(2);
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", temporary.resolve("one/ledger.json").toString());
        AtomicInteger active = new AtomicInteger(); AtomicInteger peak = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ObjectNode> result = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                result.set(StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
                    int now = active.incrementAndGet(); peak.accumulateAndGet(now, Math::max);
                    started.countDown();
                    try {
                        if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("both workers did not start");
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("workers were not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                    } finally { active.decrementAndGet(); }
                    return row(slot);
                }, TEST_RESOURCE_PROBE));
            } catch (Throwable error) { failure.set(error); }
        }, "parallel-scheduler-test");
        runner.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peak).hasValue(2);
        } finally {
            release.countDown();
        }
        runner.join(5_000L);
        assertThat(runner.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result.get().path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.get().path("planned_slots").asInt()).isEqualTo(2);
        assertThat(result.get().path("completed_slots").asInt()).isEqualTo(2);
        assertThat(result.get().path("result_refs").get(0).path("slot_id").asText())
                .isEqualTo("PREFIX|NO_EDGE|0|0|11");
        assertThat(result.get().path("result_refs").get(1).path("slot_id").asText())
                .isEqualTo("PREFIX|NO_EDGE|0|1|12");
    }

    @Test
    void fullSchedulerUsesInjectedCoordinatorProbeForSummaryBudgetChecks() throws Exception {
        ObjectNode plan = plan("FULL", 75, false);
        ObjectNode profile = profile(1);
        Path ledger = temporary.resolve("full/ledger.json");
        Files.createDirectories(ledger.getParent().resolve("artifacts"));
        @SuppressWarnings("unchecked")
        var planned = (java.util.List<StrategyOperatingCharacteristicsParallelV1.Slot>) invoke(
                declared("slots", ObjectNode.class, String.class), plan, "FULL");
        var refs = JsonHashes.mapper().createArrayNode();
        for (StrategyOperatingCharacteristicsParallelV1.Slot slot : planned) {
            ObjectNode artifact = artifact(slot, row(slot));
            Path path = ledger.getParent().resolve("artifacts").resolve(slot.ordinal() + ".json");
            Files.writeString(path, JsonHashes.mapper().writeValueAsString(artifact));
            refs.addObject().put("slot_id", slot.id()).put("relative_path", "artifacts/" + path.getFileName());
        }

        ObjectNode summary = (ObjectNode) invoke(declared("fullCellSummaries", ObjectNode.class, java.util.List.class,
                com.fasterxml.jackson.databind.node.ArrayNode.class, Path.class, ObjectNode.class, long.class,
                boolean.class, StrategyOperatingCharacteristicsParallelV1.ResourceProbe.class), plan, planned, refs,
                ledger, profile, Long.MAX_VALUE, false, TEST_RESOURCE_PROBE);

        assertThat(summary.path("cell_count").asInt()).isEqualTo(4);
        assertThat(summary.path("cells")).allMatch(cell -> cell.path("adequate").asBoolean());
    }

    @Test
    void retryIsCountedOnceAndACompletedArtifactIsReusedOnRestart() throws Exception {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        Path ledger = temporary.resolve("restart/ledger.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", ledger.toString());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode first = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("synthetic worker failure");
            return row(slot);
        }, TEST_RESOURCE_PROBE);
        assertThat(first.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(calls).hasValue(3);
        ObjectNode second = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            throw new AssertionError("completed slot must be reused");
        }, TEST_RESOURCE_PROBE);
        assertThat(second.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(Files.readString(ledger)).contains("FAILED").contains("COMPLETE");
    }

    @Test
    void resumeRejectsCorruptOrMissingResultArtifact() throws Exception {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        Path ledger = temporary.resolve("corrupt/ledger.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", ledger.toString());
        StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> row(slot), TEST_RESOURCE_PROBE);
        ObjectNode saved = JsonHashes.mapper().readValue(Files.readString(ledger), ObjectNode.class);
        Path artifact = ledger.getParent().resolve(saved.path("terminal").get(0).path("relative_path").asText());
        Files.writeString(artifact, "{}\n");
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options,
                (slot, scratch) -> row(slot), TEST_RESOURCE_PROBE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte hash");
    }

    @Test
    void resumeRejectsMissingResultArtifact() throws Exception {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        Path ledger = temporary.resolve("missing/ledger.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", ledger.toString());
        StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options,
                (slot, scratch) -> row(slot), TEST_RESOURCE_PROBE);
        ObjectNode saved = JsonHashes.mapper().readValue(Files.readString(ledger), ObjectNode.class);
        Path artifact = ledger.getParent().resolve(saved.path("terminal").get(0).path("relative_path").asText());
        Files.delete(artifact);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options,
                (slot, scratch) -> row(slot), TEST_RESOURCE_PROBE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
    }

    @Test
    void lowDiskProbeAbortsBeforeLaunchingAWorker() {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", temporary.resolve("low-disk/ledger.json").toString());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            calls.incrementAndGet(); return row(slot);
        }, resourceProbe(28, 32L * GIB, 127L * GIB));

        assertThat(result.path("status").asText()).isEqualTo("COMPUTE_INCOMPLETE");
        assertThat(result.path("stop_reason").asText()).isEqualTo("RESOURCE_DISK_HEADROOM_EXCEEDED");
        assertThat(result.path("missing_slots").asInt()).isEqualTo(2);
        assertThat(calls).hasValue(0);
    }

    @Test
    void unavailableResourceProbeAbortsBeforeLaunchingAWorker() {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", temporary.resolve("unavailable/ledger.json").toString());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            calls.incrementAndGet(); return row(slot);
        }, resourceProbe(28, 32L * GIB, -1L));

        assertThat(result.path("status").asText()).isEqualTo("COMPUTE_INCOMPLETE");
        assertThat(result.path("stop_reason").asText()).isEqualTo("RESOURCE_DISK_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @Test
    void coordinatorResourceBudgetAbortsBeforeLaunchingAWorker() {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", temporary.resolve("coordinator/ledger.json").toString());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            calls.incrementAndGet(); return row(slot);
        }, resourceProbe(28, 32L * GIB, 256L * GIB, 2L * GIB + 1L, 0L));

        assertThat(result.path("status").asText()).isEqualTo("COMPUTE_INCOMPLETE");
        assertThat(result.path("stop_reason").asText()).isEqualTo("RESOURCE_COORDINATOR_RSS_BUDGET_EXCEEDED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void privateCoordinatorBudgetGuardRejectsUnavailableAndOverLimitObservations() {
        Method method = declared("enforceCoordinatorBudget", ObjectNode.class, long.class,
                StrategyOperatingCharacteristicsParallelV1.ResourceProbe.class);
        ObjectNode profile = profile(1);

        assertThatThrownBy(() -> invoke(method, profile, Long.MAX_VALUE,
                resourceProbe(28, 32L * GIB, 256L * GIB, -1L, 0L)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoke(method, profile, Long.MAX_VALUE,
                resourceProbe(28, 32L * GIB, 256L * GIB, 2L * GIB + 1L, 0L)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void liveAdmissionUsesInjectedCpuMemoryAndDiskObservations() throws Exception {
        Method method = declared("validateLiveProfile", ObjectNode.class, ObjectNode.class,
                String.class, StrategyOperatingCharacteristicsParallelV1.ResourceProbe.class);
        ObjectNode profile = profile(1);
        ObjectNode options = JsonHashes.mapper().createObjectNode();

        invoke(method, profile, options, "PREFIX", resourceProbe(28, 32L * GIB, 256L * GIB));
        assertThatThrownBy(() -> invoke(method, profile, options, "PREFIX",
                resourceProbe(1, 32L * GIB, 256L * GIB)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoke(method, profile, options, "PREFIX",
                resourceProbe(28, 8L * GIB, 256L * GIB)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoke(method, profile, options, "PREFIX",
                resourceProbe(28, 32L * GIB, 127L * GIB)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resourceRootIsForwardedToTheTypedProbe() {
        Path root = temporary.resolve("resource-root");
        AtomicReference<Path> observed = new AtomicReference<>();
        StrategyOperatingCharacteristicsParallelV1.ResourceProbe probe = resourceRoot -> {
            observed.set(resourceRoot);
            return new StrategyOperatingCharacteristicsParallelV1.ResourceObservation(
                    28, 32L * GIB, 256L * GIB, 0L, 0L);
        };

        StrategyOperatingCharacteristicsParallelV1.executionProfile(JsonHashes.mapper().createObjectNode()
                .put("resource_root", root.toString()), probe);

        assertThat(observed).hasValue(root);
    }

    @Test
    void publicProfileIgnoresJsonProbeValues() {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("test_probe_override", true).put("available_cpus", 1)
                .put("available_memory_bytes", 1L).put("available_free_space_bytes", 1L);

        ObjectNode profile = StrategyOperatingCharacteristicsParallelV1.executionProfile(options);

        assertThat(profile.path("available_cpus").asLong()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(profile.has("test_probe_override")).isFalse();
    }

    @Test
    void macMachineIdentityParserAcceptsOnlyAPlatformUuid() {
        String uuid = "00112233-4455-6677-8899-aabbccddeeff";
        assertThat(StrategyOperatingCharacteristicsParallelV1.parseMacMachineIdentity(
                "+-o IOPlatformExpertDevice\n    \"IOPlatformUUID\" = \"" + uuid + "\"\n"))
                .isEqualTo(uuid);
        assertThat(StrategyOperatingCharacteristicsParallelV1.parseMacMachineIdentity(null)).isEmpty();
        assertThat(StrategyOperatingCharacteristicsParallelV1.parseMacMachineIdentity(
                "    \"IOPlatformUUID\" = \"unknown\"\n")).isEmpty();
        assertThat(StrategyOperatingCharacteristicsParallelV1.parseMacMachineIdentity(
                "    \"OtherUUID\" = \"" + uuid + "\"\n")).isEmpty();
    }

    @Test
    void resourceIncompleteRowsAbortGloballyWithoutPublishingCompleteTransport() {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", temporary.resolve("resource/ledger.json").toString());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            calls.incrementAndGet(); return row(slot).put("status", "RESOURCE_RSS_BUDGET_EXCEEDED");
        }, TEST_RESOURCE_PROBE);
        assertThat(result.path("status").asText()).isEqualTo("COMPUTE_INCOMPLETE");
        assertThat(result.path("missing_slots").asInt()).isEqualTo(2);
        assertThat(calls).hasValue(1);
    }

    @Test
    void productionRunRejectsTestProbeOverrideBeforeAnyInputIsOpened() {
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.run(
                JsonHashes.mapper().createObjectNode().put("test_probe_override", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test_probe_override");
    }

    @Test
    void cancellationLeavesDenominatorAndDoesNotLaunchRemainingSlots() throws Exception {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(1);
        Path cancel = temporary.resolve("cancel.flag"); Files.writeString(cancel, "cancel");
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("cancel_file", cancel.toString())
                .put("ledger", temporary.resolve("abort/ledger.json").toString());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            calls.incrementAndGet(); return row(slot);
        });
        assertThat(result.path("status").asText()).isEqualTo("COMPUTE_INCOMPLETE");
        assertThat(result.path("missing_slots").asInt()).isEqualTo(2);
        assertThat(calls).hasValue(0);
    }

    @Test
    void qualificationPortfolioRejectsExitThenEqualTimeMarkAndAcceptsClosedBook() {
        ObjectNode invalid = qualificationPortfolioRow(true);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.validateQualificationPortfolioForTest(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside its active interval");

        ObjectNode valid = qualificationPortfolioRow(false);
        StrategyOperatingCharacteristicsParallelV1.validateQualificationPortfolioForTest(valid);
    }

    @Test
    void portableEconomicDigestIgnoresOnlyWorkerPolicyPathsAndPreservesEconomics() {
        ObjectNode first = portableRaw("/worker-a/policies/portfolio.json", "/worker-a/policies/lifecycle.json");
        ObjectNode second = first.deepCopy();
        second.put("portfolio_policy_path", "/worker-b/policies/portfolio.json");
        second.put("lifecycle_timing_policy_path", "/worker-b/policies/lifecycle.json");
        assertThat(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(first))
                .isEqualTo(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(second));

        ObjectNode changedPolicy = first.deepCopy().put("portfolio_policy_sha256", "b".repeat(64));
        assertThat(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(changedPolicy))
                .isNotEqualTo(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(first));
        ObjectNode changedTrade = first.deepCopy().put("event_count", 11);
        assertThat(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(changedTrade))
                .isNotEqualTo(StrategyOperatingCharacteristicsParallelV1.portableEconomicSha256ForTest(first));
        assertThat(first.path("portfolio_policy_path").asText()).isEqualTo("/worker-a/policies/portfolio.json");
    }

    private ObjectNode profile(int workers) {
        return StrategyOperatingCharacteristicsParallelV1.executionProfile(JsonHashes.mapper().createObjectNode()
                .put("requested_workers", workers), TEST_RESOURCE_PROBE);
    }

    private static StrategyOperatingCharacteristicsParallelV1.ResourceProbe resourceProbe(
            long cpus, long memoryBytes, long freeBytes) {
        return resourceProbe(cpus, memoryBytes, freeBytes, 0L, 0L);
    }

    private static StrategyOperatingCharacteristicsParallelV1.ResourceProbe resourceProbe(
            long cpus, long memoryBytes, long freeBytes, long coordinatorRssBytes, long aggregateWorkerRssBytes) {
        return ignored -> new StrategyOperatingCharacteristicsParallelV1.ResourceObservation(
                cpus, memoryBytes, freeBytes, coordinatorRssBytes, aggregateWorkerRssBytes);
    }

    private static Method declared(String name, Class<?>... parameterTypes) {
        try {
            Method method = StrategyOperatingCharacteristicsParallelV1.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object invoke(Method method, Object... arguments) throws Exception {
        return method.invoke(null, arguments);
    }

    private static ObjectNode plan(String mode, int repetitions, boolean prefixOnly) {
        ObjectNode plan = JsonHashes.mapper().createObjectNode().put("schema", StrategyOperatingCharacteristicsParallelV1.PLAN_SCHEMA)
                .put("version", 1).put("mode", mode).put("frozen_before_outcomes", true)
                .put("outcomes_opened", false).put("promotion_eligible", false).put("activation_authorized", false)
                .put("episodes_per_replication", 1);
        var cells = plan.putArray("cells");
        int cellCount = prefixOnly ? 1 : 4;
        for (int c = 0; c < cellCount; c++) {
            ObjectNode cell = cells.addObject().put("scenario", c == 0 ? "NO_EDGE" : "PLANTED_EDGE")
                    .put("effect_size", c < 2 ? 0D : c == 2 ? .02D : .04D);
            if (prefixOnly) cell.putArray("prefix_seeds").add(11).add(12);
            else {
                var seeds = cell.putArray("seeds"); for (int i = 0; i < repetitions; i++) seeds.add(10_000L * (c + 1) + i);
            }
        }
        if (!prefixOnly) {
            plan.put("replications", repetitions).put("cell_count", 4)
                    .put("episodes_per_replication", 450).put("cluster_count", 288)
                    .put("paired_cluster_count", 162).put("lifecycle_series_per_replication", 900)
                    .put("horizon_minutes", 14_400);
            ObjectNode statistical = plan.deepCopy();
            statistical.remove("content_sha256");
            statistical.put("content_sha256", JsonHashes.ownHash(statistical));
            plan.set("statistical_plan", statistical);
            plan.put("statistical_plan_sha256", statistical.path("content_sha256").asText());
        }
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        return plan;
    }

    private static ObjectNode row(StrategyOperatingCharacteristicsParallelV1.Slot slot) {
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("cell", slot.scenario()).put("effect_size", slot.effectSize())
                .put("replication", slot.replication()).put("seed", slot.seed()).put("status", "COMPLETE")
                .put("decision", slot.replication() % 2 == 0).put("independent_units", 30).put("paired_count", 30);
        result.putObject("metrics").put("paired_tested_cluster_count", 30).put("paired_analysis_insufficient", false);
        return result;
    }

    private static ObjectNode artifact(StrategyOperatingCharacteristicsParallelV1.Slot slot, ObjectNode row) {
        ObjectNode artifact = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-evaluator-operating-characteristics-parallel-slot-result/1")
                .put("version", 1).put("status", "COMPLETE").put("slot_id", slot.id())
                .put("plan_sha256", slot.planSha256()).put("mode", slot.mode())
                .put("scenario", slot.scenario()).put("effect_size", slot.effectSize())
                .put("replication", slot.replication()).put("seed", slot.seed()).put("attempt", 1);
        artifact.set("row", row);
        artifact.set("executor_identity", JsonHashes.mapper().createObjectNode());
        artifact.put("content_sha256", JsonHashes.ownHash(artifact));
        return artifact;
    }

    private static ObjectNode portableRaw(String portfolioPath, String lifecyclePath) {
        return JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-result/1")
                .put("status", "COMPLETE")
                .put("event_count", 10)
                .put("portfolio_policy_path", portfolioPath)
                .put("lifecycle_timing_policy_path", lifecyclePath)
                .put("portfolio_policy_sha256", "a".repeat(64))
                .put("lifecycle_timing_policy_sha256", "c".repeat(64))
                .put("economic_semantic_sha256", "d".repeat(64))
                .put("content_sha256", "e".repeat(64));
    }

    private static ObjectNode qualificationPortfolioRow(boolean markAtExit) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("status", "COMPLETE");
        ObjectNode portfolio = row.putObject("portfolio_summary").put("trade_count", 1)
                .put("ending_equity_usdt", 101D).put("ending_minus_starting_equals_net", true).put("sum_check", true);
        ObjectNode trade = portfolio.putArray("trades").addObject().put("episode_id", "episode-1")
                .put("entry_price", 100D).put("quantity", 1D);
        ObjectNode lifecycle = trade.putObject("lifecycle").put("entry_time", "2026-01-01T00:00:00Z");
        lifecycle.putArray("exits").addObject().put("time", "2026-01-01T00:10:00Z").put("price", 101D);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", markAtExit ? "2026-01-01T00:10:00Z" : "2026-01-01T00:05:00Z")
                .put("price", 100.5D);
        var curve = portfolio.putArray("equity_curve");
        curve.addObject().put("event_type", "ENTRY").put("cash_usdt", 0D)
                .put("holdings_at_entry_cost_usdt", 100D).put("equity_usdt", 100D);
        curve.addObject().put("event_type", "EXIT").put("cash_usdt", 101D)
                .put("holdings_at_entry_cost_usdt", 0D).put("equity_usdt", 101D);
        return row;
    }
}
