package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PhysicalEvaluatorTrustRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyPerformanceV5TrustTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @TempDir Path temporary;

    @Test
    void loaderOwnedOutcomesAreTheOnlyCrossScopeReusableOutcomes() throws Exception {
        TrustFixture fixture = fixture();
        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();
        Object evaluator = new Object();
        AtomicInteger pitCalls = new AtomicInteger(); AtomicInteger outcomeChecks = new AtomicInteger();
        AtomicInteger computations = new AtomicInteger();
        PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration configuration =
                new PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration(
                        fixture.evaluatorSpec(), fixture.bindings(), fixture.proof(), fixture.metadataBinding(),
                        context -> { pitCalls.incrementAndGet(); return true; },
                        context -> { outcomeChecks.incrementAndGet(); return true; },
                        context -> {
                            computations.incrementAndGet();
                            return Map.of("net_r", "e1".equals(context.get("episodeId")) ? .2 : .3,
                                    "traded", true);
                        });
        registry.register(evaluator, fixture.manifest(), temporary, fixture.provenance(), configuration);

        ObjectNode cacheOptions = fixture.cacheOptions();
        StrategyPerformanceV5.ScopeVectorCache cache = StrategyPerformanceV5.makeScopeVectorCacheV5(
                cacheOptions, evaluator, registry, fixture.proof());
        Map<String, JsonNode> features = Map.of("e1", object().put("row", 1), "e2", object().put("row", 2));
        AtomicInteger callerOutcomes = new AtomicInteger();
        StrategyPerformanceV5.SignalEvaluator signals = (id, feature, chromosome) -> object().put("intent", true);
        StrategyPerformanceV5.OutcomeEvaluator forgedCaller = (id, feature, signal, chromosome, phase,
                                                                 fold, fit, evaluation) -> {
            callerOutcomes.incrementAndGet(); return object().put("net_r", 999).put("traded", true);
        };
        ObjectNode chromosome = object().put("threshold", 0);
        ObjectNode first = cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null,
                List.of("e1", "e2"), List.of("e1", "e2"), "TRAIN_ONLY", "outer-1",
                "2026-01-01T00:00:00.000Z", "2026-02-01T00:00:00.000Z", features,
                signals, forgedCaller));
        ObjectNode second = cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null,
                List.of("e2"), List.of("e2"), "VALID", "outer-2",
                "2026-03-01T00:00:00.000Z", "2026-04-01T00:00:00.000Z", features,
                signals, forgedCaller));

        assertThat(first.path("candidate_returns").path("e1").path("net_r").asDouble()).isEqualTo(.2);
        assertThat(first.path("candidate_returns").path("e2").path("net_r").asDouble()).isEqualTo(.3);
        assertThat(second.path("candidate_returns").path("e2").path("net_r").asDouble()).isEqualTo(.3);
        assertThat(callerOutcomes).hasValue(0);
        assertThat(computations).hasValue(2);
        assertThat(pitCalls).hasValue(3);
        assertThat(outcomeChecks).hasValue(3);
        assertThat(cache.diagnostics().path("scope_independent_outcomes").asBoolean()).isTrue();
        assertThat(cache.diagnostics().path("outcome_compute_count").asInt()).isEqualTo(2);
        assertThat(cache.diagnostics().path("outcome_hit_count").asInt()).isEqualTo(1);
        assertThat(registry.getInternalScopeIndependentOutcomeCapability(evaluator).diagnostics())
                .isEqualTo(new PhysicalEvaluatorTrustRegistry.Diagnostics(
                        "strategy-v5-physical-trust-diagnostics/1", 4, 16, 4));
    }

    @Test
    void trustedDiskHitsAreRecomputedAndPhysicalBytesAreReopened() throws Exception {
        TrustFixture fixture = fixture();
        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();
        Object evaluator = new Object(); AtomicInteger computations = new AtomicInteger();
        registry.register(evaluator, fixture.manifest(), temporary, fixture.provenance(),
                new PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration(
                        fixture.evaluatorSpec(), fixture.bindings(), fixture.proof(), fixture.metadataBinding(),
                        context -> true, context -> true,
                        context -> { computations.incrementAndGet(); return Map.of("net_r", .25, "traded", true); }));
        Path cacheRoot = Files.createDirectory(temporary.resolve("scope-cache"));
        ObjectNode options = fixture.cacheOptions().put("cacheRoot", cacheRoot.toString())
                .put("maxDiskBytes", 1_000_000);
        ObjectNode chromosome = object().put("threshold", 0);
        Map<String, JsonNode> features = Map.of("e1", object().put("row", 1));
        StrategyPerformanceV5.EvaluationRequest request = new StrategyPerformanceV5.EvaluationRequest(
                chromosome, null, List.of("e1"), List.of("e1"), "TRAIN_ONLY", "outer-1",
                null, null, features, (id, feature, candidate) -> object().put("intent", true), null);
        StrategyPerformanceV5.ScopeVectorCache first = StrategyPerformanceV5.makeScopeVectorCacheV5(
                options, evaluator, registry, fixture.proof());
        first.evaluate(request);
        assertThat(computations).hasValue(1);
        StrategyPerformanceV5.ScopeVectorCache reopened = StrategyPerformanceV5.makeScopeVectorCacheV5(
                options, evaluator, registry, fixture.proof());
        reopened.evaluate(request);
        assertThat(computations).hasValue(2);
        assertThat(reopened.diagnostics().path("disk_revalidation_count").asInt()).isEqualTo(2);
        assertThat(reopened.diagnostics().path("disk_hit_count").asInt()).isEqualTo(2);
        assertThat(reopened.diagnostics().path("signal_compute_count").asInt()).isZero();
        assertThat(reopened.diagnostics().path("outcome_compute_count").asInt()).isZero();

        Files.writeString(temporary.resolve("mark.parquet"), "tampered\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reopened.evaluate(request))
                .hasMessage("scope-independent outcome capability mark bytes are missing or tampered");
    }

    @Test
    void serializedProofAndLookalikeEvaluatorCannotAuthorizeReuse() throws Exception {
        TrustFixture fixture = fixture();
        ObjectNode serialized = fixture.cacheOptions();
        serialized.set("scopeIndependentOutcomeProof", fixture.proof());
        assertThatThrownBy(() -> StrategyPerformanceV5.makeScopeVectorCacheV5(serialized))
                .hasMessageContaining("cannot authorize reuse without the loader-owned in-process capability");

        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();
        assertThatThrownBy(() -> StrategyPerformanceV5.makeScopeVectorCacheV5(
                fixture.cacheOptions(), new Object(), registry, fixture.proof()))
                .hasMessageContaining("cannot authorize reuse without the loader-owned in-process capability");

        ObjectNode badProof = fixture.proof().deepCopy();
        badProof.put("physical_evaluator_code_sha256", "0".repeat(64));
        badProof.put("content_sha256", StrategyPerformanceV5.ownHash(badProof));
        Object evaluator = new Object();
        registry.register(evaluator, fixture.manifest(), temporary, fixture.provenance(),
                new PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration(
                        fixture.evaluatorSpec(), fixture.bindings(), fixture.proof(), fixture.metadataBinding(),
                        context -> true, context -> true, context -> Map.of("net_r", .1)));
        assertThatThrownBy(() -> StrategyPerformanceV5.makeScopeVectorCacheV5(
                fixture.cacheOptions(), evaluator, registry, badProof))
                .hasMessageContaining("capability binding mismatch");
    }

    private TrustFixture fixture() throws Exception {
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> artifacts = new LinkedHashMap<>();
        for (String role : List.of("feature", "label", "execution", "mark")) {
            byte[] bytes = (role + "-physical-bytes\n").getBytes(StandardCharsets.UTF_8);
            Files.write(temporary.resolve(role + ".parquet"), bytes);
            artifacts.put(role, new PhysicalEvaluatorTrustRegistry.Artifact(
                    role + ".parquet", JsonHashes.sha256(bytes), (long) bytes.length));
        }
        String source = JsonHashes.sha256("source"); String evaluatorSpec = JsonHashes.sha256("evaluator");
        PhysicalEvaluatorTrustRegistry.Manifest manifest =
                new PhysicalEvaluatorTrustRegistry.Manifest(source, artifacts);
        Path metadataDirectory = Files.createDirectories(temporary.resolve("metadata"));
        byte[] rawBytes = "metadata-raw-physical-bytes\n".getBytes(StandardCharsets.UTF_8);
        Files.write(metadataDirectory.resolve("raw.bin"), rawBytes);
        ObjectNode rawReceipt = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("path", "metadata/raw.bin").put("source", "fixture")
                .put("byte_sha256", JsonHashes.sha256(rawBytes)).put("bytes", rawBytes.length);
        rawReceipt.put("content_sha256", JsonHashes.ownHash(rawReceipt));
        ObjectNode normalizedReceipt = object().put("schema", "strategy-v5-source-receipt/1")
                .put("version", 1).put("status", "PUBLIC_OBSERVED");
        normalizedReceipt.putArray("response_sha256").add(JsonHashes.sha256(rawBytes));
        normalizedReceipt.putArray("source_byte_sha256").add(JsonHashes.sha256(rawBytes));
        normalizedReceipt.putArray("raw_receipts").add(rawReceipt.deepCopy());
        normalizedReceipt.put("content_sha256", JsonHashes.ownHash(normalizedReceipt));
        byte[] normalizedBytes = (MAPPER.writeValueAsString(normalizedReceipt) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(metadataDirectory.resolve("contract.json"), normalizedBytes);
        ObjectNode normalizedBinding = object();
        normalizedBinding.putObject("summary").put("path", "metadata/contract.json")
                .put("bytes", normalizedBytes.length);
        normalizedBinding.put("content_sha256", normalizedReceipt.path("content_sha256").asText())
                .put("byte_sha256", JsonHashes.sha256(normalizedBytes));
        normalizedBinding.putArray("raw_byte_sha256").add(JsonHashes.sha256(rawBytes));
        normalizedBinding.putArray("raw_receipts").add(rawReceipt.deepCopy());
        ObjectNode receipts = object();
        for (String kind : List.of("CONTRACT_SPEC", "FEE_SCHEDULE", "EXECUTION_MODEL")) {
            ObjectNode receipt = receipts.putObject(kind);
            receipt.put("receipt_content_sha256", JsonHashes.sha256(kind + "-receipt"));
            receipt.put("receipt_byte_sha256", JsonHashes.sha256(kind + "-receipt-bytes"));
            receipt.putArray("normalized").add(normalizedBinding.deepCopy());
        }
        String metadataDigest = JsonHashes.canonicalSha256(receipts);
        ObjectNode metadataBinding = object().put("root", temporary.toString()).put("digest", metadataDigest);
        metadataBinding.set("receipts", receipts);
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("feature_artifact_sha256", artifacts.get("feature").sha256());
        bindings.put("label_artifact_sha256", artifacts.get("label").sha256());
        bindings.put("execution_artifact_sha256", artifacts.get("execution").sha256());
        bindings.put("mark_artifact_sha256", artifacts.get("mark").sha256());
        bindings.put("metadata_artifact_sha256", metadataDigest);
        ObjectNode proof = object().put("schema", PhysicalEvaluatorTrustRegistry.OUTCOME_PROOF_SCHEMA)
                .put("version", 1).put("authority", "AUTHORITATIVE_V2_PHYSICAL_EVALUATOR")
                .put("verified", true).put("source_artifact_sha256", source)
                .put("evaluator_spec_sha256", evaluatorSpec)
                .put("data_bindings_sha256", JsonHashes.canonicalSha256(bindings))
                .put("pit_boundary_contract", "CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT")
                .put("outcome_role_contract", "FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS")
                .put("one_episode_read_contract", true)
                .put("physical_evaluator_code_sha256", JsonHashes.sha256("physical-code"))
                .put("pit_validator_code_sha256", JsonHashes.sha256("pit-code"));
        proof.put("content_sha256", JsonHashes.ownHash(proof));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source_manifest_sha256", source);
        provenance.put("feature_artifact_sha256", artifacts.get("feature").sha256());
        provenance.put("label_artifact_sha256", artifacts.get("label").sha256());
        provenance.put("execution_artifact_sha256", artifacts.get("execution").sha256());
        provenance.put("physical_role_binding", true); provenance.put("artifact_paths_bound", true);
        provenance.put("deterministic", true);
        return new TrustFixture(manifest, evaluatorSpec, bindings, proof, metadataBinding, provenance);
    }

    private record TrustFixture(PhysicalEvaluatorTrustRegistry.Manifest manifest, String evaluatorSpec,
                                Map<String, String> bindings, ObjectNode proof, ObjectNode metadataBinding,
                                Map<String, Object> provenance) {
        ObjectNode cacheOptions() {
            ObjectNode data = MAPPER.createObjectNode(); bindings.forEach(data::put);
            ObjectNode value = MAPPER.createObjectNode().put("sourceArtifactSha256", manifest.contentSha256())
                    .put("evaluatorSpecSha256", evaluatorSpec)
                    .put("signalCodeSha256", JsonHashes.sha256("signal"))
                    .put("outcomeCodeSha256", JsonHashes.sha256("outcome"))
                    .put("maxMemoryEntries", 100).put("maxMemoryBytes", 1_000_000).put("maxDiskBytes", 0);
            value.set("dataBindings", data); return value;
        }
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
