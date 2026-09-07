package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalInputAssemblyHashTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void batchAssemblyUsesJavaCanonicalNumbersForRoleIdentity() throws Exception {
        Path role = temporaryDirectory.resolve("role.json");
        Files.writeString(role, "{\"price\":30000.0,\"effect\":0.0,\"rows\":[1,2]}\n");
        Path paths = temporaryDirectory.resolve("paths.txt");
        Files.writeString(paths, role.toString() + "\n");

        ObjectNode result = StrategyResearchImprovementV1.canonicalHashBatch(
                JsonHashes.mapper().createObjectNode().put("paths_file", paths.toString()).put("include_rows", true));
        ObjectNode file = (ObjectNode) result.path("files").get(0);
        ObjectNode value = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(role));
        assertThat(file.path("byte_sha256").asText()).isEqualTo(JsonHashes.sha256(role));
        assertThat(file.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(value));
        assertThat(file.path("rows_sha256").asText()).isEqualTo(JsonHashes.ownHash(value));
    }
}
