# 🔪 FALLEN KNIVES ANALYTICS — BTC — 2026-08-10

## MONDAY PRE-CPI — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Monday, 2026-08-10, 00:20 EST
### Asset: BTC | Prior Score: 11/20 (adj, 2026-08-06) | Current Score: 7/20 (adj)

---

## 1. Header / Regime

BTC $64,979, basing directly on its 200-week SMA (+1.85%), 48% below the Oct-2025 ATH. Fear is **easing, not deepening** (F&G 3-day avg 25.67 → 30.33 since 08-06), and that easing has now flowed into the legs: weekly RSI recovered above 40 (momentum 2→1) and — the day's single biggest change — **long-term-holder behavior flipped from accumulation to distribution** (holder 3→0). The mechanical score drops a full 4 points, 11→7, out of Phase-1A eligibility. This is not a deeper knife; it is a shallower one, and the framework says so.

---

## 2. Verified Live Data Points

**Position (Hard Rule 8):** `node tools/position.mjs btc` → **EXPIRED** (snapshot age ~12,201 min ≈ 8.5 days; `holdings_as_of` driver). **Cold start per Hard Rule 4, stated explicitly** — no fresh ledger is the position of record; 100% dry powder assumed, no prior deployment inferred. A fresh ledger export is required before any tranche executes.

**Price — canonical spot $64,979.40** (tool panel median of 4 synchronized live venue quotes, spread 0.123% <0.5%, all live):

| Source | Price | ts | Note |
|---|---|---|---|
| CoinGecko | $65,024 | 2026-08-10T03:10:50Z | live |
| Binance BTCUSDT | $65,000 | live | live |
| Coinbase BTC-USD | $64,944.03 | live | live |
| Kraken XBTUSD | $64,958.80 | receipt | live |
| Yahoo BTC-USD | $64,945.69 | bar close | EXCLUDED (frozen bar) |

Canonical **$64,979.40**, source: tools/fetch.mjs 2026-08-10T03:12Z. Spread 0.123% → single-confidence, no low-confidence demotion.

**Sentiment — Crypto F&G (pinned provider: alternative.me raw daily API):**

| Metric | Value | Status |
|---|---|---|
| Spot (08-10) | **30** | Fear |
| 3-day avg (08-08/09/10: 30,31,30) | **30.33** | Fear — **scored input** → band ≤35 → leg **2** |
| Last 10 (08-01→10) | 27,28,25,27,25,29,30,31,30 (+08-10:30) | rising off ~25 |
| Streak ≤15 consec days | **0** | gate 1 dark |
| Percentile vs 2y | ~30th | below-median fear, not extreme |

No second-provider divergence ≥10 to disclose. F&G has climbed ~5 points since 08-06 — fear easing.

**Spot ETF Flows (BTC):** **NET INFLOWS, accelerating.** +$626M first 3 sessions of Aug; +$102M on 08-07; **weekly total >$750M** (BlackRock IBIT +$479M leading). Source: SoSoValue / Farside / TheStreet, 2026-08-07/08. → The ≥2%-of-AUM **outflow** gate (gate 4) is **dark**; capitulation-(c) not met. Institutions are buying the base.

**On-Chain:**

| Metric | Value | Source |
|---|---|---|
| MVRV-Z | **0.42** (ratio 1.24, RP $52,330) | AhaSignals/Glassnode, 2026-08-08 → band ≤0.5 → **V leg 4** |
| Weekly RSI-14 (Wilder, 261 completed closes, wk-end 08-03, UTC) | **41.01** (live-week 41.08) | tools/fetch.mjs → band >40≤45 → **M leg 1** |
| 24h liquidations | negligible (~$0, quiet tape) | CoinGlass, 08-10 → no capitulation spike, gate 7 dark |
| Funding | mildly positive, 0 negative intervals | carried/CoinGlass — no derivative fear |
| **LTH supply** | ATH 16.64M (07-21), then **−210k, sharpest weekly drop since late-2024** | news.bitcoin / CoinDesk → (a) NOT rising 30d |
| **Exchange reserves** | **+17,500 BTC last week → 2.719M, highest since early-July** | Coinfomania/COINOTAG, 08-07 → (b) NOT declining |
| 200-week SMA | $63,776.71 → spot **+1.85%** (within ±8%) | tools/fetch.mjs → **gate 6 LIT** |
| 200-day MA | $70,130 (price −7.37%, slope falling) | tools/fetch.mjs |

**Correlation regime (Required Fetch #8):** computed BTC–SPX 30d return correlation **+0.32** (low-confidence, n=7 daily returns extractable from the fetch window, 07-30→08-07). Corroborated by the qualitative decoupling narrative (Santiment: BTC least-correlated to equities since the FTX crash). **Well below the 0.7 risk-on surcharge trigger → surcharge OFF.** Regime label: **mild/decoupled.** Not "not computed" — a low-confidence positive read, disclosed as such.

**Macro (context):** real 10y TIPS yield 2.43%; VIX 14.9 (−6.8%/5d, calm); DXY 99.7 (soft); Brent $84.33; SPX 7,757 (+3.58%/5d, near ATH); NDX 26,691 (+5.19%/5d); HY OAS 2.71 (tight — no credit stress); NFCI −0.529 (loose); net liquidity $5.84T; stablecoin supply $183.1B (−3.51%/90d, mild drain). Fed funds **3.50–3.75% held, higher-for-longer.** Dry-powder benchmark **3.71%** (^IRX) / 3.90% (DGS3MO). **Context Panel:** BTC RV30 27.86% (a historically QUIET regime — this matters for gold's valuation adaptation below); drawdown-from-2y-high percentile 91.6; distance-to-200dma −7.37%.

**The macro tell:** equities and gold at/near record highs, crypto still 48% underwater. This is an everything-rally *ex-crypto* — BTC is the laggard/funding source, not the leader.

---

## 3. Critical Developments

- **ETF inflows have turned decisively positive** (>$750M/week) — the institutional bid is back, but this *reduces* the fallen-knife opportunity (buying into strength, not fear).
- **LTH distribution event:** the sharpest weekly LTH-supply decline since late-2024, off the 07-21 ATH, with coins simultaneously flowing *back onto* exchanges (+17.5k). Native holders are taking profit into the institutional bid — a textbook late-base rotation, and the reason the holder leg collapses.
- **CPI Wednesday 08-12, 8:30 ET (July CPI)** — the one tier-1 release inside the 5-day window (verified vs BLS schedule). Unpriced; a hot print into a higher-for-longer Fed is the primary near-term risk.
- **Clarity Act** (US digital-asset market structure) postponed in the Senate — mild regulatory-clarity setback, no thesis impact.

---

## 4. Fallen Knives Composite Score — BTC

| Category | Max | Value | Basis |
|---|---|---|---|
| Sentiment Extreme | 5 | **2** | F&G 3d-avg 30.33 → ≤35 band |
| Momentum Exhaustion | 4 | **1** | Weekly RSI 41.01 → >40≤45 band (was 2 at 38.84; new completed weekly bar crossed 40) |
| Valuation | 5 | **4** | MVRV-Z 0.42 → ≤0.5 band |
| Capitulation | 3 | **0** | (a) liq quiet, (b) funding +ve, (c) ETF inflows — 0/3 |
| Holder Behavior | 3 | **0** | LTH record weekly drop (not rising) + reserves rising (not declining) — neither |
| **Leg sum** | | **7** | |
| Discretionary (D1) | ±2 | **0** | see below |
| **Mechanical score** | | **7** | round(Σ legs) |
| **Adjusted score** | | **7** | raw 7.0, half-up |

**[V]-gate surcharge:** OFF (corr +0.32 < 0.7).

**D1 discretionary = 0 (retired).** The prior −0.5 had held for ≥3 consecutive reports (08-03/05/06) → decay bar hit; it must be re-argued from fresh evidence or retired. The evidence it captured (fear not genuine capitulation; ETF comfort) has now **migrated into the legs** — momentum 2→1 and holder 3→0 both encode the easing-fear / distribution reality mechanically. **Negative adjustment considered and declined:** accelerating ETF inflows argue the fear-entry window is closing (a mild negative, orthogonal to the legs), but with the score already at 7 and no phase near unlocking, applying it would be redundant. Logged as a near-miss. Falsifier for a future negative term: inflows persist >$1B/wk while F&G climbs above 35.

**Change vs 08-06:** mechanical 11→7. Drivers: momentum −1 (RSI crossed 40), holder −3 (distribution flip), D1 +0.5 (retired). **Metric-history note:** the holder flip rests on two fresh, independently-sourced prints (record LTH weekly drop; +17.5k reserve rise). This is a genuine 4-day regime shift, not a backdating — I hold the conservative (lower) read per the tie-break convention given single-week volatility, and flag it as the key swing. Sensitivity: at holder 1.5 the mechanical would be 9; at holder 3, 10. The sourced freshest signals support 0.

### Confirmation Gates (2 / 9)

| # | Gate | Bucket | State | Relight path |
|---|---|---|---|---|
| 1 | Sentiment ≤15, 7d | [V] | ❌ | F&G ≤15 sustained — not in regime near-term (currently 30) |
| 2 | Weekly RSI <30 | [V] | ❌ | RSI 41 → needs a fresh down-leg |
| 3 | MVRV-Z <1 | [V] | ✅ | lit (0.42) |
| 4 | ETF outflows ≥2% AUM | [V] | ❌ | flows are +$750M/wk inflows — needs a sustained reversal |
| 5 | Hash Ribbon buy | [T] | ❌ | miner-cost recovery signal |
| 6 | ±8% of 200w MA | [T] | ✅ | lit (+1.85%) |
| 7 | Capitulation spike | [V] | ❌ | a real liquidation flush (top-decile/>3σ) — quiet now |
| 8 | LTH/holder stabilizing | [V] | ❌ | **just went dark** — needs LTH to stop declining AND reserves to resume falling |
| 9 | Macro neutral-positive | [T] | ❌ | Fed higher-for-longer, CPI risk Wed |

**Lit: 3, 6 → 2 gates, 1 [V].** (Gate 8 flipped off with the holder leg.) **Gate reachability:** gates 1/2/7 need a fresh down-leg (not in current basing regime but within reach on a CPI shock); gate 4 needs a flow reversal; gate 8 needs holder re-accumulation; gate 5/9 are [T] and structurally off in a fear/higher-for-longer regime; gate 6 is lit. None tagged none-in-regime except via the standard [T] structural-off.

**Score-line & vacuity audit (2026-08-06 rule).** Attainable ceiling this report = **20** (BTC unpinned). Line states, each vs the score it reads:
- Phase 1A ≥8 (adjusted 7): **LIVE, currently FALSE** — score short by 1.
- Phase 1B ≥11 (adjusted 7): LIVE, FALSE.
- Phase 2 ≥15 / Phase 3 mech ≥17 / Override mech ≥15: LIVE, FALSE (far).
- Compound-stop score line <12 (mechanical 7): **VACUOUS-PERMISSIVE** — the mechanical score has stood below 12 for ≥4 consecutive report dates; the stop's score key is standing-satisfied, so the compound stop is **effectively single-key and price-gated** (fires *more* readily, not less). This is disclosed in its correct direction; D6 governs — the line may rise, never fall.
- **Binding axis for Phase 1A:** score (short 1) AND gates (short 1). Both bind. This is *not* a near-miss worth forcing — the score fell below the line on its own evidence.

---

## 5. Probability Matrix — BTC (analyst-set, score-anchored)

Baseline row (adj 6–10): Rally 20 / Range 35 / Retest 30 / Bear 15. Adjustments: fear easing + ETF inflows lift Range/Rally modestly; CPI risk keeps downside live. **Active-downtrend residual: NO** — BTC is below the 200d but is *basing* (not making lower lows), holding the 200-week. Small residual only.

| Scenario | Prob | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| Rally | 22% | $67,000–72,000 | $69,500 | 200d reclaim on ETF-inflow follow-through |
| Range | 38% | $62,000–67,000 | $64,500 | base holds 200-week; chop into/after CPI |
| Retest | 25% | $57,500–62,000 | $59,750 | CPI-hot shock, 200-week loses |
| Bear | 15% | $50,000–57,500 | $53,750 | macro risk-off, campaign-low retest |

Sum 100%. **Weighted EV = 0.22·69,500 + 0.38·64,500 + 0.25·59,750 + 0.15·53,750 = $62,800.** EV-vs-spot **−3.35%.** Recomputation check: 15,290+24,510+14,937.5+8,062.5 = 62,800 ✓ (within 0.5%). Rally 22% ≤50 cap ✓.

**Realized trailing-2-week price change: +1.0%** (BTC ~$64.3k on 07-27 → $65.0k). A **negative EV printed during a mildly-positive 2-week move** — disclosed per the symmetrized rule.

**EV sign attribution:** contributions Rally +1.53% / Range −0.28% / Retest −2.01% / Bear −2.59% (sum −3.35% ✓). Modal cell is Range with midpoint ~at spot; the sign is carried by the **Retest+Bear band *distance*** (−4.6% combined) vs Rally (+1.53%). → **Geometry-driven — a risk-adjusted number, not a directional forecast.** May inform sizing downward only; its sign alone is not the basis for a stance. **Non-dissolution:** the label does not dissolve any consistency check (none flagged: mechanical 7 <15).

**EV Calibration Line (mandatory, this asset's series).** Prior EV-vs-spot **−1.12%** (08-06); realized spot moved **+1.04%** → **sign CONTRADICTED.** BTC EV-vs-spot has now run **negative across ~17 consecutive machine-block reports** (07-04→today) while spot rose net ~+5% — a same-sign streak far past the ≥5 bar, contradicted in the majority. **On the record: this EV is running as a systematic bias, not a forecast.** No trend-structure event (no 200d weekly reclaim, no confirmed higher-high) is cited → **default (b): EV demoted to corroborative-only.** It is printed but carries no stance, is not the reason for any deploy/decline, and — one-directionally — **does not lift this report out of the Verdict-Confidence Collar** (|EV| 3.35% ≥2% but demoted → collar branch not satisfied, collar stays ON). Streak resets only on a genuine sign flip.

---

## 6. Deployment Strategy — BTC

**Total dry powder: 100% (cold start, Hard Rule 4 — ledger EXPIRED).** No tranche is deployed; none inferred.

Splits 10/15/30/45. **No phase is eligible.**

| Phase | Size | Unlock | Status |
|---|---|---|---|
| 1A | 10% | adj ≥8 AND ≥3 gates (≥2[V]) | **DRY** — adj 7 (<8) AND 2 gates. Both axes short. |
| 1B | 15% | adj ≥11 + ≥5 gates | DRY |
| 2 | 30% | adj ≥15 + ≥6 gates + corr<0.8 | DRY |
| 3 | 45% | mech ≥17 + ≥7 gates | DRY |

**Deep-Value Override: N/A** — presupposes ≥1 deployed tranche; zero deployed. Cannot unlock Phase 1A.

**Entry-zone ratchet note:** were 1A to re-qualify, the ladder would rest **below** spot ($60–63k zone, laddered — never at the top); a tranche does not chase into strength.

### Stop framework (prospective — no live position)

Cold start → no live stop is armed. The standing ratchet-compliant parameters (carried, unchanged — D6: may only move toward price): catastrophic **$50,000**; deepest prospective zone floor **$54,000**; compound thesis stop **$55,000 AND mechanical <12**. **Coherence check:** catastrophic $50,000 **strictly below** deepest named zone floor $54,000 → **PASS.** No parameter changed value → no Migration Ledger entry, D6-compliant. Compound-stop checkpoint (08-09) is **moot** — no deployed tranche to check; it re-arms on a fill. No D5 stops (zero analyst-channel tranches).

**Dry-powder yield:** 100% dry earning **~3.7–3.9%** risk-free — the opportunity cost of patience here is real but the fallen-knife edge is not: BTC is not cheap enough or fearful enough to force a bid.

---

## 7. Exit / Trim Framework — BTC

No position held → no trim/exit live. For the record, the mechanical-score-drop trim (≥6 from local peak) reads the **mechanical** score and applies only to a held campaign; a cold-start book has no local peak. Narrative-break exit (100%) stands independently — none active. **Status: flat, nothing to trim.**

---

## 8. Critical Watchlist — BTC

| Time (EST) | Event | Impact |
|---|---|---|
| **Wed 08-12, 08:30** | **CPI (July) — TIER-1, in-window, unpriced** | hot → USD/yields up, BTC down; cool → risk-on |
| Thu 08-13, 08:30 | PPI (July) | secondary inflation read |
| Fri 08-14 | Retail Sales (July) — date to confirm (Aug 15 is a Saturday; likely 08-14 or wk of 08-17) | consumer demand |
| Sep FOMC | next Fed decision (not in window) | higher-for-longer priced |
| ongoing | ETF daily flows | the institutional-conviction tape |

**Tier-1 completeness:** the only tier-1 release inside the next 5 trading days (08-10→08-14) is **CPI Wed 08-12** (verified vs BLS). NFP already released (early Aug); PCE and FOMC fall outside the window. Any short-horizon claim resolving after 08-12 is event-conditional on CPI.

---

## 9. Analyst Read — Discretionary Layer

**1. The read.** BTC has found a floor on its 200-week mean and is *basing*, not capitulating. The tell of the week is a rotation: **long-term holders distributing into an accelerating institutional (ETF) bid** — the sharpest LTH weekly drawdown since 2024, coins flowing back to exchanges, IBIT hoovering $479M. That is what late-stage base-building looks like, and it is *bullish for price stability but bearish for the fallen-knife score*, which is a fear gauge. The 4-point drop to 7 is the framework correctly reporting that the easy-fear entry has thinned, not that the outlook worsened.

**2. What the rubric can't see.** (a) The equities/gold-at-highs vs crypto-underwater divergence — BTC is the funding laggard of an everything-rally; the legs score BTC's absolute fear, not its glaring relative weakness. (b) The holder rotation's *composition* — natives selling to institutions is structurally healthier than natives selling to natives, which the binary holder leg can't distinguish. Both roughly offset; neither justifies a D1 term today.

**3. The D1 term = 0**, retired under the decay rule; the negative it once carried is now in the legs. A negative term was considered (closing fear window) and declined as redundant.

**4. Actions taken/declined.** No deployment. No Override (N/A, cold start). No D2 (score below line, not a near-miss). Considered and declined: nothing forces a bid at 7/20 with reserves rising into the print.

**5. Discretion Ledger:** empty (no open discretionary tranches in this campaign; cold start).

**6. What would change my mind.** Bullish: a fresh LTH re-accumulation + reserve drawdown resuming, OR a CPI-cool + 200d weekly reclaim (would arm the collar's strong-claim unlock). Bearish/opportunity: a CPI-hot flush that breaks the 200-week and re-lights the capitulation/RSI gates — *that* is the entry this framework is built for. Dated falsifier: by the 08-13 close, if BTC holds >$63.5k with F&G >30, the basing read stands; a close <$61k re-opens the deeper-knife thesis.

---

## 10. Bull vs Bear Scorecard — BTC

**Bull:** ✅ holding 200-week mean (+1.85%); ✅ ETF inflows >$750M/wk; ✅ MVRV-Z 0.42 (not overvalued); ✅ VIX 14.9 / credit tight (no systemic stress); ✅ fear still sub-median.
**Bear:** ❌ LTH record distribution week; ❌ reserves rising (coins to exchanges); ❌ below falling 200d; ❌ 48% below ATH in an everything-rally (relative weakness); ❌ CPI risk unpriced; ❌ higher-for-longer Fed.
**Net: 5 bull / 6 bear — within 1 of balanced → Verdict-Confidence Collar ON.** Slight bearish lean, low conviction.

---

## 11. Change Log — BTC

| Factor | 2026-08-06 | 2026-08-10 | Dir |
|---|---|---|---|
| Adjusted score | 11 | **7** | ↓4 |
| Momentum leg | 2 | 1 | ↓ (RSI 38.84→41.01) |
| Holder leg | 3 | 0 | ↓ (distribution flip) |
| D1 | −0.5 | 0 | retired (decay) |
| Gates lit | 3 (3,6,8) | 2 (3,6) | ↓ (gate 8 off) |
| F&G 3d-avg | 25.67 | 30.33 | ↑ (fear easing) |
| ETF flows | mixed | +$750M/wk inflows | ↑ |
| Companion FR | 7 | ~6–7 | flat |

---

## 12. Strategic Verdict — BTC

**Adjusted score 7/20 · EV −3.35% (DEMOTED, corroborative-only) · F&G 30 (Fear, easing) · stance: PREPARE / stay flat.**

BTC has quietly changed character. Four days ago the book was one sub-24 F&G print from Phase-1B eligibility; today it is *below* the Phase-1A line, because the fear that powered the score is draining out — weekly momentum has repaired above 40, and long-term holders chose this week to distribute into the returning ETF bid. None of that is bearish for *price*; the 200-week is holding and institutions are buying. It is bearish for the *fallen-knife opportunity*, which is exactly what a fear gauge should say when fear recedes. The everything-rally in equities and gold, with crypto left 48% underwater, is the backdrop: BTC is the laggard being funded, not the leader.

I am not chasing. At 7/20, with reserves rising into an unpriced CPI, there is no edge in deploying dry powder that earns 3.8% risk-free while I wait for either a genuine flush (which re-lights the capitulation gates and *lowers* my entry) or a confirmed trend repair (200d weekly reclaim, which unlocks the collar and changes the regime call). The EV number is demoted on the record — it has pointed down for ~17 straight reports while price rose, and I will not let a systematically-biased forecast either scare me out or lure me in. Cross-validation is structurally consistent (both frameworks cap-bound at 48% below ATH; FK 7 vs FR ~6–7, neither strong).

**Action items:**
1. **Deploy nothing.** 100% dry, cold start. Refresh the ledger before any fill.
2. **Watch CPI Wed 08-12** — a hot-print flush that breaks the 200-week is the entry to want (re-lights gates 1/2/7 and *lowers* the ladder); a cool print + 200d reclaim flips the read bullish and arms the collar's strong-claim unlock.
3. **Track the holder rotation** — if LTH re-accumulate and reserves resume falling, gate 8 re-lights and the score recovers toward 1A without needing a price drop.
4. **Do not read the demoted EV as a signal** in either direction.

> **The Pattern:**
> - **IF** CPI-hot breaks $63.5k 200-week on a liquidation flush **THEN** the capitulation/RSI gates re-light, score climbs back toward 8–10, and a *lower* Phase-1A ladder ($58–61k) becomes the disciplined entry.
> - **IF** CPI-cool + a 200d weekly reclaim **THEN** the trend-repair strong-claim unlocks, the accumulation thesis downgrades (bounce confirmed, not a knife), and I let it go rather than chase.
> - **IF** BTC chops $62–67k with F&G 25–35 **THEN** stay flat; base-building is not a buy signal, and idle cash earns 3.8%.

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-BTC-20260810-0020 | LOCKED | crypto |
| 1B | FK-P1B-BTC-20260810-0020 | LOCKED | crypto |
| 2 | FK-P2-BTC-20260810-0020 | LOCKED | crypto |
| 3 | FK-P3-BTC-20260810-0020 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_fallen_knives_20260810_0020.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-08-10",
  "spot": { "value": 64979.40, "source": "tools/fetch.mjs panel median of 4 synchronized live venue quotes (CoinGecko $65,024 / Binance $65,000 / Coinbase $64,944.03 / Kraken $64,958.80), spread 0.123% <0.5% all live; Yahoo bar close EXCLUDED; 2026-08-10T03:12Z" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 4, "capitulation": 0, "holder": 0 },
    "discretionary": 0,
    "mechanical": 7,
    "raw": 7.0,
    "adjusted": 7,
    "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 6] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 22, "low": 67000, "high": 72000 },
      { "name": "Range", "p": 38, "low": 62000, "high": 67000 },
      { "name": "Retest", "p": 25, "low": 57500, "high": 62000 },
      { "name": "Bear", "p": 15, "low": 50000, "high": 57500 }
    ],
    "stated_ev": 62800.00,
    "vs_spot_pct": -3.35,
    "realized_2w_pct": 1.0,
    "sign_attribution": "geometry-driven — modal Range midpoint ~at spot; sign carried by Retest+Bear band distance; risk-adjusted, not directional",
    "calibration": "prior EV -1.12% CONTRADICTED by realized +1.04%; ~17 consecutive negative machine-block reports while spot rose; EV DEMOTED to corroborative-only (default b, no trend-structure event cited); does not satisfy collar EV branch"
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "cold_start": true,
    "tranches": [
      { "phase": "1A", "size_pct": 10, "status": "DRY", "discretionary": false, "entry": "not eligible — adj 7 <8 AND 2 gates <3; prospective ladder $60-63k below spot" },
      { "phase": "1B", "size_pct": 15, "status": "DRY", "discretionary": false, "entry": "not eligible" },
      { "phase": "2", "size_pct": 30, "status": "DRY", "discretionary": false, "entry": "not eligible" },
      { "phase": "3", "size_pct": 45, "status": "DRY", "discretionary": false, "entry": "not eligible" }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 },
    "coherence": "PASS — catastrophic 50000 strictly below deepest zone floor 54000",
    "migration": [],
    "note": "Cold start — no live stop armed; parameters carried unchanged, D6-compliant (no move). Compound score line VACUOUS-PERMISSIVE (mechanical 7 <12 for >=4 report dates) — price-gated, fires more readily; disclosed in correct direction, D6 governs (may rise only). Checkpoint 08-09 moot (no deployed tranche). No D5 stops.",
    "checkpoint": { "date": null, "condition": ">=2 weekly closes <55000 AND mechanical <12", "status": "inactive — no deployed tranche" }
  },
  "verdict": "PREPARE / stay flat — adjusted 7/20, 100% dry (cold start). Below Phase-1A line; fear easing, holders distributing. Collar ON. Wait for a CPI-flush entry or a confirmed trend repair; deploy nothing.",
  "companion_fr": {
    "score": 6,
    "gates": 5,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 48.53, "ma200_falling": true, "price_below_ma200": true, "ma200_slope20_pct": -1.2, "price_below_ma200_pct": -7.37 },
    "cap_bound": true,
    "computed_note": "FR legs euphoria 2 / momentum 1 / valuation 1 / distribution 2 / vulnerability 1 (mech ~6-7); inputs materially unchanged from standalone btc_flying_rocket_20260806_1844 (mech 7), price within ~1%. Channel B (48% below 1yr ATH, 200d falling, price below 200d). Cap-bound.",
    "cross_validation": "structurally consistent (cap-bound; both-≥12 unfalsifiable by construction — BTC 48% below 1yr ATH). FK 7 (mechanical) vs FR ~6-7, neither strong, not both-≥12. FR <9 — no watch block, no standalone obligation.",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": null, "fired_on": null, "reports_outstanding": 0 }
  },
  "key_inputs": {
    "fng_spot": 30,
    "fng_3d_avg": 30.33,
    "fng_last_10": [27,28,25,27,25,29,30,31,30,30],
    "fng_streak_le15_days": 0,
    "fng_provider": "alternative.me raw daily API (pinned)",
    "weekly_rsi14": 41.01,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_live_week": 41.08,
    "weekly_rsi_boundary": "Yahoo weekly candles, week-start UTC; last completed = 2026-08-03 bar",
    "momentum_change": "38.84 (08-06) -> 41.01: new completed weekly bar crossed the 40 edge, leg 2->1",
    "daily_rsi14": 54.87,
    "mvrv_z": 0.42,
    "mvrv_ratio": 1.24,
    "realized_price": 52330,
    "mvrv_source": "AhaSignals/Glassnode 2026-08-08",
    "drawdown_from_ath_pct": 48.46,
    "ath": 126080,
    "ath_date": "2025-10-06",
    "high_1y_pct_below": 48.53,
    "sma_200w": 63776.71,
    "pct_vs_sma200w": 1.85,
    "gate6_within_8pct": true,
    "ma200d": 70130,
    "pct_vs_ma200d": -7.37,
    "lth_supply": "ATH 16.64M 07-21, then -210k (sharpest weekly drop since late-2024) — leg (a) NOT rising 30d",
    "exchange_reserves_trend": "+17,500 BTC last wk -> 2.719M, highest since early-July — leg (b) NOT declining",
    "holder_flip_note": "3->0; conservative read held on single-week volatility; sensitivity holder 1.5->mech 9, 3->mech 10",
    "funding_note": "mildly positive, 0 negative intervals — no derivative fear",
    "liquidations_note": "quiet (~$0 24h) — no capitulation spike",
    "etf_flow_week_usd_m": 750,
    "etf_flow_0807_usd_m": 102,
    "etf_streak": "inflows accelerating, IBIT +$479M — outflow gate 4 dark",
    "corr_btc_spx_30d": 0.32,
    "corr_confidence": "low (n=7 daily returns from fetch window 07-30/08-07); corroborated by qualitative decoupling; surcharge OFF",
    "real_yield_10y_tips_pct": 2.43,
    "vix": 14.9,
    "dxy": 99.7,
    "brent": 84.33,
    "spx_close": 7757.64,
    "hy_oas_pct": 2.71,
    "nfci": -0.529,
    "net_liquidity_usd_t": 5.84,
    "stablecoin_supply_usd_bn": 183.1,
    "tbill_3m_pct": 3.71,
    "fed_funds_target": "3.50-3.75% held",
    "tier1_calendar_next_5_sessions": "CPI Wed 08-12 08:30 ET (verified BLS) — only tier-1 in window; PPI 08-13; Retail Sales ~08-14 (date TBC); FOMC Sep / PCE late-Aug outside window",
    "tier1_completeness": "complete — CPI enumerated, in-window horizon disclosed",
    "position_snapshot": "EXPIRED (~8.5d) -> cold start Hard Rule 4, 100% dry, no deployment inferred",
    "attainable_ceiling": 20,
    "line_states": "P1A>=8 LIVE-FALSE(short 1); P1B>=11 LIVE-FALSE; P2>=15/P3>=17/OVR>=15 LIVE-FALSE; compound<12 VACUOUS-PERMISSIVE (price-gated)"
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_fallen_knives_20260810_0020.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "BTC",
      "report_date": "2026-08-10",
      "report_local_time": "00:20",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-BTC-20260810-0020",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260810_0020.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-10",
          "report_local_time": "00:20"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-BTC-20260810-0020",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260810_0020.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-10",
          "report_local_time": "00:20"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-BTC-20260810-0020",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260810_0020.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-10",
          "report_local_time": "00:20"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-BTC-20260810-0020",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260810_0020.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-10",
          "report_local_time": "00:20"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "btc_fallen_knives_20260810_0020.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "BTC",
    "report_date": "2026-08-10",
    "report_local_time": "00:20",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-BTC-20260810-0020",
      "FK-P1B-BTC-20260810-0020",
      "FK-P2-BTC-20260810-0020",
      "FK-P3-BTC-20260810-0020"
    ],
    "status": "REGISTERED"
  }
}
```
