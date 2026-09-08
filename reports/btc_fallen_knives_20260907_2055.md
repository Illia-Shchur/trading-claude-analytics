# BTC — Fallen Knives — 2026-09-07 20:55

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | BTC · Fallen Knives |
| Report time | 2026-09-07 20:55 (America/New_York) |
| Verdict | • WAIT — BTC 4/20; gates1/9, V1. No new accumulation. Greed/recovered weekly momentum and insufficient confirmation block every phase. Position-level controls remain DATA_LIMITED. |
| Adjusted score | **4/20** (mechanical 4, raw 4) |
| Confirmation gates | 1/9 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable BTC |
| Deployment | 0% deployed · 100% dry |
| Primary action | **WAIT_NO_ADD** — Score and gate/V floors fail independently; market weakness alone is not an FK unlock. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $79,169.68 | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | Four-source synchronized median.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| All-time high | $126,080 | ✅ AVAILABLE | — | 2025-10-06 | —<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Drawdown from ATH | 37.21% | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | —<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| CLF | $92.33999634 | ✅ AVAILABLE | — | 2026-09-08T00:28:44+00:00 | Live/last available quote; WTI is front-month futures.<br>Sources: [CLF](https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1mo&interval=1d) |
| DJI | 53,414.25 index | ✅ AVAILABLE | — | 2026-09-04T13:30:00+00:00 | Last available Dow index quote.<br>Sources: [DJI](https://query1.finance.yahoo.com/v8/finance/chart/%5EDJI?range=1mo&interval=1d) |
| ADR-5 | $2,389.75 | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | ADR corrected using Sep3-Sep7 five complete Binance sessions; Yahoo partial Sep8 excluded and missing Sep7 replaced.<br>Sources: [binance](https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=100) |
| Brent | $97.06 | ✅ AVAILABLE | — | 2026-09-08 | Yahoo BZ=F (Brent crude); change 7.26%.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Coinbase premium | -0.008% | ✅ AVAILABLE | — | 2026-09-07 | Completed daily USD/USDT-adjusted premium; negative3d FALSE.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Dry-powder yield | 3.76% | ✅ AVAILABLE | — | 2026-09-04 | 13-week T-bill discount-rate opportunity benchmark; not assumed stablecoin APY.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Dxy | 99.18 index | ✅ AVAILABLE | — | 2026-09-08 | Yahoo DX-Y.NYB (US Dollar Index); change -0.26%.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Futures cvd 3d | -$447,002,015.33 | ✅ AVAILABLE | — | 2026-09-08T00:00:00.000Z | Binance aggregate, single venue; completed 4h window. Context only.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Gold | $4,465.8 | ✅ AVAILABLE | — | 2026-09-08 | Yahoo GC=F (COMEX gold front month); change 0.78%. Gold is COMEX futures, not spot.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Long short account ratio | 1.1608 ratio | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | Binance accounts, not position notional; single venue.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Ma200 | $69,814.7 | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | —<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Ma50 | $69,695.51 | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | —<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Move | 73.1 index | ✅ AVAILABLE | — | 2026-09-04 | Yahoo ^MOVE (ICE BofA MOVE Index (bond vol)); change 3%.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Ndx | 26,506.99 index | ✅ AVAILABLE | — | 2026-09-04 | Yahoo ^IXIC (Nasdaq Composite); change 0.4%.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Oi change 3d | -1.959% | ✅ AVAILABLE | — | 2026-09-08T00:00:00.000Z | Binance aggregate, single venue; completed 4h window. Context only.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| 200-week SMA | $64,867.39 | ✅ AVAILABLE | — | 2026-09-08T00:37:01.959Z | —<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Spot cvd 24h | -$36,404,241.06 | ✅ AVAILABLE | — | 2026-09-08T00:00:00.000Z | Binance aggregate, single venue; completed 4h window. Context only.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Spot cvd 3d | -$141,144,619.71 | ✅ AVAILABLE | — | 2026-09-08T00:00:00.000Z | Binance aggregate, single venue; completed 4h window. Context only.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Spx | 7,718.6 index | ✅ AVAILABLE | — | 2026-09-04 | Yahoo ^GSPC (S&P 500); change 0.09%.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Us10y | 4.78% | ✅ AVAILABLE | — | 2026-09-04 | Yahoo ^TNX (US 10y nominal yield (×10 units)); change 1.36%.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Vix | 15.3 index | ✅ AVAILABLE | — | 2026-09-07 | Yahoo ^VIX (CBOE VIX); five-observation delta 2.55%. Yahoo VIX Sep7 holiday label is suspect; descriptive only, no session claim.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |

**Regime:** • Post-rally consolidation; no extreme fear — Active downtrend: No; Bounce age sessions: 35; Bounce pct: 27.23; Gap narrowed 20: Yes; Gap now pct: 0.17; Insufficient: —; Label: Post-rally consolidation; no extreme fear; Low 40s: 62,226.58; Ma200: 69,814.7; Ma200 falling: No; Ma200 slope20 pct: 1.12; Ma50: 69,695.51; Ma50 below ma200: Yes; Price below ma200: No; Rsi14: 63; Rsi14 confidence: ok; Sessions low to high: 31; Structure b: Yes; Trend residual: NO: price above rising200dma, so no bearish trend residual. Lower lows alone would not satisfy both conditions.; Within 3pct of ma200: No; Within 3pct of ma50 from below: No

### Spot reconciliation

**✅ AVAILABLE** — Median of four synchronized CoinGecko/Binance/Coinbase/Kraken quotes; spread 0.041%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko bitcoin | $79,154 | ✅ LIVE | [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Binance BTCUSDT | $79,186.73 | ✅ LIVE | [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Coinbase BTC-USD | $79,174.46 | ✅ LIVE | [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Kraken XBTUSD | $79,164.9 | ✅ LIVE | [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |

> Dispersion is small synchronized disagreement, not a mixed-time spread. Yahoo frozen bar close 79175.07 excluded by kind; age unavailable, never in median.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Correlation | 0.13958087 Pearson daily log-return correlation | ✅ AVAILABLE | MEDIUM | 2026-09-04 | 30 aligned closes / 29 log returns; not price-level correlation. Surcharge OFF if <=0.7; no guessed bonus.<br>Sources: [correlation](https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC) |
| Cpi | 3.4 headline / 2.5 core% | ✅ AVAILABLE | MEDIUM | 2026-08-12 | Latest release; next CPI Sep11 08:30 ET.<br>Sources: [cpi](https://www.bls.gov/news.release/cpi.htm) |
| Etf flows | 3,443.8 USD millions Aug10-Sep4 | ✅ AVAILABLE | MEDIUM | 2026-09-04 | Daily +174.6M; Aug31-Sep4 +986.7M; SepMTD +770.0M. Latest inflow streak 3 sessions. Sep7 US Labor Day closure; no zero-flow invented. YTD total NOT VERIFIED; conflicting window totals are not annual totals. AUM ratio unavailable; positive net flow cannot meet an outflow gate.<br>Sources: [farside](https://farside.co.uk/bitcoin-etf-flow-all-data/), [sosovalue](https://coinpaper.com/35390/crypto-trading-update-bitcoin-etfs-pull-in-987m-as-btc-holds-near-80k) |
| Holder | 1.397 exchange reserves change 30d % | ✅ AVAILABLE | MEDIUM | 2026-09-06 | True LTH data provider-gated: its half holds prior zero; reserves rise, holder0.<br>Sources: [coinmetrics](https://community-api.coinmetrics.io/v4/timeseries/asset-metrics), [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Jobs | 162000 payroll / 4.1 unemployment% | ✅ AVAILABLE | MEDIUM | 2026-09-04 | Realized official data; not a forecast of September FOMC.<br>Sources: [jobs](https://www.bls.gov/news.release/empsit.nr0.htm) |
| Liquidations | — | ⚠️ DATA_LIMITED | NONE | — | NOT FOUND: prior capitulation leg1 retained under stale-input debt; positive funding and positive ETF flow earn no current points. Liquidation component held, not relit. Score range without carry 3-4.<br>Sources: [liquidations](https://www.coinglass.com/liquidations), [prior](reports/btc_fallen_knives_20260822_0346.json) |
| Macro | opposing oil/rate risk | ✅ AVAILABLE | MEDIUM | 2026-09-08 | Brent strength and inflation/rate risk keep gate9 dark despite slightly softer DXY and new institutional access.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json), [news](https://ca.investing.com/news/cryptocurrency-news/bitcoin-slips-below-80k-as-fed-hike-bets-oil-surge-weigh-4829706), [jobs](https://www.bls.gov/news.release/empsit.nr0.htm), [fedrate](https://www.federalreserve.gov/newsevents/pressreleases/monetary20260729a.htm) |
| Pce | 3.7 headline / 3.3 core% | ✅ AVAILABLE | MEDIUM | 2026-08-26 | Monthly headline/core both +0.2%; next PCE Sep30.<br>Sources: [pce](https://www.bea.gov/news/2026/personal-income-and-outlays-july-2026) |
| Policy rate | 3.50-3.75% | ✅ AVAILABLE | MEDIUM | 2026-07-29 | Most recent decision, three hike dissents.<br>Sources: [fedrate](https://www.federalreserve.gov/newsevents/pressreleases/monetary20260729a.htm) |
| Real yield | 2.42% | ✅ AVAILABLE | MEDIUM | 2026-09-03 | Web fallback after failed numeric endpoint; latest displayed official observation.<br>Sources: [realyield](https://fred.stlouisfed.org/series/DFII10) |
| Sentiment | 71 F&G 3-day average | ✅ AVAILABLE | HIGH | 2026-09-08 | Pinned Alternative.me: daily 69 / 71 / 73; greed, score 0. No seven-day >=75 streak.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Spot | $79,169.68 | ✅ AVAILABLE | HIGH | 2026-09-08T00:37:01.959Z | Four synchronized independent quotes; median, no informal round-number anchor.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Us2y | 4.34% | ✅ AVAILABLE | MEDIUM | 2026-09-03 | Latest displayed official observation.<br>Sources: [us2y](https://fred.stlouisfed.org/series/DGS2) |
| Valuation | 0.891 reconstructed MVRV-Z | ✅ AVAILABLE | MEDIUM | 2026-09-06 | 5895 daily observations; (cap-realized cap)/sample full-history cap stdev. Fresh replacement for estimate debt, not proprietary Glassnode Z.<br>Sources: [coinmetrics](https://community-api.coinmetrics.io/v4/timeseries/asset-metrics), [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Weekly RSI-14 | 59.02 Wilder RSI-14 | ✅ AVAILABLE | HIGH | week starting 2026-08-31 | 261 completed Yahoo weekly closes, period14, UTC week-start boundary; live week excluded. >45 scores0.<br>Sources: [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |

**Data gaps:** 8 · **stale inputs:** 4 · **out of scope:** 3

**Data gaps**

- **custody** — ⚠️ DATA_LIMITED — No position-sized hold, trim, exit, stop, PnL or cover instruction. Market report continues.
- **liquidation_distribution** — ⚠️ DATA_LIMITED — Capitulation leg1 held from prior, debt1 this cycle; gate7 provisional/non-counted. Top-decile/3sigma current claim not verified.
- **true_lth30d** — — NOT_COVERED — Prior zero LTH subcomponent retained; no LTH gate. Provider-gated; fresh Glassnode prose and staking do not replace quantitative30d supply.
- **ETF_YTD_AUM** — ⚠️ DATA_LIMITED — YTD and synchronized all-fund AUM unverified. No ratio invented; trailing-month net flow is positive regardless. ETH headline AUM rejected as internally inconsistent.
- **FR_counts** — ⚠️ DATA_LIMITED — Distribution/vulnerability unverified; companion is an explicit range, not a full score.
- **cross_exchange_flow** — ⚠️ DATA_LIMITED — Binance single venue only; never promoted to automatic legacy score/gate.
- **macro_endpoint** — ⚠️ DATA_LIMITED — FRED realyield and2y recovered via official web. HY OAS/NFCI/netliquidity unavailable this cycle; VIX holiday-labelled close not a trading-session observation.
- **hash_ribbon** — ❔ UNKNOWN — Recovery state measured, but full dated buy event unconfirmed; gate5 provisional.

**Stale inputs**

- Capitulation1 is a flagged carry of the prior leg, not a current liquidation point; one-report debt since Aug22 live event. Positive funding and ETF flow measured fresh.
- True LTH half holds prior0; provider-gated despite renewed search, so continuing debt is explicitly disclosed.
- Coin Metrics Z is a fresh computed reconstruction using full history, not stale proprietary data; revisable daily observations.
- Ledger dates are audit metadata, not expiry. Position CLI output revalidated after the local launcher flush fix; required custody/basis fields also cross-checked against the intact canonical export.

**Out of scope**

- No exchange orders placed, cancelled or changed.
- No new strategy research or SHADOW-model activation.
- No ledger repair or other ongoing research-code changes included.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Capitulation | 1 | 3 | Mechanical component |
| Holder | 0 | 3 | Mechanical component |
| Momentum | 0 | 4 | Mechanical component |
| Sentiment | 0 | 5 | Mechanical component |
| Valuation | 3 | 5 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 4 | Legs plus penalties |
| Raw score | 4 | Mechanical plus discretion (0) |
| Adjusted score | **4/20** | Decision score |
| Rounding | half-up | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Attainable ceiling | 20 | No structural pins. Missing observations reduce confidence, not the theoretical rubric ceiling. |
| Line states | — | Same truth over the last four distinct report dates for the deeper score bars and compound key; not a license to alter them. |

### Confirmation gates — 1/9 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | DARK [V]: Alternative.me daily <=15 streak 0. Relight after seven consecutive daily prints <=15. |
| 2 | • NOT PASSED | DARK [V]: completed weekly RSI 59.02. Relight below 30. |
| 3 | ✅ PASSED | LIT [V]: reconstructed MVRV-Z 0.891<1. Estimate provenance disclosed; same input as valuation leg. |
| 4 | • NOT PASSED | DARK [V]: trailing calendar-month covered sessions Aug10-Sep4 net +$3443.8M. Relight with net outflows >=2% of verified AUM. |
| 5 | • NOT PASSED | PROVISIONAL [T], not counted: Glassnode Sep6 hash30d911.776EH/s >60d906.546EH/s; recovery state present, but dated buy-event/price-momentum transition unverified. Relight on full current buy confirmation. |
| 6 | • NOT PASSED | DARK [T]: spot 22.05% versus 200-week SMA 64867.39. Relight band is +/-8%. |
| 7 | • NOT PASSED | PROVISIONAL [V], not counted: prior liquidation credit held under data-debt rule, but Aug21 is not current 24h evidence. Relight requires verified top-decile/3-sigma flush; no $0 placeholder credit. |
| 8 | • NOT PASSED | DARK [V]: true LTH30d unavailable; reserve movement alone is not the LTH/concentration gate. Relight on sourced LTH accumulation. |
| 9 | • NOT PASSED | DARK [T]: oil +7.26%/five observations and hawkish policy risk outweigh bank-access news. Relight after actual macro repricing supports neutral-positive; CPI calendar alone is not a veto. |

### Unlock thresholds

| Phase | Score / gate threshold |
| --- | --- |
| P1A | 3 |
| P1B | 5 |
| P2 | 6 |
| P3 | 7 |




## 4. Probability matrix and expected value

| Scenario | Probability | Low | High | Midpoint | Rationale |
| --- | --- | --- | --- | --- | --- |
| Rally | 20% | $83,000 | $86,000 | $84,500 | Cool CPI / easing oil, renewed spot demand and break of current resistance. |
| Range | 40% | $76,000 | $82,000 | $79,000 | Consolidation around current daily structure; terminal band, not an assertion the low is in. |
| Retest | 25% | $70,000 | $76,000 | $73,000 | Hot CPI or sustained spot selling retests the daily support/200-day region. |
| Bear | 15% | $62,000 | $70,000 | $66,000 | Oil/rates shock and failed major-MA support revisit summer price territory. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $76,650 |
| EV versus spot | -3.18% |

> 3-30 day terminal scenario matrix. D4 weights20/40/25/15. Versus score0-5 baseline10/30/35/25: +10/+10/-10/-10 pp; above-rising200dma and ETF demand justify a less bearish distribution, conditional on CPI. Active downtrend NO; trend residual0. Rally20%<=50%. Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar. EV versus spot -3.18% alongside realized trailing14-calendar-day spot change +0.22% (Aug24 daily close to current).

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 100% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 10% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1A-BTC-20260907-2055 | LOCKED: 4 score points short; 2 total gates short; 1 V gates short. Score AND breadth bind. Prior named planning zone 58000-61000 unchanged; no order authorized. |
| 1B | 15% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1B-BTC-20260907-2055 | LOCKED: 7 score points short; 4 total gates short; 2 V gates short. Score AND breadth bind. Prior named planning zone 54000-58000 unchanged; no order authorized. |
| 2 | 30% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P2-BTC-20260907-2055 | LOCKED: 11 score points short; 5 total gates short; 2 V gates short. Score AND breadth bind. No new zone; any older named floor still governs coherence. |
| 3 | 45% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P3-BTC-20260907-2055 | LOCKED: 13 score points short; 6 total gates short; 3 V gates short. Score AND breadth bind. No new zone; any older named floor still governs coherence. |

## 6. Position, custody, and execution controls

| Position field | Value |
| --- | --- |
| Status | ⚠️ DATA_LIMITED |
| Asset | BTC |
| Quantity | — |
| Dry powder | $10,741.5780 |
| Basis reliable | no |
| Average cost | — |
| Total cost basis | — |
| Custody | • UNEXPLAINED |
| Attribution | ❔ UNKNOWN |
| Active tags | None |

### Custody reconciliation

| Field | Value |
| --- | --- |
| Reason | Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap. |
| Status | • UNEXPLAINED |

### Cost basis

| Field | Value |
| --- | --- |
| Avg cost | — |
| Reason | Unbacked disposals make basis non-derivable; no average cost, basis, unrealized PnL or ROI is quoted. |
| Reliable | No |
| Total cost | — |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Note | No confirmed BTC quantity can be mapped to an FK phase. |
| Status | ❔ UNKNOWN |

### Position P&L

| Field | Value |
| --- | --- |
| Realized | — |
| Reason | Custody and basis defects prohibit a PnL claim. |
| Status | ⚠️ DATA_LIMITED |
| Unrealized | — |

> **Position reconciliation:** Position Reconciliation: unchanged from Aug22. Latest valid Aug15 export remains FRESH under event-driven policy, but BTC custody remains UNEXPLAINED. No quantity in either direction, no basis/PnL claim; not flat. 0% deployed/100% dry describes the framework plan, not the whole account.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| ⚠️ DATA_LIMITED | yes | **NO_POSITION_CLAIM** — Unexplained custody prevents selecting or sizing a live protective order, trim or exit. No level is absolutely best; a best-available control cannot be selected from unreconciled exposure. Recommendations are not executions; no order changed. |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Dry-powder yield | 3.76% |
| Note | Cash has measurable T-bill opportunity value. |
| Status | ✅ AVAILABLE |
| Veto | No |

### Concentration

| Field | Value |
| --- | --- |
| Note | No addition; current BTC weight cannot be computed from a refused quantity claim. |
| Planned pct | 0% |
| Status | ⚠️ DATA_LIMITED |

### Ratchet

| Field | Value |
| --- | --- |
| Note | No confirmed open tranche or auditable live stop; D6 migration ledger empty. |
| Parameters changed | No |
| Status | — NOT_APPLICABLE |

### Stops

| Field | Value |
| --- | --- |
| Catastrophic | 50000 |
| Coherence | Yes |
| Compound | 55000 AND mechanical<12 |
| Deepest zone floor | 54000 |
| Note | Planning only; no live stop asserted. Catastrophic 50000 strictly below deepest carried floor 54000: computed PASS. Compound requires >=2 weekly closes below 55000 AND mechanical<12. This score key is permissive; price remains binding. No migration, no removal, no extension. |
| Status | 🔒 LOCKED |

### Time stops

| Field | Value |
| --- | --- |
| Note | No confirmed authorized fill/time clock. Prior controls cannot be reset from a data defect; no checkpoint extension authorized. Fresh fill must carry a dated maximum hold and verified venue close. |
| Status | — NOT_APPLICABLE |

## 7. Analyst rationale

**Summary:** WAIT / NO NEW ADD. BTC adjusted4/20, mechanical4, gates1/9 with V1. Greed and recovered momentum leave only valuation plus a flagged liquidation carry.

**Bull case:** Three constructive factors: positive ETF net flows; price above rising200dma; Z<1 cheap zone. IF CPI is cooler and spot flow turns positive on both24h/3d, THEN consolidation can resolve higher; falsifier is loss of major-MA support with persistent selling.

**Bear case:** Three adverse factors: spot and futures selling across both horizons; oil/rate catalyst pressure; overhead supply83-86k in dated Glassnode research. Bull/bear3:3; collar ON. No resolved regime or guaranteed bottom claim.

**Rationale:** ### BTC Analyst Read
> The old framework is doing its intended job: it buys fear/value only when enough conditions align. Sentiment71 and weekly RSI near60 describe a recovered tape, even while short-term flow deteriorates. Do not chase institutional-flow headlines or interpret every pullback as a new fallen knife. The opposing flow is context, not a secretly added score leg. The legacy report-machine/2 rules govern; SHADOW swing rules authorize nothing.
> 
> ### BTC completed-bar market flow
> Binance aggregate, single venue. Completed 4h bars through 2026-09-08T00:00:00.000Z; spot scope BTCFDUSD,BTCUSDC,BTCUSDT; perpetual scope BTCUSDC,BTCUSDT. CVD window Sep1-Sep8 rebased to zero. Stablecoin quotes treated as nominal USD. Full discovered-contract coverage; 0 incomplete OI/funding bars. Not cross-exchange and at most one D1 family. [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json)
> 
> | Metric | 24h | 3d | Full window/latest | Interpretation |
> | --- | --- | --- | --- | --- |
> | Spot CVD | $-36.40M | $-141.14M | $-123.17M | Sellers dominate; 24h imbalance -3.403%; 3d -4.963% |
> | Futures taker bid/ask delta | $-431.02M | $-447.00M | latest $8.45M | Sellers dominate; 24h imbalance -4.49%; same family as futures CVD |
> | Futures CVD | $-431.02M | $-447.00M | $-728.98M | Sellers dominate; 24h imbalance -4.49%; 3d -2.139% |
> | Aggregate OI | -0.587% | -1.959% | window -0.892%; latest O/H/L/C 9.94269B/9.94269B/9.86295B/9.86295B | Price and futures CVD down with OI down: deleveraging, not proven absorption. |
> | OI-weighted funding | mean 0.002469% | mean 0.001955% | latest 0.001782%; full mean 0.004798%; percentile 16.67 | Positive; interval-unverified, sign/relative-history only |
> 
> resampled discrete snapshots; high/low are sampled observations, not continuous extrema. OI windows begin Aug31 20:00 UTC (43 bars), CVD Sep1 00:00 (42); full-window levels are not compared across unlike start times. Price24h -1.534%. Funding row uses settled rates carried into the completed bar; the next settlement at Sep8 00:00 is outside this panel. Fresh BTCUSDT/ETHUSDT last three settlements are positive; no funding capitulation credit.
> 
> ### BTC score, uncertainty and binding constraints
> Legs sentiment0 + momentum0 + valuation3 + capitulation1 HELD + holder0 = raw4; D1=0; mechanical/adjusted4, BTC half-up. Score without the stale liquidation carry would be 3; either reading leaves entries locked. The theoretical ceiling is20, no structural pin. Deep phases>=15/>=17 and compound<12 retain the stated vacuity labels over four distinct report dates; this changes no threshold. P1A/P1B score lines are LIVE. All four phases fail score plus gate breadth; per-row deficits are explicit. No D2 near-miss: score fails and V floor fails. Override evaluated: NOT APPLICABLE without a confirmed prior deployed tranche, and mechanical<15. No new zone, stop, or time-clock migration.
> 
> ### BTC Probability and EV audit
> 3-30 day terminal scenario matrix. D4 weights20/40/25/15. Versus score0-5 baseline10/30/35/25: +10/+10/-10/-10 pp; above-rising200dma and ETF demand justify a less bearish distribution, conditional on CPI. Active downtrend NO; trend residual0. Rally20%<=50%. Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar. EV versus spot -3.18% alongside realized trailing14-calendar-day spot change +0.22% (Aug24 daily close to current).
> 
> 0.20 x 84,500.00 + 0.40 x 79,000.00 + 0.25 x 73,000.00 + 0.15 x 66,000.00 = $76,650.00. Return contributions: Rally +1.347pp, Range -0.086pp, Retest -1.948pp, Bear -2.495pp = -3.183%. The negative sign is driven chiefly by downside band distance; Range midpoint is below spot. This is risk-weighted scenario geometry, not a reliable directional forecast; it cannot carry the stance. Bands are scenario endpoints, not authorized buy ladders. Active downtrend NO; no terminal/path residual reconciliation is triggered. Even so, Range is a terminal band and a path through Retest/Bear remains possible. EV-floor inconsistency check not triggered (mechanical<15, F&G>15). Collar remains ON by balanced scorecard and demoted EV.
> 
> ### BTC Position, stops and exits
> Position Reconciliation: unchanged from Aug22. Latest valid Aug15 export remains FRESH under event-driven policy, but BTC custody remains UNEXPLAINED. No quantity in either direction, no basis/PnL claim; not flat. 0% deployed/100% dry describes the framework plan, not the whole account. Stable balance$10,741.5780, including$2,948.9340 locked in orders; free/drawable$7,792.6440, shared across the account, not allocated separately to BTC and ETH. Futures collateral is not added to this cash balance. No asset book/equity sizing invented. No reliable phase-specific PnL or performance claim. Unexplained custody prevents selecting or sizing a live protective order, trim or exit. No level is absolutely best; a best-available control cannot be selected from unreconciled exposure. Recommendations are not executions; no order changed.
> 
> Planning only; no live stop asserted. Catastrophic 50000 strictly below deepest carried floor 54000: computed PASS. Compound requires >=2 weekly closes below 55000 AND mechanical<12. This score key is permissive; price remains binding. No migration, no removal, no extension.
> 
> EXIT/TRIM audit (mechanical score only): campaign peak/drop cannot be determined from unreconciled/untagged exposure; do not infer a25% trim from the all-history peak. F&G>=75 seven-day AND weeklyRSI>70 is FALSE; valuationZ>3 FALSE; score<=3 AND gain>=40% score4>3, FALSE; ETF>=3%AUM trailing-month outflows FALSE (positive netflow). No verified protocol/narrative break found in the live search. No trim/exit execution claimed; remaining quantity unknown. Baseline stop/campaign clocks never reset by this defect. LIFO applies if a future verified trim fires.
> 
> ### BTC Discretion Ledger
> D1 2026-09-07:0, no size, no stop; negative adjustment considered for flow/oil risk and declined because the no-add stance already follows binding score/gates and no unscored two-family term is needed. Prior zero remains zero. D2 declined: not a gate-only near miss. D4 current20/40/25/15, analyst-set probabilities and conditional falsifiers in the matrix; no capital deployed and no realizedP&L. Prior Aug22 D4 negative EV contradicted by the current spot change; retired as a directional prediction, retained in the historical bias scorecard. Previous unresolved older calls are not represented as wins. Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar.
> 
> ### BTC calendar and execution plan
> US sessions Sep8,9,10,11,14 (Sep7 Labor Day closed): CPI Fri Sep11 08:30 ET is the only named tier1 release inside the next5 sessions. Payroll already released Sep4, next Oct2; PCE Sep30; FOMC Sep15-16 decision Sep16 14:00 ET, minutes last Aug19/next Oct7. PPI Thu Sep10 08:30 ET is also watched. Dates verified against BLS/BEA/Fed schedules; crypto trades every day. No automatic pre-event deployment pause.
> 
> 1. Keep all new FK phases locked; do not place a market buy from this report.
> 2. Reconcile ledger custody before any position-specific stop/trim/cover action; preserve existing verified controls.
> 3. Refresh after Sep11 CPI, or earlier if score/gates/price-zone conditions change; evaluate market-flow reversal on both24h and3d.
> 4. Keep reserved phase tags inactive until a verified authorized fill. Canonical vocabulary: FK-P1A, FK-P1B, FK-P2, FK-P3, FK-OVR, FK-D1, FK-D2 and UNFRAMED. Report-specific phase tags below remain reserved; active_tags is empty.
> 5. Complete the outstanding standalone FR analysis to discharge the Aug22 obligation.
> 
> IF cooler inflation and renewed spot demand, THEN re-evaluate the upside scenario without skipping FK gates. IF oil/rates rise and support breaks, THEN reassess downside and independent exit rules. IF a phase unlocks away from its named zone, THEN leave its ladder unfilled.

**Primary action:** **WAIT_NO_ADD** — Score and gate/V floors fail independently; market weakness alone is not an FK unlock.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Deep value override | Evaluated, no confirmed predecessor and mechanical<15; no firing. |
| Ev calibration | Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar. |
| Flow panel | Binance aggregate, single venue. Completed 4h bars through 2026-09-08T00:00:00.000Z; spot scope BTCFDUSD,BTCUSDC,BTCUSDT; perpetual scope BTCUSDC,BTCUSDT. CVD window Sep1-Sep8 rebased to zero. Stablecoin quotes treated as nominal USD. Full discovered-contract coverage; 0 incomplete OI/funding bars. Not cross-exchange and at most one D1 family. [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json) |
| Standalone fr obligation | [object Object] |
| Tier1 calendar | US sessions Sep8,9,10,11,14 (Sep7 Labor Day closed): CPI Fri Sep11 08:30 ET is the only named tier1 release inside the next5 sessions. Payroll already released Sep4, next Oct2; PCE Sep30; FOMC Sep15-16 decision Sep16 14:00 ET, minutes last Aug19/next Oct7. PPI Thu Sep10 08:30 ET is also watched. Dates verified against BLS/BEA/Fed schedules; crypto trades every day. No automatic pre-event deployment pause. |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ⚠️ DATA_LIMITED | flying_rocket · 3/20 | Java same-snapshot companion floor 3, ceiling 9; channel none. Missing distribution/vulnerability counts are unknown, not true zero. Both assets remain >20% below1y high and above rising200dma; no active FR channel. Cap applied=False; do not claim a binding cap in channel none. Full HardRule5 dischargeable=False. Prior standalone FR obligation from Aug22 remains owed (2 FK reports outstanding); last standalone Aug21 predates it. Inline calculation does not discharge that obligation. |
| Cross-validation | ⚠️ DATA_LIMITED | FK 4 versus partial FR 3-9 | Cross-validation UNVERIFIED — standalone FR obligation outstanding 2 reports. No both>=12 conflict is possible on these bounds, but unknown companion counts straddle the >=9 standalone threshold; full cross-validation is not asserted or used to support the verdict. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| CPI | ✅ AVAILABLE | Friday 2026-09-11 08:30 ET. IF hotter inflation reprices yields/oil higher, THEN increase downside risk assessment; IF cooler with positive completed spot CVD, THEN reassess Range/Rally. |
| PPI | ✅ AVAILABLE | Thursday 2026-09-10 08:30 ET; intermediate inflation checkpoint. |
| FOMC | ✅ AVAILABLE | Wednesday 2026-09-16 14:00 ET decision, outside next5 sessions but inside30-day horizon. |
| P1A relight | ✅ AVAILABLE | Adjusted>=8, total>=3, V>=2; current deficits 4 points/2 gates/1V. Fill only inside the carried P1A zone after every condition holds. |
| Position reconciliation | ⚠️ DATA_LIMITED | Resolve custody mismatch before selecting or sizing any protective/exit order. |
| Standalone FR obligation | ⚠️ DATA_LIMITED | Aug22 obligation remains owed, reports_outstanding2. Complete standalone report to discharge; inline companion does not suffice. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-09-08T00:55:21Z | Tier1 calendar | ✅ AVAILABLE | US sessions Sep8,9,10,11,14 (Sep7 Labor Day closed): CPI Fri Sep11 08:30 ET is the only named tier1 release inside the next5 sessions. Payroll already released Sep4, next Oct2; PCE Sep30; FOMC Sep15-16 decision Sep16 14:00 ET, minutes last Aug19/next Oct7. PPI Thu Sep10 08:30 ET is also watched. Dates verified against BLS/BEA/Fed schedules; crypto trades every day. No automatic pre-event deployment pause. |
| 2026-09-03 | Institutional access | ✅ AVAILABLE | Standard Chartered launched UAE institutional BTC/ETH spot trading; structural access benefit, no immediate FK gate or score credit. |
| 2026-09-04 | ETF update | ✅ AVAILABLE | Latest full US week +986.7M; latest day +174.6M. Labor Day means Sep4 remains latest completed ETF session. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| No new FK accumulation at current evidence | Reassess at Sep11 CPI: score>=8 AND total>=3 AND V>=2 AND price in valid named zone falsifies the locked entry state. A cooler CPI alone does not. | ✅ AVAILABLE |
| Consolidation above rising major MA | By Sep16 FOMC, a completed daily break below current200dma 69814.7 with continuing negative spot CVD falsifies repair; upside break above Sep3 high82262 with improving flow falsifies persistent range pressure. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.adjusted | 6 | 4 | Completed weekly momentum exhaustion credit disappears; fresh valuation/reserve classifications persist. Capitulation1 held with explicit data debt. |
| Gates.passed | 3; 7; 9 | 3 | Macro gate9 dark on oil/rate pressure; liquidation gate7 no longer live and not counted. |
| Spot cvd 3d | 321577158.57 | -141144619.71000001 | Compare equal3d deltas, never rebased cumulative levels: flow flipped to selling. |
| Score.discretion | 0 | 0 | No fresh load-bearing two-family discretion case adopted. Prior ETH overextension term retired on fresh review; this does not unlock a phase. |
| Position.status | DATA_LIMITED | DATA_LIMITED | Same newest valid export and same unexplained custody. No inferred flat/reset. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| CLF | Yahoo CLF | live data | 2026-09-08T00:28:44+00:00 | 2026-09-08T00:55:21Z | —<br>[Open source](https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1mo&interval=1d) |
| DJI | Yahoo DJI | live data | 2026-09-04T13:30:00+00:00 | 2026-09-08T00:55:21Z | —<br>[Open source](https://query1.finance.yahoo.com/v8/finance/chart/%5EDJI?range=1mo&interval=1d) |
| altseason | BlockchainCenter altcoin season index | live data | 2026-09-08 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.blockchaincenter.net/altcoin-season-index/) |
| bea | BEA release calendar | official calendar | 2026-09-08 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.bea.gov/news/schedule/full) |
| binance | Binance completed daily candles and funding | exchange | 2026-09-08T00:00:00Z | 2026-09-08T00:55:21Z | —<br>[Open source](https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=100) |
| bls | BLS September calendar | official calendar | 2026-09-08 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.bls.gov/schedule/2026/09_sched.htm) |
| coinmetrics | Coin Metrics Community daily on-chain API | on-chain | 2026-09-06 | 2026-09-08T00:55:21Z | MVRV-Z reconstructed; flash data can revise. True LTH unavailable.<br>[Open source](https://community-api.coinmetrics.io/v4/timeseries/asset-metrics) |
| correlation | Binance daily closes / Yahoo S&P500 | computed | 2026-09-04 | 2026-09-08T00:55:21Z | Aligned completed session log returns; Java compute corr.<br>[Open source](https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC) |
| cpi | BLS July CPI | official release | 2026-08-12 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.bls.gov/news.release/cpi.htm) |
| farside | BTC ETF flow primary full table | fund flows | 2026-09-04 | 2026-09-08T00:55:21Z | —<br>[Open source](https://farside.co.uk/bitcoin-etf-flow-all-data/) |
| fed | Federal Reserve FOMC calendar | official calendar | 2026-09-08 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm) |
| fedrate | Federal Reserve July policy decision | official release | 2026-07-29 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.federalreserve.gov/newsevents/pressreleases/monetary20260729a.htm) |
| glassnode | Glassnode Doubt at the Boundaries | on-chain research | 2026-09-02 | 2026-09-08T00:55:21Z | Dated structural context; not a current LTH30d measurement.<br>[Open source](https://research.glassnode.com/the-week-onchain-week-35-2026/) |
| hashribbon | Glassnode Hash Ribbon | on-chain | 2026-09-06 | 2026-09-08T00:55:21Z | 30d911.776EH/s >60d906.546EH/s, but current buy-event/crossover timestamp not confirmed.<br>[Open source](https://studio.glassnode.com/charts/indicators.HashRibbon?a=BTC) |
| jobs | BLS August jobs | official release | 2026-09-04 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.bls.gov/news.release/empsit.nr0.htm) |
| ledger | Newest valid ledger export | ledger | 2026-08-15T09:30:02.628Z | 2026-09-08T00:55:21Z | FRESH event-driven; holdings_as_of 2026-07-05. Both custody states UNEXPLAINED. No quantity or PnL claim.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| liquidations | CoinGlass liquidation page | attempted live data | 2026-09-08 | 2026-09-08T00:55:21Z | Unpopulated page reports $0/undefined placeholders; not a measured zero. No valid 90d percentile or 30d sigma distribution.<br>[Open source](https://www.coinglass.com/liquidations) |
| news | Oil and Fed risk context | news | 2026-09-07 | 2026-09-08T00:55:21Z | —<br>[Open source](https://ca.investing.com/news/cryptocurrency-news/bitcoin-slips-below-80k-as-fed-hike-bets-oil-surge-weigh-4829706) |
| pce | BEA July PCE | official release | 2026-08-26 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.bea.gov/news/2026/personal-income-and-outlays-july-2026) |
| prior | Prior FK report | report history | 2026-08-22 | 2026-09-08T00:55:21Z | —<br>[Open source](reports/btc_fallen_knives_20260822_0346.json) |
| realyield | FRED 10y real yield | official series | 2026-09-03 | 2026-09-08T00:55:21Z | Web fallback after Java HTTP2 and CSV failures.<br>[Open source](https://fred.stlouisfed.org/series/DFII10) |
| scbank | Standard Chartered UAE launch | bank primary release | 2026-09-03 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.sc.com/en/press-release/standard-chartered-becomes-first-global-systemically-important-bank-g-sib-to-launch-institutional-bitcoin-and-ether-spot-trading-in-the-uae/) |
| snapshot | Live deterministic market snapshot | computed | 2026-09-08T00:37:01.959Z | 2026-09-08T00:55:21Z | Fetched with clean committed Java toolchain30262ac; a local launcher-only flush/argument fix enables complete receipts. No arithmetic or scoring code changed; source timestamps preserved.<br>[Open source](data/runs/20260908-0037-97ce583f/snapshot.json) |
| sosovalue | Independent ETF flow corroboration | fund data relay | 2026-09-04 | 2026-09-08T00:55:21Z | SoSoValue direct pages failed. ETH aggregator AUM fails holdings-times-price consistency and is excluded.<br>[Open source](https://coinpaper.com/35390/crypto-trading-update-bitcoin-etfs-pull-in-987m-as-btc-holds-near-80k) |
| staking | Validator Queue / beaconcha.in | live data | 2026-09-08 | 2026-09-08T00:55:21Z | —<br>[Open source](https://www.validatorqueue.com/) |
| us2y | FRED 2y nominal yield | official series | 2026-09-03 | 2026-09-08T00:55:21Z | —<br>[Open source](https://fred.stlouisfed.org/series/DGS2) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-09-08T00:37:01.959Z |
| Generated at | 2026-09-08T00:55:21Z |
| Report at | 2026-09-08T00:55:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | btc_fallen_knives_20260907_2055 |
| Report filename | btc_fallen_knives_20260907_2055.json |
| Run ID | 20260908-0037-97ce583f |
| Snapshot ID | sha256:97ce583fbcd6677fd4b0c8e5998f784d67164e3cd89323bc39908a2101fbe8d9 |
| Prior report | btc_fallen_knives_20260822_0346 |
| Prior report hash | beee4a036e8f277ca8f660cedc675d290ceae536d63f524291252aab5875f94c |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:a79ebb055145873293e1dc430bafe940879de8f12f271bac03c8fa1a5187be4c |
| fetch | sha256:49cd6075b09ec7a8b4ed24809f44e1c8fdb6bd6e9131bd71d29668d548f66a66 |
| launcher | sha256:a618e762039f581377fcb9470efe2d368a445734283644557bc4901eba927875 |
| reporting_emitter | sha256:3315b0042d182922879301f3efb1c8a70373eae378552740ba89aa2c13ba2243 |
| snapshot | sha256:28a6dabb2f37fa84238065b042bec8f78986e0793f32e628376b92f2217b0c21 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | 🔒 LOCKED | FK-P1A-BTC-20260907-2055 | crypto |
| 1B | 🔒 LOCKED | FK-P1B-BTC-20260907-2055 | crypto |
| 2 | 🔒 LOCKED | FK-P2-BTC-20260907-2055 | crypto |
| 3 | 🔒 LOCKED | FK-P3-BTC-20260907-2055 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FK-P1A-BTC-20260907-2055, FK-P1B-BTC-20260907-2055, FK-P2-BTC-20260907-2055, FK-P3-BTC-20260907-2055

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":4,"field":"score.adjusted","previous":6,"reason":"Completed weekly momentum exhaustion credit disappears; fresh valuation/reserve classifications persist. Capitulation1 held with explicit data debt."},{"current":[3],"field":"gates.passed","previous":[3,7,9],"reason":"Macro gate9 dark on oil/rate pressure; liquidation gate7 no longer live and not counted."},{"current":"-141144619.71000001","field":"spot_cvd_3d","previous":"321577158.57","reason":"Compare equal3d deltas, never rebased cumulative levels: flow flipped to selling."},{"current":0,"field":"score.discretion","previous":0,"reason":"No fresh load-bearing two-family discretion case adopted. Prior ETH overextension term retired on fresh review; this does not unlock a phase."},{"current":"DATA_LIMITED","field":"position.status","previous":"DATA_LIMITED","reason":"Same newest valid export and same unexplained custody. No inferred flat/reset."}],"companion_framework":{"framework":"flying_rocket","gates":null,"rationale":"Java same-snapshot companion floor 3, ceiling 9; channel none. Missing distribution/vulnerability counts are unknown, not true zero. Both assets remain >20% below1y high and above rising200dma; no active FR channel. Cap applied=False; do not claim a binding cap in channel none. Full HardRule5 dischargeable=False. Prior standalone FR obligation from Aug22 remains owed (2 FK reports outstanding); last standalone Aug21 predates it. Inline calculation does not discharge that obligation.","score":3,"status":"DATA_LIMITED"},"cross_validation":{"rationale":"Cross-validation UNVERIFIED — standalone FR obligation outstanding 2 reports. No both>=12 conflict is possible on these bounds, but unknown companion counts straddle the >=9 standalone threshold; full cross-validation is not asserted or used to support the verdict.","relationship":"FK 4 versus partial FR 3-9","status":"DATA_LIMITED"},"data_gaps":[{"field":"custody","impact":"No position-sized hold, trim, exit, stop, PnL or cover instruction. Market report continues.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"liquidation_distribution","impact":"Capitulation leg1 held from prior, debt1 this cycle; gate7 provisional/non-counted. Top-decile/3sigma current claim not verified.","source_ids":["liquidations","prior"],"status":"DATA_LIMITED"},{"field":"true_lth30d","impact":"Prior zero LTH subcomponent retained; no LTH gate. Provider-gated; fresh Glassnode prose and staking do not replace quantitative30d supply.","source_ids":["coinmetrics","glassnode"],"status":"NOT_COVERED"},{"field":"ETF_YTD_AUM","impact":"YTD and synchronized all-fund AUM unverified. No ratio invented; trailing-month net flow is positive regardless. ETH headline AUM rejected as internally inconsistent.","source_ids":["farside","sosovalue"],"status":"DATA_LIMITED"},{"field":"FR_counts","impact":"Distribution/vulnerability unverified; companion is an explicit range, not a full score.","source_ids":["snapshot"],"status":"DATA_LIMITED"},{"field":"cross_exchange_flow","impact":"Binance single venue only; never promoted to automatic legacy score/gate.","source_ids":["snapshot"],"status":"DATA_LIMITED"},{"field":"macro_endpoint","impact":"FRED realyield and2y recovered via official web. HY OAS/NFCI/netliquidity unavailable this cycle; VIX holiday-labelled close not a trading-session observation.","source_ids":["snapshot","realyield","us2y"],"status":"DATA_LIMITED"},{"field":"hash_ribbon","impact":"Recovery state measured, but full dated buy event unconfirmed; gate5 provisional.","source_ids":["hashribbon"],"status":"UNKNOWN"}],"deployment":{"deployed_pct":"0","dry_pct":"100","throttle_released":false,"tranches":[{"channel":null,"deployed":false,"entry_price":null,"pct":"10","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"LOCKED: 4 score points short; 2 total gates short; 1 V gates short. Score AND breadth bind. Prior named planning zone 58000-61000 unchanged; no order authorized.","state":"LOCKED","stop":null,"tag":"FK-P1A-BTC-20260907-2055","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"15","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"LOCKED: 7 score points short; 4 total gates short; 2 V gates short. Score AND breadth bind. Prior named planning zone 54000-58000 unchanged; no order authorized.","state":"LOCKED","stop":null,"tag":"FK-P1B-BTC-20260907-2055","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"30","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"LOCKED: 11 score points short; 5 total gates short; 2 V gates short. Score AND breadth bind. No new zone; any older named floor still governs coherence.","state":"LOCKED","stop":null,"tag":"FK-P2-BTC-20260907-2055","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"45","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"LOCKED: 13 score points short; 6 total gates short; 3 V gates short. Score AND breadth bind. No new zone; any older named floor still governs coherence.","state":"LOCKED","stop":null,"tag":"FK-P3-BTC-20260907-2055","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"3-30 day terminal scenario matrix. D4 weights20/40/25/15. Versus score0-5 baseline10/30/35/25: +10/+10/-10/-10 pp; above-rising200dma and ETF demand justify a less bearish distribution, conditional on CPI. Active downtrend NO; trend residual0. Rally20%<=50%. Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar. EV versus spot -3.18% alongside realized trailing14-calendar-day spot change +0.22% (Aug24 daily close to current).","probability_sum":1,"scenarios":[{"high":"86000","low":"83000","mid":"84500","name":"Rally","probability":0.2,"rationale":"Cool CPI / easing oil, renewed spot demand and break of current resistance."},{"high":"82000","low":"76000","mid":"79000","name":"Range","probability":0.4,"rationale":"Consolidation around current daily structure; terminal band, not an assertion the low is in."},{"high":"76000","low":"70000","mid":"73000","name":"Retest","probability":0.25,"rationale":"Hot CPI or sustained spot selling retests the daily support/200-day region."},{"high":"70000","low":"62000","mid":"66000","name":"Bear","probability":0.15,"rationale":"Oil/rates shock and failed major-MA support revisit summer price territory."}],"stated_ev":"76650","vs_spot_pct":"-3.18"},"events":[{"as_of":"2026-09-08T00:55:21Z","impact":"US sessions Sep8,9,10,11,14 (Sep7 Labor Day closed): CPI Fri Sep11 08:30 ET is the only named tier1 release inside the next5 sessions. Payroll already released Sep4, next Oct2; PCE Sep30; FOMC Sep15-16 decision Sep16 14:00 ET, minutes last Aug19/next Oct7. PPI Thu Sep10 08:30 ET is also watched. Dates verified against BLS/BEA/Fed schedules; crypto trades every day. No automatic pre-event deployment pause.","name":"Tier1 calendar","status":"AVAILABLE"},{"as_of":"2026-09-03","impact":"Standard Chartered launched UAE institutional BTC/ETH spot trading; structural access benefit, no immediate FK gate or score credit.","name":"Institutional access","status":"AVAILABLE"},{"as_of":"2026-09-04","impact":"Latest full US week +986.7M; latest day +174.6M. Labor Day means Sep4 remains latest completed ETF session.","name":"ETF update","status":"AVAILABLE"}],"evidence":{"correlation":{"as_of":"2026-09-04","confidence":"MEDIUM","rationale":"30 aligned closes / 29 log returns; not price-level correlation. Surcharge OFF if <=0.7; no guessed bonus.","source_ids":["correlation"],"status":"AVAILABLE","unit":"Pearson daily log-return correlation","value":"0.13958087"},"cpi":{"as_of":"2026-08-12","confidence":"MEDIUM","rationale":"Latest release; next CPI Sep11 08:30 ET.","source_ids":["cpi"],"status":"AVAILABLE","unit":"percent YoY July","value":"3.4 headline / 2.5 core"},"etf_flows":{"as_of":"2026-09-04","confidence":"MEDIUM","rationale":"Daily +174.6M; Aug31-Sep4 +986.7M; SepMTD +770.0M. Latest inflow streak 3 sessions. Sep7 US Labor Day closure; no zero-flow invented. YTD total NOT VERIFIED; conflicting window totals are not annual totals. AUM ratio unavailable; positive net flow cannot meet an outflow gate.","source_ids":["farside","sosovalue"],"status":"AVAILABLE","unit":"USD millions Aug10-Sep4","value":"3443.8"},"holder":{"as_of":"2026-09-06","confidence":"MEDIUM","rationale":"True LTH data provider-gated: its half holds prior zero; reserves rise, holder0.","source_ids":["coinmetrics","snapshot"],"status":"AVAILABLE","unit":"exchange reserves change 30d %","value":"1.397"},"jobs":{"as_of":"2026-09-04","confidence":"MEDIUM","rationale":"Realized official data; not a forecast of September FOMC.","source_ids":["jobs"],"status":"AVAILABLE","unit":"jobs / percent August","value":"162000 payroll / 4.1 unemployment"},"liquidations":{"as_of":null,"confidence":"NONE","rationale":"NOT FOUND: prior capitulation leg1 retained under stale-input debt; positive funding and positive ETF flow earn no current points. Liquidation component held, not relit. Score range without carry 3-4.","source_ids":["liquidations","prior"],"status":"DATA_LIMITED","unit":"market-wide 24h / historical percentile","value":null},"macro":{"as_of":"2026-09-08","confidence":"MEDIUM","rationale":"Brent strength and inflation/rate risk keep gate9 dark despite slightly softer DXY and new institutional access.","source_ids":["snapshot","news","jobs","fedrate"],"status":"AVAILABLE","unit":null,"value":"opposing oil/rate risk"},"pce":{"as_of":"2026-08-26","confidence":"MEDIUM","rationale":"Monthly headline/core both +0.2%; next PCE Sep30.","source_ids":["pce"],"status":"AVAILABLE","unit":"percent YoY July","value":"3.7 headline / 3.3 core"},"policy_rate":{"as_of":"2026-07-29","confidence":"MEDIUM","rationale":"Most recent decision, three hike dissents.","source_ids":["fedrate"],"status":"AVAILABLE","unit":"percent target range","value":"3.50-3.75"},"real_yield":{"as_of":"2026-09-03","confidence":"MEDIUM","rationale":"Web fallback after failed numeric endpoint; latest displayed official observation.","source_ids":["realyield"],"status":"AVAILABLE","unit":"percent 10y real yield","value":"2.42"},"sentiment":{"as_of":"2026-09-08","confidence":"HIGH","rationale":"Pinned Alternative.me: daily 69 / 71 / 73; greed, score 0. No seven-day >=75 streak.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"F&G 3-day average","value":"71"},"spot":{"as_of":"2026-09-08T00:37:01.959Z","confidence":"HIGH","rationale":"Four synchronized independent quotes; median, no informal round-number anchor.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"79169.68"},"us2y":{"as_of":"2026-09-03","confidence":"MEDIUM","rationale":"Latest displayed official observation.","source_ids":["us2y"],"status":"AVAILABLE","unit":"percent 2y yield","value":"4.34"},"valuation":{"as_of":"2026-09-06","confidence":"MEDIUM","rationale":"5895 daily observations; (cap-realized cap)/sample full-history cap stdev. Fresh replacement for estimate debt, not proprietary Glassnode Z.","source_ids":["coinmetrics","snapshot"],"status":"AVAILABLE","unit":"reconstructed MVRV-Z","value":"0.891"},"weekly_rsi":{"as_of":"week starting 2026-08-31","confidence":"HIGH","rationale":"261 completed Yahoo weekly closes, period14, UTC week-start boundary; live week excluded. >45 scores0.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Wilder RSI-14","value":"59.02"}},"falsifiers":[{"claim":"No new FK accumulation at current evidence","condition":"Reassess at Sep11 CPI: score>=8 AND total>=3 AND V>=2 AND price in valid named zone falsifies the locked entry state. A cooler CPI alone does not.","status":"AVAILABLE"},{"claim":"Consolidation above rising major MA","condition":"By Sep16 FOMC, a completed daily break below current200dma 69814.7 with continuing negative spot CVD falsifies repair; upside break above Sep3 high82262 with improving flow falsifies persistent range pressure.","status":"AVAILABLE"}],"gates":{"active":9,"measurement_basis":{"1":"DARK [V]: Alternative.me daily <=15 streak 0. Relight after seven consecutive daily prints <=15.","2":"DARK [V]: completed weekly RSI 59.02. Relight below 30.","3":"LIT [V]: reconstructed MVRV-Z 0.891<1. Estimate provenance disclosed; same input as valuation leg.","4":"DARK [V]: trailing calendar-month covered sessions Aug10-Sep4 net +$3443.8M. Relight with net outflows >=2% of verified AUM.","5":"PROVISIONAL [T], not counted: Glassnode Sep6 hash30d911.776EH/s >60d906.546EH/s; recovery state present, but dated buy-event/price-momentum transition unverified. Relight on full current buy confirmation.","6":"DARK [T]: spot 22.05% versus 200-week SMA 64867.39. Relight band is +/-8%.","7":"PROVISIONAL [V], not counted: prior liquidation credit held under data-debt rule, but Aug21 is not current 24h evidence. Relight requires verified top-decile/3-sigma flush; no $0 placeholder credit.","8":"DARK [V]: true LTH30d unavailable; reserve movement alone is not the LTH/concentration gate. Relight on sourced LTH accumulation.","9":"DARK [T]: oil +7.26%/five observations and hawkish policy risk outweigh bank-access news. Relight after actual macro repricing supports neutral-positive; CPI calendar alone is not a veto.","binding_axis":"V=1; total 1/9. P1A short 4 score points, 2 total gates, 1 V gate. Both axes bind; D2 cannot substitute for score or V floor."},"na":[],"passed":[3],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":7}},"identity":{"asset":"BTC","date":"2026-09-07","filename":"btc_fallen_knives_20260907_2055.json","framework":"fallen_knives","local_time":"20:55","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-10-06","note":"","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"126080"},"drawdown_pct":{"as_of":"2026-09-08T00:37:01.959Z","note":"","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"37.21"},"metrics":{"CLF":{"as_of":"2026-09-08T00:28:44+00:00","note":"Live/last available quote; WTI is front-month futures.","source_ids":["CLF"],"status":"AVAILABLE","unit":"USD","value":"92.33999634"},"DJI":{"as_of":"2026-09-04T13:30:00+00:00","note":"Last available Dow index quote.","source_ids":["DJI"],"status":"AVAILABLE","unit":"index","value":"53414.25"},"adr5":{"as_of":"2026-09-08T00:37:01.959Z","note":"ADR corrected using Sep3-Sep7 five complete Binance sessions; Yahoo partial Sep8 excluded and missing Sep7 replaced.","source_ids":["binance"],"status":"AVAILABLE","unit":"USD","value":"2389.75"},"brent":{"as_of":"2026-09-08","note":"Yahoo BZ=F (Brent crude); change 7.26%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"97.06"},"coinbase_premium":{"as_of":"2026-09-07","note":"Completed daily USD/USDT-adjusted premium; negative3d FALSE.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"-0.008"},"dry_powder_yield":{"as_of":"2026-09-04","note":"13-week T-bill discount-rate opportunity benchmark; not assumed stablecoin APY.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"3.76"},"dxy":{"as_of":"2026-09-08","note":"Yahoo DX-Y.NYB (US Dollar Index); change -0.26%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"index","value":"99.18"},"futures_cvd_3d":{"as_of":"2026-09-08T00:00:00.000Z","note":"Binance aggregate, single venue; completed 4h window. Context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"-447002015.33"},"gold":{"as_of":"2026-09-08","note":"Yahoo GC=F (COMEX gold front month); change 0.78%. Gold is COMEX futures, not spot.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"4465.8"},"long_short_account_ratio":{"as_of":"2026-09-08T00:37:01.959Z","note":"Binance accounts, not position notional; single venue.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"ratio","value":"1.1608"},"ma200":{"as_of":"2026-09-08T00:37:01.959Z","note":"","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"69814.7"},"ma50":{"as_of":"2026-09-08T00:37:01.959Z","note":"","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"69695.51"},"move":{"as_of":"2026-09-04","note":"Yahoo ^MOVE (ICE BofA MOVE Index (bond vol)); change 3%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"index","value":"73.1"},"ndx":{"as_of":"2026-09-04","note":"Yahoo ^IXIC (Nasdaq Composite); change 0.4%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"index","value":"26506.99"},"oi_change_3d":{"as_of":"2026-09-08T00:00:00.000Z","note":"Binance aggregate, single venue; completed 4h window. Context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"-1.959"},"sma200w":{"as_of":"2026-09-08T00:37:01.959Z","note":"","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"64867.39"},"spot_cvd_24h":{"as_of":"2026-09-08T00:00:00.000Z","note":"Binance aggregate, single venue; completed 4h window. Context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"-36404241.06"},"spot_cvd_3d":{"as_of":"2026-09-08T00:00:00.000Z","note":"Binance aggregate, single venue; completed 4h window. Context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"-141144619.71"},"spx":{"as_of":"2026-09-04","note":"Yahoo ^GSPC (S&P 500); change 0.09%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"index","value":"7718.6"},"us10y":{"as_of":"2026-09-04","note":"Yahoo ^TNX (US 10y nominal yield (×10 units)); change 1.36%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"4.78"},"vix":{"as_of":"2026-09-07","note":"Yahoo ^VIX (CBOE VIX); five-observation delta 2.55%. Yahoo VIX Sep7 holiday label is suspect; descriptive only, no session claim.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"index","value":"15.3"}},"reconciliation":{"method":"Median of four synchronized CoinGecko/Binance/Coinbase/Kraken quotes","note":"Dispersion is small synchronized disagreement, not a mixed-time spread. Yahoo frozen bar close 79175.07 excluded by kind; age unavailable, never in median.","quotes":[{"as_of":"2026-09-08T00:35:10+00:00","instrument":"CoinGecko bitcoin","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"79154"},{"as_of":"2026-09-08T00:37:02.013000+00:00","instrument":"Binance BTCUSDT","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"79186.73"},{"as_of":"2026-09-08T00:37:00.713000+00:00","instrument":"Coinbase BTC-USD","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"79174.46"},{"as_of":"2026-09-08T00:37:01.959Z","instrument":"Kraken XBTUSD","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"79164.9"}],"spread_pct":"0.041","status":"AVAILABLE"},"regime":{"active_downtrend":false,"bounce_age_sessions":35,"bounce_pct":27.23,"gap_narrowed_20":true,"gap_now_pct":0.17,"insufficient":null,"label":"Post-rally consolidation; no extreme fear","low_40s":62226.58,"ma200":69814.7,"ma200_falling":false,"ma200_slope20_pct":1.12,"ma50":69695.51,"ma50_below_ma200":true,"price_below_ma200":false,"rsi14":63,"rsi14_confidence":"ok","sessions_low_to_high":31,"structure_b":true,"trend_residual":"NO: price above rising200dma, so no bearish trend residual. Lower lows alone would not satisfy both conditions.","within_3pct_of_ma200":false,"within_3pct_of_ma50_from_below":false},"spot":{"as_of":"2026-09-08T00:37:01.959Z","note":"Four-source synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"79169.68"}},"narrative":{"arguments":{"deep_value_override":"Evaluated, no confirmed predecessor and mechanical<15; no firing.","ev_calibration":"Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar.","flow_panel":"Binance aggregate, single venue. Completed 4h bars through 2026-09-08T00:00:00.000Z; spot scope BTCFDUSD,BTCUSDC,BTCUSDT; perpetual scope BTCUSDC,BTCUSDT. CVD window Sep1-Sep8 rebased to zero. Stablecoin quotes treated as nominal USD. Full discovered-contract coverage; 0 incomplete OI/funding bars. Not cross-exchange and at most one D1 family. [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json)","standalone_fr_obligation":{"fired_on":"2026-08-22","owed":true,"reports_outstanding":2,"trigger":"Prior market-wide short-liquidation tripwire, no post-trigger standalone report."},"stop_migration_ledger":[],"tier1_calendar":"US sessions Sep8,9,10,11,14 (Sep7 Labor Day closed): CPI Fri Sep11 08:30 ET is the only named tier1 release inside the next5 sessions. Payroll already released Sep4, next Oct2; PCE Sep30; FOMC Sep15-16 decision Sep16 14:00 ET, minutes last Aug19/next Oct7. PPI Thu Sep10 08:30 ET is also watched. Dates verified against BLS/BEA/Fed schedules; crypto trades every day. No automatic pre-event deployment pause."},"bear_case":"Three adverse factors: spot and futures selling across both horizons; oil/rate catalyst pressure; overhead supply83-86k in dated Glassnode research. Bull/bear3:3; collar ON. No resolved regime or guaranteed bottom claim.","bull_case":"Three constructive factors: positive ETF net flows; price above rising200dma; Z<1 cheap zone. IF CPI is cooler and spot flow turns positive on both24h/3d, THEN consolidation can resolve higher; falsifier is loss of major-MA support with persistent selling.","primary_action":{"rationale":"Score and gate/V floors fail independently; market weakness alone is not an FK unlock.","status":"AVAILABLE","value":"WAIT_NO_ADD"},"rationale":"### BTC Analyst Read\nThe old framework is doing its intended job: it buys fear/value only when enough conditions align. Sentiment71 and weekly RSI near60 describe a recovered tape, even while short-term flow deteriorates. Do not chase institutional-flow headlines or interpret every pullback as a new fallen knife. The opposing flow is context, not a secretly added score leg. The legacy report-machine/2 rules govern; SHADOW swing rules authorize nothing.\n\n### BTC completed-bar market flow\nBinance aggregate, single venue. Completed 4h bars through 2026-09-08T00:00:00.000Z; spot scope BTCFDUSD,BTCUSDC,BTCUSDT; perpetual scope BTCUSDC,BTCUSDT. CVD window Sep1-Sep8 rebased to zero. Stablecoin quotes treated as nominal USD. Full discovered-contract coverage; 0 incomplete OI/funding bars. Not cross-exchange and at most one D1 family. [snapshot](data/runs/20260908-0037-97ce583f/snapshot.json)\n\n| Metric | 24h | 3d | Full window/latest | Interpretation |\n| --- | --- | --- | --- | --- |\n| Spot CVD | $-36.40M | $-141.14M | $-123.17M | Sellers dominate; 24h imbalance -3.403%; 3d -4.963% |\n| Futures taker bid/ask delta | $-431.02M | $-447.00M | latest $8.45M | Sellers dominate; 24h imbalance -4.49%; same family as futures CVD |\n| Futures CVD | $-431.02M | $-447.00M | $-728.98M | Sellers dominate; 24h imbalance -4.49%; 3d -2.139% |\n| Aggregate OI | -0.587% | -1.959% | window -0.892%; latest O/H/L/C 9.94269B/9.94269B/9.86295B/9.86295B | Price and futures CVD down with OI down: deleveraging, not proven absorption. |\n| OI-weighted funding | mean 0.002469% | mean 0.001955% | latest 0.001782%; full mean 0.004798%; percentile 16.67 | Positive; interval-unverified, sign/relative-history only |\n\nresampled discrete snapshots; high/low are sampled observations, not continuous extrema. OI windows begin Aug31 20:00 UTC (43 bars), CVD Sep1 00:00 (42); full-window levels are not compared across unlike start times. Price24h -1.534%. Funding row uses settled rates carried into the completed bar; the next settlement at Sep8 00:00 is outside this panel. Fresh BTCUSDT/ETHUSDT last three settlements are positive; no funding capitulation credit.\n\n### BTC score, uncertainty and binding constraints\nLegs sentiment0 + momentum0 + valuation3 + capitulation1 HELD + holder0 = raw4; D1=0; mechanical/adjusted4, BTC half-up. Score without the stale liquidation carry would be 3; either reading leaves entries locked. The theoretical ceiling is20, no structural pin. Deep phases>=15/>=17 and compound<12 retain the stated vacuity labels over four distinct report dates; this changes no threshold. P1A/P1B score lines are LIVE. All four phases fail score plus gate breadth; per-row deficits are explicit. No D2 near-miss: score fails and V floor fails. Override evaluated: NOT APPLICABLE without a confirmed prior deployed tranche, and mechanical<15. No new zone, stop, or time-clock migration.\n\n### BTC Probability and EV audit\n3-30 day terminal scenario matrix. D4 weights20/40/25/15. Versus score0-5 baseline10/30/35/25: +10/+10/-10/-10 pp; above-rising200dma and ETF demand justify a less bearish distribution, conditional on CPI. Active downtrend NO; trend residual0. Rally20%<=50%. Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar. EV versus spot -3.18% alongside realized trailing14-calendar-day spot change +0.22% (Aug24 daily close to current).\n\n0.20 x 84,500.00 + 0.40 x 79,000.00 + 0.25 x 73,000.00 + 0.15 x 66,000.00 = $76,650.00. Return contributions: Rally +1.347pp, Range -0.086pp, Retest -1.948pp, Bear -2.495pp = -3.183%. The negative sign is driven chiefly by downside band distance; Range midpoint is below spot. This is risk-weighted scenario geometry, not a reliable directional forecast; it cannot carry the stance. Bands are scenario endpoints, not authorized buy ladders. Active downtrend NO; no terminal/path residual reconciliation is triggered. Even so, Range is a terminal band and a path through Retest/Bear remains possible. EV-floor inconsistency check not triggered (mechanical<15, F&G>15). Collar remains ON by balanced scorecard and demoted EV.\n\n### BTC Position, stops and exits\nPosition Reconciliation: unchanged from Aug22. Latest valid Aug15 export remains FRESH under event-driven policy, but BTC custody remains UNEXPLAINED. No quantity in either direction, no basis/PnL claim; not flat. 0% deployed/100% dry describes the framework plan, not the whole account. Stable balance$10,741.5780, including$2,948.9340 locked in orders; free/drawable$7,792.6440, shared across the account, not allocated separately to BTC and ETH. Futures collateral is not added to this cash balance. No asset book/equity sizing invented. No reliable phase-specific PnL or performance claim. Unexplained custody prevents selecting or sizing a live protective order, trim or exit. No level is absolutely best; a best-available control cannot be selected from unreconciled exposure. Recommendations are not executions; no order changed.\n\nPlanning only; no live stop asserted. Catastrophic 50000 strictly below deepest carried floor 54000: computed PASS. Compound requires >=2 weekly closes below 55000 AND mechanical<12. This score key is permissive; price remains binding. No migration, no removal, no extension.\n\nEXIT/TRIM audit (mechanical score only): campaign peak/drop cannot be determined from unreconciled/untagged exposure; do not infer a25% trim from the all-history peak. F&G>=75 seven-day AND weeklyRSI>70 is FALSE; valuationZ>3 FALSE; score<=3 AND gain>=40% score4>3, FALSE; ETF>=3%AUM trailing-month outflows FALSE (positive netflow). No verified protocol/narrative break found in the live search. No trim/exit execution claimed; remaining quantity unknown. Baseline stop/campaign clocks never reset by this defect. LIFO applies if a future verified trim fires.\n\n### BTC Discretion Ledger\nD1 2026-09-07:0, no size, no stop; negative adjustment considered for flow/oil risk and declined because the no-add stance already follows binding score/gates and no unscored two-family term is needed. Prior zero remains zero. D2 declined: not a gate-only near miss. D4 current20/40/25/15, analyst-set probabilities and conditional falsifiers in the matrix; no capital deployed and no realizedP&L. Prior Aug22 D4 negative EV contradicted by the current spot change; retired as a directional prediction, retained in the historical bias scorecard. Previous unresolved older calls are not represented as wins. Prior EV -0.98%; spot since prior +2.27%: prior sign contradicted. Recomputed from distinct-date machine reports: negative-sign streak 18 including current; 8/17 completed intervals correct (47.1%). Machine records lacking parseable spot/EV excluded as UNKNOWN, never read as a flip. The Aug22 prose claimed19 negative reports; that count is not reproducible from the available parseable machine history, so this is a labeled continuity correction rather than a reset. EV stays corroborative-only under the existing systematic-bias flag; it cannot carry WAIT or lift the collar.\n\n### BTC calendar and execution plan\nUS sessions Sep8,9,10,11,14 (Sep7 Labor Day closed): CPI Fri Sep11 08:30 ET is the only named tier1 release inside the next5 sessions. Payroll already released Sep4, next Oct2; PCE Sep30; FOMC Sep15-16 decision Sep16 14:00 ET, minutes last Aug19/next Oct7. PPI Thu Sep10 08:30 ET is also watched. Dates verified against BLS/BEA/Fed schedules; crypto trades every day. No automatic pre-event deployment pause.\n\n1. Keep all new FK phases locked; do not place a market buy from this report.\n2. Reconcile ledger custody before any position-specific stop/trim/cover action; preserve existing verified controls.\n3. Refresh after Sep11 CPI, or earlier if score/gates/price-zone conditions change; evaluate market-flow reversal on both24h and3d.\n4. Keep reserved phase tags inactive until a verified authorized fill. Canonical vocabulary: FK-P1A, FK-P1B, FK-P2, FK-P3, FK-OVR, FK-D1, FK-D2 and UNFRAMED. Report-specific phase tags below remain reserved; active_tags is empty.\n5. Complete the outstanding standalone FR analysis to discharge the Aug22 obligation.\n\nIF cooler inflation and renewed spot demand, THEN re-evaluate the upside scenario without skipping FK gates. IF oil/rates rise and support breaks, THEN reassess downside and independent exit rules. IF a phase unlocks away from its named zone, THEN leave its ladder unfilled.","summary":"WAIT / NO NEW ADD. BTC adjusted4/20, mechanical4, gates1/9 with V1. Greed and recovered momentum leave only valuation plus a flagged liquidation carry."},"out_of_scope":["No exchange orders placed, cancelled or changed.","No new strategy research or SHADOW-model activation.","No ledger repair or other ongoing research-code changes included."],"position":{"asset":"BTC","attribution":{"active_tags":[],"note":"No confirmed BTC quantity can be mapped to an FK phase.","status":"UNKNOWN"},"basis":{"avg_cost":null,"reason":"Unbacked disposals make basis non-derivable; no average cost, basis, unrealized PnL or ROI is quoted.","reliable":false,"total_cost":null},"custody":{"reason":"Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap.","status":"UNEXPLAINED"},"dry_powder":"10741.5780","futures":[],"pnl":{"realized":null,"reason":"Custody and basis defects prohibit a PnL claim.","status":"DATA_LIMITED","unrealized":null},"quantity":null,"reconciliation":"Position Reconciliation: unchanged from Aug22. Latest valid Aug15 export remains FRESH under event-driven policy, but BTC custody remains UNEXPLAINED. No quantity in either direction, no basis/PnL claim; not flat. 0% deployed/100% dry describes the framework plan, not the whole account.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"Unexplained custody prevents selecting or sizing a live protective order, trim or exit. No level is absolutely best; a best-available control cannot be selected from unreconciled exposure. Recommendations are not executions; no order changed.","status":"DATA_LIMITED","value":"NO_POSITION_CLAIM"},"required":true,"status":"DATA_LIMITED"},"report_id":"btc_fallen_knives_20260907_2055","risk_controls":{"carry":{"dry_powder_yield_pct":"3.76","note":"Cash has measurable T-bill opportunity value.","status":"AVAILABLE","veto":false},"concentration":{"note":"No addition; current BTC weight cannot be computed from a refused quantity claim.","planned_pct":"0","status":"DATA_LIMITED"},"ratchet":{"note":"No confirmed open tranche or auditable live stop; D6 migration ledger empty.","parameters_changed":false,"status":"NOT_APPLICABLE"},"stops":{"catastrophic":"50000","coherence":true,"compound":"55000 AND mechanical<12","deepest_zone_floor":"54000","note":"Planning only; no live stop asserted. Catastrophic 50000 strictly below deepest carried floor 54000: computed PASS. Compound requires >=2 weekly closes below 55000 AND mechanical<12. This score key is permissive; price remains binding. No migration, no removal, no extension.","status":"LOCKED"},"time_stops":{"note":"No confirmed authorized fill/time clock. Prior controls cannot be reset from a data defect; no checkpoint extension authorized. Fresh fill must carry a dated maximum hold and verified venue close.","status":"NOT_APPLICABLE"}},"run":{"prior_report_id":"btc_fallen_knives_20260822_0346","prior_report_sha256":"beee4a036e8f277ca8f660cedc675d290ceae536d63f524291252aab5875f94c","run_id":"20260908-0037-97ce583f","snapshot_id":"sha256:97ce583fbcd6677fd4b0c8e5998f784d67164e3cd89323bc39908a2101fbe8d9","tool_hashes":{"compute":"sha256:a79ebb055145873293e1dc430bafe940879de8f12f271bac03c8fa1a5187be4c","fetch":"sha256:49cd6075b09ec7a8b4ed24809f44e1c8fdb6bd6e9131bd71d29668d548f66a66","launcher":"sha256:a618e762039f581377fcb9470efe2d368a445734283644557bc4901eba927875","reporting_emitter":"sha256:3315b0042d182922879301f3efb1c8a70373eae378552740ba89aa2c13ba2243","snapshot":"sha256:28a6dabb2f37fa84238065b042bec8f78986e0793f32e628376b92f2217b0c21"}},"schema":"report-machine/2","score":{"adjusted":4,"caps":[{"field":"attainable_ceiling","reason":"No structural pins. Missing observations reduce confidence, not the theoretical rubric ceiling.","value":20},{"field":"line_states","reason":"Same truth over the last four distinct report dates for the deeper score bars and compound key; not a license to alter them.","value":["P1A >=8 LIVE-FALSE","P1B >=11 LIVE-FALSE","P2 >=15 VACUOUS-BLOCKING","P3 mechanical >=17 VACUOUS-BLOCKING","Override mechanical >=15 VACUOUS-BLOCKING; still evaluated","Compound mechanical <12 VACUOUS-PERMISSIVE; price-gated, prospective only"]}],"discretion":0,"legs":{"capitulation":1,"holder":0,"momentum":0,"sentiment":0,"valuation":3},"mechanical":4,"penalties":[],"raw":4,"rounding":"half-up"},"sources":{"CLF":{"as_of":"2026-09-08T00:28:44+00:00","kind":"live data","name":"Yahoo CLF","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1mo&interval=1d"},"DJI":{"as_of":"2026-09-04T13:30:00+00:00","kind":"live data","name":"Yahoo DJI","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://query1.finance.yahoo.com/v8/finance/chart/%5EDJI?range=1mo&interval=1d"},"altseason":{"as_of":"2026-09-08","kind":"live data","name":"BlockchainCenter altcoin season index","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.blockchaincenter.net/altcoin-season-index/"},"bea":{"as_of":"2026-09-08","kind":"official calendar","name":"BEA release calendar","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.bea.gov/news/schedule/full"},"binance":{"as_of":"2026-09-08T00:00:00Z","kind":"exchange","name":"Binance completed daily candles and funding","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=100"},"bls":{"as_of":"2026-09-08","kind":"official calendar","name":"BLS September calendar","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.bls.gov/schedule/2026/09_sched.htm"},"coinmetrics":{"as_of":"2026-09-06","kind":"on-chain","name":"Coin Metrics Community daily on-chain API","note":"MVRV-Z reconstructed; flash data can revise. True LTH unavailable.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"},"correlation":{"as_of":"2026-09-04","kind":"computed","name":"Binance daily closes / Yahoo S&P500","note":"Aligned completed session log returns; Java compute corr.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC"},"cpi":{"as_of":"2026-08-12","kind":"official release","name":"BLS July CPI","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.bls.gov/news.release/cpi.htm"},"farside":{"as_of":"2026-09-04","kind":"fund flows","name":"BTC ETF flow primary full table","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://farside.co.uk/bitcoin-etf-flow-all-data/"},"fed":{"as_of":"2026-09-08","kind":"official calendar","name":"Federal Reserve FOMC calendar","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"},"fedrate":{"as_of":"2026-07-29","kind":"official release","name":"Federal Reserve July policy decision","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.federalreserve.gov/newsevents/pressreleases/monetary20260729a.htm"},"glassnode":{"as_of":"2026-09-02","kind":"on-chain research","name":"Glassnode Doubt at the Boundaries","note":"Dated structural context; not a current LTH30d measurement.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://research.glassnode.com/the-week-onchain-week-35-2026/"},"hashribbon":{"as_of":"2026-09-06","kind":"on-chain","name":"Glassnode Hash Ribbon","note":"30d911.776EH/s >60d906.546EH/s, but current buy-event/crossover timestamp not confirmed.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://studio.glassnode.com/charts/indicators.HashRibbon?a=BTC"},"jobs":{"as_of":"2026-09-04","kind":"official release","name":"BLS August jobs","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.bls.gov/news.release/empsit.nr0.htm"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"ledger","name":"Newest valid ledger export","note":"FRESH event-driven; holdings_as_of 2026-07-05. Both custody states UNEXPLAINED. No quantity or PnL claim.","retrieved_at":"2026-09-08T00:55:21Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"liquidations":{"as_of":"2026-09-08","kind":"attempted live data","name":"CoinGlass liquidation page","note":"Unpopulated page reports $0/undefined placeholders; not a measured zero. No valid 90d percentile or 30d sigma distribution.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.coinglass.com/liquidations"},"news":{"as_of":"2026-09-07","kind":"news","name":"Oil and Fed risk context","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://ca.investing.com/news/cryptocurrency-news/bitcoin-slips-below-80k-as-fed-hike-bets-oil-surge-weigh-4829706"},"pce":{"as_of":"2026-08-26","kind":"official release","name":"BEA July PCE","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.bea.gov/news/2026/personal-income-and-outlays-july-2026"},"prior":{"as_of":"2026-08-22","kind":"report history","name":"Prior FK report","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"reports/btc_fallen_knives_20260822_0346.json"},"realyield":{"as_of":"2026-09-03","kind":"official series","name":"FRED 10y real yield","note":"Web fallback after Java HTTP2 and CSV failures.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://fred.stlouisfed.org/series/DFII10"},"scbank":{"as_of":"2026-09-03","kind":"bank primary release","name":"Standard Chartered UAE launch","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.sc.com/en/press-release/standard-chartered-becomes-first-global-systemically-important-bank-g-sib-to-launch-institutional-bitcoin-and-ether-spot-trading-in-the-uae/"},"snapshot":{"as_of":"2026-09-08T00:37:01.959Z","kind":"computed","name":"Live deterministic market snapshot","note":"Fetched with clean committed Java toolchain30262ac; a local launcher-only flush/argument fix enables complete receipts. No arithmetic or scoring code changed; source timestamps preserved.","retrieved_at":"2026-09-08T00:55:21Z","url":"data/runs/20260908-0037-97ce583f/snapshot.json"},"sosovalue":{"as_of":"2026-09-04","kind":"fund data relay","name":"Independent ETF flow corroboration","note":"SoSoValue direct pages failed. ETH aggregator AUM fails holdings-times-price consistency and is excluded.","retrieved_at":"2026-09-08T00:55:21Z","url":"https://coinpaper.com/35390/crypto-trading-update-bitcoin-etfs-pull-in-987m-as-btc-holds-near-80k"},"staking":{"as_of":"2026-09-08","kind":"live data","name":"Validator Queue / beaconcha.in","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://www.validatorqueue.com/"},"us2y":{"as_of":"2026-09-03","kind":"official series","name":"FRED 2y nominal yield","note":"","retrieved_at":"2026-09-08T00:55:21Z","url":"https://fred.stlouisfed.org/series/DGS2"}},"stale_inputs":["Capitulation1 is a flagged carry of the prior leg, not a current liquidation point; one-report debt since Aug22 live event. Positive funding and ETF flow measured fresh.","True LTH half holds prior0; provider-gated despite renewed search, so continuing debt is explicitly disclosed.","Coin Metrics Z is a fresh computed reconstruction using full history, not stale proprietary data; revisable daily observations.","Ledger dates are audit metadata, not expiry. Position CLI output revalidated after the local launcher flush fix; required custody/basis fields also cross-checked against the intact canonical export."],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FK-P1A-BTC-20260907-2055","decision":"LOCKED","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FK-P1B-BTC-20260907-2055","decision":"LOCKED","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FK-P2-BTC-20260907-2055","decision":"LOCKED","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FK-P3-BTC-20260907-2055","decision":"LOCKED","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FK-P1A-BTC-20260907-2055","FK-P1B-BTC-20260907-2055","FK-P2-BTC-20260907-2055","FK-P3-BTC-20260907-2055"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-09-08T00:37:01.959Z","generated_at":"2026-09-08T00:55:21Z","report_at":"2026-09-08T00:55:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Score and gate/V floors fail independently; market weakness alone is not an FK unlock.","status":"AVAILABLE","value":"WAIT_NO_ADD"},"statement":"BTC 4/20; gates1/9, V1. No new accumulation. Greed/recovered weekly momentum and insufficient confirmation block every phase. Position-level controls remain DATA_LIMITED.","status":"WAIT"},"watchlist":[{"item":"CPI","status":"AVAILABLE","trigger":"Friday 2026-09-11 08:30 ET. IF hotter inflation reprices yields/oil higher, THEN increase downside risk assessment; IF cooler with positive completed spot CVD, THEN reassess Range/Rally."},{"item":"PPI","status":"AVAILABLE","trigger":"Thursday 2026-09-10 08:30 ET; intermediate inflation checkpoint."},{"item":"FOMC","status":"AVAILABLE","trigger":"Wednesday 2026-09-16 14:00 ET decision, outside next5 sessions but inside30-day horizon."},{"item":"P1A relight","status":"AVAILABLE","trigger":"Adjusted>=8, total>=3, V>=2; current deficits 4 points/2 gates/1V. Fill only inside the carried P1A zone after every condition holds."},{"item":"Position reconciliation","status":"DATA_LIMITED","trigger":"Resolve custody mismatch before selecting or sizing any protective/exit order."},{"item":"Standalone FR obligation","status":"DATA_LIMITED","trigger":"Aug22 obligation remains owed, reports_outstanding2. Complete standalone report to discharge; inline companion does not suffice."}]}
```
