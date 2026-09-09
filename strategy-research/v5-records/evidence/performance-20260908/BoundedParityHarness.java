package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Scratch packaged-JAR parity audit; kept outside source and test trees. */
public final class BoundedParityHarness {
    private static final long SEED = 202_609_080_001L;
    private static final int EPISODES = 10;

    private BoundedParityHarness() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: BoundedParityHarness <repo-root> <output-dir>");
        }
        Path repo = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        Files.createDirectories(output);

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
        ObjectNode plan = JsonHashes.mapper().createObjectNode().put("test", "bounded-repetition-packaged-parity")
                .put("content_sha256", "");
        plan.put("content_sha256", JsonHashes.ownHash(plan));

        TimedRow eager = invokeEager(plan, baseline, controls, experiment);
        TimedRow bounded = invokeBounded(plan, baseline, controls, experiment);
        ObjectNode eagerRaw = validateRow(eager.row(), "eager");
        ObjectNode boundedRaw = validateRow(bounded.row(), "bounded");
        assertParity(eager.row(), eagerRaw, bounded.row(), boundedRaw);

        writeJson(output.resolve("eager-row.json"), eager.row());
        writeJson(output.resolve("bounded-row.json"), bounded.row());
        ObjectNode audit = JsonHashes.mapper().createObjectNode()
                .put("status", "PASS").put("seed", SEED).put("episodes", EPISODES)
                .put("mode", "PREFIX").put("scenario", "NO_EDGE").put("effect_size", 0D)
                .put("research_jar_executable_sha256", identity.path("executable").path("sha256").asText())
                .put("eager_elapsed_ns", eager.elapsedNanos()).put("bounded_elapsed_ns", bounded.elapsedNanos())
                .put("eager_heap_before", eager.heapBefore()).put("eager_heap_after", eager.heapAfter())
                .put("bounded_heap_before", bounded.heapBefore()).put("bounded_heap_after", bounded.heapAfter())
                .put("eager_rss_before_kb", eager.rssBeforeKb()).put("eager_rss_after_kb", eager.rssAfterKb())
                .put("bounded_rss_before_kb", bounded.rssBeforeKb()).put("bounded_rss_after_kb", bounded.rssAfterKb())
                .put("eager_raw_content_sha256", eagerRaw.path("content_sha256").asText())
                .put("bounded_raw_content_sha256", boundedRaw.path("content_sha256").asText())
                .put("eager_raw_canonical_sha256", eager.row().path("raw_evaluator_result_canonical_sha256").asText())
                .put("bounded_raw_canonical_sha256", bounded.row().path("raw_evaluator_result_canonical_sha256").asText())
                .put("eager_raw_byte_sha256", eager.row().path("raw_evaluator_result_byte_sha256").asText())
                .put("bounded_raw_byte_sha256", bounded.row().path("raw_evaluator_result_byte_sha256").asText())
                .put("control_selection_count", eagerRaw.path("control_selections").size())
                .put("attempt_count", eagerRaw.path("attempts").size())
                .put("trade_count", eagerRaw.path("portfolio").path("event_book").path("trades").size()
                        + eagerRaw.path("portfolio").path("control_book").path("trades").size());
        audit.set("build_identity", identity);
        audit.put("content_sha256", JsonHashes.ownHash(audit));
        writeJson(output.resolve("bounded-parity-audit.json"), audit);
    }

    private static TimedRow invokeBounded(ObjectNode plan, ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment) {
        long heapBefore = usedHeap();
        long rssBefore = rssKb();
        long started = System.nanoTime();
        ObjectNode row = StrategyOperatingCharacteristicsSuccessorV1.evaluateOptimizedReplication(
                baseline, controls, experiment, plan, "PREFIX", "NO_EDGE", 0D, 0, SEED, EPISODES,
                Long.MAX_VALUE, Long.MAX_VALUE);
        long elapsed = System.nanoTime() - started;
        return new TimedRow(row, elapsed, heapBefore, usedHeap(), rssBefore, rssKb());
    }

    private static TimedRow invokeEager(ObjectNode plan, ObjectNode baseline, ObjectNode controls,
            ObjectNode experiment) throws Exception {
        Class<?> cellType = Class.forName(StrategyOperatingCharacteristicsSuccessorV1.class.getName() + "$CellSpec");
        Constructor<?> constructor = cellType.getDeclaredConstructor(String.class, double.class, ArrayNode.class);
        constructor.setAccessible(true);
        Object cell = constructor.newInstance("NO_EDGE", 0D, null);
        Method method = StrategyOperatingCharacteristicsSuccessorV1.class.getDeclaredMethod("runReplication",
                ObjectNode.class, ObjectNode.class, ObjectNode.class, ObjectNode.class, String.class,
                cellType, int.class, long.class, int.class, long.class, long.class);
        method.setAccessible(true);
        long heapBefore = usedHeap();
        long rssBefore = rssKb();
        long started = System.nanoTime();
        ObjectNode row = (ObjectNode) method.invoke(null, plan, baseline, controls, experiment,
                "PREFIX", cell, 0, SEED, EPISODES, Long.MAX_VALUE, Long.MAX_VALUE);
        long elapsed = System.nanoTime() - started;
        return new TimedRow(row, elapsed, heapBefore, usedHeap(), rssBefore, rssKb());
    }

    private static ObjectNode validateRow(ObjectNode row, String label) throws Exception {
        JsonNode rawNode = row.path("raw_evaluator_result");
        if (!rawNode.isObject()) throw new IllegalStateException(label + " raw evaluator result is missing");
        ObjectNode raw = (ObjectNode) rawNode;
        String content = raw.path("content_sha256").asText();
        if (!content.equals(JsonHashes.ownHash(raw))) throw new IllegalStateException(label + " raw own hash mismatch");
        if (!content.equals(row.path("raw_evaluator_result_content_sha256").asText())) {
            throw new IllegalStateException(label + " raw content binding mismatch");
        }
        String canonical = JsonHashes.canonicalSha256(raw);
        if (!canonical.equals(row.path("raw_evaluator_result_canonical_sha256").asText())) {
            throw new IllegalStateException(label + " raw canonical hash mismatch");
        }
        String bytes = JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(raw));
        if (!bytes.equals(row.path("raw_evaluator_result_byte_sha256").asText())) {
            throw new IllegalStateException(label + " raw byte hash mismatch");
        }
        JsonNode receipt = row.path("evaluator_receipt");
        if (!receipt.isObject() || !receipt.path("content_sha256").asText().equals(JsonHashes.ownHash(receipt))) {
            throw new IllegalStateException(label + " evaluator receipt own hash mismatch");
        }
        for (JsonNode selection : raw.path("control_selections")) {
            if (!selection.path("content_sha256").asText().equals(JsonHashes.ownHash(selection))) {
                throw new IllegalStateException(label + " control selection own hash mismatch");
            }
        }
        return raw;
    }

    private static void assertParity(ObjectNode eager, ObjectNode eagerRaw, ObjectNode bounded,
            ObjectNode boundedRaw) {
        if (!eager.path("economic_semantic_sha256").asText()
                .equals(bounded.path("economic_semantic_sha256").asText())) {
            throw new IllegalStateException("economic semantic hash mismatch");
        }
        for (String field : List.of("metrics", "control_selections", "attempts", "portfolio")) {
            if (!eagerRaw.path(field).equals(boundedRaw.path(field))) {
                throw new IllegalStateException("economic field mismatch: " + field);
            }
        }
        ObjectNode eagerNormalized = normalized(eagerRaw);
        ObjectNode boundedNormalized = normalized(boundedRaw);
        if (!eagerNormalized.equals(boundedNormalized)) {
            throw new IllegalStateException("normalized raw evaluator result mismatch");
        }
    }

    private static ObjectNode normalized(ObjectNode raw) {
        ObjectNode copy = raw.deepCopy();
        for (String field : List.of("content_sha256", "semantic_sha256", "economic_semantic_sha256",
                "build_identity", "exposure_head_sha256", "exposure_head_path",
                "executor_identity_sha256", "attempt_identity_sha256", "legacy_exposure_migration")) {
            copy.remove(field);
        }
        stripCustodyPaths(copy);
        return copy;
    }

    private static void stripCustodyPaths(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("path");
            object.remove("physical_root_reference");
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) stripCustodyPaths(object.path(field));
        } else if (node.isArray()) {
            for (JsonNode child : node) stripCustodyPaths(child);
        }
    }

    private static ObjectNode readObject(Path path) throws IOException {
        JsonNode value = JsonHashes.mapper().readTree(Files.readAllBytes(path));
        if (!value.isObject()) throw new IllegalArgumentException("expected object: " + path);
        return (ObjectNode) value;
    }

    private static void writeJson(Path path, ObjectNode value) throws IOException {
        Files.writeString(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long rssKb() {
        try {
            Process process = new ProcessBuilder("ps", "-o", "rss=", "-p",
                    Long.toString(ProcessHandle.current().pid())).start();
            String text = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return text.isBlank() ? -1L : Long.parseLong(text);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private record TimedRow(ObjectNode row, long elapsedNanos, long heapBefore, long heapAfter,
            long rssBeforeKb, long rssAfterKb) { }
}
