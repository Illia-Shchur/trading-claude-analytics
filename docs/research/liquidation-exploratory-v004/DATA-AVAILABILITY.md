# Nine-asset data availability

Source envelope: August 11, 2022 through September 19, 2026 inclusive. Entries remain eligible November 11, 2022 through July 14, 2026. All data are public/free or accessed with the owner-provided free Coinalyze API key.

| Asset | Hourly price bars | Missing price bars | Daily liquidation dates | Missing daily dates | Missing 5m OI rows |
|---|---:|---:|---:|---:|---:|
| BTC | 36,024 | 0 | 1,501 / 1,501 | 0 | 479 |
| ETH | 36,024 | 0 | 1,501 / 1,501 | 0 | 224 |
| SOL | 36,024 | 0 | 1,501 / 1,501 | 0 | 232 |
| AAVE | 36,024 | 0 | 1,494 / 1,501 | 7 | 223 |
| UNI | 36,024 | 0 | 1,497 / 1,501 | 4 | 211 |
| BNB | 36,024 | 0 | 1,501 / 1,501 | 0 | 210 |
| LINK | 36,024 | 0 | 1,501 / 1,501 | 0 | 210 |
| ZEC | 36,024 | 0 | 1,488 / 1,501 | 13 | 493 |
| TRX | 36,024 | 0 | 1,501 / 1,501 | 0 | 198 |

Hourly bars are complete for all nine assets. Missing daily liquidation values are not replaced by zeros: each can also prevent the following 90-day percentile windows from qualifying. Raw five-minute OI gaps do not all affect H4 endpoints; the executor separately counts the exact endpoints its signal needs. Malformed OI rows are rejected and included in coverage diagnostics rather than fabricated.

14,120 available Binance ZIP archives passed their published checksum checks. One OI archive, ZEC for December 13, 2023, returned HTTP 404; its observations remain missing. Earlier network/DNS errors were retained as failed acquisition attempts and retried; they were not interpreted as missing market history. The final acquisition has zero transport/checksum errors.

Original BTC/ETH/SOL/AAVE normalized hourly/OI bytes, all original daily liquidation rows, and the S&P CSV match v003 exactly. Only UNI/BNB/LINK/ZEC/TRX are added. A fresh Coinalyze pull for all nine assets is retained as audit evidence, but its revised original-four rows are not used.

The source is a retrospective archive vintage. Daily liquidation availability remains the accepted bucket-start +48h research approximation. OI endpoint lag/staleness, S&P additional-session lag, funding exclusion, approximate contract rules and hourly mark/execution proxies are unchanged.

Coinalyze cached response receipts in the acquired frozen bundle used an earlier downloader revision that reset `retrieved_at` on cache reads. For `CACHED` entries that field is inspection time, not verified original HTTP fetch time; original fetch time is unknown. This limitation is disclosed in the independent input-verification receipt. The corrected tool preserves known source fetch times and verifies cached source hashes. Its final invocation checked the immutable freeze without modifying original receipts.

Data-freeze byte SHA-256: `fb8c6ba67633fa20cab615af708f9443c20f0a02750ffc422e662bd35a221804` (28,292 physical file receipts). Input-manifest byte SHA-256: `6838545b3a2cff527994d42871ab94251c805b70b2021f3144d72e6605ed37f2`. Credentials are excluded from frozen inputs, committed files and the durable archive.
