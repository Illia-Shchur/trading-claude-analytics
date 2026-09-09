package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

class SwingBackfillPropertyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 100)
    void constantRangeBarsNeverProduceImpossibleLabels(@ForAll @IntRange(min = 301, max = 380) int count,
            @ForAll @DoubleRange(min = 1, max = 100_000) double price) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int index = 0; index < count; index++) rows.add(MAPPER.createObjectNode().put("time", index * SwingBackfill.BAR_MS)
                .put("open", price).put("high", price).put("low", price).put("close", price));
        assertThat(SwingBackfill.labelsForBars(rows)).isEmpty();
    }

    @Property(tries = 100)
    void labelsAlwaysResolveWithinDeclaredHorizon(@ForAll @IntRange(min = 301, max = 420) int count,
            @ForAll @DoubleRange(min = .01, max = 50) double range) {
        ArrayNode rows = MAPPER.createArrayNode(); double price = 100;
        for (int index = 0; index < count; index++) { price += index % 2 == 0 ? range / 10 : -range / 12;
            rows.add(MAPPER.createObjectNode().put("time", index * SwingBackfill.BAR_MS).put("open", price)
                    .put("high", price + range).put("low", Math.max(.001, price - range)).put("close", price)); }
        SwingBackfill.labelsForBars(rows).forEach(label -> {
            if (!label.path("long_favorable_bars").isNull())
                assertThat(label.path("long_favorable_bars").asInt()).isBetween(1, 180);
            if (!label.path("short_favorable_bars").isNull())
                assertThat(label.path("short_favorable_bars").asInt()).isBetween(1, 180);
            assertThat(label.path("early_window_bars").asInt()).isEqualTo(45);
            assertThat(label.path("long_early_capture").asBoolean())
                    .isEqualTo(!label.path("long_favorable_bars").isNull() && label.path("long_favorable_bars").asInt() <= 45);
            assertThat(label.path("short_early_capture").asBoolean())
                    .isEqualTo(!label.path("short_favorable_bars").isNull() && label.path("short_favorable_bars").asInt() <= 45);
        });
    }
}
