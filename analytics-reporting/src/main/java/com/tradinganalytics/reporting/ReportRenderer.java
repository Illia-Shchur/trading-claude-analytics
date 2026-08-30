package com.tradinganalytics.reporting;

import static com.tradinganalytics.reporting.ReportingJson.array;
import static com.tradinganalytics.reporting.ReportingJson.entries;
import static com.tradinganalytics.reporting.ReportingJson.get;
import static com.tradinganalytics.reporting.ReportingJson.hasValue;
import static com.tradinganalytics.reporting.ReportingJson.nullishString;
import static com.tradinganalytics.reporting.ReportingJson.object;
import static com.tradinganalytics.reporting.ReportingJson.present;
import static com.tradinganalytics.reporting.ReportingJson.string;
import static com.tradinganalytics.reporting.ReportingJson.stringOr;
import static com.tradinganalytics.reporting.ReportingJson.truthy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Complete native port of the four exports in {@code tools/render-report.mjs}. */
public final class ReportRenderer {
    private static final String EMPTY = "";
    private static final String DASH = "—";
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");
    private static final Pattern CAMEL = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern FIRST = Pattern.compile("^.", Pattern.DOTALL);

    private static final Map<String, String> MARKS = orderedMap(
            "AVAILABLE", "✅", "PASS", "✅", "PASSED", "✅", "AUTHORIZED", "✅",
            "ACTIVE", "🔵", "CHECKED", "✅", "CONSISTENT", "✅", "FRESH", "✅",
            "LIT", "✅", "LIVE", "✅", "OPEN", "🔵", "REGISTERED", "✅",
            "RECONCILED", "✅", "LOCKED", "🔒", "FROZEN", "🔒", "DRY", "○",
            "HOLD", "⏸️", "UNKNOWN", "❔", "DATA_LIMITED", "⚠️", "STALE", "⚠️",
            "EXPIRED", "⚠️", "UNTAGGED", "⚠️", "NOT_APPLICABLE", "—",
            "NOT_COVERED", "—", "FAIL", "❌", "FAILED", "❌", "VETO", "⛔");

    private static final Map<String, String> FIELD_LABELS = orderedMap(
            "all_time_high", "All-time high", "adr5", "ADR-5", "btc_rv30", "BTC RV30",
            "catastrophic_realized_pnl_after_0_1pct_fee_usd", "Catastrophic realized P&L after 0.1% fee (USD)",
            "compound_realized_pnl_after_0_1pct_fee_usd", "Compound realized P&L after 0.1% fee (USD)",
            "cot", "COT managed-money net long", "current_deepest_buy_floor", "Current deepest buy floor",
            "current_weight_pct", "Current portfolio weight", "data_as_of", "Data as of",
            "drawdown_pct", "Drawdown from ATH", "dry_powder_yield", "Dry-powder yield",
            "dry_powder_yield_pct", "Dry-powder yield", "exit_status", "Exit status",
            "futures", "Open futures", "gold_rv30", "Gold RV30", "gld_holdings", "GLD holdings",
            "locked_notional_usd", "Locked notional (USD)", "ma200d", "200-day MA",
            "market_to_catastrophic_loss_pct_portfolio", "Market to catastrophic loss (% of portfolio)",
            "market_to_catastrophic_loss_usd", "Market to catastrophic loss (USD)",
            "market_to_compound_loss_pct_portfolio", "Market to compound loss (% of portfolio)",
            "market_to_compound_loss_usd", "Market to compound loss (USD)",
            "open_deals", "Open deals", "paxg_spot", "PAXG spot",
            "phase_eligibility_effect", "Phase eligibility effect", "pnl", "P&L",
            "position_reconciliation", "Position reconciliation",
            "potential_weight_if_buys_fill_pct", "Potential portfolio weight if buys fill",
            "remaining_after_both_paxg", "Remaining after both PAXG trims",
            "real_yield10y", "10-year real yield", "sma200w", "200-week SMA",
            "underlying_spot", "Underlying spot", "volume_flush", "Volume flush",
            "weekly_rsi", "Weekly RSI-14", "weekly_rsi14", "Weekly RSI-14");

    private ReportRenderer() {}

    public static String renderSwingFull(JsonNode report) {
        ObjectNode identity = object(report, "identity");
        ObjectNode setup = object(report, "setup");
        JsonNode score = get(setup, "score");
        JsonNode mechanical = get(setup, "mechanical_score");
        List<JsonNode> activeVetoes = new ArrayList<>();
        for (JsonNode veto : array(report, "vetoes")) if (get(veto, "active").isBoolean() && get(veto, "active").booleanValue()) activeVetoes.add(veto);
        ObjectNode features = object(report, "features");
        JsonNode flowCandidate = get(features, "flow");
        ObjectNode flow = truthy(flowCandidate) ? asObject(flowCandidate) : object(features, "market_flow");
        ObjectNode technical = object(features, "technical");
        ObjectNode macro = object(features, "macro");
        JsonNode sentimentCandidate = get(features, "sentiment");
        ObjectNode sentiment = truthy(sentimentCandidate) ? asObject(sentimentCandidate) : object(features, "institutional");
        ObjectNode valuation = object(features, "valuation");
        ObjectNode structure = object(features, "structure");

        String firstSource = entries(object(report, "sources")).isEmpty() ? null : entries(object(report, "sources")).get(0).getKey();
        Function<JsonNode, String> flowSource = value -> firstTruthy(
                get(value, "source"), get(value, "source_id"), get(flow, "source"), textNode(firstSource), textNode("source unavailable"));
        Function<JsonNode, String> flowAsOf = value -> firstTruthy(
                get(value, "as_of"), get(value, "completed_through"), get(flow, "completed_through"),
                get(report, "audit", "completed_through"), get(report, "timestamps", "data_as_of"), textNode("as-of unavailable"));
        List<List<?>> flowRows = new ArrayList<>();
        for (String key : List.of("spot_cvd", "futures_bid_ask_delta", "futures_cvd", "open_interest", "oi_weighted_funding")) {
            JsonNode value = get(flow, key);
            flowRows.add(List.of(fieldName(key), friendlyValue(value, key) + " · source " + flowSource.apply(value)
                    + " · as-of " + flowAsOf.apply(value), firstTruthy(get(value, "read"), get(value, "interpretation"), textNode(DASH))));
        }
        List<List<?>> featureRows = List.of(
                List.of("Technical", friendlyValue(technical), ""),
                List.of("Macro", friendlyValue(macro), ""),
                List.of("Sentiment / institutional", friendlyValue(sentiment), ""),
                List.of("Valuation / cycle", friendlyValue(valuation), ""),
                List.of("Structure / demand", friendlyValue(structure), ""));
        List<List<?>> vetoRows = new ArrayList<>();
        for (JsonNode veto : array(report, "vetoes")) vetoRows.add(List.of(
                orDash(get(veto, "code")), status(bool(get(veto, "active")) ? "VETO" : "CLEAR"), stringOr(get(veto, "reason"), DASH)));

        ObjectNode rb = object(report, "risk_budget");
        ObjectNode trigger = object(report, "trigger");
        ObjectNode plan = object(report, "trade_plan");
        ObjectNode entry = firstTruthyObject(get(report, "entry"), get(plan, "entry"));
        ObjectNode stop = firstTruthyObject(get(report, "stop"), get(plan, "stop"));
        ObjectNode exit = firstTruthyObject(get(report, "exit"), get(plan, "exit"), get(report, "position_controls", "exit"));
        String auditHash = ReportContract.reportHash(report).substring(0, 16);
        String lintStatus = "PASS".equals(stringOr(get(report, "audit", "lint"), "")) ? "PASS" : "FAIL";
        String filename = stringOr(get(identity, "filename"), stringOr(get(report, "report_id"), "undefined") + ".json");
        String footer = "Audit: LIVE · as-of " + stringOr(get(report, "timestamps", "data_as_of"), DASH)
                + " · coverage " + stringOr(get(report, "audit", "coverage"), "PARTIAL")
                + " · canonical " + filename + " sha256:" + auditHash + " · lint " + lintStatus;
        ObjectNode position = object(report, "position");
        List<String> lines = new ArrayList<>();
        lines.add(("# " + stringOr(get(identity, "asset"), "Unknown asset") + " — "
                + frameworkLabel(get(identity, "framework")) + " — " + stringOr(get(identity, "date"), "date unavailable")
                + " " + stringOr(get(identity, "local_time"), "")).trim());
        add(lines, "", "## 1. Decision snapshot", "");
        lines.addAll(table(List.of("Decision field", "Reading"), List.of(
                List.of("Setup", stringOr(get(setup, "variant"), "fallen_knives".equals(stringOr(get(identity, "framework"), "")) ? "accumulation" : "distribution") + " · " + stringOr(get(setup, "status"), "WATCH")),
                List.of("Model state", stringOr(get(report, "model_activation", "status"), "SHADOW")),
                List.of("Score", "**" + nullishString(score, DASH) + "/20** (mechanical " + nullishString(mechanical, DASH) + ", impulse " + nullishString(get(setup, "impulse"), DASH) + ")"),
                List.of("Phase", stringOr(get(setup, "phase"), "WATCH")),
                List.of("Trigger", status(get(trigger, "status"))),
                List.of("Vetoes", activeVetoes.isEmpty() ? "none" : activeVetoes.size() + " active"),
                List.of("Action", firstTruthy(get(report, "verdict", "statement"), get(report, "narrative", "summary"), textNode(DASH))))));
        add(lines, "", "## 2. Market-flow and regime dashboard", "",
                "Completed-bar flow panel · " + stringOr(get(flow, "interval_hours"), "4") + "h · through "
                        + firstTruthy(get(flow, "completed_through"), get(report, "audit", "completed_through"), get(report, "timestamps", "data_as_of"), textNode(DASH))
                        + " · " + stringOr(get(flow, "scope"), "provider scope not stated"), "");
        lines.addAll(table(List.of("Flow row", "24h / 3d / window", "Read"), flowRows));
        lines.add("");
        lines.addAll(table(List.of("Dimension", "Reading", "Implication"), featureRows));
        add(lines, "", "## 3. Swing score, trigger, and veto state", "");
        List<List<?>> components = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : entries(object(setup, "legs"))) components.add(List.of(fieldName(field.getKey()), string(field.getValue())));
        components.add(List.of("Mechanical score", nullishString(mechanical, DASH)));
        components.add(List.of("Adjusted score", nullishString(score, DASH)));
        components.add(List.of("Impulse", nullishString(get(setup, "impulse"), DASH)));
        components.add(List.of("Phase threshold", nullishString(get(setup, "phase_threshold"), DASH)));
        components.add(List.of("Trigger window", stringOr(get(trigger, "window_bars"), "2") + " completed 4h bars"));
        lines.addAll(table(List.of("Component", "Value"), components));
        lines.add("");
        lines.addAll(table(List.of("Veto", "State", "Reason"), vetoRows.isEmpty()
                ? List.of(List.of(DASH, status("CLEAR"), "No active vetoes")) : vetoRows));
        add(lines, "", "## 4. Entry, stop, targets, and expected R", "");
        lines.addAll(table(List.of("Control", "Value"), List.of(
                List.of("Entry trigger", friendlyValue(firstTruthyNode(get(entry, "trigger"), get(entry, "price"), get(entry, "zone"), get(trigger, "level"), textNode(DASH)))),
                List.of("Retest window", friendlyValue(firstTruthyNode(get(entry, "retest_window"), get(trigger, "expires_at"), textNode(DASH)))),
                List.of("Stop", friendlyValue(stop)),
                List.of("Targets", friendlyValue(firstTruthyNode(get(plan, "targets"), get(entry, "targets"), get(report, "targets"), textNode(DASH)))),
                List.of("Expected R", friendlyValue(get(report, "expectancy_r"))),
                List.of("Risk budget", stringOr(get(rb, "status"), DASH) + " · " + nullishString(get(rb, "notional_usd"), DASH) + " USD"),
                List.of("Sizing formula", "min(phase cap, 1.5% portfolio risk ÷ stop distance, 3% asset risk ÷ stop distance)"))));
        add(lines, "", "## 5. Position and exit status", "");
        lines.addAll(table(List.of("Position field", "Value"), List.of(
                List.of("Status", status(get(position, "status"))),
                List.of("Quantity", nullishString(get(position, "quantity"), DASH)),
                List.of("Average cost", nullishString(firstPresent(get(position, "basis", "avg_cost"), get(position, "basis", "avg_cost_usd")), DASH)),
                List.of("Exit state", friendlyValue(exit)),
                List.of("Ratchet", friendlyValue(firstTruthyNode(get(plan, "ratchet"), textNode(DASH)))),
                List.of("Carry", friendlyValue(firstTruthyNode(get(plan, "carry"), textNode(DASH)))),
                List.of("Time stop / clock", friendlyValue(firstTruthyNode(get(plan, "time_stop"), get(plan, "clock_days"), get(report, "time_stop"), get(report, "clock"), textNode(DASH)))))));
        add(lines, "", "## 6. Watchlist and changes", "");
        List<List<?>> watch = new ArrayList<>();
        for (JsonNode item : array(report, "watchlist")) watch.add(List.of(
                firstTruthy(get(item, "item"), get(item, "name")), status(firstTruthyNode(get(item, "status"), textNode("AVAILABLE"))),
                firstTruthy(get(item, "trigger"), get(item, "condition"), textNode(DASH))));
        lines.addAll(table(List.of("Item", "Status", "Trigger"), watch));
        lines.add("");
        if (!array(report, "change_log").isEmpty()) {
            List<List<?>> changes = new ArrayList<>();
            for (JsonNode change : array(report, "change_log")) changes.add(List.of(
                    firstTruthy(get(change, "field"), get(change, "name")), get(change, "previous"), get(change, "current")));
            lines.addAll(table(List.of("Change", "Previous", "Current"), changes));
            lines.add("");
        }
        lines.add("> " + text(firstTruthyNode(get(report, "narrative", "summary"), get(report, "verdict", "statement"), textNode("Swing setup under review."))));
        add(lines, "", footer, "");
        return String.join("\n", lines);
    }

    public static String renderSwingSummary(JsonNode report) {
        ObjectNode setup = object(report, "setup");
        return String.join("\n", List.of(
                "# " + stringOr(get(report, "identity", "asset"), "Unknown asset") + " "
                        + frameworkLabel(get(report, "identity", "framework")) + " — " + stringOr(get(report, "identity", "date"), "date unavailable"),
                "",
                "**" + stringOr(get(setup, "status"), "WATCH") + ":** "
                        + firstTruthy(get(report, "verdict", "statement"), get(report, "narrative", "summary"), textNode("Swing setup under review.")),
                "",
                "- Score: **" + nullishString(get(setup, "score"), DASH) + "/20** (mechanical " + nullishString(get(setup, "mechanical_score"), DASH) + ")",
                "- Trigger: **" + stringOr(get(report, "trigger", "status"), "WAIT") + "** · vetoes " + countActive(array(report, "vetoes")),
                "- Coverage: **" + stringOr(get(report, "audit", "coverage"), "PARTIAL") + "**",
                ""));
    }

    public static String renderSummary(JsonNode report) {
        ObjectNode identity = object(report, "identity");
        String actionLine = actionFor(get(report, "verdict", "primary_action"));
        return String.join("\n", List.of(
                ("# " + stringOr(get(identity, "asset"), "Unknown asset") + " " + frameworkLabel(get(identity, "framework"))
                        + " — " + stringOr(get(identity, "date"), "date unavailable") + " " + stringOr(get(identity, "local_time"), "")).trim(),
                "",
                "**" + stringOr(get(report, "verdict", "status"), "UNKNOWN") + ":** " + text(get(report, "verdict", "statement")),
                "",
                "- Score: **" + nullishString(get(report, "score", "adjusted"), DASH) + "/20** (mechanical " + nullishString(get(report, "score", "mechanical"), DASH) + ")",
                "- Gates: **" + array(report, "gates", "passed").size() + "/" + nullishString(get(report, "gates", "active"), DASH) + "** passed",
                "- Position: **" + stringOr(get(report, "position", "status"), DASH) + "**; controls **" + stringOr(get(report, "position_controls", "status"), DASH) + "**",
                "- Primary action: " + actionLine,
                "",
                text(get(report, "narrative", "summary")))) + "\n";
    }

    public static String renderFull(JsonNode report) {
        ObjectNode identity = object(report, "identity");
        ObjectNode verdict = object(report, "verdict");
        ObjectNode score = object(report, "score");
        ObjectNode gates = object(report, "gates");
        ObjectNode position = object(report, "position");
        List<List<?>> decisionRows = List.of(
                List.of("Asset / framework", stringOr(get(identity, "asset"), DASH) + " · " + frameworkLabel(get(identity, "framework"))),
                List.of("Report time", (stringOr(get(identity, "date"), DASH) + " " + stringOr(get(identity, "local_time"), "") + " (" + stringOr(get(identity, "timezone"), "timezone unavailable") + ")").trim()),
                List.of("Verdict", status(get(verdict, "status")) + " — " + stringOr(get(verdict, "statement"), "No statement supplied.")),
                List.of("Adjusted score", "**" + nullishString(get(score, "adjusted"), DASH) + "/20** (mechanical " + nullishString(get(score, "mechanical"), DASH) + ", raw " + nullishString(get(score, "raw"), DASH) + ")"),
                List.of("Confirmation gates", array(gates, "passed").size() + "/" + nullishString(get(gates, "active"), DASH) + " active passed"),
                List.of("Position", (status(get(position, "status")) + " · " + nullishString(get(position, "quantity"), "quantity unavailable") + " " + firstTruthy(get(position, "asset"), get(identity, "asset"), textNode(""))).trim()),
                List.of("Deployment", nullishString(get(report, "deployment", "deployed_pct"), DASH) + "% deployed · " + nullishString(get(report, "deployment", "dry_pct"), DASH) + "% dry"),
                List.of("Primary action", actionFor(get(verdict, "primary_action"))));
        List<String> lines = new ArrayList<>();
        add(lines, ("# " + stringOr(get(identity, "asset"), "Unknown asset") + " — " + frameworkLabel(get(identity, "framework"))
                + " — " + stringOr(get(identity, "date"), "date unavailable") + " " + stringOr(get(identity, "local_time"), "")).trim(),
                "", "## 1. Decision snapshot", "");
        lines.addAll(table(List.of("Decision field", "Reading"), decisionRows));
        lines.add("");
        lines.addAll(renderMarket(report));
        lines.addAll(renderScoreAndGates(report));
        lines.addAll(renderEv(report));
        lines.addAll(renderDeployment(report));
        lines.addAll(renderPosition(report));
        lines.addAll(renderPositionControls(report));
        lines.addAll(renderRiskControls(report));
        lines.addAll(renderNarrative(report));
        lines.addAll(renderCompanion(report));
        lines.addAll(renderWatchlist(report));
        lines.addAll(renderSubstitutionsAndSources(report));
        lines.addAll(renderTagging(report));
        add(lines, "## 12. Canonical machine payload", "",
                "The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.",
                "", "```json machine", ReportContract.canonicalReportPayload(report), "```", "");
        return String.join("\n", lines);
    }

    private static List<String> renderMarket(JsonNode report) {
        ObjectNode market = object(report, "market");
        List<List<?>> measurements = new ArrayList<>();
        measurements.add(measurementRow("Canonical spot", get(market, "spot"), get(report, "sources")));
        measurements.add(measurementRow("All-time high", get(market, "ath"), get(report, "sources")));
        measurements.add(measurementRow("Drawdown from ATH", get(market, "drawdown_pct"), get(report, "sources")));
        for (Map.Entry<String, JsonNode> metric : entries(object(market, "metrics"))) measurements.add(measurementRow(metric.getKey(), metric.getValue(), get(report, "sources")));
        List<String> lines = lines("## 2. Market, evidence, and data quality", "");
        lines.addAll(table(List.of("Measure", "Value", "Status", "Confidence", "As of", "Reading / source"), measurements));
        add(lines, "", "**Regime:** " + status(get(market, "regime", "label")) + (truthy(get(market, "regime")) ? " — " + friendlyValue(get(market, "regime")) : ""),
                "", "### Spot reconciliation", "",
                "**" + status(get(market, "reconciliation", "status")) + "** — "
                        + text(firstTruthyNode(get(market, "reconciliation", "method"), textNode("No reconciliation method supplied.")))
                        + (hasValue(get(market, "reconciliation", "spread_pct")) ? "; spread " + formatNumber(get(market, "reconciliation", "spread_pct")) + "%" : ""), "");
        List<List<?>> quotes = new ArrayList<>();
        for (JsonNode quote : array(market, "reconciliation", "quotes")) {
            String instrument = stringOr(get(quote, "instrument"), "");
            JsonNode quoteUnit = Pattern.compile("^PAXG", Pattern.CASE_INSENSITIVE).matcher(instrument).find()
                    ? textNode("USD/PAXG") : get(market, "spot", "unit");
            quotes.add(List.of(stringOr(get(quote, "instrument"), DASH), measurementValue(get(quote, "value"), quoteUnit),
                    status(get(quote, "state")), sourceLinks(get(quote, "source_ids"), get(report, "sources"))));
        }
        lines.addAll(table(List.of("Instrument", "Value", "State", "Sources"), quotes));
        lines.add(truthy(get(market, "reconciliation", "note")) ? "\n> " + text(get(market, "reconciliation", "note")) : "");
        add(lines, "", "### Evidence inputs", "");
        List<List<?>> evidence = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : entries(object(report, "evidence"))) evidence.add(measurementRow(field.getKey(), field.getValue(), get(report, "sources")));
        lines.addAll(table(List.of("Input", "Value", "Status", "Confidence", "As of", "Rationale / source"), evidence));
        add(lines, "", "**Data gaps:** " + array(report, "data_gaps").size() + " · **stale inputs:** " + array(report, "stale_inputs").size()
                + " · **out of scope:** " + array(report, "out_of_scope").size(), "");
        if (!array(report, "data_gaps").isEmpty()) {
            add(lines, "**Data gaps**", "");
            for (JsonNode gap : array(report, "data_gaps")) lines.add("- **" + string(get(gap, "field")) + "** — " + status(get(gap, "status")) + " — " + stringOr(get(gap, "impact"), "Impact not stated."));
            lines.add("");
        }
        if (!array(report, "stale_inputs").isEmpty()) {
            add(lines, "**Stale inputs**", "");
            for (JsonNode item : array(report, "stale_inputs")) lines.add("- " + text(item));
            lines.add("");
        }
        if (!array(report, "out_of_scope").isEmpty()) {
            add(lines, "**Out of scope**", "");
            for (JsonNode item : array(report, "out_of_scope")) lines.add("- " + text(item));
            lines.add("");
        }
        return lines;
    }

    private static List<String> renderScoreAndGates(JsonNode report) {
        ObjectNode score = object(report, "score");
        ObjectNode gates = object(report, "gates");
        String framework = stringOr(get(report, "identity", "framework"), "");
        List<List<?>> legRows = new ArrayList<>();
        for (Map.Entry<String, JsonNode> leg : entries(object(score, "legs"))) legRows.add(List.of(fieldName(leg.getKey()), leg.getValue(), maxForLeg(framework, leg.getKey()), "Mechanical component"));
        ArrayNode passed = array(gates, "passed"), na = array(gates, "na");
        List<List<?>> gateRows = new ArrayList<>();
        for (int number = 1; number <= 9; number++) {
            String state = ReportingJson.includesInt(na, number) ? "N/A" : ReportingJson.includesInt(passed, number) ? "PASSED" : "NOT PASSED";
            gateRows.add(List.of(number, status(state), stringOr(get(gates, "measurement_basis", String.valueOf(number)), "Measurement basis not supplied.")));
        }
        List<String> lines = lines("## 3. Score and confirmation gates", "");
        lines.addAll(table(List.of("Component", "Score", "Maximum", "Interpretation"), legRows));
        lines.add("");
        lines.addAll(table(List.of("Total", "Value", "Meaning"), List.of(
                List.of("Mechanical score", nullishString(get(score, "mechanical"), DASH), "Legs plus penalties"),
                List.of("Raw score", nullishString(get(score, "raw"), DASH), "Mechanical plus discretion (" + nullishString(get(score, "discretion"), DASH) + ")"),
                List.of("Adjusted score", "**" + nullishString(get(score, "adjusted"), DASH) + "/20**", "Decision score"),
                List.of("Rounding", stringOr(get(score, "rounding"), DASH), "Pinned convention"))));
        add(lines, "", array(score, "penalties").isEmpty() ? "**Penalties:** none" : "**Penalties:** " + joinRaw(array(score, "penalties"), ", "), "");
        if (!array(score, "caps").isEmpty()) {
            List<List<?>> caps = new ArrayList<>();
            for (JsonNode cap : array(score, "caps")) {
                JsonNode capValue = firstPresent(get(cap, "cap"), get(cap, "current_cap"));
                if (!present(capValue)) {
                    JsonNode rawValue = get(cap, "value");
                    capValue = present(rawValue) && !rawValue.isContainerNode() ? rawValue : textNode(DASH);
                }
                JsonNode reason = firstTruthyNode(get(cap, "reason"), get(cap, "derivation"));
                String capReason = truthy(reason) ? string(reason)
                        : truthy(get(cap, "value")) && get(cap, "value").isContainerNode() ? friendlyValue(get(cap, "value")) : DASH;
                caps.add(List.of(fieldName(string(get(cap, "field"))), capValue, capReason));
            }
            lines.add("### Caps, ceilings, and line-state constraints\n\n" + String.join("\n", table(List.of("Field", "Cap / value", "Reason"), caps)));
        } else lines.add("");
        add(lines, "", "### Confirmation gates — " + passed.size() + "/" + nullishString(get(gates, "active"), DASH) + " active passed", "");
        lines.addAll(table(List.of("#", "State", "Measurement / relight path"), gateRows));
        add(lines, "", "### Unlock thresholds", "");
        List<List<?>> thresholds = new ArrayList<>();
        for (Map.Entry<String, JsonNode> threshold : entries(object(gates, "thresholds"))) thresholds.add(List.of(threshold.getKey().toUpperCase(Locale.ROOT), threshold.getValue()));
        lines.addAll(table(List.of("Phase", "Score / gate threshold"), thresholds));
        add(lines, "", truthy(get(gates, "alt_reading")) ? "**Alternate reading:** correlation " + nullishString(get(gates, "alt_reading", "corr"), DASH)
                + "; surcharge " + (truthy(get(gates, "alt_reading", "corr_surcharge")) ? "active" : "off") + "; [V] gates "
                + nullishString(get(gates, "alt_reading", "v_count"), DASH) + "." : "");
        if (truthy(get(gates, "alt_reading", "binding_axis"))) {
            List<String> bindings = new ArrayList<>();
            for (Map.Entry<String, JsonNode> binding : entries(get(gates, "alt_reading", "binding_axis"))) bindings.add(binding.getKey() + ": " + oneLine(binding.getValue()));
            lines.add("\n**Binding axis:** " + String.join(" · ", bindings));
        } else lines.add("");
        lines.add("");
        return lines;
    }

    private static List<String> renderEv(JsonNode report) {
        ObjectNode ev = object(report, "ev");
        List<List<?>> scenarios = new ArrayList<>();
        for (JsonNode scenario : array(ev, "scenarios")) scenarios.add(List.of(
                stringOr(get(scenario, "name"), DASH), probabilityPercent(get(scenario, "probability")),
                measurementValue(get(scenario, "low"), get(report, "market", "spot", "unit")),
                measurementValue(get(scenario, "high"), get(report, "market", "spot", "unit")),
                measurementValue(get(scenario, "mid"), get(report, "market", "spot", "unit")), stringOr(get(scenario, "rationale"), DASH)));
        List<String> lines = lines("## 4. Probability matrix and expected value", "");
        lines.addAll(table(List.of("Scenario", "Probability", "Low", "High", "Midpoint", "Rationale"), scenarios));
        lines.add("");
        lines.addAll(table(List.of("EV field", "Value"), List.of(
                List.of("Arithmetic status", status(get(ev, "arithmetic_status"))),
                List.of("Probability sum", nullishString(get(ev, "probability_sum"), DASH)),
                List.of("Stated EV", measurementValue(get(ev, "stated_ev"), get(report, "market", "spot", "unit"))),
                List.of("EV versus spot", hasValue(get(ev, "vs_spot_pct")) ? formatNumber(get(ev, "vs_spot_pct")) + "%" : DASH))));
        lines.add(truthy(get(ev, "note")) ? "\n> " + text(get(ev, "note")) : "");
        lines.add("");
        return lines;
    }

    private static List<String> renderDeployment(JsonNode report) {
        ObjectNode deployment = object(report, "deployment");
        List<List<?>> tranches = new ArrayList<>();
        for (JsonNode tranche : array(deployment, "tranches")) tranches.add(List.of(
                stringOr(get(tranche, "phase"), DASH), hasValue(get(tranche, "pct")) ? formatNumber(get(tranche, "pct")) + "%" : DASH,
                status(get(tranche, "state")), bool(get(tranche, "deployed")) ? "yes" : "no",
                nullishString(get(tranche, "entry_price"), DASH), nullishString(get(tranche, "stop"), DASH),
                nullishString(get(tranche, "prior_stop"), DASH), nullishString(get(tranche, "time_stop"), DASH),
                nullishString(get(tranche, "prior_time_stop"), DASH), nullishString(get(tranche, "channel"), DASH),
                nullishString(get(tranche, "channel_regime"), DASH), nullishString(get(tranche, "tag"), DASH),
                stringOr(get(tranche, "rationale"), DASH)));
        List<String> lines = lines("## 5. Deployment strategy", "",
                "**Deployed:** " + formatNumber(firstPresent(get(deployment, "deployed_pct"), textNode(DASH))) + "% · **dry powder:** "
                        + formatNumber(firstPresent(get(deployment, "dry_pct"), textNode(DASH))) + "% · **throttle released:** "
                        + (truthy(get(deployment, "throttle_released")) ? "yes" : "no"), "");
        lines.addAll(table(List.of("Phase", "Size", "State", "Deployed", "Entry", "Stop", "Prior stop", "Time stop", "Prior time stop", "Channel", "Channel regime", "Canonical tag", "Decision rationale"), tranches));
        lines.add("");
        return lines;
    }

    private static List<String> renderPosition(JsonNode report) {
        ObjectNode position = object(report, "position"), basis = object(position, "basis"), custody = object(position, "custody"), pnl = object(position, "pnl"), attribution = object(position, "attribution");
        List<String> lines = lines("## 6. Position, custody, and execution controls", "");
        String activeTags = !array(attribution, "active_tags").isEmpty() ? joinRaw(array(attribution, "active_tags"), ", ")
                : !array(attribution, "tags").isEmpty() ? joinRaw(array(attribution, "tags"), ", ") : "None";
        lines.addAll(table(List.of("Position field", "Value"), List.of(
                List.of("Status", status(get(position, "status"))), List.of("Asset", firstTruthy(get(position, "asset"), get(report, "identity", "asset"), textNode(DASH))),
                List.of("Quantity", nullishString(get(position, "quantity"), DASH)),
                List.of("Dry powder", hasValue(get(position, "dry_powder")) ? moneyValue(get(position, "dry_powder")) : DASH),
                List.of("Basis reliable", !present(get(basis, "reliable")) ? DASH : bool(get(basis, "reliable")) ? "yes" : "no"),
                List.of("Average cost", hasValue(get(basis, "avg_cost_usd")) ? moneyValue(get(basis, "avg_cost_usd")) : DASH),
                List.of("Total cost basis", hasValue(get(basis, "total_cost_usd")) ? moneyValue(get(basis, "total_cost_usd")) : DASH),
                List.of("Custody", status(get(custody, "status"))), List.of("Attribution", status(get(attribution, "status"))),
                List.of("Active tags", activeTags))));
        lines.add("");
        if (!custody.isEmpty()) lines.addAll(objectTable("Custody reconciliation", custody));
        if (!basis.isEmpty()) lines.addAll(objectTable("Cost basis", basis));
        if (!attribution.isEmpty()) lines.addAll(objectTable("Phase attribution", attribution));
        if (!pnl.isEmpty()) lines.addAll(objectTable("Position P&L", pnl));
        lines.add(truthy(get(position, "reconciliation")) ? "> **Position reconciliation:** " + text(get(position, "reconciliation")) : "");
        lines.add("");
        if (!array(position, "futures").isEmpty()) {
            add(lines, "### Open futures", "");
            List<List<?>> futures = new ArrayList<>();
            for (JsonNode future : array(position, "futures")) futures.add(List.of(
                    stringOr(get(future, "symbol"), DASH), firstTruthy(get(future, "side"), get(future, "position_side"), textNode(DASH)),
                    stringOr(get(future, "quantity"), DASH), stringOr(get(future, "entry_price"), DASH), stringOr(get(future, "mark_price"), DASH), stringOr(get(future, "unrealized_pnl"), DASH)));
            lines.addAll(table(List.of("Symbol", "Side", "Quantity", "Entry", "Mark", "Unrealized P&L"), futures));
            lines.add("");
        } else add(lines, "### Open futures", "", "- None recorded.", "");
        return lines;
    }

    private static List<String> renderPositionControls(JsonNode report) {
        JsonNode controlsNode = get(report, "position_controls");
        if (!truthy(controlsNode)) return lines("### Position controls", "", "- Not supplied.", "");
        ObjectNode controls = asObject(controlsNode);
        List<String> lines = lines("### Position controls", "");
        lines.addAll(table(
                List.of("Control status", "Required", "Primary action"),
                List.of(List.of(status(get(controls, "status")),
                        truthy(get(controls, "required")) ? "yes" : "no",
                        actionFor(get(controls, "action"))))));
        lines.add("");
        for (String[] item : List.of(
                new String[]{"selection", "Selected control plan"}, new String[]{"veto", "Veto state"},
                new String[]{"ratchet", "Ratchet ledger"}, new String[]{"risk", "Risk and concentration"},
                new String[]{"execution_audit", "Execution audit"}, new String[]{"liquidation_zone", "Liquidation zone"},
                new String[]{"pnl", "Control-level P&L"})) if (truthy(get(controls, item[0]))) lines.addAll(objectTable(item[1], get(controls, item[0])));
        if (truthy(get(controls, "candidate"))) {
            ObjectNode candidate = object(controls, "candidate");
            lines.addAll(objectTable("Candidate board summary", selectedObject(candidate, "data_as_of", "phrase", "primary_action")));
            if (!array(candidate, "board").isEmpty()) {
                add(lines, "#### Candidate board", "");
                List<List<?>> board = new ArrayList<>();
                for (JsonNode item : array(candidate, "board")) board.add(List.of(
                        get(item, "candidate"), nullishString(get(item, "score"), DASH), truthy(get(item, "veto")) ? "yes" : "no",
                        friendlyValue(get(item, "dimensions")), stringOr(get(item, "reason"), DASH)));
                lines.addAll(table(List.of("Candidate", "Score", "Veto", "Dimensions", "Reason"), board));
                lines.add("");
            }
        }
        if (truthy(get(controls, "venue_order"))) {
            ObjectNode venue = object(controls, "venue_order");
            lines.addAll(objectTable("Venue order state", selectedObject(venue, "locked_notional_usd", "orders_changed", "current_protective_sell", "recommended_sequence")));
            if (!array(venue, "current_buy_orders").isEmpty()) {
                add(lines, "#### Current buy orders", "");
                List<List<?>> orders = new ArrayList<>();
                for (JsonNode order : array(venue, "current_buy_orders")) orders.add(List.of(
                        get(order, "side"), get(order, "type"), hasValue(get(order, "price")) ? moneyValue(get(order, "price")) : DASH,
                        get(order, "quantity"), hasValue(get(order, "notional_usd")) ? moneyValue(get(order, "notional_usd")) : DASH));
                lines.addAll(table(List.of("Side", "Type", "Price", "Quantity", "Notional"), orders));
                lines.add("");
            }
        }
        if (truthy(get(controls, "ladder"))) {
            ObjectNode ladder = object(controls, "ladder");
            lines.addAll(objectTable("Trim / exit ladder", selectedObject(ladder, "status", "quantity_check", "remaining_after_both_paxg")));
            if (!array(ladder, "alerts_only").isEmpty()) {
                add(lines, "#### Alert-only levels", "");
                List<List<?>> alerts = new ArrayList<>();
                for (JsonNode alert : array(ladder, "alerts_only")) alerts.add(List.of(get(alert, "price"), get(alert, "reason")));
                lines.addAll(table(List.of("Price", "Reason"), alerts)); lines.add("");
            }
            if (!array(ladder, "targets").isEmpty()) {
                add(lines, "#### Conditional targets", "");
                List<List<?>> targets = new ArrayList<>();
                for (JsonNode target : array(ladder, "targets")) targets.add(List.of(
                        get(target, "condition"), get(target, "execution"), firstTruthy(get(target, "quantity_paxg"), get(target, "target_quantity"), textNode(DASH)),
                        hasValue(get(target, "target_price_usd")) ? moneyValue(get(target, "target_price_usd")) : DASH,
                        hasValue(get(target, "expected_realized_pnl_after_0_1pct_fee_usd")) ? moneyValue(get(target, "expected_realized_pnl_after_0_1pct_fee_usd")) : DASH,
                        truthy(get(target, "position_share_pct")) ? string(get(target, "position_share_pct")) + "%" : DASH, stringOr(get(target, "price_note"), DASH)));
                lines.addAll(table(List.of("Condition", "Execution", "Quantity", "Target price", "Expected P&L", "Share", "Price note"), targets)); lines.add("");
            }
        }
        return lines;
    }

    private static List<String> renderRiskControls(JsonNode report) {
        JsonNode controls = get(report, "risk_controls");
        if (!truthy(controls)) return List.of();
        List<String> lines = lines("### Framework risk controls", "");
        for (Map.Entry<String, JsonNode> field : entries(controls)) {
            if (truthy(field.getValue()) && field.getValue().isObject()) lines.addAll(objectTable(fieldName(field.getKey()), field.getValue()));
            else { lines.addAll(table(List.of("Field", "Value"), List.of(List.of(fieldName(field.getKey()), friendlyValue(field.getValue(), field.getKey()))))); lines.add(""); }
        }
        return lines;
    }

    private static List<String> renderNarrative(JsonNode report) {
        ObjectNode narrative = object(report, "narrative"), arguments = object(narrative, "arguments");
        List<String> lines = lines("## 7. Analyst rationale", "", "**Summary:** " + text(get(narrative, "summary")), "",
                "**Bull case:** " + text(get(narrative, "bull_case")), "", "**Bear case:** " + text(get(narrative, "bear_case")), "",
                "**Rationale:** " + text(get(narrative, "rationale")), "", "**Primary action:** " + actionFor(get(narrative, "primary_action")), "");
        List<List<?>> named = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : entries(arguments)) if (!field.getValue().isArray()) named.add(List.of(fieldName(field.getKey()), field.getValue()));
        if (!named.isEmpty()) { add(lines, "### Decision-support arguments", ""); lines.addAll(table(List.of("Argument", "Reading"), named)); lines.add(""); }
        if (!array(arguments, "discretion_ledger").isEmpty()) {
            add(lines, "### Discretion ledger", "");
            List<List<?>> ledger = new ArrayList<>();
            for (JsonNode item : array(arguments, "discretion_ledger")) ledger.add(List.of(get(item, "date"), get(item, "channel"), get(item, "call"), get(item, "size"), get(item, "stop"), get(item, "falsifier"), status(get(item, "status")), get(item, "pnl")));
            lines.addAll(table(List.of("Date", "Channel", "Call", "Size", "Stop", "Falsifier", "Status", "P&L"), ledger)); lines.add("");
        }
        return lines;
    }

    private static List<String> renderCompanion(JsonNode report) {
        ObjectNode companion = object(report, "companion_framework"), validation = object(report, "cross_validation");
        List<String> lines = lines("## 8. Companion framework and cross-validation", "");
        lines.addAll(table(List.of("Check", "Status", "Score / relationship", "Reading"), List.of(
                List.of("Companion framework", status(get(companion, "status")), stringOr(get(companion, "framework"), DASH)
                        + (hasValue(get(companion, "score")) ? " · " + string(get(companion, "score")) + "/20" : "")
                        + (hasValue(get(companion, "gates")) ? " · " + string(get(companion, "gates")) + " gates" : ""), stringOr(get(companion, "rationale"), DASH)),
                List.of("Cross-validation", status(get(validation, "status")), stringOr(get(validation, "relationship"), DASH), stringOr(get(validation, "rationale"), DASH)))));
        lines.add(""); return lines;
    }

    private static List<String> renderWatchlist(JsonNode report) {
        List<String> lines = lines("## 9. Watchlist, events, falsifiers, and changes", "", "### Watchlist", "");
        lines.addAll(table(List.of("Item", "Status", "Trigger"), rows(array(report, "watchlist"), item -> List.of(get(item, "item"), status(get(item, "status")), get(item, "trigger")))));
        add(lines, "", "### Events", "");
        lines.addAll(table(List.of("Date / time", "Event", "Status", "Impact"), rows(array(report, "events"), item -> List.of(get(item, "as_of"), get(item, "name"), status(get(item, "status")), get(item, "impact")))));
        add(lines, "", "### Falsifiers", "");
        lines.addAll(table(
                List.of("Claim", "Condition", "Status"),
                rows(array(report, "falsifiers"), item -> List.of(
                        get(item, "claim"), get(item, "condition"), status(get(item, "status"))))));
        add(lines, "", "### Change log", "");
        lines.addAll(table(List.of("Field", "Previous", "Current", "Reason"), rows(array(report, "change_log"), item -> List.of(fieldName(string(get(item, "field"))), friendlyValue(get(item, "previous"), "previous"), friendlyValue(get(item, "current"), "current"), get(item, "reason")))));
        lines.add(""); return lines;
    }

    private static List<String> renderSubstitutionsAndSources(JsonNode report) {
        List<String> lines = lines("## 10. Substitutions, source register, and provenance", "", "### Asset substitutions", "");
        lines.addAll(table(List.of("Field", "Original", "Substitute", "Reason"), rows(array(report, "substitutions"), item -> List.of(fieldName(string(get(item, "field"))), get(item, "original"), get(item, "substitute"), get(item, "rationale")))));
        add(lines, "", "### Sources", "");
        List<List<?>> sources = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : entries(object(report, "sources"))) {
            JsonNode source = field.getValue();
            sources.add(List.of(field.getKey(), get(source, "name"), get(source, "kind"), get(source, "as_of"), get(source, "retrieved_at"),
                    stringOr(get(source, "note"), DASH) + (truthy(get(source, "url")) ? "<br>[Open source](" + string(get(source, "url")) + ")" : "")));
        }
        lines.addAll(table(List.of("ID", "Name", "Kind", "As of", "Retrieved", "Note / link"), sources));
        add(lines, "", "### Report timestamps", "");
        List<List<?>> timestamps = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : entries(object(report, "timestamps"))) timestamps.add(List.of(fieldName(field.getKey()), field.getValue()));
        lines.addAll(table(List.of("Timestamp", "Value"), timestamps));
        add(lines, "", "### Run provenance", "");
        lines.addAll(table(List.of("Field", "Value"), List.of(
                List.of("Report ID", get(report, "report_id")), List.of("Report filename", get(report, "identity", "filename")),
                List.of("Run ID", get(report, "run", "run_id")), List.of("Snapshot ID", get(report, "run", "snapshot_id")),
                List.of("Prior report", stringOr(get(report, "run", "prior_report_id"), "None")), List.of("Prior report hash", stringOr(get(report, "run", "prior_report_sha256"), "None")))));
        lines.add("");
        if (truthy(get(report, "run", "tool_hashes"))) {
            add(lines, "#### Tool hashes", "");
            List<List<?>> hashes = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : entries(get(report, "run", "tool_hashes"))) hashes.add(List.of(field.getKey(), field.getValue()));
            lines.addAll(table(List.of("Tool", "Hash"), hashes)); lines.add("");
        }
        return lines;
    }

    private static List<String> renderTagging(JsonNode report) {
        ObjectNode tagging = object(report, "tagging");
        List<String> lines = lines("## 11. Phase registry and canonical tags", "");
        lines.addAll(table(List.of("Phase", "Decision", "Canonical tag", "Instrument class"), rows(array(tagging, "entries"), item -> List.of(get(item, "phase"), status(get(item, "decision")), get(item, "canonical_tag"), get(item, "instrument_class")))));
        add(lines, "", "**Registry:** " + stringOr(get(tagging, "schema"), DASH) + " · " + status(get(tagging, "status")) + " · instrument class " + stringOr(get(tagging, "instrument_class"), DASH),
                "**Active tags:** " + (!array(tagging, "active_tags").isEmpty() ? joinRaw(array(tagging, "active_tags"), ", ") : "None"),
                "**Reserved tags:** " + (!array(tagging, "reserved_tags").isEmpty() ? joinRaw(array(tagging, "reserved_tags"), ", ") : "None"), "");
        return lines;
    }

    private static String text(JsonNode value) {
        String raw = present(value) ? string(value) : DASH;
        return raw.replace("```", "`\\`\\`").replaceAll("\\r?\\n", "\n> ");
    }

    private static String oneLine(Object value) {
        String raw = value == null ? DASH : value instanceof JsonNode node ? (present(node) ? string(node) : DASH) : String.valueOf(value);
        return raw.replace("```", "`\\`\\`").replaceAll("\\r?\\n", "<br>").replace("|", "\\|");
    }

    private static String human(String value) {
        if (value == null || value.isEmpty()) return DASH;
        String result = CAMEL.matcher(value).replaceAll("$1 $2").replaceAll("[_-]+", " ").replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? result : FIRST.matcher(result).replaceFirst(Matcher.quoteReplacement(result.substring(0, 1).toUpperCase(Locale.ROOT)));
    }

    private static String fieldName(String value) {
        String key = value == null ? "" : value;
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[-\\s]+", "_");
        return FIELD_LABELS.getOrDefault(normalized, human(key).replaceAll("\\bUsd\\b", "USD").replaceAll("\\bPaxg\\b", "PAXG"));
    }

    private static String status(JsonNode value) { return !hasValue(value) ? DASH : status(string(value)); }
    private static String status(String value) {
        if (value == null || value.isEmpty()) return DASH;
        return MARKS.getOrDefault(value.toUpperCase(Locale.ROOT), "•") + " " + value;
    }

    private static String formatNumber(JsonNode value) { return formatNumber(present(value) ? string(value) : "undefined"); }
    private static String formatNumber(Object value) {
        String raw = value == null ? "null" : value instanceof JsonNode node ? string(node) : String.valueOf(value);
        if (!DECIMAL.matcher(raw).matches()) return raw;
        int dot = raw.indexOf('.'); String whole = dot < 0 ? raw : raw.substring(0, dot), fraction = dot < 0 ? null : raw.substring(dot + 1);
        String sign = whole.startsWith("-") ? "-" : "", digits = sign.isEmpty() ? whole : whole.substring(1);
        StringBuilder grouped = new StringBuilder();
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (digits.length() - index) % 3 == 0) grouped.append(',');
            grouped.append(digits.charAt(index));
        }
        return sign + grouped + (fraction == null ? "" : "." + fraction);
    }

    private static String moneyValue(JsonNode value) {
        String number = formatNumber(value);
        return number.startsWith("-") ? "-$" + number.substring(1) : "$" + number;
    }

    private static String measurementValue(JsonNode value, JsonNode unit) {
        if (!hasValue(value)) return DASH;
        if (value.isObject() || value.isArray()) return friendlyValue(value);
        String number = formatNumber(value), normalized = truthy(unit) ? string(unit) : "";
        if (Pattern.compile("^percent(?:\\s|$)", Pattern.CASE_INSENSITIVE).matcher(normalized).find()
                || Pattern.compile("percent", Pattern.CASE_INSENSITIVE).matcher(normalized).find()) return number + "%";
        if ("USD".equalsIgnoreCase(normalized)) return moneyValue(value);
        if (normalized.regionMatches(true, 0, "USD/", 0, 4)) return moneyValue(value) + "/" + normalized.substring(4);
        return normalized.isEmpty() ? number : number + " " + normalized;
    }

    private static String probabilityPercent(JsonNode value) {
        if (!hasValue(value)) return DASH;
        double number;
        try { number = Double.parseDouble(string(value)); } catch (NumberFormatException exception) { number = Double.NaN; }
        double percentage = Math.floor(number * 10000 + 0.5) / 100;
        return formatNumber(com.tradinganalytics.contracts.json.CanonicalJson.canonicalize(percentage)) + "%";
    }

    private static String scalar(JsonNode value, String key) {
        if (!hasValue(value)) return DASH;
        if (value.isBoolean()) return value.booleanValue() ? "Yes" : "No";
        if (value.isNumber()) return formatNumber(value);
        String keyName = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if ("status".equals(keyName) || "state".equals(keyName) || "decision".equals(keyName)) return status(value);
        if (keyName.endsWith("_pct") || keyName.endsWith("percentage")) return formatNumber(value) + "%";
        if (keyName.endsWith("_usd")) return moneyValue(value);
        return oneLine(value);
    }

    private static String friendlyValue(JsonNode value) { return friendlyValue(value, ""); }
    private static String friendlyValue(JsonNode value, String key) {
        if (!hasValue(value)) return DASH;
        if (value.isArray()) {
            if (value.isEmpty()) return "None";
            List<String> values = new ArrayList<>(); value.forEach(item -> values.add(friendlyValue(item)));
            return String.join("; ", values);
        }
        if (value.isObject()) {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : entries(value)) values.add(fieldName(field.getKey()) + ": " + friendlyValue(field.getValue(), field.getKey()));
            return String.join("; ", values);
        }
        return scalar(value, key);
    }

    private static List<String> table(List<String> headers, List<? extends List<?>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("| " + String.join(" | ", headers) + " |");
        lines.add("| " + String.join(" | ", headers.stream().map(ignored -> "---").toList()) + " |");
        for (List<?> row : rows) {
            List<String> cells = new ArrayList<>();
            for (int index = 0; index < headers.size(); index++) cells.add(oneLine(index < row.size() ? row.get(index) : null));
            lines.add("| " + String.join(" | ", cells) + " |");
        }
        return lines;
    }

    private static List<String> objectTable(String title, JsonNode value) {
        if (value == null || !value.isObject() || value.isEmpty()) return lines("### " + title, "", "- None recorded.", "");
        List<List<?>> rows = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : entries(value)) rows.add(List.of(fieldName(field.getKey()), friendlyValue(field.getValue(), field.getKey())));
        List<String> lines = lines("### " + title, ""); lines.addAll(table(List.of("Field", "Value"), rows)); lines.add(""); return lines;
    }

    private static String sourceLinks(JsonNode ids, JsonNode sources) {
        if (ids == null || !ids.isArray() || ids.isEmpty()) return DASH;
        List<String> links = new ArrayList<>();
        for (JsonNode id : ids) {
            JsonNode url = get(sources, string(id), "url");
            links.add(truthy(url) ? "[" + string(id) + "](" + string(url) + ")" : string(id));
        }
        return String.join(", ", links);
    }

    private static List<?> measurementRow(String name, JsonNode value, JsonNode sources) {
        if (!truthy(value)) return List.of(fieldName(name), DASH, DASH, DASH, DASH, DASH);
        JsonNode noteNode = firstTruthyNode(get(value, "note"), get(value, "rationale"), textNode(DASH));
        String note = oneLine(noteNode) + (!array(value, "source_ids").isEmpty() ? "<br>Sources: " + sourceLinks(get(value, "source_ids"), sources) : "");
        return List.of(fieldName(name), measurementValue(get(value, "value"), get(value, "unit")), status(get(value, "status")),
                stringOr(get(value, "confidence"), DASH), stringOr(get(value, "as_of"), DASH), note);
    }

    private static String actionFor(JsonNode value) {
        if (!truthy(value)) return "Unavailable — evidence unavailable.";
        return "**" + stringOr(get(value, "value"), "UNSPECIFIED") + "** — " + text(firstTruthyNode(get(value, "rationale"), textNode("No rationale supplied.")));
    }

    private static String frameworkLabel(JsonNode value) {
        String key = present(value) ? string(value) : "";
        return switch (key) { case "fallen_knives" -> "Fallen Knives"; case "flying_rocket" -> "Flying Rocket"; default -> human(key); };
    }

    private static Object maxForLeg(String framework, String key) {
        Map<String, Integer> values = "flying_rocket".equals(framework)
                ? Map.of("euphoria", 5, "momentum", 4, "valuation", 5, "distribution", 3, "vulnerability", 3)
                : Map.of("sentiment", 5, "momentum", 4, "valuation", 5, "capitulation", 3, "holder", 3);
        return values.getOrDefault(key, null) == null ? DASH : values.get(key);
    }

    private static int countActive(ArrayNode values) { int count = 0; for (JsonNode value : values) if (bool(get(value, "active"))) count++; return count; }
    private static boolean bool(JsonNode value) { return value != null && value.isBoolean() && value.booleanValue(); }
    private static String orDash(JsonNode value) { return stringOr(value, DASH); }
    private static JsonNode textNode(String value) { return value == null ? MissingNode.getInstance() : ReportingJson.NODES.textNode(value); }

    private static JsonNode firstPresent(JsonNode... values) { for (JsonNode value : values) if (present(value)) return value; return MissingNode.getInstance(); }
    private static JsonNode firstTruthyNode(JsonNode... values) { for (JsonNode value : values) if (truthy(value)) return value; return MissingNode.getInstance(); }
    private static String firstTruthy(JsonNode... values) { JsonNode found = firstTruthyNode(values); return present(found) ? string(found) : "undefined"; }
    private static ObjectNode firstTruthyObject(JsonNode... values) { JsonNode found = firstTruthyNode(values); return found.isObject() ? (ObjectNode) found : ReportingJson.NODES.objectNode(); }
    private static ObjectNode asObject(JsonNode value) { return value != null && value.isObject() ? (ObjectNode) value : ReportingJson.NODES.objectNode(); }

    private static ObjectNode selectedObject(JsonNode source, String... fields) {
        ObjectNode output = ReportingJson.NODES.objectNode();
        for (String field : fields) output.set(field, present(get(source, field)) ? get(source, field).deepCopy() : MissingNode.getInstance());
        return output;
    }

    private static List<List<?>> rows(ArrayNode values, Function<JsonNode, List<?>> mapper) {
        List<List<?>> rows = new ArrayList<>(); for (JsonNode value : values) rows.add(mapper.apply(value)); return rows;
    }

    private static String joinRaw(ArrayNode values, String delimiter) {
        List<String> strings = new ArrayList<>(); values.forEach(value -> strings.add(string(value))); return String.join(delimiter, strings);
    }

    private static List<String> lines(String... values) { return new ArrayList<>(List.of(values)); }
    private static void add(List<String> target, String... values) { target.addAll(List.of(values)); }

    private static Map<String, String> orderedMap(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(values[index], values[index + 1]);
        return Map.copyOf(result);
    }
}
