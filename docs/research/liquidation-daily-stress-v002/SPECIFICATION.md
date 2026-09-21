# Liquidation daily stress v002 — frozen successor specification

Owner authorization: 2026-09-20, “it's okay let's continue with it,” accepting shorter daily history. This is a new immutable version in the same `liquidation-structure` family; it never resets cumulative hypothesis exposure. No candidate returns or price/return-selected parameters have been inspected.

## What changes

Observed liquidation activity is now a **completed daily Binance-specific stress gate**, not an intraday forced-flow trigger. A daily candle starting at UTC midnight t covers [t,t+24h); model it as usable only from t+48h. The extra 24h is a conservative research assumption, not proof of historical publication, latency or revision provenance. No daily total is distributed across hourly bars. No market-wide aggregate substitutes for Binance USDT perpetuals.

Source start is 2022-08-11, the latest first returned day across BTC, ETH, SOL and AAVE. Use 90 prior contiguous calendar-day observations per side and symbol. Nearest-rank 95th percentile means the sorted prior sample's 86th value (one-based); the current observation is excluded. Long- and short-liquidation stress are independent flags: current same-side USD total must be positive and strictly exceed its prior percentile. Missing days invalidate that side's whole lookback window; no forward-fill or zero-fill. Explicit numeric zeros may be observations only if the provider actually returned them. Invalid, duplicate, nonfinite, negative, wrong-symbol or non-midnight observations are errors.

Earliest candidate decision is 2022-11-11T00:00:00Z after warmup and modeled availability. Latest decision is strictly before 2026-07-15T00:00:00Z, leaving time for post-event confirmation, delayed entry and the full 60-day lifecycle within physical execution coverage ending 2026-09-20T00:00:00Z. Raw source data may extend later than the signal cutoff, but must never enter earlier lookbacks.

## Price/flow event and branch proposal

The liquidation flag is only one input, not a trade signal. For the eventual core evaluator, retain two price-event geometries, now evaluated from data already completed within the stress day:

1. Fast geometry: among the stress day's six completed 4h bars, select the largest absolute open-to-close move (earliest bar breaks a tie). Require at least 2 pre-bar Wilder ATR(14), base-quantity OI decline >=5% over that same 4h window, and daily liquidation stress matching that bar's direction (long liquidations for a down move; short liquidations for an up move).
2. Multi-day geometry: the 18 completed 4h bars ending at that stress day's close form an aligned 72h price/OI window. Require absolute displacement >=4 pre-window ATR, OI decline >=5%, and matching stress on the final completed day. This is expressly a final-day liquidation gate, not an invented 72h liquidation aggregate.

If both geometries qualify, preserve both flags but use the fast geometry for frozen reference levels. The six completed 4h bars before the selected geometry define the prior high/low range; the event must close outside it. No setup is armed before daily modeled availability. Branch confirmation uses only 4h bars completing **after** that availability, even if an earlier historical reversal looks attractive. This prevents counting recovery that happened before the signal was usable.

Thereafter apply the preserved v001 structural rules: two 4h closes accepting beyond the range arm continuation; two closes reclaiming the range arm reversal; invalidation cancels the setup. Branch wait <=72h, then entry-zone wait <=72h. The original 1h touch/reclaim plus previous-hour breakout, next-1m execution, structural stop, recovery target, trailing ratchet and no-chase limits remain unchanged. Repeated daily flags cannot open a second setup while that asset is armed/open. Both daily sides may be stressed; price direction and structure select a matching setup or no trade, never two opposite positions in one symbol.

## Preserved owner preferences

- Binance USDT isolated perpetuals, BTC/ETH/SOL/AAVE; long and short, with opposite directions allowed across assets and no risk-netting credit.
- $20,000 starting account; 100% margin ceiling subject to actual available collateral and costs.
- Three entries with maximum account-equity risk 1% / 1.5% / 2.5%, 5% total per position, 10% across the portfolio; first confirmed first served.
- 2x initially, proposed 2x at tranche 2 and up to 3x at tranche 3; leverage never authorizes exceeding risk ceilings.
- Macro affects additions only: neutral/supportive for stage 2, supportive for stage 3. Keep filled tranches if macro deteriorates.
- Structural stops; liquidation heatmaps do not move stops. Stop exits the whole position. Reversal exits at frozen recovery target; continuation trails; both have the same 60-day maximum from first fill.
- Funding paid/received is included using actual settlements and live quantity; no independent funding veto. Free public data only, historical work only, maximum 24 hours local research compute.

The detailed unchanged formulas, equity definitions, capacity/lot rounding, tie breaks and price logic are preserved in [v001 specification](../liquidation-structure-v001/SPECIFICATION.md). This document takes precedence only for daily-event construction and dates. No FK/FR rules are modified.

## Stages and inference

First build/validate the daily liquidation gate and assess data and raw stress-frequency feasibility. This is not generation of an executable trading candidate and supplies no PnL, direction edge, win rate or deployment decision.

Then, only when physical price/OI/execution inputs are qualified, test the score-free one-entry structural router against frozen direction controls. Only a surviving core advances to staged allocation and then the independent S&P 500 context gate. No RSI, composite score, optimizer, genetic search or profitable-window selection is added.

Retain eight quarterly outer windows from 2024-01-01 through 2025-12-31, with development beginning at the revised first usable date and ending before 2024. Purge actual outcome overlap with at least 67 days and retain seven-day embargo. If that shortened training history is inadequate, record INSUFFICIENT_EVIDENCE; do not quietly shorten the purge or use future data. January–July 2026 is a labeled exposed/development extension, not extra untouched confirmation. Use the existing ex ante sample/expectancy/stress/drawdown thresholds; raw daily stress counts do not equal independent tradable episodes, especially with overlapping 60-day holds.

## Source qualification and executable boundary

Coinalyze calendar rows today do not prove historical first availability, complete capture or revision vintages. The daily source remains `PROXY_DISCLOSED`, unfit for authoritative WFO/promotion until those requirements are resolved. Availability t+48h is a model assumption for developmental research only. Rows missing from the archive cannot distinguish quiet days from outages.

The new acquisition and daily preflight commands are additive diagnostic support. They do not weaken existing 30-day lifecycle guards, turn JSON staging into authoritative Parquet, assign a production evaluator identity, or authorize SHADOW/ACTIVE. An execution result still needs the complete derivative metadata, 60-day hydration, 4h/1h decision path, common isolated-position accounting across stages, and matching WFO contracts. A blocked qualification must remain blocked, regardless of attractive stress counts.
