# Acquired historical inputs — v003

Checked September 21, 2026, before strategy outcomes. All files cover the fixed source window August 11, 2022 through September 19, 2026 unless noted.

| Asset | Hourly price bars | Five-minute base OI observations | Missing OI slots | OI coverage |
|---|---:|---:|---:|---:|
| BTC | 36,024 / 36,024 | 431,809 / 432,288 | 479 | 99.889% |
| ETH | 36,024 / 36,024 | 432,064 / 432,288 | 224 | 99.948% |
| SOL | 36,024 / 36,024 | 432,056 / 432,288 | 232 | 99.946% |
| AAVE | 36,024 / 36,024 | 432,065 / 432,288 | 223 | 99.948% |

Hourly prices have no missing bars. OI gaps include absent archive rows and rejected invalid/nonpositive base-OI values; SOL also has two rejected off-grid timestamps. Every missing interval is recorded in the retained coverage file. Invalid optional positioning fields do not invalidate valid base OI. Affected OI endpoint windows are excluded; missing observations are never filled as zero or interpolated.

All 6,276 Binance ZIPs matched their published SHA-256 checksums. This includes 6,004 daily metrics files and 272 monthly/daily hourly-price files. Monthly metrics were unavailable, so daily metrics were acquired. The source vintage is retrospective: matching checksums proves the downloaded version, not historical first publication.

The retained Coinalyze input contains 5,997 daily liquidation rows: 1,501 each for BTC, ETH and SOL, and 1,494 for AAVE. Its seven AAVE missing dates remain missing. The original 90-day contiguous-lookback policy excludes affected liquidation windows. The owner accepted this daily source for the exploratory study; its modeled 48-hour availability remains an assumption.

FRED supplies 1,037 nonmissing S&P 500 daily closes from August 1, 2022 through September 17, 2026. The context adapter uses actual NYSE sessions and the predeclared additional-session lag. Funding and minute trade/mark data are deliberately outside this preliminary run; approximate contract metadata is bound by the frozen policy.

## Reproduce and verify

Fresh acquisition requires the retained v002 Coinalyze acquisition and FRED CSV/receipt under `.research-run/liquidation-daily-stress-v002/`; these private local research inputs are not distributed in Git. Restore those retained sources before acquiring from a fresh checkout. Run `python3 tools/acquire_liquidation_exploratory_v003.py`. Acquisition is resumable before freeze. Once `data-freeze.json` exists, this command reopens every bound input and verifies its bytes without rewriting files. Source parsing and custody fixtures run with `python3 -m unittest tools.test_acquire_liquidation_exploratory_v003`.

The parent independently reran freeze verification and all four source-tool tests successfully. The input manifest, detailed coverage, raw sources, published checksum sidecars and normalized CSVs are retained under `.research-run/liquidation-exploratory-v003/`, outside Git.

Input freeze file SHA-256: `e7854346247910b3c6ed63273f87bee470b1ecbc8f61e6adb75e84af0b6316db`.

Coverage file SHA-256: `4225572d661ac6fd84ce6f018266f494cb264b4f3ee4fa7bc25ead86b627bb96`.

Sources: [Binance public archives](https://github.com/binance/binance-public-data), [FRED S&P 500](https://fred.stlouisfed.org/series/SP500).

A durable duplicate of the frozen inputs, policies, three executable versions and both completed run records is retained in the main Desktop workspace at `.research-run/liquidation-exploratory-v003/`. All 12,569 data-freeze entries were reopened and hash-verified after copying, as were the executable and result files. This archive is ignored by Git and is separate from the temporary implementation worktree.
