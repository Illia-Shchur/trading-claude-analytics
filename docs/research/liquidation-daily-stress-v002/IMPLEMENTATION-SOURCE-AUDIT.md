# Public archive probes during implementation review

Checked 2026-09-20. These are bounded source-feasibility probes, not a full historical qualification or a strategy backtest. No candidate returns were inspected.

Binance public daily `futures/um/daily/metrics/{symbol}/` archives exist for BTCUSDT, ETHUSDT, SOLUSDT and AAVEUSDT on 2022-08-11, 2023-01-01 and 2026-09-19. Each probed file has 288 rows. Columns distinguish `sum_open_interest` from `sum_open_interest_value`; optional positioning ratios are also present. The 2026-09-19 files are unsorted but contain 288 unique, contiguous five-minute timestamps from 00:00 through 23:55. Sort and validate timestamps; file order is not time order. Some early optional ratio fields are empty.

For BTCUSDT on 2023-01-01, both the trade `klines/1m` and `markPriceKlines/1m` daily files contain 1,440 rows. Trade-bar base volume can support the declared capacity model. Mark-bar volume is zero and cannot substitute for traded volume.

The BTCUSDT monthly `fundingRate` archive for 2023-01 has 93 records with `calc_time`, `funding_interval_hours` and `last_funding_rate`. Preserve actual settlement timestamps and intervals: timestamps can include millisecond offsets. This file does not contain a settlement mark; obtaining the exact associated mark or explicitly qualifying a contemporaneous-mark approximation remains necessary.

The eight boundary metrics files and three execution/funding probes matched their published SHA-256 checksums. The four 2023 metrics probes have locally recorded hashes but were not independently compared to published checksums in this review. Raw ZIPs, URLs, hashes, headers and row summaries are retained under gitignored `.research-run/liquidation-daily-stress-v002/implementation-review/archive-probes/` in `summary.json` and `boundary-and-execution-summary.json`.

Representative sources:

- [BTC metrics, source-window start](https://data.binance.vision/data/futures/um/daily/metrics/BTCUSDT/BTCUSDT-metrics-2022-08-11.zip)
- [AAVE metrics, last source day](https://data.binance.vision/data/futures/um/daily/metrics/AAVEUSDT/AAVEUSDT-metrics-2026-09-19.zip)
- [BTC trade bars](https://data.binance.vision/data/futures/um/daily/klines/BTCUSDT/1m/BTCUSDT-1m-2023-01-01.zip)
- [BTC mark bars](https://data.binance.vision/data/futures/um/daily/markPriceKlines/BTCUSDT/1m/BTCUSDT-1m-2023-01-01.zip)
- [BTC funding settlements](https://data.binance.vision/data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2023-01.zip)
- [Binance archive documentation and updates](https://github.com/binance/binance-public-data#updates)

**Conclusion:** free historical observations exist for several missing series. Their full-window coverage, historical availability, revisions and historically effective execution metadata remain unqualified. A checksum proves consistency with a downloaded archive version, not that the same values were observable at a past decision. Do not label these inputs generally unavailable merely because full acquisition has not yet run; equally, do not label them point-in-time verified because these probes succeeded.

## Funding mechanics reference

The [Binance funding FAQ](https://www.binance.com/en/support/faq/detail/360033525031), updated 2026-03-06, describes funding debits using available futures balance first, then position margin when that balance is insufficient. The latter can change liquidation risk. It also describes settlement transaction timing variation of up to 15 seconds. These support a disclosed simulation policy and immediate maintenance recheck, not a new strategy funding veto. The current page does not establish those mechanics for every earlier date; exact historical execution ordering remains unverified.

## Macro session calendar review

The extra-session macro delay requires actual equity sessions, including early closes, rather than a weekday approximation. The [NYSE 2024–2026 calendar announcement](https://ir.theice.com/press/news-details/2023/NYSE-Group-Announces-2024-2025-and-2026-Holiday-and-Early-Closings-Calendar/default.aspx) places Juneteenth on June 19 (with applicable observation), and lists early closes separately. The [January 9, 2025 closure announcement](https://ir.theice.com/press/news-details/2024/The-New-York-Stock-Exchange-Will-Close-Markets-on-January-9-to-Honor-the-Passing-of-Former-President-Jimmy-Carter-on-National-Day-of-Mourning/default.aspx) adds a special session exception. The [NYSE calendar](https://www.nyse.com/trade/hours-calendars) also explicitly distinguishes Saturday New Year's Day from the usual preceding-Friday holiday convention. Calendar assumptions must be versioned and tested; these sources do not establish historical S&P value vintages.

## Retained-acquisition qualification cross-check

Independent read-only review reopened all seven referenced source files and checked their byte hashes. The retained manifest has 5,997 normalized daily rows and all four Binance USDT perpetual mappings. Zero liquidation-side values are valid observations; missing calendar days remain distinct from zero.

The packaged CLI created and successfully reopened the qualification receipt with the following nine series rows. The parent independently checked the retained receipt, its coverage counts and all seven AAVE missing dates. These states describe this retained acquisition, not the availability of every public archive.

| Series | Required | Qualification |
|---|---|---|
| Daily liquidations | Yes | PROXY_DISCLOSED |
| Base-quantity open interest | Yes | UNAVAILABLE |
| 4h prices | Yes | UNAVAILABLE |
| 1h prices | Yes | UNAVAILABLE |
| 1m trades and marks | Yes | UNAVAILABLE |
| Funding settlements | Yes | UNAVAILABLE |
| Contract and execution metadata | Yes | UNKNOWN |
| S&P 500 context | No, context variant only | UNKNOWN |
| Positioning ratios | No, diagnostic only | UNAVAILABLE |

No required input family is omitted. Full-window qualification remains false, with zero verified historical-vintage inputs. The retained daily data can support the daily-stress diagnostic; the acquisition alone cannot support execution replay or authoritative evidence. A separate physical development manifest must bind and reopen the execution inputs before any development replay.

The direct packaged CLI returned `BLOCKED_FOR_AUTHORITATIVE_RESEARCH` with zero verified inputs, and `liquidation-input-verify` returned `PHYSICAL_BYTES_REOPENED_PROVENANCE_UNRESOLVED`. The immutable local receipt is `.research-run/liquidation-daily-stress-v002/input-qualification.json`; raw sources and this machine audit remain gitignored. The packaged frozen profile also validated with a 60-day lifecycle and `authoritative_wfo_permitted=false`. Production source hashes still match the reviewed clean-build snapshot.
