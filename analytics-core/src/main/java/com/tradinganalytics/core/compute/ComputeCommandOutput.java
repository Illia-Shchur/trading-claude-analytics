package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;

/** Stable command output rendering, including Node-compatible number formatting. */
final class ComputeCommandOutput {

    private ComputeCommandOutput() {
    }

    static String pretty(JsonNode value) {
        return NodePrettyJson.write(normalizeNumbers(value));
    }

    private static JsonNode normalizeNumbers(JsonNode value) {
        if (value == null || value.isMissingNode()) return NullNode.getInstance();
        if (value.isNumber()) return ComputeNumericSupport.normalizedNumberNode(value.doubleValue());
        if (value.isArray()) {
            ArrayNode output = ComputeJsonSupport.array();
            value.forEach(item -> output.add(normalizeNumbers(item)));
            return output;
        }
        if (value.isObject()) {
            ObjectNode output = ComputeJsonSupport.object();
            value.fields().forEachRemaining(entry -> output.set(entry.getKey(), normalizeNumbers(entry.getValue())));
            return output;
        }
        return value.deepCopy();
    }

}
