# 🔪 FALLEN KNIVES ANALYTICS — GOLD — 2026-08-05

## WEDNESDAY MID-MORNING — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Wednesday, 2026-08-05, 10:08 EDT
### Asset: GOLD (XAU) · position held as PAXG via the ledger alias | Prior Score: 8 mechanical / 8 adjusted (2026-08-01) | Current Score: 8 mechanical / **7 adjusted**

---

## 1. What this report decides

Gold broke out. Up **+4.03%** in a single session to **$4,260.60**, a 152-point range on volume at the **99.4th percentile of two years**, resolving a five-week $3,965–$4,165 range to the upside and closing the gap to its rising 200dma from −9.61% to −4.86% in three sessions.

For a *fear-accumulation* framework, that is not good news. It is the position working and the next tranche receding. Deployment stays **FROZEN**, and the adjusted score falls below the Phase 1A line for the first time in this series.

**But the headline of this report is a data-integrity catch, not a market call.** Yahoo is currently emitting an extra weekly bar for the live session, which causes `tools/fetch.mjs` to silently include the **in-progress** week in its "completed closes" set. On gold that artifact returns a weekly RSI of **41.03** instead of the correct **38.98** — moving the momentum leg from 2 to 1, the mechanical score from 8 to 7, and thereby **satisfying the compound stop's score axis and degrading a live stop to price-only**. A stop weakened by a data-plumbing quirk rather than by evidence. It was caught, corrected, and the stop retains full two-key protection. Section 2.3 shows the arithmetic.

---

## 2. Verified Live Data Points — GOLD

### 2.1 Canonical spot reconciliation — and a futures-vs-cash basis that has to be disclosed

**Fewer than three synchronized live quotes are obtainable for COMEX gold, and this is stated rather than papered over.** `tools/fetch.mjs` returns `n_synchronized: 0` and `low_confidence: true` with the reason *"no synchronized quotes available"* — both available futures sources are frozen bar closes on the same complex:

| Source | Symbol | Price (USD) | Status |
|---|---|---|---|
| Yahoo | **GC=F** (COMEX front month) | **4,260.60** | frozen bar close, 2026-08-05 live session |
| Yahoo | MGC=F (E-micro) | 4,258.60 | frozen bar close — **same complex, not independent corroboration** |

**Live 24/7 cross-check panel** (tokenized gold and cash XAU, all 2026-08-05 14:08 UTC):

| Source | Instrument | Price (USD) |
|---|---|---|
| Binance | PAXG/USDT | 4,219.92 |
| Kraken | PAXG/USD | 4,217.19 |
| Coinbase | PAXG/USD | 4,214.77 |
| CoinGecko | pax-gold | 4,207.82 |
| **PAXG median (4 synchronized venues)** | | **4,215.98** — spread **0.287%** |
| Coinbase | **XAU/USD cash** | **4,218.59** — an independent cash-gold quote, within **0.06%** of the PAXG median |

**Canonical spot = $4,260.60 (GC=F).** Held deliberately, for series continuity: the 10-year window high ($5,586.20), the drawdown that feeds the valuation leg, the 200-week SMA, and the 200dma are all computed on the **GC=F** series. Switching the canonical to a cash quote mid-series would break the drawdown's internal consistency for a difference that changes no band.

**The futures-vs-cash gap, and why it is a basis rather than a disagreement.** GC=F sits **+1.03% above** the cash/PAXG cluster (~$4,216–4,219). On 2026-07-31 the same comparison was **+0.17%**. That widening is not venue disagreement; it is **COMEX carry on a deferred contract** — at a 3.73% risk-free rate, three-to-four months of carry is ~1.1–1.2%, and the front month has rolled off the near-expiry August contract since the last report. The instruments differ; a contango basis is structural and explainable.

**EV computed at both ends, as the rule requires:**
- vs GC=F $4,260.60 → EV-vs-spot **−0.67%**
- vs the cash/PAXG cluster $4,215.98 → EV-vs-spot **+0.38%**

**The EV sign flips across the gap, and |median EV-vs-spot| (0.67%) is smaller than the gap (1.03%).** The letter of the rule ties the low-confidence demotion to *genuine simultaneous disagreement among synchronized quotes*, which a carry basis between two different instruments is not — so the demotion is not strictly mandated. **I am applying it anyway, as a conservative deviation, and flagging it as such:** the gold read this report is marked **LOW-CONFIDENCE / CORROBORATIVE-ONLY**, and any action would require a second independent unlock condition. This costs nothing — deployment is frozen and the score is below the 1A line — and it is the right direction to err when the spot input itself is on frozen bar closes.

**Today's move is corroborated independently of the futures series.** Kraken PAXG opened at $4,065.00 and printed $4,217.19 — **+3.74%** — against GC=F's +4.03% from $4,095.40. Two different instruments, two different venues, the same event. This is a real breakout, not a roll artifact.

### 2.2 Sentiment — NOT FOUND (fallback), debt-clock branch discharged

| Metric | Value | Status |
|---|---|---|
| Daily gold fear instrument | **NOT FOUND** | Scored at the primary fallback of **2** and flagged |

No reliably sourceable **daily-resolution** gold fear instrument exists in free sources. DSI and HGNSI are paywalled or non-daily; GVZ is a volatility index, not a fear gauge. **COT is PROHIBITED as the sentiment LEG input** — it already keys capitulation-(b) and gate 1, and one input may not key two legs. GVZ/DSI/HGNSI may be cited as disclosed regime context only, never as the scored input.

**Stale-input debt clock:** the "why it cannot be obtained" branch is **discharged** — the leg will continue to score 2 until a free daily gold fear instrument becomes available. This is a structural limitation, not an outstanding task.

### 2.3 Momentum — the toolchain artifact that would have degraded a live stop

| Input | Value |
|---|---|
| Weekly Wilder RSI-14 | **38.98** |
| Weekly-close source | Yahoo GC=F, 5y 1wk candles |
| Weekly boundary | Yahoo week-start timestamps, UTC |
| Period | 14 |
| Completed closes used | **261**, through the week ending Sunday 2026-08-02 |
| Confidence | ok (≥30 closes) |

**This is the report's most important paragraph, and it is about plumbing.**

Yahoo is currently emitting an **extra weekly bar** for the live session. The GC=F weekly series ends: … 2026-07-20 ($4,067.60), 2026-07-27 ($4,049.10), **2026-08-03 ($4,095.40)**, **2026-08-05 ($4,269.10)**. `tools/fetch.mjs` drops only the **final** bar, so its "completed closes" set today includes the **in-progress week beginning 2026-08-03** and returns 262 closes.

The arithmetic, computed both ways:

| Basis | Closes | Weekly RSI-14 | Momentum band |
|---|---|---|---|
| Through **2026-07-27** (last genuinely completed week, ending Sunday 2026-08-02) | **261** | **38.98** | **2** (≤40) |
| Through 2026-08-03 (**includes the in-progress week** — what the tool returns today) | 262 | **41.03** | **1** (>40, ≤45) |
| All bars including the live 2026-08-05 stub | 263 | 48.08 | 0 |

The 261-close figure of **38.98** reproduces this series' 2026-08-01 print **exactly**, as do the corresponding BTC (38.84) and ETH (41.96) figures in the companion reports. That is the confirmation that 261 is the correct basis and 262 is the artifact.

**What the artifact would have cost, stated precisely:**
> Momentum leg 2 → 1 · mechanical score 8 → **7** · the compound stop's score condition is **score <8**, and **7 IS <8** → the score axis flips from **unsatisfied to satisfied** → **the compound stop degrades from full two-key protection to price-only at $3,850.**

The 2026-08-01 report warned about this exact failure mode — *"a single point of score decay takes gold to 7, satisfies the axis, and degrades this stop to price-only"* — and predicted the point would come from capitulation-(c) on a bullish flow datum. It nearly came instead from a Yahoo bar-count quirk. A stop is not permitted to weaken because a data source changed its pagination.

**Corrected value used: 38.98. Momentum leg = 2. Mechanical score = 8. The compound stop retains full two-key protection.** `compute.mjs band fk-momentum 38.98` → **2**.

*(Cross-asset note: the same artifact is present on BTC and ETH and is harmless there — BTC 38.84 vs 39.73 both band to 2; ETH 41.96 vs 41.58 both band to 1, and there the artifact's direction is even reversed. Gold is the only asset in the batch where it bites, which is exactly why per-asset verification beats trusting a shared tool output.)*

### 2.4 Valuation

| Metric | Value | Source |
|---|---|---|
| Drawdown from the 10-year window high | **−23.73%** (high $5,586.20, 2026-01-26) | Yahoo GC=F 10y weekly highs |
| Band-set used | **standard drawdown-from-ATH** | low-vol adaptation **withdrawn 2026-07-18, not reinstated** |
| Band | <30% → **0** | `compute.mjs band fk-drawdown 23.73` → 0 |
| Price for a score of 1 (≥30%) | **$3,910.34** | derived |
| Price for gate 3 (≥50%) | **$2,793.10** | derived |

**Open item carried forward:** the denominator is a **10-year window high**, not a verified all-time high — the fetch tool flags this itself. Nothing turns on it today: the leg scores 0 on either reading, and gate 3 needs $2,793.10 regardless.

**Low-vol band-set: WITHDRAWN 2026-07-18, NOT reinstated.** Reinstatement would require documenting gold's realized 30-day vol at ≤½ of BTC's contemporaneous 30-day realized vol, sourced and timestamped, plus a Change Log entry naming the anchoring historical bear distribution. The ratio today is **25.11% / 29.35% = 0.86** — nowhere near the ≤0.50 bar, so it would fail the test even if attempted. Not produced, not needed (the leg scores 0 on either band-set at −23.73%), not reinstated.

**The move away, quantified:** the drawdown was −27.52% on 2026-08-01 and is −23.73% today. The band is saturated at 0 in both cases — **the valuation leg is structurally incapable of expressing what happened this week**, which is directly relevant to §9's discretionary term.

### 2.5 Positioning (COT — no new print since the prior report)

| Metric | Value | Source |
|---|---|---|
| COT reporting date | **2026-07-28** — still the latest published | CFTC |
| Non-commercial net long | 182,070 contracts | CFTC legacy futures-only |
| WoW change | **−1,840 (−1.00%)** | CFTC |
| Long leg / short leg | −5,163 longs out / −3,323 shorts covered | CFTC |
| Futures + options combined | −18,835 | CFTC |
| Managed-money net (disaggregated, same date) | −5,036 | CFTC disaggregated |
| **Washout verdict** | **NO** | — |

**Metric-history continuity applied:** the CFTC publishes Tuesday positioning on the following Friday, so the **2026-08-04** positioning report releases **Friday 2026-08-07 at 15:30 ET** — after this report. The 2026-07-28 print is unchanged from the prior report and is *not* re-dated or re-characterized.

The bar is a WoW non-commercial net-long decline of **≥20–30K contracts or ≥15% of the net**. The print is −1,840 (−1.00%), an order of magnitude short on both tests; even the combined −18,835 falls below the 20K floor and well under 15%. **Read the short leg, not the net:** 5,163 longs left *and* 3,323 shorts covered — two-sided deleveraging that nearly offset, not static positioning. Two consecutive sub-threshold prints; no washout regime is claimed or backdated.

### 2.6 Physical gold-ETF flows (gate 4 — the only [V] gate lit besides holder behaviour)

| Window | Flow | Source |
|---|---|---|
| July 2026 global, as of 2026-07-24 | **−76.44 tonnes** monthly net outflow; holdings 4,044.83t | World Gold Council |
| July 2026 US-listed | **~−$5.3 billion** in redemptions | WGC / SSGA July Gold Monitor |
| June 2026 global | −$8.9bn, **all regions negative**; AUM −13% to US$526bn; holdings −74t to 4,047t | World Gold Council |
| H1 2026 context | Holdings +18t over the half; Asia's strongest first-half inflows on record | World Gold Council |

Gate 4's bar is a trailing-month global net outflow that is **multi-region and among the worst of the trailing twelve months, GLD-corroborated**. July at −76.44t global with US-listed redemptions of ~$5.3bn clears it. **Gate 4 stays lit — and it is the single most fragile input in this report.** A +4% price move with record volume is exactly the condition under which physical ETF flows reverse, and a reversal would take capitulation-(c) to zero, the leg from 1 to 0, and the mechanical score from 8 to 7 — degrading the compound stop **on a bullish flow datum**. That is the erosion the 2026-08-01 report predicted, and it is still the live one.

**Note:** a formal WGC monthly report covering full-July flows was not locatable as of this writing; the −76.44t figure is dated **as of 2026-07-24** and is carried with that date attached rather than rounded up to "July."

### 2.7 Macro

| Asset | Level | Δ 5 sessions | Source |
|---|---|---|---|
| **Brent crude** | **$79.48** | **−12.41%** | Yahoo BZ=F, 2026-08-05 |
| DXY | **99.73** | **−1.06%** | Yahoo DX-Y.NYB, 2026-08-05 |
| 10y TIPS real yield | 2.43% | −0.01pp / 5 prints | FRED DFII10, 2026-08-03 |
| US 10y nominal | 4.64% | +0.32% | Yahoo ^TNX, 2026-08-05 |
| VIX | 16.95 | −17.96% | Yahoo ^VIX, 2026-08-05 |
| S&P 500 | 7,785.30 (record) | +6.41% | Yahoo ^GSPC, 2026-08-05 |
| 3m T-bill (dry-powder benchmark) | **3.73%** | +1.97% | Yahoo ^IRX, 2026-08-05 (FRED DGS3MO cross-check 3.91%) |
| Fed funds target | 3.50–3.75%, held 9–3 on 2026-07-29, three hawkish dissents | — | CME / CNBC |
| September FOMC (2026-09-16) hike odds | **~59–63%** | CME FedWatch via Reuters/Bloomberg coverage, 2026-08-05 | |

**Context Panel** (disclosed context only — never a scored leg, gate, threshold, size, stop or cap):

| Metric | Value | Percentile vs 2y |
|---|---|---|
| Realized vol 30d | 25.11% | 66.14 |
| Realized vol 10d / 90d | 28.34% / 24.90% | — |
| Drawdown vs 2y high | 19.89% | — |
| Distance to 200dma | **−4.86%** (was −9.61% on 2026-08-01) | — |
| Weekly RSI-14 | — | **8.67** |
| **Session volume (contracts)** | **126,062** | **99.40** |
| Daily RSI-14 | 60.68 | — |
| Net liquidity (FRED, weekly) | $5.83T | as of 2026-07-29 |
| HY OAS | 2.78% | FRED, 2026-08-03 |
| NFCI | −0.529 | FRED, 2026-07-31 |

Two readings carry the story. **Session volume at the 99.4th percentile of two years** — this was a participation event, not a drift. And **weekly RSI at the 8.67th percentile** — despite a +4% day, the higher timeframe is still deeply washed out, which is the strongest remaining argument that this is a base resolving rather than a top forming.

### 2.8 Correlation regime

| Metric | Value |
|---|---|
| 30d Pearson correlation vs SPX | **0.353** |
| Window | 2026-06-24 → 2026-08-05 (30 overlapping daily log-return pairs) |
| Method | Pearson on daily log returns, Yahoo GC=F vs ^GSPC closes, computed 2026-08-05 |
| Regime label | **mild** |
| Risk-on surcharge (>0.7) | **OFF** |
| Phase-2 corr condition (<0.8) | **PASS** on a computed number |

Correlation rose from 0.240 to 0.353 — still comfortably mild, but a reminder that today's gold rally and today's equity record share a driver (the oil collapse and its effect on inflation expectations).

### 2.9 Companion Flying Rocket score (Hard Rule 5, computed — not estimated)

`compute.mjs fr-companion`:

- **Routing: NO CHANNEL — STAND DOWN.** 23.73% below the 1-year high forecloses **Channel A** (>20% off), while the 200dma is **RISING** (+0.35%/20 sessions), so **Channel B's** regime test fails. This is the genuine phase-of-cycle mismatch the original cap was written for: a basing tape that is neither a distribution top nor a confirmed downtrend.
- **Channel A legs (scored for disclosure):** euphoria 0 · momentum 0 · valuation 0 (MVRV-Z not applicable to gold — input missing) · distribution 1 · vulnerability 0.
- **FR composite: 1 / 20**, with **score_floor 1 / score_ceiling 6** on the missing input. **Confidence: partial.** `hard_rule_5_dischargeable: true` — the floor/ceiling band straddles neither 9 nor 12.
- Interpretation bands ≥9 are unreachable at the ceiling; no phase is reachable at any score, with Channel A's 1A line of 11 sitting five points above the ceiling.

**Cross-validation: structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).** Never a bare ✅.

**Routing tripwire — materially closer than at the last report.** Channel A eligibility restores if gold closes within 20% of the $5,586.20 one-year high, i.e. **above $4,468.96**. That is now **+4.89% away**; on 2026-08-01 it was +10.4%. Alternatively, Channel B opens if the 200dma rolls over from its current **+0.35%** — a slope that has flattened from +0.52% in four sessions. **Both routing conditions have moved closer.** If gold adds another ~5%, the FR phase-of-cycle cap stops binding, Hard Rule 5's both-≥12 check becomes genuinely falsifiable again, and a full standalone Flying Rocket report becomes **mandatory** under vacuity trigger (iv).

**Standalone FR report: not owed on gold today** (composite 1, ceiling 6, no trigger fired). *Separately outstanding across this batch:* the **ETH** standalone FR report, owed since 2026-08-01 and re-fired today, remains undischarged.

---

## 3. Critical Developments

- **Gold broke a five-week range to the upside on record volume.** +4.03% on 2026-08-05 to $4,260.60 (GC=F), a 152-point range — **2.62× the 5-day ADR of $58.08** — with volume at the 99.4th percentile of two years. Corroborated independently by tokenized gold: Kraken PAXG opened $4,065.00 and printed $4,217.19, +3.74%. Spot gold rose ~1.3% on the session per market reporting, with COMEX futures up ~1.05% intraday before the close extended.
- **The driver is identifiable and macro.** Reporting attributes the move to a weaker dollar (DXY −1.06% over five sessions) and to the oil collapse trimming inflation expectations and with them the perceived odds of further Fed tightening. Brent fell 12.41% to $79.48 on progress toward a Strait of Hormuz reopening agreement. September hike odds sit at ~59–63%, described in gold coverage as having been "toned down." Sources: investinglive 2026-08-05, CNBC Select 2026-08-05, ARY News, Reuters/Bloomberg coverage.
- **The Strait of Hormuz is still closed.** ~2 transits on 2026-08-02 against ~73/day normal; convoys under naval escort. US and regional officials were "zeroing in" on a deal 2026-08-04; an earlier MoU was followed by Iran re-closing the gateway. The oil move trades the *deal*, not the reopening — which cuts both ways for gold. Sources: Washington Times 2026-08-03/04, Al Jazeera.
- **Physical gold-ETF outflows persisted through late July** — global −76.44t as of 2026-07-24, US-listed ~−$5.3bn for the month, following June's −$8.9bn all-regions-negative print. This is the single lit fear gate, and the flow data predates the breakout.
- **No new COT print.** The 2026-08-04 positioning report releases Friday 2026-08-07 at 15:30 ET — on the checkpoint day, five hours after NFP.

---

## 4. Fallen Knives Composite Score — GOLD

| Category | Max | Input (sourced) | Band logic | Score |
|---|---|---|---|---|
| **Sentiment Extreme** | 5 | **NOT FOUND** — no free daily gold fear instrument; COT prohibited as the sentiment leg input | primary fallback | **2** |
| **Momentum Exhaustion** | 4 | Weekly Wilder RSI-14 **38.98** (261 completed closes through the week ending 2026-08-02) — **corrected from the tool's artifact-inflated 41.03, see §2.3** | ≤40 band | **2** |
| **Valuation** | 5 | Drawdown **−23.73%** from the 10y window high $5,586.20 (standard band-set; low-vol adaptation withdrawn, ratio 0.86 would fail its own test) | <30% band | **0** |
| **Capitulation Evidence** | 3 | (a) no downside vol/volume flush ❌ *(today's 99.4th-percentile volume was on a +4% UP day — see below)* · (b) COT washout −1,840 / −1.00% vs a ≥20–30K or ≥15% bar ❌ · (c) gold-ETF outflow spike: July −76.44t global, US-listed −$5.3bn ✅ | 1 of 3 | **1** |
| **Holder Behavior** | 3 | (a) official-sector demand stable/rising ✅ · (b) concentration stable ✅ — **carried, not freshly verified this cycle** (stale-input debt) | both | **3** |
| **Leg sum** | | | | **8** |

**Capitulation-(a), and why record volume did not credit it.** Session volume hit the **99.4th percentile of two years**. The criterion for gold is a *vol/volume flush*, and the parent leg is titled **Capitulation Evidence** — it measures forced selling. Today's volume arrived on a **+4.03% up day** that closed near its high, resolving a range upward. Crediting an upside participation event as capitulation evidence would be a mis-measurement of the leg's subject, and it would raise the score on the very day the accumulation case weakened. **Scored NO, with the volume percentile disclosed as context** and carried into §9 as a discretionary factor instead, which is where an upside structural event belongs.

- **Leg sum: 8**
- **Mechanical score: 8** — `round(Σ legs)`, no discretionary term. *Read by every protective rule, and load-bearing here: the compound stop's condition is score <8, and **8 is NOT <8**.*
- **D1 discretionary term: −1.0** (see §9)
- **Raw composite: 7.0**
- **[V]-gate surcharge: none** (corr 0.353 < 0.7)
- **Adjusted score: 7** — `round(7.0)`, gold convention half-**up**. *Read by deployment/unlock rules only.*

**Fourth consecutive report at mechanical 8.** No leg moved — but the artifact in §2.3 means "no leg moved" was a finding this time rather than an observation.

### Confirmation Gates — 2 of 8 (gate 5 N/A, denominator reduced)

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | **Gold substitution:** CONFIRMED COT positioning washout (replaces the daily-sentiment streak) | ❌ | Needs a WoW non-commercial net-long decline ≥20–30K contracts or ≥15% of the net, then held or extended on the following weekly print. Latest is −1,840 (−1.00%) — an order of magnitude short. **The next print lands Friday 2026-08-07 15:30 ET** and is the single most reachable relight on this board — though a +4% price week makes a long *flush* less likely, not more. |
| 2 | [V] | Weekly RSI <30 | ❌ | 38.98, and today's move pushes the live-week reading toward 48. Needs a sustained multi-week decline — roughly a break back below the $3,965 range floor and a hold there. **Reachable, and materially further away than on 2026-08-01.** |
| 3 | [V] | Valuation cheap (≥50% drawdown from ATH) | ❌ | **"none-in-regime"** — requires **$2,793.10**, a further −34.4% from spot. Structurally unreachable without a large, slow-moving repricing. *(Note: this is a regime judgment, not "none-by-construction" — that tag applies only under the low-vol adaptation, which is withdrawn, so gate 3 is a live-but-distant gate here.)* |
| 4 | [V] | **Gold substitution:** sustained physical gold-ETF outflows (WGC multi-region, among the worst of trailing 12 months, GLD-corroborated) | ✅ | Lit — July global −76.44t (as of 2026-07-24), US-listed ~−$5.3bn; June −$8.9bn all regions, AUM −13%. **The most fragile input in this report:** a +4% breakout on record volume is precisely the condition under which physical flows reverse. |
| 5 | [T] | Hash Ribbon | **N/A** | Structurally inapplicable — denominator reduced to 8, never scored ❌. |
| 6 | [T] | Price within ±8% of the 200-week MA | ❌ | 200-week SMA **$2,855.66**; spot is **+49.20% above** it. **"none-in-regime"** — gold fails this gate from *expensiveness*, the mirror image of ETH's failure from *cheapness*, and today's rally moved it further away. |
| 7 | [V] | Capitulation volume spike | ❌ | Today's volume hit the 99.4th percentile — **on an up day**. The gate measures a downside flush. Needs a disorderly break of the $3,965 range floor on outsized volume. **Reachable, and the breakout just made it less likely near-term.** |
| 8 | [V] | LTH accumulation / holder concentration stabilizing | ✅ | Lit — official-sector demand stable, concentration stable. **Carried, not freshly verified this cycle** (stale-input debt, second consecutive report). |
| 9 | [T] | Macro catalyst neutral-to-positive | ⚠️ | **Upgraded from ❌ on fresh evidence, and it does not count.** Pro: DXY −1.06%, Brent −12.41% trimming inflation expectations and tightening odds, VIX −17.96% — the exact mix gold rallied on. Con: real 10y yield *rose* to 2.43%, ~59–63% September hike still priced, the Strait still closed. Relights on a dovish inflection that pulls real yields down. |

**Passed: 4, 8 → 2 of 8. [V] count: 2** (gates 4, 8). Identical composition to the prior three reports.

`compute.mjs thresholds 8` → 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4).

Gates 3 and 6 carry "none-in-regime" tags; gates 1, 2, 7 and 9 have concrete reachable paths. Gate 9 moved ❌ → ⚠️ on fresh evidence and counts the same (not at all). Neither tag is cited anywhere below to lower a threshold, reduce the denominator, or credit a gate — the default conclusion stands that dark gates are correctly dark.

**The direction of travel is the point.** Three of the four dark reachable gates (2, 7, and arguably 1) moved **further away** this week, because all three require weakness and gold delivered strength. Gate 4, the one lit fear gate, is the one most likely to flip *off*. A fear-accumulation board gets worse when the asset rallies. That is the system working, not failing.

---

## 5. Probability Matrix — Score-Anchored, Analyst-Set (D4)

**Trend residual — stated as a boolean regardless of how the cells were set:**
> **Active downtrend (below a major MA AND making lower lows): NO.**
> Price is 4.86% below the 200dma, so the MA half is technically satisfied — but the **200dma is RISING** (+0.35%/20 sessions), price has just resolved a five-week range **upward** on record volume, the 25-session low is $3,962.50 and holding, and no fresh lower low has printed. This is the cleanest "not a downtrend" read of the three assets in this batch, and it strengthened this week.
> **Consequence:** no bearish residual applied. The Deep-Value Override's **quarter-size throttle is OFF** (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire regardless: mechanical 8 < 15.

**D4 taken.** Adjusted score 7 → baseline anchor row 6–10 (Rally 20 / Range 35 / Retest 30 / Bear 15). Cells set from the read: Rally **+6**, Range **+3**, Retest **−6**, Bear **−3**. All inside the ±10 percentage-point band; no cell needs a >10pp reason line. Targets re-anchored around the new spot, since the prior report's bands were built around $4,049.

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | 26% | $4,400 – $4,650 | $4,525 | Breakout follows through to the **rising 200dma at $4,478.46**; soft NFP 2026-08-07 → real yields fall; Hormuz deal signed and the dollar stays soft |
| **Range** | 38% | $4,150 – $4,400 | $4,275 | The breakout holds its retest — the old $4,165 range ceiling becomes support; no macro resolution before CPI |
| **Retest** | 24% | $3,950 – $4,150 | $4,050 | Failed breakout — price falls back into the broken $3,965–4,165 range; hot NFP lifts real yields above 2.55% |
| **Bear** | 12% | $3,700 – $3,950 | $3,825 | Hormuz deal signed *and* the Fed re-hardens — the reflation hedge and the rate hedge both unwind; the $3,962.50 low gives way |

**Sum: 26 + 38 + 24 + 12 = 100% ✅**

**Weighted EV recomputation from the printed cells (final step, cells → EV, never the reverse):**
> 0.26 × 4,525 = 1,176.50
> 0.38 × 4,275 = 1,624.50
> 0.24 × 4,050 = 972.00
> 0.12 × 3,825 = 459.00
> **Σ = 4,232.00**

**Stated EV = $4,232.00. EV-vs-spot = −0.67%** against the GC=F canonical, **+0.38%** against the cash/PAXG cluster — the sign flip disclosed in §2.1, which is why the read is marked corroborative-only. (`compute.mjs ev` returns 4232 / −0.67%; stated matches recomputed exactly.)

**Realized trailing 2-week price change: +2.81%** (vs 2026-07-22 close $4,146.90, Yahoo GC=F). A mildly negative EV printed alongside a *positive* two-week move — disclosed as the rule requires, and it is the honest expression of the read: the move happened, and the framework does not expect it to compound from here at the same rate.

**Rally cap check:** 26% ≤ 50% ✅, not the modal cell.

**EV-floor consistency check:** requires EV negative **AND mechanical score ≥15 AND** 3-day sentiment ≤15. Mechanical **8**; sentiment NOT FOUND. **Not triggered.**

**Terminal-vs-extreme reconciliation:** not required — the §5 trend residual is not live. The modal cell is Range, but the residual is absent, so the rule's trigger condition (residual live **AND** modal Range/base-building) is not met. Stated so the omission is deliberate rather than overlooked.

---

## 6. Deployment Strategy — GOLD

**Splits: 10 / 15 / 30 / 45.**

### 6.1 Position & Performance (Hard Rule 8) — and the PAXG alias, disclosed

`node tools/position.mjs gold` → **exit 1, band EXPIRED.**

| Field | Value |
|---|---|
| File | `~/.trading-claude/exchange/position-snapshot.json` |
| Band | **EXPIRED** |
| Age | **5,654 minutes (94.2 hours)** — past the 4,320-minute (72h) expiry |
| Age driver | `holdings_as_of` (5,654 min); `generated_at` 5,652 min |
| Instruction returned | *"Cold start per Hard Rule 4, stated explicitly. The ledger is too old to be the position of record."* |

**Stated explicitly: no fresh ledger was available.** Under Hard Rule 4 the default is all dry powder **unless a prior report or the user confirms a position** — and prior reports do. The 2026-08-01 report read a then-STALE snapshot showing a **real, material** holding, so the position is carried forward as narrated-and-previously-confirmed, **not** re-read as flat.

**Naming, disclosed and never silently collapsed.** The request is GOLD; the analytical asset is **GOLD (XAU)** with canonical spot from Hard Rule 1 sources; the **position is held as PAXG** via the ledger alias, because the ledger cannot hold bullion. PAXG is tokenized gold tracking XAU ~1:1 but carrying **issuer and custody counterparty risk that physical gold does not**, and it can trade at a premium or discount. Today it trades at a **−1.03% discount to the GC=F futures canonical** and within **0.06%** of a cash XAU quote — i.e. it is tracking *cash* gold tightly, and the gap to the canonical is the futures carry basis of §2.1, not a tracking failure. The counterparty risk does not disappear because the tracking is good.

**Last confirmed position (2026-08-01 snapshot, read while STALE — carried forward, NOT current):**

| Field | Value |
|---|---|
| Quantity | **1.32938940 PAXG** |
| Custody status | **RECONCILED** — deposits 0, withdrawals 0, off-venue 0; trade-derived quantity agreed exactly |
| Share of portfolio then | ~27.3% — the only real position in the account |
| `basis_reliable` | **false** — 1 unbacked disposal |
| Attribution | **UNTAGGED** |
| Realized PnL | $915.22 — an **upper bound**, not a result |

Applied strictly:
- The quantity **1.3294 PAXG** is reported as **last-confirmed, not current**. At 94.2 hours the ledger cannot satisfy any phase-dependent unlock precondition and cannot fill a realized ledger column.
- **No average cost, cost basis, unrealized PnL, ROI or MTM is quoted.** `basis.reliable` was false; the "−10.50% MTM off a ~$4,545 blended cost" that this series quoted for three reports was **withdrawn** on 2026-08-01 and is **not** restated here — and note that today's rally would have made it look considerably better, which is exactly the temptation the rule exists to refuse. **The framework knows roughly what is held and cannot say what it cost.**
- **Real dry powder is unknown.** Last readable: $14,288.54–$14,408.87 in stablecoins against a ~$19.7–19.8K portfolio — a **shared pool** with the BTC and ETH reports at this timestamp. No tranche is sized against it.
- **Dry-powder yield benchmark:** 3.73% (^IRX, 2026-08-05).
- **This remains the smallest and most tractable basis defect in the account** — 1 unbacked disposal, against BTC's 5 and ETH's 24. It should be fixed first.

### 6.2 Phase status

| Phase | Size | Zone | Score condition | Gate condition | Status |
|---|---|---|---|---|---|
| **1A** | 10% | ~$4,650 per prior reports | **adjusted 7 < 8 ❌ — line lost** | 2/8 < 3 ❌ | **DEPLOYED (last-confirmed); no fresh authorization** |
| **1B** | 15% | ~$4,475 per prior reports | adjusted 7 < 11 ❌ | 2/8 < 5 ❌ | **DEPLOYED (last-confirmed); score-blocked for more** |
| **2** | 30% | **$3,700 – $3,950** prospective | adjusted 7 ≪ 15 ❌ | 2/8 < 6 ❌ | **FROZEN** — atomic re-stop $3,800 → $3,650 required **before** any first fill |
| **3** | 45% | requires a capitulation candle | mechanical 8 < 17 ❌ | 2/8 < 7 ❌ | **DRY** |

**Deployed (last-confirmed): 25%. Dry: 75%.** No entry_price is asserted for either filled tranche — `basis.reliable` was false, so encoding a numeric fill would assert a cost this report explicitly declines to state.

**The framework line that moved, and it moved backwards.** On 2026-08-01, the 2026-07-27 cut of Phase 1A from ≥10 to ≥8 meant gold cleared a 1A score line for the first time in the series — a milestone that report correctly called *operationally moot*. **This report loses it again**, at adjusted 7. Said plainly: the milestone was never an opportunity, and its loss is not a setback. Phase 1A was and remains gate-blocked at 2/8 < 3, and it is already deployed.

**Consequence that is not cosmetic: the D2 conviction path is now UNAVAILABLE on Phase 1A.** On 2026-08-01, D2 was technically available (score 8 ≥ 8, gates short by exactly one, [V] floor met) and moot only because 1A was already filled. Today it fails the **score condition** outright — D2 substitutes for a gate, never for a score. A channel closed. See §9.4.

**Phase 2's zone is deliberately NOT re-anchored upward.** Gold rallied; the deeper buy zones simply became more distant, and $3,700–3,950 now sits **7.3–13.2% below spot**. Moving the zone up would raise the deepest-named-floor input to the coherence check and make that test **easier** — a loosening by the back door. The zone stays where prior reports named it.

**Deep-Value Override: DOES NOT FIRE.** Mechanical 8 < 15 — dispositive. Sentiment is NOT FOUND so the extreme-band condition cannot be satisfied either, and the price condition fails outright (price is 4% *above* the prior tranche's neighbourhood, not 8% below it). No near-fire, no veto or throttle reached.

**Ledger tags:** existing holdings are **UNTAGGED**. Any future fill in the $3,700–3,950 zone would carry `FK-P2`.

**Non-mechanical capital: 0% of book.** No D1 cross, no D2 path, no Override firing has ever executed on gold.

### 6.3 Stops

**No stop parameter changed value this report.**

| Tier | Level | Condition |
|---|---|---|
| **Catastrophic floor / held-position stop** | **$3,800** | Below the held ladder |
| **Compound thesis stop** | **$3,850** price line + **mechanical** score line **<8** | ≥2 consecutive weekly COMEX closes below $3,850 **AND** mechanical score <8 |
| **Prospective Phase 2 floor** | $3,700 | Frozen, not activated |
| **Prospective post-activation re-stop** | $3,650 | D6 exception-1 named-zone re-anchor; **not executed today** |
| **Time stop / checkpoint** | **2026-08-07** COMEX close | See below |
| **D5 discretionary stops** | **none** | Zero analyst-channel tranches |

**SCORE AXIS UNSATISFIED — and this is the report's second finding.** Mechanical score is **8**; the condition is score **<8**; **8 is NOT <8**. The compound stop therefore **retains full two-key protection and cannot fire on price alone** — materially better protection than either crypto asset in this batch, where BTC (11<12) and ETH (11<12) both have their axes satisfied and are effectively price-gated.

**Two separate mechanisms could have taken that protection away today, and neither did, for two different reasons:**
1. **The toolchain artifact** (§2.3) would have scored momentum 1 instead of 2, taking mechanical to 7 and satisfying the axis. It was caught and corrected; the score is 8 on the correct 261-close basis.
2. **The D1 term of −1.0** takes the *adjusted* score to 7 — which **is** <8. It has **no effect on this stop**, because the compound stop reads the **MECHANICAL** score per the 2026-07-27 governing rule. Checked explicitly, not assumed.

The 2026-08-01 report recorded that it had verified this same governing-rule interaction hypothetically. Today it is not hypothetical: a live −1.0 exists, it would satisfy the axis if the stop read the adjusted number, and it does not. **The governing rule earned its keep.**

**The live risk is unchanged and still points at gate 4.** A single point of *mechanical* decay takes gold to 7, satisfies the axis, and degrades this stop to price-only — and the point most at risk is **capitulation-(c)**, the ETF-outflow condition that is the only thing holding that leg at 1. A +4% breakout on record volume is exactly the setup for physical flows to reverse. **The stop would therefore erode on a bullish flow datum**, which remains the least intuitive and most important risk on this board.

**Stop-vs-buy-zone coherence check (mandatory, run in BOTH states as required):**
> Deepest buy-zone floor named anywhere in this report: **$3,700** (prospective Phase 2).
> **Held state:** CATASTROPHIC stop **$3,800** strictly below **$3,700**? → **FAIL** (`compute.mjs stop-coherence --catastrophic 3800 --floor 3700` → `pass: false`).
> **Post-activation state:** re-stop **$3,650** strictly below **$3,700**? → **PASS** (`pass: true`).
>
> The held-state FAIL is **expected-for-frozen**: the $3,700–3,950 zone is prospective and deployment is FROZEN. **No new deployment is authorized, so NO "stop realignment owed" flag is raised.** The $3,650 re-anchor is a **D6 exception-1 named-zone re-anchor** — the $3,700–3,950 zone is named in prior reports in this series — permitted only when executed **atomically before the first fill** and cited. Not executed today.

**Max drawdown spot-to-compound-line: −9.64%** ($4,260.60 → $3,850), widened from −4.92% on 2026-08-01 purely because price rose. Disclosed; purchases no loosening under D6.

**D6 ratchet: compliant.** No parameter moved in either direction. Note explicitly: gold rallying **does not** license raising the stop. The ratchet permits stops to move *toward* price, but a discretionary tightening into a breakout is not required and is not taken — the $3,850 line marks a structural invalidation of the base, not a trailing distance.

**Stop Migration Ledger: EMPTY this report.** The 2026-08-07 checkpoint set on 2026-08-01 has not yet resolved, so no forward roll applies.

**Checkpoint prognosis (calendar-locked):**
> **Checkpoint Friday 2026-08-07** — validated against the venue calendar **before** any distance language: a **full COMEX trading session, 13:30 ET close**. No US market holiday falls in the week of 2026-08-03…07 (the next is Labor Day, Monday 2026-09-07). No restatement applied.
> **Fires iff** ≥2 consecutive weekly COMEX closes below $3,850 **AND** mechanical score <8. Current consecutive closes below the line: **0**.
> Spot is **10.66% above** the line — **7.07× the 5-day ADR** of **$58.08** (mean |high−low| over 2026-07-29, 07-30, 07-31, 08-03, 08-04; **all five are full COMEX sessions with no holiday abbreviation**; the in-progress 2026-08-05 session is **EXCLUDED** as not a full session, disclosed inline, and the lookback extended one session to reach five full ones).
> **It structurally cannot fire on 2026-08-07** — zero of the two required closes exist, and one close cannot supply two.
> **Tier-1 release before the checkpoint: YES — Nonfarm Payrolls, Friday 2026-08-07, 08:30 ET, five hours BEFORE the 13:30 ET COMEX close.** Named in the falsifier per the calendar-lock rule: a hot print lifts real yields, which is gold-negative; a soft print pulls them down, which is gold-positive. **Because an unpriced tier-1 release sits between report and checkpoint, no likelihood adjective is used about price direction** — the only claim made is the mechanical one (0 of 2 required closes), which is traced to a named quantity and is independent of the release.
> **Additional scheduled high-information event on the same day: the CFTC COT report, Friday 2026-08-07 15:30 ET** (Tuesday 2026-08-04 positioning) — the single event most capable of changing gate 1. A ≥20–30K or ≥15% WoW net-long decline would take gate 1 to provisional and, on a confirming second weekly print, to lit, moving the board to 3/8 and clearing Phase 1A's gate condition outright. A +4% price week makes a long flush less likely, not more.

---

## 7. Exit / Trim Framework — GOLD

**Every score condition reads the MECHANICAL score (8), never the adjusted 7.** Hard Rule 2 makes this section first-class, and it is genuinely live here — this is the only asset in the batch with a real position.

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score ≥6 points below local peak | Local peak this campaign: **10** (2026-07-11/13/14) | 8 → drop of **2** | ❌ not triggered |
| Sentiment ≥75 sustained 7d **AND** weekly RSI >70 | — | sentiment NOT FOUND; weekly RSI 38.98 (live-week ~48) | ❌ |
| Valuation extreme — drawdown from ATH <10% with a vertical 30d return | Needs spot ≥ **$5,027.58** | −23.73% (spot $4,260.60) | ❌ — **but this is the trigger that today's move walks toward.** A further +18% arms it. |
| Mechanical score ≤3 **AND** price ≥40% above blended cost | — | 8; no verifiable cost basis | ❌ |
| ETF outflows ≥3% AUM after a sustained inflow regime | — | outflows, not inflows; no sustained-inflow regime in the position's life | ❌ |
| **Narrative break** | — | none — no regulatory, custodial or structural break in gold or in PAXG's issuer | ❌ |

**Current exit status: NONE. No trim executed, no exit triggered. Position held.**

**Stated because Hard Rule 2 demands symmetry and because the tape moved:** a +4% day is the direction in which trim triggers eventually arm, and it is worth naming how far away they are. The valuation-extreme trigger needs a drawdown inside 10% of the window high — **$5,027.58, +18.0% above spot**. The momentum/sentiment trigger needs weekly RSI >70 against 38.98. Neither is close. The position is working, not overheating, and nothing about today's move argues for taking profit.

---

## 8. Critical Watchlist

| Date / Time (ET) | Event | Gold impact |
|---|---|---|
| **Fri 2026-08-07, 08:30** | **Nonfarm Payrolls (July Employment Situation)** — the only tier-1 US release in the next 5 trading sessions; lands on the checkpoint day, 5h before the COMEX close | **HIGH.** Hot → real yields up → gold-negative. Soft → real yields down → gold-positive. Named in the checkpoint falsifier; no likelihood adjective used about direction. |
| **Fri 2026-08-07, 15:30** | **CFTC COT report** (2026-08-04 positioning) | **HIGH — the single event most capable of changing the deployment answer.** A ≥20–30K or ≥15% WoW net-long decline takes gate 1 to provisional; a confirming second print lights it and moves the board to 3/8. |
| Ongoing | **Strait of Hormuz reopening** — signature and first convoy volumes | HIGH, two-sided. The oil collapse that lifted gold trades the *deal*; a signed deal plus a hawkish Fed unwinds both the reflation and rate hedges (the Bear scenario). |
| Ongoing | **WGC / GLD physical flows** — first prints after the breakout | **HIGHEST for the score.** Gate 4 and capitulation-(c) both rest on outflows. A reversal takes the leg 1 → 0, mechanical 8 → 7, and **degrades the compound stop to price-only on a bullish datum.** |
| **Wed 2026-08-12, 08:30** | **CPI (July)** — first tier-1 beyond the 5-session window | HIGH. Brent at −12.41% should begin showing in headline; a soft print supports the disinflation-plus-easier-Fed mix gold rallied on. |
| Thu 2026-08-13 / Fri 2026-08-14 | PPI / Retail Sales | MEDIUM |
| **Wed 2026-09-16** | **FOMC decision** | HIGH. ~59–63% hike priced. |
| Watch | **Gold above $4,468.96** (within 20% of the $5,586.20 1-yr high) | **PROCESS TRIGGER — now only +4.89% away.** Restores Channel A eligibility, stops the FR phase-of-cycle cap from binding, makes Hard Rule 5's both-≥12 check falsifiable again, and makes a standalone Flying Rocket report **mandatory** under vacuity trigger (iv). |
| **Owed now** | Standalone **Flying Rocket report for ETH** (companion printed 9 on 2026-08-01 and again today) | Process item carried across this batch. |

**Tier-1 calendar verification:** `compute.mjs tier1 --from 2026-08-05 --sessions 5` → window 2026-08-06 … 2026-08-12, exactly one tier-1 event (NFP 2026-08-07), **zero warnings**. **Not an incomplete-data report on the calendar dimension.**

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

Gold did in one session what it had refused to do for five weeks: it resolved. Up 4.03% to $4,260.60 on a 152-point range — 2.6 times the five-day average — with volume at the **99.4th percentile of two years**, closing near the high, out the top of a $3,965–$4,165 box it had been stuck in since late June. Tokenized gold confirms it independently: PAXG from $4,065 to $4,217 on the same session, +3.74%. This was not a drift or a futures-roll artifact. Real money moved.

And the driver is legible, which matters more than the magnitude. Brent fell **12.41%** in five sessions on progress toward a Hormuz reopening. Falling energy pulls inflation expectations down with it, and falling inflation expectations pull the perceived odds of further Fed tightening down too. The dollar softened 1.06%. Gold's rally is not a fear bid — the S&P set a record on the same news — it is a **rate-expectations bid**. Bullion rallying alongside equities on a *disinflationary* impulse is coherent, and it tells you which lever is moving the metal right now.

For the position, this is good. For the *next tranche*, it is the opposite, and I want to be blunt about that because a fear-accumulation framework is easy to misread when the thing you own goes up. Every dark reachable gate on this board requires **weakness**. Gate 2 needs a weekly RSI below 30 and gold just pushed its live-week reading toward 48. Gate 7 needs a downside flush and gold just printed its heaviest volume in two years in the wrong direction. Gate 1 needs a positioning washout and a +4% week is when longs get added, not flushed. Meanwhile the one lit fear gate — physical ETF outflows — is precisely the input a breakout tends to reverse. **The board got worse this week, and it got worse because the asset did well.** That is the system behaving correctly.

There is one genuinely encouraging structural datum against all that, and it is worth holding onto: the **weekly RSI still sits at the 8.67th percentile of two years**, and the 200dma is **rising**. A market can be washed out on the higher timeframe and still be climbing its long-run mean. That combination is a base resolving, not a top forming — and it is why the position is held rather than trimmed, and why the Bear cell is only 12%.

But the honest summary of where accumulation stands is short: gold is 23.7% off its window high with a valuation leg pinned at zero, a Phase 2 zone now 7–13% below spot, and a fear board moving away from it. This is a **hold with the ladder further away than it was on Friday**.

### 9.2 What the rubric structurally cannot see

1. **Upside structural resolution.** No leg and no gate scores volume, range resolution, or breakout character. Gate 7 scores a *downside* flush only, so a 99.4th-percentile volume day on an up move is completely invisible to the board. The valuation leg *measured* the drawdown (−27.52% → −23.73%) and its band was saturated at 0 at both ends — it **could not express what happened**. **Bearish for accumulation.**
2. **The macro transmission channel, and its sign.** Gate 9 is one boolean; it cannot say that falling energy → falling inflation expectations → fewer expected hikes is a *gold-positive* impulse. No leg scores macro. **Bearish for accumulation** (the same event that is a *reduced* negative for BTC is an accumulation-negative for gold — one macro, opposite signs, scored once in each place).
3. **Higher-timeframe washout vs the daily move.** Weekly RSI at the 8.67th percentile while the daily RSI reads 60.68 is a real tension the single momentum band cannot express. **Bullish for the position, not for the next tranche.**
4. **Proximity to a routing change.** The FR companion is +4.89% from restoring Channel A eligibility, and the 200dma slope has flattened from +0.52% to +0.35%. No FK input tracks this. **Process-relevant, direction-neutral.**
5. **PAXG issuer/custody risk.** The position is held in a token, not in bullion. No leg scores counterparty risk. **Mildly bearish, and explicitly not taken as a D1 factor** — it is a constant of how the position is held, not new information.

### 9.3 The D1 term — **−1.0** (negative)

**Value: −1.0. Direction: negative. First non-zero D1 in the gold series.**

**Factor (i) — an upside structural resolution the rubric cannot register.** Volume at the **99.4th percentile of two years**; a 152-point range at **2.62× the 5-day ADR of $58.08**; a close near the session high; a five-week $3,965–$4,165 range resolved **upward**; the gap to a **rising** 200dma closed from −9.61% to −4.86% in three sessions. Corroborated on an independent instrument (PAXG +3.74% on four venues). **No leg or gate scores volume, range resolution, or breakout character** — gate 7 is downside-only by construction, and the valuation band was saturated at 0 both before and after the move, so the leg is literally incapable of expressing it. This is not a re-weighting of the drawdown *level* the valuation leg scores; it is a different object — the *structure and participation* of the move.

**Factor (ii) — a dated macro transmission with the opposite sign to the crypto book.** Brent **−12.41%** over five sessions on Hormuz reopening progress, DXY **−1.06%**, and market reporting explicitly attributing gold's move to the resulting trim in inflation expectations and Fed tightening odds (~59–63% for September). Gate 9 is a single boolean and no leg scores macro. **Consistency note, stated to preempt the obvious objection:** the BTC report *retires* its macro factor as weakened by exactly this evidence, and the ETH report *excludes* shared macro entirely. That is not a contradiction — the same oil collapse is a *reduced negative* for crypto risk appetite and a *positive for gold's price*, and a positive for price is a **negative for an accumulation score**. One macro event, scored once in each report, with the sign the transmission channel actually implies.

**Falsifier (dated):** retire the term when **either** (a) gold prints a **weekly close back below $4,100**, re-entering the broken range and invalidating the resolution, **or** (b) WGC/GLD August flows print another **multi-region outflow month of ≥50 tonnes**, confirming that the ETF-outflow regime the capitulation leg and gate 4 rest on survived the breakout. **Hard review date: 2026-08-19.**

**Was a larger negative considered?** Yes, −1.5 and −2.0. **Declined.** The weekly RSI sits at the **8.67th percentile of two years** and the 200dma is rising — the higher timeframe is still washed out and structurally intact, which cuts hard the other way. One session is one session, and Analytical Principle 3's warning against promoting a single observation to structure applies in both directions.

**Was a positive adjustment considered?** Yes, **+0.5**, on the argument that a rising 200dma with a washed-out weekly RSI is a constructive base and that record volume confirms real accumulation. **Declined on two grounds:** the momentum leg already scores the weekly RSI at 2, so re-weighting it is prohibited double-counting; and a positive term would be arguing that gold is a *better* accumulation candidate on the day it became 4% more expensive with its fear board moving away. That is the wrong direction for this framework.

**Effect of the term:** adjusted score 8 → **7**. Two real consequences, one cosmetic and one not:
- Phase 1A's ≥8 score line is **lost** (cosmetic — 1A is deployed and independently gate-blocked at 2/8 < 3).
- **The D2 conviction path on Phase 1A becomes UNAVAILABLE**, because D2 requires the phase's score condition to be met and substitutes for a gate, never for a score. On 2026-08-01 that channel was available-but-moot. Today it is closed. That is a genuine narrowing of optionality, taken deliberately.

**Governing-rule check, recorded because it is not hypothetical this time.** A −1.0 takes the adjusted score to 7, and **7 IS <8** — the compound stop's score condition. If that stop read the adjusted number, this discretionary term would have degraded the book's own stop to price-only. **It reads the MECHANICAL score (8), so it does not.** The 2026-08-01 report noted it had verified this interaction hypothetically; today a live −1.0 exists and the rule holds. Discretion buys entries and never touches exits — and here it demonstrably did not touch one.

### 9.4 Discretionary actions taken and declined

**D2 conviction path — was available on 2026-08-01, is UNAVAILABLE today.** Phase 1A: the gate count is still short by **exactly one** (2 of 3) and the [V] floor is still met (2 ≥ 2), but the **score condition fails** at adjusted 7 < 8. D2 substitutes for a gate, never for a score. Phase 1B: unavailable on the score too (7 < 11). **A channel closed as a direct consequence of the D1 term**, and it is recorded as such rather than left as an unremarked absence.

**Deep-Value Override — evaluated, does not fire.** Mechanical 8 < 15 is dispositive. Two further independent failures: sentiment is NOT FOUND so the extreme-band condition cannot be satisfied at all, and the price condition fails outright — spot is *above* the neighbourhood of the most recent tranche, not 8% below it. No near-fire, no veto or throttle reached.

**Low-vol valuation band-set — considered, NOT reinstated.** It has been withdrawn since 2026-07-18. Reinstatement needs gold's realized 30-day vol documented at ≤½ of BTC's contemporaneous figure: **25.11% / 29.35% = 0.86**, nowhere near 0.50. It would fail its own test, and it would change nothing anyway (the leg scores 0 on either band-set at −23.73%). Recorded so the absence is a decision, not an oversight.

**Phase 2 zone re-anchoring — considered and DECLINED.** Gold at $4,260.60 leaves the $3,700–3,950 zone 7.3–13.2% below spot, and there is a superficial case for lifting it. **Declined because it would make the coherence check easier**: raising the deepest named floor is a back-door loosening of the test that the catastrophic stop must clear. The zone stays where prior reports named it, and the ladder is simply further away.

**A conservative spot demotion — TAKEN, not required.** The futures-vs-cash gap is an explainable carry basis rather than genuine venue disagreement, so the low-confidence demotion is not strictly mandated. Applied anyway (§2.1), because the EV sign flips across the gap and the canonical rests on frozen bar closes. Flagged as a deviation toward conservatism.

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 (GOLD) | Declined +0.5 — unlocks nothing, ungradeable | — | — | — | RETIRED — declined | n/a |
| 2026-08-01 | D1 (GOLD) | Declined −0.5 — would double-count the macro stack already keying gate 4 and gate 9 | — | — | — | RETIRED — declined | n/a |
| 2026-08-01 | D2 (GOLD 1A) | Available but moot (1A already deployed) | — | — | — | **RETIRED — channel now CLOSED by the 2026-08-05 D1** | n/a |
| **2026-08-05** | **D1 (GOLD)** | **−1.0 TAKEN — first non-zero D1 in the gold series.** (i) upside structural resolution the rubric cannot register: 99.4th-percentile volume, 2.62× ADR range, five-week box resolved up, 200dma gap −9.61% → −4.86%; (ii) a dated macro transmission — Brent −12.41%, DXY −1.06%, tightening odds trimmed — with the **opposite sign** to the crypto book | score-only, no position | none (opened no tranche) | Weekly close back **below $4,100** **OR** WGC/GLD August flows print another multi-region outflow month ≥50t | **LIVE** — hard review 2026-08-19 | n/a |
| **2026-08-05** | **D1 (GOLD)** | Declined **−1.5 / −2.0** | — | — | — | RETIRED — declined (weekly RSI at the 8.67th percentile and a rising 200dma cut hard the other way; one session is one session) | n/a |
| **2026-08-05** | **D1 (GOLD)** | Declined **+0.5** on "rising 200dma + washed-out weekly RSI + record volume = constructive base" | — | — | — | RETIRED — declined (double-counts the momentum leg; and would argue gold is a *better* accumulation candidate on the day it got 4% more expensive) | n/a |
| **2026-08-05** | **D2 (GOLD 1A)** | **UNAVAILABLE** — score condition fails at adjusted 7 < 8 | — | — | — | **CLOSED — a direct consequence of the D1, recorded not assumed** | n/a |
| **2026-08-05** | — | Declined re-anchoring the Phase 2 zone upward | — | — | — | RETIRED — declined (would loosen the coherence check by the back door) | n/a |
| **2026-08-05** | — | **Applied a conservative low-confidence spot demotion** not strictly mandated by the letter of the rule | — | — | — | **LIVE** — read marked corroborative-only | n/a |

No D1 or D2 tranche has ever been opened on gold, so no D5 stop exists and no analyst-channel bar is running. Non-mechanical capital: **0%** of book.

### 9.6 What would change my mind

- **Toward re-engaging the ladder, dated:** a weekly close back below **$4,100** — the breakout failing its retest — which retires half the D1 on its own falsifier and puts the $3,965 range floor back in play. Or a COT print on **Friday 2026-08-07** showing a ≥20–30K / ≥15% net-long flush, which takes gate 1 to provisional and, on confirmation, moves the board to 3/8.
- **Toward reducing conviction in the accumulation case, dated:** a **WGC/GLD August print showing physical inflows resuming**. That would take capitulation-(c) to zero, the leg 1 → 0, the mechanical score 8 → 7, and — the part that matters — **degrade the compound stop to price-only on a bullish datum**. It is the least intuitive risk on this board and the one I am watching hardest.
- **Toward a different framework entirely:** gold above **$4,468.96** (+4.89%). Channel A eligibility restores, the FR phase-of-cycle cap stops binding, Hard Rule 5's both-≥12 check becomes falsifiable again, and a standalone Flying Rocket report becomes mandatory. At that point the question stops being "where do I add" and starts being "is this distribution."
- **Process:** refresh `position-snapshot.json`, and fix the single unbacked disposal — this is the smallest basis defect in the account and the only one where one correction would restore a full P&L picture on a real position.

---

## 10. Bull vs Bear Scorecard — GOLD

*Read as: bull = supports the accumulation thesis or the held position; bear = argues against adding here.*

**Bull (8):**
1. ✅ Gate 4 lit — sustained multi-region physical ETF outflows (July global −76.44t; US-listed ~−$5.3bn; June −$8.9bn all regions)
2. ✅ Gate 8 lit — official-sector demand stable, concentration stable
3. ✅ Weekly RSI **38.98** at the **8.67th percentile of two years** — the higher timeframe is still deeply washed out
4. ✅ **200dma RISING** (+0.35%/20 sessions) — the structural uptrend is intact, unique among the three assets in this batch
5. ✅ Five-week range resolved **upward** on **99.4th-percentile volume**, close near the high, corroborated on PAXG (+3.74%)
6. ✅ Gap to the 200dma closed from −9.61% to −4.86% in three sessions
7. ✅ **Compound stop retains FULL two-key protection** — score axis unsatisfied (8 is not <8), unique in this batch
8. ✅ DXY −1.06%, Brent −12.41% trimming inflation expectations and tightening odds — the macro mix gold rallies on

**Bear (10):**
1. ❌ Valuation leg **0** — drawdown only −23.73%; even a score of 1 needs **$3,910.34** (−8.2% from spot)
2. ❌ Gate 3 needs **$2,793.10** — a further −34.4%; "none-in-regime"
3. ❌ Gate 6 fails from **expensiveness** — spot **+49.20%** above the 200-week mean, and today moved it further away
4. ❌ Gate 1 dark — COT washout −1,840 (−1.00%) against a ≥20–30K / ≥15% bar, an order of magnitude short
5. ❌ Gate 7 dark — record volume arrived on an **up** day; a downside flush is now less likely near-term
6. ❌ Sentiment **NOT FOUND**, carried at the fallback 2 (structural, debt branch discharged)
7. ❌ Holder leg **carried, not freshly verified** — second consecutive report on stale-input debt
8. ❌ **Adjusted score 7 — below the Phase 1A line**, and the D2 channel closed with it
9. ❌ Phase 2's $3,700–3,950 zone is now **7.3–13.2% below spot** — the ladder receded
10. ❌ Real 10y yield **rose** to 2.43%; ~59–63% September hike still priced; gate 4 is the most fragile input on the board

**Net: 8 bull / 10 bear — bear by 2.**

---

## 11. Change Log vs 2026-08-01

| Factor | 2026-08-01 | 2026-08-05 | Direction |
|---|---|---|---|
| Canonical spot (GC=F) | $4,049.10 | **$4,260.60** | **+5.22%** |
| PAXG live median | $4,042.41 | **$4,215.98** | +4.29% |
| Futures-vs-cash gap | +0.17% | **+1.03%** | widened — COMEX carry on a deferred contract, disclosed |
| Sentiment leg | 2 (NOT FOUND) | 2 (NOT FOUND) | flat |
| Momentum leg | 2 (wRSI 38.98) | 2 (wRSI 38.98 — **artifact-corrected from 41.03**) | flat, **and it was a finding** |
| Valuation leg | 0 (drawdown −27.52%) | 0 (drawdown **−23.73%**) | flat (band saturated) — **leg could not express the move** |
| Capitulation leg | 1 | 1 | flat |
| Holder leg | 3 (carried) | 3 (carried, 2nd report) | flat, debt clock running |
| **Mechanical score** | **8** | **8** | **flat — 4th consecutive report** |
| D1 discretionary | 0 (±0.5 both considered, declined) | **−1.0 TAKEN** | **first non-zero D1 in the gold series** |
| **Adjusted score** | **8** | **7** | **−1 — Phase 1A score line LOST** |
| Gates | 2/8 (4, 8), [V] 2 | 2/8 (4, 8), [V] 2 | flat — gate 9 ❌ → ⚠️ (no count change) |
| D2 on Phase 1A | available (moot) | **UNAVAILABLE — score condition fails** | **channel closed** |
| 200dma | $4,479.49, +0.52% slope | **$4,478.46, +0.35% slope** | still rising, flattening |
| Distance to 200dma | −9.61% | **−4.86%** | closed by half |
| 5-day ADR | $46.52 | **$58.08** | +24.8% — vol expanded |
| Session volume percentile | — | **99.40** | record participation |
| Weighted EV | $4,012.38 (−0.91%) | **$4,232.00 (−0.67%)** | re-anchored to the new spot |
| Realized 2-week | +0.91% | **+2.81%** | stronger |
| Correlation vs SPX | 0.240 | **0.353** | still mild, rising |
| Brent / DXY | $90.12 / 99.80 | **$79.48 / 99.73** | oil −12.41%, dollar softer |
| Real 10y yield | 2.41% | **2.43%** | marginally higher |
| FR routing | none (STAND DOWN), 27.52% off 1y high | none (STAND DOWN), **23.73% off** | **+4.89% from a routing change** (was +10.4%) |
| FR companion | 2 (ceiling 8) | **1 (ceiling 6)** | lower |
| Compound stop score axis | **UNSATISFIED** (8 not <8) | **UNSATISFIED** (8 not <8) | **held — and defended twice, see §6.3** |
| Position band | STALE (26h) | **EXPIRED (94.2h)** | **degraded — cold start, position carried from prior report** |
| Collar | ACTIVE | **ACTIVE** (\|EV\| 0.67% < 2%; mechanical 8 in the 6–10 band) | still binding |

---

## 12. Strategic Verdict — GOLD

**Adjusted score 7/20 · Mechanical 8/20 · D1 −1.0 · Weighted EV $4,232.00 · EV-vs-spot −0.67% (GC=F) / +0.38% (cash) · Sentiment NOT FOUND · Stance: HOLD 1.3294 PAXG (last-confirmed); deployment FROZEN; 75% dry at a 3.73% T-bill carry.**

Two things happened this week and only one of them was a market event. Gold resolved a five-week range to the upside — +4.03% in a session, a 152-point bar at 2.6 times the five-day average, volume at the 99.4th percentile of two years, close near the high, confirmed independently on tokenized gold at +3.74%. The driver is legible: Brent fell 12.41% on Hormuz reopening progress, that pulled inflation expectations down, that pulled expected Fed tightening down with them, and the dollar softened 1.06% alongside. Bullion rallying on the same headline that put the S&P at a record is not a fear bid; it is a rate-expectations bid, and knowing which lever is moving the metal is worth more than the four percent. For a fear-accumulation framework, none of this is good news. The position is working and the next tranche is receding — Phase 2's $3,700–3,950 zone now sits 7 to 13 percent below spot, and three of the four dark-but-reachable gates moved *further* away precisely because they all require weakness that gold declined to supply. That is what a −1.0 prices, and it costs a real option: the D2 conviction path on Phase 1A, available on Friday, is closed today because D2 substitutes for a gate and never for a score.

The other thing that happened was in the plumbing, and it mattered more. Yahoo began emitting an extra weekly bar for the live session, which caused the fetch tool to fold an **in-progress** week into its completed-closes set. On gold that returns a weekly RSI of 41.03 instead of 38.98 — momentum leg 2 → 1, mechanical score 8 → 7 — and the compound stop's condition is *score below 8*. Seven is below eight. A live stop on the only real position in this account would have quietly degraded from two-key protection to a bare price line at $3,850, and nothing in the market would have caused it. The 2026-08-01 report predicted this failure mode and named the wrong culprit; it guessed the point of decay would come from a bullish ETF-flow print, and it nearly came from a bar count. The 261-close basis reproduces this series' own prior prints across all three assets, which is how the artifact was caught. **Stops are not permitted to weaken because a data source changed its pagination**, and the correction is documented in §2.3 rather than silently applied.

So the stop survived two separate threats today for two different reasons, and it is worth stating both because they are the framework earning its design. The artifact was caught by verification. The **discretionary term was neutralized by the governing rule** — a −1.0 takes the *adjusted* score to 7, which would satisfy the stop's axis if the stop read that number, and it reads the *mechanical* 8 instead. The 2026-08-01 report checked that interaction hypothetically and recorded that it worked; today it was live and it worked. Gold consequently holds the only compound stop in this batch with full two-key protection, while both crypto assets sit price-gated. The remaining risk is unchanged and still points at gate 4: a single point of *mechanical* decay satisfies the axis, and the point most at risk is the ETF-outflow condition holding the capitulation leg at 1 — meaning the stop would erode on a *bullish* flow datum, which is the least intuitive thing on this board and the thing I am watching hardest. Meanwhile the position itself is unreadable: the ledger expired at 94.2 hours, the last confirmed quantity is 1.3294 PAXG with custody RECONCILED and zero withdrawals, and `basis.reliable` was false on a single unbacked disposal — so today's rally cannot be turned into a P&L figure, and the temptation to restate the withdrawn −10.50% MTM as something flattering is exactly what the rule is for. One disposal. It is the smallest defect in the account and the only one where a single correction restores a full picture on a real holding.

### Action Items

1. **Fix the toolchain artifact, or verify around it every report.** `tools/fetch.mjs` must exclude any weekly bar whose week has not closed, not merely drop the last bar. Until it does, re-derive weekly RSI on the last genuinely completed week and cross-check against the prior report's print — that check is what caught this.
2. **Refresh the position ledger and fix gold's single unbacked disposal first.** It is the smallest basis defect in the account (1 disposal vs BTC's 5 and ETH's 24), and it sits on the only real position. One fix restores cost basis, unrealized PnL and ROI on 1.3294 PAXG.
3. **Hold the position. Trim nothing.** No §7 trigger is close: the valuation-extreme trim needs $5,027.58 (+18.0%) and the momentum trim needs weekly RSI >70 against 38.98.
4. **Hold every stop where it is.** Catastrophic/held $3,800, compound $3,850 + mechanical score <8, checkpoint Friday 2026-08-07 COMEX 13:30 ET. Coherence run in both states: held FAIL (expected-for-frozen), post-activation PASS. No realignment owed. **Do not tighten into the breakout** — the $3,850 line marks a structural invalidation of the base, not a trailing distance.
5. **Leave the Phase 2 zone at $3,700–3,950.** Raising it would loosen the coherence check by the back door. The ladder is simply further away now.
6. **Friday 2026-08-07 is a double event.** NFP at 08:30 ET (real-yield direction, gold-negative if hot) and the CFTC COT at 15:30 ET (the single print most capable of lighting gate 1). Neither can fire the checkpoint.
7. **Watch WGC/GLD August flows above everything else.** A reversal to inflows takes the capitulation leg 1 → 0, mechanical 8 → 7, and degrades the compound stop to price-only on a bullish datum.
8. **Set a process tripwire at $4,468.96 (+4.89%).** Above it, Channel A eligibility restores, the FR cap stops binding, and a standalone Flying Rocket report becomes mandatory under vacuity trigger (iv).

> **The Pattern**
>
> **IF** gold prints a weekly close back below **$4,100** → **THEN** the breakout has failed its retest, half the D1 retires on its own falsifier, the $3,965 range floor comes back into play, and the ladder toward $3,700–3,950 becomes a live conversation again rather than a distant one.
>
> **IF** the WGC/GLD August prints show physical **inflows resuming** → **THEN** capitulation-(c) goes to zero, the mechanical score falls to 7, the compound stop's score axis flips to satisfied, and this book's best-protected position degrades to a bare price line at $3,850 — **on good news**. That is the single most important conditional in this report, and it is the one nobody would think to watch for.
>
> **IF** gold closes above **$4,468.96** → **THEN** the Flying Rocket phase-of-cycle cap stops binding, Hard Rule 5's cross-validation becomes genuinely falsifiable again, and a standalone short-side report becomes mandatory. The question would shift from *where do I add* to *is this distribution* — and a framework that only ever asks the first question is not a framework.

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-GOLD-20260805-1008 | STAND_DOWN | non_crypto_derivative |
| 1B | FK-P1B-GOLD-20260805-1008 | STAND_DOWN | non_crypto_derivative |
| 2 | FK-P2-GOLD-20260805-1008 | STAND_DOWN | non_crypto_derivative |
| 3 | FK-P3-GOLD-20260805-1008 | STAND_DOWN | non_crypto_derivative |

Registry schema: report-phase-registry/1; version: 1; origin: gold_fallen_knives_20260805_1008.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "GOLD",
  "date": "2026-08-05",
  "spot": { "value": 4260.60, "source": "Yahoo GC=F COMEX front month, 2026-08-05 live-session bar. FEWER THAN 3 SYNCHRONIZED LIVE QUOTES obtainable for COMEX gold and this is disclosed: fetch.mjs returns n_synchronized:0 / low_confidence:true ('no synchronized quotes available'); MGC=F is the same complex, not independent corroboration. Live 24/7 cross-check: PAXG median $4,215.98 across Binance $4,219.92 / Kraken $4,217.19 / Coinbase $4,214.77 / CoinGecko $4,207.82 (all 2026-08-05 14:08 UTC, spread 0.287%), plus an independent Coinbase XAU/USD cash quote of $4,218.59 within 0.06% of that median. GC=F sits +1.03% above the cash cluster vs +0.17% on 2026-07-31 — that widening is COMEX CARRY ON A DEFERRED CONTRACT (3.73% risk-free over ~3-4 months is ~1.1-1.2%; the front month has rolled off the near-expiry August contract), NOT venue disagreement. GC=F held as canonical for SERIES CONTINUITY: the 10y window high $5,586.20, the drawdown feeding the valuation leg, the 200-week SMA and the 200dma are all computed on the GC=F series. EV computed at BOTH ends: -0.67% vs GC=F, +0.38% vs the cash cluster — THE SIGN FLIPS and |median EV-vs-spot| 0.67% < the 1.03% gap. The letter of the rule ties low-confidence demotion to genuine simultaneous disagreement among synchronized quotes, which a carry basis between different instruments is not, so the demotion is NOT strictly mandated — APPLIED ANYWAY as a conservative deviation and flagged as such: this read is LOW-CONFIDENCE / CORROBORATIVE-ONLY and any action would require a second independent unlock condition. Today's move is corroborated independently of the futures series: Kraken PAXG opened $4,065.00 and printed $4,217.19, +3.74%, against GC=F's +4.03%." },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 0, "capitulation": 1, "holder": 3 },
    "discretionary": -1.0,
    "mechanical": 8,
    "raw": 7.0,
    "adjusted": 7,
    "rounding": "half-up"
  },
  "gates": { "active": 8, "na": [5], "passed": [4, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 26, "low": 4400, "high": 4650 },
      { "name": "Range", "p": 38, "low": 4150, "high": 4400 },
      { "name": "Retest", "p": 24, "low": 3950, "high": 4150 },
      { "name": "Bear", "p": 12, "low": 3700, "high": 3950 }
    ],
    "stated_ev": 4232.00,
    "vs_spot_pct": -0.67
  },
  "deployment": {
    "deployed_pct": 25,
    "dry_pct": 75,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~4650 zone per prior reports; DEPLOYED (last-confirmed, ledger EXPIRED at 94.2h). NO entry_price asserted: basis.reliable was false (1 unbacked disposal) so encoding a numeric fill would assert a cost this report explicitly declines to state. SCORE LINE LOST — adjusted 7 < 8, reversing the first-ever clearing recorded on 2026-08-01. Cosmetic for capital (1A is deployed and independently gate-blocked at 2/8 < 3) but NOT cosmetic for optionality: the D2 conviction path on 1A is now UNAVAILABLE because D2 substitutes for a gate, never for a score. Attribution UNTAGGED.", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "~4475 zone per prior reports; DEPLOYED (last-confirmed). NO entry_price asserted, same basis reason. Score-blocked for any further authorization (adjusted 7 < 11). Attribution UNTAGGED.", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "3700-3950 prospective, FROZEN (adjusted 7 << 15, gates 2/8 < 6); now 7.3-13.2% BELOW spot after the breakout. Atomic re-stop 3800 -> 3650 required BEFORE any first fill. Zone DELIBERATELY NOT re-anchored upward: raising the deepest named floor would make the coherence check EASIER, a back-door loosening.", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 8 < 17, gates 2/8 < 7)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 3800,
    "held_position_stop": 3800,
    "prospective_p2_floor": 3700,
    "prospective_p2_restop": 3650,
    "deepest_zone_floor_absent_note": "deepest_zone_floor is deliberately NOT set, matching this series' convention (2026-08-01 and prior). The linter's coherence check tests the deepest ACTIVE buy-zone floor, and there is no active zone: deployment is FROZEN and the $3,700-3,950 Phase 2 zone is PROSPECTIVE, encoded as prospective_p2_floor. The SKILL's own rule is satisfied and the check is run IN BOTH STATES in the prose and in stops.note — held state $3,800 vs $3,700 = FAIL (expected-for-frozen), post-activation $3,650 vs $3,700 = PASS. Because the post-activation state PASSES and no new deployment is authorized, NO 'stop realignment owed' flag is raised. This is disclosure, not evasion: the failing number is printed in full above.",
    "compound": { "price": 3850, "score_line": 8 },
    "note": "NO stop parameter changed value. SCORE AXIS UNSATISFIED: mechanical score is 8 and the condition is score <8 — 8 is NOT <8 — so the compound stop retains FULL two-key protection and cannot fire on price alone. Materially better protection than either crypto asset in this batch (BTC 11<12 and ETH 11<12 both have their axes SATISFIED and are effectively price-gated). TWO SEPARATE MECHANISMS COULD HAVE REMOVED THAT PROTECTION TODAY AND NEITHER DID, for two different reasons. (1) The toolchain artifact: fetch.mjs's completed-closes set silently includes the IN-PROGRESS week, returning weekly RSI 41.03 instead of the correct 38.98 — momentum leg 2->1, mechanical 8->7, and 7 IS <8, degrading this stop to price-only at $3,850. Caught by cross-checking against this series' own prior prints and CORRECTED; see key_inputs.weekly_rsi_tool_artifact. A stop is not permitted to weaken because a data source changed its pagination. (2) The D1 -1.0 takes the ADJUSTED score to 7, which IS <8 — and has ZERO effect here because the compound stop reads the MECHANICAL score per the 2026-07-27 governing rule. The 2026-08-01 report verified this interaction HYPOTHETICALLY; today a live -1.0 exists and the rule holds. Discretion buys entries and never touches exits, demonstrated rather than asserted. LIVE RISK UNCHANGED: a single point of MECHANICAL decay takes gold to 7, satisfies the axis, and degrades this stop to price-only — and the point most at risk is capitulation-(c), the ETF-outflow condition currently the only thing holding that leg at 1, which would erode the stop on a BULLISH flow datum. Coherence run in BOTH states as required: held state catastrophic $3,800 vs deepest NAMED prospective ladder floor $3,700 = FAIL (compute.mjs stop-coherence pass:false) — expected-for-frozen, the zone is prospective and deployment is FROZEN; post-activation re-stop $3,650 vs $3,700 = PASS. No new deployment is authorized, so NO 'stop realignment owed' flag. The $3,650 re-anchor is a D6 exception-1 named-zone re-anchor (the 3700-3950 zone is named in prior reports in this series), permitted only when executed atomically before the first fill and cited; NOT executed today. No D5 stops — zero analyst-channel tranches. Max drawdown spot-to-compound-line -9.64%, widened from -4.92% purely because price rose; disclosed, purchases no loosening. D6 ratchet: compliant, no parameter moved in either direction — and note explicitly that gold rallying does NOT license tightening into the breakout: the $3,850 line marks a structural invalidation of the base, not a trailing distance.",
    "migration": [],
    "checkpoint": {
      "date": "2026-08-07",
      "line": 3850,
      "condition": ">=2 consecutive weekly COMEX closes <3850 AND mechanical score <8",
      "closes_below": 0,
      "adr": 58.08,
      "adr_sessions": "2026-07-29, 07-30, 07-31, 08-03, 08-04 — ALL FIVE are full COMEX sessions with no holiday abbreviation; the in-progress 2026-08-05 session EXCLUDED as not a full session, lookback extended one session to reach five full ones, exclusion disclosed inline",
      "dist_x_adr": 7.07,
      "calendar_validation": "Fri 2026-08-07 is a full COMEX trading session, 13:30 ET close; no US market holiday falls in the week of 2026-08-03..07 (next is Labor Day Mon 2026-09-07); no restatement applied; date computed and validated BEFORE any distance language",
      "side": "spot 10.66% above line; structurally cannot fire (0 of 2 required closes exist, and one close cannot supply two)",
      "tier1_before_checkpoint": "YES — Nonfarm Payrolls Fri 2026-08-07 08:30 ET, five hours BEFORE the 13:30 ET COMEX close. Named in the falsifier per the calendar-lock rule: hot print -> real yields up -> gold-negative; soft print -> real yields down -> gold-positive. Because an unpriced tier-1 release sits between report and checkpoint, NO likelihood adjective is used about price direction; the only claim made is the mechanical one (0 of 2 required closes), traced to a named quantity and independent of the release.",
      "scheduled_high_information_event": "CFTC COT report Fri 2026-08-07 15:30 ET (Tue 2026-08-04 positioning) — the single event most capable of changing the deployment answer. A >=20-30K or >=15% WoW net-long decline takes gate 1 to provisional and, on a confirming second weekly print, to lit, moving the board to 3/8 and clearing Phase 1A's gate condition outright. A +4% price week makes a long flush LESS likely, not more."
    }
  },
  "companion_fr": {
    "score": 1,
    "score_floor": 1,
    "score_ceiling": 6,
    "channel": "none",
    "channel_note": "STAND DOWN — no channel available.",
    "routing": { "pct_below_1y_ath": 23.73, "gt_20pct_below_1y_ath": true, "ma200_falling": false, "ma200_slope20_pct": 0.35, "ma200d": 4478.46, "price_below_ma200_pct": -4.86 },
    "routing_note": "Both Channel B regime conditions must hold; the 200dma is RISING (+0.35%/20 sessions) so Channel B's regime test FAILS, and >20% below the 1y high forecloses Channel A. Result: no channel — the genuine phase-of-cycle mismatch the original cap was written for (a basing tape that is neither a distribution top nor a confirmed downtrend). This remains the structural difference from BTC and ETH, both of which have FALLING 200dmas and route to Channel B.",
    "legs_channel_a": { "euphoria": 0, "momentum": 0, "valuation": 0, "distribution": 1, "vulnerability": 0 },
    "inputs_missing": ["mvrv_z"],
    "confidence": "partial",
    "hard_rule_5_dischargeable": true,
    "cap_vacuity_disclosure": "Score 1/20 with a floor of 1 and a ceiling of 6 on the missing input; the floor/ceiling band straddles neither 9 nor 12, so hard_rule_5_dischargeable is true. Interpretation bands >=9 unreachable at the ceiling — no phase reachable at any score, Channel A's 1A line of 11 sitting five points above the ceiling; Hard-Rule-5 both->=12 check structurally unfalsifiable in this regime.",
    "cross_validation": "structurally consistent (cap-bound; both->=12 unfalsifiable by construction)",
    "routing_tripwire": "MATERIALLY CLOSER THAN AT THE LAST REPORT. Channel A eligibility restores if gold closes within 20% of the $5,586.20 one-year high, i.e. ABOVE $4,468.96 — now +4.89% away, versus +10.4% on 2026-08-01. Alternatively Channel B opens if the 200dma rolls over from +0.35%, a slope that has flattened from +0.52% in four sessions. BOTH routing conditions moved closer. Above $4,468.96 the FR phase-of-cycle cap stops binding, Hard Rule 5's both->=12 check becomes genuinely falsifiable again, and a full standalone Flying Rocket report becomes MANDATORY under vacuity trigger (iv).",
    "standalone_report_owed": false,
    "standalone_report_note": "Not owed on gold today (composite 1, ceiling 6, no trigger fired). SEPARATELY OUTSTANDING ACROSS THIS BATCH: the ETH standalone FR report, owed since the 2026-08-01 ETH companion printed 9 and RE-FIRED today at 9, remains undischarged."
  },
  "position": {
    "source": "tools/position.mjs gold",
    "exit_code": 1,
    "band": "EXPIRED",
    "age_min": 5654,
    "age_driver": "holdings_as_of",
    "generated_age_min": 5652,
    "expired_after_min": 4320,
    "cold_start": true,
    "cold_start_basis": "Hard Rule 4 — stated explicitly, no fresh ledger was available. BUT Rule 4's default (all dry powder) applies only ABSENT a prior report or user confirmation, and prior reports DO confirm a real position, so the holding is carried forward as narrated-and-previously-confirmed rather than re-read as flat.",
    "requested_asset": "GOLD",
    "ledger_asset": "PAXG",
    "alias_disclosed": true,
    "alias_note": "The ledger cannot hold bullion, so gold resolves onto PAXG — tokenized gold tracking XAU ~1:1 but carrying issuer and custody counterparty risk that physical gold does not, and able to trade at a premium or discount. Today PAXG trades at a -1.03% discount to the GC=F futures canonical and within 0.06% of an independent cash XAU quote — i.e. it tracks CASH gold tightly, and the gap to the canonical is the futures carry basis, not a tracking failure. The counterparty risk does not disappear because the tracking is good. Canonical gold SPOT is unaffected and comes from Hard Rule 1 sources only (carve-out (a)).",
    "qty_last_confirmed": "1.32938940",
    "qty_status": "LAST-CONFIRMED, NOT CURRENT — read from the 2026-08-01 snapshot while it was STALE. At 94.2h the ledger cannot satisfy any phase-dependent unlock precondition and cannot fill a realized ledger column.",
    "custody_status_last_confirmed": "RECONCILED — deposits 0, withdrawals 0, off-venue 0; trade-derived quantity agreed exactly",
    "share_of_portfolio_pct_last_confirmed": 27.3,
    "basis_reliable": false,
    "unbacked_disposal_count": 1,
    "oversold_qty": "0",
    "short_qty": null,
    "avg_cost_usd": null,
    "total_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd_upper_bound": 915.22,
    "attribution": "UNTAGGED",
    "dry_powder_stable_usd": null,
    "dry_powder_benchmark_pct": 3.73,
    "dry_powder_benchmark_source": "Yahoo ^IRX 2026-08-05; FRED DGS3MO cross-check 3.91%",
    "last_readable_dry_powder_usd": 14288.54,
    "note": "EXIT 1 / EXPIRED at 94.2h — cold start per Hard Rule 4, stated explicitly; degraded from STALE 26h on 2026-08-01. The quantity 1.3294 PAXG is reported as LAST-CONFIRMED, not current. NO average cost, cost basis, unrealized PnL, ROI or MTM is quoted: basis.reliable was false on 1 unbacked disposal, and the '-10.50% MTM off a ~$4,545 blended cost' this series quoted for three reports was WITHDRAWN on 2026-08-01 and is NOT restated here — note that today's +4% rally would have made that figure look considerably BETTER, which is exactly the temptation the rule exists to refuse. The framework knows roughly what is held and cannot say what it cost. Realized $915.22 is an UPPER BOUND. Dry powder is a SHARED POOL with the BTC and ETH reports at this timestamp; no tranche is sized against it. THIS REMAINS THE SMALLEST AND MOST TRACTABLE BASIS DEFECT IN THE ACCOUNT — 1 unbacked disposal against BTC's 5 and ETH's 24 — and it sits on the only real position. It should be fixed first."
  },
  "trend_residual": { "active_downtrend": false, "basis": "price is 4.86% below the 200dma so the MA half is technically satisfied — but the 200dma is RISING (+0.35%/20 sessions), price has just resolved a five-week $3,965-$4,165 range UPWARD on 99.4th-percentile volume, the 25-session low is $3,962.50 and holding, and no fresh lower low has printed. The cleanest 'not a downtrend' read of the three assets in this batch, and it STRENGTHENED this week.", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire regardless: mechanical 8 < 15." },
  "correlation": { "value_30d_vs_spx": 0.353, "window": "2026-06-24 to 2026-08-05", "method": "Pearson on daily log returns, 30 overlapping return pairs, Yahoo GC=F vs ^GSPC closes, computed 2026-08-05", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.353 < 0.80)", "note": "rose from 0.240 to 0.353 — still comfortably mild, but a reminder that today's gold rally and today's equity record share a driver (the oil collapse and its effect on inflation expectations)" },
  "discretion": {
    "d1_taken": true,
    "d1_value": -1.0,
    "d1_direction": "negative",
    "d1_first_nonzero_in_series": true,
    "d1_consecutive_reports_at_this_size": 1,
    "d1_factors": [
      "AN UPSIDE STRUCTURAL RESOLUTION THE RUBRIC CANNOT REGISTER. Volume at the 99.4th PERCENTILE of two years; a 152-point range at 2.62x the 5-day ADR of $58.08; a close near the session high; a five-week $3,965-$4,165 range resolved UPWARD; the gap to a RISING 200dma closed from -9.61% to -4.86% in three sessions. Corroborated on an independent instrument (PAXG +3.74% across four venues). NO leg and NO gate scores volume, range resolution, or breakout character — gate 7 is downside-only by construction, and the valuation band was saturated at 0 both before (-27.52%) and after (-23.73%) the move, so that leg is literally incapable of expressing it. This is NOT a re-weighting of the drawdown LEVEL the valuation leg scores; it is a different object — the STRUCTURE and PARTICIPATION of the move.",
      "A DATED MACRO TRANSMISSION WITH THE OPPOSITE SIGN TO THE CRYPTO BOOK. Brent -12.41% over five sessions on Hormuz reopening progress, DXY -1.06%, and market reporting explicitly attributing gold's move to the resulting trim in inflation expectations and Fed tightening odds (~59-63% for September). Gate 9 is a single boolean and no leg scores macro. CONSISTENCY NOTE, stated to preempt the obvious objection: the BTC report RETIRES its macro factor as weakened by exactly this evidence, and the ETH report EXCLUDES shared macro entirely. That is not a contradiction — the same oil collapse is a REDUCED NEGATIVE for crypto risk appetite and a POSITIVE FOR GOLD'S PRICE, and a positive for price is a NEGATIVE for an accumulation score. One macro event, scored once in each report, with the sign the transmission channel actually implies."
    ],
    "d1_falsifier": "Retire when EITHER (a) gold prints a WEEKLY CLOSE back below $4,100, re-entering the broken range and invalidating the resolution, OR (b) WGC/GLD August flows print another MULTI-REGION OUTFLOW MONTH of >=50 tonnes, confirming that the ETF-outflow regime the capitulation leg and gate 4 rest on survived the breakout. HARD REVIEW DATE 2026-08-19.",
    "d1_effect": "Adjusted score 8 -> 7. TWO real consequences, one cosmetic and one not. (1) Phase 1A's >=8 score line is LOST, reversing the first-ever clearing recorded on 2026-08-01 — cosmetic, since 1A is deployed and independently gate-blocked at 2/8 < 3. (2) THE D2 CONVICTION PATH ON PHASE 1A BECOMES UNAVAILABLE, because D2 requires the phase's score condition to be met and substitutes for a gate, never for a score. On 2026-08-01 that channel was available-but-moot; today it is closed. A genuine narrowing of optionality, taken deliberately.",
    "d1_governing_rule_check": "NOT HYPOTHETICAL THIS TIME. A -1.0 takes the ADJUSTED score to 7, and 7 IS <8 — the compound stop's score condition. If that stop read the adjusted number, this discretionary term would have degraded the book's own stop to price-only at $3,850. It reads the MECHANICAL score (8), so it does not. The 2026-08-01 report noted it had verified this interaction hypothetically; today a live -1.0 exists and the rule holds. Discretion buys entries and never touches exits — demonstrated, not asserted.",
    "d1_larger_considered_declined": "-1.5 / -2.0 DECLINED: the weekly RSI sits at the 8.67th PERCENTILE of two years and the 200dma is RISING — the higher timeframe is still washed out and structurally intact, which cuts hard the other way. One session is one session, and Analytical Principle 3's warning against promoting a single observation to structure applies in both directions.",
    "d1_positive_considered_declined": "+0.5 DECLINED on two grounds: it would be built on 'a rising 200dma with a washed-out weekly RSI is a constructive base, and record volume confirms real accumulation' — but the momentum leg ALREADY scores the weekly RSI at 2, so re-weighting it is prohibited double-counting; and a positive term would be arguing that gold is a BETTER accumulation candidate on the day it became 4% more expensive with its fear board moving away, which is the wrong direction for this framework.",
    "d2_available": false,
    "d2_taken": false,
    "d2_phase": "1A",
    "d2_detail": "WAS AVAILABLE on 2026-08-01, is UNAVAILABLE today. Phase 1A: the gate count is still short by EXACTLY ONE (2 of 3) and the [V] floor is still met (2>=2), but the SCORE CONDITION FAILS at adjusted 7 < 8. D2 substitutes for a gate, never for a score. Phase 1B: unavailable on the score too (7 < 11). A channel closed as a DIRECT CONSEQUENCE of the D1 term, and it is recorded as such rather than left as an unremarked absence.",
    "override_evaluated": true,
    "override_fired": false,
    "override_detail": "DOES NOT FIRE. Mechanical 8 < 15 — dispositive. Two further independent failures: sentiment is NOT FOUND so the extreme-band condition cannot be satisfied at all, and the price condition fails outright — spot is ABOVE the neighbourhood of the most recent tranche, not 8% below it. No near-fire, no veto or throttle reached.",
    "d4_taken": true,
    "d4_detail": "Cells set from the read against the 6-10 anchor row (adjusted score 7): Rally +6, Range +3, Retest -6, Bear -3 — all inside the +/-10 percentage-point band, none requiring a >10pp reason line. Targets RE-ANCHORED around the new spot, since the prior report's bands were built around $4,049. EV recomputed from the printed cells as the final step.",
    "low_vol_bandset": "WITHDRAWN 2026-07-18, NOT reinstated — and this report tested the condition rather than assuming it. Reinstatement requires gold's realized 30-day vol documented at <=1/2 of BTC's contemporaneous 30d realized vol: 25.11% / 29.35% = 0.86, nowhere near the <=0.50 bar. It would FAIL its own test, and it would change nothing anyway (the leg scores 0 on either band-set at -23.73%). Recorded so the absence is a decision, not an oversight.",
    "declined_action_zone_reanchor": "Re-anchoring the Phase 2 zone UPWARD was considered and DECLINED. Gold at $4,260.60 leaves the $3,700-3,950 zone 7.3-13.2% below spot and there is a superficial case for lifting it. Declined because raising the deepest NAMED floor would make the stop-vs-buy-zone coherence check EASIER — a back-door loosening of the test the catastrophic stop must clear. The zone stays where prior reports named it; the ladder is simply further away.",
    "declined_action_stop_tighten": "Tightening the stop into the breakout was considered and DECLINED. The D6 ratchet PERMITS movement toward price, but the $3,850 line marks a STRUCTURAL INVALIDATION of the base, not a trailing distance. No parameter moved.",
    "conservative_deviation_taken": "A low-confidence spot demotion was applied although NOT strictly mandated by the letter of the rule (the futures-vs-cash gap is an explainable carry basis, not genuine venue disagreement among synchronized quotes). Applied anyway because the EV sign flips across the gap and the canonical rests on frozen bar closes. Flagged as a deviation toward conservatism.",
    "non_mechanical_capital_pct": 0
  },
  "key_inputs": {
    "sentiment": "NOT FOUND — no reliably sourceable daily-resolution gold fear instrument in free sources; primary fallback scores the leg 2 and flags it. DSI and HGNSI are paywalled or non-daily; GVZ is a volatility index, not a fear gauge. COT is PROHIBITED as the sentiment LEG input (it already keys capitulation-(b) and gate 1; one input may not key two legs). GVZ/DSI/HGNSI are disclosed regime context only, never scored. Debt-clock branch DISCHARGED: a daily gold fear instrument cannot be obtained from free sources, so the leg will continue to score 2 until one becomes available. Structural limitation, not an outstanding task.",
    "weekly_rsi14": 38.98,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_boundary": "Yahoo weekly candles, week-start timestamps UTC; last COMPLETED weekly close = bar labelled 2026-07-27 (week ending Sunday 2026-08-02)",
    "weekly_rsi_confidence": "ok",
    "weekly_rsi_tool_artifact": "THE HEADLINE FINDING OF THIS REPORT, and it is about plumbing. Yahoo is emitting an EXTRA weekly bar for the live session; the GC=F weekly series ends 2026-07-20 ($4,067.60), 2026-07-27 ($4,049.10), 2026-08-03 ($4,095.40), 2026-08-05 ($4,269.10). tools/fetch.mjs drops only the FINAL bar, so its completed-closes set today INCLUDES the in-progress week beginning 2026-08-03 and returns 262 closes. Computed three ways: through 2026-07-27 (261 closes, the last genuinely completed week) = 38.98 -> band 2; through 2026-08-03 (262 closes, INCLUDES the in-progress week — what the tool returns) = 41.03 -> band 1; all bars including the live stub (263) = 48.08 -> band 0. The 261-close figure of 38.98 reproduces this series' 2026-08-01 print EXACTLY, as do the corresponding BTC (38.84) and ETH (41.96) figures in the companion reports — that is the confirmation 261 is the correct basis. WHAT THE ARTIFACT WOULD HAVE COST: momentum leg 2->1, mechanical score 8->7, and the compound stop's condition is score <8, so 7 IS <8 — the score axis would flip from unsatisfied to satisfied and A LIVE STOP ON THE ONLY REAL POSITION IN THIS ACCOUNT would degrade from full two-key protection to a bare price line at $3,850. The 2026-08-01 report predicted this failure mode and named the wrong culprit (it expected the decay point to come from a bullish ETF-flow print). CORRECTED VALUE USED: 38.98. Cross-asset note: the same artifact is present on BTC and ETH and is HARMLESS there — BTC 38.84 vs 39.73 both band to 2, ETH 41.96 vs 41.58 both band to 1 with the artifact's direction even REVERSED. Gold is the only asset in the batch where it bites, which is exactly why per-asset verification beats trusting a shared tool output. ACTION: fetch.mjs should exclude any weekly bar whose week has not closed, not merely drop the last bar.",
    "weekly_rsi_percentile_vs_2y": 8.67,
    "weekly_rsi_incl_live_week": 48.08,
    "daily_rsi14": 60.68,
    "sma_200w": 2855.66,
    "pct_vs_sma200w": 49.20,
    "gate6_within_8pct": false,
    "ma200d": 4478.46,
    "ma200d_slope20_pct": 0.35,
    "ma200d_rising": true,
    "pct_vs_ma200d": -4.86,
    "pct_vs_ma200d_prior_report": -9.61,
    "ma50d": 4176.87,
    "drawdown_pct": -23.73,
    "drawdown_pct_prior_report": -27.52,
    "drawdown_denominator_note": "measured against a 10-YEAR WINDOW high of $5,586.20 (2026-01-26), NOT a verified all-time high — the fetch tool flags this itself. Open item carried from prior reports; nothing turns on it today since the valuation leg scores 0 either way and gate 3 needs $2,793.10.",
    "valuation_bandset": "standard drawdown-from-ATH (low-vol adaptation withdrawn 2026-07-18, not reinstated, and it would FAIL its own vol-ratio test at 0.86 vs a <=0.50 bar); -23.73% -> band <30% -> 0. A -30% score-1 print needs $3,910.34; gate 3's >=50% needs $2,793.10",
    "valuation_leg_expressiveness_note": "The drawdown was -27.52% on 2026-08-01 and is -23.73% today. The band is saturated at 0 in BOTH cases — the valuation leg is STRUCTURALLY INCAPABLE of expressing what happened this week, which is directly relevant to the D1's first factor.",
    "session_move_pct": 4.03,
    "session_range_usd": 152.0,
    "session_range_x_adr": 2.62,
    "session_volume_contracts": 126062,
    "session_volume_percentile_vs_2y": 99.40,
    "prior_range": "3965-4165, held since late June; RESOLVED UPWARD 2026-08-05",
    "paxg_corroboration": "Kraken PAXG opened $4,065.00 and printed $4,217.19 on the session — +3.74% — against GC=F's +4.03% from $4,095.40. Two different instruments, two different venues, the same event. This is a real breakout, not a roll artifact.",
    "cot_reporting_date": "2026-07-28",
    "cot_no_new_print_note": "The CFTC publishes Tuesday positioning on the following Friday, so the 2026-08-04 positioning report releases Fri 2026-08-07 15:30 ET — AFTER this report. The 2026-07-28 print is unchanged from the prior report and is NOT re-dated or re-characterized (metric-history continuity rule).",
    "cot_net_long_contracts": 182070,
    "cot_wow_change_contracts": -1840,
    "cot_wow_change_pct": -1.00,
    "cot_long_leg_change": -5163,
    "cot_short_leg_change": -3323,
    "cot_futures_plus_options_change": -18835,
    "cot_managed_money_net_change": -5036,
    "cot_washout_verdict": "NO — the bar is a WoW non-commercial net-long decline of >=20-30K contracts OR >=15% of the net; the print is -1,840 (-1.00%), an order of magnitude short on both tests, and even the combined -18,835 falls below the 20K floor and well under 15%. Read the short leg, not the net: 5,163 longs left AND 3,323 shorts covered — two-sided deleveraging that nearly offset, not static positioning. Two consecutive sub-threshold prints; no washout regime claimed or backdated.",
    "wgc_july_flows_tonnes": -76.44,
    "wgc_july_flows_asof": "2026-07-24",
    "wgc_july_holdings_tonnes": 4044.83,
    "wgc_july_us_listed_usd_bn": -5.3,
    "wgc_june_flows_usd_bn": -8.9,
    "wgc_june_note": "ALL REGIONS negative; total AUM -13% to US$526bn; holdings -74t to 4,047t. H1 context: holdings +18t over the half, Asia's strongest first-half inflows on record.",
    "wgc_caveat": "A formal WGC monthly report covering FULL-July flows was not locatable as of this writing; the -76.44t figure is dated as of 2026-07-24 and is carried with that date attached rather than rounded up to 'July'.",
    "gate4_fragility_note": "Gate 4 is the ONLY lit fear gate and the SINGLE MOST FRAGILE INPUT in this report. A +4% breakout on 99.4th-percentile volume is exactly the condition under which physical ETF flows reverse, and a reversal takes capitulation-(c) to zero, the leg 1 -> 0, and the mechanical score 8 -> 7 — degrading the compound stop to price-only ON A BULLISH FLOW DATUM. That is the erosion the 2026-08-01 report predicted and it remains the live one.",
    "capitulation_a_note": "Session volume hit the 99.4th percentile of two years. The gold criterion (a) is a vol/volume FLUSH and the parent leg is titled CAPITULATION EVIDENCE — it measures forced selling. Today's volume arrived on a +4.03% UP day closing near its high, resolving a range upward. Crediting an upside participation event as capitulation evidence would mis-measure the leg's subject and would RAISE the score on the very day the accumulation case weakened. SCORED NO, volume percentile disclosed as context, and carried into the D1 instead — which is where an upside structural event belongs.",
    "adr5": 58.08,
    "adr5_sessions": "2026-07-29, 2026-07-30, 2026-07-31, 2026-08-03, 2026-08-04 — ALL FIVE full COMEX sessions, no holiday abbreviation; in-progress 2026-08-05 EXCLUDED as not a full session, lookback extended one session, exclusion disclosed",
    "adr5_prior_report": 46.52,
    "realized_2w_change_pct": 2.81,
    "realized_2w_basis": "vs 2026-07-22 close $4,146.90 (Yahoo GC=F)",
    "realized_2w_note": "A mildly NEGATIVE EV printed alongside a POSITIVE two-week move — disclosed as the rule requires, and it is the honest expression of the read: the move happened, and the framework does not expect it to compound from here at the same rate.",
    "rv30_pct": 25.11,
    "rv30_percentile_vs_2y": 66.14,
    "rv10_pct": 28.34,
    "rv90_pct": 24.90,
    "rv30_vs_btc_ratio": 0.86,
    "rv30_vs_btc_note": "gold 25.11% / BTC 29.35% = 0.86 — nowhere near the <=0.50 bar the low-vol band-set reinstatement would require",
    "low_40s": 3962.50,
    "low_40s_age_sessions": 25,
    "bounce_pct": 7.52,
    "tbill_3m_pct": 3.73,
    "tbill_3m_cross_check_dgs3mo_pct": 3.91,
    "real_yield_10y_tips_pct": 2.43,
    "real_yield_prior_report": 2.41,
    "dxy": 99.73,
    "dxy_5session_change_pct": -1.06,
    "brent": 79.48,
    "brent_5session_change_pct": -12.41,
    "brent_prior_report": 90.12,
    "vix": 16.95,
    "vix_5session_change_pct": -17.96,
    "spx": 7785.30,
    "spx_5session_change_pct": 6.41,
    "us10y_nominal_pct": 4.64,
    "net_liquidity_usd_t": 5.83,
    "hy_oas_pct": 2.78,
    "nfci": -0.529,
    "fed_funds_target": "3.50-3.75%, held 9-3 on 2026-07-29 with three hawkish dissents",
    "next_fomc": "2026-09-16",
    "sept_fomc_hike_probability_pct": "~59-63 (CME FedWatch via Reuters/Bloomberg coverage 2026-08-05; described in gold coverage as having been 'toned down'; range across sources disclosed rather than a single point estimate)",
    "hormuz_status": "STILL CLOSED as of 2026-08-05 — ~2 transits on 2026-08-02 against ~73/day normal, convoys under naval escort. US and regional officials 'zeroing in' on a deal 2026-08-04; an earlier MoU was followed by Iran re-closing the gateway. The oil move trades the DEAL, not the reopening — which cuts both ways for gold.",
    "trim_trigger_distances": "Valuation-extreme trim (drawdown inside 10% of the window high) needs $5,027.58, +18.0% above spot. Momentum/sentiment trim needs weekly RSI >70 against 38.98. Neither is close — the position is working, not overheating.",
    "tier1_next_5_sessions": ["Nonfarm payrolls (July Employment Situation) Fri 2026-08-07 08:30 ET — lands on the checkpoint day, 5h before the 13:30 ET COMEX close"],
    "tier1_window_verified": "compute.mjs tier1 --from 2026-08-05 --sessions 5 → window 2026-08-06..2026-08-12, returns exactly one tier-1 event (NFP 2026-08-07) and zero warnings. Report is NOT an incomplete-data report on the calendar dimension.",
    "tier1_beyond_window": ["CPI (July) Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14", "FOMC decision Wed 2026-09-16"],
    "scheduled_high_information_event": "CFTC COT report Fri 2026-08-07 15:30 ET (Tue 2026-08-04 positioning) — the single event most capable of changing the deployment answer via gate 1",
    "process_tripwire": "Gold above $4,468.96 (+4.89%) restores FR Channel A eligibility, stops the phase-of-cycle cap binding, makes Hard Rule 5's both->=12 check falsifiable again, and makes a standalone Flying Rocket report MANDATORY under vacuity trigger (iv).",
    "stale_input_debt": ["sentiment leg — NOT FOUND, carried at the fallback 2; 'why it cannot be obtained' branch discharged (structural, not an outstanding task)", "holder leg — carried from the prior report, not freshly verified this cycle; SECOND consecutive report", "drawdown denominator — 10y window high, not a verified ATH"]
  },
  "collar": {
    "band_triggered": true,
    "reasons": ["|EV-vs-spot| 0.67% < 2%", "mechanical score 8 is inside the 6-10 band"],
    "scorecard_limb_met": false,
    "scorecard": "8 bull / 10 bear — bear by 2, NOT within 1 of balanced, so that limb is not met; the other two are",
    "effect": "no directional regime resolution claimed anywhere in the report; every forward statement carries a probability or an IF->THEN plus a named falsifier"
  },
  "verdict": "HOLD 1.3294 PAXG (last-confirmed); deployment FROZEN; 75% dry at a 3.73% T-bill carry. Mechanical 8/20 — FOURTH consecutive report at 8, no leg moved. D1 = -1.0, the FIRST NON-ZERO DISCRETIONARY TERM IN THE GOLD SERIES; adjusted 7/20, which LOSES the Phase 1A score line first cleared on 2026-08-01 and CLOSES the D2 conviction path with it. THE MARKET EVENT: gold resolved a five-week $3,965-$4,165 range UPWARD — +4.03% to $4,260.60, a 152-point bar at 2.62x the 5-day ADR, volume at the 99.4th PERCENTILE OF TWO YEARS, close near the high, corroborated independently on tokenized gold (Kraken PAXG $4,065 -> $4,217, +3.74%). The driver is legible: Brent -12.41% on Hormuz progress trimmed inflation expectations and with them expected Fed tightening, DXY -1.06%. Bullion rallying on the same headline that put the S&P at a record is a RATE-EXPECTATIONS bid, not a fear bid. For a fear-accumulation framework that is not good news: three of the four dark reachable gates moved FURTHER away because all three require weakness, Phase 2's $3,700-3,950 zone is now 7.3-13.2% below spot, and gate 4 — the one lit fear gate — is exactly the input a breakout reverses. THE HEADLINE, THOUGH, IS PLUMBING. Yahoo began emitting an extra live weekly bar, so fetch.mjs folded an IN-PROGRESS week into its completed-closes set and returned weekly RSI 41.03 instead of the correct 38.98 — momentum leg 2->1, mechanical 8->7, and the compound stop's condition is score <8. Seven IS below eight. A live stop on the ONLY REAL POSITION IN THIS ACCOUNT would have degraded from two-key protection to a bare $3,850 price line, and nothing in the market would have caused it. Caught by cross-checking against this series' own prior prints (261 closes reproduces 38.98 on gold, 38.84 on BTC, 41.96 on ETH — exactly). The 2026-08-01 report predicted this failure mode and named the wrong culprit. THE STOP SURVIVED TWO THREATS TODAY FOR TWO DIFFERENT REASONS: the artifact was caught by verification, and the D1 was neutralized by the governing rule — a -1.0 takes the ADJUSTED score to 7, which WOULD satisfy the axis if the stop read that number, and it reads the MECHANICAL 8 instead. The 2026-08-01 report verified that interaction hypothetically; today it was live and it held. Gold consequently holds the only compound stop in this batch with FULL two-key protection while both crypto assets sit price-gated. Remaining risk unchanged: one point of MECHANICAL decay satisfies the axis, and the point most at risk is capitulation-(c), meaning the stop erodes on a BULLISH flow datum. SPOT HANDLING: fewer than 3 synchronized COMEX quotes obtainable (n_synchronized:0, both futures sources frozen bar closes); GC=F held as canonical for series continuity since the 10y high, drawdown, 200-week SMA and 200dma are all computed on it; the +1.03% futures-vs-cash gap (vs +0.17% on Jul-31) is COMEX CARRY on a deferred contract, not venue disagreement; EV computed at both ends (-0.67% GC=F / +0.38% cash) and THE SIGN FLIPS, so a low-confidence CORROBORATIVE-ONLY demotion was applied as a CONSERVATIVE DEVIATION though not strictly mandated. POSITION (Hard Rule 8): EXIT 1 / EXPIRED at 94.2h, degraded from STALE 26h. Cold start stated explicitly, but prior reports confirm a real holding so it is carried forward rather than read as flat. 1.3294 PAXG last-confirmed, custody RECONCILED, zero withdrawals; basis.reliable FALSE on ONE unbacked disposal so no cost, PnL, ROI or MTM is quoted — and note today's rally would have made the WITHDRAWN -10.50% MTM look considerably better, which is exactly the temptation the rule refuses. THE SMALLEST BASIS DEFECT IN THE ACCOUNT (1 vs BTC's 5 and ETH's 24), on the only real position — fix it first. FR COMPANION: STAND DOWN, no channel, 1/20 with a ceiling of 6, cross-validation 'structurally consistent (cap-bound; both->=12 unfalsifiable by construction)' — never a bare check. ROUTING TRIPWIRE NOW ONLY +4.89% AWAY ($4,468.96, was +10.4%): above it the cap stops binding and a standalone FR report becomes MANDATORY. Collar ACTIVE (|EV| 0.67% < 2%; mechanical 8 in the 6-10 band): no directional regime resolution claimed.",
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "gold_fallen_knives_20260805_1008.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "GOLD",
      "report_date": "2026-08-05",
      "report_local_time": "10:08",
      "report_zone": "America/New_York",
      "instrument_class": "non_crypto_derivative",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-GOLD-20260805-1008",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-GOLD-20260805-1008",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-GOLD-20260805-1008",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-GOLD-20260805-1008",
          "decision": "STAND_DOWN",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        }
      ]
    },
    "instrument_class": "non_crypto_derivative",
    "report_file": "gold_fallen_knives_20260805_1008.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "GOLD",
    "report_date": "2026-08-05",
    "report_local_time": "10:08",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-GOLD-20260805-1008",
      "FK-P1B-GOLD-20260805-1008",
      "FK-P2-GOLD-20260805-1008",
      "FK-P3-GOLD-20260805-1008"
    ],
    "status": "REGISTERED"
  }
}
```
