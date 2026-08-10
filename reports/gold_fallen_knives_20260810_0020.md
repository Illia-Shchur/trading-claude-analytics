# 🔪 FALLEN KNIVES ANALYTICS — GOLD — 2026-08-10

## MONDAY PRE-CPI — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Monday, 2026-08-10, 00:20 EST
### Asset: GOLD (spot XAU; ledger alias PAXG) | Prior Score: 7/20 (adj, 2026-08-05) | Current Score: 6/20 (adj)

---

## 1. Header / Regime

Gold **broke out +8.6% in five sessions to $4,380**, 21.6% below its Jan-2026 ATH, daily RSI 66.8, 200-day slope turning up, COT longs *building*, ETF inflows surging. This is the opposite of a fallen knife — it is the board's clearest **Flying Rocket** candidate. The FK composite falls 7→6 (adj) as the last capitulation credit (a stale COT washout) reverses into a crowded-long build. **The honest headline: gold does not belong to this framework right now; the companion FR score (≈8, climbing toward the ≥9 standalone-report tripwire) is the correct lens.**

---

## 2. Verified Live Data Points

**Position (Hard Rule 8):** `node tools/position.mjs gold` → **EXPIRED** (~8.5 days). **Cold start per Hard Rule 4** — 100% dry, no deployment inferred. *(Gold reads through the ledger via the PAXG alias per Hard Rule 8; canonical SPOT still comes from Hard Rule 1 sources below, never the ledger mark. PAXG carries issuer/custody counterparty risk vs spot XAU — disclosed, moot at a flat book.)*

**Price — canonical spot $4,380.10.** Sourcing limitation disclosed: gold is a futures contract with no crypto-style live venue panel — the two obtainable quotes (Yahoo **GC=F** COMEX front-month $4,380.10; **MGC=F** micro $4,380.10) are **frozen bar closes**, so **0 synchronized live quotes** (<3) → **low-confidence panel flagged** per the canonical-spot rule. Both agree to the cent → **zero dispersion**, so no EV-at-extremes computation needed. Best available reference for COMEX front-month; anchored to report-publication time. Source: tools/fetch.mjs 2026-08-10T03:12Z.

**Sentiment — NOT FOUND (pinned fallback, leg = 2).** No reliably-sourceable daily-resolution gold fear instrument in free sources (DSI/HGNSI paywalled/non-daily; GVZ is a volatility index, not a fear gauge; COT is PROHIBITED as the sentiment leg — it already keys capitulation-(b)/gate 1). Per the 2026-08-05 measured conclusion, both GVZ and PHYS-premium were backtested over 10y and **failed** as scored inputs — the leg scores **2 because the available instruments carry no signal**, not merely because none exists. Debt-clock branch remains discharged (structural limitation). **Disclosed regime context only (UNSCORED):** GVZ 25.64 (76th pct, 2y — turbulence, direction-blind); PHYS CEF premium −0.41% (mild discount). Neither scored.

**Momentum — weekly RSI-14 = 38.98** (Wilder, 260 completed closes, week-end 2026-07-27, UTC) → band ≤40 → **leg 2**. **⚠️ STALENESS FLAG (mandatory):** the last *completed* weekly bar (ending Aug 2) **predates the Aug 5–10 breakout entirely.** The **live-week RSI is 51.22** (band >45 → 0). Per the completed-closes input rule I score the leg **2**, but the "momentum exhaustion" it implies is **already voided** — gold is breaking out, not exhausting. Expect this leg to drop to ~0–1 on next week's completed bar. This is the inverse of the in-progress-week artifact: here the *completed* bar is the stale one. The overstatement is not laundered through D1 (that would double-count the momentum factor); it is disclosed here and reflected in the negative D1's *orthogonal* structural-bull factors and the Analyst Read.

**Valuation — drawdown from ATH 21.59% → band <30% → leg 0.** **Low-vol adaptation UNAVAILABLE this cycle (documented):** gold RV30 **22.92%** vs BTC RV30 **27.86%** → ratio **0.82×**, NOT ≤0.5× (BTC is in an unusually quiet regime *and* gold's vol jumped on the breakout, so the ratio collapsed). The low-vol band-set (which would give 2 at ≥20% drawdown) is therefore **not permitted** — standard drawdown bands apply: 21.59% <30% → **0**. Gold is not cheap; it is 21.6% below ATH and rallying.

**On-Chain / Positioning / Flows:**

| Metric | Value | Source | FK read |
|---|---|---|---|
| **COT non-comm net long** | **197.6K** (08-07 release), from 182.1K → **+15.5K WoW** | CFTC/Investing/FXStreet | **BUILDING, no washout** — gate 1 dark, capit-(b) 0 |
| COT managed-money net long | 130,766 (as of 08-04) | metalcharts/tradingster | crowded longs |
| **GLD flows** | **+$896M/wk, +$1.78B/month**, AUM $141.5B; single-day +$636.9M | Benzinga/ETFChannel, Aug-26 | **INFLOWS surging** — gate 4 dark, capit-(c) 0 |
| Global gold ETFs (WGC) | **+$3bn July** (+23t → 4,068t), reversing 2 months of outflows | World Gold Council | holdings RISING → holder leg credit |
| 200-week SMA | $2,843.68 → spot **+54%** | tools/fetch.mjs | gate 6 dark, none-in-regime |
| 200-day MA | $4,480.13 (price −2.23%, **slope +0.27% RISING**) | tools/fetch.mjs | trend repairing UP |
| Daily RSI-14 | 66.81 | tools/fetch.mjs | elevated, not extreme |
| ADR-5 | $76.68 (5 full sessions 08-04→08-10) | tools/fetch.mjs | — |

**Macro (context):** real 10y TIPS 2.43%; DXY 99.7 (soft — gold tailwind); VIX 14.9; Brent $84.33; Fed 3.50–3.75% held. **Gold's drivers are aligned bullish** (soft USD, inflation-hedge demand into CPI, safe-haven bid alongside record equities) — which is precisely why the *fear*-buy FK framework misreads it.

---

## 3. Critical Developments

- **Gold breakout +8.6%/5d to $4,380** — decisive move off the $4,020–4,100 shelf; 200d slope turned up.
- **Positioning building, not flushing** — non-comm net long +15.5K WoW into the rally; the prior report's provisional COT-washout credit is now clearly reversed.
- **ETF inflows surging** — GLD +$1.78B/month, WGC +$3bn July reversing two months of outflows. The gate-4 physical-outflow signal has flipped fully dark.
- **CPI Wed 08-12** — a hot print is a *gold tailwind* (inflation hedge), the one macro event that could extend the rocket.

---

## 4. Fallen Knives Composite Score — GOLD

| Category | Max | Value | Basis |
|---|---|---|---|
| Sentiment | 5 | **2** | NOT-FOUND pinned fallback (measured, no signal) |
| Momentum | 4 | **2** | Weekly RSI 38.98 (completed) → ≤40 — **STALE, live-week 51.22 voids it** |
| Valuation | 5 | **0** | Drawdown 21.59% <30%; low-vol adaptation UNAVAILABLE (vol ratio 0.82× >0.5×) |
| Capitulation | 3 | **0** | (a) breakout volume not a flush, (b) COT building not washout, (c) ETF inflows — 0/3 |
| Holder | 3 | **3** | ETF/physical holdings rising (WGC +$3bn July, GLD +$1.78B/mo) — momentum-flavored, credited per rubric |
| **Leg sum** | | **7** | |
| Discretionary (D1) | ±2 | **−1.0** | see below |
| **Mechanical score** | | **7** | |
| **Adjusted score** | | **6** | raw 6.0, gold rounds .5 up (n/a here) |

**[V]-gate surcharge:** N/A (crypto-SPX correlation not gold-relevant; not computed for gold).

**D1 = −1.0, freshly re-argued.** Two **orthogonal** structural-bull factors the fear/value legs cannot see as such: **(a)** a fresh breakout to new local highs with the **200-day slope turned up (+0.27%)** — a realized *uptrend*, which no FK leg scores (they score fear/value/exhaustion, not trend-up); **(b)** **gold-ETF inflows surging** (GLD +$1.78B/mo, WGC +$3bn July reversing outflows) — momentum-chasing demand, orthogonal because the ETF leg (gate 4/capit-c) only scores *outflows*. Both say the same thing: this is a rocket, and the FK legs (especially the stale momentum-2 and the momentum-flavored holder-3) overstate accumulation appeal. The staleness of the momentum leg is **not** counted as a D1 factor (double-count prohibited) — it is disclosed separately. Falsifier: a daily close back below the $4,020 shelf with the 200d slope re-flattening retires the term. *(The case for −1.5 is defensible given the breakout's strength; I hold −1.0 for continuity and log the strengthening case.)*

**Change vs 08-05:** mechanical 8→7 (capitulation 1→0 as the COT washout reversed to a build); adjusted 7→6.

### Confirmation Gates (1 / 8) — gate 5 N/A (not PoW)

| # | Gate | Bucket | State | Relight path |
|---|---|---|---|---|
| 1 | COT washout (gold sub) | [V] | ❌ | net long declining ≥20–30K WoW — currently BUILDING (+15.5K) |
| 2 | Weekly RSI <30 | [V] | ❌ | 38.98 completed / 51.22 live — moving *away* |
| 3 | Valuation cheap (gold) | [V] | ❌ | **none-by-construction** (low-vol band-set confers no gate credit; and unavailable this cycle) |
| 4 | Physical-ETF outflows (gold sub) | [V] | ❌ | **just flipped dark** — inflows surging; needs sustained multi-region outflows |
| 5 | Hash Ribbon | [T] | **N/A** | not PoW → denominator 8 |
| 6 | ±8% of 200w MA | [T] | ❌ | needs −30% to $2,844 — **none-in-regime** |
| 7 | Capitulation spike | [V] | ❌ | a real vol flush — current volume is *breakout* thrust |
| 8 | Holder stabilizing | [V] | ✅ | lit (holdings rising) — but momentum-driven, not a fear floor |

**Lit: 8 → 1 gate, 1 [V].** (Gate 4 flipped off since 08-05.) Nowhere near any unlock — correctly, gold is not an accumulation setup.

**Score-line & vacuity audit.** **Attainable ceiling this report (re-derived from today's pins):** sentiment pinned 2; valuation capped 0 this cycle (low-vol unavailable, <30% drawdown) and reaches 3 only with a confirmed COT flush at ≥45% drawdown; momentum 4, capitulation 3, holder 3 unpinned → **ceiling ≈ 14** on today's pins (the 2026-08-05 log's "~13 at −30%" is a prior approximation; this report's arithmetic governs and they diverge because valuation is pinned lower here). Line states:
- Phase 1A ≥8 (adjusted 6): **LIVE, FALSE** (short 2).
- Phase 1B ≥11 / P2 ≥15: **VACUOUS-PERMISSIVE→FALSE / near-VACUOUS-FALSE** given the ~14 ceiling — dropped from the forward narrative; gold is not approaching accumulation.
- Compound-stop score line <8 (mechanical 7): **flipped to VACUOUS-PERMISSIVE** this report — mechanical fell to 7 (<8), so the score key is now standing-satisfied and the compound stop is price-gated (fires *more* readily). At 08-05 (mechanical 8) it was VACUOUS-BLOCKING. Disclosed in correct direction; D6 governs — the line (8) may rise, never fall.
- **Binding axis: score (short 2) — gold is score-blocked far from any phase.**

---

## 5. Probability Matrix — GOLD (analyst-set)

Baseline row (adj 6–10): Rally 20 / Range 35 / Retest 30 / Bear 15. **Trend residual: MIRROR toward Rally** — gold's **200d slope turned up (+0.27%) and it made fresh local highs = a realized trend-repair event**, cited on this line to justify lifting Rally above baseline (>10pp deviation), and to satisfy the calibration option (a) below. Rally ≤50 cap respected.

| Scenario | Prob | Target | Midpoint | Trigger |
|---|---|---|---|---|
| Rally | 34% | $4,450–4,700 | $4,575 | breakout continuation, CPI-hot hedge bid |
| Range | 34% | $4,250–4,450 | $4,350 | digest the +8.6% thrust |
| Retest | 20% | $4,050–4,250 | $4,150 | back-test the breakout shelf |
| Bear | 12% | $3,800–4,050 | $3,925 | failed breakout, macro risk-on drains safe-haven |

Sum 100%. **EV = 0.34·4,575 + 0.34·4,350 + 0.20·4,150 + 0.12·3,925 = $4,335.50.** EV-vs-spot **−1.02%.** Check: 1,555.5+1,479+830+471 = 4,335.5 ✓. Rally 34% ≤50 ✓.

**Realized trailing-2-week change: ≈ +7.5%** ($4,074.5 on 07-27 → $4,380). A **negative EV printed during a strongly-positive 2-week move** — disclosed per the symmetrized rule; even in an uptrend the downside-band geometry pulls EV slightly negative.

**EV sign attribution:** Rally +1.51% / Range −0.23% / Retest −1.05% / Bear −1.25% (sum −1.02% ✓). Modal is a Rally/Range tie (34/34); the negative sign is carried by **Retest+Bear distance** → **geometry-driven, risk-adjusted, not directional.** |EV| 1.02% <2%.

**EV Calibration Line — invoking option (a).** The calibration rule's own GOLD exhibit: the Rally cell ran **pinned 16–22% across ~10 reports into a realized breakout** — the forecast layer systematically **underweighted upside**. That underweight is now contradicted again by a realized **+7.5% 2-week move**. Because a **realized trend-structure event is present** (200d slope turned up + fresh breakout — the same bar the collar's strong-claim unlock and the Rally-cap exception use), I take **option (a): bands re-derived this report** — Rally lifted to **34% measured this report** (not carried from prior), targets reset off the current breakout structure. The streak does **not** reset (no sign flip); it continues with **"bands re-derived at this report."** This is the sanctioned use of (a), not an ungated raise into a bounce.

---

## 6. Deployment Strategy — GOLD

**Total dry powder: 100% (cold start).** **No phase eligible; no accumulation ladder named** — gold is rallying, not falling. There is no fallen-knife buy zone to ladder into.

| Phase | Size | Unlock | Status |
|---|---|---|---|
| 1A | 10% | adj ≥8 AND ≥3 gates | **DRY** — adj 6 (<8), 1 gate |
| 1B–3 | — | higher | DRY (VACUOUS/out of reach at ceiling ~14) |

**Deep-Value Override: N/A** (cold start; and price condition — 8% below a deployed basis — is meaningless with no position and price *rising*).

### Stop framework (prospective — no live position, no active ladder)

Cold start → no live stop armed. Carried ratchet-compliant parameters (from the gold series): catastrophic **$3,800**; compound **$3,850 AND mechanical <8** (score line set 2026-06-17, below gold's realized score range). **Coherence check:** no active accumulation buy-zone/ladder is named this report (gold not in accumulation), so the catastrophic-vs-deepest-zone test is **N/A** — catastrophic $3,800 sits far below spot $4,380 with no ladder beneath it. No parameter moved → no Migration entry, D6-compliant. **Note the compound score-key flip** (blocking→permissive as mechanical fell to 7 <8) disclosed in §4 — moot at a flat book. Checkpoint (08-07, now past) is inactive: no deployed tranche.

**Dry-powder yield:** 100% dry at ~3.7–3.9%.

---

## 7. Exit / Trim Framework — GOLD

Flat → nothing to trim. *(If a long PAXG position existed, note: gold's momentum/valuation profile — daily RSI 66.8, +8.6%/5d — is nearer a Flying-Rocket **short-side** or **trim** signal than an accumulation one; that assessment belongs to a standalone FR report.)* **Status: flat.**

---

## 8. Critical Watchlist — GOLD

| Time (EST) | Event | Impact |
|---|---|---|
| **Wed 08-12, 08:30** | **CPI (July) — TIER-1, in-window** | hot → gold hedge-bid extends the rocket; cool → risk-on drains safe-haven |
| Thu 08-13 | PPI | secondary inflation |
| Fri (CFTC) | next COT release | is the long-build still extending? (FR-relevant) |
| Sep FOMC | Fed decision (outside window) | real-yield path = gold's key driver |
| ongoing | DXY, real yields, GLD/WGC flows | gold's actual drivers |

**Tier-1 completeness:** only tier-1 in the 5-day window is **CPI Wed 08-12** (verified BLS). For gold a hot CPI is asymmetrically *bullish* (inflation hedge) — the opposite of its crypto effect.

---

## 9. Analyst Read — Discretionary Layer

**1. The read.** Gold is a **Flying Rocket wearing a Fallen Knives report's clothes.** It broke out +8.6% in a week, the 200-day slope has turned up, speculators are piling into longs (+15.5K net WoW), and ETFs are chasing (+$1.78B/month into GLD, +$3bn into global funds reversing two months of outflows). Every one of those is the signature of the *distribution/euphoria* framework, not the accumulation one. The FK composite does the correct thing by scoring low (6/20) — but two of the legs that hold it *up* are artifacts: a momentum-2 read off a completed weekly bar that predates the breakout, and a holder-3 read that is really momentum-chasing inflows. Strip the artifacts and the true fallen-knife appeal is near zero. The right question for gold this week is not "where do I accumulate" — it's "where does the rocket top," and that is an FR question.

**2. What the rubric can't see.** (a) The completed-weekly-RSI lag — the fear/exhaustion leg is looking at last week's market, not this one. (b) Trend-up: no FK leg scores an uptrend, so the framework is structurally blind to the single most important fact about gold right now. Both handled via the disclosure + the −1.0 D1.

**3. The D1 term = −1.0**, on the breakout/200d-slope-up and the ETF-inflow surge (orthogonal structural-bull facts). −1.5 was considered and is defensible; held at −1.0 for continuity.

**4. Actions taken/declined.** No deployment (score-blocked, and no accumulation thesis exists). Invoked EV-calibration **option (a)** to re-derive the Rally band on the realized breakout. Recommended a **standalone Flying Rocket report on gold** as the actionable follow-up (companion FR ≈8, one point below the ≥9 tripwire).

**5. Discretion Ledger:**

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-10 | D1 | −1.0 (breakout + ETF-inflow surge) | n/a | n/a | daily close <$4,020 shelf + 200d slope re-flattens | LIVE | — |
| 2026-08-10 | D4 | Rally band re-derived (calib option a) on realized breakout | n/a | n/a | failed breakout back <$4,050 | LIVE | — |

**6. What would change my mind (toward gold as an FK asset).** Only a large, slow move: a −25% to −30% correction toward the low-vol/COT-flush zone with a *positioning washout* — none of which is remotely in view. Near-term the risk is the opposite (continuation). Dated: by 08-14, a hold >$4,250 with COT still building confirms the rocket; a failed breakout back <$4,050 is the first crack.

---

## 10. Bull vs Bear Scorecard — GOLD (price direction)

**Bull (for price):** ✅ +8.6% breakout; ✅ 200d slope up; ✅ ETF inflows surging; ✅ COT longs building; ✅ soft DXY + CPI hedge bid; ✅ safe-haven alongside record equities.
**Bear (for price):** ❌ daily RSI 66.8 (extended); ❌ 21.6% below ATH (overhead supply); ❌ crowded longs = squeeze fuel; ❌ +8.6%/5d is parabolic-adjacent.
**Net: 6 bull / 4 bear for PRICE — but for the FALLEN-KNIVES thesis this is decisively BEARISH** (no fear, no value, no accumulation edge). The two scorecards point opposite ways precisely because gold is an FR asset.

---

## 11. Change Log — GOLD

| Factor | 2026-08-05 | 2026-08-10 | Dir |
|---|---|---|---|
| Adjusted score | 7 | **6** | ↓1 |
| Capitulation | 1 (provisional COT washout) | 0 | ↓ (reversed to a build) |
| Valuation | 0 | 0 | flat (low-vol still unavailable) |
| Spot | ~$4,033 | **$4,380** | ↑ +8.6% breakout |
| COT net long | washout-provisional | 197.6K, +15.5K WoW | ↑ building |
| Gate 4 (ETF outflows) | ✅ | ❌ | flipped (inflows) |
| Gates lit | 2 (4,8) | 1 (8) | ↓ |
| Companion FR | 1 | **~8** | ↑ sharply (climbing to ≥9 tripwire) |
| D1 | −1.0 | −1.0 | re-argued fresh |

---

## 12. Strategic Verdict — GOLD

**Adjusted 6/20 · EV −1.02% (geometry-driven) · sentiment NOT-FOUND (leg 2) · stance: NOT AN FK ASSET — no accumulation, refer to Flying Rocket.**

The most useful thing this report can say about gold is where it *doesn't* belong. A +8.6% weekly breakout with speculators crowding long and ETFs chasing is a distribution-framework problem, and the FK composite correctly refuses to find an accumulation signal in it — 6/20, one gate lit, no eligible phase, no buy ladder. I've flagged the two legs propping the score (a stale momentum-2 off a pre-breakout weekly bar; a momentum-flavored holder-3) so nobody mistakes them for fear or floor. The valuation leg is a clean 0: gold is 21.6% below its ATH and rising, and the low-volatility adaptation that could soften that is *unavailable* this cycle because gold's own breakout vol and BTC's unusually quiet tape pushed the vol ratio to 0.82× — well above the 0.5× gate. Nothing here is cheap.

What matters for gold now sits in the other framework. The companion FR composite has climbed from 1 to ≈8 in five days and is one point under the threshold that would formally owe a standalone Flying Rocket report; I'm scoring it conservatively per Hard Rule 6 rather than over-crediting a short signal, but the direction is unambiguous. Cross-validation is structurally consistent and correctly *inverse* (FK 6 vs FR ~8, both cap-bound at 21.6% below the 1-yr ATH). The action item writes itself: if you care about gold this week, run the rocket, not the knife.

**Action items:**
1. **No FK deployment.** Gold is not an accumulation asset here; 100% dry, cold start.
2. **Run a standalone Flying Rocket report on gold** — companion FR ≈8 and climbing; that framework carries the borrow/squeeze/stop discipline this one doesn't.
3. **Watch CPI Wed 08-12** — for gold a hot print is a *tailwind* (extends the rocket); position sizing on any short belongs to FR, not here.
4. **Refresh the ledger** (PAXG alias) before any position claim; snapshot is EXPIRED.

> **The Pattern:**
> - **IF** gold holds >$4,250 with COT still building into CPI **THEN** the rocket extends — an FR distribution/short assessment, never an FK buy.
> - **IF** a −25/−30% correction with a positioning washout eventually prints **THEN** — and only then — gold re-enters FK scope with a real value/capitulation signal.
> - **IF** the breakout fails back below $4,050 **THEN** the first crack; still an FR trim signal, not an FK entry.

---

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "GOLD",
  "date": "2026-08-10",
  "spot": { "value": 4380.10, "source": "tools/fetch.mjs — Yahoo GC=F (COMEX front-month) $4,380.10 + MGC=F micro $4,380.10, both frozen bar closes agreeing to the cent; 0 synchronized live quotes (<3) -> low-confidence panel flagged, zero dispersion so no EV-at-extremes; 2026-08-10T03:12Z. Canonical SPOT via Hard Rule 1, not the ledger/PAXG mark." },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 0, "capitulation": 0, "holder": 3 },
    "discretionary": -1.0,
    "mechanical": 7,
    "raw": 6.0,
    "adjusted": 6,
    "rounding": "half-up"
  },
  "gates": { "active": 8, "na": [5], "passed": [8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 34, "low": 4450, "high": 4700 },
      { "name": "Range", "p": 34, "low": 4250, "high": 4450 },
      { "name": "Retest", "p": 20, "low": 4050, "high": 4250 },
      { "name": "Bear", "p": 12, "low": 3800, "high": 4050 }
    ],
    "stated_ev": 4335.50,
    "vs_spot_pct": -1.02,
    "realized_2w_pct": 7.5,
    "sign_attribution": "geometry-driven — Rally/Range modal tie 34/34, sign carried by Retest+Bear distance; risk-adjusted, not directional",
    "calibration": "GOLD Rally cell ran pinned 16-22% across ~10 reports into a realized breakout (systematic upside underweight); realized +7.5% 2w contradicts again; a realized trend-structure event IS present (200d slope up + breakout) -> option (a) invoked, Rally re-derived to 34% measured this report; streak does NOT reset (no sign flip), 'bands re-derived at this report'"
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "cold_start": true,
    "tranches": [
      { "phase": "1A", "size_pct": 10, "status": "DRY", "discretionary": false, "entry": "not eligible — adj 6 <8, 1 gate; NO accumulation ladder named (gold rallying)" },
      { "phase": "1B", "size_pct": 15, "status": "DRY", "discretionary": false, "entry": "not eligible / near-VACUOUS at ceiling ~14" },
      { "phase": "2", "size_pct": 30, "status": "DRY", "discretionary": false, "entry": "not eligible" },
      { "phase": "3", "size_pct": 45, "status": "DRY", "discretionary": false, "entry": "not eligible" }
    ]
  },
  "stops": {
    "catastrophic": 3800,
    "deepest_zone_floor": null,
    "compound": { "price": 3850, "score_line": 8 },
    "coherence": "N/A — no active accumulation buy-zone/ladder named (gold not in accumulation); catastrophic 3800 far below spot 4380 with no ladder beneath",
    "migration": [],
    "note": "Cold start — no live stop armed; parameters carried unchanged, D6-compliant. Compound score-key FLIPPED blocking->permissive: mechanical fell 8->7 (<8 line), score key now standing-satisfied, price-gated (fires more readily); disclosed in correct direction, D6 governs (line 8 may rise only). Score line <8 set 2026-06-17, below gold's realized range. Checkpoint 08-07 past & inactive (no deployed tranche).",
    "checkpoint": { "date": null, "status": "inactive — no deployed tranche" }
  },
  "verdict": "NOT AN FK ASSET — adjusted 6/20, 100% dry (cold start). Gold broke out +8.6%; no fear, no value, no accumulation edge. FK correctly finds nothing to buy. Companion FR ~8 (euphoria/heightened-watch, gray-zone routing) — run a standalone Flying Rocket report; that is gold's correct framework this week.",
  "companion_fr": {
    "score": 8,
    "gates": 5,
    "channel": "none",
    "channel_routing_note": "GRAY ZONE — no FR short-channel routes: 21.59% below 1yr ATH is just OUTSIDE Channel A's within-20% gate (needs +2.03% to $4,468), and the 200-day MA is RISING (+0.27%), failing Channel B's ma200_falling bear-continuation gate. The ~8 is therefore a euphoria/heightened-watch composite, NOT a deployable short channel — which is exactly why a standalone FR report is needed to adjudicate.",
    "cap_bound": true,
    "computed_note": "FR legs euphoria 2 / momentum 2 / valuation 1 / distribution 1 (conservative per HR6 — a build is not yet confirmed distribution) / vulnerability 2 = mech 8; up sharply from 1 (08-05) on the breakout. ONE POINT below the >=9 standalone-report tripwire; scored conservatively per Hard Rule 6.",
    "cross_validation": "structurally consistent (cap-bound; both-≥12 unfalsifiable by construction — gold 21.6% below 1yr ATH). FK 6 vs FR ~8 — correctly INVERSE (gold is a rocket, not a knife), not both-≥12. FR 8 <9 — no formal standalone obligation, but recommended and flagged one point away.",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": "approaching (ii) inline FR>=9 — currently 8", "fired_on": null, "reports_outstanding": 0, "recommendation": "run a standalone gold Flying Rocket report — the correct framework for gold this week" }
  },
  "key_inputs": {
    "sentiment": "NOT FOUND (pinned fallback -> leg 2); measured no-signal conclusion (GVZ/PHYS backtested & rejected 2026-08-05); GVZ 25.64 / PHYS premium -0.41% disclosed UNSCORED",
    "weekly_rsi14": 38.98,
    "weekly_rsi_closes_used": 260,
    "weekly_rsi_incl_live_week": 51.22,
    "weekly_rsi_staleness": "completed bar (wk-end 07-27) PREDATES the Aug 5-10 breakout; live-week 51.22 (band 0) voids the exhaustion signal; scored 2 per completed-closes rule, flagged, not laundered through D1",
    "daily_rsi14": 66.81,
    "drawdown_pct": 21.59,
    "drawdown_denominator": "ATH 5586.20 (2026-01-26)",
    "valuation_bandset": "STANDARD drawdown (low-vol adaptation UNAVAILABLE)",
    "rv30_pct": 22.92,
    "rv30_vs_btc_ratio": 0.82,
    "rv30_vs_btc_note": "0.82x > 0.5x -> low-vol band-set NOT permitted; BTC unusually quiet (27.86%) + gold breakout vol collapsed the ratio; standard bands give 0 at 21.59%",
    "cot_net_long_noncomm": 197600,
    "cot_wow_change": 15500,
    "cot_managed_money_net": 130766,
    "cot_washout_verdict": "NO — BUILDING (+15.5K WoW); gate 1 dark, capit-(b) 0",
    "gld_flows_week_usd_m": 896,
    "gld_flows_month_usd_bn": 1.78,
    "wgc_july_flows_usd_bn": 3.0,
    "wgc_july_holdings_tonnes": 4068,
    "gate4_flip": "ETF-outflow gate flipped dark (was lit 08-05) — inflows surging",
    "sma_200w": 2843.68,
    "pct_vs_sma200w": 54.03,
    "gate6_reachability": "none-in-regime (needs -30% to $2,844)",
    "ma200d": 4480.13,
    "ma200d_slope20_pct": 0.27,
    "ma200d_rising": true,
    "pct_vs_ma200d": -2.23,
    "adr5": 76.68,
    "dxy": 99.7,
    "real_yield_10y_tips_pct": 2.43,
    "vix": 14.9,
    "tbill_3m_pct": 3.71,
    "tier1_calendar_next_5_sessions": "CPI Wed 08-12 08:30 ET (verified BLS) — only tier-1 in window (gold: hot=tailwind); PPI 08-13; FOMC Sep outside",
    "tier1_completeness": "complete",
    "position_snapshot": "EXPIRED (~8.5d) -> cold start Hard Rule 4, 100% dry; PAXG alias disclosed",
    "attainable_ceiling": 14,
    "ceiling_note": "re-derived from today's pins: sentiment 2, valuation 0 (3 only with a confirmed COT flush at >=45% dd), momentum 4/capit 3/holder 3 unpinned; diverges from 2026-08-05 log's ~13 approximation — this report's arithmetic governs",
    "line_states": "P1A>=8 LIVE-FALSE(short 2); P1B>=11/P2>=15 near-VACUOUS-FALSE at ceiling ~14 (dropped from forward narrative); compound<8 flipped VACUOUS-PERMISSIVE (mechanical 7<8)"
  }
}
```
