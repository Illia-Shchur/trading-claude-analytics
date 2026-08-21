# BTC — Flying Rocket — 2026-08-21 04:57

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | BTC · Flying Rocket |
| Report time | 2026-08-21 04:57 (America/New_York) |
| Verdict | • STAND_DOWN — No BTC short: no channel is live, adjusted score 3/20, gates 0/8, and gate EV -0.29%. Stand down and fix ledger reconciliation before sizing anything. |
| Adjusted score | **3/20** (mechanical 3, raw 3) |
| Confirmation gates | 0/8 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable BTC |
| Deployment | 0% deployed · 50% dry |
| Primary action | **STAND_DOWN** — No channel, no confirmation gates and -0.29% gate EV. The ledger has no corroborated BTC short and custody/basis defects block any quantity claim. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $78,498.055/BTC | ✅ AVAILABLE | — | 2026-08-21T08:53:54.804Z | Canonical synchronized median.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| All-time high | $126,198.07/BTC one-year high | ✅ AVAILABLE | — | 2025-10-06 | Yahoo trailing-one-year weekly high.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Drawdown from ATH | 37.80% | ✅ AVAILABLE | — | 2026-08-21 | Channel routing input.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| ADR-5 | $3,642.47 | ✅ AVAILABLE | — | 2026-08-21 | Five full crypto sessions; 1.5xADR noise floor is 6.96% of spot.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Borrow | 0.0011% | ✅ AVAILABLE | — | 2026-08-21T08:53:54.804Z | Bitfinex single-venue lending proxy; context only.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Correlation spx | — | ⚠️ DATA_LIMITED | — | 2026-08-21 | Not computed this cycle; conservative risk-on gate surcharge defaults ON.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Daily rsi | 86.13 RSI-14 | ✅ AVAILABLE | — | 2026-08-21 | Current daily series after the squeeze rally.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Options skew | -0.69 vol points put-rich | ✅ AVAILABLE | — | 2026-08-21T08:53:54.804Z | Moneyness-based, not a 25-delta risk reversal; context only.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Weekly RSI-14 | 38.80 RSI-14 | ✅ AVAILABLE | — | week of 2026-08-10 | Completed week; current partial week excluded.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |

**Regime:** — — Bounce age sessions: 39; Bounce pct: 27.08%; Channel: none; Ma200: 68974.44; Ma200 slope20 pct: -3.25%; Ma50: 64573.93; Price vs ma200 pct: 13.81%; Stall confirmation: No

### Spot reconciliation

**✅ AVAILABLE** — Median of four synchronized live sources; frozen Yahoo close excluded; spread 0.361%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko BTC | $78,229/BTC | ✅ live | — |
| Binance BTCUSDT | $78,493.41/BTC | ✅ live | — |
| Coinbase BTC-USD | $78,511.46/BTC | ✅ live | — |
| Kraken XBTUSD | $78,502.7/BTC | • receipt-time | — |

> Spread 0.361% is below 0.5%; no two-extremes EV test is required.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Derivatives | OI high / funding positive Binance/Deribit context | ✅ AVAILABLE | HIGH | 2026-08-21T08:53:54.804Z | Funding +6.41% annualized; OI 1.72% below its 90-day high; skew modestly call-rich at -0.69.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Etf flows | 1,610.3 USD millions | ✅ AVAILABLE | HIGH | 2026-08-20 | Aug 17-20 inflows total $1.6103B. Four completed inflow sessions remain one short of the five-session durability lock, so the prior outflow regime scores for this report only.<br>Sources: [farside_btc](https://farside.co.uk/btc/) |
| Funding | 6.41% | ✅ AVAILABLE | HIGH | 2026-08-21T08:53:54.804Z | Positive funding means longs pay shorts; no sustained negative prints.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Macro | mixed / upside catalyst live | ✅ AVAILABLE | HIGH | 2026-08-21 | VIX rose 10.74% over five sessions and equities softened, while DXY fell 1.02%; regulatory optimism, buybacks and ETF demand dominate near-term squeeze risk.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json), [market_news](https://www.theblock.co/news/markets/2026-08-20-bitcoins-rally-pushes-past-72000-analysts-see-demand-beyond-historic-short-squeeze-412337), [cftc](https://www.cftc.gov/PressRoom/PressReleases/9283-26) |
| Momentum | 86.13 daily RSI-14 | ✅ AVAILABLE | HIGH | 2026-08-21 | Daily RSI 86.13 is locally extreme, but completed-week RSI 38.80 keeps Channel-A momentum at zero.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Onchain | low valuation / reserves rising | ✅ AVAILABLE | MEDIUM | 2026-08-20 | MVRV-Z 0.67; exchange reserves rose 2.41% over 30d; true LTH is provider-gated.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Regime | none | ✅ AVAILABLE | HIGH | 2026-08-21 | 37.80% below the one-year high but price 13.81% above a falling 200dma; no channel.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Sentiment | 60 F&G 3-day average | ✅ AVAILABLE | HIGH | 2026-08-21 | F&G spot 72, three-day average 60.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |
| Spot | $78,498.055/BTC | ✅ AVAILABLE | HIGH | 2026-08-21T08:53:54.804Z | Four-source synchronized median; 0.361% spread.<br>Sources: [snapshot](data/runs/20260821-0853-bc8a2d25/snapshot.json) |

**Data gaps:** 4 · **stale inputs:** 0 · **out of scope:** 2

**Data gaps**

- **BTC custody and basis reconciliation** — ⚠️ DATA_LIMITED — No quantity, basis, unrealized PnL or ROI may be quoted.
- **True LTH distribution** — — NOT_COVERED — No gate-7 or distribution sub-leg (a) credit.
- **30-day crypto/equity correlation** — — NOT_COVERED — Risk-on gate surcharge defaults ON; effective floors are baseline 3/5/6/8 plus one, capped at the active denominator.
- **ETF-flow share of current total AUM** — ⚠️ DATA_LIMITED — The flow-direction read is verified, but a synchronized full-complex AUM denominator was not independently reproduced; no score or gate depends on it in this cap-bound regime.

**Out of scope**

- No order execution was performed.
- Context-panel fields are not promoted into rubric legs or gates.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Distribution | 1 | 3 | Mechanical component |
| Euphoria | 2 | 5 | Mechanical component |
| Momentum | 0 | 4 | Mechanical component |
| Valuation | 0 | 5 | Mechanical component |
| Vulnerability | 0 | 3 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 3 | Legs plus penalties |
| Raw score | 3 | Mechanical plus discretion (0) |
| Adjusted score | **3/20** | Decision score |
| Rounding | half-up | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Phase of cycle | 8 | BTC is more than 20% below its one-year high; Channel A is capped at 8 and no phase is reachable. |
| Channel | none | Price is above the falling 200dma, so Channel B precondition fails. |
| Squeeze trap | 0 | Funding is not sustained below -5% annualized. |
| Correlation gate surcharge | 1 | 30-day correlation was not computed; the short-side conservative default adds one effective gate. |

### Confirmation gates — 0/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | FAIL — crypto F&G is not >=80 for seven days. |
| 2 | • NOT PASSED | FAIL — completed-week RSI is 38.80, below 70. |
| 3 | • NOT PASSED | FAIL — MVRV-Z is 0.67, below 3. |
| 4 | • NOT PASSED | FAIL — funding is 6.41% annualized, below 25%. |
| 5 | • NOT PASSED | WARNING/capitulation-context — ETF inflows accelerated, but price is >20% below its high; gate cannot confirm distribution. |
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
| Recovery extends | 50% | $78,000/BTC | $90,000/BTC | $84,000/BTC | ETF demand, regulatory optimism and squeeze momentum can extend before weekly structure resolves. |
| Range / base | 30% | $73,000/BTC | $80,000/BTC | $76,500/BTC | Consolidation above the reclaimed 200dma and around the current breakout zone. |
| Mean reversion | 15% | $68,000/BTC | $73,000/BTC | $70,500/BTC | Requires rejection of the current breakout and loss of nearby support. |
| Bear continuation | 5% | $60,000/BTC | $68,000/BTC | $64,000/BTC | Requires renewed lower-low structure and a failed 200dma reclaim; absent now. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $78,725/BTC |
| EV versus spot | 0.29% |

> 0.50x84000 + 0.30x76500 + 0.15x70500 + 0.05x64000 = 78725. Directional short EV -0.29%; true positive-funding carry adds +0.37% over 21 days, producing +0.08% true total, but income is floored to zero for gating. Gate EV -0.29%, below +3%; corroborative only because no channel is live.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 50% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 5% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1A-BTC-20260821-0457 | Locked: adjusted score is below 11, confirmation stack is incomplete, and no new short is authorized. |
| 1B | 10% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1B-BTC-20260821-0457 | Locked: adjusted score is below 13, confirmation stack is incomplete, and no new short is authorized. |
| 2 | 15% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-2-BTC-20260821-0457 | Locked: adjusted score is below 15, confirmation stack is incomplete, and no new short is authorized. |
| 3 | 20% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-3-BTC-20260821-0457 | Locked: adjusted score is below 19, confirmation stack is incomplete, and no new short is authorized. |

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
| Funding annualized pct | 6.41% |
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
| ADR-5 | 3642.47 |
| Channel a 1a ceiling pct | 8.00% |
| Initial floor pct | 6.96% |
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

**Summary:** BTC remains in no-channel territory after a 7.70% advance: score 3/20, gates 0/8 and gate EV -0.29%. Stand down; ledger custody and basis remain unusable for sizing.

**Bull case:** Price is 13.81% above the falling 200dma, ETF inflows reached $1.6103B over four sessions and Coinbase Premium is positive. IF a completed week holds above the 200dma, THEN Channel B stays void. (Falsifier: daily close below 68974.44 while the slope remains negative.)

**Bear case:** Daily RSI 86.13 is stretched and the 200dma is still falling. IF BTC closes back below 68974.44 with the slope still negative and then prints a lower-close/lower-high stall, THEN Channel B can be re-evaluated from scratch.

**Rationale:** ROUTE — BTC is 37.80% below its one-year high but 13.81% above a falling 200dma. Channel A is cap-dead and Channel B fails price-below-MA: NONE / STAND DOWN.
> SCORE — F&G 60 gives euphoria 2; weekly RSI 38.80 and MVRV-Z 0.67 give 0/0. Four ETF-inflow sessions are still one short of the durability lock, so the prior outflow-regime distribution point survives once more. Mechanical/adjusted 3, discretion 0.0. Gates 0/8. Converted floors: 1A ceil(3/9×8)=3, 1B ceil(5/9×8)=5, P2 ceil(6/9×8)=6, P3 ceil(8/9×8)=8; correlation not computed adds +1 conservatively, so effective floors are 4/6/7/8. CAP VACUITY — score is 3/20 and 3/8 attainable; interpretation bands ≥9 are unreachable, no phase can be reached at any score, and Channel-A 1A at 11 sits three points above the cap. Hard-Rule-5 both-≥12 is structurally unfalsifiable here: structurally consistent, cap-bound.
> EV — Scenario EV 78725 gives -0.29% directional/gate short EV versus 78498.055. True positive-funding carry lifts transparent total to +0.08%, but funding income is floored to zero for gating; the +3% filter fails. Collar ON and EV is corroborative only.
> POSITION — Event-driven snapshot is fresh but BTC custody is UNEXPLAINED and basis unreliable. Hard Rule 8 forbids a quantity, cost basis, unrealized PnL or ROI claim. No corroborated BTC short exists.
> ANALYST READ — The rubric misses the violence of the one-session move, but that omission cuts against shorting, not for it: record short liquidations, four days of ETF demand and a regulatory catalyst make early top-picking especially fragile. The single input that changes the verdict is a completed structural failure back below the 200dma. Strongest counterargument: daily RSI 86 and OI near its 90-day high can precede a sharp pullback. That is a watch condition, not an entry while the channel is void.
> ACTIONS — 1) Stand down; do not short the squeeze. 2) Fix BTC custody and basis reconciliation. 3) Re-run after a daily close below 68974.44 with falling slope, or after a genuine Channel-A return near the high.

**Primary action:** **STAND_DOWN** — No channel, no confirmation gates and -0.29% gate EV. The ledger has no corroborated BTC short and custody/basis defects block any quantity claim.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Prior forecast grade | FALSIFIER STATUS — Aug-20 no-channel: STANDING (price remains above the 200dma); recovery-extension modal: STANDING (spot 78498 is inside the prior 73000-82000 band and the close-below-68000 condition did not fire); no-short authorization: STANDING (score 3, gates 0). Prior EV_price 72725 was exceeded by 7.94%. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-21 | S1 | — | — | — | — | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | fallen_knives · 6/20 · 3 gates | Same-timestamp FK: sentiment 0, momentum 2, valuation 3, liquidation capitulation 1, holder behavior 0 = 6. Gates 3, 7 and 9 pass. FK <12. |
| Cross-validation | ✅ CONSISTENT | FR 3 versus FK 6 | Channel A is cap-bound and no channel is live; neither score is >=12, so the Hard Rule 5 inconsistency test does not fire. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| 200dma route | ✅ AVAILABLE | Price below 68974.44 with falling slope reopens Channel B only after a full rerun. |
| ETF durability | ✅ AVAILABLE | A fifth consecutive completed inflow session ends the prior outflow-regime distribution credit; it cannot authorize a short. |
| FK force-cover | ✅ AVAILABLE | FK >=12 would independently force cover; current computed FK is 6. |
| Ledger repair | ⚠️ DATA_LIMITED | Reconcile custody and basis before any position sizing. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-20 | Record crypto short squeeze | ✅ AVAILABLE | Record $2.75B short liquidations helped lift BTC above $72K; fresh shorting into continuation is prohibited. |
| 2026-08-20 | ETF demand acceleration | ✅ AVAILABLE | US spot BTC ETFs added $606.3M on Aug 20 and $1.6103B across four completed sessions, reinforcing upside narrative risk. |
| 2026-09-15 | FOMC | ✅ AVAILABLE | Next FOMC decision; beyond any permissible fresh 1A clock. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| No channel is live | A completed daily move below 68974.44 with falling slope reopens Channel-B routing for a full rerun. | ✅ AVAILABLE |
| Recovery-extension remains modal | A close below 73000 followed by a failed breakout retest shifts mass toward range and mean reversion. | ✅ AVAILABLE |
| No short is authorized | A fresh report must show a live channel, adjusted score at its line, converted gates, preflight pass and gate EV >3%. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.mechanical | 1 | 3 | F&G three-day average rose from 49.67 to 60, lifting euphoria 0 to 2; the durability-locked distribution point remains. |
| Spot | 72884.395 | 78498.055 | BTC advanced 7.70% while remaining above the falling 200dma; no-channel routing is unchanged. |
| Channel | none | none | Price remains above the falling 200dma despite being more than 20% below the one-year high. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| cftc | CFTC Innovation Advisory Committee meeting | primary regulator event | 2026-08-20 | 2026-08-21T08:57:00Z | The inaugural meeting covered crypto regulation; regulatory follow-through remains upside narrative risk for shorts.<br>[Open source](https://www.cftc.gov/PressRoom/PressReleases/9283-26) |
| farside_btc | Farside US BTC ETF daily flows | primary ETF flow table | 2026-08-20 | 2026-08-21T08:57:00Z | Completed-session flows through August 20; August 21 is incomplete and excluded.<br>[Open source](https://farside.co.uk/btc/) |
| ledger | Personal-accounting position snapshot | user ledger | 2026-08-15T09:30:02.628Z | 2026-08-21T08:57:00Z | FRESH under the event-driven policy; asset custody defects are handled separately and fail closed.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| market_news | BTC rally, ETF demand and record short-squeeze coverage | market news | 2026-08-20 | 2026-08-21T08:57:00Z | The rally was amplified by $2.75B of short liquidations and supported by spot/ETF demand and regulatory optimism; catalyst context only.<br>[Open source](https://www.theblock.co/news/markets/2026-08-20-bitcoins-rally-pushes-past-72000-analysts-see-demand-beyond-historic-short-squeeze-412337) |
| rotation | CoinMarketCap Altcoin Season Index mirror | market breadth | 2026-08-20 | 2026-08-21T08:57:00Z | Index 37/100; neither broad altseason nor a complete BTC wrong-asset trigger.<br>[Open source](https://cryptoheatmap.app/altcoin-season/) |
| snapshot | Deterministic BTC/ETH/macro live snapshot | computed | 2026-08-21T08:53:54.804Z | 2026-08-21T08:53:54.804Z | Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Coin Metrics, Coinbase Premium, Bitfinex borrow, Deribit options and FRED/Yahoo macro.<br>[Open source](data/runs/20260821-0853-bc8a2d25/snapshot.json) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-21T08:53:54.804Z |
| Generated at | 2026-08-21T08:57:00Z |
| Report at | 2026-08-21T08:57:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | btc_flying_rocket_20260821_0457 |
| Report filename | btc_flying_rocket_20260821_0457.json |
| Run ID | 20260821-0853-bc8a2d25 |
| Snapshot ID | sha256:bc8a2d251829c690b50168a06dcdd1af9881e45c992ad2c118776a0c3c92bd30 |
| Prior report | btc_flying_rocket_20260820_1640 |
| Prior report hash | 8848759b9e3220f83b15445d0c3f5fb07120d4ec5f64591608b30f877e5d89a0 |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:5b6af85e952707e28164714ff31ebeacf6b69ec5b0c63835350110def1620aa1 |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | • STAND_DOWN | FR-A-1A-BTC-20260821-0457 | crypto |
| 1B | • STAND_DOWN | FR-A-1B-BTC-20260821-0457 | crypto |
| 2 | • STAND_DOWN | FR-A-2-BTC-20260821-0457 | crypto |
| 3 | • STAND_DOWN | FR-A-3-BTC-20260821-0457 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FR-A-1A-BTC-20260821-0457, FR-A-1B-BTC-20260821-0457, FR-A-2-BTC-20260821-0457, FR-A-3-BTC-20260821-0457

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":3,"field":"score.mechanical","previous":1,"reason":"F&G three-day average rose from 49.67 to 60, lifting euphoria 0 to 2; the durability-locked distribution point remains."},{"current":"78498.055","field":"spot","previous":"72884.395","reason":"BTC advanced 7.70% while remaining above the falling 200dma; no-channel routing is unchanged."},{"current":"none","field":"channel","previous":"none","reason":"Price remains above the falling 200dma despite being more than 20% below the one-year high."}],"channel":"none","companion_framework":{"framework":"fallen_knives","gates":3,"rationale":"Same-timestamp FK: sentiment 0, momentum 2, valuation 3, liquidation capitulation 1, holder behavior 0 = 6. Gates 3, 7 and 9 pass. FK <12.","score":6,"status":"AVAILABLE"},"cross_validation":{"rationale":"Channel A is cap-bound and no channel is live; neither score is >=12, so the Hard Rule 5 inconsistency test does not fire.","relationship":"FR 3 versus FK 6","status":"CONSISTENT"},"data_gaps":[{"field":"BTC custody and basis reconciliation","impact":"No quantity, basis, unrealized PnL or ROI may be quoted.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"True LTH distribution","impact":"No gate-7 or distribution sub-leg (a) credit.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"30-day crypto/equity correlation","impact":"Risk-on gate surcharge defaults ON; effective floors are baseline 3/5/6/8 plus one, capped at the active denominator.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"ETF-flow share of current total AUM","impact":"The flow-direction read is verified, but a synchronized full-complex AUM denominator was not independently reproduced; no score or gate depends on it in this cap-bound regime.","source_ids":["farside_btc"],"status":"DATA_LIMITED"}],"deployment":{"deployed_pct":"0","dry_pct":"50","throttle_released":false,"tranches":[{"channel":"A","deployed":false,"entry_price":null,"pct":"5","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 11, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-1A-BTC-20260821-0457","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"10","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 13, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-1B-BTC-20260821-0457","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"15","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 15, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-2-BTC-20260821-0457","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"20","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Locked: adjusted score is below 19, confirmation stack is incomplete, and no new short is authorized.","state":"LOCKED","stop":null,"tag":"FR-A-3-BTC-20260821-0457","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"0.50x84000 + 0.30x76500 + 0.15x70500 + 0.05x64000 = 78725. Directional short EV -0.29%; true positive-funding carry adds +0.37% over 21 days, producing +0.08% true total, but income is floored to zero for gating. Gate EV -0.29%, below +3%; corroborative only because no channel is live.","probability_sum":1,"scenarios":[{"high":"90000","low":"78000","mid":"84000","name":"Recovery extends","probability":0.5,"rationale":"ETF demand, regulatory optimism and squeeze momentum can extend before weekly structure resolves."},{"high":"80000","low":"73000","mid":"76500","name":"Range / base","probability":0.3,"rationale":"Consolidation above the reclaimed 200dma and around the current breakout zone."},{"high":"73000","low":"68000","mid":"70500","name":"Mean reversion","probability":0.15,"rationale":"Requires rejection of the current breakout and loss of nearby support."},{"high":"68000","low":"60000","mid":"64000","name":"Bear continuation","probability":0.05,"rationale":"Requires renewed lower-low structure and a failed 200dma reclaim; absent now."}],"stated_ev":"78725","vs_spot_pct":"0.29"},"events":[{"as_of":"2026-08-20","impact":"Record $2.75B short liquidations helped lift BTC above $72K; fresh shorting into continuation is prohibited.","name":"Record crypto short squeeze","status":"AVAILABLE"},{"as_of":"2026-08-20","impact":"US spot BTC ETFs added $606.3M on Aug 20 and $1.6103B across four completed sessions, reinforcing upside narrative risk.","name":"ETF demand acceleration","status":"AVAILABLE"},{"as_of":"2026-09-15","impact":"Next FOMC decision; beyond any permissible fresh 1A clock.","name":"FOMC","status":"AVAILABLE"}],"evidence":{"derivatives":{"as_of":"2026-08-21T08:53:54.804Z","confidence":"HIGH","rationale":"Funding +6.41% annualized; OI 1.72% below its 90-day high; skew modestly call-rich at -0.69.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Binance/Deribit context","value":"OI high / funding positive"},"etf_flows":{"as_of":"2026-08-20","confidence":"HIGH","rationale":"Aug 17-20 inflows total $1.6103B. Four completed inflow sessions remain one short of the five-session durability lock, so the prior outflow regime scores for this report only.","source_ids":["farside_btc"],"status":"AVAILABLE","unit":"USD millions","value":"1610.3"},"funding":{"as_of":"2026-08-21T08:53:54.804Z","confidence":"HIGH","rationale":"Positive funding means longs pay shorts; no sustained negative prints.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"6.41"},"macro":{"as_of":"2026-08-21","confidence":"HIGH","rationale":"VIX rose 10.74% over five sessions and equities softened, while DXY fell 1.02%; regulatory optimism, buybacks and ETF demand dominate near-term squeeze risk.","source_ids":["snapshot","market_news","cftc"],"status":"AVAILABLE","unit":null,"value":"mixed / upside catalyst live"},"momentum":{"as_of":"2026-08-21","confidence":"HIGH","rationale":"Daily RSI 86.13 is locally extreme, but completed-week RSI 38.80 keeps Channel-A momentum at zero.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"daily RSI-14","value":"86.13"},"onchain":{"as_of":"2026-08-20","confidence":"MEDIUM","rationale":"MVRV-Z 0.67; exchange reserves rose 2.41% over 30d; true LTH is provider-gated.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"low valuation / reserves rising"},"regime":{"as_of":"2026-08-21","confidence":"HIGH","rationale":"37.80% below the one-year high but price 13.81% above a falling 200dma; no channel.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"none"},"sentiment":{"as_of":"2026-08-21","confidence":"HIGH","rationale":"F&G spot 72, three-day average 60.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"F&G 3-day average","value":"60"},"spot":{"as_of":"2026-08-21T08:53:54.804Z","confidence":"HIGH","rationale":"Four-source synchronized median; 0.361% spread.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"78498.055"}},"falsifiers":[{"claim":"No channel is live","condition":"A completed daily move below 68974.44 with falling slope reopens Channel-B routing for a full rerun.","status":"AVAILABLE"},{"claim":"Recovery-extension remains modal","condition":"A close below 73000 followed by a failed breakout retest shifts mass toward range and mean reversion.","status":"AVAILABLE"},{"claim":"No short is authorized","condition":"A fresh report must show a live channel, adjusted score at its line, converted gates, preflight pass and gate EV >3%.","status":"AVAILABLE"}],"gates":{"active":8,"alt_reading":null,"measurement_basis":{"1":"FAIL — crypto F&G is not >=80 for seven days.","2":"FAIL — completed-week RSI is 38.80, below 70.","3":"FAIL — MVRV-Z is 0.67, below 3.","4":"FAIL — funding is 6.41% annualized, below 25%.","5":"WARNING/capitulation-context — ETF inflows accelerated, but price is >20% below its high; gate cannot confirm distribution.","6":"FAIL — Coinbase Premium is positive on all three completed days.","7":"UNMEASURED/FAIL — true LTH distribution is provider-gated; no substitute is used.","8":"N/A — asset is >15% below its own high; top-coincident breadth divergence is structurally inapplicable.","9":"WARNING/capitulation-context — rotation gate cannot count while price is >20% below the high.","stall":"NOT APPLICABLE — Channel B is not live.","surcharge":"Baseline converted floors are 3/5/6/8 on 8 active gates; correlation is not computed, so the conservative +1 surcharge makes effective floors 4/6/7/8."},"na":[8],"passed":[],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":8}},"identity":{"asset":"BTC","date":"2026-08-21","filename":"btc_flying_rocket_20260821_0457.json","framework":"flying_rocket","local_time":"04:57","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-10-06","note":"Yahoo trailing-one-year weekly high.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC one-year high","value":"126198.07"},"drawdown_pct":{"as_of":"2026-08-21","note":"Channel routing input.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent below one-year high","value":"37.80"},"metrics":{"adr5":{"as_of":"2026-08-21","note":"Five full crypto sessions; 1.5xADR noise floor is 6.96% of spot.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"3642.47"},"borrow":{"as_of":"2026-08-21T08:53:54.804Z","note":"Bitfinex single-venue lending proxy; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"0.0011"},"correlation_spx":{"as_of":"2026-08-21","note":"Not computed this cycle; conservative risk-on gate surcharge defaults ON.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"Pearson daily log returns","value":null},"daily_rsi":{"as_of":"2026-08-21","note":"Current daily series after the squeeze rally.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"86.13"},"options_skew":{"as_of":"2026-08-21T08:53:54.804Z","note":"Moneyness-based, not a 25-delta risk reversal; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"vol points put-rich","value":"-0.69"},"weekly_rsi":{"as_of":"week of 2026-08-10","note":"Completed week; current partial week excluded.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"38.80"}},"reconciliation":{"method":"Median of four synchronized live sources; frozen Yahoo close excluded","note":"Spread 0.361% is below 0.5%; no two-extremes EV test is required.","quotes":[{"instrument":"CoinGecko BTC","state":"live","value":"78229"},{"instrument":"Binance BTCUSDT","state":"live","value":"78493.41"},{"instrument":"Coinbase BTC-USD","state":"live","value":"78511.46"},{"instrument":"Kraken XBTUSD","state":"receipt-time","value":"78502.7"}],"spread_pct":"0.361","status":"AVAILABLE"},"regime":{"bounce_age_sessions":39,"bounce_pct":"27.08","channel":"none","ma200":"68974.44","ma200_slope20_pct":"-3.25","ma50":"64573.93","price_vs_ma200_pct":"13.81","stall_confirmation":false},"spot":{"as_of":"2026-08-21T08:53:54.804Z","note":"Canonical synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"78498.055"}},"narrative":{"arguments":{"discretion_ledger":[{"channel":"S1","date":"2026-08-21","load_bearing":false,"outcome":"STAND_DOWN","reason":"The rubric and structural veto capture the tape; no discretionary score term is warranted.","s2":false,"term":"0.0"}],"prior_forecast_grade":"FALSIFIER STATUS — Aug-20 no-channel: STANDING (price remains above the 200dma); recovery-extension modal: STANDING (spot 78498 is inside the prior 73000-82000 band and the close-below-68000 condition did not fire); no-short authorization: STANDING (score 3, gates 0). Prior EV_price 72725 was exceeded by 7.94%.","stop_migration_ledger":[]},"bear_case":"Daily RSI 86.13 is stretched and the 200dma is still falling. IF BTC closes back below 68974.44 with the slope still negative and then prints a lower-close/lower-high stall, THEN Channel B can be re-evaluated from scratch.","bull_case":"Price is 13.81% above the falling 200dma, ETF inflows reached $1.6103B over four sessions and Coinbase Premium is positive. IF a completed week holds above the 200dma, THEN Channel B stays void. (Falsifier: daily close below 68974.44 while the slope remains negative.)","primary_action":{"rationale":"No channel, no confirmation gates and -0.29% gate EV. The ledger has no corroborated BTC short and custody/basis defects block any quantity claim.","status":"AVAILABLE","value":"STAND_DOWN"},"rationale":"ROUTE — BTC is 37.80% below its one-year high but 13.81% above a falling 200dma. Channel A is cap-dead and Channel B fails price-below-MA: NONE / STAND DOWN.\nSCORE — F&G 60 gives euphoria 2; weekly RSI 38.80 and MVRV-Z 0.67 give 0/0. Four ETF-inflow sessions are still one short of the durability lock, so the prior outflow-regime distribution point survives once more. Mechanical/adjusted 3, discretion 0.0. Gates 0/8. Converted floors: 1A ceil(3/9×8)=3, 1B ceil(5/9×8)=5, P2 ceil(6/9×8)=6, P3 ceil(8/9×8)=8; correlation not computed adds +1 conservatively, so effective floors are 4/6/7/8. CAP VACUITY — score is 3/20 and 3/8 attainable; interpretation bands ≥9 are unreachable, no phase can be reached at any score, and Channel-A 1A at 11 sits three points above the cap. Hard-Rule-5 both-≥12 is structurally unfalsifiable here: structurally consistent, cap-bound.\nEV — Scenario EV 78725 gives -0.29% directional/gate short EV versus 78498.055. True positive-funding carry lifts transparent total to +0.08%, but funding income is floored to zero for gating; the +3% filter fails. Collar ON and EV is corroborative only.\nPOSITION — Event-driven snapshot is fresh but BTC custody is UNEXPLAINED and basis unreliable. Hard Rule 8 forbids a quantity, cost basis, unrealized PnL or ROI claim. No corroborated BTC short exists.\nANALYST READ — The rubric misses the violence of the one-session move, but that omission cuts against shorting, not for it: record short liquidations, four days of ETF demand and a regulatory catalyst make early top-picking especially fragile. The single input that changes the verdict is a completed structural failure back below the 200dma. Strongest counterargument: daily RSI 86 and OI near its 90-day high can precede a sharp pullback. That is a watch condition, not an entry while the channel is void.\nACTIONS — 1) Stand down; do not short the squeeze. 2) Fix BTC custody and basis reconciliation. 3) Re-run after a daily close below 68974.44 with falling slope, or after a genuine Channel-A return near the high.","summary":"BTC remains in no-channel territory after a 7.70% advance: score 3/20, gates 0/8 and gate EV -0.29%. Stand down; ledger custody and basis remain unusable for sizing."},"out_of_scope":["No order execution was performed.","Context-panel fields are not promoted into rubric legs or gates."],"position":{"asset":"BTC","attribution":{"active_tags":[],"note":"Open BTC deals are untagged; no framework short is corroborated.","status":"UNKNOWN"},"basis":{"avg_cost":null,"reason":"Twelve unbacked disposals make cost basis non-derivable; no average cost, basis, unrealized PnL or ROI is quoted.","reliable":false,"total_cost":null},"custody":{"reason":"Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap.","status":"UNEXPLAINED"},"dry_powder":"10741.5780","futures":[],"pnl":{"reason":"Basis and custody defects prohibit a PnL claim.","status":"DATA_LIMITED","unrealized":null},"quantity":null,"reconciliation":"Event-driven snapshot is fresh, but BTC custody is UNEXPLAINED and basis is unreliable. No position figure in either direction is reported.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"No corroborated BTC short exists; custody and basis defects block any position-level control or sizing claim.","status":"DATA_LIMITED","value":"STAND_DOWN"},"required":true,"status":"DATA_LIMITED"},"regime":{"ma200_falling":true,"pct_below_1y_ath":"37.80","price_below_ma200":false},"report_id":"btc_flying_rocket_20260821_0457","risk_controls":{"carry":{"carry_veto":false,"funding_annualized_pct":"6.41","gate_carry_ev_pct":"0","minimum_edge_pass":false,"status":"AVAILABLE","true_carry_ev_pct_21d":"0.37"},"concentration":{"channel_a_asset_cap_pct":"50","planned_pct":"0","status":"PASS","total_short_book_cap_pct":"50"},"ratchet":{"reason":"No corroborated BTC short or auditable prior stop exists.","status":"NOT_APPLICABLE"},"stops":{"adr5":"3642.47","channel_a_1a_ceiling_pct":"8.00","initial_floor_pct":"6.96","note":"Informational only; no new fill is authorized.","status":"LOCKED"},"time_stops":{"p1a_days":21,"p1b_days":28,"p2_days":35,"p3_days":49,"status":"LOCKED"}},"run":{"prior_report_id":"btc_flying_rocket_20260820_1640","prior_report_sha256":"8848759b9e3220f83b15445d0c3f5fb07120d4ec5f64591608b30f877e5d89a0","run_id":"20260821-0853-bc8a2d25","snapshot_id":"sha256:bc8a2d251829c690b50168a06dcdd1af9881e45c992ad2c118776a0c3c92bd30","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:5b6af85e952707e28164714ff31ebeacf6b69ec5b0c63835350110def1620aa1","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":3,"caps":[{"field":"phase_of_cycle","reason":"BTC is more than 20% below its one-year high; Channel A is capped at 8 and no phase is reachable.","value":8},{"field":"channel","reason":"Price is above the falling 200dma, so Channel B precondition fails.","value":"none"},{"field":"squeeze_trap","reason":"Funding is not sustained below -5% annualized.","value":0},{"field":"correlation_gate_surcharge","reason":"30-day correlation was not computed; the short-side conservative default adds one effective gate.","value":1}],"discretion":0,"legs":{"distribution":1,"euphoria":2,"momentum":0,"valuation":0,"vulnerability":0},"mechanical":3,"penalties":[],"raw":3,"rounding":"half-up"},"sources":{"cftc":{"as_of":"2026-08-20","kind":"primary regulator event","name":"CFTC Innovation Advisory Committee meeting","note":"The inaugural meeting covered crypto regulation; regulatory follow-through remains upside narrative risk for shorts.","retrieved_at":"2026-08-21T08:57:00Z","url":"https://www.cftc.gov/PressRoom/PressReleases/9283-26"},"farside_btc":{"as_of":"2026-08-20","kind":"primary ETF flow table","name":"Farside US BTC ETF daily flows","note":"Completed-session flows through August 20; August 21 is incomplete and excluded.","retrieved_at":"2026-08-21T08:57:00Z","url":"https://farside.co.uk/btc/"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"user ledger","name":"Personal-accounting position snapshot","note":"FRESH under the event-driven policy; asset custody defects are handled separately and fail closed.","retrieved_at":"2026-08-21T08:57:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"market_news":{"as_of":"2026-08-20","kind":"market news","name":"BTC rally, ETF demand and record short-squeeze coverage","note":"The rally was amplified by $2.75B of short liquidations and supported by spot/ETF demand and regulatory optimism; catalyst context only.","retrieved_at":"2026-08-21T08:57:00Z","url":"https://www.theblock.co/news/markets/2026-08-20-bitcoins-rally-pushes-past-72000-analysts-see-demand-beyond-historic-short-squeeze-412337"},"rotation":{"as_of":"2026-08-20","kind":"market breadth","name":"CoinMarketCap Altcoin Season Index mirror","note":"Index 37/100; neither broad altseason nor a complete BTC wrong-asset trigger.","retrieved_at":"2026-08-21T08:57:00Z","url":"https://cryptoheatmap.app/altcoin-season/"},"snapshot":{"as_of":"2026-08-21T08:53:54.804Z","kind":"computed","name":"Deterministic BTC/ETH/macro live snapshot","note":"Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Coin Metrics, Coinbase Premium, Bitfinex borrow, Deribit options and FRED/Yahoo macro.","retrieved_at":"2026-08-21T08:53:54.804Z","url":"data/runs/20260821-0853-bc8a2d25/snapshot.json"}},"stale_inputs":[],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FR-A-1A-BTC-20260821-0457","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FR-A-1B-BTC-20260821-0457","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FR-A-2-BTC-20260821-0457","decision":"STAND_DOWN","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FR-A-3-BTC-20260821-0457","decision":"STAND_DOWN","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FR-A-1A-BTC-20260821-0457","FR-A-1B-BTC-20260821-0457","FR-A-2-BTC-20260821-0457","FR-A-3-BTC-20260821-0457"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-21T08:53:54.804Z","generated_at":"2026-08-21T08:57:00Z","report_at":"2026-08-21T08:57:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"No channel, no confirmation gates and -0.29% gate EV. The ledger has no corroborated BTC short and custody/basis defects block any quantity claim.","status":"AVAILABLE","value":"STAND_DOWN"},"statement":"No BTC short: no channel is live, adjusted score 3/20, gates 0/8, and gate EV -0.29%. Stand down and fix ledger reconciliation before sizing anything.","status":"STAND_DOWN"},"watchlist":[{"item":"200dma route","status":"AVAILABLE","trigger":"Price below 68974.44 with falling slope reopens Channel B only after a full rerun."},{"item":"ETF durability","status":"AVAILABLE","trigger":"A fifth consecutive completed inflow session ends the prior outflow-regime distribution credit; it cannot authorize a short."},{"item":"FK force-cover","status":"AVAILABLE","trigger":"FK >=12 would independently force cover; current computed FK is 6."},{"item":"Ledger repair","status":"DATA_LIMITED","trigger":"Reconcile custody and basis before any position sizing."}]}
```
