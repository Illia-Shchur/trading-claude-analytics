# Premise-first crypto strategy research/2

This protocol governs new strategy families. It separates idea formation,
selection, confirmation, portfolio feasibility, and activation so a promising
chart pattern cannot silently become a live rule.

The tradable universe is crypto infrastructure only: crypto spot, perpetuals,
dated futures, options, basis, funding, carry, and other explicitly described
crypto derivative expressions. Every derivative declares venue, collateral,
and funding/carry treatment. Equities, ETFs, rates, FX, commodities, bonds,
cash indexes, and similar markets may be point-in-time-safe context or
correlation inputs only. They cannot be candidate instruments, holdings,
validation assets, portfolio PnL, or trade recommendations.

## 1. Create the idea before viewing strategy outcomes

Copy `templates/strategy-precommit-1.template.json` and complete its one-page
premise. Do this before constructing a parameter grid or reading candidate
returns. The precommit must state:

1. The observable phenomenon and the economic or behavioural mechanism.
2. The forced actor, liquidity/edge provider, and intended edge consumer.
3. Why the edge should persist and how crowding or market adaptation destroys it.
4. Exact direction, crypto expression, and holding horizon.
5. Expected signal-frequency, win-rate, average-win, and average-loss ranges.
6. Regimes where the mechanism should work and where it should fail.
7. A concrete invalidation mechanism, not merely a losing backtest.
8. Every required input, evidence family, availability timestamp, and PIT rule.
9. Independent replication groups and the limits of cross-asset independence.
10. The simplest falsifying test, named null, and thresholds chosen in advance.

The expectation ranges are predictions to grade, not gates to reverse-engineer.
An idea whose expected frequency is too low for its proposed evidence window is
redesigned or rejected before search. The core premise contains no composite
score, score threshold, or score weight.

Freeze the filled premise and review its deterministic Markdown:

```sh
node tools/strategy-research.mjs precommit \
  --input premise.json \
  --root strategy-research
```

The JSON and Markdown are immutable after freezing. A mechanism change creates
a new precommit; it does not rewrite the old one.

## 2. Verify data feasibility before outcomes

Each series declares asset, asset class, timeframe, context-only status, and
PIT/completed-bar treatment. Each input declares its evidence family, role,
and when it actually became observable. Context-only non-crypto series must
map to a declared `CONTEXT` input, be explicitly non-tradable, and use a
verified PIT series or a disclosed proxy.

Before outcome testing, verify:

- completed-bar as-of joins cannot see a later release or revised observation;
- feature and outcome tables are separated so labels cannot enter predictors;
- actual fees, slippage, funding settlements, and derivative carry are available;
- duplicate encodings of one evidence family do not receive multiple votes;
- missingness, venue gaps, and feature coverage are measurable per asset;
- crypto derivatives have instrument, venue, collateral, and funding metadata;
- no non-crypto series is present in the candidate or validation universe.

A contract can record a proxy, but a proxy cannot be relabelled as PIT truth.
Unknown or incomplete fields fail promotion rather than being silently filled.

### Sixth-edition method addenda

Modern methods do not relax the evidence contract. AI/ML research precommits
targets, training cutoffs, baselines, feature availability, hyperparameter
budgets, seeds, retraining, drift, and nested chronological evaluation. Genetic
or evolutionary optimization counts every behaviourally distinct evaluated
configuration in the search budget and freezes its fitness and constraints.
Game-theoretic ideas specify actors, actions, payoffs, feedback, and observable
adaptation. HFT/arbitrage claims require data capable of representing
synchronized legs, latency, queues/partial fills, costs, capacity, collateral,
venue failure, and unwind risk; bar-level spread diagnostics are not executable
trades.

Risk profiling includes tails, loss runs, extreme events, leverage/ruin,
liquidity, signal similarity, correlation concentration, and expected-versus-
realized behavior. For complete method-specific requirements, follow the
[`strategy-research` sixth-edition reference](../.agents/skills/strategy-research/references/sixth-edition-methods.md).

## 3. Develop one layer at a time

Research proceeds in this immutable order:

`CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE`

Each later definition and experiment references the predecessor stage, run,
and content hash.

- `CORE_PREMISE`: score-free mechanism and its minimal falsifier.
- `ENTRY_TIMING`: a small predeclared set of price/flow confirmations.
- `RISK_LIFECYCLE`: stop, target, partial, timeout, and sizing alternatives.
- `INDEPENDENT_CONTEXT`: add one independent evidence family at a time, then
  run leave-one-context-out attribution.
- `COMPOSITE_SCORE`: test a score only after a score-free baseline survives;
  require the baseline hash and exclude or block overlapping evidence.

If a context input repeats a setup evidence family, an explicit overlap
disclosure must block promotion. Sorting the input list cannot bypass this
rule. Use `ablations` to create deterministic core, add-one, and leave-one-out
plans.

## 4. Generate candidates with a declared hypothesis budget

Candidate grids are scientific perturbations of the frozen mechanism, not an
open-ended search for profitable thresholds. Before generation, declare:

- parameter names, allowed values, and why each range is plausible;
- hypothesis family and evidence-family IDs;
- stage and ablation role;
- required crypto assets and evidence phase;
- robust-statistic, plateau, stress, and portfolio gates.

Grid keys and values are expanded deterministically. The registry preserves
declared `K`, removes exact behavioural duplicates, stores effective `K`, and
hashes both sets. Cosmetic candidate IDs do not create new hypotheses. If the
researcher inspects an unrecorded variation, it belongs in the hypothesis
budget even if it is later discarded.

A single frozen baseline may declare `NO_SELECTION_SEARCH` with an empty grid.
Every actual search must pass plateau analysis; it cannot call itself a
baseline to avoid neighbour evidence.

```sh
node tools/strategy-research.mjs generate \
  --precommit strategy-research/precommits/<family>.json \
  --root strategy-research
```

## 5. Separate evidence phases

Use the evidence label that describes what the researcher truly knows:

- `DEVELOPMENT`: idea formation and diagnostics; never confirmation.
- `WALK_FORWARD_OOS`: repeated chronological train/purge/test folds.
- `EXPOSED_CONFIRMATION`: nominal confirmation data was viewed or searched.
- `SEALED_CONFIRMATION`: one-time evaluation of a frozen candidate and hashes.
- `PROSPECTIVE_LIVE`: forward signals recorded after freezing.

Confirmation and prospective evidence consume a frozen, lineage-bound
one-candidate-per-asset selection and behavioral-alias/K contract; their new
outcomes never select a replacement. A missing or mismatched contract fails
closed. Walk-forward selection runs every candidate on TRAIN only in each
declared fold, then evaluates only the TRAIN winner on TEST; purge and
embargo are enforced as timestamp gaps using the declared bar duration.

Full-sample diagnostics that include a nominal holdout expose it. They must not
coexist with a claim that the window remains untouched. Evidence labels and
content hashes are immutable; no tool can upgrade an exposed phase.

## 6. Evaluate robustness, not just average return

Retain the `sqrt(2 log K / n)` search-adjusted expectancy only as a conservative
heuristic diagnostic. Selection also requires:

- shared event/time-block resampling across every candidate and crypto asset;
- a centred candidate-set max-statistic conditional resampling p-value;
- block-bootstrap interval and p20 expectancy;
- effective independent episode count;
- per-asset and portfolio metrics, folds/years/regimes, costs, drawdown, and tails.

One shared sequence of market-episode draws preserves cross-candidate and
cross-asset dependence. Candidate/episode absences are explicit zeros in this
conditional estimand. The result is not SPA and is not a distribution-free
guarantee. Episode IDs therefore require a stable market-wide definition.

For a searched grid, neighbours differ by one adjacent declared coordinate.
The finalist must meet predeclared direct-neighbour profitability and connected
profitable-plateau size. The connected plateau traverses adjacent profitable
neighbours recursively; a remote isolated optimum does not count.

## 7. Stress execution using declared fields

Every experiment freezes all five scenarios and their minimum observations and
expectancy gates:

- fee/slippage multiplier using each trade's recorded `fee_r` and `slippage_r`;
- funding/carry multiplier using recorded `funding_debit_r`;
- adverse gap using a declared incremental `debit_r`;
- liquidity/capacity using notional, available liquidity, and participation cap;
- venue outage using declared venue/time blackout windows.

Missing model inputs fail the affected scenario. The stress result carries the
suite hash, so another set of assumptions cannot be substituted at recording
time. These are deterministic sensitivity tests, not an order-book simulator.

## 8. Test the crypto portfolio independently

Per-asset evidence never implies a portfolio pass. The separate simulator
orders signals deterministically, closes positions at their declared exit
times, releases capacity, and applies:

- total, per-asset, risk-cluster, and venue concurrency caps;
- long/short conflict policy;
- gross and net directional exposure limits;
- collateral, available-equity, and leverage limits;
- crypto derivative metadata and net trade economics;
- predeclared portfolio trade-count, drawdown, and PnL gates.

`net_pnl` or `net_r * risk_amount` is account currency; raw R is never added to
cash equity. Base costs and funding must already be included in net PnL, while
funding attribution is reported without double subtraction. The compact
simulator reports realized close-to-close drawdown; intratrade mark-to-market
drawdown needs a separately supplied equity path and may not be inferred.

The result is bound to the experiment's portfolio-acceptance hash. Non-crypto
signals are rejected even when supplied beside valid context features.

## 9. Record every candidate and monitor frozen survivors

Store metrics for every effective candidate and per asset. Store compact trades
for selected/finalist/frozen candidates. A v2 run embeds its metrics, trades,
stress, portfolio, decisions, source hashes, and evidence phase; generated
indexes expose these by strategy, experiment, asset, candidate, phase, and
status.

Useful deterministic commands:

```sh
node tools/strategy-research.mjs stats --input candidate-returns.json
node tools/strategy-research.mjs plateau --experiment experiment.json --candidates candidates.json --metrics metrics.json --candidate <id>
node tools/strategy-research.mjs ablations --input context-plan.json
node tools/strategy-research.mjs stress --trades trades.json --suite stress-suite.json
node tools/strategy-research.mjs portfolio --signals signals.json --policy portfolio-policy.json
node tools/strategy-research.mjs run --root strategy-research --experiment experiment.json --metrics metrics.json --trades trades.json --stress stress.json --portfolio portfolio.json
node tools/strategy-research.mjs rebuild-index --root strategy-research
node tools/strategy-research.mjs validate --root strategy-research
```

Prospective monitoring compares the frozen frequency, win-rate, expectancy,
loss-run, slippage, feature-coverage, drift, and regime profile with genuinely
forward evidence. Frequency units such as `per month` require an explicit
monitoring start/end and are normalized to that interval; a raw trade count is
not compared with a rate. Multi-asset monitoring is assessed independently per asset.
It may return `REJECTED`, `SHADOW`, or `CANDIDATE_REVIEW`, never `ACTIVE`.

### 9.1 Authoritative execution contract

An ordinary swing research run is authoritative only when it is evaluated from
a frozen v2 experiment/candidate set, a verified feature-store hash and a
`strategy-data-manifest/1`. The manifest records source hashes, row counts,
time bounds, availability-time policy, coverage/gaps, PIT and revision status,
and venue/instrument provenance. Development may disclose proxies or revised
history; walk-forward, confirmation and prospective evidence fail closed on
unknown/revised/non-PIT load-bearing data.

The local evaluator is `swing-engine/1`. It hashes its source bytes,
package-lock/environment, config, seeds, timezone/bar convention,
same-bar-collision policy and cost/funding assumptions. It computes canonical
trades and metrics for every effective candidate × required asset, including
zero-trade rows, and recomputes stress and portfolio outputs from those exact
trades. Reconciliation hashes bind the candidate trade set, all trades,
selected trades, derived metrics, stress and portfolio results. External JSON
may be imported for history only as `EXTERNAL_EXPOSED` evidence and is forever
`REJECTED`/`SHADOW`.

Evaluation chronology is explicit: train/development windows, chronological
folds, purge/embargo, test and holdout/prospective boundaries, selection
objective/tie-break, seeds and timezone. `WALK_FORWARD_OOS` requires real fold
artifacts; local `SEALED_CONFIRMATION` is rejected because a string/hash is
not custody of unseen data. Prospective evidence is bound to a frozen start,
candidate/data/executor hashes and a monitoring window; pre-start, duplicate,
mutated or substituted evidence fails.

Behavioural K is computed after execution from pre-outcome signal intent
(asset, decision time, direction, setup identity and lifecycle intent), while
declared and syntactic K and every alias remain recorded. Parameter topology
types are explicit: only continuous/ordered-discrete coordinates form plateau
neighbours; categorical and structural alternatives never become fake contour
adjacency. Risk diagnostics include loss runs, tails/expected shortfall,
drawdown duration/time-under-water, moments when sample-size permits, and
deterministic leverage/ruin sensitivity.

The authoritative portfolio supports crypto spot, linear perpetuals and linear
dated futures with entry/exit prices, quantity/multiplier, collateral,
leverage/margin, fees, slippage, funding settlements and a union mark path.
The union path carries a last completed mark only within a declared maximum
mark-gap contract; entry and exit still require exact eligible marks. Cross
margin uses aggregate account maintenance, and unsupported margin modes fail
closed rather than being approximated.
Missing leveraged marks, liquidation/margin breach, options, multi-leg
basis/carry, HFT/arbitrage, queue/latency and atomic-spread claims fail closed
or remain diagnostic/SHADOW until a specialized adapter exists.

## 10. Decision boundary

`REJECTED` means the declared candidate or evidence failed. `SHADOW` means it
may continue gathering evidence but cannot authorize a trade.
`CANDIDATE_REVIEW` means the frozen research evidence is ready for the separate
human/governance activation process. No `strategy-research/2` definition,
experiment, run, per-asset result, portfolio result, or prospective monitor can
set `ACTIVE` or authorize a live trade.

The v1 registry and historical imports remain read-compatible and retain their
original evidence labels. New research uses v2; old evidence is never rewritten
to appear stronger than it was.
