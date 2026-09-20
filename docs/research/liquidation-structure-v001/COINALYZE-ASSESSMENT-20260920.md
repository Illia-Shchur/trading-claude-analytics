# Coinalyze assessment — 2026-09-20

**Decision: promising daily-context source; not a five-year replacement for the frozen intraday forced-liquidation input. Actual per-contract historical coverage is unverified pending API authentication.** This is an additive audit; it does not alter the v001 precommit, specification or freeze manifest.

## Verified provider contract

The [official API documentation](https://api.coinalyze.net/v1/doc/) confirms a free API requiring an `api_key`, preferably supplied in a header. Intraday series retain roughly 1,500–2,000 observations; daily records are not deleted. The endpoint is `/v1/liquidation-history`; the request field is **`symbols`**, plural. Timestamps are seconds and the history response contains `t`, `l`, and `s`. Market identity must be discovered through `/future-markets`, rather than inferred from chart labels. Daily retention does not promise five years for every symbol.

Derived retention estimates, assuming continuous regular bars:

| Resolution | 1,500 observations | 2,000 observations |
|---|---:|---:|
| 1 hour | 62.5 days | 83.3 days |
| 4 hours | 250 days | 333.3 days |
| 12 hours | 750 days | 1,000 days |

The [BTC chart](https://coinalyze.net/bitcoin/liquidations/) describes an aggregation across coin- and stablecoin-margined contracts, with contract-level selection. It also discloses Binance sampling and changes in Bybit reporting coverage. Aggregated liquidation totals are not synonymous with complete Binance USDT forced flow. A changing venue basket can create jumps unrelated to economic stress.

## Direct access check

`COINALYZE_API_KEY` was absent from the current process environment. A direct request to `https://api.coinalyze.net/v1/future-markets` returned HTTP 401, `Invalid/Missing API key`. The receipt is `.research-run/liquidation-structure-v001/feasibility/coinalyze/market-access.json`. No secret was printed, stored, guessed or borrowed from website requests. No authenticated historical rows were obtained.

Public chart presence for BTC/ETH/SOL does not establish an uninterrupted five-year series for those assets or AAVE. The AAVE page failed through the web reader; that alone is not evidence of missing provider coverage.

## Fit to the requested strategy

The original event requires liquidation observations within a particular 4h or rolling 72h window and a 90-day same-duration history. A daily sum cannot reveal the intraday path or divide the total causally among those windows. Interpolating it into 4h bars is unsupported. Joining a day's eventual total to earlier hourly decisions is look-ahead bias.

A separate **completed-daily-stress** version could use a completed day's observed long/short liquidation activity, wait until the provider's actual availability cutoff, and then use Binance 4h structure and 1h entries. That retains the owner's execution rhythm but changes event detection and delays some trades. Even a 72h event must use aligned completed days or separately sourced intraday values; three daily sums cannot represent arbitrary rolling 72h windows.

The API response schema does not, by itself, establish historical receive time, publication delay, outage completeness or revision vintages. Proposed bar-close lagging is a disclosed assumption until independently justified. Never label a receipt captured today as evidence the value was available at its historical bar timestamp.

## Qualification before integration

With a locally configured free API key:

1. Resolve and freeze exchange, symbol, collateral and denomination metadata for BTC/ETH/SOL/AAVE USDT perpetuals. Start with Binance-specific contracts; a market-wide basket requires its own historical composition policy.
2. Request bounded daily history for the frozen window and warmup. Record first/last timestamps, expected versus actual day counts, missing/duplicate days and long/short units for every contract. No outcome evaluation.
3. Check timestamp/day-boundary semantics and whether an incomplete daily bar can appear. Closed-bar filtering must precede any feature join.
4. Capture raw response bytes and hashes with sanitized requests; keep credentials out of URLs, receipts, logs and exceptions. Respect documented rate limits and `Retry-After`.
5. Compare overlapping independently captured liquidation aggregates at their actual supported resolution. Do not interpret differences as alpha.
6. Bind the qualified source to a new version if the daily-stress approach is selected. Unknown or absent rows remain missing, not zero.

If qualification succeeds, integration belongs in the Java public-data acquisition adapter, source registry and causal feature layer. It needs tests for daily availability, partial bars, symbol/units confusion, gaps, duplicates, auth failures, retries, secret redaction and refused intraday reconstruction. The separate 60-day lifecycle, multi-tranche margin and WFO-purge work remains necessary; Coinalyze does not fix those engine contracts.

No production-code change is justified yet by the user's conditional instruction “If it works for us”: the original five-year intraday input is not supported by this retention policy, and daily coverage requires authentication plus an explicit strategy-version choice. Useful next work is source qualification, not weakening existing guards or marking the provider PIT-safe from documentation alone.
