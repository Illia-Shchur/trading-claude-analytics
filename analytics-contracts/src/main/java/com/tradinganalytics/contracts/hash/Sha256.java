package com.tradinganalytics.contracts.hash;

import com.tradinganalytics.contracts.json.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Deterministic SHA-256 helpers for raw and canonical JSON content. */
public final class Sha256 {
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern LOWERCASE_HEX_DIGEST = Pattern.compile("^[a-f0-9]{64}$");

    private Sha256() {
    }

    public static byte[] digest(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("SHA-256 input must not be null");
        }
        return newDigest().digest(bytes);
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(digest(bytes));
    }

    public static String hex(String utf8Text) {
        if (utf8Text == null) {
            throw new IllegalArgumentException("SHA-256 input must not be null");
        }
        return hex(utf8Text.getBytes(StandardCharsets.UTF_8));
    }

    public static String canonicalHex(Object value) {
        return hex(CanonicalJson.canonicalBytes(value));
    }

    public static String canonicalJsonHex(Object value) {
        return hex(CanonicalJson.canonicalJsonBytes(value));
    }

    /** Mirrors the Node helpers: strings and buffers hash raw; other values hash canonically. */
    public static String hash(Object value) {
        if (value instanceof byte[] bytes) {
            return hex(bytes);
        }
        if (value instanceof String text) {
            return hex(text);
        }
        return canonicalHex(value);
    }

    public static boolean isLowercaseHexDigest(String value) {
        return value != null && LOWERCASE_HEX_DIGEST.matcher(value).matches();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("This JVM does not provide SHA-256", exception);
        }
    }
}
