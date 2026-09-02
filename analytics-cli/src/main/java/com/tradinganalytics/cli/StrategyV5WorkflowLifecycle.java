package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.github.GitHubAttestationSignerV5;
import com.tradinganalytics.infrastructure.security.SafeTreeVerifier;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.tradinganalytics.cli.StrategyV5WorkflowJson.*;
import static com.tradinganalytics.cli.StrategyV5WorkflowPaths.*;
import static com.tradinganalytics.cli.StrategyV5WorkflowReceipts.*;

/** Cycle, audit, and attestation lifecycle modes. */
final class StrategyV5WorkflowLifecycle {
    private StrategyV5WorkflowLifecycle() {}

    static int cycle(Map<String, String> flags, Map<String, String> env, Path work, PrintStream out) {
        Path receiptPath = path(flags.getOrDefault("receipt",
                "v5-shadow-cycle-receipt.json"), work);
        String bundle = first(flags.get("bundle"), env.get("V5_SOURCE_BUNDLE_INPUT"),
                env.get("V5_PROSPECTIVE_SOURCE_BUNDLE"));
        if (bundle == null || bundle.isBlank()) {
            ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED", List.of(),
                    List.of("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED"),
                    object().put("mode", "BLOCKED_LIVE_SOURCE_UNCONFIGURED")
                            .put("reason", "no verified frozen Binance completed-4h acquisition adapter is configured for this environment"));
            writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode output = object().putNull("result");
            output.set("receipt", receipt);
            output.put("status", "BLOCKED");
            out.println(pretty(output));
            return 1;
        }
        try {
            WorkflowSecurityV5.SourceBundleVerification verified =
                    WorkflowSecurityV5.verifyProspectiveSourceBundle(work, bundle);
            Map<String, WorkflowSecurityV5.ConfinedJson> refs = verified.references();
            Path ledger = flags.containsKey("ledger")
                    ? path(flags.get("ledger"), work) : verified.ledger().absolute();
            if (flags.containsKey("ledger")) {
                WorkflowSecurityV5.verifySafeTree(ledger, "prospective ledger",
                        SafeTreeVerifier.Options.EVIDENCE);
            }
            Path reservation = refs.get("reservation").absolute();
            Path source = refs.get("source_receipt").absolute();
            Path barPath = refs.get("bar").absolute();
            Path feature = refs.get("feature_input").absolute();
            Path candidate = refs.get("candidate_set").absolute();
            Path evaluator = refs.get("evaluator_code").absolute();
            Path decision = refs.get("signal_decision").absolute();
            if (flags.containsKey("reservation")
                    && !path(flags.get("reservation"), work).equals(reservation))
                throw new IllegalArgumentException("explicit reservation path conflicts with the frozen source bundle");
            List<String> missing = new ArrayList<>();
            if (!Files.exists(ledger.resolve("HEAD.json"), LinkOption.NOFOLLOW_LINKS))
                missing.add("ledger: HEAD.json path does not exist: " + ledger.resolve("HEAD.json"));
            if (flags.containsKey("expected-head")
                    && !HASH.matcher(flags.get("expected-head")).matches())
                missing.add("expected CAS head: must be a SHA-256 head hash");
            if (!missing.isEmpty()) {
                ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED",
                        sourceInputs(verified), missing,
                        object().put("mode", "BLOCKED_NO_PRIVATE_KEY_PATH")
                                .put("reason", "one completed-bar SHADOW cycle requires every physical ledger/reservation/source/bar/feature/candidate/evaluator/decision prerequisite"));
                writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
                ObjectNode output = object().putNull("result");
                output.set("receipt", receipt);
                output.put("status", "BLOCKED");
                out.println(pretty(output));
                return 1;
            }
            ObjectNode bar = object(readObject(barPath), "completed bar");
            ObjectNode ledgerBefore = StrategyProspectiveV5.readProspectiveLedger(ledger,
                    object().put("nowAt", System.currentTimeMillis()).put("allowFuture", true));
            String bundleLineage = text(verified.bundle().get("lineage_sha256"));
            if (!bundleLineage.equals(text(ledgerBefore.get("lineage_sha256"))))
                throw new IllegalArgumentException(
                        "hydrated prospective ledger lineage differs from frozen source bundle");
            String genesis = StrategyProspectiveV5.hash(object().put(
                    "schema", "strategy-prospective-ledger-genesis/1")
                    .put("lineage_sha256", bundleLineage));
            if (ledgerBefore.path("sequence").asInt() == 0
                    && !text(ledgerBefore.get("current_head_sha256"))
                            .equals(text(verified.bundle().get("expected_head_sha256")))
                    || ledgerBefore.path("sequence").asInt() > 0
                    && !genesis.equals(text(ledgerBefore.path("events").get(0)
                            .get("previous_head_sha256"))))
                throw new IllegalArgumentException(
                        "prospective ledger chain is not anchored to the frozen genesis");
            String expectedHead = first(flags.get("expected-head"),
                    text(ledgerBefore.get("current_head_sha256")));
            if (!expectedHead.equals(text(ledgerBefore.get("current_head_sha256"))))
                throw new IllegalArgumentException(
                        "explicit expected CAS head differs from hydrated prospective ledger");
            ObjectNode noOpOptions = object();
            noOpOptions.set("ledger", ledgerBefore);
            noOpOptions.set("bar", bar);
            noOpOptions.put("sourceReceiptSha256", StrategyProspectiveV5.hash(refs.get("source_receipt").bytes()))
                    .put("signalDecisionSha256", StrategyProspectiveV5.hash(refs.get("signal_decision").bytes()))
                    .put("reservationSha256", StrategyProspectiveV5.hash(refs.get("reservation").bytes()))
                    .put("candidateSetSha256", StrategyProspectiveV5.hash(refs.get("candidate_set").bytes()))
                    .put("evaluatorCodeSha256", StrategyProspectiveV5.hash(refs.get("evaluator_code").bytes()))
                    .put("featureInputSha256", StrategyProspectiveV5.hash(refs.get("feature_input").bytes()));
            if (StrategyProspectiveV5.verifyCompletedBarNoOp(noOpOptions)) {
                ObjectNode receipt = commandReceipt("prospective-runner", "COMPLETE",
                        sourceInputs(verified), List.of("NO_NEW_COMPLETED_BAR: exact latest completed 4h bar and all source/decision bindings already exist; no append or PR created"),
                        object().put("mode", "NO_NEW_COMPLETED_BAR")
                                .put("ledger_head_sha256", text(ledgerBefore.get("current_head_sha256")))
                                .put("ledger_sequence", ledgerBefore.path("sequence").asInt()));
                writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
                ObjectNode output = object().putNull("result");
                output.set("receipt", receipt);
                output.put("status", "NO_NEW_COMPLETED_BAR").put("no_op", true);
                out.println(pretty(output));
                return 0;
            }
            ObjectNode append = object().put("path", ledger.toString())
                    .put("reservationPath", reservation.toString())
                    .put("reservationSha256", StrategyProspectiveV5.hash(refs.get("reservation").bytes()))
                    .put("sourceReceiptPath", source.toString())
                    .put("sourceReceiptSha256", StrategyProspectiveV5.hash(refs.get("source_receipt").bytes()))
                    .put("featureInputPath", feature.toString())
                    .put("featureInputSha256", StrategyProspectiveV5.hash(refs.get("feature_input").bytes()))
                    .put("candidateSetPath", candidate.toString())
                    .put("candidateSetSha256", StrategyProspectiveV5.hash(refs.get("candidate_set").bytes()))
                    .put("evaluatorCodePath", evaluator.toString())
                    .put("evaluatorCodeSha256", StrategyProspectiveV5.hash(refs.get("evaluator_code").bytes()))
                    .put("signalDecisionPath", decision.toString())
                    .put("signalDecisionSha256", StrategyProspectiveV5.hash(refs.get("signal_decision").bytes()))
                    .put("expectedHeadSha256", expectedHead)
                    .put("nowAt", System.currentTimeMillis());
            append.set("bar", bar);
            ObjectNode result = StrategyProspectiveV5.appendCompletedBarCycle(append);
            ObjectNode ledgerAfter = StrategyProspectiveV5.readProspectiveLedger(ledger,
                    object().put("nowAt", System.currentTimeMillis()).put("allowFuture", true));
            ObjectNode receipt = commandReceipt("prospective-runner", "COMPLETE",
                    sourceInputs(verified),
                    List.of(reference(ledger.resolve("HEAD.json"), work, "prospective_ledger_head")),
                    List.of("SHADOW only; no activation or private key path is available"),
                    object().put("mode", "ONE_COMPLETED_BAR_SHADOW_CYCLE")
                            .put("ledger_prior_head_sha256", text(ledgerBefore.get("current_head_sha256")))
                            .put("ledger_new_head_sha256", text(ledgerAfter.get("current_head_sha256")))
                            .put("ledger_sequence", ledgerAfter.path("sequence").asInt())
                            .put("activated", false));
            writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode output = object();
            output.set("result", result);
            output.set("receipt", receipt);
            output.put("status", "COMPLETE");
            out.println(pretty(output));
            return 0;
        } catch (Exception error) {
            ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED",
                    bestEffortSourceBundleInput(bundle, work),
                    List.of("COMPLETED_BAR_CYCLE_BLOCKED: " + CliMessages.rootCauseMessage(error)),
                    object().put("mode", "BLOCKED_CYCLE_RECOMPUTATION_OR_CUSTODY")
                            .put("reason", CliMessages.rootCauseMessage(error)));
            writeExclusive(receiptPath, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode output = object().putNull("result");
            output.set("receipt", receipt);
            output.put("status", "BLOCKED");
            out.println(pretty(output));
            return 1;
        }
    }

    private static List<ObjectNode> bestEffortSourceBundleInput(String bundle, Path work) {
        if (bundle == null || bundle.isBlank()) return List.of();
        try {
            WorkflowSecurityV5.ConfinedJson physical = WorkflowSecurityV5.readConfinedJson(
                    work, bundle, "prospective source bundle");
            return List.of(reference(physical, "source_bundle"));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    static int requireCycle(Map<String, String> flags, Path work, PrintStream out) {
        ObjectNode receipt = readObject(path(flags.getOrDefault("receipt",
                "v5-shadow-cycle-receipt.json"), work));
        validateCommandReceipt(receipt);
        if ("COMPLETE".equals(text(receipt.get("status")))
                && !receipt.path("details").path("active").asBoolean(true)) return 0;
        if ("BLOCKED".equals(text(receipt.get("status")))
                && rows(receipt.get("limitations")).stream().anyMatch(row ->
                        "PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED".equals(text(row)))) {
            out.println("PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED: no verified frozen Binance adapter is configured");
        }
        throw new IllegalArgumentException("Completed-bar SHADOW cycle is blocked: "
                + String.join("; ", stringRows(receipt.get("limitations"))));
    }

    static int audit(String mode, Map<String, String> flags, Map<String, String> env,
                     Path work, PrintStream out) {
        ObjectNode audit = StrategyV5WorkflowDeployment.makeDeploymentAudit(env, work);
        Path output = path(flags.getOrDefault("out",
                "early-audit".equals(mode) ? "v5-deployment-audit-early.json"
                        : "v5-deployment-audit.json"), work);
        writeExclusive(output, (pretty(audit) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(audit));
        return audit.path("blocked").asBoolean(true)
                && !audit.path("shadow_append_eligible").asBoolean(false) ? 1 : 0;
    }

    static int noOpAudit(Map<String, String> flags, Path work, PrintStream out) {
        Path earlyPath = path(flags.getOrDefault("early", "v5-deployment-audit-early.json"), work);
        ObjectNode early = readObject(earlyPath);
        if (!validOwnHash(early)) throw new IllegalArgumentException("early deployment audit is tampered");
        ObjectNode audit = early.deepCopy();
        audit.with("checks").put("no_new_completed_bar", true);
        audit.put("shadow_append_eligible", false).put("activation_eligible", false)
                .put("blocked", false)
                .put("reason", "NO_NEW_COMPLETED_BAR: no append, attestation, publication, or deployment transition was requested")
                .put("blocked_until_external_prerequisites", false)
                .put("content_sha256", StrategyProspectiveV5.ownHash(audit));
        Path output = path(flags.getOrDefault("out", "v5-deployment-audit.json"), work);
        writeExclusive(output, (pretty(audit) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(audit));
        return 0;
    }

    static int signAttestation(Map<String, String> env, Path work, PrintStream out) {
        GitHubAttestationSignerV5.Result result = GitHubAttestationSignerV5.sign(
                new GitHubAttestationSignerV5.Options(work, env, Clock.systemUTC(), null));
        Path registry = path(env.get("V5_ATTESTATION_KEY_REGISTRY_PATH"), work);
        Path copy = path(env.getOrDefault("V5_ATTESTATION_REGISTRY_OUT",
                "v5-attestation-key-registry.json"), work);
        copyExclusive(registry, copy);
        out.println(pretty(result.summary()));
        return 0;
    }

    static int blockedAttestation(Map<String, String> flags, Path work, PrintStream out) {
        ObjectNode receipt = commandReceipt("prospective-runner", "BLOCKED", List.of(), List.of(
                "ACTIONS_ATTESTATION_KEY_OR_REGISTRY_UNCONFIGURED"), object()
                        .put("mode", "BLOCKED_MISSING_PROTECTED_KEY_OR_FROZEN_REGISTRY"));
        Path output = path(flags.getOrDefault("out", "v5-actions-attestation-receipt.json"), work);
        writeExclusive(output, (pretty(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println(pretty(receipt));
        return 1;
    }
}
