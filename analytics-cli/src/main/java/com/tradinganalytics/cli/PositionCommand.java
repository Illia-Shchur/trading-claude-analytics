package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import com.tradinganalytics.reporting.position.PositionSnapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Hard Rule 8 position-of-record command, replacing {@code tools/position.mjs}. */
@Component
@Command(name = "position", description = "Read the newest position-snapshot/1 ledger export")
public class PositionCommand implements Callable<Integer> {
    private static final Pattern DATED_SNAPSHOT = Pattern.compile(
            "^position-snapshot-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(?:-\\d{3})?Z\\.json$");

    @Parameters(index = "0", arity = "1", paramLabel = "<asset|all>")
    private String target;

    @Option(names = "--file")
    private Path requestedFile;

    @Option(names = "--max-age-min")
    private Integer maxAgeMinutes;

    @Option(names = "--fills", defaultValue = "10")
    private int fillLimit;

    @Option(names = "--json", description = "Accepted for legacy compatibility; output is always JSON")
    private boolean jsonOutput;

    @Spec
    private CommandSpec spec;

    private final Path repositoryRoot;
    private final Path userHome;
    private final String configuredExchangeDirectory;
    private final ObjectMapper json;
    private final PositionSnapshots positions;
    private final java.util.function.LongSupplier clock;

    public PositionCommand() {
        this(RepositoryLayout.locate(), Path.of(System.getProperty("user.home")),
                System.getenv("TRADING_EXCHANGE_DIR"), new ObjectMapper(), System::currentTimeMillis);
    }

    PositionCommand(
            Path repositoryRoot,
            Path userHome,
            String configuredExchangeDirectory,
            ObjectMapper json,
            java.util.function.LongSupplier clock) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.userHome = userHome;
        this.configuredExchangeDirectory = configuredExchangeDirectory;
        this.json = json;
        this.positions = new PositionSnapshots(json);
        this.clock = clock;
    }

    @Override
    public Integer call() {
        Path file = resolveSnapshot(requestedFile);
        JsonNode snapshot;
        try {
            snapshot = json.readTree(Files.readString(file));
        } catch (Exception exception) {
            ObjectNode failure = json.createObjectNode();
            failure.put("ok", false);
            failure.put("band", "EXPIRED");
            failure.put("file", file.toString());
            failure.put("schema_expected", PositionSnapshots.POSITION_SNAPSHOT_SCHEMA);
            failure.put("error", exception instanceof java.nio.file.NoSuchFileException
                    ? "snapshot file not found" : "unreadable: " + exception.getMessage());
            failure.put("instruction", "Proceed as a COLD START per Hard Rule 4 (all dry powder, no assumed deployment) and say so explicitly in the report. Regenerate via Investments → Settings → Экспортировать snapshot, or POST /api/investments/analytics-export on the ledger.");
            emit(failure);
            return 1;
        }

        PositionSnapshots.Check structure = positions.positionSnapshotCheck(snapshot);
        if (!structure.ok()) {
            ObjectNode failure = json.createObjectNode();
            failure.put("ok", false);
            failure.put("band", "EXPIRED");
            failure.put("file", file.toString());
            failure.put("schema_expected", PositionSnapshots.POSITION_SNAPSHOT_SCHEMA);
            JsonNode schema = snapshot.get("schema");
            failure.set("schema_found", schema == null ? NullNode.instance : schema.deepCopy());
            ArrayNode errors = failure.putArray("errors");
            structure.errors().forEach(errors::add);
            failure.put("instruction", "Schema mismatch — do NOT read figures out of an unrecognised file. Cold start per Hard Rule 4.");
            emit(failure);
            return 1;
        }

        ObjectNode freshness = positions.positionSnapshotFreshness(
                snapshot, target, clock.getAsLong(), maxAgeMinutes,
                PositionSnapshots.DEFAULT_EXPIRED_MINUTES);
        if ("EXPIRED".equals(freshness.path("band").asText())) {
            ObjectNode failure = json.createObjectNode();
            failure.put("ok", false);
            failure.put("band", "EXPIRED");
            failure.put("file", file.toString());
            failure.set("freshness", freshness);
            failure.put("instruction", "Cold start per Hard Rule 4, stated explicitly. The ledger is too old to be the position of record.");
            emit(failure);
            return 1;
        }

        ObjectNode base = base(snapshot, file, freshness);
        if ("all".equalsIgnoreCase(target)) {
            base.set("positions", copyOrNull(snapshot.get("positions")));
            base.set("futures", copyOrNull(snapshot.get("futures")));
            ObjectNode deals = base.putObject("deals");
            JsonNode sourceDeals = snapshot.path("deals");
            copyField(deals, "open_count", sourceDeals.get("open_count"));
            copyField(deals, "closed_count", sourceDeals.get("closed_count"));
            deals.set("open", sourceDeals.path("open").isArray()
                    ? sourceDeals.path("open").deepCopy() : json.createArrayNode());
            emit(base);
            return 0;
        }

        ObjectNode projected = positions.positionForAsset(snapshot, target);
        ObjectNode result = base.deepCopy();
        result.setAll(projected);
        if (!projected.path("covered").asBoolean()) {
            result.put("ok", false);
            result.put("covered", false);
            emit(result);
            return 2;
        }
        JsonNode fills = result.get("fills");
        if (fills != null && fills.isObject() && fills.path("fills").isArray()) {
            ArrayNode source = (ArrayNode) fills.path("fills");
            ArrayNode selected = json.createArrayNode();
            for (int index = 0; index < Math.min(source.size(), Math.max(0, fillLimit)); index++) {
                selected.add(source.get(index).deepCopy());
            }
            ((ObjectNode) fills).set("fills", selected);
        }
        emit(result);
        return 0;
    }

    private ObjectNode base(JsonNode snapshot, Path file, ObjectNode freshness) {
        ObjectNode result = json.createObjectNode();
        result.put("ok", true);
        result.put("file", file.toString());
        copyField(result, "schema", snapshot.get("schema"));
        copyField(result, "generated_at", snapshot.get("generated_at"));
        copyField(result, "holdings_as_of", snapshot.path("source").get("holdings_as_of"));
        result.set("freshness", freshness);
        ObjectNode carveOuts = result.putObject("carve_outs");
        carveOuts.put("prices", "Snapshot marks are INFORMATIONAL ONLY and never become the report's canonical spot. Hard Rule 1 wants ≥3 independent synchronized venue quotes — sourcing spot from your own database defeats the cross-check.");
        carveOuts.put("phase_attribution", "The ledger knows what is held, not which tranche authorized it. Attribution comes from deal tags only; an untagged holding is reported as real-but-UNTAGGED, never inferred from quantity or timing.");
        copyField(result, "dry_powder", snapshot.get("dry_powder"));
        copyField(result, "portfolio", snapshot.get("portfolio"));
        JsonNode carry = snapshot.get("carry");
        result.set("carry", carry == null || carry.isNull() ? NullNode.instance : carry.deepCopy());
        result.put("carry_note", carry != null && !carry.isNull()
                ? "Spot/margin financing only, cross margin only. NOT futures funding (that is futures.funding_total_usd) — do not sum the two without saying which is which. An empty open_borrows means nothing was borrowed at the last link, not that nothing was ever borrowed; the history is interest_by_asset."
                : "NOT PRESENT in this snapshot — the producer predates the carry ledger. Report carry cost as UNKNOWN, never as zero.");
        boolean signedBasis = false;
        if (snapshot.path("positions").isArray()) {
            for (JsonNode position : snapshot.path("positions")) {
                if (position.has("short_qty")) {
                    signedBasis = true;
                    break;
                }
            }
        }
        result.put("short_leg_note", signedBasis
                ? "Each position carries short_qty / short_avg_price_usd when it is net short — borrow-corroborated, not inferred from a sale. A null or absent short_qty on a position means no short. total_cost_usd is NEGATIVE on a short: money received, not spent."
                : "NOT PRESENT in this snapshot — the producer predates the signed cost-basis model. Report short exposure as UNKNOWN, never as zero; on that producer a short surfaced as basis_reliable:false instead.");
        copyField(result, "performance_overall", snapshot.path("performance").get("overall"));
        JsonNode prefixes = snapshot.path("performance").get("by_tag_prefix");
        result.set("performance_by_tag_prefix", prefixes != null && prefixes.isArray()
                ? prefixes.deepCopy() : json.createArrayNode());
        copyField(result, "coverage", snapshot.get("coverage"));
        return result;
    }

    private Path resolveSnapshot(Path input) {
        if (input != null) {
            return Files.isDirectory(input) ? newestSnapshotIn(input) : input;
        }
        List<Path> candidates = new ArrayList<>();
        if (configuredExchangeDirectory != null && !configuredExchangeDirectory.isBlank()) {
            candidates.add(Path.of(configuredExchangeDirectory));
        } else {
            candidates.add(repositoryRoot.resolve("exports"));
            candidates.add(userHome.resolve(".trading-claude/exchange"));
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                Path selected = newestSnapshotIn(candidate);
                if (Files.exists(selected)) {
                    return selected;
                }
            }
        }
        return candidates.get(0).resolve("position-snapshot.json");
    }

    private Path newestSnapshotIn(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> DATED_SNAPSHOT.matcher(path.getFileName().toString()).matches())
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(directory.resolve("position-snapshot.json"));
        } catch (IOException ignored) {
            return directory.resolve("position-snapshot.json");
        }
    }

    private void emit(JsonNode value) {
        spec.commandLine().getOut().print(NodePrettyJson.write(value));
    }

    private static JsonNode copyOrNull(JsonNode value) {
        return value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy();
    }

    private static void copyField(ObjectNode target, String name, JsonNode value) {
        target.set(name, copyOrNull(value));
    }
}
