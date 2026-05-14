---
name: fallen-knives-analytics
description: "Proprietary crypto market analysis framework for identifying optimal accumulation points during periods of extreme fear — and trim/exit points during euphoria. Works for ANY crypto asset (BTC, ETH, SOL, major alts, smaller alts) with asset-appropriate metric substitution. Use whenever the user asks for a Fallen Knives update/score/analytics, a buy/sell/hold assessment on a crypto asset, a fear-or-euphoria readout, accumulation-zone analysis, deployment strategy, exit planning, or any variant of 'update fallen knives [asset]'. This skill MUST fetch live data from the internet before any analysis — never rely on stale or memorized data. Output is a structured multi-section report with composite scoring, derived probability matrix, phased deployment gates, and a symmetric exit framework."
---

# Fallen Knives Analytics — Crypto Accumulation & Exit Framework

## Overview

Fallen Knives is a proprietary framework for two symmetric tasks:

1. **Accumulating** crypto exposure during periods of extreme fear, in disciplined phases, with cold-start support
2. **Trimming/exiting** that exposure during euphoria or narrative breaks

It synthesizes sentiment, momentum, valuation, capitulation evidence, holder behavior, macro/catalyst direction, and a correlation-regime gate into a single composite score (0–20). The score drives a probability matrix, deployment gates, and a symmetric exit framework.

**Asset scope:** BTC by default. Also applies to ETH, SOL, major alts (top 20 by mcap), and smaller alts with asset-appropriate metric substitution (see §Asset Generalization).

## CRITICAL: Real-Time Data is Non-Negotiable

**Before writing ANY analysis, fetch live data for ALL categories below.** Never use memorized or cached data. Always search, verify, cite sources with timestamps.

### Required Data Fetches

1. **Asset price** — CoinDesk, CoinGecko, Yahoo Finance, Investing.com, CoinCodex, the asset's primary exchange. Report consensus range.
2. **Sentiment** — Alternative.me F&G (for BTC, also a proxy for ETH/large caps), CoinStats, CoinMarketCap; for alts also fetch Altcoin Season Index and asset-specific funding rates.
3. **Spot ETF flows** (BTC/ETH only) — Farside Investors, SoSoValue, CoinGlass. Daily, weekly, monthly, YTD.
4. **Oil / macro** (when geopolitical or macro stress is active) — Brent, WTI; CNBC, Reuters, Investing.com.
5. **Equities** — S&P 500, Nasdaq, Dow levels/futures; Yahoo Finance, CNBC.
6. **Macro** — Gold, VIX, 2y/10y Treasury yields, Fed policy rate, latest CPI/PCE.
7. **On-chain** — Funding rates, 24h liquidations, long/short ratio, MVRV-Z (BTC/ETH), LTH supply trend, exchange reserves, Coinbase Premium. Sources: CoinGlass, CryptoQuant, Glassnode, Checkonchain.
8. **Correlation regime** — 30-day rolling correlation of the asset vs SPX. CoinMetrics, TradingView, or compute from price series.
9. **Breaking news** — Geopolitics, regulation (SEC/CFTC, EU MiCA, etc.), OPEC, asset-specific events (forks, unlocks, hacks, founder issues).

Tag every figure with **source + timestamp**.

## Report Structure

Produce the report in this exact order. Asset name appears in every section header.

### 1. Header

```
# 🔪 FALLEN KNIVES ANALYTICS — [ASSET] — [DATE]
## [CONTEXT LINE — e.g., "WEDNESDAY OPEN — ALL DATA LIVE INTERNET-VERIFIED"]
### Report Generated: [Day], [Date], [Time] EST
### Asset: [ASSET] | Prior Score: [X/20 or "cold start"] | Current Score: [X/20]
```

### 2. Verified Live Data Points

Present all fetched data in tables with **source + timestamp** for every cell:

- **Price**: Source | Price | Timestamp | 24h Δ
- **Sentiment**: Source | Reading | Status (extreme fear / fear / neutral / greed / extreme greed)
- **Spot ETF Flows** (BTC/ETH only): Window | Net Flow | % of AUM | Source
- **Oil & Macro** (when active): Asset | Level | Δ | Source
- **Equities**: Index | Level | Δ | Source
- **On-Chain**: Metric | Value | Source
- **Correlation Regime**: 30d corr vs SPX | regime label (decoupled / mild / risk-on / inverse)

### 3. Critical Developments

Bulleted summary of the highest-impact news/events with cited sources. Cover geopolitics, regulation, market structure, key analyst calls, asset-specific catalysts.

### 4. Fallen Knives Composite Score (X / 20)

| Category | Max | Scoring Rubric |
|---|---|---|
| **Sentiment Extreme** | 5 | F&G or asset-equivalent sentiment. ≤10 → 5 · ≤15 → 4 · ≤25 → 3 · ≤35 → 2 · ≤50 → 1 · >50 → 0 |
| **Momentum Exhaustion** | 4 | Weekly RSI. <30 → 4 · 30–35 → 3 · 35–40 → 2 · 40–45 → 1 · >45 → 0. *(Removed the unreachable <25 → 5 tier — it only fires at generational bottoms and biased the legacy framework toward underdeployment.)* |
| **Valuation** | 5 | **Primary (BTC/ETH):** MVRV-Z. <0 → 5 · 0–1 → 4 · 1–2 → 3 · 2–3 → 2 · 3–5 → 0 · >5 → −2 (trim signal). **Fallback (alts without reliable MVRV):** drawdown from ATH. ≥70% → 5 · 60–70 → 4 · 50–60 → 3 · 40–50 → 2 · 30–40 → 1 · <30 → 0. State which metric was used. |
| **Capitulation Evidence** | 3 | Count of: (a) 24h liquidations >0.5% of asset mcap, (b) perp funding negative for ≥3 consecutive funding intervals, (c) ETF net outflows ≥2% of AUM over trailing month (BTC/ETH only — for alts, substitute exchange-inflow spike). 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **Holder Behavior** | 3 | (a) LTH supply rising 30d (BTC/ETH) or top-100 holder concentration stable/rising (alts), (b) exchange reserves declining 30d. Both → 3 · One → 1.5 · Neither → 0 |
| **TOTAL** | **20** | |

**Correlation Regime Modifier** (applied after summing):
- Asset–SPX 30d corr >0.7 (risk-on regime): multiply score by **0.85** (crypto trading as beta proxy, signal is noisier)
- Corr 0.2–0.7: **1.00** (baseline)
- Corr <0.2: **1.05** (crypto-native dynamics dominate)
- Corr <0 (decoupling): **1.10** (regime shift = opportunity)

Round to nearest integer. State raw score, regime modifier, and adjusted score.

#### Confirmation Gates (X / 9) — drives phase unlocks, not scoring

Mark each ✅ / ⚠️ / ❌:

1. Sentiment ≤15 (or asset-equivalent extreme) for ≥7 consecutive days
2. Weekly RSI <30
3. Valuation in cheap zone (MVRV-Z <1 for BTC/ETH; ≥50% drawdown from ATH for alts)
4. ETF outflows ≥2% of AUM trailing month *(BTC/ETH; for alts, sustained exchange inflows >30-day avg)*
5. Hash Ribbon buy signal *(PoW assets only — skip with N/A for PoS)*
6. 200-week MA (or equivalent long-horizon MA) holding as support
7. Capitulation volume spike (24h liquidations >0.5% of mcap)
8. LTH accumulation / holder concentration stabilizing
9. Macro catalyst neutral-to-positive (Fed pivot priced, geopolitical de-escalation, regulatory clarity, etc.)

Count ✅ only. ⚠️ does not count.

### 5. Probability Matrix — Derived From Score

Use this baseline grid, then adjust each cell ±10% based on idiosyncratic catalysts (and state your adjustments):

| Adj. Score | Rally | Range | Retest | Bear |
|---|---|---|---|---|
| 0–5 | 10% | 30% | 35% | 25% |
| 6–10 | 20% | 35% | 30% | 15% |
| 11–14 | 35% | 35% | 20% | 10% |
| 15–17 | 50% | 30% | 15% | 5% |
| 18–20 | 65% | 25% | 8% | 2% |

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value** = Σ (probability × midpoint of target range). State EV explicitly, and EV-vs-spot %.

### 6. Deployment Strategy

Splits: **10 / 15 / 30 / 45** (front-loaded pyramid — bigger tranches at deeper drawdowns, where reward-to-risk is highest). State **total dry powder %** prominently.

For each phase show: capital share, trigger zone, gates required, current status.

**Cold start:** if no prior phases are deployed, every phase below begins as `DRY POWDER` with its gate conditions. Do not assume continuity.

#### Phase 1A — Initial Entry (10%)
- **Unlock gates:** adjusted score ≥10 AND ≥3 of 9 confirmation gates ✅
- **Entry zone:** ASSET-SPECIFIC. State price range.
- **Stop loss:** daily close below [level]
- **Status:** DRY POWDER / DEPLOYED ([entry avg]) / STOPPED OUT

#### Phase 1B — Building (15%)
- **Unlock gates:** adjusted score ≥13 AND ≥5 of 9 gates ✅ AND Phase 1A entered
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 2 — Conviction (30%)
- **Unlock gates:** adjusted score ≥15 AND ≥6 of 9 gates ✅ AND macro catalyst neutral-to-positive AND correlation regime not "risk-on extreme" (corr <0.8)
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 3 — Generational (45%)
- **Unlock gates:** adjusted score ≥17 AND ≥7 of 9 gates ✅ AND LTH selling has collapsed (or alt equivalent) AND sustained ETF inflows for ≥5 sessions (BTC/ETH)
- **Entry zone:** [range — typically requires capitulation candle on weekly]
- **Status:** DRY POWDER / LIVE / FROZEN

**Dry powder yield benchmark:** state assumed opportunity cost (current T-bill yield or USDC/sDAI yield). Cash is a position; idle cash has a measurable cost.

### 7. Exit / Trim Framework (Symmetric)

The framework de-risks as conviction signals invert. Track cost basis per phase. Trims execute against most-recently-deployed tranches first (LIFO).

| Trigger | Action | Rationale |
|---|---|---|
| Adjusted score drops ≥6 points from local peak | Trim 25% | Signal exhaustion in progress |
| F&G ≥75 sustained 7d **AND** weekly RSI >70 | Trim 25% | Sentiment + momentum euphoria |
| MVRV-Z >3 (BTC/ETH) or drawdown from ATH <10% with vertical 30d return | Trim 50% | Valuation extreme |
| Adjusted score ≤3 **AND** price ≥40% above blended cost | Trim 25% | Score cycle complete |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime | Trim 25% | Institutional conviction breaking |
| Narrative break (regulatory ban in major jurisdiction, founder fraud, critical security breach, irreparable tokenomics change) | **Exit 100%** | Thesis voided — price is downstream of narrative |

State current exit-trigger status (none / partial trim executed / full exit) and remaining position size.

### 8. Critical Watchlist

| Time (EST) | Event | Asset Impact |
|---|---|---|

Include macro releases, ETF deadlines, asset-specific events (unlocks, forks, governance votes), geopolitical milestones.

### 9. Bull vs Bear Scorecard

Numbered bull signals (✅) and bear signals (❌) with one-line rationale each. Count both. State net direction and magnitude.

### 10. Change Log

If a prior report exists for this asset, show what changed:

| Factor | Previous | Current | Direction |
|---|---|---|---|

If cold start, state "first report — no prior comparison."

### 11. Strategic Verdict

- Restate: adjusted score, weighted EV, EV-vs-spot %, sentiment reading, current stance
- 2–3 paragraph synthesis in the voice of a **seasoned macro allocator with deep crypto fluency** (multiple equity cycles + cycles since 2013–2017 crypto)
- Numbered action items — specific, executable
- Closing "The Pattern" block quote with 2–3 conditional scenarios (`IF X → THEN Y`)

## Score Interpretation

| Adjusted Score | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — insufficient fear, or active distribution regime |
| 6–10 | Early Warning | PREPARE — build watchlist, refresh thesis |
| 11–14 | Accumulation Zone | CAUTIOUS ENTRY — Phase 1A eligible |
| 15–17 | Strong Signal | SYSTEMATIC DEPLOY — Phases 1A–2 eligible |
| 18–19 | Historic Opportunity | AGGRESSIVE DEPLOY — Phases 1A–3 eligible |
| 20 | Maximum Signal | FULL DEPLOY — all phases |

## Asset Generalization

Adapt metrics per asset:

| Metric | BTC | ETH | Major Alts (SOL, etc.) | Smaller Alts |
|---|---|---|---|---|
| Sentiment | F&G (primary) | F&G + ETH funding | Altcoin Season Index + funding | Funding + social heatmap |
| Valuation | MVRV-Z (Glassnode) | MVRV-Z (Glassnode) | Drawdown from ATH (MVRV unreliable) | Drawdown from ATH |
| ETF Flows | ✅ Farside, SoSoValue | ✅ Farside, SoSoValue | ❌ N/A — use spot exchange flows | ❌ N/A — use spot exchange flows |
| LTH / Holder | LTH supply (Glassnode) | LTH supply + staked share | Top-100 concentration | Top-100 concentration |
| Hash Ribbon | ✅ PoW | ❌ Skip (PoS) | ❌ Mostly PoS — skip | Asset-dependent |
| Validator/Staking | ❌ | Validator queue, staked % | Staking ratio where applicable | If applicable |
| Long-horizon MA | 200-week | 200-week | 200-day (insufficient history for many) | 200-day or asset's full-history mean |

If user does not specify an asset, default to BTC and prompt for confirmation if context is ambiguous.

## Analytical Principles

1. **Real-time data is non-negotiable** — every claim backed by a fresh search with source + timestamp
2. **Narrative integrity > price** — exit on broken theses, not short-term weakness
3. **Extreme fear = signal, not deterrent** — framework is designed to exploit fear; symmetric framework also exploits euphoria
4. **Dry powder discipline** — front-loaded pyramid (10/15/30/45); never all at once; idle cash earns benchmark yield
5. **Surprise vs. expectation** — reactions are about beats/misses vs consensus, not absolute values
6. **Short squeeze mechanics** — negative funding for multiple intervals sets up sharp upside
7. **Energy = #1 macro variable in geopolitical crises** — oil prices flow into risk-on appetite
8. **ETF flows = institutional conviction barometer** (BTC/ETH) — the most important daily signal in current regime
9. **LTH/long-holder selling collapse = structural floor** — when long-term holders stop distributing, bottoms form
10. **Correlation regime matters** — high BTC–SPX correlation muddies asset-specific signals; decoupling is itself information

## Data Source Priority

| Category | Primary | Secondary | Tertiary |
|---|---|---|---|
| Price | CoinDesk, CoinGecko | Yahoo Finance, Investing.com | CoinCodex, asset's primary exchange |
| Sentiment | Alternative.me | CoinStats, CoinMarketCap | BitDegree, FearGreedMeter |
| ETF Flows | Farside Investors, SoSoValue | CoinDesk, CoinGlass | CNBC, Ainvest |
| On-Chain | Glassnode, CryptoQuant | CoinGlass | Checkonchain, CoinMetrics |
| Oil/Macro | CNBC, Reuters | Bloomberg, Investing.com | Yahoo Finance, Fortune |
| Geopolitical | Reuters, AP, Al Jazeera | CNBC, BBC, FT | Wikipedia (live-updated conflict pages) |
| Correlation | CoinMetrics, TradingView | Computed from price series | — |

## Output

Save the report as a markdown file to:

```
/Users/eternal/Desktop/Trading Claude Analytics/reports/[ASSET]_fallen_knives_[YYYYMMDD]_[HHMM].md
```

Filename uses lowercase asset symbol. Example: `btc_fallen_knives_20260514_0930.md`.

After saving, post a brief conversational summary (≤6 lines) highlighting:
- Adjusted score and stance
- Top 1–2 changes vs prior report (or "first report" if cold start)
- The single most actionable item

## Voice & Tone

- Write as a **seasoned macro allocator with deep crypto fluency** — calm, data-driven, unsentimental. Has navigated multiple equity cycles (1990s–) and multiple crypto cycles (2013–). Not a crypto-native maximalist; not a permabear.
- Never use hype language ("moon," "lambo," "WAGMI") or doomerism ("zero," "rug")
- Acknowledge uncertainty explicitly; use probability ranges, not certainties
- "Cash is a position. Patience is alpha — but idle cash has a measurable yield cost; benchmark it."
- During active geopolitical/macro stress: "Geopolitical shocks create the best entries AFTER the fog clears, not during it"
- Every report ends with numbered, executable action items — never just commentary

## Language

Reports can be delivered in English or Russian per user preference. Default: English. Ask only if ambiguous.
