# Performance correctness and simplify review — 2026-09-08

Status: engineering implementation and review complete; confirmation remains blocked. Reviewer: Astra medium.
Implementers: Luna xhigh. Scope: session changes since `30262ac`, including the
preceding evidence delivery; frozen archives and unrelated generated files are
preserved. The authorized local commit includes the session-owned delivery only.

## Source and runtime evidence

The detailed design is in `RESEARCH-PERFORMANCE-PLAN-20260908.md`. The target
hardware declaration is 28 cores / 32 GiB; observed local hardware is 10 logical
CPUs / 16 GiB. The enlarged target envelope is 48 hours, 26 GiB aggregate RSS
and 128 GiB managed disk, with memory-gated workers and OS headroom.

An archived-executor JFR replay used already-exposed prefix seeds 910000000 and
910000001, two 10-event/10-control repetitions, Xmx2g and two JVM active
processors. Runner wall time was 36.220 seconds and reported peak RSS was
2,309,324,800 bytes. Among 2,768 execution samples, 1,259 stack traces contained
JCS canonicalization and 153 contained a deep-copy frame. Categories overlap;
these are sampled stack observations, not disjoint runtime percentages.
Profiling overhead and prefix geometry prevent interpreting this as target
throughput. No confirmation seed was opened.

## Correctness review checkpoints

Independent review agrees that detached child-bar roles, current-cluster common
path retention, streamed file SHA-256 and reuse of an identical array hash are
appropriate seams. Full receipt validation and JCS numerical serialization must
remain in place. The Java-null ownHash behavior was explicitly preserved.

The initial coordinator draft required corrections before acceptance: physical
input and executor bindings; real resource admission; per-worker and aggregate
RSS/disk enforcement; packaged worker invocation and artifact framing; immutable
artifact verification on resume; profile binding; bounded retries across
restarts; global resource-failure propagation; and exact clustered statistical
adequacy. The review also requires returning compact artifact references from
workers so coordinator threads do not each retain a complete raw result.

These are draft review checkpoints, not findings against an accepted final
implementation. Their resolution, actual packaged benchmark results, final
simplify decisions, regression counts and source identity will be recorded here.

The second independent review found that the first fixes were incomplete:
stored hardware facts could bypass live admission, FULL qualification and exact
inventory checks were insufficient, PREFIX could fall back to confirmation
seeds, internal resource errors could become COMPLETE, and retry exhaustion
could hide a valid orphan artifact. It also found process-exit sampling races,
missing CPU limits/reaping, and disk accounting that included unrelated files.
These were implementation acceptance items; the final regressions and real
packaged execution described below passed.

## Simplify review — stable evidence and bounded-input code

Three independent Astra reviewers applied the reuse, efficiency and
simplification lenses; the parent applied the altitude lens. The still-changing
parallel coordinator is deferred to a separate pass. Accepted contained
cleanups are streamed retained-result hashing with preserved error semantics,
one reused validated result hash, counters instead of unused identity storage,
removal of a discarded metric counter and write-only setup audit collection,
one setup-row conversion per input array, and direct attachment of a locally
owned evaluator result instead of copying it again. Parent inspection confirmed
that the removed collections have no output consumers and the directly attached
result has no subsequent mutations. No broader abstraction changes were
justified in this stable scope. Luna applied the accepted changes; final
verification is recorded below.

The deferred coordinator lenses accepted streaming JSON parsing, iterator-based
disk walks, and removal of obsolete reflection exception scaffolding. Replacing
the cancellation flag or consolidating Wilson/RSS helpers was deferred: their
benefit does not justify extra churn while the resource protocol is being
verified. Repeated custody validation remains intentional; caching it would
weaken the guarantee that resumed artifacts still match their receipts.

## Packaged development equivalence

The isolated baseline-plus-session package built successfully. An external
harness loaded its exact packaged dependencies and build marker, then replayed
development seed `202609080001`, NO_EDGE, ten events/ten controls. Both eager and
bounded rows were COMPLETE. The economic semantic hash matched exactly:
`249d68bb229749da2c6a44cbad71d22ba4f384201d530a3918c9df9de5778fa4`.
Selected controls, attempts/trade costs, portfolio, metrics and the complete raw
result after the evaluator's explicit custody-metadata exclusions matched. The
parent independently compared the structured economic fields and hashed the
retained rows. There were ten selections, ten attempts and twenty trades.

This checkpoint used executable SHA-256
`20502a243c75a09929352282bd7e4fb5a92c03ebfe8586b207589a4977d4dc84`
and source fingerprint
`1d8f6e08448e0c7ba99eb81a26394d440cfeac44636e7111598c1927882f25ab`.
Later coordinator fixes require a new final package. The equivalence harness
ran eager then bounded in one JVM: 12.202 and 11.818 seconds, with whole-process
maximum RSS 2,230,829,056 bytes. Warmup and retained heap confound comparisons;
these figures are not a controlled speedup or separate memory measurement.

One additional full-geometry development probe is predeclared separately:
seed `202609080002`, NO_EDGE, 450 events/450 controls, one worker, Xmx2g,
3-GiB RSS ceiling and 900-second whole-process limit. Its plan byte hash is
`3a0edb1da35b2935395f0b895cee7e3c208e99b2b4b7b56930fa857f701a8648`.
It cannot qualify the unconnected target or supply confirmation evidence.

## Historical and scientific boundaries

The earlier lineage and matching investigation remains development evidence.
Historical exposure custody is incomplete, the physical baseline retains 28
event clusters / three matched clusters, and no performance change supplies new
PIT history or validates the adaptive research pipeline. Increasing the compute
budget changes neither statistical acceptance criteria nor strategy status.

## Full-geometry development probe and independent accounting audit

One bounded development repetition completed all 450 events, 450 matched controls,
288 independent clusters and 900 lifecycle series. Evaluator time was 536.040 s;
external process wall time was 546.218 s (9.10 minutes). Sampled aggregate peak
RSS was 2,357,673,984 bytes (2.20 GiB); `/usr/bin/time` independently recorded
2,357,575,680 bytes. Sampled temporary disk peaked at 3,890,161,211 bytes across
928 files and was cleaned after completion. The retained raw row is 247,352,976
bytes of pretty JSON, compressed losslessly to 21,500,901 bytes. This is a single
NO_EDGE development repetition on the local host with two JVM active processors,
not a target-machine throughput measurement or evidence of operating power.
The executable is the v001 checkpoint identified above, not the final coordinator.

An independent Astra audit found a **preexisting portfolio accounting defect**.
`reconcile` and its mark/timing helpers are unchanged from `30262ac`. A mark at
exactly the exit instant can be sorted after EXIT, then reinsert the closed
position into marked holdings. Mixed `.000Z` and `Z` timestamp strings expose
this case. The cash/entry-cost sum check overlooks the residual marked holdings.
For one actual ETH trade, cash after exit is 9,937.090065771703 and the stale mark
is 940.5685429534325, producing curve equity 10,877.658608725136 despite the
reported ending equity 9,937.090065771701.

The full probe contains 165 revived event positions and 155 revived control
positions, with phantom ending holdings 155,104.409727 and 145,688.339425.
Those amounts explain the discrepancies between final curve equity and declared
ending equity. Equity curves, peaks and drawdowns fail integrity qualification.
Trade net P&L, realized cash, costs, ending-equity arithmetic, matching counts and
independent-unit counts are not changed by the stale map insertion. Independently
excluding closed marks produces different drawdowns; those diagnostic values are
falsification evidence, never replacement frozen results.

The frozen evaluator and archived results remain intact. A new external
qualification guard rejects marks outside an active trade's interval and final
curve/cash/declared-equity mismatches. Confirmation is blocked on this real defect,
in addition to target-machine qualification. A corrected evaluator requires a
separately versioned change and independent exit-boundary/timestamp regressions.

## Final coordinator review resolution

The final targeted independent review found no new actionable high-priority issue
in the corrected regions. Development qualification binds a separate frozen
plan, avoiding the profile/result hash cycle; it verifies full episode geometry,
raw-result custody, portfolio integrity, and a completed worker wave. Payloads
cannot override frozen episode counts. Supporting policy bytes are frozen before
STARTED and copied into private worker roots. COMPLETE artifacts require the
producer's three raw-result hashes and an own-hashed evaluator receipt; compact
metrics, portfolio, counts and disposition must match the raw evaluator result.
Legacy rows need no additional row own-hash. HotSpot OOM exit 3 is a run-wide
resource failure, and process cleanup covers post-launch failures.

A real v002 packaged smoke exposed the incorrect mandatory row own-hash check;
both worker artifacts were preserved as failed development attempts. The final
source corrects this compatibility error. No held-out seed was involved.

## Portable comparison and final simplify decision

The packaged one/two-worker comparison exposed another audit-only distinction:
the legacy economic digest includes absolute portfolio and lifecycle policy-file
paths. Isolated workers deliberately relocate those files, so legacy digest values
can differ despite identical metrics, controls, attempts, trades and portfolios.
The historical algorithm and frozen rows remain unchanged. The new parallel slot
contract adds `portable_economic_sha256`, using the existing economic exclusions
plus exactly the two top-level policy paths; policy content hashes remain bound.
Strict artifact verification recomputes this digest. Tests cover relocation
invariance, policy/trade sensitivity and input immutability; independent review
accepted the implementation. The small normalization is local to the new runner,
an intentional duplication that avoids changing the frozen evaluator contract.

The full reactor passed 1,036 tests with zero failures/errors and two skips.
Affected package reruns after final receipt and portable-digest edits passed;
combined current reports contain 1,037 tests with zero failures/errors and the
same two skips. One skip is the historical Node physical-null fixture; the other
is the packaged eager/bounded test under Maven's classes-only execution, exercised
instead through the separately packaged external harness. The seven Python
regressions also passed. These are isolated baseline-plus-session checks; they
do not certify concurrent unrelated working-tree changes.

## Final packaged benchmark, custody and acceptance

The final package SHA-256 is
`8c6e73508022f78b35bcbe3d59acb1e5e38707b48cf9a0c542ddf97133a4a549`.
Its compiled source fingerprint,
`b610e3d15fdde3a645bcdb7704963923de8eaf84c854702bb2070245c56c99f2`,
was independently recomputed in the isolated tree and matched an identity query
run outside the repository. The main working tree includes unrelated concurrent
work and is not claimed to have this fingerprint.

One timing pair on the same final frozen prefix plan replayed the two already
exposed seeds 910000000/910000001. One worker took 34.376 seconds with sampled
aggregate peak RSS 2,157,625,344 bytes; two workers took 19.058 seconds with
3,500,179,456 bytes. The observed ratio is 1.804×. Both jobs completed on attempt
one, with identical result order, portable digests, metrics, controls, attempts,
portfolio and the entire normalized raw result. The normalization is explicit in
`audit-final-benchmark.py`; legacy absolute-path-dependent digests are retained.
This is one small-geometry pair on the local host, not a target scaling forecast.

A real packaged restart reused both completed artifacts without adding attempts.
Copies with a corrupted artifact or a missing artifact failed before new work.
The larger execution declaration is frozen as
`parallel-confirmation-plan-v002.json`, own hash
`50810b02d34e7df09aea663907576736dac5ed15fb5495d9376a72feeb51d33f`.
Its preflight preserves all 300 slots and returns `BLOCKED_RESOURCE`, with
`outcomes_opened=false`. The raw portfolio-integrity defect separately blocks
qualification; raising the budget does not waive it.

The earlier v001 execution-plan checkpoint lacks the redundant
`executor_source_sha256` schema field and is retained as a non-accepted draft.
The accepted final plan supplies the compiled input fingerprint in both source
identity fields. Checkpoint smoke failures and path-dependent hash observations
are retained as development history, not silently promoted to accepted evidence.


The final packaged eager/bounded replay also passed after all source edits:
ten selected controls, ten attempts and twenty trades, with exact metrics,
portfolio and legacy economic digest equality within the same repository root.
The final research module SHA-256 is
`d1e6683a46bddf0e1fa7385a6232e244725be28468ed45b022b979c7863c88ba`.
Twelve accepted profile/plan/preflight/ledger/result/slot contracts and their
canonical own hashes validated. Full-probe gzip decompression reproduced the
original byte hashes. All 45 preexisting session strategy artifacts, canonical
exposure HEAD and the archived v002 executor remained unchanged.

No strategy, position, capital rule, canonical exposure count or report publication
was altered. The engineering delivery is ready for the requested local commit;
no push or held-out confirmation run is included.
