package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Schema and activation-digest checks from {@code lint-swing-calibration.mjs}. */
public final class SwingCalibrationLinter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private SwingCalibrationLinter() {}

    /** Returns every schema and activation error in Node/Ajv ordering. */
    public static List<String> lint(JsonNode report, Path workingDirectory) throws IOException {
        List<String> errors = new ArrayList<>();
        validateSchema(report, errors);
        String activation = text(report == null ? null : report.get("activation"));
        JsonNode modelActivation = report == null ? null : report.get("model_activation");
        if (!java.util.Objects.equals(activation, text(modelActivation == null ? null : modelActivation.get("status")))) {
            errors.add("activation and model_activation.status differ");
        }
        if ("SHADOW".equals(activation) && (truthy(at(modelActivation, "artifact"))
                || truthy(at(modelActivation, "sha256")) || truthy(at(modelActivation, "activated_at")))) {
            errors.add("SHADOW calibration carries ACTIVE artifact metadata");
        }
        if ("ACTIVE".equals(activation)) {
            String artifactName = text(at(modelActivation, "artifact"));
            String digest = text(at(modelActivation, "sha256"));
            if (!truthy(at(modelActivation, "artifact")) || digest == null || !SHA256.matcher(digest).matches()
                    || !truthy(at(modelActivation, "activated_at"))) {
                errors.add("ACTIVE calibration requires artifact, SHA-256, and timestamp");
            }
            if (!report.path("point_in_time_safe").asBoolean(false)
                    || !report.path("activation_policy").path("point_in_time_safe_required").asBoolean(false)) {
                errors.add("ACTIVE calibration is not point-in-time safe");
            }
            if (!report.path("proxy_contract").path("accepted").asBoolean(false)) {
                errors.add("ACTIVE calibration lacks explicit proxy-contract acceptance");
            }
            Set<String> required = new HashSet<>();
            array(report.path("activation_policy").get("required_series")).forEach(node -> required.add(node.asText()));
            Set<String> observed = new HashSet<>();
            for (JsonNode dataset : array(report.get("datasets"))) {
                String channel = truthy(dataset.get("channel")) ? ':' + dataset.get("channel").asText() : "";
                observed.add(text(dataset.get("asset")) + ":" + text(dataset.get("framework")) + channel);
            }
            for (String series : required) if (!observed.contains(series)) errors.add("ACTIVE calibration missing required series " + series);
            for (JsonNode dataset : array(report.get("datasets"))) if (!dataset.path("holdout_pass").asBoolean(false)) {
                String channel = truthy(dataset.get("channel")) ? ':' + dataset.get("channel").asText() : "";
                errors.add("ACTIVE calibration contains failed series " + text(dataset.get("asset")) + ':' + text(dataset.get("framework")) + channel);
            }
            ObjectNode payload = canonicalPayload(report);
            String calculated = SwingEngine.sha256(payload);
            if (!calculated.equals(digest)) errors.add("ACTIVE calibration SHA-256 does not match canonical payload");
            Path artifact = artifactName == null ? workingDirectory.resolve("null") : resolve(workingDirectory, artifactName);
            if (!Files.exists(artifact)) {
                errors.add("ACTIVE calibration artifact is missing: " + artifact.toAbsolutePath().normalize());
            } else {
                JsonNode artifactReport = MAPPER.readTree(Files.readString(artifact));
                if (!java.util.Objects.equals(text(artifactReport.path("model_activation").get("sha256")), digest)) {
                    errors.add("committed artifact SHA-256 differs from calibration");
                }
                if (!SwingEngine.sha256(canonicalPayload(artifactReport)).equals(digest)) {
                    errors.add("artifact content hash is tampered or stale");
                }
                if (!CanonicalJson.canonicalize(artifactReport).equals(CanonicalJson.canonicalize(report))) {
                    errors.add("calibration report and committed artifact differ");
                }
            }
        }
        return List.copyOf(errors);
    }

    public static ObjectNode canonicalPayload(JsonNode report) {
        ObjectNode payload = report != null && report.isObject() ? ((ObjectNode) report).deepCopy() : JSON.objectNode();
        payload.put("activation", "ACTIVE");
        ObjectNode activation = JSON.objectNode().put("status", "ACTIVE");
        activation.set("artifact", NullNode.instance);
        activation.set("sha256", NullNode.instance);
        activation.set("activated_at", NullNode.instance);
        payload.set("model_activation", activation);
        payload.remove("artifact");
        return payload;
    }

    private static void validateSchema(JsonNode report, List<String> errors) {
        if (report == null || !report.isObject()) { errors.add("/ must be object"); return; }
        required(report, "/", errors, "schema", "model", "generated_at", "years", "split", "criteria", "costs",
                "activation", "model_activation", "activation_policy", "point_in_time_safe", "proxy_contract", "datasets");
        constant(report.get("schema"), "swing-calibration/1", "/schema", errors);
        constant(report.get("model"), "swing-score/1", "/model", errors);
        type(report.get("generated_at"), "string", "/generated_at", errors);
        JsonNode years = report.get("years");
        if (years != null && !years.isIntegralNumber()) errors.add("/years must be integer");
        else if (years != null && (years.longValue() < 1 || years.longValue() > 3))
            errors.add(years.longValue() < 1 ? "/years must be >= 1" : "/years must be <= 3");
        objectRequired(report.get("split"), "/split", errors, "development_months", "fold_months", "untouched_holdout_months");
        objectRequired(report.get("criteria"), "/criteria", errors, "min_holdout_signals", "min_coverage_ratio", "min_regimes");
        objectRequired(report.get("costs"), "/costs", errors, "fee_pct_one_way", "slippage_pct_one_way");
        objectRequired(report.get("activation_policy"), "/activation_policy", errors,
                "point_in_time_safe_required", "proxy_inputs_accepted", "required_series");
        type(report.get("point_in_time_safe"), "boolean", "/point_in_time_safe", errors);
        objectRequired(report.get("proxy_contract"), "/proxy_contract", errors, "accepted");
        if (report.path("proxy_contract").has("accepted")) type(report.path("proxy_contract").get("accepted"), "boolean", "/proxy_contract/accepted", errors);
        enumValue(report.get("activation"), List.of("SHADOW", "ACTIVE"), "/activation", errors);
        JsonNode model = report.get("model_activation");
        objectRequired(model, "/model_activation", errors, "status", "artifact", "sha256", "activated_at");
        if (model != null && model.isObject()) {
            enumValue(model.get("status"), List.of("SHADOW", "ACTIVE"), "/model_activation/status", errors);
            nullableString(model.get("artifact"), "/model_activation/artifact", errors);
            nullableString(model.get("sha256"), "/model_activation/sha256", errors);
            JsonNode sha = model.get("sha256");
            if (sha != null && sha.isTextual() && !(sha.asText().isEmpty() || SHA256.matcher(sha.asText()).matches()))
                errors.add("/model_activation/sha256 must match pattern \"^([0-9a-f]{64})?$\"");
            nullableString(model.get("activated_at"), "/model_activation/activated_at", errors);
        }
        JsonNode datasets = report.get("datasets");
        if (datasets != null && !datasets.isArray()) errors.add("/datasets must be array");
        else if (datasets != null) for (int index = 0; index < datasets.size(); index++) {
            JsonNode dataset = datasets.get(index); String base = "/datasets/" + index;
            objectRequired(dataset, base, errors, "asset", "framework", "feature_coverage", "holdout_pass", "holdout_criteria");
            if (dataset == null || !dataset.isObject()) continue;
            type(dataset.get("asset"), "string", base + "/asset", errors);
            enumValue(dataset.get("framework"), List.of("fallen_knives", "flying_rocket"), base + "/framework", errors);
            nullableString(dataset.get("channel"), base + "/channel", errors);
            enumValue(dataset.get("feature_coverage"), List.of("COMPLETE", "PARTIAL", "HISTORICAL_PROXY"), base + "/feature_coverage", errors);
            type(dataset.get("holdout_pass"), "boolean", base + "/holdout_pass", errors);
            type(dataset.get("holdout_criteria"), "object", base + "/holdout_criteria", errors);
        }
    }

    private static void objectRequired(JsonNode node, String path, List<String> errors, String... names) {
        type(node, "object", path, errors);
        if (node != null && node.isObject()) required(node, path, errors, names);
    }
    private static void required(JsonNode node, String path, List<String> errors, String... names) {
        for (String name : names) if (!node.has(name)) errors.add(path + " must have required property '" + name + "'");
    }
    private static void constant(JsonNode node, String expected, String path, List<String> errors) {
        if (node != null && (!node.isTextual() || !expected.equals(node.asText()))) errors.add(path + " must be equal to constant");
    }
    private static void enumValue(JsonNode node, List<String> values, String path, List<String> errors) {
        if (node != null && (!node.isTextual() || !values.contains(node.asText()))) errors.add(path + " must be equal to one of the allowed values");
    }
    private static void nullableString(JsonNode node, String path, List<String> errors) {
        if (node != null && !node.isNull() && !node.isTextual()) errors.add(path + " must be string,null");
    }
    private static void type(JsonNode node, String expected, String path, List<String> errors) {
        if (node == null) return;
        boolean valid = switch (expected) { case "string" -> node.isTextual(); case "boolean" -> node.isBoolean();
            case "object" -> node.isObject(); default -> true; };
        if (!valid) errors.add(path + " must be " + expected);
    }
    private static JsonNode at(JsonNode node, String name) { return node == null ? null : node.get(name); }
    private static String text(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(); }
    private static boolean truthy(JsonNode node) { return SwingCrossValidator.truthy(node); }
    private static ArrayNode array(JsonNode node) { return SwingCrossValidator.array(node); }
    private static Path resolve(Path root, String value) { Path path = Path.of(value); return path.isAbsolute() ? path.normalize() : root.resolve(path).toAbsolutePath().normalize(); }
}
