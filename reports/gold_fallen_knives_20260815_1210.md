# GOLD — Fallen Knives — 2026-08-15 12:10

## 1. Decision snapshot

| Decision field | Reading |
| --- | --- |
| Asset / framework | GOLD · Fallen Knives |
| Report time | 2026-08-15 12:10 (America/New_York) |
| Verdict | ⏸️ HOLD — Hold the confirmed PAXG position, cancel every resting buy, and make no FK addition; adjusted score 4/20 and 1/8 gates authorize no tranche. |
| Adjusted score | **4/20** (mechanical 5, raw 4) |
| Confirmation gates | 1/8 active passed |
| Position | ✅ FRESH · 1.3293894 GOLD |
| Deployment | 0% deployed · 100% dry |
| Primary action | **CANCEL_RESTING_BUYS_AND_HOLD** — Prevent unauthorized concentration while preserving the existing long until a mechanical trim/exit or compound/catastrophic stop fires. |

## 2. Market, evidence, and data quality

| Measure | Value | Status | Confidence | As of | Reading / source |
| --- | --- | --- | --- | --- | --- |
| Canonical spot | $4,380.4/oz | ✅ AVAILABLE | — | 2026-08-14 close | Canonical underlying reference is the frozen COMEX Friday close. PAXG execution is independently reconciled at 4372.26.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| All-time high | $5,586.2/oz | ✅ AVAILABLE | — | 2026-01-26 | One-year/all-time high in the deterministic history.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Drawdown from ATH | 21.59% | ✅ AVAILABLE | — | 2026-08-14 close | —<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| ADR-5 | $60.46 | ✅ AVAILABLE | — | 2026-08-14 | Five full sessions, August 10-14<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| BTC RV30 | 21.89% | ✅ AVAILABLE | — | 2026-08-15 | Gold/BTC ratio 1.02x<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Dry-powder yield | 3.70% | ✅ AVAILABLE | — | 2026-08-14 | Three-month T-bill benchmark<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Gold RV30 | 22.38% | ✅ AVAILABLE | — | 2026-08-14 | —<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| 200-day MA | $4,486.27/oz | ✅ AVAILABLE | — | 2026-08-14 | Spot is 2.36% below; slope rising<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| 10-year real yield | 2.39% | ✅ AVAILABLE | — | 2026-08-13 | Down 0.04pp over five prints<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| 200-week SMA | $2,856.88/oz | ✅ AVAILABLE | — | 2026-08-14 | Spot is 53.33% above<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Weekly RSI-14 | 49.95 RSI | ✅ AVAILABLE | — | week ending 2026-08-09 | 260 completed closes<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |

**Regime:** • trend repair without fallen-knife fear — Active downtrend: No; Daily rsi14: 64.59; Label: trend repair without fallen-knife fear; Ma200 falling: No; Ma200 slope 20 pct: 0.29%; PAXG above sma20 50 100: Yes; Price below ma200: Yes; Trend residual: mirror toward Rally because the breakout shelf holds and the 200-day slope is positive

### Spot reconciliation

**⚠️ DATA_LIMITED** — underlying GC/MGC frozen-close agreement plus independent synchronized PAXG median; spread 0.0791%

| Instrument | Value | State | Sources |
| --- | --- | --- | --- |
| GC=F | $4,380.4/oz | • frozen Friday close | [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| MGC=F | $4,380.4/oz | • frozen Friday close | [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| PAXGUSDT Binance | $4,375.35/PAXG | ✅ live | [binance](https://api.binance.com/api/v3/ticker/bookTicker?symbol=PAXGUSDT) |
| PAXGUSD Kraken | $4,371.89/PAXG | ✅ live | [kraken](https://api.kraken.com/0/public/Ticker?pair=PAXGUSD) |
| PAXG CoinGecko | $4,372.26/PAXG | ✅ live | [coingecko](https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd&include_last_updated_at=true) |

> PAXG median trades at a 0.1858% discount to the frozen GC close. Underlying confidence is low because the bullion market is closed; execution-instrument confidence is high.

### Evidence inputs

| Input | Value | Status | Confidence | As of | Rationale / source |
| --- | --- | --- | --- | --- | --- |
| Correlation | 0.3128 Pearson correlation | ✅ AVAILABLE | MEDIUM | 2026-08-13 | Computed over 29 paired daily log returns using GLD as the gold proxy and FRED SP500 closes; no >0.7 gate surcharge.<br>Sources: [gld](https://www.spdrgoldshares.com/usa/gld/), [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| COT managed-money net long | 137,662 managed-money net-long contracts | ✅ AVAILABLE | HIGH | 2026-08-11 | Net longs rose by 6,896 week over week (148,634 long minus 10,972 short); positioning is building, not washing out.<br>Sources: [cftc](https://www.cftc.gov/files/dea/history/fut_disagg_txt_2026.zip) |
| GLD holdings | 1,023.24 tonnes | ✅ AVAILABLE | HIGH | 2026-08-13 | Official GLD holdings rose 8.52t from August 6 and 20.79t from July 13.<br>Sources: [gld](https://www.spdrgoldshares.com/usa/gld/) |
| Macro | mixed | ✅ AVAILABLE | HIGH | 2026-08-14 | 10y real yield 2.39% eased 4bp over five prints, but Brent rose 5.95% in five sessions to 88.52 and Hormuz risk is live.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json), [ap_hormuz](https://apnews.com/article/e8565c608ac5283ec8103c85df924b13) |
| PAXG spot | $4,372.26/PAXG | ✅ AVAILABLE | HIGH | 2026-08-15T16:02:00Z | Median of synchronized Binance 4375.35, Kraken 4371.89, and CoinGecko 4372.26; 0.0791% panel spread.<br>Sources: [binance](https://api.binance.com/api/v3/ticker/bookTicker?symbol=PAXGUSDT), [kraken](https://api.kraken.com/0/public/Ticker?pair=PAXGUSD), [coingecko](https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd&include_last_updated_at=true) |
| Sentiment | — | — NOT_COVERED | NONE | 2026-08-15 | No validated daily gold fear instrument exists; the measured framework fallback scores 2. GVZ and PHYS are disclosed context only.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Underlying spot | $4,380.4/oz | ✅ AVAILABLE | LOW | 2026-08-14 close | GC=F and MGC=F agree at 4380.40, but both are frozen Friday closes and there are zero synchronized live bullion quotes on Saturday.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Valuation | 21.59% | ✅ AVAILABLE | HIGH | 2026-08-14 close | Gold RV30 22.38% versus BTC RV30 21.89% gives a 1.02x ratio, so the <=0.5x low-volatility adaptation is unavailable; standard <30% drawdown scores 0.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Volume flush | 74.4% | ✅ AVAILABLE | MEDIUM | 2026-08-14 close | 1491 contracts is below the top-decile flush threshold; no capitulation credit.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |
| Weekly RSI-14 | 49.95 RSI-14 | ✅ AVAILABLE | HIGH | week ending 2026-08-09 | Wilder RSI-14 from 260 completed Monday-boundary weekly closes; >45 scores 0.<br>Sources: [snapshot](data/runs/20260815-1601-fecbe187/snapshot.json) |

**Data gaps:** 4 · **stale inputs:** 2 · **out of scope:** 3

**Data gaps**

- **gold_sentiment_daily** — — NOT_COVERED — Framework fallback leg=2; no gate-1 credit from sentiment. GVZ/PHYS remain unscored context.
- **live_underlying_bullion_panel** — ⚠️ DATA_LIMITED — Weekend GC/MGC closes lower confidence for underlying; PAXG orders use the synchronized PAXG panel instead.
- **global_gold_etf_july** — ⚠️ STALE — June WGC data is not used to credit gate 4; current official GLD tonnes supply the live US-fund read.
- **ledger_custody_reconciliation** — ⚠️ DATA_LIMITED — The user attests that live PAXG quantity is correct, but the 0.1736 PAXG replay mismatch remains an accounting defect and phase attribution remains unknown.

**Stale inputs**

- The ledger file's holdings_as_of is 2026-07-05, but the user explicitly attested on 2026-08-15 that the PAXG snapshot is current and correct; the attestation is used while the native age and custody warnings remain visible.
- World Gold Council global ETF monthly data is June 2026; it is context only, not a current scored/gate input.

**Out of scope**

- PAXG issuer solvency, bar-level allocation audit, and off-exchange custody verification are not independently audited in this report.
- No exchange order was changed or cancelled by this analysis; recommendations are not executions.
- Gold has no crypto perpetual-funding, liquidation, or on-chain-reserve analogue; only documented gold substitutions receive credit.

## 3. Score and confirmation gates

| Component | Score | Maximum | Interpretation |
| --- | --- | --- | --- |
| Capitulation | 0 | 3 | Mechanical component |
| Holder | 3 | 3 | Mechanical component |
| Momentum | 0 | 4 | Mechanical component |
| Sentiment | 2 | 5 | Mechanical component |
| Valuation | 0 | 5 | Mechanical component |

| Total | Value | Meaning |
| --- | --- | --- |
| Mechanical score | 5 | Legs plus penalties |
| Raw score | 4 | Mechanical plus discretion (-1) |
| Adjusted score | **4/20** | Decision score |
| Rounding | half-up | Pinned convention |

**Penalties:** none

### Caps, ceilings, and line-state constraints

| Field | Cap / value | Reason |
| --- | --- | --- |
| Sentiment | 2 | Measured gold fallback after GVZ/PHYS rejection |
| Valuation | 2 | Gold low-volatility maximum absent a confirmed COT flush; current low-vol substitution is unavailable, so the realized score is 0 |
| Attainable ceiling | 14 | sentiment 2 + momentum 4 + valuation 2 absent confirmed COT flush + capitulation 3 + holder 3 |
| Line states | — | P1A>=8 LIVE-FALSE; P1B>=11 LIVE-FALSE; P2>=15 VACUOUS-FALSE at ceiling 14; P3 mechanical>=17 VACUOUS-FALSE; Override mechanical>=15 VACUOUS-FALSE but still evaluated; compound mechanical<8 LIVE-TRUE on two distinct dates |

### Confirmation gates — 1/8 active passed

| # | State | Measurement / relight path |
| --- | --- | --- |
| 1 | • NOT PASSED | DARK [V] — COT managed-money net long +6,896 WoW; relight requires a 20-30K or >=15% weekly washout held/extended on the next print. |
| 2 | • NOT PASSED | DARK [V] — completed weekly RSI 49.95; relight below 30. |
| 3 | • NOT PASSED | DARK [V] — none-by-construction for gold; low-vol valuation bands confer no gate credit. |
| 4 | • NOT PASSED | DARK [V] — GLD holdings +8.52t week/+20.79t month; relight requires sustained multi-region outflows among the worst trailing 12 months, corroborated by GLD. |
| 5 | • N/A | N/A [T] — Hash Ribbon does not apply to gold. |
| 6 | • NOT PASSED | DARK [T] — spot 53.33% above the 200-week SMA; relight requires price within 8%, roughly a 29.6% decline to the upper proximity boundary. |
| 7 | • NOT PASSED | DARK [V] — volume at the 74.4th percentile, below the top-decile flush bar. |
| 8 | ✅ PASSED | LIT [V] — official GLD tonnes rising on weekly and monthly horizons, with continued official-sector accumulation context. |
| 9 | • NOT PASSED | DARK/WATCH [T] — real yields eased, but Brent +5.95% and Hormuz attacks keep the macro catalyst mixed; relight requires a cleaner neutral-to-positive policy/geopolitical impulse. |

### Unlock thresholds

| Phase | Score / gate threshold |
| --- | --- |
| P1A | 3 |
| P1B | 5 |
| P2 | 6 |
| P3 | 7 |

**Alternate reading:** correlation 0.3128; surcharge off; [V] gates 1.

**Binding axis:** 2: score short 11; gates short 5 and [V] short 2; score line also above attainable ceiling · 3: mechanical score short 12; gates short 6 and [V] short 3; score line above attainable ceiling · 1A: score short 4; gates short 2 and [V] short 1 · 1B: score short 7; gates short 4 and [V] short 2

## 4. Probability matrix and expected value

| Scenario | Probability | Low | High | Midpoint | Rationale |
| --- | --- | --- | --- | --- | --- |
| Rally | 28% | $4,485/oz | $4,700/oz | $4,592.5/oz | Rising 200-day slope, PAXG above 20/50/100-day averages, and a hold above the breakout shelf. |
| Range | 40% | $4,250/oz | $4,485/oz | $4,367.5/oz | Base case: consolidation between the breakout shelf and the 200-day/round-number resistance zone. |
| Retest | 22% | $4,020/oz | $4,250/oz | $4,135/oz | A failed near-term hold tests the July/August base without yet breaking the broader long thesis. |
| Bear | 10% | $3,700/oz | $4,020/oz | $3,860/oz | A decisive shelf failure, firmer real yields, and de-escalation unwind the hedge bid. |

| EV field | Value |
| --- | --- |
| Arithmetic status | ✅ CHECKED |
| Probability sum | 1 |
| Stated EV | $4,328.6/oz |
| EV versus spot | -1.18% |

> Computed components: 0.28x4592.5=1285.9; 0.40x4367.5=1747.0; 0.22x4135=909.7; 0.10x3860=386.0; total 4328.6. EV-vs-spot contributions are Rally +1.3558%, Range -0.1178%, Retest -1.2325%, Bear -1.1880%, sum -1.1825%. The modal Range midpoint is slightly below spot and downside distance carries the sign: geometry-driven, risk-adjusted, not a directional forecast. Trailing two-week change is +8.18%. D4 moves from the 0-5 baseline 10/30/35/25 to 28/40/22/10 because a realized trend-repair event exists; deviations over 10 points are tied to that breakout/MA repair. Active downtrend: NO. EV calibration: negative-sign streak 12 distinct report dates; 11 resolved outcomes, 6 correct/5 contradicted (54.5% hit), so the majority-contradiction tripwire is not met. Prior EV -1.02% versus realized +0.0068% was wrong.

## 5. Deployment strategy

**Deployed:** 0% · **dry powder:** 100% · **throttle released:** no

| Phase | Size | State | Deployed | Entry | Stop | Prior stop | Time stop | Prior time stop | Channel | Channel regime | Canonical tag | Decision rationale |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1A | 10% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1A-GOLD-20260815-1210 | Adjusted 4 <8; 1/8 gates <3 and 1 [V] <2. Cancel all resting PAXG buys; no ladder is authorized. |
| 1B | 15% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P1B-GOLD-20260815-1210 | Adjusted 4 <11; prerequisite phase attribution is absent and the holding is UNTAGGED. |
| 2 | 30% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P2-GOLD-20260815-1210 | Adjusted 4 <15 and the score line is above the current attainable ceiling of 14; no forward catalyst is named for this phase. |
| 3 | 45% | 🔒 LOCKED | no | — | — | — | — | — | — | — | FK-P3-GOLD-20260815-1210 | Mechanical 5 <17, the line is above the current attainable ceiling, and no analyst channel may unlock Phase 3. |

## 6. Position, custody, and execution controls

| Position field | Value |
| --- | --- |
| Status | ✅ FRESH |
| Asset | GOLD |
| Quantity | 1.3293894 |
| Dry powder | $10,741.5780 |
| Basis reliable | yes |
| Average cost | $4,210.5024215893 |
| Total cost basis | $5,597.397287935146 |
| Custody | ✅ RECONCILED |
| Attribution | ⚠️ UNTAGGED |
| Active tags | None |

### Custody reconciliation

| Field | Value |
| --- | --- |
| Interpretation | The user confirms the live balance is current and correct; the replay difference remains a ledger defect, not inferred off-venue custody. |
| Ledger asset | PAXG |
| Live qty | 1.3293894 |
| Mismatch qty | 0.1736 |
| Native ledger status | UNEXPLAINED |
| Reconciliation method | USER_ATTESTATION |
| Requested asset | GOLD |
| Status | ✅ RECONCILED |
| Trade derived qty | 1.5029894 |
| User attested current | Yes |

### Cost basis

| Field | Value |
| --- | --- |
| Avg cost usd | $4,210.5024215893 |
| Note | Basis is separately reported reliable by the ledger. |
| Reliable | Yes |
| Total cost usd | $5,597.397287935146 |

### Phase attribution

| Field | Value |
| --- | --- |
| Active tags | None |
| Open deals | 2 |
| Phase eligibility effect | Cannot infer a phase or unlock the next tranche from quantity/timing. |
| Status | ⚠️ UNTAGGED |

### Position P&L

| Field | Value |
| --- | --- |
| Fee assumption pct | 0.10% |
| Mark source | independent PAXG median, not the snapshot mark |
| Market value usd at paxg median | 5812.436098044 |
| Realized usd | $1,418.064989674 |
| Unrealized usd at paxg median | 215.038810109 |

> **Position reconciliation:** Prior report treated the book as flat under expiry. User attestation now confirms 1.3293894 PAXG, avg cost 4210.50, and 10741.58 stablecoin dry powder. The live quantity wins for this report, while the unexplained 0.1736 PAXG replay gap and UNTAGGED attribution remain explicit. GOLD is held as PAXG, not bullion.

### Open futures

- None recorded.

### Position controls

| Control status | Required | Primary action |
| --- | --- | --- |
| 🔵 OPEN | yes | **CANCEL_RESTING_BUYS_AND_HOLD** — No exit/trim trigger is live, but all four FK phases are locked and five resting buy limits would add outside authorization. |

### Selected control plan

| Field | Value |
| --- | --- |
| Catastrophic | After buy cancellation, use PAXG 3800 as the unchanged catastrophic trigger; a stop-limit implementation may use trigger 3800/limit 3780, with gap/slippage risk acknowledged. |
| Certainty | best-available-not-absolute |
| Compound | Monitor two consecutive weekly closes below PAXG 3850 while mechanical score remains <8; only then exit under the compound thesis rule. |
| Immediate | Cancel BUY limits at 3909/3806/3755/3703/3624 totaling 2948.934 USD. |
| Orders changed | No |
| Primary | HOLD current PAXG; NO ADD |

### Veto state

| Field | Value |
| --- | --- |
| Active | Yes |
| Reasons | Catastrophic 3800 is not strictly below the deepest live buy floor 3624 while those orders remain active.; No ordinary price-only shelf/ATR stop may replace the mechanical compound thesis stop.; D6 prohibits lowering the 3800 catastrophic anchor or rolling the expired checkpoint later without a named exception. |
| Resolution | Cancel all five resting buys first; do not lower the catastrophic stop to accommodate unauthorized orders. |

### Ratchet ledger

| Field | Value |
| --- | --- |
| Catastrophic current | 3800 |
| Catastrophic prior | 3800 |
| Checkpoint current | 2026-08-07 expired; not rolled later |
| Checkpoint prior | 2026-08-07 |
| Compound floor current | 3850 |
| Compound floor prior | 3850 |
| Migration | None |
| Score line current | 8 |
| Score line prior | 8 |
| Status | ✅ PASS |

### Risk and concentration

| Field | Value |
| --- | --- |
| Current paxg weight pct | 28.83% |
| Event risk | FOMC minutes August 19 at 14:00 ET; geopolitical weekend gaps remain possible. |
| Gap risk | A stop-limit can miss in a gap; market-stop support and venue liquidity must be checked at placement. |
| Issuer custody risk | PAXG is a tokenized allocated-gold claim, not physical bullion in the user's possession. |
| Weight if all buys fill pct before market move | 43.45 |

### Execution audit

| Field | Value |
| --- | --- |
| Instrument | PAXGUSDT/PAXGUSD |
| Orders changed | No |
| Ratio verified | PAXG median 0.1858% below frozen GC close |
| Stop zone coherence after cancel | Yes |
| Stop zone coherence current | No |
| Stop zone test after cancel | No active buy-zone floor = N/A/pass for protection placement |
| Stop zone test current | 3800 strictly below 3624 = FAIL |
| Trigger semantics | Compound uses weekly close twice plus mechanical score; catastrophic is price-only; structural and volatility levels are alerts only. |
| Underlying reference | GC=F/MGC=F |

### Liquidation zone

| Field | Value |
| --- | --- |
| Reason | No futures or leveraged PAXG position is present. |
| Status | — NOT_APPLICABLE |

### Control-level P&L

| Field | Value |
| --- | --- |
| Catastrophic realized P&L after 0.1% fee (USD) | -$550.77 |
| Compound realized P&L after 0.1% fee (USD) | -$484.37 |
| Market to catastrophic loss (% of portfolio) | 3.77 |
| Market to catastrophic loss (USD) | $760.76 |
| Market to compound loss (% of portfolio) | 3.44 |
| Market to compound loss (USD) | $694.29 |

### Candidate board summary

| Field | Value |
| --- | --- |
| Data as of | 2026-08-15T16:02:00Z |
| Phrase | no level is absolutely best; this is the best available control under current evidence. |
| Primary action | HOLD |

#### Candidate board

| Candidate | Score | Veto | Dimensions | Reason |
| --- | --- | --- | --- | --- |
| Actual venue state: no protective sell, five BUY limits | — | yes | — | Not a protective control and conflicts with locked phases. |
| Prior compound: two weekly closes below PAXG 3850 AND mechanical score <8 | 9 | no | Coherence: 2; Event: 1; Execution: 1; Noise: 2; Thesis: 3 | Correct compound semantics; manual monitoring is required. |
| Prior catastrophic PAXG 3800 after cancelling the buy ladder | 9 | no | Coherence: 2; Event: 1; Execution: 2; Noise: 2; Thesis: 2 | Best available catastrophic tier, unchanged under D6, execution-native and below no active buy floor after cancellation. |
| PAXG 4020 daily-close structural shelf | — | yes | — | Useful alert, but vetoed as a whole-book price-only replacement for the compound stop. |
| PAXG about 4099 from shelf/2xADR volatility control | — | yes | — | Ordinary noise control is too tight for whole-book catastrophic protection and would replace compound semantics. |
| EXIT NOW | 4 | no | Coherence: 0; Event: 0; Execution: 2; Noise: 2; Thesis: 0 | Rejected: no Section 7 trigger or narrative break is active. |

### Venue order state

| Field | Value |
| --- | --- |
| Locked notional (USD) | $2,948.934 |
| Orders changed | No |
| Current protective sell | — |
| Recommended sequence | Cancel all five BUY limits; Confirm no buy order remains; Install or retain catastrophic PAXG protection only after coherence passes |

#### Current buy orders

| Side | Type | Price | Quantity | Notional |
| --- | --- | --- | --- | --- |
| BUY | LIMIT | $3,909 | 0.0639 | $249.7851 |
| BUY | LIMIT | $3,806 | 0.1313 | $499.9278 |
| BUY | LIMIT | $3,755 | 0.1597 | $599.6735 |
| BUY | LIMIT | $3,703 | 0.162 | $599.886 |
| BUY | LIMIT | $3,624 | 0.2759 | $999.6616 |

### Trim / exit ladder

| Field | Value |
| --- | --- |
| Status | • DORMANT_CONDITIONAL_TRIMS |
| Quantity check | 0.33234735 + 0.6646947 = 0.99704205 <= 1.3293894 |
| Remaining after both PAXG trims | 0.33234735 |

#### Alert-only levels

| Price | Reason |
| --- | --- |
| 4485-4500 | 200-day/round-number resistance, but no Section 7 trigger currently authorizes a trim |

#### Conditional targets

| Condition | Execution | Quantity | Target price | Expected P&L | Share | Price note |
| --- | --- | --- | --- | --- | --- | --- |
| Mechanical score falls to 4 or lower, completing a >=6-point drop from the campaign peak of 10 | market/LIFO after trigger | 0.33234735 | $4,372.26 | $52.31 | 25% | Indicative current executable median; use live price when the trigger fires |
| Price reaches the within-10%-of-ATH threshold near 5027.58 with a vertical 30-day return | limit/LIFO after trigger | 0.6646947 | $5,030 | $541.37 | 50% | — |

### Framework risk controls

### Carry

| Field | Value |
| --- | --- |
| Dry-powder yield | 3.70% |
| Long borrow cost | none observed |
| Note | Idle cash has measurable T-bill opportunity cost. |
| Status | ✅ AVAILABLE |
| Veto | No |

### Concentration

| Field | Value |
| --- | --- |
| Action | Cancel resting buys to prevent unauthorized concentration increase. |
| Current portfolio weight | 28.83% |
| Potential portfolio weight if buys fill | 43.45% |
| Status | ✅ AVAILABLE |

### Ratchet

| Field | Value |
| --- | --- |
| Catastrophic | 3800 unchanged |
| Checkpoint | 2026-08-07 not extended |
| Compound | 3850 AND mechanical<8 unchanged |
| Parameters changed | No |
| Status | ✅ PASS |

### Stops

| Field | Value |
| --- | --- |
| Catastrophic paxg | 3800 |
| Coherence after cancel | Yes |
| Coherence current | No |
| Compound close count | 2 |
| Compound floor paxg | 3850 |
| Compound score line mechanical below | 8 |
| Current deepest buy floor | 3624 |
| Reason | Cancel unauthorized buys; do not widen protection. |
| Status | ✅ AVAILABLE |
| Stop realignment owed | No |

### Time stops

| Field | Value |
| --- | --- |
| Action | Reassess now; D6 prohibits rolling the same-campaign clock later. FOMC minutes are a watch event, not a replacement stop. |
| Prior checkpoint | 2026-08-07 |
| State | ⚠️ expired |
| Status | ⚠️ DATA_LIMITED |

## 7. Analyst rationale

**Summary:** HOLD the user-confirmed 1.3293894 PAXG, add nothing, and cancel all five resting buy limits. Adjusted score is 4/20 with 1/8 gates; the real holding is UNTAGGED and cannot be mapped into an FK phase. Protection remains 3850 plus mechanical<8 on two weekly closes, with catastrophic 3800 only coherent after the unauthorized buy ladder is removed.

**Bull case:** Gold's realized structure is constructive: the 200-day slope is +0.29%, PAXG is above its 20/50/100-day averages, GLD holdings rose 8.52t in a week and 20.79t in a month, Chinese official buying continued, and the 10-year real yield eased to 2.39%. IF PAXG holds the 4220-4250 breakout shelf through the August 19 FOMC minutes and then closes a week above 4485, THEN the Rally band gains weight; a daily close below 4020 is the structural falsifier.

**Bear case:** This is not a fallen-knife entry: weekly RSI is 49.95, drawdown is only 21.59%, managed-money net longs increased by 6896, and volume is only at the 74.4th percentile. The position also carries PAXG issuer/custody risk and an unresolved 0.1736 PAXG ledger replay gap. Five live buys would lift the position from roughly 28.83% to 43.45% of portfolio value before price effects even though every FK phase is locked.

**Rationale:** LIVE DATA AND SCORE — Canonical underlying is the low-confidence frozen GC/MGC Friday close 4380.40; execution PAXG is high-confidence at median 4372.26 across Binance/Kraken/CoinGecko, a 0.1858% discount to GC. Legs are sentiment fallback 2, momentum 0 (weekly RSI 49.95), valuation 0 (21.59% drawdown; gold/BTC RV30 ratio 1.02x blocks the low-vol adaptation), capitulation 0 (volume not top-decile, COT longs building, GLD holdings rising), holder 3 (GLD and official-sector accumulation). Mechanical 5; D1 -1; adjusted 4.
> GATES AND VACUITY — One of eight active gates is lit: holder accumulation. Gate 5 is N/A. Each dark gate has a relight path in gates.measurement_basis. The attainable ceiling is 14 on today's structural pins: sentiment 2, momentum 4, valuation 2 absent a confirmed COT flush, capitulation 3, holder 3. Phase 2 >=15, Phase 3 mechanical>=17, and Override mechanical>=15 are VACUOUS-FALSE; Override is still explicitly evaluated and does not fire because there is no tagged prior phase, mechanical is 5, and no fresh lower-low/deep-value condition exists. Phase 1A and 1B lines are LIVE-FALSE. The compound mechanical<8 key is LIVE-TRUE on two distinct report dates, not yet a four-date vacuity state. Score is the binding axis on every phase.
> PROBABILITY AND EV — D4 sets Rally/Range/Retest/Bear at 28/40/22/10 versus the 0-5 baseline 10/30/35/25 because a realized trend-repair event exists. Active downtrend: NO. EV is 4328.60, -1.18% versus GC, while trailing two-week price is +8.18%. The negative sign is carried by Retest/Bear distance, so it is geometry-driven and cannot justify the stance. The negative-sign EV streak is 12 report dates with 11 resolved outcomes and a 54.5% hit rate; no majority-contradiction tripwire. The prior -1.02% forecast was contradicted by a +0.0068% move.
> POSITION RECONCILIATION — The prior report's cold-start flat assumption is superseded by the user's explicit confirmation: 1.3293894 PAXG, average cost 4210.5024, cost 5597.40, market value 5812.44, and independent-mark unrealized gain about 215.04. Stablecoin dry powder is 10741.58, including 2948.934 locked in five resting PAXG buys. The ledger's native age test fails because holdings_as_of is old, and custody is UNEXPLAINED by 0.1736 PAXG; the user attestation authorizes the live balance as current, but it does not cure the accounting defect or UNTAGGED phase attribution. PAXG is tokenized gold with issuer/custody and premium/discount risks absent from bullion.
> OPEN-POSITION BEST-LEVEL AUDIT — Primary action is HOLD, not EXIT/TRIM/ADD. No level is absolutely best; this is the best available control under current evidence. Current venue state has no protective sell and five BUY limits at 3909, 3806, 3755, 3703 and 3624. The current stop-zone check FAILS because catastrophic 3800 is not strictly below live buy floor 3624. The valid resolution is cancellation, not a D6-prohibited stop widening. After cancellation, retain compound protection at two weekly closes below PAXG 3850 plus mechanical<8, and catastrophic PAXG 3800 as the unchanged gap tier. Market-to-3800 exposure is about 760.76 or 3.77% of portfolio; a 3800 exit realizes about -550.77 after a 0.10% fee. The prior August 7 checkpoint expired and is not rolled later. No orders were changed by this report.
> EXIT/TRIM — Campaign mechanical peak is 10 and current is 5, a five-point decline; the >=6-point 25% trim is one point away, not active. Weekly RSI is below 70, price remains more than 10% below ATH, mechanical is above 3, no qualifying post-inflow ETF break is present, and no narrative break is established. Conditional ladder: at mechanical<=4, trim 0.33234735 PAXG (25%) LIFO at the live executable price; at approximately 5027.58/5030 with a vertical 30-day return, trim 0.6646947 PAXG (50%). The 4485-4500 resistance is alert-only without a Section 7 trigger.
> ANALYST READ AND D1 LEDGER — D1 remains -1.0, freshly earned by two non-leg factors: (1) realized trend repair, with rising 200-day slope and PAXG above 20/50/100-day averages; (2) macro/geopolitical convexity, with Hormuz attacks and Brent +5.95% over five sessions sustaining hedge demand even as real yields eased. Falsifier: PAXG daily close below 4020 together with real yield >2.50% and Brent normalization. D2 was considered and declined because score is not met and gates are short by more than one. D4 is active via 28/40/22/10. Ledger entry: 2026-08-15 | D1 | -1.0 | no capital | no D5 stop | falsifier above | LIVE | PnL N/A. D4 probabilities are LIVE and falsified by a weekly >4485 continuation or daily <4020 break.
> BULL/BEAR SCORECARD — Bull price signals (5): rising 200-day slope; PAXG above 20/50/100-day averages; GLD holdings rising; real yields eased; geopolitical/official-sector demand. Bear/FK signals (5): no fear gauge signal; weekly RSI not exhausted; valuation not cheap; COT longs building; no volume/ETF capitulation. Balanced price evidence plus |EV|<2% keeps the Verdict-Confidence Collar ON: no bottom/top or resolved-regime claim.
> EXECUTABLE ACTIONS — 1) Cancel all five resting PAXG BUY limits and confirm locked stablecoins release. 2) Hold 1.3293894 PAXG; authorize no FK addition and attach no phase tag retroactively. 3) After cancellation, verify a venue-native catastrophic order at trigger 3800 (illustrative stop-limit 3780) and separately monitor the 3850 weekly-close-plus-score compound rule. 4) Reconcile the unexplained 0.1736 PAXG ledger gap. 5) Re-score after FOMC minutes on August 19 and the next COT print. Cash is a position; patience is alpha, and its benchmark yield is 3.70%.
> THE PATTERN — IF PAXG holds 4220-4250 through the FOMC minutes and closes a week above 4485, THEN Rally probability rises, but no FK add follows without score/gates. IF mechanical falls to 4 or less, THEN trim 25% at the live executable price. IF two weekly closes print below 3850 while mechanical remains <8, THEN the compound exit fires; 3800 remains the catastrophic gap tier.

**Primary action:** **CANCEL_RESTING_BUYS_AND_HOLD** — The open PAXG position has no live exit trigger, but all accumulation phases are locked and the resting buys violate the score/gate decision.

### Decision-support arguments

| Argument | Reading |
| --- | --- |
| Exit status | None; nearest live trigger is mechanical <=4 for a 25% trim. |
| Position reconciliation | User attestation controls freshness; ledger age/custody defects remain disclosed. |
| Score ceiling | 14 under current structural pins; higher phase lines are vacuous-false. |
| Tier1 calendar | FOMC minutes 2026-08-19 14:00 ET only; no NFP/CPI/PCE/FOMC decision in next five trading sessions. |

### Discretion ledger

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-08-15 | D1 | -1.0 | none | N/A | PAXG <4020 plus real yield >2.50% and Brent normalization | ✅ LIVE | — |

## 8. Companion framework and cross-validation

| Check | Status | Score / relationship | Reading |
| --- | --- | --- | --- |
| Companion framework | ✅ AVAILABLE | flying_rocket · 1/20 · 0 gates | Computed same-timestamp non-crypto adaptation: euphoria 0, momentum 0 (weekly RSI 49.95), valuation 1 (21.59% below ATH), distribution 0, vulnerability 0; mechanical/raw/adjusted 1. Gates 4/6/9 are N/A, 0/6 active gates pass, Channel A is ineligible because gold is >20% below ATH and Channel B is unavailable for non-crypto. Funding/borrow/squeeze are NOT APPLICABLE, not silently zeroed. The prior report's eyeballed approximately-8 companion was noncompliant and is corrected here. Standalone report trigger: not owed (FK 6->4 crosses no unlock, FR<9, phase-of-cycle cap still binds, no >=100M crypto short-liquidation analogue). |
| Cross-validation | ✅ CONSISTENT | FK 4 versus computed FR 1; structurally consistent, cap-bound | Gold is 21.59% below its one-year ATH, so the FR phase-of-cycle cap makes both>=12 unfalsifiable by construction. Neither framework is near an entry threshold, and no action is licensed by cross-validation. |

## 9. Watchlist, events, falsifiers, and changes

### Watchlist

| Item | Status | Trigger |
| --- | --- | --- |
| Cancel PAXG resting buy orders | ✅ AVAILABLE | Immediate: all FK phases are locked; confirm the five orders totaling 2948.934 USD are gone. |
| Mechanical trim trigger | ✅ AVAILABLE | Mechanical score <=4 would complete the >=6-point drop from the campaign peak of 10 and require a 25% trim. |
| Compound thesis stop | ✅ AVAILABLE | Two consecutive weekly closes below PAXG 3850 while mechanical score remains <8. |
| COT washout | ✅ AVAILABLE | Managed-money/non-commercial net-long decline of 20-30K contracts or >=15%, then held/extended on the following weekly print. |
| Gold ETF flow reversal | ✅ AVAILABLE | Sustained multi-region outflow among the worst trailing 12 months, corroborated by GLD tonnes. |
| Valuation trim | ✅ AVAILABLE | Gold/PAXG within 10% of 5586.2 ATH (about 5027.58) with a vertical 30-day return. |

### Events

| Date / time | Event | Status | Impact |
| --- | --- | --- | --- |
| 2026-08-19T18:00:00Z | FOMC July 28-29 meeting minutes | ✅ AVAILABLE | Tier-1 in the next five trading sessions; hawkish detail can lift real yields and pressure gold, dovish detail can support the breakout. |
| 2026-08-18T12:30:00Z | US Import and Export Prices | ✅ AVAILABLE | Secondary inflation input; not one of the mandatory tier-1 NFP/CPI/PCE/FOMC set. |
| 2026-08-21 | Next CFTC COT release | ✅ AVAILABLE | Determines whether managed-money long building continues or a qualifying washout starts. |
| 2026-08-14 | Strait of Hormuz security risk | ✅ AVAILABLE | Weekend gap and energy-inflation risk; geopolitical shocks create the best entries after the fog clears, not during it. |
| 2026-08-15 | Tier-1 completeness check | ✅ AVAILABLE | August 17-21 contains FOMC minutes only among NFP, CPI, PCE and FOMC decisions/minutes. Next PCE is August 26; no NFP/CPI or FOMC decision is in-window. |

### Falsifiers

| Claim | Condition | Status |
| --- | --- | --- |
| D1 -1 remains justified by trend repair and macro hedge demand | A PAXG daily close below 4020 together with 10y real yield above 2.50% and Brent normalization retires the adjustment. | ✅ AVAILABLE |
| Hold is preferable to immediate exit | Two weekly closes below PAXG 3850 while mechanical score <8, a catastrophic print through 3800, or a true narrative/issuer break changes the action to exit. | ✅ AVAILABLE |
| No trim trigger is active | Mechanical score <=4, price >=5027.58 with a vertical 30-day return, or another Section 7 condition activates a trim. | ✅ AVAILABLE |
| Range is the modal four-week path | A weekly close above 4485 with continued GLD accumulation shifts probability toward Rally; a daily close below 4020 shifts it toward Retest/Bear. | ✅ AVAILABLE |

### Change log

| Field | Previous | Current | Reason |
| --- | --- | --- | --- |
| Score.adjusted | 6 | 4 | Completed weekly RSI rose 38.98->49.95, moving momentum 2->0; other legs and D1 are unchanged. |
| Score.mechanical | 7 | 5 | Momentum exhaustion credit rolled off on the completed weekly series. |
| Position | cold-start flat assumption | 1.3293894 PAXG at user-attested current snapshot | The user explicitly confirmed the PAXG snapshot is not outdated and correct; untagged attribution and custody mismatch remain. |
| Companion fr | approximately 8, eyeballed | 1, computed | Corrected to the mandatory same-timestamp deterministic non-crypto adaptation; the earlier estimate was not auditable. |
| COT managed money net | 130766 as of 2026-08-04 | 137662 as of 2026-08-11 | Net longs built by 6896, so no washout credit. |
| GLD tonnes | flow estimates from secondary sources | 1023.24; +8.52t week and +20.79t month | Official issuer archive replaces the prior secondary flow estimate. |
| Venue orders | no active ladder stated | five live PAXG BUY limits totaling 2948.934 USD | Fresh order-level snapshot reveals a coherence conflict; cancel recommendation added. |
| D1 | -1 | -1 | Freshly re-argued from trend repair and macro/geopolitical convexity; no decay carry-forward. |

## 10. Substitutions, source register, and provenance

### Asset substitutions

| Field | Original | Substitute | Reason |
| --- | --- | --- | --- |
| Position asset | physical/spot GOLD | PAXG | Hard Rule 8 ledger alias; disclosed issuer/custody and premium/discount risk. |
| Gold holder behavior | crypto LTH supply and exchange reserves | official GLD tonnes plus central-bank accumulation | Asset-appropriate physical-holder evidence; GLD rose on weekly and monthly horizons and reported Chinese official buying continued in July. |
| Correlation asset | spot GOLD daily closes | GLD daily closes | Official liquid gold proxy paired with FRED SP500 closes for a reproducible 30-session calculation. |
| Gold capitulation b | perpetual funding | CFTC managed-money washout | Codified gold substitution; current net longs rose, so no credit. |
| Gold capitulation c | crypto ETF/exchange outflow | physical-gold ETF outflow spike | Codified gold substitution; GLD holdings rose, so no credit. |

### Sources

| ID | Name | Kind | As of | Retrieved | Note / link |
| --- | --- | --- | --- | --- | --- |
| ap_hormuz | Associated Press Strait of Hormuz tanker attacks | news | 2026-08-14 | 2026-08-15T15:57:00Z | Two UAE tankers were attacked; geopolitical and energy risk remains live.<br>[Open source](https://apnews.com/article/e8565c608ac5283ec8103c85df924b13) |
| axios_gold | Axios gold rally and China reserve-buying report | news | 2026-08-13 | 2026-08-15T15:57:00Z | Reports nearly 20 tonnes of Chinese official buying in July and the recent gold high.<br>[Open source](https://www.axios.com/2026/08/13/gold-inflation-rates-iran) |
| bea | BEA Personal Income and Outlays calendar/release | official macro release | 2026-07-30 | 2026-08-15T15:55:00Z | Next PCE release is August 26, outside the five-session window.<br>[Open source](https://www.bea.gov/news/2026/personal-income-and-outlays-june-2026) |
| binance | Binance PAXGUSDT ticker and daily klines | venue | 2026-08-15T16:02:00Z | 2026-08-15T16:02:00Z | Execution-instrument quote and contract-native structure.<br>[Open source](https://api.binance.com/api/v3/ticker/bookTicker?symbol=PAXGUSDT) |
| bls_calendar | BLS August 2026 release calendar | official calendar | 2026-08-15 | 2026-08-15T15:55:00Z | Used to verify no NFP or CPI release in the next five trading sessions.<br>[Open source](https://www.bls.gov/schedule/2026/08_sched_list.htm) |
| cftc | CFTC Disaggregated Futures COT 2026 archive | regulator | 2026-08-11 | 2026-08-15T15:47:00Z | COMEX Gold managed-money positioning.<br>[Open source](https://www.cftc.gov/files/dea/history/fut_disagg_txt_2026.zip) |
| coingecko | CoinGecko PAX Gold USD | aggregator | 2026-08-15T16:02:00Z | 2026-08-15T16:02:00Z | Third synchronized PAXG quote.<br>[Open source](https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd&include_last_updated_at=true) |
| fed_calendar | Federal Reserve August 2026 calendar | official calendar | 2026-08-15 | 2026-08-15T15:55:00Z | FOMC minutes release verified for August 19 at 14:00 ET.<br>[Open source](https://www.federalreserve.gov/newsevents/2026-august.htm) |
| gld | SPDR Gold Shares historical archive | issuer | 2026-08-13 | 2026-08-15T15:50:00Z | Official daily tonnes held.<br>[Open source](https://www.spdrgoldshares.com/usa/gld/) |
| kraken | Kraken PAXGUSD ticker | venue | 2026-08-15T16:02:00Z | 2026-08-15T16:02:00Z | Independent execution-instrument quote.<br>[Open source](https://api.kraken.com/0/public/Ticker?pair=PAXGUSD) |
| ledger | Personal-accounting PAXG position snapshot | user-attested ledger | 2026-08-15T16:03:00Z | 2026-08-15T16:03:00Z | The file's holdings_as_of clock is old, but the user explicitly attested in this task that the PAXG snapshot is current and correct.<br>[Open source](exports/position-snapshot-2026-08-15_09-30-02-628Z.json) |
| paxos | Paxos PAX Gold documentation | issuer | 2026-08-15 | 2026-08-15T15:59:00Z | PAXG is a tokenized allocated-gold claim and carries issuer/custody and premium/discount risk.<br>[Open source](https://www.paxos.com/paxgold) |
| snapshot | Deterministic GOLD/BTC/macro snapshot | computed | 2026-08-15T16:01:48.468Z | 2026-08-15T16:01:48.468Z | Yahoo GC=F/MGC=F and FRED series; full source metadata is preserved in the snapshot.<br>[Open source](data/runs/20260815-1601-fecbe187/snapshot.json) |
| wgc | World Gold Council gold ETF and central-bank data | industry primary dataset | 2026-07-17 | 2026-08-15T16:12:00Z | June global ETF data is lagged and used as context, not as a current gate input.<br>[Open source](https://www.gold.org/goldhub/research/gold-etfs-holdings-and-flows/2026/07) |

### Report timestamps

| Timestamp | Value |
| --- | --- |
| Data as of | 2026-08-15T16:01:48.468Z |
| Generated at | 2026-08-15T16:10:25Z |
| Report at | 2026-08-15T16:10:00Z |
| Timezone | America/New_York |

### Run provenance

| Field | Value |
| --- | --- |
| Report ID | gold_fallen_knives_20260815_1210 |
| Report filename | gold_fallen_knives_20260815_1210.json |
| Run ID | 20260815-1601-fecbe187 |
| Snapshot ID | sha256:fecbe1879215ef4239d1fea5690a9528736d5490bd84f3485bb958b1fdb00c77 |
| Prior report | gold_fallen_knives_20260810_0020 |
| Prior report hash | f96dda4e2e72f73490acb1cd8d4309c03b62ae36ad68dd5f25058865e3613024 |

#### Tool hashes

| Tool | Hash |
| --- | --- |
| compute | sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb |
| fetch | sha256:7296bd05f390f9004cf7110ac249df36f87dfb28726356b208bd7519a85750ba |
| snapshot | sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96 |

## 11. Phase registry and canonical tags

| Phase | Decision | Canonical tag | Instrument class |
| --- | --- | --- | --- |
| 1A | 🔒 LOCKED | FK-P1A-GOLD-20260815-1210 | non_crypto_derivative |
| 1B | 🔒 LOCKED | FK-P1B-GOLD-20260815-1210 | non_crypto_derivative |
| 2 | 🔒 LOCKED | FK-P2-GOLD-20260815-1210 | non_crypto_derivative |
| 3 | 🔒 LOCKED | FK-P3-GOLD-20260815-1210 | non_crypto_derivative |

**Registry:** report-phase-registry/2 · ✅ REGISTERED · instrument class non_crypto_derivative
**Active tags:** None
**Reserved tags:** FK-P1A-GOLD-20260815-1210, FK-P1B-GOLD-20260815-1210, FK-P2-GOLD-20260815-1210, FK-P3-GOLD-20260815-1210

## 12. Canonical machine payload

The following block is preserved exactly for deterministic linting and machine consumers; the sections above are the human reading view.

```json machine
{"change_log":[{"current":4,"field":"score.adjusted","previous":6,"reason":"Completed weekly RSI rose 38.98->49.95, moving momentum 2->0; other legs and D1 are unchanged."},{"current":5,"field":"score.mechanical","previous":7,"reason":"Momentum exhaustion credit rolled off on the completed weekly series."},{"current":"1.3293894 PAXG at user-attested current snapshot","field":"position","previous":"cold-start flat assumption","reason":"The user explicitly confirmed the PAXG snapshot is not outdated and correct; untagged attribution and custody mismatch remain."},{"current":"1, computed","field":"companion_fr","previous":"approximately 8, eyeballed","reason":"Corrected to the mandatory same-timestamp deterministic non-crypto adaptation; the earlier estimate was not auditable."},{"current":"137662 as of 2026-08-11","field":"COT managed-money net","previous":"130766 as of 2026-08-04","reason":"Net longs built by 6896, so no washout credit."},{"current":"1023.24; +8.52t week and +20.79t month","field":"GLD tonnes","previous":"flow estimates from secondary sources","reason":"Official issuer archive replaces the prior secondary flow estimate."},{"current":"five live PAXG BUY limits totaling 2948.934 USD","field":"venue orders","previous":"no active ladder stated","reason":"Fresh order-level snapshot reveals a coherence conflict; cancel recommendation added."},{"current":-1,"field":"D1","previous":-1,"reason":"Freshly re-argued from trend repair and macro/geopolitical convexity; no decay carry-forward."}],"companion_framework":{"framework":"flying_rocket","gates":0,"rationale":"Computed same-timestamp non-crypto adaptation: euphoria 0, momentum 0 (weekly RSI 49.95), valuation 1 (21.59% below ATH), distribution 0, vulnerability 0; mechanical/raw/adjusted 1. Gates 4/6/9 are N/A, 0/6 active gates pass, Channel A is ineligible because gold is >20% below ATH and Channel B is unavailable for non-crypto. Funding/borrow/squeeze are NOT APPLICABLE, not silently zeroed. The prior report's eyeballed approximately-8 companion was noncompliant and is corrected here. Standalone report trigger: not owed (FK 6->4 crosses no unlock, FR<9, phase-of-cycle cap still binds, no >=100M crypto short-liquidation analogue).","score":1,"status":"AVAILABLE"},"cross_validation":{"rationale":"Gold is 21.59% below its one-year ATH, so the FR phase-of-cycle cap makes both>=12 unfalsifiable by construction. Neither framework is near an entry threshold, and no action is licensed by cross-validation.","relationship":"FK 4 versus computed FR 1; structurally consistent, cap-bound","status":"CONSISTENT"},"data_gaps":[{"field":"gold_sentiment_daily","impact":"Framework fallback leg=2; no gate-1 credit from sentiment. GVZ/PHYS remain unscored context.","source_ids":["snapshot"],"status":"NOT_COVERED"},{"field":"live_underlying_bullion_panel","impact":"Weekend GC/MGC closes lower confidence for underlying; PAXG orders use the synchronized PAXG panel instead.","source_ids":["snapshot","binance","kraken","coingecko"],"status":"DATA_LIMITED"},{"field":"global_gold_etf_july","impact":"June WGC data is not used to credit gate 4; current official GLD tonnes supply the live US-fund read.","source_ids":["wgc","gld"],"status":"STALE"},{"field":"ledger_custody_reconciliation","impact":"The user attests that live PAXG quantity is correct, but the 0.1736 PAXG replay mismatch remains an accounting defect and phase attribution remains unknown.","source_ids":["ledger"],"status":"DATA_LIMITED"}],"deployment":{"deployed_pct":"0","dry_pct":"100","throttle_released":false,"tranches":[{"channel":null,"deployed":false,"entry_price":null,"pct":"10","phase":"1A","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 4 <8; 1/8 gates <3 and 1 [V] <2. Cancel all resting PAXG buys; no ladder is authorized.","state":"LOCKED","stop":null,"tag":"FK-P1A-GOLD-20260815-1210","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"15","phase":"1B","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 4 <11; prerequisite phase attribution is absent and the holding is UNTAGGED.","state":"LOCKED","stop":null,"tag":"FK-P1B-GOLD-20260815-1210","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"30","phase":"2","prior_stop":null,"prior_time_stop":null,"rationale":"Adjusted 4 <15 and the score line is above the current attainable ceiling of 14; no forward catalyst is named for this phase.","state":"LOCKED","stop":null,"tag":"FK-P2-GOLD-20260815-1210","time_stop":null},{"channel":null,"deployed":false,"entry_price":null,"pct":"45","phase":"3","prior_stop":null,"prior_time_stop":null,"rationale":"Mechanical 5 <17, the line is above the current attainable ceiling, and no analyst channel may unlock Phase 3.","state":"LOCKED","stop":null,"tag":"FK-P3-GOLD-20260815-1210","time_stop":null}]},"ev":{"arithmetic_status":"CHECKED","note":"Computed components: 0.28x4592.5=1285.9; 0.40x4367.5=1747.0; 0.22x4135=909.7; 0.10x3860=386.0; total 4328.6. EV-vs-spot contributions are Rally +1.3558%, Range -0.1178%, Retest -1.2325%, Bear -1.1880%, sum -1.1825%. The modal Range midpoint is slightly below spot and downside distance carries the sign: geometry-driven, risk-adjusted, not a directional forecast. Trailing two-week change is +8.18%. D4 moves from the 0-5 baseline 10/30/35/25 to 28/40/22/10 because a realized trend-repair event exists; deviations over 10 points are tied to that breakout/MA repair. Active downtrend: NO. EV calibration: negative-sign streak 12 distinct report dates; 11 resolved outcomes, 6 correct/5 contradicted (54.5% hit), so the majority-contradiction tripwire is not met. Prior EV -1.02% versus realized +0.0068% was wrong.","probability_sum":1,"scenarios":[{"high":"4700","low":"4485","mid":"4592.5","name":"Rally","probability":0.28,"rationale":"Rising 200-day slope, PAXG above 20/50/100-day averages, and a hold above the breakout shelf."},{"high":"4485","low":"4250","mid":"4367.5","name":"Range","probability":0.4,"rationale":"Base case: consolidation between the breakout shelf and the 200-day/round-number resistance zone."},{"high":"4250","low":"4020","mid":"4135","name":"Retest","probability":0.22,"rationale":"A failed near-term hold tests the July/August base without yet breaking the broader long thesis."},{"high":"4020","low":"3700","mid":"3860","name":"Bear","probability":0.1,"rationale":"A decisive shelf failure, firmer real yields, and de-escalation unwind the hedge bid."}],"stated_ev":"4328.6","vs_spot_pct":"-1.18"},"events":[{"as_of":"2026-08-19T18:00:00Z","impact":"Tier-1 in the next five trading sessions; hawkish detail can lift real yields and pressure gold, dovish detail can support the breakout.","name":"FOMC July 28-29 meeting minutes","status":"AVAILABLE"},{"as_of":"2026-08-18T12:30:00Z","impact":"Secondary inflation input; not one of the mandatory tier-1 NFP/CPI/PCE/FOMC set.","name":"US Import and Export Prices","status":"AVAILABLE"},{"as_of":"2026-08-21","impact":"Determines whether managed-money long building continues or a qualifying washout starts.","name":"Next CFTC COT release","status":"AVAILABLE"},{"as_of":"2026-08-14","impact":"Weekend gap and energy-inflation risk; geopolitical shocks create the best entries after the fog clears, not during it.","name":"Strait of Hormuz security risk","status":"AVAILABLE"},{"as_of":"2026-08-15","impact":"August 17-21 contains FOMC minutes only among NFP, CPI, PCE and FOMC decisions/minutes. Next PCE is August 26; no NFP/CPI or FOMC decision is in-window.","name":"Tier-1 completeness check","status":"AVAILABLE"}],"evidence":{"correlation":{"as_of":"2026-08-13","confidence":"MEDIUM","rationale":"Computed over 29 paired daily log returns using GLD as the gold proxy and FRED SP500 closes; no >0.7 gate surcharge.","source_ids":["gld","snapshot"],"status":"AVAILABLE","unit":"Pearson correlation","value":"0.3128"},"cot":{"as_of":"2026-08-11","confidence":"HIGH","rationale":"Net longs rose by 6,896 week over week (148,634 long minus 10,972 short); positioning is building, not washing out.","source_ids":["cftc"],"status":"AVAILABLE","unit":"managed-money net-long contracts","value":"137662"},"gld_holdings":{"as_of":"2026-08-13","confidence":"HIGH","rationale":"Official GLD holdings rose 8.52t from August 6 and 20.79t from July 13.","source_ids":["gld"],"status":"AVAILABLE","unit":"tonnes","value":"1023.24"},"macro":{"as_of":"2026-08-14","confidence":"HIGH","rationale":"10y real yield 2.39% eased 4bp over five prints, but Brent rose 5.95% in five sessions to 88.52 and Hormuz risk is live.","source_ids":["snapshot","ap_hormuz"],"status":"AVAILABLE","unit":null,"value":"mixed"},"paxg_spot":{"as_of":"2026-08-15T16:02:00Z","confidence":"HIGH","rationale":"Median of synchronized Binance 4375.35, Kraken 4371.89, and CoinGecko 4372.26; 0.0791% panel spread.","source_ids":["binance","kraken","coingecko"],"status":"AVAILABLE","unit":"USD/PAXG","value":"4372.26"},"sentiment":{"as_of":"2026-08-15","confidence":"NONE","rationale":"No validated daily gold fear instrument exists; the measured framework fallback scores 2. GVZ and PHYS are disclosed context only.","source_ids":["snapshot"],"status":"NOT_COVERED","unit":"index","value":null},"underlying_spot":{"as_of":"2026-08-14 close","confidence":"LOW","rationale":"GC=F and MGC=F agree at 4380.40, but both are frozen Friday closes and there are zero synchronized live bullion quotes on Saturday.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/oz","value":"4380.4"},"valuation":{"as_of":"2026-08-14 close","confidence":"HIGH","rationale":"Gold RV30 22.38% versus BTC RV30 21.89% gives a 1.02x ratio, so the <=0.5x low-volatility adaptation is unavailable; standard <30% drawdown scores 0.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent drawdown from ATH","value":"21.59"},"volume_flush":{"as_of":"2026-08-14 close","confidence":"MEDIUM","rationale":"1491 contracts is below the top-decile flush threshold; no capitulation credit.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"two-year volume percentile","value":"74.4"},"weekly_rsi":{"as_of":"week ending 2026-08-09","confidence":"HIGH","rationale":"Wilder RSI-14 from 260 completed Monday-boundary weekly closes; >45 scores 0.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI-14","value":"49.95"}},"falsifiers":[{"claim":"D1 -1 remains justified by trend repair and macro hedge demand","condition":"A PAXG daily close below 4020 together with 10y real yield above 2.50% and Brent normalization retires the adjustment.","status":"AVAILABLE"},{"claim":"Hold is preferable to immediate exit","condition":"Two weekly closes below PAXG 3850 while mechanical score <8, a catastrophic print through 3800, or a true narrative/issuer break changes the action to exit.","status":"AVAILABLE"},{"claim":"No trim trigger is active","condition":"Mechanical score <=4, price >=5027.58 with a vertical 30-day return, or another Section 7 condition activates a trim.","status":"AVAILABLE"},{"claim":"Range is the modal four-week path","condition":"A weekly close above 4485 with continued GLD accumulation shifts probability toward Rally; a daily close below 4020 shifts it toward Retest/Bear.","status":"AVAILABLE"}],"gates":{"active":8,"alt_reading":{"binding_axis":{"1A":"score short 4; gates short 2 and [V] short 1","1B":"score short 7; gates short 4 and [V] short 2","2":"score short 11; gates short 5 and [V] short 2; score line also above attainable ceiling","3":"mechanical score short 12; gates short 6 and [V] short 3; score line above attainable ceiling"},"corr":"0.3128","corr_surcharge":false,"v_count":1},"measurement_basis":{"1":"DARK [V] — COT managed-money net long +6,896 WoW; relight requires a 20-30K or >=15% weekly washout held/extended on the next print.","2":"DARK [V] — completed weekly RSI 49.95; relight below 30.","3":"DARK [V] — none-by-construction for gold; low-vol valuation bands confer no gate credit.","4":"DARK [V] — GLD holdings +8.52t week/+20.79t month; relight requires sustained multi-region outflows among the worst trailing 12 months, corroborated by GLD.","5":"N/A [T] — Hash Ribbon does not apply to gold.","6":"DARK [T] — spot 53.33% above the 200-week SMA; relight requires price within 8%, roughly a 29.6% decline to the upper proximity boundary.","7":"DARK [V] — volume at the 74.4th percentile, below the top-decile flush bar.","8":"LIT [V] — official GLD tonnes rising on weekly and monthly horizons, with continued official-sector accumulation context.","9":"DARK/WATCH [T] — real yields eased, but Brent +5.95% and Hormuz attacks keep the macro catalyst mixed; relight requires a cleaner neutral-to-positive policy/geopolitical impulse."},"na":[5],"passed":[8],"thresholds":{"p1a":3,"p1b":5,"p2":6,"p3":7}},"identity":{"asset":"GOLD","date":"2026-08-15","filename":"gold_fallen_knives_20260815_1210.json","framework":"fallen_knives","local_time":"12:10","timezone":"America/New_York"},"market":{"ath":{"as_of":"2026-01-26","note":"One-year/all-time high in the deterministic history.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/oz","value":"5586.2"},"drawdown_pct":{"as_of":"2026-08-14 close","note":null,"source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"21.59"},"metrics":{"adr5":{"as_of":"2026-08-14","note":"Five full sessions, August 10-14","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD","value":"60.46"},"btc_rv30":{"as_of":"2026-08-15","note":"Gold/BTC ratio 1.02x","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"21.89"},"dry_powder_yield":{"as_of":"2026-08-14","note":"Three-month T-bill benchmark","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"3.70"},"gold_rv30":{"as_of":"2026-08-14","note":null,"source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent annualized","value":"22.38"},"ma200d":{"as_of":"2026-08-14","note":"Spot is 2.36% below; slope rising","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/oz","value":"4486.27"},"real_yield10y":{"as_of":"2026-08-13","note":"Down 0.04pp over five prints","source_ids":["snapshot"],"status":"AVAILABLE","unit":"percent","value":"2.39"},"sma200w":{"as_of":"2026-08-14","note":"Spot is 53.33% above","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/oz","value":"2856.88"},"weekly_rsi14":{"as_of":"week ending 2026-08-09","note":"260 completed closes","source_ids":["snapshot"],"status":"AVAILABLE","unit":"RSI","value":"49.95"}},"reconciliation":{"method":"underlying GC/MGC frozen-close agreement plus independent synchronized PAXG median","note":"PAXG median trades at a 0.1858% discount to the frozen GC close. Underlying confidence is low because the bullion market is closed; execution-instrument confidence is high.","quotes":[{"instrument":"GC=F","source_ids":["snapshot"],"state":"frozen Friday close","value":"4380.4"},{"instrument":"MGC=F","source_ids":["snapshot"],"state":"frozen Friday close","value":"4380.4"},{"instrument":"PAXGUSDT Binance","source_ids":["binance"],"state":"live","value":"4375.35"},{"instrument":"PAXGUSD Kraken","source_ids":["kraken"],"state":"live","value":"4371.89"},{"instrument":"PAXG CoinGecko","source_ids":["coingecko"],"state":"live","value":"4372.26"}],"spread_pct":"0.0791","status":"DATA_LIMITED"},"regime":{"active_downtrend":false,"daily_rsi14":"64.59","label":"trend repair without fallen-knife fear","ma200_falling":false,"ma200_slope_20_pct":"0.29","paxg_above_sma20_50_100":true,"price_below_ma200":true,"trend_residual":"mirror toward Rally because the breakout shelf holds and the 200-day slope is positive"},"spot":{"as_of":"2026-08-14 close","note":"Canonical underlying reference is the frozen COMEX Friday close. PAXG execution is independently reconciled at 4372.26.","source_ids":["snapshot"],"status":"AVAILABLE","unit":"USD/oz","value":"4380.4"}},"narrative":{"arguments":{"discretion_ledger":[{"call":"-1.0","channel":"D1","date":"2026-08-15","falsifier":"PAXG <4020 plus real yield >2.50% and Brent normalization","realized_pnl":null,"size":"none","status":"LIVE","stop":"N/A"}],"exit_status":"None; nearest live trigger is mechanical <=4 for a 25% trim.","position_reconciliation":"User attestation controls freshness; ledger age/custody defects remain disclosed.","score_ceiling":"14 under current structural pins; higher phase lines are vacuous-false.","tier1_calendar":"FOMC minutes 2026-08-19 14:00 ET only; no NFP/CPI/PCE/FOMC decision in next five trading sessions."},"bear_case":"This is not a fallen-knife entry: weekly RSI is 49.95, drawdown is only 21.59%, managed-money net longs increased by 6896, and volume is only at the 74.4th percentile. The position also carries PAXG issuer/custody risk and an unresolved 0.1736 PAXG ledger replay gap. Five live buys would lift the position from roughly 28.83% to 43.45% of portfolio value before price effects even though every FK phase is locked.","bull_case":"Gold's realized structure is constructive: the 200-day slope is +0.29%, PAXG is above its 20/50/100-day averages, GLD holdings rose 8.52t in a week and 20.79t in a month, Chinese official buying continued, and the 10-year real yield eased to 2.39%. IF PAXG holds the 4220-4250 breakout shelf through the August 19 FOMC minutes and then closes a week above 4485, THEN the Rally band gains weight; a daily close below 4020 is the structural falsifier.","primary_action":{"rationale":"The open PAXG position has no live exit trigger, but all accumulation phases are locked and the resting buys violate the score/gate decision.","status":"AVAILABLE","value":"CANCEL_RESTING_BUYS_AND_HOLD"},"rationale":"LIVE DATA AND SCORE — Canonical underlying is the low-confidence frozen GC/MGC Friday close 4380.40; execution PAXG is high-confidence at median 4372.26 across Binance/Kraken/CoinGecko, a 0.1858% discount to GC. Legs are sentiment fallback 2, momentum 0 (weekly RSI 49.95), valuation 0 (21.59% drawdown; gold/BTC RV30 ratio 1.02x blocks the low-vol adaptation), capitulation 0 (volume not top-decile, COT longs building, GLD holdings rising), holder 3 (GLD and official-sector accumulation). Mechanical 5; D1 -1; adjusted 4.\nGATES AND VACUITY — One of eight active gates is lit: holder accumulation. Gate 5 is N/A. Each dark gate has a relight path in gates.measurement_basis. The attainable ceiling is 14 on today's structural pins: sentiment 2, momentum 4, valuation 2 absent a confirmed COT flush, capitulation 3, holder 3. Phase 2 >=15, Phase 3 mechanical>=17, and Override mechanical>=15 are VACUOUS-FALSE; Override is still explicitly evaluated and does not fire because there is no tagged prior phase, mechanical is 5, and no fresh lower-low/deep-value condition exists. Phase 1A and 1B lines are LIVE-FALSE. The compound mechanical<8 key is LIVE-TRUE on two distinct report dates, not yet a four-date vacuity state. Score is the binding axis on every phase.\nPROBABILITY AND EV — D4 sets Rally/Range/Retest/Bear at 28/40/22/10 versus the 0-5 baseline 10/30/35/25 because a realized trend-repair event exists. Active downtrend: NO. EV is 4328.60, -1.18% versus GC, while trailing two-week price is +8.18%. The negative sign is carried by Retest/Bear distance, so it is geometry-driven and cannot justify the stance. The negative-sign EV streak is 12 report dates with 11 resolved outcomes and a 54.5% hit rate; no majority-contradiction tripwire. The prior -1.02% forecast was contradicted by a +0.0068% move.\nPOSITION RECONCILIATION — The prior report's cold-start flat assumption is superseded by the user's explicit confirmation: 1.3293894 PAXG, average cost 4210.5024, cost 5597.40, market value 5812.44, and independent-mark unrealized gain about 215.04. Stablecoin dry powder is 10741.58, including 2948.934 locked in five resting PAXG buys. The ledger's native age test fails because holdings_as_of is old, and custody is UNEXPLAINED by 0.1736 PAXG; the user attestation authorizes the live balance as current, but it does not cure the accounting defect or UNTAGGED phase attribution. PAXG is tokenized gold with issuer/custody and premium/discount risks absent from bullion.\nOPEN-POSITION BEST-LEVEL AUDIT — Primary action is HOLD, not EXIT/TRIM/ADD. No level is absolutely best; this is the best available control under current evidence. Current venue state has no protective sell and five BUY limits at 3909, 3806, 3755, 3703 and 3624. The current stop-zone check FAILS because catastrophic 3800 is not strictly below live buy floor 3624. The valid resolution is cancellation, not a D6-prohibited stop widening. After cancellation, retain compound protection at two weekly closes below PAXG 3850 plus mechanical<8, and catastrophic PAXG 3800 as the unchanged gap tier. Market-to-3800 exposure is about 760.76 or 3.77% of portfolio; a 3800 exit realizes about -550.77 after a 0.10% fee. The prior August 7 checkpoint expired and is not rolled later. No orders were changed by this report.\nEXIT/TRIM — Campaign mechanical peak is 10 and current is 5, a five-point decline; the >=6-point 25% trim is one point away, not active. Weekly RSI is below 70, price remains more than 10% below ATH, mechanical is above 3, no qualifying post-inflow ETF break is present, and no narrative break is established. Conditional ladder: at mechanical<=4, trim 0.33234735 PAXG (25%) LIFO at the live executable price; at approximately 5027.58/5030 with a vertical 30-day return, trim 0.6646947 PAXG (50%). The 4485-4500 resistance is alert-only without a Section 7 trigger.\nANALYST READ AND D1 LEDGER — D1 remains -1.0, freshly earned by two non-leg factors: (1) realized trend repair, with rising 200-day slope and PAXG above 20/50/100-day averages; (2) macro/geopolitical convexity, with Hormuz attacks and Brent +5.95% over five sessions sustaining hedge demand even as real yields eased. Falsifier: PAXG daily close below 4020 together with real yield >2.50% and Brent normalization. D2 was considered and declined because score is not met and gates are short by more than one. D4 is active via 28/40/22/10. Ledger entry: 2026-08-15 | D1 | -1.0 | no capital | no D5 stop | falsifier above | LIVE | PnL N/A. D4 probabilities are LIVE and falsified by a weekly >4485 continuation or daily <4020 break.\nBULL/BEAR SCORECARD — Bull price signals (5): rising 200-day slope; PAXG above 20/50/100-day averages; GLD holdings rising; real yields eased; geopolitical/official-sector demand. Bear/FK signals (5): no fear gauge signal; weekly RSI not exhausted; valuation not cheap; COT longs building; no volume/ETF capitulation. Balanced price evidence plus |EV|<2% keeps the Verdict-Confidence Collar ON: no bottom/top or resolved-regime claim.\nEXECUTABLE ACTIONS — 1) Cancel all five resting PAXG BUY limits and confirm locked stablecoins release. 2) Hold 1.3293894 PAXG; authorize no FK addition and attach no phase tag retroactively. 3) After cancellation, verify a venue-native catastrophic order at trigger 3800 (illustrative stop-limit 3780) and separately monitor the 3850 weekly-close-plus-score compound rule. 4) Reconcile the unexplained 0.1736 PAXG ledger gap. 5) Re-score after FOMC minutes on August 19 and the next COT print. Cash is a position; patience is alpha, and its benchmark yield is 3.70%.\nTHE PATTERN — IF PAXG holds 4220-4250 through the FOMC minutes and closes a week above 4485, THEN Rally probability rises, but no FK add follows without score/gates. IF mechanical falls to 4 or less, THEN trim 25% at the live executable price. IF two weekly closes print below 3850 while mechanical remains <8, THEN the compound exit fires; 3800 remains the catastrophic gap tier.","summary":"HOLD the user-confirmed 1.3293894 PAXG, add nothing, and cancel all five resting buy limits. Adjusted score is 4/20 with 1/8 gates; the real holding is UNTAGGED and cannot be mapped into an FK phase. Protection remains 3850 plus mechanical<8 on two weekly closes, with catastrophic 3800 only coherent after the unauthorized buy ladder is removed."},"out_of_scope":["PAXG issuer solvency, bar-level allocation audit, and off-exchange custody verification are not independently audited in this report.","No exchange order was changed or cancelled by this analysis; recommendations are not executions.","Gold has no crypto perpetual-funding, liquidation, or on-chain-reserve analogue; only documented gold substitutions receive credit."],"position":{"asset":"GOLD","attribution":{"active_tags":[],"open_deals":2,"phase_eligibility_effect":"Cannot infer a phase or unlock the next tranche from quantity/timing.","status":"UNTAGGED"},"basis":{"avg_cost_usd":"4210.5024215893","note":"Basis is separately reported reliable by the ledger.","reliable":true,"total_cost_usd":"5597.397287935146"},"custody":{"interpretation":"The user confirms the live balance is current and correct; the replay difference remains a ledger defect, not inferred off-venue custody.","ledger_asset":"PAXG","live_qty":"1.3293894","mismatch_qty":"0.1736","native_ledger_status":"UNEXPLAINED","reconciliation_method":"USER_ATTESTATION","requested_asset":"GOLD","status":"RECONCILED","trade_derived_qty":"1.5029894","user_attested_current":true},"dry_powder":"10741.5780","futures":[],"pnl":{"fee_assumption_pct":"0.10","mark_source":"independent PAXG median, not the snapshot mark","market_value_usd_at_paxg_median":"5812.436098044","realized_usd":"1418.064989674","unrealized_usd_at_paxg_median":"215.038810109"},"quantity":"1.3293894","reconciliation":"Prior report treated the book as flat under expiry. User attestation now confirms 1.3293894 PAXG, avg cost 4210.50, and 10741.58 stablecoin dry powder. The live quantity wins for this report, while the unexplained 0.1736 PAXG replay gap and UNTAGGED attribution remain explicit. GOLD is held as PAXG, not bullion.","status":"FRESH"},"position_controls":{"action":{"rationale":"No exit/trim trigger is live, but all four FK phases are locked and five resting buy limits would add outside authorization.","status":"AVAILABLE","value":"CANCEL_RESTING_BUYS_AND_HOLD"},"candidate":{"board":[{"candidate":"Actual venue state: no protective sell, five BUY limits","reason":"Not a protective control and conflicts with locked phases.","score":null,"veto":true},{"candidate":"Prior compound: two weekly closes below PAXG 3850 AND mechanical score <8","dimensions":{"coherence":2,"event":1,"execution":1,"noise":2,"thesis":3},"reason":"Correct compound semantics; manual monitoring is required.","score":9,"veto":false},{"candidate":"Prior catastrophic PAXG 3800 after cancelling the buy ladder","dimensions":{"coherence":2,"event":1,"execution":2,"noise":2,"thesis":2},"reason":"Best available catastrophic tier, unchanged under D6, execution-native and below no active buy floor after cancellation.","score":9,"veto":false},{"candidate":"PAXG 4020 daily-close structural shelf","reason":"Useful alert, but vetoed as a whole-book price-only replacement for the compound stop.","score":null,"veto":true},{"candidate":"PAXG about 4099 from shelf/2xADR volatility control","reason":"Ordinary noise control is too tight for whole-book catastrophic protection and would replace compound semantics.","score":null,"veto":true},{"candidate":"EXIT NOW","dimensions":{"coherence":0,"event":0,"execution":2,"noise":2,"thesis":0},"reason":"Rejected: no Section 7 trigger or narrative break is active.","score":4,"veto":false}],"data_as_of":"2026-08-15T16:02:00Z","phrase":"no level is absolutely best; this is the best available control under current evidence.","primary_action":"HOLD"},"execution_audit":{"instrument":"PAXGUSDT/PAXGUSD","orders_changed":false,"ratio_verified":"PAXG median 0.1858% below frozen GC close","stop_zone_coherence_after_cancel":true,"stop_zone_coherence_current":false,"stop_zone_test_after_cancel":"No active buy-zone floor = N/A/pass for protection placement","stop_zone_test_current":"3800 strictly below 3624 = FAIL","trigger_semantics":"Compound uses weekly close twice plus mechanical score; catastrophic is price-only; structural and volatility levels are alerts only.","underlying_reference":"GC=F/MGC=F"},"ladder":{"alerts_only":[{"price":"4485-4500","reason":"200-day/round-number resistance, but no Section 7 trigger currently authorizes a trim"}],"quantity_check":"0.33234735 + 0.6646947 = 0.99704205 <= 1.3293894","remaining_after_both_paxg":"0.33234735","status":"DORMANT_CONDITIONAL_TRIMS","targets":[{"condition":"Mechanical score falls to 4 or lower, completing a >=6-point drop from the campaign peak of 10","execution":"market/LIFO after trigger","expected_realized_pnl_after_0_1pct_fee_usd":"52.31","position_share_pct":"25","price_note":"Indicative current executable median; use live price when the trigger fires","quantity_paxg":"0.33234735","target_price_usd":"4372.26"},{"condition":"Price reaches the within-10%-of-ATH threshold near 5027.58 with a vertical 30-day return","execution":"limit/LIFO after trigger","expected_realized_pnl_after_0_1pct_fee_usd":"541.37","position_share_pct":"50","quantity_paxg":"0.6646947","target_price_usd":"5030"}]},"liquidation_zone":{"reason":"No futures or leveraged PAXG position is present.","status":"NOT_APPLICABLE"},"pnl":{"catastrophic_realized_pnl_after_0_1pct_fee_usd":"-550.77","compound_realized_pnl_after_0_1pct_fee_usd":"-484.37","market_to_catastrophic_loss_pct_portfolio":"3.77","market_to_catastrophic_loss_usd":"760.76","market_to_compound_loss_pct_portfolio":"3.44","market_to_compound_loss_usd":"694.29"},"ratchet":{"catastrophic_current":"3800","catastrophic_prior":"3800","checkpoint_current":"2026-08-07 expired; not rolled later","checkpoint_prior":"2026-08-07","compound_floor_current":"3850","compound_floor_prior":"3850","migration":"None","score_line_current":"8","score_line_prior":"8","status":"PASS"},"required":true,"risk":{"current_paxg_weight_pct":"28.83","event_risk":"FOMC minutes August 19 at 14:00 ET; geopolitical weekend gaps remain possible.","gap_risk":"A stop-limit can miss in a gap; market-stop support and venue liquidity must be checked at placement.","issuer_custody_risk":"PAXG is a tokenized allocated-gold claim, not physical bullion in the user's possession.","weight_if_all_buys_fill_pct_before_market_move":"43.45"},"selection":{"catastrophic":"After buy cancellation, use PAXG 3800 as the unchanged catastrophic trigger; a stop-limit implementation may use trigger 3800/limit 3780, with gap/slippage risk acknowledged.","certainty":"best-available-not-absolute","compound":"Monitor two consecutive weekly closes below PAXG 3850 while mechanical score remains <8; only then exit under the compound thesis rule.","immediate":"Cancel BUY limits at 3909/3806/3755/3703/3624 totaling 2948.934 USD.","orders_changed":false,"primary":"HOLD current PAXG; NO ADD"},"status":"OPEN","venue_order":{"current_buy_orders":[{"notional_usd":"249.7851","price":"3909","quantity":"0.0639","side":"BUY","type":"LIMIT"},{"notional_usd":"499.9278","price":"3806","quantity":"0.1313","side":"BUY","type":"LIMIT"},{"notional_usd":"599.6735","price":"3755","quantity":"0.1597","side":"BUY","type":"LIMIT"},{"notional_usd":"599.886","price":"3703","quantity":"0.162","side":"BUY","type":"LIMIT"},{"notional_usd":"999.6616","price":"3624","quantity":"0.2759","side":"BUY","type":"LIMIT"}],"current_protective_sell":null,"locked_notional_usd":"2948.934","orders_changed":false,"recommended_sequence":["Cancel all five BUY limits","Confirm no buy order remains","Install or retain catastrophic PAXG protection only after coherence passes"]},"veto":{"active":true,"reasons":["Catastrophic 3800 is not strictly below the deepest live buy floor 3624 while those orders remain active.","No ordinary price-only shelf/ATR stop may replace the mechanical compound thesis stop.","D6 prohibits lowering the 3800 catastrophic anchor or rolling the expired checkpoint later without a named exception."],"resolution":"Cancel all five resting buys first; do not lower the catastrophic stop to accommodate unauthorized orders."}},"report_id":"gold_fallen_knives_20260815_1210","risk_controls":{"carry":{"dry_powder_yield_pct":"3.70","long_borrow_cost":"none observed","note":"Idle cash has measurable T-bill opportunity cost.","status":"AVAILABLE","veto":false},"concentration":{"action":"Cancel resting buys to prevent unauthorized concentration increase.","current_weight_pct":"28.83","potential_weight_if_buys_fill_pct":"43.45","status":"AVAILABLE"},"ratchet":{"catastrophic":"3800 unchanged","checkpoint":"2026-08-07 not extended","compound":"3850 AND mechanical<8 unchanged","parameters_changed":false,"status":"PASS"},"stops":{"catastrophic_paxg":"3800","coherence_after_cancel":true,"coherence_current":false,"compound_close_count":2,"compound_floor_paxg":"3850","compound_score_line_mechanical_below":"8","current_deepest_buy_floor":"3624","reason":"Cancel unauthorized buys; do not widen protection.","status":"AVAILABLE","stop_realignment_owed":false},"time_stops":{"action":"Reassess now; D6 prohibits rolling the same-campaign clock later. FOMC minutes are a watch event, not a replacement stop.","prior_checkpoint":"2026-08-07","state":"expired","status":"DATA_LIMITED"}},"run":{"prior_report_id":"gold_fallen_knives_20260810_0020","prior_report_sha256":"f96dda4e2e72f73490acb1cd8d4309c03b62ae36ad68dd5f25058865e3613024","run_id":"20260815-1601-fecbe187","snapshot_id":"sha256:fecbe1879215ef4239d1fea5690a9528736d5490bd84f3485bb958b1fdb00c77","tool_hashes":{"compute":"sha256:cb58b6c3bc543e668dc75e84a8ce08f2653147c84e355011b9bb4eaedc65c4eb","fetch":"sha256:7296bd05f390f9004cf7110ac249df36f87dfb28726356b208bd7519a85750ba","snapshot":"sha256:226b09178cf62b570e7baf61660373d68288f2f89720f9534b72fa8e2bf61b96"}},"schema":"report-machine/2","score":{"adjusted":4,"caps":[{"cap":2,"field":"sentiment","reason":"Measured gold fallback after GVZ/PHYS rejection"},{"current_cap":2,"field":"valuation","reason":"Gold low-volatility maximum absent a confirmed COT flush; current low-vol substitution is unavailable, so the realized score is 0"},{"derivation":"sentiment 2 + momentum 4 + valuation 2 absent confirmed COT flush + capitulation 3 + holder 3","field":"attainable_ceiling","value":14},{"field":"line_states","value":["P1A>=8 LIVE-FALSE","P1B>=11 LIVE-FALSE","P2>=15 VACUOUS-FALSE at ceiling 14","P3 mechanical>=17 VACUOUS-FALSE","Override mechanical>=15 VACUOUS-FALSE but still evaluated","compound mechanical<8 LIVE-TRUE on two distinct dates"]}],"discretion":-1,"legs":{"capitulation":0,"holder":3,"momentum":0,"sentiment":2,"valuation":0},"mechanical":5,"penalties":[],"raw":4,"rounding":"half-up"},"sources":{"ap_hormuz":{"as_of":"2026-08-14","kind":"news","name":"Associated Press Strait of Hormuz tanker attacks","note":"Two UAE tankers were attacked; geopolitical and energy risk remains live.","retrieved_at":"2026-08-15T15:57:00Z","url":"https://apnews.com/article/e8565c608ac5283ec8103c85df924b13"},"axios_gold":{"as_of":"2026-08-13","kind":"news","name":"Axios gold rally and China reserve-buying report","note":"Reports nearly 20 tonnes of Chinese official buying in July and the recent gold high.","retrieved_at":"2026-08-15T15:57:00Z","url":"https://www.axios.com/2026/08/13/gold-inflation-rates-iran"},"bea":{"as_of":"2026-07-30","kind":"official macro release","name":"BEA Personal Income and Outlays calendar/release","note":"Next PCE release is August 26, outside the five-session window.","retrieved_at":"2026-08-15T15:55:00Z","url":"https://www.bea.gov/news/2026/personal-income-and-outlays-june-2026"},"binance":{"as_of":"2026-08-15T16:02:00Z","kind":"venue","name":"Binance PAXGUSDT ticker and daily klines","note":"Execution-instrument quote and contract-native structure.","retrieved_at":"2026-08-15T16:02:00Z","url":"https://api.binance.com/api/v3/ticker/bookTicker?symbol=PAXGUSDT"},"bls_calendar":{"as_of":"2026-08-15","kind":"official calendar","name":"BLS August 2026 release calendar","note":"Used to verify no NFP or CPI release in the next five trading sessions.","retrieved_at":"2026-08-15T15:55:00Z","url":"https://www.bls.gov/schedule/2026/08_sched_list.htm"},"cftc":{"as_of":"2026-08-11","kind":"regulator","name":"CFTC Disaggregated Futures COT 2026 archive","note":"COMEX Gold managed-money positioning.","retrieved_at":"2026-08-15T15:47:00Z","url":"https://www.cftc.gov/files/dea/history/fut_disagg_txt_2026.zip"},"coingecko":{"as_of":"2026-08-15T16:02:00Z","kind":"aggregator","name":"CoinGecko PAX Gold USD","note":"Third synchronized PAXG quote.","retrieved_at":"2026-08-15T16:02:00Z","url":"https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd&include_last_updated_at=true"},"fed_calendar":{"as_of":"2026-08-15","kind":"official calendar","name":"Federal Reserve August 2026 calendar","note":"FOMC minutes release verified for August 19 at 14:00 ET.","retrieved_at":"2026-08-15T15:55:00Z","url":"https://www.federalreserve.gov/newsevents/2026-august.htm"},"gld":{"as_of":"2026-08-13","kind":"issuer","name":"SPDR Gold Shares historical archive","note":"Official daily tonnes held.","retrieved_at":"2026-08-15T15:50:00Z","url":"https://www.spdrgoldshares.com/usa/gld/"},"kraken":{"as_of":"2026-08-15T16:02:00Z","kind":"venue","name":"Kraken PAXGUSD ticker","note":"Independent execution-instrument quote.","retrieved_at":"2026-08-15T16:02:00Z","url":"https://api.kraken.com/0/public/Ticker?pair=PAXGUSD"},"ledger":{"as_of":"2026-08-15T16:03:00Z","kind":"user-attested ledger","name":"Personal-accounting PAXG position snapshot","note":"The file's holdings_as_of clock is old, but the user explicitly attested in this task that the PAXG snapshot is current and correct.","retrieved_at":"2026-08-15T16:03:00Z","url":"exports/position-snapshot-2026-08-15_09-30-02-628Z.json"},"paxos":{"as_of":"2026-08-15","kind":"issuer","name":"Paxos PAX Gold documentation","note":"PAXG is a tokenized allocated-gold claim and carries issuer/custody and premium/discount risk.","retrieved_at":"2026-08-15T15:59:00Z","url":"https://www.paxos.com/paxgold"},"snapshot":{"as_of":"2026-08-15T16:01:48.468Z","kind":"computed","name":"Deterministic GOLD/BTC/macro snapshot","note":"Yahoo GC=F/MGC=F and FRED series; full source metadata is preserved in the snapshot.","retrieved_at":"2026-08-15T16:01:48.468Z","url":"data/runs/20260815-1601-fecbe187/snapshot.json"},"wgc":{"as_of":"2026-07-17","kind":"industry primary dataset","name":"World Gold Council gold ETF and central-bank data","note":"June global ETF data is lagged and used as context, not as a current gate input.","retrieved_at":"2026-08-15T16:12:00Z","url":"https://www.gold.org/goldhub/research/gold-etfs-holdings-and-flows/2026/07"}},"stale_inputs":["The ledger file's holdings_as_of is 2026-07-05, but the user explicitly attested on 2026-08-15 that the PAXG snapshot is current and correct; the attestation is used while the native age and custody warnings remain visible.","World Gold Council global ETF monthly data is June 2026; it is context only, not a current scored/gate input."],"substitutions":[{"field":"position_asset","original":"physical/spot GOLD","rationale":"Hard Rule 8 ledger alias; disclosed issuer/custody and premium/discount risk.","substitute":"PAXG"},{"field":"gold_holder_behavior","original":"crypto LTH supply and exchange reserves","rationale":"Asset-appropriate physical-holder evidence; GLD rose on weekly and monthly horizons and reported Chinese official buying continued in July.","substitute":"official GLD tonnes plus central-bank accumulation"},{"field":"correlation_asset","original":"spot GOLD daily closes","rationale":"Official liquid gold proxy paired with FRED SP500 closes for a reproducible 30-session calculation.","substitute":"GLD daily closes"},{"field":"gold_capitulation_b","original":"perpetual funding","rationale":"Codified gold substitution; current net longs rose, so no credit.","substitute":"CFTC managed-money washout"},{"field":"gold_capitulation_c","original":"crypto ETF/exchange outflow","rationale":"Codified gold substitution; GLD holdings rose, so no credit.","substitute":"physical-gold ETF outflow spike"}],"tagging":{"active_tags":[],"entries":[{"canonical_tag":"FK-P1A-GOLD-20260815-1210","decision":"LOCKED","instrument_class":"non_crypto_derivative","phase":"1A"},{"canonical_tag":"FK-P1B-GOLD-20260815-1210","decision":"LOCKED","instrument_class":"non_crypto_derivative","phase":"1B"},{"canonical_tag":"FK-P2-GOLD-20260815-1210","decision":"LOCKED","instrument_class":"non_crypto_derivative","phase":"2"},{"canonical_tag":"FK-P3-GOLD-20260815-1210","decision":"LOCKED","instrument_class":"non_crypto_derivative","phase":"3"}],"instrument_class":"non_crypto_derivative","reserved_tags":["FK-P1A-GOLD-20260815-1210","FK-P1B-GOLD-20260815-1210","FK-P2-GOLD-20260815-1210","FK-P3-GOLD-20260815-1210"],"schema":"report-phase-registry/2","status":"REGISTERED"},"timestamps":{"data_as_of":"2026-08-15T16:01:48.468Z","generated_at":"2026-08-15T16:10:25Z","report_at":"2026-08-15T16:10:00Z","timezone":"America/New_York"},"verdict":{"primary_action":{"rationale":"Prevent unauthorized concentration while preserving the existing long until a mechanical trim/exit or compound/catastrophic stop fires.","status":"AVAILABLE","value":"CANCEL_RESTING_BUYS_AND_HOLD"},"statement":"Hold the confirmed PAXG position, cancel every resting buy, and make no FK addition; adjusted score 4/20 and 1/8 gates authorize no tranche.","status":"HOLD"},"watchlist":[{"item":"Cancel PAXG resting buy orders","status":"AVAILABLE","trigger":"Immediate: all FK phases are locked; confirm the five orders totaling 2948.934 USD are gone."},{"item":"Mechanical trim trigger","status":"AVAILABLE","trigger":"Mechanical score <=4 would complete the >=6-point drop from the campaign peak of 10 and require a 25% trim."},{"item":"Compound thesis stop","status":"AVAILABLE","trigger":"Two consecutive weekly closes below PAXG 3850 while mechanical score remains <8."},{"item":"COT washout","status":"AVAILABLE","trigger":"Managed-money/non-commercial net-long decline of 20-30K contracts or >=15%, then held/extended on the following weekly print."},{"item":"Gold ETF flow reversal","status":"AVAILABLE","trigger":"Sustained multi-region outflow among the worst trailing 12 months, corroborated by GLD tonnes."},{"item":"Valuation trim","status":"AVAILABLE","trigger":"Gold/PAXG within 10% of 5586.2 ATH (about 5027.58) with a vertical 30-day return."}]}
```
