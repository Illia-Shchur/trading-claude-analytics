# BTC — Flying Rocket — 2026-08-19 12:22

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | BTC · Flying Rocket |
| Report time | 2026-08-19 12:22 (America/New_York) |
| Verdict | • STAND_DOWN — No BTC short: Channel B score 8/20, 6/9 gates, no stall, and +1.43% gate EV below the +3% minimum. |
| Adjusted score | **8/20** (mechanical 8, raw 8) |
| Confirmation gates | 6/9 active passed |
| Position | ⚠️ EXPIRED · quantity unavailable BTC |
| Deployment | 0% deployed · 30% dry |
| Primary action | **STAND_DOWN** — Wait for a confirmed stall and full re-score; no tranche is authorized. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $68,684.555/BTC | ✅ AVAILABLE | — | 2026-08-19T16:19:47Z | Canonical synchronized median.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| All-time high | $126,198.07/BTC one-year high | ✅ AVAILABLE | — | 2025-10-06 | Yahoo trailing-one-year weekly high.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Drawdown from ATH | 45.57% | ✅ AVAILABLE | — | 2026-08-19 | Channel B distance input.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| ADR-5 | $1,680.24 | ✅ AVAILABLE | — | 2026-08-19 | Five full crypto sessions; noise floor 1.5xADR is 3.67% of spot.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Borrow | 0.0011% | ✅ AVAILABLE | — | 2026-08-19 | Bitfinex single-venue lending proxy; context only.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Correlation spx | — | ⚠️ DATA_LIMITED | — | 2026-08-10/2026-08-19 | Indicative correlation 0.3350 on only seven return observations; no >0.7 surcharge.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Daily rsi | 72.05 RSI-14 | ✅ AVAILABLE | — | 2026-08-19 | Current daily series.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Options skew | 2.40 vol points put-rich | ✅ AVAILABLE | — | 2026-08-19 | Moneyness-based, not 25-delta risk reversal; disclosed context only.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Weekly RSI-14 | 38.80 RSI-14 | ✅ AVAILABLE | — | week of 2026-08-10 | Completed week.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |

**Regime:** — — Bounce age sessions: 37; Bounce pct: 11.20%; Channel: B; Ma200: 68992.18; Ma200 slope20 pct: -3.64%; Ma50: 63961.44; Price vs ma200 pct: -0.45%; Stall confirmation: No

### Spot reconciliation

**✅ AVAILABLE** — Median of four synchronized live sources; frozen Yahoo close excluded; spread 0.081%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| CoinGecko BTC | $68,658/BTC | ✅ live | — |
| Binance BTCUSDT | $68,713.73/BTC | ✅ live | — |
| Coinbase BTC-USD | $68,685.91/BTC | ✅ live | — |
| Kraken XBTUSD | $68,683.2/BTC | • receipt-time | — |

> Spread below 0.5%; no two-extremes EV test required.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Derivatives | OI 34.48th percentile, falling Binance 30-day context | ✅ AVAILABLE | MEDIUM | 2026-08-19T16:19:47Z | L/S 1.3776 and OI both falling; 90-day OI high unavailable, so no escalation claim.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Etf flows | 486.8 USD millions over Aug 17-18 | ✅ AVAILABLE | HIGH | 2026-08-18 | Two-session inflow restart follows four negative sessions in Aug 10-14; durability lock prevents calling it a new five-session regime.<br>Sources: [farside_btc](https://farside.co.uk/btc/) |
| Funding | 5.79% | ✅ AVAILABLE | HIGH | 2026-08-19T16:19:47Z | Positive funding means longs pay shorts; no interval below -5% annualized, so squeeze penalty and gate-8 veto are inactive.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Macro | mixed | ✅ AVAILABLE | HIGH | 2026-08-19 | VIX 15.11 (+3.85%/5d), DXY 98.87 (-1.14%), US10y 4.65%, real yield 2.44%, SPX -0.13%/5d.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Momentum | 72.05 daily RSI-14 | ✅ AVAILABLE | HIGH | 2026-08-19 | Daily RSI is locally overextended while completed-week RSI 38.80 preserves Channel B qualifier.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Onchain | — | ⚠️ STALE | LOW | 2026-08-06 | A negative Coinbase premium was reported, but it is stale context only; current MVRV-Z, LTH 30-day distribution and exchange-reserve trend were not independently refreshed.<br>Sources: [tokenpost](https://www.tokenpost.com/news/insights/22577) |
| Regime | Channel B | ✅ AVAILABLE | HIGH | 2026-08-19 | 45.57% below one-year high, price below a 200dma falling 3.64% over 20 sessions.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Sentiment | 39.33 F&G 3-day average | ✅ AVAILABLE | HIGH | 2026-08-19 | Spot F&G 46; three-day average remains Fear and does not meet local-greed confirmation.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |
| Spot | $68,684.555/BTC | ✅ AVAILABLE | HIGH | 2026-08-19T16:19:47Z | Median of synchronized CoinGecko, Binance, Coinbase and Kraken quotes; 0.081% panel spread.<br>Sources: [snapshot](data/runs/20260819-1619-159a8947/snapshot.json) |

**Data gaps:** 4 · **stale inputs:** 2 · **out of scope:** 2

**Data gaps**

- **current MVRV-Z and LTH/exchange-reserve series** — ❔ UNKNOWN — Not Channel-B scored; prevents strong distribution/on-chain claims.
- **current Coinbase Premium three-day streak** — ❔ UNKNOWN — No gate credit and no cover/preflight claim based on it.
- **90-day open-interest high** — — NOT_COVERED — No OI escalation of squeeze penalty; base penalty remains inactive from funding.
- **equities breadth percent above 200dma** — ❔ UNKNOWN — Macro/breadth is descriptive only in Channel B; no gate credit inferred.

**Stale inputs**

- Ledger holdings_as_of 2026-07-05; expired.
- Coinbase premium context as of 2026-08-06; not scored.

**Out of scope**

- No order execution was authorized or performed.
- Context-panel fields are not promoted into rubric legs or gates.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Distribution | 2 | 3 | Mechanical component |
| Euphoria | 1 | 5 | Mechanical component |
| Momentum | 4 | 4 | Mechanical component |
| Valuation | 1 | 5 | Mechanical component |
| Vulnerability | 0 | 3 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 8 | Legs plus penalties |
| Raw score | 8 | Mechanical plus discretion (0) |
| Adjusted score | **8/20** | Decision score |
| Rounding | half-up | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Channel | B | No phase-of-cycle cap; 30% per-asset ceiling and no Phase 3. |
| Squeeze trap | 0 | Funding not sustained below -5% annualized. |
| Bounce maturity | 0 | Bounce age 37 sessions, not immature. |

### Confirmation gates — 6/9 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | FAIL — rally 11.20% is below 15%. |
| 2 | • NOT PASSED | FAIL — low-to-current age 37 exceeds 35; low-to-high is also 37, so readings agree. |
| 3 | ✅ PASSED | PASS — daily RSI 72.05 >=52. |
| 4 | ✅ PASSED | PASS — completed-week RSI 38.80 <50. |
| 5 | ✅ PASSED | PASS — current price is 0.45% below the falling 200dma. |
| 6 | ✅ PASSED | PASS — 50dma 63961.44 below 200dma 68992.18. |
| 7 | ✅ PASSED | PASS — no completed weekly close reclaimed the 200dma in the trailing eight weeks. |
| 8 | ✅ PASSED | PASS/VETO CLEAR — funding is not sustained negative. |
| 9 | • NOT PASSED | FAIL — F&G local-greed test not met and funding did not flip after five negative sessions. |

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
| Rally extends | 40% | $69,000/BTC | $74,000/BTC | $71,500/BTC | Daily RSI is hot but price has just reached the falling 200dma without a stall. |
| Range near resistance | 30% | $66,000/BTC | $70,000/BTC | $68,000/BTC | A pause around the 200dma/late-July shelf is the second mode. |
| Mean reversion | 20% | $62,000/BTC | $66,000/BTC | $64,000/BTC | A failed breakout returns price to the 50dma/200-week area. |
| Bear leg resumes | 10% | $56,000/BTC | $62,000/BTC | $59,000/BTC | Requires a confirmed lower high and renewed breakdown; absent today. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $67,700/BTC |
| EV versus spot | -1.43% |

> 0.40x71500 + 0.30x68000 + 0.20x64000 + 0.10x59000 = 67700. Directional short EV +1.43%; positive funding adds +0.33% true carry over 21 days but is floored to zero for gating. True total +1.76%; gate total +1.43%, below +3%. Collar ON.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 30% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 5% | 🔒 LOCKED | no | — | — | — | — | — | B | — | FR-B-1A-BTC-20260819-1222 | Adjusted 8 < Channel-B 13; stall confirmation absent despite 6/9 gates. |
| 1B | 10% | 🔒 LOCKED | no | — | — | — | — | — | B | — | FR-B-1B-BTC-20260819-1222 | Adjusted 8 <15; no live 1A tranche. |
| 2 | 15% | 🔒 LOCKED | no | — | — | — | — | — | B | — | FR-B-2-BTC-20260819-1222 | Adjusted 8 <17; no confirmed stall and no prior tranches. |

## 6. Position, custody, and execution controls

| Position field | Value |
| --- | --- |
| Status | ⚠️ EXPIRED |
| Asset | BTC |
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
| Funding annualized pct | 5.79% |
| Gate carry ev pct | 0% |
| Minimum edge pass | No |
| Status | ✅ AVAILABLE |
| True carry ev pct 21d | 0.33 |

### Concentration

| Field | Value |
| --- | --- |
| Channel b asset cap pct | 30% |
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
| ADR-5 | 1680.24 |
| Channel b 1a ceiling pct | 6.00% |
| Initial floor pct | 3.67% |
| Note | Informational only; no fill. If a later stall authorizes entry, initial stop must be between 3.67% and 6% above fill. |
| Status | 🔒 LOCKED |

### Time stops

| Field | Value |
| --- | --- |
| P1a days | 21 |
| P1b days | 21 |
| P2 days | 28 |
| Status | 🔒 LOCKED |

## 7. Analyst rationale

**Summary:** BTC is in Channel B, but the rally is still rising into the falling 200dma rather than stalling below it. Mechanical and adjusted score are 8/20, gates 6/9, and gate-total short EV is only +1.43%; all tranches stay locked.

**Bull case:** ETF inflows restarted for two sessions, spot is on the 200dma, daily momentum is strong and no stall exists. IF BTC closes a week above the 200dma, THEN Channel B is void; that is the named structural falsifier.

**Bear case:** The 200dma is still falling, weekly RSI is 38.80, the 50dma remains below the 200dma and the current bounce is 11.20% from the 40-session low. IF a lower-close/lower-high stall appears below the bounce high, THEN the short candidate improves, but score and EV must still clear their own floors.

**Rationale:** CHANNEL AND SCORE — BTC is 45.57% below its one-year high, 0.45% below a 200dma falling 3.64% over 20 sessions, so Channel B is live. Legs: rally 1, local momentum 4, resistance 1, bear structure 2, relative sentiment 0 = mechanical 8. Squeeze penalty 0, maturity penalty 0, discretion 0.0, adjusted 8. No phase-of-cycle cap, but Channel B has a 30% asset cap and no Phase 3.
> GATES — Six of nine pass: daily RSI, weekly qualifier, 200dma proximity, 50<200, no weekly reclaim, and non-negative-funding veto. Rally <15%, age 37>35 and no local-greed/funding-flip gate fail. Both age readings equal 37. Stall confirmation is absent because Aug 19 made a higher close and high. The 6/9 count clears gate floors through P2, but score misses 1A by five and stall is an independent veto.
> EV AND CARRY — 40/30/20/10 across rally/range/mean-reversion/bear gives EV_price 67700. Directional short EV is +1.43%. True positive-funding carry adds +0.33% over 21 days, but income is floored to zero for the minimum-edge test; +1.43% fails the +3% filter. Collar ON because Channel B is >20% below the high and |EV|<3%.
> POSITION — The ledger is expired at 64,424 minutes by holdings_as_of. No quantity, basis, PnL, futures position, dry-powder dollar balance or tag attribution is claimed. Planning state is 0% deployed/100% dry under Hard Rule 4.
> PRIOR FORECAST — The Aug 13 EV_price 63495 is falsified to the upside by current spot 68684.56 (+8.17% versus EV). Its Range 61500-65500 mode is also exceeded. The named weekly-close-above-200dma falsifier has moved to the boundary but has not completed; the weekly-close-below-200w falsifier did not fire; two closes above 65000 are not yet completed.
> ANALYST READ — The rubric catches the local overextension, but it cannot turn a vertical session into a stall. The strongest argument for a short is that daily RSI 72 at a falling 200dma is classic bear-rally exhaustion. The stronger argument against acting today is mechanical: price has not printed the lower close/lower high the channel requires, ETF demand has restarted, and the modeled edge is less than half the minimum. The single input that changes the verdict first is a confirmed stall; even then the score remains below 13 unless resistance/bear-structure evidence improves. Discretion ledger: 2026-08-19 | S1 0.0 | S2 no | non-load-bearing | rubric captured tape | no position. Stop migration ledger: empty; no stop exists.
> ACTIONS — 1) Stand down; do not short the rising session. 2) Recheck after a lower-close/lower-high stall at or below the bounce high. 3) Require adjusted>=13, gate 8 pass, preflight pass and gate EV>3% before any 5% probe. 4) Refresh the ledger before sizing. Shorts have a clock; today the clock has not started.

**Primary action:** **STAND_DOWN** — Score, stall confirmation and minimum-edge filter all fail independently.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Prior forecast grade | Aug 13 EV 63495 and modal range were exceeded; weekly 200dma reclaim falsifier is at the boundary but requires a completed weekly close. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-19 | S1 | — | — | — | — | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | fallen_knives · 7/20 · 2 gates | Computed same-timestamp FK state: sentiment 1 (F&G 39.33), momentum 2 (weekly RSI 38.80), valuation 4 (latest pinned low MVRV-Z state retained as stale bounded input), capitulation 0, holder 0; adjusted 7. Gates 3 and 6 pass. FK <12, so force-cover does not fire. |
| Cross-validation | • NOT_EVALUABLE | FR Channel B 8 versus FK 7 | Hard Rule 5 level-based both>=12 consistency is not evaluated in Channel B because the frameworks score different horizons. FK>=12 force-cover governs and is not live. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| BTC stall at falling 200dma | ✅ AVAILABLE | A lower close than the prior session and lower high than the bounce high; without both there is no Channel-B entry. |
| Channel B regime | ✅ AVAILABLE | A completed weekly close above roughly 68992 voids Channel B and would cover all B tranches. |
| FK force-cover | ✅ AVAILABLE | A published or strictest-wins re-derived FK score >=12 forbids the short. |
| Funding squeeze veto | ✅ AVAILABLE | Three consecutive intervals annualized below -5% activates penalty and makes gate 8 fail. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-18 | US spot BTC ETF flow restart | ✅ AVAILABLE | Two positive sessions total +$486.8M; one day is not a run, so it does not yet establish a durable new flow regime. |
| 2026-08-08 | Coldcard exploit aftermath | ✅ AVAILABLE | Custody-risk headlines can redirect demand toward ETFs and also create exchange-flow noise; bullish surprise risk is dangerous for shorts. |
| 2026-08-26 | Tier-1 calendar check | ✅ AVAILABLE | Repository calendar lists no NFP/CPI/PCE/FOMC event in the next seven crypto sessions; unscheduled geopolitical/regulatory risk remains. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| Channel B remains live | A completed weekly close above the falling 200dma near 68992 voids the channel. | ✅ AVAILABLE |
| Rally-extension is the modal path | A confirmed daily stall below the bounce high followed by a close below 66000 shifts mass toward mean reversion. | ✅ AVAILABLE |
| No short entry is authorized | Adjusted score must reach 13, at least three gates must pass, stall confirmation must print, preflight must pass and gate EV must exceed +3%. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.mechanical | 5 | 8 | Rally extension rose 4.22%->11.20%, daily RSI 48.33->72.05, and price reached the 200dma zone. |
| Gates.passed | 4/9 | 6/9 | Daily RSI and 200dma-proximity gates turned on; age remains over 35. |
| Stall confirmation | No | No | The sharp Aug 19 advance is still rising, not stalling. |
| Position | EXPIRED cold start | EXPIRED cold start | Holdings clock remains 2026-07-05; no position claim is carried. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| farside_btc | Farside US Bitcoin ETF daily flows | primary ETF flow table | 2026-08-18 | 2026-08-19T16:20:30Z | August 17 +$297.5M and August 18 +$189.3M; August 19 incomplete and excluded.<br>[Open source](https://farside.co.uk/btc/) |
| ledger | Personal-accounting position snapshot | user ledger | 2026-07-05T22:35:37.907881Z | 2026-08-19T16:18:00Z | EXPIRED; holdings clock drives age. Cold start per Hard Rule 4.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| snapshot | Deterministic BTC/ETH/macro live snapshot | computed | 2026-08-19T16:19:48.965Z | 2026-08-19T16:19:48.965Z | Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Bitfinex borrow, Deribit options and FRED/Yahoo macro.<br>[Open source](data/runs/20260819-1619-159a8947/snapshot.json) |
| theblock | The Block BTC/ETH ETF and on-chain context | news | 2026-08-08 | 2026-08-19T16:20:30Z | Five-session BTC ETF inflow streak and Coldcard exploit context; older than the current two-session flow restart.<br>[Open source](https://www.theblock.co/news/markets/2026-08-08-bitcoin-ether-etfs-draw-1-1-billion-in-best-inflow-week-since-april-despite-low-volume-411204) |
| tokenpost | Coinbase BTC flow and premium context | secondary on-chain context | 2026-08-06 | 2026-08-19T16:20:30Z | Negative Coinbase premium and mixed Coinbase flows; too stale to score a current three-day premium gate.<br>[Open source](https://www.tokenpost.com/news/insights/22577) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-19T16:19:48.965Z |
| Generated at | 2026-08-19T16:22:05Z |
| Report at | 2026-08-19T16:22:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | btc_flying_rocket_20260819_1222 |
| Report filename | btc_flying_rocket_20260819_1222.json |
| Run ID | 20260819-1619-159a8947 |
| Snapshot ID | sha256:159a8947587444604f7bcf9d8dd1bb42f6e303fa2cc71fe446de87d1ced1e3cb |
| Prior report | btc_flying_rocket_20260813_0150 |
| Prior report hash | 02cd733cb42fc193a2044d36fc3c2234ca7cd1c80f081240914282a82e0847ec |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:7296bd05f390f9004cf7110ac249df36f87dfb28726356b208bd7519a85750ba |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | • STAND_DOWN | FR-B-1A-BTC-20260819-1222 | crypto |
| 1B | • STAND_DOWN | FR-B-1B-BTC-20260819-1222 | crypto |
| 2 | • STAND_DOWN | FR-B-2-BTC-20260819-1222 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FR-B-1A-BTC-20260819-1222, FR-B-1B-BTC-20260819-1222, FR-B-2-BTC-20260819-1222

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":8,"field":"score.mechanical","previous":5,"reason":"Rally extension rose 4.22%->11.20%, daily RSI 48.33->72.05, and price reached the 200dma zone."},{"current":"6/9","field":"gates.passed","previous":"4/9","reason":"Daily RSI and 200dma-proximity gates turned on; age remains over 35."},{"current":false,"field":"stall_confirmation","previous":false,"reason":"The sharp Aug 19 advance is still rising, not stalling."},{"current":"EXPIRED cold start","field":"position","previous":"EXPIRED cold start","reason":"Holdings clock remains 2026-07-05; no position claim is carried."}],"channel":"B","companion_framework":{"framework":"fallen_knives","gates":2,"rationale":"Computed same-timestamp FK state: sentiment 1 (F&G 39.33), momentum 2 (weekly RSI 38.80), valuation 4 (latest pinned low MVRV-Z state retained as stale bounded input), capitulation 0, holder 0; adjusted 7. Gates 3 and 6 pass. FK <12, so force-cover does not fire.","score":7,"status":"AVAILABLE"},"cross_validation":{"rationale":"Hard Rule 5 level-based both>=12 consistency is not evaluated in Channel B because the frameworks score different horizons. FK>=12 force-cover governs and is not live.","relationship":"FR Channel B 8 versus FK 7","status":"NOT_EVALUABLE"},"data_gaps":[{"field":"current MVRV-Z and LTH/exchange-reserve series","impact":"Not Channel-B scored; prevents strong distribution/on-chain claims.","source_ids":["tokenpost"],"status":"UNKNOWN"},{"field":"current Coinbase Premium three-day streak","impact":"No gate credit and no cover/preflight claim based on it.","source_ids":["tokenpost"],"status":"UNKNOWN"},{"field":"90-day open-interest high","impact":"No OI escalation of squeeze penalty; base penalty remains inactive from funding.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"equities breadth percent above 200dma","impact":"Macro/breadth is descriptive only in Channel B; no gate credit inferred.","source_ids":["snapshot"],"status":"UNKNOWN"}],"deployment":{"deployed_pct":"0","dry_pct":"30","throttle_released":false,"tranches":[{"channel":"B","deployed":false,"entry_price":null,"pct":"5","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 8 < Channel-B 13; stall confirmation absent despite 6/9 gates.","state":"LOCKED","stop":null,"tag":"FR-B-1A-BTC-20260819-1222","time_stop":null},{"channel":"B","deployed":false,"entry_price":null,"pct":"10","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 8 <15; no live 1A tranche.","state":"LOCKED","stop":null,"tag":"FR-B-1B-BTC-20260819-1222","time_stop":null},{"channel":"B","deployed":false,"entry_price":null,"pct":"15","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 8 <17; no confirmed stall and no prior tranches.","state":"LOCKED","stop":null,"tag":"FR-B-2-BTC-20260819-1222","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"0.40x71500 + 0.30x68000 + 0.20x64000 + 0.10x59000 = 67700. Directional short EV +1.43%; positive funding adds +0.33% true carry over 21 days but is floored to zero for gating. True total +1.76%; gate total +1.43%, below +3%. Collar ON.","probability_sum":1,"scenarios":[{"high":"74000","low":"69000","mid":"71500","name":"Rally extends","probability":0.4,"rationale":"Daily RSI is hot but price has just reached the falling 200dma without a stall."},{"high":"70000","low":"66000","mid":"68000","name":"Range near resistance","probability":0.3,"rationale":"A pause around the 200dma/late-July shelf is the second mode."},{"high":"66000","low":"62000","mid":"64000","name":"Mean reversion","probability":0.2,"rationale":"A failed breakout returns price to the 50dma/200-week area."},{"high":"62000","low":"56000","mid":"59000","name":"Bear leg resumes","probability":0.1,"rationale":"Requires a confirmed lower high and renewed breakdown; absent today."}],"stated_ev":"67700","vs_spot_pct":"-1.43"},"events":[{"as_of":"2026-08-18","impact":"Two positive sessions total +$486.8M; one day is not a run, so it does not yet establish a durable new flow regime.","name":"US spot BTC ETF flow restart","status":"AVAILABLE"},{"as_of":"2026-08-08","impact":"Custody-risk headlines can redirect demand toward ETFs and also create exchange-flow noise; bullish surprise risk is dangerous for shorts.","name":"Coldcard exploit aftermath","status":"AVAILABLE"},{"as_of":"2026-08-26","impact":"Repository calendar lists no NFP/CPI/PCE/FOMC event in the next seven crypto sessions; unscheduled geopolitical/regulatory risk remains.","name":"Tier-1 calendar check","status":"AVAILABLE"}],"evidence":{"derivatives":{"as_of":"2026-08-19T16:19:47Z","confidence":"MEDIUM","rationale":"L/S 1.3776 and OI both falling; 90-day OI high unavailable, so no escalation claim.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Binance 30-day context","value":"OI 34.48th percentile, falling"},"etf_flows":{"as_of":"2026-08-18","confidence":"HIGH","rationale":"Two-session inflow restart follows four negative sessions in Aug 10-14; durability lock prevents calling it a new five-session regime.","source_ids":["farside_btc"],"status":"AVAILABLE","unit":"USD millions over Aug 17-18","value":"486.8"},"funding":{"as_of":"2026-08-19T16:19:47Z","confidence":"HIGH","rationale":"Positive funding means longs pay shorts; no interval below -5% annualized, so squeeze penalty and gate-8 veto are inactive.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"5.79"},"macro":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"VIX 15.11 (+3.85%/5d), DXY 98.87 (-1.14%), US10y 4.65%, real yield 2.44%, SPX -0.13%/5d.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"mixed"},"momentum":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"Daily RSI is locally overextended while completed-week RSI 38.80 preserves Channel B qualifier.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"daily RSI-14","value":"72.05"},"onchain":{"as_of":"2026-08-06","confidence":"LOW","rationale":"A negative Coinbase premium was reported, but it is stale context only; current MVRV-Z, LTH 30-day distribution and exchange-reserve trend were not independently refreshed.","source_ids":["tokenpost"],"status":"STALE","unit":null,"value":null},"regime":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"45.57% below one-year high, price below a 200dma falling 3.64% over 20 sessions.","source_ids":["snapshot"],"status":"AVAILABLE","unit":null,"value":"Channel B"},"sentiment":{"as_of":"2026-08-19","confidence":"HIGH","rationale":"Spot F&G 46; three-day average remains Fear and does not meet local-greed confirmation.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"F&G 3-day average","value":"39.33"},"spot":{"as_of":"2026-08-19T16:19:47Z","confidence":"HIGH","rationale":"Median of synchronized CoinGecko, Binance, Coinbase and Kraken quotes; 0.081% panel spread.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"68684.555"}},"falsifiers":[{"claim":"Channel B remains live","condition":"A completed weekly close above the falling 200dma near 68992 voids the channel.","status":"AVAILABLE"},{"claim":"Rally-extension is the modal path","condition":"A confirmed daily stall below the bounce high followed by a close below 66000 shifts mass toward mean reversion.","status":"AVAILABLE"},{"claim":"No short entry is authorized","condition":"Adjusted score must reach 13, at least three gates must pass, stall confirmation must print, preflight must pass and gate EV must exceed +3%.","status":"AVAILABLE"}],"gates":{"active":9,"alt_reading":null,"measurement_basis":{"1":"FAIL — rally 11.20% is below 15%.","2":"FAIL — low-to-current age 37 exceeds 35; low-to-high is also 37, so readings agree.","3":"PASS — daily RSI 72.05 >=52.","4":"PASS — completed-week RSI 38.80 <50.","5":"PASS — current price is 0.45% below the falling 200dma.","6":"PASS — 50dma 63961.44 below 200dma 68992.18.","7":"PASS — no completed weekly close reclaimed the 200dma in the trailing eight weeks.","8":"PASS/VETO CLEAR — funding is not sustained negative.","9":"FAIL — F&G local-greed test not met and funding did not flip after five negative sessions.","stall":"FAIL — Aug 19 close and high are above the prior session; rally has not stalled."},"na":[],"passed":[3,4,5,6,7,8],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":8}},"identity":{"asset":"BTC","date":"2026-08-19","filename":"btc_flying_rocket_20260819_1222.json","framework":"flying_rocket","local_time":"12:22","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-10-06","note":"Yahoo trailing-one-year weekly high.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC one-year high","value":"126198.07"},"drawdown_pct":{"as_of":"2026-08-19","note":"Channel B distance input.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent below one-year high","value":"45.57"},"metrics":{"adr5":{"as_of":"2026-08-19","note":"Five full crypto sessions; noise floor 1.5xADR is 3.67% of spot.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"1680.24"},"borrow":{"as_of":"2026-08-19","note":"Bitfinex single-venue lending proxy; context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"0.0011"},"correlation_spx":{"as_of":"2026-08-10/2026-08-19","note":"Indicative correlation 0.3350 on only seven return observations; no >0.7 surcharge.","source_ids":["snapshot"],"status":"DATA_LIMITED","unit":"Pearson daily log returns","value":null},"daily_rsi":{"as_of":"2026-08-19","note":"Current daily series.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"72.05"},"options_skew":{"as_of":"2026-08-19","note":"Moneyness-based, not 25-delta risk reversal; disclosed context only.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"vol points put-rich","value":"2.40"},"weekly_rsi":{"as_of":"week of 2026-08-10","note":"Completed week.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"38.80"}},"reconciliation":{"method":"Median of four synchronized live sources; frozen Yahoo close excluded","note":"Spread below 0.5%; no two-extremes EV test required.","quotes":[{"instrument":"CoinGecko BTC","state":"live","value":"68658"},{"instrument":"Binance BTCUSDT","state":"live","value":"68713.73"},{"instrument":"Coinbase BTC-USD","state":"live","value":"68685.91"},{"instrument":"Kraken XBTUSD","state":"receipt-time","value":"68683.2"}],"spread_pct":"0.081","status":"AVAILABLE"},"regime":{"bounce_age_sessions":37,"bounce_pct":"11.20","channel":"B","ma200":"68992.18","ma200_slope20_pct":"-3.64","ma50":"63961.44","price_vs_ma200_pct":"-0.45","stall_confirmation":false},"spot":{"as_of":"2026-08-19T16:19:47Z","note":"Canonical synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/BTC","value":"68684.555"}},"narrative":{"arguments":{"discretion_ledger":[{"channel":"S1","date":"2026-08-19","load_bearing":false,"outcome":"No position","reason":"No hidden factor overrides the rubric or stall veto.","s2":false,"term":"0.0"}],"prior_forecast_grade":"Aug 13 EV 63495 and modal range were exceeded; weekly 200dma reclaim falsifier is at the boundary but requires a completed weekly close.","stop_migration_ledger":[]},"bear_case":"The 200dma is still falling, weekly RSI is 38.80, the 50dma remains below the 200dma and the current bounce is 11.20% from the 40-session low. IF a lower-close/lower-high stall appears below the bounce high, THEN the short candidate improves, but score and EV must still clear their own floors.","bull_case":"ETF inflows restarted for two sessions, spot is on the 200dma, daily momentum is strong and no stall exists. IF BTC closes a week above the 200dma, THEN Channel B is void; that is the named structural falsifier.","primary_action":{"rationale":"Score, stall confirmation and minimum-edge filter all fail independently.","status":"AVAILABLE","value":"STAND_DOWN"},"rationale":"CHANNEL AND SCORE — BTC is 45.57% below its one-year high, 0.45% below a 200dma falling 3.64% over 20 sessions, so Channel B is live. Legs: rally 1, local momentum 4, resistance 1, bear structure 2, relative sentiment 0 = mechanical 8. Squeeze penalty 0, maturity penalty 0, discretion 0.0, adjusted 8. No phase-of-cycle cap, but Channel B has a 30% asset cap and no Phase 3.\nGATES — Six of nine pass: daily RSI, weekly qualifier, 200dma proximity, 50<200, no weekly reclaim, and non-negative-funding veto. Rally <15%, age 37>35 and no local-greed/funding-flip gate fail. Both age readings equal 37. Stall confirmation is absent because Aug 19 made a higher close and high. The 6/9 count clears gate floors through P2, but score misses 1A by five and stall is an independent veto.\nEV AND CARRY — 40/30/20/10 across rally/range/mean-reversion/bear gives EV_price 67700. Directional short EV is +1.43%. True positive-funding carry adds +0.33% over 21 days, but income is floored to zero for the minimum-edge test; +1.43% fails the +3% filter. Collar ON because Channel B is >20% below the high and |EV|<3%.\nPOSITION — The ledger is expired at 64,424 minutes by holdings_as_of. No quantity, basis, PnL, futures position, dry-powder dollar balance or tag attribution is claimed. Planning state is 0% deployed/100% dry under Hard Rule 4.\nPRIOR FORECAST — The Aug 13 EV_price 63495 is falsified to the upside by current spot 68684.56 (+8.17% versus EV). Its Range 61500-65500 mode is also exceeded. The named weekly-close-above-200dma falsifier has moved to the boundary but has not completed; the weekly-close-below-200w falsifier did not fire; two closes above 65000 are not yet completed.\nANALYST READ — The rubric catches the local overextension, but it cannot turn a vertical session into a stall. The strongest argument for a short is that daily RSI 72 at a falling 200dma is classic bear-rally exhaustion. The stronger argument against acting today is mechanical: price has not printed the lower close/lower high the channel requires, ETF demand has restarted, and the modeled edge is less than half the minimum. The single input that changes the verdict first is a confirmed stall; even then the score remains below 13 unless resistance/bear-structure evidence improves. Discretion ledger: 2026-08-19 | S1 0.0 | S2 no | non-load-bearing | rubric captured tape | no position. Stop migration ledger: empty; no stop exists.\nACTIONS — 1) Stand down; do not short the rising session. 2) Recheck after a lower-close/lower-high stall at or below the bounce high. 3) Require adjusted>=13, gate 8 pass, preflight pass and gate EV>3% before any 5% probe. 4) Refresh the ledger before sizing. Shorts have a clock; today the clock has not started.","summary":"BTC is in Channel B, but the rally is still rising into the falling 200dma rather than stalling below it. Mechanical and adjusted score are 8/20, gates 6/9, and gate-total short EV is only +1.43%; all tranches stay locked."},"out_of_scope":["No order execution was authorized or performed.","Context-panel fields are not promoted into rubric legs or gates."],"position":{"asset":"BTC","attribution":{"active_tags":[],"status":"UNKNOWN"},"basis":{"reason":"Expired ledger; no basis quoted.","reliable":false},"custody":{"reason":"Expired ledger cannot establish custody.","status":"UNKNOWN"},"dry_powder":null,"futures":[],"pnl":{"reason":"No current position of record.","status":"UNKNOWN"},"quantity":null,"reconciliation":"Position snapshot is expired at 64,424 minutes by holdings_as_of. Cold start per Hard Rule 4: 0% planning deployment and 100% planning dry powder; this is not a factual account balance.","status":"EXPIRED"},"position_controls":{"action":{"rationale":"Expired ledger supplies no auditable open short; best-level audit cannot be fabricated.","status":"NOT_APPLICABLE","value":"NO_LIVE_POSITION_OF_RECORD"},"required":false,"status":"NOT_APPLICABLE"},"regime":{"ma200_falling":true,"pct_below_1y_ath":"45.57","price_below_ma200":true},"report_id":"btc_flying_rocket_20260819_1222","risk_controls":{"carry":{"carry_veto":false,"funding_annualized_pct":"5.79","gate_carry_ev_pct":"0","minimum_edge_pass":false,"status":"AVAILABLE","true_carry_ev_pct_21d":"0.33"},"concentration":{"channel_b_asset_cap_pct":"30","planned_pct":"0","status":"PASS","total_short_book_cap_pct":"50"},"ratchet":{"reason":"No live tranche or prior stop in the current position sequence.","status":"NOT_APPLICABLE"},"stops":{"adr5":"1680.24","channel_b_1a_ceiling_pct":"6.00","initial_floor_pct":"3.67","note":"Informational only; no fill. If a later stall authorizes entry, initial stop must be between 3.67% and 6% above fill.","status":"LOCKED"},"time_stops":{"p1a_days":21,"p1b_days":21,"p2_days":28,"status":"LOCKED"}},"run":{"prior_report_id":"btc_flying_rocket_20260813_0150","prior_report_sha256":"02cd733cb42fc193a2044d36fc3c2234ca7cd1c80f081240914282a82e0847ec","run_id":"20260819-1619-159a8947","snapshot_id":"sha256:159a8947587444604f7bcf9d8dd1bb42f6e303fa2cc71fe446de87d1ced1e3cb","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:7296bd05f390f9004cf7110ac249df36f87dfb28726356b208bd7519a85750ba","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":8,"caps":[{"field":"channel","reason":"No phase-of-cycle cap; 30% per-asset ceiling and no Phase 3.","value":"B"},{"field":"squeeze_trap","reason":"Funding not sustained below -5% annualized.","value":0},{"field":"bounce_maturity","reason":"Bounce age 37 sessions, not immature.","value":0}],"discretion":0,"legs":{"distribution":2,"euphoria":1,"momentum":4,"valuation":1,"vulnerability":0},"mechanical":8,"penalties":[],"raw":8,"rounding":"half-up"},"sources":{"farside_btc":{"as_of":"2026-08-18","kind":"primary ETF flow table","name":"Farside US Bitcoin ETF daily flows","note":"August 17 +$297.5M and August 18 +$189.3M; August 19 incomplete and excluded.","retrieved_at":"2026-08-19T16:20:30Z","url":"https://farside.co.uk/btc/"},"ledger":{"as_of":"2026-07-05T22:35:37.907881Z","kind":"user ledger","name":"Personal-accounting position snapshot","note":"EXPIRED; holdings clock drives age. Cold start per Hard Rule 4.","retrieved_at":"2026-08-19T16:18:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"snapshot":{"as_of":"2026-08-19T16:19:48.965Z","kind":"computed","name":"Deterministic BTC/ETH/macro live snapshot","note":"Four synchronized venue quotes, Yahoo daily/weekly history, Alternative.me, Binance derivatives, Bitfinex borrow, Deribit options and FRED/Yahoo macro.","retrieved_at":"2026-08-19T16:19:48.965Z","url":"data/runs/20260819-1619-159a8947/snapshot.json"},"theblock":{"as_of":"2026-08-08","kind":"news","name":"The Block BTC/ETH ETF and on-chain context","note":"Five-session BTC ETF inflow streak and Coldcard exploit context; older than the current two-session flow restart.","retrieved_at":"2026-08-19T16:20:30Z","url":"https://www.theblock.co/news/markets/2026-08-08-bitcoin-ether-etfs-draw-1-1-billion-in-best-inflow-week-since-april-despite-low-volume-411204"},"tokenpost":{"as_of":"2026-08-06","kind":"secondary on-chain context","name":"Coinbase BTC flow and premium context","note":"Negative Coinbase premium and mixed Coinbase flows; too stale to score a current three-day premium gate.","retrieved_at":"2026-08-19T16:20:30Z","url":"https://www.tokenpost.com/news/insights/22577"}},"stale_inputs":["Ledger holdings_as_of 2026-07-05; expired.","Coinbase premium context as of 2026-08-06; not scored."],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FR-B-1A-BTC-20260819-1222","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FR-B-1B-BTC-20260819-1222","decision":"STAND_DOWN","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FR-B-2-BTC-20260819-1222","decision":"STAND_DOWN","instrument_class":"crypto","phase":"2"}],"instrument_class":"crypto","reserved_tags":["FR-B-1A-BTC-20260819-1222","FR-B-1B-BTC-20260819-1222","FR-B-2-BTC-20260819-1222"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-19T16:19:48.965Z","generated_at":"2026-08-19T16:22:05Z","report_at":"2026-08-19T16:22:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Wait for a confirmed stall and full re-score; no tranche is authorized.","status":"AVAILABLE","value":"STAND_DOWN"},"statement":"No BTC short: Channel B score 8/20, 6/9 gates, no stall, and +1.43% gate EV below the +3% minimum.","status":"STAND_DOWN"},"watchlist":[{"item":"BTC stall at falling 200dma","status":"AVAILABLE","trigger":"A lower close than the prior session and lower high than the bounce high; without both there is no Channel-B entry."},{"item":"Channel B regime","status":"AVAILABLE","trigger":"A completed weekly close above roughly 68992 voids Channel B and would cover all B tranches."},{"item":"FK force-cover","status":"AVAILABLE","trigger":"A published or strictest-wins re-derived FK score >=12 forbids the short."},{"item":"Funding squeeze veto","status":"AVAILABLE","trigger":"Three consecutive intervals annualized below -5% activates penalty and makes gate 8 fail."}]}
```
