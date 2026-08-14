# 🔪 FALLEN KNIVES ANALYTICS — GOLD (held as PAXG) — 2026-08-01

## SATURDAY MIDDAY — ALL DATA LIVE INTERNET-VERIFIED — COMEX CLOSED
### Report Generated: Saturday, August 1, 2026, 11:17 EDT (15:17 UTC)
### Asset: GOLD | Prior Score: 8/20 (2026-07-25) | Mechanical Score: 8/20 | D1: 0.0 | Adjusted Score: 8/20

---

## 1. What this report is deciding, and one naming matter first

The user asked for **PAXG**. This report resolves that request the way the framework requires: **the analytical asset is GOLD (XAU), and the position is held as PAXG.** Those are two different things and the distinction is disclosed, never silently collapsed.

- **Canonical spot** comes from independent gold sources under Hard Rule 1 — it is **not** sourced from the PAXG token and never will be.
- **The position** is read from the ledger, which cannot hold bullion, so `gold` resolves onto **PAXG** — tokenized gold tracking XAU roughly 1:1, but carrying **issuer and custody counterparty risk that physical gold does not**, and able to trade at a premium or discount to spot.

Today that premium/discount is small and worth stating: PAXG's live median is **$4,042.41** against a canonical gold spot of **$4,049.10** — a **−0.17% discount**, well inside normal tracking noise. Small today is not small always, and the counterparty risk does not disappear because the tracking is tight.

The substantive question: nothing has moved. The score holds at 8, the gate board holds at 2/8, deployment stays FROZEN at 25% deployed. What *has* changed is a framework line rather than a market one — the 2026-07-27 cut of the Phase 1A unlock from ≥10 to ≥8 means gold's score now clears a phase line it has never cleared before. That turns out to matter less than it sounds, and §6 explains exactly why.

---

## 2. Verified Live Data Points — GOLD

### 2.1 Canonical spot reconciliation — and an honest handling of a closed market

**Today is Saturday. COMEX is closed.** The gold futures complex last printed on Friday and will not print again until Sunday evening. This is the one asset in this batch where the "synchronized quotes" requirement genuinely cannot be satisfied by live venue quotes, and the rule says to say so rather than paper over it.

| Source | Price | Timestamp | Status |
|---|---|---|---|
| Yahoo GC=F (COMEX front month) | **$4,049.10** | 2026-07-31 settle | **frozen — market closed, age ~1 day, anchored to report-publication time** |
| Yahoo MGC=F (micro gold) | $4,049.10 | 2026-07-31 settle | frozen — same session, same complex |
| LiteFinance quoted XAU/USD | $4,035.73 | 2026-07-31 | frozen — cash-market quote, −0.33% vs the futures settle |

**Canonical gold spot = $4,049.10** (the Friday COMEX settle, carried forward explicitly as a frozen print).

**Fewer than 3 synchronized live quotes are obtainable, and I state that plainly.** The two Yahoo rows are the same complex and do not constitute independent corroboration; the third is a cash quote from a different session convention. Per the reconciliation rule this is disclosed, and the low-confidence handling is considered below.

**Live cross-check from a 24/7 venue.** PAXG trades continuously and gives a genuine synchronized read on where gold is being marked right now:

| Source | PAXG price | Timestamp (UTC) | Status |
|---|---|---|---|
| Binance PAXGUSDT | $4,045.84 | 2026-08-01 15:27 | live |
| Kraken PAXGUSD | $4,042.43 | 2026-08-01 15:27 | live |
| Coinbase PAXG-USD | $4,042.40 | 2026-08-01 15:27 | live |
| CoinGecko pax-gold | $4,042.30 | 2026-08-01 15:27 | live |

**PAXG median = $4,042.41**, inter-source spread **0.088%** — tight, four live venues, no disagreement worth flagging.

**Is a low-confidence demotion warranted? No, and here is the reasoning the rule asks for.** The dispersion in the gold panel is **staleness-driven and time-ordered**, not genuine simultaneous venue disagreement: a Friday settle versus a Saturday token mark separated by a weekend, differing by **0.17%** — inside the 0.5% flag and far inside anything that would move the EV sign. The synchronized PAXG panel, which *is* simultaneous, runs 0.088%. There is no venue disagreement to demote for. The frozen quotes are labeled, aged, and retained rather than silently dropped.

**Trailing 2-week realized price change: +0.91%** (Yahoo GC=F, Jul-17 $4,012.70 → Jul-31 $4,049.10). Gold has drifted mildly higher over two weeks. Stated so the negative EV in §5 is read against realized momentum.

### 2.2 Sentiment — NOT FOUND, and why that is the correct answer

| Metric | Reading | Source | Status |
|---|---|---|---|
| Daily gold fear instrument | **NOT FOUND** | — | **Scored conservatively at 2 per the primary fallback** |
| DSI / HGNSI | not obtained | typically paywalled / non-daily | disclosed as regime context only, **not scored** |
| GVZ (gold volatility index) | not obtained this cycle | — | context only if obtained; never the scored input |

The rubric is explicit: gold has no reliably sourceable asset-native daily fear instrument, this is the **expected default**, and the correct handling is to score the leg **2** and flag NOT FOUND rather than substitute a proxy. Two substitutions are specifically prohibited and both are declined here: a **price-derived** proxy (which would double-count the valuation leg) and a **positioning-derived** proxy — **COT is PROHIBITED as the sentiment leg input** because it already drives the capitulation leg's washout read, and one input may not key two legs.

**Stale-input debt clock:** this leg has been carried at the NOT-FOUND fallback across the gold series for many reports. The rubric names this as the expected default for gold rather than a defect, but the debt is real and the honest statement is: **a daily gold fear instrument cannot be obtained from free sources, and the leg will continue to score 2 until one is.** That is the "state explicitly why it cannot be obtained" branch of the rule, discharged.

### 2.3 Momentum, valuation, structure

| Metric | Value | Source |
|---|---|---|
| **Weekly Wilder RSI-14** | **38.98** (261 completed weekly closes, period 14, Yahoo weekly boundary UTC week-start, last completed week 2026-07-27) | computed, `tools/fetch.mjs gold` 15:17 UTC |
| Weekly RSI incl. live week | 41.52 | same |
| **200-week SMA** | **$2,843.68**; spot **+42.39%** above it → **within ±8% = FALSE** | computed, exact |
| 200-day MA | **$4,479.49, RISING +0.52%** over trailing 20 sessions; spot −9.61% beneath | computed from Yahoo GC=F |
| High (10y window) | **$5,586.20** (2026-01-26); **drawdown −27.52%** | Yahoo GC=F 10y weekly high — **NOT an all-time high; window flagged per the tool's own caveat** |
| 1-yr high | $5,586.20 (2026-01-26); −27.52% | Yahoo trailing-1y weekly highs |
| Trading range | $3,965 – $4,165 — gold has not established direction outside it | LiteFinance, Jul-31 |
| 5-day ADR | **$46.52** (Jul-27 35.20 / Jul-28 10.60 / Jul-29 16.80 / Jul-30 90.00 / Jul-31 80.00) | computed, `compute.mjs adr` |

**ADR session-exclusion disclosure:** all five sessions are full COMEX sessions with no holiday abbreviation in the window (no US market holiday fell between Jul-27 and Jul-31), so none is excluded and the lookback was not extended. This matters because an abbreviated session shrinks the ADR and flatters the checkpoint distance ratio in §6.

**The drawdown denominator, carried forward as an open item.** The −27.52% is measured against a **10-year window high**, not a verified all-time high — the fetch tool flags this itself. The prior report noted the same and observed that nothing currently turns on it, since the valuation leg scores 0 and gate 3 would need $2,793. That remains true, and the item remains open.

### 2.4 Valuation band-set — the low-vol adaptation stays withdrawn

**Band-set used: standard drawdown-from-ATH.** The low-volatility adaptation was **WITHDRAWN on 2026-07-18** and this report does not reinstate it. Reinstating it would require documenting gold's realized 30-day volatility at ≤½ of BTC's contemporaneous 30-day realized vol, sourced and timestamped, plus a Change Log entry naming the historical bear distribution it anchors to. That documentation has not been produced and I am not going to produce it inside a report that has no need of it.

**Drawdown −27.52% → standard band `<30% → 0`.** Verified: `compute.mjs band fk-drawdown 27.52` → band **0**.

Stated plainly: **gold is not cheap on this measure.** A −30% drawdown (score 1) requires **$3,910.34**. The ≥50% level that would light gate 3 requires **$2,793.10**. Spot is $4,049.10. The valuation leg scoring 0 while the asset sits 27.5% off its high is the band-set working as designed — a 27% drawdown in a low-volatility metal is not the same event as a 27% drawdown in BTC, and the framework declines to pretend otherwise.

### 2.5 COT positioning — the capitulation leg's (b) condition

CFTC Commitments of Traders, gold futures, **reporting date Tuesday 2026-07-28** (published Fri 2026-07-31):

| Metric | Value | WoW change |
|---|---|---|
| Large speculator net long (futures only) | **182,070 contracts** (219,622 long − 37,552 short) | **−1,840 contracts (−1.00%)** |
| Long leg | 219,622 | −5,163 |
| Short leg | 37,552 | −3,323 |
| Futures + options combined net long | — | **−18,835 contracts** |

Source: [GoldSeek COT report, Jul-31 2026](https://goldseek.com/article/cot-gold-silver-usdx-report-july-31-2026).

**Read the short leg, not just the net.** The futures-only net fell only 1,840 contracts, but that number conceals real deleveraging: **5,163 longs left and 3,323 shorts covered simultaneously.** Both sides reduced. The net barely moved because the two reductions nearly offset, not because positioning was static. The combined futures+options figure — down 18,835 — shows the fuller picture of speculative repositioning.

**Does this qualify as a washout? NO.** The gold-adapted gate-1 / capitulation-(b) bar is a WoW non-commercial net-long decline of **≥20–30K contracts or ≥15% of the net.** The actual print is **−1,840 contracts, −1.00% of the net** — an order of magnitude short of the bar on both tests. Even the combined −18,835 falls below the 20K contract floor and is well under 15%. The source's own characterization matches: modest positioning reduction, routine profit-taking, not panic-driven capitulation.

**Provenance for the streak claim (provenance-citation rule):** the prior report (2026-07-25) printed COT at −1.49% WoW, also below the bar. Two consecutive prints below the washout threshold. No backdating of any washout regime is claimed, and none exists to claim.

### 2.6 Gold ETF flows — the one lit [V] gate

| Window | Figure | Source |
|---|---|---|
| **June 2026 global (WGC)** | **−US$8.9bn outflows, ALL REGIONS negative**; total AUM −13% to US$526bn; holdings −74t to 4,047t | [World Gold Council](https://www.gold.org/goldhub/research/gold-etfs-holdings-and-flows/2026/06) |
| Month to Jul-24 | **−76.44 tonnes** net | TGD global gold ETF tracker, Jul-24 |
| Investment demand | fell to 262t including **45t of ETF outflows** | LiteFinance / WGC-derived, Jul-31 |

This is the gold-adapted gate-4 condition: a trailing-month global (WGC) net outflow that is **multi-region** and **among the worst of the trailing 12 months**. June's −$8.9bn with every region negative and a 13% AUM decline meets it, and the July MTD figure of −76.44t extends rather than reverses it. **Gate 4 ✅ and capitulation-(c) fires.**

This is genuinely the strongest bear-capitulation evidence on gold's board, and it is the reason the score is 8 rather than 7.

### 2.7 Holder behaviour

| Metric | Value | Source |
|---|---|---|
| Central bank / official sector demand | stable, continuing structural accumulation | WGC-derived, carried |
| Concentration | stable | carried |

Both sub-conditions lit → leg holds at **3/3**, carried from the prior report. This is the weakest-sourced leg in the report and I flag it as such: it is carried rather than freshly verified this cycle.

### 2.8 Correlation regime — **computed this cycle**

**30-day Pearson correlation of GC=F daily log returns vs ^GSPC = +0.240**, window 2026-06-18 → 2026-07-31, overlapping sessions only, computed from Yahoo daily closes 2026-08-01 15:2x UTC.

Regime label: **mild**. Below the 0.7 risk-on surcharge trigger — **no [V]-gate surcharge**, no gate or [V]-floor increase. Also below the Phase-2 `corr <0.8` bar. Essentially identical to BTC's 0.240 over the same window.

### 2.9 Macro — the variable that actually governs gold

| Metric | Level | Δ | Source | Read for gold |
|---|---|---|---|---|
| **10y TIPS real yield** | **2.41%** | −0.02 over 5 prints | FRED DFII10, Jul-30 | **The core headwind.** A 2.41% real yield is a hard hurdle for a zero-coupon asset. |
| 10y nominal | 4.67% | — | AhaSignals, Jul-31 | — |
| 3m T-bill (^IRX) | 3.68% | −0.67% over 2w | Yahoo, Jul-31 | Dry-powder benchmark |
| DXY | **99.80** | **−1.65% over 5 sessions** | Yahoo DX-Y.NYB, Jul-31 | **Mild tailwind** — a softening dollar is gold-supportive |
| VIX | 15.99 | −13.94% over 5 sessions | Yahoo ^VIX, Jul-31 | No haven bid; falling vol is gold-negative at the margin |
| Brent | $90.12 | — | Yahoo BZ=F, Jul-31 | Elevated; a mild inflation-hedge argument |
| Fed | Post-FOMC Jul-29, hawkish read, growing hawkish contingent | — | market commentary, Jul-31 | Headwind |

The bear case that market commentary keeps returning to is precisely this trio: **ETF outflows, elevated Treasury yields, and a growing hawkish FOMC contingent.** The one offsetting move is the dollar, down 1.65% in five sessions.

---

## 3. Critical Developments

- **Gold is range-bound and the range is explicit:** no clear direction until it breaks out of **$3,965–$4,165**. Spot at $4,049.10 sits almost exactly mid-range. ([LiteFinance, Jul-31](https://www.litefinance.org/blog/analysts-opinions/gold-price-prediction-forecast/gold-buys-the-dip-forecast-as-of-31072026/))
- **Investment demand fell to 262 tonnes**, including 45t of ETF outflows — the demand-side weakness limiting upside.
- **June WGC flows: −US$8.9bn, all regions negative**, AUM −13% to US$526bn, holdings −74t. The heaviest month of the current outflow regime.
- **COT positioning is NOT washed out** — futures-only net long −1,840 contracts (−1.00%) WoW, characterized by the source as routine profit-taking rather than capitulation.
- **The 200-day MA is RISING (+0.52%)** while price sits 9.61% beneath it. This is structurally different from BTC and ETH, both of which have falling 200-days, and it has a direct consequence for the companion framework (§4).
- **Bears rely on ETF outflows, elevated yields, and hawkish Fed voices** — the consensus bear stack, all three of which are live.

---

## 4. Fallen Knives Composite Score — GOLD

| Category | Max | Input | Band | Score |
|---|---|---|---|---|
| Sentiment Extreme | 5 | **NOT FOUND** — no reliably sourceable daily gold fear instrument | primary fallback → 2, flagged | **2** |
| Momentum Exhaustion | 4 | Weekly Wilder RSI-14 **38.98** | ≤40 → 2 (`compute.mjs band fk-momentum 38.98` = 2) | **2** |
| Valuation | 5 | Drawdown **−27.52%** from the 10y-window high, **standard band-set** (low-vol adaptation withdrawn 2026-07-18, not reinstated) | <30% → 0 (`fk-drawdown 27.52` = 0) | **0** |
| Capitulation Evidence | 3 | (a) vol/volume flush **NO** · (b) COT washout **NO** (−1,840 contracts, −1.00%, vs a ≥20–30K or ≥15% bar) · (c) gold-ETF outflow spike **YES** (WGC June −$8.9bn all regions, −74t; Jul MTD −76.44t) | 1/3 → 1 | **1** |
| Holder Behavior | 3 | (a) official-sector demand stable/rising ✓ · (b) concentration stable ✓ — **carried, not freshly verified this cycle** | Both → 3 | **3** |
| **Leg sum** | 20 | | | **8** |

- **Leg sum: 8.0**
- **Mechanical score: 8** — read by the compound stop's score line, the Override arming condition, every §7 trim trigger, the EV-floor check, and the Verdict-Confidence Collar.
- **D1 discretionary term: 0.0** (see §9)
- **Raw composite: 8.0**
- **[V]-gate surcharge: NOT applied** (30d corr 0.240 < 0.70, sourced and computed)
- **Rounding convention: GOLD half-up** (no half-point arose)
- **Adjusted score: 8/20** → *Accumulation Zone — Phase 1A eligible on score*

**Score change: 8 → 8. No leg moved.** Third consecutive report at 8.

### Confirmation Gates — 2 / 8

Gate 5 (Hash Ribbon) is **N/A** — structurally inapplicable to a non-mined-blockchain asset. Denominator **8**.

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | **Gold substitution:** CONFIRMED COT positioning washout (replaces the daily-sentiment streak) | ❌ | Needs a WoW non-commercial net-long decline of ≥20–30K contracts or ≥15% of the net, then held or extended on the following weekly print. Latest is −1,840 (−1.00%) — an order of magnitude short. Reachable on a real flush; not close today. |
| 2 | [V] | Weekly RSI <30 | ❌ | 38.98. Needs a sustained multi-week decline; roughly a break of the $3,965 range floor held for several weeks. |
| 3 | [V] | Valuation cheap (≥50% drawdown from ATH) | ❌ | **"none-in-regime"** — requires $2,793.10, i.e. a further −31% from spot. Structurally unreachable without a large, slow-moving repricing. Note this is a *regime* judgment, not the "none-by-construction" tag: that tag applies only under the low-vol adaptation, which is withdrawn, so gate 3 is a live-but-distant gate here. |
| 4 | [V] | **Gold substitution:** sustained physical gold-ETF outflows (WGC multi-region, among the worst of trailing 12 months, GLD-corroborated) | ✅ | Lit — June −$8.9bn all regions, AUM −13%, holdings −74t; July MTD −76.44t. |
| 5 | [T] | Hash Ribbon | **N/A** | Structurally inapplicable — denominator reduced to 8, never scored ❌. |
| 6 | [T] | Price within ±8% of the 200-week MA | ❌ | Spot is **+42.39% above** the $2,843.68 200-week mean. **"none-in-regime"** — gold is far above its long-run mean, not below it. This is the mirror image of ETH's gate-6 failure and is worth noting: gold fails this gate from *expensiveness*, ETH from *cheapness*. |
| 7 | [V] | Capitulation volume spike | ❌ | No vol/volume flush. Needs a disorderly break of the $3,965 range floor on outsized volume. |
| 8 | [V] | LTH accumulation / holder concentration stabilizing | ✅ | Lit — official-sector demand stable, concentration stable. Carried, not freshly verified. |
| 9 | [T] | Macro catalyst neutral-to-positive | ❌ | Real yield 2.41%, hawkish post-FOMC, growing hawkish contingent. Relight: a dovish inflection, a soft Aug-07 payrolls / Aug-12 CPI pair driving real yields down, or continued dollar weakness beyond the −1.65% already seen. |

**Passed: 4, 8 → 2 of 8. [V] count: 2** (gates 4, 8). Identical to the prior report.

Thresholds on an /8 board: 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4).

Gates 3 and 6 carry "none-in-regime" tags; gates 1, 2, 7 and 9 have concrete reachable paths. Neither tag is cited anywhere below to lower a threshold, reduce the denominator, or credit a gate — the default conclusion stands that dark gates are correctly dark.

### Companion Flying Rocket score (Hard Rule 5) — **computed, not estimated**

**Channel routing (FR §2.5, verified on today's data) — and gold routes differently from BTC and ETH.**

| Test | Value | Result |
|---|---|---|
| >20% below 1-year high? | −27.52% | **YES** |
| 200dma falling over trailing 20 sessions? | $4,456.28 → $4,479.49, **+0.52% RISING** | **NO** |

Both Channel B regime conditions must hold. The 200dma is **rising**, so **Channel B's regime test FAILS**. And >20% below the 1-year high forecloses Channel A. The routing result is therefore **"No channel — STAND DOWN"** — the genuine phase-of-cycle mismatch the original cap was written for: a basing tape that is neither a distribution top nor a confirmed downtrend.

Per the rule, Channel A is **capped at 8** and scored for the record:

| Leg | Input | Score |
|---|---|---|
| Euphoria Sentiment | no daily gold fear instrument; no euphoria evidence of any kind | **0** |
| Momentum Overextension | weekly RSI 38.98, far below the <60 floor | **0** |
| Valuation Extreme | fallback distance from ATH **−27.52%** → the 15–30% band (`compute.mjs band fr-ath 27.52` = 1) | **1** |
| Distribution Evidence | (c) ETF net outflows after a sustained inflow regime ✓ (WGC June −$8.9bn) · (a) ✗ · (b) ✗ | **1** |
| Structural Vulnerability | (a) no perp-funding convention for spot gold ✗ · (b) put/call not obtained ✗ · (c) not at ATH ✗ | **0** |
| **Raw** | cap applied | **2 / 20** |

**FR GOLD = 2/20**, i.e. **2 of 8 attainable**.

**Cap-regime vacuity disclosure (mandatory in the stand-down case):**
> Score **2 / 8 attainable** (2/20 nominal). **Interpretation bands ≥9 are unreachable — no phase is reachable at any score, with the Channel A Phase-1A line of 11 sitting 3 points above the cap. The Hard-Rule-5 both-≥12 check is structurally unfalsifiable in this regime.**
> **Cross-validation: structurally consistent (cap-bound; both-≥12 unfalsifiable by construction)** — never a bare consistency ✅ while the cap binds.

**Which of the two regime conditions would have to change** (the stand-down case requires naming them): either gold rallies to within 20% of its $5,586.20 1-year high — i.e. **above $4,468.96** — which restores Channel A eligibility, **or** the 200dma rolls over from its current +0.52% slope to falling, which routes to Channel B. Neither is close today: the 200dma at $4,479.49 is rising and price is 9.61% beneath it.

FR 2 is well below the ≥9 tripwire, so **no standalone companion report is owed**. FK 8 / FR 2 are strongly inversely related.

---

## 5. Probability Matrix — GOLD

**Baseline row (adjusted score 8 → the 6–10 band): Rally 20 / Range 35 / Retest 30 / Bear 15.**

**§5 trend-residual state — stated as a boolean regardless of how cells were set:**
> **Active downtrend (below a major MA **AND** making lower lows): NO.**
> Price is 9.61% below the 200dma — the MA half is satisfied. The lower-lows half is **not**, and gold's case is the cleanest of the three assets: the 200dma is **rising** (+0.52% over 20 sessions), price has been range-bound in $3,965–$4,165 without establishing direction, and no fresh lower low has printed. This is a basing structure, not a downtrend.
> **Consequence, stated so it cannot be silently orphaned:** no bearish residual shift applied, **and** were the Deep-Value Override to fire it would do so at **half** nominal size, not quarter — the quarter-size throttle keys off this boolean and it is OFF.

**D4 adjustments from baseline** (all within 10 percentage points):

| Cell | Baseline | Set | Δ | Reason |
|---|---|---|---|---|
| Rally | 20 | **22** | +2 | DXY −1.65% over 5 sessions; the 200dma is rising rather than falling |
| Range | 35 | **38** | +3 | The $3,965–$4,165 range is explicit, spot sits mid-range, and the source's own read is that gold establishes no direction until it breaks out |
| Retest | 30 | **27** | −3 | A retest requires breaking a range floor that has held; the rising 200dma argues against a fast resolution lower |
| Bear | 15 | **13** | −2 | Official-sector demand remains a structural bid beneath the market |

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | 22% | $4,200 – $4,450 | $4,325 | Break of the $4,165 range top; real yields fall on a soft Aug-07 payrolls; DXY weakness extends |
| **Range** | 38% | $3,965 – $4,200 | $4,082.50 | The $3,965–$4,165 range continues to contain price; no macro catalyst resolves |
| **Retest** | 27% | $3,750 – $3,965 | $3,857.50 | Break of the $3,965 range floor; ETF outflows extend; real yields push above 2.5% |
| **Bear** | 13% | $3,450 – $3,750 | $3,600 | Disorderly range-floor break on volume; a COT washout finally prints (which would light gate 1) |

Sum = **100%** ✓ · Rally 22% ≤ 50% cap ✓

**Weighted EV recomputation (final step, from the printed cells only):**
```
0.22 × 4,325.00   =   951.50
0.38 × 4,082.50   = 1,551.35
0.27 × 3,857.50   = 1,041.53
0.13 × 3,600.00   =   468.00
                    --------
EV                = 4,012.38
```
Verified: `compute.mjs ev --spot 4049.10 --stated 4012.38` → `rel_diff_pct 0, within_tolerance true, prob_sum_ok true, rally_cap_ok true`.

**Weighted EV = $4,012.38. EV-vs-spot = −0.91%.**
**Realized trailing-2-week price change: +0.91%.** The EV is mildly negative while the realized two-week move was mildly positive — an almost exact mirror. The disclosure exists so that tension is visible: the EV is negative because 40% of the probability mass sits below a modal band whose midpoint is itself slightly under spot, not because the last two weeks are disbelieved.

**Note on the frozen-spot sensitivity:** if canonical spot were taken at the live PAXG median of $4,042.41 instead of the Friday settle, EV-vs-spot would be **−0.74%** rather than −0.91%. The sign does not flip across the entire staleness spread, so the low-confidence handling is not triggered and no second independent unlock condition is required.

**EV-floor consistency check:** EV-vs-spot is negative, but the trigger requires **mechanical score ≥15 AND 3-day sentiment ≤15**. Mechanical is 8 and the sentiment leg is NOT FOUND (scored 2, not ≤15 on any instrument) — **neither limb met, no inconsistency flag.**

**Terminal-vs-extreme:** not compelled — the §5 trend residual is not live. Stated anyway, and it matters more here than for the crypto assets because "range" is the modal cell: **Range being modal is a statement about where I expect price to end the horizon, not a claim that $3,965 is a floor or that a low is in.** If Retest resolves, the path extreme sits in the $3,750–3,965 band. The words "floor" and "base-building" are deliberately absent from the modal row.

---

## 6. Deployment Strategy — GOLD

**Total dry powder: 75% of the gold book. Deployment FROZEN.**
**Dry-powder yield benchmark: 3-month T-bill 3.68%** (Yahoo ^IRX, Jul-31) — ~31 bp/month. Against a position carrying an unrealized loss, this is the measurable cost of *not* having waited.

### Position & Performance (Hard Rule 8) — the PAXG alias

`node tools/position.mjs gold` — returns **covered**, resolving through the ledger alias.

- `requested_asset`: **GOLD** · `ledger_asset`: **PAXG**
- **Band: STALE**, age **1,561 minutes (26.0 h)**, driver `holdings_as_of` (2026-07-31 13:15 UTC)

> ⚠️ **AGE BANNER — STALE (12–72 h).** Descriptive use only. A STALE snapshot **may not satisfy a phase-dependent unlock precondition** and **may not fill a realized ledger column**.

> **ALIAS DISCLOSURE (mandatory).** The ledger cannot hold bullion. **This gold position is held as PAXG** — tokenized gold tracking XAU approximately 1:1, but carrying **issuer and custody counterparty risk that physical gold does not**, and able to trade at a premium or discount to spot. Today's discount is −0.17% ($4,042.41 vs $4,049.10). Canonical gold spot in §2.1 is unaffected by this and comes from Hard Rule 1 sources only.

| Field | Value | Note |
|---|---|---|
| Custody status | **RECONCILED** | live balance agrees with the fill replay; deposits 0, withdrawals 0, off-venue 0 |
| **Live quantity** | **1.32938940 PAXG** | **position of record — a real, material position** |
| Trade-derived quantity | 1.32938940 PAXG | agrees exactly |
| Value at snapshot mark | $5,373.51 | mark **informational only**, never canonical spot (carve-out (a)) |
| `basis.reliable` | **FALSE** | 1 unbacked disposal; `oversold_qty` 0 |
| Average cost / cost basis / unrealized PnL / ROI | **NOT REPORTED** | see below |
| Realized PnL | $915.22 | **upper bound, not a result** |
| Short qty | `null` | **not a margin short** |
| Attribution | **UNTAGGED** | `performance_by_tag_prefix` empty — no `FK-` tag on this account |

**This is the one asset in the batch where the ledger shows a real position, and it is the one where the reporting rules bite hardest.** Quantity is `RECONCILED` and unambiguous: **1.3294 PAXG**. But `basis.reliable` is **FALSE** — one unbacked disposal means a coin was sold whose acquisition the ledger never ingested — so per Hard Rule 8 I quote **no** average cost, **no** cost basis, **no** unrealized PnL and **no** ROI, and the $915.22 realized figure is an **upper bound** rather than a result.

#### Position Reconciliation — the ledger wins

| Figure | Prior report (Jul-25) narrated | Ledger (STALE, Jul-31 13:15 UTC) | Delta |
|---|---|---|---|
| Deployed | 25% of book (1A 10% + 1B 15%) | **1.3294 PAXG, RECONCILED** | Quantity confirmed real; **percentage-of-book unverifiable** |
| Blended cost | **~$4,545** | **not derivable** (`basis.reliable` false) | The $4,545 figure cannot be corroborated |
| MTM | **−10.50%** at $4,067.60 | **NOT REPORTABLE** | An MTM computed from an uncorroborated cost basis is not a result |
| Attribution | 1A + 1B | **UNTAGGED** | Cannot resolve a phase-dependent unlock precondition |

**The −10.50% MTM loss carried in the prior report cannot be restated here, and that is a real loss of information, not a technicality.** The position is genuinely held — 1.3294 PAXG, custody reconciled — but the ledger cannot say what it cost, so it cannot say whether it is up or down. Every prior report in this series has quoted a blended cost of ~$4,545 and an MTM in the −10% region; none of those figures has ever been checked against a fill. Under Hard Rule 8 the ledger wins, and the ledger's answer is "I know you hold 1.3294 PAXG and I do not know what you paid."

**Real dry powder: $14,288.54** account-wide (USDT $9,552.96, USDC $4,735.58). Total portfolio $19,665.31, of which the PAXG position is $5,373.51 at the snapshot mark — **roughly 27% of total portfolio value.** That is the one hard number available here, and it is worth noting it sits close to the narrated "25% deployed," though the two are not the same measurement (portfolio share vs gold-book share).

**Realized performance, account-wide** (91 deals, 13 open): win rate **67.03%**, profit factor **4.94**, expectancy **$59.01**, avg hold 95.9d on winners vs 105.7d on losers. **Per-tag performance unavailable** — I cannot state how gold Phase 1A/1B entries have performed and will not assert it.

### Phases

| Phase | Size | Entry zone | Score line | Gates required | Status |
|---|---|---|---|---|---|
| **1A** | 10% | ~$4,650 | **≥8 ✅ (8) — MET FOR THE FIRST TIME** under the 2026-07-27 cut from ≥10 | ≥3/8, [V]≥2 ❌ (2/8, [V] 2) | **DEPLOYED per prior reports — attribution UNTAGGED.** See note below. |
| **1B** | 15% | ~$4,475 | ≥11 ❌ (8) | ≥5/8, [V]≥3 ❌ | **DEPLOYED per prior reports — attribution UNTAGGED.** |
| **2** | 30% | $3,700–3,950 prospective | ≥15 ❌ (8) | ≥6/8, [V]≥3 ❌ | **FROZEN.** Atomic re-stop to $3,650 required BEFORE any first fill. |
| **3** | 45% | requires capitulation | mech ≥17 ❌ (8) | ≥7/8, [V]≥4 ❌ | DRY POWDER |

**On the Phase 1A score line clearing for the first time.** The 2026-07-27 revision cut 1A from ≥10 to ≥8, and gold prints exactly 8 — so for the first time in this series the score condition for Phase 1A is satisfied. **This changes nothing operationally, for two independent reasons**, and I want both on the record rather than letting the milestone imply more than it does. First, Phase 1A is already deployed. Second, even for a fresh 1A the gate condition fails: 2 of 8 lit against 3 required. A cut unlock line that lands on an already-filled tranche is a bookkeeping event, not an opportunity.

**D2 Analyst Conviction Path — evaluated.** For Phase 1A the score condition is met (8 ≥ 8) and the gate count is short by **exactly one** (2 of 3), with the [V] floor met on lit gates (2 ≥ 2) and the risk-on surcharge off. **The path is technically available — and it is moot: Phase 1A is already deployed.** D2 unlocks a phase; it cannot re-unlock one that is filled. For Phase 1B, D2 is unavailable — 1B is score-blocked (8 < 11), and D2 substitutes for a gate, never for a score. **No D2 unlock taken.**

**Deep-Value Override — evaluated, DOES NOT FIRE.** Mechanical score 8 < 15 — dispositive. (Second independent failure: no extreme-band sentiment reading exists, the leg being NOT FOUND.) Max drawdown from spot to the compound thesis line ($3,850): **−4.92%** — the tightest of the three assets, and stated as standing disclosure; it purchases no loosening.

**Non-mechanical capital cap:** 0% of book via D1/D2/Override. Caps untouched.

### Stops

**No stop parameter changed value this report. Stop Migration Ledger: one line, checkpoint date only.**

| Tier | Level | Note |
|---|---|---|
| **Catastrophic floor / held-position stop** | **$3,800** | Unchanged |
| **Compound thesis stop** | **$3,850 price AND mechanical score <8** | Unchanged. Score line **8** — the pinned-score-asset calibration set 2026-06-17, ratified retroactively in the 2026-07-10 pass. |
| Prospective Phase-2 ladder floor | $3,700 | Named, not active |
| Prospective Phase-2 re-stop | **$3,650** | Post-activation level; atomic re-set required BEFORE the first fill |
| D5 discretionary stops | **none — no analyst-channel tranche exists** | — |

**Compound stop score-axis disclosure.** Mechanical score is **8**, and the condition is score **<8**. **8 is not <8** — the score axis is **UNSATISFIED**, and the compound stop therefore retains **full two-key protection**: it cannot fire on price alone. This is the third consecutive report in which gold's score has sat exactly on the line without crossing it, and it is materially better protection than either crypto asset currently has (both BTC at 11<12 and ETH at 10<12 have their score axes satisfied and are effectively price-gated). **A single point of score decay — say the capitulation leg losing its ETF-outflow condition — would take gold to 7, satisfy the axis, and degrade this stop to price-only.** That is the live risk on the stop and it is one leg away.

**Stop-vs-buy-zone coherence check (catastrophic tier), run in both states as required:**
> **Held state: CATASTROPHIC stop $3,800 strictly below deepest named ladder floor $3,700? → FAIL.**
> Verified: `compute.mjs stop-coherence --catastrophic 3800 --floor 3700` → `pass: false`.
> **Post-activation state: re-stop $3,650 strictly below $3,700? → PASS.**
>
> This FAIL is **expected-for-frozen** and is not a defect. The check tests against the deepest ladder floor named *anywhere* in the report, and the $3,700 Phase-2 floor is a **prospective** zone that is not active — deployment is FROZEN (score 8 ≪ 15, gates 2/8 < 6). The rule's remedy applies exactly as written: the paired post-activation stop level ($3,650) and the atomic activation sequence (**re-set the stop to $3,650 BEFORE the first Phase-2 fill**) are printed, and the boolean is run in both states.
> **No new deployment is authorized in this report, so NO "stop realignment owed" flag is raised.** Were Phase 2 ever to unlock, the re-stop is a precondition of the first fill, not a follow-up to it.

**D6 ratchet compliance:** every parameter held. The prospective $3,650 re-anchor is a **named-zone re-anchor** under D6 exception 1 — the $3,700–3,950 zone has been named in prior reports in this asset's series, so a downward re-anchor onto it is permitted when executed atomically and cited. It is not executed today. Only the checkpoint date moved.

**Stop Migration Ledger:**

| Parameter | Tier | Old | New | Direction | Rationale |
|---|---|---|---|---|---|
| Checkpoint date | checkpoint date | 2026-07-31 | **2026-08-07** | forward roll | The Jul-31 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below $3,850). Rolls to the next weekly COMEX close. |

**Checkpoint prognosis (calendar-locked — and this one required a real calendar check).**
Checkpoint **Friday, 2026-08-07, 13:30 ET COMEX close** (weekly close for the gold futures complex). **Calendar validation:** Aug-07 is a Friday and a full trading session; no US market holiday falls in the week of Aug-03–07 (the next is Labor Day, Mon Sep-07), so **no holiday restatement or abbreviated-session correction applies**, and the checkpoint stands on its nominal date. The date was computed and validated **before** any distance language below.

It **fires iff** ≥2 consecutive weekly closes print below $3,850 **and** the mechanical score is <8. Spot $4,049.10 sits **5.17% above** the $3,850 line, a distance of **4.28× the 5-day ADR of $46.52** (all five sessions full; none excluded). Closes below the line: **0 of the 2 required** — so the checkpoint **cannot** fire on Aug-07 regardless of price, a structural statement about the condition rather than a forecast.

**Tier-1 release between this report and the checkpoint: YES — nonfarm payrolls, Friday 2026-08-07, 08:30 ET, five hours before the COMEX close.** This must be named as part of the falsifier and it is: **a hot payrolls print pushes real yields up and is gold-negative; a soft print pushes real yields down and is gold-positive; the checkpoint's own weekly close therefore prices the release the same day it lands.** Because an unpriced tier-1 release sits between report and checkpoint, **no likelihood adjective is used about price direction into it.** The only claim made is the mechanical one — 0 of 2 required closes means the checkpoint cannot fire — which is traced to a named quantity and is independent of what payrolls does.

**Time stop:** the held position carries a reassessment horizon that has now been extended across multiple frozen reports. The honest statement: **the time stop is unenforceable in the usual sense because the entry dates and cost basis cannot be corroborated** (`basis.reliable` false). Reassessment is therefore anchored to the checkpoint schedule rather than to hold duration until the ledger is fixed.

### Ledger tag

No tranche fills this report. The **1.3294 PAXG already held is `UNTAGGED`** and should be tagged `FK-P1A` / `FK-P1B` per the prior reports' attribution — via `PUT /api/investments/deal-note`, first note line `report=reports/gold_fallen_knives_20260801_1117.md`. Until tagged it is a real position with unknown attribution and **cannot resolve a phase-dependent unlock precondition.** Note also that tagging fixes attribution but **not** the cost basis — that requires reconciling the unbacked disposal against Binance history.

---

## 7. Exit / Trim Framework — GOLD

Hard Rule 2: evaluated in full every report, and this is the asset in the batch with a real position, so it matters most here. **Every score condition reads the MECHANICAL score (8).**

| Trigger | Threshold | Current | Fires? |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | campaign peak **10** (mechanical, gold series) → trim at ≤4 | **8** | **NO** — 2 points off peak |
| Sentiment ≥75 sustained 7d AND weekly RSI >70 | — | sentiment NOT FOUND; RSI 38.98 | NO |
| Drawdown from ATH <10% with vertical 30d return | — | drawdown −27.52%; range-bound, not vertical | NO |
| Mechanical score ≤3 AND price ≥40% above blended cost | — | score 8; blended cost **not derivable** | NO — and the second limb is **untestable** while `basis.reliable` is false |
| Gold-ETF outflows ≥3% AUM trailing month **after a sustained inflow regime** | requires the ≥5-session inflow-flip bar to have been met during the held position's life | outflows are large (June −$8.9bn, AUM −13%) but the **sustained-inflow precondition was NOT met** during this position's life — the position was accumulated into an outflow regime, not after an inflow one | **NO — on the precondition, not the magnitude.** Worth stating precisely: the outflow magnitude would comfortably clear 3%; the trigger does not fire because its regime precondition is unsatisfied. |
| Narrative break | — | none — no regulatory ban, no fraud, no structural change to the gold market | NO |

**Current exit status: NONE. No trim, no exit. Position: 1.3294 PAXG held, UNTAGGED, cost basis unknown.**

The ETF-outflow trim row is the interesting one and it is the mirror of BTC's. On BTC the regime precondition *is* satisfied and only magnitude is missing. On gold the magnitude is there and the precondition is not. Neither fires, but they are failing for opposite reasons and would resolve on opposite news.

---

## 8. Critical Watchlist — GOLD

**Mandatory tier-1 US enumeration, next 5 trading days (Aug-03 → Aug-07), verified against the BLS/CME release schedule:**

| Date (ET) | Time | Event | Tier | Gold impact |
|---|---|---|---|---|
| Mon Aug-03 | — | *no tier-1 release* | — | — |
| Tue Aug-04 | — | *no tier-1 release* | — | — |
| Wed Aug-05 | — | *no tier-1 release* | — | — |
| Thu Aug-06 | — | *no tier-1 release* | — | — |
| **Fri Aug-07** | **08:30** | **Nonfarm Payrolls / Employment Situation** | **TIER 1** | **Lands on the stop-checkpoint day, five hours before the COMEX close.** Hot → real yields up → gold-negative. Soft → real yields down → gold-positive. Named in the checkpoint falsifier per the calendar-lock rule. |

**Beyond the window:** CPI **Wed Aug-12 08:30 ET**, PPI Thu Aug-13, Advance Retail Sales Fri Aug-14.

**This report's horizon contains no unenumerated tier-1 release**, and the one release it does contain is explicitly priced into the checkpoint prognosis.

| Cadence | Event | Impact |
|---|---|---|
| **Fri Aug-07, 15:30 ET** | **CFTC COT report** (Tuesday Aug-04 positioning) | Gate 1 — the washout gate. Needs ≥20–30K contracts or ≥15% WoW decline to go ⚠️ provisional |
| Monthly | WGC global gold ETF flow update | Gate 4 is currently the only lit [V] gate; a reversal would take the score to 7 and **degrade the compound stop to price-only** |
| Rolling | 10y TIPS real yield (2.41%) | The governing macro variable for gold |
| Rolling | DXY (99.80, −1.65% over 5 sessions) | The one live tailwind |
| Range | $3,965 floor / $4,165 top | Breakout in either direction resolves the modal Range scenario |
| **Fri Aug-07, 13:30 ET** | Weekly COMEX close | Stop checkpoint (structurally cannot fire) |

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

Gold is doing the least interesting thing an asset can do and it is doing it deliberately. Price has been contained in $3,965–$4,165 for weeks, spot sits almost exactly mid-range at $4,049, the 200-day is *rising* underneath at $4,479, and the weekly RSI is 38.98 — oversold-ish, but in a market that has stopped going down rather than one that is being liquidated. The COT print says the same thing in positioning terms: 5,163 longs left and 3,323 shorts covered in the same week, netting to a −1,840 contract change that looks like nothing and is actually two-sided deleveraging into a range. Nobody is panicking. Nobody is chasing.

The bear case is real and it is entirely macro. A 2.41% real yield is a genuine hurdle for an asset with no coupon, the FOMC's hawkish contingent is growing, and the ETF complex has been bleeding for months — June alone was −$8.9bn with every region negative and AUM down 13%. That last item is the only bear-capitulation evidence on the board and it is why the score is 8 rather than 7. The bull case is thinner but not empty: the dollar fell 1.65% in five sessions, official-sector demand continues to provide a structural bid, and the 200-day is still sloping up, which is a genuinely different structure from what BTC and ETH are showing.

What I keep coming back to is the asymmetry between what this framework can score and what gold is actually offering. The composite is 8 out of 20 because gold is not cheap on a drawdown basis (−27.5% is not a fallen knife in a metal), has no daily fear instrument to score, and has not had a positioning washout. Every one of those is correct. But the framework is also, structurally, holding a position it can no longer price — the ledger confirms 1.3294 PAXG and cannot say what it cost — while three consecutive reports have quoted a −10.5% MTM off a number that has never been checked against a fill. The most important thing in this report is not the market read. It is that the one asset here with a real position is the one whose P&L has been narrated rather than measured.

On the tape itself: I think the range holds until payrolls resolves it, and I would rather own gold at $3,850 than at $4,049. The Phase-2 zone at $3,700–3,950 is where this framework wants to be adding, and it is 2.5% to 8.6% below spot. That is close enough to be worth having the atomic re-stop sequence pre-loaded and far enough that waiting costs only the 3.68% T-bill carry.

### 9.2 What the rubric structurally cannot see

1. **The 200-day is rising while price is below it.** BTC and ETH both have falling 200-days; gold does not. This is the single most important structural difference in the batch — it is why gold routes to FR stand-down while the other two route to Channel B. The FK gate board has no slope concept at all; gate 6 reads a ±8% distance to the *200-week* and nothing else. *Cuts bullish.*
2. **Two-sided COT deleveraging.** The capitulation leg reads a net-change magnitude. It cannot see that both longs and shorts reduced simultaneously — a market clearing positioning rather than one side capitulating. *Cuts neutral-to-bullish*: it removes fuel from a squeeze in either direction.
3. **The range is explicit and acknowledged by the market.** $3,965–$4,165 is a level structure that participants are trading around. The legs have no concept of a defined range; they see a drawdown percentage. *Cuts neutral, raises the Range weight* — which is exactly what I did in §5 with a +3.
4. **Dollar weakness (−1.65% in 5 sessions).** DXY is not an input to any leg or gate. For gold it is one of the two or three variables that actually matter. *Cuts bullish.*
5. **The drawdown denominator is a 10-year window high, not a verified ATH.** The tool flags this itself. Nothing turns on it today (the leg scores 0 either way), but the valuation leg is measured against a number that has not been verified. *Direction unknown; a measurement debt.*

### 9.3 The D1 term

**D1 = 0.0.**

I considered **+0.5** on factors 1 and 4 — the rising 200-day and the dollar's five-session decline, both sourced, both genuinely outside every leg and gate. I declined it for the same structural reason as BTC, and the arithmetic here is even more stark: gold's next unlock threshold above 8 is **Phase 1B at ≥11**. A +0.5 takes the composite to 8.5, which rounds half-up to 9. Nine unlocks nothing. It does not reach 11, it does not change a gate, it does not authorize a fill, and it does not move a stop. It would, however, start a three-report decay clock and put a discretionary term on the record with no testable consequence attached. The framework licenses discretion on the condition that it can be graded; an adjustment that changes no decision cannot be graded.

I considered **−0.5** on the macro stack — real yield 2.41%, hawkish FOMC, ETF outflows — and declined it more firmly. All three are already scored: the ETF outflows key capitulation-(c) and gate 4 **by name**, and the macro stance keys gate 9. Re-weighting them in the D1 term is the double-counting the rule explicitly prohibits.

There is a third consideration specific to gold, and it argues for restraint rather than for a number. A negative D1 would take the composite to 7.5 → 8 on half-up rounding, so it would not even reach the score-<8 threshold that satisfies the compound stop's score axis. But a *−1.0* would reach 7 — and that would satisfy the axis and **degrade the compound stop to price-only**. I want to be explicit that I noticed this and that it played no part in the decision: **D1 buys entries and never touches exits**, and the compound stop reads the mechanical score (8), not the adjusted one, precisely so that a discretionary term cannot reach a stop in either direction. The governing rule works. I am recording that I checked.

**Falsifier for the zero:** if gold breaks the $4,165 range top with the DXY extending lower and the 200dma still rising, factors 1 and 4 stop being context and become a trend-structure argument — at which point they belong in the probability cells (where I have already partially expressed them) rather than in the score. If instead the $3,965 floor breaks with real yields above 2.5%, the bullish factors are refuted on their own terms.

### 9.4 Discretionary actions taken and declined

| Action | Channel | Disposition | Reason |
|---|---|---|---|
| Score adjustment +0.5 | D1 | **DECLINED** | Would round to 9; the next threshold is 11. Unlocks nothing, changes no gate, authorizes no fill — ungradeable, and it would start a decay clock for nothing |
| Score adjustment −0.5 | D1 | **DECLINED** | Double-counts the macro stack: ETF outflows already key capitulation-(c) and gate 4 by name; the Fed stance already keys gate 9 |
| Phase 1A conviction unlock | D2 | **AVAILABLE BUT MOOT** | Score 8 ≥ 8, gate count short by exactly one (2 of 3), [V] floor met, surcharge off — the path is technically open, but Phase 1A is already deployed and D2 cannot re-unlock a filled tranche |
| Phase 1B conviction unlock | D2 | **UNAVAILABLE** | 1B is score-blocked (8 < 11); D2 substitutes for a gate, never for a score |
| Probability cells set by hand | D4 | **TAKEN** | Rally +2 / Range +3 / Retest −3 / Bear −2 vs baseline; all within 10pp; reasons tabled in §5 |
| Deep-Value Override | mechanical, not discretionary | **DOES NOT ARM** | Mechanical score 8 < 15 |
| Reinstating the low-vol valuation band-set | not a discretionary channel | **DECLINED** | Withdrawn 2026-07-18; reinstatement requires documented realized-vol evidence (gold ≤½ BTC's 30d realized vol, sourced and timestamped) plus a Change Log entry. Not produced, not needed, not reinstated |

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 | Term set to **0.0**; +0.5 and −0.5 both considered and declined | n/a | n/a | Break of $4,165 with DXY extending lower and a rising 200dma would justify positive; break of $3,965 with real yield >2.5% would justify negative | **live** | n/a |
| 2026-08-01 | D2 | Phase 1A path **AVAILABLE but MOOT** (tranche already deployed); Phase 1B path **UNAVAILABLE** (score-blocked) | n/a | n/a | Becomes live only if 1A is ever exited and re-approached with gates at 2/8 | **retired (moot)** | n/a |
| 2026-08-01 | D4 | Cells set by hand: 22/38/27/13 vs baseline 20/35/30/15 | n/a | n/a | A weekly close outside $3,965–$4,165 retires the Range-modal read | **live** | n/a |

No prior open discretionary entries — the Analyst Discretion Layer shipped 2026-07-27 and this is gold's first report under it. **The layer is N=1 on this asset.**

### 9.6 What would change my mind

**Bullish flip, dated:** a weekly COMEX close above **$4,165** (the range top) by **Fri 2026-08-28**, with the DXY below 99 and the 200dma still rising. That is a genuine range resolution with the two supporting macro conditions attached, and it would convert the frozen posture from "wait for $3,700–3,950" to "the base is in and the Phase-2 zone will not trade."

**Bearish flip, dated:** a weekly COMEX close below **$3,965** by **Fri 2026-08-21** with the 10y TIPS real yield above 2.50%. That activates the §5 trend residual, puts the $3,700–3,950 Phase-2 zone in play as a live ladder, and makes the atomic re-stop sequence to $3,650 an immediate operational requirement rather than a contingency.

**What would change my mind fastest, and it is not a price:** a COT print showing a ≥20–30K contract or ≥15% WoW net-long decline. That single event would take gate 1 to ⚠️ provisional and, on a confirming second weekly print, to ✅ — moving the board to 3/8 and clearing Phase 1A's gate condition outright. **The next COT lands Friday Aug-07 at 15:30 ET**, covering Tuesday Aug-04 positioning. It is the highest-information scheduled event on gold's board and it is one week away.

**What would not change my mind:** one heavy ETF outflow month. June was −$8.9bn and gate 4 is already lit off it; more of the same adds no information the score has not already taken.

---

## 10. Bull vs Bear Scorecard — GOLD

**Bull (✅) — 5**
1. 200-day MA **rising** (+0.52%/20 sessions) — a basing structure, not a downtrend; the only asset in this batch with a rising long MA
2. DXY −1.65% over 5 sessions — the one live macro tailwind
3. Official-sector / central-bank demand stable, providing a structural bid
4. Weekly RSI 38.98 — mildly oversold without a liquidation
5. COT deleveraging is two-sided (5,163 longs out, 3,323 shorts covered) — squeeze fuel removed in both directions

**Bear (❌) — 7**
1. 10y TIPS real yield 2.41% — a hard hurdle for a zero-coupon asset
2. Hawkish post-FOMC read with a growing hawkish contingent
3. WGC June ETF outflows −$8.9bn, all regions, AUM −13%, holdings −74t; July MTD −76.44t
4. Investment demand fell to 262t including 45t of ETF outflows
5. Valuation leg 0 — a −27.52% drawdown is not cheap on the standard band-set; −30% needs $3,910 and gate 3 needs $2,793
6. Price 9.61% below the 200-day and range-bound with no direction established
7. Gate board 2/8 with gates 3 and 6 both "none-in-regime"

**Net: 5–7, bear by 2.** The scorecard is **not** within 1 of balanced, so that collar limb is **not** met. But **|EV-vs-spot| = 0.91% < 2%** is met, and the **mechanical score of 8 falls inside the 6–10 band** is met. Two of three limbs. **Verdict-Confidence Collar ACTIVE — no directional regime resolution may be claimed in §12.**

---

## 11. Change Log vs 2026-07-25

| Factor | Previous (Jul-25) | Current (Aug-01) | Direction |
|---|---|---|---|
| Canonical spot | $4,067.60 | $4,049.10 (Fri settle, frozen) | −0.45% |
| Mechanical score | 8 | **8** | flat (3rd consecutive) |
| — sentiment leg | 2 (NOT FOUND) | 2 (NOT FOUND, re-verified) | flat |
| — momentum leg | 2 (RSI 38.84 / 39.49) | 2 (RSI 38.98) | flat |
| — valuation leg | 0 (drawdown −27.18%) | 0 (drawdown −27.52%) | flat |
| — capitulation leg | 1 (ETF outflows) | 1 (ETF outflows) | flat |
| — holder leg | 3 | 3 (carried, not freshly verified) | flat |
| D1 discretionary | n/a (pre-layer) | **0.0**, logged; ±0.5 declined | new |
| Gates | 2/8 (4, 8) | 2/8 (4, 8) | flat |
| **Phase 1A score line** | ≥10, not met at 8 | **≥8, MET for the first time** (2026-07-27 cut) | milestone — **operationally moot**, 1A already deployed and gates still 2/8 < 3 |
| COT net long WoW | −1.49% | **−1.00%** (−1,840 contracts; futures+options −18,835) | still far below the washout bar |
| Weekly RSI | 38.84 | 38.98 | flat |
| 200-day slope | not stated | **+0.52% RISING** | newly computed — routes FR to stand-down |
| 30d corr vs SPX | not stated | **0.240, computed** | newly sourced |
| FR companion | n/a | **2/20 (2/8 attainable), STAND DOWN — no channel** | cap-bound, vacuity disclosure applied |
| EV-vs-spot | not restated here | −0.91% | — |
| **Position (ledger)** | narrated 25% @ ~$4,545, **MTM −10.50%** | **1.3294 PAXG RECONCILED; basis NOT derivable — MTM NOT REPORTABLE** | **the −10.50% figure is withdrawn, not restated** |
| Stop checkpoint | 2026-07-31 | **2026-08-07** (forward roll) | migration logged |

---

## 12. Strategic Verdict — GOLD

**Adjusted score 8/20 · Mechanical 8/20 · D1 0.0 · Weighted EV $4,012.38 · EV-vs-spot −0.91% · realized 2-week +0.91% · sentiment NOT FOUND (leg 2) · Gates 2/8 ([V] 2) · Stance: HOLD 1.3294 PAXG; deployment FROZEN; 75% dry.**

Gold has printed the same score for three consecutive reports and the tape supports the stability. Price is mid-range in a $3,965–$4,165 band that participants are explicitly trading around, the weekly RSI is 38.98, the 200-day is *rising* beneath price at $4,479, and the week's COT showed 5,163 longs leaving alongside 3,323 shorts covering — two-sided deleveraging that netted to a −1.00% change and looks, correctly, like nothing. Against that, the macro stack is uniformly hostile: a 2.41% real yield, a hawkish FOMC contingent, and an ETF complex that shed $8.9bn in June with every region negative. The one bull macro item is a dollar down 1.65% in five sessions. This is a market waiting for a catalyst, and the catalyst has a date on it — payrolls, Friday Aug-07, 08:30 ET, five hours before the weekly COMEX close that is also this position's stop checkpoint.

The framework's answer to all of that is 8 out of 20 and 2 gates of 8, and I think both are right. Gold is not a fallen knife at a 27.5% drawdown; a −30% print needs $3,910 and the gate-3 level needs $2,793. The one place the framework and I are aligned with genuine conviction is the Phase-2 zone: **$3,700–3,950 is where this framework wants to add**, it sits 2.5% to 8.6% below spot, and the operational requirement — re-set the catastrophic stop from $3,800 to $3,650 *before* the first fill, atomically — is pre-loaded and documented rather than something to work out under pressure. One framework line did move this week: the Phase 1A unlock was cut from ≥10 to ≥8 and gold now clears it for the first time. It changes nothing, because 1A is already deployed and the gate count would block a fresh one anyway, and I would rather say that plainly than let a milestone imply an opportunity.

What actually needs attention is not on the chart. This is the one asset in this batch where the ledger shows a real, material position — **1.3294 PAXG, custody RECONCILED, zero withdrawals, roughly 27% of total portfolio value** — and it is the one where the reporting rules bite hardest, because `basis.reliable` is FALSE. One unbacked disposal means a coin was sold whose acquisition the ledger never ingested, so the framework knows exactly what is held and cannot say what it cost. The −10.50% MTM that this series has quoted for three reports running, off a blended cost of ~$4,545, **is withdrawn rather than restated** — not because it is wrong, but because it has never been checked against a fill and Hard Rule 8 does not permit reporting a P&L derived from a basis the ledger cannot corroborate. A framework that plans a 30% Phase 2 against a position whose cost it cannot state is not managing risk; it is narrating. And there is a second-order consequence worth naming: the gold compound stop currently retains **full two-key protection** because 8 is not <8 — better protection than either crypto asset has — but that protection is exactly one point of score decay away from evaporating, and the point most at risk is the ETF-outflow condition that is currently the only thing keeping the capitulation leg at 1.

### Action items

1. **Refresh the position snapshot to FRESH (≤12 h).** 26 h old on `holdings_as_of`; requires a re-link (`POST /link`), not merely a re-export.
2. **Reconcile the PAXG cost basis.** `basis.reliable` is false on **1 unbacked disposal**. This is the smallest and most tractable basis defect in the account — ETH has 24 unbacked disposals and 8.5 units of overshoot; gold has one. **Fix this one first.** Reconciling it restores cost basis, unrealized PnL and the MTM figure for the only asset here with a real position.
3. **Tag the 1.3294 PAXG** as `FK-P1A` / `FK-P1B` per prior-report attribution, via `PUT /api/investments/deal-note` with `report=` provenance. Note that tagging fixes attribution but **not** cost basis — item 2 is separate and independent.
4. **Hold. Deploy nothing.** Score 8 ≪ 15 and gates 2/8 < 6 both block Phase 2. The Phase 1A score line clearing at ≥8 is operationally moot.
5. **Pre-load the atomic re-stop sequence.** If Phase 2 ever unlocks, the catastrophic stop moves $3,800 → $3,650 **before** the first fill, not after. This is a D6 exception-1 named-zone re-anchor and is permitted only when executed atomically and cited.
6. **Watch the Aug-07 COT print (15:30 ET, Tue Aug-04 positioning) as the highest-information scheduled event.** A ≥20–30K contract or ≥15% WoW net-long decline takes gate 1 to ⚠️ provisional and, on a confirming second print, to ✅ — moving the board to 3/8 and clearing Phase 1A's gate condition outright.
7. **Watch gate 4 for reversal risk, not just for confirmation.** It is the only lit [V] gate. If WGC flows turn, the score falls to 7, the compound stop's score axis becomes satisfied, and the stop degrades to price-only at $3,850 — a real erosion that would arrive as a *bullish* flow datum. That inversion is worth having on the radar before it happens.
8. **Note the payrolls/checkpoint collision:** NFP lands Fri Aug-07 08:30 ET, the checkpoint closes 13:30 ET the same day. The checkpoint cannot fire (0 of 2 required closes), but the release is named in its falsifier and no directional likelihood language is used across it.

> ### The Pattern
>
> **IF** gold closes a week above $4,165 with DXY below 99 and the 200dma still rising → **THEN** the range resolves upward, the Phase-2 zone at $3,700–3,950 likely never trades, and the frozen 75% becomes a permanent underweight rather than patient dry powder. Falsifier: no weekly close above $4,165 by Fri 2026-08-28.
>
> **IF** gold closes a week below $3,965 with the 10y TIPS real yield above 2.50% → **THEN** the §5 trend residual activates, the $3,700–3,950 zone becomes a live ladder, and the atomic re-stop to $3,650 moves from contingency to precondition. Falsifier: a weekly close back above $4,100 by Fri 2026-08-21.
>
> **IF** the Aug-07 COT prints a ≥20–30K or ≥15% WoW net-long decline → **THEN** gate 1 goes ⚠️ provisional, a confirming second weekly print takes it ✅, the board reaches 3/8 and Phase 1A's gate condition clears outright — the first gate gold has added since gate 4 lit. That is the single event on this board most capable of changing the deployment answer.
>
> Gold's problem is not that it fell. It is that it fell politely — 27% off the high with a rising 200-day, no washout, no flush, and no fear instrument to measure the fear that never arrived. This framework buys panic, and gold has not supplied any. Seventy-five percent dry at a 3.68% carry, with the Phase-2 ladder mapped and the re-stop sequence pre-loaded, is what patience looks like when the discount is real but the capitulation is not.

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-GOLD-20260801-1117 | STAND_DOWN | non_crypto_derivative |
| 1B | FK-P1B-GOLD-20260801-1117 | STAND_DOWN | non_crypto_derivative |
| 2 | FK-P2-GOLD-20260801-1117 | STAND_DOWN | non_crypto_derivative |
| 3 | FK-P3-GOLD-20260801-1117 | STAND_DOWN | non_crypto_derivative |

Registry schema: report-phase-registry/1; version: 1; origin: gold_fallen_knives_20260801_1117.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "GOLD",
  "date": "2026-08-01",
  "spot": { "value": 4049.10, "source": "Yahoo GC=F COMEX front-month Friday 2026-07-31 settle, carried forward as a FROZEN print — COMEX is closed Saturday. FEWER THAN 3 SYNCHRONIZED LIVE QUOTES OBTAINABLE and this is disclosed: MGC=F is the same complex/session (not independent corroboration) and a quoted cash XAU/USD of $4,035.73 is a different session convention. Live 24/7 cross-check from the PAXG token: median $4,042.41 across Binance $4,045.84 / Kraken $4,042.43 / Coinbase $4,042.40 / CoinGecko $4,042.30 (all 2026-08-01 15:27 UTC), synchronized spread 0.088%. Gold-vs-PAXG gap 0.17% — staleness-driven and time-ordered across a weekend, NOT genuine simultaneous venue disagreement, so no low-confidence demotion; frozen quotes are labeled and aged, not silently dropped. EV sign does not flip across the spread (EV-vs-spot -0.91% at the settle, -0.74% at the PAXG median)." },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 0, "capitulation": 1, "holder": 3 },
    "discretionary": 0,
    "mechanical": 8,
    "raw": 8,
    "adjusted": 8,
    "rounding": "half-up"
  },
  "gates": { "active": 8, "na": [5], "passed": [4, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 22, "low": 4200, "high": 4450 },
      { "name": "Range", "p": 38, "low": 3965, "high": 4200 },
      { "name": "Retest", "p": 27, "low": 3750, "high": 3965 },
      { "name": "Bear", "p": 13, "low": 3450, "high": 3750 }
    ],
    "stated_ev": 4012.38,
    "vs_spot_pct": -0.91
  },
  "deployment": {
    "deployed_pct": 25,
    "dry_pct": 75,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~4650 zone per prior reports. NO entry_price is asserted: basis.reliable=false (1 unbacked disposal) so the ledger cannot corroborate a cost, and encoding a numeric fill would assert a basis this report explicitly declines to state. SCORE LINE MET FOR THE FIRST TIME (8>=8 under the 2026-07-27 cut from >=10) but operationally MOOT — the tranche is already deployed and a fresh 1A would fail the gate count (2/8<3). Attribution UNTAGGED.", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "~4475 zone per prior reports; deployed. NO entry_price asserted, same basis reason. Score-blocked for any further authorization (8<11). Attribution UNTAGGED.", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "3700-3950 prospective, FROZEN (score 8<<15, gates 2/8<6); atomic re-stop 3800 -> 3650 required BEFORE the first fill", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 8<17)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 3800,
    "held_position_stop": 3800,
    "prospective_p2_floor": 3700,
    "prospective_p2_restop": 3650,
    "compound": { "price": 3850, "score_line": 8 },
    "note": "NO stop parameter changed value. SCORE AXIS UNSATISFIED: mechanical score is 8 and the condition is score <8 — 8 is NOT <8 — so the compound stop retains FULL two-key protection and cannot fire on price alone. This is materially better protection than either crypto asset in this batch (BTC 11<12 and ETH 10<12 both have their axes SATISFIED and are effectively price-gated). LIVE RISK: a single point of score decay takes gold to 7, satisfies the axis, and degrades this stop to price-only — and the point most at risk is capitulation-(c), the ETF-outflow condition that is currently the only thing holding that leg at 1, which would erode the stop on a BULLISH flow datum. Coherence run in BOTH states as required: held state catastrophic $3,800 vs deepest NAMED prospective ladder floor $3,700 = FAIL (compute.mjs stop-coherence pass:false) — expected-for-frozen, the $3,700 zone is prospective and deployment is FROZEN; post-activation re-stop $3,650 vs $3,700 = PASS. No new deployment is authorized, so NO 'stop realignment owed' flag. The $3,650 re-anchor is a D6 exception-1 named-zone re-anchor (the 3700-3950 zone is named in prior reports in this series), permitted only when executed atomically before the first fill and cited; NOT executed today. No D5 stops — zero analyst-channel tranches. Max drawdown spot-to-compound-line -4.92%, disclosed; purchases no loosening. D6 ratchet: compliant, only the checkpoint date moved.",
    "migration": [
      { "parameter": "checkpoint date", "tier": "checkpoint date", "old": "2026-07-31", "new": "2026-08-07", "direction": "forward roll", "rationale": "Jul-31 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below 3850); rolls to the next weekly COMEX close" }
    ],
    "checkpoint": {
      "date": "2026-08-07",
      "line": 3850,
      "condition": ">=2 consecutive weekly closes <3850 AND mechanical score <8",
      "closes_below": 0,
      "adr": 46.52,
      "adr_note": "all 5 sessions (Jul-27..Jul-31) are FULL COMEX sessions; no holiday abbreviation in the window, none excluded, lookback not extended",
      "dist_x_adr": 4.28,
      "calendar_validation": "Fri 2026-08-07 is a full COMEX trading session, 13:30 ET close; no US market holiday falls in the week of Aug-03..07 (next is Labor Day Mon 2026-09-07); no restatement applied; date computed and validated BEFORE any distance language",
      "side": "spot 5.17% above line; structurally cannot fire (0 of 2 required closes)",
      "tier1_before_checkpoint": "YES — Nonfarm Payrolls Fri 2026-08-07 08:30 ET, five hours BEFORE the 13:30 ET COMEX close. Named in the falsifier per the calendar-lock rule: hot print -> real yields up -> gold-negative; soft print -> real yields down -> gold-positive. Because an unpriced tier-1 release sits between report and checkpoint, NO likelihood adjective is used about price direction; the only claim made is the mechanical one (0 of 2 required closes), traced to a named quantity and independent of the release."
    }
  },
  "companion_fr": {
    "score": 2,
    "score_attainable": 8,
    "channel": "none — STAND DOWN",
    "routing": { "pct_below_1y_ath": 27.52, "gt_20pct_below_1y_ath": true, "ma200_falling": false, "ma200_slope20_pct": 0.52, "ma200d": 4479.49, "price_below_ma200_pct": -9.61 },
    "routing_note": "Both Channel B regime conditions must hold; the 200dma is RISING (+0.52%/20 sessions) so Channel B's regime test FAILS, and >20% below the 1y high forecloses Channel A. Result: no channel — the genuine phase-of-cycle mismatch the original cap was written for (a basing tape that is neither a distribution top nor a confirmed downtrend). This is the structural difference from BTC and ETH, both of which have FALLING 200dmas and route to Channel B.",
    "legs_channel_a": { "euphoria": 0, "momentum": 0, "valuation": 1, "distribution": 1, "vulnerability": 0 },
    "cap_vacuity_disclosure": "Score 2 / 8 attainable (2/20 nominal). Interpretation bands >=9 unreachable — no phase reachable at any score, the Channel A 1A line of 11 sitting 3 points above the cap; Hard-Rule-5 both->=12 check structurally unfalsifiable in this regime.",
    "cross_validation": "structurally consistent (cap-bound; both->=12 unfalsifiable by construction)",
    "conditions_to_change_routing": "either gold rallies to within 20% of its $5,586.20 1-year high (above $4,468.96), restoring Channel A eligibility, OR the 200dma rolls over from +0.52% to falling, routing to Channel B. Neither is close: the 200dma at $4,479.49 is rising and price is 9.61% beneath it.",
    "standalone_report_owed": false
  },
  "position": {
    "source": "tools/position.mjs gold",
    "requested_asset": "GOLD",
    "ledger_asset": "PAXG",
    "alias_disclosed": true,
    "alias_note": "The ledger cannot hold bullion, so gold resolves onto PAXG — tokenized gold tracking XAU ~1:1 but carrying issuer and custody counterparty risk that physical gold does not, and able to trade at a premium or discount. Today's discount is -0.17% ($4,042.41 PAXG median vs $4,049.10 canonical gold). Canonical gold SPOT is unaffected and comes from Hard Rule 1 sources only (carve-out (a)).",
    "band": "STALE",
    "age_min": 1561,
    "age_driver": "holdings_as_of",
    "custody_status": "RECONCILED",
    "qty": "1.32938940",
    "qty_note": "REAL, MATERIAL POSITION — the only one in this batch. Custody RECONCILED, deposits 0, withdrawals 0, off-venue 0; trade-derived quantity agrees exactly.",
    "value_at_snapshot_mark_usd": 5373.51,
    "share_of_portfolio_pct": 27.3,
    "basis_reliable": false,
    "unbacked_disposal_count": 1,
    "oversold_qty": "0",
    "short_qty": null,
    "avg_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd_upper_bound": 915.22,
    "attribution": "UNTAGGED",
    "dry_powder_stable_usd": 14288.54,
    "portfolio_total_usd": 19665.31,
    "note": "STALE band — descriptive use only; may NOT satisfy a phase-dependent unlock precondition and may NOT fill a realized ledger column. Position Reconciliation: prior reports narrate 25% deployed at a blended ~$4,545 with MTM -10.50%. Quantity is CONFIRMED REAL at 1.3294 PAXG, but basis.reliable=false on 1 unbacked disposal, so NO average cost, cost basis, unrealized PnL, ROI or MTM is reported and the -10.50% figure is WITHDRAWN rather than restated — it has never been checked against a fill and Hard Rule 8 does not permit reporting a P&L derived from an uncorroborable basis. Realized $915.22 is an upper bound. This is the smallest and most tractable basis defect in the account (1 unbacked disposal vs ETH's 24 and 8.5 units of overshoot) and should be fixed first."
  },
  "trend_residual": { "active_downtrend": false, "basis": "price is 9.61% below the 200dma so the MA half is satisfied, but NOT making lower lows — the 200dma is RISING (+0.52%/20 sessions), price is range-bound in $3,965-$4,165 with no direction established, and no fresh lower low has printed. This is a basing structure, and it is the cleanest 'not a downtrend' read of the three assets in this batch.", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned" },
  "correlation": { "value_30d_vs_spx": 0.240, "window": "2026-06-18 to 2026-07-31", "method": "Pearson on daily log returns, overlapping sessions, Yahoo GC=F vs ^GSPC closes, computed 2026-08-01", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.240 < 0.80)" },
  "discretion": {
    "d1_considered_declined": [
      { "value": 0.5, "reason": "would round half-up to 9; the next threshold is Phase 1B at >=11. Unlocks nothing, changes no gate, authorizes no fill, moves no stop — ungradeable, and would start a three-report decay clock for no testable consequence" },
      { "value": -0.5, "reason": "double-counts the macro stack: ETF outflows already key capitulation-(c) and gate 4 BY NAME, and the Fed stance already keys gate 9" }
    ],
    "d1_governing_rule_check": "Noted and recorded: a -1.0 term would take the composite to 7, which WOULD satisfy the compound stop's score-<8 axis and degrade the stop to price-only. It played no part in the decision, because the compound stop reads the MECHANICAL score (8), not the adjusted one — D1 buys entries and never touches exits. The governing rule works; recording that it was checked.",
    "d2_available": true,
    "d2_taken": false,
    "d2_detail": "Phase 1A: score 8>=8, gate count short by EXACTLY ONE (2 of 3), [V] floor met (2>=2), surcharge off — path technically AVAILABLE but MOOT, since 1A is already deployed and D2 cannot re-unlock a filled tranche. Phase 1B: UNAVAILABLE — score-blocked at 8<11, and D2 substitutes for a gate, never for a score.",
    "d4_taken": true,
    "low_vol_bandset": "WITHDRAWN 2026-07-18, NOT reinstated. Reinstatement would require documenting gold's realized 30-day vol at <=1/2 of BTC's contemporaneous 30d realized vol, sourced and timestamped, plus a Change Log entry naming the anchoring historical bear distribution. Not produced, not needed (the leg scores 0 on either band-set at a -27.52% drawdown), not reinstated."
  },
  "key_inputs": {
    "sentiment": "NOT FOUND — no reliably sourceable daily gold fear instrument; primary fallback scores the leg 2 and flags it. COT is PROHIBITED as the sentiment LEG input (it already keys capitulation-(b) and gate 1; one input may not key two legs). DSI/HGNSI/GVZ are disclosed regime context only, never scored. Debt-clock branch discharged: a daily gold fear instrument cannot be obtained from free sources, so the leg will continue to score 2 until one is.",
    "weekly_rsi14": 38.98,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_incl_live_week": 41.52,
    "sma_200w": 2843.68,
    "pct_vs_sma200w": 42.39,
    "ma200d": 4479.49,
    "ma200d_slope20_pct": 0.52,
    "ma200d_rising": true,
    "drawdown_pct": -27.52,
    "drawdown_denominator_note": "measured against a 10-YEAR WINDOW high of $5,586.20 (2026-01-26), NOT a verified all-time high — the fetch tool flags this itself. Open item carried from the prior report; nothing turns on it today since the valuation leg scores 0 either way and gate 3 needs $2,793.10.",
    "valuation_bandset": "standard drawdown-from-ATH (low-vol adaptation withdrawn 2026-07-18, not reinstated); -27.52% -> band <30% -> 0. A -30% score-1 print needs $3,910.34; gate 3's >=50% needs $2,793.10",
    "trading_range": "3965-4165, spot mid-range, no direction established",
    "cot_reporting_date": "2026-07-28",
    "cot_net_long_contracts": 182070,
    "cot_wow_change_contracts": -1840,
    "cot_wow_change_pct": -1.00,
    "cot_long_leg_change": -5163,
    "cot_short_leg_change": -3323,
    "cot_futures_plus_options_change": -18835,
    "cot_washout_verdict": "NO — the bar is a WoW non-commercial net-long decline of >=20-30K contracts OR >=15% of the net; the print is -1,840 (-1.00%), an order of magnitude short on both tests, and even the combined -18,835 falls below the 20K floor and well under 15%. Source characterizes it as routine profit-taking, not capitulation. Read the short leg, not the net: 5,163 longs left AND 3,323 shorts covered — two-sided deleveraging that nearly offset, not static positioning. Prior report printed -1.49%, also below the bar; two consecutive sub-threshold prints, no washout regime claimed or backdated.",
    "wgc_june_flows_usd_bn": -8.9,
    "wgc_june_note": "ALL REGIONS negative; total AUM -13% to US$526bn; holdings -74t to 4,047t. July MTD -76.44 tonnes. Investment demand fell to 262t including 45t of ETF outflows. This is the gate-4 condition and the only lit [V] gate besides holder behaviour.",
    "adr5": 46.52,
    "realized_2w_change_pct": 0.91,
    "tbill_3m_pct": 3.68,
    "real_yield_10y_tips_pct": 2.41,
    "dxy": 99.80,
    "dxy_5session_change_pct": -1.65,
    "vix": 15.99,
    "brent": 90.12,
    "tier1_next_5_sessions": ["NFP Fri 2026-08-07 08:30 ET — lands on the checkpoint day, 5h before the 13:30 ET COMEX close"],
    "tier1_beyond_window": ["CPI Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14"],
    "scheduled_high_information_event": "CFTC COT report Fri 2026-08-07 15:30 ET (Tue Aug-04 positioning) — the single event most capable of changing the deployment answer: a >=20-30K or >=15% WoW net-long decline takes gate 1 to provisional and, on a confirming second weekly print, to lit, moving the board to 3/8 and clearing Phase 1A's gate condition outright",
    "stale_input_debt": ["sentiment leg — NOT FOUND, carried at the fallback 2; 'why it cannot be obtained' branch discharged", "holder leg — carried from the prior report, not freshly verified this cycle", "drawdown denominator — 10y window high, not a verified ATH"]
  },
  "collar": { "band_triggered": true, "reasons": ["|EV-vs-spot| 0.91% < 2%", "mechanical score 8 is inside the 6-10 band"], "scorecard_limb_met": false, "scorecard": "5 bull / 7 bear — bear by 2, NOT within 1 of balanced, so that limb is not met; the other two are", "effect": "no directional regime resolution claimed" },
  "verdict": "HOLD 1.3294 PAXG; deployment FROZEN; 75% dry at a 3.68% T-bill carry. Mechanical 8/20, D1 0.0, adjusted 8/20 — THIRD consecutive report at 8, no leg moved. NAMING: the request was PAXG; the analytical asset is GOLD (XAU) with canonical spot from Hard Rule 1 sources, and the POSITION is held as PAXG via the ledger alias — disclosed, never silently collapsed. PAXG trades at a -0.17% discount to the gold settle today ($4,042.41 vs $4,049.10); the counterparty risk does not disappear because the tracking is tight. SPOT HANDLING: COMEX is closed Saturday, fewer than 3 synchronized live gold quotes are obtainable, and this is stated rather than papered over — the Friday settle is carried as a labeled FROZEN print, cross-checked against a genuinely synchronized 4-venue PAXG panel (spread 0.088%); the 0.17% gap is staleness-driven and time-ordered across a weekend, not venue disagreement, so no low-confidence demotion and the EV sign does not flip across it. FRAMEWORK LINE THAT MOVED: the 2026-07-27 cut of Phase 1A from >=10 to >=8 means gold clears a 1A score line for the first time in the series — and it is OPERATIONALLY MOOT for two independent reasons: 1A is already deployed, and a fresh 1A would fail the gate count anyway (2/8 < 3). Said plainly rather than letting a milestone imply an opportunity. POSITIONING: COT shows 5,163 longs OUT and 3,323 shorts COVERED, netting -1,840 contracts (-1.00%) — two-sided deleveraging an order of magnitude short of the >=20-30K / >=15% washout bar. Read the short leg, not the net. FR COMPANION routes differently from BTC and ETH: gold's 200dma is RISING (+0.52%/20 sessions), so Channel B's regime test FAILS while >20%-off-1y-high forecloses Channel A — result STAND DOWN, no channel, Channel A scored 2/20 (2/8 attainable) with the full cap-regime vacuity disclosure; cross-validation reads 'structurally consistent (cap-bound; both->=12 unfalsifiable by construction)', never a bare check. STOP: uniquely in this batch the score axis is UNSATISFIED (8 is not <8), so the compound stop retains FULL two-key protection — but it sits ONE point of score decay from degrading to price-only, and the point most at risk is the ETF-outflow condition, meaning the stop would erode on a BULLISH flow datum. Coherence run in both states: held FAIL (expected-for-frozen, $3,800 vs a prospective $3,700), post-activation PASS ($3,650); no realignment owed since no deployment is authorized. POSITION OF RECORD (Hard Rule 8, STALE at 26h): 1.3294 PAXG, custody RECONCILED, zero withdrawals, ~27% of total portfolio value — the only real position in this batch. basis.reliable=FALSE on 1 unbacked disposal, so the -10.50% MTM this series has quoted for three reports off a ~$4,545 blended cost is WITHDRAWN rather than restated: the framework knows exactly what is held and cannot say what it cost. This is the smallest basis defect in the account (1 unbacked disposal vs ETH's 24) and should be fixed first. Collar ACTIVE (|EV-vs-spot| 0.91% < 2%; mechanical 8 in the 6-10 band): no directional regime resolution claimed.",
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "gold_fallen_knives_20260801_1117.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "GOLD",
      "report_date": "2026-08-01",
      "report_local_time": "11:17",
      "report_zone": "America/New_York",
      "instrument_class": "non_crypto_derivative",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-GOLD-20260801-1117",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-GOLD-20260801-1117",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-GOLD-20260801-1117",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-GOLD-20260801-1117",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260801_1117.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-01",
          "report_local_time": "11:17"
        }
      ]
    },
    "instrument_class": "non_crypto_derivative",
    "report_file": "gold_fallen_knives_20260801_1117.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "GOLD",
    "report_date": "2026-08-01",
    "report_local_time": "11:17",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-GOLD-20260801-1117",
      "FK-P1B-GOLD-20260801-1117",
      "FK-P2-GOLD-20260801-1117",
      "FK-P3-GOLD-20260801-1117"
    ],
    "status": "REGISTERED"
  }
}
```
