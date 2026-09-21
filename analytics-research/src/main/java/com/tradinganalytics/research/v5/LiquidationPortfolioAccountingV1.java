package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Shared event-time account core used by both deterministic fixtures and physical replay. */
public final class LiquidationPortfolioAccountingV1 {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal POSITION_CAP = bd("0.05");
    private static final BigDecimal PORTFOLIO_CAP = bd("0.10");
    private static final List<BigDecimal> STAGE_FRACTIONS = List.of(bd("0.01"), bd("0.015"), bd("0.025"));
    private static final List<BigDecimal> LEVERAGE = List.of(bd("2"), bd("2"), bd("3"));

    private LiquidationPortfolioAccountingV1() {}

    /**
     * Processes all asset events on one deterministic chronological account. At equal timestamps the order is
     * mark, funding, pre-entry bar exits, explicit exits, stop updates, entries, then same-minute post-entry paths.
     * Same-time entries use confirmed decision time and the frozen BTC/ETH/SOL/AAVE order.
     */
    static ObjectNode replayFixture(ObjectNode request) {
        AccountSession session = startSession(request);
        for (AccountEvent event : readEvents(request, session.positions.keySet())) {
            session.validateFullPositionRiskBeforeAccept(event);
            session.acceptInternal(event);
        }
        return session.snapshot();
    }

    static AccountSession startSession(ObjectNode request) {
        String schema = text(request, "schema");
        boolean publicReplay = "liquidation-v2-public-replay-account/1".equals(schema);
        if (!publicReplay && !"liquidation-v2-shared-account-fixture/1".equals(schema)) throw fail("unsupported shared-account fixture schema");
        BigDecimal equity = positive(request, "initial_equity_usdt"), reserve = nonnegative(request, "reserved_costs_usdt");
        return new AccountSession(equity, reserve, readPositions(request, equity, publicReplay), true, null);
    }

    /** Bounded-memory production boundary; full event rows are hashed and streamed to the caller. */
    static AccountSession startStreamingSession(ObjectNode request, Consumer<ObjectNode> eventSink) {
        String schema = text(request, "schema");
        boolean publicReplay = "liquidation-v2-public-replay-account/1".equals(schema);
        if (!publicReplay && !"liquidation-v2-shared-account-fixture/1".equals(schema)) throw fail("unsupported shared-account fixture schema");
        BigDecimal equity = positive(request, "initial_equity_usdt"), reserve = nonnegative(request, "reserved_costs_usdt");
        return new AccountSession(equity, reserve, readPositions(request, equity, publicReplay), false, eventSink, false);
    }

    /** Hourly bars are permitted only through this explicitly DEVELOPMENT-only v003 boundary. */
    static AccountSession startHourlyDevelopmentSession(ObjectNode request) {
        if (!"liquidation-v3-hourly-development-account/1".equals(text(request, "schema"))
                || !"H1_OHLC_APPROXIMATION".equals(text(request, "execution_timeframe"))) {
            throw fail("hourly account events require the explicit v003 DEVELOPMENT schema and timeframe");
        }
        BigDecimal equity = positive(request, "initial_equity_usdt"), reserve = nonnegative(request, "reserved_costs_usdt");
        return new AccountSession(equity, reserve, readPositions(request, equity, true), false, null, true);
    }

    /** Incremental account boundary for chronological router/execution interleaving. */
    static final class AccountSession {
        private final BigDecimal initialEquity, reservedCosts;
        private final Map<String, Position> positions;
        private final ArrayNode log = JsonHashes.mapper().createArrayNode(), curve = JsonHashes.mapper().createArrayNode();
        private final ArrayNode closedEpisodes = JsonHashes.mapper().createArrayNode();
        private final Set<String> fundingIds = new HashSet<>(), activeTimestampIdentities = new HashSet<>(),
                attemptedStageOneIntentIds = new HashSet<>(), filledStageOneSetupIds = new HashSet<>();
        private final boolean retainDetailedEvents;
        private final boolean allowHourlyBars;
        private final Consumer<ObjectNode> eventSink;
        private BigDecimal free, portfolioRealized = ZERO, portfolioFunding = ZERO, portfolioEntryCosts = ZERO, portfolioExitCosts = ZERO;
        private BigDecimal unpaidLiability = ZERO;
        private long processedEventCount;
        private String eventStreamHash = "0000000000000000000000000000000000000000000000000000000000000000";
        private LocalDate sampledCurveDay;
        private ObjectNode sampledCurveRow;
        private long identityBucketTime = Long.MIN_VALUE;
        private BigDecimal equityPeak;
        private BigDecimal maximumDrawdown = ZERO;
        private AccountEvent previous;

        private AccountSession(BigDecimal initialEquity, BigDecimal reservedCosts, Map<String, Position> positions,
                boolean retainDetailedEvents, Consumer<ObjectNode> eventSink) {
            this(initialEquity, reservedCosts, positions, retainDetailedEvents, eventSink, false);
        }

        private AccountSession(BigDecimal initialEquity, BigDecimal reservedCosts, Map<String, Position> positions,
                boolean retainDetailedEvents, Consumer<ObjectNode> eventSink, boolean allowHourlyBars) {
            this.initialEquity = initialEquity; this.reservedCosts = reservedCosts; this.positions = positions;
            this.retainDetailedEvents = retainDetailedEvents; this.eventSink = eventSink;
            this.allowHourlyBars = allowHourlyBars; this.equityPeak = initialEquity;
            this.free = initialEquity.subtract(reservedCosts, MC);
            if (free.signum() < 0) throw fail("account reserves exceed the 100% margin cap");
        }

        ObjectNode accept(ObjectNode eventRow) {
            ObjectNode request = JsonHashes.mapper().createObjectNode();
            request.putArray("events").add(eventRow.deepCopy());
            AccountEvent event = readEvents(request, positions.keySet(), allowHourlyBars).get(0);
            if (previous != null && compareEvents(previous, event) > 0) throw fail("account events must arrive in deterministic chronological order");
            validateFullPositionRiskBeforeAccept(event);
            if (identityBucketTime != event.time()) {
                identityBucketTime = event.time(); activeTimestampIdentities.clear();
            }
            String identity = eventIdentity(event);
            if (!activeTimestampIdentities.add(identity)) throw fail("account event identity is repeated");
            if ("FUNDING".equals(event.type()) && !fundingIds.add(text(event.row(), "event_id"))) throw fail("funding event id is repeated");
            return acceptInternal(event);
        }

        private void validateFullPositionRiskBeforeAccept(AccountEvent event) {
            ObjectNode row = event.row();
            if (!"ADD".equals(event.type()) || row.path("stage").asInt(-1) != 1
                    || !row.hasNonNull("full_position_reference_risk_usdt")) return;
            Position p = positions.get(event.asset());
            String setupId = row.path("setup_id").asText("");
            String intentId = row.path("intent_id").asText("");
            String attemptIdentity = intentId.isBlank()
                    ? setupId + "|STAGE1|" + row.path("decision_time").asText(Long.toString(event.time()))
                    : intentId;
            if (p == null || p.referenceEquity != null || p.quantity.signum() != 0
                    || filledStageOneSetupIds.contains(setupId) || attemptedStageOneIntentIds.contains(attemptIdentity)) return;
            BigDecimal reference = markedEquity(initialEquity, positions, portfolioRealized, portfolioFunding,
                    portfolioEntryCosts, portfolioExitCosts);
            BigDecimal declared = positive(row, "full_position_reference_risk_usdt");
            if (declared.compareTo(reference.multiply(bd("0.05"), MC)) != 0) {
                throw fail("staged gap reference risk must equal 5% of first-fill equity");
            }
        }

        boolean hasOpenPosition(String asset) {
            Position position = positions.get(asset.toUpperCase(Locale.ROOT));
            if (position == null) throw fail("position lookup references unknown asset " + asset);
            return position.quantity.signum() > 0;
        }

        String activeSetupId(String asset) {
            Position position = positions.get(asset.toUpperCase(Locale.ROOT));
            if (position == null) throw fail("position lookup references unknown asset " + asset);
            return position.activeSetupId;
        }

        double activeStopPrice(String asset) {
            Position position = positions.get(asset.toUpperCase(Locale.ROOT));
            if (position == null || position.quantity.signum() <= 0 || position.stop == null) {
                throw fail("active stop lookup requires an open configured position for " + asset);
            }
            return position.stop.doubleValue();
        }

        private ObjectNode acceptInternal(AccountEvent event) {
            Position p = positions.get(event.asset());
            if (p == null) throw fail("event references unknown asset " + event.asset());
            p.currentTime = event.time();
            ObjectNode record = JsonHashes.mapper().createObjectNode().put("time", event.time()).put("asset", event.asset()).put("type", event.type());
            switch (event.type()) {
                case "METADATA" -> {
                    applyMetadata(p, event.row()); record.put("type", "METADATA_APPLIED").put("effective_at", event.time());
                }
                case "MARK" -> { p.mark = positive(event.row(), "price"); record.put("mark_price", dec(p.mark)); }
                case "BAR_PRE", "BAR_POST" -> {
                    if ("BAR_PRE".equals(event.type()) && p.quantity.signum() == 0) {
                        p.mark = positive(event.row(), "mark_open"); record.put("type", "BAR_OPEN_NO_POSITION");
                    } else if ("BAR_POST".equals(event.type()) && p.quantity.signum() == 0) {
                        p.mark = positive(event.row(), "mark_close"); record.put("type", "BAR_CLOSE_NO_POSITION");
                    } else {
                        String previouslyLatchedReason = p.pendingVenueExitReason;
                        String previouslyLatchedStatus = p.pendingVenueExitStatus;
                        int previouslyLatchedPriority = p.pendingVenueExitPriority;
                        long previouslyLatchedObservedTime = p.pendingVenueExitObservedTime;
                        p.mark = "BAR_PRE".equals(event.type()) ? positive(event.row(), "mark_open")
                                : (p.longSide ? positive(event.row(), "mark_low") : positive(event.row(), "mark_high"));
                        observeCurrentDrawdown();
                        BarResult result = "BAR_PRE".equals(event.type())
                                ? applyBarOpen(p, event.row(), free, event.time()) : applyBarIntrabar(p, event.row(), free, event.time());
                        free = result.freeAfter();
                        if (result.closed()) {
                            p.realizedGross = p.realizedGross.add(result.grossPnl(), MC); p.exitCosts = p.exitCosts.add(result.exitCosts(), MC);
                            portfolioRealized = portfolioRealized.add(result.grossPnl(), MC); portfolioExitCosts = portfolioExitCosts.add(result.exitCosts(), MC);
                            absorbNegativeFree(record);
                        }
                        record.put("type", result.status()).put("free_balance_after_usdt", dec(free)).put("mark_price", dec(p.mark));
                        if (event.row().path("venue_execution_blocked").asBoolean(false)) {
                            record.put("venue_execution_blocked", true);
                        }
                        if (result.status().endsWith("_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT")) {
                            record.put("latched_exit_reason", p.pendingVenueExitReason)
                                    .put("latched_exit_priority", p.pendingVenueExitPriority)
                                    .put("latched_exit_observed_time", p.pendingVenueExitObservedTime)
                                    .put("outage_trigger_status", p.pendingVenueExitStatus);
                        }
                        if (result.executionGapDebit().signum() > 0) record.put("execution_gap_debit_usdt", dec(result.executionGapDebit()))
                                .put("execution_gap_reference_risk_usdt", dec(result.executionGapReferenceRisk()));
                        if (result.liquidated()) record.put("liquidated", true).put("historical_mechanics_qualification", "UNKNOWN_PROXY_ONLY");
                        if (result.maintenanceUnqualified()) record.put("maintenance_status", "UNQUALIFIED_NO_EFFECTIVE_TIER");
                        if (result.closed()) {
                            record.put("exit_reason", result.reason()).put("exit_price", dec(result.exitPrice()))
                                    .put("gross_pnl_usdt", dec(result.grossPnl())).put("exit_cost_usdt", dec(result.exitCosts()));
                            if (previouslyLatchedReason != null
                                    && !event.row().path("venue_execution_blocked").asBoolean(false)
                                    && !record.path("outage_deferred_exit").asBoolean(false)) {
                                ObjectNode exit = (ObjectNode) p.exits.get(p.exits.size() - 1);
                                long delayMs = Math.max(0L, event.time() - previouslyLatchedObservedTime);
                                String exitPriceBasis = result.liquidated()
                                        ? "MODELED_FIRST_PERMITTED_MINUTE_MARK_OPEN_AFTER_BLACKOUT"
                                        : "MODELED_FIRST_PERMITTED_MINUTE_OPEN_AFTER_BLACKOUT";
                                record.put("outage_deferred_exit", true)
                                        .put("outage_trigger_reason", previouslyLatchedReason)
                                        .put("outage_trigger_status", previouslyLatchedStatus)
                                        .put("outage_trigger_priority", previouslyLatchedPriority)
                                        .put("outage_trigger_observed_time", previouslyLatchedObservedTime)
                                        .put("outage_execution_resume_time", event.time())
                                        .put("outage_execution_delay_ms", delayMs)
                                        .put("exit_price_basis", exitPriceBasis);
                                exit.put("outage_trigger_reason", previouslyLatchedReason)
                                        .put("outage_trigger_status", previouslyLatchedStatus)
                                        .put("outage_trigger_priority", previouslyLatchedPriority)
                                        .put("outage_trigger_observed_time", previouslyLatchedObservedTime)
                                        .put("outage_execution_resume_time", event.time())
                                        .put("outage_execution_delay_ms", delayMs)
                                        .put("outage_exit_price_basis", exitPriceBasis);
                            }
                            if (result.reason().startsWith("VENUE_OUTAGE_DEFERRED_")) {
                                ObjectNode exit = (ObjectNode) p.exits.get(p.exits.size() - 1);
                                record.put("outage_deferred_exit", true)
                                        .put("outage_trigger_reason", exit.path("outage_trigger_reason").asText())
                                        .put("outage_trigger_status", exit.path("outage_trigger_status").asText())
                                        .put("outage_trigger_observed_time", exit.path("outage_trigger_observed_time").asLong())
                                        .put("exit_price_basis", "MODELED_FIRST_PERMITTED_MINUTE_OPEN_AFTER_BLACKOUT");
                            }
                            if (result.reason().endsWith("SIXTY_DAY_LIFECYCLE")) {
                                long due = p.firstFillTime + 60L * 86_400_000L;
                                record.put("deadline_order_time", due).put("deadline_fill_time", event.time())
                                        .put("deadline_execution_overrun_ms", Math.max(0L, event.time() - due));
                            }
                        }
                    }
                    if (p.quantity.signum() == 0) observeCurrentDrawdown();
                }
                case "ADD" -> {
                    int stage = event.row().path("stage").asInt(-1);
                    String setupId = event.row().path("setup_id").asText("");
                    String intentId = event.row().path("intent_id").asText("");
                    String attemptIdentity = intentId.isBlank()
                            ? setupId + "|STAGE1|" + event.row().path("decision_time").asText(Long.toString(event.time()))
                            : intentId;
                    boolean repeatedSetup = !setupId.isEmpty() && stage == 1 && !attemptedStageOneIntentIds.add(attemptIdentity);
                    boolean setupAlreadyFilled = !setupId.isEmpty() && stage == 1 && filledStageOneSetupIds.contains(setupId);
                    if (setupAlreadyFilled) {
                        record.put("type", "ADD_REJECTED").put("stage", stage).put("reason", "STAGE_ONE_SETUP_ALREADY_FILLED");
                        if (p.closed) {
                            closedEpisodes.add(p.toJson());
                            p = p.restart();
                            positions.put(event.asset(), p);
                        }
                    }
                    else if (repeatedSetup) record.put("type", "ADD_REJECTED").put("stage", stage).put("reason", "STAGE_ONE_INTENT_ALREADY_ATTEMPTED");
                    else if (stage == 1 && p.quantity.signum() == 0) {
                        if (p.closed) { closedEpisodes.add(p.toJson()); p = p.restart(); positions.put(event.asset(), p); }
                        // Every fresh stage-one confirmation replaces rejected, still-flat setup geometry.
                        // A live position is never reconfigured here, so its stop and first-fill clock persist.
                        if (!setupId.isEmpty()) configureEpisode(p, event.row());
                    }
                    if (!repeatedSetup && !setupAlreadyFilled) {
                        BigDecimal addFeeDelta = applyAdd(event, p, positions, initialEquity, portfolioRealized, portfolioFunding,
                                portfolioEntryCosts, portfolioExitCosts, free, record);
                        if (record.path("type").asText().equals("ADD_FILLED")) {
                            free = new BigDecimal(record.path("free_balance_after_usdt").asText());
                            portfolioEntryCosts = portfolioEntryCosts.add(addFeeDelta, MC);
                            if (stage == 1 && !setupId.isEmpty()) filledStageOneSetupIds.add(setupId);
                        }
                    }
                }
                case "FUNDING" -> {
                    BigDecimal amount = applyFunding(event, p, positions, free, unpaidLiability,
                            event.row().path("venue_execution_blocked").asBoolean(false), record);
                    if (event.row().path("venue_execution_blocked").asBoolean(false)) record.put("venue_execution_blocked", true);
                    if (record.path("liquidation_trigger_latched_during_venue_blackout").asBoolean(false)) {
                        record.put("latched_exit_reason", p.pendingVenueExitReason)
                                .put("latched_exit_priority", p.pendingVenueExitPriority)
                                .put("latched_exit_observed_time", p.pendingVenueExitObservedTime)
                                .put("outage_trigger_status", p.pendingVenueExitStatus);
                    }
                    if (record.path("type").asText().equals("FUNDING_SETTLED")) {
                        free = new BigDecimal(record.path("free_balance_after_usdt").asText());
                        p.fundingPnl = p.fundingPnl.add(amount, MC);
                        if (amount.signum() < 0) p.fundingDebits = p.fundingDebits.add(amount.abs(), MC);
                        portfolioFunding = portfolioFunding.add(amount, MC);
                        unpaidLiability = new BigDecimal(record.path("account_unpaid_funding_liability_usdt").asText("0"));
                        if (record.path("liquidated").asBoolean(false)) {
                            BigDecimal gross = new BigDecimal(record.path("realized_gross_pnl_usdt").asText());
                            BigDecimal costs = new BigDecimal(record.path("exit_cost_usdt").asText());
                            p.realizedGross = p.realizedGross.add(gross, MC); p.exitCosts = p.exitCosts.add(costs, MC);
                            p.quantity = ZERO; p.margin = ZERO; p.heldCloseReserve = ZERO; p.leverage = ZERO; p.closed = true;
                            portfolioRealized = portfolioRealized.add(gross, MC); portfolioExitCosts = portfolioExitCosts.add(costs, MC);
                            free = new BigDecimal(record.path("free_balance_after_usdt").asText());
                            absorbNegativeFree(record);
                        }
                    }
                }
                case "EXIT" -> {
                    ExitResult result = closePosition(p, positive(event.row(), "price"), text(event.row(), "reason"), free, "EXIT_FILLED");
                    free = result.freeAfter(); p.realizedGross = p.realizedGross.add(result.grossPnl(), MC); p.exitCosts = p.exitCosts.add(result.exitCost(), MC);
                    portfolioRealized = portfolioRealized.add(result.grossPnl(), MC); portfolioExitCosts = portfolioExitCosts.add(result.exitCost(), MC);
                    absorbNegativeFree(record);
                    record.put("type", result.status()).put("price", dec(positive(event.row(), "price"))).put("quantity", dec(result.quantity()))
                            .put("gross_pnl_usdt", dec(result.grossPnl())).put("exit_cost_usdt", dec(result.exitCost())).put("free_balance_after_usdt", dec(free));
                }
                case "STOP_UPDATE" -> {
                    BigDecimal nextStop = positive(event.row(), "stop"); long sourceClose = integer(event.row(), "source_bar_close_time");
                    if (sourceClose > event.time()) throw fail("trailing stop cannot predate completion of its source bar");
                    long sourceAvailable = event.row().has("source_available_at") ? integer(event.row(), "source_available_at") : event.time();
                    if (sourceAvailable != event.time() || sourceAvailable < sourceClose) throw fail("trailing stop may only activate when its source observation is available");
                    if (event.row().path("venue_execution_blocked").asBoolean(false)) {
                        record.put("type", "STOP_UPDATE_BLOCKED_VENUE_BLACKOUT")
                                .put("reason", "VENUE_BLACKOUT_PREVENTS_STOP_ORDER_MODIFICATION")
                                .put("venue_execution_blocked", true);
                    } else {
                        boolean improves = p.longSide ? nextStop.compareTo(p.stop) > 0 : nextStop.compareTo(p.stop) < 0;
                        if (p.quantity.signum() == 0 || !improves || (p.longSide && nextStop.compareTo(p.mark) >= 0)
                                || (!p.longSide && nextStop.compareTo(p.mark) <= 0)) record.put("type", "STOP_UPDATE_REJECTED")
                                        .put("reason", "NO_OPEN_POSITION_OR_NONMONOTONIC_OR_ALREADY_CROSSED");
                        else { p.stop = nextStop; record.put("type", "STOP_UPDATED").put("new_stop", dec(nextStop)).put("source_bar_close_time", sourceClose); }
                    }
                }
                default -> throw fail("unsupported account event type " + event.type());
            }
            previous = event;
            if (!record.has("free_balance_after_usdt")) record.put("free_balance_after_usdt", dec(free));
            record.put("portfolio_equity_usdt", dec(markedEquity(initialEquity, positions, portfolioRealized,
                    portfolioFunding, portfolioEntryCosts, portfolioExitCosts)));
            record.put("portfolio_stop_risk_usdt", dec(portfolioStopRisk(positions)));
            BigDecimal delta = reconciliationDelta();
            if (delta.abs().compareTo(bd("0.00000001")) > 0) throw fail("account cash, isolated margin, reserves, PnL, and liabilities do not reconcile: " + delta);
            record.put("reconciliation_delta_usdt", dec(delta));
            processedEventCount++;
            eventStreamHash = JsonHashes.sha256(eventStreamHash + "\n" + JsonHashes.canonicalSha256(record));
            if (eventSink != null) eventSink.accept(record.deepCopy());
            if (retainDetailedEvents) log.add(record);
            recordCurvePoint(event.time(), new BigDecimal(record.path("portfolio_equity_usdt").asText()));
            return record.deepCopy();
        }

        private void recordCurvePoint(long time, BigDecimal equity) {
            observeDrawdown(equity);
            if (retainDetailedEvents) {
                curve.addObject().put("time", time).put("equity_usdt", dec(equity));
                return;
            }
            LocalDate day = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate();
            if (sampledCurveDay != null && !sampledCurveDay.equals(day)) curve.add(sampledCurveRow);
            sampledCurveDay = day;
            sampledCurveRow = JsonHashes.mapper().createObjectNode().put("time", time).put("equity_usdt", dec(equity));
        }

        private void observeCurrentDrawdown() {
            observeDrawdown(markedEquity(initialEquity, positions, portfolioRealized, portfolioFunding, portfolioEntryCosts, portfolioExitCosts));
        }

        private void observeDrawdown(BigDecimal equity) {
            if (equity.compareTo(equityPeak) > 0) equityPeak = equity;
            if (equityPeak.signum() > 0) {
                BigDecimal drawdown = equityPeak.subtract(equity, MC).divide(equityPeak, MC);
                if (drawdown.compareTo(maximumDrawdown) > 0) maximumDrawdown = drawdown;
            }
        }

        private BigDecimal reconciliationDelta() {
            BigDecimal assets = free.add(reservedCosts, MC);
            for (Position position : positions.values()) {
                assets = assets.add(position.margin, MC).add(position.heldCloseReserve, MC);
                if (position.quantity.signum() > 0) assets = assets.add(unrealizedPnl(position, position.mark), MC);
            }
            BigDecimal equity = markedEquity(initialEquity, positions, portfolioRealized, portfolioFunding, portfolioEntryCosts, portfolioExitCosts);
            return assets.subtract(unpaidLiability, MC).subtract(equity, MC);
        }

        private void absorbNegativeFree(ObjectNode record) {
            if (free.signum() >= 0) return;
            BigDecimal deficit = free.abs(); free = ZERO; unpaidLiability = unpaidLiability.add(deficit, MC);
            record.put("unpaid_account_liability_usdt", dec(deficit)).put("account_unpaid_funding_liability_usdt", dec(unpaidLiability))
                    .put("economic_status", "UNQUALIFIED_COLLATERAL_DEFICIT");
        }

        ObjectNode snapshot() {
            ObjectNode output = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-ledger/1")
                    .put("status", "DEVELOPMENT_SYNTHETIC_ONLY").put("authoritative", false).put("initial_equity_usdt", dec(initialEquity))
                    .put("free_balance_usdt", dec(free)).put("unpaid_funding_liability_usdt", dec(unpaidLiability))
                    .put("event_count", processedEventCount).put("event_stream_sha256", eventStreamHash)
                    .put("maximum_event_path_drawdown_fraction", dec(maximumDrawdown))
                    .put("mark_to_market_equity_usdt", dec(markedEquity(initialEquity, positions, portfolioRealized,
                            portfolioFunding, portfolioEntryCosts, portfolioExitCosts)))
                    .put("realized_gross_pnl_usdt", dec(portfolioRealized)).put("funding_pnl_usdt", dec(portfolioFunding))
                    .put("entry_costs_usdt", dec(portfolioEntryCosts)).put("exit_costs_usdt", dec(portfolioExitCosts))
                    .put("portfolio_stop_risk_usdt", dec(portfolioStopRisk(positions)))
                    .put("free_balance_definition", "SHARED_AVAILABLE_BALANCE_AFTER_ISOLATED_MARGIN_AND_HELD_CLOSE_COST_RESERVES")
                    .put("event_order", "COMPLETED_PRIOR_MINUTE_PATH_THEN_METADATA_AND_MARK_OPEN_THEN_FUNDING_THEN_AVAILABLE_STOP_UPDATE_THEN_OPEN_CHECK_THEN_ENTRIES_THEN_COMPLETED_MINUTE_PATH;DECISION_TIME_AND_FROZEN_ASSET_TIE_ORDER")
                    .put("intraminute_order_assumption", "FUNDING_USES_EXACT_SETTLEMENT_TIME;COMPLETED_ONE_MINUTE_OHLC_PATH_IS_APPLIED_AT_MINUTE_CLOSE;WITHIN_MINUTE_ORDER_IS_UNKNOWN_AND_NOT_CLAIMED;MARK_LIQUIDATION_PRECEDES_STOP_FIRST_EXECUTION_COLLISIONS")
                    .put("marked_drawdown_assumption", "ADVERSE_MARK_OHLC_EXTREMUM_IS_OBSERVED_BEFORE_TRADE_EXIT_PATH;SAME_MINUTE_MULTI_ASSET_EXTREMA_APPLIED_IN_FROZEN_ASSET_ORDER;CONSERVATIVE_SIMULATION_NOT_OBSERVED_SYNCHRONIZED_PATH")
                    .put("deadline_exit_policy", "60X24H_CLOCK_FROM_FIRST_FILL;AT_OR_AFTER_DEADLINE_EXIT_AT_FIRST_PRESENT_ELIGIBLE_1M_OPEN;MISSING_MINUTE_RETAINS_EXPOSURE;NO_SYNTHETIC_FILL;OVERRUN_RECORDED_FROM_ACTUAL_FILL_TIME")
                    .put("risk_netting_credit", false).put("reconciliation_delta_usdt", dec(reconciliationDelta()));
            ArrayNode rows = output.putArray("positions");
            positions.values().stream().sorted(Comparator.comparingInt(position -> assetRank(position.asset))).forEach(position -> rows.add(position.toJson()));
            output.set("closed_episodes", closedEpisodes.deepCopy()); output.set("events", log.deepCopy());
            ArrayNode curveCopy = curve.deepCopy();
            if (!retainDetailedEvents && sampledCurveRow != null) curveCopy.add(sampledCurveRow.deepCopy());
            output.set("marked_equity_curve", curveCopy);
            output.put("content_sha256", JsonHashes.ownHash(output)); return output;
        }
    }

    /** Converts the historical single-asset hand fixture into the shared account boundary. */
    static ObjectNode singlePositionFixtureResult(ObjectNode request) {
        if (!"liquidation-v2-lifecycle-fixture/1".equals(text(request, "schema"))) throw fail("unsupported lifecycle fixture schema");
        if (nonnegative(request, "portfolio_existing_stop_risk_usdt").signum() != 0
                || nonnegative(request, "portfolio_existing_locked_margin_usdt").signum() != 0) {
            throw fail("single-position fixture cannot inject stale external portfolio snapshots");
        }
        ObjectNode shared = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", number(request, "initial_equity_usdt"))
                .put("reserved_costs_usdt", number(request, "reserved_costs_usdt"));
        ObjectNode spec = shared.putArray("positions").addObject().put("asset", text(request, "asset").toUpperCase(Locale.ROOT))
                .put("direction", text(request, "direction").toUpperCase(Locale.ROOT))
                .put("common_stop", number(request, "common_stop")).put("lot_size", number(request, "lot_size"))
                .put("minimum_notional", number(request, "minimum_notional")).put("taker_fee_rate", number(request, "taker_fee_rate"))
                .put("slippage_rate", number(request, "slippage_rate"))
                .put("liquidation_fee_rate", request.has("liquidation_fee_rate") ? number(request, "liquidation_fee_rate") : ZERO)
                .put("initial_mark_price", number(request, "initial_mark_price"));
        JsonNode target = request.get("recovery_target"); if (target == null || target.isNull()) spec.putNull("recovery_target"); else spec.set("recovery_target", target.deepCopy());
        ArrayNode eventRows = shared.putArray("events");
        for (JsonNode add : request.path("add_requests")) {
            ObjectNode row = eventRows.addObject().put("type", "ADD").put("asset", spec.path("asset").asText())
                    .put("time", integer((ObjectNode) add, "time"));
            for (String key : List.of("stage", "price", "previous_completed_minute_base_volume", "mode", "decision_mark")) {
                if (add.has(key)) row.set(key, add.path(key).deepCopy());
            }
        }
        for (JsonNode funding : request.path("funding_events")) {
            eventRows.addObject().put("type", "FUNDING").put("asset", spec.path("asset").asText())
                    .put("time", integer((ObjectNode) funding, "settlement_time"))
                    .put("event_id", text((ObjectNode) funding, "event_id"))
                    .put("funding_rate", number((ObjectNode) funding, "funding_rate"))
                    .put("mark_price", number((ObjectNode) funding, "mark_price"));
        }
        for (JsonNode exit : request.path("exit_events")) {
            eventRows.addObject().put("type", "EXIT").put("asset", spec.path("asset").asText())
                    .put("time", integer((ObjectNode) exit, "time")).put("price", number((ObjectNode) exit, "price"))
                    .put("reason", text((ObjectNode) exit, "reason"));
        }
        ObjectNode ledger = replayFixture(shared);
        JsonNode position = ledger.path("positions").get(0);
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-lifecycle-ledger/1")
                .put("status", position.path("status").asText()).put("evidence_tier", "DEVELOPMENT_SYNTHETIC_ONLY")
                .put("authoritative", false).put("asset", position.path("asset").asText()).put("direction", position.path("direction").asText())
                .put("reference_equity_usdt", position.path("reference_equity_usdt").decimalValue())
                .put("initial_equity_usdt", ledger.path("initial_equity_usdt").decimalValue())
                .put("first_fill_time", position.path("first_fill_time").asLong(-1)).put("quantity", position.path("quantity").decimalValue())
                .put("weighted_entry", position.path("weighted_entry").decimalValue()).put("common_stop", position.path("common_stop").decimalValue())
                .put("last_mark_price", position.path("last_mark_price").decimalValue())
                .put("gross_unrealized_pnl_usdt", position.path("gross_unrealized_pnl_usdt").decimalValue())
                .put("entry_costs_usdt", position.path("entry_costs_usdt").decimalValue())
                .put("realized_gross_pnl_usdt", position.path("realized_gross_pnl_usdt").decimalValue())
                .put("exit_costs_usdt", position.path("exit_costs_usdt").decimalValue())
                .put("funding_pnl_usdt", position.path("funding_pnl_usdt").decimalValue())
                .put("funding_debits_for_risk_headroom_usdt", position.path("funding_debits_for_risk_headroom_usdt").decimalValue())
                .put("open_stop_risk_usdt", position.path("open_stop_risk_usdt").decimalValue())
                .put("estimated_close_cost_reserve_usdt", position.path("estimated_close_cost_reserve_usdt").decimalValue())
                .put("risk_committed_usdt", position.path("risk_committed_usdt").decimalValue())
                .put("mark_to_market_equity_usdt", ledger.path("mark_to_market_equity_usdt").decimalValue())
                .put("isolated_margin_locked_usdt", position.path("isolated_margin_locked_usdt").decimalValue())
                .put("current_position_leverage", position.path("current_position_leverage").decimalValue())
                .put("free_collateral_usdt", ledger.path("free_balance_usdt").decimalValue()).put("next_stage", position.path("next_stage").asInt());
        if (position.path("recovery_target").isNull()) result.putNull("recovery_target"); else result.set("recovery_target", position.path("recovery_target").deepCopy());
        result.set("fills", position.path("fills").deepCopy()); result.set("funding_events", position.path("funding_events").deepCopy());
        result.set("events", ledger.path("events").deepCopy()); result.set("exits", position.path("exits").deepCopy());
        result.put("funding_event_order", "BEFORE_SAME_TIMESTAMP_ENTRIES; OPEN_QUANTITY_ONLY");
        result.put("collateral_model", "ENTRY_NOTIONAL_DIVIDED_BY_CURRENT_COMMON_POSITION_LEVERAGE; STAGE3_RELEASES_EXCESS; NO_AUTO_TOP_UP");
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static BigDecimal applyAdd(AccountEvent event, Position p, Map<String, Position> positions,
            BigDecimal startingEquity, BigDecimal realized, BigDecimal funding, BigDecimal entryCosts,
            BigDecimal exitCosts, BigDecimal free, ObjectNode result) {
        ObjectNode row = event.row(); int stage = row.path("stage").asInt(-1);
        BigDecimal price = positive(row, "price"); BigDecimal decisionMark = row.has("decision_mark") ? positive(row, "decision_mark") : price;
        BigDecimal capacity = nonnegative(row, "previous_completed_minute_base_volume");
        p.mark = row.has("mark_price") ? positive(row, "mark_price") : decisionMark;
        String reason = null;
        if (stage != p.nextStage || stage < 1 || stage > 3) reason = "INVALID_OR_NONSEQUENTIAL_STAGE";
        if (p.closed) reason = "POSITION_ALREADY_CLOSED";
        long hour = Math.floorDiv(event.time(), 3_600_000L);
        if (reason == null && hour == p.lastEntryHour) reason = "ENTRY_HOUR_ALREADY_USED";
        if (reason == null && p.firstFillTime >= 0 && event.time() - p.firstFillTime >= 60L * 86_400_000L) reason = "SIXTY_DAY_LIFECYCLE_EXPIRED";
        String mode = row.path("mode").asText("CONTINUATION").toUpperCase(Locale.ROOT);
        if (reason == null && "REVERSAL".equals(mode) && p.target == null) reason = "REVERSAL_TARGET_MISSING";
        if (reason == null && p.longSide && p.stop.compareTo(price) >= 0) reason = "INVALID_LONG_STOP";
        if (reason == null && !p.longSide && p.stop.compareTo(price) <= 0) reason = "INVALID_SHORT_STOP";
        BigDecimal sign = p.longSide ? BigDecimal.ONE : BigDecimal.ONE.negate();
        if (reason == null && "REVERSAL".equals(mode)) {
            if (p.longSide && p.target.compareTo(price) <= 0 || !p.longSide && p.target.compareTo(price) >= 0) reason = "INVALID_RECOVERY_TARGET";
            else {
                BigDecimal reward = p.target.subtract(price, MC).multiply(sign, MC);
                BigDecimal risk = price.subtract(p.stop, MC).multiply(sign, MC);
                BigDecimal netReward = reward.subtract(price.multiply(p.costRate, MC), MC).subtract(p.target.multiply(p.costRate, MC), MC);
                BigDecimal netRisk = risk.add(price.multiply(p.costRate, MC), MC).add(p.stop.multiply(p.costRate, MC), MC);
                if (risk.signum() <= 0 || netReward.signum() <= 0 || netReward.compareTo(netRisk) < 0) reason = "REVERSAL_ADDITION_RR_BELOW_1R_OR_TARGET_REACHED";
                if (decisionMark.subtract(p.target, MC).multiply(sign, MC).signum() >= 0) reason = "REVERSAL_TARGET_ALREADY_REACHED";
            }
        }
        if (reason == null && capacity.signum() <= 0) reason = "NO_PRIOR_COMPLETED_MINUTE_CAPACITY";
        if (reason != null) { result.put("type", "ADD_REJECTED").put("stage", stage).put("reason", reason); return ZERO; }

        BigDecimal equity = markedEquity(startingEquity, positions, realized, funding, entryCosts, exitCosts);
        BigDecimal reference = p.referenceEquity == null ? equity : p.referenceEquity;
        BigDecimal currentPositionRisk = stopRisk(p).add(p.entryCosts, MC).add(p.fundingDebits, MC).add(closeReserve(p), MC);
        BigDecimal totalRisk = portfolioStopRisk(positions);
        BigDecimal otherRisk = totalRisk.subtract(currentPositionRisk, MC).max(ZERO);
        BigDecimal positionCap = reference.multiply(POSITION_CAP, MC).min(equity.multiply(POSITION_CAP, MC));
        BigDecimal portfolioCap = equity.multiply(PORTFOLIO_CAP, MC);
        BigDecimal tranche = reference.multiply(STAGE_FRACTIONS.get(stage - 1), MC);
        BigDecimal budget = tranche.min(positionCap.subtract(currentPositionRisk, MC))
                .min(portfolioCap.subtract(otherRisk, MC).subtract(currentPositionRisk, MC));
        BigDecimal unitStop = price.subtract(p.stop, MC).multiply(sign, MC);
        if (unitStop.signum() <= 0) throw fail("accepted candidate has nonpositive directional stop distance");
        BigDecimal unitRisk = unitStop.add(price.multiply(p.costRate, MC), MC).add(p.stop.multiply(p.costRate, MC), MC);
        BigDecimal byRisk = budget.signum() > 0 ? budget.divide(unitRisk, MC) : ZERO;
        BigDecimal byVolume = capacity.multiply(bd("0.01"), MC);
        BigDecimal leverage = LEVERAGE.get(stage - 1);
        BigDecimal marginRoom = free.add(p.margin, MC).subtract(p.entryNotional.divide(leverage, MC), MC);
        BigDecimal perUnitCollateral = price.divide(leverage, MC).add(price.multiply(p.costRate, MC), MC).add(p.stop.multiply(p.costRate, MC), MC);
        BigDecimal byCollateral = marginRoom.signum() > 0 ? marginRoom.divide(perUnitCollateral, MC) : ZERO;
        BigDecimal q = floorToLot(byRisk.min(byVolume).min(byCollateral), p.lotSize);
        if (q.signum() <= 0 || q.multiply(price, MC).compareTo(p.minimumNotional) < 0) {
            result.put("type", "ADD_REJECTED").put("stage", stage).put("reason", "RISK_CAPACITY_COLLATERAL_OR_MINIMUM_NOTIONAL"); return ZERO;
        }
        BigDecimal notional = q.multiply(price, MC), fee = notional.multiply(p.feeRate, MC), slip = notional.multiply(p.slippageRate, MC);
        BigDecimal nextNotional = p.entryNotional.add(notional, MC), nextMargin = nextNotional.divide(leverage, MC);
        BigDecimal reserveDelta = q.multiply(p.stop, MC).multiply(p.costRate, MC);
        BigDecimal debit = nextMargin.subtract(p.margin, MC).add(fee, MC).add(slip, MC).add(reserveDelta, MC);
        if (debit.compareTo(free) > 0) { result.put("type", "ADD_REJECTED").put("stage", stage).put("reason", "INSUFFICIENT_FREE_COLLATERAL"); return ZERO; }
        p.quantity = p.quantity.add(q, MC); p.entryNotional = nextNotional; p.weightedEntryNumerator = p.weightedEntryNumerator.add(notional, MC);
        p.margin = nextMargin; p.leverage = leverage; p.heldCloseReserve = p.heldCloseReserve.add(reserveDelta, MC);
        p.entryCosts = p.entryCosts.add(fee, MC).add(slip, MC);
        p.fills.addObject().put("stage", stage).put("time", event.time()).put("direction", p.direction).put("quantity", dec(q))
                .put("price", dec(price)).put("notional_usdt", dec(notional)).put("planned_tranche_risk_usdt", dec(tranche))
                .put("stage_leverage", dec(leverage)).put("entry_fee_usdt", dec(fee)).put("slippage_cost_usdt", dec(slip)).put("common_stop", dec(p.stop));
        if (p.target != null) ((ObjectNode) p.fills.get(p.fills.size() - 1)).put("recovery_target", dec(p.target));
        if (p.referenceEquity == null) {
            p.referenceEquity = reference; p.firstFillTime = event.time();
            if (row.has("full_position_reference_risk_usdt")) {
                BigDecimal frozenRisk = positive(row, "full_position_reference_risk_usdt");
                if (frozenRisk.compareTo(reference.multiply(bd("0.05"), MC)) != 0) {
                    throw fail("staged gap reference risk must equal 5% of first-fill equity");
                }
                p.fullPositionReferenceRisk = frozenRisk;
            }
        }
        p.lastFillTime = event.time();
        p.lastEntryHour = hour; p.nextStage++;
        BigDecimal freeAfter = free.subtract(debit, MC);
        result.put("type", "ADD_FILLED").put("stage", stage).put("quantity", dec(q)).put("price", dec(price))
                .put("risk_limited_by", limiting(byRisk, byVolume, byCollateral)).put("free_balance_after_usdt", dec(freeAfter));
        return fee.add(slip, MC);
    }

    private static BigDecimal applyFunding(AccountEvent event, Position p, Map<String, Position> positions, BigDecimal free,
            BigDecimal accountLiability, boolean executionBlocked, ObjectNode record) {
        ObjectNode row = event.row(); BigDecimal rate = number(row, "funding_rate"), mark = positive(row, "mark_price");
        String id = text(row, "event_id"); p.mark = mark;
        if (p.quantity.signum() == 0 || p.firstFillTime < 0 || event.time() <= p.firstFillTime) {
            p.fundingEvents.addObject().put("event_id", id).put("settlement_time", event.time()).put("quantity_open", 0)
                    .put("rate", dec(rate)).put("mark_price", dec(mark)).put("amount_usdt", 0).put("status", "NO_POSITION_AT_SETTLEMENT");
            record.put("type", "FUNDING_NO_POSITION").put("event_id", id); return ZERO;
        }
        BigDecimal amount = p.quantity.multiply(mark, MC).multiply(rate, MC).multiply(p.longSide ? bd("-1") : BigDecimal.ONE, MC);
        BigDecimal freeAfter = free;
        BigDecimal marginDebit = ZERO;
        BigDecimal deficit = ZERO, liabilityAfter = accountLiability;
        if (amount.signum() < 0) {
            BigDecimal debit = amount.abs(); BigDecimal fromFree = debit.min(freeAfter); freeAfter = freeAfter.subtract(fromFree, MC);
            marginDebit = debit.subtract(fromFree, MC).min(p.margin); p.margin = p.margin.subtract(marginDebit, MC);
            deficit = debit.subtract(fromFree, MC).subtract(marginDebit, MC).max(ZERO);
            liabilityAfter = liabilityAfter.add(deficit, MC);
            if (deficit.signum() > 0) p.fundingMechanicsUnqualified = true;
        } else {
            BigDecimal debtPayment = amount.min(liabilityAfter);
            liabilityAfter = liabilityAfter.subtract(debtPayment, MC);
            freeAfter = freeAfter.add(amount.subtract(debtPayment, MC), MC);
        }
        p.fundingEvents.addObject().put("event_id", id).put("settlement_time", event.time()).put("quantity_open", dec(p.quantity))
                .put("charged_quantity_at_settlement", dec(p.quantity))
                .put("rate", dec(rate)).put("mark_price", dec(mark)).put("amount_usdt", dec(amount))
                .put("status", rate.signum() == 0 ? "ZERO_RATE_RETAINED" : "SETTLED")
                .put("available_balance_debit_usdt", dec(amount.signum() < 0 ? amount.abs().min(free) : ZERO))
                .put("isolated_margin_debit_usdt", dec(marginDebit)).put("unfunded_deficit_usdt", dec(deficit))
                .put("liability_paid_usdt", dec(amount.signum() > 0 ? amount.min(accountLiability) : ZERO));
        record.put("type", "FUNDING_SETTLED").put("event_id", id).put("quantity", dec(p.quantity))
                .put("amount_usdt", dec(amount)).put("available_balance_debit_usdt", dec(amount.signum() < 0 ? amount.abs().min(free) : ZERO))
                .put("isolated_margin_debit_usdt", dec(marginDebit)).put("unfunded_deficit_usdt", dec(deficit))
                .put("account_unpaid_funding_liability_usdt", dec(liabilityAfter)).put("free_balance_after_usdt", dec(freeAfter));
        // Current documented available-balance-first mechanics are used as a disclosed proxy. The maintenance check
        // is fail-closed when no applicable tier exists; never veto funding or silently restore collateral.
        if (amount.signum() < 0 && p.quantity.signum() > 0 && !maintenanceCovered(p, event.time())) {
            record.put("maintenance_status", "UNQUALIFIED_NO_EFFECTIVE_TIER").put("historical_mechanics_qualification", "UNKNOWN_PROXY_ONLY");
        } else if (amount.signum() < 0 && p.quantity.signum() > 0
                && (deficit.signum() > 0 || breachedMaintenance(p, mark, event.time()))) {
            if (p.pendingVenueExitReason != null && p.pendingVenueExitPriority == 0) {
                // A settlement at the reopening timestamp is still ordered before BAR_PRE. Preserve the
                // already-latched liquidation so it executes at the first eligible open, not at funding time.
                record.put("liquidation_latch_preserved_until_open", true)
                        .put("latched_exit_reason", p.pendingVenueExitReason)
                        .put("outage_trigger_status", p.pendingVenueExitStatus)
                        .put("latched_exit_priority", p.pendingVenueExitPriority)
                        .put("latched_exit_observed_time", p.pendingVenueExitObservedTime)
                        .put("maintenance_status", "LIQUIDATION_PENDING_FIRST_EXECUTABLE_OPEN")
                        .put("historical_mechanics_qualification", "UNKNOWN_PROXY_ONLY");
            } else if (executionBlocked) {
                latchVenueExit(p, "LIQUIDATION", 0, event.time(), freeAfter, false,
                        "LIQUIDATION_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
                record.put("liquidation_trigger_latched_during_venue_blackout", true)
                        .put("maintenance_status", "BREACHED_AFTER_FUNDING")
                        .put("historical_mechanics_qualification", "UNKNOWN_PROXY_ONLY")
                        .put("free_balance_after_usdt", dec(freeAfter));
            } else {
            BigDecimal heldReserve = p.heldCloseReserve;
            LiquidationResult liquidation = liquidate(p, mark);
            freeAfter = freeAfter.add(liquidation.marginReleased(), MC).add(heldReserve, MC)
                    .add(liquidation.grossPnl(), MC).subtract(liquidation.fee(), MC);
            record.put("liquidated", true).put("liquidation_price", dec(mark)).put("liquidation_reason", "FUNDING_MARGIN_DEBIT_MAINTENANCE_BREACH")
                    .put("realized_gross_pnl_usdt", dec(liquidation.grossPnl())).put("exit_cost_usdt", dec(liquidation.fee()))
                    .put("maintenance_status", "BREACHED_AFTER_FUNDING").put("historical_mechanics_qualification", "UNKNOWN_PROXY_ONLY");
            record.put("free_balance_after_usdt", dec(freeAfter));
            p.fundingMechanicsUnqualified = true;
            }
        } else if (amount.signum() < 0 && p.quantity.signum() > 0 && p.fundingMechanicsUnqualified) {
            record.put("economic_status", "UNQUALIFIED_ISOLATED_FUNDING_DEFICIT");
        }
        return amount;
    }

    /** Opening phase uses only opening prices before this minute's admissions. */
    private static BarResult applyBarOpen(Position p, ObjectNode row, BigDecimal free, long time) {
        BigDecimal markOpen = positive(row, "mark_open"), tradeOpen = positive(row, "trade_open");
        p.mark = markOpen;
        boolean executionBlocked = row.path("venue_execution_blocked").asBoolean(false);
        long intervalStart = integer(row, "bar_start_time");
        boolean unqualified = !maintenanceCovered(p, intervalStart);
        if (p.pendingVenueExitReason != null && p.pendingVenueExitPriority == 0 && !executionBlocked) {
            return executeLatchedVenueExit(p, tradeOpen, markOpen, free, time, unqualified);
        }
        if (!unqualified && breachedMaintenance(p, markOpen, intervalStart)) {
            if (executionBlocked) return latchVenueExit(p, "LIQUIDATION", 0, time, free, unqualified, "LIQUIDATION_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            return liquidateAt(p, markOpen, free, true, false);
        }
        if (p.pendingVenueExitReason != null && !executionBlocked) {
            return executeLatchedVenueExit(p, tradeOpen, markOpen, free, time, unqualified);
        }
        long lifecycleDeadline = p.firstFillTime < 0 ? Long.MAX_VALUE : p.firstFillTime + 60L * 86_400_000L;
        if (p.firstFillTime >= 0 && time > lifecycleDeadline) {
            if (executionBlocked) return latchVenueExit(p, "SIXTY_DAY_LIFECYCLE", 1, time, free, unqualified, "SIXTY_DAY_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            ExitResult closed = closePosition(p, tradeOpen, "SIXTY_DAY_LIFECYCLE", free, "EXIT_TIME_LIMIT");
            return new BarResult(closed.freeAfter(), true, false, unqualified, closed.grossPnl(), closed.exitCost(), tradeOpen, "SIXTY_DAY_LIFECYCLE", "EXIT_TIME_LIMIT", ZERO, ZERO);
        }
        boolean stopGap = p.longSide ? tradeOpen.compareTo(p.stop) <= 0 : tradeOpen.compareTo(p.stop) >= 0;
        boolean targetGap = p.target != null && (p.longSide ? tradeOpen.compareTo(p.target) >= 0 : tradeOpen.compareTo(p.target) <= 0);
        if (stopGap) {
            if (executionBlocked) return latchVenueExit(p, "STOP_GAP", 2, time, free, unqualified, "STOP_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            GapAdjustedExit adjusted = adverseGapExit(p, row, tradeOpen, "STOP_GAP");
            ExitResult closed = closePosition(p, adjusted.price(), "STOP_GAP", free, "EXIT_STOP_GAP");
            return new BarResult(closed.freeAfter(), true, false, unqualified, closed.grossPnl(), closed.exitCost(), adjusted.price(), "STOP_GAP", "EXIT_STOP_GAP", adjusted.debit(), adjusted.referenceRisk());
        }
        if (targetGap) {
            if (executionBlocked) return latchVenueExit(p, "RECOVERY_TARGET_GAP", 3, time, free, unqualified, "TARGET_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            ExitResult closed = closePosition(p, tradeOpen, "RECOVERY_TARGET_GAP", free, "EXIT_TARGET_GAP");
            return new BarResult(closed.freeAfter(), true, false, unqualified, closed.grossPnl(), closed.exitCost(), tradeOpen, "RECOVERY_TARGET_GAP", "EXIT_TARGET_GAP", ZERO, ZERO);
        }
        return new BarResult(free, false, false, unqualified, ZERO, ZERO, ZERO, "", executionBlocked ? "VENUE_BLACKOUT_OPEN_POSITION_RETAINED"
                : unqualified ? "BAR_OPEN_WITH_UNKNOWN_MAINTENANCE_TIER" : "BAR_OPEN_PROCESSED", ZERO, ZERO);
    }

    /** Intrabar market path runs after entries; mark liquidation precedes stop-first execution collisions. */
    private static BarResult applyBarIntrabar(Position p, ObjectNode row, BigDecimal free, long time) {
        BigDecimal markHigh = positive(row, "mark_high"), markLow = positive(row, "mark_low"), markClose = positive(row, "mark_close");
        BigDecimal tradeOpen = positive(row, "trade_open"), tradeHigh = positive(row, "trade_high"), tradeLow = positive(row, "trade_low");
        BigDecimal adverseMark = p.longSide ? markLow : markHigh;
        p.mark = adverseMark;
        boolean executionBlocked = row.path("venue_execution_blocked").asBoolean(false);
        long intervalStart = integer(row, "bar_start_time");
        boolean unqualified = !maintenanceCovered(p, intervalStart);
        if (!unqualified && breachedMaintenance(p, adverseMark, intervalStart)) {
            if (executionBlocked) return latchVenueExit(p, "LIQUIDATION", 0, time, free, unqualified, "LIQUIDATION_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            return liquidateAt(p, adverseMark, free, true, false);
        }
        boolean stopHit = p.longSide ? tradeLow.compareTo(p.stop) <= 0 : tradeHigh.compareTo(p.stop) >= 0;
        boolean targetHit = p.target != null && (p.longSide ? tradeHigh.compareTo(p.target) >= 0 : tradeLow.compareTo(p.target) <= 0);
        if (stopHit) {
            if (executionBlocked) return latchVenueExit(p, "STOP", 2, time, free, unqualified, "STOP_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            BigDecimal exit = p.longSide ? tradeOpen.min(p.stop) : tradeOpen.max(p.stop);
            GapAdjustedExit adjusted = adverseGapExit(p, row, exit, "STOP");
            exit = adjusted.price();
            ExitResult closed = closePosition(p, exit, "STOP", free, "EXIT_STOP");
            return new BarResult(closed.freeAfter(), true, false, unqualified, closed.grossPnl(), closed.exitCost(), exit, "STOP", "EXIT_STOP", adjusted.debit(), adjusted.referenceRisk());
        }
        if (targetHit) {
            if (executionBlocked) return latchVenueExit(p, "RECOVERY_TARGET", 3, time, free, unqualified, "TARGET_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT");
            BigDecimal exit = p.longSide ? tradeOpen.max(p.target) : tradeOpen.min(p.target);
            ExitResult closed = closePosition(p, exit, "RECOVERY_TARGET", free, "EXIT_TARGET");
            return new BarResult(closed.freeAfter(), true, false, unqualified, closed.grossPnl(), closed.exitCost(), exit, "RECOVERY_TARGET", "EXIT_TARGET", ZERO, ZERO);
        }
        p.mark = markClose;
        return new BarResult(free, false, false, unqualified, ZERO, ZERO, ZERO, "", executionBlocked ? "VENUE_BLACKOUT_OPEN_POSITION_RETAINED"
                : unqualified ? "BAR_PROCESSED_WITH_UNKNOWN_MAINTENANCE_TIER" : "BAR_PROCESSED", ZERO, ZERO);
    }

    private static BarResult latchVenueExit(Position p, String reason, int priority, long observedTime,
            BigDecimal free, boolean maintenanceUnqualified, String status) {
        if (p.pendingVenueExitReason == null || priority < p.pendingVenueExitPriority) {
            p.pendingVenueExitReason = reason;
            p.pendingVenueExitStatus = status;
            p.pendingVenueExitPriority = priority;
            p.pendingVenueExitObservedTime = observedTime;
        }
        return new BarResult(free, false, false, maintenanceUnqualified, ZERO, ZERO, ZERO, p.pendingVenueExitReason, status, ZERO, ZERO);
    }

    private static BarResult executeLatchedVenueExit(Position p, BigDecimal tradeOpen, BigDecimal markOpen,
            BigDecimal free, long time, boolean maintenanceUnqualified) {
        String reason = p.pendingVenueExitReason;
        int priority = p.pendingVenueExitPriority;
        long triggerTime = p.pendingVenueExitObservedTime;
        if (priority == 0) {
            BarResult result = liquidateAt(p, markOpen, free, true, maintenanceUnqualified);
            ObjectNode exit = (ObjectNode) p.exits.get(p.exits.size() - 1);
            exit.put("outage_trigger_reason", reason).put("outage_trigger_priority", priority)
                    .put("outage_trigger_status", p.pendingVenueExitStatus)
                    .put("outage_trigger_observed_time", triggerTime)
                    .put("outage_execution_resume_time", time)
                    .put("outage_exit_price_basis", "MODELED_FIRST_PERMITTED_MINUTE_MARK_OPEN_AFTER_BLACKOUT");
            p.pendingVenueExitReason = null; p.pendingVenueExitStatus = null;
            p.pendingVenueExitPriority = Integer.MAX_VALUE; p.pendingVenueExitObservedTime = -1;
            return new BarResult(result.freeAfter(), true, true, maintenanceUnqualified, result.grossPnl(),
                    result.exitCosts(), result.exitPrice(), "VENUE_OUTAGE_DEFERRED_LIQUIDATION", "EXIT_LIQUIDATION",
                    ZERO, ZERO);
        }
        String deferredReason = "VENUE_OUTAGE_DEFERRED_" + reason;
        ExitResult closed = closePosition(p, tradeOpen, deferredReason, free, "EXIT_VENUE_OUTAGE_DEFERRED");
        ObjectNode exit = (ObjectNode) p.exits.get(p.exits.size() - 1);
        exit.put("outage_trigger_reason", reason).put("outage_trigger_priority", priority)
                .put("outage_trigger_status", p.pendingVenueExitStatus)
                .put("outage_trigger_observed_time", triggerTime)
                .put("outage_execution_resume_time", time)
                .put("outage_exit_price_basis", "MODELED_FIRST_PERMITTED_MINUTE_OPEN_AFTER_BLACKOUT");
        p.pendingVenueExitReason = null; p.pendingVenueExitStatus = null;
        p.pendingVenueExitPriority = Integer.MAX_VALUE; p.pendingVenueExitObservedTime = -1;
        return new BarResult(closed.freeAfter(), true, false, maintenanceUnqualified, closed.grossPnl(),
                closed.exitCost(), tradeOpen, deferredReason, "EXIT_VENUE_OUTAGE_DEFERRED", ZERO, ZERO);
    }

    private static GapAdjustedExit adverseGapExit(Position p, ObjectNode row, BigDecimal stopExit, String reason) {
        BigDecimal gapR = row.has("adverse_gap_r") ? nonnegative(row, "adverse_gap_r") : ZERO;
        if (gapR.signum() == 0 || !("STOP".equals(reason) || "STOP_GAP".equals(reason)) || p.quantity.signum() == 0) {
            return new GapAdjustedExit(stopExit, ZERO, ZERO);
        }
        BigDecimal referenceRisk = p.fullPositionReferenceRisk;
        if (referenceRisk.signum() <= 0) {
            for (JsonNode fill : p.fills) if (fill.path("planned_tranche_risk_usdt").isNumber()) {
                referenceRisk = referenceRisk.add(fill.path("planned_tranche_risk_usdt").decimalValue(), MC);
            }
        }
        if (referenceRisk.signum() <= 0) throw fail("adverse gap stress requires a positive position reference risk");
        BigDecimal debit = referenceRisk.multiply(gapR, MC);
        BigDecimal perUnit = debit.divide(p.quantity, MC);
        BigDecimal adjusted = p.longSide ? stopExit.subtract(perUnit, MC) : stopExit.add(perUnit, MC);
        if (adjusted.signum() <= 0) throw fail("adverse gap stress produced a nonpositive execution price");
        return new GapAdjustedExit(adjusted, debit, referenceRisk);
    }

    private record GapAdjustedExit(BigDecimal price, BigDecimal debit, BigDecimal referenceRisk) {}

    private static BarResult liquidateAt(Position p, BigDecimal mark, BigDecimal free, boolean liquidated, boolean maintenanceUnqualified) {
        p.mark = mark;
        BigDecimal held = p.heldCloseReserve;
        LiquidationResult result = liquidate(p, mark);
        BigDecimal after = free.add(result.marginReleased(), MC).add(held, MC).add(result.grossPnl(), MC).subtract(result.fee(), MC);
        return new BarResult(after, true, liquidated, maintenanceUnqualified, result.grossPnl(), result.fee(), mark,
                "LIQUIDATION", "EXIT_LIQUIDATION", ZERO, ZERO);
    }

    private static ExitResult closePosition(Position p, BigDecimal price, String reason, BigDecimal free, String status) {
        if (p.quantity.signum() == 0) return new ExitResult(free, ZERO, ZERO, ZERO, "EXIT_SKIPPED_NO_POSITION");
        BigDecimal q = p.quantity, gross = price.multiply(q, MC).subtract(p.weightedEntryNumerator, MC)
                .multiply(p.longSide ? BigDecimal.ONE : BigDecimal.ONE.negate(), MC);
        BigDecimal cost = price.multiply(q, MC).multiply(p.costRate, MC);
        BigDecimal after = free.add(p.margin, MC).add(p.heldCloseReserve, MC).add(gross, MC).subtract(cost, MC);
        p.exits.addObject().put("time", p.currentTime).put("price", dec(price)).put("reason", reason)
                .put("quantity", dec(q)).put("gross_pnl_usdt", dec(gross)).put("exit_costs_usdt", dec(cost));
        p.quantity = ZERO; p.margin = ZERO; p.heldCloseReserve = ZERO; p.leverage = ZERO; p.closed = true; p.mark = price;
        return new ExitResult(after, gross, cost, q, status);
    }

    private static LiquidationResult liquidate(Position p, BigDecimal price) {
        BigDecimal q = p.quantity; BigDecimal gross = price.multiply(q, MC).subtract(p.weightedEntryNumerator, MC)
                .multiply(p.longSide ? BigDecimal.ONE : BigDecimal.ONE.negate(), MC);
        BigDecimal fee = price.multiply(q, MC).multiply(p.liquidationFeeRate, MC);
        BigDecimal margin = p.margin;
        p.exits.addObject().put("time", p.currentTime).put("price", dec(price)).put("reason", "LIQUIDATION")
                .put("quantity", dec(q)).put("gross_pnl_usdt", dec(gross)).put("liquidation_fee_usdt", dec(fee));
        p.quantity = ZERO; p.margin = ZERO; p.heldCloseReserve = ZERO; p.leverage = ZERO; p.closed = true; p.mark = price;
        return new LiquidationResult(margin, gross, fee);
    }

    private static boolean maintenanceCovered(Position p, long time) { return maintenanceTier(p, time, p.quantity.multiply(p.mark, MC)) != null; }
    private static boolean breachedMaintenance(Position p, BigDecimal mark, long time) {
        Tier tier = maintenanceTier(p, time, p.quantity.multiply(mark, MC));
        if (tier == null) return false;
        BigDecimal unrealized = unrealizedPnl(p, mark);
        BigDecimal maintenance = p.quantity.multiply(mark, MC).multiply(tier.rate(), MC).subtract(tier.deduction(), MC);
        return p.margin.add(unrealized, MC).compareTo(maintenance) <= 0;
    }

    private static Tier maintenanceTier(Position p, long time, BigDecimal notional) {
        Tier found = null;
        for (Tier tier : p.tiers) if (time >= tier.from() && time < tier.until() && notional.compareTo(tier.cap()) <= 0
                && (found == null || tier.cap().compareTo(found.cap()) < 0)) found = tier;
        return found;
    }

    private static Map<String, Position> readPositions(ObjectNode request, BigDecimal initialEquity, boolean allowUnconfigured) {
        if (!request.path("positions").isArray() || request.path("positions").isEmpty()) throw fail("positions must be a nonempty array");
        Map<String, Position> result = new HashMap<>();
        for (JsonNode node : request.path("positions")) {
            ObjectNode row = (ObjectNode) node; String asset = text(row, "asset").toUpperCase(Locale.ROOT);
            if (!Set.of("BTC", "ETH", "SOL", "AAVE").contains(asset) || result.containsKey(asset)) throw fail("position assets must be distinct frozen symbols");
            String direction = row.hasNonNull("direction") ? text(row, "direction").toUpperCase(Locale.ROOT) : "UNCONFIGURED";
            boolean unconfigured = allowUnconfigured && "UNCONFIGURED".equals(direction);
            if (!Set.of("LONG", "SHORT").contains(direction) && !unconfigured) throw fail("position direction must be LONG or SHORT");
            BigDecimal stop = unconfigured ? null : positive(row, "common_stop");
            JsonNode targetNode = row.get("recovery_target"); BigDecimal target = targetNode == null || targetNode.isNull() ? null : positive(row, "recovery_target");
            if ("LONG".equals(direction) && target != null && stop.compareTo(target) >= 0) throw fail("long stop must be below fixed recovery target");
            if ("SHORT".equals(direction) && target != null && stop.compareTo(target) <= 0) throw fail("short stop must be above fixed recovery target");
            Position p = new Position(asset, direction, stop, target, positive(row, "lot_size"), positive(row, "minimum_notional"),
                    nonnegative(row, "taker_fee_rate"), nonnegative(row, "slippage_rate"),
                    nonnegative(row, "liquidation_fee_rate"), positive(row, "initial_mark_price"), initialEquity,
                    readTiers(row));
            result.put(asset, p);
        }
        return result;
    }

    private static List<Tier> readTiers(ObjectNode row) {
        List<Tier> tiers = new ArrayList<>(); JsonNode array = row.path("maintenance_tiers");
        if (!array.isArray()) return tiers;
        for (JsonNode node : array) {
            tiers.add(new Tier(integer((ObjectNode) node, "effective_from"), integer((ObjectNode) node, "effective_until"),
                    positive((ObjectNode) node, "tier_notional_cap"), nonnegative((ObjectNode) node, "maintenance_margin_rate"),
                    nonnegative((ObjectNode) node, "maintenance_deduction")));
        }
        tiers.sort(Comparator.comparingLong(Tier::from).thenComparingLong(Tier::until).thenComparing(Tier::cap));
        long previousUntil = Long.MIN_VALUE; long groupFrom = Long.MIN_VALUE, groupUntil = Long.MIN_VALUE;
        BigDecimal previousCap = ZERO;
        for (Tier tier : tiers) {
            if (tier.until() <= tier.from()) throw fail("maintenance tier effective interval must be nonempty");
            if (tier.from() != groupFrom || tier.until() != groupUntil) {
                if (groupFrom != Long.MIN_VALUE && tier.from() < previousUntil) throw fail("maintenance tier effective intervals must not overlap");
                groupFrom = tier.from(); groupUntil = tier.until(); previousUntil = tier.until(); previousCap = ZERO;
            }
            if (tier.cap().compareTo(previousCap) <= 0) throw fail("maintenance notional tier caps must be strictly increasing");
            previousCap = tier.cap();
        }
        return List.copyOf(tiers);
    }

    private static List<AccountEvent> readEvents(ObjectNode request, Set<String> assets) {
        return readEvents(request, assets, false);
    }

    private static List<AccountEvent> readEvents(ObjectNode request, Set<String> assets, boolean allowHourlyBars) {
        if (!request.path("events").isArray()) throw fail("events must be an array");
        List<AccountEvent> result = new ArrayList<>(); Set<String> eventIds = new HashSet<>(), eventKeys = new HashSet<>();
        for (JsonNode node : request.path("events")) {
            ObjectNode row = (ObjectNode) node; String asset = text(row, "asset").toUpperCase(Locale.ROOT); long time = integer(row, "time");
            if (!assets.contains(asset)) throw fail("event asset has no position specification");
            String type = text(row, "type").toUpperCase(Locale.ROOT);
            int order = switch (type) {
                case "BAR_POST" -> 0; case "METADATA" -> 1; case "MARK" -> 2; case "FUNDING" -> 3;
                case "STOP_UPDATE" -> 4; case "BAR_PRE" -> 5; case "EXIT" -> 6; case "ADD" -> 7;
                default -> throw fail("unsupported portfolio event " + type);
            };
            if ("MARK".equals(type)) positive(row, "price");
            if ("BAR_PRE".equals(type) || "BAR_POST".equals(type)) validateBar(row, type, time, allowHourlyBars);
            if ("METADATA".equals(type)) validateMetadata(row);
            if ("FUNDING".equals(type)) {
                if (!eventIds.add(text(row, "event_id"))) throw fail("funding event ids must be unique portfolio-wide");
                number(row, "funding_rate"); positive(row, "mark_price");
            }
            if ("EXIT".equals(type)) { positive(row, "price"); text(row, "reason"); }
            if ("STOP_UPDATE".equals(type)) { positive(row, "stop"); integer(row, "source_bar_close_time"); }
            if ("ADD".equals(type)) { positive(row, "price"); nonnegative(row, "previous_completed_minute_base_volume"); }
            String eventKey = type + "\u0000" + asset + "\u0000" + time + "\u0000" + row.path("stage").asInt(0);
            if (!eventKeys.add(eventKey)) throw fail("duplicate event identity for type, asset, time, and stage");
            result.add(new AccountEvent(time, order, asset, type, row.deepCopy()));
        }
        result.sort(LiquidationPortfolioAccountingV1::compareEvents);
        return result;
    }

    private static int compareEvents(AccountEvent left, AccountEvent right) {
        int compared = Long.compare(left.time(), right.time()); if (compared != 0) return compared;
        compared = Integer.compare(left.order(), right.order()); if (compared != 0) return compared;
        compared = Long.compare(left.row().path("decision_time").asLong(left.time()), right.row().path("decision_time").asLong(right.time()));
        if (compared != 0) return compared;
        compared = Integer.compare(assetRank(left.asset()), assetRank(right.asset())); if (compared != 0) return compared;
        compared = Integer.compare(left.row().path("stage").asInt(0), right.row().path("stage").asInt(0));
        if (compared != 0) return compared;
        return left.type().compareTo(right.type());
    }

    private static String eventIdentity(AccountEvent event) {
        return event.type() + "\u0000" + event.asset() + "\u0000" + event.time() + "\u0000" + event.row().path("stage").asInt(0);
    }

    private static void validateMetadata(ObjectNode row) {
        positive(row, "lot_size"); positive(row, "minimum_notional"); nonnegative(row, "taker_fee_rate");
        nonnegative(row, "slippage_rate"); nonnegative(row, "liquidation_fee_rate"); readTiers(row);
        if (row.has("effective_from") && integer(row, "effective_from") > integer(row, "effective_until")) {
            throw fail("metadata validity start cannot be after its end");
        }
    }

    private static void applyMetadata(Position p, ObjectNode row) {
        if (row.has("effective_from") && (p.currentTime < integer(row, "effective_from") || p.currentTime >= integer(row, "effective_until"))) {
            throw fail("metadata event is outside its declared effective interval");
        }
        p.lotSize = positive(row, "lot_size"); p.minimumNotional = positive(row, "minimum_notional");
        p.feeRate = nonnegative(row, "taker_fee_rate"); p.slippageRate = nonnegative(row, "slippage_rate");
        p.liquidationFeeRate = nonnegative(row, "liquidation_fee_rate"); p.costRate = p.feeRate.add(p.slippageRate, MC);
        p.tiers = readTiers(row);
    }

    private static void configureEpisode(Position p, ObjectNode row) {
        String direction = text(row, "direction").toUpperCase(Locale.ROOT);
        if (!Set.of("LONG", "SHORT").contains(direction)) throw fail("new episode direction must be LONG or SHORT");
        BigDecimal stop = positive(row, "common_stop");
        JsonNode targetNode = row.get("recovery_target"); BigDecimal target = targetNode == null || targetNode.isNull() ? null : positive(row, "recovery_target");
        boolean longSide = "LONG".equals(direction);
        if (target != null && (longSide ? stop.compareTo(target) >= 0 : stop.compareTo(target) <= 0)) throw fail("episode stop and recovery target are directionally incoherent");
        p.direction = direction; p.longSide = longSide; p.stop = stop; p.target = target; p.activeSetupId = text(row, "setup_id");
        p.quantity = ZERO; p.entryNotional = ZERO; p.weightedEntryNumerator = ZERO; p.entryCosts = ZERO; p.realizedGross = ZERO;
        p.exitCosts = ZERO; p.fundingPnl = ZERO; p.fundingDebits = ZERO; p.margin = ZERO; p.leverage = ZERO; p.heldCloseReserve = ZERO;
        p.fullPositionReferenceRisk = ZERO;
        p.fills.removeAll(); p.fundingEvents.removeAll(); p.exits.removeAll(); p.fundingMechanicsUnqualified = false;
        p.referenceEquity = null; p.firstFillTime = -1; p.lastFillTime = -1; p.nextStage = 1; p.closed = false;
        p.pendingVenueExitReason = null; p.pendingVenueExitStatus = null;
        p.pendingVenueExitPriority = Integer.MAX_VALUE; p.pendingVenueExitObservedTime = -1;
        p.lastEntryHour = Long.MIN_VALUE;
    }

    private static void validateBar(ObjectNode row, String type, long eventTime, boolean allowHourlyBars) {
        long start = integer(row, "bar_start_time");
        JsonNode durationNode = row.get("bar_duration_ms");
        long duration = durationNode == null ? 60_000L
                : durationNode.isIntegralNumber() && durationNode.canConvertToLong() ? durationNode.asLong() : -1L;
        if (duration != 60_000L && !(allowHourlyBars && duration == 3_600_000L)) {
            throw fail(allowHourlyBars ? "hourly diagnostic bars must declare one-hour duration"
                    : "production account accepts minute bars only");
        }
        if ("BAR_PRE".equals(type) && start != eventTime) throw fail("BAR_PRE time must equal the bar opening boundary");
        if ("BAR_POST".equals(type) && start + duration != eventTime) throw fail("BAR_POST time must equal the completed bar boundary");
        for (String prefix : List.of("trade", "mark")) {
            BigDecimal open = positive(row, prefix + "_open"), high = positive(row, prefix + "_high");
            BigDecimal low = positive(row, prefix + "_low"), close = positive(row, prefix + "_close");
            if (high.compareTo(open.max(close)) < 0 || low.compareTo(open.min(close)) > 0 || high.compareTo(low) < 0) {
                throw fail(prefix + " OHLC is internally inconsistent");
            }
        }
    }

    private static BigDecimal markedEquity(BigDecimal initial, Map<String, Position> positions, BigDecimal realized,
            BigDecimal funding, BigDecimal entryCosts, BigDecimal exitCosts) {
        BigDecimal value = initial.add(realized, MC).add(funding, MC).subtract(entryCosts, MC).subtract(exitCosts, MC);
        for (Position p : positions.values()) if (p.quantity.signum() > 0) value = value.add(unrealizedPnl(p, p.mark), MC);
        return value;
    }

    private static BigDecimal portfolioStopRisk(Map<String, Position> positions) {
        BigDecimal value = ZERO; for (Position p : positions.values()) if (p.quantity.signum() > 0) value = value.add(stopRisk(p)
                .add(p.entryCosts, MC).add(p.fundingDebits, MC).add(closeReserve(p), MC), MC); return value;
    }
    private static BigDecimal stopRisk(Position p) {
        if (p.quantity.signum() == 0) return ZERO;
        return p.entryNotional.subtract(p.stop.multiply(p.quantity, MC), MC)
                .multiply(p.longSide ? BigDecimal.ONE : BigDecimal.ONE.negate(), MC).max(ZERO);
    }
    private static BigDecimal unrealizedPnl(Position p, BigDecimal mark) {
        return mark.multiply(p.quantity, MC).subtract(p.entryNotional, MC)
                .multiply(p.longSide ? BigDecimal.ONE : BigDecimal.ONE.negate(), MC);
    }
        private static BigDecimal closeReserve(Position p) { return p.quantity.signum() == 0 || p.stop == null ? ZERO : p.quantity.multiply(p.stop, MC).multiply(p.costRate, MC); }
    private static BigDecimal weightedEntry(Position p) { return p.quantity.signum() == 0 ? ZERO : p.weightedEntryNumerator.divide(p.quantity, MC); }
    private static BigDecimal floorToLot(BigDecimal quantity, BigDecimal lot) { return quantity.signum() <= 0 ? ZERO : quantity.divide(lot, 0, RoundingMode.DOWN).multiply(lot, MC).stripTrailingZeros(); }
    private static String limiting(BigDecimal risk, BigDecimal volume, BigDecimal collateral) {
        BigDecimal min = risk.min(volume).min(collateral); return min.compareTo(risk) == 0 ? "RISK_BUDGET" : min.compareTo(volume) == 0 ? "PRIOR_MINUTE_VOLUME_1_PERCENT" : "FREE_COLLATERAL";
    }
    private static int assetRank(String asset) { return switch (asset) { case "BTC" -> 0; case "ETH" -> 1; case "SOL" -> 2; case "AAVE" -> 3; default -> 4; }; }
    private static BigDecimal positive(ObjectNode n, String k) { BigDecimal v = number(n, k); if (v.signum() <= 0) throw fail(k + " must be positive"); return v; }
    private static BigDecimal nonnegative(ObjectNode n, String k) { BigDecimal v = number(n, k); if (v.signum() < 0) throw fail(k + " cannot be negative"); return v; }
    private static BigDecimal number(ObjectNode n, String k) { JsonNode v = n.get(k); if (v == null || !v.isNumber() || !Double.isFinite(v.asDouble())) throw fail(k + " must be finite numeric data"); return new BigDecimal(v.asText()).round(MC); }
    private static long integer(ObjectNode n, String k) { JsonNode v = n.get(k); if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) throw fail(k + " must be integer epoch milliseconds"); return v.asLong(); }
    private static String text(ObjectNode n, String k) { JsonNode v = n.get(k); if (v == null || !v.isTextual() || v.asText().isBlank()) throw fail(k + " must be nonempty text"); return v.asText(); }
    private static BigDecimal bd(String n) { return new BigDecimal(n, MC); }
    private static BigDecimal dec(BigDecimal n) { return n.stripTrailingZeros(); }
    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }

    private record AccountEvent(long time, int order, String asset, String type, ObjectNode row) {}
    private record Tier(long from, long until, BigDecimal cap, BigDecimal rate, BigDecimal deduction) {}
    private record ExitResult(BigDecimal freeAfter, BigDecimal grossPnl, BigDecimal exitCost, BigDecimal quantity, String status) {}
    private record LiquidationResult(BigDecimal marginReleased, BigDecimal grossPnl, BigDecimal fee) {}
    private record BarResult(BigDecimal freeAfter, boolean closed, boolean liquidated, boolean maintenanceUnqualified,
            BigDecimal grossPnl, BigDecimal exitCosts, BigDecimal exitPrice, String reason, String status,
            BigDecimal executionGapDebit, BigDecimal executionGapReferenceRisk) {}

    private static final class Position {
        final String asset; String direction; boolean longSide; BigDecimal stop, target, lotSize, minimumNotional, feeRate, slippageRate, liquidationFeeRate, costRate;
        List<Tier> tiers; final BigDecimal referenceEquityAtStart; String activeSetupId = ""; ArrayNode fills = JsonHashes.mapper().createArrayNode();
        ArrayNode fundingEvents = JsonHashes.mapper().createArrayNode(); ArrayNode exits = JsonHashes.mapper().createArrayNode();
        BigDecimal quantity = ZERO, entryNotional = ZERO, weightedEntryNumerator = ZERO, entryCosts = ZERO, realizedGross = ZERO;
        BigDecimal exitCosts = ZERO, fundingPnl = ZERO, fundingDebits = ZERO, margin = ZERO, leverage = ZERO, heldCloseReserve = ZERO, mark;
        BigDecimal fullPositionReferenceRisk = ZERO;
        boolean fundingMechanicsUnqualified;
        String pendingVenueExitReason;
        String pendingVenueExitStatus;
        int pendingVenueExitPriority = Integer.MAX_VALUE;
        long pendingVenueExitObservedTime = -1;
        BigDecimal referenceEquity; long firstFillTime = -1, lastFillTime = -1, lastEntryHour = Long.MIN_VALUE, currentTime; int nextStage = 1; boolean closed;
        Position(String asset, String direction, BigDecimal stop, BigDecimal target, BigDecimal lotSize, BigDecimal minimumNotional,
                BigDecimal feeRate, BigDecimal slippageRate, BigDecimal liquidationFeeRate, BigDecimal mark, BigDecimal referenceEquity, List<Tier> tiers) {
            this.asset = asset; this.direction = direction; this.longSide = "LONG".equals(direction); this.stop = stop; this.target = target;
            this.lotSize = lotSize; this.minimumNotional = minimumNotional; this.feeRate = feeRate; this.slippageRate = slippageRate;
            this.liquidationFeeRate = liquidationFeeRate; this.costRate = feeRate.add(slippageRate, MC); this.mark = mark;
            this.referenceEquityAtStart = referenceEquity; this.tiers = tiers;
        }
        Position restart() {
            Position next = new Position(asset, direction, stop, target, lotSize, minimumNotional, feeRate, slippageRate,
                    liquidationFeeRate, mark, referenceEquityAtStart, tiers);
            next.currentTime = currentTime;
            return next;
        }
        ObjectNode toJson() {
            BigDecimal gross = quantity.signum() > 0 ? unrealizedPnl(this, mark) : ZERO;
            BigDecimal risk = quantity.signum() > 0 ? stopRisk(this).add(entryCosts, MC).add(fundingDebits, MC).add(closeReserve(this), MC) : ZERO;
            ObjectNode value = JsonHashes.mapper().createObjectNode().put("asset", asset).put("direction", direction)
                    .put("status", quantity.signum() > 0 ? "OPEN" : exits.isEmpty() ? "NO_POSITION" : "CLOSED")
                    .put("quantity", dec(quantity)).put("weighted_entry", dec(weightedEntry(this)))
                    .put("last_mark_price", dec(mark)).put("gross_unrealized_pnl_usdt", dec(gross)).put("entry_costs_usdt", dec(entryCosts))
                    .put("realized_gross_pnl_usdt", dec(realizedGross)).put("exit_costs_usdt", dec(exitCosts)).put("funding_pnl_usdt", dec(fundingPnl))
                    .put("funding_debits_for_risk_headroom_usdt", dec(fundingDebits)).put("open_stop_risk_usdt", dec(stopRisk(this)))
                    .put("estimated_close_cost_reserve_usdt", dec(closeReserve(this))).put("held_close_reserve_usdt", dec(heldCloseReserve)).put("risk_committed_usdt", dec(risk))
                    .put("isolated_margin_locked_usdt", dec(margin)).put("current_position_leverage", dec(leverage)).put("next_stage", nextStage)
                    .put("first_fill_time", firstFillTime).put("active_setup_id", activeSetupId)
                    .put("reference_equity_usdt", dec(referenceEquity == null ? referenceEquityAtStart : referenceEquity));
            value.put("full_position_reference_risk_usdt", dec(fullPositionReferenceRisk));
            if (pendingVenueExitReason != null) value.put("pending_venue_exit_reason", pendingVenueExitReason)
                    .put("pending_venue_exit_status", pendingVenueExitStatus)
                    .put("pending_venue_exit_priority", pendingVenueExitPriority)
                    .put("pending_venue_exit_observed_time", pendingVenueExitObservedTime);
            if (stop == null) value.putNull("common_stop"); else value.put("common_stop", dec(stop));
            if (target == null) value.putNull("recovery_target"); else value.put("recovery_target", dec(target));
            value.set("fills", fills.deepCopy()); value.set("funding_events", fundingEvents.deepCopy()); value.set("exits", exits.deepCopy());
            return value;
        }
    }
}
