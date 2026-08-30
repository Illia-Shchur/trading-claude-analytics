package com.tradinganalytics.infrastructure.security;

/** Bounded evidence-custody limits; all values are enforced independently. */
public record CustodyLimits(int maxFiles, long maxFileBytes, long maxTotalBytes) {
    public static final CustodyLimits DEFAULT = new CustodyLimits(2_048, 1_048_576, 16_777_216);

    public CustodyLimits {
        if (maxFiles < 1) {
            throw new CustodyException("evidence custody maxFiles must be a positive integer");
        }
        if (maxFileBytes < 1) {
            throw new CustodyException("evidence custody maxFileBytes must be a positive integer");
        }
        if (maxTotalBytes < 1) {
            throw new CustodyException("evidence custody maxTotalBytes must be a positive integer");
        }
    }
}
