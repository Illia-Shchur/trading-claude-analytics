package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Durable pre-outcome family exposure recorder for the liquidation v002 lineage. */
public final class LiquidationV2FamilyExposureAttemptV1 {
    public static final String JOURNAL_SCHEMA = "liquidation-v2-family-exposure-attempt-journal/1";
    public static final String STATE_SCHEMA = "liquidation-v2-family-exposure-state/1";
    public static final String FAMILY = "liquidation-structure";
    public static final String FREEZE_SCHEMA = "liquidation-v2-replay-freeze/1";
    private static final String JOURNAL_ANCHOR_SCHEMA = "liquidation-v2-family-exposure-attempt-journal-anchor/1";

    private static final String V2_JOURNAL_FILE = "liquidation-v2-known-exposure-attempts.json";
    private static final String V2_JOURNAL_ANCHOR_FILE = V2_JOURNAL_FILE + ".anchor.json";
    private static final Set<String> CORE_IDS = Set.of(
            "liquidation-v2-core-routed-one-entry",
            "liquidation-v2-core-always-continuation-one-entry",
            "liquidation-v2-core-always-reversal-one-entry",
            "liquidation-v2-price-oi-only-event-diagnostic");
    private static final Map<String, String> CORE_VARIANTS = Map.of(
            "liquidation-v2-core-routed-one-entry", "ROUTED_REVERSAL_CONTINUATION",
            "liquidation-v2-core-always-continuation-one-entry", "ALWAYS_CONTINUATION_CONTROL",
            "liquidation-v2-core-always-reversal-one-entry", "ALWAYS_REVERSAL_CONTROL",
            "liquidation-v2-price-oi-only-event-diagnostic", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
    private static final List<String> STRESS_SCENARIOS = List.of("fee_slippage", "funding_carry",
            "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout");

    private LiquidationV2FamilyExposureAttemptV1() {}

    /** Records all four actually frozen core arms before the public runner reads outcomes. */
    public static ObjectNode recordCurrentFreezeBeforeOutcomes(ObjectNode freeze) {
        return recordCurrentFreezeBeforeOutcomes(freeze,
                StrategyResearchAuthoritativeV5.canonicalFamilyCustodyRoot(FAMILY));
    }

    /** Records a single staged candidate plan before its own outcomes are read. */
    public static ObjectNode recordBeforeOutcomes(ObjectNode plan, String candidateId, ObjectNode currentFreeze) {
        return recordBeforeOutcomes(plan, candidateId, currentFreeze,
                StrategyResearchAuthoritativeV5.canonicalFamilyCustodyRoot(FAMILY));
    }

    /** Reopens canonical V5 HEAD plus the separate v002 journal; a missing HEAD leaves old K unknown. */
    public static ObjectNode reopenFamilyExposureAttempts() {
        return reopenFamilyExposureAttempts(StrategyResearchAuthoritativeV5.canonicalFamilyCustodyRoot(FAMILY));
    }

    static ObjectNode recordCurrentFreezeBeforeOutcomes(ObjectNode freeze, Path familyRoot) {
        validateFreeze(freeze);
        if (isSynthetic(freeze.path("source_mode").asText())) return skipped("SYNTHETIC_FIXTURE_NOT_RECORDED", freeze);
        ObjectNode inventory = object(freeze, "candidate_inventory");
        verifyOwnHash(inventory, "core frozen candidate inventory");
        validateCoreInventory(inventory);
        String inventorySha = inventory.path("content_sha256").asText();
        if (freeze.has("candidate_inventory_sha256")
                && !inventorySha.equals(freeze.path("candidate_inventory_sha256").asText())) {
            throw failure("pre-outcome freeze does not bind its exact candidate inventory");
        }
        List<AttemptRequest> requests = new ArrayList<>();
        for (JsonNode candidate : inventory.path("current_candidates")) {
            String id = text(candidate, "candidate_id");
            requests.add(new AttemptRequest(id, candidate.path("variant").asText(),
                    JsonHashes.canonicalSha256(candidate), freeze.path("content_sha256").asText(), null,
                    inventorySha, freeze.path("dataset_root_sha256").asText(), freeze.path("source_mode").asText(),
                    "CORE_CANDIDATE", null));
        }
        requests.addAll(stressScenarioRequests(freeze, inventorySha));
        return recordRequests(requests, familyRoot, freeze.path("content_sha256").asText());
    }

    static ObjectNode recordBeforeOutcomes(ObjectNode plan, String candidateId,
            ObjectNode currentFreeze, Path familyRoot) {
        verifyOwnHash(plan, "frozen staged candidate plan");
        validateFreeze(currentFreeze);
        if (isSynthetic(plan.path("source_mode").asText()) || isSynthetic(currentFreeze.path("source_mode").asText())) {
            return skipped("SYNTHETIC_FIXTURE_NOT_RECORDED", currentFreeze);
        }
        if (!plan.path("source_mode").asText().equals(currentFreeze.path("source_mode").asText())
                || !FAMILY.equals(plan.path("strategy_family").asText())) {
            throw failure("staged exposure attempt is detached from its frozen physical source/family");
        }
        if (!candidateId.equals(plan.path("candidate_id").asText())
                || !candidateId.equals(plan.path("candidate").path("candidate_id").asText())) {
            throw failure("staged exposure attempt must use the one exact frozen candidate ID");
        }
        String variant = plan.path("candidate").path("macro_gate_policy").asText();
        AttemptRequest request = new AttemptRequest(candidateId, variant,
                JsonHashes.canonicalSha256(plan.path("candidate")), plan.path("content_sha256").asText(),
                plan.path("content_sha256").asText(), plan.path("anchor_inventory_sha256").asText(),
                currentFreeze.path("dataset_root_sha256").asText(), plan.path("source_mode").asText(),
                "STAGED_CANDIDATE", null);
        List<AttemptRequest> requests = new ArrayList<>();
        requests.add(request);
        requests.addAll(stagedStressScenarioRequests(plan, currentFreeze));
        return recordRequests(requests, familyRoot, currentFreeze.path("content_sha256").asText());
    }

    private static List<AttemptRequest> stagedStressScenarioRequests(ObjectNode plan, ObjectNode freeze) {
        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(object(freeze, "precommit"));
        ArrayNode scenarios = (ArrayNode) policy.toJson().path("scenarios");
        if (scenarios.size() != STRESS_SCENARIOS.size()) throw failure("frozen staged stress policy inventory changed");
        String matrixSha = JsonHashes.canonicalSha256(scenarios);
        List<AttemptRequest> requests = new ArrayList<>();
        String candidateId = text(plan, "candidate_id");
        String mode = text(plan, "mode_id");
        for (int index = 0; index < STRESS_SCENARIOS.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            String scenarioId = STRESS_SCENARIOS.get(index);
            if (!scenarioId.equals(scenario.path("id").asText())) {
                throw failure("frozen staged stress scenario inventory/order changed at " + index);
            }
            ObjectNode behavior = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-staged-stress-behavior-definition/1")
                    .put("strategy_family", FAMILY).put("mode_id", mode).put("candidate_id", candidateId)
                    .put("candidate_plan_sha256", plan.path("content_sha256").asText())
                    .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText())
                    .put("source_precommit_sha256", policy.precommitSha256()).put("scenario_id", scenarioId);
            behavior.set("scenario_policy", scenario.deepCopy());
            behavior.put("content_sha256", JsonHashes.ownHash(behavior));
            requests.add(new AttemptRequest(candidateId + "-stress-" + scenarioId,
                    "FROZEN_STAGED_STRESS_SCENARIO", behavior.path("content_sha256").asText(),
                    plan.path("content_sha256").asText(), plan.path("content_sha256").asText(),
                    plan.path("anchor_inventory_sha256").asText(), freeze.path("dataset_root_sha256").asText(),
                    plan.path("source_mode").asText(), "STAGED_STRESS_SENSITIVITY", matrixSha));
        }
        return requests;
    }

    /** Records the exact frozen five-scenario behavior matrix before any scenario outcomes are read. */
    public static ObjectNode recordStressScenarioAttemptsBeforeOutcomes(ObjectNode freeze) {
        return recordStressScenarioAttemptsBeforeOutcomes(freeze,
                StrategyResearchAuthoritativeV5.canonicalFamilyCustodyRoot(FAMILY));
    }

    static ObjectNode recordStressScenarioAttemptsBeforeOutcomes(ObjectNode freeze, Path familyRoot) {
        validateFreeze(freeze);
        if (isSynthetic(freeze.path("source_mode").asText())) return skipped("SYNTHETIC_FIXTURE_NOT_RECORDED", freeze);
        ObjectNode inventory = object(freeze, "candidate_inventory");
        verifyOwnHash(inventory, "core frozen candidate inventory");
        validateCoreInventory(inventory);
        return recordRequests(stressScenarioRequests(freeze, inventory.path("content_sha256").asText()),
                familyRoot, freeze.path("content_sha256").asText());
    }

    private static List<AttemptRequest> stressScenarioRequests(ObjectNode freeze, String candidateInventorySha) {
        LiquidationV2StressPolicyV1.Policy policy = LiquidationV2StressPolicyV1.reopen(object(freeze, "precommit"));
        ArrayNode scenarios = (ArrayNode) policy.toJson().path("scenarios");
        if (scenarios.size() != STRESS_SCENARIOS.size()) throw failure("frozen five-scenario policy inventory changed");
        String matrixSha = JsonHashes.canonicalSha256(scenarios);
        List<AttemptRequest> requests = new ArrayList<>();
        for (int index = 0; index < STRESS_SCENARIOS.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            String scenarioId = STRESS_SCENARIOS.get(index);
            if (!scenarioId.equals(scenario.path("id").asText())) {
                throw failure("frozen stress scenario inventory/order changed at " + index);
            }
            ObjectNode behavior = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-stress-behavior-definition/1")
                    .put("strategy_family", FAMILY).put("scenario_id", scenarioId)
                    .put("source_precommit_sha256", policy.precommitSha256());
            behavior.set("scenario_policy", scenario.deepCopy());
            behavior.put("content_sha256", JsonHashes.ownHash(behavior));
            requests.add(new AttemptRequest("liquidation-v2-stress-scenario-" + scenarioId,
                    "FROZEN_STRESS_SCENARIO", behavior.path("content_sha256").asText(),
                    freeze.path("content_sha256").asText(), null, candidateInventorySha,
                    freeze.path("dataset_root_sha256").asText(), freeze.path("source_mode").asText(),
                    "STRESS_SENSITIVITY", matrixSha));
        }
        return requests;
    }

    static ObjectNode reopenFamilyExposureAttempts(Path familyRoot) {
        Path normalized = normalizeRoot(familyRoot);
        Path headPath = normalized.resolve("exposure-head.json");
        Path journalPath = normalized.resolve(V2_JOURNAL_FILE);
        ObjectNode head = readHeadIfPresent(headPath);
        ObjectNode journal = readJournalIfPresent(journalPath);
        validateJournalAnchor(normalized.resolve(V2_JOURNAL_ANCHOR_FILE), journal, false);
        ObjectNode state = JsonHashes.mapper().createObjectNode().put("schema", STATE_SCHEMA)
                .put("version", 1).put("strategy_family", FAMILY)
                .put("historical_cumulative_k_status", head == null ? "UNKNOWN_NO_VERIFIED_CANONICAL_HEAD" : "KNOWN_FROM_CANONICAL_V5_HEAD")
                .put("journal_cumulative_k_status", "KNOWN_ATTEMPTS_ONLY_NOT_HISTORICAL_TOTAL")
                .put("canonical_head_present", head != null)
                .put("known_v002_attempt_count", journal == null ? 0 : journal.path("entries").size())
                .put("authoritative", false);
        if (head == null) state.putNull("historical_cumulative_k");
        else state.put("historical_cumulative_k", head.path("cumulative_k").asLong());
        if (head == null) state.putNull("canonical_head_sha256");
        else state.put("canonical_head_sha256", head.path("content_sha256").asText());
        if (journal == null) state.putNull("known_attempt_journal_sha256");
        else state.put("known_attempt_journal_sha256", journal.path("content_sha256").asText());
        ArrayNode attempts = state.putArray("known_v002_attempts");
        if (journal != null) for (JsonNode row : journal.path("entries")) attempts.add(row.deepCopy());
        state.put("content_sha256", JsonHashes.ownHash(state));
        return state;
    }

    private static ObjectNode recordRequests(List<AttemptRequest> requests, Path rawRoot, String freezeSha) {
        Path root = normalizeRoot(rawRoot);
        String rootFreezeSha = requireHash(freezeSha, "physical freeze SHA-256");
        try {
            Files.createDirectories(root);
        } catch (IOException error) {
            throw failure("cannot create canonical family custody root: " + error.getMessage());
        }
        Path headPath = root.resolve("exposure-head.json");
        Path journalPath = root.resolve(V2_JOURNAL_FILE);
        ObjectNode priorHead = readHeadIfPresent(headPath);
        ObjectNode journal = readJournalIfPresent(journalPath);
        if (priorHead != null && journal != null) {
            for (JsonNode entry : journal.path("entries")) priorHead = appendHeadEntry(headPath, priorHead, entry);
        }
        ArrayNode outcomes = JsonHashes.mapper().createArrayNode();
        for (AttemptRequest request : requests) {
            validateRequest(request);
            ObjectNode identity = attemptIdentity(request, rootFreezeSha);
            String status;
            long knownCount;
            String kStatus;
            if (priorHead != null) {
                ObjectNode updated = appendHeadAttempt(headPath, priorHead, request, identity);
                boolean already = updated.path("content_sha256").asText().equals(priorHead.path("content_sha256").asText());
                priorHead = updated;
                status = already ? "IDEMPOTENT_CANONICAL_HEAD" : "RECORDED_CANONICAL_HEAD";
                knownCount = priorHead.path("cumulative_k").asLong();
                kStatus = "KNOWN_FROM_CANONICAL_V5_HEAD";
            } else {
                ObjectNode append = appendJournalAttempt(journalPath, journal, request, identity);
                journal = (ObjectNode) append.path("journal");
                status = append.path("already_present").asBoolean(false)
                        ? "IDEMPOTENT_UNKNOWN_K_JOURNAL" : "RECORDED_UNKNOWN_K_JOURNAL";
                knownCount = journal.path("entries").size();
                kStatus = "UNKNOWN_NO_VERIFIED_CANONICAL_HEAD";
            }
            // The exact attempt journal carries candidate/freeze/physical identity, which the
            // legacy V5 HEAD intentionally reduces to behavior aliases. Mirror every new V5
            // attempt here so replay evidence can later verify an exact durable prefix.
            ObjectNode exact = appendJournalAttempt(journalPath, journal, request, identity);
            journal = (ObjectNode) exact.path("journal");
            if (priorHead != null) status += exact.path("already_present").asBoolean(false)
                    ? "_IDENTITY_JOURNAL_IDEMPOTENT" : "_IDENTITY_JOURNAL_RECORDED";
            outcomes.add(attemptReceipt(request, identity, status, knownCount, kStatus));
        }
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-family-exposure-attempt-receipt/1")
                .put("version", 1).put("strategy_family", FAMILY)
                .put("status", "PRE_OUTCOME_EXPOSURE_RECORDED")
                .put("physical_freeze_sha256", rootFreezeSha).put("historical_cumulative_k_status",
                        priorHead == null ? "UNKNOWN_NO_VERIFIED_CANONICAL_HEAD" : "KNOWN_FROM_CANONICAL_V5_HEAD")
                .put("authoritative", false);
        if (priorHead == null) result.putNull("historical_cumulative_k");
        else result.put("historical_cumulative_k", priorHead.path("cumulative_k").asLong());
        result.set("attempts", outcomes);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode attemptIdentity(AttemptRequest request, String physicalFreezeSha) {
        ObjectNode behavior = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-family-behavior/1")
                .put("strategy_family", FAMILY).put("candidate_id", request.candidateId())
                .put("candidate_variant_or_macro_policy", request.variant())
                .put("candidate_definition_sha256", request.candidateDefinitionSha256())
                .put("anchor_inventory_sha256", request.anchorInventorySha256())
                .put("attempt_kind", request.attemptKind());
        if (request.scenarioMatrixSha256() != null) behavior.put("scenario_matrix_sha256", request.scenarioMatrixSha256());
        behavior.put("behavior_sha256", JsonHashes.ownHash(behavior));
        ObjectNode identity = behavior.deepCopy().put("schema", "liquidation-v2-family-exposure-attempt/1")
                .put("attempt_freeze_sha256", request.attemptFreezeSha256())
                .put("physical_freeze_sha256", physicalFreezeSha)
                .put("dataset_sha256", request.datasetSha256()).put("source_mode", request.sourceMode());
        identity.put("physical_run_attempt_sha256", JsonHashes.ownHash(identity));
        return identity;
    }

    private static ObjectNode appendHeadAttempt(Path headPath, ObjectNode prior,
            AttemptRequest request, ObjectNode identity) {
        String alias = identity.path("behavior_sha256").asText();
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("filePath", headPath.toString())
                .put("expectedHeadSha256", prior.path("content_sha256").asText())
                .put("datasetSha256", request.datasetSha256()).put("fixedAttempt", true)
                .put("exposureAttemptCount", 1).put("source", "LIQUIDATION_V2_FROZEN_PRE_OUTCOME_ATTEMPT")
                .putNull("observedAt");
        ArrayNode aliases = append.putArray("behaviorAliases"); aliases.add(alias);
        ObjectNode next = StrategyStatisticalV5.appendExposureHeadFile(append);
        if (!FAMILY.equals(next.path("hypothesis_family").asText())
                || next.path("cumulative_k").asLong(-1) < prior.path("cumulative_k").asLong(-1)
                || next.path("exposure_attempt_k").asLong(-1) < prior.path("exposure_attempt_k").asLong(-1)) {
            throw failure("canonical V5 exposure HEAD append lost or decreased family exposure");
        }
        return next;
    }

    private static ObjectNode appendHeadEntry(Path headPath, ObjectNode prior, JsonNode journalEntry) {
        String alias = text(journalEntry, "behavior_sha256");
        String dataset = text(journalEntry, "dataset_sha256");
        if (hasHeadPair(prior, alias, dataset)) return prior;
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("filePath", headPath.toString())
                .put("expectedHeadSha256", prior.path("content_sha256").asText())
                .put("datasetSha256", dataset).put("fixedAttempt", true)
                .put("exposureAttemptCount", 1).put("source", "LIQUIDATION_V2_KNOWN_JOURNAL_RECONCILIATION")
                .putNull("observedAt");
        append.putArray("behaviorAliases").add(alias);
        return StrategyStatisticalV5.appendExposureHeadFile(append);
    }

    private static ObjectNode appendJournalAttempt(Path journalPath, ObjectNode prior,
            AttemptRequest request, ObjectNode identity) {
        Path lockPath = Path.of(journalPath + ".lock");
        try {
            Files.createDirectories(journalPath.getParent());
            if (Files.isSymbolicLink(journalPath) || Files.isSymbolicLink(lockPath)) {
                throw failure("v002 exposure journal cannot be a symbolic link");
            }
            // Keep the inode stable. Advisory locks disappear when a crashed writer exits;
            // CREATE_NEW sentinel files do not and would strand a resumed checkpoint.
            try (FileChannel lock = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = lock.lock()) {
                ObjectNode current = readJournalIfPresent(journalPath);
                Path anchorPath = journalPath.resolveSibling(V2_JOURNAL_ANCHOR_FILE);
                reconcileJournalAnchor(anchorPath, current);
                if (current != null && prior != null
                        && !current.path("content_sha256").asText().equals(prior.path("content_sha256").asText())) {
                    throw failure("stale v002 exposure journal predecessor");
                }
                if (current == null) current = emptyJournal();
                ObjectNode found = findJournalAttempt(current, request.candidateId(), request.attemptFreezeSha256());
                if (found != null) {
                    if (!found.path("behavior_sha256").asText().equals(identity.path("behavior_sha256").asText())
                            || !found.path("physical_freeze_sha256").asText().equals(identity.path("physical_freeze_sha256").asText())) {
                        throw failure("idempotent candidate/freeze exposure identity was rebound to different content");
                    }
                    return JsonHashes.mapper().createObjectNode().put("already_present", true)
                            .set("journal", current);
                }
                ObjectNode entry = identity.deepCopy();
                entry.put("sequence", current.path("entries").size() + 1L)
                        .put("previous_sha256", current.path("entries").isEmpty() ? genesisHash()
                                : JsonHashes.canonicalSha256(current.path("entries").get(current.path("entries").size() - 1)));
                entry.put("content_sha256", JsonHashes.ownHash(entry));
                ObjectNode next = current.deepCopy();
                ((ArrayNode) next.path("entries")).add(entry);
                next.put("known_attempt_count", next.path("entries").size());
                next.put("content_sha256", JsonHashes.ownHash(next));
                writeAtomic(journalPath, next);
                writeJournalAnchor(anchorPath, next);
                return JsonHashes.mapper().createObjectNode().put("already_present", false).set("journal", next);
            } catch (OverlappingFileLockException busy) {
                throw failure("v002 exposure journal already has an active writer in this process");
            }
        } catch (IOException error) {
            throw failure("cannot append v002 exposure journal: " + error.getMessage());
        }
    }

    private static ObjectNode emptyJournal() {
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", JOURNAL_SCHEMA)
                .put("version", 1).put("strategy_family", FAMILY)
                .put("status", "KNOWN_V002_ATTEMPTS_HISTORICAL_K_UNKNOWN")
                .putNull("historical_cumulative_k").put("cumulative_k_status", "UNKNOWN_NO_VERIFIED_CANONICAL_HEAD")
                .put("known_attempt_count", 0);
        result.putArray("entries"); result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode readJournalIfPresent(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("v002 exposure journal path is not a regular non-symlink file");
        }
        try {
            JsonNode raw = JsonHashes.mapper().readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (raw == null || !raw.isObject() || !JOURNAL_SCHEMA.equals(raw.path("schema").asText())
                    || raw.path("version").asInt(-1) != 1 || !FAMILY.equals(raw.path("strategy_family").asText())
                    || !raw.path("historical_cumulative_k").isNull()
                    || !"UNKNOWN_NO_VERIFIED_CANONICAL_HEAD".equals(raw.path("cumulative_k_status").asText())
                    || !raw.path("entries").isArray()
                    || raw.path("known_attempt_count").asInt(-1) != raw.path("entries").size()
                    || !JsonHashes.ownHash(raw).equals(raw.path("content_sha256").asText())) {
                throw failure("v002 exposure journal schema/status/hash is invalid");
            }
            validateJournalEntries(raw.path("entries"));
            return (ObjectNode) raw;
        } catch (IOException error) {
            throw failure("cannot reopen v002 exposure journal: " + error.getMessage());
        }
    }

    /**
     * A separate monotone witness makes deletion/truncation of the journal fail closed. The
     * witness may safely lag a committed journal after a crash; it is advanced under the same
     * advisory lock after the journal's atomic replacement.
     */
    private static void reconcileJournalAnchor(Path anchorPath, ObjectNode journal) {
        ObjectNode anchor = readJournalAnchor(anchorPath);
        if (anchor == null) {
            if (journal == null) writeJournalAnchor(anchorPath, null);
            else writeJournalAnchor(anchorPath, journal); // migrate an intact pre-anchor v002 journal
            return;
        }
        validateJournalAnchorValue(anchor, journal);
        if (journal != null && anchor.path("known_attempt_count").asInt() < journal.path("entries").size()) {
            writeJournalAnchor(anchorPath, journal);
        }
    }

    private static void validateJournalAnchor(Path anchorPath, ObjectNode journal, boolean allowMissing) {
        ObjectNode anchor = readJournalAnchor(anchorPath);
        if (anchor == null) {
            if (!allowMissing && journal == null) return;
            if (!allowMissing && journal != null) return; // compatible with an intact pre-anchor journal
            return;
        }
        validateJournalAnchorValue(anchor, journal);
    }

    private static ObjectNode readJournalAnchor(Path anchorPath) {
        if (!Files.exists(anchorPath, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(anchorPath) || !Files.isRegularFile(anchorPath, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("v002 exposure journal anchor is not a regular non-symlink file");
        }
        try {
            JsonNode raw = JsonHashes.mapper().readTree(Files.readString(anchorPath, StandardCharsets.UTF_8));
            if (raw == null || !raw.isObject() || !JOURNAL_ANCHOR_SCHEMA.equals(raw.path("schema").asText())
                    || raw.path("version").asInt(-1) != 1 || !FAMILY.equals(raw.path("strategy_family").asText())
                    || !JsonHashes.ownHash(raw).equals(raw.path("content_sha256").asText())) {
                throw failure("v002 exposure journal anchor schema/hash is invalid");
            }
            return (ObjectNode) raw;
        } catch (IOException error) {
            throw failure("cannot reopen v002 exposure journal anchor: " + error.getMessage());
        }
    }

    private static void validateJournalAnchorValue(ObjectNode anchor, ObjectNode journal) {
        int count = anchor.path("known_attempt_count").asInt(-1);
        if (count < 0 || !isHash(anchor.path("tip_sha256").asText())) {
            throw failure("v002 exposure journal anchor count/tip is invalid");
        }
        if (count == 0) {
            if (!genesisHash().equals(anchor.path("tip_sha256").asText())) throw failure("empty journal anchor has a non-genesis tip");
        } else {
            if (journal == null || count > journal.path("entries").size()) {
                throw failure("v002 exposure journal is missing or truncated behind its durable anchor");
            }
            String actualTip = JsonHashes.canonicalSha256(journal.path("entries").get(count - 1));
            if (!actualTip.equals(anchor.path("tip_sha256").asText())) {
                throw failure("v002 exposure journal no longer contains the anchored attempt prefix");
            }
        }
    }

    private static void writeJournalAnchor(Path anchorPath, ObjectNode journal) {
        ObjectNode anchor = JsonHashes.mapper().createObjectNode().put("schema", JOURNAL_ANCHOR_SCHEMA)
                .put("version", 1).put("strategy_family", FAMILY);
        int count = journal == null ? 0 : journal.path("entries").size();
        String tip = count == 0 ? genesisHash()
                : JsonHashes.canonicalSha256(journal.path("entries").get(count - 1));
        anchor.put("known_attempt_count", count).put("tip_sha256", tip);
        if (journal == null) anchor.putNull("journal_content_sha256");
        else anchor.put("journal_content_sha256", journal.path("content_sha256").asText());
        anchor.put("historical_cumulative_k_status", "UNKNOWN_NO_VERIFIED_CANONICAL_HEAD");
        anchor.put("content_sha256", JsonHashes.ownHash(anchor));
        try { writeAtomic(anchorPath, anchor); }
        catch (IOException error) { throw failure("cannot update v002 exposure journal anchor: " + error.getMessage()); }
    }

    private static ObjectNode readHeadIfPresent(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("canonical exposure HEAD is not a regular non-symlink file");
        }
        ObjectNode head = StrategyStatisticalV5.readExposureHeadFile(path);
        if (!FAMILY.equals(head.path("hypothesis_family").asText())) throw failure("canonical exposure HEAD has a different family");
        return head;
    }

    private static void validateJournalEntries(JsonNode entries) {
        Set<String> uniqueAttempts = new HashSet<>();
        String previous = genesisHash();
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            if (entry.path("sequence").asLong(-1) != index + 1L
                    || !previous.equals(entry.path("previous_sha256").asText())
                    || !JsonHashes.ownHash(entry).equals(entry.path("content_sha256").asText())
                    || !FAMILY.equals(entry.path("strategy_family").asText())) {
                throw failure("v002 exposure attempt hash chain is broken");
            }
            String candidate = text(entry, "candidate_id"), freeze = text(entry, "attempt_freeze_sha256");
            requireHash(freeze, "attempt freeze SHA-256");
            requireHash(text(entry, "behavior_sha256"), "attempt behavior SHA-256");
            requireHash(text(entry, "dataset_sha256"), "attempt dataset SHA-256");
            if (!uniqueAttempts.add(candidate + "|" + freeze)) throw failure("v002 exposure journal has a duplicate candidate/freeze attempt");
            previous = JsonHashes.canonicalSha256(entry);
        }
    }

    private static ObjectNode findJournalAttempt(ObjectNode journal, String candidate, String freeze) {
        for (JsonNode row : journal.path("entries")) if (candidate.equals(row.path("candidate_id").asText())
                && freeze.equals(row.path("attempt_freeze_sha256").asText())) return (ObjectNode) row;
        return null;
    }

    private static boolean hasHeadPair(ObjectNode head, String behavior, String dataset) {
        for (JsonNode row : head.path("entries")) if (behavior.equals(row.path("behavior_sha256").asText())
                && dataset.equals(row.path("dataset_sha256").asText())) return true;
        for (JsonNode row : head.path("fixed_attempt_pairs")) if (behavior.equals(row.path("behavior_sha256").asText())
                && dataset.equals(row.path("dataset_sha256").asText())) return true;
        return false;
    }

    private static ObjectNode attemptReceipt(AttemptRequest request, ObjectNode identity,
            String status, long knownCount, String kStatus) {
        return JsonHashes.mapper().createObjectNode().put("candidate_id", request.candidateId())
                .put("candidate_variant_or_macro_policy", request.variant())
                .put("attempt_freeze_sha256", request.attemptFreezeSha256())
                .put("physical_freeze_sha256", identity.path("physical_freeze_sha256").asText())
                .put("behavior_sha256", identity.path("behavior_sha256").asText())
                .put("status", status).put("known_or_cumulative_count", knownCount)
                .put("cumulative_k_status", kStatus);
    }

    private static ObjectNode skipped(String status, ObjectNode freeze) {
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-family-exposure-attempt-receipt/1")
                .put("version", 1).put("strategy_family", FAMILY).put("status", status)
                .put("physical_freeze_sha256", freeze.path("content_sha256").asText())
                .put("custody_mutated", false).put("authoritative", false);
        result.putNull("historical_cumulative_k");
        result.put("historical_cumulative_k_status", "UNKNOWN_SYNTHETIC_FIXTURE_NOT_RECORDED")
                .putArray("attempts");
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static void validateRequest(AttemptRequest request) {
        boolean core = CORE_IDS.contains(request.candidateId());
        boolean staged = Set.of(LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE,
                LiquidationV2StagedCandidateInventoryV1.MACRO_CANDIDATE).contains(request.candidateId());
        boolean coreStress = STRESS_SCENARIOS.stream().anyMatch(id -> request.candidateId()
                .equals("liquidation-v2-stress-scenario-" + id));
        boolean stagedStress = Set.of(LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE,
                LiquidationV2StagedCandidateInventoryV1.MACRO_CANDIDATE).stream().anyMatch(candidate ->
                        STRESS_SCENARIOS.stream().anyMatch(id -> request.candidateId().equals(candidate + "-stress-" + id)));
        if (!core && !staged && !coreStress && !stagedStress) {
            throw failure("exposure attempt candidate is outside the frozen strategy family inventory");
        }
        if ((core && !"CORE_CANDIDATE".equals(request.attemptKind()))
                || (staged && !"STAGED_CANDIDATE".equals(request.attemptKind()))
                || (coreStress && (!"STRESS_SENSITIVITY".equals(request.attemptKind())
                    || !isHash(request.scenarioMatrixSha256())))
                || (stagedStress && (!"STAGED_STRESS_SENSITIVITY".equals(request.attemptKind())
                    || !isHash(request.scenarioMatrixSha256())))) {
            throw failure("family exposure attempt kind differs from its frozen candidate class");
        }
        requireHash(request.candidateDefinitionSha256(), "candidate definition hash");
        requireHash(request.attemptFreezeSha256(), "candidate freeze hash");
        if (request.planSha256() != null) requireHash(request.planSha256(), "staged plan hash");
        requireHash(request.anchorInventorySha256(), "candidate anchor inventory hash");
        requireHash(request.datasetSha256(), "dataset root SHA-256");
        if (!Set.of("SYNTHETIC_DEVELOPMENT_ONLY", "PROXY_DISCLOSED_DEVELOPMENT_ONLY").contains(request.sourceMode())) {
            throw failure("exposure attempt has an unsupported physical source mode");
        }
    }

    private static void validateCoreInventory(ObjectNode inventory) {
        if (!LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA.equals(inventory.path("schema").asText())
                || inventory.path("version").asInt(-1) != 1 || !FAMILY.equals(inventory.path("strategy_family").asText())
                || !inventory.path("current_candidates").isArray() || inventory.path("current_candidates").size() != CORE_IDS.size()) {
            throw failure("core pre-outcome freeze does not contain the exact frozen four candidate inventory");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode row : inventory.path("current_candidates")) {
            String id = text(row, "candidate_id");
            if (!CORE_IDS.contains(id) || !CORE_VARIANTS.get(id).equals(row.path("variant").asText()) || !seen.add(id)) {
                throw failure("core pre-outcome freeze has an unknown or duplicate candidate/variant");
            }
        }
    }

    private static void validateFreeze(ObjectNode freeze) {
        verifyOwnHash(freeze, "pre-outcome freeze");
        if (!FREEZE_SCHEMA.equals(freeze.path("schema").asText()) || freeze.path("version").asInt(-1) != 1
                || !"FROZEN_BEFORE_OUTCOME_READ".equals(freeze.path("status").asText())
                || freeze.path("outcomes_examined").asBoolean(true)
                || !freeze.path("source_mode").isTextual()
                || !freeze.path("dataset_root_sha256").isTextual()
                || !freeze.path("candidate_inventory").isObject()
                || !freeze.path("precommit").isObject()) {
            throw failure("exposure must be recorded from a valid pre-outcome physical freeze");
        }
        requireHash(freeze.path("dataset_root_sha256").asText(), "dataset root SHA-256");
    }

    private static ObjectNode object(ObjectNode node, String field) {
        if (!node.path(field).isObject()) throw failure("exposure artifact requires object " + field);
        return (ObjectNode) node.path(field);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw failure("exposure artifact requires " + field);
        return value.asText();
    }

    private static String requireHash(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw failure(label + " must be lowercase SHA-256 hex");
        return value;
    }

    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static void verifyOwnHash(ObjectNode value, String label) {
        if (!JsonHashes.ownHash(value).equals(value.path("content_sha256").asText())) throw failure(label + " content hash is invalid");
    }

    private static boolean isSynthetic(String sourceMode) { return "SYNTHETIC_DEVELOPMENT_ONLY".equals(sourceMode); }

    private static String genesisHash() { return JsonHashes.sha256("LIQUIDATION_V2_FAMILY_EXPOSURE_GENESIS"); }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "familyRoot");
        if (Files.isSymbolicLink(root)) throw failure("canonical family custody root cannot be a symbolic link");
        return root.toAbsolutePath().normalize();
    }

    private static void writeAtomic(Path target, ObjectNode value) throws IOException {
        Path temporary = Path.of(target + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw failure("v002 journal temp file cannot be a symbolic link");
        byte[] bytes = JsonHashes.canonicalBytes(value);
        try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            file.write(java.nio.ByteBuffer.wrap(bytes)); file.force(true);
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record AttemptRequest(String candidateId, String variant,
            String candidateDefinitionSha256, String attemptFreezeSha256, String planSha256,
            String anchorInventorySha256, String datasetSha256, String sourceMode,
            String attemptKind, String scenarioMatrixSha256) {}

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}
