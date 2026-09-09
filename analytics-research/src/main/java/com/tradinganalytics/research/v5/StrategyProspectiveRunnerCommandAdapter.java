package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.research.legacy.LegacyResearchNext;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-boundary adapter for {@code tools/strategy-prospective-runner.mjs}.
 *
 * <p>The scheduled runner intentionally consumes the legacy frozen prospective
 * ledger contract.  The v5 content-addressed ledger API is separate; this
 * adapter delegates the runner's exact eligibility and append semantics to the
 * existing legacy implementation.</p>
 */
public final class StrategyProspectiveRunnerCommandAdapter {
    public static final String USAGE = "usage: strategy-prospective-runner.mjs preflight|append|eligibility --ledger <path>";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private StrategyProspectiveRunnerCommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        String command = args == null || args.length == 0 ? "" : args[0];
        Map<String, String> flags = flags(args, 1);
        try {
            if (!"preflight".equals(command) && !"append".equals(command) && !"eligibility".equals(command)) {
                throw new IllegalArgumentException(USAGE);
            }
            String ledgerPath = flags.get("ledger");
            if (ledgerPath == null || ledgerPath.isEmpty() || !Files.exists(resolve(ledgerPath), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("FUTURE_SEAL_UNAVAILABLE: frozen prospective ledger is missing");
            }
            JsonNode ledger = read(ledgerPath);
            LegacyResearchNext.validateProspectiveLedger(ledger);
            if ("preflight".equals(command)) {
                long start;
                try { start = timestamp(ledger.path("reservation").path("start_at"), "prospective reservation start_at"); }
                catch (IllegalArgumentException invalidStart) { throw new IllegalArgumentException("prospective reservation has no valid start_at"); }
                if (System.currentTimeMillis() < start) {
                    throw new IllegalArgumentException("FUTURE_SEAL_NOT_STARTED: current time precedes frozen start");
                }
                ObjectNode result = JSON.objectNode().put("ready", true)
                        .put("schema", text(ledger.get("schema")))
                        .put("reservation_sha256", text(ledger.path("reservation").get("content_sha256")))
                        .put("head_sha256", text(ledger.get("head_sha256")))
                        .put("data_rule", "completed public data available after frozen start only");
                print(stdout, result);
            } else if ("eligibility".equals(command)) {
                print(stdout, LegacyResearchNext.prospectiveEligibility(ledger));
            } else {
                ObjectNode input = JSON.objectNode();
                put(input, "kind", flags.get("kind"));
                put(input, "decisionTime", flags.get("decision_time"));
                put(input, "outcomeTime", flags.get("outcome_time"));
                input.set("payload", flags.containsKey("payload") ? read(flags.get("payload")) : JSON.objectNode());
                ObjectNode next = LegacyResearchNext.appendProspectiveEvent(ledger, input);
                String outputPath = flags.get("out");
                if (outputPath == null || outputPath.isEmpty()) {
                    throw new IllegalArgumentException("append requires --out; refusing to overwrite the source ledger");
                }
                Path target = resolve(outputPath);
                writeExclusive(target, next);
                print(stdout, JSON.objectNode().put("path", target.toString())
                        .put("head_sha256", text(next.get("head_sha256")))
                        .put("event_count", next.path("events").size()));
            }
            return 0;
        } catch (RuntimeException error) {
            stderr.println(message(error));
            return 1;
        }
    }

    private static Map<String, String> flags(String[] args, int start) {
        Map<String, String> result = new LinkedHashMap<>();
        if (args == null) return result;
        for (int index = start; index < args.length; index++) {
            String value = args[index];
            if (value == null || !value.startsWith("--")) continue;
            String key = value.substring(2);
            String next = index + 1 >= args.length || args[index + 1].startsWith("--")
                    ? "true" : args[++index];
            result.put(key, next);
        }
        return result;
    }

    private static JsonNode read(String value) {
        Path path = resolve(value);
        try {
            return JsonHashes.mapper().readTree(PathConfinement.readSinglyLinkedFile(path, "JSON input"));
        } catch (IOException error) {
            throw new IllegalArgumentException("JSON input is not valid: " + path, error);
        }
    }

    private static Path resolve(String value) {
        return Path.of(value == null ? "" : value).toAbsolutePath().normalize();
    }

    private static void writeExclusive(Path target, JsonNode value) {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IllegalArgumentException("output path has no parent");
        try {
            Files.createDirectories(parent);
            PathConfinement.requireRealDirectory(parent, "output parent");
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("overwrite refused: " + absolute);
            }
            Files.write(absolute, NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            PathConfinement.validateSinglyLinkedFile(absolute, "immutable output");
        } catch (IOException error) {
            throw new IllegalArgumentException("immutable output cannot be written: " + absolute, error);
        }
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value != null) node.put(key, value);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.isTextual() ? value.textValue() : value.asText();
    }

    private static long timestamp(JsonNode value, String name) {
        if (value != null && value.isNumber() && Double.isFinite(value.asDouble())) return (long) value.asDouble();
        try { return Instant.parse(text(value)).toEpochMilli(); }
        catch (DateTimeParseException error) { throw new IllegalArgumentException("invalid " + name); }
    }

    private static void print(PrintStream out, JsonNode value) {
        out.print(NodePrettyJson.write(value));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
