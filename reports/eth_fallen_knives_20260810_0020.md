# 🔪 FALLEN KNIVES ANALYTICS — ETH — 2026-08-10

## MONDAY PRE-CPI — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Monday, 2026-08-10, 00:20 EST
### Asset: ETH | Prior Score: 10/20 (adj, 2026-08-06) | Current Score: 10/20 (adj)

---

## 1. Header / Regime

ETH $1,916.60, **61% below** its Aug-2025 high, ~23% below its falling 200-week SMA — the deepest-value member of the majors and the one where **holders are still accumulating, not distributing.** MVRV-Z is robustly negative (aggregate holders underwater), exchange reserves sit at 10-year lows, staking just hit a record 34%. The legs are unchanged from 08-06 (mechanical 11, adjusted 10). The story is the **divergence from BTC**: same easing crypto-fear tape, opposite holder behavior. Deep value, no trend confirmation — and I decline the near-miss entry again, on the same evidence I declined it on last report.

---

## 2. Verified Live Data Points

**Position (Hard Rule 8):** `node tools/position.mjs eth` → **EXPIRED** (~8.5 days). **Cold start per Hard Rule 4** — 100% dry, no deployment inferred, fresh ledger required before any fill.

**Price — canonical spot $1,916.60** (tool panel, live venue quotes, spread 0.086% <0.5%):

| Source | Price | Note |
|---|---|---|
| CoinGecko / Binance / Coinbase / Kraken | ~$1,914–1,917 | live, synchronized |
| Yahoo ETH-USD | $1,916.54 | bar close — informational |

Canonical **$1,916.60**, tools/fetch.mjs 2026-08-10T03:12Z. Spread 0.086% → single-confidence.

**Sentiment — Crypto F&G (pinned alternative.me; the ETH proxy per the Asset Generalization table):**

| Metric | Value | Status |
|---|---|---|
| Spot (08-10) | 30 | Fear |
| 3-day avg | **30.33** | → band ≤35 → leg **2** |
| Streak ≤15 | 0 | gate 1 dark |

**Secondary sentiment input — ETH funding:** +0.0058%/8h (≈ +6.3% annualized, longs pay shorts, *barely*) — **no fear in derivatives.** Confirms sentiment leg 2, no downgrade to fear.

**Spot ETF Flows (ETH):** **NET INFLOWS** — +$78.6M recent session, BlackRock-led; cumulative ~$11.6B+. → outflow gate 4 **dark**; capitulation-(c) not met.

**On-Chain / Valuation:**

| Metric | Value | Source |
|---|---|---|
| **MVRV-Z** | **≈ −0.80** (robustly negative) | derived from Santiment anchor — see below → band (any negative) → **V leg 5** |
| Weekly RSI-14 (261 closes, wk-end 08-03) | **43.17** (live-week 43.21) | tools/fetch.mjs → band ≤45 → **M leg 1** |
| Staking | **record 34%** of supply (from 32.4% May) | Phemex/KuCoin → holder (a) rising |
| **Exchange reserves** | **15.12M ETH, ~10y lows** (−1.74M since Jan, ~−10%) | KuCoin/Phemex → holder (b) declining ✅ |
| Validator exit queue | ~0 (negligible); entry queue ~2.48M ETH | sellable supply tightening |
| Funding | +0.0058%/8h | no fear |
| 200-week SMA | $2,484.77 → spot **−22.87%** (NOT within 8%) | gate 6 dark |
| 200-day MA | $2,050 (price −6.51%, slope falling) | structural downtrend |

**MVRV-Z derivation (provenance, per the single-source/continuity rules).** A secondary source (MEXC/Phemex aggregation) quotes ETH MVRV-Z **0.29**; I **reject it in favor of the documented derivation** carried in this series. Method (from the 08-06 report): z_now = z_anchor·((r_now−1)/(r_anchor−1)), Santiment anchor z=−1.1117 / ratio 0.7767 (2026-07-07, free-tier ~30d cap advancing with the calendar), realized cap held constant. At spot $1,916.6 (vs $1,902.5 on 08-06, +0.7%), z_now ≈ **−0.80** — the 08-06 value nudged slightly less negative. **Sign bound:** ETH would need to exceed ~$2,277 (+19%) merely to reach MVRV-Z 0; at $1,916 it is solidly negative. The 0.29 print is disclosed and set aside as methodologically inconsistent with the pinned Santiment series. Fallback drawdown band (−61.3% → 4) would give 4; the primary metric gives **5**, resting entirely on the robust negative sign.

**Correlation regime (#8):** computed ETH–SPX 30d **−0.09** (low-confidence, n=7). Inverse/decoupled, far below the 0.7 surcharge → **surcharge OFF.**

**Macro:** as BTC report — VIX 14.9, DXY 99.7, SPX/NDX near ATHs, Fed 3.50–3.75% held, dry-powder benchmark ~3.7–3.9%. **Context Panel:** ETH RV30 **39.34%** (highest of the three — ETH is the volatile leg); drawdown-from-2y-high percentile 89.6; distance-to-200dma −6.51%.

**The ETH-specific tell:** while BTC's long-term holders distributed this week (record LTH drop, reserves rising), **ETH holders did the opposite** — reserves at decade lows, staking at a record, validator exits near zero. Supply is being locked *out* of the float. That is the single cleanest structural-floor signal across the three assets.

---

## 3. Critical Developments

- **BTC/ETH holder divergence** (above) — the report's headline cross-asset signal. ETH is being accumulated on-chain even as its price languishes.
- **ETH/BTC ratio ≈ 0.0295** — near multi-year lows and still bleeding; ETH is the majors' structural underperformer.
- **ETF inflows resumed** (+$78.6M) but smaller and later than BTC's — institutions prefer BTC in this regime.
- **CPI Wed 08-12** — the tier-1 in-window release (see §8); unpriced.
- **Clarity Act** postponed — mild setback, ETH-relevant for market-structure clarity but no thesis impact.

---

## 4. Fallen Knives Composite Score — ETH

| Category | Max | Value | Basis |
|---|---|---|---|
| Sentiment | 5 | **2** | F&G 3d 30.33 → ≤35; funding confirms no fear |
| Momentum | 4 | **1** | Weekly RSI 43.17 → ≤45 |
| Valuation | 5 | **5** | MVRV-Z ≈ −0.80 (negative → generational band) |
| Capitulation | 3 | **0** | liq quiet, funding +ve, ETF inflows — 0/3 |
| Holder | 3 | **3** | staking record 34% (rising) + reserves 10y-lows (declining) — both |
| **Leg sum** | | **11** | |
| Discretionary (D1) | ±2 | **−0.5** | see below |
| **Mechanical score** | | **11** | |
| **Adjusted score** | | **10** | raw 10.5, ETH rounds .5 **down** |

**[V]-gate surcharge:** OFF (corr −0.09).

**D1 = −0.5, freshly re-argued (decay bar cleared).** The −0.5 has held ≥3 reports, so it must be re-argued from *fresh* evidence, which exists: **(a)** the ETH/BTC ratio ≈ 0.0295, at multi-year lows and still declining — a structural relative-weakness signal the absolute-fear legs cannot see; **(b)** ETH sits ~23% below a *falling* 200-week SMA with a falling daily 200d — structurally more broken than the fear/value legs alone convey. Both cut bearish, both orthogonal to the scored legs (which measure absolute fear/value, not relative structure or trend). **Cross-validation ratchet check (mandatory):** neither factor is a cross-validation claim — both rest on independent market facts (the live ratio; the live MA distances). No companion-corroboration weight is being withdrawn or added here. Falsifier: ETH/BTC reclaims ~0.032 **or** ETH prints a weekly close back above its 200-week ($2,485). Direction/size held; re-graded next report under the >3-report decay rule.

**Change vs 08-06:** legs identical (mech 11), adjusted identical (10). The report is *stable* on ETH — the movement is all in the cross-asset context, not the number.

### Confirmation Gates (2 / 8) — gate 5 N/A (PoS)

| # | Gate | Bucket | State | Relight path |
|---|---|---|---|---|
| 1 | Sentiment ≤15, 7d | [V] | ❌ | F&G ≤15 — not in regime |
| 2 | Weekly RSI <30 | [V] | ❌ | RSI 43 → fresh down-leg |
| 3 | MVRV-Z <1 | [V] | ✅ | lit (−0.80) |
| 4 | ETF outflows ≥2% | [V] | ❌ | flows are inflows |
| 5 | Hash Ribbon | [T] | **N/A** | PoS → denominator 8 |
| 6 | ±8% of 200w MA | [T] | ❌ | needs +22.9% rally to $2,485 — **none-in-regime** (large slow move) |
| 7 | Capitulation spike | [V] | ❌ | a real flush |
| 8 | LTH/holder stabilizing | [V] | ✅ | lit (reserves 10y-low, staking record) |
| 9 | Macro neutral-positive | [T] | ❌ | Fed higher-for-longer, CPI risk |

**Lit: 3, 8 → 2 gates, both [V].** Gate 6 tagged **none-in-regime** (structurally unreachable without a +22.9% move). Gates 1/2/7 reachable only on a fresh down-leg; gate 4 on a flow reversal; 9 on a macro pivot.

**Score-line & vacuity audit.** Attainable ceiling **20** (ETH unpinned). States:
- Phase 1A ≥8 (adjusted 10): **LIVE, TRUE on score** — the score line is *satisfied*; the binding constraint is the **gate count** (2 of 8, need 3).
- Phase 1B ≥11 (adjusted 10): LIVE, FALSE (short 1).
- Phase 2 ≥15 / P3 mech ≥17 / Override mech ≥15: LIVE, FALSE.
- Compound-stop score line <12 (mechanical 11): **VACUOUS-PERMISSIVE** — mechanical has stood below 12 for ≥4 report dates; stop is price-gated (fires more readily), disclosed in that direction; D6 governs (may rise only).
- **Binding axis for Phase 1A: the GATES, not the score.** Score clears ≥8; gate count is short by exactly 1 → this is the **D2 near-miss configuration** (see §6/§9).

---

## 5. Probability Matrix — ETH (analyst-set)

Baseline row (adj 6–10): Rally 20 / Range 35 / Retest 30 / Bear 15. **Active-downtrend residual: LIVE** — ETH is below a falling 200d/200w. Shift ~5pp Rally→Retest+Bear, widen downside.

| Scenario | Prob | Target | Midpoint | Trigger |
|---|---|---|---|---|
| Rally | 17% | $2,050–2,250 | $2,150 | 200d reclaim + ETH/BTC repair |
| Range | 35% | $1,820–2,050 | $1,935 | reserves-sink floor holds; chop |
| Retest | 30% | $1,650–1,820 | $1,735 | CPI risk-off, structural bleed |
| Bear | 18% | $1,400–1,650 | $1,525 | trend continuation to catastrophic zone |

Sum 100%. **EV = 0.17·2,150 + 0.35·1,935 + 0.30·1,735 + 0.18·1,525 = $1,837.75.** EV-vs-spot **−4.11%.** Check: 365.5+677.25+520.5+274.5 = 1,837.75 ✓. Rally 17% ≤50 ✓.

**Realized trailing-2-week change: ≈ +1.5%.** Negative EV during a mildly-positive 2-week move — disclosed (symmetrized rule).

**EV sign attribution:** Rally +2.07% / Range +0.34% / Retest −2.84% / Bear −3.68% (sum −4.11% ✓). Modal Range midpoint ($1,935) sits **above** spot; the sign is carried by **Retest+Bear distance** → **geometry-driven, risk-adjusted, not a directional forecast.** Sizing-down only. Non-dissolution: dissolves no check (mechanical 11 <15).

**Terminal-vs-extreme reconciliation (residual live + modal Range).** The modal Range band expresses the **terminal** expectation (base-building near the reserve-sink floor); the expected **path extreme** sits DOWN — Retest+Bear combine to **48%** vs Rally 17%, matching the sign of the live bearish trend-residual. **Base-building here does not claim the low is in** — ETH remains below a falling 200-week with no higher-high confirmed (Principle 3).

**EV Calibration Line.** Prior ETH EV negative; realized ETH ~flat-to-up → contradicted. ETH EV-vs-spot has run **negative across ~15 consecutive machine-block reports** (07-11→08-06) while spot chopped sideways-to-up. Streak past the ≥5 bar, majority contradicted → **on the record, systematic bias.** No trend-structure event cited → **default (b): EV DEMOTED to corroborative-only** — carries no stance, does not lift the report out of the collar. Streak resets only on a sign flip.

---

## 6. Deployment Strategy — ETH

**Total dry powder: 100% (cold start, ledger EXPIRED).**

Splits 10/15/30/45.

| Phase | Size | Unlock | Status |
|---|---|---|---|
| 1A | 10% | adj ≥8 AND ≥3 gates (≥2[V]) **OR D2 near-miss** | **DRY** — score clears (adj 10≥8), gates 2/8 (short 1). **D2 available, DECLINED** (below). |
| 1B | 15% | adj ≥11 + ≥5 gates | DRY (adj 10 <11) |
| 2 | 30% | adj ≥15 + ≥6 gates | DRY |
| 3 | 45% | mech ≥17 + ≥7 gates | DRY |

**D2 Analyst Conviction Path — AVAILABLE, DECLINED.** Phase-1A score condition met (adj 10 ≥8), gate count short by **exactly 1** (2 of 3, both lit are [V] → [V]-floor ≥2 satisfied), risk-on surcharge OFF → the path is genuinely open. **I decline it, for the second consecutive report, on the same evidence:** ETH is deep-value but in an **unbroken structural downtrend** (below a falling 200d, ~23% below a falling 200-week), weekly momentum weak (RSI 43), and the ETH/BTC ratio still bleeding. Principle 3 (bounces within a downtrend are suspect; require trend confirmation) governs. Nothing material changed since 08-06 (identical legs, identical gates) — taking the entry now, with no new bullish evidence, would be an inconsistent, discretion-for-its-own-sake fill. The substituted gate would be gate 2 (weekly RSI <30) or gate 9 (macro), relight paths as tabled. **What would flip me to take it:** a gate relighting *mechanically*, or a realized trend-structure event (200d weekly reclaim / confirmed higher-low). Logged in the Discretion Ledger as a declined near-miss.

**Deep-Value Override: N/A** (cold start, zero tranches deployed).

**Entry-zone ratchet:** a future 1A (mechanical or D2) rests **below** spot — a $1,750–1,880 laddered zone — never chasing $1,916. If ETH rallies away, the tranche stays dry and the framework accepts missing it.

### Stop framework (prospective — no live position)

Cold start → no live stop armed. Carried ratchet-compliant parameters: catastrophic **$1,300**; deepest prospective zone floor **$1,450**; compound **$1,350 AND mechanical <12**. **Coherence:** catastrophic $1,300 **strictly below** deepest zone floor $1,450 → **PASS.** No parameter moved → no Migration entry, D6-compliant. A D2 fill, were it taken, would additionally carry a **D5 hard price-only stop** below the structural swing low (≤15% under fill, single daily close) — noted, not armed. Checkpoint moot (no deployed tranche).

**Dry-powder yield:** 100% dry at ~3.7–3.9%.

---

## 7. Exit / Trim Framework — ETH

Flat → nothing to trim. Narrative-break exit (100%) independent — none active. **Status: flat.**

---

## 8. Critical Watchlist — ETH

| Time (EST) | Event | Impact |
|---|---|---|
| **Wed 08-12, 08:30** | **CPI (July) — TIER-1, in-window, unpriced** | risk-on/off for the highest-beta major |
| Thu 08-13 | PPI | secondary |
| Fri 08-14 | Retail Sales (date TBC — Aug 15 is Saturday) | consumer demand |
| ongoing | ETH ETF flows, ETH/BTC ratio, validator queue | structural-supply tape |
| Sep FOMC | Fed decision (outside window) | higher-for-longer |

**Tier-1 completeness:** only tier-1 in the 5-day window is **CPI Wed 08-12** (verified BLS). Post-08-12 short-horizon claims are CPI-conditional.

---

## 9. Analyst Read — Discretionary Layer

**1. The read.** ETH is the cleanest deep-value case among the majors and the messiest trend. On-chain, this is what a bottom's *plumbing* looks like: reserves draining to 10-year lows, staking at a record, validator exits near zero, holders in aggregate underwater (negative MVRV-Z) and refusing to sell. Off-chain, the tape says wait: ETH is ~23% below a falling 200-week, the ETH/BTC ratio is at multi-year lows and still dripping, and there is no higher-high to confirm a turn. Both are true at once. The framework's job here is to hold that tension without resolving it prematurely — which is exactly why the D2 path exists and exactly why I'm not taking it yet.

**2. What the rubric can't see.** (a) ETH/BTC relative weakness — the legs score ETH's absolute fear, not that capital is choosing BTC over it; this is the −0.5 D1. (b) The supply-lock mechanics (staking + validator queue removing float) — the holder leg credits it as a 3 but cannot weight *how structurally powerful* a decade-low reserve base is as a future-squeeze setup. (a) is bearish (priced in the D1); (b) is bullish (already maxed in the holder leg).

**3. The D1 term = −0.5**, re-argued fresh on the ETH/BTC ratio and the falling-200w distance (both orthogonal market facts, not cross-validation claims). Falsifier stated in §4.

**4. Actions taken/declined.** **Declined the D2 Phase-1A near-miss** (score clears, gates short 1) — Principle 3, no trend confirmation, nothing new vs 08-06. No mechanical deployment (1A gate-blocked). No Override (cold start).

**5. Discretion Ledger:**

| Date | Channel | Call | Size | Stop | Falsifier | Status | P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-10 | D2 | Phase-1A near-miss (gates short 1) | — | (D5 if taken) | declined pending gate relight / 200d reclaim | **DECLINED** | — |
| 2026-08-10 | D1 | −0.5 (ETH/BTC weakness + falling-200w) | n/a | n/a | ETH/BTC>0.032 or weekly>200w $2,485 | LIVE | — |

**6. What would change my mind.** Bullish trigger: a weekly close back above the 200-week ($2,485) **or** ETH/BTC reclaiming 0.032 — either flips the D2 decline to an entry and downgrades the −0.5. Bearish/opportunity: a CPI-driven flush toward $1,650 that re-lights capitulation/RSI gates — a *lower* 1A ladder. Dated: by 08-14 close, ETH holding >$1,850 with reserves still draining keeps the deep-value-but-wait read; a close <$1,750 activates the deeper-knife ladder.

---

## 10. Bull vs Bear Scorecard — ETH

**Bull:** ✅ MVRV-Z negative (generational value); ✅ reserves 10y-lows; ✅ staking record 34%; ✅ validator exits ~0 (supply lock); ✅ ETF inflows resumed.
**Bear:** ❌ 61% below high; ❌ below falling 200d AND 200w (−23%); ❌ ETH/BTC at multi-year lows, still bleeding; ❌ momentum weak (RSI 43); ❌ CPI risk; ❌ higher-for-longer Fed.
**Net: 5 bull / 6 bear — within 1 of balanced → Collar ON.** Deep value, no confirmation.

---

## 11. Change Log — ETH

| Factor | 2026-08-06 | 2026-08-10 | Dir |
|---|---|---|---|
| Adjusted score | 10 | **10** | flat |
| Legs (mech) | 11 | 11 | flat |
| Valuation | 5 (MVRV −0.82) | 5 (MVRV −0.80) | flat |
| Holder | 3 | 3 | flat (reserves 10y-low, staking record) |
| D1 | −0.5 | −0.5 | re-argued fresh |
| Gates | 2 (3,8) | 2 (3,8) | flat |
| Companion FR | 7 (standalone 08-06) | ~7 | flat |
| Cross-asset | — | **BTC distributing / ETH accumulating** | new divergence |

---

## 12. Strategic Verdict — ETH

**Adjusted 10/20 · EV −4.11% (DEMOTED) · F&G 30 (Fear) · stance: CAUTIOUS — deep value, staged, but not yet.**

ETH is the asset where the on-chain floor and the price trend are telling opposite stories, and I am not going to pretend the tension is resolved. The accumulation plumbing is as clean as it gets — a decade-low reserve base, record staking, holders underwater and refusing to sell — and it is the sharpest structural-floor signal on my board. But a deep-value asset below a falling 200-week, with its cross-ratio bleeding and no higher-high to point to, is a knife that has not landed. Principle 3 was written for exactly this configuration, and the framework's own bias-correction (the D2 path, the cut 1A line) buys me the *option* to enter, not the *obligation*. Nothing changed since Thursday; taking the near-miss now would be discretion talking to itself.

So I hold — 100% dry, cold start — with the D2 Phase-1A path explicitly armed-and-declined and a resting ladder mapped **below** spot for when either a gate re-lights or ETH gives me a trend tell. The demoted EV (−4.11%, negative for 15 straight reports while price didn't fall) is exactly the systematic bias the calibration layer flagged: I read it as risk-adjusted geometry, not direction, and it moves nothing. Cross-validation is structurally consistent (both cap-bound; FK 10 vs FR ~7, correctly long-leaning). The one thing I'd underline for next report: watch the BTC/ETH holder divergence — if BTC's distribution deepens while ETH's supply keeps locking, ETH is where the eventual mean-reversion has the most stored fuel.

**Action items:**
1. **Deploy nothing today.** 100% dry, cold start; refresh ledger before any fill.
2. **Arm the D2 Phase-1A resting ladder** at $1,750–1,880 (half-size, D5 stop below the swing low) — executes only on a pullback into zone *and* a gate relight or trend tell; not a market order.
3. **Trigger to take the entry:** weekly close >$2,485 (200w) OR ETH/BTC >0.032 OR a CPI-flush that re-lights gates 2/7.
4. **Ignore the demoted EV's sign.**

> **The Pattern:**
> - **IF** ETH prints a weekly 200-week reclaim ($2,485) or ETH/BTC repairs to 0.032 **THEN** the D2 decline flips to a staged 1A entry, the −0.5 D1 retires, and the trend finally corroborates the on-chain floor.
> - **IF** CPI-hot drives ETH toward $1,650–1,700 on a flush **THEN** capitulation/RSI gates re-light and the *lower* 1A ladder fills — the entry this framework prefers.
> - **IF** ETH chops $1,820–2,050 with reserves still draining **THEN** stay dry and patient; the supply-lock is building fuel, but base-building is not a buy.

---

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-08-10",
  "spot": { "value": 1916.60, "source": "tools/fetch.mjs panel, live synchronized venue quotes (CoinGecko/Binance/Coinbase/Kraken ~$1,914-1,917), spread 0.086% <0.5%; Yahoo bar close informational; 2026-08-10T03:12Z" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 5, "capitulation": 0, "holder": 3 },
    "discretionary": -0.5,
    "mechanical": 11,
    "raw": 10.5,
    "adjusted": 10,
    "rounding": "half-down"
  },
  "gates": { "active": 8, "na": [5], "passed": [3, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 17, "low": 2050, "high": 2250 },
      { "name": "Range", "p": 35, "low": 1820, "high": 2050 },
      { "name": "Retest", "p": 30, "low": 1650, "high": 1820 },
      { "name": "Bear", "p": 18, "low": 1400, "high": 1650 }
    ],
    "stated_ev": 1837.75,
    "vs_spot_pct": -4.11,
    "realized_2w_pct": 1.5,
    "sign_attribution": "geometry-driven — modal Range midpoint above spot; sign carried by Retest+Bear distance; risk-adjusted, not directional",
    "terminal_vs_extreme": "residual live + modal Range: modal = terminal (base near reserve-sink floor); path extreme DOWN (Retest+Bear 48% vs Rally 17%); base-building does NOT claim low is in (below falling 200w, no higher-high)",
    "calibration": "~15 consecutive negative machine-block EV reports (07-11->08-06) while spot flat-to-up; systematic bias on record; DEMOTED to corroborative-only (default b, no trend-structure event); does not satisfy collar EV branch"
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "cold_start": true,
    "tranches": [
      { "phase": "1A", "size_pct": 10, "status": "DRY", "discretionary": false, "entry": "score clears (adj 10>=8), gates 2/8 short 1; D2 near-miss AVAILABLE but DECLINED (Principle 3, no trend confirmation, unchanged vs 08-06); prospective resting ladder $1,750-1,880 below spot" },
      { "phase": "1B", "size_pct": 15, "status": "DRY", "discretionary": false, "entry": "not eligible (adj 10 <11)" },
      { "phase": "2", "size_pct": 30, "status": "DRY", "discretionary": false, "entry": "not eligible" },
      { "phase": "3", "size_pct": 45, "status": "DRY", "discretionary": false, "entry": "not eligible" }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1450,
    "compound": { "price": 1350, "score_line": 12 },
    "coherence": "PASS — catastrophic 1300 strictly below deepest zone floor 1450",
    "migration": [],
    "note": "Cold start — no live stop armed; parameters carried unchanged, D6-compliant. Compound score line VACUOUS-PERMISSIVE (mechanical 11 <12, >=4 report dates) — price-gated. A D2 fill (if taken) additionally carries a D5 hard price-only stop below the swing low (<=15% under fill, single daily close) — noted not armed. Checkpoint moot.",
    "d5_note": "no D5 stop armed — D2 near-miss declined, zero analyst-channel tranches",
    "checkpoint": { "date": null, "status": "inactive — no deployed tranche" }
  },
  "verdict": "CAUTIOUS / stay flat — adjusted 10/20, 100% dry (cold start). Deep value (MVRV-Z negative, reserves 10y-low, staking record) but unbroken downtrend; D2 Phase-1A near-miss AVAILABLE and DECLINED (Principle 3, unchanged vs 08-06). Resting ladder armed below spot. Collar ON.",
  "companion_fr": {
    "score": 7,
    "gates": 6,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 61.31, "ma200_falling": true, "price_below_ma200": true, "ma200_slope20_pct": -1.5, "price_below_ma200_pct": -6.51 },
    "cap_bound": true,
    "computed_note": "FR legs euphoria 3 / momentum 2 / valuation 1 / distribution 1 / vulnerability 0 (mech ~7); inputs materially unchanged from standalone eth_flying_rocket_20260806_1844 (mech 7), price within ~1%. Channel B (61% below 1yr ATH, 200d falling, price below 200d). Cap-bound.",
    "cross_validation": "structurally consistent (cap-bound; both-≥12 unfalsifiable by construction — ETH 61% below 1yr ATH). FK 10 (adjusted) / 11 (mechanical) vs FR ~7, correctly long-leaning/inverse, not both-≥12. FR <9 — no watch block, no new standalone obligation.",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": null, "fired_on": null, "reports_outstanding": 0, "prior_note": "08-01/03/05/06 inline FR>=9 obligation DISCHARGED by eth_flying_rocket_20260806_1844; FR now ~7 <9, no new trigger" }
  },
  "key_inputs": {
    "fng_spot": 30,
    "fng_3d_avg": 30.33,
    "fng_streak_le15_days": 0,
    "fng_provider": "alternative.me raw daily API (pinned) — crypto F&G as ETH proxy",
    "eth_funding_secondary_sentiment": "+0.0058%/8h (~+6.3% annualized, longs pay shorts) — no fear",
    "weekly_rsi14": 43.17,
    "weekly_rsi_live_week": 43.21,
    "weekly_rsi_closes_used": 261,
    "daily_rsi14": 56.73,
    "mvrv_z": -0.80,
    "mvrv_z_method": "derived from Santiment anchor z=-1.1117/ratio 0.7767 (2026-07-07), realized cap held constant; z_now=z_anchor*((r_now-1)/(r_anchor-1))",
    "mvrv_z_sign_bound": "solidly negative — needs spot >~$2,277 (+19%) to reach 0",
    "mvrv_z_declined_source": "MEXC/Phemex quote 0.29 REJECTED as methodologically inconsistent with pinned Santiment series (single-source/continuity rule)",
    "valuation_fallback_note": "drawdown -61.3% would give 4; MVRV-Z negative gives 5 (used)",
    "drawdown_from_ath_pct": 61.31,
    "high_1y": 4953.73,
    "high_1y_date": "2025-08-18",
    "staked_pct": 34,
    "staked_note": "record, from 32.4% May",
    "exchange_reserves": "15.12M ETH, ~10y lows, -1.74M since Jan (~-10%) — leg (b) declining",
    "validator_exit_queue": "~0 negligible; entry ~2.48M ETH",
    "eth_btc_ratio": 0.0295,
    "eth_btc_note": "multi-year lows, still bleeding — D1 factor (a)",
    "sma_200w": 2484.77,
    "pct_vs_sma200w": -22.87,
    "gate6_reachability": "none-in-regime (needs +22.9% to $2,485)",
    "ma200d": 2050,
    "pct_vs_ma200d": -6.51,
    "ma200d_falling": true,
    "etf_flow_recent_usd_m": 78.6,
    "etf_note": "inflows, BlackRock-led — outflow gate dark",
    "corr_eth_spx_30d": -0.09,
    "corr_confidence": "low (n=7); inverse/decoupled; surcharge OFF",
    "rv30_pct": 39.34,
    "tier1_calendar_next_5_sessions": "CPI Wed 08-12 08:30 ET (verified BLS) — only tier-1 in window; PPI 08-13; Retail Sales ~08-14 TBC; FOMC Sep/PCE late-Aug outside",
    "tier1_completeness": "complete",
    "position_snapshot": "EXPIRED (~8.5d) -> cold start Hard Rule 4, 100% dry",
    "attainable_ceiling": 20,
    "line_states": "P1A>=8 LIVE-TRUE-on-score (binding = gates 2/8 short 1, D2 near-miss); P1B>=11 LIVE-FALSE; P2>=15/P3>=17/OVR>=15 LIVE-FALSE; compound<12 VACUOUS-PERMISSIVE"
  }
}
```
