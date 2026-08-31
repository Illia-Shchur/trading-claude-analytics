# BTC — Fallen Knives — 2026-08-22 03:46

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | BTC · Fallen Knives |
| Report time | 2026-08-22 03:46 (America/New_York) |
| Verdict | • WAIT — BTC is not an accumulation entry: adjusted 6/20, gates 3/9, and every phase remains locked. Do not add; reconcile the ledger and let leverage cool. |
| Adjusted score | **6/20** (mechanical 6, raw 6) |
| Confirmation gates | 3/9 active passed |
| Position | ⚠️ DATA_LIMITED · quantity unavailable BTC |
| Deployment | 0% deployed · 100% dry |
| Primary action | **WAIT_NO_ADD** — Phase 1A score is two points short; no discretionary channel can bridge it. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $77,409.69 | ✅ AVAILABLE | — | 2026-08-22T07:38:48Z | Four-source synchronized median.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| All-time high | $126,080 | ✅ AVAILABLE | — | 2025-10-06 | —<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Drawdown from ATH | 38.60% | ✅ AVAILABLE | — | 2026-08-22 | —<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| ADR-5 | $3,919.36 | ✅ AVAILABLE | — | 2026-08-22 | Five most recent full crypto sessions.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Dry-powder yield | 3.71% | ✅ AVAILABLE | — | 2026-08-21 | —<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Funding | 6.73% | ✅ AVAILABLE | — | 2026-08-22T04:00:00Z | Positive; no negative interval streak.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Futures cvd 3d | $4,327,166,210.91 | ✅ AVAILABLE | — | 2026-08-22T00:00:00Z | Binance single-venue fallback; positive and much larger than spot.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Open interest change 24h | 3.281% | ✅ AVAILABLE | — | 2026-08-22T04:00:00Z | OI is at its Binance 90-day high; leverage is rebuilding.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| 200-week SMA | $63,994.17 | ✅ AVAILABLE | — | week starting 2026-08-10 | Spot is 20.96% above; gate 6 off.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Spot cvd 3d | $321,577,158.57 | ✅ AVAILABLE | — | 2026-08-22T00:00:00Z | Binance single-venue fallback; positive.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |

**Regime:** • violent trend repair after short squeeze; not a fallen knife — Active downtrend: No; Daily rsi14: 81.67; Label: violent trend repair after short squeeze; not a fallen knife; Ma200: 68982.51; Ma200 falling: Yes; Ma200 slope 20 pct: -3.01%; Price below ma200: No; Trend residual: spot/futures CVD and OI rose together; chase risk offsets repaired spot demand

### Spot reconciliation

**✅ AVAILABLE** — median of four synchronized venue/aggregator quotes; spread 0.026%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| — | $77,419 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| — | $77,410.08 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| — | $77,399.07 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| — | $77,409.30 | — | [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |

> Yahoo frozen daily close excluded from the median.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Correlation | 0.113192 Pearson correlation | ✅ AVAILABLE | MEDIUM | 2026-08-21 | 29 paired daily log returns; surcharge off and the Phase-2 correlation condition passes.<br>Sources: [correlation](https://query1.finance.yahoo.com/) |
| Etf flows | 1,940.3 USD millions trailing month | ✅ AVAILABLE | HIGH | 2026-08-21 | Latest day +307.5M, five consecutive positive sessions and +1917.8M for Aug 17-21; monthly flow is positive, so gate 4 is off.<br>Sources: [farside](https://farside.co.uk/btc/), [sosovalue](https://www.sosovalue.com/assets/etf/us-btc-spot) |
| Holder | 2.309% | ✅ AVAILABLE | MEDIUM | 2026-08-21 | Reserves rose; true LTH data is provider-gated, so holder leg and gate 8 receive no credit.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Liquidations | 1,060,000,000 USD short liquidations / 24h | ✅ AVAILABLE | MEDIUM | 2026-08-21 | CoinGlass-cited market-wide short liquidations were a historic squeeze; counted as top-decile liquidation intensity but not as long-side capitulation direction.<br>Sources: [liquidations](https://finance.yahoo.com/markets/crypto/articles/crypto-bears-burned-short-liquidations-055534539.html) |
| Macro | neutral-positive with oil risk | ✅ AVAILABLE | MEDIUM | 2026-08-21 | Real yield eased 4bp and DXY fell 0.87%; Treasury/regulatory catalysts support gate 9, while Brent +6.63% and equities lower keep confidence moderate.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json), [cftc](https://www.cftc.gov/PressRoom/PressReleases/9279-26) |
| Sentiment | 68.33 Fear & Greed 3-day average | ✅ AVAILABLE | HIGH | 2026-08-22 | Alternative.me prints 62, 72 and 71; >50 scores zero and the <=15 streak is zero.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Spot | $77,409.69 | ✅ AVAILABLE | HIGH | 2026-08-22T07:38:48Z | Median of four synchronized CoinGecko, Binance, Coinbase and Kraken quotes; panel spread 0.026%.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Valuation | 0.841 MVRV-Z | ✅ AVAILABLE | MEDIUM | 2026-08-21 | Coin Metrics reconstruction; <=2 scores valuation 3 and <1 lights gate 3.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| Weekly RSI-14 | 38.80 Wilder RSI-14 | ✅ AVAILABLE | HIGH | week starting 2026-08-10 | Completed weekly closes only; 38.80 scores momentum 2.<br>Sources: [snapshot](data/runs/20260822-0738-5f8835b8/snapshot.json) |

**Data gaps:** 4 · **stale inputs:** 1 · **out of scope:** 2

**Data gaps**

- **ledger_btc_custody_and_basis** — ⚠️ DATA_LIMITED — No BTC quantity, cost basis, PnL, trim or position-sized action may be stated.
- **true_lth_supply** — — NOT_COVERED — Holder gate 8 and the LTH half of the holder leg receive no credit.
- **hash_ribbon** — ❔ UNKNOWN — Gate 5 stays dark; a fresh Hash Ribbon buy/stress confirmation is required.
- **cross_exchange_market_flow** — ⚠️ DATA_LIMITED — CVD/OI are context from Binance fallback, not a scored leg or gate.

**Stale inputs**

- Ledger holdings_as_of is 2026-07-05, disclosed as audit metadata under event-driven validity; structural custody/basis defects, not elapsed time, limit the BTC claim.

**Out of scope**

- No exchange order is placed or cancelled by this report.
- The published liquidation figure is a reported CoinGlass snapshot, not an independently reconstructed percentile series.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Capitulation | 1 | 3 | Mechanical component |
| Holder | 0 | 3 | Mechanical component |
| Momentum | 2 | 4 | Mechanical component |
| Sentiment | 0 | 5 | Mechanical component |
| Valuation | 3 | 5 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 6 | Legs plus penalties |
| Raw score | 6 | Mechanical plus discretion (0) |
| Adjusted score | **6/20** | Decision score |
| Rounding | half-up | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Attainable ceiling | 20 | No structural score leg is pinned unavailable. |
| Line states | — | All phase score lines are evaluated independently. |

### Confirmation gates — 3/9 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | DARK [V] — F&G <=15 streak is 0; relight after seven daily prints <=15. |
| 2 | • NOT PASSED | DARK [V] — completed weekly RSI 38.80; relight below 30. |
| 3 | ✅ PASSED | LIT [V] — MVRV-Z 0.841 <1. |
| 4 | • NOT PASSED | DARK [V] — trailing-month ETF flow +$1.9403B, not an outflow >=2% of AUM. |
| 5 | • NOT PASSED | DARK [T] — no fresh Hash Ribbon confirmation; relight requires a current buy/stress signal. |
| 6 | • NOT PASSED | DARK [T] — spot 20.96% above the 200-week SMA; relight inside +/-8%. |
| 7 | ✅ PASSED | LIT [V] — Aug-21 market-wide short liquidations about $1.06B, a historic/top-decile intensity event; direction is a short squeeze. |
| 8 | • NOT PASSED | DARK [V] — reserves +2.309%/30d and true LTH data provider-gated. |
| 9 | ✅ PASSED | LIT [T] — easing real yield/DXY plus Treasury and regulatory catalysts; Brent/equity weakness keeps it moderate. |

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
| Rally | 30% | $82,000 | $88,000 | $85,000 | ETF inflows and positive spot CVD extend the squeeze. |
| Range | 35% | $73,000 | $82,000 | $77,500 | Post-squeeze consolidation near current spot. |
| Retest | 25% | $68,000 | $73,000 | $70,500 | Daily RSI/OI excess mean-reverts toward the 200-day area. |
| Bear | 10% | $60,000 | $68,000 | $64,000 | Macro/oil shock erases the repaired structure. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $76,650 |
| EV versus spot | -0.98% |

> D4 30/35/25/10 follows a realized trend-repair event versus the score-6 baseline. Active downtrend NO. Prior -2.77% EV was contradicted by a +22.11% spot move; this becomes the 19th negative-sign report and EV remains corroborative-only. Collar ON because |EV|<2% and score 6.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 100% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 10% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1A-BTC-20260822-0346 | Adjusted 6<8; gates 3/9 and V=2 meet, but the score line fails. Carried planning zone 58000-61000; no resting order is authorized. |
| 1B | 15% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1B-BTC-20260822-0346 | Adjusted 6<11 and gate floor 3<5; carried planning zone 54000-58000. |
| 2 | 30% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P2-BTC-20260822-0346 | Adjusted 6<15 and gates 3<6; no authorized zone. |
| 3 | 45% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P3-BTC-20260822-0346 | Mechanical 6<17 and gates 3<7; no analyst channel may unlock Phase 3. |

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

> **Position reconciliation:** Prior report used an EXPIRED cold-start planning state. The newest ledger is now FRESH under event-driven validity, but BTC custody is UNEXPLAINED and basis unreliable; no position figure in either direction is reported. The ledger wins over prior narration, while Rule 4 supplies only the 0%-deployed deployment plan.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| ⚠️ DATA_LIMITED | yes | **NO_POSITION_CLAIM** — Custody and basis defects block quantity, trim/exit, stop attachment and position-sized action. The report proceeds with a no-add market decision. |

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
| Note | Prospective only; catastrophic is strictly below the deepest carried floor. No live stop is armed because position evidence is defective. |
| Status | 🔒 LOCKED |

### Time stops

| Field | Value |
| --- | --- |
| Note | No newly authorized tranche. |
| Status | — NOT_APPLICABLE |

## 7. Analyst rationale

**Summary:** WAIT / NO ADD. BTC FK is 6/20 with 3/9 gates; the score is two points below Phase 1A. Spot demand is real, but this is a leveraged post-squeeze rally, not a fallen knife. Position controls are data-limited by an unexplained ledger mismatch.

**Bull case:** Five positive ETF sessions, +$1.9178B weekly inflows, positive spot CVD, a 20.96% cushion above the 200-week SMA and easing DXY/real yield show genuine repair. IF spot CVD stays positive while OI cools and price holds the 200-day MA, THEN Range/Rally remains the dominant terminal cluster.

**Bear case:** Daily RSI 81.67, OI at the Binance 90-day high, futures CVD far larger than spot CVD, positive funding and a 24% one-week flow-window rally create chase risk. IF price loses the 200-day MA with ETF/spot-CVD reversal, THEN Retest probability rises sharply.

**Rationale:** SCORE — 0/2/3/1/0 gives mechanical and adjusted 6. D1=0: a negative term was considered and declined because daily overextension and leverage are one market-flow family and the rubric already captures absence of fear; ETF/spot demand independently offsets it.
> GATES — [3,7,9] pass, 3/9 with V=2. Phase 1A meets gates but fails score 6<8, so D2 is unavailable. Deeper phases and Override fail score.
> EV — 30/35/25/10 produces 76650, -0.98% versus spot. Active downtrend NO; realized trend repair moves probability toward Rally/Range. Collar ON and the 19-report negative-EV series stays corroborative-only.
> POSITION RECONCILIATION — FRESH event-driven snapshot, but UNEXPLAINED custody plus unreliable basis prohibit any BTC quantity, cost, PnL, trim or stop claim. Prior cold-start narration is superseded; the 0%-deployed plan is not an account-balance fact.
> ACTIONS — 1) Do not add after the squeeze. 2) Preserve the carried 58000-61000 and 54000-58000 zones as planning references only. 3) Repair ledger custody/basis. 4) Rerun after Aug-26 PCE or a score/gate change. 5) Produce the owed standalone BTC Flying Rocket report. Cash is a position; patience is alpha.

**Primary action:** **WAIT_NO_ADD** — Adjusted score 6<8 despite sufficient P1A gates; buying a leveraged squeeze offers poor FK asymmetry.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Deep value override | Evaluated and not applicable: no confirmed prior deployed tranche and mechanical 6<15. |
| Tier1 calendar | Aug 24-28: PCE/GDP second estimate Aug 26 08:30 ET; no NFP, CPI or FOMC decision. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-22 | D1 | — | — | — | Fresh two-family evidence required before any nonzero term. | — | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | flying_rocket · 3/20 · 0 gates | Same-timestamp FR companion: euphoria 2, momentum 0, valuation 0, distribution 0, vulnerability 1, no penalty; channel NONE because spot is above the falling 200-day MA. Cycle cap is structurally 8. Standalone FR report owed: TRUE because Aug-21 short liquidations exceeded $100M; fired_on 2026-08-22, reports_outstanding 1. |
| Cross-validation | ✅ CONSISTENT | FK 6 versus FR 3; structurally cap-bound | Neither score is >=12. BTC is >20% below the one-year high, so the joint >=12 inconsistency is also unfalsifiable under the FR phase-cycle cap. Re-examination found no conflict. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| PCE and GDP second estimate | ✅ AVAILABLE | 2026-08-26 08:30 ET; rerun after the official release. |
| Phase 1A | ✅ AVAILABLE | Adjusted score >=8 while at least 3 gates and V>=2 remain live; then reassess the carried 58000-61000 zone. |
| Leverage cooling | ✅ AVAILABLE | OI falls away from the 90-day high while spot CVD stays positive; this reduces chase risk but does not itself unlock a phase. |
| Ledger repair | ⚠️ DATA_LIMITED | Reconcile custody and basis before any position-level action. |
| Standalone Flying Rocket | ✅ AVAILABLE | Produce a fresh BTC FR report because short liquidations exceeded the $100M tripwire. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-21 | Crypto short squeeze | ✅ AVAILABLE | About $1.06B of short liquidations powered a historic squeeze; it lights intensity gate 7 but argues against chasing a long entry. |
| 2026-08-21 | US spot BTC ETF inflow streak | ✅ AVAILABLE | Five green sessions and +$1.9178B for the week confirm institutional demand and keep ETF capitulation gate 4 dark. |
| 2026-08-26T08:30:00-04:00 | BEA PCE and GDP second estimate | ✅ AVAILABLE | Only Tier-1 release in the next five trading sessions; no NFP, CPI or FOMC decision is scheduled in-window. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| Trend repair can persist without an FK entry | A completed weekly close back below the 200-day MA with negative spot CVD and ETF outflows would falsify the repair; a score/gate unlock would falsify WAIT. | ✅ AVAILABLE |
| D1=0 | No unscored term is load-bearing; future evidence must earn a fresh term from two independent source families. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.adjusted | 7 | 6 | Fear disappeared (sentiment 2 to 0), while valuation softened (4 to 3) and the historic liquidation event added 1. |
| Gates.passed | 3,6 | 3,7,9 | Price left the 200-week band; liquidation intensity and macro/regulatory catalyst relit gates 7 and 9. |
| Position.status | EXPIRED cold-start | DATA_LIMITED | Event-driven snapshot is FRESH, but custody and basis defects require refusing the BTC position claim. |
| Companion framework.owed | No | Yes | Reported short liquidations exceeded the $100M standalone-report tripwire. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| bea | BEA release schedule | official calendar | 2026-08-22 | 2026-08-22T07:30:00Z | —<br>[Open source](https://www.bea.gov/news/schedule/full) |
| bls | BLS August 2026 schedule | official calendar | 2026-08-22 | 2026-08-22T07:30:00Z | —<br>[Open source](https://www.bls.gov/schedule/2026/08_sched.htm) |
| cftc | CFTC Innovation Advisory Committee release | regulator | 2026-08-20 | 2026-08-22T07:24:00Z | —<br>[Open source](https://www.cftc.gov/PressRoom/PressReleases/9279-26) |
| correlation | Yahoo BTC-USD and S&P 500 closes; deterministic correlation | computed market data | 2026-08-21 | 2026-08-22T07:41:40.616Z | —<br>[Open source](https://query1.finance.yahoo.com/) |
| farside | Farside Investors BTC ETF flow table | issuer-flow aggregator | 2026-08-21 | 2026-08-22T07:25:00Z | —<br>[Open source](https://farside.co.uk/btc/) |
| fed | Federal Reserve FOMC calendar | official calendar | 2026-08-22 | 2026-08-22T07:30:00Z | —<br>[Open source](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm) |
| ledger | Personal-accounting position snapshot | ledger | 2026-08-15T09:30:02.628Z | 2026-08-22T07:35:00Z | Event-driven FRESH; exact BTC claim refused because custody is UNEXPLAINED and basis is unreliable.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| liquidations | CoinGlass liquidation data reported by Yahoo Finance | news/data relay | 2026-08-21 | 2026-08-22T07:22:00Z | —<br>[Open source](https://finance.yahoo.com/markets/crypto/articles/crypto-bears-burned-short-liquidations-055534539.html) |
| snapshot | Deterministic BTC/ETH/macro snapshot | computed | 2026-08-22T07:38:55.994Z | 2026-08-22T07:38:55.994Z | Cross-venue spot, completed-week RSI, 200-week SMA, funding, on-chain and macro blocks retain their source metadata.<br>[Open source](data/runs/20260822-0738-5f8835b8/snapshot.json) |
| sosovalue | SoSoValue BTC ETF net-assets context | fund-data aggregator | 2026-08-20 | 2026-08-22T07:27:00Z | —<br>[Open source](https://www.sosovalue.com/assets/etf/us-btc-spot) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-22T07:38:55.994Z |
| Generated at | 2026-08-22T07:47:00Z |
| Report at | 2026-08-22T07:46:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | btc_fallen_knives_20260822_0346 |
| Report filename | btc_fallen_knives_20260822_0346.json |
| Run ID | 20260822-0738-5f8835b8 |
| Snapshot ID | sha256:5f8835b8f5dc5bc20aed2c97b3ef234bef67d5550447c629255090ad417e9f30 |
| Prior report | btc_fallen_knives_20260813_1744 |
| Prior report hash | c4c57601eb062fee307766e29c05fbc4d3de8c486f2060492beff4c04e74e71e |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:334e81012d5853a87a6b5c8e422382c8e3be0e1532c62e5f57b8f741be7453ae |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | 🔒 LOCKED | FK-P1A-BTC-20260822-0346 | crypto |
| 1B | 🔒 LOCKED | FK-P1B-BTC-20260822-0346 | crypto |
| 2 | 🔒 LOCKED | FK-P2-BTC-20260822-0346 | crypto |
| 3 | 🔒 LOCKED | FK-P3-BTC-20260822-0346 | crypto |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class crypto
**Active tags:** None
**Reserved tags:** FK-P1A-BTC-20260822-0346, FK-P1B-BTC-20260822-0346, FK-P2-BTC-20260822-0346, FK-P3-BTC-20260822-0346

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-BTC-20260822-0346 | UNVERIFIED | crypto |
| 1B | FK-P1B-BTC-20260822-0346 | UNVERIFIED | crypto |
| 2 | FK-P2-BTC-20260822-0346 | UNVERIFIED | crypto |
| 3 | FK-P3-BTC-20260822-0346 | UNVERIFIED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_fallen_knives_20260822_0346.md (report-machine/1).
```json machine
{"change_log":[{"current":6,"field":"score.adjusted","previous":7,"reason":"Fear disappeared (sentiment 2 to 0), while valuation softened (4 to 3) and the historic liquidation event added 1."},{"current":"3,7,9","field":"gates.passed","previous":"3,6","reason":"Price left the 200-week band; liquidation intensity and macro/regulatory catalyst relit gates 7 and 9."},{"current":"DATA_LIMITED","field":"position.status","previous":"EXPIRED cold-start","reason":"Event-driven snapshot is FRESH, but custody and basis defects require refusing the BTC position claim."},{"current":true,"field":"companion_framework.owed","previous":false,"reason":"Reported short liquidations exceeded the $100M standalone-report tripwire."}],"companion_framework":{"framework":"flying_rocket","gates":0,"rationale":"Same-timestamp FR companion: euphoria 2, momentum 0, valuation 0, distribution 0, vulnerability 1, no penalty; channel NONE because spot is above the falling 200-day MA. Cycle cap is structurally 8. Standalone FR report owed: TRUE because Aug-21 short liquidations exceeded $100M; fired_on 2026-08-22, reports_outstanding 1.","score":3,"status":"AVAILABLE"},"cross_validation":{"rationale":"Neither score is >=12. BTC is >20% below the one-year high, so the joint >=12 inconsistency is also unfalsifiable under the FR phase-cycle cap. Re-examination found no conflict.","relationship":"FK 6 versus FR 3; structurally cap-bound","status":"CONSISTENT"},"data_gaps":[{"field":"ledger_btc_custody_and_basis","impact":"No BTC quantity, cost basis, PnL, trim or position-sized action may be stated.","source_ids":["ledger"],"status":"DATA_LIMITED"},{"field":"true_lth_supply","impact":"Holder gate 8 and the LTH half of the holder leg receive no credit.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"hash_ribbon","impact":"Gate 5 stays dark; a fresh Hash Ribbon buy/stress confirmation is required.","source_ids":[],"status":"UNKNOWN"},{"field":"cross_exchange_market_flow","impact":"CVD/OI are context from Binance fallback, not a scored leg or gate.","source_ids":["snapshot"],"status":"DATA_LIMITED"}],"deployment":{"deployed_pct":"0","dry_pct":"100","throttle_released":false,"tranches":[{"channel":null,"deployed":false,"entry_price":null,"pct":"10","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 6<8; gates 3/9 and V=2 meet, but the score line fails. Carried planning zone 58000-61000; no resting order is authorized.","state":"LOCKED","stop":null,"tag":"FK-P1A-BTC-20260822-0346","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"15","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 6<11 and gate floor 3<5; carried planning zone 54000-58000.","state":"LOCKED","stop":null,"tag":"FK-P1B-BTC-20260822-0346","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"30","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 6<15 and gates 3<6; no authorized zone.","state":"LOCKED","stop":null,"tag":"FK-P2-BTC-20260822-0346","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"45","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Mechanical 6<17 and gates 3<7; no analyst channel may unlock Phase 3.","state":"LOCKED","stop":null,"tag":"FK-P3-BTC-20260822-0346","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"D4 30/35/25/10 follows a realized trend-repair event versus the score-6 baseline. Active downtrend NO. Prior -2.77% EV was contradicted by a +22.11% spot move; this becomes the 19th negative-sign report and EV remains corroborative-only. Collar ON because |EV|<2% and score 6.","probability_sum":1,"scenarios":[{"high":"88000","low":"82000","mid":"85000","name":"Rally","probability":0.3,"rationale":"ETF inflows and positive spot CVD extend the squeeze."},{"high":"82000","low":"73000","mid":"77500","name":"Range","probability":0.35,"rationale":"Post-squeeze consolidation near current spot."},{"high":"73000","low":"68000","mid":"70500","name":"Retest","probability":0.25,"rationale":"Daily RSI/OI excess mean-reverts toward the 200-day area."},{"high":"68000","low":"60000","mid":"64000","name":"Bear","probability":0.1,"rationale":"Macro/oil shock erases the repaired structure."}],"stated_ev":"76650","vs_spot_pct":"-0.98"},"events":[{"as_of":"2026-08-21","impact":"About $1.06B of short liquidations powered a historic squeeze; it lights intensity gate 7 but argues against chasing a long entry.","name":"Crypto short squeeze","status":"AVAILABLE"},{"as_of":"2026-08-21","impact":"Five green sessions and +$1.9178B for the week confirm institutional demand and keep ETF capitulation gate 4 dark.","name":"US spot BTC ETF inflow streak","status":"AVAILABLE"},{"as_of":"2026-08-26T08:30:00-04:00","impact":"Only Tier-1 release in the next five trading sessions; no NFP, CPI or FOMC decision is scheduled in-window.","name":"BEA PCE and GDP second estimate","status":"AVAILABLE"}],"evidence":{"correlation":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"29 paired daily log returns; surcharge off and the Phase-2 correlation condition passes.","source_ids":["correlation"],"status":"AVAILABLE","unit":"Pearson correlation","value":"0.113192"},"etf_flows":{"as_of":"2026-08-21","confidence":"HIGH","rationale":"Latest day +307.5M, five consecutive positive sessions and +1917.8M for Aug 17-21; monthly flow is positive, so gate 4 is off.","source_ids":["farside","sosovalue"],"status":"AVAILABLE","unit":"USD millions trailing month","value":"1940.3"},"holder":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"Reserves rose; true LTH data is provider-gated, so holder leg and gate 8 receive no credit.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent exchange-reserve change 30d","value":"2.309"},"liquidations":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"CoinGlass-cited market-wide short liquidations were a historic squeeze; counted as top-decile liquidation intensity but not as long-side capitulation direction.","source_ids":["liquidations"],"status":"AVAILABLE","unit":"USD short liquidations / 24h","value":"1060000000"},"macro":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"Real yield eased 4bp and DXY fell 0.87%; Treasury/regulatory catalysts support gate 9, while Brent +6.63% and equities lower keep confidence moderate.","source_ids":["snapshot","cftc"],"status":"AVAILABLE","unit":null,"value":"neutral-positive with oil risk"},"sentiment":{"as_of":"2026-08-22","confidence":"HIGH","rationale":"Alternative.me prints 62, 72 and 71; >50 scores zero and the <=15 streak is zero.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Fear & Greed 3-day average","value":"68.33"},"spot":{"as_of":"2026-08-22T07:38:48Z","confidence":"HIGH","rationale":"Median of four synchronized CoinGecko, Binance, Coinbase and Kraken quotes; panel spread 0.026%.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"77409.69"},"valuation":{"as_of":"2026-08-21","confidence":"MEDIUM","rationale":"Coin Metrics reconstruction; <=2 scores valuation 3 and <1 lights gate 3.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"MVRV-Z","value":"0.841"},"weekly_rsi":{"as_of":"week starting 2026-08-10","confidence":"HIGH","rationale":"Completed weekly closes only; 38.80 scores momentum 2.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"Wilder RSI-14","value":"38.80"}},"falsifiers":[{"claim":"Trend repair can persist without an FK entry","condition":"A completed weekly close back below the 200-day MA with negative spot CVD and ETF outflows would falsify the repair; a score/gate unlock would falsify WAIT.","status":"AVAILABLE"},{"claim":"D1=0","condition":"No unscored term is load-bearing; future evidence must earn a fresh term from two independent source families.","status":"AVAILABLE"}],"gates":{"active":9,"measurement_basis":{"1":"DARK [V] — F&G <=15 streak is 0; relight after seven daily prints <=15.","2":"DARK [V] — completed weekly RSI 38.80; relight below 30.","3":"LIT [V] — MVRV-Z 0.841 <1.","4":"DARK [V] — trailing-month ETF flow +$1.9403B, not an outflow >=2% of AUM.","5":"DARK [T] — no fresh Hash Ribbon confirmation; relight requires a current buy/stress signal.","6":"DARK [T] — spot 20.96% above the 200-week SMA; relight inside +/-8%.","7":"LIT [V] — Aug-21 market-wide short liquidations about $1.06B, a historic/top-decile intensity event; direction is a short squeeze.","8":"DARK [V] — reserves +2.309%/30d and true LTH data provider-gated.","9":"LIT [T] — easing real yield/DXY plus Treasury and regulatory catalysts; Brent/equity weakness keeps it moderate.","binding_axis":"P1A has 3/9 gates and V=2 but adjusted score is two points short; every deeper phase is score-bound. D2 unavailable because the score line fails."},"na":[],"passed":[3,7,9],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":7}},"identity":{"asset":"BTC","date":"2026-08-22","filename":"btc_fallen_knives_20260822_0346.json","framework":"fallen_knives","local_time":"03:46","timezone":"America/New_York"},"market":{"ath":{"as_of":"2025-10-06","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"126080"},"drawdown_pct":{"as_of":"2026-08-22","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"38.60"},"metrics":{"adr5":{"as_of":"2026-08-22","note":"Five most recent full crypto sessions.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"3919.36"},"dry_powder_yield":{"as_of":"2026-08-21","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"3.71"},"funding":{"as_of":"2026-08-22T04:00:00Z","note":"Positive; no negative interval streak.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"6.73"},"futures_cvd_3d":{"as_of":"2026-08-22T00:00:00Z","note":"Binance single-venue fallback; positive and much larger than spot.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"4327166210.91"},"open_interest_change_24h":{"as_of":"2026-08-22T04:00:00Z","note":"OI is at its Binance 90-day high; leverage is rebuilding.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"3.281"},"sma200w":{"as_of":"week starting 2026-08-10","note":"Spot is 20.96% above; gate 6 off.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"63994.17"},"spot_cvd_3d":{"as_of":"2026-08-22T00:00:00Z","note":"Binance single-venue fallback; positive.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"321577158.57"}},"reconciliation":{"method":"median of four synchronized venue/aggregator quotes","note":"Yahoo frozen daily close excluded from the median.","quotes":[{"source_ids":["snapshot"],"value":"77419"},{"source_ids":["snapshot"],"value":"77410.08"},{"source_ids":["snapshot"],"value":"77399.07"},{"source_ids":["snapshot"],"value":"77409.30"}],"spread_pct":"0.026","status":"AVAILABLE"},"regime":{"active_downtrend":false,"daily_rsi14":"81.67","label":"violent trend repair after short squeeze; not a fallen knife","ma200":"68982.51","ma200_falling":true,"ma200_slope_20_pct":"-3.01","price_below_ma200":false,"trend_residual":"spot/futures CVD and OI rose together; chase risk offsets repaired spot demand"},"spot":{"as_of":"2026-08-22T07:38:48Z","note":"Four-source synchronized median.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"77409.69"}},"narrative":{"arguments":{"deep_value_override":"Evaluated and not applicable: no confirmed prior deployed tranche and mechanical 6<15.","discretion_ledger":[{"channel":"D1","date":"2026-08-22","falsifier":"Fresh two-family evidence required before any nonzero term.","load_bearing":false,"reason":"Overextension/OI risk was considered but declined as one flow family offset by independent ETF and spot demand.","term":"0.0"}],"stop_migration_ledger":[],"tier1_calendar":"Aug 24-28: PCE/GDP second estimate Aug 26 08:30 ET; no NFP, CPI or FOMC decision."},"bear_case":"Daily RSI 81.67, OI at the Binance 90-day high, futures CVD far larger than spot CVD, positive funding and a 24% one-week flow-window rally create chase risk. IF price loses the 200-day MA with ETF/spot-CVD reversal, THEN Retest probability rises sharply.","bull_case":"Five positive ETF sessions, +$1.9178B weekly inflows, positive spot CVD, a 20.96% cushion above the 200-week SMA and easing DXY/real yield show genuine repair. IF spot CVD stays positive while OI cools and price holds the 200-day MA, THEN Range/Rally remains the dominant terminal cluster.","primary_action":{"rationale":"Adjusted score 6<8 despite sufficient P1A gates; buying a leveraged squeeze offers poor FK asymmetry.","status":"AVAILABLE","value":"WAIT_NO_ADD"},"rationale":"SCORE — 0/2/3/1/0 gives mechanical and adjusted 6. D1=0: a negative term was considered and declined because daily overextension and leverage are one market-flow family and the rubric already captures absence of fear; ETF/spot demand independently offsets it.\nGATES — [3,7,9] pass, 3/9 with V=2. Phase 1A meets gates but fails score 6<8, so D2 is unavailable. Deeper phases and Override fail score.\nEV — 30/35/25/10 produces 76650, -0.98% versus spot. Active downtrend NO; realized trend repair moves probability toward Rally/Range. Collar ON and the 19-report negative-EV series stays corroborative-only.\nPOSITION RECONCILIATION — FRESH event-driven snapshot, but UNEXPLAINED custody plus unreliable basis prohibit any BTC quantity, cost, PnL, trim or stop claim. Prior cold-start narration is superseded; the 0%-deployed plan is not an account-balance fact.\nACTIONS — 1) Do not add after the squeeze. 2) Preserve the carried 58000-61000 and 54000-58000 zones as planning references only. 3) Repair ledger custody/basis. 4) Rerun after Aug-26 PCE or a score/gate change. 5) Produce the owed standalone BTC Flying Rocket report. Cash is a position; patience is alpha.","summary":"WAIT / NO ADD. BTC FK is 6/20 with 3/9 gates; the score is two points below Phase 1A. Spot demand is real, but this is a leveraged post-squeeze rally, not a fallen knife. Position controls are data-limited by an unexplained ledger mismatch."},"out_of_scope":["No exchange order is placed or cancelled by this report.","The published liquidation figure is a reported CoinGlass snapshot, not an independently reconstructed percentile series."],"position":{"asset":"BTC","attribution":{"active_tags":[],"note":"No confirmed BTC quantity can be mapped to an FK phase.","status":"UNKNOWN"},"basis":{"avg_cost":null,"reason":"Unbacked disposals make basis non-derivable; no average cost, basis, unrealized PnL or ROI is quoted.","reliable":false,"total_cost":null},"custody":{"reason":"Live balance and fill replay disagree; neither withdrawals nor a migration seed explains the gap.","status":"UNEXPLAINED"},"dry_powder":"10741.5780","futures":[],"pnl":{"realized":null,"reason":"Custody and basis defects prohibit a PnL claim.","status":"DATA_LIMITED","unrealized":null},"quantity":null,"reconciliation":"Prior report used an EXPIRED cold-start planning state. The newest ledger is now FRESH under event-driven validity, but BTC custody is UNEXPLAINED and basis unreliable; no position figure in either direction is reported. The ledger wins over prior narration, while Rule 4 supplies only the 0%-deployed deployment plan.","status":"DATA_LIMITED"},"position_controls":{"action":{"rationale":"Custody and basis defects block quantity, trim/exit, stop attachment and position-sized action. The report proceeds with a no-add market decision.","status":"DATA_LIMITED","value":"NO_POSITION_CLAIM"},"required":true,"status":"DATA_LIMITED"},"report_id":"btc_fallen_knives_20260822_0346","risk_controls":{"carry":{"dry_powder_yield_pct":"3.71","note":"Cash has measurable T-bill opportunity value.","status":"AVAILABLE","veto":false},"concentration":{"note":"No addition; current BTC weight cannot be computed from a refused quantity claim.","planned_pct":"0","status":"DATA_LIMITED"},"ratchet":{"note":"No confirmed open tranche or auditable live stop; D6 migration ledger empty.","parameters_changed":false,"status":"NOT_APPLICABLE"},"stops":{"catastrophic":"50000","coherence":true,"compound":"55000 AND mechanical<12","deepest_zone_floor":"54000","note":"Prospective only; catastrophic is strictly below the deepest carried floor. No live stop is armed because position evidence is defective.","status":"LOCKED"},"time_stops":{"note":"No newly authorized tranche.","status":"NOT_APPLICABLE"}},"run":{"prior_report_id":"btc_fallen_knives_20260813_1744","prior_report_sha256":"c4c57601eb062fee307766e29c05fbc4d3de8c486f2060492beff4c04e74e71e","run_id":"20260822-0738-5f8835b8","snapshot_id":"sha256:5f8835b8f5dc5bc20aed2c97b3ef234bef67d5550447c629255090ad417e9f30","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:334e81012d5853a87a6b5c8e422382c8e3be0e1532c62e5f57b8f741be7453ae","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":6,"caps":[{"field":"attainable_ceiling","reason":"No structural score leg is pinned unavailable.","value":20},{"field":"line_states","reason":"All phase score lines are evaluated independently.","value":["P1A>=8 LIVE-FALSE","P1B>=11 LIVE-FALSE","P2>=15 LIVE-FALSE","P3 mechanical>=17 LIVE-FALSE","compound mechanical<12 VACUOUS-PERMISSIVE but price-gated"]}],"discretion":0,"legs":{"capitulation":1,"holder":0,"momentum":2,"sentiment":0,"valuation":3},"mechanical":6,"penalties":[],"raw":6,"rounding":"half-up"},"sources":{"bea":{"as_of":"2026-08-22","kind":"official calendar","name":"BEA release schedule","retrieved_at":"2026-08-22T07:30:00Z","url":"https://www.bea.gov/news/schedule/full"},"bls":{"as_of":"2026-08-22","kind":"official calendar","name":"BLS August 2026 schedule","retrieved_at":"2026-08-22T07:30:00Z","url":"https://www.bls.gov/schedule/2026/08_sched.htm"},"cftc":{"as_of":"2026-08-20","kind":"regulator","name":"CFTC Innovation Advisory Committee release","retrieved_at":"2026-08-22T07:24:00Z","url":"https://www.cftc.gov/PressRoom/PressReleases/9279-26"},"correlation":{"as_of":"2026-08-21","kind":"computed market data","name":"Yahoo BTC-USD and S&P 500 closes; deterministic correlation","retrieved_at":"2026-08-22T07:41:40.616Z","url":"https://query1.finance.yahoo.com/"},"farside":{"as_of":"2026-08-21","kind":"issuer-flow aggregator","name":"Farside Investors BTC ETF flow table","retrieved_at":"2026-08-22T07:25:00Z","url":"https://farside.co.uk/btc/"},"fed":{"as_of":"2026-08-22","kind":"official calendar","name":"Federal Reserve FOMC calendar","retrieved_at":"2026-08-22T07:30:00Z","url":"https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"},"ledger":{"as_of":"2026-08-15T09:30:02.628Z","kind":"ledger","name":"Personal-accounting position snapshot","note":"Event-driven FRESH; exact BTC claim refused because custody is UNEXPLAINED and basis is unreliable.","retrieved_at":"2026-08-22T07:35:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"liquidations":{"as_of":"2026-08-21","kind":"news/data relay","name":"CoinGlass liquidation data reported by Yahoo Finance","retrieved_at":"2026-08-22T07:22:00Z","url":"https://finance.yahoo.com/markets/crypto/articles/crypto-bears-burned-short-liquidations-055534539.html"},"snapshot":{"as_of":"2026-08-22T07:38:55.994Z","kind":"computed","name":"Deterministic BTC/ETH/macro snapshot","note":"Cross-venue spot, completed-week RSI, 200-week SMA, funding, on-chain and macro blocks retain their source metadata.","retrieved_at":"2026-08-22T07:38:55.994Z","url":"data/runs/20260822-0738-5f8835b8/snapshot.json"},"sosovalue":{"as_of":"2026-08-20","kind":"fund-data aggregator","name":"SoSoValue BTC ETF net-assets context","retrieved_at":"2026-08-22T07:27:00Z","url":"https://www.sosovalue.com/assets/etf/us-btc-spot"}},"stale_inputs":["Ledger holdings_as_of is 2026-07-05, disclosed as audit metadata under event-driven validity; structural custody/basis defects, not elapsed time, limit the BTC claim."],"substitutions":[],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FK-P1A-BTC-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"1A"},{"canonical_tag":"FK-P1B-BTC-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"1B"},{"canonical_tag":"FK-P2-BTC-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"2"},{"canonical_tag":"FK-P3-BTC-20260822-0346","decision":"LOCKED","instrument_class":"crypto","phase":"3"}],"instrument_class":"crypto","reserved_tags":["FK-P1A-BTC-20260822-0346","FK-P1B-BTC-20260822-0346","FK-P2-BTC-20260822-0346","FK-P3-BTC-20260822-0346"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-22T07:38:55.994Z","generated_at":"2026-08-22T07:47:00Z","report_at":"2026-08-22T07:46:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Phase 1A score is two points short; no discretionary channel can bridge it.","status":"AVAILABLE","value":"WAIT_NO_ADD"},"statement":"BTC is not an accumulation entry: adjusted 6/20, gates 3/9, and every phase remains locked. Do not add; reconcile the ledger and let leverage cool.","status":"WAIT"},"watchlist":[{"item":"PCE and GDP second estimate","status":"AVAILABLE","trigger":"2026-08-26 08:30 ET; rerun after the official release."},{"item":"Phase 1A","status":"AVAILABLE","trigger":"Adjusted score >=8 while at least 3 gates and V>=2 remain live; then reassess the carried 58000-61000 zone."},{"item":"Leverage cooling","status":"AVAILABLE","trigger":"OI falls away from the 90-day high while spot CVD stays positive; this reduces chase risk but does not itself unlock a phase."},{"item":"Ledger repair","status":"DATA_LIMITED","trigger":"Reconcile custody and basis before any position-level action."},{"item":"Standalone Flying Rocket","status":"AVAILABLE","trigger":"Produce a fresh BTC FR report because short liquidations exceeded the $100M tripwire."}],
  "tagging": {
    "active_tags": [],
    "entries": [
      {
        "canonical_tag": "FK-P1A-BTC-20260822-0346",
        "decision": "LOCKED",
        "instrument_class": "crypto",
        "phase": "1A"
      },
      {
        "canonical_tag": "FK-P1B-BTC-20260822-0346",
        "decision": "LOCKED",
        "instrument_class": "crypto",
        "phase": "1B"
      },
      {
        "canonical_tag": "FK-P2-BTC-20260822-0346",
        "decision": "LOCKED",
        "instrument_class": "crypto",
        "phase": "2"
      },
      {
        "canonical_tag": "FK-P3-BTC-20260822-0346",
        "decision": "LOCKED",
        "instrument_class": "crypto",
        "phase": "3"
      }
    ],
    "instrument_class": "crypto",
    "reserved_tags": [
      "FK-P1A-BTC-20260822-0346",
      "FK-P1B-BTC-20260822-0346",
      "FK-P2-BTC-20260822-0346",
      "FK-P3-BTC-20260822-0346"
    ],
    "schema": "report-phase-registry/2",
    "status": "REGISTERED",
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_fallen_knives_20260822_0346.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "BTC",
      "report_date": "2026-08-22",
      "report_local_time": "03:46",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-BTC-20260822-0346",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260822_0346.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-22",
          "report_local_time": "03:46"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-BTC-20260822-0346",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260822_0346.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-22",
          "report_local_time": "03:46"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-BTC-20260822-0346",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260822_0346.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-22",
          "report_local_time": "03:46"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-BTC-20260822-0346",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260822_0346.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-22",
          "report_local_time": "03:46"
        }
      ]
    },
    "report_file": "btc_fallen_knives_20260822_0346.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "BTC",
    "report_date": "2026-08-22",
    "report_local_time": "03:46"
  }
}
```
