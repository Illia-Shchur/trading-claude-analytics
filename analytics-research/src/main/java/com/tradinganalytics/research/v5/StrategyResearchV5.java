package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Quarantined Java compatibility facade for {@code tools/strategy-research-v5.mjs}.
 *
 * <p>The class deliberately delegates authoritative execution to the specialized v5 owners. The
 * code retained here is the old public facade contract: typed candidate fixtures, immutable
 * exposure heads, fail-closed legacy helpers, aggregate validation/indexing, and deployment
 * evidence adapters. It never upgrades fixture output into authoritative evidence.</p>
 */
public final class StrategyResearchV5 {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    /** SHA-256 of the frozen Node facade source at the migration baseline. */
    private static final String STRATEGY_RESEARCH_V5_CODE_SHA256 =
            "cba56a4d27105e47b0ac091610923ce1874be2cf4166c2f5e269a9f1b533c134";
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> GENE_TYPES = Set.of(
            "continuous", "ordered-discrete", "categorical", "structural");
    private static final Set<String> COSMETIC = Set.of(
            "candidate_id", "id", "label", "description", "display_name", "hypothesis_index",
            "generation", "seed", "operator", "parent_ids");
    private static final Set<String> LABEL_KEYS = Set.of(
            "target", "label", "outcome", "forward_return", "future_return", "forward_pnl",
            "future_pnl", "net_r", "gross_r", "resolved_at", "resolution_bars", "exit_price",
            "exit_time");
    private static final Map<String, String> GENESIS_FAMILIES =
            Collections.synchronizedMap(new HashMap<>());

    public static final Map<String, String> V5;
    public static final List<String> V5_UNIVERSE = List.of(
            "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    public static final List<String> V5_INSTRUMENTS = List.of(
            "BINANCE_SPOT", "BINANCE_USDM_PERPETUAL", "BINANCE_USDM_DATED_FUTURE");
    public static final Map<String, Object> V5_DEFAULTS;

    public static final String FEATURE_DAG_SCHEMA = FeatureDagV5.FEATURE_DAG_SCHEMA;
    public static final String FEATURE_DAG_CODE_SHA256 = FeatureDagV5.FEATURE_DAG_CODE_SHA256;
    public static final String LIFECYCLE_SCHEMA = TradeLifecycleV5.LIFECYCLE_SCHEMA;
    public static final String LIFECYCLE_TRUST_SCHEMA = LifecycleTrustService.LIFECYCLE_TRUST_SCHEMA;
    public static final String OPPORTUNITY_SCHEMA = OpportunityV5.OPPORTUNITY_SCHEMA;
    public static final String HYDRATION_SCHEMA = OpportunityV5.HYDRATION_SCHEMA;
    public static final String OPPORTUNITY_DOMAIN_SCHEMA = OpportunityV5.OPPORTUNITY_DOMAIN_SCHEMA;

    static {
        LinkedHashMap<String, String> schemas = new LinkedHashMap<>();
        schemas.put("candidate", "strategy-candidate-set/5");
        schemas.put("genetic", "strategy-genetic-run/1");
        schemas.put("exposure", "strategy-exposure-ledger/2");
        schemas.put("wfo", "strategy-wfo-result/2");
        schemas.put("run", "strategy-research-run/5");
        schemas.put("evidence", "strategy-research-evidence/5");
        schemas.put("manifest", "strategy-data-manifest/3");
        schemas.put("envelope", "strategy-opportunity-envelope/1");
        schemas.put("prospective", "strategy-prospective-runner/2");
        schemas.put("index", "strategy-research-index/5");
        schemas.put("deployment", "strategy-deployment-audit/1");
        schemas.put("publication", "strategy-prospective-publication/1");
        V5 = Collections.unmodifiableMap(schemas);

        LinkedHashMap<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("population", 48);
        defaults.put("maxGenerations", 20);
        defaults.put("minGenerations", 10);
        defaults.put("plateauGenerations", 5);
        defaults.put("crossoverProbability", .9d);
        defaults.put("seeds", List.of(11, 23, 47));
        defaults.put("halfLifeMonths", 18);
        defaults.put("outerFolds", 8);
        defaults.put("purgeDays", 30);
        defaults.put("embargoDays", 7);
        V5_DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    private final LifecycleTrustService trustService;
    private final TradeLifecycleV5 lifecycle;

    public StrategyResearchV5(LifecycleTrustService trustService) {
        this.trustService = Objects.requireNonNull(trustService, "trustService");
        this.lifecycle = new TradeLifecycleV5(trustService);
    }

    /* Canonical JSON facade. */

    public static String stable(JsonNode value) {
        return CanonicalJson.canonicalize(value == null ? NullNode.instance : value);
    }

    public static String hash(JsonNode value) {
        return JsonHashes.canonicalSha256(value == null ? NullNode.instance : value);
    }

    public static String hash(String value) { return JsonHashes.sha256(value); }
    public static String hash(byte[] value) { return JsonHashes.sha256(value); }

    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }

    public static String ownHash(JsonNode value, String field) {
        JsonNode copy = value == null ? NullNode.instance : value.deepCopy();
        if (copy instanceof ObjectNode object) object.remove(field);
        return hash(copy);
    }

    public static ObjectNode withHash(ObjectNode value) { return withHash(value, "content_sha256"); }

    public static ObjectNode withHash(ObjectNode value, String field) {
        ObjectNode copy = value == null ? object() : value.deepCopy();
        copy.remove(field);
        copy.put(field, ownHash(copy, field));
        return copy;
    }

    /* Direct-owner re-exports: feature DAG. */

    public static ObjectNode makeFeatureGraphV5(ObjectNode options) {
        return FeatureDagV5.makeFeatureGraphV5(options);
    }
    public static boolean validateFeatureGraphV5(JsonNode graph) {
        return FeatureDagV5.validateFeatureGraphV5(graph);
    }
    public static ObjectNode evaluateFeatureGraphV5(JsonNode graph, ObjectNode options) {
        return FeatureDagV5.evaluateFeatureGraphV5(graph, options);
    }
    public static ObjectNode evaluateFeatureDagV5(JsonNode graph, ObjectNode options) {
        return FeatureDagV5.evaluateFeatureDagV5(graph, options);
    }
    public static ObjectNode planFeatureGraphV5(ObjectNode options) {
        return FeatureDagV5.planFeatureGraphV5(options);
    }
    public static ObjectNode deriveFeatureRequirementsV5(ObjectNode options) {
        return FeatureDagV5.deriveFeatureRequirementsV5(options);
    }
    public static ObjectNode resumeRecursiveEmaV5(ArrayNode values, ObjectNode options) {
        return FeatureDagV5.resumeRecursiveEmaV5(values, options);
    }
    public static ObjectNode resumeWilderRsiV5(ArrayNode values, ObjectNode options) {
        return FeatureDagV5.resumeWilderRsiV5(values, options);
    }
    public static ArrayNode pointInTimeJoinV5(ObjectNode options) {
        return FeatureDagV5.pointInTimeJoinV5(options);
    }
    public static ArrayNode joinPointInTimeV5(ObjectNode options) {
        return FeatureDagV5.joinPointInTimeV5(options);
    }
    public static boolean assertTradeableFeatureGraphV5(JsonNode graph) {
        return FeatureDagV5.assertTradeableFeatureGraphV5(graph);
    }
    public static boolean assertTradeableFeatureGraphV5(JsonNode graph, JsonNode outputs) {
        return FeatureDagV5.assertTradeableFeatureGraphV5(graph, outputs);
    }
    public static boolean validateFeatureLineageV5(JsonNode graph) {
        return FeatureDagV5.validateFeatureLineageV5(graph);
    }
    public static ObjectNode dedupeEvidenceVotesV5(ObjectNode options) {
        return FeatureDagV5.dedupeEvidenceVotesV5(options);
    }

    /* Direct-owner re-exports: lifecycle and injected physical trust. */

    public static boolean validateLifecycleSpecV5(JsonNode spec) {
        return TradeLifecycleV5.validateLifecycleSpecV5(spec);
    }
    public static boolean validateLifecycleSpecV5(JsonNode spec, String side, String instrumentType) {
        return TradeLifecycleV5.validateLifecycleSpecV5(spec, side, instrumentType);
    }
    public ObjectNode normalizeTradeLifecycleV5(ObjectNode request) {
        return lifecycle.normalizeTradeLifecycleV5(request);
    }
    public ObjectNode normalizeTradeLifecycleV5(
            ObjectNode request, LifecycleTrustService.Token token) {
        return lifecycle.normalizeTradeLifecycleV5(request, token);
    }
    public ObjectNode simulateTradeLifecycleV5(ObjectNode request) {
        return lifecycle.simulateTradeLifecycleV5(request);
    }
    public ObjectNode simulateLifecycleV5(ObjectNode request) {
        return lifecycle.simulateLifecycleV5(request);
    }
    public ObjectNode executeTradeIntentV5(ObjectNode request) {
        return lifecycle.executeTradeIntentV5(request);
    }
    public LifecycleTrustService.Token openLifecycleTrustV5(
            Path root, String rootReference,
            Map<String, LifecycleTrustService.ReceiptReference> receipts,
            Object lineage, boolean requireBars) {
        return trustService.openLifecycleTrustV5(root, rootReference, receipts, lineage, requireBars);
    }
    public LifecycleTrustService.ReopenedTrust reopenLifecycleTrustV5(
            LifecycleTrustService.Token token, Map<String, JsonNode> suppliedValues) {
        return trustService.reopenLifecycleTrustV5(token, suppliedValues);
    }
    public boolean isLifecycleTrustV5(LifecycleTrustService.Token token) {
        return trustService.isLifecycleTrustV5(token);
    }
    public String lifecycleTrustReceiptHashV5(LifecycleTrustService.Token token, String role) {
        return trustService.lifecycleTrustReceiptHashV5(token, role);
    }

    /* Direct-owner re-exports: opportunity domain/envelope/hydration. */

    public static ObjectNode makeOpportunityDomainV5(ObjectNode options) {
        return OpportunityV5.makeOpportunityDomainV5(options);
    }
    public static boolean validateOpportunityDomainV5(JsonNode domain) {
        return OpportunityV5.validateOpportunityDomainV5(domain);
    }
    public static ObjectNode makeOpportunityEnvelopeV5(ObjectNode options) {
        return OpportunityV5.makeOpportunityEnvelopeV5(options);
    }
    public static ObjectNode buildOpportunityEnvelopeV5(ObjectNode options) {
        return OpportunityV5.buildOpportunityEnvelopeV5(options);
    }
    public static boolean validateOpportunityEnvelopeV5(JsonNode envelope) {
        return OpportunityV5.validateOpportunityEnvelopeV5(envelope);
    }
    public static ObjectNode assertCandidateIntentSubsetV5(ObjectNode options) {
        return OpportunityV5.assertCandidateIntentSubsetV5(options);
    }
    public static ObjectNode proveCandidateSubsetV5(ObjectNode options) {
        return OpportunityV5.proveCandidateSubsetV5(options);
    }
    public static ObjectNode makeContentAddressedPartitionsV5(ObjectNode options) {
        return OpportunityV5.makeContentAddressedPartitionsV5(options);
    }
    public static ObjectNode normalizeExecutionPartitionsV5(ObjectNode options) {
        return OpportunityV5.normalizeExecutionPartitionsV5(options);
    }
    public static ObjectNode hydrateOpportunityEnvelopeV5(ObjectNode options) {
        return OpportunityV5.hydrateOpportunityEnvelopeV5(options);
    }
    public static ObjectNode buildOpportunityHydrationV5(ObjectNode options) {
        return OpportunityV5.buildOpportunityHydrationV5(options);
    }
    public static ObjectNode hydrateExecutionEnvelopeV5(ObjectNode options) {
        return OpportunityV5.hydrateExecutionEnvelopeV5(options);
    }
    public static ObjectNode readHydratedRangeV5(ObjectNode options) {
        return OpportunityV5.readHydratedRangeV5(options);
    }
    public static ObjectNode lazyReadHydratedRangeV5(ObjectNode options) {
        return OpportunityV5.lazyReadHydratedRangeV5(options);
    }
    public static ObjectNode readExecutionRangeV5(ObjectNode options) {
        return OpportunityV5.readExecutionRangeV5(options);
    }

    /* Frozen typed gene space and candidate compatibility. */

    public static ObjectNode normalizeGeneSpace(JsonNode rawSpace) {
        return normalizeGeneSpace(rawSpace, object());
    }

    public static ObjectNode normalizeGeneSpace(JsonNode rawSpace, ObjectNode options) {
        JsonNode source = rawSpace == null ? object() : rawSpace;
        JsonNode rawGenes = first(source.get("genes"), at(source, "chromosome", "genes"),
                at(source, "experiment", "genetic", "genes"));
        ArrayNode genes = array();
        if (rawGenes != null && rawGenes.isArray()) {
            int index = 0;
            for (JsonNode raw : rawGenes) genes.add(normalizeGene(raw, index++));
        }
        if (genes.isEmpty()) throw failure("genetic search requires a frozen typed gene space");
        Set<String> names = new HashSet<>();
        for (JsonNode gene : genes) if (!names.add(text(gene, "name"))) {
            throw failure("duplicate gene " + text(gene, "name"));
        }
        boolean authoritative = bool(options, "authoritative") || bool(source, "authoritative");
        if (authoritative) for (JsonNode gene : genes) if (!gene.has("usage")) {
            throw failure("authoritative gene space requires a frozen usage mapping for every gene");
        }
        ObjectNode value = object();
        value.put("schema", "strategy-gene-space/1");
        value.set("genes", genes);
        value.put("authoritative", authoritative);
        value.put("content_sha256", hash(value));
        return value;
    }

    private static ObjectNode normalizeGene(JsonNode raw, int index) {
        ObjectNode result = object();
        String name = text(raw, "name");
        if (name.isEmpty()) name = text(raw, "id");
        if (name.isEmpty()) name = "gene_" + (index + 1);
        String type = text(raw, "type");
        if (type.isEmpty()) type = "categorical";
        type = type.toLowerCase(Locale.ROOT);
        if (!GENE_TYPES.contains(type)) throw failure("unsupported gene type " + type);
        result.put("name", name); result.put("type", type);
        switch (type) {
            case "continuous" -> {
                double min = finite(raw.get("min"), name + ".min");
                double max = finite(raw.get("max"), name + ".max");
                if (max < min) throw failure(name + " max is below min");
                result.put("min", min); result.put("max", max);
                if (!defined(raw.get("step"))) result.putNull("step");
                else {
                    double step = finite(raw.get("step"), name + ".step");
                    if (!(step > 0)) throw failure(name + ".step must be positive");
                    result.put("step", step);
                }
                result.put("precision", raw.path("precision").isIntegralNumber()
                        ? raw.path("precision").asInt() : 8);
                result.put("default", defined(raw.get("default"))
                        ? finite(raw.get("default"), name + ".default") : min);
            }
            case "ordered-discrete" -> {
                if (!raw.path("values").isArray() || raw.path("values").isEmpty()) {
                    throw failure(name + " has no values");
                }
                List<Double> values = new ArrayList<>();
                for (JsonNode value : raw.path("values")) values.add(finite(value, name + ".value"));
                values = values.stream().distinct().sorted().toList();
                ArrayNode output = array(); values.forEach(output::add); result.set("values", output);
                result.put("default", defined(raw.get("default"))
                        ? finite(raw.get("default"), name + ".default") : values.getFirst());
            }
            default -> {
                if (!raw.path("values").isArray() || raw.path("values").isEmpty()) {
                    throw failure(name + " has no values");
                }
                ArrayNode values = raw.path("values").deepCopy(); result.set("values", values);
                result.set("default", defined(raw.get("default"))
                        ? raw.get("default").deepCopy() : values.get(0).deepCopy());
            }
        }
        if (defined(raw.get("usage"))) result.put("usage", raw.get("usage").asText());
        return result;
    }

    public static ObjectNode makeCandidateSetV5(ObjectNode options) {
        ObjectNode args = options == null ? object() : options;
        String precommit = requiredHash(firstText(args, "precommitSha256", "precommit_sha256"),
                "precommitSha256");
        String experiment = requiredHash(firstText(args, "experimentSha256", "experiment_sha256"),
                "experimentSha256");
        String objective = requiredHash(firstText(args, "objectiveContractSha256", "objective_contract_sha256"),
                "objectiveContractSha256");
        String acceptance = requiredHash(firstText(args, "acceptanceSha256", "acceptance_sha256"),
                "acceptanceSha256");
        ObjectNode space = normalizeGeneSpace(args.get("geneSpace"));
        ArrayNode candidates = arrayOrEmpty(args.get("candidates"));
        if (candidates.isEmpty()) throw failure("v5 candidate set cannot be empty");
        String generator = firstText(args, "generator"); if (generator.isEmpty()) generator = "GENETIC";
        ArrayNode rows = array(); int index = 0;
        for (JsonNode candidate : candidates) {
            JsonNode source = first(candidate.get("definition"), candidate.get("candidate"), candidate);
            ObjectNode definition = compileStrategyDefinition(space, objectOrEmpty(source),
                    space.path("authoritative").asBoolean(false));
            String behavior = behavior(definition);
            JsonNode rawVector = first(candidate.get("behavior_vector"),
                    candidate.get("canonical_behavior_vector"), at(candidate, "fitness", "metrics", "episode_returns"));
            ArrayNode vector = canonicalObservedVector(rawVector);
            String alias = vector == null || vector.isEmpty() ? behavior : hash(observedVectorEnvelope(vector));
            ObjectNode row = object();
            String id = text(candidate, "candidate_id");
            if (id.isEmpty()) id = "v5-candidate-%06d".formatted(index + 1);
            row.put("candidate_id", id); row.set("definition", definition);
            row.put("behavior_sha256", behavior); row.put("behavior_alias_sha256", alias);
            if (vector == null) { row.putNull("behavior_vector"); row.putNull("behavior_vector_sha256"); }
            else { row.set("behavior_vector", vector); row.put("behavior_vector_sha256", hash(observedVectorEnvelope(vector))); }
            row.put("hypothesis_index", ++index); row.put("generator", generator); rows.add(row);
        }
        TreeMap<String, List<String>> aliasIds = new TreeMap<>();
        for (JsonNode row : rows) aliasIds.computeIfAbsent(text(row, "behavior_alias_sha256"), ignored -> new ArrayList<>())
                .add(text(row, "candidate_id"));
        ArrayNode aliases = array();
        aliasIds.forEach((alias, ids) -> {
            Collections.sort(ids); ObjectNode entry = object(); entry.put("behavior_sha256", alias);
            entry.set("candidate_ids", strings(ids)); aliases.add(entry);
        });
        ObjectNode lineage = objectOrEmpty(args.get("lineage")).deepCopy();
        lineage.put("precommit_sha256", precommit); lineage.put("experiment_sha256", experiment);
        lineage.put("objective_contract_sha256", objective); lineage.put("acceptance_sha256", acceptance);
        ObjectNode accounting = object(); accounting.put("declared_k", rows.size());
        accounting.put("effective_k", aliases.size()); accounting.put("behavioural_aliases_included", true);
        accounting.put("every_candidate_recorded", true);
        ObjectNode result = object(); result.put("schema", V5.get("candidate")); result.put("version", 1);
        result.put("precommit_sha256", precommit); result.put("experiment_sha256", experiment);
        result.put("objective_contract_sha256", objective); result.put("acceptance_sha256", acceptance);
        result.set("lineage", lineage); result.set("gene_space", space); result.set("candidates", rows);
        result.put("declared_k", rows.size()); result.put("effective_k", aliases.size());
        result.set("aliases", aliases); result.set("accounting", accounting);
        result = withHash(result); validateCandidateSetV5(result); return result;
    }

    public static boolean validateCandidateSetV5(JsonNode candidateSet) {
        assertHash(candidateSet, V5.get("candidate"), "v5 candidate set");
        for (String field : List.of("precommit_sha256", "experiment_sha256",
                "objective_contract_sha256", "acceptance_sha256")) {
            requiredHash(text(candidateSet, field), "candidate set " + field);
        }
        JsonNode lineage = candidateSet.path("lineage");
        for (String field : List.of("precommit_sha256", "experiment_sha256",
                "objective_contract_sha256", "acceptance_sha256")) {
            if (!text(candidateSet, field).equals(text(lineage, field))) {
                throw failure("v5 candidate lineage is incomplete or inconsistent");
            }
        }
        ArrayNode candidates = arrayOrEmpty(candidateSet.get("candidates"));
        ArrayNode aliases = arrayOrEmpty(candidateSet.get("aliases"));
        if (candidates.isEmpty() || candidateSet.path("declared_k").asInt(-1) != candidates.size()
                || candidateSet.path("effective_k").asInt(-1) != aliases.size()) {
            throw failure("v5 candidate accounting K does not reconcile");
        }
        Set<String> ids = new HashSet<>(), aliasSet = new HashSet<>();
        for (JsonNode candidate : candidates) {
            String id = text(candidate, "candidate_id");
            if (!ids.add(id)) throw failure("v5 candidate id collision");
            String behavior = requiredHash(text(candidate, "behavior_sha256"), "candidate behavior");
            String alias = requiredHash(text(candidate, "behavior_alias_sha256"), "candidate behavior alias");
            if (!behavior.equals(behavior(candidate.get("definition")))) {
                throw failure("v5 candidate definition hash mismatch " + id);
            }
            JsonNode vector = candidate.get("behavior_vector");
            if (defined(vector)) {
                if (!vector.isArray()) throw failure("v5 candidate observed behavior vector is not hash-bound " + id);
                String vectorHash = hash(observedVectorEnvelope((ArrayNode) vector));
                if (!vectorHash.equals(text(candidate, "behavior_vector_sha256")) || !vectorHash.equals(alias)) {
                    throw failure("v5 candidate observed behavior vector is not hash-bound " + id);
                }
            } else if (!alias.equals(behavior)) {
                throw failure("v5 candidate alias lacks an observed vector " + id);
            }
            aliasSet.add(alias);
        }
        ArrayNode expected = array();
        aliasSet.stream().sorted().forEach(alias -> {
            List<String> idsForAlias = new ArrayList<>();
            for (JsonNode row : candidates) if (alias.equals(text(row, "behavior_alias_sha256"))) {
                idsForAlias.add(text(row, "candidate_id"));
            }
            Collections.sort(idsForAlias); ObjectNode entry = object(); entry.put("behavior_sha256", alias);
            entry.set("candidate_ids", strings(idsForAlias)); expected.add(entry);
        });
        if (!stable(expected).equals(stable(aliases))) throw failure("v5 candidate alias registry does not reconcile");
        if (aliasSet.size() != candidateSet.path("effective_k").asInt(-1)) {
            throw failure("v5 candidate behavioural K mismatch");
        }
        return true;
    }

    public static ArrayNode chromosomeNeighbours(JsonNode rawSpace, JsonNode rawDefinition) {
        ObjectNode space = normalizeGeneSpace(rawSpace);
        ObjectNode base = chromosome(space, objectOrEmpty(rawDefinition));
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode gene : space.path("genes")) {
            String name = text(gene, "name"), type = text(gene, "type");
            JsonNode value = base.get(name);
            switch (type) {
                case "continuous" -> {
                    double span = defined(gene.get("step")) ? gene.get("step").asDouble()
                            : (gene.path("max").asDouble() - gene.path("min").asDouble()) / 20d;
                    if (span == 0) span = 1;
                    for (int direction : List.of(-1, 1)) {
                        ObjectNode next = base.deepCopy();
                        next.set(name, quantize(NF.numberNode(value.asDouble() + direction * span), gene));
                        if (!stable(next).equals(stable(base))) result.add(next);
                    }
                }
                case "ordered-discrete" -> {
                    ArrayNode values = (ArrayNode) gene.path("values"); int current = indexOf(values, value);
                    for (int nextIndex : List.of(current - 1, current + 1)) if (nextIndex >= 0 && nextIndex < values.size()) {
                        ObjectNode next = base.deepCopy(); next.set(name, values.get(nextIndex).deepCopy()); result.add(next);
                    }
                }
                case "categorical" -> {
                    for (JsonNode choice : gene.path("values")) if (!stable(choice).equals(stable(value))) {
                        ObjectNode next = base.deepCopy(); next.set(name, choice.deepCopy()); result.add(next);
                    }
                }
                default -> { /* structural alternatives are not contour neighbours */ }
            }
        }
        TreeMap<String, ObjectNode> unique = new TreeMap<>();
        result.forEach(row -> unique.putIfAbsent(stable(row), row));
        List<ObjectNode> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparing(StrategyResearchV5::behavior));
        ArrayNode output = array(); ordered.forEach(output::add); return output;
    }

    public static Double weightedP20(JsonNode returns) { return weightedP20(returns, object()); }

    public static Double weightedP20(JsonNode returns, ObjectNode options) {
        List<ReturnRow> rows = valuesFromReturns(returns); if (rows.isEmpty()) return null;
        String cutoff = firstText(options, "cutoff");
        int months = integer(first(options.get("halfLifeMonths"), options.get("half_life_months")), 18);
        double[] weights = new double[rows.size()]; double total = 0;
        for (int i = 0; i < rows.size(); i++) {
            double value = cutoff.isEmpty() ? 1d : Math.pow(2d,
                    -Math.max(0d, (millis(cutoff) - millis(rows.get(i).time()))
                            / (30.4375d * 86_400_000d)) / months);
            weights[i] = value; total += value;
        }
        if (total == 0) total = 1; for (int i = 0; i < weights.length; i++) weights[i] /= total;
        return p20(blockBootstrapMeans(rows, 512, 0x51eed, weights));
    }

    public static ArrayNode selectNsgaSurvivors(ArrayNode population, int populationSize) {
        List<ObjectNode> rows = objects(population); List<List<ObjectNode>> ranked = rankAndCrowd(rows);
        ArrayNode output = array();
        for (List<ObjectNode> front : ranked) {
            if (output.size() + front.size() <= populationSize) front.forEach(output::add);
            else {
                int remaining = Math.max(0, populationSize - output.size());
                front.sort((a, b) -> {
                    double ac = crowding(a), bc = crowding(b);
                    int order = Double.compare(bc, ac);
                    return order != 0 ? order : text(a, "behavior_sha256").compareTo(text(b, "behavior_sha256"));
                });
                for (int i = 0; i < remaining; i++) output.add(front.get(i)); break;
            }
        }
        return output;
    }

    public static ObjectNode selectBestV5(ArrayNode rows) {
        if (rows == null || rows.isEmpty()) return null;
        return objects(rows).stream().min((a, b) -> {
            JsonNode af = a.path("fitness"), bf = b.path("fitness");
            boolean feasibleA = af.path("feasible").asBoolean(false), feasibleB = bf.path("feasible").asBoolean(false);
            if (feasibleA != feasibleB) return feasibleA ? -1 : 1;
            JsonNode am = af.path("metrics"), bm = bf.path("metrics");
            double scoreA = Math.min(number(am, "bootstrap_p20", -1e9), number(am, "weighted_p20", -1e9));
            double scoreB = Math.min(number(bm, "bootstrap_p20", -1e9), number(bm, "weighted_p20", -1e9));
            int order = Double.compare(scoreB, scoreA);
            if (order == 0) order = Double.compare(drawdown(am), drawdown(bm));
            if (order == 0) order = Double.compare(number(am, "complexity", 0), number(bm, "complexity", 0));
            if (order == 0) order = text(a, "behavior_sha256").compareTo(text(b, "behavior_sha256"));
            return order;
        }).orElse(null);
    }

    public static ObjectNode writeGeneticCheckpoint(Path path, JsonNode state) {
        ObjectNode value = object(); value.put("schema", "strategy-genetic-checkpoint/1"); value.put("version", 1);
        value.set("state", checkpointSafe(state)); ObjectNode checkpoint = withHash(value);
        Path target = path.toAbsolutePath().normalize(); Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(temporary, pretty(checkpoint), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return checkpoint;
        } catch (IOException error) { throw failure(error.getMessage()); }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    public static ObjectNode writeGeneticCheckpoint(String path, JsonNode state) {
        return writeGeneticCheckpoint(Path.of(path), state);
    }

    public static ObjectNode readGeneticCheckpoint(Path path) {
        try {
            JsonNode value = JSON.readTree(Files.readAllBytes(path.toAbsolutePath().normalize()));
            if (!value.isObject() || !"strategy-genetic-checkpoint/1".equals(text(value, "schema"))
                    || !ownHash(value).equals(text(value, "content_sha256"))) {
                throw failure("tampered genetic checkpoint");
            }
            return (ObjectNode) value;
        } catch (IOException error) { throw failure(error.getMessage()); }
    }

    public static ObjectNode readGeneticCheckpoint(String path) {
        return readGeneticCheckpoint(Path.of(path));
    }

    private static ObjectNode geneticCurrentState(int seed, int generation, List<ObjectNode> population,
                                                   LinkedHashMap<String, ObjectNode> evaluated, XorShift random,
                                                   String previousSignature, int plateau, String stopping) {
        ObjectNode current = object(); current.put("seed", seed); current.put("generation", generation);
        current.set("population", toArray(population)); current.set("evaluated", toArray(evaluated.values()));
        current.put("rng_state", random.state()); current.put("previous_signature", previousSignature);
        current.put("plateau", plateau); current.put("stopping", stopping); return current;
    }

    private static ObjectNode writeSearchCheckpoint(String checkpointPath, String searchKey, int seedIndex,
                                                     ObjectNode current, ArrayNode history, ArrayNode seedRuns,
                                                     ArrayNode seedFinalists, ObjectNode completedResult) {
        return writeSearchCheckpoint(checkpointPath, searchKey, seedIndex, current, history, seedRuns,
                seedFinalists, completedResult, -1);
    }

    private static ObjectNode writeSearchCheckpoint(String checkpointPath, String searchKey, int seedIndex,
                                                     ObjectNode current, ArrayNode history, ArrayNode seedRuns,
                                                     ArrayNode seedFinalists, ObjectNode completedResult,
                                                     int completedGeneration) {
        if (checkpointPath == null || checkpointPath.isEmpty()) return null;
        ObjectNode state = object(); state.put("search_key", searchKey); state.put("seed_index", seedIndex);
        if (current == null) state.putNull("current"); else state.set("current", current);
        state.set("all_history", history); state.set("seed_runs", seedRuns);
        state.set("seed_finalists", seedFinalists);
        if (completedResult != null) {
            state.put("generation", completedGeneration); state.set("completed_result", completedResult);
        }
        return writeGeneticCheckpoint(checkpointPath, state);
    }

    public static boolean validateGeneticRun(JsonNode run) {
        assertHash(run, V5.get("genetic"), "genetic run");
        JsonNode config = run.path("config"); ArrayNode seeds = arrayOrEmpty(run.get("seeds"));
        ArrayNode history = arrayOrEmpty(run.get("population_history"));
        if (config.path("population").asInt(0) < 2 || seeds.isEmpty()) {
            throw failure("genetic run config is incomplete");
        }
        if (history.isEmpty()) throw failure("genetic run must retain complete population history");
        Set<String> aliases = new HashSet<>();
        for (JsonNode row : history) {
            String alias = firstText(row, "behavior_alias_sha256", "behavior_sha256"); aliases.add(alias);
            if (!(row.has("chromosome") || row.has("candidate")) || text(row, "behavior_sha256").isEmpty()
                    || alias.isEmpty() || !row.path("fitness").isObject() || !row.path("generation").isInt()) {
                throw failure("genetic population history row is incomplete");
            }
        }
        if (run.path("evaluated_k").asInt(-1) != aliases.size()) {
            throw failure("genetic run evaluated behavior K does not match population history");
        }
        return true;
    }

    @FunctionalInterface
    public interface LegacyEvaluator {
        JsonNode evaluate(ObjectNode chromosome, ObjectNode context);
    }

    public static final class SearchInterruptedException extends IllegalStateException {
        private final String checkpointSha256;
        public SearchInterruptedException(String message, String checkpointSha256) {
            super(message); this.checkpointSha256 = checkpointSha256;
        }
        public String code() { return "SEARCH_INTERRUPTED"; }
        public String checkpointSha256() { return checkpointSha256; }
    }

    /** Callable-valued Node export: callers must provide the evaluator as a typed capability. */
    public static ObjectNode searchGenetic(ObjectNode args) {
        throw failure("searchGenetic requires a typed deterministic evaluator and is FIXTURE/LEGACY_EXPOSED only");
    }

    public static ObjectNode searchGenetic(ObjectNode rawArgs, LegacyEvaluator evaluator) {
        ObjectNode args = rawArgs == null ? object() : rawArgs;
        if (!bool(args, "testOnly")) {
            throw failure("searchGenetic is FIXTURE/LEGACY_EXPOSED only; pass testOnly:true or use the physical search-genetic CLI");
        }
        if (evaluator == null) throw failure("genetic search requires a deterministic training evaluator");
        ObjectNode space = normalizeGeneSpace(first(args.get("geneSpace"), args.get("space")));
        ObjectNode config = frozenGeneticConfig(objectOrEmpty(args.get("config")), space.size());
        String family = firstText(args, "hypothesisFamily");
        if (family.isEmpty()) family = firstText(args.path("config"), "hypothesisFamily");
        if (family.isEmpty()) family = "v5-family";
        String dataset = firstText(args, "datasetRootSha256");
        if (dataset.isEmpty()) dataset = hash(space); requiredHash(dataset, "dataset_root_sha256");

        String checkpointPath = firstText(args, "checkpointPath");
        ObjectNode searchLineage = object();
        putNullable(searchLineage, "precommit_sha256", firstNonEmpty(firstText(args, "precommitSha256"), text(args.path("config"), "precommit_sha256")));
        putNullable(searchLineage, "experiment_sha256", firstNonEmpty(firstText(args, "experimentSha256"), text(args.path("config"), "experiment_sha256")));
        putNullable(searchLineage, "objective_contract_sha256", firstNonEmpty(firstText(args, "objectiveContractSha256"), text(args.path("config"), "objective_contract_sha256")));
        putNullable(searchLineage, "acceptance_sha256", firstNonEmpty(firstText(args, "acceptanceSha256"), text(args.path("config"), "acceptance_sha256")));
        ObjectNode searchBinding = object(); searchBinding.set("gene_space", space); searchBinding.set("config", config);
        searchBinding.set("lineage", searchLineage); searchBinding.put("dataset_root_sha256", dataset);
        searchBinding.put("hypothesis_family", family);
        if (defined(args.get("trainingCutoff"))) searchBinding.set("training_cutoff", args.get("trainingCutoff"));
        else searchBinding.putNull("training_cutoff");
        searchBinding.putNull("exposure_head"); String searchKey = hash(searchBinding);
        ObjectNode restored = null;
        if (bool(args, "resume") && !checkpointPath.isEmpty() && Files.exists(Path.of(checkpointPath))) {
            restored = objectOrEmpty(readGeneticCheckpoint(checkpointPath).get("state"));
            if (!searchKey.equals(text(restored, "search_key"))) {
                throw failure("genetic checkpoint does not bind the requested frozen search");
            }
            if (restored.path("completed_result").isObject()) {
                return ((ObjectNode) restored.path("completed_result")).deepCopy();
            }
        }
        ArrayNode history = restored == null ? array() : arrayOrEmpty(restored.get("all_history")).deepCopy();
        ArrayNode seedRuns = restored == null ? array() : arrayOrEmpty(restored.get("seed_runs")).deepCopy();
        ArrayNode allFinalists = restored == null ? array() : arrayOrEmpty(restored.get("seed_finalists")).deepCopy();
        int populationSize = config.path("population").asInt();
        int maxGenerations = config.path("max_generations").asInt();
        int minGenerations = config.path("min_generations").asInt();
        int plateauGenerations = config.path("plateau_generations").asInt();
        String trainingCutoff = firstText(args, "trainingCutoff");
        int interruptGeneration = integer(first(args.path("config").get("interrupt_after_generation"),
                args.path("config").get("interruptAfterGeneration")), -1);
        if (interruptGeneration >= 0 && checkpointPath.isEmpty()) {
            throw failure("interrupt_after_generation requires checkpointPath");
        }
        int seedIndex = restored == null ? 0 : restored.path("seed_index").asInt(0);
        ObjectNode currentState = restored == null ? null : objectOrNull(restored.get("current"));
        while (seedIndex < config.path("seeds").size()) {
            int seed = config.path("seeds").get(seedIndex).asInt();
            LinkedHashMap<String, ObjectNode> evaluated = new LinkedHashMap<>();
            List<ObjectNode> current; XorShift random;
            int generationsCompleted; int plateau; String previousSignature; String stopping;
            if (currentState == null) {
                random = new XorShift(seed); current = new ArrayList<>();
                for (int i = 0; i < populationSize; i++) {
                    ObjectNode candidate = object();
                    for (JsonNode gene : space.path("genes")) candidate.set(text(gene, "name"), randomGene(gene, random));
                    ObjectNode prior = evaluated.get(behavior(candidate));
                    current.add(prior != null ? prior : evaluateLegacy(candidate, 0, seed, "INITIAL", array(), evaluator,
                            trainingCutoff, config.path("half_life_months").asInt(), evaluated, history));
                }
                generationsCompleted = 1; plateau = 0; previousSignature = ""; stopping = "MAX_GENERATIONS";
                currentState = geneticCurrentState(seed, generationsCompleted, current, evaluated, random,
                        previousSignature, plateau, stopping);
                writeSearchCheckpoint(checkpointPath, searchKey, seedIndex, currentState, history, seedRuns, allFinalists, null);
                if (interruptGeneration == 0) {
                    throw new SearchInterruptedException("genetic search interrupted after atomic generation checkpoint",
                            text(readGeneticCheckpoint(checkpointPath), "content_sha256"));
                }
            } else {
                for (JsonNode row : arrayOrEmpty(currentState.get("evaluated"))) {
                    ObjectNode value = objectOrEmpty(row); evaluated.put(text(value, "behavior_sha256"), value);
                }
                current = objects(arrayOrEmpty(currentState.get("population")));
                random = new XorShift((int) currentState.path("rng_state").asLong(seed));
                generationsCompleted = currentState.path("generation").asInt(1);
                plateau = currentState.path("plateau").asInt(0);
                previousSignature = text(currentState, "previous_signature");
                stopping = firstNonEmpty(text(currentState, "stopping"), "MAX_GENERATIONS");
            }
            for (int generation = generationsCompleted; generation < maxGenerations; generation++) {
                rankAndCrowd(current); List<ObjectNode> offspring = new ArrayList<>();
                while (offspring.size() < populationSize) {
                    ObjectNode left = tournament(current, random), right = tournament(current, random);
                    ObjectNode child = mutate(space, crossover(space, left.path("candidate"), right.path("candidate"),
                            random, config.path("crossover_probability").asDouble(), config.path("sbx_eta").asDouble()),
                            random, config.path("mutation_probability").isNull()
                                    ? 1d / Math.max(1, space.path("genes").size())
                                    : config.path("mutation_probability").asDouble());
                    ArrayNode parents = array().add(text(left, "behavior_sha256")).add(text(right, "behavior_sha256"));
                    String behavior = behavior(child); ObjectNode prior = evaluated.get(behavior);
                    if (prior != null) {
                        ObjectNode duplicate = prior.deepCopy(); duplicate.put("generation", generation);
                        duplicate.put("operator", "DUPLICATE_RETAINED"); duplicate.set("parent_ids", parents);
                        duplicate.put("duplicate_of", behavior); history.add(duplicate); offspring.add(prior);
                    } else offspring.add(evaluateLegacy(child, generation, seed,
                            "TOURNAMENT_SBX_UNIFORM_MUTATION", parents, evaluator, trainingCutoff,
                            config.path("half_life_months").asInt(), evaluated, history));
                }
                LinkedHashMap<String, ObjectNode> unique = new LinkedHashMap<>();
                current.forEach(row -> unique.put(text(row, "behavior_sha256"), row));
                offspring.forEach(row -> unique.put(text(row, "behavior_sha256"), row));
                current = objects(selectNsgaSurvivors(toArray(unique.values()), populationSize));
                ArrayNode signatureRows = array();
                current.forEach(row -> { ObjectNode v = object(); v.put("behavior_sha256", text(row, "behavior_sha256"));
                    v.set("objectives", row.path("fitness").path("objectives")); signatureRows.add(v); });
                String signature = hash(signatureRows); plateau = signature.equals(previousSignature) ? plateau + 1 : 0;
                previousSignature = signature; generationsCompleted = generation + 1;
                if (generationsCompleted >= minGenerations && plateau >= plateauGenerations) {
                    stopping = "NO_NEW_PARETO_SIGNATURE_FOR_PLATEAU";
                }
                currentState = geneticCurrentState(seed, generationsCompleted, current, evaluated, random,
                        previousSignature, plateau, stopping);
                ObjectNode checkpoint = writeSearchCheckpoint(checkpointPath, searchKey, seedIndex, currentState,
                        history, seedRuns, allFinalists, null);
                if (interruptGeneration >= 0 && generation >= interruptGeneration) {
                    throw new SearchInterruptedException(
                            "genetic search interrupted after atomic generation checkpoint",
                            text(checkpoint, "content_sha256"));
                }
                if (!"MAX_GENERATIONS".equals(stopping)) break;
            }
            rankAndCrowd(current); ArrayNode finalists = array();
            current.stream().filter(row -> row.path("rank").asInt(-1) == 0)
                    .sorted(Comparator.comparing(row -> text(row, "behavior_sha256"))).forEach(finalists::add);
            ObjectNode seedRun = object(); seedRun.put("seed", seed);
            seedRun.put("evaluated_k", evaluated.size()); seedRun.put("generations_completed", generationsCompleted);
            seedRun.put("stopping", stopping); ArrayNode hashes = array();
            finalists.forEach(row -> hashes.add(text(row, "behavior_sha256")));
            seedRun.set("pareto_behavior_sha256", hashes); seedRuns.add(seedRun); allFinalists.add(finalists);
            seedIndex++; currentState = null;
            writeSearchCheckpoint(checkpointPath, searchKey, seedIndex, null, history, seedRuns, allFinalists, null);
        }
        ObjectNode baseline = chromosome(space, objectOrEmpty(args.get("baseline")));
        LinkedHashMap<String, ObjectNode> confirmationDefinitions = new LinkedHashMap<>();
        for (JsonNode front : allFinalists) for (JsonNode row : front) {
            ObjectNode candidate = objectOrEmpty(row.get("candidate"));
            confirmationDefinitions.putIfAbsent(behavior(candidate), candidate);
        }
        confirmationDefinitions.putIfAbsent(behavior(baseline), baseline);
        List<ObjectNode> roots = new ArrayList<>(); roots.add(baseline);
        for (JsonNode front : allFinalists) for (JsonNode row : front) {
            ObjectNode candidate = objectOrEmpty(row.get("candidate"));
            if (roots.stream().noneMatch(existing -> behavior(existing).equals(behavior(candidate)))) roots.add(candidate);
        }
        LinkedHashMap<String, ObjectNode> neighbourDefinitions = new LinkedHashMap<>();
        for (ObjectNode root : roots) for (JsonNode neighbour : chromosomeNeighbours(space, root)) {
            neighbourDefinitions.putIfAbsent(behavior(neighbour), (ObjectNode) neighbour);
        }
        neighbourDefinitions.forEach(confirmationDefinitions::putIfAbsent);
        int directNeighbourCount = neighbourDefinitions.size();
        ArrayNode confirmations = array(); int confirmationIndex = 0; int primarySeed = config.path("seeds").get(0).asInt();
        for (ObjectNode definition : confirmationDefinitions.values()) {
            ObjectNode row = evaluateLegacy(definition, -1, primarySeed,
                    confirmationIndex++ == 0 ? "SIMPLE_BASELINE" : "DIRECT_PARAMETER_NEIGHBOUR",
                    array(), evaluator, trainingCutoff, config.path("half_life_months").asInt(),
                    new LinkedHashMap<>(), history);
            row.put("confirmation", true); confirmations.add(row);
        }
        LinkedHashMap<String, ObjectNode> aliases = new LinkedHashMap<>();
        history.forEach(row -> aliases.putIfAbsent(firstText(row, "behavior_alias_sha256", "behavior_sha256"), (ObjectNode) row));
        ArrayNode behaviours = array(); int behaviorIndex = 0;
        for (Map.Entry<String, ObjectNode> entry : aliases.entrySet()) {
            ObjectNode row = object(); row.put("behavior_sha256", entry.getKey());
            row.put("candidate_id", "v5-" + (++behaviorIndex));
            if (!trainingCutoff.isEmpty()) row.put("observed_at", trainingCutoff); else row.putNull("observed_at");
            row.put("source", text(entry.getValue(), "operator")); behaviours.add(row);
        }
        ObjectNode exposureArgs = object(); exposureArgs.set("prior", args.get("priorExposure"));
        exposureArgs.put("hypothesisFamily", family); exposureArgs.put("datasetRootSha256", dataset);
        exposureArgs.set("behaviours", behaviours); exposureArgs.put("genesis", bool(args, "genesis"));
        if (defined(args.get("exposureHeadPath"))) exposureArgs.set("headPath", args.get("exposureHeadPath"));
        exposureArgs.put("authoritative", false); ObjectNode ledger = makeExposureLedgerV5(exposureArgs);

        ObjectNode lineage = objectOrEmpty(args.get("lineage")).deepCopy();
        String precommit = lineageHash(args, "precommitSha256", "precommit_sha256", family, dataset);
        String experiment = lineageHash(args, "experimentSha256", "experiment_sha256", family, dataset);
        String objective = lineageHash(args, "objectiveContractSha256", "objective_contract_sha256", family, dataset);
        String acceptance = lineageHash(args, "acceptanceSha256", "acceptance_sha256", family, dataset);
        ArrayNode candidateInputs = array(); int candidateIndex = 0;
        for (JsonNode row : confirmations) {
            ObjectNode candidate = object(); candidate.put("candidate_id", "v5-%06d".formatted(++candidateIndex));
            candidate.set("definition", row.path("candidate"));
            JsonNode vector = at(row, "fitness", "metrics", "episode_returns");
            if (vector.isArray()) candidate.set("behavior_vector", vector); candidateInputs.add(candidate);
        }
        ObjectNode candidateArgs = object(); candidateArgs.set("geneSpace", space);
        candidateArgs.set("candidates", candidateInputs); candidateArgs.put("precommitSha256", precommit);
        candidateArgs.put("experimentSha256", experiment); candidateArgs.put("objectiveContractSha256", objective);
        candidateArgs.put("acceptanceSha256", acceptance); candidateArgs.set("lineage", lineage);
        candidateArgs.put("generator", "GENETIC"); ObjectNode candidateSet = makeCandidateSetV5(candidateArgs);

        ObjectNode run = object(); run.put("schema", V5.get("genetic")); run.put("version", 1);
        run.set("config", config); ObjectNode runLineage = lineage.deepCopy();
        runLineage.put("precommit_sha256", precommit); runLineage.put("experiment_sha256", experiment);
        runLineage.put("objective_contract_sha256", objective); runLineage.put("acceptance_sha256", acceptance);
        run.set("lineage", runLineage); run.set("gene_space", space);
        run.put("candidate_set_sha256", text(candidateSet, "content_sha256")); run.set("seeds", config.path("seeds"));
        if (trainingCutoff.isEmpty()) run.putNull("training_cutoff"); else run.put("training_cutoff", trainingCutoff);
        run.put("hypothesis_family", family); run.put("dataset_root_sha256", dataset); run.set("seed_runs", seedRuns);
        ArrayNode normalizedHistory = array();
        for (JsonNode row : history) {
            ObjectNode out = object(); out.set("chromosome", row.path("candidate"));
            out.put("behavior_sha256", text(row, "behavior_sha256"));
            out.put("behavior_alias_sha256", firstText(row, "behavior_alias_sha256", "behavior_sha256"));
            out.put("generation", row.path("generation").asInt()); out.put("seed", row.path("seed").asInt());
            out.put("operator", text(row, "operator")); out.set("parent_ids", arrayOrEmpty(row.get("parent_ids")));
            out.set("fitness", row.path("fitness"));
            if (defined(row.get("duplicate_of"))) out.set("duplicate_of", row.get("duplicate_of")); else out.putNull("duplicate_of");
            out.put("confirmation", row.path("confirmation").asBoolean(false)); normalizedHistory.add(out);
        }
        run.set("population_history", normalizedHistory); run.put("evaluated_k", aliases.size());
        Set<String> chromosomeHashes = new HashSet<>(); history.forEach(row -> chromosomeHashes.add(text(row, "behavior_sha256")));
        run.put("chromosome_evaluated_k", chromosomeHashes.size()); run.put("cumulative_k", ledger.path("cumulative_k").asInt());
        run.put("direct_neighbour_count", directNeighbourCount); run.put("confirmation_count", confirmations.size());
        Set<String> finalistAliases = new HashSet<>();
        for (JsonNode front : allFinalists) for (JsonNode row : front) {
            finalistAliases.add(firstText(row, "behavior_alias_sha256", "behavior_sha256"));
        }
        List<String> stableFinalists = finalistAliases.stream().filter(alias -> {
            int seedCount = 0;
            for (JsonNode front : allFinalists) {
                boolean present = false;
                for (JsonNode row : front) {
                    if (alias.equals(firstText(row, "behavior_alias_sha256", "behavior_sha256"))) {
                        present = true; break;
                    }
                }
                if (present) seedCount++;
            }
            return seedCount >= 2;
        }).sorted().toList();
        ObjectNode stability = object(); stability.put("stable_across_at_least_two_seeds", !stableFinalists.isEmpty());
        stability.set("behavior_sha256", strings(stableFinalists)); run.set("finalist_stability", stability);
        run.put("stopping_rule", "minimum_10_generations_then_5_generation_no_new_pareto_signature_or_20_max");
        run.put("exposure_ledger_sha256", text(ledger, "content_sha256")); run = withHash(run);
        validateGeneticRun(run);

        ObjectNode result = object(); result.set("run", run); result.set("ledger", ledger);
        result.set("candidateSet", candidateSet);
        if (!checkpointPath.isEmpty() && Files.exists(Path.of(checkpointPath))) result.set("checkpoint", readGeneticCheckpoint(checkpointPath));
        else result.putNull("checkpoint");
        List<ObjectNode> orderedConfirmations = objects(confirmations);
        orderedConfirmations.sort(Comparator.comparing(row -> text(row, "behavior_sha256")));
        result.set("finalists", toArray(orderedConfirmations)); ObjectNode best = selectBestV5(confirmations);
        if (best == null) result.putNull("best"); else result.set("best", best);
        if (!checkpointPath.isEmpty()) result.set("checkpoint", writeSearchCheckpoint(
                checkpointPath, searchKey, config.path("seeds").size(), null, history, seedRuns,
                allFinalists, result, maxGenerations));
        return result;
    }

    public static ObjectNode searchGeneticFixture(ObjectNode args, LegacyEvaluator evaluator) {
        ObjectNode value = args == null ? object() : args.deepCopy(); value.put("testOnly", true);
        return searchGenetic(value, evaluator);
    }

    /* Cumulative family exposure ledger compatibility. */

    public static ObjectNode makeExposureLedgerV5(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        String family = firstText(options, "hypothesisFamily", "hypothesis_family");
        if (family.isEmpty()) throw failure("hypothesis family is required");
        String dataset = requiredHash(firstText(options, "datasetRootSha256", "dataset_root_sha256"),
                "dataset_root_sha256");
        boolean genesis = bool(options, "genesis"); boolean authoritative = bool(options, "authoritative");
        Path head = exposureHeadPath(options, family);
        if (authoritative && head == null) {
            throw failure("authoritative exposure ledger requires an explicit persistent head path/root");
        }
        ObjectNode persisted = head == null ? null : readPersistentExposureHead(head);
        ObjectNode prior = objectOrNull(options.get("prior"));
        if (persisted != null && genesis && prior == null) {
            throw failure("competing genesis head for exposure family " + family);
        }
        if (prior == null && persisted != null) prior = persisted;
        if (head != null && persisted == null && prior != null && authoritative) {
            throw failure("authoritative exposure prior is not held by the persistent canonical head");
        }
        if (prior == null && !genesis) {
            throw failure("v5 exposure ledger requires the canonical prior head; pass genesis only for one-time initialization");
        }
        if (prior == null && genesis && head == null && GENESIS_FAMILIES.containsKey(family)) {
            throw failure("competing genesis head for exposure family " + family);
        }
        if (prior != null) {
            assertHash(prior, V5.get("exposure"), "prior v5 exposure ledger");
            if (!family.equals(text(prior, "hypothesis_family"))) throw failure("exposure ledger family mismatch");
            String expectedHead = exposurePointer(prior);
            if (!"HEAD".equals(text(prior, "status"))
                    || !expectedHead.equals(text(prior, "head_pointer_sha256"))) {
                throw failure("prior exposure ledger is not the canonical HEAD");
            }
            if (head != null && (persisted == null
                    || !text(persisted, "content_sha256").equals(text(prior, "content_sha256")))) {
                throw failure("prior exposure ledger is stale relative to persistent canonical head");
            }
            String remembered = GENESIS_FAMILIES.get(family);
            if (defined(prior.get("competing_head_sha256"))
                    || head == null && remembered != null && !remembered.equals(text(prior, "content_sha256"))) {
                throw failure("prior exposure ledger has a stale or competing head");
            }
        }
        ArrayNode priorRows = prior == null ? array() : arrayOrEmpty(prior.get("entries"));
        Set<String> priorBehaviors = new HashSet<>(); priorRows.forEach(row -> priorBehaviors.add(text(row, "behavior_sha256")));
        LinkedHashMap<String, ObjectNode> freshUnique = new LinkedHashMap<>(); int index = 0;
        for (JsonNode raw : arrayOrEmpty(first(options.get("behaviours"), options.get("behaviors")))) {
            String behaviorHash = requiredHash(text(raw, "behavior_sha256"), "behaviour[" + index + "]");
            ObjectNode row = object(); row.put("behavior_sha256", behaviorHash);
            String id = text(raw, "candidate_id"); if (id.isEmpty()) id = "candidate-" + (index + 1);
            row.put("candidate_id", id);
            if (defined(raw.get("observed_at"))) row.set("observed_at", raw.get("observed_at")); else row.putNull("observed_at");
            row.put("dataset_root_sha256", dataset);
            String source = text(raw, "source"); row.put("source", source.isEmpty() ? "SEARCH" : source);
            freshUnique.putIfAbsent(behaviorHash, row); index++;
        }
        ArrayNode all = priorRows.deepCopy(); int newK = 0;
        for (Map.Entry<String, ObjectNode> entry : freshUnique.entrySet()) if (!priorBehaviors.contains(entry.getKey())) {
            all.add(entry.getValue()); newK++;
        }
        ArrayNode entries = array(); String previous = hash("V5-GENESIS");
        for (JsonNode raw : all) {
            ObjectNode entry = objectOrEmpty(raw).deepCopy(); entry.put("sequence", entries.size() + 1);
            entry.put("previous_sha256", previous); entries.add(entry); previous = hash(entry);
        }
        Set<String> roots = new HashSet<>(); roots.add(dataset);
        for (JsonNode entry : entries) {
            String root = text(entry, "dataset_root_sha256");
            if (root.isEmpty() && prior != null) root = text(prior, "dataset_root_sha256");
            if (!root.isEmpty()) roots.add(root);
        }
        Set<String> behaviorAliases = new HashSet<>(); entries.forEach(row -> behaviorAliases.add(text(row, "behavior_sha256")));
        ObjectNode result = object(); result.put("schema", V5.get("exposure")); result.put("version", 1);
        result.put("status", "HEAD"); result.put("hypothesis_family", family);
        result.put("dataset_root_sha256", dataset); result.set("dataset_roots", strings(roots.stream().sorted().toList()));
        if (prior == null) result.putNull("prior_head_sha256"); else result.put("prior_head_sha256", text(prior, "content_sha256"));
        result.put("genesis", prior == null); result.set("entries", entries); result.put("cumulative_k", entries.size());
        result.put("new_k", newK); result.set("behavior_aliases", strings(behaviorAliases.stream().sorted().toList()));
        result.put("head_pointer_sha256", exposurePointer(family, dataset, entries)); result.putNull("competing_head_sha256");
        ObjectNode finalized = withHash(result); validateExposureLedgerV5(finalized);
        if (head != null) persistExposureHead(head, finalized, prior == null ? null : text(prior, "content_sha256"));
        GENESIS_FAMILIES.put(family, text(finalized, "content_sha256")); return finalized;
    }

    public static boolean validateExposureLedgerV5(JsonNode ledger) {
        assertHash(ledger, V5.get("exposure"), "v5 exposure ledger");
        ArrayNode entries = arrayOrEmpty(ledger.get("entries"));
        if (!"HEAD".equals(text(ledger, "status")) || !ledger.path("cumulative_k").isIntegralNumber()
                || ledger.path("cumulative_k").asInt() != entries.size()
                || !ledger.path("dataset_roots").isArray()
                || !containsText(ledger.path("dataset_roots"), text(ledger, "dataset_root_sha256"))) {
            throw failure("v5 exposure ledger K/status/root is invalid");
        }
        String previous = hash("V5-GENESIS"); Set<String> seen = new HashSet<>(); int index = 0;
        for (JsonNode row : entries) {
            if (row.path("sequence").asInt(-1) != ++index || !previous.equals(text(row, "previous_sha256"))
                    || !isHash(text(row, "behavior_sha256"))
                    || !isHash(firstNonEmpty(text(row, "dataset_root_sha256"), text(ledger, "dataset_root_sha256")))) {
                throw failure("v5 exposure ledger chain is broken");
            }
            previous = hash(row); seen.add(text(row, "behavior_sha256"));
        }
        if (!exposurePointer(ledger).equals(text(ledger, "head_pointer_sha256"))
                || defined(ledger.get("competing_head_sha256"))) {
            throw failure("v5 exposure ledger head pointer is not canonical");
        }
        Set<String> aliases = textSet(ledger.path("behavior_aliases"));
        if (!aliases.equals(seen)) throw failure("v5 exposure ledger aliases do not reconcile");
        return true;
    }

    /* Legacy five-year facade. Authoritative acquisition remains DataV5-owned. */

    public static ObjectNode makeFiveYearBackfillPlan(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        long rawEnd = defined(options.get("asOf")) ? millis(options.get("asOf")) : System.currentTimeMillis();
        int years = integer(options.get("years"), 5);
        if (years != 5) throw failure("v5 backfill is frozen to five completed years");
        long fourHours = 4L * 3_600_000L;
        long completedThrough = Math.floorDiv(rawEnd, fourHours) * fourHours;
        long end = completedThrough - fourHours;
        long start = Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).minusYears(years).toInstant().toEpochMilli();
        List<String> assets = options.path("assets").isArray() ? texts(options.path("assets")) : V5_UNIVERSE;
        List<String> selected = new ArrayList<>(); for (String value : assets) selected.add(normalizeAsset(value));
        boolean dated = !options.has("includeDatedFutures") || bool(options, "includeDatedFutures");
        ArrayNode series = array();
        for (String asset : selected) for (String instrument : V5_INSTRUMENTS) {
            if ("BINANCE_USDM_DATED_FUTURE".equals(instrument) && !dated) continue;
            series.add(makeLegacySeries(asset, instrument, "4h", start, end, completedThrough));
            if ("BINANCE_USDM_PERPETUAL".equals(instrument)) {
                series.add(makeLegacySeries(asset, instrument, "funding", start, end, completedThrough));
            }
        }
        ObjectNode window = object(); window.put("years", 5); window.put("start_at", iso(start));
        window.put("end_at", iso(end)); window.put("completed_through_at", iso(completedThrough));
        ObjectNode hydration = object(); hydration.put("status", "NOT_CAPTURED");
        hydration.put("execution_timeframe", "1m"); hydration.put("source", "opportunity-envelope");
        hydration.put("maximum_lifecycle_days", 30); hydration.put("requires_frozen_envelope", true);
        ObjectNode source = object(); source.put("venue", "BINANCE"); source.put("public_only", true);
        source.set("adapters", strings(List.of("spot_ohlcv", "usd_m_ohlcv", "funding_events",
                "contract_specs", "fee_schedule"))); source.put("no_private_queue_claims", true);
        ObjectNode coverage = object(); coverage.put("status", "NOT_DOWNLOADED");
        coverage.put("base_history_only", true); coverage.put("one_minute_hydration", "NOT_CAPTURED");
        coverage.put("unavailable_series_must_be_disclosed", true); coverage.set("gaps", array());
        coverage.set("partitions", array()); coverage.set("source_receipts", array());
        ObjectNode result = object(); result.put("schema", V5.get("manifest")); result.put("version", 1);
        result.put("status", "PLAN_ONLY"); result.put("as_of", iso(rawEnd)); result.set("window", window);
        result.set("assets", strings(selected));
        result.set("required_instruments", strings(List.of("BINANCE_SPOT", "BINANCE_USDM_PERPETUAL")));
        result.set("optional_instruments", dated ? strings(List.of("BINANCE_USDM_DATED_FUTURE")) : array());
        result.set("series", series); result.set("hydration", hydration); result.set("source", source);
        result.put("raw_data_policy", "raw_parquet_gitignored_and_never_fabricated");
        result.set("coverage", coverage); return withHash(result);
    }

    public static boolean validateFiveYearPlan(JsonNode plan) { return validateFiveYearPlan(plan, object()); }

    public static boolean validateFiveYearPlan(JsonNode plan, ObjectNode options) {
        assertHash(plan, V5.get("manifest"), "v5 data manifest");
        String status = text(plan, "status");
        if (!Set.of("PLAN_ONLY", "ACQUIRED").contains(status)) throw failure("invalid v5 manifest status");
        List<String> assets = texts(plan.path("assets")); List<String> expected = new ArrayList<>(V5_UNIVERSE);
        Collections.sort(assets); Collections.sort(expected);
        if (plan.path("window").path("years").asInt(-1) != 5 || !assets.equals(expected)) {
            throw failure("v5 manifest must cover exactly eight assets and five years");
        }
        ArrayNode series = arrayOrEmpty(plan.get("series"));
        if (series.size() < 16) throw failure("v5 manifest lacks completed-bar/PIT/dense-series contract");
        for (JsonNode row : series) {
            boolean funding = "funding_events".equals(text(row, "series_type"));
            if (row.path("completed_bars_only").asBoolean(!funding) == funding
                    || !row.path("require_availability_time").asBoolean(false)
                    || text(row, "fee_schedule").isEmpty() || text(row, "contract_specification").isEmpty()
                    || !row.path("expected_step_ms").isIntegralNumber()
                    || !row.path("expected_event_count").isIntegralNumber()) {
                throw failure("v5 manifest lacks completed-bar/PIT/dense-series contract");
            }
        }
        JsonNode coverage = plan.path("coverage");
        if ("PLAN_ONLY".equals(status)) {
            if (!Set.of("NOT_DOWNLOADED", "PARTIAL").contains(text(coverage, "status")) || plan.has("captures")) {
                throw failure("PLAN_ONLY manifest has an invalid partial/acquired contract");
            }
            if ("NOT_DOWNLOADED".equals(text(coverage, "status"))
                    && (!coverage.path("partitions").isEmpty() || !coverage.path("source_receipts").isEmpty())) {
                throw failure("PLAN_ONLY manifest cannot contain acquired artifacts");
            }
            return true;
        }
        if (!"ACQUIRED".equals(text(coverage, "status")) || text(coverage, "raw_output_root").isEmpty()
                || !coverage.path("partitions").isArray() || !coverage.path("source_receipts").isArray()
                || !coverage.path("gaps").isArray()
                || !"BOUND".equals(at(coverage, "fee_schedules", "status").asText())
                || !"BOUND".equals(at(coverage, "contract_specs", "status").asText())
                || !"BOUND".equals(at(coverage, "funding_identity", "status").asText())) {
            throw failure("ACQUIRED manifest lacks physical coverage/fee/contract/funding contract");
        }
        Path root = defined(options.get("root")) ? Path.of(options.get("root").asText())
                : Path.of(text(coverage, "raw_output_root"));
        Map<String, JsonNode> partitions = new HashMap<>();
        for (JsonNode row : coverage.path("partitions")) {
            partitions.put(text(row, "asset") + "|" + text(row, "instrument") + "|" + text(row, "interval"), row);
        }
        for (JsonNode declaration : series) if (!declaration.has("required") || declaration.path("required").asBoolean()) {
            String key = text(declaration, "asset") + "|" + text(declaration, "instrument") + "|" + text(declaration, "interval");
            JsonNode partition = partitions.get(key);
            if (partition == null) throw failure("ACQUIRED manifest missing exact required partition "
                    + text(declaration, "asset") + "/" + text(declaration, "instrument") + "/" + text(declaration, "interval"));
            validateLegacyPartition(root, partition, declaration);
        }
        for (String pair : List.of("fee_schedules|fee schedule", "contract_specs|contract specification",
                "funding_identity|funding identity")) {
            String[] parts = pair.split("\\|"); validateLegacyMetadata(root, coverage.path(parts[0]), parts[1], plan);
        }
        for (JsonNode receipt : coverage.path("source_receipts")) {
            Path path = root.resolve(text(receipt, "path")).normalize();
            if (!Files.isRegularFile(path) || !requiredHash(text(receipt, "sha256"), "source receipt").equals(fileHash(path))) {
                throw failure("ACQUIRED source receipt is missing or tampered");
            }
            try {
                JsonNode body = JSON.readTree(Files.readAllBytes(path));
                String planHash = firstText(plan, "plan_sha256", "content_sha256");
                if (!planHash.equals(text(body, "plan_sha256"))
                        || !text(plan.path("window"), "start_at").equals(at(body, "bounds", "start_at").asText())
                        || !text(plan.path("window"), "end_at").equals(at(body, "bounds", "end_at").asText())) {
                    throw failure("source receipt bounds/plan binding mismatch");
                }
            } catch (IOException error) { throw failure("ACQUIRED source receipt is not bound to the manifest: " + error.getMessage()); }
        }
        return true;
    }

    /** Fail-closed untyped boundary: live transport is an explicit capability in Java. */
    public static ObjectNode acquireFiveYearPublic(ObjectNode options) {
        throw failure("acquireFiveYearPublic requires an injected public-data transport; use the typed overload");
    }

    public static ObjectNode acquireFiveYearPublic(
            ObjectNode options,
            com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters.InjectableHttpClient transport) {
        if (transport == null) throw failure("public acquisition requires an injected transport");
        return StrategyResearchDataV5.acquireAuthoritativeStaging(options, transport);
    }

    public static ObjectNode makeOpportunityEnvelope(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        JsonNode candidateSet = options.get("candidateSet");
        if (candidateSet == null || !V5.get("candidate").equals(text(candidateSet, "schema"))) {
            throw failure("opportunity envelope requires a schema-bound v5 candidate set");
        }
        validateCandidateSetV5(candidateSet); JsonNode manifest = options.get("manifest");
        ObjectNode rootOptions = object();
        if (defined(at(manifest, "coverage", "raw_output_root"))) {
            rootOptions.set("root", at(manifest, "coverage", "raw_output_root"));
        }
        validateFiveYearPlan(manifest, rootOptions);
        if (!"ACQUIRED".equals(text(manifest, "status"))) {
            throw failure("opportunity envelope requires a physically ACQUIRED manifest");
        }
        if (bool(options, "finalistOnly")) throw failure("opportunity hydration cannot be finalist-only");
        double lifecycleDays = defined(options.get("lifecycleDays")) ? options.get("lifecycleDays").asDouble() : 30;
        if (!(lifecycleDays > 0)) throw failure("opportunity envelope lifecycle must be positive");
        ArrayNode sourceCandidates = arrayOrEmpty(candidateSet.get("candidates"));
        if (sourceCandidates.isEmpty()) throw failure("opportunity envelope requires the complete frozen candidate set");
        boolean datedUnavailable = false;
        for (JsonNode gap : at(manifest, "coverage", "gaps")) if ("BINANCE_USDM_DATED_FUTURE".equals(text(gap, "instrument"))
                && gap.has("required") && !gap.path("required").asBoolean()) datedUnavailable = true;
        if (datedUnavailable) for (JsonNode candidate : sourceCandidates) {
            String instrument = firstText(candidate.path("definition"), "instrument", "instrument_type").toUpperCase(Locale.ROOT);
            if (instrument.contains("DATED")) throw failure("candidate requires unavailable dated-future coverage");
        }
        String timeframe = firstText(options, "executionTimeframe"); if (timeframe.isEmpty()) timeframe = "1m";
        ArrayNode partitionArtifacts = arrayOrEmpty(options.get("partitionArtifacts"));
        if (partitionArtifacts.isEmpty()) throw failure("opportunity envelope requires bound physical 1m execution partitions");
        Path physicalRoot = Path.of(at(manifest, "coverage", "raw_output_root").asText());
        Map<String, List<JsonNode>> rowsByHash = new HashMap<>();
        for (JsonNode partition : partitionArtifacts) {
            String digest = firstText(partition, "sha256", "content_sha256"); String relative = text(partition, "path");
            if (!isHash(digest) || relative.isEmpty()) throw failure("opportunity envelope requires bound physical 1m execution partitions");
            Path path = physicalRoot.resolve(relative).normalize();
            if (!Files.isRegularFile(path) || !digest.equals(fileHash(path))) {
                throw failure("opportunity execution partition is missing or tampered: " + relative);
            }
            List<JsonNode> rows = jsonLines(path); Set<Long> seen = new HashSet<>();
            for (JsonNode row : rows) {
                long event = millis(first(row.get("event_time"), row.get("time"), row.get("open_time")));
                long available = millis(first(row.get("availability_time"), row.get("available_at"), row.get("close_time")));
                if (!seen.add(event) || available < event + 59_000) {
                    throw failure("opportunity execution partition exposes a 1m bar before its close");
                }
            }
            if (rows.isEmpty() || !timeframe.equals(text(partition, "interval"))) {
                throw failure("opportunity execution partition has wrong interval, duplicate times, or no rows");
            }
            rowsByHash.put(digest, rows);
        }
        Set<String> assetSet = new HashSet<>(texts(manifest.path("assets"))); ArrayNode windows = array();
        for (JsonNode feature : arrayOrEmpty(options.get("featureRows"))) {
            String asset = normalizeAsset(text(feature, "asset")); if (!assetSet.contains(asset)) continue;
            ArrayNode matches = array();
            for (JsonNode candidate : sourceCandidates) if (candidateMatches(feature, candidate.path("definition"))) {
                matches.add(text(candidate, "candidate_id"));
            }
            if (matches.isEmpty()) continue; List<String> ids = texts(matches); Collections.sort(ids);
            long start = millis(first(feature.get("decision_time"), feature.get("event_time"), feature.get("time")));
            ObjectNode key = object(); key.put("asset", asset); key.put("start", start); key.put("lifecycleDays", lifecycleDays);
            ObjectNode window = object(); window.put("window_id", hash(key)); window.put("asset", asset);
            window.put("signal_time", iso(start)); window.put("execution_start", iso(start));
            window.put("execution_end", iso(start + Math.round(lifecycleDays * 86_400_000d)));
            window.put("max_lifecycle_days", lifecycleDays); window.put("source_feature_sha256", hash(feature));
            window.set("candidate_ids", strings(ids)); windows.add(window);
        }
        sortArray(windows, Comparator.comparing(row -> text(row, "signal_time") + "|" + text(row, "asset") + "|" + text(row, "window_id")));
        if (windows.isEmpty()) throw failure("opportunity envelope has no frozen core-predicate windows");
        for (JsonNode window : windows) {
            long start = millis(window.get("execution_start")), end = millis(window.get("execution_end"));
            int expectedRows = Math.toIntExact(Math.floorDiv(end - start, 60_000) + 1); boolean covered = false;
            for (JsonNode partition : partitionArtifacts) if (text(window, "asset").equals(text(partition, "asset"))) {
                List<Long> selected = rowsByHash.get(firstText(partition, "sha256", "content_sha256")).stream()
                        .map(row -> millis(first(row.get("event_time"), row.get("time"), row.get("open_time"))))
                        .filter(value -> value >= start && value <= end).sorted().toList();
                if (selected.size() == expectedRows) {
                    covered = true; for (int i = 0; i < selected.size(); i++) if (selected.get(i) != start + i * 60_000L) covered = false;
                }
                if (covered) break;
            }
            if (!covered) throw failure("opportunity execution hydration is not a dense 1m partition for " + text(window, "window_id"));
        }
        List<String> instruments = options.path("instruments").isArray() ? texts(options.path("instruments")) : V5_INSTRUMENTS;
        instruments = instruments.stream().distinct().sorted().toList(); List<String> sortedAssets = new ArrayList<>(assetSet); Collections.sort(sortedAssets);
        ArrayNode predicateRows = array(); for (JsonNode candidate : sourceCandidates) {
            ObjectNode value = object(); value.put("candidate_id", text(candidate, "candidate_id"));
            value.set("definition", candidate.path("definition")); predicateRows.add(value);
        }
        List<String> partitionHashes = new ArrayList<>(); partitionArtifacts.forEach(row -> partitionHashes.add(firstText(row, "sha256", "content_sha256")));
        Collections.sort(partitionHashes); ObjectNode coverage = object();
        coverage.put("execution_bars", "REQUIRED_FOR_ALL_ENVELOPE_ASSETS_AND_WINDOWS");
        coverage.put("profitable_finalist_only", false); coverage.put("core_predicate", "FROZEN_SCORE_FREE_PREDICATE");
        coverage.put("partitions_span_max_lifecycle", true); coverage.put("dense_one_minute_grid", true);
        ObjectNode result = object(); result.put("schema", V5.get("envelope")); result.put("version", 1);
        result.put("manifest_sha256", text(manifest, "content_sha256"));
        result.put("candidate_set_sha256", text(candidateSet, "content_sha256"));
        result.put("candidate_predicate_sha256", hash(predicateRows)); result.put("feature_rows_sha256", hash(arrayOrEmpty(options.get("featureRows"))));
        result.set("partition_hashes", strings(partitionHashes)); result.set("frozen_at", manifest.get("as_of"));
        result.set("assets", strings(sortedAssets)); result.set("instruments", strings(instruments));
        result.put("signal_timeframe", "4h"); result.put("execution_timeframe", timeframe);
        result.put("maximum_lifecycle_days", lifecycleDays); result.put("execution_data_required", true);
        result.put("hydrated_before_outcomes", true); result.set("windows", windows); result.put("window_count", windows.size());
        result.put("window_union", "ALL_FROZEN_CANDIDATE_CORE_PREDICATES"); result.set("coverage", coverage);
        result = withHash(result); SCHEMAS.validateContractSchema(result); return result;
    }

    /* Legacy performance/statistical facade. */

    public static ArrayNode buildAuthoritativeTrades(ObjectNode options) {
        if (options == null || !bool(options, "testOnly")) {
            throw failure("buildAuthoritativeTrades is FIXTURE/LEGACY_EXPOSED only; use the physical evaluator command");
        }
        return StrategyPerformanceV5Worker.buildAuthoritativeTradesFixture(options);
    }

    public static ArrayNode buildAuthoritativeTradesFixture(ObjectNode options) {
        ObjectNode value = options == null ? object() : options.deepCopy(); value.put("testOnly", true);
        return StrategyPerformanceV5Worker.buildAuthoritativeTradesFixture(value);
    }

    public static ObjectNode marketWideEpisodeVector(ObjectNode options) {
        return StrategyPerformanceV5Worker.marketWideEpisodeVector(options);
    }

    public static ObjectNode metricsFromTrades(ArrayNode trades, JsonNode episodeReturns) {
        ObjectNode options = object(); options.set("trades", trades == null ? array() : trades);
        options.set("episodeReturns", episodeReturns == null ? object() : episodeReturns);
        return StrategyPerformanceV5Worker.metricsFromTrades(options);
    }

    public static ObjectNode metricsFromTrades(ObjectNode options) {
        return StrategyPerformanceV5Worker.metricsFromTrades(options);
    }

    private static final List<String> STRESS_NAMES = List.of(
            "DOUBLED_COSTS", "DELAYED_ENTRY", "ADVERSE_OHLC_COLLISION", "GAP", "LIQUIDITY",
            "CAPACITY", "VENUE_OUTAGE", "FUNDING", "EXPIRY", "LIQUIDATION");

    public static ObjectNode deriveStressSuiteV5(ArrayNode trades) {
        return deriveStressSuiteV5(trades, object());
    }

    public static ObjectNode deriveStressSuiteV5(ArrayNode trades, ObjectNode options) {
        if (options == null || !bool(options, "testOnly")) {
            throw failure("deriveStressSuiteV5 is FIXTURE/LEGACY_EXPOSED only; use the physical evaluator command");
        }
        return deriveStressSuiteV5Fixture(trades, options);
    }

    public static ObjectNode deriveStressSuiteV5Fixture(ArrayNode trades) {
        return deriveStressSuiteV5Fixture(trades, object());
    }

    public static ObjectNode deriveStressSuiteV5Fixture(ArrayNode trades, ObjectNode options) {
        List<String> required = options != null && options.path("requiredScenarios").isArray()
                ? texts(options.path("requiredScenarios")) : STRESS_NAMES;
        ArrayNode scenarios = array(); boolean all = true;
        for (String name : required) {
            boolean missing = trades == null || trades.isEmpty();
            List<Double> adjustedValues = new ArrayList<>();
            if (!missing) for (JsonNode trade : trades) {
                JsonNode input = trade.path("scenario_inputs").path(name);
                boolean notApplicable = stressNotApplicable(trade, name);
                boolean bound = input.isObject()
                        && hash(trade.path("scenario_inputs")).equals(text(trade, "scenario_inputs_sha256"))
                        && isHash(text(input, "source_sha256"))
                        && (notApplicable || input.path("applied").asBoolean(false)
                        && finiteNumber(input.get("debit_r")) && input.path("debit_r").asDouble() > 0);
                boolean direct = switch (name) {
                    case "DOUBLED_COSTS" -> finiteNumber(trade.get("fee_r"))
                            && finiteNumber(trade.get("slippage_r")) && finiteNumber(trade.get("funding_debit_r"));
                    case "FUNDING" -> trade.path("funding_settlements").isArray()
                            && finiteNumber(trade.get("funding_debit_r"));
                    case "LIQUIDITY", "CAPACITY" -> trade.path("notional").asDouble() > 0
                            && isHash(text(trade, "execution_sha256"));
                    default -> true;
                };
                boolean derivativeContract = true;
                if (Set.of("EXPIRY", "LIQUIDATION").contains(name)
                        && firstText(trade, "instrument_type").toUpperCase(Locale.ROOT).contains("FUTURE")) {
                    derivativeContract = isHash(text(trade, "contract_spec_sha256"))
                            && finiteNumber(trade.get("liquidation_price"));
                }
                if (!(bound && direct && derivativeContract)) { missing = true; break; }
                double debit = notApplicable ? 0 : input.path("debit_r").asDouble();
                adjustedValues.add(trade.path("net_r").asDouble() - debit);
            }
            Double adjusted = missing ? null : mean(adjustedValues); boolean pass = adjusted != null && adjusted > 0;
            ObjectNode row = object(); row.put("name", name); row.put("pass", pass);
            row.put("missing_inputs", missing); if (adjusted == null) row.putNull("adjusted_expectancy_r");
            else row.put("adjusted_expectancy_r", adjusted);
            row.put("provenance", "DERIVED_FROM_AUTHORITATIVE_TRADES"); row.put("inputs_bound", !missing);
            row.put("not_applicable_allowed", Set.of("EXPIRY", "LIQUIDATION").contains(name));
            scenarios.add(row); all &= pass;
        }
        ObjectNode result = object(); result.put("schema", "strategy-stress-result/2"); result.put("version", 1);
        if (options != null && defined(options.get("lineage"))) result.set("lineage", options.get("lineage"));
        else result.putNull("lineage"); result.set("scenarios", scenarios); result.set("required_scenarios", strings(required));
        result.put("pass", all); return withFixtureHash(result);
    }

    public static ObjectNode derivePortfolioV5(ArrayNode trades) { return derivePortfolioV5(trades, object()); }

    public static ObjectNode derivePortfolioV5(ArrayNode trades, ObjectNode options) {
        if (options == null || !bool(options, "testOnly")) {
            throw failure("derivePortfolioV5 is FIXTURE/LEGACY_EXPOSED only; use the physical portfolio stage");
        }
        return derivePortfolioV5Fixture(trades, options);
    }

    public static ObjectNode derivePortfolioV5Fixture(ArrayNode trades) {
        return derivePortfolioV5Fixture(trades, object());
    }

    public static ObjectNode derivePortfolioV5Fixture(ArrayNode trades, ObjectNode options) {
        ArrayNode marks = options == null ? array() : arrayOrEmpty(options.get("marks"));
        JsonNode lineage = options == null ? NullNode.instance : nullToNull(options.get("lineage"));
        if (marks.isEmpty()) {
            ObjectNode missing = object(); missing.put("schema", "strategy-portfolio-result/2");
            missing.put("version", 1); missing.set("lineage", lineage); missing.put("pass", false);
            missing.put("marks_bound", false); missing.put("funding_attribution_only", false);
            missing.put("reason", "MISSING_TIMESTAMP_ALIGNED_MARK_PATH"); return withFixtureHash(missing);
        }
        ArrayNode signals = array(); int index = 0;
        for (JsonNode raw : trades == null ? array() : trades) {
            ObjectNode trade = objectOrEmpty(raw).deepCopy();
            if (!defined(trade.get("entry_time")) || !defined(trade.get("exit_time"))
                    || !finiteNumber(trade.get("entry_price")) || !finiteNumber(trade.get("exit_price"))) {
                throw failure("portfolio trade " + firstNonEmpty(text(trade, "signal_id"), String.valueOf(index))
                        + " lacks exact fill prices/times");
            }
            String type = firstText(trade, "instrument_type"); if (type.isEmpty()) type = "spot";
            ObjectNode instrument = object(); instrument.put("asset_class", "crypto"); instrument.put("instrument_type", type);
            String venue = firstText(trade, "venue"); instrument.put("venue", venue.isEmpty() ? "binance" : venue);
            String asset = text(trade, "asset"), symbol = text(trade, "symbol");
            instrument.put("symbol", symbol.isEmpty() ? asset.toUpperCase(Locale.ROOT) + "USDT" : symbol);
            instrument.put("asset", asset); if (trade.path("instrument").isObject()) instrument.setAll((ObjectNode) trade.path("instrument"));
            if (text(trade, "signal_id").isEmpty()) trade.put("signal_id", "trade-" + (++index));
            trade.set("instrument", instrument); trade.put("entry_price", trade.path("entry_price").asDouble());
            trade.put("exit_price", trade.path("exit_price").asDouble()); trade.put("quantity", trade.path("quantity").asDouble());
            trade.put("notional", trade.path("notional").asDouble());
            if (!trade.has("fees")) trade.put("fees", firstNumber(trade, "fees_usd", "fees", 0));
            if (!trade.path("funding_settlements").isArray()) trade.set("funding_settlements", array());
            signals.add(trade);
        }
        ObjectNode policy = options != null && options.path("policy").isObject()
                ? (ObjectNode) options.path("policy").deepCopy() : object();
        policy.put("authoritative", true); policy.put("advanced_risk", true);
        policy.put("initial_equity", options == null ? 100_000 : number(options, "initialEquity", 100_000));
        policy.set("marks", marks); policy.put("max_mark_gap_ms",
                options == null ? 86_400_000 : (long) number(options, "maxMarkGapMs", 86_400_000));
        policy.put("require_authoritative_funding_identity", true);
        try {
            ObjectNode engine = StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(signals, policy);
            boolean marksBound = engine.path("equity_curve").isArray() && engine.path("equity_curve").size() > 1
                    && engine.path("accepted_signals").size() == signals.size()
                    && defined(engine.path("policy").get("max_mark_gap_ms")) && engine.path("failures").isEmpty();
            boolean fundingBound = !containsText(engine.path("failures"), "INVALID_FUNDING_DATA");
            ObjectNode result = object(); result.put("schema", "strategy-portfolio-result/2"); result.put("version", 1);
            result.set("lineage", lineage); result.put("pass", engine.path("pass").asBoolean(false) && marksBound && fundingBound);
            result.put("marks_bound", marksBound); result.put("timestamp_aligned", marksBound && engine.path("equity_curve").isArray());
            result.put("funding_attribution_only", fundingBound);
            result.put("account_currency", firstText(policy, "account_currency").isEmpty() ? "USDT" : text(policy, "account_currency"));
            result.set("account_currency_pnl", engine.get("net_pnl")); result.set("current_equity", engine.get("portfolio_equity"));
            result.set("engine", engine); result.set("aligned_return_increments", engine.path("equity_curve"));
            result.set("marginal_risk_contribution", engine.get("marginal_risk_contribution"));
            result.set("crypto_beta_concentration", engine.get("crypto_beta_risk_cluster"));
            return withFixtureHash(result);
        } catch (RuntimeException error) {
            ObjectNode result = object(); result.put("schema", "strategy-portfolio-result/2"); result.put("version", 1);
            result.set("lineage", lineage); result.put("pass", false); result.put("marks_bound", false);
            result.put("funding_attribution_only", false); result.put("reason", rootMessage(error));
            return withFixtureHash(result);
        }
    }

    public static ObjectNode evaluateAuthoritativeV5(ObjectNode options) {
        if (options == null || !bool(options, "testOnly")) {
            throw failure("evaluateAuthoritativeV5 is FIXTURE/LEGACY_EXPOSED only; use runAuthoritativeV5Cli with physical manifests");
        }
        return evaluateAuthoritativeV5Fixture(options);
    }

    public static ObjectNode evaluateAuthoritativeV5Fixture(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        for (String forbidden : List.of("callerMetrics", "callerTrades", "callerExecution", "callerStress",
                "callerPortfolio", "callerWfo")) if (defined(options.get(forbidden))) {
            throw failure("caller-supplied execution/metrics/stress/portfolio/WFO artifacts are not accepted by v5 authoritative evaluation");
        }
        JsonNode manifest = options.get("manifest");
        if (!V5.get("manifest").equals(text(manifest, "schema"))) {
            throw failure("v5 authoritative evaluation requires strategy-data-manifest/3");
        }
        ObjectNode rootOptions = object();
        if (defined(at(manifest, "coverage", "raw_output_root"))) rootOptions.set("root", at(manifest, "coverage", "raw_output_root"));
        validateFiveYearPlan(manifest, rootOptions);
        if (!"ACQUIRED".equals(text(manifest, "status"))) {
            throw failure("v5 authoritative evaluation requires an ACQUIRED manifest; PLAN_ONLY is not data");
        }
        JsonNode envelope = options.get("envelope");
        if (defined(envelope)) {
            assertHash(envelope, V5.get("envelope"), "opportunity envelope");
            if (!text(envelope, "manifest_sha256").equals(text(manifest, "content_sha256"))) {
                throw failure("opportunity envelope/data lineage mismatch");
            }
        }
        ArrayNode candidates = arrayOrEmpty(options.path("candidateSet").get("candidates"));
        if (candidates.isEmpty()) throw failure("authoritative v5 evaluation requires candidate set");
        ArrayNode featureRows = requiredArray(options.get("featureRows"), "featureRows");
        ArrayNode labelRows = requiredArray(options.get("labelRows"), "labelRows");
        ArrayNode executionRows = requiredArray(options.get("executionRows"), "executionRows");
        ArrayNode markRows = arrayOrEmpty(options.get("markRows")); ArrayNode candidateMetrics = array();
        ObjectNode lineage = object(); lineage.put("manifest_sha256", text(manifest, "content_sha256"));
        if (defined(envelope)) lineage.put("envelope_sha256", text(envelope, "content_sha256")); else lineage.putNull("envelope_sha256");
        lineage.put("candidate_set_sha256", text(options.path("candidateSet"), "content_sha256"));
        lineage.put("feature_rows_sha256", hash(featureRows)); lineage.put("label_rows_sha256", hash(labelRows));
        lineage.put("execution_rows_sha256", hash(executionRows)); lineage.put("mark_rows_sha256", hash(markRows));
        for (JsonNode candidate : candidates) {
            ObjectNode request = object(); request.set("featureRows", featureRows); request.set("labelRows", labelRows);
            request.set("executionRows", executionRows); request.set("candidate", candidate);
            request.put("manifestSha256", text(manifest, "content_sha256")); ArrayNode trades = buildAuthoritativeTradesFixture(request);
            ObjectNode vectorRequest = object(); vectorRequest.set("labelRows", labelRows); vectorRequest.set("trades", trades);
            ObjectNode vector = marketWideEpisodeVector(vectorRequest);
            ObjectNode stressOptions = object(); stressOptions.set("lineage", lineage);
            ObjectNode stresses = deriveStressSuiteV5Fixture(trades, stressOptions);
            ObjectNode portfolioOptions = object(); portfolioOptions.set("marks", markRows); portfolioOptions.set("lineage", lineage);
            ObjectNode portfolio = derivePortfolioV5Fixture(trades, portfolioOptions);
            ObjectNode row = object(); row.put("candidate_id", firstText(candidate, "candidate_id", "id"));
            String behaviorHash = text(candidate, "behavior_sha256");
            if (behaviorHash.isEmpty()) behaviorHash = behavior(candidate.path("definition")); row.put("behavior_sha256", behaviorHash);
            ArrayNode intents = array(); for (JsonNode trade : trades) {
                ObjectNode intent = object(); intent.put("signal_id", text(trade, "signal_id"));
                intent.put("asset", text(trade, "asset")); intent.put("decision_time", text(trade, "decision_time")); intents.add(intent);
            }
            row.set("signal_intent", intents); row.set("trades", trades); row.set("metrics", metricsFromTrades(trades, vector));
            row.set("stresses", stresses); row.set("portfolio", portfolio); candidateMetrics.add(row);
        }
        ArrayNode pipeline = strings(List.of("features", "signal_intent", "labels", "execution_fills",
                "trades", "metrics", "stresses", "portfolio", "wfo"));
        ObjectNode gate = object(); gate.put("wfo", false);
        gate.put("stress", every(candidateMetrics, row -> row.path("stresses").path("pass").asBoolean(false)));
        gate.put("portfolio", every(candidateMetrics, row -> row.path("portfolio").path("pass").asBoolean(false)));
        gate.put("all_required_stages", false);
        ObjectNode accounting = object(); accounting.put("declared_k", candidates.size());
        accounting.put("evaluated_k", candidateMetrics.size());
        Set<String> episodes = new HashSet<>(); for (JsonNode row : labelRows) episodes.add(firstNonEmpty(text(row, "episode_id"),
                text(row, "asset") + ":" + millis(first(row.get("decision_time"), row.get("event_time"), row.get("time")))));
        accounting.put("market_episode_count", episodes.size()); accounting.put("zero_episode_binding", true);
        ObjectNode run = object(); run.put("schema", V5.get("run")); run.put("version", 1);
        run.put("provenance", "AUTHORITATIVE_RECOMPUTED"); run.set("pipeline", pipeline); run.set("lineage", lineage);
        run.put("manifest_sha256", text(manifest, "content_sha256"));
        if (defined(envelope)) run.put("envelope_sha256", text(envelope, "content_sha256")); else run.putNull("envelope_sha256");
        if (defined(options.get("cutoff"))) run.put("cutoff", iso(millis(options.get("cutoff")))); else run.putNull("cutoff");
        run.put("feature_rows_sha256", hash(featureRows)); run.put("label_rows_sha256", hash(labelRows));
        run.put("execution_rows_sha256", hash(executionRows)); run.put("mark_rows_sha256", hash(markRows));
        run.set("candidate_metrics", candidateMetrics); run.set("accounting", accounting);
        ObjectNode wfo = object(); wfo.put("status", "NOT_RUN"); wfo.put("pass", false);
        wfo.put("reason", "AUTHORITATIVE_WFO_REQUIRES_FROZEN_GENE_SPACE"); run.set("wfo", wfo);
        run.set("gate_status", gate); run.put("decision", "REJECTED"); run = withFixtureHash(run);
        ObjectNode evidence = object(); evidence.put("schema", V5.get("evidence")); evidence.put("version", 1);
        evidence.put("run_sha256", text(run, "content_sha256")); evidence.put("provenance", "AUTHORITATIVE_RECOMPUTED");
        evidence.set("pipeline", pipeline); evidence.put("manifest_sha256", text(manifest, "content_sha256"));
        evidence.set("lineage", lineage); evidence.put("decision", "REJECTED"); evidence = withFixtureHash(evidence);
        ObjectNode result = object(); result.set("run", run); result.set("evidence", evidence);
        ObjectNode wfoResult = object(); wfoResult.putNull("run");
        wfoResult.put("error", "AUTHORITATIVE_WFO_REQUIRES_FROZEN_GENE_SPACE"); result.set("wfo", wfoResult);
        return result;
    }

    public static ObjectNode runNullControlsV5(JsonNode episodeReturns, ObjectNode options) {
        if (options == null || !bool(options, "testOnly")) {
            throw failure("runNullControlsV5 is FIXTURE/LEGACY_EXPOSED only; use the physical statistical null artifact");
        }
        return runNullControlsV5Fixture(episodeReturns, options);
    }

    public static ObjectNode runNullControlsV5Fixture(JsonNode episodeReturns) {
        return runNullControlsV5Fixture(episodeReturns, object());
    }

    public static ObjectNode runNullControlsV5Fixture(JsonNode episodeReturns, ObjectNode options) {
        Map<String, JsonNode> candidates = candidateReturnMap(episodeReturns); List<String> ids = new ArrayList<>(candidates.keySet());
        Collections.sort(ids); List<List<Double>> vectors = new ArrayList<>();
        for (String id : ids) vectors.add(valuesFromReturns(candidates.get(id)).stream().map(ReturnRow::value).toList());
        List<String> names = List.of("BLOCK_PERMUTED_LABELS", "SHIFTED_OUTCOMES", "RANDOM_SIGNAL", "WINNERS_CURSE");
        if (vectors.isEmpty() || vectors.stream().noneMatch(row -> !row.isEmpty())) {
            ObjectNode output = object(); output.put("pass", false); output.put("reason", "NO_EPISODE_RETURNS");
            ArrayNode tests = array(); for (String name : names) {
                ObjectNode row = object(); row.put("name", name); row.put("p_value", 1);
                row.put("pass", false); row.put("method", "NO_DATA_FAIL_CLOSED"); tests.add(row);
            }
            output.set("tests", tests); return output;
        }
        int seed = integer(options.get("seed"), 11), iterations = integer(options.get("iterations"), 200);
        double alpha = number(options, "alpha", .05); XorShift random = new XorShift(seed);
        double observed = vectors.stream().mapToDouble(row -> mean(row) == null ? Double.NEGATIVE_INFINITY : mean(row)).max().orElse(Double.NEGATIVE_INFINITY);
        List<Double> flat = vectors.stream().flatMap(Collection::stream).toList();
        int block = Math.max(1, (int) Math.ceil(Math.sqrt(vectors.stream().mapToInt(List::size).max().orElse(0))));
        Map<String, Integer> counts = new LinkedHashMap<>(); names.forEach(name -> counts.put(name, 0));
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (String name : names) {
                List<Double> source;
                if ("SHIFTED_OUTCOMES".equals(name)) { source = new ArrayList<>(flat); Collections.reverse(source); }
                else {
                    source = new ArrayList<>();
                    while (source.size() < flat.size()) {
                        int start = random.nextInt(flat.size());
                        for (int offset = 0; offset < block && source.size() < flat.size(); offset++) {
                            source.add(flat.get((start + offset) % flat.size()));
                        }
                    }
                }
                int[] cursor = {0}; List<Double> candidateMeans = new ArrayList<>();
                for (List<Double> vector : vectors) {
                    List<Double> selected = new ArrayList<>();
                    for (int i = 0; i < vector.size(); i++) selected.add("RANDOM_SIGNAL".equals(name)
                            ? source.get(random.nextInt(source.size())) : source.get(cursor[0]++ % source.size()));
                    candidateMeans.add(mean(selected));
                }
                double statistic = candidateMeans.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NEGATIVE_INFINITY);
                if (statistic >= observed) counts.put(name, counts.get(name) + 1);
            }
        }
        ArrayNode tests = array(); boolean pass = true;
        for (String name : names) {
            double p = (counts.get(name) + 1d) / (iterations + 1d); boolean rowPass = p <= alpha; pass &= rowPass;
            ObjectNode row = object(); row.put("name", name); row.put("p_value", p); row.put("pass", rowPass);
            row.put("method", switch (name) {
                case "BLOCK_PERMUTED_LABELS" -> "SHARED_TIME_BLOCK_LABEL_TO_SIGNAL_BINDING_PERMUTATION";
                case "SHIFTED_OUTCOMES" -> "OUTCOME_BLOCK_SHIFT_RELATIVE_TO_SIGNAL_TIMES";
                case "RANDOM_SIGNAL" -> "FIXED_MATRIX_RANDOM_SIGNAL_RERUN";
                default -> "SELECTION_PROCESS_WINNERS_CURSE_NULL";
            }); tests.add(row);
        }
        ObjectNode output = object(); output.put("pass", pass); output.set("tests", tests);
        output.put("iterations", iterations); output.put("seed", seed); output.put("observed_expectancy_r", mean(flat));
        output.put("selection_statistic", observed); output.put("mean_invariant_under_label_shuffle", true);
        output.put("null_rejection_alpha", alpha); return output;
    }

    public static ObjectNode runOverfitAuditV5(ObjectNode options) {
        if (options == null || !bool(options, "testOnly")) {
            throw failure("runOverfitAuditV5 is FIXTURE/LEGACY_EXPOSED only; use overfit-audit with physical artifacts");
        }
        return runOverfitAuditV5Fixture(options);
    }

    public static ObjectNode runOverfitAuditV5Fixture(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        Map<String, JsonNode> matrix = candidateReturnMap(options.get("episodeReturns"));
        List<String> ids = new ArrayList<>(matrix.keySet()); Collections.sort(ids);
        ObjectNode required = objectOrEmpty(options.get("required"));
        String selected = text(required, "selected_candidate_id"); if (selected.isEmpty()) selected = ids.isEmpty() ? "selected" : ids.getFirst();
        JsonNode selectedReturns = matrix.getOrDefault(selected, array()); List<ReturnRow> rows = valuesFromReturns(selectedReturns);
        List<Double> values = rows.stream().map(ReturnRow::value).toList(); int iterations = integer(required.get("bootstrap_iterations"), 512);
        int seed = integer(required.get("seed"), 11); List<Double> boot = deterministicBootstrap(values, iterations, seed);
        Double p20 = p20(boot), weighted = weightedP20(selectedReturns, object()); double expectancy = mean(values) == null ? 0 : mean(values);
        int searchK = integer(options.get("searchK"), Math.max(1, ids.size()));
        ObjectNode max = synchronizedCenteredMaxStatistic(matrix, integer(required.get("max_stat_iterations"), 512), seed);
        ObjectNode nulls = runNullControlsV5Fixture(options.get("episodeReturns"), object()
                .put("iterations", integer(required.get("null_iterations"), 200)).put("seed", seed));
        Long latest = rows.stream().map(ReturnRow::time).map(StrategyResearchV5::tryMillis)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        long recentCutoff = latest == null ? Long.MIN_VALUE : latest - 365L * 86_400_000L;
        List<ReturnRow> recent = latest == null ? rows : rows.stream()
                .filter(row -> { Long value = tryMillis(row.time()); return value != null && value >= recentCutoff; }).toList();
        ObjectNode recentWindow = object();
        if (latest == null) { recentWindow.putNull("cutoff"); recentWindow.putNull("latest"); }
        else { recentWindow.put("cutoff", iso(recentCutoff)); recentWindow.put("latest", iso(latest)); }
        recentWindow.put("rows", recent.size()); recentWindow.put("weighting", "UNWEIGHTED_OUTER_OOS");
        ObjectNode gates = object();
        gates.put("minimum_episodes", rows.size() >= 30 || bool(required, "sample_size_insufficient"));
        gates.put("positive_bootstrap_p20", p20 != null && p20 > 0);
        gates.put("positive_weighted_p20", weighted != null && weighted > 0);
        gates.put("positive_expectancy", expectancy > 0);
        gates.put("max_statistic", max.path("status").asText().equals("PASS")
                && max.path("p_value").asDouble(1) <= number(required, "max_statistic_p_value", .1));
        gates.put("null_controls", nulls.path("pass").asBoolean(false));
        JsonNode metrics = options.path("metrics"); gates.put("positive_outer_folds", metrics.path("positive_outer_folds").asInt() >= 3);
        gates.put("positive_years", metrics.path("positive_years").asInt() >= 2);
        gates.put("earlier_blocks", metrics.path("earlier_blocks").isArray() && !metrics.path("earlier_blocks").isEmpty());
        gates.put("connected_plateau", options.path("plateau").path("pass").asBoolean(false));
        gates.put("seed_stability", options.path("stability").path("stable_across_at_least_two_seeds").asBoolean(false));
        gates.put("stress", options.path("stresses").path("pass").asBoolean(false));
        gates.put("portfolio", options.path("portfolio").path("pass").asBoolean(false));
        boolean pass = true; for (JsonNode value : gates) pass &= value.asBoolean(false);
        ObjectNode result = object(); result.put("schema", "strategy-overfit-audit/1"); result.put("version", 1);
        result.put("selected_candidate_id", selected); result.put("sample_count", rows.size());
        if (p20 == null) result.putNull("bootstrap_p20"); else result.put("bootstrap_p20", p20);
        if (weighted == null) result.putNull("weighted_bootstrap_p20"); else result.put("weighted_bootstrap_p20", weighted);
        result.put("expectancy_r", expectancy); result.put("search_adjusted_expectancy_r", expectancy / Math.sqrt(Math.max(1, searchK)));
        result.set("max_statistic", max); result.putNull("dsr_probability"); result.putNull("dsr");
        result.putNull("pbo"); result.putNull("pbo_detail"); result.set("recent_window", recentWindow);
        Double recentP20 = p20(deterministicBootstrap(recent.stream().map(ReturnRow::value).toList(), iterations, seed));
        if (recentP20 == null) result.putNull("recent_oos_p20"); else result.put("recent_oos_p20", recentP20);
        result.set("null_controls", nulls); result.set("gates", gates); result.put("pass", pass);
        result.put("fail_closed_missing_inputs", true); result.put("provenance", "RECOMPUTED_FROM_EPISODE_BLOCKS");
        return withFixtureHash(result);
    }

    public static ArrayNode makeQuarterlyOuterFolds(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        long end = defined(options.get("endAt")) ? millis(options.get("endAt")) : System.currentTimeMillis();
        long start = defined(options.get("startAt")) ? millis(options.get("startAt"))
                : Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).minusYears(2).toInstant().toEpochMilli();
        int count = integer(options.get("count"), 8), purgeDays = integer(options.get("purgeDays"), 30),
                embargoDays = integer(options.get("embargoDays"), 7); double span = (end - start) / (double) count;
        long trainStart = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).minusYears(3).toInstant().toEpochMilli();
        ArrayNode folds = array();
        for (int index = 0; index < count; index++) {
            long testStart = (long) (start + index * span), testEnd = index == count - 1 ? end : (long) (testStart + span);
            ObjectNode fold = object(); fold.put("fold_id", "outer-" + (index + 1)); fold.put("train_start", iso(trainStart));
            fold.put("train_end", iso(testStart - purgeDays * 86_400_000L));
            fold.put("test_start", iso(testStart + embargoDays * 86_400_000L)); fold.put("test_end", iso(testEnd));
            fold.put("purge_ms", purgeDays * 86_400_000L); fold.put("embargo_ms", embargoDays * 86_400_000L);
            fold.put("selection_phase", "TRAIN_ONLY"); fold.put("recency_weighting", "TRAIN_ONLY"); folds.add(fold);
        }
        return folds;
    }

    public static ObjectNode runWfoV5(ObjectNode rawArgs) {
        ObjectNode args = rawArgs == null ? object() : rawArgs;
        if (args.has("evaluator")) throw failure("authoritative WFO rejects caller-supplied evaluators");
        String dataset = firstText(args, "datasetRootSha256", "dataset_root_sha256");
        if (dataset.isEmpty()) {
            ObjectNode input = object(); input.set("featureRows", arrayOrEmpty(args.get("featureRows")));
            input.set("labelRows", arrayOrEmpty(args.get("labelRows"))); input.set("executionRows", arrayOrEmpty(args.get("executionRows")));
            dataset = hash(input);
        }
        ObjectNode config = objectOrEmpty(args.get("config")); ObjectNode lineage = object();
        lineage.put("dataset_root_sha256", requiredHash(dataset, "dataset_root_sha256"));
        lineage.put("precommit_sha256", requiredHash(firstNonEmpty(firstText(args, "precommitSha256"),
                text(config, "precommit_sha256")), "precommitSha256"));
        lineage.put("experiment_sha256", requiredHash(firstNonEmpty(firstText(args, "experimentSha256"),
                text(config, "experiment_sha256")), "experimentSha256"));
        lineage.put("objective_contract_sha256", requiredHash(firstNonEmpty(firstText(args, "objectiveContractSha256"),
                text(config, "objective_contract_sha256")), "objectiveContractSha256"));
        lineage.put("acceptance_sha256", requiredHash(firstNonEmpty(firstText(args, "acceptanceSha256"),
                text(config, "acceptance_sha256")), "acceptanceSha256"));
        ArrayNode folds = args.path("folds").isArray() ? (ArrayNode) args.path("folds").deepCopy()
                : makeQuarterlyOuterFolds(object().set("endAt", defined(args.get("endAt")) ? args.get("endAt") : NF.textNode(iso(System.currentTimeMillis()))));
        if (folds.size() != 8) throw failure("v5 WFO requires eight quarterly outer folds");
        ArrayNode rejected = array(); for (JsonNode raw : folds) {
            ObjectNode fold = objectOrEmpty(raw).deepCopy(); fold.put("status", "REJECTED");
            fold.put("reason", "AUTHORITATIVE_BOUND_STATISTICAL_ASSEMBLY_REQUIRED");
            ObjectNode train = object(); train.put("oos_inaccessible", true); train.put("weighted_recency", "TRAIN_ONLY");
            ObjectNode test = object(); test.put("weighted_recency", false); fold.set("train", train); fold.set("test", test); rejected.add(fold);
        }
        ObjectNode overfit = object(); overfit.put("status", "NOT_RUN");
        overfit.put("reason", "AUTHORITATIVE_BOUND_STATISTICAL_ASSEMBLY_REQUIRED");
        ObjectNode gate = object(); for (String key : List.of("positive_outer_folds", "stress", "portfolio", "overfit",
                "asset_portfolio", "all_required_stages")) gate.put(key, false);
        ObjectNode run = object(); run.put("schema", V5.get("wfo")); run.put("version", 1); run.set("lineage", lineage);
        run.set("folds", rejected); run.put("fold_count", 8); run.put("cumulative_runtime_k", 0);
        run.putNull("exposure_ledger_sha256"); run.put("selection_phase", "TRAIN_ONLY");
        run.put("test_phase", "OUTER_OOS_UNWEIGHTED"); run.put("purge_ms", 30L * 86_400_000L);
        run.put("embargo_ms", 7L * 86_400_000L); run.set("oos_trades", array());
        run.put("oos_weighting", "UNWEIGHTED"); run.set("overfit_audit", overfit); run.set("gate_status", gate);
        run.put("decision", "REJECTED"); run.put("gate_pass", false); run = withHash(run);
        ObjectNode result = object(); result.set("run", run); result.putNull("exposure"); return result;
    }

    /* Prospective/deployment compatibility facade. */

    public static ObjectNode makeProspectiveRunner(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions; JsonNode reservation = options.get("reservation");
        if (!defined(reservation)) {
            String path = firstText(options, "reservationPath", "reservation_path");
            if (path.isEmpty()) throw failure("prospective runner requires a frozen reservation");
            reservation = readJson(Path.of(path));
        }
        if (!isHash(text(reservation, "content_sha256"))
                || !text(reservation, "content_sha256").equals(ownHash(reservation))) {
            throw failure("prospective reservation is not frozen/hash-valid");
        }
        String outputRoot = firstText(options, "outputRoot", "output_root");
        if (outputRoot.isEmpty()) outputRoot = "strategy-research/prospective-v5";
        ObjectNode result = object(); result.put("schema", V5.get("prospective")); result.put("version", 1);
        result.put("status", "READY_BUT_NOT_PUBLISHING");
        result.put("reservation_sha256", text(reservation, "content_sha256")); result.put("output_root", outputRoot);
        result.put("completed_bar_only", true); result.put("duplicate_policy", "reject");
        result.put("pre_freeze_policy", "reject"); result.put("secret_policy", "no_private_keys_in_repository");
        result.put("external_custody", "REQUIRED"); result.put("decision", "SHADOW"); return withHash(result);
    }

    public static ObjectNode makeDeploymentSettingsCaptureV5(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        JsonNode response = first(options.get("githubApiResponse"), options.get("github_api_response"));
        String subject = firstText(options, "oidcSubject", "oidc_subject");
        JsonNode claims = first(options.get("oidcClaims"), options.get("oidc_claims"));
        JsonNode verifiedNode = first(options.get("oidcSignatureVerified"), options.get("oidc_signature_verified"));
        Boolean verified = defined(verifiedNode) ? verifiedNode.asBoolean() : null;
        String captured = firstText(options, "capturedAt", "captured_at");
        Instant capturedAt = captured.isEmpty() ? Instant.now() : Instant.ofEpochMilli(millis(captured));
        String head = firstText(options, "evidenceBranchHeadSha256", "evidence_branch_head_sha256");
        JsonNode appNode = first(options.get("evidenceWriterAppId"), options.get("evidence_writer_app_id"));
        Long appId = defined(appNode) && appNode.canConvertToLong() ? appNode.asLong() : null;
        if (appId == null) {
            String environment = System.getenv("V5_EVIDENCE_WRITER_APP_ID");
            try { if (environment != null && !environment.isBlank()) appId = Long.parseLong(environment); }
            catch (NumberFormatException ignored) { appId = null; }
        }
        return GitHubSettingsCaptureV5.makeDeploymentSettingsCapture(
                response, subject.isEmpty() ? null : subject, claims, verified, capturedAt,
                head.isEmpty() ? null : head, appId);
    }

    public static ObjectNode makeGitHubSettingsDriftEvidenceV5(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        JsonNode currentCapture = options.get("currentCapture"), currentApi = options.get("currentApiReceipt");
        JsonNode previousCapture = options.get("previousCapture"), previousApi = options.get("previousApiReceipt");
        if (!"github-deployment-settings-capture/1".equals(text(currentCapture, "schema"))
                || !text(currentCapture, "content_sha256").equals(ownHash(currentCapture))
                || text(currentCapture, "repository").isEmpty() || !defined(currentCapture.get("repository_id"))) {
            throw failure("current GitHub settings capture is not hash-valid");
        }
        if (!"github-settings-api-receipt/1".equals(text(currentApi, "schema"))
                || !text(currentApi, "content_sha256").equals(ownHash(currentApi))
                || !text(currentApi, "repository").equals(text(currentCapture, "repository"))) {
            throw failure("current GitHub API receipt is not hash-valid or not bound to capture");
        }
        try { SCHEMAS.validateContractSchema(currentCapture); SCHEMAS.validateContractSchema(currentApi); }
        catch (RuntimeException error) { throw failure("current GitHub settings/API evidence schema is invalid"); }
        boolean hasPrevious = defined(previousCapture) || defined(previousApi);
        if (hasPrevious && (!defined(previousCapture) || !defined(previousApi))) {
            throw failure("partial GitHub settings baseline is invalid; refusing to rebaseline");
        }
        if (hasPrevious) try { SCHEMAS.validateContractSchema(previousCapture); SCHEMAS.validateContractSchema(previousApi); }
        catch (RuntimeException error) { throw failure("prior GitHub settings baseline schema is invalid"); }
        boolean previousValid = hasPrevious && text(previousCapture, "schema").equals(text(currentCapture, "schema"))
                && text(previousCapture, "content_sha256").equals(ownHash(previousCapture))
                && text(previousApi, "schema").equals(text(currentApi, "schema"))
                && text(previousApi, "content_sha256").equals(ownHash(previousApi))
                && text(previousCapture, "repository").equals(text(currentCapture, "repository"))
                && previousCapture.path("repository_id").asText().equals(currentCapture.path("repository_id").asText())
                && text(previousApi, "repository").equals(text(currentApi, "repository"));
        if (hasPrevious && !previousValid) throw failure("prior GitHub settings baseline is invalid or not repository-bound");
        String currentPolicy = hash(githubSettingsPolicy(currentCapture));
        String priorPolicy = previousValid ? hash(githubSettingsPolicy(previousCapture)) : null;
        String currentApiPolicy = hash(githubApiPolicy(currentApi));
        String priorApiPolicy = previousValid ? hash(githubApiPolicy(previousApi)) : null;
        List<String> changed = new ArrayList<>();
        if (!previousValid) changed.add("BASELINE_ESTABLISHED");
        else { if (!currentPolicy.equals(priorPolicy)) changed.add("settings_policy");
            if (!currentApiPolicy.equals(priorApiPolicy)) changed.add("api_receipt"); }
        String status = !previousValid ? "BASELINE_ESTABLISHED" : changed.isEmpty() ? "CLEAR" : "DRIFTED";
        String compared = firstText(options, "comparedAt", "compared_at");
        ObjectNode value = object(); value.put("schema", "github-settings-drift-evidence/1"); value.put("version", 1);
        value.put("repository", text(currentCapture, "repository")); value.set("repository_id", currentCapture.get("repository_id"));
        value.set("evidence_branch", nullToNull(currentCapture.get("evidence_branch"))); value.put("status", status);
        if (previousValid) value.put("previous_capture_sha256", text(previousCapture, "content_sha256")); else value.putNull("previous_capture_sha256");
        value.put("current_capture_sha256", text(currentCapture, "content_sha256"));
        if (previousValid) value.put("previous_api_receipt_sha256", text(previousApi, "content_sha256")); else value.putNull("previous_api_receipt_sha256");
        value.put("current_api_receipt_sha256", text(currentApi, "content_sha256")); value.set("changed_fields", strings(changed));
        value.put("compared_at", iso(compared.isEmpty() ? System.currentTimeMillis() : millis(compared))); return withHash(value);
    }

    public static boolean verifyGitHubSettingsDriftEvidenceV5(
            JsonNode evidence, JsonNode currentCapture, JsonNode currentApiReceipt) {
        try {
            if (!defined(evidence) || !"github-settings-drift-evidence/1".equals(text(evidence, "schema"))
                    || !text(evidence, "content_sha256").equals(ownHash(evidence))
                    || !defined(currentCapture) || !defined(currentApiReceipt)) return false;
            SCHEMAS.validateContractSchema(evidence); SCHEMAS.validateContractSchema(currentCapture);
            SCHEMAS.validateContractSchema(currentApiReceipt); String status = text(evidence, "status");
            return text(evidence, "repository").equals(text(currentCapture, "repository"))
                    && evidence.path("repository_id").asText().equals(currentCapture.path("repository_id").asText())
                    && text(evidence, "current_capture_sha256").equals(text(currentCapture, "content_sha256"))
                    && text(evidence, "current_api_receipt_sha256").equals(text(currentApiReceipt, "content_sha256"))
                    && Set.of("BASELINE_ESTABLISHED", "CLEAR").contains(status)
                    && ("BASELINE_ESTABLISHED".equals(status)
                    || isHash(text(evidence, "previous_capture_sha256"))
                    && isHash(text(evidence, "previous_api_receipt_sha256"))
                    && evidence.path("changed_fields").isEmpty());
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean verifyPhysicalActionsCustodyV5(ObjectNode options) {
        JsonNode captureEvidence = options == null ? null : options.get("captureEvidence");
        JsonNode apiEvidence = options == null ? null : options.get("apiEvidence");
        JsonNode cycleEvidence = options == null ? null : options.get("cycleEvidence");
        JsonNode attestationEvidence = options == null ? null : options.get("attestationEvidence");
        JsonNode registryEvidence = options == null ? null : options.get("registryEvidence");
        for (JsonNode evidence : List.of(orNull(captureEvidence), orNull(apiEvidence), orNull(cycleEvidence),
                orNull(attestationEvidence), orNull(registryEvidence))) {
            if (!defined(evidence) || bool(evidence, "invalid") || !evidence.path("value").isObject()
                    || !isHash(text(evidence, "byte_sha256")) || !isHash(text(evidence, "content_sha256"))) return false;
        }
        try {
            JsonNode capture = captureEvidence.path("value"), api = apiEvidence.path("value"), cycle = cycleEvidence.path("value");
            JsonNode attestation = attestationEvidence.path("value"), registry = registryEvidence.path("value");
            for (JsonNode value : List.of(capture, api, cycle, attestation, registry)) {
                if (!text(value, "content_sha256").equals(ownHash(value))) return false; SCHEMAS.validateContractSchema(value);
            }
            if (!"github-settings-api-receipt/1".equals(text(api, "schema")) || !bool(api, "verified")
                    || !api.path("blockers").isEmpty() || !"strategy-v5-authoritative-command-receipt/1".equals(text(cycle, "schema"))
                    || !"COMPLETE".equals(text(cycle, "status")) || cycle.path("details").path("active").asBoolean(true)) return false;
            for (String field : List.of("actions_secret", "actions_permissions", "writer_environment_protection",
                    "evidence_writer_secret", "rulesets")) if (!stable(capture.path(field)).equals(stable(api.path(field)))) return false;
            if (!capture.path("actions_permissions").path("verified").asBoolean(false)
                    || !capture.path("writer_environment_protection").path("verified").asBoolean(false)
                    || capture.path("writer_environment_protection").path("can_admins_bypass").asBoolean(true)
                    || !StrategyReadinessV5.environmentReviewSafe(capture.path("writer_environment_protection"))
                    || !capture.path("evidence_writer_secret").path("verified").asBoolean(false)
                    || !capture.path("rulesets").path("layered_policy_verified").asBoolean(false)
                    || !capture.path("rulesets").path("actions_bypass_app_ids").isEmpty()
                    || !"strategy-github-attestation-key-registry/1".equals(text(registry, "schema"))
                    || !"FROZEN".equals(text(registry, "status"))) return false;
            ObjectNode request = object(); request.set("attestation", attestation); request.set("capture", capture);
            request.put("bytesSha256", text(captureEvidence, "byte_sha256"));
            request.put("nowMs", millis(first(options.get("nowAt"), NF.numberNode(System.currentTimeMillis()))));
            request.put("apiReceiptSha256", text(apiEvidence, "byte_sha256"));
            request.put("cycleReceiptSha256", text(cycleEvidence, "byte_sha256")); request.set("trustedKeyRegistry", registry);
            request.put("trustedKeyRegistrySha256", text(registry, "content_sha256"));
            request.put("trustedKeyRegistryByteSha256", text(registryEvidence, "byte_sha256"));
            String pin = firstText(options, "pinnedFingerprint", "pinned_fingerprint");
            if (!pin.isEmpty()) request.put("pinnedFingerprint", pin);
            return StrategyReadinessV5.verifyActionsAttestation(request);
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean verifyReplayEvidenceV5(JsonNode evidence) {
        if (!validEvidence(evidence, true)) return false; JsonNode value = evidence.path("value");
        if (!"strategy-prospective-replay-registry/1".equals(text(value, "schema"))) return false;
        ArrayNode entries = arrayOrEmpty(value.get("entries")), refs = arrayOrEmpty(value.get("entry_refs"));
        if (!text(value, "content_sha256").equals(ownHash(value)) || !isHash(text(value, "lineage_sha256"))
                || !isHash(text(value, "head_sha256")) || !text(value, "current_head_sha256").equals(text(value, "head_sha256"))
                || refs.size() != entries.size() || value.path("sequence").asInt(-1) != entries.size() || entries.isEmpty()) return false;
        Path snapshot = Path.of(text(evidence, "path")).toAbsolutePath().normalize(), root = snapshot.getParent();
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return false;
        ObjectNode genesis = object(); genesis.put("schema", "strategy-prospective-replay-genesis/1");
        genesis.put("lineage_sha256", text(value, "lineage_sha256")); String previous = hash(genesis);
        Map<String, List<String>> actions = new HashMap<>(); int index = 0;
        for (JsonNode entry : entries) {
            List<String> prior = actions.computeIfAbsent(text(entry, "nonce"), ignored -> new ArrayList<>());
            String action = text(entry, "action"); boolean validLifecycle = "USE".equals(action)
                    ? prior.isEmpty() && isHash(text(entry, "publication_payload_sha256"))
                    : "REVOKE".equals(action) && prior.equals(List.of("USE")) && !text(entry, "key_id").isEmpty()
                    && !text(entry, "signature").isEmpty() && isHash(text(entry, "trust_root_sha256"))
                    && entry.path("trust_root_generation").isIntegralNumber();
            if (entry.path("sequence").asInt(-1) != index + 1 || !previous.equals(text(entry, "previous_head_sha256"))
                    || !isHash(text(entry, "entry_sha256"))
                    || !text(entry, "entry_sha256").equals(ownHash(entry, "entry_sha256"))
                    || text(entry, "nonce").isEmpty() || !validLifecycle) return false;
            prior.add(action); JsonNode ref = refs.get(index++); String relative = text(ref, "path");
            if (ref.path("sequence").asInt(-1) != entry.path("sequence").asInt()
                    || !text(ref, "entry_sha256").equals(text(entry, "entry_sha256"))
                    || !isHash(text(ref, "byte_sha256")) || relative.isEmpty() || Path.of(relative).isAbsolute()
                    || relative.contains("..") || relative.contains("\\")) return false;
            Path child = root.resolve(relative).normalize(); if (!child.startsWith(root) || !singleRegularFile(child)) return false;
            byte[] bytes = readBytes(child); if (!hash(bytes).equals(text(ref, "byte_sha256"))) return false;
            JsonNode reopened = parse(bytes); if (!stable(reopened).equals(stable(entry))
                    || !text(reopened, "entry_sha256").equals(text(entry, "entry_sha256"))) return false;
            previous = text(entry, "entry_sha256");
        }
        return true;
    }

    public static boolean verifyRevocationEvidenceV5(JsonNode evidence, ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions; JsonNode value = evidence == null ? null : evidence.path("value");
        JsonNode root = options.get("trustRoot"); String pin = firstText(options, "pinnedTrustRootFingerprint");
        String genesisPin = firstText(options, "pinnedTrustRootGenesisFingerprint");
        if (!verifyReplayEvidenceV5(evidence) || !defined(root) || pin.isEmpty() || genesisPin.isEmpty()) return false;
        try {
            ObjectNode verify = object(); verify.set("nowAt", first(options.get("nowAt"), NF.numberNode(System.currentTimeMillis())));
            verify.put("pinnedFingerprint", pin); verify.put("pinnedGenesisFingerprint", genesisPin);
            if (defined(options.get("previousTrustRoot"))) verify.set("previousRoot", options.get("previousTrustRoot"));
            StrategyProspectiveV5.verifyTrustRoot(root, verify);
            for (JsonNode entry : value.path("entries")) if ("REVOKE".equals(text(entry, "action"))
                    && text(entry, "trust_root_sha256").equals(text(root, "content_sha256"))
                    && entry.path("trust_root_generation").asInt(-1) == root.path("generation").asInt()) {
                for (JsonNode delegated : root.path("delegations")) if ("revocation".equals(text(delegated, "role"))
                        && text(delegated, "key_id").equals(text(entry, "key_id"))
                        && !containsText(root.path("revoked_key_ids"), text(delegated, "key_id"))) {
                    ObjectNode payload = object(); for (String field : List.of("nonce", "action", "reason", "revoked_at",
                            "trust_root_sha256", "trust_root_generation")) payload.set(field, entry.get(field));
                    if (StrategyProspectiveV5.verifyPayload(payload, text(entry, "signature"), text(delegated, "public_key_pem"))) return true;
                }
            }
            return false;
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean verifyLeaseEvidenceV5(JsonNode evidence, JsonNode nowAt, ObjectNode rawOptions) {
        if (!validEvidence(evidence, false)) return false; ObjectNode options = rawOptions == null ? object() : rawOptions;
        JsonNode value = evidence.path("value"); Long expires = tryMillis(first(value.get("lease_expires_at"), value.get("expires_at")));
        Long current = tryMillis(nowAt); if (expires == null || current == null) return false;
        long lease = expires - current; if (lease <= 0 || lease > 90L * 86_400_000L) return false;
        if (!"strategy-prospective-signed-evidence/2".equals(text(value, "schema"))) return false;
        String ledger = firstText(options, "ledgerPath"), replay = firstText(options, "replayPath");
        if (ledger.isEmpty() || replay.isEmpty() || !defined(options.get("trustRoot"))
                || firstText(options, "pinnedTrustRootFingerprint").isEmpty()
                || firstText(options, "pinnedTrustRootGenesisFingerprint").isEmpty()) return false;
        try {
            ObjectNode verify = options.deepCopy(); verify.put("ledgerPath", ledger); verify.put("replayPath", replay);
            verify.set("nowAt", nowAt); StrategyProspectiveV5.verifyProspectivePublication((ObjectNode) value, verify); return true;
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean verifyActionsOnlySecretEvidenceV5(
            JsonNode evidence, JsonNode capture, JsonNode attestationEvidence,
            JsonNode registryEvidence, JsonNode apiEvidence) {
        if (!validEvidence(evidence, false) || !defined(capture)) return false; JsonNode value = evidence.path("value");
        if (!"strategy-actions-only-secret-evidence/1".equals(text(value, "schema"))
                || !text(value, "content_sha256").equals(ownHash(value))) return false;
        try { SCHEMAS.validateContractSchema(value); } catch (RuntimeException ignored) { return false; }
        JsonNode secret = capture.path("actions_secret"), apiSecret = apiEvidence == null ? null : apiEvidence.path("value").path("actions_secret");
        return "BOUND".equals(text(value, "status")) && "ACTIONS_ATTESTATION_ONLY".equals(text(value, "scope"))
                && text(value, "repository").equals(text(capture, "repository"))
                && value.path("repository_id").asText().equals(capture.path("repository_id").asText())
                && "prospective-v5".equals(text(value, "environment")) && secret.path("verified").asBoolean(false)
                && "github-settings-api-receipt/1".equals(apiEvidence == null ? "" : text(apiEvidence.path("value"), "schema"))
                && text(apiEvidence, "content_sha256").equals(text(value, "api_receipt_sha256"))
                && text(apiEvidence, "content_sha256").equals(text(apiEvidence.path("value"), "content_sha256"))
                && apiSecret != null && apiSecret.path("verified").asBoolean(false) && stable(apiSecret).equals(stable(secret))
                && text(value, "secret_name").equals(text(secret, "name"))
                && sameFields(value, secret, Map.of(
                    "environment_secret_status", "environment_status",
                    "environment_secret_body_sha256", "environment_body_sha256",
                    "repository_secret_status", "repository_status",
                    "repository_secret_body_sha256", "repository_body_sha256",
                    "organization_secret_status", "organization_status",
                    "organization_secret_body_sha256", "organization_body_sha256"))
                && text(value, "settings_capture_sha256").equals(text(capture, "content_sha256"))
                && text(value, "attestation_sha256").equals(attestationEvidence == null ? "" : text(attestationEvidence, "content_sha256"))
                && text(value, "registry_sha256").equals(registryEvidence == null ? "" : text(registryEvidence, "content_sha256"))
                && !value.has("secret_value") && !value.has("private_key");
    }

    public static boolean verifyActionsOnlySecretEvidenceV5(JsonNode evidence, JsonNode capture) {
        return verifyActionsOnlySecretEvidenceV5(evidence, capture, null, null, null);
    }

    public static ObjectNode makeDeploymentAuditV5() { return makeDeploymentAuditV5(object()); }

    public static ObjectNode makeDeploymentAuditV5(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions;
        ObjectNode settings = objectOrEmpty(options.get("settings")); ObjectNode keys = objectOrEmpty(options.get("keys"));
        JsonNode suppliedCapture = options.get("settingsCapture");
        JsonNode captureInline = defined(suppliedCapture)
                && text(suppliedCapture, "content_sha256").equals(ownHash(suppliedCapture)) ? suppliedCapture : null;
        ObjectNode captureEvidence = deploymentArtifact(settings, List.of(
                "settings_capture", "settings_capture_artifact", "github_settings_capture", "settings_capture_path"));
        if (captureEvidence == null && defined(suppliedCapture) && defined(suppliedCapture.get("path"))) {
            captureEvidence = readDeploymentEvidence(suppliedCapture);
        }
        JsonNode capture = captureEvidence != null ? captureEvidence.path("value") : captureInline;
        boolean captureVerified = defined(capture) && bool(capture, "verified");
        JsonNode trustRoot = options.get("trustRoot"); String pin = firstText(options, "pinnedTrustRootFingerprint");
        String genesisPin = firstText(options, "pinnedTrustRootGenesisFingerprint");
        JsonNode previousRoot = options.get("previousTrustRoot");
        JsonNode nowAt = first(options.get("nowAt"), NF.textNode(iso(System.currentTimeMillis())));
        boolean trustVerified = verifyOfflineTrustRoot(trustRoot, pin, genesisPin, previousRoot, nowAt);
        PublicKey assetKey = publicEd25519(firstText(keys, "asset_public_key_pem"));
        PublicKey portfolioKey = publicEd25519(firstText(keys, "portfolio_public_key_pem"));
        ArrayNode approvals = options.path("approvals").isArray() ? (ArrayNode) options.path("approvals")
                : options.path("approvals").path("approvals").isArray()
                ? (ArrayNode) options.path("approvals").path("approvals") : array();
        ArrayNode boundApprovals = array();
        for (JsonNode approval : approvals) if (approvalBoundToRoot(approval, trustRoot, trustVerified)) boundApprovals.add(approval);
        Set<String> roles = new HashSet<>(), keyIds = new HashSet<>();
        boundApprovals.forEach(row -> { roles.add(text(row, "role")); keyIds.add(text(row, "key_id")); });
        boolean distinctApprovals = boundApprovals.size() >= 2 && roles.size() == 2 && keyIds.size() == boundApprovals.size();
        ObjectNode secret = deploymentArtifact(settings, List.of(
                "actions_only_secret_artifact", "actions_only_secret_receipt", "secret_receipt", "actions_secret"));
        ObjectNode replay = deploymentArtifact(settings, List.of("replay_artifact", "replay_registry", "replay_receipt"));
        ObjectNode revocation = deploymentArtifact(settings, List.of("revocation_artifact", "revocation_list", "revocation_registry"));
        ObjectNode lease = deploymentArtifact(settings, List.of("lease_artifact", "lease_receipt", "prospective_evidence"));
        ObjectNode api = deploymentArtifact(settings, List.of("github_api_receipt", "settings_api_receipt", "api_receipt", "github_settings_receipt"));
        ObjectNode cycle = deploymentArtifact(settings, List.of("cycle_receipt", "completed_bar_receipt", "shadow_cycle_receipt"));
        ObjectNode attestation = deploymentArtifact(settings, List.of("github_attestation", "actions_attestation", "attestation", "actions_attestation_artifact"));
        ObjectNode registry = deploymentArtifact(settings, List.of("attestation_key_registry", "github_attestation_key_registry", "trusted_key_registry"));
        ObjectNode drift = deploymentArtifact(settings, List.of("github_settings_drift", "github_settings_drift_evidence", "settings_drift"));
        ObjectNode custody = object(); if (captureEvidence != null) custody.set("captureEvidence", captureEvidence);
        if (api != null) custody.set("apiEvidence", api); if (cycle != null) custody.set("cycleEvidence", cycle);
        if (attestation != null) custody.set("attestationEvidence", attestation); if (registry != null) custody.set("registryEvidence", registry);
        custody.set("nowAt", nowAt); String fingerprint = firstNonEmpty(firstText(keys, "github_attestation_public_key_fingerprint"),
                firstText(keys, "attestation_public_key_fingerprint")); if (!fingerprint.isEmpty()) custody.put("pinnedFingerprint", fingerprint);
        boolean actionsCustody = verifyPhysicalActionsCustodyV5(custody);
        boolean driftVerified = drift != null && verifyGitHubSettingsDriftEvidenceV5(
                drift.path("value"), capture, api == null ? null : api.path("value"));
        boolean shadow = captureVerified && actionsCustody && cycle != null
                && "strategy-v5-authoritative-command-receipt/1".equals(text(cycle.path("value"), "schema"))
                && "COMPLETE".equals(text(cycle.path("value"), "status"))
                && !cycle.path("value").path("details").path("active").asBoolean(true) && driftVerified;
        ObjectNode checks = object();
        checks.put("repository_private", captureVerified && bool(capture, "repository_visibility_verified")
                && Set.of("PUBLIC", "PRIVATE").contains(text(capture, "repository_visibility")));
        checks.put("append_only_branch_protected", captureVerified && capture.path("branch_protection").path("verified").asBoolean(false)
                && !capture.path("branch_protection").path("allow_force_pushes").asBoolean(true)
                && !capture.path("branch_protection").path("allow_deletions").asBoolean(true));
        checks.put("prospective_environment_protected", captureVerified
                && capture.path("environment_protection").path("verified").asBoolean(false)
                && StrategyReadinessV5.environmentReviewSafe(capture.path("environment_protection")));
        checks.put("oidc_subject_restricted", captureVerified && bool(capture, "oidc_subject_restricted")
                && "https://token.actions.githubusercontent.com".equals(at(capture, "oidc_claims", "iss").asText()));
        checks.put("actions_only_secret", actionsCustody
                && verifyActionsOnlySecretEvidenceV5(secret, capture, attestation, registry, api));
        checks.put("github_settings_drift", actionsCustody && driftVerified);
        checks.put("offline_trust_root_verified", trustVerified); checks.put("asset_key_present", assetKey != null);
        checks.put("portfolio_key_present", portfolioKey != null); checks.put("activation_root_verified", trustVerified);
        checks.put("distinct_approval_roles", distinctApprovals
                && containsApproval(boundApprovals, "ASSET", firstText(keys, "asset_public_key_pem"))
                && containsApproval(boundApprovals, "PORTFOLIO", firstText(keys, "portfolio_public_key_pem")));
        checks.put("replay_protection", actionsCustody && verifyReplayEvidenceV5(replay));
        ObjectNode revocationOptions = object(); revocationOptions.set("trustRoot", nullToNull(trustRoot));
        revocationOptions.put("pinnedTrustRootFingerprint", pin); revocationOptions.put("pinnedTrustRootGenesisFingerprint", genesisPin);
        revocationOptions.set("previousTrustRoot", nullToNull(previousRoot)); revocationOptions.set("nowAt", nowAt);
        checks.put("revocation_list", actionsCustody && verifyRevocationEvidenceV5(revocation, revocationOptions));
        String ledgerPath = firstNonEmpty(firstText(settings, "ledger_path", "prospective_ledger_path"),
                firstText(settings.path("artifacts"), "ledger_path"));
        String replayPath = firstNonEmpty(firstText(settings, "replay_path", "prospective_replay_path"),
                firstText(settings.path("artifacts"), "replay_path"));
        ObjectNode leaseOptions = revocationOptions.deepCopy(); leaseOptions.put("ledgerPath", ledgerPath);
        leaseOptions.put("replayPath", replayPath); leaseOptions.set("evidencePaths", settings.path("evidence_paths"));
        checks.put("lease_enforced", actionsCustody && verifyLeaseEvidenceV5(lease, nowAt, leaseOptions));
        checks.put("shadow_append_eligible", shadow);
        List<String> failed = new ArrayList<>(); checks.fields().forEachRemaining(entry -> {
            if (!entry.getValue().asBoolean(false)) failed.add(entry.getKey());
        }); boolean activation = failed.isEmpty();
        ObjectNode result = object(); result.put("schema", V5.get("deployment")); result.put("version", 1);
        if (defined(capture)) result.put("settings_capture_sha256", text(capture, "content_sha256")); else result.putNull("settings_capture_sha256");
        result.set("checks", checks); result.put("shadow_append_eligible", shadow); result.put("activation_eligible", activation);
        result.put("blocked", !activation); result.put("reason", activation
                ? "deployment audit passed; external activation remains separately authorized"
                : "deployment audit blocked: " + String.join(", ", failed));
        result.set("exact_external_verification_required", strings(List.of(
                "GitHub branch/environment protection settings captured by API",
                "OIDC workflow subject restriction", "Actions-only secret physical receipt",
                "public approval keys", "offline activation trust root", "physical replay registry",
                "physical revocation registry", "bounded signed lease evidence")));
        result.put("blocked_until_external_prerequisites", !activation); result.putNull("content_sha256");
        return withHash(result);
    }

    public static ObjectNode makeProspectivePublicationV5(ObjectNode rawOptions) {
        ObjectNode options = rawOptions == null ? object() : rawOptions; JsonNode completed = options.get("completedBar");
        long sequence = options.path("sequence").asLong(Long.MIN_VALUE), last = options.path("lastSequence").asLong(0);
        JsonNode asset = options.get("assetApproval"), portfolio = options.get("portfolioApproval");
        if (!defined(completed) || !bool(completed, "completed_bar") || !isHash(text(completed, "content_sha256"))) {
            throw failure("prospective publication requires a completed-bar attestation");
        }
        if (!options.path("sequence").isIntegralNumber() || sequence <= last) {
            throw failure("prospective publication sequence is invalid or replayed");
        }
        if (!defined(asset) || text(asset, "signature").isEmpty() || !"ASSET".equals(text(asset, "role"))) {
            throw failure("distinct asset approval signature is required");
        }
        if (!defined(portfolio) || text(portfolio, "signature").isEmpty() || !"PORTFOLIO".equals(text(portfolio, "role"))) {
            throw failure("distinct portfolio approval signature is required");
        }
        if (text(asset, "key_id").equals(text(portfolio, "key_id"))) {
            throw failure("asset and portfolio approval keys must be distinct");
        }
        String assetPem = text(asset, "public_key_pem"), portfolioPem = text(portfolio, "public_key_pem");
        if (assetPem.isEmpty() || portfolioPem.isEmpty()) {
            throw failure("prospective approvals require public Ed25519 keys; verified booleans are not sufficient");
        }
        if (hash(assetPem).equals(hash(portfolioPem))) {
            throw failure("asset and portfolio approval keys must be cryptographically distinct");
        }
        String nonce = firstText(options, "replayNonce", "replay_nonce");
        if (nonce.length() < 16 || containsText(options.path("revokedNonces"), nonce)) {
            throw failure("prospective publication replay/revocation check failed");
        }
        long now = defined(options.get("nowAt")) ? millis(options.get("nowAt")) : System.currentTimeMillis();
        long expiry = millis(first(options.get("leaseExpiresAt"), options.get("lease_expires_at")));
        long lease = expiry - now; if (lease <= 0 || lease > 90L * 86_400_000L) {
            throw failure("prospective publication lease must be positive and no longer than 90 days");
        }
        String issuedAt = iso(now); ObjectNode payload = object();
        payload.put("completed_bar_sha256", text(completed, "content_sha256")); payload.put("sequence", sequence);
        payload.put("issued_at", issuedAt); String canonical = stable(payload);
        verifyEd25519Approval(asset, assetPem, canonical); verifyEd25519Approval(portfolio, portfolioPem, canonical);
        ObjectNode value = object(); value.put("schema", V5.get("publication")); value.put("version", 1);
        value.put("completed_bar_sha256", text(completed, "content_sha256")); value.put("sequence", sequence);
        value.set("asset_approval", asset); value.set("portfolio_approval", portfolio); value.put("replay_nonce", nonce);
        value.put("issued_at", issuedAt); value.put("lease_expires_at", iso(expiry)); value = withHash(value);
        SCHEMAS.validateContractSchema(value); return value;
    }

    /* Aggregate validation/index and authoritative command hand-off. */

    public static boolean validateV5Artifact(JsonNode value) {
        Set<String> supported = new HashSet<>(V5.values());
        supported.addAll(List.of("strategy-overfit-audit/1", "strategy-gene-space/1",
                "strategy-stress-result/2", "strategy-portfolio-result/2",
                "github-deployment-settings-capture/1", "strategy-v5-feature-dag/1",
                "strategy-v5-feature-plan/1", "strategy-v5-trade-lifecycle/1",
                "strategy-v5-lifecycle-trust/1", "strategy-v5-execution-partition-set/1",
                "strategy-v5-opportunity-domain/1", "strategy-v5-opportunity-envelope/2",
                "strategy-v5-opportunity-hydration/2",
                "strategy-v5-statistical-behavior-definition-registry/1",
                "strategy-v5-statistical-wfo/1"));
        String schema = text(value, "schema");
        boolean statistical = schema.startsWith("strategy-v5-statistical-");
        if (schema.isEmpty() || !supported.contains(schema) && !statistical) {
            throw failure("unsupported v5 schema " + (schema.isEmpty() ? "?" : schema));
        }
        if ("FIXTURE/LEGACY_EXPOSED".equals(text(value, "provenance"))) {
            throw failure("fixture/legacy artifact cannot enter the authoritative v5 registry");
        }
        Set<String> primitive = Set.of("strategy-v5-feature-dag/1", "strategy-v5-trade-lifecycle/1",
                "strategy-v5-lifecycle-trust/1", "strategy-v5-execution-partition-set/1",
                "strategy-v5-opportunity-domain/1", "strategy-v5-opportunity-envelope/2",
                "strategy-v5-opportunity-hydration/2");
        if (primitive.contains(schema) && bool(value, "fixture_only")) {
            throw failure(schema + " fixture artifact cannot enter the authoritative v5 registry");
        }
        if ("strategy-v5-execution-partition-set/1".equals(schema)) {
            if (!value.path("partitions").isArray()) {
                throw failure("authoritative execution partition set requires path-backed physical partitions");
            }
            for (JsonNode row : value.path("partitions")) if (row.has("body") || row.has("rows")
                    || text(row, "path").isEmpty()) {
                throw failure("authoritative execution partition set requires path-backed physical partitions");
            }
        }
        if ("strategy-v5-lifecycle-trust/1".equals(schema)
                && (!"AUTHORITATIVE".equals(text(value, "provenance")) || bool(value, "fixture_only")
                || !at(value, "receipts", "contract_spec").isObject()
                || !at(value, "receipts", "execution_model").isObject()
                || !at(value, "receipts", "capacity").isObject()
                || !at(value, "receipts", "bars").isObject())) {
            throw failure("authoritative lifecycle trust artifact is incomplete");
        }
        if (statistical) {
            StrategyStatisticalV5.validateContractSchema(value);
        } else SCHEMAS.validateContractSchema(value);
        assertHash(value, schema, schema);
        if ("strategy-v5-opportunity-domain/1".equals(schema)) {
            if (!"AUTHORITATIVE".equals(text(value, "provenance")) || bool(value, "fixture_only")
                    || !bool(value, "domain_complete") || value.path("branch_count").asInt(-1) != value.path("branches").size()
                    || value.path("branches").isEmpty()) throw failure("authoritative opportunity domain is incomplete");
            Set<String> branches = new HashSet<>(); for (JsonNode branch : value.path("branches")) {
                if (text(branch, "branch_id").isEmpty() || !branches.add(text(branch, "branch_id"))
                        || !branch.path("predicate").isObject()) throw failure("authoritative opportunity domain branch is invalid");
            }
        }
        if ("strategy-v5-opportunity-envelope/2".equals(schema)) {
            if (!"AUTHORITATIVE".equals(text(value, "provenance")) || bool(value, "fixture_only")
                    || !defined(value.get("opportunity_domain_sha256")) || value.path("windows").isEmpty()) {
                throw failure("authoritative opportunity envelope is incomplete");
            }
            for (JsonNode window : value.path("windows")) {
                if (millis(window.get("execution_start")) != millis(window.get("decision_time"))
                        || millis(window.get("execution_end")) != millis(window.get("entry_time"))
                        + value.path("max_lifecycle_ms").asLong()) {
                    throw failure("authoritative opportunity envelope window boundary is invalid");
                }
            }
        }
        if ("strategy-v5-opportunity-hydration/2".equals(schema)) {
            if (!"AUTHORITATIVE".equals(text(value, "provenance")) || bool(value, "fixture_only")
                    || value.path("windows").isEmpty()) throw failure("authoritative opportunity hydration is incomplete");
            for (JsonNode window : value.path("windows")) if (!"COMPLETE".equals(text(window, "lifecycle_status"))
                    || !bool(window, "eligible")) throw failure("authoritative opportunity hydration is incomplete");
        }
        if (V5.get("exposure").equals(schema)) validateExposureLedgerV5(value);
        if (V5.get("genetic").equals(schema)) {
            validateGeneticRun(value); for (String field : List.of("precommit_sha256", "experiment_sha256",
                    "objective_contract_sha256", "acceptance_sha256")) if (!isHash(at(value, "lineage", field).asText())) {
                throw failure("genetic run lineage is incomplete");
            }
        }
        if (V5.get("candidate").equals(schema)) validateCandidateSetV5(value);
        if (V5.get("manifest").equals(schema)) validateFiveYearPlan(value);
        if (V5.get("run").equals(schema) && !"REJECTED".equals(text(value, "decision"))
                && !at(value, "gate_status", "all_required_stages").asBoolean(false)) {
            throw failure("research run decision/gate invariant failed");
        }
        if (V5.get("evidence").equals(schema) && defined(at(value, "wfo", "decision"))
                && !text(value, "decision").equals(at(value, "wfo", "decision").asText())) {
            throw failure("evidence decision/WFO invariant failed");
        }
        return true;
    }

    @FunctionalInterface public interface LegacyArtifactValidator { boolean validate(JsonNode value); }

    public static boolean validateV5Artifact(JsonNode value, LegacyArtifactValidator legacyValidator) {
        try { return validateV5Artifact(value); }
        catch (IllegalArgumentException error) {
            if (legacyValidator != null && text(error.getMessage()).startsWith("unsupported v5 schema")) {
                return legacyValidator.validate(value);
            }
            throw error;
        }
    }

    public static ObjectNode indexV5Records() { return indexV5Records(Path.of("strategy-research/v5-records")); }
    public static ObjectNode indexV5Records(String root) { return indexV5Records(Path.of(root)); }

    public static ObjectNode indexV5Records(Path rawRoot) {
        Path root = rawRoot.toAbsolutePath().normalize(); PublicationInventory publication = publicationInventory(root);
        ArrayNode records = array(); if (Files.exists(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                    String relative = portable(root.relativize(path));
                    if (!relative.endsWith(".json") || publicationControlPath(relative)
                            || relative.endsWith("behavior-definition-registry-head.json")) return;
                    JsonNode value = readJson(path);
                    if (publication.owned().contains(path) && !publication.committed().contains(path)) return;
                    if (("strategy-v5-statistical-wfo/1".equals(text(value, "schema"))
                            || V5.get("run").equals(text(value, "schema")))
                            && !publication.committed().contains(path)) return;
                    validateV5Artifact(value); ObjectNode row = object(); row.put("schema", text(value, "schema"));
                    row.put("content_sha256", text(value, "content_sha256")); row.put("path", relative);
                    String decision = firstText(value, "decision", "status");
                    if (decision.isEmpty()) row.putNull("decision"); else row.put("decision", decision); records.add(row);
                });
            } catch (IOException error) { throw failure(error.getMessage()); }
        }
        sortArray(records, Comparator.comparing(row -> text(row, "schema") + ":" + text(row, "content_sha256")));
        ObjectNode result = object(); result.put("schema", V5.get("index")); result.put("version", 1);
        result.set("records", records); return withHash(result);
    }

    @FunctionalInterface public interface LegacyIndexCallback { JsonNode index(Path root); }
    public record LegacyCallbacks(LegacyArtifactValidator validate, LegacyIndexCallback index) {}

    public static JsonNode runAuthoritativeV5Cli(String command, ObjectNode options, LegacyCallbacks callbacks) {
        String normalized = command == null ? "" : command; ObjectNode args = options == null ? object() : options;
        if ("validate".equals(normalized) && callbacks != null && callbacks.validate() != null) {
            injectLegacyValidation(args, callbacks.validate());
        } else if ("index".equals(normalized) && callbacks != null) {
            injectLegacyIndexCallbacks(args, callbacks);
        }
        JsonNode authoritative = StrategyResearchAuthoritativeV5.runAuthoritativeV5Cli(normalized, args);
        if (authoritative != null) return authoritative;
        if ("deployment-audit".equals(normalized)) return runDeploymentAuditCommand(args);
        return null;
    }

    public static JsonNode runAuthoritativeV5Cli(String command, ObjectNode options) {
        return runAuthoritativeV5Cli(command, options, null);
    }

    private static void injectLegacyValidation(ObjectNode options, LegacyArtifactValidator validator) {
        String input = text(options, "input");
        if (input.isEmpty()) return; // the authoritative owner emits the canonical missing-input failure
        Path path = Path.of(input).toAbsolutePath().normalize();
        JsonNode value = readPhysicalJson(path, "artifact to validate");
        validateLegacyCallback(value, validator);
    }

    private static void injectLegacyIndexCallbacks(ObjectNode options, LegacyCallbacks callbacks) {
        Path root = Path.of(firstNonEmpty(text(options, "root"), "strategy-research/v5-records"))
                .toAbsolutePath().normalize();
        Path output = Path.of(firstNonEmpty(text(options, "out"), root.resolve("index.json").toString()))
                .toAbsolutePath().normalize();
        PublicationInventory publication = publicationInventory(root);
        if (callbacks.validate() != null && Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.walk(root)) {
                for (Path path : stream.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList()) {
                    String relative = portable(root.relativize(path));
                    if (!relative.endsWith(".json") || path.toAbsolutePath().normalize().equals(output)
                            || relative.split("/").length > 0 && List.of(relative.split("/")).contains("receipts")
                            || publicationControlPath(relative)) continue;
                    JsonNode value = readPhysicalJson(path, "indexed artifact");
                    Path normalized = path.toAbsolutePath().normalize();
                    if ((publication.owned().contains(normalized) && !publication.committed().contains(normalized))
                            || (publicationArtifact(value) && !publication.committed().contains(normalized))
                            || text(value, "schema").startsWith("strategy-research-index/")) continue;
                    validateLegacyCallback(value, callbacks.validate());
                }
            } catch (IOException error) { throw failure(error.getMessage()); }
        }
        if (callbacks.index() != null) {
            try { callbacks.index().index(root); }
            catch (RuntimeException error) {
                if (!rootMessage(error).contains("unsupported v5 schema")) throw error;
            }
        }
    }

    private static void validateLegacyCallback(JsonNode value, LegacyArtifactValidator validator) {
        String schema = text(value, "schema");
        if (!SCHEMAS.hasContractSchema(schema) || schema.startsWith("strategy-v5-")
                || schema.endsWith("/5") || "strategy-portfolio-policy/2".equals(schema)
                || !schema.matches(".*/[1-4]$")) return;
        try {
            if (!validator.validate(value)) throw failure("legacy validation rejected " + schema);
        } catch (RuntimeException error) {
            if (!rootMessage(error).contains("unsupported v5 schema")) throw error;
        }
    }

    private static JsonNode readPhysicalJson(Path path, String label) {
        if (!singleRegularFile(path)) throw failure(label + " is missing or not a regular single-link file: " + path);
        JsonNode value = parse(readBytes(path));
        if (!value.isObject()) throw failure(label + " is not a JSON object");
        return value;
    }

    private static boolean publicationArtifact(JsonNode value) {
        return "strategy-v5-statistical-wfo/1".equals(text(value, "schema"))
                || V5.get("run").equals(text(value, "schema"));
    }

    private static ObjectNode runDeploymentAuditCommand(ObjectNode options) {
        ObjectNode auditOptions = object();
        auditOptions.set("settings", readOptionalJsonOption(options, "settings", object()));
        auditOptions.set("keys", readOptionalJsonOption(options, "keys", object()));
        auditOptions.set("approvals", readOptionalJsonOption(options, "approvals", array()));
        auditOptions.set("trustRoot", readOptionalJsonOption(options, "trust_root", NullNode.instance));
        auditOptions.set("previousTrustRoot", readOptionalJsonOption(options, "previous_trust_root", NullNode.instance));
        if (defined(options.get("pinned_trust_root_fingerprint"))) {
            auditOptions.set("pinnedTrustRootFingerprint", options.get("pinned_trust_root_fingerprint"));
        }
        if (defined(options.get("pinned_trust_root_genesis_fingerprint"))) {
            auditOptions.set("pinnedTrustRootGenesisFingerprint", options.get("pinned_trust_root_genesis_fingerprint"));
        }
        ObjectNode audit = makeDeploymentAuditV5(auditOptions); ObjectNode result = object();
        if (defined(options.get("out")) && !text(options, "out").isEmpty()) {
            Path output = Path.of(text(options, "out")).toAbsolutePath().normalize();
            writeImmutableV5(output, audit); result.put("path", output.toString());
        } else result.putNull("path");
        result.set("audit", audit); return result;
    }

    private static JsonNode readOptionalJsonOption(ObjectNode options, String name, JsonNode fallback) {
        JsonNode value = options.get(name);
        if (!defined(value)) return fallback.deepCopy();
        if (value.isObject() || value.isArray()) return value.deepCopy();
        return readPhysicalJson(Path.of(value.asText()).toAbsolutePath().normalize(), name);
    }

    private static void writeImmutableV5(Path path, ObjectNode value) {
        validateV5Artifact(value); Path parent = path.getParent();
        if (parent == null) throw failure("immutable output has no parent: " + path);
        try {
            Files.createDirectories(parent);
            byte[] body = pretty(value).getBytes(StandardCharsets.UTF_8);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                JsonNode existing = readPhysicalJson(path, "existing immutable output"); validateV5Artifact(existing);
                if (!text(existing, "content_sha256").equals(text(value, "content_sha256"))) {
                    throw failure("immutable output collision: " + path);
                }
                return;
            }
            Files.write(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException error) { writeImmutableV5(path, value); }
        catch (IOException error) { throw failure(error.getMessage()); }
    }

    /* Internal compatibility mechanics. */

    private static ObjectNode compileStrategyDefinition(ObjectNode space, ObjectNode raw, boolean authoritative) {
        ObjectNode source = compileLooseDefinition(raw); ObjectNode values = chromosome(space, source);
        List<String> recognized = List.of("threshold", "threshold_op", "window", "side", "use_filter",
                "signal_rule", "predicate", "lifecycle", "max_lifecycle_bars", "stop_price", "target_price");
        List<String> unused = new ArrayList<>(); ObjectNode usage = object();
        for (JsonNode gene : space.path("genes")) {
            String name = text(gene, "name"), mapped = text(gene, "usage");
            if (mapped.isEmpty() && !recognized.contains(name)) unused.add(name);
            if (mapped.isEmpty()) mapped = recognized.contains(name) ? name : "UNUSED_TEST_GENE";
            usage.put(name, mapped);
        }
        if (authoritative && !unused.isEmpty()) {
            throw failure("authoritative gene compiler has unused genes: " + String.join(", ", unused));
        }
        source.setAll(values);
        if (!source.path("signal_rule").isObject() && finiteNumber(values.get("threshold"))) {
            ObjectNode signal = object(); signal.put("feature", "score");
            signal.put("op", firstText(source, "threshold_op").isEmpty() ? ">=" : text(source, "threshold_op"));
            signal.put("value", values.path("threshold").asDouble()); source.set("signal_rule", signal);
        }
        if (text(source, "direction").isEmpty() && !text(values, "side").isEmpty()) {
            source.put("direction", text(values, "side").toLowerCase(Locale.ROOT));
        }
        if (!defined(source.get("max_lifecycle_bars")) && finiteNumber(values.get("window"))) {
            source.put("max_lifecycle_bars", values.path("window").asDouble());
        }
        ObjectNode compiler = object(); compiler.put("schema", "strategy-frozen-gene-compiler/1");
        compiler.set("usage", usage); compiler.set("unused_genes", strings(unused)); source.set("compiler", compiler);
        return source;
    }

    private static ObjectNode compileLooseDefinition(ObjectNode raw) {
        ObjectNode value = raw == null ? object() : raw.deepCopy();
        if (!value.path("signal_rule").isObject() && finiteNumber(value.get("threshold"))) {
            ObjectNode signal = object(); signal.put("feature", "score");
            signal.put("op", text(value, "threshold_op").isEmpty() ? ">=" : text(value, "threshold_op"));
            signal.put("value", value.path("threshold").asDouble()); value.set("signal_rule", signal);
        }
        if (text(value, "direction").isEmpty() && !text(value, "side").isEmpty()) {
            value.put("direction", text(value, "side").toLowerCase(Locale.ROOT));
        }
        if (!defined(value.get("max_lifecycle_bars")) && finiteNumber(value.get("window"))) {
            value.put("max_lifecycle_bars", value.path("window").asDouble());
        }
        if (value.has("use_filter") && !value.path("use_filter").asBoolean(true) && value.path("signal_rule").isObject()) {
            ((ObjectNode) value.path("signal_rule")).putNull("filter");
        }
        return value;
    }

    private static ObjectNode chromosome(JsonNode space, JsonNode definition) {
        ObjectNode result = object(); JsonNode source = definition == null ? object() : definition;
        for (JsonNode gene : space.path("genes")) {
            String name = text(gene, "name"); JsonNode value = defined(source.get(name)) ? source.get(name) : gene.get("default");
            result.set(name, quantize(value, gene));
        }
        return result;
    }

    private static JsonNode quantize(JsonNode raw, JsonNode gene) {
        String type = text(gene, "type");
        if ("continuous".equals(type)) {
            double min = gene.path("min").asDouble(), max = gene.path("max").asDouble();
            double value = Math.min(max, Math.max(min, raw == null ? min : raw.asDouble()));
            if (defined(gene.get("step"))) value = min + Math.round((value - min) / gene.path("step").asDouble()) * gene.path("step").asDouble();
            int precision = gene.path("precision").asInt(8); double factor = Math.pow(10, precision);
            value = Math.round(value * factor) / factor; return NF.numberNode(value);
        }
        ArrayNode values = (ArrayNode) gene.path("values");
        if ("ordered-discrete".equals(type)) {
            JsonNode best = values.get(0); double target = raw == null ? best.asDouble() : raw.asDouble();
            for (JsonNode candidate : values) if (Math.abs(candidate.asDouble() - target) < Math.abs(best.asDouble() - target)) best = candidate;
            return best.deepCopy();
        }
        String serialized = stable(raw == null ? NullNode.instance : raw);
        for (JsonNode choice : values) if (stable(choice).equals(serialized)) return choice.deepCopy();
        return values.get(0).deepCopy();
    }

    private static JsonNode randomGene(JsonNode gene, XorShift random) {
        if ("continuous".equals(text(gene, "type"))) {
            return quantize(NF.numberNode(gene.path("min").asDouble()
                    + random.next() * (gene.path("max").asDouble() - gene.path("min").asDouble())), gene);
        }
        ArrayNode values = (ArrayNode) gene.path("values"); return values.get(random.nextInt(values.size())).deepCopy();
    }

    private static ArrayNode canonicalObservedVector(JsonNode raw) {
        if (!defined(raw)) return null;
        if (raw.isArray()) return raw.deepCopy();
        if (!raw.isObject()) return null; ArrayNode rows = array();
        TreeMap<String, JsonNode> ordered = new TreeMap<>(); raw.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
        ordered.forEach((time, value) -> {
            ObjectNode row = object(); row.put("time", time);
            JsonNode number = value.isObject() ? first(value.get("net_r"), value.get("r"), value.get("return")) : value;
            row.put("r", number == null ? 0 : number.asDouble()); rows.add(row);
        }); return rows;
    }

    private static ObjectNode observedVectorEnvelope(ArrayNode vector) {
        ObjectNode result = object(); result.put("schema", "strategy-observed-behavior-vector/1"); result.set("vector", vector); return result;
    }

    private static String behavior(JsonNode value) { return hash(stripCosmetic(value)); }

    private static JsonNode stripCosmetic(JsonNode value) {
        if (value == null || value.isNull()) return NullNode.instance;
        if (value.isArray()) { ArrayNode output = array(); value.forEach(child -> output.add(stripCosmetic(child))); return output; }
        if (!value.isObject()) return value.deepCopy(); ObjectNode output = object(); TreeMap<String, JsonNode> sorted = new TreeMap<>();
        value.fields().forEachRemaining(entry -> { if (!COSMETIC.contains(entry.getKey())) sorted.put(entry.getKey(), entry.getValue()); });
        sorted.forEach((key, child) -> output.set(key, stripCosmetic(child))); return output;
    }

    private static List<List<ObjectNode>> rankAndCrowd(List<ObjectNode> population) {
        List<ObjectNode> remaining = new ArrayList<>(population); List<List<ObjectNode>> fronts = new ArrayList<>(); int rank = 0;
        while (!remaining.isEmpty()) {
            List<ObjectNode> front = remaining.stream().filter(candidate -> remaining.stream()
                    .noneMatch(other -> other != candidate && dominates(other.path("fitness"), candidate.path("fitness"))))
                    .sorted(Comparator.comparing(row -> text(row, "behavior_sha256"))).toList();
            if (front.isEmpty()) throw failure("NSGA front construction stalled");
            for (ObjectNode row : front) row.put("rank", rank); crowd(front); fronts.add(new ArrayList<>(front));
            remaining.removeAll(front); rank++;
        }
        return fronts;
    }

    private static boolean dominates(JsonNode left, JsonNode right) {
        boolean lf = left.path("feasible").asBoolean(false), rf = right.path("feasible").asBoolean(false);
        if (lf && !rf) return true; if (!lf && rf) return false;
        if (!lf) return left.path("violations").size() < right.path("violations").size();
        JsonNode lo = left.path("objectives"), ro = right.path("objectives"); boolean noWorse = true, better = false;
        for (int i = 0; i < Math.min(lo.size(), ro.size()); i++) {
            noWorse &= lo.get(i).asDouble() >= ro.get(i).asDouble(); better |= lo.get(i).asDouble() > ro.get(i).asDouble();
        }
        return noWorse && better;
    }

    private static void crowd(List<ObjectNode> front) {
        if (front.isEmpty()) return; front.forEach(row -> row.put("crowding_distance", 0d));
        for (int objective = 0; objective < 4; objective++) {
            int index = objective; List<ObjectNode> sorted = new ArrayList<>(front);
            sorted.sort(Comparator.comparingDouble((ObjectNode row) -> row.path("fitness").path("objectives").path(index).asDouble())
                    .thenComparing(row -> text(row, "behavior_sha256")));
            sorted.getFirst().put("crowding_distance", Double.POSITIVE_INFINITY);
            sorted.getLast().put("crowding_distance", Double.POSITIVE_INFINITY);
            double low = sorted.getFirst().path("fitness").path("objectives").path(index).asDouble();
            double high = sorted.getLast().path("fitness").path("objectives").path(index).asDouble(), range = high - low;
            if (range == 0) range = 1;
            for (int i = 1; i < sorted.size() - 1; i++) if (Double.isFinite(crowding(sorted.get(i)))) {
                double increment = (sorted.get(i + 1).path("fitness").path("objectives").path(index).asDouble()
                        - sorted.get(i - 1).path("fitness").path("objectives").path(index).asDouble()) / range;
                sorted.get(i).put("crowding_distance", crowding(sorted.get(i)) + increment);
            }
        }
    }

    private static double crowding(JsonNode row) {
        JsonNode value = row.get("crowding_distance"); return value != null && value.isTextual()
                && "Infinity".equals(value.asText()) ? Double.POSITIVE_INFINITY : row.path("crowding_distance").asDouble();
    }

    private static ObjectNode tournament(List<ObjectNode> population, XorShift random) {
        ObjectNode a = population.get(random.nextInt(population.size())), b = population.get(random.nextInt(population.size()));
        int ar = a.path("rank").asInt(), br = b.path("rank").asInt(); if (ar != br) return ar < br ? a : b;
        double ac = crowding(a), bc = crowding(b); if (ac != bc) return ac > bc ? a : b;
        return text(a, "behavior_sha256").compareTo(text(b, "behavior_sha256")) <= 0 ? a : b;
    }

    private static ObjectNode crossover(JsonNode space, JsonNode left, JsonNode right,
            XorShift random, double probability, double eta) {
        if (random.next() > probability) return chromosome(space, left); ObjectNode child = object();
        for (JsonNode gene : space.path("genes")) {
            String name = text(gene, "name");
            if ("continuous".equals(text(gene, "type"))) {
                double u = random.next(), beta = u <= .5 ? Math.pow(2 * u, 1d / (eta + 1))
                        : Math.pow(1d / (2 * (1 - u)), 1d / (eta + 1));
                child.set(name, quantize(NF.numberNode(.5 * ((1 + beta) * left.path(name).asDouble()
                        + (1 - beta) * right.path(name).asDouble())), gene));
            } else child.set(name, (random.next() < .5 ? left.path(name) : right.path(name)).deepCopy());
        }
        return child;
    }

    private static ObjectNode mutate(JsonNode space, JsonNode rawCandidate, XorShift random, double probability) {
        ObjectNode result = chromosome(space, rawCandidate);
        for (JsonNode gene : space.path("genes")) if (random.next() < probability) {
            String name = text(gene, "name"), type = text(gene, "type");
            if ("continuous".equals(type)) {
                double span = defined(gene.get("step")) ? gene.path("step").asDouble()
                        : (gene.path("max").asDouble() - gene.path("min").asDouble()) / 10d;
                if (span == 0) span = 1;
                result.set(name, quantize(NF.numberNode(result.path(name).asDouble() + (random.next() * 2 - 1) * span), gene));
            } else if ("ordered-discrete".equals(type)) {
                ArrayNode values = (ArrayNode) gene.path("values"); int current = indexOf(values, result.get(name));
                result.set(name, values.get(Math.max(0, Math.min(values.size() - 1,
                        current + (random.next() < .5 ? -1 : 1)))).deepCopy());
            } else {
                ArrayNode values = (ArrayNode) gene.path("values"); result.set(name, values.get(random.nextInt(values.size())).deepCopy());
            }
        }
        return result;
    }

    private static ObjectNode evaluateLegacy(ObjectNode candidate, int generation, int seed, String operator,
            ArrayNode parents, LegacyEvaluator evaluator, String cutoff, int halfLifeMonths,
            Map<String, ObjectNode> evaluated, ArrayNode history) {
        String behavior = behavior(candidate); ObjectNode context = object(); context.put("generation", generation);
        context.put("seed", seed); context.put("phase", "TRAIN_ONLY");
        if (cutoff.isEmpty()) context.putNull("cutoff"); else context.put("cutoff", cutoff);
        context.put("half_life_months", halfLifeMonths); JsonNode raw = evaluator.evaluate(candidate.deepCopy(), context);
        ObjectNode metrics = metricRecord(raw, candidate, cutoff, halfLifeMonths); ArrayNode objectives = array();
        objectives.add(metrics.path("bootstrap_p20").asDouble(-1e9)); objectives.add(metrics.path("weighted_p20").asDouble(-1e9));
        double turnover = -metrics.path("annualized_turnover").asDouble(); objectives.add(turnover == 0 ? 0 : turnover);
        objectives.add(-metrics.path("complexity").asDouble()); metrics.set("objectives", objectives.deepCopy()); String alias = hash(first(raw == null ? null : raw.get("behavior_vector"),
                raw == null ? null : raw.get("signal_intent"), metrics.get("episode_returns"), candidate));
        ObjectNode fitness = object(); fitness.set("metrics", metrics); fitness.put("behavior_alias_sha256", alias);
        fitness.set("objectives", objectives); fitness.put("feasible", metrics.path("feasible").asBoolean(false));
        fitness.set("violations", metrics.path("violations"));
        ObjectNode row = object(); row.set("candidate", candidate.deepCopy()); row.put("behavior_sha256", behavior);
        row.put("generation", generation); row.put("seed", seed); row.put("operator", operator); row.set("parent_ids", parents);
        row.put("behavior_alias_sha256", alias); row.set("fitness", fitness); evaluated.put(behavior, row); history.add(row); return row;
    }

    private static ObjectNode metricRecord(JsonNode raw, JsonNode candidate, String cutoff, int halfLifeMonths) {
        JsonNode metrics = raw != null && raw.path("metrics").isObject() ? raw.path("metrics")
                : raw == null ? object() : raw; JsonNode returns = first(metrics.get("episode_returns"), raw == null ? null : raw.get("episode_returns"), array());
        List<ReturnRow> rows = valuesFromReturns(returns); int iterations = integer(metrics.get("bootstrap_iterations"), 512);
        int seed = hash(candidate).substring(0, 8).chars().sum(); List<Double> boot = blockBootstrapMeans(rows, iterations, seed, null);
        Double unweighted = p20(boot); ObjectNode weightedOptions = object().put("halfLifeMonths", halfLifeMonths);
        if (!cutoff.isEmpty()) weightedOptions.put("cutoff", cutoff); Double weighted = weightedP20(returns, weightedOptions);
        double expectancy = firstFinite(metrics, "expectancy_r", mean(rows.stream().map(ReturnRow::value).toList()), -1e9);
        double turnover = Math.max(0, firstFinite(metrics, "annualized_turnover",
                finiteOrNull(metrics.get("turnover")), 0));
        double complexity = Math.max(0, firstFinite(metrics, "complexity", null,
                candidate != null && candidate.isObject() ? candidate.size() : 0));
        int completed = Math.max(0, (int) Math.floor(firstFinite(metrics, "completed_episodes", null, rows.size())));
        double drawdown = firstFinite(metrics, "max_drawdown_r", finiteOrNull(metrics.get("drawdown_r")), 0);
        Double profitFactor = finiteOrNull(metrics.get("profit_factor")); double coverage = firstFinite(metrics, "coverage_fraction", null, 1);
        double cost = firstFinite(metrics, "cost_r", finiteOrNull(metrics.get("cost")), 0);
        double drawdownMagnitude = Math.abs(firstFinite(metrics, "max_drawdown_r_magnitude", null, drawdown));
        Double drawdownLimit = finiteOrNull(metrics.get("max_drawdown_limit_r"));
        Double maxCost = finiteOrNull(metrics.get("max_cost_r")); ObjectNode constraints = object();
        constraints.put("minimum_episodes", completed >= 30); constraints.put("positive_expectancy", expectancy > 0);
        constraints.put("drawdown", metrics.path("constraints").has("drawdown")
                ? metrics.path("constraints").path("drawdown").asBoolean(false)
                : drawdownLimit == null || drawdownMagnitude <= Math.abs(drawdownLimit));
        constraints.put("cost", metrics.path("constraints").has("cost")
                ? metrics.path("constraints").path("cost").asBoolean(false) : maxCost == null || cost <= maxCost);
        constraints.put("coverage", coverage >= .95); constraints.put("profit_factor", profitFactor == null || profitFactor >= 1);
        constraints.put("capacity", !metrics.has("capacity_pass") || metrics.path("capacity_pass").asBoolean(false));
        metrics.path("constraints").fields().forEachRemaining(entry -> {
            if (entry.getValue().isBoolean()) constraints.put(entry.getKey(), entry.getValue().asBoolean());
        });
        ArrayNode violations = array(); constraints.fields().forEachRemaining(entry -> {
            if (!entry.getValue().asBoolean()) violations.add(entry.getKey());
        });
        ObjectNode result = object(); result.put("bootstrap_p20", unweighted == null ? -1e9 : unweighted);
        result.put("weighted_p20", weighted == null ? -1e9 : weighted); result.put("expectancy_r", expectancy);
        result.put("annualized_turnover", turnover); result.put("complexity", complexity); result.put("completed_episodes", completed);
        result.put("max_drawdown_r", drawdown); result.put("max_drawdown_r_magnitude", drawdownMagnitude);
        if (profitFactor == null) result.putNull("profit_factor"); else result.put("profit_factor", profitFactor);
        result.put("coverage_fraction", coverage); result.put("cost_r", cost); result.set("constraints", constraints);
        result.set("violations", violations); result.put("feasible", violations.isEmpty()); result.set("episode_returns", returns); return result;
    }

    private record ReturnRow(double value, JsonNode time) {}

    private static List<ReturnRow> valuesFromReturns(JsonNode returns) {
        List<ReturnRow> rows = new ArrayList<>(); if (!defined(returns)) return rows;
        if (returns.isArray()) {
            int index = 0; for (JsonNode raw : returns) {
                JsonNode value = raw.isObject() ? first(raw.get("net_r"), raw.get("r"), raw.get("return")) : raw;
                if (!finiteNumber(value)) throw failure("episode " + index + " must be finite");
                JsonNode time = raw.isObject() ? first(raw.get("time"), raw.get("exit_time"), NF.numberNode(index)) : NF.numberNode(index);
                rows.add(new ReturnRow(value.asDouble(), time == null ? NF.numberNode(index) : time.deepCopy())); index++;
            }
        } else if (returns.isObject()) {
            returns.fields().forEachRemaining(entry -> {
                JsonNode raw = entry.getValue(); JsonNode value = raw.isObject()
                        ? first(raw.get("net_r"), raw.get("r"), raw.get("return")) : raw;
                if (!finiteNumber(value)) throw failure("episode " + entry.getKey() + " must be finite");
                rows.add(new ReturnRow(value.asDouble(), NF.textNode(entry.getKey())));
            });
        }
        return rows;
    }

    private static Map<String, JsonNode> candidateReturnMap(JsonNode returns) {
        LinkedHashMap<String, JsonNode> result = new LinkedHashMap<>();
        if (returns == null || returns.isNull()) return result;
        if (returns.isArray()) { result.put("selected", returns); return result; }
        if (!returns.isObject()) return result; boolean matrix = returns.size() > 0;
        Iterator<JsonNode> values = returns.elements();
        while (values.hasNext()) {
            JsonNode value = values.next(); matrix &= value.isArray()
                    || value.isObject() && (value.path("episode_returns").isArray() || value.path("returns").isArray());
        }
        if (!matrix) { result.put("selected", returns); return result; }
        returns.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().isArray()
                ? entry.getValue() : first(entry.getValue().get("episode_returns"), entry.getValue().get("returns"))));
        return result;
    }

    private static List<Double> blockBootstrapMeans(List<ReturnRow> rows, int iterations, int seed, double[] weights) {
        if (rows.isEmpty()) return List.of(); XorShift random = new XorShift(seed);
        int block = Math.max(1, (int) Math.ceil(Math.sqrt(rows.size()))); double[] normalized = weights;
        if (normalized == null) { normalized = new double[rows.size()]; java.util.Arrays.fill(normalized, 1d / rows.size()); }
        List<Double> output = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Double> sample = new ArrayList<>(); while (sample.size() < rows.size()) {
                int start = weightedChoice(random, normalized);
                for (int offset = 0; offset < block && sample.size() < rows.size(); offset++) {
                    sample.add(rows.get((start + offset) % rows.size()).value());
                }
            }
            output.add(mean(sample));
        }
        return output;
    }

    private static List<Double> deterministicBootstrap(List<Double> values, int iterations, int seed) {
        if (values.isEmpty()) return List.of(); XorShift random = new XorShift(seed);
        int block = Math.max(1, (int) Math.ceil(Math.sqrt(values.size()))); List<Double> output = new ArrayList<>();
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Double> sample = new ArrayList<>(); while (sample.size() < values.size()) {
                int start = random.nextInt(values.size());
                for (int offset = 0; offset < block && sample.size() < values.size(); offset++) {
                    sample.add(values.get((start + offset) % values.size()));
                }
            }
            output.add(mean(sample));
        }
        return output;
    }

    private static ObjectNode synchronizedCenteredMaxStatistic(Map<String, JsonNode> matrix, int iterations, int seed) {
        List<String> ids = new ArrayList<>(matrix.keySet()); Collections.sort(ids);
        List<List<Double>> vectors = ids.stream().map(id -> valuesFromReturns(matrix.get(id)).stream().map(ReturnRow::value).toList()).toList();
        int length = vectors.stream().mapToInt(List::size).max().orElse(0); ObjectNode result = object();
        if (ids.isEmpty() || length == 0) {
            result.put("status", "NOT_RUN"); result.putNull("p_value"); result.putNull("statistic");
            result.put("iterations", iterations); result.put("synchronized", true); result.put("candidate_count", ids.size()); return result;
        }
        double observed = vectors.stream().mapToDouble(row -> mean(row)).max().orElse(Double.NEGATIVE_INFINITY);
        List<List<Double>> centered = vectors.stream().map(row -> { double mean = mean(row);
            return row.stream().map(value -> value - mean).toList(); }).toList();
        XorShift random = new XorShift(seed); int block = Math.max(1, (int) Math.ceil(Math.sqrt(length))), exceed = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Integer> indexes = new ArrayList<>(); while (indexes.size() < length) {
                int start = random.nextInt(length);
                for (int offset = 0; offset < block && indexes.size() < length; offset++) indexes.add((start + offset) % length);
            }
            double max = centered.stream().mapToDouble(row -> mean(indexes.stream().map(index -> row.get(index % row.size())).toList()))
                    .max().orElse(Double.NEGATIVE_INFINITY); if (max >= observed) exceed++;
        }
        result.put("status", "PASS"); result.put("p_value", (exceed + 1d) / (iterations + 1d));
        result.put("statistic", observed); result.put("iterations", iterations); result.put("synchronized", true);
        result.put("candidate_count", ids.size()); result.put("null_center", "EACH_CANDIDATE_MEAN");
        result.put("resampling", "SHARED_STATIONARY_TIME_BLOCKS"); result.put("explicit_aligned_zeros", true); return result;
    }

    private static int weightedChoice(XorShift random, double[] weights) {
        double target = random.next(), cumulative = 0; for (int index = 0; index < weights.length; index++) {
            cumulative += weights[index]; if (target <= cumulative) return index;
        }
        return weights.length - 1;
    }

    private static Double p20(List<Double> values) {
        if (values == null || values.isEmpty()) return null; List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted); return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * .2) - 1));
    }

    private static Double mean(Collection<Double> values) {
        if (values == null || values.isEmpty()) return null;
        double sum = 0; for (Double value : values) sum += value; return sum / values.size();
    }

    private static ObjectNode frozenGeneticConfig(ObjectNode raw, int ignored) {
        ObjectNode config = raw == null ? object() : raw; int worker = integer(first(config.get("workerCount"), config.get("worker_count")), 1);
        if (worker < 1 || worker > 8) throw failure("worker_count must be an integer in [1,8]");
        int population = integer(config.get("population"), 48), max = integer(first(config.get("maxGenerations"), config.get("max_generations")), 20);
        int min = integer(first(config.get("minGenerations"), config.get("min_generations")), 10);
        int plateau = integer(first(config.get("plateauGenerations"), config.get("plateau_generations")), 5);
        double crossover = defined(first(config.get("crossoverProbability"), config.get("crossover_probability")))
                ? first(config.get("crossoverProbability"), config.get("crossover_probability")).asDouble() : .9;
        ArrayNode seeds = config.path("seeds").isArray() ? (ArrayNode) config.path("seeds").deepCopy() : array().add(11).add(23).add(47);
        if (population < 2 || max < 1 || min < 1 || min > max || seeds.isEmpty()) throw failure("invalid frozen genetic configuration");
        if (crossover < 0 || crossover > 1) throw failure("crossover_probability must be in [0,1]");
        ObjectNode result = object(); result.put("population", population); result.put("max_generations", max);
        result.put("min_generations", min); result.put("plateau_generations", plateau); result.put("crossover_probability", crossover);
        result.put("sbx_eta", number(config, "sbxEta", number(config, "sbx_eta", 2)));
        if (defined(config.get("mutationProbability"))) result.put("mutation_probability", config.path("mutationProbability").asDouble());
        else result.putNull("mutation_probability"); result.set("seeds", seeds);
        result.put("half_life_months", integer(first(config.get("halfLifeMonths"), config.get("half_life_months")), 18));
        result.set("objectives", strings(List.of("unweighted_bootstrap_p20_max", "weighted_bootstrap_p20_max",
                "annualized_turnover_min", "complexity_min")));
        result.put("constraint_policy", "hard_constraints_constraint_dominance"); result.put("population_history", "complete");
        result.put("scheduler", worker > 1 || bool(config, "worker_threads")
                ? "deterministic_bounded_worker_threads" : "deterministic_bounded_single_process_evaluator");
        result.put("worker_count", worker); result.put("checkpoint", "content_addressed_checkpoint_resume");
        result.put("tie_break", "behavior_sha256_ascending");
        result.put("implementation_sha256", STRATEGY_RESEARCH_V5_CODE_SHA256);
        ObjectNode environment = object(); environment.put("java", System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name")); environment.put("arch", System.getProperty("os.arch"));
        result.put("environment_sha256", hash(environment)); return result;
    }

    private static String lineageHash(ObjectNode args, String camel, String snake, String family, String dataset) {
        String value = firstNonEmpty(firstText(args, camel), text(args.path("config"), snake), text(args.path("lineage"), snake));
        if (value.isEmpty()) { ObjectNode fixture = object(); fixture.put("v5_test_lineage", camel);
            fixture.put("family", family); fixture.put("dataset", dataset); value = hash(fixture); }
        return requiredHash(value, camel);
    }

    private static JsonNode checkpointSafe(JsonNode value) {
        if (value == null || value.isNull()) return NullNode.instance;
        if (value.isNumber() && !Double.isFinite(value.asDouble())) return NullNode.instance;
        if (value.isArray()) { ArrayNode result = array(); value.forEach(row -> result.add(checkpointSafe(row))); return result; }
        if (value.isObject()) { ObjectNode result = object(); value.fields().forEachRemaining(entry -> result.set(entry.getKey(), checkpointSafe(entry.getValue()))); return result; }
        return value.deepCopy();
    }

    private static final class XorShift {
        private int state;
        private XorShift(int seed) { state = seed == 0 ? 1 : seed; }
        private double next() { state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
            return Integer.toUnsignedLong(state) / 4294967296d; }
        private int nextInt(int maximum) { return Math.min(maximum - 1, (int) Math.floor(next() * maximum)); }
        private long state() { return Integer.toUnsignedLong(state); }
    }

    private static Path exposureHeadPath(ObjectNode options, String family) {
        String headPath = firstText(options, "headPath", "head_path"); if (!headPath.isEmpty()) return Path.of(headPath).toAbsolutePath().normalize();
        String root = firstText(options, "headRoot", "head_root"); if (root.isEmpty()) return null;
        return Path.of(root).resolve(family.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".head.json").toAbsolutePath().normalize();
    }

    private static ObjectNode readPersistentExposureHead(Path path) {
        if (!Files.exists(path)) return null; JsonNode wrapper = readJson(path);
        if (!"strategy-exposure-head/1".equals(text(wrapper, "schema"))
                || !text(wrapper, "content_sha256").equals(ownHash(wrapper))) {
            throw failure("persistent exposure head is tampered");
        }
        JsonNode ledger = wrapper.path("ledger"); assertHash(ledger, V5.get("exposure"), "persistent exposure ledger");
        if (!text(wrapper, "ledger_sha256").equals(text(ledger, "content_sha256"))) {
            throw failure("persistent exposure head ledger binding is invalid");
        }
        return (ObjectNode) ledger;
    }

    private static void persistExposureHead(Path path, ObjectNode ledger, String expectedPrior) {
        Path lock = Path.of(path + ".lock"); boolean owned = false;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try { Files.writeString(lock, ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); owned = true; }
            catch (FileAlreadyExistsException error) { throw failure("EEXIST: competing exposure head writer is active"); }
            ObjectNode current = readPersistentExposureHead(path);
            if (expectedPrior == null ? current != null : current == null || !expectedPrior.equals(text(current, "content_sha256"))) {
                throw failure("persistent exposure head compare-and-swap failed");
            }
            ObjectNode wrapper = object(); wrapper.put("schema", "strategy-exposure-head/1"); wrapper.put("version", 1);
            wrapper.put("ledger_sha256", text(ledger, "content_sha256")); wrapper.set("ledger", ledger);
            if (expectedPrior == null) wrapper.putNull("expected_prior_sha256"); else wrapper.put("expected_prior_sha256", expectedPrior);
            wrapper = withHash(wrapper); Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + System.nanoTime());
            Files.writeString(temporary, pretty(wrapper), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IllegalArgumentException error) { throw error; }
        catch (IOException error) { throw failure(error.getMessage()); }
        finally { if (owned) try { Files.deleteIfExists(lock); } catch (IOException ignored) {} }
    }

    private static String exposurePointer(JsonNode ledger) {
        return exposurePointer(text(ledger, "hypothesis_family"), text(ledger, "dataset_root_sha256"),
                arrayOrEmpty(ledger.get("entries")));
    }

    private static String exposurePointer(String family, String dataset, ArrayNode entries) {
        ObjectNode value = object(); value.put("hypothesis_family", family);
        value.put("last_entry_sha256", entries.isEmpty() ? hash("V5-GENESIS") : hash(entries.get(entries.size() - 1)));
        value.put("dataset_root_sha256", dataset); return hash(value);
    }

    /* Small JSON/filesystem primitives kept local so the facade has no implicit mutable state. */

    private static ObjectNode object() { return NF.objectNode(); }
    private static ArrayNode array() { return NF.arrayNode(); }

    private static ObjectNode objectOrEmpty(JsonNode value) {
        return value instanceof ObjectNode object ? object : object();
    }

    private static ObjectNode objectOrNull(JsonNode value) {
        return value instanceof ObjectNode object ? object : null;
    }

    private static ArrayNode arrayOrEmpty(JsonNode value) {
        return value instanceof ArrayNode array ? array : array();
    }

    private static ArrayNode requiredArray(JsonNode value, String name) {
        if (!(value instanceof ArrayNode array)) throw failure(name + " must be an array");
        return array;
    }

    private static JsonNode first(JsonNode... values) {
        if (values == null) return null;
        for (JsonNode value : values) if (defined(value)) return value;
        return null;
    }

    private static JsonNode at(JsonNode value, String... fields) {
        JsonNode current = value;
        for (String field : fields) {
            if (current == null || !current.isObject()) return NullNode.instance;
            current = current.get(field);
        }
        return current == null ? NullNode.instance : current;
    }

    private static boolean defined(JsonNode value) { return value != null && !value.isNull() && !value.isMissingNode(); }
    private static JsonNode nullToNull(JsonNode value) { return defined(value) ? value : NullNode.instance; }
    private static JsonNode orNull(JsonNode value) { return nullToNull(value); }

    private static String text(JsonNode value) {
        if (!defined(value)) return "";
        return value.isTextual() ? value.textValue() : value.asText("");
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static String text(JsonNode value, String field) {
        return value == null ? "" : text(value.get(field));
    }

    private static String firstText(JsonNode value, String... fields) {
        if (value == null) return "";
        for (String field : fields) {
            String candidate = text(value, field);
            if (!candidate.isEmpty()) return candidate;
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        if (values != null) for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    private static void putNullable(ObjectNode target, String name, String value) {
        if (value == null || value.isEmpty()) target.putNull(name); else target.put(name, value);
    }

    private static boolean bool(JsonNode value, String field) {
        return value != null && value.path(field).asBoolean(false);
    }

    private static boolean finiteNumber(JsonNode value) {
        return defined(value) && value.isNumber() && Double.isFinite(value.asDouble());
    }

    private static double finite(JsonNode value, String name) {
        if (!finiteNumber(value)) throw failure(name + " must be finite");
        return value.asDouble();
    }

    private static double number(JsonNode value, String field, double fallback) {
        JsonNode child = value == null ? null : value.get(field);
        return finiteNumber(child) ? child.asDouble() : fallback;
    }

    private static double firstNumber(JsonNode value, double fallback, String... fields) {
        if (value != null) for (String field : fields) {
            JsonNode candidate = value.get(field);
            if (finiteNumber(candidate)) return candidate.asDouble();
        }
        return fallback;
    }

    private static double firstNumber(JsonNode value, String firstField, String secondField, double fallback) {
        return firstNumber(value, fallback, firstField, secondField);
    }

    private static int integer(JsonNode value, int fallback) {
        return defined(value) && value.isIntegralNumber() ? value.asInt() : fallback;
    }

    private static Double finiteOrNull(JsonNode value) {
        return finiteNumber(value) ? value.asDouble() : null;
    }

    private static Double firstFinite(JsonNode value, String... fields) {
        if (value != null) for (String field : fields) {
            Double candidate = finiteOrNull(value.get(field)); if (candidate != null) return candidate;
        }
        return null;
    }

    private static double firstFinite(JsonNode value, String field, Double secondary, double fallback) {
        Double primary = value == null ? null : finiteOrNull(value.get(field));
        return primary != null ? primary : secondary != null ? secondary : fallback;
    }

    private static double drawdown(JsonNode metrics) {
        return Math.abs(firstNumber(metrics, 0d, "max_drawdown_r_magnitude", "max_drawdown_r", "drawdown_r"));
    }

    private static ArrayNode strings(Collection<String> values) {
        ArrayNode result = array(); if (values != null) values.forEach(result::add); return result;
    }

    private static List<String> texts(JsonNode values) {
        List<String> result = new ArrayList<>(); if (values != null && values.isArray()) {
            for (JsonNode value : values) result.add(text(value));
        }
        return result;
    }

    private static Set<String> textSet(JsonNode values) { return new HashSet<>(texts(values)); }

    private static boolean containsText(JsonNode values, String target) {
        if (values == null || !values.isArray()) return false;
        for (JsonNode value : values) if (Objects.equals(text(value), target)) return true;
        return false;
    }

    private static int indexOf(ArrayNode values, JsonNode target) {
        for (int index = 0; index < values.size(); index++) if (stable(values.get(index)).equals(stable(target))) return index;
        return -1;
    }

    private static List<ObjectNode> objects(JsonNode values) {
        List<ObjectNode> result = new ArrayList<>(); if (values != null && values.isArray()) {
            for (JsonNode value : values) if (value instanceof ObjectNode object) result.add(object);
        }
        return result;
    }

    private static ArrayNode toArray(Collection<? extends JsonNode> values) {
        ArrayNode result = array(); if (values != null) values.forEach(result::add); return result;
    }

    private static void sortArray(ArrayNode values, Comparator<JsonNode> comparator) {
        List<JsonNode> rows = new ArrayList<>(); values.forEach(rows::add); rows.sort(comparator);
        values.removeAll(); rows.forEach(values::add);
    }

    private static boolean every(JsonNode values, java.util.function.Predicate<JsonNode> predicate) {
        if (values == null || !values.isArray()) return false;
        for (JsonNode value : values) if (!predicate.test(value)) return false;
        return true;
    }

    private static long millis(JsonNode value) {
        if (!defined(value)) throw failure("timestamp is missing");
        if (value.isNumber()) {
            double numeric = value.asDouble(); if (!Double.isFinite(numeric)) throw failure("timestamp is invalid");
            return (long) numeric;
        }
        return millis(text(value));
    }

    private static long millis(String value) {
        if (value == null || value.isBlank()) throw failure("timestamp is invalid");
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { }
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
            catch (DateTimeParseException error) { throw failure("timestamp is invalid: " + value); }
        }
    }

    private static Long tryMillis(JsonNode value) {
        try { return defined(value) ? millis(value) : null; } catch (IllegalArgumentException ignored) { return null; }
    }

    private static final DateTimeFormatter NODE_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static String iso(long millis) { return NODE_ISO.format(Instant.ofEpochMilli(millis)); }

    private static String normalizeAsset(String raw) {
        String value = text(raw).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (value.endsWith("usdt")) value = value.substring(0, value.length() - 4);
        if (value.isEmpty()) throw failure("asset is required"); return value;
    }

    private static boolean isHash(String value) { return value != null && HASH.matcher(value).matches(); }

    private static String requiredHash(String value, String name) {
        if (!isHash(value)) throw failure(name + " must be a sha256 digest"); return value;
    }

    private static void assertHash(JsonNode value, String schema, String name) {
        if (value == null || !value.isObject() || !schema.equals(text(value, "schema"))
                || !isHash(text(value, "content_sha256")) || !ownHash(value).equals(text(value, "content_sha256"))) {
            throw failure(name + " is not hash-valid");
        }
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message == null ? "v5 facade failure" : message);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return text(current.getMessage());
    }

    private static String pretty(JsonNode value) {
        return NodePrettyJson.write(value);
    }

    private static JsonNode parse(String value) {
        try { return JSON.readTree(value); } catch (JsonProcessingException error) { throw failure(error.getMessage()); }
    }


    private static JsonNode parse(byte[] value) {
        try { return JSON.readTree(value); } catch (IOException error) { throw failure(error.getMessage()); }
    }

    private static JsonNode readJson(Path path) {
        try { return JSON.readTree(Files.readAllBytes(path)); } catch (IOException error) { throw failure(error.getMessage()); }
    }

    private static byte[] readBytes(Path path) {
        try { return Files.readAllBytes(path); } catch (IOException error) { throw failure(error.getMessage()); }
    }

    private static String fileHash(Path path) { return hash(readBytes(path)); }

    private static List<JsonNode> jsonLines(Path path) {
        try {
            List<JsonNode> result = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) if (!line.isBlank()) result.add(parse(line));
            return result;
        } catch (IOException error) { throw failure(error.getMessage()); }
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }

    private static boolean singleRegularFile(Path path) {
        try { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path) && Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS).equals(1);
        } catch (UnsupportedOperationException | IOException error) {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
        }
    }

    private static ObjectNode withFixtureHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy(); copy.put("provenance", "FIXTURE/LEGACY_EXPOSED");
        return withHash(copy);
    }

    private static boolean sameFields(JsonNode left, JsonNode right, Map<String, String> mapping) {
        if (left == null || right == null) return false;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (!stable(nullToNull(left.get(entry.getKey()))).equals(stable(nullToNull(right.get(entry.getValue()))))) return false;
        }
        return true;
    }

    private static ObjectNode makeLegacySeries(
            String asset, String instrument, String interval, long start, long end, long completedThrough) {
        boolean funding = "funding".equals(interval), dated = "BINANCE_USDM_DATED_FUTURE".equals(instrument);
        boolean derivative = !"BINANCE_SPOT".equals(instrument); long step = funding ? 8L * 3_600_000L : 4L * 3_600_000L;
        ObjectNode row = object(); row.put("asset", asset); row.put("venue", "BINANCE"); row.put("instrument", instrument);
        row.put("interval", interval); row.put("series_type", funding ? "funding_events" : "signal_bars");
        row.put("expected_step_ms", step); row.put("expected_event_count", Math.floorDiv(end - start, step) + 1);
        row.put("signal_timeframe", "4h"); row.put("execution_timeframe", funding ? "funding" : "4h");
        row.put("start_at", iso(start)); row.put("end_at", iso(end)); row.put("availability_cutoff_at", iso(completedThrough));
        row.put("completed_bars_only", !funding); row.put("require_availability_time", true);
        row.put("fee_schedule", "effective_binance_fee_schedule_required");
        row.put("contract_specification", "exact_contract_specification_required");
        row.put("funding", "BINANCE_USDM_PERPETUAL".equals(instrument) ? "exact_settlement_events_required" : "not_applicable");
        row.put("expiry", dated ? "exact_contract_expiry_required" : "not_applicable");
        row.put("margin", derivative ? "exact_contract_margin_required" : "not_applicable");
        row.put("liquidation", derivative ? "exact_mark_liquidation_inputs_required" : "not_applicable");
        row.put("required", !dated); return row;
    }

    private static void validateLegacyPartition(Path root, JsonNode partition, JsonNode series) {
        String relative = text(partition, "path"), digest = text(partition, "sha256");
        int rowCount = integer(partition.get("row_count"), -1);
        if (relative.isEmpty() || !isHash(digest) || rowCount < 1) throw failure("acquired partition metadata is incomplete");
        Path path = root.resolve(relative).normalize(); if (!singleRegularFile(path) || !digest.equals(fileHash(path))) {
            throw failure("acquired partition is missing or tampered: " + relative);
        }
        List<JsonNode> rows = jsonLines(path); if (rows.size() != rowCount) throw failure("acquired partition row count mismatch: " + relative);
        List<Long> times = new ArrayList<>(), availability = new ArrayList<>();
        for (JsonNode row : rows) {
            times.add(millis(first(row.get("event_time"), row.get("time"), row.get("open_time"))));
            availability.add(millis(first(row.get("availability_time"), row.get("available_at"), row.get("event_time"), row.get("time"))));
        }
        List<Long> ordered = new ArrayList<>(times); Collections.sort(ordered);
        if (new HashSet<>(times).size() != times.size()) throw failure("acquired partition contains duplicate event times: " + relative);
        if (!iso(ordered.getFirst()).equals(iso(millis(partition.get("min_event_time"))))
                || !iso(ordered.getLast()).equals(iso(millis(partition.get("max_event_time"))))) {
            throw failure("acquired partition event bounds mismatch: " + relative);
        }
        long step = series.path("expected_step_ms").asLong(), expected = series.path("expected_event_count").asLong();
        if (rows.size() != expected || !at(partition, "coverage", "complete").asBoolean(false)) {
            throw failure("acquired partition is not a dense complete grid: " + relative);
        }
        long start = millis(series.get("start_at")); for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index) != start + index * step) throw failure("acquired partition has a gap or wrong cadence: " + relative);
            if (bool(series, "completed_bars_only") && availability.get(index) < times.get(index) + step - 1000) {
                throw failure("acquired completed bar is available before its close: " + relative);
            }
        }
    }

    private static void validateLegacyMetadata(Path root, JsonNode metadata, String name, JsonNode manifest) {
        ArrayNode artifacts = arrayOrEmpty(metadata.get("artifacts"));
        if (!"BOUND".equals(text(metadata, "status")) || artifacts.isEmpty()) {
            throw failure("ACQUIRED " + name + " metadata is not physically bound");
        }
        List<String> requiredFields = name.equals("fee schedule")
                ? List.of("asset", "instrument", "effective_from", "effective_to", "maker_fee_rate", "taker_fee_rate", "currency")
                : name.equals("contract specification")
                ? List.of("asset", "instrument", "effective_from", "effective_to", "symbol", "contract_multiplier", "margin_asset", "maintenance_margin_ratio", "liquidation_policy")
                : List.of("asset", "instrument", "effective_from", "effective_to", "event_id", "venue", "source");
        Set<String> seen = new HashSet<>(); List<JsonNode> records = new ArrayList<>();
        for (JsonNode artifact : artifacts) {
            Path path = root.resolve(text(artifact, "path")).normalize();
            if (!singleRegularFile(path) || !isHash(text(artifact, "sha256")) || !text(artifact, "sha256").equals(fileHash(path))) {
                throw failure("ACQUIRED " + name + " metadata artifact is missing or tampered");
            }
            JsonNode body = readJson(path); String planHash = firstNonEmpty(text(manifest, "plan_sha256"), text(manifest, "content_sha256"));
            if (!planHash.equals(text(body, "plan_sha256")) || !body.path("records").isArray() || body.path("records").isEmpty()) {
                throw failure("ACQUIRED " + name + " metadata lacks effective-dated semantic records");
            }
            for (JsonNode record : body.path("records")) {
                for (String field : requiredFields) if (!defined(record.get(field)) || text(record.get(field)).isEmpty()) {
                    throw failure("ACQUIRED " + name + " metadata record is incomplete");
                }
                if (millis(record.get("effective_to")) < millis(record.get("effective_from"))) {
                    throw failure("ACQUIRED " + name + " metadata effective bounds are invalid");
                }
                records.add(record); seen.add(normalizeAsset(text(record, "asset")) + "|" + text(record, "instrument"));
            }
        }
        Set<String> requiredPairs = new HashSet<>(); for (JsonNode series : manifest.path("series")) {
            if (!series.has("required") || series.path("required").asBoolean()) {
                if (!name.equals("funding identity") || "funding_events".equals(text(series, "series_type"))) {
                    requiredPairs.add(text(series, "asset") + "|" + text(series, "instrument"));
                }
            }
        }
        for (String pair : requiredPairs) if (!seen.contains(pair)) throw failure("ACQUIRED " + name + " metadata lacks required " + pair + " record");
    }

    private static boolean candidateMatches(JsonNode row, JsonNode rawDefinition) {
        ObjectNode definition = compileLooseDefinition(objectOrEmpty(rawDefinition));
        JsonNode rule = first(definition.get("signal_rule"), definition.get("predicate"));
        if (rule == null || !rule.isObject()) return true;
        String field = text(rule, "field"), op = text(rule, "op"); double threshold = number(rule, "threshold", Double.NaN);
        JsonNode raw = row == null ? null : row.get(field); if (!finiteNumber(raw) || !Double.isFinite(threshold)) return false;
        double value = raw.asDouble(); return switch (op) {
            case ">" -> value > threshold; case ">=" -> value >= threshold; case "<" -> value < threshold;
            case "<=" -> value <= threshold; case "==" -> value == threshold; default -> false;
        };
    }

    private static boolean stressNotApplicable(JsonNode trade, String name) {
        String instrument = firstNonEmpty(text(trade, "instrument_type"), at(trade, "instrument", "instrument_type").asText("spot"))
                .toUpperCase(Locale.ROOT); JsonNode evidence = at(trade, "scenario_inputs", name);
        return Set.of("EXPIRY", "LIQUIDATION").contains(name) && "SPOT".equals(instrument)
                && "NOT_APPLICABLE".equals(text(evidence, "status")) && bool(evidence, "not_applicable")
                && !bool(evidence, "applied") && number(evidence, "debit_r", 0) == 0
                && !text(evidence, "reason").isEmpty() && isHash(text(evidence, "source_sha256"));
    }

    private static ObjectNode githubSettingsPolicy(JsonNode value) {
        ObjectNode result = object(); JsonNode claims = value == null ? null : value.get("oidc_claims");
        for (String field : List.of("repository", "repository_id", "evidence_branch", "repository_private",
                "repository_visibility", "repository_visibility_verified", "branch_protection", "rulesets",
                "environment_protection", "actions_permissions", "actions_secret", "settings_token_secret",
                "settings_token_identity", "settings_auditor_installation", "oidc_signature_verified", "oidc_subject_restricted")) {
            result.set(field, nullToNull(value == null ? null : value.get(field)));
        }
        if (!defined(claims)) result.putNull("oidc"); else {
            ObjectNode oidc = object(); for (String field : List.of("repository_id", "repository_owner_id", "environment",
                    "workflow_ref", "workflow_sha", "sub", "aud", "iss")) oidc.set(field, nullToNull(claims.get(field)));
            result.set("oidc", oidc);
        }
        return result;
    }

    private static ObjectNode githubApiPolicy(JsonNode value) {
        ObjectNode result = object(); JsonNode endpoints = value == null ? null : value.get("endpoints");
        if (endpoints != null && endpoints.isObject()) {
            TreeMap<String, JsonNode> rows = new TreeMap<>(); endpoints.fields().forEachRemaining(e -> rows.put(e.getKey(), e.getValue()));
            rows.forEach((key, row) -> { ObjectNode entry = object(); entry.put("status", integer(row.get("status"), 0));
                if (Set.of("branch_head", "oidc_subject_restriction").contains(key)) entry.putNull("body_sha256");
                else entry.set("body_sha256", nullToNull(row.get("body_sha256"))); result.set(key, entry); });
        }
        return result;
    }

    private static boolean validEvidence(JsonNode evidence, boolean requirePath) {
        if (evidence == null || !evidence.isObject() || bool(evidence, "invalid") || !defined(evidence.get("value"))) return false;
        if (!isHash(text(evidence, "content_sha256")) || !text(evidence, "content_sha256").equals(text(evidence.path("value"), "content_sha256"))) return false;
        if (requirePath || defined(evidence.get("path"))) {
            String raw = text(evidence, "path"); if (raw.isEmpty() || !isHash(text(evidence, "byte_sha256"))) return false;
            Path path = Path.of(raw).toAbsolutePath().normalize(); if (!singleRegularFile(path)) return false;
            if (!text(evidence, "byte_sha256").equals(fileHash(path))) return false;
            JsonNode physical = readJson(path); if (!stable(physical).equals(stable(evidence.get("value")))) return false;
        }
        return true;
    }

    private static ObjectNode readDeploymentEvidence(JsonNode reference) {
        ObjectNode ref = reference != null && reference.isTextual() ? object().put("path", reference.asText()) : objectOrNull(reference);
        if (ref == null || text(ref, "path").isEmpty()) return null;
        ObjectNode result = object(); Path path = Path.of(text(ref, "path")).toAbsolutePath().normalize(); result.put("path", path.toString());
        try {
            if (!singleRegularFile(path)) { result.put("invalid", true); return result; }
            byte[] bytes = Files.readAllBytes(path); String byteHash = hash(bytes);
            if (defined(ref.get("byte_sha256")) && !byteHash.equals(text(ref, "byte_sha256"))) { result.put("invalid", true); return result; }
            JsonNode value = JSON.readTree(bytes); if (value == null || !value.isObject()) { result.put("invalid", true); return result; }
            if (defined(value.get("content_sha256")) && !ownHash(value).equals(text(value, "content_sha256"))) { result.put("invalid", true); return result; }
            if (defined(ref.get("content_sha256")) && !text(ref, "content_sha256").equals(text(value, "content_sha256"))) { result.put("invalid", true); return result; }
            if ("ACTIVE".equals(text(value, "status").toUpperCase(Locale.ROOT))) { result.put("invalid", true); return result; }
            result.set("value", value); result.put("byte_sha256", byteHash); result.set("content_sha256",
                    defined(value.get("content_sha256")) ? value.get("content_sha256") : NullNode.instance); return result;
        } catch (Exception error) { result.removeAll(); result.put("invalid", true); return result; }
    }

    private static ObjectNode deploymentArtifact(JsonNode settings, List<String> names) {
        JsonNode artifacts = settings == null ? null : settings.get("artifacts");
        for (String name : names) {
            JsonNode value = settings == null ? null : settings.get(name);
            if (!defined(value) && artifacts != null) value = artifacts.get(name);
            if (defined(value)) return readDeploymentEvidence(value);
        }
        return null;
    }

    private static PublicKey publicEd25519(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", ""); byte[] bytes = Base64.getDecoder().decode(body);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
            return "EdDSA".equalsIgnoreCase(key.getAlgorithm()) || "Ed25519".equalsIgnoreCase(key.getAlgorithm()) ? key : null;
        } catch (Exception ignored) { return null; }
    }

    private static boolean verifyOfflineTrustRoot(JsonNode trustRoot, String pin, String genesisPin,
                                                   JsonNode previousRoot, JsonNode nowAt) {
        if (!defined(trustRoot) || !"strategy-prospective-trust-root/1".equals(text(trustRoot, "schema"))
                || pin.isEmpty() || genesisPin.isEmpty()) return false;
        try {
            ObjectNode options = object(); options.put("pinnedTrustRootFingerprint", pin);
            options.put("pinnedTrustRootGenesisFingerprint", genesisPin);
            options.set("previousTrustRoot", nullToNull(previousRoot)); options.set("nowAt", nullToNull(nowAt));
            return StrategyProspectiveV5.verifyTrustRoot(trustRoot, options);
        } catch (RuntimeException error) { return false; }
    }

    private static boolean approvalBoundToRoot(JsonNode approval, JsonNode trustRoot, boolean rootVerified) {
        if (!rootVerified || publicEd25519(text(approval, "public_key_pem")) == null
                || !Set.of("ASSET", "PORTFOLIO").contains(text(approval, "role"))
                || text(approval, "key_id").isEmpty() || text(approval, "trust_root_signature").isEmpty()) return false;
        JsonNode delegated = null; String role = text(approval, "role").toLowerCase(Locale.ROOT);
        for (JsonNode row : arrayOrEmpty(trustRoot.get("delegations"))) if (role.equals(text(row, "role"))
                && text(approval, "key_id").equals(text(row, "key_id"))
                && text(approval, "public_key_pem").equals(text(row, "public_key_pem"))) { delegated = row; break; }
        if (delegated == null || containsText(trustRoot.path("revoked_key_ids"), text(approval, "key_id"))) return false;
        ObjectNode payload = object(); payload.put("role", text(approval, "role")); payload.put("key_id", text(approval, "key_id"));
        payload.put("public_key_sha256", hash(text(approval, "public_key_pem")));
        return verifySignature(publicEd25519(text(trustRoot, "root_public_key_pem")), stable(payload), text(approval, "trust_root_signature"));
    }

    private static boolean containsApproval(List<JsonNode> approvals, String role, String pem) {
        for (JsonNode approval : approvals) if (role.equals(text(approval, "role")) && pem.equals(text(approval, "public_key_pem"))) return true;
        return false;
    }

    private static boolean containsApproval(JsonNode approvals, String role, String pem) {
        if (approvals != null && approvals.isArray()) for (JsonNode approval : approvals) {
            if (role.equals(text(approval, "role")) && pem.equals(text(approval, "public_key_pem"))) return true;
        }
        return false;
    }

    private static boolean verifySignature(PublicKey key, String payload, String base64Signature) {
        if (key == null) return false;
        try { Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8)); return verifier.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception ignored) { return false; }
    }

    private static void verifyEd25519Approval(JsonNode approval, String pem, String payload) {
        PublicKey key = publicEd25519(pem);
        if (!verifySignature(key, payload, text(approval, "signature"))) {
            throw failure("prospective approval signature verification failed");
        }
    }

    private record PublicationInventory(Set<Path> owned, Set<Path> committed) {}
    private record PublicationJournal(ObjectNode value, Path path) {}

    private static PublicationInventory publicationInventory(Path root) {
        root = root.toAbsolutePath().normalize(); Set<Path> owned = new HashSet<>(), committed = new HashSet<>();
        List<PublicationJournal> journals = new ArrayList<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new PublicationInventory(owned, committed);
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && candidate.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String relative = portable(root.relativize(path)); List<String> components = List.of(relative.split("/"));
                if (components.stream().anyMatch(part -> part.equals("stage") || part.endsWith(".stage") || part.endsWith(".lock"))) continue;
                boolean transactionDirectory = components.stream().anyMatch(
                        part -> part.equals("transactions") || part.equals(".transactions"));
                JsonNode value; try { value = readJson(path); } catch (RuntimeException error) {
                    if (transactionDirectory) throw failure("publication transaction journal is unreadable: " + path); continue;
                }
                if (!"strategy-v5-statistical-publication-transaction/1".equals(text(value, "schema"))) {
                    if (transactionDirectory) throw failure("unexpected JSON control file under publication transaction directory: " + path);
                    continue;
                }
                if (!singleRegularFile(path) || !relative.equals(text(value, "transaction_path"))) {
                    throw failure("publication transaction journal is not verifiable: " + path);
                }
                try { StrategyStatisticalV5.validateContractSchema(value); }
                catch (RuntimeException error) {
                    throw failure("publication transaction journal is not verifiable: " + path + ": " + rootMessage(error));
                }
                journals.add(new PublicationJournal((ObjectNode) value, path.toAbsolutePath().normalize()));
            }
        } catch (IOException error) { throw failure(error.getMessage()); }
        for (PublicationJournal journal : journals) {
            ObjectNode verified = null;
            if ("COMMITTED".equals(text(journal.value(), "status"))) {
                try {
                    ObjectNode args = object(); args.set("journal", journal.value());
                    args.put("journalPath", journal.path().toString()); args.put("recordRoot", root.toString());
                    verified = StrategyStatisticalV5.verifyCommittedStatisticalPublication(args);
                } catch (RuntimeException error) {
                    throw failure("publication committed inventory is not verifiable: "
                            + text(journal.value(), "transaction_path") + ": " + rootMessage(error));
                }
            }
            for (JsonNode ref : arrayOrEmpty(journal.value().get("artifact_refs"))) {
                Path artifact = root.resolve(text(ref, "path")).toAbsolutePath().normalize();
                if (!artifact.startsWith(root)) throw failure("publication artifact path escapes the record root");
                owned.add(artifact);
                if (verified != null && artifact.toString().equals(
                        text(verified.path("artifactPaths"), text(ref, "role")))) committed.add(artifact);
            }
        }
        return new PublicationInventory(owned, committed);
    }

    private static boolean publicationControlPath(String relative) {
        for (String part : relative.replace('\\', '/').split("/")) if (part.equals("transactions")
                || part.equals(".transactions") || part.equals("stage") || part.endsWith(".stage") || part.endsWith(".lock")) return true;
        return false;
    }
}
