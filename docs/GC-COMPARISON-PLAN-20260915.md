# Java 21 collector comparison — 2026-09-15

The owner requested collector tests delegated to Luna after the completed scalar-encoding optimization. Compare collectors on the available M1 Pro / 16 GiB Mac. Windows i7-14700K / 32 GB results require a separate machine-specific experiment. This campaign changes engineering runtime configuration only; the reserved 300 repetitions, qualification profile, strategy parameters, and trading remain outside scope.

## Frozen comparison

Use the optimized executable and eight development seeds from the completed calculation-performance campaign, preserving all prior packages and evidence. Record exact executable, Java, harness, controller, validator, audit and profile identities. Create new campaign-local harness copies rather than altering archived files. Before timing, obtain independent plan and harness review, including negative tests for wrong collector/thread/profile binding and collector-specific GC parsing.

Hold six outer workers, 1536 MiB maximum heap per JVM, ActiveProcessorCount=1, and the same workload fixed. Test in this order:

1. G1, fresh baseline using the existing selected settings.
2. Serial GC.
3. Parallel GC with ParallelGCThreads=1.
4. Parallel GC with ParallelGCThreads=2.
5. Generational ZGC with UseZGC and ZGenerational explicitly enabled.

Run lightweight availability and effective-flag probes first. Record unsupported settings as unavailable, with the actual error; never silently substitute a collector. ActiveProcessorCount is an ergonomic input, not CPU affinity or a hard CPU quota. Verify the actual collector and relevant thread settings used by each process, retaining all launch flags. Do not infer ZGC performance from pause duration alone; concurrent work and allocation stalls also matter.

## Execution and comparison

Run on AC with sleep prevention, with no competing build, archive, profile or benchmark workloads. Preserve clock integrity and check disk capacity before each stage. User policy allows GC pauses, swap and memory pressure; these remain diagnostic and do not invalidate a correct complete run. Retain existing correctness, disk and process-failure handling.

The primary endpoint is elapsed time to complete all eight jobs, excluding separate validation. A stage becomes eligible only after eight strict validations, reference economics and all three curve comparisons, raw-byte preservation, and frozen independent replay of 7200 trades across 16 books pass. Keep the existing three provenance normalizations only. New collector logging requires accurate parsing, never a spurious zero for unsupported formats.

The initial slowdown censor is 1.25 times the prior selected-profile mean of 1341.981 seconds (1677.47625 seconds). Subsequently use 1.25 times the best fully validated current-campaign complete batch. If a fresh baseline itself exceeds the initial bound, preserve the interruption and investigate comparability before changing the bound or running alternatives. Censored timings are lower bounds and never ranked as successful completions. Preserve failures separately from slowdown censoring. Stop and verify cleanup of only campaign-owned processes.

After the five comparisons, repeat the best completed alternative followed by G1, reversing their original relative order. If no alternative beats G1, repeat the closest completed alternative to check the negative finding. If every alternative fails or is censored, confirm G1 and report the bounded result without claiming a global optimum. Report all timings, order and variation; adopt a different collector only when correctness passes and repeated batch measurements support a practical advantage. Retain G1 for an unresolved tie.

A maximum of three further full batches may refine heap, GC threads or outer worker count when observed evidence identifies a specific bottleneck or plausible improvement. Record the hypothesis and settings before execution. Keep effective concurrency at or below eight for this workload and include an appropriate same-setting comparator; do not label a changed-heap gain as an isolated collector effect. At most ten full batches are authorized by this bounded plan; unresolved questions should be reported rather than expanded into an open-ended search.

## Completion

Preserve immutable raw evidence and verified archives, publish compact receipts and a concise report, and independently review the final comparison and profile. If a new setting wins, update the actual runner-consumed local profile plus runbook and AGENTS; otherwise retain the existing G1 selection. Keep formal production/qualification settings untouched. Collector availability and platform-neutral Java do not establish Windows performance or runner compatibility.

Complete relevant harness tests and repository checks. Production Java edits are not planned; any necessary such changes require independent source review, a clean JDK21 reactor and at least 80% changed-line/branch coverage against the reviewed starting baseline. Commit scoped work, push to the existing PR13 branch, update its description, and verify CI on the exact pushed head. Do not merge the PR.

## Bounded recovery after the first controller-contract defect

The first fresh G1 worker batch completed all eight jobs in 1335.412622 seconds, passed timing integrity and all eight strict output validations, and its GC logs identified G1. The stage remains `FAILED` and is not an eligible timing because the campaign-local batch receipt omitted `parallel_gc_threads` and `jvm_gc_flags`; the controller correctly stopped before normalized-economics checks and the frozen independent replay. This is a runner metadata integration defect, not an accepted correctness result.

Repair only the campaign-local batch receipt to repeat those exact profile settings. Preserve the failed stage and its original campaign-state bytes, bind their hashes in a recovery receipt, and rerun a fresh G1 batch in a new immutable stage directory before any alternatives. The failed 1335.412622-second observation consumes one of the ten authorized full-batch launches and remains diagnostic only. Recovery is permitted only when the failure text matches this exact controller-contract error, all eight strict outputs passed, timing and parsed G1 identity passed, every child command matches the frozen profile, and no independent audit was reached. Any correctness defect or any other runtime failure remains terminal. The retry keeps the fixed six workers, 1536 MiB heap, ActiveProcessorCount=1, Java 21.0.11, package, seeds, and initial censor unchanged; the original collector ordering and confirmation rules then continue.
