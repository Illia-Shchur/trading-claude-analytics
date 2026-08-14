# 🔪 FALLEN KNIVES ANALYTICS — GOLD — JULY 20, 2026
## MONDAY SESSION — ALL DATA LIVE INTERNET-VERIFIED — SUB-$4,000 INTRADAY PROBE, SCORE HOLDS 8, ALL EYES ON FRIDAY'S COT
### Report Generated: Monday, July 20, 2026, 2:50 PM EDT
### Asset: GOLD | Prior Score: 8/20 (Jul 18, 3:05 PM) | Current Score: 8/20

---

## 1. Verified Live Data Points — GOLD

### Price (canonical-spot reconciliation)

| Source | Price | Timestamp | Status |
|---|---|---|---|
| Yahoo GC=F front month (tools/fetch.mjs) | $4,010.70 | Jul 20, 18:36 UTC | live (Monday session) |
| Yahoo MGC=F micro (tools/fetch.mjs) | $4,010.50 | Jul 20, 18:36 UTC | live |
| TradingEconomics gold | $4,001.06 (−0.40% on the day) | Jul 20, ~18:40 UTC | live (spot/continuous basis) |

**Canonical spot: $4,010.70 (GC=F front month, series convention).** 3 synchronized quotes; spread ~0.24% (<0.5%) — judged **basis-driven** (futures front-month vs spot/continuous), not genuine venue disagreement; no low-confidence handling required. Monday session range $3,986.50–4,046.00 — **the tape probed below $4,000 intraday** (nine-month-low territory per TradingEconomics) before recovering.

### Sentiment

**NOT FOUND — scored at the conservative default 2 per the pinned gold rule** (no asset-native daily fear instrument reliably sourceable; DSI/HGNSI paywalled/non-daily). COT is PROHIBITED as the sentiment input (it keys the capitulation leg). No fresh GVZ print fetched this cycle (context-only metric regardless).

### Positioning / Flows (capitulation + gate inputs)

| Metric | Value | Source |
|---|---|---|
| COT non-commercial net-long | **186,682** (−7,564 WoW, −3.9% — first decline off the plateau, NOT a flush; bar ≥20–30K or ≥15%) | CFTC via series Jul-18; **next print Fri Jul-24, 3:30 PM ET (data as of Tue Jul-21)** — investing.com calendar |
| GLD trailing month | −$3.69B / −2.6% of AUM (held from Jul-18; no fresher print) | series (WGC/GLD) |
| WGC global gold ETFs, June | −74 tonnes, multi-region | series (WGC monthly) |
| PBoC June | **+14.93t — 20th consecutive month**, largest single-month add since 2023 | goldsilver.com / WGC, fetched Jul-20 |
| WGC CB survey | 89% of central banks expect global official reserves to rise 12-mo; record 45% plan to add themselves | WGC 2026 survey via goldsilver.com Jul-20 |

### Macro (tools/fetch.mjs macro, Jul 20 18:36 UTC)

| Metric | Level | Note |
|---|---|---|
| US 10y real yield (TIPS) | **2.35%** (FRED DFII10, Jul-16 print — latest) | cycle-high zone; the channel that is beating gold |
| Brent | $89.21 (+7.09%/5d; >$90 intraday per NPR) | oil +~30% off July lows → hike fear |
| Sept hike odds | **~53%** (up from 47% a day earlier) | TradingEconomics/goldsilver.com Jul-20; Hammack hawkish Friday |
| July 28–29 FOMC | ~89% hold priced | FedWatch via financecalendar, Jul-20 |
| DXY | 100.93 | Yahoo |
| VIX | 17.76 | Yahoo |
| SPX / NDX (intraday) | 7,464.94 (+0.1%) / 25,618.19 (+0.4%) | stabilizing after Friday's semis rout |

### Weekly / Technical (tools/fetch.mjs, Jul 20 18:36 UTC)

| Metric | Value |
|---|---|
| Weekly Wilder RSI-14 (completed week Jul-13→17, closed Fri Jul-17; Yahoo GC=F weekly, 261 closes) | **37.22** (unchanged — no new weekly close since the Jul-18 report); live-week 37.17 |
| Drawdown from cycle ATH $5,586.20 (Jan-26-2026; tool window is 10y weekly — it is the cycle ATH, caveat disclosed per series) | **−28.2%** |
| 200-week SMA | $2,819.64 — spot +42.24% above (gate 6 boolean FALSE) |
| 5-day ADR (5 full sessions Jul 14–20, none abbreviated) | $65.16 |

### Correlation Regime

Not computed for gold this cycle (the 30d corr framework input is defined vs SPX for the crypto legs; gold series has never carried it) — **risk-on surcharge defaults OFF, per rule.**

---

## 2. Critical Developments — GOLD

- **Gold broke below $4,000 intraday Monday** — nine-month-low territory — as oil's surge (≈+30% off July lows) stoked rate-hike fear; recovered to ~$4,010 by mid-afternoon. (TradingEconomics, Jul-20)
- **The real-yield regime is unbroken:** 10y TIPS 2.35% cycle-high zone; Cleveland Fed's Hammack joined the persistent-inflation chorus Friday; **September hike odds 53% and rising.** A non-yielding asset keeps losing the carry argument. (goldsilver.com/TradingEconomics Jul-20)
- **Hormuz day 9:** strikes on Iranian infrastructure, a 3rd US service member killed, Iran disabled two tankers Monday. The haven bid this SHOULD produce keeps losing to the rates channel it also produces — the defining tension of this gold tape. (NPR Jul-20)
- **Central banks keep buying the dip:** PBoC's 20th straight month (+14.93t June, biggest since 2023); record 45% of central banks plan to add. Structural floor intact beneath a cyclical bleed. (WGC survey via goldsilver.com)
- **Friday Jul-24 COT is the event of the week for this series** — the first read on whether the −7,564 first crack accelerates into the ≥20–30K flush the framework has waited 14 reports for.

---

## 3. Fallen Knives Composite Score — GOLD: 8 / 20

| Category | Max | Input | Score |
|---|---|---|---|
| Sentiment Extreme | 5 | NOT FOUND — conservative default (pinned gold rule; COT prohibited here) | **2** |
| Momentum Exhaustion | 4 | Weekly RSI **37.22** (completed Fri Jul-17; unchanged, no new close) → ≤40 band; ~2.2 pts above the ≤35 edge | **2** |
| Valuation | 5 | Standard drawdown bands (low-vol substitution WITHDRAWN Jul-18, ratified): **−28.2% < 30% → 0** (tools/compute.mjs fk-drawdown) | **0** |
| Capitulation Evidence | 3 | (a) no >3σ/top-decile vol flush ❌ · (b) COT washout ❌ (−7,564/−3.9% is a first crack, bar is ≥20–30K or ≥15%; next data Jul-24) · (c) gold-ETF outflow spike ✅ (GLD −2.6% AUM/mo, WGC June −74t multi-region) | **1** |
| Holder Behavior | 3 | PBoC 20th month + record CB accumulation intent ✅ · official-sector demand rising ✅ | **3** |
| **TOTAL (raw)** | **20** | | **8** |

**Adjusted score: 8** (raw 8, gold half-up convention — no half-point). **Margin note (standing): composite 8 sits ONE notch above the compound stop's <8 score condition** — unchanged from Jul-18, line NOT loosened.

### Confirmation Gates — GOLD: 2 / 8 ✅ (both [V]; gate 5 N/A → denominator 8)

| # | Gate | Status | Note / relight path (re-derived) |
|---|---|---|---|
| 1 | [V] COT positioning washout (gold substitution) | ❌ | −7,564 is not the bar. Relight: **Fri Jul-24 print showing ≥20–30K (or ≥15%) net-long decline → gate turns ⚠️ provisional**, confirms ✅ on the following week's hold/extension. |
| 2 | [V] Weekly RSI <30 | ❌ | 37.22. Relight: ~2 more decisively red weekly closes. |
| 3 | [V] Valuation cheap (≥50% DD) | ❌ | −28.2%. Relight: none-in-regime (requires gold <$2,793 — a further −30% from here; structurally distant). |
| 4 | [V] Gold-ETF outflows (WGC/GLD bar) | ✅ | GLD −2.6% AUM trailing month; WGC June −74t multi-region |
| 5 | — Hash Ribbon | **N/A** | not applicable to gold |
| 6 | [T] ±8% of 200-week MA | ❌ | +42.24% above $2,819.64. Relight: none-in-regime (needs ~$3,045 or lower — far below even the Bear band). |
| 7 | [V] Capitulation volume spike | ❌ | ADR $65 vs the $104 Jul-14 max — no >3σ day. Relight: a disorderly >3σ range/volume session. |
| 8 | [V] Holder accumulation | ✅ | PBoC 20th month; record CB survey intent |
| 9 | [T] Macro neutral-positive | ❌ | real-yield/oil-hike regime is the active bear thesis. Relight: oil de-escalation + real yields rolling off 2.35%, or a dovish Jul-29 FOMC. |

Thresholds (denominator 8): 1A ≥3 · 1B ≥5 · 2 ≥6 · 3 ≥7; [V] floors 2/3/3/4. **Passed 2/8 [gates 4, 8] — 1A remains GATE-blocked (2 < 3) as well as score-frozen.**

---

## 4. Probability Matrix — GOLD (score 8 → 6–10 row)

Baseline 20/35/30/15. Adjustments (±pp, stated): **§5 active-downtrend residual LIVE** — gold is below its major MAs and printed a fresh lower low Friday ($3,964.20 < the Jul-13 $3,985.90); Monday's higher intraday low is one session, not structure. Shift −4 Rally → +3 Retest / +1 Bear (within the ≤5–7pp residual cap). Same cells as Jul-18 — the tape is unchanged in structure.

| Scenario | Probability | Target Range | Mid | Key Trigger |
|---|---|---|---|---|
| Rally | 16% | $4,150–4,450 | $4,300 | oil de-escalation + real yields roll over; $4,050 reclaim first |
| Range | 35% | $3,950–4,150 | $4,050 | churn between CB bid and rates pressure into FOMC |
| Retest | 33% | $3,800–3,950 | $3,875 | Sept-hike odds keep rising; COT bleed accelerates |
| Bear | 16% | $3,500–3,800 | $3,650 | hike delivered/signaled + speculative flush in motion |

**Sum = 100% ✓ (tools/compute.mjs). Weighted EV = $3,968.25 → EV-vs-spot −1.06%.** Components: 688 + 1,417.50 + 1,278.75 + 584 = 3,968.25. **Realized trailing-2-week change: −3.47%** ($4,155.10 Jul-6 close → $4,010.70) — the EV and the tape agree: modestly negative, orderly.

**Reconciliation (mandatory — residual live, modal cell Range):** the modal band expresses the TERMINAL expectation; while the §5 downtrend residual is live, the path EXTREME is expected in the **Retest band $3,800–3,950** — the Range label does not claim the low is in.

---

## 5. Deployment Strategy — GOLD (dry powder: 75%)

Benchmark on idle cash: ~4.3% T-bill — cash is being PAID to wait for the flush.

| Phase | Size | Zone | Status |
|---|---|---|---|
| 1A | 10% | ~$4,650 (legacy) | DEPLOYED (part of 25% blend) |
| 1B | 15% | ~$4,475 (legacy) | DEPLOYED — **blended 25% @ ~$4,545, MTM −11.76% at $4,010.70** |
| 2 | 30% | $3,700–3,950 prospective | **FROZEN** (score 8 ≪ 15; gates 2/8 < 6; atomic re-stop to $3,650 required BEFORE any fill) |
| 3 | 45% | — | DRY |

**Deep-Value Override: NOT ARMED** (score 8 < 15 — not close).

**Stops (no migration this report — all parameters unchanged from Jul-18's pre-committed checkpoint migration):**
- Catastrophic / held-position stop: **$3,800**.
- Compound thesis stop: ≥2 consecutive weekly closes < **$3,850** AND score < **8** (gold's calibrated line, set 2026-06-17, ratified 2026-07-10). Current: closes-below count **0** (Jul-17 weekly close ~$4,011 > $3,850); score condition NOT met (8 ≮ 8) — **margin remains one notch, disclosed.**
- **Coherence check:** deployment FROZEN — tested vs the deepest NAMED prospective ladder floor $3,700 (Phase-2): held-state catastrophic $3,800 strictly below $3,700? **FAIL — expected-for-frozen** (tools/compute.mjs); post-activation atomic re-stop $3,650 strictly below $3,700? **PASS**. No new deployment authorized this report → no realignment owed.
- **Checkpoint (mechanical form):** Friday, **July 24, 2026**, COMEX close ~1:30 PM ET settle / 5:00 PM ET Globex (normal full session — no holiday; verified vs CME calendar week). Fires iff the weekly close prints < $3,850 (first of the required 2) AND score < 8. Spot is **4.17% above** the line — **2.47×** the 5-day ADR ($65.16, 5 full sessions Jul 14–20, none abbreviated, none excluded). Next tier-1 release before the checkpoint: **none** (FOMC is Jul-29, PCE Jul-30; verified Fed/NY-Fed calendars Jul-20). The COT print lands the same afternoon (3:30 PM ET, after settle) — it informs the NEXT report, not this checkpoint.

---

## 6. Exit / Trim Framework — GOLD

| Trigger | Status |
|---|---|
| Score −6 from campaign peak (~12; −2 of the drop is the labeled Jul-18 measurement correction, carved out) | like-for-like drop −2. Not triggered |
| Sentiment/momentum euphoria | RSI 37, fear regime. Not triggered |
| Valuation extreme | −28% drawdown. Not triggered |
| Score ≤3 + ≥40% above cost | No (MTM −11.8%). Not triggered |
| ETF outflows post-inflow-regime | No inflow regime held during position life. Not triggered |
| Narrative break | None — CB accumulation thesis INTACT (PBoC 20th month) |

**Exit status: none. Position: 25% blend @ ~$4,545, held above the $3,800 stop.**

---

## 7. Critical Watchlist — GOLD

| Time (EST) | Event | Impact |
|---|---|---|
| Daily | Hormuz / Brent >$90 | the two-channel tension: haven bid vs rate-hike fear — rates winning since June |
| Tue Jul 21 | COT survey date (data cutoff for Friday's print) | — |
| **Fri Jul 24, ~1:30 PM settle** | **Weekly close checkpoint** (< $3,850 starts the 2-close sequence; score already at the 8 margin) | compound-stop axis 1 |
| **Fri Jul 24, 3:30 PM** | **CFTC COT print** | THE event: ≥20–30K net-long decline = the flush beginning → gate 1 ⚠️ provisional, capitulation leg b re-test |
| Mon Jul 27 | no tier-1 releases through Jul-27 (verified) | quiet data window |
| Wed Jul 29, 2:00 PM | **FOMC decision** + presser (Fed calendar) | ~89% hold priced; the Sept-hike signal in dots/presser is gold's real event |
| Thu Jul 30, 8:30 AM | **PCE + Q2 GDP + claims** (NY Fed calendar) | tier-1; oil pass-through watch |

---

## 8. Bull vs Bear Scorecard — GOLD

**Bulls (4):** 1) ✅ PBoC 20th straight month, biggest add since 2023 — the structural bid never blinked. 2) ✅ Record 45% of central banks plan to add (WGC survey). 3) ✅ COT bleed has BEGUN (−7,564) — the pre-condition of the flush this framework wants to buy. 4) ✅ −28% off the parabolic top already absorbed; Hormuz haven optionality is free at these levels.

**Bears (6):** 1) ❌ Real yields 2.35% cycle-high zone — the regime that has beaten gold since June. 2) ❌ Sept hike odds 53% and RISING (Hammack Friday). 3) ❌ Soft June CPI/PPI could not rally gold — the tell stands. 4) ❌ Sub-$4,000 intraday probe; fresh lower weekly low Friday; downtrend structure intact. 5) ❌ COT flush NOT confirmed — 186,682 net-long is still a crowded book that can bleed for weeks. 6) ❌ GLD/global ETF outflows continuing (−2.6% AUM/mo).

**Net: 4–6, bearish by 2.** → Verdict-Confidence Collar ENGAGED regardless (score 8 in the 6–10 band; |EV| 1.06% < 2%).

---

## 9. Change Log — GOLD (vs Jul 18, 3:05 PM)

| Factor | Previous | Current | Direction |
|---|---|---|---|
| Spot (GC=F) | $4,012.00 (frozen weekend) | $4,010.70 (live Monday) | ≈ flat; sub-$4,000 intraday probe |
| Adjusted score | 8 | 8 | = |
| Weekly RSI | 37.22 (live 37.48) | 37.22 (live 37.17) | = (no new close) |
| COT net-long | 186,682 (−7,564) | 186,682 (no new print until Jul-24) | = |
| Sept hike odds | ~50% | **~53%** | ▲ bearish |
| Brent | $88.10 | $89.21 (>$90 intraday) | ▲ bearish channel |
| PBoC | +14.93t June, 20th month | same, re-confirmed | = |
| Stops | $3,800/$3,850/<8 (post-migration) | unchanged, no migration | = |
| Checkpoint | Jul-24, 2.24× ADR | Jul-24, **2.47×** ADR ($65.16) | ≈ |
| EV vs spot | −1.09% | −1.06% | = |

---

## 10. Strategic Verdict — GOLD

**Adjusted score 8/20 · Weighted EV $3,968.25 · EV-vs-spot −1.06% · sentiment NOT-FOUND-default · stance: HOLD 25% (blend ~$4,545, MTM −11.8%), 75% dry, deployment FROZEN.**

Nothing in Monday's tape changes Saturday's assessment; it sharpens it. Gold probed below $4,000 for the first time in nine months, on the same mechanism this series has documented for five weeks: oil up thirty percent from its July lows feeds hike expectations, September odds tick to 53%, real yields hold their cycle highs, and a non-yielding metal gets repriced — while the war that drove the oil is simultaneously the reason gold hasn't fallen further. Both channels flow from Hormuz; the rates channel keeps winning. The one datapoint that could break this stalemate arrives Friday at 3:30 PM: the COT print covering Tuesday's survey date. A first crack of −7,564 became visible last week; the framework's bar for a real flush is ≥20–30K. IF Friday prints that acceleration → THEN gate 1 turns provisional, the capitulation leg re-arms, and the Phase-2 zone at $3,700–3,950 starts becoming buyable; falsifier — a flat or rebuilding COT print means the crowded book still hasn't cleared and patience continues.

The position needs no decision. The 25% blend sits 5.2% above its $3,800 stop with the compound score-margin at one notch — disclosed again, not loosened. The checkpoint arithmetic is honest: the $3,850 line is 2.47 ADRs below spot with no tier-1 release before Friday's close, so a checkpoint fire this week would require an outsized move, not drift. And the 75% dry powder earning 4.3% is not idle — it is short the flush that hasn't happened, which has been the right trade for fourteen consecutive reports.

**Action items — GOLD:**
1. **HOLD 25%. No add, no trim.** Deployment FROZEN (score 8 ≪ 15; gates 2/8).
2. **Friday Jul-24, 3:30 PM ET COT — the week's decision point:** ≥20–30K net-long decline → gate 1 ⚠️ provisional and the flush thesis activates; <10K → nothing changes, stay dry.
3. **Friday's weekly close vs $3,850** — checkpoint axis 1; 2.47× ADR away, fire unlikely absent a shock; score margin (8 vs <8) unchanged and NOT loosened.
4. **Phase-2 discipline stands:** any prospective $3,700–3,950 fill requires the atomic re-stop to $3,650 FIRST. No fill is authorized this report.
5. **FOMC Jul-29:** the September signal, not the July hold, is gold's event — a hawkish dot/presser is the Retest trigger; a dovish surprise is the only near-term Rally path.

> **The Pattern — GOLD:**
> **IF** Jul-24 COT prints ≥20–30K net-long decline **AND** weekly RSI breaks <35 **→ THEN** the capitulation this series has stalked since June is finally in motion — re-rate gates 1/2 and begin arming the Phase-2 zone (with the $3,650 re-stop sequence).
> **IF** gold prints a weekly close <$3,850 **AND** the score slips <8 (one leg deteriorating suffices) **→ THEN** the compound stop is one Friday from firing on the held 25% — the thinned margin made explicit, again.
> **IF** oil de-escalates and real yields roll off 2.35% **→ THEN** the haven channel finally outbids the rates channel — but only a reclaim of $4,050+ with trend-structure repair downgrades the bear regime; a bounce is not a bottom.

---

### Companion Flying Rocket (cross-validation — Hard Rule 5)

**Computed inline FR composite: ~2/20 — cap-bound.** Gold is −28.2% below its 1-yr/cycle high ($5,586.20) → the FR phase-of-cycle cap binds (>20% below 1-yr ATH). **Cross-validation: structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).** FR 2 < 9 → tripwire dormant. No standalone FR trigger (score held 8 — no cross; FR < 9; no short-side liquidation condition for gold; cap binds).

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-GOLD-20260720-1450 | UNVERIFIED | non_crypto_derivative |
| 1B | FK-P1B-GOLD-20260720-1450 | UNVERIFIED | non_crypto_derivative |
| 2 | FK-P2-GOLD-20260720-1450 | LOCKED | non_crypto_derivative |
| 3 | FK-P3-GOLD-20260720-1450 | LOCKED | non_crypto_derivative |

Registry schema: report-phase-registry/1; version: 1; origin: gold_fallen_knives_20260720_1450.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "GOLD",
  "date": "2026-07-20",
  "spot": { "value": 4010.70, "source": "GC=F front month $4,010.70 / MGC=F $4,010.50 (tools/fetch.mjs Jul-20 18:36 UTC, live Monday session) / TradingEconomics $4,001.06 (live, spot basis); 3 synchronized quotes, spread ~0.24% <0.5%, basis-driven not venue disagreement" },
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
    "stated_ev": 3968.25, "vs_spot_pct": -1.06
  },
  "deployment": {
    "deployed_pct": 25, "dry_pct": 75,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~4650" },
      { "phase": "1B", "pct": 15, "entry": "~4475 (blended 25% @ ~4545, MTM -11.76%)" },
      { "phase": "2", "pct": 30, "entry": "3700-3950 prospective (FROZEN, score 8<<15, gates 2/8; atomic re-stop 3650 required first)" },
      { "phase": "3", "pct": 45, "entry": "dry" }
    ]
  },
  "stops": {
    "catastrophic": 3800,
    "held_position_stop": 3800,
    "prospective_p2_floor": 3700,
    "prospective_p2_restop": 3650,
    "compound": { "price": 3850, "score_line": 8 },
    "note": "deepest_zone_floor omitted — deployment FROZEN, no active buy zone. Coherence vs deepest NAMED prospective ladder floor $3,700: held-state $3,800 < $3,700 FAILS (expected-for-frozen); post-activation re-stop $3,650 < $3,700 PASSES; no new deployment authorized, no realignment owed. NO migration this report — all parameters carried from Jul-18.",
    "checkpoint": { "date": "2026-07-24", "line": 3850, "condition": ">=2 weekly closes <3850 AND score<8", "closes_below": 0, "adr": 65.16, "dist_x_adr": 2.47, "side": "spot 4.17% above line; no tier-1 before checkpoint (FOMC Jul-29, PCE Jul-30); COT prints same day 3:30 PM ET after settle" }
  },
  "verdict": "HOLD 25% (blend ~$4,545, MTM -11.76% at $4,010.70); 75% dry; deployment FROZEN (score 8<<15, gates 2/8<6). Score HOLDS 8 (2/2/0/1/3) -- no new weekly close since Jul-18 (RSI 37.22 unchanged, live-week 37.17), no new COT print (186,682 held; next Fri Jul-24 3:30 PM ET, data as of Tue Jul-21), valuation stays 0 on standard drawdown bands (-28.2% <30%, low-vol substitution withdrawn Jul-18 and not revisited -- BTC vol regime unchanged). Monday tape: sub-$4,000 INTRADAY PROBE (session low $3,986.50, nine-month-low territory per TradingEconomics) recovered to ~$4,010; the two-channel Hormuz tension unchanged -- oil +30% off July lows (Brent $89.21, >$90 intraday, day 9 of strikes, 2 tankers disabled Monday) feeds Sept hike odds 53% (up from 47%, Hammack hawkish Friday) and real yields 2.35% cycle-high, beating the haven bid. PBoC 20th month re-confirmed (+14.93t June, largest since 2023; record 45% of CBs plan to add). Gates hold 2/8 [4,8]: gate 1 relight = Fri COT >=20-30K decline -> provisional; gate 3 none-in-regime (needs <$2,793); gate 6 none-in-regime (+42.24% above 200w $2,819.64). EV $3,968.25 / -1.06% vs -3.47% realized 2wk ($4,155.10 Jul-6 -> $4,010.70) -- EV and tape agree. Matrix identical to Jul-18 (16/35/33/16, residual live -4 Rally: fresh lower low Fri $3,964.20 < Jul-13 $3,985.90; Monday's higher low is one session not structure); terminal-vs-extreme: path extreme in Retest band $3,800-3,950. Stops NO migration: catastrophic/held $3,800, compound >=2 weekly closes <$3,850 AND score<8 (closes-below 0; margin one notch, 8 vs <8, disclosed NOT loosened); checkpoint Fri Jul-24 COMEX settle, 4.17% / 2.47x ADR ($65.16, 5 full sessions) above the line, no tier-1 before it. Coherence: held-state $3,800 vs prospective P2 floor $3,700 FAIL expected-for-frozen; post-activation $3,650 PASS; no new deployment authorized. No trim (like-for-like peak drop -2 after the labeled Jul-18 correction carve-out). Scorecard 4-6 bearish + score 6-10 band + |EV|<2% -> Collar ENGAGED. Companion FR ~2/20 cap-bound (-28.2% below cycle high), structurally consistent (both>=12 unfalsifiable by construction); FR<9, no standalone FR trigger.",
  "inputs": {
    "weekly_rsi": 37.22, "weekly_rsi_live_week": 37.17, "rsi_closes": 261, "rsi_source": "tools/fetch.mjs Wilder-14, Yahoo GC=F weekly, last completed week Jul-13..17 (closed Fri Jul-17; no new close since prior report)",
    "valuation_metric": "drawdown_from_ath_standard", "valuation_bandset": "standard alt (low-vol WITHDRAWN Jul-18, not revisited)", "drawdown_pct": -28.2, "ath": 5586.20, "ath_note": "Jan-26-2026 cycle ATH; tool window 10y weekly, caveat disclosed",
    "cot_net_long": 186682, "cot_wow": -7564, "cot_wow_pct": -3.9, "cot_flush": false, "cot_flush_bar": ">=20-30K or >=15%", "cot_next": "2026-07-24 3:30 PM ET (data as of Jul-21) — investing.com CFTC calendar",
    "sentiment": "NOT FOUND -> conservative default 2 (pinned gold rule; COT prohibited as sentiment input; no fresh GVZ fetched, context-only regardless)",
    "sma_200w": 2819.64, "sma_200w_vs_spot_pct": 42.24, "adr5": 65.16, "adr5_note": "5 FULL sessions Jul 14-20, none abbreviated, none excluded",
    "real_yield_10y_tips": 2.35, "sept_hike_odds": "~53% (up from 47%; Hammack hawkish Fri)", "july_fomc": "~89% hold priced, decision Wed Jul-29 2:00 PM ET", "dxy": 100.93, "vix": 17.76, "us10y_nominal": 4.60, "oil_brent": 89.21, "spx": 7464.94, "ndx": 25618.19,
    "pboc_june_tonnes": 14.93, "pboc_streak_months": 20, "wgc_survey": "89% expect global official reserves to rise; record 45% plan to add", "etf_june_tonnes": -74, "gld_trailing_month": "-$3.69B / -2.6% AUM (held from Jul-18)",
    "iran": "Hormuz day 9; 3rd US service member killed; 2 tankers disabled Monday; Brent >$90 intraday (NPR Jul-20)",
    "session_low_jul20": 3986.50, "sub_4000_probe": true,
    "realized_2wk_pct": -3.47,
    "tier1_next_5td": "none (Jul 21-27); FOMC Jul-29, PCE+GDP+claims Jul-30 (Fed + NY Fed calendars)",
    "corr_spx_30d": "not computed for gold series -> surcharge OFF per rule",
    "companion_fr": { "composite": 2, "gates": 0, "cap_bound": true, "standalone_report_triggered": false }
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "gold_fallen_knives_20260720_1450.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "GOLD",
      "report_date": "2026-07-20",
      "report_local_time": "14:50",
      "report_zone": "America/New_York",
      "instrument_class": "non_crypto_derivative",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-GOLD-20260720-1450",
          "decision": "UNVERIFIED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260720_1450.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-20",
          "report_local_time": "14:50"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-GOLD-20260720-1450",
          "decision": "UNVERIFIED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260720_1450.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-20",
          "report_local_time": "14:50"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-GOLD-20260720-1450",
          "decision": "LOCKED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260720_1450.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-20",
          "report_local_time": "14:50"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-GOLD-20260720-1450",
          "decision": "LOCKED",
          "instrument_class": "non_crypto_derivative",
          "report_file": "gold_fallen_knives_20260720_1450.md",
          "report_version": "report-machine/1",
          "asset": "GOLD",
          "report_date": "2026-07-20",
          "report_local_time": "14:50"
        }
      ]
    },
    "instrument_class": "non_crypto_derivative",
    "report_file": "gold_fallen_knives_20260720_1450.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "GOLD",
    "report_date": "2026-07-20",
    "report_local_time": "14:50",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-GOLD-20260720-1450",
      "FK-P1B-GOLD-20260720-1450",
      "FK-P2-GOLD-20260720-1450",
      "FK-P3-GOLD-20260720-1450"
    ],
    "status": "REGISTERED"
  }
}
```
