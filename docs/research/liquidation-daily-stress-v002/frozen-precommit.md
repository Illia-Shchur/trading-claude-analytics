# Strategy premise precommit: liquidation-daily-stress-v002

Stage: CORE_PREMISE  
Immutable SHA-256: cfae45fac7678a913610c37c156b044ed733e3ecd5e45673b01e412f8eaca904

## Core premise

- Phenomenon: Completed daily observed Binance forced-liquidation stress identifies an already known deleveraging episode. Subsequent 4h price acceptance or reclaim may distinguish continuation from reversal after conservative publication lag.
- Mechanism: Margin-constrained participants close urgently. Some effects may persist after a stress day has completed and its daily aggregate is available. Subsequent confirmed structure can distinguish persistent repricing from temporary dislocation. Daily aggregation does not recover intraday cascade timing; the surviving delayed edge, if any, must be tested.
- Direction/expression: Conditional long and short: continuation follows the extreme move after acceptance; reversal opposes it after reclaim; ambiguous structure means no trade. / Binance USDT linear perpetuals on BTC, ETH, SOL and AAVE only; isolated margin; three risk-budgeted entries in later lifecycle stage.
- Holding horizon: {"min":0,"max":60,"unit":"days","clock":"From first fill; never reset by additions; stops may close immediately"}
- Persistence/crowding decay: Forced execution and temporarily constrained market-maker inventories can outlast instantaneous arbitrage; whether enough delay remains after hourly confirmation is the main empirical question. / Faster absorption, improved arbitrage or crowded reclaim/pullback entries can consume the effect before confirmation and make net expectancy nonpositive.

## Falsifier

- Test: Score-free one-entry model using fixed structural routing, evaluated against always-continue and always-reverse controls on the same eligible confirmed decisions. No macro or staging in the core test. Separate forced-flow contribution diagnostic compares with predeclared price-plus-OI-only event selection; it never becomes a replacement liquidation strategy without a new version.
- Null: Routing has no positive incremental net expectancy over the better unconditional direction control after shared event/time-block correction.
- Rejection thresholds: {"max_familywise_p_value":0.05,"minimum_bootstrap_p20_net_expectancy_r":0,"minimum_bootstrap_p20_incremental_expectancy_r":0,"minimum_effective_independent_episodes":30,"minimum_completed_positions_per_branch":20,"minimum_positive_outer_folds":5,"outer_folds":8,"maximum_portfolio_drawdown_pct":30}

## Universe and sequencing

- Tradable universe: CRYPTO_ONLY (spot and crypto derivatives only)
- Non-crypto inputs: context-only; never PnL, holdings, validation markets, or candidate instruments
- Stage order: CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE
- Composite score: deferred to a later incremental test; absent from CORE_PREMISE
