package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Publication path, reference, and control binding rejection matrix. */
final class StrategyStatisticalV5PublicationSecurityMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @TempDir
    Path temporary;

    @Test
    void publicationRejectsControlPathCollisionAndDuplicateRoles() {
        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture = fixture();

        ObjectNode controlCollision = fixture.options().deepCopy();
        ((ObjectNode) controlCollision.withArray("artifacts").get(0)).put("path", "control/head.json");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(controlCollision))
                .hasMessageContaining("collides with a transaction/control path");

        ObjectNode duplicateRole = fixture.options().deepCopy();
        ObjectNode duplicateRun = (ObjectNode) duplicateRole.withArray("artifacts").get(1);
        duplicateRun.put("role", "wfo").set("value", fixture.wfo());
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(duplicateRole))
                .hasMessageContaining("publication artifact role is duplicated");
    }

    @Test
    void publicationRejectsOutsideAndPartialFinalOosPaths() {
        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture = fixture();
        ObjectNode outside = fixture.options().deepCopy();
        ((ObjectNode) outside.withArray("artifacts").get(0)).put("path", "/tmp/publication-outside-wfo.json");
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(outside))
                .hasMessageContaining("publication artifact path is outside record root");

        ObjectNode partial = fixture.options().deepCopy();
        partial.withArray("artifacts").remove(3);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(partial))
                .hasMessageContaining("must include the final OOS artifact and vector inventory");
    }

    @Test
    void publicationRejectsIncompleteAndDishonestPhysicalReferences() {
        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture = fixture();

        ObjectNode missing = fixture.options().deepCopy();
        ObjectNode missingRun = (ObjectNode) missing.path("run");
        missingRun.with("stage_artifact_refs").remove("genetic");
        missingRun = StrategyStatisticalV5.withHash(missingRun); setRun(missing, missingRun);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(missing))
                .hasMessageContaining("stage artifact reference inventory");

        ObjectNode dishonest = fixture.options().deepCopy();
        ObjectNode dishonestRun = (ObjectNode) dishonest.path("run");
        ObjectNode geneticRef = (ObjectNode) dishonestRun.with("stage_artifact_refs").path("genetic");
        geneticRef.put("bytes", geneticRef.path("bytes").asInt() + 1);
        dishonestRun = StrategyStatisticalV5.withHash(dishonestRun); setRun(dishonest, dishonestRun);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(dishonest))
                .hasMessageContaining("stage_artifact_refs.genetic bytes are tampered");
    }

    @Test
    void publicationRejectsReferenceSchemaAndExpectedHashDrift() {
        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture = fixture();

        ObjectNode wrongSchema = fixture.options().deepCopy();
        ObjectNode schemaRun = (ObjectNode) wrongSchema.path("run");
        ((ObjectNode) schemaRun.with("stage_artifact_refs").path("genetic"))
                .put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("input"));
        schemaRun = StrategyStatisticalV5.withHash(schemaRun); setRun(wrongSchema, schemaRun);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(wrongSchema))
                .hasMessageContaining("registered schema validation failed");

        ObjectNode wrongHash = fixture.options().deepCopy();
        ObjectNode hashRun = (ObjectNode) wrongHash.path("run");
        hashRun.with("stage_artifacts").put("genetic", JsonHashes.sha256("unregistered-genetic"));
        hashRun = StrategyStatisticalV5.withHash(hashRun); setRun(wrongHash, hashRun);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(wrongHash))
                .hasMessageContaining("stage_artifact_refs.genetic disagrees with stage inventory");
    }

    @Test
    void publicationRejectsRegistryAndCasHeadBindingDrift() throws IOException {
        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture = fixture();

        ObjectNode wrongRegistry = fixture.registry().deepCopy();
        wrongRegistry.put("exposure_head_sha256", JsonHashes.sha256("different-head"));
        wrongRegistry = StrategyStatisticalV5.withHash(wrongRegistry);
        Files.write(fixture.root().resolve("control/registry.json"), MAPPER.writeValueAsBytes(wrongRegistry));
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(fixture.options()))
                .hasMessageContaining("publication registry binding does not match");

        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture changed = fixture();
        String extraAlias = JsonHashes.sha256("publication-security-extra");
        ObjectNode nextHeadArgs = object().put("hypothesisFamily", changed.head().path("hypothesis_family").asText())
                .put("datasetSha256", changed.head().path("dataset_sha256").asText());
        nextHeadArgs.set("entries", changed.head().path("entries").deepCopy());
        nextHeadArgs.withArray("entries").addObject().put("behavior_sha256", extraAlias)
                .put("dataset_sha256", JsonHashes.sha256("publication-security-extra-data"));
        ObjectNode changedHead = StrategyStatisticalV5.makeExposureHead(nextHeadArgs);
        ObjectNode headDrift = changed.options().deepCopy(); headDrift.set("nextHead", changedHead);
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(headDrift))
                .hasMessageContaining("compare-and-swap expected hash");
    }

    @Test
    void publicationRejectsPhysicalSymlinkStagePath() throws IOException {
        StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture = fixture();
        Path original = fixture.root().resolve("stages/genetic.json");
        Path target = fixture.root().resolve("stages/genetic-real.json");
        Files.move(original, target);
        Files.createSymbolicLink(original, target.getFileName());
        assertThatThrownBy(() -> StrategyStatisticalV5.makeStatisticalPublicationTransaction(fixture.options()))
                .hasMessageContaining("symlink");
    }

    private StrategyStatisticalV5PublicationBindingMatrixTest.Fixture fixture() {
        try {
            Path root = temporary == null ? Files.createTempDirectory("statistical-publication-security")
                    : Files.createTempDirectory(temporary, "case-");
            return StrategyStatisticalV5PublicationBindingMatrixTest.fixtureAt(root);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void setRun(ObjectNode options, ObjectNode run) {
        options.set("run", run);
        ((ObjectNode) options.withArray("artifacts").get(1)).set("value", run);
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}
