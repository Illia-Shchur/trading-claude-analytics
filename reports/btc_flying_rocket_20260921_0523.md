# BTC — Flying Rocket — 2026-09-21 05:23

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | BTC · Flying Rocket |
| Report time | 2026-09-21 05:23 (America/New_York) |
| Verdict | • STAND_DOWN — No new BTC short: no channel is live; known score floor 4/20 (range 4–7), 0/8 gates, and all phases stay locked. Repair unexplained custody before any position claim or sizing. |
| Adjusted score | **4/20** (mechanical 4, raw 4) |
| Confirmation gates | 0/8 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable BTC |
| Deployment | 0% deployed · 50% dry |
| Primary action | **STAND_DOWN** — No channel, 0/8 gates and incomplete score inputs. Stand down and fix custody reconciliation; do not short the squeeze. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $83,782.00/BTC | ✅ AVAILABLE | — | 2026-09-21T08:55:22.411Z | Canonical median of three synchronized CoinGecko, Binance and Coinbase live quotes.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| All-time high | 126,080 USD lifetime ATH | ✅ AVAILABLE | — | 2025-10-06 | CoinGecko lifetime ATH; one-year routing high is recorded separately.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Drawdown from ATH | 33.61% | ✅ AVAILABLE | — | 2026-09-21T08:55:22.411Z | One-year high $126198.07; routing input.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| ADR-5 | $2,051.42 | ✅ AVAILABLE | — | 2026-09-20 | Five complete UTC sessions Sep16–20; partial Sep21 excluded. 1.5×ADR is the initial noise floor only; no stop is authorized.<br>Sources: [binance_daily](https://api.binance.com/api/v3/klines) |
| Borrow | — | ⚠️ DATA_LIMITED | — | 2026-09-21T08:55:22.411Z | No venue-specific spot-borrow quote was verified for a new position; this report considers only perp funding carry.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Coinbase premium | 0.002% | ✅ AVAILABLE | — | 2026-09-20 | Last three completed daily prints retained in the gate basis.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Correlation spx | 0.2981738853 Pearson daily log returns | ✅ AVAILABLE | — | 2026-09-18 | 30 aligned closes / 29 return observations; corr <0.7 so risk-on surcharge is off.<br>Sources: [corr](https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC) |
| Daily rsi | 69.49 RSI-14 | ✅ AVAILABLE | — | 2026-09-21T08:55:22.411Z | Completed daily history; context only for Channel A.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Funding annualized | 6.76% | ✅ AVAILABLE | — | 2026-09-21T08:55:22.411Z | Positive funding pays a short; carry income is floored to zero for the entry filters.<br>Sources: [binance_funding](https://fapi.binance.com/fapi/v1/fundingRate) |
| Market flow panel | 114,786,194.28 USD spot CVD over 3 days | ✅ AVAILABLE | — | 2026-09-21T08:00:00.000Z | Single-venue Binance aggregate, 42 completed four-hour bars through 2026-09-21T08:00:00.000Z. Spot CVD: 24h $51.5M, 3d $114.8M, full window $207.8M. Futures taker delta/CVD: 24h $176.3M, 3d $408.9M, full-window CVD $-346.3M. OI: +2.085% 24h, +6.541% 3d; sampled latest $10.583B. OI-weighted funding latest raw fraction 0.00004503 per interval (do not annualize aggregate); prior-window percentile 14.29. Price rose 1.756% over 24h and 5.074% over the full flow window. Panel scope is Binance only, not cross-exchange; these linked flow/price/OI observations are one context family, not separate score votes.<br>Sources: [binance_flow](https://api.binance.com/) |
| Oi 90d | 1 indicator: 1 = within 5% of 90-day high | ✅ AVAILABLE | — | 2026-09-20 | 90d archive available; single venue, not market-wide. This OI condition does not independently create a score credit.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Options skew | — | ⚠️ DATA_LIMITED | — | 2026-09-21 | True 25-delta skew / put-call ratio not verified. Any moneyness-based surface statistic is not substituted for 25-delta; structural-vulnerability input remains unknown.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Weekly RSI-14 | 58.69 RSI-14 | ✅ AVAILABLE | — | 2026-09-14 | Completed weekly closes; live week excluded.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |

**Regime:** — — Bounce age sessions: 38; Bounce pct: 34.08%; Channel: none; Ma200: 70568.93; Ma200 slope20 pct: 1.58%; Ma50: 73614.35; Price vs ma200 pct: 18.72%; Stall confirmation: No

### Spot reconciliation

**✅ AVAILABLE** — Median of three synchronized independent sources; spread 0.028%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko bitcoin | $83,782/BTC | ✅ LIVE | [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Binance BTCUSDT | $83,788.54/BTC | ✅ LIVE | [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Coinbase BTC-USD | $83,765.26/BTC | ✅ LIVE | [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Yahoo BTC-USD BTC-USD | $83,765/BTC | • EXCLUDED | [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Kraken XBTUSD | $83,766.5/BTC | • EXCLUDED | [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |

> Small simultaneous dispersion, not staleness-driven. Yahoo frozen daily close and Kraken unknown-timestamp quote excluded. All three accepted quotes inside two-hour window. No EV sign-flip stress required at spread<=0.5%.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Derivatives | positive funding / OI near 90d high Binance derivatives + single-venue market-flow panel | ✅ AVAILABLE | MEDIUM | 2026-09-21T08:00:00.000Z | Single-venue Binance aggregate, 42 completed four-hour bars through 2026-09-21T08:00:00.000Z. Spot CVD: 24h $51.5M, 3d $114.8M, full window $207.8M. Futures taker delta/CVD: 24h $176.3M, 3d $408.9M, full-window CVD $-346.3M. OI: +2.085% 24h, +6.541% 3d; sampled latest $10.583B. OI-weighted funding latest raw fraction 0.00004503 per interval (do not annualize aggregate); prior-window percentile 14.29. Price rose 1.756% over 24h and 5.074% over the full flow window. Panel scope is Binance only, not cross-exchange; these linked flow/price/OI observations are one context family, not separate score votes. Mean annualized settled Binance funding 6.76%; positive means long holders pay shorts. No negative interval in the current analyzed run and no sustained-negative run; no squeeze penalty. True 25-delta options skew and market-wide put/call ratio are not available; no option proxy is scored.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json), [binance_flow](https://api.binance.com/), [binance_funding](https://fapi.binance.com/fapi/v1/fundingRate) |
| Etf flows | 1,762.1 USD millions trailing calendar month | ✅ AVAILABLE | HIGH | 2026-09-18 | Trailing calendar month Aug21–Sep18 net +$1,762.1M; September MTD +$313.4M; Sep14–18 +$6.1M after Sep8–11 −$462.7M, with Sep18 +$433.0M. Inflows decelerated materially from the two preceding strong weeks, earning the known single §4A distribution sub-leg; because price remains >20% below its own one-year high, this is also capitulation-context for gate 5 and cannot confirm a short.<br>Sources: [farside_btc](https://farside.co.uk/bitcoin-etf-flow-all-data/) |
| Funding | 6.76% | ✅ AVAILABLE | HIGH | 2026-09-21T08:55:22.411Z | Mean annualized settled Binance funding 6.76%; positive means long holders pay shorts. No negative interval in the current analyzed run and no sustained-negative run; no squeeze penalty.<br>Sources: [binance_funding](https://fapi.binance.com/fapi/v1/fundingRate) |
| Macro | mixed rates, FX, equities and event calendar | ✅ AVAILABLE | MEDIUM | 2026-09-21 | Fed raised 25bp on Sep16 to 3.75–4.00%; August CPI 3.4% headline/2.4% core y/y; July PCE 3.7%/3.3%; FRED 10y real yield 2.61% and 2y 4.67% on Sep17. VIX14.93 (5-observation change −12.69%), DXY100.28 (+0.82%), Brent97.52 (−7.72%); S&P500 Sep18 close 7650.5 (5d −0.08%), Nasdaq Composite 26522.54 (+0.72%), breadth 49.5% above 200dma on Sep17. No CPI, NFP, PCE, FOMC decision or minutes Sep21–25; next PCE Sep30, employment Oct2. These are mixed macro context, not a scored short signal.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json), [fed](https://www.federalreserve.gov/newsevents/pressreleases/monetary20260916a.htm), [cpi](https://www.bls.gov/news.release/archives/cpi_09112026.htm), [pce](https://www.bea.gov/news/2026/personal-income-and-outlays-july-2026), [real_yield](https://fred.stlouisfed.org/series/DFII10), [us2y](https://fred.stlouisfed.org/series/DGS2), [calendar](https://www.bea.gov/news/schedule/full) |
| Momentum | 58.69 completed weekly Wilder RSI-14 | ✅ AVAILABLE | HIGH | 2026-09-14 | Weekly RSI-14 58.69; BTC earns 0 (<60), ETH earns 1 (60–65 band). Partial week excluded.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Onchain | MVRV-Z 0.914; reserves +0.372%/30d; net exchange flow $-3.438B Coin Metrics reconstructed on-chain daily | ✅ AVAILABLE | MEDIUM | 2026-09-20 | MVRV-Z 0.914. Exchange reserves 2,721,135.941 BTC (+0.372%/30d); 30d net exchange inflow $-3.438B, which is net outflow. True LTH supply/profit-taking is provider-gated; tagged large-address inflows are unavailable and neither is assigned a short score credit.<br>Sources: [coinmetrics](https://community-api.coinmetrics.io/v4/timeseries/asset-metrics), [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Regime | none channel routing | ✅ AVAILABLE | HIGH | 2026-09-21 | Price $83,782.00 is 33.61% below the trailing one-year high and 18.72% above the 200dma $70,568.93; that 200dma is rising 1.58% over 20 sessions. This is neither a Channel-A top (not within 20% of high) nor a Channel-B downtrend (price is above a rising 200dma).<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| Sentiment | 70.67 Fear and Greed 3-day average | ✅ AVAILABLE | HIGH | 2026-09-21 | Alternative.me daily prints 70,71,71; average 70.67; greed, not ≥80 for seven days.<br>Sources: [sentiment](https://api.alternative.me/fng/?limit=90) |
| Spot | $83,782.00 | ✅ AVAILABLE | HIGH | 2026-09-21T08:55:22.411Z | Median of three timestamped CoinGecko, Binance and Coinbase quotes; spread below 0.5%.<br>Sources: [snapshot](data/runs/20260921-0855-a7e2031b/snapshot.json) |

**Data gaps:** 6 · **stale inputs:** 0 · **out of scope:** 4

**Data gaps**

- **BTC custody reconciliation** — ⚠️ DATA_LIMITED — No asset quantity in either direction, no basis/PnL/ROI claim, and no sizing against this position; fix the ledger.
- **True LTH distribution and profit-taking rate** — — NOT_COVERED — Distribution sub-leg (a) and gate 7 remain unknown; no age-band proxy.
- **Tagged large-address exchange flow** — — NOT_COVERED — Distribution sub-leg (b) cannot be confirmed from aggregate 30d exchange flows; remains unknown.
- **True 25-delta options skew / put-call ratio** — — NOT_COVERED — Structural-vulnerability sub-leg (b) remains unknown; moneyness skew is not a substitute.
- **Rotation trend and BTC dominance direction** — ⚠️ DATA_LIMITED — Gate 9 uncounted; Altcoin Season Index level 51 is available but its crossing/trend is not.
- **Venue-specific spot borrow quote** — ⚠️ DATA_LIMITED — No spot-borrow quote verified; positive perp funding is disclosed and no new position is recommended.

**Out of scope**

- No order execution or account change was performed.
- Channel NONE is not an entry or target signal.
- Binance market-flow context is single-venue and not a market-wide tally.
- Unknown rubric inputs are excluded from the known score floor and not replaced by proxies.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Distribution | 1 | 3 | Mechanical component |
| Euphoria | 3 | 5 | Mechanical component |
| Momentum | 0 | 4 | Mechanical component |
| Valuation | 0 | 5 | Mechanical component |
| Vulnerability | 0 | 3 | Mechanical component |

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
| Route | none | 33.61% below one-year high and price above rising 200dma; Channel A top regime and Channel B bear-continuation precondition both fail. |
| Hypothetical channel a ceiling | 8 | §2.5 would cap Channel A at 8/20 when >20% below the high, but route is NONE; cap.applied=false and that cap is not included in arithmetic. |
| Squeeze trap | 0 | No negative funding run or current single-interval trigger; no penalty. |
| Correlation gate surcharge | 0 | 30-session daily log-return corr 0.298<0.7; no surcharge. |
| Known score range | 4–7 (known-input floor to missing-input ceiling) | Known predicates only are counted. Distribution LTH/profit and large-address labels, plus true 25-delta skew/PCR, are not all observable; do not promote the floor to a complete score. |

### Confirmation gates — 0/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | FAIL — F&G three-day average 70.67; no seven-day daily streak at ≥80. |
| 2 | • NOT PASSED | FAIL — completed-week RSI-14 58.69, below >70. |
| 3 | • NOT PASSED | FAIL — reconstructed MVRV-Z 0.914, below 3. |
| 4 | • NOT PASSED | FAIL — mean perp funding 6.76% annualized, below >25% for three intervals. |
| 5 | • NOT PASSED | WARNING/capitulation-context — Trailing calendar month Aug21–Sep18 net +$1,762.1M; September MTD +$313.4M; Sep14–18 +$6.1M after Sep8–11 −$462.7M, with Sep18 +$433.0M. Inflows decelerated materially from the two preceding strong weeks, earning the known single §4A distribution sub-leg; because price remains >20% below its own one-year high, this is also capitulation-context for gate 5 and cannot confirm a short. |
| 6 | • NOT PASSED | FAIL — Coinbase Premium was positive on each of the three completed days Sep18–20. |
| 7 | • NOT PASSED | UNMEASURED — true LTH supply and profit-taking rate are provider-gated; no age-band proxy is used. |
| 8 | • N/A | N/A — 33.61% below own one-year high (>15%); breadth divergence is top-coincident and structurally inapplicable. |
| 9 | • NOT PASSED | UNKNOWN — Altcoin Season Index is 51/100, but its rising-through-50 path and BTC dominance trend were not measured; gate stays uncounted. |

### Unlock thresholds

| Phase | Score / gate threshold |
| --- | --- |
| P1A | 3 |
| P1B | 5 |
| P2 | 6 |
| P3 | 8 |




## 4. Probability matrix and expected value

| Scenario | Probability | Low | High | Midpoint | Rationale |
| --- | --- | --- | --- | --- | --- |
| Recovery extends | 50% | $84,480/BTC | $91,520/BTC | $88,000/BTC | Score-band baseline; ranges are scenario bands, not price targets or an authorization. Upside path remains supported by positive spot/futures flow and squeeze risk. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance. |
| Range / base | 30% | $78,720/BTC | $85,280/BTC | $82,000/BTC | Score-band baseline; ranges are scenario bands, not price targets or an authorization. Requires the stated trend / support development; the no-channel route stays binding. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance. |
| Mean reversion | 15% | $73,440/BTC | $79,560/BTC | $76,500/BTC | Score-band baseline; ranges are scenario bands, not price targets or an authorization. Requires the stated trend / support development; the no-channel route stays binding. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance. |
| Bear reversal | 5% | $67,200/BTC | $72,800/BTC | $70,000/BTC | Score-band baseline; ranges are scenario bands, not price targets or an authorization. Requires the stated trend / support development; the no-channel route stays binding. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $83,575.0/BTC |
| EV versus spot | -0.25% |

> 50x88000 + 30x82000 + 15x76500 + 5x70000 = 83575.00. Directional short EV +0.25%; true positive-funding carry adds 0.39% over 21 days, for total short EV +0.64%. Carry income is floored to zero for the +3% minimum-edge and 40%-of-target checks; gate EV +0.25% fails. Corroborative only because no channel is live.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 50% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 5% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1A-BTC-20260921-0523 | Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set. |
| 1B | 10% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1B-BTC-20260921-0523 | Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set. |
| 2 | 15% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-2-BTC-20260921-0523 | Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set. |
| 3 | 20% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-3-BTC-20260921-0523 | Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set. |

## 6. Position, custody, and execution controls

| Position field | Value |
| --- | --- |
| Status | ⚠️ DATA_LIMITED |
| Asset | BTC |
| Quantity | — |
| Dry powder | $7,792.6440 |
| Basis reliable | no |
| Average cost | — |
| Total cost basis | — |
| Custody | • UNEXPLAINED |
| Attribution | ❔ UNKNOWN |
| Active tags | None |

### Custody reconciliation

| Field | Value |
| --- | --- |
| Reason | The live balance and the fill replay disagree, and neither recorded withdrawals nor a migration seed accounts for the gap. This is a data defect — an unread wallet, an uncovered venue, or an incomplete backfill — not a position. Do NOT report a figure for this asset in either direction; fix the ledger first. |
| Status | • UNEXPLAINED |

### Cost basis

| Field | Value |
| --- | --- |
| Avg cost | — |
| Reason | Custody is UNEXPLAINED and the basis is not reliable; no average cost, total basis, PnL or ROI is stated. |
| Reliable | No |
| Total cost | — |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Note | No framework short attribution is corroborated. Custody is UNEXPLAINED; do not infer a flat position. |
| Status | ❔ UNKNOWN |

### Position P&L

| Field | Value |
| --- | --- |
| Reason | Unexplained custody prohibits any position PnL claim. |
| Status | ⚠️ DATA_LIMITED |
| Unrealized | — |

> **Position reconciliation:** Snapshot generated 2026-08-15T09:30:02.628253Z under event-driven validity. BTC custody is UNEXPLAINED. Hard Rule 8: report no asset quantity, basis, or PnL in either direction; reconcile ledger before sizing. Ledger does not corroborate a tagged FR short; this is not a claim of flat exposure.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| ⚠️ DATA_LIMITED | yes | **STAND_DOWN** — Snapshot generated 2026-08-15T09:30:02.628253Z under event-driven validity. BTC custody is UNEXPLAINED. Hard Rule 8: report no asset quantity, basis, or PnL in either direction; reconcile ledger before sizing. Ledger does not corroborate a tagged FR short; this is not a claim of flat exposure. |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Carry veto | No |
| Funding annualized pct | 6.76% |
| Gate carry ev pct | 0.00% |
| Minimum edge pass | No |
| Status | ✅ AVAILABLE |
| True carry ev pct 21d | 0.39 |

### Concentration

| Field | Value |
| --- | --- |
| Channel a asset cap pct | 50% |
| Planned pct | 0% |
| Status | ⚠️ DATA_LIMITED |
| Total short book cap pct | 50% |

### Ratchet

| Field | Value |
| --- | --- |
| Reason | No independently corroborated, attributable BTC FR tranche or auditable stop; custody remains unexplained. |
| Status | ⚠️ DATA_LIMITED |

### Stops

| Field | Value |
| --- | --- |
| ADR-5 | 2051.42 |
| Channel a 1a ceiling pct | 8.00% |
| Initial floor pct | 3.67% |
| Note | Informational ADR floor only; channel NONE means no entry, price stop, or stop ratchet is set. |
| Status | 🔒 LOCKED |

### Time stops

| Field | Value |
| --- | --- |
| P1a days | 21 |
| P1b days | 28 |
| P2 days | 35 |
| P3 days | 49 |
| Status | 🔒 LOCKED |

## 7. Analyst rationale

**Summary:** BTC: channel NONE; known score floor 4/20 with disclosed range 4–7; 0/8 gates. No new FR tranche. Stand down and repair custody records before position claims.

**Bull case:** Price is 18.72% above a rising 200dma; BTC and ETH spot/futures flow is positive across 24h and 3d, and the fresh marketwide liquidation report is short-heavy. IF price continues to hold above the rising average, THEN the no-channel route remains. (Falsifier: daily close below the current 200dma followed by a negative 20-session slope.)

**Bear case:** The rebound is extended: 40-session low-to-current bounce 34.08%, daily RSI 69.49, and Binance OI is within5% of its 90d high. The current squeeze/liquidation and positive flow impulse can still reverse. IF price closes below $70,568.93 and the 200dma slope turns negative, THEN re-run for Channel B; until then no short.

**Rationale:** ROUTE — BTC is 33.61% below its trailing one-year high, but 18.72% above a rising 200dma (slope +1.58% over 20 sessions). Channel A's top condition and Channel B's downtrend condition both fail: NONE. The hypothetical Channel-A cap of 8/20 is not applied arithmetically in this route.
> SCORE — Known legs: euphoria 3 (F&G 70.67), momentum 0 (weekly RSI 58.69), valuation 0 (MVRV-Z 0.914), distribution known minimum 1 (ETF flows decelerated/reversed), structural vulnerability known minimum 0. Mechanical and adjusted floor 4/20; plausible data-limited interval 4–7. True LTH/profit taking, tagged large-address flows and 25d skew/PCR are not inferred. No squeeze penalty, discretion 0, correlation surcharge OFF (30-session corr 0.298).
> GATES — 0/8 passed; gate8 N/A because asset is >15% below its own ATH. Converted floors are 3/5/6/8. Gate 5/6 reads are warning/capitulation-context and cannot confirm distribution; LTH is unmeasured; rotation trend is unknown. No phase can unlock because no channel is live.
> FLOWS — Single-venue Binance aggregate, 42 completed four-hour bars through 2026-09-21T08:00:00.000Z. Spot CVD: 24h $51.5M, 3d $114.8M, full window $207.8M. Futures taker delta/CVD: 24h $176.3M, 3d $408.9M, full-window CVD $-346.3M. OI: +2.085% 24h, +6.541% 3d; sampled latest $10.583B. OI-weighted funding latest raw fraction 0.00004503 per interval (do not annualize aggregate); prior-window percentile 14.29. Price rose 1.756% over 24h and 5.074% over the full flow window. Panel scope is Binance only, not cross-exchange; these linked flow/price/OI observations are one context family, not separate score votes.
> ETF — Trailing calendar month Aug21–Sep18 net +$1,762.1M; September MTD +$313.4M; Sep14–18 +$6.1M after Sep8–11 −$462.7M, with Sep18 +$433.0M. Inflows decelerated materially from the two preceding strong weeks, earning the known single §4A distribution sub-leg; because price remains >20% below its own one-year high, this is also capitulation-context for gate 5 and cannot confirm a short.
> MACRO — Fed target 3.75–4.00% after Sep16 hike; August CPI 3.4%/2.4% y/y, July PCE 3.7%/3.3%; no CPI/NFP/PCE/FOMC decision/minutes in Sep21–25, next PCE Sep30.
> EV — Scenario EV $83,575.00 versus spot $83,782.00; directional short EV +0.25%, true carry +0.39% over21d, true total +0.64%. Carry income floors to zero for gates; +3% minimum-edge fails. EV is corroborative only because the route is none.
> POSITION — Ledger snapshot remains event-driven fresh, but BTC custody is UNEXPLAINED. No coin quantity, basis or PnL is stated. Drawable stablecoin cash is $7792.6440; $2948.9340 is locked and not drawable. BTC ledger does not corroborate an FR-tagged short; unresolved custody prevents calling the account flat.

**Primary action:** **STAND_DOWN** — No channel, 0/8 qualifying gates, incomplete FR score interval, and unexplained custody. Stand down; do not call the position flat or size a new short.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Prior forecast grade | Latest prior report is Sep11, not Aug21. Its mechanical/adjusted score was 2/20 with no active channel. Spot increased 7.67% from $77,814.24 to $83,782.00; prior scenario EV was $77,872.60. Current price lies in the prior Recovery extends band. This interim observation supports the recovery mode but is not a completed 21-day forecast grade; the original horizon has not elapsed. Today no channel is active. No claim is made about every intermediate daily trigger. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-09-21 | S1 | — | — | — | — | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | fallen_knives · 4/20 · 1 gates | Same-snapshot computed FK 4/20, 1/9 gates. The paired FK machine report is the source; FR input uncertainty limits any stronger inverse-consistency claim. |
| Cross-validation | ⚠️ DATA_LIMITED | UNVERIFIED — FR score interval not fully observed; no both-≥12 trigger | FK score 4; FR known floor 4 with disclosed ceiling 7. FR unknown distribution and options inputs prevent claiming a precise inverse relationship. Both ranges remain below 12, so the Hard Rule 5 both-≥12 inconsistency trigger is not present; relationship is marked unverified. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| Channel routing | ✅ AVAILABLE | Re-evaluate only after a close below $70,568.93 with a falling 200dma, or a return to within20% of the one-year high. |
| ETF-flow regime | ✅ AVAILABLE | Continue to monitor completed daily ETF flows; current flows are capitulation-context and cannot confirm short distribution while >20% off high. |
| Squeeze / positioning | ✅ AVAILABLE | Reassess after the short-heavy liquidation event and with a fresh funding/OI snapshot; current flow panel is single venue. |
| Ledger repair | ⚠️ DATA_LIMITED | Reconcile BTC custody and fills before quantity/PnL claims. |
| FK force-cover | ✅ AVAILABLE | Same-timestamp FK score is 4, below the >=12 force-cover cross-check; this does not override ledger or route rules. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-09-21 | Short-side liquidation wave | ✅ AVAILABLE | Gate/ChainCatcher citing CoinGlass reports an approximate $401M in 24h crypto liquidations, $241M short vs $160M long; low-confidence squeeze/cover-overhang context, not distribution evidence. |
| 2026-09-18 | ETF flow inflection | ✅ AVAILABLE | ETF weekly flow +6.1M after prior sustained inflows; price remains >20% below the one-year high so this cannot count as short confirmation. |
| 2026-09-16 | Macro calendar | ✅ AVAILABLE | FOMC raised target range 25bp; next scheduled major releases are PCE Sep30 and employment Oct2, outside the Sep21–25 five-session window. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| No channel is live | Re-run routing if BTC closes below $70,568.93 while the 200dma slope turns negative, or price returns within 20% of its one-year high and distribution evidence is re-evaluated. | ✅ AVAILABLE |
| No FR short is authorized | A new report must establish a live channel, a fully observed score at its phase line, converted gate floor, all veto/preflight checks and Total Short EV above +3%. | ✅ AVAILABLE |
| Ledger position cannot be called flat | Reconcile BTC custody, external transfers and fills; do not infer flatness from UNEXPLAINED custody. | ⚠️ DATA_LIMITED |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.adjusted | 2 | 4 | Known diagnostic floor increased with current euphoria and ETF deceleration; unresolved inputs remain explicit, no active channel. |
| Channel | none | none | Price remains above rising 200dma and more than20% below one-year high; no new short. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |
| Options vulnerability 25d skew or put call | True 25-delta options skew / put-call ratio | none | Not available; moneyness-based surface information is not a 25-delta substitute. |
| Distribution lth and large address | True LTH supply/profit taking and tagged large-address inflows | none | Provider-gated/unavailable; no cohort or exchange-flow proxy is promoted into the score. |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| altseason | BlockchainCenter Altcoin Season Index | rotation index | 2026-09-21 | 2026-09-21T09:20:25Z | Index 51/100; direction over the required lookback was not independently verified.<br>[Open source](https://www.blockchaincenter.net/altcoin-season-index/) |
| binance_daily | Binance BTCUSDT/ETHUSDT OHLC | completed daily candles | 2026-09-20 | 2026-09-21T08:55:22.411Z | Five complete sessions Sep16–20 used for ADR; partial Sep21 excluded.<br>[Open source](https://api.binance.com/api/v3/klines) |
| binance_flow | Binance aggregate market-flow panel | completed-bar derivative and spot flow | 2026-09-21T08:00:00Z | 2026-09-21T08:55:22.411Z | 42 completed 4-hour bars, Sep14 08:00–Sep21 08:00 UTC; single venue, not market-wide; stable-USD quote assets treated as nominal USD.<br>[Open source](https://api.binance.com/) |
| binance_funding | Binance USD-M settled funding rates | derivatives funding | 2026-09-21T08:55:22.411Z | 2026-09-21T08:55:22.411Z | 45 recent intervals; market-rate sign convention: positive funding is income to a short.<br>[Open source](https://fapi.binance.com/fapi/v1/fundingRate) |
| calendar | BLS, BEA, Federal Reserve release calendars | primary release calendar | 2026-09-21 | 2026-09-21T09:20:25Z | No CPI, employment report, PCE release, FOMC decision or minutes scheduled Sep21–25; next PCE Sep30 and employment report Oct2.<br>[Open source](https://www.bea.gov/news/schedule/full) |
| coinmetrics | Coin Metrics Community API | on-chain data | 2026-09-20 | 2026-09-21T08:55:22.411Z | MVRV-Z reconstruction, exchange reserves and net exchange flows; true LTH provider-gated; large-address labels absent.<br>[Open source](https://community-api.coinmetrics.io/v4/timeseries/asset-metrics) |
| corr | 30-session BTC/ETH vs S&P 500 daily log-return correlation | computed market correlation | 2026-09-18 | 2026-09-21T08:55:22.411Z | 29 aligned return observations after inner-joining 30 aligned closes; computed from Binance closes and SPX history.<br>[Open source](https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC) |
| cpi | BLS August 2026 CPI | primary inflation release | 2026-09-11 | 2026-09-21T09:20:25Z | Headline 3.4% y/y; core 2.4% y/y; monthly headline +0.4%, core +0.3%.<br>[Open source](https://www.bls.gov/news.release/archives/cpi_09112026.htm) |
| farside_btc | Farside US spot BTC ETF daily flows | primary ETF flow table | 2026-09-18 | 2026-09-21T09:20:25Z | Completed daily sessions through Sep18; no partial Sep21 row used.<br>[Open source](https://farside.co.uk/bitcoin-etf-flow-all-data/) |
| farside_eth | Farside US spot ETH ETF daily flows | primary ETF flow table | 2026-09-18 | 2026-09-21T09:20:25Z | Completed daily sessions through Sep18; no partial Sep21 row used.<br>[Open source](https://farside.co.uk/ethereum-etf-flow-all-data/) |
| fed | Federal Reserve September 16 FOMC statement | primary central-bank release | 2026-09-16 | 2026-09-21T09:20:25Z | Target range raised 25bp to 3.75–4.00%.<br>[Open source](https://www.federalreserve.gov/newsevents/pressreleases/monetary20260916a.htm) |
| ledger | Personal-accounting position snapshot | user ledger | 2026-08-15T09:30:02.628Z | 2026-09-21T09:23:23Z | Fresh under event-driven policy; BTC and ETH custody is UNEXPLAINED. Do not state either asset quantity, basis or PnL.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| liquidations | Gate / ChainCatcher marketwide crypto liquidations | secondary market news citing CoinGlass | 2026-09-21 | 2026-09-21T09:20:25Z | Article timestamp label 01:33:53; timezone unspecified. Approximate rolling 24-hour aggregate: $401M total, $241M shorts and $160M longs; low-confidence squeeze context, not distribution proof.<br>[Open source](https://www.gate.com/en-us/news/detail/crypto-market-sees-401-million-in-liquidations-in-24-hours-241-million-in-17877034) |
| pce | BEA July 2026 Personal Income and Outlays | primary inflation release | 2026-08-26 | 2026-09-21T09:20:25Z | Headline PCE 3.7% y/y, core 3.3%; each +0.2% m/m. Next PCE release Sep30.<br>[Open source](https://www.bea.gov/news/2026/personal-income-and-outlays-july-2026) |
| prior_fr | Latest prior Flying Rocket report | prior report | 2026-09-11T13:18:00Z | 2026-09-21T09:39:27Z | Historical forecast and inherited risk controls; current quantity remains unknown.<br>[Open source](reports/btc_flying_rocket_20260911_0918.json) |
| real_yield | FRED 10-year TIPS real yield DFII10 | primary rate series | 2026-09-17 | 2026-09-21T09:20:25Z | 2.61%; official displayed observation.<br>[Open source](https://fred.stlouisfed.org/series/DFII10) |
| sentiment | Alternative.me Fear and Greed Index | primary sentiment series | 2026-09-21 | 2026-09-21T08:55:22.411Z | Raw daily series; score uses the three-day average.<br>[Open source](https://api.alternative.me/fng/?limit=90) |
| snapshot | Fresh Java BTC/ETH/macro snapshot | computed live snapshot | 2026-09-21T08:55:22.411Z | 2026-09-21T08:55:22.411Z | Three synchronized venue quotes; on-chain, F&G, weekly technicals, completed flow context and macro blocks; Binance market-flow panel remains a single-venue aggregate.<br>[Open source](data/runs/20260921-0855-a7e2031b/snapshot.json) |
| us2y | FRED 2-year Treasury constant maturity DGS2 | primary rate series | 2026-09-17 | 2026-09-21T09:20:25Z | 4.67%; official displayed observation.<br>[Open source](https://fred.stlouisfed.org/series/DGS2) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-09-21T08:55:22.411Z |
| Generated at | 2026-09-21T09:39:27Z |
| Report at | 2026-09-21T09:23:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | btc_flying_rocket_20260921_0523 |
| Report filename | btc_flying_rocket_20260921_0523.json |
| Run ID | 20260921-0855-a7e2031b |
| Snapshot ID | sha256:a7e2031bc4ac6fe4dc2f2f497e68f12d13dd32939d27c5039dade2219af983f6 |
| Prior report | btc_flying_rocket_20260821_0457 |
| Prior report hash | d2afd6c38bd1432af49794dc1657e0f0d538ef976db9b9644c3c4df17c3fc831 |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:a79ebb055145873293e1dc430bafe940879de8f12f271bac03c8fa1a5187be4c |
| launcher | sha256:d76783ffa0bf1ced47afce55473cdb9650d00f231c6f854d0e566817b15d3228 |
| reporting_emitter | sha256:80823b10833d57f18a5b48f8c67850aa23645360379a706470763f95b31e1791 |
| snapshot | sha256:5bde591355329fe8babb914c54226e2644e76e19f3964d49d39473b7119114e8 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | • STAND_DOWN | FR-A-1A-BTC-20260921-0523 | crypto |
| 1B | • STAND_DOWN | FR-A-1B-BTC-20260921-0523 | crypto |
| 2 | • STAND_DOWN | FR-A-2-BTC-20260921-0523 | crypto |
| 3 | • STAND_DOWN | FR-A-3-BTC-20260921-0523 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FR-A-1A-BTC-20260921-0523, FR-A-1B-BTC-20260921-0523, FR-A-2-BTC-20260921-0523, FR-A-3-BTC-20260921-0523

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":4,"field":"score.adjusted","previous":2,"reason":"Known diagnostic floor increased with current euphoria and ETF deceleration; unresolved inputs remain explicit, no active channel."},{"current":"none","field":"channel","previous":"none","reason":"Price remains above rising 200dma and more than20% below one-year high; no new short."}],"channel":"none","companion_framework":{"framework":"fallen_knives","gates":1,"rationale":"Same-snapshot computed FK 4/20, 1/9 gates. The paired FK machine report is the source; FR input uncertainty limits any stronger inverse-consistency claim.","score":4,"status":"AVAILABLE"},"cross_validation":{"rationale":"FK score 4; FR known floor 4 with disclosed ceiling 7. FR unknown distribution and options inputs prevent claiming a precise inverse relationship. Both ranges remain below 12, so the Hard Rule 5 both-≥12 inconsistency trigger is not present; relationship is marked unverified.","relationship":"UNVERIFIED — FR score interval not fully observed; no both-≥12 trigger","status":"DATA_LIMITED"},"data_gaps":[{"field":"BTC custody reconciliation","impact":"No asset quantity in either direction, no basis/PnL/ROI claim, and no sizing against this position; fix the ledger.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"True LTH distribution and profit-taking rate","impact":"Distribution sub-leg (a) and gate 7 remain unknown; no age-band proxy.","source_ids":["coinmetrics"],"status":"NOT_COVERED"},{"field":"Tagged large-address exchange flow","impact":"Distribution sub-leg (b) cannot be confirmed from aggregate 30d exchange flows; remains unknown.","source_ids":["coinmetrics"],"status":"NOT_COVERED"},{"field":"True 25-delta options skew / put-call ratio","impact":"Structural-vulnerability sub-leg (b) remains unknown; moneyness skew is not a substitute.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"Rotation trend and BTC dominance direction","impact":"Gate 9 uncounted; Altcoin Season Index level 51 is available but its crossing/trend is not.","source_ids":["altseason"],"status":"DATA_LIMITED"},{"field":"Venue-specific spot borrow quote","impact":"No spot-borrow quote verified; positive perp funding is disclosed and no new position is recommended.","source_ids":["snapshot"],"status":"DATA_LIMITED"}],"deployment":{"deployed_pct":"0","dry_pct":"50","throttle_released":false,"tranches":[{"channel":"A","deployed":false,"entry_price":null,"pct":"5","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set.","state":"LOCKED","stop":null,"tag":"FR-A-1A-BTC-20260921-0523","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"10","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set.","state":"LOCKED","stop":null,"tag":"FR-A-1B-BTC-20260921-0523","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"15","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set.","state":"LOCKED","stop":null,"tag":"FR-A-2-BTC-20260921-0523","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"20","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: no channel is live; score floor 4 (uncertainty range 4–7), 0/8 gates and custody data limits. No entry, hard stop, or clock is set.","state":"LOCKED","stop":null,"tag":"FR-A-3-BTC-20260921-0523","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"50x88000 + 30x82000 + 15x76500 + 5x70000 = 83575.00. Directional short EV +0.25%; true positive-funding carry adds 0.39% over 21 days, for total short EV +0.64%. Carry income is floored to zero for the +3% minimum-edge and 40%-of-target checks; gate EV +0.25% fails. Corroborative only because no channel is live.","probability_sum":1,"scenarios":[{"high":"91520","low":"84480","mid":"88000","name":"Recovery extends","probability":0.5,"rationale":"Score-band baseline; ranges are scenario bands, not price targets or an authorization. Upside path remains supported by positive spot/futures flow and squeeze risk. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance."},{"high":"85280","low":"78720","mid":"82000","name":"Range / base","probability":0.3,"rationale":"Score-band baseline; ranges are scenario bands, not price targets or an authorization. Requires the stated trend / support development; the no-channel route stays binding. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance."},{"high":"79560","low":"73440","mid":"76500","name":"Mean reversion","probability":0.15,"rationale":"Score-band baseline; ranges are scenario bands, not price targets or an authorization. Requires the stated trend / support development; the no-channel route stays binding. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance."},{"high":"72800","low":"67200","mid":"70000","name":"Bear reversal","probability":0.05,"rationale":"Score-band baseline; ranges are scenario bands, not price targets or an authorization. Requires the stated trend / support development; the no-channel route stays binding. Numerical bounds are analyst-defined +/-4% around the stated midpoint, not measured support/resistance."}],"stated_ev":"83575.0","vs_spot_pct":"-0.25"},"events":[{"as_of":"2026-09-21","impact":"Gate/ChainCatcher citing CoinGlass reports an approximate $401M in 24h crypto liquidations, $241M short vs $160M long; low-confidence squeeze/cover-overhang context, not distribution evidence.","name":"Short-side liquidation wave","status":"AVAILABLE"},{"as_of":"2026-09-18","impact":"ETF weekly flow +6.1M after prior sustained inflows; price remains >20% below the one-year high so this cannot count as short confirmation.","name":"ETF flow inflection","status":"AVAILABLE"},{"as_of":"2026-09-16","impact":"FOMC raised target range 25bp; next scheduled major releases are PCE Sep30 and employment Oct2, outside the Sep21–25 five-session window.","name":"Macro calendar","status":"AVAILABLE"}],"evidence":{"derivatives":{"as_of":"2026-09-21T08:00:00.000Z","confidence":"MEDIUM","rationale":"Single-venue Binance aggregate, 42 completed four-hour bars through 2026-09-21T08:00:00.000Z. Spot CVD: 24h $51.5M, 3d $114.8M, full window $207.8M. Futures taker delta/CVD: 24h $176.3M, 3d $408.9M, full-window CVD $-346.3M. OI: +2.085% 24h, +6.541% 3d; sampled latest $10.583B. OI-weighted funding latest raw fraction 0.00004503 per interval (do not annualize aggregate); prior-window percentile 14.29. Price rose 1.756% over 24h and 5.074% over the full flow window. Panel scope is Binance only, not cross-exchange; these linked flow/price/OI observations are one context family, not separate score votes. Mean annualized settled Binance funding 6.76%; positive means long holders pay shorts. No negative interval in the current analyzed run and no sustained-negative run; no squeeze penalty. True 25-delta options skew and market-wide put/call ratio are not available; no option proxy is scored.","source_ids":["snapshot","binance_flow","binance_funding"],"status":"AVAILABLE","unit":"Binance derivatives + single-venue market-flow panel","value":"positive funding / OI near 90d high"},"etf_flows":{"as_of":"2026-09-18","confidence":"HIGH","rationale":"Trailing calendar month Aug21–Sep18 net +$1,762.1M; September MTD +$313.4M; Sep14–18 +$6.1M after Sep8–11 −$462.7M, with Sep18 +$433.0M. Inflows decelerated materially from the two preceding strong weeks, earning the known single §4A distribution sub-leg; because price remains >20% below its own one-year high, this is also capitulation-context for gate 5 and cannot confirm a short.","source_ids":["farside_btc"],"status":"AVAILABLE","unit":"USD millions trailing calendar month","value":"1762.1"},"funding":{"as_of":"2026-09-21T08:55:22.411Z","confidence":"HIGH","rationale":"Mean annualized settled Binance funding 6.76%; positive means long holders pay shorts. No negative interval in the current analyzed run and no sustained-negative run; no squeeze penalty.","source_ids":["binance_funding"],"status":"AVAILABLE","unit":"percent annualized","value":"6.76"},"macro":{"as_of":"2026-09-21","confidence":"MEDIUM","rationale":"Fed raised 25bp on Sep16 to 3.75–4.00%; August CPI 3.4% headline/2.4% core y/y; July PCE 3.7%/3.3%; FRED 10y real yield 2.61% and 2y 4.67% on Sep17. VIX14.93 (5-observation change −12.69%), DXY100.28 (+0.82%), Brent97.52 (−7.72%); S&P500 Sep18 close 7650.5 (5d −0.08%), Nasdaq Composite 26522.54 (+0.72%), breadth 49.5% above 200dma on Sep17. No CPI, NFP, PCE, FOMC decision or minutes Sep21–25; next PCE Sep30, employment Oct2. These are mixed macro context, not a scored short signal.","source_ids":["snapshot","fed","cpi","pce","real_yield","us2y","calendar"],"status":"AVAILABLE","unit":"rates, FX, equities and event calendar","value":"mixed"},"momentum":{"as_of":"2026-09-14","confidence":"HIGH","rationale":"Weekly RSI-14 58.69; BTC earns 0 (<60), ETH earns 1 (60–65 band). Partial week excluded.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"completed weekly Wilder RSI-14","value":"58.69"},"onchain":{"as_of":"2026-09-20","confidence":"MEDIUM","rationale":"MVRV-Z 0.914. Exchange reserves 2,721,135.941 BTC (+0.372%/30d); 30d net exchange inflow $-3.438B, which is net outflow. True LTH supply/profit-taking is provider-gated; tagged large-address inflows are unavailable and neither is assigned a short score credit.","source_ids":["coinmetrics","snapshot"],"status":"AVAILABLE","unit":"Coin Metrics reconstructed on-chain daily","value":"MVRV-Z 0.914; reserves +0.372%/30d; net exchange flow $-3.438B"},"regime":{"as_of":"2026-09-21","confidence":"HIGH","rationale":"Price $83,782.00 is 33.61% below the trailing one-year high and 18.72% above the 200dma $70,568.93; that 200dma is rising 1.58% over 20 sessions. This is neither a Channel-A top (not within 20% of high) nor a Channel-B downtrend (price is above a rising 200dma).","source_ids":["snapshot"],"status":"AVAILABLE","unit":"channel routing","value":"none"},"sentiment":{"as_of":"2026-09-21","confidence":"HIGH","rationale":"Alternative.me daily prints 70,71,71; average 70.67; greed, not ≥80 for seven days.","source_ids":["sentiment"],"status":"AVAILABLE","unit":"Fear and Greed 3-day average","value":"70.67"},"spot":{"as_of":"2026-09-21T08:55:22.411Z","confidence":"HIGH","rationale":"Median of three timestamped CoinGecko, Binance and Coinbase quotes; spread below 0.5%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"83782.00"}},"falsifiers":[{"claim":"No channel is live","condition":"Re-run routing if BTC closes below $70,568.93 while the 200dma slope turns negative, or price returns within 20% of its one-year high and distribution evidence is re-evaluated.","status":"AVAILABLE"},{"claim":"No FR short is authorized","condition":"A new report must establish a live channel, a fully observed score at its phase line, converted gate floor, all veto/preflight checks and Total Short EV above +3%.","status":"AVAILABLE"},{"claim":"Ledger position cannot be called flat","condition":"Reconcile BTC custody, external transfers and fills; do not infer flatness from UNEXPLAINED custody.","status":"DATA_LIMITED"}],"gates":{"active":8,"alt_reading":null,"measurement_basis":{"1":"FAIL — F&G three-day average 70.67; no seven-day daily streak at ≥80.","2":"FAIL — completed-week RSI-14 58.69, below >70.","3":"FAIL — reconstructed MVRV-Z 0.914, below 3.","4":"FAIL — mean perp funding 6.76% annualized, below >25% for three intervals.","5":"WARNING/capitulation-context — Trailing calendar month Aug21–Sep18 net +$1,762.1M; September MTD +$313.4M; Sep14–18 +$6.1M after Sep8–11 −$462.7M, with Sep18 +$433.0M. Inflows decelerated materially from the two preceding strong weeks, earning the known single §4A distribution sub-leg; because price remains >20% below its own one-year high, this is also capitulation-context for gate 5 and cannot confirm a short.","6":"FAIL — Coinbase Premium was positive on each of the three completed days Sep18–20.","7":"UNMEASURED — true LTH supply and profit-taking rate are provider-gated; no age-band proxy is used.","8":"N/A — 33.61% below own one-year high (>15%); breadth divergence is top-coincident and structurally inapplicable.","9":"UNKNOWN — Altcoin Season Index is 51/100, but its rising-through-50 path and BTC dominance trend were not measured; gate stays uncounted.","surcharge":"Channel A diagnostic only; no channel is live. Gate 8 is N/A, so active denominator=8. Floors: 1A ceil(3/9×8)=3, 1B ceil(5/9×8)=5, P2 ceil(6/9×8)=6, P3 ceil(8/9×8)=8. 30d log-return corr=0.298 is below .7; correlation surcharge OFF. Passed 0/8."},"na":[8],"passed":[],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":8}},"identity":{"asset":"BTC","date":"2026-09-21","filename":"btc_flying_rocket_20260921_0523.json","framework":"flying_rocket","local_time":"05:23","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-10-06","note":"CoinGecko lifetime ATH; one-year routing high is recorded separately.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD lifetime ATH","value":"126080"},"drawdown_pct":{"as_of":"2026-09-21T08:55:22.411Z","note":"One-year high $126198.07; routing input.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent below one-year high","value":"33.61"},"metrics":{"adr5":{"as_of":"2026-09-20","note":"Five complete UTC sessions Sep16–20; partial Sep21 excluded. 1.5×ADR is the initial noise floor only; no stop is authorized.","source_ids":["binance_daily"],"status":"AVAILABLE","unit":"USD","value":"2051.42"},"borrow":{"as_of":"2026-09-21T08:55:22.411Z","note":"No venue-specific spot-borrow quote was verified for a new position; this report considers only perp funding carry.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"percent annualized","value":null},"coinbase_premium":{"as_of":"2026-09-20","note":"Last three completed daily prints retained in the gate basis.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"0.002"},"correlation_spx":{"as_of":"2026-09-18","note":"30 aligned closes / 29 return observations; corr <0.7 so risk-on surcharge is off.","source_ids":["corr"],"status":"AVAILABLE","unit":"Pearson daily log returns","value":"0.2981738853"},"daily_rsi":{"as_of":"2026-09-21T08:55:22.411Z","note":"Completed daily history; context only for Channel A.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"69.49"},"funding_annualized":{"as_of":"2026-09-21T08:55:22.411Z","note":"Positive funding pays a short; carry income is floored to zero for the entry filters.","source_ids":["binance_funding"],"status":"AVAILABLE","unit":"percent annualized","value":"6.76"},"market_flow_panel":{"as_of":"2026-09-21T08:00:00.000Z","note":"Single-venue Binance aggregate, 42 completed four-hour bars through 2026-09-21T08:00:00.000Z. Spot CVD: 24h $51.5M, 3d $114.8M, full window $207.8M. Futures taker delta/CVD: 24h $176.3M, 3d $408.9M, full-window CVD $-346.3M. OI: +2.085% 24h, +6.541% 3d; sampled latest $10.583B. OI-weighted funding latest raw fraction 0.00004503 per interval (do not annualize aggregate); prior-window percentile 14.29. Price rose 1.756% over 24h and 5.074% over the full flow window. Panel scope is Binance only, not cross-exchange; these linked flow/price/OI observations are one context family, not separate score votes.","source_ids":["binance_flow"],"status":"AVAILABLE","unit":"USD spot CVD over 3 days","value":"114786194.28"},"oi_90d":{"as_of":"2026-09-20","note":"90d archive available; single venue, not market-wide. This OI condition does not independently create a score credit.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"indicator: 1 = within 5% of 90-day high","value":"1"},"options_skew":{"as_of":"2026-09-21","note":"True 25-delta skew / put-call ratio not verified. Any moneyness-based surface statistic is not substituted for 25-delta; structural-vulnerability input remains unknown.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"25-delta risk reversal","value":null},"weekly_rsi":{"as_of":"2026-09-14","note":"Completed weekly closes; live week excluded.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"58.69"}},"reconciliation":{"method":"Median of three synchronized independent sources","note":"Small simultaneous dispersion, not staleness-driven. Yahoo frozen daily close and Kraken unknown-timestamp quote excluded. All three accepted quotes inside two-hour window. No EV sign-flip stress required at spread<=0.5%.","quotes":[{"as_of":"2026-09-21T08:53:40+00:00","instrument":"CoinGecko bitcoin","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"83782"},{"as_of":"2026-09-21T08:55:22.004000+00:00","instrument":"Binance BTCUSDT","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"83788.54"},{"as_of":"2026-09-21T08:55:22.299000+00:00","instrument":"Coinbase BTC-USD","source_ids":["snapshot"],"state":"LIVE","status":"AVAILABLE","value":"83765.26"},{"as_of":null,"instrument":"Yahoo BTC-USD BTC-USD","note":"frozen bar close — never enters the median; timestamp/age unknown","source_ids":["snapshot"],"state":"EXCLUDED","status":"DATA_LIMITED","value":"83765"},{"as_of":null,"instrument":"Kraken XBTUSD","note":"EXCLUDED — quote freshness is unknown (timestamp and recognized timestamp kind are required); timestamp/age unknown","source_ids":["snapshot"],"state":"EXCLUDED","status":"DATA_LIMITED","value":"83766.5"}],"spread_pct":"0.028","status":"AVAILABLE"},"regime":{"bounce_age_sessions":38,"bounce_pct":"34.08","channel":"none","ma200":"70568.93","ma200_slope20_pct":"1.58","ma50":"73614.35","price_vs_ma200_pct":"18.72","stall_confirmation":false},"spot":{"as_of":"2026-09-21T08:55:22.411Z","note":"Canonical median of three synchronized CoinGecko, Binance and Coinbase live quotes.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"83782.00"}},"narrative":{"arguments":{"discretion_ledger":[{"channel":"S1","date":"2026-09-21","load_bearing":false,"outcome":"STAND_DOWN","reason":"No discretionary term; discretion cannot replace unknown inputs, a missing route, gates or stops.","s2":false,"term":"0.0"}],"prior_forecast_grade":"Latest prior report is Sep11, not Aug21. Its mechanical/adjusted score was 2/20 with no active channel. Spot increased 7.67% from $77,814.24 to $83,782.00; prior scenario EV was $77,872.60. Current price lies in the prior Recovery extends band. This interim observation supports the recovery mode but is not a completed 21-day forecast grade; the original horizon has not elapsed. Today no channel is active. No claim is made about every intermediate daily trigger.","stop_migration_ledger":[]},"bear_case":"The rebound is extended: 40-session low-to-current bounce 34.08%, daily RSI 69.49, and Binance OI is within5% of its 90d high. The current squeeze/liquidation and positive flow impulse can still reverse. IF price closes below $70,568.93 and the 200dma slope turns negative, THEN re-run for Channel B; until then no short.","bull_case":"Price is 18.72% above a rising 200dma; BTC and ETH spot/futures flow is positive across 24h and 3d, and the fresh marketwide liquidation report is short-heavy. IF price continues to hold above the rising average, THEN the no-channel route remains. (Falsifier: daily close below the current 200dma followed by a negative 20-session slope.)","primary_action":{"rationale":"No channel, 0/8 qualifying gates, incomplete FR score interval, and unexplained custody. Stand down; do not call the position flat or size a new short.","status":"DATA_LIMITED","value":"STAND_DOWN"},"rationale":"ROUTE — BTC is 33.61% below its trailing one-year high, but 18.72% above a rising 200dma (slope +1.58% over 20 sessions). Channel A's top condition and Channel B's downtrend condition both fail: NONE. The hypothetical Channel-A cap of 8/20 is not applied arithmetically in this route.\nSCORE — Known legs: euphoria 3 (F&G 70.67), momentum 0 (weekly RSI 58.69), valuation 0 (MVRV-Z 0.914), distribution known minimum 1 (ETF flows decelerated/reversed), structural vulnerability known minimum 0. Mechanical and adjusted floor 4/20; plausible data-limited interval 4–7. True LTH/profit taking, tagged large-address flows and 25d skew/PCR are not inferred. No squeeze penalty, discretion 0, correlation surcharge OFF (30-session corr 0.298).\nGATES — 0/8 passed; gate8 N/A because asset is >15% below its own ATH. Converted floors are 3/5/6/8. Gate 5/6 reads are warning/capitulation-context and cannot confirm distribution; LTH is unmeasured; rotation trend is unknown. No phase can unlock because no channel is live.\nFLOWS — Single-venue Binance aggregate, 42 completed four-hour bars through 2026-09-21T08:00:00.000Z. Spot CVD: 24h $51.5M, 3d $114.8M, full window $207.8M. Futures taker delta/CVD: 24h $176.3M, 3d $408.9M, full-window CVD $-346.3M. OI: +2.085% 24h, +6.541% 3d; sampled latest $10.583B. OI-weighted funding latest raw fraction 0.00004503 per interval (do not annualize aggregate); prior-window percentile 14.29. Price rose 1.756% over 24h and 5.074% over the full flow window. Panel scope is Binance only, not cross-exchange; these linked flow/price/OI observations are one context family, not separate score votes.\nETF — Trailing calendar month Aug21–Sep18 net +$1,762.1M; September MTD +$313.4M; Sep14–18 +$6.1M after Sep8–11 −$462.7M, with Sep18 +$433.0M. Inflows decelerated materially from the two preceding strong weeks, earning the known single §4A distribution sub-leg; because price remains >20% below its own one-year high, this is also capitulation-context for gate 5 and cannot confirm a short.\nMACRO — Fed target 3.75–4.00% after Sep16 hike; August CPI 3.4%/2.4% y/y, July PCE 3.7%/3.3%; no CPI/NFP/PCE/FOMC decision/minutes in Sep21–25, next PCE Sep30.\nEV — Scenario EV $83,575.00 versus spot $83,782.00; directional short EV +0.25%, true carry +0.39% over21d, true total +0.64%. Carry income floors to zero for gates; +3% minimum-edge fails. EV is corroborative only because the route is none.\nPOSITION — Ledger snapshot remains event-driven fresh, but BTC custody is UNEXPLAINED. No coin quantity, basis or PnL is stated. Drawable stablecoin cash is $7792.6440; $2948.9340 is locked and not drawable. BTC ledger does not corroborate an FR-tagged short; unresolved custody prevents calling the account flat.","summary":"BTC: channel NONE; known score floor 4/20 with disclosed range 4–7; 0/8 gates. No new FR tranche. Stand down and repair custody records before position claims."},"out_of_scope":["No order execution or account change was performed.","Channel NONE is not an entry or target signal.","Binance market-flow context is single-venue and not a market-wide tally.","Unknown rubric inputs are excluded from the known score floor and not replaced by proxies."],"position":{"asset":"BTC","attribution":{"active_tags":[],"note":"No framework short attribution is corroborated. Custody is UNEXPLAINED; do not infer a flat position.","status":"UNKNOWN"},"basis":{"avg_cost":null,"reason":"Custody is UNEXPLAINED and the basis is not reliable; no average cost, total basis, PnL or ROI is stated.","reliable":false,"total_cost":null},"custody":{"reason":"The live balance and the fill replay disagree, and neither recorded withdrawals nor a migration seed accounts for the gap. This is a data defect — an unread wallet, an uncovered venue, or an incomplete backfill — not a position. Do NOT report a figure for this asset in either direction; fix the ledger first.","status":"UNEXPLAINED"},"dry_powder":"7792.6440","futures":[],"pnl":{"reason":"Unexplained custody prohibits any position PnL claim.","status":"DATA_LIMITED","unrealized":null},"quantity":null,"reconciliation":"Snapshot generated 2026-08-15T09:30:02.628253Z under event-driven validity. BTC custody is UNEXPLAINED. Hard Rule 8: report no asset quantity, basis, or PnL in either direction; reconcile ledger before sizing. Ledger does not corroborate a tagged FR short; this is not a claim of flat exposure.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"Snapshot generated 2026-08-15T09:30:02.628253Z under event-driven validity. BTC custody is UNEXPLAINED. Hard Rule 8: report no asset quantity, basis, or PnL in either direction; reconcile ledger before sizing. Ledger does not corroborate a tagged FR short; this is not a claim of flat exposure.","status":"DATA_LIMITED","value":"STAND_DOWN"},"required":true,"status":"DATA_LIMITED"},"regime":{"ma200_falling":false,"pct_below_1y_ath":"33.61","price_below_ma200":false},"report_id":"btc_flying_rocket_20260921_0523","risk_controls":{"carry":{"carry_veto":false,"funding_annualized_pct":"6.76","gate_carry_ev_pct":"0.00","minimum_edge_pass":false,"status":"AVAILABLE","true_carry_ev_pct_21d":"0.39"},"concentration":{"channel_a_asset_cap_pct":"50","planned_pct":"0","status":"DATA_LIMITED","total_short_book_cap_pct":"50"},"ratchet":{"reason":"No independently corroborated, attributable BTC FR tranche or auditable stop; custody remains unexplained.","status":"DATA_LIMITED"},"stops":{"adr5":"2051.42","channel_a_1a_ceiling_pct":"8.00","initial_floor_pct":"3.67","note":"Informational ADR floor only; channel NONE means no entry, price stop, or stop ratchet is set.","status":"LOCKED"},"time_stops":{"p1a_days":21,"p1b_days":28,"p2_days":35,"p3_days":49,"status":"LOCKED"}},"run":{"prior_report_id":"btc_flying_rocket_20260821_0457","prior_report_sha256":"d2afd6c38bd1432af49794dc1657e0f0d538ef976db9b9644c3c4df17c3fc831","run_id":"20260921-0855-a7e2031b","snapshot_id":"sha256:a7e2031bc4ac6fe4dc2f2f497e68f12d13dd32939d27c5039dade2219af983f6","tool_hashes":{"compute":"sha256:a79ebb055145873293e1dc430bafe940879de8f12f271bac03c8fa1a5187be4c","launcher":"sha256:d76783ffa0bf1ced47afce55473cdb9650d00f231c6f854d0e566817b15d3228","reporting_emitter":"sha256:80823b10833d57f18a5b48f8c67850aa23645360379a706470763f95b31e1791","snapshot":"sha256:5bde591355329fe8babb914c54226e2644e76e19f3964d49d39473b7119114e8"}},"schema":"report-machine/2","score":{"adjusted":4,"caps":[{"field":"route","reason":"33.61% below one-year high and price above rising 200dma; Channel A top regime and Channel B bear-continuation precondition both fail.","value":"none"},{"field":"hypothetical_channel_a_ceiling","reason":"§2.5 would cap Channel A at 8/20 when >20% below the high, but route is NONE; cap.applied=false and that cap is not included in arithmetic.","value":8},{"field":"squeeze_trap","reason":"No negative funding run or current single-interval trigger; no penalty.","value":0},{"field":"correlation_gate_surcharge","reason":"30-session daily log-return corr 0.298<0.7; no surcharge.","value":0},{"field":"known_score_range","reason":"Known predicates only are counted. Distribution LTH/profit and large-address labels, plus true 25-delta skew/PCR, are not all observable; do not promote the floor to a complete score.","value":"4–7 (known-input floor to missing-input ceiling)"}],"discretion":0,"legs":{"distribution":1,"euphoria":3,"momentum":0,"valuation":0,"vulnerability":0},"mechanical":4,"penalties":[],"raw":4,"rounding":"half-up"},"sources":{"altseason":{"as_of":"2026-09-21","kind":"rotation index","name":"BlockchainCenter Altcoin Season Index","note":"Index 51/100; direction over the required lookback was not independently verified.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://www.blockchaincenter.net/altcoin-season-index/"},"binance_daily":{"as_of":"2026-09-20","kind":"completed daily candles","name":"Binance BTCUSDT/ETHUSDT OHLC","note":"Five complete sessions Sep16–20 used for ADR; partial Sep21 excluded.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"https://api.binance.com/api/v3/klines"},"binance_flow":{"as_of":"2026-09-21T08:00:00Z","kind":"completed-bar derivative and spot flow","name":"Binance aggregate market-flow panel","note":"42 completed 4-hour bars, Sep14 08:00–Sep21 08:00 UTC; single venue, not market-wide; stable-USD quote assets treated as nominal USD.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"https://api.binance.com/"},"binance_funding":{"as_of":"2026-09-21T08:55:22.411Z","kind":"derivatives funding","name":"Binance USD-M settled funding rates","note":"45 recent intervals; market-rate sign convention: positive funding is income to a short.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"https://fapi.binance.com/fapi/v1/fundingRate"},"calendar":{"as_of":"2026-09-21","kind":"primary release calendar","name":"BLS, BEA, Federal Reserve release calendars","note":"No CPI, employment report, PCE release, FOMC decision or minutes scheduled Sep21–25; next PCE Sep30 and employment report Oct2.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://www.bea.gov/news/schedule/full"},"coinmetrics":{"as_of":"2026-09-20","kind":"on-chain data","name":"Coin Metrics Community API","note":"MVRV-Z reconstruction, exchange reserves and net exchange flows; true LTH provider-gated; large-address labels absent.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"},"corr":{"as_of":"2026-09-18","kind":"computed market correlation","name":"30-session BTC/ETH vs S&P 500 daily log-return correlation","note":"29 aligned return observations after inner-joining 30 aligned closes; computed from Binance closes and SPX history.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC"},"cpi":{"as_of":"2026-09-11","kind":"primary inflation release","name":"BLS August 2026 CPI","note":"Headline 3.4% y/y; core 2.4% y/y; monthly headline +0.4%, core +0.3%.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://www.bls.gov/news.release/archives/cpi_09112026.htm"},"farside_btc":{"as_of":"2026-09-18","kind":"primary ETF flow table","name":"Farside US spot BTC ETF daily flows","note":"Completed daily sessions through Sep18; no partial Sep21 row used.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://farside.co.uk/bitcoin-etf-flow-all-data/"},"farside_eth":{"as_of":"2026-09-18","kind":"primary ETF flow table","name":"Farside US spot ETH ETF daily flows","note":"Completed daily sessions through Sep18; no partial Sep21 row used.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://farside.co.uk/ethereum-etf-flow-all-data/"},"fed":{"as_of":"2026-09-16","kind":"primary central-bank release","name":"Federal Reserve September 16 FOMC statement","note":"Target range raised 25bp to 3.75–4.00%.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://www.federalreserve.gov/newsevents/pressreleases/monetary20260916a.htm"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"user ledger","name":"Personal-accounting position snapshot","note":"Fresh under event-driven policy; BTC and ETH custody is UNEXPLAINED. Do not state either asset quantity, basis or PnL.","retrieved_at":"2026-09-21T09:23:23Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"liquidations":{"as_of":"2026-09-21","kind":"secondary market news citing CoinGlass","name":"Gate / ChainCatcher marketwide crypto liquidations","note":"Article timestamp label 01:33:53; timezone unspecified. Approximate rolling 24-hour aggregate: $401M total, $241M shorts and $160M longs; low-confidence squeeze context, not distribution proof.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://www.gate.com/en-us/news/detail/crypto-market-sees-401-million-in-liquidations-in-24-hours-241-million-in-17877034"},"pce":{"as_of":"2026-08-26","kind":"primary inflation release","name":"BEA July 2026 Personal Income and Outlays","note":"Headline PCE 3.7% y/y, core 3.3%; each +0.2% m/m. Next PCE release Sep30.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://www.bea.gov/news/2026/personal-income-and-outlays-july-2026"},"prior_fr":{"as_of":"2026-09-11T13:18:00Z","kind":"prior report","name":"Latest prior Flying Rocket report","note":"Historical forecast and inherited risk controls; current quantity remains unknown.","retrieved_at":"2026-09-21T09:39:27Z","url":"reports/btc_flying_rocket_20260911_0918.json"},"real_yield":{"as_of":"2026-09-17","kind":"primary rate series","name":"FRED 10-year TIPS real yield DFII10","note":"2.61%; official displayed observation.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://fred.stlouisfed.org/series/DFII10"},"sentiment":{"as_of":"2026-09-21","kind":"primary sentiment series","name":"Alternative.me Fear and Greed Index","note":"Raw daily series; score uses the three-day average.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"https://api.alternative.me/fng/?limit=90"},"snapshot":{"as_of":"2026-09-21T08:55:22.411Z","kind":"computed live snapshot","name":"Fresh Java BTC/ETH/macro snapshot","note":"Three synchronized venue quotes; on-chain, F&G, weekly technicals, completed flow context and macro blocks; Binance market-flow panel remains a single-venue aggregate.","retrieved_at":"2026-09-21T08:55:22.411Z","url":"data/runs/20260921-0855-a7e2031b/snapshot.json"},"us2y":{"as_of":"2026-09-17","kind":"primary rate series","name":"FRED 2-year Treasury constant maturity DGS2","note":"4.67%; official displayed observation.","retrieved_at":"2026-09-21T09:20:25Z","url":"https://fred.stlouisfed.org/series/DGS2"}},"stale_inputs":[],"substitutions":[{"field":"options_vulnerability_25d_skew_or_put_call","original":"True 25-delta options skew / put-call ratio","rationale":"Not available; moneyness-based surface information is not a 25-delta substitute.","substitute":"none"},{"field":"distribution_lth_and_large_address","original":"True LTH supply/profit taking and tagged large-address inflows","rationale":"Provider-gated/unavailable; no cohort or exchange-flow proxy is promoted into the score.","substitute":"none"}],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FR-A-1A-BTC-20260921-0523","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FR-A-1B-BTC-20260921-0523","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FR-A-2-BTC-20260921-0523","decision":"STAND_DOWN","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FR-A-3-BTC-20260921-0523","decision":"STAND_DOWN","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FR-A-1A-BTC-20260921-0523","FR-A-1B-BTC-20260921-0523","FR-A-2-BTC-20260921-0523","FR-A-3-BTC-20260921-0523"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-09-21T08:55:22.411Z","generated_at":"2026-09-21T09:39:27Z","report_at":"2026-09-21T09:23:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"No channel, 0/8 gates and incomplete score inputs. Stand down and fix custody reconciliation; do not short the squeeze.","status":"DATA_LIMITED","value":"STAND_DOWN"},"statement":"No new BTC short: no channel is live; known score floor 4/20 (range 4–7), 0/8 gates, and all phases stay locked. Repair unexplained custody before any position claim or sizing.","status":"STAND_DOWN"},"watchlist":[{"item":"Channel routing","status":"AVAILABLE","trigger":"Re-evaluate only after a close below $70,568.93 with a falling 200dma, or a return to within20% of the one-year high."},{"item":"ETF-flow regime","status":"AVAILABLE","trigger":"Continue to monitor completed daily ETF flows; current flows are capitulation-context and cannot confirm short distribution while >20% off high."},{"item":"Squeeze / positioning","status":"AVAILABLE","trigger":"Reassess after the short-heavy liquidation event and with a fresh funding/OI snapshot; current flow panel is single venue."},{"item":"Ledger repair","status":"DATA_LIMITED","trigger":"Reconcile BTC custody and fills before quantity/PnL claims."},{"item":"FK force-cover","status":"AVAILABLE","trigger":"Same-timestamp FK score is 4, below the >=12 force-cover cross-check; this does not override ledger or route rules."}]}
```
