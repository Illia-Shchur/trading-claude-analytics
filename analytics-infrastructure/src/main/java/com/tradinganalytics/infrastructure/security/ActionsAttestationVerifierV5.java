package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Trusted-base verification dependency used by the complete prospective snapshot verifier. */
public final class ActionsAttestationVerifierV5 {
    private static final Pattern ED25519_PUBLIC_SPKI_PEM = Pattern.compile(
            "^-----BEGIN PUBLIC KEY-----\\n(?:[A-Za-z0-9+/=]{1,64}\\n)+"
                    + "-----END PUBLIC KEY-----\\n?$");

    public record Request(
            JsonNode attestation,
            JsonNode capture,
            JsonNode publication,
            String bytesSha256,
            long nowMs,
            String pinnedFingerprint,
            String apiReceiptSha256,
            String cycleReceiptSha256,
            String ledgerPriorHeadSha256,
            String ledgerNewHeadSha256,
            Long ledgerSequence,
            JsonNode trustedKeyRegistry,
            String trustedKeyRegistrySha256,
            String trustedKeyRegistryByteSha256) {}

    private ActionsAttestationVerifierV5() {}

    public static boolean verify(Request request) {
        JsonNode attestation = request.attestation();
        JsonNode capture = request.capture();
        JsonNode registry = request.trustedKeyRegistry();
        if (attestation == null
                || !"strategy-github-prospective-attestation/1".equals(
                        attestation.path("schema").asText())
                || !attestation.path("content_sha256").asText().equals(JsonHashes.ownHash(attestation))) {
            throw new CustodyException("GitHub attestation hash/schema is invalid");
        }
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(attestation);
        if (registry == null
                || !"strategy-github-attestation-key-registry/1".equals(
                        registry.path("schema").asText())
                || !"FROZEN".equals(registry.path("status").asText())
                || !registry.path("content_sha256").asText().equals(JsonHashes.ownHash(registry))
                || !JsonHashes.isSha256(request.trustedKeyRegistrySha256())
                || !registry.path("content_sha256").asText()
                        .equals(request.trustedKeyRegistrySha256())) {
            throw new CustodyException(
                    "separately frozen Actions attestation key registry is required");
        }
        if (capture == null
                || !registry.path("repository").asText().equals(capture.path("repository").asText())
                || !string(registry.get("repository_id")).equals(string(capture.get("repository_id")))
                || !"prospective-v5".equals(registry.path("environment").asText())
                || !registry.path("environment").asText()
                        .equals(capture.path("oidc_claims").path("environment").asText())) {
            throw new CustodyException(
                    "Actions attestation key registry is not bound to this repository, repository id, and environment");
        }
        if (!JsonHashes.isSha256(request.pinnedFingerprint())) {
            throw new CustodyException(
                    "externally pinned Actions attestation key fingerprint is required");
        }
        if (request.trustedKeyRegistryByteSha256() != null
                && !JsonHashes.isSha256(request.trustedKeyRegistryByteSha256())) {
            throw new CustodyException("trusted Actions key registry byte hash is invalid");
        }
        if (!attestation.path("trusted_key_registry_sha256").asText()
                    .equals(request.trustedKeyRegistrySha256())
                || (request.trustedKeyRegistryByteSha256() != null
                    && !attestation.path("trusted_key_registry_byte_sha256").asText()
                            .equals(request.trustedKeyRegistryByteSha256()))) {
            throw new CustodyException(
                    "Actions attestation is not bound to the trusted key registry bytes");
        }

        JsonNode trusted = null;
        for (JsonNode row : registry.path("keys")) {
            String publicPem = attestation.path("public_key_pem").asText();
            if (row.path("key_id").asText().equals(attestation.path("key_id").asText())
                    && "ACTIONS_ATTESTATION".equals(row.path("role").asText())
                    && row.path("public_key_pem").asText().equals(publicPem)
                    && row.path("fingerprint").asText().equals(publicKeyFingerprint(publicPem))) {
                trusted = row;
                break;
            }
        }
        if (trusted == null || !trusted.path("fingerprint").asText()
                .equals(request.pinnedFingerprint())) {
            throw new CustodyException(
                    "Actions attestation public key is not in the separately trusted registry");
        }
        long validFrom = instant(trusted.path("valid_from").asText());
        long validUntil = instant(trusted.path("valid_until").asText());
        if (validUntil <= validFrom || request.nowMs() < validFrom || request.nowMs() >= validUntil) {
            throw new CustodyException(
                    "Actions attestation key is outside its trusted validity window");
        }
        ObjectNode payload = attestation.deepCopy();
        payload.remove(List.of("signature", "content_sha256", "attestation_payload_sha256"));
        if (!attestation.path("attestation_payload_sha256").asText()
                    .equals(JsonHashes.canonicalSha256(payload))
                || !verifySignature(payload, attestation.path("signature").asText(),
                        attestation.path("public_key_pem").asText())) {
            throw new CustodyException("Actions attestation signature is invalid");
        }
        if (!attestation.path("protected").asBoolean(false)
                || !attestation.path("settings_capture_sha256").asText()
                        .equals(capture.path("content_sha256").asText())
                || !attestation.path("settings_capture_byte_sha256").asText()
                        .equals(request.bytesSha256())
                || request.apiReceiptSha256() == null
                || !attestation.path("api_receipt_sha256").asText()
                        .equals(request.apiReceiptSha256())
                || (request.cycleReceiptSha256() != null
                    && !attestation.path("cycle_receipt_sha256").asText()
                            .equals(request.cycleReceiptSha256()))
                || (request.ledgerPriorHeadSha256() != null
                    && !attestation.path("ledger_prior_head_sha256").asText()
                            .equals(request.ledgerPriorHeadSha256()))
                || (request.ledgerNewHeadSha256() != null
                    && !attestation.path("ledger_new_head_sha256").asText()
                            .equals(request.ledgerNewHeadSha256()))
                || (request.ledgerSequence() != null
                    && numericLong(attestation.get("ledger_sequence")) != request.ledgerSequence())) {
            throw new CustodyException(
                    "Actions attestation is not bound to the physical settings/API/cycle/ledger receipts");
        }

        JsonNode claims = capture.path("oidc_claims");
        String[] repositoryParts = capture.path("repository").asText("/").split("/", -1);
        String owner = repositoryParts.length > 0 ? repositoryParts[0] : "";
        String repository = repositoryParts.length > 1 ? repositoryParts[1] : "";
        String immutableSubject = claims.has("repository_owner_id")
                && !capture.path("repository_id").isNull()
                ? "repo:" + owner + "@" + string(claims.get("repository_owner_id"))
                    + "/" + repository + "@" + string(capture.get("repository_id"))
                    + ":environment:prospective-v5"
                : null;
        List<String> audience = new ArrayList<>();
        if (claims.path("aud").isArray()) claims.path("aud").forEach(row -> audience.add(row.asText()));
        else audience.add(claims.path("aud").asText());
        long iat = numericLongStrict(claims.get("iat"));
        long exp = numericLongStrict(claims.get("exp"));
        if (!capture.path("oidc_signature_verified").asBoolean(false)
                || !string(claims.get("repository_id")).equals(string(capture.get("repository_id")))
                || !claims.path("sub").asText().equals(capture.path("oidc_subject").asText())
                || immutableSubject == null || !claims.path("sub").asText().equals(immutableSubject)
                || !"https://token.actions.githubusercontent.com".equals(claims.path("iss").asText())
                || !audience.contains("strategy-v5")
                || iat == Long.MIN_VALUE || exp == Long.MIN_VALUE || exp <= iat || exp - iat > 15 * 60
                || !"prospective-v5".equals(claims.path("environment").asText())
                || claims.path("workflow_ref").asText().isEmpty()
                || claims.path("workflow_sha").asText().isEmpty()
                || !claims.has("run_id")
                || numericLong(claims.get("run_attempt")) == Long.MIN_VALUE) {
            throw new CustodyException("GitHub OIDC claims are not exact/freshly bound");
        }
        Map<String, Object> expected = new LinkedHashMapBuilder()
                .put("repository", capture.path("repository").asText())
                .put("repository_id", string(capture.get("repository_id")))
                .put("environment", claims.path("environment").asText())
                .put("workflow_ref", claims.path("workflow_ref").asText())
                .put("workflow_sha256", workflowShaDigest(claims.path("workflow_sha").asText()))
                .put("run_id", string(claims.get("run_id")))
                .put("run_attempt", numericLong(claims.get("run_attempt")))
                .put("oidc_subject", capture.path("oidc_subject").asText())
                .put("oidc_audience", audience.isEmpty() ? null : audience.get(0))
                .put("oidc_issuer", claims.path("iss").asText())
                .put("evidence_branch", nullableText(capture.get("evidence_branch")))
                .put("evidence_branch_head_sha256",
                        nullableText(capture.get("evidence_branch_head_sha256")))
                .values();
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            if (entry.getValue() != null
                    && !string(attestation.get(entry.getKey())).equals(String.valueOf(entry.getValue()))) {
                throw new CustodyException("Actions attestation " + entry.getKey() + " mismatch");
            }
        }
        long issued = instant(attestation.path("issued_at").asText());
        long expires = instant(attestation.path("expires_at").asText());
        long runId = numericLong(attestation.get("run_id"));
        long runAttempt = numericLongStrict(attestation.get("run_attempt"));
        if (runId < 1 || runAttempt < 1
                || !attestation.path("nonce").isTextual()
                || attestation.path("nonce").asText().length() < 16
                || issued > request.nowMs() || expires <= request.nowMs()
                || expires - issued > 15 * 60_000L
                || Math.floorDiv(issued, 1_000L) < iat
                || ceilDiv(expires, 1_000L) > exp) {
            throw new CustodyException("Actions attestation freshness/nonce is invalid");
        }
        if (request.publication() != null && !request.publication().isNull()
                && !attestation.path("nonce").asText()
                        .equals(string(request.publication().get("replay_nonce")))) {
            throw new CustodyException(
                    "Actions attestation nonce is not bound to publication replay");
        }
        return true;
    }

    private static boolean verifySignature(ObjectNode payload, String signature, String pem) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(pem));
            verifier.update(JsonHashes.canonicalBytes(payload));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception error) {
            return false;
        }
    }

    public static String publicKeyFingerprint(String pem) {
        try {
            if (!ED25519_PUBLIC_SPKI_PEM.matcher(pem).matches()) return null;
            PublicKey key = publicKey(pem);
            return "EdDSA".equalsIgnoreCase(key.getAlgorithm()) || "Ed25519".equalsIgnoreCase(key.getAlgorithm())
                    ? JsonHashes.sha256(pem.getBytes(StandardCharsets.UTF_8)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PublicKey publicKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static long instant(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException error) {
            return Long.MIN_VALUE;
        }
    }

    private static long numericLong(JsonNode value) {
        if (value == null || value.isNull()) return Long.MIN_VALUE;
        try {
            return new java.math.BigDecimal(value.isTextual() ? value.textValue() : value.asText())
                    .longValueExact();
        } catch (RuntimeException error) {
            return Long.MIN_VALUE;
        }
    }

    private static long numericLongStrict(JsonNode value) {
        return value != null && value.isIntegralNumber() ? value.longValue() : Long.MIN_VALUE;
    }

    private static long ceilDiv(long value, long divisor) {
        return Math.floorDiv(value + divisor - 1, divisor);
    }

    private static String workflowShaDigest(String value) {
        return JsonHashes.isSha256(value) ? value : JsonHashes.sha256(value);
    }

    private static String string(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        return value.asText();
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static final class LinkedHashMapBuilder {
        private final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        LinkedHashMapBuilder put(String key, Object value) { values.put(key, value); return this; }
        Map<String, Object> values() { return values; }
    }
}
