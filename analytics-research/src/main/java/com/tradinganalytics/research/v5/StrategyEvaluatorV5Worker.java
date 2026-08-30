package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;

/** In-process isolation boundary equivalent to {@code strategy-evaluator-v5-worker.mjs}. */
public final class StrategyEvaluatorV5Worker implements AutoCloseable {
    public record Response(ObjectNode result, String error, String key, long evaluationOrdinal, int workerSlot) {
        @Override public ObjectNode result() { return result == null ? null : result.deepCopy(); }
    }

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private final int maxResultBytes;
    private final int workerSlot;
    private final StrategyEvaluatorV5.Evaluator evaluator;
    private final String initializationError;

    public StrategyEvaluatorV5Worker(ObjectNode binding, int maxResultBytes, int workerSlot) {
        this.maxResultBytes = maxResultBytes;
        this.workerSlot = workerSlot;
        StrategyEvaluatorV5.Evaluator value = null;
        String error = null;
        try {
            value = StrategyEvaluatorV5.createVerifiedWorkerEvaluatorV5(
                    binding == null ? MAPPER.createObjectNode() : binding.deepCopy());
        } catch (RuntimeException failure) {
            error = String.valueOf(failure.getMessage());
        }
        evaluator = value;
        initializationError = error;
    }

    public boolean initialized() { return initializationError == null; }
    public String initializationError() { return initializationError; }

    public Response evaluate(ObjectNode args, String key, long evaluationOrdinal) {
        if (initializationError != null) return new Response(null, initializationError, key, evaluationOrdinal, workerSlot);
        try {
            ObjectNode result = evaluator.evaluate(args == null ? MAPPER.createObjectNode() : args.deepCopy());
            ObjectNode payload = MAPPER.createObjectNode();
            payload.set("result", result);
            ObjectNode runtime = payload.putObject("runtime");
            runtime.put("key", key);
            runtime.put("evaluation_ordinal", evaluationOrdinal);
            runtime.put("worker_slot", workerSlot);
            if (bytes(payload).length > maxResultBytes) {
                throw new IllegalArgumentException("authoritative evaluator result exceeds " + maxResultBytes + " bytes");
            }
            return new Response(result, null, key, evaluationOrdinal, workerSlot);
        } catch (RuntimeException failure) {
            ObjectNode errorPayload = MAPPER.createObjectNode();
            errorPayload.put("error", String.valueOf(failure.getMessage()));
            byte[] bytes = bytes(errorPayload);
            String message = String.valueOf(failure.getMessage());
            if (bytes.length > maxResultBytes) {
                int safe = Math.max(0, maxResultBytes - 20);
                message = message.substring(0, Math.min(message.length(), safe));
            }
            return new Response(null, message, key, evaluationOrdinal, workerSlot);
        }
    }

    @Override public void close() { if (evaluator != null) evaluator.close(); }

    private static byte[] bytes(ObjectNode value) {
        try { return MAPPER.writeValueAsBytes(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }
}
