package com.tradinganalytics.core.swing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.tradinganalytics.core.swing.SwingJsSupport.clamp;
import static com.tradinganalytics.core.swing.SwingJsSupport.finiteNumber;
import static com.tradinganalytics.core.swing.SwingJsSupport.half;
import static com.tradinganalytics.core.swing.SwingJsSupport.mathRound;
import static com.tradinganalytics.core.swing.SwingJsSupport.numberText;

/** Score-leg normalization and the bounded six-leg swing score. */
final class SwingScoring {

    private SwingScoring() {
    }

    static double roundHalf(double value) {
        if (!Double.isFinite(value)) {
            throw new SwingScore.SwingTypeException("swing score requires finite numeric inputs");
        }
        return half(value);
    }

    static Map<String, Double> normalizeLegs(Map<String, ?> legs) {
        Map<String, ?> input = legs == null ? Map.of() : legs;
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        SwingScore.SCORE_MAXES.forEach((name, max) -> {
            Object candidate = input.get(name);
            double value = candidate == null ? 0.0 : requireFinite(candidate, "legs." + name + " must be finite");
            if (value < 0.0 || value > max) {
                throw new SwingScore.SwingRangeException("legs." + name + " must be between 0 and " + max);
            }
            result.put(name, half(value));
        });
        return Collections.unmodifiableMap(result);
    }

    static Map<String, SwingScore.LegComponent> normalizeLegComponents(
            Map<String, SwingScore.LegComponentInput> components) {
        Map<String, SwingScore.LegComponentInput> input = components == null ? Map.of() : components;
        LinkedHashMap<String, SwingScore.LegComponent> result = new LinkedHashMap<>();
        SwingScore.LEG_COMPONENT_MAXES.forEach((name, maxima) -> {
            SwingScore.LegComponentInput component = input.get(name);
            double state = roundHalf(component == null || component.state() == null ? 0.0 : component.state());
            double impulse = roundHalf(component == null || component.impulse() == null ? 0.0 : component.impulse());
            if (state < 0.0 || state > maxima.state()) {
                throw new SwingScore.SwingRangeException(
                        name + ".state must be between 0 and " + numberText(maxima.state()));
            }
            if (impulse < 0.0 || impulse > maxima.impulse()) {
                throw new SwingScore.SwingRangeException(
                        name + ".impulse must be between 0 and " + numberText(maxima.impulse()));
            }
            result.put(name, new SwingScore.LegComponent(
                    state, impulse, roundHalf(state + impulse), maxima.state() + maxima.impulse()));
        });
        return Collections.unmodifiableMap(result);
    }

    static SwingScore.ScoreResult scoreSwing(SwingScore.ScoreInput input) {
        SwingScore.ScoreInput source = input == null
                ? new SwingScore.ScoreInput(Map.of(), null, 0.0, 0.0) : input;
        Map<String, SwingScore.LegComponent> normalizedComponents = source.components() == null
                ? null : normalizeLegComponents(source.components());

        LinkedHashMap<String, Object> mergedLegs = new LinkedHashMap<>();
        if (source.legs() != null) mergedLegs.putAll(source.legs());
        if (normalizedComponents != null) {
            normalizedComponents.forEach((name, value) -> mergedLegs.put(name, value.total()));
        }
        Map<String, Double> normalized = normalizeLegs(mergedLegs);

        Object discretionInput = source.discretion() == null ? 0.0 : source.discretion();
        if (!finiteNumber(discretionInput)) {
            throw new SwingScore.SwingRangeException("discretion must be a half-point in the range -1..1");
        }
        double discretion = ((Number) discretionInput).doubleValue();
        if (discretion < -1.0 || discretion > 1.0
                || Math.abs(discretion * 2.0 - mathRound(discretion * 2.0)) > 1e-9) {
            throw new SwingScore.SwingRangeException("discretion must be a half-point in the range -1..1");
        }

        Object impulseInput = source.impulse() == null ? 0.0 : source.impulse();
        double impulse = requireFinite(impulseInput, "impulse must be finite");
        double sum = normalized.values().stream().mapToDouble(Double::doubleValue).sum();
        double mechanical = clamp(half(sum), 0.0, 20.0);
        double adjusted = clamp(half(mechanical + discretion), 0.0, 20.0);
        return new SwingScore.ScoreResult(
                SwingScore.SWING_SCORE_VERSION, normalized, normalizedComponents,
                half(impulse), half(discretion), mechanical, adjusted,
                half(mechanical + discretion), 20);
    }

    private static double requireFinite(Object value, String message) {
        if (!finiteNumber(value)) throw new SwingScore.SwingTypeException(message);
        return ((Number) value).doubleValue();
    }

}
