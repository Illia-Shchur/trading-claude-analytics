# Liquidation exploratory backtest — v003

The historical run completed. The frozen strategy returned **11.46% cumulatively after assumed execution costs, before funding**, taking a $20,000 account to **$22,291.47**. It produced only **five trades in four holding-overlap groups**. This is exploratory DEVELOPMENT evidence, insufficient to establish a reliable trading edge or advance the strategy.

## What was tested

BTC, ETH, SOL and AAVE Binance USDT perpetuals, long and short, with 4-hour structure and 1-hour execution. Entries were eligible from November 11, 2022 through July 14, 2026. Source data extends from August 11, 2022 through September 19, 2026. The full sample is now exposed development data; no sealed out-of-sample result is claimed.

Three fixed variants were run without optimization: one entry, staged entries without macro, and staged entries with the S&P gate. All retain the 60-day holding ceiling. The one-entry allocation is 1% of account equity at that position’s first fill, at 2×; the full staged plan remains 1%/1.5%/2.5% risk at 2×/2×/3×, subject to the frozen account limits.

## Portfolio results

| Variant | Final equity | Return | Simulated max drawdown | Trades / tranches |
|---|---:|---:|---:|---:|
| CORE_ONE_ENTRY | $22,291.47 | 11.46% | 5.19% | 5 / 5 |
| STAGED_NO_MACRO | $22,291.47 | 11.46% | 5.19% | 5 / 5 |
| STAGED_MACRO | $22,291.47 | 11.46% | 5.19% | 5 / 5 |

All results include assumed 0.05% fees and 0.05% slippage per side, and exclude funding. Hourly trade OHLC approximates mark prices and execution. Drawdown uses conservative hourly adverse extremes, not an observed synchronized portfolio path.

Doubling both fee and slippage assumptions and recalculating sizing produced **$21,673.57 (+8.37%)**, with **4.72% simulated drawdown**, in every variant. The lower stressed drawdown reflects changed position sizing; it does not mean higher costs improve risk.

The entry funnel was **88 qualified source events → seven confirmed setups → ten stage-one fill attempts → five fills**. Repeated confirmations explain why attempts exceed unique setups. Five attempts were rejected at execution. No second-stage or third-stage intent was produced.

All five fills were **short continuations**: three BTC and two AAVE. ETH, SOL, longs and reversals had no fills. No second or third tranche was entered. Consequently, the equal variant results provide **no evidence about the value of staging or the macro gate**.

## Trade ledger

| Asset | Entry UTC | Exit UTC | Hold | Net PnL before funding |
|---|---|---|---:|---:|
| BTC | 2024-03-17 23:00 | 2024-03-18 06:00 | 7 h | $-199.63 |
| AAVE | 2024-08-07 02:00 | 2024-08-08 03:00 | 25 h | $12.50 |
| BTC | 2024-08-07 13:00 | 2024-08-08 02:00 | 13 h | $-127.68 |
| AAVE | 2025-09-24 15:00 | 2025-09-26 17:00 | 50 h | $1,201.83 |
| BTC | 2025-10-14 00:00 | 2025-10-19 10:00 | 130 h | $1,404.44 |

All exits were structural or trailing stops. No trade reached the 60-day limit. The two largest winners contributed $2,606.27, exceeding the entire $2,291.47 net profit; the other three trades together lost $314.81. The historical result is concentrated in very few outcomes.

Four trades never formed an eligible post-fill H4 pivot before exit. In the longest BTC trade, the pivot formed but was invalidated by a tighter trailing stop before hourly addition confirmation. These are consequences of the frozen confirmation rules, not grounds to silently loosen them after seeing returns.

## Fixed response diagnostics

These are unlevered price moves before all costs, measured from the modeled first observable event time: daily bucket start plus 48 hours, approximately one day after the stress day closes. The diagnostic therefore evaluates a delayed daily-stress signal. They average within each market-shock group, then weight groups equally. They are not executable portfolio returns.

| Horizon | Opposite-shock mean | Descriptive 95% interval | Same-shock mean |
|---|---:|---:|---:|
| 1 day | +0.56% | -0.22% to +1.41% | -0.56% |
| 3 days | +0.43% | -0.90% to +1.69% | -0.43% |
| 7 days | +2.39% | +0.54% to +4.19% | -2.39% |

All three horizons contain **88 events in 38 source-shock groups**, with no missing response paths. The seven-day average points toward reversal of the original shock. Its descriptive interval excludes zero, but these intervals are unadjusted across the tested horizons and do not establish a validated edge. Seven-day response windows may also overlap between the predeclared shock groups, so residual dependence can make those intervals optimistic.

At the first routed entry decision, there were only **seven setups in six groups**. Mean returns in the routed direction were **+0.90%, +2.75% and +5.66%** over 1, 3 and 7 days, respectively. The corresponding descriptive intervals were **−0.88% to +2.72%**, **+0.06% to +6.36%**, and **−1.08% to +16.34%**. This includes confirmed opportunities that did not fill and is especially fragile evidence.

## Dependence and sample size

- The precommitted feature-only rule grouped 88 events into 38 market-wide shocks without using returns. These are not 88 independent observations, and 38 is not asserted to be a proven independent sample size.
- Five actual trades form four holding-overlap groups. The three repeated strategy variants are not additional independent trades: their 15 position records describe the same five historical exposures.
- The 67-day sensitivity has 22 complete calendar blocks; the final 27-day partial block is excluded from its bootstrap. Mean block return was +0.53%, with a descriptive 95% interval of −0.14% to +1.78%. Most blocks were flat.
- Lagged daily portfolio-return correlations were −0.192 at one day and approximately −0.001 at seven and thirty days. Sparse activity limits their interpretation; they are not converted into an invented effective sample size.
- The 67-day purge and seven-day embargo remain separate leakage controls. This full-sample run performs no walk-forward validation.

## Data and limitations

Hourly prices are complete for all four assets. Only 26 required H4 OI boundary snapshots are missing across the full source window; affected setups are skipped. Daily liquidation coverage retains seven missing AAVE dates. S&P context uses FRED closes with the frozen additional-session availability lag. See [data availability](DATA-AVAILABILITY.md).

The liquidation source and its 48-hour availability delay are accepted research approximations. Contract rules and maintenance margin are approximate, mark prices use trade bars, volume capacity uses the prior hour, and funding is excluded. Archive hashes bind the retained vintage, not the information actually published at each historical instant. Deferred direction controls, price/OI controls, exact liquidation/funding/outage stress and sealed walk-forward evaluation remain untested.

## Decision

**DEVELOPMENT / INSUFFICIENT_EVIDENCE.** The requested small-sample research is now possible and has run. The broad shock-response diagnostic merits further investigation, but this strategy version is too selective to validate its conditional branches, staging or macro gate from five trades. No parameters were optimized and no variant is selected as a winner. Any next rule revision must be a separately frozen successor and must carry forward this exposed sample.

## Verification

The reporting-fix rerun completed; all six event-stream hashes and fill/exit records, equity curves, trade counts and numeric response fields matched the earlier completed run exactly. Repository verification passed: the clean research suite completed, the stale CLI help-text expectation was repaired and the remaining reactor resumed successfully. The final focused suite passed 47 tests with zero skips; changed production code achieved **90.58% line coverage and 80.20% branch coverage** against explicit 80% thresholds. One unrelated, pre-existing packaged-executor test was conditionally skipped in the broader suite. See the [verification receipt](verification.json) for exact commands and counts. The first completed run already reconciled all six accounts exactly and passed independent source, completion-digest, trade-record and response-mean checks. See [review](REVIEW.md), [runbook](RUNBOOK.md) and the immutable [policy](exploratory-policy.json).

Final run: `2026-09-21T06-54-44-023959Z-c25a6567-c717-448e-aef3-17a4e12702a5`. Full result SHA-256: `40c9c55f1e349e3b01d5def43a605f0f0dde5c459222dbc3338c581514b5eabe`. The committed [compact results](results.json) retain all variants, all response diagnostics, dependence measures, complete trade records and audit bindings. [Exposure receipt](EXPOSURE.json) retains both completed runs without resetting prior family exposure.
