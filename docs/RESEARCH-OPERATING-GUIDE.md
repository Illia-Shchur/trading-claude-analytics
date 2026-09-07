# Governed strategy-research operating guide

This guide describes the additive fixed-baseline and staged diagnostic path as
it exists in this checkout. It does not authorize orders, activation, or a
promotion decision. Historical v1–v5 records remain immutable.

The authoritative Java index is strict outside the explicitly marked
noncanonical retention archive. The working `strategy-research/v5-records`
tree marks `evidence/` with the physical
`strategy-research-retention-archive/1` marker; raw arrays, build-identity
receipts and historical errata below it remain retained bytes, not index
records. The marker is validated before exclusion, transaction-control files
inside the archive remain strict, and invalid or unknown artifacts outside it
still fail. The current root indexes to 13 canonical records, including the
legacy `strategy-fixed-attempt-ledger/1` sidecar. Use a separate curated root
for a review index of compact evidence summaries.

## Current stage state

The fixed baseline has a real eight-asset physical computation and an
independent recomputation. Both are retained under
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/`.
They are `BLOCKED` for promotion because legacy family lineage is unresolved
and only three paired independent clusters were available. This is a completed
diagnostic computation with insufficient evidence, not a successful strategy
test and not a fresh family.

The bounded C inventory is frozen; its run-9 member outcomes were opened as
post-baseline DEVELOPMENT diagnostics and remain promotion-ineligible. The D
operating-characteristics design and generator are frozen in
`operating-characteristics-plan-v004.json`, including its conditional 32-cluster
geometry, 50 treated/50 control rows, exact 21-day control lag, setup shocks,
effect units, four cells, 50 replications per cell, Wilson-bound decision rule,
preflight budget, and resource limits. Source/JAR freezes preserve the original
v004d prefix, an explicit corrected v004d transition, and the final additive
v004e prefix; the v004e result is `INSUFFICIENT_EVIDENCE` with unavailable
Wilson rates. Its bounded numerical equivalence audit matches generator
inputs, setup/control selections, metrics, dispositions, decisions, portfolio
totals, and representative trade economics to the retained corrected4 prefix;
only lifecycle/mark timing fields differ under the close-derived availability
amendment. The full run completed from a separate copied immutable JAR. Its raw
result is `operating-characteristics-full-v004-dec4470d.json` with content SHA
`36ecb2be8690c9bb1bd5f0cf14817df98aeaf7c5a86e53690f0b40e98a4b0a71` and byte
SHA `1156b5e34e2dca2fec545f5059b61ceac2e066a7c824959d0fc603f08794a8cb`.
All 200 planned repetitions completed. The display-only compact projection
`operating-characteristics-full-v004-dec4470d-summary.json` retains every
replication's seed, metrics, counts, decisions and semantic hashes; its
content SHA is `7df566f3f677f00e47d2535531469de2634bfcf97427a58edb0719e84fe12b3e`.
The terminal observation is retained in
`operating-characteristics-full-v004-dec4470d-exit-receipt.json`; the monitor
recorded process termination but did not capture a child exit integer, so the
receipt intentionally makes no exit-code claim. No D calibration or promotion claim is retained. The adaptive confirmation path
is unchanged and remains unavailable without its existing physical custody and
threshold requirements.

The D boundary is checked before outcomes with:

```sh
./bin/analytics strategy-research-v5 operating-characteristics-preflight \
  --plan strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json \
  > /tmp/operating-characteristics-preflight-v004.json
```

The supported synthetic run requires the frozen source receipt and the three
typed definitions. It uses the shared fixed evaluator and writes a new output
path on every replay:

```sh
java -jar strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/executors/operating-characteristics-v004-full-current.jar \
  strategy-research-v5 operating-characteristics-run --full \
  --plan strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json \
  --source-freeze strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-source-freeze-v004b.json \
  --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
  --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
  --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
  --out /tmp/operating-characteristics-full-new.json
```

That command replays the retained local executor only while the ignored JAR
whose SHA is recorded in the source-freeze receipt is available. A fresh clone
can reproduce the amended generator from source by packaging a new JAR and
creating a new source-freeze receipt; a SHA alone cannot retrieve the historical
binary or claim byte-identical replay. The retained generator source file is
only a generator checkpoint, not a complete runtime source snapshot, so it
cannot rebuild the historical executor by itself.

The prefix is fixed at two replications and ten event/control series for
performance measurement only. It cannot produce FPR/power estimates. The
full run must retain every planned replication and use Wilson bounds only when
all 50 replications in a cell are complete and adequate; missing, invalid or
resource-aborted rows remain visible and block a measured claim.
The v004 resource guard checks the wall deadline on generation boundaries,
samples process RSS at most once per second (using Linux `VmRSS` when present),
tracks the peak sample, and fails closed when RSS is unavailable or exceeds the
frozen limit.

## Replaying the fixed diagnostic

Run from the repository root. The launcher checks build inputs and executes a
fresh packaged JAR when needed. A replay must use a new output path; an old
result is reusable only when the current packaged executor identity and every
bound input match it.

```sh
./bin/analytics build-identity
./bin/analytics strategy-research-v5 fixed-baseline-produce-signal-bars \
  --manifest strategy-research/v5-records/data-raw-replay/parquet-31f85e351861f05edc66794ec57369c0a7fafe67e236676aab5c6cc7bec4cfc6.json \
  --root strategy-research/v5-data/backfill-20260825-v7-final-local/parquet \
  --out /tmp/fixed-all-physical-replay/signal_bars.json
```

The producer reopens and verifies the frozen Parquet partitions. It is the
only accepted producer for the signal role. The durable bundle recipe below
copies the compact typed receipts, derives the signal role, hydrates all 130
event windows plus the three selected control windows (133 total), and
assembles the immutable input. It creates no labels, fills, filters, or
synthetic bars:

```sh
python3 tools/prepare-fixed-baseline-physical-bundle.py \
  --inventory strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/inventory-v004.json \
  --manifest strategy-research/v5-records/data-raw-replay/parquet-31f85e351861f05edc66794ec57369c0a7fafe67e236676aab5c6cc7bec4cfc6.json \
  --parquet-root strategy-research/v5-data/backfill-20260825-v7-final-local/parquet \
  --template strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/physical-input-closure-v004.json \
  --role-receipts strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/role-receipts-v004 \
  --producer-receipt strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/producer-receipt.json \
  --non-trading-policy strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/role-receipts-v004/non-trading-intervals.json \
  --out-root /tmp/fixed-all-physical-replay
./bin/analytics strategy-research-v5 fixed-baseline \
  --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
  --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
  --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
  --physical-input /tmp/fixed-all-physical-replay/physical-input-replay.json \
  --exposure-head strategy-research/v5-records/families/2afbe00fbdbb720a5c2fd1968c447dd1a0478d7670f3b9422b44f86169e3e38e/exposure-head.json \
  --out /tmp/fixed-all-physical-replay/result-20260906-new.json
```

The assembly script recomputes every role's byte and canonical content hash,
and refuses to overwrite a different output. It does not create missing bars,
labels, fills, filters, or PIT claims. Bulk 1m data stays outside Git. Public
transport is labelled `USER_BOUND_RETROSPECTIVE`; it is not prospective
exchange custody or observed-fill evidence. A fresh output root is a new
physical-input identity: it can reproduce the economic source roles, but it
cannot silently resume an exposure bound to the historical `/tmp` root. Use a
new accounted attempt for a relocated bundle and retain the old-root bytes for
exact historical replay.

The completed fresh preparation is retained as
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/fresh-physical-reconstruction-e11.json`:
all 133 windows succeeded, 1,914,720 rows were hydrated, and the assembled
physical-input content hash is
`2407e9957ee38ca789483ee49b8120941956c939fedd75af129399b44f01a883`.
Evaluation of that relocated identity was deliberately deferred; it is a
reconstruction receipt, not a second exposure claim.

New prospective outcome evidence can add a typed numeric reconciliation after
the existing custody/lifecycle artifacts have been validated:

```sh
./bin/analytics strategy-research-v5 prospective-outcome-reconcile \
  --input <strategy-prospective-outcome-reconciliation-input.json>
```

The input binds the outcome-resolution, label and execution source hashes and
contains paper lifecycle rows with entry/exit/resolution/maturity timestamps,
quantity, prices, frozen fee/slippage rates and capacity debit. The command
recomputes paper gross, costs and net arithmetic. Optional observed fills are
reported with their expected-versus-observed delta; missing observed fills are
`UNAVAILABLE`, never zero or an observed result. Arithmetic or maturity errors
produce `paper_status=INVALID`. Legacy opaque resolution artifacts remain
readable but do not acquire numeric or observed-fill claims retroactively.
The governed completed-cycle route additionally requires a separately hashed
input file plus typed label and execution source artifacts; it reopens those
roles, recomputes the result and rejects a rehashed result whose numbers differ
from the typed inputs. Observed fills must declare either
`REFERENCE_PRICES_PLUS_EXECUTION_DEBITS` or `ACTUAL_FILL_PRICES`; the latter
does not accept a second modeled slippage or capacity debit.

The fixed baseline uses the additive
`strategy-research/experiments/fk-deleveraging-baseline-v002/lifecycle-timing-v001.json`
erratum. Lifecycle `time` remains the bar-open identifier for replay, while
close-derived barrier and timeout fills become available at the bar's
`close_time`/`availability_time`; `GAP_OPEN` is available at the bar open.
Fixed-book admission, cash posting and mark horizons use that availability
field, so a close-derived stop cannot release capital before its minute closes.

The historical post-amendment packaged checkpoint used for bounded replay review is
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/executors/analytics-current-20260906-e09-timing.jar`
with SHA-256
`06ae9c89a90ddf6e899909875c69d2c4258d5423d1b6a6cd61a4d18e573a4bed`.
Its direct `java -jar ... build-identity` receipt is retained beside the JAR;
the JAR is locally ignored. The final current-source checkpoint, including the
display-only evidence-summary contract and predecessor-schema guard, is
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/executors/analytics-current-20260906-e12-final.jar`
with SHA-256
`423a66c54dc13f07ab91b27565368217f8fda46d9fa402e0e70b2bf6acae50e1` and
build-identity receipt SHA
`39821af49a41b1dfce821dbb74d52aba905c41aeac01e3be733ea7fc88301989`.
The current index-boundary checkpoint is
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/executors/analytics-current-20260906-e14-retention-index.jar`
with SHA-256
`4567c7d493f07cdec806f046c40007e02bbac7358c41cd0eb5e1403183395a6d` and
source fingerprint
`fd1599dc5b64828c52203f796deb6f884d1f32ec602a97454a44c19bb91ae507`.
It indexes the marked current `v5-records` root to 13 canonical records with
index content hash
`6a557327f21e21bb8f9aa0de48a9d9becb198289d3438ea58edf1349aed65686`;
the E09/E11/E12/E13 historical executors remain immutable checkpoints.
Historical result files remain bound to their recorded executor and must use a
new output path for any replay.

The retained v004 setup/control inventory is
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/inventory-v004.json`.
It has 130 rows (126 admitted and 4 chronological skips), 130 outcome-blind
selection rows, and 3 selected controls. It deliberately contains no outcomes
or P&L. Compact role receipts are retained beside it under
`role-receipts-v004/`; the 1m arrays and signal array remain ignored bulk
inputs, bound by their hashes in the physical-input receipt.

The e11 relocated input was also evaluated as a new accounted identity with a
new output and attempt. Its retained result is
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/fixed-baseline-e11-result.json`
(byte SHA
`334f3ffe07de95694735600bb66ff5c95fb988e825d718d6a33b9311327c9946`, content
SHA `35f69ab55486b4669bb68e309a2b749c763bad3f5678e4181fe5fd4c9b1140ba`). It
is `BLOCKED`/`DEVELOPMENT` with `INVALID_EVIDENCE` plus
`INSUFFICIENT_EVIDENCE`, 126 admitted events, 3 controls, 129 trades and 28
independent clusters. The fresh transport roles have four provenance-level
differences from the historical role bytes (public source labels in all four,
plus one close and two volume values); an independent comparison found no
change in trade arithmetic or final economic metrics. The role drift changes
provenance hashes, so the e11 result remains a distinct diagnostic attempt and
does not silently resume the historical exposure tuple.

Large historical result arrays remain locally retained and ignored by default.
Compact `strategy-research-evidence-summary/1` projections keep the source path,
raw byte/content hashes, binding identities, disposition, and account-currency
totals as display-only non-promoting evidence. The e11 projection is
`fixed-baseline-e11-result-summary.json` (content SHA
`d85082bbfd02362d8fcd466e1c7b5e2eaf0c94a575fbadb5b945b9d47112d7bb`); run-9,
run-6, v20 and v21 projections are retained beside their raw result paths.
They cannot substitute for a fixed-baseline or refinement predecessor. The
compact content hashes are: run-9 R1
`df7600c1726c66437381486d7c918d01110faa261b781c5867d4dd9872416cbc`, run-6
R1 `3131dcef92dfd1d1539d07245e362cbd5333b2b2dbc9d28a12a16a00935964c0`, v20
`1b7f533428277da123ceeabd2db042f123e395a2171ad783368c280ee80dfd5f`, and
v21 `231e1b7faaf44d3fc1e0b9eda057941cd88267af6f55b8b1fd7e5e63e1968b8a`.
This keeps a fresh checkout's required evidence small without deleting or
rewriting the historical raw bytes.

## Freezing the bounded refinement

The supported freeze command accepts only the three predeclared members in
`refinement-input-v001.json`. It invokes no optimizer and opens no outcomes;
the later run-9 member outcomes are a separately retained post-baseline
DEVELOPMENT diagnostic through the shared evaluator:

```sh
./bin/analytics strategy-research-v5 freeze-refinement \
  --input strategy-research/experiments/fk-deleveraging-baseline-v002/refinement-input-v001.json
```

The output is a diagnostic, append-only receipt. It cannot reset cumulative K,
advance the family, or satisfy the blocked paired-evidence requirement.

To execute the frozen members through the shared physical evaluator, provide
the exact baseline predecessor and its frozen starting exposure head. Each
member has an immutable result and the aggregate remains diagnostic:

```sh
./bin/analytics strategy-research-v5 fixed-baseline-refinement \
  --refinement strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/refinement-plan-v001.json \
  --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
  --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
  --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
  --physical-input <verified-physical-input.json> \
  --exposure-head <canonical-family-head.json> \
  --starting-exposure-head strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/refinement-start-exposure-head-v001.json \
  --predecessor strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/full-result-20260906-v20-final.json \
  --out-dir <new-output-directory> --out <new-output-directory>/aggregate.json
```

The starting head is retained at attempt K=32. Current heads may contain
append-only member attempts, but the runner rejects reset or divergent prefix
heads, changed frozen inputs, and mismatched executor identities. A repeated
behavior/dataset pair resumes idempotently through its bound attempt ledger.

## Evidence and deployment boundary

The full run README records the exact historical commands, executor SHA,
economic hashes, source manifest, role receipt, measured approximate runtime,
and independent recomputation. Existing deployment configuration is audited in
`docs/RESEARCH-DEPLOYMENT-AUDIT-20260905.md`; its green scheduled workflow is
dormant configuration with no observed active forward cycle. No prospective
operation or activation is claimed.
