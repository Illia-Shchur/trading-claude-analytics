package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Mints identity-based lifecycle trust tokens after reopening exact physical JSON receipts.
 * Every use reopens the files, so retained tokens fail closed after byte mutation.
 */
public final class LifecycleTrustService {
    public static final String SCHEMA = "strategy-v5-lifecycle-trust/1";
    public static final String LIFECYCLE_TRUST_SCHEMA = SCHEMA;
    public record ReceiptReference(
            String path,
            String contentSha256,
            String byteSha256,
            Long bytes,
            String rowsSha256,
            String schema) {
        public ReceiptReference {
            if (path == null || path.isBlank()) {
                throw new CustodyException("lifecycle trust receipt path is required");
            }
            contentSha256 = JsonHashes.requireSha256(contentSha256, "receipt content_sha256");
            byteSha256 = JsonHashes.requireSha256(byteSha256, "receipt byte_sha256");
            if (bytes != null && bytes < 0) {
                throw new CustodyException("receipt bytes is invalid");
            }
            if (rowsSha256 != null) {
                rowsSha256 = JsonHashes.requireSha256(rowsSha256, "receipt rows_sha256");
            }
        }
    }

    public record ReopenedTrust(
            String schema,
            int version,
            boolean fixtureOnly,
            String provenance,
            String bundleSha256,
            String rootReference,
            Map<String, ReceiptReference> receipts,
            Map<String, JsonNode> values,
            JsonNode lineage) {
        public ReopenedTrust {
            receipts = Map.copyOf(receipts);
            values = immutableValueCopies(values);
            lineage = lineage.deepCopy();
        }

        @Override public Map<String, JsonNode> values() { return immutableValueCopies(values); }
        @Override public JsonNode lineage() { return lineage.deepCopy(); }
    }

    public static final class Token {
        private final String rootReference;
        private final Map<String, ReceiptReference> receipts;
        private final JsonNode lineage;
        private final String bundleSha256;
        private final String contentSha256;

        private Token(
                String rootReference,
                Map<String, ReceiptReference> receipts,
                JsonNode lineage,
                String bundleSha256) {
            this.rootReference = rootReference;
            this.receipts = Map.copyOf(receipts);
            this.lineage = lineage.deepCopy();
            this.bundleSha256 = bundleSha256;
            this.contentSha256 = JsonHashes.canonicalSha256(tokenNode(this, false));
        }

        public String schema() { return SCHEMA; }
        public int version() { return 1; }
        public boolean fixtureOnly() { return false; }
        public String provenance() { return "AUTHORITATIVE"; }
        public String rootReference() { return rootReference; }
        public Map<String, ReceiptReference> receipts() { return receipts; }
        public JsonNode lineage() { return lineage.deepCopy(); }
        public String bundleSha256() { return bundleSha256; }
        public String contentSha256() { return contentSha256; }
    }

    @FunctionalInterface
    public interface VerifiedLoaderReopener {
        LoaderReopen reopen();
    }

    public record LoaderReopen(
            Map<String, ReceiptReference> receipts,
            Map<String, JsonNode> values) {
        public LoaderReopen {
            receipts = receipts == null ? null : Map.copyOf(receipts);
            values = values == null ? null : immutableValueCopies(values);
        }

        @Override public Map<String, JsonNode> values() {
            return values == null ? null : immutableValueCopies(values);
        }
    }

    private record State(
            Path root,
            Map<String, ReceiptReference> receipts,
            JsonNode lineage,
            Map<String, JsonNode> initialValues,
            VerifiedLoaderReopener reopener) {}
    private record Opened(JsonNode value, ReceiptReference receipt) {}

    private final Map<Token, State> trusted = Collections.synchronizedMap(new WeakHashMap<>());

    public static Path resolveLifecyclePhysicalPathV5(Path root, String candidate, String label) {
        return PathConfinement.resolve(root, candidate, label, PathConfinement.ExpectedType.FILE)
                .absolute();
    }

    public Token open(
            Path root,
            String rootReference,
            Map<String, ReceiptReference> receiptReferences,
            Object lineage) {
        return open(root, rootReference, receiptReferences, lineage, true);
    }

    public Token openLifecycleTrustV5(
            Path root,
            String rootReference,
            Map<String, ReceiptReference> receiptReferences,
            Object lineage,
            boolean requireBars) {
        return open(root, rootReference, receiptReferences, lineage, requireBars);
    }

    public Token open(
            Path root,
            String rootReference,
            Map<String, ReceiptReference> receiptReferences,
            Object lineage,
            boolean requireBars) {
        Path realRoot = PathConfinement.requireRealDirectory(root, "lifecycle trust physical root");
        if (rootReference != null && rootReference.isBlank()) {
            throw new CustodyException("lifecycle trust rootReference must be a non-empty portable label or null");
        }
        Map<String, ReceiptReference> normalized = normalizeRoles(receiptReferences, requireBars);
        Map<String, ReceiptReference> openedReceipts = new LinkedHashMap<>();
        for (Map.Entry<String, ReceiptReference> entry : normalized.entrySet()) {
            openedReceipts.put(entry.getKey(), openReceipt(realRoot, entry.getValue(), entry.getKey()).receipt());
        }
        JsonNode normalizedLineage = normalizeLineage(lineage);
        String bundle = bundleDigest(openedReceipts, normalizedLineage);
        Token token = new Token(rootReference, openedReceipts, normalizedLineage, bundle);
        trusted.put(token, new State(realRoot, Map.copyOf(openedReceipts), normalizedLineage.deepCopy(),
                Map.of(), null));
        return token;
    }

    /**
     * Mints the non-serializable lifecycle token whose physical reopen is owned by an
     * already verified Parquet/metadata loader.
     */
    public Token createVerifiedLoaderLifecycleTrustV5(
            String rootReference,
            Map<String, ReceiptReference> receiptReferences,
            Map<String, JsonNode> values,
            Object lineage,
            VerifiedLoaderReopener reopener) {
        if (reopener == null) {
            throw failure("verified loader lifecycle capability requires a reopen verifier");
        }
        Map<String, ReceiptReference> normalized = normalizeVerifiedLoaderRoles(receiptReferences);
        if (rootReference != null && rootReference.isBlank()) {
            throw failure("verified loader rootReference must be a portable label or null");
        }
        Map<String, JsonNode> initialValues = validateLoaderValues(
                values, normalized, true, "verified loader ", "");
        JsonNode normalizedLineage = normalizeLineage(lineage);
        String bundle = bundleDigest(normalized, normalizedLineage);
        Token token = new Token(rootReference, normalized, normalizedLineage, bundle);
        trusted.put(token, new State(null, normalized, normalizedLineage.deepCopy(),
                initialValues, reopener));
        return token;
    }

    public ReopenedTrust reopen(Token token) {
        return reopen(token, Map.of());
    }

    public ReopenedTrust reopenLifecycleTrustV5(
            Token token, Map<String, JsonNode> suppliedValues) {
        return reopen(token, suppliedValues);
    }

    public ReopenedTrust reopen(Token token, Map<String, JsonNode> suppliedValues) {
        State state = assertToken(token);
        if (state.reopener() != null) {
            return reopenVerifiedLoader(token, state, suppliedValues);
        }
        Map<String, ReceiptReference> currentReceipts = new LinkedHashMap<>();
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (Map.Entry<String, ReceiptReference> entry : state.receipts().entrySet()) {
            Opened opened = openReceipt(state.root(), entry.getValue(), entry.getKey());
            ReceiptReference expected = entry.getValue();
            ReceiptReference current = opened.receipt();
            if (!current.path().equals(expected.path())
                    || !current.contentSha256().equals(expected.contentSha256())
                    || !current.byteSha256().equals(expected.byteSha256())) {
                throw failure(entry.getKey() + " receipt identity changed");
            }
            currentReceipts.put(entry.getKey(), current);
            values.put(entry.getKey(), opened.value());
        }
        if (!bundleDigest(currentReceipts, token.lineage).equals(token.bundleSha256)) {
            throw failure("physical receipt set changed");
        }
        if (suppliedValues != null) {
            for (Map.Entry<String, JsonNode> supplied : suppliedValues.entrySet()) {
                ReceiptReference reference = currentReceipts.get(supplied.getKey());
                if (reference == null) {
                    throw failure(supplied.getKey() + " input is present but has no physical receipt");
                }
                JsonNode suppliedValue = supplied.getValue();
                String actual = reference.rowsSha256() == null
                        ? JsonHashes.canonicalSha256(suppliedValue)
                        : JsonHashes.canonicalSha256(requireRows(suppliedValue, supplied.getKey()));
                String expected = reference.rowsSha256() == null
                        ? reference.contentSha256() : reference.rowsSha256();
                if (!actual.equals(expected)) {
                    throw failure(supplied.getKey() + " input does not match its physical receipt");
                }
            }
        }
        return new ReopenedTrust(SCHEMA, 1, false, "AUTHORITATIVE", token.bundleSha256,
                token.rootReference, currentReceipts, values, token.lineage);
    }

    private ReopenedTrust reopenVerifiedLoader(
            Token token, State state, Map<String, JsonNode> suppliedValues) {
        LoaderReopen reopened;
        try {
            reopened = state.reopener().reopen();
        } catch (RuntimeException error) {
            throw failure("verified loader physical receipt reopen failed: " + error.getMessage());
        }
        if (reopened == null || reopened.values() == null || reopened.receipts() == null) {
            throw failure("verified loader reopen returned an incomplete capability");
        }
        Map<String, ReceiptReference> currentReceipts = new LinkedHashMap<>();
        for (Map.Entry<String, ReceiptReference> expectedEntry : state.receipts().entrySet()) {
            String role = expectedEntry.getKey();
            ReceiptReference current = reopened.receipts().get(role);
            if (current == null) {
                throw failure(role + " receipt reference is required");
            }
            ReceiptReference expected = expectedEntry.getValue();
            if (!current.path().equals(expected.path())
                    || !current.contentSha256().equals(expected.contentSha256())
                    || !current.byteSha256().equals(expected.byteSha256())
                    || !Objects.equals(current.bytes(), expected.bytes())
                    || !Objects.equals(current.rowsSha256(), expected.rowsSha256())) {
                throw failure(role + " verified loader receipt identity changed");
            }
            currentReceipts.put(role, current);
        }
        Map<String, JsonNode> currentValues = validateLoaderValues(
                reopened.values(), currentReceipts, false, "", " verified loader");
        if (!bundleDigest(currentReceipts, token.lineage).equals(token.bundleSha256)) {
            throw failure("verified loader physical receipt set changed");
        }
        validateSupplied(currentReceipts, suppliedValues);
        return new ReopenedTrust(SCHEMA, 1, false, "AUTHORITATIVE", token.bundleSha256,
                token.rootReference, currentReceipts, currentValues, token.lineage);
    }

    public boolean isTrusted(Token token) {
        return token != null && trusted.containsKey(token);
    }

    public boolean isLifecycleTrustV5(Token token) {
        return isTrusted(token);
    }

    public String receiptHash(Token token, String role) {
        assertToken(token);
        ReceiptReference receipt = token.receipts.get(role);
        if (receipt == null) {
            throw failure("unknown lifecycle trust receipt role " + role);
        }
        return receipt.contentSha256();
    }

    public String lifecycleTrustReceiptHashV5(Token token, String role) {
        return receiptHash(token, role);
    }

    private State assertToken(Token token) {
        if (token == null || !trusted.containsKey(token)) {
            throw failure("production lifecycle requires a non-serializable physical trust token");
        }
        if (!token.contentSha256.equals(JsonHashes.canonicalSha256(tokenNode(token, false)))) {
            throw failure("trust token content hash is invalid");
        }
        State state = trusted.get(token);
        if (state == null || !token.bundleSha256.equals(bundleDigest(token.receipts, token.lineage))) {
            throw failure("trust token digest is invalid");
        }
        return state;
    }

    private static Opened openReceipt(Path root, ReceiptReference reference, String role) {
        PathConfinement.ResolvedPath physical = PathConfinement.resolve(
                root, reference.path(), "lifecycle trust " + role, PathConfinement.ExpectedType.FILE);
        byte[] bytes = PathConfinement.readSinglyLinkedFile(physical.absolute(), "lifecycle trust " + role);
        if (reference.bytes() != null && reference.bytes() != bytes.length) {
            throw failure(role + " byte length changed");
        }
        String byteHash = JsonHashes.sha256(bytes);
        if (!byteHash.equals(reference.byteSha256())) {
            throw failure(role + " bytes are missing or tampered");
        }
        JsonNode value = JsonHashes.parse(bytes, "lifecycle trust " + role);
        if (!value.isContainerNode()) {
            throw failure(role + " JSON value must be an object or array");
        }
        if (!JsonHashes.ownHash(value).equals(reference.contentSha256())) {
            throw failure(role + " content hash is missing or tampered");
        }
        if (reference.schema() != null
                && (!value.isObject() || !reference.schema().equals(value.path("schema").asText()))) {
            throw failure(role + " schema does not match its receipt");
        }
        if (reference.rowsSha256() != null
                && !JsonHashes.canonicalSha256(requireRows(value, role)).equals(reference.rowsSha256())) {
            throw failure(role + " physical row-set hash is missing or tampered");
        }
        ReceiptReference receipt = new ReceiptReference(
                physical.relative(), reference.contentSha256(), byteHash, (long) bytes.length,
                reference.rowsSha256(), reference.schema());
        return new Opened(value.deepCopy(), receipt);
    }

    private static Map<String, ReceiptReference> normalizeRoles(
            Map<String, ReceiptReference> receipts, boolean requireBars) {
        if (receipts == null) {
            throw failure("receipt references are required");
        }
        Map<String, ReceiptReference> output = new LinkedHashMap<>();
        output.put("contract_spec", first(receipts, "contract_spec", "contract", "contractSpec"));
        output.put("execution_model", first(receipts, "execution_model", "model", "executionModel"));
        output.put("capacity", first(receipts, "capacity", "liquidity", "capacity_model"));
        ReceiptReference bars = firstOptional(receipts, "bars", "execution_bars", "bar_path");
        if (requireBars && bars == null) {
            throw failure("execution bars receipt reference is required");
        }
        if (bars != null) output.put("bars", bars);
        ReceiptReference funding = firstOptional(receipts, "funding", "funding_events");
        ReceiptReference marks = firstOptional(receipts, "marks", "mark_bars");
        ReceiptReference hydration = firstOptional(receipts, "hydration", "opportunity_hydration");
        if (funding != null) output.put("funding", funding);
        if (marks != null) output.put("marks", marks);
        if (hydration != null) output.put("hydration", hydration);
        return output;
    }

    private static Map<String, ReceiptReference> normalizeVerifiedLoaderRoles(
            Map<String, ReceiptReference> receipts) {
        if (receipts == null) receipts = Map.of();
        Map<String, ReceiptReference> output = new LinkedHashMap<>();
        for (String role : List.of("contract_spec", "execution_model", "capacity", "bars")) {
            ReceiptReference reference = receipts.get(role);
            if (reference == null) {
                throw failure("verified loader " + role + " receipt is required");
            }
            if (reference.path().isBlank()) {
                throw failure("verified loader " + role + " receipt path is required");
            }
            output.put(role, reference);
        }
        for (String role : List.of("funding", "marks", "hydration")) {
            if (receipts.get(role) != null) output.put(role, receipts.get(role));
        }
        return Map.copyOf(output);
    }

    private static Map<String, JsonNode> validateLoaderValues(
            Map<String, JsonNode> values,
            Map<String, ReceiptReference> receipts,
            boolean initial,
            String prefix,
            String infix) {
        if (values == null) {
            throw failure("verified loader values must be an object");
        }
        Map<String, JsonNode> output = new LinkedHashMap<>();
        for (Map.Entry<String, ReceiptReference> entry : receipts.entrySet()) {
            String role = entry.getKey();
            if (!values.containsKey(role) || values.get(role) == null) {
                if (initial) throw failure("verified loader " + role + " value is missing");
                throw failure(role + " verified loader content changed");
            }
            JsonNode value = values.get(role);
            ReceiptReference reference = entry.getValue();
            if (reference.rowsSha256() != null) {
                JsonNode rows;
                try {
                    rows = requireRows(value, role);
                } catch (CustodyException error) {
                    if (initial) {
                        throw failure("verified loader " + role + " row-set does not match its receipt");
                    }
                    throw failure(role + " verified loader rows changed");
                }
                if (!JsonHashes.canonicalSha256(rows).equals(reference.rowsSha256())) {
                    if (initial) {
                        throw failure("verified loader " + role + " row-set does not match its receipt");
                    }
                    throw failure(role + " verified loader rows changed");
                }
            } else if (!JsonHashes.ownHash(value).equals(reference.contentSha256())) {
                if (initial) {
                    throw failure("verified loader " + role + " content does not match its receipt");
                }
                throw failure(role + " verified loader content changed");
            }
            output.put(role, value.deepCopy());
        }
        return immutableValueCopies(output);
    }

    private static void validateSupplied(
            Map<String, ReceiptReference> receipts, Map<String, JsonNode> suppliedValues) {
        if (suppliedValues == null) return;
        for (Map.Entry<String, JsonNode> supplied : suppliedValues.entrySet()) {
            String role = supplied.getKey();
            if (!List.of("bars", "funding", "marks", "hydration").contains(role)) continue;
            ReceiptReference reference = receipts.get(role);
            if (reference == null) {
                throw failure(role + " input is present but has no physical receipt");
            }
            String actual = reference.rowsSha256() == null
                    ? JsonHashes.canonicalSha256(supplied.getValue())
                    : JsonHashes.canonicalSha256(requireRows(supplied.getValue(), role));
            String expected = reference.rowsSha256() == null
                    ? reference.contentSha256() : reference.rowsSha256();
            if (!actual.equals(expected)) {
                throw failure(role + " input does not match its physical receipt");
            }
        }
    }

    private static ReceiptReference first(Map<String, ReceiptReference> values, String... names) {
        ReceiptReference result = firstOptional(values, names);
        if (result == null) {
            throw failure(names[0] + " receipt reference is required");
        }
        return result;
    }

    private static ReceiptReference firstOptional(Map<String, ReceiptReference> values, String... names) {
        for (String name : names) {
            if (values.get(name) != null) return values.get(name);
        }
        return null;
    }

    private static JsonNode normalizeLineage(Object lineage) {
        JsonNode node = lineage == null ? JsonHashes.mapper().createObjectNode()
                : lineage instanceof JsonNode json ? json.deepCopy() : JsonHashes.mapper().valueToTree(lineage);
        if (!node.isObject()) {
            throw failure("lineage must be an object");
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getKey().endsWith("_sha256") && !entry.getValue().isNull()
                    && !JsonHashes.isSha256(entry.getValue().asText())) {
                throw failure("lineage " + entry.getKey() + " is not a valid hash");
            }
        });
        return node;
    }

    private static JsonNode requireRows(JsonNode value, String role) {
        if (value.isArray()) return value;
        if (value.isObject()) {
            for (String name : List.of("rows", "bars", "child_bars", "data")) {
                if (value.path(name).isArray()) return value.path(name);
            }
        }
        throw failure(role + " physical row-set is missing");
    }

    private static String bundleDigest(Map<String, ReceiptReference> receipts, JsonNode lineage) {
        ObjectNode payload = JsonHashes.mapper().createObjectNode();
        payload.put("schema", SCHEMA);
        payload.put("version", 1);
        payload.set("receipts", receiptsNode(receipts));
        payload.set("lineage", lineage);
        return JsonHashes.canonicalSha256(payload);
    }

    private static JsonNode tokenNode(Token token, boolean includeContentHash) {
        ObjectNode node = JsonHashes.mapper().createObjectNode();
        node.put("schema", SCHEMA);
        node.put("version", 1);
        node.put("fixture_only", false);
        node.put("provenance", "AUTHORITATIVE");
        if (token.rootReference == null) node.putNull("root_reference");
        else node.put("root_reference", token.rootReference);
        node.set("receipts", receiptsNode(token.receipts));
        node.set("lineage", token.lineage);
        node.put("bundle_sha256", token.bundleSha256);
        if (includeContentHash) node.put("content_sha256", token.contentSha256);
        return node;
    }

    private static JsonNode receiptsNode(Map<String, ReceiptReference> receipts) {
        ObjectNode output = JsonHashes.mapper().createObjectNode();
        receipts.forEach((role, receipt) -> {
            ObjectNode value = JsonHashes.mapper().createObjectNode();
            value.put("path", receipt.path());
            if (receipt.bytes() != null) value.put("bytes", receipt.bytes());
            value.put("byte_sha256", receipt.byteSha256());
            value.put("content_sha256", receipt.contentSha256());
            if (receipt.rowsSha256() != null) value.put("rows_sha256", receipt.rowsSha256());
            if (receipt.schema() != null) value.put("schema", receipt.schema());
            output.set(role, value);
        });
        return output;
    }

    private static Map<String, JsonNode> immutableValueCopies(Map<String, JsonNode> values) {
        Map<String, JsonNode> copies = new LinkedHashMap<>();
        values.forEach((key, value) -> copies.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copies);
    }

    private static CustodyException failure(String message) {
        return new CustodyException("lifecycle trust: " + message);
    }
}
