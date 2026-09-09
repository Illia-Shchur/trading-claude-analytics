package com.tradinganalytics.infrastructure.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Unregistered backfill/resume adapter for {@code public-data-adapters.mjs}. */
public final class PublicDataAdaptersCommandAdapter {
    private PublicDataAdaptersCommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err,
                new PublicDataAdapters.JdkInjectableHttpClient());
        if (status != 0) System.exit(status);
    }

    public static int run(
            String[] args, PrintStream out, PrintStream err,
            PublicDataAdapters.InjectableHttpClient client) {
        String command = args.length == 0 ? "" : args[0];
        Map<String, String> options = flags(args);
        if ("resume".equals(command) && !options.containsKey("resume") && args.length > 1
                && !args[1].startsWith("--")) options.put("resume", args[1]);
        if (!"backfill".equals(command) && !"resume".equals(command)) {
            out.print("usage: public-data-adapters backfill|resume --asset <asset> "
                    + "--start <time>|--resume <receipt> --out <path>\n");
            return 0;
        }
        try {
            execute(options, client, out);
            return 0;
        } catch (RuntimeException | IOException error) {
            err.println(rootMessage(error));
            return 1;
        }
    }

    private static void execute(
            Map<String, String> options, PublicDataAdapters.InjectableHttpClient client,
            PrintStream out) throws IOException {
        ObjectNode prior = options.containsKey("resume")
                ? validateReceipt(object(Files.readAllBytes(Path.of(options.get("resume"))))) : null;
        String asset = options.getOrDefault("asset",
                prior == null ? null : prior.path("asset").asText(null));
        if (asset == null || (!options.containsKey("start") && prior == null)) {
            throw new IllegalArgumentException(
                    "backfill requires --asset and --start, or --resume <prior-receipt>");
        }
        if (!options.containsKey("out")) throw new IllegalArgumentException("backfill requires --out");
        String kind = options.getOrDefault("adapter",
                prior == null ? "spot-ohlc" : prior.path("adapter").asText("spot-ohlc"))
                .toLowerCase(Locale.ROOT);
        asset = asset.toLowerCase(Locale.ROOT);
        if (prior != null && (!kind.equals(prior.path("adapter").asText())
                || !asset.equals(prior.path("asset").asText()))) {
            throw new IllegalArgumentException("resume adapter/asset does not match prior receipt");
        }
        String interval = options.getOrDefault("interval",
                prior == null || prior.path("interval").isNull()
                        ? "4h" : prior.path("interval").asText("4h"));
        long step = kind.contains("funding") ? 1 : intervalMillis(interval);
        long start = options.containsKey("start") ? timestamp(options.get("start"))
                : prior.path("coverage").path("last_event_time").asLong() + step;
        Long end = options.containsKey("end") ? timestamp(options.get("end")) : null;
        if (prior != null && prior.path("coverage").path("complete").asBoolean(false)
                && !options.containsKey("force")) {
            throw new IllegalArgumentException(
                    "prior receipt is complete; use --force only to replay from its cursor");
        }
        String capturedAt = options.get("captured_at");
        PublicDataAdapters.HttpOptions http = new PublicDataAdapters.HttpOptions(
                client, capturedAt, capturedAt != null, 3, 0);
        int pageSize = Integer.parseInt(options.getOrDefault("page_size",
                kind.contains("open") || kind.contains("interest") || "oi".equals(kind)
                        ? "500" : "1000"));
        int maxPages = Integer.parseInt(options.getOrDefault("max_pages", "1000"));
        int maxRows = Integer.parseInt(options.getOrDefault("max_rows", "1000000"));
        PublicDataAdapters.BackfillResult result;
        if (kind.contains("funding")) {
            result = PublicDataAdapters.backfillBinanceFunding(
                    new PublicDataAdapters.FundingOptions(asset, null, start, end, pageSize, http),
                    start, end, pageSize, maxPages, maxRows, 0);
        } else if (kind.contains("open") || kind.contains("interest") || "oi".equals(kind)) {
            result = PublicDataAdapters.backfillBinanceOpenInterest(
                    new PublicDataAdapters.OpenInterestOptions(
                            asset, interval, start, end, pageSize, true, http),
                    start, end, pageSize, maxPages, maxRows, 0);
        } else {
            result = PublicDataAdapters.backfillBinanceOhlc(
                    new PublicDataAdapters.OhlcOptions(
                            asset, null, start, end, interval, pageSize,
                            kind.contains("linear"), http),
                    start, end, pageSize, maxPages, maxRows, 0);
        }
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        if (prior != null) prior.path("rows").forEach(row -> rows.add(row.deepCopy()));
        result.rows().forEach(rows::add);
        ObjectNode coverage = result.coverage();
        if (prior != null) {
            coverage.set("start_cursor", prior.path("coverage").get("start_cursor").deepCopy());
            coverage.put("pages", prior.path("coverage").path("pages").asInt()
                    + result.coverage().path("pages").asInt());
        }
        if (rows.isEmpty()) {
            coverage.putNull("first_event_time"); coverage.putNull("last_event_time");
        } else {
            coverage.put("first_event_time", rows.get(0).path("event_time").asLong());
            coverage.put("last_event_time", rows.get(rows.size() - 1).path("event_time").asLong());
        }
        coverage.put("observed_rows", rows.size()); coverage.put("resumed", prior != null);
        ObjectNode pagination = result.receipt();
        ArrayNode pages = JsonHashes.mapper().createArrayNode();
        if (prior != null) prior.path("receipt").path("pages").forEach(page -> pages.add(page.deepCopy()));
        result.receipt().path("pages").forEach(page -> pages.add(page.deepCopy()));
        pagination.set("pages", pages); pagination.set("coverage", coverage);
        pagination.put("content_sha256", ownHash(pagination));

        ObjectNode receipt = JsonHashes.mapper().createObjectNode();
        receipt.put("schema", "public-data-backfill/1"); receipt.put("adapter", kind);
        receipt.put("asset", asset);
        if (kind.contains("funding")) receipt.putNull("interval"); else receipt.put("interval", interval);
        receipt.set("rows", rows);
        ArrayNode hashes = receipt.putArray("response_sha256");
        if (prior != null) prior.path("response_sha256").forEach(hashes::add);
        result.responseSha256().forEach(hashes::add);
        receipt.set("coverage", coverage); receipt.set("receipt", pagination);
        if (prior == null) receipt.putNull("resumed_from");
        else receipt.put("resumed_from", prior.path("content_sha256").asText());
        receipt.put("immutable", true); receipt.put("content_sha256", ownHash(receipt));
        Path destination = Path.of(options.get("out")).toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("backfill output already exists: " + destination);
        }
        Files.createDirectories(destination.getParent());
        Files.write(destination, pretty(receipt), java.nio.file.StandardOpenOption.CREATE_NEW);
        ObjectNode summary = JsonHashes.mapper().createObjectNode();
        summary.put("path", destination.toString()); summary.put("rows", rows.size());
        summary.put("complete", coverage.path("complete").asBoolean());
        summary.put("content_sha256", receipt.path("content_sha256").asText());
        out.print(JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
    }

    private static ObjectNode validateReceipt(ObjectNode receipt) {
        if (!"public-data-backfill/1".equals(receipt.path("schema").asText())
                || !receipt.path("immutable").asBoolean(false)) {
            throw new IllegalArgumentException(
                    "resume input is not an immutable public-data-backfill/1 receipt");
        }
        if (!receipt.path("content_sha256").asText().equals(ownHash(receipt))) {
            throw new IllegalArgumentException("resume input content hash mismatch");
        }
        JsonNode pagination = receipt.path("receipt");
        if (!"public-data-backfill-receipt/1".equals(pagination.path("schema").asText())
                || !pagination.path("content_sha256").asText().equals(ownHash((ObjectNode) pagination))) {
            throw new IllegalArgumentException("resume input pagination receipt hash mismatch");
        }
        return receipt;
    }

    private static String ownHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy(); copy.remove("content_sha256");
        return JsonHashes.canonicalSha256(copy);
    }

    private static Map<String, String> flags(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index++) if (args[index].startsWith("--")) {
            String name = args[index].substring(2).replace('-', '_');
            result.put(name, index + 1 >= args.length || args[index + 1].startsWith("--")
                    ? "true" : args[++index]);
        }
        return result;
    }

    private static long timestamp(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) {
            try { return Instant.parse(value).toEpochMilli(); }
            catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException(
                        "backfill start/resume cursor must be a valid timestamp");
            }
        }
    }

    private static long intervalMillis(String interval) {
        var matcher = java.util.regex.Pattern.compile("^(\\d+)(m|h|d)$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(interval);
        if (!matcher.matches()) throw new IllegalArgumentException(
                "unsupported Binance interval " + interval);
        return Long.parseLong(matcher.group(1)) * switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> 60_000L; case "h" -> 3_600_000L; default -> 86_400_000L;
        };
    }

    private static ObjectNode object(byte[] bytes) {
        JsonNode value = JsonHashes.parse(bytes, "public backfill receipt");
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException("public backfill receipt must be an object");
        }
        return object.deepCopy();
    }

    private static byte[] pretty(JsonNode value) throws IOException {
        return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getMessage() == null ? error.getMessage() : cursor.getMessage();
    }
}
