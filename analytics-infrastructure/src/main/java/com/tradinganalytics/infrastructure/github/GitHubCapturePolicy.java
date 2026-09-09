package com.tradinganalytics.infrastructure.github;

import java.util.List;
import java.util.Map;

/** Deterministic first-failure mapping for the GitHub settings capture. */
public final class GitHubCapturePolicy {
    public static final List<String> REQUIRED_ENDPOINTS = List.of(
            "repository",
            "branch_protection",
            "branch_head",
            "environment_protection",
            "writer_environment_protection",
            "rulesets",
            "ruleset_details",
            "installation",
            "settings_token_identity",
            "settings_token_secret",
            "evidence_writer_secret",
            "oidc_subject_restriction",
            "actions_permissions",
            "actions_selected_permissions",
            "actions_workflow_permissions");

    public record EndpointFailure(String endpoint, int status) {}

    private GitHubCapturePolicy() {}

    public static EndpointFailure firstNon200Endpoint(Map<String, ?> endpointStatuses) {
        return firstNon200Endpoint(endpointStatuses, REQUIRED_ENDPOINTS);
    }

    public static EndpointFailure firstNon200Endpoint(Map<String, ?> endpointStatuses, List<String> order) {
        Map<String, ?> statuses = endpointStatuses == null ? Map.of() : endpointStatuses;
        for (String endpoint : order) {
            int status = integerStatus(statuses.get(endpoint));
            if (status != 200) return new EndpointFailure(endpoint, status);
        }
        return null;
    }

    public static String captureFailureReason(EndpointFailure failure) {
        return failure == null ? null
                : "GITHUB_API_ENDPOINT_FAILED:" + failure.endpoint() + ":" + failure.status();
    }

    public static int selectCaptureStatus(boolean allVerified, Map<String, ?> endpointStatuses) {
        if (allVerified) return 200;
        EndpointFailure failure = firstNon200Endpoint(endpointStatuses);
        return failure == null ? 0 : failure.status();
    }

    private static int integerStatus(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long number
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                double parsed = Double.parseDouble(text.trim());
                if (Double.isFinite(parsed) && parsed == Math.rint(parsed)
                        && parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                    return (int) parsed;
                }
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (Double.isFinite(parsed) && parsed == Math.rint(parsed)
                    && parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                return (int) parsed;
            }
        }
        return 0;
    }
}
