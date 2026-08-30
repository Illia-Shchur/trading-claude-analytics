package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.core.compute.ComputeMath;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native report-machine/1 compatibility linter retained by {@link LintReportCommand}. */
final class LegacyReportLinter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISCRETION_EPOCH = "2026-07-27";
    private static final String TAG_EPOCH = "2026-08-12";
    private static final List<Integer> FK_V_GATES = List.of(1, 2, 3, 4, 7, 8);
    private static final Map<String, Integer> FK_SCORE_UNLOCK = Map.of("p1a", 8, "p1b", 11, "p2", 15, "p3", 17);
    private static final Map<String, Integer> FR_A_UNLOCK = Map.of("p1a", 11, "p1b", 13, "p2", 15, "p3", 19);
    private static final Map<String, Integer> FR_B_UNLOCK = Map.of("p1a", 13, "p1b", 15, "p2", 17);
    private static final Map<String, Integer> FR_GATE_FLOORS = Map.of("p1a", 3, "p1b", 5, "p2", 6, "p3", 8);
    private static final Map<String, String> FR_B_GATE_BASIS = frGateBasis();
    private static final List<Integer> FR_NONCRYPTO_NA = List.of(4, 6, 9);
    private static final Pattern MACHINE = Pattern.compile("```json machine\\s*\\n([\\s\\S]*?)```");
    private static final Pattern DAY_COUNT = Pattern.compile("^\\s*(\\d{1,3})\\s*(?:calendar\\s*)?d(?:ays?)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL_NEGATIVE = Pattern.compile("\\b(unfilled|dry|frozen|prospective|armed|staged|not filled|no fill)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL_BARE = Pattern.compile("^\\s*~?\\s*\\$?\\s*[\\d,]+(?:\\.\\d+)?\\s*(?:\\(|$)");
    private static final Pattern FILL_MTM = Pattern.compile("\\b(MTM|blended)\\b", Pattern.CASE_INSENSITIVE);

    private LegacyReportLinter() {}

    static ReportingCommandResult lint(Path file, boolean legacy, Path repositoryRoot) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        String text;
        try { text = Files.readString(file, StandardCharsets.UTF_8); }
        catch (Exception exception) { return ReportingCommandResult.failure("", ReportingFiles.message(exception) + "\n"); }
        String name = file.getFileName().toString();
        ObjectNode meta = ToolchainSupport.reportFileMeta(name);
        if (!meta.path("ok").asBoolean()) errors.add("filename \"" + name + "\" does not match asset_framework_YYYYMMDD_HHMM.md");
        Matcher block = MACHINE.matcher(text);
        if (!block.find()) {
            String message = "machine block missing (```json machine ... ``` with schema report-machine/1)";
            if (legacy) warnings.add(message + " — legacy report, skipping arithmetic checks"); else errors.add(message);
            return LintReportCommand.finish(errors, warnings, name, name);
        }
        JsonNode body;
        try { body = JSON.readTree(block.group(1)); }
        catch (Exception exception) {
            errors.add("machine block is not valid JSON: " + nodeJsonError(block.group(1), exception));
            return LintReportCommand.finish(errors, warnings, name, name);
        }
        if (!"report-machine/1".equals(body.path("schema").asText(null))) errors.add("schema " + json(body.get("schema")) + " — expected \"report-machine/1\"");
        String framework = body.path("framework").asText(null);
        if (!("fallen_knives".equals(framework) || "flying_rocket".equals(framework))) errors.add("framework " + json(body.get("framework")) + " invalid");
        if (meta.path("ok").asBoolean()) {
            String blockAsset = body.path("asset").asText("").toUpperCase(Locale.ROOT);
            if (!meta.path("asset").asText().equals(blockAsset)) errors.add("asset mismatch: filename \"" + meta.path("asset").asText() + "\" vs block \"" + body.path("asset").asText("") + "\"");
            if (!meta.path("framework").asText().equals(framework)) errors.add("framework mismatch: filename \"" + meta.path("framework").asText() + "\" vs block \"" + framework + "\"");
            if (!meta.path("date").asText().equals(body.path("date").asText(null))) errors.add("date mismatch: filename " + meta.path("date").asText() + " vs block " + body.path("date").asText(null));
        }
        if (!body.path("spot").path("value").isNumber()) errors.add("spot.value missing");
        if (body.path("spot").isObject() && !ReportingJson.truthy(body.path("spot").get("source"))) warnings.add("spot.source missing — every figure carries source + timestamp (Hard Rule 1)");
        if (!ReportingJson.truthy(body.get("verdict"))) errors.add("verdict missing");

        JsonNode score = body.path("score");
        List<String> legNames = "fallen_knives".equals(framework)
                ? List.of("sentiment", "momentum", "valuation", "capitulation", "holder")
                : List.of("euphoria", "momentum", "valuation", "distribution", "vulnerability");
        Map<String, Integer> legMax = "fallen_knives".equals(framework)
                ? Map.of("sentiment", 5, "momentum", 4, "valuation", 5, "capitulation", 3, "holder", 3)
                : Map.of("euphoria", 5, "momentum", 4, "valuation", 5, "distribution", 3, "vulnerability", 3);
        if (!score.path("legs").isObject()) errors.add("score.legs missing");
        else {
            for (String leg : legNames) {
                JsonNode value = score.path("legs").get(leg);
                if (value == null || !value.isNumber()) { errors.add("score.legs." + leg + " missing"); continue; }
                int minimum = "fallen_knives".equals(framework) && "valuation".equals(leg) ? -2 : 0;
                if (value.doubleValue() < minimum || value.doubleValue() > legMax.get(leg)) errors.add("score.legs." + leg + "=" + raw(value) + " outside [" + minimum + ", " + legMax.get(leg) + "]");
            }
            if ("flying_rocket".equals(framework) && score.has("penalty") && !score.get("penalty").isNull()) {
                JsonNode penalty = score.get("penalty");
                if (!penalty.isNumber() || !Double.isFinite(penalty.doubleValue())) errors.add("score.penalty=" + json(penalty) + " must be a number");
                else if (penalty.doubleValue() > 0) errors.add("score.penalty=" + raw(penalty) + " is positive — the penalty term only ever subtracts (squeeze-trap −2, bounce-maturity −2)");
                else if (penalty.doubleValue() < -4) errors.add("score.penalty=" + raw(penalty) + " is below the −4 floor (squeeze-trap −2 + bounce-maturity −2 is the deepest defined stack)");
            }
            double sum = legSum(score.path("legs"), legNames) + ("flying_rocket".equals(framework) ? orZero(score.get("penalty")) : 0);
            String addend = "flying_rocket".equals(framework) ? "+penalty" : "";
            boolean isFk = "fallen_knives".equals(framework);
            Discretion discretion = discretionValid(score.get("discretionary"));
            String layer = isFk ? "D1" : "S1";
            String discretionMessage = "score.discretionary " + discretion.reason() + " — required field, bounded ±2 on a 0.5 step (" + layer + ")";
            if (!discretion.ok()) addEpoch(body, DISCRETION_EPOCH, errors, warnings, discretionMessage);
            else { sum += score.path("discretionary").doubleValue(); addend = addend.isEmpty() ? "+discretionary" : addend + "+discretionary"; }
            double penaltyValue = isFk ? 0 : orZero(score.get("penalty"));
            double mechanicalLegSum = legSum(score.path("legs"), legNames) + penaltyValue;
            String convention = ReportingJson.truthy(score.get("rounding")) ? score.path("rounding").asText() : ComputeMath.ROUNDING.get(body.path("asset").asText("").toLowerCase(Locale.ROOT));
            if (score.path("mechanical").isNumber() && convention != null) {
                ObjectNode cap = !isFk && score.path("cap").isObject() ? (ObjectNode) score.path("cap") : null;
                ObjectNode composite = ComputeMath.frComposite((ObjectNode) score.path("legs"), penaltyValue, 0, convention, "A", cap);
                double expected = composite.path("mechanical").doubleValue();
                if (score.path("mechanical").doubleValue() != expected) errors.add("score.mechanical=" + raw(score.get("mechanical")) + " but " + convention
                        + "(leg sum" + (isFk ? "" : "+penalty") + " " + num(mechanicalLegSum) + ")"
                        + (!isFk && score.path("cap").path("applied").asBoolean() ? " capped at " + raw(score.path("cap").get("value")) : "") + " = " + num(expected));
            } else if (date(body).compareTo(DISCRETION_EPOCH) >= 0) warnings.add(isFk
                    ? "score.mechanical not declared — the compound stop, Override arming, §7 trims, the EV-floor check and the collar all read it (D1 governing rule)"
                    : "score.mechanical not declared — every §7 cover trigger, the FK≥12 force-cover, the preflight veto, the carry veto, the minimum-edge filter, the collar and Phase 3 all read it (S1 governing rule)");
            if (!score.path("raw").isNumber()) errors.add("score.raw missing");
            else if (Math.abs(sum - score.path("raw").doubleValue()) > 0.01) errors.add("score.raw=" + raw(score.get("raw")) + " but legs" + addend + " sum to " + num(sum));
        }
        if (!score.path("adjusted").isNumber()) errors.add("score.adjusted missing");
        else if (score.path("raw").isNumber()) {
            String asset = body.path("asset").asText("").toLowerCase(Locale.ROOT);
            String convention = ReportingJson.truthy(score.get("rounding")) ? score.path("rounding").asText() : ComputeMath.ROUNDING.get(asset);
            if (convention == null) addEpoch(body, DISCRETION_EPOCH, errors, warnings, "score.rounding not declared and asset has no pinned convention — declare one, or the entire score arithmetic goes unchecked (§4)");
            else {
                String pinned = ComputeMath.ROUNDING.get(asset);
                if (pinned != null && ReportingJson.truthy(score.get("rounding")) && !pinned.equals(score.path("rounding").asText()))
                    addEpoch(body, ToolchainSupport.NONCRYPTO_SCHEMA_EPOCH, errors, warnings, "score.rounding=\"" + score.path("rounding").asText()
                            + "\" conflicts with the pinned convention for " + body.path("asset").asText() + " (\"" + pinned + "\") — a pinned rounding convention may not be overridden per report (§4)");
                double expected = Math.max(0, Math.min(20, ComputeMath.roundScore(score.path("raw").doubleValue(), convention)));
                if ("flying_rocket".equals(framework) && score.path("cap").path("applied").asBoolean()) expected = Math.min(expected, score.path("cap").path("value").doubleValue());
                if (score.path("adjusted").doubleValue() != expected) errors.add("score.adjusted=" + raw(score.get("adjusted")) + " but " + convention + "(" + raw(score.get("raw")) + ")"
                        + (score.path("cap").path("applied").asBoolean() ? " capped at " + raw(score.path("cap").get("value")) : "") + " = " + num(expected));
            }
        }

        JsonNode gates = body.path("gates");
        if (!gates.path("active").isNumber() || !gates.path("passed").isArray()) errors.add("gates.active / gates.passed missing");
        else {
            ArrayNode na = gates.path("na").isArray() ? (ArrayNode) gates.path("na") : ReportingJson.NODES.arrayNode();
            ArrayNode passed = (ArrayNode) gates.path("passed");
            if (gates.path("active").intValue() != 9 - na.size()) errors.add("gates.active=" + raw(gates.get("active")) + " but 9 − " + na.size() + " N/A = " + (9 - na.size()));
            List<String> bad = new ArrayList<>(); for (JsonNode value : passed) if (!value.isIntegralNumber() || value.intValue() < 1 || value.intValue() > 9) bad.add(raw(value));
            if (!bad.isEmpty()) errors.add("gates.passed contains invalid gate numbers: " + String.join(", ", bad));
            List<String> overlap = new ArrayList<>(); for (JsonNode value : passed) if (containsNumber(na, value.intValue())) overlap.add(raw(value));
            if (!overlap.isEmpty()) errors.add("gates " + String.join(", ", overlap) + " are both passed and N/A");
            if (passed.size() > gates.path("active").intValue()) errors.add(passed.size() + " gates passed > " + gates.path("active").intValue() + " active");
            if ("flying_rocket".equals(framework) && ReportPhaseRegistry.frNonCryptoClass(body.path("asset").asText()) != null) {
                List<Integer> got = ints(na); got.sort(Integer::compareTo);
                if (!got.equals(FR_NONCRYPTO_NA)) addEpoch(body, ToolchainSupport.NONCRYPTO_SCHEMA_EPOCH, errors, warnings,
                        "gates.na=[" + joinInts(got) + "] but the frozen " + ReportPhaseRegistry.frNonCryptoClass(body.path("asset").asText())
                                + " schema is [4, 6, 9] (active 6) — the non-crypto N/A set is fixed per asset class and may only change via a disclosed schema-revision note in the SKILL, never per report (FR annex)");
            }
            if ("flying_rocket".equals(framework) && "B".equals(body.path("channel").asText())) {
                JsonNode measurement = gates.path("measurement"); boolean post = date(body).compareTo(ToolchainSupport.GATE_MEASUREMENT_EPOCH) >= 0;
                for (Map.Entry<String, String> field : FR_B_GATE_BASIS.entrySet()) {
                    JsonNode got = measurement.get(field.getKey());
                    if (got == null) add(post, errors, warnings, "gates.measurement." + field.getKey() + " missing — Channel B gate " + field.getKey()
                            + " has more than one defensible reading and the report must cite the declared one (\"" + field.getValue() + "\") (FR §4, " + ToolchainSupport.GATE_MEASUREMENT_EPOCH + ")");
                    else if (!field.getValue().equals(got.asText())) add(post, errors, warnings, "gates.measurement." + field.getKey() + "=" + json(got)
                            + " but the declared basis is \"" + field.getValue() + "\" — a report may not re-measure a gate on its own convention; this is fixed by the SKILL and moves only via a disclosed revision (FR §4)");
                }
                if (gates.has("alt_reading")) {
                    JsonNode alt = gates.get("alt_reading");
                    if (!alt.isObject() || !alt.path("passed").isArray() || !alt.path("gate").isNumber()) add(post, errors, warnings, "gates.alt_reading must be {gate:<n>, basis:\"…\", passed:[…]} — the disclosed alternative needs its own gate list so the count under each reading is checkable (FR §4)");
                    else {
                        String gate = raw(alt.get("gate"));
                        if (!FR_B_GATE_BASIS.containsKey(gate)) add(post, errors, warnings, "gates.alt_reading.gate=" + gate + " is not one of the ambiguous Channel B gates {1, 2, 5} (FR §4)");
                        if (FR_B_GATE_BASIS.get(gate) != null && FR_B_GATE_BASIS.get(gate).equals(alt.path("basis").asText())) add(post, errors, warnings, "gates.alt_reading.basis=\"" + alt.path("basis").asText() + "\" is the DECLARED basis, not an alternative — alt_reading exists to disclose the reading that did NOT govern (FR §4)");
                        boolean sameCount = alt.path("passed").size() == passed.size(), sameSet = sameCount;
                        if (sameSet) for (JsonNode value : alt.path("passed")) if (!containsNumber(passed, value.intValue())) sameSet = false;
                        if (sameSet) add(post, errors, warnings, "gates.alt_reading is identical to the governing board — rule 4 asks for it only when the two readings DISAGREE on a verdict; drop it (FR §4)");
                    }
                }
            }
            if ("fallen_knives".equals(framework)) {
                ObjectNode thresholds;
                try { thresholds = ComputeMath.ceilThresholds(gates.path("active").intValue()); }
                catch (Exception exception) { thresholds = ReportingJson.NODES.objectNode(); }
                int vPassed = 0; for (JsonNode value : passed) if (FK_V_GATES.contains(value.intValue())) vPassed++;
                if (gates.path("v_passed").isNumber() && gates.path("v_passed").intValue() != vPassed) errors.add("gates.v_passed=" + raw(gates.get("v_passed")) + " but passed ∩ {1,2,3,4,7,8} = " + vPassed);
                if (gates.path("thresholds").isObject()) for (Map.Entry<String, JsonNode> field : ReportingJson.entries(gates.path("thresholds")))
                    if (thresholds.has(field.getKey()) && thresholds.path(field.getKey()).doubleValue() != field.getValue().doubleValue()) errors.add("gates.thresholds." + field.getKey() + "=" + raw(field.getValue()) + " but ceil arithmetic gives " + raw(thresholds.get(field.getKey())) + " on /" + raw(gates.get("active")));
            }
        }

        JsonNode ev = body.path("ev");
        if (!ev.path("scenarios").isArray() || ev.path("scenarios").isEmpty()) errors.add("ev.scenarios missing");
        else if (!ev.path("stated_ev").isNumber()) errors.add("ev.stated_ev missing");
        else {
            ObjectNode check = ComputeMath.evCheck(ev.path("stated_ev").doubleValue(), (ArrayNode) ev.path("scenarios"),
                    body.path("spot").path("value").isNumber() ? body.path("spot").path("value").doubleValue() : null, 0.5);
            if (!check.path("prob_sum_ok").asBoolean()) errors.add("scenario probabilities sum to " + raw(check.get("prob_sum")) + ", not 100 (±0.5)");
            if (!check.path("within_tolerance").asBoolean()) errors.add("stated EV " + raw(ev.get("stated_ev")) + " differs from recomputed " + raw(check.get("recomputed_ev")) + " by " + raw(check.get("rel_diff_pct")) + "% (> 0.5% of recomputed — FK §5 sum-check)");
            if ("fallen_knives".equals(framework) && !check.path("rally_cap_ok").asBoolean()) errors.add("Rally probability > 50% — violates the post-adjustment Rally ≤50% cap (FK §5)");
            if (ev.path("vs_spot_pct").isNumber() && !check.path("vs_spot_pct").isNull() && Math.abs(ev.path("vs_spot_pct").doubleValue() - check.path("vs_spot_pct").doubleValue()) > 0.3)
                warnings.add("ev.vs_spot_pct=" + raw(ev.get("vs_spot_pct")) + " vs recomputed " + raw(check.get("vs_spot_pct")) + " (>0.3pp apart)");
        }

        JsonNode deployment = body.path("deployment");
        if (deployment.path("deployed_pct").isNumber() && deployment.path("dry_pct").isNumber()
                && Math.abs(deployment.path("deployed_pct").doubleValue() + deployment.path("dry_pct").doubleValue() - 100) > 0.01)
            errors.add("deployed_pct + dry_pct = " + num(deployment.path("deployed_pct").doubleValue() + deployment.path("dry_pct").doubleValue()) + ", not 100");
        ArrayNode tranches = deployment.path("tranches").isArray() ? (ArrayNode) deployment.path("tranches") : ReportingJson.NODES.arrayNode();

        boolean postTags = date(body).compareTo(TAG_EPOCH) >= 0; JsonNode tagging = body.path("tagging");
        if (!"phase_registry".equals(tagging.path("mode").asText())) add(postTags, errors, warnings, "tagging.mode must be \"phase_registry\" — every report carries an immutable report-phase registry (report-machine/1, " + TAG_EPOCH + ")");
        String instrumentClass = tagging.path("instrument_class").asText(null);
        if (instrumentClass == null || !ReportPhaseRegistry.INSTRUMENT_CLASSES.contains(instrumentClass)) add(postTags, errors, warnings, "tagging.instrument_class must be \"crypto\", \"non_crypto_derivative\" or \"non_crypto_cash\"");
        String declaredChannel = body.path("channel").asText(null);
        String registryChannel = "fallen_knives".equals(framework) ? null
                : ("A".equals(declaredChannel) || "B".equals(declaredChannel) || "none".equals(declaredChannel)) ? declaredChannel : null;
        if (meta.path("ok").asBoolean() && tagging.path("registry").isObject()) errors.addAll(ReportPhaseRegistry.issues(tagging.get("registry"), meta, framework, registryChannel));
        else if (postTags) errors.add("tagging.registry is required and must contain exact report-specific canonical tags and decisions");
        else warnings.add("tagging.registry is absent on a pre-epoch report — legacy report; no registry arithmetic is applied");
        for (String key : List.of("active_tags", "reserved_tags")) if (tagging.has(key) && !tagging.get(key).isArray()) errors.add("tagging." + key + " must be an array when present");
        if (tagging.path("reserved_tags").isArray() && tagging.path("registry").isObject()) {
            ArrayNode wanted = ReportingJson.NODES.arrayNode(); tagging.path("registry").path("entries").forEach(entry -> wanted.add(entry.path("canonical_tag").asText()));
            if (!tagging.path("reserved_tags").equals(wanted)) errors.add("tagging.reserved_tags must mirror registry entry order (compatibility alias only)");
        }
        if (tagging.path("active_tags").isArray()) for (JsonNode tag : tagging.path("active_tags")) if (!tag.isTextual() || tag.textValue().length() > 64) errors.add("tagging.active_tags contains an invalid tag " + json(tag));

        boolean postEntry = date(body).compareTo(ToolchainSupport.ENTRY_PRICE_EPOCH) >= 0;
        for (JsonNode tranche : tranches) {
            if (fillPrice(tranche) != null) continue;
            FillLook look = entryLooksLikeFill(tranche.get("entry"));
            if (look.fillLike()) add(postEntry, errors, warnings, "tranche " + raw(tranche.get("phase")) + ": entry " + json(tranche.get("entry")) + " reads as a FILL (" + look.reason()
                    + ") but no numeric entry_price — the score unlock line, gate floor, stop band, size cap and ratchet are all skipped without it (report-machine/1, " + ToolchainSupport.ENTRY_PRICE_EPOCH + ")");
            else if (tranche.path("deployed").asBoolean(false)) add(postEntry, errors, warnings, "tranche " + raw(tranche.get("phase")) + ": deployed:true but no numeric entry_price — the stop-distance bounds (D5 / S5 / frStopBand) cannot be checked against a fill that has no price");
        }

        if ("fallen_knives".equals(framework)) lintFk(body, score, deployment, tranches, errors, warnings);
        else lintFr(body, score, tranches, errors, warnings);
        lintTail(body, framework, errors, warnings, repositoryRoot);
        return LintReportCommand.finish(errors, warnings, name, name);
    }

    private static void lintFk(JsonNode body, JsonNode score, JsonNode deployment, ArrayNode tranches, List<String> errors, List<String> warnings) {
        for (JsonNode tranche : tranches) if (tranche.has("pct") && !tranche.get("pct").isNull() && !List.of(10d, 15d, 30d, 45d).contains(tranche.path("pct").doubleValue()))
            warnings.add("tranche " + raw(tranche.get("phase")) + " size " + raw(tranche.get("pct")) + "% not a pyramid split (10/15/30/45) — partial deployment is allowed DOWN only, state it");
        double discretionaryPct = 0;
        for (JsonNode tranche : tranches) {
            boolean nonMechanical = tranche.path("discretionary").asBoolean(false) || "override".equals(tranche.path("channel").asText());
            if (!nonMechanical) continue;
            if ("override".equals(tranche.path("channel").asText()) && !tranche.path("discretionary").asBoolean(false)) warnings.add("tranche " + raw(tranche.get("phase")) + " has channel \"override\" but discretionary:" + raw(tranche.get("discretionary")) + " — Override fills are written discretionary:true (they count toward the 40%/25% caps); counted anyway");
            discretionaryPct += orZero(tranche.get("pct"));
            if ("override".equals(tranche.path("channel").asText())) continue;
            if ("3".equals(phaseKey(tranche.get("phase")))) errors.add("tranche " + raw(tranche.get("phase")) + " flagged discretionary — no analyst channel reaches Phase 3 (D1/D2)");
            Double fill = fillPrice(tranche);
            if (!tranche.path("stop").isNumber()) errors.add("tranche " + raw(tranche.get("phase")) + " is an analyst-channel fill but carries no D5 hard stop — every D1/D2 tranche states a price-only stop at fill");
            else if (fill != null) {
                StopCheck check = d5StopCheck(fill, tranche.path("stop").doubleValue());
                if (!check.pass()) errors.add("tranche " + raw(tranche.get("phase")) + " D5 stop " + raw(tranche.get("stop")) + " vs fill " + num(fill) + ": " + check.reason() + " (deepest permitted " + num(check.bound()) + ")");
            } else warnings.add("tranche " + raw(tranche.get("phase")) + " is discretionary with a stop but no numeric entry_price — D5 15%-of-fill bound not checkable");
            if (ReportingJson.truthy(tranche.get("channel")) && !List.of("D1", "D2", "override").contains(tranche.path("channel").asText())) warnings.add("tranche " + raw(tranche.get("phase")) + " channel \"" + tranche.path("channel").asText() + "\" — expected D1, D2, or override");
        }
        boolean released = deployment.path("throttle_released").asBoolean(false);
        double overridePct = 0; for (JsonNode tranche : tranches) if ("override".equals(tranche.path("channel").asText())) overridePct += orZero(tranche.get("pct"));
        if (!released) {
            if (discretionaryPct > 40) errors.add("non-mechanical capital " + num(discretionaryPct) + "% (D1 + D2 + Override) exceeds the 40% book cap — set deployment.throttle_released:true only when a [T] gate has relit or a confirmed higher-low printed (D5)");
            if (overridePct > 25) errors.add("Deep-Value Override capital " + num(overridePct) + "% exceeds its own 25% sub-cap, counted inside the 40% (§6)");
        } else if (discretionaryPct > 40 || overridePct > 25) warnings.add("caps released (throttle_released:true) with " + num(discretionaryPct) + "% non-mechanical / " + num(overridePct) + "% override — state the relit [T] gate or the confirmed higher-low in the report");
        if (score.path("adjusted").isNumber()) for (JsonNode tranche : tranches) {
            if (!trancheFilled(tranche)) continue; String key = phaseKey(tranche.get("phase")); Integer line = key == null ? null : FK_SCORE_UNLOCK.get("p" + key); if (line == null) continue;
            boolean usesMechanical = "3".equals(key); double read = usesMechanical && score.path("mechanical").isNumber() ? score.path("mechanical").doubleValue() : score.path("adjusted").doubleValue();
            if (usesMechanical && !score.path("mechanical").isNumber()) warnings.add("tranche " + raw(tranche.get("phase")) + " checked against adjusted " + raw(score.get("adjusted")) + " — declare score.mechanical so the Phase-3 leg-sum-only line is actually enforced (§6)");
            if (read < line) errors.add("tranche " + raw(tranche.get("phase")) + " deployed at " + (usesMechanical ? "mechanical" : "adjusted") + " score " + num(read) + ", below its ≥" + line + " unlock line (§6)");
        }
        JsonNode stops = body.get("stops");
        if ((body.path("deployment").path("deployed_pct").asDouble(0) > 0 || !tranches.isEmpty()) && !ReportingJson.truthy(stops)) errors.add("position/zone active but stops block missing");
        if (ReportingJson.truthy(stops)) {
            if (!stops.path("catastrophic").isNumber()) errors.add("stops.catastrophic missing");
            if (stops.path("deepest_zone_floor").isNumber() && stops.path("catastrophic").isNumber()) {
                if (!ComputeMath.stopCoherence(stops.path("catastrophic").doubleValue(), stops.path("deepest_zone_floor").doubleValue()).path("pass").asBoolean())
                    errors.add("coherence FAIL: catastrophic stop " + raw(stops.get("catastrophic")) + " not strictly below deepest zone floor " + raw(stops.get("deepest_zone_floor")));
            } else warnings.add("stops.deepest_zone_floor missing — coherence boolean not checkable");
            if (!stops.path("compound").path("price").isNumber() || !stops.path("compound").path("score_line").isNumber()) warnings.add("stops.compound {price, score_line} incomplete — the compound thesis stop needs both (score line default 12, per-asset calibrated)");
        }
    }

    private static void lintFr(JsonNode body, JsonNode score, ArrayNode tranches, List<String> errors, List<String> warnings) {
        boolean post = date(body).compareTo(DISCRETION_EPOCH) >= 0;
        List<Double> sizes = List.of(5d, 10d, 15d, 20d, 2.5d);
        double total = 0;
        for (JsonNode tranche : tranches) {
            total += orZero(tranche.get("pct"));
            if (tranche.has("pct") && !tranche.get("pct").isNull() && !sizes.contains(tranche.path("pct").doubleValue())) warnings.add("FR tranche " + raw(tranche.get("phase")) + " size " + raw(tranche.get("pct")) + "% not in 5/10/15/20 (or 2.5 for an S2 half-size probe)");
            if (!tranche.path("stop").isNumber()) errors.add("FR tranche " + raw(tranche.get("phase")) + ": price stop missing (mandatory)");
            if (!ReportingJson.truthy(tranche.get("time_stop"))) errors.add("FR tranche " + raw(tranche.get("phase")) + ": time stop missing (mandatory)");
            else if (post && daysOf(tranche.get("time_stop")) == null) errors.add("FR tranche " + raw(tranche.get("phase")) + ": time_stop " + json(tranche.get("time_stop")) + " is not a day count — write \"21 days\" or 21, so the clock limits are checkable");
            if (post && phaseKey(tranche.get("phase")) == null) errors.add("FR tranche " + raw(tranche.get("phase")) + ": phase label does not resolve to 1A/1B/2/3 — an unresolvable label skips the Phase-3 bar and every clock limit");
        }
        if (total > 50) errors.add("FR tranches total " + num(total) + "% > 50% short-book cap");
        String channel = body.path("channel").asText(null);
        if (!("A".equals(channel) || "B".equals(channel) || "none".equals(channel))) add(post, errors, warnings, "channel must be \"A\", \"B\" or \"none\" (got " + json(body.get("channel")) + ") — the score means different things in each (§2.5)");
        else if ("A".equals(channel)) {
            JsonNode regime = body.path("regime"); JsonNode offNode = regime.path("pct_below_1y_ath").isNumber() ? regime.get("pct_below_1y_ath") : body.get("high_1y_pct_below");
            if (post && (offNode == null || !offNode.isNumber())) errors.add("channel \"A\" requires regime.pct_below_1y_ath (or high_1y_pct_below) — without it the phase-of-cycle cap is unverifiable");
            else if (offNode != null && offNode.isNumber() && offNode.doubleValue() > 20) errors.add("channel \"A\" declared at " + raw(offNode) + "% below the 1y ATH — beyond 20% the asset is Channel B (falling 200dma) or stand-down, never A (§2.5)");
            if (post && offNode != null && offNode.isNumber() && offNode.doubleValue() >= 10 && !score.path("cap").path("applied").asBoolean()) errors.add("channel \"A\" at " + raw(offNode) + "% below the 1y ATH must declare score.cap {applied:true, value:14} — the cap tier is not optional (§4)");
        } else if ("B".equals(channel)) {
            JsonNode regime = body.path("regime");
            if (!(regime.path("pct_below_1y_ath").isNumber() && regime.path("pct_below_1y_ath").doubleValue() > 20)) errors.add("channel \"B\" requires regime.pct_below_1y_ath > 20 (got " + json(regime.get("pct_below_1y_ath")) + ") — inside 20% of the ATH the channel is A");
            if (!regime.path("ma200_falling").isBoolean() || !regime.path("ma200_falling").booleanValue()) errors.add("channel \"B\" requires regime.ma200_falling:true — a flat/rising 200dma is the stand-down case, not a bear continuation");
            if (!regime.path("price_below_ma200").isBoolean() || !regime.path("price_below_ma200").booleanValue()) errors.add("channel \"B\" requires regime.price_below_ma200:true — above the 200dma there is no bear structure to continue");
        }
        Integer gatesPassed = body.path("gates").path("passed").isArray() ? body.path("gates").path("passed").size() : null;
        int activeGates = body.path("gates").path("active").isNumber() ? body.path("gates").path("active").intValue() : 9;
        double channelBPct = 0, analystPct = 0, liveTotal = 0;
        for (JsonNode tranche : tranches) {
            boolean live = trancheFilled(tranche); Double fill = fillPrice(tranche); String key = phaseKey(tranche.get("phase"));
            if (post && live && !("A".equals(tranche.path("channel_regime").asText()) || "B".equals(tranche.path("channel_regime").asText()))) errors.add("FR tranche " + raw(tranche.get("phase")) + ": channel_regime must be \"A\" or \"B\" on a live tranche — a tranche keeps the channel it opened under (channel-migration rule)");
            String trancheChannel = "A".equals(tranche.path("channel_regime").asText()) || "B".equals(tranche.path("channel_regime").asText()) ? tranche.path("channel_regime").asText() : channel;
            boolean analyst = tranche.path("discretionary").asBoolean(false) || "S1".equals(tranche.path("channel").asText()) || "S2".equals(tranche.path("channel").asText());
            Double days = daysOf(tranche.get("time_stop"));
            if (tranche.path("discretionary").asBoolean(false) && !("S1".equals(tranche.path("channel").asText()) || "S2".equals(tranche.path("channel").asText()))) errors.add("FR tranche " + raw(tranche.get("phase")) + " is discretionary:true but channel is " + json(tranche.get("channel")) + " — analyst fills are \"S1\" or \"S2\" (S5 encoding rule)");
            if (("S1".equals(tranche.path("channel").asText()) || "S2".equals(tranche.path("channel").asText())) && !tranche.path("discretionary").asBoolean(false)) errors.add("FR tranche " + raw(tranche.get("phase")) + " has channel \"" + tranche.path("channel").asText() + "\" but discretionary:" + raw(tranche.get("discretionary")) + " — analyst fills are written discretionary:true so they count toward the 20% cap (S5 encoding rule)");
            if (live && key != null) {
                liveTotal += orZero(tranche.get("pct")); Map<String, Integer> ladder = "B".equals(trancheChannel) ? FR_B_UNLOCK : FR_A_UNLOCK; Integer line = ladder.get("p" + key);
                if (line == null) errors.add("FR tranche " + raw(tranche.get("phase")) + " is filled in Channel " + trancheChannel + ", which has no Phase " + key.toUpperCase(Locale.ROOT) + " (§4B)");
                else {
                    JsonNode readScore = "3".equals(key) ? score.get("mechanical") : score.get("adjusted"); String which = "3".equals(key) ? "mechanical" : "adjusted";
                    if (readScore != null && readScore.isNumber() && readScore.doubleValue() < line) errors.add("FR tranche " + raw(tranche.get("phase")) + " filled at " + which + " score " + raw(readScore) + " but Channel " + trancheChannel + " Phase " + key.toUpperCase(Locale.ROOT) + " unlocks at ≥" + line + " (§6)");
                }
                Integer floor9 = FR_GATE_FLOORS.get("p" + key);
                if (floor9 != null && gatesPassed != null) {
                    int need = (int) Math.ceil(floor9 / 9d * activeGates);
                    if (!analyst && gatesPassed < need) errors.add("FR tranche " + raw(tranche.get("phase")) + " filled on " + gatesPassed + "/" + activeGates + " gates but Phase " + key.toUpperCase(Locale.ROOT) + " needs ceil(" + floor9 + "/9×" + activeGates + ")=" + need + " (§4) — an S2 fill may be exactly one short, and must be encoded as such");
                    if (analyst && "S2".equals(tranche.path("channel").asText()) && gatesPassed < need - 1) errors.add("FR tranche " + raw(tranche.get("phase")) + " is an S2 fill on " + gatesPassed + "/" + activeGates + " gates — the Conviction Path substitutes for EXACTLY ONE missing gate (needs ≥" + (need - 1) + ") (S2)");
                }
                if ("B".equals(trancheChannel) && body.path("gates").path("passed").isArray() && !containsNumber(body.path("gates").path("passed"), 8)) errors.add("FR tranche " + raw(tranche.get("phase")) + " is a Channel B fill but gate 8 (funding not sustained-negative) is not passed — gate 8 voids a Channel B unlock regardless of count (§4B)");
                if (fill != null && tranche.path("stop").isNumber()) {
                    JsonNode adrNode = body.path("inputs").get("adr5"); Double adr = adrNode != null && adrNode.isNumber() ? adrNode.doubleValue() : null;
                    StopBand band = frStopBand(fill, adr, trancheChannel, key);
                    if (!band.ok()) errors.add("FR tranche " + raw(tranche.get("phase")) + ": " + band.reason());
                    else if (tranche.path("stop").doubleValue() <= fill) errors.add("FR tranche " + raw(tranche.get("phase")) + ": stop " + raw(tranche.get("stop")) + " is at or below the fill " + num(fill) + " — a short's stop sits ABOVE entry");
                    else if (tranche.path("stop").doubleValue() > band.ceiling()) errors.add("FR tranche " + raw(tranche.get("phase")) + ": stop " + raw(tranche.get("stop")) + " is " + num(round2((tranche.path("stop").doubleValue() / fill - 1) * 100)) + "% above fill — Channel " + trancheChannel + " Phase " + key.toUpperCase(Locale.ROOT) + " caps it at " + num(band.ceilingPct()) + "% (" + num(band.ceiling()) + ")");
                    else if (band.floor() != null && tranche.path("stop").doubleValue() < band.floor()) errors.add("FR tranche " + raw(tranche.get("phase")) + ": stop " + raw(tranche.get("stop")) + " sits " + num(round2((tranche.path("stop").doubleValue() / fill - 1) * 100)) + "% above fill, inside the 1.5×ADR(5) noise floor of " + num(band.floorPct()) + "% (" + num(band.floor()) + ") — a stop this tight is a coin flip on noise");
                } else if (tranche.path("stop").isNumber() && post) warnings.add("FR tranche " + raw(tranche.get("phase")) + " has a stop but no numeric entry_price — no stop-distance bound is checkable");
                if (line != null && !"3".equals(key) && score.path("mechanical").isNumber() && score.path("adjusted").isNumber()
                        && score.path("mechanical").doubleValue() < line && score.path("adjusted").doubleValue() >= line && !analyst)
                    errors.add("FR tranche " + raw(tranche.get("phase")) + ": the discretionary term is load-bearing (mechanical " + raw(score.get("mechanical")) + " < " + line + " ≤ adjusted " + raw(score.get("adjusted")) + ") — write discretionary:true with channel \"S1\" so the tranche pays the S5 tax (S5)");
            }
            if ("B".equals(trancheChannel)) {
                channelBPct += orZero(tranche.get("pct"));
                if ("3".equals(key)) errors.add("FR tranche " + raw(tranche.get("phase")) + " is Channel B — Phase 3 is unreachable in Channel B at any score (§6)");
                Integer maxDays = switch (key == null ? "" : key) { case "1a", "1b" -> 21; case "2" -> 28; default -> null; };
                if (maxDays != null && days != null && days > maxDays) errors.add("FR tranche " + raw(tranche.get("phase")) + " Channel B time stop " + num(days) + "d exceeds the " + maxDays + "d limit (§6)");
            }
            if (tranche.path("prior_stop").isNumber() && tranche.path("stop").isNumber()) {
                Ratchet ratchet = frRatchet(tranche.path("prior_stop").doubleValue(), tranche.path("stop").doubleValue(), "stop");
                if (!ratchet.pass()) errors.add("FR tranche " + raw(tranche.get("phase")) + ": " + ratchet.reason());
            }
            if (tranche.has("prior_time_stop") && !tranche.get("prior_time_stop").isNull() && days != null) {
                Double priorDays = daysOf(tranche.get("prior_time_stop"));
                if (priorDays != null) { Ratchet ratchet = frRatchet(priorDays, days, "time_stop"); if (!ratchet.pass()) errors.add("FR tranche " + raw(tranche.get("phase")) + ": " + ratchet.reason()); }
            }
            if (!analyst) continue;
            analystPct += orZero(tranche.get("pct"));
            if ("3".equals(key)) errors.add("FR tranche " + raw(tranche.get("phase")) + " flagged discretionary — no analyst channel reaches Phase 3 (S5)");
            if ("S2".equals(tranche.path("channel").asText()) && !"1a".equals(key)) errors.add("FR tranche " + raw(tranche.get("phase")) + " filled via S2 — the Conviction Path unlocks Phase 1A only (S2)");
            if (days != null && days > 14) errors.add("FR tranche " + raw(tranche.get("phase")) + " is an analyst-channel fill with a " + num(days) + "d clock — S5 caps it at 14d");
            if (live && fill != null && tranche.path("stop").isNumber()) {
                StopCheck check = s5StopCheck(fill, tranche.path("stop").doubleValue());
                if (!check.pass()) errors.add("FR tranche " + raw(tranche.get("phase")) + " S5 stop " + raw(tranche.get("stop")) + " vs fill " + num(fill) + ": " + check.reason() + " (widest permitted " + num(check.bound()) + ")");
            } else if (live && tranche.path("stop").isNumber()) warnings.add("FR tranche " + raw(tranche.get("phase")) + " is an analyst fill with a stop but no numeric entry_price — the S5 6%-of-fill bound is not checkable");
        }
        if (channelBPct > 30) errors.add("FR Channel B tranches total " + num(channelBPct) + "% > the 30% Channel B sub-cap (§4B)");
        if (analystPct > 20) errors.add("FR analyst-channel capital " + num(analystPct) + "% (S1 + S2) exceeds the 20% book cap (S5)");
        if (liveTotal > 30) errors.add("FR live tranches total " + num(liveTotal) + "% on " + body.path("asset").asText() + " > the 30% per-asset concentration cap — the two channels may not stack into one asset (§6)");
        if (body.has("high_1y_pct_below") && !body.get("high_1y_pct_below").isNull() && score.path("cap").isObject() && !"B".equals(channel)) {
            Double off = body.path("high_1y_pct_below").doubleValue(); Integer expected = off > 20 ? 8 : off >= 10 ? 14 : null;
            Integer actual = score.path("cap").path("applied").asBoolean() ? score.path("cap").path("value").intValue() : null;
            if (!java.util.Objects.equals(expected, actual)) errors.add("FR cycle cap: " + raw(body.get("high_1y_pct_below")) + "% below 1y ATH ⇒ cap " + (expected == null ? "null" : expected) + ", block says " + (actual == null ? "none" : actual));
        }
        if ("B".equals(channel) && score.path("cap").path("applied").asBoolean()) errors.add("FR Channel B declares a phase-of-cycle cap — the cap is Channel A only; Channel B is bounded by the 30% sub-cap and the Phase-3 exclusion instead (§4B)");
    }

    private static void lintTail(JsonNode body, String framework, List<String> errors, List<String> warnings, Path repositoryRoot) {
        boolean postCompanion = date(body).compareTo(ToolchainSupport.COMPANION_FR_EPOCH) >= 0;
        JsonNode companion = body.has("companion_fr") ? body.get("companion_fr") : body.path("inputs").get("companion_fr");
        if ("fallen_knives".equals(framework) && !ReportingJson.truthy(companion) && postCompanion) errors.add("companion_fr missing — every fallen_knives report needs the Hard Rule 5 FR companion (report-machine/1, " + ToolchainSupport.COMPANION_FR_EPOCH + ")");
        else if (ReportingJson.truthy(companion)) {
            boolean nested = !body.has("companion_fr") && body.path("inputs").has("companion_fr");
            if (nested) add(postCompanion, errors, warnings, "companion_fr found nested under inputs.companion_fr — write it top-level (report-machine/1 migration)");
            if (!companion.path("score").isNumber() || companion.path("score").doubleValue() < 0 || companion.path("score").doubleValue() > 20) add(postCompanion, errors, warnings, "companion_fr.score=" + json(companion.get("score")) + " must be a number 0-20");
            String rawChannel = companion.path("channel").asText(""); boolean strictChannel = "A".equals(rawChannel) || "B".equals(rawChannel) || "none".equals(rawChannel);
            if (!strictChannel) add(postCompanion, errors, warnings, "companion_fr.channel=" + json(companion.get("channel")) + " is not exactly \"A\"/\"B\"/\"none\" — move descriptive text to companion_fr.channel_note (report-machine/1, " + ToolchainSupport.COMPANION_FR_EPOCH + ")");
            String channel = strictChannel ? rawChannel : rawChannel.startsWith("B") ? "B" : rawChannel.startsWith("none") ? "none" : rawChannel.startsWith("A") ? "A" : null;
            if ("B".equals(channel)) {
                JsonNode regime = ReportingJson.truthy(companion.get("regime")) ? companion.get("regime") : companion.get("routing");
                if (regime == null || !regime.path("pct_below_1y_ath").isNumber() || !regime.path("ma200_falling").asBoolean(false)) add(postCompanion, errors, warnings, "companion_fr channel \"B\" requires a complete regime/routing block (pct_below_1y_ath, ma200_falling:true) proving the bear-continuation regime");
            }
            if (companion.path("standalone_report_owed").isBoolean() && companion.path("score").isNumber() && companion.path("score").doubleValue() >= 9 && !companion.path("standalone_report_owed").booleanValue()) errors.add("companion_fr.score=" + raw(companion.get("score")) + " >= 9 but standalone_report_owed is not true — the tripwire is unconditional at >=9");
            if (!companion.path("cross_validation").isTextual() || companion.path("cross_validation").textValue().isEmpty()) add(postCompanion, errors, warnings, "companion_fr.cross_validation missing — Hard Rule 5 requires the FK/FR inverse-relation check stated on every report");
        }
        if (ReportingJson.truthy(body.get("correlation"))) {
            JsonNode correlation = body.get("correlation"), value = correlation.get("value_30d_vs_spx");
            if (value != null && value.isNumber()) {
                double number = value.doubleValue(); boolean implied = number > 0.7;
                if (correlation.path("surcharge_applied").isBoolean() && correlation.path("surcharge_applied").booleanValue() != implied) errors.add("correlation.surcharge_applied=" + raw(correlation.get("surcharge_applied")) + " but value_30d_vs_spx=" + raw(value) + " implies " + implied + " (surcharge is exactly corr > 0.7)");
                JsonNode condition = correlation.get("phase2_corr_condition"); boolean phasePass = number < 0.8;
                if (condition != null && condition.isBoolean() && condition.booleanValue() != phasePass) errors.add("correlation.phase2_corr_condition=" + raw(condition) + " but value_30d_vs_spx=" + raw(value) + " implies " + phasePass + " (Phase 2 condition is exactly corr < 0.8)");
                else if (condition != null && condition.isTextual()) {
                    String conditionText = condition.textValue().toUpperCase(Locale.ROOT);
                    if (phasePass && conditionText.contains("FAIL")) errors.add("correlation.phase2_corr_condition reads FAIL but value_30d_vs_spx=" + raw(value) + " < 0.8 should PASS");
                    if (!phasePass && conditionText.contains("PASS")) errors.add("correlation.phase2_corr_condition reads PASS but value_30d_vs_spx=" + raw(value) + " >= 0.8 should FAIL");
                }
                if (!ReportingJson.truthy(correlation.get("window"))) warnings.add("correlation.window missing — state the date range a numeric correlation was computed over");
                if (!ReportingJson.truthy(correlation.get("method"))) warnings.add("correlation.method missing — state the method (e.g. Pearson on daily log returns) a numeric correlation was computed with");
                else if (correlation.path("method").isTextual() && Pattern.compile("price[\\s-]?level", Pattern.CASE_INSENSITIVE).matcher(correlation.path("method").asText()).find()
                        && !Pattern.compile("log[\\s-]?return", Pattern.CASE_INSENSITIVE).matcher(correlation.path("method").asText()).find()) warnings.add("correlation.method=\"" + correlation.path("method").asText() + "\" reads as price-level correlation, but tools/lib.mjs correlationFromCloses() computes Pearson on daily log returns — update the wording or recompute");
            }
        }
        List<String> cited = new ArrayList<>(); JsonNode keyInputs = body.path("key_inputs");
        for (String metric : List.of("mvrv_z", "realized_price", "lth_mvrv", "sth_mvrv")) if (keyInputs.has(metric) && !keyInputs.get(metric).isNull()) cited.add(metric);
        if (!cited.isEmpty()) {
            Path marketdata = repositoryRoot.resolve("tools/marketdata.json");
            if (!Files.exists(marketdata)) warnings.add("key_inputs cites " + String.join(", ", cited) + " but tools/marketdata.json does not exist — manual on-chain inputs are unbacked by a dated entry");
            else try {
                JsonNode data = JSON.readTree(Files.readString(marketdata)); String asset = body.path("asset").asText("").toUpperCase(Locale.ROOT); List<String> missing = new ArrayList<>();
                for (String metric : cited) { boolean found = false; for (JsonNode entry : data.path("entries")) if (asset.equals(entry.path("asset").asText()) && metric.equals(entry.path("metric").asText())) found = true; if (!found) missing.add(metric); }
                if (!missing.isEmpty()) warnings.add("key_inputs cites " + String.join(", ", missing) + " for " + asset + " with no backing tools/marketdata.json entry (metric+asset) — add a dated, sourced entry");
            } catch (Exception exception) { throw new IllegalArgumentException(exception); }
        }
        if (ReportingJson.truthy(body.get("trend_residual"))) {
            JsonNode trend = body.get("trend_residual");
            if (trend.has("active_downtrend") && !trend.get("active_downtrend").isNull() && !trend.get("active_downtrend").isBoolean()) errors.add("trend_residual.active_downtrend=" + json(trend.get("active_downtrend")) + " must be a boolean");
            if (trend.path("active_downtrend").asBoolean(false) && !ReportingJson.truthy(trend.get("consequence"))) warnings.add("trend_residual.active_downtrend is true but no consequence is stated — say what changed (e.g. Deep-Value Override throttle)");
        }
    }

    private static Discretion discretionValid(JsonNode value) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) return new Discretion(false, "missing or non-numeric (write 0 when no adjustment was taken)");
        double number = value.doubleValue(); if (Math.abs(number) > 2) return new Discretion(false, "|" + raw(value) + "| exceeds the ±2 bound (D1)");
        if (Math.abs(number / 0.5 - Math.round(number / 0.5)) > 1e-9) return new Discretion(false, raw(value) + " is not on the 0.5 step (D1)");
        return new Discretion(true, null);
    }

    private static Double fillPrice(JsonNode tranche) { if (tranche == null) return null; JsonNode entryPrice = tranche.get("entry_price"), entry = tranche.get("entry"); if (entryPrice != null && entryPrice.isNumber() && Double.isFinite(entryPrice.doubleValue())) return entryPrice.doubleValue(); if (entry != null && entry.isNumber() && Double.isFinite(entry.doubleValue())) return entry.doubleValue(); return null; }
    private static boolean trancheFilled(JsonNode tranche) { return tranche != null && tranche.path("deployed").asBoolean(false) || fillPrice(tranche) != null; }
    private static FillLook entryLooksLikeFill(JsonNode entry) {
        if (entry == null || !entry.isTextual()) return new FillLook(false, "entry is not prose"); String value = entry.textValue(); Matcher negative = FILL_NEGATIVE.matcher(value);
        if (negative.find()) return new FillLook(false, "staged/placeholder language (\"" + negative.group(1) + "\")");
        if (FILL_BARE.matcher(value).find()) return new FillLook(true, "entry opens with a single price, not a range"); Matcher mtm = FILL_MTM.matcher(value);
        if (mtm.find()) return new FillLook(true, "entry says \"" + mtm.group(1) + "\", which only has meaning against a real position"); return new FillLook(false, "no fill signature");
    }
    private static StopCheck d5StopCheck(double fill, double stop) { double floor = round2(fill * .85), distance = round2((1 - stop / fill) * 100); if (stop >= fill) return new StopCheck(false, floor, "stop is at or above the fill"); return new StopCheck(stop >= floor, floor, stop >= floor ? null : "stop sits " + num(distance) + "% below fill — deeper than the 15% D5 limit"); }
    private static StopCheck s5StopCheck(double fill, double stop) { double ceiling = round2(fill * 1.06), distance = round2((stop / fill - 1) * 100); if (stop <= fill) return new StopCheck(false, ceiling, "stop is at or below the fill — a short stop sits ABOVE entry"); return new StopCheck(stop <= ceiling, ceiling, stop <= ceiling ? null : "stop sits " + num(distance) + "% above fill — wider than the 6% S5 limit"); }
    private static StopBand frStopBand(double fill, Double adr, String channel, String phase) {
        Map<String, Double> values = "B".equals(channel) ? Map.of("1a", 6d, "1b", 6d, "2", 8d) : Map.of("1a", 8d, "1b", 10d, "2", 12d, "3", 15d);
        Double ceilingPct = values.get(phase); if (ceilingPct == null) return new StopBand(false, null, null, null, null, "phase " + phase + " is unreachable in Channel " + channel);
        double ceiling = round2(fill * (1 + ceilingPct / 100)); if (adr == null) return new StopBand(true, ceiling, ceilingPct, null, null, "ADR(5) not supplied — minimum-distance rule not checkable");
        double floorPct = round2(1.5 * adr / fill * 100); if (floorPct > ceilingPct) return new StopBand(false, ceiling, ceilingPct, null, floorPct, "1.5×ADR(5) = " + num(floorPct) + "% exceeds the " + num(ceilingPct) + "% phase ceiling — tape too volatile for this phase, no trade");
        return new StopBand(true, ceiling, ceilingPct, round2(fill * (1 + floorPct / 100)), floorPct, null);
    }
    private static Ratchet frRatchet(double oldValue, double newValue, String tier) { return newValue <= oldValue ? new Ratchet(true, null) : new Ratchet(false, "S6 ratchet: " + tier + " " + num(oldValue) + " → " + num(newValue) + " widens the stop — prohibited, not merely disclosable"); }
    private static Double daysOf(JsonNode value) { if (value == null || value.isNull()) return null; if (value.isNumber()) return Double.isFinite(value.doubleValue()) ? value.doubleValue() : null; Matcher matcher = DAY_COUNT.matcher(value.asText()); return matcher.matches() ? Double.valueOf(matcher.group(1)) : null; }
    private static String phaseKey(JsonNode phase) { String value = phase == null || phase.isNull() ? "" : phase.asText().toLowerCase(Locale.ROOT).replace("phase", ""); Matcher matcher = Pattern.compile("1a|1b|2|3").matcher(value); return matcher.find() ? matcher.group() : null; }
    private static double legSum(JsonNode legs, List<String> names) { double sum = 0; for (String name : names) sum += orZero(legs.get(name)); return sum; }
    private static double orZero(JsonNode value) { return ReportingJson.truthy(value) && value.isNumber() ? value.doubleValue() : 0; }
    private static boolean containsNumber(JsonNode array, int number) { if (array != null && array.isArray()) for (JsonNode value : array) if (value.isIntegralNumber() && value.intValue() == number) return true; return false; }
    private static List<Integer> ints(JsonNode array) { List<Integer> values = new ArrayList<>(); if (array != null && array.isArray()) array.forEach(value -> values.add(value.intValue())); return values; }
    private static String joinInts(List<Integer> values) { return String.join(", ", values.stream().map(String::valueOf).toList()); }
    private static String date(JsonNode body) { return body.path("date").asText("undefined"); }
    private static void addEpoch(JsonNode body, String epoch, List<String> errors, List<String> warnings, String message) { add(date(body).compareTo(epoch) >= 0, errors, warnings, message); }
    private static void add(boolean error, List<String> errors, List<String> warnings, String message) { (error ? errors : warnings).add(message); }
    private static double round2(double value) { return Math.round(value * 100) / 100d; }
    private static String num(double value) { return CanonicalJson.canonicalize(value); }
    private static String raw(JsonNode value) { return value == null || value.isMissingNode() ? "undefined" : value.isTextual() ? value.textValue() : value.isNumber() ? CanonicalJson.canonicalize(value) : value.toString(); }
    private static String json(JsonNode value) { return value == null || value.isMissingNode() ? "undefined" : value.toString(); }
    private static String nodeJsonError(String source, Exception exception) {
        if (source != null && source.strip().equals("{")) {
            int position = source.length(), line = 1, column = 1;
            for (int index = 0; index < position; index++) {
                if (source.charAt(index) == '\n') { line++; column = 1; }
                else column++;
            }
            return "Expected property name or '}' in JSON at position " + position
                    + " (line " + line + " column " + column + ")";
        }
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
    }
    private static Map<String, String> frGateBasis() { Map<String, String> values = new LinkedHashMap<>(); values.put("1", "current_session_high"); values.put("2", "low_to_current"); values.put("5", "bounce_window"); return java.util.Collections.unmodifiableMap(values); }

    private record Discretion(boolean ok, String reason) {}
    private record FillLook(boolean fillLike, String reason) {}
    private record StopCheck(boolean pass, double bound, String reason) {}
    private record StopBand(boolean ok, Double ceiling, Double ceilingPct, Double floor, Double floorPct, String reason) {}
    private record Ratchet(boolean pass, String reason) {}
}
