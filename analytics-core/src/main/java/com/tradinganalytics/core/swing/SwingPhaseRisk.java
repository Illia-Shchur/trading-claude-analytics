package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.tradinganalytics.core.swing.SwingJsSupport.clamp;
import static com.tradinganalytics.core.swing.SwingJsSupport.finiteNumber;
import static com.tradinganalytics.core.swing.SwingJsSupport.number;
import static com.tradinganalytics.core.swing.SwingJsSupport.property;
import static com.tradinganalytics.core.swing.SwingJsSupport.truthyNumber;

/** Applies phase gates, vetoes, trigger windows, risk budgets, and expectancy rules. */
final class SwingPhaseRisk {

    private static final DateTimeFormatter JS_ISO_MILLIS = new DateTimeFormatterBuilder()
            .appendInstant(3)
            .toFormatter();

    private SwingPhaseRisk() {
    }

    static Map<String, Integer> phaseThresholds(String framework, String channel) {
        if ("fallen_knives".equals(framework)) {
            return immutableCopy(SwingScore.PHASE_THRESHOLDS.fallen_knives());
        }
        return immutableCopy("B".equals(channel)
                ? SwingScore.PHASE_THRESHOLDS.flying_rocket().B()
                : SwingScore.PHASE_THRESHOLDS.flying_rocket().A());
    }

    static Map<String, Integer> phaseCaps(String framework, String channel) {
        Map<String, Integer> source = switch (String.valueOf(framework)) {
            case "fallen_knives" -> SwingScore.PHASE_CAPS_PCT.fallen_knives();
            case "flying_rocket" -> SwingScore.PHASE_CAPS_PCT.flying_rocket();
            default -> null;
        };
        LinkedHashMap<String, Integer> caps = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        if ("flying_rocket".equals(framework) && "B".equals(channel)) {
            caps.remove("3");
        }
        return Collections.unmodifiableMap(caps);
    }

    static SwingScore.ActivePhaseResult activePhase(SwingScore.ActivePhaseInput input) {
        SwingScore.ActivePhaseInput source = input == null
                ? new SwingScore.ActivePhaseInput(null, "A", null, null, null, List.of())
                : input;
        String channel = source.channel() == null ? "A" : source.channel();
        Integer threshold = phaseThresholds(source.framework(), channel).get(source.phase());
        List<?> suppliedVetoes = source.vetoes() == null ? List.of() : source.vetoes();
        List<Object> activeVetoes = suppliedVetoes.stream()
                .filter(Objects::nonNull)
                .filter(SwingPhaseRisk::isActiveVeto)
                .map(value -> (Object) value)
                .toList();

        Double scoreValue = source.score() == null ? null : source.score().mechanical();
        boolean scorePass = scoreValue != null && Double.isFinite(scoreValue)
                && threshold != null && scoreValue >= threshold;
        SwingScore.TriggerWindow trigger = source.trigger();
        boolean triggerPass = trigger != null
                && "VALID".equals(trigger.status())
                && "4h".equals(trigger.timeframe())
                && trigger.completed_bar_required()
                && trigger.completed_bar()
                && Double.isFinite(trigger.window_bars())
                && trigger.window_bars() >= 1.0
                && trigger.window_bars() <= 2.0
                && (trigger.age_bars() == null
                    || number(trigger.age_bars()) <= trigger.window_bars());

        boolean vetoPass = activeVetoes.isEmpty();
        return new SwingScore.ActivePhaseResult(
                source.phase(),
                threshold,
                scoreValue,
                scorePass,
                triggerPass,
                vetoPass,
                scorePass && triggerPass && vetoPass,
                activeVetoes
        );
    }

    static List<SwingScore.Veto> hardVetoes(SwingScore.HardVetoInput input) {
        SwingScore.HardVetoInput source = input == null
                ? new SwingScore.HardVetoInput(null, false, false, false, false, false, false, false)
                : input;
        String coverage = source.coverage() == null ? "COMPLETE" : source.coverage();
        return List.of(
                SwingScore.veto("FLOW_COVERAGE", !"COMPLETE".equals(coverage),
                        "Flow coverage is incomplete or not common across required horizons."),
                SwingScore.veto("OPPOSING_FLOW", source.flowOpposes(),
                        "Two-horizon flow points against the proposed setup."),
                SwingScore.veto("REGIME_MISMATCH", source.regimeMismatch(),
                        "The setup does not match the prevailing macro/technical regime."),
                SwingScore.veto("RISK_BUDGET", source.riskBudgetExhausted(),
                        "Portfolio or asset risk budget is exhausted."),
                SwingScore.veto("NARRATIVE_EXIT", source.narrativeExit(),
                        "A live narrative or position-exit condition is active."),
                SwingScore.veto("CARRY", source.carryVeto(),
                        "Carry cost is outside the permitted edge."),
                SwingScore.veto("FUNDING", source.fundingVeto(),
                        "Funding/carry veto is active for this setup."),
                SwingScore.veto("MACRO_SHOCK", source.macroShock(),
                        "A multi-family macro shock is at the extreme rolling percentile.")
        );
    }

    static SwingScore.TriggerWindow triggerWindow(SwingScore.TriggerInput input) {
        SwingScore.TriggerInput source = input == null
                ? new SwingScore.TriggerInput(null, false, null, null, null, null, null)
                : input;
        String timeframe = source.timeframe() == null ? "4h" : source.timeframe();
        boolean completedBar = !Boolean.FALSE.equals(source.completedBar());
        double barsNumber = source.bars() == null ? 2.0 : number(source.bars());
        double windowBars = clamp(truthyNumber(barsNumber) ? barsNumber : 2.0, 1.0, 2.0);

        String expiresAt = null;
        if (source.createdAt() != null && !source.createdAt().isEmpty()) {
            Instant created = parseJsDate(source.createdAt());
            if (created != null) {
                long addedMillis = (long) (windowBars * 4.0 * 3_600_000.0);
                Instant expiry = created.truncatedTo(ChronoUnit.MILLIS).plusMillis(addedMillis);
                String formatted = JS_ISO_MILLIS.format(expiry);
                expiresAt = formatted.endsWith(".000Z")
                        ? formatted.substring(0, formatted.length() - 5) + "Z"
                        : formatted;
            }
        }

        Object ageBars = source.ageBars();
        double ageNumber = ageBars == null ? Double.NaN : number(ageBars);
        boolean fresh = ageBars == null || Double.isFinite(ageNumber) && ageNumber <= windowBars;
        String status = source.valid() && completedBar && fresh
                ? "VALID"
                : source.valid() && !fresh ? "EXPIRED" : "WAIT";

        return new SwingScore.TriggerWindow(
                status,
                timeframe,
                true,
                completedBar,
                source.level(),
                source.createdAt(),
                expiresAt,
                windowBars,
                ageBars
        );
    }

    static SwingScore.RiskBudgetResult riskBudget(SwingScore.RiskBudgetInput input) {
        SwingScore.RiskBudgetInput source = input == null
                ? new SwingScore.RiskBudgetInput(null, null, null, null, null)
                : input;
        if (!finiteNumber(source.equityUsd())
                || !finiteNumber(source.stopDistancePct())
                || !finiteNumber(source.phaseCapPct())
                || source.equityUsd().doubleValue() <= 0.0
                || source.stopDistancePct().doubleValue() <= 0.0
                || source.phaseCapPct().doubleValue() < 0.0) {
            return new SwingScore.DataLimitedRiskBudget(
                    "DATA_LIMITED",
                    null,
                    "portfolio equity and a valid stop are required"
            );
        }

        double equity = source.equityUsd().doubleValue();
        double stopDistance = source.stopDistancePct().doubleValue();
        double phaseCap = source.phaseCapPct().doubleValue();
        double remainingAssetRisk = source.remainingAssetRiskPct() == null
                ? 3.0 : source.remainingAssetRiskPct().doubleValue();
        double remainingPortfolioRisk = source.remainingPortfolioRiskPct() == null
                ? 1.5 : source.remainingPortfolioRiskPct().doubleValue();
        double stopFraction = stopDistance / 100.0;
        double byPortfolioRisk = equity * (remainingPortfolioRisk / 100.0) / stopFraction;
        double byAssetRisk = equity * (remainingAssetRisk / 100.0) / stopFraction;
        double cap = equity * (phaseCap / 100.0);
        double notional = Math.max(0.0, Math.min(cap, Math.min(byPortfolioRisk, byAssetRisk)));

        return new SwingScore.AvailableRiskBudget(
                "AVAILABLE",
                equity,
                stopDistance,
                phaseCap,
                notional,
                new SwingScore.RiskConstraints(cap, byPortfolioRisk, byAssetRisk)
        );
    }

    static SwingScore.ExpectancyResult expectancyR(SwingScore.ExpectancyInput input) {
        SwingScore.ExpectancyInput source = input == null
                ? new SwingScore.ExpectancyInput(0.0, 0.0, 0.0, 0.0, 0.0)
                : input;
        double winProbability = expectancyValue(source.winProbability());
        double avgWinR = expectancyValue(source.avgWinR());
        double lossProbability = expectancyValue(source.lossProbability());
        double avgLossR = expectancyValue(source.avgLossR());
        double costsR = expectancyValue(source.costsR());
        return new SwingScore.ExpectancyResult(
                winProbability,
                avgWinR,
                lossProbability,
                avgLossR,
                costsR,
                winProbability * avgWinR - lossProbability * avgLossR - costsR
        );
    }

    static SwingScore.SetupSummary setupSummary(SwingScore.SetupSummaryInput input) {
        SwingScore.SetupSummaryInput source = input == null
                ? new SwingScore.SetupSummaryInput(null, null, null, null, null, List.of())
                : input;
        List<SwingScore.Veto> vetoes = source.vetoes() == null ? List.of() : source.vetoes();
        boolean activeVeto = vetoes.stream().filter(Objects::nonNull).anyMatch(SwingScore.Veto::active);
        return new SwingScore.SetupSummary(
                source.framework(),
                source.channel(),
                SwingScore.SWING_HORIZON_DAYS,
                source.score() == null ? null : source.score().adjusted(),
                source.score() == null ? null : source.score().mechanical(),
                source.phase(),
                source.trigger() == null || source.trigger().status() == null
                        || source.trigger().status().isEmpty() ? "WAIT" : source.trigger().status(),
                activeVeto ? "VETO" : "CLEAR",
                source.phase() != null && source.phase().unlocked()
        );
    }

    private static double expectancyValue(Number value) {
        if (!finiteNumber(value)) {
            throw new SwingScore.SwingTypeException("expectancy inputs must be finite");
        }
        return value.doubleValue();
    }

    private static boolean isActiveVeto(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return true;
        }
        if (value instanceof SwingScore.Veto veto) {
            return veto.active();
        }
        if (value instanceof JsonNode node) {
            JsonNode active = property(node, "active");
            return active.isBoolean() && active.booleanValue();
        }
        if (value instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("active"));
        }
        return false;
    }

    private static Instant parseJsDate(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ignored) {
            // Date.parse also accepts an ISO offset and a bare ISO date.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException ignored) {
            // Continue to the date-only form.
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
