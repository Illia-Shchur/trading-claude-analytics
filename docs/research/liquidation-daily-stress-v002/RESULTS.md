# Daily stress diagnostic — 2026-09-20

Result: **BLOCKED / DEVELOPMENT**, liquidation gate only. The activated Coinalyze API key worked. The new Java importer acquired 5,997 daily rows and the preflight reopened and verified every retained raw response before calculation. No returns, candidates or trades were calculated. An independent calculation reproduced all four assets’ eligible-day and side-specific stress counts exactly.

| Asset | Daily records | Missing days | Eligible decision days | Blocked days | Long-liquidation stress | Short-liquidation stress |
|---|---:|---:|---:|---:|---:|---:|
| BTC | 1501 | 0 | 1342 | 0 | 101 | 91 |
| ETH | 1501 | 0 | 1342 | 0 | 104 | 100 |
| SOL | 1501 | 0 | 1342 | 0 | 104 | 91 |
| AAVE | 1494 | 7 | 1108 | 234 | 85 | 83 |

Source window: August 11, 2022 through September 19, 2026 inclusive. Decision window: November 11, 2022 through July 14, 2026 inclusive. Final source days after the decision cutoff are retained for coverage, not used as earlier predictors.

AAVE first has a complete usable window on April 3, 2023. Its seven missing observations invalidate 234 candidate decision days; those gaps are not converted to zero liquidation activity. Later gaps can block a new 90-day window.

A long-liquidation stress flag describes liquidated long positions; it is not a recommendation to enter long. Trade direction must later come from the frozen price/OI geometry and structural branch. The two sides can flag the same day, and many assets can share one market-wide episode, so summing flags would overstate independent opportunities.

The archive is Binance-specific and observes the liquidation snapshots that Binance publishes, not every forced liquidation or a market-wide total. Historical first publication and revision vintages remain unknown. The modeled t+48h availability is an explicit retrospective research assumption.

The [machine summary](DIAGNOSTIC-SUMMARY.json) binds the raw acquisition and diagnostic output by content and byte hashes. Full raw files and event-level outputs remain in `.research-run/liquidation-daily-stress-v002/`. The summary is a display projection, not authoritative strategy evidence.

The [remaining research gates](NEXT-STEPS.md) explain why this is not yet a completed 60-day staged backtest.
