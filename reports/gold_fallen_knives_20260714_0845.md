# 🔪 FALLEN KNIVES ANALYTICS — GOLD — July 14, 2026
## TUESDAY, CPI MORNING — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Tuesday, July 14, 2026, 8:45 AM EDT
### Asset: GOLD (XAU / GC=F) | Prior Score: 10/20 (Jul 13) | Current Score: 10/20

> **Framework note.** Adapted Fallen Knives for gold: MVRV/funding/crypto-ETF inputs substituted with CFTC COT positioning, physical gold-ETF flows, real yields + DXY, central-bank buying, cross-asset confirmation. Compound-stop score line runs **<8** (set 2026-06-17, ratified in the 2026-07-10 audit — structurally-pinned-score asset). **Vol-basis correction this report:** prior reports asserted gold realized 30-day vol "well under ½ of BTC's" — fresh data does **not** support that in the current regime (gold ≈ 0.6–0.7× BTC on realized); the low-vol valuation substitution is carried as a FLAGGED, debt-clocked read this report (§4 valuation leg, §10). **CPI-day report:** June CPI released 8:30 ET this morning; the actual print had **not** propagated to any source at our 8:26–8:31 ET data fetch — every CPI-conditional claim below is held event-conditional (consensus ~3.9% headline / ~2.9% core).

---

## 2. Verified Live Data Points — GOLD

### Price (canonical reconciliation)
Canonical XAU spot = **median of the Jul-14 synchronized cluster = ~$4,025**; COMEX front-month GC=F ~$4,033 (normal futures/spot basis). Tuesday is a modest green bounce (+0.6%) off Monday's −2.6% reopen break — still **below** the $4,050 checkpoint line.

| Source | Price | Timestamp | 24h Δ |
|---|---|---|---|
| Kitco (bid/ask) | $4,027.80 / $4,029.80 | Jul 14 live | +$27.70 / +0.69% |
| Trading Economics | $4,027.15 | Jul 14 | +$27.15 / +0.63% |
| IFCM | $4,022.17 | Jul 14 | — |
| Investing.com (XAU) | $4,018.49 | Jul 14 (prev close $4,001.13) | +$17.36 |
| **Canonical XAU (median of 4)** | **~$4,025** | Jul 14 ~08:30 ET | +~0.27% vs Jul-13 $4,014 |
| GC=F front-month (Yahoo/`fetch.mjs`) | $4,032.8 (MGC=F $4,034.1) | Jul 14 12:26 UTC | — |
| TradingView (intraday low tick) | $3,996.55 | Jul 14 | — (frozen/low tick, excluded) |

Cluster spread among synchronized XAU quotes ($4,018–4,028) = **0.24%** < 0.5% → no low-confidence demotion; the GC=F $4,033 print is a futures-basis premium, not venue disagreement. Today's XAU range $3,985.76–$4,034.20.

### Sentiment
| Source | Reading | Status |
|---|---|---|
| Gold daily fear instrument | **NOT FOUND** (no reliably-sourced daily gold-native fear gauge; DSI/HGNSI paywalled/non-daily) | scored conservatively at **2**, flagged |
| GVZ (CBOE gold vol) | 25.52 (−1.85%; 52-wk range 14.47–48.68) — Jul 14 | **disclosed regime context only — never the scored input** |
| CFTC COT | see below — **PROHIBITED as sentiment input** (already keys the capitulation leg; one input may not key two legs) | — |

### Positioning — CFTC COT (Commitment of Traders)
| Metric | Value | Source | Timestamp |
|---|---|---|---|
| Non-commercial net-long | **194,246 contracts** (UNCHANGED — plateau, NO flush) | Investing.com CFTC / CFTC.gov | Jul 10 release (data as of Tue Jul 7) |
| Recent series | Jul 10 → 194.2K · Jul 6 → 194.0K · Jun 26 → 181.3K · Jun 22 → 180.2K | Investing.com | Jul 14 |
| Next real print | **Fri Jul 17, 3:30 pm ET** (covers Tue Jul 14 positions) | CFTC schedule | — |

> The "Jul 17 = 194.2K" row visible on Investing.com is a **carried-forward PLACEHOLDER, not new data.** The positioning flush that marks durable gold lows still has **not** printed — the spec book sits at its richest of the correction and merely plateaued.

### Physical Gold-ETF Flows
| Window | Net Flow | Source | Timestamp |
|---|---|---|---|
| June 2026 (global, WGC) | **net OUTFLOW ~−74t** (multi-region, worst of the cycle; ~$3.6B Asian/China selling late-May–June) | World Gold Council | Jun data, WGC file dated Jul 13 |
| GLD (SPDR) July daily/weekly | **NOT FOUND** (MacroMicro 403; WGC weekly mid-July not yet posted) — prior "5-day ~−$1.03B" not refreshable; **carried, debt-flagged** | State Street / WGC | — |
| 2026 YTD context | Jan was a record +$18.7B inflow month; the outflow regime is a Q2/summer development | WGC | Jul 2026 |

### Central-Bank Buying (holder leg)
| Metric | Value | Source | Timestamp |
|---|---|---|---|
| PBoC June purchase | **+14.93 tonnes** (biggest single-month since 2023) | Kitco | Jul 7 |
| Consecutive-month streak | **20th straight month** (began Nov 2024 — longest since 2015) | WGC / IndexBox | Jul 2026 |
| Other buyers (June) | Uzbekistan, Poland added | IndexBox | Jul 2026 |

### Macro
| Asset | Level | Δ | Source | Timestamp |
|---|---|---|---|---|
| Real 10y (TIPS/DFII10) | **2.32%** (at/near cycle high) | ~flat vs prior 2.33% | FRED DFII10 (`fetch.mjs`) | Jul 10 print |
| 10y nominal (^TNX) | 4.61% | +2.9% / 5 sessions | Yahoo (`fetch.mjs`) | Jul 13 |
| DXY | 101.05–101.15 | +0.20% (firm) | Yahoo / Investing | Jul 14 |
| VIX | 17.52 | +8.62% / 5 sessions | Yahoo (`fetch.mjs`) | Jul 14 |
| Brent | **$86.61** | **+16.79% / 5 sessions** (Trump 20% Hormuz toll + Iranian-port blockade) | Yahoo (`fetch.mjs`) | Jul 14 |
| S&P 500 | 7,515.34 (Mon close, −0.79%); ~7,575 intraday Tue (+0.42%) | — | Yahoo | Jul 13–14 |

### Bank Targets
Goldman **$4,900** YE (cut from $5,400 on Jun 19 — unchanged) · JPMorgan **$4,300 Q3 / $4,500 Q4** (slashed Jul 3, Q3 target now BELOW spot) · UBS **$3,850–$4,000** near-term · Wells Fargo $6,100–6,300 / BofA $6,000 (outlier highs). Source: goldsilver.com roundup / J.P. Morgan Research, Jul 2026.

### Correlation Regime
30-day gold–SPX correlation **not computed this cycle** → risk-on surcharge defaults OFF (never penalize on a guess). Gold trading as a real-yield asset, not an equity beta — the relevant cross-asset is the TIPS curve, not SPX.

---

## 3. Critical Developments — GOLD

- **Oil escalation hardened, not eased.** The weekend Iran strikes have become a sustained supply shock: Trump announced **20% shipping tolls on all Hormuz cargo + reinstated a blockade of Iranian ports** (3rd consecutive strike-round), lifting Brent to **~$86** (+~17%/5 sessions) — its highest since mid-June ([Al Jazeera Jul 14](https://www.aljazeera.com/economy/2026/7/14/oil-hits-1-month-high-as-us-iran-fighting-clouds-strait-of-hormuz-outlook), [CNBC Jul 14](https://www.cnbc.com/2026/07/14/oil-prices-today-brent-wti-hormuz-trump-toll-iran.html)). Hormuz is **not formally closed** but running at a two-month traffic low with "dark crossings" dominating; a disputed 60-day toll-free interim deal is in force ([US News/Reuters Jul 13](https://www.usnews.com/news/top-news/articles/2026-07-13/hormuz-traffic-slows-to-two-month-low-as-renewed-us-iran-strikes-raise-safety-risk)).
- **This is hostile to gold, via the rates channel.** Monday's −2.9% break to ~$4,012 was the cleanest possible demonstration ([Kitco AM Jul 13](https://www.kitco.com/news/article/2026-07-13/gold-and-silver-slide-hormuz-oil-shock-lifts-yields-kitco-am-report)): a war headline **sold** gold because it arrived as an *oil* shock → inflation impulse → real yields to a fresh cycle high (~2.32%) → a non-yielding metal sells into rising real rates. The rates channel outranks the haven channel at this positioning.
- **CPI TODAY (8:30 ET) + hawkish Fed.** June CPI releases this morning; consensus headline **~3.9%** (down from 4.2%, driven by a ~10% June gasoline drop from the earlier Hormuz reopening), **core ~2.9%** sticky ([IndexBox](https://www.indexbox.io/blog/june-cpi-report-preview-inflation-data-release-on-july-14-2026/), [Kiplinger](https://www.kiplinger.com/investing/economy/june-cpi-preview-dont-let-a-negative-headline-fool-you)). Kiplinger's warning is apt: a soft *headline* on falling June gasoline could mask a sticky core against a *July* oil spike. Gov. Waller tied the next move to this week's core CPI; swaps price **~43% odds of a 25bp HIKE at the Jul 28–29 FOMC**, Sept ≥1-hike **~70%** (CME). New Chair **Warsh testifies today (House) + Jul 15 (Senate)** — inaugural semiannual, framed hawkish ("returning inflation to 2% is the defining priority") ([CryptoBriefing](https://cryptobriefing.com/fed-chair-warsh-testifies-monetary-policy-july/)).
- **Positioning still refuses to capitulate.** COT flat at 194,246 — the durable-low signal (a net-long flush) has not printed for a 12th straight report.
- **Structural bid intact and record-pace.** PBoC +14.93t in June (20th straight month), buying gold's worst quarterly decline in 13 years.

---

## 4. Fallen Knives Composite Score (GOLD) — 10 / 20

| Category | Max | Score | Rubric Basis |
|---|---|---|---|
| **Sentiment Extreme** | 5 | **2** | No reliably-sourced gold-native DAILY fear instrument → **NOT FOUND default = 2**, flagged (per rubric; GVZ 25.52 is disclosed context only, COT prohibited as sentiment input). |
| **Momentum Exhaustion** | 4 | **2** | Weekly Wilder RSI-14 = **36.86** (262 completed weekly closes, Yahoo GC=F, last completed week Jul-6; live-week 38.32) → **≤40 band → 2**. Fell from 39.51 (Jul-13) — same band, now closer to the ≤35 edge (a break <35 lifts this leg to 3). |
| **Valuation** | 5 | **2** | Low-vol drawdown band-set: **−27.95%** ($4,025 vs $5,586 10y-high) → **≥20% band → 2** (deep-value 3 requires a confirmed COT flush — none). **FLAGGED / debt-clocked** — see vol-basis adjudication below. |
| **Capitulation Evidence** | 3 | **1** | 1/3: (a) vol/volume flush ❌ (Monday −2.6% sharp but not a confirmed top-decile/>3σ event; GVZ 25.5 elevated-not-extreme); (b) **positioning washout ❌ — COT plateau 194,246, no decline**; (c) **ETF-flow capitulation ✅ — June −74t (worst of cycle), GLD outflow regime.** |
| **Holder Behavior** | 3 | **3** | Central-bank structural bid intact and STRENGTHENING: PBoC +14.93t June (biggest since 2023, 20th straight), buying the decline. |
| **TOTAL** | **20** | **10** | Raw 10 → **adjusted 10** (half-up convention; no surcharge). **12th straight report at 10.** |

**Vol-basis adjudication (honest correction).** Fresh data does **not** support the prior series' "gold realized 30-day vol runs well under ½ of BTC's." Current reads: gold 30d realized ~**20–30%** annualized (GVZ implied **25.52**) vs BTC ~**30–50%** → gold ≈ **0.6–0.7× BTC** on realized (on ADR terms, gold $63.3 = 1.57% vs BTC ~$1,480 = 2.35% ≈ 0.67×), **not** a clean ≤½. The ≤½ relationship clears **only on implied vol** (GVZ 25.5 vs BTC IV ~40–55%). The SKILL licenses the low-vol substitution "ONLY when realized 30d vol ≤ ½ BTC" — now borderline-to-failing on realized. **Resolution — hold at 2, flagged, debt-clocked:** (i) the standard alt drawdown-from-ATH fallback is *itself invalid* for gold — its $5,586 basis is a 10-yr weekly high explicitly NOT a confirmed all-time high, so those bands (which presuppose a real ATH) cannot be cleanly applied either; (ii) the choice changes **no** gate/stop/deployment decision (score 8 vs 10 both sit in the 6–10 band; both ≥8 so the compound stop is untouched; deployment frozen either way); (iii) per the stale-input debt clock, when a scored input's basis is in question and no clean replacement exists, HOLD the leg at prior value and flag it. **Owed next report:** a clean realized-30d-vol computation (gold vs BTC from price series); if it confirms gold ≥ ½ BTC, the low-vol substitution is withdrawn and this leg re-scores.

#### Confirmation Gates (2 / 8 — gate 5 N/A, denominator 8)

| # | Gate | Bucket | Status |
|---|---|---|---|
| 1 | COT positioning washout (replaces daily-sentiment streak for gold) | [V] | ❌ (plateau 194,246, no decline) · *relight: a WoW non-commercial net-long decline ≥20–30K or ≥15% of the net on the Jul-17 print* |
| 2 | Weekly RSI <30 | [V] | ❌ (36.86) · *relight: RSI <30, ~6.9 pts away* |
| 3 | Valuation cheap (deep drawdown vs mean) | [V] | ⚠️ **none-by-construction** (−27.95%; the low-vol band-set confers no gate credit — stays in the /8 denominator deliberately) |
| 4 | ETF/flow capitulation | [V] | **✅** — June −74t all-region outflow, worst of the cycle; GLD outflow regime |
| 5 | Hash Ribbon | [T] | **N/A → denominator 8** |
| 6 | Price within ±8% of long-horizon mean | [T] | ❌ (spot +42.75% above the $2,819.56 200-week, post-parabola) · *relight: mean-reversion toward the rising long-horizon average — none-in-regime (large, slow structural change)* |
| 7 | Capitulation volume spike | [V] | ❌ (Monday −2.6% not a confirmed >3σ/top-decile flush) · *relight: a >3σ volume/liquidation flush* |
| 8 | Holder/CB-buying stabilizing | [V] | ✅ (record-pace dip-buying) |
| 9 | Macro catalyst neutral-to-positive | [T] | **❌** — real yields ~2.32% cycle high, oil spiking, July hike ~43% / Sept ~70%, CPI today, Warsh hawkish · *relight: a soft Jul-14 CPI re-collapsing hike odds AND real yields rolling off ~2.32% AND oil de-escalating* |

**Count: 2 ✅ (gates 4 + 8 — both [V]).** The binding constraint is unchanged for a 12th report: **the fear/value gates are dark because the speculative book never capitulated.** Today's price is a real-yield move, not a positioning flush; macro cannot light these gates and positioning still refuses to.

**Companion Flying Rocket (computed, Hard Rule 5):** FR composite ≈ **2/20, 0 unlock gates** — euphoria 0 (GVZ-stress tape, not euphoria), momentum 0 (RSI 36.86), valuation-extreme 0 (down 28%, not overvalued), distribution 1 (ETF-outflow regime), structural 1 (rich COT book as latent supply); phase-of-cycle hard cap (>20% below 1-yr high → cap 8) **binding.** Per the vacuity rule: **cross-validation structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).** FR 2 < 9 → no watch tripwire, no standalone FR report triggered.

---

## 5. Probability Matrix — Derived From Score (Adjusted 10 → 6–10 band)

Baseline 6–10: Rally 20 / Range 35 / Retest 30 / Bear 15. **Trend residual (live, downtrend side):** gold sits below the broken $4,050 checkpoint, making lower highs off the $4,200 Jul-6 pop, into a hostile rates backdrop → shift ~3% Rally→Retest + ~1% Range→Bear. Tuesday's +0.6% bounce trims ~1% back toward Rally/Range vs Jul-13. Canonical $4,025.

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|
| **Rally** | **17%** | $4,150 – $4,450 (mid $4,300) | Soft Jul-14 CPI re-collapses hike odds; real yields roll off ~2.32%; gold reclaims $4,050+ and the haven channel finally outbids the rates channel |
| **Range** | **34%** | $3,950 – $4,150 (mid $4,050) | Consolidates around the breached checkpoint line while CPI/PPI resolve |
| **Retest** | **33%** | $3,800 – $3,950 (mid $3,875) | Hot CPI core; real yields extend; the rich 194K book finally unwinds; the Jul-17 checkpoint fires |
| **Bear** | **16%** | $3,500 – $3,800 (mid $3,650) | Hike path fully re-hardens (Sept confirmed); forced spec liquidation through $3,944 |

Sum 100%. **Weighted EV = $3,970.75** (`tools/compute.mjs ev`). Spot ~$4,025 → **EV-vs-spot ≈ −1.35%.** **Realized trailing-2-week: ≈ +0.5%** (Jun-30 ~$4,006 → Jul-14 ~$4,025, via the $4,200 pop and the $3,944 low — roughly flat). EV is modestly *below* the roughly-flat tape: the negative EV states the same thing the COT does — **price sits above the mass of the distribution while the positioning that usually marks a durable low has never printed.**

**Sum-check (mandatory):** 17+34+33+16 = 100 ✅; EV recomputed from printed cells = **$3,970.75**, matches (Δ 0%) ✅. Rally 17% < modal Range 34% and ≤50% ✅.
**EV-floor consistency:** binds only at score ≥15 + extreme fear → **N/A** (score 10). Clean.
**Terminal-vs-extreme reconciliation:** modal = **Range** ($3,950–4,150); **Retest** ($3,800–3,950) is a near-tie 1 pt behind → the §5 residual is live on the Retest/Bear side, so the path EXTREME points DOWN toward the **$3,800–3,950 Retest band** (the more-bearish adjacent band, per the near-tie rule). Range expresses the terminal expectation; it does not claim the $3,944 low is in. Pre-CPI: this matrix resolves conditional on June CPI (Tue Jul-14 08:30 ET) and PPI (Wed Jul-15).

---

## 6. Deployment Strategy — GOLD

**Confirmed deployed: 25% (1A ~$4,650 + 1B ~$4,475, blended $4,545, MTM −11.4% at $4,025). Dry powder: 75%.** Splits 10 / 15 / 30 / 45.

**⚑ Deep-Value Override status: NOT ARMED.** Score 10 << 15; the extreme-fear condition also fails (book rich; GVZ is stress, not extreme fear). No firing, no near-fire.

| Phase | Size | Trigger / Gates (denominator 8) | Status |
|---|---|---|---|
| **1A** | 10% | — | **DEPLOYED ~$4,650** |
| **1B** | 15% | — | **DEPLOYED ~$4,475** |
| **2 — Conviction** | 30% | score ≥15 + ≥6/8 gates (≥3 [V]) + regime | **FROZEN** (score 10; 2/8 gates) |
| **3 — Generational** | 45% | score ≥17 + ≥7/8 gates + CB-bid confirmation | **DRY POWDER** |

**The twelve-report refusal to add still stands, and the escalation does not change it.** A price move driven by real yields — not a positioning flush — is exactly the Retest fuel this series has warned about: a rich 194K book above a support shelf, testing that shelf on a rates move rather than being cleared out. The prospective **Phase-2 ladder stays $3,700–$3,950, score-gated** — price alone reaching it is insufficient; only a positioning washout (a COT decline) or genuinely deep valuation *with* a flush produces the ≥15 that deploys it.

**Stop Philosophy.** Compound thesis stop: **≥2 consecutive weekly closes below ~$3,850 AND score back below 8.** Time stop: reassess the 25% if the thesis hasn't worked through Q3 2026. Narrative-break exit independent (= confirmed CB-*selling* regime; the opposite is in force — record dip-buying).

**Stop Migration Ledger:** **no migration this report** — all stop parameters UNCHANGED (compound line ~$3,850/score<8; catastrophic/held-position $3,900; checkpoint date Jul-17 vs $4,050; prospective P2 re-stop $3,650). The checkpoint's *distance* narrowed from −0.54× (Jul-13) to −0.40× ADR on Tuesday's bounce, but no stop *parameter* changed — a market move is not a stop change.

**Checkpoint prognosis (mechanical form):** **Checkpoint Friday, July 17, 2026** (weekday-verified vs the CFTC/CME calendar — normal full COMEX/Globex session, no holiday; weekly settle ~5:00 pm ET): **fires iff the weekly close < $4,050** (→ held-position stop re-drops $3,900 → ~$3,800). Spot **$4,025 is $25 = 0.62% BELOW the line = 0.40× the 5-day average daily range** ($63.30, mean of |high−low| over the 5 FULL sessions Jul-8/9/10/13/14, Yahoo GC=F — no abbreviated session in-window). **Next tier-1 releases before the checkpoint: June CPI Tue Jul-14 (dominant, releasing now), PPI Wed Jul-15, Retail Sales Thu Jul-16 — expected direction of effect: hot CPI core → real yields extend → gold stays below the line and the checkpoint fires (stop → $3,800); soft CPI → gold reclaims $4,050+ and the checkpoint holds (stop stays $3,900).** **No likelihood adjective is licensed:** three unpriced tier-1 releases sit between report and checkpoint, and the distance is under half an average day's range — genuinely two-sided and CPI-conditional, from below the line.

**Stop-vs-buy-zone coherence check (mandatory):** No buy zone is active (deployment frozen) → the **$3,900 catastrophic stop governs the held 25% only**; spot $4,025 sits **+3.2% above it**. The prospective Phase-2 ladder $3,700–$3,950 sits *below* $3,900 → on any activation, **re-set the stop to ~$3,650 first** (strictly below the $3,700 floor), atomic sequence: re-stop to $3,650 BEFORE the first Phase-2 fill. **For the held position: PASS** ($3,900 < $4,025). Post-activation (prospective): $3,650 < $3,700 → PASS. Max-drawdown-to-thesis-stop from the $4,545 blend: at $3,900 = **−14.2%**; at the ~$3,850 compound line = **−15.3%**.

**Dry powder yield benchmark:** ~4.3% (3-month T-bill) / ~4.5% (USDC). 75% dry earns the benchmark while positioning refuses to capitulate — cash is a position, and it is currently paid to wait.

---

## 7. Exit / Trim Framework — GOLD

Track cost basis per phase; trims LIFO. Local campaign peak = the highest adjusted score since first fill (peak was ~12 earlier in the campaign; now 10 → drop of ~2, well short of the ≥6 trim trigger).

| Trigger | Status |
|---|---|
| Score drops ≥6 from local peak | **No** (peak ~12 → 10 = −2) |
| F&G ≥75 7d AND weekly RSI >70 | **No** (RSI 36.86) |
| Valuation extreme (MVRV>3 / drawdown <10% + vertical) | **No** (down 28%) |
| Score ≤3 AND price ≥40% above cost | **No** (score 10; MTM −11.4%) |
| ETF outflows ≥3% AUM after a sustained-inflow regime | **No** (no prior ≥5-session inflow regime this campaign) |
| Narrative break (confirmed CB-*selling* regime) | **No** — the opposite is in force (record dip-buying) |

**Status: no trim. Remaining position 25%.**

---

## 8. Critical Watchlist — GOLD

| Time (EST) | Event | Impact |
|---|---|---|
| **Tue Jul 14, 8:30** | **June CPI** (headline ~3.9% / core ~2.9% cons.) | **Dominant.** Hot core → real yields extend, checkpoint fires; soft → gold reclaims $4,050+ |
| Tue Jul 14 (AM) | **Warsh testimony — House** (hawkish framing) | Rate-path signal |
| Wed Jul 15, 8:30 | **June PPI** | Confirms/denies the CPI read |
| Wed Jul 15 (AM) | Warsh testimony — Senate Banking | Rate-path signal |
| Thu Jul 16 | June Retail Sales | Growth read (secondary) |
| **Fri Jul 17, 3:30** | **CFTC COT** (data as of Jul 14) — first real positioning update since 194,246 | **Gate-1 decider:** a ≥20–30K net-long decline = the flush that would begin to light the [V] gates |
| **Fri Jul 17, ~5:00** | **Weekly close vs $4,050 checkpoint** | Fires the stop re-drop $3,900 → $3,800 if close < $4,050 |
| Jul 28–29 | FOMC decision (Jul 29, 2:00 pm) — swaps ~43% for 25bp hike | Post-window; the real rate-path event |

**Tier-1 calendar lock:** in-window tier-1 US releases = **CPI (Jul-14, today), PPI (Jul-15)**; PCE (~Jul 25/30) and the FOMC (Jul 28–29) fall outside the 5-day window; next NFP early August. CPI is enumerated and event-conditioned — this is a disclosed CPI-day report, not an incomplete-data one.

---

## 9. Bull vs Bear Scorecard — GOLD

**Bull (structural):**
1. ✅ PBoC +14.93t June, 20th straight month — record-pace CB bid buying the dip
2. ✅ Speculative book NOT washed out (194K plateau) — the flush that marks lows still ahead, so the down-leg is not "used up"
3. ✅ Tuesday +0.6% bounce held above the $3,985 intraday low
4. ✅ Bank target floor holding (Goldman $4,900 YE; even JPM's cut $4,300 Q3 is a soft landing, not a crash)

**Bear (cyclical/rates):**
1. ❌ Real yields ~2.32% at cycle high — the dominant headwind for a non-yielding asset
2. ❌ Oil +17%/5 sessions revives the inflation/hike path (swaps ~43% July hike)
3. ❌ Below the broken $4,050 checkpoint; lower highs off $4,200
4. ❌ ETF outflow regime (June −74t worst of cycle)
5. ❌ Warsh hawkish debut + CPI-week de-risking of a rich, one-sided book

**Net: 4 bull / 5 bear — bearish-leaning, cyclical-over-structural.** → **Verdict-Confidence Collar engaged** (scorecard within 1 of balanced AND |EV| < 2%): no regime-resolution claims permitted.

---

## 10. Change Log — GOLD (vs Jul 13, 2026)

| Factor | Previous (Jul 13) | Current (Jul 14) | Direction |
|---|---|---|---|
| Canonical spot | $4,014 (XAU) | ~$4,025 (XAU) / $4,033 GC=F | ↑ +0.27% (Tue +0.6% bounce off Mon break) |
| Adjusted score | 10 | 10 | → (12th straight) |
| Weekly RSI-14 | 39.51 | **36.86** (live-week 38.32) | ↓ (same ≤40 band; nearing the 35 edge) |
| Drawdown | −28.12% | −27.95% | ↑ (shrank on the bounce; same ≥20% band → val 2) |
| **Vol basis** | "well under ½ BTC" (asserted) | **corrected: ~0.6–0.7× BTC realized — NOT ≤½; substitution held FLAGGED/debt-clocked** | ⚠️ honesty correction |
| Brent | $79.0 | **$86.61** (+17%/5 sess; Trump 20% Hormuz toll + port blockade) | ↑↑ more hostile |
| July hike odds | (Sept ~69%) | **~43% July (25bp) / Sept ~70%** | ↑ hostile |
| CPI | "CPI Tue" pending | **releasing 8:30 ET today** (cons. ~3.9%/2.9%; actual not yet in fetch) | live catalyst |
| COT net-long | 194,246 (plateau) | 194,246 — no new print (Jul-17 row is placeholder) | → UNCHANGED |
| PBoC | +14.93t / 20th mo | +14.93t / 20th mo | → UNCHANGED |
| 200-week SMA | $2,807.93 (+42.99%) | $2,819.56 (+42.75%) | ↑ (rising mean) |
| Checkpoint distance | −0.54× ADR below $4,050 | −0.40× ADR below $4,050 | ↑ (bounce toward line) |
| Stops | $3,850/<8 · $3,900 cat · Jul-17/$4,050 | identical | → no migration |
| Companion FR | 2/20, cap-bound | 2/20, cap-bound | → |

---

## 11. Strategic Verdict — GOLD

**Adjusted score 10/20 · Weighted EV $3,970.75 · EV-vs-spot −1.35% · sentiment NOT-FOUND-default (extreme-fear absent by construction) · stance: HOLD 25%, 75% dry, deployment FROZEN.**

Gold has now sat at a composite of 10 for twelve straight reports, and the reason is the same every time: this is a real-yield asset in a rates regime, and the one signal that would change the call — a capitulation in the speculative book — has never printed. This week sharpened the picture rather than changing it. A weekend war escalated into a sustained oil shock, Brent ran seventeen percent, and gold *fell* — the cleanest demonstration you will get that escalation currently hurts the metal, because it arrives through inflation and real yields, not flight-to-quality. The book that should be scared is instead richer than at any point in this correction, merely plateaued at 194,000 net-long. That is not the shape of a bottom; it is the shape of a market that still has one more unwind to give.

Two honest disclosures this report. First, I am correcting a claim the prior notes carried: gold's realized volatility is **not** "well under half" of Bitcoin's right now — it has compressed to roughly two-thirds — so the low-vol valuation substitution is carried this report as a flagged, debt-clocked read (it clears only on *implied* vol, and the alternative alt-drawdown bands are themselves invalid on a 10-year high that is not a confirmed all-time high; the choice moves no gate, stop, or tranche). Second, this is CPI morning, and I will not pretend to know the print: a soft headline on falling June gasoline could still hide a sticky core against a July oil spike, and three tier-1 releases sit between here and Friday's checkpoint — so no likelihood adjective is licensed on the $4,050 line, which is now four-tenths of an average day below spot and CPI-conditional.

The discipline holds: I do not add on a rates-driven price move that is not a positioning flush, and I do not sell a 25% position stopped safely above $3,900 into a record central-bank bid. Cash is a position, it earns ~4.3%, and it is paid to wait for the flush.

**Action items:**
1. **HOLD 25%** (blend $4,545, MTM −11.4%). **No add, no trim.** Deployment FROZEN (score 10 << 15).
2. **Watch the Jul-17 COT (3:30 ET) above all** — a ≥20–30K net-long decline is the first real evidence the flush is starting; only that (or deep valuation *with* a flush) begins to light the [V] gates.
3. **Checkpoint Fri Jul-17 weekly close vs $4,050** — hot CPI core keeps gold below and fires the stop re-drop to $3,800; soft CPI reclaims $4,050+ and holds it. Two-sided, CPI-conditional.
4. **Deliver the owed realized-30d-vol computation next report** (gold vs BTC); if gold ≥ ½ BTC, withdraw the low-vol substitution and re-score valuation.
5. Keep the compound stop **$3,850/score<8** and catastrophic **$3,900** unchanged; prospective Phase-2 ladder $3,700–3,950 requires the atomic re-stop to $3,650 before any fill.

> **The Pattern:**
> **IF** the Jul-17 COT prints a ≥20–30K net-long decline **AND** RSI breaks <35 **→ THEN** the flush this series has waited twelve reports for is beginning; re-rate the [V] gates and the Phase-2 arming.
> **IF** June CPI core comes in hot **AND** real yields extend above ~2.35% **→ THEN** gold stays below $4,050, the Friday checkpoint fires, and the held-position stop re-drops to $3,800.
> **IF** CPI is soft **AND** oil de-escalates **AND** real yields roll off ~2.32% **→ THEN** the haven channel finally outbids the rates channel, gold reclaims $4,050+, and the Rally band opens — but that requires a realized trend-structure repair, not a bounce.

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "GOLD",
  "date": "2026-07-14",
  "spot": { "value": 4025.00, "source": "XAU median Jul-14 cluster (Kitco $4,027.80 / TradingEconomics $4,027.15 / IFCM $4,022.17 / Investing $4,018.49); GC=F $4,032.8; spread 0.24%" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 2, "capitulation": 1, "holder": 3 },
    "raw": 10, "adjusted": 10, "rounding": "half-up"
  },
  "gates": { "active": 8, "na": [5], "passed": [4, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 17, "low": 4150, "high": 4450 },
      { "name": "Range", "p": 34, "low": 3950, "high": 4150 },
      { "name": "Retest", "p": 33, "low": 3800, "high": 3950 },
      { "name": "Bear", "p": 16, "low": 3500, "high": 3800 }
    ],
    "stated_ev": 3970.75, "vs_spot_pct": -1.35
  },
  "deployment": {
    "deployed_pct": 25, "dry_pct": 75,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~4650" },
      { "phase": "1B", "pct": 15, "entry": "~4475 (blended 25% @ ~4545, MTM -11.4%)" },
      { "phase": "2", "pct": 30, "entry": "3700-3950 prospective (frozen, score-gated; re-stop 3650 first)" },
      { "phase": "3", "pct": 45, "entry": "dry" }
    ]
  },
  "stops": {
    "catastrophic": 3900,
    "deepest_zone_floor_note": "deployment FROZEN — no active buy zone; the prospective Phase-2 ladder ($3,700 floor) requires an atomic re-stop to $3,650 BEFORE any fill (prose coherence check authoritative: held-position $3,900 < spot $4,025 PASS; post-activation $3,650 < $3,700 PASS). deepest_zone_floor omitted because no zone is armed.",
    "held_position_stop": 3900,
    "prospective_p2_floor": 3700,
    "prospective_p2_restop": 3650,
    "compound": { "price": 3850, "score_line": 8 },
    "checkpoint": { "date": "2026-07-17", "line": 4050, "prior": "2026-07-13 -0.54x ADR below; Jul-10 HELD close 4104 >= 4050", "adr": 63.30, "dist_x_adr": -0.40, "side": "below line, CPI-conditional" }
  },
  "verdict": "HOLD 25% confirmed (blend $4,545, MTM -11.4% at $4,025); 75% dry; deployment FROZEN (score 10 << 15). Score 10 HELD (12th straight). Gold bounced +0.6% Tuesday to ~$4,025 off Monday's -2.6% break, still below the $4,050 checkpoint. Oil ESCALATED further (Trump 20% Hormuz toll + Iranian-port blockade; Brent $86.61 +17%/5 sess), driving the rates channel (real yields 2.32% cycle high, July hike ~43%/Sept ~70%) that dominates the haven channel. CPI releasing 8:30 ET today (cons ~3.9%/2.9%; actual not yet in fetch). COT UNCHANGED 194,246 (plateau, no flush; next real print Jul-17 — the Jul-17 row is a placeholder). RSI fell 39.51->36.86 (same band, nearing 35 edge). VOL-BASIS CORRECTION: gold realized vol ~0.6-0.7x BTC, NOT the prior 'well under 1/2' claim -> low-vol substitution held FLAGGED/debt-clocked (clean realized-vol comp owed next report). Gates 2/8 (4,8 [V]; gate 3 none-by-construction; gate 5 N/A). Override NOT ARMED. Checkpoint distance narrowed -0.54x -> -0.40x ADR on the bounce (no stop parameter change -> no migration). Companion FR 2/20 cap-bound. Scorecard 4-5 -> Collar engaged.",
  "inputs": {
    "weekly_rsi": 36.86, "rsi_closes": 262, "rsi_source": "tools/fetch.mjs Wilder-14, Yahoo GC=F weekly, last completed week 2026-07-06; live-week 38.32",
    "valuation_metric": "low_vol_drawdown_from_ath", "drawdown_pct": -27.95, "ath": 5586.20, "ath_note": "10y weekly high, not confirmed all-time",
    "vol_basis_note": "gold 30d realized ~20-30% vs BTC ~30-50% => ~0.6-0.7x (ADR gold $63.3/1.57% vs BTC ~$1480/2.35% ~0.67x); NOT clean <=1/2 realized; clears only on implied (GVZ 25.52 vs BTC IV ~40-55%); leg HELD at 2, flagged, debt-clocked; standard alt-drawdown fallback invalid (10y high not confirmed ATH); clean realized-vol comp OWED next report",
    "cot_net_long": 194246, "cot_wow": 0, "cot_flush": false, "cot_next": "2026-07-17 3:30pm ET (placeholder row is not new data)",
    "sma_200w": 2819.56, "sma_200w_vs_spot_pct": 42.75,
    "real_yield_10y_tips": 2.32, "july_hike_odds_25bp": "~43%", "sept_hike_odds_ge1": "~70%", "dxy": 101.15, "vix": 17.52, "oil_brent": 86.61, "gvz": 25.52,
    "pboc_june_tonnes": 14.93, "pboc_streak_months": 20, "etf_june_tonnes": -74, "gld_5day": "NOT FOUND (403; prior -1.03B carried, debt-flagged)", "goldman_ye": 4900, "jpm_q3": 4300, "ubs_near": "3850-4000",
    "iran": "Trump 20% Hormuz toll + reinstated Iranian-port blockade, 3rd strike-round; Hormuz open-but-dark, 2-month traffic low; disputed 60-day toll-free interim deal",
    "cpi_status": "June CPI releasing Jul-14 8:30 ET; actual NOT in fetch; consensus headline ~3.9% (from 4.2%), core ~2.9% MoM +0.2%",
    "adr5": 63.30, "companion_fr": { "composite": 2, "gates": 0, "cap_bound": true }
  }
}
```
