package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Publication-time verification for ACTIVE swing calibration artifacts. */
final class SwingActivationVerifier {
    private static final Pattern ARTIFACT_PATH = Pattern.compile("^calibrations/[^/]+\\.json$");
    private SwingActivationVerifier() {
    }

    static List<String> verify(JsonNode report, Path repositoryRoot) {
        List<String> errors = new ArrayList<>();
        JsonNode activation = object(report, "model_activation");
        if (!"ACTIVE".equals(text(activation, "status"))) {
            return List.of();
        }

        String relative = text(activation, "artifact");
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path calibrationRoot = root.resolve("calibrations").normalize();
        if (relative == null || !ARTIFACT_PATH.matcher(relative).matches() || relative.contains("..")) {
            errors.add("ACTIVE swing model artifact must be a relative calibrations/<file>.json path");
            return List.copyOf(errors);
        }

        Path artifactPath = root.resolve(relative).normalize();
        if (!artifactPath.equals(calibrationRoot) && !artifactPath.startsWith(calibrationRoot)) {
            errors.add("ACTIVE swing model artifact resolves outside calibrations/");
            return List.copyOf(errors);
        }
        if (!Files.exists(artifactPath)) {
            errors.add("ACTIVE swing model artifact is missing: " + relative);
            return List.copyOf(errors);
        }

        JsonNode artifact;
        try {
            String raw = new String(Files.readAllBytes(artifactPath), StandardCharsets.UTF_8);
            artifact = StrictJson.parseStrictJSON(raw, relative);
        } catch (IOException | IllegalArgumentException exception) {
            errors.add("ACTIVE swing model artifact is invalid JSON: " + exception.getMessage());
            return List.copyOf(errors);
        }

        if (!"swing-calibration/1".equals(text(artifact, "schema"))
                || !"swing-score/1".equals(text(artifact, "model"))) {
            errors.add("ACTIVE swing model artifact has the wrong calibration/model schema");
        }
        if (!"ACTIVE".equals(text(artifact, "activation"))
                || !"ACTIVE".equals(text(object(artifact, "model_activation"), "status"))) {
            errors.add("ACTIVE swing model artifact is not ACTIVE");
        }
        if (!relative.equals(text(object(artifact, "model_activation"), "artifact"))) {
            errors.add("ACTIVE report artifact path does not match calibration artifact metadata");
        }
        String activationSha = text(activation, "sha256");
        if (!sameNullable(text(object(artifact, "model_activation"), "sha256"), activationSha)) {
            errors.add("ACTIVE report and calibration artifact SHA-256 differ");
        }

        // Object spread in JavaScript is total for every JSON value. Calibration
        // artifacts are objects, but keep the verifier fail-closed instead of
        // throwing if a strict-JSON primitive is supplied at this boundary.
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (artifact != null && artifact.isObject()) {
            artifact.properties().forEach(entry -> payload.set(entry.getKey(), entry.getValue().deepCopy()));
        } else if (artifact != null && artifact.isArray()) {
            for (int index = 0; index < artifact.size(); index++) {
                payload.set(Integer.toString(index), artifact.get(index).deepCopy());
            }
        }
        payload.put("activation", "ACTIVE");
        ObjectNode payloadActivation = payload.putObject("model_activation");
        payloadActivation.put("status", "ACTIVE");
        payloadActivation.putNull("artifact");
        payloadActivation.putNull("sha256");
        payloadActivation.putNull("activated_at");
        payload.remove("artifact");
        String digest = ReportContract.reportHash(payload);
        if (!sameNullable(digest, activationSha)) {
            errors.add("ACTIVE swing model artifact SHA-256 does not match its canonical payload");
        }

        JsonNode convenience = object(artifact, "artifact");
        if (!relative.equals(text(convenience, "path"))
                || !sameNullable(text(convenience, "sha256"), activationSha)) {
            errors.add("ACTIVE calibration convenience artifact metadata is inconsistent");
        }

        JsonNode policy = object(artifact, "activation_policy");
        if (!isTrue(artifact.get("point_in_time_safe")) || !isTrue(policy.get("point_in_time_safe_required"))) {
            errors.add("ACTIVE swing model calibration is not point-in-time safe");
        }
        if (!isTrue(object(artifact, "proxy_contract").get("accepted"))
                || !isTrue(policy.get("proxy_inputs_accepted"))) {
            errors.add("ACTIVE swing model calibration lacks accepted proxy policy");
        }

        Set<String> declared = stringSet(policy.get("required_series"));
        for (String series : orderedRequiredSeries()) {
            if (!declared.contains(series)) {
                errors.add("ACTIVE swing model calibration policy missing " + series);
            }
        }

        Set<String> passed = new LinkedHashSet<>();
        JsonNode datasets = artifact.get("datasets");
        if (datasets != null && datasets.isArray()) {
            for (JsonNode dataset : datasets) {
                if (isTrue(dataset.get("holdout_pass"))) {
                    String asset = text(dataset, "asset");
                    String framework = text(dataset, "framework");
                    String channel = text(dataset, "channel");
                    passed.add(String.valueOf(asset) + ':' + String.valueOf(framework)
                            + (channel == null || channel.isEmpty() ? "" : ':' + channel));
                }
            }
        }
        for (String series : orderedRequiredSeries()) {
            if (!passed.contains(series)) {
                errors.add("ACTIVE swing model calibration holdout failed or missing: " + series);
            }
        }
        return List.copyOf(errors);
    }

    private static List<String> orderedRequiredSeries() {
        return List.of(
                "btc:fallen_knives",
                "btc:flying_rocket:A",
                "btc:flying_rocket:B",
                "eth:fallen_knives",
                "eth:flying_rocket:A",
                "eth:flying_rocket:B");
    }

    private static Set<String> stringSet(JsonNode values) {
        Set<String> output = new LinkedHashSet<>();
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                if (value.isTextual()) {
                    output.add(value.textValue());
                }
            });
        }
        return output;
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isObject() ? value : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean isTrue(JsonNode value) {
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static boolean sameNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
