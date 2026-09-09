package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.CustodyException;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Additive, outcome-agnostic evidence diagnostics for the v5 fixed research
 * path.  This class never writes a family exposure HEAD and never turns a
 * recovered legacy row into modern cumulative K.  It is intentionally kept
 * separate from the evaluator so historical bytes and frozen selection rules
 * remain unchanged.
 */
public final class StrategyEvidenceV1 {
    public static final String LINEAGE_SCHEMA = "strategy-research-lineage-inventory/1";
    public static final String ATTRITION_SCHEMA = "strategy-matching-attrition/1";
    public static final String SUCCESSOR_SCHEMA = "strategy-control-successor-design/1";

    private static final Set<String> FAMILY_FIELDS = Set.of(
            "strategy_family_id", "strategy_family", "strategy_id", "hypothesis_family_id",
            "hypothesis_family", "family_id", "family", "setup_family", "setup_families");
    private static final List<String> ID_FIELDS = List.of(
            "attempt_identity_sha256", "attempt_id", "attempt_sha256", "exposure_attempt_id",
            "behavior_definition_sha256", "behavior_sha256", "physical_input_sha256",
            "experiment_sha256", "run_id");
    private static final Set<String> OUTCOME_FIELDS = Set.of(
            "future_return", "label", "exit_price", "net_r", "pnl", "trade_outcome",
            "realized_volatility_after_decision", "outcome", "forward_return", "forward_pnl");

    private StrategyEvidenceV1() {}

    /**
     * Inventory all retained JSON and JSONL bytes below a caller supplied root.
     * Parse failures and rows whose identity cannot be recovered are retained as
     * unresolved evidence.  Counts in this report are explicitly lower bounds.
     */
    public static ObjectNode lineageInventory(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        Path root = path(input, "root", "record_root", "search_root", "strategy-research/runs");
        String family = normalizeFamily(text(input, "family", "hypothesis_family", "fk-deleveraging-absorption"));
        List<Path> paths = new ArrayList<>();
        boolean rootAvailable = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS);
        if (rootAvailable) {
            try (var stream = Files.walk(root)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.toString().endsWith(".json") || path.toString().endsWith(".jsonl"))
                        .sorted().forEach(paths::add);
            } catch (IOException error) {
                throw failure("cannot scan lineage root: " + error.getMessage());
            }
        } else {
            // Absence of the retained root is a custody failure, never proof
            // that the family has no historical exposure.
            paths.clear();
        }

        ArrayNode artifacts = array();
        ArrayNode units = array();
        ArrayNode unresolved = array();
        Map<String, ArrayNode> byteGroups = new LinkedHashMap<>();
        int matchingRows = 0;
        int parsedArtifactCount = 0;
        int parseFailureCount = 0;
        int familyArtifactCount = 0;
        int candidateRowCount = 0;
        Set<String> candidateIds = new LinkedHashSet<>();
        int tradeOutcomeRowCount = 0;
        Set<String> runExperimentHashes = new LinkedHashSet<>();
        Set<String> tradeJoinKeys = new LinkedHashSet<>();
        Map<Path, Set<String>> candidateIdsByRunDirectory = new LinkedHashMap<>();
        for (Path path : paths) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException error) {
                parseFailureCount++;
                unresolved.add(unresolved(path, "READ_FAILED", error.getMessage()));
                continue;
            }
            String byteSha = JsonHashes.sha256(bytes);
            byteGroups.computeIfAbsent(byteSha, ignored -> array()).add(path.toAbsolutePath().normalize().toString());
            ObjectNode artifact = object().put("path", path.toAbsolutePath().normalize().toString())
                    .put("bytes", bytes.length).put("byte_sha256", byteSha);
            List<JsonNode> rows = new ArrayList<>();
            boolean parseOk = true;
            try {
                if (path.toString().endsWith(".jsonl")) {
                    int line = 0;
                    for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\\R", -1)) {
                        line++;
                        if (raw.isBlank()) continue;
                        try {
                            rows.add(JsonHashes.mapper().readTree(raw));
                        } catch (Exception badLine) {
                            parseOk = false;
                            parseFailureCount++;
                            unresolved.add(unresolved(path, "JSONL_ROW_PARSE_FAILED", "line=" + line));
                        }
                    }
                } else {
                    rows.add(JsonHashes.mapper().readTree(bytes));
                }
                parsedArtifactCount++;
            } catch (Exception error) {
                parseOk = false;
                parseFailureCount++;
                unresolved.add(unresolved(path, "JSON_PARSE_FAILED", safeMessage(error)));
            }
            artifact.put("parse_status", parseOk ? "PARSED" : "PARTIAL_OR_FAILED");
            int artifactMatches = 0;
            boolean hasCandidateRows = false;
            Set<String> schemas = new LinkedHashSet<>();
            int identityCount = 0;
            for (JsonNode row : rows) {
                if (row == null || row.isMissingNode()) continue;
                String schema = row.path("schema").asText("");
                if (!schema.isBlank()) schemas.add(schema);
                List<JsonNode> matching = matchingRows(row, family);
                for (JsonNode match : matching) {
                    artifactMatches++;
                    matchingRows++;
                    ObjectNode unit = exposureUnit(path, byteSha, row, match, family);
                    units.add(unit);
                    switch (unit.path("row_kind").asText()) {
                        case "CANDIDATE" -> {
                            candidateRowCount++; hasCandidateRows = true;
                            String candidateId = findRecursive(match, "candidate_id") == null ? ""
                                    : findRecursive(match, "candidate_id").asText("");
                            if (!candidateId.isBlank()) candidateIds.add(candidateId);
                            if (!candidateId.isBlank()) candidateIdsByRunDirectory
                                    .computeIfAbsent(path.getParent(), ignored -> new LinkedHashSet<>()).add(candidateId);
                        }
                        case "TRADE_OUTCOME" -> tradeOutcomeRowCount++;
                        default -> { }
                    }
                    if (unit.path("classification").asText().equals("UNRESOLVABLE_ATTEMPT")) {
                        unresolved.add(unit.deepCopy());
                    }
                    if (unit.path("classification").asText().equals("OUTCOME_JOIN_PENDING")) {
                        tradeJoinKeys.add(unit.path("path").asText() + "#" + unit.path("row_content_sha256").asText());
                    }
                    JsonNode identity = unit.get("identity");
                    if (identity != null && !identity.isNull()) identityCount++;
                }
            }
            artifact.put("matching_row_count", artifactMatches);
            artifact.put("family_match", artifactMatches > 0);
            artifact.put("identity_count", identityCount);
            ArrayNode schemaRows = array(); schemas.forEach(schemaRows::add);
            artifact.set("schemas", schemaRows);
            if (artifactMatches > 0) familyArtifactCount++;
            if (hasCandidateRows) {
                Path runPath = path.getParent() == null ? null : path.getParent().resolve("run.json");
                if (runPath != null && Files.isRegularFile(runPath, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        JsonNode run = JsonHashes.mapper().readTree(Files.readAllBytes(runPath));
                        JsonNode experiment = run.path("experiment").path("sha256");
                        if (experiment.isTextual() && experiment.asText().matches("[a-f0-9]{64}")) runExperimentHashes.add(experiment.asText());
                    } catch (Exception ignored) {
                        // The artifact itself remains inventoried; missing run
                        // context is disclosed by the lower-bound count.
                    }
                }
            }
            artifacts.add(artifact);
        }

        ArrayNode runReconciliations = array();
        int metricsMembershipCount = 0;
        int rawMetricRowCount = 0;
        int unboundMetricCount = 0;
        Set<String> boundMetricCandidateIds = new LinkedHashSet<>();
        for (Map.Entry<Path, Set<String>> context : candidateIdsByRunDirectory.entrySet()) {
            Path directory = context.getKey();
            Path runPath = directory == null ? null : directory.resolve("run.json");
            ObjectNode reconciliation = object().put("run_directory", directory == null ? "" : directory.toString())
                    .put("candidate_id_count", context.getValue().size());
            if (runPath == null || !Files.isRegularFile(runPath, LinkOption.NOFOLLOW_LINKS)) {
                reconciliation.put("status", "UNRESOLVED_RUN_RECORD");
                unresolved.add(unresolved(runPath == null ? directory : runPath, "RUN_RECORD_MISSING",
                        "candidate artifact has no adjacent run.json"));
                runReconciliations.add(reconciliation);
                continue;
            }
            int unresolvedBefore = unresolved.size();
            try {
                byte[] runBytes = Files.readAllBytes(runPath);
                JsonNode run = JsonHashes.mapper().readTree(runBytes);
                reconciliation.put("run_path", runPath.toString()).put("run_byte_sha256", JsonHashes.sha256(runBytes));
                JsonNode experiment = run.path("experiment").path("sha256");
                if (experiment.isTextual() && experiment.asText().matches("[a-f0-9]{64}")) runExperimentHashes.add(experiment.asText());
                Path metricsPath = directory.resolve("metrics.jsonl");
                int boundMetrics = 0;
                int allMetrics = 0;
                Set<String> metricCandidateIds = new LinkedHashSet<>();
                if (Files.isRegularFile(metricsPath, LinkOption.NOFOLLOW_LINKS)) {
                    for (JsonNode metric : readJsonLines(metricsPath, unresolved)) {
                        rawMetricRowCount++;
                        allMetrics++;
                        String metricCandidateId = metric.path("candidate_id").asText("");
                        if (!metricCandidateId.isBlank() && context.getValue().contains(metricCandidateId)) {
                            metricCandidateIds.add(metricCandidateId);
                        }
                        if (!metricCandidateId.isBlank() && context.getValue().contains(metricCandidateId)) boundMetrics++;
                    }
                } else {
                    unresolved.add(unresolved(metricsPath, "METRICS_ARTIFACT_MISSING", "run-bound metrics artifact is unavailable"));
                }
                metricsMembershipCount += boundMetrics;
                boundMetricCandidateIds.addAll(metricCandidateIds);
                int declared = run.path("artifacts").path("metrics").path("rows").asInt(-1);
                if (declared >= 0 && declared != allMetrics) {
                    unresolved.add(unresolved(metricsPath, "METRICS_ROW_COUNT_MISMATCH",
                            "run declared " + declared + " rows but " + allMetrics + " rows were reopened"));
                }
                reconciliation.put("metrics_artifact_row_count", allMetrics)
                        .put("metrics_bound_row_count", boundMetrics)
                        .put("metrics_bound_candidate_id_count", metricCandidateIds.size())
                        .put("metrics_declared_row_count", declared);
                for (String kind : List.of("candidates", "metrics", "trades")) {
                    JsonNode ref = run.path("artifacts").path(kind);
                    if (!ref.isObject()) continue;
                    String rawRef = ref.path("path").asText("");
                    Path referenced = directory.resolve(rawRef).normalize();
                    ObjectNode receipt = object().put("kind", kind).put("path", referenced.toString())
                            .put("expected_sha256", ref.path("sha256").asText("")).put("expected_rows", ref.path("rows").asInt(-1));
                    if (!Files.isRegularFile(referenced, LinkOption.NOFOLLOW_LINKS)) {
                        receipt.put("status", "UNRESOLVED_MISSING");
                        unresolved.add(unresolved(referenced, "REFERENCED_ARTIFACT_MISSING", "run.json references an unavailable artifact"));
                    } else {
                        try {
                            byte[] bytes = Files.readAllBytes(referenced);
                            int rows = countRows(referenced, bytes);
                            String actual = JsonHashes.sha256(bytes);
                            receipt.put("actual_sha256", actual).put("actual_rows", rows)
                                    .put("status", actual.equals(ref.path("sha256").asText("") ) && rows == ref.path("rows").asInt(-1)
                                            ? "VERIFIED" : "MISMATCH");
                            if (!"VERIFIED".equals(receipt.path("status").asText())) unresolved.add(unresolved(referenced,
                                    "REFERENCED_ARTIFACT_MISMATCH", "run.json receipt does not match reopened bytes"));
                        } catch (Exception error) {
                            receipt.put("status", "UNRESOLVED_READ_FAILED");
                            unresolved.add(unresolved(referenced, "REFERENCED_ARTIFACT_READ_FAILED", safeMessage(error)));
                        }
                    }
                    if ("trades".equals(kind) && Files.isRegularFile(referenced, LinkOption.NOFOLLOW_LINKS)) {
                        for (JsonNode trade : readJsonLines(referenced, unresolved)) {
                            String candidateId = trade.path("candidate_id").asText("");
                            if (context.getValue().contains(candidateId) && "TRADE_OUTCOME".equals(rowKind(trade))) {
                                tradeJoinKeys.add(referenced.toAbsolutePath().normalize() + "#" + JsonHashes.canonicalSha256(trade));
                            }
                        }
                    }
                    reconciliation.withArray("artifact_receipts").add(receipt);
                }
                reconciliation.put("status", unresolved.size() == unresolvedBefore ? "RECONCILED" : "RECONCILED_WITH_LIMITATIONS");
            } catch (Exception error) {
                reconciliation.put("status", "UNRESOLVED_RUN_PARSE");
                unresolved.add(unresolved(runPath, "RUN_RECORD_PARSE_FAILED", safeMessage(error)));
            }
            runReconciliations.add(reconciliation);
        }
        unboundMetricCount = Math.max(0, rawMetricRowCount - metricsMembershipCount);

        ArrayNode duplicateGroups = array();
        int duplicateArtifactCount = 0;
        for (Map.Entry<String, ArrayNode> entry : byteGroups.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            duplicateArtifactCount += entry.getValue().size() - 1;
            duplicateGroups.add(object().put("byte_sha256", entry.getKey())
                    .put("artifact_count", entry.getValue().size()).set("paths", entry.getValue().deepCopy()));
        }
        Map<String, List<ObjectNode>> identityGroups = new LinkedHashMap<>();
        for (JsonNode raw : units) {
            if (!raw.isObject()) continue;
            JsonNode identity = raw.get("identity");
            if (identity == null || identity.isNull() || identity.asText("").isBlank()) continue;
            identityGroups.computeIfAbsent(identity.asText(), ignored -> new ArrayList<>()).add((ObjectNode) raw);
        }
        int provenRetryGroups = 0;
        int distinctExposureUnits = 0;
        int outcomeJoinPendingCount = tradeJoinKeys.size();
        int unresolvedAttemptCount = 0;
        Set<String> behaviorHashes = new LinkedHashSet<>();
        Set<String> experimentHashes = new LinkedHashSet<>();
        for (Map.Entry<String, List<ObjectNode>> entry : identityGroups.entrySet()) {
            List<ObjectNode> group = entry.getValue();
            for (ObjectNode row : group) {
                String behavior = row.path("exposure_tuple").path("behavior_sha256").asText(
                        row.path("exposure_tuple").path("behavior_definition_sha256").asText(""));
                if (!behavior.isBlank()) behaviorHashes.add(behavior);
                String experiment = row.path("exposure_tuple").path("experiment_sha256").asText("");
                if (!experiment.isBlank()) experimentHashes.add(experiment);
            }
            boolean sameTuple = group.stream().map(row -> row.path("exposure_tuple").toString()).distinct().count() == 1;
            boolean attemptIdentity = group.stream().allMatch(row -> "ATTEMPT".equals(row.path("identity_kind").asText()));
            boolean completeCustodyTuple = group.stream().allMatch(row -> {
                JsonNode tuple = row.path("exposure_tuple");
                return tuple.has("behavior_definition_sha256") && tuple.has("physical_input_sha256")
                        && tuple.has("experiment_sha256") && tuple.has("executor_identity_sha256");
            });
            if (group.size() > 1 && sameTuple && attemptIdentity && completeCustodyTuple) provenRetryGroups++;
            else if (group.size() == 1 && attemptIdentity && completeCustodyTuple) distinctExposureUnits++;
        }
        for (JsonNode raw : units) {
            if ("UNRESOLVABLE_ATTEMPT".equals(raw.path("classification").asText())) unresolvedAttemptCount++;
        }
        if (!rootAvailable) unresolved.add(unresolved(root, "ROOT_UNAVAILABLE", "retained lineage root is missing or not a directory"));
        ObjectNode result = object().put("schema", LINEAGE_SCHEMA).put("version", 1)
                .put("family", family).put("root", root.toAbsolutePath().normalize().toString())
                .put("lower_bound", true).put("promotion_eligible", false)
                .put("modern_cumulative_k_claim", false)
                .put("parsed_artifact_count", parsedArtifactCount).put("artifact_count", artifacts.size())
                .put("family_artifact_count", familyArtifactCount).put("matching_row_count", matchingRows)
                .put("candidate_row_count", candidateRowCount).put("candidate_id_count", candidateIds.size())
                .put("metric_row_count", metricsMembershipCount).put("metrics_artifact_row_count", rawMetricRowCount)
                .put("metrics_other_family_row_count", unboundMetricCount)
                .put("trade_outcome_row_count", tradeOutcomeRowCount)
                .put("trade_outcome_join_pending_count", outcomeJoinPendingCount)
                .put("metrics_membership_row_count", metricsMembershipCount)
                .put("metrics_candidate_id_count", boundMetricCandidateIds.size())
                .put("candidate_ids_without_metric_row_count", Math.max(0,
                        candidateIds.size() - boundMetricCandidateIds.size()))
                .putNull("unbound_metric_row_count")
                .put("parse_failure_count", parseFailureCount)
                .put("unresolvable_attempt_count", unresolvedAttemptCount)
                .put("duplicate_artifact_count_lower_bound", duplicateArtifactCount)
                .put("proven_retry_group_count_lower_bound", provenRetryGroups)
                .put("distinct_exposure_unit_count_lower_bound", distinctExposureUnits)
                .put("claimed_behavior_hash_count", behaviorHashes.size())
                .put("claimed_experiment_hash_count", Math.max(experimentHashes.size(), runExperimentHashes.size()))
                .put("run_context_experiment_hash_count", runExperimentHashes.size())
                .put("status", !rootAvailable ? "UNRESOLVED_ROOT"
                        : parseFailureCount > 0 || !unresolved.isEmpty() ? "UNRESOLVED_HISTORY" : "INVENTORIED");
        result.put("limitations", "Counts are lower bounds. Missing, malformed, or identity-free retained bytes cannot be converted into exact historical K.");
        result.set("artifacts", artifacts);
        result.set("exposure_units", units);
        result.set("duplicate_groups", duplicateGroups);
        result.set("run_reconciliations", runReconciliations);
        result.set("unresolved", unresolved);
        return withHash(result);
    }

    /**
     * Reconcile the authoritative matcher output without reopening outcomes.
     * The report distinguishes selection candidates from admitted event rows,
     * actual paired trades, and merged scheduled lifecycle clusters.
     */
    public static ObjectNode matchingAttrition(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        Path source = path(input, "result", "source_result", "input", "");
        if (source == null && input.has("physical_input") && input.has("baseline") && input.has("controls")) {
            return StrategyFixedBaselineV5.diagnosticMatchingStages(input);
        }
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("matching attrition requires a retained raw fixed-baseline result path");
        }
        ObjectNode result = readObject(source, "fixed-baseline result");
        String schema = result.path("schema").asText("");
        if (!schema.equals("strategy-fixed-baseline-result/1")
                && !schema.equals("strategy-fixed-refinement-member-result/1")) {
            throw failure("matching attrition requires a fixed-baseline result, not " + schema);
        }
        String sourceContentSha = result.path("content_sha256").asText("");
        String ownContentSha = JsonHashes.ownHash(result);
        if (!sourceContentSha.isBlank() && !sourceContentSha.equals(ownContentSha)) {
            throw failure("fixed-baseline result content hash is invalid");
        }
        ArrayNode selections = arrayFrom(result, "control_selections");
        ArrayNode skipped = arrayFrom(result, "skipped_events");
        ArrayNode setup = arrayFrom(result, "setup_events");
        ArrayNode attempts = arrayFrom(result, "attempts");
        ArrayNode independent = arrayFrom(result, "independent_market_episodes");
        long eventCount = result.path("event_count").asLong(setup.size() + skipped.size());
        long admitted = result.path("admitted_event_count").asLong(setup.size());
        long skippedOpen = result.path("skipped_open_position_count").asLong(skipped.size());
        long matched = result.path("matched_control_count").asLong(0);
        long noMatch = 0, matchedSelectionRows = 0, candidatePoolTotal = 0;
        Map<String, Long> candidateHistogram = new LinkedHashMap<>();
        for (JsonNode selection : selections) {
            int candidates = selection.path("candidate_count").asInt(0);
            candidatePoolTotal += candidates;
            candidateHistogram.merge(Integer.toString(candidates), 1L, Long::sum);
            if (selection.path("control").isObject()) matchedSelectionRows++;
            else if (!"SKIPPED_OPEN_POSITION".equals(selection.path("status").asText())) noMatch++;
        }
        long completePairs = 0, unresolvedControl = 0, unresolvedEvent = 0;
        long noControlSelected = 0, unresolvedSelectedControl = 0;
        Map<String, JsonNode> selectionByEvent = new HashMap<>();
        for (JsonNode selection : selections) {
            String eventId = selection.path("event_id").asText("");
            if (!eventId.isBlank()) selectionByEvent.put(eventId, selection);
        }
        for (JsonNode attempt : attempts) {
            String status = attempt.path("status").asText();
            JsonNode selection = selectionByEvent.get(attempt.path("event_id").asText(""));
            boolean hasSelectedControl = selection != null && selection.path("control").isObject();
            if (!hasSelectedControl) noControlSelected++;
            else if (!"COMPLETE".equals(status)) unresolvedSelectedControl++;
            switch (status) {
                case "COMPLETE" -> completePairs++;
                case "EVENT_COMPLETE_CONTROL_UNRESOLVED" -> {
                    if (hasSelectedControl) unresolvedControl++;
                }
                default -> unresolvedEvent++;
            }
        }
        long pairedClusters = result.path("metrics").path("paired_tested_cluster_count").asLong(
                result.path("metrics").path("paired_tested_cluster_count").asLong(0));
        long eventClusters = result.path("metrics").path("event_tested_cluster_count").asLong(independent.size());
        long skippedSelectionRows = 0;
        for (JsonNode row : skipped) if ("SKIPPED_OPEN_POSITION".equals(row.path("status").asText())) skippedSelectionRows++;
        ObjectNode sequential = object()
                .put("feature_rows_to_setup_events", eventCount)
                .put("setup_events_to_admitted_events", admitted)
                .put("admitted_events_to_control_selections", Math.max(0, selections.size() - skippedSelectionRows))
                .put("all_control_selection_rows_including_skips", selections.size())
                .put("control_selections_to_matched_controls", matchedSelectionRows)
                .put("matched_controls_to_complete_pairs", completePairs)
                .put("complete_pairs_to_paired_clusters", pairedClusters)
                .put("admitted_events_to_event_clusters", eventClusters);
        ObjectNode marginal = object().put("selection_rows", selections.size())
                .put("selection_candidate_count_sum", candidatePoolTotal)
                .put("selection_rows_with_zero_candidates", candidateHistogram.getOrDefault("0", 0L))
                .put("selection_rows_with_one_or_more_candidates", selections.size() - noMatch - skippedSelectionRows)
                .put("selection_rows_matched", matchedSelectionRows)
                .put("selection_rows_unmatched", noMatch)
                .put("no_control_selected", noControlSelected)
                .put("skipped_open_position", skippedOpen)
                .put("unresolved_event_execution", unresolvedEvent)
                .put("unresolved_control_execution", unresolvedControl)
                .put("unresolved_selected_control_execution", unresolvedSelectedControl)
                .put("merged_scheduled_lifecycle_clusters", independent.size());
        marginal.putNull("candidate_pool_rows_examined_sum")
                .put("candidate_pool_reopen_required", true);
        ArrayNode hist = array();
        candidateHistogram.forEach((key, value) -> hist.add(object().put("candidate_count", Integer.parseInt(key)).put("selection_rows", value)));
        ObjectNode reconciliation = object().put("source_result_content_sha256", result.path("content_sha256").asText(ownContentSha))
                .put("source_result_byte_sha256", sha256(source)).put("event_count", eventCount)
                .put("admitted_event_count", admitted).put("matched_control_count", matched)
                .put("event_independent_cluster_count", eventClusters)
                .put("paired_independent_cluster_count", pairedClusters)
                .put("selection_rows_reconciled", selections.size())
                .put("actual_pair_count_reconciled", completePairs)
                .put("scheduled_lifecycle_window_definition", "EVENT and CONTROL start at their decision times and each use the frozen 240h maximum window; clusters are unions of overlapping windows.")
                .put("outcome_blind", true).put("outcome_values_opened", false);
        ObjectNode output = object().put("schema", ATTRITION_SCHEMA).put("version", 1)
                .put("status", "RECONCILED_RAW_RESULT").put("lower_bound", true)
                .put("promotion_eligible", false).put("diagnostic_only", true)
                .put("outcome_blind", true);
        output.set("source", reconciliation);
        output.set("sequential_attrition", sequential);
        output.set("marginal_attrition", marginal);
        JsonNode embedded = result.path("matching_attrition");
        if (embedded.isObject() && embedded.path("physical_stage_attrition").isObject()) {
            output.set("physical_stage_attrition", embedded.path("physical_stage_attrition").deepCopy());
            output.put("physical_stage_receipt_status", "EMBEDDED_AUTHORITATIVE_MATCHER_INSTRUMENTATION");
            output.put("physical_stage_replay_required", true)
                    .put("physical_stage_replay_completed", true)
                    .put("physical_stage_replay_required_reason",
                            "RAW_HISTORICAL_STAGE_COUNTER_UNAVAILABLE;EMBEDDED_REPLAY_IS_DIAGNOSTIC_NOT_RAW_COUNTERS");
        } else {
            output.put("physical_stage_receipt_status", "UNAVAILABLE_IN_RETAINED_RESULT")
                    .put("physical_stage_replay_required", true)
                    .put("physical_stage_replay_completed", false)
                    .put("physical_stage_replay_required_reason", "RAW_HISTORICAL_STAGE_COUNTER_UNAVAILABLE");
        }
        if (input.has("physical_input") && input.has("baseline") && input.has("controls")) {
            ObjectNode replay = StrategyFixedBaselineV5.diagnosticMatchingStages(input);
            output.set("physical_producer_replay", replay);
            output.put("physical_stage_replay_completed", true)
                    .put("candidate_pool_reopen_completed", true)
                    .put("physical_stage_replay_required_reason",
                            "RAW_HISTORICAL_STAGE_COUNTER_UNAVAILABLE;PHYSICAL_PRODUCER_REPLAY_COMPLETED_FOR_DIAGNOSTIC");
        }
        output.set("candidate_count_histogram", hist);
        output.set("historical_reconciliation", object().put("expected_event_count", 130)
                        .put("expected_admitted_event_count", 126).put("expected_matched_control_count", 3)
                        .put("expected_event_cluster_count", 28).put("expected_paired_cluster_count", 3)
                        .put("event_count_matches", eventCount == 130).put("admitted_count_matches", admitted == 126)
                        .put("matched_count_matches", matched == 3).put("event_cluster_count_matches", eventClusters == 28)
                        .put("paired_cluster_count_matches", pairedClusters == 3));
        return withHash(output);
    }

    /**
     * Freeze a new outcome-blind control design as additive development
     * evidence.  The predecessor remains untouched and this method refuses
     * designs that mention outcome fields or claim promotion.
     */
    public static ObjectNode freezeSuccessorControlDesign(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        Path predecessorPath = path(input, "predecessor", "control_spec", "controls", "");
        if (predecessorPath == null || !Files.isRegularFile(predecessorPath, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("successor control design requires the frozen predecessor control spec");
        }
        ObjectNode predecessor = readObject(predecessorPath, "predecessor control spec");
        String predecessorSha = predecessor.path("content_sha256").asText("");
        if (!predecessorSha.matches("[a-f0-9]{64}") || !predecessorSha.equals(JsonHashes.ownHash(predecessor))) {
            throw failure("predecessor control spec hash is invalid");
        }
        String family = normalizeFamily(input.path("hypothesis_family").asText(
                predecessor.path("hypothesis_family").asText("fk-deleveraging-absorption")));
        String designId = input.path("design_id").asText("fk-deleveraging-absorption-controls-v003-development");
        ObjectNode result = object().put("schema", SUCCESSOR_SCHEMA).put("version", 1)
                .put("status", "FROZEN_DEVELOPMENT").put("design_id", designId)
                .put("hypothesis_family", family).put("predecessor_control_spec_sha256", predecessorSha)
                .put("predecessor_control_id", predecessor.path("control_id").asText())
                .put("outcome_blind", true).put("decision_time_only", true)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("outcomes_opened", false).put("development_exposure", true).put("development_only", true)
                .put("matching_rule_change_is_new_specification", true)
                .put("rationale", "The retained baseline produced 126 admitted event lifecycles but only three matched controls and three paired clusters. This successor expands the predeclared physical pool and records attrition before outcomes; it does not reinterpret the baseline result.")
                .put("candidate_pool_policy", "SAME_ASSET_FULL_FIVE_YEAR_PIT_LOOKBACK")
                .put("same_asset_first", true)
                .put("cross_asset_fallback", false)
                .put("reuse_policy", "WITHOUT_REPLACEMENT_WITHIN_ASSET_AND_CALENDAR_YEAR")
                .put("tie_break", "STANDARDIZED_DISTANCE_ASCENDING,event_time_ASCENDING,episode_id_ASCENDING")
                .put("history_expansion", "full physically retained five-year PIT window only; no synthetic rows")
                .put("maximum_prior_lookback_days", 1825)
                .put("minimum_prior_lag_hours", 480)
                .put("downside_return_min", -0.08)
                .put("downside_return_max", -0.02)
                .put("prior_30_bar_return_abs", 0.02)
                .put("prior_30_bar_realized_volatility_abs", 0.005)
                .put("prior_30_bar_volume_zscore_abs", 0.5)
                .put("independence_policy", "paired clusters remain merged scheduled lifecycle intervals; additional rows never increase independent count without disjoint windows")
                .put("null_control_policy", "retain unmatched and unresolved controls in denominator; missing execution remains explicit")
                .put("limitations", "Development design only. The wider historical lookback is a new specification and must be tested on held-out data after this immutable freeze.");
        ObjectNode inherited = object()
                .put("rule", "ALL_PREDECESSOR_V002_CONTROL_AND_BOUND_BASELINE_RULES_INHERITED_EXCEPT_LOOKBACK")
                .put("venue", predecessor.path("pool").path("venue").asText("BINANCE"))
                .put("instrument", predecessor.path("pool").path("instrument").asText("BINANCE_SPOT"))
                .put("signal_timeframe", predecessor.path("pool").path("signal_timeframe").asText("4h"))
                .put("same_hour_and_weekday_matching", true)
                .put("minimum_prior_lag_hours", predecessor.path("calipers").path("minimum_prior_lag_hours").asInt(480))
                .put("scheduled_lifecycle_window_hours", predecessor.path("calipers").path("maximum_lifecycle_hours").asInt(240))
                .put("entry_fee_rate", 0.001D).put("exit_fee_rate", 0.001D)
                .put("entry_slippage_rate", 0.00050D).put("exit_slippage_rate", 0.00050D)
                .put("costs_source", "BOUND_PREDECESSOR_BASELINE_EXECUTION_POLICY")
                .put("retained_rows_pit_verified_for_expanded_window", false);
        inherited.set("matching_variables", predecessor.path("matching_variables").deepCopy());
        inherited.set("pool", predecessor.path("pool").deepCopy());
        inherited.set("calipers_except_lookback", predecessor.path("calipers").deepCopy());
        result.set("inherited_predecessor_rules", inherited);
        result.set("successor_delta", object().put("maximum_prior_lookback_days_from",
                        predecessor.path("calipers").path("maximum_prior_lookback_days").asInt(365))
                .put("maximum_prior_lookback_days_to", 1825)
                .put("only_matching_rule_change", true));
        ArrayNode assets = array();
        for (String asset : List.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave")) assets.add(asset);
        result.set("required_assets", assets);
        ArrayNode forbidden = array(); OUTCOME_FIELDS.stream().sorted().forEach(forbidden::add);
        result.set("forbidden_fields", forbidden);
        ObjectNode predecessorSnapshot = object().put("control_id", predecessor.path("control_id").asText())
                .put("content_sha256", predecessorSha);
        predecessorSnapshot.set("matching_variables", predecessor.path("matching_variables").deepCopy());
        predecessorSnapshot.set("pool", predecessor.path("pool").deepCopy());
        predecessorSnapshot.set("calipers", predecessor.path("calipers").deepCopy());
        predecessorSnapshot.set("reuse_policy", predecessor.path("reuse_policy").deepCopy());
        predecessorSnapshot.set("tie_break", predecessor.path("tie_break").deepCopy());
        predecessorSnapshot.set("forbidden_fields", predecessor.path("forbidden_fields").deepCopy());
        result.set("predecessor_snapshot", predecessorSnapshot);
        String out = input.path("out").asText(input.path("output").asText("")).trim();
        ObjectNode hashed = withHash(result);
        if (!out.isEmpty()) writeImmutable(Path.of(out), hashed);
        return hashed;
    }

    private static ObjectNode exposureUnit(Path path, String byteSha, JsonNode container,
                                           JsonNode match, String family) {
        ObjectNode unit = object().put("path", path.toAbsolutePath().normalize().toString())
                .put("artifact_byte_sha256", byteSha).put("family", family)
                .put("schema", container.path("schema").asText(match.path("schema").asText("")))
                .put("row_content_sha256", JsonHashes.canonicalSha256(match));
        // Never borrow an identity from a parent/sibling candidate.  A nested
        // candidate row without its own identity is unresolved even when its
        // containing run has a run_id.
        String identity = firstIdentity(match);
        if (identity.isBlank()) {
            unit.putNull("identity").put("classification", "UNRESOLVABLE_ATTEMPT")
                    .put("reason", "matching family row has no stable attempt/behavior/data identity");
        } else {
            unit.put("identity", identity).put("classification", "IDENTITY_BOUND");
        }
        ObjectNode tuple = object();
        for (String field : List.of("behavior_sha256", "behavior_definition_sha256", "physical_input_sha256",
                "experiment_sha256", "executor_identity_sha256", "attempt_identity_sha256")) {
            JsonNode value = findRecursive(match, field);
            if (value != null && value.isValueNode()) tuple.set(field, value.deepCopy());
        }
        JsonNode candidateId = findRecursive(match, "candidate_id");
        if (candidateId != null && candidateId.isValueNode() && !candidateId.asText("").isBlank()) {
            unit.put("candidate_id", candidateId.asText());
        }
        unit.set("exposure_tuple", tuple);
        boolean outcomes = containsField(match, OUTCOME_FIELDS);
        unit.put("identity_kind", identityKind(match));
        unit.put("row_kind", rowKind(match));
        unit.put("outcome_fields_present", outcomes);
        if (identity.isBlank() && "TRADE_OUTCOME".equals(unit.path("row_kind").asText())
                && unit.has("candidate_id")) {
            unit.put("classification", "OUTCOME_JOIN_PENDING").put("reason",
                    "trade outcome carries a candidate key but no independent attempt custody tuple");
        }
        return unit;
    }

    private static List<JsonNode> matchingRows(JsonNode value, String family) {
        List<JsonNode> result = new ArrayList<>();
        collectMatches(value, family, result, new HashSet<>());
        return result;
    }

    private static void collectMatches(JsonNode value, String family, List<JsonNode> result, Set<String> seen) {
        if (value == null || value.isMissingNode()) return;
        String marker = Integer.toHexString(System.identityHashCode(value));
        if (!seen.add(marker)) return;
        if (value.isObject() && containsFamilyDeep(value, family) && isFamilyRecord(value, family)) result.add(value);
        if (value.isObject()) value.elements().forEachRemaining(child -> collectMatches(child, family, result, seen));
        else if (value.isArray()) value.elements().forEachRemaining(child -> collectMatches(child, family, result, seen));
    }

    private static boolean containsFamily(JsonNode value, String family) {
        for (String field : FAMILY_FIELDS) {
            JsonNode candidate = value.get(field);
            if (candidate == null) continue;
            if (candidate.isArray()) {
                for (JsonNode item : candidate) {
                    if (familyMatches(item.asText(""), family)) return true;
                }
            } else if (familyMatches(candidate.asText(""), family)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFamilyRecord(JsonNode value, String family) {
        if (value.path("candidate_id").isTextual() || value.path("behavior_sha256").isTextual()
                || value.path("behavior_definition_sha256").isTextual()
                || value.path("attempt_identity_sha256").isTextual()
                || value.path("run_id").isTextual() || value.path("experiment").isObject()) return true;
        String schema = value.path("schema").asText("");
        return (schema.startsWith("strategy-") || schema.startsWith("research-"))
                && containsFamily(value, family);
    }

    private static boolean containsFamilyDeep(JsonNode value, String family) {
        if (containsFamily(value, family)) return true;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) if (containsFamilyDeep(fields.next().getValue(), family)) return true;
        } else if (value.isArray()) {
            for (JsonNode child : value) if (containsFamilyDeep(child, family)) return true;
        }
        return false;
    }

    private static String identityKind(JsonNode value) {
        for (String field : ID_FIELDS) {
            JsonNode found = value.get(field);
            if (found != null && found.isValueNode() && !found.asText("").isBlank()) {
                if (field.startsWith("attempt")) return "ATTEMPT";
                if (field.startsWith("behavior")) return "BEHAVIOR";
                if (field.startsWith("physical")) return "DATASET";
                if (field.startsWith("experiment")) return "EXPERIMENT";
                return "RUN";
            }
        }
        return "NONE";
    }

    private static String rowKind(JsonNode value) {
        if (value.has("trade_id") || value.has("net_r") || value.has("net_pnl") || value.has("exit_price")) {
            return "TRADE_OUTCOME";
        }
        if (value.has("behavior_sha256") || value.has("behavior_definition_sha256")) return "CANDIDATE";
        if (value.path("metrics").isObject() || value.has("scope") || value.has("selected")) return "METRIC";
        return "ARTIFACT_RECORD";
    }

    private static boolean familyMatches(String raw, String family) {
        if (raw == null || raw.isBlank()) return false;
        String normalized = normalizeFamily(raw);
        return normalized.equals(family) || normalized.replace('-', '_').equals(family.replace('-', '_'))
                || (family.equals("fk-deleveraging-absorption") && normalized.equals("fk_deleveraging_absorption"));
    }

    private static String firstIdentity(JsonNode value) {
        for (String field : ID_FIELDS) {
            JsonNode found = findRecursive(value, field);
            if (found != null && found.isValueNode() && !found.asText("").isBlank()) return found.asText();
        }
        return "";
    }

    private static JsonNode findRecursive(JsonNode value, String field) {
        if (value == null || value.isMissingNode()) return null;
        if (value.isObject()) {
            JsonNode direct = value.get(field);
            if (direct != null && !direct.isNull()) return direct;
            var fields = value.fields();
            while (fields.hasNext()) {
                JsonNode found = findRecursive(fields.next().getValue(), field);
                if (found != null) return found;
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) {
                JsonNode found = findRecursive(child, field);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsField(JsonNode value, Set<String> fields) {
        for (String field : fields) if (findRecursive(value, field) != null) return true;
        return false;
    }

    private static ObjectNode unresolved(Path path, String reason, String detail) {
        return object().put("path", path.toAbsolutePath().normalize().toString())
                .put("classification", "UNRESOLVED_ARTIFACT").put("reason", reason).put("detail", detail == null ? "" : detail);
    }

    private static Path path(ObjectNode options, String first, String second, String fallback) {
        for (String key : List.of(first, second)) {
            String value = options.path(key).asText("").trim();
            if (!value.isEmpty()) return Path.of(value).toAbsolutePath().normalize();
        }
        if (fallback == null || fallback.isEmpty()) return null;
        return Path.of(fallback).toAbsolutePath().normalize();
    }

    private static Path path(ObjectNode options, String first, String second, String third, String fallback) {
        for (String key : List.of(first, second, third)) {
            String value = options.path(key).asText("").trim();
            if (!value.isEmpty()) return Path.of(value).toAbsolutePath().normalize();
        }
        return fallback == null || fallback.isEmpty() ? null : Path.of(fallback).toAbsolutePath().normalize();
    }

    private static ObjectNode readObject(Path path, String label) {
        try {
            JsonNode value = JsonHashes.mapper().readTree(Files.readAllBytes(path));
            if (!value.isObject()) throw failure(label + " must be a JSON object");
            return (ObjectNode) value;
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
            throw failure("cannot read " + label + ": " + error.getMessage());
        }
    }

    private static String sha256(Path path) {
        try { return JsonHashes.sha256(path); }
        catch (CustodyException error) {
            if (error.getCause() instanceof IOException cause) {
                throw failure("cannot hash source result: " + cause.getMessage());
            }
            throw error;
        }
    }

    private static ArrayNode arrayFrom(ObjectNode value, String field) {
        return value.path(field).isArray() ? (ArrayNode) value.path(field) : array();
    }

    private static String normalizeFamily(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace('_', '-').replaceAll("[^a-z0-9.-]", "");
    }

    private static String text(ObjectNode value, String first, String second, String fallback) {
        for (String field : List.of(first, second)) if (value.has(field) && !value.path(field).asText("").isBlank()) return value.path(field).asText();
        return fallback;
    }

    private static String safeMessage(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }

    private static ArrayNode readJsonLines(Path path, ArrayNode unresolved) {
        ArrayNode rows = array();
        try {
            int line = 0;
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line++;
                if (raw.isBlank()) continue;
                try { rows.add(JsonHashes.mapper().readTree(raw)); }
                catch (Exception error) { unresolved.add(unresolved(path, "JSONL_ROW_PARSE_FAILED", "line=" + line)); }
            }
        } catch (IOException error) {
            unresolved.add(unresolved(path, "JSONL_READ_FAILED", safeMessage(error)));
        }
        return rows;
    }

    private static int countRows(Path path, byte[] bytes) throws IOException {
        if (path.toString().endsWith(".jsonl")) {
            int count = 0;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) if (!line.isBlank()) count++;
            return count;
        }
        JsonNode value = JsonHashes.mapper().readTree(bytes);
        if (value != null && value.isArray()) return value.size();
        return value == null || value.isNull() ? 0 : 1;
    }

    private static ObjectNode withHash(ObjectNode value) {
        ObjectNode result = value.deepCopy(); result.remove("content_sha256");
        result.put("content_sha256", JsonHashes.canonicalSha256(result)); return result;
    }

    private static void writeImmutable(Path path, ObjectNode value) {
        try {
            Path target = path.toAbsolutePath().normalize();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ObjectNode existing = readObject(target, "existing evidence artifact");
                if (!existing.equals(value)) throw failure("refusing to overwrite an existing evidence artifact: " + target);
                return;
            }
            Path parent = target.getParent(); if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException error) { throw failure("cannot write evidence artifact: " + error.getMessage()); }
    }
}
