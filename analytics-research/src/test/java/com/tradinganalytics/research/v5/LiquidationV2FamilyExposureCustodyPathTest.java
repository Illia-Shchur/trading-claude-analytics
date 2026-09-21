package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationV2FamilyExposureCustodyPathTest {
    @TempDir Path temporary;

    @Test
    void wrongFamilyCanonicalHeadIsRejectedWithoutChangingTheHead() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("wrong-family"));
        Path headPath = root.resolve("exposure-head.json");
        ObjectNode args = JsonHashes.mapper().createObjectNode()
                .put("hypothesisFamily", "unrelated-family")
                .put("datasetSha256", JsonHashes.sha256("prior-data"));
        args.putArray("entries").addObject().put("behavior_sha256", JsonHashes.sha256("prior-behavior"))
                .put("dataset_sha256", args.path("datasetSha256").asText());
        ObjectNode prior = StrategyStatisticalV5.makeExposureHead(args);
        StrategyStatisticalV5.initializeExposureHeadFile(JsonHashes.mapper().createObjectNode()
                .put("filePath", headPath.toString()).set("head", prior));

        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze(), root));
        assertEquals(prior.path("content_sha256").asText(),
                StrategyStatisticalV5.readExposureHeadFile(headPath).path("content_sha256").asText());
        assertTrue(Files.notExists(root.resolve("liquidation-v2-known-exposure-attempts.json")));
    }

    @Test
    void rehashedButUnknownCandidateInventoryIsRejectedBeforeCustodyMutation() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("bad-inventory"));
        ObjectNode freeze = freeze();
        ((ObjectNode) freeze.path("candidate_inventory").path("current_candidates").get(0)).put("variant", "UNFROZEN_VARIANT");
        rehash((ObjectNode) freeze.path("candidate_inventory"));
        freeze.put("candidate_inventory_sha256", freeze.path("candidate_inventory").path("content_sha256").asText());
        rehash(freeze);

        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze, root));
        assertTrue(Files.notExists(root.resolve("liquidation-v2-known-exposure-attempts.json")));
        assertTrue(Files.notExists(root.resolve("exposure-head.json")));
    }

    @Test
    void hashRecomputedJournalMutationStillFailsAgainstDurablePrefixWitness() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("tampered-journal"));
        LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze(), root);
        Path journalPath = root.resolve("liquidation-v2-known-exposure-attempts.json");
        ObjectNode journal = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(journalPath));
        ArrayNode entries = (ArrayNode) journal.path("entries");
        ((ObjectNode) entries.get(0)).put("candidate_id", "forged-candidate-id");
        String previous = entries.get(0).path("previous_sha256").asText();
        for (int index = 0; index < entries.size(); index++) {
            ObjectNode entry = (ObjectNode) entries.get(index);
            entry.put("previous_sha256", previous);
            rehash(entry);
            previous = JsonHashes.canonicalSha256(entry);
        }
        rehash(journal);
        Files.write(journalPath, JsonHashes.canonicalBytes(journal));

        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root));
    }

    private static ObjectNode freeze() throws Exception {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2FamilyExposureAttemptV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-routed-one-entry")
                .put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry")
                .put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry")
                .put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic")
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("frozen precommit was not found from test cwd");
        ObjectNode precommit = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(
                cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode freeze = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.FREEZE_SCHEMA).put("version", 1)
                .put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                .put("dataset_root_sha256", JsonHashes.sha256("fixed-physical-dataset"))
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        freeze.set("candidate_inventory", inventory);
        freeze.set("precommit", precommit);
        rehash(freeze);
        return freeze;
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256");
        node.put("content_sha256", JsonHashes.ownHash(node));
    }
}
