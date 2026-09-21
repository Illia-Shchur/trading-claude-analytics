package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public expected_replay verifier matrix over one accepted tiny physical replay baseline. */
class LiquidationPortfolioReplayCoreOutputVerificationMatrixV1Test {
    private static final String ENVELOPE = "replay output is not self-hashed and bound to the frozen development inputs";
    private static final String REQUEST = "replay request, disclosed macro policy or frozen source mode does not match its results";
    private static final String STREAM = "event stream identity/count changed after replay";
    private static final String CANDIDATES = "replay evaluated-candidate inventory differs from frozen candidate exposure";

    @TempDir Path temporary;

    @Test
    void expectedCoreReplayRejectsEachIndependentEnvelopeAndLedgerDetachmentBeforeOutcomeReplay() throws Exception {
        Path sourceRoot = Files.createDirectories(temporary.resolve("physical-source"));
        ObjectNode sourceFreeze = LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.buildSmallFreeze(sourceRoot);
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                .put("execution_end_exclusive", "2024-01-04T00:00:00Z");

        Path acceptedRoot = temporary.resolve("accepted-run");
        LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.cloneTree(sourceRoot, acceptedRoot);
        ObjectNode acceptedFreeze = rebindFreeze(sourceFreeze, acceptedRoot);
        Path acceptedPath = acceptedRoot.resolve("accepted-replay.json");
        ObjectNode runOptions = options(acceptedFreeze, request, acceptedPath, null);
        ObjectNode accepted = LiquidationPortfolioReplayV1.run(runOptions);
        assertEquals("SYNTHETIC_DEVELOPMENT_ONLY", accepted.path("status").asText());
        assertEquals(JsonHashes.ownHash(accepted), accepted.path("content_sha256").asText());
        assertTrue(Files.isRegularFile(acceptedPath));
        assertTrue(accepted.path("ledger").path("accounts").isArray());
        assertEquals(3, accepted.path("ledger").path("accounts").size());
        assertTrue(accepted.path("event_stream_identity").isArray());
        assertTrue(accepted.path("account_path_summary").isObject());

        List<Mutation> mutations = mutations();
        assertTrue(mutations.size() >= 30, "matrix should cover the independent public output invariants");
        for (int index = 0; index < mutations.size(); index++) {
            Mutation mutation = mutations.get(index);
            Path caseRoot = temporary.resolve(String.format("case-%02d", index));
            LiquidationPortfolioReplayWindowBoundaryMatrixV1Test.cloneTree(sourceRoot, caseRoot);
            ObjectNode caseFreeze = rebindFreeze(sourceFreeze, caseRoot);
            ObjectNode expected = accepted.deepCopy();
            mutation.apply().accept(expected);
            if (mutation.resealOuter()) reseal(expected);

            Path out = caseRoot.resolve("must-not-publish-replay.json");
            ObjectNode caseOptions = options(caseFreeze, request, out, expected);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationPortfolioReplayV1.runResumable(caseOptions), mutation.label());
            assertEquals(mutation.message(), failure.getMessage(), mutation.label());
            assertFalse(Files.exists(out), mutation.label() + " must fail before economic output publication");
        }
    }

    private List<Mutation> mutations() {
        List<Mutation> result = new ArrayList<>();
        result.add(new Mutation("schema", replay -> replay.put("schema", "liquidation-v2-replay-result/99"), ENVELOPE));
        result.add(new Mutation("version", replay -> replay.put("version", 2), ENVELOPE));
        result.add(new Mutation("stale outer digest", replay -> replay.put("event_count", replay.path("event_count").asLong() + 1),
                ENVELOPE, false));
        result.add(new Mutation("manifest digest", replay -> replay.put("manifest_sha256", "0".repeat(64)), ENVELOPE));
        result.add(new Mutation("dataset-root digest", replay -> replay.put("dataset_root_sha256", "0".repeat(64)), ENVELOPE));
        for (String field : List.of("authoritative", "wfo_permitted", "sealed_confirmation_permitted",
                "prospective_live_permitted", "trade_authorization_permitted")) {
            result.add(new Mutation("authorization flag " + field, replay -> replay.put(field, true), ENVELOPE));
        }
        result.add(new Mutation("nested frozen reference", replay -> ((ObjectNode) replay.path("freeze"))
                .put("profile_sha256", "0".repeat(64)), "replay freeze references do not match the reopened frozen inputs"));
        result.add(new Mutation("request source mode", replay -> ((ObjectNode) replay.path("execution_request"))
                .put("synthetic_smoke", false), REQUEST));
        result.add(new Mutation("result source mode", replay -> replay.put("source_mode", "PROXY_RETROSPECTIVE_DIAGNOSTIC"), CANDIDATES));
        result.add(new Mutation("top-level warmup", replay -> replay.put("feature_warmup_start", "2023-10-02T00:00:00Z"), REQUEST));
        result.add(new Mutation("top-level decision start", replay -> replay.put("replay_start", "2024-01-02T00:00:00Z"), REQUEST));
        result.add(new Mutation("top-level decision end", replay -> replay.put("decision_end_exclusive", "2024-01-05T00:00:00Z"), REQUEST));
        result.add(new Mutation("top-level execution end", replay -> replay.put("replay_end_exclusive", "2024-01-05T00:00:00Z"), REQUEST));
        result.add(new Mutation("disclosed macro policy", replay -> replay.put("default_stage_add_macro_gate_policy", "UNFROZEN"), REQUEST));
        result.add(new Mutation("request warmup", replay -> ((ObjectNode) replay.path("execution_request"))
                .put("feature_warmup_start", "2023-10-02T00:00:00Z"), REQUEST));
        result.add(new Mutation("request decision start", replay -> ((ObjectNode) replay.path("execution_request"))
                .put("replay_start", "2024-01-02T00:00:00Z"), REQUEST));
        result.add(new Mutation("request decision end", replay -> ((ObjectNode) replay.path("execution_request"))
                .put("decision_end_exclusive", "2024-01-05T00:00:00Z"), REQUEST));
        result.add(new Mutation("request replay end", replay -> ((ObjectNode) replay.path("execution_request"))
                .put("replay_end_exclusive", "2024-01-05T00:00:00Z"), REQUEST));
        result.add(new Mutation("request execution end", replay -> ((ObjectNode) replay.path("execution_request"))
                .put("execution_end_exclusive", "2024-01-05T00:00:00Z"), REQUEST));

        result.add(new Mutation("missing event identities", replay -> replay.remove("event_stream_identity"),
                "event stream identity rows are absent"));
        result.add(new Mutation("negative event identity count", replay -> ((ObjectNode) replay.path("event_stream_identity").get(0))
                .put("event_count", -1), "event stream identity has an invalid count"));
        result.add(new Mutation("event identity hash", replay -> replay.put("event_stream_sha256", "0".repeat(64)), STREAM));
        result.add(new Mutation("event identity aggregate count", replay -> replay.put("event_count", replay.path("event_count").asLong() + 1), STREAM));
        result.add(new Mutation("ledger accounts wrong type", replay -> replaceAccounts(replay, JsonHashes.mapper().getNodeFactory().textNode("accounts")),
                "ledger must retain the routed and two control accounts"));
        result.add(new Mutation("ledger accounts missing", replay -> replaceAccounts(replay, JsonHashes.mapper().createArrayNode()),
                "ledger must retain the routed and two control accounts"));
        result.add(new Mutation("routed ledger curve detached", replay -> {
            ObjectNode ledger = (ObjectNode) replay.path("ledger");
            ((ObjectNode) ledger.path("accounts").get(0)).putArray("account_curve").addObject().put("fixture", true);
            rebindLedger(replay, ledger);
        }, "replay account curve differs from the frozen routed ledger path"));
        result.add(new Mutation("top-level routed curve detached", replay -> {
            ArrayNode curve = (ArrayNode) replay.path("account_curve");
            if (curve.isEmpty()) curve.addObject().put("fixture", true);
            else ((ObjectNode) curve.get(0)).put("fixture", true);
        }, "replay account curve differs from the frozen routed ledger path"));
        result.add(new Mutation("adverse path stale digest", replay -> ((ObjectNode) replay.path("account_path_summary"))
                .put("fixture_mutation", true), "adverse account-path summary is not bound to the replay ledger"));
        result.add(new Mutation("adverse path ledger binding", replay -> {
            ObjectNode path = (ObjectNode) replay.path("account_path_summary");
            path.put("ledger_sha256", "0".repeat(64));
            reseal(path);
        }, "adverse account-path summary is not bound to the replay ledger"));

        result.add(new Mutation("candidate variant", replay -> ((ObjectNode) replay.path("evaluated_candidates").get(0))
                .put("variant", "INVENTED_VARIANT"), CANDIDATES));
        result.add(new Mutation("candidate audit-only flag", replay -> {
            ObjectNode candidate = (ObjectNode) replay.path("evaluated_candidates").get(0);
            candidate.put("audit_only", !candidate.path("audit_only").asBoolean());
        }, CANDIDATES));
        result.add(new Mutation("candidate execution scope", replay -> ((ObjectNode) replay.path("evaluated_candidates").get(0))
                .put("execution_scope", "UNBOUND_SCOPE"), CANDIDATES));
        result.add(new Mutation("candidate opportunity count", replay -> ((ObjectNode) replay.path("evaluated_candidates").get(0))
                .put("opportunity_count", replay.path("evaluated_candidates").get(0).path("opportunity_count").asLong() + 1), CANDIDATES));
        return List.copyOf(result);
    }

    private static ObjectNode options(ObjectNode freeze, ObjectNode request, Path out, ObjectNode expectedReplay) {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", out.toString());
        options.set("freeze", freeze.deepCopy());
        options.set("replay_request", request.deepCopy());
        if (expectedReplay != null) options.set("expected_replay", expectedReplay.deepCopy());
        return options;
    }

    private static ObjectNode rebindFreeze(ObjectNode sourceFreeze, Path root) {
        ObjectNode freeze = sourceFreeze.deepCopy().put("physical_root", root.toString());
        reseal(freeze);
        return freeze;
    }

    private static void replaceAccounts(ObjectNode replay, JsonNode accounts) {
        ObjectNode ledger = (ObjectNode) replay.path("ledger");
        ledger.set("accounts", accounts);
        rebindLedger(replay, ledger);
    }

    private static void rebindLedger(ObjectNode replay, ObjectNode ledger) {
        reseal(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private record Mutation(String label, Consumer<ObjectNode> apply, String message, boolean resealOuter) {
        Mutation(String label, Consumer<ObjectNode> apply, String message) { this(label, apply, message, true); }
    }
}
