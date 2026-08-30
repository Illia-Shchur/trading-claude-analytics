package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Report-local Draft 2020-12 validation with AJV's validateFormats:false policy. */
final class ReportSchemaValidator {
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .failFast(false)
                    .formatAssertionsEnabled(false)
                    .schemaIdValidator((id, root, location, evaluationPath, context) -> true)
                    .build()));
    private static final Schema V2 = load("schemas/report-machine-2.schema.json");
    private static final Schema V3 = load("schemas/report-machine-3.schema.json");

    private ReportSchemaValidator() {
    }

    static List<String> validateV2(JsonNode report) {
        return validate(V2, report);
    }

    static List<String> validateV3(JsonNode report) {
        return validate(V3, report);
    }

    private static List<String> validate(Schema schema, JsonNode report) {
        return schema.validate(report == null ? "null" : report.toString(), InputFormat.JSON).stream()
                .map(ReportSchemaValidator::message)
                .toList();
    }

    private static String message(Error error) {
        String path = error.getInstanceLocation() == null ? "$" : error.getInstanceLocation().toString();
        if (path == null || path.isBlank()) {
            path = "$";
        }
        String ajvMessage = switch (String.valueOf(error.getKeyword())) {
            case "required" -> "must have required property '" + error.getProperty() + "'";
            case "additionalProperties" -> "must NOT have additional properties";
            case "enum" -> "must be equal to one of the allowed values";
            default -> null;
        };
        if (ajvMessage != null) {
            return path + " " + ajvMessage;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.toString();
        }
        if (message.startsWith(path + ": ")) {
            message = message.substring(path.length() + 2);
        }
        return path + " " + message;
    }

    private static Schema load(String resource) {
        try (InputStream input = ReportSchemaValidator.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing report schema resource " + resource);
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return REGISTRY.getSchema(text, InputFormat.JSON);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load report schema resource " + resource, exception);
        }
    }
}
