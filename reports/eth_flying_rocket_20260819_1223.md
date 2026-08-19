# ETH — Flying Rocket — 2026-08-19 12:23

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | ETH · Flying Rocket |
| Report time | 2026-08-19 12:23 (America/New_York) |
| Verdict | • STAND_DOWN — No ETH short: no channel is live, Channel-A record score is 0/20 with 0/8 gates, and gate EV is -0.08%. |
| Adjusted score | **0/20** (mechanical 0, raw 0) |
| Confirmation gates | 0/8 active passed |
| Position | ⚠️ EXPIRED · quantity unavailable ETH |
| Deployment | 0% deployed · 50% dry |
| Primary action | **STAND_DOWN** — Wait for a completed regime test and rerun from scratch; no tranche is authorized. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $2,098.29/ETH | ✅ AVAILABLE | — | 2026-08-19T16:19:47Z | Canonical synchronized median.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| All-time high | $4,796.35/ETH one-year high | ✅ AVAILABLE | — | 2025-08-25 | Yahoo trailing-one-year weekly high.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Drawdown from ATH | 56.25% | ✅ AVAILABLE | — | 2026-08-19 | Phase-of-cycle routing input.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| ADR-5 | $62.15 | ✅ AVAILABLE | — | 2026-08-19 | Five full crypto sessions; approximately 2.96% of spot, so 1.5xADR is about 4.44%.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Borrow | 0.3929% | ✅ AVAILABLE | — | 2026-08-19 | Bitfinex single-venue lending proxy; context only.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Correlation spx | — | ⚠️ DATA_LIMITED | — | 2026-08-10/2026-08-19 | Indicative 0.4452 on only seven return observations; no >0.7 surcharge.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Daily rsi | 75.77 RSI-14 | ✅ AVAILABLE | — | 2026-08-19 | Context only because no channel is live.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Options skew | -1.16 vol points call-rich | ✅ AVAILABLE | — | 2026-08-19 | Moneyness-based, not 25-delta; mild inversion is context, not a scored deep call-favored extreme.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Weekly RSI-14 | 41.92 RSI-14 | ✅ AVAILABLE | — | week of 2026-08-10 | Completed week.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |

**Regime:** — — Bounce age sessions: 37; Bounce pct: 19.95%; Channel: none; Ma200: 2002.50; Ma200 slope20 pct: -5.37%; Ma50: 1859.76; Price vs ma200 pct: 4.78%; Stall confirmation: No

### Spot reconciliation

**✅ AVAILABLE** — Median of four synchronized live sources; frozen Yahoo close excluded; spread 0.217%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko ETH | $2,095.11/ETH | ✅ live | — |
| Binance ETHUSDT | $2,099.66/ETH | ✅ live | — |
| Coinbase ETH-USD | $2,098.58/ETH | ✅ live | — |
| Kraken ETHUSD | $2,098/ETH | • receipt-time | — |

> Spread below 0.5%; no two-extremes EV test required.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Derivatives | OI 100th percentile, rising Binance 30-day context | ✅ AVAILABLE | MEDIUM | 2026-08-19T16:19:47Z | L/S ratio 2.2468 but falling; OI extreme is only a 30-day single-venue context and cannot substitute for a Channel-A top.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Etf flows | 102.3 USD millions over Aug 17-18 | ✅ AVAILABLE | HIGH | 2026-08-18 | Two positive sessions and five positive weeks are absorption/support, not post-euphoria distribution; durability lock prevents a new short-friendly inflection claim.<br>Sources: [farside_eth](https://farside.co.uk/eth/) |
| Funding | 4.26% | ✅ AVAILABLE | HIGH | 2026-08-19T16:19:47Z | Positive but far below the >25% structural-vulnerability threshold; no sustained negative-funding squeeze penalty.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Macro | mixed | ✅ AVAILABLE | HIGH | 2026-08-19 | VIX 15.11, DXY 98.87, US10y 4.65%, real yield 2.44%, SPX 7738.52 and NDX 26452.70.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Momentum | 41.92 completed-week RSI-14 | ✅ AVAILABLE | HIGH | week of 2026-08-10 | Below Channel-A 60-point scoring floor; live-week RSI 50.45 is not used.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Onchain | — | ⚠️ STALE | LOW | 2026-08-08 | Large-wallet accumulation was reported, but current MVRV-Z, LTH supply and exchange inflow trends are not refreshed enough for Channel-A scoring.<br>Sources: [theblock](https://www.theblock.co/news/markets/2026-08-08-bitcoin-ether-etfs-draw-1-1-billion-in-best-inflow-week-since-april-despite-low-volume-411204), [coinbase_glassnode](https://ctf-images-01.coinbasecdn.net/k3n74unfin40/6T2gPNDvHvBpf246ziwYTW/81039d2975a8c36c9e475c06a24a4455/Charting_Crypto_2Q26.pdf) |
| Regime | NONE / stand down | ✅ AVAILABLE | HIGH | 2026-08-19 | ETH is 56.25% below its one-year high but 4.78% above its falling 200dma. Channel B requires price below; Channel A is capped at 8 and unreachable.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Sentiment | 39.33 F&G 3-day average | ✅ AVAILABLE | HIGH | 2026-08-19 | Fear, not Channel-A euphoria.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Spot | $2,098.29/ETH | ✅ AVAILABLE | HIGH | 2026-08-19T16:19:47Z | Median of synchronized CoinGecko, Binance, Coinbase and Kraken quotes; 0.217% spread.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |

**Data gaps:** 4 · **stale inputs:** 2 · **out of scope:** 2

**Data gaps**

- **current ETH MVRV-Z and LTH/exchange-inflow series** — ❔ UNKNOWN — No valuation or distribution credit is inferred; stand-down is conservative.
- **current Coinbase Premium three-day streak** — ❔ UNKNOWN — Gate 6 stays unconfirmed, not N/A.
- **90-day open-interest high** — — NOT_COVERED — No OI escalation claim; the 30-day percentile remains context only.
- **equities breadth percent above 200dma** — ❔ UNKNOWN — No breadth-divergence credit; asset is far from ATH anyway.

**Stale inputs**

- Ledger holdings_as_of 2026-07-05; expired.
- On-chain MVRV definitions/current accumulation context are stale and unscored.

**Out of scope**

- No order execution was authorized or performed.
- Context-panel OI, skew and borrow are not promoted into rubric legs or gates.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Distribution | 0 | 3 | Mechanical component |
| Euphoria | 0 | 5 | Mechanical component |
| Momentum | 0 | 4 | Mechanical component |
| Valuation | 0 | 5 | Mechanical component |
| Vulnerability | 0 | 3 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 0 | Legs plus penalties |
| Raw score | 0 | Mechanical plus discretion (0) |
| Adjusted score | **0/20** | Decision score |
| Rounding | half-down | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Phase of cycle | 8 | ETH is >20% below its one-year high; Channel A is structurally dead. |
| Channel | none | Price is above the falling 200dma, so Channel B precondition fails. |
| Squeeze trap | 0 | Funding not sustained below -5% annualized. |

### Confirmation gates — 0/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | FAIL [TOP] — F&G 39.33, not >=80 for seven days. |
| 2 | • NOT PASSED | FAIL [TOP] — weekly RSI 41.92, not >70. |
| 3 | • NOT PASSED | FAIL [TOP] — no current MVRV-Z >3; deep drawdown is opposite context. |
| 4 | • NOT PASSED | FAIL [FLOW] — funding 4.26% annualized, not >25%. |
| 5 | • NOT PASSED | FAIL/WARNING [FLOW] — ETF inflows are positive and price is >20% off high; capitulation-context rule forbids a short confirmation. |
| 6 | • NOT PASSED | FAIL/WARNING [FLOW] — current three-day Coinbase Premium streak unavailable; deep-drawdown condition forbids confirmation. |
| 7 | • NOT PASSED | FAIL [FLOW] — no fresh LTH distribution above pro-rated threshold. |
| 8 | • N/A | N/A [TOP] — ETH is >15% below its own one-year high, so breadth divergence precondition is structurally inapplicable. |
| 9 | • NOT PASSED | FAIL/WARNING [FLOW] — rotation gate cannot confirm while price is >20% off high under the capitulation-context rule. |

### Unlock thresholds

| Phase | Score / gate threshold |
| --- | --- |
| P1A | 3 |
| P1B | 5 |
| P2 | 6 |
| P3 | 8 |

**Alternate reading:** correlation —; surcharge off; [V] gates —.


## 4. Probability matrix and expected value

| Scenario | Probability | Low | High | Midpoint | Rationale |
| --- | --- | --- | --- | --- | --- |
| Recovery extends | 45% | $2,100/ETH | $2,400/ETH | $2,250/ETH | Price reclaimed the falling 200dma and daily RSI is strong; no stall yet. |
| Range/base | 30% | $1,950/ETH | $2,200/ETH | $2,075/ETH | Consolidation around the reclaimed 200dma is the second mode. |
| Mean reversion | 20% | $1,750/ETH | $2,050/ETH | $1,900/ETH | Failure back below the 200dma revisits the 50dma and July base. |
| Bear continuation | 5% | $1,600/ETH | $1,800/ETH | $1,700/ETH | Requires a failed reclaim and renewed lower-low structure; not present. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $2,100/ETH |
| EV versus spot | 0.08% |

> 0.45x2250 + 0.30x2075 + 0.20x1900 + 0.05x1700 = 2100. Directional short EV -0.08%. Positive funding adds about +0.25% true carry over 21 days but is floored to zero for gating. Gate EV -0.08%; true total about +0.17%. CORROBORATIVE ONLY because the structural no-channel veto dominates. Collar ON.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 50% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 5% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1A-ETH-20260819-1223 | No channel; adjusted 0 <11 and 0/8 gates <3. |
| 1B | 10% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-1B-ETH-20260819-1223 | No channel; adjusted 0 <13 and no prior tranche. |
| 2 | 15% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-2-ETH-20260819-1223 | No channel; adjusted 0 <15 and the cap makes the line unreachable. |
| 3 | 20% | 🔒 LOCKED | no | — | — | — | — | — | A | — | FR-A-3-ETH-20260819-1223 | No channel; mechanical 0 <19 and the cap makes the line unreachable. |

## 6. Position, custody, and execution controls

| Position field | Value |
| --- | --- |
| Status | ⚠️ EXPIRED |
| Asset | ETH |
| Quantity | — |
| Dry powder | — |
| Basis reliable | no |
| Average cost | — |
| Total cost basis | — |
| Custody | ❔ UNKNOWN |
| Attribution | ❔ UNKNOWN |
| Active tags | None |

### Custody reconciliation

| Field | Value |
| --- | --- |
| Reason | Expired ledger cannot establish custody. |
| Status | ❔ UNKNOWN |

### Cost basis

| Field | Value |
| --- | --- |
| Reason | Expired ledger; no basis quoted. |
| Reliable | No |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Status | ❔ UNKNOWN |

### Position P&L

| Field | Value |
| --- | --- |
| Reason | No current position of record. |
| Status | ❔ UNKNOWN |

> **Position reconciliation:** Position snapshot is expired at 64,424 minutes by holdings_as_of. Cold start per Hard Rule 4: 0% planning deployment and 100% planning dry powder; this is not a factual account balance.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| — NOT_APPLICABLE | no | **NO_LIVE_POSITION_OF_RECORD** — Expired ledger supplies no auditable open short; best-level audit cannot be fabricated. |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Carry veto | No |
| Funding annualized pct | 4.26% |
| Gate carry ev pct | 0% |
| Minimum edge pass | No |
| Status | ✅ AVAILABLE |
| True carry ev pct 21d | 0.25 |

### Concentration

| Field | Value |
| --- | --- |
| Per asset cap pct | 30% |
| Planned pct | 0% |
| Status | ✅ PASS |
| Total short book cap pct | 50% |

### Ratchet

| Field | Value |
| --- | --- |
| Reason | No live tranche or prior stop in the current position sequence. |
| Status | — NOT_APPLICABLE |

### Stops

| Field | Value |
| --- | --- |
| ADR-5 | 62.15 |
| Channel a 1a ceiling pct | 8.00% |
| Initial floor pct | 4.44% |
| Note | Informational only; no channel and no fill. |
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

**Summary:** ETH has exited Channel B by trading 4.78% above its falling 200dma while still 56.25% below the one-year high. Channel A is cap-bound and scores 0/20 with 0/8 active gates. Stand down; no short channel exists.

**Bull case:** ETF inflows remain positive, large-wallet accumulation was recently reported and price has reclaimed the 200dma intraday. IF ETH completes and holds a weekly close above the 200dma, THEN the old bear-continuation thesis is structurally void; a weekly close back below 2000 is the named falsifier.

**Bear case:** The 200dma is still falling 5.37% over 20 sessions, the 50dma remains below it and the move is only one vertical session. IF ETH fails the reclaim, closes back below 2000 and later prints a lower-close/lower-high stall, THEN Channel B can be re-evaluated from scratch; no short is pre-authorized.

**Rationale:** ROUTING — ETH is 56.25% below its one-year high and its 200dma is falling, but spot is 4.78% above that average. Channel B requires price below it and therefore is void. Channel A remains capped at 8 and dead because the asset is >20% below its one-year high. The framework has no live channel.
> SCORE AND GATES — Channel-A record score is 0: euphoria 0 (F&G 39.33), momentum 0 (weekly RSI 41.92), valuation 0 (no current MVRV-Z extreme; bounded state is negative), distribution 0 (ETF inflows/accumulation, no fresh LTH/exchange distribution), vulnerability 0 (funding 4.26%, no deep call skew, no ATH breadth divergence). Mechanical 0, discretion 0.0, adjusted 0, cap 8. Gate 8 is N/A because ETH is >15% below its high; 0/8 pass. Converted floors are 3/5/6/8 and all interpretation bands >=9 are unreachable; Phase 1A's 11 line sits three points above the cap. Hard-Rule-5 both>=12 is unfalsifiable by construction.
> EV — Recovery/range/mean-reversion/bear at 45/30/20/5 produces EV_price 2100, only 0.08% above spot. Directional short EV is -0.08%; positive funding income is +0.25% true but floored out of the minimum-edge gate. Gate EV fails +3%. It is CORROBORATIVE ONLY because no-channel routing is the dominant veto.
> POSITION — Ledger is expired at 64,424 minutes by holdings_as_of. No quantity, cost basis, PnL, futures position, dry-powder dollars or attribution is claimed. Planning state is 0% deployed/100% dry under Hard Rule 4.
> PRIOR FORECAST AND FALSIFIERS — Aug 13 EV_price 1873.20 and modal range 1810-1960 were exceeded by current spot 2098.29, so both are falsified to the upside. The named gap-through-10% channel-void condition fired: the 50/200 gap narrowed to 7.13%, and price has moved above the 200dma. The protective weekly-close reclaim test has not completed yet, but the entry channel is already void at the live regime check. The held-close-below-1757 and FK>=12 falsifiers did not fire.
> ANALYST READ — The rubric misses the emotional force of a daily RSI 75.77 and call-rich skew, both superficially attractive to a top-picker. They do not cure the routing failure. The strongest argument against stand-down is that a one-day 200dma overshoot can reverse violently. The stronger answer is that shorting that reversal before it exists is exactly the falling-knife error on the short side. The single input that changes the verdict first is a completed move back below the 200dma; even then a fresh Channel-B stall and score are required. Discretion ledger: 2026-08-19 | S1 0.0 | S2 no | non-load-bearing | structural routing dominates | no position. Stop migration ledger empty.
> ACTIONS — 1) Stand down; no ETH short channel exists. 2) Grade the 200dma on the completed weekly close, not the intraday print. 3) If price closes back below a falling 200dma, rerun the entire Channel-B stack; do not carry forward the old 6/9 gates. 4) Refresh the ledger before any sizing. 5) Treat positive ETF flows and the FK 10/20 companion as squeeze risk, not short fuel.

**Primary action:** **STAND_DOWN** — No live channel, 0/8 gates and no minimum short edge.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Prior forecast grade | Aug 13 EV 1873.20 and modal range were exceeded; the named narrowing-gap channel-void falsifier fired. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-19 | S1 | — | — | — | — | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | fallen_knives · 10/20 · 2 gates | Computed same-timestamp FK state: sentiment 1, momentum 1, valuation 5 from negative bounded MVRV state, capitulation 0 and holder accumulation 3; mechanical/adjusted 10 with no fresh discretionary term. Gates 3 and 8 pass on the eight-gate ETH schema. FK <12, so force-cover does not fire. |
| Cross-validation | ✅ CONSISTENT | FR 0 versus FK 10; cap-bound inverse relationship | Channel A is capped at 8 and no channel is live, so both>=12 is structurally unfalsifiable. ETH's stronger long-side accumulation read is directionally consistent with no short. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| Completed weekly close versus 200dma | ✅ AVAILABLE | Holding above roughly 2002.50 keeps Channel B void; a move back below does not itself authorize a short and requires full rerouting. |
| Channel-A eligibility | ✅ AVAILABLE | ETH must recover to within 20% of the one-year high for Channel A to become live; within 10% removes its cap. |
| FK force-cover | ✅ AVAILABLE | FK >=12 would explicitly forbid any short even if Channel B reappeared. |
| Funding squeeze veto | ✅ AVAILABLE | Three intervals annualized below -5% activates the crowded-short penalty. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-19 | ETH reclaims falling 200dma intraday | ✅ AVAILABLE | This fires the prior channel-void falsifier at the live-price level; a completed weekly close is still the protective cover test. |
| 2026-08-18 | US spot ETH ETF inflows | ✅ AVAILABLE | Two sessions total +$102.3M after five positive weeks; supportive absorption, not distribution. |
| 2026-08-26 | Tier-1 calendar check | ✅ AVAILABLE | Repository calendar lists no NFP/CPI/PCE/FOMC event in the next seven crypto sessions; unscheduled regulatory risk remains. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| No channel is live | A completed close back below a still-falling 200dma while ETH remains >20% below the one-year high would restore Channel-B eligibility, subject to a fresh full stack. | ✅ AVAILABLE |
| Recovery/base is modal | A completed weekly close back below 2000 followed by a lower high shifts probability toward mean reversion/bear continuation. | ✅ AVAILABLE |
| No short entry is authorized | A channel must first become live, then its score, gates, stall/preflight and EV filters must all pass. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Channel | B | none | Price moved from 6.77% below to 4.78% above the falling 200dma, failing Channel-B price precondition. |
| Score.mechanical | 5 | 0 | No-channel reports score Channel A for record; fear, low weekly RSI, no fresh distribution extreme and the 8-cap leave zero scored legs. |
| Gates.passed | 6/9 Channel B | 0/8 Channel A | Channel-B gate set no longer applies; Channel-A top gates are dark and gate 8 is N/A. |
| Position | EXPIRED cold start | EXPIRED cold start | Holdings clock remains 2026-07-05; no position claim is carried. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| coinbase_glassnode | Coinbase Institutional and Glassnode Charting Crypto Q2 2026 | primary research | 2026-03-31 | 2026-08-19T16:20:30Z | Defines ETH MVRV/realized-price context but is stale; current MVRV-Z is not claimed from it.<br>[Open source](https://ctf-images-01.coinbasecdn.net/k3n74unfin40/6T2gPNDvHvBpf246ziwYTW/81039d2975a8c36c9e475c06a24a4455/Charting_Crypto_2Q26.pdf) |
| farside_eth | Farside US Ethereum ETF daily flows | primary ETF flow table | 2026-08-18 | 2026-08-19T16:20:30Z | August 17 +$30.9M and August 18 +$71.4M; August 19 incomplete and excluded.<br>[Open source](https://farside.co.uk/eth/) |
| ledger | Personal-accounting position snapshot | user ledger | 2026-07-05T22:35:37.907881Z | 2026-08-19T16:18:00Z | EXPIRED; holdings clock drives age. Cold start per Hard Rule 4.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| snapshot | Deterministic BTC/ETH/macro live snapshot | computed | 2026-08-19T16:19:48.965Z | 2026-08-19T16:19:48.965Z | Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Bitfinex borrow, Deribit options and FRED/Yahoo macro.<br>[Open source](data/runs/20260819-1619-159a8947/snapshot.json) |
| theblock | The Block ETH ETF and whale accumulation context | news | 2026-08-08 | 2026-08-19T16:20:30Z | ETH ETFs had five positive weeks and CryptoQuant-reported large-wallet accumulation; context is supportive, not current enough for a scored inflection.<br>[Open source](https://www.theblock.co/news/markets/2026-08-08-bitcoin-ether-etfs-draw-1-1-billion-in-best-inflow-week-since-april-despite-low-volume-411204) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-19T16:19:48.965Z |
| Generated at | 2026-08-19T16:23:00Z |
| Report at | 2026-08-19T16:23:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | eth_flying_rocket_20260819_1223 |
| Report filename | eth_flying_rocket_20260819_1223.json |
| Run ID | 20260819-1619-159a8947 |
| Snapshot ID | sha256:159a8947587444604f7bcf9d8dd1bb42f6e303fa2cc71fe446de87d1ced1e3cb |
| Prior report | eth_flying_rocket_20260813_0150 |
| Prior report hash | 7b469c836062c14f06474a41a53385e9a199e1b9aece9ad071e5f8d8cdca25ec |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:7296bd05f390f9004cf7110ac249df36f87dfb28726356b208bd7519a85750ba |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | • STAND_DOWN | FR-A-1A-ETH-20260819-1223 | crypto |
| 1B | • STAND_DOWN | FR-A-1B-ETH-20260819-1223 | crypto |
| 2 | • STAND_DOWN | FR-A-2-ETH-20260819-1223 | crypto |
| 3 | • STAND_DOWN | FR-A-3-ETH-20260819-1223 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FR-A-1A-ETH-20260819-1223, FR-A-1B-ETH-20260819-1223, FR-A-2-ETH-20260819-1223, FR-A-3-ETH-20260819-1223

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":"none","field":"channel","previous":"B","reason":"Price moved from 6.77% below to 4.78% above the falling 200dma, failing Channel-B price precondition."},{"current":0,"field":"score.mechanical","previous":5,"reason":"No-channel reports score Channel A for record; fear, low weekly RSI, no fresh distribution extreme and the 8-cap leave zero scored legs."},{"current":"0/8 Channel A","field":"gates.passed","previous":"6/9 Channel B","reason":"Channel-B gate set no longer applies; Channel-A top gates are dark and gate 8 is N/A."},{"current":"EXPIRED cold start","field":"position","previous":"EXPIRED cold start","reason":"Holdings clock remains 2026-07-05; no position claim is carried."}],"channel":"none","companion_framework":{"framework":"fallen_knives","gates":2,"rationale":"Computed same-timestamp FK state: sentiment 1, momentum 1, valuation 5 from negative bounded MVRV state, capitulation 0 and holder accumulation 3; mechanical/adjusted 10 with no fresh discretionary term. Gates 3 and 8 pass on the eight-gate ETH schema. FK <12, so force-cover does not fire.","score":10,"status":"AVAILABLE"},"cross_validation":{"rationale":"Channel A is capped at 8 and no channel is live, so both>=12 is structurally unfalsifiable. ETH's stronger long-side accumulation read is directionally consistent with no short.","relationship":"FR 0 versus FK 10; cap-bound inverse relationship","status":"CONSISTENT"},"data_gaps":[{"field":"current ETH MVRV-Z and LTH/exchange-inflow series","impact":"No valuation or distribution credit is inferred; stand-down is conservative.","source_ids":["coinbase_glassnode","theblock"],"status":"UNKNOWN"},{"field":"current Coinbase Premium three-day streak","impact":"Gate 6 stays unconfirmed, not N/A.","source_ids":["snapshot"],"status":"UNKNOWN"},{"field":"90-day open-interest high","impact":"No OI escalation claim; the 30-day percentile remains context only.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"equities breadth percent above 200dma","impact":"No breadth-divergence credit; asset is far from ATH anyway.","source_ids":["snapshot"],"status":"UNKNOWN"}],"deployment":{"deployed_pct":"0","dry_pct":"50","throttle_released":false,"tranches":[{"channel":"A","deployed":false,"entry_price":null,"pct":"5","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"No channel; adjusted 0 <11 and 0/8 gates <3.","state":"LOCKED","stop":null,"tag":"FR-A-1A-ETH-20260819-1223","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"10","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"No channel; adjusted 0 <13 and no prior tranche.","state":"LOCKED","stop":null,"tag":"FR-A-1B-ETH-20260819-1223","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"15","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"No channel; adjusted 0 <15 and the cap makes the line unreachable.","state":"LOCKED","stop":null,"tag":"FR-A-2-ETH-20260819-1223","time_stop":null},{"channel":"A","deployed":false,"entry_price":null,"pct":"20","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"No channel; mechanical 0 <19 and the cap makes the line unreachable.","state":"LOCKED","stop":null,"tag":"FR-A-3-ETH-20260819-1223","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"0.45x2250 + 0.30x2075 + 0.20x1900 + 0.05x1700 = 2100. Directional short EV -0.08%. Positive funding adds about +0.25% true carry over 21 days but is floored to zero for gating. Gate EV -0.08%; true total about +0.17%. CORROBORATIVE ONLY because the structural no-channel veto dominates. Collar ON.","probability_sum":1,"scenarios":[{"high":"2400","low":"2100","mid":"2250","name":"Recovery extends","probability":0.45,"rationale":"Price reclaimed the falling 200dma and daily RSI is strong; no stall yet."},{"high":"2200","low":"1950","mid":"2075","name":"Range/base","probability":0.3,"rationale":"Consolidation around the reclaimed 200dma is the second mode."},{"high":"2050","low":"1750","mid":"1900","name":"Mean reversion","probability":0.2,"rationale":"Failure back below the 200dma revisits the 50dma and July base."},{"high":"1800","low":"1600","mid":"1700","name":"Bear continuation","probability":0.05,"rationale":"Requires a failed reclaim and renewed lower-low structure; not present."}],"stated_ev":"2100","vs_spot_pct":"0.08"},"events":[{"as_of":"2026-08-19","impact":"This fires the prior channel-void falsifier at the live-price level; a completed weekly close is still the protective cover test.","name":"ETH reclaims falling 200dma intraday","status":"AVAILABLE"},{"as_of":"2026-08-18","impact":"Two sessions total +$102.3M after five positive weeks; supportive absorption, not distribution.","name":"US spot ETH ETF inflows","status":"AVAILABLE"},{"as_of":"2026-08-26","impact":"Repository calendar lists no NFP/CPI/PCE/FOMC event in the next seven crypto sessions; unscheduled regulatory risk remains.","name":"Tier-1 calendar check","status":"AVAILABLE"}],"evidence":{"derivatives":{"as_of":"2026-08-19T16:19:47Z","confidence":"MEDIUM","rationale":"L/S ratio 2.2468 but falling; OI extreme is only a 30-day single-venue context and cannot substitute for a Channel-A top.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Binance 30-day context","value":"OI 100th percentile, rising"},"etf_flows":{"as_of":"2026-08-18","confidence":"HIGH","rationale":"Two positive sessions and five positive weeks are absorption/support, not post-euphoria distribution; durability lock prevents a new short-friendly inflection claim.","source_ids":["farside_eth"],"status":"AVAILABLE","unit":"USD millions over Aug 17-18","value":"102.3"},"funding":{"as_of":"2026-08-19T16:19:47Z","confidence":"HIGH","rationale":"Positive but far below the >25% structural-vulnerability threshold; no sustained negative-funding squeeze penalty.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"4.26"},"macro":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"VIX 15.11, DXY 98.87, US10y 4.65%, real yield 2.44%, SPX 7738.52 and NDX 26452.70.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"mixed"},"momentum":{"as_of":"week of 2026-08-10","confidence":"HIGH","rationale":"Below Channel-A 60-point scoring floor; live-week RSI 50.45 is not used.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"completed-week RSI-14","value":"41.92"},"onchain":{"as_of":"2026-08-08","confidence":"LOW","rationale":"Large-wallet accumulation was reported, but current MVRV-Z, LTH supply and exchange inflow trends are not refreshed enough for Channel-A scoring.","source_ids":["theblock","coinbase_glassnode"],"status":"STALE","unit":null,"value":null},"regime":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"ETH is 56.25% below its one-year high but 4.78% above its falling 200dma. Channel B requires price below; Channel A is capped at 8 and unreachable.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"NONE / stand down"},"sentiment":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"Fear, not Channel-A euphoria.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"F&G 3-day average","value":"39.33"},"spot":{"as_of":"2026-08-19T16:19:47Z","confidence":"HIGH","rationale":"Median of synchronized CoinGecko, Binance, Coinbase and Kraken quotes; 0.217% spread.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/ETH","value":"2098.29"}},"falsifiers":[{"claim":"No channel is live","condition":"A completed close back below a still-falling 200dma while ETH remains >20% below the one-year high would restore Channel-B eligibility, subject to a fresh full stack.","status":"AVAILABLE"},{"claim":"Recovery/base is modal","condition":"A completed weekly close back below 2000 followed by a lower high shifts probability toward mean reversion/bear continuation.","status":"AVAILABLE"},{"claim":"No short entry is authorized","condition":"A channel must first become live, then its score, gates, stall/preflight and EV filters must all pass.","status":"AVAILABLE"}],"gates":{"active":8,"alt_reading":{"reachable_ceiling":"0/8 in the current cap-bound recovery regime; TOP gates are unreachable and FLOW-only changes are not setup progress.","threshold_conversion":"1A ceil(3/9x8)=3; 1B ceil(5/9x8)=5; P2 ceil(6/9x8)=6; P3 ceil(8/9x8)=8."},"measurement_basis":{"1":"FAIL [TOP] — F&G 39.33, not >=80 for seven days.","2":"FAIL [TOP] — weekly RSI 41.92, not >70.","3":"FAIL [TOP] — no current MVRV-Z >3; deep drawdown is opposite context.","4":"FAIL [FLOW] — funding 4.26% annualized, not >25%.","5":"FAIL/WARNING [FLOW] — ETF inflows are positive and price is >20% off high; capitulation-context rule forbids a short confirmation.","6":"FAIL/WARNING [FLOW] — current three-day Coinbase Premium streak unavailable; deep-drawdown condition forbids confirmation.","7":"FAIL [FLOW] — no fresh LTH distribution above pro-rated threshold.","8":"N/A [TOP] — ETH is >15% below its own one-year high, so breadth divergence precondition is structurally inapplicable.","9":"FAIL/WARNING [FLOW] — rotation gate cannot confirm while price is >20% off high under the capitulation-context rule."},"na":[8],"passed":[],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":8}},"identity":{"asset":"ETH","date":"2026-08-19","filename":"eth_flying_rocket_20260819_1223.json","framework":"flying_rocket","local_time":"12:23","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-08-25","note":"Yahoo trailing-one-year weekly high.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/ETH one-year high","value":"4796.35"},"drawdown_pct":{"as_of":"2026-08-19","note":"Phase-of-cycle routing input.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent below one-year high","value":"56.25"},"metrics":{"adr5":{"as_of":"2026-08-19","note":"Five full crypto sessions; approximately 2.96% of spot, so 1.5xADR is about 4.44%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"62.15"},"borrow":{"as_of":"2026-08-19","note":"Bitfinex single-venue lending proxy; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"0.3929"},"correlation_spx":{"as_of":"2026-08-10/2026-08-19","note":"Indicative 0.4452 on only seven return observations; no >0.7 surcharge.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"Pearson daily log returns","value":null},"daily_rsi":{"as_of":"2026-08-19","note":"Context only because no channel is live.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"75.77"},"options_skew":{"as_of":"2026-08-19","note":"Moneyness-based, not 25-delta; mild inversion is context, not a scored deep call-favored extreme.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"vol points call-rich","value":"-1.16"},"weekly_rsi":{"as_of":"week of 2026-08-10","note":"Completed week.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"41.92"}},"reconciliation":{"method":"Median of four synchronized live sources; frozen Yahoo close excluded","note":"Spread below 0.5%; no two-extremes EV test required.","quotes":[{"instrument":"CoinGecko ETH","state":"live","value":"2095.11"},{"instrument":"Binance ETHUSDT","state":"live","value":"2099.66"},{"instrument":"Coinbase ETH-USD","state":"live","value":"2098.58"},{"instrument":"Kraken ETHUSD","state":"receipt-time","value":"2098"}],"spread_pct":"0.217","status":"AVAILABLE"},"regime":{"bounce_age_sessions":37,"bounce_pct":"19.95","channel":"none","ma200":"2002.50","ma200_slope20_pct":"-5.37","ma50":"1859.76","price_vs_ma200_pct":"4.78","stall_confirmation":false},"spot":{"as_of":"2026-08-19T16:19:47Z","note":"Canonical synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/ETH","value":"2098.29"}},"narrative":{"arguments":{"discretion_ledger":[{"channel":"S1","date":"2026-08-19","load_bearing":false,"outcome":"No position","reason":"No discretion can repair a failed channel precondition.","s2":false,"term":"0.0"}],"prior_forecast_grade":"Aug 13 EV 1873.20 and modal range were exceeded; the named narrowing-gap channel-void falsifier fired.","stop_migration_ledger":[]},"bear_case":"The 200dma is still falling 5.37% over 20 sessions, the 50dma remains below it and the move is only one vertical session. IF ETH fails the reclaim, closes back below 2000 and later prints a lower-close/lower-high stall, THEN Channel B can be re-evaluated from scratch; no short is pre-authorized.","bull_case":"ETF inflows remain positive, large-wallet accumulation was recently reported and price has reclaimed the 200dma intraday. IF ETH completes and holds a weekly close above the 200dma, THEN the old bear-continuation thesis is structurally void; a weekly close back below 2000 is the named falsifier.","primary_action":{"rationale":"No live channel, 0/8 gates and no minimum short edge.","status":"AVAILABLE","value":"STAND_DOWN"},"rationale":"ROUTING — ETH is 56.25% below its one-year high and its 200dma is falling, but spot is 4.78% above that average. Channel B requires price below it and therefore is void. Channel A remains capped at 8 and dead because the asset is >20% below its one-year high. The framework has no live channel.\nSCORE AND GATES — Channel-A record score is 0: euphoria 0 (F&G 39.33), momentum 0 (weekly RSI 41.92), valuation 0 (no current MVRV-Z extreme; bounded state is negative), distribution 0 (ETF inflows/accumulation, no fresh LTH/exchange distribution), vulnerability 0 (funding 4.26%, no deep call skew, no ATH breadth divergence). Mechanical 0, discretion 0.0, adjusted 0, cap 8. Gate 8 is N/A because ETH is >15% below its high; 0/8 pass. Converted floors are 3/5/6/8 and all interpretation bands >=9 are unreachable; Phase 1A's 11 line sits three points above the cap. Hard-Rule-5 both>=12 is unfalsifiable by construction.\nEV — Recovery/range/mean-reversion/bear at 45/30/20/5 produces EV_price 2100, only 0.08% above spot. Directional short EV is -0.08%; positive funding income is +0.25% true but floored out of the minimum-edge gate. Gate EV fails +3%. It is CORROBORATIVE ONLY because no-channel routing is the dominant veto.\nPOSITION — Ledger is expired at 64,424 minutes by holdings_as_of. No quantity, cost basis, PnL, futures position, dry-powder dollars or attribution is claimed. Planning state is 0% deployed/100% dry under Hard Rule 4.\nPRIOR FORECAST AND FALSIFIERS — Aug 13 EV_price 1873.20 and modal range 1810-1960 were exceeded by current spot 2098.29, so both are falsified to the upside. The named gap-through-10% channel-void condition fired: the 50/200 gap narrowed to 7.13%, and price has moved above the 200dma. The protective weekly-close reclaim test has not completed yet, but the entry channel is already void at the live regime check. The held-close-below-1757 and FK>=12 falsifiers did not fire.\nANALYST READ — The rubric misses the emotional force of a daily RSI 75.77 and call-rich skew, both superficially attractive to a top-picker. They do not cure the routing failure. The strongest argument against stand-down is that a one-day 200dma overshoot can reverse violently. The stronger answer is that shorting that reversal before it exists is exactly the falling-knife error on the short side. The single input that changes the verdict first is a completed move back below the 200dma; even then a fresh Channel-B stall and score are required. Discretion ledger: 2026-08-19 | S1 0.0 | S2 no | non-load-bearing | structural routing dominates | no position. Stop migration ledger empty.\nACTIONS — 1) Stand down; no ETH short channel exists. 2) Grade the 200dma on the completed weekly close, not the intraday print. 3) If price closes back below a falling 200dma, rerun the entire Channel-B stack; do not carry forward the old 6/9 gates. 4) Refresh the ledger before any sizing. 5) Treat positive ETF flows and the FK 10/20 companion as squeeze risk, not short fuel.","summary":"ETH has exited Channel B by trading 4.78% above its falling 200dma while still 56.25% below the one-year high. Channel A is cap-bound and scores 0/20 with 0/8 active gates. Stand down; no short channel exists."},"out_of_scope":["No order execution was authorized or performed.","Context-panel OI, skew and borrow are not promoted into rubric legs or gates."],"position":{"asset":"ETH","attribution":{"active_tags":[],"status":"UNKNOWN"},"basis":{"reason":"Expired ledger; no basis quoted.","reliable":false},"custody":{"reason":"Expired ledger cannot establish custody.","status":"UNKNOWN"},"dry_powder":null,"futures":[],"pnl":{"reason":"No current position of record.","status":"UNKNOWN"},"quantity":null,"reconciliation":"Position snapshot is expired at 64,424 minutes by holdings_as_of. Cold start per Hard Rule 4: 0% planning deployment and 100% planning dry powder; this is not a factual account balance.","status":"EXPIRED"},"position_controls":{"action":{"rationale":"Expired ledger supplies no auditable open short; best-level audit cannot be fabricated.","status":"NOT_APPLICABLE","value":"NO_LIVE_POSITION_OF_RECORD"},"required":false,"status":"NOT_APPLICABLE"},"regime":{"ma200_falling":true,"pct_below_1y_ath":"56.25","price_below_ma200":false},"report_id":"eth_flying_rocket_20260819_1223","risk_controls":{"carry":{"carry_veto":false,"funding_annualized_pct":"4.26","gate_carry_ev_pct":"0","minimum_edge_pass":false,"status":"AVAILABLE","true_carry_ev_pct_21d":"0.25"},"concentration":{"per_asset_cap_pct":"30","planned_pct":"0","status":"PASS","total_short_book_cap_pct":"50"},"ratchet":{"reason":"No live tranche or prior stop in the current position sequence.","status":"NOT_APPLICABLE"},"stops":{"adr5":"62.15","channel_a_1a_ceiling_pct":"8.00","initial_floor_pct":"4.44","note":"Informational only; no channel and no fill.","status":"LOCKED"},"time_stops":{"p1a_days":21,"p1b_days":28,"p2_days":35,"p3_days":49,"status":"LOCKED"}},"run":{"prior_report_id":"eth_flying_rocket_20260813_0150","prior_report_sha256":"7b469c836062c14f06474a41a53385e9a199e1b9aece9ad071e5f8d8cdca25ec","run_id":"20260819-1619-159a8947","snapshot_id":"sha256:159a8947587444604f7bcf9d8dd1bb42f6e303fa2cc71fe446de87d1ced1e3cb","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:7296bd05f390f9004cf7110ac249df36f87dfb28726356b208bd7519a85750ba","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":0,"caps":[{"field":"phase_of_cycle","reason":"ETH is >20% below its one-year high; Channel A is structurally dead.","value":8},{"field":"channel","reason":"Price is above the falling 200dma, so Channel B precondition fails.","value":"none"},{"field":"squeeze_trap","reason":"Funding not sustained below -5% annualized.","value":0}],"discretion":0,"legs":{"distribution":0,"euphoria":0,"momentum":0,"valuation":0,"vulnerability":0},"mechanical":0,"penalties":[],"raw":0,"rounding":"half-down"},"sources":{"coinbase_glassnode":{"as_of":"2026-03-31","kind":"primary research","name":"Coinbase Institutional and Glassnode Charting Crypto Q2 2026","note":"Defines ETH MVRV/realized-price context but is stale; current MVRV-Z is not claimed from it.","retrieved_at":"2026-08-19T16:20:30Z","url":"https://ctf-images-01.coinbasecdn.net/k3n74unfin40/6T2gPNDvHvBpf246ziwYTW/81039d2975a8c36c9e475c06a24a4455/Charting_Crypto_2Q26.pdf"},"farside_eth":{"as_of":"2026-08-18","kind":"primary ETF flow table","name":"Farside US Ethereum ETF daily flows","note":"August 17 +$30.9M and August 18 +$71.4M; August 19 incomplete and excluded.","retrieved_at":"2026-08-19T16:20:30Z","url":"https://farside.co.uk/eth/"},"ledger":{"as_of":"2026-07-05T22:35:37.907881Z","kind":"user ledger","name":"Personal-accounting position snapshot","note":"EXPIRED; holdings clock drives age. Cold start per Hard Rule 4.","retrieved_at":"2026-08-19T16:18:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"snapshot":{"as_of":"2026-08-19T16:19:48.965Z","kind":"computed","name":"Deterministic BTC/ETH/macro live snapshot","note":"Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Bitfinex borrow, Deribit options and FRED/Yahoo macro.","retrieved_at":"2026-08-19T16:19:48.965Z","url":"data/runs/20260819-1619-159a8947/snapshot.json"},"theblock":{"as_of":"2026-08-08","kind":"news","name":"The Block ETH ETF and whale accumulation context","note":"ETH ETFs had five positive weeks and CryptoQuant-reported large-wallet accumulation; context is supportive, not current enough for a scored inflection.","retrieved_at":"2026-08-19T16:20:30Z","url":"https://www.theblock.co/news/markets/2026-08-08-bitcoin-ether-etfs-draw-1-1-billion-in-best-inflow-week-since-april-despite-low-volume-411204"}},"stale_inputs":["Ledger holdings_as_of 2026-07-05; expired.","On-chain MVRV definitions/current accumulation context are stale and unscored."],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FR-A-1A-ETH-20260819-1223","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FR-A-1B-ETH-20260819-1223","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FR-A-2-ETH-20260819-1223","decision":"STAND_DOWN","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FR-A-3-ETH-20260819-1223","decision":"STAND_DOWN","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FR-A-1A-ETH-20260819-1223","FR-A-1B-ETH-20260819-1223","FR-A-2-ETH-20260819-1223","FR-A-3-ETH-20260819-1223"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-19T16:19:48.965Z","generated_at":"2026-08-19T16:23:00Z","report_at":"2026-08-19T16:23:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Wait for a completed regime test and rerun from scratch; no tranche is authorized.","status":"AVAILABLE","value":"STAND_DOWN"},"statement":"No ETH short: no channel is live, Channel-A record score is 0/20 with 0/8 gates, and gate EV is -0.08%.","status":"STAND_DOWN"},"watchlist":[{"item":"Completed weekly close versus 200dma","status":"AVAILABLE","trigger":"Holding above roughly 2002.50 keeps Channel B void; a move back below does not itself authorize a short and requires full rerouting."},{"item":"Channel-A eligibility","status":"AVAILABLE","trigger":"ETH must recover to within 20% of the one-year high for Channel A to become live; within 10% removes its cap."},{"item":"FK force-cover","status":"AVAILABLE","trigger":"FK >=12 would explicitly forbid any short even if Channel B reappeared."},{"item":"Funding squeeze veto","status":"AVAILABLE","trigger":"Three intervals annualized below -5% activates the crowded-short penalty."}]}
```
