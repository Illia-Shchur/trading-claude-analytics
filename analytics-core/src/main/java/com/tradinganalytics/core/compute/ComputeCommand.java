package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore;
import com.tradinganalytics.core.swing.SwingScore.ActivePhaseInput;
import com.tradinganalytics.core.swing.SwingScore.FlowOptions;
import com.tradinganalytics.core.swing.SwingScore.HardVetoInput;
import com.tradinganalytics.core.swing.SwingScore.RiskBudgetInput;
import com.tradinganalytics.core.swing.SwingScore.ScoreInput;
import com.tradinganalytics.core.swing.SwingScore.ScoreResult;
import com.tradinganalytics.core.swing.SwingScore.TriggerInput;
import com.tradinganalytics.core.swing.SwingScore.Veto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-facing Java port of {@code tools/compute.mjs}.
 *
 * <p>This class intentionally is not registered with the shared CLI root yet.
 * It parses the same argv grammar, supports inline and {@code @file} JSON,
 * and returns captured stdout/stderr/exit status for a future picocli adapter.</p>
 */
public final class ComputeCommand {

    private static final String FUNDING_SIGN =
            "POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026)";
    private static final String UNKNOWN_COMMANDS =
            "rsi | thresholds | round | band | ev | stop-coherence | adr | streak | fr-funding | fr-cap | squeeze | sma | drawdown | trend | stall | fr-composite | fr-companion | corr | tier1 | percentile | rvol | vol-surface | basis | positioning | netliq | stablecoin | marketdata | borrow | short-ev (see header of tools/compute.mjs)";

    private final ObjectMapper json;
    private final Path workspaceRoot;
    private final Clock clock;

    public ComputeCommand() {
        this(Path.of("").toAbsolutePath().normalize(), Clock.systemUTC(), new ObjectMapper());
    }

    public ComputeCommand(Path workspaceRoot, Clock clock) {
        this(workspaceRoot, clock, new ObjectMapper());
    }

    public ComputeCommand(Path workspaceRoot, Clock clock, ObjectMapper json) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.json = json;
    }

    public Result execute(String... argv) {
        try {
            Parsed parsed = parse(argv == null ? new String[0] : argv);
            JsonNode output = dispatch(parsed.command(), parsed.args(), parsed.flags());
            return new Result(0, pretty(output) + "\n", "");
        } catch (CommandFailure failure) {
            return new Result(1, "", "error: " + failure.getMessage() + "\n");
        } catch (ComputeMath.ComputeValidationException | SwingScore.SwingRangeException
                 | SwingScore.SwingTypeException failure) {
            return new Result(1, "", "Error: " + failure.getMessage() + "\n");
        } catch (JsonProcessingException failure) {
            return new Result(1, "", "SyntaxError: " + firstLine(failure.getOriginalMessage()) + "\n");
        } catch (IOException failure) {
            return new Result(1, "", "Error: " + firstLine(failure.getMessage()) + "\n");
        } catch (RuntimeException failure) {
            return new Result(1, "", failure.getClass().getSimpleName() + ": " + firstLine(failure.getMessage()) + "\n");
        }
    }

    public JsonNode compute(String... argv) throws IOException {
        Parsed parsed = parse(argv == null ? new String[0] : argv);
        return dispatch(parsed.command(), parsed.args(), parsed.flags());
    }

    private JsonNode dispatch(String command, List<String> args, Map<String, Object> flags) throws IOException {
        return switch (command == null ? "" : command) {
            case "rsi" -> rsi(args, flags);
            case "thresholds" -> thresholds(args, flags);
            case "round" -> round(args, flags);
            case "band" -> band(args, flags);
            case "ev" -> ev(flags);
            case "stop-coherence" -> ComputeMath.stopCoherence(
                    num(flags.get("catastrophic")), num(flags.get("floor")));
            case "adr" -> adr(flags);
            case "streak" -> streak(flags);
            case "fr-funding" -> funding(flags);
            case "swing-score" -> swingScore(flags);
            case "squeeze" -> squeeze(flags);
            case "fr-cap" -> frCap(flags);
            case "sma" -> sma(flags);
            case "drawdown" -> drawdown(flags);
            case "trend" -> trend(flags);
            case "stall" -> stall(flags);
            case "fr-composite" -> frComposite(flags);
            case "fr-companion" -> frCompanion(flags);
            case "corr" -> correlation(flags);
            case "percentile" -> percentile(flags);
            case "rvol" -> realizedVol(flags);
            case "basis" -> basis(flags);
            case "short-ev" -> shortEv(flags);
            case "borrow" -> borrow(flags);
            case "stablecoin" -> stablecoin(flags);
            case "netliq" -> netLiquidity(flags);
            case "positioning" -> positioning(flags);
            case "vol-surface" -> volSurface(flags);
            case "marketdata" -> marketData(flags);
            case "tier1" -> tierOne(flags);
            default -> throw fail("unknown command \"" + (command == null ? "" : command) + "\" — " + UNKNOWN_COMMANDS);
        };
    }

    private JsonNode rsi(List<String> args, Map<String, Object> flags) {
        if (args.isEmpty() || !ComputeMath.truthy(args.get(0))) {
            throw fail("pass comma-separated closes (oldest → newest)");
        }
        List<Double> closes = nums(args.get(0));
        double rawPeriod = jsNumber(or(flags.get("period"), 14));
        int period = (int) rawPeriod;
        ObjectNode input = object();
        input.put("closes", closes.size());
        putNumber(input, "period", rawPeriod);
        ObjectNode out = object();
        out.set("input", input);
        out.setAll(ComputeMath.wilderRsi(closes, period));
        return out;
    }

    private JsonNode thresholds(List<String> args, Map<String, Object> flags) {
        double value = num(args.isEmpty() ? 9 : args.get(0));
        if (value != Math.rint(value)) {
            throw new ComputeMath.ComputeValidationException("active denominator must be an integer 1–9");
        }
        return ComputeMath.truthy(flags.get("fr"))
                ? ComputeMath.frThresholds((int) value)
                : ComputeMath.ceilThresholds((int) value);
    }

    private JsonNode round(List<String> args, Map<String, Object> flags) {
        double raw = num(args.isEmpty() ? null : args.get(0));
        Object conventionFlag = flags.get("convention");
        String convention = ComputeMath.truthy(conventionFlag)
                ? string(conventionFlag)
                : ComputeMath.ROUNDING.get(string(flags.get("asset")).toLowerCase(Locale.ROOT));
        if (!ComputeMath.truthy(convention)) {
            throw fail("pass --convention half-up|half-down or --asset btc|eth|gold (new assets must declare a convention, FK §4)");
        }
        ObjectNode out = object();
        putNumber(out, "raw", raw);
        out.put("convention", convention);
        out.put("adjusted", ComputeMath.roundScore(raw, convention));
        return out;
    }

    private JsonNode band(List<String> args, Map<String, Object> flags) {
        String kind = args.isEmpty() ? null : args.get(0);
        double value = num(args.size() > 1 ? args.get(1) : null);
        List<String> kinds = List.of(
                "fk-sentiment", "fk-momentum", "fk-mvrv", "fk-drawdown", "fk-gold",
                "fr-euphoria", "fr-momentum", "fr-mvrv", "fr-ath", "fr-distribution", "fr-vulnerability");
        if (!kinds.contains(kind)) {
            throw fail("unknown band kind \"" + kind + "\" — one of " + String.join(", ", kinds));
        }
        ObjectNode out = object();
        out.put("kind", kind);
        putNumber(out, "value", value);
        switch (kind) {
            case "fk-sentiment" -> out.put("band", ComputeMath.fkSentimentBand(value));
            case "fk-momentum" -> out.setAll(ComputeMath.fkMomentumBand(
                    value, ComputeMath.truthy(flags.get("low-confidence"))));
            case "fk-mvrv" -> out.put("band", ComputeMath.fkMvrvBand(value));
            case "fk-drawdown" -> out.put("band", ComputeMath.fkDrawdownBand(value));
            case "fk-gold" -> {
                boolean confirmed = ComputeMath.truthy(flags.get("cot-flush"));
                out.put("band", ComputeMath.fkGoldLowVolBand(value, confirmed));
                out.put("note", confirmed
                        ? "COT flush confirmed → ≥45% band uncapped"
                        : "≥45% band capped at 2 without --cot-flush");
            }
            case "fr-euphoria" -> out.put("band", ComputeMath.frEuphoriaBand(value));
            case "fr-momentum" -> out.put("band", ComputeMath.frMomentumBand(value));
            case "fr-mvrv" -> out.put("band", ComputeMath.frMvrvBand(value));
            case "fr-ath" -> out.put("band", ComputeMath.frAthDistanceBand(value));
            case "fr-distribution" -> out.put("band", ComputeMath.frDistributionBand(value));
            case "fr-vulnerability" -> out.put("band", ComputeMath.frVulnerabilityBand(value));
            default -> throw new IllegalStateException(kind);
        }
        return out;
    }

    private JsonNode ev(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("scenarios"))) throw fail("pass --scenarios <json array>");
        ArrayNode scenarios = array(json(flags.get("scenarios")), "scenarios");
        Double spot = ComputeMath.truthy(flags.get("spot")) ? num(flags.get("spot")) : null;
        if (flags.get("stated") != null) {
            return ComputeMath.evCheck(num(flags.get("stated")), scenarios, spot, 0.5);
        }
        ObjectNode out = ComputeMath.weightedEv(scenarios);
        double recomputed = out.path("ev").doubleValue();
        putNumber(out, "vs_spot_pct", spot != null && ComputeMath.truthy(spot)
                ? ComputeMath.round2((recomputed / spot - 1.0) * 100.0) : null);
        return out;
    }

    private JsonNode adr(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("sessions"))) {
            throw fail("pass --sessions <json array of {date,high,low}> (chronological)");
        }
        int count = (int) jsNumber(or(flags.get("n"), 5));
        List<String> excludes = ComputeMath.truthy(flags.get("exclude"))
                ? Arrays.asList(string(flags.get("exclude")).split(",", -1)) : List.of();
        return ComputeMath.adr(array(json(flags.get("sessions")), "sessions"), count, excludes);
    }

    private JsonNode streak(Map<String, Object> flags) {
        if (!ComputeMath.truthy(flags.get("values")) || flags.get("threshold") == null) {
            throw fail("pass --values v1,v2,... (newest first) --threshold N");
        }
        List<Double> values = nums(flags.get("values"));
        double threshold = num(flags.get("threshold"));
        ObjectNode out = object();
        putNumber(out, "threshold", threshold);
        out.put("streak", ComputeMath.fngStreak(values, threshold));
        out.put("counted", values.size());
        return out;
    }

    private JsonNode funding(Map<String, Object> flags) {
        double perEightHours = num(flags.get("per8h"));
        double annualized = ComputeMath.frAnnualizedFunding(perEightHours);
        ObjectNode out = object();
        putNumber(out, "per8h_pct", perEightHours);
        putNumber(out, "annualized_pct", annualized);
        putNumber(out, "monthly_pct", ComputeMath.round2(annualized / 12.0));
        out.put("sign_convention", FUNDING_SIGN);
        return out;
    }

    private JsonNode swingScore(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("legs"))) {
            throw fail("pass --legs <json> with flow, technical, macro, sentiment, valuation, structure");
        }
        String framework = ComputeMath.truthy(flags.get("framework"))
                ? string(flags.get("framework")) : "fallen_knives";
        String channel = ComputeMath.truthy(flags.get("channel")) ? string(flags.get("channel")) : "A";
        ObjectNode legsNode = object(json(flags.get("legs")), "legs");
        LinkedHashMap<String, Object> legs = new LinkedHashMap<>();
        legsNode.fields().forEachRemaining(entry -> legs.put(entry.getKey(), nodeValue(entry.getValue())));
        double discretion = flags.get("discretion") != null ? num(flags.get("discretion")) : 0.0;
        double impulse = flags.get("impulse") != null ? num(flags.get("impulse")) : 0.0;
        ScoreResult initialScore = SwingScore.scoreSwing(new ScoreInput(legs, null, discretion, impulse));

        boolean hasFlowPanel = ComputeMath.truthy(flags.get("flow"));
        String requestedCoverage = string(or(flags.get("coverage"), hasFlowPanel ? "COMPLETE" : "PARTIAL"))
                .toUpperCase(Locale.ROOT);
        JsonNode flowPanel = hasFlowPanel ? json(flags.get("flow")) : object();
        SwingScore.FlowAssessment flow = SwingScore.assessFlowPanel(
                flowPanel,
                new FlowOptions("fallen_knives".equals(framework) ? 1.0 : -1.0,
                        hasFlowPanel ? requestedCoverage : "PARTIAL"));
        String coverage = flow.eligible_for_entry() ? "COMPLETE" : "PARTIAL";
        LinkedHashMap<String, Double> adjustedLegs = new LinkedHashMap<>(initialScore.legs());
        adjustedLegs.put("flow", flow.score());
        double mechanical = adjustedLegs.values().stream().mapToDouble(Double::doubleValue).sum();
        mechanical = SwingScore.roundHalf(mechanical);
        double raw = SwingScore.roundHalf(mechanical + initialScore.discretion());
        double adjusted = Math.max(0.0, Math.min(20.0, raw));
        ScoreResult score = new ScoreResult(
                initialScore.version(), adjustedLegs, initialScore.leg_components(), initialScore.impulse(),
                initialScore.discretion(), mechanical, adjusted, raw, initialScore.max());
        SwingScore.TriggerWindow trigger = SwingScore.triggerWindow(new TriggerInput(
                null,
                boolFlag(flags.get("trigger-valid")),
                ComputeMath.truthy(flags.get("created-at")) ? string(flags.get("created-at")) : null,
                ComputeMath.truthy(flags.get("level")) ? flags.get("level") : null,
                null, null, null));
        boolean flowOpposes = flow.opposing_rows() > 0 || boolFlag(flags.get("flow-opposes"));
        List<Veto> vetoes = SwingScore.hardVetoes(new HardVetoInput(
                coverage,
                flowOpposes,
                boolFlag(flags.get("regime-mismatch")),
                boolFlag(flags.get("risk-exhausted")),
                false, false, false, false));
        SwingScore.ActivePhaseResult phase = ComputeMath.truthy(flags.get("phase"))
                ? SwingScore.activePhase(new ActivePhaseInput(
                        framework, channel, string(flags.get("phase")), score, trigger, vetoes))
                : null;

        JsonNode risk;
        if (ComputeMath.truthy(flags.get("equity-usd")) && ComputeMath.truthy(flags.get("stop-distance-pct"))) {
            risk = json.valueToTree(SwingScore.riskBudget(new RiskBudgetInput(
                    jsNumber(or(flags.get("phase-cap-pct"), 10)),
                    num(flags.get("equity-usd")),
                    num(flags.get("stop-distance-pct")))));
        } else {
            ObjectNode limited = object();
            limited.put("status", "DATA_LIMITED");
            limited.set("notional_usd", NullNode.getInstance());
            risk = limited;
        }

        ObjectNode out = object();
        out.put("version", "swing-score/1");
        out.put("framework", framework);
        out.put("channel", channel);
        out.put("coverage", coverage);
        out.set("flow_assessment", json.valueToTree(flow));
        out.set("score", json.valueToTree(score));
        out.set("trigger", json.valueToTree(trigger));
        out.set("vetoes", json.valueToTree(vetoes));
        out.set("phase", phase == null ? NullNode.getInstance() : json.valueToTree(phase));
        Map<String, Integer> thresholdValues = SwingScore.phaseThresholds(framework, channel);
        ObjectNode thresholds = object();
        // JSON.stringify emits integer-index keys before ordinary string keys,
        // regardless of insertion order. Preserve that observable Node order.
        if (thresholdValues.containsKey("2")) thresholds.put("2", thresholdValues.get("2"));
        if (thresholdValues.containsKey("3")) thresholds.put("3", thresholdValues.get("3"));
        if (thresholdValues.containsKey("1A")) thresholds.put("1A", thresholdValues.get("1A"));
        if (thresholdValues.containsKey("1B")) thresholds.put("1B", thresholdValues.get("1B"));
        out.set("thresholds", thresholds);
        out.set("risk_budget", risk);
        return out;
    }

    private JsonNode squeeze(Map<String, Object> flags) {
        double annualized = num(flags.get("funding-annualized"));
        boolean sustained = boolFlag(flags.get("sustained3"));
        boolean nearHigh = boolFlag(flags.get("oi-within-5pct"));
        boolean single = boolFlag(flags.get("single-below-7"));
        ObjectNode inputs = object();
        putNumber(inputs, "fundingAnnualizedPct", annualized);
        inputs.put("sustained3Intervals", sustained);
        inputs.put("oiWithin5PctOf90dHigh", nearHigh);
        inputs.put("singleIntervalBelowMinus7", single);
        ObjectNode out = object();
        out.set("inputs", inputs);
        out.setAll(ComputeMath.squeezeTrapPenalty(annualized, sustained, nearHigh, single));
        out.put("note", "base tier: annualized < -5% AND sustained >=3 consecutive intervals → -2 raw + 1 gate surcharge. Escalated (+2 surcharge) if OI is additionally within 5% of its 90-day high, or immediately on a single interval < -7% with that same OI conjunct. In Channel B this penalty darkens gate 8, which VOIDS the unlock regardless of gate count.");
        out.put("sign_convention", FUNDING_SIGN);
        return out;
    }

    private JsonNode frCap(Map<String, Object> flags) {
        double spot = num(flags.get("spot"));
        double ath = num(flags.get("ath1y"));
        double percentBelow = ComputeMath.drawdownPct(spot, ath);
        ObjectNode out = object();
        putNumber(out, "spot", spot);
        putNumber(out, "ath_1y", ath);
        putNumber(out, "pct_below_1y_ath", percentBelow);
        putNumber(out, "cap", ComputeMath.frPhaseCycleCap(percentBelow));
        out.put("note", "cap 8 if >20% below · 14 if 10–20% below (exact 10 → 14, conservative) · none within 10%");
        return out;
    }

    private JsonNode sma(Map<String, Object> flags) {
        double rawN = jsNumber(flags.get("n"));
        List<Double> values = nums(flags.get("values"));
        ObjectNode out = object();
        putNumber(out, "n", rawN);
        putNumber(out, "sma", Double.isFinite(rawN) ? ComputeMath.sma(values, (int) rawN) : null);
        return out;
    }

    private JsonNode drawdown(Map<String, Object> flags) {
        double spot = num(flags.get("spot"));
        double ath = num(flags.get("ath"));
        ObjectNode out = object();
        putNumber(out, "spot", spot);
        putNumber(out, "ath", ath);
        putNumber(out, "drawdown_pct", ComputeMath.drawdownPct(num(flags.get("spot")), num(flags.get("ath"))));
        return out;
    }

    private JsonNode trend(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("sessions"))) {
            throw fail("pass --sessions <json array of {date,high,low,close}> (chronological)");
        }
        return ComputeMath.dailyTrend(
                array(json(flags.get("sessions")), "sessions"),
                flags.get("spot") != null ? num(flags.get("spot")) : null,
                (int) jsNumber(or(flags.get("fast"), 50)),
                (int) jsNumber(or(flags.get("slow"), 200)),
                (int) jsNumber(or(flags.get("slope-n"), 20)),
                (int) jsNumber(or(flags.get("low-n"), 40)));
    }

    private JsonNode stall(Map<String, Object> flags) {
        ObjectNode result = ComputeMath.frStallConfirmation(
                num(flags.get("close")), num(flags.get("prior-close")),
                num(flags.get("high")), num(flags.get("bounce-high")));
        return result == null ? NullNode.getInstance() : result;
    }

    private JsonNode frComposite(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("legs"))) throw fail("pass --legs <json {name:value}>");
        ObjectNode cap = null;
        if (ComputeMath.truthy(flags.get("cap-applied")) || flags.get("cap-value") != null) {
            cap = object();
            cap.put("applied", ComputeMath.truthy(flags.get("cap-applied")));
            putNumber(cap, "value", flags.get("cap-value") != null ? num(flags.get("cap-value")) : null);
        }
        return ComputeMath.frComposite(
                object(json(flags.get("legs")), "legs"),
                flags.get("penalty") != null ? num(flags.get("penalty")) : 0.0,
                flags.get("discretionary") != null ? num(flags.get("discretionary")) : 0.0,
                flags.get("rounding") == null ? null : string(flags.get("rounding")),
                ComputeMath.truthy(flags.get("channel")) ? string(flags.get("channel")) : "A",
                cap);
    }

    private JsonNode frCompanion(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("market"))) throw fail("pass --market <json>");
        ObjectNode counts = ComputeMath.truthy(flags.get("counts"))
                ? object(json(flags.get("counts")), "counts") : object();
        String rounding = ComputeMath.truthy(flags.get("rounding")) ? string(flags.get("rounding")) : "half-up";
        return ComputeMath.frCompanion(object(json(flags.get("market")), "market"), counts, rounding);
    }

    private JsonNode correlation(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("asset")) || !ComputeMath.truthy(flags.get("spx"))) {
            throw fail("pass --asset <json array of {date,close}> --spx <json array of {date,close}>");
        }
        Integer window = flags.get("window") != null ? (int) jsNumber(flags.get("window")) : null;
        return ComputeMath.correlationFromCloses(
                array(json(flags.get("asset")), "asset"),
                array(json(flags.get("spx")), "spx"),
                window);
    }

    private JsonNode percentile(Map<String, Object> flags) {
        if (!ComputeMath.truthy(flags.get("values")) || flags.get("x") == null) {
            throw fail("pass --values v1,v2,... --x N (context only — not a scored input)");
        }
        List<Double> values = nums(flags.get("values"));
        double x = num(flags.get("x"));
        ObjectNode out = object();
        out.put("n", values.size());
        putNumber(out, "x", x);
        putNumber(out, "percentile_rank", ComputeMath.percentileRank(values, x));
        out.set("stats", ComputeMath.distributionStats(values));
        return out;
    }

    private JsonNode realizedVol(Map<String, Object> flags) {
        if (!ComputeMath.truthy(flags.get("closes"))) {
            throw fail("pass --closes c1,c2,... (chronological) [--annualize 365|252]");
        }
        List<Double> closes = nums(flags.get("closes"));
        int annualize = (int) jsNumber(or(flags.get("annualize"), 365));
        ObjectNode block = ComputeMath.realizedVolBlock(closes, annualize);
        List<Double> rolling = ComputeMath.rollingRealizedVol(closes, 30, annualize);
        JsonNode rv30 = block.get("rv30");
        putNumber(block, "rv30_percentile_vs_own_history",
                rolling.isEmpty() || rv30 == null || rv30.isNull()
                        ? null : ComputeMath.percentileRank(rolling, rv30.doubleValue()));
        block.put("n_closes", closes.size());
        block.put("note", "context only — not a scored input or gate");
        return block;
    }

    private JsonNode basis(Map<String, Object> flags) {
        if (flags.get("mark") == null || flags.get("index") == null) {
            throw fail("pass --mark N --index N [--funding-annualized-pct N] [--risk-free-pct N]");
        }
        return ComputeMath.basisBlock(
                num(flags.get("mark")), num(flags.get("index")),
                flags.get("funding-annualized-pct") != null ? num(flags.get("funding-annualized-pct")) : null,
                flags.get("risk-free-pct") != null ? num(flags.get("risk-free-pct")) : null);
    }

    private JsonNode shortEv(Map<String, Object> flags) {
        if (flags.get("directional-ev") == null || flags.get("funding-annualized") == null
                || flags.get("hold-days") == null) {
            throw fail("pass --directional-ev N --funding-annualized N --hold-days N [--target-gain-pct N]");
        }
        return ComputeMath.shortEv(
                num(flags.get("directional-ev")), num(flags.get("funding-annualized")),
                num(flags.get("hold-days")),
                flags.get("target-gain-pct") != null ? num(flags.get("target-gain-pct")) : null);
    }

    private JsonNode borrow(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("ticker"))) {
            throw fail("pass --ticker <@file.json|json array> (raw Bitfinex GET /v2/ticker/f<CCY> shape)");
        }
        return ComputeMath.borrowBlock(json(flags.get("ticker")));
    }

    private JsonNode stablecoin(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("rows"))) {
            throw fail("pass --rows <@file.json|json> (raw DefiLlama stablecoincharts/all array)");
        }
        return ComputeMath.stablecoinBlock(json(flags.get("rows")));
    }

    private JsonNode netLiquidity(Map<String, Object> flags) {
        if (flags.get("walcl") == null || flags.get("rrpontsyd") == null || flags.get("wtregen") == null) {
            throw fail("pass --walcl N (FRED $M) --rrpontsyd N (FRED $B) --wtregen N (FRED $M)");
        }
        return ComputeMath.netLiquidity(
                num(flags.get("walcl")), num(flags.get("rrpontsyd")), num(flags.get("wtregen")));
    }

    private JsonNode positioning(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("long-short"))
                && !ComputeMath.truthy(flags.get("taker"))
                && !ComputeMath.truthy(flags.get("oi"))) {
            throw fail("pass --long-short <json> and/or --taker <json> and/or --oi <json> (raw Binance fapi arrays)");
        }
        ArrayNode longShort = ComputeMath.truthy(flags.get("long-short"))
                ? array(json(flags.get("long-short")), "long-short") : array();
        ArrayNode taker = ComputeMath.truthy(flags.get("taker"))
                ? array(json(flags.get("taker")), "taker") : array();
        ArrayNode openInterest = ComputeMath.truthy(flags.get("oi"))
                ? array(json(flags.get("oi")), "oi") : array();
        return ComputeMath.positioningBlock(longShort, taker, openInterest);
    }

    private JsonNode volSurface(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("book"))) {
            throw fail("pass --book <json array from Deribit get_book_summary_by_currency?kind=option> [--dvol <json candles>] [--rv30 N]");
        }
        return ComputeMath.deribitVolBlock(
                array(json(flags.get("book")), "book"),
                ComputeMath.truthy(flags.get("dvol")) ? array(json(flags.get("dvol")), "dvol") : array(),
                flags.get("rv30") != null ? num(flags.get("rv30")) : null,
                clock.millis());
    }

    private JsonNode marketData(Map<String, Object> flags) throws IOException {
        String asset = string(or(flags.get("asset"), "")).toUpperCase(Locale.ROOT);
        if (asset.isEmpty()) throw fail("pass --asset btc|eth|sol|gold [--max-age-days N]");
        double maxAgeDays = jsNumber(or(flags.get("max-age-days"), 3));
        ObjectNode marketData = object(readDataFile("marketdata.json"), "marketdata");
        ArrayNode entries = array();
        JsonNode sourceEntries = marketData.get("entries");
        if (sourceEntries != null && sourceEntries.isArray()) {
            sourceEntries.forEach(entry -> {
                if (asset.equals(entry.path("asset").asText())) entries.add(entry.deepCopy());
            });
        }
        ArrayNode warnings = array();
        if (entries.isEmpty()) {
            warnings.add("no marketdata.json entries for " + asset
                    + " — every manual metric for this asset is unbacked by a dated entry");
        }
        int stale = 0;
        for (JsonNode entry : entries) {
            if (ageDays(entry.path("verified_on").asText()) > maxAgeDays) stale++;
        }
        if (stale > 0) {
            warnings.add(stale + "/" + entries.size() + " entries have verified_on >" + jsString(maxAgeDays)
                    + " days stale — re-confirm against source before relying on them");
        }
        ObjectNode out = object();
        out.put("asset", asset);
        putNumber(out, "max_age_days", maxAgeDays);
        out.set("entries", entries);
        out.set("warnings", warnings);
        return out;
    }

    private JsonNode tierOne(Map<String, Object> flags) throws IOException {
        if (!ComputeMath.truthy(flags.get("from"))) throw fail("pass --from <date> --sessions N");
        String from = string(flags.get("from"));
        int sessions = (int) jsNumber(or(flags.get("sessions"), 5));
        String assetClass = ComputeMath.truthy(flags.get("asset-class"))
                ? string(flags.get("asset-class")) : "equity";
        ObjectNode calendar = object(readDataFile("calendar-tier1.json"), "calendar");
        List<String> window = ComputeMath.nextNTradingDays(from, sessions, assetClass);
        String windowEnd = window.get(window.size() - 1);
        ArrayNode entries = array(calendar.get("entries"), "entries");
        ArrayNode upcoming = array();
        String lastEntryDate = "";
        int stale = 0;
        for (JsonNode entry : entries) {
            String date = entry.path("date").asText();
            if (date.compareTo(from) > 0 && date.compareTo(windowEnd) <= 0) upcoming.add(entry.deepCopy());
            if (date.compareTo(lastEntryDate) > 0) lastEntryDate = date;
            if (ageDays(entry.path("verified_on").asText()) > 30.0) stale++;
        }
        ArrayNode warnings = array();
        if (stale > 0) warnings.add(stale + "/" + entries.size()
                + " calendar entries have verified_on >30 days stale — re-confirm against source before relying on them");
        if (windowEnd.compareTo(lastEntryDate) > 0) warnings.add("window end " + windowEnd
                + " runs past the last calendar entry (" + lastEntryDate + ") — add more entries to tools/calendar-tier1.json");
        ObjectNode out = object();
        out.put("from", from);
        out.put("sessions", sessions);
        out.put("asset_class", assetClass);
        out.set("window", json.valueToTree(window));
        out.put("window_end", windowEnd);
        out.set("upcoming", upcoming);
        out.set("warnings", warnings);
        return out;
    }

    private Parsed parse(String[] argv) {
        String command = argv.length == 0 ? null : argv[0];
        List<String> args = new ArrayList<>();
        LinkedHashMap<String, Object> flags = new LinkedHashMap<>();
        for (int i = 1; i < argv.length; i++) {
            String token = argv[i];
            if (token.startsWith("--")) {
                String key = token.substring(2);
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    flags.put(key, argv[++i]);
                } else {
                    flags.put(key, true);
                }
            } else {
                args.add(token);
            }
        }
        return new Parsed(command, args, flags);
    }

    private JsonNode json(Object input) throws IOException {
        String text = string(input);
        if (text.startsWith("@")) {
            Path path = workspaceRoot.resolve(text.substring(1)).normalize();
            text = Files.readString(path, StandardCharsets.UTF_8);
        }
        return json.readTree(text);
    }

    private JsonNode readDataFile(String name) throws IOException {
        Path direct = workspaceRoot.resolve("tools").resolve(name).normalize();
        return json.readTree(Files.readString(direct, StandardCharsets.UTF_8));
    }

    private double ageDays(String verifiedOn) {
        Instant verified = LocalDate.parse(verifiedOn).atStartOfDay().toInstant(ZoneOffset.UTC);
        return (clock.millis() - verified.toEpochMilli()) / 86_400_000.0;
    }

    private List<Double> nums(Object value) {
        String[] pieces = string(value).split(",", -1);
        List<Double> out = new ArrayList<>(pieces.length);
        for (String piece : pieces) out.add(num(piece));
        return out;
    }

    private double num(Object value) {
        double number = jsNumber(value);
        if (!Double.isFinite(number)) throw fail("not a number: " + display(value));
        return number;
    }

    private double jsNumber(Object value) {
        if (value == null) return Double.NaN;
        return ComputeMath.jsNumber(value);
    }

    private static boolean boolFlag(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    private static Object or(Object value, Object fallback) {
        return ComputeMath.truthy(value) ? value : fallback;
    }

    private static String string(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean bool) return Boolean.toString(bool);
        return String.valueOf(value);
    }

    private static String display(Object value) {
        return value == null ? "undefined" : string(value);
    }

    private static String jsString(double value) {
        if (value == 0.0) return "0";
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return BigDecimalString.integral(value);
        return BigDecimalString.decimal(value);
    }

    private Object nodeValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isTextual()) return node.textValue();
        return json.convertValue(node, Object.class);
    }

    private String pretty(JsonNode value) throws JsonProcessingException {
        StringBuilder out = new StringBuilder();
        appendPretty(normalizeNumbers(value), out, 0);
        return out.toString();
    }

    private JsonNode normalizeNumbers(JsonNode value) {
        if (value == null || value.isMissingNode()) return NullNode.getInstance();
        if (value.isNumber()) return ComputeMath.normalizedNumberNode(value.doubleValue());
        if (value.isArray()) {
            ArrayNode out = array();
            value.forEach(item -> out.add(normalizeNumbers(item)));
            return out;
        }
        if (value.isObject()) {
            ObjectNode out = object();
            value.fields().forEachRemaining(entry -> out.set(entry.getKey(), normalizeNumbers(entry.getValue())));
            return out;
        }
        return value.deepCopy();
    }

    private void appendPretty(JsonNode value, StringBuilder out, int depth) throws JsonProcessingException {
        if (value.isObject()) {
            if (value.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            for (int i = 0; i < fields.size(); i++) {
                indent(out, depth + 1);
                out.append(json.writeValueAsString(fields.get(i).getKey())).append(": ");
                appendPretty(fields.get(i).getValue(), out, depth + 1);
                if (i + 1 < fields.size()) out.append(',');
                out.append('\n');
            }
            indent(out, depth);
            out.append('}');
        } else if (value.isArray()) {
            if (value.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < value.size(); i++) {
                indent(out, depth + 1);
                appendPretty(value.get(i), out, depth + 1);
                if (i + 1 < value.size()) out.append(',');
                out.append('\n');
            }
            indent(out, depth);
            out.append(']');
        } else {
            out.append(value.toString());
        }
    }

    private static void indent(StringBuilder target, int depth) {
        target.append("  ".repeat(Math.max(0, depth)));
    }

    private static ObjectNode object() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    private static ArrayNode array() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private static ObjectNode object(JsonNode value, String name) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException(name + " must be a JSON object");
        return (ObjectNode) value;
    }

    private static ArrayNode array(JsonNode value, String name) {
        if (value == null || !value.isArray()) throw new IllegalArgumentException(name + " must be a JSON array");
        return (ArrayNode) value;
    }

    private static void putNumber(ObjectNode target, String key, Number value) {
        target.set(key, value == null ? NullNode.getInstance() : ComputeMath.normalizedNumberNode(value.doubleValue()));
    }

    private static CommandFailure fail(String message) {
        return new CommandFailure(message);
    }

    private static String firstLine(String value) {
        if (value == null) return "";
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }

    private record Parsed(String command, List<String> args, Map<String, Object> flags) {
    }

    private static final class CommandFailure extends IllegalArgumentException {
        private CommandFailure(String message) {
            super(message);
        }
    }

    private static final class BigDecimalString {
        private static String integral(double value) {
            return java.math.BigDecimal.valueOf(value).toBigInteger().toString();
        }

        private static String decimal(double value) {
            return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toString()
                    .replace("E+", "e+").replace("E-", "e-");
        }
    }
}
