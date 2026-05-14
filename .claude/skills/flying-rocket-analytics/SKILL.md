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

The framework treats shorting with **more humility than longing**: higher unlock thresholds, smaller maximum position size (cap **50% total short notional** of the dedicated short book), mandatory stops at every phase, time-decay max-hold limits, and explicit carry-cost accounting.

**Cross-validation rule:** Flying Rocket and Fallen Knives scores should be **inversely related** for the same asset at the same timestamp. If both score ≥12, the framework is internally inconsistent — pause and re-examine inputs before acting.

**Asset scope:** BTC default. ETH, SOL, major alts (top 20 mcap) with metric substitution. **Smaller alts → require explicit user confirmation** due to short-borrow availability and squeeze risk.

## CRITICAL: Real-Time Data is Non-Negotiable

Before writing ANY analysis, fetch live data for ALL categories. Never use memorized or cached data. Always search, verify, cite sources with timestamps.

### Required Data Fetches

1. **Asset price + intraday volatility** — CoinDesk, CoinGecko, Yahoo Finance, asset's primary exchange. Note distance from local ATH.
2. **Sentiment (greed side)** — Alternative.me F&G, CoinMarketCap, social heat (LunarCrush, Santiment if available), retail-leverage proxies.
3. **Spot ETF flows** (BTC/ETH) — Farside, SoSoValue, CoinGlass. Look specifically for **flow inflection from inflows to outflows** and **late-stage parabolic inflows** (both are distribution tells).
4. **Macro risk regime** — VIX level + 5-day Δ, US 10y yield, DXY, gold. Risk-off turns favor shorts.
5. **Equities breadth** — S&P 500, Nasdaq, equal-weight vs cap-weight spread (NYSE A/D, % stocks above 200d). Narrowing breadth in a rally = vulnerability signal.
6. **Derivatives positioning** — Funding rates (target: high positive), open interest delta, long/short ratio, perp basis, options skew/put-call ratio.
7. **On-chain distribution** — MVRV-Z, LTH supply trend (rising distribution rate), exchange inflows (large addresses sending to exchanges), Coinbase Premium turning negative.
8. **Borrow/carry costs** — Perp funding (8h × 3 × 365 = annualized); spot borrow rates where applicable.
9. **Breaking news** — Regulatory wins for crypto (bullish, dangerous for shorts), ETF news, large M&A, treasury company moves, exchange/protocol exploits (bearish, supportive for shorts).

Tag every figure with **source + timestamp**.

## Report Structure

### 1. Header

```
# 🚀 FLYING ROCKET ANALYTICS — [ASSET] — [DATE]
## [CONTEXT LINE]
### Report Generated: [Day], [Date], [Time] EST
### Asset: [ASSET] | Prior Score: [X/20 or "cold start"] | Current Score: [X/20]
### Cross-Check: Fallen Knives Score (same asset, same date): [X/20 or "not run"]
```

### 2. Verified Live Data Points

Tables with **source + timestamp** for every cell:

- **Price & Distance from ATH**: Source | Price | ATH | % from ATH | Timestamp
- **Sentiment**: Source | Reading | Status (extreme fear / fear / neutral / greed / **extreme greed**)
- **ETF Flows (BTC/ETH)**: Window | Net Flow | % of AUM | Inflection? (Y/N)
- **Macro Risk Regime**: VIX | 10y | DXY | Gold | Δ5d
- **Equities Breadth**: SPX/NDX | % above 200d | A/D spread | Source
- **Derivatives**: Funding (8h & annualized) | OI Δ7d | L/S ratio | Perp basis | Skew/PCR
- **On-Chain**: MVRV-Z | LTH 30d Δ | Exchange inflow trend | Coinbase Premium
- **Carry**: Perp funding annualized | Spot borrow (if applicable) | Implied bleed/month

### 3. Critical Developments

Bulleted highest-impact news: regulatory wins/losses, ETF news, macro releases, exchange events, treasury company actions, "obvious tops" cultural signals (mainstream press cover stories, IPO frenzies in adjacent sectors).

### 4. Flying Rocket Composite Score (X / 20)

| Category | Max | Scoring Rubric |
|---|---|---|
| **Euphoria Sentiment** | 5 | F&G or asset equivalent. ≥90 → 5 · 80–89 → 4 · 70–79 → 3 · 60–69 → 2 · 50–59 → 1 · <50 → 0 |
| **Momentum Overextension** | 4 | Weekly RSI. >75 → 4 · 70–75 → 3 · 65–70 → 2 · 60–65 → 1 · <60 → 0. Bonus −1 if monthly RSI also >70 (rare cycle-top condition) — capped at 4 max |
| **Valuation Extreme** | 5 | **Primary (BTC/ETH):** MVRV-Z. >5 → 5 · 3–5 → 4 · 2–3 → 3 · 1–2 → 1 · <1 → 0. **Fallback (alts):** distance from ATH. New ATH or <5% from ATH → 5 · 5–15% → 3 · 15–30% → 1 · >30% → 0. State which used. |
| **Distribution Evidence** | 3 | Count of: (a) LTH supply declining 30d AND profit-taking rate >$500M/day (BTC scale; pro-rate by mcap for ETH/alts), (b) net exchange inflows >30d avg with large-address tagging, (c) ETF flows decelerating or net outflows after sustained inflow regime. 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **Structural Vulnerability** | 3 | Count of: (a) perp funding pinned positive (annualized >25%) ≥7d, (b) put/call ratio <0.6 or 25d skew deeply call-favored, (c) breadth divergence — asset at ATH while equities breadth narrowing OR while majority of other crypto majors are NOT at ATH. 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **TOTAL** | **20** | |

**Correlation / Regime Modifier** (applied after summing):
- Asset–SPX 30d corr >0.7 AND SPX within 3% of ATH (full risk-on regime): score **×0.80** — shorting into systemic risk-on is dangerous regardless of asset-level signals
- Corr 0.2–0.7 (mixed regime): **×1.00**
- Corr <0.2 (decoupled): **×1.05** — crypto-specific weakness easier to express
- Corr <0 AND asset weakening while SPX rises (negative decoupling): **×1.15** — idiosyncratic distribution most actionable

Round to nearest integer. State raw, modifier, adjusted.

#### Confirmation Gates (X / 9) — drives phase unlocks, not scoring

Mark each ✅ / ⚠️ / ❌:

1. F&G ≥80 (or asset-equivalent extreme greed) sustained ≥7 days
2. Weekly RSI >70
3. MVRV-Z >3 (BTC/ETH) OR within 5% of ATH (alts)
4. Perp funding annualized >25% for ≥3 funding intervals
5. ETF flows decelerating or net outflows (BTC/ETH) OR retail euphoria proxies elevated (alts)
6. Coinbase Premium negative ≥3 consecutive days (US institutions selling)
7. LTH 30d distribution rate >$500M/day (pro-rated for non-BTC)
8. Breadth divergence: asset at new ATH while >50% of top-20 mcap peers off-ATH by >15%
9. Macro catalyst neutral-to-negative (Fed hawkish surprise, geopolitical escalation, regulatory negative)

Count ✅ only.

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

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value** = Σ (probability × midpoint).

**Short EV** = (spot − weighted EV) / spot × 100. **A short is EV-positive only if this number is positive AND exceeds annualized carry cost prorated for expected hold period.** State both numbers.

### 6. Short Deployment Strategy

**Splits: 5 / 10 / 15 / 20** (max **50% of dedicated short book**, NEVER 100%). Front-loaded but smaller than long phases — shorts demand more humility.

**Cold start:** every phase begins as `DRY POWDER`.

**Mandatory:** every deployed phase has a **hard stop** (price level above entry) and a **time stop** (max hold). Both must be stated at entry. Violation of either = automatic cover of that tranche.

#### Phase 1A — Probe (5%)
- **Unlock gates:** adjusted score ≥13 AND ≥4 of 9 gates ✅
- **Entry zone:** ASSET-SPECIFIC. State range.
- **Hard stop:** +8% above entry OR daily close above local high — whichever tighter
- **Time stop:** 21 calendar days
- **Status:** DRY POWDER / SHORT ([entry]) / COVERED / STOPPED

#### Phase 1B — Add (10%)
- **Unlock gates:** adjusted score ≥15 AND ≥5 gates ✅ AND Phase 1A still in profit OR scratch
- **Entry zone:** [range — must be at higher price than 1A entry, confirming overextension]
- **Hard stop:** +10% above blended cost
- **Time stop:** 28 days from this entry
- **Status:** DRY POWDER / SHORT / COVERED / STOPPED

#### Phase 2 — Conviction (15%)
- **Unlock gates:** adjusted score ≥17 AND ≥6 gates ✅ AND macro catalyst neutral-to-negative AND correlation regime not full risk-on
- **Hard stop:** +12% above blended cost
- **Time stop:** 35 days
- **Status:** DRY POWDER / SHORT / COVERED / STOPPED

#### Phase 3 — Generational Short (20%)
- **Unlock gates:** adjusted score ≥19 AND ≥8 gates ✅ AND clear distribution candle (weekly bearish engulfing or break of key support on volume) AND ETF flows confirmed net outflow ≥5 sessions
- **Hard stop:** +15% above blended cost
- **Time stop:** 49 days
- **Status:** DRY POWDER / SHORT / COVERED / STOPPED

**Total max short exposure: 50% of dedicated short book. Remaining 50% is structural dry powder for adverse moves / averaging at higher prices ONLY if thesis intact AND score has not deteriorated.**

#### Carry Cost Ledger (mandatory section when any short is live)

| Phase | Entry Date | Notional | Funding Annualized at Entry | Cumulative Funding Paid | Days Held |
|---|---|---|---|---|---|

State assumed carry-cost-to-target as a % of expected gain. **If carry > 40% of target gain, the trade is structurally bad — do not enter, regardless of score.**

### 7. Cover / Exit Framework (Symmetric)

Covers execute LIFO (most-recently-added tranche first). Apply mechanically.

| Trigger | Action | Rationale |
|---|---|---|
| Adjusted score drops ≥5 points from peak | Cover 25% | Top-signal eroding |
| F&G drops below 40 | Cover 25% | Sentiment regime change |
| MVRV-Z drops below 2 (BTC/ETH) | Cover 50% | Valuation reset achieved |
| Asset breaks below 50-day MA on daily close with volume | Cover 25% | Technical confirmation |
| Funding flips negative for ≥3 consecutive intervals | **Cover 50%** | Squeeze fuel building under shorts — exit before forced |
| Adjusted score ≥15 (i.e., trade still wins) but ANY time stop hit | Cover that tranche, reassess | Decay discipline — do not let shorts age indefinitely |
| Hard stop hit on any tranche | **Cover that tranche immediately** | No exceptions |
| Narrative break to the UPSIDE (surprise ETF approval, major regulatory win, sovereign adoption, etc.) | **Cover 100%** | Thesis voided |
| Fallen Knives score ≥12 on same asset | **Cover 100%** | Inverse framework signals accumulation zone — directly contradicts thesis |

State current cover-trigger status and remaining position.

### 8. Critical Watchlist

| Time (EST) | Event | Asset Impact | Short Implication |
|---|---|---|---|

Highlight events that could squeeze shorts: ETF deadlines, Fed decisions, large unlock cliffs (could go either way), regulatory deadlines, major options expiry (OPEX), earnings of crypto-adjacent stocks.

### 9. Bull vs Bear Scorecard (for the asset price)

Numbered bull (✅) and bear (❌) signals with one-line rationale. **Reminder:** bull signals are bad for shorts.

### 10. Change Log

| Factor | Previous | Current | Direction | Short Impact |

### 11. Strategic Verdict

- Restate: adjusted score, weighted EV, short EV vs carry, F&G, current stance
- 2–3 paragraph synthesis. Voice: **seasoned macro allocator who has shorted multiple bubbles (dotcom, 2008, 2018 crypto, 2022 crypto) and lost money on others** — humility and respect for asymmetry are non-negotiable
- Numbered action items
- "The Pattern" block quote with 2–3 conditional scenarios

## Score Interpretation

| Adjusted Score | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — no short edge |
| 6–10 | Watch List | PREPARE — refresh thesis, mark key levels |
| 11–12 | Caution Zone | NO ENTRY — insufficient confirmation; risk of squeeze still high |
| 13–14 | Probe Eligible | PHASE 1A unlock if gates ≥4 |
| 15–17 | Conviction | PHASES 1A–2 eligible |
| 18+ | Generational Top | PHASES 1A–3 eligible — these are rare |

**Note:** the long-side framework lets you deploy starting at 11. The short side requires 13. The 2-point spread is the asymmetry tax — shorts demand more confirmation because adverse moves cost more.

## Asset Generalization

| Metric | BTC | ETH | Major Alts | Smaller Alts |
|---|---|---|---|---|
| Sentiment | F&G | F&G + ETH funding | Altcoin Season Index + funding + social | Funding + social — **require explicit user OK to short** |
| Valuation | MVRV-Z (Glassnode) | MVRV-Z (Glassnode) | Distance from ATH | Distance from ATH |
| ETF flows | Farside, SoSoValue | Farside, SoSoValue | N/A — use spot exchange inflows | N/A |
| Borrow | Deep, cheap | Deep, cheap | Variable, can spike | **Often unavailable or expensive — flag** |
| Liquidity | Highest | Very high | Adequate | **Thin — squeeze risk elevated** |

**Default asset:** BTC. Prompt for confirmation if context ambiguous. **For smaller alts, explicitly state borrow/liquidity risk in the report and require user acknowledgement before producing deployment recommendations.**

## Analytical Principles

1. **Shorting is not the mirror of longing.** Carry bleeds, drift hurts, squeezes kill. Operate with more humility.
2. **Real-time data is non-negotiable.**
3. **Top-picking is a confirmation game, not a prediction game.** Wait for evidence — distribution candles, breadth divergence, funding extremes — not feels.
4. **Carry > target / 0.4 → no trade.** Even a perfect thesis loses money to bleed if the move takes too long.
5. **Always have a time stop.** Decay discipline.
6. **Always have a price stop.** No exceptions.
7. **Never max-short.** Cap 50% of dedicated short book. Reserve dry powder for averaging at higher (worse) prices ONLY if thesis intact.
8. **Cross-validate with Fallen Knives.** If both frameworks light up on the same asset, something is broken.
9. **Cover into weakness, not strength.** LIFO covers as score drops; this is the symmetric mirror of pyramid-adding on the way up for longs.
10. **Regulatory and adoption catalysts are the #1 short killer.** Stay current; cover into surprises.

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
/Users/eternal/Desktop/Trading Claude Analytics/reports/[ASSET]_flying_rocket_[YYYYMMDD]_[HHMM].md
```

Filename uses lowercase asset symbol. Example: `btc_flying_rocket_20260514_1030.md`.

After saving, post a ≤6-line conversational summary:
- Adjusted score and stance
- Top 1–2 changes vs prior (or "first report")
- The single most actionable item — including **explicit no-trade verdict if score insufficient** (this is the most common honest output)

## Voice & Tone

- Write as a **seasoned macro allocator who has both made and lost money shorting bubbles** — calm, data-driven, respectful of asymmetry
- Has shorted dot-com, GFC, 2018 crypto, 2022 crypto. Has also been squeezed out of tops too early. The scars matter.
- Never use hype language ("zero," "going to zero," "this is the top") or copium
- Acknowledge: **most tops do not look like tops in real time**. Confirmation requires patience.
- Use probability ranges. Never certainties.
- "Shorts have a clock. Longs have time. Treat them differently."
- "The carry bleeds while you wait. Make sure the wait is worth it."
- Every report ends with numbered, executable action items — including the option of **standing down**, which is often correct.

## Language

English by default. Russian on explicit user request. Default: English. Ask only if ambiguous.
