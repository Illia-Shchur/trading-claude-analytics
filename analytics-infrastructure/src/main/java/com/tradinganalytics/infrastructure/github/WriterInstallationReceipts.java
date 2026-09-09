package com.tradinganalytics.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.CustodyException;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen GitHub App installation proof used by the strategy-v5 evidence writer. */
public final class WriterInstallationReceipts {
    public static final long WRITER_APP_ID = 4_716_299L;
    public static final long WRITER_INSTALLATION_ID = 156_524_819L;
    public static final String WRITER_APP_SLUG = "strategy-v5-evidence";
    public static final String SCHEMA = "github-writer-installation-receipt/1";

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Map<String, String> EXACT_PERMISSIONS = Map.of(
            "contents", "write",
            "metadata", "read",
            "pull_requests", "write");

    private WriterInstallationReceipts() {}

    public record ApiResponse(int status, JsonNode body) {}

    public record Request(
            String repository,
            Object repositoryId,
            Object appId,
            String appSlug,
            Object installationId,
            ApiResponse apiResponse,
            ApiResponse appMetadataResponse,
            ApiResponse installationMetadataResponse,
            String generatedAt) {
        public Request(
                String repository,
                Object repositoryId,
                Object appId,
                String appSlug,
                Object installationId,
                ApiResponse apiResponse,
                ApiResponse appMetadataResponse,
                ApiResponse installationMetadataResponse) {
            this(repository, repositoryId, appId, appSlug, installationId, apiResponse,
                    appMetadataResponse, installationMetadataResponse, null);
        }
    }

    public record Verification(
            String repository,
            Object repositoryId,
            Object appId,
            Object installationId,
            String appSlug) {
        public Verification(String repository, Object repositoryId) {
            this(repository, repositoryId, WRITER_APP_ID, WRITER_INSTALLATION_ID, WRITER_APP_SLUG);
        }
    }

    /** Exact Java port of {@code makeWriterInstallationReceipt}. */
    public static ObjectNode makeWriterInstallationReceipt(Request request) {
        if (request == null) request = new Request(null, null, null, null, null, null, null, null, null);
        long repositoryId = positiveInteger(request.repositoryId(), "repository id");
        long appId = positiveInteger(request.appId(), "writer App id");
        long installationId = positiveInteger(request.installationId(), "installation id");
        if (appId != WRITER_APP_ID || installationId != WRITER_INSTALLATION_ID) {
            throw new CustodyException(
                    "writer App/installation identity is not the frozen strategy-v5-evidence deployment identity");
        }
        if (!WRITER_APP_SLUG.equals(String.valueOf(request.appSlug()))) {
            throw new CustodyException("writer App slug is not the frozen strategy-v5-evidence slug");
        }
        if (request.repository() == null || request.repository().isEmpty()
                || !successful(request.apiResponse()) || !successful(request.appMetadataResponse())
                || !successful(request.installationMetadataResponse())) {
            throw new CustodyException("writer installation metadata/API proof is incomplete");
        }

        JsonNode app = objectOrEmpty(request.appMetadataResponse().body());
        JsonNode installation = objectOrEmpty(request.installationMetadataResponse().body());
        if (app.path("id").asLong(Long.MIN_VALUE) != WRITER_APP_ID
                || !WRITER_APP_SLUG.equals(app.path("slug").asText())
                || !exactPermissionMap(app.get("permissions"))
                || !exactEvents(app.get("events"))
                || installation.path("id").asLong(Long.MIN_VALUE) != WRITER_INSTALLATION_ID
                || installation.path("app_id").asLong(Long.MIN_VALUE) != WRITER_APP_ID
                || !WRITER_APP_SLUG.equals(installation.path("app_slug").asText())
                || !"selected".equals(installation.path("repository_selection").asText())
                || !exactPermissionMap(installation.get("permissions"))
                || !exactEvents(installation.get("events"))) {
            throw new CustodyException("writer App/installation metadata is not the exact frozen contract");
        }

        JsonNode body = objectOrEmpty(request.apiResponse().body());
        JsonNode rows = body.get("repositories");
        if (body.path("total_count").asLong(Long.MIN_VALUE) != 1
                || rows == null || !rows.isArray() || rows.size() != 1) {
            throw new CustodyException("writer App must expose exactly one accessible repository");
        }
        JsonNode repository = rows.get(0);
        if (repository.path("id").asLong(Long.MIN_VALUE) != repositoryId
                || !request.repository().equals(repository.path("full_name").asText())) {
            throw new CustodyException("writer App installation repository/id proof is insufficient");
        }

        JsonNode account = objectOrEmpty(installation.get("account"));
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", SCHEMA);
        value.put("version", 1);
        value.put("generated_at", request.generatedAt() == null ? Instant.now().toString() : request.generatedAt());
        value.put("repository", request.repository());
        value.put("repository_id", repositoryId);
        value.put("app_id", WRITER_APP_ID);
        value.put("app_slug", WRITER_APP_SLUG);
        value.put("installation_id", WRITER_INSTALLATION_ID);
        value.put("app_endpoint", "app");
        value.put("app_endpoint_status", 200);
        value.put("app_endpoint_body_sha256", JsonHashes.canonicalSha256(app));
        value.put("installation_endpoint", "app/installations/" + WRITER_INSTALLATION_ID);
        value.put("installation_endpoint_status", 200);
        value.put("installation_endpoint_body_sha256", JsonHashes.canonicalSha256(installation));
        value.put("endpoint", "installation/repositories");
        value.put("endpoint_status", 200);
        value.put("endpoint_body_sha256", JsonHashes.canonicalSha256(body));
        value.put("repository_selection", "selected");
        ObjectNode accountValue = value.putObject("account");
        accountValue.put("id", account.path("id").asLong(0));
        accountValue.put("login", account.path("login").asText(""));
        accountValue.put("type", account.path("type").asText(""));
        ObjectNode permissions = value.putObject("permissions");
        EXACT_PERMISSIONS.forEach(permissions::put);
        value.putArray("events");
        value.put("accessible_repository_count", 1);
        ArrayNode accessible = value.putArray("accessible_repositories");
        ObjectNode accessibleRepository = accessible.addObject();
        accessibleRepository.put("id", repositoryId);
        accessibleRepository.put("full_name", request.repository());
        value.put("verified", true);
        value.putNull("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
        return value.deepCopy();
    }

    /** Exact fail-closed Java port of {@code verifyWriterInstallationReceipt}. */
    public static boolean verifyWriterInstallationReceipt(JsonNode value, Verification expected) {
        try {
            if (expected == null) expected = new Verification(null, null);
            ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
            long expectedRepositoryId = positiveInteger(expected.repositoryId(), "repository id");
            return value != null && value.isObject()
                    && SCHEMA.equals(value.path("schema").asText())
                    && value.path("version").asInt(Integer.MIN_VALUE) == 1
                    && value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))
                    && value.path("verified").asBoolean(false)
                    && String.valueOf(expected.repository()).equals(value.path("repository").asText())
                    && value.path("repository_id").asLong(Long.MIN_VALUE) == expectedRepositoryId
                    && value.path("app_id").asLong(Long.MIN_VALUE) == WRITER_APP_ID
                    && positiveInteger(expected.appId(), "writer App id") == WRITER_APP_ID
                    && value.path("app_slug").asText().equals(expected.appSlug())
                    && WRITER_APP_SLUG.equals(value.path("app_slug").asText())
                    && value.path("installation_id").asLong(Long.MIN_VALUE) == WRITER_INSTALLATION_ID
                    && positiveInteger(expected.installationId(), "installation id") == WRITER_INSTALLATION_ID
                    && "app".equals(value.path("app_endpoint").asText())
                    && value.path("app_endpoint_status").asInt(Integer.MIN_VALUE) == 200
                    && JsonHashes.isSha256(value.path("app_endpoint_body_sha256").asText())
                    && ("app/installations/" + WRITER_INSTALLATION_ID)
                            .equals(value.path("installation_endpoint").asText())
                    && value.path("installation_endpoint_status").asInt(Integer.MIN_VALUE) == 200
                    && JsonHashes.isSha256(value.path("installation_endpoint_body_sha256").asText())
                    && "installation/repositories".equals(value.path("endpoint").asText())
                    && value.path("endpoint_status").asInt(Integer.MIN_VALUE) == 200
                    && JsonHashes.isSha256(value.path("endpoint_body_sha256").asText())
                    && "selected".equals(value.path("repository_selection").asText())
                    && value.path("account").path("id").asLong(0) > 0
                    && !value.path("account").path("login").asText().isEmpty()
                    && !value.path("account").path("type").asText().isEmpty()
                    && exactPermissionMap(value.get("permissions"))
                    && exactEvents(value.get("events"))
                    && value.path("accessible_repository_count").asInt(Integer.MIN_VALUE) == 1
                    && value.path("accessible_repositories").isArray()
                    && value.path("accessible_repositories").size() == 1
                    && value.path("accessible_repositories").get(0).path("id").asLong(Long.MIN_VALUE)
                            == expectedRepositoryId
                    && String.valueOf(expected.repository()).equals(
                            value.path("accessible_repositories").get(0).path("full_name").asText());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean verifyWriterInstallationReceipt(
            JsonNode value, String repository, Object repositoryId) {
        return verifyWriterInstallationReceipt(value, new Verification(repository, repositoryId));
    }

    public static Map<String, String> exactPermissions() {
        return new LinkedHashMap<>(EXACT_PERMISSIONS);
    }

    private static boolean successful(ApiResponse response) {
        return response != null && response.status() == 200 && response.body() != null;
    }

    private static JsonNode objectOrEmpty(JsonNode value) {
        return value != null && value.isObject() ? value : JsonHashes.mapper().createObjectNode();
    }

    private static boolean exactPermissionMap(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != EXACT_PERMISSIONS.size()) return false;
        for (Map.Entry<String, String> entry : EXACT_PERMISSIONS.entrySet()) {
            if (!entry.getValue().equals(value.path(entry.getKey()).asText())) return false;
        }
        return true;
    }

    private static boolean exactEvents(JsonNode value) {
        return value != null && value.isArray() && value.isEmpty();
    }

    private static long positiveInteger(Object value, String label) {
        try {
            if (value == null) throw new NumberFormatException();
            java.math.BigDecimal number = new java.math.BigDecimal(String.valueOf(value));
            long result = number.longValueExact();
            if (result <= 0 || result > MAX_SAFE_INTEGER) throw new NumberFormatException();
            return result;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new CustodyException(label + " must be a positive integer");
        }
    }
}
