package com.tradinganalytics.core.lib;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Report filename metadata and local-to-UTC conversion rules. */
final class ToolchainCalendar {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern TIME = Pattern.compile("^(\\d{2}):(\\d{2})$");

    private ToolchainCalendar() {
    }

    static String localToUtcISO(String date, String time, String zone) {
        Matcher dateMatch = DATE.matcher(String.valueOf(date));
        Matcher timeMatch = TIME.matcher(String.valueOf(time));
        if (!dateMatch.matches() || !timeMatch.matches()) return null;
        try {
            int year = Integer.parseInt(dateMatch.group(1));
            int month = Integer.parseInt(dateMatch.group(2));
            int day = Integer.parseInt(dateMatch.group(3));
            int hour = Integer.parseInt(timeMatch.group(1));
            int minute = Integer.parseInt(timeMatch.group(2));
            if (hour > 23 || minute > 59) return null;
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute);
            Instant target = local.toInstant(ZoneOffset.UTC);
            Instant cursor = target;
            ZoneId zoneId = ZoneId.of(zone);
            for (int iteration = 0; iteration < 3; iteration++) {
                int offsetSeconds = zoneId.getRules().getOffset(cursor).getTotalSeconds();
                Instant next = target.minusSeconds(offsetSeconds);
                if (next.equals(cursor)) break;
                cursor = next;
            }
            return DateTimeFormatter.ISO_INSTANT.format(cursor);
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    static ObjectNode reportFileMeta(String name) {
        String file = String.valueOf(name);
        Matcher matcher = ToolchainSupport.REPORT_FILE_RE.matcher(file);
        if (!matcher.matches()) {
            return failedMeta(file, "filename does not match <asset>_<framework>_YYYYMMDD_HHMM.md");
        }
        String asset = matcher.group(1);
        String framework = matcher.group(2);
        String date = matcher.group(3) + "-" + matcher.group(4) + "-" + matcher.group(5);
        String localTime = matcher.group(6) + ":" + matcher.group(7);
        String atUtc = localToUtcISO(date, localTime, ToolchainSupport.REPORT_ZONE);
        if (atUtc == null) {
            return failedMeta(file, "filename encodes an impossible date/time (" + date + " " + localTime + ")");
        }
        ObjectNode output = NODES.objectNode();
        output.put("ok", true);
        output.put("file", file);
        output.put("asset", asset.toUpperCase(Locale.ROOT));
        output.put("framework", framework);
        output.put("date", date);
        output.put("local_time", localTime);
        output.put("zone", ToolchainSupport.REPORT_ZONE);
        output.put("at_utc", atUtc);
        output.put("schema_epoch", ToolchainSupport.schemaEpochOf(date));
        return output;
    }

    static int tradingDaysBetween(String fromDate, String toDate, String assetClass) {
        if (toDate.compareTo(fromDate) <= 0) return 0;
        int count = 0;
        LocalDate cursor = LocalDate.parse(fromDate);
        LocalDate end = LocalDate.parse(toDate);
        while (true) {
            cursor = cursor.plusDays(1);
            if (!cursor.isBefore(end)) return count;
            if (ComputeMath.isTradingDay(cursor.toString(), assetClass)) count++;
        }
    }

    private static ObjectNode failedMeta(String file, String reason) {
        ObjectNode output = NODES.objectNode();
        output.put("ok", false);
        output.put("file", file);
        output.put("reason", reason);
        return output;
    }
}
