# Strategy research/5

The v5 path is additive. Existing v1--v4 schemas, hashes, runs, and evidence
are read-only and are not migrated by this implementation.

## Commands

```sh
node tools/strategy-research-v5.mjs data-backfill --as-of <ISO-UTC> --record-root strategy-research/v5-records --plan-out <plan.json> --coverage-out <coverage.json>
node tools/strategy-research-v5.mjs data-backfill --catalog-only --as-of <ISO-UTC> --raw-root <ignored/raw> --record-root strategy-research/v5-records --plan-out <plan.json> --catalog-out <catalog.json> --coverage-out <coverage.json>
node tools/strategy-research-v5.mjs data-backfill --download --as-of <ISO-UTC> --raw-root <ignored/raw> --staging-root <ignored/staging> --parquet-root <ignored/parquet> --record-root strategy-research/v5-records --plan-out <plan.json> --catalog-out <catalog.json> --coverage-out <coverage.json> --acquisition-out <staging-manifest.json> --parquet-out <parquet-manifest.json> --checkpoint checkpoint.json --rate-limit-ms 250
node tools/strategy-research-v5.mjs data-backfill --download --plan <plan.json> --catalog <catalog.json> --raw-root <ignored/raw> --staging-root <ignored/staging> --parquet-root <ignored/parquet> --record-root strategy-research/v5-records --acquisition-out <staging-manifest.json> --parquet-out <parquet-manifest.json> --checkpoint checkpoint.json --rate-limit-ms 250
node tools/strategy-research-v5.mjs data-raw-replay --plan <plan.json> --source-checkpoint <checkpoint.json> --source-root <prior-staging-root> --target-root <ignored/staging>
node tools/strategy-research-v5.mjs data-raw-replay --plan <plan.json> --source-checkpoint <checkpoint.json> --source-root <prior-staging-root> --target-root <ignored/staging> --parquet-root <ignored/parquet> --catalog <catalog.json> --catalog-root <catalog-raw-root> --record-root strategy-research/v5-records
node tools/strategy-research-v5.mjs opportunity-envelope --plan <plan.json> --acquisition <staging-manifest.json> --staging-root <ignored/staging> --candidates <frozen-candidate-set.json> --precommit <precommit.json> --gene-space <gene-space.json> --predictor-registry <predictors.json> --evaluator-spec <evaluator-spec.json> --features <physical-features.json> --hydrate --hydration-root <ignored/one-minute-root> --domain-out <opportunity-domain.json> --out <envelope.json> --hydration-out <hydration.json>
node tools/strategy-research-v5.mjs search-genetic --artifact <statistical-input.json> --plan <plan.json> --parquet-manifest <parquet-manifest.json> --parquet-root <ignored/parquet> --predictor-registry <predictors.json> --evaluator-spec <evaluator-spec.json> --gene-space <gene-space.json> --metadata <metadata-receipts.json> --exposure-head <canonical-head.json> --checkpoint <ignored/checkpoint.json> --cache-root <ignored/cache> --opportunity-domain <opportunity-domain.json> --opportunity-envelope <envelope.json> --opportunity-hydration <hydration.json> --hydration-root <ignored/one-minute-root>
node tools/strategy-research-v5.mjs research-run --plan <plan.json> --parquet-manifest <parquet-manifest.json> --parquet-root <ignored/parquet> --artifact <statistical-input.json> --opportunity-domain <opportunity-domain.json> --opportunity-envelope <envelope.json> --opportunity-hydration <hydration.json> --hydration-root <ignored/one-minute-root> --precommit <precommit.json> --experiment <experiment.json> --predictor-registry <predictors.json> --evaluator-spec <evaluator-spec.json> --gene-space <gene-space.json> --metadata <metadata-receipts.json> --exposure-head <canonical-head.json> --checkpoint <ignored/checkpoint.json> --cache-root <ignored/cache> --portfolio-mark-artifact <physical-mark-artifact.json> --portfolio-policy <frozen-portfolio-policy.json>
node tools/strategy-research-v5.mjs overfit-audit --artifact <statistical-input.json> --exposure-head-artifact <exposure-head.json> --vector <vector-inventory.json> --folds <wfo-folds.json> --genetic <genetic-run.json>
node tools/strategy-research-v5.mjs prospective-runner --ledger <shadow-ledger-dir> --expected-head-sha256 <cas-head-hash> --reservation <frozen-reservation.json> --source-receipt <source-receipt.json> --bar <completed-bar.json> --feature-input <feature.json> --candidate-set <candidate-set.json> --evaluator-code <evaluator-code.json> --signal-decision <signal.json>
node tools/strategy-research-v5.mjs deployment-audit --out <deployment-audit.json>
node tools/strategy-research-v5.mjs readiness-audit --evidence-manifest <frozen-evidence-manifest.json> --record-root strategy-research/v5-records --out <readiness-audit.json> --markdown <readiness-audit.md>
node tools/strategy-research-v5.mjs validate --input <v5-record.json>
node tools/strategy-research-v5.mjs index --root strategy-research/v5-records
```

The optional replay promotion requires all three of `--parquet-root`,
`--catalog`, and `--catalog-root`; it refuses a partial acquisition, verifies
the local Parquet reopen and frozen coverage, and writes content-addressed
artifacts under `--record-root`. A `STAGING_PARTIAL`/base-incomplete replay is
refused; BASE_ONLY remains explicit when optional metrics are absent. It never
upgrades non-PIT metric captures or an incompletely bound dated-futures series
into authoritative/tradeable data.

When resuming into a fresh staging root, pass the prior immutable partial
manifest with `--resume-acquisition <manifest.json>` and its original root
with `--resume-staging-root <old-root>`. The command verifies and copies only
manifest-bound raw, normalized-receipt, and partition bytes. Adapter cursor
files under `checkpoints/` are intentionally not trusted or copied; incomplete
captures refetch them and receive new, hash-bound adapter provenance. This
keeps the first blocked attempt byte-stable while making the corrected attempt
auditable.

`strategy-research-v5.mjs` is the authoritative v5 boundary. Legacy entry
points remain available for v1--v4 read support, but do not substitute their
loose input contracts for these commands. `generate --method GENETIC`
continues to fail closed; only `search-genetic` has an adaptive evaluator.
The opportunity command builds `strategy-v5-opportunity-envelope/2`: its
frozen premise predicate is the conservative superset over the complete
mutable gene domain, so an adaptive chromosome cannot create a signal outside
the hydrated universe. `--hydrate` is mandatory for a `COMPLETE` receipt; an
envelope-only invocation writes a `BLOCKED` receipt, and incomplete/right-edge
physical one-minute coverage is also blocked.

## Reproducible first-strategy workflow

For a new strategy family, freeze the premise and its falsifier first, then
run the following sequence. Every placeholder is a physical JSON artifact
written by the preceding command or by the frozen strategy package; do not
replace one with an inline object or a narrated metric.

1. Freeze `precommit.json`, `experiment.json`, `gene-space.json`,
   `predictors.json`, `evaluator-spec.json`, and the v5 portfolio policy. The
   policy must be `strategy-portfolio-policy/2`, `version: 2`, `status: FROZEN`,
   and bind the precommit, experiment acceptance, and evaluator lifecycle
   hashes.
2. Acquire and promote the public data, then verify the physical manifest:
   `data-backfill --download` → `validate --input <parquet-manifest.json>`.
   Keep raw/staging/checkpoint/cache roots outside the registry; only the
   content-addressed plan, receipts, and authoritative manifest are evidence.
3. Freeze the opportunity envelope from the promoted feature rows, run
   `search-genetic` with the persistent exposure head, and retain its immutable
   checkpoint/receipt. Candidate metrics and trades are outputs, never inputs.
4. Run `research-run` with the exact physical plan, Parquet root/manifest,
   evaluator inputs, envelope, exposure head, physical marks, and portfolio
   policy. A complete command emits `SHADOW` or `REJECTED`; incomplete inputs
   emit `BLOCKED` and must be repaired before proceeding.
5. Run `overfit-audit`, `validate`, and `index`. Read historical decisions with
   `index` filters for family/version, experiment, phase/status, and asset;
   never edit an existing content-addressed record to “advance” it.
6. Build a frozen `strategy-readiness-evidence-manifest/1` whose entries point
   to the exact JSON evidence files and their byte/content hashes, then run
   `readiness-audit`. The command reopens every listed dependency, writes a
   content-addressed JSON audit and deterministic Markdown with per-check
   evidence/failures, and reports capability separately from operational
   evidence. An empty or forged manifest scores zero and remains `BLOCKED`.
7. Only after a `SHADOW` evidence bundle is independently reviewed, start the
   prospective runner and deployment-audit workflow. This path records
   evidence and approvals; it does not authorize live trading locally.

Binance spot short positions are not executable under this contract: the
physical input set has no borrow/locate/recall/rate custody. Spot rows with
`direction: short` are rejected by both outcome derivation and portfolio risk;
shorts require a supported linear derivative with bound funding, margin,
liquidation, and expiry/settlement metadata where applicable.

`research-run` requires a separate physical
`strategy-portfolio-policy/2` artifact. It must use `version: 2` and status
`FROZEN`, be content-hash bound, and bind the exact precommit, experiment
acceptance, and evaluator lifecycle hashes consumed by the run. Its required
limits include mark-to-market drawdown/underwater/equity floors plus gross,
net, reservation, collateral, concentration, beta, maintenance-margin, and
top-level concurrency caps; `max_concurrent` is not duplicated inside
`limits`. Exposure, beta-exposure, and maintenance-margin caps are in the
account currency; share/HHI/reservation/collateral caps are fractions. The
policy's `asOf` cannot be later than its consuming cutoff, and
the equity floors cannot exceed current equity. The v1 policy schema remains
unchanged and is not accepted as a v5 portfolio-policy input.

Legacy v1--v4 validation reads the original bytes and historical hash
semantics without rewriting them. The v5 index records both content and
physical-byte hashes and exposes strategy family/version, experiment,
phase, status, decision, asset set, per-asset counts, and source-run identity;
registry index files themselves are validated but never indexed as evidence.

## Search contract

`searchGenetic` implements deterministic constrained NSGA-II. It freezes typed
continuous, ordered-discrete, categorical, and structural genes; evaluates
population history; records parent/operator/generation/seed/fitness; uses
constraint dominance, tournament selection, typed crossover/mutation, crowding
distance, and elitism; and preserves three independent seeds. Defaults are a
48-member population, 20 generations, 0.90 crossover, `1/gene_count` mutation,
18-month half-life training weighting, and a minimum of ten generations before
five-generation stopping. Every distinct behaviour, direct neighbour,
baseline, seed, and fold is charged to the cumulative exposure ledger.

The canonical CLI rejects caller-supplied fitness, returns, trades, metrics,
stress, portfolio, and WFO fields. It requires a frozen statistical artifact,
authoritative separated-Parquet manifest/root, evaluator spec, predictor
registry, gene space, metadata receipt bundle, persistent exposure head, and
ignored checkpoint/cache roots. It never fabricates returns, downloads private
data, or treats staging JSONL as authoritative. Exposure-head appends remain
physically hash-bound and cumulative `K` is never reset by a new dataset.

## Data and evidence boundary

The five-year planner freezes the latest five completed years for BTC, ETH, SOL,
BNB, XRP, ADA, LINK, and AAVE across Binance spot, USD-M perpetuals, and
available dated futures. The base acquisition is complete 4-hour history plus
exact event/contract metadata. One-minute data is a separate, deduplicated
opportunity-envelope hydration product covering every frozen core-predicate
window through the maximum lifecycle; the base manifest never claims that
hydration is present. Public OHLCV/funding/contract backfill is resumable
through content-addressed partitions and must be run explicitly; raw data
belongs under the gitignored lake/raw paths. Missing history, funding, expiry,
margin, liquidation, mark, or execution data remains an honest fail-closed
limitation. A partial download writes the frozen plan, catalog, staging
manifest, coverage report, and a `BLOCKED` receipt before returning; it never
promotes Parquet. The checkpoint argument is always a relative path inside the
supplied staging root (for example `--checkpoint checkpoint.json`), and a
resume must pass the exact hash-verified `--plan` and `--catalog` rather than
rediscovering a new catalog.

Authoritative evaluation consumes physically separate feature, label, and
1-minute execution rows. Feature rows are recursively checked for outcome
fields. Eligible signals must bind to a label and execution fill; outcomes are
derived from the label path plus bound costs, and market-wide episode vectors
contain internal zeros for eligible episodes without a trade. Caller-supplied
metrics/trades/stress/portfolio/WFO are rejected.

The WFO contract freezes eight quarterly folds with a 30-day purge and seven-day
embargo. `research-run` reopens the physical evaluator, runs genetic selection
inside each outer training fold, and derives selected physical fills, stress
decisions and portfolio decisions. It emits `SHADOW` or `REJECTED` only after
recomputation; missing physical prerequisites produce a schema-valid `BLOCKED`
receipt/run. The assembly keeps 18-month weighting training-only and outer OOS
unweighted. Every command writes an immutable receipt under the tracked
`strategy-research/v5-records/` root; raw/staging/Parquet/checkpoint/cache roots
remain explicitly ignored and are never indexed as evidence.
The overfit audit is fail-closed for missing p20, max-statistic, search-adjusted
expectancy, DSR/PBO support, independent episodes, recent/earlier blocks,
positive years/folds, plateau/neighbours, seed stability, stress, and null
controls. It never returns `ACTIVE`.

## Prospective custody

The runner records a readiness contract only. A protected append-only evidence
branch, GitHub OIDC/workflow identity, Actions-only attestation key, separate
asset and portfolio approvers, public verification keys, and an offline
activation trust root are deployment prerequisites. The deployment workflow
must receive externally managed `V5_TRUST_ROOT_FINGERPRINT`,
`V5_TRUST_ROOT_GENESIS_FINGERPRINT`, and `V5_ATTESTATION_KEY_FINGERPRINT`.

GitHub settings custody is fail-closed until the protected environment supplies
the separate read-only `strategy-v5-settings-auditor` App (App ID `4716635`,
installation ID `156531963`) and its PEM under
`V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM`. The auditor must be pinned to
the exact repository with only `actions`, `administration`, `environments`,
`metadata`, and `secrets` read permissions; it has no code, pull-request, write,
or event permissions. The capture must verify the App and installation through
JWT-authenticated metadata plus the installation-token repository list. A PAT
compatibility path may call `/user` with an explicitly pinned identity, but it
does not prove App installation identity and cannot satisfy activation without
the runtime receipts. It never falls back to `GITHUB_TOKEN`. The auditor PEM
itself must be environment-only and is not configured until its physical secret
is independently captured. The attestation and writer-App private keys have
the same environment-only requirement.

The exact writer App is `strategy-v5-evidence` (App ID `4716299`, installation
ID `156524819`) with metadata read, contents write, and pull-requests write,
no events, and access to only this repository. Both evidence rulesets must have
zero bypass actors: the immutable core enforces deletion and non-fast-forward,
while the writer gate enforces pull-request review and the required status
context `strategy-v5-evidence-custody`. That context is
emitted by the pinned, read-only `.github/workflows/strategy-v5-evidence-custody.yml`
workflow, which permits only additive evidence and verifies the complete
ledger prefix chain. `can_admins_bypass` must be false; configure this in the
GitHub environment/ruleset UI and verify it from the API. For an unattended
hourly SHADOW append, the environment may use protected-branches-only with no
reviewer; if a `required_reviewers` rule exists, it must contain a concrete
reviewer and `prevent_self_review: true` (an empty rule never counts). This
does not authorize activation: the separate cryptographic trust-root, asset,
portfolio, replay/revocation, and lease gates remain fail-closed.

`V5_REPOSITORY_VISIBILITY` must also be explicitly set to the observed frozen
mode (`PUBLIC` or `PRIVATE`); either mode is admissible, but a visibility change
or missing declaration blocks activation. Public repositories do not make
environment-scoped secrets or approval keys repository data.
All of these variables and fingerprints are external inputs; values embedded
in a repository artifact cannot self-pin trust. No
keys are generated or stored by the repository and no GitHub settings are
changed by the local CLI.

The OIDC policy is frozen to
`use_default:false`, `use_immutable_subject:true`, and
`include_claim_keys:["repo","context"]`. The subject must be the exact
immutable GitHub form
`repo:OWNER@OWNER_ID/REPO@REPO_ID:environment:prospective-v5`, with the
repository and owner IDs bound to the captured API metadata. Claims are
independently bound to workflow ref/SHA, run ID/attempt, repository ID,
environment, issuer, audience, and freshness. The capture verifies the JWT
signature against GitHub's OIDC JWKS; an unverified or name-only subject is
never accepted.

The public Binance prospective adapter is intentionally not enabled by this
repository yet. The hourly workflow accepts only a frozen, public,
content-addressed source bundle; without one it emits
`PROSPECTIVE_LIVE_SOURCE_UNCONFIGURED` and remains blocked. Enabling a public
adapter requires a version-pinned implementation, a completed-4h availability
cutoff, raw response byte receipts, pagination/coverage receipts, outage and
rate-limit fail-closed semantics, and a source hash bound into the cycle and
ledger. Private API keys, mutable “latest” endpoints, and unbound live rows
are not acceptable inputs. Until those artifacts and tests exist, no live
Binance data may feed even SHADOW.

## Causal feature, lifecycle and opportunity v5.1 primitives

The additive `tools/strategy-v5-feature-dag.mjs` module freezes a topologically
ordered feature program. `FIELD` nodes are point-in-time as-of reads
(`availability_time <= decision_time`); derived transforms are evaluated only
from the causal prefix. Rolling fit transforms (`ZSCORE`, robust z-score,
winsorization and percentile rank) are prior-only unless a frozen recipe
explicitly names `SELF_INCLUSIVE`. EMA is the standard recursive EMA and RSI is
Wilder RSI with its seed history. Graphs carry scalar type, unit, evidence
family, trade scope and precommit/registry/config/code hashes. CONTEXT_ONLY
inputs may qualify a crypto predictor, but a context-only lineage may not emit
a trade. The planner emits recursive source lookback and warmup bounds. The
registered `strategy-v5-feature-plan/1` artifact marks checkpoint state for
EMA/Wilder RSI; `resumeRecursiveEmaV5` and `resumeWilderRsiV5` provide portable
split/resume state with full-replay equivalence.

`tools/strategy-v5-lifecycle.mjs` normalizes a decision-boundary open, bounded
stop/target/partial/trailing lifecycle and derives quantity from the frozen
risk/notional contract. Its lifecycle interval is half-open `[entry,
entry + max_lifecycle_ms)`; the final time stop fills the close of
`entry + max_lifecycle_ms - bar_interval`. Stop/liquidation barriers win
same-bar collisions, gaps use the declared open-or-fail policy, and every
entry and partial/remaining exit charges fees, slippage, capacity and funding.

`tools/strategy-v5-opportunity.mjs` freezes a conservative OR-superset over the
full declared gene domain and candidate structural branches. Windows begin at
the exact decision boundary and hydration is a lazy reference to shared,
content-addressed 1-minute partitions; child-bar arrays are not nested per
episode. Hydration is unresolved rather than artificially time-stopped when
the right edge is incomplete, except for an exact declared terminal expiry.
Production envelopes require a complete, hash-bound candidate behavior domain
with precommit, gene-space and evaluator lineage; an unprovable branch fails
closed. `preentry_warmup_bars` is a separate dense requirement for ATR/structure
stops and is never silently expanded into exposure. Hydration indexes partition
bounds, reads intersecting bodies once, and enforces per-partition and aggregate
byte/row ceilings before parsing.
