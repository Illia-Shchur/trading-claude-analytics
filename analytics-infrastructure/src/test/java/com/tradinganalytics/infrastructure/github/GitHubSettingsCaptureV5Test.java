package com.tradinganalytics.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5.ApiResponse;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5.AuthMode;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5.Result;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5.Transport;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GitHubSettingsCaptureV5Test {
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path temporary;

    @Test
    void patCaptureMatchesTheFrozenRulesetOidcAndCustodyContract() throws Exception {
        Fixture fixture = Fixture.valid();
        Map<String, String> env = patEnvironment();
        Result result = GitHubSettingsCaptureV5.capture(env, fixture, CLOCK);

        assertThat(result.verified()).isTrue();
        assertThat(result.capture().path("schema").asText())
                .isEqualTo("github-deployment-settings-capture/1");
        assertThat(result.capture().path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(result.capture()));
        assertThat(result.receipt().path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(result.receipt()));
        assertThat(result.capture().path("branch_protection").path("api_status").asInt())
                .isEqualTo(404);
        assertThat(result.capture().path("branch_protection").path("verified").asBoolean())
                .isTrue();
        assertThat(result.capture().path("rulesets").path("rules_verified").asBoolean())
                .isTrue();
        assertThat(result.capture().path("rulesets").path("actions_bypass_app_ids"))
                .isEmpty();
        assertThat(result.capture().path("settings_token_identity").path("token_kind").asText())
                .isEqualTo("PAT");
        assertThat(result.capture().path("settings_token_identity").path("user_id").asLong())
                .isEqualTo(123);
        assertThat(result.capture().path("oidc_signature_verified").asBoolean()).isTrue();
        assertThat(result.capture().path("oidc_subject_restricted").asBoolean()).isTrue();
        assertThat(result.receipt().path("installation_proof_verified").asBoolean()).isFalse();
        assertThat(result.receipt().path("endpoints").path("installation").path("status").asInt())
                .isZero();
        assertThat(result.receipt().path("blockers")).isEmpty();
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(result.capture());
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(result.receipt());
    }

    @Test
    void exactPinnedAppProofCanReachSigningReadyStateWithoutLeakingCredentials() throws Exception {
        Fixture fixture = Fixture.valid();
        Map<String, String> env = appEnvironment(fixture.privateKeyPem());
        Result result = GitHubSettingsCaptureV5.capture(env, fixture, CLOCK);

        assertThat(result.verified()).isTrue();
        assertThat(result.capture().path("settings_token_identity").path("token_kind").asText())
                .isEqualTo("APP");
        assertThat(result.capture().path("settings_token_identity").path("secret_name").asText())
                .isEqualTo("V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM");
        assertThat(result.capture().path("settings_auditor_installation").path("verified").asBoolean())
                .isTrue();
        assertThat(result.receipt().path("installation_proof_verified").asBoolean()).isTrue();
        assertThat(result.receipt().path("endpoints").path("settings_auditor_app").path("status").asInt())
                .isEqualTo(200);
        assertThat(fixture.appJwtRequests).hasSize(3);
        assertThat(fixture.appJwtRequests).allSatisfy(request -> {
            assertThat(request.token()).matches("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
            assertThat(request.authMode()).isEqualTo(AuthMode.APP_JWT);
            assertThat(request.token()).doesNotContain(fixture.privateKeyPem());
        });
        assertThat(result.capture().toString()).doesNotContain(fixture.privateKeyPem());
        assertThat(result.receipt().toString()).doesNotContain(fixture.privateKeyPem());
        assertThat(result.capture().toString()).doesNotContain("bootstrap-token");
    }

    @Test
    void appIdentityPermissionAndRepositoryDriftFailClosed() throws Exception {
        List<Map<String, JsonNode>> mutations = List.of(
                Map.of("app", json("{\"id\":99,\"slug\":\"strategy-v5-settings-auditor\","
                        + "\"permissions\":{" + permissions() + "},\"events\":[]}")),
                Map.of("app", json("{\"id\":4716635,\"slug\":\"strategy-v5-settings-auditor\","
                        + "\"permissions\":{\"actions\":\"write\",\"administration\":\"read\","
                        + "\"environments\":\"read\",\"metadata\":\"read\",\"secrets\":\"read\"},"
                        + "\"events\":[]}")),
                Map.of("installation/repositories", json("{\"total_count\":2,\"repositories\":[]}")));
        for (Map<String, JsonNode> mutation : mutations) {
            Fixture fixture = Fixture.valid();
            fixture.overrides.putAll(mutation);
            Result result = GitHubSettingsCaptureV5.capture(
                    appEnvironment(fixture.privateKeyPem()), fixture, CLOCK);
            assertThat(result.verified()).isFalse();
            assertThat(result.capture().path("settings_auditor_installation").path("verified").asBoolean())
                    .isFalse();
            assertThat(result.receipt().path("blockers").toString())
                    .contains("GITHUB_SETTINGS_AUDITOR_INSTALLATION_UNVERIFIED");
        }
    }

    @Test
    void everyActionsAndRulesetTrustBoundaryFailsClosed() throws Exception {
        List<Map.Entry<String, JsonNode>> mutations = List.of(
                Map.entry("repos/owner/repo/actions/permissions",
                        json("{\"allowed_actions\":\"all\",\"sha_pinning_required\":true}")),
                Map.entry("repos/owner/repo/actions/permissions/selected-actions",
                        json("{\"github_owned_allowed\":true,\"verified_allowed\":true,\"patterns_allowed\":[]}")),
                Map.entry("repos/owner/repo/actions/permissions/workflow",
                        json("{\"default_workflow_permissions\":\"write\",\"can_approve_pull_request_reviews\":false}")),
                Map.entry("repos/owner/repo/rulesets/8",
                        json(writerRuleset("other-required-check", 15368, true, 0,
                                "refs/heads/strategy-v5-evidence"))),
                Map.entry("repos/owner/repo/rulesets/8",
                        json(writerRuleset("strategy-v5-evidence-custody", 99, true, 0,
                                "refs/heads/strategy-v5-evidence"))),
                Map.entry("repos/owner/repo/rulesets/8",
                        json(writerRuleset("strategy-v5-evidence-custody", 15368, false, 0,
                                "refs/heads/strategy-v5-evidence"))),
                Map.entry("repos/owner/repo/rulesets/8",
                        json(writerRuleset("strategy-v5-evidence-custody", 15368, true, 1,
                                "refs/heads/strategy-v5-evidence"))),
                Map.entry("repos/owner/repo/rulesets/8",
                        json(writerRuleset("strategy-v5-evidence-custody", 15368, true, 0,
                                "refs/heads/strategy-v5-evidence/*"))));
        for (Map.Entry<String, JsonNode> mutation : mutations) {
            Fixture fixture = Fixture.valid();
            fixture.overrides.put(mutation.getKey(), mutation.getValue());
            Result result = GitHubSettingsCaptureV5.capture(patEnvironment(), fixture, CLOCK);
            assertThat(result.verified()).as(mutation.getKey()).isFalse();
        }
    }

    @Test
    void environmentsRequireExplicitAdminAndSelfReviewProtection() throws Exception {
        for (JsonNode mutation : List.of(
                json("{\"protection_rules\":[{\"type\":\"required_reviewers\","
                        + "\"reviewers\":[{\"login\":\"r\"}],\"prevent_self_review\":false}],"
                        + "\"deployment_branch_policy\":{\"protected_branches\":true,"
                        + "\"custom_branch_policies\":false}}"),
                json("{\"can_admins_bypass\":true,\"protection_rules\":[],"
                        + "\"deployment_branch_policy\":{\"protected_branches\":true,"
                        + "\"custom_branch_policies\":false}}"))) {
            Fixture fixture = Fixture.valid();
            fixture.overrides.put("repos/owner/repo/environments/prospective-v5", mutation);
            Result result = GitHubSettingsCaptureV5.capture(patEnvironment(), fixture, CLOCK);
            assertThat(result.verified()).isFalse();
            assertThat(result.capture().path("environment_protection").path("verified").asBoolean())
                    .isFalse();
        }
    }

    @Test
    void forgedOidcSignatureAndMalformedApiBodiesAreNotAuthenticated() throws Exception {
        Fixture forged = Fixture.valid();
        forged.forgeOidcSignature = true;
        Result forgedResult = GitHubSettingsCaptureV5.capture(patEnvironment(), forged, CLOCK);
        assertThat(forgedResult.verified()).isFalse();
        assertThat(forgedResult.capture().path("oidc_signature_verified").asBoolean()).isFalse();

        Fixture malformed = Fixture.valid();
        malformed.statusOverrides.put("repos/owner/repo", 0);
        malformed.overrides.put("repos/owner/repo", json("{}"));
        Result malformedResult = GitHubSettingsCaptureV5.capture(patEnvironment(), malformed, CLOCK);
        assertThat(malformedResult.receipt().path("endpoints").path("repository").path("status").asInt())
                .isZero();
        assertThat(malformedResult.verified()).isFalse();
    }

    @Test
    void productionHttpTransportPreservesNonSuccessEvidenceAndRejectsMalformedJson() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> proofHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/non-success", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = "{\"enforce_admins\":{\"enabled\":true}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/malformed", exchange -> {
            byte[] body = "{\"id\":".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/url", exchange -> {
            proofHeader.set(exchange.getRequestHeaders().getFirst("X-Proof"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI root = URI.create("http://" + server.getAddress().getHostString() + ":"
                    + server.getAddress().getPort() + "/api");
            GitHubSettingsCaptureV5.HttpTransport transport =
                    new GitHubSettingsCaptureV5.HttpTransport(HttpClient.newHttpClient(), root);

            ApiResponse nonSuccess = transport.github(
                    "/non-success", "transport-secret", AuthMode.TOKEN);
            assertThat(nonSuccess.status()).isEqualTo(404);
            assertThat(nonSuccess.body().path("enforce_admins").path("enabled").asBoolean())
                    .isTrue();
            assertThat(authorization).hasValue("Bearer transport-secret");
            assertThat(accept).hasValue("application/vnd.github+json");
            assertThat(nonSuccess.body().toString()).doesNotContain("transport-secret");

            ApiResponse malformed = transport.github("malformed", "", AuthMode.TOKEN);
            assertThat(malformed.status()).isZero();
            assertThat(malformed.body()).isEmpty();

            ApiResponse direct = transport.url(
                    URI.create(root + "/url"), Map.of("X-Proof", "bound"));
            assertThat(direct.status()).isEqualTo(200);
            assertThat(direct.body().path("ok").asBoolean()).isTrue();
            assertThat(proofHeader).hasValue("bound");

            ApiResponse unsupportedScheme = transport.url(URI.create("file:///not-http"), Map.of());
            assertThat(unsupportedScheme.status()).isZero();
            assertThat(unsupportedScheme.body()).isEmpty();

            Thread.currentThread().interrupt();
            try {
                ApiResponse interrupted = transport.url(URI.create(root + "/url"), Map.of());
                assertThat(interrupted.status()).isZero();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configurationAndArtifactWriterAreFailClosed() throws Exception {
        Fixture fixture = Fixture.valid();
        assertThatThrownBy(() -> GitHubSettingsCaptureV5.capture(Map.of(), fixture, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GITHUB_REPOSITORY");
        Map<String, String> auditorMissing = new LinkedHashMap<>(patEnvironment());
        auditorMissing.put("V5_REQUIRE_SETTINGS_AUDITOR", "true");
        assertThatThrownBy(() -> GitHubSettingsCaptureV5.capture(auditorMissing, fixture, CLOCK))
                .hasMessageContaining("auditor App/installation ids are required");

        Map<String, String> tokenMissing = new LinkedHashMap<>(patEnvironment());
        tokenMissing.remove("GH_TOKEN");
        assertThatThrownBy(() -> GitHubSettingsCaptureV5.capture(tokenMissing, fixture, CLOCK))
                .hasMessageContaining("protected settings token is required");

        Map<String, String> visibilityInvalid = new LinkedHashMap<>(patEnvironment());
        visibilityInvalid.put("V5_REPOSITORY_VISIBILITY", "INTERNAL");
        assertThatThrownBy(() -> GitHubSettingsCaptureV5.capture(visibilityInvalid, fixture, CLOCK))
                .hasMessageContaining("V5_REPOSITORY_VISIBILITY");

        Map<String, String> wrongAppSecret = new LinkedHashMap<>(
                appEnvironment(fixture.privateKeyPem()));
        wrongAppSecret.put("V5_SETTINGS_TOKEN_SECRET_NAME", "WRONG_SECRET");
        assertThatThrownBy(() -> GitHubSettingsCaptureV5.capture(wrongAppSecret, fixture, CLOCK))
                .hasMessageContaining("protected auditor secret");

        Result result = GitHubSettingsCaptureV5.capture(patEnvironment(), fixture, CLOCK);
        Path capture = temporary.resolve("capture.json");
        Path receipt = temporary.resolve("receipt.json");
        GitHubSettingsCaptureV5.writeArtifacts(result, Map.of(
                "V5_SETTINGS_OUT", capture.toString(),
                "V5_SETTINGS_RECEIPT_OUT", receipt.toString()));
        assertThat(JsonHashes.parse(Files.readAllBytes(capture), "capture").toString())
                .isEqualTo(result.capture().toString());
        assertThat(JsonHashes.parse(Files.readAllBytes(receipt), "receipt").toString())
                .isEqualTo(result.receipt().toString());
    }

    @Test
    void deploymentCaptureBindingIsByteCanonicalWithTheFrozenOracle() throws Exception {
        Result baseline = GitHubSettingsCaptureV5.capture(patEnvironment(), Fixture.valid(), CLOCK);
        ObjectNode capture = baseline.capture();
        ObjectNode body = JsonHashes.mapper().createObjectNode();
        body.set("repository", json("{\"id\":1,\"owner_id\":\"2\","
                + "\"full_name\":\"owner/repo\",\"private\":false}"));
        body.put("repository_visibility", "PUBLIC");
        body.put("repository_visibility_verified", true);
        body.set("branch_protection", capture.path("branch_protection").deepCopy());
        body.set("branch_head", json("{\"commit\":{\"sha\":\"" + "f".repeat(64) + "\"}}"));
        body.set("environment_protection", json("{\"api_status\":200,"
                + "\"can_admins_bypass\":false,\"protection_rules\":[{"
                + "\"type\":\"required_reviewers\",\"reviewers\":[{\"login\":\"reviewer\"}],"
                + "\"prevent_self_review\":true}],\"deployment_branch_policy\":{"
                + "\"protected_branches\":true,\"custom_branch_policies\":false}}"));
        for (String field : List.of("writer_environment_protection", "rulesets",
                "actions_permissions", "actions_secret", "evidence_writer_secret",
                "settings_token_secret", "settings_token_identity",
                "settings_auditor_installation")) {
            body.set(field, capture.path(field).deepCopy());
        }
        ObjectNode oidc = body.putObject("oidc");
        oidc.put("api_status", 200);
        oidc.put("use_default", false);
        oidc.put("use_immutable_subject", true);
        oidc.putArray("include_claim_keys").add("repo").add("context");
        oidc.put("signature_verified", true);
        oidc.set("claims", capture.path("oidc_claims").deepCopy());
        body.put("evidence_branch", "strategy-v5-evidence");
        ObjectNode response = JsonHashes.mapper().createObjectNode();
        response.put("status", 200);
        response.set("body", body);

        JsonNode expected = frozenDeploymentCapture();
        ObjectNode actual = GitHubSettingsCaptureV5.makeDeploymentSettingsCapture(
                response,
                capture.path("oidc_subject").asText(),
                capture.path("oidc_claims"),
                true,
                NOW,
                "f".repeat(64),
                4_716_299L);
        assertThat(JsonHashes.canonicalString(actual))
                .isEqualTo(JsonHashes.canonicalString(expected));
    }

    private static JsonNode frozenDeploymentCapture() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                GitHubSettingsCaptureV5Test.class.getResourceAsStream(
                        "/oracles/github-settings-capture-v5-v1.json"),
                "frozen deployment capture oracle is missing")) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private static Map<String, String> patEnvironment() {
        Map<String, String> env = baseEnvironment();
        env.put("V5_SETTINGS_TOKEN_KIND", "PAT");
        env.put("V5_SETTINGS_TOKEN_USER_ID", "123");
        env.put("V5_SETTINGS_TOKEN_LOGIN", "settings-bot");
        env.put("V5_SETTINGS_TOKEN_SECRET_NAME", "V5_GITHUB_SETTINGS_PAT");
        return env;
    }

    private static Map<String, String> appEnvironment(String pem) {
        Map<String, String> env = baseEnvironment();
        env.put("V5_SETTINGS_TOKEN_KIND", "APP");
        env.put("V5_REQUIRE_SETTINGS_AUDITOR", "true");
        env.put("V5_SETTINGS_AUDITOR_APP_ID", "4716635");
        env.put("V5_SETTINGS_AUDITOR_INSTALLATION_ID", "156531963");
        env.put("V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM", pem);
        env.put("V5_GITHUB_SETTINGS_APP_ID", "4716635");
        return env;
    }

    private static Map<String, String> baseEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GITHUB_REPOSITORY", "owner/repo");
        env.put("GITHUB_REPOSITORY_ID", "1");
        env.put("GITHUB_WORKFLOW_REF", "owner/repo/.github/workflows/strategy-v5-prospective.yml@refs/heads/main");
        env.put("GITHUB_SHA", "a".repeat(64));
        env.put("GITHUB_RUN_ID", "42");
        env.put("GITHUB_RUN_ATTEMPT", "1");
        env.put("GH_TOKEN", "bootstrap-token");
        env.put("V5_REQUIRE_SETTINGS_TOKEN", "true");
        env.put("V5_REPOSITORY_VISIBILITY", "PUBLIC");
        env.put("V5_EVIDENCE_BRANCH", "strategy-v5-evidence");
        env.put("V5_EVIDENCE_WRITER_APP_ID", "4716299");
        env.put("ACTIONS_ID_TOKEN_REQUEST_URL", "https://actions.example/token");
        env.put("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "request-token");
        return env;
    }

    private static String permissions() {
        return "\"actions\":\"read\",\"administration\":\"read\","
                + "\"environments\":\"read\",\"metadata\":\"read\",\"secrets\":\"read\"";
    }

    private static String writerRuleset(
            String context, int integration, boolean strict, int approvals, String ref) {
        return "{\"target\":\"branch\",\"enforcement\":\"active\","
                + "\"conditions\":{\"ref_name\":{\"include\":[\"" + ref + "\"]}},"
                + "\"bypass_actors\":[],\"rules\":[{\"type\":\"pull_request\","
                + "\"parameters\":{\"required_approving_review_count\":" + approvals + "}},"
                + "{\"type\":\"required_status_checks\",\"parameters\":{"
                + "\"strict_required_status_checks_policy\":" + strict + ","
                + "\"required_status_checks\":[{\"context\":\"" + context + "\","
                + "\"integration_id\":" + integration + "}]}}]}";
    }

    private static JsonNode json(String value) {
        try {
            return JsonHashes.mapper().readTree(value);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Request(String path, String token, AuthMode authMode) {}

    private static final class Fixture implements Transport {
        private final KeyPair oidcKeys;
        private final KeyPair appKeys;
        private final Map<String, JsonNode> overrides = new LinkedHashMap<>();
        private final Map<String, Integer> statusOverrides = new LinkedHashMap<>();
        private final List<Request> appJwtRequests = new ArrayList<>();
        private boolean forgeOidcSignature;

        private Fixture(KeyPair oidcKeys, KeyPair appKeys) {
            this.oidcKeys = oidcKeys;
            this.appKeys = appKeys;
        }

        static Fixture valid() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new Fixture(generator.generateKeyPair(), generator.generateKeyPair());
        }

        String privateKeyPem() {
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(((RSAPrivateKey) appKeys.getPrivate()).getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
        }

        @Override
        public ApiResponse github(String path, String token, AuthMode authMode) {
            if (authMode == AuthMode.APP_JWT) appJwtRequests.add(new Request(path, token, authMode));
            int status = statusOverrides.getOrDefault(path, defaultStatus(path));
            JsonNode body = overrides.getOrDefault(path, defaultBody(path));
            return new ApiResponse(status, body);
        }

        @Override
        public ApiResponse url(URI uri, Map<String, String> headers) {
            if (GitHubSettingsCaptureV5.OIDC_JWKS_URL.equals(uri.toString())) {
                RSAPublicKey key = (RSAPublicKey) oidcKeys.getPublic();
                ObjectNode jwk = (ObjectNode) json("{\"kid\":\"test-kid\",\"kty\":\"RSA\"}");
                jwk.put("n", unsigned(key.getModulus()));
                jwk.put("e", unsigned(key.getPublicExponent()));
                ObjectNode body = JsonHashes.mapper().createObjectNode();
                body.putArray("keys").add(jwk);
                return new ApiResponse(200, body);
            }
            assertThat(headers.get("Authorization")).isEqualTo("bearer request-token");
            return new ApiResponse(200, json("{\"value\":\"" + oidcJwt() + "\"}"));
        }

        private int defaultStatus(String path) {
            if (path.endsWith("/branches/strategy-v5-evidence/protection")) return 404;
            if (path.contains("/actions/secrets/") || path.startsWith("orgs/")) return 404;
            return 200;
        }

        private JsonNode defaultBody(String path) {
            if (path.equals("repos/owner/repo")) return json("{\"id\":1,\"name\":\"repo\","
                    + "\"owner\":{\"id\":2,\"login\":\"owner\",\"type\":\"User\"},"
                    + "\"full_name\":\"owner/repo\",\"private\":false}");
            if (path.endsWith("/branches/strategy-v5-evidence/protection")) return json("{}");
            if (path.endsWith("/branches/strategy-v5-evidence"))
                return json("{\"commit\":{\"sha\":\"" + "f".repeat(64) + "\"}}");
            if (path.endsWith("/environments/prospective-v5")) return environment("reviewer");
            if (path.endsWith("/environments/evidence-writer-v5")) return environment("writer-reviewer");
            if (path.contains("/rulesets?")) return json("[{\"id\":7},{\"id\":8},{\"id\":9}]");
            if (path.endsWith("/rulesets/7")) return json("{\"target\":\"branch\","
                    + "\"enforcement\":\"active\",\"conditions\":{\"ref_name\":{"
                    + "\"include\":[\"refs/heads/strategy-v5-evidence\"]}},\"bypass_actors\":[],"
                    + "\"rules\":[{\"type\":\"deletion\"},{\"type\":\"non_fast_forward\"}]}");
            if (path.endsWith("/rulesets/8")) return json(writerRuleset(
                    "strategy-v5-evidence-custody", 15368, true, 0,
                    "refs/heads/strategy-v5-evidence"));
            if (path.endsWith("/rulesets/9")) return json("{\"target\":\"branch\","
                    + "\"enforcement\":\"active\",\"conditions\":{\"ref_name\":{"
                    + "\"include\":[\"refs/heads/main\"]}},\"bypass_actors\":[],\"rules\":["
                    + "{\"type\":\"deletion\"},{\"type\":\"non_fast_forward\"},"
                    + "{\"type\":\"pull_request\",\"parameters\":{"
                    + "\"required_approving_review_count\":1,\"dismiss_stale_reviews_on_push\":true,"
                    + "\"require_last_push_approval\":true}}]}");
            if (path.endsWith("/actions/oidc/customization/sub")) return json("{\"use_default\":false,"
                    + "\"use_immutable_subject\":true,\"include_claim_keys\":[\"repo\",\"context\"]}");
            if (path.endsWith("/actions/permissions/selected-actions")) return json("{"
                    + "\"github_owned_allowed\":true,\"verified_allowed\":false,\"patterns_allowed\":[]}");
            if (path.endsWith("/actions/permissions/workflow")) return json("{"
                    + "\"default_workflow_permissions\":\"read\","
                    + "\"can_approve_pull_request_reviews\":false}");
            if (path.endsWith("/actions/permissions")) return json("{\"allowed_actions\":\"selected\","
                    + "\"sha_pinning_required\":true}");
            if (path.equals("user")) return json("{\"id\":123,\"login\":\"settings-bot\"}");
            if (path.equals("app")) return json("{\"id\":4716635,"
                    + "\"slug\":\"strategy-v5-settings-auditor\",\"permissions\":{"
                    + permissions() + "},\"events\":[]}");
            if (path.equals("app/installations/156531963")) return json("{\"id\":156531963,"
                    + "\"app_id\":4716635,\"app_slug\":\"strategy-v5-settings-auditor\","
                    + "\"repository_selection\":\"selected\",\"permissions\":{" + permissions() + "},"
                    + "\"events\":[],\"account\":{\"id\":2,\"login\":\"owner\","
                    + "\"type\":\"User\"}}");
            if (path.equals("installation/repositories")) return json("{\"total_count\":1,"
                    + "\"repositories\":[{\"id\":1,\"name\":\"repo\","
                    + "\"full_name\":\"owner/repo\",\"owner\":{\"id\":2,\"login\":\"owner\"}}]}");
            if (path.endsWith("/installation")) return json("{\"app_id\":4716635,"
                    + "\"app_slug\":\"strategy-v5-settings-auditor\"}");
            if (path.contains("/environments/") && path.contains("/secrets/"))
                return json("{\"name\":\"configured\"}");
            return json("{}");
        }

        private JsonNode environment(String reviewer) {
            return json("{\"can_admins_bypass\":false,\"protection_rules\":[{"
                    + "\"type\":\"required_reviewers\",\"reviewers\":[{\"login\":\""
                    + reviewer + "\"}],\"prevent_self_review\":true}],"
                    + "\"deployment_branch_policy\":{\"protected_branches\":true,"
                    + "\"custom_branch_policies\":false}}");
        }

        private String oidcJwt() {
            try {
                String header = part("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"test-kid\"}");
                String payload = part("{\"repository_id\":\"1\",\"repository_owner_id\":\"2\","
                        + "\"environment\":\"prospective-v5\","
                        + "\"workflow_ref\":\"owner/repo/.github/workflows/strategy-v5-prospective.yml@refs/heads/main\","
                        + "\"workflow_sha\":\"" + "a".repeat(64) + "\",\"run_id\":\"42\","
                        + "\"run_attempt\":\"1\",\"sub\":"
                        + "\"repo:owner@2/repo@1:environment:prospective-v5\","
                        + "\"aud\":\"strategy-v5\",\"iss\":"
                        + "\"https://token.actions.githubusercontent.com\",\"iat\":"
                        + (NOW.getEpochSecond() - 30) + ",\"exp\":" + (NOW.getEpochSecond() + 570) + "}");
                String input = header + "." + payload;
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(oidcKeys.getPrivate());
                signature.update(input.getBytes(StandardCharsets.US_ASCII));
                byte[] signed = signature.sign();
                if (forgeOidcSignature) signed[0] ^= 1;
                return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }

        private static String part(String json) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }

        private static String unsigned(BigInteger value) {
            byte[] bytes = value.toByteArray();
            if (bytes.length > 1 && bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
