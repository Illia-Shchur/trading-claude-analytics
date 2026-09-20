# Liquidation structure — v001 research specification

Date: 2026-09-20. State: **premise frozen, execution BLOCKED**. This is a proposed first version, chosen before strategy returns were examined. Numeric choices below are ex ante research assumptions, not calibrated facts or additional user preferences. No grid, backtest, parameter selection, monitoring, or order has been run.

## One-page premise

**Mechanism.** Leveraged participants can be forced to close regardless of valuation. Their urgent orders meet temporarily constrained liquidity. Once that pressure subsides, a return into the old range may indicate exhaustion; sustained acceptance outside it may indicate persistent directional demand/supply. The test is whether this distinction retains useful predictive information after waiting for confirmation and paying costs. Falling open interest alone cannot identify involuntary liquidation, and price acceptance cannot prove informed trading.

**Expression.** Conditional long/short Binance USDT perpetuals on BTC, ETH, SOL and AAVE. Reversal opposes the shock after a reclaim. Continuation follows it after acceptance and a pullback. Ambiguous or incomplete evidence produces no trade. Non-crypto markets are context only.

**Predictions, not promises.** Expect 1–4 independent portfolio-wide episodes per month, 35–55% winning positions, average winners 1.3–2.8R and average losers 0.7–1.3R. These broad predictions can imply losing configurations and are not evidence of positive expectancy. Correlated symbols do not multiply independent sample size. Continuation should have a longer right tail; recovery targets should limit reversal upside. Prediction failures must be reported, not rewritten.

**Works/fails.** The proposed edge should be strongest when forced flow is temporary and post-event structure is stable, or persistent repricing becomes distinguishable after the event. It should fail in alternating breakouts, low liquidity, insolvency, venue impairment, or when waiting consumes the whole edge. Faster arbitrage and crowding can remove it.

**Falsifier.** A one-entry, score-free routed baseline must have positive net expectancy and improve over the better of always-continuation and always-reversal controls at the same eligible confirmed decision times, after shared dependence-aware correction. Missing data means untestable; weak statistical power means inconclusive. Neither is a profitable-strategy claim. Staging and macro must prove incremental value later.

## Owner constraints

- $20,000 initial equity; USDT accounted at $1 for the base simulation, with stablecoin impairment disclosed as an unmodelled/base limitation and stressed separately before any deployment claim.
- BTC, ETH, SOL, AAVE only; isolated Binance USDT perpetuals; both directions. One net position per asset at a time. Opposite directions across different assets are allowed, without hedging credit.
- Completed 4h structure and 1h decisions; lower-resolution execution cannot be inferred from those decision candles. Use 1m trade and mark paths for fills, stops and liquidation checks.
- Maximum 60 elapsed days from first fill, never reset by additions. No separate stall timeout after entry.
- Up to 100% of current account equity committed as isolated margin, also constrained by free collateral and actual costs. This is a ceiling, not required deployment.
- Planned position risk capped at 5%; tranche budgets 1%, 1.5%, 2.5%. Combined planned risk at most 10%, no offset for opposing positions. Gaps/liquidation can exceed stop risk.
- Leverage 2x for stages 1 and 2; up to 3x at stage 3. This exact middle-stage choice is an implementation proposal. Increasing leverage changes collateral, not permission to exceed quantity/risk limits.
- Macro has no first-entry veto; tranche 2 needs neutral/supportive macro; tranche 3 needs supportive macro. Deterioration blocks additions only.
- Structural stops remain structural. No heatmap-based widening. Funding is charged/credited, not used as a discretionary exit or carry veto in this new family. Existing Fallen Knives/Flying Rocket guardrails are untouched.
- Historical research only; no paid data, cloud spend, monitoring or orders. Maximum 24 hours of local research compute; stop earlier at a feasibility gate.

## Fixed causal definitions

### 1. Event detection — no outcome data

Use UTC intervals, half-open bars, and decisions only after all required observations are available. Wilder ATR(14) is a volatility measurement, not a voting indicator. At least 140 completed 4h bars seed/warm its recursive value. Freeze ATR from the last bar strictly before the event window.

Two event types are evaluated at completed 4h boundaries:

- **Fast shock:** absolute open-to-close change across one 4h bar is at least 2 times the pre-window ATR.
- **Multi-day unwind:** absolute change from the open of the first to the close of the eighteenth 4h bar is at least 4 times the pre-window ATR (72h window).

Both also require base-quantity open interest to decline at least 5% from the window start to end, and the sampled forced-liquidation notional on the shock side to exceed the 95th percentile of comparable same-duration historical windows ending in the previous 90 days. SELL forced orders corroborate a down shock; BUY forced orders corroborate an up shock. Use observed filled quantity and execution price, deduplicate cumulative fills/order updates, and retain sampling semantics. Neither candle volume nor USD OI decline substitutes for liquidations. A price decline mechanically reduces USD OI; base quantity avoids that confound.

The liquidation percentile uses strictly prior windows, minimum 80% verified capture coverage, and a stable provider/feed regime. Missing capture intervals invalidate the event; they are not zero liquidation volume. These are engineering coverage floors, not claims that the data already satisfy them.

Use OI snapshots available before each boundary, at least one 5m interval lagged, no more than 10m stale. Time-label semantics must be verified per source. No forward interpolation. For a fast and multi-day event on the same asset at the same timestamp, retain both flags but create one setup, with fast-event geometry as the deterministic tie-break. While a setup is armed or a position is open, suppress new setups for that asset. After setup expiry/exit, require a new independently qualifying event.

### 2. Frozen reference range and branch

At event detection, freeze the high/low of the six completed 4h bars immediately preceding the event window. For a down shock, the broken boundary is that range's low; for an up shock, its high. Require a close beyond that boundary at detection; otherwise the extreme observation remains descriptive and creates no trade setup.

After detection, wait up to 72h for two consecutive completed 4h closes:

- Beyond the broken boundary in the shock direction: **continuation** armed.
- Back inside the prior range: **reversal** armed, opposite the shock.

Only bars after detection count. Freeze the first qualifying branch. No switching branches within a setup; if subsequent 4h structure invalidates it before entry, cancel it. A reversal cancelled by a close outside the broken boundary must wait for a new event. A continuation cancelled by a close back inside the range also waits for a new event. No branch within 72h means expiry, not an open-ended search for a profitable entry.

### 3. Zones, hourly confirmation, and initial stop

The first entry zone is the frozen broken boundary plus/minus 0.25 pre-event ATR. After the branch is armed, wait at most another 72h for a **new** completed 1h bar to intersect this zone and close on the trade side of the level. For a long, it must also close above the immediately previous completed 1h high; for a short, below that previous low. If it does not, remain waiting. A boundary invalidation cancels the setup. Never fill a touch retrospectively at the zone's best price.

Execute a confirmed order at the next 1m open strictly after the hourly decision plus declared observation/processing delay, using a frozen market-order fill and slippage model. If the price is more than 0.5 pre-event ATR from the zone centre, cancel rather than chase. Halt/missing next-open data means no synthetic fill.

Initial stop: below the minimum low of the three completed 1h bars ending at confirmation for longs; above their maximum high for shorts, with a 0.10 pre-event ATR buffer. This is the confirmed local entry structure, not the full liquidation extreme. Invalid/non-adverse stops reject the entry. No minimum hold overrides a stop.

### 4. Exit by branch

- **Reversal:** frozen midpoint of the pre-event six-bar range. If already reached, behind the fill, or less than 1 planned R away after entry costs, skip the entry. Target is not extended later. Exit all remaining quantity there, at the stop, or at day 60.
- **Continuation:** no fixed profit target. After entry, ratchet the stop at each completed 4h close to the adverse extreme of the last three completed 4h bars, buffered by 0.10 pre-event ATR, only if it tightens. It becomes active after that close, never retrospectively within its source bar. Exit all quantity at the active stop or day 60.
- A stop-market gap fills at the first available worse executable price. Target exits are reduce-only with conservative touch/fill assumptions. A 1m bar that could hit both stop and target uses stop first; mark-price liquidation dominates both when applicable.

### 5. Additions — tested only after the core survives

After each fill, require at least one additional completed 4h bar in the favourable direction. Freeze the newest confirmed 4h pivot for the next zone: a pivot low/high uses two bars to its left and two to its right and becomes known only when the second right-hand bar closes. For a long use a pivot low above the active stop; for a short use a pivot high below it. The pivot must form after the preceding fill. Its zone is plus/minus 0.25 pre-event ATR. Lock one zone per prospective tranche; do not keep replacing it until one works.

Wait for a later 1h touch/reclaim and previous-hour breakout as for tranche 1. An addition can occur at a better or worse price; price improvement alone is insufficient. Check macro and all capital limits at that decision. If macro blocks it, require a new hourly confirmation after eligibility returns. Require at least one distinct hour between tranches; never fill multiple stages from the same bar. Reversal additions need at least 1R to the unchanged recovery target and are blocked after that target has been reached.

All tranches share the existing stop and branch exit; additions never widen that stop. No added tranche extends the day-60 clock. Risk released by a tightened stop cannot increase the frozen tranche allowances or create a fourth tranche.

### 6. Minimal macro proposal — separate later-stage experiment

Use a single transparent **S&P 500 market-behaviour proxy**, not an invented broad macro score. Take its 5-completed-session close-to-close return, with one additional session availability lag and verifiable historical publication timing. Above +0.5% is supportive for longs/opposing for shorts; below -0.5% reverses that classification; between is neutral. Data older than four calendar days or missing provenance is UNKNOWN, which blocks additions rather than masquerading as neutral. US holidays/weekends follow the exchange calendar; never use a partial session's future close.

This explicitly assumes a positive crypto/equity risk relationship. It may fail during decoupling and must beat an otherwise identical staged strategy without the macro restriction. Rolling correlation, rates, dollar, RSI, long/short ratios and funding signals are not extra gates in v001. Funding and account/top-position ratios are retained as descriptive diagnostics; do not interpret account ratios as the market's net long/short notional. Liquidation heatmaps are not required inputs.

### 7. Risk, collateral, and order priority

Freeze each position's reference equity at its first fill. Tranche allowances are 1%, 1.5%, 2.5% of that reference; additions are also limited by 5% of current account equity less existing risk, so losses elsewhere can shrink the remaining allowance. Mark existing risk conservatively as nonnegative loss to the common stop plus estimated close costs, with no negative-risk credit from winning stops. Include already-paid entry fees in lifetime budget checks; report funding separately and include known paid debits in remaining loss headroom. No future funding amount is assumed known.

Across assets, require summed planned stop loss plus estimated close costs <=10% current marked equity. Quantity is rounded down to historical lot steps and limited by risk, free collateral, notional filters and 1% of the last completed 1m traded base-volume capacity. Report when collateral or capacity prevents spending a tranche allowance. Do not borrow future bar volume to size an order.

Place all tranches of an asset into one replayed isolated position with weighted entry, realized fees, signed funding and historical maintenance/liquidation calculations. Simulate 2x/2x/3x at the position level, with explicitly modelled collateral release when leverage changes; no unrequested auto-top-up. Capital checks reserve entry and estimated exit fees/slippage. Margin commitment cannot use unrealized profit that the venue does not make available for transfer. Insufficient funding collateral follows venue mechanics, not an invented funding veto.

For orders with identical timestamps, sort by precommitted asset order BTC, ETH, SOL, AAVE, then tranche number. Stops/liquidations/settlements are processed before new entries at a common timestamp under an explicit venue-ordering contract. Admit the largest quantity within that tranche's budget and remaining capacity, or skip below exchange minimums. Never rebalance existing positions to fund a newer signal.

## Evidence and search discipline

1. **CORE_PREMISE:** one-entry routed rule and two direction controls. Add one price+OI-only event diagnostic to assess whether observed liquidation evidence contributes. Four predeclared evaluated rule variants are exposure, including failed controls; no optimization, no genetic search.
2. **ENTRY_TIMING:** only after core survives; do not tune hourly timing on the held-out outcomes.
3. **RISK_LIFECYCLE:** add the owner's three tranches and 60-day lifecycle with the same core. Baseline already uses the 60-day maximum; the lifecycle experiment isolates staged allocation, not a longer hindsight horizon.
4. **INDEPENDENT_CONTEXT:** compare identical staged allocation with and without the single equity-context gate. Record both. No composite score is planned.

The requested signal window is 2021-01-01 through 2025-12-31, with warmup before it and outcomes through 2026-03-02. Use the first three years for development and eight quarterly outer folds in 2024–2025. Purge by actual outcome overlap, at least 67 days to cover up to six days of post-event confirmation/entry waiting plus 60-day holdings and an execution margin, and embargo seven days. The existing fixed 30-day purge is not acceptable. Shorter source history cannot silently replace this period; an amended version must declare its reduced evidential scope before examining outcomes.

Inference uses common market-time resamples across assets, branches and controls; cluster events within 72h for descriptive episode counting, but account for overlapping 60-day positions with blocks at least 67 days long. Report effective episode count, not just raw trades. Predeclare 10,000 shared bootstrap draws, seed 20260920, p20 net expectancy >0, p20 incremental expectancy >0, and familywise max-statistic p<=0.05 against the predeclared controls. Require >=30 effective episodes, >=20 completed positions per branch, >=60 portfolio trades and >=5 positive outer folds out of 8. These floors do not guarantee power: estimate feasibility before outcomes and label low power honestly.

Report both branches, both shock types, both directions and all four assets, including failures. Core R uses frozen initial-tranche risk; staged R uses the frozen full-position 5% reference risk, with tranche R and dollar PnL also reported so unfilled stages cannot inflate performance. Require positive base net PnL and <=30% marked-equity drawdown as a proposed research gate. Costs, risk and this drawdown gate are assumptions, not extra owner instructions.

Stress doubled execution costs, doubled funding debits without doubling credits, +0.25R adverse gap debits, 1% capacity participation, and uniformly declared post-event outage windows. Every stress requires >=30 observations and positive expectancy; a blackout affecting no trades is not a pass. Preserve tails, drawdown duration, liquidation events, funding attribution, skipped signals, competing entries and all search exposure.

## Freeze and readiness boundary

`precommit-input.json` records the causal contract and constraints; the Java `precommit` command creates immutable JSON/Markdown under `.research-run/liquidation-structure-v001/registry/`. A separate SHA-256 manifest binds this specification, feasibility audit, input and frozen premise. A frozen premise may contain UNKNOWN data status; it is not a production experiment or a readiness certificate.

No candidate generation or outcome evaluation is permitted until the required data and executor gaps in `FEASIBILITY.md` are resolved. A changed mechanism, shorter history, OI-only substitution or altered rule requires a new immutable version. Existing FK/FR policies and historical schemas are not rewritten to accommodate this new family.
