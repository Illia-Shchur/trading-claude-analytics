# Strategy premise precommit: liquidation-structure-v001

Stage: CORE_PREMISE  
Immutable SHA-256: 1bbeb1e7daf49a2f1bd0f8765e137f7ef3ff7e46fc91547198f3bbf952738130

## Core premise

- Phenomenon: After an extreme directional crypto move with observed forced-order activity and declining base-quantity open interest, price may reclaim its prior range or accept a breakout.
- Mechanism: Margin-constrained holders must close into reduced liquidity. If their pressure dominates, absorption can lead to reversal; if persistent independent order flow dominates, price can accept the new range and continue. The proposed acceptance/reclaim distinction is a falsifiable hypothesis, not evidence of hidden liquidation targets or informed actors.
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
