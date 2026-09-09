# Research capability and evidence matrix

| Capability | Code path | Local tests | Physical exercise | Evidence / present boundary |
|---|---|---:|---:|---|
| Build freshness and executable identity | `bin/analytics`, `BuildIdentity`, build-input helper | Passed A reviewer and launcher regressions | Packaged outer-JAR SHA checked from another cwd | A accepted; identity bound into new evidence |
| Fixed baseline setup and control selection | `StrategyFixedBaselineV5` | Focused baseline/PIT/control tests pass | 87,656 verified 4h rows; 130 events; 3 controls; fresh e11 assembly hydrated 133/133 windows and 1,914,720 rows; e11 evaluated as attempt K=37 | Diagnostic only; control and paired evidence insufficient; e11 source-role drift is provenance-bound and does not change independently reconciled economics |
| Production lifecycle and account portfolio | `TradeLifecycleV5`, fixed portfolio reconciler | Lifecycle closure, timing, cash, capacity, drawdown tests pass | 129 trades and Decimal arithmetic reconciliation | Paper OHLC lifecycle; no observed exchange fills; close-derived fills use explicit availability timing |
| Source custody and producer rederivation | `readBoundProducerReceipt`, Parquet producer | Rehashed-role negative test passes | Canonical manifest and all eight partitions reopened | Caller-rehashed signal role rejected |
| Legacy exposure lineage | `auditExposureLineage`, append-only exposure | Alias and existing-head boundary tests pass | 28 recoverable legacy matches; historical B result records canonical K=1/attempt K=32; current authoritative HEAD is K=3/attempt K=37 with fixed-pair custody | `UNRESOLVED_LEGACY_HISTORY`; promotion blocked |
| Closure-aware gap handling | typed v1 policy plus v2 erratum generator | Four production-token closure tests pass | Binance 2021 closure receipt retained; no synthetic bars | Amendment applies only to covered held spot positions |
| Fixed diagnostic stage | `fixed-baseline` CLI | No optimizer path exercised | Full v004 result and v20/v21 economic hash equality | `BLOCKED`, `INVALID_EVIDENCE` + `INSUFFICIENT_EVIDENCE` |
| Frozen refinement inventory | `freeze-refinement` | Inventory validation and atomic pair-custody tests pass | Three members run through the shared evaluator; run-9 outcomes are post-baseline DEVELOPMENT diagnostics | All members remain `BLOCKED`; no promotion or family advancement |
| Operating-characteristics generator/evaluator | `StrategyOperatingCharacteristicsV4` + `StrategyFixedBaselineV5.evaluate` | Setup/geometry/Wilson/effect-unit/resource tests pass; RSS parser/throttle/deadline/limit tests pass | Full v004 run completed 200/200 repetitions from frozen JAR; 800 representative trade checks independently reconciled; 2/50, 0/50, 14/50 and 45/50 positive decisions across the four cells, with every target unmet | Synthetic conditional diagnostic only; PIT/observed fills/promotion remain false. Raw result and all-200 display-only projection are retained; any failed future replication is `COMPUTE_INCOMPLETE` |
| Numeric prospective outcome reconciliation | `StrategyProspectiveOutcomeReconciliationV1`, `prospective-outcome-reconcile` | Arithmetic, typed-source, rehash, maturity, unavailable-fill and convention negatives pass | Supported runner command path is covered by signal→matured-outcome and retry tests; no live forward cycle is claimed | Governed cycle reopens typed input/label/execution sources and recomputes; missing observed fills remain `UNAVAILABLE` |
| Adaptive confirmation / WFO | Existing v5 adaptive path | Existing regression suite retained | No new confirmation evidence | Thresholds and custody gates unchanged |
| Prospective operation | Existing protected workflow and runner | Existing local prospective tests retained | No active forward cycle observed | Deployment prerequisites remain external/dormant |

The matrix separates implementation capability from evidence. A local test or
retrospective computation cannot be relabelled as PIT, SHADOW, confirmation,
activation, or an observed exchange fill.

## Successor evaluation boundary — 2026-09-07

| Capability | Code path | Physical exercise | Evidence / present boundary |
|---|---|---|---|
| Prior raw/compact diagnosis | `operating-characteristics-diagnose`, `StrategyOperatingCharacteristicsSuccessorV1` | All 200 retained rows and four cells reconciled; numeric falsifiers and joint decisions recomputed | Complete diagnostic receipt; 2/50, 0/50, 14/50, 45/50 remain below the declared Wilson targets where applicable |
| Frozen successor precommit | `operating-characteristics-successor-plan-v004.json` | v002 froze its full design before its prefix; v003 preserved the post-prefix durable metadata checkpoint; v004 corrected one independently audited truncated artifact-byte hash before any full outcomes | Development and promotion-ineligible; +0.02/+0.04 are cumulative log-return shifts, not historical returns; v003 bytes remain preserved |
| Resumable successor ledger | `operating-characteristics-successor-run`, `successor-record-attempt` | v002 two-replication prefix retained `STARTED` reservations, complete evaluator rows, full raw outputs, and independent trade arithmetic audit | Prefix is `measured=false`; raw result/ledger/audit are durable and cannot satisfy full acceptance |
| Full successor feasibility | `operating-characteristics-successor-preflight` | 288 clusters, 450 event/control series and 75 reps estimated at 265,154 seconds and 59,742,388,224 bytes peak RSS; disk is reported but not estimated/enforced and the prefix is not exact geometry | `BLOCKED_RESOURCE_ESTIMATE` under 720-minute/8-GiB/20-GiB envelope; any future run needs complete disk and exact-geometry qualification |

These rows describe implementation and evidence custody separately. A retained
raw evaluator receipt does not become confirmation, and the resource-blocked
full geometry is not replaced by an underpowered prefix.


## Performance execution boundary — 2026-09-08

| Capability | Implementation / exercise | Present boundary |
|---|---|---|
| Bounded lifecycle inputs and hashing | Lazy receipt-backed role loading, current-cluster paths, streaming SHA; eager/bounded packaged equivalence | Frozen trade and portfolio arithmetic preserved |
| Memory/CPU-governed parallel runner | Independent JVM workers, compact immutable result references, STARTED-before-outcome ledger, bounded retry, resource abort and resume verification | Target 28 cores/32 GiB: maximum eight workers; local 10 CPUs/16 GiB: two |
| Increased execution declaration | 48-hour / 26-GiB aggregate RSS / 128-GiB managed disk ceiling | Additive execution plan v002; statistical plan v004 and 300 held-out seeds unchanged |
| Full-size development computation | 450 matched pairs / 288 units / 900 series; 546.218 seconds and 2.20 GiB sampled peak RSS | One development seed; no target qualification or operating-power claim |
| Parallel equivalence and speed | Final package one/two-worker prefix: 34.376/19.058 seconds (1.804×); exact normalized results and portable digests | One local timing pair; historical digest remains path dependent |
| Portfolio qualification | Rejects inactive/equal-exit marks and final-curve reconciliation defects | Preexisting frozen evaluator defect blocks confirmation; needs separately versioned correction |
