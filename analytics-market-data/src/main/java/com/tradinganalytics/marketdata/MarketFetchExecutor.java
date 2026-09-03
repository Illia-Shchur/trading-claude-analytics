package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Executes independent endpoint calls while preserving declaration-order errors. */
final class MarketFetchExecutor {
    private MarketFetchExecutor() {
    }

    static Map<String, JsonNode> run(Map<String, Task> tasks, List<String> errors) {
        Map<String, JsonNode> output = Collections.synchronizedMap(new LinkedHashMap<>());
        if (tasks.isEmpty()) {
            return output;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<JsonNode>> futures = new LinkedHashMap<>();
            tasks.forEach((key, task) -> futures.put(key, executor.submit(() -> task.supplier().get())));
            // Futures are consumed in declaration order. This makes the published error
            // array deterministic even when network calls complete out of order.
            futures.forEach((key, future) -> {
                try {
                    JsonNode value = future.get();
                    if (value != null) {
                        output.put(key, value);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    errors.add(tasks.get(key).label() + ": interrupted");
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    errors.add(tasks.get(key).label() + ": " + message(cause));
                }
            });
        }
        return output;
    }

    static void add(Map<String, Task> tasks, String key, String label, ThrowingSupplier<? extends JsonNode> supplier) {
        if (supplier != null) {
            tasks.put(key, new Task(label, supplier));
        }
    }

    static String message(Throwable throwable) {
        String value = throwable == null ? null : throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    record Task(String label, ThrowingSupplier<? extends JsonNode> supplier) {
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
