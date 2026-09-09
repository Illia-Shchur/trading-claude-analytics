package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpportunityV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @TempDir Path temporary;

    @Test
    void domainEnvelopeSubsetAndAliasesMatchNodeExactly() throws Exception {
        ObjectNode domainOptions = domainOptions();
        ObjectNode expectedDomain = nodeValue("makeOpportunityDomainV5", domainOptions);
        ObjectNode actualDomain = OpportunityV5.makeOpportunityDomainV5(domainOptions);
        assertJson(actualDomain, expectedDomain);
        assertThat(OpportunityV5.validateOpportunityDomainV5(actualDomain)).isTrue();
        assertThat(actualDomain.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(actualDomain));

        ObjectNode envelopeOptions = envelopeOptions();
        ObjectNode expectedEnvelope = nodeValue("makeOpportunityEnvelopeV5", envelopeOptions);
        ObjectNode actualEnvelope = OpportunityV5.makeOpportunityEnvelopeV5(envelopeOptions);
        assertJson(actualEnvelope, expectedEnvelope);
        assertJson(OpportunityV5.buildOpportunityEnvelopeV5(envelopeOptions), expectedEnvelope);
        assertThat(OpportunityV5.validateOpportunityEnvelopeV5(actualEnvelope)).isTrue();

        ObjectNode subsetOptions = MAPPER.createObjectNode();
        subsetOptions.set("envelope", expectedEnvelope);
        subsetOptions.set("intent", intent("2026-01-01T00:02:00Z", 20));
        ObjectNode expectedSubset = nodeValue("assertCandidateIntentSubsetV5", subsetOptions);
        assertJson(OpportunityV5.assertCandidateIntentSubsetV5(subsetOptions), expectedSubset);
        assertJson(OpportunityV5.proveCandidateSubsetV5(subsetOptions), expectedSubset);

        assertThat(OpportunityV5.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(OpportunityV5.hash("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(OpportunityV5.hash("abc"));
    }

    @Test
    void inlinePartitionHydrationAndLazyReadMatchNodeExactly() throws Exception {
        ObjectNode envelope = singleWindowEnvelope("BINANCE_SPOT", 3, 1);
        ArrayNode bars = bars("2026-01-01T00:00:00Z", 4, "PRICE", 100);
        ObjectNode partitionOptions = MAPPER.createObjectNode().set("bars", bars);
        ObjectNode expectedSet = nodeValue("makeContentAddressedPartitionsV5", partitionOptions);
        ObjectNode actualSet = OpportunityV5.makeContentAddressedPartitionsV5(partitionOptions);
        assertJson(actualSet, expectedSet);
        assertJson(OpportunityV5.normalizeExecutionPartitionsV5(partitionOptions), expectedSet);
        ObjectNode mistypedPartitionOptions = partitionOptions.deepCopy();
        mistypedPartitionOptions.put("fixtureOnly", "true");
        assertJson(OpportunityV5.makeContentAddressedPartitionsV5(mistypedPartitionOptions),
                nodeValue("makeContentAddressedPartitionsV5", mistypedPartitionOptions));

        ObjectNode hydrationOptions = MAPPER.createObjectNode();
        hydrationOptions.set("envelope", envelope);
        hydrationOptions.set("partitions", expectedSet.path("partitions"));
        hydrationOptions.put("batchSize", 2);
        ObjectNode expectedHydration = nodeValue("hydrateOpportunityEnvelopeV5", hydrationOptions);
        ObjectNode actualHydration = OpportunityV5.hydrateOpportunityEnvelopeV5(hydrationOptions);
        assertJson(actualHydration, expectedHydration);
        assertJson(OpportunityV5.buildOpportunityHydrationV5(hydrationOptions), expectedHydration);
        assertJson(OpportunityV5.hydrateExecutionEnvelopeV5(hydrationOptions), expectedHydration);
        ObjectNode mistypedHydration = hydrationOptions.deepCopy();
        mistypedHydration.put("fixtureOnly", "true");
        assertJson(OpportunityV5.hydrateOpportunityEnvelopeV5(mistypedHydration),
                nodeValue("hydrateOpportunityEnvelopeV5", mistypedHydration));

        ObjectNode readOptions = MAPPER.createObjectNode();
        readOptions.set("hydration", expectedHydration);
        readOptions.set("partitions", expectedSet.path("partitions"));
        readOptions.put("window_id", envelope.path("windows").get(0).path("window_id").asText());
        readOptions.put("start", "2026-01-01T00:00:00Z");
        readOptions.put("batchSize", 2);
        ObjectNode expectedRead = nodeValue("readHydratedRangeV5", readOptions);
        assertJson(OpportunityV5.readHydratedRangeV5(readOptions), expectedRead);
        assertJson(OpportunityV5.lazyReadHydratedRangeV5(readOptions), expectedRead);
        assertJson(OpportunityV5.readExecutionRangeV5(readOptions), expectedRead);
    }

    @Test
    void physicalDerivativePartitionsExpiryAndMarkHydrationMatchNode() throws Exception {
        ObjectNode envelope = singleWindowEnvelope("BINANCE_PERPETUAL", 3, 0);
        Path physicalRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath()
                .resolve("target/opportunity-v5-physical-oracle").normalize();
        clearTree(physicalRoot);
        Path pricesRoot = physicalRoot.resolve("prices");
        Path marksRoot = physicalRoot.resolve("marks");
        ObjectNode priceOptions = physicalPartitionOptions(
                bars("2026-01-01T00:01:00Z", 2, "PRICE", 200), pricesRoot);
        ObjectNode markOptions = physicalPartitionOptions(
                bars("2026-01-01T00:01:00Z", 3, "MARK", 199), marksRoot);
        ObjectNode expectedPrices = physicalOracleValue(
                "makeContentAddressedPartitionsV5", priceOptions, physicalRoot);
        ObjectNode actualPrices = OpportunityV5.makeContentAddressedPartitionsV5(priceOptions);
        assertJson(actualPrices, expectedPrices);
        ObjectNode expectedMarks = physicalOracleValue(
                "makeContentAddressedPartitionsV5", markOptions, physicalRoot);
        ObjectNode actualMarks = OpportunityV5.makeContentAddressedPartitionsV5(markOptions);
        assertJson(actualMarks, expectedMarks);

        for (JsonNode row : (ArrayNode) expectedMarks.path("partitions")) ((ObjectNode) row).put("series_role", "MARK");
        for (JsonNode row : (ArrayNode) actualMarks.path("partitions")) ((ObjectNode) row).put("series_role", "MARK");
        ObjectNode hydrationOptions = MAPPER.createObjectNode();
        hydrationOptions.set("envelope", envelope);
        hydrationOptions.set("partitions", expectedPrices.path("partitions"));
        hydrationOptions.set("markPartitions", expectedMarks.path("partitions"));
        hydrationOptions.putArray("expiryTerminals").addObject()
                .put("window_id", envelope.path("windows").get(0).path("window_id").asText())
                .put("terminal_time", "2026-01-01T00:02:00Z");
        hydrationOptions.put("fixtureOnly", false);
        hydrationOptions.put("maxResidentBytes", 100_000);
        ObjectNode expectedHydration = physicalOracleValue(
                "hydrateOpportunityEnvelopeV5", hydrationOptions, physicalRoot);
        ObjectNode actualHydration = OpportunityV5.hydrateOpportunityEnvelopeV5(hydrationOptions);
        assertJson(actualHydration, expectedHydration);
        assertThat(actualHydration.path("windows").get(0).path("lifecycle_status").asText()).isEqualTo("COMPLETE");
        assertThat(actualHydration.path("windows").get(0).path("mark_complete").asBoolean()).isTrue();

        ObjectNode markRead = MAPPER.createObjectNode();
        markRead.set("hydration", expectedHydration);
        markRead.set("partitions", expectedMarks.path("partitions"));
        markRead.put("window_id", envelope.path("windows").get(0).path("window_id").asText());
        markRead.put("role", "MARK");
        markRead.put("end", "2026-01-01T00:03:00Z");
        assertJson(OpportunityV5.readHydratedRangeV5(markRead),
                physicalOracleValue("readHydratedRangeV5", markRead, physicalRoot));
        clearTree(physicalRoot);
    }

    @Test
    void authoritativeDomainAndEnvelopeLineageMatchNodeExactly() throws Exception {
        ObjectNode precommit = boundArtifact("kind", "precommit");
        ObjectNode evaluator = boundArtifact("kind", "evaluator");
        ObjectNode registry = boundArtifact("kind", "registry");
        ObjectNode plan = boundArtifact("kind", "plan");
        ObjectNode geneSpace = MAPPER.createObjectNode();
        geneSpace.putArray("genes").addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 1).put("max", 3).put("default", 2);
        bind(geneSpace);
        ObjectNode candidateSet = MAPPER.createObjectNode().put("schema", "strategy-candidate-set/5")
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("gene_space_sha256", geneSpace.path("content_sha256").asText())
                .put("evaluator_spec_sha256", evaluator.path("content_sha256").asText());
        candidateSet.set("gene_space", geneSpace.deepCopy());
        candidateSet.putArray("candidates").addObject().put("candidate_id", "candidate-1")
                .putObject("definition").set("signal_rule", predicate("score", "GTE", 2));
        bind(candidateSet);

        ObjectNode domainOptions = MAPPER.createObjectNode();
        domainOptions.set("candidateSet", candidateSet);
        domainOptions.set("precommit", precommit);
        domainOptions.set("geneSpace", geneSpace);
        domainOptions.set("evaluatorSpec", evaluator);
        domainOptions.set("predictorRegistry", registry);
        ObjectNode expectedDomain = nodeValue("makeOpportunityDomainV5", domainOptions);
        ObjectNode actualDomain = OpportunityV5.makeOpportunityDomainV5(domainOptions);
        assertJson(actualDomain, expectedDomain);
        assertThat(actualDomain.path("provenance").asText()).isEqualTo("AUTHORITATIVE");

        ObjectNode envelopeOptions = MAPPER.createObjectNode().put("max_lifecycle_ms", 120_000)
                .put("execution_interval_ms", 60_000);
        envelopeOptions.set("candidateSet", candidateSet);
        envelopeOptions.set("opportunityDomain", expectedDomain);
        envelopeOptions.set("geneSpace", geneSpace);
        envelopeOptions.set("precommit", precommit);
        envelopeOptions.set("predictorRegistry", registry);
        envelopeOptions.set("evaluatorSpec", evaluator);
        envelopeOptions.set("plan", plan);
        envelopeOptions.set("predicate", predicate("score", "GTE", 1));
        envelopeOptions.putArray("featureRows").add(intent("2026-01-01T00:01:00Z", 3));
        ObjectNode expectedEnvelope = nodeValue("makeOpportunityEnvelopeV5", envelopeOptions);
        ObjectNode actualEnvelope = OpportunityV5.makeOpportunityEnvelopeV5(envelopeOptions);
        assertJson(actualEnvelope, expectedEnvelope);
        assertThat(actualEnvelope.path("provenance").asText()).isEqualTo("AUTHORITATIVE");
        assertThat(OpportunityV5.validateOpportunityEnvelopeV5(actualEnvelope)).isTrue();
    }

    @Test
    void nodeAndJavaRejectTheSameCriticalBoundaryFailures() throws Exception {
        ObjectNode labeled = envelopeOptions();
        ((ObjectNode) labeled.path("featureRows").get(0)).put("future_return", .5);
        assertSameFailure("makeOpportunityEnvelopeV5", labeled);

        ObjectNode invalidDomain = domainOptions();
        ((ObjectNode) invalidDomain.path("branches").get(0).path("predicate")).put("resolved_at", "tomorrow");
        assertSameFailure("makeOpportunityDomainV5", invalidDomain);

        ObjectNode nonBooleanComplete = domainOptions();
        nonBooleanComplete.put("domain_complete", "true");
        assertSameFailure("makeOpportunityDomainV5", nonBooleanComplete);

        ObjectNode nonBooleanFixture = envelopeOptions();
        nonBooleanFixture.put("fixtureOnly", "true");
        assertSameFailure("makeOpportunityEnvelopeV5", nonBooleanFixture);

        ObjectNode envelope = singleWindowEnvelope("BINANCE_SPOT", 2, 0);
        ObjectNode partitionSet = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", bars("2026-01-01T00:01:00Z", 2, "PRICE", 100)));
        ObjectNode corruptHydration = MAPPER.createObjectNode();
        corruptHydration.set("envelope", envelope);
        ArrayNode corruptPartitions = (ArrayNode) partitionSet.path("partitions").deepCopy();
        ((ObjectNode) corruptPartitions.get(0)).put("body", corruptPartitions.get(0).path("body").asText() + "{}\n");
        corruptHydration.set("partitions", corruptPartitions);
        assertSameFailure("hydrateOpportunityEnvelopeV5", corruptHydration);

        ObjectNode inlineProduction = MAPPER.createObjectNode();
        inlineProduction.set("envelope", envelope);
        inlineProduction.set("partitions", partitionSet.path("partitions"));
        inlineProduction.put("fixtureOnly", false);
        assertSameFailure("hydrateOpportunityEnvelopeV5", inlineProduction);

        ObjectNode missingMarks = MAPPER.createObjectNode();
        missingMarks.set("envelope", singleWindowEnvelope("BINANCE_PERPETUAL", 2, 0));
        missingMarks.set("partitions", partitionSet.path("partitions"));
        assertSameFailure("hydrateOpportunityEnvelopeV5", missingMarks);
    }

    @Test
    void contentAddressedPathCollisionFailsClosed() throws Exception {
        ArrayNode rows = bars("2026-01-01T00:00:00Z", 2, "PRICE", 100);
        ObjectNode fixture = OpportunityV5.makeContentAddressedPartitionsV5(
                MAPPER.createObjectNode().set("bars", rows));
        String sha = fixture.path("partitions").get(0).path("sha256").asText();
        Path output = Files.createDirectory(temporary.resolve("collision"));
        Files.writeString(output.resolve(sha + ".jsonl"), "tampered\n");
        ObjectNode options = physicalPartitionOptions(rows, output);
        assertThatThrownBy(() -> OpportunityV5.makeContentAddressedPartitionsV5(options))
                .hasMessage("content-addressed partition collision");
    }

    private static ObjectNode domainOptions() {
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", true);
        ArrayNode branches = options.putArray("branches");
        branches.addObject().put("branch_id", "branch-b").put("candidate_id", "candidate-b")
                .set("predicate", predicate("score", "GTE", 20));
        branches.addObject().put("branch_id", "branch-a")
                .set("predicate", predicate("score", "GTE", 10));
        return options;
    }

    private static ObjectNode envelopeOptions() {
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", true)
                .put("max_lifecycle_ms", 120_000).put("execution_interval_ms", 60_000)
                .put("preentry_warmup_bars", 1).put("asOf", "2026-01-01T00:03:00Z");
        ObjectNode geneSpace = options.putObject("geneSpace");
        geneSpace.putArray("genes").addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 10).put("max", 20).put("default", 15);
        options.set("predicate", genePredicate("score", "GTE", "threshold"));
        ArrayNode rows = options.putArray("featureRows");
        rows.add(intent("2026-01-01T00:00:00Z", 5));
        rows.add(intent("2026-01-01T00:01:00Z", 10));
        rows.add(intent("2026-01-01T00:02:00Z", 20));
        rows.add(intent("2026-01-01T00:03:00Z", 30).put("trade_scope", "CONTEXT_ONLY"));
        rows.add(intent("2026-01-01T00:04:00Z", 40));
        options.putArray("candidates").addObject().put("candidate_id", "c-1")
                .putObject("definition").set("signal_rule", predicate("score", "GTE", 15));
        return options;
    }

    private static ObjectNode singleWindowEnvelope(String instrument, int lifeMinutes, int warmup) {
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", true)
                .put("max_lifecycle_ms", lifeMinutes * 60_000L).put("execution_interval_ms", 60_000)
                .put("preentry_warmup_bars", warmup);
        options.set("predicate", predicate("score", "GTE", 1));
        options.putArray("featureRows").add(intent("2026-01-01T00:01:00Z", 5).put("instrument", instrument));
        return OpportunityV5.makeOpportunityEnvelopeV5(options);
    }

    private static ObjectNode intent(String timestamp, int score) {
        ObjectNode row = MAPPER.createObjectNode().put("decision_time", timestamp).put("availability_time", timestamp)
                .put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("episode_id", "episode-" + timestamp).put("signal_id", "signal-" + timestamp)
                .put("score", score);
        row.putObject("feature").put("score", score);
        return row;
    }

    private static ObjectNode predicate(String predictor, String operator, int value) {
        return MAPPER.createObjectNode().put("predictor_id", predictor).put("op", operator).put("value", value);
    }

    private static ObjectNode genePredicate(String predictor, String operator, String gene) {
        ObjectNode predicate = MAPPER.createObjectNode().put("predictor_id", predictor).put("op", operator);
        predicate.putObject("value").put("$gene", gene);
        return predicate;
    }

    private static ArrayNode bars(String first, int count, String role, int base) {
        long start = java.time.Instant.parse(first).toEpochMilli();
        ArrayNode rows = MAPPER.createArrayNode();
        for (int index = 0; index < count; index++) {
            rows.addObject().put("event_time", start + index * 60_000L).put("asset", "btc")
                    .put("instrument", "PRICE".equals(role) ? "BINANCE_SPOT" : "BINANCE_PERPETUAL")
                    .put("symbol", "BTCUSDT").put("open", base + index).put("high", base + index + 2)
                    .put("low", base + index - 1).put("close", base + index + 1).put("series_role", role)
                    .put("note", index == 0 ? "κρυπτο🚀<&" : "ok");
        }
        return rows;
    }

    private static ObjectNode physicalPartitionOptions(ArrayNode bars, Path output) {
        ObjectNode options = MAPPER.createObjectNode().put("fixtureOnly", false).put("outputRoot", output.toString())
                .put("asset", "btc").put("instrument", "BINANCE_PERPETUAL").put("symbol", "BTCUSDT");
        options.set("bars", bars);
        return options;
    }

    private static ObjectNode boundArtifact(String key, String value) {
        ObjectNode artifact = MAPPER.createObjectNode().put(key, value);
        bind(artifact);
        return artifact;
    }

    private static void bind(ObjectNode artifact) {
        ObjectNode copy = artifact.deepCopy();
        copy.remove("content_sha256");
        artifact.put("content_sha256", OpportunityV5.hash(copy));
    }

    private static ObjectNode nodeValue(String action, ObjectNode options) throws Exception {
        JsonNode response = node(action, options);
        assertThat(response.path("ok").asBoolean()).describedAs(response.toString()).isTrue();
        return (ObjectNode) response.path("value");
    }

    private static void assertSameFailure(String action, ObjectNode options) throws Exception {
        JsonNode expected = node(action, options);
        assertThat(expected.path("ok").asBoolean()).isFalse();
        assertThatThrownBy(() -> dispatch(action, options))
                .hasMessage(expected.path("error").asText());
    }

    private static JsonNode dispatch(String action, ObjectNode options) {
        return switch (action) {
            case "makeOpportunityDomainV5" -> OpportunityV5.makeOpportunityDomainV5(options);
            case "makeOpportunityEnvelopeV5" -> OpportunityV5.makeOpportunityEnvelopeV5(options);
            case "hydrateOpportunityEnvelopeV5" -> OpportunityV5.hydrateOpportunityEnvelopeV5(options);
            default -> throw new IllegalArgumentException("unsupported test action " + action);
        };
    }

    private static JsonNode node(String action, ObjectNode options) throws Exception {
        ObjectNode request = MAPPER.createObjectNode().put("action", action).set("options", options);
        String key = JsonHashes.canonicalSha256(request);
        try (InputStream input = Objects.requireNonNull(
                OpportunityV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/opportunity-v5.json"),
                "frozen opportunity oracle is missing")) {
            JsonNode response = MAPPER.readTree(input).get(key);
            assertThat(response).as("missing frozen opportunity oracle for " + key)
                    .isNotNull();
            return response.deepCopy();
        }
    }

    /**
     * Physical partition paths are deliberately absolute in the production contract, but the
     * frozen oracle was captured on one checkout. Canonicalize only those fixture paths for the
     * lookup, then restore the current checkout's root in the returned value so the assertion
     * still covers every path-bearing field without baking a machine-specific path into the key.
    */
    private static ObjectNode physicalOracleValue(String action, ObjectNode options, Path actualRoot)
            throws Exception {
        Path oracleRoot = oraclePhysicalRoot();
        ObjectNode canonicalOptions = (ObjectNode) bindPathDependentHashes(
                relocatePaths(options, actualRoot, oracleRoot));
        ObjectNode expected = nodeValue(action, canonicalOptions);
        ObjectNode relocated = (ObjectNode) bindPathDependentHashes(
                relocatePaths(expected, oracleRoot, actualRoot));
        return relocated;
    }

    private static JsonNode bindPathDependentHashes(JsonNode source) {
        if (source.isObject()) {
            ObjectNode value = (ObjectNode) source;
            value.fieldNames().forEachRemaining(name ->
                    value.set(name, bindPathDependentHashes(value.get(name))));
            if (value.has("partition_inventory") && value.has("partition_bytes_root_sha256")) {
                value.put("partition_bytes_root_sha256", partitionBytesRoot(value));
            }
            if (value.has("content_sha256")) {
                value.put("content_sha256", JsonHashes.ownHash(value));
            }
            return value;
        }
        if (source.isArray()) {
            ArrayNode value = (ArrayNode) source;
            for (int index = 0; index < value.size(); index++) {
                value.set(index, bindPathDependentHashes(value.get(index)));
            }
        }
        return source;
    }

    private static String partitionBytesRoot(ObjectNode hydration) {
        List<JsonNode> rows = new java.util.ArrayList<>();
        hydration.path("partition_inventory").forEach(rows::add);
        rows.sort(Comparator.comparing(row -> row.path("partition_sha256").asText()));
        ArrayNode projection = MAPPER.createArrayNode();
        for (JsonNode inventory : rows) {
            ObjectNode row = MAPPER.createObjectNode();
            for (String name : List.of("partition_sha256", "partition_path", "bytes", "row_count",
                    "min_event_time", "max_event_time", "asset", "instrument", "symbol", "series_role")) {
                row.set(name, inventory.path(name).deepCopy());
            }
            projection.add(row);
        }
        return JsonHashes.canonicalSha256(projection);
    }

    private static Path oraclePhysicalRoot() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                OpportunityV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/opportunity-v5.json"),
                "frozen opportunity oracle is missing")) {
            JsonNode oracle = MAPPER.readTree(input);
            for (JsonNode response : oracle) {
                JsonNode path = response.path("value").path("partitions").path(0).path("path");
                if (path.isTextual() && !path.asText().isBlank()) {
                    Path partitionPath = Path.of(path.asText());
                    return partitionPath.getParent().getParent();
                }
            }
        }
        throw new AssertionError("frozen opportunity oracle has no physical partition path");
    }

    private static JsonNode relocatePaths(JsonNode source, Path from, Path to) {
        String fromText = from.toAbsolutePath().normalize().toString();
        String toText = to.toAbsolutePath().normalize().toString();
        if (source.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            source.fields().forEachRemaining(entry ->
                    result.set(entry.getKey(), relocatePaths(entry.getValue(), from, to)));
            return result;
        }
        if (source.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            source.forEach(value -> result.add(relocatePaths(value, from, to)));
            return result;
        }
        if (source.isTextual()) {
            return MAPPER.getNodeFactory().textNode(source.asText().replace(fromText, toText));
        }
        return source.deepCopy();
    }

    private static void clearTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }
}
