package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Standalone stdout/stderr/exit differential for the v5 executable facade. */
public final class StrategyResearchV5CommandAdapterNodeOracleTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static int assertions;

    public static void main(String[] ignored) throws Exception {
        parity();
        flagGrammar();
        System.out.println("StrategyResearchV5CommandAdapterNodeOracleTest: ok (" + assertions + " assertions)");
    }

    private static void parity() throws Exception {
        assertResult("help-empty", 0, StrategyResearchV5CommandAdapter.HELP_USAGE + "\n", "");
        assertResult("help-command", 0, StrategyResearchV5CommandAdapter.HELP_USAGE + "\n", "", "--help");
        assertResult("help-flag", 0, StrategyResearchV5CommandAdapter.HELP_USAGE + "\n", "", "unknown", "--help");
        assertResult("help-string-is-not-boolean", 1, "", "unknown strategy-research-v5 command: unknown\n"
                + StrategyResearchV5CommandAdapter.USAGE + "\n", "unknown", "--help", "true");
        assertResult("unknown", 1, "", "unknown strategy-research-v5 command: definitely-unknown\n"
                + StrategyResearchV5CommandAdapter.USAGE + "\n", "definitely-unknown", "--foo-bar", "value", "--switch");
        assertAlias("raw-replay-alias", "data-raw-replay", "data-local-raw-replay");
        assertAlias("genesis-alias", "research-init", "statistical-genesis");
        ProcessResult deployment = java(new String[] {"deployment-audit"});
        assertEqual("deployment-empty/exit", 0, deployment.status());
        assertEqual("deployment-empty/stderr", "", deployment.stderr());
        assertEqual("deployment-empty/schema", "strategy-deployment-audit/1",
                JSON.readTree(deployment.stdout()).path("audit").path("schema").asText());

        Path root = Files.createTempDirectory("strategy-v5-command-adapter-");
        Path output = root.resolve("deployment.json");
        assertEqual("deployment-immutable-out", 0, java(new String[] {"deployment-audit", "--out", output.toString()}).status());
        byte[] before = Files.readAllBytes(output);
        assertEqual("deployment-immutable-reopen", 0, java(new String[] {"deployment-audit", "--out", output.toString()}).status());
        assertEqual("deployment-immutable-bytes", Base64.getEncoder().encodeToString(before),
                Base64.getEncoder().encodeToString(Files.readAllBytes(output)));

        Path javaOutput = root.resolve("java-deployment.json");
        ProcessResult javaWrite = java(new String[] {"deployment-audit", "--out", javaOutput.toString()});
        assertEqual("deployment-fresh-write/java-exit", 0, javaWrite.status());
        JsonNode javaResult = JSON.readTree(javaWrite.stdout());
        assertEqual("deployment-fresh-write/java-path", javaOutput.toAbsolutePath().normalize().toString(),
                javaResult.path("path").asText());
        assertJson("deployment-fresh-write/file", javaResult.path("audit"), JSON.readTree(Files.readAllBytes(javaOutput)));
    }

    private static void flagGrammar() throws Exception {
        String[] args = {"ignored", "positional", "--dash-key", "value", "--switch", "--next", "x",
                "tail", "--dash-key", "last"};
        ObjectNode actual = StrategyResearchV5CommandAdapter.flags(args, 1);
        ObjectNode expected = JSON.createObjectNode().put("dash-key", "last").put("dash_key", "last")
                .put("switch", true).put("next", "x");
        assertJson("flag-grammar-json", expected, actual);
    }

    private static void assertResult(String label, int status, String stdout, String stderr, String... args) {
        ProcessResult actual = java(args);
        assertEqual(label + "/exit", status, actual.status());
        assertEqual(label + "/stdout", stdout, actual.stdout());
        assertEqual(label + "/stderr", stderr, actual.stderr());
    }

    private static void assertAlias(String label, String canonical, String alias) throws Exception {
        Path receiptRoot = Files.createTempDirectory("strategy-v5-alias-receipts-");
        List<String> canonicalArgs = List.of(canonical, "--record-root", receiptRoot.toString());
        List<String> aliasArgs = List.of(alias, "--record-root", receiptRoot.toString());
        ProcessResult javaCanonical = java(canonicalArgs.toArray(String[]::new));
        ProcessResult javaAlias = java(aliasArgs.toArray(String[]::new));
        assertEqual(label + "/java-exit", javaCanonical.status(), javaAlias.status());
        assertEqual(label + "/java-stdout", javaCanonical.stdout(), javaAlias.stdout());
        assertEqual(label + "/java-stderr", javaCanonical.stderr(), javaAlias.stderr());
    }

    private static ProcessResult java(String[] args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream(); int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = StrategyResearchV5CommandAdapter.run(args, out, err);
        }
        return new ProcessResult(status, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static void assertJson(String label, JsonNode expected, JsonNode actual) {
        assertions++; String left = StrategyResearchV5.stable(expected), right = StrategyResearchV5.stable(actual);
        if (!left.equals(right)) throw new AssertionError(label + " mismatch\nNODE=" + left + "\nJAVA=" + right);
    }

    private static void assertEqual(String label, Object expected, Object actual) {
        assertions++; if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + " mismatch\nNODE=" + expected + "\nJAVA=" + actual);
        }
    }

    private record ProcessResult(int status, String stdout, String stderr) {}
}
