package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact Java port of the normalized, PIT-safe strategy-research/5 trade lifecycle. */
public final class TradeLifecycleV5 {
    public static final String LIFECYCLE_SCHEMA = "strategy-v5-trade-lifecycle/1";
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final double EPSILON = 1e-12;

    private final LifecycleTrustService trustService;

    public TradeLifecycleV5() {
        this(new LifecycleTrustService());
    }

    public TradeLifecycleV5(LifecycleTrustService trustService) {
        this.trustService = trustService;
    }

    public static String hash(JsonNode value) {
        return JsonHashes.canonicalSha256(value);
    }

    public static String hash(String value) {
        return JsonHashes.sha256(value);
    }

    public static boolean validateLifecycleSpecV5(JsonNode spec) {
        return validateLifecycleSpecV5(spec, null, null);
    }

    public static boolean validateLifecycleSpecV5(JsonNode spec, String side, String instrumentType) {
        if (spec == null || !spec.isObject()) {
            throw failure("lifecycle specification is required");
        }
        JsonNode stop = truthy(first(spec, "stop")) ? first(spec, "stop") : first(spec, "stop_spec");
        JsonNode target = truthy(first(spec, "target")) ? first(spec, "target") : first(spec, "target_spec");
        double max = number(nullish(first(spec, "max_lifecycle_ms"), first(spec, "max_time_ms"), numberNode(0)),
                "max_lifecycle_ms");
        if (!(max > 0)) {
            throw failure("mandatory maximum time stop is missing");
        }
        if ("short".equals(side) && "SPOT".equals(instrumentType)) {
            throw failure("spot shorts are not supported");
        }
        String margin = jsString(or(first(spec, "margin_mode"), textNode(""))).toUpperCase(Locale.ROOT);
        String instrumentMargin = jsString(or(at(spec, "instrument", "margin_mode"), textNode("")))
                .toUpperCase(Locale.ROOT);
        if ("CROSS".equals(margin) || "CROSS".equals(instrumentMargin)) {
            throw failure("cross margin is not supported");
        }
        String sizingMode = jsString(or(at(spec, "sizing", "mode"), textNode("")));
        if (!truthy(stop) && !sizingMode.contains("NOTIONAL") && !sizingMode.contains("VOLATILITY")) {
            throw failure("risk sizing requires an explicit stop");
        }
        normalizePartials(or(first(spec, "partial_exits"), first(spec, "partials")));
        if (truthy(target)) {
            targetFromSpec(target, side == null ? "long" : side, 100, truthy(stop) ? 99d : null);
        }
        JsonNode trailing = first(spec, "trailing");
        if (truthy(trailing)) {
            String trailingType = jsString(or(first(trailing, "type"), textNode(""))).toUpperCase(Locale.ROOT);
            if (!List.of("BREAK_EVEN", "ATR", "PERCENT").contains(trailingType)) {
                throw failure("unsupported trailing type " + trailingType);
            }
        }
        if (present(spec, "gap_policy")
                && !List.of("OPEN", "FAIL").contains(jsString(first(spec, "gap_policy")).toUpperCase(Locale.ROOT))) {
            throw failure("gap_policy must be OPEN or FAIL");
        }
        if (present(trailing, "type") && "PERCENT".equals(jsString(first(trailing, "type")).toUpperCase(Locale.ROOT))) {
            double percent = numberJs(first(trailing, "percent"));
            if (!(percent > 0 && percent < 1)) {
                throw failure("trailing percent must be a fraction between 0 and 1");
            }
        }
        if (present(trailing, "type") && "ATR".equals(jsString(first(trailing, "type")).toUpperCase(Locale.ROOT))
                && !(numberJs(first(trailing, "multiple")) > 0)) {
            throw failure("trailing ATR multiple must be positive");
        }
        if (present(trailing, "activation_r") && !(numberJs(first(trailing, "activation_r")) > 0)) {
            throw failure("trailing activation_r must be positive");
        }
        return true;
    }

    public ObjectNode normalizeTradeLifecycleV5(ObjectNode request) {
        return normalizeTradeLifecycleV5(request, null);
    }

    /**
     * Runs a lifecycle. Production callers pass the non-serializable token separately;
     * fixture-only requests intentionally need no token, matching the Node oracle.
     */
    public ObjectNode normalizeTradeLifecycleV5(
            ObjectNode request, LifecycleTrustService.Token lifecycleTrustToken) {
        ObjectNode source = request == null ? MAPPER.createObjectNode() : request;
        ObjectNode intent = objectOrEmpty(first(source, "intent"));
        ArrayNode bars = arrayOrEmpty(first(source, "bars"));
        ArrayNode funding = arrayOrEmpty(first(source, "funding"));
        ArrayNode marks = arrayOrEmpty(first(source, "marks"));
        ObjectNode execution = objectOrEmpty(first(source, "execution"));
        long interval = Math.max(1L, truncate(number(or(first(source, "interval_ms"), numberNode(60_000)), "interval_ms")));

        String side = direction(intent);
        String type = instrumentType(intent);
        if ("CONTEXT_ONLY".equals(jsString(or(first(intent, "trade_scope"), at(intent, "feature", "trade_scope"), textNode("")))
                .toUpperCase(Locale.ROOT)) || first(intent, "context_only").asBoolean(false)) {
            throw failure("CONTEXT_ONLY assets/predictors cannot produce execution");
        }
        if ("SPOT".equals(type) && "short".equals(side)) {
            throw failure("spot shorts are not supported");
        }
        if ("CROSS".equals(jsString(or(first(intent, "margin_mode"), at(intent, "instrument", "margin_mode"), textNode("")))
                .toUpperCase(Locale.ROOT))) {
            throw failure("cross margin is not supported");
        }

        JsonNode lifecycle = truthy(first(intent, "lifecycle")) ? first(intent, "lifecycle") : intent;
        boolean production = !first(intent, "fixtureOnly").asBoolean(false);
        validateLifecycleSpecV5(lifecycle, side, type);
        if (production && (truthy(first(intent, "contract")) || truthy(at(intent, "instrument", "contract"))
                || truthy(first(execution, "execution_model")) || truthy(first(execution, "model"))
                || truthy(first(execution, "capacity")))) {
            throw failure("production lifecycle rejects caller-owned contract/model/capacity objects; use a physical trust token");
        }

        LifecycleTrustService.ReopenedTrust trust = null;
        JsonNode contract;
        JsonNode model;
        JsonNode capacityBound;
        if (production) {
            Map<String, JsonNode> supplied = new LinkedHashMap<>();
            supplied.put("bars", bars);
            if (!funding.isEmpty()) supplied.put("funding", funding);
            if (!marks.isEmpty()) supplied.put("marks", marks);
            if (present(execution, "hydration")) supplied.put("hydration", first(execution, "hydration"));
            trust = trustService.reopenLifecycleTrustV5(lifecycleTrustToken, supplied);
            contract = trust.values().get("contract_spec");
            model = trust.values().get("execution_model");
            capacityBound = trust.values().get("capacity");
        } else {
            contract = or(first(intent, "contract"), at(intent, "instrument", "contract"), first(intent, "instrument"), MAPPER.createObjectNode());
            model = or(first(execution, "execution_model"), first(execution, "model"));
            capacityBound = or(first(execution, "capacity"), first(intent, "capacity"));
        }
        ObjectNode resolvedExecutionCapacity = null;
        if (truthy(capacityBound)) {
            resolvedExecutionCapacity = objectOrEmpty(capacityBound).deepCopy();
            resolvedExecutionCapacity.put("impact_bps", numberJs(or(first(capacityBound, "impact_bps"), numberNode(0)))
                    + numberJs(or(first(model, "impact_bps"), numberNode(0))));
        }
        final ObjectNode executionCapacity = resolvedExecutionCapacity;

        if (production) {
            Map<String, LifecycleTrustService.ReceiptReference> receipts = trust.receipts();
            if (!validReceipt(receipts.get("contract_spec")) || !validReceipt(receipts.get("execution_model"))
                    || !validReceipt(receipts.get("capacity"))) {
                throw failure("production lifecycle trust token lacks required receipt identities");
            }
            if (present(intent, "contract_spec_sha256") && !first(intent, "contract_spec_sha256").asText()
                    .equals(receipts.get("contract_spec").contentSha256())) {
                throw failure("caller contract hash conflicts with the physical trust receipt");
            }
            if (present(execution, "execution_model_sha256") && !first(execution, "execution_model_sha256").asText()
                    .equals(receipts.get("execution_model").contentSha256())) {
                throw failure("caller execution-model hash conflicts with the physical trust receipt");
            }
            if (present(execution, "capacity_sha256") && !first(execution, "capacity_sha256").asText()
                    .equals(receipts.get("capacity").contentSha256())) {
                throw failure("caller capacity hash conflicts with the physical trust receipt");
            }
            if (!"SPOT".equals(type) && !receipts.containsKey("funding")) {
                throw failure("production derivatives require a physical funding receipt");
            }
            if (!"SPOT".equals(type) && !receipts.containsKey("marks")) {
                throw failure("production derivatives require a physical mark receipt");
            }
        }

        String lifecycleSpecSha256 = hash(lifecycle);
        if (production && present(trust.lineage(), "lifecycle_spec_sha256")
                && !first(trust.lineage(), "lifecycle_spec_sha256").asText().equals(lifecycleSpecSha256)) {
            throw failure("lifecycle specification conflicts with the physical trust lineage");
        }

        long decision = time(nullish(first(intent, "decision_time"), first(intent, "event_time"), first(intent, "entry_time")));
        List<ObjectNode> sorted = validateBars(bars, interval);
        long entryTime = decision;
        int entryIndex = -1;
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index).path("__time").asLong() == entryTime) {
                entryIndex = index;
                break;
            }
        }
        if (entryIndex < 0) throw failure("exact decision-boundary entry open is missing");
        ObjectNode entryBar = sorted.get(entryIndex);
        double entry = price(entryBar, "open");
        JsonNode multiplierValue = production
                ? first(contract, "contract_multiplier")
                : nullish(first(intent, "contract_multiplier"), at(intent, "instrument", "contract_multiplier"),
                        first(contract, "contract_multiplier"), numberNode(1));
        double multiplier = number(multiplierValue, "contract multiplier");
        JsonNode instrumentExpiryNode = production
                ? first(contract, "expiry_time")
                : nullish(first(intent, "expiry_time"), at(intent, "instrument", "expiry_time"),
                        first(contract, "expiry_time"), first(lifecycle, "expiry_time"));
        Long instrumentExpiry = defined(instrumentExpiryNode) ? time(instrumentExpiryNode) : null;
        if (instrumentExpiry != null && instrumentExpiry <= entryTime) throw failure("dated instrument expires before entry");
        JsonNode liquidationNode = production
                ? first(contract, "liquidation_price")
                : nullish(first(intent, "liquidation_price"), at(intent, "instrument", "liquidation_price"),
                        first(contract, "liquidation_price"), first(lifecycle, "liquidation_price"));
        Double liquidationPrice = defined(liquidationNode) ? numberJs(liquidationNode) : null;
        if (production && !"SPOT".equals(type)) {
            if (!"ISOLATED".equals(jsString(or(first(contract, "margin_mode"), textNode(""))).toUpperCase(Locale.ROOT))) {
                throw failure("production derivatives require isolated margin");
            }
            if (!(numberJs(first(contract, "leverage")) > 0)) throw failure("production derivatives require positive leverage");
            if (!(liquidationPrice != null && liquidationPrice > 0)) throw failure("production derivatives require bound liquidation price");
        }
        if (liquidationPrice != null && (!(liquidationPrice > 0)
                || "long".equals(side) && liquidationPrice >= entry
                || "short".equals(side) && liquidationPrice <= entry)) {
            throw failure("liquidation price must be adverse to entry");
        }

        JsonNode stopSpec = or(first(lifecycle, "stop"), first(lifecycle, "stop_spec"));
        JsonNode targetSpec = or(first(lifecycle, "target"), first(lifecycle, "target_spec"));
        Double stop = stopFromSpec(stopSpec, side, entry, sorted, entryIndex);
        Double target = targetFromSpec(targetSpec, side, entry, stop);
        JsonNode sizing = or(first(lifecycle, "sizing"), first(intent, "sizing"));
        if (!truthy(sizing)) {
            ObjectNode defaultSizing = MAPPER.createObjectNode().put("mode", "FIXED_NOTIONAL");
            defaultSizing.set("notional_usd", or(first(intent, "notional_usd"), numberNode(1)));
            sizing = defaultSizing;
        }
        double quantity = quantityFor(sizing, entry, stop, multiplier, contract, production);
        long maxLife = truncate(number(first(lifecycle, "max_lifecycle_ms"), "max_lifecycle_ms"));
        if (maxLife < interval || maxLife % interval != 0) {
            throw failure("max_lifecycle_ms must be a positive multiple of the bar interval");
        }
        long endExclusive = entryTime + maxLife;
        String gapPolicy = jsString(or(first(lifecycle, "gap_policy"), first(execution, "gap_policy"), textNode("OPEN")))
                .toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "FAIL").contains(gapPolicy)) throw failure("gap_policy must be OPEN or FAIL");

        JsonNode modelFee = nullish(first(model, "taker_fee_rate"), first(model, "fee_rate"), at(model, "fees", "taker"));
        JsonNode modelSlippage = nullish(first(model, "slippage_bps"), at(model, "slippage", "bps"));
        if (production && (!(numberJs(modelFee) >= 0) || !(numberJs(modelSlippage) >= 0))) {
            throw failure("bound execution model lacks fee/slippage fields");
        }
        if (production && (present(execution, "fee_rate") && numberJs(first(execution, "fee_rate")) != numberJs(modelFee)
                || present(execution, "slippage_bps") && numberJs(first(execution, "slippage_bps")) != numberJs(modelSlippage))) {
            throw failure("caller execution cost override conflicts with bound execution model");
        }
        double feeRate = number(production ? modelFee : nullish(first(execution, "fee_rate"), first(intent, "fee_rate"), numberNode(0)), "fee_rate");
        double slippageBps = number(production ? modelSlippage : nullish(first(execution, "slippage_bps"), first(intent, "slippage_bps"), numberNode(0)), "slippage_bps");
        List<ObjectNode> partials = normalizePartials(or(first(lifecycle, "partial_exits"), first(lifecycle, "partials")));
        JsonNode trailing = or(first(lifecycle, "trailing"));
        MutableDouble remaining = new MutableDouble(quantity);
        MutableDouble currentStop = new MutableDouble(stop);
        ArrayNode exits = MAPPER.createArrayNode();
        ArrayNode costRecords = MAPPER.createArrayNode();
        boolean beArmed = false;

        Cost entryCosts = executionCost(side, entry, quantity, multiplier, feeRate, slippageBps, executionCapacity);
        costRecords.add(costNode(entryCosts).put("stage", "ENTRY"));
        MutableLong fundingCursor = new MutableLong(entryTime);

        Settlement settle = (bar, fillPrice, reason, fraction, fillType) -> {
            double exitQuantity = fraction == null ? remaining.value : Math.min(remaining.value, quantity * fraction);
            if (!(exitQuantity > 0)) return;
            Cost costs = executionCost(side, fillPrice, exitQuantity, multiplier, feeRate, slippageBps, executionCapacity);
            Funding fundingResult = fundingCost(funding, fundingCursor.value, bar.path("__time").asLong(),
                    remaining.value, multiplier, side, marks);
            fundingCursor.value = bar.path("__time").asLong();
            double grossPnl = ("long".equals(side) ? fillPrice - entry : entry - fillPrice) * exitQuantity * multiplier;
            ObjectNode exit = MAPPER.createObjectNode()
                    .put("time", iso(bar.path("__time").asLong()))
                    .put("price", fillPrice)
                    .put("quantity", exitQuantity)
                    .put("fraction", exitQuantity / quantity)
                    .put("reason", reason)
                    .put("fill_type", fillType)
                    .put("gross_pnl_usd", grossPnl)
                    .put("fees_usd", costs.feesUsd)
                    .put("slippage_usd", costs.slippageUsd)
                    .put("capacity_debit_usd", costs.capacityDebitUsd)
                    .put("funding_usd", fundingResult.amount)
                    .put("net_pnl_usd", grossPnl - costs.feesUsd - costs.slippageUsd - costs.capacityDebitUsd + fundingResult.amount);
            exit.set("funding_settlements", fundingResult.settlements);
            exits.add(exit);
            costRecords.add(costNode(costs).put("stage", "EXIT").put("time", iso(bar.path("__time").asLong())));
            remaining.value -= exitQuantity;
        };

        for (int index = entryIndex; index < sorted.size() && remaining.value > EPSILON; index++) {
            ObjectNode bar = sorted.get(index);
            long barAt = bar.path("__time").asLong();
            if (barAt >= endExclusive) break;
            if (instrumentExpiry != null && barAt >= instrumentExpiry) {
                settle.apply(bar, numberJs(first(bar, "open")), "EXPIRY", null, "EXPIRY_OPEN");
                break;
            }
            Fill stopFill = fillOnBarrier(bar, currentStop.value, side, "STOP", gapPolicy);
            Fill targetFill = fillOnBarrier(bar, target, side, "TARGET", gapPolicy);
            Fill liquidationFill = liquidationPrice == null ? null
                    : fillOnBarrier(bar, liquidationPrice, side, "LIQUIDATION", gapPolicy);
            if (stopFill != null && liquidationFill != null) {
                double stopLoss = "long".equals(side) ? entry - stopFill.price : stopFill.price - entry;
                double liquidationLoss = "long".equals(side) ? entry - liquidationFill.price : liquidationFill.price - entry;
                if (Math.abs(stopLoss - liquidationLoss) < EPSILON) {
                    throw failure("stop/liquidation same-bar collision is ambiguous");
                }
                if (liquidationLoss > stopLoss) {
                    settle.apply(bar, liquidationFill.price, "LIQUIDATION", null, liquidationFill.fillType);
                } else {
                    settle.apply(bar, stopFill.price, "STOP", null, stopFill.fillType);
                }
                break;
            }
            if (stopFill != null) {
                settle.apply(bar, stopFill.price, "STOP", null, stopFill.fillType);
                break;
            }
            if (liquidationFill != null) {
                settle.apply(bar, liquidationFill.price, "LIQUIDATION", null, liquidationFill.fillType);
                break;
            }

            List<PartialFill> partialFills = new ArrayList<>();
            for (ObjectNode partial : partials) {
                if (partial.path("filled").asBoolean(false)) continue;
                Double barrier = partialBarrier(partial, side, entry, stop, target);
                if (barrier != null && fillOnBarrier(bar, barrier, side, "TARGET", gapPolicy) != null) {
                    partialFills.add(new PartialFill(partial, barrier));
                }
            }
            partialFills.sort(Comparator.comparingDouble(row -> partialSortKey(row.row, row.barrier)));
            for (PartialFill partial : partialFills) {
                partial.row.put("filled", true);
                Fill fill = fillOnBarrier(bar, partial.barrier, side, "TARGET", gapPolicy);
                settle.apply(bar, fill.price, "PARTIAL_TARGET", numberJs(first(partial.row, "fraction")), fill.fillType);
                if (remaining.value <= EPSILON) break;
            }
            if (targetFill != null && remaining.value > EPSILON) {
                settle.apply(bar, targetFill.price, "TARGET", null, targetFill.fillType);
                break;
            }

            if (truthy(trailing) && index < sorted.size() - 1) {
                String trailingType = jsString(or(first(trailing, "type"), textNode(""))).toUpperCase(Locale.ROOT);
                Double proposed = null;
                double activationR = numberJs(or(first(trailing, "activation_r"), numberNode(1)));
                double risk = Math.abs(entry - (stop == null ? entry : stop));
                if ("BREAK_EVEN".equals(trailingType) && !beArmed && target != null
                        && ("long".equals(side) ? price(bar, "high") >= entry + risk * activationR
                        : price(bar, "low") <= entry - risk * activationR)) {
                    beArmed = true;
                    proposed = entry;
                }
                if ("PERCENT".equals(trailingType)) {
                    double percent = number(first(trailing, "percent"), "trailing percent");
                    proposed = "long".equals(side) ? price(bar, "close") * (1 - percent)
                            : price(bar, "close") * (1 + percent);
                }
                if ("ATR".equals(trailingType)) {
                    Double atr = atrAt(sorted, index, (int) numberJs(or(first(trailing, "period"), numberNode(14))));
                    if (atr != null && atr != 0) {
                        double multiple = number(first(trailing, "multiple"), "trailing ATR multiple");
                        proposed = "long".equals(side) ? price(bar, "close") - atr * multiple
                                : price(bar, "close") + atr * multiple;
                    }
                }
                if (proposed != null) {
                    currentStop.value = currentStop.value == null ? proposed
                            : "long".equals(side) ? Math.max(currentStop.value, proposed) : Math.min(currentStop.value, proposed);
                }
            }
        }

        if (remaining.value > EPSILON) {
            ObjectNode last = null;
            for (ObjectNode bar : sorted) if (bar.path("__time").asLong() < endExclusive) last = bar;
            if (last == null || last.path("__time").asLong() != endExclusive - interval) {
                throw failure("right-edge lifecycle is incomplete; no artificial time stop is permitted");
            }
            settle.apply(last, price(last, "close"), "TIME_STOP", null, "TIME_STOP_CLOSE");
        }

        double gross = sum(exits, "gross_pnl_usd");
        double fees = sum(exits, "fees_usd") + entryCosts.feesUsd;
        double slip = sum(exits, "slippage_usd") + entryCosts.slippageUsd;
        double fundingUsd = sum(exits, "funding_usd");
        double capacity = sum(exits, "capacity_debit_usd") + entryCosts.capacityDebitUsd;

        ObjectNode result = MAPPER.createObjectNode()
                .put("schema", LIFECYCLE_SCHEMA)
                .put("version", 1)
                .put("status", "COMPLETE")
                .put("fixture_only", first(intent, "fixtureOnly").asBoolean(false))
                .put("provenance", first(intent, "fixtureOnly").asBoolean(false) ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE")
                .put("decision_time", iso(decision))
                .put("entry_time", iso(entryTime))
                .put("entry_price", entry)
                .put("direction", side)
                .put("instrument_type", type)
                .put("quantity", quantity)
                .put("contract_multiplier", multiplier);
        putNullableNumber(result, "stop_price", stop);
        putNullableNumber(result, "target_price", target);
        result.put("max_lifecycle_ms", maxLife)
                .put("lifecycle_end_exclusive", iso(endExclusive));
        result.set("exits", exits);
        result.put("remaining_quantity", Math.max(0, remaining.value))
                .put("gross_pnl_usd", gross)
                .put("fees_usd", fees)
                .put("slippage_usd", slip)
                .put("funding_usd", fundingUsd)
                .put("capacity_debit_usd", capacity)
                .put("net_pnl_usd", gross - fees - slip - capacity + fundingUsd);
        result.set("cost_records", costRecords);
        result.set("entry_costs", costNode(entryCosts));
        result.set("entry_fill", MAPPER.createObjectNode().put("time", iso(entryTime)).put("price", entry)
                .put("quantity", quantity).put("fill_type", "DECISION_BOUNDARY_OPEN"));
        if (truthy(trailing)) result.put("effective_trailing_from", iso(entryTime + interval));
        else result.putNull("effective_trailing_from");
        if (production) result.set("physical_execution_lineage", physicalLineage(trust, lifecycleSpecSha256));
        result.put("content_sha256", hash(result));
        return result;
    }

    public ObjectNode simulateTradeLifecycleV5(ObjectNode request) {
        return normalizeTradeLifecycleV5(request);
    }

    public ObjectNode simulateLifecycleV5(ObjectNode request) {
        return normalizeTradeLifecycleV5(request);
    }

    public ObjectNode executeTradeIntentV5(ObjectNode request) {
        return normalizeTradeLifecycleV5(request);
    }

    private static ObjectNode physicalLineage(
            LifecycleTrustService.ReopenedTrust trust, String lifecycleSpecSha256) {
        ObjectNode output = MAPPER.createObjectNode()
                .put("trust_schema", trust.schema())
                .put("trust_bundle_sha256", trust.bundleSha256())
                .put("lifecycle_trust_sha256", trust.bundleSha256());
        if (trust.rootReference() == null) output.putNull("physical_root_reference");
        else output.put("physical_root_reference", trust.rootReference());
        ObjectNode receipts = MAPPER.createObjectNode();
        trust.receipts().forEach((role, receipt) -> receipts.set(role, receiptNode(receipt)));
        output.set("receipt_refs", receipts);
        output.put("bars_content_sha256", trust.receipts().get("bars").contentSha256());
        LifecycleTrustService.ReceiptReference bars = trust.receipts().get("bars");
        output.put("bars_rows_sha256", bars.rowsSha256() == null ? bars.contentSha256() : bars.rowsSha256());
        putNullableText(output, "funding_content_sha256", receiptHash(trust, "funding"));
        putNullableText(output, "marks_content_sha256", receiptHash(trust, "marks"));
        putNullableText(output, "hydration_content_sha256", receiptHash(trust, "hydration"));
        output.put("lifecycle_spec_sha256", lifecycleSpecSha256);
        putNullableText(output, "evaluator_spec_sha256", textOrNull(first(trust.lineage(), "evaluator_spec_sha256")));
        putNullableText(output, "precommit_sha256", textOrNull(first(trust.lineage(), "precommit_sha256")));
        if (trust.lineage().isObject()) output.setAll((ObjectNode) trust.lineage());
        return output;
    }

    private static String receiptHash(LifecycleTrustService.ReopenedTrust trust, String role) {
        LifecycleTrustService.ReceiptReference receipt = trust.receipts().get(role);
        return receipt == null ? null : receipt.contentSha256();
    }

    private static ObjectNode receiptNode(LifecycleTrustService.ReceiptReference receipt) {
        ObjectNode node = MAPPER.createObjectNode().put("path", receipt.path())
                .put("content_sha256", receipt.contentSha256()).put("byte_sha256", receipt.byteSha256());
        if (receipt.bytes() == null) node.putNull("bytes"); else node.put("bytes", receipt.bytes());
        putNullableText(node, "rows_sha256", receipt.rowsSha256());
        putNullableText(node, "schema", receipt.schema());
        return node;
    }

    private static boolean validReceipt(LifecycleTrustService.ReceiptReference receipt) {
        return receipt != null && HASH.matcher(receipt.contentSha256()).matches();
    }

    private static List<ObjectNode> validateBars(ArrayNode bars, long intervalMs) {
        if (bars == null || bars.isEmpty()) throw failure("lifecycle requires physical 1m bars");
        List<ObjectNode> sorted = new ArrayList<>();
        for (JsonNode row : bars) {
            if (!row.isObject()) throw failure("invalid timestamp undefined");
            ObjectNode copy = ((ObjectNode) row).deepCopy();
            copy.put("__time", barTime(row));
            sorted.add(copy);
        }
        sorted.sort(Comparator.comparingLong(row -> row.path("__time").asLong()));
        for (int index = 0; index < sorted.size(); index++) {
            ObjectNode row = sorted.get(index);
            double open = price(row, "open"), high = price(row, "high"), low = price(row, "low"), close = price(row, "close");
            if (high < Math.max(open, close) || low > Math.min(open, close)) throw failure("bar OHLC is inconsistent");
            if (index > 0 && row.path("__time").asLong() != sorted.get(index - 1).path("__time").asLong() + intervalMs) {
                throw failure("lifecycle bars are not contiguous");
            }
        }
        return sorted;
    }

    private static Fill fillOnBarrier(ObjectNode bar, Double barrier, String side, String kind, String gapPolicy) {
        if (barrier == null || !Double.isFinite(barrier)) return null;
        boolean favorable = kind.toUpperCase(Locale.ROOT).contains("TARGET");
        boolean crossed = favorable
                ? ("long".equals(side) ? price(bar, "high") >= barrier : price(bar, "low") <= barrier)
                : ("long".equals(side) ? price(bar, "low") <= barrier : price(bar, "high") >= barrier);
        if (!crossed) return null;
        boolean openCross = favorable
                ? ("long".equals(side) ? price(bar, "open") >= barrier : price(bar, "open") <= barrier)
                : ("long".equals(side) ? price(bar, "open") <= barrier : price(bar, "open") >= barrier);
        if (openCross) {
            if ("FAIL".equals(gapPolicy)) throw failure("gap through " + kind + " is not fillable");
            return new Fill(price(bar, "open"), "GAP_OPEN");
        }
        return new Fill(barrier, "BARRIER");
    }

    private static double trueRange(ObjectNode bar, Double previousClose) {
        double high = price(bar, "high"), low = price(bar, "low");
        return previousClose == null ? high - low
                : Math.max(high - low, Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
    }

    private static Double atrAt(List<ObjectNode> bars, int index, int period) {
        List<Double> values = new ArrayList<>();
        int last = index - 1;
        for (int cursor = Math.max(0, last - period + 1); cursor <= last; cursor++) {
            values.add(trueRange(bars.get(cursor), cursor > 0 ? price(bars.get(cursor - 1), "close") : null));
        }
        if (values.size() < period) return null;
        double total = 0;
        for (double value : values) total += value;
        return total / values.size();
    }

    private static Double stopFromSpec(JsonNode spec, String side, double entry, List<ObjectNode> bars, int entryIndex) {
        if (!truthy(spec)) return null;
        String type = jsString(or(first(spec, "type"), first(spec, "kind"), textNode(""))).toUpperCase(Locale.ROOT);
        double value;
        if ("PERCENT".equals(type) || "PCT".equals(type)) {
            double percent = number(nullish(first(spec, "value"), first(spec, "percent")), "stop percent");
            if (!(percent > 0 && percent < 1)) throw failure("stop percent must be between 0 and 1");
            value = "long".equals(side) ? entry * (1 - percent) : entry * (1 + percent);
        } else if ("ATR".equals(type) || "ATR_MULTIPLE".equals(type)) {
            double multiple = number(nullish(first(spec, "multiple"), first(spec, "value")), "stop ATR multiple");
            Double atr = atrAt(bars, entryIndex, (int) numberJs(or(first(spec, "period"), numberNode(14))));
            if (atr == null || !(atr > 0)) throw failure("ATR stop lacks sufficient physical history");
            value = "long".equals(side) ? entry - multiple * atr : entry + multiple * atr;
        } else if ("PRIOR_STRUCTURE".equals(type) || "STRUCTURE".equals(type)) {
            int lookback = Math.max(1, (int) truncate(number(nullish(first(spec, "lookback_bars"), numberNode(20)), "structure lookback")));
            int start = Math.max(0, entryIndex - lookback);
            if (start == entryIndex) throw failure("structure stop lacks prior bars");
            double buffer = number(nullish(first(spec, "buffer"), numberNode(0)), "structure buffer");
            if ("long".equals(side)) {
                value = Double.POSITIVE_INFINITY;
                for (int i = start; i < entryIndex; i++) value = Math.min(value, price(bars.get(i), "low"));
                value -= buffer;
            } else {
                value = Double.NEGATIVE_INFINITY;
                for (int i = start; i < entryIndex; i++) value = Math.max(value, price(bars.get(i), "high"));
                value += buffer;
            }
        } else throw failure("unsupported stop type " + type);
        if (present(spec, "min")) value = "long".equals(side) ? Math.max(value, number(first(spec, "min"), "stop min"))
                : Math.min(value, number(first(spec, "min"), "stop min"));
        if (present(spec, "max")) value = "long".equals(side) ? Math.min(value, number(first(spec, "max"), "stop max"))
                : Math.max(value, number(first(spec, "max"), "stop max"));
        if (!(value > 0) || "long".equals(side) && value >= entry || "short".equals(side) && value <= entry) {
            throw failure("stop is not adverse to entry");
        }
        return value;
    }

    private static Double targetFromSpec(JsonNode spec, String side, double entry, Double stop) {
        if (!truthy(spec)) return null;
        String type = jsString(or(first(spec, "type"), first(spec, "kind"), textNode(""))).toUpperCase(Locale.ROOT);
        if ("R".equals(type) || "R_MULTIPLE".equals(type)) {
            double multiple = number(nullish(first(spec, "multiple"), first(spec, "value")), "target R multiple");
            if (!(multiple > 0) || stop == null) throw failure("R target requires positive multiple and stop");
            double distance = Math.abs(entry - stop) * multiple;
            return "long".equals(side) ? entry + distance : entry - distance;
        }
        if ("PERCENT".equals(type) || "PCT".equals(type)) {
            double percent = number(nullish(first(spec, "value"), first(spec, "percent")), "target percent");
            if (!(percent > 0 && percent < 1)) throw failure("target percent must be a fraction between 0 and 1");
            return "long".equals(side) ? entry * (1 + percent) : entry * (1 - percent);
        }
        throw failure("unsupported target type " + type);
    }

    private static List<ObjectNode> normalizePartials(JsonNode partials) {
        if (!truthy(partials)) return new ArrayList<>();
        if (!partials.isArray()) throw failure("partial exits must be an array");
        List<ObjectNode> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode rowNode : partials) {
            if (!rowNode.isObject()) throw failure("partial " + (index + 1) + " lacks an independent trigger");
            if (!present(rowNode, "trigger_r") && !present(rowNode, "r")
                    && !present(rowNode, "trigger_percent") && !present(rowNode, "price")) {
                throw failure("partial " + (index + 1) + " lacks an independent trigger");
            }
            ObjectNode row = ((ObjectNode) rowNode).deepCopy();
            row.put("fraction", number(first(row, "fraction"), "partial " + (index + 1) + ".fraction"));
            row.put("order", index);
            if (!present(row, "trigger_r") && present(row, "r")) row.set("trigger_r", first(row, "r"));
            rows.add(row);
            index++;
        }
        double total = 0;
        for (ObjectNode row : rows) {
            double fraction = numberJs(first(row, "fraction"));
            if (!(fraction > 0 && fraction <= 1)) throw failure("partial exit fractions must be in (0,1]");
            total += fraction;
        }
        if (total > 1 + EPSILON) throw failure("partial exit fractions total more than one");
        rows.sort(Comparator.comparingDouble(TradeLifecycleV5::partialBaseSortKey)
                .thenComparingInt(row -> row.path("order").asInt()));
        return rows;
    }

    private static double partialBaseSortKey(ObjectNode row) {
        return numberJs(nullish(first(row, "trigger_r"), first(row, "r"), first(row, "trigger_percent"), first(row, "price"), numberNode(Double.POSITIVE_INFINITY)));
    }

    private static double partialSortKey(ObjectNode row, double barrier) {
        return numberJs(nullish(first(row, "trigger_r"), first(row, "trigger_percent"), numberNode(barrier)));
    }

    private static Double partialBarrier(ObjectNode row, String side, double entry, Double stop, Double target) {
        if (present(row, "trigger_r") || present(row, "r")) {
            double r = number(nullish(first(row, "trigger_r"), first(row, "r")), "partial trigger_r");
            if (!(r > 0) || stop == null) return null;
            return "long".equals(side) ? entry + Math.abs(entry - stop) * r : entry - Math.abs(entry - stop) * r;
        }
        if (present(row, "trigger_percent")) {
            double percent = number(first(row, "trigger_percent"), "partial trigger_percent");
            if (!(percent > 0 && percent < 1)) throw failure("partial trigger percent must be a fraction");
            return "long".equals(side) ? entry * (1 + percent) : entry * (1 - percent);
        }
        if (present(row, "price")) return number(first(row, "price"), "partial price");
        return target;
    }

    private static double quantityFor(
            JsonNode sizing, double entry, Double stop, double multiplier, JsonNode contract, boolean production) {
        String defaultMode = truthy(first(sizing, "notional_usd")) ? "FIXED_NOTIONAL" : "RISK_USD";
        String mode = jsString(or(first(sizing, "mode"), first(sizing, "type"), textNode(defaultMode))).toUpperCase(Locale.ROOT);
        double quantity;
        if ("RISK_USD".equals(mode) || "FIXED_RISK_BUDGET_USD".equals(mode)) {
            if (stop == null) throw failure("risk sizing requires a stop distance");
            quantity = number(nullish(first(sizing, "risk_usd"), first(sizing, "budget_usd"), first(sizing, "risk_amount_usd")), "risk budget")
                    / (Math.abs(entry - stop) * multiplier);
        } else if ("FIXED_NOTIONAL".equals(mode) || "FIXED_NOTIONAL_USD".equals(mode)) {
            quantity = number(nullish(first(sizing, "notional_usd"), first(sizing, "notional")), "notional")
                    / (entry * multiplier);
        } else if ("VOLATILITY_RISK".equals(mode) || "VOLATILITY_RISK_USD".equals(mode)) {
            double volatility = number(nullish(first(sizing, "volatility"), first(sizing, "atr"), numberNode(0)), "volatility");
            if (!(volatility > 0)) throw failure("volatility-risk sizing requires volatility");
            quantity = number(nullish(first(sizing, "risk_usd"), first(sizing, "budget_usd")), "risk budget")
                    / (volatility * multiplier);
        } else throw failure("unsupported sizing mode " + mode);
        JsonNode stepValue = nullish(first(contract, "step_size"), first(contract, "lot_step"));
        if (production && !(numberJs(stepValue) > 0)) throw failure("production sizing requires bound positive exchange step_size");
        double step = defined(stepValue) ? numberJs(stepValue) : 0;
        quantity = roundDown(quantity, step);
        JsonNode minQuantity = nullish(first(contract, "min_qty"), first(contract, "min_quantity"));
        if (production && !(numberJs(minQuantity) > 0)) throw failure("production sizing requires bound positive min_qty");
        if (defined(minQuantity) && quantity < numberJs(minQuantity)) throw failure("quantity is below exchange minimum");
        double notional = quantity * entry * multiplier;
        if (production && !(numberJs(first(contract, "min_notional")) > 0)) throw failure("production sizing requires bound positive min_notional");
        if (present(contract, "min_notional") && notional < numberJs(first(contract, "min_notional"))) {
            throw failure("quantity is below exchange minimum notional");
        }
        if (production && !(numberJs(first(contract, "max_notional")) > 0)) throw failure("production sizing requires bound positive max_notional");
        if (present(contract, "max_notional") && notional > numberJs(first(contract, "max_notional"))) {
            quantity = roundDown(numberJs(first(contract, "max_notional")) / (entry * multiplier), step);
        }
        if (present(contract, "max_qty")) {
            if (!(numberJs(first(contract, "max_qty")) > 0)) throw failure("bound max_qty must be positive");
            if (quantity > numberJs(first(contract, "max_qty"))) quantity = roundDown(numberJs(first(contract, "max_qty")), step);
        }
        notional = quantity * entry * multiplier;
        if (defined(minQuantity) && quantity < numberJs(minQuantity)
                || present(contract, "min_notional") && notional < numberJs(first(contract, "min_notional"))) {
            throw failure("quantity falls below bound after exchange max clamp");
        }
        if (!(quantity > 0)) throw failure("sizing produced no executable quantity");
        return quantity;
    }

    private static Cost executionCost(
            String side, double fillPrice, double quantity, double multiplier, double feeRate,
            double slippageBps, JsonNode capacity) {
        int sign = "long".equals(side) ? 1 : -1;
        if (!(feeRate >= 0) || !(slippageBps >= 0)) throw failure("fee/slippage rates must be nonnegative");
        double notional = fillPrice * quantity * multiplier;
        double slip = notional * slippageBps / 10_000;
        double fees = notional * feeRate;
        double capacityDebit = 0;
        if (truthy(capacity)) {
            double available = number(first(capacity, "available_liquidity_usd"), "available liquidity");
            double cap = number(first(capacity, "participation_cap"), "participation cap");
            double impact = numberJs(or(first(capacity, "impact_bps"), numberNode(0)));
            if (!(available > 0 && cap > 0 && cap <= 1 && impact >= 0)) throw failure("capacity inputs are invalid");
            if (notional > available * cap) throw failure("order exceeds bound capacity participation cap");
            capacityDebit = notional * impact / 10_000;
        }
        return new Cost(notional, fees, slip, capacityDebit, sign * (fees + slip + capacityDebit));
    }

    private static Funding fundingCost(
            ArrayNode funding, long entryTime, long exitTime, double quantity, double multiplier,
            String side, ArrayNode marks) {
        double total = 0;
        ArrayNode settlements = MAPPER.createArrayNode();
        for (JsonNode row : funding) {
            long at = time(nullish(first(row, "event_time"), first(row, "time"), first(row, "settlement_time")));
            if (at <= entryTime || at > exitTime) continue;
            JsonNode markNode = nullish(first(row, "mark_price"), first(row, "mark"));
            if (!defined(markNode)) {
                for (JsonNode mark : marks) {
                    if (time(nullish(first(mark, "event_time"), first(mark, "time"))) == at) {
                        markNode = first(mark, "price");
                        break;
                    }
                }
            }
            double mark = numberJs(markNode);
            if (!(mark > 0)) throw failure("funding settlement lacks PIT mark");
            double rate = number(nullish(first(row, "rate"), first(row, "funding_rate")), "funding rate");
            double signed = ("long".equals(side) ? -1 : 1) * mark * quantity * multiplier * rate;
            total += signed;
            String eventId = truthy(first(row, "event_id")) ? jsString(first(row, "event_id")) : Long.toString(at);
            settlements.add(MAPPER.createObjectNode().put("event_time", iso(at)).put("event_id", eventId)
                    .put("rate", rate).put("mark_price", mark).put("amount_usd", signed));
        }
        return new Funding(total, settlements);
    }

    private static ObjectNode costNode(Cost cost) {
        return MAPPER.createObjectNode().put("notional", cost.notional).put("fees_usd", cost.feesUsd)
                .put("slippage_usd", cost.slippageUsd).put("capacity_debit_usd", cost.capacityDebitUsd)
                .put("signed_cost_usd", cost.signedCostUsd);
    }

    private static double sum(ArrayNode rows, String field) {
        double total = 0;
        for (JsonNode row : rows) total += numberJs(first(row, field));
        return total;
    }

    private static double roundDown(double value, double step) {
        return step > 0 ? Math.floor((value + EPSILON) / step) * step : value;
    }

    private static String instrumentType(JsonNode intent) {
        String raw = jsString(or(first(intent, "instrument_type"), at(intent, "instrument", "instrument_type"),
                at(intent, "instrument", "type"), first(intent, "type"), textNode("spot"))).toUpperCase(Locale.ROOT);
        if (raw.contains("SPOT")) return "SPOT";
        if (raw.contains("DATED") || raw.contains("FUTURE")) return "DATED_FUTURE";
        if (raw.contains("PERP")) return "PERPETUAL";
        return raw;
    }

    private static String direction(JsonNode intent) {
        String output = jsString(or(first(intent, "direction"), first(intent, "side"), textNode(""))).toLowerCase(Locale.ROOT);
        if (!List.of("long", "short").contains(output)) throw failure("lifecycle direction must be long or short");
        return output;
    }

    private static long barTime(JsonNode bar) {
        return time(nullish(first(bar, "event_time"), first(bar, "time"), first(bar, "open_time"), first(bar, "timestamp")));
    }

    private static double price(JsonNode bar, String key) {
        double output = numberJs(first(bar, key));
        if (!Double.isFinite(output) || output <= 0) throw failure("bar " + key + " must be positive");
        return output;
    }

    private static long time(JsonNode value) {
        if (value != null && value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw failure("invalid timestamp " + jsString(value));
            return (long) number;
        }
        String text = jsString(value);
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant().toEpochMilli();
            } catch (DateTimeParseException alsoIgnored) {
                try {
                    return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (DateTimeParseException finalIgnored) {
                    throw failure("invalid timestamp " + text);
                }
            }
        }
    }

    private static String iso(long epochMillis) {
        return JS_ISO.format(Instant.ofEpochMilli(epochMillis));
    }

    private static double number(JsonNode value, String label) {
        double output = numberJs(value);
        if (!Double.isFinite(output)) throw failure(label + " must be finite");
        return output;
    }

    private static double numberJs(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isTextual()) {
            String text = value.textValue().trim();
            if (text.isEmpty()) return 0;
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static long truncate(double value) {
        return (long) (value < 0 ? Math.ceil(value) : Math.floor(value));
    }

    private static boolean truthy(JsonNode value) {
        if (!defined(value) || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    private static boolean defined(JsonNode value) {
        return value != null && !value.isMissingNode() && !value.isNull();
    }

    private static boolean present(JsonNode node, String key) {
        return node != null && node.isObject() && node.has(key);
    }

    private static JsonNode first(JsonNode node, String key) {
        return node == null || !node.isObject() ? NullNode.getInstance() : node.path(key);
    }

    private static JsonNode at(JsonNode node, String... path) {
        JsonNode cursor = node;
        for (String key : path) {
            if (cursor == null || !cursor.isObject() || !cursor.has(key)) return NullNode.getInstance();
            cursor = cursor.get(key);
        }
        return cursor == null ? NullNode.getInstance() : cursor;
    }

    private static JsonNode nullish(JsonNode... values) {
        for (JsonNode value : values) if (defined(value)) return value;
        return NullNode.getInstance();
    }

    private static JsonNode or(JsonNode... values) {
        for (JsonNode value : values) if (truthy(value)) return value;
        return NullNode.getInstance();
    }

    private static String jsString(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return Boolean.toString(value.booleanValue());
        if (value.isNumber()) return value.asText();
        if (value.isArray()) return value.isEmpty() ? "" : value.toString();
        return "[object Object]";
    }

    private static ObjectNode objectOrEmpty(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : MAPPER.createObjectNode();
    }

    private static ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : MAPPER.createArrayNode();
    }

    private static JsonNode numberNode(double value) {
        return MAPPER.getNodeFactory().numberNode(value);
    }

    private static JsonNode textNode(String value) {
        return MAPPER.getNodeFactory().textNode(value);
    }

    private static String textOrNull(JsonNode value) {
        return defined(value) ? value.asText() : null;
    }

    private static void putNullableNumber(ObjectNode node, String key, Double value) {
        if (value == null) node.putNull(key); else node.put(key, value);
    }

    private static void putNullableText(ObjectNode node, String key, String value) {
        if (value == null) node.putNull(key); else node.put(key, value);
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private record Fill(double price, String fillType) {}
    private record Cost(double notional, double feesUsd, double slippageUsd, double capacityDebitUsd, double signedCostUsd) {}
    private record Funding(double amount, ArrayNode settlements) {}
    private record PartialFill(ObjectNode row, double barrier) {}
    private static final class MutableDouble {
        private Double value;
        private MutableDouble(Double value) { this.value = value; }
    }
    private static final class MutableLong {
        private long value;
        private MutableLong(long value) { this.value = value; }
    }
    @FunctionalInterface
    private interface Settlement {
        void apply(ObjectNode bar, double price, String reason, Double fraction, String fillType);
    }
}
