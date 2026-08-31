# Strategy premise precommit: fk-deleveraging-absorption

Stage: CORE_PREMISE  
Immutable SHA-256: 96aafcc9d007a775d0df44c5a391bfc89043b6aee457b988f25019533c5f2ea8

## Core premise

- Phenomenon: After an abrupt, high-volume downside shock in liquid crypto spot markets, forced and liquidity-insensitive selling can temporarily push price below the level supported by patient spot demand; once the forced flow abates, price may partially rebound over the following several days.
- Mechanism: Leveraged long liquidations, collateral constraints, stop cascades, and risk-limit reductions make some holders sell immediately rather than at a reservation price. Patient unlevered spot buyers can supply liquidity only after the completed shock bar makes the dislocation observable. The hypothesized edge is compensation for absorbing urgent inventory, not a forecast that every large decline mean-reverts.
- Direction/expression: long / Unlevered long positions in liquid Binance crypto spot pairs, entered no earlier than the first tradable instant after the qualifying 4-hour bar closes; no derivatives, short positions, or non-crypto instruments in the core baseline.
- Holding horizon: {"min":2,"max":10,"unit":"days"}
- Persistence/crowding decay: Crypto trades continuously, leverage is structurally available, collateral is marked rapidly, and risk reductions cluster during volatility spikes. Capital willing to absorb those flows is finite because the shock is uncertain in real time and inventory must be held through adverse selection. / The edge should decay if leverage falls materially, liquidation execution becomes smoother, patient capital pre-positions around obvious triggers, or many participants buy the same completed-bar pattern quickly enough that the rebound is captured before a feasible next-bar entry.

## Falsifier

- Test: Predeclare one fixed completed-4h-bar shock rule without parameter search, enter at the next feasible bar under conservative spot costs, and compare net R over the fixed holding policy with matched non-event downside bars using market-wide episode blocks.
- Null: The dependence-adjusted net expectancy is less than or equal to zero, or is not better than the matched downside-control expectancy.
- Rejection thresholds: {"maximum_statistic_p_value":0.1,"minimum_bootstrap_p20_expectancy_r":0.05,"minimum_effective_independent_episode_count":30}

## Universe and sequencing

- Tradable universe: CRYPTO_ONLY (spot and crypto derivatives only)
- Non-crypto inputs: context-only; never PnL, holdings, validation markets, or candidate instruments
- Stage order: CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE -> INDEPENDENT_CONTEXT -> COMPOSITE_SCORE
- Composite score: deferred to a later incremental test; absent from CORE_PREMISE
