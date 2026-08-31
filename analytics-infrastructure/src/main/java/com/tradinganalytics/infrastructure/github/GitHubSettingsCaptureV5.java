package com.tradinganalytics.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Java port of {@code tools/capture-github-settings.mjs}.
 *
 * <p>The service deliberately separates HTTP transport from policy evaluation. Production uses
 * the JDK HTTP client, so credentials are sent in request headers and never enter child-process
 * arguments or environments. Tests inject a deterministic transport and exercise the same policy
 * builder without network access.</p>
 */
public final class GitHubSettingsCaptureV5 {
    public static final long SETTINGS_AUDITOR_APP_ID = 4_716_635L;
    public static final long SETTINGS_AUDITOR_INSTALLATION_ID = 156_531_963L;
    public static final String SETTINGS_AUDITOR_APP_SLUG = "strategy-v5-settings-auditor";
    public static final String DEFAULT_EVIDENCE_BRANCH = "strategy-v5-evidence";
    public static final String PROSPECTIVE_ENVIRONMENT = "prospective-v5";
    public static final String WRITER_ENVIRONMENT = "evidence-writer-v5";
    public static final String OIDC_JWKS_URL =
            "https://token.actions.githubusercontent.com/.well-known/jwks";

    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Map<String, String> AUDITOR_PERMISSIONS = Map.of(
            "actions", "read",
            "administration", "read",
            "environments", "read",
            "metadata", "read",
            "secrets", "read");
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public enum AuthMode { TOKEN, APP_JWT }

    public record ApiResponse(int status, JsonNode body) {
        public ApiResponse {
            body = body == null ? JsonHashes.mapper().createObjectNode() : body.deepCopy();
            if (status < 0) status = 0;
        }

        public static ApiResponse of(int status, String json) {
            try {
                return new ApiResponse(status, JsonHashes.mapper().readTree(json));
            } catch (IOException invalid) {
                return new ApiResponse(0, JsonHashes.mapper().createObjectNode());
            }
        }
    }

    /** Transport boundary used by both GitHub API and OIDC/JWKS requests. */
    public interface Transport {
        ApiResponse github(String path, String token, AuthMode authMode);

        ApiResponse url(URI uri, Map<String, String> headers);
    }

    /** Production transport. No credential is placed in a command line or subprocess. */
    public static final class HttpTransport implements Transport {
        private final HttpClient client;
        private final URI apiRoot;

        public HttpTransport() {
            this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                    URI.create("https://api.github.com"));
        }

        public HttpTransport(HttpClient client, URI apiRoot) {
            this.client = Objects.requireNonNull(client, "client");
            String root = Objects.requireNonNull(apiRoot, "apiRoot").toString();
            this.apiRoot = URI.create(root.endsWith("/") ? root : root + "/");
        }

        @Override
        public ApiResponse github(String path, String token, AuthMode authMode) {
            String clean = Objects.requireNonNullElse(path, "").replaceFirst("^/+", "");
            return request(apiRoot.resolve(clean), token == null || token.isBlank()
                    ? Map.of()
                    : Map.of("Authorization", "Bearer " + token,
                            "Accept", "application/vnd.github+json"));
        }

        @Override
        public ApiResponse url(URI uri, Map<String, String> headers) {
            return request(uri, headers == null ? Map.of() : headers);
        }

        private ApiResponse request(URI uri, Map<String, String> headers) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    builder.header(header.getKey(), header.getValue());
                }
                HttpResponse<String> response = client.send(
                        builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return ApiResponse.of(response.statusCode(), response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new ApiResponse(0, JsonHashes.mapper().createObjectNode());
            } catch (IOException | IllegalArgumentException failure) {
                return new ApiResponse(0, JsonHashes.mapper().createObjectNode());
            }
        }
    }

    public record Result(ObjectNode capture, ObjectNode receipt) {
        public Result {
            capture = capture.deepCopy();
            receipt = receipt.deepCopy();
        }

        public boolean verified() {
            return capture.path("verified").asBoolean(false);
        }
    }

    private record OidcIdentity(ObjectNode claims, boolean signatureVerified) {}
    private record InstalledApp(int status, Long id, String slug, boolean verified) {}
    private record BypassActor(String type, long id, String mode) {}
    private record DetailRow(
            long id,
            int status,
            String target,
            String enforcement,
            List<String> refs,
            List<String> ruleTypes,
            List<String> requiredStatusContexts,
            List<Long> requiredStatusIntegrations,
            boolean strictStatusChecks,
            ObjectNode pullRequestParameters,
            List<BypassActor> bypassActors,
            List<Long> bypassAppIds,
            String layer,
            boolean rulesVerified,
            boolean immutableRulesVerified,
            boolean writerGateRulesVerified,
            String bodySha256) {}

    private GitHubSettingsCaptureV5() {}

    /** Captures and validates both physical JSON documents. */
    public static Result capture(Map<String, String> environment, Transport transport, Clock clock) {
        Env env = new Env(environment);
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(clock, "clock");

        String repository = required(env.get("GITHUB_REPOSITORY"), "GITHUB_REPOSITORY is required");
        String[] repositoryParts = repository.split("/", -1);
        if (repositoryParts.length != 2 || repositoryParts[0].isBlank() || repositoryParts[1].isBlank()) {
            throw new IllegalArgumentException("GITHUB_REPOSITORY must be owner/name");
        }
        String tokenKind = env.get("V5_SETTINGS_TOKEN_KIND").toUpperCase(Locale.ROOT);
        String bootstrapToken = env.get("GH_TOKEN");
        boolean requireToken = env.trueValue("V5_REQUIRE_SETTINGS_TOKEN");
        boolean requireAuditor = env.trueValue("V5_REQUIRE_SETTINGS_AUDITOR");
        if (requireToken && bootstrapToken.isBlank()) {
            throw new IllegalArgumentException(
                    "a protected settings token is required; refusing to use an unbound default token");
        }
        if (requireToken && !Set.of("PAT", "APP").contains(tokenKind)) {
            throw new IllegalArgumentException("V5_SETTINGS_TOKEN_KIND must be explicitly PAT or APP");
        }
        if (!Set.of("PAT", "APP").contains(tokenKind)) {
            throw new IllegalArgumentException("V5_SETTINGS_TOKEN_KIND must be explicitly PAT or APP");
        }

        String configuredAuditorAppId = env.get("V5_SETTINGS_AUDITOR_APP_ID");
        String configuredAuditorInstallationId = env.get("V5_SETTINGS_AUDITOR_INSTALLATION_ID");
        String auditorPem = env.get("V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM");
        boolean auditorIdentityConfigured = configuredAuditorAppId.equals(String.valueOf(SETTINGS_AUDITOR_APP_ID))
                && configuredAuditorInstallationId.equals(String.valueOf(SETTINGS_AUDITOR_INSTALLATION_ID));
        if (requireAuditor && (!"APP".equals(tokenKind)
                || configuredAuditorAppId.isBlank()
                || configuredAuditorInstallationId.isBlank()
                || auditorPem.isBlank())) {
            throw new IllegalArgumentException(
                    "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM and pinned auditor App/installation ids are required; refusing PAT-only settings custody");
        }
        if (requireAuditor && !auditorIdentityConfigured) {
            throw new IllegalArgumentException(
                    "auditor App/installation environment must exactly match the frozen deployment identity");
        }

        String declaredVisibility = env.get("V5_REPOSITORY_VISIBILITY").toUpperCase(Locale.ROOT);
        if (requireToken && !Set.of("PUBLIC", "PRIVATE").contains(declaredVisibility)) {
            throw new IllegalArgumentException(
                    "V5_REPOSITORY_VISIBILITY must be explicitly declared as PUBLIC or PRIVATE");
        }
        String evidenceBranch = env.or("V5_EVIDENCE_BRANCH", DEFAULT_EVIDENCE_BRANCH);
        String encodedRepository = encodePath(repositoryParts[0]) + "/" + encodePath(repositoryParts[1]);
        String encodedBranch = encodePath(evidenceBranch);

        ApiResponse repositoryApi = transport.github("repos/" + encodedRepository, bootstrapToken, AuthMode.TOKEN);
        ApiResponse branch = transport.github(
                "repos/" + encodedRepository + "/branches/" + encodedBranch + "/protection",
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse branchHead = transport.github(
                "repos/" + encodedRepository + "/branches/" + encodedBranch,
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse prospectiveEnvironment = transport.github(
                "repos/" + encodedRepository + "/environments/" + PROSPECTIVE_ENVIRONMENT,
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse writerEnvironment = transport.github(
                "repos/" + encodedRepository + "/environments/" + WRITER_ENVIRONMENT,
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse rulesets = transport.github(
                "repos/" + encodedRepository + "/rulesets?includes_parents=true",
                bootstrapToken, AuthMode.TOKEN);

        List<JsonNode> rawRulesetRows = rows(rulesets.body(), "rulesets");
        List<ApiResponse> rulesetResponses = new ArrayList<>();
        List<Long> rulesetIdsInRequestOrder = new ArrayList<>();
        for (JsonNode row : rawRulesetRows) {
            Long id = nullableLong(row.get("id"));
            if (id == null) continue;
            rulesetIdsInRequestOrder.add(id);
            rulesetResponses.add(transport.github(
                    "repos/" + encodedRepository + "/rulesets/" + id,
                    bootstrapToken, AuthMode.TOKEN));
        }

        ApiResponse oidcPolicy = transport.github(
                "repos/" + encodedRepository + "/actions/oidc/customization/sub",
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse actionsPermissions = transport.github(
                "repos/" + encodedRepository + "/actions/permissions",
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse selectedPermissions = transport.github(
                "repos/" + encodedRepository + "/actions/permissions/selected-actions",
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse workflowPermissions = transport.github(
                "repos/" + encodedRepository + "/actions/permissions/workflow",
                bootstrapToken, AuthMode.TOKEN);

        String actionsSecretName = env.or(
                "V5_ACTIONS_ATTESTATION_SECRET_NAME",
                "PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64");
        String organization = repositoryParts[0];
        ApiResponse actionsSecret = transport.github(
                "repos/" + encodedRepository + "/environments/" + PROSPECTIVE_ENVIRONMENT
                        + "/secrets/" + encodePath(actionsSecretName),
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse repositorySecret = transport.github(
                "repos/" + encodedRepository + "/actions/secrets/" + encodePath(actionsSecretName),
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse organizationSecret = transport.github(
                "orgs/" + encodePath(organization) + "/actions/secrets/" + encodePath(actionsSecretName),
                bootstrapToken, AuthMode.TOKEN);

        String defaultSettingsSecret = "APP".equals(tokenKind)
                ? "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM"
                : "V5_GITHUB_SETTINGS_PAT";
        if ("APP".equals(tokenKind)
                && !env.get("V5_SETTINGS_TOKEN_SECRET_NAME").isBlank()
                && !defaultSettingsSecret.equals(env.get("V5_SETTINGS_TOKEN_SECRET_NAME"))) {
            throw new IllegalArgumentException(
                    "APP settings custody must use the protected auditor secret " + defaultSettingsSecret);
        }
        String settingsSecretName = "APP".equals(tokenKind)
                ? defaultSettingsSecret
                : env.or("V5_SETTINGS_TOKEN_SECRET_NAME", defaultSettingsSecret);
        ApiResponse settingsSecret = transport.github(
                "repos/" + encodedRepository + "/environments/" + PROSPECTIVE_ENVIRONMENT
                        + "/secrets/" + encodePath(settingsSecretName),
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse settingsRepositorySecret = transport.github(
                "repos/" + encodedRepository + "/actions/secrets/" + encodePath(settingsSecretName),
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse settingsOrganizationSecret = transport.github(
                "orgs/" + encodePath(organization) + "/actions/secrets/" + encodePath(settingsSecretName),
                bootstrapToken, AuthMode.TOKEN);

        String writerSecretName = env.or(
                "V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_SECRET_NAME",
                "V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM");
        ApiResponse writerSecret = transport.github(
                "repos/" + encodedRepository + "/environments/" + WRITER_ENVIRONMENT
                        + "/secrets/" + encodePath(writerSecretName),
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse writerRepositorySecret = transport.github(
                "repos/" + encodedRepository + "/actions/secrets/" + encodePath(writerSecretName),
                bootstrapToken, AuthMode.TOKEN);
        ApiResponse writerOrganizationSecret = transport.github(
                "orgs/" + encodePath(organization) + "/actions/secrets/" + encodePath(writerSecretName),
                bootstrapToken, AuthMode.TOKEN);

        ApiResponse installation = new ApiResponse(0, object(
                "skipped_for", "PAT", "installation_proof", "UNPROVEN"));
        InstalledApp installedApp = parseInstalledApp(installation, tokenKind);
        ObjectNode auditorProof = auditorProofBase(tokenKind, auditorIdentityConfigured);
        if ("APP".equals(tokenKind) && auditorIdentityConfigured && !auditorPem.isBlank()) {
            String jwt = auditorAppJwt(SETTINGS_AUDITOR_APP_ID, auditorPem, clock.instant());
            installation = transport.github(
                    "repos/" + encodedRepository + "/installation", jwt, AuthMode.APP_JWT);
            installedApp = parseInstalledApp(installation, tokenKind);
            ApiResponse appMetadata = transport.github("app", jwt, AuthMode.APP_JWT);
            ApiResponse installationMetadata = transport.github(
                    "app/installations/" + SETTINGS_AUDITOR_INSTALLATION_ID,
                    jwt, AuthMode.APP_JWT);
            ApiResponse accessibleRepositories = transport.github(
                    "installation/repositories", bootstrapToken, AuthMode.TOKEN);
            auditorProof = buildAuditorProof(
                    tokenKind, auditorIdentityConfigured, repository, repositoryApi,
                    installedApp, appMetadata, installationMetadata, accessibleRepositories);
        }

        ApiResponse settingsIdentity = "PAT".equals(tokenKind)
                ? transport.github("user", bootstrapToken, AuthMode.TOKEN)
                : installation;
        OidcIdentity oidcIdentity = requestOidcIdentity(env, transport);
        Instant capturedAt = clock.instant();

        return assemble(new AssemblyInputs(
                env, repository, tokenKind, evidenceBranch, declaredVisibility, capturedAt,
                repositoryApi, branch, branchHead, prospectiveEnvironment, writerEnvironment,
                rulesets, rawRulesetRows, rulesetIdsInRequestOrder, rulesetResponses,
                oidcPolicy, actionsPermissions, selectedPermissions, workflowPermissions,
                actionsSecretName, actionsSecret, repositorySecret, organizationSecret,
                settingsSecretName, settingsSecret, settingsRepositorySecret, settingsOrganizationSecret,
                writerSecretName, writerSecret, writerRepositorySecret, writerOrganizationSecret,
                installation, installedApp, auditorProof, settingsIdentity, oidcIdentity));
    }

    private static Result assemble(AssemblyInputs in) {
        ObjectNode repositoryBody = asObject(in.repository().body());
        ObjectNode branchBody = asObject(in.branch().body());

        List<DetailRow> detailRows = buildDetailRows(in);
        List<DetailRow> protectedRows = detailRows.stream()
                .filter(row -> "branch".equalsIgnoreCase(row.target()))
                .filter(row -> "ACTIVE".equals(row.enforcement()))
                .filter(row -> row.refs().stream().anyMatch(ref -> refMatches(ref, in.evidenceBranch())))
                .toList();
        List<DetailRow> immutableRows = protectedRows.stream()
                .filter(row -> "IMMUTABLE_CORE".equals(row.layer())).toList();
        List<DetailRow> writerGateRows = protectedRows.stream()
                .filter(row -> "WRITER_GATE".equals(row.layer())).toList();
        List<DetailRow> mainRows = detailRows.stream()
                .filter(row -> "branch".equalsIgnoreCase(row.target()))
                .filter(row -> "ACTIVE".equals(row.enforcement()))
                .filter(row -> row.refs().equals(List.of("refs/heads/main")))
                .toList();
        ObjectNode mainPullParameters = mainRows.isEmpty()
                ? JsonHashes.mapper().createObjectNode()
                : mainRows.get(0).pullRequestParameters();
        boolean mainPolicyVerified = mainRows.size() == 1
                && mainRows.get(0).ruleTypes().equals(
                        List.of("deletion", "non_fast_forward", "pull_request"))
                && number(mainPullParameters.get("required_approving_review_count"), 0) >= 1
                && isTrue(mainPullParameters.get("dismiss_stale_reviews_on_push"))
                && isTrue(mainPullParameters.get("require_last_push_approval"))
                && mainRows.get(0).bypassActors().isEmpty();
        boolean detailStatusesOk = in.rulesets().status() == 200
                && in.rulesetResponses().stream().allMatch(response -> response.status() == 200);
        boolean immutablePolicyVerified = protectedRows.size() == 2
                && immutableRows.size() == 1
                && immutableRows.get(0).bypassActors().isEmpty();
        Long configuredWriterAppId = strictLong(in.env().get("V5_EVIDENCE_WRITER_APP_ID"));
        boolean writerAppIdValid = configuredWriterAppId != null
                && configuredWriterAppId == WriterInstallationReceipts.WRITER_APP_ID;
        boolean actionsOnlyBypassVerified = writerAppIdValid
                && protectedRows.size() == 2
                && writerGateRows.size() == 1
                && writerGateRows.get(0).bypassActors().isEmpty()
                && immutableRows.size() == 1
                && immutableRows.get(0).bypassActors().isEmpty();
        boolean layeredPolicyVerified = immutablePolicyVerified
                && actionsOnlyBypassVerified
                && protectedRows.stream().allMatch(row -> row.rulesVerified()
                        && row.refs().size() == 1
                        && refMatches(row.refs().get(0), in.evidenceBranch()));

        ObjectNode normalizedRulesets = normalizeRulesets(
                in, detailRows, protectedRows, immutableRows, writerGateRows, mainRows,
                writerAppIdValid, actionsOnlyBypassVerified, immutablePolicyVerified,
                layeredPolicyVerified, detailStatusesOk);

        ObjectNode actionsSecretSummary = secretSummary(
                in.actionsSecretName(), in.actionsSecret(), in.repositorySecret(),
                in.organizationSecret(), true);
        ObjectNode settingsSecretSummary = secretSummary(
                in.settingsSecretName(), in.settingsSecret(), in.settingsRepositorySecret(),
                in.settingsOrganizationSecret(), true);
        ObjectNode actionsPermissionsSummary = actionsPermissionsSummary(
                in.actionsPermissions(), in.selectedPermissions(), in.workflowPermissions());

        EnvironmentEvaluation environment = evaluateEnvironment(in.environment());
        EnvironmentEvaluation writerEnvironment = evaluateEnvironment(in.writerEnvironment());
        ObjectNode writerEnvironmentSummary = writerEnvironment.summary();
        ObjectNode writerSecretSummary = secretSummary(
                in.writerSecretName(), in.writerSecret(), in.writerRepositorySecret(),
                in.writerOrganizationSecret(), writerEnvironment.secure());
        boolean writerCredentialConfigured = writerSecretSummary.path("verified").asBoolean(false);
        normalizedRulesets.put("evidence_writer_credential_configured", writerCredentialConfigured);
        normalizedRulesets.put("verified",
                detailStatusesOk
                        && actionsOnlyBypassVerified
                        && layeredPolicyVerified
                        && mainPolicyVerified
                        && writerCredentialConfigured);

        ObjectNode branchSummary = normalizeBranchProtection(
                in.branch(), branchBody, in.installedApp(), in.tokenKind());
        boolean exactBranchApp = branchSummary.path("restrictions").path("apps_verified").asBoolean(false)
                && branchSummary.path("restrictions").path("users").isEmpty()
                && branchSummary.path("restrictions").path("teams").isEmpty();
        boolean legacyBranchSecure = in.branch().status() == 200
                && branchSummary.path("enforce_admins").asBoolean(false)
                && branchSummary.path("required_pull_request_reviews").asBoolean(false)
                && branchSummary.path("required_status_checks").asBoolean(false)
                && !branchSummary.path("allow_force_pushes").asBoolean(false)
                && !branchSummary.path("allow_deletions").asBoolean(false)
                && exactBranchApp
                && !branchSummary.path("restrictions").path("apps").isEmpty();
        boolean rulesetBranchSecure = in.branchHead().status() == 200
                && normalizedRulesets.path("api_status").asInt() == 200
                && layeredPolicyVerified
                && normalizedRulesets.path("protected_ref_matches").asBoolean(false)
                && immutablePolicyVerified
                && normalizedRulesets.path("writer_gate_policy_verified").asBoolean(false)
                && normalizedRulesets.path("enforcement_verified").asBoolean(false)
                && normalizedRulesets.path("rules_verified").asBoolean(false);
        boolean branchSecure = legacyBranchSecure || rulesetBranchSecure;

        ObjectNode settingsIdentitySummary = settingsIdentitySummary(
                in, settingsSecretSummary, in.auditorProof());
        OidcVerification oidc = verifyOidc(in, repositoryBody);

        boolean declaredPrivate = repositoryBody.path("private").asBoolean(false);
        String repositoryVisibility = in.declaredVisibility().isBlank()
                ? (declaredPrivate ? "PRIVATE" : "PUBLIC")
                : in.declaredVisibility();
        boolean repositoryVisibilityVerified = in.repository().status() == 200
                && Set.of("PUBLIC", "PRIVATE").contains(repositoryVisibility)
                && ("PRIVATE".equals(repositoryVisibility) == declaredPrivate);
        boolean auditorProofRequired = "APP".equals(in.tokenKind());
        boolean auditorProofVerified = !auditorProofRequired
                || in.auditorProof().path("verified").asBoolean(false);
        boolean allVerified = repositoryVisibilityVerified
                && branchSecure
                && in.branchHead().status() == 200
                && (legacyBranchSecure || (normalizedRulesets.path("verified").asBoolean(false)
                        && rulesetBranchSecure))
                && environment.secure()
                && writerEnvironment.secure()
                && writerSecretSummary.path("verified").asBoolean(false)
                && in.oidcPolicy().status() == 200
                && isFalse(asObject(in.oidcPolicy().body()).get("use_default"))
                && isTrue(asObject(in.oidcPolicy().body()).get("use_immutable_subject"))
                && oidc.claimPolicyVerified()
                && oidc.identityVerified()
                && actionsSecretSummary.path("verified").asBoolean(false)
                && settingsIdentitySummary.path("verified").asBoolean(false)
                && actionsPermissionsSummary.path("verified").asBoolean(false)
                && auditorProofVerified;

        ObjectNode apiBody = buildApiBody(
                in, repositoryBody, repositoryVisibility, repositoryVisibilityVerified,
                branchSummary, environment, writerEnvironmentSummary, normalizedRulesets,
                actionsPermissionsSummary, actionsSecretSummary, writerSecretSummary,
                settingsSecretSummary, settingsIdentitySummary, oidc);

        int rulesetDetailsStatus = detailStatusesOk ? 200 : in.rulesetResponses().stream()
                .filter(response -> response.status() != 200)
                .map(ApiResponse::status)
                .findFirst().orElse(in.rulesets().status());
        Map<String, Object> endpointStatuses = new LinkedHashMap<>();
        endpointStatuses.put("repository", in.repository().status());
        endpointStatuses.put("branch_protection", in.branch().status());
        endpointStatuses.put("branch_head", in.branchHead().status());
        endpointStatuses.put("environment_protection", in.environment().status());
        endpointStatuses.put("writer_environment_protection", in.writerEnvironment().status());
        endpointStatuses.put("rulesets", in.rulesets().status());
        endpointStatuses.put("ruleset_details", rulesetDetailsStatus);
        endpointStatuses.put("installation", in.installation().status());
        endpointStatuses.put("settings_token_identity", in.settingsIdentity().status());
        endpointStatuses.put("settings_token_secret", in.settingsSecret().status());
        endpointStatuses.put("evidence_writer_secret", in.writerSecret().status());
        endpointStatuses.put("evidence_writer_repository_secret", in.writerRepositorySecret().status());
        endpointStatuses.put("evidence_writer_organization_secret", in.writerOrganizationSecret().status());
        endpointStatuses.put("oidc_subject_restriction", in.oidcPolicy().status());
        endpointStatuses.put("actions_permissions", in.actionsPermissions().status());
        endpointStatuses.put("actions_selected_permissions", in.selectedPermissions().status());
        endpointStatuses.put("actions_workflow_permissions", in.workflowPermissions().status());
        endpointStatuses.put("settings_auditor_app", in.auditorProof().path("app_endpoint_status").asInt());
        endpointStatuses.put("settings_auditor_installation",
                in.auditorProof().path("installation_endpoint_status").asInt());
        endpointStatuses.put("settings_auditor_repositories",
                in.auditorProof().path("repositories_endpoint_status").asInt());

        Map<String, Object> statusForFailure = new LinkedHashMap<>(endpointStatuses);
        if (rulesetBranchSecure && in.branch().status() == 404) {
            statusForFailure.put("branch_protection", 200);
        }
        if ("PAT".equals(in.tokenKind()) && in.installation().status() != 200) {
            statusForFailure.put("installation", 200);
        }
        if ("PAT".equals(in.tokenKind())) {
            statusForFailure.put("settings_auditor_app", 200);
            statusForFailure.put("settings_auditor_installation", 200);
            statusForFailure.put("settings_auditor_repositories", 200);
        }
        GitHubCapturePolicy.EndpointFailure firstFailure =
                GitHubCapturePolicy.firstNon200Endpoint(statusForFailure);
        String firstFailureReason = GitHubCapturePolicy.captureFailureReason(firstFailure);
        int captureStatus = GitHubCapturePolicy.selectCaptureStatus(allVerified, statusForFailure);

        ObjectNode receipt = buildReceipt(
                in, repositoryVisibility, repositoryVisibilityVerified, normalizedRulesets,
                actionsPermissionsSummary, writerEnvironmentSummary, writerSecretSummary,
                actionsSecretSummary, settingsIdentitySummary, settingsSecretSummary,
                rulesetDetailsStatus, detailRows, endpointStatuses, firstFailureReason,
                branchSecure, writerAppIdValid, writerCredentialConfigured, environment.secure(),
                writerEnvironment.secure(), oidc, allVerified, auditorProofRequired,
                auditorProofVerified);

        ObjectNode githubResponse = object("status", captureStatus, "body", apiBody);
        if (firstFailure != null) githubResponse.put("failure_endpoint", firstFailure.endpoint());
        ObjectNode capture = makeDeploymentSettingsCapture(
                githubResponse,
                oidc.subject(),
                oidc.claims(),
                in.oidcIdentity() != null && in.oidcIdentity().signatureVerified(),
                in.capturedAt(),
                textAt(in.branchHead().body(), "commit", "sha"),
                configuredWriterAppId);

        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(receipt);
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(capture);
        return new Result(capture, receipt);
    }

    private record EnvironmentEvaluation(
            ObjectNode summary, ObjectNode apiBody, boolean secure) {}

    private record OidcVerification(
            ObjectNode claims,
            String subject,
            boolean identityVerified,
            boolean claimPolicyVerified) {}

    private static List<DetailRow> buildDetailRows(AssemblyInputs in) {
        List<DetailRow> result = new ArrayList<>();
        for (int index = 0; index < in.rulesetResponses().size(); index++) {
            ApiResponse response = in.rulesetResponses().get(index);
            long id = in.rulesetIds().get(index);
            ObjectNode body = asObject(response.body());
            List<JsonNode> rules = rows(body.get("rules"), null);
            List<String> ruleTypes = rules.stream()
                    .map(rule -> text(rule.get("type")).toLowerCase(Locale.ROOT))
                    .distinct().sorted().toList();
            JsonNode pullRequest = rules.stream()
                    .filter(rule -> "pull_request".equalsIgnoreCase(text(rule.get("type"))))
                    .findFirst().orElse(null);
            JsonNode statusChecks = rules.stream()
                    .filter(rule -> "required_status_checks".equalsIgnoreCase(text(rule.get("type"))))
                    .findFirst().orElse(null);
            List<JsonNode> requiredChecks = rows(
                    at(statusChecks, "parameters", "required_status_checks"), null);
            List<String> contexts = requiredChecks.stream()
                    .map(check -> text(check.get("context"))).sorted().toList();
            List<Long> integrations = requiredChecks.stream()
                    .map(check -> nullableLong(check.get("integration_id")))
                    .filter(Objects::nonNull).sorted().toList();
            boolean exactCustodyCheck = requiredChecks.stream().anyMatch(check ->
                    "strategy-v5-evidence-custody".equals(text(check.get("context")))
                            && number(check.get("integration_id"), Long.MIN_VALUE) == 15_368L);
            boolean strictStatusChecks = isTrue(at(statusChecks, "parameters",
                    "strict_required_status_checks_policy"));
            List<String> refs = strings(at(body, "conditions", "ref_name", "include"));
            refs = refs.stream().sorted().toList();
            boolean exactEvidenceRef = refs.size() == 1 && refMatches(refs.get(0), in.evidenceBranch());
            List<BypassActor> bypassActors = new ArrayList<>();
            for (JsonNode actor : rows(body.get("bypass_actors"), null)) {
                bypassActors.add(new BypassActor(
                        text(actor.get("actor_type")),
                        number(actor.get("actor_id"), 0),
                        text(actor.get("bypass_mode")).toLowerCase(Locale.ROOT)));
            }
            boolean immutableVerified = exactEvidenceRef
                    && ruleTypes.equals(List.of("deletion", "non_fast_forward"))
                    && bypassActors.isEmpty();
            boolean exactApprovalZero = integral(at(pullRequest, "parameters",
                    "required_approving_review_count"))
                    && number(at(pullRequest, "parameters", "required_approving_review_count"), -1) == 0;
            boolean writerVerified = exactEvidenceRef
                    && ruleTypes.equals(List.of("pull_request", "required_status_checks"))
                    && exactApprovalZero
                    && exactCustodyCheck
                    && strictStatusChecks;
            String layer = immutableVerified ? "IMMUTABLE_CORE" : writerVerified ? "WRITER_GATE" : null;
            List<Long> bypassAppIds = bypassActors.stream()
                    .filter(actor -> "integration".equalsIgnoreCase(actor.type()))
                    .map(BypassActor::id).sorted().toList();
            result.add(new DetailRow(
                    id,
                    response.status(),
                    textOrNull(body.get("target")),
                    upperOrNull(first(body.get("enforcement"), body.get("enforcement_state"))),
                    List.copyOf(refs),
                    List.copyOf(ruleTypes),
                    List.copyOf(contexts),
                    List.copyOf(integrations),
                    strictStatusChecks,
                    asObject(at(pullRequest, "parameters")),
                    List.copyOf(bypassActors),
                    List.copyOf(bypassAppIds),
                    layer,
                    immutableVerified || writerVerified,
                    immutableVerified,
                    writerVerified,
                    JsonHashes.canonicalSha256(body)));
        }
        return List.copyOf(result);
    }

    private static ObjectNode normalizeRulesets(
            AssemblyInputs in,
            List<DetailRow> detailRows,
            List<DetailRow> protectedRows,
            List<DetailRow> immutableRows,
            List<DetailRow> writerRows,
            List<DetailRow> mainRows,
            boolean writerAppValid,
            boolean actionsOnlyBypassVerified,
            boolean immutablePolicyVerified,
            boolean layeredPolicyVerified,
            boolean detailStatusesOk) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", in.rulesets().status());
        value.put("status", in.rulesets().status());
        putLongArray(value.putArray("ids"), in.rulesetIds().stream().sorted().toList());
        List<Long> protectedIds = new ArrayList<>();
        protectedRows.forEach(row -> protectedIds.add(row.id()));
        mainRows.forEach(row -> protectedIds.add(row.id()));
        protectedIds.sort(Long::compareTo);
        putLongArray(value.putArray("protected_branch_ids"), protectedIds);
        putLongArray(value.putArray("immutable_ruleset_ids"),
                immutableRows.stream().map(DetailRow::id).sorted().toList());
        putLongArray(value.putArray("writer_gate_ruleset_ids"),
                writerRows.stream().map(DetailRow::id).sorted().toList());
        ArrayNode layers = value.putArray("layers");
        protectedRows.stream().filter(row -> row.layer() != null)
                .forEach(row -> layers.add(detailLayer(row, row.layer())));
        mainRows.forEach(row -> layers.add(detailLayer(row, "IMMUTABLE_CORE")));
        if (writerAppValid) value.put("evidence_writer_app_id", WriterInstallationReceipts.WRITER_APP_ID);
        else value.putNull("evidence_writer_app_id");
        value.put("evidence_writer_credential_configured", false);
        List<Long> bypassIds = protectedRows.stream()
                .flatMap(row -> row.bypassAppIds().stream())
                .distinct().sorted().toList();
        putLongArray(value.putArray("actions_bypass_app_ids"), bypassIds);
        boolean protectedRefMatches = protectedRows.size() == 2
                && protectedRows.stream().allMatch(row -> row.refs().size() == 1
                        && refMatches(row.refs().get(0), in.evidenceBranch()));
        value.put("protected_ref_matches", protectedRefMatches);
        value.put("bypass_verified", actionsOnlyBypassVerified);
        value.put("actions_only_bypass_verified", actionsOnlyBypassVerified);
        value.put("immutable_policy_verified", immutablePolicyVerified);
        value.put("writer_gate_policy_verified", writerRows.size() == 1
                && writerRows.get(0).rulesVerified()
                && writerRows.get(0).bypassActors().isEmpty());
        value.put("layered_policy_verified", layeredPolicyVerified);
        value.put("enforcement_verified", protectedRows.size() == 2
                && protectedRows.stream().allMatch(row -> "ACTIVE".equals(row.enforcement())));
        value.put("rules_verified", layeredPolicyVerified);
        value.put("detail_statuses_ok", detailStatusesOk);
        value.put("verified", false);
        return value;
    }

    private static ObjectNode detailLayer(DetailRow row, String layer) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("id", row.id());
        value.put("layer", layer);
        value.put("status", row.status());
        putNullable(value, "target", row.target());
        putNullable(value, "enforcement", row.enforcement());
        putStringArray(value.putArray("refs"), row.refs());
        putStringArray(value.putArray("rule_types"), row.ruleTypes());
        putStringArray(value.putArray("required_status_contexts"), row.requiredStatusContexts());
        putLongArray(value.putArray("required_status_check_integrations"),
                row.requiredStatusIntegrations());
        value.put("strict_status_checks", row.strictStatusChecks());
        value.set("pull_request_parameters", row.pullRequestParameters().deepCopy());
        ArrayNode actors = value.putArray("bypass_actors");
        for (BypassActor actor : row.bypassActors()) {
            actors.add(object("type", actor.type(), "id", actor.id(), "mode", actor.mode()));
        }
        value.put("body_sha256", row.bodySha256());
        value.put("rules_verified", row.rulesVerified());
        return value;
    }

    private static ObjectNode normalizeBranchProtection(
            ApiResponse response, ObjectNode body, InstalledApp installedApp, String tokenKind) {
        ObjectNode restrictions = asObject(body.get("restrictions"));
        List<JsonNode> rawApps = rows(restrictions.get("apps"), null);
        ObjectNode normalizedRestrictions = JsonHashes.mapper().createObjectNode();
        putStringArray(normalizedRestrictions.putArray("users"), rows(restrictions.get("users"), null)
                .stream().map(row -> row.isTextual() ? row.asText() : text(row.get("login")))
                .sorted().toList());
        putStringArray(normalizedRestrictions.putArray("teams"), rows(restrictions.get("teams"), null)
                .stream().map(row -> row.isTextual() ? row.asText() : text(row.get("slug")))
                .sorted().toList());
        putStringArray(normalizedRestrictions.putArray("apps"), rawApps.stream()
                .map(row -> firstText(row.get("slug"), row.get("name"), row.get("id")))
                .sorted().toList());
        putLongArray(normalizedRestrictions.putArray("app_ids"), rawApps.stream()
                .map(row -> nullableLong(row.get("id"))).filter(Objects::nonNull).sorted().toList());
        boolean appsVerified = installedApp.verified()
                && !rawApps.isEmpty()
                && rawApps.stream().allMatch(row -> Objects.equals(nullableLong(row.get("id")), installedApp.id())
                        || Objects.equals(textOrNull(row.get("slug")), installedApp.slug()));
        normalizedRestrictions.put("apps_verified", appsVerified);
        ObjectNode installed = JsonHashes.mapper().createObjectNode();
        installed.put("status", installedApp.status());
        putNullable(installed, "id", installedApp.id());
        putNullable(installed, "slug", installedApp.slug());
        installed.put("verified", installedApp.verified());
        normalizedRestrictions.set("installed_app", installed);

        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", response.status());
        value.put("enforce_admins", enabled(body.get("enforce_admins")));
        value.put("required_pull_request_reviews", present(body, "required_pull_request_reviews"));
        value.put("required_status_checks", present(body, "required_status_checks"));
        value.put("allow_force_pushes", enabled(body.get("allow_force_pushes")));
        value.put("allow_deletions", enabled(body.get("allow_deletions")));
        value.put("required_linear_history", enabled(body.get("required_linear_history")));
        value.set("restrictions", normalizedRestrictions);
        return value;
    }

    private static ObjectNode secretSummary(
            String name,
            ApiResponse environment,
            ApiResponse repository,
            ApiResponse organization,
            boolean extraGate) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("name", name);
        value.put("environment_status", environment.status());
        value.put("environment_body_sha256", JsonHashes.canonicalSha256(environment.body()));
        value.put("repository_status", repository.status());
        value.put("repository_body_sha256", JsonHashes.canonicalSha256(repository.body()));
        value.put("organization_status", organization.status());
        value.put("organization_body_sha256", JsonHashes.canonicalSha256(organization.body()));
        value.put("verified", environment.status() == 200
                && repository.status() == 404
                && organization.status() == 404
                && extraGate);
        return value;
    }

    private static ObjectNode actionsPermissionsSummary(
            ApiResponse actions, ApiResponse selected, ApiResponse workflow) {
        ObjectNode actionsBody = asObject(actions.body());
        ObjectNode selectedBody = asObject(selected.body());
        ObjectNode workflowBody = asObject(workflow.body());
        List<String> patterns = strings(selectedBody.get("patterns_allowed")).stream().sorted().toList();
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", actions.status());
        value.put("selected_api_status", selected.status());
        value.put("workflow_api_status", workflow.status());
        value.put("allowed_actions", text(actionsBody.get("allowed_actions")));
        value.put("sha_pinning_required", isTrue(actionsBody.get("sha_pinning_required")));
        value.put("github_owned_allowed", isTrue(selectedBody.get("github_owned_allowed")));
        value.put("verified_allowed", isTrue(selectedBody.get("verified_allowed")));
        putStringArray(value.putArray("patterns_allowed"), patterns);
        value.put("default_workflow_permissions", text(workflowBody.get("default_workflow_permissions")));
        value.put("can_approve_pull_request_reviews",
                isTrue(workflowBody.get("can_approve_pull_request_reviews")));
        value.put("verified", actions.status() == 200
                && selected.status() == 200
                && workflow.status() == 200
                && "selected".equals(text(actionsBody.get("allowed_actions")))
                && isTrue(actionsBody.get("sha_pinning_required"))
                && isTrue(selectedBody.get("github_owned_allowed"))
                && isFalse(selectedBody.get("verified_allowed"))
                && selectedBody.path("patterns_allowed").isArray()
                && selectedBody.path("patterns_allowed").isEmpty()
                && "read".equals(text(workflowBody.get("default_workflow_permissions")))
                && isFalse(workflowBody.get("can_approve_pull_request_reviews")));
        return value;
    }

    private static EnvironmentEvaluation evaluateEnvironment(ApiResponse response) {
        ObjectNode body = asObject(response.body());
        List<JsonNode> rules = rows(body.get("protection_rules"), null);
        List<JsonNode> reviewerRules = rules.stream()
                .filter(GitHubSettingsCaptureV5::isReviewerRule).toList();
        long concreteReviewers = rules.stream().filter(GitHubSettingsCaptureV5::hasConcreteReviewers).count();
        boolean reviewSafe = reviewerRules.isEmpty()
                || reviewerRules.stream().allMatch(rule -> hasConcreteReviewers(rule)
                        && hasSelfReviewProtection(rule));
        boolean secure = response.status() == 200
                && isFalse(body.get("can_admins_bypass"))
                && reviewSafe
                && isTrue(at(body, "deployment_branch_policy", "protected_branches"))
                && !isTrue(at(body, "deployment_branch_policy", "custom_branch_policies"));
        ObjectNode summary = JsonHashes.mapper().createObjectNode();
        summary.put("api_status", response.status());
        summary.put("reviewer_count", concreteReviewers);
        summary.put("required_reviewer_rule_count", reviewerRules.size());
        summary.put("protection_rule_count", rules.size());
        summary.put("can_admins_bypass", isTrue(body.get("can_admins_bypass")));
        summary.put("protected_branches", isTrue(at(body, "deployment_branch_policy", "protected_branches")));
        summary.put("custom_branch_policies",
                isTrue(at(body, "deployment_branch_policy", "custom_branch_policies")));
        summary.put("prevent_self_review", !reviewerRules.isEmpty()
                && reviewerRules.stream().allMatch(GitHubSettingsCaptureV5::hasSelfReviewProtection));
        summary.put("verified", secure);

        ObjectNode apiBody = JsonHashes.mapper().createObjectNode();
        apiBody.put("api_status", response.status());
        apiBody.put("can_admins_bypass", isTrue(body.get("can_admins_bypass")));
        ArrayNode normalizedRules = apiBody.putArray("protection_rules");
        for (JsonNode rule : rules) {
            ObjectNode normalized = JsonHashes.mapper().createObjectNode();
            putNullable(normalized, "type", textOrNull(rule.get("type")));
            ArrayNode reviewers = normalized.putArray("reviewers");
            for (JsonNode reviewer : rows(rule.get("reviewers"), null)) {
                if (reviewer.isTextual()) reviewers.add(reviewer.asText());
                else reviewers.add(object(
                        "id", nullableLong(reviewer.get("id")),
                        "login", textOrNull(reviewer.get("login")),
                        "type", textOrNull(reviewer.get("type"))));
            }
            normalized.put("prevent_self_review", hasSelfReviewProtection(rule));
            normalizedRules.add(normalized);
        }
        ObjectNode branchPolicy = apiBody.putObject("deployment_branch_policy");
        branchPolicy.put("protected_branches",
                isTrue(at(body, "deployment_branch_policy", "protected_branches")));
        branchPolicy.put("custom_branch_policies",
                isTrue(at(body, "deployment_branch_policy", "custom_branch_policies")));
        return new EnvironmentEvaluation(summary, apiBody, secure);
    }

    private static ObjectNode settingsIdentitySummary(
            AssemblyInputs in, ObjectNode settingsSecretSummary, ObjectNode auditorProof) {
        ObjectNode body = asObject(in.settingsIdentity().body());
        String expectedUserId = in.env().get("V5_SETTINGS_TOKEN_USER_ID");
        String expectedLogin = emptyToNull(in.env().get("V5_SETTINGS_TOKEN_LOGIN"));
        Long expectedAppId = strictLong(in.env().get("V5_GITHUB_SETTINGS_APP_ID"));
        boolean identityPinned = "PAT".equals(in.tokenKind())
                ? positiveDecimal(expectedUserId) && expectedLogin != null
                : expectedAppId != null;
        Long bodyAppId = nullableLong(body.get("app_id"));
        Long bodyUserId = nullableLong(body.get("id"));
        String bodyLogin = textOrNull(body.get("login"));
        boolean identityVerified;
        if ("PAT".equals(in.tokenKind())) {
            identityVerified = identityPinned
                    && in.settingsIdentity().status() == 200
                    && bodyUserId != null
                    && String.valueOf(bodyUserId).equals(expectedUserId)
                    && Objects.equals(bodyLogin, expectedLogin);
        } else {
            identityVerified = in.installedApp().verified()
                    && auditorProof.path("verified").asBoolean(false)
                    && in.settingsIdentity().status() == 200
                    && bodyAppId != null
                    && bodyAppId == SETTINGS_AUDITOR_APP_ID
                    && auditorProof.path("app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                    && (!in.env().trueValue("V5_REQUIRE_SETTINGS_TOKEN")
                            || (expectedAppId != null && bodyAppId.equals(expectedAppId)));
        }
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", in.settingsIdentity().status());
        putNullable(value, "app_id", bodyAppId);
        putNullable(value, "user_id", bodyUserId);
        putNullable(value, "login", bodyLogin);
        value.put("token_kind", in.tokenKind());
        putNullable(value, "expected_user_id", positiveDecimal(expectedUserId)
                ? Long.parseLong(expectedUserId) : null);
        putNullable(value, "expected_login", expectedLogin);
        value.put("secret_name", settingsSecretSummary.path("name").asText());
        copyFields(value, settingsSecretSummary, Map.of(
                "secret_environment_status", "environment_status",
                "secret_environment_body_sha256", "environment_body_sha256",
                "secret_repository_status", "repository_status",
                "secret_repository_body_sha256", "repository_body_sha256",
                "secret_organization_status", "organization_status",
                "secret_organization_body_sha256", "organization_body_sha256"));
        value.put("body_sha256", JsonHashes.canonicalSha256(body));
        value.put("verified", identityVerified
                && settingsSecretSummary.path("verified").asBoolean(false));
        return value;
    }

    private static OidcVerification verifyOidc(AssemblyInputs in, ObjectNode repositoryBody) {
        OidcIdentity identity = in.oidcIdentity();
        ObjectNode claims = identity == null ? null : identity.claims();
        String subject = claims == null ? null : textOrNull(claims.get("sub"));
        String repositoryId = emptyToNull(in.env().get("GITHUB_REPOSITORY_ID"));
        if (repositoryId == null && repositoryBody.hasNonNull("id")) {
            repositoryId = repositoryBody.get("id").asText();
        }
        String ownerId = emptyToNull(in.env().get("V5_REPOSITORY_OWNER_ID"));
        if (ownerId == null && at(repositoryBody, "owner", "id") != null
                && !at(repositoryBody, "owner", "id").isNull()) {
            ownerId = at(repositoryBody, "owner", "id").asText();
        }
        String[] parts = in.repositoryName().split("/", -1);
        String expectedSubject = ownerId == null || repositoryId == null ? null
                : "repo:" + parts[0] + "@" + ownerId + "/" + parts[1] + "@"
                        + repositoryId + ":environment:" + PROSPECTIVE_ENVIRONMENT;
        ObjectNode policy = asObject(in.oidcPolicy().body());
        List<String> claimKeys = strings(policy.get("include_claim_keys"));
        boolean claimPolicyVerified = claimKeys.size() == 2
                && new LinkedHashSet<>(claimKeys).size() == 2
                && claimKeys.stream().sorted().toList().equals(List.of("context", "repo"));
        long captureSec = in.capturedAt().getEpochSecond();
        boolean verified = identity != null
                && identity.signatureVerified()
                && claims != null
                && expectedSubject != null
                && Objects.equals(subject, expectedSubject)
                && Objects.equals(textOrNull(claims.get("repository_id")), repositoryId)
                && Objects.equals(textOrNull(claims.get("repository_owner_id")), ownerId)
                && PROSPECTIVE_ENVIRONMENT.equals(text(claims.get("environment")))
                && !text(claims.get("workflow_ref")).isBlank()
                && !text(claims.get("workflow_sha")).isBlank()
                && "https://token.actions.githubusercontent.com".equals(text(claims.get("iss")))
                && audienceContains(claims.get("aud"), "strategy-v5")
                && integral(claims.get("iat"))
                && integral(claims.get("exp"))
                && number(claims.get("exp"), 0) > number(claims.get("iat"), 0)
                && number(claims.get("iat"), Long.MAX_VALUE) <= captureSec + 60
                && number(claims.get("exp"), Long.MIN_VALUE) >= captureSec - 60
                && number(claims.get("exp"), Long.MAX_VALUE)
                        - number(claims.get("iat"), Long.MIN_VALUE) <= 15 * 60
                && optionalEquals(claims.get("workflow_ref"), in.env().get("GITHUB_WORKFLOW_REF"))
                && optionalEquals(claims.get("workflow_sha"), in.env().get("GITHUB_SHA"))
                && optionalEquals(claims.get("run_id"), in.env().get("GITHUB_RUN_ID"))
                && optionalNumberEquals(claims.get("run_attempt"),
                        in.env().get("GITHUB_RUN_ATTEMPT"));
        return new OidcVerification(claims == null ? null : claims.deepCopy(), subject,
                verified, claimPolicyVerified);
    }

    private static ObjectNode buildApiBody(
            AssemblyInputs in,
            ObjectNode repositoryBody,
            String repositoryVisibility,
            boolean repositoryVisibilityVerified,
            ObjectNode branchSummary,
            EnvironmentEvaluation environment,
            ObjectNode writerEnvironmentSummary,
            ObjectNode rulesets,
            ObjectNode actionsPermissions,
            ObjectNode actionsSecret,
            ObjectNode writerSecret,
            ObjectNode settingsSecret,
            ObjectNode settingsIdentity,
            OidcVerification oidc) {
        ObjectNode api = JsonHashes.mapper().createObjectNode();
        ObjectNode repository = api.putObject("repository");
        JsonNode repositoryId = repositoryBody.get("id");
        if (repositoryId == null || repositoryId.isNull()) {
            String declaredId = emptyToNull(in.env().get("GITHUB_REPOSITORY_ID"));
            if (declaredId != null) repositoryId = JsonHashes.mapper().getNodeFactory().textNode(declaredId);
        }
        setNullable(repository, "id", repositoryId);
        String ownerId = emptyToNull(in.env().get("V5_REPOSITORY_OWNER_ID"));
        if (ownerId == null) ownerId = textOrNull(at(repositoryBody, "owner", "id"));
        putNullable(repository, "owner_id", ownerId);
        repository.put("full_name", firstText(repositoryBody.get("full_name"),
                JsonHashes.mapper().getNodeFactory().textNode(in.repositoryName())));
        repository.put("private", repositoryBody.path("private").asBoolean(false));
        api.put("repository_visibility", repositoryVisibility);
        api.put("repository_visibility_verified", repositoryVisibilityVerified);
        api.set("branch_protection", branchSummary.deepCopy());
        ObjectNode branchHead = api.putObject("branch_head");
        branchHead.put("api_status", in.branchHead().status());
        putNullable(branchHead, "sha", textAt(in.branchHead().body(), "commit", "sha"));
        api.set("environment_protection", environment.apiBody().deepCopy());
        api.set("writer_environment_protection", writerEnvironmentSummary.deepCopy());
        api.set("rulesets", rulesets.deepCopy());
        api.set("actions_permissions", actionsPermissions.deepCopy());
        api.set("actions_secret", actionsSecret.deepCopy());
        api.set("evidence_writer_secret", writerSecret.deepCopy());
        api.set("settings_token_secret", settingsSecret.deepCopy());
        api.set("settings_auditor_installation", in.auditorProof().deepCopy());
        ObjectNode installed = api.putObject("installation");
        installed.put("status", in.installedApp().status());
        putNullable(installed, "id", in.installedApp().id());
        putNullable(installed, "slug", in.installedApp().slug());
        installed.put("verified", in.installedApp().verified());
        api.set("settings_token_identity", settingsIdentity.deepCopy());
        ObjectNode oidcNode = api.putObject("oidc");
        oidcNode.put("api_status", in.oidcPolicy().status());
        ObjectNode policy = asObject(in.oidcPolicy().body());
        if (policy.has("use_default")) oidcNode.set("use_default", policy.get("use_default").deepCopy());
        else oidcNode.putNull("use_default");
        oidcNode.put("use_immutable_subject", isTrue(policy.get("use_immutable_subject")));
        putStringArray(oidcNode.putArray("include_claim_keys"),
                strings(policy.get("include_claim_keys")).stream().sorted().toList());
        oidcNode.put("signature_verified",
                in.oidcIdentity() != null && in.oidcIdentity().signatureVerified());
        if (oidc.claims() == null) {
            oidcNode.putNull("claims");
        } else {
            ObjectNode normalized = normalizeOidcClaims(oidc.claims(), ownerId, oidc.subject());
            oidcNode.set("claims", normalized);
        }
        api.put("evidence_branch", in.evidenceBranch());
        return api;
    }

    private static ObjectNode buildReceipt(
            AssemblyInputs in,
            String repositoryVisibility,
            boolean repositoryVisibilityVerified,
            ObjectNode rulesets,
            ObjectNode actionsPermissions,
            ObjectNode writerEnvironment,
            ObjectNode writerSecret,
            ObjectNode actionsSecret,
            ObjectNode settingsIdentity,
            ObjectNode settingsSecret,
            int rulesetDetailsStatus,
            List<DetailRow> detailRows,
            Map<String, Object> endpointStatuses,
            String firstFailureReason,
            boolean branchSecure,
            boolean writerAppIdValid,
            boolean writerCredentialConfigured,
            boolean environmentSecure,
            boolean writerEnvironmentSecure,
            OidcVerification oidc,
            boolean allVerified,
            boolean auditorProofRequired,
            boolean auditorProofVerified) {
        ObjectNode receipt = JsonHashes.mapper().createObjectNode();
        receipt.put("schema", "github-settings-api-receipt/1");
        receipt.put("version", 1);
        receipt.put("repository", in.repositoryName());
        receipt.put("captured_at", iso(in.capturedAt()));
        receipt.put("evidence_branch", in.evidenceBranch());
        receipt.set("actions_permissions", actionsPermissions.deepCopy());
        receipt.set("writer_environment_protection", writerEnvironment.deepCopy());
        receipt.set("evidence_writer_secret", writerSecret.deepCopy());
        receipt.set("actions_secret", actionsSecret.deepCopy());
        receipt.set("rulesets", rulesets.deepCopy());
        receipt.put("repository_visibility", repositoryVisibility);
        receipt.put("repository_visibility_verified", repositoryVisibilityVerified);
        receipt.set("settings_token_identity", settingsIdentity.deepCopy());
        receipt.set("settings_token_secret", settingsSecret.deepCopy());
        receipt.set("settings_auditor_installation", in.auditorProof().deepCopy());
        receipt.put("installation_proof_verified", "APP".equals(in.tokenKind())
                && in.auditorProof().path("verified").asBoolean(false));
        receipt.put("oidc_signature_verified",
                in.oidcIdentity() != null && in.oidcIdentity().signatureVerified());
        ObjectNode endpoints = receipt.putObject("endpoints");
        endpoint(endpoints, "repository", in.repository());
        endpoint(endpoints, "branch_protection", in.branch());
        endpoint(endpoints, "branch_head", in.branchHead());
        endpoint(endpoints, "environment_protection", in.environment());
        endpoint(endpoints, "writer_environment_protection", in.writerEnvironment());
        endpoint(endpoints, "rulesets", in.rulesets());
        ArrayNode internalDetails = JsonHashes.mapper().createArrayNode();
        detailRows.forEach(row -> internalDetails.add(detailInternal(row)));
        endpoint(endpoints, "ruleset_details", rulesetDetailsStatus,
                JsonHashes.canonicalSha256(internalDetails));
        endpoint(endpoints, "installation", in.installation());
        endpoint(endpoints, "settings_token_identity", in.settingsIdentity());
        endpoint(endpoints, "settings_token_secret", in.settingsSecret());
        endpoint(endpoints, "evidence_writer_secret", in.writerSecret());
        endpoint(endpoints, "evidence_writer_repository_secret", in.writerRepositorySecret());
        endpoint(endpoints, "evidence_writer_organization_secret", in.writerOrganizationSecret());
        endpoint(endpoints, "oidc_subject_restriction", in.oidcPolicy());
        endpoint(endpoints, "actions_permissions", in.actionsPermissions());
        endpoint(endpoints, "actions_selected_permissions", in.selectedPermissions());
        endpoint(endpoints, "actions_workflow_permissions", in.workflowPermissions());
        endpoint(endpoints, "settings_auditor_app",
                in.auditorProof().path("app_endpoint_status").asInt(),
                in.auditorProof().path("app_endpoint_body_sha256").asText());
        endpoint(endpoints, "settings_auditor_installation",
                in.auditorProof().path("installation_endpoint_status").asInt(),
                in.auditorProof().path("installation_endpoint_body_sha256").asText());
        endpoint(endpoints, "settings_auditor_repositories",
                in.auditorProof().path("repositories_endpoint_status").asInt(),
                in.auditorProof().path("repositories_endpoint_body_sha256").asText());
        receipt.put("verified", allVerified);
        ArrayNode blockers = receipt.putArray("blockers");
        addBlocker(blockers, firstFailureReason);
        addBlocker(blockers, repositoryVisibilityVerified ? null : "REPOSITORY_VISIBILITY_UNVERIFIED");
        addBlocker(blockers, branchSecure ? null : "BRANCH_PROTECTION_POLICY_UNVERIFIED");
        addBlocker(blockers, in.branchHead().status() == 200 ? null : "EVIDENCE_BRANCH_HEAD_UNVERIFIED");
        addBlocker(blockers, writerAppIdValid ? null : "EVIDENCE_WRITER_APP_ID_UNVERIFIED");
        addBlocker(blockers, writerCredentialConfigured ? null
                : "EVIDENCE_WRITER_APP_CREDENTIAL_UNCONFIGURED");
        addBlocker(blockers, rulesets.path("verified").asBoolean(false) ? null
                : "RULESETS_UNAVAILABLE_OR_UNVERIFIED");
        addBlocker(blockers, "PAT".equals(in.tokenKind())
                || (in.installation().status() == 200 && in.installedApp().verified())
                ? null : "GITHUB_APP_IDENTITY_UNVERIFIED");
        addBlocker(blockers, auditorProofRequired && !auditorProofVerified
                ? "GITHUB_SETTINGS_AUDITOR_INSTALLATION_UNVERIFIED" : null);
        addBlocker(blockers, settingsIdentity.path("verified").asBoolean(false)
                ? null : "GITHUB_SETTINGS_TOKEN_IDENTITY_UNVERIFIED");
        addBlocker(blockers, settingsSecret.path("verified").asBoolean(false)
                ? null : "GITHUB_SETTINGS_TOKEN_SECRET_UNVERIFIED");
        addBlocker(blockers, environmentSecure ? null : "ENVIRONMENT_PROTECTION_POLICY_UNVERIFIED");
        addBlocker(blockers, writerEnvironmentSecure ? null : "EVIDENCE_WRITER_ENVIRONMENT_UNVERIFIED");
        addBlocker(blockers, actionsPermissions.path("verified").asBoolean(false)
                ? null : "GITHUB_ACTIONS_POLICY_UNVERIFIED");
        addBlocker(blockers, in.oidcPolicy().status() == 200
                && oidc.identityVerified()
                && isFalse(asObject(in.oidcPolicy().body()).get("use_default"))
                && isTrue(asObject(in.oidcPolicy().body()).get("use_immutable_subject"))
                && oidc.claimPolicyVerified()
                ? null : "OIDC_SUBJECT_OR_CLAIM_POLICY_UNVERIFIED");
        addBlocker(blockers, actionsSecret.path("verified").asBoolean(false)
                ? null : "ACTIONS_ONLY_SECRET_UNVERIFIED");
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    private static ObjectNode detailInternal(DetailRow row) {
        ObjectNode value = detailLayer(row, row.layer());
        ArrayNode bypassIds = value.putArray("bypass_app_ids");
        putLongArray(bypassIds, row.bypassAppIds());
        if (row.layer() == null) value.putNull("layer");
        value.put("immutable_rules_verified", row.immutableRulesVerified());
        value.put("writer_gate_rules_verified", row.writerGateRulesVerified());
        return value;
    }

    /** Exact Java binding of {@code makeDeploymentSettingsCaptureV5}. */
    public static ObjectNode makeDeploymentSettingsCapture(
            JsonNode githubApiResponse,
            String oidcSubject,
            JsonNode oidcClaims,
            Boolean oidcSignatureVerified,
            Instant capturedAt,
            String evidenceBranchHeadSha256,
            Long evidenceWriterAppId) {
        ObjectNode response = asObject(githubApiResponse);
        int status = (int) number(first(
                response.get("status"), response.get("status_code"), response.get("http_status")), 0);
        ObjectNode body = asObject(first(response.get("body"), response.get("data")));
        ObjectNode repositoryBody = asObject(body.get("repository"));
        String repository = firstText(repositoryBody.get("full_name"), repositoryBody.get("name"),
                body.get("repository_name"));
        JsonNode repositoryIdNode = first(repositoryBody.get("id"), body.get("repository_id"));
        Long repositoryIdLong = nullableLong(repositoryIdNode);
        String repositoryIdText = repositoryIdNode == null || repositoryIdNode.isNull()
                ? null : repositoryIdNode.asText();
        ObjectNode branch = asObject(first(body.get("branch_protection"), body.get("branch")));
        ObjectNode environment = asObject(first(
                body.get("environment_protection"), body.get("environment")));
        ObjectNode writerEnvironment = asObject(body.get("writer_environment_protection"));
        ObjectNode rulesets = asObject(body.get("rulesets"));
        ObjectNode actionSecret = asObject(body.get("actions_secret"));
        ObjectNode actionsPermissions = asObject(body.get("actions_permissions"));
        ObjectNode settingsTokenSecret = asObject(body.get("settings_token_secret"));
        ObjectNode evidenceWriterSecret = asObject(body.get("evidence_writer_secret"));
        ObjectNode oidc = asObject(first(body.get("oidc"), body.get("oidc_workflow")));
        String evidenceBranch = text(body.get("evidence_branch"));

        ObjectNode branchSummary = JsonHashes.mapper().createObjectNode();
        branchSummary.put("api_status", number(branch.get("api_status"), status));
        branchSummary.put("enforce_admins", enabled(branch.get("enforce_admins")));
        branchSummary.put("required_pull_request_reviews",
                present(branch, "required_pull_request_reviews"));
        branchSummary.put("required_status_checks", present(branch, "required_status_checks"));
        branchSummary.put("allow_force_pushes", enabled(branch.get("allow_force_pushes")));
        branchSummary.put("allow_deletions", enabled(branch.get("allow_deletions")));
        branchSummary.put("required_linear_history", enabled(branch.get("required_linear_history")));
        ObjectNode restriction = asObject(branch.get("restrictions"));
        ObjectNode installedSource = asObject(restriction.get("installed_app"));
        ObjectNode restrictions = branchSummary.putObject("restrictions");
        putStringArray(restrictions.putArray("users"), strings(restriction.get("users")).stream()
                .sorted().toList());
        putStringArray(restrictions.putArray("teams"), strings(restriction.get("teams")).stream()
                .sorted().toList());
        putStringArray(restrictions.putArray("apps"), strings(restriction.get("apps")).stream()
                .sorted().toList());
        putLongArray(restrictions.putArray("app_ids"), longs(restriction.get("app_ids")));
        restrictions.put("apps_verified", isTrue(restriction.get("apps_verified")));
        ObjectNode installed = restrictions.putObject("installed_app");
        installed.put("status", number(installedSource.get("status"), 0));
        putNullable(installed, "id", nullableLong(installedSource.get("id")));
        putNullable(installed, "slug", textOrNull(installedSource.get("slug")));
        installed.put("verified", isTrue(installedSource.get("verified")));

        boolean writerAppValid = evidenceWriterAppId != null
                && evidenceWriterAppId == WriterInstallationReceipts.WRITER_APP_ID
                && number(rulesets.get("evidence_writer_app_id"), Long.MIN_VALUE)
                        == WriterInstallationReceipts.WRITER_APP_ID;
        ObjectNode rulesetsSummary = JsonHashes.mapper().createObjectNode();
        rulesetsSummary.put("api_status", number(first(rulesets.get("api_status"), rulesets.get("status")), status));
        rulesetsSummary.put("status", number(rulesets.get("status"), 0));
        putLongArray(rulesetsSummary.putArray("ids"), longs(rulesets.get("ids")));
        putLongArray(rulesetsSummary.putArray("protected_branch_ids"),
                longs(rulesets.get("protected_branch_ids")));
        putLongArray(rulesetsSummary.putArray("immutable_ruleset_ids"),
                longs(rulesets.get("immutable_ruleset_ids")));
        putLongArray(rulesetsSummary.putArray("writer_gate_ruleset_ids"),
                longs(rulesets.get("writer_gate_ruleset_ids")));
        rulesetsSummary.set("layers", rulesets.path("layers").isArray()
                ? rulesets.path("layers").deepCopy() : JsonHashes.mapper().createArrayNode());
        if (writerAppValid) rulesetsSummary.put("evidence_writer_app_id", evidenceWriterAppId);
        else rulesetsSummary.putNull("evidence_writer_app_id");
        copyBoolean(rulesetsSummary, rulesets,
                "evidence_writer_credential_configured", "protected_ref_matches", "bypass_verified",
                "actions_only_bypass_verified", "immutable_policy_verified",
                "writer_gate_policy_verified", "layered_policy_verified", "enforcement_verified",
                "rules_verified");
        putLongArray(rulesetsSummary.putArray("actions_bypass_app_ids"),
                longs(rulesets.get("actions_bypass_app_ids")));
        rulesetsSummary.put("detail_statuses_ok", isTrue(rulesets.get("detail_statuses_ok"))
                || number(rulesets.get("status"), 0) == 200);
        boolean mainPolicyVerified = false;
        for (JsonNode layer : rows(rulesetsSummary.get("layers"), null)) {
            if (strings(layer.get("refs")).equals(List.of("refs/heads/main"))
                    && strings(layer.get("rule_types")).equals(
                            List.of("deletion", "non_fast_forward", "pull_request"))
                    && rows(layer.get("bypass_actors"), null).isEmpty()) {
                mainPolicyVerified = true;
                break;
            }
        }
        rulesetsSummary.put("verified", isTrue(rulesets.get("verified"))
                && isTrue(rulesets.get("layered_policy_verified"))
                && mainPolicyVerified);

        List<JsonNode> environmentRules = rows(environment.get("protection_rules"), null);
        List<JsonNode> requiredReviewerRules = environmentRules.stream()
                .filter(GitHubSettingsCaptureV5::isReviewerRule).toList();
        long reviewerCount = requiredReviewerRules.stream()
                .filter(GitHubSettingsCaptureV5::hasConcreteReviewers).count();
        boolean preventSelfReview = !requiredReviewerRules.isEmpty()
                && requiredReviewerRules.stream().allMatch(rule -> hasConcreteReviewers(rule)
                        && hasSelfReviewProtection(rule));
        ObjectNode environmentSummary = JsonHashes.mapper().createObjectNode();
        environmentSummary.put("reviewer_count", reviewerCount);
        environmentSummary.put("required_reviewer_rule_count", requiredReviewerRules.size());
        environmentSummary.put("protection_rule_count", environmentRules.size());
        environmentSummary.put("can_admins_bypass", isTrue(environment.get("can_admins_bypass")));
        environmentSummary.put("protected_branches",
                isTrue(at(environment, "deployment_branch_policy", "protected_branches")));
        environmentSummary.put("custom_branch_policies",
                isTrue(at(environment, "deployment_branch_policy", "custom_branch_policies")));
        environmentSummary.put("prevent_self_review", preventSelfReview);
        boolean environmentReviewSafe = environmentReviewSafe(environmentSummary);

        ObjectNode writerSummary = normalizeWriterEnvironment(writerEnvironment);
        ObjectNode actionsPermissionsSummary = normalizeActionsPermissions(actionsPermissions);
        ObjectNode writerSecretSummary = normalizeSecret(
                evidenceWriterSecret, "V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM",
                writerSummary.path("verified").asBoolean(false));
        ObjectNode actionsSecretSummary = normalizeSecret(
                actionSecret, "PROD_V5_ACTIONS_ATTESTATION_PRIVATE_KEY_B64", true);
        ObjectNode settingsSecretSummary = normalizeSecret(
                settingsTokenSecret, "V5_GITHUB_SETTINGS_PAT", true);
        ObjectNode identitySource = asObject(body.get("settings_token_identity"));
        String tokenKind = textOrNull(identitySource.get("token_kind"));
        settingsSecretSummary.put("verified", settingsSecretSummary.path("verified").asBoolean(false)
                && settingsAuditorSecretExact(settingsSecretSummary, tokenKind));
        ObjectNode identitySummary = normalizeSettingsIdentity(identitySource, settingsSecretSummary);

        JsonNode suppliedClaimsNode = oidcClaims != null && oidcClaims.isObject()
                ? oidcClaims : oidc.get("claims");
        ObjectNode suppliedClaims = suppliedClaimsNode != null && suppliedClaimsNode.isObject()
                ? (ObjectNode) suppliedClaimsNode.deepCopy() : null;
        Long runAttempt = suppliedClaims == null ? null
                : strictPositiveLong(suppliedClaims.get("run_attempt"));
        JsonNode ownerIdNode = first(repositoryBody.get("owner_id"),
                at(repositoryBody, "owner", "id"), body.get("repository_owner_id"),
                suppliedClaims == null ? null : suppliedClaims.get("repository_owner_id"));
        String ownerId = ownerIdNode == null || ownerIdNode.isNull() ? null : ownerIdNode.asText();
        String[] repoParts = repository.split("/", -1);
        String immutableSubject = ownerId != null && repositoryIdText != null && repoParts.length == 2
                ? "repo:" + repoParts[0] + "@" + ownerId + "/" + repoParts[1] + "@"
                        + repositoryIdText + ":environment:" + PROSPECTIVE_ENVIRONMENT
                : null;
        boolean cryptographic = oidcSignatureVerified == null
                ? isTrue(oidc.get("signature_verified")) : oidcSignatureVerified;
        List<String> includedClaims = strings(oidc.get("include_claim_keys"));
        boolean claimPolicy = includedClaims.size() == 2
                && new LinkedHashSet<>(includedClaims).size() == 2
                && includedClaims.stream().sorted().toList().equals(List.of("context", "repo"));
        long captureSec = capturedAt.getEpochSecond();
        boolean fresh = suppliedClaims != null
                && integral(suppliedClaims.get("iat"))
                && integral(suppliedClaims.get("exp"))
                && number(suppliedClaims.get("exp"), 0) > number(suppliedClaims.get("iat"), 0)
                && number(suppliedClaims.get("iat"), Long.MAX_VALUE) <= captureSec + 60
                && number(suppliedClaims.get("exp"), Long.MIN_VALUE) >= captureSec - 60
                && number(suppliedClaims.get("exp"), Long.MAX_VALUE)
                        - number(suppliedClaims.get("iat"), Long.MIN_VALUE) <= 900;
        boolean subjectBound = oidcSubject != null && !oidcSubject.isBlank()
                && suppliedClaims != null
                && oidcSubject.equals(text(suppliedClaims.get("sub")))
                && isTrue(oidc.get("use_immutable_subject"))
                && oidcSubject.equals(immutableSubject);
        boolean oidcVerified = number(oidc.get("api_status"), status) == 200
                && isFalse(oidc.get("use_default"))
                && isTrue(oidc.get("use_immutable_subject"))
                && claimPolicy
                && cryptographic
                && subjectBound
                && suppliedClaims != null
                && repositoryIdText != null
                && Objects.equals(textOrNull(suppliedClaims.get("repository_id")), repositoryIdText)
                && ownerId != null
                && Objects.equals(textOrNull(suppliedClaims.get("repository_owner_id")), ownerId)
                && PROSPECTIVE_ENVIRONMENT.equals(text(suppliedClaims.get("environment")))
                && !text(suppliedClaims.get("workflow_ref")).isBlank()
                && !text(suppliedClaims.get("workflow_sha")).isBlank()
                && suppliedClaims.has("run_id")
                && runAttempt != null
                && "https://token.actions.githubusercontent.com".equals(text(suppliedClaims.get("iss")))
                && audienceContains(suppliedClaims.get("aud"), "strategy-v5")
                && fresh;

        boolean repositoryPrivate = repositoryBody.path("private").asBoolean(false);
        String visibility = text(body.get("repository_visibility"));
        if (visibility.isBlank()) visibility = repositoryPrivate ? "PRIVATE" : "PUBLIC";
        visibility = visibility.toUpperCase(Locale.ROOT);
        boolean visibilityVerified = isTrue(body.get("repository_visibility_verified"))
                && Set.of("PUBLIC", "PRIVATE").contains(visibility)
                && ("PRIVATE".equals(visibility) == repositoryPrivate);
        String branchHead = firstNonBlank(evidenceBranchHeadSha256,
                text(body.get("evidence_branch_head_sha256")),
                textAt(body, "branch_head", "commit", "sha"),
                text(body.get("branch_head_sha256")));
        String branchHeadHash = branchHead == null ? null
                : JsonHashes.isSha256(branchHead) ? branchHead : JsonHashes.sha256(branchHead);
        ObjectNode normalizedClaims = suppliedClaims == null ? null
                : normalizeOidcClaims(suppliedClaims, ownerId, oidcSubject);
        ObjectNode auditorSummary = normalizeAuditorProof(body.get("settings_auditor_installation"),
                tokenKind == null ? "PAT" : tokenKind);
        boolean auditorExact = exactAuditorProof(
                auditorSummary, tokenKind, repository, repositoryIdText, ownerId);
        boolean branchAppPolicy = restrictions.path("apps_verified").asBoolean(false)
                && !restrictions.path("apps").isEmpty()
                && restrictions.path("users").isEmpty()
                && restrictions.path("teams").isEmpty();
        boolean legacyPolicy = branchSummary.path("api_status").asInt() == 200
                && branchSummary.path("enforce_admins").asBoolean(false)
                && branchSummary.path("required_pull_request_reviews").asBoolean(false)
                && branchSummary.path("required_status_checks").asBoolean(false)
                && !branchSummary.path("allow_force_pushes").asBoolean(false)
                && !branchSummary.path("allow_deletions").asBoolean(false)
                && branchAppPolicy;
        boolean rulesetPolicy = rulesetsSummary.path("api_status").asInt() == 200
                && writerAppValid
                && rulesetsSummary.path("evidence_writer_app_id").asLong(Long.MIN_VALUE)
                        == evidenceWriterAppId
                && rulesetsSummary.path("evidence_writer_credential_configured").asBoolean(false)
                && rulesetsSummary.path("actions_bypass_app_ids").isEmpty()
                && rulesetsSummary.path("protected_ref_matches").asBoolean(false)
                && rulesetsSummary.path("bypass_verified").asBoolean(false)
                && rulesetsSummary.path("actions_only_bypass_verified").asBoolean(false)
                && rulesetsSummary.path("immutable_policy_verified").asBoolean(false)
                && rulesetsSummary.path("writer_gate_policy_verified").asBoolean(false)
                && rulesetsSummary.path("layered_policy_verified").asBoolean(false)
                && mainPolicyVerified
                && rulesetsSummary.path("enforcement_verified").asBoolean(false)
                && rulesetsSummary.path("rules_verified").asBoolean(false);
        boolean branchVerified = DEFAULT_EVIDENCE_BRANCH.equals(evidenceBranch)
                && branchHeadHash != null
                && (legacyPolicy || rulesetPolicy);
        boolean environmentVerified = number(environment.get("api_status"), status) == 200
                && isFalse(environment.get("can_admins_bypass"))
                && environmentReviewSafe
                && environmentSummary.path("protected_branches").asBoolean(false)
                && !environmentSummary.path("custom_branch_policies").asBoolean(false);
        boolean verified = visibilityVerified
                && !repository.isBlank()
                && repositoryIdText != null
                && writerAppValid
                && branchVerified
                && environmentVerified
                && writerSummary.path("verified").asBoolean(false)
                && writerSecretSummary.path("verified").asBoolean(false)
                && oidcVerified
                && actionsPermissionsSummary.path("verified").asBoolean(false)
                && actionsSecretSummary.path("verified").asBoolean(false)
                && identitySummary.path("verified").asBoolean(false)
                && settingsSecretSummary.path("verified").asBoolean(false)
                && auditorExact;

        String failureEndpoint = firstText(response.get("failure_endpoint"), response.get("failureEndpoint"));
        String blockedReason = verified ? null
                : !failureEndpoint.isBlank()
                        ? "GITHUB_API_ENDPOINT_FAILED:" + failureEndpoint + ":" + status
                        : status == 403
                                ? "GitHub API returned 403; external protection is not proven for this repository plan"
                                : "GitHub branch/environment/OIDC/ruleset/actions-permissions/actions-secret/settings-token settings evidence is incomplete";
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", "github-deployment-settings-capture/1");
        value.put("version", 1);
        value.put("captured_at", iso(capturedAt));
        value.put("repository", repository);
        setNullable(value, "repository_id", repositoryIdLong != null
                ? JsonHashes.mapper().getNodeFactory().numberNode(repositoryIdLong)
                : repositoryIdText == null ? null
                        : JsonHashes.mapper().getNodeFactory().textNode(repositoryIdText));
        value.put("repository_private", repositoryPrivate);
        value.put("repository_visibility", visibility);
        value.put("repository_visibility_verified", visibilityVerified);
        ObjectNode apiResponse = value.putObject("api_response");
        apiResponse.put("status", Math.max(0, status));
        apiResponse.put("body_sha256", JsonHashes.canonicalSha256(body));
        apiResponse.put("provider", "github-api");
        putNullable(value, "evidence_branch", emptyToNull(evidenceBranch));
        putNullable(value, "evidence_branch_head_sha256", branchHeadHash);
        putNullable(value, "oidc_subject", emptyToNull(oidcSubject));
        if (normalizedClaims == null) value.putNull("oidc_claims");
        else value.set("oidc_claims", normalizedClaims);
        value.put("oidc_signature_verified", cryptographic);
        branchSummary.put("verified", branchVerified);
        value.set("branch_protection", branchSummary);
        value.set("rulesets", rulesetsSummary);
        environmentSummary.put("verified", environmentVerified);
        value.set("environment_protection", environmentSummary);
        value.set("writer_environment_protection", writerSummary);
        value.set("actions_permissions", actionsPermissionsSummary);
        value.set("actions_secret", actionsSecretSummary);
        value.set("evidence_writer_secret", writerSecretSummary);
        value.set("settings_token_secret", settingsSecretSummary);
        value.set("settings_token_identity", identitySummary);
        value.set("settings_auditor_installation", auditorSummary);
        value.put("oidc_subject_restricted", oidcVerified);
        value.put("verified", verified);
        putNullable(value, "blocked_reason", blockedReason);
        value.put("content_sha256", JsonHashes.ownHash(value));
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
        return value;
    }

    private static InstalledApp parseInstalledApp(ApiResponse response, String tokenKind) {
        ObjectNode body = asObject(response.body());
        ObjectNode nested = asObject(body.get("app"));
        Long id = nullableLong(body.get("app_id"));
        if (id == null) id = nullableLong(nested.get("id"));
        String slug = textOrNull(body.get("app_slug"));
        if (slug == null) slug = textOrNull(nested.get("slug"));
        boolean verified = "APP".equals(tokenKind)
                && response.status() == 200
                && id != null && id == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(slug);
        return new InstalledApp(response.status(), id, slug, verified);
    }

    private static ObjectNode auditorProofBase(String tokenKind, boolean configured) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("token_kind", tokenKind);
        if (configured) {
            value.put("expected_app_id", SETTINGS_AUDITOR_APP_ID);
            value.put("expected_installation_id", SETTINGS_AUDITOR_INSTALLATION_ID);
        } else {
            value.putNull("expected_app_id");
            value.putNull("expected_installation_id");
        }
        value.put("expected_app_slug", SETTINGS_AUDITOR_APP_SLUG);
        value.put("app_endpoint_status", 0);
        value.put("app_endpoint_body_sha256",
                JsonHashes.canonicalSha256(JsonHashes.mapper().createObjectNode()));
        value.put("installation_endpoint_status", 0);
        value.put("installation_endpoint_body_sha256",
                JsonHashes.canonicalSha256(JsonHashes.mapper().createObjectNode()));
        value.put("repositories_endpoint_status", 0);
        value.put("repositories_endpoint_body_sha256",
                JsonHashes.canonicalSha256(JsonHashes.mapper().createObjectNode()));
        value.putNull("app_id");
        value.putNull("app_slug");
        value.putNull("installation_id");
        value.putNull("repository_selection");
        value.putNull("account");
        value.putNull("permissions");
        value.putNull("installation_permissions");
        value.putNull("events");
        value.putNull("installation_events");
        value.put("accessible_repository_count", 0);
        value.putNull("accessible_repository");
        value.put("verified", false);
        return value;
    }

    private static ObjectNode buildAuditorProof(
            String tokenKind,
            boolean configured,
            String repository,
            ApiResponse repositoryResponse,
            InstalledApp installedApp,
            ApiResponse appMetadata,
            ApiResponse installationMetadata,
            ApiResponse accessibleRepositories) {
        ObjectNode proof = auditorProofBase(tokenKind, configured);
        ObjectNode repositoryBody = asObject(repositoryResponse.body());
        ObjectNode appBody = asObject(appMetadata.body());
        ObjectNode installationBody = asObject(installationMetadata.body());
        ObjectNode repositoriesBody = asObject(accessibleRepositories.body());
        List<JsonNode> repositoryRows = rows(repositoriesBody.get("repositories"), null);
        JsonNode repositoryRow = repositoryRows.isEmpty() ? null : repositoryRows.get(0);
        ObjectNode account = asObject(installationBody.get("account"));
        Long ownerId = nullableLong(at(repositoryBody, "owner", "id"));
        if (ownerId == null) ownerId = nullableLong(repositoryBody.get("owner_id"));
        String ownerLogin = repository.split("/", -1)[0];
        String ownerType = textOrNull(at(repositoryBody, "owner", "type"));
        String repositoryName = firstText(repositoryBody.get("name"),
                JsonHashes.mapper().getNodeFactory().textNode(repository.split("/", -1)[1]));
        boolean exactAccount = Objects.equals(nullableLong(account.get("id")), ownerId)
                && ownerLogin.equals(text(account.get("login")))
                && (ownerType == null || ownerType.equals(text(account.get("type"))));
        boolean exactRepository = repositoryRow != null
                && Objects.equals(nullableLong(repositoryRow.get("id")), nullableLong(repositoryBody.get("id")))
                && repository.equals(text(repositoryRow.get("full_name")))
                && repositoryName.equals(text(repositoryRow.get("name")))
                && Objects.equals(nullableLong(at(repositoryRow, "owner", "id")), ownerId)
                && ownerLogin.equals(text(at(repositoryRow, "owner", "login")));
        proof.put("app_endpoint_status", appMetadata.status());
        proof.put("app_endpoint_body_sha256", JsonHashes.canonicalSha256(appMetadata.body()));
        proof.put("installation_endpoint_status", installationMetadata.status());
        proof.put("installation_endpoint_body_sha256",
                JsonHashes.canonicalSha256(installationMetadata.body()));
        proof.put("repositories_endpoint_status", accessibleRepositories.status());
        proof.put("repositories_endpoint_body_sha256",
                JsonHashes.canonicalSha256(accessibleRepositories.body()));
        // The public schema pins these identities when token_kind=APP. The response hashes retain
        // the raw observed bytes; a drifted value is represented by verified=false, never by an
        // artifact that cannot itself pass the evidence schema.
        putNullable(proof, "app_id", configured
                ? SETTINGS_AUDITOR_APP_ID : nullableLong(appBody.get("id")));
        putNullable(proof, "app_slug", textOrNull(appBody.get("slug")));
        putNullable(proof, "installation_id", configured
                ? SETTINGS_AUDITOR_INSTALLATION_ID : nullableLong(installationBody.get("id")));
        putNullable(proof, "repository_selection", textOrNull(installationBody.get("repository_selection")));
        if (account.hasNonNull("id")) {
            proof.set("account", object(
                    "id", nullableLong(account.get("id")),
                    "login", text(account.get("login")),
                    "type", text(account.get("type"))));
        }
        proof.set("permissions", appBody.path("permissions").isObject()
                ? appBody.path("permissions").deepCopy() : nullNode());
        proof.set("installation_permissions", installationBody.path("permissions").isObject()
                ? installationBody.path("permissions").deepCopy() : nullNode());
        proof.set("events", appBody.path("events").isArray()
                ? appBody.path("events").deepCopy() : nullNode());
        proof.set("installation_events", installationBody.path("events").isArray()
                ? installationBody.path("events").deepCopy() : nullNode());
        long accessibleCount = number(repositoriesBody.get("total_count"), 0);
        if (accessibleCount == 0) accessibleCount = repositoryRows.size();
        proof.put("accessible_repository_count", accessibleCount);
        if (exactRepository) {
            proof.set("accessible_repository", object(
                    "id", nullableLong(repositoryRow.get("id")),
                    "full_name", text(repositoryRow.get("full_name"))));
        } else if (repositoryRow != null
                && (repositoryRow.hasNonNull("id") || repositoryRow.hasNonNull("full_name"))) {
            proof.set("accessible_repository", object(
                    "id", nullableLong(repositoryRow.get("id")),
                    "full_name", text(repositoryRow.get("full_name"))));
        }
        boolean verified = installedApp.verified()
                && appMetadata.status() == 200
                && installationMetadata.status() == 200
                && accessibleRepositories.status() == 200
                && number(appBody.get("id"), Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(text(appBody.get("slug")))
                && exactPermissions(appBody.get("permissions"))
                && emptyArray(appBody.get("events"))
                && number(installationBody.get("id"), Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && number(installationBody.get("app_id"), Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(text(installationBody.get("app_slug")))
                && "selected".equals(text(installationBody.get("repository_selection")))
                && exactPermissions(installationBody.get("permissions"))
                && emptyArray(installationBody.get("events"))
                && exactAccount
                && number(repositoriesBody.get("total_count"), Long.MIN_VALUE) == 1
                && repositoryRows.size() == 1
                && exactRepository;
        proof.put("verified", verified);
        return proof;
    }

    private static String auditorAppJwt(long appId, String privateKeyPem, Instant now) {
        try {
            ObjectNode header = object("alg", "RS256", "typ", "JWT");
            ObjectNode payload = object(
                    "iat", now.getEpochSecond() - 60,
                    "exp", now.getEpochSecond() + 540,
                    "iss", appId);
            String input = URL_ENCODER.encodeToString(JsonHashes.mapper().writeValueAsBytes(header))
                    + "." + URL_ENCODER.encodeToString(JsonHashes.mapper().writeValueAsBytes(payload));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(parseRsaPrivateKey(privateKeyPem));
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + URL_ENCODER.encodeToString(signer.sign());
        } catch (Exception failure) {
            throw new IllegalArgumentException("auditor App private key is invalid", failure);
        }
    }

    private static PrivateKey parseRsaPrivateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static OidcIdentity requestOidcIdentity(Env env, Transport transport) {
        String requestUrl = env.get("ACTIONS_ID_TOKEN_REQUEST_URL");
        String requestToken = env.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN");
        if (requestUrl.isBlank() || requestToken.isBlank()) return null;
        try {
            String separator = requestUrl.contains("?") ? "&" : "?";
            ApiResponse tokenResponse = transport.url(
                    URI.create(requestUrl + separator + "audience=strategy-v5"),
                    Map.of("Authorization", "bearer " + requestToken));
            String jwt = text(asObject(tokenResponse.body()).get("value"));
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) return null;
            JsonNode parsed = JsonHashes.mapper().readTree(URL_DECODER.decode(parts[1]));
            if (!parsed.isObject()) return null;
            return new OidcIdentity((ObjectNode) parsed,
                    verifyOidcJwt(jwt, transport));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean verifyOidcJwt(String jwt, Transport transport) {
        try {
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) return false;
            JsonNode header = JsonHashes.mapper().readTree(URL_DECODER.decode(parts[0]));
            if (!"RS256".equals(text(header.get("alg"))) || !header.path("kid").isTextual()) {
                return false;
            }
            ApiResponse response = transport.url(URI.create(OIDC_JWKS_URL), Map.of());
            for (JsonNode jwk : rows(response.body(), "keys")) {
                if (!text(header.get("kid")).equals(text(jwk.get("kid")))
                        || !"RSA".equals(text(jwk.get("kty")))) continue;
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                        new RSAPublicKeySpec(
                                new BigInteger(1, URL_DECODER.decode(text(jwk.get("n")))),
                                new BigInteger(1, URL_DECODER.decode(text(jwk.get("e"))))));
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key);
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                return verifier.verify(URL_DECODER.decode(parts[2]));
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ObjectNode normalizeWriterEnvironment(ObjectNode source) {
        long reviewerCount = number(source.get("reviewer_count"), 0);
        long protectionRuleCount = number(source.get("protection_rule_count"), 0);
        long requiredReviewerCount = source.has("required_reviewer_rule_count")
                ? number(source.get("required_reviewer_rule_count"), 0)
                : reviewerCount > 0 ? 1 : protectionRuleCount == 0 ? 0 : 1;
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", number(source.get("api_status"), 0));
        value.put("reviewer_count", reviewerCount);
        value.put("required_reviewer_rule_count", requiredReviewerCount);
        value.put("protection_rule_count", protectionRuleCount);
        value.put("can_admins_bypass", isTrue(source.get("can_admins_bypass")));
        value.put("protected_branches", isTrue(source.get("protected_branches")));
        value.put("custom_branch_policies", isTrue(source.get("custom_branch_policies")));
        value.put("prevent_self_review", isTrue(source.get("prevent_self_review")));
        value.put("verified", isTrue(source.get("verified"))
                && environmentReviewSafe(value)
                && isFalse(source.get("can_admins_bypass"))
                && isTrue(source.get("protected_branches"))
                && !isTrue(source.get("custom_branch_policies")));
        return value;
    }

    private static ObjectNode normalizeActionsPermissions(ObjectNode source) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", number(source.get("api_status"), 0));
        value.put("selected_api_status", number(source.get("selected_api_status"), 0));
        value.put("workflow_api_status", number(source.get("workflow_api_status"), 0));
        value.put("allowed_actions", text(source.get("allowed_actions")));
        value.put("sha_pinning_required", isTrue(source.get("sha_pinning_required")));
        value.put("github_owned_allowed", isTrue(source.get("github_owned_allowed")));
        value.put("verified_allowed", isTrue(source.get("verified_allowed")));
        putStringArray(value.putArray("patterns_allowed"),
                strings(source.get("patterns_allowed")).stream().sorted().toList());
        value.put("default_workflow_permissions", text(source.get("default_workflow_permissions")));
        value.put("can_approve_pull_request_reviews",
                isTrue(source.get("can_approve_pull_request_reviews")));
        value.put("verified", isTrue(source.get("verified"))
                && isTrue(source.get("sha_pinning_required")));
        return value;
    }

    private static ObjectNode normalizeSecret(ObjectNode source, String fallbackName, boolean extraGate) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("name", firstNonBlank(text(source.get("name")), fallbackName));
        value.put("environment_status", number(source.get("environment_status"), 0));
        value.put("environment_body_sha256", deploymentHash(source, "environment_body_sha256",
                "environment_body"));
        value.put("repository_status", number(source.get("repository_status"), 0));
        value.put("repository_body_sha256", deploymentHash(source, "repository_body_sha256",
                "repository_body"));
        value.put("organization_status", number(source.get("organization_status"), 0));
        value.put("organization_body_sha256", deploymentHash(source, "organization_body_sha256",
                "organization_body"));
        value.put("verified", isTrue(source.get("verified")) && extraGate);
        return value;
    }

    private static ObjectNode normalizeSettingsIdentity(ObjectNode source, ObjectNode secret) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("api_status", number(first(source.get("api_status"), source.get("status")), 0));
        putNullable(value, "app_id", nullableLong(source.get("app_id")));
        putNullable(value, "user_id", nullableLong(source.get("user_id")));
        putNullable(value, "login", textOrNull(source.get("login")));
        putNullable(value, "token_kind", textOrNull(source.get("token_kind")));
        putNullable(value, "expected_user_id", nullableLong(source.get("expected_user_id")));
        putNullable(value, "expected_login", textOrNull(source.get("expected_login")));
        putNullable(value, "secret_name", textOrNull(source.get("secret_name")));
        value.put("secret_environment_status", number(source.get("secret_environment_status"), 0));
        value.put("secret_environment_body_sha256", deploymentHash(
                source, "secret_environment_body_sha256", "secret_environment_body"));
        value.put("secret_repository_status", number(source.get("secret_repository_status"), 0));
        value.put("secret_repository_body_sha256", deploymentHash(
                source, "secret_repository_body_sha256", "secret_repository_body"));
        value.put("secret_organization_status", number(source.get("secret_organization_status"), 0));
        value.put("secret_organization_body_sha256", deploymentHash(
                source, "secret_organization_body_sha256", "secret_organization_body"));
        value.put("body_sha256", deploymentHash(source, "body_sha256", "body"));
        boolean secretMatches = Objects.equals(textOrNull(source.get("secret_name")),
                    textOrNull(secret.get("name")))
                && number(source.get("secret_environment_status"), 0)
                    == number(secret.get("environment_status"), 0)
                && Objects.equals(text(source.get("secret_environment_body_sha256")),
                    text(secret.get("environment_body_sha256")))
                && number(source.get("secret_repository_status"), 0)
                    == number(secret.get("repository_status"), 0)
                && Objects.equals(text(source.get("secret_repository_body_sha256")),
                    text(secret.get("repository_body_sha256")))
                && number(source.get("secret_organization_status"), 0)
                    == number(secret.get("organization_status"), 0)
                && Objects.equals(text(source.get("secret_organization_body_sha256")),
                    text(secret.get("organization_body_sha256")));
        boolean patExact = !"PAT".equals(text(source.get("token_kind")))
                || (source.hasNonNull("expected_user_id")
                        && Objects.equals(nullableLong(source.get("user_id")),
                            nullableLong(source.get("expected_user_id")))
                        && !text(source.get("expected_login")).isBlank()
                        && Objects.equals(text(source.get("login")), text(source.get("expected_login"))));
        value.put("verified", isTrue(source.get("verified")) && secretMatches && patExact);
        return value;
    }

    private static ObjectNode normalizeAuditorProof(JsonNode input, String tokenKind) {
        ObjectNode source = asObject(input);
        ObjectNode value = auditorProofBase(tokenKind == null ? "PAT" : tokenKind, false);
        value.put("token_kind", tokenKind == null ? "PAT" : tokenKind);
        putNullable(value, "expected_app_id", nullableLong(source.get("expected_app_id")));
        putNullable(value, "expected_installation_id", nullableLong(source.get("expected_installation_id")));
        value.put("expected_app_slug", firstNonBlank(text(source.get("expected_app_slug")),
                SETTINGS_AUDITOR_APP_SLUG));
        value.put("app_endpoint_status", number(source.get("app_endpoint_status"), 0));
        value.put("app_endpoint_body_sha256", deploymentHash(
                source, "app_endpoint_body_sha256", "app_endpoint_body"));
        value.put("installation_endpoint_status", number(source.get("installation_endpoint_status"), 0));
        value.put("installation_endpoint_body_sha256", deploymentHash(
                source, "installation_endpoint_body_sha256", "installation_endpoint_body"));
        value.put("repositories_endpoint_status", number(source.get("repositories_endpoint_status"), 0));
        value.put("repositories_endpoint_body_sha256", deploymentHash(
                source, "repositories_endpoint_body_sha256", "repositories_endpoint_body"));
        putNullable(value, "app_id", nullableLong(source.get("app_id")));
        putNullable(value, "app_slug", textOrNull(source.get("app_slug")));
        putNullable(value, "installation_id", nullableLong(source.get("installation_id")));
        putNullable(value, "repository_selection", textOrNull(source.get("repository_selection")));
        if (source.path("account").isObject()) {
            JsonNode account = source.path("account");
            value.set("account", object(
                    "id", nullableLong(account.get("id")),
                    "login", text(account.get("login")),
                    "type", text(account.get("type"))));
        }
        value.set("permissions", source.path("permissions").isObject()
                ? source.path("permissions").deepCopy() : nullNode());
        value.set("installation_permissions", source.path("installation_permissions").isObject()
                ? source.path("installation_permissions").deepCopy() : nullNode());
        value.set("events", source.path("events").isArray()
                ? source.path("events").deepCopy() : nullNode());
        value.set("installation_events", source.path("installation_events").isArray()
                ? source.path("installation_events").deepCopy() : nullNode());
        value.put("accessible_repository_count", number(source.get("accessible_repository_count"), 0));
        if (source.path("accessible_repository").isObject()) {
            JsonNode repository = source.path("accessible_repository");
            value.set("accessible_repository", object(
                    "id", nullableLong(repository.get("id")),
                    "full_name", text(repository.get("full_name"))));
        }
        value.put("verified", isTrue(source.get("verified")));
        return value;
    }

    private static boolean exactAuditorProof(
            ObjectNode proof,
            String tokenKind,
            String repository,
            String repositoryId,
            String ownerId) {
        if (!"APP".equals(tokenKind)) return true;
        JsonNode account = proof.get("account");
        JsonNode accessible = proof.get("accessible_repository");
        String ownerLogin = repository.split("/", -1)[0];
        return proof.path("verified").asBoolean(false)
                && proof.path("expected_app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && proof.path("expected_installation_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(proof.path("expected_app_slug").asText())
                && proof.path("app_endpoint_status").asInt() == 200
                && proof.path("installation_endpoint_status").asInt() == 200
                && proof.path("repositories_endpoint_status").asInt() == 200
                && proof.path("app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(proof.path("app_slug").asText())
                && proof.path("installation_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && "selected".equals(proof.path("repository_selection").asText())
                && exactPermissions(proof.get("permissions"))
                && exactPermissions(proof.get("installation_permissions"))
                && emptyArray(proof.get("events"))
                && emptyArray(proof.get("installation_events"))
                && (ownerId == null || Objects.equals(nullableLong(at(account, "id")), strictLong(ownerId)))
                && ownerLogin.equals(text(at(account, "login")))
                && proof.path("accessible_repository_count").asLong() == 1
                && Objects.equals(nullableLong(at(accessible, "id")), strictLong(repositoryId))
                && repository.equals(text(at(accessible, "full_name")));
    }

    private static boolean settingsAuditorSecretExact(ObjectNode value, String tokenKind) {
        return !"APP".equals(tokenKind)
                || ("V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM".equals(text(value.get("name")))
                        && number(value.get("environment_status"), 0) == 200
                        && number(value.get("repository_status"), 0) == 404
                        && number(value.get("organization_status"), 0) == 404
                        && isTrue(value.get("verified")));
    }

    private static ObjectNode normalizeOidcClaims(
            ObjectNode claims, String ownerId, String oidcSubject) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        setNullable(value, "repository_id", claims.get("repository_id"));
        JsonNode repositoryOwner = claims.get("repository_owner_id");
        if (repositoryOwner == null || repositoryOwner.isNull()) {
            putNullable(value, "repository_owner_id", ownerId);
        } else value.set("repository_owner_id", repositoryOwner.deepCopy());
        value.put("environment", text(claims.get("environment")));
        value.put("workflow_ref", text(claims.get("workflow_ref")));
        value.put("workflow_sha", text(claims.get("workflow_sha")));
        putNullable(value, "sub", firstNonBlank(text(claims.get("sub")), oidcSubject));
        setNullable(value, "aud", claims.get("aud"));
        value.put("iss", text(claims.get("iss")));
        value.put("iat", number(claims.get("iat"), 0));
        value.put("exp", number(claims.get("exp"), 0));
        setNullable(value, "run_id", claims.get("run_id"));
        putNullable(value, "run_attempt", strictPositiveLong(claims.get("run_attempt")));
        return value;
    }

    private static boolean environmentReviewSafe(ObjectNode value) {
        Long reviewers = nonNegativeLong(value.get("reviewer_count"));
        Long rules = nonNegativeLong(value.get("protection_rule_count"));
        if (reviewers == null || rules == null) return false;
        Long required = value.has("required_reviewer_rule_count")
                ? nonNegativeLong(value.get("required_reviewer_rule_count"))
                : reviewers > 0 ? 1L : rules == 0 ? 0L : null;
        if (required == null || required > rules || !required.equals(reviewers)) return false;
        return required == 0 || isTrue(value.get("prevent_self_review"));
    }

    private static String deploymentHash(ObjectNode source, String hashField, String bodyField) {
        String candidate = text(source.get(hashField));
        return JsonHashes.isSha256(candidate) ? candidate
                : JsonHashes.canonicalSha256(source.has(bodyField)
                        ? source.get(bodyField) : JsonHashes.mapper().createObjectNode());
    }

    private static void endpoint(ObjectNode endpoints, String name, ApiResponse response) {
        endpoint(endpoints, name, response.status(), JsonHashes.canonicalSha256(response.body()));
    }

    private static void endpoint(ObjectNode endpoints, String name, int status, String hash) {
        ObjectNode value = endpoints.putObject(name);
        value.put("status", Math.max(0, status));
        value.put("body_sha256", hash);
    }

    private static void addBlocker(ArrayNode blockers, String blocker) {
        if (blocker != null && !blocker.isBlank()) blockers.add(blocker);
    }

    private static boolean exactPermissions(JsonNode permissions) {
        if (permissions == null || !permissions.isObject()
                || permissions.size() != AUDITOR_PERMISSIONS.size()) return false;
        return AUDITOR_PERMISSIONS.entrySet().stream().allMatch(entry ->
                entry.getValue().equals(text(permissions.get(entry.getKey()))));
    }

    private static boolean emptyArray(JsonNode value) {
        return value != null && value.isArray() && value.isEmpty();
    }

    private static boolean isReviewerRule(JsonNode rule) {
        return "required_reviewers".equalsIgnoreCase(text(rule == null ? null : rule.get("type")));
    }

    private static boolean hasConcreteReviewers(JsonNode rule) {
        return isReviewerRule(rule)
                && (!rows(rule.get("reviewers"), null).isEmpty()
                        || !rows(at(rule, "parameters", "reviewers"), null).isEmpty());
    }

    private static boolean hasSelfReviewProtection(JsonNode rule) {
        return isTrue(rule == null ? null : rule.get("prevent_self_review"))
                || isTrue(at(rule, "parameters", "prevent_self_review"));
    }

    private static boolean refMatches(String ref, String branch) {
        return Objects.equals(ref, branch) || Objects.equals(ref, "refs/heads/" + branch);
    }

    private static boolean audienceContains(JsonNode value, String expected) {
        if (value == null || value.isNull()) return false;
        if (value.isArray()) {
            for (JsonNode row : value) if (expected.equals(row.asText())) return true;
            return false;
        }
        return expected.equals(value.asText());
    }

    private static boolean optionalEquals(JsonNode actual, String expected) {
        return expected == null || expected.isBlank() || expected.equals(text(actual));
    }

    private static boolean optionalNumberEquals(JsonNode actual, String expected) {
        if (expected == null || expected.isBlank()) return true;
        Long parsed = strictLong(expected);
        return parsed != null && number(actual, Long.MIN_VALUE) == parsed;
    }

    private static ObjectNode object(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("object entries must be pairs");
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        for (int index = 0; index < entries.length; index += 2) {
            putNullable(value, String.valueOf(entries[index]), entries[index + 1]);
        }
        return value;
    }

    private static JsonNode nullNode() {
        return JsonHashes.mapper().getNodeFactory().nullNode();
    }

    private static ObjectNode asObject(JsonNode value) {
        return value != null && value.isObject()
                ? (ObjectNode) value.deepCopy() : JsonHashes.mapper().createObjectNode();
    }

    private static List<JsonNode> rows(JsonNode value, String nestedField) {
        JsonNode source = value;
        if (nestedField != null && source != null && source.isObject()) source = source.get(nestedField);
        if (source == null || !source.isArray()) return List.of();
        List<JsonNode> values = new ArrayList<>();
        source.forEach(row -> values.add(row));
        return List.copyOf(values);
    }

    private static List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(row -> result.add(row.isTextual() ? row.asText() : row.asText()));
        return List.copyOf(result);
    }

    private static List<Long> longs(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<Long> result = new ArrayList<>();
        for (JsonNode row : value) {
            Long parsed = nullableLong(row);
            if (parsed != null) result.add(parsed);
        }
        result.sort(Long::compareTo);
        return List.copyOf(result);
    }

    private static JsonNode at(JsonNode value, String... path) {
        JsonNode cursor = value;
        for (String segment : path) {
            if (cursor == null || !cursor.isObject()) return null;
            cursor = cursor.get(segment);
        }
        return cursor;
    }

    private static JsonNode first(JsonNode... values) {
        for (JsonNode value : values) if (value != null && !value.isMissingNode() && !value.isNull()) return value;
        return null;
    }

    private static String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            String candidate = text(value);
            if (!candidate.isBlank()) return candidate;
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String textAt(JsonNode value, String... path) {
        return textOrNull(at(value, path));
    }

    private static String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.asText() : value.asText();
    }

    private static String textOrNull(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        return value.isTextual() ? value.asText() : value.asText();
    }

    private static String upperOrNull(JsonNode value) {
        String text = textOrNull(value);
        return text == null || text.isBlank() ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static long number(JsonNode value, long fallback) {
        Long parsed = nullableLong(value);
        return parsed == null ? fallback : parsed;
    }

    private static Long nullableLong(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.isBoolean()) return null;
        try {
            if (value.isIntegralNumber()) {
                BigInteger integer = value.bigIntegerValue();
                if (integer.abs().compareTo(BigInteger.valueOf(9_007_199_254_740_991L)) > 0) return null;
                return integer.longValueExact();
            }
            if (value.isFloatingPointNumber()) {
                double parsed = value.asDouble();
                if (!Double.isFinite(parsed) || parsed != Math.rint(parsed)
                        || Math.abs(parsed) > 9_007_199_254_740_991d) return null;
                return (long) parsed;
            }
            if (value.isTextual()) return strictLong(value.asText());
        } catch (ArithmeticException ignored) {
            return null;
        }
        return null;
    }

    private static Long strictLong(String value) {
        if (value == null || value.isBlank() || !value.matches("-?\\d+")) return null;
        try {
            BigInteger integer = new BigInteger(value);
            if (integer.abs().compareTo(BigInteger.valueOf(9_007_199_254_740_991L)) > 0) return null;
            return integer.longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    private static Long strictPositiveLong(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual() && !value.asText().matches("[1-9]\\d*")) return null;
        Long parsed = nullableLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static Long nonNegativeLong(JsonNode value) {
        Long parsed = nullableLong(value);
        return parsed != null && parsed >= 0 ? parsed : null;
    }

    private static boolean positiveDecimal(String value) {
        return value != null && value.matches("\\d+") && strictLong(value) != null;
    }

    private static boolean integral(JsonNode value) {
        return nullableLong(value) != null;
    }

    private static boolean isTrue(JsonNode value) {
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static boolean isFalse(JsonNode value) {
        return value != null && value.isBoolean() && !value.booleanValue();
    }

    private static boolean enabled(JsonNode value) {
        return isTrue(value) || isTrue(at(value, "enabled"));
    }

    private static boolean present(ObjectNode value, String field) {
        return value.has(field) && !value.get(field).isNull();
    }

    private static void putStringArray(ArrayNode destination, List<String> values) {
        values.forEach(destination::add);
    }

    private static void putLongArray(ArrayNode destination, List<Long> values) {
        values.forEach(destination::add);
    }

    private static void putNullable(ObjectNode destination, String field, Object value) {
        if (value == null) {
            destination.putNull(field);
        } else if (value instanceof JsonNode node) {
            destination.set(field, node.deepCopy());
        } else if (value instanceof String text) {
            destination.put(field, text);
        } else if (value instanceof Integer number) {
            destination.put(field, number);
        } else if (value instanceof Long number) {
            destination.put(field, number);
        } else if (value instanceof Boolean bool) {
            destination.put(field, bool);
        } else if (value instanceof Double number) {
            destination.put(field, number);
        } else {
            destination.set(field, JsonHashes.mapper().valueToTree(value));
        }
    }

    private static void setNullable(ObjectNode destination, String field, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) destination.putNull(field);
        else destination.set(field, value.deepCopy());
    }

    private static void copyBoolean(ObjectNode destination, ObjectNode source, String... fields) {
        for (String field : fields) destination.put(field, isTrue(source.get(field)));
    }

    private static void copyFields(
            ObjectNode destination, ObjectNode source, Map<String, String> destinationToSource) {
        for (Map.Entry<String, String> entry : destinationToSource.entrySet()) {
            setNullable(destination, entry.getKey(), source.get(entry.getValue()));
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String iso(Instant instant) {
        return ISO_MILLIS.format(instant);
    }

    private static void writeJson(Path path, ObjectNode value) {
        try {
            byte[] bytes = (JsonHashes.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot write GitHub settings evidence: " + path, failure);
        }
    }

    /** Writes the two command artifacts with the legacy environment-variable defaults. */
    public static void writeArtifacts(Result result, Map<String, String> environment) {
        Env env = new Env(environment);
        Path receiptPath = Path.of(env.or(
                "V5_SETTINGS_RECEIPT_OUT", "github-settings-api-receipt.json")).toAbsolutePath();
        Path capturePath = Path.of(env.or(
                "V5_SETTINGS_OUT", "github-deployment-settings-capture.json")).toAbsolutePath();
        writeJson(receiptPath, result.receipt());
        writeJson(capturePath, result.capture());
    }

    private record AssemblyInputs(
            Env env,
            String repositoryName,
            String tokenKind,
            String evidenceBranch,
            String declaredVisibility,
            Instant capturedAt,
            ApiResponse repository,
            ApiResponse branch,
            ApiResponse branchHead,
            ApiResponse environment,
            ApiResponse writerEnvironment,
            ApiResponse rulesets,
            List<JsonNode> rawRulesetRows,
            List<Long> rulesetIds,
            List<ApiResponse> rulesetResponses,
            ApiResponse oidcPolicy,
            ApiResponse actionsPermissions,
            ApiResponse selectedPermissions,
            ApiResponse workflowPermissions,
            String actionsSecretName,
            ApiResponse actionsSecret,
            ApiResponse repositorySecret,
            ApiResponse organizationSecret,
            String settingsSecretName,
            ApiResponse settingsSecret,
            ApiResponse settingsRepositorySecret,
            ApiResponse settingsOrganizationSecret,
            String writerSecretName,
            ApiResponse writerSecret,
            ApiResponse writerRepositorySecret,
            ApiResponse writerOrganizationSecret,
            ApiResponse installation,
            InstalledApp installedApp,
            ObjectNode auditorProof,
            ApiResponse settingsIdentity,
            OidcIdentity oidcIdentity) {}

    private static final class Env {
        private final Map<String, String> values;

        private Env(Map<String, String> values) {
            this.values = values == null ? Map.of() : Map.copyOf(values);
        }

        private String get(String key) {
            return Objects.requireNonNullElse(values.get(key), "");
        }

        private String or(String key, String fallback) {
            String value = get(key);
            return value.isEmpty() ? fallback : value;
        }

        private boolean trueValue(String key) {
            return "true".equals(get(key));
        }
    }
}
