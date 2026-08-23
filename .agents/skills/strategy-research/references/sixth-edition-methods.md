# Sixth-edition method guidance

Use this addendum when a candidate uses AI/machine learning, game theory,
genetic/evolutionary optimization, high-frequency/arbitrage logic, or advanced
risk/portfolio allocation. The ordinary premise-first and crypto-only rules
still apply.

The source scope is Perry J. Kaufman's *Trading Systems and Methods*, Sixth
Edition (Wiley, 2019, ISBN 978-1-119-60535-5). Wiley describes the revision as
adding current-market examples, expanded arbitrage and high-frequency trading,
AI and game-theory approaches, sophisticated risk models, and genetic-algorithm
spreadsheets. Its official contents place these methods beside chapters on
system testing, adding reality, risk control, and portfolio allocation. This
skill interprets those additions conservatively for crypto research; it does
not copy a book strategy or treat a modern algorithm as evidence of an edge.

Official sources:

- [Wiley sixth-edition description](https://uat.store.wiley.com/en-us/trading-systems-and-methods-5th-edition-p-9781119605355)
- [Wiley sixth-edition table of contents](https://catalogimages.wiley.com/images/db/pdf/9781119605355.toc.pdf)
- [Wiley companion site](https://books.wiley.com/titles/9781119605355/)

## AI and machine learning

Define the economic premise independently of the model. "The network predicts
returns" is not a mechanism. Before training, freeze:

- prediction target, label horizon, direction, and actual trade expression;
- decision timestamp and availability of every feature;
- training, purge/embargo, validation, and sealed-test chronology;
- simple economic/rule-based and naive statistical baselines;
- loss/fitness function and its relationship to net trading utility;
- architecture/model families, hyperparameter space, seeds, and total trials;
- class imbalance, missing-data, scaling, and feature-selection treatment;
- retraining frequency, allowed training window, and production versioning;
- calibration, turnover, cost, capacity, and probability-to-position mapping;
- drift tests and the condition that disables or retrains the model.

Use nested chronological selection: tuning occurs only inside each training
window, followed by purged forward evaluation. Never normalize using future
observations, randomly split serial market data, reuse the sealed set for model
selection, or report only the best seed. Count feature-selection variants,
architectures, prompts/agents, seeds used for selection, and hyperparameter
trials in the hypothesis/search record.

Require incremental evidence over the simplest baseline. If model complexity
does not improve robust net expectancy, tail risk, or stability after the full
search penalty, prefer the baseline.

## Game theory and strategic adaptation

A game-theoretic candidate must specify:

- actors and which are constrained, informed, liquidity-seeking, or adversarial;
- each actor's observable actions and information set at the decision time;
- payoff transfer, timing, repeated-game feedback, and equilibrium intuition;
- why the trade remains profitable after other actors learn about it;
- crowding/adaptation measurements and an explicit decay/exit condition.

Test alternative actor explanations that generate the same observable signal.
The candidate survives only if the predicted state-contingent response appears,
not merely because the unconditional backtest is positive. Re-evaluate after
fee changes, venue rules, liquidation mechanics, market-maker participation, or
new arbitrage capital changes the game.

## Genetic and evolutionary optimization

Freeze the chromosome representation, allowed genes/ranges, objective,
constraints, mutation/crossover rules, population size, generations, stopping
rule, seeds, and compute budget. Store the evaluated population history or a
tamper-evident digest sufficient to reconstruct exact effective K.

The hypothesis budget includes every behaviorally distinct configuration whose
fitness was observed—not only finalists, last-generation chromosomes, or the
number of manually named strategies. Multiple seeds are robustness evidence
only when they were not used to choose the most favourable result; otherwise
they add to selection.

Use constraints for risk and operational feasibility rather than hiding them
inside a return-only fitness function. After optimization, require:

- comparison with a simple predeclared baseline;
- stable neighbouring/perturbed solutions rather than a brittle optimum;
- chronological walk-forward evidence with optimization repeated only inside training;
- one frozen candidate entering sealed or genuinely prospective confirmation;
- stress and portfolio tests using the same costs and constraints as selection.

## High-frequency trading and crypto arbitrage

Match data resolution to the claimed edge. OHLC bars cannot validate
millisecond queue position, leg simultaneity, transient spread capture, or
market impact. A candidate needs event/order-book data when those mechanics
determine profitability.

For each leg, declare venue, instrument, clock synchronization, quote/trade
timestamps, order type, queue/fill model, partial-fill behavior, cancel/replace
latency, fees/rebates, price impact, and capacity. For crypto derivatives also
declare funding, basis convergence, collateral haircuts, liquidation, margin
netting, borrow/locate where relevant, settlement, counterparty, and stablecoin
risks.

Simulate asymmetric failure: one leg fills while another does not; venue or API
access fails; withdrawal/transfer is unavailable; price gaps during unwind;
funding or borrow changes; quoted liquidity disappears. Report opportunity
count separately from executable trades. Never translate a frictionless spread
into expected PnL.

## Advanced risk profiling and portfolio allocation

Report risk in the units that govern survival and sizing:

- net return distribution, skew, kurtosis/tail quantiles, expected shortfall;
- maximum and duration of drawdown, time under water, loss-run distribution;
- gap/extreme-event and liquidity/capacity stress;
- leverage, collateral, liquidation distance, and probability-of-ruin scenarios;
- volatility stability and risk contribution by asset, venue, strategy, and factor;
- contemporaneous and stressed correlation, signal overlap, and crypto-beta concentration;
- turnover, funding/carry, and expected-versus-realized execution drift.

Diversification counts distinct risk and signal drivers, not symbol count. BTC,
ETH, and altcoin variants sharing the same crypto beta, venue, flow evidence,
or liquidation episode can be one portfolio exposure. Evaluate simultaneous
positions on one chronological equity path. Optimize allocation only after
individual strategies survive; bind the portfolio objective, constraints, and
capital assumptions before viewing allocation results.

## Applying non-crypto sixth-edition examples

Stocks, ETFs, rates, FX, commodities, and conventional futures examples may
suggest a measurement, risk model, or context feature. Under this workspace's
scope they remain PIT-safe side data only. Replicate the economic mechanism on
crypto spot or crypto derivatives before it becomes a candidate trade. Do not
import non-crypto returns into validation PnL or call them cross-asset trade
replication.
