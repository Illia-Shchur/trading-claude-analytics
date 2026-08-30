package com.tradinganalytics.infrastructure.security;

/** Raised when an untrusted filesystem or trust-boundary input fails closed. */
public final class CustodyException extends RuntimeException {
    public CustodyException(String message) {
        super(message);
    }

    public CustodyException(String message, Throwable cause) {
        super(message, cause);
    }
}
