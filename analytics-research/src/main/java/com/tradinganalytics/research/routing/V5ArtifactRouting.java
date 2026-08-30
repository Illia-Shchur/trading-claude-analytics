package com.tradinganalytics.research.routing;

import java.util.List;

/** Routing policy preserved from {@code tools/strategy-v5-routing.mjs}. */
public final class V5ArtifactRouting {
    public static final List<String> ARTIFACT_SCHEMA_ALLOWLIST = List.of(
            "strategy-data-manifest/3",
            "strategy-genetic-run/1",
            "strategy-exposure-ledger/2",
            "strategy-opportunity-envelope/1",
            "strategy-prospective-runner/2",
            "strategy-overfit-audit/1",
            "strategy-gene-space/1",
            "strategy-portfolio-policy/2",
            "strategy-readiness-evidence-manifest/1",
            "strategy-readiness-audit/2");

    public static final List<String> INDEX_SCHEMA_ALLOWLIST = List.of(
            "strategy-portfolio-policy/2",
            "strategy-readiness-evidence-manifest/1",
            "strategy-readiness-audit/2");

    public static final List<String> VALIDATE_SCHEMA_ALLOWLIST = List.of(
            "strategy-data-manifest/3",
            "strategy-genetic-run/1",
            "strategy-exposure-ledger/2",
            "strategy-opportunity-envelope/1",
            "strategy-prospective-runner/2",
            "strategy-overfit-audit/1",
            "strategy-gene-space/1");

    private V5ArtifactRouting() {
    }

    public static boolean isV5ArtifactSchema(String schema) {
        return isV5ArtifactSchema(schema, true, ARTIFACT_SCHEMA_ALLOWLIST);
    }

    public static boolean isV5ArtifactSchema(String schema, boolean allowPrefix, List<String> allowlist) {
        String text = schema == null ? "" : schema;
        List<String> effectiveAllowlist = allowlist == null ? ARTIFACT_SCHEMA_ALLOWLIST : allowlist;
        return (allowPrefix && text.startsWith("strategy-v5-"))
                || text.endsWith("/5")
                || (schema != null && effectiveAllowlist.contains(schema));
    }
}
