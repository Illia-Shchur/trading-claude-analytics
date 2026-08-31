package com.tradinganalytics.reporting.position;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure Hard Rule 8 projection and validity logic ported from {@code tools/lib.mjs}. */
public final class PositionSnapshots {
    public static final String POSITION_SNAPSHOT_SCHEMA = "position-snapshot/1";
    public static final int DEFAULT_STALE_MINUTES = 720;
    public static final int DEFAULT_EXPIRED_MINUTES = 4320;

    private static final Pattern ACCEPTED_FUTURES_STATUS = Pattern.compile(
            "^(LIVE|AVAILABLE|AVAILABLE_EMPTY|COMPLETE|SUCCESS|NO_OPEN_POSITIONS)$");
    private static final String GOLD_ALIAS_NOTE = "Position read from PAXG, tokenized gold. PAXG is a PROXY for spot gold — fully backed and tracking XAU ~1:1, but carrying issuer/custody counterparty risk that spot gold does not, and able to trade at a premium or discount. Quantity and cost basis are real; treat the instrument as PAXG, not bullion. Canonical gold SPOT still comes from Hard Rule 1 sources, never from this mark.";

    private final ObjectMapper json;

    public PositionSnapshots(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode positionFreshness(JsonNode generatedAt, JsonNode holdingsAsOf, long nowMillis) {
        return positionFreshness(generatedAt, holdingsAsOf, nowMillis, null, DEFAULT_EXPIRED_MINUTES);
    }

    /** A non-null {@code strictStaleMinutes} is the Node API's own-property strict-time switch. */
    public ObjectNode positionFreshness(
            JsonNode generatedAt,
            JsonNode holdingsAsOf,
            long nowMillis,
            Integer strictStaleMinutes,
            int expiredMinutes) {
        int stale = strictStaleMinutes == null ? DEFAULT_STALE_MINUTES : strictStaleMinutes;
        boolean strictTime = strictStaleMinutes != null;
        Long generatedMillis = toMillis(generatedAt);
        Long holdingsMillis = toMillis(holdingsAsOf);
        ObjectNode result = json.createObjectNode();
        if (generatedMillis == null) {
            result.put("band", "EXPIRED");
            result.set("age_min", NullNode.instance);
            result.put("driver", "generated_at");
            result.set("generated_age_min", NullNode.instance);
            result.set("holdings_age_min", NullNode.instance);
            result.put("stale_after_min", stale);
            result.put("expired_after_min", expiredMinutes);
            result.put("note", "generated_at is missing or unparseable — treat as no snapshot at all (cold start, Hard Rule 4)");
            return result;
        }

        long generatedAge = Math.round((nowMillis - generatedMillis) / 60_000.0);
        Long holdingsAge = holdingsMillis == null ? null : Math.round((nowMillis - holdingsMillis) / 60_000.0);
        String band = strictTime
                ? generatedAge <= stale ? "FRESH" : generatedAge <= expiredMinutes ? "STALE" : "EXPIRED"
                : "FRESH";
        String note = switch (band) {
            case "FRESH" -> strictTime
                    ? "position of record under explicit time limit — supersedes any figure carried forward from a prior report"
                    : "position of record under event-driven policy — user exports after every new trade; elapsed time alone does not invalidate an unchanged position";
            case "STALE" -> "descriptive use only, with an age banner. May NOT satisfy a phase-dependent unlock precondition or fill a realized ledger column.";
            default -> "cold start per Hard Rule 4 — state explicitly that no fresh ledger was available";
        };
        result.put("band", band);
        result.put("age_min", generatedAge);
        result.put("driver", strictTime ? "generated_at" : "event_driven_snapshot");
        result.put("policy", strictTime ? "STRICT_TIME" : "EVENT_DRIVEN");
        result.put("generated_age_min", generatedAge);
        putNullable(result, "holdings_age_min", holdingsAge);
        if (strictTime) {
            result.put("stale_after_min", stale);
            result.put("expired_after_min", expiredMinutes);
        } else {
            result.set("stale_after_min", NullNode.instance);
            result.set("expired_after_min", NullNode.instance);
        }
        result.put("note", note);
        return result;
    }

    public ObjectNode positionSnapshotFreshness(JsonNode snapshot, String asset, long nowMillis) {
        return positionSnapshotFreshness(snapshot, asset, nowMillis, null, DEFAULT_EXPIRED_MINUTES);
    }

    public ObjectNode positionSnapshotFreshness(
            JsonNode snapshot,
            String assetRaw,
            long nowMillis,
            Integer strictStaleMinutes,
            int expiredMinutes) {
        String target = upper(assetRaw);
        ArrayNode positions = arrayAt(snapshot, "positions");
        ArrayNode openDeals = arrayAt(path(snapshot, "deals"), "open");
        ArrayNode futures = arrayAt(path(snapshot, "futures"), "open_positions");
        boolean all = "ALL".equals(target);
        boolean hasSpot = all
                ? positions.size() > 0 || openDeals.size() > 0
                : any(positions, row -> target.equals(upper(row.path("asset").asText())))
                        || any(openDeals, row -> target.equals(upper(row.path("asset").asText())));
        List<JsonNode> relevantFutures = new ArrayList<>();
        for (JsonNode future : futures) {
            if (all || target.equals(upper(future.path("analytics_asset").asText()))
                    || target.equals(upper(future.path("base_asset").asText()))) {
                relevantFutures.add(future);
            }
        }
        boolean hasFutures = !relevantFutures.isEmpty();

        LinkedHashMap<String, JsonNode> clocks = new LinkedHashMap<>();
        if (hasSpot || !hasFutures) {
            clocks.put("holdings_as_of", path(snapshot, "source", "holdings_as_of"));
        }
        if (hasFutures) {
            JsonNode futuresNode = path(snapshot, "futures");
            for (String name : List.of("account_as_of", "positions_as_of", "marks_as_of", "orders_as_of", "income_as_of")) {
                clocks.put("futures." + name, path(futuresNode, name));
            }
            for (JsonNode future : relevantFutures) {
                String key = firstText(future, "position_key", "symbol");
                if (key == null || key.isEmpty()) {
                    key = "unknown";
                }
                JsonNode value = defined(future, "position_as_of")
                        ? future.get("position_as_of") : path(futuresNode, "positions_as_of");
                clocks.put("futures.position_as_of:" + key, value);
            }
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, JsonNode> clock : clocks.entrySet()) {
            if (toMillis(clock.getValue()) == null) {
                missing.add(clock.getKey());
            }
        }
        ObjectNode fresh = positionFreshness(
                path(snapshot, "generated_at"), path(snapshot, "source", "holdings_as_of"),
                nowMillis, strictStaleMinutes, expiredMinutes);
        List<String> limitations = new ArrayList<>();
        if (hasFutures) {
            JsonNode futuresNode = path(snapshot, "futures");
            for (String name : List.of("account_status", "positions_status", "marks_status", "orders_status", "income_status")) {
                JsonNode value = path(futuresNode, name);
                String status = value.isMissingNode() || value.isNull() ? null : value.asText();
                if (status == null || !ACCEPTED_FUTURES_STATUS.matcher(status).matches()) {
                    limitations.add(name + "=" + (status == null ? "MISSING" : status));
                }
            }
            for (JsonNode future : relevantFutures) {
                String status = defined(future, "income_coverage_status")
                        ? future.get("income_coverage_status").asText() : null;
                if (!"COMPLETE_FOR_SEQUENCE".equals(status)) {
                    String key = firstText(future, "position_key", "symbol");
                    limitations.add("income_coverage:" + (key == null || key.isEmpty() ? "unknown" : key)
                            + "=" + (status == null ? "MISSING" : status));
                }
            }
        }
        for (int index = missing.size() - 1; index >= 0; index--) {
            limitations.add(0, missing.get(index) + "=MISSING");
        }

        String band = fresh.path("band").asText();
        String note = fresh.path("note").asText();
        if ("FRESH".equals(band) && !limitations.isEmpty()) {
            band = "STALE";
            note = "descriptive use only: at least one relevant futures component has incomplete status/coverage.";
        }
        ObjectNode result = fresh.deepCopy();
        result.put("band", band);
        result.put("driver", missing.isEmpty() ? fresh.path("driver").asText() : missing.get(0));
        result.put("relevant_scope", hasFutures ? (hasSpot ? "MIXED_SPOT_FUTURES" : "FUTURES_ONLY") : "SPOT");
        ObjectNode componentClocks = json.createObjectNode();
        clocks.forEach((key, value) -> componentClocks.set(key,
                value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy()));
        result.set("component_clocks", componentClocks);
        ArrayNode limitationArray = result.putArray("limitations");
        limitations.forEach(limitationArray::add);
        result.put("note", note);
        return result;
    }

    public Check positionSnapshotCheck(JsonNode snapshot) {
        List<String> errors = new ArrayList<>();
        if (snapshot == null || !snapshot.isObject()) {
            return new Check(false, List.of("not a JSON object"));
        }
        if (!POSITION_SNAPSHOT_SCHEMA.equals(snapshot.path("schema").asText())) {
            JsonNode schema = snapshot.get("schema");
            String rendered;
            try {
                rendered = schema == null ? "undefined" : json.writeValueAsString(schema);
            } catch (Exception ignored) {
                rendered = String.valueOf(schema);
            }
            errors.add("schema is " + rendered + ", expected \"" + POSITION_SNAPSHOT_SCHEMA + "\"");
        }
        for (String key : List.of("generated_at", "source", "portfolio", "dry_powder", "positions",
                "futures", "trades", "deals", "performance", "coverage")) {
            if (!defined(snapshot, key)) {
                errors.add("missing top-level \"" + key + "\"");
            }
        }
        if (snapshot.has("positions") && !snapshot.get("positions").isArray()) {
            errors.add("\"positions\" is not an array");
        }
        return new Check(errors.isEmpty(), List.copyOf(errors));
    }

    public ObjectNode custodyForPosition(JsonNode position, String asset) {
        ObjectNode result = json.createObjectNode();
        if (position == null || position.isNull() || position.isMissingNode()) {
            result.put("status", "NO_POSITION_ROW");
            result.set("on_venue", NullNode.instance);
            result.set("off_venue_qty", NullNode.instance);
            result.put("note", "The snapshot carries no position row for " + asset + ", so custody is UNKNOWN — not reconciled. An absent row is the absence of an answer, never an all-clear. Do NOT report this asset as on-venue, flat, or exited on the strength of this response; a snapshot written before 2026-07-30 omitted any asset whose live balance was exactly zero, including assets the replay still held a position in.");
            return result;
        }
        String status = nullableText(position.get("qty_reconciliation_status"));
        if (status == null || "RECONCILED".equals(status)) {
            result.put("status", status == null ? "RECONCILED" : status);
            result.put("on_venue", true);
            result.set("off_venue_qty", NullNode.instance);
            result.put("note", "Live balance agrees with the fill replay; the position is where the ledger can see it.");
            return result;
        }
        if ("EXPLAINED_BY_EXTERNAL_TRANSFER".equals(status)) {
            result.put("status", status);
            result.put("on_venue", false);
            copyOrNull(result, "off_venue_qty", position.get("off_venue_qty"));
            copyOrNull(result, "custody_adjusted_unrealized_pnl_usd", position.get("custody_adjusted_unrealized_pnl_usd"));
            String quantity = jsString(position.get("off_venue_qty"));
            result.put("note", quantity + " " + asset + " left the exchange as a withdrawal, not a sale, and is presumed held in external custody. REPORT THIS AS A HELD POSITION — do NOT read the near-zero live balance as flat or as an exit. The mark is custody-adjusted and therefore a belief the ledger cannot verify: it cannot tell cold storage from a sale on another venue. Confirm custody before sizing against it, and do not let it satisfy a phase-dependent unlock precondition.");
            return result;
        }
        if ("EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE".equals(status)) {
            result.put("status", status);
            result.put("on_venue", true);
            result.set("off_venue_qty", NullNode.instance);
            result.put("cost_basis_contaminated", true);
            result.put("note", "The replay exceeds the live balance by a synthetic OPENING_BALANCE seed carried at the ledger's data floor — an accounting artefact, not coins, and one that can never be reconciled because the pre-floor fills it was computed from were deleted. REPORT THE LIVE QUANTITY AS THE POSITION (this asset may genuinely be flat); do NOT report trade_derived_qty as " + asset + " held, and do NOT treat it as off-venue custody — unlike a withdrawal there is no evidence these coins exist. Cost basis, unrealized PnL and realized PnL for " + asset + " are contaminated by the seed's price: quote them as unreliable or not at all, and do not let them satisfy a phase-dependent unlock precondition.");
            return result;
        }
        result.put("status", "UNEXPLAINED");
        result.set("on_venue", NullNode.instance);
        result.set("off_venue_qty", NullNode.instance);
        result.put("note", "The live balance and the fill replay disagree, and neither recorded withdrawals nor a migration seed accounts for the gap. This is a data defect — an unread wallet, an uncovered venue, or an incomplete backfill — not a position. Do NOT report a figure for this asset in either direction; fix the ledger first.");
        return result;
    }

    public ObjectNode basisForPosition(JsonNode position, String asset) {
        ObjectNode result = json.createObjectNode();
        if (position == null || position.isNull() || position.isMissingNode()) {
            result.set("reliable", NullNode.instance);
            result.set("oversold_qty", NullNode.instance);
            result.put("note", "NO POSITION ROW for " + asset + " — cost-basis reliability is UNKNOWN, not confirmed. Do not read this as a reliable basis, and do not quote average cost, cost basis, unrealized PnL or ROI on the strength of it.");
            return result;
        }
        if (!position.has("basis_reliable") || !position.get("basis_reliable").isBoolean()
                || position.get("basis_reliable").booleanValue()) {
            JsonNode dust = defined(position, "dust_unbacked_qty") ? position.get("dust_unbacked_qty") : null;
            result.put("reliable", true);
            result.set("oversold_qty", NullNode.instance);
            copyOrNull(result, "dust_unbacked_qty", dust);
            if (truthy(dust)) {
                result.put("note", "Basis reliable for " + asset + ", with a disclosure: " + jsString(dust) + " was disposed unbacked but waived as sub-dollar dust rather than counted against the flag. Quote the cost figures normally — this is the ordinary state of a long-tail book, not a defect — but do not describe the replay as having had nothing missing.");
            } else {
                result.set("note", NullNode.instance);
            }
            return result;
        }
        result.put("reliable", false);
        copyOrNull(result, "oversold_qty", position.get("oversold_qty"));
        String producer = nullableText(position.get("basis_unreliable_note"));
        result.put("note", producer != null ? producer
                : "COST BASIS NOT DERIVABLE for " + asset + ": the ledger's replay disposed of more than it ever saw acquired (" + (defined(position, "oversold_qty") ? jsString(position.get("oversold_qty")) : "an unrecorded quantity") + " beyond the position), because the asset was sold short on margin or because an acquisition was never ingested — the ledger cannot tell which. Do NOT quote average cost, cost basis, unrealized PnL or ROI for " + asset + "; state that the basis is unknown. The QUANTITY is still sound and is the position of record. Realized PnL is an UPPER BOUND, not a result: a short leg was realized against a zero basis, so it overstates the gain. This may not satisfy a phase-dependent unlock precondition that reads cost basis, and nothing is sized against a cost basis that does not exist.");
        return result;
    }

    public ObjectNode shortForPosition(JsonNode position, String asset) {
        ObjectNode result = json.createObjectNode();
        if (position == null || position.isNull() || position.isMissingNode()) {
            result.set("short", NullNode.instance);
            result.set("short_qty", NullNode.instance);
            result.set("avg_entry_usd", NullNode.instance);
            result.put("note", "NO POSITION ROW for " + asset + " — whether a short is open is UNKNOWN, not answered.");
            return result;
        }
        if (!position.has("short_qty")) {
            result.set("short", NullNode.instance);
            result.set("short_qty", NullNode.instance);
            result.set("avg_entry_usd", NullNode.instance);
            result.put("note", "NOT PRESENT in this snapshot — the producer predates the signed cost-basis model and could not represent a short. Report short exposure in " + asset + " as UNKNOWN, never as zero; on this producer a short surfaced as basis_reliable:false instead.");
            return result;
        }
        double quantity = position.get("short_qty").isNull() ? 0.0 : position.get("short_qty").asDouble(Double.NaN);
        if (!(quantity > 0)) {
            result.put("short", false);
            result.put("short_qty", 0);
            result.set("avg_entry_usd", NullNode.instance);
            result.set("note", NullNode.instance);
            return result;
        }
        result.put("short", true);
        result.set("short_qty", position.get("short_qty").deepCopy());
        copyOrNull(result, "avg_entry_usd", position.get("short_avg_price_usd"));
        String producer = nullableText(position.get("short_note"));
        result.put("note", producer != null ? producer
                : "NET SHORT " + jsString(position.get("short_qty")) + " " + asset + " at an average entry of " + (defined(position, "short_avg_price_usd") ? jsString(position.get("short_avg_price_usd")) : "an unstated price") + ". trade_derived_qty is a NET and may read flat or even long against an offsetting spot position; this leg still has to be covered and still pays carry. total_cost_usd is NEGATIVE for a short — money received, not spent.");
        return result;
    }

    public ObjectNode positionForAsset(JsonNode snapshot, String assetRaw) {
        String requested = upper(assetRaw);
        boolean goldAlias = "GOLD".equals(requested);
        String asset = goldAlias ? "PAXG" : requested;
        ObjectNode aliasFields = json.createObjectNode();
        if (goldAlias) {
            aliasFields.put("requested_asset", requested);
            aliasFields.put("ledger_asset", "PAXG");
            aliasFields.put("alias_note", GOLD_ALIAS_NOTE);
        }

        Set<String> notTracked = new LinkedHashSet<>();
        for (JsonNode row : arrayAt(path(snapshot, "coverage"), "assets_not_tracked")) {
            String value = upper(row.asText());
            if (!(goldAlias && requested.equals(value))) {
                notTracked.add(value);
            }
        }
        if (notTracked.contains(asset)) {
            ObjectNode result = json.createObjectNode();
            result.put("asset", asset);
            result.setAll(aliasFields);
            result.put("covered", false);
            result.put("reason", "not_tracked");
            result.put("note", "This asset has no counterpart in the ledger and never will. Carry position state forward from the prior report; do NOT read a zero position from this response.");
            return result;
        }

        JsonNode position = find(arrayAt(snapshot, "positions"), row -> asset.equals(upper(row.path("asset").asText())));
        List<JsonNode> openDeals = filter(arrayAt(path(snapshot, "deals"), "open"), row -> asset.equals(upper(row.path("asset").asText())));
        List<JsonNode> closedDeals = filter(arrayAt(path(snapshot, "deals"), "closed"), row -> asset.equals(upper(row.path("asset").asText())));
        JsonNode fills = find(arrayAt(path(snapshot, "trades"), "by_asset"), row -> asset.equals(upper(row.path("asset").asText())));
        List<JsonNode> futures = filter(arrayAt(path(snapshot, "futures"), "open_positions"), row -> {
            String value = firstText(row, "analytics_asset", "base_asset");
            return asset.equals(upper(value));
        });
        Set<String> futureSymbols = new LinkedHashSet<>();
        for (JsonNode future : futures) {
            String symbol = upper(future.path("symbol").asText());
            if (!symbol.isEmpty()) {
                futureSymbols.add(symbol);
            }
        }
        List<JsonNode> fundingRows = filter(arrayAt(path(snapshot, "futures"), "funding_by_symbol"), row ->
                asset.equals(upper(row.path("analytics_asset").asText()))
                        || futureSymbols.contains(upper(row.path("symbol").asText())));
        JsonNode legacyFunding = find(arrayAt(path(snapshot, "futures"), "funding_by_asset"), row ->
                asset.equals(upper(row.path("asset").asText())));
        JsonNode funding = fundingRows.size() == 1 ? fundingRows.get(0)
                : fundingRows.size() > 1 ? json.valueToTree(fundingRows) : legacyFunding;

        if (position == null && openDeals.isEmpty() && closedDeals.isEmpty() && futures.isEmpty()) {
            ObjectNode result = json.createObjectNode();
            result.put("asset", asset);
            result.setAll(aliasFields);
            result.put("covered", false);
            result.put("reason", "no_ledger_history");
            result.put("note", "The ledger tracks this asset but holds no position row, no round trip and no open future in it. That is a genuine flat, not a gap — but it is stated, not inferred from an absent row. It holds only for a snapshot generated on or after 2026-07-30, when the exporter began emitting a row for every replayed asset including those with a zero live balance; on an older file an absent row may simply be an asset that was sold to exactly zero.");
            return result;
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        int untagged = 0;
        for (JsonNode deal : openDeals) {
            String tag = nullableText(deal.get("tag"));
            if (tag == null || tag.isEmpty()) {
                untagged++;
            } else {
                tags.add(tag);
            }
        }
        for (JsonNode future : futures) {
            JsonNode tagNode = path(future, "attribution", "canonical_tag");
            String tag = nullableText(tagNode);
            if (tag == null || tag.isEmpty()) {
                untagged++;
            } else {
                tags.add(tag);
            }
        }
        List<JsonNode> performanceByTag = filter(arrayAt(path(snapshot, "performance"), "by_tag"), row ->
                tags.contains(row.path("tag").asText()));

        ObjectNode result = json.createObjectNode();
        result.put("asset", asset);
        result.setAll(aliasFields);
        result.put("covered", true);
        result.set("position", position == null ? NullNode.instance : position.deepCopy());
        result.set("custody", custodyForPosition(position, asset));
        result.set("basis", basisForPosition(position, asset));
        result.set("short", shortForPosition(position, asset));
        ObjectNode attribution = result.putObject("attribution");
        ArrayNode tagArray = attribution.putArray("tags");
        tags.forEach(tagArray::add);
        attribution.put("untagged_open_deals", untagged);
        attribution.put("note", untagged > 0
                ? untagged + " open deal(s) carry no tag. Report the position as real but attribution UNTAGGED — a phase-dependent unlock precondition cannot resolve through an untagged holding."
                : "every open deal carries a phase tag");
        result.set("open_deals", json.valueToTree(openDeals));
        result.set("closed_deals", json.valueToTree(closedDeals));
        if (fills == null) {
            result.set("fills", NullNode.instance);
        } else {
            ObjectNode projectedFills = json.createObjectNode();
            copyOrNull(projectedFills, "fill_count_total", fills.get("fill_count_total"));
            copyOrNull(projectedFills, "fills", fills.get("fills"));
            result.set("fills", projectedFills);
        }
        result.set("futures_positions", json.valueToTree(futures));
        result.set("funding", funding == null ? NullNode.instance : funding.deepCopy());
        result.set("performance_by_tag", json.valueToTree(performanceByTag));
        return result;
    }

    public record Check(boolean ok, List<String> errors) {
    }

    private static Long toMillis(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            double numeric = value.doubleValue();
            return Double.isFinite(numeric) ? (long) numeric : null;
        }
        String text = value.asText();
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static JsonNode path(JsonNode node, String... names) {
        JsonNode cursor = node;
        for (String name : names) {
            if (cursor == null) {
                return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
            cursor = cursor.path(name);
        }
        return cursor;
    }

    private ArrayNode arrayAt(JsonNode parent, String name) {
        JsonNode value = path(parent, name);
        return value.isArray() ? (ArrayNode) value : json.createArrayNode();
    }

    private static boolean defined(JsonNode parent, String name) {
        return parent != null && parent.isObject() && parent.has(name) && !parent.get(name).isNull();
    }

    private static String upper(String value) {
        return String.valueOf(value == null ? "" : value).toUpperCase(Locale.ROOT);
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = nullableText(node == null ? null : node.get(first));
        return value != null && !value.isEmpty() ? value : nullableText(node == null ? null : node.get(second));
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue() != 0 && !Double.isNaN(node.doubleValue());
        }
        if (node.isTextual()) {
            return !node.textValue().isEmpty();
        }
        return true;
    }

    private static String jsString(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "undefined";
        }
        if (node.isNull()) {
            return "null";
        }
        return node.isTextual() ? node.textValue() : node.asText();
    }

    private static void copyOrNull(ObjectNode target, String name, JsonNode value) {
        target.set(name, value == null || value.isMissingNode() ? NullNode.instance : value.deepCopy());
    }

    private static void putNullable(ObjectNode target, String name, Long value) {
        if (value == null) {
            target.set(name, NullNode.instance);
        } else {
            target.put(name, value);
        }
    }

    private static boolean any(ArrayNode rows, java.util.function.Predicate<JsonNode> predicate) {
        for (JsonNode row : rows) {
            if (predicate.test(row)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode find(ArrayNode rows, java.util.function.Predicate<JsonNode> predicate) {
        for (JsonNode row : rows) {
            if (predicate.test(row)) {
                return row;
            }
        }
        return null;
    }

    private static List<JsonNode> filter(ArrayNode rows, java.util.function.Predicate<JsonNode> predicate) {
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode row : rows) {
            if (predicate.test(row)) {
                selected.add(row.deepCopy());
            }
        }
        return selected;
    }
}
