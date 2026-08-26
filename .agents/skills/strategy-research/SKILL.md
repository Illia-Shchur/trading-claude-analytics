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

## Legacy compatibility boundary

V1--v4 schemas, records, commands, and migrated evidence remain immutable and
read-compatible, but they are not the entry point for new research. Use the v5
path below for every new candidate family, including adaptive genetic search.
Never reinterpret a legacy deterministic sample as genetic search, upgrade an
exposed/partial legacy result into sealed evidence, or let a legacy per-asset
pass authorize a portfolio. Unsupported options, nonlinear instruments, HFT,
atomic multi-leg execution, and queue/latency claims remain diagnostic or
fail-closed until a specialized authoritative adapter exists.

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
