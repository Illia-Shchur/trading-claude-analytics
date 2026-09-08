package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic settings-drift contracts for baseline, clear, and fail-closed transitions. */
final class StrategyResearchV5SettingsDriftContractsTest {
    private static final String CAPTURED_AT = "2026-01-01T00:00:00.000Z";

    @Test
    void driftEvidenceEstablishesBaselineThenClearsAgainstTheSameBoundArtifacts() {
        ObjectNode capture = capture();
        ObjectNode api = api(capture);

        ObjectNode baselineOptions = object();
        baselineOptions.set("currentCapture", capture);
        baselineOptions.set("currentApiReceipt", api);
        baselineOptions.put("comparedAt", CAPTURED_AT);
        ObjectNode baseline = StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(baselineOptions);
        assertThat(baseline.path("status").asText()).isEqualTo("BASELINE_ESTABLISHED");
        assertThat(baseline.path("changed_fields").get(0).asText()).isEqualTo("BASELINE_ESTABLISHED");
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(baseline, capture, api)).isTrue();

        ObjectNode clearOptions = baselineOptions.deepCopy();
        clearOptions.set("previousCapture", capture.deepCopy());
        clearOptions.set("previousApiReceipt", api.deepCopy());
        ObjectNode clear = StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(clearOptions);
        assertThat(clear.path("status").asText()).isEqualTo("CLEAR");
        assertThat(clear.path("changed_fields")).isEmpty();
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(clear, capture, api)).isTrue();

        ObjectNode tampered = clear.deepCopy().put("status", "DRIFTED");
        tampered.put("content_sha256", StrategyResearchV5.ownHash(tampered));
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(tampered, capture, api)).isFalse();
    }

    @Test
    void driftEvidenceReportsPolicyChangeAndRejectsPartialOrUnboundBaselines() {
        ObjectNode capture = capture();
        ObjectNode api = api(capture);
        ObjectNode changedApi = api.deepCopy();
        ((ObjectNode) changedApi.path("endpoints").path("repository")).put("status", 503)
                .put("body_sha256", "b".repeat(64));
        changedApi.put("content_sha256", StrategyResearchV5.ownHash(changedApi));

        ObjectNode options = object();
        options.put("comparedAt", CAPTURED_AT);
        options.set("currentCapture", capture);
        options.set("currentApiReceipt", changedApi);
        options.set("previousCapture", capture.deepCopy());
        options.set("previousApiReceipt", api.deepCopy());
        ObjectNode drifted = StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(options);
        assertThat(drifted.path("status").asText()).isEqualTo("DRIFTED");
        assertThat(drifted.path("changed_fields").get(0).asText()).isEqualTo("api_receipt");
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(drifted, capture, changedApi)).isFalse();

        ObjectNode partial = object();
        partial.put("comparedAt", CAPTURED_AT);
        partial.set("currentCapture", capture);
        partial.set("currentApiReceipt", api);
        partial.set("previousCapture", capture.deepCopy());
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(partial))
                .hasMessageContaining("partial GitHub settings baseline");

        ObjectNode invalidCurrent = object();
        invalidCurrent.put("comparedAt", CAPTURED_AT);
        invalidCurrent.set("currentCapture", capture.deepCopy());
        invalidCurrent.set("currentApiReceipt", api);
        ((ObjectNode) invalidCurrent.path("currentCapture")).put("content_sha256", "not-a-digest");
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(invalidCurrent))
                .hasMessageContaining("current GitHub settings capture is not hash-valid");

        ObjectNode otherRepository = capture.deepCopy().put("repository", "other/repo");
        otherRepository.put("content_sha256", StrategyResearchV5.ownHash(otherRepository));
        ObjectNode mismatched = object();
        mismatched.put("comparedAt", CAPTURED_AT);
        mismatched.set("currentCapture", capture);
        mismatched.set("currentApiReceipt", api);
        mismatched.set("previousCapture", otherRepository);
        mismatched.set("previousApiReceipt", api.deepCopy());
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(mismatched))
                .hasMessageContaining("prior GitHub settings baseline is invalid or not repository-bound");
    }

    @Test
    void driftConstructorsAndVerifierRejectSchemaAndBindingMutations() {
        ObjectNode capture = capture();
        ObjectNode api = api(capture);
        ObjectNode current = object();
        current.put("comparedAt", CAPTURED_AT);
        current.set("currentCapture", capture);
        current.set("currentApiReceipt", api);

        ObjectNode wrongApiSchema = api.deepCopy().put("schema", "wrong-api-schema");
        wrongApiSchema.put("content_sha256", StrategyResearchV5.ownHash(wrongApiSchema));
        current.set("currentApiReceipt", wrongApiSchema);
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(current))
                .hasMessageContaining("current GitHub API receipt");

        ObjectNode extraCaptureField = capture.deepCopy().put("unexpected", true);
        extraCaptureField.put("content_sha256", StrategyResearchV5.ownHash(extraCaptureField));
        current.set("currentCapture", extraCaptureField);
        current.set("currentApiReceipt", api);
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(current))
                .hasMessageContaining("current GitHub settings/API evidence schema");

        ObjectNode extraApiField = api.deepCopy().put("unexpected", true);
        extraApiField.put("content_sha256", StrategyResearchV5.ownHash(extraApiField));
        current.set("currentCapture", capture);
        current.set("currentApiReceipt", extraApiField);
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(current))
                .hasMessageContaining("current GitHub settings/API evidence schema");

        ObjectNode previousInvalid = capture.deepCopy().put("unexpected", true);
        previousInvalid.put("content_sha256", StrategyResearchV5.ownHash(previousInvalid));
        current.set("currentApiReceipt", api);
        current.set("previousCapture", previousInvalid);
        current.set("previousApiReceipt", api.deepCopy());
        assertThatThrownBy(() -> StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(current))
                .hasMessageContaining("prior GitHub settings baseline schema");

        ObjectNode baseline = object();
        baseline.put("comparedAt", CAPTURED_AT);
        baseline.set("currentCapture", capture);
        baseline.set("currentApiReceipt", api);
        ObjectNode evidence = StrategyResearchV5.makeGitHubSettingsDriftEvidenceV5(baseline);
        ObjectNode wrongRepository = evidence.deepCopy().put("repository", "other/repo");
        wrongRepository.put("content_sha256", StrategyResearchV5.ownHash(wrongRepository));
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(wrongRepository, capture, api)).isFalse();
        ObjectNode wrongCaptureHash = evidence.deepCopy().put("current_capture_sha256", "a".repeat(64));
        wrongCaptureHash.put("content_sha256", StrategyResearchV5.ownHash(wrongCaptureHash));
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(wrongCaptureHash, capture, api)).isFalse();

        ObjectNode clearWithoutPrevious = evidence.deepCopy().put("status", "CLEAR");
        clearWithoutPrevious.putArray("changed_fields");
        clearWithoutPrevious.put("content_sha256", StrategyResearchV5.ownHash(clearWithoutPrevious));
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(clearWithoutPrevious, capture, api)).isFalse();
        ObjectNode clearWithChanges = evidence.deepCopy().put("status", "CLEAR");
        clearWithChanges.putArray("changed_fields").add("settings_policy");
        clearWithChanges.put("content_sha256", StrategyResearchV5.ownHash(clearWithChanges));
        assertThat(StrategyResearchV5.verifyGitHubSettingsDriftEvidenceV5(clearWithChanges, capture, api)).isFalse();
    }

    private static ObjectNode capture() {
        ObjectNode body = object();
        body.putObject("repository").put("full_name", "owner/repo").put("id", 1).put("private", false)
                .putObject("owner").put("id", 2);
        body.put("repository_visibility", "PUBLIC").put("repository_visibility_verified", true)
                .put("evidence_branch", "strategy-v5-evidence")
                .put("evidence_branch_head_sha256", "f".repeat(64));
        body.putObject("branch_protection");
        body.putObject("environment_protection");
        body.putObject("writer_environment_protection");
        body.putObject("rulesets");
        body.putObject("actions_permissions");
        body.putObject("actions_secret");
        body.putObject("evidence_writer_secret");
        body.putObject("settings_token_secret");
        body.putObject("settings_token_identity").put("token_kind", "PAT")
                .put("secret_name", "V5_GITHUB_SETTINGS_PAT").put("expected_user_id", 123)
                .put("user_id", 123).put("expected_login", "settings-bot").put("login", "settings-bot");
        body.putObject("oidc");
        ObjectNode response = object().put("status", 200).set("body", body);
        return GitHubSettingsCaptureV5.makeDeploymentSettingsCapture(response, null, null, false,
                Instant.parse(CAPTURED_AT), "f".repeat(64), null);
    }

    private static ObjectNode api(ObjectNode capture) {
        ObjectNode api = object().put("schema", "github-settings-api-receipt/1").put("version", 1)
                .put("repository", capture.path("repository").asText()).put("captured_at", CAPTURED_AT)
                .put("evidence_branch", capture.path("evidence_branch").asText())
                .put("repository_visibility", "PUBLIC").put("repository_visibility_verified", true);
        String digest = "a".repeat(64);
        List<String> endpointNames = List.of("repository", "branch_protection", "branch_head", "environment_protection",
                "writer_environment_protection", "rulesets", "ruleset_details", "installation",
                "settings_token_identity", "settings_token_secret", "evidence_writer_secret",
                "evidence_writer_repository_secret", "evidence_writer_organization_secret",
                "oidc_subject_restriction", "actions_permissions", "actions_selected_permissions",
                "actions_workflow_permissions");
        ObjectNode endpointValues = api.putObject("endpoints");
        for (String name : endpointNames) {
            endpointValues.putObject(name).put("status", 200).put("body_sha256", digest);
        }
        api.set("rulesets", capture.path("rulesets").deepCopy());
        api.set("actions_permissions", capture.path("actions_permissions").deepCopy());
        api.set("writer_environment_protection", capture.path("writer_environment_protection").deepCopy());
        api.set("evidence_writer_secret", capture.path("evidence_writer_secret").deepCopy());
        api.set("settings_token_identity", capture.path("settings_token_identity").deepCopy());
        api.set("settings_token_secret", capture.path("settings_token_secret").deepCopy());
        api.put("installation_proof_verified", false).put("oidc_signature_verified", false)
                .put("verified", true).putArray("blockers");
        api.put("content_sha256", StrategyResearchV5.ownHash(api));
        return api;
    }

    private static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }
}
