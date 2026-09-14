import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Compact streaming summaries for diagnostic JFR evidence. */
public final class JfrProfileReport {
    private static final class Stats {
        long count;
        long amount;
        long durationNanos;
        long maxDurationNanos;

        void add(long value, long duration) {
            count++;
            amount += value;
            durationNanos += duration;
            maxDurationNanos = Math.max(maxDurationNanos, duration);
        }
    }

    private static final class Summary {
        final Map<String, Stats> executionByPhase = new TreeMap<>();
        final Map<String, Stats> executionByMethod = new TreeMap<>();
        final Map<String, Stats> allocationByPhase = new TreeMap<>();
        final Map<String, Stats> allocationByMethod = new TreeMap<>();
        final Map<String, Stats> ioByKind = new TreeMap<>();
        final Map<String, Stats> ioByPathBucket = new TreeMap<>();
        final Map<String, Stats> ioByMethod = new TreeMap<>();
        long gcCount;
        long gcDurationNanos;
    }

    private JfrProfileReport() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("usage: JfrProfileReport <recording.jfr>");
        Path source = Path.of(args[0]);
        Summary summary = new Summary();
        try (RecordingFile recording = new RecordingFile(source)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (type.equals("jdk.ExecutionSample")) {
                    String method = topMethod(event.getStackTrace());
                    String phase = classify(event.getStackTrace());
                    add(summary.executionByPhase, phase, 0, 0);
                    add(summary.executionByMethod, method, 0, 0);
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    String method = topMethod(event.getStackTrace());
                    String phase = classify(event.getStackTrace());
                    long weight = event.getLong("weight");
                    add(summary.allocationByPhase, phase, weight, 0);
                    add(summary.allocationByMethod, method, weight, 0);
                } else if (type.equals("jdk.FileRead") || type.equals("jdk.FileWrite")) {
                    String kind = type.equals("jdk.FileRead") ? "read" : "write";
                    String path = event.getString("path");
                    long bytes = event.getLong(type.equals("jdk.FileRead") ? "bytesRead" : "bytesWritten");
                    long duration = event.getDuration().toNanos();
                    String method = topMethod(event.getStackTrace());
                    add(summary.ioByKind, kind, bytes, duration);
                    add(summary.ioByPathBucket, kind + ":" + bucket(path), bytes, duration);
                    add(summary.ioByMethod, kind + ":" + method, bytes, duration);
                } else if (type.equals("jdk.GarbageCollection")) {
                    summary.gcCount++;
                    summary.gcDurationNanos += event.getDuration().toNanos();
                }
            }
        }
        System.out.println(toJson(source, summary));
    }

    private static void add(Map<String, Stats> target, String key, long amount, long duration) {
        target.computeIfAbsent(key, ignored -> new Stats()).add(amount, duration);
    }

    private static String topMethod(RecordedStackTrace trace) {
        if (trace == null || trace.getFrames().isEmpty()) return "<no-stack>";
        return methodName(trace.getFrames().get(0).getMethod());
    }

    private static String methodName(RecordedMethod method) {
        if (method == null) return "<unknown>";
        return method.getType().getName() + "." + method.getName();
    }

    private static String classify(RecordedStackTrace trace) {
        if (trace == null) return "OTHER";
        boolean jackson = false;
        boolean jcs = false;
        boolean worker = false;
        for (RecordedFrame frame : trace.getFrames()) {
            String name = frame.getMethod().getType().getName();
            if (name.contains("JsonHashes") || name.contains("CanonicalJson")
                    || name.contains("JsonCanonicalizer") || name.contains("JsonDecoder")
                    || name.contains("NumberFastDtoa")) return "HASHING_CANONICALIZATION";
            if (name.contains("StrategyFixedBaselineCorrectedV1")
                    || name.contains("StrategyFixedBaselinePortfolioCorrectionV1")) {
                return "CORRECTION_CURVE";
            }
            if (name.contains("TradeLifecycleV5") || name.contains("StrategyFixedBaselineV5")) {
                return "GENERATION_LIFECYCLE";
            }
            if (name.contains("StrategyOperatingCharacteristicsSuccessorV1")
                    || name.contains("StrategyOperatingCharacteristicsParallelV1")) worker = true;
            if (name.startsWith("com.fasterxml.jackson.")) jackson = true;
            if (name.startsWith("org.erdtman.jcs.")) jcs = true;
        }
        if (worker) return "WORKER_ASSEMBLY_PUBLICATION";
        if (jackson || jcs) return "JSON_SERIALIZATION_OR_PARSING";
        return "OTHER";
    }

    private static String bucket(String path) {
        if (path == null || path.isBlank()) return "path unavailable";
        if (path.endsWith("/executor.jar")) return "executor.jar";
        if (path.contains("/bars_d-")) return "generated bars_d JSON files";
        if (path.endsWith("/worker-result.json")) return "published worker result";
        if (path.endsWith("/payload.json")) return "worker payload";
        if (path.contains("/tmp/strategy-successor-v1-")) return "other lifecycle temp files";
        return "other paths";
    }

    private static String toJson(Path source, Summary summary) {
        long executionSamples = sumCount(summary.executionByPhase);
        long allocationWeight = sumAmount(summary.allocationByPhase);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "calculation-performance-jfr-summary/1");
        root.put("source", source.toAbsolutePath().normalize().toString());
        root.put("scope", "DIAGNOSTIC_ONLY_SAMPLED_CPU_AND_ALLOCATION_LOGICAL_FILE_IO");
        root.put("execution_samples", executionSamples);
        root.put("execution_sample_share_by_phase", shares(summary.executionByPhase, executionSamples, false));
        root.put("top_execution_methods", top(summary.executionByMethod, 30, executionSamples, false));
        root.put("allocation_sample_weight_bytes", allocationWeight);
        root.put("allocation_sample_weight_by_phase", shares(summary.allocationByPhase, allocationWeight, true));
        root.put("top_allocation_sites", top(summary.allocationByMethod, 30, allocationWeight, true));
        root.put("logical_file_io", rows(summary.ioByKind));
        root.put("logical_file_io_by_path_bucket", rows(summary.ioByPathBucket));
        root.put("logical_file_io_by_top_method", top(summary.ioByMethod, 30, 0, false));
        root.put("gc_events", summary.gcCount);
        root.put("gc_duration_ms", summary.gcDurationNanos / 1_000_000.0);
        return json(root);
    }

    private static long sumCount(Map<String, Stats> values) {
        return values.values().stream().mapToLong(stats -> stats.count).sum();
    }

    private static long sumAmount(Map<String, Stats> values) {
        return values.values().stream().mapToLong(stats -> stats.amount).sum();
    }

    private static List<Map<String, Object>> shares(Map<String, Stats> values, long total, boolean amount) {
        List<Map<String, Object>> result = new ArrayList<>();
        values.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Stats> entry) ->
                amount ? entry.getValue().amount : entry.getValue().count).reversed()).forEach(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long value = amount ? entry.getValue().amount : entry.getValue().count;
            row.put("phase", entry.getKey());
            row.put(amount ? "sample_weight_bytes" : "sample_count", value);
            row.put("share", total == 0 ? 0 : (double) value / total);
            result.add(row);
        });
        return result;
    }

    private static List<Map<String, Object>> top(Map<String, Stats> values, int limit, long total, boolean amount) {
        List<Map<String, Object>> result = new ArrayList<>();
        values.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Stats> entry) ->
                amount ? entry.getValue().amount : entry.getValue().count).reversed()).limit(limit).forEach(entry -> {
            Stats stats = entry.getValue();
            long value = amount ? stats.amount : stats.count;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", entry.getKey());
            row.put(amount ? "sample_weight_bytes" : "sample_count", value);
            if (total > 0) row.put("share", (double) value / total);
            row.put("file_io_duration_ms", stats.durationNanos / 1_000_000.0);
            result.add(row);
        });
        return result;
    }

    private static List<Map<String, Object>> rows(Map<String, Stats> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        values.forEach((key, stats) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", key);
            row.put("event_count", stats.count);
            row.put("bytes", stats.amount);
            row.put("duration_ms", stats.durationNanos / 1_000_000.0);
            row.put("max_event_duration_ms", stats.maxDurationNanos / 1_000_000.0);
            result.add(row);
        });
        return result;
    }

    private static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder output = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) output.append(',');
                first = false;
                output.append(quote(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return output.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder output = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) output.append(',');
                first = false;
                output.append(json(item));
            }
            return output.append(']').toString();
        }
        if (value instanceof String string) return quote(string);
        return String.valueOf(value);
    }

    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (c < 0x20) output.append(String.format("\\u%04x", (int) c));
                    else output.append(c);
                }
            }
        }
        return output.append('"').toString();
    }
}
