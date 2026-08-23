# Durable strategy research registry

This Git-tracked directory is the audit record for swing-strategy research. It stores small, reviewable evidence—not raw market caches. Every strategy definition is immutable and versioned; every run is content-addressed and append-only. Existing paths are never overwritten.

## Layout

- `definitions/<strategy>/vNNN.json`: immutable strategy logic, lineage, field-level feature contract, and activation prohibition.
- `experiments/<id>/experiment.json` and `candidates.json`: exact grid, acceptance gates, declared/effective `K`, behavioral hashes, and the persisted candidate set.
- `runs/<sha256>/run.json`: content-addressed manifest with cross-file hashes and per-asset/portfolio decisions.
- `runs/<sha256>/*.jsonl`: all-candidate metrics plus compact trades for selected/finalist/frozen candidates.
- `evidence-bundles/<sha256>.json`: immutable authoritative recomputation bundle linked by v2 runs.
- `index.json`, `INDEX.md`, and `PERFORMANCE.md`: deterministic generated catalogs. Rebuild; do not hand-edit.

No file may exceed 10 MiB. Feature stores, exchange archives, ZIPs, raw caches, and large trade dumps stay outside Git. Their hashes belong in manifests/runs.

## Evidence and activation

Evidence phases are exact: `DEVELOPMENT`, `WALK_FORWARD_OOS`, `EXPOSED_CONFIRMATION`, `SEALED_CONFIRMATION`, and `PROSPECTIVE_LIVE`. V2 decisions are independent per asset and portfolio: `REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`; `ACTIVE` is impossible in this path. Missing PIT safety, no completed trades, or failed acceptance gates fail closed.

A registry run always has `activation.authorized:false`. `CANDIDATE_REVIEW` is not permission to trade and `ACTIVE` cannot be produced by research recording. Activation remains a separate reviewed calibration action under the FK/FR framework contracts.

## Commands

```sh
node tools/strategy-research.mjs generate --definition strategy-research/definitions/example/v001.json --experiment /path/to/experiment.json
node tools/strategy-research.mjs run --experiment strategy-research/experiments/experiment-id/experiment.json --features /path/to/store.json
node tools/strategy-research.mjs record --input /path/to/definition-or-experiment.json
node tools/strategy-research.mjs validate
node tools/strategy-research.mjs rebuild-index
node tools/strategy-research.mjs list --kind performance --asset btc --evidence_phase WALK_FORWARD_OOS --status SHADOW
node tools/strategy-research.mjs show --strategy family@v001
node tools/strategy-research.mjs show --id <run-id-or-prefix>
node tools/strategy-research.mjs compare --left <run-id> --right <run-id>
node tools/strategy-research.mjs import-legacy --source .report-run/strategy-v2
```

Grid keys are sorted before Cartesian expansion. Duplicate effective behavior is deduplicated before evaluation. Reusing one candidate ID for different behavior is an error. Runs preserve declared K, effective K, hashes, and effective K per declared feature series so the search penalty cannot silently shrink.

Legacy imports are deliberately conservative and cover BTC, ETH, SOL, BNB, XRP, ADA, LINK, and AAVE. Import records source hash/schema, only recoverable compact metrics/trades, and explicit missing-detail flags. They remain non-activation evidence.

## New candidate families (strategy-research/2)

New work is premise-first. Freeze a filled `strategy-precommit/1`, review its
deterministic Markdown, then generate the versioned definition and experiment:

```sh
node tools/strategy-research.mjs precommit --input premise.json --root strategy-research
node tools/strategy-research.mjs generate --precommit strategy-research/precommits/<family>.json --root strategy-research
```

The reusable protocol, staged ordering, PIT contracts, crypto-only universe,
robust statistics, plateau gates, portfolio simulator, stresses, and
prospective monitor are documented in [RESEARCH-PROTOCOL.md](RESEARCH-PROTOCOL.md).
V1 definitions, experiments, runs, and imports retain their original
semantics; v2 cannot authorize activation.

### Authoritative evaluation bundles

Promotable v2 evidence is produced only by the frozen, hash-bound evaluator:

```sh
node tools/strategy-research.mjs evaluate \
  --experiment strategy-research/experiments/<id>/experiment.json \
  --features /path/to/frozen-feature-store.json \
  --manifest /path/to/data-manifest.json \
  --record-root strategy-research
```

--out is an optional inspection copy; --record-root is the durable path. It
writes an immutable content-addressed evidence-bundles/<sha256>.json and
linked runs/<run_id>/run.json. Repeating an identical evaluation is a no-op;
changed bytes collide and fail. Walk-forward selection is train-only per
declared fold, with timestamp purge/embargo and bound fold artifacts.
Confirmation and prospective phases require a hash-bound frozen
one-candidate-per-asset selection plus its frozen behavioral-alias/K contract;
their new outcomes can never select a replacement. WFO behavioral K is
computed from TRAIN intent per fold, with aggregate OOS using the conservative
maximum fold K.

`strategy-data-manifest/1` binds source/data hashes, coverage, availability
policy, PIT status and revisions to the feature store. The registered
`swing-engine/1` adapter derives every candidate × required-asset metric and
trade from that store, then recomputes stress and the mark-to-market linear
portfolio. The resulting `strategy-evidence-bundle/1` carries executor source,
package/environment, seed, timezone/bar, cost/funding and reconciliation
hashes, including runtime behavioural K and aliases. The legacy `run` command
remains a read-compatible external/migration surface; supplied metrics,
trades, stress and portfolio results are never authoritative and cannot reach
activation review. Local v2 runs cannot mint `SEALED_CONFIRMATION`, and no v2
path can write `ACTIVE`. Prospective frozen bindings include the candidate,
definition, data, executor and a canonical experiment hash with the binding
field itself excluded (avoiding a self-referential hash); pre-freeze signals
are rejected. Options, multi-leg/basis/carry, HFT/order-book and other
specialized methods remain diagnostic/SHADOW until a specialized adapter is
registered. The linear portfolio carries last valid marks on the union
timeline only within a declared maximum mark gap and applies aggregate
cross-margin maintenance; missing marks or unsupported margin terms fail
closed.
