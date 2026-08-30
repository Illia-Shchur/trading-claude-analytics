package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyResearchNextCommandSecurityTest {
    private static final Set<String> CONSTANTS = Set.of(
            "STACK_SCHEMA", "SOURCE_RECEIPT_SCHEMA", "EXPOSURE_SCHEMA", "EXECUTION_SCHEMA",
            "PORTFOLIO_SCHEMA", "PROSPECTIVE_SCHEMA", "ACTIVATION_SCHEMA",
            "REVOCATION_SCHEMA", "READINESS_SCHEMA", "RUN_SCHEMA", "EVIDENCE_SCHEMA",
            "UNIVERSE", "PIT_TIERS", "DECISIONS", "EXECUTABLE_INSTRUMENTS",
            "SOURCE_REGISTRY");
    private static final Set<String> FUNCTIONS = Set.of(
            "appendExposureLedger", "appendProspectiveEvent", "assignPitTier", "behaviorHash",
            "coverageMatrix", "evaluateAuthoritativeNext", "evaluatePlateau",
            "freezeNextPrecommit", "generateNextCandidates", "hash", "makeActivationArtifact",
            "makeExecutionPolicy", "makePortfolioPolicy", "makeProspectiveAttestation",
            "makeProspectiveLedger", "makeProspectiveReservation", "makeRevocationArtifact",
            "makeSourceReceipt", "makeStackContract", "monitorProspective", "nestedWalkForward",
            "ownHash", "prospectiveEligibility", "readinessAudit", "readinessMarkdown",
            "researchDecision", "runAblations", "runAuthoritativeWfo",
            "simulateBinanceExecution", "simulateResearchPortfolio", "stable",
            "stationaryBlockMaxStatistic", "validateAuthoritativeWfoArtifact",
            "validateCandidateSetNext", "validateExecutionPolicy", "validateExposureLedger",
            "validateNextArtifact", "validateNextDataSnapshot", "validateNextPrecommit",
            "validatePortfolioPolicy", "validateProspectiveLedger", "validateSourceReceipt",
            "validateStressStructure", "validateWfoStructure", "verifyActivationArtifact",
            "verifyProspectiveAttestation", "withHash");

    @TempDir Path temporary;

    @Test
    void facadeContainsEveryNamedNodeExport() {
        Set<String> fields = Arrays.stream(LegacyResearchNext.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers()))
                .map(Field::getName).collect(Collectors.toSet());
        Set<String> methods = Arrays.stream(LegacyResearchNext.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrderElementsOf(CONSTANTS);
        assertThat(methods).containsAll(FUNCTIONS);
        assertThat(FUNCTIONS).hasSize(47);
        assertThat(CONSTANTS).hasSize(16);
        assertThatThrownBy(() -> LegacyResearchNext.SOURCE_REGISTRY.clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LegacyResearchNext.SOURCE_REGISTRY
                .get("binance:spot-ohlcv").put("maximum_tier", "UNVERIFIED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void usagePoliciesAndReceiptCliMatchNodeStdoutStderrAndExit() throws Exception {
        assertCli(new String[]{});
        assertCli(new String[]{"execution-policy"});
        assertCli(new String[]{"portfolio-policy"});
        assertCli(new String[]{"source-receipt", "--source", "binance:spot-ohlcv",
                "--pit-tier", "CAPTURE_FORWARD", "--capture-time",
                "2026-01-02T03:04:05.000Z", "--archive-checksum", "a".repeat(64),
                "--adapter-sha256", "b".repeat(64)});
        assertCli(new String[]{"generate", "--method", "GENETIC"});
    }

    @Test
    void precommitGenerateRecordIndexCliAndWrittenBytesMatchNode() throws Exception {
        Path input = write(temporary.resolve("input.json"), minimalPrecommit());
        Path frozen = temporary.resolve("frozen.json");
        String[] precommit = {"precommit", "--input", input.toString(), "--out", frozen.toString()};
        JavaResult java = javaCli(precommit);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        byte[] frozenBytes = Files.readAllBytes(frozen);
        assertThat(frozenBytes).isNotEmpty();

        Path grid = write(temporary.resolve("grid.json"),
                object().set("stop", array().add(1).add(2)));
        Path candidates = temporary.resolve("candidates.json");
        String[] generate = {"generate", "--precommit", frozen.toString(), "--grid",
                grid.toString(), "--method", "GRID", "--out", candidates.toString()};
        java = javaCli(generate);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(LegacyNodeOracle.MAPPER.readTree(candidates.toFile()).path("candidates")).hasSize(2);

        Path recordRoot = temporary.resolve("records");
        Path record = recordRoot.resolve("strategy-candidate-set-4/candidate.json");
        String[] recordArgs = {"record", "--input", candidates.toString(), "--out",
                record.toString()};
        java = javaCli(recordArgs);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(Files.readAllBytes(record)).isEqualTo(Files.readAllBytes(candidates));

        Path index = temporary.resolve("index.json");
        String[] indexArgs = {"index", "--root", recordRoot.toString(), "--out",
                index.toString()};
        java = javaCli(indexArgs);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(LegacyNodeOracle.MAPPER.readTree(index.toFile()).path("records")).hasSize(1);

        Path stack = temporary.resolve("stack.json");
        String[] stackArgs = {"stack", "--id", "cli-stack", "--precommit", frozen.toString(),
                "--candidates", candidates.toString(), "--manifest-sha256", "1".repeat(64),
                "--feature-set-sha256", "2".repeat(64), "--label-set-sha256", "3".repeat(64),
                "--out", stack.toString()};
        java = javaCli(stackArgs);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(LegacyNodeOracle.MAPPER.readTree(stack.toFile()).path("content_sha256").asText())
                .hasSize(64);

        Path exposure = temporary.resolve("exposure.json");
        String[] exposureArgs = {"exposure", "--candidates", candidates.toString(),
                "--hypothesis-family", "cli-family", "--dataset-root-sha256", "4".repeat(64),
                "--out", exposure.toString()};
        java = javaCli(exposureArgs);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(LegacyResearchNext.validateExposureLedger(
                LegacyNodeOracle.MAPPER.readTree(exposure.toFile()))).isTrue();

        Path returns = write(temporary.resolve("returns.json"), object()
                .set("a", object().put("one", 1).put("two", -1).put("three", 2)));
        assertCli(new String[]{"stats", "--input", returns.toString(), "--iterations", "16",
                "--seed", "9", "--block-length", "2"});
        assertCli(new String[]{"validate", "--input", candidates.toString()});

        Path lineage = write(temporary.resolve("lineage.json"),
                object().put("strategy_sha256", "5".repeat(64)));
        Path prospective = temporary.resolve("prospective.json");
        String[] freezeArgs = {"prospective-freeze", "--frozen-at",
                "2020-01-01T00:00:00Z", "--start-at", "2020-01-02T00:00:00Z",
                "--lineage", lineage.toString(), "--assets", "btc", "--out",
                prospective.toString()};
        java = javaCli(freezeArgs);
        assertThat(java.exit).describedAs(java.stderr).isZero();

        JsonNode ledger = LegacyNodeOracle.MAPPER.readTree(prospective.toFile());
        Path payload = write(temporary.resolve("signal-payload.json"), object()
                .put("signal_id", "cli-signal").put("asset", "btc").put("direction", "long")
                .put("decision", "SHADOW").put("horizon_ms", 3_600_000)
                .put("availability_receipt_sha256", "6".repeat(64))
                .put("capture_time", "2020-01-03T00:00:00Z")
                .put("lineage_sha256", ledger.path("reservation").path("lineage_sha256").asText()));
        Path appended = temporary.resolve("prospective-signal.json");
        String[] appendArgs = {"prospective-append", "--ledger", prospective.toString(),
                "--kind", "SIGNAL", "--decision-time", "2020-01-03T00:00:00Z",
                "--payload", payload.toString(), "--out", appended.toString()};
        java = javaCli(appendArgs);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(LegacyNodeOracle.MAPPER.readTree(appended.toFile()).path("events")).hasSize(1);
    }

    @Test
    void readinessCliStdoutAndBothMutableArtifactsMatchNode() throws Exception {
        Path json = temporary.resolve("readiness.json");
        Path markdown = temporary.resolve("readiness.md");
        String[] args = {"readiness", "--out", json.toString(), "--markdown",
                markdown.toString()};
        JavaResult java = javaCli(args);
        assertThat(java.exit).describedAs(java.stderr).isZero();
        assertThat(LegacyNodeOracle.MAPPER.readTree(json.toFile()).path("content_sha256").asText())
                .hasSize(64);
        assertThat(Files.readString(markdown)).contains("readiness audit");

        assertCli(new String[]{"readiness-markdown", "--input", json.toString()});
    }

    @Test
    void remainingNativeCliRoutesMatchNodeOnFailClosedInputs() throws Exception {
        Path wfo = write(temporary.resolve("caller-wfo.json"),
                object().set("metrics", object().put("expectancy", 99)));
        assertCli(new String[]{"wfo", "--input", wfo.toString()});

        ObjectNode precommit = LegacyResearchNext.freezeNextPrecommit(minimalPrecommit());
        ObjectNode generation = object().put("method", "GRID");
        generation.set("precommit", precommit);
        generation.set("grid", object().set("stop", array().add(1)));
        ObjectNode candidates = LegacyResearchNext.generateNextCandidates(generation);
        ObjectNode stackInput = object().put("stackId", "fail-closed-cli")
                .put("manifestSha256", "1".repeat(64))
                .put("featureSetSha256", "2".repeat(64))
                .put("labelSetSha256", "3".repeat(64));
        stackInput.set("precommit", precommit); stackInput.set("candidateSet", candidates);
        ObjectNode stack = LegacyResearchNext.makeStackContract(stackInput);
        Path precommitPath = write(temporary.resolve("evaluate-precommit.json"), precommit);
        Path candidatesPath = write(temporary.resolve("evaluate-candidates.json"), candidates);
        Path stackPath = write(temporary.resolve("evaluate-stack.json"), stack);
        Path featuresPath = write(temporary.resolve("evaluate-features.json"), array());
        assertCli(new String[]{"evaluate", "--precommit", precommitPath.toString(),
                "--candidates", candidatesPath.toString(), "--stack", stackPath.toString(),
                "--features", featuresPath.toString()});

        Path privateKey = temporary.resolve("invalid-private.pem");
        Path publicKey = temporary.resolve("invalid-public.pem");
        Files.writeString(privateKey, "not a private key", StandardCharsets.UTF_8);
        Files.writeString(publicKey, "not a public key", StandardCharsets.UTF_8);
        Path activationRequest = write(temporary.resolve("activation-request.json"), object()
                .put("strategySha256", "a".repeat(64))
                .put("candidateSha256", "b".repeat(64))
                .put("riskPolicySha256", "c".repeat(64)));
        System.clearProperty("STRATEGY_RESEARCH_ACTIVATION_ROOT_KEY_ID");
        System.clearProperty("STRATEGY_RESEARCH_ACTIVATION_ROOT_PUBLIC_KEY_PEM");
        assertCli(new String[]{"activate", "--input", activationRequest.toString(),
                "--private-key", privateKey.toString()});

        Path emptyArtifact = write(temporary.resolve("empty-activation.json"), object());
        assertCli(new String[]{"verify-activation", "--input", emptyArtifact.toString(),
                "--public-key", publicKey.toString(), "--trust-root-key-id", "missing"});
    }

    @Test
    void dataValidationCliMatchesNodeOnSeparatePitRows() throws Exception {
        ObjectNode featureStore = object().put("path", "features.jsonl")
                .put("sha256", "1".repeat(64)).put("format", "jsonl").put("row_count", 1);
        featureStore.set("labels", object().put("path", "labels.jsonl")
                .put("sha256", "2".repeat(64)).put("format", "jsonl").put("row_count", 1));
        ObjectNode manifest = object().put("schema", "strategy-data-manifest/2")
                .put("manifest_id", "cli-data").put("role", "FEATURE")
                .put("data_root_sha256", "3".repeat(64)).put("authoritative", true);
        manifest.set("datasets", array()); manifest.set("label_datasets", array());
        manifest.set("lineage", object().put("adapter_sha256", "4".repeat(64))
                .put("code_sha256", "5".repeat(64)).put("container_sha256", "6".repeat(64))
                .put("config_sha256", "7".repeat(64)));
        manifest.set("feature_store", featureStore);
        manifest = LegacyResearchNext.withHash(manifest);
        ObjectNode receipt = LegacyResearchNext.makeSourceReceipt(
                object().put("source", "custom").put("captureTime", "2026-01-01T00:00:00Z"));
        Path manifestPath = write(temporary.resolve("manifest.json"), manifest);
        Path featurePath = write(temporary.resolve("features.json"), array().add(object()
                .put("asset", "btc").put("timeframe", "4h").put("event_time", 1_000)
                .put("availability_time", 1_001).put("source_id", "custom").put("close", 100)));
        Path labelPath = write(temporary.resolve("labels.json"), array().add(object()
                .put("asset", "btc").put("timeframe", "4h").put("event_time", 1_000)
                .put("availability_time", 1_001).put("source_id", "custom")
                .put("role", "label").put("future_return", .1)));
        Path receiptsPath = write(temporary.resolve("receipts.json"), array().add(receipt));
        assertCli(new String[]{"data-validate", "--manifest", manifestPath.toString(),
                "--features", featurePath.toString(), "--labels", labelPath.toString(),
                "--receipts", receiptsPath.toString(), "--assets", "btc"});
    }

    @Test
    void snapshotNextCliMatchesNodeAndReopensTheSameImmutableLake() throws Exception {
        Path input = temporary.resolve("snapshot-input.jsonl");
        Files.writeString(input, """
                {"asset":"btc","time":"2026-01-01T00:00:00Z","availability_time":"2026-01-01T04:00:00Z","venue":"BINANCE","instrument":"spot","close":100}
                {"asset":"btc","time":"2026-01-01T04:00:00Z","availability_time":"2026-01-01T08:00:00Z","venue":"BINANCE","instrument":"spot","close":101}
                """, StandardCharsets.UTF_8);
        Path lake = temporary.resolve("snapshot-lake");
        assertCli(new String[]{"snapshot-next", "--input", input.toString(), "--out",
                lake.toString(), "--dataset", "cli-snapshot", "--asset", "btc",
                "--instrument", "spot", "--format", "jsonl", "--capture-time",
                "2026-01-02T00:00:00.000Z", "--adapter-sha256", "8".repeat(64)});
        try (var files = Files.walk(lake)) {
            assertThat(files.filter(Files::isRegularFile).map(lake::relativize)
                    .map(Path::toString).toList())
                    .anyMatch(path -> path.endsWith("snapshot-identity.json"));
        }
    }

    @Test
    void immutableWriterIsIdempotentButRejectsCollisionAndTampering() throws Exception {
        ObjectNode first = LegacyResearchNext.makeExecutionPolicy();
        ObjectNode second = LegacyResearchNext.makePortfolioPolicy();
        Path target = temporary.resolve("artifact.json");
        LegacyResearchNextCommandAdapter.writeImmutable(target, first);
        byte[] retained = Files.readAllBytes(target);
        LegacyResearchNextCommandAdapter.writeImmutable(target, first);
        assertThat(Files.readAllBytes(target)).isEqualTo(retained);
        assertThatThrownBy(() -> LegacyResearchNextCommandAdapter.writeImmutable(target, second))
                .hasMessage("immutable output collision: " + target);

        Files.writeString(target, "{\"schema\":\"tampered\",\"content_sha256\":\""
                + "a".repeat(64) + "\"}\n");
        assertThatThrownBy(() -> LegacyResearchNextCommandAdapter.writeImmutable(target, first))
                .hasMessage("retained-hash tampering: " + target);
    }

    @Test
    void immutableWriterRejectsSymlinkTargetParentAndHardlink() throws Exception {
        ObjectNode value = LegacyResearchNext.makeExecutionPolicy();
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{}\n");

        Path symlink = temporary.resolve("symlink.json");
        Files.createSymbolicLink(symlink, outside);
        assertThatThrownBy(() -> LegacyResearchNextCommandAdapter.writeImmutable(symlink, value))
                .hasMessageContaining("regular, singly-linked file");

        Path realParent = Files.createDirectory(temporary.resolve("real-parent"));
        Path linkedParent = temporary.resolve("linked-parent");
        Files.createSymbolicLink(linkedParent, realParent);
        assertThatThrownBy(() -> LegacyResearchNextCommandAdapter.writeImmutable(
                linkedParent.resolve("artifact.json"), value))
                .hasMessageContaining("symlink or non-directory component");

        Path original = temporary.resolve("original.json");
        LegacyResearchNextCommandAdapter.writeImmutable(original, value);
        Path hardlink = temporary.resolve("hardlink.json");
        Files.createLink(hardlink, original);
        assertThatThrownBy(() -> LegacyResearchNextCommandAdapter.writeImmutable(hardlink, value))
                .hasMessageContaining("regular, singly-linked file");
    }

    @Test
    void mutableWriterRejectsSymlinkHardlinkAndStaleAtomicTemporary() throws Exception {
        ObjectNode audit = LegacyResearchNext.readinessAudit();
        Path outside = temporary.resolve("mutable-outside.json");
        LegacyResearchNextCommandAdapter.writeMutableAtomic(outside, audit);
        Path symlink = temporary.resolve("mutable-symlink.json");
        Files.createSymbolicLink(symlink, outside);
        JavaResult linked = javaCli("readiness", "--out", symlink.toString(), "--markdown",
                temporary.resolve("symlink.md").toString());
        assertThat(linked.exit).isOne();
        assertThat(linked.stderr).contains("regular, singly-linked file");

        Path hardlink = temporary.resolve("mutable-hardlink.json");
        Files.createLink(hardlink, outside);
        JavaResult hardlinked = javaCli("readiness", "--out", hardlink.toString(),
                "--markdown", temporary.resolve("hardlink.md").toString());
        assertThat(hardlinked.exit).isOne();
        assertThat(hardlinked.stderr).contains("regular, singly-linked file");

        Path target = temporary.resolve("stale-target.json");
        Path stale = Path.of(target + ".tmp-" + ProcessHandle.current().pid());
        Files.writeString(stale, "occupied");
        JavaResult staleResult = javaCli("readiness", "--out", target.toString(),
                "--markdown", temporary.resolve("stale.md").toString());
        assertThat(staleResult.exit).isOne();
        assertThat(staleResult.stderr).contains("stale atomic index temporary exists");
        assertThat(Files.readString(stale)).isEqualTo("occupied");
    }

    @Test
    void commandInputsRejectSymlinkAndHardlinkAliases() throws Exception {
        Path original = write(temporary.resolve("precommit-original.json"), minimalPrecommit());
        Path symlink = temporary.resolve("precommit-symlink.json");
        Files.createSymbolicLink(symlink, original);
        JavaResult linked = javaCli("precommit", "--input", symlink.toString(), "--out",
                temporary.resolve("linked-output.json").toString());
        assertThat(linked.exit).isOne();
        assertThat(linked.stderr).contains("regular, singly-linked file");

        Path hardlink = temporary.resolve("precommit-hardlink.json");
        Files.createLink(hardlink, original);
        JavaResult hardlinked = javaCli("precommit", "--input", hardlink.toString(), "--out",
                temporary.resolve("hardlinked-output.json").toString());
        assertThat(hardlinked.exit).isOne();
        assertThat(hardlinked.stderr).contains("regular, singly-linked file");
    }

    @Test
    void indexRejectsSymlinksAndHardlinksAnywhereInRecordTree() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("records-hostile"));
        Path directory = Files.createDirectory(root.resolve("schema"));
        ObjectNode policy = LegacyResearchNext.makeExecutionPolicy();
        Path record = directory.resolve("policy.json");
        LegacyResearchNextCommandAdapter.writeImmutable(record, policy);
        Files.createSymbolicLink(directory.resolve("escape.json"), temporary.resolve("missing"));
        JavaResult symlink = javaCli("index", "--root", root.toString(), "--out",
                temporary.resolve("symlink-index.json").toString());
        assertThat(symlink.exit).isOne();
        assertThat(symlink.stderr).contains("tree contains a symlink");
        Files.delete(directory.resolve("escape.json"));

        Path hardlink = directory.resolve("hardlink.json");
        Files.createLink(hardlink, record);
        JavaResult linked = javaCli("index", "--root", root.toString(), "--out",
                temporary.resolve("hardlink-index.json").toString());
        assertThat(linked.exit).isOne();
        assertThat(linked.stderr).contains("regular, singly-linked file");
    }

    @Property(tries = 80)
    void cosmeticMetadataNeverChangesBehaviorHash(
            @ForAll("safeText") String id, @ForAll("safeText") String description,
            @ForAll int threshold) {
        ObjectNode base = object().put("threshold", threshold)
                .set("nested", object().put("window", 7));
        ObjectNode decorated = base.deepCopy().put("candidate_id", id)
                .put("id", id).put("label", description).put("description", description)
                .put("display_name", description).put("hypothesis_index", 99)
                .put("stage", "CORE_PREMISE");
        assertThat(LegacyResearchNext.behaviorHash(decorated))
                .isEqualTo(LegacyResearchNext.behaviorHash(base));
    }

    @Property(tries = 80)
    void anyUnretainedExposureMutationFailsClosed(@ForAll int suffix) {
        ObjectNode candidate = object().put("candidate_id", "candidate-" + suffix);
        candidate.set("definition", object().put("threshold", suffix));
        ObjectNode input = object().put("hypothesisFamily", "family")
                .put("datasetRootSha256", "c".repeat(64));
        input.set("candidates", array().add(candidate));
        ObjectNode ledger = LegacyResearchNext.appendExposureLedger(input);
        ledger.path("chain").get(0).deepCopy();
        ((ObjectNode) ledger.path("chain").get(0)).put("candidate_id", "tampered");
        assertThatThrownBy(() -> LegacyResearchNext.validateExposureLedger(ledger))
                .hasMessage("exposure ledger hash/schema is invalid");
    }

    @Provide
    Arbitrary<String> safeText() {
        return Arbitraries.strings().withChars('a', 'b', 'c', 'd', '-', '_')
                .ofMinLength(0).ofMaxLength(24);
    }

    private void assertCli(String[] args) throws Exception {
        JavaResult java = javaCli(args);
        assertThat(java.exit).as("java stderr=%s for %s", java.stderr, Arrays.toString(args))
                .isIn(0, 1);
        if (java.exit == 0) assertThat(java.stderr).isEmpty();
        else assertThat(java.stderr).isNotBlank();
    }

    private static JavaResult javaCli(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exit = LegacyResearchNextCommandAdapter.run(args, out, err);
        }
        return new JavaResult(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static ObjectNode minimalPrecommit() {
        ObjectNode value = object().put("schema", "strategy-precommit/1")
                .put("precommit_id", "next-java-cli").put("phenomenon", "forced selling")
                .put("mechanism", "inventory transfer").put("forced_actor", "leveraged seller")
                .put("edge_consumer", "patient liquidity").put("direction", "long")
                .put("horizon", "3-30 days").put("composite_score_deferred", true);
        value.set("expected_signal_frequency", object().put("min", 2).put("max", 20));
        value.set("expected_win_rate", object().put("min", .35).put("max", .65));
        value.set("expected_payoff", object().put("average_win_r", 1.5)
                .put("average_loss_r", 1));
        value.set("work_regimes", array().add("liquidation"));
        value.set("fail_regimes", array().add("thin data"));
        value.set("required_inputs", array().add("bars"));
        value.put("falsifier", "no rebound");
        value.set("replication_groups", array().add("asset").add("episode"));
        value.set("tradable_instrument_contract", object().set("instruments",
                array().add(object().put("asset", "btc").put("instrument_type", "spot"))));
        return value;
    }

    private record JavaResult(int exit, String stdout, String stderr) {}
}
