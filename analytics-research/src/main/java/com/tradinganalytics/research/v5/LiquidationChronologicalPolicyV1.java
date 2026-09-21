package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

/** Frozen chronological folds and synchronized market-time resampling for liquidation v002. */
public final class LiquidationChronologicalPolicyV1 {
    public static final String SCHEMA = "liquidation-v2-chronological-fold-inventory/1";
    public static final String BOOTSTRAP_SCHEMA = "liquidation-v2-synchronized-block-sample/1";
    public static final int DEFAULT_DRAWS = 10_000;
    public static final long DEFAULT_SEED = 20_260_920L;
    public static final int MINIMUM_BLOCK_DAYS = 67;
    private static final int EMBARGO_DAYS = 7;
    private static final int OUTER_FOLD_COUNT = 8;
    private static final int MAX_DRAWS = 100_000;
    private static final Comparator<Observation> OBSERVATION_ORDER = Comparator
            .comparing(Observation::decisionTime).thenComparing(Observation::id);

    private LiquidationChronologicalPolicyV1() {}

    public enum OutcomeState { UNRESOLVED_NO_FILL, OPEN_TRADE, RESOLVED_NO_TRADE, CLOSED_TRADE }

    /** One candidate observation on the common market clock, including resolved no-trade zeros. */
    public record Observation(String id, Instant decisionTime, OutcomeState outcomeState,
            Instant outcomeAvailableTime, Instant firstFillTime, Instant exitTime) {
        /** Compatibility constructor: null/null is unresolved, fill/null is open, and fill/exit is closed. */
        public Observation(String id, Instant decisionTime, Instant firstFillTime, Instant exitTime) {
            this(id, decisionTime, stateFor(firstFillTime, exitTime), exitTime, firstFillTime, exitTime);
        }

        public Observation {
            if (id == null || id.isBlank()) throw failure("observation identity must be non-empty");
            Objects.requireNonNull(decisionTime, "decisionTime");
            Objects.requireNonNull(outcomeState, "outcomeState");
            if (outcomeState == OutcomeState.UNRESOLVED_NO_FILL
                    && (outcomeAvailableTime != null || firstFillTime != null || exitTime != null)) {
                throw failure("unresolved no-fill observation cannot carry outcome timestamps: " + id);
            }
            if (outcomeState == OutcomeState.OPEN_TRADE && (outcomeAvailableTime != null
                    || firstFillTime == null || exitTime != null || firstFillTime.isBefore(decisionTime))) {
                throw failure("open trade must carry only a first-fill time: " + id);
            }
            if (outcomeState == OutcomeState.RESOLVED_NO_TRADE && (outcomeAvailableTime == null
                    || outcomeAvailableTime.isBefore(decisionTime) || firstFillTime != null || exitTime != null)) {
                throw failure("resolved no-trade observation must carry its resolution time and no fills: " + id);
            }
            if (outcomeState == OutcomeState.CLOSED_TRADE && (outcomeAvailableTime == null
                    || firstFillTime == null || exitTime == null || firstFillTime.isBefore(decisionTime)
                    || exitTime.isBefore(firstFillTime) || outcomeAvailableTime.isBefore(exitTime))) {
                throw failure("observation lifecycle times are invalid: " + id);
            }
        }

        public static Observation unresolved(String id, Instant decisionTime) {
            return new Observation(id, decisionTime, OutcomeState.UNRESOLVED_NO_FILL, null, null, null);
        }

        public static Observation completed(String id, Instant decisionTime, Instant firstFillTime, Instant exitTime) {
            return new Observation(id, decisionTime, OutcomeState.CLOSED_TRADE, exitTime, firstFillTime, exitTime);
        }

        /** A paired comparison becomes known only when both arms resolve, after the latest arm exit. */
        public static Observation pairedCompleted(String id, Instant decisionTime, Instant firstFillTime,
                Instant finalExposureExitTime, Instant jointOutcomeAvailableTime) {
            return new Observation(id, decisionTime, OutcomeState.CLOSED_TRADE,
                    jointOutcomeAvailableTime, firstFillTime, finalExposureExitTime);
        }

        public static Observation resolvedNoTrade(String id, Instant decisionTime, Instant outcomeAvailableTime) {
            return new Observation(id, decisionTime, OutcomeState.RESOLVED_NO_TRADE,
                    outcomeAvailableTime, null, null);
        }

        public static Observation openTrade(String id, Instant decisionTime, Instant firstFillTime) {
            return new Observation(id, decisionTime, OutcomeState.OPEN_TRADE, null, firstFillTime, null);
        }

        public boolean hasResolvedOutcome() {
            return outcomeState == OutcomeState.RESOLVED_NO_TRADE || outcomeState == OutcomeState.CLOSED_TRADE;
        }
        public boolean hasCompletedTrade() { return outcomeState == OutcomeState.CLOSED_TRADE; }
    }

    /** The exact retained and excluded identity inventory for one inner chronological split. */
    public record InnerFold(
            String foldId,
            Instant rawValidationStart,
            Instant validationStart,
            Instant validationEndExclusive,
            Instant fitDecisionCutoffExclusive,
            Instant fitOutcomeAvailableBy,
            String status,
            List<String> fitIds,
            List<String> validationIds,
            SortedMap<String, String> excludedIds) {
        public InnerFold {
            fitIds = immutableSorted(fitIds);
            validationIds = immutableSorted(validationIds);
            excludedIds = immutableMap(excludedIds);
        }
        public List<String> purgedIds() { return idsWithReason(excludedIds, "PURGED_67D"); }
        public List<String> embargoedIds() { return idsWithReason(excludedIds, "EMBARGOED_7D"); }
        public List<String> actualOverlapIds() { return idsWithReason(excludedIds, "ACTUAL_OUTCOME_INTERVAL_OVERLAP"); }
        public ObjectNode toJson() {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("inner_fold_id", foldId).put("status", status);
            putInstant(row, "raw_validation_start", rawValidationStart);
            putInstant(row, "validation_start", validationStart);
            putInstant(row, "validation_end_exclusive", validationEndExclusive);
            putInstant(row, "fit_decision_cutoff_exclusive", fitDecisionCutoffExclusive);
            putInstant(row, "fit_outcome_available_by", fitOutcomeAvailableBy);
            row.set("fit_ids", strings(fitIds)); row.set("validation_ids", strings(validationIds));
            row.set("purged_ids", strings(purgedIds())); row.set("embargoed_ids", strings(embargoedIds()));
            row.set("actual_overlap_ids", strings(actualOverlapIds())); row.set("excluded", exclusionRows(excludedIds));
            return row;
        }
    }

    /** The exact quarterly OOS interval and corresponding expanding chronological inventory. */
    public record Fold(
            String foldId,
            Instant rawTestStart,
            Instant testStart,
            Instant testEndExclusive,
            Instant trainingDecisionCutoffExclusive,
            Instant trainingOutcomeAvailableBy,
            List<String> trainingIds,
            List<String> testIds,
            SortedMap<String, String> excludedIds,
            List<InnerFold> innerFolds) {
        public Fold {
            trainingIds = immutableSorted(trainingIds);
            testIds = immutableSorted(testIds);
            excludedIds = immutableMap(excludedIds);
            innerFolds = List.copyOf(innerFolds);
        }
        public List<String> purgedIds() { return idsWithReason(excludedIds, "PURGED_67D"); }
        public List<String> embargoedIds() { return idsWithReason(excludedIds, "EMBARGOED_7D"); }
        public List<String> actualOverlapIds() { return idsWithReason(excludedIds, "ACTUAL_OUTCOME_INTERVAL_OVERLAP"); }
        public ObjectNode toJson() {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("fold_id", foldId);
            putInstant(row, "raw_test_start", rawTestStart); putInstant(row, "test_start", testStart);
            putInstant(row, "test_end_exclusive", testEndExclusive);
            putInstant(row, "training_decision_cutoff_exclusive", trainingDecisionCutoffExclusive);
            putInstant(row, "training_outcome_available_by", trainingOutcomeAvailableBy);
            row.put("purge_days", MINIMUM_BLOCK_DAYS).put("embargo_days", EMBARGO_DAYS)
                    .put("actual_overlap_removal", true);
            row.set("training_ids", strings(trainingIds)); row.set("test_ids", strings(testIds));
            row.set("purged_ids", strings(purgedIds())); row.set("embargoed_ids", strings(embargoedIds()));
            row.set("actual_overlap_ids", strings(actualOverlapIds())); row.set("excluded", exclusionRows(excludedIds));
            ArrayNode inner = JsonHashes.mapper().createArrayNode();
            innerFolds.forEach(fold -> inner.add(fold.toJson())); row.set("inner_folds", inner);
            return row;
        }
    }

    /** Immutable, hash-bound fold inventory. Empty sets remain explicit diagnostics, never passes. */
    public record Inventory(String profileSha256, String observationsSha256, int observationCount,
            List<Fold> folds) {
        public Inventory {
            folds = List.copyOf(folds);
            if (folds.size() != OUTER_FOLD_COUNT) throw failure("v002 inventory must contain exactly eight outer folds");
        }
        public ObjectNode toJson() {
            ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", SCHEMA).put("version", 1)
                    .put("profile_sha256", profileSha256).put("observations_sha256", observationsSha256)
                    .put("observation_count", observationCount)
                    .put("outer_fold_count", OUTER_FOLD_COUNT).put("training_policy", "EXPANDING_CHRONOLOGICAL_ONLY")
                    .put("test_windows", "EXACT_UTC_QUARTERS_2024_2025")
                    .put("minimum_purge_days", MINIMUM_BLOCK_DAYS).put("embargo_days", EMBARGO_DAYS)
                    .put("embargo_clock", "VALIDATION_START_EQUALS_RAW_START_PLUS_SEVEN_DAYS")
                    .put("actual_first_fill_exit_overlap_removal", true)
                    .put("evidence_label", "DEVELOPMENT_ONLY_PROFILE_BOUND_DIAGNOSTIC");
            ArrayNode rows = JsonHashes.mapper().createArrayNode(); folds.forEach(fold -> rows.add(fold.toJson()));
            value.set("folds", rows); value.put("content_sha256", JsonHashes.ownHash(value)); return value;
        }
    }

    /** One already-synchronized market-time block containing every asset/candidate row for that interval. */
    public record MarketTimeBlock(String blockId, Instant startInclusive, Instant endExclusive,
            List<String> synchronizedObservationIds) {
        public MarketTimeBlock {
            if (blockId == null || blockId.isBlank()) throw failure("market-time block identity must be non-empty");
            Objects.requireNonNull(startInclusive, "startInclusive");
            Objects.requireNonNull(endExclusive, "endExclusive");
            if (!endExclusive.isAfter(startInclusive)
                    || Duration.between(startInclusive, endExclusive).compareTo(Duration.ofDays(MINIMUM_BLOCK_DAYS)) < 0) {
                throw failure("synchronized market-time blocks must span at least 67 calendar days");
            }
            synchronizedObservationIds = immutableSorted(synchronizedObservationIds);
        }
    }

    /** The same selected block identities are applied to every asset and candidate in a replay. */
    public record BlockDraw(int drawIndex, List<String> sampledBlockIds) {
        public BlockDraw { sampledBlockIds = List.copyOf(sampledBlockIds); }
    }

    /** Deterministic index selection only. This object intentionally contains no metrics or p-values. */
    public record SynchronizedBlockSample(String profileSha256, String inputBlocksSha256, int blockDays,
            long seed, int drawCount, List<BlockDraw> draws) {
        public SynchronizedBlockSample { draws = List.copyOf(draws); }
        public ObjectNode toJson() {
            ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", BOOTSTRAP_SCHEMA).put("version", 1)
                    .put("profile_sha256", profileSha256).put("input_blocks_sha256", inputBlocksSha256)
                    .put("minimum_block_days", blockDays).put("draw_count", drawCount).put("seed", seed)
                    .put("synchronization_axis", "SHARED_MARKET_TIME_BLOCK_IDS_ACROSS_ASSETS_AND_CANDIDATES")
                    .put("purpose", "SYNCHRONIZED_INDEX_SELECTION_ONLY")
                    .put("metrics_emitted", false).put("statistical_significance_claimed", false);
            ArrayNode rows = JsonHashes.mapper().createArrayNode();
            for (BlockDraw draw : draws) {
                ObjectNode row = JsonHashes.mapper().createObjectNode().put("draw_index", draw.drawIndex());
                row.set("sampled_block_ids", strings(draw.sampledBlockIds())); rows.add(row);
            }
            value.set("draws", rows); value.put("content_sha256", JsonHashes.ownHash(value)); return value;
        }
    }

    /** Builds eight exact quarterly outer windows and two training-only inner splits per outer fold. */
    public static Inventory buildInventory(ObjectNode profile, Collection<Observation> observations) {
        LiquidationDailyStressProfileV1.validate(Objects.requireNonNull(profile, "profile"));
        List<Observation> rows = normalizeObservations(observations);
        Instant decisionStart = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant decisionEnd = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        Instant executionEnd = Instant.parse(profile.path("windows").path("execution_end_exclusive").asText());
        int purgeDays = profile.path("windows").path("minimum_outer_and_inner_purge_days").asInt(-1);
        int embargoDays = profile.path("windows").path("embargo_days").asInt(-1);
        if (purgeDays != MINIMUM_BLOCK_DAYS || embargoDays != EMBARGO_DAYS) {
            throw failure("v002 fold inventory requires the frozen 67-day purge and seven-day embargo");
        }
        ArrayList<Fold> folds = new ArrayList<>(OUTER_FOLD_COUNT);
        for (int index = 0; index < OUTER_FOLD_COUNT; index++) {
            Instant rawStart = quarterStart(index);
            Instant end = quarterStart(index + 1);
            Instant testStart = rawStart.plus(Duration.ofDays(embargoDays));
            Instant trainingDecisionCutoff = rawStart.minus(Duration.ofDays(purgeDays));
            List<Observation> test = rows.stream().filter(row -> row.decisionTime().compareTo(testStart) >= 0
                    && row.decisionTime().isBefore(end) && inDecisionWindow(row, decisionStart, decisionEnd)
                    && hasUsableOutcome(row, executionEnd)).sorted(OBSERVATION_ORDER).toList();
            List<OutcomeInterval> testIntervals = test.stream().filter(Observation::hasCompletedTrade)
                    .map(OutcomeInterval::of).toList();
            ArrayList<String> trainIds = new ArrayList<>(), testIds = new ArrayList<>();
            TreeMap<String, String> excluded = new TreeMap<>();
            for (Observation row : rows) {
                if (!inDecisionWindow(row, decisionStart, decisionEnd)) {
                    excluded.put(row.id(), "OUTSIDE_FROZEN_DECISION_WINDOW");
                } else if (row.decisionTime().compareTo(rawStart) >= 0 && row.decisionTime().isBefore(end)) {
                    if (row.decisionTime().isBefore(testStart)) {
                        excluded.put(row.id(), "EMBARGOED_7D");
                    } else if (hasUsableOutcome(row, executionEnd)) testIds.add(row.id());
                    else excluded.put(row.id(), !row.hasResolvedOutcome() ? "OUTCOME_UNRESOLVED"
                            : "OUTCOME_AT_OR_AFTER_FROZEN_EXECUTION_CUTOFF");
                } else if (row.decisionTime().isBefore(rawStart)) {
                    if (hasCompletedOutcome(row) && overlapsAny(row, testIntervals)) {
                        excluded.put(row.id(), "ACTUAL_OUTCOME_INTERVAL_OVERLAP");
                    } else if (!row.decisionTime().isBefore(trainingDecisionCutoff)) {
                        excluded.put(row.id(), "PURGED_67D");
                    } else if (!row.hasResolvedOutcome()) {
                        excluded.put(row.id(), "OUTCOME_UNRESOLVED");
                    } else if (row.outcomeAvailableTime().isAfter(rawStart)) {
                        excluded.put(row.id(), "TRAIN_LABEL_NOT_AVAILABLE_BY_TEST_BOUNDARY");
                    } else {
                        trainIds.add(row.id());
                    }
                } else {
                    excluded.put(row.id(), "AFTER_OUTER_TEST_WINDOW");
                }
            }
            trainIds.sort(String::compareTo); testIds.sort(String::compareTo);
            List<Observation> train = rowsByIds(rows, trainIds);
            List<InnerFold> innerFolds = makeInnerFolds("outer-" + (index + 1), train, trainingDecisionCutoff,
                    executionEnd, purgeDays, embargoDays);
            folds.add(new Fold("outer-" + (index + 1), rawStart, testStart, end, trainingDecisionCutoff, rawStart,
                    trainIds, testIds, excluded, innerFolds));
        }
        String observationsHash = JsonHashes.canonicalSha256(observationArray(rows));
        return new Inventory(profile.path("content_sha256").asText(), observationsHash, rows.size(), folds);
    }

    /** Reopens the supplied inventory by deterministic reconstruction from its bound inputs. */
    public static boolean validateInventory(ObjectNode profile, Collection<Observation> observations,
            JsonNode inventory) {
        if (inventory == null || !inventory.isObject() || !SCHEMA.equals(inventory.path("schema").asText())
                || inventory.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(inventory).equals(inventory.path("content_sha256").asText())) {
            throw failure("chronological fold inventory is missing, malformed, or hash-tampered");
        }
        ObjectNode expected = buildInventory(profile, observations).toJson();
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(inventory))) {
            throw failure("chronological fold inventory does not match its frozen profile and observations");
        }
        return true;
    }

    /** Uses the frozen default of 10,000 synchronized block-index draws with seed 20260920. */
    public static SynchronizedBlockSample sampleSynchronizedBlocks(ObjectNode profile,
            Collection<MarketTimeBlock> blocks) {
        return sampleSynchronizedBlocks(profile, blocks, DEFAULT_DRAWS, DEFAULT_SEED);
    }

    /**
     * Samples whole market-time blocks with replacement. A block is a shared identity bundle; callers
     * apply each sampled block ID to every asset and candidate, so their chronology cannot desynchronize.
     */
    public static SynchronizedBlockSample sampleSynchronizedBlocks(ObjectNode profile,
            Collection<MarketTimeBlock> blocks, int draws, long seed) {
        LiquidationDailyStressProfileV1.validate(Objects.requireNonNull(profile, "profile"));
        if (draws < 1 || draws > MAX_DRAWS) throw failure("draw count must be within [1, 100000]");
        int minimumBlockDays = profile.path("fold_contract").path("outer_and_inner_purge_days").asInt(-1);
        if (minimumBlockDays != MINIMUM_BLOCK_DAYS) throw failure("v002 resampling requires synchronized blocks of at least 67 days");
        List<MarketTimeBlock> ordered = normalizeBlocks(blocks, minimumBlockDays);
        String inputHash = JsonHashes.canonicalSha256(blockArray(ordered));
        SplittableRandom random = new SplittableRandom(seed);
        ArrayList<BlockDraw> output = new ArrayList<>(draws);
        for (int draw = 0; draw < draws; draw++) {
            ArrayList<String> selected = new ArrayList<>(ordered.size());
            for (int slot = 0; slot < ordered.size(); slot++) {
                selected.add(ordered.get(random.nextInt(ordered.size())).blockId());
            }
            output.add(new BlockDraw(draw, selected));
        }
        return new SynchronizedBlockSample(profile.path("content_sha256").asText(), inputHash,
                minimumBlockDays, seed, draws, output);
    }

    private static List<InnerFold> makeInnerFolds(String outerId, List<Observation> training,
            Instant outerTrainingCutoff, Instant executionEnd, int purgeDays, int embargoDays) {
        TreeSet<Instant> uniqueTimes = new TreeSet<>();
        training.forEach(row -> uniqueTimes.add(row.decisionTime()));
        ArrayList<InnerFold> result = new ArrayList<>(2);
        if (uniqueTimes.size() < 3) {
            for (int inner = 1; inner <= 2; inner++) result.add(new InnerFold(outerId + "-inner-" + inner,
                    null, null, null, null, null, "INSUFFICIENT_CHRONOLOGICAL_MARKET_TIMES",
                    List.of(), List.of(), new TreeMap<>()));
            return List.copyOf(result);
        }
        List<Instant> times = List.copyOf(uniqueTimes);
        Instant firstBoundary = times.get(times.size() / 3);
        Instant secondBoundary = times.get((times.size() * 2) / 3);
        for (int inner = 1; inner <= 2; inner++) {
            Instant rawStart = inner == 1 ? firstBoundary : secondBoundary;
            Instant rawEnd = inner == 1 ? secondBoundary : outerTrainingCutoff;
            Instant validationStart = rawStart.plus(Duration.ofDays(embargoDays));
            Instant fitDecisionCutoff = rawStart.minus(Duration.ofDays(purgeDays));
            List<Observation> validation = training.stream().filter(row -> row.decisionTime().compareTo(validationStart) >= 0
                    && row.decisionTime().isBefore(rawEnd) && hasUsableOutcome(row, executionEnd))
                    .sorted(OBSERVATION_ORDER).toList();
            List<OutcomeInterval> validationIntervals = validation.stream().filter(Observation::hasCompletedTrade)
                    .map(OutcomeInterval::of).toList();
            ArrayList<String> fitIds = new ArrayList<>(), validationIds = new ArrayList<>();
            TreeMap<String, String> excluded = new TreeMap<>();
            for (Observation row : training) {
                if (row.decisionTime().compareTo(rawStart) >= 0 && row.decisionTime().isBefore(rawEnd)
                        && row.decisionTime().isBefore(validationStart)) {
                    excluded.put(row.id(), "EMBARGOED_7D");
                } else if (row.decisionTime().compareTo(validationStart) >= 0 && row.decisionTime().isBefore(rawEnd)) {
                    if (hasUsableOutcome(row, executionEnd)) validationIds.add(row.id());
                    else excluded.put(row.id(), !row.hasResolvedOutcome() ? "OUTCOME_UNRESOLVED"
                            : "OUTCOME_AT_OR_AFTER_FROZEN_EXECUTION_CUTOFF");
                } else if (row.decisionTime().isBefore(rawStart)) {
                    if (hasCompletedOutcome(row) && overlapsAny(row, validationIntervals)) {
                        excluded.put(row.id(), "ACTUAL_OUTCOME_INTERVAL_OVERLAP");
                    } else if (!row.decisionTime().isBefore(rawStart.minus(Duration.ofDays(purgeDays)))) {
                        excluded.put(row.id(), "PURGED_67D");
                    } else if (!row.hasResolvedOutcome()) {
                        excluded.put(row.id(), "OUTCOME_UNRESOLVED");
                    } else if (row.outcomeAvailableTime().isAfter(rawStart)) {
                        excluded.put(row.id(), "TRAIN_LABEL_NOT_AVAILABLE_BY_VALIDATION_BOUNDARY");
                    } else {
                        fitIds.add(row.id());
                    }
                } else {
                    excluded.put(row.id(), "AFTER_INNER_VALIDATION_WINDOW");
                }
            }
            fitIds.sort(String::compareTo); validationIds.sort(String::compareTo);
            result.add(new InnerFold(outerId + "-inner-" + inner, rawStart, validationStart, rawEnd,
                    fitDecisionCutoff, rawStart, "CHRONOLOGICAL_SPLIT", fitIds, validationIds, excluded));
        }
        return List.copyOf(result);
    }

    private static boolean inDecisionWindow(Observation row, Instant start, Instant end) {
        return !row.decisionTime().isBefore(start) && row.decisionTime().isBefore(end);
    }

    private static boolean hasCompletedOutcome(Observation row) { return row.hasCompletedTrade(); }

    private static boolean hasUsableOutcome(Observation row, Instant executionEnd) {
        return row.hasResolvedOutcome() && row.outcomeAvailableTime().isBefore(executionEnd);
    }

    private static boolean overlapsAny(Observation row, List<OutcomeInterval> intervals) {
        if (!row.hasCompletedTrade()) return false;
        for (OutcomeInterval interval : intervals) {
            // Closed boundaries conservatively retain a shared execution instant, including zero-duration fills.
            if (!row.firstFillTime().isAfter(interval.exitInclusive())
                    && !interval.firstFillInclusive().isAfter(row.exitTime())) return true;
        }
        return false;
    }

    private record OutcomeInterval(Instant firstFillInclusive, Instant exitInclusive) {
        private static OutcomeInterval of(Observation row) {
            return new OutcomeInterval(row.firstFillTime(), row.exitTime());
        }
    }

    private static List<Observation> normalizeObservations(Collection<Observation> observations) {
        if (observations == null) throw failure("observations are required");
        ArrayList<Observation> rows = new ArrayList<>(observations.size());
        TreeSet<String> ids = new TreeSet<>();
        for (Observation row : observations) {
            if (row == null) throw failure("observation rows cannot be null");
            if (!ids.add(row.id())) throw failure("duplicate observation identity: " + row.id());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(Observation::id));
        return List.copyOf(rows);
    }

    private static List<Observation> rowsByIds(List<Observation> rows, List<String> ids) {
        Set<String> selected = Set.copyOf(ids);
        return rows.stream().filter(row -> selected.contains(row.id())).sorted(OBSERVATION_ORDER).toList();
    }

    private static List<MarketTimeBlock> normalizeBlocks(Collection<MarketTimeBlock> blocks, int minimumDays) {
        if (blocks == null || blocks.isEmpty()) throw failure("at least one synchronized market-time block is required");
        ArrayList<MarketTimeBlock> ordered = new ArrayList<>(blocks);
        if (ordered.stream().anyMatch(Objects::isNull)) throw failure("market-time block rows cannot be null");
        ordered.sort(Comparator.comparing(MarketTimeBlock::startInclusive).thenComparing(MarketTimeBlock::blockId));
        TreeSet<String> blockIds = new TreeSet<>(), observationIds = new TreeSet<>();
        Instant previousEnd = null;
        for (MarketTimeBlock block : ordered) {
            if (!blockIds.add(block.blockId())) throw failure("duplicate market-time block identity: " + block.blockId());
            if (Duration.between(block.startInclusive(), block.endExclusive()).compareTo(Duration.ofDays(minimumDays)) < 0) {
                throw failure("market-time block is shorter than the frozen 67-day minimum: " + block.blockId());
            }
            if (previousEnd != null && block.startInclusive().isBefore(previousEnd)) {
                throw failure("synchronized market-time blocks must not overlap");
            }
            previousEnd = block.endExclusive();
            for (String id : block.synchronizedObservationIds()) if (!observationIds.add(id)) {
                throw failure("observation identity appears in multiple market-time blocks: " + id);
            }
        }
        return List.copyOf(ordered);
    }

    private static ObjectNode observationArray(List<Observation> rows) {
        ArrayNode array = JsonHashes.mapper().createArrayNode();
        for (Observation row : rows) {
            ObjectNode value = JsonHashes.mapper().createObjectNode().put("id", row.id())
                    .put("outcome_state", row.outcomeState().name());
            putInstant(value, "decision_time", row.decisionTime());
            putInstant(value, "outcome_available_time", row.outcomeAvailableTime());
            putInstant(value, "first_fill_time", row.firstFillTime()); putInstant(value, "exit_time", row.exitTime());
            array.add(value);
        }
        ObjectNode wrapped = JsonHashes.mapper().createObjectNode(); wrapped.set("observations", array); return wrapped;
    }

    private static ArrayNode blockArray(List<MarketTimeBlock> blocks) {
        ArrayNode array = JsonHashes.mapper().createArrayNode();
        for (MarketTimeBlock block : blocks) {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("block_id", block.blockId());
            putInstant(row, "start_inclusive", block.startInclusive()); putInstant(row, "end_exclusive", block.endExclusive());
            row.set("synchronized_observation_ids", strings(block.synchronizedObservationIds())); array.add(row);
        }
        return array;
    }

    private static ArrayNode strings(Collection<String> values) {
        ArrayNode array = JsonHashes.mapper().createArrayNode(); values.forEach(array::add); return array;
    }

    private static ArrayNode exclusionRows(SortedMap<String, String> exclusions) {
        ArrayNode array = JsonHashes.mapper().createArrayNode();
        exclusions.forEach((id, reason) -> array.addObject().put("id", id).put("reason", reason));
        return array;
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) node.putNull(field); else node.put(field, value.toString());
    }

    private static OutcomeState stateFor(Instant firstFillTime, Instant exitTime) {
        if (firstFillTime == null && exitTime == null) return OutcomeState.UNRESOLVED_NO_FILL;
        if (firstFillTime != null && exitTime == null) return OutcomeState.OPEN_TRADE;
        if (firstFillTime != null) return OutcomeState.CLOSED_TRADE;
        throw failure("an exit time cannot exist without a first fill");
    }

    private static Instant quarterStart(int index) {
        if (index < 0 || index > OUTER_FOLD_COUNT) throw failure("outer quarter index is outside frozen range");
        int month = 1 + (index % 4) * 3;
        int year = 2024 + (index / 4);
        return quarterBoundary(year, month);
    }

    private static Instant quarterBoundary(int year, int month) {
        return YearMonth.of(year, month).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static List<String> idsWithReason(SortedMap<String, String> values, String reason) {
        return values.entrySet().stream().filter(entry -> reason.equals(entry.getValue()))
                .map(Map.Entry::getKey).toList();
    }

    private static List<String> immutableSorted(Collection<String> values) {
        if (values == null) throw failure("identity collection is required");
        return List.copyOf(new TreeSet<>(values));
    }

    private static SortedMap<String, String> immutableMap(Map<String, String> values) {
        TreeMap<String, String> copy = new TreeMap<>();
        if (values != null) copy.putAll(new LinkedHashMap<>(values));
        return java.util.Collections.unmodifiableSortedMap(copy);
    }

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}
