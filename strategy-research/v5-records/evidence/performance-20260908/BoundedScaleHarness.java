package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Scratch bounded-only full-geometry development probe. */
public final class BoundedScaleHarness {
    private static final long SEED = 202_609_080_002L;
    private static final int EPISODES = 450;
    private static final long MINUTES_PER_SERIES = 14_400L;
    private static final long MAX_RSS = 3L * 1024L * 1024L * 1024L;
    private static final long WALL_SECONDS = 900L;
    private static final String PLAN_BYTES_SHA256 =
            "3a0edb1da35b2935395f0b895cee7e3c208e99b2b4b7b56930fa857f701a8648";

    private BoundedScaleHarness() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("usage: BoundedScaleHarness <repo-root> <output-dir> <plan>");
        }
        Path repo = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path planPath = Path.of(arguments[2]).toAbsolutePath().normalize();
        Files.createDirectories(output);

        byte[] planBytes = Files.readAllBytes(planPath);
        String planBytesSha256 = JsonHashes.sha256(planBytes);
        if (!PLAN_BYTES_SHA256.equals(planBytesSha256)) {
            throw new IllegalStateException("frozen probe plan bytes changed: " + planBytesSha256);
        }
        ObjectNode frozenPlan = readObject(planBytes);
        requireFrozenProbePlan(frozenPlan);
        ObjectNode plan = frozenPlan.deepCopy();
        plan.put("content_sha256", JsonHashes.ownHash(plan));

        ObjectNode identity = BuildIdentityService.describe(StrategyFixedBaselineV5.class);
        if (!"KNOWN".equals(identity.path("status").asText())
                || !"JAR".equals(identity.path("executable").path("kind").asText())) {
            throw new IllegalStateException("packaged executor identity unavailable: " + identity);
        }
        ObjectNode baseline = readObject(repo.resolve(
                "strategy-research/definitions/fk-deleveraging-absorption/v002.json"));
        ObjectNode controls = readObject(repo.resolve(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json"));
        ObjectNode experiment = readObject(repo.resolve(
                "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json"));

        ObjectNode started = JsonHashes.mapper().createObjectNode()
                .put("schema", "bounded-full-geometry-development-probe/1")
                .put("status", "STARTED").put("confirmation", false)
                .put("target_qualification", false).put("no_strategy_promotion", true)
                .put("seed", SEED).put("scenario", "NO_EDGE").put("effect_size", 0D)
                .put("episodes", EPISODES).put("event_control_series", EPISODES * 2)
                .put("minutes_per_series", MINUTES_PER_SERIES).put("workers", 1)
                .put("worker_heap_bytes", 2L * 1024L * 1024L * 1024L)
                .put("worker_rss_limit_bytes", MAX_RSS).put("wall_limit_seconds", WALL_SECONDS)
                .put("plan_path", planPath.toString()).put("plan_bytes_sha256", planBytesSha256)
                .put("plan_content_sha256", plan.path("content_sha256").asText())
                .put("started_at_utc", Instant.now().toString());
        started.set("executor_identity", identity);
        started.put("content_sha256", JsonHashes.ownHash(started));
        writeJson(output.resolve("bounded-scale-started.json"), started);

        long startedNanos = System.nanoTime();
        long deadline = startedNanos + WALL_SECONDS * 1_000_000_000L;
        try {
            ObjectNode row = StrategyOperatingCharacteristicsSuccessorV1.evaluateOptimizedReplication(
                    baseline, controls, experiment, plan, "FULL", "NO_EDGE", 0D, 0, SEED,
                    EPISODES, deadline, MAX_RSS);
            long elapsedNanos = System.nanoTime() - startedNanos;
            ObjectNode raw = validateRow(row);
            writeJson(output.resolve("bounded-scale-row.json"), row);
            ObjectNode result = result("COMPLETE", started, identity, elapsedNanos, row, raw);
            writeJson(output.resolve("bounded-scale-result.json"), result);
        } catch (Throwable failure) {
            long elapsedNanos = System.nanoTime() - startedNanos;
            writeFailure(output, started, identity, elapsedNanos, failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void requireFrozenProbePlan(ObjectNode plan) {
        if (!"DEVELOPMENT_FULL_GEOMETRY_PERFORMANCE_PROBE".equals(plan.path("kind").asText())
                || plan.path("confirmation").asBoolean(true)
                || plan.path("target_qualification").asBoolean(true)
                || plan.path("seed").asLong() != SEED
                || !"NO_EDGE".equals(plan.path("scenario").asText())
                || plan.path("effect_size").asDouble(Double.NaN) != 0D
                || plan.path("episodes").asInt() != EPISODES
                || plan.path("event_control_series").asInt() != EPISODES * 2
                || plan.path("minutes_per_series").asLong() != MINUTES_PER_SERIES
                || plan.path("cluster_count").asInt() != 288
                || plan.path("repetitions").asInt() != 1
                || plan.path("workers").asInt() != 1
                || plan.path("worker_heap_bytes").asLong() != 2L * 1024L * 1024L * 1024L
                || plan.path("worker_rss_limit_bytes").asLong() != MAX_RSS
                || plan.path("wall_limit_seconds").asLong() != WALL_SECONDS) {
            throw new IllegalStateException("probe plan is not the frozen bounded full-geometry contract");
        }
    }

    private static ObjectNode result(String status, ObjectNode started, ObjectNode identity,
            long elapsedNanos, ObjectNode row, ObjectNode raw) throws IOException {
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "bounded-full-geometry-development-probe-result/1")
                .put("status", status).put("confirmation", false)
                .put("target_qualification", false).put("no_strategy_promotion", true)
                .put("seed", SEED).put("scenario", "NO_EDGE").put("effect_size", 0D)
                .put("episodes", EPISODES).put("event_control_series", EPISODES * 2)
                .put("minutes_per_series", MINUTES_PER_SERIES)
                .put("elapsed_nanos", elapsedNanos)
                .put("elapsed_seconds", elapsedNanos / 1_000_000_000D)
                .put("raw_content_sha256", raw.path("content_sha256").asText())
                .put("raw_canonical_sha256", row.path("raw_evaluator_result_canonical_sha256").asText())
                .put("raw_byte_sha256", row.path("raw_evaluator_result_byte_sha256").asText())
                .put("economic_semantic_sha256", row.path("economic_semantic_sha256").asText())
                .put("event_count", row.path("event_count").asInt())
                .put("paired_count", row.path("paired_count").asInt())
                .put("independent_units", row.path("independent_units").asInt())
                .put("row_bytes", JsonHashes.mapper().writeValueAsBytes(row).length);
        result.set("metrics", raw.path("metrics").deepCopy());
        result.set("portfolio", raw.path("portfolio").deepCopy());
        result.set("executor_identity", identity);
        result.set("started", started);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static void writeFailure(Path output, ObjectNode started, ObjectNode identity,
            long elapsedNanos, Throwable failure) {
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "bounded-full-geometry-development-probe-result/1")
                .put("status", "FAILED").put("confirmation", false)
                .put("target_qualification", false).put("no_strategy_promotion", true)
                .put("seed", SEED).put("scenario", "NO_EDGE").put("effect_size", 0D)
                .put("episodes", EPISODES).put("event_control_series", EPISODES * 2)
                .put("minutes_per_series", MINUTES_PER_SERIES)
                .put("elapsed_nanos", elapsedNanos)
                .put("elapsed_seconds", elapsedNanos / 1_000_000_000D)
                .put("error_class", failure.getClass().getName())
                .put("error_message", failure.getMessage() == null ? "" : failure.getMessage());
        result.set("executor_identity", identity);
        result.set("started", started);
        try {
            Files.writeString(output.resolve("bounded-scale-stacktrace.log"), stackTrace(failure));
            result.put("content_sha256", JsonHashes.ownHash(result));
            writeJson(output.resolve("bounded-scale-result.json"), result);
        } catch (IOException io) {
            io.printStackTrace(System.err);
        }
    }

    private static ObjectNode validateRow(ObjectNode row) throws IOException {
        JsonNode rawNode = row.path("raw_evaluator_result");
        if (!rawNode.isObject()) throw new IllegalStateException("raw evaluator result is missing");
        ObjectNode raw = (ObjectNode) rawNode;
        String content = raw.path("content_sha256").asText();
        if (!content.equals(JsonHashes.ownHash(raw))) throw new IllegalStateException("raw own hash mismatch");
        if (!content.equals(row.path("raw_evaluator_result_content_sha256").asText())) {
            throw new IllegalStateException("raw content hash binding mismatch");
        }
        String canonical = JsonHashes.canonicalSha256(raw);
        if (!canonical.equals(row.path("raw_evaluator_result_canonical_sha256").asText())) {
            throw new IllegalStateException("raw canonical hash mismatch");
        }
        String bytes = JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(raw));
        if (!bytes.equals(row.path("raw_evaluator_result_byte_sha256").asText())) {
            throw new IllegalStateException("raw serialized byte hash mismatch");
        }
        JsonNode receipt = row.path("evaluator_receipt");
        if (!receipt.isObject() || !receipt.path("content_sha256").asText().equals(JsonHashes.ownHash(receipt))) {
            throw new IllegalStateException("evaluator receipt own hash mismatch");
        }
        for (JsonNode selection : raw.path("control_selections")) {
            if (!selection.path("content_sha256").asText().equals(JsonHashes.ownHash(selection))) {
                throw new IllegalStateException("control selection own hash mismatch");
            }
        }
        return raw;
    }

    private static ObjectNode readObject(byte[] bytes) throws IOException {
        JsonNode value = JsonHashes.mapper().readTree(bytes);
        if (!value.isObject()) throw new IllegalArgumentException("expected object input");
        return (ObjectNode) value;
    }

    private static ObjectNode readObject(Path path) throws IOException {
        return readObject(Files.readAllBytes(path));
    }

    private static void writeJson(Path path, ObjectNode value) throws IOException {
        Files.writeString(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private static String stackTrace(Throwable failure) {
        java.io.StringWriter text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }
}
