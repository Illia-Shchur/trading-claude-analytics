# BTC — Flying Rocket — 2026-08-20 16:40

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | BTC · Flying Rocket |
| Report time | 2026-08-20 16:40 (America/New_York) |
| Verdict | • STAND_DOWN — No BTC short: no channel is live, adjusted score 1/20, gates 0/8, and gate EV +0.22%. Stand down and fix ledger reconciliation before sizing anything. |
| Adjusted score | **1/20** (mechanical 1, raw 1) |
| Confirmation gates | 0/8 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable BTC |
| Deployment | 0% deployed · 50% dry |
| Primary action | **STAND_DOWN** — No channel, no confirmation gates and +0.22% gate EV. The ledger has no corroborated BTC short and the custody/basis defect blocks any quantity claim. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $72,884.395/BTC | ✅ AVAILABLE | — | 2026-08-20T20:35:21Z | Canonical synchronized median.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| All-time high | $126,198.07/BTC one-year high | ✅ AVAILABLE | — | 2025-10-06 | Yahoo trailing-one-year weekly high.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Drawdown from ATH | 42.25% | ✅ AVAILABLE | — | 2026-08-20 | Channel routing input.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| ADR-5 | $2,676.28 | ✅ AVAILABLE | — | 2026-08-20 | Five full crypto sessions; 1.5xADR noise floor is 5.51% of spot.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Borrow | 0.0011% | ✅ AVAILABLE | — | 2026-08-20 | Bitfinex single-venue lending proxy; context only.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Correlation spx | — | ⚠️ DATA_LIMITED | — | 2026-08-20 | Not computed this cycle; conservative risk-on gate surcharge defaults ON.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Daily rsi | 80.22 RSI-14 | ✅ AVAILABLE | — | 2026-08-20 | Current daily series after the squeeze rally.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Options skew | 0.12 vol points put-rich | ✅ AVAILABLE | — | 2026-08-20 | Moneyness-based, not a 25-delta risk reversal; context only.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Weekly RSI-14 | 38.80 RSI-14 | ✅ AVAILABLE | — | week of 2026-08-10 | Completed week; current partial week excluded.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |

**Regime:** — — Bounce age sessions: 38; Bounce pct: 18.00%; Channel: none; Ma200: 68974.61; Ma200 slope20 pct: -3.47%; Ma50: 64230.54; Price vs ma200 pct: 5.67%; Stall confirmation: No

### Spot reconciliation

**✅ AVAILABLE** — Median of four synchronized live sources; frozen Yahoo close excluded; spread 0.038%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko BTC | $72,890/BTC | ✅ live | — |
| Binance BTCUSDT | $72,887.16/BTC | ✅ live | — |
| Coinbase BTC-USD | $72,881.63/BTC | ✅ live | — |
| Kraken XBTUSD | $72,862/BTC | • receipt-time | — |

> Spread 0.038% is below 0.5%; no two-extremes EV test is required.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Derivatives | not euphoric enough Binance/Deribit context | ✅ AVAILABLE | HIGH | 2026-08-20T20:35:21Z | Funding +6.37% annualized; OI 6.10% below its 90-day high; skew nearly flat at +0.12.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Etf flows | 1,004.0 USD millions | ✅ AVAILABLE | HIGH | 2026-08-19 | Aug 17-19 inflows total $1.004B, but three sessions do not overturn the prior five-session regime under the durability lock.<br>Sources: [farside_btc](https://farside.co.uk/btc/) |
| Funding | 6.37% | ✅ AVAILABLE | HIGH | 2026-08-20T20:35:21Z | Positive funding means longs pay shorts; no sustained negative prints.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Macro | mixed | ✅ AVAILABLE | HIGH | 2026-08-20 | VIX and yields rose, but DXY fell and regulatory optimism triggered a large squeeze.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json), [market_news](https://ng.investing.com/news/cryptocurrency-news/bitcoin-hits-72k-after-trump-calls-for-clear-crypto-legislation-2667941), [cftc](https://www.cftc.gov/PressRoom/Events/opaeventiac082026) |
| Momentum | 80.22 daily RSI-14 | ✅ AVAILABLE | HIGH | 2026-08-20 | Daily RSI 80.22 is locally extreme, but completed-week RSI 38.80 keeps Channel-A momentum at zero.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Onchain | low valuation / reserves rising | ✅ AVAILABLE | MEDIUM | 2026-08-19 | MVRV-Z 0.547; exchange reserves rose 2.99% over 30d; true LTH is provider-gated.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Regime | none | ✅ AVAILABLE | HIGH | 2026-08-20 | 42.25% below high but price 5.67% above falling 200dma; no channel.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Sentiment | 49.67 F&G 3-day average | ✅ AVAILABLE | HIGH | 2026-08-20 | F&G spot 62, three-day average 49.67.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Spot | $72,884.395/BTC | ✅ AVAILABLE | HIGH | 2026-08-20T20:35:21Z | Four-source synchronized median; 0.038% spread.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |

**Data gaps:** 3 · **stale inputs:** 0 · **out of scope:** 2

**Data gaps**

- **BTC custody and basis reconciliation** — ⚠️ DATA_LIMITED — No quantity, basis, unrealized PnL or ROI may be quoted.
- **True LTH distribution** — — NOT_COVERED — No gate-7 or distribution sub-leg (a) credit.
- **30-day crypto/equity correlation** — — NOT_COVERED — Risk-on gate surcharge defaults ON.

**Out of scope**

- No order execution was performed.
- Context-panel fields are not promoted into rubric legs or gates.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Distribution | 1 | 3 | Mechanical component |
| Euphoria | 0 | 5 | Mechanical component |
| Momentum | 0 | 4 | Mechanical component |
| Valuation | 0 | 5 | Mechanical component |
| Vulnerability | 0 | 3 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 1 | Legs plus penalties |
| Raw score | 1 | Mechanical plus discretion (0) |
| Adjusted score | **1/20** | Decision score |
| Rounding | half-up | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Phase of cycle | 8 | BTC is more than 20% below its one-year high; Channel A is capped at 8 and no phase is reachable. |
| Channel | none | Price is above the falling 200dma, so Channel B precondition fails. |
| Squeeze trap | 0 | Funding is not sustained below -5% annualized. |

### Confirmation gates — 0/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | FAIL — crypto F&G is not >=80 for seven days. |
| 2 | • NOT PASSED | FAIL — completed-week RSI is 38.80, below 70. |
| 3 | • NOT PASSED | FAIL — MVRV-Z is 0.547, below 3. |
| 4 | • NOT PASSED | FAIL — funding is 6.37% annualized, below 25%. |
| 5 | • NOT PASSED | WARNING/capitulation-context — ETF flows improved, but price is >20% below its high; gate cannot confirm distribution. |
| 6 | • NOT PASSED | FAIL — Coinbase Premium is positive on all three completed days. |
| 7 | • NOT PASSED | UNMEASURED/FAIL — true LTH distribution is provider-gated; no substitute is used. |
| 8 | • N/A | N/A — asset is >15% below its own high; top-coincident breadth divergence is structurally inapplicable. |
| 9 | • NOT PASSED | WARNING/capitulation-context — rotation gate cannot count while price is >20% below the high. |

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
| Recovery extends | 50% | $73,000/BTC | $82,000/BTC | $77,500/BTC | Squeeze and regulatory optimism can extend without a completed trend repair. |
| Range / base | 30% | $68,000/BTC | $74,000/BTC | $71,000/BTC | Consolidation around the reclaimed 200dma. |
| Mean reversion | 15% | $62,000/BTC | $68,000/BTC | $65,000/BTC | Requires a failed reclaim. |
| Bear continuation | 5% | $55,000/BTC | $62,000/BTC | $58,500/BTC | Requires renewed lower-low structure; absent. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $72,725/BTC |
| EV versus spot | -0.22% |

> 0.50x77500 + 0.30x71000 + 0.15x65000 + 0.05x58500 = 72725. Directional short EV +0.22%; true positive-funding carry adds +0.37% over 21 days but is floored to zero for gating. Gate EV +0.22%, below +3%; corroborative only because no channel is live.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 50% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 5% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1A-BTC-20260820-1640 | Locked: adjusted score is below 11, confirmation stack is incomplete, and no new short is authorized. |
| 1B | 10% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1B-BTC-20260820-1640 | Locked: adjusted score is below 13, confirmation stack is incomplete, and no new short is authorized. |
| 2 | 15% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-2-BTC-20260820-1640 | Locked: adjusted score is below 15, confirmation stack is incomplete, and no new short is authorized. |
| 3 | 20% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-3-BTC-20260820-1640 | Locked: adjusted score is below 19, confirmation stack is incomplete, and no new short is authorized. |

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
| Reason | Twelve unbacked disposals make cost basis non-derivable; no average cost, basis, unrealized PnL or ROI is quoted. |
| Reliable | No |
| Total cost | — |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Note | Open BTC deals are untagged; no framework short is corroborated. |
| Status | ❔ UNKNOWN |

### Position P&L

| Field | Value |
| --- | --- |
| Reason | Basis and custody defects prohibit a PnL claim. |
| Status | ⚠️ DATA_LIMITED |
| Unrealized | — |

> **Position reconciliation:** Event-driven snapshot is fresh, but BTC custody is UNEXPLAINED and basis is unreliable. No position figure in either direction is reported.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| ⚠️ DATA_LIMITED | yes | **STAND_DOWN** — No corroborated BTC short exists; custody and basis defects block any position-level control or sizing claim. |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Carry veto | No |
| Funding annualized pct | 6.37% |
| Gate carry ev pct | 0% |
| Minimum edge pass | No |
| Status | ✅ AVAILABLE |
| True carry ev pct 21d | 0.37 |

### Concentration

| Field | Value |
| --- | --- |
| Channel a asset cap pct | 50% |
| Planned pct | 0% |
| Status | ✅ PASS |
| Total short book cap pct | 50% |

### Ratchet

| Field | Value |
| --- | --- |
| Reason | No corroborated BTC short or auditable prior stop exists. |
| Status | — NOT_APPLICABLE |

### Stops

| Field | Value |
| --- | --- |
| ADR-5 | 2676.28 |
| Channel a 1a ceiling pct | 8.00% |
| Initial floor pct | 5.51% |
| Note | Informational only; no new fill is authorized. |
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

**Summary:** BTC has no live short channel after moving 5.67% above its falling 200dma. Score is 1/20, gates 0/8 and gate EV +0.22%. Stand down; the asset ledger remains unreconciled and cannot support sizing.

**Bull case:** Price is 5.67% above the falling 200dma, ETF inflows reached $1.004B over three completed sessions, Coinbase Premium is positive and the squeeze catalyst remains live. IF a completed week holds above the 200dma, THEN Channel B stays void.

**Bear case:** Daily RSI 80.22 is stretched and the 200dma is still falling. IF BTC falls back below 68974.61 and prints a lower-close/lower-high stall, THEN Channel B can be re-evaluated from scratch.

**Rationale:** ROUTE — BTC is 42.25% below its one-year high but 5.67% above a falling 200dma. Channel A is cap-dead and Channel B fails price-below-MA: NONE / STAND DOWN.
> SCORE — F&G 49.67, weekly RSI 38.80 and MVRV-Z 0.547 all give Channel-A short legs 0. The prior five-session ETF-outflow regime contributes distribution 1 under the durability lock; funding, options and breadth add no vulnerability. Mechanical/adjusted 1, discretion 0.0. Gates 0/8; correlation not computed, so the conservative gate surcharge defaults ON.
> EV — Scenario EV 72725 gives +0.22% directional/gate short EV versus 72884.395. Positive funding income is floored to zero for gating; +0.22% fails +3%. Collar ON and EV is corroborative only.
> POSITION — Event-driven snapshot is fresh but BTC custody is UNEXPLAINED and basis unreliable. Hard Rule 8 forbids a quantity, cost basis, unrealized PnL or ROI claim. No corroborated BTC short exists. Dry powder is $10,741.578, but nothing is sized against a defective asset ledger.
> ACTIONS — 1) Stand down; do not short the squeeze. 2) Fix BTC custody and basis reconciliation. 3) Re-run only after a completed structural test below the 200dma or a genuine Channel-A return near the high.

**Primary action:** **STAND_DOWN** — No channel, no confirmation gates and +0.22% gate EV. The ledger has no corroborated BTC short and the custody/basis defect blocks any quantity claim.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Prior forecast grade | The Aug-19 EV_price 67700 was exceeded by 7.66%; spot 72884 remains inside the prior 69000-74000 rally-extension band. The completed-week 200dma-reclaim falsifier has not yet finalized. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-20 | S1 | — | — | — | — | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | fallen_knives · 7/20 · 3 gates | Same-timestamp FK: sentiment 1, momentum 2, valuation 3, liquidation capitulation 1, holder behavior 0 = 7. Gates 3, 7 and 9 pass. FK <12. |
| Cross-validation | ✅ CONSISTENT | FR 1 versus FK 7 | Channel A is cap-bound and no channel is live; neither score is >=12, so the Hard Rule 5 inconsistency test does not fire. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| 200dma route | ✅ AVAILABLE | Price back below 68974.61 with falling slope reopens Channel B only after full rerun. |
| Completed weekly reclaim | ✅ AVAILABLE | A completed week above the 200dma keeps Channel B void. |
| FK force-cover | ✅ AVAILABLE | FK >=12 would independently force cover; current computed FK is 7. |
| Ledger repair | ⚠️ DATA_LIMITED | Reconcile custody and basis before any position sizing. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-20 | Crypto short squeeze | ✅ AVAILABLE | A record-scale short squeeze and regulatory optimism drove BTC above $72K; shorting the rising session is prohibited by discipline. |
| 2026-08-20 | CFTC Innovation Advisory Committee | ✅ AVAILABLE | CFTC innovation meeting keeps regulatory surprise risk live. |
| 2026-09-15 | FOMC | ✅ AVAILABLE | Next FOMC decision; beyond any permissible fresh 1A clock. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| No channel is live | A completed daily move back below 68974.61 with falling slope reopens Channel-B routing for a full rerun. | ✅ AVAILABLE |
| Recovery-extension remains modal | A close below 68000 followed by a failed 200dma reclaim shifts mass toward mean reversion. | ✅ AVAILABLE |
| No short is authorized | A fresh report must show a live channel, adjusted score at its line, converted gates, preflight pass and gate EV >3%. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.mechanical | 8 | 1 | Routing moved from Channel B to cap-bound Channel A record scoring; the two rubrics measure different objects. |
| Channel | B | none | Price moved above the falling 200dma. |
| Position | EXPIRED cold start | DATA_LIMITED | Event-driven snapshot is fresh but custody/basis are defective; exact position claim is refused. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| cftc | CFTC Innovation Advisory Committee meeting | primary regulator event | 2026-08-20 | 2026-08-20T20:37:00Z | Inaugural meeting on crypto regulatory evolution; bullish-regulatory surprise risk for shorts.<br>[Open source](https://www.cftc.gov/PressRoom/Events/opaeventiac082026) |
| farside_btc | Farside US BTC ETF daily flows | primary ETF flow table | 2026-08-19 | 2026-08-20T20:36:00Z | Completed-session flows through August 19; August 20 incomplete and excluded.<br>[Open source](https://farside.co.uk/btc/) |
| ledger | Personal-accounting position snapshot | user ledger | 2026-08-15T09:30:02.628Z | 2026-08-20T20:34:00Z | FRESH under the event-driven policy; asset custody defects are handled separately and fail closed.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| market_news | Crypto rally and short-squeeze coverage | market news | 2026-08-20 | 2026-08-20T20:37:00Z | BTC and ETH rally amplified by short covering and regulatory optimism; used as catalyst context, not a scored leg.<br>[Open source](https://ng.investing.com/news/cryptocurrency-news/bitcoin-hits-72k-after-trump-calls-for-clear-crypto-legislation-2667941) |
| snapshot | Deterministic BTC/ETH/macro live snapshot | computed | 2026-08-20T20:35:38.055Z | 2026-08-20T20:35:38.055Z | Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Coin Metrics, Coinbase Premium, Bitfinex borrow, Deribit options and FRED/Yahoo macro.<br>[Open source](data/runs/20260820-2035-5595e232/snapshot.json) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-20T20:35:38.055Z |
| Generated at | 2026-08-20T20:40:00Z |
| Report at | 2026-08-20T20:40:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | btc_flying_rocket_20260820_1640 |
| Report filename | btc_flying_rocket_20260820_1640.json |
| Run ID | 20260820-2035-5595e232 |
| Snapshot ID | sha256:5595e232da279ccea117a0cb9542fc4d2f806ecf9063233a3ecacdea13263cb8 |
| Prior report | btc_flying_rocket_20260819_1222 |
| Prior report hash | 02b23c4a2846a28066dbc120157aa8a012869e70130c33ec3adf08da5ba71cca |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:5b6af85e952707e28164714ff31ebeacf6b69ec5b0c63835350110def1620aa1 |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | • STAND_DOWN | FR-A-1A-BTC-20260820-1640 | crypto |
| 1B | • STAND_DOWN | FR-A-1B-BTC-20260820-1640 | crypto |
| 2 | • STAND_DOWN | FR-A-2-BTC-20260820-1640 | crypto |
| 3 | • STAND_DOWN | FR-A-3-BTC-20260820-1640 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FR-A-1A-BTC-20260820-1640, FR-A-1B-BTC-20260820-1640, FR-A-2-BTC-20260820-1640, FR-A-3-BTC-20260820-1640

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":1,"field":"score.mechanical","previous":8,"reason":"Routing moved from Channel B to cap-bound Channel A record scoring; the two rubrics measure different objects."},{"current":"none","field":"channel","previous":"B","reason":"Price moved above the falling 200dma."},{"current":"DATA_LIMITED","field":"position","previous":"EXPIRED cold start","reason":"Event-driven snapshot is fresh but custody/basis are defective; exact position claim is refused."}],"channel":"none","companion_framework":{"framework":"fallen_knives","gates":3,"rationale":"Same-timestamp FK: sentiment 1, momentum 2, valuation 3, liquidation capitulation 1, holder behavior 0 = 7. Gates 3, 7 and 9 pass. FK <12.","score":7,"status":"AVAILABLE"},"cross_validation":{"rationale":"Channel A is cap-bound and no channel is live; neither score is >=12, so the Hard Rule 5 inconsistency test does not fire.","relationship":"FR 1 versus FK 7","status":"CONSISTENT"},"data_gaps":[{"field":"BTC custody and basis reconciliation","impact":"No quantity, basis, unrealized PnL or ROI may be quoted.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"True LTH distribution","impact":"No gate-7 or distribution sub-leg (a) credit.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"30-day crypto/equity correlation","impact":"Risk-on gate surcharge defaults ON.","source_ids":["snapshot"],"status":"NOT_COVERED"}],"deployment":{"deployed_pct":"0","dry_pct":"50","throttle_released":false,"tranches":[{"channel":"A","deployed":false,"entry_price":null,"pct":"5","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 11, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-1A-BTC-20260820-1640","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"10","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 13, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-1B-BTC-20260820-1640","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"15","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 15, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-2-BTC-20260820-1640","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"20","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 19, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-3-BTC-20260820-1640","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"0.50x77500 + 0.30x71000 + 0.15x65000 + 0.05x58500 = 72725. Directional short EV +0.22%; true positive-funding carry adds +0.37% over 21 days but is floored to zero for gating. Gate EV +0.22%, below +3%; corroborative only because no channel is live.","probability_sum":1,"scenarios":[{"high":"82000","low":"73000","mid":"77500","name":"Recovery extends","probability":0.5,"rationale":"Squeeze and regulatory optimism can extend without a completed trend repair."},{"high":"74000","low":"68000","mid":"71000","name":"Range / base","probability":0.3,"rationale":"Consolidation around the reclaimed 200dma."},{"high":"68000","low":"62000","mid":"65000","name":"Mean reversion","probability":0.15,"rationale":"Requires a failed reclaim."},{"high":"62000","low":"55000","mid":"58500","name":"Bear continuation","probability":0.05,"rationale":"Requires renewed lower-low structure; absent."}],"stated_ev":"72725","vs_spot_pct":"-0.22"},"events":[{"as_of":"2026-08-20","impact":"A record-scale short squeeze and regulatory optimism drove BTC above $72K; shorting the rising session is prohibited by discipline.","name":"Crypto short squeeze","status":"AVAILABLE"},{"as_of":"2026-08-20","impact":"CFTC innovation meeting keeps regulatory surprise risk live.","name":"CFTC Innovation Advisory Committee","status":"AVAILABLE"},{"as_of":"2026-09-15","impact":"Next FOMC decision; beyond any permissible fresh 1A clock.","name":"FOMC","status":"AVAILABLE"}],"evidence":{"derivatives":{"as_of":"2026-08-20T20:35:21Z","confidence":"HIGH","rationale":"Funding +6.37% annualized; OI 6.10% below its 90-day high; skew nearly flat at +0.12.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Binance/Deribit context","value":"not euphoric enough"},"etf_flows":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"Aug 17-19 inflows total $1.004B, but three sessions do not overturn the prior five-session regime under the durability lock.","source_ids":["farside_btc"],"status":"AVAILABLE","unit":"USD millions","value":"1004.0"},"funding":{"as_of":"2026-08-20T20:35:21Z","confidence":"HIGH","rationale":"Positive funding means longs pay shorts; no sustained negative prints.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"6.37"},"macro":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"VIX and yields rose, but DXY fell and regulatory optimism triggered a large squeeze.","source_ids":["snapshot","market_news","cftc"],"status":"AVAILABLE","unit":null,"value":"mixed"},"momentum":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"Daily RSI 80.22 is locally extreme, but completed-week RSI 38.80 keeps Channel-A momentum at zero.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"daily RSI-14","value":"80.22"},"onchain":{"as_of":"2026-08-19","confidence":"MEDIUM","rationale":"MVRV-Z 0.547; exchange reserves rose 2.99% over 30d; true LTH is provider-gated.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"low valuation / reserves rising"},"regime":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"42.25% below high but price 5.67% above falling 200dma; no channel.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"none"},"sentiment":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"F&G spot 62, three-day average 49.67.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"F&G 3-day average","value":"49.67"},"spot":{"as_of":"2026-08-20T20:35:21Z","confidence":"HIGH","rationale":"Four-source synchronized median; 0.038% spread.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"72884.395"}},"falsifiers":[{"claim":"No channel is live","condition":"A completed daily move back below 68974.61 with falling slope reopens Channel-B routing for a full rerun.","status":"AVAILABLE"},{"claim":"Recovery-extension remains modal","condition":"A close below 68000 followed by a failed 200dma reclaim shifts mass toward mean reversion.","status":"AVAILABLE"},{"claim":"No short is authorized","condition":"A fresh report must show a live channel, adjusted score at its line, converted gates, preflight pass and gate EV >3%.","status":"AVAILABLE"}],"gates":{"active":8,"alt_reading":null,"measurement_basis":{"1":"FAIL — crypto F&G is not >=80 for seven days.","2":"FAIL — completed-week RSI is 38.80, below 70.","3":"FAIL — MVRV-Z is 0.547, below 3.","4":"FAIL — funding is 6.37% annualized, below 25%.","5":"WARNING/capitulation-context — ETF flows improved, but price is >20% below its high; gate cannot confirm distribution.","6":"FAIL — Coinbase Premium is positive on all three completed days.","7":"UNMEASURED/FAIL — true LTH distribution is provider-gated; no substitute is used.","8":"N/A — asset is >15% below its own high; top-coincident breadth divergence is structurally inapplicable.","9":"WARNING/capitulation-context — rotation gate cannot count while price is >20% below the high.","stall":"NOT APPLICABLE — Channel B is not live."},"na":[8],"passed":[],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":8}},"identity":{"asset":"BTC","date":"2026-08-20","filename":"btc_flying_rocket_20260820_1640.json","framework":"flying_rocket","local_time":"16:40","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-10-06","note":"Yahoo trailing-one-year weekly high.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC one-year high","value":"126198.07"},"drawdown_pct":{"as_of":"2026-08-20","note":"Channel routing input.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent below one-year high","value":"42.25"},"metrics":{"adr5":{"as_of":"2026-08-20","note":"Five full crypto sessions; 1.5xADR noise floor is 5.51% of spot.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"2676.28"},"borrow":{"as_of":"2026-08-20","note":"Bitfinex single-venue lending proxy; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"0.0011"},"correlation_spx":{"as_of":"2026-08-20","note":"Not computed this cycle; conservative risk-on gate surcharge defaults ON.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"Pearson daily log returns","value":null},"daily_rsi":{"as_of":"2026-08-20","note":"Current daily series after the squeeze rally.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"80.22"},"options_skew":{"as_of":"2026-08-20","note":"Moneyness-based, not a 25-delta risk reversal; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"vol points put-rich","value":"0.12"},"weekly_rsi":{"as_of":"week of 2026-08-10","note":"Completed week; current partial week excluded.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"38.80"}},"reconciliation":{"method":"Median of four synchronized live sources; frozen Yahoo close excluded","note":"Spread 0.038% is below 0.5%; no two-extremes EV test is required.","quotes":[{"instrument":"CoinGecko BTC","state":"live","value":"72890"},{"instrument":"Binance BTCUSDT","state":"live","value":"72887.16"},{"instrument":"Coinbase BTC-USD","state":"live","value":"72881.63"},{"instrument":"Kraken XBTUSD","state":"receipt-time","value":"72862"}],"spread_pct":"0.038","status":"AVAILABLE"},"regime":{"bounce_age_sessions":38,"bounce_pct":"18.00","channel":"none","ma200":"68974.61","ma200_slope20_pct":"-3.47","ma50":"64230.54","price_vs_ma200_pct":"5.67","stall_confirmation":false},"spot":{"as_of":"2026-08-20T20:35:21Z","note":"Canonical synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"72884.395"}},"narrative":{"arguments":{"discretion_ledger":[{"channel":"S1","date":"2026-08-20","load_bearing":false,"outcome":"STAND_DOWN","reason":"Rubric captures the tape; no discretionary score term is warranted.","s2":false,"term":"0.0"}],"prior_forecast_grade":"The Aug-19 EV_price 67700 was exceeded by 7.66%; spot 72884 remains inside the prior 69000-74000 rally-extension band. The completed-week 200dma-reclaim falsifier has not yet finalized.","stop_migration_ledger":[]},"bear_case":"Daily RSI 80.22 is stretched and the 200dma is still falling. IF BTC falls back below 68974.61 and prints a lower-close/lower-high stall, THEN Channel B can be re-evaluated from scratch.","bull_case":"Price is 5.67% above the falling 200dma, ETF inflows reached $1.004B over three completed sessions, Coinbase Premium is positive and the squeeze catalyst remains live. IF a completed week holds above the 200dma, THEN Channel B stays void.","primary_action":{"rationale":"No channel, no confirmation gates and +0.22% gate EV. The ledger has no corroborated BTC short and the custody/basis defect blocks any quantity claim.","status":"AVAILABLE","value":"STAND_DOWN"},"rationale":"ROUTE — BTC is 42.25% below its one-year high but 5.67% above a falling 200dma. Channel A is cap-dead and Channel B fails price-below-MA: NONE / STAND DOWN.\nSCORE — F&G 49.67, weekly RSI 38.80 and MVRV-Z 0.547 all give Channel-A short legs 0. The prior five-session ETF-outflow regime contributes distribution 1 under the durability lock; funding, options and breadth add no vulnerability. Mechanical/adjusted 1, discretion 0.0. Gates 0/8; correlation not computed, so the conservative gate surcharge defaults ON.\nEV — Scenario EV 72725 gives +0.22% directional/gate short EV versus 72884.395. Positive funding income is floored to zero for gating; +0.22% fails +3%. Collar ON and EV is corroborative only.\nPOSITION — Event-driven snapshot is fresh but BTC custody is UNEXPLAINED and basis unreliable. Hard Rule 8 forbids a quantity, cost basis, unrealized PnL or ROI claim. No corroborated BTC short exists. Dry powder is $10,741.578, but nothing is sized against a defective asset ledger.\nACTIONS — 1) Stand down; do not short the squeeze. 2) Fix BTC custody and basis reconciliation. 3) Re-run only after a completed structural test below the 200dma or a genuine Channel-A return near the high.","summary":"BTC has no live short channel after moving 5.67% above its falling 200dma. Score is 1/20, gates 0/8 and gate EV +0.22%. Stand down; the asset ledger remains unreconciled and cannot support sizing."},"out_of_scope":["No order execution was performed.","Context-panel fields are not promoted into rubric legs or gates."],"position":{"asset":"BTC","attribution":{"active_tags":[],"note":"Open BTC deals are untagged; no framework short is corroborated.","status":"UNKNOWN"},"basis":{"avg_cost":null,"reason":"Twelve unbacked disposals make cost basis non-derivable; no average cost, basis, unrealized PnL or ROI is quoted.","reliable":false,"total_cost":null},"custody":{"reason":"Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap.","status":"UNEXPLAINED"},"dry_powder":"10741.5780","futures":[],"pnl":{"reason":"Basis and custody defects prohibit a PnL claim.","status":"DATA_LIMITED","unrealized":null},"quantity":null,"reconciliation":"Event-driven snapshot is fresh, but BTC custody is UNEXPLAINED and basis is unreliable. No position figure in either direction is reported.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"No corroborated BTC short exists; custody and basis defects block any position-level control or sizing claim.","status":"DATA_LIMITED","value":"STAND_DOWN"},"required":true,"status":"DATA_LIMITED"},"regime":{"ma200_falling":true,"pct_below_1y_ath":"42.25","price_below_ma200":false},"report_id":"btc_flying_rocket_20260820_1640","risk_controls":{"carry":{"carry_veto":false,"funding_annualized_pct":"6.37","gate_carry_ev_pct":"0","minimum_edge_pass":false,"status":"AVAILABLE","true_carry_ev_pct_21d":"0.37"},"concentration":{"channel_a_asset_cap_pct":"50","planned_pct":"0","status":"PASS","total_short_book_cap_pct":"50"},"ratchet":{"reason":"No corroborated BTC short or auditable prior stop exists.","status":"NOT_APPLICABLE"},"stops":{"adr5":"2676.28","channel_a_1a_ceiling_pct":"8.00","initial_floor_pct":"5.51","note":"Informational only; no new fill is authorized.","status":"LOCKED"},"time_stops":{"p1a_days":21,"p1b_days":28,"p2_days":35,"p3_days":49,"status":"LOCKED"}},"run":{"prior_report_id":"btc_flying_rocket_20260819_1222","prior_report_sha256":"02b23c4a2846a28066dbc120157aa8a012869e70130c33ec3adf08da5ba71cca","run_id":"20260820-2035-5595e232","snapshot_id":"sha256:5595e232da279ccea117a0cb9542fc4d2f806ecf9063233a3ecacdea13263cb8","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:5b6af85e952707e28164714ff31ebeacf6b69ec5b0c63835350110def1620aa1","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":1,"caps":[{"field":"phase_of_cycle","reason":"BTC is more than 20% below its one-year high; Channel A is capped at 8 and no phase is reachable.","value":8},{"field":"channel","reason":"Price is above the falling 200dma, so Channel B precondition fails.","value":"none"},{"field":"squeeze_trap","reason":"Funding is not sustained below -5% annualized.","value":0}],"discretion":0,"legs":{"distribution":1,"euphoria":0,"momentum":0,"valuation":0,"vulnerability":0},"mechanical":1,"penalties":[],"raw":1,"rounding":"half-up"},"sources":{"cftc":{"as_of":"2026-08-20","kind":"primary regulator event","name":"CFTC Innovation Advisory Committee meeting","note":"Inaugural meeting on crypto regulatory evolution; bullish-regulatory surprise risk for shorts.","retrieved_at":"2026-08-20T20:37:00Z","url":"https://www.cftc.gov/PressRoom/Events/opaeventiac082026"},"farside_btc":{"as_of":"2026-08-19","kind":"primary ETF flow table","name":"Farside US BTC ETF daily flows","note":"Completed-session flows through August 19; August 20 incomplete and excluded.","retrieved_at":"2026-08-20T20:36:00Z","url":"https://farside.co.uk/btc/"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"user ledger","name":"Personal-accounting position snapshot","note":"FRESH under the event-driven policy; asset custody defects are handled separately and fail closed.","retrieved_at":"2026-08-20T20:34:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"market_news":{"as_of":"2026-08-20","kind":"market news","name":"Crypto rally and short-squeeze coverage","note":"BTC and ETH rally amplified by short covering and regulatory optimism; used as catalyst context, not a scored leg.","retrieved_at":"2026-08-20T20:37:00Z","url":"https://ng.investing.com/news/cryptocurrency-news/bitcoin-hits-72k-after-trump-calls-for-clear-crypto-legislation-2667941"},"snapshot":{"as_of":"2026-08-20T20:35:38.055Z","kind":"computed","name":"Deterministic BTC/ETH/macro live snapshot","note":"Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Coin Metrics, Coinbase Premium, Bitfinex borrow, Deribit options and FRED/Yahoo macro.","retrieved_at":"2026-08-20T20:35:38.055Z","url":"data/runs/20260820-2035-5595e232/snapshot.json"}},"stale_inputs":[],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FR-A-1A-BTC-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FR-A-1B-BTC-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FR-A-2-BTC-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FR-A-3-BTC-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FR-A-1A-BTC-20260820-1640","FR-A-1B-BTC-20260820-1640","FR-A-2-BTC-20260820-1640","FR-A-3-BTC-20260820-1640"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-20T20:35:38.055Z","generated_at":"2026-08-20T20:40:00Z","report_at":"2026-08-20T20:40:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"No channel, no confirmation gates and +0.22% gate EV. The ledger has no corroborated BTC short and the custody/basis defect blocks any quantity claim.","status":"AVAILABLE","value":"STAND_DOWN"},"statement":"No BTC short: no channel is live, adjusted score 1/20, gates 0/8, and gate EV +0.22%. Stand down and fix ledger reconciliation before sizing anything.","status":"STAND_DOWN"},"watchlist":[{"item":"200dma route","status":"AVAILABLE","trigger":"Price back below 68974.61 with falling slope reopens Channel B only after full rerun."},{"item":"Completed weekly reclaim","status":"AVAILABLE","trigger":"A completed week above the 200dma keeps Channel B void."},{"item":"FK force-cover","status":"AVAILABLE","trigger":"FK >=12 would independently force cover; current computed FK is 7."},{"item":"Ledger repair","status":"DATA_LIMITED","trigger":"Reconcile custody and basis before any position sizing."}]}
```
