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
- **Sentiment**: Source | Reading (spot **and** 3-day avg) | Status (extreme fear / fear / neutral / greed / extreme greed)
- **Spot ETF Flows** (BTC/ETH only): Window | Net Flow | % of AUM | Source
- **Oil & Macro** (when active): Asset | Level | Δ | Source
- **Equities**: Index | Level | Δ | Source
- **On-Chain**: Metric | Value | Source
- **Correlation Regime**: 30d corr vs SPX (**sourced + timestamped, or "not computed"**) | regime label (decoupled / mild / risk-on / inverse)

**Canonical-spot reconciliation rule (Jun 2026).** Do not pick an informal round number for spot. **Canonical spot = median of the primary source + ≥2 others**, timestamped. If the inter-source spread is **>0.5%**, report it and compute EV at both extremes. If the EV *sign flips* across that spread **AND** |median EV-vs-spot| < the spread, mark the read **low-confidence / corroborative-only** and require a *second independent* unlock condition before acting — do **not** mark it INDETERMINATE (that would suppress legitimate near-zero-EV calls, which were the framework's best in-sample). *(Gold Jun 10 carried a ~0.43% inter-source spread while EV was quoted at 0.1%; the May-31 "don't add" call sat at −0.5%, inside the spread.)*

**Mandatory computed companion score (cross-validation — Hard Rule 5).** Every Fallen Knives report must state the **computed** Flying Rocket composite for the same asset/timestamp (number + gate count, from the same live data fetch) — **estimated/eyeballed companion numbers are prohibited.** *(In May–Jun 2026 exactly one FR report existed — May 14, F&G 50, the moment of least signal — and every later FK report asserted inverse "consistency" with a sourceless "~3–4," making the check unfalsifiable precisely as fear deepened.)* If the companion composite is ≥9, append a short informational watch block (it does **not** unlock any short phase — FR Phase 1A still needs ≥13, per Hard Rule 6). If the companion **cannot** be computed (data failure), pause **net-new long deployment only** — never force a trim, relax a stop, or block a hold. The cross-validation inconsistency condition remains **both frameworks ≥12 simultaneously** (never a sum).

### 3. Critical Developments

Bulleted summary of the highest-impact news/events with cited sources. Cover geopolitics, regulation, market structure, key analyst calls, asset-specific catalysts.

### 4. Fallen Knives Composite Score (X / 20)

| Category | Max | Scoring Rubric |
|---|---|---|
| **Sentiment Extreme** | 5 | **3-day average** F&G or asset-equivalent sentiment — use the smoothed average, NOT the single-day spot print, to avoid score whipsaw (Jun 2026 F&G swung 11→23→17 in three days). ≤10 → 5 · ≤15 → 4 · ≤25 → 3 · ≤35 → 2 · ≤50 → 1 · >50 → 0 |
| **Momentum Exhaustion** | 4 | Weekly RSI. <30 → 4 · 30–35 → 3 · 35–40 → 2 · 40–45 → 1 · >45 → 0. *(Removed the unreachable <25 → 5 tier — it only fires at generational bottoms and biased the legacy framework toward underdeployment.)* |
| **Valuation** | 5 | **Primary (BTC/ETH):** MVRV-Z. **<0.1 → 5** (generational green-zone; any negative MVRV-Z also lands here) · **0.1–0.5 → 4** · **0.5–1.0 → 3** · 1–2 → 3 · 2–3 → 2 · 3–5 → 0 · >5 → −2 (trim signal). *(Re-banded Jun 2026: the legacy `0–1 → 4` band spanned the entire $79.5K→$66K decline and sat saturated at 4/5 from a local top — MVRV 0.64–0.87 in May scored 4 when it should read 3. The 0.5 breakpoint is where the realized bottom actually sat.)* **Fallback (alts without reliable MVRV):** drawdown from ATH. ≥70% → 5 · 60–70 → 4 · 50–60 → 3 · 40–50 → 2 · 30–40 → 1 · <30 → 0. State which metric was used. |
| **Capitulation Evidence** | 3 | Count of: (a) **24h liquidations in the top decile of the trailing-90-day distribution OR >3σ above the trailing-30-day mean** — state explicitly whether the figure is asset-specific or market-wide and be consistent across reports. *(Regime-relative as of Jun 2026: the legacy fixed `>0.5% of mcap` bar NEVER fired for any asset in the May–Jun 2026 sample — BTC peak ~0.13%, ETH ~0.20%, SOL ~0.014% — an unreachable tier, the same defect removed from the momentum leg. A real flush must register.)* (b) perp funding negative for ≥3 consecutive funding intervals, (c) ETF net outflows ≥2% of AUM over trailing month (BTC/ETH only — for alts, substitute exchange-inflow spike). 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **Holder Behavior** | 3 | (a) LTH supply rising 30d (BTC/ETH) or top-100 holder concentration stable/rising (alts), (b) exchange reserves declining 30d. Both → 3 · One → 1.5 · Neither → 0 |
| **TOTAL** | **20** | |

**Correlation Regime Treatment** (revised Jun 2026 — demoted from a score multiplier to a sourced gate surcharge + context label):

The legacy multiplier (×0.85 / ×1.00 / ×1.05 / ×1.10) rode on *eyeballed* correlations ("~0.3–0.5") and could flip the gate-driving integer off an unsourced guess — a "modifier technicality." It never changed an unlock in the May–Jun 2026 sample, so removing the bonus side is non-destructive. The only legitimate function — distrusting a "fear" score in a high-beta risk-on regime — is preserved as a **breadth surcharge**, not an arithmetic haircut:

- **Risk-on suppressor (kept, hardened):** if a **sourced** 30-day Pearson correlation (CoinMetrics / TradingView / computed from price series, with timestamp) is **>0.7**, require **one additional [V] confirmation gate** for ANY phase unlock. A fear signal that is really equity beta deserves more confirmation, not a fractional score cut — and this now protects Phases 1A/1B, which the old ×0.85 covered but the Phase-2 `corr <0.8` gate did not.
- **Decoupled / inverse (corr <0.2 or <0):** **context label only** — no score change. The §5 trend modifier and the Phase-2 `corr <0.8` gate already carry the regime information.
- **Sourcing rule (Hard Rule 1):** state the sourced correlation + timestamp, or write "not computed this cycle." If not computed, the risk-on surcharge defaults **OFF** (never penalize on a guess either).

Round to nearest integer. State raw score, any [V]-gate surcharge applied, and adjusted score. *(The Flying Rocket ×1.05 decoupled modifier is demoted symmetrically — see that skill's revision log.)*

#### Confirmation Gates (X / 9) — drives phase unlocks, not scoring

Mark each ✅ / ⚠️ / ❌. Each gate is tagged **[V] fear/value** (the conditions you WANT lit to accumulate) or **[T] floor/trend** (conditions that mechanically turn OFF as price falls):

1. **[V]** Sentiment ≤15 (or asset-equivalent extreme) for ≥7 consecutive days
2. **[V]** Weekly RSI <30
3. **[V]** Valuation in cheap zone (MVRV-Z <1 for BTC/ETH; ≥50% drawdown from ATH for alts)
4. **[V]** ETF outflows ≥2% of AUM trailing month *(BTC/ETH; for alts, sustained exchange inflows >30-day avg)*
5. **[T]** Hash Ribbon buy signal *(PoW assets only — skip with N/A for PoS)*
6. **[T]** Price within **±8% of the 200-week MA** (or equivalent long-horizon MA) — proximity to the long-run mean, **above OR below**. *(Reframed Jun 2026: the old "holding as support" wording penalized the exact 200-week break a knife-catcher wants to buy — a clean break that stays within 8% still counts.)*
7. **[V]** Capitulation volume spike — 24h liquidations in the **top decile of the trailing-90-day distribution OR >3σ above the trailing-30-day mean** (regime-relative; the fixed >0.5%-of-mcap bar never fired in-sample and is retired)
8. **[V]** LTH accumulation / holder concentration stabilizing
9. **[T]** Macro catalyst neutral-to-positive (Fed pivot priced, geopolitical de-escalation, regulatory clarity, etc.)

Count ✅ only. ⚠️ does not count.

**Gate buckets:** six **[V] fear/value** gates (1, 2, 3, 4, 7, 8) and three **[T] floor/trend** gates (5, 6, 9). [T] gates structurally turn off in a fear cascade — in May–Jun 2026 the 200-week break (gate 6) and a hawkish macro inversion (gate 9) stripped two gates exactly as BTC got cheapest, freezing deployment at its smallest tranche / highest price.

**Counting rule (revised Jun 2026):**
1. **N/A denominator reduction (ported from Flying Rocket).** If a gate is *structurally inapplicable* to the asset/cycle — Hash Ribbon (gate 5) on a PoS asset is the canonical case — mark it **N/A and reduce the denominator by 1**. Never silently mark an inapplicable gate ❌ (false-negative bias), and **never convert an ambiguous ⚠️ to N/A** (that is a measurable-but-missing signal, not an inapplicable one). For ETH/SOL, gate 5 is N/A → denominator becomes 8, leaving only two [T] gates (6, 9). Without this reduction, Phase 3's "≥7 of 9" was nearly unreachable for PoS assets.
2. **Thresholds are proportions, not fixed counts.** Each phase's gate requirement is `ceil(fraction × active_denominator)`, where the fractions are 1A ≈ ⅓, 1B ≈ 5/9, 2 ≈ ⅔, 3 ≈ 7/9. On the full /9 board this reproduces the familiar ≥3 / ≥5 / ≥6 / ≥7.
3. **[V] floor (anti-[T]-veto).** At least the stated [V] minimum must come from the [V] bucket (1A ≥2 [V], 1B ≥3 [V], 2 ≥3 [V], 3 ≥4 [V]). Six [V] gates exist, so every floor is satisfiable without any [T] gate — trend gates can no longer veto an unlock the fear/value evidence already earns.
4. **Override path.** A tranche may also unlock via the **Deep-Value Override** (§6) regardless of [T] gates, subject to the throttle defined there. Treat [T] gates as sizing/timing inputs, not hard vetoes.

> **Cross-reference (gate 6 ⇄ §5 trend modifier):** the same 200-week break that keeps gate 6 lit (within ±8%, pro-*eligibility*) also shifts the §5 matrix bearish (pro-*caution on EV/sizing*). This is **not** a contradiction — gate 6 governs whether a phase *can* unlock; the matrix governs *how big / what edge*. They are not meant to cancel; report both.

### 5. Probability Matrix — Derived From Score

Use this baseline grid (**re-flattened Jun 2026** — see below), then adjust each cell ±10% based on idiosyncratic catalysts (and state your adjustments):

| Adj. Score | Rally | Range | Retest | Bear |
|---|---|---|---|---|
| 0–5 | 10% | 30% | 35% | 25% |
| 6–10 | 20% | 35% | 30% | 15% |
| 11–14 | 30% | 35% | 22% | 13% |
| 15–17 | **38%** | **33%** | **19%** | **10%** |
| 18–20 | **50%** | **28%** | **14%** | **8%** |

> **Why flattened:** the legacy grid put Rally at **50% (scores 15–17) / 65% (18–20)** — so deepening fear *mechanically manufactured* a majority rally weight, and because **EV = Σ(probability × midpoint)** with targets anchored above a falling spot, EV came out **positive by construction at maximum fear**. In May–Jun 2026 this produced ~5 of 7 BTC EV reads pointing up while price fell every time, Rally staying *modal* on Jun 6 and Jun 10 at the cycle's deepest fear. The grid is a **fear map, not a direction forecast** — Rally is now capped *below modal* at every tier.

**Trend/regime modifier (residual, Jun 2026).** With the grid flattened, the trend term is now a small residual to avoid double-counting: when price is **below a major MA AND making lower lows** (active downtrend), shift a further **≤5–7% of mass from Rally → Retest + Bear** and widen downside target ranges; apply the mirror toward Rally when the trend repairs (major-MA reclaim / confirmed higher-low). State the shift. (See the gate-6 ⇄ §5 cross-reference in §4.)

**EV-floor consistency check (Jun 2026).** After computing EV, if **EV-vs-spot is negative while adjusted score ≥15 AND 3-day F&G ≤15**, flag it as an internal inconsistency and re-examine inputs — a genuine deep-value, extreme-fear zone should not simultaneously show a *negative* accumulation edge (that pattern usually means the targets or the trend shift were set too pessimistically, and it would perversely fight the Deep-Value Override). Do not deploy *or* refuse to deploy on a flagged-inconsistent EV; resolve it first.

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value** = Σ (probability × midpoint of target range). State EV explicitly, and EV-vs-spot %. **Disclose the realized trailing-2-week price change next to the EV claim** — a positive EV printed during a −X% two-week move must say so, so the reader sees the EV contradicts realized momentum.

### 6. Deployment Strategy

Splits: **10 / 15 / 30 / 45** (front-loaded pyramid — bigger tranches at deeper drawdowns, where reward-to-risk is highest). State **total dry powder %** prominently.

**⚑ Deep-Value Override (knife-deepening rule — added Jun 2026, recalibrated Jun 2026).** The pyramid's whole purpose is *bigger tranches at deeper drawdowns*. In May–Jun 2026 the gate system did the opposite: BTC deployed only its smallest 10% tranche at ~$65K, then was locked out of the 15/30/45 tranches as price fell to $59K and score *rose* to 16 at F&G 9 — maximum signal, zero incremental deployment. The override defeats that inversion. The **next** tranche unlocks **regardless of [T] gate count** when ALL of:

- Adjusted score ≥15, **AND**
- **Price condition (reachability-fixed):** the trailing-period **low or a daily close** is **≥8% below the blended cost of the most-recently-deployed tranche** AND a **fresh lower-low** has printed since that tranche. *(The original "spot ≥10% below basis" was evaluated only at report-time spot — in Jun 2026 the deepest print was −9.1% off the $65K basis, so the 10% trigger NEVER fired despite score 16 / F&G 9; it shipped decorative. Trailing-low + 8% makes it actually reachable without firing on a shallow dip.)* **AND**
- 3-day avg sentiment in the extreme band (F&G ≤15 or asset-equivalent), **AND**
- **Worsening-flows veto is OFF:** do NOT fire if ETF outflows are re-accelerating AND the major MA broke within the last 5 sessions (don't add into an actively worsening institutional exodus), **AND**
- No §7 narrative-break trigger active.

**Sizing + throttle (prevents chain-runaway):**
- Default fire size = **half** the tranche's nominal size (remaining half waits for [T] confirmation or a still-deeper *pre-named* zone).
- **While the §5 active-downtrend shift is live, fire at quarter-size, not half** — the override (deploy more) and the trend modifier (expect lower) must be wired together, not pull against each other unthrottled.
- **At most one override unlock per report / per 5 calendar days**, and **override-deployed capital may not exceed 25% of the book** until at least one [T] gate relights OR a confirmed higher-low prints. *(Unthrottled, repeated 8–10% steps could otherwise walk the book to ~45% deployed through a single uninterrupted −27% cascade with zero trend confirmation — a softer version of the knife-catching the framework exists to avoid.)*
- The override governs **deployment only** and is **independent of the stop** (§ Stop Philosophy) — the two must never be evaluated off the same trigger.

Log every override firing (and every blocked-by-veto/throttle near-fire) in the report.

For each phase show: capital share, trigger zone, gates required, current status.

**Cold start:** if no prior phases are deployed, every phase below begins as `DRY POWDER` with its gate conditions. Do not assume continuity.

#### Phase 1A — Initial Entry (10%)
- **Unlock gates:** adjusted score ≥10 AND ≥3 of 9 confirmation gates ✅ (with ≥2 from the **[V]** bucket)
- **Entry zone:** ASSET-SPECIFIC. State price range. **Ladder across the FULL zone — never deploy at the top of it** (May 31 2026 instructed a top-of-zone entry at $70–73K; the better fill came from laddering into $64–67K days later).
- **Stop:** governed by **Stop Philosophy** below (compound thesis stop placed *below the deepest planned buy zone*; run the mandatory stop-vs-buy-zone coherence check).
- **Status:** DRY POWDER / DEPLOYED ([entry avg]) / STOPPED OUT

#### Phase 1B — Building (15%)
- **Unlock gates:** adjusted score ≥13 AND (≥5 of 9 gates ✅ with ≥3 from the **[V]** bucket **OR** Deep-Value Override fires) AND Phase 1A entered
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 2 — Conviction (30%)
- **Unlock gates:** adjusted score ≥15 AND (≥6 of 9 gates ✅ with ≥3 from the **[V]** bucket **OR** Deep-Value Override fires) AND correlation regime not "risk-on extreme" (corr <0.8). *(The "macro neutral-to-positive" condition is now a **[T]** sizing/timing input, not a hard veto — it structurally turns off in every fear spike, which is when this tranche is supposed to fire.)*
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 3 — Generational (45%)
- **Unlock gates:** adjusted score ≥17 AND **[ (≥7 of 9 gates ✅ — or `ceil(7/9 × active denominator)` for PoS assets where gate 5 is N/A — with ≥4 from the [V] bucket) OR (Deep-Value Override fires AND a weekly capitulation candle has printed) ]** AND LTH selling has collapsed (or alt equivalent). *(Parentheses are load-bearing: the capitulation-candle requirement binds ONLY to the override branch, never to the normal gate path — this is the generational 45% tranche, the most expensive place for an operator-precedence ambiguity. Sustained ETF inflows ≥5 sessions are a **[T]** confirmation — strong when present, but this tranche may fire on a capitulation candle into max fear rather than waiting for inflows that only appear after the recovery begins.)*
- **Entry zone:** [range — typically requires capitulation candle on weekly]
- **Status:** DRY POWDER / LIVE / FROZEN

**Stop Philosophy (revised Jun 2026).** A fear-accumulation strategy adds *into* lower prices; a hard per-tranche price stop that sits **above** a deeper planned buy zone is self-contradictory — it ejects you before your own Phase 1B/2/3 entries. In Jun 2026 the BTC Phase 1A stop ($58K) sat *inside* the Phase 1B zone ($53–58K), and the Jun 6 low of $59,110 came within ~1.9% of stopping the position out **at maximum fear** (score 16, F&G 9) — selling the exact bottom the framework exists to buy. Gold already shipped the coherent design; crypto now inherits it:

- **Placement rule (mandatory):** the catastrophic stop sits **strictly below the deepest defined buy zone**. If the deepest zone is open-ended, anchor the stop a defined margin below the deepest *named* price reference. There is **no "no-stop" option** — every deployed position carries a stop placed below the ladder (an un-stopped position alongside an override that adds into weakness is how books blow up).
- **Compound thesis stop (price AND score):** invalidation fires only on a **sustained weekly close below the structural floor (≥2 consecutive weekly closes) AND the composite score back below 12**. A pure-price line that would have fired at score 16 / F&G 9 is self-contradictory; requiring the score to *also* have rolled over means the stop fires only when fear AND value have genuinely deteriorated — it can never fire *more* often than a price-only stop, only less. (Would NOT have fired Jun 6, correctly keeping the holder in the position that recovered.)
- **Mandatory time stop:** every tranche also carries a max-hold/decay limit; an accumulation thesis that hasn't worked after a stated horizon is reassessed, not held indefinitely.
- **§7 narrative-break exit is independent** — the 100%-exit on a broken thesis stands on its own, separate from the price/score stop.
- **Stop-vs-buy-zone coherence check (mandatory, every report — long side only):** print the two numbers and the boolean — *"Stop [X] strictly below deepest active buy-zone floor [Y]? PASS/FAIL."* A FAIL means the stop is inside a zone you intend to buy; fix it before publishing. Widening or removing a stop while the Deep-Value Override is armed requires stating the explicit max-drawdown-to-thesis-stop figure. *(This check is FK-only; Flying Rocket's stop-above-entry is correct by design — do not port it there, per Hard Rule 6.)*

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

> **Verdict-Confidence Collar (Jun 2026 — anti-overstatement guardrail).** The prose must not be more confident than the quant layer supports. When **|EV-vs-spot| < 2% OR the bull/bear scorecard is within 1 of balanced OR adjusted score is 6–10**, you are **prohibited from declaring a directional regime resolved** ("the fear window has closed," "the bottom is in," "the top is set"). The discipline is structural, not lexical: separate **realized-data statements** (which may use strong language — "flows turned net-negative this week" is a fact) from **forward / regime-resolution claims** (which must carry a probability **or** an `IF→THEN` **and** a named falsifier). Negations and conditionals are permitted. *(This collar blocks the May-14 "fear window already closed for this leg" sentence — written at EV +0.6%, a 7/7 scorecard, score 8, immediately before a −23% drop to F&G 9 — while leaving that report's correct 100%-dry stance and well-hedged May-28 verdict untouched. The strong-claim unlock is "score ≥15 OR a realized trend-structure event," not a feeling.)*
- **Single-observation durability:** do not promote one data point to structure. A claim that a **fear regime has ENDED** (e.g., "$80K converted to support," "flows flipped supportive") requires the framework's existing confirmation bars — ≥5-session flow trend for flows, trend-structure repair for levels. Claims that *reinforce* the prevailing fear/down regime may use fewer observations (a correct fast bearish read is not penalized).

## Score Interpretation

| Adjusted Score | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — insufficient fear, or active distribution regime |
| 6–10 | Early Warning | PREPARE — build watchlist, refresh thesis |
| 11–14 | Accumulation Zone | CAUTIOUS ENTRY — Phase 1A eligible |
| 15–17 | Strong Signal | SYSTEMATIC DEPLOY — Phases 1A–2 eligible |
| 18–19 | Historic Opportunity | AGGRESSIVE DEPLOY — Phases 1A–3 eligible |
| 20 | Maximum Signal | FULL DEPLOY — all phases |

> **Reconciliation note (Jun 2026):** these stances describe **score eligibility only** — actual deployment is gate-gated (§6). A high score is *necessary but not sufficient*. In Jun 2026 BTC sat at score 15–16 ("SYSTEMATIC DEPLOY — Phases 1A–2 eligible") for four straight reports while only Phase 1A (10%) was ever deployed, because the gates blocked the rest. Do not let this table imply more deployment than the gates + Deep-Value Override (§6) actually authorize; state the gate-limited reality explicitly in every verdict.

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
3. **Bounces within a downtrend are suspect** — never declare a fear cycle "closed" on a rally. Require **trend-structure repair** (reclaim of a major MA or a confirmed higher-high) before downgrading the accumulation thesis. *(May 14 2026: the framework called the fear window "already closed for this leg" at $79.5K — right before a −23% drop to F&G 9. The bounce was a bull trap; the deeper leg was the real signal.)*
4. **Two-tier certainty** — realized-data statements may use strong language; **forward / regime-resolution claims must carry a probability OR an `IF→THEN` plus a named falsifier.** Enforced on structure, not vocabulary. (The score is a *coincident fear gauge, not a forecast* — it rose 8→16 as price fell −23%, peaking at the lowest-confidence-in-a-bottom moment; never let a high score license a confident *price* prediction.)
5. **Extreme fear = signal, not deterrent** — framework is designed to exploit fear; symmetric framework also exploits euphoria
6. **Dry powder discipline** — front-loaded pyramid (10/15/30/45); never all at once; idle cash earns benchmark yield
7. **Surprise vs. expectation** — reactions are about beats/misses vs consensus, not absolute values
8. **Short squeeze mechanics** — negative funding for multiple intervals sets up sharp upside
9. **Energy = #1 macro variable in geopolitical crises** — oil prices flow into risk-on appetite
10. **ETF flows = institutional conviction barometer** (BTC/ETH) — the most important daily signal in current regime
11. **LTH/long-holder selling collapse = structural floor** — when long-term holders stop distributing, bottoms form
12. **Correlation regime matters** — high BTC–SPX correlation muddies asset-specific signals; decoupling is itself information

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

## Framework Revision Log

### 2026-06-10 — Backtest-driven tuning (BTC/ETH/Gold, May 14 → Jun 10 2026)

Source: retrospective on 7 BTC + 5 ETH + 4 Gold reports. Realized path: BTC $79.5K→$60K (F&G 50→9), every deployed tranche ended 5–10% underwater while 75–90% of capital never deployed. Seven tunes applied:

1. **Deep-Value Override (§6)** — the central fix. Gate system inverted the pyramid (smallest tranche at highest price; locked out of 15/30/45 as price fell and score *rose* to 16 at F&G 9). Override unlocks the next tranche at half-size on score ≥15 + ≥10% below prior basis + extreme fear + no narrative break, regardless of [T] gates.
2. **Gate buckets [V]/[T] + reframed gate 6 (§4)** — floor/trend gates turn off in fear cascades (200-week break, hawkish macro stripped 2 gates at the cheapest prices). ≥half of any required count must be [V]; gate 6 reframed to "within ±8% of 200-week, above OR below."
3. **Stop Philosophy (§6)** — old per-tranche price stops sat *above* lower buy zones (BTC $58K stop inside the $53–58K Phase 1B zone); Jun 6 low came ~1.9% from selling the bottom. Primary stop is now the thesis (narrative break); any price stop goes below the deepest tranche.
4. **Trend/regime modifier (§5)** — grid was monotonic fear→rally; produced 4 straight optimistic EVs while price fell. Now shifts 10–15% mass to downside in a confirmed downtrend.
5. **3-day-average sentiment (§4)** — spot F&G whipsawed 11→23→17, jittering the score; now smoothed.
6. **Interpretation-table reconciliation** — table oversold deployment vs what gates allowed; now footnoted.
7. **Anti-bull-trap principle (§Analytical Principles)** — don't declare a fear cycle "closed" on a bounce (May 14 did, pre-−23%).

What the framework got *right* and must keep: the May 14/28 "wait" calls (refused to deploy pre-drop), conservative sizing (10% tranches kept book ~flat through −23%), and cross-validation consistency (FK 8 / FR 0 on May 14).

### 2026-06-11 — Adversarial calibration pass (66-agent backtest workflow)

The Jun-10 tunes were directionally right but **mis-calibrated as shipped**. A multi-agent backtest (19 reports extracted, per-asset grading, 7-dimension diagnosis, adversarial refutation of 33 candidate tunes → 11 adopted-with-modification, 17 rejected) found and fixed:

- **Override was unreachable.** The "10% below basis" trigger ($58.5K off a $65K basis) never fired — deepest print was $59,110 (−9.1%) — so it shipped *decorative* despite score 16 / F&G 9. Re-anchored to trailing-low/daily-close at **8% + a fresh lower-low + a worsening-flows veto**.
- **Override chain-runaway.** No throttle could walk the book to ~45% in one cascade. Added: **quarter-size while the §5 downtrend shift is live**, ≤1 unlock per 5 days, ≤25% override-deployed until a [T] gate relights or a higher-low prints. Override decoupled from the stop.
- **Probability grid was optimistic *by construction*** (50/65% Rally at high scores manufactured positive EV at max fear). Grid **flattened** (15–17 Rally 50→38; 18–20 65→50, Rally capped below modal everywhere); §5 trend term reduced to a **≤5–7% residual** to avoid double-count; added an **EV-floor inconsistency check**.
- **Valuation leg re-banded** (`<0.1→5 · 0.1–0.5→4 · 0.5–1.0→3`) so it has resolution through a normal decline instead of saturating at 4/5 from a top.
- **Correlation modifier demoted** from an eyeballed multiplier to a **sourced [V]-gate surcharge** (corr >0.7 → +1 [V] gate); bonus branches are now context labels only.
- **Capitulation/gate-7 made regime-relative** (top-decile trailing-90d OR >3σ) — the fixed 0.5%-of-mcap bar never fired for any asset in-sample.
- **[V]/[T] counting hardened**: six [V] gates (not five); **N/A denominator reduction** ported from Flying Rocket for PoS Hash-Ribbon (Phase 3 ≥7/9 was nearly unreachable for ETH/SOL); thresholds as `ceil(fraction × active_denominator)`.
- **Stops made coherent**: compound thesis stop (sustained weekly close below floor **AND** score <12) + time stop, placed **strictly below the deepest buy zone**, with a mandatory per-report **coherence check**. The "no-stop" escape clause was removed.
- **Numbering bug fixed** (two "4." in Analytical Principles); **Phase-3 gate clause parenthesized**; **gate-6 ⇄ §5 cross-reference** added.
- **New guardrails**: Verdict-Confidence Collar + Two-Tier-Certainty principle (would have blocked the May-14 "fear window closed" assertion without touching the correct dry stance); **canonical-spot reconciliation**; **mandatory computed companion FR score** (kills the stale-cross-validation loophole).

**Rejected (and why it matters):** ~17 candidate tunes were dropped — nearly all either (i) knocked the score below 15 at the lows and thereby *broke* the Deep-Value Override (a composite downtrend penalty, a sentiment stabilization sub-leg), (ii) were overfit to one bounce/print, or (iii) loosened a guardrail into a falling knife (re-weighting phases to front-load 40% at $65K, lowering gate floors with half-weighted *failed* [T] gates, a 7%/5% override trigger fitted to the $61,531 endpoint). **The frameworks' restraint was the edge; most "improvements" that touched the score or loosened a gate would have made the realized outcome worse.** Calibrations are N=1 on a single four-week cascade — re-validate after the next full fear cycle.
