package com.tradinganalytics.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GitHubCapturePolicyTest {
    @Test
    void reportsTheFirstRequiredNon200Endpoint() {
        Map<String, Object> statuses = all200();
        statuses.put("branch_protection", 403);
        statuses.put("branch_head", 404);

        GitHubCapturePolicy.EndpointFailure failure =
                GitHubCapturePolicy.firstNon200Endpoint(statuses);
        assertThat(failure).isEqualTo(
                new GitHubCapturePolicy.EndpointFailure("branch_protection", 403));
        assertThat(GitHubCapturePolicy.captureFailureReason(failure))
                .isEqualTo("GITHUB_API_ENDPOINT_FAILED:branch_protection:403");
        assertThat(GitHubCapturePolicy.selectCaptureStatus(false, statuses)).isEqualTo(403);
        assertThat(GitHubCapturePolicy.selectCaptureStatus(true, statuses)).isEqualTo(200);
    }

    @Test
    void missingAndNonIntegerStatusesNormalizeToZeroLikeTheJavascriptPolicy() {
        assertThat(GitHubCapturePolicy.firstNon200Endpoint(Map.of()))
                .isEqualTo(new GitHubCapturePolicy.EndpointFailure("repository", 0));
        assertThat(GitHubCapturePolicy.firstNon200Endpoint(
                Map.of("repository", "not-a-number"), List.of("repository")))
                .isEqualTo(new GitHubCapturePolicy.EndpointFailure("repository", 0));
        assertThat(GitHubCapturePolicy.firstNon200Endpoint(
                Map.of("repository", 200.5), List.of("repository")))
                .isEqualTo(new GitHubCapturePolicy.EndpointFailure("repository", 0));
        assertThat(GitHubCapturePolicy.firstNon200Endpoint(
                Map.of("repository", "200"), List.of("repository"))).isNull();
        assertThat(GitHubCapturePolicy.captureFailureReason(null)).isNull();
    }

    @Test
    void allRequiredEndpointsAt200HaveNoFailureButUnverifiedStatusStillFailsClosed() {
        Map<String, Object> statuses = all200();
        assertThat(GitHubCapturePolicy.firstNon200Endpoint(statuses)).isNull();
        assertThat(GitHubCapturePolicy.selectCaptureStatus(false, statuses)).isZero();
        assertThat(GitHubCapturePolicy.selectCaptureStatus(true, null)).isEqualTo(200);
        assertThat(GitHubCapturePolicy.REQUIRED_ENDPOINTS).containsExactly(
                "repository", "branch_protection", "branch_head", "environment_protection",
                "writer_environment_protection", "rulesets", "ruleset_details", "installation",
                "settings_token_identity", "settings_token_secret", "evidence_writer_secret",
                "oidc_subject_restriction", "actions_permissions", "actions_selected_permissions",
                "actions_workflow_permissions");
    }

    private static Map<String, Object> all200() {
        Map<String, Object> values = new LinkedHashMap<>();
        GitHubCapturePolicy.REQUIRED_ENDPOINTS.forEach(endpoint -> values.put(endpoint, 200));
        return values;
    }
}
