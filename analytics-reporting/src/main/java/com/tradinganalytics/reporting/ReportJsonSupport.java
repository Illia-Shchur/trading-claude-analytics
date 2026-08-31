package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportJsonSupport {
    static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$");
    static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern TIME = Pattern.compile("^(\\d{2}):(\\d{2})$");

    private ReportJsonSupport() {
    }

    static JsonNode field(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        return value == null ? MissingNode.getInstance() : value;
    }

    static ObjectNode object(JsonNode parent, String name) {
        JsonNode value = field(parent, name);
        return value.isObject() ? (ObjectNode) value : new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
    }

    static ArrayNode array(JsonNode parent, String name) {
        JsonNode value = field(parent, name);
        return value.isArray() ? (ArrayNode) value : new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
    }

    static String text(JsonNode parent, String name) {
        JsonNode value = field(parent, name);
        return value.isTextual() ? value.textValue() : null;
    }

    static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    static boolean hasNonNull(JsonNode parent, String name) {
        JsonNode value = field(parent, name);
        return !value.isMissingNode() && !value.isNull();
    }

    static boolean isTrue(JsonNode value) {
        return value != null && value.isBoolean() && value.booleanValue();
    }

    static boolean isFalse(JsonNode value) {
        return value != null && value.isBoolean() && !value.booleanValue();
    }

    static boolean finite(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue());
    }

    static double number(JsonNode parent, String name) {
        return field(parent, name).doubleValue();
    }

    static boolean numericEquals(JsonNode value, double expected) {
        return finite(value) && value.doubleValue() == expected;
    }

    static double plainDecimal(JsonNode value, String field) {
        if (value == null || !value.isTextual() || !PLAIN_DECIMAL.matcher(value.textValue()).matches()) {
            throw new IllegalArgumentException(field + " must be a plain-decimal string");
        }
        double number = Double.parseDouble(value.textValue());
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException(field + " is outside the calculation range");
        }
        return number;
    }

    static Double maybePlainDecimal(JsonNode value, String field) {
        return value != null && value.isNull() ? null : plainDecimal(value, field);
    }

    static boolean same(double left, double right, double tolerance) {
        return Math.abs(left - right) <= tolerance;
    }

    static boolean iso(JsonNode value) {
        return value != null && value.isTextual() && ISO.matcher(value.textValue()).matches();
    }

    static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();
        if (object != null && object.isObject()) {
            object.propertyStream().forEach(entry -> names.add(entry.getKey()));
        }
        return names;
    }

    static List<String> stringList(JsonNode array) {
        List<String> output = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> output.add(value.isTextual() ? value.textValue() : String.valueOf(value)));
        }
        return output;
    }

    static List<Integer> integerList(JsonNode array) {
        List<Integer> output = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> output.add(value.intValue()));
        }
        return output;
    }

    static boolean hasDuplicates(List<?> values) {
        return new HashSet<>(values).size() != values.size();
    }

    static String jsNumber(double value) {
        return CanonicalJson.canonicalize(value);
    }

    static double jsNumberConversion(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return Double.NaN;
        }
        if (value.isNull()) {
            return 0;
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? 1 : 0;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isTextual()) {
            String text = value.textValue().trim();
            if (text.isEmpty()) {
                return 0;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    static long parseDateMillis(JsonNode value) {
        return value != null && value.isTextual() ? parseDateMillis(value.textValue()) : Long.MIN_VALUE;
    }

    static long parseDateMillis(String value) {
        if (value == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeException ignored) {
            // Date.parse also accepts ISO offsets and bare dates.
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            // Continue to date-only parsing.
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    static String localToUtcIso(String date, String time, String zone) {
        Matcher dateMatch = DATE.matcher(String.valueOf(date));
        Matcher timeMatch = TIME.matcher(String.valueOf(time));
        if (!dateMatch.matches() || !timeMatch.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(dateMatch.group(1));
            int month = Integer.parseInt(dateMatch.group(2));
            int day = Integer.parseInt(dateMatch.group(3));
            int hour = Integer.parseInt(timeMatch.group(1));
            int minute = Integer.parseInt(timeMatch.group(2));
            // Date.UTC treats 0..99 as 1900..1999; the Node helper rejects the
            // resulting year during its calendar round-trip check.
            if (year < 100) {
                return null;
            }
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute);
            long target = local.toInstant(ZoneOffset.UTC).toEpochMilli();
            ZoneId zoneId = ZoneId.of(zone);
            long instant = target;
            for (int index = 0; index < 3; index++) {
                ZoneOffset offset = zoneId.getRules().getOffset(Instant.ofEpochMilli(instant));
                long next = target - offset.getTotalSeconds() * 1_000L;
                if (next == instant) {
                    break;
                }
                instant = next;
            }
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(instant));
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    static double jsRoundHalf(double value) {
        return Math.floor(value * 2 + 0.5) / 2.0;
    }

    static boolean halfPoint(double value) {
        return Math.abs(value * 2 - Math.floor(value * 2 + 0.5)) <= 1e-9;
    }

    static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
