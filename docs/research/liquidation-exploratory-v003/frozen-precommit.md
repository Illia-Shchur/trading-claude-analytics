# Strategy premise precommit: liquidation-exploratory-v003

Stage: CORE_PREMISE  
Immutable SHA-256: 5daba2a82d44dc753d6af9eb123d0a30bcc8e20ec5e793c5d499fbb9f69d1ff4

## Core premise

- Phenomenon: Completed daily observed Binance forced-liquidation stress identifies an already known deleveraging episode. Subsequent 4h price acceptance or reclaim may distinguish continuation from reversal after conservative publication lag.
- Mechanism: Margin-constrained participants close urgently. Some effects may persist after a stress day has completed and its daily aggregate is available. Subsequent confirmed structure can distinguish persistent repricing from temporary dislocation. Daily aggregation does not recover intraday cascade timing; the surviving delayed edge, if any, must be tested.
- Direction/expression: Conditional long and short: continuation follows the extreme move after acceptance; reversal opposes it after reclaim; ambiguous structure means no trade. / Binance USDT linear perpetuals on BTC, ETH, SOL and AAVE only; isolated margin; three risk-budgeted entries in later lifecycle stage.
- Holding horizon: {"min":0,"max":60,"unit":"days","clock":"From first fill; never reset by additions; stops may close immediately"}
- Persistence/crowding decay: Forced execution and temporarily constrained market-maker inventories can outlast instantaneous arbitrage; whether enough delay remains after hourly confirmation is the main empirical question. / Faster absorption, improved arbitrage or crowded reclaim/pullback entries can consume the effect before confirmation and make net expectancy nonpositive.

## Falsifier

- Test: Exploratory characterization of fixed core/staged/staged-macro variants and1/3/7day post-availability responses. Parent always-continue/reverse and price+OI controls DEFERRED_NOT_TESTED; no claim routing beats unconditional controls.
- Null: Descriptive null: no directional post-availability response and no positive pre-funding expectancy. Formal parent routing superiority null remains untested.
- Rejection thresholds: {"max_familywise_p_value":0.05,"minimum_bootstrap_p20_net_expectancy_r":0,"minimum_bootstrap_p20_incremental_expectancy_r":0,"minimum_effective_independent_episodes":30,"minimum_completed_positions_per_branch":20,"minimum_positive_outer_folds":5,"outer_folds":8,"maximum_portfolio_drawdown_pct":30}

## Universe and sequencing

- Tradable universe: CRYPTO_ONLY (spot and crypto derivatives only)
- Non-crypto inputs: context-only; never PnL, holdings, validation markets, or candidate instruments
- Stage order: CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE
- Composite score: deferred to a later incremental test; absent from CORE_PREMISE
