package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;

/** Small algebraic checks for invariants that are independent of fixture shape. */
final class StrategyPortfolioV5PropertyTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final long T0 = 1_767_225_600_000L;

    @Property(tries = 40)
    void everyNonEmptyCryptoSpotAssetIsAccepted(@ForAll("assetNames") String asset) {
        ObjectNode instrument = object().put("asset_class", "crypto")
                .put("instrument_type", "spot").put("asset", asset);

        assertThat(StrategyPortfolioV5.validatePortfolioInstrument(instrument)).isTrue();
    }

    @Property(tries = 20)
    void nonCryptoAssetClassesAlwaysFailClosed(@ForAll("assetNames") String asset) {
        ObjectNode instrument = object().put("asset_class", "equity")
                .put("instrument_type", "spot").put("asset", asset);

        assertThatThrownBy(() -> StrategyPortfolioV5.validatePortfolioInstrument(instrument))
                .hasMessage("instrument must be crypto");
    }

    @Property(tries = 30)
    void realizedOnlyPortfolioEquityIsAffineInExplicitPnl(
            @ForAll @DoubleRange(min = -100.0, max = 100.0) double pnl) {
        ObjectNode signal = object().put("signal_id", "property-" + Double.doubleToLongBits(pnl))
                .set("instrument", spot("btc"));
        signal.put("entry_time", T0).put("exit_time", T0 + 3_600_000L)
                .put("direction", "long").put("notional", 100).put("collateral_used", 100)
                .put("net_pnl", pnl);

        ObjectNode result = StrategyPortfolioV5.simulateCryptoPortfolio(
                MAPPER.createArrayNode().add(signal), object().put("initial_equity", 1_000));

        assertThat(result.path("portfolio_equity").doubleValue()).isCloseTo(1_000.0 + pnl,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.path("net_pnl").doubleValue()).isCloseTo(pnl,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.path("accepted_signals")).hasSize(1);
        assertThat(result.path("closed_trades")).hasSize(1);
    }

    @Property(tries = 25)
    void strictLongPnlEqualsMarkDelta(
            @ForAll @IntRange(min = 10, max = 200) int entryPrice,
            @ForAll @IntRange(min = 1, max = 30) int priceDelta,
            @ForAll @IntRange(min = 1, max = 3) int quantity) {
        int exitPrice = entryPrice + priceDelta;
        ObjectNode signal = object().put("signal_id", "strict-property-" + entryPrice + "-" + priceDelta + "-" + quantity)
                .set("instrument", spot("btc"));
        signal.put("entry_time", T0).put("exit_time", T0 + 2_000L).put("direction", "long")
                .put("entry_price", entryPrice).put("exit_price", exitPrice).put("quantity", quantity);
        ObjectNode policy = object().put("initial_equity", 10_000).put("max_mark_gap_ms", 60_000);
        ArrayNode marks = policy.putArray("marks");
        marks.add(mark(T0, entryPrice));
        marks.add(mark(T0 + 1_000L, entryPrice + priceDelta / 2.0));
        marks.add(mark(T0 + 2_000L, exitPrice));

        ObjectNode result = StrategyPortfolioV5.simulateLinearMarkToMarketPortfolio(
                MAPPER.createArrayNode().add(signal), policy);

        double expected = (double) priceDelta * quantity;
        assertThat(result.path("pass").asBoolean()).isTrue();
        assertThat(result.path("portfolio_equity").doubleValue()).isEqualTo(10_000.0 + expected);
        assertThat(result.path("net_pnl").doubleValue()).isEqualTo(expected);
        assertThat(result.path("closed_trades").get(0).path("gross_pnl").doubleValue()).isEqualTo(expected);
    }

    @Provide
    Arbitrary<String> assetNames() {
        return Arbitraries.strings().withChars('a', 'b', 'c', 'd', 'e')
                .ofMinLength(1).ofMaxLength(8);
    }

    private static ObjectNode spot(String asset) {
        return object().put("asset_class", "crypto").put("instrument_type", "spot").put("asset", asset);
    }

    private static ObjectNode mark(long time, double price) {
        return object().put("time", time).put("asset", "btc").put("price", price);
    }

    private static ObjectNode object() {
        return MAPPER.createObjectNode();
    }
}
