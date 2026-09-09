package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.marketdata.research.ResearchData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchFoundationV3ContractTest {
    @TempDir Path temporary;

    @Test
    void originalFoundationSnapshotMetricsAndAttestationContractIsPreserved() throws Exception {
        Path input = temporary.resolve("bars.jsonl");
        Files.writeString(input, """
                {"asset":"btc","time":"2026-01-01T00:00:00Z","close":100,"availability_time":"2026-01-01T04:00:00Z"}
                """);
        ResearchData.SnapshotOptions snapshotOptions = new ResearchData.SnapshotOptions(
                input, temporary.resolve("lake"), "core", "btc", null, null,
                "T0_IMMUTABLE_EVENT", "FEATURE", "jsonl", "public", true,
                null, null, null, null, null, null);
        ResearchData.SnapshotResult first = ResearchData.snapshot(snapshotOptions);
        ResearchData.SnapshotResult second = ResearchData.snapshot(snapshotOptions);
        ObjectNode manifest = (ObjectNode) LegacyNodeOracle.MAPPER.readTree(
                Files.readAllBytes(first.manifest()));
        ObjectNode manifestAgain = (ObjectNode) LegacyNodeOracle.MAPPER.readTree(
                Files.readAllBytes(second.manifest()));
        assertThat(manifest.path("content_sha256").asText())
                .isEqualTo(manifestAgain.path("content_sha256").asText());
        assertThatThrownBy(() -> ResearchData.validateManifest(manifest,
                new ResearchData.ValidationOptions(
                        "WALK_FORWARD_OOS", List.of("btc"), first.root())))
                .hasMessageMatching(".*(JSONL|authoritative).*?");
        assertThat(ResearchData.validateManifest(manifest,
                new ResearchData.ValidationOptions(
                        "DEVELOPMENT", List.of("btc"), first.root()))).isTrue();
        assertThat(manifest.path("feature_store").path("path").asText())
                .startsWith("features/").endsWith(".jsonl");

        ObjectNode acceptance = LegacyResearchV3.makeAcceptanceContract();
        assertThat(LegacyResearchV3.validateAcceptanceContract(acceptance)).isTrue();
        ObjectNode experimentOptions = object().put("experimentId", "foundation-test")
                .put("precommitSha256", "a".repeat(64))
                .put("definitionSha256", "b".repeat(64))
                .put("candidateSetSha256", "c".repeat(64))
                .put("dataManifestSha256", manifest.path("content_sha256").asText());
        experimentOptions.set("acceptanceContract", acceptance);
        experimentOptions.set("requiredAssets", array().add("btc"));
        experimentOptions.set("chronology", object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .set("seeds", array().add(7)));
        ObjectNode experiment = LegacyResearchV3.makeExperimentV3(experimentOptions);
        assertThat(LegacyResearchV3.validateExperimentV3(
                experiment, acceptance, null)).isTrue();

        ArrayNode trades = array().add(object().put("candidate_id", "c")
                .put("asset", "btc").put("entry_time", "2026-01-01T00:00:00Z")
                .put("exit_time", "2026-01-02T00:00:00Z").put("net_r", 0.5)
                .put("net_pnl", 50).put("episode_id", "e1"));
        ObjectNode metricOptions = object().put("candidateId", "c")
                .put("asset", "btc").put("candidateCount", 1);
        ObjectNode metrics = LegacyResearchV3.computeCandidateMetrics(trades, metricOptions);
        assertThat(metrics.path("completed_trades").asInt()).isOne();
        assertThat(metrics.path("robust_stats")
                .path("effective_independent_episode_count").asInt()).isOne();
        assertThat(metrics.path("win_rate_wilson_95").path("high").asDouble())
                .isPositive();

        ObjectNode keys = LegacyResearchV3.generateEd25519KeyPair();
        Path workflow = repositoryRoot().resolve(
                ".github/workflows/strategy-confirmation.yml");
        String workflowSha = LegacyResearchV3.hash(Files.readAllBytes(workflow));
        ObjectNode reservationOptions = object().put("sealId", "foundation-test")
                .put("repository", "owner/repo").put("commitSha", "d".repeat(40))
                .put("workflowSha256", workflowSha)
                .put("precommitSha256", "a".repeat(64))
                .put("definitionSha256", "b".repeat(64))
                .put("experimentSha256", experiment.path("content_sha256").asText())
                .put("candidateSetSha256", "c".repeat(64))
                .put("dataRootSha256", "f".repeat(64))
                .put("acceptanceContractSha256",
                        acceptance.path("content_sha256").asText())
                .put("containerSha256", "1".repeat(64))
                .put("executorSha256", "2".repeat(64))
                .put("experimentPath", "strategy-research/experiments/e.json")
                .put("dataPath", "data/e.json").put("workflowPath", workflow.toString());
        ObjectNode reservation = LegacyResearchV3.makeConfirmationReservation(
                reservationOptions);
        Path burnRoot = Files.createDirectories(temporary.resolve("burn"));
        assertThat(LegacyResearchV3.burnReservation(reservation, burnRoot)).exists();
        ObjectNode burnReceipt = object()
                .put("ref", "refs/tags/research-seal/foundation-test")
                .put("reservation_sha256", reservation.path("content_sha256").asText())
                .put("commit_sha", "d".repeat(40)).put("status", "BURNED");
        ObjectNode result = object().put("data_root_sha256", "f".repeat(64))
                .put("evidence_phase", "CI_ATTESTED_CONFIRMATION");
        result.set("decision", object().put("status", "SHADOW"));
        result.set("metrics", metrics);
        ObjectNode signOptions = object().put("privateKeyPem",
                        keys.path("privateKey").asText())
                .put("repository", "owner/repo").put("commitSha", "d".repeat(40))
                .put("workflowSha", workflowSha).put("workflowPath", workflow.toString())
                .put("runId", "1");
        signOptions.set("reservation", reservation);
        signOptions.set("result", result);
        signOptions.set("burnReceipt", burnReceipt);
        ObjectNode attestation = LegacyResearchV3.signAttestation(signOptions);
        ObjectNode verifyOptions = object()
                .put("publicKeyPem", keys.path("publicKey").asText())
                .put("expectedRepository", "owner/repo")
                .put("expectedCommitSha", "d".repeat(40))
                .put("workflowPath", workflow.toString())
                .put("burnRoot", burnRoot.toString());
        verifyOptions.set("reservation", reservation);
        assertThat(LegacyResearchV3.verifyAttestation(attestation, verifyOptions)
                .path("label").asText()).isEqualTo("CI_ATTESTED_CONFIRMATION");

        ObjectNode replay = attestation.deepCopy().put("run_attempt", 2);
        assertThatThrownBy(() -> LegacyResearchV3.verifyAttestation(replay, verifyOptions))
                .hasMessageMatching(".*(content hash|rerun).*?");
        ObjectNode wrongResult = result.deepCopy().put("data_root_sha256", "0".repeat(64));
        ObjectNode wrongSign = signOptions.deepCopy();
        wrongSign.set("result", wrongResult);
        assertThatThrownBy(() -> LegacyResearchV3.signAttestation(wrongSign))
                .hasMessageContaining("reserved data root");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null
                && !Files.isRegularFile(current.resolve(
                ".github/workflows/strategy-confirmation.yml"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
