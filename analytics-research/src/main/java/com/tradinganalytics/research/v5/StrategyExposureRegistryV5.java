package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.StrategyStatisticalV5.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Package-private exposure-head, behavior-registry, snapshot, and journal subsystem. */
final class StrategyExposureRegistryV5 {
    private static final ObjectMapper MAPPER = StrategyStatisticalV5.mapper();
    private static final JsonNodeFactory JSON = StrategyStatisticalV5.jsonFactory();
    private static final Pattern HASH_RE = StrategyStatisticalV5.hashPattern();

    static ObjectNode validateExposureHead(JsonNode rawHead) {
        ObjectNode head = objectOrEmpty(rawHead);
        assertOwnHash(head, schema("exposure"), "exposure head");
        if (!"HEAD".equals(text(field(head, "status"))) || !truthy(field(head, "hypothesis_family"))) {
            throw failure("exposure head status/family is invalid");
        }
        ArrayNode entries = requireArray(field(head, "entries"), "exposure head entries");
        long cumulative = integer(field(head, "cumulative_k"), Long.MIN_VALUE);
        if (cumulative != entries.size()) throw failure("exposure head cumulative K does not equal entries");
        if (defined(field(head, "exposure_attempt_k"))) {
            long attempts = integer(field(head, "exposure_attempt_k"), Long.MIN_VALUE);
            if (attempts < cumulative) throw failure("exposure head selection-attempt K is invalid");
        }
        Set<String> seen = new HashSet<>();
        String previous = hash("V5-STAT-GENESIS");
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            assertKnownKeys(entry, Set.of("behavior_sha256", "dataset_sha256", "observed_at", "source",
                    "sequence", "previous_sha256", "definition_sha256", "vector_commitment_sha256"),
                    "exposure entry " + index);
            if (integer(field(entry, "sequence"), Long.MIN_VALUE) != index + 1
                    || !previous.equals(text(field(entry, "previous_sha256")))) {
                throw failure("exposure head chain is broken");
            }
            String behavior = requireHash(field(entry, "behavior_sha256"),
                    "exposure.entries[" + index + "].behavior_sha256");
            requireHash(field(entry, "dataset_sha256"), "exposure.entries[" + index + "].dataset_sha256");
            if (defined(field(entry, "definition_sha256"))) requireHash(field(entry, "definition_sha256"),
                    "exposure.entries[" + index + "].definition_sha256");
            if (defined(field(entry, "vector_commitment_sha256"))) requireHash(
                    field(entry, "vector_commitment_sha256"),
                    "exposure.entries[" + index + "].vector_commitment_sha256");
            if (!seen.add(behavior)) throw failure("exposure head contains duplicate behavior aliases");
            previous = hash(entry);
        }
        ObjectNode pointerInput = object();
        pointerInput.put("hypothesis_family", text(field(head, "hypothesis_family")));
        pointerInput.put("last_entry_sha256", entries.isEmpty()
                ? hash("V5-STAT-GENESIS") : hash(entries.get(entries.size() - 1)));
        if (!hash(pointerInput).equals(text(field(head, "head_pointer_sha256")))) {
            throw failure("exposure head pointer is invalid");
        }
        if (defined(field(head, "fixed_attempt_pairs"))) {
            ArrayNode pairs = requireArray(field(head, "fixed_attempt_pairs"), "fixed attempt pairs");
            Set<String> pairKeys = new HashSet<>();
            for (JsonNode pair : pairs) {
                assertKnownKeys(pair, Set.of("behavior_sha256", "dataset_sha256"), "fixed attempt pair");
                String behavior = requireHash(field(pair, "behavior_sha256"), "fixed attempt behavior");
                String dataset = requireHash(field(pair, "dataset_sha256"), "fixed attempt dataset");
                if (!seen.contains(behavior) || !pairKeys.add(behavior + ":" + dataset)) {
                    throw failure("fixed attempt pair has unknown behavior or duplicate identity");
                }
            }
            if (pairs.size() > integer(field(head, "exposure_attempt_k"), cumulative)) {
                throw failure("fixed attempt pairs exceed recorded exposure attempts");
            }
        }
        if (defined(field(head, "fixed_attempt_ledger_migration_sha256"))) {
            requireHash(field(head, "fixed_attempt_ledger_migration_sha256"), "fixed attempt ledger migration");
            requireArray(field(head, "fixed_attempt_pairs"), "migrated fixed attempt pairs");
        }
        return head;
    }

    static ObjectNode makeExposureHead(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        String family = text(field(options, "hypothesisFamily"));
        if (family.isEmpty()) throw failure("exposure head requires a hypothesis family");
        String dataset = requireHash(field(options, "datasetSha256"), "dataset_sha256");
        ArrayNode inputEntries = defined(field(options, "entries"))
                ? requireArray(field(options, "entries"), "entries") : array();
        Set<String> unique = new HashSet<>();
        ArrayNode rows = array();
        for (int index = 0; index < inputEntries.size(); index++) {
            JsonNode raw = inputEntries.get(index);
            String behavior = requireHash(field(raw, "behavior_sha256"), "exposure entry " + index);
            if (!unique.add(behavior)) throw failure("exposure entries must be behaviorally unique");
            ObjectNode row = object();
            row.put("behavior_sha256", behavior);
            row.put("dataset_sha256", requireHash(truthy(field(raw, "dataset_sha256"))
                    ? field(raw, "dataset_sha256") : JSON.textNode(dataset),
                    "exposure entry " + index + ".dataset_sha256"));
            if (!definedNonNull(field(raw, "observed_at"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(raw, "observed_at"), "exposure entry " + index + ".observed_at"));
            row.put("source", truthy(field(raw, "source")) ? jsString(field(raw, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", index + 1);
            row.put("previous_sha256", index == 0 ? hash("V5-STAT-GENESIS") : hash(rows.get(index - 1)));
            if (defined(field(raw, "definition_sha256"))) row.put("definition_sha256",
                    requireHash(field(raw, "definition_sha256"), "exposure entry " + index + ".definition_sha256"));
            if (defined(field(raw, "vector_commitment_sha256"))) row.put("vector_commitment_sha256",
                    requireHash(field(raw, "vector_commitment_sha256"),
                            "exposure entry " + index + ".vector_commitment_sha256"));
            rows.add(row);
        }
        long attempts = definedNonNull(field(options, "exposureAttemptK"))
                ? integer(field(options, "exposureAttemptK"), Long.MIN_VALUE) : rows.size();
        if (attempts < rows.size()) throw failure("exposure attempt K must cover behavioral K");
        ObjectNode pointerInput = object();
        pointerInput.put("hypothesis_family", family);
        pointerInput.put("last_entry_sha256", rows.isEmpty()
                ? hash("V5-STAT-GENESIS") : hash(rows.get(rows.size() - 1)));
        ObjectNode result = object();
        result.put("schema", schema("exposure"));
        result.put("version", 1);
        result.put("status", "HEAD");
        result.put("hypothesis_family", family);
        result.put("dataset_sha256", dataset);
        result.set("entries", rows);
        result.put("cumulative_k", rows.size());
        result.put("exposure_attempt_k", attempts);
        result.put("head_pointer_sha256", hash(pointerInput));
        if (defined(field(options, "fixedAttemptPairs"))) {
            result.set("fixed_attempt_pairs", cloneNode(field(options, "fixedAttemptPairs")));
        }
        if (defined(field(options, "fixedAttemptLedgerMigrationSha256"))) {
            result.set("fixed_attempt_ledger_migration_sha256", cloneNode(field(options, "fixedAttemptLedgerMigrationSha256")));
        }
        return finalizeExposureHead(result);
    }

    static ObjectNode appendExposureHead(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode prior = validateExposureHead(field(options, "prior"));
        String dataset = requireHash(field(options, "datasetSha256"), "dataset_sha256");
        ArrayNode priorEntries = array(field(prior, "entries"));
        ArrayNode rows = priorEntries.deepCopy();
        Set<String> known = new HashSet<>();
        priorEntries.forEach(row -> known.add(text(field(row, "behavior_sha256"))));
        Set<String> distinct = new LinkedHashSet<>();
        array(field(options, "behaviorAliases")).forEach(value -> distinct.add(jsString(value)));
        List<String> aliases = new ArrayList<>(distinct);
        aliases.sort(String::compareTo);
        boolean fixedAttempt = field(options, "fixedAttempt").asBoolean(false);
        if (fixedAttempt) {
            if (aliases.size() != 1 || integer(field(options, "exposureAttemptCount"), 1) != 1) {
                throw failure("a fixed attempt must identify exactly one behavior and one exposure");
            }
            String behavior = requireHash(JSON.textNode(aliases.get(0)), "fixed attempt behavior");
            if (containsFixedPair(priorEntries, behavior, dataset)
                    || containsFixedPair(field(prior, "fixed_attempt_pairs"), behavior, dataset)) return prior;
        }
        JsonNode definitions = field(options, "behaviorDefinitions");
        JsonNode commitments = field(options, "vectorCommitments");
        for (String behavior : aliases) {
            requireHash(JSON.textNode(behavior), "behavior_alias_sha256");
            if (!known.add(behavior)) continue;
            ObjectNode row = object();
            row.put("behavior_sha256", behavior);
            row.put("dataset_sha256", dataset);
            if (!definedNonNull(field(options, "observedAt"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(options, "observedAt"), "observed_at"));
            row.put("source", truthy(field(options, "source")) ? jsString(field(options, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", rows.size() + 1);
            row.put("previous_sha256", rows.isEmpty() ? hash("V5-STAT-GENESIS") : hash(rows.get(rows.size() - 1)));
            if (defined(field(definitions, behavior))) row.put("definition_sha256",
                    requireHash(field(definitions, behavior), "behaviorDefinitions." + behavior));
            if (defined(field(commitments, behavior))) row.put("vector_commitment_sha256",
                    requireHash(field(commitments, behavior), "vectorCommitments." + behavior));
            rows.add(row);
        }
        long priorAttempts = defined(field(prior, "exposure_attempt_k"))
                ? integer(field(prior, "exposure_attempt_k"), 0) : integer(field(prior, "cumulative_k"), 0);
        long increment = definedNonNull(field(options, "exposureAttemptCount"))
                ? integer(field(options, "exposureAttemptCount"), Long.MIN_VALUE) : array(field(options, "behaviorAliases")).size();
        if (increment < 0) throw failure("exposure attempt increment is invalid");
        ObjectNode next = object();
        next.put("hypothesisFamily", text(field(prior, "hypothesis_family")));
        next.put("datasetSha256", dataset);
        next.set("entries", rows);
        next.put("exposureAttemptK", priorAttempts + increment);
        if (defined(field(prior, "fixed_attempt_pairs")) || fixedAttempt) {
            ArrayNode pairs = defined(field(prior, "fixed_attempt_pairs"))
                    ? requireArray(field(prior, "fixed_attempt_pairs"), "fixed attempt pairs").deepCopy() : array();
            if (fixedAttempt) pairs.add(object().put("behavior_sha256", aliases.get(0)).put("dataset_sha256", dataset));
            next.set("fixedAttemptPairs", pairs);
        }
        if (defined(field(prior, "fixed_attempt_ledger_migration_sha256"))) {
            next.set("fixedAttemptLedgerMigrationSha256", cloneNode(field(prior, "fixed_attempt_ledger_migration_sha256")));
        }
        return makeExposureHead(next);
    }

    private static boolean containsFixedPair(JsonNode pairs, String behavior, String dataset) {
        if (pairs == null || !pairs.isArray()) return false;
        for (JsonNode pair : pairs) {
            if (behavior.equals(text(field(pair, "behavior_sha256")))
                    && dataset.equals(text(field(pair, "dataset_sha256")))) return true;
        }
        return false;
    }

    /** Migrate the short-lived sidecar once, within the same HEAD lock and atomic write. */
    private static ObjectNode migrateFixedAttemptLedger(Path target, ObjectNode prior) throws IOException {
        if (defined(field(prior, "fixed_attempt_ledger_migration_sha256"))) return prior;
        Path legacyPath = Path.of(target + ".fixed-attempt-ledger.json");
        if (!Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) return prior;
        if (Files.isSymbolicLink(legacyPath)) throw failure("fixed attempt ledger cannot be a symbolic link");
        ObjectNode legacy = objectOrEmpty(MAPPER.readTree(Files.readString(legacyPath, StandardCharsets.UTF_8)));
        assertOwnHash(legacy, "strategy-fixed-attempt-ledger/1", "legacy fixed attempt ledger");
        if (!text(field(prior, "hypothesis_family")).equals(text(field(legacy, "family")))
                || !text(field(prior, "content_sha256")).equals(text(field(legacy, "head_sha256")))) {
            throw failure("legacy fixed attempt ledger requires reconciliation with its bound HEAD before migration");
        }
        ArrayNode pairs = defined(field(prior, "fixed_attempt_pairs"))
                ? requireArray(field(prior, "fixed_attempt_pairs"), "fixed attempt pairs").deepCopy() : array();
        for (JsonNode pair : requireArray(field(legacy, "pairs"), "legacy fixed attempt pairs")) {
            if (!containsFixedPair(pairs, text(field(pair, "behavior_sha256")), text(field(pair, "dataset_sha256")))) {
                pairs.add(pair.deepCopy());
            }
        }
        ObjectNode migrated = prior.deepCopy();
        migrated.set("fixed_attempt_pairs", pairs);
        migrated.set("fixed_attempt_ledger_migration_sha256", cloneNode(field(legacy, "content_sha256")));
        return finalizeExposureHead(migrated);
    }

    static ObjectNode readExposureHeadFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) throw failure("exposure head path is required");
        JsonNode value;
        try { value = MAPPER.readTree(Files.readString(Path.of(filePath), StandardCharsets.UTF_8)); }
        catch (Exception error) { throw failure("cannot read exposure head: " + error.getMessage()); }
        return validateExposureHead(value);
    }

    static ObjectNode readExposureHeadFile(Path filePath) {
        return readExposureHeadFile(filePath == null ? null : filePath.toString());
    }

    static ObjectNode initializeExposureHeadFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode head = validateExposureHead(field(options, "head"));
        String rawPath = text(field(options, "filePath"));
        try { writeExclusiveJson(requiredFilePath(rawPath, "exposure head path"), head); }
        catch (Exception error) {
            throw failure("exposure head already exists or cannot be initialized: " + error.getMessage());
        }
        return readExposureHeadFile(rawPath);
    }

    static ObjectNode appendExposureHeadFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        String rawPath = text(field(options, "filePath"));
        Path target = requiredFilePath(rawPath, "exposure head path");
        String expected = requireHash(field(options, "expectedHeadSha256"), "expected_head_sha256");
        String dataset = requireHash(field(options, "datasetSha256"), "dataset_sha256");
        Path lock = Path.of(target.toString() + ".lock");
        FileChannel lockChannel = null;
        boolean ownsLock = false;
        try {
            ensureParent(target); lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownsLock = true;
            ObjectNode prior = readExposureHeadFile(target);
            if (!expected.equals(text(field(prior, "content_sha256")))) {
                throw failure("stale or competing exposure head predecessor");
            }
            ObjectNode append = object();
            boolean fixedAttempt = field(options, "fixedAttempt").asBoolean(false);
            append.set("prior", fixedAttempt ? migrateFixedAttemptLedger(target, prior) : prior);
            append.put("datasetSha256", dataset);
            if (fixedAttempt) append.put("fixedAttempt", true);
            append.set("behaviorAliases", cloneNode(field(options, "behaviorAliases")));
            append.set("behaviorDefinitions", cloneNode(field(options, "behaviorDefinitions")));
            append.set("vectorCommitments", cloneNode(field(options, "vectorCommitments")));
            append.set("observedAt", defined(field(options, "observedAt"))
                    ? cloneNode(field(options, "observedAt")) : NullNode.instance);
            append.put("source", truthy(field(options, "source"))
                    ? jsString(field(options, "source")) : "STATISTICAL_SEARCH");
            if (defined(field(options, "exposureAttemptCount"))) {
                append.set("exposureAttemptCount", cloneNode(field(options, "exposureAttemptCount")));
            }
            ObjectNode next = appendExposureHead(append);
            ObjectNode current = readExposureHeadFile(target);
            if (!expected.equals(text(field(current, "content_sha256")))) {
                throw failure("exposure head changed during append");
            }
            writeAtomicJson(target, next); return readExposureHeadFile(target);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing exposure head writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw failure(error.getMessage());
        } finally {
            if (lockChannel != null) try { lockChannel.close(); } catch (IOException ignored) {}
            if (ownsLock) try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    static boolean validateBehaviorDefinitionRegistry(JsonNode registry) {
        return validateBehaviorDefinitionRegistry(registry, object());
    }

    static boolean validateBehaviorDefinitionRegistry(JsonNode registry, ObjectNode args) {
        assertOwnHash(registry, schema("behaviorRegistry"), "behavior-definition registry");
        assertKnownKeys(registry, Set.of("schema", "version", "status", "hypothesis_family",
                "exposure_head_sha256", "entries", "snapshot_path", "snapshot_content_sha256",
                "snapshot_byte_sha256", "content_sha256"), "behavior-definition registry");
        if (!"HEAD".equals(text(field(registry, "status")))
                || text(field(registry, "hypothesis_family")).isEmpty() || !field(registry, "entries").isArray()) {
            throw failure("behavior-definition registry status/family is invalid");
        }
        boolean anySnapshot = defined(field(registry, "snapshot_path"))
                || defined(field(registry, "snapshot_content_sha256")) || defined(field(registry, "snapshot_byte_sha256"));
        if (anySnapshot) {
            String pointer = text(field(registry, "snapshot_path"));
            if (pointer.isEmpty() || !HASH_RE.matcher(text(field(registry, "snapshot_content_sha256"))).matches()
                    || !HASH_RE.matcher(text(field(registry, "snapshot_byte_sha256"))).matches()) {
                throw failure("behavior-definition registry snapshot binding is incomplete");
            }
            String normalized = pointer.replace('\\', '/');
            if (pointer.startsWith("/") || pointer.startsWith("\\")
                    || List.of(normalized.split("/")).contains("..")) {
                throw failure("behavior-definition registry snapshot path must be portable and relative");
            }
        }
        requireHash(field(registry, "exposure_head_sha256"),
                "behavior-definition registry exposure_head_sha256");
        ObjectNode exposure = field(args, "exposureHead").isObject()
                ? validateExposureHead(field(args, "exposureHead")) : null;
        if (exposure != null && !text(field(registry, "exposure_head_sha256"))
                .equals(text(field(exposure, "content_sha256")))) {
            throw failure("behavior-definition registry/exposure head lineage mismatch");
        }
        Set<String> seen = new HashSet<>();
        String previous = hash("V5-STAT-BEHAVIOR-REGISTRY-GENESIS");
        ArrayNode entries = array(field(registry, "entries"));
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            assertKnownKeys(entry, Set.of("behavior_sha256", "definition_sha256", "dataset_sha256",
                    "observed_at", "source", "sequence", "previous_sha256", "chromosome",
                    "evaluator_sha256", "precommit_sha256", "lifecycle_sha256"),
                    "behavior-definition registry entry " + index);
            if (integer(field(entry, "sequence"), Long.MIN_VALUE) != index + 1
                    || !previous.equals(text(field(entry, "previous_sha256")))) {
                throw failure("behavior-definition registry chain is broken");
            }
            String behavior = requireHash(field(entry, "behavior_sha256"),
                    "behavior-definition registry entry " + index + ".behavior_sha256");
            requireHash(field(entry, "definition_sha256"),
                    "behavior-definition registry entry " + index + ".definition_sha256");
            requireHash(field(entry, "dataset_sha256"),
                    "behavior-definition registry entry " + index + ".dataset_sha256");
            requireHash(field(entry, "evaluator_sha256"),
                    "behavior-definition registry entry " + index + ".evaluator_sha256");
            if (!field(entry, "precommit_sha256").isNull()) requireHash(field(entry, "precommit_sha256"),
                    "behavior-definition registry entry " + index + ".precommit_sha256");
            if (!field(entry, "lifecycle_sha256").isNull()) requireHash(field(entry, "lifecycle_sha256"),
                    "behavior-definition registry entry " + index + ".lifecycle_sha256");
            if (!field(entry, "chromosome").isObject()) {
                throw failure("behavior-definition registry entry " + index + " lacks a physical chromosome definition");
            }
            if (!field(entry, "observed_at").isNull()) iso(field(entry, "observed_at"),
                    "behavior-definition registry entry " + index + ".observed_at");
            if (!behaviorDefinitionSha256(entry).equals(text(field(entry, "definition_sha256")))) {
                throw failure("behavior-definition registry entry " + index + " definition hash is invalid");
            }
            if (!seen.add(behavior)) throw failure("behavior-definition registry contains duplicate behavior aliases");
            previous = hash(entry);
        }
        if (exposure != null) {
            Map<String, JsonNode> definitions = new LinkedHashMap<>();
            entries.forEach(entry -> definitions.put(text(field(entry, "behavior_sha256")), entry));
            Set<String> aliases = new HashSet<>();
            array(field(exposure, "entries")).forEach(entry -> aliases.add(text(field(entry, "behavior_sha256"))));
            for (JsonNode entry : entries) if (!aliases.contains(text(field(entry, "behavior_sha256")))) {
                throw failure("behavior-definition registry contains an alias absent from the exposure head: "
                        + text(field(entry, "behavior_sha256")));
            }
            for (JsonNode exposureEntry : array(field(exposure, "entries"))) {
                String behavior = text(field(exposureEntry, "behavior_sha256"));
                JsonNode definition = definitions.get(behavior);
                if (definition == null) throw failure("exposure head behavior " + behavior
                        + " has no durable physical definition");
                if (defined(field(exposureEntry, "definition_sha256"))
                        && !text(field(exposureEntry, "definition_sha256"))
                        .equals(text(field(definition, "definition_sha256")))) {
                    throw failure("exposure head definition commitment differs from durable registry for " + behavior);
                }
            }
        }
        return true;
    }

    static ObjectNode makeBehaviorDefinitionRegistry(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode exposure = field(options, "exposureHead").isObject()
                ? validateExposureHead(field(options, "exposureHead")) : null;
        JsonNode headNode = exposure == null ? field(options, "exposureHeadSha256")
                : field(exposure, "content_sha256");
        String headSha = requireHash(headNode, "behavior-definition registry exposure_head_sha256");
        ArrayNode input = array(field(options, "entries")); ArrayNode rows = array(); Set<String> seen = new HashSet<>();
        for (int index = 0; index < input.size(); index++) {
            JsonNode raw = input.get(index);
            String behavior = requireHash(field(raw, "behavior_sha256"),
                    "behavior-definition registry entry " + index + ".behavior_sha256");
            if (!seen.add(behavior)) throw failure("behavior-definition registry entries must be unique");
            JsonNode chromosome = cloneNode(field(raw, "chromosome"));
            if (!chromosome.isObject()) throw failure("behavior-definition registry entry " + index + ".chromosome is invalid");
            ObjectNode row = object(); row.put("behavior_sha256", behavior);
            row.put("definition_sha256", behaviorDefinitionSha256(raw));
            row.put("dataset_sha256", requireHash(field(raw, "dataset_sha256"),
                    "behavior-definition registry entry " + index + ".dataset_sha256"));
            if (!definedNonNull(field(raw, "observed_at"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(raw, "observed_at"),
                    "behavior-definition registry entry " + index + ".observed_at"));
            row.put("source", truthy(field(raw, "source")) ? jsString(field(raw, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", index + 1); row.put("previous_sha256",
                    index == 0 ? hash("V5-STAT-BEHAVIOR-REGISTRY-GENESIS") : hash(rows.get(index - 1)));
            row.set("chromosome", chromosome);
            row.put("evaluator_sha256", requireHash(field(raw, "evaluator_sha256"),
                    "behavior-definition registry entry " + index + ".evaluator_sha256"));
            if (!defined(field(raw, "precommit_sha256")) || field(raw, "precommit_sha256").isNull()) {
                row.set("precommit_sha256", NullNode.instance);
            } else row.put("precommit_sha256", requireHash(field(raw, "precommit_sha256"),
                    "behavior-definition registry entry " + index + ".precommit_sha256"));
            if (!defined(field(raw, "lifecycle_sha256")) || field(raw, "lifecycle_sha256").isNull()) {
                row.set("lifecycle_sha256", NullNode.instance);
            } else row.put("lifecycle_sha256", requireHash(field(raw, "lifecycle_sha256"),
                    "behavior-definition registry entry " + index + ".lifecycle_sha256"));
            rows.add(row);
        }
        ObjectNode value = object(); value.put("schema", schema("behaviorRegistry")); value.put("version", 1);
        value.put("status", "HEAD"); value.put("hypothesis_family", jsString(field(options, "hypothesisFamily")));
        value.put("exposure_head_sha256", headSha); value.set("entries", rows);
        ObjectNode result = withHash(value); ObjectNode validation = object();
        if (exposure != null) validation.set("exposureHead", exposure);
        validateBehaviorDefinitionRegistry(result, validation); validateRegisteredSchema(result); return result;
    }

    static ObjectNode appendBehaviorDefinitionRegistry(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        ObjectNode exposure = validateExposureHead(field(options, "exposureHead"));
        String expected = definedNonNull(field(options, "expectedExposureHeadSha256"))
                ? text(field(options, "expectedExposureHeadSha256")) : null;
        if (expected != null && expected.equals(text(field(exposure, "content_sha256")))) {
            throw failure("behavior-definition registry append requires the new exposure head plus its predecessor");
        }
        ObjectNode prior = field(options, "prior").isObject() ? objectOrEmpty(field(options, "prior")) : null;
        if (prior != null) {
            validateBehaviorDefinitionRegistry(prior);
            if (expected != null && !expected.equals(text(field(prior, "exposure_head_sha256")))) {
                throw failure("behavior-definition registry predecessor is stale");
            }
            if (!text(field(prior, "hypothesis_family")).equals(text(field(exposure, "hypothesis_family")))) {
                throw failure("behavior-definition registry family differs from exposure head");
            }
        }
        ArrayNode rows = prior == null ? array() : array(field(prior, "entries")).deepCopy();
        Map<String, JsonNode> known = new LinkedHashMap<>();
        rows.forEach(row -> known.put(text(field(row, "behavior_sha256")), row));
        Set<String> headAliases = new HashSet<>();
        array(field(exposure, "entries")).forEach(row -> headAliases.add(text(field(row, "behavior_sha256"))));
        List<JsonNode> definitions = new ArrayList<>(); array(field(options, "definitions")).forEach(definitions::add);
        definitions.sort(Comparator.comparing(row -> text(field(row, "behavior_sha256"))));
        for (JsonNode raw : definitions) {
            String behavior = requireHash(field(raw, "behavior_sha256"), "behavior definition alias");
            if (!headAliases.contains(behavior)) throw failure("behavior definition " + behavior + " is absent from exposure head");
            JsonNode chromosome = cloneNode(field(raw, "chromosome"));
            ObjectNode normalized = objectOrEmpty(raw).deepCopy(); normalized.put("behavior_sha256", behavior);
            normalized.set("chromosome", chromosome);
            if (!truthy(field(normalized, "dataset_sha256"))) normalized.set("dataset_sha256", field(exposure, "dataset_sha256"));
            if (!truthy(field(normalized, "evaluator_sha256"))) normalized.set("evaluator_sha256", field(raw, "evaluator_spec_sha256"));
            if (!defined(field(normalized, "precommit_sha256"))) normalized.set("precommit_sha256", NullNode.instance);
            if (!defined(field(normalized, "lifecycle_sha256"))) normalized.set("lifecycle_sha256", NullNode.instance);
            String definitionSha = behaviorDefinitionSha256(normalized); JsonNode existing = known.get(behavior);
            if (existing != null) {
                if (!definitionSha.equals(text(field(existing, "definition_sha256")))
                        || !stable(effectiveExecutionBehavior(field(existing, "chromosome")))
                        .equals(stable(effectiveExecutionBehavior(chromosome)))) {
                    throw failure("behavior definition " + behavior + " changed after exposure");
                }
                continue;
            }
            ObjectNode row = object(); row.put("behavior_sha256", behavior); row.put("definition_sha256", definitionSha);
            row.put("dataset_sha256", requireHash(field(normalized, "dataset_sha256"), behavior + ".dataset_sha256"));
            if (!definedNonNull(field(normalized, "observed_at"))) row.set("observed_at", NullNode.instance);
            else row.put("observed_at", iso(field(normalized, "observed_at"), behavior + ".observed_at"));
            row.put("source", truthy(field(normalized, "source")) ? jsString(field(normalized, "source")) : "STATISTICAL_SEARCH");
            row.put("sequence", rows.size() + 1); row.put("previous_sha256",
                    rows.isEmpty() ? hash("V5-STAT-BEHAVIOR-REGISTRY-GENESIS") : hash(rows.get(rows.size() - 1)));
            row.set("chromosome", chromosome); row.put("evaluator_sha256",
                    requireHash(field(normalized, "evaluator_sha256"), behavior + ".evaluator_sha256"));
            if (field(normalized, "precommit_sha256").isNull()) row.set("precommit_sha256", NullNode.instance);
            else row.put("precommit_sha256", requireHash(field(normalized, "precommit_sha256"), behavior + ".precommit_sha256"));
            if (field(normalized, "lifecycle_sha256").isNull()) row.set("lifecycle_sha256", NullNode.instance);
            else row.put("lifecycle_sha256", requireHash(field(normalized, "lifecycle_sha256"), behavior + ".lifecycle_sha256"));
            rows.add(row); known.put(behavior, row);
        }
        ObjectNode make = object(); make.put("hypothesisFamily", truthy(field(options, "hypothesisFamily"))
                ? jsString(field(options, "hypothesisFamily")) : text(field(exposure, "hypothesis_family")));
        make.set("exposureHead", exposure); make.set("entries", rows);
        ObjectNode result = makeBehaviorDefinitionRegistry(make);
        if (array(field(result, "entries")).size() != array(field(exposure, "entries")).size()) {
            throw failure("behavior-definition registry is incomplete for the exposure head");
        }
        return result;
    }

    static ObjectNode readBehaviorDefinitionRegistryFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) throw failure("behavior-definition registry path is required");
        JsonNode value;
        try { value = MAPPER.readTree(Files.readString(Path.of(filePath), StandardCharsets.UTF_8)); }
        catch (Exception error) { throw failure("cannot read behavior-definition registry: " + error.getMessage()); }
        validateBehaviorDefinitionRegistry(value); return objectOrEmpty(value);
    }

    static ObjectNode readBehaviorDefinitionRegistryFile(Path filePath) {
        return readBehaviorDefinitionRegistryFile(filePath == null ? null : filePath.toString());
    }

    static ObjectNode appendBehaviorDefinitionRegistryFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path target = requiredFilePath(text(field(options, "filePath")), "behavior-definition registry path");
        ObjectNode exposure = validateExposureHead(field(options, "exposureHead"));
        Path lock = Path.of(target + ".lock"); FileChannel lockChannel = null;
        boolean ownsLock = false;
        try {
            ensureParent(target); lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownsLock = true;
            ObjectNode existing = Files.exists(target) ? readBehaviorDefinitionRegistryFile(target) : null;
            if (existing != null && truthy(field(options, "expectedRegistrySha256"))
                    && !text(field(options, "expectedRegistrySha256")).equals(text(field(existing, "content_sha256")))) {
                throw failure("stale or competing behavior-definition registry predecessor");
            }
            if (existing != null && truthy(field(options, "priorExposureHeadSha256"))
                    && !text(field(options, "priorExposureHeadSha256"))
                    .equals(text(field(existing, "exposure_head_sha256")))) {
                throw failure("behavior-definition registry is not bound to the exposure predecessor");
            }
            if (existing != null && !truthy(field(options, "priorExposureHeadSha256"))) {
                throw failure("behavior-definition registry append requires an exposure-head predecessor");
            }
            ObjectNode append = object(); if (existing != null) append.set("prior", existing);
            append.set("exposureHead", exposure); append.set("expectedExposureHeadSha256",
                    defined(field(options, "priorExposureHeadSha256"))
                            ? cloneNode(field(options, "priorExposureHeadSha256")) : NullNode.instance);
            append.set("definitions", cloneNode(field(options, "definitions")));
            ObjectNode next = appendBehaviorDefinitionRegistry(append);
            ObjectNode validation = object(); validation.set("exposureHead", exposure);
            validateBehaviorDefinitionRegistry(next, validation);
            if (existing != null) {
                ObjectNode current = readBehaviorDefinitionRegistryFile(target);
                if (!text(field(current, "content_sha256")).equals(text(field(existing, "content_sha256")))) {
                    throw failure("behavior-definition registry changed during append");
                }
            } else if (Files.exists(target)) {
                throw failure("behavior-definition registry appeared during append");
            }
            writeAtomicJson(target, next); return next;
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing behavior-definition registry writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw failure(error.getMessage());
        } finally {
            if (lockChannel != null) try { lockChannel.close(); } catch (IOException ignored) {}
            if (ownsLock) try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    static ObjectNode bindBehaviorDefinitionRegistrySnapshotFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path state = requiredFilePath(text(field(options, "filePath")), "behavior-definition registry state path");
        Path snapshotPath = requiredFilePath(text(field(options, "snapshotPath")),
                "behavior-definition registry snapshot path");
        ObjectNode snapshot = objectOrEmpty(field(options, "snapshot")); validateBehaviorDefinitionRegistry(snapshot);
        Path directory = state.getParent();
        assertConfinedPath(state, "behavior-definition registry state", false, directory);
        Path physicalSnapshot = assertConfinedPath(snapshotPath, "behavior-definition registry snapshot", true, directory);
        String relative = directory.relativize(physicalSnapshot).toString().replace('\\', '/');
        if (relative.isEmpty() || relative.equals(".") || relative.equals("..") || relative.startsWith("../")
                || relative.startsWith("/")) {
            throw failure("behavior-definition registry snapshot must remain inside its state directory");
        }
        requireRegularSingleLink(physicalSnapshot, "behavior-definition registry immutable snapshot");
        byte[] snapshotBytes;
        ObjectNode onDisk;
        try {
            snapshotBytes = Files.readAllBytes(physicalSnapshot); onDisk = objectOrEmpty(MAPPER.readTree(snapshotBytes));
        } catch (Exception error) {
            throw failure("behavior-definition registry immutable snapshot is not valid JSON");
        }
        validateBehaviorDefinitionRegistry(onDisk);
        if (!text(field(onDisk, "content_sha256")).equals(text(field(snapshot, "content_sha256")))) {
            throw failure("behavior-definition registry immutable snapshot content changed");
        }
        Path lock = Path.of(state + ".lock"); FileChannel lockChannel = null;
        boolean ownsLock = false;
        try {
            lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownsLock = true;
            if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("behavior-definition registry state is missing before snapshot bind");
            }
            assertConfinedPath(state, "behavior-definition registry state", true, directory);
            ObjectNode existing = readBehaviorDefinitionRegistryFile(state);
            if (truthy(field(options, "expectedRegistrySha256"))
                    && !text(field(options, "expectedRegistrySha256")).equals(text(field(existing, "content_sha256")))) {
                throw failure("stale or competing behavior-definition registry state predecessor");
            }
            if (!text(field(existing, "hypothesis_family")).equals(text(field(snapshot, "hypothesis_family")))) {
                throw failure("behavior-definition registry snapshot family differs from state predecessor");
            }
            ArrayNode priorEntries = array(field(existing, "entries")); ArrayNode snapshotEntries = array(field(snapshot, "entries"));
            if (snapshotEntries.size() < priorEntries.size()) {
                throw failure("behavior-definition registry snapshot rolls back the state predecessor");
            }
            for (int index = 0; index < priorEntries.size(); index++) {
                if (!stable(priorEntries.get(index)).equals(stable(snapshotEntries.get(index)))) {
                    throw failure("behavior-definition registry snapshot does not preserve the state predecessor lineage at "
                            + index + ": " + text(field(priorEntries.get(index), "behavior_sha256")) + " != "
                            + (snapshotEntries.size() > index
                                    ? text(field(snapshotEntries.get(index), "behavior_sha256")) : "missing"));
                }
            }
            ObjectNode value = snapshot.deepCopy(); value.put("snapshot_path", relative);
            value.put("snapshot_content_sha256", text(field(snapshot, "content_sha256")));
            value.put("snapshot_byte_sha256", hash(snapshotBytes)); ObjectNode next = withHash(value);
            validateBehaviorDefinitionRegistry(next); validateRegisteredSchema(next); writeAtomicJson(state, next); return next;
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw failure("competing behavior-definition registry state writer is active");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw failure(error.getMessage());
        } finally {
            if (lockChannel != null) try { lockChannel.close(); } catch (IOException ignored) {}
            if (ownsLock) try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    static ObjectNode resolveBehaviorDefinitionRegistrySnapshotFile(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        Path state = requiredFilePath(text(field(options, "filePath")), "behavior-definition registry state path");
        Path directory = state.getParent(); assertConfinedPath(state, "behavior-definition registry state", true, directory);
        ObjectNode registry = field(options, "registry").isObject()
                ? objectOrEmpty(field(options, "registry")) : readBehaviorDefinitionRegistryFile(state);
        if (!truthy(field(registry, "snapshot_path"))) return null;
        String pointer = text(field(registry, "snapshot_path")); String normalized = pointer.replace('\\', '/');
        if (pointer.startsWith("/") || pointer.startsWith("\\") || List.of(normalized.split("/")).contains("..")) {
            throw failure("behavior-definition registry snapshot pointer is not portable or confined");
        }
        Path target = directory.resolve(pointer).toAbsolutePath().normalize();
        if (target.equals(directory) || !target.startsWith(directory)) {
            throw failure("behavior-definition registry snapshot pointer escapes its state directory");
        }
        assertConfinedPath(target, "behavior-definition registry snapshot pointer", true, directory);
        requireRegularSingleLink(target, "behavior-definition registry snapshot pointer");
        byte[] bytes;
        try { bytes = Files.readAllBytes(target); }
        catch (IOException error) { throw failure("behavior-definition registry snapshot pointer cannot be read"); }
        if (!hash(bytes).equals(text(field(registry, "snapshot_byte_sha256")))) {
            throw failure("behavior-definition registry immutable snapshot bytes are tampered");
        }
        ObjectNode snapshot;
        try { snapshot = objectOrEmpty(MAPPER.readTree(bytes)); }
        catch (IOException error) { throw failure("behavior-definition registry immutable snapshot is not valid JSON"); }
        validateBehaviorDefinitionRegistry(snapshot);
        if (!text(field(snapshot, "content_sha256")).equals(text(field(registry, "snapshot_content_sha256")))) {
            throw failure("behavior-definition registry immutable snapshot content binding differs from state");
        }
        if (!stable(behaviorRegistrySemantic(registry)).equals(stable(behaviorRegistrySemantic(snapshot)))) {
            throw failure("behavior-definition registry state semantic contents differ from its immutable snapshot");
        }
        ObjectNode result = object(); result.put("path", target.toString()); result.set("value", snapshot);
        result.put("byte_sha256", hash(bytes)); return result;
    }

    static ObjectNode writeExposureRegistryJournal(ObjectNode args) {
        ObjectNode options = args == null ? object() : args;
        if (!truthy(field(options, "journalPath"))) throw failure("registry journal path is required");
        Path target = Path.of(jsString(field(options, "journalPath"))).toAbsolutePath().normalize();
        ObjectNode value = registryJournalValue(options);
        try {
            ensureParent(target); writeExclusiveJson(target, value); return value;
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            try {
                JsonNode existing = MAPPER.readTree(Files.readString(target, StandardCharsets.UTF_8));
                assertOwnHash(existing, schema("registryJournal"), "registry journal");
                if (text(field(existing, "content_sha256")).equals(text(field(value, "content_sha256")))) {
                    return objectOrEmpty(existing);
                }
            } catch (Exception ignored) {
                // A malformed or different prepared transaction is a competing writer.
            }
            throw failure("registry journal already exists or cannot be prepared: " + exists.getMessage());
        } catch (Exception error) {
            throw failure("registry journal already exists or cannot be prepared: " + error.getMessage());
        }
    }

    static ObjectNode recoverExposureRegistryTransaction(ObjectNode args) {
        ObjectNode options = args == null ? object() : args; JsonNode rawPath = field(options, "journalPath");
        if (!truthy(rawPath) || !Files.exists(Path.of(jsString(rawPath)), LinkOption.NOFOLLOW_LINKS)) {
            ObjectNode none = object(); none.put("status", "NONE");
            if (truthy(rawPath)) none.set("journal_path", cloneNode(rawPath)); else none.putNull("journal_path");
            return none;
        }
        Path journalPath = Path.of(jsString(rawPath)); ObjectNode journal;
        try { journal = objectOrEmpty(MAPPER.readTree(Files.readString(journalPath, StandardCharsets.UTF_8))); }
        catch (Exception error) { throw failure("registry journal is unreadable: " + error.getMessage()); }
        assertOwnHash(journal, schema("registryJournal"), "registry journal"); validateContractSchema(journal);
        if (!"PREPARED".equals(text(field(journal, "status")))) throw failure("registry journal status is invalid");
        ObjectNode head = readExposureHeadFile(text(field(journal, "exposure_head_path")));
        String headSha = text(field(head, "content_sha256")); String priorHead = text(field(journal, "prior_head_sha256"));
        String nextHead = text(field(journal, "next_head_sha256"));
        if (!headSha.equals(priorHead) && !headSha.equals(nextHead)) {
            throw failure("registry journal exposure head is neither the recorded predecessor nor successor");
        }
        Path registryPath = Path.of(text(field(journal, "registry_path")));
        ObjectNode existing = Files.exists(registryPath, LinkOption.NOFOLLOW_LINKS)
                ? readBehaviorDefinitionRegistryFile(registryPath) : null;
        if (headSha.equals(priorHead)) {
            String expectedRegistry = truthy(field(journal, "prior_registry_sha256"))
                    ? jsString(field(journal, "prior_registry_sha256")) : null;
            if (existing == null || !java.util.Objects.equals(text(field(existing, "content_sha256")), expectedRegistry)) {
                throw failure("registry journal predecessor registry is inconsistent");
            }
            try { Files.delete(journalPath.toAbsolutePath().normalize()); }
            catch (IOException error) { throw failure(error.getMessage()); }
            ObjectNode result = object(); result.put("status", "ABORTED_BEFORE_HEAD_COMMIT");
            result.set("journal_path", cloneNode(rawPath)); result.put("head_sha256", headSha); return result;
        }
        ObjectNode registry;
        if (existing != null && nextHead.equals(text(field(existing, "exposure_head_sha256")))) {
            ObjectNode validate = object(); validate.set("exposureHead", head);
            validateBehaviorDefinitionRegistry(existing, validate); registry = existing;
        } else {
            ObjectNode append = object(); append.put("filePath", registryPath.toString());
            if (existing == null) append.putNull("expectedRegistrySha256");
            else append.put("expectedRegistrySha256", text(field(existing, "content_sha256")));
            append.put("priorExposureHeadSha256", priorHead); append.set("exposureHead", head);
            append.set("definitions", cloneNode(field(journal, "definitions")));
            registry = appendBehaviorDefinitionRegistryFile(append);
        }
        if (!nextHead.equals(text(field(registry, "exposure_head_sha256")))) {
            throw failure("recovered behavior registry does not bind the committed exposure head");
        }
        ObjectNode validate = object(); validate.set("exposureHead", head);
        validateBehaviorDefinitionRegistry(registry, validate);
        try { Files.delete(journalPath.toAbsolutePath().normalize()); }
        catch (IOException error) { throw failure(error.getMessage()); }
        ObjectNode result = object(); result.put("status", "RECOVERED_REGISTRY");
        result.set("journal_path", cloneNode(rawPath)); result.put("head_sha256", headSha);
        result.put("registry_sha256", text(field(registry, "content_sha256"))); return result;
    }

    private static ObjectNode registryJournalValue(ObjectNode options) {
        ObjectNode prior = validateExposureHead(field(options, "priorHead"));
        ObjectNode next = validateExposureHead(field(options, "nextHead"));
        if (text(field(next, "content_sha256")).equals(text(field(prior, "content_sha256")))) {
            throw failure("registry journal requires a new exposure head");
        }
        ObjectNode value = object(); value.put("schema", schema("registryJournal")); value.put("version", 1);
        value.put("status", "PREPARED");
        value.put("exposure_head_path", Path.of(jsString(field(options, "exposureHeadPath")))
                .toAbsolutePath().normalize().toString());
        value.put("registry_path", Path.of(jsString(field(options, "registryPath")))
                .toAbsolutePath().normalize().toString());
        value.put("prior_head_sha256", text(field(prior, "content_sha256")));
        value.put("next_head_sha256", text(field(next, "content_sha256")));
        if (definedNonNull(field(options, "priorRegistrySha256"))) {
            value.set("prior_registry_sha256", cloneNode(field(options, "priorRegistrySha256")));
        } else value.putNull("prior_registry_sha256");
        value.set("next_head", next.deepCopy()); ArrayNode definitions = array();
        for (JsonNode definition : array(field(options, "definitions"))) definitions.add(cloneNode(definition));
        value.set("definitions", definitions);
        if (truthy(field(options, "journalPath"))) value.put("journal_path", Path.of(
                jsString(field(options, "journalPath"))).toAbsolutePath().normalize().toString());
        else value.putNull("journal_path");
        ObjectNode result = withHash(value); validateContractSchema(result); return result;
    }

    private static ObjectNode finalizeExposureHead(ObjectNode value) {
        ObjectNode result = withHash(value);
        validateExposureHead(result);
        validateRegisteredSchema(result);
        return result;
    }

    private static ObjectNode behaviorRegistrySemantic(JsonNode registry) {
        ObjectNode result = object();
        for (String key : List.of("schema", "version", "status", "hypothesis_family", "exposure_head_sha256")) {
            result.set(key, cloneNode(field(registry, key)));
        }
        result.set("entries", cloneNode(field(registry, "entries"))); return result;
    }
}
