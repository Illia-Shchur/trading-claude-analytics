---
name: flying-rocket-analytics
description: "Proprietary crypto market analysis framework for identifying optimal SHORT entry points during periods of extreme euphoria, overextension, and structural distribution. The inverse companion to fallen-knives-analytics. Works for any crypto asset (BTC, ETH, SOL, major alts) with asset-appropriate substitutions; smaller alts require explicit confirmation due to short-borrow and squeeze risk. Use whenever the user asks for a Flying Rocket update/score/analytics, where to short, when to short, top-signal assessment, euphoria/blow-off readout, distribution analysis, short deployment strategy, or any variant of 'flying rocket [asset]' / 'rocket update'. This skill MUST fetch live data from the internet before any analysis — never rely on stale or memorized data. Output is a structured multi-section report with composite scoring, derived probability matrix, phased short-deployment gates, mandatory stops, carry-cost ledger, and a symmetric cover/exit framework."
---

# Flying Rocket Analytics — Crypto Short / Distribution Framework

## Overview

Flying Rocket is the inverse companion to Fallen Knives. It identifies points where crypto exposure should be **shorted** (or, for cash-only operators, **rotated out of**) because the asset is exhibiting euphoria, valuation extreme, momentum overextension, and structural distribution — and where the reward-to-risk for shorting is asymmetrically favorable.

**Critical asymmetry — read first:**

Shorting crypto is **not** the mirror of going long. The asymmetries are:

1. **Unlimited theoretical loss** vs capped at notional on longs
2. **Persistent upward drift** in BTC/major-cap history works against short carry over time
3. **Funding/borrow bleed** — paying 0.01–0.10% per 8h on perps compounds; spot-borrow rates can hit double-digits annualized on stressed assets
4. **Short-squeeze mechanics** — concentrated short interest + thin order books + positive catalyst = parabolic adverse moves
5. **Asymmetric news risk** — surprise ETF approvals, regulatory wins, M&A, "Trump tweet" effects can gap shorts violently overnight in a 24/7 market

The framework treats shorting with **more humility than longing**: smaller maximum position size (cap **50% total short notional** of the dedicated short book), mandatory price **and** time stops at every phase, time-decay max-hold limits, and explicit carry-cost accounting.

**Where the asymmetry tax is paid (revised 2026-07-27, owner-directed).** Until this revision the tax was paid mostly at the *entry threshold* — a 2-point spread over the long side, plus a hard cap that made a short structurally impossible outside a top. A twelve-fall ETH backtest (`reports/fr_eth_fall_capture_backtest_20260727.md`) showed what that bought: of twelve ≥10% ETH declines in the trailing year, the framework could reach an entry on **one**, and seven were excluded by the cycle cap before a single input was scored. The tax is now paid where it actually protects capital — **stops, size, clock, and the ratchet** — rather than at the door. Entry thresholds come down; risk control gets stricter, not looser. Nothing in this revision relaxes a stop, a time limit, a size cap, or a cover trigger, and the analyst channel is *taxed* on all four.

### The two channels

A crypto asset falls ≥10% in two structurally different ways, and the backtest found them in near-equal number (ETH 6/6 in-sample, 13/13 over four prior years; BTC 15/9):

| | **Channel A — Distribution** | **Channel B — Bear Continuation** |
|---|---|---|
| Regime | at/near the highs — price within 20% of the 1-year ATH | confirmed bear — below a falling 200dma, >20% off the 1-year ATH |
| What you are shorting | euphoria and distribution *at a top* | an exhausted counter-trend **rally** inside a downtrend |
| Signature | absolute extremes: F&G ≥80, weekly RSI >70, MVRV-Z >3 | *relative* extremes: a 15–35% bounce off the lows dying into resistance, daily RSI recovered while weekly RSI stays <50 |
| Rubric | §4A (unchanged in kind) | §4B (new) |
| Max size | 50% of short book | **30% of short book** |
| Deepest phase | Phase 3 | **Phase 2 — no Phase 3, ever** |

Channel B exists because the old cap answered "is this a top?" and, on a correct "no," concluded "therefore no short" — a non-sequitur that made ~half of all shortable declines invisible. **The cap is not deleted; it is forked.** A >20%-off-ATH tape still cannot be scored as distribution. It can now be scored as what it is.

**Channel exclusivity:** exactly one channel is active per report. Determine it in §2.5, print it in the header, and score only that channel's rubric. Positions opened under one channel keep that channel's rules for their whole life — a Channel B tranche does not inherit Channel A's wider stops if the regime later repairs (see §6, channel-migration rule).

**Cross-validation rule:** Flying Rocket and Fallen Knives scores should be **inversely related** for the same asset at the same timestamp. If **both score ≥12** (never a *sum* threshold — the May-14 report's "sum = 8 vs 24" framing is wrong and appears nowhere in these skills), the framework is internally inconsistent — pause and re-examine inputs before acting. **Channel A only, from 2026-07-27:** in Channel B the two frameworks score different objects on different horizons and a both-≥12 reading is expected rather than anomalous; the FK ≥12 force-cover governs there instead. See §7 for the full resolution — the force-cover itself is unchanged in both channels.

**Mandatory computed companion score (Jun 2026):** the Cross-Check line in the header must carry the **computed** Fallen Knives composite (number + gate count, from the same live data fetch) — **estimated/eyeballed companion numbers are prohibited.** *(In May–Jun 2026 the long-side series leaned on a sourceless "FR ~3–4" for weeks; do not repeat that on either side.)* If the companion cannot be computed, say so explicitly and treat the cross-validation as **unverified** — do not assert inverse consistency you did not measure.

**Asset scope:** BTC default. ETH, SOL, major alts (top 20 mcap) with metric substitution. **Smaller alts → require explicit user confirmation** due to short-borrow availability and squeeze risk.

## CRITICAL: Real-Time Data is Non-Negotiable

Before writing ANY analysis, fetch live data for ALL categories. Never use memorized or cached data. Always search, verify, cite sources with timestamps.

### Required Data Fetches

1. **Asset price + intraday volatility + 1-year ATH** — CoinDesk, CoinGecko, Yahoo Finance, asset's primary exchange. Note distance from 1-year ATH; this drives the phase-of-cycle cap (see §4).
2. **Sentiment (greed side)** — Alternative.me F&G, CoinMarketCap, social heat (LunarCrush, Santiment if available), retail-leverage proxies.
3. **Spot ETF flows** (BTC/ETH) — Farside, SoSoValue, CoinGlass. Look specifically for **flow inflection from inflows to outflows** and **late-stage parabolic inflows** (both are distribution tells).
4. **Macro risk regime** — VIX level + 5-day Δ, US 10y yield, DXY, gold. Risk-off turns favor shorts.
5. **Equities breadth** — S&P 500, Nasdaq, equal-weight vs cap-weight spread (NYSE A/D, % stocks above 200d). Narrowing breadth in a rally = vulnerability signal.
6. **Derivatives positioning** — Funding rates (target: high positive), open interest delta + position vs 90-day OI high, long/short ratio, perp basis, options skew/put-call ratio.
7. **On-chain distribution** — MVRV-Z, LTH supply trend (rising distribution rate), exchange inflows (large addresses sending to exchanges), Coinbase Premium turning negative.
8. **Borrow/carry costs** — Perp funding (8h × 3 × 365 = annualized); spot borrow rates where applicable. **Sign matters (corrected Jul 2026) — standard perp convention:** POSITIVE funding = longs pay shorts (carry INCOME to a short); NEGATIVE funding = shorts pay longs (carry COST — and a crowded-short squeeze flag, see §4 penalty). Search-and-correct all prose worked examples elsewhere in this SKILL using the old (inverted) sign convention.
9. **Rotation regime** — Altcoin Season Index (CoinMarketCap), BTC dominance level + 30d trend. Drives the asset-selection pre-check (see §2.5) and feeds the structural-vulnerability sub-gate.
10. **Breaking news** — Regulatory wins for crypto (bullish, dangerous for shorts), ETF news, large M&A, treasury company moves, exchange/protocol exploits (bearish, supportive for shorts).

Tag every figure with **source + timestamp**.

### Deterministic toolchain (mandatory, Jul 2026 — shared with Fallen Knives)

Use the repo toolchain (`tools/README.md`) for every computable number:

0. **Position (Hard Rule 8):** run `./bin/analytics position <asset>` first. Real dry powder against the 50% book cap, open shorts reconciled against the tranche ledger, and per-channel realized win rate (`FR-A-` vs `FR-B-`) all come from it — see §6 "Position & Performance". Exit-code contract per CLAUDE.md / `tools/README.md`.
1. **Fetch:** `./bin/analytics fetch <asset>` (+ `macro`) — cross-checked spot (>1.5% divergence flagged, feeding the canonical-spot reconciliation), computed weekly Wilder RSI-14, **trailing-1-year high with % below** (the §4 phase-of-cycle cap input, computed not eyeballed), F&G 3-day average, VIX/DXY/real-yield/Brent macro block. The same fetch also feeds the mandatory computed Fallen Knives companion score — both sides score off one data pull, making Hard Rule 5's inverse check same-timestamp by construction.
2. **Compute:** `./bin/analytics compute` covers funding annualization, cycle-cap tier, rubric bands, EV, ADR, the squeeze-trap penalty, and the carry-veto/minimum-edge check — see `tools/README.md` for the current flag catalogue; the tool prints its own fields, so this SKILL does not re-enumerate them. **Trap warning kept:** take `--sustained3` from `./bin/analytics fetch`'s `funding.sustained3_below_minus5`, never `longest_negative_run_intervals`. **Edge convention codified in code (Hard Rule 6):** where §4's dash-range bands leave an exact edge ambiguous, Java `ToolchainSupport`/`ComputeMath` resolves it to the LOWER-score band — the harder-to-short reading (RSI exactly 75 → 3, not 4; MVRV-Z exactly 5 → 4; exactly 10% below 1y-ATH → cap 14 applies). A tightening-or-neutral mirror of the FK edge rule, never a loosening.
3. **Scope honesty + failure:** ETF flows, on-chain (MVRV-Z, LTH, reserves), and news remain live web fetches per Hard Rule 1; spot borrow now has a disclosed-context feed (step 4) but that single-venue lending-rate proxy does not itself discharge Hard Rule 1 if a stronger per-venue quote is available. On tool-source failure, follow the documented fallback rules and disclose.
4. **Context Panel (market-data-extension plan, 2026-08, optional):** derivatives positioning under `outp.context` — see the fuller Context Panel entry in §2's live-data table for field names and the Deribit skew sign convention. **DISCLOSED CONTEXT ONLY** — none of this is a scored leg, gate, threshold, phase size, stop, or cap, and it does not relax the Hard Rule 1 web-fetch requirement for ETF flows/on-chain/news above. `./bin/analytics tripwire` (run by hand, not part of the mandatory workflow) can flag a scoring-relevant boundary crossing between two stored `./bin/analytics snapshot` runs without a full report — including, as of 2026-08-05, every §4A/§4B band edge and the actual gate-8 funding boundary, not just the informational funding-sign flip it started with.

## Report Structure

### 1. Header

```
# 🚀 FLYING ROCKET ANALYTICS — [ASSET] — [DATE]
## [CONTEXT LINE]
### Report Generated: [Day], [Date], [Time] EST
### Channel: [A — Distribution | B — Bear Continuation | NONE — stand down] ([X]% off 1y ATH, 200dma [rising/falling])
### Asset: [ASSET] | Prior Score: [X/20 or "cold start"] | Mechanical: [X/20] | Discretionary: [±X.X] | Adjusted: [X/20]
### Cross-Check: Fallen Knives Score (same asset, same date): [X/20 or "not run"]
```

The **Channel** line is mandatory and comes before the score, because the score means different things in each channel — a 12 in Channel A and a 12 in Channel B are not the same claim. If a prior report on this asset used a different channel, say so and name the crossing date.

### 2. Verified Live Data Points

Tables with **source + timestamp** for every cell:

- **Price & Distance from ATH**: Source | Price | ATH | % from ATH | Timestamp
- **Sentiment**: Source | Reading | Status (extreme fear / fear / neutral / greed / **extreme greed**)
- **ETF Flows (BTC/ETH)**: Window | Net Flow | % of AUM | Inflection? (Y/N)
- **Macro Risk Regime**: VIX | 10y | DXY | Gold | Δ5d
- **Equities Breadth**: SPX/NDX | % above 200d | A/D spread | Source
- **Derivatives**: Funding (8h & annualized) | OI Δ7d | L/S ratio | Perp basis | Skew/PCR
- **On-Chain**: MVRV-Z | LTH 30d Δ | Exchange inflow trend | Coinbase Premium
- **Carry**: Perp funding annualized | Spot borrow (if applicable — `./bin/analytics fetch`'s `context.borrow`, BTC/ETH/SOL only, Bitfinex single-venue, disclosed context; a stronger per-venue quote still wins if available) | Implied bleed/month
- **Context Panel** (market-data-extension plan, 2026-08) — optional, **disclosed context only, never a scored leg or gate**: several columns above that used to be eyeballed are now tool-computed — `./bin/analytics fetch`'s `outp.context.basis` (perp basis + annualized carry, same sign convention as `fr-funding`) and `outp.context.positioning` (Binance long/short ratio, taker buy/sell ratio, OI — ~30-day history, single-venue, stated in the output) cover the **Derivatives**/**Carry** rows' funding/OI/L-S/basis cells; `outp.context.deribit` (BTC/ETH only) gives ATM IV + a MONEYNESS-based skew (`skew_90_110_moneyness_pct` — not a true 25-delta risk reversal) + VRP for the **Skew/PCR** cell — **sign convention (2026-08-05, FR-parity plan): POSITIVE = the ~10% OTM put is richer than the ~10% OTM call = a downside hedging bid; a distribution blow-off shows this skew COMPRESSING toward zero or INVERTING (calls bid), never richening further** — `skew_sign_convention` states this in the field's own output, the same discipline as `fundingBlock`'s and `basisBlock`'s sign lines; realized-vol/drawdown/RSI percentiles and macro-scope `net_liquidity`/`hy_oas`/`nfci`/`stablecoin_supply` round out the panel. Include when it sharpens the read (e.g. skew compressing or inverting into a blow-off, OI at a percentile extreme); a report is complete without it. Promoting any field here into the rubric is a `framework-calibration` job, not something this skill does inline.

**Canonical-spot reconciliation (Jun 2026).** Governed by Fallen Knives' `[R:canonical-spot]` rule — same computation (median of the primary source + ≥2 others, timestamped), same >0.5%-spread reporting duty, same sign-flip-triggers-low-confidence test. **Corrected 2026-08-07 (C7):** this was labeled a "mirror," which overstated the symmetry — FK's rule additionally carries a synchronized-quote window (2hr), explicit "live"/"frozen-stale" row labelling, and an EXCLUDED-tag requirement for divergent stale quotes; FR has none of those and is **silently tighter as of this correction**, not looser: an FR report now inherits the AND-conjunct FK actually enforces (sign flip **AND** |median EV-vs-spot| < spread) rather than the OR this paragraph previously implied by omission. FR's own delta: the sign-flip test reads **Total Short EV**, not EV-vs-spot, and the two-extremes computation is required whenever the spread threshold is crossed, with no separate "fewer than 3 synchronized quotes" carve-out stated here. Do not act on a short whose entire edge sits inside the price-source noise.

**Extend to historical price anchors (Jul 2026).** Any historical price anchor used in a scenario band, falsifier, or IF-THEN must carry source + date and be cross-checked against the same-asset report series (FK + FR) within the trailing ~30 days, or the single freshest live 52-week-low/high print if no report-series match exists. Any percent-change claim referencing the anchor must reproduce it within ~2% (or consistent with the corroborative-spread tolerance above). Failure → anchor marked UNVERIFIED and no band boundary may sit on it; the band boundary must instead use the nearest independently-sourced live figure, or the boundary is omitted with the gap disclosed (never silently rounded near the unverified number). For cold-start assets/early series with no prior report to cross-check against, degrade to "UNVERIFIED — disclose only, do not block."

### 2.5. Regime / Asset-Selection Pre-Check (mandatory)

Before scoring, run this fast check. If any condition fires, **stop and warn the user** rather than producing a stale score.

| Condition | Implication |
|---|---|
| Chosen asset is >20% below its 1-year ATH **AND** below a falling 200dma (200dma today < 200dma 20 sessions ago) | **Channel B — Bear Continuation.** Not a top; do not score §4A. Score §4B and use the Channel B gates, sizes, stops, and 30% cap. State the channel and the two regime measurements (% off 1y ATH, 200dma slope) in the header. |
| Chosen asset is >20% below its 1-year ATH **AND** the 200dma is flat or rising (or price is above it) | **No channel — stand down.** This is the genuine phase-of-cycle mismatch the old cap was written for: a recovery/basing tape that is neither a distribution top nor a confirmed downtrend. Channel A is capped at **8/20** (unreachable); Channel B's regime test fails. Continue scoring §4A for the record, state that no phase can unlock, and say which of the two regime conditions would have to change. |
| Chosen asset = BTC AND BTC dominance is in confirmed uptrend (30d trend up, dominance >55%, broke out of a multi-month range) AND Altcoin Season Index <40 | **Wrong-asset risk.** Dominance breakout = capital rotating INTO BTC FROM alts. If a short exists in crypto today, it is more likely in a lagging alt than in BTC. Recommend a screening run on the lagging top-20 alt cohort (see §6.5) BEFORE deploying any short on BTC. |
| Chosen asset = an alt AND BTC dominance is *falling* (altseason regime, index >75) AND user's alt has outperformed BTC by >2× over trailing 30d | **Squeeze-trap risk on alt short.** Short interest in outperforming alts during altseason is the most frequently liquidated short cohort. Require ≥6 of 9 confirmation gates ✅ (vs the default 3 — a deliberate +3, not the +2 it was against the old floor of 4; the most-liquidated short cohort gets the wider margin) before Phase 1A unlocks, **and the S2 Conviction Path is unavailable** — a discretionary substitution is exactly the wrong instrument in the most-liquidated short cohort. State this as a temporary asymmetry override in the report. |
| User requested a smaller alt (top-20-by-mcap not satisfied) | Confirm borrow availability and likely liquidity before producing deployment numbers. State borrow rate and 24h volume in the report — `context.borrow` only covers BTC/ETH/SOL, so a smaller alt still needs a live web-sourced borrow rate per Hard Rule 1. |

If none fire, proceed normally.

### 3. Critical Developments

Bulleted highest-impact news: regulatory wins/losses, ETF news, macro releases, exchange events, treasury company actions, "obvious tops" cultural signals (mainstream press cover stories, IPO frenzies in adjacent sectors).

### 4. Flying Rocket Composite Score (X / 20)

Score **only the rubric of the channel selected in §2.5**. Both rubrics are 20-point scales, both feed the same phase table, and both are subject to the Analyst Discretion Layer's ±2 (see below) — but they measure different things and their scores are not comparable across channels.

#### 4A — Channel A rubric (Distribution / top)

| Category | Max | Scoring Rubric |
|---|---|---|
| **Euphoria Sentiment** | 5 | **3-day average** F&G or asset equivalent — use the smoothed average, NOT the single-day spot print, to avoid score whipsaw (mirror of the Fallen Knives Jun-2026 tune). ≥90 → 5 · 80–89 → 4 · 70–79 → 3 · 60–69 → 2 · 50–59 → 1 · <50 → 0 |
| **Momentum Overextension** | 4 | Weekly RSI. >75 → 4 · 70–75 → 3 · 65–70 → 2 · 60–65 → 1 · <60 → 0. Bonus −1 if monthly RSI also >70 (rare cycle-top condition) — capped at 4 max |
| **Valuation Extreme** | 5 | **Primary (BTC/ETH):** MVRV-Z. >5 → 5 · 3–5 → 4 · 2–3 → 3 · 1–2 → 1 · <1 → 0. **Fallback (alts):** distance from ATH. New ATH or <5% from ATH → 5 · 5–15% → 3 · 15–30% → 1 · >30% → 0. State which used. |
| **Distribution Evidence** | 3 | Count of: (a) LTH supply declining 30d AND profit-taking rate >$500M/day (BTC scale; pro-rate by mcap for ETH/alts), (b) net exchange inflows >30d avg with large-address tagging, (c) ETF flows decelerating or net outflows after sustained inflow regime. 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **Structural Vulnerability** | 3 | Count of: (a) perp funding pinned positive (annualized >25%) ≥7d, (b) put/call ratio <0.6 or 25d skew deeply call-favored, (c) breadth divergence — asset at ATH while equities breadth narrowing OR while majority of other crypto majors are NOT at ATH. 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **TOTAL** | **20** | |

**Squeeze-Trap Penalty** (applied to raw before modifier; decoupled from the OI conjunct, Jul 2026):
- Perp funding annualized <−5% sustained ≥3 consecutive funding intervals: **−2** to raw score AND **+1** confirmation-gate surcharge on every phase unlock (stacking with the correlation surcharge, capped so total stacked gate surcharges never require more than 100% of the active gate denominator).
- If additionally OI is within 5% of the 90-day high, escalate: **−2** raw and **+2** gate surcharge.
- If funding prints <−7% (annualized) in a single interval AND OI is within 5% of the 90-day high, the escalated penalty fires IMMEDIATELY without waiting for 3-interval confirmation.
- Rationale: negative funding alone is sufficient evidence of a crowded short — the consensus is already positioned; you are buying squeeze fuel, not selling a top. The OI conjunct is no longer required to trigger the base penalty (it only escalates it) — this penalty formalizes what would otherwise be only narrative warning.

**Correlation / Regime Treatment** (revised Jun 2026 — demoted from a score multiplier to a sourced gate surcharge + label, mirror of the Fallen Knives change; every branch here only ever makes a short *harder*, never easier, per Hard Rule 6):
- **Risk-on suppressor (kept, hardened):** **sourced** 30d corr >0.7 AND SPX within 3% of ATH (full risk-on) → require **one additional confirmation gate** for any short-phase unlock. This replaces the ×0.80 haircut and extends the suppression to *every* phase rather than fractionally trimming a score.
- **Decoupled / negative-decoupling (corr <0.2 / <0):** **context label only — NO score bonus.** The old ×1.05 / ×1.15 multipliers rode on eyeballed correlations and *inflated short conviction*; removed. The §5 trend term and §3 developments already carry the idiosyncratic-distribution information.
- **Sourcing rule (Hard Rule 1):** state the sourced 30d correlation + timestamp, or "not computed." If **not computed**, the risk-on surcharge defaults **ON** (when unsure, demand *more* confirmation to short — the conservative direction for the asymmetric side).

**Phase-of-Cycle Cap — Channel A only** (applied last, overrides everything above within Channel A):
- Asset 10–20% below 1-year ATH: capped at **14** (Phases 1A–1B reachable; Phase 2–3 locked out).
- Asset within 10% of 1-year ATH: no cap; full 20 scale available.
- Asset >20% below 1-year ATH: Channel A is capped at **8** and therefore dead — but this is now a *routing* result, not a verdict. §2.5 has already sent you to Channel B (if the 200dma is falling) or to stand-down (if it is not). **Do not report a capped Channel A score as the framework's answer when Channel B is the live channel.** The cap-regime vacuity disclosure below applies only in the stand-down case.
- **Cap-regime vacuity disclosure (Jul 2026, retained for the stand-down case):** while the 8-cap binds and no channel is live, print: (i) the score as X / 8 attainable (alongside X/20); (ii) the line "interpretation bands ≥9 unreachable — no phase reachable at any score, the Channel A 1A line of 11 sitting 3 points above the cap; Hard-Rule-5 both-≥12 check structurally unfalsifiable in this regime"; (iii) never a bare consistency ✅ — use "structurally consistent (cap-bound; both-≥12 unfalsifiable by construction)".

Round to nearest integer. State, in this order: **raw legs · squeeze-trap penalty · bounce-maturity floor (Channel B) · gate surcharges (these move the gate floor, not the score) · cap · MECHANICAL score · discretionary term · cap re-applied · final ADJUSTED score.** The mechanical score is not optional to print — it is the number every cover trigger, the force-cover, both vetoes, the minimum-edge filter, the collar and Phase 3 read.

#### 4B — Channel B rubric (Bear Continuation)

**Regime precondition (re-verify here, do not inherit):** close below the 200dma, 200dma falling over the trailing 20 sessions, and >20% below the 1-year ATH. If any of the three fails mid-report, Channel B is void — re-run §2.5.

This rubric scores a **counter-trend rally dying into resistance**. Every leg is *relative to the prevailing bear*, because absolute euphoria measures read zero in a market that has already repriced 40–60% — the backtest found all six ETH bear-rally peaks scored 0/0/0 on Channel A's sentiment, momentum, and valuation legs while sharing a tight common signature.

| Category | Max | Scoring Rubric |
|---|---|---|
| **Rally Extension** | 5 | Bounce off the trailing 40-session low, measured to the current session high. >35% → 5 · >25% → 4 · >18% → 3 · >12% → 2 · >8% → 1 · else 0. *(Six ETH bear-rally peaks: 19.1 / 20.3 / 20.8 / 26.4 / 26.6 / 34.5%.)* |
| **Local Momentum Exhaustion** | 4 | **Daily** Wilder RSI-14 — the bounce timeframe, not the weekly. >65 → 4 · >58 → 3 · >52 → 2 · >45 → 1 · else 0. **Hard qualifier: weekly RSI must be <50.** If weekly RSI ≥50 the bounce may be a genuine trend repair, not an exhaustion — score this leg **0** and add a ⚠️ to the regime line regardless of the daily print. |
| **Resistance Confluence** | 5 | Count of: (a) price within 3% of, or rejected from, the 200dma; (b) price within 3% of the 50dma from below, or has just lost it; (c) price at/below a prior swing high that is itself a lower high; (d) price into a prior breakdown level / gap. 4/4 → 5 · 3/4 → 4 · 2/4 → 3 · 1/4 → 1 · 0/4 → 0 |
| **Bear Structure Integrity** | 3 | Count of: (a) the bounce high is a **lower high** than the prior swing high; (b) the 50dma is below the 200dma **and the gap has not narrowed** over the trailing 20 sessions; (c) **no** weekly close above the 200dma in the trailing 8 weeks. 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0. **At 0/3 the channel is void** — the bear structure you are trading has already broken. *(Rewritten 2026-07-27: the original criteria — "50dma below 200dma" and "last weekly close did not reclaim the 200dma" — were near-tautological with §4B's own regime precondition, handing every Channel B setup ~3 free points. Each replacement tests something the precondition does not already guarantee.)* |
| **Relative Sentiment / Positioning** | 3 | Count of: (a) F&G 3-day average ≥1.5× its own trailing 30-day mean, or ≥45 in a sub-30 regime — *local* greed, sourced and computed, never eyeballed; (b) perp funding has flipped positive after ≥5 sessions negative (longs re-crowding into the bounce); (c) a flow tell — ETF net inflows resuming into the rally, or exchange outflows stalling. 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **TOTAL** | **20** | |

**Edge convention (Hard Rule 6, encoded in Java `ToolchainSupport`/`ComputeMath` as `frB.*`):** where a dash-range leaves an exact edge ambiguous, it resolves to the **lower-score** band — the harder-to-short reading. Rally exactly 35% → 4, not 5; daily RSI exactly 65 → 3, not 4; exactly 58 → 2. Compute the bands with the tool rather than by eye.

**Channel B modifiers:**
- The **squeeze-trap penalty** applies unchanged (§4A text). Negative funding in a bear rally is the *most* dangerous configuration this framework can encounter — a crowded short into a bounce — and the penalty is the main thing standing between the analyst and a liquidation. Never waive it in Channel B.
- The **risk-on correlation surcharge** applies unchanged, and the sourced-or-default-ON rule with it.
- **No phase-of-cycle cap** (the regime *is* the cap's old trigger condition), but Channel B has a hard structural ceiling instead: **Phase 3 is unreachable in Channel B at any score**, and total Channel B notional is capped at **30% of the short book**.
- **Bounce-maturity floor:** if the rally is <8 sessions old, subtract **2** from the raw score. The backtest's stall-confirmation trigger fired early on the bounces that failed; a bounce that has not yet had time to exhaust is a lower-quality short.

**Channel B stall confirmation (required for any unlock, not scored):** the current session must print a **lower close than the prior session AND a lower high than the bounce high**. Without it you are shorting a rally that is still rising. This is a gate condition, not a leg — an analyst may not substitute for it under S2, and no discretionary point may stand in for it.

#### Confirmation Gates (X / N) — drives phase unlocks, not scoring

Mark each ✅ / ⚠️ / ❌ / **N/A** (inapplicable).

**N/A handling:** if a gate is structurally inapplicable to the current asset/cycle position (e.g., breadth divergence cannot be evaluated when the asset is >15% off ATH), mark **N/A** and *reduce the denominator by 1*. Do not silently mark inapplicable gates as ❌ — that biases the framework toward false-negatives and obscures which signals are genuinely absent vs unmeasurable. **Fixed non-crypto gate schema (Jul 2026):** the set of gates STRUCTURALLY inapplicable to the asset class (e.g., gate 4 perp-funding-based squeeze-trap for gold) is fixed per asset class and restated with the N/A list each report; gates that are merely unmeasured in a given run stay ⚠️ per the N/A-handling rule above and must NOT be folded into the frozen denominator. The active denominator must be an exact integer — a previously-N/A gate may only be reclassified as active via an explicit, disclosed schema-revision note, never a per-report ad hoc redefinition.

**The non-crypto schema is now PINNED, not declared per report (2026-08-05).** The paragraph above required a fixed schema and got four different ones: the four non-crypto reports produced denominators 7, "~6", 7 and 6, two of them on the same asset on the same day off the same data. The denominator scales every phase floor through the ceil conversion, which made it a per-report knob on a protective threshold. The frozen sets now live in the **Adapted Non-Crypto Reads** annex below and in `FR_NONCRYPTO_NA` (Java `ToolchainSupport`/`ComputeMath`), and `./bin/analytics lint-report` errors on any deviation in a report dated on/after 2026-08-05.

1. F&G ≥80 (or asset-equivalent extreme greed) sustained ≥7 days
2. Weekly RSI >70
3. MVRV-Z >3 (BTC/ETH) OR within 5% of ATH (alts)
4. Perp funding annualized >25% for ≥3 funding intervals
5. ETF flows decelerating or net outflows (BTC/ETH) OR retail euphoria proxies elevated (alts) — counts ✅ only when (a) price is within the phase-of-cycle no-cap zone (not >20% off 1-yr ATH, per §2.5 cap) AND (b) price has not made a fresh multi-month or cycle low in the trailing 10 sessions. If either condition fails, record ⚠️ capitulation-context — not counted (this signal fires in both distribution and capitulation; only the distribution reading confirms a short).
6. Coinbase Premium negative ≥3 consecutive days (US institutions selling) — counts ✅ only when (a) and (b) above hold; otherwise ⚠️ capitulation-context. If data unavailable, mark ⚠️ (not N/A — this is a measurable signal, just missing in this run).
7. LTH 30d distribution rate >$500M/day (pro-rated for non-BTC)
8. Breadth divergence: asset at or within 5% of ATH while >50% of top-20 mcap peers off-ATH by >15%. **Mark N/A if the asset is >15% below its own ATH** — the precondition for divergence cannot be evaluated.
9. Rotation regime favors the short: for BTC short, BTC dominance rolling over or in established downtrend (Altcoin Season Index rising through 50); for alt short, BTC dominance rising and altseason index falling — counts ✅ only when (a) and (b) above hold (see gate 5); otherwise ⚠️ capitulation-context. *(Replaces the legacy "macro catalyst neutral-to-negative" gate — macro is already captured by the correlation modifier and §3 critical developments. This gate is more decisive for crypto-specific distribution.)*

**Gate-class labels + reachable-ceiling disclosure (Jul 2026). Channel A gate set only** — Channel B's gates are all evaluable inside a downtrend by construction, so it carries no [TOP]/[FLOW] classification and no reachable-ceiling disclosure, and its gate 8 may never be marked N/A. Tag each gate [TOP] or [FLOW] per its actual condition as written this report (not a hardcoded universal list) — gates 1, 2, 3, 8 are [TOP] (top-coincident, extinguished by trend breakdown); gates 5, 6, 7, 9 are [FLOW] (evaluable through a decline); gate 4 (funding-rate elevation) is CONDITIONAL — flag per-report whether the top-extinguishing logic actually applies (funding can spike on short-squeeze/relief-rally events inside a confirmed downtrend). Print the reachable gate ceiling in the current regime, qualified as regime-conditional (e.g., "ceiling ≈3/9: [TOP] gates unreachable at the current confirmed trend state — re-widens if trend structure repairs"). While the §2.5 cap is active, label the count "structurally dark (cap-bound; [TOP] gates unreachable — a rising [FLOW]-only count is NOT setup progress)" instead of a bare X/Y.

**Channel B gate set (use INSTEAD of gates 1–9 when Channel B is live).** Channel A's gates are top-coincident by construction and would read 0/9 at every bear-rally peak — a false negative, not a finding. Channel B's nine:

1. Rally ≥15% off the trailing 40-session low, **measured to the current session high** — the identical quantity §4B's Rally Extension leg scores (`trend.bounce_pct`)
2. Bounce age ≥8 sessions and ≤35 sessions (mature but not a new trend), measured **from the 40-session low to the current session** (`trend.bounce_age_sessions`) — *not* low-to-bounce-high
3. Daily RSI-14 ≥52 at the bounce high
4. Weekly RSI-14 <50 (the bounce has not repaired the higher timeframe)
5. Price rejected from, or currently within 3% of, the 200dma — **"rejected from"** means a session high **within the bounce window** (the 40-session low through the current session, inclusive) came within 3% of the 200dma and the asset subsequently closed back below it; **"within 3%"** reads the *current* price (`trend.within_3pct_of_ma200`). A 200dma never approached inside the bounce window is **❌, never N/A**
6. 50dma below 200dma
7. Last completed weekly close did not reclaim the 200dma
8. Funding not sustained-negative (i.e. the squeeze-trap penalty is NOT active) — **this gate is a veto in disguise: at ❌ the Channel B unlock is void regardless of count**
9. F&G 3-day average ≥1.5× its trailing 30-day mean, OR funding flipped positive after ≥5 negative sessions

Gate 8 is the only gate in either channel that voids an unlock on its own. That is deliberate: the backtest's worst realistic failure mode for Channel B is shorting a bounce that is already crowded short. **N/A handling, the ceil-threshold conversion, and the ⚠️-may-not-become-N/A rule apply to the Channel B set identically.**

**Gate measurement convention** *(added 2026-08-07)*. Gates 1, 2 and 5 each name a quantity that has more than one defensible endpoint or window, and until now none of the three declared which. On 2026-08-06 that cost two gates on BTC (7/9 → 5/9) and one on ETH (7/9 → 6/9) on the same tape, same data, same day — a swing across Channel B's Phase 2 floor of 6 decided by nothing but an undeclared convention. It is the same disease the 2026-08-05 calibration froze the non-crypto gate schema against, one level over: that was the **denominator** (which gates are active), this is the **numerator** (which gates pass). Four rules, in precedence order:

1. **A gate that restates a §4B leg's quantity inherits that leg's measurement, exactly.** One number serves both; a gate may never re-measure a leg's quantity on a different endpoint. This settles gate 1 on its own — the Rally Extension leg has said *"measured to the current session high"* since the rubric was written, so the bounce-high reading used by reports before 2026-08-06 was **incorrect, not merely undeclared.**
2. **Where a gate names a quantity no leg defines and Java `ToolchainSupport`/`ComputeMath` emits a field of that name, the tool's field is primary** and is cited by name in the report. This settles gate 2 on `trend.bounce_age_sessions`, and it is why `dailyTrend()` emits `sessions_low_to_high` alongside rather than discarding it.
3. **Otherwise the harder-to-short reading governs** — Hard Rule 6, extending §3's existing dash-range edge convention from rubric bands to gate conditions. A tie in defensibility is never a tie in consequence.
4. **When two readings of the same gate disagree on its verdict, print both, with the gate count under each.** The declared reading is the one that counts; the alternative is disclosed so a calibration can grade the convention itself rather than re-deriving it. A gate whose two readings *agree* needs only the declared one.

**The ≤35 upper bound and the rolled-over case.** Gate 2's parenthetical names two conditions with one number: *"mature"* (the ≥8 floor) and *"not a new trend"* (the ≤35 cap). Measured low-to-current, a bounce that topped out and has been decaying for weeks fails the cap at 36+ sessions — arguably against the cap's own stated purpose, since a rally falling away from its high is not a new trend. **The gate still fails, and this is deliberate:** the reading is the harder-to-short one, gate count is a protective threshold, and Hard Rule 6 forbids curing a false negative by loosening one. What the report must do instead is **disclose it**: when gate 2 fails *only* on the ≤35 bound while the bounce high is already behind (`sessions_low_to_high` < `bounce_age_sessions`) and price sits below it, state that in the gate table. That disclosure **may not be counted as a pass, may not raise the gate count, may not substitute for a gate under S2, and may not be cited as evidence in a Conviction Path** — it exists so the observation reaches the next calibration instead of being silently discarded. If the cap is genuinely misspecified, a calibration fixes it on evidence; a report does not fix it at the moment it is inconvenient.

Count ✅ only. State count as **X / Y** where Y is the active (non-N/A) gate count. **Unified N/A gate-count arithmetic (Jul 2026, ceil-threshold convention — replaces the round-down conversion):** when gates are N/A, do NOT convert the achieved count. Restate each phase THRESHOLD against the active denominator as `ceil(legacy_threshold/9 × active_count)` — e.g., 8 active gates: 1A `ceil(3/9×8)=ceil(2.67)=3`, 1B `ceil(4.44)=5`, P2 `ceil(5.33)=6`, P3 `ceil(7.11)=8` — and print the restated floors in the phase table alongside the legacy /9 floors for cross-check (e.g. "1B: ceil(5/9×8)=5 [legacy 5]"). Never round in the direction that lowers a floor; forbid converting ambiguous ⚠️ gates to N/A (mirror of the FK rule). Every report MUST print the converted unlock thresholds with arithmetic shown; approximate (~) denominators are prohibited. Strictest-wins rounding (ceil, never floor) applies to all required counts.

Phase unlocks reference the legacy 9-denominator thresholds (**≥3** for 1A, ≥5 for 1B, ≥6 for 2, ≥8 for 3 — the 1A floor moved 4 → 3 on 2026-07-27; every other floor is unchanged). When N/A reduction is in effect, use the ceil-threshold conversion above.

### 5. Probability Matrix — Derived From Score

Baseline grid for short setups (probabilities reflect price direction, NOT short P&L):

| Adj. Score | Continued Rally | Range | Mean-Revert | Bear Reversal |
|---|---|---|---|---|
| 0–5 | 50% | 30% | 15% | 5% |
| 6–10 | 35% | 35% | 22% | 8% |
| 11–14 | 20% | 30% | 35% | 15% |
| 15–17 | 10% | 25% | 40% | 25% |
| 18–20 | 5% | 15% | 40% | 40% |

Adjust each cell ±10% based on idiosyncratic catalysts (and state).

**Trend term (added Jun 2026 — short-side directional humility, mirror of Fallen Knives §5).** This grid maps euphoria→mean-revert monotonically, so it reads **persistently bearish in an active UPtrend** — telling you to short strength that keeps making higher highs (the short-side falling-knife). Correct for this: when price is **above a major MA AND making higher highs** (confirmed uptrend), shift **10–15% of mass from Mean-Revert / Bear-Reversal → Continued Rally** — respect the trend you are shorting into. Apply the mirror toward the downside only once the trend is **breaking** (loss of a major MA or a confirmed lower-high). State the shift. *(This tune can only make a short case weaker, never stronger — consistent with Hard Rule 6.)*

**Confirmation throttle — bounce ≠ uptrend (Jul 2026).** While the asset is >20% off its 1-yr ATH — **Channel A stand-down or Channel B alike**, since Channel B removes the cap but not the measurement — a bounce does NOT qualify as a confirmed uptrend for this shift unless the higher-high/higher-low structure is ≥15 sessions old on weekly-close basis. Single-report momentum (>5% move in ≤7 days) must be labeled BOUNCE (UNCONFIRMED) and the shift withheld; the post-shift Continued Rally cell may never exceed 55%. In the 10–20%-off-ATH band, apply the same standard at a ≥10-session threshold (this is the live regime where Phase 1A can unlock and a single-week rip could otherwise be mislabeled "confirmed uptrend"). Outside both cap regimes the original criterion stands unchanged. If assets oscillate across the 20%-off-ATH boundary report-to-report, use the prior report's regime classification as tie-break.

**Analyst override of the grid (2026-07-27, S1).** The baseline grid is a starting point, not an output. The analyst may depart from it by more than the ±10% cell adjustment above when the tape warrants — including replacing the scenario set entirely with one that fits the actual setup (Channel B, for instance, is usually better described by "bounce resumes / range / breakdown resumes / capitulation leg" than by the top-centric default). Requirements: probabilities still sum to 100%, the EV recomputation/sum-check still runs, and the departure is stated with its reasoning in §9. The **direction** of the departure is unconstrained — the grid may be made more bearish or more bullish than baseline — but a departure that makes the short case stronger must name the evidence it rests on, per Principle 11's two-tier certainty rule.

The grid never overrides a veto: the +3% minimum-edge filter, the carry veto, and the collar all read the mechanical score and the printed EV regardless of how the scenarios were built.

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value (EV_price)** = Σ (probability × midpoint).

**Decompose short EV into directional + carry components, sign-aware:**

```
Directional EV (%)  = (spot − EV_price) / spot × 100
Carry EV (%)        = + (annualized funding rate) × (hold days / 365) for a short,
                      MINUS borrow/fee costs (never floored — always count in full
                      against Total Short EV and the 40%-of-target veto, regardless of sign)
Total Short EV (%)  = Directional EV + Carry EV
```

**Sign convention (corrected Jul 2026):** POSITIVE funding = longs pay shorts (carry INCOME to a short); NEGATIVE funding = shorts pay longs (carry COST — and a crowded-short squeeze flag, §4 penalty). **Zero-floor on carry income:** for carry INCOME (positive-funding case) specifically, floor it at ZERO for the purposes of the +3% minimum-edge filter and the carry>40%-of-target veto — funding income may never help a short clear the filter or shrink the carry veto; only costs count against the trade at those two decision points. The headline Total Short EV and Carry EV lines shown for transparency must still report the TRUE signed (unfloored) number, with a footnote showing the floored value used for the two gate checks when they differ. **Mandatory EV recomputation/sum-check:** probabilities must sum to 100%; recompute EV_price from the printed cells as the final step, flowing from the printed probability/midpoint cells to EV_price only (never the reverse); if the stated EV differs from the recomputation by >0.5%, correct before publishing — show the component sum. (This sum-check requirement mirrors, and is textually identical to, the Fallen Knives EV grid's own mandatory recomputation check.)

Three lessons baked into this:

1. **Sign of funding matters.** Most reports won't see negative funding, but when they do, it is a COST (a crowded-short squeeze signal), not income. Show both lines explicitly even when one is trivial.
2. **Carry can flip the trade either way.** A +6% directional edge with a funding cost (negative funding) drag over a 90-day hold reduces net edge; a positive-funding income case is floored at zero for gating purposes regardless of magnitude. Show the math.
3. **Carry income is not a green light to short** and can never itself clear the minimum-edge filter or shrink the carry veto — see the zero-floor rule above.

State: spot, EV_price, Directional EV, expected hold (days), annualized funding, Carry EV (true signed value + floored value if they differ), **Total Short EV**, and the no-trade threshold (Total Short EV must exceed +3% to clear the framework's minimum-edge filter; below that, expected gain is not worth the asymmetry of being short).

**Stand-down accountability (Jul 2026).** A stand-down verdict does not exempt the forecast layer from grading. Every FR report must restate the prior FR report's EV_price, modal band, and named falsifiers (text explicitly tagged "(Falsifier: ...)" in the source report only — not any forward-probability "Pattern" line) for the asset and mark each as held / falsified with the realized print. This creates no new report-cadence obligation — it is a duty to grade IF/when a report is produced on that asset, not a trigger to produce one. Grading does not itself re-arm a short — resuming FR coverage on a stood-down asset requires re-running the full §2.5/§4 gate stack from scratch. *(Corrected 2026-08-07 (C6): this previously claimed Fallen Knives carries a reciprocal "prior FR forecast check" line. It does not — grep confirms zero hits. This obligation is FR-only; adding the reciprocal to FK would be a new rule, not a compaction, and is not being made here.)*

### 6. Short Deployment Strategy

**EV-voice demotion when the phase-of-cycle cap binds (Jul 2026, corroborative-only labeling).** When the phase-of-cycle hard cap (or 0-gate state) already vetoes all phases, the Total Short EV must be labeled CORROBORATIVE ONLY — NOT LOAD-BEARING, and the verdict must name the structural veto (cap / gates / FK≥12 force-cover) as the binding reason. This applies only where the cap/gate-state is the unambiguous SOLE or dominant veto (not merely present alongside other borderline factors). Categorical directional prose ("a short here is expected to lose," "coin-flip," "asymmetry runs against the short") is prohibited in this state; state only "Total Short EV fails/passes the +3% minimum-edge filter at both spread extremes," with the point estimate and its trailing calibration (signed error of up to the last 3 available published EVs for that asset, or "n=[count], insufficient for a trend read" if fewer than 2 exist) printed beside it. Cross-references Principle 12 (bars "confirmed/resolved" claims) — this clause bars categorical EV-magnitude/directional-confidence claims in the same trigger state.

**Splits: 5 / 10 / 15 / 20** (max **50% of dedicated short book**, NEVER 100%). Front-loaded but smaller than long phases — shorts demand more humility.

**Cold start:** every phase begins as `DRY POWDER`.

**Mandatory:** every deployed phase has a **hard stop** (price level above entry) and a **time stop** (max hold). Both must be stated at entry. Violation of either = automatic cover of that tranche.

**Score lines cut 2026-07-27** (1A 13→**11**, 1B 15→**13**, P2 17→**15**; Phase 3 stays at **19** — a generational short must stay rare, and the backtest gave no evidence for cutting it). The cut is worth more than any other single change: at realistic inputs it takes Channel A from 1 of 6 distribution tops to 4 of 6. Going to 10 was tested and rejected — it bought one extra fall at the optimistic leg assumption and nothing at the realistic one.

**The two channels use DIFFERENT ladders** *(added 2026-07-27, second pass)*. §4B's rubric scores **2–4 points higher than §4A on an equivalent-quality setup** — its legs are measured against a bear baseline, so a merely typical bear-rally top scores 13–16 where a merely typical distribution top scores 10–12. Running both through one ladder put Phase 2 — Channel B's *maximum* — at the **modal** Channel B signal, while in Channel A the same line is the rare one. That is a calibration error, not a policy: it would have deployed the channel's full 30% on its most ordinary setup.

| Phase | Channel A | Channel B |
|---|---|---|
| 1A | ≥11 | **≥13** |
| 1B | ≥13 | **≥15** |
| 2 | ≥15 | **≥17** |
| 3 | ≥19 (mechanical) | **unreachable** |

Gate floors are shared (3/5/6/8 on the /9 denominator). The scores are not comparable across channels and the ladders are not either — always state which channel's ladder you are reading against.

#### Phase 1A — Probe (5%)
- **Unlock gates:** adjusted score **≥11 (Channel A) / ≥13 (Channel B)** AND **≥3** of 9 gates ✅ AND §7 preflight PASS — **OR** the S2 Analyst Conviction Path
- **Entry zone:** ASSET-SPECIFIC. State range.
- **Hard stop:** +8% above entry OR daily close above local high — whichever tighter. **Channel B: +6% or the bounce high +1%, whichever tighter.**
- **Time stop:** 21 calendar days. **Channel B: 21 days.** Neither is extendable — S6 binds both.
- **Status:** DRY POWDER / SHORT ([entry]) / COVERED / STOPPED

#### Phase 1B — Add (10%)
- **Unlock gates:** adjusted score **≥13 (Channel A) / ≥15 (Channel B)** AND ≥5 gates ✅ AND Phase 1A still in profit OR scratch AND §7 preflight PASS
- **Entry zone:** [range — must be at higher price than 1A entry, confirming overextension]
- **Channel B adds require a FRESH stall confirmation at a higher price, with the earlier tranche unstopped.** In Channel B "price is higher" is not confirmation of overextension — it is the bounce resuming, which is the thesis failing, and the 1A stop is probably already hit. A Channel B add is therefore only legitimate when the rally has stalled a *second* time at a *higher* level while the first tranche survived. The practical consequence, stated plainly: **Channel B is normally a single 5% probe.** Its 30% ceiling requires three separately confirmed stalls and is expected to be rare — if you find yourself reaching it often, the stall confirmation is being read too loosely.
- **Hard stop:** +10% above blended cost. **Channel B: +6% above blended cost, or the bounce high +1%, whichever tighter.**
- **Time stop:** 28 days from this entry. **Channel B: 21 days.**
- **Status:** DRY POWDER / SHORT / COVERED / STOPPED

#### Phase 2 — Conviction (15%)
- **Unlock gates:** adjusted score **≥15 (Channel A) / ≥17 (Channel B)** AND ≥6 gates ✅ AND macro catalyst neutral-to-negative AND correlation regime not full risk-on AND §7 preflight PASS. *(The macro clause is a deliberate Phase-2-only extra, retained; it is not the retired gate-9 predecessor, which §4's gate list replaced.)*
- **Hard stop:** +12% above blended cost. **Channel B: +8% above blended cost.**
- **Time stop:** 35 days. **Channel B: 28 days.**
- **Status:** DRY POWDER / SHORT / COVERED / STOPPED
- **Channel B: this is the deepest reachable phase.** Total Channel B notional across 1A+1B+2 is capped at **30% of the short book**.

#### Phase 3 — Generational Short (20%)
- **Unlock gates:** **mechanical** score ≥19 AND ≥8 gates ✅ AND clear distribution candle (weekly bearish engulfing or break of key support on volume) AND ETF flows confirmed net outflow ≥5 sessions AND §7 preflight PASS
- **Channel A only. Unreachable in Channel B at any score. No analyst channel (S1 or S2) may unlock it** — see S5.
- **Hard stop:** +15% above blended cost
- **Time stop:** 49 days
- **Status:** DRY POWDER / SHORT / COVERED / STOPPED

#### Stop distance has a FLOOR as well as a ceiling (added 2026-07-27, second pass)

A stop parked at "the bounce high +1%" typically lands **2.5–4% above the fill**, because you enter on the stall bar a few percent below the high. Against ETH's realised daily σ of ~2–3% — 3.24% ADR(5) on the repo's own most recent quiet-week print — that is a **~80% probability of being touched by noise alone over a 21-day hold, before any edge at all.** A stop that tight does not control risk; it converts the strategy into a lottery on the minority of entries that survive the chop, and "tighten the stop further" is therefore the wrong response to a poor win rate.

**Rule:** every tranche's stop sits in a band — no closer than **1.5 × ADR(5)** above the fill, and no wider than the phase ceiling (Channel A 8/10/12/15%, Channel B 6/6/8%).

- If the structure level (bounce high +1%) is **inside** the noise floor, widen to the floor — up to the phase ceiling, never past it.
- If **1.5 × ADR(5) exceeds the phase ceiling**, the tape is too volatile for that phase: **no trade.** This tightens entry rather than widening risk, which is the correct direction on this side of the book.
- The floor never overrides the S6 ratchet: on an existing position a stop may still only move toward price. The floor governs where a stop is first set.

Print ADR(5), the floor, the ceiling, and the chosen stop for every tranche. `./bin/analytics compute adr` computes the input; `frStopBand()` in the Java computation core computes the band and the linter enforces both bounds.

#### Concentration, and what "the short book" actually means

**Per-asset cap: 30% of the dedicated short book, across BOTH channels combined.** Without it the two channels stack legitimately into a single name — Channel A 15% while the asset is 19% off its high, then Channel B 30% after it breaks 20%, then an analyst 5% — reaching 45–50% of the book on one asset, entered on two different theses about one decline, all short, all exposed to the same squeeze. Every book-level cap held in that construction; none of them was measuring the thing that would hurt.

**Leverage and liquidation (mandatory statement in every report with a live tranche).** All phase sizes in this framework are percentages of a **dedicated short book, assumed unlevered**. The framework's entire loss control is a *price level*, and a price-level stop is not the mechanism by which a short position actually dies — margin is. Therefore:

- State the assumed book size basis and whether the expression is spot-borrow, unlevered perp, or levered perp.
- If levered, state the **liquidation price** for each tranche. **A tranche whose liquidation price sits at or inside its stop distance is prohibited** — the stop would never execute, and the position would be closed by the venue instead, at a price nobody chose.
- If the short book shares margin with a Fallen Knives long book on the same venue, say so. A cascade that liquidates one can liquidate the other, and neither framework models the other's collateral.

#### Consecutive-stop-out suspension (Channel B)

**Three consecutive stopped-out Channel B tranches on the same asset suspends Channel B for that asset** until either the asset leaves the Channel B regime (a weekly close reclaims the 200dma) or 30 calendar days pass with no new entry. This implements backtest falsifier **F4** as a live control rather than a post-hoc grading note: at a 29–54% win rate the expected worst losing streak over ~46 signals is roughly ten, and nothing else in this framework counts consecutive losses. A suspension is not a judgment that the thesis was wrong — it is an admission that the *timing* signal has stopped working on this asset in this regime, which is precisely what three stop-outs measure.

The suspension is recorded in the S7 Discretion Ledger and may not be lifted by an S1 term, an S2 path, or a high score. *(This is deliberately narrower than a book-level drawdown circuit breaker, which was considered and declined for the long side; it is per-asset, per-channel, and triggered by a count rather than by a P&L threshold.)*

**Channel-migration rule.** A tranche keeps the channel it was opened under for its entire life. If the regime crosses (a Channel B asset reclaims its 200dma on a weekly close, or a Channel A asset falls >20% off the ATH into a falling 200dma), open tranches do **not** inherit the new channel's wider stops or longer clocks — they keep their own, and the Channel B 200dma-reclaim cover in §7 fires regardless. A new channel may only open *new* tranches, and the 30% Channel B cap and 50% total cap are both measured across the whole book, not per channel.

**§7 Cover-Trigger Preflight veto (Jul 2026, pure tightening; channel-scoped 2026-07-27).** Before any phase unlock, evaluate the state-checkable §7 cover triggers **for the active channel** at the proposed entry, print the result as a table, and if ANY is live the unlock is VOID — do not open a tranche the exit framework closes at inception.

- **Channel A (six):** F&G <40 · published FK ≥12 · funding negative ≥3 intervals · price below the 50d MA · MVRV-Z <2 · upside narrative break.
- **Channel B (five):** published FK ≥12 · funding negative ≥3 intervals · upside narrative break · last weekly close reclaimed the 200dma · Bear Structure Integrity 0/3.

**Why the lists differ, and why this is not a loosening.** Channel B trades a market that has already repriced 40–60%. F&G <40, MVRV-Z <2 and price-below-the-50dma are near-permanent there — and price below the 50dma is a Channel B *scoring positive* (§4B Resistance Confluence credits "within 3% of the 50dma from below, or has just lost it"). Applying Channel A's list to Channel B would veto every Channel B entry at inception and then trim any that opened by 25/50/25% on conditions that were true *before* entry and formed part of the thesis. That is not caution, it is an incoherent rule. Channel B's replacements are strictly harder-edged: two of the five are its own 100%-cover regime tests, which have no Channel A equivalent. The two dropped from the veto list are dropped **only** from Channel B, where they are regime constants rather than signals. The two positional §7 triggers (score-drop-from-peak, time/hard-stop-hit) are explicitly OUT OF SCOPE for this preflight — they are evaluated only after a tranche exists. A single borderline/noisy trigger within a stated tolerance band (e.g., MVRV-Z 1.9–2.0) may be logged as a WARNING rather than an automatic VOID, with an analyst note required.

**Total max short exposure: 50% of dedicated short book. Remaining 50% is structural dry powder for adverse moves / averaging at higher prices ONLY if thesis intact AND score has not deteriorated.**

#### Position & Performance (Hard Rule 8 — read the ledger before sizing anything)

Run `./bin/analytics position <asset>` **before** writing this section. It reads the position snapshot exported from the personal-accounting ledger — derived from actual Binance fills, not from what a prior report said. This replaces the narrated carry-forward line that used to open §6.

**Exit 0 / band FRESH (≤12 h): these figures are the position of record and supersede any number carried forward from a prior report.** Print, from the snapshot:

- **Position of record** — real quantity, ACB cost basis, unrealized PnL, and each holding's **attribution** (its deal tag, e.g. `FR-B-1A`, or `UNTAGGED`).
- **Open shorts reconciled against the tranche ledger** — `futures.open_positions` where `side: "SHORT"`, matched to the tranches §6 believes are live. A short in the account that no tranche authorizes, or a tranche with no position behind it, is a discrepancy to state, not to average out.
- **Real dry powder** — `dry_powder.stable_balance_usd`, against the 50% book cap and the 30% per-asset cap. Note it **excludes futures-wallet collateral**, which is counted as equity, not powder.
- **Realized performance** — closed round trips with realized PnL and hold time, plus win rate / profit factor / expectancy per tag. `performance_by_tag_prefix` carries `FR-A-` and `FR-B-` separately: **per-channel win rate is exactly the evidence Hard Rule 6 asks for**, and it is now readable rather than asserted.
- **Position Reconciliation** — where the prior report's narrated figures diverge from the ledger, naming the delta. **The ledger wins.**

**Band STALE (12–72 h):** descriptive use only, under an explicit age banner. It may **not** satisfy a phase-dependent unlock precondition — including Phase 1B's *"1A in profit or scratch"* — and may not fill a realized ledger column below.

**Exit 1 (EXPIRED / missing) or exit 2 (NOT_COVERED — an asset with no ledger counterpart and no alias):** say so in one line and proceed as a **cold start under Hard Rule 4**. Never read a zero position out of a NOT_COVERED response. Refuse the position claim, never the report.

**Governed verbatim by CLAUDE.md Hard Rule 8** (already in context every report) — the gold→PAXG alias, the `custody.status` literals (`RECONCILED` / `EXPLAINED_BY_EXTERNAL_TRANSFER` / `EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE` / `UNEXPLAINED`) and their handling, and `basis.reliable` as a question separate from custody — read `custody.status` before any quantity, and `basis.reliable` separately from it. *(Stage 2 pointer, 2026-08-07 — this paragraph and the enumerated custody-status/basis rules below it were byte-identical to Fallen Knives' copy and both restated Hard Rule 8; kept here only if it ever needs to diverge from CLAUDE.md.)*


**Two carve-outs survive even at FRESH, and only two.** (a) Snapshot marks are **informational** and never become canonical spot — and note the snapshot's `liquidation_price_usd` is **always null**: liquidation price is not synced, and a null there is *not* permission to omit a stated liquidation price from a levered tranche. (b) Phase attribution comes from **deal tags only**; an untagged short is real-but-`UNTAGGED`, never inferred from size or timing.

#### Open-position “Absolutely Best Level” rubric (mandatory when any short is open)

Run this audit on **every report with a non-zero open short**, whether framework-authorized, `UNTAGGED`, or `UNFRAMED`. The phrase “absolutely best” is the user's requested label, not a certainty claim: the report must say **“no level is absolutely best; this is the best available control under current evidence.”** Research with fresh instrument and underlying data before carrying forward any prior stop or take-profit.

**Evaluate the primary action first:** `COVER NOW` / `PARTIAL COVER` / `RETAIN`. A price stop and take-profit ladder are fallback controls, not permission to retain a position that the mechanical cover framework already closes. If a §7 cover trigger fires, it outranks the level comparison.

Build at least these candidates:

1. Actual live venue stop/order state from the ledger.
2. The prior report's ratcheted stop (the S6 baseline).
3. Contract-native structural invalidation: swing high, rejection high, major MA, gap/breakout level, or channel-specific structure.
4. Volatility control: current ADR(5), the 1.5×ADR initial-stop floor, and the phase ceiling. For an existing position the floor is context only; S6 still permits tightening through it.
5. Underlying/reference-market equivalent, cross-checked against the traded contract. **Execution is anchored to the traded instrument**; never transfer an index/spot level through an assumed ratio without verifying the contract itself.
6. Event/gap/liquidity control through the time stop, including closed-underlying/24×7-perpetual behavior.
7. `COVER NOW` at the live executable price.

Score every non-vetoed level on a 10-point board:

| Dimension | Points | Test |
|---|---:|---|
| Thesis invalidation | 0–3 | Does crossing the level actually disprove the short thesis/channel? |
| Noise survival | 0–2 | Is it outside ordinary ADR/ATR and local wick noise without exceeding the phase ceiling? |
| Execution quality | 0–2 | Correct instrument, trigger type, reduce-only semantics, full quantity coverage, realistic slippage? |
| Capital/liquidation control | 0–2 | Loss in USD and % of book stated; liquidation remains beyond the stop? |
| Event/liquidity robustness | 0–1 | Does it account for the next tier-1 catalyst, market closure, and gap risk? |

**Hard veto before scoring:** any stop that widens versus S6; breaches the phase ceiling; leaves liquidation at/inside the stop; uses stale/unmapped pricing as fact; lacks executable order semantics; or conflicts with a live §7 cover trigger. A higher point total never rescues a vetoed candidate. Ties resolve toward the **tighter valid stop** on the short side.

Then research a **take-profit / cover ladder** from contract-native support, moving averages, gaps, volume structure, scenario bands and the time stop. Print each target's price, reduce-only quantity, position share, expected PnL after known fees/funding, and reward/risk from both entry and current mark. Quantities must sum to the remaining position. Reject a sole remote take-profit whose required move is implausible inside the time stop or absent from the probability matrix. State the ratchet after each fill; later stops may move down only.

Every live-position report prints an **Open-Position Best-Level Audit** containing: data timestamp; primary action; candidate table with scores/vetoes; selected best-available stop; current venue order versus recommended order; target ladder; trigger type (`MARK_PRICE` / `CONTRACT_PRICE`) and price protection; loss at stop; liquidation distance; event calendar; ratchet check; and alternatives rejected. Recommendations are not executions—say explicitly whether any live order was actually changed.

Optional machine encoding (recommended until lint enforcement exists):

```json
"position_controls": {
  "required": true,
  "status": "BEST_AVAILABLE|NO_VALID_LEVEL|DATA_LIMITED",
  "certainty": "best-available-not-absolute",
  "primary_action": "COVER_NOW|PARTIAL_COVER|RETAIN",
  "selected_stop": null,
  "current_venue_stop": null,
  "targets": [],
  "ratchet_pass": true,
  "orders_changed": false,
  "data_as_of": null
}
```

This audit changes no score, gate, size cap, stop ceiling, time stop, cover trigger, funding/carry veto, or S6 ratchet. Those rules remain authoritative.

#### Carry Cost Ledger (mandatory section when any short is live)

| Phase | Entry Date | Notional | Funding Annualized at Entry | Cumulative Funding Paid | Days Held |
|---|---|---|---|---|---|

State assumed carry-cost-to-target as a % of expected gain. **If carry > 40% of target gain, the trade is structurally bad — do not enter, regardless of score.**

**Filling the realized column from the ledger — and the sign trap that will invert it if you do not read this.** *Cumulative Funding Paid* is filled from `futures.funding_by_asset[].funding_usd` in the position snapshot. That figure is **account cashflow**: negative means funding was **paid out of the account**, positive means received — whichever side the position is on. This framework's *"positive funding = income to a short"* describes the **market rate**, which is direction-agnostic. They are different quantities and they **invert**: a long paying positive-rate funding books *negative* in the ledger. Use the ledger figure **only** for this realized column, and read its `funding_sign_convention` string inline before transcribing a number. *Funding Annualized at Entry* stays a **live rate quote** and never comes from the ledger.

**Grain gap, stated not hidden.** This ledger is per **tranche**; the account's funding is recorded per **symbol** and never will have a tranche dimension (futures never enters the cost-basis engine). When more than one tranche is live in the same symbol, the per-symbol total must be **allocated by hand** across the rows — say that you allocated it and on what basis. It is 1:1 only while a single tranche is live. Do not present an allocated figure as if the ledger measured it per tranche.

#### Report-specific deal attribution (immutable origin)

Every machine report dated on/after 2026-08-12 publishes a versioned `tagging.registry`. Each applicable phase entry contains the exact report-derived tag (for example `FR-A-1A-SP500-20260812-1542`), an `AUTHORIZED`, `LOCKED`, `STAND_DOWN`, or `UNVERIFIED` decision, instrument class, and immutable report filename/date/time. A SPY execution against an SP500 report uses SP500 in the tag. Channel `none` uses the recorded FR-A rubric vocabulary conservatively. There is no `FR-B-3`.

Attribution is recorded in the accounting app with `reportFile`, `phase`, `entryRoute` (`MECHANICAL`, `FR_S1`, `FR_S2`, or `MANUAL`), and alignment (`AUTHORIZED`, `AGAINST_REPORT`, or `UNVERIFIED`). A LOCKED/STAND_DOWN origin requires explicit acknowledgement and persists as `AGAINST_REPORT`; an UNVERIFIED origin stays distinct. The server generates and checks the canonical tag, makes the origin immutable, and requires an explicit clear/reassign correction to change it. Add management-report references separately. Legacy/custom tags and `UNFRAMED` remain accepted; they do not become framework origins.

| Channel / phase | Prefix |
|---|---|
| Channel A Phase 1A / 1B / 2 / 3 | `FR-A-1A-` · `FR-A-1B-` · `FR-A-2-` · `FR-A-3-` |
| Channel B Phase 1A / 1B / 2 | `FR-B-1A-` · `FR-B-1B-` · `FR-B-2-` |
| S1 / S2 analyst-discretion entries | `FR-S1-` · `FR-S2-` |
| Traded outside the framework | `UNFRAMED` |

Use `PUT /api/investments/deal-note` with the structured attribution rather than a client-supplied canonical tag. Exact tags identify report results; prefixes identify channel/framework rollups. Headline framework performance includes authorized origins only, with against-report and unverified segments exposed separately.

### 6.5. Asset Rotation / Cross-Asset Screening (when own-asset scores <11)

When the requested asset scores below Phase-1A eligibility (11), do **not** stop the analysis. Run a short screening pass across the lagging cohort to surface where a short might actually be live, and report it explicitly. The user's question is rarely "is this *specific* asset a short" — it's usually "is there a short anywhere in crypto right now." Answer that question.

**Screening procedure** (qualitative — no need for full skill rerun per candidate):

1. Identify the **lagging cohort**: top-20 mcap assets that are simultaneously (a) within 10% of their own 1-year ATH (precondition for a **Channel A** setup — skip §6.5 entirely in Channel B, where the screen can only return candidates the active channel cannot trade), AND (b) underperforming BTC over trailing 30d by >15%, AND (c) showing positive funding (consensus long).
2. Of the lagging cohort, flag the 1–3 candidates whose qualitative scoring on Sentiment / Funding / Distribution looks highest. State this is a *qualitative scan*, not a full Flying Rocket score — recommend the user run `/flying-rocket-analytics <candidate>` for any flagged name to get the formal score.
3. If no lagging-cohort candidate qualifies, state explicitly: "no actionable short candidate identified in top-20 cohort."

**BTC-hedged expression for alt shorts** — when BTC dominance is in confirmed uptrend AND the candidate is an alt, consider expressing the short as **short alt / long BTC pair** rather than naked short alt. This neutralizes the systemic risk-on beta and isolates the relative-weakness thesis. Note: pair sizing is at the user's discretion (typically dollar-neutral); the framework's phase sizing applies to the *short leg notional*.

### 6.6. Stood-Down Accountability (Jul 2026)

Every STAND DOWN report must name its re-check triggers and open with a FALSIFIER STATUS line grading every prior published falsifier explicitly tagged "(Falsifier: ...)" in the source report on that asset: STANDING / FIRED (with date and one-line mark-to-market) / EXPIRED.

A full standalone FR re-run (or, if no input category has materially changed since the last full FR report, a short logged reaffirmation: verdict + one-paragraph delta) is required within one session when any of:
(a) the inline FR companion computed in the FK series prints ≥9;
(b) a shorts-dominated liquidation/squeeze day occurs on the asset (≥$100M short liqs or the asset leads a squeeze);
(c) a NAMED falsifier's specific condition has moved materially closer to firing (e.g., price within 10% of the stated falsifier level) since the last report;
(d) any self-declared falsifier in the last FR report FIRES (log it: "falsifier fired" is a required header line in the re-run);
(e) **the asset newly satisfies the Channel B regime test** (added 2026-07-27) — it crosses >20% below its 1-year ATH with a falling 200dma and price beneath it, having not been in that state at the last report. Before this revision that crossing meant the framework went permanently dark on the asset; it now means the *other* channel just became live, and nothing else in §6.6 would catch it. The reverse crossing (a weekly close reclaiming the 200dma) is already a mandatory cover under §7 and needs no separate re-check trigger.

A mandatory re-check obligates a fresh look, not a fresh trade — it inherits all existing caps/thresholds/vetoes unchanged and may validly re-confirm STAND DOWN. Silence is only permitted while all published falsifiers are STANDING. A companion framework's (FK's) score threshold crossing does NOT independently trigger this note — it belongs to a different discipline tier under Hard Rule 6.

**Unresolved contradiction, flagged not fixed (C5, 2026-08-07):** Fallen Knives' cross-validation section requires a standalone FR report precisely when the FK score crosses a phase-unlock threshold — the trigger this line just excluded. Left standing per owner decision (2026-08-07 compaction) rather than adjudicated inline; flagged for the next `framework-calibration` run to resolve with reports as evidence — see both REVISION-LOG.md files.

### 7. Cover / Exit Framework (Symmetric)

Covers execute LIFO (most-recently-added tranche first). Apply mechanically.

**Every trigger in this table reads the MECHANICAL score (S1).** Where a row says "score," it means the mechanical reading, and where it compares against a peak, that peak is also the mechanical one. A discretionary term may never delay, soften, or suppress a cover.

| Trigger | Action | Rationale |
|---|---|---|
| **Mechanical** score drops ≥5 points from its **mechanical** peak | Cover 25% | Top-signal eroding |
| **Channel B only:** weekly close reclaims the 200dma | **Cover 100% of Channel B tranches** | The regime the channel is defined by has ended — this is Channel B's equivalent of a thesis void, and it is not discretionary |
| **Channel B only:** Bear Structure Integrity leg scores 0/3 | **Cover 100% of Channel B tranches** | Same, measured on the rubric rather than the single MA |
| **Channel A only:** F&G drops below 40 | Cover 25% | Sentiment regime change |
| **Channel A only:** MVRV-Z drops below 2 (BTC/ETH) | Cover 50% | Valuation reset achieved |
| **Channel A only:** asset breaks below 50-day MA on daily close with volume | Cover 25% | Technical confirmation |
| Funding flips negative for ≥3 consecutive intervals | **Cover 50%** | Squeeze fuel building under shorts — exit before forced |
| **Mechanical** score ≥15 (i.e., trade still wins) but ANY time stop hit | Cover that tranche, reassess | Decay discipline — do not let shorts age indefinitely. Per S6 the clock never extends |
| Hard stop hit on any tranche | **Cover that tranche immediately** | No exceptions |
| Narrative break to the UPSIDE (surprise ETF approval, major regulatory win, sovereign adoption, etc.) | **Cover 100%** | Thesis voided |
| Fallen Knives score ≥12 on same asset (Jul 2026: cite the PUBLISHED same-trading-day or most recent prior close FK score when one exists; re-derive only when none exists and disclose "re-derived, unpublished." If a re-derivation and a published score disagree across the 12 boundary, the trigger FIRES — strictest-wins/max of the two readings — and the divergence must be disclosed and reconciled next report) | **Cover 100%** | Inverse framework signals accumulation zone — directly contradicts thesis |

**The FK cross-check in Channel B (2026-07-27) — the force-cover holds, the *consistency law* is rescoped.** Channel B is defined as an asset >20% below its 1-year ATH under a falling 200dma: precisely the tape where Fallen Knives scores its highest. FK 12–15 alongside a Channel B FR of 11–15 is therefore an **expected co-occurrence**, not the evidence of internal inconsistency that Hard Rule 5's both-≥12 test was written to catch. The two frameworks are scoring different objects on different horizons — FK asks "is this asset worth owning for the next two quarters," Channel B asks "is *this three-week bounce* exhausted." Those can both be true.

Resolution, in two parts:

1. **The FK ≥12 force-cover is unchanged and still fires** — cover 100%, no carve-out, in either channel. It is a protective trigger and this revision does not weaken protective triggers. The practical consequence is that **Channel B is unavailable whenever the companion FK score is ≥12**, and that is the correct behaviour: do not short a bounce while the long-side framework is calling a generational accumulation zone. State the companion score in every Channel B report; if it is ≥12, stand down and say the force-cover is what stood you down.
2. **Hard Rule 5's both-≥12 *inconsistency* flag is Channel A only.** In Channel B, both frameworks reading ≥12 does not mean an input is broken — it means the short must not be open, which part 1 already enforces. Print "Hard Rule 5: Channel B — level-based inverse consistency not evaluable (different scored objects); FK ≥12 force-cover governs instead" rather than a bare ✅ or a false inconsistency flag.

This is a real coverage limitation and worth stating plainly: it makes Channel B unavailable in the deepest fear, which is exactly where the largest bear rallies occur. It is the price of not weakening a cover trigger, and it is the right price to pay.

State current cover-trigger status and remaining position.

### 8. Critical Watchlist

| Time (EST) | Event | Asset Impact | Short Implication |
|---|---|---|---|

Highlight events that could squeeze shorts: ETF deadlines, Fed decisions, large unlock cliffs (could go either way), regulatory deadlines, major options expiry (OPEX), earnings of crypto-adjacent stocks.

### 9. Analyst Read (mandatory — see S4)

Free-form. What you actually think, in whatever structure serves it. Required content: what the rubric is missing in either direction, which single input would change the verdict, the strongest argument against the current stance, and the full reasoning behind any S1 term or S2 path used. Not a summary of the sections above — an argument.

Carry the **Discretion Ledger** (S7) and the **Stop Migration Ledger** here. The stop ledger records every stop change with its direction; per S6 a widening entry is a lint error, not an entry.

| Date | Tranche | Old stop | New stop | Direction | Trigger |
|---|---|---|---|---|---|

### 10. Bull vs Bear Scorecard (for the asset price)

Numbered bull (✅) and bear (❌) signals with one-line rationale. **Reminder:** bull signals are bad for shorts.

### 11. Change Log

| Factor | Previous | Current | Direction | Short Impact |

### 12. Strategic Verdict

- Restate: **mechanical score, discretionary term, adjusted score** (all three — the mechanical one is what every protective rule reads), weighted EV, short EV vs carry, F&G, current stance
- 2–3 paragraph synthesis. Voice: **seasoned macro allocator who has shorted multiple bubbles (dotcom, 2008, 2018 crypto, 2022 crypto) and lost money on others** — humility and respect for asymmetry are non-negotiable
- Numbered action items
- "The Pattern" block quote with 2–3 conditional scenarios

## Analyst Discretion Layer

Adopted 2026-07-27, mirroring the Fallen Knives layer of the same date at short-side strictness. The rubric is a coincident gauge built from a handful of measurable inputs. The analyst sees things the rubric cannot: a funding flip mid-session, a borrow rate doubling, an exchange outage, a Fed speaker, a narrative turning, an order book thinning. The backtest is explicit that this matters here more than on the long side — Channel B's mechanical trigger fires only ~3–7×/year (7 signals in the 12-month in-sample window, 16 in 3.1 years of ETH out-of-sample, 13 in 4.5 years of BTC) and is right under half the time, with expectancy carried by a fat right tail. **A signal like that is a candidate generator, not an edge.** The selection among candidates is exactly the analyst's job, and this layer gives that judgment bounded, audited weight.

Bounded and audited. Not unlimited.

### S1 — Discretionary score adjustment (±2)

The analyst may add or subtract up to **2.0 points**, in **0.5 steps**, from the summed legs. Every report states the term explicitly — **including when it is 0.0**, which is a positive assertion that the rubric captured the tape, not an omission. A missing term is a lint error, not a default of zero.

Each non-zero adjustment names, in one line each: the specific factor, why the rubric cannot see it, and the direction. "Feels toppy" is not a reason. "Borrow on the primary venue went from 4% to 31% annualized in 48h, which the carry ledger only reflects at next entry" is.

**Governing rule — S1 buys entries, never exits.** Two numbers now exist and every rule must name which it reads:

- **Mechanical score** = `min(channel_cap, clamp(round(Σ legs + squeeze-trap penalty + bounce-maturity floor), 0, 20))` — with **no** discretionary term. The correlation and squeeze-trap **gate** surcharges are not in this formula: they move the gate floor, not the score.
- **Adjusted score** = `min(channel_cap, clamp(mechanical + discretionary, 0, 20))`.

**The cap is applied AFTER the discretionary term, not before it.** Discretion may not reach past a phase-of-cycle cap: a mechanical 14 under the 10–20% cap plus a +2 is still **14**, not 16, and Phase 2 stays locked exactly as the cap says. The cap is a structural statement about where in the cycle the asset sits, and no analyst read changes where in the cycle the asset sits. Likewise the squeeze-trap penalty and the bounce-maturity floor sit *inside* the mechanical score, so the adjusted score inherits them — a +2 cannot buy back the −2 that a crowded-short tape or an immature bounce just imposed, because it is added to an already-penalised number rather than to the raw legs.

**The adjusted score is read by deployment/unlock rules ONLY** — the Phase 1A/1B/2 score lines and the §5 probability grid.

**Every protective rule reads the MECHANICAL score, without exception:**

- every §7 cover/exit trigger, including the score-drop-from-peak row — **both** the peak and the current reading are mechanical, so a +2 at the peak can never manufacture a 5-point "decline" that trims the book, and a +2 today can never mask a real one;
- the Fallen Knives ≥12 force-cover cross-check (Hard Rule 5);
- the §7 Cover-Trigger Preflight veto;
- Phase 3's score line;
- the +3% minimum-edge filter and the 40%-carry veto;
- the Verdict-Confidence Collar's trigger conditions;
- Channel B's gate-8 veto and its 0/3-bear-structure void.

A short book must never be able to talk itself out of an exit. Discretion opens positions; only the mechanical reading closes them.

**There is no short-side Override channel.** The Fallen Knives Deep-Value Override has no analogue here and never will (see the 2026-06-11 log). S1 and S2 are the only two non-mechanical channels on this side, which also means the discretion caps below cannot be circumvented by a third path.

### S2 — Analyst Conviction Path (substitutes for one gate)

When the score line for **Phase 1A only** is met but the gate count is **exactly one short**, the analyst may substitute a written conviction argument for that one missing gate, at **half size** (2.5% instead of 5%).

Conditions, all required:
1. Phase 1A's score line is met on the **adjusted** score.
2. The gate count is short by **exactly one** — never two.
3. The §7 preflight PASSes, with no borderline-WARNING entries.
4. The missing gate is **named**, with what would make it fire and by when.
5. In Channel B, the **stall-confirmation** condition holds. S2 cannot substitute for it.
6. In Channel B, **gate 8 is ✅** (funding not sustained-negative). S2 cannot substitute for a veto gate in either channel.

S2 unlocks Phase 1A and nothing deeper. It does not stack: one S2 fill per position sequence, and a second S2 requires the first to be closed.

### S3 — Form is free, substance is mandatory

Prescribed *phrasings*, section orders, and boilerplate lines are guidance, not law. Restructure a report, merge sections, drop a table that says nothing today, lead with what matters.

What is **not** negotiable, ever: Hard Rule 1 (live data, source + timestamp on every figure), the machine block and its lint, the stop and time-stop mandates, the size caps, the cover triggers, the carry veto, the two-tier certainty rule, the collar, and every rule in this layer. Substance rules bind; sentence templates do not.

When you depart from a prescribed form, do not announce it. Just write the better report.

### S4 — The Analyst Read (report §9, mandatory)

Every report carries a free-form **Analyst Read** (report §9) — the analyst's own argument in their own structure. See that section for what it must cover.

### S5 — The Discretion Tax

Every non-mechanical entry pays, on all four axes:

| | Mechanical unlock | S1/S2 analyst channel |
|---|---|---|
| Size | full phase size | **half** |
| Price stop | phase default (Ch. A 8/10/12/15%, Ch. B 6/6/8%), inside the ADR noise band | **the tightest of** 6% above fill, the active channel's phase default, and the structure level — never wider than the mechanical tranche it sits beside, and still no tighter than the 1.5×ADR(5) floor |
| Time stop | phase default (21/28/35d) | **14 days** |
| Deepest phase | Phase 3 (Channel A) | **Phase 1A only via S2; Phase 2 max via S1** |

A tranche is on the analyst channel if the discretionary term was **load-bearing** — i.e. the mechanical score alone would not have cleared the line — or if it filled via S2. A tranche that clears its line on the mechanical score is a mechanical unlock even if a non-zero S1 term happens to be printed.

**Capital caps:** analyst-channel tranches may total no more than **20% of the dedicated short book** (40% of the 50% maximum). This binds across both channels and the whole book, not per report.

**Machine-block encoding (mandatory, so the caps cannot fail open):** every tranche that is not a plain score-plus-gate unlock is written **`discretionary: true`** with its `channel` (`"S1"` or `"S2"`). The flag means "counts toward the 20% cap"; the `channel` value drives the stop and phase ceilings. A tranche with a load-bearing discretionary term written `discretionary: false` is a lint error, not a judgment call.

### S6 — The Ratchet (stops never widen)

Once set, a stop moves **toward price only** — for a short, that means **down, never up**. This binds the analyst, the mechanical rules, and every future report on the position.

- A stop may tighten on any evidence. It may never loosen on any evidence, including a higher score, a stronger thesis, a "temporary" squeeze, or a wider volatility read.
- The **only** exception: the first time a stop is set for a position sequence. There is no prior stop to ratchet against.
- **A position sequence is not ended by a stop-out.** It is ended by the *regime* ending — a Channel B 200dma reclaim, a channel change, or 30 calendar days with no live tranche on the asset. Without this, the exception swallowed the rule: a full stop-out leaves the book flat, so a stopped-out short could re-enter with a wider stop than the one that just failed, and the ratchet would bind only while you were winning. Trimming does not reset it either.
- A time stop ratchets the same way: it may shorten, never extend. **"Mechanical score ≥15 but the time stop hit" covers the tranche.** A short that needs more time is a short that was wrong about *when*, which on this side of the book is indistinguishable from being wrong.
- Every stop change is logged in the Stop Migration Ledger with its direction; a widening entry is a lint error.

### S7 — The Discretion Ledger

Every report carries a running table of discretionary actions taken on this asset, so the channel can be graded rather than trusted:

| Date | Channel | S1 term | S2 used? | Load-bearing? | Reason (one line) | Outcome to date |
|---|---|---|---|---|---|---|

The next calibration grades this ledger as its own channel, with an explicit counterfactual: what would the mechanical framework alone have done, and did discretion beat it? A discretion layer that cannot show its work is a discretion layer that gets removed.

## Score Interpretation

**Channel A ladder** (Channel B reads its own floors — ≥13/15/17 for 1A/1B/2, no Phase 3 — stated at each phase's Unlock gates line, not repeated here):

| Score — adjusted, except the 19+ row which is mechanical (S1) | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — no short edge |
| 6–8 | Watch List | PREPARE — refresh thesis, mark key levels |
| 9–10 | Caution Zone | NO ENTRY — insufficient confirmation; squeeze risk still dominant |
| 11–12 | Probe Eligible | PHASE 1A unlock if gates ≥3 (or S2 at half size) |
| 13–14 | Add Eligible | PHASES 1A–1B eligible |
| 15–18 | Conviction | PHASES 1A–2 eligible |
| 19+ | Generational Top | PHASES 1A–3 eligible — Channel A only, mechanical score only, these are rare |

**Note (revised 2026-07-27):** the long side unlocks Phase 1A at 8, the short side at 11. The 3-point spread is the asymmetry tax at the door — *wider* than the 2 points it replaced, even though both numbers came down. The rest of the tax moved to where Hard Rule 6 says it does more good — its seven never-relax items govern here verbatim.

> **Reconciliation note (Jun 2026 — mirror of the Fallen Knives footnote):** these stances describe **score eligibility only.** Actual short deployment is *additionally* gated by the gate count (§4), the **phase-of-cycle hard cap** (§4), the **squeeze-trap penalty** (§4), and **carry economics** (§6, Carry Cost Ledger — carry > 40% of target = no trade). A "Conviction" 15–18 row is **not** a deploy mandate: the cap, penalty, or carry can each veto every phase. State the gate/cap/penalty-limited reality explicitly in the verdict — never let the table imply more short exposure than the full gating authorizes.

## Asset Generalization

| Metric | BTC | ETH | Major Alts | Smaller Alts |
|---|---|---|---|---|
| Sentiment | F&G | F&G + ETH funding | Altcoin Season Index + funding + social | Funding + social — **require explicit user OK to short** |
| Valuation | MVRV-Z (Glassnode) | MVRV-Z (Glassnode) | Distance from ATH | Distance from ATH |
| ETF flows | Farside, SoSoValue | Farside, SoSoValue | N/A — use spot exchange inflows | N/A |
| Borrow | Deep, cheap | Deep, cheap | Variable, can spike | **Often unavailable or expensive — flag** |
| Liquidity | Highest | Very high | Adequate | **Thin — squeeze risk elevated** |

**Default asset:** BTC. Prompt for confirmation if context ambiguous. **For smaller alts, explicitly state borrow/liquidity risk in the report and require user acknowledgement before producing deployment recommendations.**

**Adapted Non-Crypto Reads — OUT OF DECLARED SCOPE.** Equity indices and metals are NOT in this framework's scope and this does not put them in it.
**If the requested asset is not crypto: STOP before producing any number and Read `NON-CRYPTO-ANNEX.md` in full** (sibling file, same directory). It carries ten binding restrictions — frozen gate schema (denominator 6), valuation ceiling, the NOT-MET rule, pinned rounding, correlation suppressor, Channel B unavailable, Phase 3 unreachable, two inert protections, the Hard-Rule-6 disclosure line, the N=1 caveat. **A non-crypto read produced without reading it is invalid and must not be published.**

## Analytical Principles

1. **Shorting is not the mirror of longing.** Carry bleeds, drift hurts, squeezes kill. Operate with more humility.
2. **Real-time data is non-negotiable.**
3. **Top-picking is a confirmation game, not a prediction game.** Wait for evidence — distribution candles, breadth divergence, funding extremes — not feels. **Anti-bear-trap (added Jun 2026, mirror of Fallen Knives' anti-bull-trap):** pullbacks within an uptrend are suspect — *never declare a top "confirmed" on a single down-week.* Require **trend-structure breakdown** (loss of a major MA or a confirmed lower-high) before deploying the distribution thesis. A dip in a structural bull tape is squeeze fuel, not a top. **(Channel A. In Channel B the trend-structure breakdown is the entry precondition rather than the thing being waited for; Principle 14 governs there.)**
4. **Carry > target / 0.4 → no trade.** Even a perfect thesis loses money to bleed if the move takes too long.
5. **Always have a time stop.** Decay discipline.
6. **Always have a price stop.** No exceptions.
7. **Never max-short.** Cap 50% of dedicated short book. Reserve dry powder for averaging at higher (worse) prices ONLY if thesis intact.
8. **Cross-validate with Fallen Knives.** If both frameworks light up on the same asset, something is broken.
9. **Cover into weakness, not strength.** LIFO covers as score drops; this is the symmetric mirror of pyramid-adding on the way up for longs.
10. **Regulatory and adoption catalysts are the #1 short killer.** Stay current; cover into surprises.
11. **Two-tier certainty (Jun 2026, mirror of Fallen Knives).** Realized-data statements may use strong language; **forward / regime-resolution claims must carry a probability OR an `IF→THEN` plus a named falsifier.** The score is a coincident euphoria gauge, not a forecast — never let a high score license a confident *price* prediction.
12. **Verdict-confidence collar (Jun 2026, mirror of Fallen Knives; FR ≥ as strict; symmetrized Jul 2026).** When **|Total Short EV| < 3% (the existing minimum-edge filter) OR the asset is >20% off its 1-year ATH (Channel A stand-down **or Channel B** — in Channel B the cap does not fire but the collar is always on; state that once) OR the squeeze-trap penalty is active**, you are prohibited from declaring a top "confirmed" or a distribution regime "resolved." **The collar is TWO-SIDED:** under the same conditions you are equally prohibited from declaring, in any section (including §5 trend-term prose), a bottom "in," a correction "over/behind us/complete," an uptrend "confirmed," a squeeze setup "unwound," or a positioning book "bailed out." The lexicon confirmed/resolved/unwound/behind us may only attach to a defined structural test that has completed (weekly close, ≥5 sessions held, finalized monthly data) as a forward-looking resolution claim; otherwise the claim must be an IF→THEN with a named falsifier. Plain descriptive-present-tense structural statements (e.g., "gold is in a confirmed downtrend" describing current position relative to moving averages) remain permitted without an IF→THEN wrapper. A stand-down verdict never requires a bullish counter-forecast — the cap and gates are sufficient grounds on their own. **Stand-down remains the modal, correct output** — frame a no-trade verdict as the analysis, not a failure.
13. **Single-observation durability lock (Jul 2026, mirror of FK's already-adopted single-observation durability rule, at ≥ FK strictness).** No flow, funding, or positioning INFLECTION/turn/regime-change claim — in prose, in the Distribution-Evidence leg (including partial/fractional sub-leg credit), or in gate 5 — may rest on fewer than 5 sessions of confirming data or a completed trend-structure event. One-day prints must be reported as "one day ≠ a run" and score as the prior regime. Asymmetry: claims that make a short HARDER (e.g., outflows ending) need the full 5 sessions; claims that make a short easier get no relaxation (Hard Rule 6). **The durability lock is a rubric rule, not a discretion rule** — an S1 term may rest on a single observation, because it is bounded at ±2, logged, and cannot move any protective rule. Say plainly in §9 that the read is one-session-old.

14. **Two channels, one discipline (2026-07-27).** Channel B is not a licence to short a falling market — it is a licence to short an *exhausted rally inside* one. The distinction is the whole of the channel's risk control: shorting the breakdown was tested and it filled at the trough of two of the three falls it caught. If the setup in front of you is a fresh low rather than a stalling bounce, Channel B has no trade, whatever the score says.

15. **The stop is the strategy (2026-07-27).** Channel B's out-of-sample win rate is 29–54%; its expectancy comes from a minority of trades running 20–38% against a worst case held to −6% by the structure stop. That arithmetic only works while the stop holds and the ratchet binds. Any pressure to widen a stop, extend a clock, or "give it room" is a proposal to convert a positive-expectancy channel into a negative one. S6 exists to make that pressure inadmissible rather than merely discouraged.

## Data Source Priority

| Category | Primary | Secondary | Tertiary |
|---|---|---|---|
| Price | CoinDesk, CoinGecko | Yahoo Finance, Investing.com | CoinCodex, asset's exchange |
| Sentiment | Alternative.me | CoinMarketCap, CoinStats | LunarCrush, Santiment, BitDegree |
| ETF Flows | Farside Investors, SoSoValue | CoinDesk, CoinGlass | The Block, Bitbo |
| Derivatives | CoinGlass, Coinalyze | Deribit (options) | Laevitas, Amberdata |
| On-Chain | Glassnode, CryptoQuant | CoinGlass | Checkonchain, CoinMetrics |
| Macro | CNBC, Reuters | Bloomberg, Investing.com | FRED (yields, VIX) |
| Breadth | Yahoo Finance (NYSE A/D) | TradingView | FRED |
| Borrow rates | Asset's primary perp venue | Aave/Compound (DeFi) | — |

## Output

Save the report as a markdown file to:

```
reports/[asset]_flying_rocket_[YYYYMMDD]_[HHMM].md
```

Path is repo-relative, per CLAUDE.md's Output Convention (which also governs the post-save commit/push). Filename uses lowercase asset symbol. Example: `btc_flying_rocket_20260514_1030.md`.

**Machine block + lint (mandatory, Jul 2026; extended 2026-07-27).** Every report ends with a fenced ` ```json machine ` block (schema `report-machine/1`, field list in `./bin/analytics lint-report`). FR-specific enforcement: every tranche in the block must carry BOTH a price `stop` and a `time_stop` (the linter errors on either missing), tranche sizes are checked against 5/10/15/20 with the ≤50% short-book cap, and the cycle cap is cross-checked against the recorded % below 1-year ATH.

Added 2026-07-27, all fail-closed:
- `channel` at report level: `"A"`, `"B"`, or `"none"` — required; `"B"` additionally requires `regime.pct_below_1y_ath > 20` and `regime.ma200_falling === true`, so a Channel B report cannot be written outside the regime that defines it.
- `score.discretionary` required and bounded ±2 on a 0.5 step; `score.mechanical` required and arithmetic-checked against the legs; adjusted = legs + discretionary, clamped [0,20].
- Analyst-channel tranches (`discretionary: true`, `channel` `"S1"`/`"S2"`) enforce the S5 tax: stop ≤6% above fill, `time_stop` ≤14 days, no Phase 3, and ≤20% of the short book in aggregate.
- Channel B tranches: no Phase 3 at any score, ≤30% of the short book in aggregate, `time_stop` ≤21 days (≤28 at Phase 2).
- S6 ratchet: a stop that moved away from price versus the prior report on the same position is an error, not a warning.

Added 2026-07-29:
- **`entry_price` (numeric) on every filled tranche**, alongside the prose `entry` (which keeps its own meaning — which zone, why blocked, blended MTM). This is load-bearing for exactly the rules Hard Rule 6 protects: the stop band (`frStopBand`, including the 1.5×ADR(5) noise floor), the S5 6%-above-fill bound, the S6 ratchet, the per-phase clock limits, the Channel B 30% sub-cap and the 20% analyst cap are **all gated on the fill predicate**, and until this field existed that predicate had never once been true — 152/152 tranches across 39 reports encoded `entry` as prose. A 20%-of-book Phase-3 short at score 0/20 on 0/9 gates with a stop 40% BELOW entry linted clean, with zero warnings. Four of Hard Rule 6's seven never-relax items were written down and unenforced. The linter **warns** when a prose `entry` reads like a fill without an `entry_price` and **errors** on reports dated on/after 2026-07-29. **This relaxes nothing — it makes existing discipline bind.**

Post-save workflow (lint → export-signals → commit + push) per CLAUDE.md's per-report workflow and Auto-push convention; a lint FAIL is fixed, never overridden.

After saving, post a ≤6-line conversational summary:
- Mechanical and adjusted score, channel, and stance
- Top 1–2 changes vs prior (or "first report")
- The single most actionable item — including **explicit no-trade verdict if score insufficient** (this is the most common honest output)

## Voice & Tone

- Write as a **seasoned macro allocator who has both made and lost money shorting bubbles** — calm, data-driven, respectful of asymmetry.
- Has shorted dot-com, GFC, 2018 crypto, 2022 crypto. Has also been squeezed out of tops too early. The scars matter.
- Never use hype language ("zero," "going to zero," "this is the top") or copium.
- Acknowledge: **most tops do not look like tops in real time**. Confirmation requires patience.
- Use probability ranges. Never certainties.
- "Shorts have a clock. Longs have time. Treat them differently."
- "The carry bleeds while you wait. Make sure the wait is worth it."
- **Stand down is the modal output.** In any given month, a score of 0–10 ("no signal" or "watch list") is the expected result 80%+ of the time. The framework is built to be silent most days and decisive on the rare ones. A no-trade verdict is not a failed analysis — it is the analysis. Frame it that way in the verdict, especially when the asset is mid-cycle (>20% off ATH).
- When the phase-of-cycle cap, squeeze-trap penalty, or wrong-asset pre-check fires, **lead the verdict with that finding** rather than burying it under the scoring breakdown. The reader needs to know in the first sentence whether the framework is even applicable.
- Every report ends with numbered, executable action items — including the option of **standing down**, which is often correct.

## Language

English by default. Russian on explicit user request. Default: English. Ask only if ambiguous.


## Framework Revision Log

**Relocated 2026-08-07 → [`REVISION-LOG.md`](REVISION-LOG.md)** (sibling file, same directory).

It had grown to 24% of this file and was loaded into context on **every report run**, while every one of its readers is calibration-time: `./bin/analytics calib-run` (prior-tune re-validation, pre-apply audit) and the `framework-calibration` skill. No report-time rule lives there — an applied tune edits the operative text above, and the log entry is its provenance receipt. Verified at relocation: zero `every report must` / `the report must` mandates in the log.

**Do not re-inline it, and do not compress it into a summary.** The enumerated mechanism lists are what make prior-tune re-validation gradeable; an entry that loses its mechanism names stops being testable. A calibration **appends** a dated entry to the sibling file; historical entries are never silently renumbered or rewritten, and corrections are dated in place. Hard Rule 6's seven non-negotiables are recorded there with the reasoning that produced them — that reasoning is why they survive re-argument.
