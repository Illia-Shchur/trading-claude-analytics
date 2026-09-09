package com.tradinganalytics.cli;

/** Shared CLI error text policies. */
final class CliMessages {
    private CliMessages() {}

    static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return message(current);
    }
}
