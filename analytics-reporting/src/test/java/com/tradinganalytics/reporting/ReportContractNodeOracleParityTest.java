package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Differential vectors frozen against the current report-contract.mjs implementation. */
class ReportContractNodeOracleParityTest {
    @Test
    void schemaValidSemanticMutationsMatchNodeExactly() throws Exception {
        List<Vector> vectors = vectors();
        ArrayNode oracle = frozenOracle("/oracles/report-contract-semantic-v1.json");

        assertThat(oracle).hasSize(vectors.size());
        for (int index = 0; index < vectors.size(); index++) {
            Vector vector = vectors.get(index);
            JsonNode expected = oracle.get(index);
            ReportContract.ValidationResult actual = "report-machine/3".equals(vector.report().path("schema").asText())
                    ? ReportContract.validateReportMachine3(vector.report())
                    : ReportContract.validateReportMachine2(vector.report());
            assertThat(actual.ok()).as(vector.name()).isEqualTo(expected.path("ok").asBoolean());
            assertThat(actual.schema()).as(vector.name()).isEqualTo(expected.path("schema").asText());
            assertThat(actual.errors()).as(vector.name() + " errors")
                    .containsExactlyElementsOf(strings(expected.path("errors")));
            assertThat(actual.warnings()).as(vector.name() + " warnings")
                    .containsExactlyElementsOf(strings(expected.path("warnings")));
        }
    }

    @Test
    void everyRequiredRootFieldAndRepresentativeSchemaConstraintMatchesNodeRejection() throws Exception {
        ObjectNode v2 = read("reports/btc_fallen_knives_20260822_0346.json");
        ObjectNode v3 = read("tools/fixtures/report-machine-3.sample.json");
        List<Vector> vectors = new ArrayList<>();
        for (String field : List.of(
                "schema", "report_id", "identity", "timestamps", "run", "sources", "evidence", "market",
                "data_gaps", "substitutions", "stale_inputs", "out_of_scope", "score", "gates", "ev",
                "deployment", "position", "position_controls", "risk_controls", "companion_framework",
                "cross_validation", "watchlist", "events", "falsifiers", "change_log", "narrative",
                "verdict", "tagging")) {
            add(vectors, "v2 required " + field, v2, report -> report.remove(field));
        }
        for (String field : List.of(
                "schema", "report_id", "identity", "timestamps", "model_activation", "setup", "features",
                "trigger", "vetoes", "risk_budget", "expectancy_r", "trade_plan", "audit", "sources",
                "provenance", "tags")) {
            add(vectors, "v3 required " + field, v3, report -> report.remove(field));
        }
        add(vectors, "v2 root additional property", v2, report -> report.put("unexpected", true));
        add(vectors, "v3 root additional property", v3, report -> report.put("unexpected", true));
        add(vectors, "v2 nested additional property", v2,
                report -> object(report, "identity").put("unexpected", true));
        add(vectors, "v3 nested additional property", v3,
                report -> object(report, "identity").put("unexpected", true));
        add(vectors, "v2 wrong status enum", v2,
                report -> object(object(report, "evidence"), "correlation").put("status", "OTHER"));
        add(vectors, "v3 wrong framework enum", v3,
                report -> object(report, "setup").put("framework", "other"));

        ArrayNode oracle = frozenOracle("/oracles/report-contract-schema-v1.json");
        for (int index = 0; index < vectors.size(); index++) {
            Vector vector = vectors.get(index);
            ReportContract.ValidationResult actual = "report-machine/3".equals(vector.report().path("schema").asText())
                    ? ReportContract.validateReportMachine3(vector.report())
                    : ReportContract.validateReportMachine2(vector.report());
            assertThat(oracle.get(index).path("ok").asBoolean()).as(vector.name() + " Node").isFalse();
            assertThat(actual.ok()).as(vector.name() + " Java").isFalse();
            if ("v3 required schema".equals(vector.name())) {
                // Removing the discriminator intentionally routes this v3 shape
                // through v2. Networknt and AJV return the same complete error
                // multiset but traverse simultaneous nested errors differently.
                assertThat(actual.errors()).as(vector.name())
                        .containsExactlyInAnyOrderElementsOf(strings(oracle.get(index).path("errors")));
            } else {
                assertThat(actual.errors()).as(vector.name())
                        .containsExactlyElementsOf(strings(oracle.get(index).path("errors")));
            }
            assertThat(actual.warnings()).as(vector.name() + " warnings")
                    .containsExactlyElementsOf(strings(oracle.get(index).path("warnings")));
        }
    }

    private List<Vector> vectors() throws IOException {
        ObjectNode fk2 = read("reports/btc_fallen_knives_20260822_0346.json");
        ObjectNode frb2 = read("reports/btc_flying_rocket_20260819_1222.json");
        ObjectNode open2 = read("reports/sp500_flying_rocket_20260820_1640.json");
        ObjectNode v3 = read("tools/fixtures/report-machine-3.sample.json");
        ObjectNode authFk3 = authorizedFk(v3);
        ObjectNode authFr3 = authorizedFr(v3, "A");
        ObjectNode authFrb3 = authorizedFr(v3, "B");
        List<Vector> vectors = new ArrayList<>();

        add(vectors, "v2 published baseline", fk2, report -> { });
        add(vectors, "v2 identity asset", fk2, report -> object(report, "identity").put("asset", "ETH"));
        add(vectors, "v2 timestamp warning", fk2,
                report -> object(report, "timestamps").put("generated_at", "2026-08-22T07:45:00Z"));
        add(vectors, "v2 evidence unavailable value", fk2,
                report -> object(object(report, "evidence"), "correlation").put("status", "UNKNOWN"));
        add(vectors, "v2 unresolved source", fk2, report -> {
            ArrayNode ids = array(object(object(report, "evidence"), "correlation"), "source_ids");
            ids.removeAll().add("missing-source");
        });
        add(vectors, "v2 reconciliation quote count", fk2, report -> {
            ArrayNode quotes = array(object(object(report, "market"), "reconciliation"), "quotes");
            while (quotes.size() > 1) quotes.remove(quotes.size() - 1);
        });
        add(vectors, "v2 score recomputation", fk2, report -> object(report, "score").put("mechanical", 7));
        add(vectors, "v2 applied score cap", fk2, report ->
                array(object(report, "score"), "caps").addObject().put("applied", true).put("value", 5));
        add(vectors, "v2 sorted gates", fk2, report -> {
            ArrayNode passed = array(object(report, "gates"), "passed");
            int first = passed.get(0).intValue();
            JsonNode second = passed.get(1);
            passed.set(0, second);
            passed.set(1, passed.numberNode(first));
        });
        add(vectors, "v2 deterministic threshold", fk2,
                report -> object(object(report, "gates"), "thresholds").put("p1a", 4));
        add(vectors, "v2 EV probability", fk2,
                report -> object(array(object(report, "ev"), "scenarios").get(0)).put("probability", 0.2));
        add(vectors, "v2 EV stated", fk2, report -> object(report, "ev").put("stated_ev", "1"));
        add(vectors, "v2 unchecked EV carries values", fk2,
                report -> object(report, "ev").put("arithmetic_status", "DATA_LIMITED"));
        add(vectors, "v2 filled tranche historical score lookup", fk2, report -> fillV2(report, false, false));
        add(vectors, "v2 invalid long stop", fk2, report -> fillV2(report, true, false));
        add(vectors, "v2 DATA_LIMITED numeric position", fk2,
                report -> object(report, "position").put("quantity", "1"));
        add(vectors, "v2 external custody quantity", fk2,
                report -> object(object(report, "position"), "custody").put("status", "EXPLAINED_BY_EXTERNAL_TRANSFER"));
        add(vectors, "v2 unreliable basis values", fk2,
                report -> object(object(report, "position"), "basis").put("avg_cost", "1"));
        add(vectors, "v2 duplicate reserved tag", fk2, report -> {
            ArrayNode reserved = array(object(report, "tagging"), "reserved_tags");
            reserved.add(reserved.get(0).textValue());
        });
        add(vectors, "v2 unreserved active tag", fk2,
                report -> array(object(report, "tagging"), "active_tags").add("FK-UNKNOWN-BTC-20260822-0346"));
        add(vectors, "v2 broken tag identity", fk2, report ->
                array(object(report, "tagging"), "reserved_tags").set(0,
                        array(object(report, "tagging"), "reserved_tags").textNode("FK-P1A-ETH-20260822-0346")));
        add(vectors, "v2 elevated companion inconsistency", fk2, report -> {
            object(report, "score").put("adjusted", 12);
            object(report, "companion_framework").put("score", 12);
        });
        add(vectors, "v2 FR-B regime drawdown", frb2,
                report -> object(report, "regime").put("pct_below_1y_ath", "20"));
        add(vectors, "v2 FR-B regime trend", frb2, report ->
                object(report, "regime").put("ma200_falling", false).put("price_below_ma200", false));
        add(vectors, "v2 FR-B companion", frb2,
                report -> object(report, "companion_framework").put("framework", "none"));
        add(vectors, "v2 FR penalty", frb2,
                report -> array(object(report, "score"), "penalties").add(1));
        add(vectors, "v2 FR filled without clock", frb2, report -> fillV2(report, false, false));
        add(vectors, "v2 FR filled excessive clock", frb2, report -> fillV2(report, false, true));
        add(vectors, "v2 FR-B phase 3 registry", frb2, ReportContractNodeOracleParityTest::addFrbPhase3);
        add(vectors, "v2 open target quantity", open2,
                report -> object(object(report, "position_controls"), "ladder").put("target_quantity", "15"));
        add(vectors, "v2 open liquidation side", open2,
                report -> object(object(report, "position_controls"), "liquidation_zone").put("price", "700"));
        add(vectors, "v2 open risk caps", open2,
                report -> object(object(report, "position_controls"), "risk").put("book_pct", "51").put("asset_pct", "31"));
        add(vectors, "v2 open required control", open2,
                report -> object(report, "position_controls").remove("candidate"));
        add(vectors, "v2 open required flag", open2,
                report -> object(report, "position_controls").put("required", false));

        add(vectors, "v3 sample baseline", v3, report -> { });
        add(vectors, "v3 identity", v3, report -> object(report, "identity").put("asset", "ETH"));
        add(vectors, "v3 timezone", v3, report -> object(report, "timestamps").put("timezone", "UTC"));
        add(vectors, "v3 ISO semantic format", v3,
                report -> object(report, "timestamps").put("generated_at", "not-an-instant"));
        add(vectors, "v3 non-active metadata warning", v3,
                report -> object(report, "model_activation").put("artifact", "calibrations/model.json"));
        add(vectors, "v3 active incomplete metadata", v3,
                report -> object(report, "model_activation").put("status", "ACTIVE"));
        add(vectors, "v3 horizon", v3,
                report -> object(object(report, "setup"), "horizon_days").put("min", 4));
        add(vectors, "v3 half-point flow", v3,
                report -> object(object(report, "setup"), "legs").put("flow", 4.25));
        add(vectors, "v3 component total", v3,
                report -> object(object(object(report, "setup"), "leg_components"), "technical").put("total", 2.5));
        add(vectors, "v3 mechanical score", v3,
                report -> object(report, "setup").put("mechanical_score", 11.5));
        add(vectors, "v3 invalid phase", v3, report -> object(report, "setup").put("phase", "X"));
        add(vectors, "v3 valid trigger incomplete", v3, report -> object(report, "trigger").put("status", "VALID"));
        add(vectors, "v3 trigger age", v3,
                report -> object(report, "trigger").put("window_bars", 1).put("age_bars", 2));
        add(vectors, "v3 authorized status consistency", v3,
                report -> object(report, "setup").put("status", "AUTHORIZED"));
        add(vectors, "v3 missing canonical veto", v3, report -> array(report, "vetoes").remove(0));
        add(vectors, "v3 duplicate veto", v3,
                report -> object(array(report, "vetoes").get(1)).put("code", "FLOW_COVERAGE"));
        add(vectors, "v3 complete audit source", v3,
                report -> object(report, "audit").set("sources", report.objectNode()));
        add(vectors, "v3 duplicate reserved tag", v3, report -> {
            ArrayNode reserved = array(object(report, "tags"), "reserved");
            reserved.add(reserved.get(0).textValue());
        });
        add(vectors, "v3 unreserved active tag", v3,
                report -> array(object(report, "tags"), "active").add("NOT-RESERVED"));
        add(vectors, "v3 incomplete flow", v3,
                report -> object(object(report, "features"), "flow").remove("spot_cvd"));
        add(vectors, "v3 opposing flow", v3, report ->
                object(object(object(report, "features"), "flow"), "spot_cvd").put("24h", "down").put("3d", "down"));
        add(vectors, "v3 audit lint", v3, report -> object(report, "audit").put("lint", "FAIL"));

        add(vectors, "v3 authorized FK baseline", authFk3, report -> { });
        add(vectors, "v3 authorized active veto", authFk3,
                report -> object(array(report, "vetoes").get(7)).put("active", true));
        add(vectors, "v3 authorized risk phase cap", authFk3,
                report -> object(report, "risk_budget").put("phase_cap_pct", 11));
        add(vectors, "v3 authorized risk notional", authFk3,
                report -> object(report, "risk_budget").put("notional_usd", 151));
        add(vectors, "v3 authorized trigger timeframe", authFk3,
                report -> object(report, "trigger").put("timeframe", "1d"));
        add(vectors, "v3 authorized target ladder", authFk3,
                report -> object(array(object(report, "trade_plan"), "targets").get(1)).put("share_pct", 30));
        add(vectors, "v3 authorized tactical buffer", authFk3,
                report -> object(object(object(report, "trade_plan"), "stop"), "tactical").put("buffer_atr", 0.5));
        add(vectors, "v3 authorized long stop", authFk3,
                report -> object(object(report, "trade_plan"), "stop").put("price", 110));
        add(vectors, "v3 authorized clock", authFk3,
                report -> object(report, "trade_plan").put("clock_days", 8));
        add(vectors, "v3 authorized time stop", authFk3,
                report -> object(report, "trade_plan").put("time_stop", "2026-08-29T15:00:00Z"));
        add(vectors, "v3 active tag requires fill", authFk3,
                report -> array(object(report, "tags"), "active").add(
                        array(object(report, "tags"), "reserved").get(0).textValue()));

        add(vectors, "v3 authorized FR-A baseline", authFr3, report -> { });
        add(vectors, "v3 FR ratchet", authFr3,
                report -> object(object(report, "trade_plan"), "ratchet").put("can_loosen", true));
        add(vectors, "v3 FR carry", authFr3,
                report -> object(object(report, "trade_plan"), "carry").put("status", "FAIL"));
        add(vectors, "v3 FR short stop", authFr3,
                report -> object(object(report, "trade_plan"), "stop").put("price", 90));

        add(vectors, "v3 authorized FR-B baseline", authFrb3, report -> { });
        add(vectors, "v3 FR-B book cap", authFrb3,
                report -> object(object(report, "risk_budget"), "constraints").put("book_pct", 31));
        add(vectors, "v3 FR-B funding veto", authFrb3,
                report -> object(array(report, "vetoes").get(6)).put("active", true));
        add(vectors, "v3 FR-B phase 3", authFrb3, report -> object(report, "setup").put("phase", "3"));
        return vectors;
    }

    private ObjectNode authorizedFk(ObjectNode source) {
        ObjectNode report = source.deepCopy();
        activate(report);
        object(report, "setup").put("status", "AUTHORIZED").put("entry_authorized", true);
        authorizeTrigger(report, "LONG");
        authorizeRisk(report, 10);
        authorizePlan(report, "LONG", 90, false);
        return report;
    }

    private ObjectNode authorizedFr(ObjectNode source, String channel) {
        ObjectNode report = source.deepCopy();
        String reportId = "btc_flying_rocket_20260822_1200";
        report.put("report_id", reportId);
        object(report, "identity").put("framework", "flying_rocket").put("filename", reportId + ".json");
        ObjectNode setup = object(report, "setup");
        setup.put("framework", "flying_rocket").put("channel", channel)
                .put("status", "AUTHORIZED").put("entry_authorized", true);
        ObjectNode legs = object(setup, "legs");
        if ("B".equals(channel)) {
            setup.put("mechanical_score", 13).put("score", 13).put("phase_threshold", 13);
            ObjectNode technical = object(object(setup, "leg_components"), "technical");
            technical.put("state", 2).put("total", 3.5);
            legs.put("technical", 3.5);
            ObjectNode valuation = object(object(setup, "leg_components"), "valuation");
            valuation.put("state", 1).put("total", 1.5);
            legs.put("valuation", 1.5);
        } else {
            setup.put("phase_threshold", 11);
        }
        ObjectNode flow = object(object(report, "features"), "flow");
        for (String row : List.of("spot_cvd", "futures_bid_ask_delta", "futures_cvd", "open_interest")) {
            object(flow, row).put("24h", "down").put("3d", "down");
        }
        activate(report);
        authorizeTrigger(report, "SHORT");
        authorizeRisk(report, 5);
        if ("B".equals(channel)) object(report, "risk_budget").putObject("constraints").put("book_pct", 30);
        authorizePlan(report, "SHORT", 110, true);
        return report;
    }

    private static void activate(ObjectNode report) {
        object(report, "model_activation").put("status", "ACTIVE")
                .put("artifact", "calibrations/model.json").put("sha256", "0".repeat(64))
                .put("activated_at", "2026-08-22T15:00:00Z");
    }

    private static void authorizeTrigger(ObjectNode report, String direction) {
        object(report, "trigger").put("status", "VALID").put("direction", direction)
                .put("timeframe", "4h").put("completed_bar_required", true).put("completed_bar", true)
                .put("level", 100).put("created_at", "2026-08-22T12:00:00Z")
                .put("expires_at", "2026-08-22T20:00:00Z").put("window_bars", 2).put("age_bars", 1);
    }

    private static void authorizeRisk(ObjectNode report, int phaseCap) {
        object(report, "risk_budget").put("status", "AVAILABLE").put("portfolio_risk_pct", 1.5)
                .put("asset_risk_pct", 3).put("phase_cap_pct", phaseCap).put("stop_distance_pct", 10)
                .put("notional_usd", 150).put("equity_usd", 10_000);
    }

    private static void authorizePlan(ObjectNode report, String direction, int stopPrice, boolean flyingRocket) {
        ObjectNode plan = object(report, "trade_plan");
        plan.put("status", "AUTHORIZED").put("direction", direction).put("clock_days", 7)
                .put("time_stop", "2026-08-29T16:00:00Z");
        plan.putObject("entry").put("price", 100).put("status", "PLANNED");
        ObjectNode stop = plan.putObject("stop").put("price", stopPrice).put("distance_pct", 10);
        if (flyingRocket) {
            plan.putObject("ratchet").put("can_loosen", false).put("after_t1", "breakeven").put("after_t2", "trail");
            plan.putObject("carry").put("status", "PASS").put("veto_active", false);
        } else {
            stop.put("mode", "TACTICAL");
            stop.putObject("tactical").put("atr", 1).put("invalidation_price", 92)
                    .put("buffer_atr", 0.25).put("distance_atr", 8);
            plan.putNull("ratchet");
            plan.putNull("carry");
        }
        ArrayNode targets = plan.putArray("targets");
        targets.addObject().put("r", 1).put("share_pct", 40);
        targets.addObject().put("r", 2).put("share_pct", 40);
        targets.addObject().put("r", 3).put("share_pct", 20).put("trailing", true);
    }

    private static void fillV2(ObjectNode report, boolean invalidStop, boolean excessiveClock) {
        ObjectNode tranche = object(array(object(report, "deployment"), "tranches").get(0));
        tranche.put("state", "FILLED").put("deployed", true)
                .put("entry_price", "70000").put("stop", invalidStop ? "80000" : "60000");
        if ("flying_rocket".equals(report.path("identity").path("framework").asText())) {
            tranche.put("stop", invalidStop ? "60000" : "80000");
            if (excessiveClock) tranche.put("time_stop", "2026-09-30T00:00:00Z");
        }
        object(report, "deployment").put("deployed_pct", "10").put("dry_pct", "90");
    }

    private static void addFrbPhase3(ObjectNode report) {
        ObjectNode tags = object(report, "tagging");
        String tag = "FR-B-P3-BTC-20260819-1222";
        array(tags, "reserved_tags").add(tag);
        array(tags, "entries").addObject().put("phase", "3").put("canonical_tag", tag)
                .put("decision", "LOCKED").put("instrument_class", tags.path("instrument_class").asText());
    }

    private static ArrayNode frozenOracle(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                ReportContractNodeOracleParityTest.class.getResourceAsStream(resource),
                "frozen report-contract oracle is missing: " + resource)) {
            JsonNode parsed = ReportContract.parseStrictJSON(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    resource);
            assertThat(parsed.isArray()).as(resource).isTrue();
            return (ArrayNode) parsed;
        }
    }

    private ObjectNode read(String relative) throws IOException {
        return (ObjectNode) ReportContract.parseStrictJSON(
                Files.readString(ReportContractKnownAnswerTest.repositoryRoot().resolve(relative)), relative);
    }

    private static void add(List<Vector> vectors, String name, ObjectNode base, Consumer<ObjectNode> mutation) {
        ObjectNode copy = base.deepCopy();
        mutation.accept(copy);
        vectors.add(new Vector(name, copy));
    }

    private static ObjectNode object(JsonNode parent, String field) { return (ObjectNode) parent.get(field); }
    private static ObjectNode object(JsonNode node) { return (ObjectNode) node; }
    private static ArrayNode array(JsonNode parent, String field) { return (ArrayNode) parent.get(field); }

    private static List<String> strings(JsonNode values) {
        List<String> output = new ArrayList<>();
        values.forEach(value -> output.add(value.textValue()));
        return output;
    }

    private record Vector(String name, ObjectNode report) { }
}
