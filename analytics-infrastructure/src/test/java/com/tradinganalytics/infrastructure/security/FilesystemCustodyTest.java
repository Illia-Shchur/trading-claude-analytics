package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilesystemCustodyTest {
    @TempDir Path temporary;

    @Test
    void acceptsOnlyPortableRepositoryRelativePaths() {
        assertThat(PathConfinement.repositoryRelativePath("evidence/HEAD.json", "ledger"))
                .isEqualTo("evidence/HEAD.json");
        assertThat(PathConfinement.repositoryRelativePath("./evidence.json", "ledger"))
                .isEqualTo("./evidence.json");

        for (String hostile : new String[] {
                "", "../outside.json", "a/../outside.json", "/tmp/outside.json",
                "C:\\outside.json", "C:/outside.json", "C:outside.json", "a\\b.json",
                "a//b.json", "a/", "bad\u0001.json", "bad\u0085.json"}) {
            assertThatThrownBy(() -> PathConfinement.repositoryRelativePath(hostile, "bundle"))
                    .as(hostile).isInstanceOf(CustodyException.class)
                    .hasMessageMatching("(?i).*(relative|traversal).*" );
        }
    }

    @Test
    void confinesEveryComponentAndRejectsSymlinksHardlinksAndWrongTypes() throws IOException {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("HEAD.json"), "{}\n");
        assertThat(PathConfinement.resolve(temporary, "evidence/HEAD.json", "ledger head",
                PathConfinement.ExpectedType.FILE).relative()).isEqualTo("evidence/HEAD.json");

        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{}\n");
        Files.createSymbolicLink(evidence.resolve("escape.json"), outside);
        assertThatThrownBy(() -> PathConfinement.resolve(
                temporary, "evidence/escape.json", "escape", PathConfinement.ExpectedType.FILE))
                .isInstanceOf(CustodyException.class).hasMessageContaining("symlink");

        Path outsideDirectory = Files.createDirectory(temporary.resolve("outside-directory"));
        Files.writeString(outsideDirectory.resolve("child.json"), "{}\n");
        Files.createSymbolicLink(evidence.resolve("linked-directory"), outsideDirectory);
        assertThatThrownBy(() -> PathConfinement.resolve(temporary,
                "evidence/linked-directory/child.json", "parent escape", PathConfinement.ExpectedType.FILE))
                .isInstanceOf(CustodyException.class).hasMessageContaining("symlink");

        Path original = temporary.resolve("original.json");
        Files.writeString(original, "{}\n");
        Files.createLink(temporary.resolve("alias.json"), original);
        assertThatThrownBy(() -> PathConfinement.resolve(
                temporary, "alias.json", "hardlink", PathConfinement.ExpectedType.FILE))
                .isInstanceOf(CustodyException.class).hasMessageContaining("singly-linked");

        assertThatThrownBy(() -> PathConfinement.resolve(
                temporary, "evidence", "wrong type", PathConfinement.ExpectedType.FILE))
                .isInstanceOf(CustodyException.class).hasMessageContaining("regular");
        assertThatThrownBy(() -> PathConfinement.resolve(
                temporary, "evidence/HEAD.json", "wrong type", PathConfinement.ExpectedType.DIRECTORY))
                .isInstanceOf(CustodyException.class).hasMessageContaining("directory");
    }

    @Test
    void rejectsASymlinkAsThePhysicalRoot() throws IOException {
        Path real = Files.createDirectory(temporary.resolve("real"));
        Path link = temporary.resolve("root-link");
        Files.createSymbolicLink(link, real);
        assertThatThrownBy(() -> PathConfinement.requireRealDirectory(link, "physical root"))
                .isInstanceOf(CustodyException.class).hasMessageContaining("real directory");
    }

    @Test
    void safeTreeRejectsLinksButAllowsArbitraryRepositoryFilesOutsideEvidenceMode() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("tree"));
        Files.writeString(root.resolve("receipt.json"), "{}\n");
        Files.writeString(root.resolve("README.md"), "# trusted source\n");
        assertThat(SafeTreeVerifier.verify(root, "repository archive", SafeTreeVerifier.Options.REPOSITORY).files())
                .isEqualTo(2);
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .isInstanceOf(CustodyException.class).hasMessageContaining("non-JSON");

        Files.delete(root.resolve("README.md"));
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{}\n");
        Files.createSymbolicLink(root.resolve("escape.json"), outside);
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .isInstanceOf(CustodyException.class).hasMessageContaining("symlink");
        Files.delete(root.resolve("escape.json"));

        Path original = root.resolve("original.json");
        Files.writeString(original, "{}\n");
        Files.createLink(root.resolve("alias.json"), original);
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .isInstanceOf(CustodyException.class).hasMessageContaining("singly-linked");
    }

    @Test
    void evidenceTreeEnforcesAllIndependentCeilingsBeforeAcceptingContent() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("limits"));
        Files.writeString(root.resolve("a.json"), "{}\n");
        var exact = new SafeTreeVerifier.Options(new CustodyLimits(1, 3, 3), true);
        assertThat(SafeTreeVerifier.verify(root, "bounded evidence", exact))
                .isEqualTo(new SafeTreeVerifier.TreeSummary(1, 3));

        Files.writeString(root.resolve("b.json"), "{}\n");
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root, "bounded evidence",
                new SafeTreeVerifier.Options(new CustodyLimits(1, 10, 20), true)))
                .hasMessageContaining("file-count");
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root, "bounded evidence",
                new SafeTreeVerifier.Options(new CustodyLimits(3, 2, 20), true)))
                .hasMessageContaining("per-file");
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root, "bounded evidence",
                new SafeTreeVerifier.Options(new CustodyLimits(3, 3, 3), true)))
                .hasMessageContaining("total");

        assertThatThrownBy(() -> new CustodyLimits(0, 1, 1)).isInstanceOf(CustodyException.class);
        assertThatThrownBy(() -> new CustodyLimits(1, 0, 1)).isInstanceOf(CustodyException.class);
        assertThatThrownBy(() -> new CustodyLimits(1, 1, 0)).isInstanceOf(CustodyException.class);
    }

    @Test
    void evidenceTreeRejectsRawSecretAndAmbiguousJsonPayloads() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("content"));
        Path candidate = root.resolve("receipt.json");

        for (byte[] hostile : new byte[][] {
                "{\"payload\":\"-----BEGIN PRIVATE KEY-----\"}\n".getBytes(StandardCharsets.UTF_8),
                "{\"payload\":\"-----BEGIN RSA PRIVATE KEY-----\"}\n".getBytes(StandardCharsets.UTF_8),
                "{\"payload\":\"-----BEGIN PUBLIC KEY-----\"}\n".getBytes(StandardCharsets.UTF_8),
                new byte[] {'{', (byte) 0xff, '}'},
                "{\"payload\":\"ok\"}\u0000".getBytes(StandardCharsets.UTF_8),
                "not-json\n".getBytes(StandardCharsets.UTF_8),
                "{\"same\":1,\"same\":2}\n".getBytes(StandardCharsets.UTF_8)}) {
            Files.write(candidate, hostile);
            assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                    .as(new String(hostile, StandardCharsets.UTF_8))
                    .isInstanceOf(CustodyException.class);
        }

        Files.writeString(candidate, "{}\n");
        for (String name : new String[] {"private.pem.json", "api-key.json", "raw.bin.json", "secret.json"}) {
            Path forbidden = root.resolve(name);
            Files.writeString(forbidden, "{}\n");
            assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                    .as(name).isInstanceOf(CustodyException.class)
                    .hasMessageMatching("(?i).*(private|key|raw).*" );
            Files.delete(forbidden);
        }
    }

    @Test
    void publicAttestationRegistryAllowsOnlyExactEd25519PemAtTheExactField() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("registry"));
        Path registryPath = root.resolve(EvidenceContentValidator.PUBLIC_REGISTRY);
        String publicPem = publicPem("Ed25519");

        ObjectNode registry = JsonHashes.mapper().createObjectNode();
        registry.put("schema", "strategy-github-attestation-key-registry/1");
        ArrayNode keys = registry.putArray("keys");
        keys.addObject().put("key_id", "actions-1").put("public_key_pem", publicPem);
        Files.write(registryPath, JsonHashes.mapper().writeValueAsBytes(registry));
        assertThat(SafeTreeVerifier.verify(root).files()).isEqualTo(1);

        ObjectNode markerInId = registry.deepCopy();
        ((ObjectNode) markerInId.path("keys").get(0)).put("key_id", "-----BEGIN PRIVATE KEY-----");
        Files.write(registryPath, JsonHashes.mapper().writeValueAsBytes(markerInId));
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .hasMessageContaining("outside public_key_pem");

        ObjectNode markerElsewhere = registry.deepCopy();
        markerElsewhere.put("repository", "owner/-----BEGIN RSA PRIVATE KEY-----");
        Files.write(registryPath, JsonHashes.mapper().writeValueAsBytes(markerElsewhere));
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .hasMessageContaining("outside public_key_pem");

        ObjectNode rsa = registry.deepCopy();
        ((ObjectNode) rsa.path("keys").get(0)).put("public_key_pem", publicPem("RSA"));
        Files.write(registryPath, JsonHashes.mapper().writeValueAsBytes(rsa));
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .hasMessageContaining("Ed25519");

        ObjectNode malformed = registry.deepCopy();
        ((ObjectNode) malformed.path("keys").get(0)).put(
                "public_key_pem", publicPem.replace("\n-----END", "-----END"));
        Files.write(registryPath, JsonHashes.mapper().writeValueAsBytes(malformed));
        assertThatThrownBy(() -> SafeTreeVerifier.verify(root))
                .hasMessageContaining("exact public PEM");
    }

    private static String publicPem(String algorithm) throws Exception {
        var generator = KeyPairGenerator.getInstance(algorithm);
        if (algorithm.equals("RSA")) generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPublic().getEncoded();
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }
}
