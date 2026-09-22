# Entry-rule audit and nine-asset historical replay

**The added universe produces eight trades instead of five, but the main finding is a premise mismatch in breadth: the implementation waits for a narrow retest of a frozen pre-shock boundary after delayed confirmation.** It does not yet test the broader idea of building into newly confirmed post-shock price structure. No trading thresholds were changed for this audit.

UNI, BNB, LINK, ZEC and TRX now join BTC, ETH, SOL and AAVE. All three frozen variants ran: one entry, staged without macro, and staged with macro. Their results are identical because no second tranche qualified. This is **DEVELOPMENT historical research**, not forward testing or evidence sufficient for promotion.

## Window and performance

Hourly source prices cover August 11, 2022 through September 19, 2026. The entry-decision window is November 11, 2022 through July 14, 2026 inclusive, with later data reserved to finish positions under the unchanged 60-day ceiling. This is roughly 3 years 8 months of entry eligibility, not two years. There is no requirement to hold a trade for 60 days.

| Metric | Original four | Expanded nine |
|---|---:|---:|
| Completed positions | 5 | 8 |
| Ending equity | $22,291.47 | $21,856.58 |
| Return before funding | 11.46% | 9.28% |
| Simulated event-path maximum drawdown | 5.19% | 6.23% |
| Return with doubled fees/slippage | 8.37% | 5.61% |
| Market-wide shock groups | 38 | 52 |
| Holding/dependence components | 4 | 5 |

All results start from $20,000, include modeled fees/slippage and exclude funding. Drawdown uses the simulated event-price path, not minute mark prices. Doubled-cost sizing is recomputed, so its drawdown is not guaranteed to rise monotonically. Added symbols affect the shared equity and risk budget; their PnL cannot simply be added to the old four-asset total. The separate original-four rerun passes all 110 numeric-equivalence checks against v003.

## Where opportunities disappear

Counts below use the modeled-availability decision window and count each asset-day once even if both liquidation sides are stressed.

| Stage | Original four | Expanded nine |
|---|---:|---:|
| Liquidation-stress asset-days | 676 | 1,407 |
| Qualified price/OI events | 88 | 179 |
| Suppressed because asset already occupied | 15 | 29 |
| Admitted setups | 73 | 150 |
| Setups with an H1 confirmation | 7 | 12 |
| Execution attempts, including retries | 10 | 16 |
| Filled positions | 5 | 8 |

For nine assets, **1,228 of 1,407 stress dates (87.3%) fail price/OI geometry**. Most fail to combine the prescribed price displacement with a 5% OI contraction; five pass one geometry candidate but fail its selected prior-range-break requirement. The largest H4 candle is tested; another candle is not substituted when it fails.

Of 150 admitted setups, 115 expire without confirmation, 27 invalidate their selected branch, and eight become positions. These are final dispositions, so confirmed-but-unfilled setups remain within expiry/cancellation counts. There are 137 continuations and 13 reversals. All eight rejected execution attempts fail the maximum-chase-distance cap; attempts can repeat within a setup.

Repeated hourly checks show 8,868 first failures to touch the entry zone, 40 failures to close on the correct side, and 145 failures to break the preceding hour's extreme. These are repeated checks, not 9,053 different missed trades. Two additional checks are suppressed by an already pending intent. Overlapping all-failed counters must not be added to these first-failure counts.

At branch confirmation, 134/137 continuations and 11/13 reversals are already outside the ±0.25 ATR entry zone. The median absolute distance to the frozen boundary is 4.15 ATR for continuations and 1.40 ATR for reversals. This attribution uses only price at confirmation, not later returns. It explains why requiring a return to the original boundary is restrictive; it does not establish that a looser entry would be profitable.

| Asset | Stress dates | Qualified events | Admitted setups | Confirmed setups | Trades |
|---|---:|---:|---:|---:|---:|
| BTC | 168 | 28 | 23 | 3 | 3 |
| ETH | 190 | 27 | 22 | 1 | 0 |
| SOL | 167 | 15 | 13 | 0 | 0 |
| AAVE | 151 | 18 | 15 | 3 | 2 |
| UNI | 148 | 14 | 12 | 1 | 1 |
| BNB | 145 | 21 | 18 | 1 | 0 |
| LINK | 150 | 21 | 19 | 2 | 1 |
| ZEC | 130 | 14 | 12 | 0 | 0 |
| TRX | 158 | 21 | 16 | 1 | 1 |

## Trades and staging

All eight positions are short continuations and close through stops, including profitable trailing stops. UNI, LINK and TRX add one trade each; BNB and ZEC add none: BNB has one confirmed setup rejected by the chase cap, while ZEC has no H1-confirmed setup. There are no long or reversal fills.

| Asset | First fill, UTC | Exit, UTC | Net PnL before funding |
|---|---|---|---:|
| TRX | 2023-06-12 13:00 | 2023-06-12 18:00 | $-137.90 |
| BTC | 2024-03-17 23:00 | 2024-03-18 06:00 | $-198.14 |
| AAVE | 2024-08-07 02:00 | 2024-08-08 03:00 | $12.41 |
| BTC | 2024-08-07 13:00 | 2024-08-08 02:00 | $-126.79 |
| UNI | 2024-08-08 09:00 | 2024-08-08 13:00 | $-195.39 |
| AAVE | 2025-09-24 15:00 | 2025-09-26 17:00 | $1,180.79 |
| LINK | 2025-09-24 15:00 | 2025-09-24 18:00 | $-52.94 |
| BTC | 2025-10-14 00:00 | 2025-10-19 10:00 | $1,374.56 |

Six positions close without an eligible strict post-fill five-H4-bar pivot; one has no favorable H4 bar after entry; one forms a pivot but its trailing stop invalidates the frozen addition zone. No second tranche reaches the macro gate. Identical variants therefore provide no test of whether macro helps or whether staging improves returns.

## Fixed response diagnostics

These measure the opposite-shock price response from first modeled event availability for all 179 qualified events, clustered into 52 shared market shocks. They do not require an executable entry and are not portfolio returns. Intervals use the frozen 10,000 cluster bootstrap draws and seed; they are not proof that the groups are independent.

| Horizon | Mean opposite-shock return | Cluster-bootstrap 95% interval |
|---|---:|---:|
| 1 day(s) | 0.66% | -0.11% to 1.39% |
| 3 day(s) | 0.64% | -0.59% to 2.24% |
| 7 day(s) | 1.22% | -0.25% to 2.83% |

All three intervals cross zero. The eight actual positions collapse to only five holding/dependence groups. More assets increased coverage but did not resolve the small effective trading sample.

## Premise fidelity and next decision

The audit supports revisiting the **definition of the entry structure**, before changing thresholds based on profits. The current rules combine a daily liquidation signal usable at bucket start +48h, two H4 confirmation closes, and a retest of the old range boundary. A confirmed post-shock swing/pullback structure would be a different entry contract and must be specified and frozen as a successor before evaluating its returns. No such replacement was tested here.

The strict post-fill pivot timing and permanent invalidation of its frozen zone also limit staging. The initial stop uses three H1 bars plus a buffer, which is narrower in concept than a broad multi-day structure stop; the 60-day ceiling does not change that. These choices should be resolved from intended market behaviour, not the fact that this sample happened to make money.

One unresolved specification ambiguity remains: reversal midpoint touches before the current confirmation bar are not latched. The original wording may imply they should be. This audit preserves current trading behavior and records the issue for a separately tested correction.

## Evidence and limitations

All nine assets have 36,024 hourly price bars with no gaps. Missing daily liquidations remain missing; the 90-day lookback compounds their eligibility impact. There are 59 missing H4 OI endpoints across the source envelope. Daily availability, retrospective archive vintages, approximate contract metadata, hourly execution/mark proxies and excluded funding remain accepted exploratory limitations. The original HTTP fetch time for cached added-asset Coinalyze responses is unknown; the bundle's cache inspection timestamp must not be described as that fetch time.

The original-four replay passes 110 equivalence checks. Exact decimal reconstruction reconciles all six nine-asset account simulations with zero difference. Independent source and actual-trace reviews passed. Final affected Java tests, CLI/compatibility checks and acquisition tests passed; changed-code coverage is 90.75% lines and 80.00% branches. One pre-existing conditional test remained skipped. The initial clean run found a test-message assertion mismatch; it was corrected and affected tests rerun, followed by a successful reactor build. The exact sequence is recorded in `verification.json`; freeze hashes and outcomes are retained in `results.json` and `EXPOSURE.json`.

The durable bundle includes full traces, source receipts, every attempted run and immutable executable 004 (SHA-256 `6d5bd7f256c73875f4c1647b9e5cbeea89c49d502d1018170422c211b5da30f6`). The first expanded attempt completed internal simulations but failed on a legacy diagnostics policy guard before emitting results; the policy-only repair and rerun are disclosed. No outcomes were erased, no holdout was reset, and no promotion is claimed.
