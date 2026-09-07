package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** A declared timestamp cannot make a completed OHLC price knowable before its bar ends. */
final class TradeLifecycleAvailabilityReviewTest {
    @Test
    void aBarrierCannotBecomeAvailableAtOpenFromAnEarlyTimestampClaim() {
        ObjectNode bar = bar().put("availability_time", "1970-01-01T00:00:00Z");
        try {
            assertThat(availability(bar, "BARRIER")).isGreaterThanOrEqualTo(60_000L);
        } catch (IllegalArgumentException acceptedRejection) {
            // Rejecting an inconsistent source timestamp is also fail-closed.
        }
    }

    @Test
    void aBinanceInclusiveCloseTimestampMapsToTheCompletedBoundary() {
        assertThat(availability(bar().put("close_time", "1970-01-01T00:00:59.999Z"), "TIME_STOP_CLOSE"))
                .isEqualTo(60_000L);
    }

    @Test
    void laterPublicationRemainsLaterThanBarCompletion() {
        assertThat(availability(bar().put("availability_time", "1970-01-01T00:01:30Z"), "BARRIER"))
                .isEqualTo(90_000L);
    }

    @Test
    void anOpenGapUsesOpenInformationWithoutWaitingForTheClosingPrice() {
        assertThat(availability(bar().put("availability_time", "1970-01-01T00:01:30Z"), "GAP_OPEN"))
                .isZero();
    }

    private static ObjectNode bar() { return JsonHashes.mapper().createObjectNode().put("__time", 0L); }

    private static long availability(ObjectNode bar, String type) {
        try {
            Method method = TradeLifecycleV5.class.getDeclaredMethod("fillAvailabilityTime", ObjectNode.class,
                    String.class, long.class);
            method.setAccessible(true); return (long) method.invoke(null, bar, type, 60_000L);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Update reviewer adapter after lifecycle extraction", error);
        }
    }
}
