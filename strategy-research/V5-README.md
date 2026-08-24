# Strategy research/5

The v5 path is additive. Existing v1--v4 schemas, hashes, runs, and evidence
are read-only and are not migrated by this implementation.

## Commands

```sh
node tools/strategy-research-v5.mjs data-backfill --out strategy-research/v5-records/manifest-plan.json
node tools/strategy-research-v5.mjs opportunity-envelope --manifest <acquired-manifest-3.json> --candidates <candidate-set.json> --features <features.json> --out <envelope.json>
node tools/strategy-research-v5.mjs search-genetic --stack <stack.json> --manifest <acquired-manifest-3.json> --features <features.json> --labels <labels.json> --execution <execution.json> --gene-space <gene-space.json> --precommit-sha256 <sha> --experiment-sha256 <sha> --objective-contract-sha256 <sha> --acceptance-sha256 <sha> --dataset-root-sha256 <sha> --exposure-head <canonical-head.json> --genesis
node tools/strategy-research-v5.mjs research-run --input <authoritative-input.json>
node tools/strategy-research-v5.mjs overfit-audit --input <audit-input.json>
node tools/strategy-research-v5.mjs prospective-runner --reservation <frozen-reservation.json>
node tools/strategy-research-v5.mjs deployment-audit --out <deployment-audit.json>
node tools/strategy-research-v5.mjs validate --input <v5-record.json>
node tools/strategy-research-v5.mjs index --root strategy-research/v5-records
```

The same v5 command names are accepted by `strategy-research-next.mjs` for
callers that already use the v4 entry point; it forwards the same frozen input
contracts and explicit public-download options. `generate --method GENETIC`
continues to fail closed; only `search-genetic` has an adaptive evaluator.

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

The canonical CLI rejects caller-supplied fitness fixtures and requires the
frozen stack, physically acquired manifest, separated rows, lineage hashes,
objective/acceptance contract, dataset root, and a persistent canonical
exposure-head path. It never creates returns,
downloads private data, or fabricates unavailable derivatives. A test-only pure
API is available through `searchGenetic(..., testOnly:true)`. The first ledger
requires an explicit `genesis`; subsequent searches require the canonical
`HEAD` while allowing a new bound dataset root to preserve cumulative `K`.

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
limitation.

Authoritative evaluation consumes physically separate feature, label, and
1-minute execution rows. Feature rows are recursively checked for outcome
fields. Eligible signals must bind to a label and execution fill; outcomes are
derived from the label path plus bound costs, and market-wide episode vectors
contain internal zeros for eligible episodes without a trade. Caller-supplied
metrics/trades/stress/portfolio/WFO are rejected.

The WFO contract freezes eight quarterly folds with a 30-day purge and seven-day
embargo. Its public runner currently rejects every fold until the fold-level
statistical artifacts are authoritatively retained and hash-bound; it cannot
emit `SHADOW` from partial or caller-supplied evidence. The internal assembly
keeps 18-month weighting training-only and outer OOS unweighted.
The overfit audit is fail-closed for missing p20, max-statistic, search-adjusted
expectancy, DSR/PBO support, independent episodes, recent/earlier blocks,
positive years/folds, plateau/neighbours, seed stability, stress, and null
controls. It never returns `ACTIVE`.

## Prospective custody

The runner records a readiness contract only. A protected append-only evidence
branch, GitHub OIDC/workflow identity, Actions-only attestation key, separate
asset and portfolio approvers, public verification keys, and an offline
activation trust root are deployment prerequisites. No keys are generated or
stored by the repository and no GitHub settings are changed by the local CLI.
