package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.research.legacy.LegacyResearchNext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Exact stdout/stderr/exit and immutable-output checks for the scheduled runner port. */
final class StrategyProspectiveRunnerNodeOracleTest {
    private static final ObjectNode ORACLE = loadOracle();

    @Test
    void invalidCommandAndMissingLedgerMatchNode() throws Exception {
        Result javaResult = javaRun("not-a-command");
        assertSame(javaResult, frozenResult("invalid_command"));

        javaResult = javaRun("preflight", "--ledger", "missing-ledger.json");
        assertSame(javaResult, frozenResult("missing_ledger"));
    }

    @Test
    void preflightEligibilityAndAppendPreserveRunnerContract() throws Exception {
        Path root = Files.createTempDirectory("prospective-runner-");
        ObjectNode ledger = fixtureLedger();
        Path ledgerPath = root.resolve("ledger.json");
        Files.write(ledgerPath, NodePrettyJson.write(ledger).getBytes(StandardCharsets.UTF_8));
        Result javaPreflight = javaRun("preflight", "--ledger", ledgerPath.toString());
        assertThat(javaPreflight.exit).isZero();
        assertThat(javaPreflight.stderr).isEmpty();
        assertThat(JsonHashes.mapper().readTree(javaPreflight.stdout)).isEqualTo(ORACLE.path("preflight"));

        Result javaEligibility = javaRun("eligibility", "--ledger", ledgerPath.toString());
        assertEligibilityMatchesFrozen(javaEligibility);

        Path payloadPath = root.resolve("payload.json");
        ObjectNode payload = JsonHashes.mapper().createObjectNode().put("signal_id", "runner-signal")
                .put("asset", "btc").put("direction", "long").put("decision", "SHADOW")
                .put("horizon_ms", 3_600_000).put("availability_receipt_sha256", "b".repeat(64))
                .put("capture_time", "2020-01-03T00:00:00Z")
                .put("lineage_sha256", ledger.path("reservation").path("lineage_sha256").asText());
        Files.write(payloadPath, NodePrettyJson.write(payload).getBytes(StandardCharsets.UTF_8));
        Path javaOut = root.resolve("java-next.json");
        String[] append = {"append", "--ledger", ledgerPath.toString(), "--kind", "SIGNAL",
                "--decision_time", "2020-01-03T00:00:00Z", "--payload", payloadPath.toString()};
        Result javaAppend = javaRun(concat(append, "--out", javaOut.toString()));
        assertThat(javaAppend.exit).isZero();
        assertThat(javaAppend.stderr).isEmpty();
        ObjectNode appendResult = (ObjectNode) JsonHashes.mapper().readTree(javaAppend.stdout);
        appendResult.put("path", "$OUT");
        assertThat(appendResult).isEqualTo(ORACLE.path("append"));
        ObjectNode appended = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(javaOut));
        assertThat(appended.path("content_sha256").asText())
                .isEqualTo(ORACLE.path("appended_ledger").path("content_sha256").asText());
        assertThat(JsonHashes.sha256(Files.readAllBytes(javaOut)))
                .isEqualTo(ORACLE.path("appended_ledger").path("byte_sha256").asText());
        assertThat(javaRun(append).stderr).contains("append requires --out; refusing to overwrite the source ledger");
    }

    private static ObjectNode fixtureLedger() {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("frozenAt", "2020-01-01T00:00:00Z")
                .put("startAt", "2020-01-02T00:00:00Z");
        options.set("lineage", JsonHashes.mapper().createObjectNode().put("strategy_sha256", "a".repeat(64)));
        options.putArray("proposedAssets").add("btc");
        return LegacyResearchNext.makeProspectiveLedger(LegacyResearchNext.makeProspectiveReservation(options));
    }

    private static Result javaRun(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        int exit = StrategyProspectiveRunnerCommandAdapter.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static String[] concat(String[] source, String... suffix) {
        String[] result = Arrays.copyOf(source, source.length + suffix.length);
        System.arraycopy(suffix, 0, result, source.length, suffix.length);
        return result;
    }

    private static void assertSame(Result left, Result right) {
        assertThat(left.exit).isEqualTo(right.exit);
        assertThat(left.stdout).isEqualTo(right.stdout);
        assertThat(left.stderr).isEqualTo(right.stderr);
    }

    private static void assertEligibilityMatchesFrozen(Result result) throws Exception {
        assertThat(result.exit).isZero();
        assertThat(result.stderr).isEmpty();
        ObjectNode value = (ObjectNode) JsonHashes.mapper().readTree(result.stdout);
        assertThat(value.path("days").asDouble()).isGreaterThanOrEqualTo(0);
        value.remove("days");
        assertThat(value).isEqualTo(ORACLE.path("eligibility_without_days"));
    }

    private record Result(int exit, String stdout, String stderr) {}

    private static Result frozenResult(String name) {
        JsonNode value = ORACLE.path(name);
        return new Result(value.path("exit").asInt(), value.path("stdout").asText(),
                value.path("stderr").asText());
    }

    private static ObjectNode loadOracle() {
        try (var input = StrategyProspectiveRunnerNodeOracleTest.class.getResourceAsStream(
                "/oracles/strategy-prospective-runner-v1.json")) {
            if (input == null) throw new IllegalStateException("frozen prospective runner oracle is missing");
            return (ObjectNode) JsonHashes.mapper().readTree(input);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
