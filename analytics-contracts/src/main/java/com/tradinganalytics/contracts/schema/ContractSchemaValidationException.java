package com.tradinganalytics.contracts.schema;

import java.util.List;

/** Raised when a recognized contract does not satisfy its registered JSON Schema. */
public final class ContractSchemaValidationException extends IllegalArgumentException {
    private final String schemaId;
    private final List<String> validationErrors;

    ContractSchemaValidationException(String schemaId, List<String> validationErrors) {
        super("Ajv schema validation failed for " + schemaId + ": " + String.join(", ", validationErrors));
        this.schemaId = schemaId;
        this.validationErrors = List.copyOf(validationErrors);
    }

    public String schemaId() {
        return schemaId;
    }

    public List<String> validationErrors() {
        return validationErrors;
    }
}
