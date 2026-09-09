package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the supported command boundary, not only the in-process calculator. */
final class StrategyProspectiveOutcomeCommandReviewTest {
    @Test
    void signalThenMatureOutcomeThenIdenticalRetryDoesNotDuplicateEitherEvent() throws Exception {
        Fixture f = fixture();
        assertThat(run(f, f.options, false).status()).isEqualTo("COMPLETE");
        assertThat(count(f)).isEqualTo(1);
        Result outcome = run(f, f.options, true);
        assertThat(outcome.status()).as(outcome.error).isEqualTo("COMPLETE");
        java.util.Set<String> inputDigests = new java.util.HashSet<>();
        outcome.value.path("receipt").path("inputs").forEach(row -> inputDigests.add(row.path("byte_sha256").asText()));
        for (String field : List.of("outcomeResolutionSha256", "outcomeReceiptSha256", "outcomeResolutionSourceSha256",
                "labelSourceSha256", "executionSourceSha256", "numericReconciliationSha256", "numericReconciliationInputSha256")) {
            assertThat(inputDigests).as("Durable command input inventory must retain %s", field)
                    .contains(f.options.path(field).asText());
        }
        assertThat(count(f)).isEqualTo(2);
        byte[] head = Files.readAllBytes(head(f));
        assertThat(run(f, f.options, true).status()).isEqualTo("NO_NEW_MATURE_OUTCOME");
        assertThat(Files.readAllBytes(head(f))).isEqualTo(head);
    }

    @Test
    void olderSignalCanMatureAfterANewerSignalWasRecorded() throws Exception {
        Fixture f = fixture();
        assertThat(run(f, f.options, false).status()).isEqualTo("COMPLETE");
        ObjectNode newer = f.options.deepCopy();
        String at = "2026-01-01T04:00:00.000Z";
        ObjectNode source = read(Path.of(newer.path("sourceReceiptPath").asText()));
        source.put("completed_bar_id", "bar-2").put("availability_time", at)
                .put("bar_start", "2026-01-01T00:00:00.000Z").put("bar_end", at);
        Path sourcePath = f.root.resolve("bar2-source.json");
        String sourceSha = writeHashed(sourcePath, source);
        ObjectNode signal = read(Path.of(newer.path("signalDecisionPath").asText()));
        signal.put("completed_bar_id", "bar-2").put("source_receipt_sha256", sourceSha)
                .put("availability_cutoff_time", at).put("decision_time", at);
        Path signalPath = f.root.resolve("bar2-signal.json");
        String signalSha = writeHashed(signalPath, signal);
        newer.put("sourceReceiptPath", sourcePath.toString()).put("sourceReceiptSha256", sourceSha)
                .put("signalDecisionPath", signalPath.toString()).put("signalDecisionSha256", signalSha);
        ((ObjectNode) newer.path("bar")).put("completed_bar_id", "bar-2").put("availability_time", at);
        Result next = run(f, newer, false);
        assertThat(next.status()).as(next.error).isEqualTo("COMPLETE");
        assertThat(count(f)).isEqualTo(2);
        Result mature = run(f, f.options, true);
        assertThat(mature.status()).as(mature.error).isEqualTo("COMPLETE");
        assertThat(count(f)).isEqualTo(3);
    }

    @Test
    void retryReopensNumericBytesEvenWhenTheAdvertisedDigestIsUnchanged() throws Exception {
        Fixture f = fixture();
        assertThat(run(f, f.options, false).status()).isEqualTo("COMPLETE");
        assertThat(run(f, f.options, true).status()).isEqualTo("COMPLETE");
        byte[] before = Files.readAllBytes(head(f));
        Files.writeString(Path.of(f.options.path("numericReconciliationPath").asText()), "{}");
        assertRejected(run(f, f.options, true));
        assertThat(Files.readAllBytes(head(f))).isEqualTo(before);
    }

    @Test
    void changedOutcomeReceiptIsNotAnIdenticalRetry() throws Exception {
        Fixture f = fixture();
        assertThat(run(f, f.options, false).status()).isEqualTo("COMPLETE");
        assertThat(run(f, f.options, true).status()).isEqualTo("COMPLETE");
        byte[] before = Files.readAllBytes(head(f));
        Path receiptPath = Path.of(f.options.path("outcomeReceiptPath").asText());
        ObjectNode receipt = read(receiptPath);
        receipt.put("payload_sha256", "d".repeat(64));
        f.options.put("outcomeReceiptSha256", writeHashed(receiptPath, receipt));
        assertRejected(run(f, f.options, true));
        assertThat(Files.readAllBytes(head(f))).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void outcomeMustRemainBoundToTheOriginallyCommittedSignal(boolean alreadyResolved) throws Exception {
        Fixture f = fixture();
        assertThat(run(f, f.options, false).status()).isEqualTo("COMPLETE");
        if (alreadyResolved) assertThat(run(f, f.options, true).status()).isEqualTo("COMPLETE");
        byte[] before = Files.readAllBytes(head(f));
        Path candidatePath = Path.of(f.options.path("candidateSetPath").asText());
        ObjectNode candidate = read(candidatePath);
        ((ObjectNode) candidate.path("generator").path("provenance")).put("scope", "REHASHED_DIFFERENT_SOURCE");
        String candidateSha = writeHashed(candidatePath, candidate);
        f.options.put("candidateSetSha256", candidateSha);
        Path decisionPath = Path.of(f.options.path("signalDecisionPath").asText());
        ObjectNode decision = read(decisionPath).put("candidate_set_sha256", candidateSha);
        f.options.put("signalDecisionSha256", writeHashed(decisionPath, decision));
        assertRejected(run(f, f.options, true));
        assertThat(Files.readAllBytes(head(f))).isEqualTo(before);
    }

    @Test
    void invalidNumericSourceBeforeFirstOutcomeLeavesOnlyTheOriginalSignal() throws Exception {
        Fixture f = fixture();
        assertThat(run(f, f.options, false).status()).isEqualTo("COMPLETE");
        byte[] before = Files.readAllBytes(head(f));
        Files.writeString(Path.of(f.options.path("executionSourcePath").asText()), "{}");
        assertRejected(run(f, f.options, true));
        assertThat(Files.readAllBytes(head(f))).isEqualTo(before);
        assertThat(count(f)).isEqualTo(1);
    }

    private static void assertRejected(Result result) {
        assertThat(result.exitCode != 0 || "BLOCKED".equals(result.status()))
                .as("Invalid physical input must reject, got %s: %s", result.status(), result.error).isTrue();
    }
    private record Fixture(ObjectNode options, Path root) {}
    private record Result(int exitCode, ObjectNode value, String error) {
        String status() { return value.path("status").asText(""); }
    }
    private static Fixture fixture() throws Exception {
        Method factory = StrategyProspectiveTypedBoundaryReviewTest.class.getDeclaredMethod("fixture");
        factory.setAccessible(true); Object value = factory.invoke(null);
        Method options = value.getClass().getDeclaredMethod("options"), root = value.getClass().getDeclaredMethod("root");
        options.setAccessible(true); root.setAccessible(true);
        ObjectNode o = (ObjectNode) options.invoke(value);
        Path reservationPath = Path.of(o.path("reservationPath").asText());
        ObjectNode reservation = read(reservationPath);
        // The older low-level oracle fixture includes an extra convenience field.
        reservation.remove("lineage");
        String reservationSha = writeHashed(reservationPath, reservation);
        o.put("reservationSha256", reservationSha);
        Path featurePath = Path.of(o.path("featureInputPath").asText());
        ObjectNode feature = read(featurePath);
        feature.remove(List.of("data_manifest_sha256", "feature_code_sha256"));
        feature.putObject("series").put("asset", "btc").put("instrument", "SPOT")
                .put("symbol", "BTCUSDT").put("interval", "4h");
        String featureSha = writeHashed(featurePath, feature);
        o.put("featureInputSha256", featureSha);
        Path candidatePath = Path.of(o.path("candidateSetPath").asText());
        ObjectNode candidate = read(candidatePath);
        ((ObjectNode) candidate.path("generator")).putObject("provenance").put("scope", "REVIEW_FIXTURE");
        ((ObjectNode) candidate.path("candidates").get(0)).put("hypothesis_index", 0).put("generator", "GRID");
        String candidateSha = writeHashed(candidatePath, candidate);
        o.put("candidateSetSha256", candidateSha);
        Path evaluatorPath = Path.of(o.path("evaluatorCodePath").asText());
        ObjectNode evaluator = read(evaluatorPath).put("strategy_family", "command-review-fixture")
                .put("precommit_sha256", candidate.path("precommit_sha256").asText())
                .put("gene_space_sha256", "a".repeat(64)).put("predictor_registry_sha256", "b".repeat(64));
        evaluator.putObject("candidate_template").put("direction", "long").put("entry_policy", "NEXT_BAR_OPEN")
                .put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 86_400_000L).put("exit_policy", "TIME_STOP");
        evaluator.putObject("execution_contract").put("entry_policy", "NEXT_BAR_OPEN").put("completed_bar_only", true)
                .put("child_interval_ms", 60_000).put("collision_policy", "ADVERSE_STOP_FIRST").put("outage_policy", "FAIL")
                .put("gap_policy", "FILL_AT_OPEN").put("capacity_input_contract", "NOTIONAL_LE_AVAILABLE_LIQUIDITY_X_PARTICIPATION_CAP")
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h");
        String evaluatorSha = writeHashed(evaluatorPath, evaluator);
        o.put("evaluatorCodeSha256", evaluatorSha);
        Path decisionPath = Path.of(o.path("signalDecisionPath").asText());
        ObjectNode decision = read(decisionPath).put("reservation_sha256", reservationSha)
                .put("feature_input_sha256", featureSha).put("candidate_set_sha256", candidateSha)
                .put("evaluator_code_sha256", evaluatorSha);
        o.put("signalDecisionSha256", writeHashed(decisionPath, decision));
        return new Fixture(o, (Path) root.invoke(value));
    }
    private static Path head(Fixture f) { return Path.of(f.options.path("path").asText()).resolve("HEAD.json"); }
    private static int count(Fixture f) { return StrategyProspectiveV5.readProspectiveLedger(f.options.path("path").asText(),
            JsonHashes.mapper().createObjectNode().set("nowAt", f.options.path("nowAt"))).path("sequence").asInt(); }
    private static ObjectNode read(Path path) throws Exception { return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path)); }
    private static String writeHashed(Path path, ObjectNode value) throws Exception {
        value.put("content_sha256", JsonHashes.ownHash(value));
        byte[] bytes = NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes); return JsonHashes.sha256(bytes);
    }
    private static Result run(Fixture f, ObjectNode options, boolean outcome) throws Exception {
        Path bar = f.root.resolve("command-" + options.path("bar").path("completed_bar_id").asText() + ".json");
        writeHashed(bar, ((ObjectNode) options.path("bar")).deepCopy());
        List<String> args = new ArrayList<>(List.of("prospective-runner", "--record-root", f.root.resolve("receipts").toString(),
                "--ledger", options.path("path").asText(), "--bar", bar.toString(), "--expected-head-sha256",
                read(head(f)).path("head_sha256").asText(), "--now-at", java.time.Instant.ofEpochMilli(options.path("nowAt").asLong()).toString()));
        for (Map.Entry<String,String> pair : Map.of("reservation", "reservationPath", "source-receipt", "sourceReceiptPath",
                "feature-input", "featureInputPath", "candidate-set", "candidateSetPath", "evaluator-code", "evaluatorCodePath",
                "signal-decision", "signalDecisionPath").entrySet()) {
            args.add("--" + pair.getKey()); args.add(options.path(pair.getValue()).asText());
        }
        if (outcome) {
            for (String field : List.of("outcomeResolutionPath", "outcomeResolutionSha256", "outcomeReceiptPath",
                    "outcomeReceiptSha256", "outcomeResolutionSourcePath", "outcomeResolutionSourceSha256",
                    "labelSourcePath", "labelSourceSha256", "executionSourcePath", "executionSourceSha256",
                    "numericReconciliationPath", "numericReconciliationSha256", "numericReconciliationInputPath",
                    "numericReconciliationInputSha256")) {
                args.add("--" + field.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase());
                args.add(options.path(field).asText());
            }
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        int status = StrategyResearchV5CommandAdapter.run(args.toArray(String[]::new), new PrintStream(stdout), new PrintStream(stderr));
        ObjectNode value = stdout.size() == 0 ? JsonHashes.mapper().createObjectNode() :
                (ObjectNode) JsonHashes.mapper().readTree(stdout.toByteArray());
        if (status != 0) System.err.println("Command review rejection: " + stderr.toString(StandardCharsets.UTF_8));
        return new Result(status, value, stderr.toString(StandardCharsets.UTF_8));
    }
}
