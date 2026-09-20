# Feasibility and implementation audit — 2026-09-20

**Disposition: BLOCKED for the requested full historical test.** Premise/specification work is complete; the data and executable contracts are not yet ready. No strategy return was computed, no full archive was downloaded, no production code was changed, and no paid service was purchased. The 24-hour limit is a ceiling, not a reason to spend compute on unsupported evidence.

## Free-data checks performed

Machine receipts, URLs, retrieval times and byte hashes are in `.research-run/liquidation-structure-v001/feasibility/`. Checks inspected object availability, listings and column headers; retained market files were not used to select parameters or inspect candidate returns.

| Input | Actual check | Conclusion and remaining work |
|---|---|---|
| Binance USDT perpetual 1h/4h bars | December 2025 monthly objects returned HTTP 200 for BTC, ETH, SOL, AAVE | Free files exist. Complete five-year coverage, checksums, revisions, 1m execution data and gap audit remain unverified. |
| Funding | December 2025 fundingRate objects returned HTTP 200 for all four | Free settlements exist. Reopen every required period and verify intervals, signed rates, zero settlements and historical marks; do not assume fixed eight-hour funding. |
| OI and ratios | Four December 1, 2025 daily metric ZIPs returned HTTP 200; headers contain base OI, USD OI, global account ratio and top-trader ratios | A free historical archive exists even though REST endpoints limit recent history. Source schemas and actual historical availability need verification; file existence is not PIT authority. |
| Earliest listed OI/ratio files | BTC listing begins 2020-09-01; ETH/SOL/AAVE listings begin 2021-12-01 | The requested 2021 calendar-year coverage is already incomplete for three assets in the inspected archive. No later window is silently substituted. |
| Binance liquidations | Four asset prefixes and the entire USD-M daily liquidationSnapshot prefix returned successful, non-truncated empty listings | No historical files available at those checked prefixes. This is not a claim that no free history exists anywhere. |
| CryptoHFTData liquidations | BTC 2026-09-02 12h sample and AAVE 2025-07-01 12h sample both returned HTTP 200 without credentials | A plausible free source exists, but only shorter advertised history. The AAVE response was Zstd; bytes were retained, not treated as parsed/validated rows. Sampling, missing-hour/outage distinction, historical symbol coverage, rate limits and provenance are not qualified. |
| S&P 500 | FRED CSV returned HTTP 200; date/value headers checked | A free daily context proxy exists. Receipt today does not prove when each historical observation was available. Daily context cannot represent real-time US-market reaction within a crypto shock. |
| Isolated margin metadata | Source-code audit only | Effective historical risk brackets, maintenance margin, filters, fees, mark-price triggers and funding collateral behavior are still required. Current exchange metadata cannot silently represent five years of history. |

Source documentation, checked 2026-09-20:

- [Binance public data](https://github.com/binance/binance-public-data): daily/monthly archives, checksums and the possibility of retrospective corrections.
- [Binance market data API](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data): funding settlements and recent-history constraints for OI/ratio REST paths. The archive must be assessed separately from the API limit.
- [CryptoHFTData liquidation dataset](https://www.cryptohftdata.com/datasets/crypto-liquidation-data): advertised history starts June 28, 2025; unauthenticated rate-limited access; collector receive time; files only for hours with published events. Empty object availability alone cannot distinguish an outage from no events.
- [Tardis free-access rules](https://docs.tardis.dev/api/http-api-reference): first day of each month is free; this is not continuous history for arbitrary cascades.
- [Tardis source limitations](https://docs.tardis.dev/faq/data): Binance forced-order collection is sampled; since April 27, 2021 the stream is capped at one snapshot per second. Buying a copy cannot restore events Binance never broadcast. This strategy therefore measures observed forced-flow activity, not true complete liquidation totals.
- [FRED SP500](https://fred.stlouisfed.org/series/SP500): daily market-close series; used only as a proposed context proxy.

**Paid-data conclusion:** do not buy anything yet. Continuous older liquidation history with collector timestamps and outage records is the likely paid-data candidate if the fixed five-year window remains essential. The free shorter-history provider deserves qualification first. A purchase would not itself solve feed sampling, missing 2021 OI, the executor gaps, or the small number of independent 60-day episodes. No price or completeness guarantee has been assumed.

## Current-code findings

Inspected both local HEAD `17bd5e9` and fetched `origin/main` `4385b39`. The data, lifecycle, statistical and precommit files cited below are byte-identical across those revisions. Local main was preserved: earlier permission to alter it had been declined. The audit does not claim the entire working checkout is synchronized.

| Required capability | Evidence in source | Needed change or qualification |
|---|---|---|
| 60-day opportunity hydration | `StrategyResearchAuthoritativeV5.java:1105–1107` rejects lifecycle beyond 30 days | Add a versioned 60-day contract and hydrate every eligible window to full maturity. Preserve legacy hashes and limits. |
| Longer execution metadata | `StrategyResearchDataV5.java:4903` limits metadata lifecycle to 30 days | Bind fees, contracts, marks and costs through the longer final lifecycle. |
| Mixed 4h/1h decisions | `StrategyResearchDataV5.java:2306` requires exact completed 4h boundaries | Add separate structure/decision clocks and causal as-of joins; 1h archive existence alone does not make this executor ready. |
| Multi-tranche isolated positions | `TradeLifecycleV5.java:237–334` derives a single entry/quantity; partial exits exist | Demonstrate or implement one coherent position state across additions, weighted entry, risk reservations, leverage changes, funding and common stops. Three independent trades are not equivalent. |
| Perpetual metadata | `StrategyResearchDataV5.java` metadata builder is explicitly a spot execution-policy bridge | Build/qualify derivative-specific physical receipts. Do not project spot USER_BOUND assumptions onto perpetuals. |
| Derivative liquidation | `TradeLifecycleV5.java:271–280` requires isolated mode, leverage and a bound liquidation price | Primitive support exists; dynamic liquidation after additions/funding needs physical validation, not a guessed fixed price. |
| WFO dependence | `StrategyStatisticalV5.java:92–94`, `:947`, `:1709–1721`, `:6447` use/fix 30-day purge and lifecycle defaults | Version the fold construction/validation, use actual outcome overlap and the proposed >=67-day event purge, then verify joint resampling. Merely changing holding days leaks labels across folds. |
| Four-asset scope | Current protocol specifies eight required assets | Owner explicitly selected four; use a versioned four-asset research contract. Additional symbols may not become traded or validation assets just to satisfy old defaults. |
| New input authority | Public/archive series require source registry and physical custody | Add/qualify the chosen liquidation/metric/context recipes. A downloaded file or a lag assumption alone must not pass PIT gates. |

Required tests before execution include: no unclosed 4h/1h predictors; pivot confirmation delayed by two right-hand bars; no same-bar retrospective fill; all three stages obey aggregate risk; leverage changes alter collateral without creating cash; funding charged on exact live quantity; liquidation after funding debits; common stop and unchanged clock; day-60 boundary; blackout and gap behavior; overlapping fold labels removed; missing source intervals cannot turn into zeros; identical event aliases share cumulative exposure.

These are an implementation plan, not a claim that those changes have been made. The user-authorized current deliverable was premise/specification, feasibility and required tooling changes.

## Why historical evaluation has not started

The [strategy-research skill](../../../.agents/skills/strategy-research/SKILL.md), Mandatory Stage 1, says: “Reject or redesign the idea before testing if ... the required data cannot satisfy its PIT contract.” Its testing requirements also say to “preserve all source/content hashes and fail closed on missing evidence.”

Here, observed liquidations are load-bearing and full-window, time-of-availability-qualified coverage has not been established. The 60-day, three-stage execution contract is also unsupported by the audited path. Therefore this package records **BLOCKED**, not a substitute OI-only backtest, a 30-day approximation, a profitable result or a rejection of the economic hypothesis.

A continuation can qualify the free shorter-history provider and historical OI provenance without viewing returns. If sufficient data can only support a shorter period, that is a material precommit amendment, not an automatic fallback. Preserve the current version. If suitable older history is only paid, present verified coverage and price before asking the owner to change the free-only constraint.
