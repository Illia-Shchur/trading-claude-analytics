package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.research.legacy.LegacyResearchNext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact JSON/tree and filesystem port of {@code strategy-prospective-v5.mjs}.
 *
 * <p>All mutable state is represented by immutable, content-addressed files.  The
 * API intentionally accepts JSON trees rather than closed records: these contracts
 * are signed and their unknown fields must not be silently discarded.</p>
 */
public final class StrategyProspectiveV5 {
    public static final long MAX_PROSPECTIVE_LEASE_MS = 90L * 86_400_000L;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> EVENTS = Set.of("SIGNAL", "OUTCOME");
    private static final Set<String> ASSETS = Set.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final ThreadLocal<java.util.function.Consumer<String>> FAULT_HOOK = new ThreadLocal<>();

    private StrategyProspectiveV5() {}

    // ---------------------------------------------------------------------
    // Canonical/hash/signature exports
    // ---------------------------------------------------------------------

    public static String hash(JsonNode value) { return Sha256.canonicalHex(value == null ? NullNode.instance : value); }
    public static String hash(String value) { return Sha256.hex(value); }
    public static String hash(byte[] value) { return Sha256.hex(value); }
    public static String hash(Object value) {
        if (value instanceof JsonNode node) return hash(node);
        if (value instanceof String text) return hash(text);
        if (value instanceof byte[] bytes) return hash(bytes);
        return hash(com.tradinganalytics.infrastructure.security.JsonHashes.mapper().valueToTree(value));
    }

    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }
    public static String ownHash(JsonNode value, String field) {
        ObjectNode copy = object(value, "value").deepCopy();
        copy.remove(field);
        return hash(copy);
    }
    public static ObjectNode withHash(JsonNode value) { return withHash(value, "content_sha256"); }
    public static ObjectNode withHash(JsonNode value, String field) {
        ObjectNode copy = object(value, "value").deepCopy();
        copy.put(field, ownHash(copy, field));
        return copy;
    }

    public static String signPayload(JsonNode value, String privateKeyPem) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey(privateKeyPem));
            signer.update(LegacyResearchNext.stable(value).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception error) {
            throw new IllegalArgumentException("Ed25519 private key is invalid", error);
        }
    }

    public static String signPayload(Object value, String privateKeyPem) {
        return signPayload(toNode(value), privateKeyPem);
    }

    public static boolean verifyPayload(JsonNode value, String signature, String publicKeyPem) {
        if (signature == null || signature.isEmpty() || publicKeyPem == null || publicKeyPem.isEmpty()) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(publicKeyPem));
            verifier.update(LegacyResearchNext.stable(value).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean verifyPayload(Object value, String signature, String publicKeyPem) {
        return verifyPayload(toNode(value), signature, publicKeyPem);
    }

    // ---------------------------------------------------------------------
    // Content-addressed prospective ledger
    // ---------------------------------------------------------------------

    public static ObjectNode createProspectiveLedger(ObjectNode options) {
        ObjectNode o = options == null ? JSON.objectNode() : options;
        String lineage = requireHash(first(o, "lineageSha256", "lineage_sha256"), "lineage_sha256");
        String rawPath = text(first(o, "path", "ledgerPath", "ledger_path"));
        if (rawPath.isEmpty()) throw new IllegalArgumentException("ledger path is required");
        List<String> assets = new ArrayList<>();
        JsonNode assetNode = first(o, "assets");
        if (assetNode != null && assetNode.isArray()) for (JsonNode asset : assetNode) assets.add(text(asset).toLowerCase(Locale.ROOT));
        assets = new ArrayList<>(new LinkedHashSet<>(assets)); assets.sort(String::compareTo);
        Path path = absolute(rawPath);
        secureParents(path); realDirOrCreate(path);
        secureParents(path.resolve("events")); realDirOrCreate(path.resolve("events"));
        ObjectNode index = ledgerIndex(lineage);
        index.set("assets", strings(assets));
        index.set("frozen_start", timestampNode(first(o, "frozenStart", "frozen_start")));
        index.set("frozen_end", timestampNode(first(o, "frozenEnd", "frozen_end")));
        ObjectNode finalIndex = withHash(index);
        writeExclusive(path.resolve("HEAD.json"), pretty(finalIndex));
        ObjectNode snapshot = JSON.objectNode().put("schema", "strategy-prospective-ledger/2").put("version", 2)
                .put("lineage_sha256", lineage).set("assets", strings(assets));
        snapshot.set("frozen_start", finalIndex.get("frozen_start")); snapshot.set("frozen_end", finalIndex.get("frozen_end"));
        snapshot.put("sequence", 0).put("head_sha256", text(finalIndex.get("head_sha256"))).putArray("events");
        snapshot.put("index_path", path.resolve("HEAD.json").toString());
        snapshot = withHash(snapshot); validateSchema(snapshot); return snapshot;
    }

    public static ObjectNode createProspectiveLedger(Path path, String lineageSha256, List<String> assets,
                                                      Object frozenStart, Object frozenEnd) {
        ObjectNode o = JSON.objectNode().put("path", path.toString()).put("lineage_sha256", lineageSha256);
        ArrayNode rows = o.putArray("assets"); if (assets != null) assets.forEach(rows::add);
        setNullable(o, "frozen_start", frozenStart); setNullable(o, "frozen_end", frozenEnd);
        return createProspectiveLedger(o);
    }

    public static ArrayNode recoverProspectiveLedger(Path path) {
        return withLock(path, () -> reconcileTransactionsUnlocked(path));
    }

    private static ArrayNode reconcileTransactionsUnlocked(Path path) {
        ArrayNode recovered = JSON.arrayNode();
        Path root = absolute(path.toString()).resolve(".transactions");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return recovered;
        List<Path> journals = listRegular(root, ".json");
        journals.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path file : journals) {
            String id = file.getFileName().toString().replaceFirst("\\.json$", "");
            ObjectNode journal = readTransactionJournal(path, id);
            ObjectNode current = readLedgerIndex(path);
            ObjectNode target = object(journal.get("updated_index"), "updated_index");
            if (!"strategy-prospective-ledger-index/1".equals(text(target.get("schema")))
                    || !ownHash(target).equals(text(target.get("content_sha256")))
                    || !text(target.get("lineage_sha256")).equals(text(journal.get("lineage_sha256")))) {
                throw new IllegalArgumentException("ledger transaction target is invalid: " + file);
            }
            boolean committedPrefix = "COMMITTED".equals(text(journal.get("state")))
                    && current.path("sequence").asLong() >= target.path("sequence").asLong()
                    && current.path("event_refs").size() >= target.path("event_refs").size();
            if (committedPrefix) {
                boolean same = true;
                for (int i = 0; i < target.path("event_refs").size(); i++) {
                    if (!current.path("event_refs").get(i).equals(target.path("event_refs").get(i))) { same = false; break; }
                }
                if (same) continue;
            }
            boolean targetCommitted = text(current.get("content_sha256")).equals(text(target.get("content_sha256")))
                    && text(current.get("head_sha256")).equals(text(target.get("head_sha256")))
                    && current.path("sequence").asInt() == target.path("sequence").asInt();
            if (!targetCommitted && (!text(current.get("lineage_sha256")).equals(text(journal.get("lineage_sha256")))
                    || !text(current.get("head_sha256")).equals(text(journal.get("expected_head_sha256")))
                    || current.path("sequence").asInt() != journal.path("expected_sequence").asInt())) {
                throw new IllegalArgumentException("ledger transaction conflicts with current HEAD: " + file.getFileName());
            }
            ArrayNode refs = (ArrayNode) journal.get("refs"); ArrayNode events = (ArrayNode) journal.get("events");
            for (int i = 0; i < refs.size(); i++) {
                ObjectNode ref = object(refs.get(i), "transaction ref"); ObjectNode event = object(events.get(i), "transaction event");
                if (!text(event.get("event_sha256")).equals(text(ref.get("event_sha256")))
                        || event.path("sequence").asInt() != ref.path("sequence").asInt()
                        || !text(event.get("event_sha256")).equals(ownHash(event, "event_sha256"))) {
                    throw new IllegalArgumentException("ledger transaction event journal is invalid: " + file.getFileName());
                }
                byte[] bytes = eventBytes(event); if (!hash(bytes).equals(text(ref.get("byte_sha256")))) throw new IllegalArgumentException("ledger transaction event byte hash is invalid: " + file.getFileName());
                Path staged = pathOf(path, text(ref.get("staged_path"))); Path targetFile = pathOf(path, text(ref.get("path")));
                if (!targetCommitted && !exists(staged) && !exists(targetFile)) { secureParents(staged.getParent()); writeExclusive(staged, bytes); }
                if (!targetCommitted) promoteNoOverwrite(staged, targetFile, text(ref.get("byte_sha256")));
            }
            if (!targetCommitted) atomic(path.resolve("HEAD.json"), target);
            ObjectNode committed = journal.deepCopy();
            committed.put("state", "COMMITTED");
            if (!journal.hasNonNull("committed_at")) committed.put("committed_at", iso(System.currentTimeMillis()));
            committed = withHash(committed);
            atomic(transactionPath(path, id), committed); removeStage(path, committed); recovered.add(id);
        }
        return recovered;
    }

    public static ArrayNode recoverProspectiveLedger(String path) { return recoverProspectiveLedger(Path.of(path)); }

    public static ObjectNode readProspectiveLedger(Path path) { return readProspectiveLedger(path, JSON.objectNode()); }
    public static ObjectNode readProspectiveLedger(String path) { return readProspectiveLedger(Path.of(path)); }
    public static ObjectNode readProspectiveLedger(Path path, ObjectNode options) {
        return withLock(path, () -> {
            recoverUnlocked(path);
            return readLedgerRaw(path, options == null ? JSON.objectNode() : options);
        });
    }
    public static ObjectNode readProspectiveLedger(String path, ObjectNode options) { return readProspectiveLedger(Path.of(path), options); }

    public static boolean verifyCompletedBarNoOp(ObjectNode options) {
        ObjectNode o = options == null ? JSON.objectNode() : options;
        JsonNode ledger = o.get("ledger"), bar = o.get("bar");
        if (ledger == null || !barOrEmpty(bar).hasNonNull("completed_bar_id")) return false;
        String asset = text(bar.get("asset")).toLowerCase(Locale.ROOT), id = text(bar.get("completed_bar_id"));
        List<JsonNode> matches = new ArrayList<>(); for (JsonNode row : rows(ledger.path("events"))) if ("SIGNAL".equals(text(row.get("kind"))) && asset.equals(text(row.get("asset")).toLowerCase(Locale.ROOT)) && id.equals(text(row.get("completed_bar_id")))) matches.add(row);
        if (matches.isEmpty()) return false; if (matches.size() != 1) throw new IllegalArgumentException("duplicate completed-bar identity is not a valid no-op");
        JsonNode event = matches.get(0), payload = event.path("payload");
        if (!text(event.get("source_receipt_sha256")).equals(text(o.get("sourceReceiptSha256")))
                || !text(payload.get("signal_decision_sha256")).equals(text(o.get("signalDecisionSha256")))
                || !text(payload.get("reservation_sha256")).equals(text(o.get("reservationSha256")))
                || !text(payload.get("candidate_set_sha256")).equals(text(o.get("candidateSetSha256")))
                || !text(payload.get("evaluator_code_sha256")).equals(text(o.get("evaluatorCodeSha256")))
                || !text(payload.get("feature_input_sha256")).equals(text(o.get("featureInputSha256")))
                || !text(event.get("decision_time")).equals(iso(time(bar.get("availability_time"))))
                || !text(event.get("availability_time")).equals(iso(time(bar.get("availability_time"))))) throw new IllegalArgumentException("same completed-bar identity has divergent source or signal payload");
        for (JsonNode row : rows(ledger.path("events"))) if ("SIGNAL".equals(text(row.get("kind"))) && asset.equals(text(row.get("asset")).toLowerCase(Locale.ROOT)) && time(row.get("decision_time")) > time(bar.get("availability_time"))) throw new IllegalArgumentException("same completed-bar identity is not the latest ledger bar");
        return true;
    }

    public static boolean verifyCompletedBarNoOp(JsonNode options) { return verifyCompletedBarNoOp((ObjectNode) options); }

    public static ArrayNode appendProspectiveEventsAtomically(ObjectNode options) {
        ObjectNode o = options == null ? JSON.objectNode() : options; JsonNode eventRows = o.get("events");
        if (eventRows == null || !eventRows.isArray() || eventRows.isEmpty()) throw new IllegalArgumentException("at least one prospective event is required");
        Path path = Path.of(text(first(o, "path", "ledgerPath", "ledger_path"))); long now = nowAt(o.get("nowAt"));
        String expectedHead = requireHash(first(o, "expectedHeadSha256", "expected_head_sha256"), "expected_head_sha256");
        return withLock(path, () -> {
            recoverUnlocked(path); ObjectNode initial = readLedgerRaw(path, JSON.objectNode());
            String transactionId = transactionFingerprint(expectedHead, rows(eventRows)); ObjectNode existing = readTransactionJournal(path, transactionId);
            if (existing != null) { ObjectNode current = readLedgerIndex(path); ObjectNode target = object(existing.get("updated_index"), "updated_index"); if (text(current.get("content_sha256")).equals(text(target.get("content_sha256"))) && text(current.get("head_sha256")).equals(text(target.get("head_sha256")))) return arrayOf(existing.get("events")); }
            if (!text(initial.get("current_head_sha256")).equals(expectedHead)) throw new IllegalArgumentException("prospective ledger CAS head mismatch");
            ObjectNode index = readLedgerIndex(path), working = initial.deepCopy(); ArrayNode prepared = JSON.arrayNode();
            for (JsonNode raw : eventRows) { ObjectNode next = prepareEvent(working, object(raw, "event"), now); next.put("sequence", working.path("sequence").asInt() + 1).put("previous_head_sha256", text(working.get("current_head_sha256"))); next.put("event_sha256", ownHash(next, "event_sha256")); validateSchema(next); prepared.add(next); working.set("events", arrayOfConcat(working.get("events"), next)); working.put("sequence", next.path("sequence").asInt()).put("current_head_sha256", text(next.get("event_sha256"))).put("head_sha256", text(next.get("event_sha256"))); }
            ArrayNode refs = JSON.arrayNode(); for (JsonNode raw : prepared) { String file = String.format(Locale.ROOT, "events/%012d-%s.json", raw.path("sequence").asInt(), text(raw.get("event_sha256"))); String staged = ".transactions/" + transactionId + "/" + file; refs.add(JSON.objectNode().put("sequence", raw.path("sequence").asInt()).put("event_sha256", text(raw.get("event_sha256"))).put("byte_sha256", hash(eventBytes(raw))).put("path", file).put("staged_path", staged)); }
            ArrayNode persistedRefs = JSON.arrayNode(); refs.forEach(r -> { ObjectNode c = object(r, "ref").deepCopy(); c.remove("staged_path"); persistedRefs.add(c); });
            ObjectNode updated = index.deepCopy(); updated.put("sequence", working.path("sequence").asInt()).put("head_sha256", text(working.get("current_head_sha256"))).set("event_refs", concat(index.get("event_refs"), persistedRefs)); updated = withHash(updated);
            ObjectNode journal = JSON.objectNode().put("schema", "strategy-prospective-ledger-transaction/1").put("version", 1).put("transaction_id", transactionId).put("lineage_sha256", text(index.get("lineage_sha256"))).put("expected_head_sha256", expectedHead).put("expected_sequence", index.path("sequence").asInt()).put("state", "PREPARED").put("stage_root", ".transactions/" + transactionId);
            journal.set("refs", refs); journal.set("events", prepared); journal.set("updated_index", updated);
            journal.put("created_at", iso(System.currentTimeMillis())); writeJournal(path, journal); hook(o, "after-journal");
            for (int i = 0; i < prepared.size(); i++) { ObjectNode ref = object(refs.get(i), "ref"); Path staged = pathOf(path, text(ref.get("staged_path"))); secureParents(staged.getParent()); if (!exists(staged)) writeExclusive(staged, eventBytes(prepared.get(i))); else if (!hash(readBytes(staged)).equals(text(ref.get("byte_sha256")))) throw new IllegalArgumentException("staged event collision at " + staged); hook(o, "after-stage-" + (i + 1)); ObjectNode latest = readTransactionJournal(path, transactionId); latest.put("state", "STAGED").put("staged_count", i + 1); writeJournal(path, latest); hook(o, "after-stage-journal-" + (i + 1)); }
            ObjectNode ready = readTransactionJournal(path, transactionId); ready.put("state", "READY"); writeJournal(path, ready); hook(o, "after-ready-journal");
            for (int i = 0; i < refs.size(); i++) { ObjectNode ref = object(refs.get(i), "ref"); promoteNoOverwrite(pathOf(path, text(ref.get("staged_path"))), pathOf(path, text(ref.get("path"))), text(ref.get("byte_sha256"))); hook(o, "after-promote-" + (i + 1)); }
            ObjectNode promoted = readTransactionJournal(path, transactionId); promoted.put("state", "PROMOTED"); writeJournal(path, promoted); hook(o, "after-promoted-journal"); atomic(path.resolve("HEAD.json"), updated); hook(o, "after-head"); ObjectNode committed = readTransactionJournal(path, transactionId); committed.put("state", "COMMITTED"); committed.put("committed_at", iso(System.currentTimeMillis())); writeJournal(path, committed); hook(o, "after-commit-journal"); removeStage(path, committed); return prepared;
        });
    }

    public static ArrayNode appendProspectiveEventsAtomically(JsonNode options) { return appendProspectiveEventsAtomically((ObjectNode) options); }
    public static ObjectNode appendProspectiveEvent(ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options.deepCopy(); if (!o.has("events")) { JsonNode event = o.get("event"); if (event == null) throw new IllegalArgumentException("at least one prospective event is required"); o.set("events", JSON.arrayNode().add(event)); } return object(appendProspectiveEventsAtomically(o).get(0), "event"); }
    public static ObjectNode appendProspectiveEvent(JsonNode options) { return appendProspectiveEvent((ObjectNode) options); }

    // ---------------------------------------------------------------------
    // Completed bar cycle and replay registry
    // ---------------------------------------------------------------------

    public static ObjectNode appendCompletedBarCycle(ObjectNode options) {
        ObjectNode o = options == null ? JSON.objectNode() : options;
        ObjectNode reservation = physicalJson(Path.of(path(o, "reservationPath", "reservation_path")), requiredHash(o, "reservationSha256", "reservation_sha256"), Set.of("strategy-prospective-reservation/1"));
        if (!"FROZEN".equals(text(reservation.get("status"))) || !"SHADOW".equals(text(reservation.get("decision")))) throw new IllegalArgumentException("reservation is not frozen SHADOW");
        String signalPath = text(first(o, "signalDecisionPath", "signal_decision_path")); String signalSha = requiredHash(o, "signalDecisionSha256", "signal_decision_sha256"); if (signalPath.isEmpty()) throw new IllegalArgumentException("physical signal decision artifact is required"); ObjectNode decision = physicalJson(Path.of(signalPath), signalSha, Set.of("strategy-prospective-signal-decision/1")); validateSchema(decision);
        ObjectNode bar = object(o.get("bar"), "bar"); String lineage = text(reservation.get("lineage_sha256"));
        if (!"SHADOW".equals(text(decision.get("decision"))) || !"SHADOW".equals(text(decision.get("signal_state"))) || !lineage.equals(text(decision.get("lineage_sha256"))) || !text(decision.get("completed_bar_id")).equals(text(bar.get("completed_bar_id"))) || !text(decision.get("source_receipt_sha256")).equals(requiredHash(o, "sourceReceiptSha256", "source_receipt_sha256")) || !text(decision.get("reservation_sha256")).equals(requiredHash(o, "reservationSha256", "reservation_sha256")) || !isHash(decision.get("candidate_set_sha256")) || !isHash(decision.get("evaluator_code_sha256")) || !isHash(decision.get("feature_input_sha256")) || !o.hasNonNull("candidateSetPath") || !o.hasNonNull("candidateSetSha256") || !text(decision.get("candidate_set_sha256")).equals(requiredHash(o, "candidateSetSha256", "candidate_set_sha256")) || !o.hasNonNull("evaluatorCodePath") || !o.hasNonNull("evaluatorCodeSha256") || !text(decision.get("evaluator_code_sha256")).equals(requiredHash(o, "evaluatorCodeSha256", "evaluator_code_sha256")) || time(decision.get("availability_cutoff_time")) < time(bar.get("availability_time")) || (decision.hasNonNull("decision_time") && time(decision.get("decision_time")) > time(decision.get("availability_cutoff_time")))) throw new IllegalArgumentException("signal decision is not bound to frozen reservation/source/evaluator/cutoff");
        ObjectNode feature = physicalJson(Path.of(text(o.get("featureInputPath"))), requiredHash(o, "featureInputSha256", "feature_input_sha256"), Set.of("research-feature-set/1", "strategy-v5-source-receipt/1"));
        if (!validFeatureInput(feature)) throw new IllegalArgumentException("feature input is not a verified authoritative dependency");
        ObjectNode candidate = physicalJson(Path.of(text(o.get("candidateSetPath"))), requiredHash(o, "candidateSetSha256", "candidate_set_sha256"), Set.of("strategy-candidate-set/4", "strategy-candidate-set/5", "strategy-v5-statistical-input/1")); if (!validCandidateSet(candidate)) throw new IllegalArgumentException("candidate set is not a verified authoritative dependency");
        ObjectNode evaluator = physicalJson(Path.of(text(o.get("evaluatorCodePath"))), requiredHash(o, "evaluatorCodeSha256", "evaluator_code_sha256"), Set.of("strategy-v5-evaluator-spec/1")); if (!"FROZEN".equals(text(evaluator.get("status"))) || !isHash(evaluator.get("code_sha256")) || !isHash(evaluator.get("worker_code_sha256"))) throw new IllegalArgumentException("evaluator code is not a verified authoritative dependency");
        if (!text(evaluator.get("code_sha256")).equals(StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_CODE_SHA256)
                || !text(evaluator.get("worker_code_sha256")).equals(StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256)) {
            throw new IllegalArgumentException("frozen evaluator code is not the exact runtime evaluator implementation");
        }
        if (time(bar.get("availability_time")) > nowAt(o.get("nowAt")) || !bar.hasNonNull("completed_bar_id")) throw new IllegalArgumentException("bar is not completed"); if (reservation.hasNonNull("frozen_start") && time(bar.get("availability_time")) < time(reservation.get("frozen_start"))) throw new IllegalArgumentException("bar precedes reservation freeze");
        Path ledgerPath = Path.of(text(o.get("path"))); long cycleNow = nowAt(o.get("nowAt")); ObjectNode ledger = readProspectiveLedger(ledgerPath, JSON.objectNode().put("nowAt", cycleNow)); String expected = o.hasNonNull("expectedHeadSha256") ? text(o.get("expectedHeadSha256")) : text(ledger.get("current_head_sha256")); ObjectNode receipt = validateSourceReceipt(Path.of(text(o.get("sourceReceiptPath"))), requiredHash(o, "sourceReceiptSha256", "source_receipt_sha256"), text(bar.get("asset")).toLowerCase(Locale.ROOT), text(bar.get("completed_bar_id")), lineage, cycleNow);
        recomputeSignalDecision(decision, bar, receipt, candidate, evaluator, requiredHash(o, "featureInputSha256", "feature_input_sha256"), requiredHash(o, "sourceReceiptSha256", "source_receipt_sha256"), requiredHash(o, "reservationSha256", "reservation_sha256"), requiredHash(o, "candidateSetSha256", "candidate_set_sha256"), requiredHash(o, "evaluatorCodeSha256", "evaluator_code_sha256"), lineage);
        boolean outcomeRequested = List.of("outcomeResolutionPath", "outcomeResolutionSha256", "outcomeReceiptPath", "outcomeReceiptSha256", "outcomeResolutionSourcePath", "outcomeResolutionSourceSha256", "labelSourcePath", "labelSourceSha256", "executionSourcePath", "executionSourceSha256").stream().anyMatch(o::hasNonNull);
        if (outcomeRequested) {
            for (String key : List.of("outcomeResolutionPath", "outcomeResolutionSha256", "outcomeReceiptPath", "outcomeReceiptSha256", "outcomeResolutionSourcePath", "outcomeResolutionSourceSha256", "labelSourcePath", "labelSourceSha256", "executionSourcePath", "executionSourceSha256")) {
                if (!o.hasNonNull(key)) throw new IllegalArgumentException("complete physical outcome artifacts are required");
            }
            validateOutcomeArtifacts(o, bar, lineage);
        }
        ObjectNode signal = JSON.objectNode().put("event_id", text(bar.get("asset")) + ":" + text(bar.get("completed_bar_id")) + ":SIGNAL").put("kind", "SIGNAL").put("asset", text(bar.get("asset"))).put("completed_bar_id", text(bar.get("completed_bar_id"))).put("decision_time", iso(time(bar.get("availability_time")))).put("availability_time", iso(time(bar.get("availability_time")))).put("source_receipt_path", text(o.get("sourceReceiptPath"))).put("source_receipt_sha256", requiredHash(o, "sourceReceiptSha256", "source_receipt_sha256")).put("source_receipt_ref", text(receipt.get("completed_bar_id"))).put("lineage_sha256", lineage);
        signal.set("payload", JSON.objectNode().put("signal_state", text(decision.get("signal_state"))).put("signal_intent", decision.path("signal_intent").asBoolean()).put("signal_decision_sha256", signalSha).put("reservation_sha256", requiredHash(o, "reservationSha256", "reservation_sha256")).put("candidate_set_sha256", text(decision.get("candidate_set_sha256"))).put("evaluator_code_sha256", text(decision.get("evaluator_code_sha256"))).put("feature_input_sha256", text(decision.get("feature_input_sha256"))).put("feature_row_sha256", text(decision.get("feature_row_sha256"))).put("availability_cutoff_time", text(decision.get("availability_cutoff_time"))));
        if (!outcomeRequested) { ObjectNode appendOptions = JSON.objectNode().put("path", ledgerPath.toString()).put("expected_head_sha256", expected).put("nowAt", cycleNow); appendOptions.set("event", signal); ObjectNode result = JSON.objectNode(); result.set("signal", appendProspectiveEvent(appendOptions)); result.set("outcome", NullNode.instance); result.put("activated", false); return result; }
        ObjectNode resolution = physicalJson(Path.of(text(o.get("outcomeResolutionPath"))), requiredHash(o, "outcomeResolutionSha256", "outcome_resolution_sha256"), Set.of("strategy-prospective-outcome-resolution/1")); validateSchema(resolution); ObjectNode outcomeReceipt = validateSourceReceipt(Path.of(text(o.get("outcomeReceiptPath"))), requiredHash(o, "outcomeReceiptSha256", "outcome_receipt_sha256"), text(bar.get("asset")).toLowerCase(Locale.ROOT), text(bar.get("completed_bar_id")), lineage, nowAt(o.get("nowAt")));
        ObjectNode outcome = JSON.objectNode().put("event_id", text(bar.get("asset")) + ":" + text(bar.get("completed_bar_id")) + ":OUTCOME").put("kind", "OUTCOME").put("asset", text(bar.get("asset"))).put("completed_bar_id", text(bar.get("completed_bar_id"))).put("decision_time", text(outcomeReceipt.get("availability_time"))).put("availability_time", text(outcomeReceipt.get("availability_time"))).put("source_receipt_path", text(o.get("outcomeReceiptPath"))).put("source_receipt_sha256", requiredHash(o, "outcomeReceiptSha256", "outcome_receipt_sha256")).put("source_receipt_ref", text(outcomeReceipt.get("completed_bar_id"))).put("lineage_sha256", lineage);
        outcome.set("payload", JSON.objectNode().put("resolution", text(resolution.get("resolution"))).put("resolution_sha256", requiredHash(o, "outcomeResolutionSha256", "outcome_resolution_sha256")).put("outcome_resolution_sha256", requiredHash(o, "outcomeResolutionSha256", "outcome_resolution_sha256")).put("outcome_resolution_source_sha256", requiredHash(o, "outcomeResolutionSourceSha256", "outcome_resolution_source_sha256")).put("reservation_sha256", requiredHash(o, "reservationSha256", "reservation_sha256")).put("label_source_sha256", text(resolution.get("label_source_sha256"))).put("execution_source_sha256", text(resolution.get("execution_source_sha256"))));
        ArrayNode events = JSON.arrayNode().add(signal).add(outcome); ObjectNode appendOptions = JSON.objectNode().put("path", ledgerPath.toString()).put("expected_head_sha256", expected).put("nowAt", cycleNow); appendOptions.set("events", events); ArrayNode appended = appendProspectiveEventsAtomically(appendOptions); ObjectNode result = JSON.objectNode(); result.set("signal", appended.get(0)); result.set("outcome", appended.get(1)); result.put("activated", false); return result;
    }
    public static ObjectNode appendCompletedBarCycle(JsonNode options) { return appendCompletedBarCycle((ObjectNode) options); }

    public static ObjectNode createReplayRegistry(ObjectNode options) {
        ObjectNode o = options == null ? JSON.objectNode() : options; String lineage = requireHash(first(o, "lineageSha256", "lineage_sha256"), "lineage_sha256"); String rawPath = text(first(o, "path", "replayPath", "replay_path")); if (rawPath.isEmpty()) throw new IllegalArgumentException("replay path is required"); Path path = Path.of(rawPath); secureParents(path); realDirOrCreate(path); secureParents(path.resolve("entries")); realDirOrCreate(path.resolve("entries")); ObjectNode index = registryIndex(lineage); writeExclusive(path.resolve("HEAD.json"), pretty(index)); ObjectNode out = index.deepCopy(); out.put("path", path.toAbsolutePath().normalize().toString()); return out;
    }
    public static ObjectNode createReplayRegistry(Path path, String lineageSha256) { return createReplayRegistry(JSON.objectNode().put("path", path.toString()).put("lineage_sha256", lineageSha256)); }
    public static ObjectNode createReplayRegistry(String path, String lineageSha256) { return createReplayRegistry(Path.of(path), lineageSha256); }

    public static ObjectNode readReplayRegistry(Path path) { return readReplayRegistry(path, JSON.objectNode()); }
    public static ObjectNode readReplayRegistry(String path) { return readReplayRegistry(Path.of(path)); }
    public static ObjectNode readReplayRegistry(Path path, ObjectNode options) { ObjectNode index = readRegistryIndex(path); int requested = options != null && options.has("atSequence") ? options.path("atSequence").asInt() : index.path("sequence").asInt(); ObjectNode loaded = loadRegistry(path, index, requested); ObjectNode out = JSON.objectNode().put("schema", "strategy-prospective-replay-registry/1").put("version", 1).put("lineage_sha256", text(index.get("lineage_sha256"))).put("sequence", loaded.path("sequence").asInt()).put("head_sha256", text(loaded.get("head_sha256"))).put("current_head_sha256", text(index.get("head_sha256"))); out.set("entries", loaded.get("entries")); out.set("entry_refs", refsAt(index.get("entry_refs"), requested)); out.put("index_path", path.resolve("HEAD.json").toAbsolutePath().normalize().toString()); return withHashValidated(out);
    }
    public static ObjectNode readReplayRegistry(String path, ObjectNode options) { return readReplayRegistry(Path.of(path), options); }

    public static ObjectNode reserveReplayNonce(ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options; String nonce = text(o.get("nonce")); String payload = requiredHash(o, "publicationPayloadSha256", "publication_payload_sha256"); if (nonce.isEmpty() || !isHash(payload)) throw new IllegalArgumentException("replay USE requires payload hash"); return appendReplayEntry(Path.of(text(o.get("path"))), JSON.objectNode().put("nonce", nonce).put("action", "USE").put("publication_payload_sha256", payload).put("used_at", iso(nowAt(o.get("nowAt")))), requiredHash(o, "expectedHeadSha256", "expected_head_sha256")); }
    public static ObjectNode reserveReplayNonce(JsonNode options) { return reserveReplayNonce((ObjectNode) options); }

    public static ObjectNode revokeProspectiveNonce(ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options; String nonce = text(o.get("nonce")), reason = text(o.get("reason")); if (nonce.isEmpty() || reason.isEmpty() || !isHash(o.get("expectedHeadSha256")) || !o.hasNonNull("trustRoot") || !o.path("revocationApproval").hasNonNull("key_id")) throw new IllegalArgumentException("signed revocation requires nonce, reason, expected head, trust root, and revocation approval"); Path path = Path.of(text(o.get("path"))); ObjectNode current = readReplayRegistry(path); String expected = requiredHash(o, "expectedHeadSha256", "expected_head_sha256"); if (!text(current.get("current_head_sha256")).equals(expected)) throw new IllegalArgumentException("replay registry CAS head mismatch"); for (JsonNode row : rows(current.get("entries"))) if (nonce.equals(text(row.get("nonce"))) && "REVOKE".equals(text(row.get("action")))) return current; ObjectNode root = object(o.get("trustRoot"), "trustRoot"), approval = object(o.get("revocationApproval"), "revocationApproval"); ObjectNode key = delegatedKey(root, "revocation", text(approval.get("key_id")), nowAt(o.get("nowAt")), text(o.get("pinnedTrustRootFingerprint")), o.get("previousTrustRoot"), text(o.get("pinnedTrustRootGenesisFingerprint"))); ObjectNode payload = JSON.objectNode().put("nonce", nonce).put("action", "REVOKE").put("reason", reason).put("revoked_at", iso(nowAt(o.get("nowAt")))).put("trust_root_sha256", text(root.get("content_sha256"))).put("trust_root_generation", root.path("generation").asInt()); String signature = approval.hasNonNull("privateKeyPem") ? signPayload(payload, text(approval.get("privateKeyPem"))) : text(approval.get("signature")); if (!verifyPayload(payload, signature, text(key.get("public_key_pem")))) throw new IllegalArgumentException("revocation signature invalid"); appendReplayEntry(path, payload.put("key_id", text(key.get("key_id"))).put("signature", signature), expected); return readReplayRegistry(path); }
    public static ObjectNode revokeProspectiveNonce(JsonNode options) { return revokeProspectiveNonce((ObjectNode) options); }

    // ---------------------------------------------------------------------
    // Trust roots and signed evidence publication
    // ---------------------------------------------------------------------

    public static ObjectNode makeTrustRootBundle(ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options; String id = text(o.get("rootKeyId")), pub = text(o.get("rootPublicKeyPem")), priv = text(o.get("rootPrivateKeyPem")); if (id.isEmpty() || pub.isEmpty() || priv.isEmpty()) throw new IllegalArgumentException("root identity and offline signing key are required"); ArrayNode rows = JSON.arrayNode(); for (JsonNode raw : rows(o.get("delegations"))) { ObjectNode payload = delegationPayload(object(raw, "delegation")); String signature = raw.hasNonNull("signature") ? text(raw.get("signature")) : signPayload(payload, priv); ObjectNode row = payload.deepCopy().put("public_key_pem", text(raw.get("public_key_pem"))).put("signature", signature); rows.add(row); } requireDistinctDelegations(rows, "root must contain distinct asset, portfolio, and revocation delegations"); ObjectNode root = JSON.objectNode().put("schema", "strategy-prospective-trust-root/1").put("version", 1).put("root_key_id", id).put("root_public_key_pem", pub).put("pinned_fingerprint", hash(pub)).put("generation", o.path("generation").asInt(1)).put("genesis_pinned_fingerprint", hash(pub)); root.set("delegations", rows); root.putArray("revoked_key_ids"); return finalizeRoot(root, priv); }
    public static ObjectNode makeTrustRootBundle(JsonNode options) { return makeTrustRootBundle((ObjectNode) options); }
    public static ObjectNode rotateTrustRoot(ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options; ObjectNode previous = object(o.get("previousRoot"), "previous root"); String prevPriv = text(o.get("previousRootPrivateKeyPem")); if (prevPriv.isEmpty()) throw new IllegalArgumentException("previous root and offline rotation key are required"); int generation = o.hasNonNull("generation") ? o.path("generation").asInt() : previous.path("generation").asInt() + 1; if (generation <= previous.path("generation").asInt()) throw new IllegalArgumentException("trust-root generation must increase"); ArrayNode rows = JSON.arrayNode(); for (JsonNode raw : rows(o.get("delegations"))) { ObjectNode payload = delegationPayload(object(raw, "delegation")); ObjectNode row = payload.deepCopy().put("public_key_pem", text(raw.get("public_key_pem"))).put("signature", signPayload(payload, text(o.get("rootPrivateKeyPem")))); rows.add(row); } if (rows.size() < 3 || rows(rows).stream().noneMatch(r -> "revocation".equals(text(r.get("role"))))) throw new IllegalArgumentException("rotated root must preserve revocation delegation"); ObjectNode root = JSON.objectNode().put("schema", "strategy-prospective-trust-root/1").put("version", 1).put("root_key_id", text(o.get("rootKeyId"))).put("root_public_key_pem", text(o.get("rootPublicKeyPem"))).put("pinned_fingerprint", hash(text(o.get("rootPublicKeyPem")))).put("generation", generation).put("genesis_pinned_fingerprint", text(previous.hasNonNull("genesis_pinned_fingerprint") ? previous.get("genesis_pinned_fingerprint") : previous.get("pinned_fingerprint"))).put("previous_root_pinned_fingerprint", text(previous.get("pinned_fingerprint"))).put("previous_root_sha256", text(previous.get("content_sha256"))).put("previous_root_key_id", text(previous.get("root_key_id"))); root.set("delegations", rows); root.putArray("revoked_key_ids"); ObjectNode rotation = JSON.objectNode().put("schema", "strategy-trust-root-rotation/1").put("previous_root_sha256", text(previous.get("content_sha256"))).put("previous_root_key_id", text(previous.get("root_key_id"))).put("new_root_key_id", text(root.get("root_key_id"))).put("generation", generation); root.put("rotation_signature", signPayload(rotation, prevPriv)); return finalizeRoot(root, text(o.get("rootPrivateKeyPem"))); }
    public static ObjectNode rotateTrustRoot(JsonNode options) { return rotateTrustRoot((ObjectNode) options); }
    public static boolean verifyTrustRoot(ObjectNode root, ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options; JsonNode previous = o.get("previousRoot"); verifyTrustRootInternal(root, nowAt(o.get("nowAt")), text(o.get("pinnedFingerprint")), text(o.get("pinnedGenesisFingerprint")), previous == null || previous.isNull() ? null : object(previous, "previousRoot")); return true; }
    public static boolean verifyTrustRoot(JsonNode root, JsonNode options) { return verifyTrustRoot((ObjectNode) root, (ObjectNode) options); }

    public static ObjectNode publishProspectiveEvidence(ObjectNode o) {
        ObjectNode ledger = readProspectiveLedger(Path.of(text(o.get("ledgerPath")))); String lineage = requiredHash(o, "lineageSha256", "lineage_sha256"); if (ledger.path("sequence").asInt() < 1 || !lineage.equals(text(ledger.get("lineage_sha256")))) throw new IllegalArgumentException("publication ledger lineage/sequence invalid"); long now = nowAt(o.get("nowAt")), lease = time(o.get("leaseExpiresAt")); if (!(lease > now) || lease - now > MAX_PROSPECTIVE_LEASE_MS) throw new IllegalArgumentException("prospective lease invalid"); List<Evidence> evidence = digestEvidence(o.get("evidence")); ObjectNode assetApproval = object(o.get("assetApproval"), "asset approval"), portfolioApproval = object(o.get("portfolioApproval"), "portfolio approval"); String assetDecisionSha = requiredHash(assetApproval, "decision_sha256", "decision_sha256"), portfolioDecisionSha = requiredHash(portfolioApproval, "decision_sha256", "decision_sha256"); ObjectNode assetDecision = decisionArtifact(Path.of(text(assetApproval.get("decision_path"))), assetDecisionSha, "asset", lineage), portfolioDecision = decisionArtifact(Path.of(text(portfolioApproval.get("decision_path"))), portfolioDecisionSha, "portfolio", lineage); Set<String> assets = new LinkedHashSet<>(); for (JsonNode event : rows(ledger.get("events"))) assets.add(text(event.get("asset")).toLowerCase(Locale.ROOT)); if (assets.size() != 1 || !ASSETS.contains(assets.iterator().next()) || !text(assetDecision.get("asset")).equalsIgnoreCase(assets.iterator().next())) throw new IllegalArgumentException("publication must contain one crypto asset and a matching asset decision"); List<String> required = requiredEvidence(ledger.get("events")); if (required.stream().anyMatch(h -> evidence.stream().noneMatch(e -> h.equals(e.sha256)))) throw new IllegalArgumentException("publication evidence inventory is incomplete for ledger source/decision dependencies"); if (assetDecisionSha.equals(portfolioDecisionSha) || text(assetDecision.get("content_sha256")).equals(text(portfolioDecision.get("content_sha256")))) throw new IllegalArgumentException("asset and portfolio decisions must be distinct physical artifacts"); requireDecisionEvidence(evidence, assetApproval, assetDecision, portfolioApproval, portfolioDecision);
        ObjectNode replay = readReplayRegistry(Path.of(text(o.get("replayPath")))); String expectedReplay = requiredHash(o, "expectedReplayHeadSha256", "expected_replay_head_sha256"); if (!text(replay.get("current_head_sha256")).equals(expectedReplay)) throw new IllegalArgumentException("replay CAS head mismatch"); String nonce = text(o.get("replayNonce")); ObjectNode payload = JSON.objectNode().put("schema", "strategy-prospective-signed-evidence/2").put("version", 2).put("lineage_sha256", lineage).put("sequence", ledger.path("sequence").asInt()).put("previous_head_sha256", ledger.path("events").isEmpty() ? text(ledger.get("head_sha256")) : text(ledger.path("events").get(ledger.path("events").size() - 1).get("previous_head_sha256"))).put("new_head_sha256", text(ledger.get("head_sha256"))).put("replay_sequence", replay.path("sequence").asInt() + 1).put("replay_previous_head_sha256", text(replay.get("current_head_sha256"))).put("trust_root_sha256", text(o.path("trustRoot").get("content_sha256"))).put("trust_root_generation", o.path("trustRoot").path("generation").asInt()).put("trust_root_fingerprint", text(o.path("trustRoot").get("pinned_fingerprint"))).put("replay_nonce", nonce).put("lease_expires_at", iso(lease)); ArrayNode digestRows = JSON.arrayNode(); evidence.forEach(e -> digestRows.add(JSON.objectNode().put("id", e.id).put("sha256", e.sha256))); payload.set("evidence", digestRows); payload.put("evidence_digest_sha256", hash(digestRows)); payload.set("required_evidence_sha256", strings(required)); payload.put("asset_decision_sha256", assetDecisionSha).put("portfolio_decision_sha256", portfolioDecisionSha).put("asset_decision_content_sha256", text(assetDecision.get("content_sha256"))).put("portfolio_decision_content_sha256", text(portfolioDecision.get("content_sha256"))).put("asset_decision_evidence_id", text(assetApproval.hasNonNull("decision_evidence_id") ? assetApproval.get("decision_evidence_id") : JSON.textNode("asset-decision"))).put("portfolio_decision_evidence_id", text(portfolioApproval.hasNonNull("decision_evidence_id") ? portfolioApproval.get("decision_evidence_id") : JSON.textNode("portfolio-decision")));
        ObjectNode root = object(o.get("trustRoot"), "trustRoot"); ObjectNode assetKey = delegatedKey(root, "asset", text(assetApproval.get("key_id")), now, text(o.get("pinnedTrustRootFingerprint")), o.get("previousTrustRoot"), text(o.get("pinnedTrustRootGenesisFingerprint"))); ObjectNode portfolioKey = delegatedKey(root, "portfolio", text(portfolioApproval.get("key_id")), now, text(o.get("pinnedTrustRootFingerprint")), o.get("previousTrustRoot"), text(o.get("pinnedTrustRootGenesisFingerprint"))); if (text(assetKey.get("key_id")).equals(text(portfolioKey.get("key_id")))) throw new IllegalArgumentException("asset/portfolio approval keys must be distinct"); ObjectNode preview = JSON.objectNode().put("nonce", nonce).put("action", "USE").put("publication_payload_sha256", hash(payload)).put("used_at", iso(now)).put("sequence", replay.path("sequence").asInt() + 1).put("previous_head_sha256", text(replay.get("current_head_sha256"))); preview.put("entry_sha256", ownHash(preview, "entry_sha256")); ObjectNode finalPayload = payload.deepCopy().put("replay_new_head_sha256", text(preview.get("entry_sha256"))).put("replay_entry_sha256", text(preview.get("entry_sha256"))); String assetSig = assetApproval.hasNonNull("privateKeyPem") ? signPayload(finalPayload, text(assetApproval.get("privateKeyPem"))) : text(assetApproval.get("signature")); String portfolioSig = portfolioApproval.hasNonNull("privateKeyPem") ? signPayload(finalPayload, text(portfolioApproval.get("privateKeyPem"))) : text(portfolioApproval.get("signature")); if (!verifyPayload(finalPayload, assetSig, text(assetKey.get("public_key_pem"))) || !verifyPayload(finalPayload, portfolioSig, text(portfolioKey.get("public_key_pem")))) throw new IllegalArgumentException("approval signatures do not cover complete evidence payload"); ObjectNode publication = finalPayload.deepCopy(); publication.set("asset_approval", JSON.objectNode().put("role", "asset").put("key_id", text(assetKey.get("key_id"))).put("decision_sha256", assetDecisionSha).put("decision_content_sha256", text(assetDecision.get("content_sha256"))).put("signature", assetSig)); publication.set("portfolio_approval", JSON.objectNode().put("role", "portfolio").put("key_id", text(portfolioKey.get("key_id"))).put("decision_sha256", portfolioDecisionSha).put("decision_content_sha256", text(portfolioDecision.get("content_sha256"))).put("signature", portfolioSig)); publication = withHash(publication); validateSchema(publication); ObjectNode entry = reserveReplayNonce(JSON.objectNode().put("path", text(o.get("replayPath"))).put("nonce", nonce).put("expected_head_sha256", text(replay.get("current_head_sha256"))).put("nowAt", now).put("publication_payload_sha256", hash(payload))); if (!text(entry.get("entry_sha256")).equals(text(preview.get("entry_sha256")))) throw new IllegalArgumentException("replay reservation preview changed before commit"); return publication;
    }
    public static ObjectNode publishProspectiveEvidence(JsonNode options) { return publishProspectiveEvidence((ObjectNode) options); }

    public static ObjectNode verifyProspectivePublication(ObjectNode publication, ObjectNode options) { ObjectNode o = options == null ? JSON.objectNode() : options; ObjectNode replay = readReplayRegistry(Path.of(text(o.get("replayPath")))); for (JsonNode row : rows(replay.get("entries"))) if ("REVOKE".equals(text(row.get("action"))) && text(row.get("nonce")).equals(text(publication.get("replay_nonce")))) throw new IllegalArgumentException("publication replay or revocation check failed"); return verifyPublicationCore(publication, o); }
    public static ObjectNode verifyProspectivePublication(JsonNode publication, JsonNode options) { return verifyProspectivePublication((ObjectNode) publication, (ObjectNode) options); }

    // ---------------------------------------------------------------------
    // Internal implementation
    // ---------------------------------------------------------------------

    private static ObjectNode verifyPublicationCore(ObjectNode p, ObjectNode o) {
        if (!"strategy-prospective-signed-evidence/2".equals(text(p.get("schema"))) || !ownHash(p).equals(text(p.get("content_sha256")))) throw new IllegalArgumentException("publication hash/schema invalid"); ObjectNode ledger = readProspectiveLedger(Path.of(text(o.get("ledgerPath"))), JSON.objectNode().put("atSequence", p.path("sequence").asInt())); ObjectNode replay = readReplayRegistry(Path.of(text(o.get("replayPath"))), JSON.objectNode().put("atSequence", p.path("replay_sequence").asInt())); if (!text(p.get("new_head_sha256")).equals(text(ledger.get("head_sha256"))) || !text(p.get("previous_head_sha256")).equals(ledger.path("events").isEmpty() ? text(ledger.get("head_sha256")) : text(ledger.path("events").get(ledger.path("events").size() - 1).get("previous_head_sha256")))) throw new IllegalArgumentException("historical ledger head mismatch"); if (!text(p.get("replay_new_head_sha256")).equals(text(replay.get("head_sha256"))) || !text(p.get("replay_previous_head_sha256")).equals(replay.path("entries").isEmpty() ? text(replay.get("head_sha256")) : text(replay.path("entries").get(replay.path("entries").size() - 1).get("previous_head_sha256")))) throw new IllegalArgumentException("historical replay head mismatch"); long now = nowAt(o.get("nowAt")), lease = time(p.get("lease_expires_at")); if (lease <= now || lease - now > MAX_PROSPECTIVE_LEASE_MS) throw new IllegalArgumentException("publication lease invalid"); JsonNode used = null; for (JsonNode row : rows(replay.get("entries"))) if ("USE".equals(text(row.get("action"))) && text(row.get("nonce")).equals(text(p.get("replay_nonce"))) && text(row.get("entry_sha256")).equals(text(p.get("replay_entry_sha256")))) used = row; if (used == null || !isHash(used.get("publication_payload_sha256"))) throw new IllegalArgumentException("publication replay or revocation check failed"); List<Evidence> evidence = new ArrayList<>(); for (JsonNode row : rows(p.get("evidence"))) { String path = text(o.path("evidencePaths").get(text(row.get("id")))); if (path.isEmpty()) throw new IllegalArgumentException("evidence path missing for " + text(row.get("id"))); if (!hash(readBytes(Path.of(path))).equals(text(row.get("sha256")))) throw new IllegalArgumentException("evidence hash mismatch for " + text(row.get("id"))); evidence.add(new Evidence(text(row.get("id")), text(row.get("sha256")))); } if (new HashSet<>(evidence.stream().map(e -> e.id).toList()).size() != evidence.size() || new HashSet<>(evidence.stream().map(e -> e.sha256).toList()).size() != evidence.size()) throw new IllegalArgumentException("publication evidence digest or uniqueness is invalid"); ArrayNode digest = JSON.arrayNode(); evidence.forEach(e -> digest.add(JSON.objectNode().put("id", e.id).put("sha256", e.sha256))); if (!hash(digest).equals(text(p.get("evidence_digest_sha256")))) throw new IllegalArgumentException("publication evidence digest or uniqueness is invalid"); List<String> required = requiredEvidence(ledger.get("events")); if (required.stream().anyMatch(h -> evidence.stream().noneMatch(e -> h.equals(e.sha256))) || !required.equals(stringList(p.get("required_evidence_sha256")))) throw new IllegalArgumentException("publication evidence inventory is incomplete or substituted"); Map<String,String> paths = new HashMap<>(); o.path("evidencePaths").fields().forEachRemaining(e -> paths.put(e.getKey(), text(e.getValue()))); ObjectNode asset = decisionArtifact(Path.of(paths.get(text(p.get("asset_decision_evidence_id")))), text(p.get("asset_decision_sha256")), "asset", text(p.get("lineage_sha256"))); ObjectNode portfolio = decisionArtifact(Path.of(paths.get(text(p.get("portfolio_decision_evidence_id")))), text(p.get("portfolio_decision_sha256")), "portfolio", text(p.get("lineage_sha256"))); if (!text(asset.get("content_sha256")).equals(text(p.get("asset_decision_content_sha256"))) || !text(portfolio.get("content_sha256")).equals(text(p.get("portfolio_decision_content_sha256")))) throw new IllegalArgumentException("publication decision asset/portfolio lineage mismatch"); ObjectNode root = object(o.get("trustRoot"), "trustRoot"); if (!text(p.get("trust_root_sha256")).equals(text(root.get("content_sha256"))) || p.path("trust_root_generation").asInt() != root.path("generation").asInt() || !text(p.get("trust_root_fingerprint")).equals(text(root.get("pinned_fingerprint")))) throw new IllegalArgumentException("publication trust root mismatch"); ObjectNode assetKey = delegatedKey(root, "asset", text(p.path("asset_approval").get("key_id")), now, text(o.get("pinnedTrustRootFingerprint")), o.get("previousTrustRoot"), text(o.get("pinnedTrustRootGenesisFingerprint"))); ObjectNode portfolioKey = delegatedKey(root, "portfolio", text(p.path("portfolio_approval").get("key_id")), now, text(o.get("pinnedTrustRootFingerprint")), o.get("previousTrustRoot"), text(o.get("pinnedTrustRootGenesisFingerprint"))); ObjectNode payload = publicationPayload(p), replayPayload = replayPayload(p); if (text(assetKey.get("key_id")).equals(text(portfolioKey.get("key_id"))) || !text(used.get("publication_payload_sha256")).equals(hash(replayPayload)) || !verifyPayload(payload, text(p.path("asset_approval").get("signature")), text(assetKey.get("public_key_pem"))) || !verifyPayload(payload, text(p.path("portfolio_approval").get("signature")), text(portfolioKey.get("public_key_pem")))) throw new IllegalArgumentException("publication signature invalid"); return JSON.objectNode().put("verified", true).put("sequence", p.path("sequence").asInt()).put("lineage_sha256", text(p.get("lineage_sha256"))).put("evidence_digest_sha256", text(p.get("evidence_digest_sha256")));
    }

    private static ObjectNode readLedgerRaw(Path path, ObjectNode o) { ObjectNode index = readLedgerIndex(path); int requested = o.has("atSequence") ? o.path("atSequence").asInt() : index.path("sequence").asInt(); if (requested < 0 || requested > index.path("sequence").asInt()) throw new IllegalArgumentException("invalid requested ledger sequence"); Path snapshotBase = o.hasNonNull("snapshotRootBase") ? absolute(text(o.get("snapshotRootBase"))) : null; ArrayNode events = loadLedgerEvents(path, index, requested, snapshotBase); long now = nowAt(o.get("nowAt")); boolean allowFuture = o.path("allowFuture").asBoolean(false); for (JsonNode event : events) { if (!allowFuture && (time(event.get("availability_time")) > now || time(event.get("decision_time")) > now)) throw new IllegalArgumentException("future prospective evidence is not admissible"); if (time(event.get("availability_time")) > time(event.get("decision_time"))) throw new IllegalArgumentException("event availability is after decision time"); } ObjectNode out = JSON.objectNode().put("schema", "strategy-prospective-ledger/2").put("version", 2).put("lineage_sha256", text(index.get("lineage_sha256"))); out.set("assets", index.get("assets") == null ? JSON.arrayNode() : index.get("assets").deepCopy()); out.set("frozen_start", index.get("frozen_start") == null ? NullNode.instance : index.get("frozen_start")); out.set("frozen_end", index.get("frozen_end") == null ? NullNode.instance : index.get("frozen_end")); out.put("sequence", events.size()).put("head_sha256", events.isEmpty() ? ledgerGenesis(text(index.get("lineage_sha256"))) : text(events.get(events.size() - 1).get("event_sha256"))).put("current_head_sha256", text(index.get("head_sha256"))); out.set("events", events); out.put("index_path", path.resolve("HEAD.json").toAbsolutePath().normalize().toString()).put("index_content_sha256", text(index.get("content_sha256"))); return withHashValidated(out); }
    private static ArrayNode loadLedgerEvents(Path path, ObjectNode index, int at, Path snapshotBase) { List<SourceRefs> source = ledgerRefSources(path, index, snapshotBase, new HashSet<>()); List<RefAt> refs = new ArrayList<>(); for (SourceRefs s : source) for (JsonNode ref : rows(s.refs)) if (ref.path("sequence").asInt() <= at) refs.add(new RefAt(s.path, object(ref, "ref"))); String previous = ledgerGenesis(text(index.get("lineage_sha256"))); ArrayNode events = JSON.arrayNode(); for (RefAt item : refs) { ObjectNode event = physicalJson(item.path.resolve(text(item.ref.get("path"))), text(item.ref.get("byte_sha256")), null, "event_sha256"); if (item.ref.path("sequence").asInt() != event.path("sequence").asInt() || !text(item.ref.get("event_sha256")).equals(text(event.get("event_sha256"))) || !text(event.get("previous_head_sha256")).equals(previous) || !text(event.get("event_sha256")).equals(ownHash(event, "event_sha256"))) throw new IllegalArgumentException("immutable prospective event chain is invalid"); previous = text(event.get("event_sha256")); events.add(event); } if (refs.size() != at && at != 0) throw new IllegalArgumentException("requested ledger sequence is unavailable"); return events; }
    private static List<SourceRefs> ledgerRefSources(Path path, ObjectNode index, Path base, Set<Path> seen) { Path root = path.toAbsolutePath().normalize(); if (!seen.add(root)) throw new IllegalArgumentException("prospective ledger snapshot chain contains a cycle"); String prior = text(index.get("prior_snapshot_root")); if (prior.isEmpty() || "null".equals(prior)) return List.of(new SourceRefs(root, index.get("event_refs"))); if (!isHash(prior) || !isHash(index.get("prior_head_sha256"))) throw new IllegalArgumentException("prospective ledger snapshot predecessor binding is invalid"); Path snapshotBase = base == null ? root.getParent().getParent() : base; Path priorPath = snapshotBase.resolve(prior).resolve("ledger"); ObjectNode priorIndex = readLedgerIndex(priorPath); if (!text(priorIndex.get("lineage_sha256")).equals(text(index.get("lineage_sha256"))) || !text(priorIndex.get("head_sha256")).equals(text(index.get("prior_head_sha256"))) || priorIndex.path("sequence").asInt() >= index.path("sequence").asInt()) throw new IllegalArgumentException("prospective ledger snapshot predecessor is not the exact immutable prefix"); List<SourceRefs> out = new ArrayList<>(ledgerRefSources(priorPath, priorIndex, snapshotBase, seen)); out.add(new SourceRefs(root, index.get("event_refs"))); return out; }
    private static ObjectNode readLedgerIndex(Path path) { ObjectNode index = parseObject(readBytes(path.resolve("HEAD.json"))); if (!"strategy-prospective-ledger-index/1".equals(text(index.get("schema"))) || !ownHash(index).equals(text(index.get("content_sha256"))) || !isHash(index.get("lineage_sha256")) || !index.path("event_refs").isArray()) throw new IllegalArgumentException("prospective ledger HEAD is invalid"); validateSchema(index); return index; }
    private static ObjectNode readRegistryIndex(Path path) { ObjectNode index = parseObject(readBytes(path.resolve("HEAD.json"))); if (!"strategy-prospective-replay-index/1".equals(text(index.get("schema"))) || !ownHash(index).equals(text(index.get("content_sha256"))) || !isHash(index.get("lineage_sha256")) || !index.path("entry_refs").isArray()) throw new IllegalArgumentException("replay registry HEAD is invalid"); validateSchema(index); return index; }
    private static ObjectNode loadRegistry(Path path, ObjectNode index, int at) { if (at < 0 || at > index.path("sequence").asInt()) throw new IllegalArgumentException("invalid requested replay sequence"); String previous = registryGenesis(text(index.get("lineage_sha256"))); ArrayNode entries = JSON.arrayNode(); Map<String,Set<String>> actions = new HashMap<>(); int count = 0; for (JsonNode raw : rows(index.get("entry_refs"))) if (raw.path("sequence").asInt() <= at) { count++; ObjectNode ref = object(raw, "entry ref"), entry = physicalJson(path.resolve(text(ref.get("path"))), text(ref.get("byte_sha256")), null, "entry_sha256"); Set<String> prior = actions.computeIfAbsent(text(entry.get("nonce")), k -> new HashSet<>()); if (entry.path("sequence").asInt() != ref.path("sequence").asInt() || !text(entry.get("entry_sha256")).equals(text(ref.get("entry_sha256"))) || !text(entry.get("previous_head_sha256")).equals(previous) || !text(entry.get("entry_sha256")).equals(ownHash(entry, "entry_sha256")) || !prior.add(text(entry.get("action"))) || !Set.of("USE", "REVOKE").contains(text(entry.get("action"))) || ("USE".equals(text(entry.get("action"))) && !isHash(entry.get("publication_payload_sha256"))) || ("REVOKE".equals(text(entry.get("action"))) && (!entry.hasNonNull("key_id") || !entry.hasNonNull("signature") || !isHash(entry.get("trust_root_sha256")) || !entry.path("trust_root_generation").canConvertToInt()))) throw new IllegalArgumentException("immutable replay entry chain is invalid"); previous = text(entry.get("entry_sha256")); entries.add(entry); } if (count != at && at != 0) throw new IllegalArgumentException("requested replay sequence is unavailable"); return JSON.objectNode().put("sequence", count).put("head_sha256", count == 0 ? registryGenesis(text(index.get("lineage_sha256"))) : text(entries.get(count - 1).get("entry_sha256"))).set("entries", entries); }
    private static ObjectNode appendReplayEntry(Path path, ObjectNode raw, String expected) { return withLock(path, () -> { ObjectNode registry = readReplayRegistry(path), entry = raw.deepCopy(); if (!isHash(expected) || !expected.equals(text(registry.get("current_head_sha256")))) throw new IllegalArgumentException("replay registry CAS head mismatch"); List<JsonNode> same = rows(registry.get("entries")).stream().filter(r -> text(r.get("nonce")).equals(text(entry.get("nonce")))).toList(); if (same.stream().anyMatch(r -> text(r.get("action")).equals(text(entry.get("action")))) || ("USE".equals(text(entry.get("action"))) && !same.isEmpty())) throw new IllegalArgumentException("replay nonce already used or revoked"); entry.put("sequence", registry.path("sequence").asInt() + 1).put("previous_head_sha256", text(registry.get("current_head_sha256"))).put("entry_sha256", ownHash(entry, "entry_sha256")); String rel = String.format(Locale.ROOT, "entries/%012d-%s.json", entry.path("sequence").asInt(), text(entry.get("entry_sha256"))); Path absolute = path.resolve(rel); secureParents(absolute.getParent()); writeExclusive(absolute, pretty(entry)); ObjectNode index = readRegistryIndex(path), updated = index.deepCopy(); updated.put("sequence", entry.path("sequence").asInt()).put("head_sha256", text(entry.get("entry_sha256"))).set("entry_refs", concat(index.get("entry_refs"), JSON.arrayNode().add(JSON.objectNode().put("sequence", entry.path("sequence").asInt()).put("entry_sha256", text(entry.get("entry_sha256"))).put("byte_sha256", hash(readBytes(absolute))).put("path", rel)))); atomic(path.resolve("HEAD.json"), withHash(updated)); return entry; }); }

    private static ObjectNode prepareEvent(ObjectNode ledger, ObjectNode event, long now) { String id = text(event.get("event_id")); if (id.isEmpty()) throw new IllegalArgumentException("event id is required"); ObjectNode receipt = validateSourceReceipt(Path.of(text(event.get("source_receipt_path"))), text(event.get("source_receipt_sha256")), text(event.get("asset")).toLowerCase(Locale.ROOT), text(event.get("completed_bar_id")), text(ledger.get("lineage_sha256")), now); if (rows(ledger.get("events")).stream().anyMatch(r -> id.equals(text(r.get("event_id"))))) throw new IllegalArgumentException("duplicate event id"); String identity = text(event.get("asset")) + "|" + text(event.get("completed_bar_id")) + "|" + text(event.get("kind")); if (rows(ledger.get("events")).stream().anyMatch(r -> (text(r.get("asset")) + "|" + text(r.get("completed_bar_id")) + "|" + text(r.get("kind"))).equals(identity))) throw new IllegalArgumentException("duplicate completed-bar identity"); if ("OUTCOME".equals(text(event.get("kind")))) { JsonNode signal = rows(ledger.get("events")).stream().filter(r -> text(r.get("asset")).equals(text(event.get("asset")).toLowerCase(Locale.ROOT)) && text(r.get("completed_bar_id")).equals(text(event.get("completed_bar_id"))) && "SIGNAL".equals(text(r.get("kind")))).findFirst().orElse(null); if (signal == null || text(signal.get("source_receipt_sha256")).equals(text(event.get("source_receipt_sha256"))) || time(event.get("decision_time")) <= time(signal.get("decision_time"))) throw new IllegalArgumentException("outcome requires a later separate resolution receipt"); }
        validateEventInput(ledger, event, now); Set<String> allowed = "SIGNAL".equals(text(event.get("kind"))) ? Set.of("signal_state", "signal_intent", "signal_decision_sha256", "reservation_sha256", "candidate_set_sha256", "evaluator_code_sha256", "feature_input_sha256", "feature_row_sha256", "availability_cutoff_time") : Set.of("resolution", "resolution_sha256", "outcome_resolution_sha256", "outcome_resolution_source_sha256", "reservation_sha256", "label_source_sha256", "execution_source_sha256"); for (String key : fieldNames(event.get("payload"))) if (!allowed.contains(key)) throw new IllegalArgumentException("event payload schema contains an unsupported field"); ObjectNode out = JSON.objectNode().put("event_id", id).put("kind", text(event.get("kind"))).put("asset", text(event.get("asset")).toLowerCase(Locale.ROOT)).put("completed_bar_id", text(event.get("completed_bar_id"))).put("decision_time", iso(time(event.get("decision_time")))).put("availability_time", iso(time(event.get("availability_time")))).put("source_receipt_sha256", text(event.get("source_receipt_sha256"))).put("source_receipt_schema", text(receipt.get("schema"))).put("source_receipt_ref", text(event.hasNonNull("source_receipt_ref") ? event.get("source_receipt_ref") : JSON.textNode(Path.of(text(event.get("source_receipt_path"))).getFileName().toString()))).put("lineage_sha256", text(ledger.get("lineage_sha256"))); out.set("payload", event.get("payload") == null ? JSON.objectNode() : event.get("payload").deepCopy()); return out; }
    private static void validateEventInput(ObjectNode ledger, ObjectNode event, long now) { String kind = text(event.get("kind")), asset = text(event.get("asset")).toLowerCase(Locale.ROOT); if (!EVENTS.contains(kind)) throw new IllegalArgumentException("event kind is not allowed"); if (!rows(ledger.get("assets")).stream().map(StrategyProspectiveV5::text).anyMatch(asset::equals) || !ASSETS.contains(asset)) throw new IllegalArgumentException("event asset is not in frozen crypto universe"); if (!event.hasNonNull("completed_bar_id")) throw new IllegalArgumentException("completed_bar_id is required"); if (event.hasNonNull("lineage_sha256") && !text(event.get("lineage_sha256")).equals(text(ledger.get("lineage_sha256")))) throw new IllegalArgumentException("event lineage mismatch"); ObjectNode payload = event.hasNonNull("payload") && event.get("payload").isObject() ? (ObjectNode) event.get("payload") : JSON.objectNode(); if ("OUTCOME".equals(kind) && (!payload.hasNonNull("resolution") && !payload.has("outcome") || fieldNames(payload).stream().anyMatch(Set.of("signal_state", "active", "pnl", "net_r", "metrics")::contains))) throw new IllegalArgumentException("outcome event lacks a closed resolution payload"); if ("SIGNAL".equals(kind) && (!"SHADOW".equals(text(payload.get("signal_state"))) || fieldNames(payload).stream().anyMatch(Set.of("outcome", "resolution", "active", "pnl", "net_r", "metrics", "trade", "execution")::contains))) throw new IllegalArgumentException("signal event requires closed SHADOW signal payload"); long decision = time(event.get("decision_time")), available = time(event.get("availability_time")); if (decision > now || available > decision) throw new IllegalArgumentException("event is not completed and available"); if (ledger.hasNonNull("frozen_start") && decision < time(ledger.get("frozen_start"))) throw new IllegalArgumentException("event precedes prospective frozen window"); if (ledger.hasNonNull("frozen_end") && decision > time(ledger.get("frozen_end"))) throw new IllegalArgumentException("event exceeds prospective frozen window"); }

    private static void validateOutcomeArtifacts(ObjectNode o, ObjectNode bar, String lineage) {
        String asset = text(bar.get("asset")).toLowerCase(Locale.ROOT);
        String barId = text(bar.get("completed_bar_id"));
        long now = nowAt(o.get("nowAt"));
        String resolutionPath = path(o, "outcomeResolutionPath", "outcome_resolution_path");
        String resolutionSha = requiredHash(o, "outcomeResolutionSha256", "outcome_resolution_sha256");
        String receiptPath = path(o, "outcomeReceiptPath", "outcome_receipt_path");
        String receiptSha = requiredHash(o, "outcomeReceiptSha256", "outcome_receipt_sha256");
        String sourcePath = path(o, "outcomeResolutionSourcePath", "outcome_resolution_source_path");
        String sourceSha = requiredHash(o, "outcomeResolutionSourceSha256", "outcome_resolution_source_sha256");
        String labelPath = path(o, "labelSourcePath", "label_source_path");
        String labelSha = requiredHash(o, "labelSourceSha256", "label_source_sha256");
        String executionPath = path(o, "executionSourcePath", "execution_source_path");
        String executionSha = requiredHash(o, "executionSourceSha256", "execution_source_sha256");
        ObjectNode resolution = physicalJson(Path.of(resolutionPath), resolutionSha, Set.of("strategy-prospective-outcome-resolution/1"));
        validateSchema(resolution);
        if (!barId.equals(text(resolution.get("completed_bar_id"))) || text(resolution.get("resolution")).isEmpty()
                || time(resolution.get("resolution_time")) <= time(bar.get("availability_time"))
                || !lineage.equals(text(resolution.get("decision_lineage_sha256")))
                || !isValidHash(text(resolution.get("label_source_sha256")))
                || !isValidHash(text(resolution.get("execution_source_sha256")))
                || !isValidHash(text(resolution.get("source_byte_sha256")))) {
            throw new IllegalArgumentException("outcome resolution must bind later label/execution sources and decision lineage");
        }
        ObjectNode resolutionSource = physicalBytes(Path.of(sourcePath), sourceSha, "outcome resolution source");
        physicalBytes(Path.of(labelPath), labelSha, "label");
        physicalBytes(Path.of(executionPath), executionSha, "execution");
        if (!text(resolution.get("source_byte_sha256")).equals(text(resolutionSource.get("byte_sha256")))
                || text(resolutionSource.get("byte_sha256")).equals(labelSha)
                || text(resolutionSource.get("byte_sha256")).equals(executionSha)
                || !text(resolution.get("label_source_sha256")).equals(labelSha)
                || !text(resolution.get("execution_source_sha256")).equals(executionSha)) {
            throw new IllegalArgumentException("outcome source identity mismatch");
        }
        ObjectNode receipt = validateSourceReceipt(Path.of(receiptPath), receiptSha, asset, barId, lineage, now);
        if (time(receipt.get("availability_time")) <= time(resolution.get("resolution_time"))
                || time(receipt.get("availability_time")) <= time(bar.get("availability_time"))) {
            throw new IllegalArgumentException("outcome receipt must be available after resolution");
        }
    }

    private static ObjectNode validateSourceReceipt(Path path, String sha, String asset, String bar, String lineage, long now) { ObjectNode receipt = physicalJson(path, sha, Set.of("strategy-prospective-source-receipt/1")); validateSchema(receipt); if (!receipt.path("completed").asBoolean(false) || !receipt.hasNonNull("completed_bar_id") || !bar.equals(text(receipt.get("completed_bar_id")))) throw new IllegalArgumentException("source receipt completed-bar identity is invalid"); if (!asset.isEmpty() && !asset.equals(text(receipt.get("asset")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("source receipt asset mismatch"); if (time(receipt.get("bar_end")) > time(receipt.get("availability_time")) || time(receipt.get("bar_start")) >= time(receipt.get("bar_end"))) throw new IllegalArgumentException("source receipt bar interval is invalid"); if (!lineage.isEmpty() && !lineage.equals(text(receipt.get("lineage_sha256")))) throw new IllegalArgumentException("source receipt lineage mismatch"); if (time(receipt.get("availability_time")) > now) throw new IllegalArgumentException("source receipt is not available yet"); return receipt; }
    private static void recomputeSignalDecision(ObjectNode decision, ObjectNode bar, ObjectNode receipt, ObjectNode candidateSet, ObjectNode evaluator, String featureSha, String sourceSha, String reservationSha, String candidateSha, String evaluatorSha, String lineage) { JsonNode feature = bar.has("feature_row") ? bar.get("feature_row") : bar.get("features"); if (feature == null || !feature.isObject() || feature.isArray()) throw new IllegalArgumentException("signal decision recomputation requires a physical feature row on the completed bar"); if (!decision.hasNonNull("candidate_id") || !decision.has("signal_intent") || !decision.path("signal_intent").isBoolean() || !isHash(decision.get("feature_row_sha256"))) throw new IllegalArgumentException("signal decision must declare candidate_id, signal_intent, and feature-row hash for recomputation"); if (!hash(feature).equals(text(decision.get("feature_row_sha256"))) || !text(receipt.get("payload_sha256")).equals(text(decision.get("feature_row_sha256")))) throw new IllegalArgumentException("feature row is not byte-bound to the source receipt and signal decision"); if (feature.toString().matches("(?s).*")) for (String key : fieldNames(feature)) if (key.matches("(?i)(^|_)(label|outcome|pnl|net_r|execution|fill|trade)(_|$)")) throw new IllegalArgumentException("feature row contains forbidden outcome/execution fields"); JsonNode candidate = rows(candidateSet.get("candidates")).stream().filter(r -> text(r.get("candidate_id")).equals(text(decision.get("candidate_id")))).findFirst().orElse(null); if (candidate == null) throw new IllegalArgumentException("signal decision candidate is not in the frozen candidate set"); JsonNode predicate = evaluator.get("predicate"); JsonNode chromosome = candidate.has("definition") ? candidate.get("definition").path("chromosome") : NullNode.instance; if (chromosome.isMissingNode() || chromosome.isNull()) chromosome = candidate.has("definition") ? candidate.get("definition") : candidate.get("definition"); boolean intent = evaluatePredicate(predicate, feature, chromosome); ObjectNode expected = JSON.objectNode().put("schema", "strategy-prospective-signal-decision/1").put("version", 1).put("decision", "SHADOW").put("signal_state", "SHADOW").put("signal_intent", intent).put("candidate_id", text(decision.get("candidate_id"))).put("completed_bar_id", text(bar.get("completed_bar_id"))).put("source_receipt_sha256", sourceSha).put("reservation_sha256", reservationSha).put("candidate_set_sha256", candidateSha).put("evaluator_code_sha256", evaluatorSha).put("feature_input_sha256", featureSha).put("feature_row_sha256", text(decision.get("feature_row_sha256"))).put("availability_cutoff_time", iso(time(decision.get("availability_cutoff_time")))).put("decision_time", iso(time(decision.hasNonNull("decision_time") ? decision.get("decision_time") : bar.get("availability_time")))).put("lineage_sha256", lineage); expected.put("content_sha256", ownHash(expected)); if (!LegacyResearchNext.stable(decision).equals(LegacyResearchNext.stable(expected))) throw new IllegalArgumentException("signal decision does not byte-for-byte match frozen evaluator recomputation"); }

    private static boolean evaluatePredicate(JsonNode predicate, JsonNode feature, JsonNode chromosome) {
        return StrategyEvaluatorV5.evaluateSignalPredicateV5(predicate, feature, chromosome);
    }

    @SuppressWarnings("unused")
    private static boolean evaluatePredicateLegacy(JsonNode predicate, JsonNode feature, JsonNode chromosome) { // retained only as a local compatibility fallback
        if (predicate == null || predicate.isNull()) throw new IllegalArgumentException("signal evaluator predicate is missing"); if (predicate.isBoolean()) return predicate.asBoolean(); if (predicate.isObject()) { String op = text(predicate.get("op")).toUpperCase(Locale.ROOT); if ("FIELD_GT".equals(op) || "GT".equals(op)) return feature.path(text(predicate.get("field"))).asDouble(Double.NaN) > predicate.path("value").asDouble(Double.NaN); if ("FIELD_GTE".equals(op) || "GTE".equals(op)) return feature.path(text(predicate.get("field"))).asDouble(Double.NaN) >= predicate.path("value").asDouble(Double.NaN); if ("FIELD_LT".equals(op) || "LT".equals(op)) return feature.path(text(predicate.get("field"))).asDouble(Double.NaN) < predicate.path("value").asDouble(Double.NaN); if ("FIELD_LTE".equals(op) || "LTE".equals(op)) return feature.path(text(predicate.get("field"))).asDouble(Double.NaN) <= predicate.path("value").asDouble(Double.NaN); if ("AND".equals(op)) return rows(predicate.get("inputs")).stream().allMatch(p -> evaluatePredicate(p, feature, chromosome)); if ("OR".equals(op)) return rows(predicate.get("inputs")).stream().anyMatch(p -> evaluatePredicate(p, feature, chromosome)); if ("NOT".equals(op)) return !evaluatePredicate(predicate.get("input"), feature, chromosome); if ("CHROMOSOME".equals(op)) return evaluatePredicate(predicate.get("predicate"), feature, chromosome); } throw new IllegalArgumentException("unsupported evaluator predicate"); }

    // Publication/trust helpers.
    private static ObjectNode decisionArtifact(Path path, String sha, String role, String lineage) { ObjectNode value = physicalJson(path, sha, Set.of("strategy-prospective-decision/1")); validateSchema(value); if (!role.equals(text(value.get("role"))) || !"PASS".equals(text(value.get("decision"))) || !lineage.equals(text(value.get("lineage_sha256"))) || !value.path("evidence_sha256").isArray() || value.path("evidence_sha256").isEmpty() || rows(value.get("evidence_sha256")).stream().anyMatch(r -> !isHash(r)) || !isHash(value.get("workflow_attestation_sha256"))) throw new IllegalArgumentException(role + " decision artifact must bind exact PASS evidence and workflow attestation"); if ("portfolio".equals(role) && !"portfolio".equals(text(value.get("asset")))) throw new IllegalArgumentException("portfolio decision must identify portfolio"); if ("asset".equals(role) && !ASSETS.contains(text(value.get("asset")).toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("asset decision must identify one supported crypto asset"); return value; }
    private static void requireDecisionEvidence(List<Evidence> inventory, ObjectNode a, ObjectNode ad, ObjectNode p, ObjectNode pd) { Set<String> hashes = inventory.stream().map(e -> e.sha256).collect(java.util.stream.Collectors.toSet()); if (!hashes.contains(text(a.get("decision_sha256"))) || !hashes.contains(text(p.get("decision_sha256"))) || rows(ad.get("evidence_sha256")).stream().anyMatch(h -> !hashes.contains(text(h))) || rows(pd.get("evidence_sha256")).stream().anyMatch(h -> !hashes.contains(text(h))) || !hashes.contains(text(ad.get("workflow_attestation_sha256"))) || !hashes.contains(text(pd.get("workflow_attestation_sha256")))) throw new IllegalArgumentException("decision dependencies must be physical evidence digests"); }
    private static List<Evidence> digestEvidence(JsonNode value) { if (value == null || !value.isArray() || value.isEmpty()) throw new IllegalArgumentException("evidence digest cannot be empty"); List<Evidence> out = new ArrayList<>(); for (JsonNode row : value) { String id = text(row.get("id")), sha = text(row.get("sha256")), path = text(row.get("path")); if (id.isEmpty() || path.isEmpty() || !isValidHash(sha)) throw new IllegalArgumentException("evidence requires id/path/sha256"); if (!sha.equals(hash(readBytes(Path.of(path))))) throw new IllegalArgumentException("evidence hash mismatch for " + id); out.add(new Evidence(id, sha)); } out.sort(Comparator.comparing(e -> e.id)); if (new HashSet<>(out.stream().map(e -> e.id).toList()).size() != out.size()) throw new IllegalArgumentException("duplicate publication evidence id"); if (new HashSet<>(out.stream().map(e -> e.sha256).toList()).size() != out.size()) throw new IllegalArgumentException("duplicate publication evidence hash is ambiguous"); return out; }
    private static List<String> requiredEvidence(JsonNode events) { Set<String> out = new LinkedHashSet<>(); List<String> source = List.of("source_receipt_sha256"), signal = List.of("signal_decision_sha256", "reservation_sha256", "candidate_set_sha256", "evaluator_code_sha256", "feature_input_sha256"), outcome = List.of("resolution_sha256", "outcome_resolution_sha256", "outcome_resolution_source_sha256", "reservation_sha256", "label_source_sha256", "execution_source_sha256"); for (JsonNode event : rows(events)) { for (String key : source) if (isValidHash(text(event.get(key)))) out.add(text(event.get(key))); for (String key : "SIGNAL".equals(text(event.get("kind"))) ? signal : outcome) if (isValidHash(text(event.path("payload").get(key)))) out.add(text(event.path("payload").get(key))); } List<String> result = new ArrayList<>(out); result.sort(String::compareTo); return result; }
    private static ObjectNode publicationPayload(ObjectNode value) { ObjectNode out = value.deepCopy(); out.remove(List.of("asset_approval", "portfolio_approval", "content_sha256")); return out; }
    private static ObjectNode replayPayload(ObjectNode value) { ObjectNode out = publicationPayload(value); out.remove(List.of("replay_new_head_sha256", "replay_entry_sha256")); return out; }
    private static ObjectNode rootPayload(ObjectNode root) { ObjectNode out = root.deepCopy(); out.remove(List.of("root_signature", "content_sha256")); return out; }
    private static ObjectNode delegationPayload(ObjectNode row) { ObjectNode out = JSON.objectNode().put("role", text(row.get("role"))).put("key_id", text(row.get("key_id"))).put("public_key_sha256", hash(text(row.get("public_key_pem")))); out.set("valid_from", row.get("valid_from") == null ? NullNode.instance : row.get("valid_from")); out.set("valid_until", row.get("valid_until") == null ? NullNode.instance : row.get("valid_until")); return out; }
    private static ObjectNode finalizeRoot(ObjectNode root, String privatePem) { root.put("root_signature", signPayload(rootPayload(root), privatePem)); ObjectNode out = withHash(root); validateSchema(out); return out; }
    private static void requireDistinctDelegations(ArrayNode rows, String message) { if (rows.size() < 3 || rows(rows).stream().map(r -> text(r.get("role"))).distinct().count() != rows.size() || rows(rows).stream().noneMatch(r -> "asset".equals(text(r.get("role")))) || rows(rows).stream().noneMatch(r -> "portfolio".equals(text(r.get("role")))) || rows(rows).stream().noneMatch(r -> "revocation".equals(text(r.get("role"))))) throw new IllegalArgumentException(message); }
    private static ObjectNode delegatedKey(ObjectNode root, String role, String keyId, long now, String pinned, JsonNode previous, String genesis) { ObjectNode previousRoot = previous == null || previous.isNull() ? null : object(previous, "previousRoot"); verifyTrustRootInternal(root, now, pinned, genesis, previousRoot); for (JsonNode row : rows(root.get("delegations"))) if (role.equals(text(row.get("role"))) && keyId.equals(text(row.get("key_id")))) return object(row, "delegation"); throw new IllegalArgumentException("no trusted " + role + " delegation"); }
    private static void verifyTrustRootInternal(ObjectNode root, long now, String pinned, String genesis, ObjectNode previous) { if (root == null || !"strategy-prospective-trust-root/1".equals(text(root.get("schema"))) || !ownHash(root).equals(text(root.get("content_sha256"))) || !isValidHash(text(root.get("pinned_fingerprint"))) || !text(root.get("pinned_fingerprint")).equals(hash(text(root.get("root_public_key_pem")))) || !isValidHash(text(root.get("root_signature"))) || (!text(root.get("genesis_pinned_fingerprint")).equals(text(root.get("pinned_fingerprint"))) && !root.hasNonNull("previous_root_sha256"))) throw new IllegalArgumentException("trust root signature/hash is invalid"); if (pinned.isEmpty() || !pinned.equals(text(root.get("pinned_fingerprint")))) throw new IllegalArgumentException("pinned trust-root fingerprint is required"); if (genesis.isEmpty() || !genesis.equals(text(root.get("genesis_pinned_fingerprint")))) throw new IllegalArgumentException("externally pinned trust-root genesis fingerprint is required"); if (!verifyPayload(rootPayload(root), text(root.get("root_signature")), text(root.get("root_public_key_pem")))) throw new IllegalArgumentException("trust root root-signature invalid"); if (root.hasNonNull("previous_root_sha256")) { if (previous == null || !text(previous.get("content_sha256")).equals(text(root.get("previous_root_sha256"))) || !text(root.get("previous_root_pinned_fingerprint")).equals(text(previous.get("pinned_fingerprint")))) throw new IllegalArgumentException("rotated trust root predecessor is missing or unpinned"); verifyTrustRootInternal(previous, now, text(previous.get("pinned_fingerprint")), genesis, null); ObjectNode rotation = JSON.objectNode().put("schema", "strategy-trust-root-rotation/1").put("previous_root_sha256", text(previous.get("content_sha256"))).put("previous_root_key_id", text(previous.get("root_key_id"))).put("new_root_key_id", text(root.get("root_key_id"))).put("generation", root.path("generation").asInt()); if (root.path("generation").asInt() <= previous.path("generation").asInt() || !verifyPayload(rotation, text(root.get("rotation_signature")), text(previous.get("root_public_key_pem")))) throw new IllegalArgumentException("trust-root rotation signature invalid"); } Set<String> ids = new HashSet<>(); for (JsonNode row : rows(root.get("delegations"))) { if (!ids.add(text(row.get("key_id"))) || text(row.get("role")).isEmpty() || text(row.get("public_key_pem")).isEmpty() || !verifyPayload(delegationPayload(object(row, "delegation")), text(row.get("signature")), text(root.get("root_public_key_pem")))) throw new IllegalArgumentException("delegation signature invalid"); if (row.hasNonNull("valid_from") && time(row.get("valid_from")) > now) throw new IllegalArgumentException("delegation not yet valid"); if (row.hasNonNull("valid_until") && time(row.get("valid_until")) < now) throw new IllegalArgumentException("delegation expired"); if (rows(root.get("revoked_key_ids")).stream().anyMatch(r -> text(r).equals(text(row.get("key_id"))))) throw new IllegalArgumentException("delegated key revoked"); } requireDistinctDelegations((ArrayNode) root.get("delegations"), "distinct asset/portfolio/revocation delegations required"); }

    // Files/JSON primitives and small transaction helpers.
    private static ObjectNode ledgerIndex(String lineage) { ObjectNode out = JSON.objectNode().put("schema", "strategy-prospective-ledger-index/1").put("version", 1).put("lineage_sha256", lineage).put("sequence", 0).put("head_sha256", ledgerGenesis(lineage)); out.set("prior_snapshot_root", NullNode.instance); out.set("prior_head_sha256", NullNode.instance); out.putArray("event_refs"); return withHash(out); }
    private static ObjectNode registryIndex(String lineage) { ObjectNode out = JSON.objectNode().put("schema", "strategy-prospective-replay-index/1").put("version", 1).put("lineage_sha256", lineage).put("sequence", 0).put("head_sha256", registryGenesis(lineage)); out.putArray("entry_refs"); return withHash(out); }
    private static String ledgerGenesis(String lineage) { return hash(JSON.objectNode().put("schema", "strategy-prospective-ledger-genesis/1").put("lineage_sha256", lineage)); }
    private static String registryGenesis(String lineage) { return hash(JSON.objectNode().put("schema", "strategy-prospective-replay-genesis/1").put("lineage_sha256", lineage)); }
    private static String transactionFingerprint(String expected, List<JsonNode> events) { ArrayNode rows = JSON.arrayNode(); for (JsonNode e : events) { ObjectNode row = JSON.objectNode().put("event_id", text(e.get("event_id"))).put("kind", text(e.get("kind"))).put("asset", text(e.get("asset"))).put("completed_bar_id", text(e.get("completed_bar_id"))).put("decision_time", text(e.get("decision_time"))).put("availability_time", text(e.get("availability_time"))).put("source_receipt_sha256", text(e.get("source_receipt_sha256"))); row.set("payload", e.get("payload") == null ? NullNode.instance : e.get("payload")); rows.add(row); } ObjectNode value = JSON.objectNode().put("expected_head_sha256", expected); value.set("events", rows); return hash(value); }
    /* Callers that already own .lock must not recursively acquire it.  Recovery is
       deliberately idempotent; the public entry point acquires the lock and the
       locked readers/appender invoke the same reconciliation body directly. */
    private static void recoverUnlocked(Path path) { reconcileTransactionsUnlocked(path); }
    private static ObjectNode readTransactionJournal(Path path, String id) { Path p = transactionPath(path, id); if (!exists(p)) return null; ObjectNode j = parseObject(readBytes(p)); if (!"strategy-prospective-ledger-transaction/1".equals(text(j.get("schema"))) || j.path("version").asInt() != 1 || !ownHash(j).equals(text(j.get("content_sha256"))) || !isHash(text(j.get("transaction_id"))) || !id.equals(text(j.get("transaction_id"))) || !isHash(text(j.get("expected_head_sha256"))) || !isHash(text(j.get("lineage_sha256"))) || !j.has("updated_index") || !j.path("events").isArray() || !j.path("refs").isArray()) throw new IllegalArgumentException("ledger transaction journal is invalid: " + p); return j; }
    private static void writeJournal(Path path, ObjectNode journal) { Path root = path.resolve(".transactions"); secureParents(root); realDirOrCreate(root); atomic(root.resolve(text(journal.get("transaction_id")) + ".json"), withHash(journal)); }
    private static Path transactionPath(Path path, String id) { return path.toAbsolutePath().normalize().resolve(".transactions").resolve(id + ".json"); }
    private static void removeStage(Path path, ObjectNode j) { Path stage = path.resolve(text(j.get("stage_root"))); if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage); }
    private static void promoteNoOverwrite(Path staged, Path target, String sha) { if (exists(target)) { if (!sha.equals(hash(readBytes(target)))) throw new IllegalArgumentException("content-addressed event collision at " + target); if (exists(staged)) { if (!sha.equals(hash(readBytes(staged)))) throw new IllegalArgumentException("staged event bytes are tampered: " + staged); deleteFile(staged); } return; } if (!exists(staged)) throw new IllegalArgumentException("staged event is missing: " + staged); if (!sha.equals(hash(readBytes(staged)))) throw new IllegalArgumentException("staged event bytes are tampered: " + staged); secureParents(target.getParent()); move(staged, target); }
    private static ObjectNode physicalJson(Path path, String sha, Set<String> schemas) { return physicalJson(path, sha, schemas, "content_sha256"); }
    private static ObjectNode physicalJson(Path path, String sha, Set<String> schemas, String field) { if (path == null || !exists(path)) throw new IllegalArgumentException("physical source artifact is missing"); requireHash(sha, "physical source byte hash"); byte[] bytes = readBytes(path); if (!sha.equals(hash(bytes))) throw new IllegalArgumentException("physical source byte hash mismatch"); ObjectNode value = parseObject(bytes); if (schemas != null && !schemas.isEmpty() && !schemas.contains(text(value.get("schema")))) throw new IllegalArgumentException("physical source artifact schema is unsupported"); if (!isValidHash(text(value.get(field))) || !text(value.get(field)).equals(ownHash(value, field))) throw new IllegalArgumentException("physical source " + field + " is invalid"); return value; }
    private static ObjectNode physicalBytes(Path path, String sha, String name) { if (path == null || !exists(path)) throw new IllegalArgumentException(name + " source artifact is missing"); requireHash(sha, name + " source byte hash"); String actual = hash(readBytes(path)); if (!sha.equals(actual)) throw new IllegalArgumentException(name + " source byte hash mismatch"); return JSON.objectNode().put("byte_sha256", actual); }
    private static boolean validCandidateSet(ObjectNode v) { if (Set.of("strategy-candidate-set/4", "strategy-candidate-set/5").contains(text(v.get("schema")))) return v.path("candidates").isArray() && !v.path("candidates").isEmpty() && v.path("declared_k").asDouble() >= v.path("effective_k").asDouble() && rows(v.get("candidates")).stream().allMatch(r -> r.hasNonNull("candidate_id") && isValidHash(text(r.get("behavior_sha256")))); return "strategy-v5-statistical-input/1".equals(text(v.get("schema"))) && v.path("candidates").isArray() && !v.path("candidates").isEmpty() && v.path("episodes").isArray() && !v.path("episodes").isEmpty() && v.hasNonNull("lineage") && isValidHash(text(v.get("exposure_head_sha256"))); }
    private static boolean validFeatureInput(ObjectNode v) { return "research-feature-set/1".equals(text(v.get("schema"))) ? !v.path("labels_allowed").asBoolean(true) && isValidHash(text(v.get("data_manifest_sha256"))) && isValidHash(text(v.get("feature_code_sha256"))) : "strategy-v5-source-receipt/1".equals(text(v.get("schema"))) && v.path("authoritative").asBoolean(false) && "PUBLIC_OBSERVED".equals(text(v.get("status"))) && v.has("series") && v.path("coverage").path("complete").asBoolean(false) && !v.has("labels") && !v.has("outcomes"); }
    private static ObjectNode withHashValidated(ObjectNode v) { ObjectNode out = withHash(v); validateSchema(out); return out; }
    private static ArrayNode arrayOfConcat(JsonNode a, JsonNode b) { ArrayNode out = a == null || !a.isArray() ? JSON.arrayNode() : (ArrayNode) a.deepCopy(); out.add(b); return out; }
    private static ArrayNode concat(JsonNode a, JsonNode b) { ArrayNode out = a == null || !a.isArray() ? JSON.arrayNode() : (ArrayNode) a.deepCopy(); rows(b).forEach(out::add); return out; }
    private static ArrayNode refsAt(JsonNode refs, int at) { ArrayNode out = JSON.arrayNode(); for (JsonNode ref : rows(refs)) if (ref.path("sequence").asInt() <= at) out.add(ref); return out; }
    private static ArrayNode arrayOf(JsonNode values) { return values != null && values.isArray() ? (ArrayNode) values.deepCopy() : JSON.arrayNode(); }
    private static ArrayNode strings(List<String> values) { ArrayNode out = JSON.arrayNode(); values.forEach(out::add); return out; }
    private static List<JsonNode> rows(JsonNode value) { List<JsonNode> out = new ArrayList<>(); if (value != null && value.isArray()) value.forEach(out::add); return out; }
    private static List<String> fieldNames(JsonNode value) { List<String> out = new ArrayList<>(); if (value != null && value.isObject()) value.fieldNames().forEachRemaining(out::add); return out; }
    private static List<String> stringList(JsonNode value) { List<String> out = new ArrayList<>(); for (JsonNode v : rows(value)) out.add(text(v)); return out; }
    private static JsonNode first(JsonNode value, String... names) { if (value == null) return null; for (String name : names) { JsonNode found = value.get(name); if (found != null) return found; } return null; }
    private static String text(JsonNode value) { return value == null || value.isNull() || value.isMissingNode() ? "" : value.isTextual() ? value.textValue() : value.isBoolean() ? Boolean.toString(value.booleanValue()) : value.isNumber() ? value.asText() : value.toString(); }
    private static ObjectNode object(JsonNode value, String name) { if (value == null || !value.isObject()) throw new IllegalArgumentException(name + " must be an object"); return (ObjectNode) value; }
    private static ObjectNode barOrEmpty(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value : JSON.objectNode(); }
    private static JsonNode toNode(Object value) { if (value instanceof JsonNode n) return n; return com.tradinganalytics.infrastructure.security.JsonHashes.mapper().valueToTree(value); }
    private static boolean isHash(JsonNode value) { return isHash(text(value)); }
    private static boolean isHash(String value) { return HASH.matcher(value == null ? "" : value).matches(); }
    private static boolean isValidHash(String value) { return isHash(value) || value != null && value.length() >= 80 && value.matches("[A-Za-z0-9+/=]+"); }
    private static String requireHash(JsonNode value, String name) { return requireHash(text(value), name); }
    private static String requireHash(ObjectNode value, String camel, String snake) { return requireHash(first(value, camel, snake), snake); }
    private static String requiredHash(ObjectNode value, String camel, String snake) { return requireHash(value, camel, snake); }
    private static String requireHash(String value, String name) { if (!isHash(value)) throw new IllegalArgumentException(name + " must be a SHA-256 hash"); return value; }
    private static String path(ObjectNode value, String camel, String snake) { String out = text(first(value, camel, snake)); if (out.isEmpty()) throw new IllegalArgumentException(camel + " is required"); return out; }
    private static long time(JsonNode value) { if (value != null && value.isNumber()) { double n = value.asDouble(); if (Double.isFinite(n)) return (long) n; } String raw = text(value); try { return Instant.parse(raw).toEpochMilli(); } catch (DateTimeParseException e) { try { return (long) Double.parseDouble(raw); } catch (NumberFormatException x) { throw new IllegalArgumentException("invalid timestamp: " + raw); } } }
    private static long nowAt(JsonNode value) { return value == null || value.isNull() ? System.currentTimeMillis() : time(value); }
    private static String iso(long value) { return ISO_MILLIS.format(Instant.ofEpochMilli(value)); }
    private static JsonNode timestampNode(JsonNode value) { return value == null || value.isNull() ? NullNode.instance : JSON.textNode(iso(time(value))); }
    private static void setNullable(ObjectNode o, String key, Object value) { o.set(key, value == null ? NullNode.instance : JSON.textNode(String.valueOf(value))); }
    private static void validateSchema(JsonNode value) { SCHEMAS.validateContractSchema(value); }
    private static byte[] pretty(JsonNode value) { return NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8); }
    private static byte[] eventBytes(JsonNode value) { return pretty(value); }
    private static byte[] readBytes(Path path) { try { return PathConfinement.readSinglyLinkedFile(path.toAbsolutePath().normalize(), "physical source"); } catch (RuntimeException e) { throw e; } }
    private static ObjectNode parseObject(byte[] bytes) { try { JsonNode n = com.tradinganalytics.infrastructure.security.JsonHashes.mapper().readTree(bytes); return object(n, "JSON artifact"); } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("JSON artifact is not valid", e); } }
    private static Path absolute(String p) { return Path.of(p).toAbsolutePath().normalize(); }
    private static Path pathOf(Path root, String rel) { Path base = root.toAbsolutePath().normalize(); Path candidate = base.resolve(rel == null ? "" : rel).normalize(); if (!candidate.startsWith(base)) throw new IllegalArgumentException("content-addressed path escapes ledger root"); return candidate; }
    private static boolean exists(Path p) { return p != null && Files.exists(p, LinkOption.NOFOLLOW_LINKS); }
    private static void secureParents(Path dir) { if (dir == null) return; Path abs = dir.toAbsolutePath().normalize(); List<Path> missing = new ArrayList<>(); for (Path p = abs; p != null && !exists(p); p = p.getParent()) missing.add(p); if (!missing.isEmpty()) { Path existing = missing.get(missing.size() - 1).getParent(); if (existing != null && exists(existing)) PathConfinement.requireRealDirectory(existing, "output parent"); } for (int i = missing.size() - 1; i >= 0; i--) { try { Files.createDirectory(missing.get(i)); } catch (FileAlreadyExistsException ignored) {} catch (IOException error) { throw new IllegalArgumentException("output parent cannot be created: " + missing.get(i), error); } PathConfinement.requireRealDirectory(missing.get(i), "output parent"); } }
    private static void realDirOrCreate(Path p) { secureParents(p); PathConfinement.requireRealDirectory(p, "output directory"); }
    private static void writeExclusive(Path p, byte[] bytes) { secureParents(p.getParent()); if (exists(p)) throw new IllegalArgumentException("overwrite refused: " + p); try { Files.write(p, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); } catch (IOException e) { throw new IllegalArgumentException("immutable output cannot be written: " + p, e); } PathConfinement.validateSinglyLinkedFile(p, "immutable output"); }
    private static void atomic(Path p, ObjectNode value) { secureParents(p.getParent()); Path tmp = p.resolveSibling(p.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime()); writeExclusive(tmp, pretty(value)); try { Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { deleteFile(tmp); throw new IllegalArgumentException("atomic output cannot be written: " + p, e); } }
    private static void move(Path a, Path b) { try { Files.move(a, b, StandardCopyOption.ATOMIC_MOVE); } catch (IOException e) { try { Files.move(a, b); } catch (IOException x) { throw new IllegalArgumentException("event promotion failed: " + b, x); } } }
    private static void deleteFile(Path p) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
    private static void deleteTree(Path root) { try (var stream = Files.walk(root)) { stream.sorted(Comparator.reverseOrder()).forEach(StrategyProspectiveV5::deleteFile); } catch (IOException ignored) {} }
    private static List<Path> listRegular(Path root, String suffix) { List<Path> out = new ArrayList<>(); try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) { for (Path p : ds) if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().endsWith(suffix)) out.add(p); } catch (IOException e) { throw new IllegalArgumentException("transaction journal cannot be read", e); } return out; }
    private static <T> T withLock(Path path, java.util.function.Supplier<T> body) {
        Path root = path.toAbsolutePath().normalize(); secureParents(root); realDirOrCreate(root); Path lock = root.resolve(".lock");
        long now = System.currentTimeMillis(); String owner = ProcessHandle.current().pid() + ":" + now;
        String token = hash(JSON.objectNode().put("owner", owner).put("path", root.toString()));
        ObjectNode bodyNode = JSON.objectNode().put("schema", "strategy-prospective-lock/1").put("owner", owner)
                .put("pid", ProcessHandle.current().pid()).put("acquired_at", iso(now)).put("token", token);
        boolean acquired = false;
        for (int attempt = 0; attempt < 2 && !acquired; attempt++) {
            try { writeExclusive(lock, NodePrettyJson.write(bodyNode).getBytes(StandardCharsets.UTF_8)); acquired = true; }
            catch (RuntimeException error) {
                if (!exists(lock)) throw error;
                ObjectNode existing = null; boolean stale = false;
                try {
                    existing = parseObject(readBytes(lock));
                    long acquiredAt = existing.hasNonNull("acquired_at") ? time(existing.get("acquired_at")) : 0L;
                    long modified = Files.getLastModifiedTime(lock, LinkOption.NOFOLLOW_LINKS).toMillis();
                    stale = now - Math.max(acquiredAt, modified) > 15L * 60_000L;
                } catch (RuntimeException | IOException ignored) { stale = false; }
                if (!stale || existing == null || text(existing.get("token")).isEmpty()) throw new IllegalArgumentException("concurrent writer lock exists for " + path);
                try {
                    ObjectNode current = parseObject(readBytes(lock));
                    if (!text(current.get("token")).equals(text(existing.get("token")))) throw new IllegalArgumentException("stale lock owner changed");
                    Files.delete(lock);
                } catch (IOException | RuntimeException removeError) {
                    throw new IllegalArgumentException("stale-lock recovery failed: " + removeError.getMessage(), removeError);
                }
            }
        }
        if (!acquired) throw new IllegalArgumentException("concurrent writer lock exists for " + path);
        try { return body.get(); }
        finally { try { if (exists(lock)) { ObjectNode current = parseObject(readBytes(lock)); if (token.equals(text(current.get("token")))) Files.deleteIfExists(lock); } } catch (RuntimeException | IOException ignored) {} }
    }
    private static void hook(ObjectNode o, String name) { java.util.function.Consumer<String> callback = FAULT_HOOK.get(); if (callback != null) callback.accept(name); }
    public static ArrayNode appendProspectiveEventsAtomically(Path path, ArrayNode events, String expectedHead, long nowAt) { ObjectNode o = JSON.objectNode().put("path", path.toString()).put("expected_head_sha256", expectedHead).put("nowAt", nowAt); o.set("events", events); return appendProspectiveEventsAtomically(o); }
    public static ArrayNode appendProspectiveEventsAtomically(Path path, ArrayNode events, String expectedHead, long nowAt, java.util.function.Consumer<String> faultHook) { ObjectNode o = JSON.objectNode().put("path", path.toString()).put("expected_head_sha256", expectedHead).put("nowAt", nowAt); o.set("events", events); java.util.function.Consumer<String> previous = FAULT_HOOK.get(); FAULT_HOOK.set(faultHook); try { return appendProspectiveEventsAtomically(o); } finally { if (previous == null) FAULT_HOOK.remove(); else FAULT_HOOK.set(previous); } }
    private static KeyFactory keyFactory() throws Exception { return KeyFactory.getInstance("Ed25519"); }
    private static PrivateKey privateKey(String pem) throws Exception { return keyFactory().generatePrivate(new PKCS8EncodedKeySpec(pemBytes(pem))); }
    private static PublicKey publicKey(String pem) throws Exception { return keyFactory().generatePublic(new X509EncodedKeySpec(pemBytes(pem))); }
    private static byte[] pemBytes(String pem) { return Base64.getDecoder().decode(pem.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "")); }
    private static record Evidence(String id, String sha256) {}
    private static record SourceRefs(Path path, JsonNode refs) {}
    private static record RefAt(Path path, ObjectNode ref) {}
}
