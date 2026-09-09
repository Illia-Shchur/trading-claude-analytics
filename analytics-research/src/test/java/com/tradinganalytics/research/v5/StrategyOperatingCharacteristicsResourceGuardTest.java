package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deterministic checks for the bounded D resource guard. */
final class StrategyOperatingCharacteristicsResourceGuardTest {
    @Test
    void parsesLinuxVmRssWithoutFallingBackToPs() {
        String status = "Name:\tjava\nVmPeak:\t999999 kB\nVmRSS:\t1234 kB\nThreads:\t4\n";

        assertThat(StrategyOperatingCharacteristicsV4.parseLinuxRssBytes(status))
                .isEqualTo(1_263_616L);
        assertThat(StrategyOperatingCharacteristicsV4.parseLinuxRssBytes("Name:\tjava\n"))
                .isEqualTo(-1L);
    }

    @Test
    void checksDeadlineBeforeSamplingRss() {
        AtomicInteger probes = new AtomicInteger();
        StrategyOperatingCharacteristicsV4.ResourceGuard guard =
                new StrategyOperatingCharacteristicsV4.ResourceGuard(10L, Long.MAX_VALUE,
                        () -> 11L, () -> { probes.incrementAndGet(); return 1L; });

        assertThat(guard.violation()).isEqualTo("RESOURCE_WALL_DEADLINE_EXCEEDED");
        assertThat(probes).hasValue(0);
    }

    @Test
    void rejectsAnRssLimitAndRecordsThePeakSample() {
        StrategyOperatingCharacteristicsV4.ResourceGuard guard =
                new StrategyOperatingCharacteristicsV4.ResourceGuard(10_000L, 1_023L,
                        () -> 1L, () -> 1_024L);

        assertThat(guard.violation()).isEqualTo("RESOURCE_RSS_BUDGET_EXCEEDED");
        assertThat(guard.peakRssBytes()).isEqualTo(1_024L);
    }

    @Test
    void reportsUnavailableRssInsteadOfTreatingItAsZero() {
        StrategyOperatingCharacteristicsV4.ResourceGuard guard =
                new StrategyOperatingCharacteristicsV4.ResourceGuard(10_000L, Long.MAX_VALUE,
                        () -> 1L, () -> -1L);

        assertThat(guard.violation()).isEqualTo("RESOURCE_RSS_UNAVAILABLE");
        assertThat(guard.peakRssBytes()).isZero();
    }

    @Test
    void throttlesRssSamplingButKeepsDeadlineChecksCheap() {
        AtomicInteger probes = new AtomicInteger();
        long[] now = {1L};
        StrategyOperatingCharacteristicsV4.ResourceGuard guard =
                new StrategyOperatingCharacteristicsV4.ResourceGuard(10_000_000_000L, Long.MAX_VALUE,
                        () -> now[0], () -> { probes.incrementAndGet(); return 100L; });

        assertThat(guard.violation()).isNull();
        assertThat(guard.violation()).isNull();
        assertThat(probes).hasValue(1);
        now[0] = 1_000_000_002L;
        assertThat(guard.violation()).isNull();
        assertThat(probes).hasValue(2);
    }
}
