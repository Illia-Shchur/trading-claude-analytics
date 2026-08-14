# 🔪 FALLEN KNIVES ANALYTICS — ETH — 2026-08-01

## SATURDAY MIDDAY — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Saturday, August 1, 2026, 11:17 EDT (15:17 UTC)
### Asset: ETH | Prior Score: 11/20 (2026-07-25) | Mechanical Score: 10/20 | D1: 0.0 | Adjusted Score: 10/20

---

## 1. What this report is deciding

The Jul-25 report scored ETH an 11 on a capitulation leg that it simultaneously flagged as **fired but not standing** — four negative funding intervals over a ~32-hour window that had already reversed before the report was written. That disclosure was the right call, and it has now been settled by the tape: **funding has not printed three consecutive negative intervals since, so the leg reverts to 0 and the score falls to 10.** The prior report's honesty about the transience of its own scoring input is the reason today's decline is a confirmation rather than a surprise.

The consequence is asymmetric between phases. **Phase 1A stays open** — score 10 clears the cut ≥8 line comfortably, and the pre-assigned remainder of the tranche needs no fresh unlock under the partial-tranche rule. **Phase 1B closes** — 10 falls one point short of the cut ≥11 line, so where BTC's 1B is now blocked on gates alone, ETH's is blocked on score.

---

## 2. Verified Live Data Points — ETH

### 2.1 Canonical spot reconciliation

| Source | Price | Timestamp (UTC) | Status |
|---|---|---|---|
| Binance ETHUSDT | $1,872.06 | 2026-08-01 15:27 | live |
| CoinGecko | $1,870.88 | 2026-08-01 15:27 | live |
| Coinbase ETH-USD | $1,869.93 | 2026-08-01 15:27 | live |
| Kraken XETHZUSD | $1,869.79 | 2026-08-01 15:27 | live |

**Canonical spot = $1,870.40** (median of 4 synchronized live quotes, all within a 60-second window).
Inter-source spread **0.121%** — below the 0.5% flag. No dual-extreme EV, no low-confidence demotion, no stale quote excluded.

Cross-check: `tools/fetch.mjs eth` at 15:17 UTC returned canonical $1,873.11 (CoinGecko $1,873.11 / Yahoo last daily close $1,872.19, divergence 0.05%). The ten-minute drift is time-ordering, not venue disagreement.

**Trailing 2-week realized price change: +0.45%** (Yahoo ETH-USD, Jul-18 $1,861.39 → Aug-01 $1,869.80). ETH has gone essentially nowhere in two weeks while BTC fell 2.78% — a small but real relative outperformance, stated here so the negative EV in §5 is read against it.

### 2.2 Sentiment

| Metric | Reading | Source | Timestamp |
|---|---|---|---|
| F&G spot | **27** (Fear) | Alternative.me raw API (PINNED provider) | 2026-08-01 |
| F&G 3-day average | **26.67** → scored band | Alternative.me | Jul-30/31, Aug-01 |
| Last 10 daily prints | 27, 25, 28, 29, 29, 30, 26, 27, 28, 31 | Alternative.me | Jul-23 → Aug-01 |
| Gate-1 streak ≤15 | **0 consecutive days** | computed (`compute.mjs streak --threshold 15` = 0 of 10) | — |

The crypto F&G index is the pinned instrument for ETH as a large cap. No second provider quoted; no ≥10-point divergence to disclose.

### 2.3 Momentum, valuation, structure

| Metric | Value | Source |
|---|---|---|
| **Weekly Wilder RSI-14** | **41.40** (262 completed weekly closes, period 14, Yahoo weekly boundary UTC week-start, last completed week 2026-07-27) | computed, `tools/fetch.mjs eth` 15:17 UTC |
| Weekly RSI incl. live week | 41.84 | same |
| **200-week SMA** | **$2,481.69**; spot **−24.52%** vs it → **within ±8% = FALSE** | computed, exact |
| 200-day MA | $2,102.71, falling −5.35% over trailing 20 sessions; spot −11.01% beneath | computed from Yahoo ETH-USD |
| 50-day MA | $1,777.21; spot **+5.28% above it** | computed |
| ATH | $4,946.05 (2025-08-24); **drawdown −62.13%** | CoinGecko |
| 1-yr high | $4,953.73 (2025-08-18); **−62.19%** | Yahoo weekly highs |
| **MVRV-Z decimal** | **UNOBTAINABLE — standing declaration (first made 2026-07-20), re-verified today** | Glassnode / Santiment / CoinGlass / BGeometrics all paywalled or non-current for ETH |
| MVRV ratio (proxy) | **~0.81** (spot ~19% below realized price ~$2,304, carried from the Jul-25 report) | prior report, carried |
| 5-day ADR | **$53.46** (Jul-28 70.16 / Jul-29 56.28 / Jul-30 42.38 / Jul-31 86.31 / Aug-01 12.19); no abbreviated session excluded — crypto trades continuously | computed |

**Stale-input debt clock — valuation leg, and the trap I declined to fall into.** Search surfaced two pieces reporting ETH's MVRV Z-Score "near −0.7" at a seven-year low. Both trace to a **single Phemex/BeInCrypto article dated 2026-06-08, citing Glassnode, at an ETH price of $1,684** — roughly two months old and 10% below today's spot. A negative MVRV-Z would score the valuation leg **5** rather than 4 and take the composite to 11, which is exactly the Phase-1B line. **I decline to score it.** A two-month-old decimal taken at a materially different price is not a current print, and the provenance-citation rule bars using it to make a scored claim about today. The standing UNOBTAINABLE declaration is re-verified and the leg holds at 4 on the ratio proxy. This is the third consecutive report carrying the leg on a non-decimal basis — **the debt clock is at report 3 and a sourced or computed decimal is owed next report, or an explicit statement of why it cannot be obtained.**

I flag the direction of the error explicitly: if the −0.7 reading is directionally right, this leg is scored **one point too low**, and the composite is a 10 that "should" be an 11. That is the conservative-on-a-buy-signal direction the framework's fallback conventions deliberately choose, and I am comfortable with it — but it is a real cost of the sourcing gap, and it is a second reason the debt matters.

### 2.4 Derivatives — live, primary-source

Binance USDT-M perpetual `ETHUSDT`, last 15 funding intervals (fapi/v1/fundingRate, fetched 2026-08-01 15:2x UTC):

```
Jul-27 16:00  +0.00161%   Jul-30 00:00  +0.00385%
Jul-28 00:00  +0.00146%   Jul-30 08:00  +0.00466%
Jul-28 08:00  −0.00366%   Jul-30 16:00  +0.00199%
Jul-28 16:00  +0.00282%   Jul-31 00:00  +0.00573%
Jul-29 00:00  +0.00574%   Jul-31 08:00  +0.00121%
Jul-29 08:00  +0.00687%   Jul-31 16:00  −0.00204%
Jul-29 16:00  +0.00214%   Aug-01 00:00  +0.00674%
                          Aug-01 08:00  +0.00231%
```

**Negative intervals in window: 2. Longest consecutive negative run: 1.** Trailing-3-interval annualized **+2.56%**.

**This is the leg that moved, and it moved down.** The rubric's capitulation-(b) condition requires funding negative for **≥3 consecutive intervals**. The two negatives in this window (Jul-28 08:00 and Jul-31 16:00) are isolated single prints, each immediately reversed. The Jul-23/24 episode that scored the leg on Jul-25 — four consecutive negatives over ~32 hours — has not recurred, and the prior report explicitly warned it would not: it recorded that funding was "fully positive by Jul-24 16:00" and that the Jul-25 00:00 print was the week's most positive. **The leg reverts to 0.** The framework scored a transient correctly under a plain-count rubric, disclosed the transience, and has now unwound it on schedule. That is the metric-history continuity discipline working as intended.

ETH funding at +2.56% annualized is roughly *half* BTC's +4.73% — the ETH complex is carrying meaningfully less long leverage than BTC. That is a structural observation, not a scored one.

### 2.5 Liquidations

| Window | Figure | Source |
|---|---|---|
| Jul-31 24h, market-wide (comparable venue set) | ~$360M, longs ~$235M (~65%) | market trackers, Jul-31 |
| Jul-31 24h, widest venue set (17+ exchanges) | $1.45B ($1.25B long / $196.19M short) | Loris Tools, Jul-31 — **methodology mismatch, see below** |
| Jul-30 24h, network | $286M (BTC+ETH whipsaw) | CoinDesk, Jul-30 |
| Jul-24 24h, ETH-specific / network | $48.62M / $274M, longs 66%, 84,929 traders | prior report, Jul-25 |

Same methodology disclosure as the BTC report and it applies identically: the Loris figure covers a materially wider exchange set than every prior print in this series (all in the $270–320M network band), so crediting a top-decile event off it would be a measurement artefact. **The series' comparable-venue convention is held.** On that basis Jul-31 is ordinary — no decile event, no 3σ. Capitulation-(a) does not fire and gate 7 stays dark. The Loris figure is disclosed and flagged **PROVISIONAL**, not scored.

### 2.6 ETF flows — ETH

| Window | Net flow | Source |
|---|---|---|
| **July 2026 (month)** | **+$365.17M net inflows**, led by BlackRock's ETHB — the strongest of BTC/ETH/XRP | CoinGabbar, Jul-31 |
| Week ending Jul-24 | +$103.9M net inflows | Cointribune, Jul-2x |
| **Fri Jul-25** | **−$70.62M**, ending a 5-session green streak | Cointribune, Jul-25 |
| Week ending Jul-11 | +$84.42M — first positive week after **eight straight negative weeks** | Phemex, Jul-1x |

The ETH flow picture is the mirror image of BTC's and it is unambiguously the stronger of the two. ETH broke an eight-week outflow streak in early July and closed the month with the largest net inflow of the three major complexes. **Trailing-month flows are decisively net positive, so capitulation-(c) is off and gate 4 is dark — and unlike BTC, gate 4 is not the nearest gate to lighting; it is moving in the wrong direction entirely.** For an accumulation framework this is genuinely awkward: the flow evidence that would deepen the fear signal is absent because institutions are buying.

### 2.7 On-chain holder behaviour

| Metric | Value | Source |
|---|---|---|
| Exchange reserves 30d | **−~1,000,000 ETH (~$2B) to ~15.1M, a decade low** — explicitly uninterrupted through both rallies and corrections | prior report Jul-25, carried; corroborated by aggregator reporting of multi-year-low reserves |
| Exchange supply trajectory | 8.5M (Dec) → 6.82M low (late Apr) → 7.7M (May) → 7.28M on the narrower exchange-set measure | on-chain aggregators |
| Staked share | rising / stable | — |
| Holder concentration | stable | — |

Both sub-conditions lit → leg holds at **3/3**. Note the two reserve series above use different exchange sets and are not directly comparable in level; both agree on **direction**, which is what the rubric scores.

### 2.8 Correlation regime — **computed this cycle**

**30-day Pearson correlation of ETH daily log returns vs ^GSPC = +0.317**, window 2026-06-18 → 2026-07-31, overlapping sessions only, computed from Yahoo daily closes 2026-08-01 15:2x UTC.

Regime label: **mild**. Below the 0.7 risk-on surcharge trigger — **no [V]-gate surcharge**, no additional gate, no [V]-floor increase. Also below the Phase-2 `corr <0.8` bar. ETH is the most equity-correlated of the three assets in this batch (BTC 0.241, gold 0.240), but not by enough to change anything.

**A consequence worth naming:** because the surcharge is off, the **D2 Analyst Conviction Path is available** on gate-count grounds. It is barred whenever the risk-on surcharge is live, and it is not.

### 2.9 Macro

Identical to the BTC report and not re-derived here: 10y TIPS real yield **2.41%** (FRED DFII10, Jul-30), 10y nominal 4.67%, 3m T-bill **3.68%** (^IRX, Jul-31), VIX **15.99** (−13.94% over 5 sessions), DXY **99.80**, Brent **$90.12** (all Yahoo, Jul-31). Post-FOMC Jul-29 read hawkish.

---

## 3. Critical Developments

- **ETH ETFs closed July with +$365.17M in net inflows**, the strongest of the three major complexes, after breaking an eight-week outflow streak in early July. ([CoinGabbar](https://www.coingabbar.com/en/crypto-currency-news/crypto-etf-news-july-2026-bitcoin-ethereum-xrp-flows))
- **Jul-31 risk-off session:** ETH −2.8% to $1,866.57 alongside BTC −2.9% and SOL −2.0%. ([Motley Fool, Jul-31](https://www.fool.com/coverage/stock-market-today/2026/07/31/crypto-market-today-july-31-bitcoin-slides-below-usd63-000-and-coinbase-tumbles-10/))
- **Both BTC and ETH beat every major market in July** while chip stocks crashed — the AI-unwind decoupling is not a BTC-only phenomenon.
- **Exchange reserves at a decade low**, the drawdown explicitly uninterrupted through both rallies and corrections — the single most durable bull data point on ETH's board.
- **The Jul-23/24 funding capitulation episode has fully unwound** — see §2.4. This is the scored change in this report.
- **Clarity Act Senate window narrowing** — a shared crypto-complex regulatory overhang.

---

## 4. Fallen Knives Composite Score — ETH

| Category | Max | Input | Band | Score |
|---|---|---|---|---|
| Sentiment Extreme | 5 | F&G 3-day avg **26.67** | ≤35 → 2 (`compute.mjs band fk-sentiment 26.67` = 2) | **2** |
| Momentum Exhaustion | 4 | Weekly Wilder RSI-14 **41.40** | ≤45 → 1 (`fk-momentum 41.4` = 1) | **1** |
| Valuation | 5 | MVRV-Z decimal **UNOBTAINABLE** (re-verified); MVRV ratio proxy ~0.81 | held at prior value, flagged — debt clock report 3 | **4** |
| Capitulation Evidence | 3 | (a) liquidation decile **NO** · (b) funding negative ≥3 consecutive intervals **NO** (longest run 1 of 15) · (c) ETF outflows ≥2% AUM **NO** (July +$365M inflows) | 0/3 → 0 | **0** ▼ |
| Holder Behavior | 3 | (a) exchange reserves declining 30d ✓ (decade low) · (b) holder concentration / staked share stable ✓ | Both → 3 | **3** |
| **Leg sum** | 20 | | | **10** |

- **Leg sum: 10.0**
- **Mechanical score: 10** — read by the compound stop's score line, the Override arming condition, every §7 trim trigger, the EV-floor check, and the Verdict-Confidence Collar.
- **D1 discretionary term: 0.0** (see §9)
- **Raw composite: 10.0**
- **[V]-gate surcharge: NOT applied** (30d corr 0.317 < 0.70, sourced and computed)
- **Rounding convention: ETH half-down** (estimate-heavy input set → conservative on a buy signal; no half-point arose)
- **Adjusted score: 10/20** → *Accumulation Zone — Phase 1A eligible*

**Score change: 11 → 10 (−1), entirely the capitulation leg.** No other leg moved.

### Confirmation Gates — 2 / 8

Gate 5 (Hash Ribbon) is **N/A** — ETH is PoS, the canonical structural-inapplicability case. Denominator reduces to **8**. Note this leaves only two [T] gates (6, 9) on the board.

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | Sentiment ≤15 for ≥7 consecutive daily prints | ❌ | 0-day streak; lowest print in 10 sessions is 25. Needs a market-wide fear leg of June's depth. Reachable, requires a cascade. |
| 2 | [V] | Weekly RSI <30 | ❌ | 41.40 — the furthest of the three assets from its gate. Needs a sustained multi-week decline toward the low $1,500s held for several weeks. |
| 3 | [V] | Valuation cheap — MVRV-Z <1 | ✅ | Lit on the MVRV ratio proxy (~0.81 <1). Credited as in prior reports; note the underlying decimal is UNOBTAINABLE, so this gate rests on the proxy. |
| 4 | [V] | ETF outflows ≥2% AUM trailing month | ❌ | **Moving away, not toward.** July printed +$365.17M net *inflows*. Would require the July trend to reverse entirely and then run negative for a month. |
| 5 | [T] | Hash Ribbon | **N/A** | Structurally inapplicable (PoS) — denominator reduced to 8, never scored ❌. |
| 6 | [T] | Price within ±8% of the 200-week MA | ❌ | Spot −24.52% below the $2,481.69 200-week mean. **"none-in-regime"** — closing a 24.5-point gap requires a large, slow-moving change in either price or the long-horizon mean. This tag is informational and is not cited anywhere to lower a threshold or credit a gate. |
| 7 | [V] | Capitulation volume spike (top-decile 90d OR >3σ 30d) | ❌ | Jul-31 ordinary on the comparable venue set. Needs an ETH-specific print several multiples of Jul-24's $48.62M — realistically a disorderly break of $1,700. |
| 8 | [V] | LTH accumulation / holder concentration stabilizing | ✅ | Lit — reserves at a decade low, decline uninterrupted through rallies and corrections. |
| 9 | [T] | Macro catalyst neutral-to-positive | ❌ | Hawkish post-FOMC, real yield 2.41%, Clarity Act window narrowing. Relight: dovish inflection at the Sep FOMC, a soft Aug-07 payrolls / Aug-12 CPI pair, or Clarity Act passage. |

**Passed: 3, 8 → 2 of 8. [V] count: 2** (gates 3, 8).

Thresholds on an /8 board (`compute.mjs thresholds 8`): 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4). Note `ceil(7/9 × 8) = 7`, **not 6** — the arithmetic ETH misprinted in three Jun-2026 reports, restated here for the record.

Only gate 6 carries a "none-in-regime" tag; gates 1, 2, 4, 7 and 9 all have concrete paths, and gate 4's path runs *away* from lighting.

### Companion Flying Rocket score (Hard Rule 5) — **computed, not estimated**

**Channel routing (FR §2.5, verified on today's data):** ETH is −62.19% below its 1-year high **and** the 200dma is falling (−5.35% over 20 sessions, $2,221.55 → $2,102.71) with price −11.01% beneath it → **Channel B — Bear Continuation**. The Channel A phase-of-cycle cap does not bind because Channel A is not the live channel.

Channel B legs, computed from the same live fetch:

| Leg | Input | Score |
|---|---|---|
| Rally Extension | 40-session low **$1,510.51** (2026-06-26) → high since **$1,976.46** (Jul-27) = **+30.85%** | >25% → **4** |
| Local Momentum Exhaustion | **Daily** Wilder RSI-14 **51.78**; weekly RSI 41.40 < 50, hard qualifier passes | >45 but not >52 → **1** |
| Resistance Confluence | (c) below the Jul-27 lower high $1,976.46 ✓ · (a) 200dma −11.0% away ✗ · (b) spot is **+5.28% above** the 50dma, not below it and not just lost ✗ · (d) not credited | 1/4 → **1** |
| Bear Structure Integrity | (a) bounce high is a lower high ✓ · (c) no weekly close above the 200dma in 8 weeks ✓ · (b) 50–200 gap **narrowed** (−460.44 → −325.50) ✗ | 2/3 → **2** |
| Relative Sentiment / Positioning | (c) flow tell — **ETF inflows resuming into the rally** (July +$365.17M, the strongest complex) ✓ · (a) F&G 3d 26.67 not ≥1.5× its 30d mean, not ≥45 ✗ · (b) funding has not flipped positive after ≥5 negative sessions ✗ | 1/3 → **1** |
| **Raw** | squeeze-trap penalty 0 (funding not negative) · bounce-maturity floor N/A (rally 37 sessions old, ≥8) · corr surcharge off | **9 / 20** |

**FR ETH = 9/20** against a Channel B Phase-1A line of 13 — **short by 4**. Stall confirmation also **fails**: Aug-01 closed $1,871 against Jul-31's $1,860 — a *higher* close, not the required lower close. **STAND DOWN on the short side.**

**Cross-validation: FK 10 / FR 9.** Both below 12, so **Hard Rule 5's inconsistency condition (both ≥12) is NOT met** and the frameworks are consistent. The label stands **unqualified** — the Channel A cap is not binding, so the both-≥12 check is genuinely falsifiable here rather than vacuous.

But I will not dress this up: **FK 10 and FR 9 are not meaningfully inverse.** They are nearly equal, which on its face looks like the frameworks disagreeing about nothing. The FR skill's own Channel B note explains why this is expected rather than anomalous — in Channel B the two frameworks score **different objects on different horizons**: Fallen Knives is scoring "how cheap and capitulated is this asset for accumulation," Flying Rocket Channel B is scoring "is this specific counter-trend bounce dying into resistance." A 30.85% bounce off the June low that is now rolling over can be simultaneously a decent long-term accumulation zone and a decent tactical short. That is a coherent joint statement, not a contradiction.

**⚠️ STANDALONE COMPANION REPORT OWED.** The vacuity/tripwire rule requires a full standalone Flying Rocket report when the inline FR companion prints **≥9**. It prints exactly **9**. **This obligation is OWED and NOT DISCHARGED by this report** — an inline computation is not a standalone report, and I will not claim otherwise. It should be produced via `/flying-rocket-analytics eth` before the next FK ETH report. Recorded as action item 2 in §12.

For completeness on the other tripwires: the FK score crossed no phase-unlock threshold upward (it fell), the cap is not binding, and the ≥$100M short-side liquidation trigger rests on the same methodology-mismatched Loris source this report declined to score.

---

## 5. Probability Matrix — ETH

**Baseline row (adjusted score 10 → the 6–10 band): Rally 20 / Range 35 / Retest 30 / Bear 15.**

**§5 trend-residual state — stated as a boolean regardless of how cells were set:**
> **Active downtrend (below a major MA **AND** making lower lows): NO.**
> Price is 24.52% below the 200-week mean and 11.01% below a falling 200dma — the MA half is emphatically satisfied. The lower-lows half is **not**: the 40-session low is $1,510.51 (Jun-26), price has since rallied 30.85% to $1,976.46 and pulled back to $1,870 — comfortably above the low, and above the 50dma by 5.28%. The structure since late June is a higher-low sequence inside a broader bear.
> **Consequence, stated so it cannot be silently orphaned:** no bearish residual shift applied, **and** were the Deep-Value Override to fire it would do so at **half** nominal size, not quarter — the quarter-size throttle keys off this boolean and it is OFF.

**D4 adjustments from baseline** (all within 10 percentage points):

| Cell | Baseline | Set | Δ | Reason |
|---|---|---|---|---|
| Rally | 20 | **22** | +2 | July ETF inflows of +$365.17M are the strongest of any major complex; ETH outperformed BTC over the trailing 2 weeks (+0.45% vs −2.78%) |
| Range | 35 | **36** | +1 | Spot holding above the 50dma with the 50–200 gap narrowing |
| Retest | 30 | **28** | −2 | The $1,510.51 June low is 19% away and price is above the 50dma — a retest requires undoing more structure than the baseline row assumes |
| Bear | 15 | **14** | −1 | Funding at +2.56% ann leaves less long leverage to cascade than BTC carries |

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | 22% | $1,950 – $2,150 | $2,050 | Weekly close above the Jul-27 lower high $1,976.46; ETF inflows extend the July trend into August |
| **Range** | 36% | $1,800 – $1,950 | $1,875 | 50dma ($1,777.21) holds as support; funding stays modestly positive; flows stay net positive |
| **Retest** | 28% | $1,650 – $1,800 | $1,725 | Loss of the 50dma on a weekly close; ETF flows turn net-negative; retest toward the June structure |
| **Bear** | 14% | $1,450 – $1,650 | $1,550 | The $1,510.51 June low breaks; a genuine ETH-specific liquidation flush lights gates 1/2/7 together |

Sum = **100%** ✓ · Rally 22% ≤ 50% cap ✓

**Weighted EV recomputation (final step, from the printed cells only):**
```
0.22 × 2,050 = 451.00
0.36 × 1,875 = 675.00
0.28 × 1,725 = 483.00
0.14 × 1,550 = 217.00
               ------
EV           = 1,826.00
```
Verified: `compute.mjs ev --spot 1870.40 --stated 1826.00` → `rel_diff_pct 0, within_tolerance true, prob_sum_ok true, rally_cap_ok true`.

**Weighted EV = $1,826.00. EV-vs-spot = −2.37%.**
**Realized trailing-2-week price change: +0.45%.** The EV is negative while realized momentum was mildly *positive* — the two disagree in sign, and the disclosure exists so that is visible rather than buried. The EV is negative because the modal Range midpoint ($1,875) sits essentially at spot while 42% of the mass sits below it; it is a statement about the distribution's skew, not a forecast that the last two weeks were fake.

**EV-floor consistency check:** EV-vs-spot is negative, but the trigger requires **mechanical score ≥15 AND 3-day F&G ≤15**. Mechanical is 10 and F&G 3d is 26.67 — **neither limb met, no inconsistency flag.**

**Terminal-vs-extreme:** not compelled — the §5 trend residual is not live. Stated anyway: Range being modal is where I expect price to *end* the horizon, not a claim that $1,510.51 was the low. If Retest resolves, the path extreme sits in the $1,650–1,800 band. The word "floor" appears nowhere on the modal row.

---

## 6. Deployment Strategy — ETH

**Total dry powder: ~95% of the ETH book.**
**Dry-powder yield benchmark: 3-month T-bill 3.68%** (Yahoo ^IRX, Jul-31) — ~31 bp/month of measurable opportunity cost.

### Position & Performance (Hard Rule 8)

`node tools/position.mjs eth` — **band STALE**, age **1,561 minutes (26.0 h)**, driver `holdings_as_of` (2026-07-31 13:15 UTC).

> ⚠️ **AGE BANNER — STALE (12–72 h).** Descriptive use only. A STALE snapshot **may not satisfy a phase-dependent unlock precondition** and **may not fill a realized ledger column**. Nothing below authorizes a fill.

| Field | Value | Note |
|---|---|---|
| Custody status | **RECONCILED** | live balance agrees with the fill replay; deposits 0, withdrawals 0, off-venue 0 |
| Live quantity | **0.00006517 ETH** | position of record — **dust** |
| Trade-derived quantity | 0.00006517 ETH | agrees |
| `basis.reliable` | **FALSE** | 24 unbacked disposals exceed the replay by **8.5064 ETH** |
| Average cost / cost basis / unrealized PnL / ROI | **NOT REPORTED** | see below |
| Realized PnL | $447.02 | **upper bound, not a result** |
| Dust unbacked qty | 0.00026124 | — |
| Short qty | `null` | **explicitly not a margin short** — a short would be borrow-corroborated and reported via `short_qty` with a real basis |
| Attribution | **UNTAGGED** | `performance_by_tag_prefix` empty — no `FK-` tag exists on this account |

**`basis.reliable` is FALSE, and it is a separate question from custody.** Custody is clean — zero withdrawals, live balance matching the replay. The ledger simply cannot derive what these coins cost: 24 unbacked disposals exceeded the replayed position by 8.5064 ETH, meaning coins were sold whose acquisition was never ingested. The snapshot is explicit that this is **not** a margin short. Per Hard Rule 8 I quote **no** average cost, cost basis, unrealized PnL or ROI, and the $447.02 realized figure is an **upper bound** — this asset's fill history is incomplete, so its realized ledger is partial. The **quantity is the position of record**.

**Real dry powder: $14,288.54** account-wide (USDT $9,552.96, USDC $4,735.58). Total portfolio $19,665.31. Futures equity $0.00.

**Realized performance, account-wide** (91 deals, 13 open): win rate **67.03%**, profit factor **4.94**, expectancy **$59.01**. **Per-tag performance is unavailable** (`performance_by_tag_prefix` empty) — I cannot state how ETH Phase 1A entries have performed and will not assert it.

#### Position Reconciliation — the ledger wins

| Figure | Prior report (Jul-25) narrated | Ledger (STALE, Jul-31 13:15 UTC) | Delta |
|---|---|---|---|
| Phase 1A first rung | ~5% of book FILLED @ ~$1,844 | **0.00006517 ETH — dust** | The narrated fill has no counterpart in the live balance |
| Phase 1A remainder | ~5% laddered $1,800–1,825, WORKING as unfilled limits | `dry_powder` **includes** stablecoins locked in open orders; no per-order breakdown available | Cannot confirm or refute the working orders from this snapshot |
| Blended cost | ~$1,844, MTM +0.65% | **not derivable** (`basis.reliable` false) | The cost figure has never been checked against a fill |
| Book size | "5% of book" | **$19,665.31 total, $14,288.54 stable** | "5%" was 5% of an unstated notional |

Custody is `RECONCILED` with zero withdrawals, so this is not a cold-storage case. But because the fill history is provably incomplete (8.5064 ETH of unbacked disposals), "the position was sold" is **not** a supportable conclusion either. What is defensible: **the ledger has no evidence of a held ETH position and no evidence of what one would have cost.** Per Hard Rule 4 governing the absence of usable evidence, the deployment table carries the prior narration forward flagged **UNVERIFIED**, and authorizes nothing against it.

### Phases

| Phase | Size | Entry zone | Score line | Gates required | Status |
|---|---|---|---|---|---|
| **1A** | 10% | $1,800–1,880 | ≥8 ✅ (10) | ≥3/8, [V]≥2 ❌ (2/8, [V] 2) | **PARTIALLY DEPLOYED per prior report (~5% filled, ~5% laddered) — ⚠️ UNVERIFIED.** Spot $1,870.40 is **inside** the zone. Gate count blocks any *new* 1A authorization; the pre-assigned remainder needs no fresh unlock under the partial-tranche rule. |
| **1B** | 15% | $1,600–1,750 | **≥11 ❌ (10) — SCORE-BLOCKED** | ≥5/8, [V]≥3 ❌ (2/8, [V] 2) | **DOUBLE-BLOCKED.** Score short by 1; gates short by 3. |
| **2** | 30% | $1,450–1,600 | ≥15 ❌ (10) | ≥6/8, [V]≥3 ❌ | FROZEN |
| **3** | 45% | requires weekly capitulation candle | mech ≥17 ❌ (10) | ≥7/8, [V]≥4 ❌ | DRY POWDER |

**The Phase 1B divergence from BTC is the structural point of this batch.** Both assets ran the same 2026-07-27 cut from ≥13 to ≥11. BTC prints 11 and clears it; ETH prints 10 and misses by one — and the single point of difference is the capitulation leg that reverted this week. Where BTC's 1B is now blocked on gates alone, **ETH's is blocked on score first**, which is the more restrictive lock because it cannot be reached by the D2 conviction path at all (D2 substitutes for a *gate*, never for the score condition).

**D2 Analyst Conviction Path — AVAILABLE for Phase 1A, and DECLINED.**
Phase 1A meets its score condition (10 ≥ 8) and falls short on gate count by **exactly one** (2 of 3 required), with the [V] floor met on lit gates (2 ≥ 2). The risk-on surcharge is off (corr 0.317), so the path is not barred. **The conditions for a D2 unlock are met.** I decline it — reasoning in §9.4, but the short version is that it would buy nothing: the partial-tranche rule already lets the pre-assigned 1A remainder deploy in its zone without a fresh unlock, so a D2 unlock would purchase a half-nominal authorization for capital that is *already authorized*, at the price of a hard D5 stop and a 10-day discretionary bar in the phase. Paying a stop for an entry you already have is a bad trade.

**Deep-Value Override — evaluated, DOES NOT FIRE.** Mechanical score 10 < 15 — dispositive. (Second independent failure: 3-day F&G 26.67 is not ≤15.) Max drawdown from spot to the compound thesis line ($1,350): **−27.82%**, stated as standing disclosure; it purchases no loosening.

**Non-mechanical capital cap:** 0% of book via D1/D2/Override. The 40% cap and the 25% Override sub-cap are untouched.

### Stops

**No stop parameter changed value this report. Stop Migration Ledger: one line, checkpoint date only.**

| Tier | Level | Note |
|---|---|---|
| **Catastrophic floor** | **$1,300** | Unchanged. Strictly below the deepest named buy-zone floor. |
| **Compound thesis stop** | **$1,350 price AND mechanical score <12** | Unchanged. Score line 12 (ETH standard, not a pinned-score asset). |
| Deepest named buy-zone floor | $1,470 (Phase 2 zone $1,450–1,600 → floor stated as $1,470 per the prior report's convention for this series) | — |
| D5 discretionary stops | **none — no analyst-channel tranche exists** | — |

**Compound stop disclosure (carried, no migration).** Mechanical score is **10**, which is **<12** — the score axis **is satisfied**, so the stop is effectively **price-gated at $1,350** until the score re-crosses 12. This is unchanged in kind from Jul-25 (which had score 11) but it is now **further** from restoring two-key protection: the score fell a point, so ETH needs to regain 2 points rather than 1. The prior report flagged that a Jul-26 close ≤$1,830.61 would take score to 12 and restore protection; that was a *tightening* path and it did not materialize — the score went the other way. **This is a real erosion of stop quality and it is disclosed rather than buried: it is not a widening (no parameter moved) but the protective condition is now less likely to be restored than it was a week ago.**

**Stop-vs-buy-zone coherence check (catastrophic tier):**
> **CATASTROPHIC stop $1,300 strictly below deepest active buy-zone floor $1,470? → PASS.**
> Verified: `compute.mjs stop-coherence --catastrophic 1300 --floor 1470` → `pass: true`.
> No prospective ladder is named below $1,470 anywhere in this report, so no post-activation re-stop or atomic activation sequence is owed. No "stop realignment owed" flag.

**D6 ratchet compliance:** every parameter held or moved toward price. Only the checkpoint date changed.

**Stop Migration Ledger:**

| Parameter | Tier | Old | New | Direction | Rationale |
|---|---|---|---|---|---|
| Checkpoint date | checkpoint date | 2026-07-26 | **2026-08-02** | forward roll | The Jul-26 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below $1,350). Rolls to the next weekly close. |

**Checkpoint prognosis (calendar-locked).**
Checkpoint **Sunday, 2026-08-02, 00:00 UTC weekly close** — verified a real weekly boundary on the crypto weekly calendar (week-start UTC, the same boundary used for the RSI computation); crypto venues trade continuously so no holiday or abbreviated-session correction applies. It **fires iff** ≥2 consecutive weekly closes print below $1,350 **and** the mechanical score is <12. Spot $1,870.40 sits **38.55% above** the $1,350 line, a distance of **18.20× the 5-day ADR of $53.46**. Closes below the line: **0 of the 2 required** — the checkpoint therefore **cannot** fire on Aug-02 regardless of price, a structural statement about the condition rather than a forecast. **Tier-1 US releases between this report and the Aug-02 checkpoint: NONE** — the next is nonfarm payrolls **Friday 2026-08-07 08:30 ET**, then CPI **Wednesday 2026-08-12 08:30 ET**.

**Time stop:** the Phase 1A first rung carries a 90-day reassessment horizon from its stated fill. As with BTC, that horizon is **unenforceable until the position is verified** — a further reason the ledger reconciliation outranks the market call.

### Ledger tag

No tranche fills this report, so no tag is issued. A 1A remainder fill in the $1,800–1,825 ladder carries **`FK-P1A`**; a D2 unlock (declined here) would have carried **`FK-D2`** and a D5 stop. Applied via `PUT /api/investments/deal-note`, first note line `report=reports/eth_fallen_knives_20260801_1117.md`. **The account carries no `FK-` tags at all** — every holding is `UNTAGGED` and cannot resolve a phase-dependent unlock precondition.

---

## 7. Exit / Trim Framework — ETH

Hard Rule 2: evaluated in full every report. **Every score condition reads the MECHANICAL score (10).**

| Trigger | Threshold | Current | Fires? |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | campaign peak **12** (mechanical, ETH series) → trim at ≤6 | **10** | **NO** — 2 points off peak |
| F&G ≥75 sustained 7d AND weekly RSI >70 | — | F&G 27, RSI 41.40 | NO |
| MVRV-Z >3 or drawdown <10% with vertical 30d return | — | MVRV ratio ~0.81, drawdown −62.13% | NO |
| Mechanical score ≤3 AND price ≥40% above blended cost | — | score 10; blended cost **not derivable** | NO — second limb **untestable** while `basis.reliable` is false |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime | the ≥5-session green bar **was** met (the streak broken Jul-25), so the regime precondition **is satisfied** | trailing month **net positive +$365.17M** | NO — magnitude is the opposite sign |
| Narrative break | — | none — no regulatory ban, no founder fraud, no critical breach, no irreparable tokenomics change | NO |

**Current exit status: NONE. No trim, no exit.** Position as narrated: ~5% Phase 1A filled + ~5% laddered working, both UNVERIFIED.

The ≥6-point drop trigger sits 4 points away — comfortably clear, and note it just moved 1 point *closer* as the capitulation leg reverted. That is the correct behaviour: the trim rule is supposed to track signal decay, and signal did decay this week.

---

## 8. Critical Watchlist — ETH

**Mandatory tier-1 US enumeration, next 5 trading days (Aug-03 → Aug-07), verified against the BLS/CME release schedule:**

| Date (ET) | Time | Event | Tier | ETH impact |
|---|---|---|---|---|
| Mon Aug-03 | — | *no tier-1 release* | — | — |
| Tue Aug-04 | — | *no tier-1 release* | — | — |
| Wed Aug-05 | — | *no tier-1 release* | — | — |
| Thu Aug-06 | — | *no tier-1 release* | — | — |
| **Fri Aug-07** | **08:30** | **Nonfarm Payrolls / Employment Situation** | **TIER 1** | The horizon's dominant macro event. A soft print is the most direct path to relighting gate 9; a hot print extends the post-FOMC hawkish drift. |

**Beyond the window:** CPI **Wed Aug-12 08:30 ET**, PPI Thu Aug-13, Advance Retail Sales Fri Aug-14.

**This report's horizon contains no unenumerated tier-1 release.**

| Ongoing | Event | Impact |
|---|---|---|
| Rolling | Daily ETH ETF flow prints | The August continuation of July's +$365.17M is the single most informative ETH-specific series |
| Rolling | Exchange reserve trajectory | The decade-low decline is the most durable bull data point; a reversal would take the holder leg from 3 to 1.5 |
| Rolling | Senate Clarity Act window | Gate 9 |
| Weekly | Sun Aug-02 00:00 UTC weekly close | Stop checkpoint (structurally cannot fire) |
| **Owed** | **Standalone Flying Rocket ETH report** | Triggered by the inline FR companion printing 9 (≥9 tripwire) |

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

ETH is 62% below its all-time high, 24.5% below its 200-week mean, and 19% below the aggregate cost basis of everyone who owns it — and the thing that strikes me most about the tape is how *orderly* it is. Funding sits at +2.56% annualized, roughly half of BTC's. Exchange reserves are at a decade low and have declined uninterrupted through both rallies and corrections. Liquidations on Jul-31 were ordinary. Price is 5.28% above its 50-day and has held a higher-low sequence since June 26. This is not a market in distress. It is a market that has already been repriced and is now sitting still.

That orderliness is exactly what makes the score fall to 10 rather than rise, and I think the score is telling the truth. The framework's five legs are asking "is this asset cheap *and* has the selling exhausted itself in a way I can measure?" ETH answers the first half emphatically — the valuation leg is at 4 on a proxy that I suspect is one point too generous to the market, not too harsh — and answers the second half with a shrug. There is no funding capitulation. There is no liquidation flush. And there is the awkward fact that the framework's institutional-fear gate is dark because ETH ETFs took in $365 million in July after eight straight negative weeks. You do not get to score institutional capitulation while institutions are the marginal buyer.

The Jul-23/24 funding episode is the useful case study here, and I want to give the prior report credit for it. That report scored the capitulation leg on four consecutive negative intervals while explicitly stating the condition had **fired but was not standing** — funding was already fully positive by the time the report was written. It scored it anyway because the rubric is a plain count with no standing qualifier. A week later the live Binance funding series shows a longest consecutive negative run of **one** across fifteen intervals. The leg reverts, the score falls to 10, and Phase 1B closes on score. The framework did not get whipsawed by this because the prior report told the truth about what it was scoring. That is what the disclosure rules are for, and it is worth saying out loud when they work.

Where does that leave the position? Spot is inside the $1,800–1,880 Phase 1A zone, and the pre-assigned remainder of that tranche can deploy in the $1,800–1,825 ladder without any fresh unlock. That is the right amount of activity for a 10-score tape: finish the first tranche if the ladder fills, and wait. What I am *not* willing to do is manufacture a Phase 1B authorization out of a conviction case, and the framework agrees — the D2 path substitutes for a gate, never for the score condition, and ETH is short on score.

### 9.2 What the rubric structurally cannot see

1. **The MVRV-Z sourcing gap cuts against the asset.** The decimal is UNOBTAINABLE and the leg is held at 4 on a ratio proxy. The one circulating decimal (−0.7) is two months stale at a 10%-lower price, and I declined it — correctly, but the likely truth is that ETH is *cheaper* than the leg scores. *Cuts bullish; deliberately not scored.*
2. **Funding at half BTC's level.** The capitulation leg is a binary count of negative intervals; it cannot express that ETH carries materially less long leverage than BTC at +2.56% vs +4.73%. Less cascade fuel means a lower probability of the Bear tail — which is a *structural* fact the rubric only sees if funding actually goes negative. *Cuts bullish.*
3. **The ETF flow inversion.** Gate 4 and capitulation-(c) both key on outflows. ETH's flows are strongly positive, which the framework can only register as *absence of fear evidence* — it has no way to register institutional accumulation as a positive. *Cuts bullish, and is invisible by construction.*
4. **Reserve decline "uninterrupted through both rallies and corrections."** The holder leg is a 30-day direction boolean. It cannot express *persistence through regime changes*, which is the property that makes a reserve trend informative rather than incidental. *Cuts bullish.*
5. **The bounce is 37 sessions old and rolling over.** The FK legs have no bounce-maturity concept at all; only the FR Channel B rubric does, and it scores this configuration a 9. *Cuts bearish* — and it is the reason I did not take a positive D1 term.

### 9.3 The D1 term

**D1 = 0.0.**

This one required more argument than BTC's, because four of the five factors in §9.2 cut bullish and three of them (items 1, 2, 4) are sourced and genuinely outside the legs. On the letter of the rule, a **+1.0** is constructible: the MVRV proxy is conservative, funding carries half BTC's leverage, and the reserve decline has persisted through regime changes. That would take the composite to 11 — **exactly the Phase 1B unlock line.**

I decline it, and the reason is the strongest available one: **that adjustment would be the sole enabler of a phase unlock, on an asset where the mechanical evidence just deteriorated.** The score fell to 10 because the capitulation leg reverted on live primary-source data. Using discretion to put back the exact point the tape just took away — and to unlock a 15% tranche with it — is not analysis, it is a rebuttal of the measurement I just made. The D1 term exists to see what the legs cannot, not to overrule what they can.

There is a second reason and it is dispositive on its own: **Phase 1B is short by 3 gates as well as by 1 point of score.** Even at 11 the tranche stays blocked (2 of 8 lit vs 5 required, [V] 2 vs 3), and D2 cannot bridge a 3-gate shortfall. So a +1.0 would cross an unlock threshold *on paper* while authorizing nothing — the worst of both worlds: it would trigger the decay clock, place the report's score above a line the gates still bar, and invite exactly the "the score says 1B" confusion this framework has been burned by before.

I also considered **−0.5** for factor 5 (a mature bounce rolling over, FR Channel B at 9) and declined it, because the FK legs and the FR score are already both printed and cross-validated. Scoring the same bounce twice — once as an FR 9, once as an FK haircut — is double-counting across frameworks.

**Falsifier for the zero:** if a current, sourced ETH MVRV-Z decimal below 0.1 is obtained (retiring the debt clock), the valuation leg moves to 5 **mechanically** and the score reaches 11 without any discretionary term — which is the correct way for this thesis to reach that line. Conversely, if ETH loses the 50dma ($1,777.21) on a weekly close while ETF flows turn net-negative, factors 2, 3 and 4 are all refuted together and a negative term becomes arguable.

### 9.4 Discretionary actions taken and declined

| Action | Channel | Disposition | Reason |
|---|---|---|---|
| Score adjustment +1.0 | D1 | **DECLINED** | Would be the sole enabler of a threshold cross, restoring the exact point the tape just removed; and 1B stays gate-blocked at 11 anyway, so it authorizes nothing while triggering a decay clock |
| Score adjustment −0.5 | D1 | **DECLINED** | Double-counts the mature-bounce read already expressed by the FR Channel B companion score of 9 |
| **Phase 1A conviction unlock** | **D2** | **AVAILABLE — DECLINED** | All conditions met: score 10 ≥ 8, gate count short by **exactly one** (2 of 3), [V] floor met on lit gates (2 ≥ 2), risk-on surcharge off (corr 0.317). Declined because it buys nothing: the partial-tranche rule already authorizes the pre-assigned 1A remainder to deploy in the $1,800–1,825 ladder without a fresh unlock. A D2 unlock would purchase half-nominal authorization for capital that is already authorized, and pay for it with a hard D5 price-only stop and a 10-day bar on analyst channels in that phase. Paying a stop for an entry you already hold is a bad trade. |
| Probability cells set by hand | D4 | **TAKEN** | Rally +2 / Range +1 / Retest −2 / Bear −1 vs baseline; all within 10pp; reasons tabled in §5 |
| Deep-Value Override | mechanical, not discretionary | **DOES NOT ARM** | Mechanical score 10 < 15 |

The D2 near-miss is the most important line in this table. It is a documented case of the conviction path being **genuinely available and correctly unused** — the framework's own note says near-misses are evidence too, and this one is evidence that the partial-tranche rule and the D2 path overlap in a way that makes D2 redundant for a phase already part-filled. Worth carrying into the next calibration.

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 | Term set to **0.0**; +1.0 and −0.5 both considered and declined | n/a | n/a | A sourced ETH MVRV-Z <0.1 would take the leg to 5 mechanically (no D1 needed); loss of the 50dma with negative flows would justify a negative term | **live** | n/a |
| 2026-08-01 | D2 | Phase 1A conviction unlock **AVAILABLE and DECLINED** — substituting gate would have been gate 1 (sentiment streak) or gate 7 (capitulation spike); [V] floor met on lit gates | would have been 5% (half of 10% nominal) | would have been the $1,510.51 Jun-26 swing low, ~19.2% below spot — **capped to 15% below fill per D5**, i.e. ~$1,589.84 | Declined on redundancy with the partial-tranche rule; re-examine if the 1A remainder fills and gates remain at 2/8 | **retired (declined)** | n/a |
| 2026-08-01 | D4 | Cells set by hand: 22/36/28/14 vs baseline 20/35/30/15 | n/a | n/a | A weekly close outside $1,800–1,950 retires the Range-modal read | **live** | n/a |

No prior open discretionary entries — the Analyst Discretion Layer shipped 2026-07-27 and this is ETH's first report under it. **The layer is N=1 on this asset.** The D2 entry above is the framework's **first recorded conviction-path evaluation**, and it resolved to a decline.

### 9.6 What would change my mind

**Bullish flip, dated:** a weekly close above **$1,976.46** (the Jul-27 lower high) by **Sun 2026-08-30**, with August ETF flows extending July's net-positive run. That is a trend-structure event on this framework's own definition — it would repair the lower-high sequence, void the FR Channel B regime, and convert the accumulation posture to hold-and-let-it-run.

**Bearish flip, dated:** a weekly close below the 50dma **$1,777.21** by **Sun 2026-08-16** with ETF flows turned net-negative. That would put a fresh lower low in play, activate the §5 trend residual, throttle any future Override to quarter-size, and make the $1,600–1,750 Phase 1B zone a live ladder — at which point the score would need to *earn* its way to 11 rather than be argued there.

**What would not change my mind:** another isolated negative funding print. Two of the last fifteen intervals were negative and neither lasted. Under the rubric's plain count, three consecutive would re-score the leg; under my own read, a 32-hour deleveraging episode that reverses inside a day is noise, and I said so about the last one before it unwound.

---

## 10. Bull vs Bear Scorecard — ETH

**Bull (✅) — 7**
1. Drawdown −62.13% from ATH; 24.52% below the 200-week mean
2. Spot ~19% below the aggregate holder cost basis (MVRV ratio ~0.81)
3. Exchange reserves at a decade low, ~−1M ETH over 30 days, decline uninterrupted through both rallies and corrections
4. July ETF inflows +$365.17M — strongest of the major complexes, after breaking an eight-week outflow streak
5. Funding +2.56% ann, roughly half BTC's — materially less long leverage to cascade
6. Higher-low structure since Jun-26; spot +5.28% above the 50dma; 50–200 gap narrowing
7. Outperformed BTC over the trailing 2 weeks (+0.45% vs −2.78%) at 30d SPX correlation 0.317

**Bear (❌) — 7**
1. Capitulation leg reverted to 0 — the Jul-23/24 funding episode did not recur (longest negative run 1 of 15)
2. Score fell 11 → 10, closing Phase 1B on the score condition
3. Weekly RSI 41.40 — the furthest of the three assets from its gate-2 threshold
4. Gate 6 "none-in-regime" — 24.52% below the 200-week mean, structurally unreachable near-term
5. Gate board 2/8, the thinnest of the three assets in this batch
6. FR Channel B companion at 9/20 — a 30.85% bounce, 37 sessions old, rolling over into resistance
7. Real yield 2.41% with a hawkish post-FOMC read; Clarity Act window narrowing

**Net: 7–7, exactly balanced.** Verdict-Confidence Collar **ACTIVE** on all three limbs independently: the scorecard is balanced (within 1), |EV-vs-spot| is 2.37% — *above* the 2% threshold, so that limb is **not** met — and the **mechanical score of 10 falls inside the 6–10 band**, which is met. Two limbs of three. **No directional regime resolution may be claimed in §12.**

---

## 11. Change Log vs 2026-07-25

| Factor | Previous (Jul-25) | Current (Aug-01) | Direction |
|---|---|---|---|
| Canonical spot | $1,855.89 | $1,870.40 | +0.78% |
| **Mechanical score** | **11** | **10** | **▼ −1** |
| — sentiment leg | 2 | 2 (3d avg 26.67) | flat |
| — momentum leg | 1 | 1 (RSI 41.40) | flat |
| — valuation leg | 4 | 4 (MVRV-Z still UNOBTAINABLE, debt clock report 3) | flat |
| — **capitulation leg** | **1** (4 consecutive negative funding intervals, flagged "fired but not standing") | **0** (longest negative run 1 of 15) | **▼ −1 — the only leg that moved** |
| — holder leg | 3 | 3 | flat |
| D1 discretionary | n/a (pre-layer) | **0.0**, logged; +1.0 and −0.5 declined | new |
| Gates | 2/8 (3, 8) | 2/8 (3, 8) | flat |
| **Phase 1B** | score-blocked (11<13, old line) | **score-blocked (10<11, new line)** — missed the cut line by 1 | still blocked, now by 1 point |
| **Phase 1A D2 path** | not available (pre-layer) | **AVAILABLE (short by exactly 1 gate) — DECLINED** | new, first evaluation in framework history |
| Weekly RSI | ~41 | 41.40 | flat |
| Funding | 4 consecutive negatives Jul-23/24, then fully positive | +2.56% ann, longest negative run 1 of 15 | reverted |
| ETF flows | positive streak broken Jul-25 (−$70.62M) | **July closed +$365.17M**, strongest complex | stronger |
| 30d corr vs SPX | not stated | **0.317, computed** | newly sourced |
| FR companion | n/a | **9/20 Channel B** — **≥9 tripwire fired** | standalone report OWED |
| EV-vs-spot | not restated here | −2.37% | — |
| Position (ledger) | narrated ~5% @ ~$1,844 + ~5% working | **dust, basis unreliable, STALE, UNTAGGED** | **material divergence flagged** |

---

## 12. Strategic Verdict — ETH

**Adjusted score 10/20 · Mechanical 10/20 · D1 0.0 · Weighted EV $1,826.00 · EV-vs-spot −2.37% · realized 2-week +0.45% · F&G 27 (3d avg 26.67, Fear) · Gates 2/8 ([V] 2) · Stance: HOLD; finish the 1A ladder if it fills; authorize nothing new.**

The score fell to 10 and it fell for an honest reason. A week ago this series scored a capitulation point on four consecutive negative funding intervals while stating plainly, in the same report, that the condition had already reversed — that it was a 32-hour deleveraging episode, not a standing regime. The live Binance series now shows a longest consecutive negative run of one across fifteen intervals. The point comes back off. Nothing was whipsawed, because the prior report told the truth about what it was scoring, and that is worth more to this framework than the point was.

What the decline costs is Phase 1B, and the way it costs it is instructive when set against BTC. Both assets ran the same 2026-07-27 cut of the 1B line from ≥13 to ≥11. BTC prints 11, clears the score condition for the first time in its campaign, and is now blocked on gates alone. ETH prints 10, misses by one, and is blocked on score *first* — which is the harder lock, because the D2 conviction path substitutes for a gate and never for a score. One point of capitulation evidence is the entire difference between "waiting for the market to confirm" and "waiting for the score to recover." I could have manufactured that point with a +1.0 discretionary term, and I had three sourced, legs-invisible factors to hang it on. I declined, because using discretion to restore the exact point the tape just removed — and to unlock a 15% tranche with it — is the failure mode the mechanical/adjusted split was written to prevent. If ETH deserves an 11, a sourced MVRV-Z decimal below 0.1 will give it one mechanically, and that debt is now three reports old.

The uncomfortable truth underneath all of this is that ETH's fear evidence is thin because ETH is not being feared. Institutions bought $365 million of it in July after eight straight negative weeks. Exchange reserves are at a decade low and falling through both rallies and corrections. Funding carries half the long leverage BTC does. Liquidations are ordinary. This is a cheap, orderly, structurally sound market — which is a fine thing to own and a poor thing to score on a rubric that measures capitulation. The framework is not wrong to sit at 2 of 8 gates. It is telling you that the discount is real and the panic is not, and those are different trades.

### Action items

1. **Refresh the position snapshot to FRESH (≤12 h) before the next report.** 26 h old on `holdings_as_of`; a re-link (`POST /link`) is required, not merely a re-export. Until then no phase-dependent unlock precondition can be satisfied from the ledger in either direction.
2. **Produce the standalone Flying Rocket ETH report.** The inline companion printed **9**, firing the ≥9 tripwire. This obligation is **owed and not discharged** by the inline computation here. Run `/flying-rocket-analytics eth` before the next FK ETH report.
3. **Clear the MVRV-Z debt clock — report 3.** Ship a sourced or computed current decimal next report, or state explicitly why it cannot be obtained. The circulating "−0.7" reading traces to a single 2026-06-08 article at ETH $1,684 and was correctly declined; note that if it is directionally right, the valuation leg is scored one point too low and the composite would mechanically reach the Phase 1B line.
4. **Resolve the ETH position discrepancy.** Custody is `RECONCILED` with zero withdrawals, but `basis.reliable` is false — 24 unbacked disposals exceed the replay by 8.5064 ETH, so the fill history is provably incomplete and neither "held" nor "sold" is supportable. Reconcile against Binance trade history directly.
5. **Tag existing holdings** (`FK-P1A` etc. via `PUT /api/investments/deal-note`, first line `report=…`). `performance_by_tag_prefix` is empty, so the framework cannot read back per-phase realized performance on money.
6. **Let the Phase 1A remainder work in the $1,800–1,825 ladder.** Spot $1,870.40 is inside the $1,800–1,880 zone. The pre-assigned remainder needs no fresh unlock under the partial-tranche rule; upsizing beyond nominal is prohibited. **Do not** take the available D2 unlock — it buys nothing and costs a hard D5 stop plus a 10-day discretionary bar.
7. **Watch the 50dma ($1,777.21) as the operative level**, not the 200-week mean. Gate 6 is "none-in-regime" at −24.52%; the 50-day is where this tape actually resolves.
8. **Mark Aug-07 payrolls (08:30 ET)** as the horizon's one tier-1 event. Nothing lands before the Aug-02 checkpoint, which structurally cannot fire (0 of 2 required weekly closes).

> ### The Pattern
>
> **IF** ETH closes a week above $1,976.46 (the Jul-27 lower high) while August ETF flows extend July's +$365.17M run → **THEN** the lower-high sequence is repaired, the FR Channel B regime is voided, and the accumulation posture converts from waiting to holding. Falsifier: either condition unmet by Sun 2026-08-30.
>
> **IF** ETH loses the 50dma ($1,777.21) on a weekly close with ETF flows turned net-negative → **THEN** a fresh lower low comes into play, the §5 trend residual activates, any future Override throttles to quarter-size, and the $1,600–1,750 Phase 1B zone becomes a live ladder — reached by a score that earned its way back to 11, not one argued there. Falsifier: a weekly close back above $1,900 by Sun 2026-08-16.
>
> **IF** a current sourced MVRV-Z prints below 0.1 → **THEN** the valuation leg moves to 5, the composite reaches 11 mechanically, and Phase 1B's score condition clears with no discretion involved — leaving a 3-gate shortfall as the only remaining lock. That is the difference between a thesis and an argument.
>
> ETH is the cheapest asset on this desk and the one with the least fear attached to it. Those two facts are not in tension; they are the whole trade. Ninety-five percent dry at a 3.68% carry, with a ladder working into the zone, is the correct expression of both.

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-ETH-20260801-1117 | UNVERIFIED | crypto |
| 1B | FK-P1B-ETH-20260801-1117 | LOCKED | crypto |
| 2 | FK-P2-ETH-20260801-1117 | LOCKED | crypto |
| 3 | FK-P3-ETH-20260801-1117 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: eth_fallen_knives_20260801_1117.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-08-01",
  "spot": { "value": 1870.40, "source": "median of 4 synchronized live quotes: Binance ETHUSDT $1,872.06 / CoinGecko $1,870.88 / Coinbase ETH-USD $1,869.93 / Kraken XETHZUSD $1,869.79 (all 2026-08-01 15:27 UTC); spread 0.121%, all live, no staleness exclusion" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 4, "capitulation": 0, "holder": 3 },
    "discretionary": 0,
    "mechanical": 10,
    "raw": 10,
    "adjusted": 10,
    "rounding": "half-down"
  },
  "gates": { "active": 8, "na": [5], "passed": [3, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 22, "low": 1950, "high": 2150 },
      { "name": "Range", "p": 36, "low": 1800, "high": 1950 },
      { "name": "Retest", "p": 28, "low": 1650, "high": 1800 },
      { "name": "Bear", "p": 14, "low": 1450, "high": 1650 }
    ],
    "stated_ev": 1826.00,
    "vs_spot_pct": -2.37
  },
  "deployment": {
    "deployed_pct": 5,
    "dry_pct": 95,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "1800-1880 zone, spot 1870.40 INSIDE it; prior report narrates ~5% filled plus ~5% laddered 1800-1825 working. NO entry_price is asserted: basis.reliable=false (24 unbacked disposals exceed the replay by 8.5064 ETH) and the live quantity is dust, so encoding a numeric fill would assert a cost basis this report explicitly declines to state. Status UNVERIFIED pending a FRESH snapshot. Gate count 2/8<3 blocks NEW 1A authorization; the pre-assigned remainder needs no fresh unlock (partial-tranche rule)", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "1600-1750 DOUBLE-BLOCKED: score 10<11 (the 2026-07-27 cut line, missed by 1) AND gates 2/8<5, [V] 2<3. D2 cannot bridge a score shortfall — it substitutes for a gate only", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "1450-1600 frozen (score 10<15, gates 2/8<6)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 10<17)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1470,
    "compound": { "price": 1350, "score_line": 12 },
    "note": "NO stop parameter changed value. Mechanical score 10<12, so the compound stop's score axis IS satisfied — stop effectively price-gated at $1,350 until score re-crosses 12. This is NOT a widening (no parameter moved) but it is a disclosed EROSION of stop quality: the score fell from 11 to 10, so restoring two-key protection now requires regaining 2 points rather than 1, and the Jul-25 report's flagged tightening path (a Jul-26 close <=1830.61 taking score to 12) did not materialize. Coherence: catastrophic $1,300 strictly below deepest named zone floor $1,470 = PASS (compute.mjs stop-coherence pass:true). No D5 stops — zero analyst-channel tranches exist; the available D2 unlock was DECLINED so no D5 stop attaches. Max drawdown spot-to-compound-line -27.82%, disclosed; purchases no loosening. D6 ratchet: compliant, only the checkpoint date moved.",
    "migration": [
      { "parameter": "checkpoint date", "tier": "checkpoint date", "old": "2026-07-26", "new": "2026-08-02", "direction": "forward roll", "rationale": "Jul-26 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below 1350); rolls to the next weekly close" }
    ],
    "checkpoint": {
      "date": "2026-08-02",
      "line": 1350,
      "condition": ">=2 consecutive weekly closes <1350 AND mechanical score <12",
      "closes_below": 0,
      "adr": 53.46,
      "dist_x_adr": 18.20,
      "side": "spot 38.55% above line; structurally cannot fire (0 of 2 required closes); no tier-1 release before the checkpoint — next is NFP Fri 2026-08-07 08:30 ET, then CPI Wed 2026-08-12"
    }
  },
  "companion_fr": {
    "score": 9,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 62.19, "ma200_falling": true, "ma200_slope20_pct": -5.35, "price_below_ma200_pct": -11.01 },
    "legs_channel_b": { "rally_extension": 4, "local_momentum": 1, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 1510.51, "low_40s_date": "2026-06-26", "high_since": 1976.46, "bounce_pct": 30.85, "daily_rsi14": 51.78, "weekly_rsi14": 41.40, "bounce_age_sessions": 37 },
    "gates_note": "Channel B Phase 1A line 13 — short by 4; stall confirmation FAILS (Aug-01 close 1871 > Jul-31 close 1860)",
    "cross_validation": "consistent — FK 10 / FR 9, both <12 so Hard Rule 5's both->=12 condition is NOT met; label UNQUALIFIED because the Channel A cap is not binding. DISCLOSED HONESTLY: 10 and 9 are nearly equal rather than strongly inverse. Per the FR skill's own Channel B note this is expected, not anomalous — in Channel B the frameworks score different objects on different horizons (FK: accumulation value; FR-B: whether a specific counter-trend bounce is dying into resistance).",
    "standalone_report_owed": true,
    "standalone_report_trigger": "inline FR companion printed >=9 (exactly 9) — OWED and NOT DISCHARGED by this report; run /flying-rocket-analytics eth before the next FK ETH report"
  },
  "position": {
    "source": "tools/position.mjs eth",
    "band": "STALE",
    "age_min": 1561,
    "age_driver": "holdings_as_of",
    "custody_status": "RECONCILED",
    "qty": "0.00006517",
    "basis_reliable": false,
    "oversold_qty": "8.50642325",
    "unbacked_disposal_count": 24,
    "short_qty": null,
    "avg_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd_upper_bound": 447.02,
    "attribution": "UNTAGGED",
    "dry_powder_stable_usd": 14288.54,
    "portfolio_total_usd": 19665.31,
    "note": "STALE band — descriptive use only; may NOT satisfy a phase-dependent unlock precondition and may NOT fill a realized ledger column. basis.reliable=false: 24 unbacked disposals exceeded the replayed position by 8.5064 ETH — coins sold whose acquisition was never ingested. The snapshot states explicitly this is NOT a margin short (short_qty null). No average cost, cost basis, unrealized PnL or ROI reported; realized PnL is an upper bound and the fill history is partial. Position Reconciliation: prior report narrates ~5% Phase 1A filled at ~$1,844 plus ~5% laddered 1800-1825 working; the ledger shows dust with no derivable basis and zero withdrawals. Custody RECONCILED rules out cold storage, but the provably incomplete fill history bars concluding 'sold' either. Reported UNVERIFIED; no deployment authorized against it."
  },
  "trend_residual": { "active_downtrend": false, "basis": "24.52% below the 200-week mean and 11.01% below a falling 200dma, but NOT making lower lows — the 40-session low $1,510.51 (Jun-26) has held, price rallied 30.85% off it and sits +5.28% above the 50dma in a higher-low sequence", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned" },
  "correlation": { "value_30d_vs_spx": 0.317, "window": "2026-06-18 to 2026-07-31", "method": "Pearson on daily log returns, overlapping sessions, Yahoo closes, computed 2026-08-01", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.317 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds" },
  "discretion": {
    "d1_considered_declined": [
      { "value": 1.0, "reason": "would be the SOLE enabler of the Phase 1B threshold cross, restoring the exact point the tape removed when the capitulation leg reverted on live primary-source funding data; and 1B stays gate-blocked at 11 (2/8 vs 5 required, [V] 2 vs 3), so it authorizes nothing while starting a decay clock" },
      { "value": -0.5, "reason": "double-counts the mature-bounce read already expressed by the FR Channel B companion score of 9" }
    ],
    "d2_available": true,
    "d2_taken": false,
    "d2_phase": "1A",
    "d2_detail": "All conditions met: score 10>=8, gate count short by EXACTLY ONE (2 of 3 required), [V] floor met on lit gates (2>=2), risk-on surcharge OFF (corr 0.317). DECLINED because the partial-tranche rule already authorizes the pre-assigned 1A remainder to deploy in the 1800-1825 ladder with no fresh unlock — a D2 unlock would buy half-nominal authorization for already-authorized capital at the price of a hard D5 price-only stop and a 10-day bar on analyst channels in that phase. First recorded conviction-path evaluation in framework history; resolved to a decline.",
    "d4_taken": true
  },
  "key_inputs": {
    "fng_spot": 27,
    "fng_3d_avg": 26.67,
    "fng_streak_le15_days": 0,
    "weekly_rsi14": 41.40,
    "weekly_rsi_closes_used": 262,
    "daily_rsi14": 51.78,
    "sma_200w": 2481.69,
    "pct_vs_sma200w": -24.52,
    "ma200d": 2102.71,
    "ma200d_slope20_pct": -5.35,
    "ma50d": 1777.21,
    "pct_vs_ma50d": 5.28,
    "mvrv_z": "decimal UNOBTAINABLE — standing declaration (2026-07-20) RE-VERIFIED today; debt clock at report 3, sourced or computed decimal owed next report",
    "mvrv_z_declined_source": "a circulating '-0.7 / 7-year low' reading traces to a single Phemex/BeInCrypto article dated 2026-06-08 citing Glassnode at ETH $1,684 — two months stale at a ~10% lower price; DECLINED per the provenance-citation rule. If directionally right, the valuation leg is scored one point too low (4 rather than 5) and the composite would mechanically reach 11, the Phase 1B line",
    "mvrv_proxy_ratio": 0.81,
    "realized_price_proxy": 2304,
    "drawdown_from_ath_pct": -62.13,
    "funding_ann_pct": 2.56,
    "funding_negative_intervals_in_15": 2,
    "funding_longest_negative_run": 1,
    "funding_note": "capitulation-(b) requires >=3 CONSECUTIVE negative intervals; the two negatives (Jul-28 08:00, Jul-31 16:00) are isolated single prints. The Jul-23/24 four-interval episode that scored this leg on Jul-25 — and which that report flagged as FIRED BUT NOT STANDING — has not recurred. Leg reverts 1 -> 0.",
    "exchange_reserves_30d": "-~1,000,000 ETH (~$2B) to ~15.1M, a decade low; decline explicitly uninterrupted through both rallies and corrections",
    "etf_flow_july_usd_m": 365.17,
    "etf_flow_note": "strongest of the major complexes; broke an eight-week outflow streak in early July; week ending Jul-24 +$103.9M; Jul-25 -$70.62M ended a 5-session green streak. Gate 4 and capitulation-(c) are moving AWAY from lighting, not toward it",
    "liquidations_jul31_network_usd_m": 360,
    "liquidations_jul31_widest_venue_set_usd_m": 1450,
    "liquidations_methodology_note": "the $1.45B Loris figure covers a materially wider exchange set than every prior print in this series ($270-320M band) — disclosed and flagged PROVISIONAL, NOT scored; the series' comparable-venue convention is held and Jul-31 reads ordinary",
    "adr5": 53.46,
    "realized_2w_change_pct": 0.45,
    "tbill_3m_pct": 3.68,
    "real_yield_10y_tips_pct": 2.41,
    "vix": 15.99,
    "dxy": 99.80,
    "tier1_next_5_sessions": ["NFP Fri 2026-08-07 08:30 ET"],
    "tier1_beyond_window": ["CPI Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14"],
    "stale_input_debt": ["valuation leg — MVRV-Z decimal UNOBTAINABLE, report 3 of the debt clock, held at prior value 4 on the ratio proxy and flagged inline"]
  },
  "collar": { "band_triggered": true, "reasons": ["mechanical score 10 is inside the 6-10 band", "bull/bear scorecard 7-7, balanced"], "ev_limb_met": false, "ev_limb_note": "|EV-vs-spot| 2.37% exceeds the 2% threshold, so that limb is NOT met; the other two are", "effect": "no directional regime resolution claimed" },
  "verdict": "HOLD; let the pre-assigned Phase 1A remainder work the $1,800-1,825 ladder; authorize nothing new. ~95% dry at a 3.68% T-bill carry. SCORE DOWN 11 -> 10 on the CAPITULATION leg, the only leg that moved. THE LEG THAT MOVED AND WHY IT IS CLEAN: the Jul-25 report scored a point on four consecutive negative funding intervals while explicitly flagging the condition as FIRED BUT NOT STANDING (funding was fully positive again before that report was written). Live Binance USDT-M ETHUSDT now shows a longest consecutive negative run of ONE across fifteen intervals (two isolated negatives, Jul-28 08:00 and Jul-31 16:00), so the >=3-consecutive condition fails and the leg reverts to 0. The framework unwound a transient on schedule rather than being whipsawed by it. CONSEQUENCE, AND THE CONTRAST WITH BTC: both assets ran the same 2026-07-27 cut of the Phase 1B line from >=13 to >=11. BTC prints 11 and is now blocked on GATES alone; ETH prints 10 and is blocked on SCORE first — the harder lock, because D2 substitutes for a gate and never for a score. D2 EVALUATED AND DECLINED (first conviction-path evaluation in framework history): Phase 1A meets every D2 condition — score 10>=8, gate count short by exactly one (2 of 3), [V] floor met on lit gates, surcharge off at corr 0.317 — and is declined because the partial-tranche rule already authorizes the 1A remainder, so a D2 unlock would buy already-authorized capital at the price of a hard D5 stop and a 10-day phase bar. D1 = 0.0: a +1.0 was constructible on three sourced legs-invisible factors (conservative MVRV proxy, funding at half BTC's leverage, reserve decline persisting through regime changes) and DECLINED because it would be the sole enabler of a threshold cross restoring the exact point the tape just took away — and 1B stays gate-blocked at 11 regardless. VALUATION SOURCING: the circulating ETH MVRV-Z '-0.7 / 7-year low' traces to a single 2026-06-08 article at ETH $1,684 and was declined under the provenance rule; the decimal stays UNOBTAINABLE at report 3 of the debt clock, and if that reading is directionally right this leg is scored one point too low. THE UNCOMFORTABLE TRUTH: ETH's fear evidence is thin because ETH is not being feared — July ETF inflows +$365.17M (strongest complex, after eight negative weeks), exchange reserves at a decade low falling through both rallies and corrections, funding at half BTC's, liquidations ordinary. Cheap and orderly, not cheap and panicked. FR COMPANION 9/20 Channel B — the >=9 tripwire FIRED and a standalone FR report is OWED and NOT DISCHARGED here. POSITION (Hard Rule 8, STALE at 26h): dust, custody RECONCILED, basis.reliable=false on 8.5064 ETH of unbacked disposals, UNTAGGED — narrated fills reported UNVERIFIED, no PnL or cost basis quoted, nothing sized against it. Collar ACTIVE (mechanical 10 in the 6-10 band; scorecard 7-7): no directional regime resolution claimed.",
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "eth_fallen_knives_20260801_1117.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "ETH",
      "report_date": "2026-08-01",
      "report_local_time": "11:17",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-ETH-20260801-1117",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-ETH-20260801-1117",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-ETH-20260801-1117",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-ETH-20260801-1117",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "eth_fallen_knives_20260801_1117.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "ETH",
    "report_date": "2026-08-01",
    "report_local_time": "11:17",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-ETH-20260801-1117",
      "FK-P1B-ETH-20260801-1117",
      "FK-P2-ETH-20260801-1117",
      "FK-P3-ETH-20260801-1117"
    ],
    "status": "REGISTERED"
  }
}
```
