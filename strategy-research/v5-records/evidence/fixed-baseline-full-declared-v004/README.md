# Fixed baseline full declared evaluation (v004)

This directory retains the two independent packaged evaluations of the frozen FK baseline over all eight required assets. The computation completed the physical lifecycle pass but remains diagnostic and blocked from promotion: legacy family lineage is unresolved and the paired sample is below the frozen minimum.

- v20: `full-result-20260906-v20-final.json`
- v21 independent recomputation: `full-result-20260906-v21-recompute.json`
- both status: `BLOCKED`; scope: `FULL_DECLARED_SCOPE`; disposition: `['INVALID_EVIDENCE', 'INSUFFICIENT_EVIDENCE']`
- inventory: event_count=130, admitted=126, controls=3, unresolved=0, trades=129, independent_clusters=28
- economic semantic hash (both): `1e4d63e3cabaeaaa71a09d2106580218c2c3b998693d88927d7050ca7777bb08`
- provenance semantic hashes: v20 `a8341162ff32df6ba1e39ee45f29cec97c9c713de4070b28b8df586af1d29065`, v21 `a8341162ff32df6ba1e39ee45f29cec97c9c713de4070b28b8df586af1d29065`
- packaged executor SHA (both): `ff277e85470d3fde87a4723c17f31f2e1581cc98dd26c94a27e02ba211c64d7e`
- physical input SHA: `4f5ac7a69e4543aa34c66fa7dcf215a1b0a1eaf9ebe5d7178f6da312a7ddfa40`
- source Parquet manifest SHA: `31f85e351861f05edc66794ec57369c0a7fafe67e236676aab5c6cc7bec4cfc6`
- source signal role SHA: `3eeee5f714b73b00ce62969253566e5f04aa26a5a2cd00b17612344b34fd8500`
- legacy audit: `UNRESOLVED_LEGACY_HISTORY`, matches=28; this historical B result records canonical K=1/attempt K=32. The current authoritative family HEAD is append-only K=3/attempt K=36 and carries validated fixed-attempt pairs; it does not reset or reinterpret the historical lineage.
- metrics: paired=3, event_clusters=28, paired_clusters=3, paired_analysis_insufficient=True
- measured runtime: each retained pass took approximately four minutes wall time on the local OpenJDK/macOS host (peak RSS approximately 4.4 GB; the evaluator used multiple CPU cores). This is an observed run measurement, not an adaptive-cost extrapolation.
- deterministic setup/control inventory: `inventory-v004.json` (content SHA `b4a5c33682559e663bae245d8098f9c5b81c145512acd28b45c9443f2bbaa498`), 130 event rows including 126 admitted and 4 chronological skips, 130 outcome-blind selection rows and 3 selected controls. It contains no labels, trades, P&L or disposition.

The physical input references the verified 1m role cache under `/tmp/fixed-all-physical-v2`; the retained input, source manifest, producer receipt, closure policy, and role hashes are sufficient to audit the boundary without checking in the bulk cache. The public hydration receipt is retained as `public-hydration-receipt.json`; the original REST gap failure and public Binance monthly archive receipts remain described there and in the working-tree hydration records. No synthetic bars were created.

The v20 and v21 paths below are historical immutable outputs. The command was entered through `./bin/analytics`; the launcher resolved to the packaged `java -jar` executor whose SHA is recorded above. They must not be used as the destination of a later replay after a build change. A new replay uses a new output path and is accepted only after the current JAR identity and economic hash are compared.

The compact non-bulk role receipts used to assemble v004 are retained under
`role-receipts-v004/` (labels, execution rows, per-asset contract/model/
capacity receipts, the closure policy and empty placeholder roles). The large
signal and 1m child-bar arrays remain ignored bulk inputs; their exact byte and
canonical hashes are in `physical-input-closure-v004.json`. A fresh bundle is
assembled by `tools/prepare-fixed-baseline-physical-bundle.py`: it derives the
signal role from the pinned Parquet producer, hydrates all 130 inventory rows
through the public transport tool, copies compact receipts, and invokes the
Java canonical-hash assembler. There is no manual role-file placement step;
the output root must start empty.

Exact commands (repo root):

```text
./bin/analytics strategy-research-v5 fixed-baseline-produce-signal-bars --manifest strategy-research/v5-records/data-raw-replay/parquet-31f85e351861f05edc66794ec57369c0a7fafe67e236676aab5c6cc7bec4cfc6.json --root strategy-research/v5-data/backfill-20260825-v7-final-local/parquet --out /tmp/fixed-signal/signal-bars-latest-20260906.json
./bin/analytics strategy-research-v5 fixed-baseline --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json --physical-input /tmp/fixed-all-physical-v2/physical-input-closure-v004.json --exposure-head strategy-research/v5-records/families/2afbe00fbdbb720a5c2fd1968c447dd1a0478d7670f3b9422b44f86169e3e38e/exposure-head.json --out /tmp/fixed-all-physical-v2/full-result-20260906-v20-final.json
./bin/analytics strategy-research-v5 fixed-baseline --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json --physical-input /tmp/fixed-all-physical-v2/physical-input-closure-v004.json --exposure-head strategy-research/v5-records/families/2afbe00fbdbb720a5c2fd1968c447dd1a0478d7670f3b9422b44f86169e3e38e/exposure-head.json --out /tmp/fixed-all-physical-v2/full-result-20260906-v21-recompute.json
```

For a replay today, first rebuild the compact signal role and assemble the
hash-bound input from the role directory. The assembly script reopens every
JSON role, recomputes byte/canonical hashes, and refuses to overwrite a
different output; it does not fabricate missing bars or metadata. The bulk
1m cache remains an ignored local prerequisite.

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
  --out /tmp/fixed-all-physical-replay/result-20260906-new.jar-bound.json
```

The retained `inventory-v004.json` is the fixed evaluator's complete setup
inventory for the declared event windows. The orchestrator hydrates all 130
event windows and the three selected control windows, for 133 physical
windows. The hydration command is a public transport preparer and records
`USER_BOUND_RETROSPECTIVE` filter status; its typed policy verifies the four
known Binance spot closure archive checksums and rejects every unknown gap.
The original policy bytes are retained; `role-receipts-v004/non-trading-intervals-erratum-v001.json`
corrects the notice wording without changing the tested interval or fabricating
bars.
The orchestrator also requires the per-event labels, executions,
contract/model/capacity receipts and typed closure policy in the durable role
directory. The retained v004 bundle was assembled from those receipts under
`/tmp`; its replayable contract and producer are durable here, while the bulk
bars remain intentionally ignored.

Frozen refinement inventory (C)

`refinement-input-v001.json` freezes exactly three stricter shock-threshold subsets (-0.08, -0.10, -0.12) of the already hydrated baseline opportunity inventory with volume 2.0 and volatility 0.015 unchanged. `refinement-plan-v001.json` records `candidate_count=3`, `max_attempts=3`, `optimizer=NONE`, `evidence_phase=DIAGNOSTIC` and `promotion_eligible=false`. Its pre-run bytes had `outcomes_opened=false`; run 9 subsequently opened all three member outcomes as post-baseline DEVELOPMENT diagnostics. It is downstream of the blocked v20 result and cannot advance the family or reset exposure.

The retained run-9 member results are in `refinement-results-v004/`; the
earlier run-6 bytes reviewed during development remain unchanged in
`refinement-results-v004-run6/`. Run 9 completed all three members through the
same physical evaluator and produced a typed diagnostic aggregate with
`content_sha256=935a42cf46c5b754df295d9a2388505df91cf0fccbcce987d9a17280f0786bdf`.
All three members are `BLOCKED` with `INVALID_EVIDENCE` and
`INSUFFICIENT_EVIDENCE`; this measured diagnostic result cannot advance the
family. The run used executor SHA
`c88e11c940571880bb43781990b3657a412927c4f551ecdfd18b72072932f2d0` and took
434.69 seconds wall time. The fixed-attempt pair ledger is now migrated into
the authoritative exposure head by the current source; historical sidecar
bytes remain retained for audit only.

The D operating-characteristics design is frozen as additive
`operating-characteristics-plan-v004.json` (content SHA
`63e17392714365810db25d52f382f5b9578a2cead9ee06b0335e4360b41eb235`). v001
and v002 remain immutable. v004 defines a conditional fixed-stage benchmark:
32 calendar clusters, 50 raw treated events, 50 raw controls exactly 21 days
prior, 14 singleton plus 18 two-event clusters, 42-day cluster spacing, the
explicit -9%/-4% setup shocks and volume/volatility paths, effect units,
seed-to-replication mapping, four fixed cells (50 replications), 32-unit event
and paired minima, natural-yield disclosure, Wilson-bound decisions, and a
predeclared performance-only prefix. `outcomes_opened=false` describes the
pre-outcome plan bytes. A bounded WIP prefix was opened before the final
source freeze; its hashes are disclosed in the source-freeze receipts and it
is not calibration evidence. The corrected generator now materializes the
declared 240-child 4h setup bars, Markov regime paths, shared common shocks
for paired assets, AR(1) asset noise, lognormal volume, per-asset cost/filter
roles, and the actual fixed evaluator. A frozen source/JAR pair is retained
under `executors/` (JAR files are locally ignored; their hashes are in the
freeze receipts).

The D boundary can be checked before outcomes are opened:

```sh
./bin/analytics strategy-research-v5 operating-characteristics-preflight \
  --plan strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json \
  > /tmp/operating-characteristics-preflight-v004.json
```

It emits a self-bound `READY_PRE_OUTCOME` receipt tied to the fixed baseline,
TradeLifecycleV5 and disposition path. It does not generate outcomes or claim
calibration. The post-freeze prefix was run with the copied executor and
source-freeze receipt. It is `PREFIX_COMPLETE` with two planned replications,
10 events, 10 pairs and 8 independent units per replication. Both
replications are `INSUFFICIENT_EVIDENCE` under the frozen 30-unit minimum;
Wilson bounds and rates are unavailable. Runtime was 35.592 seconds
in-process, peak RSS 2,866,216,960 bytes, result content SHA
`3e54472f8a94973a53a87de44c9363c87608421b69352ca1465b5e76191407a5`, and
executor SHA `cef69d3f1293de27fc8462d450b2019bd569e0956de37e3dd3447fcd16d4393d`.
A separate full-budget run completed from the later immutable executor SHA
`dec4470d815d05f4eb56b871d4b07657745c06952ac802991e7b6b7a11e9d20c`. Its raw
result is retained as `operating-characteristics-full-v004-dec4470d.json` with
200/200 complete repetitions. The display-only compact projection
`operating-characteristics-full-v004-dec4470d-summary.json` contains all 200
seeds, metrics, counts, decisions and hashes; the terminal observation is
`operating-characteristics-full-v004-dec4470d-exit-receipt.json`. The monitor
observed process termination but did not capture the child exit integer, so the
receipt makes no exit-code claim. All four target decisions remain unmet and
the result is conditional synthetic diagnostic evidence only.


The original additive v004d prefix bytes remain at
`operating-characteristics-source-freeze-v004d.json` (content SHA
`9a5d1145191b346c901870af8acf81c6a2a04e4ccf77fcb4bc6f3f649b9bb5cc`) and
`operating-characteristics-prefix-v004d.json` (content SHA
`578743d1b7bd507d9d10c0fb1c4509983fc1c68def0d3cb13547285c2065d7cc`, byte SHA
`4c38430d64505f91ace6624cfe8e79ba8490510b2a863b9eab32f7c171e4bb19`). The
intermediate corrected lineage is retained under explicit
`operating-characteristics-source-freeze-v004d-corrected.json` and
`operating-characteristics-prefix-v004d-corrected.json` paths (freeze content
SHA `26a193cf3e28b679a0252c7dad14fd523977fc4c17ebd41ac184cd90e222bdf1`,
result content SHA `103a43c938d57ebbf665ae090b53c0cbaed2b5494978e02b52d56f35bbe66a35`).

The final additive v004e prefix uses
`operating-characteristics-source-freeze-v004e.json` (content SHA
`8fe7bb979f01c68b799f71577b538e04598969504b8ee49cb1e8a7d18fa7ffe7`) and
`operating-characteristics-prefix-v004e.json` (`PREFIX_COMPLETE`, content SHA
`58f183b276f770aff22d086061f7cee05ba25344b44feb0716001349ba80646e`, byte
SHA `5eeb2ece1a7de8ddde22e1754a651797606b27dd024d408d16b8445f364f321f`). It
has two completed replications, ten event/control series, eight tested
independent units per replication, and `INSUFFICIENT_EVIDENCE` with rates and
Wilson bounds unavailable; runtime was 32.108169541 seconds and peak sampled
RSS was 2,263,678,976 bytes. Compared with the retained corrected4 pre-freeze
prefix (content SHA `afe0d368612201fb98b506e15010810da873d8fed544d05520ca69a97e5dc4f4`,
byte SHA `8f7dcf6b4c064ddf36bf1f2534dc1010b8e2e1b670a8ce6a597329dd3660824c`),
all generator inputs, setup/control selections, metrics, dispositions,
decisions and portfolio totals match; eight representative trades match in
price, quantity, cost and net P&L with maximum Decimal residual
`9.856661156040E-14`. Lifecycle hashes and mark points differ only under the
conservative availability amendment. This is bounded prefix equivalence, not
all-200-replication or byte identity; see
`docs/RESEARCH-D-PREFIX-EQUIVALENCE-V004E.md` and the v004d transition receipt. The immutable v004e source-freeze prior-outcome list predates the two v004d transition records; the omitted hashes and their explicit transition paths are documented in `docs/RESEARCH-D-PREFIX-PROVENANCE-ERRATUM-20260906.md`.

Large raw projections remain locally retained but are ignored by default; compact
`strategy-research-evidence-summary/1` replacements preserve their source hashes
and account-level summaries as display-only, non-promoting evidence. The e11
summary has content SHA
`d85082bbfd02362d8fcd466e1c7b5e2eaf0c94a575fbadb5b945b9d47112d7bb` and byte
SHA `acd7085d208c3a5787ac6edc45fd4fc76e3cd404248bc6ec4d4452e627ba2970`.
The run-9 R1 summary has content SHA
`df7600c1726c66437381486d7c918d01110faa261b781c5867d4dd9872416cbc` and byte
SHA `2394aaf4ebb7a8fb15d57c41d448fbdf3efab9f634413850ed311f33947334ef`.
The immutable run-6 R1 summary has content SHA
`3131dcef92dfd1d1539d07245e362cbd5333b2b2dbc9d28a12a16a00935964c0` and byte
SHA `998c1221b4319223991c2cd9f5dd4cf91fba14a903f5628956495e46412806c5`.
The v20/v21 summaries have content SHAs
`1b7f533428277da123ceeabd2db042f123e395a2171ad783368c280ee80dfd5f` and
`231e1b7faaf44d3fc1e0b9eda057941cd88267af6f55b8b1fd7e5e63e1968b8a`,
respectively. Each projection retains status, binding identities,
account-currency totals, and source raw path/content/byte hashes while omitting
only repeated attempt and equity-curve arrays; it cannot be supplied as a
fixed-baseline or refinement predecessor.
