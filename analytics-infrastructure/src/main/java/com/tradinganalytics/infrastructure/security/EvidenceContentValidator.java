package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class EvidenceContentValidator {
    static final String PUBLIC_REGISTRY = "v5-attestation-key-registry.json";

    private static final Pattern FORBIDDEN_FILENAME = Pattern.compile(
            "(?:^|[-_.])(private|secret|key|pem|raw|der|crt|p12|bin)(?:[-_.]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_PEM_BEGIN = Pattern.compile(
            "-----BEGIN\\s+[^\\r\\n-]+-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN_REGISTRY_MARKER = Pattern.compile(
            "-----BEGIN\\s+[^\\r\\n-]+-----|-----END\\s+[^\\r\\n-]+-----|"
                    + "(?:OPENSSH|RSA|EC|DSA|PKCS8|ENCRYPTED)?\\s*PRIVATE\\s+KEY|\\bSECRET\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT_PUBLIC_PEM = Pattern.compile(
            "^-----BEGIN PUBLIC KEY-----\\n(?:[A-Za-z0-9+/=]{1,64}\\n)+-----END PUBLIC KEY-----\\n?$");
    private static final byte[] ED25519_SPKI_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private EvidenceContentValidator() {}

    static void validateFilename(String path, String label) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new CustodyException(label + " contains non-JSON/raw evidence: " + path);
        }
        if (!name.equals(PUBLIC_REGISTRY) && FORBIDDEN_FILENAME.matcher(name).find()) {
            throw new CustodyException(label + " contains private/key/raw evidence: " + path);
        }
    }

    static JsonNode validateBytes(byte[] bytes, String label, String path) {
        for (byte value : bytes) {
            if (value == 0) {
                throw new CustodyException(label + " contains raw/NUL bytes: " + path);
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException error) {
            throw new CustodyException(label + " contains invalid UTF-8/raw evidence: " + path, error);
        }
        String normalized = path.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        boolean registry = filename.equals(PUBLIC_REGISTRY);
        JsonNode value = JsonHashes.parse(bytes, label + " artifact " + path);
        if (registry) {
            validateRegistry(value, label, path, new ArrayList<>());
        } else {
            boolean attestation = filename.equals("v5-actions-attestation.json")
                    && "strategy-github-prospective-attestation/1"
                            .equals(value.path("schema").asText());
            validateEvidenceStrings(value, label, path, attestation, false, new ArrayList<>());
        }
        return value;
    }

    /**
     * Public attestation keys are intentionally carried in the signed receipt so the
     * verifier can bind them to the frozen registry.  Everything else that looks like
     * PEM remains forbidden, including JSON-escaped markers.
     */
    private static void validateEvidenceStrings(JsonNode node, String label, String path,
                                                boolean attestation,
                                                boolean publicKeyField, List<Object> location) {
        if (node == null) return;
        if (node.isTextual() && ANY_PEM_BEGIN.matcher(node.textValue()).find()) {
            boolean allowed = attestation && publicKeyField && isExactPublicPem(node.textValue());
            if (!allowed) {
                throw new CustodyException(label + " contains key/PEM material: " + path);
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                List<Object> next = new ArrayList<>(location);
                next.add(index);
                validateEvidenceStrings(node.get(index), label, path, attestation,
                        false, next);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (ANY_PEM_BEGIN.matcher(entry.getKey()).find()) {
                    throw new CustodyException(label + " contains key/PEM material: " + path);
                }
                List<Object> next = new ArrayList<>(location);
                next.add(entry.getKey());
                validateEvidenceStrings(entry.getValue(), label, path, attestation,
                        attestation && location.isEmpty() && "public_key_pem".equals(entry.getKey()), next);
            });
        }
    }

    private static boolean isExactPublicPem(String value) {
        if (!EXACT_PUBLIC_PEM.matcher(value).matches()) return false;
        try {
            validateEd25519PublicPem(value, "evidence", "attestation");
            return true;
        } catch (CustodyException invalid) {
            return false;
        }
    }

    private static void validateRegistry(JsonNode node, String label, String path, List<Object> location) {
        if (node.isTextual()) {
            String text = node.textValue();
            boolean publicKeyField = location.size() == 3
                    && "keys".equals(location.get(0))
                    && location.get(1) instanceof Integer
                    && "public_key_pem".equals(location.get(2));
            if (publicKeyField) {
                validateEd25519PublicPem(text, label, path);
            } else if (FORBIDDEN_REGISTRY_MARKER.matcher(text).find()) {
                throw new CustodyException(label
                        + " registry contains key/secret material outside public_key_pem: " + path);
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                List<Object> next = new ArrayList<>(location);
                next.add(index);
                validateRegistry(node.get(index), label, path, next);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (FORBIDDEN_REGISTRY_MARKER.matcher(entry.getKey()).find()) {
                    throw new CustodyException(label
                            + " registry contains key/secret material outside public_key_pem: " + path);
                }
                List<Object> next = new ArrayList<>(location);
                next.add(entry.getKey());
                validateRegistry(entry.getValue(), label, path, next);
            });
        }
    }

    private static void validateEd25519PublicPem(String pem, String label, String path) {
        if (!EXACT_PUBLIC_PEM.matcher(pem).matches()) {
            throw new CustodyException(label + " registry public_key_pem is not an exact public PEM: " + path);
        }
        try {
            String payload = pem.replace("-----BEGIN PUBLIC KEY-----\n", "")
                    .replace("-----END PUBLIC KEY-----\n", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\n", "");
            byte[] encoded = Base64.getDecoder().decode(payload);
            if (encoded.length != 44) {
                throw new IllegalArgumentException("wrong Ed25519 SPKI length");
            }
            for (int index = 0; index < ED25519_SPKI_PREFIX.length; index++) {
                if (encoded[index] != ED25519_SPKI_PREFIX[index]) {
                    throw new IllegalArgumentException("wrong Ed25519 SPKI algorithm");
                }
            }
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            if (key.getEncoded().length != 44) {
                throw new IllegalArgumentException("invalid Ed25519 public key");
            }
        } catch (Exception error) {
            throw new CustodyException(label
                    + " registry public_key_pem is not Ed25519 public SPKI: " + path, error);
        }
    }
}
