package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public process-adapter rejection contracts for replay and staged replay commands. */
class LiquidationV2ReplayCliBoundaryTest {
    @TempDir Path temporary;

    @Test
    void replayDispatchRequiresReadableJsonObjectAndSelfHashedFreeze() throws Exception {
        assertInvocation(1, "", "--options requires a JSON file path\n", "liquidation-v2-replay-resumable");

        Path missing = temporary.resolve("missing.json");
        Invocation missingResult = invoke("liquidation-v2-replay-resumable", "--options", missing.toString());
        assertEquals(1, missingResult.exitCode());
        assertEquals("", missingResult.stdout());
        assertTrue(missingResult.stderr().startsWith("cannot read --options:"));

        Path array = temporary.resolve("array.json");
        Files.writeString(array, "[]", StandardCharsets.UTF_8);
        assertInvocation(1, "", "--options must point to a JSON object\n",
                "liquidation-v2-replay-resumable", "--options", array.toString());

        Path malformedFreeze = writeOptions(JsonHashes.mapper().createObjectNode().set("freeze",
                JsonHashes.mapper().createObjectNode()));
        assertInvocation(1, "", "freeze schema or outer content hash is invalid\n",
                "liquidation-v2-replay-resumable", "--options", malformedFreeze.toString());
    }

    @Test
    void stagedDispatchRequiresItsVersionedPlanBeforeOpeningPhysicalInputs() throws Exception {
        Path options = writeOptions(JsonHashes.mapper().createObjectNode().set("freeze",
                JsonHashes.mapper().createObjectNode()));
        assertInvocation(1, "", "runStaged requires its separately frozen staged_plan\n",
                "liquidation-v2-staged-replay", "--options", options.toString());
        assertInvocation(1, "", "evaluateStaged requires its separately frozen staged_plan\n",
                "liquidation-v2-staged-evidence", "--options", options.toString());

        assertInvocation(1, "", "--freeze requires a JSON file path\n",
                "liquidation-v2-staged-plan-macro");
    }

    @Test
    void noMacroPlanDispatchRejectsMalformedPredecessorAtTheAdapterBoundary() throws Exception {
        // Dispatch-level success is covered by the staged public E2E. This test locks down the
        // malformed predecessor path without manufacturing a survival claim.
        ObjectNode invalidCore = JsonHashes.mapper().createObjectNode();
        Path core = writeOptions(invalidCore);
        Path evidence = writeOptions(JsonHashes.mapper().createObjectNode());
        Path inventory = writeOptions(JsonHashes.mapper().createObjectNode());
        Invocation result = invoke("liquidation-v2-staged-plan-no-macro", "--core_replay", core.toString(),
                "--core_evidence", evidence.toString(), "--candidate_inventory", inventory.toString(),
                "--out", temporary.resolve("ignored-plan.json").toString());
        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertEquals("core predecessor replay content hash is invalid\n", result.stderr());
    }

    private Path writeOptions(ObjectNode node) throws Exception {
        Path path = temporary.resolve("options-" + System.nanoTime() + ".json");
        Files.write(path, JsonHashes.canonicalBytes(node));
        return path;
    }

    private static void assertInvocation(int status, String stdout, String stderr, String... args) {
        Invocation actual = invoke(args);
        assertEquals(status, actual.exitCode());
        assertEquals(stdout, actual.stdout());
        assertEquals(stderr, actual.stderr());
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            status = StrategyResearchV5CommandAdapter.run(args, out, err);
        }
        return new Invocation(status, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int exitCode, String stdout, String stderr) {}
}
