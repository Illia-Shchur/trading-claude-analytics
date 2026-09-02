package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.tradinganalytics.cli.StrategyV5WorkflowJson.*;

/** Builds and validates the inactive authoritative receipts used by the workflow. */
final class StrategyV5WorkflowReceipts {
    static final String AUTHORITATIVE_SCHEMA = "strategy-v5-authoritative-command-receipt/1";

    private StrategyV5WorkflowReceipts() {}

    static ObjectNode commandReceipt(String command, String status, List<ObjectNode> inputs,
                                     List<String> limitations, ObjectNode details) {
        return commandReceipt(command, status, inputs, List.of(), limitations, details);
    }

    static ObjectNode commandReceipt(String command, String status, List<ObjectNode> inputs,
                                     List<ObjectNode> outputs, List<String> limitations,
                                     ObjectNode details) {
        if (!Set.of("PLANNED", "COMPLETE", "BLOCKED", "REJECTED").contains(status)) {
            throw new IllegalArgumentException("invalid authoritative command status " + status);
        }
        ObjectNode result = object().put("schema", AUTHORITATIVE_SCHEMA).put("version", 1)
                .put("command", command).put("status", status);
        ArrayNode inputRows = result.putArray("inputs");
        inputs.forEach(inputRows::add);
        ArrayNode outputRows = result.putArray("outputs");
        outputs.forEach(outputRows::add);
        ArrayNode limitRows = result.putArray("limitations");
        limitations.stream().distinct().sorted().forEach(limitRows::add);
        ObjectNode safeDetails = details == null ? object() : details.deepCopy();
        safeDetails.put("active", false);
        result.set("details", safeDetails);
        result.put("content_sha256", StrategyProspectiveV5.ownHash(result));
        com.tradinganalytics.contracts.schema.ResearchSchemaRegistry.defaultRegistry()
                .validateContractSchema(result);
        return result;
    }

    static void validateCommandReceipt(ObjectNode receipt) {
        requireSchemaAndHash(receipt, AUTHORITATIVE_SCHEMA, "authoritative command receipt");
        if (receipt.path("details").path("active").asBoolean(true)
                || receipt.toString().contains("\"ACTIVE\"")) {
            throw new IllegalArgumentException("authoritative command receipt may not claim ACTIVE");
        }
    }

    static List<ObjectNode> sourceInputs(WorkflowSecurityV5.SourceBundleVerification verified) {
        List<ObjectNode> result = new ArrayList<>();
        result.add(reference(verified.bundlePhysical(), "source_bundle"));
        for (String role : WorkflowSecurityV5.SOURCE_BUNDLE_ROLES) {
            result.add(reference(verified.references().get(role), role));
        }
        return result;
    }

    static ObjectNode reference(WorkflowSecurityV5.ConfinedJson physical, String role) {
        byte[] bytes = physical.bytes();
        ObjectNode value = physical.value().isObject() ? (ObjectNode) physical.value() : null;
        ObjectNode out = object().put("role", role).put("storage", "PHYSICAL")
                .put("path", physical.relative()).put("byte_sha256", StrategyProspectiveV5.hash(bytes))
                .put("bytes", bytes.length);
        if (value != null && validOwnHash(value)) {
            out.put("content_sha256", text(value.get("content_sha256")));
        } else {
            out.putNull("content_sha256");
        }
        return out;
    }

    static ObjectNode reference(Path path, Path work, String role) {
        Path base = com.tradinganalytics.infrastructure.security.PathConfinement
                .requireRealDirectory(work, "workflow working directory");
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) throw new IllegalArgumentException(role + " escapes repository");
        String relative = base.relativize(absolute).toString()
                .replace(absolute.getFileSystem().getSeparator(), "/");
        WorkflowSecurityV5.ConfinedJson physical = WorkflowSecurityV5.readConfinedJson(
                work, relative, role);
        return reference(physical, role);
    }
}
