# Calculation speed review — 2026-09-14

This is a bounded read-only review of production source at commit 33065e15731e08fcbe6c2f99dff9657580e31d8b and small completed benchmark receipts. No profiler, build, benchmark, or production edit was run during Luna’s ongoing timing campaign. There is GC telemetry, but no method-level CPU/allocation profile in the evidence inspected. Opportunities below are hypotheses, not measured speedup claims.

## Measured context

The original four-worker/1024 MiB batches took 2094.071 and 2070.140 seconds; six workers took 2087.468 seconds. Higher-heap results currently validated: four/1280 = 1972.815 seconds, six/1280 = 1991.305 seconds, four/1536 = 1899.389 seconds. Six/1536 was censored. The four/1536 candidate is about 9.0% shorter than the historical six/1024 reference; a fresh matched confirmation pair is still underway. This does not establish the final default yet.

Aggregate worker GC pause fractions were about 16.4% at 1024 MiB, 14.1–14.3% at 1280 MiB, and 12.27% for four workers at 1536 MiB. These are summed worker pauses divided by summed worker lifetimes, not exact critical-path batch fractions or measurements of all concurrent GC CPU work. GC-only savings cannot justify a promised twofold speedup. Removing allocation and redundant computation may improve both GC and useful CPU work, so those benefits overlap.

## Priority 1: canonical encoding and repeated full-tree hashing

`analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/security/JsonHashes.java:128` and `analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyFixedBaselineCorrectedV1.java:556` sort object field names and call the general canonical encoder for each name/scalar. `analytics-contracts/src/main/java/com/tradinganalytics/contracts/json/CanonicalJson.java:20` serializes a one-element JSON wrapper, creates a JsonCanonicalizer, extracts a string, and later encodes UTF-8. This preserves correctness but repeats work for many small values and common keys.

First prototype: bounded caching of canonical bytes for common schema keys, a reusable scalar encoder that exactly matches the pinned canonicalizer, and reuse of encoded tokens across eligible digest streams. Preserve distinctions between canonical, serialized, semantic, and own-hash inputs, plus their exclusion rules and the order in which hashes enter outer documents. Do not globally cache arbitrary strings or mutable JSON trees. Differential tests must cover IEEE-754 number formatting, negative zero, Unicode/surrogates, UTF-16 key ordering, root-field exclusions, and all current artifact hashes.

Source standard: https://www.rfc-editor.org/rfc/rfc8785.html . Its number serialization and key ordering rules explain why replacing the encoder with ordinary toString or a normal JSON writer is insufficient.

## Priority 2: repeated bar parsing, hashing, copying, and timestamp conversion

`StrategyFixedBaselineV5.java:1077` reopens a detached bar role; at line 1106 the request receives a deep copy. `LifecycleTrustService.java:154` opens all trust receipts, and line 205 reopens them on use; `openReceipt` at line 321 reads bytes, validates byte identity, parses JSON, computes content/row-set hashes, and returns another deep copy. `TradeLifecycleV5.java:567` then copies every bar again, parses a timestamp into __time, and sorts it. `StrategyFixedBaselineV5.java:1689` parses bar timestamps again to produce marks.

The FULL generator builds event and control paths with 14,400-minute geometry across 450 pairs. Repeated per-row work can therefore matter much more than a small loop over final trade metrics. Investigate a lifecycle-scoped, verified immutable representation with timestamps and numeric fields decoded once. Preserve physical byte-reopen and mutation-detection guarantees: do not replace the trust contract with a cache keyed only by path or mtime. Byte verification may remain necessary even when repeated parsing/canonicalization is safely reusable. Prove ownership and mutation rejection with existing negative tests.

## Priority 3: duplicate result representations

`StrategyOperatingCharacteristicsSuccessorV1.java:486` deep-copies the entire portfolio into portfolio_summary, then retains it again inside raw_evaluator_result at line 493. The same raw result receives canonical and serialized hashes at lines 495–498; outer artifact hashing also traverses these retained branches. Existing publication optimizations removed other copies, but this one remains.

A proven immutable shared subtree can reduce retained memory without changing serialized bytes, but both occurrences still need writing/hashing under the current schema. Removing the duplicate serialized portfolio or replacing it with a reference needs a new artifact schema and reader/auditor compatibility. Keep the complete authoritative record and independent validation; do not relabel a reduced record as the old schema.

## Priority 4: portfolio replay and curve materialization

The corrected path first evaluates frozen V5, then calls correctFrozenResult (`StrategyOperatingCharacteristicsSuccessorV1.java:473–475`). Correction builds event and control books again. `StrategyFixedBaselinePortfolioCorrectionV1.java:139` copies each trade including marks, then replaces marks with a newly copied list. At line 297 each curve point sums all marked positions; line 317 sorts and serializes active trade IDs. `StrategyFixedBaselineCorrectedV1.java:451` collects both curves, parses their timestamps, and sorts them again.

Immediate candidates are avoiding copies of subtrees that will be replaced, reusing decoded timestamps, and reducing transient curve objects. A direct merge of already ordered book curves must preserve time/event/book/ordinal ordering; do not promise an asymptotic gain over Java’s adaptive sort, which may already handle the two ordered runs efficiently. Maintaining running marked totals can change floating-point summation and therefore hashes; it needs explicit numerical compatibility evidence, not only approximate P&L equality.

Longer term, a separately versioned corrected evaluator could build corrected accounting directly while keeping the frozen engine as the comparison oracle. The current contract includes a source frozen-result hash, so bypassing legacy result generation is not a drop-in deletion of duplicate work. Plan provenance/versioning and economic parity first.

## Potential savings and next experiment

No trustworthy percentage can be attributed to an individual method from source inspection alone. For planning, use conditional elapsed-time savings: if a target consumes 20% of runtime and is made twice as fast, total time falls 10%; if it consumes 40% and is twice as fast, time falls 20%; if it consumes 40% and is four times as fast, time falls 30%. Formula: new_time / old_time = (1 - fraction) + fraction / local_speedup.

A first engineering target of 10–25% less batch time (roughly 24–29 minutes from a rounded 32-minute baseline) is a hypothesis to test, not a forecast. These opportunities overlap and their percentages must not be added. There is no evidence yet supporting a promised 2x overall speedup.

After the current heap comparison and confirmation finish, run one separate FULL worker with JFR CPU/allocation/file-I/O evidence and phase timings for generation, lifecycle evaluation, correction, hashing, and publication. Do not attach a profiler to the current comparison. Oracle documents default.jfc as a continuous-recording configuration with typically under 1% overhead, while profile.jfc records more events for profiling; measure overhead for this application rather than assuming it. Reference: https://docs.oracle.com/en/java/javase/21/jfapi/flight-recorder-api-programmers-guide.pdf .

Prototype the largest measured avoidable cost first, rerun byte/hash and ownership/mutation tests, then compare complete eight-task batches against the same selected configuration. Preserve every trade, time boundary, ordering rule, cost, mark, random seed, and required audit. GPU migration, fewer repetitions, and dropping validation do not follow from this review.
