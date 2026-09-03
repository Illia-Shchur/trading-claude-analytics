package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.tradinganalytics.core.swing.SwingJsSupport.half;
import static com.tradinganalytics.core.swing.SwingJsSupport.number;
import static com.tradinganalytics.core.swing.SwingJsSupport.property;
import static com.tradinganalytics.core.swing.SwingJsSupport.stringValue;
import static com.tradinganalytics.core.swing.SwingJsSupport.truthy;

/** Interprets the completed-bar market-flow panel used by the swing score. */
final class SwingFlowAssessment {

    private static final Pattern POSITIVE_FLOW = Pattern.compile("positive|up|buy|rising|increase|absorb");
    private static final Pattern NEGATIVE_FLOW = Pattern.compile("negative|down|sell|fall|decrease|build");
    private static final Pattern ALIGNED = Pattern.compile("aligned|favourable|favorable|confirm");
    private static final Pattern OPPOSING = Pattern.compile("opposing|adverse|diverg");
    private static final Pattern NEUTRAL = Pattern.compile("neutral|flat|mixed");

    private SwingFlowAssessment() {
    }

    static SwingScore.FlowAssessment assess(JsonNode panel, SwingScore.FlowOptions options) {
        JsonNode source = isObject(panel) ? panel : MissingNode.getInstance();
        double direction = options == null || options.direction() == null ? 1.0 : options.direction();
        int sign = direction >= 0.0 ? 1 : -1;
        String coverage = resolveCoverage(source, options);

        List<SwingScore.FlowRow> rows = SwingScore.FLOW_PANEL_ROWS.stream()
                .map(name -> assessRow(name, property(source, name), sign))
                .toList();

        JsonNode intervalInput = nullishProperty(source, "interval_hours", "intervalHours");
        double interval = number(intervalInput);
        JsonNode errorsNode = property(source, "errors");
        int errorCount = errorsNode.isArray() ? errorsNode.size() : 0;
        JsonNode completedNode = property(source, "completed_through");
        boolean hasCompletedString = completedNode.isTextual();
        boolean complete = "COMPLETE".equals(coverage.toUpperCase(Locale.ROOT))
                && interval == 4.0
                && hasCompletedString
                && errorCount == 0
                && rows.stream().allMatch(row -> row.available() && row.state() != null && row.impulse() != null);

        int alignedRows = (int) rows.stream().filter(SwingScore.FlowRow::aligned).count();
        int opposingRows = (int) rows.stream().filter(SwingScore.FlowRow::opposing).count();
        Map<String, SwingScore.FlowRow> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.name(), row));

        List<SwingScore.EvidenceFamily> evidenceFamilies = List.of(
                evidenceFamily("spot_cvd", List.of("spot_cvd"), byName),
                evidenceFamily("futures_taker_flow", List.of("futures_bid_ask_delta", "futures_cvd"), byName),
                evidenceFamily("open_interest", List.of("open_interest"), byName),
                evidenceFamily("oi_weighted_funding", List.of("oi_weighted_funding"), byName)
        );
        int alignedEvidence = (int) evidenceFamilies.stream().filter(SwingScore.EvidenceFamily::aligned).count();
        int opposingEvidence = (int) evidenceFamilies.stream().filter(SwingScore.EvidenceFamily::opposing).count();
        double evidenceScore = alignedEvidence
                * (SwingScore.SCORE_MAXES.get("flow").doubleValue() / SwingScore.FLOW_EVIDENCE_FAMILIES.size());
        String completedThrough = hasCompletedString && !completedNode.textValue().isEmpty()
                ? completedNode.textValue()
                : null;

        return new SwingScore.FlowAssessment(
                SwingScore.SWING_SCORE_VERSION,
                coverage.toUpperCase(Locale.ROOT),
                complete ? "COMPLETE" : "PARTIAL",
                Double.isFinite(interval) ? interval : null,
                completedThrough,
                rows,
                alignedRows,
                opposingRows,
                evidenceFamilies,
                alignedEvidence,
                opposingEvidence,
                complete,
                complete,
                half(complete ? evidenceScore : Math.min(evidenceScore, 2.5)),
                complete ? null : "requires error-free completed 4h bars with 24h and 3d directions for all five rows"
        );
    }

    private static SwingScore.FlowRow assessRow(String name, JsonNode entry, int direction) {
        Integer state = horizonValue(entry, "24h", name, direction);
        Integer impulse = horizonValue(entry, "3d", name, direction);
        boolean available = !entry.isMissingNode() && !entry.isNull()
                && !(entry.isObject() && property(entry, "available").isBoolean()
                    && !property(entry, "available").booleanValue());
        return new SwingScore.FlowRow(
                name,
                state,
                impulse,
                available,
                state != null && impulse != null && state == direction && impulse == direction,
                state != null && impulse != null && state == -direction && impulse == -direction
        );
    }

    private static Integer horizonValue(JsonNode entry, String suffix, String name, int direction) {
        if (!isObject(entry)) {
            return null;
        }
        JsonNode interpreted = nullishThenFinalProperty(
                entry,
                "setup_signal_" + suffix,
                "alignment_" + suffix
        );
        if (!interpreted.isMissingNode()) {
            String text = stringValue(interpreted).toLowerCase(Locale.ROOT);
            if (ALIGNED.matcher(text).find()) {
                return direction;
            }
            if (OPPOSING.matcher(text).find()) {
                return -direction;
            }
            if (NEUTRAL.matcher(text).find()) {
                return 0;
            }
            return flowSign(interpreted);
        }
        if ("open_interest".equals(name)) {
            return null;
        }
        JsonNode raw = nullishThenFinalProperty(
                entry,
                "direction_" + suffix,
                "signal_" + suffix,
                "delta_" + suffix + "_usd",
                "change_" + suffix + "_pct",
                suffix
        );
        if (raw.isMissingNode()) {
            return null;
        }
        int value = flowSign(raw);
        return "oi_weighted_funding".equals(name) ? -value : value;
    }

    private static int flowSign(JsonNode value) {
        if (value != null && value.isNumber() && Double.isFinite(value.doubleValue())) {
            double numeric = value.doubleValue();
            return numeric > 0.0 ? 1 : numeric < 0.0 ? -1 : 0;
        }
        String text = stringValue(value == null || value.isNull() ? MissingNode.getInstance() : value)
                .toLowerCase(Locale.ROOT);
        if (POSITIVE_FLOW.matcher(text).find()) {
            return 1;
        }
        if (NEGATIVE_FLOW.matcher(text).find()) {
            return -1;
        }
        return 0;
    }

    private static SwingScore.EvidenceFamily evidenceFamily(
            String name,
            List<String> memberNames,
            Map<String, SwingScore.FlowRow> byName) {
        List<SwingScore.FlowRow> members = memberNames.stream().map(byName::get).toList();
        return new SwingScore.EvidenceFamily(
                name,
                memberNames,
                members.stream().allMatch(SwingScore.FlowRow::available),
                members.stream().allMatch(SwingScore.FlowRow::aligned),
                members.stream().allMatch(SwingScore.FlowRow::opposing)
        );
    }

    private static String resolveCoverage(JsonNode panel, SwingScore.FlowOptions options) {
        if (options != null && options.coverage() != null) {
            return options.coverage();
        }
        JsonNode panelCoverage = property(panel, "coverage");
        if (truthy(panelCoverage)) {
            return stringValue(panelCoverage);
        }
        return "COMPLETE";
    }

    private static JsonNode nullishProperty(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode value = property(object, name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return MissingNode.getInstance();
    }

    /** Models {@code a ?? b ?? finalProperty}, where the final null is retained. */
    private static JsonNode nullishThenFinalProperty(JsonNode object, String... names) {
        for (int i = 0; i < names.length - 1; i++) {
            JsonNode value = property(object, names[i]);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return property(object, names[names.length - 1]);
    }

    private static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }

}
