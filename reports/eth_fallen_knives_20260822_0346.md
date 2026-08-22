# ETH — Fallen Knives — 2026-08-22 03:46

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | ETH · Fallen Knives |
| Report time | 2026-08-22 03:46 (America/New_York) |
| Verdict | • WAIT — ETH is not an accumulation entry: adjusted 7/20, gates 4/8, and every phase remains locked. Do not add; reconcile the ledger and let leverage cool. |
| Adjusted score | **7/20** (mechanical 7, raw 7) |
| Confirmation gates | 4/8 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable ETH |
| Deployment | 0% deployed · 100% dry |
| Primary action | **WAIT_NO_ADD** — Phase 1A score is one point short; no discretionary channel can bridge it. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $2,438.39 | ✅ AVAILABLE | — | 2026-08-22T07:38:48Z | Four-source synchronized median.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| All-time high | $4,946.05 | ✅ AVAILABLE | — | 2025-08-24 | —<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Drawdown from ATH | 50.70% | ✅ AVAILABLE | — | 2026-08-22 | —<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| ADR-5 | $184.68 | ✅ AVAILABLE | — | 2026-08-22 | Five most recent full crypto sessions.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Dry-powder yield | 3.71% | ✅ AVAILABLE | — | 2026-08-21 | —<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Eth btc | 0.03150 ETH/BTC ratio | ✅ AVAILABLE | — | 2026-08-22T07:38:48Z | Below the 0.032 D1 falsifier.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Funding | 5.73% | ✅ AVAILABLE | — | 2026-08-22T04:00:00Z | Positive; no three-interval negative streak.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Futures cvd 3d | $1,932,341,508.57 | ✅ AVAILABLE | — | 2026-08-22T00:00:00Z | Binance single-venue fallback; positive.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Open interest change 24h | 10.367% | ✅ AVAILABLE | — | 2026-08-22T04:00:00Z | OI is at its Binance 90-day high after +38.207% over the fetched window.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| 200-week SMA | $2,487.57 | ✅ AVAILABLE | — | week starting 2026-08-10 | Spot is 1.98% below; gate 6 lit.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Spot cvd 3d | $307,231,925.69 | ✅ AVAILABLE | — | 2026-08-22T00:00:00Z | Binance single-venue fallback; positive.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |

**Regime:** • violent trend repair at the 200-week mean; not a fallen knife — Active downtrend: No; Daily rsi14: 79.57; Label: violent trend repair at the 200-week mean; not a fallen knife; Ma200: 2005.47; Ma200 falling: Yes; Ma200 slope 20 pct: -4.28%; Price below ma200: No; Trend residual: spot/futures CVD and OI rose together; 200-week proximity helps structure but leverage makes chase risk acute

### Spot reconciliation

**✅ AVAILABLE** — median of four synchronized venue/aggregator quotes; spread 0.034%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| — | $2,438.09 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| — | $2,438.39 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| — | $2,438.43 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| — | $2,438.84 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |

> Yahoo frozen daily close excluded from the median.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Correlation | 0.132117 Pearson correlation | ✅ AVAILABLE | MEDIUM | 2026-08-21 | 29 paired daily log returns; surcharge off and the Phase-2 correlation condition passes.<br>Sources: [correlation](https://query1.finance.yahoo.com/) |
| Etf flows | 971.6 USD millions trailing month | ✅ AVAILABLE | HIGH | 2026-08-21 | Latest day +184.0M, five consecutive positive sessions and +692.6M for Aug 17-21; monthly flow is positive, so gate 4 is off.<br>Sources: [farside](https://farside.co.uk/eth/), [sosovalue](https://www.sosovalue.com/assets/etf/us-eth-spot) |
| Holder | -1.263% | ✅ AVAILABLE | MEDIUM | 2026-08-21 | Reserves declined, earning one of two holder tests and a 1.5 leg; true LTH data is provider-gated, so gate 8 receives no credit.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Liquidations | 206,880,000 USD ETH liquidations / 24h | ✅ AVAILABLE | MEDIUM | 2026-08-21 | CoinGlass-cited ETH losses during a historic market-wide short squeeze; counted as top-decile intensity, not as long-side capitulation direction.<br>Sources: [liquidations](https://finance.yahoo.com/markets/crypto/articles/crypto-bears-burned-short-liquidations-055534539.html) |
| Macro | neutral-positive with oil risk | ✅ AVAILABLE | MEDIUM | 2026-08-21 | Real yield eased 4bp and DXY fell 0.87%; regulatory catalysts support gate 9, while Brent +6.63% and equities lower keep confidence moderate.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json), [cftc](https://www.cftc.gov/PressRoom/PressReleases/9279-26) |
| Sentiment | 68.33 Fear & Greed 3-day average | ✅ AVAILABLE | HIGH | 2026-08-22 | Alternative.me prints 62, 72 and 71; >50 scores zero and the <=15 streak is zero.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Spot | $2,438.39 | ✅ AVAILABLE | HIGH | 2026-08-22T07:38:48Z | Median of synchronized CoinGecko, Binance, Coinbase and Kraken quotes; panel spread 0.034%.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Valuation | 0.288 MVRV-Z | ✅ AVAILABLE | MEDIUM | 2026-08-21 | Coin Metrics reconstruction; <=0.5 scores valuation 4 and <1 lights gate 3.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Weekly RSI-14 | 41.92 Wilder RSI-14 | ✅ AVAILABLE | HIGH | week starting 2026-08-10 | Completed weekly closes only; 41.92 scores momentum 1.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |

**Data gaps:** 3 · **stale inputs:** 1 · **out of scope:** 2

**Data gaps**

- **ledger_eth_custody** — ⚠️ DATA_LIMITED — No exact ETH quantity, basis, PnL, trim or position-sized action may be stated despite a separately reliable replay basis flag.
- **true_lth_supply** — — NOT_COVERED — Gate 8 receives no credit; reserve decline alone earns only the half holder leg.
- **cross_exchange_market_flow** — ⚠️ DATA_LIMITED — CVD/OI are context from Binance fallback, not a scored leg or gate.

**Stale inputs**

- Ledger holdings_as_of is 2026-07-05, disclosed as audit metadata under event-driven validity; structural custody mismatch, not elapsed time, limits the ETH claim.

**Out of scope**

- No exchange order is placed or cancelled by this report.
- The published liquidation figure is a reported CoinGlass snapshot, not an independently reconstructed percentile series.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Capitulation | 1 | 3 | Mechanical component |
| Holder | 1.5 | 3 | Mechanical component |
| Momentum | 1 | 4 | Mechanical component |
| Sentiment | 0 | 5 | Mechanical component |
| Valuation | 4 | 5 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 7 | Legs plus penalties |
| Raw score | 7 | Mechanical plus discretion (-0.5) |
| Adjusted score | **7/20** | Decision score |
| Rounding | half-down | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Attainable ceiling | 20 | No structural score leg is pinned unavailable. |
| Line states | — | All phase score lines are evaluated independently. |

### Confirmation gates — 4/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | DARK [V] — F&G <=15 streak is 0; relight after seven daily prints <=15. |
| 2 | • NOT PASSED | DARK [V] — completed weekly RSI 41.92; relight below 30. |
| 3 | ✅ PASSED | LIT [V] — MVRV-Z 0.288 <1. |
| 4 | • NOT PASSED | DARK [V] — trailing-month ETF flow +$971.6M, not an outflow >=2% of AUM. |
| 5 | • N/A | N/A [T] — proof-of-stake ETH has no Hash Ribbon gate. |
| 6 | ✅ PASSED | LIT [T] — spot is 1.98% below the 200-week SMA, within +/-8%. |
| 7 | ✅ PASSED | LIT [V] — Aug-21 ETH liquidations about $206.88M inside a historic market-wide short squeeze; intensity passes, direction warns against chasing. |
| 8 | • NOT PASSED | DARK [V] — reserves decline but true LTH supply is provider-gated; both conditions are required for the gate. |
| 9 | ✅ PASSED | LIT [T] — easing real yield/DXY plus regulatory/protocol catalysts; Brent/equity weakness keeps it moderate. |

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
| Rally | 29% | $2,700 | $3,000 | $2,850 | ETF inflows and spot demand extend the structural repair. |
| Range | 36% | $2,250 | $2,700 | $2,475 | Consolidation around the 200-week mean after the squeeze. |
| Retest | 25% | $2,000 | $2,250 | $2,125 | Daily RSI and record OI mean-revert toward the 200-day area. |
| Bear | 10% | $1,700 | $2,000 | $1,850 | Macro/oil shock or failed 200-week reclaim resumes weakness. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $2,433.75 |
| EV versus spot | -0.19% |

> D4 29/36/25/10 follows a realized trend-repair event versus the score-7 baseline. Active downtrend NO. Prior -4.24% EV was contradicted by a +29.24% spot move; this becomes the 19th negative-sign report and EV remains corroborative-only. Collar ON because |EV|<2% and mechanical score 7.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 100% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 10% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1A-ETH-20260822-0346 | Adjusted 7<8; gates 4/8 and V=2 meet, but the score line fails. Carried planning zone 1750-1880; no resting order is authorized. |
| 1B | 15% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1B-ETH-20260822-0346 | Adjusted 7<11 and gate floor 4<5; carried planning zone 1650-1750. |
| 2 | 30% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P2-ETH-20260822-0346 | Adjusted 7<15 and gates 4<6; carried planning zone 1450-1600. |
| 3 | 45% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P3-ETH-20260822-0346 | Mechanical 7<17 and gates 4<7; no analyst channel may unlock Phase 3. |

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
| Reason | Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap. |
| Status | • UNEXPLAINED |

### Cost basis

| Field | Value |
| --- | --- |
| Avg cost | — |
| Reason | Replay basis flag is reliable, but the custody defect prohibits quoting it as the basis of an exact held quantity. |
| Reliable | Yes |
| Total cost | — |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Note | No confirmed ETH quantity can be mapped to an FK phase. |
| Status | ❔ UNKNOWN |

### Position P&L

| Field | Value |
| --- | --- |
| Realized | — |
| Reason | Unexplained custody mismatch prohibits a position-level PnL claim. |
| Status | ⚠️ DATA_LIMITED |
| Unrealized | — |

> **Position reconciliation:** Prior report used an EXPIRED cold-start planning state. The newest ledger is now FRESH under event-driven validity, but ETH custody is UNEXPLAINED; no position figure in either direction is reported. The ledger wins over prior narration, while Rule 4 supplies only the 0%-deployed deployment plan.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| ⚠️ DATA_LIMITED | yes | **NO_POSITION_CLAIM** — Custody mismatch blocks quantity, trim/exit, stop attachment and position-sized action. The report proceeds with a no-add market decision. |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Dry-powder yield | 3.71% |
| Note | Cash has measurable T-bill opportunity value. |
| Status | ✅ AVAILABLE |
| Veto | No |

### Concentration

| Field | Value |
| --- | --- |
| Note | No addition; current ETH weight cannot be computed from a refused quantity claim. |
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
| Catastrophic | 1300 |
| Coherence | Yes |
| Compound | 1350 AND mechanical<12 |
| Deepest zone floor | 1450 |
| Note | Prospective only; catastrophic is strictly below the deepest carried floor. No live stop is armed because position evidence is defective. |
| Status | 🔒 LOCKED |

### Time stops

| Field | Value |
| --- | --- |
| Note | No newly authorized tranche. |
| Status | — NOT_APPLICABLE |

## 7. Analyst rationale

**Summary:** WAIT / NO ADD. ETH FK is 7/20 with 4/8 gates; the score is one point below Phase 1A. The 200-week reclaim and ETF demand are constructive, but daily RSI 79.57 and record OI make this a squeeze chase, not a fallen knife. Position controls are data-limited by an unexplained ledger mismatch.

**Bull case:** Five positive ETF sessions, +$692.6M weekly inflows, positive spot CVD, reserve decline and price inside 2% of the 200-week SMA show genuine repair. IF ETH holds the 200-week area while OI cools and ETH/BTC reclaims 0.032, THEN Range/Rally remains the dominant terminal cluster.

**Bear case:** Daily RSI 79.57, OI at the Binance 90-day high after +38.2% in the flow window, positive funding and ETH/BTC still below 0.032 create chase risk. IF price loses the 200-week/200-day area with ETF and spot-CVD reversal, THEN Retest probability rises sharply.

**Rationale:** SCORE — legs sum 7.5: 0/1/4/1/1.5. Mechanical half-down 7. D1 -0.5 gives raw 7.0 and adjusted 7. The term is freshly re-argued from two independent unscored families: ETH/BTC below 0.032 and leverage/OI at a 90-day high after a 38.2% build. It is not load-bearing for the rounded score.
> GATES — [3,6,7,9] pass, 4/8 with V=2. Phase 1A meets gates but fails score 7<8, so D2 is unavailable. Deeper phases and Override fail score.
> EV — 29/36/25/10 produces 2433.75, -0.19% versus spot. Active downtrend NO; realized trend repair moves probability toward Rally/Range. Collar ON and the 19-report negative-EV series stays corroborative-only.
> POSITION RECONCILIATION — FRESH event-driven snapshot, but UNEXPLAINED custody prohibits any ETH quantity, cost, PnL, trim or stop claim. Prior cold-start narration is superseded; the 0%-deployed plan is not an account-balance fact.
> ACTIONS — 1) Do not add after the squeeze. 2) Preserve the carried 1750-1880, 1650-1750 and 1450-1600 zones as planning references only. 3) Repair ledger custody. 4) Rerun after Aug-26 PCE or a score/gate change. 5) Produce the owed standalone ETH Flying Rocket report. Cash is a position; patience is alpha.

**Primary action:** **WAIT_NO_ADD** — Adjusted score 7<8 despite sufficient P1A gates; buying a leveraged squeeze offers poor FK asymmetry.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Deep value override | Evaluated and not applicable: no confirmed prior deployed tranche and mechanical 7<15. |
| Tier1 calendar | Aug 24-28: PCE/GDP second estimate Aug 26 08:30 ET; no NFP, CPI or FOMC decision. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-22 | D1 | — | — | — | ETH/BTC daily close >0.032 plus OI cooling while spot CVD remains positive. | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | flying_rocket · 3/20 · 0 gates | Same-timestamp FR companion: euphoria 2, momentum 0, valuation 0, distribution 0, vulnerability 1, no penalty; channel NONE because spot is above the falling 200-day MA. Cycle cap is structurally 8. Standalone FR report owed: TRUE because ETH liquidations exceeded $100M; fired_on 2026-08-22, reports_outstanding 1. |
| Cross-validation | ✅ CONSISTENT | FK 7 versus FR 3; structurally cap-bound | Neither score is >=12. ETH is >20% below the one-year high, so the joint >=12 inconsistency is also unfalsifiable under the FR phase-cycle cap. Re-examination found no conflict. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| PCE and GDP second estimate | ✅ AVAILABLE | 2026-08-26 08:30 ET; rerun after the official release. |
| Phase 1A | ✅ AVAILABLE | Adjusted score >=8 while at least 3 gates and V>=2 remain live; then reassess the carried 1750-1880 zone. |
| ETH/BTC and leverage | ✅ AVAILABLE | ETH/BTC daily close above 0.032 plus OI cooling while spot CVD stays positive retires the negative D1 headwind; it does not itself unlock a phase. |
| Ledger repair | ⚠️ DATA_LIMITED | Reconcile custody before any position-level action. |
| Standalone Flying Rocket | ✅ AVAILABLE | Produce a fresh ETH FR report because ETH liquidations exceeded the $100M tripwire. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-21 | Crypto short squeeze | ✅ AVAILABLE | About $206.88M in ETH liquidations occurred inside a historic squeeze; it lights intensity gate 7 but argues against chasing a long entry. |
| 2026-08-21 | US spot ETH ETF inflow streak | ✅ AVAILABLE | Five green sessions and +$692.6M for the week confirm institutional demand and keep ETF capitulation gate 4 dark. |
| 2026-08-17 | Ethereum Plataberget testnet update | ✅ AVAILABLE | Protocol-development catalyst is constructive context, not a scored leg or deployment unlock. |
| 2026-08-26T08:30:00-04:00 | BEA PCE and GDP second estimate | ✅ AVAILABLE | Only Tier-1 release in the next five trading sessions; no NFP, CPI or FOMC decision is scheduled in-window. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| Trend repair can persist without an FK entry | A completed weekly loss of the 200-week/200-day area with negative spot CVD and ETF outflows would falsify repair; a score/gate unlock would falsify WAIT. | ✅ AVAILABLE |
| D1=-0.5 | Retire if ETH/BTC closes above 0.032 and OI cools away from its 90-day high while spot CVD remains positive. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.adjusted | 10 | 7 | Fear disappeared (sentiment 2 to 0), valuation softened (5 to 4), holder evidence fell from both tests to one (3 to 1.5), and liquidation added 1. |
| Gates.passed | 3,8 | 3,6,7,9 | Price reached the 200-week band; liquidation intensity and macro/regulatory catalyst relit, while strict two-part holder gate 8 went dark. |
| Position.status | EXPIRED cold-start | DATA_LIMITED | Event-driven snapshot is FRESH, but custody mismatch requires refusing the ETH position claim. |
| Companion framework.owed | No | Yes | Reported ETH liquidations exceeded the $100M standalone-report tripwire. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |
| Mining stress gate | BTC Hash Ribbon | N/A for proof-of-stake ETH | Gate 5 is excluded from the ETH denominator rather than replaced by a proxy. |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| bea | BEA release schedule | official calendar | 2026-08-22 | 2026-08-22T07:30:00Z | —<br>[Open source](https://www.bea.gov/news/schedule/full) |
| bls | BLS August 2026 schedule | official calendar | 2026-08-22 | 2026-08-22T07:30:00Z | —<br>[Open source](https://www.bls.gov/schedule/2026/08_sched.htm) |
| cftc | CFTC Innovation Advisory Committee release | regulator | 2026-08-20 | 2026-08-22T07:24:00Z | —<br>[Open source](https://www.cftc.gov/PressRoom/PressReleases/9279-26) |
| correlation | Yahoo ETH-USD and S&P 500 closes; deterministic correlation | computed market data | 2026-08-21 | 2026-08-22T07:41:40.616Z | —<br>[Open source](https://query1.finance.yahoo.com/) |
| ethereum | Ethereum Foundation archive | protocol primary | 2026-08-17 | 2026-08-22T07:28:00Z | —<br>[Open source](https://blog.ethereum.org/archive) |
| farside | Farside Investors ETH ETF flow table | issuer-flow aggregator | 2026-08-21 | 2026-08-22T07:26:00Z | —<br>[Open source](https://farside.co.uk/eth/) |
| fed | Federal Reserve FOMC calendar | official calendar | 2026-08-22 | 2026-08-22T07:30:00Z | —<br>[Open source](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm) |
| ledger | Personal-accounting position snapshot | ledger | 2026-08-15T09:30:02.628Z | 2026-08-22T07:35:00Z | Event-driven FRESH; exact ETH claim refused because custody is UNEXPLAINED.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| liquidations | CoinGlass liquidation data reported by Yahoo Finance | news/data relay | 2026-08-21 | 2026-08-22T07:22:00Z | —<br>[Open source](https://finance.yahoo.com/markets/crypto/articles/crypto-bears-burned-short-liquidations-055534539.html) |
| snapshot | Deterministic BTC/ETH/macro snapshot | computed | 2026-08-22T07:38:55.994Z | 2026-08-22T07:38:55.994Z | Cross-venue spot, completed-week RSI, 200-week SMA, funding, on-chain and macro blocks retain source metadata.<br>[Open source](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| sosovalue | SoSoValue ETH ETF net-assets context | fund-data aggregator | 2026-08-20 | 2026-08-22T07:27:00Z | —<br>[Open source](https://www.sosovalue.com/assets/etf/us-eth-spot) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-22T07:38:55.994Z |
| Generated at | 2026-08-22T07:48:00Z |
| Report at | 2026-08-22T07:46:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | eth_fallen_knives_20260822_0346 |
| Report filename | eth_fallen_knives_20260822_0346.json |
| Run ID | 20260822-0738-5f8835b8 |
| Snapshot ID | sha256:5f8835b8f5dc5bc20aed2c97b3ef234bef67d5550447c629255090ad417e9f30 |
| Prior report | eth_fallen_knives_20260813_1744 |
| Prior report hash | c2120eaa6d6d31f2e21d9c8f7addd2c4b641a92a6dae240f7ed7fcdcb7fe7561 |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:334e81012d5853a87a6b5c8e422382c8e3be0e1532c62e5f57b8f741be7453ae |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | 🔒 LOCKED | FK-P1A-ETH-20260822-0346 | crypto |
| 1B | 🔒 LOCKED | FK-P1B-ETH-20260822-0346 | crypto |
| 2 | 🔒 LOCKED | FK-P2-ETH-20260822-0346 | crypto |
| 3 | 🔒 LOCKED | FK-P3-ETH-20260822-0346 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FK-P1A-ETH-20260822-0346, FK-P1B-ETH-20260822-0346, FK-P2-ETH-20260822-0346, FK-P3-ETH-20260822-0346

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":7,"field":"score.adjusted","previous":10,"reason":"Fear disappeared (sentiment 2 to 0), valuation softened (5 to 4), holder evidence fell from both tests to one (3 to 1.5), and liquidation added 1."},{"current":"3,6,7,9","field":"gates.passed","previous":"3,8","reason":"Price reached the 200-week band; liquidation intensity and macro/regulatory catalyst relit, while strict two-part holder gate 8 went dark."},{"current":"DATA_LIMITED","field":"position.status","previous":"EXPIRED cold-start","reason":"Event-driven snapshot is FRESH, but custody mismatch requires refusing the ETH position claim."},{"current":true,"field":"companion_framework.owed","previous":false,"reason":"Reported ETH liquidations exceeded the $100M standalone-report tripwire."}],"companion_framework":{"framework":"flying_rocket","gates":0,"rationale":"Same-timestamp FR companion: euphoria 2, momentum 0, valuation 0, distribution 0, vulnerability 1, no penalty; channel NONE because spot is above the falling 200-day MA. Cycle cap is structurally 8. Standalone FR report owed: TRUE because ETH liquidations exceeded $100M; fired_on 2026-08-22, reports_outstanding 1.","score":3,"status":"AVAILABLE"},"cross_validation":{"rationale":"Neither score is >=12. ETH is >20% below the one-year high, so the joint >=12 inconsistency is also unfalsifiable under the FR phase-cycle cap. Re-examination found no conflict.","relationship":"FK 7 versus FR 3; structurally cap-bound","status":"CONSISTENT"},"data_gaps":[{"field":"ledger_eth_custody","impact":"No exact ETH quantity, basis, PnL, trim or position-sized action may be stated despite a separately reliable replay basis flag.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"true_lth_supply","impact":"Gate 8 receives no credit; reserve decline alone earns only the half holder leg.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"cross_exchange_market_flow","impact":"CVD/OI are context from Binance fallback, not a scored leg or gate.","source_ids":["snapshot"],"status":"DATA_LIMITED"}],"deployment":{"deployed_pct":"0","dry_pct":"100","throttle_released":false,"tranches":[{"channel":null,"deployed":false,"entry_price":null,"pct":"10","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 7<8; gates 4/8 and V=2 meet, but the score line fails. Carried planning zone 1750-1880; no resting order is authorized.","state":"LOCKED","stop":null,"tag":"FK-P1A-ETH-20260822-0346","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"15","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 7<11 and gate floor 4<5; carried planning zone 1650-1750.","state":"LOCKED","stop":null,"tag":"FK-P1B-ETH-20260822-0346","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"30","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 7<15 and gates 4<6; carried planning zone 1450-1600.","state":"LOCKED","stop":null,"tag":"FK-P2-ETH-20260822-0346","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"45","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Mechanical 7<17 and gates 4<7; no analyst channel may unlock Phase 3.","state":"LOCKED","stop":null,"tag":"FK-P3-ETH-20260822-0346","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"D4 29/36/25/10 follows a realized trend-repair event versus the score-7 baseline. Active downtrend NO. Prior -4.24% EV was contradicted by a +29.24% spot move; this becomes the 19th negative-sign report and EV remains corroborative-only. Collar ON because |EV|<2% and mechanical score 7.","probability_sum":1,"scenarios":[{"high":"3000","low":"2700","mid":"2850","name":"Rally","probability":0.29,"rationale":"ETF inflows and spot demand extend the structural repair."},{"high":"2700","low":"2250","mid":"2475","name":"Range","probability":0.36,"rationale":"Consolidation around the 200-week mean after the squeeze."},{"high":"2250","low":"2000","mid":"2125","name":"Retest","probability":0.25,"rationale":"Daily RSI and record OI mean-revert toward the 200-day area."},{"high":"2000","low":"1700","mid":"1850","name":"Bear","probability":0.1,"rationale":"Macro/oil shock or failed 200-week reclaim resumes weakness."}],"stated_ev":"2433.75","vs_spot_pct":"-0.19"},"events":[{"as_of":"2026-08-21","impact":"About $206.88M in ETH liquidations occurred inside a historic squeeze; it lights intensity gate 7 but argues against chasing a long entry.","name":"Crypto short squeeze","status":"AVAILABLE"},{"as_of":"2026-08-21","impact":"Five green sessions and +$692.6M for the week confirm institutional demand and keep ETF capitulation gate 4 dark.","name":"US spot ETH ETF inflow streak","status":"AVAILABLE"},{"as_of":"2026-08-17","impact":"Protocol-development catalyst is constructive context, not a scored leg or deployment unlock.","name":"Ethereum Plataberget testnet update","status":"AVAILABLE"},{"as_of":"2026-08-26T08:30:00-04:00","impact":"Only Tier-1 release in the next five trading sessions; no NFP, CPI or FOMC decision is scheduled in-window.","name":"BEA PCE and GDP second estimate","status":"AVAILABLE"}],"evidence":{"correlation":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"29 paired daily log returns; surcharge off and the Phase-2 correlation condition passes.","source_ids":["correlation"],"status":"AVAILABLE","unit":"Pearson correlation","value":"0.132117"},"etf_flows":{"as_of":"2026-08-21","confidence":"HIGH","rationale":"Latest day +184.0M, five consecutive positive sessions and +692.6M for Aug 17-21; monthly flow is positive, so gate 4 is off.","source_ids":["farside","sosovalue"],"status":"AVAILABLE","unit":"USD millions trailing month","value":"971.6"},"holder":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"Reserves declined, earning one of two holder tests and a 1.5 leg; true LTH data is provider-gated, so gate 8 receives no credit.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent exchange-reserve change 30d","value":"-1.263"},"liquidations":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"CoinGlass-cited ETH losses during a historic market-wide short squeeze; counted as top-decile intensity, not as long-side capitulation direction.","source_ids":["liquidations"],"status":"AVAILABLE","unit":"USD ETH liquidations / 24h","value":"206880000"},"macro":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"Real yield eased 4bp and DXY fell 0.87%; regulatory catalysts support gate 9, while Brent +6.63% and equities lower keep confidence moderate.","source_ids":["snapshot","cftc"],"status":"AVAILABLE","unit":null,"value":"neutral-positive with oil risk"},"sentiment":{"as_of":"2026-08-22","confidence":"HIGH","rationale":"Alternative.me prints 62, 72 and 71; >50 scores zero and the <=15 streak is zero.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Fear & Greed 3-day average","value":"68.33"},"spot":{"as_of":"2026-08-22T07:38:48Z","confidence":"HIGH","rationale":"Median of synchronized CoinGecko, Binance, Coinbase and Kraken quotes; panel spread 0.034%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"2438.39"},"valuation":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"Coin Metrics reconstruction; <=0.5 scores valuation 4 and <1 lights gate 3.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"MVRV-Z","value":"0.288"},"weekly_rsi":{"as_of":"week starting 2026-08-10","confidence":"HIGH","rationale":"Completed weekly closes only; 41.92 scores momentum 1.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Wilder RSI-14","value":"41.92"}},"falsifiers":[{"claim":"Trend repair can persist without an FK entry","condition":"A completed weekly loss of the 200-week/200-day area with negative spot CVD and ETF outflows would falsify repair; a score/gate unlock would falsify WAIT.","status":"AVAILABLE"},{"claim":"D1=-0.5","condition":"Retire if ETH/BTC closes above 0.032 and OI cools away from its 90-day high while spot CVD remains positive.","status":"AVAILABLE"}],"gates":{"active":8,"measurement_basis":{"1":"DARK [V] — F&G <=15 streak is 0; relight after seven daily prints <=15.","2":"DARK [V] — completed weekly RSI 41.92; relight below 30.","3":"LIT [V] — MVRV-Z 0.288 <1.","4":"DARK [V] — trailing-month ETF flow +$971.6M, not an outflow >=2% of AUM.","5":"N/A [T] — proof-of-stake ETH has no Hash Ribbon gate.","6":"LIT [T] — spot is 1.98% below the 200-week SMA, within +/-8%.","7":"LIT [V] — Aug-21 ETH liquidations about $206.88M inside a historic market-wide short squeeze; intensity passes, direction warns against chasing.","8":"DARK [V] — reserves decline but true LTH supply is provider-gated; both conditions are required for the gate.","9":"LIT [T] — easing real yield/DXY plus regulatory/protocol catalysts; Brent/equity weakness keeps it moderate.","binding_axis":"P1A has 4/8 gates and V=2 but adjusted score is one point short; every deeper phase is score-bound. D2 unavailable because the score line fails."},"na":[5],"passed":[3,6,7,9],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":7}},"identity":{"asset":"ETH","date":"2026-08-22","filename":"eth_fallen_knives_20260822_0346.json","framework":"fallen_knives","local_time":"03:46","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-08-24","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"4946.05"},"drawdown_pct":{"as_of":"2026-08-22","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"50.70"},"metrics":{"adr5":{"as_of":"2026-08-22","note":"Five most recent full crypto sessions.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"184.68"},"dry_powder_yield":{"as_of":"2026-08-21","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"3.71"},"eth_btc":{"as_of":"2026-08-22T07:38:48Z","note":"Below the 0.032 D1 falsifier.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"ETH/BTC ratio","value":"0.03150"},"funding":{"as_of":"2026-08-22T04:00:00Z","note":"Positive; no three-interval negative streak.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"5.73"},"futures_cvd_3d":{"as_of":"2026-08-22T00:00:00Z","note":"Binance single-venue fallback; positive.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"1932341508.57"},"open_interest_change_24h":{"as_of":"2026-08-22T04:00:00Z","note":"OI is at its Binance 90-day high after +38.207% over the fetched window.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"10.367"},"sma200w":{"as_of":"week starting 2026-08-10","note":"Spot is 1.98% below; gate 6 lit.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"2487.57"},"spot_cvd_3d":{"as_of":"2026-08-22T00:00:00Z","note":"Binance single-venue fallback; positive.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"307231925.69"}},"reconciliation":{"method":"median of four synchronized venue/aggregator quotes","note":"Yahoo frozen daily close excluded from the median.","quotes":[{"source_ids":["snapshot"],"value":"2438.09"},{"source_ids":["snapshot"],"value":"2438.39"},{"source_ids":["snapshot"],"value":"2438.43"},{"source_ids":["snapshot"],"value":"2438.84"}],"spread_pct":"0.034","status":"AVAILABLE"},"regime":{"active_downtrend":false,"daily_rsi14":"79.57","label":"violent trend repair at the 200-week mean; not a fallen knife","ma200":"2005.47","ma200_falling":true,"ma200_slope_20_pct":"-4.28","price_below_ma200":false,"trend_residual":"spot/futures CVD and OI rose together; 200-week proximity helps structure but leverage makes chase risk acute"},"spot":{"as_of":"2026-08-22T07:38:48Z","note":"Four-source synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"2438.39"}},"narrative":{"arguments":{"deep_value_override":"Evaluated and not applicable: no confirmed prior deployed tranche and mechanical 7<15.","discretion_ledger":[{"channel":"D1","date":"2026-08-22","falsifier":"ETH/BTC daily close >0.032 plus OI cooling while spot CVD remains positive.","load_bearing":false,"outcome":"WAIT_NO_ADD","reason":"ETH/BTC remains below 0.032 and leverage/OI is at a 90-day high after an extreme build.","term":"-0.5"}],"stop_migration_ledger":[],"tier1_calendar":"Aug 24-28: PCE/GDP second estimate Aug 26 08:30 ET; no NFP, CPI or FOMC decision."},"bear_case":"Daily RSI 79.57, OI at the Binance 90-day high after +38.2% in the flow window, positive funding and ETH/BTC still below 0.032 create chase risk. IF price loses the 200-week/200-day area with ETF and spot-CVD reversal, THEN Retest probability rises sharply.","bull_case":"Five positive ETF sessions, +$692.6M weekly inflows, positive spot CVD, reserve decline and price inside 2% of the 200-week SMA show genuine repair. IF ETH holds the 200-week area while OI cools and ETH/BTC reclaims 0.032, THEN Range/Rally remains the dominant terminal cluster.","primary_action":{"rationale":"Adjusted score 7<8 despite sufficient P1A gates; buying a leveraged squeeze offers poor FK asymmetry.","status":"AVAILABLE","value":"WAIT_NO_ADD"},"rationale":"SCORE — legs sum 7.5: 0/1/4/1/1.5. Mechanical half-down 7. D1 -0.5 gives raw 7.0 and adjusted 7. The term is freshly re-argued from two independent unscored families: ETH/BTC below 0.032 and leverage/OI at a 90-day high after a 38.2% build. It is not load-bearing for the rounded score.\nGATES — [3,6,7,9] pass, 4/8 with V=2. Phase 1A meets gates but fails score 7<8, so D2 is unavailable. Deeper phases and Override fail score.\nEV — 29/36/25/10 produces 2433.75, -0.19% versus spot. Active downtrend NO; realized trend repair moves probability toward Rally/Range. Collar ON and the 19-report negative-EV series stays corroborative-only.\nPOSITION RECONCILIATION — FRESH event-driven snapshot, but UNEXPLAINED custody prohibits any ETH quantity, cost, PnL, trim or stop claim. Prior cold-start narration is superseded; the 0%-deployed plan is not an account-balance fact.\nACTIONS — 1) Do not add after the squeeze. 2) Preserve the carried 1750-1880, 1650-1750 and 1450-1600 zones as planning references only. 3) Repair ledger custody. 4) Rerun after Aug-26 PCE or a score/gate change. 5) Produce the owed standalone ETH Flying Rocket report. Cash is a position; patience is alpha.","summary":"WAIT / NO ADD. ETH FK is 7/20 with 4/8 gates; the score is one point below Phase 1A. The 200-week reclaim and ETF demand are constructive, but daily RSI 79.57 and record OI make this a squeeze chase, not a fallen knife. Position controls are data-limited by an unexplained ledger mismatch."},"out_of_scope":["No exchange order is placed or cancelled by this report.","The published liquidation figure is a reported CoinGlass snapshot, not an independently reconstructed percentile series."],"position":{"asset":"ETH","attribution":{"active_tags":[],"note":"No confirmed ETH quantity can be mapped to an FK phase.","status":"UNKNOWN"},"basis":{"avg_cost":null,"reason":"Replay basis flag is reliable, but the custody defect prohibits quoting it as the basis of an exact held quantity.","reliable":true,"total_cost":null},"custody":{"reason":"Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap.","status":"UNEXPLAINED"},"dry_powder":"10741.5780","futures":[],"pnl":{"realized":null,"reason":"Unexplained custody mismatch prohibits a position-level PnL claim.","status":"DATA_LIMITED","unrealized":null},"quantity":null,"reconciliation":"Prior report used an EXPIRED cold-start planning state. The newest ledger is now FRESH under event-driven validity, but ETH custody is UNEXPLAINED; no position figure in either direction is reported. The ledger wins over prior narration, while Rule 4 supplies only the 0%-deployed deployment plan.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"Custody mismatch blocks quantity, trim/exit, stop attachment and position-sized action. The report proceeds with a no-add market decision.","status":"DATA_LIMITED","value":"NO_POSITION_CLAIM"},"required":true,"status":"DATA_LIMITED"},"report_id":"eth_fallen_knives_20260822_0346","risk_controls":{"carry":{"dry_powder_yield_pct":"3.71","note":"Cash has measurable T-bill opportunity value.","status":"AVAILABLE","veto":false},"concentration":{"note":"No addition; current ETH weight cannot be computed from a refused quantity claim.","planned_pct":"0","status":"DATA_LIMITED"},"ratchet":{"note":"No confirmed open tranche or auditable live stop; D6 migration ledger empty.","parameters_changed":false,"status":"NOT_APPLICABLE"},"stops":{"catastrophic":"1300","coherence":true,"compound":"1350 AND mechanical<12","deepest_zone_floor":"1450","note":"Prospective only; catastrophic is strictly below the deepest carried floor. No live stop is armed because position evidence is defective.","status":"LOCKED"},"time_stops":{"note":"No newly authorized tranche.","status":"NOT_APPLICABLE"}},"run":{"prior_report_id":"eth_fallen_knives_20260813_1744","prior_report_sha256":"c2120eaa6d6d31f2e21d9c8f7addd2c4b641a92a6dae240f7ed7fcdcb7fe7561","run_id":"20260822-0738-5f8835b8","snapshot_id":"sha256:5f8835b8f5dc5bc20aed2c97b3ef234bef67d5550447c629255090ad417e9f30","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:334e81012d5853a87a6b5c8e422382c8e3be0e1532c62e5f57b8f741be7453ae","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":7,"caps":[{"field":"attainable_ceiling","reason":"No structural score leg is pinned unavailable.","value":20},{"field":"line_states","reason":"All phase score lines are evaluated independently.","value":["P1A>=8 LIVE-FALSE","P1B>=11 LIVE-FALSE","P2>=15 LIVE-FALSE","P3 mechanical>=17 LIVE-FALSE","compound mechanical<12 VACUOUS-PERMISSIVE but price-gated"]}],"discretion":-0.5,"legs":{"capitulation":1,"holder":1.5,"momentum":1,"sentiment":0,"valuation":4},"mechanical":7,"penalties":[],"raw":7,"rounding":"half-down"},"sources":{"bea":{"as_of":"2026-08-22","kind":"official calendar","name":"BEA release schedule","retrieved_at":"2026-08-22T07:30:00Z","url":"https://www.bea.gov/news/schedule/full"},"bls":{"as_of":"2026-08-22","kind":"official calendar","name":"BLS August 2026 schedule","retrieved_at":"2026-08-22T07:30:00Z","url":"https://www.bls.gov/schedule/2026/08_sched.htm"},"cftc":{"as_of":"2026-08-20","kind":"regulator","name":"CFTC Innovation Advisory Committee release","retrieved_at":"2026-08-22T07:24:00Z","url":"https://www.cftc.gov/PressRoom/PressReleases/9279-26"},"correlation":{"as_of":"2026-08-21","kind":"computed market data","name":"Yahoo ETH-USD and S&P 500 closes; deterministic correlation","retrieved_at":"2026-08-22T07:41:40.616Z","url":"https://query1.finance.yahoo.com/"},"ethereum":{"as_of":"2026-08-17","kind":"protocol primary","name":"Ethereum Foundation archive","retrieved_at":"2026-08-22T07:28:00Z","url":"https://blog.ethereum.org/archive"},"farside":{"as_of":"2026-08-21","kind":"issuer-flow aggregator","name":"Farside Investors ETH ETF flow table","retrieved_at":"2026-08-22T07:26:00Z","url":"https://farside.co.uk/eth/"},"fed":{"as_of":"2026-08-22","kind":"official calendar","name":"Federal Reserve FOMC calendar","retrieved_at":"2026-08-22T07:30:00Z","url":"https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"ledger","name":"Personal-accounting position snapshot","note":"Event-driven FRESH; exact ETH claim refused because custody is UNEXPLAINED.","retrieved_at":"2026-08-22T07:35:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"liquidations":{"as_of":"2026-08-21","kind":"news/data relay","name":"CoinGlass liquidation data reported by Yahoo Finance","retrieved_at":"2026-08-22T07:22:00Z","url":"https://finance.yahoo.com/markets/crypto/articles/crypto-bears-burned-short-liquidations-055534539.html"},"snapshot":{"as_of":"2026-08-22T07:38:55.994Z","kind":"computed","name":"Deterministic BTC/ETH/macro snapshot","note":"Cross-venue spot, completed-week RSI, 200-week SMA, funding, on-chain and macro blocks retain source metadata.","retrieved_at":"2026-08-22T07:38:55.994Z","url":"data/runs/20260822-0738-5f8835b8/snapshot.json"},"sosovalue":{"as_of":"2026-08-20","kind":"fund-data aggregator","name":"SoSoValue ETH ETF net-assets context","retrieved_at":"2026-08-22T07:27:00Z","url":"https://www.sosovalue.com/assets/etf/us-eth-spot"}},"stale_inputs":["Ledger holdings_as_of is 2026-07-05, disclosed as audit metadata under event-driven validity; structural custody mismatch, not elapsed time, limits the ETH claim."],"substitutions":[{"field":"mining_stress_gate","original":"BTC Hash Ribbon","rationale":"Gate 5 is excluded from the ETH denominator rather than replaced by a proxy.","substitute":"N/A for proof-of-stake ETH"}],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FK-P1A-ETH-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FK-P1B-ETH-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FK-P2-ETH-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FK-P3-ETH-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FK-P1A-ETH-20260822-0346","FK-P1B-ETH-20260822-0346","FK-P2-ETH-20260822-0346","FK-P3-ETH-20260822-0346"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-22T07:38:55.994Z","generated_at":"2026-08-22T07:48:00Z","report_at":"2026-08-22T07:46:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Phase 1A score is one point short; no discretionary channel can bridge it.","status":"AVAILABLE","value":"WAIT_NO_ADD"},"statement":"ETH is not an accumulation entry: adjusted 7/20, gates 4/8, and every phase remains locked. Do not add; reconcile the ledger and let leverage cool.","status":"WAIT"},"watchlist":[{"item":"PCE and GDP second estimate","status":"AVAILABLE","trigger":"2026-08-26 08:30 ET; rerun after the official release."},{"item":"Phase 1A","status":"AVAILABLE","trigger":"Adjusted score >=8 while at least 3 gates and V>=2 remain live; then reassess the carried 1750-1880 zone."},{"item":"ETH/BTC and leverage","status":"AVAILABLE","trigger":"ETH/BTC daily close above 0.032 plus OI cooling while spot CVD stays positive retires the negative D1 headwind; it does not itself unlock a phase."},{"item":"Ledger repair","status":"DATA_LIMITED","trigger":"Reconcile custody before any position-level action."},{"item":"Standalone Flying Rocket","status":"AVAILABLE","trigger":"Produce a fresh ETH FR report because ETH liquidations exceeded the $100M tripwire."}]}
```
