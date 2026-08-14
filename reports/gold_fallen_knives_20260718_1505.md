# 🔪 FALLEN KNIVES ANALYTICS — GOLD — July 18, 2026

## SATURDAY AFTERNOON (FUTURES CLOSED) — OWED VOL DEBT RESOLVED → SCORE 10 → 8 — CHECKPOINT FIRED — ALL DATA LIVE INTERNET-VERIFIED

### Report Generated: Saturday, July 18, 2026, ~3:05 PM EDT

### Asset: GOLD (XAU / GC=F) | Prior Score: 10/20 (Jul 14) | Current Score: **8/20**

> **Framework note.** Adapted Fallen Knives for gold: MVRV/funding/crypto-ETF inputs substituted with CFTC COT positioning, physical gold-ETF flows, real yields + DXY, central-bank buying. Compound-stop score line runs **<8** (set 2026-06-17, ratified 2026-07-10).
>
> **Two decisive events since Jul-14, both resolving OPEN debts:**
> 1. **The owed realized-30d-vol computation is delivered — and it withdraws the low-vol valuation substitution.** Computed from price series: gold 30d realized vol **28.0% annualized vs BTC 32.3%** (ratio **0.87**; **1.05×** on a raw daily-std basis). Gold is running at ~BTC-like vol right now — decisively **NOT ≤½ BTC**. Per the Jul-14 report's own pre-committed resolution ("if it confirms gold ≥ ½ BTC, the low-vol substitution is withdrawn and this leg re-scores"), the substitution is **WITHDRAWN**. Valuation re-scores on the standard drawdown-from-ATH bands: **−28.17% → <30% band → 0** (was 2). **Score 10 → 8.** This is the conservative direction (lower score = less buy eligibility) and moves no operative decision (8 is still the 6–10 band; compound-stop score-line <8 still safe; deployment frozen either way).
> 2. **The Jul-17 checkpoint FIRED.** Gold weekly-closed **below the $4,050 line** (Friday ~$3,995–4,012, week **−3.2%**). Per the pre-committed mechanic, the catastrophic/held-position stop **re-drops $3,900 → $3,800** (logged in the Migration Ledger).
>
> **And the COT did NOT flush** — the Jul-17 print bled −7,564 (−3.9%) to 186,682, a *first* decline off the plateau but far short of the ≥20–30K / ≥15% washout bar. The 13th straight report with the fear/value gates dark because the speculative book still refuses to capitulate. Companion FR ≈2/20 cap-bound — no standalone FR trigger.

---

## 2. Verified Live Data Points — GOLD

### Price (canonical reconciliation — WEEKEND, futures closed, all quotes FROZEN at Fri Jul-17 close)

Gold futures do not trade Saturday. Every quote below is **frozen/stale, anchored to report-publication time (~3:05 PM EDT Sat Jul-18)**, age ≈ since Friday's ~5 PM ET settle.

| Source | Price | Timestamp | Label |
|---|---|---|---|
| GC=F front-month (Yahoo/`fetch.mjs`) | **$4,012.70** | Jul 17 close | frozen — canonical anchor |
| MGC=F (Yahoo) | $4,018.80 | Jul 17 close | frozen — futures basis |
| XAU spot (Reuters/FXStreet cluster) | ~$3,995–4,011 | Jul 17 | frozen — spot, slight negative basis to GC=F |
| **Canonical (GC=F close)** | **~$4,012** | Jul 17, anchored to report time | — |

Cluster spread GC=F $4,012.7 vs XAU low-side ~$3,995 = **~0.44%** < 0.5% → no low-confidence demotion; the spread is spot-vs-futures basis + intraday-vs-close timing on a frozen weekend tape, **not** genuine simultaneous venue disagreement. **Week −3.2%** (biggest weekly loss since early June); Friday touched the lowest level since Jul-1. **Whichever quote you take, the Friday weekly close was below $4,050 → checkpoint fired unambiguously.**

### Sentiment

| Source | Reading | Status |
|---|---|---|
| Gold daily fear instrument | **NOT FOUND** (no reliably-sourced daily gold-native fear gauge; DSI/HGNSI paywalled/non-daily) | scored conservatively at **2**, flagged (unchanged) |
| GVZ (CBOE gold vol) | ~25 (elevated-not-extreme; 52-wk 14.47–48.68) | **disclosed regime context only — never the scored input** |
| CFTC COT | see below — **PROHIBITED as sentiment input** (already keys the capitulation leg) | — |

### Positioning — CFTC COT (the Jul-17 print — gate-1 decider)

| Metric | Value | Source | Timestamp |
|---|---|---|---|
| Non-commercial net-long | **186,682 contracts** (Long 227,310 / Short 40,628) | Tradingster COT Legacy (088691) / CFTC.gov | **Jul 17 release, data as of Tue Jul 14** |
| WoW change | **−7,564 contracts** (Long −6,403, Short +1,161) = **−3.9% of the net** | Tradingster | Jul 17 |
| Prior series | Jul 10 → 194,246 · Jul 6 → 194,000 · Jun 26 → 181,300 | Investing.com | Jul 18 |
| Next print | **Fri Jul 24, 3:30 PM ET** (data as of Jul 21) | CFTC schedule | — |

> **The first real decline off the plateau — but NOT a flush.** −7,564 (−3.9%) is well short of the gate-1 washout bar (a WoW net-long decline **≥20–30K contracts or ≥15% of the net**). The spec book is *beginning* to bleed, but the durable-low signal — a genuine capitulation flush — still has **not** printed for a 13th straight report. Watch item, not a gate change.

### Physical Gold-ETF Flows

| Window | Net Flow | Source | Timestamp |
|---|---|---|---|
| June 2026 (global, WGC) | **net OUTFLOW ~−74t** (multi-region, worst of the cycle) | World Gold Council | Jun data |
| GLD (SPDR) trailing month | **~−$3.69B (−2.6% of AUM)** (partly fee-migration to GLDM/IAU) | CryptoBriefing / SSGA | late-Jun/mid-Jul |
| GLD since Mar 1 | ~−$14.4B cumulative | CryptoBriefing | Jul 2026 |
| July tonnage | **NOT FOUND** (WGC July not yet posted) — carried, debt-flagged | WGC | — |

### Central-Bank Buying (holder leg)

| Metric | Value | Source | Timestamp |
|---|---|---|---|
| PBoC June purchase | **+14.93 tonnes** (biggest single-month since 2023) | Kitco | Jul 7 |
| Consecutive streak | **20th straight month** (began Nov 2024) | WGC / SCMP | Jul 2026 |
| July print | not yet released (~early August) | — | — |

### Macro (`tools/fetch.mjs macro`, Jul 16–17)

| Series | Level | Δ | Source |
|---|---|---|---|
| Real 10y (TIPS/DFII10) | **2.35%** (at/near cycle high) | +0.04/5 prints | FRED DFII10, Jul 16 |
| 10y nominal (^TNX) | 4.54% | −0.61%/5d | Yahoo, Jul 17 |
| DXY | 100.75 | −0.22%/5d | Yahoo, Jul 17 |
| VIX | 18.77 | +24.88%/5d | Yahoo, Jul 17 |
| Brent | **$88.10** | **+15.91%/5d** (Hormuz escalation) | Yahoo BZ=F, Jul 17 |
| S&P 500 | 7,457.69 | −1.55%/5d | Yahoo, Jul 17 |

### Inflation prints (RESOLVED since Jul-14 — both SOFT)

- **June CPI (Jul 14):** headline **3.5% YoY** (vs ~3.9% cons.), core **2.6% YoY** (vs ~2.9%), headline **−0.4% MoM** (sharpest since Apr-2020, energy −5.7%), core MoM flat. **Soft/dovish miss.** *(CNBC/BLS)*
- **June PPI (Jul 15):** final demand **−0.3% MoM** (unexpected decline), YoY +5.5%; gasoline −12%. **Soft.** *(CNBC/BLS)*
- **Yet gold FELL.** The soft prints "largely ruled out a July hike" — but the oil-driven inflation impulse + hawkish-Fed chorus (Logan called for a hike; Jefferson open) kept real yields at a cycle high, and a non-yielding metal sells into that regardless of a soft *backward-looking* CPI.

### Bank Targets

Goldman **$4,900 YE** ($4,400 in a hike scenario) · JPMorgan **$4,300 Q3 / $4,500 Q4** (cut Jul-3) · UBS reportedly **raised to ~$5,200 / 12m** (diverges from the prior $3,850–4,000 note — FLAGGED, verify). CB buying ~60t/month cited as the structural floor by all three. *(GoldSilver roundup / JPM Research)*

### Correlation Regime

30-day gold–SPX correlation **not computed this cycle** → risk-on surcharge defaults OFF. Gold trading as a real-yield asset; the relevant cross-asset is the TIPS curve, not SPX.

---

## 3. Critical Developments — GOLD

- **The owed vol debt is settled and it re-scores the book.** Computed 30-day realized vol (log-return stdev, price series): **gold 28.0% ann. vs BTC 32.3% (0.87×)** — gold is NOT the low-vol asset the substitution presumes; BTC has gone quiet (range-bound ~$60–65K) while gold whipsawed on the parabola unwind + oil shocks. Low-vol substitution **withdrawn**; valuation re-scored to **0** on standard drawdown bands. Score **10 → 8**.
- **Checkpoint fired.** Friday's weekly close (~$3,995–4,012) is below the $4,050 line → held-position stop **$3,900 → $3,800** (pre-committed mechanic; Migration Ledger below).
- **COT first-decline, not flush.** 194,246 → 186,682 (−3.9%). The book is richer than at any point in this correction and merely *beginning* to bleed — still not the washout that marks durable gold lows.
- **Soft CPI + PPI, gold down anyway.** The cleanest possible demonstration that this is a real-yield/oil regime, not an inflation-hedge bid.
- **Oil escalation intact.** Brent $88.10 (+15.9%/5d), 6th night of US strikes, Hormuz transit −52–62%, 20% cargo toll. Hostile-to-gold via rates, not supportive via haven.
- **Structural bid record-pace.** PBoC +14.93t June (20th straight month), buying the worst quarterly decline in 13 years.

---

## 4. Fallen Knives Composite Score (GOLD) — 8 / 20

| Category | Max | Score | Rubric Basis |
|---|---|---|---|
| **Sentiment Extreme** | 5 | **2** | No reliably-sourced gold-native DAILY fear instrument → **NOT FOUND default = 2**, flagged. GVZ context only; COT prohibited as sentiment input. Unchanged. |
| **Momentum Exhaustion** | 4 | **2** | Weekly Wilder RSI-14 = **37.22** (261 completed weekly closes, Yahoo GC=F, last completed week Jul-13; live-week 37.48) → **≤40 band → 2**. Up from 36.86; ~2.2 pts above the ≤35 edge. |
| **Valuation** | 5 | **0** | **Low-vol substitution WITHDRAWN this report** (realized vol 0.87× BTC, not ≤½ — owed computation delivered). Standard drawdown-from-ATH bands now apply: **−28.17%** ($4,012 vs $5,586 Jan-2026 parabolic high) → **<30% band → 0**. Metric: drawdown-from-ATH; band-set: standard alt. See adjudication below. |
| **Capitulation Evidence** | 3 | **1** | 1/3: (a) vol/volume flush ❌ (week −3.2% but no confirmed top-decile/>3σ single-day event); (b) **positioning washout ❌ — COT −7,564 = −3.9%, a first decline but NOT a flush**; (c) **ETF-flow capitulation ✅ — June −74t, GLD −2.6% AUM trailing month.** |
| **Holder Behavior** | 3 | **3** | Central-bank structural bid intact and STRENGTHENING: PBoC +14.93t June (20th straight), buying the decline. |
| **TOTAL** | **20** | **8** | Raw 8 → **adjusted 8** (half-up; no surcharge). **Score 10 → 8 on the vol-debt resolution.** |

**Valuation adjudication — low-vol substitution WITHDRAWN (honest resolution of the Jul-14 owed item).** The Jul-14 report carried the low-vol drawdown substitution as FLAGGED/debt-clocked and owed "a clean realized-30d-vol computation (gold vs BTC from price series); if it confirms gold ≥ ½ BTC, the low-vol substitution is withdrawn and this leg re-scores." **Delivered:** 30 daily log-returns each, gold GC=F vs BTC-USD → gold daily-return stdev **1.766%** (ann. 252 = **28.0%**), BTC **1.689%** (ann. 365 = **32.3%**), **ratio 0.87 annualized / 1.05 on raw daily std**. Both are decisively **> ½**. The premise of the low-vol adaptation ("realized 30d vol ≤ ½ BTC") is **false in the current regime**, so the substitution is withdrawn. The rubric's fallback for an asset without reliable MVRV is **standard drawdown-from-ATH**: −28.17% from the $5,586 Jan-2026 parabolic high → **<30% band → 0**. *(Basis note: the tool flags $5,586 as a "10y weekly high"; in this cycle it is the parabolic all-time high — gold never traded above it — so the standard bands apply cleanly, with the caveat disclosed.)* **Operative-decision impact: NONE** — 8 vs 10 both sit in the 6–10 "PREPARE" band; compound-stop score-line is <8 so score 8 remains safe (with reduced margin — see §6); deployment frozen (needs ≥15) either way; no trim (peak ~12 → 8 = −4 < 6). The debt is now CLOSED, not carried.

#### Confirmation Gates (2 / 8 — gate 5 N/A, denominator 8)

| # | Gate | Bucket | Status |
|---|---|---|---|
| 1 | COT positioning washout (replaces daily-sentiment streak for gold) | [V] | ❌ (−7,564 / −3.9%, a first decline but no flush) · *relight: a WoW net-long decline ≥20–30K or ≥15% on the Jul-24 print* |
| 2 | Weekly RSI <30 | [V] | ❌ (37.22) · *relight: RSI <30, ~7.2 pts away* |
| 3 | Valuation cheap (**standard**: ≥50% drawdown from ATH) | [V] | ❌ (−28.17% < 50%) · *relight: none-in-regime (needs gold ~$2,793, a large structural decline)* — **now a standard measurable ❌**, no longer "none-by-construction" (the low-vol band-set that made it un-creditable is withdrawn this report) |
| 4 | ETF/flow capitulation | [V] | **✅** — June −74t all-region outflow, GLD −2.6% AUM |
| 5 | Hash Ribbon | [T] | **N/A → denominator 8** |
| 6 | Price within ±8% of long-horizon mean | [T] | ❌ (spot +42.31% above the $2,819.64 200-week, post-parabola) · *relight: none-in-regime (large, slow structural mean-reversion)* |
| 7 | Capitulation volume spike | [V] | ❌ (week −3.2% not a confirmed >3σ/top-decile flush) · *relight: a >3σ volume/liquidation flush* |
| 8 | Holder/CB-buying stabilizing | [V] | ✅ (record-pace dip-buying) |
| 9 | Macro catalyst neutral-to-positive | [T] | **❌** — real yields ~2.35% cycle high, oil +15.9%/5d, Sept hike ~50%, Warsh hawkish · *NOTE: June CPI/PPI came soft (a genuine positive on the inflation axis), so this gate is LESS hostile than Jul-14, but net remains negative for a real-yield asset — gold fell on the soft prints* · *relight: oil de-escalation AND real yields rolling off ~2.35% AND soft data sustained* |

**Count: 2 ✅ (gates 4 + 8 — both [V]).** Unchanged from Jul-14 in count. The binding constraint holds for a 13th report: the fear/value gates are dark because the speculative book never capitulated, and today's price is a real-yield move, not a positioning flush.

**Companion Flying Rocket (computed, Hard Rule 5):** FR composite ≈ **2/20, 0 unlock gates** — euphoria 0 (stress tape, not euphoria), momentum 0 (RSI 37.22), valuation-extreme 0 (down 28%, not overvalued), distribution 1 (ETF-outflow regime), structural 1 (rich COT book as latent supply); phase-of-cycle hard cap (>20% below 1-yr high → cap 8) **binding.** Per the vacuity rule: **cross-validation structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).** FR 2 < 9 → no watch tripwire; no standalone FR report triggered.

---

## 5. Probability Matrix — Derived From Score (Adjusted 8 → 6–10 band)

Baseline 6–10: Rally 20 / Range 35 / Retest 30 / Bear 15. **Trend residual (live, downtrend side):** gold sits below the broken $4,050 checkpoint (now FIRED), made a fresh weekly low, into a hostile rates backdrop → shift ~4 pp Rally→Retest + ~1 pp Range→Bear. Canonical $4,012.

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | **16%** | $4,150 – $4,450 | $4,300 | Real yields roll off ~2.35%; oil de-escalates; gold reclaims $4,050+ and the haven channel finally outbids the rates channel |
| **Range** | **35%** | $3,950 – $4,150 | $4,050 | Consolidates around the breached checkpoint while data resolves |
| **Retest** | **33%** | $3,800 – $3,950 | $3,875 | Real yields extend; the rich 186K book finally unwinds; presses the held-position stop |
| **Bear** | **16%** | $3,500 – $3,800 | $3,650 | Hike path re-hardens (Sept confirmed); forced spec liquidation through $3,800 |

**Sum 100% ✅. Weighted EV = $3,968.25** (`tools/compute.mjs ev`; components 688 + 1,417.5 + 1,278.75 + 584). Spot ~$4,012 → **EV-vs-spot = −1.09%.** **Realized trailing-2-week: ≈ −0.3%** ($4,025 Jul-14 → $4,012, via the $3,944 low). EV modestly below a roughly-flat tape — the same statement the COT makes: **price sits above the mass of the distribution while the positioning that marks a durable low has never printed.**

Rally 16% < modal Range 35% and ≤50% ✅ (post-adjustment). **EV-floor consistency:** binds only at score ≥15 → N/A. **Terminal-vs-extreme reconciliation:** modal = **Range** ($3,950–4,150); **Retest** ($3,800–3,950) is a near-tie 2 pts behind, and the §5 residual is live on the Retest/Bear side → the path EXTREME points DOWN toward the **$3,800–3,950 Retest band** (the more-bearish adjacent band, per the near-tie rule). Range expresses the terminal expectation; it does **not** claim the $3,944 low is in.

---

## 6. Deployment Strategy — GOLD

**Confirmed deployed: 25% (1A ~$4,650 + 1B ~$4,475, blended $4,545, MTM −11.7% at $4,012). Dry powder: 75%.** Splits 10 / 15 / 30 / 45.

**⚑ Deep-Value Override: NOT ARMED.** Score 8 << 15; extreme-fear condition also fails (book rich; no flush). No firing, no near-fire.

| Phase | Size | Trigger / Gates (denominator 8) | Status |
|---|---|---|---|
| **1A** | 10% | — | **DEPLOYED ~$4,650** |
| **1B** | 15% | — | **DEPLOYED ~$4,475** |
| **2 — Conviction** | 30% | score ≥15 + ≥6/8 gates (≥3 [V]) + regime | **FROZEN** (score 8; 2/8 gates) |
| **3 — Generational** | 45% | score ≥17 + ≥7/8 gates + CB-bid confirmation | **DRY POWDER** |

**The thirteen-report refusal to add stands, and the vol-debt re-score reinforces it.** Dropping valuation to 0 says exactly what the COT says: gold is neither cheap by any standard drawdown measure nor washed out in positioning. The prospective **Phase-2 ladder stays $3,700–$3,950, score-gated** — price alone reaching it is insufficient; only a positioning washout (a COT flush) produces the ≥15 that deploys it.

### Stop Philosophy — GOLD (MIGRATION this report — checkpoint fired)

- **Compound thesis stop: ≥2 consecutive weekly closes below ~$3,850 AND score back below 8.** Unchanged. *Cannot fire on price alone.* **Heightened-margin disclosure:** the vol-debt re-score moved the composite to **8**, one notch above the **<8** score condition (was 10, a 2-notch margin). The line is **not loosened** (loosening the score condition would weaken the stop — barred by the agility mandate); the reduced margin is a real, disclosed consequence of the honest re-score. The stop is now effectively *tighter*, which is the safe direction.
- **CATASTROPHIC / held-position stop: $3,900 → $3,800** (MIGRATED — see Ledger). Governs the held 25%.
- **Stop Migration Ledger (Jul 2026):**
  - **Catastrophic/held-position tier: $3,900 → $3,800.** Direction: **away from price (looser).** Rationale: **pre-committed checkpoint mechanic** published Jul-14 ("weekly close < $4,050 → held-position stop re-drops $3,900 → ~$3,800") — the Jul-17 weekly close (~$3,995–4,012) fired it; this is execution of a previously-published conditional, not a fresh discretionary loosening. The wider stop avoids ejecting the held position at the very $3,800–3,944 retest the framework's Phase-2 zone wants to buy.
  - No other stop parameter changed value (compound price ~$3,850 and score-line <8 UNCHANGED; prospective P2 re-stop $3,650 UNCHANGED).
- **Stop-vs-buy-zone coherence check (tested against the deepest named ladder floor = prospective Phase-2 $3,700):** CATASTROPHIC **$3,800** strictly below **$3,700**? **NO → held-state FAIL** (expected: a frozen position with a deeper prospective ladder). **Post-activation:** atomic sequence — **re-set stop to $3,650 BEFORE the first Phase-2 fill** → $3,650 < $3,700 → **PASS.** Deployment is frozen (no imminent fill), so the report publishes; **"stop realignment owed" does NOT apply** because the post-activation state passes and no new deployment is authorized. Max-drawdown-to-thesis-stop from the $4,545 blend: at $3,800 = **−16.4%**; at the ~$3,850 compound line = **−15.3%**.
- **Checkpoint prognosis (mechanical form):** **Next checkpoint Friday, July 24, 2026** (weekday-verified vs CFTC/CME calendar — normal full COMEX/Globex session; weekly settle ~5:00 PM ET): **fires iff ≥2 consecutive weekly closes < $3,850 AND score < 8.** Currently **0** closes below $3,850 (Friday ~$3,995–4,012 is *above* $3,850). Spot $4,012 is **+$162 above $3,850 = +2.24× the 5-day ADR** ($72.28, mean of |high−low| over the 5 FULL sessions Jul-13/14/15/16/17, Yahoo GC=F — no abbreviated session in-window). **Next tier-1 US release before Jul-24: none top-tier** (jobless claims Jul-23 is secondary; PCE ~Jul-25/30 and FOMC Jul-28/29 fall *after*). Distance >2 ADR and no unpriced tier-1 in-window → **no likelihood adjective licensed**; the checkpoint is not in reach next week.
- **Time stop:** reassess the 25% if the thesis hasn't worked through Q3 2026.
- **Dry powder yield benchmark:** ~4.3% (3-month T-bill) / ~4.5% (USDC). 75% dry earns the benchmark while positioning refuses to capitulate.

---

## 7. Exit / Trim Framework — GOLD

Track cost basis per phase; trims LIFO. Local campaign peak = highest adjusted score since first fill (~12); now 8 → drop of **−4**, short of the ≥6 trim trigger. *(The −2 from this report's re-score is a **labeled measurement correction** — the low-vol withdrawal — not signal deterioration; per the exit-table carve-out it does not count toward the ≥6 drop. Even counting it raw, −4 < 6.)*

| Trigger | Status |
|---|---|
| Score drops ≥6 from local peak | **No** (peak ~12 → 8 = −4; and −2 of that is a labeled measurement correction) |
| F&G ≥75 7d AND weekly RSI >70 | **No** (RSI 37.22) |
| Valuation extreme (drawdown <10% + vertical) | **No** (down 28%) |
| Score ≤3 AND price ≥40% above cost | **No** (score 8; MTM −11.7%) |
| ETF outflows ≥3% AUM after a sustained-inflow regime | **No** (no prior ≥5-session inflow regime this campaign) |
| Narrative break (confirmed CB-*selling* regime) | **No** — the opposite is in force (record dip-buying) |

**Status: no trim. Remaining position 25%.**

---

## 8. Critical Watchlist — GOLD

| Time (ET) | Event | Impact |
|---|---|---|
| Thu Jul 23, 8:30 | Initial jobless claims (secondary) | Labor read; minor |
| **Fri Jul 24, 3:30** | **CFTC COT** (data as of Jul 21) — 2nd print since the first decline | **Gate-1 decider:** a ≥20–30K net-long decline = the flush that begins to light the [V] gates |
| **Fri Jul 24, ~5:00** | **Weekly close vs the $3,850 compound line** | 1st of the 2 closes that (with score <8) could fire the compound stop — currently far above |
| Fri Jul 25 / ~30 | June PCE | Fed's preferred gauge; real-yield path |
| Jul 28–29 | FOMC decision (Jul 29, 2:00 PM) — July hike largely ruled out; Sept ~50% | The real rate-path event |

**Tier-1 calendar lock:** in-window (Jul 18–24) tier-1 US releases = **none top-tier** (jobless claims Jul-23 secondary). PCE (~Jul 25/30) and FOMC (Jul 28–29) fall just outside. This is a complete-data report; no unpriced tier-1 sits before the Jul-24 checkpoint.

---

## 9. Bull vs Bear Scorecard — GOLD

**Bull (structural):**
1. ✅ PBoC +14.93t June, 20th straight month — record-pace CB bid buying the dip
2. ✅ Speculative book NOT washed out (186K, only −3.9% off the plateau) — the flush that marks lows still ahead, so the down-leg is not "used up"
3. ✅ Soft June CPI/PPI — the inflation impulse (backward-looking) is cooling
4. ✅ Bank target floor holding (Goldman $4,900 YE; JPM's cut $4,300 Q3 a soft landing; UBS reportedly $5,200)

**Bear (cyclical/rates):**
1. ❌ Real yields ~2.35% at cycle high — the dominant headwind for a non-yielding asset
2. ❌ Oil +15.9%/5d revives the inflation/hike path via *forward* July prices
3. ❌ Below the broken $4,050 checkpoint (now FIRED); fresh weekly low; week −3.2%
4. ❌ ETF outflow regime (June −74t worst of cycle; GLD −2.6% AUM)
5. ❌ Valuation not cheap by any standard measure (down only 28% from a parabolic top; vol ≈ BTC)

**Net: 4 bull / 5 bear — bearish-leaning, cyclical-over-structural.** → **Verdict-Confidence Collar engaged** (scorecard within 1 of balanced AND |EV| < 2% AND score in 6–10): no regime-resolution claims permitted.

---

## 10. Change Log — GOLD (vs Jul 14, 2026)

| Factor | Previous (Jul 14) | Current (Jul 18) | Direction |
|---|---|---|---|
| Canonical spot | ~$4,025 (XAU) / $4,033 GC=F | ~$4,012 (GC=F close) | ↓ ~0.3% (week −3.2%) |
| **Adjusted score** | 10 | **8** | ↓ 2 (valuation 2→0, low-vol withdrawn) |
| **Valuation leg** | 2 (low-vol drawdown, flagged/debt) | **0 (standard drawdown; low-vol WITHDRAWN)** | ↓↓ debt resolved |
| **Vol basis** | flagged ~0.6–0.7× BTC, unresolved | **RESOLVED: 0.87× ann / 1.05× daily-std — NOT ≤½; substitution withdrawn** | ✅ debt closed |
| Weekly RSI-14 | 36.86 (wk Jul-6) | **37.22** (wk Jul-13; live 37.48) | ↑ (same ≤40 band) |
| Drawdown | −27.95% | −28.17% | ~flat (now scored via standard bands) |
| COT net-long | 194,246 (plateau) | **186,682 (−7,564 / −3.9%)** | ↓ first decline, NOT a flush |
| CPI/PPI | pending (cons. ~3.9%/2.9%) | **actual SOFT: CPI 3.5%/2.6%, PPI −0.3% MoM** | ✅ dovish (gold fell anyway) |
| July hike odds | ~43% | **largely ruled out; Sept ~50%** | ↓ (dovish shift on rates) |
| Checkpoint ($4,050) | −0.40× ADR below, pending | **FIRED (weekly close < $4,050)** | ⚑ stop migrated |
| Stops | $3,900 cat · $3,850/<8 comp | **$3,800 cat (migrated)** · $3,850/<8 comp | ⚑ catastrophic looser (pre-committed) |
| Brent | $86.61 | $88.10 (+15.9%/5d) | ↑ hostile |
| PBoC | +14.93t / 20th mo | +14.93t / 20th mo | → UNCHANGED |
| Companion FR | 2/20 cap-bound | 2/20 cap-bound | → |

---

## 11. Strategic Verdict — GOLD

**Adjusted score 8/20 · Weighted EV $3,968.25 · EV-vs-spot −1.09% · sentiment NOT-FOUND-default · stance: HOLD 25%, 75% dry, deployment FROZEN.**

Two open debts closed this report, and both cut the same way. First, I owed you a real volatility computation, and it does not support the story the series had been telling: gold's realized 30-day volatility is running at 28% annualized against Bitcoin's 32% — 0.87 times, or slightly *above* Bitcoin on a raw daily-range basis. Gold is not a low-volatility asset right now; Bitcoin has simply gone to sleep in a five-thousand-dollar range while gold has been thrown around by a parabola unwind and an oil war. So the low-volatility valuation bands come off, the standard drawdown bands go on, and a 28% decline from a parabolic top scores as what it is — not cheap. Valuation drops to zero and the composite to eight. That is the honest number, it is the conservative direction, and it changes no action: eight and ten sit in the same "prepare" band, the stop is safe, and I was not going to add at ten either.

Second, the Friday checkpoint fired. Gold closed the week below $4,050 — its worst week since early June — so the held-position stop steps down to $3,800 exactly as it was pre-committed to do, giving the 25% room to survive the $3,800–3,944 retest that the framework's own Phase-2 zone is waiting to buy. And the one signal that would actually change the call still refuses to appear: the speculative book finally *began* to bleed, down under four percent to 186,000 net-long, but that is a first crack, not the flush that marks durable gold lows. The tell of the week is that inflation came in soft — CPI and PPI both undershot — and gold fell anyway. That is a real-yield regime, full stop; a non-yielding metal cannot rally on a dovish backward-looking print while forward oil prices push real yields to a cycle high.

The discipline is unchanged: I do not add on a rates-driven price move that is not a positioning flush, and I do not sell a 25% position held above a $3,800 stop into a record central-bank bid. But I will name the new risk honestly — the re-score put the composite one notch above the compound stop's score condition, so the margin that protects this position has thinned, and I will not loosen the score line to fatten it back up. Cash is a position, it earns ~4.3%, and it is still paid to wait for the flush.

**Action items:**
1. **HOLD 25%** (blend $4,545, MTM −11.7%). **No add, no trim.** Deployment FROZEN (score 8 << 15).
2. **Note the thinned stop margin:** composite 8 sits one notch above the <8 compound score condition. Line NOT loosened. If a second leg deteriorates AND gold prints 2 weekly closes < $3,850, the compound stop arms — watch both axes.
3. **Watch the Jul-24 COT (3:30 ET) above all** — a ≥20–30K net-long decline (a real second-week acceleration off this week's −7,564) is the first evidence the flush is starting.
4. **Catastrophic stop now $3,800** (migrated, pre-committed). Compound $3,850/score<8 unchanged. Prospective Phase-2 ladder $3,700–3,950 requires the atomic re-stop to $3,650 before any fill.
5. **Vol debt CLOSED** — re-confirm the realized-vol ratio next report only if BTC vol regime-shifts materially (a return to gold ≤ ½ BTC would re-license the substitution).

> **The Pattern:**
> **IF** the Jul-24 COT prints a ≥20–30K net-long decline **AND** RSI breaks <35 **→ THEN** the flush this series has waited thirteen reports for is beginning; re-rate the [V] gates and the Phase-2 arming.
> **IF** real yields extend above ~2.35% **AND** gold prints a 2nd weekly close < $3,850 with score <8 **→ THEN** the compound thesis stop arms on the held 25% — the thinned-margin risk made explicit.
> **IF** oil de-escalates **AND** real yields roll off ~2.35% **AND** gold reclaims $4,050+ **→ THEN** the haven channel finally outbids the rates channel — but that requires a realized trend-structure repair, not a bounce.

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-GOLD-20260718-1505 | UNVERIFIED | non_crypto_derivative |
| 1B | FK-P1B-GOLD-20260718-1505 | UNVERIFIED | non_crypto_derivative |
| 2 | FK-P2-GOLD-20260718-1505 | LOCKED | non_crypto_derivative |
| 3 | FK-P3-GOLD-20260718-1505 | LOCKED | non_crypto_derivative |

Registry schema: report-phase-registry/1; version: 1; origin: gold_fallen_knives_20260718_1505.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "GOLD",
  "date": "2026-07-18",
  "spot": { "value": 4012.00, "source": "GC=F front-month Yahoo/fetch.mjs Jul-17 close $4,012.70 (frozen weekend, anchored to report time); XAU spot cluster ~$3,995-4,011; spread ~0.44% <0.5% (basis+timing, not venue disagreement)" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 0, "capitulation": 1, "holder": 3 },
    "raw": 8, "adjusted": 8, "rounding": "half-up"
  },
  "gates": { "active": 8, "na": [5], "passed": [4, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 16, "low": 4150, "high": 4450 },
      { "name": "Range", "p": 35, "low": 3950, "high": 4150 },
      { "name": "Retest", "p": 33, "low": 3800, "high": 3950 },
      { "name": "Bear", "p": 16, "low": 3500, "high": 3800 }
    ],
    "stated_ev": 3968.25, "vs_spot_pct": -1.09
  },
  "deployment": {
    "deployed_pct": 25, "dry_pct": 75,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~4650" },
      { "phase": "1B", "pct": 15, "entry": "~4475 (blended 25% @ ~4545, MTM -11.7%)" },
      { "phase": "2", "pct": 30, "entry": "3700-3950 prospective (frozen, score-gated; re-stop 3650 first)" },
      { "phase": "3", "pct": 45, "entry": "dry" }
    ]
  },
  "stops": {
    "catastrophic": 3800,
    "held_position_stop": 3800,
    "prospective_p2_floor": 3700,
    "prospective_p2_restop": 3650,
    "compound": { "price": 3850, "score_line": 8 },
    "note": "deepest_zone_floor omitted — deployment FROZEN, no active buy zone. Coherence tested in prose vs deepest NAMED prospective ladder floor $3,700: held-state $3,800 < $3,700 FAILS (expected for frozen position w/ deeper prospective ladder); post-activation atomic re-stop $3,650 < $3,700 PASSES; no new deployment authorized so no realignment owed.",
    "migration": "catastrophic/held-position tier $3,900 -> $3,800, direction away-from-price (looser), rationale: pre-committed Jul-14 checkpoint mechanic fired (Jul-17 weekly close ~$3,995-4,012 < $4,050). Compound price $3,850 and score-line <8 UNCHANGED (score-line NOT loosened despite score->8 thinning the margin -- agility mandate bars weakening stops).",
    "checkpoint": { "date": "2026-07-24", "line": 3850, "condition": ">=2 weekly closes <3850 AND score<8", "closes_below": 0, "adr": 72.28, "dist_x_adr": 2.24, "side": "above line, out of reach; no top-tier tier-1 before Jul-24 (jobless claims Jul-23 secondary)" }
  },
  "verdict": "HOLD 25% (blend $4,545, MTM -11.7% at $4,012); 75% dry; deployment FROZEN (score 8<<15). SCORE 10->8: the owed realized-30d-vol computation is delivered and WITHDRAWS the low-vol valuation substitution -- gold 30d realized vol 28.0% ann vs BTC 32.3% (ratio 0.87; 1.05x on raw daily-std), decisively NOT <=1/2 BTC, so per the Jul-14 pre-committed resolution the substitution is withdrawn and valuation re-scores on standard drawdown-from-ATH bands: -28.17% <30% -> 0 (was low-vol band 2). Decision-neutral (8 vs 10 both 6-10 band; compound score-line <8 still safe though margin thinned to 1 notch; deployment frozen; no trim). Other legs: sentiment 2 (NOT FOUND default), momentum 2 (RSI 37.22, wk Jul-13, live 37.48, ~2.2pts above <=35 edge), capitulation 1 (leg c ETF outflow only; COT washout ❌ at -7,564/-3.9% a first-decline-not-flush; no >3sigma vol flush), holder 3 (PBoC +14.93t June, 20th straight). CHECKPOINT FIRED: Friday weekly close (~$3,995-4,012) < $4,050 -> catastrophic/held stop MIGRATED $3,900->$3,800 (pre-committed mechanic, direction looser, avoids ejecting at the $3,800-3,944 retest P2 wants to buy). Compound $3,850/score<8 UNCHANGED (NOT loosened). Coherence: held-state $3,800 not < prospective P2 floor $3,700 = FAIL (expected, frozen); post-activation re-stop $3,650 < $3,700 PASS; no new deployment so no realignment owed. June CPI 3.5%/2.6% + PPI -0.3% MoM both SOFT yet gold fell -3.2% on the week -- a real-yield/oil regime (real yields 2.35% cycle high, Brent $88.10 +15.9%/5d, Sept hike ~50%, Warsh hawkish). Gates 2/8 [4,8] both [V]; gate 3 now a standard measurable ❌ (28%<50%) rather than none-by-construction (low-vol band-set withdrawn). Companion FR ~2/20 cap-bound (-28.17% below 1y high), structurally consistent (both>=12 unfalsifiable); FR 2<9, no standalone FR. Scorecard 4-5 + |EV|<2% + score 6-10 -> Collar engaged. Local peak ~12->8=-4 (of which -2 is a labeled measurement correction) < 6 -> no trim.",
  "inputs": {
    "weekly_rsi": 37.22, "weekly_rsi_live_week": 37.48, "rsi_closes": 261, "rsi_source": "tools/fetch.mjs Wilder-14, Yahoo GC=F weekly, last completed week 2026-07-13",
    "valuation_metric": "drawdown_from_ath_standard", "valuation_bandset": "standard alt (low-vol WITHDRAWN)", "drawdown_pct": -28.17, "ath": 5586.20, "ath_note": "Jan-2026 parabolic high; tool labels '10y weekly high' but it is the cycle ATH -- standard bands apply, caveat disclosed",
    "vol_computation": "gold GC=F daily-return stdev 1.766% (ann252 28.0%) vs BTC-USD 1.689% (ann365 32.3%); ratio 0.87 annualized / 1.05 raw-daily-std; both > 1/2 -> low-vol substitution WITHDRAWN, debt CLOSED",
    "cot_net_long": 186682, "cot_wow": -7564, "cot_wow_pct": -3.9, "cot_flush": false, "cot_flush_bar": ">=20-30K or >=15%", "cot_prior": 194246, "cot_next": "2026-07-24 3:30pm ET (data as of Jul 21)",
    "cpi_june": "3.5% headline / 2.6% core YoY, -0.4% MoM (soft miss)", "ppi_june": "-0.3% MoM final demand (soft)",
    "sma_200w": 2819.64, "sma_200w_vs_spot_pct": 42.31,
    "real_yield_10y_tips": 2.35, "july_hike_odds": "largely ruled out post-soft-CPI", "sept_hike_odds": "~50%", "dxy": 100.75, "vix": 18.77, "us10y_nominal": 4.54, "oil_brent": 88.10, "gvz": "~25 (context only)",
    "pboc_june_tonnes": 14.93, "pboc_streak_months": 20, "etf_june_tonnes": -74, "gld_trailing_month": "-$3.69B / -2.6% AUM", "gld_since_mar1": "-$14.4B", "goldman_ye": 4900, "jpm_q3": 4300, "ubs_note": "reportedly raised ~$5,200/12m (FLAGGED, diverges from prior $3,850-4,000)",
    "iran": "6th night of US strikes; Hormuz transit -52-62%; 20% cargo toll; Brent $88.10 +15.9%/5d",
    "adr5": 72.28, "companion_fr": { "composite": 2, "gates": 0, "cap_bound": true, "standalone_report_triggered": false }
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "gold_fallen_knives_20260718_1505.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "GOLD",
      "report_date": "2026-07-18",
      "report_local_time": "15:05",
      "report_zone": "America/New_York",
      "instrument_class": "non_crypto_derivative",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-GOLD-20260718-1505",
          "decision": "UNVERIFIED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260718_1505.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-18",
          "report_local_time": "15:05"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-GOLD-20260718-1505",
          "decision": "UNVERIFIED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260718_1505.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-18",
          "report_local_time": "15:05"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-GOLD-20260718-1505",
          "decision": "LOCKED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260718_1505.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-18",
          "report_local_time": "15:05"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-GOLD-20260718-1505",
          "decision": "LOCKED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260718_1505.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-18",
          "report_local_time": "15:05"
        }
      ]
    },
    "instrument_class": "non_crypto_derivative",
    "report_file": "gold_fallen_knives_20260718_1505.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "GOLD",
    "report_date": "2026-07-18",
    "report_local_time": "15:05",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-GOLD-20260718-1505",
      "FK-P1B-GOLD-20260718-1505",
      "FK-P2-GOLD-20260718-1505",
      "FK-P3-GOLD-20260718-1505"
    ],
    "status": "REGISTERED"
  }
}
```
