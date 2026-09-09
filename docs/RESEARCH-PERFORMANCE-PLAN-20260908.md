# Performance implementation plan — 2026-09-08

## Objective and scope

Make the existing conditional synthetic evaluator practical on the owner's
28-core, 32-GiB target through bounded memory, independent repetition workers,
and durable result references. Increase the declared execution budget without
changing statistical geometry, random paths, costs, matching, lifecycle,
portfolio arithmetic, acceptance rules, or confirmation seeds. This is
performance engineering, not new evidence of strategy profitability.

The owner authorizes implementation by Luna xhigh, independent correctness
review, the simplify skill's four independent lenses, and a local commit.
Planning and review use Astra medium. No push, deployment, trade or activation
is in scope. Prior frozen plans, executors and results remain byte-immutable.

Baseline is `30262ac0f910bbec6471dbbd3132a476da04866f`, with the preceding
session's uncommitted evidence tooling. The review/commit boundary is all work
from this conversation since that baseline, including its relevant untracked
source, schemas, documentation and evidence. The 20 earlier commits ahead of
origin/main are preserved. The 670 loose untracked `.class` files present at
start are unrelated generated material: preserve them and exclude from commit.
Other work subsequently appeared in CI, core scoring, market data, reporting,
the root build and migration documentation. Those changes are outside this
session. The explicit owned-file manifest under the ignored performance scratch
directory controls review and staging. Final packaging must use an isolated
snapshot of the baseline plus session-owned changes so its identity does not
silently include concurrent work.

## Findings from source inspection

1. `StrategyOperatingCharacteristicsV4.writeRole` writes each role to disk and
   retains its entire JSON tree. Full successor geometry therefore retains
   900 × 14,400 minute bars within a repetition. File-backed child-bar roles
   with verified on-demand opening are the first memory optimization.
2. Successor generation keeps common paths for every cluster despite iterating
   clusters in order. Only the current cluster's event/control common paths
   need remain resident; draw order and seeds must be identical.
3. The successor retains every full result, embeds it in its attempt ledger,
   and rereads, hashes and rewrites the growing ledger after each attempt.
   This causes repeated work proportional to the growing history. Immutable
   per-slot result files and compact ledger references remove this source of
   aggregate memory and quadratic result serialization.
4. The 300 scenario/repetition jobs are independent. Their predetermined seeds
   and isolated input directories make repetition-level multiprocessing the
   safest parallel seam. Event admission depends on lifecycle release times;
   control selection is without replacement. Keep those operations ordered
   inside each repetition rather than parallelizing arbitrary trades.
5. Production lifecycle trust physically reopens and validates receipts. Those
   checks are part of correctness, not optional benchmark overhead. Avoid
   changing shared lifecycle arithmetic or removing defensive copies without
   specific measurements and immutability proofs.
6. Current preflight scales one historical time/RSS observation linearly and
   does not enforce disk limits. Evaluation stages also outlive generator-only
   deadline checks. A coordinator must monitor worker processes across the
   entire job lifetime, including fixed evaluation and serialization.

These are concrete code findings. No profiler has yet assigned percentages of
runtime to generation, JSON, trust, lifecycle, statistics or checkpointing.

Initial bounded measurement, taken after the plan was written: the archived
v002 executor replayed its two already-exposed development prefix seeds under
JFR, `-Xmx2g`, and two JVM active processors. Runner wall time was 36.22 seconds
and reported peak RSS was 2.309 GB. Sampling found prominent string building,
JCS canonicalization, map allocation and deep-copy work, with 107 GC events.
This supports reducing retained graphs and repeated result serialization;
it does not yet isolate exact stage percentages or predict target throughput.
The recording and summary are under `.report-run/performance-20260908/` and
will be distilled into the final retained performance evidence.

## New target resource declaration

The owner-provided target is 28 cores and 32 GiB RAM. The connected host actually
reports 10 logical CPUs and 16 GiB RAM. Record both explicitly and never label a
local measurement as a 28-core benchmark.

| Resource | New target ceiling | Rationale |
| --- | --- | --- |
| Whole-run wall time | 48 hours | Increases the former 12-hour ceiling; includes worker execution and shutdown |
| Aggregate process RSS | 26 GiB | Leaves 6 GiB for the OS and other activity |
| Coordinator RSS reservation | 2 GiB | Compact manifests and one validation result at a time |
| Worker RSS ceiling | 3 GiB initially | Eight workers plus coordinator fit within 26 GiB |
| Worker Java heap | 2 GiB initially | Leaves native/GC overhead inside the RSS ceiling |
| Concurrent workers | At most 8 initially | Memory-gated; 28 cores are a ceiling, not a reason to allocate 28 heaps |
| CPU allocation | At most 24 target cores | Reserve capacity; bound worker GC/helper concurrency too |
| Whole-run scratch + retained result storage | 128 GiB | Includes child bars, checkpoints and final raw results; require actual free-space headroom |

Admission is the minimum of requested workers, CPU allowance, and available
aggregate memory after coordinator reservation. On this smaller host apply a
conservative local cap (initially two workers) and record effective limits.
No automatic worker-count increases during confirmation. A higher count is
allowed only after development measurements justify a separately frozen
execution profile. Avoid swap-dependent throughput.

Do not turn the historical 74-hour / 56-GiB estimate into a claim about the new
executor. The performance profile must identify measured versus extrapolated
figures and remain unqualified for full confirmation until the applicable
geometry and resource envelope have supporting measurements.

## Implementation phases

### A. Bounded repetition inputs

Retain eager role compatibility and add a narrow receipt-backed child-bar path
to the shared fixed evaluator. Release generated bar trees after their exact
bytes and receipts are persisted. Reopen each needed bar role using its bound
path/size/byte/content/row hashes; corrupted, missing or cross-asset data must
fail identically. Do not cache all reopened roles. Use the existing generator's
arithmetic and RNG ordering, with only bounded retention changing.

Offer an optimized repetition entry point for the new runner while preserving
the legacy serial command and all archived executors. Keep common paths for
only the current cluster. Avoid duplicated simulators or copying statistical
and lifecycle implementations into the runner.

The measured allocation profile also justifies three contained shared-helper
changes: stream file SHA-256 through a fixed buffer; hash arrays without a deep
copy and objects through a shallow top-level view excluding only their own
hash field; reuse an already-computed array content hash for an identical
array row-set check. Keep the JCS algorithm, every trust check and error
semantic, and input immutability. Test nested hash fields and tampering so this
does not become a cache that survives mutations or a validation shortcut.

### B. Durable independent results

Each planned slot is `(plan, mode, scenario, effect, repetition, seed)`.
The coordinator reserves STARTED before launching it. A worker produces one
immutable raw result artifact; the coordinator verifies its bindings before
publishing a terminal record. The ledger contains compact metadata and result
references, never full portfolio paths. No more than the bounded active jobs
and one result under verification need be resident in the coordinator.

Crash ordering: reservation → worker temporary output → atomic immutable
publication → verified terminal record. Retain abandoned reservations, failed
attempts and orphan outputs. Resume verifies receipts and reuses valid finished
slots; it never silently discards an attempted seed or counts a retry twice.
Final summaries are ordered by the frozen slot inventory, not completion time.
Missing/inadequate slots stay in the declared denominator and prevent a
measured confirmation claim. Old inline-ledger artifacts stay readable by the
old command; a new schema/command boundary may express external result custody.

### C. Bounded worker processes and resource supervision

Use a coordinator-controlled fixed process pool rather than shared mutable
worker threads. Each worker uses the same hash-verified packaged JAR, explicit
heap/CPU settings, an isolated directory and the frozen slot arguments. Workers
do not append the ledger. Bound the submission queue so 300 job payloads cannot
materialize simultaneously. Collect stdout/stderr in bounded or on-disk logs.

Monitor wall time, worker and aggregate RSS, and managed disk/free-space usage
at a bounded cadence. Kill and reap worker process trees on cancellation or
resource violation before cleaning scratch. Preserve an incomplete attempt
receipt for admitted work, and do not launch the remaining jobs after a global
budget breach. Keep successful raw artifacts durable. Unknown resource probes
must not be interpreted as zero usage.

### D. New execution precommit and reproducibility

Freeze an additive execution plan referencing the existing statistical plan
and the optimized packaged executor. Preserve all hypotheses, effects,
cluster/dependence geometry, 75 repetitions per cell, confidence bounds and
multiplicity/stopping rules. Explicitly supersede the former execution budget;
never edit the old plan in place or present a raised budget as measured proof.

Development equivalence may replay already-exposed prefix seeds. New scaling
measurements use a separately recorded disjoint development seed namespace.
Do not consume any of the 300 held-out confirmation seeds for profiling.
Record hardware, source/JAR identity, geometry, worker count, stage timings,
wall/CPU time where available, RSS, disk peak and artifact sizes. The final
execution freeze follows all source changes, including simplify, and precedes
any confirmation run. The target machine is not connected here, so target
qualification and full confirmation may remain explicit external prerequisites.

## Verification and performance acceptance

- Compare eager and bounded role modes on identical development paths. Require
  identical selected controls, event admission, trades, costs, portfolio values,
  clustering, bootstrap statistics and economic semantic hashes. Operational
  paths, timing and executor identities are expected to differ and are excluded
  explicitly rather than compared through a broad lossy projection.
- Compare one versus multiple workers on identical frozen development slots;
  verify deterministic ordering, complete result custody and no duplicate
  statistical counts. Add tests for corrupt/missing results, interrupted workers,
  competing coordinators, restart, bounded concurrency and resource failures.
- Run bounded prefix measurements on this host first. Then attempt one
  full-geometry DEVELOPMENT repetition only if smaller measurements and actual
  free resources support it within a bounded profiling allowance. A prefix is
  never power evidence. Do not extrapolate a target speedup from core count.
- Run affected lifecycle/trust/research/CLI tests, then the full pinned Maven
  reactor and the seven Python tests. Verify final packaged identity from
  outside the repository against an independent workspace fingerprint.
- Correctness review is separate from simplify. After implementation, four
  independent read-only lenses inspect reuse, simplification, efficiency and
  abstraction level over the session diff. Parent checks every finding; Luna
  applies only contained behavior-preserving cleanups. Recheck affected tests
  and run required full checks on final source before committing.

## Deliverables and completion

Deliver this plan, an optimized bounded runner and durable contracts, targeted
regressions, development performance/equivalence receipts, a frozen larger
execution-budget declaration, and a dated correctness/simplify review with
measured results and remaining limitations. Commit only session-owned source,
docs, contracts and intentional evidence after reviewing the staged diff.
Preserve generated loose files and prior work; do not push the commit.

## Measured development checkpoint and remaining scientific gate

The bounded full-size development repetition completed in 546.218 seconds of
external wall time, with sampled peak RSS 2,357,673,984 bytes and temporary disk
3,890,161,211 bytes. This replaces the old linear RAM extrapolation with a real
measurement for **one** full-size repetition. It does not establish eight-worker
throughput or qualify the unconnected 28-core machine. The 48-hour / 26-GiB /
128-GiB declaration remains a ceiling, not a forecast or instruction to consume
all resources.

Independent inspection discovered a preexisting exit-boundary marking defect in
the frozen portfolio curve. The optimized evaluator preserves its historical
semantics; the new qualification layer blocks invalid portfolio evidence. Fixing
that accounting rule belongs in a separately versioned evaluator with independent
regressions and new qualification. Faster execution alone cannot unlock the 300
held-out confirmation jobs. See the dated performance review and retained probe
receipts for the exact computational result and failed integrity checks.


The final packaged prefix comparison completed in 34.376 seconds with one worker
and 19.058 seconds with two (1.804× in one local pair). Exact normalized raw
results and portable economic digests matched. The accepted larger-budget plan is
`strategy-research/v5-records/evidence/performance-20260908/parallel-confirmation-plan-v002.json`;
it binds the final executor and remains blocked before all held-out outcomes.
See the review for complete tests, resume negatives and the preserved historical
hash/portfolio limitations.
