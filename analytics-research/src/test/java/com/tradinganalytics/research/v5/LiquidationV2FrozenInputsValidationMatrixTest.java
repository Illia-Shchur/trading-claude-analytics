package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Draft: frozen-input validation matrix, to copy into test source only after Maven barrier. */
class LiquidationV2FrozenInputsValidationMatrixTest {
    private static final String[] IDS = {"liquidation-v2-core-routed-one-entry",
            "liquidation-v2-core-always-continuation-one-entry",
            "liquidation-v2-core-always-reversal-one-entry",
            "liquidation-v2-price-oi-only-event-diagnostic"};
    private static final String[] VARIANTS = {"ROUTED_REVERSAL_CONTINUATION", "ALWAYS_CONTINUATION_CONTROL",
            "ALWAYS_REVERSAL_CONTROL", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC"};
    private static final Instant DECISION = Instant.parse("2024-02-01T00:00:00Z");

    @Test
    void validatesDevelopmentProvenanceHashesModeExecutorInventoryAndPriorFamilyK() throws Exception {
        Fixture base = fixture();
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(base.replay(), base.frozen()));

        assertReject(base, f -> {
            ObjectNode precommit = f.precommit().deepCopy(); precommit.put("revision_reason", "tampered");
            return copyWith(f, f.manifest(), precommit, f.executorIdentity(), f.candidateInventory(), null);
        }, "frozen precommit content hash does not match its reopened object");
        assertReject(base, f -> {
            ObjectNode precommit = f.precommit().deepCopy(); precommit.put("precommit_id", "not-v002"); reseal(precommit);
            return copyWith(f, f.manifest(), precommit, f.executorIdentity(), f.candidateInventory(), null);
        }, "replay precommit is not the frozen liquidation v002 premise");
        assertReject(base, f -> {
            ObjectNode manifest = f.manifest().deepCopy(); manifest.put("authoritative", true); reseal(manifest);
            return copyWith(f, manifest, f.precommit(), f.executorIdentity(), f.candidateInventory(), null);
        }, "physical manifest is not a bound v002 development manifest");
        assertReject(base, f -> {
            ObjectNode manifest = f.manifest().deepCopy(); manifest.put("source_mode", "UNKNOWN_SOURCE").put("status", "DEVELOPMENT_SYNTHETIC"); reseal(manifest);
            return copyWith(f, manifest, f.precommit(), f.executorIdentity(), f.candidateInventory(), null);
        }, "physical manifest is not a bound v002 development manifest");
        assertReject(base, f -> {
            ObjectNode manifest = f.manifest().deepCopy(); manifest.put("status", "DEVELOPMENT_PROXY_DISCLOSED"); reseal(manifest);
            return copyWith(f, manifest, f.precommit(), f.executorIdentity(), f.candidateInventory(), null);
        }, "physical manifest status does not match its frozen source mode");
        assertReject(base, f -> {
            ObjectNode executor = f.executorIdentity().deepCopy(); executor.put("default_stage_add_macro_gate_policy", "STRUCTURE_ONLY"); reseal(executor);
            return copyWith(f, f.manifest(), f.precommit(), executor, f.candidateInventory(), null);
        }, "executor identity does not match the frozen v002 capability");
        assertReject(base, f -> {
            ObjectNode inventory = f.candidateInventory().deepCopy();
            ((ObjectNode) inventory.path("current_candidates").get(0)).put("variant", "UNFROZEN"); reseal(inventory);
            return copyWith(f, f.manifest(), f.precommit(), f.executorIdentity(), inventory, null);
        }, "candidate inventory differs from the exact frozen four-variant inventory");

        ObjectNode wrongPrior = prior("not-the-family-inventory", List.of("old-a"));
        assertReject(base, f -> copyWith(f, f.manifest(), f.precommit(), f.executorIdentity(), f.candidateInventory(), wrongPrior),
                "prior family exposure inventory is not bound to the frozen v001 predecessor");
        ObjectNode emptyPrior = prior("liquidation-family-exposure-inventory/1", List.of());
        assertReject(base, f -> copyWith(f, f.manifest(), f.precommit(), f.executorIdentity(), f.candidateInventory(), emptyPrior),
                "prior family exposure inventory is not bound to the frozen v001 predecessor");
        ObjectNode duplicatePrior = prior("liquidation-family-exposure-inventory/1", java.util.Arrays.asList("same", "same"));
        assertReject(base, f -> copyWith(f, f.manifest(), f.precommit(), f.executorIdentity(), f.candidateInventory(), duplicatePrior),
                "prior family exposure inventory has invalid or duplicate candidate identities");
        ObjectNode stalePrior = prior("liquidation-family-exposure-inventory/1", List.of("old-a"));
        stalePrior.put("status", "tampered");
        assertReject(base, f -> copyWith(f, f.manifest(), f.precommit(), f.executorIdentity(), f.candidateInventory(), stalePrior),
                "prior family exposure inventory content hash does not match its reopened object");
    }

    @Test
    void parentLineageMustReopenAllThreeArtifactsAsExactBytes() throws Exception {
        Fixture base = fixture();
        Path root = repositoryRoot();
        ObjectNode parent = read(root.resolve("docs/research/liquidation-structure-v001/frozen-precommit.json"));
        Path manifestPath = root.resolve("docs/research/liquidation-structure-v001/FREEZE-MANIFEST.json");
        String manifestBytes = Files.readString(manifestPath);
        ObjectNode parentManifest = (ObjectNode) JsonHashes.mapper().readTree(manifestBytes);
        String feasibility = Files.readString(root.resolve("docs/research/liquidation-structure-v001/FEASIBILITY.md"));
        var complete = new LiquidationV2ReplayEvidenceV1.FreezeInputs(base.frozen().manifest(), base.frozen().precommit(),
                base.frozen().profile(), base.frozen().executorIdentity(), base.frozen().candidateInventory(), null,
                parent, parentManifest, manifestBytes, feasibility);
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(replay(complete), complete));

        var partial = new LiquidationV2ReplayEvidenceV1.FreezeInputs(base.frozen().manifest(), base.frozen().precommit(),
                base.frozen().profile(), base.frozen().executorIdentity(), base.frozen().candidateInventory(), null,
                parent, null, null, null);
        assertReject(base, partial, "parent family lineage must reopen precommit, exact freeze-manifest bytes, and exact feasibility bytes together");

        ObjectNode wrongParentId = parent.deepCopy(); wrongParentId.put("precommit_id", "other-parent"); reseal(wrongParentId);
        var wrongParent = withParent(base.frozen(), wrongParentId, parentManifest, manifestBytes, feasibility);
        assertReject(base, wrongParent, "v002 precommit does not bind the reopened v001 family predecessor");

        var changedBytes = withParent(base.frozen(), parent, parentManifest, "{}", feasibility);
        assertReject(base, changedBytes, "reopened parent freeze manifest object does not match its exact bytes");

        ObjectNode changedStatus = parentManifest.deepCopy(); changedStatus.put("status", "OTHER");
        String changedManifestBytes = JsonHashes.mapper().writeValueAsString(changedStatus);
        var invalidStatus = withParent(base.frozen(), parent, changedStatus, changedManifestBytes, feasibility);
        assertReject(base, invalidStatus, "parent freeze manifest is not the frozen blocked v001 family record");

        var changedFeasibility = withParent(base.frozen(), parent, parentManifest, manifestBytes, feasibility + "\nchanged");
        assertReject(base, changedFeasibility, "parent FEASIBILITY.md bytes do not match the frozen v001 manifest digest");
    }

    private static void assertReject(Fixture f,
            java.util.function.Function<LiquidationV2ReplayEvidenceV1.FreezeInputs,
                    LiquidationV2ReplayEvidenceV1.FreezeInputs> change, String message) {
        assertReject(f, change.apply(f.frozen()), message);
    }

    private static void assertReject(Fixture fixture, LiquidationV2ReplayEvidenceV1.FreezeInputs changed, String message) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(replay(changed), changed));
        assertTrue(error.getMessage().contains(message), () -> "expected '" + message + "', got '" + error.getMessage() + "'");
    }

    private static LiquidationV2ReplayEvidenceV1.FreezeInputs withParent(
            LiquidationV2ReplayEvidenceV1.FreezeInputs f, ObjectNode parent, ObjectNode manifest,
            String manifestBytes, String feasibility) {
        return new LiquidationV2ReplayEvidenceV1.FreezeInputs(f.manifest(), f.precommit(), f.profile(),
                f.executorIdentity(), f.candidateInventory(), f.priorFamilyInventory(), parent, manifest,
                manifestBytes, feasibility);
    }

    private static LiquidationV2ReplayEvidenceV1.FreezeInputs copyWith(
            LiquidationV2ReplayEvidenceV1.FreezeInputs f, ObjectNode manifest, ObjectNode precommit,
            ObjectNode executor, ObjectNode inventory, ObjectNode prior) {
        return new LiquidationV2ReplayEvidenceV1.FreezeInputs(manifest, precommit, f.profile(), executor,
                inventory, prior, f.parentPrecommit(), f.parentFreezeManifest(), f.parentFreezeManifestBytes(),
                f.parentFeasibilityMarkdown());
    }

    private static Fixture fixture() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode precommit = read(repositoryRoot().resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"));
        ObjectNode manifest = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-physical-manifest/1")
                .put("version", 1).put("status", "DEVELOPMENT_SYNTHETIC").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("profile_sha256", profile.path("content_sha256").asText()).put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("authoritative", false).put("authoritative_evaluation_permitted", false);
        reseal(manifest);
        ObjectNode executor = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.EXECUTOR_IDENTITY_SCHEMA)
                .put("version", 1).put("capability", profile.path("executor_capability").asText())
                .put("default_stage_add_macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        reseal(executor);
        ObjectNode inventory = LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(profile);
        var frozen = new LiquidationV2ReplayEvidenceV1.FreezeInputs(manifest, precommit, profile, executor, inventory);
        return new Fixture(frozen, replay(frozen));
    }

    private static ObjectNode replay(LiquidationV2ReplayEvidenceV1.FreezeInputs f) {
        ObjectNode r = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1);
        ObjectNode refs = r.putObject("freeze").put("manifest_sha256", f.manifest().path("content_sha256").asText())
                .put("precommit_sha256", f.precommit().path("content_sha256").asText())
                .put("profile_sha256", f.profile().path("content_sha256").asText())
                .put("executor_sha256", f.executorIdentity().path("content_sha256").asText())
                .put("candidate_inventory_sha256", f.candidateInventory().path("content_sha256").asText())
                .put("source_mode", f.manifest().path("source_mode").asText());
        if (f.priorFamilyInventory() != null) refs.put("prior_family_inventory_sha256", f.priorFamilyInventory().path("content_sha256").asText());
        if (f.parentPrecommit() != null) refs.put("parent_precommit_sha256", f.parentPrecommit().path("content_sha256").asText());
        if (f.parentFreezeManifest() != null) refs.put("parent_freeze_manifest_byte_sha256", JsonHashes.sha256(f.parentFreezeManifestBytes()));
        if (f.parentFeasibilityMarkdown() != null) refs.put("parent_feasibility_byte_sha256", JsonHashes.sha256(f.parentFeasibilityMarkdown()));
        r.put("event_stream_sha256", "a".repeat(64)).put("event_count", 1);
        ArrayNode curve = r.putArray("account_curve"); curve.addObject().put("time", 1700000000000L).put("equity_usdt", 20000);
        curve.addObject().put("time", 1700000060000L).put("equity_usdt", 20001);
        r.put("account_curve_sha256", JsonHashes.canonicalSha256(curve)).put("ledger_sha256", "b".repeat(64));
        ArrayNode rows = r.putArray("opportunities");
        for (int i = 0; i < 3; i++) rows.addObject().put("opportunity_id", "p::" + IDS[i]).put("pair_id", "p")
                .put("candidate_id", IDS[i]).put("variant", VARIANTS[i]).put("asset", "BTC").put("decision_time", DECISION.toString())
                .put("outcome_state", "CLOSED_TRADE").put("outcome_available_time", DECISION.plus(Duration.ofDays(2)).toString())
                .put("first_fill_time", DECISION.plusSeconds(60).toString()).put("exit_time", DECISION.plus(Duration.ofDays(2)).toString())
                .put("net_pnl_usdt", 1).put("reference_risk_usdt", 100).putArray("reason_codes");
        ArrayNode evaluated = r.putArray("evaluated_candidates");
        for (int i = 0; i < 4; i++) evaluated.addObject().put("candidate_id", IDS[i]).put("variant", VARIANTS[i])
                .put("audit_only", i == 3).put("execution_scope", "SYNTHETIC_FIXTURE_EXECUTED")
                .put("opportunity_count", i == 3 ? 0 : 1).put("executed", true);
        reseal(r); return r;
    }

    private static ObjectNode prior(String schema, java.util.List<String> ids) throws Exception {
        ObjectNode p = JsonHashes.mapper().createObjectNode().put("schema", schema).put("version", 1)
                .put("strategy_family", "liquidation-structure")
                .put("precommit_sha256", read(repositoryRoot().resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))
                        .path("parent_precommit").path("content_sha256").asText());
        ArrayNode a = p.putArray("effective_candidate_ids"); ids.forEach(a::add); reseal(p); return p;
    }

    private static ObjectNode read(Path p) throws Exception { return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(p)); }
    private static void reseal(ObjectNode x) { x.remove("content_sha256"); x.put("content_sha256", JsonHashes.ownHash(x)); }
    private static Path repositoryRoot() { Path p=Path.of("").toAbsolutePath().normalize(); while(p!=null&&!Files.exists(p.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) p=p.getParent(); if(p==null) throw new IllegalStateException("repo root missing"); return p; }
    private record Fixture(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen, ObjectNode replay) {}
}
