package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** ECMAScript coercion and rounding rules shared by swing-score collaborators. */
final class SwingJsSupport {

    private SwingJsSupport() {
    }

    static double half(double value) {
        return mathRound(value * 2.0) / 2.0;
    }

    static double mathRound(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) return value;
        double floor = Math.floor(value);
        double rounded = value - floor < 0.5 ? floor : floor + 1.0;
        return rounded == 0.0 && value < 0.0 ? -0.0 : rounded;
    }

    static double clamp(double value, double low, double high) {
        return Math.min(high, Math.max(low, value));
    }

    static boolean finiteNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    static boolean truthyNumber(double value) {
        return value != 0.0 && !Double.isNaN(value);
    }

    static boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return truthyNumber(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    static double number(Object value) {
        if (value == null || value instanceof MissingNode) return Double.NaN;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        if (value instanceof JsonNode node) return number(node);
        if (value instanceof CharSequence text) return parseNumber(text.toString());
        return Double.NaN;
    }

    static double number(JsonNode value) {
        if (value == null || value.isMissingNode()) return Double.NaN;
        if (value.isNull()) return 0.0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1.0 : 0.0;
        if (value.isTextual()) return parseNumber(value.textValue());
        if (value.isArray()) {
            if (value.isEmpty()) return 0.0;
            return value.size() == 1 ? parseNumber(stringValue(value.get(0))) : Double.NaN;
        }
        return Double.NaN;
    }

    static String stringValue(JsonNode value) {
        if (value == null || value.isMissingNode()) return "";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return Boolean.toString(value.booleanValue());
        if (value.isNumber()) return numberText(value.doubleValue());
        if (value.isArray()) {
            List<String> pieces = new ArrayList<>();
            value.forEach(element -> pieces.add(element.isNull() ? "" : stringValue(element)));
            return String.join(",", pieces);
        }
        return "[object Object]";
    }

    static String numberText(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (value == 0.0) return "0";
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            return BigDecimal.valueOf(value).toBigInteger().toString();
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toString()
                .replace("E+", "e+")
                .replace("E-", "e-");
    }

    static JsonNode property(JsonNode object, String name) {
        if (object == null || !object.isObject()) return MissingNode.getInstance();
        JsonNode value = object.get(name);
        return value == null ? MissingNode.getInstance() : value;
    }

    private static double parseNumber(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return 0.0;
        if ("Infinity".equals(text) || "+Infinity".equals(text)) return Double.POSITIVE_INFINITY;
        if ("-Infinity".equals(text)) return Double.NEGATIVE_INFINITY;
        try {
            if (text.matches("0[xX][0-9a-fA-F]+")) {
                return new BigDecimal(new BigInteger(text.substring(2), 16)).doubleValue();
            }
            if (text.matches("0[bB][01]+")) {
                return new BigDecimal(new BigInteger(text.substring(2), 2)).doubleValue();
            }
            if (text.matches("0[oO][0-7]+")) {
                return new BigDecimal(new BigInteger(text.substring(2), 8)).doubleValue();
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }
}
