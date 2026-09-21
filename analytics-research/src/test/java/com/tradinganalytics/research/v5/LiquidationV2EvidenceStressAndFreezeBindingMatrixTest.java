package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises calculation-only stress gates and frozen-input rejection from an accepted fixture. */
class LiquidationV2EvidenceStressAndFreezeBindingMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final List<String> CONTROL_IDS = List.of(
            ROUTED,
            "liquidation-v2-core-always-continuation-one-entry",
            "liquidation-v2-core-always-reversal-one-entry");

    @Test
    void acceptedStressBaselineUsesHashBoundSyntheticRunnerArtifacts(@TempDir Path runRoot) throws Exception {
        Fixture fixture = fixture(runRoot);
        LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(fixture.replay(), runRoot);
        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen());
        assertTrue(evidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean());
        for (JsonNode required : fixture.frozen().precommit().path("experiment").path("acceptance")
                .path("stress").path("required_scenarios")) {
            assertTrue(evidence.path("acceptance_gates").path("five_frozen_stress_gates")
                    .path(required.path("id").asText()).asBoolean(false), required.path("id").asText());
        }
        assertTrue(LiquidationV2ReplayEvidenceV1.validate(fixture.replay(), fixture.frozen(), evidence));
    }

    @Test
    void stressEnvelopeBindingsFailClosedIndividually(@TempDir Path runRoot) throws Exception {
        Fixture fixture = fixture(runRoot);
        ObjectNode baseline = LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen());
        assertTrue(baseline.path("acceptance_gates").path("all_five_stresses_pass").asBoolean());

        List<EnvelopeMutation> mutations = List.of(
                new EnvelopeMutation("missing evaluation", replay -> replay.remove("stress_evaluation"), false),
                new EnvelopeMutation("wrong schema", replay -> evaluation(replay).put("schema", "wrong/1"), true),
                new EnvelopeMutation("wrong base ledger", replay -> evaluation(replay).put("base_ledger_sha256", "f".repeat(64)), true),
                new EnvelopeMutation("wrong status", replay -> evaluation(replay).put("status", "CALLER_ASSERTED_PASS"), true),
                new EnvelopeMutation("not physically rerun", replay -> evaluation(replay)
                        .put("all_scenarios_rerun_from_physical_observations", false), true),
                new EnvelopeMutation("post-hoc pnl adjustment", replay -> evaluation(replay)
                        .put("no_posthoc_pnl_adjustment", false), true),
                new EnvelopeMutation("wrong scenario policy", replay -> evaluation(replay)
                        .put("frozen_scenario_policy_sha256", "a".repeat(64)), true),
                new EnvelopeMutation("wrong transform policy", replay -> evaluation(replay)
                        .put("stress_policy_sha256", "a".repeat(64)), true),
                new EnvelopeMutation("missing scenario", replay -> ((ArrayNode) evaluation(replay)
                        .path("scenario_results")).remove(0), true),
                new EnvelopeMutation("wrong evaluation type", replay -> replay.put("stress_evaluation", "not-object"), false),
                new EnvelopeMutation("tampered evaluation self hash", replay -> evaluation(replay)
                        .put("content_sha256", "0".repeat(64)), false));

        for (EnvelopeMutation mutation : mutations) {
            ObjectNode replay = fixture.replay().deepCopy();
            mutation.apply().accept(replay);
            sealStressAndReplay(replay, mutation.sealEvaluation());
            ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, fixture.frozen());
            assertFalse(evidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean(),
                    mutation.name());
            for (JsonNode required : fixture.frozen().precommit().path("experiment").path("acceptance")
                    .path("stress").path("required_scenarios")) {
                assertFalse(evidence.path("acceptance_gates").path("five_frozen_stress_gates")
                        .path(required.path("id").asText()).asBoolean(false), mutation.name());
            }
        }
    }

    @Test
    void stressScenarioIdsRemainUniqueAndExactlyFrozen(@TempDir Path runRoot) throws Exception {
        Fixture fixture = fixture(runRoot);
        ObjectNode duplicate = fixture.replay().deepCopy();
        String firstId = evaluation(duplicate).path("scenario_results").get(0).path("scenario_id").asText();
        scenario(duplicate, "funding_carry").put("scenario_id", firstId);
        sealStressAndReplay(duplicate, true);
        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(duplicate, fixture.frozen()));
        assertTrue(duplicateError.getMessage().contains("stress scenario IDs must be unique and non-empty"),
                duplicateError.getMessage());

        ObjectNode undeclared = fixture.replay().deepCopy();
        scenario(undeclared, "funding_carry").put("scenario_id", "unfrozen_scenario");
        sealStressAndReplay(undeclared, true);
        IllegalArgumentException extraError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(undeclared, fixture.frozen()));
        assertTrue(extraError.getMessage().contains("stress result contains an undeclared scenario"),
                extraError.getMessage());
    }

    @Test
    void eachRequiredScenarioRowMustCarryTypedBoundArtifactAndThresholdFields(@TempDir Path runRoot)
            throws Exception {
        Fixture fixture = fixture(runRoot);
        assertTrue(LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen())
                .path("acceptance_gates").path("all_five_stresses_pass").asBoolean());
        List<ScenarioMutation> mutations = List.of(
                new ScenarioMutation("artifact missing", row -> row.remove("scenario_run_artifact")),
                new ScenarioMutation("artifact schema", row -> artifact(row).put("schema", "wrong/1")),
                new ScenarioMutation("artifact blank path", row -> artifact(row).put("relative_path", " ")),
                new ScenarioMutation("artifact traversal", row -> artifact(row).put("relative_path", "scenario-runs/../outside.json")),
                new ScenarioMutation("artifact absolute path", row -> artifact(row).put("relative_path", "/scenario-runs/x.json")),
                new ScenarioMutation("artifact windows path", row -> artifact(row).put("relative_path", "scenario-runs\\\\x.json")),
                new ScenarioMutation("artifact colon path", row -> artifact(row).put("relative_path", "scenario-runs/x:y.json")),
                new ScenarioMutation("artifact outside scenario-runs", row -> artifact(row).put("relative_path", "other/x.json")),
                new ScenarioMutation("artifact malformed digest", row -> artifact(row).put("sha256", "A".repeat(64))),
                new ScenarioMutation("artifact digest differs from output", row -> artifact(row).put("sha256", "a".repeat(64))),
                new ScenarioMutation("runner result digest malformed", row -> row.put("runner_result_sha256", "bad")),
                new ScenarioMutation("run content digest malformed", row -> row.put("scenario_run_content_sha256", "bad")),
                new ScenarioMutation("runner ledger digest malformed", row -> row.put("runner_ledger_sha256", "bad")),
                new ScenarioMutation("runner event digest malformed", row -> row.put("runner_event_stream_sha256", "bad")),
                new ScenarioMutation("transform count fractional", row -> row.put("transform_count", 1.5)),
                new ScenarioMutation("transform count negative", row -> row.put("transform_count", -1)),
                new ScenarioMutation("transform count text", row -> row.put("transform_count", "1")),
                new ScenarioMutation("transform chain digest malformed", row -> row.put("transform_chain_sha256", "bad")),
                new ScenarioMutation("completed count fractional", row -> row.put("completed_observations", 31.5)),
                new ScenarioMutation("completed count negative", row -> row.put("completed_observations", -1)),
                new ScenarioMutation("expectancy not numeric", row -> row.put("expectancy_r", "0.1")),
                new ScenarioMutation("unresolved count fractional", row -> row.put("unresolved_count", 0.5)),
                new ScenarioMutation("coverage count not integral", row -> row.put("coverage_blocked_count", "0")),
                new ScenarioMutation("affected count negative", row -> row.put("affected_position_count", -1)),
                new ScenarioMutation("minimum observations not integral", row -> row.put("minimum_observations", 30.5)),
                new ScenarioMutation("minimum observations differs from frozen", row -> row.put("minimum_observations",
                        row.path("minimum_observations").asInt() + 1)),
                new ScenarioMutation("minimum expectancy not numeric", row -> row.put("minimum_expectancy_r", "0")),
                new ScenarioMutation("minimum expectancy differs from frozen", row -> row.put("minimum_expectancy_r",
                        row.path("minimum_expectancy_r").asDouble() + 0.01)),
                new ScenarioMutation("development scope is not asserted", row -> row.put("development_only", false)),
                new ScenarioMutation("runner gate label is not recomputed", row -> row.put("candidate_gate_status", "PASS")));

        for (ScenarioMutation mutation : mutations) {
            ObjectNode replay = fixture.replay().deepCopy();
            mutation.apply().accept(scenario(replay, "fee_slippage"));
            sealStressAndReplay(replay, true);
            ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, fixture.frozen());
            assertFalse(evidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean(), mutation.name());
            assertFalse(evidence.path("acceptance_gates").path("five_frozen_stress_gates")
                    .path("fee_slippage").asBoolean(false), mutation.name());
        }
    }

    @Test
    void routedSummaryRequiresExactlyTheThreeTradeArmsAndMatchingTopLevelMetrics(@TempDir Path runRoot)
            throws Exception {
        Fixture fixture = fixture(runRoot);
        ObjectNode validEvidence = LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen());
        assertTrue(validEvidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean());
        List<SummaryMutation> mutations = new ArrayList<>();
        mutations.add(new SummaryMutation("summary missing", row -> row.remove("by_candidate")));
        mutations.add(new SummaryMutation("summary has too few arms", row -> ((ArrayNode) row.path("by_candidate")).remove(0)));
        mutations.add(new SummaryMutation("blank arm identity", row -> candidate(row, ROUTED).put("candidate_id", " ")));
        mutations.add(new SummaryMutation("duplicate arm identity", row -> candidate(row,
                CONTROL_IDS.get(1)).put("candidate_id", ROUTED)));
        mutations.add(new SummaryMutation("completed count is text", row -> candidate(row, ROUTED).put("completed_observations", "32")));
        mutations.add(new SummaryMutation("expectancy is text", row -> candidate(row, ROUTED).put("expectancy_r", "0.01")));
        mutations.add(new SummaryMutation("open count is fractional", row -> candidate(row, ROUTED).put("open_unresolved_count", 0.5)));
        mutations.add(new SummaryMutation("coverage count is negative", row -> candidate(row, ROUTED).put("coverage_blocked_count", -1)));
        mutations.add(new SummaryMutation("unresolved total is inconsistent", row -> candidate(row, ROUTED).put("unresolved_count", 1)));
        mutations.add(new SummaryMutation("affected count is fractional", row -> candidate(row, ROUTED).put("affected_position_count", 1.5)));
        mutations.add(new SummaryMutation("wrong frozen arm identity", row -> candidate(row,
                CONTROL_IDS.get(2)).put("candidate_id", "not-a-frozen-candidate")));
        mutations.add(new SummaryMutation("routed arm absent", row -> candidate(row, ROUTED).put("candidate_id", "not-routed")));
        mutations.add(new SummaryMutation("top-level count differs", row -> row.put("completed_observations",
                row.path("completed_observations").asInt() + 1)));
        mutations.add(new SummaryMutation("top-level expectancy differs", row -> row.put("expectancy_r",
                row.path("expectancy_r").asDouble() + 0.1)));
        mutations.add(new SummaryMutation("top-level unresolved differs", row -> row.put("unresolved_count", 1)));
        mutations.add(new SummaryMutation("top-level coverage differs", row -> row.put("coverage_blocked_count", 1)));
        mutations.add(new SummaryMutation("top-level affected differs", row -> row.put("affected_position_count",
                row.path("affected_position_count").asInt() + 1)));

        for (SummaryMutation mutation : mutations) {
            ObjectNode replay = fixture.replay().deepCopy();
            mutation.apply().accept(scenario(replay, "fee_slippage"));
            sealStressAndReplay(replay, true);
            // These are in-memory metric checks; the accepted runner artifacts remain byte/hash bound.
            LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(replay, runRoot);
            ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, fixture.frozen());
            assertFalse(evidence.path("acceptance_gates").path("all_five_stresses_pass").asBoolean(), mutation.name());
            assertFalse(evidence.path("acceptance_gates").path("five_frozen_stress_gates")
                    .path("fee_slippage").asBoolean(false), mutation.name());
        }
    }

    @Test
    void scenarioGateDistinguishesBelowThresholdUnresolvedAndOutageWithoutImpact(@TempDir Path runRoot)
            throws Exception {
        Fixture fixture = fixture(runRoot);
        ObjectNode good = LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen());
        assertTrue(good.path("acceptance_gates").path("five_frozen_stress_gates").path("fee_slippage").asBoolean());

        ObjectNode insufficient = fixture.replay().deepCopy();
        ObjectNode row = scenario(insufficient, "fee_slippage");
        ObjectNode routed = candidate(row, ROUTED);
        int minimum = row.path("minimum_observations").asInt();
        row.put("completed_observations", minimum - 1);
        routed.put("completed_observations", minimum - 1);
        row.put("candidate_gate_status", "INSUFFICIENT_OR_BELOW_THRESHOLD");
        sealStressAndReplay(insufficient, true);
        assertFalse(LiquidationV2ReplayEvidenceV1.build(insufficient, fixture.frozen())
                .path("acceptance_gates").path("five_frozen_stress_gates").path("fee_slippage").asBoolean(false));

        ObjectNode lowExpectancy = fixture.replay().deepCopy();
        row = scenario(lowExpectancy, "funding_carry"); routed = candidate(row, ROUTED);
        double below = row.path("minimum_expectancy_r").asDouble() - 1.0;
        row.put("expectancy_r", below); routed.put("expectancy_r", below);
        row.put("candidate_gate_status", "INSUFFICIENT_OR_BELOW_THRESHOLD");
        sealStressAndReplay(lowExpectancy, true);
        assertFalse(LiquidationV2ReplayEvidenceV1.build(lowExpectancy, fixture.frozen())
                .path("acceptance_gates").path("five_frozen_stress_gates").path("funding_carry").asBoolean(false));

        ObjectNode unresolved = fixture.replay().deepCopy();
        row = scenario(unresolved, "adverse_execution_gap"); routed = candidate(row, ROUTED);
        row.put("unresolved_count", 1).put("coverage_blocked_count", 0)
                .put("candidate_gate_status", "BLOCKED_UNRESOLVED_OR_COVERAGE");
        routed.put("open_unresolved_count", 1).put("unresolved_count", 1);
        sealStressAndReplay(unresolved, true);
        assertFalse(LiquidationV2ReplayEvidenceV1.build(unresolved, fixture.frozen())
                .path("acceptance_gates").path("five_frozen_stress_gates").path("adverse_execution_gap").asBoolean(false));

        ObjectNode blocked = fixture.replay().deepCopy();
        row = scenario(blocked, "liquidity_capacity"); routed = candidate(row, ROUTED);
        row.put("unresolved_count", 1).put("coverage_blocked_count", 1)
                .put("candidate_gate_status", "BLOCKED_UNRESOLVED_OR_COVERAGE");
        routed.put("open_unresolved_count", 0).put("coverage_blocked_count", 1).put("unresolved_count", 1);
        sealStressAndReplay(blocked, true);
        assertFalse(LiquidationV2ReplayEvidenceV1.build(blocked, fixture.frozen())
                .path("acceptance_gates").path("five_frozen_stress_gates").path("liquidity_capacity").asBoolean(false));

        ObjectNode outageNoImpact = fixture.replay().deepCopy();
        row = scenario(outageNoImpact, "venue_outage_blackout");
        row.put("affected_position_count", 0);
        for (String id : CONTROL_IDS) candidate(row, id).put("affected_position_count", 0);
        sealStressAndReplay(outageNoImpact, true);
        assertFalse(LiquidationV2ReplayEvidenceV1.build(outageNoImpact, fixture.frozen())
                .path("acceptance_gates").path("five_frozen_stress_gates").path("venue_outage_blackout").asBoolean(false));
    }

    @Test
    void frozenManifestExecutorAndInventoryContractsRejectRehashedMutations(@TempDir Path runRoot) throws Exception {
        Fixture fixture = fixture(runRoot);
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen()));

        List<FreezeMutation> manifestMutations = List.of(
                new FreezeMutation("manifest schema", freeze -> freeze.manifest().put("schema", "wrong/1"),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("manifest version", freeze -> freeze.manifest().put("version", 2),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("unsupported source mode", freeze -> freeze.manifest().put("source_mode", "AUTHORITATIVE"),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("profile reference", freeze -> freeze.manifest().put("profile_sha256", "f".repeat(64)),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("precommit reference", freeze -> freeze.manifest().put("precommit_sha256", "f".repeat(64)),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("authoritative flag", freeze -> freeze.manifest().put("authoritative", true),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("authoritative evaluator", freeze -> freeze.manifest().put("authoritative_evaluation_permitted", true),
                        "physical manifest is not a bound v002 development manifest"),
                new FreezeMutation("synthetic status", freeze -> freeze.manifest().put("status", "DEVELOPMENT_PROXY_DISCLOSED"),
                        "physical manifest status does not match its frozen source mode"),
                new FreezeMutation("executor schema", freeze -> freeze.executor().put("schema", "wrong/1"),
                        "executor identity does not match the frozen v002 capability"),
                new FreezeMutation("executor version", freeze -> freeze.executor().put("version", 2),
                        "executor identity does not match the frozen v002 capability"),
                new FreezeMutation("executor capability", freeze -> freeze.executor().put("capability", "caller-asserted"),
                        "executor identity does not match the frozen v002 capability"),
                new FreezeMutation("executor macro policy", freeze -> freeze.executor()
                        .put("default_stage_add_macro_gate_policy", "NOT_REQUIRED"),
                        "executor identity does not match the frozen v002 capability"),
                new FreezeMutation("inventory candidate definition", freeze -> ((ObjectNode) freeze.inventory()
                        .path("current_candidates").get(0)).put("variant", "ALWAYS_CONTINUATION_CONTROL"),
                        "candidate inventory differs from the exact frozen four-variant inventory"));
        for (FreezeMutation mutation : manifestMutations) {
            MutableFreeze changed = new MutableFreeze(fixture.frozen().manifest().deepCopy(),
                    fixture.frozen().executorIdentity().deepCopy(), fixture.frozen().candidateInventory().deepCopy());
            mutation.apply().accept(changed);
            rehash(changed.selected(mutation.name()));
            LiquidationV2ReplayEvidenceV1.FreezeInputs bad = new LiquidationV2ReplayEvidenceV1.FreezeInputs(
                    changed.manifest(), fixture.frozen().precommit(), fixture.frozen().profile(), changed.executor(),
                    changed.inventory(), fixture.frozen().priorFamilyInventory(), fixture.frozen().parentPrecommit(),
                    fixture.frozen().parentFreezeManifest(), fixture.frozen().parentFreezeManifestBytes(),
                    fixture.frozen().parentFeasibilityMarkdown());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2ReplayEvidenceV1.build(fixture.replay(), bad), mutation.name());
            assertTrue(error.getMessage().contains(mutation.expected()), mutation.name() + ": " + error.getMessage());
        }
    }

    @Test
    void precommitAndProfileSelfBindingRejectTamperedFrozenObjects(@TempDir Path runRoot) throws Exception {
        Fixture fixture = fixture(runRoot);
        ObjectNode changedPrecommit = fixture.frozen().precommit().deepCopy().put("schema", "wrong/1");
        rehash(changedPrecommit);
        LiquidationV2ReplayEvidenceV1.FreezeInputs changedCommit = new LiquidationV2ReplayEvidenceV1.FreezeInputs(
                fixture.frozen().manifest(), changedPrecommit, fixture.frozen().profile(),
                fixture.frozen().executorIdentity(), fixture.frozen().candidateInventory(),
                fixture.frozen().priorFamilyInventory(), fixture.frozen().parentPrecommit(),
                fixture.frozen().parentFreezeManifest(), fixture.frozen().parentFreezeManifestBytes(),
                fixture.frozen().parentFeasibilityMarkdown());
        IllegalArgumentException commitFailure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(fixture.replay(), changedCommit));
        assertTrue(commitFailure.getMessage().contains("replay precommit is not the frozen liquidation v002 premise"),
                commitFailure.getMessage());

        ObjectNode changedProfile = fixture.frozen().profile().deepCopy().put("trade_authorization_permitted", true);
        rehash(changedProfile);
        LiquidationV2ReplayEvidenceV1.FreezeInputs changedPolicy = new LiquidationV2ReplayEvidenceV1.FreezeInputs(
                fixture.frozen().manifest(), fixture.frozen().precommit(), changedProfile,
                fixture.frozen().executorIdentity(), fixture.frozen().candidateInventory(),
                fixture.frozen().priorFamilyInventory(), fixture.frozen().parentPrecommit(),
                fixture.frozen().parentFreezeManifest(), fixture.frozen().parentFreezeManifestBytes(),
                fixture.frozen().parentFeasibilityMarkdown());
        IllegalArgumentException profileFailure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(fixture.replay(), changedPolicy));
        assertTrue(profileFailure.getMessage().contains("v002 profile differs from the frozen capability contract"),
                profileFailure.getMessage());

        ObjectNode unsealedManifest = fixture.frozen().manifest().deepCopy().put("status", "MUTATED");
        LiquidationV2ReplayEvidenceV1.FreezeInputs unsealed = new LiquidationV2ReplayEvidenceV1.FreezeInputs(
                unsealedManifest, fixture.frozen().precommit(), fixture.frozen().profile(),
                fixture.frozen().executorIdentity(), fixture.frozen().candidateInventory(),
                fixture.frozen().priorFamilyInventory(), fixture.frozen().parentPrecommit(),
                fixture.frozen().parentFreezeManifest(), fixture.frozen().parentFreezeManifestBytes(),
                fixture.frozen().parentFeasibilityMarkdown());
        IllegalArgumentException hashFailure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(fixture.replay(), unsealed));
        assertTrue(hashFailure.getMessage().contains("physical manifest content hash does not match its reopened object"),
                hashFailure.getMessage());
    }

    @Test
    void closedCurrentPositionSuppliesPaidCostAndFundingCreditsAreNotNetted(@TempDir Path runRoot) throws Exception {
        Fixture fixture = closedTradeFixture(runRoot);
        ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(fixture.replay(), fixture.frozen());
        assertEquals(.15, evidence.path("robust_trade_statistics").path("mean_cost_r").asDouble(), 1e-12,
                "the current account positions array retains the just-closed episode; only paid funding debits count");

        ObjectNode archivedWins = fixture.replay().deepCopy();
        ObjectNode account = routedAccount(archivedWins);
        ObjectNode archived = ((ArrayNode) account.path("closed_episodes")).addObject()
                .put("active_setup_id", "cost-setup").put("asset", "BTC")
                .put("first_fill_time", Instant.parse("2024-01-20T00:01:00Z").toEpochMilli())
                .put("status", "CLOSED").put("entry_costs_usdt", 40.0)
                .put("exit_costs_usdt", 40.0).put("funding_debits_for_risk_headroom_usdt", 20.0);
        archived.putArray("exits").addObject().put("exit_time", "2024-01-21T00:00:00Z");
        resealReplayWithBoundRuns(archivedWins, fixture, runRoot);
        ObjectNode archivedEvidence = LiquidationV2ReplayEvidenceV1.build(archivedWins, fixture.frozen());
        assertEquals(.5, archivedEvidence.path("robust_trade_statistics").path("mean_cost_r").asDouble(), 1e-12,
                "an archived matching position episode takes precedence over the current-position fallback");
    }

    @Test
    void closedPositionCostRequiresExactEpisodeIdentityAndEveryPaidCostComponent(@TempDir Path runRoot)
            throws Exception {
        Fixture fixture = closedTradeFixture(runRoot);
        List<LedgerMutation> mutations = List.of(
                new LedgerMutation("missing routed account", replay -> ((ArrayNode) replay.path("ledger")
                        .path("accounts")).remove(0)),
                new LedgerMutation("missing archived episode array", replay -> routedAccount(replay).remove("closed_episodes")),
                new LedgerMutation("missing current position array", replay -> routedAccount(replay).remove("positions")),
                new LedgerMutation("current position remains open", replay -> currentPosition(replay).put("status", "OPEN")),
                new LedgerMutation("current position has no exit", replay -> currentPosition(replay).remove("exits")),
                new LedgerMutation("setup identity differs", replay -> currentPosition(replay).put("active_setup_id", "other-setup")),
                new LedgerMutation("asset identity differs", replay -> currentPosition(replay).put("asset", "ETH")),
                new LedgerMutation("fill identity has wrong type", replay -> currentPosition(replay)
                        .put("first_fill_time", "1705708860000")),
                new LedgerMutation("entry costs missing", replay -> currentPosition(replay).remove("entry_costs_usdt")),
                new LedgerMutation("entry costs negative", replay -> currentPosition(replay).put("entry_costs_usdt", -1.0)),
                new LedgerMutation("exit costs wrong type", replay -> currentPosition(replay).put("exit_costs_usdt", "10")),
                new LedgerMutation("funding debit missing", replay -> currentPosition(replay)
                        .remove("funding_debits_for_risk_headroom_usdt")),
                new LedgerMutation("funding debit negative", replay -> currentPosition(replay)
                        .put("funding_debits_for_risk_headroom_usdt", -1.0)));
        for (LedgerMutation mutation : mutations) {
            ObjectNode replay = fixture.replay().deepCopy();
            mutation.apply().accept(replay);
            resealReplayWithBoundRuns(replay, fixture, runRoot);
            ObjectNode evidence = LiquidationV2ReplayEvidenceV1.build(replay, fixture.frozen());
            assertTrue(evidence.path("robust_trade_statistics").path("mean_cost_r").isNull(), mutation.name());
            assertTrue(evidence.path("acceptance_gates").path("maximum_cost_r").isNull(), mutation.name());
        }
    }

    private static Fixture closedTradeFixture(Path runRoot) throws Exception {
        Fixture base = fixture(runRoot);
        ObjectNode replay = base.replay().deepCopy();
        Instant decision = Instant.parse("2024-01-20T00:00:00Z");
        String pairId = "cost-pair";
        ArrayNode opportunities = (ArrayNode) replay.path("opportunities");
        for (JsonNode candidate : base.frozen().candidateInventory().path("current_candidates")) {
            String id = candidate.path("candidate_id").asText();
            String variant = candidate.path("variant").asText();
            if ("PRICE_OI_ONLY_EVENT_DIAGNOSTIC".equals(variant)) continue;
            String branch = "ALWAYS_REVERSAL_CONTROL".equals(variant) ? "REVERSAL" : "CONTINUATION";
            Instant fill = decision.plusSeconds(60), exit = decision.plusSeconds(86_400);
            opportunities.addObject().put("opportunity_id", pairId + "::" + id).put("pair_id", pairId)
                    .put("candidate_id", id).put("variant", variant).put("asset", "BTC")
                    .put("decision_time", decision.toString()).put("branch", branch).put("direction", "LONG")
                    .put("outcome_state", "CLOSED_TRADE").put("outcome_available_time", exit.toString())
                    .put("first_fill_time", fill.toString()).put("exit_time", exit.toString())
                    .put("net_pnl_usdt", 20.0).put("reference_risk_usdt", 200.0)
                    .put("setup_id", "cost-setup").putArray("reason_codes");
        }
        for (JsonNode raw : replay.path("evaluated_candidates")) {
            if (((ObjectNode) raw).path("audit_only").asBoolean()) ((ObjectNode) raw).put("opportunity_count", 0);
            else ((ObjectNode) raw).put("opportunity_count", 1);
        }
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes");
        ObjectNode current = account.putArray("positions").addObject()
                .put("status", "CLOSED").put("active_setup_id", "cost-setup").put("asset", "BTC")
                .put("first_fill_time", decision.plusSeconds(60).toEpochMilli())
                .put("entry_costs_usdt", 10.0).put("exit_costs_usdt", 10.0)
                .put("funding_debits_for_risk_headroom_usdt", 10.0).put("funding_pnl_usdt", -10_000.0);
        current.putArray("exits").addObject().put("exit_time", decision.plusSeconds(86_400).toString());
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", ROUTED).set("account", account);
        rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        rehash(replay);
        resealReplayWithBoundRuns(replay, base, runRoot);
        return new Fixture(base.frozen(), replay, runRoot);
    }

    private static ObjectNode routedAccount(ObjectNode replay) {
        for (JsonNode row : replay.path("ledger").path("accounts")) {
            if (ROUTED.equals(row.path("candidate_id").asText())) return (ObjectNode) row.path("account");
        }
        throw new AssertionError("routed account not found");
    }

    private static ObjectNode currentPosition(ObjectNode replay) {
        return (ObjectNode) routedAccount(replay).path("positions").get(0);
    }

    private static void resealReplayWithBoundRuns(ObjectNode replay, Fixture fixture, Path runRoot) throws Exception {
        ObjectNode ledger = (ObjectNode) replay.path("ledger");
        rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        replay.remove("stress_evaluation");
        attachValidStressRuns(replay, fixture.frozen(), runRoot);
    }

    private static Fixture fixture(Path runRoot) throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode precommit = read(repositoryRoot().resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"));
        ObjectNode manifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-physical-manifest/1").put("version", 1)
                .put("status", "DEVELOPMENT_SYNTHETIC").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("authoritative", false).put("authoritative_evaluation_permitted", false);
        rehash(manifest);
        ObjectNode executor = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EXECUTOR_IDENTITY_SCHEMA).put("version", 1)
                .put("capability", profile.path("executor_capability").asText())
                .put("default_stage_add_macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        rehash(executor);
        ObjectNode inventory = LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(profile);
        Path root = repositoryRoot();
        ObjectNode parent = read(root.resolve("docs/research/liquidation-structure-v001/frozen-precommit.json"));
        Path manifestPath = root.resolve("docs/research/liquidation-structure-v001/FREEZE-MANIFEST.json");
        String manifestBytes = Files.readString(manifestPath);
        ObjectNode parentManifest = (ObjectNode) JsonHashes.mapper().readTree(manifestBytes);
        String feasibility = Files.readString(root.resolve("docs/research/liquidation-structure-v001/FEASIBILITY.md"));
        LiquidationV2ReplayEvidenceV1.FreezeInputs frozen = new LiquidationV2ReplayEvidenceV1.FreezeInputs(
                manifest, precommit, profile, executor, inventory, null, parent, parentManifest, manifestBytes, feasibility);
        ObjectNode replay = blankReplay(frozen);
        attachValidStressRuns(replay, frozen, runRoot);
        return new Fixture(frozen, replay, runRoot);
    }

    private static ObjectNode blankReplay(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1);
        ObjectNode refs = replay.putObject("freeze");
        refs.put("manifest_sha256", frozen.manifest().path("content_sha256").asText())
                .put("precommit_sha256", frozen.precommit().path("content_sha256").asText())
                .put("profile_sha256", frozen.profile().path("content_sha256").asText())
                .put("executor_sha256", frozen.executorIdentity().path("content_sha256").asText())
                .put("candidate_inventory_sha256", frozen.candidateInventory().path("content_sha256").asText())
                .put("source_mode", frozen.manifest().path("source_mode").asText())
                .put("parent_precommit_sha256", frozen.parentPrecommit().path("content_sha256").asText())
                .put("parent_freeze_manifest_byte_sha256", JsonHashes.sha256(frozen.parentFreezeManifestBytes()))
                .put("parent_feasibility_byte_sha256", JsonHashes.sha256(frozen.parentFeasibilityMarkdown()));
        byte[] eventBytes = JsonHashes.canonicalBytes(JsonHashes.mapper().createArrayNode());
        replay.put("event_stream_sha256", JsonHashes.sha256(eventBytes)).put("event_count", 0);
        ArrayNode curve = replay.putArray("account_curve");
        curve.addObject().put("time", 1_700_000_000_000L).put("equity_usdt", "20000.00");
        replay.put("account_curve_sha256", JsonHashes.canonicalSha256(curve))
                .put("account_curve_scope", "SYNTHETIC_TEST_MARKS");
        ObjectNode ledger = replay.putObject("ledger"); ledger.putArray("accounts"); rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        replay.putArray("opportunities");
        ArrayNode evaluated = replay.putArray("evaluated_candidates");
        for (JsonNode candidate : frozen.candidateInventory().path("current_candidates")) {
            evaluated.addObject().put("candidate_id", candidate.path("candidate_id").asText())
                    .put("variant", candidate.path("variant").asText())
                    .put("audit_only", candidate.path("audit_only").asBoolean())
                    .put("execution_scope", "SYNTHETIC_FIXTURE_EXECUTED")
                    .put("opportunity_count", 0).put("executed", true);
        }
        rehash(replay);
        return replay;
    }

    private static void attachValidStressRuns(ObjectNode replay,
            LiquidationV2ReplayEvidenceV1.FreezeInputs frozen, Path runRoot) throws Exception {
        JsonNode required = frozen.precommit().path("experiment").path("acceptance").path("stress").path("required_scenarios");
        String eventHash = replay.path("event_stream_sha256").asText();
        String ledgerHash = replay.path("ledger_sha256").asText();
        String policyHash = LiquidationV2StressPolicyV1.reopen(frozen.precommit()).contentSha256();
        ObjectNode evaluation = replay.putObject("stress_evaluation")
                .put("schema", "liquidation-v2-stress-evaluation/1").put("status", "RUNNER_EXECUTED_DEVELOPMENT_ONLY")
                .put("base_ledger_sha256", ledgerHash)
                .put("frozen_scenario_policy_sha256", JsonHashes.canonicalSha256(required))
                .put("stress_policy_sha256", policyHash)
                .put("all_scenarios_rerun_from_physical_observations", true).put("no_posthoc_pnl_adjustment", true);
        ArrayNode rows = evaluation.putArray("scenario_results");
        for (JsonNode rule : required) {
            String id = rule.path("id").asText();
            int count = Math.max(30, rule.path("minimum_observations").asInt());
            double expectancy = rule.path("minimum_expectancy_r").asDouble() + 0.05;
            ObjectNode row = rows.addObject().put("scenario_id", id).put("transform_count", 1)
                    .put("transform_chain_sha256", "1".repeat(64)).put("completed_observations", count)
                    .put("expectancy_r", expectancy).put("unresolved_count", 0).put("coverage_blocked_count", 0)
                    .put("affected_position_count", "venue_outage_blackout".equals(id) ? 1 : 0)
                    .put("minimum_observations", rule.path("minimum_observations").asInt())
                    .put("minimum_expectancy_r", rule.path("minimum_expectancy_r").asDouble())
                    .put("development_only", true).put("candidate_gate_status", "CANDIDATE_THRESHOLDS_MET_DEVELOPMENT_ONLY")
                    .put("outage_cancelled_entry_attempt_count", "venue_outage_blackout".equals(id) ? 1 : 0)
                    .put("outage_deferred_exit_trigger_count", 0).put("outage_blocked_stop_update_count", 0);
            ArrayNode summaries = row.putArray("by_candidate");
            for (String candidateId : CONTROL_IDS) {
                summaries.addObject().put("candidate_id", candidateId).put("completed_observations", count)
                        .put("expectancy_r", expectancy).put("open_unresolved_count", 0)
                        .put("coverage_blocked_count", 0).put("unresolved_count", 0)
                        .put("affected_position_count", "venue_outage_blackout".equals(id) ? 1 : 0);
            }
            ObjectNode execution = JsonHashes.mapper().createObjectNode().put("scenario_id", id)
                    .put("stress_policy_sha256", policyHash).put("transform_count", 1)
                    .put("transform_chain_sha256", "1".repeat(64));
            ObjectNode run = JsonHashes.mapper().createObjectNode()
                    .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                    .put("ledger_sha256", ledgerHash).put("event_stream_sha256", eventHash);
            run.set("scenario_execution", execution);
            rehash(run);
            byte[] runBytes = JsonHashes.canonicalBytes(run);
            Path relative = Path.of("scenario-runs", id + ".json");
            Path target = runRoot.resolve(relative);
            Files.createDirectories(target.getParent()); Files.write(target, runBytes);
            String resultHash = JsonHashes.sha256(runBytes);
            row.put("runner_result_sha256", resultHash).put("scenario_run_content_sha256", run.path("content_sha256").asText())
                    .put("runner_ledger_sha256", ledgerHash).put("runner_event_stream_sha256", eventHash);
            row.putObject("scenario_run_artifact").put("relative_path", relative.toString())
                    .put("sha256", resultHash).put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA);
        }
        rehash(evaluation); rehash(replay);
    }

    private static ObjectNode candidate(ObjectNode scenario, String id) {
        for (JsonNode row : scenario.path("by_candidate")) if (id.equals(row.path("candidate_id").asText())) return (ObjectNode) row;
        throw new AssertionError("candidate summary not found: " + id);
    }

    private static ObjectNode artifact(ObjectNode scenario) {
        JsonNode value = scenario.path("scenario_run_artifact");
        return value.isObject() ? (ObjectNode) value : scenario.putObject("scenario_run_artifact");
    }

    private static ObjectNode scenario(ObjectNode replay, String id) {
        for (JsonNode row : replay.path("stress_evaluation").path("scenario_results")) {
            if (id.equals(row.path("scenario_id").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("stress scenario not found: " + id);
    }

    private static ObjectNode evaluation(ObjectNode replay) { return (ObjectNode) replay.path("stress_evaluation"); }

    private static void sealStressAndReplay(ObjectNode replay, boolean sealEvaluation) {
        JsonNode evaluation = replay.path("stress_evaluation");
        if (sealEvaluation && evaluation.isObject()) rehash((ObjectNode) evaluation);
        rehash(replay);
    }

    private static void rehash(ObjectNode node) { node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node)); }

    private static ObjectNode read(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath().normalize();
        while (path != null && !Files.exists(path.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
            path = path.getParent();
        }
        if (path == null) throw new IllegalStateException("frozen v002 precommit is unavailable");
        return path;
    }

    private record Fixture(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen, ObjectNode replay, Path physicalRoot) {}
    private record EnvelopeMutation(String name, Consumer<ObjectNode> apply, boolean sealEvaluation) {}
    private record SummaryMutation(String name, Consumer<ObjectNode> apply) {}
    private record ScenarioMutation(String name, Consumer<ObjectNode> apply) {}
    private record LedgerMutation(String name, Consumer<ObjectNode> apply) {}
    private record FreezeMutation(String name, Consumer<MutableFreeze> apply, String expected) {}
    private record MutableFreeze(ObjectNode manifest, ObjectNode executor, ObjectNode inventory) {
        private ObjectNode selected(String name) {
            if (name.startsWith("manifest" ) || name.equals("unsupported source mode") || name.equals("profile reference")
                    || name.equals("precommit reference") || name.startsWith("authoritative") || name.equals("synthetic status")) return manifest;
            if (name.startsWith("executor")) return executor;
            return inventory;
        }
    }
}
