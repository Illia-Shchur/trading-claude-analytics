package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyOperatingCharacteristicsParallelV1Test {
    @TempDir Path temporary;

    @Test
    void profileCapsAnUndersizedHostAndPreflightKeepsFullExecutionGated() {
        ObjectNode profile = StrategyOperatingCharacteristicsParallelV1.executionProfile(JsonHashes.mapper().createObjectNode()
                .put("test_probe_override", true).put("available_cpus", 10).put("available_memory_bytes", 16L * 1024 * 1024 * 1024)
                .put("available_free_space_bytes", 256L * 1024 * 1024 * 1024));
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
    void schedulerUsesBoundedConcurrencyAndDeterministicSlotOrdering() {
        ObjectNode plan = plan("PREFIX", 2, true);
        ObjectNode profile = profile(2);
        ObjectNode options = JsonHashes.mapper().createObjectNode(); options.set("plan", plan); options.set("profile", profile);
        options.put("mode", "PREFIX").put("ledger", temporary.resolve("one/ledger.json").toString());
        AtomicInteger active = new AtomicInteger(); AtomicInteger peak = new AtomicInteger();
        ObjectNode result = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            int now = active.incrementAndGet(); peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(5L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return row(slot);
        });
        assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("planned_slots").asInt()).isEqualTo(2);
        assertThat(result.path("completed_slots").asInt()).isEqualTo(2);
        assertThat(peak).hasValue(2);
        assertThat(result.path("result_refs").get(0).path("slot_id").asText())
                .isEqualTo("PREFIX|NO_EDGE|0|0|11");
        assertThat(result.path("result_refs").get(1).path("slot_id").asText())
                .isEqualTo("PREFIX|NO_EDGE|0|1|12");
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
        });
        assertThat(first.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(calls).hasValue(3);
        ObjectNode second = StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> {
            throw new AssertionError("completed slot must be reused");
        });
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
        StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> row(slot));
        ObjectNode saved = JsonHashes.mapper().readValue(Files.readString(ledger), ObjectNode.class);
        Path artifact = ledger.getParent().resolve(saved.path("terminal").get(0).path("relative_path").asText());
        Files.writeString(artifact, "{}\n");
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options, (slot, scratch) -> row(slot)))
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
                (slot, scratch) -> row(slot));
        ObjectNode saved = JsonHashes.mapper().readValue(Files.readString(ledger), ObjectNode.class);
        Path artifact = ledger.getParent().resolve(saved.path("terminal").get(0).path("relative_path").asText());
        Files.delete(artifact);
        assertThatThrownBy(() -> StrategyOperatingCharacteristicsParallelV1.runWithExecutor(options,
                (slot, scratch) -> row(slot)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
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
        });
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
                .put("test_probe_override", true).put("available_cpus", 28).put("available_memory_bytes", 32L * 1024 * 1024 * 1024)
                .put("available_free_space_bytes", 256L * 1024 * 1024 * 1024).put("requested_workers", workers));
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
