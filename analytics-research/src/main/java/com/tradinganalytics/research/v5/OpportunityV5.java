package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Exact Java port of {@code tools/strategy-v5-opportunity.mjs}.
 *
 * <p>The public methods intentionally accept and return JSON trees. The Node
 * contract is an artifact protocol rather than an application entity model;
 * retaining its exact null, field-order, and content-hash behavior makes the
 * Java implementation byte-for-byte differential-testable while keeping all
 * filesystem access behind bounded, content-addressed partitions.</p>
 */
public final class OpportunityV5 {
    public static final String OPPORTUNITY_SCHEMA = "strategy-v5-opportunity-envelope/2";
    public static final String HYDRATION_SCHEMA = "strategy-v5-opportunity-hydration/2";
    public static final String OPPORTUNITY_DOMAIN_SCHEMA = "strategy-v5-opportunity-domain/1";

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern HASH_RE = Pattern.compile("^[a-f0-9]{64}$");
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> LABEL_KEYS = Set.of(
            "label", "target", "outcome", "forward_return", "future_return", "forward_pnl", "future_pnl",
            "net_r", "gross_r", "exit_price", "exit_time", "resolved_at", "resolution_time");
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "(^|_)(future|forward|realized|resolved|outcome|label|target|settled)(_|$)");
    private static final Pattern TRADE_LABEL_PATTERN = Pattern.compile("(^|_)(trade_pnl|exit_price|exit_time)(_|$)");

    private OpportunityV5() {}

    /** Mirrors the exported Node hash helper for JSON values. */
    public static String hash(JsonNode value) {
        return JsonHashes.canonicalSha256(value);
    }

    /** Mirrors the exported Node hash helper for strings. */
    public static String hash(String value) {
        return JsonHashes.sha256(value);
    }

    /** Mirrors the exported Node hash helper for Buffer values. */
    public static String hash(byte[] value) {
        return JsonHashes.sha256(value);
    }

    public static ObjectNode makeOpportunityDomainV5(ObjectNode options) {
        ObjectNode sourceOptions = options == null ? object() : options;
        JsonNode candidateSet = field(sourceOptions, "candidateSet");
        JsonNode branchesOption = field(sourceOptions, "branches");
        JsonNode fixtureValue = defined(field(sourceOptions, "fixtureOnly"))
                ? field(sourceOptions, "fixtureOnly") : JSON.booleanNode(false);
        boolean fixtureTruthy = truthy(fixtureValue);
        boolean fixtureExact = exactTrue(fixtureValue);
        JsonNode domainCompleteValue = defined(field(sourceOptions, "domain_complete"))
                ? field(sourceOptions, "domain_complete") : JSON.booleanNode(true);

        String precommitHash = bindDomainArtifact(
                field(sourceOptions, "precommit"),
                truthyFirst(field(sourceOptions, "precommitSha256"), field(sourceOptions, "precommit_sha256")),
                "precommit");
        String geneSpaceHash = bindDomainArtifact(
                field(sourceOptions, "geneSpace"),
                truthyFirst(field(sourceOptions, "geneSpaceSha256"), field(sourceOptions, "gene_space_sha256")),
                "gene space");
        String evaluatorHash = bindDomainArtifact(
                field(sourceOptions, "evaluatorSpec"),
                truthyFirst(field(sourceOptions, "evaluatorSpecSha256"), field(sourceOptions, "evaluator_spec_sha256")),
                "evaluator spec");
        String registryHash = bindDomainArtifact(
                field(sourceOptions, "predictorRegistry"),
                truthyFirst(field(sourceOptions, "predictorRegistrySha256"), field(sourceOptions, "predictor_registry_sha256")),
                "predictor registry");

        String candidateSetHash = null;
        if (truthy(candidateSet)) {
            if (!"strategy-candidate-set/5".equals(text(field(candidateSet, "schema")))
                    || !text(field(candidateSet, "content_sha256")).equals(ownHash(candidateSet))) {
                throw failure("opportunity domain candidate set is not content-hash bound");
            }
            candidateSetHash = text(field(candidateSet, "content_sha256"));
        }

        ArrayNode branches;
        if (branchesOption != null && branchesOption.isArray() && !branchesOption.isEmpty()) {
            branches = (ArrayNode) branchesOption;
        } else {
            branches = array(field(candidateSet, "candidates"));
        }
        if (branches.isEmpty()) {
            throw failure("opportunity domain requires at least one complete structural branch");
        }
        if (!exactTrue(domainCompleteValue)) {
            throw failure("opportunity domain must explicitly declare domain_complete:true");
        }

        List<ObjectNode> normalized = new ArrayList<>();
        for (int index = 0; index < branches.size(); index++) {
            JsonNode branch = branches.get(index);
            JsonNode definition = truthy(field(branch, "definition")) ? field(branch, "definition") : branch;
            JsonNode predicate = normalizeCandidatePredicate(truthyFirst(
                    field(branch, "predicate"), field(definition, "signal_rule"), field(definition, "predicate")));
            String rawIdentity = truthyText(field(branch, "branch_id"), field(branch, "candidate_id"));
            if (!truthy(predicate)) {
                throw failure("opportunity domain branch " + (rawIdentity == null ? index : rawIdentity)
                        + " lacks a complete predicate");
            }
            rejectLabels(predicate, "opportunity domain branch " + index + ".predicate");
            String branchId = rawIdentity == null ? "branch-" + String.format(Locale.ROOT, "%06d", index + 1) : rawIdentity;
            String behaviorIdentity = rawIdentity == null ? "branch-" + (index + 1) : rawIdentity;
            ObjectNode behaviorInput = object();
            behaviorInput.put("branch_id", behaviorIdentity);
            behaviorInput.set("predicate", cloneNode(predicate));
            ObjectNode row = object();
            row.put("branch_id", branchId);
            if (!defined(field(branch, "candidate_id"))) row.set("candidate_id", NullNode.instance);
            else row.put("candidate_id", jsString(field(branch, "candidate_id")));
            row.set("predicate", cloneNode(predicate));
            row.put("behavior_sha256", truthy(field(branch, "behavior_sha256"))
                    ? jsString(field(branch, "behavior_sha256")) : hash(behaviorInput));
            row.put("definition_sha256", truthy(field(branch, "definition_sha256"))
                    ? jsString(field(branch, "definition_sha256")) : hash(definition));
            normalized.add(row);
        }
        normalized.sort(Comparator.comparing(row -> row.path("branch_id").asText()));
        Set<String> identities = new HashSet<>();
        for (ObjectNode row : normalized) {
            if (!identities.add(row.path("branch_id").asText())) {
                throw failure("opportunity domain branch id collision " + row.path("branch_id").asText());
            }
        }
        if (!fixtureTruthy) {
            boundHash(textOrNull(precommitHash), "precommit_sha256");
            boundHash(textOrNull(geneSpaceHash), "gene_space_sha256");
            boundHash(textOrNull(evaluatorHash), "evaluator_spec_sha256");
            boundHash(textOrNull(registryHash), "predictor_registry_sha256");
            boundHash(textOrNull(candidateSetHash), "candidate_set_sha256");
        }

        ObjectNode result = object();
        result.put("schema", OPPORTUNITY_DOMAIN_SCHEMA);
        result.put("version", 1);
        result.put("status", "FROZEN");
        result.put("fixture_only", fixtureExact);
        result.put("provenance", fixtureExact ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE");
        result.put("domain_complete", true);
        putNullable(result, "precommit_sha256", precommitHash);
        putNullable(result, "candidate_set_sha256", candidateSetHash);
        putNullable(result, "gene_space_sha256", geneSpaceHash);
        putNullable(result, "evaluator_spec_sha256", evaluatorHash);
        putNullable(result, "predictor_registry_sha256", registryHash);
        result.put("branch_count", normalized.size());
        ArrayNode normalizedRows = result.putArray("branches");
        normalized.forEach(normalizedRows::add);
        result.set("content_sha256", NullNode.instance);
        withHashInPlace(result);
        validateOpportunityDomainV5(result);
        return result;
    }

    public static boolean validateOpportunityDomainV5(JsonNode domain) {
        if (domain == null || !domain.isObject()
                || !OPPORTUNITY_DOMAIN_SCHEMA.equals(text(field(domain, "schema")))
                || jsNumber(field(domain, "version")) != 1
                || !"FROZEN".equals(text(field(domain, "status")))
                || !booleanValue(field(domain, "domain_complete"), false)) {
            throw failure("opportunity domain is not a complete frozen artifact");
        }
        if (!text(field(domain, "content_sha256")).equals(ownHash(domain))) {
            throw failure("opportunity domain hash is invalid");
        }
        ArrayNode branches = array(field(domain, "branches"));
        if (branches.isEmpty() || jsNumber(field(domain, "branch_count")) != branches.size()) {
            throw failure("opportunity domain branch accounting is invalid");
        }
        Set<String> identities = new HashSet<>();
        for (JsonNode branch : branches) {
            String identity = text(field(branch, "branch_id"));
            if (!truthy(branch) || !truthy(field(branch, "branch_id")) || !identities.add(identity)
                    || !truthy(field(branch, "predicate"))) {
                throw failure("opportunity domain has an invalid or duplicate branch");
            }
            rejectLabels(field(branch, "predicate"), "opportunity domain " + identity + ".predicate");
        }
        if (!booleanValue(field(domain, "fixture_only"), false)) {
            boundHash(field(domain, "precommit_sha256"), "precommit_sha256");
            boundHash(field(domain, "candidate_set_sha256"), "candidate_set_sha256");
            boundHash(field(domain, "gene_space_sha256"), "gene_space_sha256");
            boundHash(field(domain, "evaluator_spec_sha256"), "evaluator_spec_sha256");
            boundHash(field(domain, "predictor_registry_sha256"), "predictor_registry_sha256");
        }
        return true;
    }

    public static ObjectNode makeOpportunityEnvelopeV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        JsonNode rowsOption = defined(field(input, "rows")) ? field(input, "rows") : MAPPER.createArrayNode();
        JsonNode featureRows = defined(field(input, "featureRows")) ? field(input, "featureRows") : rowsOption;
        JsonNode candidates = defined(field(input, "candidates")) ? field(input, "candidates") : arrayNode();
        JsonNode candidateSet = field(input, "candidateSet");
        JsonNode geneSpace = field(input, "geneSpace");
        if (!truthy(geneSpace) && truthy(field(candidateSet, "gene_space"))) geneSpace = field(candidateSet, "gene_space");
        JsonNode fixtureValue = defined(field(input, "fixtureOnly"))
                ? field(input, "fixtureOnly") : JSON.booleanNode(false);
        boolean fixtureTruthy = truthy(fixtureValue);
        boolean fixtureExact = exactTrue(fixtureValue);
        boolean fullDomain = defined(field(input, "fullDomain")) && truthy(field(input, "fullDomain"));

        String planHash = bindEnvelopeArtifact(field(input, "plan"),
                truthyFirst(field(input, "planSha256"), field(input, "plan_sha256")), "plan", fixtureTruthy);
        String precommitHash = bindEnvelopeArtifact(field(input, "precommit"),
                truthyFirst(field(input, "precommitSha256"), field(input, "precommit_sha256")), "precommit", fixtureTruthy);
        String registryHash = bindEnvelopeArtifact(field(input, "predictorRegistry"),
                truthyFirst(field(input, "predictorRegistrySha256"), field(input, "predictor_registry_sha256")),
                "predictor registry", fixtureTruthy);
        String evaluatorHash = bindEnvelopeArtifact(field(input, "evaluatorSpec"),
                truthyFirst(field(input, "evaluatorSpecSha256"), field(input, "evaluator_spec_sha256")),
                "evaluator spec", fixtureTruthy);
        String graphHash = bindEnvelopeArtifact(field(input, "graph"),
                truthyFirst(field(input, "graphSha256"), field(input, "graph_sha256")), "graph", fixtureTruthy);
        String geneSpaceHash = bindEnvelopeArtifact(geneSpace,
                truthyFirst(field(input, "geneSpaceSha256"), field(input, "gene_space_sha256")), "gene space", fixtureTruthy);

        JsonNode originalPredicate = field(input, "predicate");
        rejectLabels(originalPredicate, "predicate");
        JsonNode domainArtifact = truthyFirst(field(input, "opportunityDomain"), field(input, "opportunity_domain"));
        if (!fixtureTruthy && (!truthy(candidateSet) || !truthy(domainArtifact))) {
            throw failure("production opportunity envelope requires the complete frozen candidate-set and opportunity-domain artifacts");
        }
        if (truthy(candidateSet)
                && !text(field(candidateSet, "content_sha256")).equals(ownHash(candidateSet))) {
            throw failure("candidate-set artifact is not content-hash bound");
        }
        if (truthy(domainArtifact)) {
            validateOpportunityDomainV5(domainArtifact);
            if (truthy(candidateSet) && !same(field(domainArtifact, "candidate_set_sha256"), field(candidateSet, "content_sha256")))
                throw failure("opportunity-domain candidate-set lineage does not match envelope");
            if (precommitHash != null && !precommitHash.equals(text(field(domainArtifact, "precommit_sha256"))))
                throw failure("opportunity-domain precommit lineage does not match envelope");
            if (geneSpaceHash != null && !geneSpaceHash.equals(text(field(domainArtifact, "gene_space_sha256"))))
                throw failure("opportunity-domain gene-space lineage does not match envelope");
            if (evaluatorHash != null && !evaluatorHash.equals(text(field(domainArtifact, "evaluator_spec_sha256"))))
                throw failure("opportunity-domain evaluator lineage does not match envelope");
            if (registryHash != null && !registryHash.equals(text(field(domainArtifact, "predictor_registry_sha256"))))
                throw failure("opportunity-domain predictor lineage does not match envelope");
        }

        List<TimedRow> source = normalizeRows(featureRows);
        JsonNode lifeNode = field(input, "max_lifecycle_ms");
        long life = truncate(!nullish(lifeNode) ? jsNumber(lifeNode)
                : jsNumber(truthy(field(input, "lifecycleDays")) ? field(input, "lifecycleDays") : JSON.numberNode(30)) * 86_400_000d);
        long interval = truncate(defined(field(input, "execution_interval_ms"))
                ? jsNumber(field(input, "execution_interval_ms")) : 60_000d);
        JsonNode warmNode = !nullish(field(input, "preentryWarmupBars"))
                ? field(input, "preentryWarmupBars") : field(input, "preentry_warmup_bars");
        long warmup = truncate(defined(warmNode) ? jsNumber(warmNode) : 0);
        if (!(life > 0) || !(interval > 0) || life % interval != 0 || !(warmup >= 0)) {
            throw failure("lifecycle and pre-entry warmup must be valid interval-aligned bounds");
        }

        JsonNode frozenPredicate = cloneNode(truthy(originalPredicate)
                ? originalPredicate : field(input, "opportunity_predicate"));
        rejectLabels(frozenPredicate, "opportunity_predicate");
        String boundGeneSpace = geneSpaceHash != null ? geneSpaceHash : textOrNull(field(geneSpace, "content_sha256"));
        validateFrozenBindings(precommitHash, registryHash, evaluatorHash, graphHash, boundGeneSpace,
                frozenPredicate, fixtureExact);
        List<String> predicateIds = new ArrayList<>();
        predicateIds(frozenPredicate, predicateIds);

        if (truthy(candidateSet) && !fixtureTruthy) {
            JsonNode lineage = truthy(field(candidateSet, "lineage")) ? field(candidateSet, "lineage") : object();
            String candidatePrecommit = textTruthyFirst(field(candidateSet, "precommit_sha256"), field(lineage, "precommit_sha256"));
            String candidateGeneSpace = textTruthyFirst(
                    field(candidateSet, "gene_space_sha256"), field(field(candidateSet, "gene_space"), "content_sha256"),
                    field(lineage, "gene_space_sha256"));
            String candidateEvaluator = textTruthyFirst(field(candidateSet, "evaluator_spec_sha256"), field(lineage, "evaluator_spec_sha256"));
            if (precommitHash == null || !precommitHash.equals(candidatePrecommit))
                throw failure("candidate-set precommit lineage does not match envelope");
            String geneContentHash = textOrNull(field(geneSpace, "content_sha256"));
            if (candidateGeneSpace == null || geneContentHash == null
                    || (!candidateGeneSpace.equals(boundGeneSpace) && !candidateGeneSpace.equals(geneContentHash)))
                throw failure("candidate-set gene-space lineage does not match envelope");
            if (candidateEvaluator != null && (evaluatorHash == null || !candidateEvaluator.equals(evaluatorHash)))
                throw failure("candidate-set evaluator lineage does not match envelope");
        }

        Long asOf = nullish(field(input, "asOf")) ? null : time(field(input, "asOf"));
        List<TimedRow> available = new ArrayList<>();
        for (TimedRow row : source) {
            if (isEligibleDecision(row, asOf)
                    && predicateMayTrigger(frozenPredicate, row.value(), geneSpace)) available.add(row);
        }
        Set<String> seen = new HashSet<>();
        ArrayNode windows = arrayNode();
        for (TimedRow timed : available) {
            ObjectNode row = timed.value();
            long decision = timed.time();
            String asset = jsString(or(field(row, "asset"), TextNode.valueOf(""))).toLowerCase(Locale.ROOT);
            String instrument = jsString(or(field(row, "instrument"), field(row, "instrument_type"), TextNode.valueOf("BINANCE_SPOT")));
            String symbol = jsString(or(field(row, "symbol"), TextNode.valueOf(asset.toUpperCase(Locale.ROOT) + "USDT")));
            String episodeId = !defined(field(row, "episode_id")) || field(row, "episode_id").isNull()
                    ? null : jsString(field(row, "episode_id"));
            String signalId = !defined(field(row, "signal_id")) || field(row, "signal_id").isNull()
                    ? null : jsString(field(row, "signal_id"));
            String key = asset + '|' + instrument + '|' + symbol + '|' + nullToEmpty(episodeId)
                    + '|' + nullToEmpty(signalId) + '|' + decision;
            if (!seen.add(key)) continue;
            ObjectNode identity = object();
            identity.put("asset", asset);
            identity.put("instrument", instrument);
            identity.put("symbol", symbol);
            putNullable(identity, "episode_id", episodeId);
            putNullable(identity, "signal_id", signalId);
            identity.put("decision", decision);
            ObjectNode window = object();
            window.put("window_id", "opp-" + hash(identity).substring(0, 20));
            window.put("asset", asset);
            window.put("instrument", instrument);
            window.put("symbol", symbol);
            putNullable(window, "episode_id", episodeId);
            putNullable(window, "signal_id", signalId);
            window.put("decision_time", iso(decision));
            window.put("preentry_start", iso(decision - warmup * interval));
            window.put("preentry_warmup_bars", warmup);
            window.put("execution_start", iso(decision));
            window.put("entry_time", iso(decision));
            window.put("execution_end", iso(decision + life));
            window.put("max_lifecycle_ms", life);
            window.put("lifecycle_timeframe", Math.round((double) interval / 1000d) + "s");
            window.put("candidate_subset_required", true);
            window.put("right_edge_terminal_policy", "UNRESOLVED_UNLESS_DECLARED_EXPIRY");
            window.put("source_row_sha256", hash(clean(row)));
            windows.add(window);
        }
        if (windows.isEmpty()) {
            throw failure("opportunity envelope has no physical decision boundaries in the provable predicate superset");
        }

        JsonNode domain;
        if (truthy(field(domainArtifact, "branches"))) domain = field(domainArtifact, "branches");
        else if (truthy(field(candidateSet, "structural_branches"))) domain = field(candidateSet, "structural_branches");
        else if (truthy(field(candidateSet, "behavior_domain"))) domain = field(candidateSet, "behavior_domain");
        else if (fullDomain) {
            ObjectNode full = object();
            full.put("candidate_id", "__FULL_MUTABLE_GENE_DOMAIN__");
            full.putObject("definition").set("predicate", cloneNode(frozenPredicate));
            domain = arrayNode().add(full);
        } else if (defined(field(candidateSet, "candidates"))) domain = field(candidateSet, "candidates");
        else domain = arrayNode();
        if (!fixtureTruthy && (!truthy(domainArtifact) || !domain.isArray() || domain.isEmpty())) {
            throw failure("production opportunity envelope requires a complete frozen structural/gene behavior domain");
        }
        JsonNode candidateRowsNode = truthy(domainArtifact) ? domain : truthy(candidateSet) ? domain : candidates;
        ArrayNode candidateRows = array(candidateRowsNode);
        ArrayNode candidateAudit = arrayNode();
        Set<String> windowKeys = new HashSet<>();
        for (JsonNode window : windows) {
            windowKeys.add(text(field(window, "asset")) + '|' + text(field(window, "instrument")) + '|'
                    + text(field(window, "symbol")) + '|' + nullToEmpty(textOrNull(field(window, "episode_id")))
                    + '|' + nullToEmpty(textOrNull(field(window, "signal_id"))) + '|'
                    + text(field(window, "decision_time")));
        }
        for (JsonNode candidate : candidateRows) {
            JsonNode definition = truthy(field(candidate, "definition")) ? field(candidate, "definition") : candidate;
            JsonNode candidatePredicate = normalizeCandidatePredicate(truthyFirst(
                    field(candidate, "predicate"), field(definition, "signal_rule"), field(definition, "predicate")));
            if (!truthy(candidatePredicate)) {
                if (!fixtureTruthy) throw failure("candidate behavior domain contains an unprovable branch predicate");
                continue;
            }
            ObjectNode genes = candidateGenes(candidate, geneSpace);
            for (TimedRow timed : source) {
                ObjectNode row = timed.value();
                if (!isEligibleDecision(timed, asOf)) continue;
                boolean triggers = predicateHasGene(candidatePredicate)
                        ? predicateMayTrigger(candidatePredicate, row, geneSpace)
                        : evaluatePredicate(candidatePredicate, row, genes);
                if (!triggers) continue;
                String asset = jsString(or(field(row, "asset"), TextNode.valueOf(""))).toLowerCase(Locale.ROOT);
                String instrument = jsString(or(field(row, "instrument"), field(row, "instrument_type"), TextNode.valueOf("BINANCE_SPOT")));
                String symbol = jsString(or(field(row, "symbol"), TextNode.valueOf(asset.toUpperCase(Locale.ROOT) + "USDT")));
                String episodeId = !defined(field(row, "episode_id")) || field(row, "episode_id").isNull()
                        ? null : jsString(field(row, "episode_id"));
                String signalId = !defined(field(row, "signal_id")) || field(row, "signal_id").isNull()
                        ? null : jsString(field(row, "signal_id"));
                String key = asset + '|' + instrument + '|' + symbol + '|' + nullToEmpty(episodeId)
                        + '|' + nullToEmpty(signalId) + '|' + iso(timed.time());
                if (!windowKeys.contains(key)) {
                    throw failure("candidate intent is not a subset of the frozen opportunity predicate");
                }
                ObjectNode audit = object();
                JsonNode candidateIdentity = truthyFirst(field(candidate, "candidate_id"), field(candidate, "branch_id"));
                if (truthy(candidateIdentity)) audit.set("candidate_id", cloneNode(candidateIdentity));
                else audit.set("candidate_id", NullNode.instance);
                putNullable(audit, "episode_id", episodeId);
                putNullable(audit, "signal_id", signalId);
                audit.put("decision_time", iso(timed.time()));
                audit.put("row_sha256", hash(clean(row)));
                candidateAudit.add(audit);
            }
        }
        long maxAuditRows = defined(field(input, "maxAuditRows")) ? truncate(jsNumber(field(input, "maxAuditRows"))) : 1_000_000;
        if (candidateAudit.size() > maxAuditRows) {
            throw failure("opportunity subset audit exceeds bounded row count");
        }
        String candidateDomainHash = hash(mapDefinitions(candidateRows));
        sortArray(candidateAudit, row -> jsString(field(row, "candidate_id")) + '|'
                + nullToEmpty(textOrNull(field(row, "episode_id"))) + '|'
                + nullToEmpty(textOrNull(field(row, "signal_id"))) + '|' + text(field(row, "decision_time")));
        ObjectNode subsetInput = object();
        subsetInput.set("frozen_predicate", cloneNode(frozenPredicate));
        subsetInput.set("candidate_audit", candidateAudit.deepCopy());
        subsetInput.put("candidate_domain_sha256", candidateDomainHash);
        putNullable(subsetInput, "candidate_space_sha256", textOrNull(field(geneSpace, "content_sha256")));
        String subsetAuditHash = hash(subsetInput);

        ObjectNode result = object();
        result.put("schema", OPPORTUNITY_SCHEMA);
        result.put("version", 2);
        result.put("status", "FROZEN");
        result.put("fixture_only", fixtureExact);
        result.put("provenance", fixtureExact ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE");
        putNullable(result, "plan_sha256", planHash);
        putNullable(result, "precommit_sha256", precommitHash);
        putNullable(result, "predictor_registry_sha256", registryHash);
        putNullable(result, "evaluator_spec_sha256", evaluatorHash);
        putNullable(result, "graph_sha256", graphHash);
        putNullable(result, "candidate_set_sha256", textOrNull(field(candidateSet, "content_sha256")));
        putNullable(result, "opportunity_domain_sha256", textOrNull(field(domainArtifact, "content_sha256")));
        result.put("max_lifecycle_ms", life);
        result.put("execution_interval_ms", interval);
        result.put("lifecycle_timeframe", Math.round((double) interval / 1000d) + "s");
        result.set("opportunity_predicate", cloneNode(frozenPredicate));
        ArrayNode uniqueIds = result.putArray("predicate_predictor_ids");
        predicateIds.stream().distinct().sorted().forEach(uniqueIds::add);
        result.put("predicate_semantics", "CONSERVATIVE_OR_SUPERSET_OVER_FULL_MUTABLE_GENE_SPACE");
        putNullable(result, "gene_space_sha256", geneSpaceHash != null ? geneSpaceHash : textOrNull(field(geneSpace, "content_sha256")));
        result.put("candidate_domain_sha256", candidateDomainHash);
        if (candidateRows.isEmpty()) result.set("predeclared_candidate_count", NullNode.instance);
        else result.put("predeclared_candidate_count", candidateRows.size());
        result.put("subset_audit_sha256", subsetAuditHash);
        result.put("subset_audit_count", candidateAudit.size());
        JsonNode explicitAssets = field(input, "assets");
        result.set("assets", truthy(explicitAssets) ? cloneNode(explicitAssets) : distinctSorted(windows, "asset"));
        JsonNode explicitInstruments = field(input, "instruments");
        result.set("instruments", truthy(explicitInstruments) ? cloneNode(explicitInstruments) : distinctSorted(windows, "instrument"));
        result.set("windows", windows);
        result.set("content_sha256", NullNode.instance);
        withHashInPlace(result);
        validateOpportunityEnvelopeV5(result);
        return result;
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode buildOpportunityEnvelopeV5(ObjectNode options) {
        return makeOpportunityEnvelopeV5(options);
    }

    public static boolean validateOpportunityEnvelopeV5(JsonNode envelope) {
        String schema = text(field(envelope, "schema"));
        if (envelope == null || !envelope.isObject()
                || !(OPPORTUNITY_SCHEMA.equals(schema) || "strategy-v5-opportunity-envelope/1".equals(schema))
                || !"FROZEN".equals(text(field(envelope, "status")))) {
            throw failure("opportunity envelope is not frozen");
        }
        if (!text(field(envelope, "content_sha256")).equals(ownHash(envelope))) {
            throw failure("opportunity envelope hash is invalid");
        }
        ArrayNode windows = array(field(envelope, "windows"));
        if (OPPORTUNITY_SCHEMA.equals(schema)) {
            if (field(envelope, "fixture_only") == null || !field(envelope, "fixture_only").isBoolean()
                    || field(envelope, "provenance") == null || !field(envelope, "provenance").isTextual()) {
                throw failure("v2 opportunity envelope fixture/provenance marker is required");
            }
            if (!booleanValue(field(envelope, "fixture_only"), false)) {
                boundHash(field(envelope, "plan_sha256"), "plan_sha256");
                boundHash(field(envelope, "precommit_sha256"), "precommit_sha256");
                boundHash(field(envelope, "predictor_registry_sha256"), "predictor_registry_sha256");
                boundHash(field(envelope, "evaluator_spec_sha256"), "evaluator_spec_sha256");
                boundHash(field(envelope, "gene_space_sha256"), "gene_space_sha256");
                boundHash(field(envelope, "candidate_set_sha256"), "candidate_set_sha256");
                boundHash(field(envelope, "opportunity_domain_sha256"), "opportunity_domain_sha256");
                if (windows.isEmpty()) throw failure("production opportunity envelope windows are missing");
                List<String> declaredAssets = uniqueArray(field(envelope, "assets"),
                        "opportunity envelope assets", value -> jsString(or(value, TextNode.valueOf(""))).toLowerCase(Locale.ROOT));
                List<String> declaredInstruments = uniqueArray(field(envelope, "instruments"),
                        "opportunity envelope instruments", value -> jsString(or(value, TextNode.valueOf(""))).toUpperCase(Locale.ROOT));
                List<String> windowAssets = distinctSortedList(windows, "asset", true);
                List<String> windowInstruments = distinctSortedList(windows, "instrument", false);
                if (declaredInstruments.size() != 1 || !declaredAssets.equals(windowAssets)
                        || !declaredInstruments.equals(windowInstruments)) {
                    throw failure("production opportunity envelope assets/instrument do not exactly match its windows");
                }
                ArrayNode episodes = arrayNode();
                windows.forEach(window -> episodes.add(cloneNode(field(window, "episode_id"))));
                uniqueArray(episodes, "opportunity envelope episode identities", OpportunityV5::jsString);
            }
        }
        double lifeNumber = jsNumber(field(envelope, "max_lifecycle_ms"));
        if (!Double.isFinite(lifeNumber) || lifeNumber != Math.rint(lifeNumber) || lifeNumber <= 0 || windows.isEmpty()) {
            throw failure("opportunity envelope lifecycle/windows are invalid");
        }
        long maxLife = (long) lifeNumber;
        Set<String> identities = new HashSet<>();
        for (JsonNode row : windows) {
            if (!identities.add(text(field(row, "window_id")))) throw failure("duplicate opportunity window");
            long start = time(field(row, "execution_start"));
            long end = time(field(row, "execution_end"));
            if ("strategy-v5-opportunity-envelope/1".equals(schema)) {
                if (end < start || end - start > maxLife) throw failure("legacy opportunity window lifecycle is invalid");
                continue;
            }
            if (time(field(row, "execution_start")) != time(field(row, "decision_time")))
                throw failure("opportunity window does not begin at exact decision boundary");
            JsonNode episode = field(row, "episode_id");
            if (defined(episode) && !episode.isNull() && (!episode.isTextual() || episode.textValue().isEmpty()))
                throw failure("opportunity window episode identity is invalid");
            JsonNode signal = field(row, "signal_id");
            if (defined(signal) && !signal.isNull() && (!signal.isTextual() || signal.textValue().isEmpty()))
                throw failure("opportunity window signal identity is invalid");
            if (end != time(field(row, "entry_time")) + maxLife)
                throw failure("opportunity window lifecycle endpoint is inconsistent with entry boundary");
            if (end <= time(field(row, "entry_time"))) throw failure("opportunity window ends before exposure");
        }
        return true;
    }

    public static ObjectNode assertCandidateIntentSubsetV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        JsonNode envelope = field(input, "envelope");
        JsonNode intent = field(input, "intent");
        JsonNode candidate = field(input, "candidate");
        JsonNode geneSpace = field(input, "geneSpace");
        JsonNode predicate = field(input, "predicate");
        validateOpportunityEnvelopeV5(envelope);
        rejectLabels(intent, "candidate intent");
        long decision = time(!nullish(field(intent, "decision_time"))
                ? field(intent, "decision_time") : field(intent, "event_time"));
        String asset = jsString(or(field(intent, "asset"), TextNode.valueOf(""))).toLowerCase(Locale.ROOT);
        String instrument = jsString(or(field(intent, "instrument"), field(intent, "instrument_type"), TextNode.valueOf("BINANCE_SPOT")));
        String symbol = jsString(or(field(intent, "symbol"), TextNode.valueOf(asset.toUpperCase(Locale.ROOT) + "USDT")));
        JsonNode matched = null;
        for (JsonNode window : array(field(envelope, "windows"))) {
            if (!asset.equals(text(field(window, "asset")))
                    || !instrument.equals(text(field(window, "instrument")))
                    || !symbol.equals(text(field(window, "symbol")))
                    || !optionalIdentityMatches(field(intent, "episode_id"), field(window, "episode_id"))
                    || !optionalIdentityMatches(field(intent, "signal_id"), field(window, "signal_id"))
                    || time(field(window, "decision_time")) != decision) continue;
            matched = window;
            break;
        }
        if (matched == null) throw failure("candidate intent is outside frozen opportunity superset");
        if (truthy(candidate) && truthy(predicate)) {
            JsonNode feature = truthy(field(intent, "feature")) ? field(intent, "feature") : intent;
            if (!evaluatePredicate(predicate, feature, candidateGenes(candidate, geneSpace)))
                throw failure("candidate intent does not satisfy its declared frozen branch");
        }
        JsonNode lifecycleNode = !nullish(field(intent, "max_lifecycle_ms")) ? field(intent, "max_lifecycle_ms")
                : !nullish(field(field(intent, "lifecycle"), "max_lifecycle_ms"))
                        ? field(field(intent, "lifecycle"), "max_lifecycle_ms") : field(envelope, "max_lifecycle_ms");
        if (jsNumber(lifecycleNode) > jsNumber(field(envelope, "max_lifecycle_ms")))
            throw failure("candidate lifecycle exceeds frozen envelope");
        ObjectNode result = object();
        result.put("subset", true);
        result.put("window_id", text(field(matched, "window_id")));
        result.put("decision_time", text(field(matched, "decision_time")));
        return result;
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode proveCandidateSubsetV5(ObjectNode options) {
        return assertCandidateIntentSubsetV5(options);
    }

    public static ObjectNode makeContentAddressedPartitionsV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        ArrayNode bars = array(defined(field(input, "bars")) ? field(input, "bars") : arrayNode());
        List<TimedRow> sorted = normalizeBars(bars);
        JsonNode partitionMsNode = defined(field(input, "partitionMs"))
                ? field(input, "partitionMs") : defined(field(input, "partition_ms"))
                        ? field(input, "partition_ms") : JSON.numberNode(86_400_000);
        long span = Math.max(60_000, truncate(jsNumber(partitionMsNode)));
        JsonNode assetNode = defined(field(input, "asset")) ? field(input, "asset") : NullNode.instance;
        JsonNode instrumentNode = defined(field(input, "instrument")) ? field(input, "instrument") : NullNode.instance;
        JsonNode symbolNode = defined(field(input, "symbol")) ? field(input, "symbol") : NullNode.instance;
        JsonNode fixtureValue = defined(field(input, "fixtureOnly"))
                ? field(input, "fixtureOnly") : JSON.booleanNode(true);
        boolean fixtureTruthy = truthy(fixtureValue);
        boolean fixtureExact = exactTrue(fixtureValue);
        JsonNode outputRootNode = field(input, "outputRoot");
        if (!fixtureTruthy && !truthy(outputRootNode)) {
            throw failure("production partition creation requires a physical outputRoot");
        }
        Path outputRoot = truthy(outputRootNode) ? Path.of(jsString(outputRootNode)).toAbsolutePath().normalize() : null;

        Map<Long, List<ObjectNode>> groups = new TreeMap<>();
        for (TimedRow row : sorted) {
            long bucket = Math.floorDiv(row.time(), span);
            groups.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(clean(row.value()));
        }
        ArrayNode partitions = arrayNode();
        for (Map.Entry<Long, List<ObjectNode>> group : groups.entrySet()) {
            long bucket = group.getKey();
            List<ObjectNode> rows = group.getValue();
            StringBuilder bodyBuilder = new StringBuilder();
            for (ObjectNode row : rows) bodyBuilder.append(jsonStringify(row)).append('\n');
            String body = bodyBuilder.toString();
            String sha = hash(body);
            Path path = outputRoot == null ? null : outputRoot.resolve(sha + ".jsonl");
            if (path != null) {
                try {
                    Files.createDirectories(outputRoot);
                    if (!Files.exists(path)) {
                        Files.writeString(path, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    } else if (!hash(Files.readAllBytes(path)).equals(sha)) {
                        throw failure("content-addressed partition collision");
                    }
                } catch (IOException error) {
                    throw failure(error.getMessage(), error);
                }
            }
            ObjectNode first = rows.getFirst();
            String idAsset = truthy(assetNode) ? jsString(assetNode)
                    : truthy(field(first, "asset")) ? jsString(field(first, "asset")) : "asset";
            String idInstrument = truthy(instrumentNode) ? jsString(instrumentNode)
                    : truthy(field(first, "instrument")) ? jsString(field(first, "instrument")) : "instrument";
            String idSymbol = truthy(symbolNode) ? jsString(symbolNode)
                    : truthy(field(first, "symbol")) ? jsString(field(first, "symbol")) : "symbol";
            ObjectNode partition = object();
            partition.put("partition_id", idAsset + '-' + idInstrument + '-' + idSymbol + '-' + bucket);
            partition.put("sha256", sha);
            partition.put("bytes", body.getBytes(StandardCharsets.UTF_8).length);
            partition.put("row_count", rows.size());
            partition.put("min_event_time", iso(barTime(rows.getFirst())));
            partition.put("max_event_time", iso(barTime(rows.getLast())));
            partition.put("format", "JSONL_1M");
            if (fixtureTruthy) partition.put("body", body);
            else partition.put("path", path.toString());
            partition.set("asset", cloneNode(assetNode));
            partition.set("instrument", cloneNode(instrumentNode));
            partition.set("symbol", cloneNode(symbolNode));
            partitions.add(partition);
        }
        if (partitions.isEmpty()) throw failure("execution partition set cannot be empty");
        ArrayNode rootInput = arrayNode();
        for (JsonNode partition : partitions) {
            ObjectNode row = object();
            for (String field : List.of("partition_id", "sha256", "bytes", "row_count", "min_event_time",
                    "max_event_time", "format", "asset", "instrument", "symbol")) {
                row.set(field, cloneNode(field(partition, field)));
            }
            rootInput.add(row);
        }
        ObjectNode result = object();
        result.put("schema", "strategy-v5-execution-partition-set/1");
        result.put("version", 1);
        result.put("status", "FROZEN");
        result.put("fixture_only", fixtureExact);
        result.put("provenance", fixtureExact ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE");
        result.put("partition_count", partitions.size());
        result.put("partition_bytes_root_sha256", hash(rootInput));
        result.set("partitions", partitions);
        result.set("content_sha256", NullNode.instance);
        return withHashInPlace(result);
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode normalizeExecutionPartitionsV5(ObjectNode options) {
        return makeContentAddressedPartitionsV5(options);
    }

    public static ObjectNode hydrateOpportunityEnvelopeV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        JsonNode envelope = field(input, "envelope");
        validateOpportunityEnvelopeV5(envelope);
        long batchSize = optionLong(input, "batchSize", 4_096);
        long maxRows = optionLong(input, "maxRows", 10_000_000);
        long maxPartitionBytes = optionLong(input, "maxPartitionBytes", 512L * 1024 * 1024);
        long maxTotalBytes = optionLong(input, "maxTotalBytes", 2L * 1024 * 1024 * 1024);
        long maxResidentBytes = optionLong(input, "maxResidentBytes", 192L * 1024 * 1024);
        long maxIndexedPartitions = optionLong(input, "maxIndexedPartitions", 100_000);
        long maxUniqueRows = optionLong(input, "maxUniqueRows", 1_000_000);
        long maxAuditRows = optionLong(input, "maxAuditRows", 1_000_000);
        JsonNode fixtureValue = defined(field(input, "fixtureOnly"))
                ? field(input, "fixtureOnly") : JSON.booleanNode(true);
        boolean fixtureTruthy = truthy(fixtureValue);
        boolean fixtureExact = exactTrue(fixtureValue);
        if (!(maxResidentBytes > 0) || !(maxTotalBytes > 0) || !(maxIndexedPartitions > 0)
                || !(maxUniqueRows > 0) || !(maxAuditRows > 0)) {
            throw failure("hydration resident/aggregate/index byte and row bounds must be positive");
        }

        ArrayNode suppliedPartitions = array(field(input, "partitions"));
        ArrayNode physical;
        if (!suppliedPartitions.isEmpty()) {
            physical = suppliedPartitions;
        } else if (truthy(field(input, "bars"))) {
            ObjectNode partitionOptions = object();
            partitionOptions.set("bars", cloneNode(field(input, "bars")));
            partitionOptions.put("fixtureOnly", true);
            physical = (ArrayNode) makeContentAddressedPartitionsV5(partitionOptions).path("partitions");
        } else {
            physical = arrayNode();
        }
        ArrayNode suppliedMarks = array(field(input, "markPartitions"));
        ArrayNode physicalMarks = arrayNode();
        if (!suppliedMarks.isEmpty()) {
            suppliedMarks.forEach(physicalMarks::add);
        } else {
            for (JsonNode partition : physical) {
                String role = jsString(or(field(partition, "series_role"), field(partition, "series_type"), TextNode.valueOf("")))
                        .toUpperCase(Locale.ROOT);
                if ("MARK".equals(role)) physicalMarks.add(partition);
            }
        }
        if (physical.isEmpty()) throw failure("hydration requires normalized physical partitions");
        if ((long) physical.size() + physicalMarks.size() > maxIndexedPartitions)
            throw failure("hydration partition metadata index exceeds bounded partition count");

        List<IndexedPartition> indexed = indexPartitions(physical, fixtureTruthy, maxPartitionBytes);
        List<IndexedPartition> indexedMarks = indexPartitions(physicalMarks, fixtureTruthy, maxPartitionBytes);
        HydrationState state = new HydrationState(indexed, indexedMarks, maxResidentBytes, maxPartitionBytes);
        ArrayNode expiryTerminals = array(field(input, "expiryTerminals"));
        ArrayNode captures = arrayNode();
        Set<String> uniquePhysical = new HashSet<>();
        Set<String> touchedPartitions = new HashSet<>();
        long declaredBytes = 0;
        long declaredRows = 0;

        for (JsonNode window : array(field(envelope, "windows"))) {
            long start = time(field(window, "entry_time"));
            long end = time(field(window, "execution_end"));
            long interval = truthy(field(envelope, "execution_interval_ms"))
                    ? truncate(jsNumber(field(envelope, "execution_interval_ms"))) : 60_000;
            ArrayNode refs = arrayNode();
            ArrayNode warmupRefs = arrayNode();
            boolean complete = true;

            long warmupBars = Math.max(0, truncate(truthy(field(window, "preentry_warmup_bars"))
                    ? jsNumber(field(window, "preentry_warmup_bars")) : 0));
            long warmupStart = time(truthy(field(window, "preentry_start"))
                    ? field(window, "preentry_start") : JSON.numberNode(start));
            if (warmupBars > 0 && warmupStart != start - warmupBars * interval)
                throw failure("pre-entry warmup boundary is inconsistent");
            Map<Long, TimedRow> warmupByTime = new TreeMap<>();
            if (warmupBars > 0) {
                for (IndexedPartition entry : indexed) {
                    if (entry.max < warmupStart || entry.min >= start) continue;
                    long[] totals = touchPartition(entry, touchedPartitions, declaredBytes, declaredRows,
                            maxTotalBytes, maxRows);
                    declaredBytes = totals[0];
                    declaredRows = totals[1];
                    List<TimedRow> selected = select(state.load(entry), warmupStart, start);
                    if (selected.isEmpty()) continue;
                    for (TimedRow row : selected) {
                        TimedRow prior = warmupByTime.get(row.time());
                        if (prior != null && !canonicalEquals(clean(prior.value()), clean(row.value())))
                            throw failure("overlapping warmup partitions disagree at a timestamp");
                        if (prior == null) warmupByTime.put(row.time(), row);
                        addUniquePhysical(uniquePhysical, entry.sha() + '|' + row.time(), maxUniqueRows);
                    }
                    warmupRefs.add(partitionReference(entry, selected, start, interval));
                }
            }
            if (warmupBars > 0) {
                List<TimedRow> rows = new ArrayList<>(warmupByTime.values());
                boolean dense = rows.size() == warmupBars;
                for (int index = 0; dense && index < rows.size(); index++)
                    dense = rows.get(index).time() == warmupStart + index * interval;
                if (!dense) throw failure("pre-entry warmup coverage is incomplete or non-contiguous");
            }

            Map<Long, TimedRow> selectedByTime = new TreeMap<>();
            for (IndexedPartition entry : indexed) {
                if (entry.max < start || entry.min >= end) continue;
                long[] totals = touchPartition(entry, touchedPartitions, declaredBytes, declaredRows,
                        maxTotalBytes, maxRows);
                declaredBytes = totals[0];
                declaredRows = totals[1];
                List<TimedRow> selected = select(state.load(entry), start, end);
                if (selected.isEmpty()) continue;
                for (TimedRow row : selected) {
                    TimedRow prior = selectedByTime.get(row.time());
                    if (prior != null && !canonicalEquals(clean(prior.value()), clean(row.value())))
                        throw failure("overlapping physical partitions disagree at a timestamp");
                    if (prior == null) selectedByTime.put(row.time(), row);
                    addUniquePhysical(uniquePhysical, entry.sha() + '|' + row.time(), maxUniqueRows);
                }
                refs.add(partitionReference(entry, selected, end, interval));
            }
            List<TimedRow> selectedAll = new ArrayList<>(selectedByTime.values());
            long expected = Math.max(1, ceilDiv(end - start, interval));
            boolean contiguous = true;
            for (int index = 0; index < selectedAll.size(); index++)
                if (selectedAll.get(index).time() != start + (long) index * interval) contiguous = false;
            JsonNode expiry = null;
            for (JsonNode row : expiryTerminals) {
                if (same(field(row, "window_id"), field(window, "window_id"))) {
                    expiry = row;
                    break;
                }
            }
            Long terminal = expiry == null ? null : time(!nullish(field(expiry, "terminal_time"))
                    ? field(expiry, "terminal_time") : field(expiry, "expiry_time"));
            if (terminal != null && (terminal < start || terminal > end))
                throw failure("declared expiry terminal is outside the lifecycle boundary");
            boolean terminalAtBoundary = terminal != null && terminal == end;
            Long terminalExpected = terminal == null ? null : terminalAtBoundary
                    ? Math.floorDiv(terminal - start, interval)
                    : Math.floorDiv(terminal - start, interval) + 1;
            long effectiveEnd = terminal == null ? end : terminalAtBoundary ? end : terminal + interval;
            if (!contiguous && selectedAll.size() < expected)
                throw failure("hydrated execution range has an interior gap or wrong start");
            if (selectedAll.size() < expected) {
                boolean terminalShape = contiguous && terminalExpected != null
                        && selectedAll.size() == terminalExpected
                        && !selectedAll.isEmpty()
                        && selectedAll.getLast().time() == (terminalAtBoundary ? terminal - interval : terminal);
                if (!terminalShape) complete = false;
            } else if (selectedAll.size() > expected || !contiguous) {
                throw failure("hydrated execution range is not dense/contiguous");
            }

            ArrayNode markRefs = arrayNode();
            int markCount = 0;
            boolean markComplete = true;
            if (!"BINANCE_SPOT".equals(jsString(or(field(window, "instrument"), TextNode.valueOf("")))
                    .toUpperCase(Locale.ROOT))) {
                if (indexedMarks.isEmpty())
                    throw failure("derivative v2 hydration requires a separately bound mark partition set");
                Map<Long, TimedRow> markByTime = new TreeMap<>();
                for (IndexedPartition entry : indexedMarks) {
                    if (entry.max < start || entry.min >= end) continue;
                    long[] totals = touchPartition(entry, touchedPartitions, declaredBytes, declaredRows,
                            maxTotalBytes, maxRows);
                    declaredBytes = totals[0];
                    declaredRows = totals[1];
                    List<TimedRow> selected = select(state.load(entry), start, end);
                    if (selected.isEmpty()) continue;
                    for (TimedRow row : selected) {
                        TimedRow prior = markByTime.get(row.time());
                        if (prior != null && !canonicalEquals(clean(prior.value()), clean(row.value())))
                            throw failure("overlapping mark partitions disagree at a timestamp");
                        if (prior == null) markByTime.put(row.time(), row);
                    }
                    markRefs.add(partitionReference(entry, selected, end, interval));
                }
                List<TimedRow> markRows = new ArrayList<>(markByTime.values());
                markCount = markRows.size();
                markComplete = markRows.size() == expected;
                for (int index = 0; markComplete && index < markRows.size(); index++)
                    markComplete = markRows.get(index).time() == start + (long) index * interval;
                if (!markComplete) throw failure("derivative v2 mark range is incomplete or non-contiguous");
            }

            ObjectNode capture = object();
            capture.set("window_id", cloneNode(field(window, "window_id")));
            capture.set("execution_start", cloneNode(field(window, "execution_start")));
            capture.set("hydration_start", cloneNode(field(window, "entry_time")));
            capture.set("preentry_start", truthy(field(window, "preentry_start"))
                    ? cloneNode(field(window, "preentry_start")) : NullNode.instance);
            capture.put("preentry_warmup_bars", warmupBars);
            capture.set("preentry_partition_refs", warmupRefs);
            capture.set("execution_end", cloneNode(field(window, "execution_end")));
            capture.put("effective_end_exclusive", iso(effectiveEnd));
            putNullable(capture, "terminal_time", terminal == null ? null : iso(terminal));
            capture.set("partition_refs", refs);
            capture.put("row_count", selectedAll.size());
            capture.set("mark_partition_refs", markRefs);
            capture.put("mark_row_count", markCount);
            capture.put("mark_complete", markComplete);
            capture.put("lifecycle_status", complete ? "COMPLETE" : "UNRESOLVED_RIGHT_EDGE");
            capture.put("eligible", complete);
            captures.add(capture);
        }

        ArrayNode inventory = arrayNode();
        List<IndexedPartition> combined = new ArrayList<>(indexed);
        combined.addAll(indexedMarks);
        for (IndexedPartition entry : combined) {
            JsonNode partition = entry.partition;
            ObjectNode row = object();
            row.put("partition_sha256", entry.sha());
            putNullable(row, "partition_path", partitionPath(partition));
            row.put("bytes", truncate(jsNumber(field(partition, "bytes"))));
            row.put("row_count", truncate(jsNumber(field(partition, "row_count"))));
            row.put("min_event_time", iso(entry.min));
            row.put("max_event_time", iso(entry.max));
            row.put("format", "JSONL_1M");
            copyOrNull(row, "asset", field(partition, "asset"));
            copyOrNull(row, "instrument", field(partition, "instrument"));
            copyOrNull(row, "symbol", field(partition, "symbol"));
            String role = jsString(or(field(partition, "series_role"), field(partition, "series_type"), TextNode.valueOf("")))
                    .toUpperCase(Locale.ROOT);
            row.put("series_role", "MARK".equals(role) ? "MARK" : "PRICE");
            inventory.add(row);
        }
        ArrayNode bytesRootRows = arrayNode();
        for (JsonNode inventoryRow : inventory) {
            ObjectNode row = object();
            for (String name : List.of("partition_sha256", "partition_path", "bytes", "row_count",
                    "min_event_time", "max_event_time", "asset", "instrument", "symbol", "series_role"))
                row.set(name, cloneNode(field(inventoryRow, name)));
            bytesRootRows.add(row);
        }
        sortArray(bytesRootRows, row -> text(field(row, "partition_sha256")));
        String partitionBytesRoot = hash(bytesRootRows);
        ArrayNode setHashes = arrayNode();
        physical.forEach(row -> setHashes.add(text(field(row, "sha256"))));
        physicalMarks.forEach(row -> setHashes.add(text(field(row, "sha256"))));
        sortTextArray(setHashes);

        long logicalReferenceRows = 0;
        for (JsonNode capture : captures) {
            for (JsonNode ref : array(field(capture, "partition_refs")))
                logicalReferenceRows += truncate(jsNumber(field(ref, "row_count")));
            for (JsonNode ref : array(field(capture, "mark_partition_refs")))
                logicalReferenceRows += truncate(jsNumber(field(ref, "row_count")));
        }
        ObjectNode result = object();
        result.put("schema", HYDRATION_SCHEMA);
        result.put("version", 2);
        result.put("status", "FROZEN");
        result.put("fixture_only", fixtureExact);
        result.put("provenance", fixtureExact ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE");
        result.set("envelope_sha256", cloneNode(field(envelope, "content_sha256")));
        result.set("max_lifecycle_ms", cloneNode(field(envelope, "max_lifecycle_ms")));
        result.put("execution_interval_ms", truthy(field(envelope, "execution_interval_ms"))
                ? truncate(jsNumber(field(envelope, "execution_interval_ms"))) : 60_000);
        result.put("partition_set_sha256", hash(setHashes));
        result.put("partition_bytes_root_sha256", partitionBytesRoot);
        result.set("partition_inventory", inventory);
        result.put("batch_size", Math.max(1, batchSize));
        result.put("max_rows", maxRows);
        result.put("max_resident_bytes", maxResidentBytes);
        result.put("peak_resident_bytes", state.peakResidentBytes);
        result.put("max_indexed_partitions", maxIndexedPartitions);
        result.put("max_unique_rows", maxUniqueRows);
        result.put("max_audit_rows", maxAuditRows);
        result.set("windows", captures);
        result.put("materialized_rows", uniquePhysical.size());
        result.put("logical_reference_rows", logicalReferenceRows);
        result.put("duplicate_nested_child_arrays", false);
        result.set("content_sha256", NullNode.instance);
        return withHashInPlace(result);
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode buildOpportunityHydrationV5(ObjectNode options) {
        return hydrateOpportunityEnvelopeV5(options);
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode hydrateExecutionEnvelopeV5(ObjectNode options) {
        return hydrateOpportunityEnvelopeV5(options);
    }

    public static ObjectNode readHydratedRangeV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        JsonNode hydration = field(input, "hydration");
        if (hydration == null || !HYDRATION_SCHEMA.equals(text(field(hydration, "schema")))
                || !text(field(hydration, "content_sha256")).equals(ownHash(hydration))) {
            throw failure("hydration artifact is invalid");
        }
        String windowId = text(field(input, "window_id"));
        JsonNode capture = null;
        for (JsonNode row : array(field(hydration, "windows"))) {
            if (windowId.equals(text(field(row, "window_id")))) {
                capture = row;
                break;
            }
        }
        if (capture == null) throw failure("unknown hydration window");
        ArrayNode partitions = array(field(input, "partitions"));
        Map<String, JsonNode> byHash = new LinkedHashMap<>();
        for (JsonNode partition : partitions) byHash.put(text(field(partition, "sha256")), partition);
        long lower = nullish(field(input, "start"))
                ? time(truthy(field(capture, "hydration_start")) ? field(capture, "hydration_start") : field(capture, "execution_start"))
                : time(field(input, "start"));
        long upper = nullish(field(input, "end"))
                ? time(truthy(field(capture, "effective_end_exclusive")) ? field(capture, "effective_end_exclusive") : field(capture, "execution_end"))
                : time(field(input, "end"));
        long maxRows = optionLong(input, "maxRows", 100_000);
        long maxPartitionBytes = optionLong(input, "maxPartitionBytes", 512L * 1024 * 1024);
        long maxResidentBytes = optionLong(input, "maxResidentBytes", 192L * 1024 * 1024);
        long maxOutputBytes = optionLong(input, "maxOutputBytes", 128L * 1024 * 1024);
        if (!(upper > lower) || !(maxResidentBytes > 0) || !(maxOutputBytes > 0))
            throw failure("lazy hydrated range bounds are invalid");
        String requestedRole = defined(field(input, "role")) ? jsString(field(input, "role")) : "PRICE";
        boolean mark = "MARK".equals(requestedRole.toUpperCase(Locale.ROOT));
        long entryTime = time(truthy(field(capture, "hydration_start"))
                ? field(capture, "hydration_start") : field(capture, "execution_start"));
        ArrayNode references = arrayNode();
        if (mark) {
            array(field(capture, "mark_partition_refs")).forEach(references::add);
        } else if (lower < entryTime) {
            array(field(capture, "preentry_partition_refs")).forEach(references::add);
            array(field(capture, "partition_refs")).forEach(references::add);
        } else {
            array(field(capture, "partition_refs")).forEach(references::add);
        }

        Map<Long, ObjectNode> outputByTime = new TreeMap<>();
        long outputBytes = 0;
        for (JsonNode ref : references) {
            String sha = text(field(ref, "partition_sha256"));
            JsonNode partition = byHash.get(sha);
            if (partition == null) throw failure("missing physical partition " + sha);
            double rowCount = jsNumber(field(partition, "row_count"));
            if (!HASH_RE.matcher(text(field(partition, "sha256"))).matches()
                    || !Double.isFinite(rowCount) || rowCount != Math.rint(rowCount) || rowCount < 1
                    || jsNumber(field(partition, "bytes")) > maxResidentBytes) {
                throw failure("lazy range partition metadata exceeds bound or is invalid");
            }
            List<TimedRow> rows = normalizeBars(partitionRows(partition, Math.min(maxPartitionBytes, maxResidentBytes)));
            if (rows.size() != (long) rowCount || rows.getFirst().time() != time(field(partition, "min_event_time"))
                    || rows.getLast().time() != time(field(partition, "max_event_time"))) {
                throw failure("lazy range partition content does not match declared bounds");
            }
            for (TimedRow row : rows) {
                if (row.time() < lower || row.time() >= upper) continue;
                ObjectNode clean = clean(row.value());
                ObjectNode prior = outputByTime.get(row.time());
                if (prior != null && !canonicalEquals(prior, clean))
                    throw failure("lazy hydrated range has conflicting overlapping rows");
                if (prior == null) {
                    outputBytes += jsonStringify(clean).getBytes(StandardCharsets.UTF_8).length;
                    if (outputBytes > maxOutputBytes)
                        throw failure("lazy hydrated output exceeds resident memory ceiling");
                    outputByTime.put(row.time(), clean);
                }
                if (outputByTime.size() > maxRows) throw failure("lazy hydration read exceeds bound");
            }
        }
        List<Map.Entry<Long, ObjectNode>> output = new ArrayList<>(outputByTime.entrySet());
        long interval = truthy(field(hydration, "execution_interval_ms"))
                ? truncate(jsNumber(field(hydration, "execution_interval_ms"))) : 60_000;
        if (output.isEmpty() || barTime(output.getFirst().getValue()) != lower)
            throw failure("lazy hydrated range starts at the wrong boundary");
        for (int index = 1; index < output.size(); index++) {
            if (barTime(output.get(index).getValue()) != barTime(output.get(index - 1).getValue()) + interval)
                throw failure("lazy hydrated range has a timestamp gap or duplicate");
        }
        long expected = Math.max(0, ceilDiv(upper - lower, interval));
        boolean complete = "COMPLETE".equals(text(field(capture, "lifecycle_status")));
        if (complete && output.size() != expected)
            throw failure("lazy hydrated range count does not match complete lifecycle");
        if (complete && !output.isEmpty() && barTime(output.getLast().getValue()) != upper - interval)
            throw failure("lazy hydrated range does not end at the lifecycle boundary");
        JsonNode defaultBatch = truthy(field(hydration, "batch_size")) ? field(hydration, "batch_size") : JSON.numberNode(4_096);
        long batchSize = Math.max(1, truncate(defined(field(input, "batchSize"))
                ? jsNumber(field(input, "batchSize")) : jsNumber(defaultBatch)));
        ArrayNode batches = arrayNode();
        for (int index = 0; index < output.size(); index += (int) Math.min(Integer.MAX_VALUE, batchSize)) {
            ArrayNode batch = arrayNode();
            int end = (int) Math.min(output.size(), index + batchSize);
            for (int offset = index; offset < end; offset++) batch.add(output.get(offset).getValue());
            batches.add(batch);
        }
        Set<String> physicalHashes = new HashSet<>();
        references.forEach(ref -> physicalHashes.add(text(field(ref, "partition_sha256"))));
        ObjectNode result = object();
        result.put("window_id", windowId);
        result.put("role", mark ? "MARK" : "PRICE");
        result.put("row_count", output.size());
        result.set("batches", batches);
        result.put("physical_partition_count", physicalHashes.size());
        return result;
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode lazyReadHydratedRangeV5(ObjectNode options) {
        return readHydratedRangeV5(options);
    }

    /** Alias preserved from the JavaScript public API. */
    public static ObjectNode readExecutionRangeV5(ObjectNode options) {
        return readHydratedRangeV5(options);
    }

    private static List<IndexedPartition> indexPartitions(
            ArrayNode source, boolean fixtureOnly, long maxPartitionBytes) {
        List<IndexedPartition> result = new ArrayList<>();
        for (JsonNode partition : source) {
            String sha = text(field(partition, "sha256"));
            double bytes = jsNumber(field(partition, "bytes"));
            double rowCount = jsNumber(field(partition, "row_count"));
            if (!HASH_RE.matcher(sha).matches()) throw failure("physical partition lacks a declared SHA-256");
            if (!Double.isFinite(bytes) || bytes != Math.rint(bytes) || bytes < 1
                    || !Double.isFinite(rowCount) || rowCount != Math.rint(rowCount) || rowCount < 1)
                throw failure("physical partition lacks declared byte/row bounds");
            if (!fixtureOnly && (defined(field(partition, "body")) || defined(field(partition, "rows"))))
                throw failure("production hydration cannot retain inline partition bodies");
            if (bytes > maxPartitionBytes) throw failure("physical partition exceeds bounded byte ceiling");
            long min = time(field(partition, "min_event_time"));
            long max = time(field(partition, "max_event_time"));
            if (max < min) throw failure("physical partition bounds are invalid");
            result.add(new IndexedPartition(partition, min, max, (long) bytes));
        }
        result.sort(Comparator.comparingLong((IndexedPartition row) -> row.min)
                .thenComparing(IndexedPartition::sha));
        return result;
    }

    private static long[] touchPartition(
            IndexedPartition entry, Set<String> touched, long declaredBytes, long declaredRows,
            long maxTotalBytes, long maxRows) {
        if (!touched.add(entry.sha())) return new long[] {declaredBytes, declaredRows};
        declaredBytes += truncate(jsNumber(field(entry.partition, "bytes")));
        declaredRows += truncate(jsNumber(field(entry.partition, "row_count")));
        if (declaredBytes > maxTotalBytes) throw failure("hydration exceeds bounded aggregate partition bytes");
        if (declaredRows > maxRows) throw failure("hydration exceeds bounded declared physical rows");
        return new long[] {declaredBytes, declaredRows};
    }

    private static void addUniquePhysical(Set<String> rows, String key, long maximum) {
        if (rows.contains(key)) return;
        if (rows.size() >= maximum) throw failure("hydration exceeds bounded unique physical row count");
        rows.add(key);
    }

    private static ObjectNode partitionReference(
            IndexedPartition entry, List<TimedRow> selected, long boundary, long interval) {
        ObjectNode ref = object();
        ref.put("partition_sha256", entry.sha());
        putNullable(ref, "partition_path", partitionPath(entry.partition));
        ref.put("partition_bytes", truncate(jsNumber(field(entry.partition, "bytes"))));
        ref.put("partition_row_count", truncate(jsNumber(field(entry.partition, "row_count"))));
        ref.put("row_start", iso(selected.getFirst().time()));
        ref.put("row_end_exclusive", iso(Math.min(boundary, selected.getLast().time() + interval)));
        ref.put("row_count", selected.size());
        return ref;
    }

    private static String partitionPath(JsonNode partition) {
        JsonNode value = truthyFirst(field(partition, "partition_path"), field(partition, "path"));
        return truthy(value) ? jsString(value) : null;
    }

    private static List<TimedRow> select(List<TimedRow> rows, long start, long end) {
        List<TimedRow> selected = new ArrayList<>();
        for (TimedRow row : rows) if (row.time() >= start && row.time() < end) selected.add(row);
        return selected;
    }

    private static ArrayNode partitionRows(JsonNode partition, long maxBytes) {
        JsonNode declaredBytes = field(partition, "bytes");
        if (defined(declaredBytes) && jsNumber(declaredBytes) > maxBytes)
            throw failure("physical partition exceeds bounded byte ceiling");
        String body;
        if (field(partition, "rows") != null && field(partition, "rows").isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode row : field(partition, "rows")) builder.append(jsonStringify(row)).append('\n');
            body = builder.toString();
        } else if (field(partition, "body") != null && field(partition, "body").isTextual()) {
            body = field(partition, "body").textValue();
        } else if (truthy(field(partition, "path"))) {
            Path path = Path.of(jsString(field(partition, "path")));
            try {
                if (Files.size(path) > maxBytes) throw failure("physical partition exceeds bounded byte ceiling");
                body = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw failure(error.getMessage(), error);
            }
        } else {
            throw failure("partition has no lazy body/path");
        }
        if (truthy(field(partition, "sha256")) && !hash(body).equals(text(field(partition, "sha256"))))
            throw failure("physical partition SHA mismatch " + text(field(partition, "sha256")));
        if (defined(declaredBytes) && body.getBytes(StandardCharsets.UTF_8).length != jsNumber(declaredBytes))
            throw failure("physical partition byte count mismatch");
        ArrayNode rows = arrayNode();
        for (String line : body.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            try {
                rows.add(MAPPER.readTree(line));
            } catch (JsonProcessingException error) {
                throw failure(error.getMessage(), error);
            }
        }
        return rows;
    }

    private static final class HydrationState {
        private final List<IndexedPartition> all;
        private final long maxResidentBytes;
        private final long maxPartitionBytes;
        private long residentBytes;
        private long peakResidentBytes;
        private long accessCounter;

        private HydrationState(
                List<IndexedPartition> indexed, List<IndexedPartition> marks,
                long maxResidentBytes, long maxPartitionBytes) {
            this.all = new ArrayList<>(indexed);
            this.all.addAll(marks);
            this.maxResidentBytes = maxResidentBytes;
            this.maxPartitionBytes = maxPartitionBytes;
        }

        private List<TimedRow> load(IndexedPartition entry) {
            entry.lastUse = ++accessCounter;
            if (entry.rows == null) {
                long needed = entry.bytes;
                if (needed > maxResidentBytes) throw failure("physical partition exceeds resident memory ceiling");
                while (residentBytes + needed > maxResidentBytes) {
                    IndexedPartition victim = all.stream()
                            .filter(row -> row.rows != null && row != entry)
                            .min(Comparator.comparingLong(row -> row.lastUse))
                            .orElseThrow(() -> failure("hydration cannot fit one physical partition in resident memory ceiling"));
                    victim.rows = null;
                    residentBytes -= victim.residentBytes;
                    victim.residentBytes = 0;
                }
                entry.rows = normalizeBars(partitionRows(entry.partition, maxPartitionBytes));
                if (entry.rows.size() != truncate(jsNumber(field(entry.partition, "row_count")))
                        || entry.rows.isEmpty() || entry.rows.getFirst().time() != entry.min
                        || entry.rows.getLast().time() != entry.max) {
                    throw failure("physical partition content does not match declared bounds");
                }
                entry.residentBytes = needed;
                residentBytes += needed;
                peakResidentBytes = Math.max(peakResidentBytes, residentBytes);
            }
            return entry.rows;
        }
    }

    private static final class IndexedPartition {
        private final JsonNode partition;
        private final long min;
        private final long max;
        private final long bytes;
        private List<TimedRow> rows;
        private long residentBytes;
        private long lastUse;

        private IndexedPartition(JsonNode partition, long min, long max, long bytes) {
            this.partition = partition;
            this.min = min;
            this.max = max;
            this.bytes = bytes;
        }

        private String sha() {
            return text(field(partition, "sha256"));
        }
    }

    private record TimedRow(ObjectNode value, long time, long available) {}

    private static List<TimedRow> normalizeRows(JsonNode rows) {
        List<TimedRow> result = new ArrayList<>();
        if (rows == null || !rows.isArray()) return result;
        for (JsonNode source : rows) {
            rejectLabels(source, "feature row");
            if (!source.isObject()) throw failure("feature row must be an object");
            ObjectNode value = (ObjectNode) source.deepCopy();
            long rowTime = rowTime(source);
            JsonNode availability = firstNullish(source,
                    "availability_time", "available_at", "event_time", "decision_time", "time");
            long available = time(availability);
            value.put("__time", rowTime);
            value.put("__available", available);
            if (available > rowTime) throw failure("opportunity feature row is not available at its decision");
            result.add(new TimedRow(value, rowTime, available));
        }
        result.sort(Comparator.comparingLong(TimedRow::time));
        return result;
    }

    private static List<TimedRow> normalizeBars(JsonNode bars) {
        List<TimedRow> result = new ArrayList<>();
        if (bars == null || !bars.isArray()) return result;
        for (JsonNode source : bars) {
            if (!source.isObject()) throw failure("execution bar must be an object");
            ObjectNode value = (ObjectNode) source.deepCopy();
            long timestamp = barTime(source);
            value.put("__time", timestamp);
            rejectLabels(value, "execution bar");
            result.add(new TimedRow(value, timestamp, timestamp));
        }
        result.sort(Comparator.comparingLong(TimedRow::time));
        for (int index = 1; index < result.size(); index++) {
            if (result.get(index).time() == result.get(index - 1).time())
                throw failure("duplicate physical 1m bar");
        }
        return result;
    }

    private static long rowTime(JsonNode row) {
        return time(firstNullish(row, "decision_time", "event_time", "time", "open_time"));
    }

    private static long barTime(JsonNode row) {
        return time(firstNullish(row, "event_time", "time", "open_time"));
    }

    private static void predicateIds(JsonNode predicate, List<String> output) {
        if (predicate == null || !predicate.isObject()) return;
        if (truthy(field(predicate, "predictor_id"))) output.add(jsString(field(predicate, "predictor_id")));
        JsonNode children = truthy(field(predicate, "all")) ? field(predicate, "all")
                : truthy(field(predicate, "any")) ? field(predicate, "any") : arrayNode();
        if (children.isArray()) for (JsonNode child : children) predicateIds(child, output);
        if (truthy(field(predicate, "not"))) predicateIds(field(predicate, "not"), output);
    }

    private static JsonNode resolveGene(JsonNode value, JsonNode genes) {
        if (isGeneReference(value)) return field(genes, jsString(field(value, "$gene")));
        return value;
    }

    private static GeneDomain geneDomain(String reference, JsonNode geneSpace) {
        for (JsonNode gene : array(field(geneSpace, "genes"))) {
            if (!reference.equals(text(field(gene, "name")))) continue;
            if ("continuous".equals(text(field(gene, "type")))) {
                return new GeneDomain(jsNumber(field(gene, "min")), jsNumber(field(gene, "max")), true, List.of());
            }
            List<JsonNode> values = new ArrayList<>();
            if (field(gene, "values") != null && field(gene, "values").isArray())
                field(gene, "values").forEach(values::add);
            else if (defined(field(gene, "default"))) values.add(field(gene, "default"));
            return new GeneDomain(0, 0, false, values);
        }
        return null;
    }

    private record GeneDomain(double min, double max, boolean continuous, List<JsonNode> values) {}

    private static boolean leafMayTrigger(JsonNode actual, String operator, JsonNode expected, JsonNode geneSpace) {
        if (isGeneReference(expected)) {
            if ("IN".equals(operator.toUpperCase(Locale.ROOT))) {
                throw failure("gene-controlled IN predicates are unsupported; freeze an explicit literal membership set");
            }
            String reference = jsString(field(expected, "$gene"));
            GeneDomain domain = geneDomain(reference, geneSpace);
            if (domain == null) throw failure("opportunity predicate references undeclared gene " + reference);
            if (domain.continuous()) {
                double value = jsNumber(actual);
                if (!Double.isFinite(value)) return true;
                return switch (operator.toUpperCase(Locale.ROOT)) {
                    case "GTE" -> value >= domain.min();
                    case "GT" -> value > domain.min();
                    case "LTE" -> value <= domain.max();
                    case "LT" -> value < domain.max();
                    default -> true;
                };
            }
            return domain.values().stream().anyMatch(choice -> compare(actual, operator, choice));
        }
        return compare(actual, operator, expected);
    }

    private static boolean predicateMayTrigger(JsonNode predicate, JsonNode row, JsonNode geneSpace) {
        if (!truthy(predicate)) return false;
        if (truthy(field(predicate, "predictor_id"))) {
            return leafMayTrigger(field(row, jsString(field(predicate, "predictor_id"))),
                    jsString(or(field(predicate, "op"), TextNode.valueOf(""))), field(predicate, "value"), geneSpace);
        }
        if (truthy(field(predicate, "any"))) {
            for (JsonNode child : array(field(predicate, "any")))
                if (predicateMayTrigger(child, row, geneSpace)) return true;
            return false;
        }
        if (truthy(field(predicate, "all"))) {
            for (JsonNode child : array(field(predicate, "all")))
                if (!predicateMayTrigger(child, row, geneSpace)) return false;
            return true;
        }
        if (truthy(field(predicate, "not"))) return true;
        throw failure("invalid opportunity predicate");
    }

    private static boolean compare(JsonNode actual, String operator, JsonNode expected) {
        if (actual == null || actual.isNull()) return false;
        String name = operator == null ? "" : operator.toUpperCase(Locale.ROOT);
        if ("IN".equals(name)) {
            if (expected == null || !expected.isArray()) return false;
            for (JsonNode value : expected) if (canonicalEquals(value, actual)) return true;
            return false;
        }
        if ("EQ".equals(name)) return canonicalEquals(actual, expected);
        if ("NE".equals(name)) return !canonicalEquals(actual, expected);
        double left = jsNumber(actual);
        double right = jsNumber(expected);
        if (!Double.isFinite(left) || !Double.isFinite(right)) return false;
        return switch (name) {
            case "GT" -> left > right;
            case "GTE" -> left >= right;
            case "LT" -> left < right;
            case "LTE" -> left <= right;
            default -> false;
        };
    }

    private static boolean evaluatePredicate(JsonNode predicate, JsonNode row, JsonNode genes) {
        if (!truthy(predicate)) return true;
        if (truthy(field(predicate, "predictor_id"))) {
            return compare(field(row, jsString(field(predicate, "predictor_id"))),
                    jsString(or(field(predicate, "op"), TextNode.valueOf(""))),
                    resolveGene(field(predicate, "value"), genes));
        }
        if (truthy(field(predicate, "all"))) {
            for (JsonNode child : array(field(predicate, "all")))
                if (!evaluatePredicate(child, row, genes)) return false;
            return true;
        }
        if (truthy(field(predicate, "any"))) {
            for (JsonNode child : array(field(predicate, "any")))
                if (evaluatePredicate(child, row, genes)) return true;
            return false;
        }
        if (truthy(field(predicate, "not"))) return !evaluatePredicate(field(predicate, "not"), row, genes);
        throw failure("invalid opportunity predicate");
    }

    private static JsonNode normalizeCandidatePredicate(JsonNode value) {
        if (!truthy(value)) return null;
        if (truthy(field(value, "feature")) && defined(field(value, "value"))) {
            String operator = jsString(field(value, "op"));
            operator = switch (operator) {
                case ">" -> "GT";
                case ">=" -> "GTE";
                case "<" -> "LT";
                case "<=" -> "LTE";
                case "==", "=" -> "EQ";
                default -> operator;
            };
            ObjectNode result = object();
            result.set("predictor_id", cloneNode(field(value, "feature")));
            result.put("op", operator);
            result.set("value", cloneNode(field(value, "value")));
            return result;
        }
        return value;
    }

    private static boolean predicateHasGene(JsonNode predicate) {
        if (predicate == null || !predicate.isObject()) return false;
        if (isGeneReference(field(predicate, "value"))) return true;
        JsonNode children = truthy(field(predicate, "all")) ? field(predicate, "all")
                : truthy(field(predicate, "any")) ? field(predicate, "any") : arrayNode();
        if (children.isArray()) for (JsonNode child : children) if (predicateHasGene(child)) return true;
        return predicateHasGene(field(predicate, "not"));
    }

    private static boolean isGeneReference(JsonNode value) {
        return value != null && value.isObject() && value.size() == 1 && truthy(field(value, "$gene"));
    }

    private static ObjectNode candidateGenes(JsonNode candidate, JsonNode geneSpace) {
        ObjectNode result = object();
        for (JsonNode gene : array(field(geneSpace, "genes"))) {
            String name = text(field(gene, "name"));
            JsonNode definitionValue = field(field(candidate, "definition"), name);
            JsonNode candidateValue = field(candidate, name);
            JsonNode selected = !nullish(definitionValue) ? definitionValue
                    : !nullish(candidateValue) ? candidateValue : field(gene, "default");
            result.set(name, cloneNode(selected));
        }
        return result;
    }

    private static boolean isEligibleDecision(TimedRow row, Long asOf) {
        JsonNode eligible = field(row.value(), "signal_eligible");
        boolean explicitlyFalse = eligible != null && eligible.isBoolean() && !eligible.booleanValue();
        String scope = jsString(or(field(row.value(), "trade_scope"), TextNode.valueOf(""))).toUpperCase(Locale.ROOT);
        return !explicitlyFalse && !"CONTEXT_ONLY".equals(scope) && row.available() <= row.time()
                && (asOf == null || row.time() <= asOf);
    }

    private static void validateFrozenBindings(
            String precommit, String registry, String evaluator, String graph, String geneSpace,
            JsonNode predicate, boolean fixtureOnly) {
        LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
        bindings.put("precommit_sha256", precommit);
        bindings.put("predictor_registry_sha256", registry);
        bindings.put("evaluator_spec_sha256", evaluator);
        bindings.put("gene_space_sha256", geneSpace);
        if (graph != null) bindings.put("graph_sha256", graph);
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            if (!fixtureOnly || entry.getValue() != null) boundHash(textOrNull(entry.getValue()), entry.getKey());
        }
        if (!truthy(predicate)) throw failure("opportunity envelope requires a frozen premise-level predicate");
    }

    private static String bindDomainArtifact(JsonNode artifact, JsonNode supplied, String label) {
        if (truthy(artifact)) {
            String content = textOrNull(field(artifact, "content_sha256"));
            if (content == null || !content.equals(ownHash(artifact)))
                throw failure(label + " artifact is not content-hash bound");
            if (truthy(supplied) && !content.equals(jsString(supplied)))
                throw failure(label + " binding does not match artifact content");
            return content;
        }
        if (defined(supplied) && !supplied.isNull()) return boundHash(supplied, label);
        return null;
    }

    private static String bindEnvelopeArtifact(
            JsonNode artifact, JsonNode supplied, String label, boolean fixtureOnly) {
        if (truthy(artifact)) {
            JsonNode contentNode = field(artifact, "content_sha256");
            if (truthy(contentNode) && !jsString(contentNode).equals(ownHash(artifact)))
                throw failure("opportunity binding artifact hash is invalid");
            if (!truthy(contentNode) && !fixtureOnly)
                throw failure("opportunity binding artifact lacks content hash");
            String actual = truthy(contentNode) ? jsString(contentNode) : hash(artifact);
            if (truthy(supplied) && !actual.equals(jsString(supplied)))
                throw failure(label + " binding does not match artifact content");
            return actual;
        }
        return defined(supplied) && !supplied.isNull() ? jsString(supplied) : null;
    }

    private static String boundHash(JsonNode value, String label) {
        if (value != null && value.isObject()) {
            String content = textOrNull(field(value, "content_sha256"));
            if (content == null || !content.equals(ownHash(value)))
                throw failure(label + " artifact is not content-hash bound");
            return content;
        }
        String candidate = defined(value) ? jsString(value) : "";
        if (!HASH_RE.matcher(candidate).matches()) throw failure(label + " must be a SHA-256 hash");
        return candidate;
    }

    private static void rejectLabels(JsonNode value, String path) {
        if (value == null || (!value.isObject() && !value.isArray())) return;
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (LABEL_KEYS.contains(lower) || LABEL_PATTERN.matcher(lower).find()
                        || TRADE_LABEL_PATTERN.matcher(lower).find()) {
                    throw failure("opportunity envelope cannot depend on label/outcome " + path + '.' + entry.getKey());
                }
                if (entry.getValue() != null && (entry.getValue().isObject() || entry.getValue().isArray()))
                    rejectLabels(entry.getValue(), path + '.' + entry.getKey());
            }
        } else {
            for (int index = 0; index < value.size(); index++)
                rejectLabels(value.get(index), path + '.' + index);
        }
    }

    private static String ownHash(JsonNode value) {
        if (value == null) return hash(NullNode.instance);
        JsonNode copy = value.deepCopy();
        if (copy.isObject()) ((ObjectNode) copy).remove("content_sha256");
        return hash(copy);
    }

    private static ObjectNode withHashInPlace(ObjectNode value) {
        value.put("content_sha256", ownHash(value));
        return value;
    }

    private static ObjectNode clean(ObjectNode value) {
        ObjectNode result = object();
        value.fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("__")) result.set(entry.getKey(), cloneNode(entry.getValue()));
        });
        return result;
    }

    private static ArrayNode mapDefinitions(ArrayNode candidates) {
        ArrayNode result = arrayNode();
        for (JsonNode candidate : candidates) {
            result.add(cloneNode(truthy(field(candidate, "definition")) ? field(candidate, "definition") : candidate));
        }
        return result;
    }

    private static ArrayNode distinctSorted(ArrayNode rows, String name) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        rows.forEach(row -> values.add(text(field(row, name))));
        ArrayNode result = arrayNode();
        values.stream().sorted().forEach(result::add);
        return result;
    }

    private static List<String> distinctSortedList(ArrayNode rows, String name, boolean lower) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : rows) {
            String value = text(field(row, name));
            value = lower ? value.toLowerCase(Locale.ROOT) : value.toUpperCase(Locale.ROOT);
            if (!value.isEmpty() && !values.contains(value)) values.add(value);
        }
        values.sort(String::compareTo);
        return values;
    }

    private static List<String> uniqueArray(
            JsonNode values, String label, Function<JsonNode, String> normalizer) {
        if (values == null || !values.isArray() || values.isEmpty())
            throw failure(label + " must be a non-empty frozen array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) result.add(normalizer.apply(value));
        if (result.stream().anyMatch(String::isEmpty) || new HashSet<>(result).size() != result.size())
            throw failure(label + " contains an empty or duplicate identity");
        result.sort(String::compareTo);
        return result;
    }

    private static void sortArray(ArrayNode array, Function<JsonNode, String> key) {
        List<JsonNode> rows = new ArrayList<>();
        array.forEach(rows::add);
        rows.sort(Comparator.comparing(key));
        array.removeAll();
        rows.forEach(array::add);
    }

    private static void sortTextArray(ArrayNode array) {
        sortArray(array, OpportunityV5::jsString);
    }

    private static boolean optionalIdentityMatches(JsonNode intent, JsonNode window) {
        if (!defined(intent) || intent.isNull() || !defined(window) || window.isNull()) return true;
        return jsString(intent).equals(jsString(window));
    }

    private static boolean canonicalEquals(JsonNode left, JsonNode right) {
        return JsonHashes.canonicalString(left == null ? NullNode.instance : left)
                .equals(JsonHashes.canonicalString(right == null ? NullNode.instance : right));
    }

    private static boolean same(JsonNode left, JsonNode right) {
        return canonicalEquals(left, right);
    }

    private static long time(JsonNode value) {
        if (value != null && value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw failure("invalid timestamp " + jsString(value));
            return truncate(number);
        }
        String input = jsString(value);
        try {
            return Instant.parse(input).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(input).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDateTime.parse(input).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (DateTimeParseException ignoredLocal) {
                    try {
                        return LocalDate.parse(input).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                    } catch (DateTimeParseException invalid) {
                        throw failure("invalid timestamp " + input);
                    }
                }
            }
        }
    }

    private static String iso(long value) {
        return JS_ISO.format(Instant.ofEpochMilli(value));
    }

    private static long ceilDiv(long numerator, long denominator) {
        if (numerator <= 0) return 0;
        return (numerator + denominator - 1) / denominator;
    }

    private static long optionLong(ObjectNode options, String name, long defaultValue) {
        return defined(field(options, name)) ? truncate(jsNumber(field(options, name))) : defaultValue;
    }

    private static long truncate(double value) {
        if (!Double.isFinite(value)) return 0;
        return (long) value;
    }

    private static double jsNumber(JsonNode value) {
        if (value == null) return Double.NaN;
        if (value.isNull()) return 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isTextual()) {
            String text = value.textValue().trim();
            if (text.isEmpty()) return 0;
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static String jsString(JsonNode value) {
        if (value == null) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return Boolean.toString(value.booleanValue());
        if (value.isNumber()) {
            BigDecimal number = value.decimalValue().stripTrailingZeros();
            if (number.signum() == 0) return "0";
            return number.toPlainString();
        }
        return jsonStringify(value);
    }

    private static String jsonStringify(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value == null ? NullNode.instance : value);
        } catch (JsonProcessingException impossible) {
            throw failure(impossible.getMessage(), impossible);
        }
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    private static JsonNode truthyFirst(JsonNode... values) {
        JsonNode last = null;
        for (JsonNode value : values) {
            last = value;
            if (truthy(value)) return value;
        }
        return last;
    }

    private static JsonNode or(JsonNode... values) {
        return truthyFirst(values);
    }

    private static String truthyText(JsonNode... values) {
        JsonNode value = truthyFirst(values);
        return truthy(value) ? jsString(value) : null;
    }

    private static String textTruthyFirst(JsonNode... values) {
        JsonNode value = truthyFirst(values);
        return truthy(value) ? jsString(value) : null;
    }

    private static JsonNode firstNullish(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode value = field(object, name);
            if (!nullish(value)) return value;
        }
        return null;
    }

    private static boolean defined(JsonNode value) {
        return value != null;
    }

    private static boolean nullish(JsonNode value) {
        return value == null || value.isNull();
    }

    private static boolean exactTrue(JsonNode value) {
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static boolean booleanValue(JsonNode value, boolean fallback) {
        return value == null ? fallback : value.asBoolean(fallback);
    }

    private static JsonNode field(JsonNode object, String name) {
        return object != null && object.isObject() ? object.get(name) : null;
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String textOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : jsString(value);
    }

    private static JsonNode textOrNull(String value) {
        return value == null ? NullNode.instance : TextNode.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : arrayNode();
    }

    private static ArrayNode arrayNode() {
        return MAPPER.createArrayNode();
    }

    private static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    private static JsonNode cloneNode(JsonNode value) {
        return value == null ? NullNode.instance : value.deepCopy();
    }

    private static void putNullable(ObjectNode target, String name, String value) {
        if (value == null) target.set(name, NullNode.instance);
        else target.put(name, value);
    }

    private static void copyOrNull(ObjectNode target, String name, JsonNode value) {
        target.set(name, value == null ? NullNode.instance : cloneNode(value));
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
