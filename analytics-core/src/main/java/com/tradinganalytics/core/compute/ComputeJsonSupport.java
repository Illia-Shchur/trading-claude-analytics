package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared JSON construction helpers for compute-domain collaborators. */
final class ComputeJsonSupport {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ComputeJsonSupport() {
    }

    static ObjectNode object() {
        return NODES.objectNode();
    }

    static ArrayNode array() {
        return NODES.arrayNode();
    }

    static void putNumber(ObjectNode target, String key, Number value) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            target.set(key, NullNode.getInstance());
        } else {
            target.set(key, ComputeNumericSupport.normalizedNumberNode(value.doubleValue()));
        }
    }

    static void putBoolean(ObjectNode target, String key, Boolean value) {
        if (value == null) target.set(key, NullNode.getInstance());
        else target.put(key, value);
    }
}
