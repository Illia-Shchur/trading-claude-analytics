# Durable strategy research registry

## Canonical v3 foundation

New research uses the additive v3 contracts and pinned DuckDB/Parquet lake:
`strategy-data-manifest/2`, `research-feature-set/1`, `research-label-set/1`,
`strategy-experiment/3`, `strategy-evidence-bundle/2`, and
`strategy-run/3`. Features and future labels are physically separate; only
PIT-verified Parquet can support WFO or confirmation. Required tradable assets
are BTC, ETH, SOL, BNB, XRP, ADA, LINK, and AAVE. Other markets are CONTEXT
only; DOGE is excluded.

`evaluate-v3` rejects caller-authored metrics/trades and executes the frozen
DEVELOPMENT/WALK_FORWARD_OOS path locally from the v3 experiment, candidate
set, PIT feature/label manifests, and pinned swing executor. The separate
public-unseen-data custody runner is not shipped, so CI attestation remains
`CI_ATTESTED_CONFIRMATION`/SHADOW and cannot become SEALED or ACTIVE locally.
Reservations bind current commit/workflow bytes, every lineage hash,
container/executor hashes, and canonical confirmation paths; remote immutable
burn evidence plus append-only import records are required.

This Git-tracked directory is the audit record for swing-strategy research. It stores small, reviewable evidence—not raw market caches. Every strategy definition is immutable and versioned; every run is content-addressed and append-only. Existing paths are never overwritten.

## Layout

- `definitions/<strategy>/vNNN.json`: immutable strategy logic, lineage, field-level feature contract, and activation prohibition.
- `experiments/<id>/experiment.json` and `candidates.json`: exact grid, acceptance gates, declared/effective `K`, behavioral hashes, and the persisted candidate set.
- `runs/<sha256>/run.json`: content-addressed manifest with cross-file hashes and per-asset/portfolio decisions.
- `runs/<sha256>/*.jsonl`: all-candidate metrics plus compact trades for selected/finalist/frozen candidates.
- `evidence-bundles/<sha256>.json`: immutable v3 authoritative recomputation bundle linked by v3 runs.
- `index.json`, `INDEX.md`, and `PERFORMANCE.md`: deterministic generated catalogs. Rebuild; do not hand-edit.

No file may exceed 10 MiB. Feature stores, exchange archives, ZIPs, raw caches, and large trade dumps stay outside Git. Their hashes belong in manifests/runs.

## Evidence and activation

Evidence phases are exact for the additive v4 stack: `DEVELOPMENT`, `WALK_FORWARD_OOS`, `EXPOSED_CONFIRMATION`, `CI_ATTESTED_CONFIRMATION`, and `PROSPECTIVE_LIVE`. V3 historical custody labels remain read-only. Decisions are independent per asset and portfolio: `REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`; `ACTIVE` is impossible in this path. Missing PIT safety, no completed trades, or failed acceptance gates fail closed.

A registry run always has `activation.authorized:false`. `CANDIDATE_REVIEW` is not permission to trade and `ACTIVE` cannot be produced by research recording. Activation remains a separate reviewed calibration action under the FK/FR framework contracts.

## Commands

```sh
node tools/strategy-research.mjs generate --precommit strategy-research/precommits/<family>.json --root strategy-research
node tools/strategy-research.mjs evaluate-v3 --experiment /path/to/experiment.json --manifest /path/to/dataset-manifest.json --features /path/to/feature-set.json --labels /path/to/label-set.json --candidates /path/to/candidates.json
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

## New candidate families (strategy-research/3)

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

### Additive strategy-research/4 canonical stack

The next-generation policy core is `tools/strategy-research-next.mjs`. It is
additive: v1-v3 definitions, runs, and evidence remain read-only and are never
relabeled. It binds one premise-first contract, deterministic `GRID`, `RANDOM`,
`GENETIC`, and `ML` generator records, runtime-behavior aliases, and an
append-only hypothesis-family × dataset exposure ledger so cumulative `K`
cannot reset between experiments. GENETIC is a recognized frozen contract but
stays fail-closed until an evolutionary fitness/selection implementation is
bound; it is not presented as random sampling.

PIT authority comes from the reviewed source registry, not a caller flag.
Unknown/custom imports receive `UNVERIFIED_DEVELOPMENT_ONLY`; a hash does not
upgrade them. Features and future labels remain separate, completed-bar/as-of
rules are explicit, and missing historical derivatives/funding data fails
closed. Binance execution is market/stop-market only, uses the next executable
1-minute child bar with adverse collision handling, deterministic public-data
slippage/participation capacity, effective fees/funding, and outage/gap
repricing. Public bars do not claim private queue/fill reconstruction.

The portfolio profile is frozen at 1.5% initial trade risk, 6% total open risk,
3% asset, 4.5% cluster, 3x gross, 2x net, 70% collateral, six positions (two
per asset), 18% drawdown, and a 30% ruin boundary with <5% block-bootstrap
ruin probability. Full risk applies immediately after approval; there is no
probation ramp. Nested purge/embargo WFO, synchronized stationary-block
candidate-set max statistics, plateau/ablation contracts, event-time risk
diagnostics, future-only hash-chained prospective evidence, and a separate
Ed25519 90-day `strategy-activation/1` lease are all fail-closed interfaces.
Research emits only `REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`; `ACTIVE`
requires verified asset and portfolio approvals and is never emitted by a
research run. The readiness audit reports activation infrastructure separately
and always reports zero actual active strategies.

Activation verification requires an externally configured trust root, supplied
through `STRATEGY_RESEARCH_TRUST_ROOT_FILE` (JSON with `key_id` and
`public_key_pem`) or the paired `STRATEGY_RESEARCH_TRUST_ROOT_KEY_ID` and
`STRATEGY_RESEARCH_TRUST_ROOT_PUBLIC_KEY_PEM` environment variables. A key
embedded in an artifact or generated by a caller is not trusted.

```sh
node tools/strategy-research-next.mjs precommit --input premise.json
node tools/strategy-research-next.mjs generate --precommit strategy-research/precommits/<id>.json --method GRID
node tools/strategy-research-next.mjs exposure --candidates strategy-research/candidates/<sha>.json --hypothesis-family <family> --dataset-root-sha256 <sha256>
node tools/strategy-research-next.mjs evaluate --precommit <frozen.json> --candidates <candidate-set.json> --stack <stack.json> --manifest <data-manifest.json> --features <feature-rows.json> --labels <label-rows.json> --feature-set <feature-set.json> --label-set <label-set.json> --receipts <source-receipts.json>
node tools/strategy-research-next.mjs wfo --input <stacked-wfo-input.json>
node tools/strategy-research-next.mjs snapshot-next --input <public.jsonl> --source binance:spot-ohlcv --asset btc
node tools/strategy-research-next.mjs source-receipt --source binance:spot-ohlcv
node tools/strategy-research-next.mjs readiness --out strategy-research/readiness-audit.json
```

### Authoritative v3 evaluation bundles (v1/v2 read-only)

Historical v1/v2 evidence can be inspected with the legacy evaluator, but it
is read-only. New authoritative evidence is produced only by the canonical v3
evaluator below:

```sh
node tools/strategy-research.mjs evaluate-v3 \
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

`strategy-data-manifest/2` binds source/data hashes, coverage, availability
policy, PIT status and revisions to physically separate feature/label stores.
The v3 `evaluate-v3` path uses the registered `swing-engine/1` adapter to derive
every candidate × required-asset metric and compact selected/OOS trades, then
recomputes stress and the mark-to-market linear portfolio. The resulting
`strategy-evidence-bundle/2` carries executor source, package/environment,
seed, timezone/bar, cost/funding, candidate-accounting and reconciliation
hashes. v1/v2 commands remain read-compatible historical/migration surfaces;
supplied metrics, trades, stress and portfolio results are never authoritative
and cannot reach activation review. Local v3 runs cannot mint
`SEALED_CONFIRMATION`, and no v3 path can write `ACTIVE`. Prospective frozen bindings include the candidate,
definition, data, executor and a canonical experiment hash with the binding
field itself excluded (avoiding a self-referential hash); pre-freeze signals
are rejected. Options, multi-leg/basis/carry, HFT/order-book and other
specialized methods remain diagnostic/SHADOW until a specialized adapter is
registered. The linear portfolio carries last valid marks on the union
timeline only within a declared maximum mark gap and applies aggregate
cross-margin maintenance; missing marks or unsupported margin terms fail
closed.

### Foundation v3

The v3 foundation adds a pinned DuckDB/Parquet lake and physically separate
`features` and `labels` layers. Use `node tools/research-data.mjs snapshot`
for immutable snapshots; the default `parquet` format is produced only by the
pinned image in `docker/duckdb.lock.json`. Raw data and disposable catalogs
stay outside Git.

New v3 contracts (`strategy-experiment/3`, `strategy-evidence-bundle/2`, and
`strategy-run/3`) bind precommit, candidate, feature/label, data, executor,
chronology, seed, cost, and acceptance hashes. The reference
`balanced-swing-v1` contract is created with:

```sh
node tools/strategy-research.mjs acceptance-contract --out .research-run/acceptance.json
node tools/research-data.mjs snapshot --input <source.jsonl> --asset btc --pit-tier T0_IMMUTABLE_EVENT
```

The resulting `strategy-data-manifest/2`, `research-feature-set/1`, and
`research-label-set/1` manifests are portable/content-addressed; a label set
is explicitly ineligible for predictors. `research-data init`, `query`,
`diff`, `pack`, and `restore` provide deterministic lake operations. Parquet
conversion fails closed when Docker is unavailable instead of writing a file
with a false Parquet extension.

For a local authoritative backtest, run `evaluate-v3` with the frozen
experiment, manifest, feature set, label set, and candidate set. DEVELOPMENT
may use JSONL staging; WFO and exposed/confirmation phases require verified
Parquet artifacts and physical partition hashes. The CLI records canonical
metrics for every candidate×asset plus compact selected/OOS trades, robust metrics, WFO,
stress, portfolio, evidence, run, and an append-only v3 index.

The confirmation contract is explicitly `CI_ATTESTED_CONFIRMATION`. The
checked-in workflow currently performs reservation/workflow preflight and
fails before burn because the public-unseen-data custody runner is unavailable.
When that runner is shipped, it must use a one-time reservation, durable
immutable burn receipt, and Ed25519 verify/import flow. A local hash is not
custody of an unseen holdout, so local code cannot mint `SEALED_CONFIRMATION`;
`ACTIVE` is impossible in every research path.
