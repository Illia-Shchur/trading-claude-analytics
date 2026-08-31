package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchV3NodeOracleTest {
    @Test
    void publicApiCoversEveryV3Export() {
        Set<String> methods = Arrays.stream(LegacyResearchV3.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(methods).contains(
                "stable", "hash", "ownHash", "withHash", "makeTrainingSelectionPolicy",
                "validateTrainingSelectionPolicy", "makeAcceptanceContract",
                "validateAcceptanceContract", "frozenSelectionByAsset", "validateExperimentV3",
                "makeExperimentV3", "blockBootstrap", "centredCandidateSetMaxStatistic",
                "computeCandidateMetrics", "evaluateAcceptance", "validateResearchDecision",
                "makeEvidenceBundle", "validateEvidenceBundleV2",
                "validateExposedParentEvidence", "makeRunV3", "validateRunV3",
                "validateWfoFolds", "walkForwardV3", "validateAuthoritativeData",
                "generateEd25519KeyPair", "makeConfirmationReservation",
                "validateConfirmationReservation", "burnReservation", "signAttestation",
                "verifyAttestation", "importAttestation");
        assertThat(LegacyResearchV3.CORE_UNIVERSE).containsExactly(
                "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
        assertThat(LegacyResearchV3.REQUIRED_STRESS_SCENARIOS).hasSize(5);
    }

    @Test
    void trainingPolicyAndAcceptanceContractAreCanonicalAndValid() {
        ObjectNode policyOptions = object()
                .put("minimumCompletedTrades", 7)
                .put("minimumExpectancyR", 0.15)
                .put("objective", "expectancy_r_desc")
                .put("tieBreak", "candidate_id_asc")
                .put("nestedSearchControl", "WFO_NESTED_SELECTION");
        ObjectNode policy = LegacyResearchV3.makeTrainingSelectionPolicy(policyOptions);
        assertThat(policy.path("minimum_completed_trades").asInt()).isEqualTo(7);
        assertThat(policy.path("minimum_expectancy_r").asDouble()).isEqualTo(.15);
        assertThat(policy.path("content_sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(LegacyResearchV3.validateTrainingSelectionPolicy(policy)).isTrue();

        ObjectNode contractOptions = object()
                .put("contractId", "fixture-contract")
                .put("profile", "balanced-swing-v1");
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract(contractOptions);
        assertThat(contract.path("contract_id").asText()).isEqualTo("fixture-contract");
        assertThat(contract.path("profile").asText()).isEqualTo("balanced-swing-v1");
        assertThat(contract.path("content_sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(LegacyResearchV3.validateAcceptanceContract(contract)).isTrue();
    }

    @Test
    void blockBootstrapAndSharedMaxStatisticAreDeterministic() {
        ArrayNode values = array().add(1).add(-0.2).add(0.4).add(0.8).add(-0.1);
        ObjectNode options = object().put("seed", 23).put("iterations", 80)
                .put("blockLength", 2);
        ObjectNode bootstrap = LegacyResearchV3.blockBootstrap(values, options);
        assertThat(bootstrap).isEqualTo(LegacyResearchV3.blockBootstrap(values, options));
        assertThat(bootstrap.path("iterations").asInt()).isEqualTo(80);

        ObjectNode candidates = object();
        candidates.set("a", array()
                .add(object().put("episode_id", "e1").put("net_r", 1))
                .add(object().put("episode_id", "e2").put("net_r", -0.25))
                .add(object().put("episode_id", "e3").put("net_r", 0.5)));
        candidates.set("b", array()
                .add(object().put("episode_id", "e1").put("net_r", -0.1))
                .add(object().put("episode_id", "e2").put("net_r", 0.2))
                .add(object().put("episode_id", "e3").put("net_r", 0.3)));
        ObjectNode statistic = LegacyResearchV3.centredCandidateSetMaxStatistic(candidates, options);
        assertThat(statistic).isEqualTo(
                LegacyResearchV3.centredCandidateSetMaxStatistic(candidates, options));
        assertThat(statistic.path("p_value").asDouble()).isBetween(0.0, 1.0);
    }

    @Test
    void candidateMetricsAndAcceptanceAreAuthoritativelyRecomputed() {
        ArrayNode trades = array()
                .add(trade("a", "2024-01-01T00:00:00Z", 1.0, 100))
                .add(trade("b", "2024-06-01T00:00:00Z", -0.4, -40))
                .add(trade("c", "2025-01-01T00:00:00Z", 0.7, 70))
                .add(trade("d", "2025-06-01T00:00:00Z", 0.2, 20));
        ObjectNode options = object()
                .put("candidateId", "fixture").put("asset", "btc")
                .put("candidateCount", 2).put("initialEquity", 1_000)
                .put("seed", 7).put("bootstrapIterations", 64)
                .put("fundingProcessed", true);
        options.set("candidateIds", array().add("fixture").add("other"));
        ArrayNode accounting = trades.deepCopy();
        accounting.forEach(row -> ((ObjectNode) row).put("candidate_id", "fixture"));
        accounting.add(object().put("candidate_id", "other")
                .put("episode_id", "explicit-zero").put("net_r", 0));
        options.set("allTrades", accounting);
        options.set("coverage", object().put("price_fraction", 1)
                .put("derivatives_fraction", 1));
        ObjectNode metrics = LegacyResearchV3.computeCandidateMetrics(trades, options);
        assertThat(metrics.path("candidate_id").asText()).isEqualTo("fixture");
        assertThat(metrics.path("completed_trades").asInt()).isEqualTo(4);
        assertThat(metrics.toString()).doesNotContain("caller_metrics");

        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract();
        ObjectNode acceptanceOptions = object().put("phase", "DEVELOPMENT");
        acceptanceOptions.set("coverage", object().put("price_fraction", 1)
                .put("verified", true));
        ObjectNode acceptance = LegacyResearchV3.evaluateAcceptance(metrics, contract, acceptanceOptions);
        assertThat(acceptance.path("phase").asText()).isEqualTo("DEVELOPMENT");
        assertThat(acceptance.path("decision").asText()).isNotBlank();
    }

    @Test
    void invalidContractAndSerializedWfoFailClosed() {
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract();
        contract.put("unknown", true);
        assertThatThrownBy(() -> LegacyResearchV3.validateAcceptanceContract(contract))
                .hasMessage("acceptance contract content hash mismatch");

        assertThatThrownBy(() -> LegacyResearchV3.walkForwardV3(object()))
                .hasMessage("authoritative WFO requires executable train/test evaluators; "
                        + "serialized callbacks are not an authority");
    }

    @Test
    void generatedTimestampsUseNodeIsoMilliseconds() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-28T12:34:56Z"), ZoneOffset.UTC);
        ObjectNode options = object().put("experimentId", "timestamp-fixture")
                .put("precommitSha256", LegacyResearchV3.hash("precommit"))
                .put("definitionSha256", LegacyResearchV3.hash("definition"))
                .put("candidateSetSha256", LegacyResearchV3.hash("candidate-set"))
                .put("dataManifestSha256", LegacyResearchV3.hash("data-manifest"));
        ObjectNode experiment = LegacyResearchV3.makeExperimentV3(options, clock);
        assertThat(experiment.path("created_at").asText())
                .isEqualTo("2026-08-28T12:34:56.000Z");
    }

    private static ObjectNode trade(
            String id, String entry, double netR, double pnl) {
        return object().put("trade_id", id).put("candidate_id", "fixture")
                .put("asset", "btc").put("episode_id", "episode-" + id)
                .put("entry_time", entry).put("exit_time", entry)
                .put("net_r", netR).put("net_pnl", pnl)
                .put("fee_r", 0.01).put("slippage_r", 0.01)
                .put("funding_debit_r", 0).put("regime", "RANGE")
                .put("notional", 100).put("available_liquidity_notional", 10_000)
                .put("adverse_gap_r", 0.1);
    }
}
