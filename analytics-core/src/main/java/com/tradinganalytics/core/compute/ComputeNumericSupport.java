package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** JavaScript Number/truthiness and numeric JSON normalization compatibility rules. */
final class ComputeNumericSupport {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ComputeNumericSupport() {
    }

    static double round2(double value) {
        return jsRound(value * 100.0) / 100.0;
    }

    static double jsNumber(JsonNode value) {
        if (value == null || value.isMissingNode()) return Double.NaN;
        if (value.isNull()) return 0.0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1.0 : 0.0;
        if (value.isTextual()) return jsNumber(value.textValue());
        if (value.isArray()) {
            if (value.isEmpty()) return 0.0;
            if (value.size() == 1) {
                JsonNode only = value.get(0);
                return jsNumber(only == null || only.isNull() ? "" : jsString(only));
            }
            return Double.NaN;
        }
        return Double.NaN;
    }

    static double jsNumber(Object value) {
        if (value == null) return Double.NaN;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        if (value instanceof JsonNode node) return jsNumber(node);
        return jsNumber(String.valueOf(value));
    }

    static double jsNumber(String raw) {
        String text = raw == null ? "undefined" : raw.trim();
        if (text.isEmpty()) return 0.0;
        if ("Infinity".equals(text) || "+Infinity".equals(text)) return Double.POSITIVE_INFINITY;
        if ("-Infinity".equals(text)) return Double.NEGATIVE_INFINITY;
        try {
            if (text.matches("0[xX][0-9a-fA-F]+")) return new BigInteger(text.substring(2), 16).doubleValue();
            if (text.matches("0[bB][01]+")) return new BigInteger(text.substring(2), 2).doubleValue();
            if (text.matches("0[oO][0-7]+")) return new BigInteger(text.substring(2), 8).doubleValue();
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    static boolean truthy(Object value) {
        if (value == null || value instanceof MissingNode || value instanceof NullNode) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return numeric != 0.0 && !Double.isNaN(numeric);
        }
        if (value instanceof String text) return !text.isEmpty();
        if (value instanceof JsonNode node) {
            if (node.isMissingNode() || node.isNull()) return false;
            if (node.isBoolean()) return node.booleanValue();
            if (node.isNumber()) return node.doubleValue() != 0.0 && !Double.isNaN(node.doubleValue());
            if (node.isTextual()) return !node.textValue().isEmpty();
            return true;
        }
        return true;
    }

    static JsonNode normalizedNumberNode(double value) {
        if (!Double.isFinite(value)) return NullNode.getInstance();
        if (value == 0.0) return NODES.numberNode(0);
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            return NODES.numberNode(BigDecimal.valueOf(value).toBigIntegerExact());
        }
        return NODES.numberNode(BigDecimal.valueOf(value).stripTrailingZeros());
    }

    static String jsString(JsonNode node) {
        if (node == null || node.isMissingNode()) return "";
        if (node.isNull()) return "null";
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return Boolean.toString(node.booleanValue());
        if (node.isNumber()) return node.asText();
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.isNull() ? "" : jsString(value)));
            return String.join(",", values);
        }
        return "[object Object]";
    }

    static double jsRound(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) return value;
        double floor = Math.floor(value);
        double result = value - floor < 0.5 ? floor : floor + 1.0;
        return result == 0.0 && value < 0.0 ? -0.0 : result;
    }
}
