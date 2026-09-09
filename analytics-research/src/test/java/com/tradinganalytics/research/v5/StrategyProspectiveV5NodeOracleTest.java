package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Differential and surface checks for the complete strategy-prospective-v5 export set. */
final class StrategyProspectiveV5NodeOracleTest {
    @Test
    void allNodeExportsHaveJavaBindings() {
        Set<String> expected = Set.of("appendCompletedBarCycle",
                "appendProspectiveEvent", "appendProspectiveEventsAtomically", "createProspectiveLedger",
                "createReplayRegistry", "hash", "makeTrustRootBundle", "ownHash", "publishProspectiveEvidence",
                "readProspectiveLedger", "readReplayRegistry", "recoverProspectiveLedger", "reserveReplayNonce",
                "revokeProspectiveNonce", "rotateTrustRoot", "signPayload", "verifyCompletedBarNoOp",
                "verifyPayload", "verifyProspectivePublication", "verifyTrustRoot", "withHash");
        Set<String> actual = Set.of(StrategyProspectiveV5.class.getDeclaredMethods()).stream()
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
        assertThat(actual).containsAll(expected);
        assertThat(Set.of(StrategyProspectiveV5.class.getDeclaredFields()).stream()
                .filter(field -> java.lang.reflect.Modifier.isPublic(field.getModifiers())
                        && java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName)).contains("MAX_PROSPECTIVE_LEASE_MS");
        assertThat(StrategyProspectiveV5.MAX_PROSPECTIVE_LEASE_MS).isEqualTo(7_776_000_000L);
    }

    @Test
    void canonicalHashHelpersMatchNode() throws Exception {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("z", 3).put("a", "x");
        value.putObject("nested").put("b", true).put("a", 2);
        String hash = StrategyProspectiveV5.hash(value);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(JsonHashes.canonicalSha256(value));
        assertThat(StrategyProspectiveV5.ownHash(value)).isEqualTo(hash);
        ObjectNode withHash = StrategyProspectiveV5.withHash(value);
        assertThat(withHash.path("content_sha256").asText()).isEqualTo(StrategyProspectiveV5.ownHash(withHash));
        assertThat(withHash.path("a").asText()).isEqualTo("x");
    }

    @Test
    void createReadRoundTripKeepsImmutableBytes() throws Exception {
        Path javaPath = Files.createTempDirectory("prospective-v5-java-");
        String lineage = StrategyProspectiveV5.hash("lineage");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("path", javaPath.toString())
                .put("lineage_sha256", lineage);
        options.putArray("assets").add("BTC");
        ObjectNode snapshot = StrategyProspectiveV5.createProspectiveLedger(options);
        assertThat(snapshot.path("sequence").asInt()).isZero();
        byte[] head = Files.readAllBytes(javaPath.resolve("HEAD.json"));
        ObjectNode read = StrategyProspectiveV5.readProspectiveLedger(javaPath, JsonHashes.mapper().createObjectNode()
                .put("nowAt", System.currentTimeMillis()));
        assertThat(read.path("schema").asText()).isEqualTo(snapshot.path("schema").asText());
        assertThat(read.path("sequence").asInt()).isZero();
        assertThat(read.path("head_sha256").asText()).isEqualTo(snapshot.path("head_sha256").asText());
        assertThat(Files.readAllBytes(javaPath.resolve("HEAD.json"))).isEqualTo(head);
    }

    @Test
    void appendCasAndFaultRecoveryMatchNodeEventShape() throws Exception {
        Path artifacts = Files.createTempDirectory("prospective-v5-artifacts-");
        Path javaPath = Files.createTempDirectory("prospective-v5-append-java-");
        String lineage = StrategyProspectiveV5.hash("append-lineage");
        Path receiptPath = writeReceipt(artifacts, lineage, "bar-1", "btc", "2026-01-01T00:00:00.000Z");
        String receiptSha = StrategyProspectiveV5.hash(Files.readAllBytes(receiptPath));
        ObjectNode create = JsonHashes.mapper().createObjectNode().put("path", javaPath.toString())
                .put("lineage_sha256", lineage);
        create.putArray("assets").add("btc");
        ObjectNode javaLedger = StrategyProspectiveV5.createProspectiveLedger(create);
        ObjectNode event = signalEvent(receiptPath, receiptSha, lineage);
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("path", javaPath.toString())
                .put("expected_head_sha256", javaLedger.path("head_sha256").asText()).put("nowAt", epoch("2026-01-02T00:00:00.000Z"));
        append.set("event", event);
        ObjectNode javaEvent = StrategyProspectiveV5.appendProspectiveEvent(append);

        assertThat(javaEvent.path("sequence").asInt()).isEqualTo(1);
        assertThat(javaEvent.path("event_sha256").asText()).hasSize(64);
        assertThat(javaEvent.path("previous_head_sha256").asText()).isEqualTo(javaLedger.path("head_sha256").asText());

        ObjectNode retry = StrategyProspectiveV5.appendProspectiveEvent(append);
        assertJson(retry, javaEvent);
        assertThatThrownBy(() -> StrategyProspectiveV5.appendProspectiveEvent(
                append.deepCopy().put("expected_head_sha256", StrategyProspectiveV5.hash("wrong"))))
                .hasMessage("prospective ledger CAS head mismatch");

        Path recoveryPath = Files.createTempDirectory("prospective-v5-recovery-");
        ObjectNode recoveryCreate = create.deepCopy().put("path", recoveryPath.toString());
        ObjectNode recoveryLedger = StrategyProspectiveV5.createProspectiveLedger(recoveryCreate);
        AtomicBoolean tripped = new AtomicBoolean();
        assertThatThrownBy(() -> StrategyProspectiveV5.appendProspectiveEventsAtomically(recoveryPath,
                JsonHashes.mapper().createArrayNode().add(event), recoveryLedger.path("head_sha256").asText(),
                epoch("2026-01-02T00:00:00.000Z"), boundary -> { if ("after-stage-1".equals(boundary) && tripped.compareAndSet(false, true)) throw new IllegalStateException("fault"); }))
                .hasMessage("fault");
        ObjectNode recovered = StrategyProspectiveV5.readProspectiveLedger(recoveryPath,
                JsonHashes.mapper().createObjectNode().put("nowAt", epoch("2026-01-02T00:00:00.000Z")));
        assertThat(recovered.path("sequence").asInt()).isEqualTo(1);
        assertThat(Files.exists(recoveryPath.resolve(".lock"))).isFalse();
    }

    @Test
    void replayUseIsImmutableAndMatchesNode() throws Exception {
        Path javaPath = Files.createTempDirectory("prospective-v5-replay-java-");
        String lineage = StrategyProspectiveV5.hash("replay-lineage");
        ObjectNode create = JsonHashes.mapper().createObjectNode().put("path", javaPath.toString())
                .put("lineage_sha256", lineage);
        ObjectNode javaRegistry = StrategyProspectiveV5.createReplayRegistry(create);
        String payloadSha = StrategyProspectiveV5.hash("publication-payload");
        ObjectNode reserve = JsonHashes.mapper().createObjectNode().put("path", javaPath.toString())
                .put("nonce", "nonce-1").put("expected_head_sha256", javaRegistry.path("head_sha256").asText())
                .put("publication_payload_sha256", payloadSha).put("nowAt", epoch("2026-01-02T00:00:00.000Z"));
        ObjectNode javaEntry = StrategyProspectiveV5.reserveReplayNonce(reserve);
        assertThat(javaEntry.path("nonce").asText()).isEqualTo("nonce-1");
        assertThat(javaEntry.path("publication_payload_sha256").asText()).isEqualTo(payloadSha);
        assertThat(javaEntry.path("sequence").asInt()).isEqualTo(1);
        ObjectNode retry = reserve.deepCopy().put("expected_head_sha256",
                StrategyProspectiveV5.readReplayRegistry(javaPath).path("current_head_sha256").asText());
        assertThatThrownBy(() -> StrategyProspectiveV5.reserveReplayNonce(retry))
                .hasMessage("replay nonce already used or revoked");
        assertThat(StrategyProspectiveV5.readReplayRegistry(javaPath).path("sequence").asInt()).isEqualTo(1);
    }

    @Test
    void trustRootAndRotationSignaturesMatchNodeAndVerifyPins() throws Exception {
        KeyPair root = keyPair();
        KeyPair asset = keyPair();
        KeyPair portfolio = keyPair();
        KeyPair revocation = keyPair();
        ObjectNode options = rootOptions(root, asset, portfolio, revocation);
        ObjectNode javaRoot = StrategyProspectiveV5.makeTrustRootBundle(options);
        assertThat(javaRoot.path("content_sha256").asText()).isEqualTo(StrategyProspectiveV5.ownHash(javaRoot));
        ObjectNode verify = JsonHashes.mapper().createObjectNode().put("pinnedFingerprint", javaRoot.path("pinned_fingerprint").asText())
                .put("pinnedGenesisFingerprint", javaRoot.path("genesis_pinned_fingerprint").asText())
                .put("nowAt", epoch("2026-01-02T00:00:00.000Z"));
        assertThat(StrategyProspectiveV5.verifyTrustRoot(javaRoot, verify)).isTrue();
        KeyPair nextRoot = keyPair();
        ObjectNode rotatedOptions = options.deepCopy();
        rotatedOptions.set("previousRoot", javaRoot);
        rotatedOptions.put("previousRootPrivateKeyPem", pem("PRIVATE KEY", root.getPrivate().getEncoded()));
        rotatedOptions.put("rootKeyId", "root-2").put("rootPublicKeyPem", pem("PUBLIC KEY", nextRoot.getPublic().getEncoded()))
                .put("rootPrivateKeyPem", pem("PRIVATE KEY", nextRoot.getPrivate().getEncoded())).put("generation", 2);
        ObjectNode javaRotated = StrategyProspectiveV5.rotateTrustRoot(rotatedOptions);
        assertThat(javaRotated.path("generation").asInt()).isEqualTo(2);
        assertThat(javaRotated.path("previous_root_sha256").asText()).isEqualTo(javaRoot.path("content_sha256").asText());
        ObjectNode rotatedVerify = verify.deepCopy().put("pinnedFingerprint", javaRotated.path("pinned_fingerprint").asText());
        rotatedVerify.set("previousRoot", javaRoot);
        assertThat(StrategyProspectiveV5.verifyTrustRoot(javaRotated, rotatedVerify)).isTrue();
    }

    @Test
    void tamperSymlinkHardlinkAndStaleLockFailClosed() throws Exception {
        Path artifacts = Files.createTempDirectory("prospective-v5-security-artifacts-");
        Path ledgerPath = Files.createTempDirectory("prospective-v5-security-ledger-");
        String lineage = StrategyProspectiveV5.hash("security-lineage");
        Path receipt = writeReceipt(artifacts, lineage, "bar-1", "btc", "2026-01-01T00:00:00.000Z");
        String receiptSha = StrategyProspectiveV5.hash(Files.readAllBytes(receipt));
        ObjectNode create = JsonHashes.mapper().createObjectNode().put("path", ledgerPath.toString()).put("lineage_sha256", lineage);
        create.putArray("assets").add("btc");
        ObjectNode ledger = StrategyProspectiveV5.createProspectiveLedger(create);
        ObjectNode append = JsonHashes.mapper().createObjectNode().put("path", ledgerPath.toString())
                .put("expected_head_sha256", ledger.path("head_sha256").asText()).put("nowAt", epoch("2026-01-02T00:00:00.000Z"));
        append.set("event", signalEvent(receipt, receiptSha, lineage));
        StrategyProspectiveV5.appendProspectiveEvent(append);
        Path event = Files.list(ledgerPath.resolve("events")).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(event);
        Files.write(event, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StrategyProspectiveV5.readProspectiveLedger(ledgerPath,
                JsonHashes.mapper().createObjectNode().put("nowAt", epoch("2026-01-02T00:00:00.000Z"))))
                .hasMessageContaining("physical source byte hash mismatch");
        Files.write(event, bytes);
        Path lock = ledgerPath.resolve(".lock");
        ObjectNode lockBody = JsonHashes.mapper().createObjectNode().put("token", "a".repeat(64))
                .put("acquired_at", "2020-01-01T00:00:00.000Z");
        Files.write(lock, NodePrettyJson.write(lockBody).getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(lock, java.nio.file.attribute.FileTime.fromMillis(epoch("2020-01-01T00:00:00.000Z")));
        assertThat(StrategyProspectiveV5.readProspectiveLedger(ledgerPath,
                JsonHashes.mapper().createObjectNode().put("nowAt", epoch("2026-01-02T00:00:00.000Z"))).path("sequence").asInt()).isEqualTo(1);
        Path hardlink = ledgerPath.resolve("events/hardlink.json");
        try {
            Files.createLink(hardlink, event);
            assertThatThrownBy(() -> StrategyProspectiveV5.readProspectiveLedger(ledgerPath,
                    JsonHashes.mapper().createObjectNode().put("nowAt", epoch("2026-01-02T00:00:00.000Z"))))
                    .hasMessageContaining("singly-linked");
        } finally {
            Files.deleteIfExists(hardlink);
        }
        Path symlink = ledgerPath.resolveSibling(ledgerPath.getFileName() + "-link");
        try {
            Files.createSymbolicLink(symlink, ledgerPath);
            assertThatThrownBy(() -> StrategyProspectiveV5.readProspectiveLedger(symlink)).isInstanceOf(RuntimeException.class);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // Symlink support is platform-dependent; production reads remain no-link.
        }
    }

    @Test
    void completedBarCycleSupportsSignalNoOpAndAtomicOutcome() throws Exception {
        CycleFixture signalFixture = cycleFixture(false);
        ObjectNode signalResult = StrategyProspectiveV5.appendCompletedBarCycle(signalFixture.options());
        assertThat(signalResult.path("signal").path("kind").asText()).isEqualTo("SIGNAL");
        assertThat(signalResult.path("outcome").isNull()).isTrue();
        ObjectNode signalLedger = StrategyProspectiveV5.readProspectiveLedger(signalFixture.ledgerPath());
        assertThat(signalLedger.path("sequence").asInt()).isEqualTo(1);
        assertThat(StrategyProspectiveV5.verifyCompletedBarNoOp(signalFixture.noOpOptions(signalLedger))).isTrue();
        assertThatThrownBy(() -> StrategyProspectiveV5.verifyCompletedBarNoOp(
                signalFixture.noOpOptions(signalLedger).put("featureInputSha256", hash("wrong-feature"))))
                .hasMessage("same completed-bar identity has divergent source or signal payload");

        CycleFixture outcomeFixture = cycleFixture(true);
        ObjectNode outcomeResult = StrategyProspectiveV5.appendCompletedBarCycle(outcomeFixture.options());
        assertThat(outcomeResult.path("signal").path("sequence").asInt()).isEqualTo(1);
        assertThat(outcomeResult.path("outcome").path("sequence").asInt()).isEqualTo(2);
        assertThat(outcomeResult.path("outcome").path("kind").asText()).isEqualTo("OUTCOME");
        assertThat(StrategyProspectiveV5.readProspectiveLedger(outcomeFixture.ledgerPath()).path("sequence").asInt()).isEqualTo(2);
    }

    @Test
    void completedBarCycleRejectsPhysicalTamperAndIncompleteOutcome() throws Exception {
        CycleFixture fixture = cycleFixture(false);
        byte[] original = Files.readAllBytes(fixture.sourceReceiptPath());
        Files.write(fixture.sourceReceiptPath(), "tampered-source-receipt".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StrategyProspectiveV5.appendCompletedBarCycle(fixture.options()))
                .hasMessage("physical source byte hash mismatch");
        Files.write(fixture.sourceReceiptPath(), original);

        CycleFixture outcome = cycleFixture(true);
        ObjectNode incomplete = outcome.options().deepCopy();
        incomplete.remove("labelSourcePath");
        assertThatThrownBy(() -> StrategyProspectiveV5.appendCompletedBarCycle(incomplete))
                .hasMessage("complete physical outcome artifacts are required");
        ObjectNode expired = outcome.options().deepCopy().put("nowAt", epoch("2025-12-31T00:00:00.000Z"));
        assertThatThrownBy(() -> StrategyProspectiveV5.appendCompletedBarCycle(expired))
                .hasMessage("bar is not completed");
    }

    @Test
    void replayRevocationIsSignedIdempotentAndFailClosed() throws Exception {
        Path root = Files.createTempDirectory("prospective-v5-revocation-");
        Path replayPath = root.resolve("replay");
        String lineage = hash("revocation-lineage");
        ObjectNode registry = StrategyProspectiveV5.createReplayRegistry(
                JsonHashes.mapper().createObjectNode().put("path", replayPath.toString()).put("lineage_sha256", lineage));
        String payloadHash = hash("revocation-payload");
        ObjectNode use = JsonHashes.mapper().createObjectNode().put("path", replayPath.toString()).put("nonce", "nonce-revoke")
                .put("expected_head_sha256", registry.path("head_sha256").asText()).put("publication_payload_sha256", payloadHash)
                .put("nowAt", epoch("2026-01-02T00:00:00.000Z"));
        StrategyProspectiveV5.reserveReplayNonce(use);

        KeyPair rootKey = keyPair(), revocationKey = keyPair(), assetKey = keyPair(), portfolioKey = keyPair();
        ObjectNode rootOptions = rootOptions(rootKey, assetKey, portfolioKey, revocationKey);
        ObjectNode trustRoot = StrategyProspectiveV5.makeTrustRootBundle(rootOptions);
        ObjectNode revoke = JsonHashes.mapper().createObjectNode().put("path", replayPath.toString()).put("nonce", "nonce-revoke")
                .put("reason", "operator review").put("expectedHeadSha256", StrategyProspectiveV5.readReplayRegistry(replayPath).path("current_head_sha256").asText())
                .put("nowAt", epoch("2026-01-02T00:00:00.000Z"))
                .put("pinnedTrustRootFingerprint", trustRoot.path("pinned_fingerprint").asText())
                .put("pinnedTrustRootGenesisFingerprint", trustRoot.path("genesis_pinned_fingerprint").asText());
        revoke.set("trustRoot", trustRoot);
        revoke.set("revocationApproval", JsonHashes.mapper().createObjectNode().put("key_id", "revocation-1")
                .put("privateKeyPem", pem("PRIVATE KEY", revocationKey.getPrivate().getEncoded())));
        ObjectNode revoked = StrategyProspectiveV5.revokeProspectiveNonce(revoke);
        assertThat(revoked.path("sequence").asInt()).isEqualTo(2);
        JsonNode row = revoked.path("entries").get(1);
        ObjectNode signedPayload = JsonHashes.mapper().createObjectNode().put("nonce", row.path("nonce").asText())
                .put("action", row.path("action").asText()).put("reason", row.path("reason").asText())
                .put("revoked_at", row.path("revoked_at").asText()).put("trust_root_sha256", row.path("trust_root_sha256").asText())
                .put("trust_root_generation", row.path("trust_root_generation").asInt());
        assertThat(StrategyProspectiveV5.verifyPayload(signedPayload, row.path("signature").asText(),
                trustRoot.path("delegations").get(2).path("public_key_pem").asText())).isTrue();
        ObjectNode retry = revoke.deepCopy().put("expectedHeadSha256", revoked.path("current_head_sha256").asText());
        assertJson(StrategyProspectiveV5.revokeProspectiveNonce(retry), revoked);
        ObjectNode wrongKey = revoke.deepCopy().put("expectedHeadSha256", revoked.path("current_head_sha256").asText());
        wrongKey.with("revocationApproval").put("privateKeyPem", pem("PRIVATE KEY", assetKey.getPrivate().getEncoded()));
        wrongKey.put("nonce", "nonce-other");
        assertThatThrownBy(() -> StrategyProspectiveV5.revokeProspectiveNonce(wrongKey))
                .hasMessage("revocation signature invalid");
    }

    @Test
    void publicationVerifiesAndRejectsEvidenceLeaseAndReplayTamper() throws Exception {
        PublicationFixture fixture = publicationFixture();
        ObjectNode publication = StrategyProspectiveV5.publishProspectiveEvidence(fixture.publishOptions());
        assertThat(publication.path("schema").asText()).isEqualTo("strategy-prospective-signed-evidence/2");
        assertThat(publication.path("replay_entry_sha256").asText()).isEqualTo(
                StrategyProspectiveV5.readReplayRegistry(fixture.replayPath()).path("head_sha256").asText());
        assertThat(StrategyProspectiveV5.verifyProspectivePublication(publication, fixture.verifyOptions()).path("verified").asBoolean()).isTrue();

        Path tamperedEvidence = fixture.evidencePaths().get("asset-support");
        byte[] evidenceBytes = Files.readAllBytes(tamperedEvidence);
        Files.write(tamperedEvidence, "tampered-evidence".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StrategyProspectiveV5.verifyProspectivePublication(publication, fixture.verifyOptions()))
                .hasMessage("evidence hash mismatch for asset-support");
        Files.write(tamperedEvidence, evidenceBytes);

        ObjectNode expired = StrategyProspectiveV5.withHash(publication.deepCopy().put("lease_expires_at", "2026-01-02T00:00:00.000Z"));
        assertThatThrownBy(() -> StrategyProspectiveV5.verifyProspectivePublication(expired, fixture.verifyOptions()))
                .hasMessage("publication lease invalid");

        ObjectNode replay = StrategyProspectiveV5.readReplayRegistry(fixture.replayPath());
        ObjectNode revoke = fixture.revokeOptions(replay.path("current_head_sha256").asText());
        StrategyProspectiveV5.revokeProspectiveNonce(revoke);
        assertThatThrownBy(() -> StrategyProspectiveV5.verifyProspectivePublication(publication, fixture.verifyOptions()))
                .hasMessage("publication replay or revocation check failed");
    }

    private static ObjectNode signalEvent(Path receipt, String receiptSha, String lineage) {
        ObjectNode event = JsonHashes.mapper().createObjectNode().put("event_id", "btc:bar-1:SIGNAL")
                .put("kind", "SIGNAL").put("asset", "btc").put("completed_bar_id", "bar-1")
                .put("decision_time", "2026-01-01T00:00:00.000Z").put("availability_time", "2026-01-01T00:00:00.000Z")
                .put("source_receipt_path", receipt.toString()).put("source_receipt_sha256", receiptSha)
                .put("lineage_sha256", lineage);
        event.set("payload", JsonHashes.mapper().createObjectNode().put("signal_state", "SHADOW").put("signal_intent", false));
        return event;
    }

    private static Path writeReceipt(Path root, String lineage, String bar, String asset, String availability) throws Exception {
        ObjectNode receipt = JsonHashes.mapper().createObjectNode().put("schema", "strategy-prospective-source-receipt/1")
                .put("version", 1).put("source_id", "fixture").put("source", "fixture")
                .put("adapter_sha256", "a".repeat(64)).put("code_sha256", "b".repeat(64))
                .put("raw_byte_sha256", "c".repeat(64)).put("payload_sha256", "d".repeat(64))
                .put("venue", "fixture").put("symbol", "BTCUSDT").put("timeframe", "4h")
                .put("bar_start", "2025-12-31T20:00:00.000Z").put("bar_end", "2026-01-01T00:00:00.000Z")
                .put("completed", true).put("completed_bar_id", bar).put("asset", asset)
                .put("availability_time", availability).put("lineage_sha256", lineage);
        receipt = StrategyProspectiveV5.withHash(receipt);
        Path path = root.resolve("receipt.json");
        Files.write(path, NodePrettyJson.write(receipt).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static long epoch(String instant) { return java.time.Instant.parse(instant).toEpochMilli(); }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static ObjectNode rootOptions(KeyPair root, KeyPair asset, KeyPair portfolio, KeyPair revocation) {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("rootKeyId", "root-1")
                .put("rootPublicKeyPem", pem("PUBLIC KEY", root.getPublic().getEncoded()))
                .put("rootPrivateKeyPem", pem("PRIVATE KEY", root.getPrivate().getEncoded())).put("generation", 1);
        var delegations = options.putArray("delegations");
        delegation(delegations, "asset", "asset-1", asset);
        delegation(delegations, "portfolio", "portfolio-1", portfolio);
        delegation(delegations, "revocation", "revocation-1", revocation);
        return options;
    }

    private static void delegation(com.fasterxml.jackson.databind.node.ArrayNode rows, String role,
                                    String id, KeyPair key) {
        rows.addObject().put("role", role).put("key_id", id)
                .put("public_key_pem", pem("PUBLIC KEY", key.getPublic().getEncoded()));
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(bytes)
                + "\n-----END " + label + "-----\n";
    }

    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

    private record CycleFixture(Path root, Path ledgerPath, Path reservationPath, Path sourceReceiptPath,
                                Path featureInputPath, Path candidateSetPath, Path evaluatorCodePath,
                                Path signalDecisionPath, Path outcomeResolutionPath, Path outcomeResolutionSourcePath,
                                Path labelSourcePath, Path executionSourcePath, Path outcomeReceiptPath,
                                String lineage, String reservationSha, String sourceReceiptSha, String featureInputSha,
                                String candidateSetSha, String evaluatorCodeSha, String signalDecisionSha,
                                String outcomeResolutionSha, String outcomeResolutionSourceSha, String labelSourceSha,
                                String executionSourceSha, String outcomeReceiptSha, ObjectNode options, ObjectNode bar) {
        ObjectNode noOpOptions(ObjectNode ledger) {
            ObjectNode out = JsonHashes.mapper().createObjectNode();
            out.set("ledger", ledger.deepCopy());
            out.set("bar", bar.deepCopy());
            out.put("sourceReceiptSha256", sourceReceiptSha);
            out.put("signalDecisionSha256", signalDecisionSha);
            out.put("reservationSha256", reservationSha);
            out.put("candidateSetSha256", candidateSetSha);
            out.put("evaluatorCodeSha256", evaluatorCodeSha);
            out.put("featureInputSha256", featureInputSha);
            return out;
        }
    }

    private record PublicationFixture(CycleFixture cycle, Path replayPath, ObjectNode root,
                                      ObjectNode publishOptions, ObjectNode verifyOptions,
                                      Map<String, Path> evidencePaths, ObjectNode revokeBase) {
        ObjectNode revokeOptions(String expectedHead) {
            return revokeBase.deepCopy().put("expectedHeadSha256", expectedHead);
        }
    }

    private static CycleFixture cycleFixture(boolean includeOutcome) throws Exception {
        Path root = Files.createTempDirectory("prospective-v5-cycle-");
        Path artifacts = Files.createDirectories(root.resolve("artifacts"));
        Path ledgerPath = root.resolve("ledger");
        String lineage = hash("cycle-lineage");
        ObjectNode lineageObject = JsonHashes.mapper().createObjectNode()
                .put("stack_sha256", hash("stack"))
                .put("candidate_sha256", hash("candidate"))
                .put("data_manifest_sha256", hash("manifest"));
        ObjectNode reservation = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-prospective-reservation/1").put("version", 1)
                .put("status", "FROZEN").put("decision", "SHADOW")
                .put("lineage_sha256", lineage).put("frozen_start", "2026-01-01T00:00:00.000Z")
                .put("frozen_end", "2026-02-01T00:00:00.000Z");
        reservation.set("lineage", lineageObject);
        reservation = StrategyProspectiveV5.withHash(reservation);
        Path reservationPath = root.resolve("reservation.json");
        String reservationSha = writeJson(reservationPath, reservation);

        String featureTime = "2026-01-01T00:00:00.000Z";
        ObjectNode featureRow = JsonHashes.mapper().createObjectNode().put("x", 1).put("signal_eligible", true);
        String featureRowSha = StrategyProspectiveV5.hash(featureRow);
        ObjectNode featureInput = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                .put("authoritative", true).put("status", "PUBLIC_OBSERVED")
                .put("data_manifest_sha256", hash("feature-manifest"))
                .put("feature_code_sha256", hash("feature-code"));
        featureInput.set("series", JsonHashes.mapper().createArrayNode().add(featureRow));
        featureInput.putObject("coverage").put("complete", true);
        Path featureInputPath = artifacts.resolve("feature-input.json");
        String featureInputSha = writeJson(featureInputPath, StrategyProspectiveV5.withHash(featureInput));

        ObjectNode candidateSet = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-candidate-set/4").put("precommit_sha256", hash("precommit"));
        candidateSet.putObject("generator").put("method", "GRID").put("seed", 1).put("declared_trials", 1);
        candidateSet.put("declared_k", 1).put("effective_k", 1);
        ObjectNode candidate = candidateSet.putArray("candidates").addObject()
                .put("candidate_id", "candidate-1").put("behavior_sha256", hash("candidate-behavior"));
        candidate.putObject("definition").putObject("chromosome");
        candidateSet.putArray("aliases").addObject().put("behavior_sha256", hash("candidate-behavior"))
                .putArray("candidate_ids").add("candidate-1");
        candidateSet.putObject("accounting");
        Path candidateSetPath = artifacts.resolve("candidate-set.json");
        String candidateSetSha = writeJson(candidateSetPath, StrategyProspectiveV5.withHash(candidateSet));

        ObjectNode evaluator = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-evaluator-spec/1").put("version", 1).put("status", "FROZEN")
                .put("code_sha256", StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_CODE_SHA256)
                .put("worker_code_sha256", StrategyEvaluatorV5.STRATEGY_EVALUATOR_V5_WORKER_CODE_SHA256);
        evaluator.putObject("predicate").put("predictor_id", "x").put("op", "GTE").put("value", 0);
        Path evaluatorCodePath = artifacts.resolve("evaluator.json");
        String evaluatorCodeSha = writeJson(evaluatorCodePath, StrategyProspectiveV5.withHash(evaluator));

        Path sourceReceiptPath = artifacts.resolve("signal-receipt.json");
        ObjectNode sourceReceipt = cycleReceipt("signal-source", lineage, "bar-1", "btc", featureTime,
                featureRowSha, "2025-12-31T20:00:00.000Z", featureTime);
        String sourceReceiptSha = writeJson(sourceReceiptPath, sourceReceipt);
        ObjectNode bar = JsonHashes.mapper().createObjectNode().put("asset", "btc")
                .put("completed_bar_id", "bar-1").put("availability_time", featureTime);
        bar.set("feature_row", featureRow);
        ObjectNode decision = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-prospective-signal-decision/1").put("version", 1)
                .put("decision", "SHADOW").put("signal_state", "SHADOW").put("signal_intent", true)
                .put("candidate_id", "candidate-1").put("completed_bar_id", "bar-1")
                .put("source_receipt_sha256", sourceReceiptSha).put("reservation_sha256", reservationSha)
                .put("candidate_set_sha256", candidateSetSha).put("evaluator_code_sha256", evaluatorCodeSha)
                .put("feature_input_sha256", featureInputSha).put("feature_row_sha256", featureRowSha)
                .put("availability_cutoff_time", featureTime).put("decision_time", featureTime)
                .put("lineage_sha256", lineage);
        Path signalDecisionPath = artifacts.resolve("signal-decision.json");
        String signalDecisionSha = writeJson(signalDecisionPath, StrategyProspectiveV5.withHash(decision));

        ObjectNode options = JsonHashes.mapper().createObjectNode().put("path", ledgerPath.toString())
                .put("reservationPath", reservationPath.toString()).put("reservationSha256", reservationSha)
                .put("sourceReceiptPath", sourceReceiptPath.toString()).put("sourceReceiptSha256", sourceReceiptSha)
                .put("featureInputPath", featureInputPath.toString()).put("featureInputSha256", featureInputSha)
                .put("candidateSetPath", candidateSetPath.toString()).put("candidateSetSha256", candidateSetSha)
                .put("evaluatorCodePath", evaluatorCodePath.toString()).put("evaluatorCodeSha256", evaluatorCodeSha)
                .put("signalDecisionPath", signalDecisionPath.toString()).put("signalDecisionSha256", signalDecisionSha)
                .put("nowAt", epoch("2026-01-03T00:00:00.000Z"));
        options.set("bar", bar.deepCopy());
        String outcomeResolutionSha = null, outcomeResolutionSourceSha = null, labelSourceSha = null,
                executionSourceSha = null, outcomeReceiptSha = null;
        Path outcomeResolutionPath = null, outcomeResolutionSourcePath = null, labelSourcePath = null,
                executionSourcePath = null, outcomeReceiptPath = null;
        if (includeOutcome) {
            outcomeResolutionSourcePath = artifacts.resolve("outcome-resolution-source.bin");
            outcomeResolutionSourceSha = writeBytes(outcomeResolutionSourcePath, "outcome-resolution-source");
            labelSourcePath = artifacts.resolve("label-source.bin");
            labelSourceSha = writeBytes(labelSourcePath, "label-source");
            executionSourcePath = artifacts.resolve("execution-source.bin");
            executionSourceSha = writeBytes(executionSourcePath, "execution-source");
            ObjectNode resolution = JsonHashes.mapper().createObjectNode()
                    .put("schema", "strategy-prospective-outcome-resolution/1").put("version", 1)
                    .put("completed_bar_id", "bar-1").put("resolution", "WIN")
                    .put("resolution_time", "2026-01-02T12:00:00.000Z")
                    .put("label_source_sha256", labelSourceSha).put("execution_source_sha256", executionSourceSha)
                    .put("source_byte_sha256", outcomeResolutionSourceSha).put("decision_lineage_sha256", lineage);
            outcomeResolutionPath = artifacts.resolve("outcome-resolution.json");
            outcomeResolutionSha = writeJson(outcomeResolutionPath, StrategyProspectiveV5.withHash(resolution));
            outcomeReceiptPath = artifacts.resolve("outcome-receipt.json");
            ObjectNode outcomeReceipt = cycleReceipt("outcome-source", lineage, "bar-1", "btc",
                    "2026-01-03T00:00:00.000Z", hash("outcome-payload"), "2026-01-02T00:00:00.000Z", "2026-01-03T00:00:00.000Z");
            outcomeReceiptSha = writeJson(outcomeReceiptPath, outcomeReceipt);
            options.put("outcomeResolutionPath", outcomeResolutionPath.toString()).put("outcomeResolutionSha256", outcomeResolutionSha)
                    .put("outcomeResolutionSourcePath", outcomeResolutionSourcePath.toString()).put("outcomeResolutionSourceSha256", outcomeResolutionSourceSha)
                    .put("labelSourcePath", labelSourcePath.toString()).put("labelSourceSha256", labelSourceSha)
                    .put("executionSourcePath", executionSourcePath.toString()).put("executionSourceSha256", executionSourceSha)
                    .put("outcomeReceiptPath", outcomeReceiptPath.toString()).put("outcomeReceiptSha256", outcomeReceiptSha);
        }
        ObjectNode ledgerOptions = JsonHashes.mapper().createObjectNode()
                .put("path", ledgerPath.toString()).put("lineage_sha256", lineage);
        ledgerOptions.putArray("assets").add("btc");
        StrategyProspectiveV5.createProspectiveLedger(ledgerOptions);
        return new CycleFixture(root, ledgerPath, reservationPath, sourceReceiptPath, featureInputPath,
                candidateSetPath, evaluatorCodePath, signalDecisionPath, outcomeResolutionPath,
                outcomeResolutionSourcePath, labelSourcePath, executionSourcePath, outcomeReceiptPath,
                lineage, reservationSha, sourceReceiptSha, featureInputSha, candidateSetSha, evaluatorCodeSha,
                signalDecisionSha, outcomeResolutionSha, outcomeResolutionSourceSha, labelSourceSha,
                executionSourceSha, outcomeReceiptSha, options, bar);
    }

    private static PublicationFixture publicationFixture() throws Exception {
        CycleFixture cycle = cycleFixture(false);
        StrategyProspectiveV5.appendCompletedBarCycle(cycle.options());
        Path replayPath = Files.createTempDirectory(cycle.root(), "replay-");
        ObjectNode replay = StrategyProspectiveV5.createReplayRegistry(JsonHashes.mapper().createObjectNode()
                .put("path", replayPath.toString()).put("lineage_sha256", cycle.lineage()));
        Path evidenceRoot = Files.createDirectories(cycle.root().resolve("publication-evidence"));
        ObjectNode evidencePathsJson = JsonHashes.mapper().createObjectNode();
        ArrayNode evidence = JsonHashes.mapper().createArrayNode();
        Map<String, Path> evidencePaths = new java.util.LinkedHashMap<>();
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "source-receipt", cycle.sourceReceiptPath());
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "reservation", cycle.reservationPath());
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "feature-input", cycle.featureInputPath());
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "candidate-set", cycle.candidateSetPath());
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "evaluator", cycle.evaluatorCodePath());
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "signal-decision", cycle.signalDecisionPath());
        String assetSupport = addBytesEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "asset-support", "asset-support");
        String assetWorkflow = addBytesEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "asset-workflow", "asset-workflow");
        String portfolioSupport = addBytesEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "portfolio-support", "portfolio-support");
        String portfolioWorkflow = addBytesEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "portfolio-workflow", "portfolio-workflow");
        ObjectNode assetDecision = JsonHashes.mapper().createObjectNode().put("schema", "strategy-prospective-decision/1")
                .put("version", 1).put("asset", "btc").put("role", "asset").put("decision", "PASS")
                .put("lineage_sha256", cycle.lineage());
        assetDecision.putArray("evidence_sha256").add(assetSupport);
        assetDecision.put("workflow_attestation_sha256", assetWorkflow);
        Path assetDecisionPath = evidenceRoot.resolve("asset-decision.json");
        String assetDecisionSha = writeJson(assetDecisionPath, StrategyProspectiveV5.withHash(assetDecision));
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "asset-decision", assetDecisionPath);
        ObjectNode portfolioDecision = JsonHashes.mapper().createObjectNode().put("schema", "strategy-prospective-decision/1")
                .put("version", 1).put("asset", "portfolio").put("role", "portfolio").put("decision", "PASS")
                .put("lineage_sha256", cycle.lineage());
        portfolioDecision.putArray("evidence_sha256").add(portfolioSupport);
        portfolioDecision.put("workflow_attestation_sha256", portfolioWorkflow);
        Path portfolioDecisionPath = evidenceRoot.resolve("portfolio-decision.json");
        String portfolioDecisionSha = writeJson(portfolioDecisionPath, StrategyProspectiveV5.withHash(portfolioDecision));
        addFileEvidence(evidenceRoot, evidencePathsJson, evidence, evidencePaths, "portfolio-decision", portfolioDecisionPath);

        KeyPair rootKey = keyPair(), assetKey = keyPair(), portfolioKey = keyPair(), revocationKey = keyPair();
        ObjectNode trustRoot = StrategyProspectiveV5.makeTrustRootBundle(rootOptions(rootKey, assetKey, portfolioKey, revocationKey));
        long now = epoch("2026-01-03T00:00:00.000Z");
        ObjectNode publish = JsonHashes.mapper().createObjectNode().put("ledgerPath", cycle.ledgerPath().toString())
                .put("replayPath", replayPath.toString()).put("pinnedTrustRootFingerprint", trustRoot.path("pinned_fingerprint").asText())
                .put("pinnedTrustRootGenesisFingerprint", trustRoot.path("genesis_pinned_fingerprint").asText())
                .put("lineageSha256", cycle.lineage()).put("replayNonce", "publication-1")
                .put("leaseExpiresAt", "2026-01-10T00:00:00.000Z").put("expectedReplayHeadSha256", replay.path("head_sha256").asText())
                .put("nowAt", now);
        publish.set("trustRoot", trustRoot);
        publish.set("evidence", evidence);
        publish.set("assetApproval", JsonHashes.mapper().createObjectNode().put("key_id", "asset-1")
                .put("decision_sha256", assetDecisionSha).put("decision_path", assetDecisionPath.toString())
                .put("decision_evidence_id", "asset-decision").put("privateKeyPem", pem("PRIVATE KEY", assetKey.getPrivate().getEncoded())));
        publish.set("portfolioApproval", JsonHashes.mapper().createObjectNode().put("key_id", "portfolio-1")
                .put("decision_sha256", portfolioDecisionSha).put("decision_path", portfolioDecisionPath.toString())
                .put("decision_evidence_id", "portfolio-decision").put("privateKeyPem", pem("PRIVATE KEY", portfolioKey.getPrivate().getEncoded())));
        ObjectNode verify = JsonHashes.mapper().createObjectNode().put("ledgerPath", cycle.ledgerPath().toString())
                .put("replayPath", replayPath.toString()).put("pinnedTrustRootFingerprint", trustRoot.path("pinned_fingerprint").asText())
                .put("pinnedTrustRootGenesisFingerprint", trustRoot.path("genesis_pinned_fingerprint").asText()).put("nowAt", now);
        verify.set("trustRoot", trustRoot);
        verify.set("evidencePaths", evidencePathsJson);
        ObjectNode revoke = JsonHashes.mapper().createObjectNode().put("path", replayPath.toString()).put("nonce", "publication-1")
                .put("reason", "publication review").put("nowAt", now)
                .put("pinnedTrustRootFingerprint", trustRoot.path("pinned_fingerprint").asText())
                .put("pinnedTrustRootGenesisFingerprint", trustRoot.path("genesis_pinned_fingerprint").asText());
        revoke.set("trustRoot", trustRoot);
        revoke.set("revocationApproval", JsonHashes.mapper().createObjectNode()
                .put("key_id", "revocation-1").put("privateKeyPem", pem("PRIVATE KEY", revocationKey.getPrivate().getEncoded())));
        return new PublicationFixture(cycle, replayPath, trustRoot, publish, verify, evidencePaths, revoke);
    }

    private static ObjectNode cycleReceipt(String sourceId, String lineage, String barId, String asset,
                                            String availability, String payloadSha, String barStart, String barEnd) {
        return StrategyProspectiveV5.withHash(JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-prospective-source-receipt/1").put("version", 1)
                .put("source_id", sourceId).put("source", "fixture")
                .put("adapter_sha256", hash(sourceId + "-adapter")).put("code_sha256", hash(sourceId + "-code"))
                .put("raw_byte_sha256", hash(sourceId + "-raw")).put("payload_sha256", payloadSha)
                .put("venue", "fixture").put("symbol", "BTCUSDT").put("timeframe", "4h")
                .put("bar_start", barStart).put("bar_end", barEnd).put("completed", true)
                .put("completed_bar_id", barId).put("asset", asset).put("availability_time", availability)
                .put("lineage_sha256", lineage));
    }

    private static String writeJson(Path path, ObjectNode value) throws Exception {
        byte[] bytes = NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        return StrategyProspectiveV5.hash(bytes);
    }

    private static String writeBytes(Path path, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        return StrategyProspectiveV5.hash(bytes);
    }

    private static void addFileEvidence(Path root, ObjectNode paths, ArrayNode rows,
                                        Map<String, Path> pathMap, String id, Path source) throws Exception {
        Path target = root.resolve(id + ".json");
        Files.write(target, Files.readAllBytes(source));
        String sha = StrategyProspectiveV5.hash(Files.readAllBytes(target));
        paths.put(id, target.toString()); pathMap.put(id, target);
        rows.addObject().put("id", id).put("path", target.toString()).put("sha256", sha);
    }

    private static String addBytesEvidence(Path root, ObjectNode paths, ArrayNode rows,
                                           Map<String, Path> pathMap, String id, String content) throws Exception {
        Path target = root.resolve(id + ".bin");
        String sha = writeBytes(target, content);
        paths.put(id, target.toString()); pathMap.put(id, target);
        rows.addObject().put("id", id).put("path", target.toString()).put("sha256", sha);
        return sha;
    }

    private static String testIso(long milliseconds) {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(milliseconds));
    }

    private static String hash(String value) {
        return StrategyProspectiveV5.hash(value);
    }
}
