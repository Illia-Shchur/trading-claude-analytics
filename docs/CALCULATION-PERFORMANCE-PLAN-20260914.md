# Calculation performance implementation and worker retuning — 2026-09-14

## Objective and ownership

Implement worthwhile calculation speedups, then remeasure the Mac worker/heap combinations on the new executable and install reproducible local runtime profiles based on complete eight-task batch time. The user explicitly authorized stopping the old campaign, implementation by **gpt-5.6-luna with xhigh reasoning**, new tests, and another empirical configuration search. This plan supersedes the old heap-expansion completion handoff.

The Luna implementation owner must carry the work through code, correctness tests, independent review, a newly frozen package, runtime profile search, final evidence, documentation, commit/push, and exact-head CI. Use the existing PR #13 branch; do not merge it. No held-out seeds, strategy calibration, formal qualification, trading, or changes to strategy parameters are authorized by this engineering task.

## Preserve the stopped campaign

Workspace: `/Users/eternal/.codex/worktrees/trading-pr13-performance`, branch `codex/finish-pr13`; pre-change HEAD `33065e15731e08fcbe6c2f99dff9657580e31d8b`. The same commit is pushed to `origin/codex/corrected-worker-qualification`, with nine CI checks passing. Root sent SIGINT to the verified controller for `confirm-4w-1536`; its terminal status is INTERRUPTED and the observed benchmark JVMs have exited. Collect the former owner's stop checkpoint and session result. Never resume the obsolete `confirm-6w-1024` stage.

Preserve all original evidence and frozen source/package files. Old roots: `.report-run/throughput-search-20260913/` and `.report-run/throughput-heap-expansion-20260913/`. Existing permanent archive custody: `/Users/eternal/.codex/artifacts/trading-pr13-performance/throughput-search-20260913/`. Archive the newer completed, censored, and interrupted stages with checksums before any destructive cleanup. An interrupted or censored stage is never a completed timing.

The validated older-code observations are:

| Workers | Heap per worker | Eight-task worker time | Status |
| ---: | ---: | ---: | --- |
| 4 | 1024 MiB | 2094.071 and 2070.140 s | PASS |
| 6 | 1024 MiB | 2087.468 s | PASS |
| 4 | 1280 MiB | 1972.815 s | PASS |
| 6 | 1280 MiB | 1991.305 s | PASS |
| 4 | 1536 MiB | 1899.389 s | PASS, one observation |
| 6 | 1536 MiB | Incomplete at the 2504.962 s censor | CENSORED |
| 8 | 1024 / 896 MiB | Incomplete beyond the prior slowdown bound | CENSORED |

These guide starting points, not limits for the improved code. The 4×1536 result was not fully confirmed before the user stopped the campaign. Do not describe it as a final optimum.

## Phase 1: measure and rank avoidable work

Read `.report-run/calculation-speed-review-20260914.md` for source locations and the distinction between observed code and unmeasured speedup hypotheses. No CPU profile was collected for that review. Before substantial refactoring, collect one isolated representative FULL-worker CPU/allocation/file-I/O profile of the pre-change executable, including generation, lifecycle evaluation, correction, hashing, and publication. JFR is suitable; profiling runs must be labeled separately and never used as clean timing evidence. Capture the entire workload so late hashing/publication is not missed. Do not run profiling, builds, archives, or other heavy work concurrently with benchmark timing.

Retain the existing pinned JDK 21 and exact eight development seeds/geometry. Determine time and allocations by phase; rank implementation choices by measured avoidable cost, correctness risk, and expected end-to-end gain. Any numeric gain estimate must use the measured fraction and a stated local speedup assumption. The earlier 10–25% target was a hypothesis, not a guaranteed outcome.

## Phase 2: implement, highest measured value first

1. **Canonical encoding and hashes.** Remove repeated general-purpose canonicalizer work for common field names and scalar values. Consider bounded caching of encoded schema keys, reusable scalar encoding and token reuse across compatible digest passes. Preserve RFC 8785 number formatting, Unicode/surrogate handling, UTF-16 property order, exclusions, and differences between canonical/serialized/economic/own hashes. A normal JSON writer or `Double.toString` is not an assumed substitute. Avoid unbounded caches of external values and caching mutable trees.
2. **Bar processing.** Reduce repeated parse/copy/timestamp conversions with a lifecycle-scoped verified immutable representation. Preserve physical byte-reopen, path confinement, mutation rejection, cost bindings, and trust-token semantics. Do not cache solely by path or mtime, and do not retain every episode in memory. Prefer ownership-aware local improvements over changing the public contract.
3. **Duplicate portfolio representation.** Remove avoidable deep copies while proving immutability and ownership isolation. The full `portfolio_summary` currently duplicates the portfolio in `raw_evaluator_result`; sharing a proven immutable representation can save RAM, but current serialized bytes still contain both. Removing serialized fields requires an explicitly versioned contract and reader/auditor migration. Prefer a compatible implementation in this PR; do not silently weaken the record.
4. **Correction and curve allocation.** Avoid copying mark arrays that will immediately be replaced, reuse decoded times, and reduce temporary curve objects. Any curve merge must preserve exact timestamp/event/book/ordinal order. Running sums can change floating-point results: retain exact numerical behavior or reject the optimization. The current corrected output binds a frozen source-result hash, so directly deleting legacy evaluation is not a compatible shortcut. A new direct corrected evaluator is optional only if profiling justifies it and versioned provenance/economic equivalence can be demonstrated; otherwise document it as deferred rather than expanding the task into an uncontrolled rewrite.

Implement measured, behavior-preserving improvements from these priorities. Do not force all speculative refactors into the patch. Document any deferred item and the measured reason. Keep the frozen V5/original correction contracts stable; modifications to shared utilities must preserve their outputs and negative cases.

## Phase 3: correctness and package freeze

Run affected Java and Python tests, exact canonical/serialized hash and artifact parity vectors, mutable-input isolation tests, trust/mutation negative cases, time-boundary/order cases, and corrected portfolio/economic replay. No changes to seeds, trade count, data horizon, costs, stops, scoring, sampling frequency, or mandatory audit coverage are performance optimizations.

Run the required clean JDK 21 reactor and aggregate coverage gate after Java changes. For added/modified production Java, use the explicit reviewed pre-change baseline and at least 80% changed-line / 80% changed-branch coverage as required by the user's instructions. Preserve the distinction from any older whole-PR 55% CI gate; never claim the latter meets 80/80. Resolve failures, then obtain independent source review before freezing a new package.

Commit the reviewed implementation and its tests/plan before the measurement package is frozen, so the manifest identifies a clean reproducible source commit; build from that exact commit and record its checks. Use a separate later commit for final evidence and profile selection.

Create a fresh campaign root `.report-run/calculation-performance-20260914/`. Freeze the new executable, source fingerprint, manifest, plans, runtime profile bindings, harness and auditor identities. Adapt an isolated controller copy to the new expected manifest; do not disable manifest validation or edit the previous controller/capsule. All package identity changes must be explicit and reviewed. Preserve the same baseline comparison and three-path in-memory provenance normalization; an unexpected mismatch is a defect to investigate, not permission to remove more fields.

Do not rebuild or edit a package while its measurement campaign runs. If code changes become necessary after a failure, create a new package/campaign identity and repeat affected comparisons.

## Phase 4: real local profiles and a bounded adaptive search

Create machine-readable **local engineering runtime profiles**, consumed by the runner/CLI, carrying actual outer workers, per-JVM Xmx, ActiveProcessorCount, collector, and evidence references. Initial values are empirical starting estimates; final selected values come from validated full-batch timings. Record observed RSS/GC/swap separately from configured heap. Do not merely update prose while leaving the runnable configuration unchanged.

Keep these runtime overrides distinct from frozen production/qualification profiles. Updating a local engineering profile does not qualify this 16 GiB Mac for an unchanged formal target. Do not relabel an estimated RSS value as a proven hard maximum or falsify the worker's bound execution envelope.

Use AC power and `caffeinate -is`. Require clock integrity, preserve OOM/nonzero/correctness failures and disk/watchdog limits. GC counts, pauses, RSS, compression, pressure, and swap remain diagnostics only; Mac usability is not an acceptance condition. Choose by complete makespan of the same eight tasks, excluding separate validation time.

Start with the following new-code coarse comparisons, one at a time, each followed by full validation:

- 4 workers × 1536 MiB: code-effect anchor against the old fastest observed configuration.
- 4 workers × 1280 MiB: test whether lower allocation permits less heap without a batch penalty.
- 6 workers × 1280 MiB and 6 workers × 1024 MiB: test density after allocation changes.
- 8 workers at the best promising validated heap from the new-code evidence, initially 1024 or 1280 MiB; do not assume the old eight-worker slowdown still applies.

Adapt order or prune clearly dominated candidates only with a prospective written decision receipt. Use measured live-heap/RSS/GC data to propose the next 128–256 MiB heap step; estimates select experiments, not winners. Around the best complete setting, test adjacent worker counts (for example five or seven) and one lower/higher heap when that could resolve a trade-off. Effective concurrency cannot exceed this fixed eight-job workload. If eight wins, refine its heap and confirm it; configuring ten workers would not test more than eight simultaneous jobs and is excluded. A larger-workload concurrency study would need a separate comparison contract and is outside this plan. If eight degrades, do not keep increasing concurrency. Extend the local harness's heap choices and tests as needed, without changing formal qualification admission.

Use a predeclared optional performance censor at 1.20 times the best validated incumbent. Record the trigger separately from cleanup elapsed; censored timings are bounds, not completed durations. Stop a direction at a reproduced meaningful slowdown or failure; do not pursue lower memory solely for its own sake. Small differences require repeated matched evidence before declaring a winner. Prefer more workers only when batch timings are indistinguishable under observed run variation, reflecting the user's tie-break; a clearly faster four-worker batch beats a slower six-worker batch.

Keep the coarse grid plus local refinement bounded and purposeful: ordinarily no more than ten new-code exploratory full batches, plus a final matched confirmation pair. Do not silently truncate unresolved required work at that soft bound; report what remains and narrow the next experiment based on evidence. The deliverable is the best validated configuration within the tested neighborhood, not a claim of a global mathematical optimum.

## Phase 5: confirm and deliver

Repeat the selected new-code configuration and compare against an appropriate fresh reference with the same eight tasks. Isolate code gains using the same worker/heap setting on old and new executables; isolate configuration gains using the same new executable. Prior interrupted confirmation is not a reference result. Keep profiling timings out of performance claims.

Every selectable stage must have eight strict validations, reference economic hash and event/control/combined curve matches, independent 7200-trade/16-book replay, raw-byte preservation, and valid timing. Summarize CPU/GC/RSS/swap and worker-only versus validation durations separately. Repeated batches are still the same eight development datasets, not additional independent research samples.

Archive stages/packages with verified checksums to `/Users/eternal/.codex/artifacts/trading-pr13-performance/calculation-performance-20260914/`, preserving raw sources and original receipts. Produce compact manifested evidence, runnable selected local profiles, a dated report, and updated runbook/AGENTS guidance. Require independent final review of code, selected configuration, small evidence receipts, and packaging. Existing `/root/throughput_review` is available for review; give it bounded work and keep it off heavy reads during timing.

Commit the intended changes, push `HEAD:codex/corrected-worker-qualification`, rewrite PR #13 around the final change/results, and verify all checks on the exact pushed head. Preserve failed and superseded attempts with explanations. Do not merge. Final response: what code improved, measured code/configuration gains, selected actual workers/heap, correctness/coverage/CI evidence, and any remaining limitations.
