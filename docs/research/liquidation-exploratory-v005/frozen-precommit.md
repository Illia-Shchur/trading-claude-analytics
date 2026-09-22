# Strategy premise precommit: liquidation-exploratory-v005

Stage: CORE_PREMISE  
Immutable SHA-256: a7f65d2ee299c565887ee2e82ae888338d3eefd9ed8d7865241a7fdcaf3c7260

## Core premise

- Phenomenon: After observed liquidation deleveraging, a confirmed break and retest of newly formed post-shock H4 structure may identify delayed continuation or reversal opportunities that a pre-shock-boundary retest misses.
- Mechanism: Margin-constrained forced closers transfer risk urgently. Subsequent two-sided trading may form observable local swing structure before a delayed recovery or continuing repricing. Confirmation and patient retests seek that delayed adjustment; daily aggregation may already consume it.
- Direction/expression: Long and short selected symmetrically by confirmed post-shock swing-level breaks; relation to original shock determines continuation or reversal. / Nine frozen BinanceUSDT isolated perpetuals; fixed sequential rules and daily price-derived context, historical DEVELOPMENT only.
- Holding horizon: {"min":0,"max":60,"unit":"days","clock":"From first fill; never reset by additions; stops may close immediately"}
- Persistence/crowding decay: Forced execution and temporarily constrained market-maker inventories can outlast instantaneous arbitrage; whether enough delay remains after hourly confirmation is the main empirical question. / Faster absorption, improved arbitrage or crowded reclaim/pullback entries can consume the effect before confirmation and make net expectancy nonpositive.

## Falsifier

- Test: Fixed unchanged-event cohort: compare revisedstructure entry to v004baseline, then sequential lifecycle and add-one/leave-one-out context. Report all profiles; no outcome-based selection.
- Null: No positive cost-adjusted incremental expectancy from eachdeclared change after sharedshock dependence; apparent entrycoverage gain may be only more losing entries.
- Rejection thresholds: {"max_familywise_p_value":0.05,"minimum_bootstrap_p20_net_expectancy_r":0,"minimum_bootstrap_p20_incremental_expectancy_r":0,"minimum_effective_independent_episodes":30,"minimum_completed_positions_per_branch":20,"minimum_positive_outer_folds":5,"outer_folds":8,"maximum_portfolio_drawdown_pct":30}

## Universe and sequencing

- Tradable universe: CRYPTO_ONLY (spot and crypto derivatives only)
- Non-crypto inputs: context-only; never PnL, holdings, validation markets, or candidate instruments
- Stage order: CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE
- Composite score: deferred to a later incremental test; absent from CORE_PREMISE
