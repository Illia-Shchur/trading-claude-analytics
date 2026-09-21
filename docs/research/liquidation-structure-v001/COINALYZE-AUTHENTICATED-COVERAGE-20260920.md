# Authenticated Coinalyze coverage — 2026-09-20

Authentication now succeeds. Exchange discovery identifies Binance as A; all four requested stablecoin-margined USDT perpetual contracts were resolved through the market catalogue. No credential is included in artifacts.

Queried liquidation-history with symbols (plural), convert_to_usd=true, yearly daily requests from 2021-01-01 through 2026-09-19, plus one 4hour request for the same period. All seven returned HTTP 200; 2021 returned an empty array. Dates below describe rows returned, not a universal claim about other venues or private provider archives.

| Interval | Contract | First returned UTC | Last returned UTC | Rows | Missing grid intervals inside returned span |
|---|---|---|---|---:|---:|
| daily | AAVEUSDT_PERP.A | 2022-06-26T00:00:00+00:00 | 2026-09-19T00:00:00+00:00 | 1540 | 7 |
| daily | BTCUSDT_PERP.A | 2022-08-11T00:00:00+00:00 | 2026-09-19T00:00:00+00:00 | 1501 | 0 |
| daily | ETHUSDT_PERP.A | 2022-08-11T00:00:00+00:00 | 2026-09-19T00:00:00+00:00 | 1501 | 0 |
| daily | SOLUSDT_PERP.A | 2022-08-08T00:00:00+00:00 | 2026-09-19T00:00:00+00:00 | 1504 | 0 |
| 4hour | AAVEUSDT_PERP.A | 2025-11-04T04:00:00+00:00 | 2026-09-19T20:00:00+00:00 | 1844 | 75 |
| 4hour | BTCUSDT_PERP.A | 2026-01-12T00:00:00+00:00 | 2026-09-19T20:00:00+00:00 | 1502 | 4 |
| 4hour | ETHUSDT_PERP.A | 2026-01-12T00:00:00+00:00 | 2026-09-19T20:00:00+00:00 | 1502 | 4 |
| 4hour | SOLUSDT_PERP.A | 2026-01-09T08:00:00+00:00 | 2026-09-19T20:00:00+00:00 | 1518 | 4 |

All returned timestamps were unique within each contract/resolution; l/s values were numeric and nonnegative. Missing intervals were not filled with zeros. Sparse zero-event representation versus acquisition gaps remains unresolved. No observed-value trading performance was evaluated.

This sample establishes reachable historical data but does not satisfy the frozen five-year intraday liquidation requirement. The returned Binance daily histories begin in 2022; 4hour histories are much shorter. A shorter daily-stress successor remains a possible research design, not an authorized silent substitution. Historical availability/capture completeness and executor readiness still require qualification.

Raw responses, request parameters and SHA-256 receipts: `.research-run/liquidation-structure-v001/feasibility/coinalyze/history-coverage.json`. This addendum supersedes the prior authentication blocker only; the frozen v001 package is unchanged.
