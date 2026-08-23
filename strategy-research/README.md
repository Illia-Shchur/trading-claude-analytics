# Durable strategy research registry

This Git-tracked directory is the audit record for swing-strategy research. It stores small, reviewable evidence—not raw market caches. Every strategy definition is immutable and versioned; every run is content-addressed and append-only. Existing paths are never overwritten.

## Layout

- `definitions/<strategy>/vNNN.json`: immutable strategy logic, lineage, field-level feature contract, and activation prohibition.
- `experiments/<id>/experiment.json` and `candidates.json`: exact grid, acceptance gates, declared/effective `K`, behavioral hashes, and the persisted candidate set.
- `runs/<sha256>/run.json`: content-addressed manifest with cross-file hashes and per-asset/portfolio decisions.
- `runs/<sha256>/*.jsonl`: all-candidate metrics plus compact trades for selected/finalist/frozen candidates.
- `index.json`, `INDEX.md`, and `PERFORMANCE.md`: deterministic generated catalogs. Rebuild; do not hand-edit.

No file may exceed 10 MiB. Feature stores, exchange archives, ZIPs, raw caches, and large trade dumps stay outside Git. Their hashes belong in manifests/runs.

## Evidence and activation

Evidence phases are exact: `DEVELOPMENT`, `WALK_FORWARD_OOS`, `EXPOSED_CONFIRMATION`, `SEALED_CONFIRMATION`, and `PROSPECTIVE_LIVE`. Decisions are exact and independent per asset and portfolio: `REJECTED`, `SHADOW`, `CANDIDATE_REVIEW`, or `ACTIVE`. Missing PIT safety, no completed trades, or failed acceptance gates fail closed.

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
