# Liquidation daily stress v002

This is the owner-approved shorter-history successor to [v001](../liquidation-structure-v001/SPECIFICATION.md), in the same `liquidation-structure` family. The additive v002 contracts now include a public physical-input path, frozen replay identity, development-only portfolio runner, and evidence gate. These do not qualify historical point-in-time provenance or authorize promotion.

- [Implementation and backtest plan](IMPLEMENTATION-AND-BACKTEST-PLAN.md)
- [Actual-data diagnostic results](RESULTS.md)
- [Verification](VERIFICATION.md)
- [Frozen specification](SPECIFICATION.md)
- [Frozen precommit](frozen-precommit.json), with [readable rendering](frozen-precommit.md)
- [Original freeze manifest](FREEZE-MANIFEST.json)
- [Implementation clarifications](CLARIFICATIONS.md)
- [Implementation status and acceptance coverage](IMPLEMENTATION-STATUS.md)
- [Independent implementation review](IMPLEMENTATION-REVIEW.md)
- [Public archive source audit](IMPLEMENTATION-SOURCE-AUDIT.md)
- [Remaining research gates](NEXT-STEPS.md)

The durable frozen-precommit copies are byte-identical to the original registry files named in the freeze manifest. The original manifest is preserved. Raw provider responses, acquisition manifests and diagnostic outputs remain in gitignored `.research-run/` storage.

## Reproduce acquisition

Supply `COINALYZE_API_KEY` through the process environment. Do not put it in a source file, URL, committed configuration or command argument. Acquisition reads it only for the API request header.

```sh
./bin/analytics public-data-adapters coinalyze-daily \
  --from 2022-08-11 --to 2026-09-20 \
  --as-of 2026-09-20T00:00:00Z \
  --root .research-run/liquidation-daily-stress-v002/acquisition
```

Use a new root for each acquisition; dates are UTC and `--to` is exclusive. The importer resolves the four Binance USDT perpetual contracts from provider catalogues, retains raw bytes and hashes, and reports missing calendar days. It never fills missing observations with zeros.

## Reproduce the daily diagnostic

Create an input JSON with schema `daily-stress-preflight-input/1` and two references: `precommit_ref` and `acquisition_manifest_ref`. Each reference contains exactly `path` and `byte_sha256`. Bind the frozen precommit above and the acquired `coinalyze-daily-acquisition.json`; hash the actual file bytes with SHA-256. Relative reference paths resolve against the input file directory.

From the repository root, this creates the bindings from the actual files:

```sh
python3 - <<'PY'
from pathlib import Path
import hashlib, json
run = Path('.research-run/liquidation-daily-stress-v002')
def ref(path):
    path = path.resolve()
    return {'path': str(path), 'byte_sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
value = {
    'schema': 'daily-stress-preflight-input/1',
    'precommit_ref': ref(Path('docs/research/liquidation-daily-stress-v002/frozen-precommit.json')),
    'acquisition_manifest_ref': ref(run / 'acquisition/coinalyze-daily-acquisition.json'),
}
with (run / 'preflight-input.json').open('x') as output:
    json.dump(value, output, indent=2)
    output.write('\n')
PY
```

```sh
./bin/analytics strategy-research-v5 daily-stress-preflight \
  --input .research-run/liquidation-daily-stress-v002/preflight-input.json \
  --out .research-run/liquidation-daily-stress-v002/preflight-result.json
```

The diagnostic reopens raw files, verifies hashes and rederives normalized rows. It uses only the preceding 90 contiguous daily observations for each side's nearest-rank 95th percentile. A current positive observation must strictly exceed that threshold. The assumed availability is daily bucket start +48 hours. Eligible decisions begin November 11, 2022 and stop before July 15, 2026.

## Meaning of the result

Stress flags are not entries, independent episodes or evidence of profitability. These are Binance-specific observed liquidations, not market-wide totals; the provider warns that Binance publishes limited liquidation snapshots, so the figures are not a complete count of all forced liquidations. Historical publication/revision provenance remains unknown, so this is a disclosed retrospective proxy. The result stays `BLOCKED` / `DEVELOPMENT`; it cannot authorize SHADOW or live use.

## Reopen the retained historical-input qualification

The qualification command audits the exact retained acquisition vintage, without
requesting new provider data or inspecting strategy returns:

```sh
./bin/analytics strategy-research-v5 liquidation-input-qualify \
  --precommit docs/research/liquidation-daily-stress-v002/frozen-precommit.json \
  --acquisition .research-run/liquidation-daily-stress-v002/acquisition/coinalyze-daily-acquisition.json \
  --out .research-run/liquidation-daily-stress-v002/input-qualification.json
./bin/analytics strategy-research-v5 liquidation-input-verify \
  --receipt .research-run/liquidation-daily-stress-v002/input-qualification.json
```

The first command creates an immutable receipt. If that output already exists,
run only the verification command; use a new output path for a new audit.
Keep the acquisition manifest and its referenced raw files together. The audit
rejects a different acquisition vintage, even if its date range is identical.
The receipt distinguishes physical holdings of data from historical availability;
`UNAVAILABLE` in this pinned acquisition audit means that the required series is
not present in that acquisition, not that no public archive exists. A later
physical development manifest must independently reopen the additional inputs.

## Additive v002 physical and replay commands

The v002-only commands are available through `strategy-research-v5`. They keep
the existing eight-asset, five-year and 30-day contracts unchanged:

1. `liquidation-profile-contract` writes the frozen four-asset policy; `liquidation-v2-plan` derives the versioned physical plan.
2. `liquidation-v2-physical-build` creates separate FEATURE, LABEL, EXECUTION, MARK, FUNDING and METADATA Parquet roles from hash-bound source partitions and normalization receipts. `liquidation-v2-physical-verify` reopens them. `liquidation-input-qualify` / `liquidation-input-verify` retain the pinned daily acquisition audit.
3. `liquidation-v2-freeze` binds the exact v002 precommit bytes, v001 family lineage, profile, physical manifest/plan, source and executor bytes, candidate inventory and macro policy before replay.
4. `liquidation-v2-replay` runs the full permitted proxy envelope. For an explicitly synthetic fixture only, pass `synthetic_smoke: true`, a UTC-day-aligned `feature_warmup_start` at least 92 days before `replay_start`, and a decision window of at most seven days. The feature warmup seeds signals but does not create opportunity rows.
5. `liquidation-v2-run-evaluate` is the one-pass path: it accepts a `freeze` plus `replay_request`, writes immutable `replay_out` and `evidence_out` under the bound physical root, and derives evidence directly from the in-process runner. Standalone `liquidation-v2-evidence` reruns the physical engine from the embedded `execution_request` and rejects any replay whose recomputed PnL, drawdown, ledger or event hashes differ.

`liquidation-v2-run-evaluate` returns the research evidence decision. A completed
synthetic execution can therefore return `BLOCKED`; `single_runner_pass` and the
retained replay/evidence bindings describe execution completion. The resumable
runner separately returns operational `COMPLETE` / `RESUMABLE` statuses.

The freeze/replay/evidence commands take `--options <json-file>`. The physical
plan takes `--profile <json-file>`; physical build takes `--profile`, `--inputs`
and `--root`; physical verify takes `--profile`, `--manifest` and `--root`.
Profile and plan commands print JSON to standard output; redirect it to a new
file to retain the artifact. A `liquidation-v2-freeze` options file has
`project_root`, `profile`, and `physical_options: {root, profile, manifest}`.
These nested values are JSON objects, not file paths.

A replay options file contains the frozen `freeze` object, a `replay_request`
object, and an absolute `out` path beneath the frozen physical root. The
synthetic request has `synthetic_smoke`, `feature_warmup_start`, `replay_start`,
`decision_end_exclusive` and `execution_end_exclusive`. The decision window
is at most seven days; the execution window may continue through the 60-day
position lifecycle. Legacy `replay_end_exclusive` uses the same end for both.
For proxy mode, the runner enforces the full frozen decision and execution
envelope. For synthetic mode, its status remains fixture-only. Outputs remain
`DEVELOPMENT_ONLY`; public archives and disclosed availability assumptions do
not establish historical vintages. Macro-enabled additions use the frozen
next-completed-NYSE-session model only when its receipt-bound source and
normalization policy are present; that modeled schedule is not PIT proof.

### Interrupt and resume

`liquidation-v2-replay-resumable --options <json-file>` uses the same frozen
inputs and adds `checkpoint_out`, optional `operational_out`, and optional
`stop_after_boundary_exclusive` (a UTC midnight). It returns `RESUMABLE` for
a deliberate intermediate stop, `COMPLETE` after finalization, or
`COMPUTE_INCOMPLETE` when the durable compute allowance prevents more work.
To continue, set `resume: true` and advance or remove the stop boundary while
preserving the original request, freeze, output and checkpoint bindings.
Do not shorten the original execution envelope to make a checkpoint.

Resume rebuilds the saved prefix from verified inputs, checks its hashes and
continues the same portfolio state. A checkpoint does not close open positions.
The real family compute ledger lives under gitignored `.research-run/`; retain
it across attempts and candidate changes. Deleting it is not a budget reset.
Synthetic fixtures keep their separate budget custody beneath their fixture
root. Operational timing receipts are separate from deterministic replay bytes.

### Sequential staged comparison

Complete the core run and its evidence first. The sequential commands are:

| Step | Command | Inputs |
|---|---|---|
| Freeze three stages without macro | `liquidation-v2-staged-plan-no-macro` | `--core-replay`, `--core-evidence`, `--candidate-inventory`: paths to the exact core artifacts |
| Execute the staged plan | `liquidation-v2-staged-replay` | `--options`: the staged run options below |
| Reexecute and evaluate it | `liquidation-v2-staged-evidence` | `--options`: the same predecessor bindings, plus the resulting `replay` object and evidence `out` |
| Freeze macro additions last | `liquidation-v2-staged-plan-macro` | `--freeze`, `--no-macro-plan`, `--core-replay`, `--core-evidence`, `--no-macro-replay`, `--no-macro-evidence`: JSON file paths; required `--out`, optional `--checkpoint-out` |
| Execute and evaluate macro additions | The same staged replay/evidence commands | The macro plan and its exact no-macro predecessor bindings |

Plan commands print JSON to standard output. Macro-plan freezing also writes
its immutable `--out` beneath the frozen physical root and charges evidence
recomputation to the same durable family budget. Retain the exact object; do not
copy selected fields or modify its gate map. Every staged run options file
contains `freeze`, `staged_plan`, `core_replay`, `core_evidence`,
`core_candidate_inventory`, the unchanged `replay_request`, and `out`.
These are embedded JSON objects except the output path. Macro-mode options
also contain `predecessor_plan`, `predecessor_replay` and
`predecessor_evidence` from the no-macro comparison. Output files remain
beneath the bound physical root.

The runner reexecutes and verifies the predecessor before exposing the new
candidate's outcomes. Failed historical predecessor gates stop advancement;
the synthetic-fixture exception exercises mechanics and never claims survival.
All stages retain the original decision-anchor inventory, including no-trades
and unresolved outcomes. Additions use their own actual prior fills, shared
account capacity and the original first-fill 60-day deadline.

The [implementation status](IMPLEMENTATION-STATUS.md) lists every acceptance
gate and unresolved limitation. A synthetic replay does not establish historical performance; missing mandatory price/OI/execution/funding/metadata inputs
block an actual physical run, and no unavailable rows are synthesized.
