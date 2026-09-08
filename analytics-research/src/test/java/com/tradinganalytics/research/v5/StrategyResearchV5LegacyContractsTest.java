package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic contract coverage for the legacy V5 facade's custody boundaries. */
final class StrategyResearchV5LegacyContractsTest {
    private static final String AS_OF = "2026-08-24T12:30:00.000Z";
    private static final String DATASET = "a".repeat(64);

    @TempDir Path temporary;

    @Test
    void fiveYearPlanValidatorAcceptsFrozenPlanAndRejectsStructuralDrift() {
        ObjectNode plan = frozenPlan(true);
        assertThat(StrategyResearchV5.validateFiveYearPlan(plan)).isTrue();
        assertThat(plan.path("assets")).hasSize(8);
        assertThat(plan.path("series")).hasSize(32);
        assertThat(plan.path("optional_instruments").get(0).asText())
                .isEqualTo("BINANCE_USDM_DATED_FUTURE");

        ObjectNode noDated = frozenPlan(false);
        assertThat(StrategyResearchV5.validateFiveYearPlan(noDated)).isTrue();
        assertThat(noDated.path("series")).hasSize(24);
        assertThat(noDated.path("optional_instruments")).isEmpty();

        assertPlanFailure(plan, value -> value.put("status", "BROKEN"), "invalid v5 manifest status");
        assertPlanFailure(plan, value -> {
            ArrayNode assets = (ArrayNode) value.path("assets");
            assets.removeAll();
            assets.add("btc");
        },
                "must cover exactly eight assets");
        assertPlanFailure(plan, value -> ((ObjectNode) value.path("window")).put("years", 4),
                "must cover exactly eight assets");
        assertPlanFailure(plan, value -> ((ArrayNode) value.path("series")).removeAll(),
                "lacks completed-bar/PIT/dense-series contract");

        assertPlanFailure(plan, value -> firstSignal(value).put("completed_bars_only", false),
                "lacks completed-bar/PIT/dense-series contract");
        assertPlanFailure(plan, value -> firstFunding(value).put("completed_bars_only", true),
                "lacks completed-bar/PIT/dense-series contract");
        assertPlanFailure(plan, value -> firstSeries(value).put("require_availability_time", false),
                "lacks completed-bar/PIT/dense-series contract");
        assertPlanFailure(plan, value -> firstSeries(value).put("fee_schedule", ""),
                "lacks completed-bar/PIT/dense-series contract");
        assertPlanFailure(plan, value -> firstSeries(value).put("contract_specification", ""),
                "lacks completed-bar/PIT/dense-series contract");
        assertPlanFailure(plan, value -> firstSeries(value).put("expected_step_ms", "4h"),
                "lacks completed-bar/PIT/dense-series contract");
        assertPlanFailure(plan, value -> firstSeries(value).put("expected_event_count", "many"),
                "lacks completed-bar/PIT/dense-series contract");
    }

    @Test
    void planOnlyCoverageCannotSmuggleAcquiredArtifacts() {
        ObjectNode status = frozenPlan(true);
        assertPlanFailure(status,
                value -> ((ObjectNode) value.path("coverage")).put("status", "ACQUIRED"),
                "invalid partial/acquired contract");

        ObjectNode captures = frozenPlan(true);
        captures.putArray("captures");
        assertPlanFailure(captures, ignored -> {}, "invalid partial/acquired contract");

        ObjectNode artifacts = frozenPlan(true);
        ObjectNode coverage = (ObjectNode) artifacts.path("coverage");
        coverage.putArray("partitions").addObject().put("path", "partition.jsonl");
        assertPlanFailure(artifacts, ignored -> {}, "cannot contain acquired artifacts");

        ObjectNode receipts = frozenPlan(true);
        ((ObjectNode) receipts.path("coverage")).putArray("source_receipts")
                .addObject().put("path", "receipt.json");
        assertPlanFailure(receipts, ignored -> {}, "cannot contain acquired artifacts");
    }

    @Test
    void exposureGenesisAndPersistentHeadAppendAreCanonicalAndReplayable() throws Exception {
        String family = "contracts-exposure-" + temporary.getFileName();
        ObjectNode genesisOptions = exposureOptions(family, true);
        ObjectNode genesis = StrategyResearchV5.makeExposureLedgerV5(genesisOptions);

        assertThat(StrategyResearchV5.validateExposureLedgerV5(genesis)).isTrue();
        assertThat(genesis.path("genesis").asBoolean()).isTrue();
        assertThat(genesis.path("new_k").asInt()).isEqualTo(2);
        assertThat(genesis.path("cumulative_k").asInt()).isEqualTo(2);
        assertThat(genesis.path("behavior_aliases")).hasSize(2);
        assertThat(genesis.path("prior_head_sha256").isNull()).isTrue();

        ObjectNode appendOptions = exposureOptions(family, false);
        appendOptions.set("prior", genesis);
        appendOptions.set("behaviours", array(
                behavior("b".repeat(64), "duplicate"),
                behavior("d".repeat(64), "third")));
        ObjectNode appended = StrategyResearchV5.makeExposureLedgerV5(appendOptions);
        assertThat(appended.path("genesis").asBoolean()).isFalse();
        assertThat(appended.path("new_k").asInt()).isEqualTo(1);
        assertThat(appended.path("cumulative_k").asInt()).isEqualTo(3);
        assertThat(appended.path("prior_head_sha256").asText())
                .isEqualTo(genesis.path("content_sha256").asText());
        assertThat(StrategyResearchV5.validateExposureLedgerV5(appended)).isTrue();

        Path headRoot = temporary.resolve("exposure-head");
        ObjectNode persistedOptions = exposureOptions("contracts-persistent-" + temporary.getFileName(), true);
        persistedOptions.put("headRoot", headRoot.toString());
        ObjectNode persistedGenesis = StrategyResearchV5.makeExposureLedgerV5(persistedOptions);
        assertThat(Files.exists(headRoot.resolve(
                persistedOptions.path("hypothesisFamily").asText() + ".head.json"))).isTrue();

        ObjectNode persistedAppend = exposureOptions(
                persistedOptions.path("hypothesisFamily").asText(), false);
        persistedAppend.put("headRoot", headRoot.toString());
        persistedAppend.set("behaviours", array(behavior("e".repeat(64), "next")));
        ObjectNode persistedResult = StrategyResearchV5.makeExposureLedgerV5(persistedAppend);
        assertThat(persistedResult.path("prior_head_sha256").asText())
                .isEqualTo(persistedGenesis.path("content_sha256").asText());
        assertThat(persistedResult.path("new_k").asInt()).isEqualTo(1);
    }

    @Test
    void exposureLedgerRejectsCompetingPriorAndEveryChainPointerMutation() {
        String family = "contracts-exposure-invalid-" + temporary.getFileName();
        ObjectNode genesis = StrategyResearchV5.makeExposureLedgerV5(exposureOptions(family, true));

        assertThatThrownBy(() -> StrategyResearchV5.makeExposureLedgerV5(
                exposureOptions(family, true))).hasMessageContaining("competing genesis head");

        ObjectNode nonGenesis = exposureOptions(family, false);
        assertThatThrownBy(() -> StrategyResearchV5.makeExposureLedgerV5(nonGenesis))
                .hasMessageContaining("canonical prior head");

        ObjectNode stalePrior = genesis.deepCopy().put("competing_head_sha256", "b".repeat(64));
        stalePrior.put("content_sha256", StrategyResearchV5.ownHash(stalePrior));
        ObjectNode staleOptions = exposureOptions(family, false);
        staleOptions.set("prior", stalePrior);
        assertThatThrownBy(() -> StrategyResearchV5.makeExposureLedgerV5(staleOptions))
                .hasMessageContaining("stale or competing head");

        assertLedgerFailure(genesis, value -> value.put("status", "OPEN"), "K/status/root is invalid");
        assertLedgerFailure(genesis, value -> value.put("cumulative_k", 1), "K/status/root is invalid");
        assertLedgerFailure(genesis, value -> value.set("dataset_roots", array()), "K/status/root is invalid");
        assertLedgerFailure(genesis, value -> firstEntry(value).put("sequence", 2), "chain is broken");
        assertLedgerFailure(genesis, value -> firstEntry(value).put("previous_sha256", "b".repeat(64)),
                "chain is broken");
        assertLedgerFailure(genesis, value -> firstEntry(value).put("behavior_sha256", "not-a-hash"),
                "chain is broken");
        assertLedgerFailure(genesis, value -> firstEntry(value).put("dataset_root_sha256", "not-a-hash"),
                "chain is broken");
        assertLedgerFailure(genesis, value -> value.put("head_pointer_sha256", "b".repeat(64)),
                "head pointer is not canonical");
        assertLedgerFailure(genesis, value -> value.put("competing_head_sha256", "b".repeat(64)),
                "head pointer is not canonical");
        assertLedgerFailure(genesis, value -> value.set("behavior_aliases", array()),
                "aliases do not reconcile");
    }

    @Test
    void opportunityFacadeRequiresPhysicalAcquisitionAndRejectsUntrustedInputs() {
        ObjectNode plan = frozenPlan(false);
        ObjectNode candidateSet = candidateSet();
        ObjectNode options = object();
        options.set("candidateSet", candidateSet);
        options.set("manifest", plan);

        assertThatThrownBy(() -> StrategyResearchV5.makeOpportunityEnvelope(options))
                .hasMessageContaining("physically ACQUIRED manifest");
        ObjectNode untrusted = object();
        untrusted.set("candidateSet", object().put("schema", "untrusted"));
        assertThatThrownBy(() -> StrategyResearchV5.makeOpportunityEnvelope(untrusted))
                .hasMessageContaining("schema-bound v5 candidate set");

        ObjectNode tamperedCandidate = candidateSet.deepCopy();
        ((ObjectNode) tamperedCandidate.path("candidates").get(0).path("definition"))
                .put("threshold", "not-a-number");
        ObjectNode malformed = options.deepCopy();
        malformed.set("candidateSet", tamperedCandidate);
        assertThatThrownBy(() -> StrategyResearchV5.makeOpportunityEnvelope(malformed))
                .hasMessageContaining("v5 candidate set is not hash-valid");
    }

    private static ObjectNode frozenPlan(boolean includeDated) {
        ObjectNode options = object().put("asOf", AS_OF).put("includeDatedFutures", includeDated);
        return StrategyResearchV5.makeFiveYearBackfillPlan(options);
    }

    private static void assertPlanFailure(
            ObjectNode original, Consumer<ObjectNode> mutation, String message) {
        ObjectNode candidate = original.deepCopy();
        mutation.accept(candidate);
        candidate.put("content_sha256", StrategyResearchV5.ownHash(candidate));
        assertThatThrownBy(() -> StrategyResearchV5.validateFiveYearPlan(candidate))
                .hasMessageContaining(message);
    }

    private static ObjectNode exposureOptions(String family, boolean genesis) {
        ObjectNode options = object().put("hypothesisFamily", family)
                .put("datasetRootSha256", DATASET).put("genesis", genesis);
        options.set("behaviours", array(
                behavior("b".repeat(64), "first"),
                behavior("c".repeat(64), "second"),
                behavior("b".repeat(64), "duplicate")));
        return options;
    }

    private static ObjectNode behavior(String hash, String candidate) {
        return object().put("behavior_sha256", hash).put("candidate_id", candidate)
                .put("source", "CONTRACT_TEST");
    }

    private static ObjectNode candidateSet() {
        ObjectNode options = object();
        ObjectNode space = object();
        space.set("genes", array(object().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 1).put("step", 0.1).put("default", 0.5)));
        options.set("geneSpace", space);
        ObjectNode candidate = object().put("candidate_id", "candidate-1");
        candidate.set("definition", object().put("threshold", 0.6));
        candidate.set("behavior_vector", array(object().put("time", AS_OF).put("r", 1)));
        options.set("candidates", array(candidate));
        options.put("precommitSha256", "1".repeat(64));
        options.put("experimentSha256", "2".repeat(64));
        options.put("objectiveContractSha256", "3".repeat(64));
        options.put("acceptanceSha256", "4".repeat(64));
        return StrategyResearchV5.makeCandidateSetV5(options);
    }

    private static void assertLedgerFailure(
            ObjectNode original, Consumer<ObjectNode> mutation, String message) {
        ObjectNode candidate = original.deepCopy();
        mutation.accept(candidate);
        candidate.put("content_sha256", StrategyResearchV5.ownHash(candidate));
        assertThatThrownBy(() -> StrategyResearchV5.validateExposureLedgerV5(candidate))
                .hasMessageContaining(message);
    }

    private static ObjectNode firstEntry(ObjectNode ledger) {
        return (ObjectNode) ledger.path("entries").get(0);
    }

    private static ObjectNode firstSeries(ObjectNode plan) {
        return (ObjectNode) plan.path("series").get(0);
    }

    private static ObjectNode firstSignal(ObjectNode plan) {
        for (JsonNode row : plan.path("series")) {
            if ("signal_bars".equals(row.path("series_type").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("fixture has no signal series");
    }

    private static ObjectNode firstFunding(ObjectNode plan) {
        for (JsonNode row : plan.path("series")) {
            if ("funding_events".equals(row.path("series_type").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("fixture has no funding series");
    }

    private static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }

    private static ArrayNode array(ObjectNode... values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (ObjectNode value : values) result.add(value);
        return result;
    }
}
