package com.tradinganalytics.contracts.json;

/** Raised when input is not valid strict RFC 8259 JSON. */
public final class StrictJsonException extends IllegalArgumentException {
    private final String label;
    private final long offset;
    private final String detail;

    StrictJsonException(String label, long offset, String detail, Throwable cause) {
        super(label + ": invalid strict JSON at offset " + offset + ": " + detail, cause);
        this.label = label;
        this.offset = offset;
        this.detail = detail;
    }

    StrictJsonException(String label, String message) {
        super(label + ": " + message);
        this.label = label;
        this.offset = -1;
        this.detail = message;
    }

    public String label() {
        return label;
    }

    public long offset() {
        return offset;
    }

    public String detail() {
        return detail;
    }
}
