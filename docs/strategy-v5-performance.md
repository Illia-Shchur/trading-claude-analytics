# Strategy Research v5 performance bounds

## Opt-in production data-plane benchmark

The benchmark entry point is `tools/strategy-research-v5-performance-benchmark.mjs`.
It accepts only an explicitly supplied frozen five-year plan, acquisition
manifest, authoritative Parquet manifest/root, and optionally a coverage
manifest. Every supplied JSON document is reopened, checked against its
registered schema and canonical `content_sha256`, and checked for plan,
acquisition, source-byte, dataset-root, and role linkage. Each declared
Parquet partition is then hashed through a bounded stream and reopened with
the pinned one-thread DuckDB runtime. Reopening validates physical identity and
role columns, event/PIT bounds, completed-bar close boundaries, OHLC/funding
values, cadence, duplicates, and complete count/bounds—not just a footer or a
self-reported hash. Funding is additionally required to be a non-empty,
pagination/boundary-proven event sequence with discovered cadence and an exact
per-row settlement-mark source/hash/event/availability identity; missing or
shifted provenance blocks readiness pending reconversion. Promoted coverage can describe a generic scan, but a
canonical v5-ready result requires a file-backed
`strategy-v5-authoritative-coverage/1` manifest with `OBSERVED_COMPLETE` and
exact acquisition/Parquet/dataset hashes.

Run a non-production sample while developing:

```sh
node tools/strategy-research-v5-performance-benchmark.mjs \
  --plan=strategy-research/v5-records/data-backfill/plan-<hash>.json \
  --acquisition-manifest=strategy-research/v5-records/data-backfill/staging_manifest-<hash>.json \
  --acquisition-root=strategy-research/v5-data/<run>/network-resume/staging \
  --parquet-manifest=strategy-research/v5-records/data-backfill/parquet_manifest-<hash>.json \
  --parquet-root=strategy-research/v5-data/<run>/network-resume/parquet \
  --coverage=strategy-research/v5-records/data-backfill/coverage-<hash>.json \
  --sample-partitions=2
```

Only `--full` proves every partition declared by the Parquet manifest:

```sh
node tools/strategy-research-v5-performance-benchmark.mjs \
  --plan=<frozen-plan.json> --acquisition-manifest=<acquisition.json> \
  --acquisition-root=<staging-root> --parquet-manifest=<parquet.json> \
  --parquet-root=<parquet-root> --coverage=<coverage.json> --full
```

Sampled output is always `production_data: false` and `NON_PRODUCTION_SAMPLED_SCAN`.
Full output separates deterministic `semantic` results (including partition,
asset, required-series, byte, and row counts plus `semantic_sha256`) from
variable `runtime` observations (wall time, total throughput, separate
acquisition-source and Parquet bytes/rows/partitions/chunks, RSS, and stream
chunk bound; the bounded-memory assertion applies to the hash-stream buffer while
DuckDB RSS is observed, not falsely claimed as globally capped). A scan can set
`readiness.data_plane.ready` only after a complete full scan of the canonical
exact UTC-calendar five-year window through the latest completed 4-hour
boundary, and the eight-asset v5 universe (BTC, ETH, SOL, BNB, XRP, ADA, LINK,
AAVE) with four exact required series per asset: spot 4-hour signal bars,
USD-M perpetual 4-hour signal bars, USD-M perpetual mark 4-hour mark bars, and
USD-M perpetual funding events (32 required series);
a smaller fixture may report `generic_data_plane_complete` while remaining
blocked by `BLOCKED_V5_CANONICAL_PLAN_CONTRACT`. The canonical claim additionally
requires every plan-declared available dated-future capture to be physically
acquired, reconciled to Parquet, and covered; a missing optional metric is only
ignored when the coverage contract explicitly proves it unavailable. Omission of
the authoritative coverage manifest leaves only the generic result. Every JSONL
source is streamed with bounded line/partition limits; roots and every component
are lstat/realpath checked, and symlink or multi-link files are rejected. A scan never changes
`readiness.statistical`, `readiness.physical_null`, or
`readiness.global`, which remain blocked until an authoritative WFO and
physical-null benchmark actually runs.

The performance helpers in `tools/strategy-research-v5-performance.mjs` are
opt-in building blocks for the authoritative evaluator. They do not change the
GA population, generation count, three-seed requirement, cumulative `K`, null
budget, selection objective, or OOS boundary.

## Scope-vector cache

`makeScopeVectorCacheV5` stores only one-episode signal and outcome values.
Every cache binding includes the source artifact, evaluator spec, predictor
registry, exact feature/label/execution/mark/metadata artifact hashes,
signal/outcome code hashes, and (when present) the worker code hash. The caller
chromosome digest is checked against the canonical chromosome bytes on every
request. Disk entries are content-addressed and published with an exclusive
hard-link CAS operation; an existing key is accepted only after an exact
result comparison, so concurrent writers cannot overwrite one another.
Every disk-loaded signal or ordinary scope-bound outcome is recomputed by its
current callback and compared before promotion into in-process memory; a
self-rehashed disk record therefore cannot create or remove an intent/trade.
In-memory entries are bounded by count and bytes, and both each serialized
result and each complete serialized entry have byte ceilings. A cache miss is
evaluated only for an episode in the caller's declared scope. The cache never
stores metrics, selected candidates, or null results across a changed data
binding.

Outcome reuse across scopes is disabled by default. The old self-asserted
`scopeIndependentOutcomes` flag, a serialized proof receipt, and a caller
`verifyPitBoundary: () => true` callback are all insufficient and rejected for
scope-independent reuse. Without the authoritative capability, outcome keys
include the complete episode-scope digest, phase, fold, and cutoffs, so an
overlapping scope cannot borrow an outcome from another scope.

Reuse requires the loader-owned, frozen
`strategy-v5-internal-scope-independent-outcome-capability/1` installed on the
non-serializable trust-marked physical-v2 evaluator before it is sealed. The
capability binds the exact feature/label/execution/mark/metadata bytes and the
proof receipt, and supplies in-process one-episode PIT and outcome verifiers.
Each `evaluate` call opens an opaque loader-owned trust epoch: the exact role
and raw metadata bytes are reopened and hashed at the epoch start, then checked
once again at its end. Per-episode PIT checks, misses, and memory hits use that
private epoch instead of hashing the lake once per episode; a mutation during
the scope still fails at the end boundary. A trusted miss is computed once by
the loader-owned outcome function. A trusted disk hit is recomputed once and
byte-compared before promotion into trusted memory, while an already verified
in-process hit does not recompute. A caller cannot fabricate the capability by
self-hashing its descriptor. The trust module keeps it in a private
non-serializable `WeakMap` keyed by the registered evaluator, rather than on a
writable/serialized evaluator property. Transformed null roles therefore use a
different exact `dataBindings` tuple and cannot reuse ordinary outcomes. The
integration hand-off is the loader's `authoritativeEvaluator` plus its exact
loader proof; the cache resolves the matching WeakMap capability internally.

The parity test exercises overlapping inner/refit scopes and confirms that the
cached `candidate_returns` and intent vector match direct evaluation exactly.

## Lazy execution references

`makeLazyExecutionReferenceV5` and `materializeLazyExecutionReferenceV5` bind to
the v2 opportunity hydration artifact and carry only canonical partition
references plus a separate sorted partition-content root digest in a worker
payload. The reference verifies its execution and pre-entry refs against the
bound hydration, includes both classes in the root digest, and validates the
declared partition bytes/rows before reading. Materialization is delegated to
`readHydratedRangeV5`, which verifies partition hashes and applies row, output,
resident-memory, and partition-byte ceilings. A `child_bars` array exists only
for the one range being evaluated; it is not copied into every worker's role
payload. The v1 `featureWindows` path is intentionally unsupported by this
helper until the authoritative loader is wired to the v2 conservative domain
superset.

`makeBoundedPartitionReadCacheV5` is an immutable, content-addressed per-worker
LRU. It reads and verifies a referenced partition once per resident lifetime,
retains the verified JSONL bytes under a resident-byte ceiling, and rechecks
the source file signature on cache reuse plus the SHA/byte/row contract before
the hydration reader materializes a range. It
filters the caller's partition catalog to the refs actually requested, so a
worker does not hydrate an entire one-million-window lake just because it was
given a catalog handle. It never caches transformed labels, executions, or
outcomes; null replay therefore retains its mutation/tamper checks and exact
selection work. A shared read-only implementation may be supplied by the
integrator, but `PER_WORKER` is the conservative memory model.

## Production-shaped accounting

With the current frozen defaults (8 assets, 8 outer folds, 2 inner folds per
asset, population 48, 20 generations, 3 seeds, four physical-null families,
128 physical-null iterations):

```
GA runs                         8 × 8 × (2 + 1)       =       192
attempts per GA run             48 × 20 × 3           =     2,880
base GA attempts                192 × 2,880          =   552,960
physical-null GA attempts      4 × 128 × 552,960     = 283,115,520
confirmation attempts           192 × 4 × 128 × 100   =   9,830,400
PBO evaluations                 8 × 8 × 4 × 128 × 8 × 8 = 2,097,152
vector materializations         8 × 4 × 128 × 48      =     196,608
outer-selected evaluations      8 × 8 × 4 × 128 × 1   =      32,768
full physical-null scale        sum above             = 295,272,448
```

The ~295.3M figure is the full physical-null attempt scale: four frozen null
families, confirmations, PBO comparisons, vector materialization, and the
outer-selected replay. The 552,960 figure remains the ordinary GA base. These
are attempts/evaluations, not a reduced cumulative `K`, and the estimator does
not lower the fixed 128 budget, skip confirmations/PBO, read OOS during
selection, or reset cumulative `K`.

Physical source work is accounted for separately because each shuffled method
currently materializes every lazy source execution path. If a source pass
contains `W` hydrated windows, `P` referenced partitions per window, and `B`
bytes across the full source pass, the no-reuse bounds are:

```
source path materializations       W × 4 × 128
physical partition reads           W × P × 4 × 128
physical partition bytes           B × 4 × 128
```

The estimator reports those counts, plus a bounded-cache cold-read amount of
`B × workers` for `PER_WORKER` (or `B` for shared read-only storage). The
reported warm-read amount is an upper bound for partitions evicted by the LRU;
it is not silently presented as a guaranteed zero. `B` is explicitly the full
source pass across all windows, so a production run must supply the actual v2
partition inventory bytes rather than the old v1 feature-window estimate.

The checked-in benchmark uses the same 8×8×(2+1) scope geometry with a small
sample (8 chromosomes × 96 episodes per asset). On the reference run:

```
                         direct callbacks      cached callbacks       hits
signal / outcome             98,304 / 98,304        6,144 / 98,304       92,160 / 0
signal reduction                                                        93.75%
total callback reduction                                                46.875%
```

The benchmark also executes the v2 physical fixture path (partition creation,
hydration, lazy reference verification, pre-entry-aware materialization, and
the bounded partition LRU). The fixture run is deliberately labelled
`production_data: false`: it uses eight short synthetic partition sets rather
than the authoritative five-year, eight-asset lake. It intentionally uses
scope-bound outcomes and does not claim trusted cross-fold reuse. Its readiness
flag remains `BLOCKED_REQUIRES_AUTHORITATIVE_V2_PRODUCTION_BENCHMARK`; no
production runtime or memory claim is made until that benchmark runs against
the corrected v2 artifact, conventional artifact content hash, separate
partition-root digest, exact role bytes, and actual partition byte inventory.

The synthetic benchmark emits `cache_wall_clock` with direct/cached wall times,
delta, cached/direct ratio, speedup factor, and an explicit faster/slower
result. It is intentionally not a production latency or speedup claim: the
fixture callback is trivial while the cache performs binding and clone work, so
the cached path may regress wall time even while reducing callback work. In the
real evaluator the saved path includes execution bars, fees, funding, and
lifecycle checks; only a representative heavy-evaluator run can support a
speedup claim.

## Resident worker memory

The estimator models the two materially different deployment cases. With `R`
resident partition bytes and `N` workers, a private cache reserves `N × R`,
while a verified shared read-only mapping reserves `R` physical bytes (plus
per-worker references and runtime overhead). The conservative default is
`PER_WORKER`; shared memory is reported only when the launcher can prove that
workers map the same immutable artifact. The full nested-role payload is still
reported as `role_payload_bytes × workers`, so the lazy-reference figure does
not hide the remaining partition residency.

## Remaining bounds and integration limits

- The cache does not deduplicate across changed null-role data, any changed
  feature/label/execution/mark/metadata binding, or changed evaluator/code
  hashes.
- A chromosome that is never revisited across scopes receives no cache hit;
  this is an optimization, not a change to the search budget.
- Disk cache growth is bounded by `maxDiskBytes`; once full, evaluation remains
  correct but new entries stay in memory only.
- A loader-owned PIT and outcome verifier is mandatory for every capability-
  enabled request, including disk and memory hits; a serialized receipt or
  caller callback never proves PIT safety.
- Disk resume is not a trust shortcut: signal and ordinary outcome records are
  callback-recomputed once before memory promotion, and trusted physical
  outcomes are loader-recomputed once against the exact role epoch.
- Lazy execution still materializes one requested lifecycle range. It does not
  make a single lifecycle cheaper than its required 1-minute bars.
- The bounded partition LRU does not cache transformed null outcomes. It avoids
  source-file rereads while resident, but an evicted partition is reread and
  revalidated; the estimator charges the full no-reuse upper bound.
- Worker integration must pass references plus a verified shared partition
  root and actual partition byte hashes; it must not pass inline bodies in
  authoritative mode. The corrected conventional artifact content hash must
  remain distinct from the partition-root digest.
- The authoritative v1 opportunity loader is not assumed safe for adaptive GA
  chromosomes. Integration must first bind the v2 conservative full mutable
  domain and its partition hydration to the evaluator; otherwise no runtime
  claim is made for the old path.
- Focused tests remove their own `/tmp/strategy-v5-performance-cache-*`
  directories; repository `tmp/`/raw data are not touched.
