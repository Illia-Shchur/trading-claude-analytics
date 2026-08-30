package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class StrategyPortfolioV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @Test
    void everyPublicBindingMatchesNodeForDeterministicFixtures() throws Exception {
        ArrayNode requests = MAPPER.createArrayNode();
        requests.add(object().put("name", "validate").put("arg_name", "instrument").set("instrument", spotInstrument()));
        requests.add(object().put("name", "validate").put("arg_name", "derivative").set("instrument", perpetualInstrument()));
        requests.add(object().put("name", "validate").put("arg_name", "bad").set("instrument", object().put("asset_class", "equity").put("instrument_type", "spot").put("asset", "spy")));
        requests.add(object().put("name", "validateCryptoPortfolioInstrument").set("instrument", spotInstrument()));
        requests.add(request("simulateCryptoPortfolio", legacySignals(), object().put("initial_equity", 1_000)));
        requests.add(request("runCryptoPortfolio", legacySignals(), object().put("initial_equity", 1_000)));
        requests.add(request("simulateLinearMarkToMarketPortfolio", strictSignals(), strictPolicy()));
        requests.add(request("simulateLinearMarkToMarketPortfolioLegacy", strictSignals(), strictPolicy()));
        requests.add(request("simulateLinearMarkToMarketPortfolio", derivativeSignals(), derivativePolicy(false)));
        requests.add(request("simulateLinearMarkToMarketPortfolio", derivativeSignals(), derivativePolicy(true)));
        requests.add(request("simulateCryptoPortfolio", cappedLegacySignals(), cappedLegacyPolicy()));
        requests.add(request("simulateCryptoPortfolio", legacySignals(), object().put("initial_equity", 1_000).put("authoritative", "true")));
        requests.add(request("simulateLinearMarkToMarketPortfolio", strictSignals(), strictPolicy().put("advanced_risk", "true")));
        requests.add(request("simulateLinearMarkToMarketPortfolio", MAPPER.createArrayNode(), object()));
        requests.add(request("simulateLinearMarkToMarketPortfolio", MAPPER.createArrayNode(), object().put("initial_equity", 1_000)));
        requests.add(request("simulateLinearMarkToMarketPortfolio", MAPPER.createArrayNode(), object().put("initial_equity", 1_000)
                .set("marks", MAPPER.createArrayNode().add(object().put("time", 1).put("asset", "btc").put("price", 0)))));
        requests.add(request("simulateLinearMarkToMarketPortfolioLegacy", MAPPER.createArrayNode(), object()));
        requests.add(request("simulateCryptoPortfolio", MAPPER.createArrayNode(), object()));
        ArrayNode expected = frozenOracle("/oracles/strategy-portfolio-v5-public.json");
        ArrayNode actual = MAPPER.createArrayNode();
        for (JsonNode item : requests) {
            ObjectNode result = object();
            try {
                String name = item.path("name").asText(); JsonNode value;
                if (name.equals("validate") || name.equals("validateCryptoPortfolioInstrument")) value = MAPPER.valueToTree(name.equals("validate")
                        ? StrategyPortfolioV5.validatePortfolioInstrument(item.path("instrument"), item.path("arg_name").asText())
                        : StrategyPortfolioV5.validateCryptoPortfolioInstrument(item.path("instrument")));
                else if (name.equals("simulateCryptoPortfolio") || name.equals("runCryptoPortfolio")) value = StrategyPortfolioV5.simulateCryptoPortfolio(item.path("signals"), item.path("policy"));
                else if (name.equals("simulateLinearMarkToMarketPortfolio")) value = StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(item.path("signals"), item.path("policy"));
                else value = StrategyPortfolioV5.simulateLinearMarkToMarketPortfolioLegacy(item.path("signals"), item.path("policy"));
                result.put("ok", true).set("value", value);
            } catch (RuntimeException error) { result.put("ok", false).put("error", error.getMessage()); }
            actual.add(result);
        }
        assertCanonical(actual, expected);
    }

    @Test
    void authoritativeBoundaryAndErrorContractsAreFailClosed() {
        assertThatThrownBy(() -> StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(MAPPER.createArrayNode(), object()))
                .hasMessage("authoritative portfolio initial_equity must be positive");
        assertThatThrownBy(() -> StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(MAPPER.createArrayNode(), object().put("initial_equity", 1_000)))
                .hasMessage("authoritative portfolio requires a mark path");
        assertThat(StrategyPortfolioV5.validatePortfolioInstrument(object().put("asset_class", "crypto").put("instrument_type", "option").put("asset", "btc").put("venue", "binance").put("collateral", "USDT"))).isTrue();
        assertThatThrownBy(() -> StrategyPortfolioV5.validatePortfolioInstrument(object().put("asset_class", "crypto").put("instrument_type", "perpetual").put("asset", "btc")))
                .hasMessage("instrument.venue is required for derivatives");
    }

    @Test
    void aliasesRemainBehaviorallyIdentical() {
        ObjectNode policy = strictPolicy(); ArrayNode signals = strictSignals();
        assertCanonical(StrategyPortfolioV5.simulateCryptoPortfolio(signals, policy), StrategyPortfolioV5.runCryptoPortfolio(signals, policy));
        assertThat(StrategyPortfolioV5.validateCryptoPortfolioInstrument(spotInstrument())).isTrue();
        assertThat(StrategyPortfolioV5.validatePortfolioInstrument(spotInstrument())).isTrue();
    }

    @Test
    void strictPathRejectsForgedFillPricesAndCanMeasureAdvancedRisk() {
        ObjectNode policy = strictPolicy(); policy.put("advanced_risk", true).put("max_mark_gap_ms", 3_600_000);
        ObjectNode forged = strictSignals().get(0).deepCopy(); forged.put("entry_price", 99);
        ObjectNode result = StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(MAPPER.createArrayNode().add(forged), policy);
        assertThat(result.path("rejected_signals").get(0).path("reason").asText()).isEqualTo("FILL_PRICE_MARK_RECONCILIATION_FAILED");
        assertThat(result.path("failures").toString()).contains("FORGED_FILL_PRICE");
    }

    @Test
    void derivativeFundingAndLegacyRejectionBranchesMatchNode() throws Exception {
        ArrayNode requests = MAPPER.createArrayNode();
        requests.add(request("simulateLinearMarkToMarketPortfolio", derivativeSignals(), derivativePolicy(false)));
        requests.add(request("simulateLinearMarkToMarketPortfolio", derivativeSignalsWithBadFunding(), derivativePolicy(false)));
        requests.add(request("simulateCryptoPortfolio", cappedLegacySignals(), cappedLegacyPolicy()));
        ArrayNode expected = frozenOracle(
                "/oracles/strategy-portfolio-v5-derivative.json");
        ArrayNode actual = MAPPER.createArrayNode();
        for (JsonNode item : requests) {
            ObjectNode row = object();
            try {
                JsonNode value = item.path("name").asText().equals("simulateCryptoPortfolio")
                        ? StrategyPortfolioV5.simulateCryptoPortfolio(item.path("signals"), item.path("policy"))
                        : StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(item.path("signals"), item.path("policy"));
                row.put("ok", true).set("value", value);
            } catch (RuntimeException error) { row.put("ok", false).put("error", error.getMessage()); }
            actual.add(row);
        }
        assertCanonical(actual, expected);
    }

    @Test
    void legacyV3EquityCapAndMaxAbsoluteMarginalRiskMatchNode() throws Exception {
        ArrayNode capMarks = MAPPER.createArrayNode();
        ArrayNode capSignals = MAPPER.createArrayNode();
        for (String asset : List.of("btc", "eth")) {
            String symbol = asset.toUpperCase() + "USDT";
            capMarks.add(mark(1, 100, asset, "cap", symbol));
            capMarks.add(mark(2, 100, asset, "cap", symbol));
            ObjectNode instrument = spotInstrument().put("venue", "cap").put("symbol", symbol);
            ObjectNode signal = object().put("signal_id", "cap-" + asset).set("instrument", instrument);
            signal.put("asset", asset).put("direction", "long").put("entry_time", 1)
                    .put("exit_time", 2).put("entry_price", 100).put("exit_price", 100)
                    .put("quantity", 1);
            capSignals.add(signal);
        }
        ObjectNode capPolicy = object().put("authoritative", true).put("initial_equity", 100)
                .put("collateral_cap", 1_000).put("total_concurrency", 2)
                .put("max_mark_gap_ms", 10);
        capPolicy.set("marks", capMarks);

        int[] btc = {100, 101, 102, 101, 103, 104, 102, 105};
        double[] eth = {100, 100.5, 101, 100.4, 101.5, 102, 101.2, 103};
        ArrayNode riskMarks = MAPPER.createArrayNode();
        for (int index = 0; index < btc.length; index++) {
            riskMarks.add(mark(index + 1, btc[index], "btc", "fixture", "BTCUSDT"));
            riskMarks.add(mark(index + 1, eth[index], "eth", "fixture", "ETHUSDT"));
        }
        ArrayNode riskSignals = MAPPER.createArrayNode();
        for (Object[] row : List.of(
                new Object[]{"btc", 100, 105},
                new Object[]{"eth", 100, 103})) {
            String asset = (String) row[0];
            ObjectNode instrument = spotInstrument().put("asset", asset).put("venue", "fixture")
                    .put("symbol", asset.toUpperCase() + "USDT");
            ObjectNode signal = object().put("signal_id", asset).set("instrument", instrument);
            signal.put("asset", asset).put("direction", "long").put("entry_time", 1)
                    .put("exit_time", 8).put("entry_price", (int) row[1])
                    .put("exit_price", (int) row[2]).put("quantity", 1);
            riskSignals.add(signal);
        }
        ObjectNode riskPolicy = object().put("authoritative", true).put("advanced_risk", true)
                .put("initial_equity", 1_000).put("max_mark_gap_ms", 10)
                .put("stress_window_bars", 3)
                .put("stress_correlation_selection", "MAX_ABSOLUTE");
        riskPolicy.set("marks", riskMarks);

        ArrayNode requests = MAPPER.createArrayNode()
                .add(request("simulateLinearMarkToMarketPortfolio", capSignals, capPolicy))
                .add(request("simulateLinearMarkToMarketPortfolio", riskSignals, riskPolicy));
        ArrayNode expected = frozenOracle(
                "/oracles/strategy-portfolio-v5-cap-risk.json");
        ArrayNode actual = MAPPER.createArrayNode();
        for (JsonNode item : requests) {
            ObjectNode result = object();
            try {
                result.put("ok", true).set("value",
                        StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(
                                item.path("signals"), item.path("policy")));
            } catch (RuntimeException error) {
                result.put("ok", false).put("error", error.getMessage());
            }
            actual.add(result);
        }
        assertCanonical(actual, expected);
        JsonNode cap = actual.path(0).path("value");
        assertThat(cap.path("accepted_signals")).hasSize(1);
        assertThat(cap.path("rejected_signals").path(0).path("cap_reason").asText())
                .isEqualTo("AVAILABLE_EQUITY_CAP");
        assertThat(cap.path("rejected_signals").path(0).path("detail")
                .path("available_equity").asDouble()).isEqualTo(100);
        JsonNode risk = actual.path(1).path("value");
        assertThat(risk.path("marginal_risk_contribution").path("status").asText())
                .isEqualTo("MEASURED");
        assertThat(risk.path("marginal_risk_contribution")
                .path("component_sum_matches_portfolio").asBoolean()).isTrue();
        assertThat(risk.path("policy").path("stress_correlation_selection").asText())
                .isEqualTo("MAX_ABSOLUTE");
    }

    private static ObjectNode spotInstrument() { return object().put("asset_class", "crypto").put("instrument_type", "spot").put("asset", "btc"); }

    private static ObjectNode perpetualInstrument() { return object().put("asset_class", "crypto").put("instrument_type", "perpetual").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("collateral", "USDT").put("funding", true); }

    private static ArrayNode legacySignals() {
        ObjectNode signal = object().put("signal_id", "legacy-1").set("instrument", spotInstrument());
        signal.put("entry_time", "2026-01-01T00:00:00Z").put("exit_time", "2026-01-01T01:00:00Z").put("direction", "long").put("notional", 100).put("collateral_used", 100).put("net_pnl", 10);
        return MAPPER.createArrayNode().add(signal);
    }

    private static ArrayNode strictSignals() {
        ObjectNode signal = object().put("signal_id", "strict-1").set("instrument", spotInstrument());
        signal.put("entry_time", "2026-01-01T00:00:00Z").put("exit_time", "2026-01-01T01:00:00Z").put("direction", "long").put("entry_price", 100).put("exit_price", 110).put("quantity", 1);
        return MAPPER.createArrayNode().add(signal);
    }

    private static ObjectNode strictPolicy() {
        long first = 1_767_225_600_000L;
        return object().put("initial_equity", 1_000).set("marks", MAPPER.createArrayNode()
                .add(object().put("asset", "btc").put("time", first).put("price", 100))
                .add(object().put("asset", "btc").put("time", first + 1_800_000).put("price", 102))
                .add(object().put("asset", "btc").put("time", first + 3_600_000).put("price", 110)));
    }

    private static ObjectNode derivativeInstrument() { return object().put("asset_class", "crypto").put("instrument_type", "perpetual").put("asset", "btc").put("venue", "binance").put("symbol", "BTCUSDT").put("collateral", "USDT").put("funding", true).put("contract_multiplier", 1); }

    private static ArrayNode derivativeSignals() {
        long t = 1_767_225_600_000L; ObjectNode signal = object().put("signal_id", "deriv-1").set("instrument", derivativeInstrument());
        signal.put("entry_time", t).put("exit_time", t + 7_200_000).put("direction", "short").put("entry_price", 100).put("exit_price", 90).put("quantity", 1).put("margin_mode", "cross").put("leverage", 2).put("collateral_used", 100).put("maintenance_margin_ratio", .01);
        signal.putArray("funding_settlements").addObject().put("time", t + 3_600_000).put("amount", 2).put("event_id", "fund-1").put("source", "exchange").put("venue", "binance").put("instrument", "BTCUSDT");
        return MAPPER.createArrayNode().add(signal);
    }

    private static ArrayNode derivativeSignalsWithBadFunding() {
        ArrayNode result = derivativeSignals(); ((ObjectNode) result.get(0).path("funding_settlements").get(0)).put("time", 1_767_225_500_000L); return result;
    }

    private static ObjectNode derivativePolicy(boolean authoritativeIdentity) {
        long t = 1_767_225_600_000L; ObjectNode policy = object().put("initial_equity", 1_000).put("max_mark_gap_ms", 7_200_000).put("require_authoritative_funding_identity", authoritativeIdentity);
        ArrayNode marks = policy.putArray("marks"); marks.add(mark(t, 100, "btc", "binance", "BTCUSDT")); marks.add(mark(t + 3_600_000, 98, "btc", "binance", "BTCUSDT")); marks.add(mark(t + 7_200_000, 90, "btc", "binance", "BTCUSDT")); return policy;
    }

    private static ObjectNode mark(long time, double price, String asset, String venue, String symbol) { return object().put("time", time).put("price", price).put("asset", asset).put("venue", venue).put("symbol", symbol); }

    private static ArrayNode cappedLegacySignals() {
        ArrayNode result = MAPPER.createArrayNode();
        result.add(legacySignal("a", "btc", "long", 100, 100, 10, 0));
        result.add(legacySignal("b", "btc", "short", 100, 100, 10, 1));
        result.add(legacySignal("c", "eth", "long", 100, 100, 5, 2));
        return result;
    }

    private static ObjectNode legacySignal(String id, String asset, String side, double notional, double collateral, double pnl, int offset) {
        long t = 1_767_225_600_000L + offset * 3_600_000L; ObjectNode signal = object().put("signal_id", id).set("instrument", spotInstrument()); ((ObjectNode) signal.path("instrument")).put("asset", asset);
        signal.put("entry_time", t).put("exit_time", t + 7_200_000).put("direction", side).put("notional", notional).put("collateral_used", collateral).put("net_pnl", pnl); return signal;
    }

    private static ObjectNode cappedLegacyPolicy() { return object().put("initial_equity", 1_000).put("per_asset_concurrency", 1).put("total_concurrency", 2).put("net_exposure_cap", 150).put("gross_exposure_cap", 150).put("collateral_cap", 150).put("leverage_cap", 2).set("acceptance", object().put("minimum_accepted_trades", 3)); }

    private static ObjectNode request(String name, JsonNode signals, JsonNode policy) { ObjectNode result = object().put("name", name); result.set("signals", signals); result.set("policy", policy); return result; }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }

    private static ArrayNode frozenOracle(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                StrategyPortfolioV5NodeOracleTest.class.getResourceAsStream(resource),
                "frozen strategy-portfolio oracle is missing: " + resource)) {
            return (ArrayNode) MAPPER.readTree(input);
        }
    }

    private static void assertCanonical(JsonNode actual, JsonNode expected) { assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected)); }

}
