# 🔪 FALLEN KNIVES ANALYTICS — BTC — July 16, 2026

## THURSDAY PRE-MARKET — DUAL SOFT-INFLATION PRINT (CPI+PPI) — SCORE CROSSES 13 — ALL DATA LIVE INTERNET-VERIFIED

### Report Generated: Thursday, July 16, 2026, 3:30 AM EDT

### Asset: BITCOIN (BTC) | Prior Score: 12/20 (Jul 14, 2:30 PM) | Current Score: **13/20**

> **Score crosses 13 for the first time in this series.** June PPI (released Jul-15, 8:30 AM ET) came in soft on top of Tuesday's soft CPI — a second straight downside inflation miss — and BTC extended its short-squeeze rally to an intraday high of $65,507 before settling near $64,648. The score move is real but modest: sentiment deepened (3-day F&G 25.33→24, a fear-band upgrade) while momentum stayed flat and valuation held — net +1. **Phase 1B's SCORE gate (≥13) is now met for the first time — but the GATE-count condition (≥5/9) is not (4/9 lit), so 1B stays locked, just for a different reason than Tuesday.** Two mandatory triggers fire this report: the score crossing a phase-unlock threshold, and ≥$100M of Jul-15's liquidation volume landing short-side — both require a full standalone Flying Rocket companion report, produced alongside this one.

---

## 2. Verified Live Data Points — BTC

### Price (canonical reconciliation)

Canonical spot = **$64,648** (median of CoinGecko live $64,648 and Yahoo BTC-USD last close $64,622.07; divergence 0.04%, both same-session synchronized). Source: `tools/fetch.mjs`, Jul 16 07:13 UTC (~3:13 AM EDT).

| Source | Price | Timestamp | Note |
|---|---|---|---|
| CoinGecko (live) | $64,648 | Jul 16, 07:13 UTC | canonical component |
| Yahoo BTC-USD (last daily close) | $64,622.07 | Jul 15 close | canonical component; 0.04% from live — genuine agreement, not staleness |
| **Canonical** | **$64,648** | Jul 16, ~3:13 AM EDT | — |
| Jul-15 intraday high (context) | $65,507.59 | Jul 15 (daily range) | short-squeeze extension; not sustained |
| Jul-16 session so far | $64,389–$64,893 | Jul 16 (partial session) | consolidating just below the $65K test |

Spread is a genuine live agreement (0.04% < 0.5%), no low-confidence demotion. Price ran from a Jul-13/14 pre-CPI low near $62,207 through a dual soft-inflation print (CPI Jul-14, PPI Jul-15) to an intraday $65,507 high, and is now consolidating ~$64,600–64,900.

### Sentiment (Alternative.me, pinned provider)

| Reading | Value | Status |
|---|---|---|
| Spot (Jul-16 daily print) | **25** | Extreme Fear |
| 3-day average (scored) | **24** | ≤25 band → **leg 3** (up from 2; 3d-avg deepened from 25.33→24) |
| Daily prints ≤15 streak | **0** | gate 1 dark |

Last 5 daily prints: 25 (Jul-16) · 25 (Jul-15) · 22 (Jul-14) · 28 (Jul-13) · 26 (Jul-12). 3-day avg = (25+25+22)/3 = **24** (`tools/fetch.mjs`, confirmed via `tools/compute.mjs band fk-sentiment 24` → band 3). The rally has **not** lifted the fear print — daily readings remain pinned in the low-to-mid 20s even after two soft-inflation days, a genuine (if modest) deepening of the scored fear signal rather than the "rally drains fear" pattern seen Jul-14.

### Spot BTC ETF Flows — choppy reversal, Jul-15 unconfirmed

| Window | Net Flow | Source | Timestamp |
|---|---|---|---|
| Jul 13 (daily) | **−$424.66M** (IBIT −$185.5M, FBTC −$245.6M) | Bloomingbit/CryptoBriefing | Jul 14/15 |
| Jul 14 (daily) | **+$181.07M** — IBIT +$138.90M, FBTC +$21.07M, MSBT +$7.4M, ARKB +$3.64M, BITB +$3.5M, Mini +$6.56M — **no fund negative** | CryptoBriefing/CoinDesk daybook | Jul 15 |
| Jul 15 (daily) | **NOT FOUND** — no BTC-specific figure located as of report time; treat as PENDING | — | — |
| July MTD | Choppy — swinging in/out nearly every session; no clear direction confirmed | CoinDesk daybook | Jul 15 |
| YTD 2026 | ≈ **−$5.8B** (June alone ≈ −$4.5B) — revised from the ≈−$5.4B prior-report figure as later-month data firmed up | multiple aggregators | mid-Jul |

> Two data points in a row are now green-adjacent (Jul-14 solidly green; Jul-15 unconfirmed but no source flagged a large outflow). This is **not yet** the ≥5-session sustained-inflow bar the Exit Framework and gate-4 continuity both require — trailing-month aggregate remains deeply negative (June alone was ~5.8% of the ~$78B AUM, several multiples of the 2% gate threshold), so gate 4 / capitulation-leg (c) hold ✅ on the trailing-month basis. **Watch**: two more green sessions would be meaningful.

### On-Chain

| Metric | Value | Source | Timestamp |
|---|---|---|---|
| MVRV-Z | **0.33** (ratio 1.24, realized ~$52,451) | ahasignals.com | Jul 14 |
| Funding | **positive, mild** — CF Benchmarks Kraken Perp Funding Index **10.17% annualized** | CF Benchmarks | Jul 13 |
| 24h liquidations (Jul 15) | **$376M total crypto; BTC $113M, ~$105M short-side (~93% short)** | TechTimes/CoinGlass | Jul 15 |
| LTH supply (30d) | **RISING**, net accumulation ~50–100K BTC (shifted from distribution to accumulation) | Glassnode/CoinDesk "Great Rotation" | Jul 14 |
| Exchange reserves | **FALLING**, multi-year low — tracker divergence: CoinGlass/CryptoQuant basis ~2.40–2.43M BTC (Jul 14) vs a broader-tracker reading ~2.67–2.70M BTC (lowest since Nov-2018, undated within July) — methodology difference between trackers, not a reversal; direction consistent | CoinGlass/CryptoQuant; Phemex | Jul 14 (dir.) |
| Coinbase premium | **STALE** — last confirmed negative streak read ~50 consecutive days through Jul 7–8; no update found past that date | CryptoBriefing (last confirmed) | Jul 7–8 (stale, 9 days) |
| MSTR / Strategy | **843,775 BTC unchanged** — zero purchases week of Jul 6–12; raised $466.7M via stock sales; adopted a new "Digital Credit Capital Framework" permitting monetization of **up to 20,800 BTC (~2.5% of holdings)** for dividends/debt service — a structural softening of the prior "never sell" posture | CoinDesk/The Block | Jul 13 |

Jul-15's liquidation flush was **again short-dominated** (~93% short, same pattern as Jul-14's post-CPI flush) — a second consecutive squeeze day, not a long-capitulation event. This is the fact that independently triggers the mandatory standalone Flying Rocket report (see §4).

### Correlation Regime

30-day BTC–SPX correlation **not computed live this cycle** (The Block/CoinGlass dashboards JS-gated, unreadable via fetch) → risk-on surcharge defaults **OFF**. Stale context only: ~0.74 in March 2026 (2026 high); qualitative "elevated throughout 2026."

### Macro (post dual soft-print)

June PPI: **headline −0.3% MoM vs 0.0% consensus** (largest monthly drop since Aug-2025), **YoY 5.5% vs 6.2% consensus** (down from 6.0% May); **core +0.2% MoM vs ~0.3–0.4% consensus**, **core YoY 4.7% vs 5.2% consensus** — both miss to the downside, goods −1.4% (gasoline −12%), services +0.2% ([BLS](https://www.bls.gov/news.release/pdf/ppi.pdf), Jul 15). Fed hold odds firmed further: CME FedWatch July-hold in the low-to-mid 80s to high-80s across aggregators (dispersion by source/snapshot; directionally, hike odds fell further from Tuesday's ~85–86% hold toward roughly 83–88%). NY Fed's Williams: "inflation has peaked," expects ~3.25% by year-end.

Real 10y TIPS **2.33%** (Jul-14, essentially flat vs Tuesday's 2.32% cycle high) · VIX **15.67** (Jul-15, −7.28%/5d — falling further) · DXY **100.47** (Jul-16, −0.46%/5d) · **Brent $84.64 (+10.93%/5d — a significant escalation)** · S&P 7,572.40 (+1.2%/5d) · Nasdaq 26,269.23 (+1.54%/5d) · gold $4,033.70 (−2.35%/5d).

---

## 3. Critical Developments — BTC

- **June PPI came in soft, confirming the CPI signal.** Headline −0.3% MoM (vs flat consensus) and core +0.2% (vs ~0.3–0.4% consensus) — a second consecutive downside inflation surprise. This is the "dual inflation miss" framing multiple outlets used ([BLS](https://www.bls.gov/news.release/pdf/ppi.pdf), [TechTimes](https://www.techtimes.com/articles/320631/20260715/bitcoin-breaks-65k-dual-inflation-miss-short-squeeze-amplified-move.htm), Jul 15).
- **BTC squeezed to an intraday $65,507 on the print, framed explicitly as a short-squeeze-amplified move** — not a clean distribution rally. Price has since consolidated back to ~$64,648, still comfortably above the $63,094.65 200-week SMA (+2.46%).
- **The Iran/Hormuz conflict escalated, not de-escalated, since Tuesday's "toll withdrawn" framing.** New US strikes hit Iranian targets Jul-15; three merchant ships were hit in the Strait of Hormuz (2 UAE tankers, 1 fatality); Brent is **up 10.93% over 5 sessions** to $84.64 — the oil tail risk that Tuesday's report treated as "easing" has instead widened ([CNN](https://www.cnn.com/2026/07/15/world/live-news/iran-war-trump), Jul 15). This is the single most important reason gate 9 stays at ⚠️ rather than upgrading to ✅ despite two soft inflation prints.
- **MSTR/Strategy's capital framework shifted.** The company adopted a "Digital Credit Capital Framework" permitting sale of up to 20,800 BTC (2.5% of its 843,775-BTC holding) for dividends/debt service — a real, if modest, softening of its "never sell" posture (holdings themselves are unchanged this week). Not a scored input, but a watch item for the holder leg's largest single corporate holder.
- **CLARITY Act: a pivotal Senate hearing lands Jul-17 (tomorrow)** — flagged by multiple outlets as potentially decisive for the bill's 2026 prospects; still stalled on three disputes (insider-trading disclosures, DeFi liability, stablecoin-yield loophole).
- **On-chain base continues to strengthen modestly.** MVRV-Z ticked down to 0.33 (deeper value than Tuesday's 0.35 at a similar price — realized price basis is grinding higher more slowly than spot); LTH accumulation and falling exchange reserves both intact.

---

## 4. Fallen Knives Composite Score (BTC) — 13 / 20

| Category | Max | Score | Rubric Basis |
|---|---|---|---|
| **Sentiment Extreme** | 5 | **3** | 3-day F&G **24** → ≤25 band → 3 (`tools/compute.mjs band fk-sentiment 24`). Up from 2 — the daily prints did not lift with the price rally; fear deepened slightly (25.33→24). |
| **Momentum Exhaustion** | 4 | **2** | Weekly Wilder RSI-14 = **39.77** (262 completed closes, Yahoo weekly, last completed week Jul-13) → ≤40 band → 2 (`tools/compute.mjs band fk-momentum 39.77`). Unchanged. |
| **Valuation** | 5 | **4** | MVRV-Z **0.33** (ratio 1.24, realized ~$52,451) → ≤0.5 band → 4 (`tools/compute.mjs band fk-mvrv 0.33`). Unchanged band; deeper within it. |
| **Capitulation Evidence** | 3 | **1** | 1/3: (a) liquidations ❌ (Jul-15 flush was ~93% SHORT-side again — a second squeeze day, not a long capitulation); (b) funding ❌ (positive ~10.17% ann., no negative flip); (c) **ETF outflows ✅ — trailing-month basis still deeply negative (June alone ≈5.8% of AUM) despite Jul-14's green day and Jul-15's unconfirmed print.** |
| **Holder Behavior** | 3 | **3** | Both sub-legs ✅: LTH supply rising 30d (net accumulation) + exchange reserves falling to a multi-year low (tracker-methodology spread disclosed above, direction unanimous). |
| **TOTAL** | **20** | **13** | Raw 13 → **adjusted 13** (integer; no rounding tie; no correlation surcharge — not computed → OFF). |

#### Confirmation Gates (4 / 9 — full board, no N/A)

| # | Gate | Bucket | Status |
|---|---|---|---|
| 1 | Sentiment ≤15 × ≥7 daily prints | [V] | ❌ (≤15 streak = 0) · *relight: F&G daily prints ≤15 for 7 days* |
| 2 | Weekly RSI <30 | [V] | ❌ (39.77) · *relight: RSI <30, ~9.8 pts away* |
| 3 | Valuation cheap (MVRV-Z <1) | [V] | **✅** (0.33) |
| 4 | ETF outflows ≥2% AUM trailing month | [V] | **✅** (trailing-month basis still deeply negative despite the Jul-14 green day) |
| 5 | Hash Ribbon buy signal | [T] | ❌ (no confirmed buy cross; not independently re-verified this cycle — carried forward) · *relight: miner-capitulation recovery / hash-ribbon buy cross* |
| 6 | Price within ±8% of 200-week | [T] | **✅** (+2.46% above $63,094.65) |
| 7 | Capitulation volume spike (top-decile/>3σ) | [V] | ❌ (Jul-15 flush was again SHORT-liquidation-dominated) · *relight: a >3σ/top-decile LONG-liquidation flush* |
| 8 | LTH accumulation / holder stabilizing | [V] | **✅** (LTH accumulating, reserves falling) |
| 9 | Macro catalyst neutral-to-positive | [T] | ⚠️ **(unchanged)** — two soft inflation prints in a row is a genuine positive, but the Iran/Hormuz conflict escalated (3 merchant ships hit, Brent +10.93%/5d) over the same window — a clean pass requires the oil/geopolitical leg to improve too, and it moved the wrong way · *relight: oil de-escalation (still the missing leg) + a real-yield rollover (2.33%, essentially flat)* |

**Count: 4 ✅ (gates 3, 4, 8 = [V]; gate 6 = [T]).** Unchanged from Jul-14. Three [V] + one [T].

**Companion Flying Rocket (computed, Hard Rule 5):** FR composite = **0/20 (0/8 attainable, cap-bound at −48.77% below the 1-year high)**, 0/8 gates. FR 0 < 9 → the standing FR≥9 tripwire is not lit. **However, a separate mandatory trigger fires independently: ≥$100M of Jul-15's liquidation volume was short-side (~$105M of BTC's $113M total, ~93%)** — this requires a full standalone BTC Flying Rocket report this session (produced alongside this one: `btc_flying_rocket_20260716_0330.md`). Per the vacuity rule: **cross-validation structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).**

---

## 5. Probability Matrix — Derived From Score (Adjusted 13 → 11–14 band)

Baseline 11–14: Rally 30 / Range 35 / Retest 22 / Bear 13. **Trend residual — modest continuation shift applied:** BTC has now spent parts of three sessions above the 200-week SMA (Jul-14 intraday reclaim, held through Jul-15's extension to $65,507, still +2.46% above as of Jul-16) on the back of two consecutive soft-inflation prints — a firmer, if still short, repair than Jul-14's single-day intraday poke. A **+3pp shift to Rally** applies (from Retest −2, Bear −2), held modest because (i) the reclaim, while now multi-session, is nowhere near the ≥15-weekly-close structural bar this framework's sister rubric (FR §5) uses for a "confirmed" uptrend, and (ii) the Iran/oil escalation is a live, worsening offset. Canonical $64,648.

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|
| **Rally** | **33%** | $66,000 – $72,000 (mid $69,000) | A weekly-confirmed close above $63,095 on Sunday; ETF flows sustain green ≥5 sessions; oil de-escalates |
| **Range** | **35%** | $62,000 – $66,000 (mid $64,000) | Consolidates around the reclaimed 200-week while ETF flows and oil resolve |
| **Retest** | **21%** | $58,000 – $62,000 (mid $60,000) | Iran/oil re-escalates further; a weekly close back below $63,095 |
| **Bear** | **11%** | $50,000 – $58,000 (mid $54,000) | Forced liquidation through the retest zone on an oil shock into a hawkish surprise |

Sum 100%. **Weighted EV = $63,710** (`tools/compute.mjs ev`). Spot $64,648 → **EV-vs-spot ≈ −1.45%.** **Realized trailing-2-week: ≈ +5.6%** (~$61.2K → $64,648). The positive realized tape continues to contradict the negative at-spot EV — the rally has priced most of the near-term edge out; the edge lives in the $58–62K retest ladder.

**Sum-check (mandatory):** 33+35+21+11 = 100 ✅; EV recomputed from printed cells = **$63,710**, matches (Δ 0%) ✅. Rally 33% < modal Range 35% and ≤50% ✅ (the multi-session 200-week reclaim across two soft-inflation prints is the cited trend event permitting the above-baseline Rally weight; it stays below modal because the structural repair is not weekly-confirmed and the oil leg is actively worsening).
**EV-floor consistency:** binds only at score ≥15 + extreme fear → **N/A** (score 13). Clean.
**Terminal-vs-extreme reconciliation:** not triggered this report — the live §5 residual here is an *upward* shift (trend repairing), not the downtrend-residual case the rule is scoped to; no "base-building" language is used on the modal Range row regardless.

---

## 6. Deployment Strategy — BTC

**Confirmed deployed: 10% (Phase 1A @ ~$65K, MTM +ex negligible at $64,648 — essentially flat). Dry powder: 90%.** Splits 10 / 15 / 30 / 45.

**⚑ Deep-Value Override status: NOT ARMED.** Score 13 < 15; price is not making a fresh lower-low; the override's price condition is not met and moving further away.

| Phase | Size | Trigger / Gates (denominator 9) | Status |
|---|---|---|---|
| **1A** | 10% | score ≥10 + ≥3/9 gates (≥2 [V]) | **DEPLOYED ~$65K** (MTM ≈ flat) |
| **1B** | 15% | score ≥13 + ≥5/9 gates (≥3 [V]) OR Override | **GATE-BLOCKED (score condition now MET at 13; only 4/9 gates lit, need ≥5)** — a material change from Tuesday's SCORE-block; zone $58,000–61,500 unchanged |
| **2 — Conviction** | 30% | score ≥15 + ≥6/9 (≥3 [V]) + corr <0.8 | **FROZEN** (score 13) |
| **3 — Generational** | 45% | score ≥17 + ≥7/9 (≥4 [V]) OR (Override + weekly capitulation candle) | **DRY POWDER** |

This is the first time in the series the score has cleared 13, but the honest read is that the binding constraint simply moved from the score line to the gate line — **1B is one dark gate away, not multiple score points away.** The fastest realistic relight path is gate 9 (macro, currently ⚠️): a genuine oil de-escalation (still absent) would plausibly flip it to ✅ and bring the gate count to 5/9, satisfying the [V]-floor (3 [V] already lit: 3, 4, 8) and unlocking 1B without any further score movement. **HOLD 1A, no add, no chase** — the gate condition, not the score, is now the operative discipline.

**Stop Philosophy.** Compound thesis stop: **≥2 consecutive weekly closes below $55,000 AND score back below 12.** The score's move to 13 puts this stop marginally further from live (one more point of cushion) — not a migration, a market/score move. Time stop: reassess Phase 1A if the thesis hasn't worked through Q3 2026. Narrative-break exit independent.

**Stop Migration Ledger:** **no migration this report** — all parameters UNCHANGED (compound $55K/score<12; catastrophic $50K; deepest-zone floor $54K).

**Stop-vs-buy-zone coherence check (mandatory):** deepest named buy-zone floor = **$54,000** (the 1B/contingency band, unchanged). Catastrophic **$50,000 < $54,000 → PASS** (`tools/compute.mjs stop-coherence`). Max-drawdown-to-thesis-stop from the ~$65K 1A basis: at $55K = −15.4%.

**Checkpoint (structural, gate 6):** **Sun Jul-19** — the weekly candle closes Sun 24:00 UTC (crypto trades 24/7; a real close). Gate 6 holds iff the close is within ±8% of the (rolling) 200-week SMA (currently $63,094.65, +2.46% above spot, well inside). Spot is **$1,553.35 above the SMA = 1.02× the $1,523.10 5-day ADR** (`tools/fetch.mjs`, 5 full sessions Jul-12→16, no exclusions needed — crypto trades weekends). **Next tier-1 macro release before the checkpoint: NONE** — the 5-trading-day forward window (Jul-17 through Jul-23) contains no CPI/PPI/NFP/FOMC/PCE print (next CPI Aug-12, NFP Aug-7, FOMC Jul 28–29 and PCE Jul-30 both fall outside the window). The one dated near-term event is the **Jul-17 CLARITY Act Senate hearing** (non-macro, crypto-specific — flagged in the watchlist, not a tier-1 release).

**Dry powder yield benchmark:** ~4.3% (3-month T-bill) / ~4.5% (USDC). 90% dry earns the benchmark.

---

## 7. Exit / Trim Framework — BTC

Local campaign peak = highest adjusted score since first fill = **16**; now 13 → drop of **−3** (moving closer to, not further from, peak vs Tuesday's −4).

| Trigger | Status |
|---|---|
| Score drops ≥6 from local peak (16) | **No** (−3; arms at score ≤10) |
| F&G ≥75 7d AND weekly RSI >70 | **No** (Extreme Fear; RSI 39.77) |
| MVRV-Z >3 or drawdown <10% + vertical | **No** (MVRV-Z 0.33; −48.72% off ATH) |
| Score ≤3 AND price ≥40% above cost | **No** (score 13; MTM ≈ flat) |
| ETF outflows ≥3% AUM after a sustained-inflow regime | **No** (no prior ≥5-session inflow regime this campaign) |
| Narrative break | **No** |

**Status: no trim. Remaining position 10% (Phase 1A).**

---

## 8. Critical Watchlist — BTC

| Time (EST) | Event | Impact |
|---|---|---|
| **Wed Jul 15, 8:30 ✓** | **June PPI — SOFT** (−0.3%/5.5% headline, +0.2%/4.7% core, both miss to the downside) | **Resolved bullish** — second soft print, confirms CPI disinflation |
| **Thu Jul 16 (today)** | Jul-15 BTC ETF daily flow (unconfirmed as of report time) | Would be the third data point on whether the institutional bid is turning |
| **Fri Jul 17** | CLARITY Act Senate hearing (flagged as pivotal for 2026 passage) | Regulatory clarity — gate-9 adjacent, non-macro |
| **Sun Jul 19** | Weekly close vs $63,094.65 200-week | The real gate-6 test — spot is 1.02× ADR above the line |
| Jul 28–29 | FOMC (Jul 29, 2:30 pm) — hold odds firming | Post-window rate-path event |
| Jul 30 | PCE (June data) + Q2 GDP advance | Post-window, Fed's preferred gauge |
| Aug 7 / Aug 12 | NFP (Jul jobs) / CPI (Jul data) | Next tier-1 prints, both outside the 5-day window |

**Tier-1 calendar lock:** the 5-trading-day forward window (Jul-17 through Jul-23) contains **no** CPI/PPI/NFP/FOMC/PCE release — verified against BLS/BEA/Fed schedules. PPI (Jul-15) is now realized; no further tier-1 sits between this report and the Jul-19 weekly checkpoint.

---

## 9. Bull vs Bear Scorecard — BTC

**Bull:**
1. ✅ Second consecutive soft inflation print (PPI Jul-15: −0.3%/5.5% headline, +0.2%/4.7% core) confirms the CPI disinflation signal
2. ✅ BTC held above the $63,094.65 200-week SMA across multiple sessions (+2.46%), not just an intraday poke
3. ✅ MVRV-Z 0.33 — deepened slightly, still near the floor
4. ✅ LTH accumulating; exchange reserves falling to a multi-year low — structural floor intact
5. ✅ Sentiment 3-day avg deepened (25.33→24) despite the rally — a genuine fear signal, not whipsaw
6. ✅ ETF flows showed a clean green day (Jul-14 +$181M, every fund positive)

**Bear:**
1. ❌ Iran/Hormuz conflict escalated (3 merchant ships hit, Brent +10.93%/5d) — the oil tail risk widened, not eased
2. ❌ Jul-15's liquidation flush was again ~93% short-side — squeeze fuel spent twice in a row, not long capitulation
3. ❌ Jul-15 BTC ETF flow unconfirmed — only one clean green day so far, well short of the ≥5-session sustained-inflow bar
4. ❌ Real yields still ~2.33% (essentially cycle-high, flat)
5. ❌ Coinbase premium data stale (9 days); MSTR adopted a framework permitting future BTC sales (2.5% of holdings) — a subtle softening of a prior "never sell" stance

**Net: 6 bull / 5 bear — bull-leaning but within 1 of balanced.** With |EV-vs-spot| 1.45% < 2%, the **Verdict-Confidence Collar is engaged**: no regime-resolution claims.

---

## 10. Change Log — BTC (vs Jul 14, 2:30 PM)

| Factor | Previous (Jul 14, 2:30 PM) | Current (Jul 16, 3:30 AM) | Direction |
|---|---|---|---|
| Canonical spot | ~$64,500 | $64,648 | → flat/slightly up |
| Adjusted score | 12 | **13** | ↑ crosses Phase-1B's score threshold |
| Sentiment leg | 2 (3d avg 25.33) | **3** (3d avg 24) | ↑ fear deepened despite rally |
| Momentum leg | 2 | 2 | → |
| Valuation leg | 4 (MVRV-Z 0.35) | 4 (MVRV-Z 0.33) | → same band, deeper |
| Capitulation leg | 1 | 1 | → |
| Holder leg | 3 | 3 | → |
| **June PPI** | pending | **SOFT: −0.3%/5.5% headline, +0.2%/4.7% core** | ↓ inflation (bullish resolution) |
| Fed hold odds | ~85.6% | firmed further (~83–88% range across aggregators) | ↑ |
| 200-week reclaim | +2.60% intraday (unconfirmed) | +2.46%, held across 3 sessions | ↑ firmer, still not weekly-confirmed |
| Iran/oil | toll withdrawn, Brent $84.39 | **escalated: 3 merchant ships hit, Brent $84.64 (+10.93%/5d)** | ↓ worse |
| Gate 9 (macro) | ⚠️ | ⚠️ (unchanged — positives and negatives both moved) | → |
| ETF flows | Jul-13 −$424.63M; MTD ~−$300M | Jul-14 +$181.07M (green); Jul-15 unconfirmed | ↑ tentative |
| Liquidations | 93% short (Jul-14, 1hr) | 93% short again (Jul-15, 24h, $113M BTC) | → repeat squeeze pattern |
| Stops | $55K/<12 · $50K cat · $54K floor | identical | → no migration |
| Companion FR | 1/20, cap-bound | 0/20, cap-bound — **standalone report triggered (≥$100M short liqs)** | → |
| 1B blocking reason | SCORE (12<13) | **GATE (4/9 lit, need 5/9)** | ↑ one condition down |

---

## 11. Strategic Verdict — BTC

**Adjusted score 13/20 · Weighted EV $63,710 · EV-vs-spot −1.45% · sentiment 3-day F&G 24 (Extreme Fear) · stance: HOLD Phase 1A (10%), 90% dry, no chase.**

The number that matters most this report is not the price — it is that the score cleared thirteen for the first time in this series, and it did so on a genuinely constructive input: the fear print deepened even as the tape rallied on two consecutive soft-inflation surprises. That is the opposite of the whipsaw pattern that has dogged this campaign since May, where relief rallies mechanically drained the fear score. Momentum and valuation held their bands, capitulation and holder legs were unchanged, and the one point of movement came from sentiment genuinely refusing to lift. If the fear reading holds through another session or two of price strength, that would be a more durable signal than anything a single squeeze day can produce.

The practical consequence is smaller than the headline suggests. Phase 1B's score condition is now satisfied, but the gate count is not — four of nine confirmation gates are lit against a five-gate floor, and the honest read is that the constraint simply relocated rather than disappeared. The most reachable dark gate is macro (gate 9), and it did not light this week for a specific, disclosed reason: the Iran/Hormuz conflict escalated over the same window the inflation data improved, with three merchant ships hit and Brent up nearly eleven percent in five sessions. Two soft prints bought real credibility on the rate path; they did not buy an all-clear on the geopolitical leg, and the framework is right to withhold the gate until both legs clear together.

Twice now — Tuesday's CPI and Wednesday's PPI — the liquidation data has shown the same signature: a squeeze of crowded shorts, not a flush of long capitulation. That pattern is worth taking seriously as information about market positioning (consensus has been leaning short into a fear tape and keeps getting run over), but it is not the kind of capitulation evidence the framework's own gates are built to reward, and I am not treating it as such. The collar remains engaged — the scorecard is a hair off balanced and the EV magnitude is inside the 2% threshold — so this stays a description of where the evidence sits, not a call that any regime has resolved.

**Action items:**
1. **HOLD Phase 1A (10%, ~$65K, MTM ≈ flat).** No add — 1B is now gate-blocked (4/9, need 5/9), not score-blocked; the fastest path is gate 9 relighting on a genuine oil de-escalation. 90% dry.
2. **Do not chase.** EV is negative at spot (−1.45%); the edge lives in the $58–62K retest ladder, not here.
3. **Watch for a third consecutive constructive ETF session** — Jul-14 was clean green; Jul-15 is unconfirmed. Two more would approach (not yet meet) the ≥5-session sustained-inflow bar.
4. **Sunday Jul-19 weekly close vs $63,094.65** remains the gate-6 decider; spot currently sits 1.02× the 5-day ADR above the line.
5. **Track the Jul-17 CLARITY Act Senate hearing** — a regulatory win would be the most direct path to lighting gate 9 outright, independent of the oil/macro mix.
6. Keep stops unchanged: compound **$55K/score<12**, catastrophic **$50K** (< $54K deepest-zone floor, PASS).

> **The Pattern:**
> **IF** BTC holds a Sunday weekly close above $63,095 **AND** oil de-escalates from here **→ THEN** gate 9 plausibly relights, gate count reaches 5/9, and Phase 1B unlocks at its already-satisfied score of 13 — the first genuine gate-driven unlock of this campaign.
> **IF** the Iran/Hormuz conflict escalates further and Brent pushes materially higher **→ THEN** gate 9 stays dark regardless of the score, and a hawkish real-yield response could pull BTC back into the $58–62K retest ladder — where the EV edge actually sits.
> **IF** the fear print (3-day avg) continues to hold or deepen through further price strength **→ THEN** that is the more durable signal this report is watching for — a fear score that survives a rally is worth more than one that whipsaws with it.

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-07-16",
  "spot": { "value": 64648, "source": "median CoinGecko live $64,648 / Yahoo BTC-USD last close $64,622.07 (tools/fetch.mjs, Jul 16 07:13 UTC); divergence 0.04%, genuine live agreement" },
  "score": {
    "legs": { "sentiment": 3, "momentum": 2, "valuation": 4, "capitulation": 1, "holder": 3 },
    "raw": 13, "adjusted": 13, "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 4, 6, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 33, "low": 66000, "high": 72000 },
      { "name": "Range", "p": 35, "low": 62000, "high": 66000 },
      { "name": "Retest", "p": 21, "low": 58000, "high": 62000 },
      { "name": "Bear", "p": 11, "low": 50000, "high": 58000 }
    ],
    "stated_ev": 63710, "vs_spot_pct": -1.45
  },
  "deployment": {
    "deployed_pct": 10, "dry_pct": 90,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~65000 (MTM approx flat at 64648)" },
      { "phase": "1B", "pct": 15, "entry": "58000-61500 (GATE-blocked: 4/9 lit, need 5/9; score condition 13>=13 now MET)" },
      { "phase": "2", "pct": 30, "entry": "frozen" },
      { "phase": "3", "pct": 45, "entry": "dry" }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 }
  },
  "verdict": "HOLD Phase 1A (10%, ~$65K, MTM approx flat at $64,648); 90% dry. Score CROSSED 13 for the first time this series (up from 12), driven by sentiment leg 2->3 (3d F&G 25.33->24, fear deepened despite the rally) with momentum/valuation/capitulation/holder unchanged. June PPI (Jul-15) came in SOFT (headline -0.3%/5.5% vs 0.0%/6.2% cons; core +0.2%/4.7% vs 0.3-0.4%/5.2% cons) -- second consecutive soft inflation print. BTC squeezed to an intraday $65,507 high (short-squeeze-amplified per press), settled ~$64,648, held above the $63,094.65 200-week SMA across multiple sessions (+2.46%). Phase 1B's SCORE condition (>=13) is now MET for the first time -- but GATE condition (>=5/9) is NOT (4/9 lit: gates 3,4,6,8) -- 1B is now GATE-blocked, not score-blocked, a material framing change. Gate 9 (macro) stays >=9 unchanged at WARN: two soft inflation prints offset by an ESCALATING Iran/Hormuz conflict (3 merchant ships hit, Brent +10.93%/5d to $84.64) -- oil leg moved the wrong way. Jul-15 liquidation flush again ~93% short-side ($105M of $113M BTC total) -- second consecutive squeeze day, NOT long capitulation; this independently triggers a MANDATORY standalone Flying Rocket report (>=$100M short liqs condition), produced this session alongside this report. Companion FR computed at 0/20 (0/8 attainable, cap-bound -48.77% off 1y high), 0/8 gates -- structurally consistent (cap-bound; both>=12 unfalsifiable). ETF flows: Jul-14 clean green (+$181.07M, every fund positive), Jul-15 unconfirmed/NOT FOUND -- not yet the >=5-session sustained-inflow bar; trailing-month basis still deeply negative (gate 4 / leg (c) hold). MVRV-Z 0.33 (deepened slightly). LTH accumulating, reserves falling to multi-year low (tracker-methodology spread disclosed: CoinGlass ~2.40-2.43M vs broader-tracker ~2.67-2.70M, direction unanimous). Coinbase premium STALE (9 days, no update past Jul 7-8). MSTR unchanged at 843,775 BTC but adopted a framework permitting sale of up to 20,800 BTC (2.5%) for dividends/debt service -- a structural softening worth watching, not yet a leg change. No trim (peak 16->13=-3, moving closer to peak). Stops unchanged (no migration). EV -1.45% at spot vs +5.6% realized 2wk. Scorecard 6-5 + |EV|<2% -> Collar engaged; no regime-resolution claims. Deep-Value Override NOT ARMED (score 13<15).",
  "inputs": {
    "weekly_rsi": 39.77, "rsi_closes": 262, "rsi_source": "tools/fetch.mjs Wilder-14, Yahoo weekly, last completed week 2026-07-13",
    "mvrv_z": 0.33, "mvrv_ratio": 1.24, "realized_price": 52451, "fng_3d": 24, "fng_spot": 25, "fng_le15_streak": 0,
    "drawdown_pct": -48.72, "high_1y_pct_below": 48.77, "sma_200w": 63094.65, "sma_200w_vs_spot_pct": 2.46, "adr5": 1523.10,
    "ppi_june": { "headline_mom": -0.3, "headline_yoy": 5.5, "core_mom": 0.2, "core_yoy": 4.7, "cons_headline_mom": 0.0, "cons_headline_yoy": 6.2, "cons_core_mom": 0.3, "cons_core_yoy": 5.2, "verdict": "soft, both beat to the downside, second straight miss after CPI" },
    "fed_hold_odds_post_ppi": "firmed further, ~83-88% hold range across aggregators (dispersion by source/snapshot)",
    "etf_btc_daily_jul13": -424660000, "etf_btc_daily_jul14": 181070000, "etf_btc_daily_jul15": "NOT FOUND / pending", "etf_ytd": -5800000000, "etf_june": -4500000000,
    "liquidations_jul15_total_crypto": 376000000, "liquidations_jul15_btc": 113000000, "liquidations_jul15_btc_short": 105000000, "liquidations_jul15_btc_short_pct": 93,
    "funding_ann": 10.17, "funding_source": "CF Benchmarks Kraken Perp Funding Index, Jul-13",
    "lth_30d": "rising, net accumulation ~50-100K BTC (Great Rotation)", "exchange_reserves": "falling, multi-year low; tracker spread: CoinGlass/CryptoQuant ~2.40-2.43M vs broader ~2.67-2.70M (methodology, not reversal)",
    "coinbase_premium": "STALE (no update past Jul 7-8, last read negative ~50d streak)",
    "mstr_btc": 843775, "mstr_note": "unchanged holdings; adopted Digital Credit Capital Framework permitting sale of up to 20,800 BTC (2.5%) for dividends/debt service",
    "iran_oil": "ESCALATED: new US strikes Jul-15, 3 merchant ships hit in Hormuz (2 UAE tankers, 1 fatality); Brent $84.64 (+10.93%/5d)",
    "real_yield_10y_tips": 2.33, "vix": 15.67, "dxy": 100.47, "brent": 84.64, "spx": 7572.40, "ndx": 26269.23, "gold": 4033.70,
    "corr_spx_30d": "not computed this cycle -> risk-on surcharge OFF",
    "companion_fr": { "composite": 0, "gates": 0, "cap_bound": true, "standalone_report_triggered": true, "trigger_reason": ">=$100M short-side liquidation volume Jul-15 (condition iii)" }
  }
}
```
