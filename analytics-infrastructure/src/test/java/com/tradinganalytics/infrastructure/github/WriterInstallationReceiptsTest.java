package com.tradinganalytics.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class WriterInstallationReceiptsTest {
    private static final String REPOSITORY = "Illia-Shchur/trading-claude-analytics";
    private static final long REPOSITORY_ID = 1_238_541_043L;
    private static final String GENERATED_AT = "2026-08-25T00:00:00Z";

    @Test
    void makeAndVerifyMatchTheNodeOracleExactly() throws Exception {
        Fixture fixture = fixture();
        ObjectNode javaReceipt = WriterInstallationReceipts.makeWriterInstallationReceipt(
                fixture.request());
        JsonNode nodeReceipt = frozenWriterReceipt();

        assertThat(JsonHashes.canonicalString(javaReceipt))
                .isEqualTo(JsonHashes.canonicalString(nodeReceipt));
        assertThat(WriterInstallationReceipts.verifyWriterInstallationReceipt(
                javaReceipt, REPOSITORY, REPOSITORY_ID)).isTrue();
        ObjectNode tampered = javaReceipt.deepCopy();
        tampered.put("content_sha256", "0".repeat(64));
        assertThat(WriterInstallationReceipts.verifyWriterInstallationReceipt(
                tampered, REPOSITORY, REPOSITORY_ID)).isFalse();
        assertThat(WriterInstallationReceipts.WRITER_APP_ID).isEqualTo(4_716_299L);
        assertThat(WriterInstallationReceipts.WRITER_INSTALLATION_ID).isEqualTo(156_524_819L);
        assertThat(WriterInstallationReceipts.WRITER_APP_SLUG).isEqualTo("strategy-v5-evidence");
    }

    @Test
    void rejectsEveryFrozenIdentityAndLeastPrivilegeDeviation() {
        Fixture fixture = fixture();
        for (String mutation : new String[] {
                "app-slug", "app-id", "installation-id", "installation-app-id",
                "extra-repository", "extra-permission", "missing-permission", "event"}) {
            WriterInstallationReceipts.Request request = mutate(fixture, mutation);
            assertThatThrownBy(() -> WriterInstallationReceipts.makeWriterInstallationReceipt(request))
                    .as(mutation).hasMessageMatching(".*(exact frozen contract|exactly one).*" );
        }
        assertThatThrownBy(() -> WriterInstallationReceipts.makeWriterInstallationReceipt(
                new WriterInstallationReceipts.Request(REPOSITORY, 0,
                        WriterInstallationReceipts.WRITER_APP_ID,
                        WriterInstallationReceipts.WRITER_APP_SLUG,
                        WriterInstallationReceipts.WRITER_INSTALLATION_ID,
                        fixture.request().apiResponse(), fixture.request().appMetadataResponse(),
                        fixture.request().installationMetadataResponse(), GENERATED_AT)))
                .hasMessage("repository id must be a positive integer");
        assertThatThrownBy(() -> WriterInstallationReceipts.makeWriterInstallationReceipt(
                new WriterInstallationReceipts.Request(REPOSITORY, REPOSITORY_ID, 99,
                        WriterInstallationReceipts.WRITER_APP_SLUG,
                        WriterInstallationReceipts.WRITER_INSTALLATION_ID,
                        fixture.request().apiResponse(), fixture.request().appMetadataResponse(),
                        fixture.request().installationMetadataResponse(), GENERATED_AT)))
                .hasMessageContaining("frozen strategy-v5-evidence deployment identity");
    }

    private static WriterInstallationReceipts.Request mutate(Fixture fixture, String mutation) {
        ObjectNode app = fixture.app().deepCopy();
        ObjectNode installation = fixture.installation().deepCopy();
        ObjectNode repositories = fixture.repositories().deepCopy();
        switch (mutation) {
            case "app-slug" -> app.put("slug", "other-app");
            case "app-id" -> app.put("id", 99);
            case "installation-id" -> installation.put("id", 99);
            case "installation-app-id" -> installation.put("app_id", 99);
            case "extra-repository" -> {
                repositories.put("total_count", 2);
                repositories.withArray("repositories").addObject()
                        .put("id", 99).put("full_name", "other/repo");
            }
            case "extra-permission" -> ((ObjectNode) app.path("permissions")).put("issues", "write");
            case "missing-permission" -> ((ObjectNode) installation.path("permissions"))
                    .remove("pull_requests");
            case "event" -> app.withArray("events").add("push");
            default -> throw new IllegalArgumentException(mutation);
        }
        return request(app, installation, repositories);
    }

    private static Fixture fixture() {
        ObjectNode permissions = JsonHashes.mapper().createObjectNode();
        permissions.put("contents", "write");
        permissions.put("metadata", "read");
        permissions.put("pull_requests", "write");
        ObjectNode app = JsonHashes.mapper().createObjectNode();
        app.put("id", WriterInstallationReceipts.WRITER_APP_ID);
        app.put("slug", WriterInstallationReceipts.WRITER_APP_SLUG);
        app.set("permissions", permissions.deepCopy());
        app.putArray("events");
        ObjectNode installation = JsonHashes.mapper().createObjectNode();
        installation.put("id", WriterInstallationReceipts.WRITER_INSTALLATION_ID);
        installation.put("app_id", WriterInstallationReceipts.WRITER_APP_ID);
        installation.put("app_slug", WriterInstallationReceipts.WRITER_APP_SLUG);
        installation.put("repository_selection", "selected");
        installation.set("permissions", permissions.deepCopy());
        installation.putArray("events");
        installation.putObject("account").put("id", 37_546_899L)
                .put("login", "Illia-Shchur").put("type", "User");
        ObjectNode repositories = JsonHashes.mapper().createObjectNode();
        repositories.put("total_count", 1);
        ObjectNode row = repositories.putArray("repositories").addObject();
        row.put("id", REPOSITORY_ID).put("full_name", REPOSITORY);
        row.putObject("permissions").put("admin", false).put("push", true).put("pull", true);
        return new Fixture(app, installation, repositories, request(app, installation, repositories));
    }

    private static WriterInstallationReceipts.Request request(
            ObjectNode app, ObjectNode installation, ObjectNode repositories) {
        return new WriterInstallationReceipts.Request(
                REPOSITORY, REPOSITORY_ID, WriterInstallationReceipts.WRITER_APP_ID,
                WriterInstallationReceipts.WRITER_APP_SLUG,
                WriterInstallationReceipts.WRITER_INSTALLATION_ID,
                new WriterInstallationReceipts.ApiResponse(200, repositories),
                new WriterInstallationReceipts.ApiResponse(200, app),
                new WriterInstallationReceipts.ApiResponse(200, installation), GENERATED_AT);
    }

    private static JsonNode frozenWriterReceipt() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                WriterInstallationReceiptsTest.class.getResourceAsStream(
                        "/oracles/writer-installation-receipt-v1.json"),
                "frozen writer-installation oracle is missing")) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private record Fixture(
            ObjectNode app,
            ObjectNode installation,
            ObjectNode repositories,
            WriterInstallationReceipts.Request request) {}
}
