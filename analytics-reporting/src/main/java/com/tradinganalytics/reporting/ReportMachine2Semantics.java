package com.tradinganalytics.reporting;

import static com.tradinganalytics.reporting.ReportJsonSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

final class ReportMachine2Semantics {
    private static final Map<String, String> ROUNDING = Map.of(
            "btc", "half-up",
            "gold", "half-up",
            "eth", "half-down",
            "spx", "half-down",
            "sp500", "half-down",
            "ndx", "half-down",
            "nasdaq", "half-down");
    private static final Map<String, Integer> FK_SCORE_UNLOCK = Map.of(
            "p1a", 8, "p1b", 11, "p2", 15, "p3", 17);
    private static final Map<String, Integer> FR_SCORE_UNLOCK_A = Map.of(
            "p1a", 11, "p1b", 13, "p2", 15, "p3", 19);
    private static final Map<String, Integer> FR_SCORE_UNLOCK_B = Map.of(
            "p1a", 13, "p1b", 15, "p2", 17);

    private ReportMachine2Semantics() {
    }

    static SemanticIssues validate(JsonNode report, String filename) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String id = text(report, "report_id");
        Matcher identityMatch = ReportPaths.REPORT_REPORT_ID_RE.matcher(id == null ? "" : id);
        if (!identityMatch.matches()) {
            errors.add("report_id has invalid identity format");
        } else {
            String asset = identityMatch.group(1);
            String framework = identityMatch.group(2);
            String stamp = identityMatch.group(3);
            String time = identityMatch.group(4);
            String expected = asset + '_' + framework + '_' + stamp + '_' + time + ".json";
            ObjectNode identity = object(report, "identity");
            if (!asset.toUpperCase(Locale.ROOT).equals(text(identity, "asset"))) {
                errors.add("identity.asset=" + text(identity, "asset") + " does not match report_id");
            }
            if (!framework.equals(text(identity, "framework"))) {
                errors.add("identity.framework=" + text(identity, "framework") + " does not match report_id");
            }
            String expectedDate = stamp.substring(0, 4) + '-' + stamp.substring(4, 6) + '-' + stamp.substring(6, 8);
            if (!expectedDate.equals(text(identity, "date"))) {
                errors.add("identity.date does not match report_id");
            }
            String expectedTime = time.substring(0, 2) + ':' + time.substring(2);
            if (!expectedTime.equals(text(identity, "local_time"))) {
                errors.add("identity.local_time does not match report_id");
            }
            if (!expected.equals(text(identity, "filename"))) {
                errors.add("identity.filename must be " + expected);
            }
            if (filename != null && !basename(filename).equals(expected)) {
                errors.add("filename " + basename(filename) + " does not match identity.filename " + expected);
            }
        }

        ObjectNode identity = object(report, "identity");
        ObjectNode timestamps = object(report, "timestamps");
        if (!sameNullable(text(timestamps, "timezone"), text(identity, "timezone"))) {
            errors.add("timestamps.timezone and identity.timezone differ");
        }
        for (String key : List.of("generated_at", "report_at", "data_as_of")) {
            if (!iso(field(timestamps, key))) {
                errors.add("timestamps." + key + " is not a UTC ISO timestamp");
            }
        }
        String expectedReportAt = localToUtcIso(
                text(identity, "date"), text(identity, "local_time"), text(identity, "timezone"));
        if (expectedReportAt != null && !expectedReportAt.equals(text(timestamps, "report_at"))) {
            errors.add("timestamps.report_at=" + text(timestamps, "report_at")
                    + " does not match identity local time (" + expectedReportAt + ')');
        }
        if (iso(field(timestamps, "data_as_of")) && iso(field(timestamps, "report_at"))
                && text(timestamps, "data_as_of").compareTo(text(timestamps, "report_at")) > 0) {
            errors.add("timestamps.data_as_of cannot be later than report_at");
        }
        if (iso(field(timestamps, "generated_at")) && iso(field(timestamps, "report_at"))
                && text(timestamps, "generated_at").compareTo(text(timestamps, "report_at")) < 0) {
            warnings.add("generated_at precedes report_at; verify the report was not generated from a future-dated draft");
        }

        Set<String> sourceIds = fieldNames(field(report, "sources"));
        ObjectNode evidence = object(report, "evidence");
        for (Map.Entry<String, JsonNode> entry : evidence.properties()) {
            JsonNode value = entry.getValue();
            String status = text(value, "status");
            if (!ReportPaths.REPORT_STATUSES.contains(status)) {
                errors.add("evidence." + entry.getKey() + ".status invalid");
            }
            sourceRefs(errors, sourceIds, field(value, "source_ids"), "evidence." + entry.getKey());
            if (!"AVAILABLE".equals(status) && !field(value, "value").isNull()) {
                errors.add("evidence." + entry.getKey() + ": " + status + " must carry value:null");
            }
            if ("AVAILABLE".equals(status) && field(value, "value").isNull()) {
                errors.add("evidence." + entry.getKey() + ": AVAILABLE cannot carry value:null");
            }
        }

        ObjectNode market = object(report, "market");
        JsonNode spot = field(market, "spot");
        sourceRefs(errors, sourceIds, field(spot, "source_ids"), "market.spot");
        for (JsonNode quote : array(object(market, "reconciliation"), "quotes")) {
            sourceRefs(errors, sourceIds, field(quote, "source_ids"), "market.reconciliation");
        }
        LinkedHashMap<String, JsonNode> measurements = new LinkedHashMap<>();
        measurements.put("spot", spot);
        measurements.put("ath", field(market, "ath"));
        measurements.put("drawdown_pct", field(market, "drawdown_pct"));
        JsonNode metrics = field(market, "metrics");
        if (metrics.isObject()) {
            metrics.properties().forEach(entry -> measurements.put(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, JsonNode> entry : measurements.entrySet()) {
            JsonNode measurement = entry.getValue();
            if (measurement.isMissingNode() || measurement.isNull()) {
                continue;
            }
            sourceRefs(errors, sourceIds, field(measurement, "source_ids"), "market." + entry.getKey());
            String status = text(measurement, "status");
            if (!"AVAILABLE".equals(status) && !field(measurement, "value").isNull()) {
                errors.add("market." + entry.getKey() + ": " + status + " must carry value:null");
            }
            if ("AVAILABLE".equals(status)) {
                plainDecimal(field(measurement, "value"), "market." + entry.getKey() + ".value");
            }
        }
        ObjectNode reconciliation = object(market, "reconciliation");
        if ("AVAILABLE".equals(text(reconciliation, "status")) && array(reconciliation, "quotes").size() < 2) {
            errors.add("market.reconciliation requires at least two quotes when AVAILABLE");
        }
        for (JsonNode gap : array(report, "data_gaps")) {
            sourceRefs(errors, sourceIds, field(gap, "source_ids"), "data_gaps." + text(gap, "field"));
        }

        String framework = text(identity, "framework");
        JsonNode channelNode = field(report, "channel");
        String channel = channelNode.isTextual() ? channelNode.textValue() : null;
        if ("flying_rocket".equals(framework) && !List.of("A", "B", "none").contains(channel)) {
            errors.add("flying_rocket reports require channel A, B, or none");
        }
        if ("fallen_knives".equals(framework) && !channelNode.isNull() && !channelNode.isMissingNode()) {
            errors.add("fallen_knives reports must set channel:null when channel is present");
        }
        if ("flying_rocket".equals(framework) && "B".equals(channel)) {
            JsonNode regime = field(report, "regime");
            if (regime.isMissingNode() || regime.isNull()) {
                errors.add("Flying Rocket Channel B requires regime evidence");
            } else {
                if (plainDecimal(field(regime, "pct_below_1y_ath"), "regime.pct_below_1y_ath") <= 20) {
                    errors.add("Channel B requires pct_below_1y_ath > 20");
                }
                if (!isTrue(field(regime, "ma200_falling")) || !isTrue(field(regime, "price_below_ma200"))) {
                    errors.add("Channel B requires a falling 200-day MA and price below it");
                }
            }
        }

        List<String> legNames = "fallen_knives".equals(framework)
                ? List.of("sentiment", "momentum", "valuation", "capitulation", "holder")
                : List.of("euphoria", "momentum", "valuation", "distribution", "vulnerability");
        Map<String, Integer> maxes = "fallen_knives".equals(framework)
                ? Map.of("sentiment", 5, "momentum", 4, "valuation", 5, "capitulation", 3, "holder", 3)
                : Map.of("euphoria", 5, "momentum", 4, "valuation", 5, "distribution", 3, "vulnerability", 3);
        ObjectNode score = object(report, "score");
        ObjectNode legs = object(score, "legs");
        if (legs.size() != legNames.size() || !fieldNames(legs).equals(new LinkedHashSet<>(legNames))) {
            errors.add("score.legs must contain exactly " + String.join(", ", legNames));
        }
        for (String name : legNames) {
            JsonNode value = field(legs, name);
            int min = "fallen_knives".equals(framework) && "valuation".equals(name) ? -2 : 0;
            if (!value.isNumber() || value.doubleValue() < min || value.doubleValue() > maxes.get(name)) {
                errors.add("score.legs." + name + " outside [" + min + ',' + maxes.get(name) + ']');
            }
        }
        double penalty = 0;
        for (JsonNode value : array(score, "penalties")) {
            penalty += value.doubleValue();
        }
        if ("flying_rocket".equals(framework) && (penalty > 0 || penalty < -4)) {
            errors.add("Flying Rocket penalty sum " + jsNumber(penalty) + " is outside -4..0");
        }
        double legSum = 0;
        for (String name : legNames) {
            legSum += field(legs, name).doubleValue();
        }
        double raw = legSum + penalty + number(score, "discretion");
        double mechanicalRaw = legSum + penalty;
        String rounding = text(score, "rounding");
        if (rounding == null) {
            rounding = ROUNDING.get(String.valueOf(text(identity, "asset")).toLowerCase(Locale.ROOT));
        }
        if (rounding == null) {
            errors.add("no pinned rounding convention for " + text(identity, "asset"));
        } else {
            double mechanical = clamp(roundScore(mechanicalRaw, rounding), 0, 20);
            double adjusted = clamp(roundScore(raw, rounding), 0, 20);
            for (JsonNode cap : array(score, "caps")) {
                JsonNode capValue = field(cap, "value");
                if (isTrue(field(cap, "applied")) && capValue.isNumber()) {
                    if (capValue.doubleValue() < 0 || capValue.doubleValue() > 20) {
                        errors.add("score cap " + jsNumber(capValue.doubleValue()) + " is outside 0..20");
                    }
                    mechanical = Math.min(mechanical, capValue.doubleValue());
                    adjusted = Math.min(adjusted, capValue.doubleValue());
                }
            }
            if (!numericEquals(field(score, "mechanical"), mechanical)) {
                errors.add("score.mechanical=" + jsonDisplay(field(score, "mechanical"))
                        + " but expected " + jsNumber(mechanical));
            }
            if (!numericEquals(field(score, "raw"), raw)) {
                errors.add("score.raw=" + jsonDisplay(field(score, "raw")) + " but expected " + jsNumber(raw));
            }
            if (!numericEquals(field(score, "adjusted"), adjusted)) {
                errors.add("score.adjusted=" + jsonDisplay(field(score, "adjusted"))
                        + " but expected " + jsNumber(adjusted));
            }
        }

        ObjectNode gate = object(report, "gates");
        List<Integer> na = integerList(field(gate, "na"));
        List<Integer> passed = integerList(field(gate, "passed"));
        List<Integer> sortedNa = na.stream().sorted().toList();
        List<Integer> sortedPassed = passed.stream().sorted().toList();
        int active = field(gate, "active").intValue();
        if (active != 9 - na.size()) {
            errors.add("gates.active=" + active + " but expected " + (9 - na.size()));
        }
        if (!na.equals(sortedNa)) {
            errors.add("gates.na must be sorted");
        }
        if (!passed.equals(sortedPassed)) {
            errors.add("gates.passed must be sorted");
        }
        if (passed.stream().anyMatch(na::contains)) {
            errors.add("gates.passed and gates.na overlap");
        }
        Map<String, Integer> expectedThresholds = "fallen_knives".equals(framework)
                ? fkGateThresholds(active)
                : frGateThresholds(active);
        ObjectNode thresholds = object(gate, "thresholds");
        for (String key : List.of("p1a", "p1b", "p2")) {
            if (field(thresholds, key).intValue() != expectedThresholds.get(key)) {
                errors.add("gates.thresholds." + key + " does not match deterministic threshold");
            }
        }
        if ("fallen_knives".equals(framework)
                && field(thresholds, "p3").intValue() != expectedThresholds.get("p3")) {
            errors.add("gates.thresholds.p3 does not match deterministic threshold");
        }
        ObjectNode companion = object(report, "companion_framework");
        if ("flying_rocket".equals(framework) && "AVAILABLE".equals(text(companion, "status"))
                && !"fallen_knives".equals(text(companion, "framework"))) {
            errors.add("flying_rocket companion must be fallen_knives when available");
        }

        ObjectNode ev = object(report, "ev");
        double probability = 0;
        for (JsonNode scenario : array(ev, "scenarios")) {
            probability += number(scenario, "probability");
        }
        if (!same(probability, 1, 0.000001) || !same(number(ev, "probability_sum"), probability, 0.000001)) {
            errors.add("EV probability sum " + jsNumber(probability) + " is not exactly 1");
        }
        if ("CHECKED".equals(text(ev, "arithmetic_status"))) {
            double expected = 0;
            for (JsonNode scenario : array(ev, "scenarios")) {
                expected += number(scenario, "probability")
                        * ((plainDecimal(field(scenario, "low"), "ev.low")
                        + plainDecimal(field(scenario, "high"), "ev.high")) / 2);
            }
            Double stated = maybePlainDecimal(field(ev, "stated_ev"), "ev.stated_ev");
            if (stated == null || !same(stated, expected, Math.max(0.01, Math.abs(expected) * 0.005))) {
                errors.add("ev.stated_ev does not match weighted scenario EV (" + jsNumber(expected) + ')');
            }
            if ("AVAILABLE".equals(text(spot, "status")) && stated != null) {
                double expectedPct = (stated / plainDecimal(field(spot, "value"), "market.spot.value") - 1) * 100;
                if (field(ev, "vs_spot_pct").isNull()
                        || !same(plainDecimal(field(ev, "vs_spot_pct"), "ev.vs_spot_pct"), expectedPct, 0.01)) {
                    errors.add("ev.vs_spot_pct does not match stated_ev and spot");
                }
            }
        } else if (!field(ev, "stated_ev").isNull() || !field(ev, "vs_spot_pct").isNull()) {
            errors.add("non-CHECKED EV must carry stated_ev and vs_spot_pct as null");
        }

        double deployed = 0;
        double dry = 0;
        List<String> filledTags = new ArrayList<>();
        Map<String, Integer> scoreUnlock = "fallen_knives".equals(framework)
                ? FK_SCORE_UNLOCK
                : ("B".equals(channel) ? FR_SCORE_UNLOCK_B : FR_SCORE_UNLOCK_A);
        ObjectNode deployment = object(report, "deployment");
        ArrayNode tranches = array(deployment, "tranches");
        for (int index = 0; index < tranches.size(); index++) {
            JsonNode tranche = tranches.get(index);
            double pct = plainDecimal(field(tranche, "pct"), "deployment.tranches[" + index + "].pct");
            boolean trancheDeployed = isTrue(field(tranche, "deployed"));
            boolean filled = "FILLED".equals(text(tranche, "state")) || trancheDeployed;
            if (filled) {
                deployed += pct;
                if (field(tranche, "entry_price").isNull()) {
                    errors.add("deployment.tranches[" + index + "] filled without entry_price");
                }
                if (field(tranche, "stop").isNull()) {
                    errors.add("deployment.tranches[" + index + "] filled without stop");
                }
                // Preserve the JavaScript key lookup exactly: "1A" becomes "1a", while
                // the maps use p1a. This historical no-op is part of report-machine/2.
                String phase = text(tranche, "phase");
                String phaseKey = phase == null ? "" : phase.toLowerCase(Locale.ROOT);
                Integer scoreLine = scoreUnlock.get(phaseKey);
                JsonNode gateLine = field(thresholds, phaseKey);
                double scoreForPhase = "3".equals(phase) ? number(score, "mechanical") : number(score, "adjusted");
                if (scoreLine != null && scoreForPhase < scoreLine) {
                    errors.add("deployment.tranches[" + index + "] filled below " + phase
                            + " score unlock " + scoreLine);
                }
                if (!gateLine.isMissingNode() && passed.size() < gateLine.intValue()) {
                    errors.add("deployment.tranches[" + index + "] filled below " + phase
                            + " gate floor " + gateLine.intValue());
                }
                Double entry = field(tranche, "entry_price").isNull() ? null
                        : plainDecimal(field(tranche, "entry_price"), "deployment.tranches[" + index + "].entry_price");
                Double stop = field(tranche, "stop").isNull() ? null
                        : plainDecimal(field(tranche, "stop"), "deployment.tranches[" + index + "].stop");
                if (entry != null && stop != null) {
                    if ("fallen_knives".equals(framework) && stop >= entry) {
                        errors.add("deployment.tranches[" + index + "] long stop must be below entry");
                    }
                    if ("flying_rocket".equals(framework) && stop <= entry) {
                        errors.add("deployment.tranches[" + index + "] short stop must be above entry");
                    }
                }
                if ("flying_rocket".equals(framework)) {
                    String timeStop = text(tranche, "time_stop");
                    if (timeStop == null || timeStop.isEmpty()) {
                        errors.add("deployment.tranches[" + index + "] Flying Rocket fill requires a time stop");
                    } else {
                        long stopMillis = parseDateMillis(timeStop);
                        long reportMillis = parseDateMillis(text(timestamps, "report_at"));
                        if (stopMillis == Long.MIN_VALUE || reportMillis == Long.MIN_VALUE || stopMillis <= reportMillis) {
                            errors.add("deployment.tranches[" + index + "] time stop must be a future timestamp");
                        }
                        int maxDays = "B".equals(channel) ? ("2".equals(phase) ? 28 : 21) : 14;
                        if (stopMillis != Long.MIN_VALUE && reportMillis != Long.MIN_VALUE
                                && stopMillis - reportMillis > maxDays * 86_400_000L + 1_000L) {
                            errors.add("deployment.tranches[" + index + "] time stop exceeds " + maxDays + "-day limit");
                        }
                    }
                }
                if (!field(tranche, "prior_stop").isNull()) {
                    double prior = plainDecimal(field(tranche, "prior_stop"), "deployment.tranches[" + index + "].prior_stop");
                    if ("fallen_knives".equals(framework) && stop != null && stop < prior) {
                        errors.add("deployment.tranches[" + index + "] FK stop ratchet moved away from price");
                    }
                    if ("flying_rocket".equals(framework) && stop != null && stop > prior) {
                        errors.add("deployment.tranches[" + index + "] FR stop ratchet moved away from price");
                    }
                }
                filledTags.add(text(tranche, "tag"));
            } else {
                dry += pct;
            }
            if ("FILLED".equals(text(tranche, "state")) && !trancheDeployed) {
                errors.add("deployment.tranches[" + index + "] FILLED must set deployed:true");
            }
            if (trancheDeployed && !"FILLED".equals(text(tranche, "state"))) {
                errors.add("deployment.tranches[" + index + "] deployed:true must be state FILLED");
            }
            if (!field(tranche, "entry_price").isNull()) {
                plainDecimal(field(tranche, "entry_price"), "deployment.tranches[" + index + "].entry_price");
            }
            if (!field(tranche, "stop").isNull()) {
                plainDecimal(field(tranche, "stop"), "deployment.tranches[" + index + "].stop");
            }
        }
        if (!same(deployed, plainDecimal(field(deployment, "deployed_pct"), "deployment.deployed_pct"), 0.000001)) {
            errors.add("deployment.deployed_pct does not equal filled tranche total");
        }
        if (!same(dry, plainDecimal(field(deployment, "dry_pct"), "deployment.dry_pct"), 0.000001)) {
            errors.add("deployment.dry_pct does not equal unfilled tranche total");
        }

        ObjectNode position = object(report, "position");
        ObjectNode controls = object(report, "position_controls");
        if (!sameNullable(text(position, "asset"), text(identity, "asset"))) {
            errors.add("position.asset must match identity.asset");
        }
        boolean positionOpen = !field(position, "quantity").isNull()
                && plainDecimal(field(position, "quantity"), "position.quantity") != 0;
        if ("DATA_LIMITED".equals(text(position, "status")) && !field(position, "quantity").isNull()) {
            errors.add("DATA_LIMITED position must not be converted into a numeric quantity");
        }
        JsonNode custody = field(position, "custody");
        String custodyStatus = text(custody, "status");
        if ("EXPLAINED_BY_EXTERNAL_TRANSFER".equals(custodyStatus)
                && !hasNonNull(custody, "off_venue_qty")) {
            errors.add("external-transfer custody requires custody.off_venue_qty");
        }
        if ("UNEXPLAINED".equals(custodyStatus) && !field(position, "quantity").isNull()) {
            errors.add("UNEXPLAINED custody cannot report a quantity; resolve the ledger defect first");
        }
        JsonNode basis = field(position, "basis");
        if (isFalse(field(basis, "reliable")) && (hasNonNull(basis, "avg_cost")
                || hasNonNull(basis, "total_cost") || hasNonNull(field(position, "pnl"), "unrealized"))) {
            errors.add("unreliable basis cannot carry average cost, cost basis, or unrealized PnL");
        }
        String controlsStatus = text(controls, "status");
        if (positionOpen && !"OPEN".equals(controlsStatus)) {
            errors.add("non-zero position requires position_controls.status=OPEN");
        }
        if (!positionOpen && "OPEN".equals(controlsStatus)) {
            errors.add("OPEN position_controls requires a non-zero position quantity");
        }
        if ("NOT_APPLICABLE".equals(controlsStatus)) {
            if (!isFalse(field(controls, "required"))) {
                errors.add("FLAT/NOT_APPLICABLE position_controls must set required:false");
            }
            if (!"NOT_APPLICABLE".equals(text(object(controls, "action"), "status"))) {
                errors.add("FLAT position action must be NOT_APPLICABLE");
            }
        }
        if ("DATA_LIMITED".equals(controlsStatus)) {
            JsonNode action = field(controls, "action");
            if ("AVAILABLE".equals(text(action, "status"))
                    && List.of("HOLD", "RETAIN").contains(text(action, "value"))) {
                errors.add("DATA_LIMITED position cannot fabricate HOLD/RETAIN");
            }
        }
        if ("OPEN".equals(controlsStatus)) {
            for (String key : List.of("candidate", "veto", "selection", "venue_order", "ladder", "pnl",
                    "ratchet", "liquidation_zone", "risk", "execution_audit")) {
                if (!hasNonNull(controls, key)) {
                    errors.add("OPEN position_controls missing " + key);
                }
            }
            if (!isTrue(field(controls, "required"))) {
                errors.add("OPEN position_controls must set required:true");
            }
        }
        if (!filledTags.isEmpty() && !"FRESH".equals(text(position, "status"))) {
            errors.add("filled tranches require a FRESH position snapshot");
        }
        if (!filledTags.isEmpty() && "EXPLAINED_BY_EXTERNAL_TRANSFER".equals(custodyStatus)) {
            errors.add("custody-adjusted quantity cannot satisfy a phase-dependent fill unlock");
        }

        ObjectNode tags = object(report, "tagging");
        List<String> reservedTags = stringList(field(tags, "reserved_tags"));
        List<String> activeTags = stringList(field(tags, "active_tags"));
        Set<String> reserved = new LinkedHashSet<>(reservedTags);
        Set<String> activeTagsSet = new LinkedHashSet<>(activeTags);
        if (activeTagsSet.size() != activeTags.size()) {
            errors.add("tagging.active_tags contains duplicates");
        }
        if (reserved.size() != reservedTags.size()) {
            errors.add("tagging.reserved_tags contains duplicates");
        }
        for (String tag : activeTagsSet) {
            if (!reserved.contains(tag)) {
                errors.add("active tag " + tag + " is not reserved");
            }
        }
        for (String tag : filledTags) {
            if (!activeTagsSet.contains(tag)) {
                errors.add("filled tranche tag " + tag + " is not active");
            }
        }
        String tagIdentity = text(identity, "asset") + '-'
                + text(identity, "date").replace("-", "") + '-'
                + text(identity, "local_time").replace(":", "");
        for (String tag : reservedTags) {
            if (!tag.contains(text(identity, "asset")) || !tag.contains(tagIdentity)) {
                errors.add("tag " + tag + " does not carry report asset/time identity");
            }
        }
        ArrayNode tagEntries = array(tags, "entries");
        if ("flying_rocket".equals(framework) && "B".equals(channel)) {
            for (JsonNode entry : tagEntries) {
                if ("3".equals(text(entry, "phase"))) {
                    errors.add("FR Channel B cannot reserve or register Phase 3");
                    break;
                }
            }
        }
        List<String> expectedPhases = "flying_rocket".equals(framework) && "B".equals(channel)
                ? List.of("1A", "1B", "2") : List.of("1A", "1B", "2", "3");
        List<String> entryPhases = new ArrayList<>();
        tagEntries.forEach(entry -> entryPhases.add(text(entry, "phase")));
        entryPhases.sort(Comparator.comparingInt(expectedPhases::indexOf));
        if (!entryPhases.equals(expectedPhases)) {
            errors.add("tagging.entries must contain each applicable phase exactly once");
        }
        Set<String> entryTags = new LinkedHashSet<>();
        tagEntries.forEach(entry -> entryTags.add(text(entry, "canonical_tag")));
        if (entryTags.size() != tagEntries.size() || entryTags.size() != reserved.size()
                || entryTags.stream().anyMatch(tag -> !reserved.contains(tag))) {
            errors.add("tagging.entries and reserved_tags must be the same tag registry");
        }
        for (JsonNode entry : tagEntries) {
            if (!sameNullable(text(entry, "instrument_class"), text(tags, "instrument_class"))) {
                errors.add("tagging entry " + text(entry, "phase") + " instrument class differs from registry");
            }
        }
        for (String tag : activeTagsSet) {
            JsonNode matching = null;
            for (JsonNode entry : tagEntries) {
                if (tag.equals(text(entry, "canonical_tag"))) {
                    matching = entry;
                    break;
                }
            }
            if (matching == null || !"AUTHORIZED".equals(text(matching, "decision"))) {
                errors.add("active tag " + tag + " is not AUTHORIZED in tagging registry");
            }
        }
        if ("REGISTERED".equals(text(tags, "status")) && tagEntries.isEmpty()) {
            errors.add("REGISTERED tagging registry cannot be empty");
        }
        ObjectNode crossValidation = object(report, "cross_validation");
        if ("AVAILABLE".equals(text(companion, "status")) && !field(companion, "score").isNull()
                && number(score, "adjusted") >= 12 && number(companion, "score") >= 12
                && !"INCONSISTENT".equals(text(crossValidation, "status"))) {
            errors.add("both companion and primary scores are elevated; cross_validation must be INCONSISTENT");
        }
        String expectedCompanion = "fallen_knives".equals(framework) ? "flying_rocket" : "fallen_knives";
        if (!expectedCompanion.equals(text(companion, "framework"))
                && !"none".equals(text(companion, "framework"))) {
            errors.add("companion framework must be " + expectedCompanion + " or none");
        }
        JsonNode ladder = field(controls, "ladder");
        if ("OPEN".equals(controlsStatus) && hasNonNull(ladder, "target_quantity")
                && !field(position, "quantity").isNull()
                && plainDecimal(field(ladder, "target_quantity"), "position_controls.ladder.target_quantity")
                != Math.abs(plainDecimal(field(position, "quantity"), "position.quantity"))) {
            errors.add("position_controls.ladder.target_quantity does not match open position quantity");
        }
        if ("OPEN".equals(controlsStatus)) {
            JsonNode candidate = field(controls, "candidate");
            JsonNode zone = field(controls, "liquidation_zone");
            JsonNode liquidationPrice = field(zone, "price");
            JsonNode referenceEntry = hasNonNull(ladder, "entry_price")
                    ? field(ladder, "entry_price") : field(candidate, "entry_price");
            String side = text(candidate, "side");
            if (!liquidationPrice.isNull() && !liquidationPrice.isMissingNode()
                    && !referenceEntry.isNull() && !referenceEntry.isMissingNode()
                    && side != null && !side.isEmpty()) {
                double liquidation = plainDecimal(liquidationPrice, "position_controls.liquidation_zone.price");
                double entry = plainDecimal(referenceEntry, "position_controls.ladder.entry_price");
                if ("LONG".equals(side) && liquidation >= entry) {
                    errors.add("long liquidation zone must be below its reference entry");
                }
                if ("SHORT".equals(side) && liquidation <= entry) {
                    errors.add("short liquidation zone must be above its reference entry");
                }
            }
            if ("flying_rocket".equals(framework)) {
                JsonNode risk = field(controls, "risk");
                if (hasNonNull(risk, "book_pct")
                        && plainDecimal(field(risk, "book_pct"), "position_controls.risk.book_pct") > 50) {
                    errors.add("Flying Rocket open risk exceeds the 50% short-book cap");
                }
                if (hasNonNull(risk, "asset_pct")
                        && plainDecimal(field(risk, "asset_pct"), "position_controls.risk.asset_pct") > 30) {
                    errors.add("Flying Rocket open risk exceeds the 30% per-asset cap");
                }
            }
        }
        return new SemanticIssues(errors, warnings);
    }

    private static void sourceRefs(List<String> errors, Set<String> known, JsonNode refs, String field) {
        if (refs != null && refs.isArray()) {
            for (JsonNode ref : refs) {
                String id = text(ref);
                if (!known.contains(id)) {
                    errors.add(field + " references unresolved source id " + id);
                }
            }
        }
    }

    private static Map<String, Integer> fkGateThresholds(int active) {
        return Map.of(
                "p1a", (int) Math.ceil(active / 3.0),
                "p1b", (int) Math.ceil(5 * active / 9.0),
                "p2", (int) Math.ceil(2 * active / 3.0),
                "p3", (int) Math.ceil(7 * active / 9.0));
    }

    private static Map<String, Integer> frGateThresholds(int active) {
        return Map.of(
                "p1a", (int) Math.ceil(3 * active / 9.0),
                "p1b", (int) Math.ceil(5 * active / 9.0),
                "p2", (int) Math.ceil(6 * active / 9.0),
                "p3", (int) Math.ceil(8 * active / 9.0));
    }

    private static double roundScore(double raw, String convention) {
        return switch (convention) {
            case "half-up" -> Math.floor(raw + 0.5);
            case "half-down" -> Math.ceil(raw - 0.5);
            default -> throw new IllegalArgumentException("unknown rounding convention \"" + convention
                    + "\" — declare half-up or half-down (FK SKILL §4)");
        };
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String jsonDisplay(JsonNode value) {
        return value != null && value.isNumber() ? jsNumber(value.doubleValue()) : String.valueOf(value);
    }

    private static boolean sameNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
