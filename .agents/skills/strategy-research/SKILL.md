---
name: strategy-research
description: "Premise-first creation and rigorous testing of new crypto strategy families and candidate setups. Use when developing, generating, backtesting, comparing, rejecting, or advancing a new strategy, signal family, market phenomenon, entry trigger, risk lifecycle, context filter, or composite-score extension. Governs precommitment, crypto-only trade scope, PIT data contracts, candidate accounting, walk-forward evidence, robust statistics, stress/portfolio tests, and SHADOW decisions. Do not use for an ordinary live Fallen Knives/Flying Rocket report or for calibrating an existing framework against its own prior reports."
---

# Strategy Research

Develop falsifiable crypto trading ideas before optimizing rules. The objective
is a durable research record that distinguishes an economic hypothesis from a
parameter search and never converts research evidence directly into live trade
authorization.

## Mandatory Stage 1: premise before candidate

Every new strategy family starts with one immutable one-page
`strategy-precommit/1`. Complete it before viewing candidate outcomes, making a
parameter grid, selecting a composite-score threshold, or choosing a favourable
asset/window.

The precommit must contain:

1. Observable phenomenon and economic or behavioural explanation.
2. Forced actor, edge provider, and edge consumer: who transfers value to whom.
3. Why the effect should persist and how crowding or adaptation should weaken it.
4. Exact long, short, or relative direction; crypto expression; holding horizon.
5. Expected independent signal-frequency range with a time unit.
6. Expected win-rate range.
7. Expected average win and average loss in R, plus the qualitative payoff shape.
8. Regimes where it should work.
9. Regimes where it should fail.
10. Required data, evidence family, availability timestamp, and PIT/completed-bar contract.
11. Failure or invalidation mechanism.
12. Simplest falsifying test, named null, and thresholds selected in advance.
13. Intended independent replication groups.
14. Explicit statement that a composite score is deferred to a later incremental test.

Use the expectation ranges as predictions that later evidence will grade. Do
not infer them from the backtest. Reject or redesign the idea before testing if
the proposed window cannot plausibly produce enough independent episodes, the
mechanism is not falsifiable, or the required data cannot satisfy its PIT
contract.

## How to develop the premise

Build the causal chain in this order:

`participant constraint -> observable market state -> delayed adjustment -> crypto trade expression -> expected payoff -> falsifier`

Good starting phenomena may come from forced liquidation/deleveraging,
inventory or hedging pressure, funding/basis/carry, option positioning,
liquidity fragmentation, slow information diffusion, behavioural
under/overreaction, protocol/token mechanics, or cross-crypto relative value.
Price indicators can measure a phenomenon but do not by themselves explain why
someone should keep paying the strategy.

For each idea, actively ask:

- What participant is constrained rather than merely wrong?
- Why can arbitrage capital not remove the effect immediately?
- Which observation is available before the decision, not revised afterward?
- What would happen in a regime where the mechanism is absent?
- Which result would falsify the mechanism rather than invite another threshold?
- Is apparent replication genuinely independent, or the same crypto beta,
  venue, cycle, data provider, or evidence family repeated on another symbol?

Do not begin from a composite score, profitable threshold, favourite indicator
combination, or retrospective chart pattern. Such observations may inspire a
new hypothesis, but the mechanism and precommit must be written and frozen
before their outcomes are tested.

## Sixth-edition method extensions

The sixth edition of Perry Kaufman's *Trading Systems and Methods* expands the
research surface to AI/machine learning, game theory, genetic algorithms,
high-frequency trading/arbitrage, and more sophisticated risk and portfolio
models. These are additional method contracts, not exceptions to Stage 1:

- **AI/ML:** precommit the prediction target, decision timestamp, feature
  availability, training cutoff, baseline, loss/fitness function,
  hyperparameter budget, seeds, retraining rule, and nested chronological
  evaluation. A model is one searched strategy family; random train/test splits
  and outcome-adjacent features are forbidden.
- **Game theory:** identify the strategic actors, available actions, payoff
  transfer, feedback/crowding response, and the observation that would show the
  opponent adapted. A story about "smart money" is not a game-theoretic model.
- **Genetic/evolutionary search:** freeze the objective and constraints before
  search; count every evaluated chromosome/configuration in the search budget;
  preserve seeds and population history; require simple-baseline, plateau, and
  independent confirmation evidence. A genetic optimizer does not reduce K.
- **HFT/arbitrage:** bar-level theoretical spread is not executable PnL. Require
  synchronized legs, latency/queue/partial-fill assumptions, fees, funding,
  borrow, collateral, capacity, venue outage, and unwind/failure behavior. If
  the available data cannot represent these, reject the test or label it only
  as an opportunity-frequency diagnostic.
- **Advanced risk:** grade skew, tails, loss runs, extreme events, liquidity,
  leverage/ruin, signal similarity, correlation concentration, and expected
  versus realized behavior—not only mean expectancy, win rate, or Sharpe.

For any candidate using one of these methods, read and apply
[`references/sixth-edition-methods.md`](references/sixth-edition-methods.md)
before freezing its precommit or experiment.

## Tradable and context universes

Trade crypto infrastructure only. Crypto spot, perpetuals, dated futures,
options, basis, funding, carry, and other declared crypto derivatives are
eligible. Derivatives require venue, collateral, and funding/carry treatment.

Equities, ETFs, rates, FX, commodities, bonds, and indexes may be PIT-safe side
data for regime, correlation, or context. They are never candidate instruments,
validation assets, holdings, portfolio PnL, or trade recommendations. Context
must add evidence not already encoded in the setup; disclosed overlap blocks
promotion.

## Research sequence after Stage 1

Develop one layer at a time:

`CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE`

- Test the minimal score-free premise first.
- Add a small predeclared set of entry confirmations.
- Then test stop, target, partial, timeout, and sizing lifecycle.
- Add one independent context family at a time, with add-one and leave-one-out ablations.
- Test a composite score only after the score-free baseline survives, and bind
  it to that baseline's hash.

Each later stage references the frozen predecessor. A material mechanism change
creates a new family or immutable version; it does not rewrite history.

## Testing and selection requirements

Before running research, read and follow the complete
[`strategy-research/RESEARCH-PROTOCOL.md`](../../../strategy-research/RESEARCH-PROTOCOL.md).
Use its canonical template at
[`strategy-research/templates/strategy-precommit-1.template.json`](../../../strategy-research/templates/strategy-precommit-1.template.json).

At minimum:

- freeze the precommit before generation;
- separate development, walk-forward OOS, exposed, sealed, and prospective evidence;
- preserve declared and behaviourally effective hypothesis counts;
- align event/time-block resampling across candidates and crypto assets;
- require bootstrap p20, independent episode count, and max-statistic evidence;
- reject isolated parameter optima using connected plateau gates;
- stress recorded fees, slippage, funding/carry, gaps, liquidity, and venue outages;
- test asset and portfolio decisions separately with real concurrency and account-currency PnL;
- record metrics for every effective candidate and compact trades for finalists;
- preserve all source/content hashes and fail closed on missing evidence.

Use `node tools/strategy-research.mjs precommit` before `generate`. The CLI and
registry are authoritative for immutable hashes, candidate accounting, run
recording, indexes, and tamper checks; narrated results do not replace them.

## Additive v4 stack boundary

The canonical next-generation path is `node tools/strategy-research-next.mjs`:
freeze the filled canonical precommit, generate candidates, append the durable
family×dataset exposure ledger, bind a PIT snapshot/receipts, then run the
authoritative feature-backed `evaluate` command with separate raw feature/label
rows plus frozen `--feature-set` and `--label-set` artifacts. The `wfo` command
requires the same frozen stack, manifest, feature/label sets, receipts, and
folds. `GRID`, `RANDOM`, and `ML`
outputs carry frozen seeds/budgets/cutoffs. The GENETIC contract is recognized
but remains fail-closed until a recorded evolutionary fitness/selection
implementation is supplied; deterministic sampling is never mislabelled as a
genetic optimizer. Caller-supplied WFO metrics are rejected.

The v4 executor is Binance spot and USD-M linear swing scope only (MARKET and
STOP_MARKET), with completed-bar → next 1m-child ordering, adverse OHLC
collisions, bound fee/funding receipts, capacity and outage fail-closed rules.
Its portfolio simulator is event-time based: entries reserve current-equity
capacity and PnL/funding are credited only at exit/settlement events. PIT
validation requires source-registry receipts, row bindings, physical hashes,
and complete eight-asset 1m/1h/4h/1d coverage for non-development evidence.

Prospective signals/outcomes are reservation- and lineage-bound; one matching
signal, declared horizon, capture receipt, and one resolution are mandatory.
Fast minimums (60 days, 25 portfolio trades, 8 per proposed asset) do not waive
statistical, stress, monitoring, or portfolio gates. Research emits only
`REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`; activation requires an external
trust-root Ed25519 signature, distinct asset/portfolio approvers, exact evidence
digests, a 90-day lease, and revocation/drift checks. Local readiness reports
activation infrastructure separately and keeps actual active strategies at 0.
Missing GitHub/OIDC custody or external trust roots is an honest fail-closed
limitation, not a readiness pass.

## Canonical v3 execution (mandatory for new research)

New strategy-research/3 outcome work must use `node tools/strategy-research.mjs
evaluate-v3` with a frozen experiment/candidate set, PIT feature/label sets and
`strategy-data-manifest/2`. The registered `swing-engine/1` adapter is the
implemented ordinary completed-bar swing capability. It hashes adapter source,
config, supported features/instruments, feature/data manifests,
package-lock/environment, seed ledger, timezone/bar convention, same-bar
collision and cost/funding assumptions. It derives canonical trades and
metrics for every effective candidate × required asset, while zero-trade
episodes remain internal and digest-bound; it recomputes the selected/OOS stress suite and mark-to-market
linear crypto portfolio from those trades. `strategy-evidence-bundle/2` binds
candidate accounting, compact selected/OOS trades, derived-metrics, stress and
portfolio hashes.

The local path rejects `SEALED_CONFIRMATION`; an externally signed attestation
bound to experiment/candidate/data hashes is required for that label, and no
v3 path can produce `ACTIVE`. Caller-supplied metrics, trades, stress or
portfolio JSON is only an `EXTERNAL_EXPOSED` migration/read path and remains
`REJECTED`/`SHADOW`. Require explicit chronology (development/training,
chronological folds, purge/embargo, test/holdout or prospective bounds,
selection objective/tie-break, seeds and timezone), PIT/data-manifest safety,
typed parameter topology, behavioural K, and advanced risk diagnostics.
Confirmation and prospective phases require a frozen lineage-bound
one-candidate-per-asset selection and alias/K contract; they never select from
new confirmation/monitoring outcomes. WFO computes behavioral K from TRAIN
intent per fold and uses only TRAIN-selected candidates on TEST; purge and
embargo are timestamp gaps, not labels. A missing or mismatched contract is a
hard failure.
Unsupported options, multi-leg basis/carry, nonlinear derivatives,
HFT/arbitrage, queue/latency and atomic-spread claims fail closed or remain
diagnostic/SHADOW until a specialized adapter is registered. The portfolio
mark path supports crypto spot, linear perpetuals and linear dated futures;
leveraged positions without exact entry/exit marks, a declared mark-gap
contract, or actual funding/collateral terms fail. Marks carry forward only
within that gap, and cross-margin maintenance is aggregate-account math.

## Foundation v3 implementation boundary

The repository also carries additive `strategy-experiment/3`,
`strategy-evidence-bundle/2`, and `strategy-run/3` contracts. These bind
immutable PIT-tiered data manifests, physically separate feature and label
sets, executor/container hashes, candidate accounting, the acceptance
contract, and chronological seeds. The `balanced-swing-v1` acceptance profile
includes independent episodes, raw and search-adjusted expectancy, R/account
profit factors, bootstrap p20, candidate max-statistic, years, chronological
blocks, doubled costs, coverage and declared gaps.

`node tools/research-data.mjs` owns snapshots and the pinned Docker DuckDB
conversion. `node tools/strategy-attestation.mjs` owns reservations, burn
records, Ed25519 signing, verification and append-only import. GitHub Actions
may produce `CI_ATTESTED_CONFIRMATION`; it must never be described as sealed
without independent unseen-data custody. No v3 path can produce `ACTIVE`.

## Deliverables by request type

- **Idea development only:** produce and critique the one-page precommit. Do not
  inspect outcomes or generate a search grid.
- **Candidate generation:** require a frozen precommit, then declare the small
  mechanism-linked grid and acceptance gates before generation.
- **Backtest/research:** verify PIT/data feasibility, run the frozen experiment,
  record every candidate, and report failures as prominently as survivors.
- **Refinement:** identify whether the change belongs to entry, lifecycle,
  context, or score; link the preceding evidence and count it in the search.
- **AI, genetic, game-theoretic, HFT/arbitrage, or advanced portfolio work:**
  apply the sixth-edition method addendum and record its complete search,
  execution, adaptation, and risk assumptions.
- **Existing-framework calibration from prior reports:** route to
  `framework-calibration` instead of treating it as a new candidate family.

## Decision boundary

Research may decide `REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`. It never
decides `ACTIVE`, authorizes a live trade, or promotes a per-asset result into a
portfolio pass. Activation is a separate governed decision after sufficient
sealed and prospective evidence.

## Additive strategy-research/5 implementation

The v5 implementation is exposed by `node tools/strategy-research-v5.mjs` (and
the same command names through `strategy-research-next.mjs`). It preserves all
v1--v4 records and schemas as immutable read-compatible history. `generate
--method GENETIC` remains fail-closed; only `search-genetic` runs an adaptive
NSGA-II evaluator. Authoritative search also requires a persistent canonical
exposure-head path; a process-local ledger is test-only evidence.

The v5 chromosome contract freezes typed continuous, ordered-discrete,
categorical, and structural genes, population history, operators, parents,
seeds, hard constraints, direct-neighbour confirmation, a simple baseline, and
an append-only family exposure HEAD. Defaults are population 48, maximum 20
generations, minimum 10 generations, five-generation no-new-Pareto-signature
stopping, 0.90 crossover, and `1/gene_count` mutation. Three seeds are
independent search exposure and never reduce cumulative K. A missing/stale/
competing HEAD fails closed; genesis is explicit and one-time.

The v5 data planner freezes the latest five completed years for the eight
required crypto assets and declares Binance spot, USD-M perpetual, and available
dated-future series. It writes a resumable public-data plan, not fabricated
rows; raw data remains gitignored. The opportunity envelope commits the full
execution universe before outcomes, including 1-minute bars for every eligible
asset/window and maximum 30-day lifecycle.

Authoritative v5 evaluation derives `features -> signal intent -> labels ->
execution fills -> trades -> metrics -> stresses -> portfolio -> WFO`. Feature,
label, and execution rows are physically and hash separated. Labels cannot
enter predicates; missing/mismatched labels, fills, funding, expiry, margin,
liquidation, marks, or costs fail closed. It emits market-wide episode vectors
with internal zeros. The v5 WFO contract freezes eight quarterly outer folds,
a 30-day purge, seven-day embargo, training-only 18-month recency weighting,
and unweighted OOS. Its public runner remains `REJECTED` until every fold-level
statistical artifact is authoritatively retained and hash-bound; partial or
caller-supplied evidence cannot emit `SHADOW`.

`overfit-audit` fails closed unless p20, cumulative max-statistic, search-
adjusted expectancy, DSR/PBO, independent episodes, recent/earlier blocks,
positive years/folds, connected plateau/neighbours, three-seed stability,
stress, null/permutation/shift/baseline, and portfolio controls pass. V5 only
returns `REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`; prospective custody and an
offline activation root remain external deployment prerequisites.
