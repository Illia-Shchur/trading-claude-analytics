package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable compatibility contract against a frozen oracle captured from the
 * original CLI. These vectors deliberately compare stdout byte-for-byte:
 * property ordering, null handling, number spelling, and JSON indentation are
 * all public CLI behavior in addition to the computations themselves.
 */
class ComputeMjsNodeOracleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = findWorkspaceRoot();
    private static final Clock ORACLE_CLOCK = Clock.fixed(
            Instant.parse("2026-08-30T15:14:00Z"), ZoneOffset.UTC);
    private static final ComputeCommand JAVA = new ComputeCommand(ROOT, ORACLE_CLOCK);
    private static final JsonNode ORACLE = frozenOracle();

    @TestFactory
    Stream<DynamicTest> everyComputeModeMatchesFrozenStdoutExactly() {
        return vectors().stream().map(vector -> DynamicTest.dynamicTest(vector.name(), () -> {
            OracleResult oracle = oracleResult(ORACLE.path("vectors").path(vector.name()), vector.argv());
            ComputeCommand.Result java = JAVA.execute(vector.argv());

            assertThat(java.exitCode()).as("Java exit for %s", vector.name()).isEqualTo(oracle.exitCode());
            assertThat(java.stderr()).as("Java stderr for %s", vector.name()).isEqualTo(oracle.stderr());
            assertThat(java.stdout()).as("Java stdout for %s", vector.name()).isEqualTo(oracle.stdout());
        }));
    }

    @Test
    void explicitCliFailuresPreserveExitStatusAndDiagnosticText() throws Exception {
        List<String[]> failures = List.of(
                new String[0],
                new String[]{"rsi"},
                new String[]{"thresholds", "nope"},
                new String[]{"round", "12.5", "--asset", "sol"},
                new String[]{"band", "unknown", "2"},
                new String[]{"ev"},
                new String[]{"adr"},
                new String[]{"streak", "--values", "1,2"},
                new String[]{"corr", "--asset", "[]"},
                new String[]{"basis", "--mark", "1"},
                new String[]{"positioning"},
                new String[]{"tier1"}
        );

        for (String[] argv : failures) {
            OracleResult oracle = oracleFailure(argv);
            ComputeCommand.Result java = JAVA.execute(argv);
            assertThat(java.exitCode()).as(String.join(" ", argv)).isEqualTo(oracle.exitCode());
            assertThat(java.stdout()).as(String.join(" ", argv)).isEqualTo(oracle.stdout());
            assertThat(java.stderr()).as(String.join(" ", argv)).isEqualTo(oracle.stderr());
        }
    }

    @Test
    void uncaughtValidationRetainsTerminalDiagnosticWithoutDependingOnStackFrames() throws Exception {
        String[] argv = {"fr-composite", "--legs", "{}", "--rounding", "bankers"};
        OracleResult oracle = oracleResult(ORACLE.path("uncaught_validation"), argv);
        ComputeCommand.Result java = JAVA.execute(argv);

        String diagnostic = "Error: unknown rounding convention \"bankers\" — declare half-up or half-down (FK SKILL §4)";
        assertThat(oracle.exitCode()).isEqualTo(1);
        assertThat(java.exitCode()).isEqualTo(1);
        assertThat(oracle.stderr()).contains(diagnostic);
        assertThat(java.stderr()).isEqualTo(diagnostic + "\n");
    }

    private static OracleResult oracleFailure(String[] argv) {
        JsonNode failures = ORACLE.path("failures");
        for (JsonNode failure : failures) {
            if (arguments(failure.path("argv")).equals(List.of(argv))) {
                return oracleResult(failure, argv);
            }
        }
        throw new AssertionError("frozen failure oracle is missing: " + String.join(" ", argv));
    }

    private static OracleResult oracleResult(JsonNode value, String[] argv) {
        assertThat(value.isMissingNode()).as("frozen oracle entry for %s", String.join(" ", argv))
                .isFalse();
        List<String> frozenArguments = arguments(value.path("argv"));
        assertThat(frozenArguments).as("frozen oracle argument count for %s", String.join(" ", argv))
                .hasSize(argv.length);
        for (int index = 0; index < argv.length; index++) {
            assertThat(normalizeIntegralDoubleSpelling(frozenArguments.get(index)))
                    .as("frozen oracle argument %s for %s", index, String.join(" ", argv))
                    .isEqualTo(normalizeIntegralDoubleSpelling(argv[index]));
        }
        return new OracleResult(
                value.path("exitCode").asInt(),
                value.path("stdout").asText(),
                value.path("stderr").asText());
    }

    private static String normalizeIntegralDoubleSpelling(String value) {
        return value.replaceAll("(?<![0-9.])(-?[0-9]+)\\.0(?=\\s*[,\\]}]|$)", "$1");
    }

    private static List<String> arguments(JsonNode value) {
        List<String> arguments = new ArrayList<>();
        value.forEach(argument -> arguments.add(argument.asText()));
        return arguments;
    }

    private static JsonNode frozenOracle() {
        try (InputStream input = Objects.requireNonNull(
                ComputeMjsNodeOracleTest.class.getResourceAsStream(
                        "/oracles/compute-mjs-v1.json"),
                "frozen compute oracle is missing")) {
            return JSON.readTree(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static List<Vector> vectors() {
        List<Vector> vectors = new ArrayList<>();
        vectors.add(v("rsi", "rsi", csvDoubles(30, i -> 100 + i * 1.7 + (i % 4 - 2) * 0.6), "--period", "14"));
        vectors.add(v("rsi-insufficient", "rsi", "1,2,3", "--period", "14"));
        vectors.add(v("thresholds-fk", "thresholds", "8"));
        vectors.add(v("thresholds-fr", "thresholds", "8", "--fr"));
        vectors.add(v("round-half-up", "round", "12.5", "--asset", "btc"));
        vectors.add(v("round-half-down", "round", "12.5", "--convention", "half-down"));

        vectors.add(v("band-fk-sentiment", "band", "fk-sentiment", "15"));
        vectors.add(v("band-fk-momentum", "band", "fk-momentum", "28", "--low-confidence"));
        vectors.add(v("band-fk-mvrv", "band", "fk-mvrv", "-0.3"));
        vectors.add(v("band-fk-drawdown", "band", "fk-drawdown", "42"));
        vectors.add(v("band-fk-gold", "band", "fk-gold", "47", "--cot-flush"));
        vectors.add(v("band-fr-euphoria", "band", "fr-euphoria", "82"));
        vectors.add(v("band-fr-momentum", "band", "fr-momentum", "73"));
        vectors.add(v("band-fr-mvrv", "band", "fr-mvrv", "5.2"));
        vectors.add(v("band-fr-ath", "band", "fr-ath", "3"));
        vectors.add(v("band-fr-distribution", "band", "fr-distribution", "2"));
        vectors.add(v("band-fr-vulnerability", "band", "fr-vulnerability", "3"));

        String scenarios = "[{\"name\":\"Rally\",\"p\":30,\"low\":70000,\"high\":78000},"
                + "{\"name\":\"Base\",\"p\":70,\"mid\":60000}]";
        vectors.add(v("ev-weighted", "ev", "--scenarios", scenarios, "--spot", "64400"));
        vectors.add(v("ev-check", "ev", "--scenarios", scenarios, "--spot", "64400", "--stated", "64200"));
        vectors.add(v("stop-coherence", "stop-coherence", "--catastrophic", "50000", "--floor", "54000"));

        String adrSessions = "[{\"date\":\"2026-08-20\",\"high\":110,\"low\":100},"
                + "{\"date\":\"2026-08-21\",\"high\":113,\"low\":101},"
                + "{\"date\":\"2026-08-22\",\"high\":115,\"low\":105},"
                + "{\"date\":\"2026-08-23\",\"high\":116,\"low\":106},"
                + "{\"date\":\"2026-08-24\",\"high\":120,\"low\":108},"
                + "{\"date\":\"2026-08-25\",\"high\":121,\"low\":109}]";
        vectors.add(v("adr", "adr", "--sessions", adrSessions, "--exclude", "2026-08-22", "--n", "5"));
        vectors.add(v("adr-insufficient", "adr", "--sessions", "[{\"date\":\"2026-08-20\",\"high\":110,\"low\":100}]", "--n", "5"));
        vectors.add(v("streak", "streak", "--values", "14,15,12,18", "--threshold", "15"));
        vectors.add(v("fr-funding", "fr-funding", "--per8h", "0.0053"));

        String legs = "{\"flow\":1,\"technical\":3,\"macro\":2,\"sentiment\":2,\"valuation\":2,\"structure\":1}";
        String aligned = "{\"direction_24h\":\"positive\",\"direction_3d\":\"positive\"}";
        String flow = "{\"interval_hours\":4,\"completed_through\":\"2026-08-28T08:00:00Z\",\"errors\":[],"
                + "\"spot_cvd\":" + aligned + ",\"futures_bid_ask_delta\":" + aligned
                + ",\"futures_cvd\":" + aligned
                + ",\"open_interest\":{\"setup_signal_24h\":\"aligned\",\"setup_signal_3d\":\"aligned\"},"
                + "\"oi_weighted_funding\":{\"direction_24h\":\"negative\",\"direction_3d\":\"negative\"}}";
        vectors.add(v("swing-score", "swing-score", "--legs", legs, "--flow", flow,
                "--coverage", "COMPLETE", "--phase", "1A", "--trigger-valid", "true",
                "--equity-usd", "100000", "--stop-distance-pct", "5", "--phase-cap-pct", "10"));
        vectors.add(v("swing-score-partial", "swing-score", "--legs", legs));

        vectors.add(v("squeeze-base", "squeeze", "--funding-annualized", "-6.2", "--sustained3"));
        vectors.add(v("squeeze-escalated", "squeeze", "--funding-annualized", "-8", "--oi-within-5pct", "--single-below-7"));
        vectors.add(v("fr-cap", "fr-cap", "--spot", "64400", "--ath1y", "73800"));
        vectors.add(v("sma", "sma", "--values", "1,2,3,4", "--n", "2"));
        vectors.add(v("drawdown", "drawdown", "--spot", "64400", "--ath", "73800"));
        vectors.add(v("trend", "trend", "--sessions", trendSessions(), "--spot", "105",
                "--fast", "50", "--slow", "200", "--slope-n", "20", "--low-n", "40"));
        vectors.add(v("trend-insufficient", "trend", "--sessions",
                "[{\"date\":\"2026-08-20\",\"high\":2,\"low\":0.5,\"close\":1}]"));
        vectors.add(v("stall", "stall", "--close", "99", "--prior-close", "100", "--high", "104", "--bounce-high", "105"));
        vectors.add(v("fr-composite", "fr-composite", "--legs",
                "{\"euphoria\":4,\"momentum\":3,\"valuation\":2,\"distribution\":3,\"vulnerability\":2}",
                "--penalty", "-2", "--discretionary", "0.5", "--rounding", "half-up",
                "--channel", "A", "--cap-applied", "--cap-value", "14"));
        vectors.add(v("fr-companion", "fr-companion", "--market",
                "{\"pct_below_1y_ath\":8,\"ma200_falling\":false,\"price_below_ma200\":false,"
                        + "\"fng_avg_3d\":82,\"weekly_rsi\":72,\"funding_annualized_pct\":-8,"
                        + "\"sustained_3_intervals\":true,\"oi_within_5pct_of_90d_high\":true}",
                "--counts", "{\"mvrv_z\":5.2,\"distribution_count\":3,\"vulnerability_count\":2}",
                "--rounding", "half-up"));
        vectors.add(v("fr-companion-channel-b-missing-counts", "fr-companion", "--market",
                "{\"pct_below_1y_ath\":25,\"ma200_falling\":true,\"price_below_ma200\":true,"
                        + "\"bounce_pct\":20,\"daily_rsi\":60,\"weekly_rsi\":45,\"bounce_age_sessions\":5,"
                        + "\"funding_annualized_pct\":0}",
                "--counts", "{\"resistance_count\":2}", "--rounding", "half-down"));

        String asset = "[{\"date\":\"2026-08-24\",\"close\":100},{\"date\":\"2026-08-25\",\"close\":102},"
                + "{\"date\":\"2026-08-26\",\"close\":101},{\"date\":\"2026-08-27\",\"close\":105}]";
        String spx = "[{\"date\":\"2026-08-24\",\"close\":6000},{\"date\":\"2026-08-25\",\"close\":6060},"
                + "{\"date\":\"2026-08-26\",\"close\":6030},{\"date\":\"2026-08-27\",\"close\":6120}]";
        vectors.add(v("corr", "corr", "--asset", asset, "--spx", spx, "--window", "4"));
        vectors.add(v("corr-dropped-and-trimmed", "corr", "--asset", asset,
                "--spx", "[{\"date\":\"2026-08-23\",\"close\":5900},{\"date\":\"2026-08-24\",\"close\":6000},"
                        + "{\"date\":\"2026-08-25\",\"close\":6060},{\"date\":\"2026-08-26\",\"close\":6030}]",
                "--window", "3"));
        vectors.add(v("percentile", "percentile", "--values", "1,2,2,4,8", "--x", "2"));
        // Math.sin/Math.exp are allowed to differ by a final ULP across JDK/libm
        // implementations. The original CLI argument bytes are already part of
        // the frozen oracle, so execute this vector with those exact bytes rather
        // than regenerating an equivalent series on the current platform.
        vectors.add(v("rvol", arguments(ORACLE.path("vectors").path("rvol").path("argv"))
                .toArray(String[]::new)));
        vectors.add(v("basis", "basis", "--mark", "101", "--index", "100",
                "--funding-annualized-pct", "5.8", "--risk-free-pct", "4.2"));
        vectors.add(v("short-ev", "short-ev", "--directional-ev", "6.2",
                "--funding-annualized", "-18", "--hold-days", "20", "--target-gain-pct", "8"));
        vectors.add(v("borrow", "borrow", "--ticker", "[3.56e-8,2e-8,2,0.82,4e-8,7,1.27]"));
        vectors.add(v("stablecoin", "stablecoin", "--rows", stablecoinRows()));
        vectors.add(v("netliq", "netliq", "--walcl", "6825000", "--rrpontsyd", "84.5", "--wtregen", "760000"));
        vectors.add(v("positioning", "positioning",
                "--long-short", "[{\"longShortRatio\":\"1.1\"},{\"longShortRatio\":\"1.2\"},{\"longShortRatio\":\"1.15\"}]",
                "--taker", "[{\"buySellRatio\":\"0.9\"},{\"buySellRatio\":\"1.05\"}]",
                "--oi", "[{\"sumOpenInterest\":\"1000\"},{\"sumOpenInterest\":\"1100\"}]"));
        vectors.add(v("vol-surface-empty", "vol-surface", "--book", "[]", "--dvol", "[[0,1,2,3,49.5]]", "--rv30", "42"));
        vectors.add(v("vol-surface-available", "vol-surface", "--book", optionBook(),
                "--dvol", "[[0,1,2,3,49.5]]", "--rv30", "42"));
        vectors.add(v("vol-surface-outside-window", "vol-surface", "--book",
                "[{\"instrument_name\":\"BTC-20SEP27-100000-C\",\"mark_iv\":50,\"underlying_price\":100000}]"));
        vectors.add(v("marketdata", "marketdata", "--asset", "btc", "--max-age-days", "3"));
        vectors.add(v("marketdata-missing-asset", "marketdata", "--asset", "doge", "--max-age-days", "3"));
        vectors.add(v("tier1", "tier1", "--from", "2026-08-28", "--sessions", "5", "--asset-class", "equity"));
        vectors.add(v("tier1-crypto", "tier1", "--from", "2026-08-28", "--sessions", "3", "--asset-class", "crypto"));
        return vectors;
    }

    private static String trendSessions() {
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < 225; i++) {
            double close = 160 - i * 0.23 + Math.sin(i / 4.0) * 1.4;
            ObjectNode row = rows.addObject();
            row.put("date", "2026-" + String.format("%03d", i + 1));
            row.put("high", close + 2 + (i % 3) * 0.1);
            row.put("low", close - 2 - (i % 5) * 0.1);
            row.put("close", close);
        }
        return compact(rows);
    }

    private static String stablecoinRows() {
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < 95; i++) {
            ObjectNode row = rows.addObject();
            row.put("date", 1_700_000_000L + i * 86_400L);
            row.putObject("totalCirculatingUSD").put("peggedUSD", 150_000_000_000L + i * 125_000_000L);
        }
        return compact(rows);
    }

    private static String optionBook() {
        ArrayNode rows = JSON.createArrayNode();
        addOption(rows, "BTC-20SEP26-90000-P", 63, 100_000);
        addOption(rows, "BTC-20SEP26-100000-P", 55, 100_000);
        addOption(rows, "BTC-20SEP26-100000-C", 54, 100_000);
        addOption(rows, "BTC-20SEP26-110000-C", 58, 100_000);
        return compact(rows);
    }

    private static void addOption(ArrayNode rows, String name, double iv, double underlying) {
        ObjectNode row = rows.addObject();
        row.put("instrument_name", name);
        row.put("mark_iv", iv);
        row.put("underlying_price", underlying);
    }

    private static String csvDoubles(int count, IndexedDouble value) {
        return IntStream.range(0, count)
                .mapToObj(i -> Double.toString(value.at(i)))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String compact(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Vector v(String name, String... argv) {
        return new Vector(name, argv);
    }

    private static Path findWorkspaceRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.isRegularFile(cursor.resolve("tools/marketdata.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("cannot locate tools/marketdata.json");
        return cursor;
    }

    private record Vector(String name, String[] argv) {
    }

    private record OracleResult(int exitCode, String stdout, String stderr) {
    }

    @FunctionalInterface
    private interface IndexedDouble {
        double at(int index);
    }
}
