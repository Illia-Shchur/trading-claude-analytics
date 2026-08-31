package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps converted operational workflows on the Spring command boundary. */
class JavaWorkflowCutoverTest {
    @Test
    void activeOperatorGuidanceUsesTheSpringLauncher() throws Exception {
        Path root = RepositoryRoot.find();
        String guidance = String.join("\n",
                Files.readString(root.resolve("AGENTS.md")),
                Files.readString(root.resolve("CLAUDE.md")),
                Files.readString(root.resolve(
                        ".agents/skills/fallen-knives-analytics/SKILL.md")),
                Files.readString(root.resolve(
                        ".agents/skills/flying-rocket-analytics/SKILL.md")),
                Files.readString(root.resolve(
                        ".agents/skills/framework-calibration/SKILL.md")),
                Files.readString(root.resolve(
                        ".agents/skills/strategy-research/SKILL.md")),
                Files.readString(root.resolve(
                        ".claude/skills/fallen-knives-analytics/SKILL.md")),
                Files.readString(root.resolve(
                        ".claude/skills/flying-rocket-analytics/SKILL.md")),
                Files.readString(root.resolve(
                        ".claude/skills/framework-calibration/SKILL.md")));

        assertThat(guidance)
                .contains("./bin/analytics position", "./bin/analytics fetch",
                        "./bin/analytics strategy-research-v5")
                .doesNotContain("node tools/");

        assertThat(root.resolve("package.json")).doesNotExist();
        assertThat(root.resolve("package-lock.json")).doesNotExist();
        assertThat(Files.readString(root.resolve("docs/JAVA-MIGRATION.md")))
                .contains("./mvnw test", "./mvnw verify");
    }

    @Test
    void confirmationAndTimeSealWorkflowsUseOnlyTheJavaApplication() throws Exception {
        Path root = RepositoryRoot.find();
        String confirmation = Files.readString(root.resolve(
                ".github/workflows/strategy-confirmation.yml"));
        String prospective = Files.readString(root.resolve(
                ".github/workflows/strategy-prospective.yml"));
        String custody = Files.readString(root.resolve(
                ".github/workflows/strategy-v5-evidence-custody.yml"));
        String foundation = Files.readString(root.resolve(
                ".github/workflows/research-foundation.yml"));
        String v5Prospective = Files.readString(root.resolve(
                ".github/workflows/strategy-v5-prospective.yml"));

        assertThat(confirmation)
                .contains("./bin/analytics ci-confirmation --preflight")
                .contains("actions/setup-java@")
                .doesNotContain("node tools/", "npm ci", "actions/setup-node@");
        assertThat(prospective)
                .contains("./bin/analytics strategy-prospective-runner preflight")
                .contains("actions/setup-java@")
                .doesNotContain("node tools/", "npm ci", "actions/setup-node@");
        assertThat(custody)
                .contains("strategy-v5-workflow-security snapshot-root")
                .contains("strategy-v5-workflow-security archive")
                .contains("strategy-v5-workflow-security snapshot")
                .contains("actions/setup-java@")
                .doesNotContain("node tools/", "node --input-type=module", "npm ci",
                        "actions/setup-node@");
        assertThat(foundation)
                .contains("./mvnw --batch-mode --no-transfer-progress verify")
                .contains("ResearchDataTest#originalDockerParquetContractRunsLocallyAndIsIdempotent")
                .contains("actions/setup-java@")
                .doesNotContain("node ", "npm ", "actions/setup-node@");
        assertThat(v5Prospective)
                .contains("./bin/analytics strategy-v5-prospective-workflow capture-settings")
                .contains("strategy-v5-prospective-workflow verify-preflight")
                .contains("strategy-v5-prospective-workflow verify-snapshot")
                .contains("actions/setup-java@")
                .doesNotContain("node ", "node --input-type=module", "npm ",
                        "actions/setup-node@");
    }
}
