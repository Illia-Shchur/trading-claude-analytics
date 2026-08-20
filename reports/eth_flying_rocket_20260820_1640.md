# ETH — Flying Rocket — 2026-08-20 16:40

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | ETH · Flying Rocket |
| Report time | 2026-08-20 16:40 (America/New_York) |
| Verdict | • STAND_DOWN — No ETH short channel: adjusted 2/20, 0/8 gates, and +1.67% gate EV. Ledger custody is defective; verify the venue and cover any remaining ETH short immediately. |
| Adjusted score | **2/20** (mechanical 2, raw 2) |
| Confirmation gates | 0/8 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable ETH |
| Deployment | 0% deployed · 50% dry |
| Primary action | **VERIFY_AND_COVER** — Custody is UNEXPLAINED, so no quantity may be quoted. If the corroborated margin short still exists at the venue, every framework time stop is long expired: cover it in full and do not add. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $2,327.625/ETH | ✅ AVAILABLE | — | 2026-08-20T20:35:21Z | Canonical synchronized median.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| All-time high | $4,796.35/ETH one-year high | ✅ AVAILABLE | — | 2025-08-25 | Yahoo trailing-one-year weekly high.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Drawdown from ATH | 51.47% | ✅ AVAILABLE | — | 2026-08-20 | Channel routing input.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| ADR-5 | $129.34 | ✅ AVAILABLE | — | 2026-08-20 | Five full crypto sessions; 1.5xADR noise floor is 8.34% of spot.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Borrow | 1.6588% | ✅ AVAILABLE | — | 2026-08-20 | Bitfinex single-venue lending proxy; context only.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Correlation spx | — | ⚠️ DATA_LIMITED | — | 2026-08-20 | Not computed this cycle; conservative risk-on gate surcharge defaults ON.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Daily rsi | 84.42 RSI-14 | ✅ AVAILABLE | — | 2026-08-20 | Current daily series after the squeeze rally.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Options skew | -3.34 vol points put-rich | ✅ AVAILABLE | — | 2026-08-20 | Moneyness-based, not a 25-delta risk reversal; context only.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Weekly RSI-14 | 41.92 RSI-14 | ✅ AVAILABLE | — | week of 2026-08-10 | Completed week; current partial week excluded.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |

**Regime:** — — Bounce age sessions: 38; Bounce pct: 33.06%; Channel: none; Ma200: 2003.56; Ma200 slope20 pct: -5.04%; Ma50: 1877.19; Price vs ma200 pct: 16.17%; Stall confirmation: No

### Spot reconciliation

**✅ AVAILABLE** — Median of four synchronized live sources; frozen Yahoo close excluded; spread 0.061%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko ETH | $2,328.62/ETH | ✅ live | — |
| Binance ETHUSDT | $2,327.86/ETH | ✅ live | — |
| Coinbase ETH-USD | $2,327.39/ETH | ✅ live | — |
| Kraken ETHUSD | $2,327.21/ETH | • receipt-time | — |

> Spread 0.061% is below 0.5%; no two-extremes EV test is required.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Derivatives | call-rich / OI high Binance/Deribit context | ✅ AVAILABLE | HIGH | 2026-08-20T20:35:21Z | Funding +4.82% annualized; OI at its 90-day high; skew -3.34 is call-rich. No squeeze-trap penalty.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Etf flows | 289.1 USD millions | ✅ AVAILABLE | HIGH | 2026-08-19 | Aug 17-19 inflows total $289.1M, but three sessions do not overturn the prior five-session regime under the durability lock.<br>Sources: [farside_eth](https://farside.co.uk/eth/) |
| Funding | 4.82% | ✅ AVAILABLE | HIGH | 2026-08-20T20:35:21Z | Positive funding means longs pay shorts; no sustained negative prints.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Macro | mixed | ✅ AVAILABLE | HIGH | 2026-08-20 | VIX and yields rose, but DXY fell and crypto regulatory optimism triggered a large squeeze.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json), [market_news](https://ng.investing.com/news/cryptocurrency-news/bitcoin-hits-72k-after-trump-calls-for-clear-crypto-legislation-2667941), [cftc](https://www.cftc.gov/PressRoom/Events/opaeventiac082026) |
| Momentum | 84.42 daily RSI-14 | ✅ AVAILABLE | HIGH | 2026-08-20 | Daily RSI 84.42 is extreme locally, but completed-week RSI 41.92 keeps the Channel-A momentum leg at zero.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Onchain | low valuation / reserve decline | ✅ AVAILABLE | MEDIUM | 2026-08-19 | MVRV-Z 0.129; exchange reserves down 0.91% over 30d; true LTH is provider-gated.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Regime | none | ✅ AVAILABLE | HIGH | 2026-08-20 | 51.47% below high but price 16.17% above falling 200dma; no channel.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Sentiment | 49.67 F&G 3-day average | ✅ AVAILABLE | HIGH | 2026-08-20 | F&G spot 62, three-day average 49.67.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |
| Spot | $2,327.625/ETH | ✅ AVAILABLE | HIGH | 2026-08-20T20:35:21Z | Four-source synchronized median; 0.061% spread.<br>Sources: [snapshot](data/runs/20260820-2035-5595e232/snapshot.json) |

**Data gaps:** 3 · **stale inputs:** 0 · **out of scope:** 2

**Data gaps**

- **ETH custody reconciliation** — ⚠️ DATA_LIMITED — No quantity, basis, unrealized PnL or ROI may be quoted; venue verification is mandatory.
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
| Vulnerability | 1 | 3 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 2 | Legs plus penalties |
| Raw score | 2 | Mechanical plus discretion (0) |
| Adjusted score | **2/20** | Decision score |
| Rounding | half-down | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Phase of cycle | 8 | ETH is more than 20% below its one-year high; Channel A is capped at 8 and no phase is reachable. |
| Channel | none | Price is above the falling 200dma, so Channel B precondition fails. |
| Squeeze trap | 0 | Funding is not sustained below -5% annualized. |

### Confirmation gates — 0/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | FAIL — crypto F&G is not >=80 for seven days. |
| 2 | • NOT PASSED | FAIL — completed-week RSI is 41.92, below 70. |
| 3 | • NOT PASSED | FAIL — MVRV-Z is 0.129, below 3. |
| 4 | • NOT PASSED | FAIL — funding is 4.82% annualized, below 25%. |
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
| Recovery extends | 50% | $2,300/ETH | $2,700/ETH | $2,500/ETH | Vertical squeeze and regulatory optimism can extend before structure resolves. |
| Range / base | 30% | $2,050/ETH | $2,350/ETH | $2,200/ETH | Consolidation above the reclaimed 200dma. |
| Mean reversion | 15% | $1,850/ETH | $2,050/ETH | $1,950/ETH | Requires a failed reclaim. |
| Bear continuation | 5% | $1,600/ETH | $1,850/ETH | $1,725/ETH | Requires fresh lower-low structure; absent. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $2,288.75/ETH |
| EV versus spot | -1.67% |

> 0.50x2500 + 0.30x2200 + 0.15x1950 + 0.05x1725 = 2288.75. Directional short EV +1.67%; true positive-funding carry adds +0.28% over 21 days but is floored to zero for gating. Gate EV +1.67%, below +3%; corroborative only because no channel is live.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 50% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 5% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1A-ETH-20260820-1640 | Locked: adjusted score is below 11, confirmation stack is incomplete, and no new short is authorized. |
| 1B | 10% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1B-ETH-20260820-1640 | Locked: adjusted score is below 13, confirmation stack is incomplete, and no new short is authorized. |
| 2 | 15% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-2-ETH-20260820-1640 | Locked: adjusted score is below 15, confirmation stack is incomplete, and no new short is authorized. |
| 3 | 20% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-3-ETH-20260820-1640 | Locked: adjusted score is below 19, confirmation stack is incomplete, and no new short is authorized. |

## 6. Position, custody, and execution controls

| Position field | Value |
| --- | --- |
| Status | ⚠️ DATA_LIMITED |
| Asset | ETH |
| Quantity | — |
| Dry powder | $10,741.5780 |
| Basis reliable | yes |
| Average cost | — |
| Total cost basis | — |
| Custody | • UNEXPLAINED |
| Attribution | ❔ UNKNOWN |
| Active tags | None |

### Custody reconciliation

| Field | Value |
| --- | --- |
| Reason | Live balance and fill replay disagree; withdrawals and migration seeds do not explain the gap. Hard Rule 8 forbids a position figure. |
| Status | • UNEXPLAINED |

### Cost basis

| Field | Value |
| --- | --- |
| Reason | The replayed basis may be internally derivable, but custody defect prevents quoting position basis or PnL. |
| Reliable | Yes |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Note | Open ETH deals are untagged; phase attribution cannot be inferred. |
| Status | ❔ UNKNOWN |

### Position P&L

| Field | Value |
| --- | --- |
| Reason | No unrealized PnL or ROI is stated through UNEXPLAINED custody. |
| Status | ⚠️ DATA_LIMITED |

> **Position reconciliation:** Event-driven snapshot is fresh, but ETH custody is UNEXPLAINED. The tool separately reports borrow-corroborated short evidence; exact quantity is deliberately refused until venue reconciliation.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| ⚠️ DATA_LIMITED | yes | **VERIFY_AND_COVER** — Verify the live venue liability. If any ETH short remains, cover 100% because every framework time stop is expired; exact quantity is unavailable. |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Carry veto | No |
| Funding annualized pct | 4.82% |
| Gate carry ev pct | 0% |
| Minimum edge pass | No |
| Status | ✅ AVAILABLE |
| True carry ev pct 21d | 0.28 |

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
| Reason | No reliable current quantity or prior framework stop can be asserted through UNEXPLAINED custody. |
| Status | ⚠️ DATA_LIMITED |

### Stops

| Field | Value |
| --- | --- |
| ADR-5 | 129.34 |
| Channel a 1a ceiling pct | 8.00% |
| Initial floor pct | 8.34% |
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

**Summary:** ETH has no live short channel after moving 16.17% above its falling 200dma. Score is 2/20, gates 0/8 and gate EV +1.67%. The ledger custody defect blocks a quantity claim; verify and cover any remaining ETH short immediately.

**Bull case:** Price is 16.17% above the falling 200dma, ETF inflows accelerated for three sessions, Coinbase Premium is positive and a regulatory/short-squeeze catalyst is live. IF a completed week holds above the 200dma, THEN Channel B remains void.

**Bear case:** Daily RSI 84.42 and call-rich skew are locally stretched. IF ETH falls back below 2003.56 and the weekly close fails the reclaim, THEN Channel B may reactivate only after a fresh full rerun.

**Rationale:** ROUTE — ETH is 51.47% below its one-year high but 16.17% above a falling 200dma. Channel A is cap-dead and Channel B fails its price-below-MA precondition: NONE / STAND DOWN.
> SCORE — F&G 49.67, weekly RSI 41.92 and MVRV-Z 0.129 give Channel-A short legs 0/0/0. The five-session prior ETF-outflow regime contributes distribution 1 under the durability lock; call-rich -3.34 skew contributes vulnerability 1. Mechanical/adjusted 2, discretion 0.0. Gates 0/8; default risk-on surcharge is ON because correlation was not computed.
> EV — 2288.75 scenario EV gives +1.67% directional/gate short EV versus 2327.625. Positive funding income is floored to zero for gating; +1.67% fails +3%. Collar ON and EV is corroborative only.
> POSITION — The event-driven snapshot is structurally fresh, but ETH custody is UNEXPLAINED. Hard Rule 8 forbids quoting any quantity, basis, unrealized PnL or ROI. Separately, the ledger contains corroborated borrow evidence for an ETH margin short. Because the position appears to date to 2023, every allowable FR time stop has expired many times over. Verify the venue; if any short remains, COVER 100% now. Exact quantity is deliberately omitted until reconciliation.
> ACTIONS — 1) Verify the live ETH margin liability and open orders. 2) Cover any remaining short in full; no add. 3) Fix custody reconciliation before any position sizing. 4) Re-run only after a completed structural test.

**Primary action:** **VERIFY_AND_COVER** — Custody is UNEXPLAINED, so no quantity may be quoted. If the corroborated margin short still exists at the venue, every framework time stop is long expired: cover it in full and do not add.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Prior forecast grade | The Aug-19 EV_price 2100 was exceeded by 10.84%; spot remains inside the prior 2100-2400 recovery-extension band. The no-channel route held. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-20 | S1 | — | — | — | — | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | fallen_knives · 8/20 · 4 gates | Same-timestamp FK: sentiment 1, momentum 1, valuation 4, liquidation capitulation 1, exchange-reserve decline 1.5; ETH half-down rounds 8. Gates 3, 6, 7 and 9 pass on the eight-gate schema. FK <12. |
| Cross-validation | ✅ CONSISTENT | FR 2 versus FK 8 | Channel A is cap-bound and no channel is live; neither score is >=12, so the Hard Rule 5 inconsistency test does not fire. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| Venue reconciliation | ⚠️ DATA_LIMITED | Confirm ETH liability, exact net short and any protective orders; do not infer quantity from the defective snapshot. |
| 200dma route | ✅ AVAILABLE | Price back below 2003.56 with falling slope reopens Channel B only after full rerun. |
| FK force-cover | ✅ AVAILABLE | FK >=12 would independently force 100% cover; current computed FK is 8. |
| Funding veto | ✅ AVAILABLE | Three consecutive intervals annualized below -5% activates squeeze-trap protection. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-20 | Crypto short squeeze | ✅ AVAILABLE | ETH rose roughly 20% as short covering and regulatory optimism accelerated; this is dangerous upside narrative risk for shorts. |
| 2026-08-20 | CFTC Innovation Advisory Committee | ✅ AVAILABLE | CFTC innovation meeting keeps regulatory surprise risk live. |
| 2026-09-15 | FOMC | ✅ AVAILABLE | Next FOMC decision; well beyond any permissible fresh 1A clock. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| No channel is live | A completed daily move back below 2003.56 with the 200dma still falling reopens Channel-B routing for a full rerun. | ✅ AVAILABLE |
| Recovery-extension remains modal | A close below 2050 followed by a failed 200dma reclaim shifts mass toward mean reversion. | ✅ AVAILABLE |
| Any old short should be covered | No price path can un-expire a time stop; only proof that no short exists resolves the action. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.mechanical | 0 | 2 | ETF outflow durability and call-rich skew add one point each. |
| Channel | none | none | Price remains above the falling 200dma. |
| Position | EXPIRED cold start | DATA_LIMITED | Event-driven snapshot is fresh but custody is UNEXPLAINED; exact quantity is refused. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| cftc | CFTC Innovation Advisory Committee meeting | primary regulator event | 2026-08-20 | 2026-08-20T20:37:00Z | Inaugural meeting on crypto regulatory evolution; bullish-regulatory surprise risk for shorts.<br>[Open source](https://www.cftc.gov/PressRoom/Events/opaeventiac082026) |
| farside_eth | Farside US ETH ETF daily flows | primary ETF flow table | 2026-08-19 | 2026-08-20T20:36:00Z | Completed-session flows through August 19; August 20 incomplete and excluded.<br>[Open source](https://farside.co.uk/eth/) |
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
| Report ID | eth_flying_rocket_20260820_1640 |
| Report filename | eth_flying_rocket_20260820_1640.json |
| Run ID | 20260820-2035-5595e232 |
| Snapshot ID | sha256:5595e232da279ccea117a0cb9542fc4d2f806ecf9063233a3ecacdea13263cb8 |
| Prior report | eth_flying_rocket_20260819_1223 |
| Prior report hash | 05eff905090caca9649df6f6f63771262568cc76fcb75554fc7dab6837893d6e |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:5b6af85e952707e28164714ff31ebeacf6b69ec5b0c63835350110def1620aa1 |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | • STAND_DOWN | FR-A-1A-ETH-20260820-1640 | crypto |
| 1B | • STAND_DOWN | FR-A-1B-ETH-20260820-1640 | crypto |
| 2 | • STAND_DOWN | FR-A-2-ETH-20260820-1640 | crypto |
| 3 | • STAND_DOWN | FR-A-3-ETH-20260820-1640 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FR-A-1A-ETH-20260820-1640, FR-A-1B-ETH-20260820-1640, FR-A-2-ETH-20260820-1640, FR-A-3-ETH-20260820-1640

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":2,"field":"score.mechanical","previous":0,"reason":"ETF outflow durability and call-rich skew add one point each."},{"current":"none","field":"channel","previous":"none","reason":"Price remains above the falling 200dma."},{"current":"DATA_LIMITED","field":"position","previous":"EXPIRED cold start","reason":"Event-driven snapshot is fresh but custody is UNEXPLAINED; exact quantity is refused."}],"channel":"none","companion_framework":{"framework":"fallen_knives","gates":4,"rationale":"Same-timestamp FK: sentiment 1, momentum 1, valuation 4, liquidation capitulation 1, exchange-reserve decline 1.5; ETH half-down rounds 8. Gates 3, 6, 7 and 9 pass on the eight-gate schema. FK <12.","score":8,"status":"AVAILABLE"},"cross_validation":{"rationale":"Channel A is cap-bound and no channel is live; neither score is >=12, so the Hard Rule 5 inconsistency test does not fire.","relationship":"FR 2 versus FK 8","status":"CONSISTENT"},"data_gaps":[{"field":"ETH custody reconciliation","impact":"No quantity, basis, unrealized PnL or ROI may be quoted; venue verification is mandatory.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"True LTH distribution","impact":"No gate-7 or distribution sub-leg (a) credit.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"30-day crypto/equity correlation","impact":"Risk-on gate surcharge defaults ON.","source_ids":["snapshot"],"status":"NOT_COVERED"}],"deployment":{"deployed_pct":"0","dry_pct":"50","throttle_released":false,"tranches":[{"channel":"A","deployed":false,"entry_price":null,"pct":"5","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 11, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-1A-ETH-20260820-1640","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"10","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 13, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-1B-ETH-20260820-1640","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"15","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 15, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-2-ETH-20260820-1640","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"20","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 19, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-3-ETH-20260820-1640","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"0.50x2500 + 0.30x2200 + 0.15x1950 + 0.05x1725 = 2288.75. Directional short EV +1.67%; true positive-funding carry adds +0.28% over 21 days but is floored to zero for gating. Gate EV +1.67%, below +3%; corroborative only because no channel is live.","probability_sum":1,"scenarios":[{"high":"2700","low":"2300","mid":"2500","name":"Recovery extends","probability":0.5,"rationale":"Vertical squeeze and regulatory optimism can extend before structure resolves."},{"high":"2350","low":"2050","mid":"2200","name":"Range / base","probability":0.3,"rationale":"Consolidation above the reclaimed 200dma."},{"high":"2050","low":"1850","mid":"1950","name":"Mean reversion","probability":0.15,"rationale":"Requires a failed reclaim."},{"high":"1850","low":"1600","mid":"1725","name":"Bear continuation","probability":0.05,"rationale":"Requires fresh lower-low structure; absent."}],"stated_ev":"2288.75","vs_spot_pct":"-1.67"},"events":[{"as_of":"2026-08-20","impact":"ETH rose roughly 20% as short covering and regulatory optimism accelerated; this is dangerous upside narrative risk for shorts.","name":"Crypto short squeeze","status":"AVAILABLE"},{"as_of":"2026-08-20","impact":"CFTC innovation meeting keeps regulatory surprise risk live.","name":"CFTC Innovation Advisory Committee","status":"AVAILABLE"},{"as_of":"2026-09-15","impact":"Next FOMC decision; well beyond any permissible fresh 1A clock.","name":"FOMC","status":"AVAILABLE"}],"evidence":{"derivatives":{"as_of":"2026-08-20T20:35:21Z","confidence":"HIGH","rationale":"Funding +4.82% annualized; OI at its 90-day high; skew -3.34 is call-rich. No squeeze-trap penalty.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Binance/Deribit context","value":"call-rich / OI high"},"etf_flows":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"Aug 17-19 inflows total $289.1M, but three sessions do not overturn the prior five-session regime under the durability lock.","source_ids":["farside_eth"],"status":"AVAILABLE","unit":"USD millions","value":"289.1"},"funding":{"as_of":"2026-08-20T20:35:21Z","confidence":"HIGH","rationale":"Positive funding means longs pay shorts; no sustained negative prints.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"4.82"},"macro":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"VIX and yields rose, but DXY fell and crypto regulatory optimism triggered a large squeeze.","source_ids":["snapshot","market_news","cftc"],"status":"AVAILABLE","unit":null,"value":"mixed"},"momentum":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"Daily RSI 84.42 is extreme locally, but completed-week RSI 41.92 keeps the Channel-A momentum leg at zero.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"daily RSI-14","value":"84.42"},"onchain":{"as_of":"2026-08-19","confidence":"MEDIUM","rationale":"MVRV-Z 0.129; exchange reserves down 0.91% over 30d; true LTH is provider-gated.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"low valuation / reserve decline"},"regime":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"51.47% below high but price 16.17% above falling 200dma; no channel.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"none"},"sentiment":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"F&G spot 62, three-day average 49.67.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"F&G 3-day average","value":"49.67"},"spot":{"as_of":"2026-08-20T20:35:21Z","confidence":"HIGH","rationale":"Four-source synchronized median; 0.061% spread.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/ETH","value":"2327.625"}},"falsifiers":[{"claim":"No channel is live","condition":"A completed daily move back below 2003.56 with the 200dma still falling reopens Channel-B routing for a full rerun.","status":"AVAILABLE"},{"claim":"Recovery-extension remains modal","condition":"A close below 2050 followed by a failed 200dma reclaim shifts mass toward mean reversion.","status":"AVAILABLE"},{"claim":"Any old short should be covered","condition":"No price path can un-expire a time stop; only proof that no short exists resolves the action.","status":"AVAILABLE"}],"gates":{"active":8,"alt_reading":null,"measurement_basis":{"1":"FAIL — crypto F&G is not >=80 for seven days.","2":"FAIL — completed-week RSI is 41.92, below 70.","3":"FAIL — MVRV-Z is 0.129, below 3.","4":"FAIL — funding is 4.82% annualized, below 25%.","5":"WARNING/capitulation-context — ETF flows improved, but price is >20% below its high; gate cannot confirm distribution.","6":"FAIL — Coinbase Premium is positive on all three completed days.","7":"UNMEASURED/FAIL — true LTH distribution is provider-gated; no substitute is used.","8":"N/A — asset is >15% below its own high; top-coincident breadth divergence is structurally inapplicable.","9":"WARNING/capitulation-context — rotation gate cannot count while price is >20% below the high.","stall":"NOT APPLICABLE — Channel B is not live."},"na":[8],"passed":[],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":8}},"identity":{"asset":"ETH","date":"2026-08-20","filename":"eth_flying_rocket_20260820_1640.json","framework":"flying_rocket","local_time":"16:40","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-08-25","note":"Yahoo trailing-one-year weekly high.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/ETH one-year high","value":"4796.35"},"drawdown_pct":{"as_of":"2026-08-20","note":"Channel routing input.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent below one-year high","value":"51.47"},"metrics":{"adr5":{"as_of":"2026-08-20","note":"Five full crypto sessions; 1.5xADR noise floor is 8.34% of spot.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"129.34"},"borrow":{"as_of":"2026-08-20","note":"Bitfinex single-venue lending proxy; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"1.6588"},"correlation_spx":{"as_of":"2026-08-20","note":"Not computed this cycle; conservative risk-on gate surcharge defaults ON.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"Pearson daily log returns","value":null},"daily_rsi":{"as_of":"2026-08-20","note":"Current daily series after the squeeze rally.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"84.42"},"options_skew":{"as_of":"2026-08-20","note":"Moneyness-based, not a 25-delta risk reversal; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"vol points put-rich","value":"-3.34"},"weekly_rsi":{"as_of":"week of 2026-08-10","note":"Completed week; current partial week excluded.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"41.92"}},"reconciliation":{"method":"Median of four synchronized live sources; frozen Yahoo close excluded","note":"Spread 0.061% is below 0.5%; no two-extremes EV test is required.","quotes":[{"instrument":"CoinGecko ETH","state":"live","value":"2328.62"},{"instrument":"Binance ETHUSDT","state":"live","value":"2327.86"},{"instrument":"Coinbase ETH-USD","state":"live","value":"2327.39"},{"instrument":"Kraken ETHUSD","state":"receipt-time","value":"2327.21"}],"spread_pct":"0.061","status":"AVAILABLE"},"regime":{"bounce_age_sessions":38,"bounce_pct":"33.06","channel":"none","ma200":"2003.56","ma200_slope20_pct":"-5.04","ma50":"1877.19","price_vs_ma200_pct":"16.17","stall_confirmation":false},"spot":{"as_of":"2026-08-20T20:35:21Z","note":"Canonical synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/ETH","value":"2327.625"}},"narrative":{"arguments":{"discretion_ledger":[{"channel":"S1","date":"2026-08-20","load_bearing":false,"outcome":"VERIFY_AND_COVER","reason":"Rubric captures the tape; no discretionary score term is warranted.","s2":false,"term":"0.0"}],"prior_forecast_grade":"The Aug-19 EV_price 2100 was exceeded by 10.84%; spot remains inside the prior 2100-2400 recovery-extension band. The no-channel route held.","stop_migration_ledger":[]},"bear_case":"Daily RSI 84.42 and call-rich skew are locally stretched. IF ETH falls back below 2003.56 and the weekly close fails the reclaim, THEN Channel B may reactivate only after a fresh full rerun.","bull_case":"Price is 16.17% above the falling 200dma, ETF inflows accelerated for three sessions, Coinbase Premium is positive and a regulatory/short-squeeze catalyst is live. IF a completed week holds above the 200dma, THEN Channel B remains void.","primary_action":{"rationale":"Custody is UNEXPLAINED, so no quantity may be quoted. If the corroborated margin short still exists at the venue, every framework time stop is long expired: cover it in full and do not add.","status":"AVAILABLE","value":"VERIFY_AND_COVER"},"rationale":"ROUTE — ETH is 51.47% below its one-year high but 16.17% above a falling 200dma. Channel A is cap-dead and Channel B fails its price-below-MA precondition: NONE / STAND DOWN.\nSCORE — F&G 49.67, weekly RSI 41.92 and MVRV-Z 0.129 give Channel-A short legs 0/0/0. The five-session prior ETF-outflow regime contributes distribution 1 under the durability lock; call-rich -3.34 skew contributes vulnerability 1. Mechanical/adjusted 2, discretion 0.0. Gates 0/8; default risk-on surcharge is ON because correlation was not computed.\nEV — 2288.75 scenario EV gives +1.67% directional/gate short EV versus 2327.625. Positive funding income is floored to zero for gating; +1.67% fails +3%. Collar ON and EV is corroborative only.\nPOSITION — The event-driven snapshot is structurally fresh, but ETH custody is UNEXPLAINED. Hard Rule 8 forbids quoting any quantity, basis, unrealized PnL or ROI. Separately, the ledger contains corroborated borrow evidence for an ETH margin short. Because the position appears to date to 2023, every allowable FR time stop has expired many times over. Verify the venue; if any short remains, COVER 100% now. Exact quantity is deliberately omitted until reconciliation.\nACTIONS — 1) Verify the live ETH margin liability and open orders. 2) Cover any remaining short in full; no add. 3) Fix custody reconciliation before any position sizing. 4) Re-run only after a completed structural test.","summary":"ETH has no live short channel after moving 16.17% above its falling 200dma. Score is 2/20, gates 0/8 and gate EV +1.67%. The ledger custody defect blocks a quantity claim; verify and cover any remaining ETH short immediately."},"out_of_scope":["No order execution was performed.","Context-panel fields are not promoted into rubric legs or gates."],"position":{"asset":"ETH","attribution":{"active_tags":[],"note":"Open ETH deals are untagged; phase attribution cannot be inferred.","status":"UNKNOWN"},"basis":{"reason":"The replayed basis may be internally derivable, but custody defect prevents quoting position basis or PnL.","reliable":true},"custody":{"reason":"Live balance and fill replay disagree; withdrawals and migration seeds do not explain the gap. Hard Rule 8 forbids a position figure.","status":"UNEXPLAINED"},"dry_powder":"10741.5780","futures":[],"pnl":{"reason":"No unrealized PnL or ROI is stated through UNEXPLAINED custody.","status":"DATA_LIMITED"},"quantity":null,"reconciliation":"Event-driven snapshot is fresh, but ETH custody is UNEXPLAINED. The tool separately reports borrow-corroborated short evidence; exact quantity is deliberately refused until venue reconciliation.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"Verify the live venue liability. If any ETH short remains, cover 100% because every framework time stop is expired; exact quantity is unavailable.","status":"DATA_LIMITED","value":"VERIFY_AND_COVER"},"required":true,"status":"DATA_LIMITED"},"regime":{"ma200_falling":true,"pct_below_1y_ath":"51.47","price_below_ma200":false},"report_id":"eth_flying_rocket_20260820_1640","risk_controls":{"carry":{"carry_veto":false,"funding_annualized_pct":"4.82","gate_carry_ev_pct":"0","minimum_edge_pass":false,"status":"AVAILABLE","true_carry_ev_pct_21d":"0.28"},"concentration":{"channel_a_asset_cap_pct":"50","planned_pct":"0","status":"PASS","total_short_book_cap_pct":"50"},"ratchet":{"reason":"No reliable current quantity or prior framework stop can be asserted through UNEXPLAINED custody.","status":"DATA_LIMITED"},"stops":{"adr5":"129.34","channel_a_1a_ceiling_pct":"8.00","initial_floor_pct":"8.34","note":"Informational only; no new fill is authorized.","status":"LOCKED"},"time_stops":{"p1a_days":21,"p1b_days":28,"p2_days":35,"p3_days":49,"status":"LOCKED"}},"run":{"prior_report_id":"eth_flying_rocket_20260819_1223","prior_report_sha256":"05eff905090caca9649df6f6f63771262568cc76fcb75554fc7dab6837893d6e","run_id":"20260820-2035-5595e232","snapshot_id":"sha256:5595e232da279ccea117a0cb9542fc4d2f806ecf9063233a3ecacdea13263cb8","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:5b6af85e952707e28164714ff31ebeacf6b69ec5b0c63835350110def1620aa1","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":2,"caps":[{"field":"phase_of_cycle","reason":"ETH is more than 20% below its one-year high; Channel A is capped at 8 and no phase is reachable.","value":8},{"field":"channel","reason":"Price is above the falling 200dma, so Channel B precondition fails.","value":"none"},{"field":"squeeze_trap","reason":"Funding is not sustained below -5% annualized.","value":0}],"discretion":0,"legs":{"distribution":1,"euphoria":0,"momentum":0,"valuation":0,"vulnerability":1},"mechanical":2,"penalties":[],"raw":2,"rounding":"half-down"},"sources":{"cftc":{"as_of":"2026-08-20","kind":"primary regulator event","name":"CFTC Innovation Advisory Committee meeting","note":"Inaugural meeting on crypto regulatory evolution; bullish-regulatory surprise risk for shorts.","retrieved_at":"2026-08-20T20:37:00Z","url":"https://www.cftc.gov/PressRoom/Events/opaeventiac082026"},"farside_eth":{"as_of":"2026-08-19","kind":"primary ETF flow table","name":"Farside US ETH ETF daily flows","note":"Completed-session flows through August 19; August 20 incomplete and excluded.","retrieved_at":"2026-08-20T20:36:00Z","url":"https://farside.co.uk/eth/"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"user ledger","name":"Personal-accounting position snapshot","note":"FRESH under the event-driven policy; asset custody defects are handled separately and fail closed.","retrieved_at":"2026-08-20T20:34:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"market_news":{"as_of":"2026-08-20","kind":"market news","name":"Crypto rally and short-squeeze coverage","note":"BTC and ETH rally amplified by short covering and regulatory optimism; used as catalyst context, not a scored leg.","retrieved_at":"2026-08-20T20:37:00Z","url":"https://ng.investing.com/news/cryptocurrency-news/bitcoin-hits-72k-after-trump-calls-for-clear-crypto-legislation-2667941"},"snapshot":{"as_of":"2026-08-20T20:35:38.055Z","kind":"computed","name":"Deterministic BTC/ETH/macro live snapshot","note":"Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Coin Metrics, Coinbase Premium, Bitfinex borrow, Deribit options and FRED/Yahoo macro.","retrieved_at":"2026-08-20T20:35:38.055Z","url":"data/runs/20260820-2035-5595e232/snapshot.json"}},"stale_inputs":[],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FR-A-1A-ETH-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FR-A-1B-ETH-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FR-A-2-ETH-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FR-A-3-ETH-20260820-1640","decision":"STAND_DOWN","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FR-A-1A-ETH-20260820-1640","FR-A-1B-ETH-20260820-1640","FR-A-2-ETH-20260820-1640","FR-A-3-ETH-20260820-1640"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-20T20:35:38.055Z","generated_at":"2026-08-20T20:40:00Z","report_at":"2026-08-20T20:40:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Custody is UNEXPLAINED, so no quantity may be quoted. If the corroborated margin short still exists at the venue, every framework time stop is long expired: cover it in full and do not add.","status":"AVAILABLE","value":"VERIFY_AND_COVER"},"statement":"No ETH short channel: adjusted 2/20, 0/8 gates, and +1.67% gate EV. Ledger custody is defective; verify the venue and cover any remaining ETH short immediately.","status":"STAND_DOWN"},"watchlist":[{"item":"Venue reconciliation","status":"DATA_LIMITED","trigger":"Confirm ETH liability, exact net short and any protective orders; do not infer quantity from the defective snapshot."},{"item":"200dma route","status":"AVAILABLE","trigger":"Price back below 2003.56 with falling slope reopens Channel B only after full rerun."},{"item":"FK force-cover","status":"AVAILABLE","trigger":"FK >=12 would independently force 100% cover; current computed FK is 8."},{"item":"Funding veto","status":"AVAILABLE","trigger":"Three consecutive intervals annualized below -5% activates squeeze-trap protection."}]}
```
