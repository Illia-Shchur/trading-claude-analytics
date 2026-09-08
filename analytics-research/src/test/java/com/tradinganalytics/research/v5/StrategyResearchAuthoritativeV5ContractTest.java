package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Small public-contract checks for the authoritative orchestration facade.
 * These fixtures exercise custody and state transitions without opening a
 * physical data or statistical evaluation run.
 */
final class StrategyResearchAuthoritativeV5ContractTest {
    @TempDir Path temporary;

    @Test
    void coverageReportEmitsPlanOnlyStateAndRejectsPlanTampering() {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                JsonHashes.mapper().createObjectNode()
                        .put("asOf", "2026-08-24T20:30:00.000Z")
                        .put("rootReference", "contract-fixture"));
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("mode", "PLAN_ONLY")
                .put("capturedAt", "2026-08-24T20:30:00.000Z");
        options.set("plan", plan);

        ObjectNode planned = StrategyResearchAuthoritativeV5.coverageReport(options);

        assertThat(planned.path("status").asText()).isEqualTo("PLANNED");
        assertThat(planned.path("limitations").toString()).contains("NO_DATA_ROWS_ACQUIRED");
        assertThat(planned.path("series").size()).isGreaterThan(0);
        assertThat(planned.path("content_sha256").asText())
                .isEqualTo(StrategyResearchAuthoritativeV5.ownHash(planned));

        ObjectNode tamperedPlan = plan.deepCopy().put("content_sha256", "a".repeat(64));
        ObjectNode tamperedOptions = options.deepCopy();
        tamperedOptions.set("plan", tamperedPlan);
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.coverageReport(tamperedOptions))
                .hasMessageContaining("hash-valid authoritative plan");
    }

    @Test
    void commandReceiptValidationRejectsTamperingAndActiveStateAfterRehash() {
        ObjectNode request = JsonHashes.mapper().createObjectNode()
                .put("command", "validate").put("status", "COMPLETE");
        request.putArray("inputs");
        request.putArray("outputs");
        request.putArray("limitations");
        request.putObject("details").put("mode", "CONTRACT_TEST");

        ObjectNode receipt = StrategyResearchAuthoritativeV5.makeCommandReceipt(request);
        assertThat(StrategyResearchAuthoritativeV5.validateCommandReceipt(receipt)).isTrue();

        ObjectNode tampered = receipt.deepCopy().put("status", "ACTIVE");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateCommandReceipt(tampered))
                .hasMessageContaining("missing or tampered");

        ObjectNode active = receipt.deepCopy();
        ((ObjectNode) active.path("details")).put("mode", "ACTIVE");
        active.put("content_sha256", StrategyResearchAuthoritativeV5.ownHash(active));
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.validateCommandReceipt(active))
                .hasMessageContaining("may not claim ACTIVE");
    }

    @Test
    void behaviorRegistryPathsPreserveMutableHeadImmutableSnapshotAndMigrationGuard() throws Exception {
        Path root = temporary.resolve("registry");
        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths initial =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root);
        assertThat(initial.directory()).isEqualTo(root.toAbsolutePath().resolve("behavior-definitions"));
        assertThat(initial.statePath()).isEqualTo(initial.directory().resolve("behavior-definition-registry-head.json"));
        assertThat(initial.seedPath()).isNull();
        ObjectNode encoded = JsonHashes.mapper().createObjectNode().put("record_root", root.toString());
        assertThat(StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(encoded)
                .path("statePath").asText()).isEqualTo(initial.statePath().toString());

        Path explicitState = root.resolve("custom-head.json");
        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths mutable =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root, explicitState);
        assertThat(mutable.statePath()).isEqualTo(explicitState.toAbsolutePath());
        assertThat(mutable.seedPath()).isNull();

        Files.createDirectories(initial.directory());
        Path snapshot = initial.directory().resolve("registry-" + "b".repeat(64) + ".json");
        Files.writeString(snapshot, "{}\n");
        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths immutable =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root, snapshot);
        assertThat(immutable.statePath()).isEqualTo(initial.statePath());
        assertThat(immutable.seedPath()).isEqualTo(snapshot.toAbsolutePath());

        Files.writeString(initial.directory().resolve("registry-" + "c".repeat(64) + ".json"), "{}\n");
        assertThatThrownBy(() -> StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root))
                .hasMessageContaining("multiple immutable behavior-registry snapshots");
    }

    @Test
    void behaviorRegistryUsesLegacySeedWhenNoMutableHeadExists() throws Exception {
        Path root = temporary.resolve("legacy-registry");
        Files.createDirectories(root);
        Path legacy = root.resolve("behavior-definition-registry.json");
        Files.writeString(legacy, "{}\n");

        StrategyResearchAuthoritativeV5.BehaviorRegistryPaths paths =
                StrategyResearchAuthoritativeV5.behaviorRegistryStatePaths(root);

        assertThat(paths.statePath()).isEqualTo(root.toAbsolutePath().resolve(
                "behavior-definitions/behavior-definition-registry-head.json"));
        assertThat(paths.seedPath()).isEqualTo(legacy.toAbsolutePath());
    }
}
