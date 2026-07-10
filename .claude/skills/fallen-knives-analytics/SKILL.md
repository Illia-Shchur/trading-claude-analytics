---
name: fallen-knives-analytics
description: "Proprietary crypto market analysis framework for identifying optimal accumulation points during periods of extreme fear — and trim/exit points during euphoria. Works for ANY crypto asset (BTC, ETH, SOL, major alts, smaller alts) with asset-appropriate metric substitution. Use whenever the user asks for a Fallen Knives update/score/analytics, a buy/sell/hold assessment on a crypto asset, a fear-or-euphoria readout, accumulation-zone analysis, deployment strategy, exit planning, or any variant of 'update fallen knives [asset]'. This skill MUST fetch live data from the internet before any analysis — never rely on stale or memorized data. Output is a structured multi-section report with composite scoring, derived probability matrix, phased deployment gates, and a symmetric exit framework."
---

# Fallen Knives Analytics — Crypto Accumulation & Exit Framework

## Overview

Fallen Knives is a proprietary framework for two symmetric tasks:

1. **Accumulating** crypto exposure during periods of extreme fear, in disciplined phases, with cold-start support
2. **Trimming/exiting** that exposure during euphoria or narrative breaks

It synthesizes five scored legs — sentiment, momentum, valuation, capitulation evidence, holder behavior — into a single composite score (0–20), alongside a nine-gate confirmation board (which carries macro/catalyst direction as gate 9 and the correlation regime as a sourced gate surcharge — neither feeds the 0–20 number). The score drives a probability matrix, deployment gates, and a symmetric exit framework.

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
10. **Mandatory forward calendar (tier-1 lock, Jul 2026)** — every report must fetch and enumerate, in the watchlist, all tier-1 US releases within the next 5 trading days — at minimum NFP/payrolls, CPI, PCE, FOMC decisions/minutes — with dates verified against the CURRENT release schedule including holiday shifts (releases move; verify the date against a named source, e.g. BLS/CME economic calendar — do not assume the usual weekday). Every dated trigger/checkpoint in the report must be validated as a real trading day. A report whose horizon contains an unenumerated tier-1 release is flagged as an incomplete-data report (disclosure only — creates no implicit pre-catalyst deployment pause and no rebalancing obligation).

Tag every figure with **source + timestamp**.

### Deterministic toolchain (mandatory, Jul 2026)

The repo ships `tools/` (see `tools/README.md`) so the numeric backbone is **computed, not narrated** — the failure modes it retires were all hand-done steps (the 4-report RSI NOT-FOUND debt, the ETH `ceil(7/9×8)=6` misprint, eyeballed EV sum-checks, ADR absorbing a half-session).

1. **Fetch:** start every report with `node tools/fetch.mjs <asset>` (+ `node tools/fetch.mjs macro`). It returns, with source + timestamp on every block: cross-checked spot (>1.5% divergence flagged), ATH/drawdown, weekly closes → **computed Wilder RSI-14** (satisfying the momentum input rule's auditability line: source, boundary, period, closes count), the **exact 200-week SMA** with the gate-6 ±8% boolean (retires the "estimated, flagged" 200-week), daily sessions + 5-day ADR (exclude abbreviated sessions via `tools/compute.mjs adr --exclude`), and F&G spot / 3-day average / gate-1 daily-print streaks from the pinned provider.
2. **Compute:** band assignments, `ceil` gate thresholds, per-asset .5 rounding, EV recomputation, and the stop-coherence boolean come from `tools/compute.mjs` (or the fetch output) — hand arithmetic is a cross-check, never the source of record. The band classifiers in `tools/lib.mjs` mirror §4 letter-for-letter; **any band/threshold change to this SKILL must change `tools/lib.mjs` + `tools/selftest.mjs` in the same commit.**
3. **Scope honesty:** the toolchain covers price/momentum/valuation-input/sentiment/macro series only. ETF flows (Farside is bot-blocked), on-chain (MVRV-Z, LTH, reserves, liquidations), COT, and news remain live web fetches under Hard Rule 1 — a tool-covered field never excuses a missing web-sourced one.
4. **Tool failure:** if a fetch source errors (the tool reports per-source errors instead of dying), fall back to the SKILL's documented NOT-FOUND/fallback rules and disclose the failure — never substitute memory.

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

**Canonical-spot reconciliation rule (Jun 2026, refined Jul 2026).** Do not pick an informal round number for spot. **Canonical spot = median of the primary source + ≥2 others**, timestamped. Label each source-panel row explicitly as "live" or "frozen/stale (age stated, anchored to report-publication time)." The spread computation must state whether dispersion is judged time-ordered/staleness-driven or genuine simultaneous disagreement, with low-confidence demotion applying only to genuine disagreement among synchronized (within 2hr of report-publication time) quotes; a stale quote within 0.5% of the live cluster need not be flagged as excluded, but a stale quote that diverges must be shown in the table with its timestamp/age and an explicit "EXCLUDED — outside 2hr window, divergent" tag rather than silently dropped. If fewer than 3 synchronized quotes are obtainable, say so and apply the low-confidence handling below. If the inter-source spread among synchronized quotes is **>0.5%**, report it and compute EV at both extremes. If the EV *sign flips* across that spread **AND** |median EV-vs-spot| < the spread, mark the read **low-confidence / corroborative-only** and require a *second independent* unlock condition before acting — do **not** mark it INDETERMINATE (that would suppress legitimate near-zero-EV calls, which were the framework's best in-sample). *(Gold Jun 10 carried a ~0.43% inter-source spread while EV was quoted at 0.1%; the May-31 "don't add" call sat at −0.5%, inside the spread. Jul-1 2026 BTC: a flagged 2.0% spread mixed Jul-1 live with Jun-30 prints — time-skew during a fast tape, not venue disagreement; the synchronized Jul-2 panel ran 0.22%.)*

**Metric-history continuity rule (Jul 2026).** Any claim about a metric's history extending beyond the current fetch window (regime start dates, streak lengths, sustained-since claims) must be reconciled against the same metric's print in this series' most recent prior reports. A regime may never be backdated past the last prior report that recorded the opposite state — the in-series provenance is authoritative (regime start = first prior report showing the new state). If a live source's claimed lineage contradicts the series, use the series' dating and disclose the discrepancy. *(ETH Jul-1 2026 backdated sustained-negative funding to ~Jun 3–4 while its own Jun-19/21 reports printed normalized, not sustained-negative — the true flip was ~Jun-25.)*

**Provenance citation for duration/"since [date]" claims on scored inputs (Jul 2026).** Any claim of the form "X has been Y since [date]" (or "for N weeks") about an input that feeds a score leg or gate must cite either (a) the prior report that first printed state Y, or (b) a fresh source covering the full interval. Before printing, grep the asset's own prior reports for the claimed state term; if any prior report printed the contradicting state after the claimed start date, use the latest self-consistent start date and disclose: "sustained since ~[corrected date] (prior reports [dates] printed [contradicting state])". Scope: limited to inputs consumed by name in a scoring-table row or gate-status line (funding sign/persistence, ETF flow streak length, F&G streak length, price-vs-MA structural claims) — not narrative color elsewhere in the report.

**Single-source streak-completion rule (Jul 2026, codifies the ETH Jul-9 practice).** A print that completes a streak/regime bar (e.g. the 5th green flow session) but is single-sourced marks the streak **PROVISIONAL**: disclose it, and no regime-flip claim, gate credit, or unlock keyed to that streak until a second source corroborates (by the next report at latest). If corroboration contradicts, apply the metric-history continuity rule.

**Stale-input debt clock (Jul 2026, generalizes the momentum leg's rule).** Any scored input or gate input carried on NOT FOUND / stale / derived-estimate status for ≥3 consecutive reports must ship a sourced or computed replacement next report, or state explicitly why it cannot be obtained; while carried, the affected leg/gate holds its prior value and is flagged inline. This extends the momentum-leg rule to all legs (valuation, holder, capitulation) and to gate 6's long-horizon MA.

**Mandatory computed companion score (cross-validation — Hard Rule 5).** Every Fallen Knives report must state the **computed** Flying Rocket composite for the same asset/timestamp (number + gate count, from the same live data fetch) — **estimated/eyeballed companion numbers are prohibited.** *(In May–Jun 2026 exactly one FR report existed — May 14, F&G 50, the moment of least signal — and every later FK report asserted inverse "consistency" with a sourceless "~3–4," making the check unfalsifiable precisely as fear deepened.)* If the companion composite is ≥9, append a short informational watch block (it does **not** unlock any short phase — FR Phase 1A still needs ≥13, per Hard Rule 6). If the companion **cannot** be computed (data failure), pause **net-new long deployment only** — never force a trim, relax a stop, or block a hold. The cross-validation inconsistency condition remains **both frameworks ≥12 simultaneously** (never a sum).

**Vacuity labeling + FR≥9 tripwire (Jul 2026).** While the FR phase-of-cycle cap binds (asset >20% below its 1-yr ATH), both-≥12 is unreachable by construction; in that regime the consistency line must read "structurally consistent (cap-bound; both-≥12 unfalsifiable by construction)" — never a bare consistent ✅. FR≥9 while cap-bound is a standing (currently dormant, never yet fired in-sample) heightened-watch condition, NOT a Hard-Rule-5 substitute or a new pause/unlock threshold. A full standalone companion report is additionally required when (i) the primary FK score crosses a phase-unlock threshold, or (ii) the inline FR companion prints ≥9, or (iii) ≥$100M of the day's liquidation volume is on the short side (e.g., via CoinGlass/COINOTAG), or (iv) the FR phase-of-cycle cap stops binding (asset closes within 20% of its 1-yr ATH). When the cap does NOT bind, the plain "cross-validation: consistent ✅" / "inconsistent" label stands unqualified and carries full evidentiary weight — this relabeling is conditional, not a permanent softening, and Hard Rule 5's substantive both-≥12 pause-and-flag mandate remains fully live and reactivates the instant the cap stops binding.

### 3. Critical Developments

Bulleted summary of the highest-impact news/events with cited sources. Cover geopolitics, regulation, market structure, key analyst calls, asset-specific catalysts.

### 4. Fallen Knives Composite Score (X / 20)

| Category | Max | Scoring Rubric |
|---|---|---|
| **Sentiment Extreme** | 5 | **3-day average** F&G or asset-equivalent sentiment — use the smoothed average, NOT the single-day spot print, to avoid score whipsaw (Jun 2026 F&G swung 11→23→17 in three days). ≤10 → 5 · ≤15 → 4 · ≤25 → 3 · ≤35 → 2 · ≤50 → 1 · >50 → 0. **Provider pinning + boundary tie-break (Jul 2026):** the sentiment provider is PINNED per asset series (crypto: Alternative.me raw API daily series) — spot and 3-day average must come from the pinned provider only, never mixed across days or switched between reports; a second provider may be quoted as context, and divergence ≥10 index points must be disclosed inline. Exact-boundary tie-break: a value equal to a band edge belongs to the band whose inequality includes it (15.0 ≤ 15 → 4) as the DEFAULT; an analyst may deviate from the letter only toward the MORE CONSERVATIVE (lower-score) reading, must flag it explicitly with a one-line reason, and must revisit/reconcile it next report. This fixed-boundary tie-break convention applies to all other ≤/≥ rubric bands in this table (momentum, capitulation, valuation) via cross-reference. **"Asset-equivalent" for non-BTC/ETH means a fear instrument with daily resolution, never a positioning report** — COT is PROHIBITED as the sentiment input (it already drives the capitulation leg's washout read; one input may not key two legs). Primary fallback when no asset-native daily fear instrument is reliably sourceable (the expected default for gold/commodities — DSI/HGNSI are typically paywalled/non-daily): score conservatively at 2 and flag NOT FOUND; do not substitute a price-derived or positioning-derived proxy. GVZ/DSI/HGNSI may be cited as disclosed regime context only, never as the scored input. |
| **Momentum Exhaustion** | 4 | Weekly RSI. <30 → 4 · ≤35 → 3 · ≤40 → 2 · ≤45 → 1 · >45 → 0 *(chained ≤ bands, first match wins — an exact edge (35.0, 40.0, 45.0) belongs to the band whose ≤ includes it, mirroring the pinned sentiment tie-break; deviation permitted only toward the lower score, flagged)*. *(Removed the unreachable <25 → 5 tier — it only fires at generational bottoms and biased the legacy framework toward underdeployment.)* **Input rule (Jul 2026, mirrors the correlation leg's "sourced or not computed"):** the weekly RSI must be (a) sourced with timestamp, or (b) computed as Wilder RSI-14 from ≥15 fetched weekly closes (15–29 closes = a low-confidence read — flag it, and if the value lands within 2 RSI points of a band edge take the lower-score band; ≥30 closes for an unflagged read), with the weekly-close source, weekly boundary (e.g. Mon 00:00 UTC), period (14), and number of closes used stated inline for auditability. An "inferred ~X–Y" eyeball range is NOT a scoring input. If neither sourced nor computable, hold the leg at its prior value and flag NOT FOUND (cold start with no prior value: score the leg 1 — conservative-on-a-buy-signal, mirroring the sentiment leg's fallback-at-2 convention — and start the debt clock at report 1); a leg held ≥3 consecutive reports on NOT FOUND must ship the computed fallback next report or state why computation failed — never fabricate closes. This changes an INPUT only — momentum lighting alone never completes a 1B/2 unlock without the independently-required gate count. |
| **Valuation** | 5 | **Primary (BTC/ETH):** MVRV-Z. **<0.1 → 5** (generational green-zone; any negative MVRV-Z also lands here) · **≤0.5 → 4** · **≤2 → 3** · ≤3 → 2 · ≤5 → 0 · >5 → −2 (trim signal) *(chained ≤ bands, first match wins; an exact edge belongs to the band whose ≤ includes it — 0.50 → 4. The former 0.5–1.0 / 1–2 bands both scored 3 and are merged, output-identical)*. *(Re-banded Jun 2026: the legacy `0–1 → 4` band spanned the entire $79.5K→$66K decline and sat saturated at 4/5 from a local top — MVRV 0.64–0.87 in May scored 4 when it should read 3. The 0.5 breakpoint is where the realized bottom actually sat.)* **Fallback (alts without reliable MVRV):** drawdown from ATH. ≥70% → 5 · ≥60% → 4 · ≥50% → 3 · ≥40% → 2 · ≥30% → 1 · <30% → 0 *(chained ≥ bands, first match wins; an exact edge belongs to the band whose ≥ includes it — 60.0% → 4)*. State which metric was used. **Low-volatility adaptation (gold/commodities, Jul 2026):** may be substituted ONLY when the asset's realized 30-day vol is documented (sourced + timestamped) at ≤½ of BTC's contemporaneous 30-day vol, and the substitution is logged in the Change Log with the historical bear distribution it is anchored to (2008 −30%, 1996–99 −39%, 2011–15 −45%) and the current regime's realized vol/ratio disclosed. Bands: ≥45%→3 (gated behind a CONFIRMED positioning capitulation — a COT non-commercial net-long flush; absent a flush, cap at 2) · ≥36%→2 · ≥28%→2 · ≥20%→2 · ≥12%→1 · <12%→0 *(chained ≥, first match wins)*. This band-set feeds the [V] scoring leg ONLY — it does not relight gate #3, confers no gate credit, and no stop change. Never score a leg off an undocumented "asset-adapted" judgment constant; if the published band-set doesn't fit the asset, publish the asset's band-set and log it in the Change Log, stating which metric AND which band-set was used. |
| **Capitulation Evidence** | 3 | Count of: (a) **24h liquidations in the top decile of the trailing-90-day distribution OR >3σ above the trailing-30-day mean** — state explicitly whether the figure is asset-specific or market-wide and be consistent across reports. *(Regime-relative as of Jun 2026: the legacy fixed `>0.5% of mcap` bar NEVER fired for any asset in the May–Jun 2026 sample — BTC peak ~0.13%, ETH ~0.20%, SOL ~0.014% — an unreachable tier, the same defect removed from the momentum leg. A real flush must register.)* (b) perp funding negative for ≥3 consecutive funding intervals, (c) ETF net outflows ≥2% of AUM over trailing month (BTC/ETH only — for alts, substitute exchange-inflow spike). 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **Holder Behavior** | 3 | (a) LTH supply rising 30d (BTC/ETH) or top-100 holder concentration stable/rising (alts), (b) exchange reserves declining 30d. Both → 3 · One → 1.5 · Neither → 0 |
| **TOTAL** | **20** | |

**Correlation Regime Treatment** (revised Jun 2026 — demoted from a score multiplier to a sourced gate surcharge + context label):

The legacy multiplier (×0.85 / ×1.00 / ×1.05 / ×1.10) rode on *eyeballed* correlations ("~0.3–0.5") and could flip the gate-driving integer off an unsourced guess — a "modifier technicality." It never changed an unlock in the May–Jun 2026 sample, so removing the bonus side is non-destructive. The only legitimate function — distrusting a "fear" score in a high-beta risk-on regime — is preserved as a **breadth surcharge**, not an arithmetic haircut:

- **Risk-on suppressor (kept, hardened):** if a **sourced** 30-day Pearson correlation (CoinMetrics / TradingView / computed from price series, with timestamp) is **>0.7**, require **one additional [V] confirmation gate** for ANY phase unlock — defined precisely (Jul 2026): the phase's required TOTAL gate count increases by 1 AND its [V]-floor increases by 1 (the incremental gate must come from the [V] bucket). A fear signal that is really equity beta deserves more confirmation, not a fractional score cut — and this now protects Phases 1A/1B, which the old ×0.85 covered but the Phase-2 `corr <0.8` gate did not.
- **Decoupled / inverse (corr <0.2 or <0):** **context label only** — no score change. The §5 trend modifier and the Phase-2 `corr <0.8` gate already carry the regime information.
- **Sourcing rule (Hard Rule 1):** state the sourced correlation + timestamp, or write "not computed this cycle." If not computed, the risk-on surcharge defaults **OFF** (never penalize on a guess either).

Round to nearest integer. **Adjusted score = the rounded raw composite — no multiplicative modifier remains (Jun 2026); the [V]-gate surcharge changes gate requirements only, never the score number.** Half-point ties follow the established per-asset convention (adjudicated in the 2026-07-04 calibration — see the "round-down-at-.5" rejection): BTC and Gold round .5 **up**; ETH rounds .5 **down** (estimate-heavy input set → conservative on a buy signal). For a new asset, declare a convention with a one-line rationale at its first .5 occurrence and hold it constant for the series. State raw score, any [V]-gate surcharge applied, and adjusted score. *(The Flying Rocket ×1.05 decoupled modifier is demoted symmetrically — see that skill's revision log.)*

#### Confirmation Gates (X / N, where N = active denominator: 9 on the full board, 8 when one gate is N/A) — drives phase unlocks, not scoring

Mark each ✅ / ⚠️ / ❌. Each gate is tagged **[V] fear/value** (the conditions you WANT lit to accumulate) or **[T] floor/trend** (conditions that mechanically turn OFF as price falls):

1. **[V]** Sentiment ≤15 (or asset-equivalent extreme) for ≥7 consecutive days — the streak counts the pinned provider's DAILY prints (the series every report to date has counted), not the 3-day average; the 3-day average remains the scored sentiment input
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

**Gold/low-vol gate substitutions (codified Jul 2026 from the report series):**
- **Gate 1 (gold):** a CONFIRMED COT positioning washout replaces the daily-sentiment streak. Weekly instrument → streak equivalence: a qualifying flush print (WoW non-commercial net-long decline ≥20–30K contracts or ≥15% of the net — the series' stated guideline) marks the gate ⚠️ provisional; it turns ✅ when the next weekly print holds or extends the washout. Gate-level reuse of the capitulation leg's COT input is permitted exactly as gates 2/3/4 reuse their legs' inputs (the double-key prohibition bars one input from keying two *score legs*); COT remains PROHIBITED as the sentiment LEG input.
- **Gate 3 (gold):** permanently un-creditable by construction (the low-vol band-set confers no gate credit) — it stays in the /8 denominator deliberately (conservative); its reachability tag may read "none-by-construction."
- **Gate 4 (gold):** sustained physical gold-ETF outflows — a trailing-month global (WGC) net outflow that is multi-region and among the worst of the trailing 12 months, corroborated by GLD flows; state the figures (mirror of the crypto ≥2%-of-AUM bar).
- **Capitulation leg (gold):** (a) unchanged (vol/volume flush), (b) = the COT washout defined above (replacing perp funding), (c) = the gold-ETF outflow spike per gate 4.

**Counting rule (revised Jun 2026):**
1. **N/A denominator reduction (ported from Flying Rocket).** If a gate is *structurally inapplicable* to the asset/cycle — Hash Ribbon (gate 5) on a PoS asset is the canonical case — mark it **N/A and reduce the denominator by 1**. Never silently mark an inapplicable gate ❌ (false-negative bias), and **never convert an ambiguous ⚠️ to N/A** (that is a measurable-but-missing signal, not an inapplicable one). For ETH/SOL, gate 5 is N/A → denominator becomes 8, leaving only two [T] gates (6, 9). Without this reduction, Phase 3's "≥7 of 9" was nearly unreachable for PoS assets.
2. **Thresholds are proportions, not fixed counts.** Each phase's gate requirement is `ceil(fraction × active_denominator)`, where the fractions are 1A ≈ ⅓, 1B ≈ 5/9, 2 ≈ ⅔, 3 ≈ 7/9. On the full /9 board this reproduces the familiar ≥3 / ≥5 / ≥6 / ≥7. On an /8 board (e.g. PoS gate-5 N/A) the ceil arithmetic yields the numerically identical 3 / 5 / 6 / 7 — the reduction never lowers a phase requirement until the denominator falls to ≤7. (Worked check: ceil(7/9 × 8) = 7, not 6 — the value ETH misprinted in three Jun-2026 reports.)
3. **[V] floor (anti-[T]-veto).** At least the stated [V] minimum must come from the [V] bucket (1A ≥2 [V], 1B ≥3 [V], 2 ≥3 [V], 3 ≥4 [V]). Six [V] gates exist, so every floor is satisfiable without any [T] gate — trend gates can no longer veto an unlock the fear/value evidence already earns.
4. **Override path.** A tranche may also unlock via the **Deep-Value Override** (§6) regardless of [T] gates, subject to the throttle defined there. Treat [T] gates as sizing/timing inputs, not hard vetoes.

> **Cross-reference (gate 6 ⇄ §5 trend modifier):** the same 200-week break that keeps gate 6 lit (within ±8%, pro-*eligibility*) also shifts the §5 matrix bearish (pro-*caution on EV/sizing*). This is **not** a contradiction — gate 6 governs whether a phase *can* unlock; the matrix governs *how big / what edge*. They are not meant to cancel; report both.

**Gate reachability disclosure (Jul 2026, documentation-only).** Mandatory one-line annotation per dark gate — a *dark gate* is any gate not counting toward the unlock, i.e. marked ❌ or ⚠️: "relight path: <concrete nearby condition>" — required uniformly for every dark gate, re-derived each report (not carried over from a prior report). Reserve the tag "none-in-regime" ONLY for a gate that is structurally unreachable without a large, slow-moving change (e.g., gate 6: price 31%+ below the long-horizon mean) — forbidden to tag any gate "none-in-regime" while it is ⚠️ or was within its trigger band in the trailing window (= since the prior report in this asset's series). *(Sole exception: a gate that is permanently un-creditable by construction — currently only gate 3 under the gold low-vol adaptation — may carry "none-by-construction" regardless of its ✅/⚠️/❌ mark.)* This disclosure is informational only; a "none-in-regime" finding is NOT evidence a gate is mis-specified and may NOT be cited to lower a threshold, reduce the denominator, or credit a gate — the default conclusion remains that dark gates are correctly dark. No aggregate "effective board X/N" denominator, no "requires perfection" framing, and no threshold/score/unlock change may accompany this disclosure. Any threshold change requires a separate, independently-graded tune.

### 5. Probability Matrix — Derived From Score

Use this baseline grid (**re-flattened Jun 2026** — see below), then adjust each cell by up to ±10 **percentage points** for idiosyncratic catalysts (state every adjustment; cells must re-normalize to a 100% sum):

| Adj. Score | Rally | Range | Retest | Bear |
|---|---|---|---|---|
| 0–5 | 10% | 30% | 35% | 25% |
| 6–10 | 20% | 35% | 30% | 15% |
| 11–14 | 30% | 35% | 22% | 13% |
| 15–17 | **38%** | **33%** | **19%** | **10%** |
| 18–20 | **50%** | **28%** | **14%** | **8%** |

> **Why flattened:** the legacy grid put Rally at **50% (scores 15–17) / 65% (18–20)** — so deepening fear *mechanically manufactured* a majority rally weight, and because **EV = Σ(probability × midpoint)** with targets anchored above a falling spot, EV came out **positive by construction at maximum fear**. In May–Jun 2026 this produced ~5 of 7 BTC EV reads pointing up while price fell every time, Rally staying *modal* on Jun 6 and Jun 10 at the cycle's deepest fear. The grid is a **fear map, not a direction forecast** — Rally is now capped at ≤50% — never a *majority* weight — at every tier (it may still be the single largest cell, as at 15–17 and 18–20; what the flattening removed is the manufactured >50% rally mass). This cap binds POST-adjustment as well: after the ±-point adjustments and the trend residual, Rally may not exceed 50% at any tier unless a realized trend-structure event (major-MA weekly reclaim / confirmed higher-low — the collar's strong-claim unlock) is cited on the same line.

**Trend/regime modifier (residual, Jun 2026).** With the grid flattened, the trend term is now a small residual to avoid double-counting: when price is **below a major MA AND making lower lows** (active downtrend), shift a further small residual — **at most 5–7 percentage points of mass from Rally → Retest + Bear** and widen downside target ranges; apply the mirror toward Rally when the trend repairs (major-MA reclaim / confirmed higher-low). State the shift. (See the gate-6 ⇄ §5 cross-reference in §4.)

**EV-floor consistency check (Jun 2026).** After computing EV, if **EV-vs-spot is negative while adjusted score ≥15 AND 3-day F&G ≤15**, flag it as an internal inconsistency and re-examine inputs — a genuine deep-value, extreme-fear zone should not simultaneously show a *negative* accumulation edge (that pattern usually means the targets or the trend shift were set too pessimistically, and it would perversely fight the Deep-Value Override). Do not deploy *or* refuse to deploy on a flagged-inconsistent EV; resolve it first.

**Terminal-vs-extreme reconciliation (Jul 2026).** Whenever the §5 trend residual is live (active downtrend) AND the modal scenario is Range/base-building, append one line: "Reconciliation: modal band expresses the TERMINAL expectation; while the §5 downtrend residual is live, the path EXTREME is expected in the [direction-matched] band $[X–Y] — base-building labels do not claim the low is in." The reconciliation must point to whichever combined direction (Rally vs Retest+Bear) matches the sign of the live §5 trend-residual shift, not merely the second-highest-probability scenario; in a near-tie (within 2 percentage points), name both adjacent bands or default to whichever is more bearish/further from spot. The words "base-building"/"floor" may not appear on the modal row without this line.

**Mandatory EV recomputation/sum-check (Jul 2026).** Probabilities must sum to 100%. Self-check: recompute the Weighted EV from the printed cells as the final step, flowing from the printed probability/midpoint cells to EV only (never the reverse — never adjust cells to hit a preferred EV); if the stated EV differs from the recomputation by >0.5% (measured relative to the recomputed EV), correct before publishing. Show the component sum.

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value** = Σ (probability × midpoint of target range). State EV explicitly, and EV-vs-spot %. **Disclose the realized trailing-2-week price change next to the EV claim** — a positive EV printed during a −X% two-week move must say so, so the reader sees the EV contradicts realized momentum.

### 6. Deployment Strategy

Splits: **10 / 15 / 30 / 45** (front-loaded pyramid — bigger tranches at deeper drawdowns, where reward-to-risk is highest). State **total dry powder %** prominently.

**Partial-tranche deployment (Jul 2026, codifies the BTC Jul-9 practice):** an unlocked phase authorizes UP TO its nominal size; deploying a stated fraction (e.g. a half-size 1B) laddered within the zone is permitted, with the remainder staying assigned to that phase and deployable in the same zone without a fresh unlock. Upsizing beyond nominal is prohibited. (Override firings keep their own half/quarter-size rules.)

**⚑ Deep-Value Override (knife-deepening rule — added Jun 2026, recalibrated Jun 2026).** The pyramid's whole purpose is *bigger tranches at deeper drawdowns*. In May–Jun 2026 the gate system did the opposite: BTC deployed only its smallest 10% tranche at ~$65K, then was locked out of the 15/30/45 tranches as price fell to $59K and score *rose* to 16 at F&G 9 — maximum signal, zero incremental deployment. The override defeats that inversion. The **next** tranche unlocks **regardless of [T] gate count** when ALL of the following hold (the Override presupposes at least one deployed tranche — it unlocks the tranche AFTER the most-recently-deployed one and can never unlock Phase 1A; with zero tranches deployed it is N/A):

- Adjusted score ≥15, **AND**
- **Price condition (reachability-fixed):** the trailing-period **low or a daily close** is **≥8% below the blended cost of the most-recently-deployed tranche** AND a **fresh lower-low** has printed since that tranche. **Definitions (Jul 2026):** *trailing-period* = the window since the most-recently-deployed tranche's fill. *Fresh lower-low* = a daily low breaking below the most recent prior swing low in daily structure since that fill — a structure break, NOT a break of the cycle low (the cycle-low reading would restore the unreachability the Jun-2026 re-anchor removed — the exact ambiguity that left the Jun-24 evaluation "borderline"; the swing-low reading still refuses shallow-dip chop). *(The original "spot ≥10% below basis" was evaluated only at report-time spot — in Jun 2026 the deepest print was −9.1% off the $65K basis, so the 10% trigger NEVER fired despite score 16 / F&G 9; it shipped decorative. Trailing-low + 8% makes it actually reachable without firing on a shallow dip.)* **AND**
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
- **Unlock gates:** adjusted score ≥10 AND ≥3 of 9 confirmation gates ✅ — or `ceil(1/3 × active denominator)` when any gate is N/A — (with ≥2 from the **[V]** bucket)
- **Entry zone:** ASSET-SPECIFIC. State price range. **Ladder across the FULL zone — never deploy at the top of it** (May 31 2026 instructed a top-of-zone entry at $70–73K; the better fill came from laddering into $64–67K days later).
- **Stop:** governed by **Stop Philosophy** below (the CATASTROPHIC stop is the line placed *below the deepest planned buy zone*, per the placement rule; the compound thesis stop is a separate price-AND-score line and may sit at or inside deeper zones by design — it cannot fire on price alone; run the mandatory stop-vs-buy-zone coherence check).
- **Status:** DRY POWDER / DEPLOYED ([entry avg]) / STOPPED OUT

#### Phase 1B — Building (15%)
- **Unlock gates:** adjusted score ≥13 AND **[ (≥5 of 9 gates ✅ — or `ceil(5/9 × active denominator)` when any gate is N/A — with ≥3 from the **[V]** bucket) OR (Deep-Value Override fires) ]** AND Phase 1A entered *(bracketing mirrors Phase 3: the [V]-floor binds only to the gate-count branch, never to the Override branch)*
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 2 — Conviction (30%)
- **Unlock gates:** adjusted score ≥15 AND **[ (≥6 of 9 gates ✅ — or `ceil(2/3 × active denominator)` when any gate is N/A — with ≥3 from the **[V]** bucket) OR (Deep-Value Override fires) ]** AND correlation regime not "risk-on extreme" (corr <0.8). **Corr input rule (Jul 2026):** when Phase 2 is otherwise unlocked, sourcing/computing the 30d correlation becomes mandatory (it is Required Data Fetch #8); if a documented attempt genuinely fails, the corr condition defaults to PASS (mirror of the surcharge's "defaults OFF — never penalize on a guess"), with the mandatory line "corr unverified — risk-on-extreme untested." An unattempted fetch blocks Phase-2 deployment (Hard Rule 1). *(The "macro neutral-to-positive" condition is now a **[T]** sizing/timing input, not a hard veto — it structurally turns off in every fear spike, which is when this tranche is supposed to fire.)*
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 3 — Generational (45%)
- **Unlock gates:** adjusted score ≥17 AND **[ (≥7 of 9 gates ✅ — or `ceil(7/9 × active denominator)` for PoS assets where gate 5 is N/A — with ≥4 from the [V] bucket) OR (Deep-Value Override fires AND a weekly capitulation candle has printed) ]** AND LTH selling has collapsed (or alt equivalent). *(Parentheses are load-bearing: the capitulation-candle requirement binds ONLY to the override branch, never to the normal gate path — this is the generational 45% tranche, the most expensive place for an operator-precedence ambiguity. Sustained ETF inflows ≥5 sessions are a **[T]** confirmation — strong when present, but this tranche may fire on a capitulation candle into max fear rather than waiting for inflows that only appear after the recovery begins.)*
- **Entry zone:** [range — typically requires capitulation candle on weekly]
- **Status:** DRY POWDER / LIVE / FROZEN

**Stop Philosophy (revised Jun 2026).** A fear-accumulation strategy adds *into* lower prices; a hard per-tranche price stop that sits **above** a deeper planned buy zone is self-contradictory — it ejects you before your own Phase 1B/2/3 entries. In Jun 2026 the BTC Phase 1A stop ($58K) sat *inside* the Phase 1B zone ($53–58K), and the Jun 6 low of $59,110 came within ~1.9% of stopping the position out **at maximum fear** (score 16, F&G 9) — selling the exact bottom the framework exists to buy. Gold already shipped the coherent design; crypto now inherits it:

- **Placement rule (mandatory):** the catastrophic stop sits **strictly below the deepest defined buy zone**. If the deepest zone is open-ended, anchor the stop a defined margin below the deepest *named* price reference. There is **no "no-stop" option** — every deployed position carries a stop placed below the ladder (an un-stopped position alongside an override that adds into weakness is how books blow up).
- **Compound thesis stop (price AND score):** invalidation fires only on a **sustained weekly close below the structural floor (≥2 consecutive weekly closes) AND the composite score back below the asset's named score line (default 12)**. **Score-line calibration (Jul 2026):** for an adapted asset whose composite is structurally pinned below 12 — which would make the score condition vacuous and degrade this stop to price-only, the exact failure the compound design exists to prevent — the line must be re-set at stop-set time to sit meaningfully below the asset's realized score range, via an explicit Stop Migration line. (Gold runs <8, set 2026-06-17 and ratified here retroactively — the original change shipped before the Migration Ledger existed; BTC/ETH remain at 12.) A pure-price line that would have fired at score 16 / F&G 9 is self-contradictory; requiring the score to *also* have rolled over means the stop fires only when fear AND value have genuinely deteriorated — it can never fire *more* often than a price-only stop, only less. (Would NOT have fired Jun 6, correctly keeping the holder in the position that recovered.)
- **Mandatory time stop:** every tranche also carries a max-hold/decay limit; an accumulation thesis that hasn't worked after a stated horizon is reassessed, not held indefinitely.
- **§7 narrative-break exit is independent** — the 100%-exit on a broken thesis stands on its own, separate from the price/score stop.
- **Stop-vs-buy-zone coherence check (mandatory, every report — long side only, re-scoped Jul 2026):** evaluate against the deepest buy zone or ladder named ANYWHERE in the report — any Phase 1A/1B/2/3 row carrying an explicit price/entry-zone, or any prose labeled a "prospective ladder"/"future ladder" (excludes probability-matrix scenario bands, which are forecast ranges, not buy commitments). If a Phase row's price is omitted in a given report, fall back to the most recent report's named price for that phase. If multiple prospective zones are named with different floors, use the single LOWEST floor. Frozen deployment does NOT pass by construction — test against the deepest prospective ladder floor. Print the two numbers and the boolean — *"CATASTROPHIC stop [X] strictly below deepest active buy-zone floor [Y]? PASS/FAIL."* (The check tests the catastrophic tier — the placement rule's subject. The compound thesis line is not the tested number; it may legitimately sit inside deeper zones because it cannot fire on price alone.) If any prospective ladder floor sits at or below the current stop, print the paired post-activation stop level and the atomic activation sequence (re-set stop to [Z] BEFORE the first fill) and run the boolean in both states; if post-activation genuinely fails, the report may still publish but must carry an explicit "stop realignment owed" flag and cannot authorize new deployment until resolved. Widening or removing a stop while the Deep-Value Override is armed requires stating the explicit max-drawdown-to-thesis-stop figure. *(This check is FK-only; Flying Rocket's stop-above-entry is correct by design — do not port it there, per Hard Rule 6.)*
- **Stop Migration Ledger (Jul 2026).** Any change to any stop parameter (price line, score condition, checkpoint date, catastrophic anchor) prints a Stop Migration line: old → new, direction (toward/away from price), which TIER changed (compound-line vs catastrophic-floor vs score-condition vs checkpoint date — named explicitly), and one-line rationale. Each parameter that changes value gets its own line even when multiple move in the same report. This applies to every migration regardless of direction, including a loosening that reverses a prior tightening — "silent" is defined as any un-annotated numeric change to a stop parameter. Silent migrations are a publishing FAIL.
- **Calendar-lock on dated stop checkpoints (Jul 2026).** Every dated stop checkpoint must be validated against the venue trading calendar at write time: print the exact session date, close time, and any holiday/abbreviated-session note. If a checkpoint date resolves to a non-trading day, restate it to the nearest ACTUAL trading session's close for that same calendar week using the venue's real calendar (direction — earlier or later within the week — follows whatever the calendar dictates, never a fixed backward-only rule), and disclose the correction explicitly. Tiebreak (the single named exception to "direction follows the calendar"): a checkpoint naming a non-trading day immediately preceding a multi-day holiday weekend defaults to the last close BEFORE that non-trading day.
- **Checkpoint prognosis discipline (Jul 2026, addendum to Principle 4 / Verdict-Confidence Collar).** A stop-checkpoint prognosis is a directional forward claim subject to the forward-claim hedge rule (Principle 4). Checkpoint narration is restricted to the mechanical form: "Checkpoint [weekday-verified date, cross-checked against a named calendar source — computed BEFORE any distance/likelihood language]: fires iff [condition]; spot is [X]% [above/below] the line ([Y]× the 5-day average daily range, computed as mean of |daily high − daily low| over the last 5 FULL trading sessions from the same canonical spot source used for the report's price reconciliation — holiday-abbreviated sessions are EXCLUDED and the lookback extended to reach 5 full sessions, with the exclusion disclosed inline; an abbreviated session shrinks the ADR and flatters the distance ratio); next tier-1 release before the checkpoint: [event/none]." A likelihood adjective ("likely"/"probable") is permitted ONLY when traced to a named quantity (the stated distance-vs-ADR ratio and/or a specific just-printed data point with direction and magnitude) AND no unpriced tier-1 release sits between report and checkpoint. Additionally, every report's Watchlist/Critical Watchlist section must explicitly check the calendar for tier-1 US macro releases (payrolls/CPI/FOMC/PCE) landing between the report date and the next stop checkpoint, and flag if the pending checkpoint has NOT priced one in; when a tier-1 release lands before the checkpoint, the prognosis must explicitly name that release and its expected direction of effect (up/down/neutral) as part of the falsifier — omitting it is a violation of the hedge rule.

**Dry powder yield benchmark:** state assumed opportunity cost (current T-bill yield or USDC/sDAI yield). Cash is a position; idle cash has a measurable cost.

### 7. Exit / Trim Framework (Symmetric)

The framework de-risks as conviction signals invert. Track cost basis per phase. Trims execute against most-recently-deployed tranches first (LIFO).

| Trigger | Action | Rationale |
|---|---|---|
| Adjusted score drops ≥6 points from local peak — *local peak* = the highest adjusted score printed since the current campaign's first fill (a cold start resets it). Labeled measurement/input-honesty corrections do NOT count toward the drop: restate the peak on corrected inputs and compare like-for-like (carve-out valid only when the correction was labeled in the report where it was taken) | Trim 25% | Signal exhaustion in progress |
| F&G ≥75 sustained 7d **AND** weekly RSI >70 | Trim 25% | Sentiment + momentum euphoria |
| MVRV-Z >3 (BTC/ETH) or drawdown from ATH <10% with vertical 30d return | Trim 50% | Valuation extreme |
| Adjusted score ≤3 **AND** price ≥40% above blended cost | Trim 25% | Score cycle complete |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime — *sustained inflow regime* = the framework's own ≥5-consecutive-green-session flow-flip bar was met during the held position's life | Trim 25% | Institutional conviction breaking |
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
- **Tier-1 macro calendar-lock on short-horizon prose (Jul 2026).** The watchlist's fetched tier-1 release line (Required Data Fetches) is checked just-in-time whenever a short-horizon declarative comparative claim ("the more likely outcome", "not a V-recovery", "path of least resistance") is about to be written. Any such claim whose resolution window contains a tier-1 release must name it and use event-conditional phrasing: "pre-[event]; claim resolves conditional on [event], [date/time]". If the calendar fetch fails, the report may still print such claims but must flag them: "calendar-unverified — treat as base case, not primary call."

## Score Interpretation

| Adjusted Score | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — insufficient fear, or active distribution regime |
| 6–10 | Early Warning | PREPARE — build watchlist, refresh thesis |
| 11–14 | Accumulation Zone | CAUTIOUS ENTRY — Phase 1A eligible |
| 15–16 | Strong Signal | SYSTEMATIC DEPLOY — Phases 1A–2 eligible |
| 17–19 | Historic Opportunity | AGGRESSIVE DEPLOY — Phases 1A–3 eligible |
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
reports/[asset]_fallen_knives_[YYYYMMDD]_[HHMM].md
```

Path is repo-relative, per CLAUDE.md's Output Convention (which also governs the post-save commit/push). Filename uses lowercase asset symbol. Example: `btc_fallen_knives_20260514_0930.md`.

**Machine block + lint (mandatory, Jul 2026).** Every report ends with a fenced ` ```json machine ` block (schema `report-machine/1` — field list in the header of `tools/lint-report.mjs`) carrying the structured facts: spot, score legs/raw/adjusted/rounding, gates (active/na/passed), EV scenarios + stated EV, deployment, stops, verdict, key inputs. After saving and BEFORE committing, run `node tools/lint-report.mjs reports/<file>.md` — it recomputes the arithmetic (legs sum, rounding convention, gate denominator + ceil thresholds + [V] count, EV within the §5 0.5% tolerance, Rally ≤50% cap, stop coherence). **A FAIL is fixed, never overridden or committed around.** The block also makes future calibrations cheaper and more accurate: extraction agents read it instead of re-deriving numbers from prose.

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

### 2026-07-04 — Adversarial re-calibration (314-agent backtest, 39 reports, Jun-11 → Jul-2 2026 window; thorough audit, 3 skeptics/tune)

**Prior-calibration re-validation:** all 19 tunes adopted 2026-06-11 were re-graded out-of-sample against 38 post-calibration reports (BTC/ETH/Gold/UNI). **15 validated cleanly, 4 not-exercised** (override throttle, gate-7 regime-relative capitulation, and the FR symmetric mirrors — no euphoria/flush regime occurred; the ETH `ceil(7/9×8)` arithmetic self-corrected mid-window with zero practical effect), **0 harmful. All 17 prior rejections held — none re-adopted.** The Deep-Value Override armed for the first time in framework history (proving the reachability fix) but was correctly veto-blocked both times by the worsening-flows condition; net effect ~+0.2% of book foregone at the BTC cycle low, N=1 on the veto's protective value. **Flying Rocket produced zero reports since Jun-18** — its adopted tunes are validated only by construction, not by a live report; treat FR as unaudited pending its next run.

**18 new FK-side tunes adopted-with-modification** (38 candidates cleared adversarial review across both frameworks out of 83 proposed; skeptic panels of 3 per tune, strictest-wins merge; full detail + exact before→after text in `reports/strategy_retrospective_20260704.md`):

- **Data integrity (mostly FK, some shared):** metric-history continuity rule (bars backdating a regime past a prior report's contradicting print — closes the ETH funding-backdate defect); provenance citation for "since [date]" claims on scored inputs; sentiment provider pinning (Alternative.me only, no cross-provider mixing) + a fixed exact-boundary tie-break (`15.0 ≤ 15 → 4`, letter-of-the-rule default); momentum input rule mirroring the correlation leg's "sourced or computed, never inferred"; canonical-spot reconciliation refined to distinguish staleness-driven dispersion from genuine simultaneous disagreement; mandatory tier-1 US macro calendar fetch + a calendar-lock on dated stop checkpoints and short-horizon comparative prose (the Jul-1 NFP-omission defect).
- **Gold/low-vol asset adaptation:** codified the ad-hoc "gold-adapted" valuation band-set into sourced, capital-neutral bands (top band gated behind a confirmed COT flush); gold sentiment leg restricted to a daily fear instrument, COT prohibited as the sentiment input (it already keys the capitulation leg — one input may not key two legs).
- **Stop discipline:** Stop Migration Ledger (mandatory old→new/tier/rationale line on every stop-parameter change — would have caught the undisclosed Gold `<12→<8` score-condition halving); calendar-lock on stop checkpoints; the buy-zone coherence check re-scoped to test against ANY named ladder/zone in the report (not just the active phase), closing an ETH Phase-2 drift loophole; checkpoint prognosis narration restricted to a mechanical form (weekday-verified date, ADR-relative distance, named tier-1-release check) before any likelihood language is permitted.
- **Scoring/EV:** a Terminal-vs-extreme reconciliation line when the §5 trend residual is live but the modal cell reads Range/base-building (prevents "floor" language from implying the low is in); mandatory EV recomputation/sum-check as the final publishing step; gate reachability disclosure (per-dark-gate "relight path," explicitly barred from being cited to lower a threshold or credit a gate); vacuity labeling + a standing (dormant) FR≥9 watch tripwire while the FR phase-of-cycle cap structurally blocks Hard Rule 5.

**Withheld at apply-time (guardrail):** one tune — a Cross-Asset Conviction-vs-Capital Ledger — was rejected at the pre-apply audit stage as a family resemblance to the already-rejected R5 (soft mandate to re-examine gates, redundant with existing disclosure).

**23 FK-side rejections held (a representative sample; full list + evidence in the retrospective):** a gate-1 sentiment-streak tolerance and a "durability-symmetric" streak reset (both would have lit gate 1 on the realized path at the exact BTC cycle high/low, arming a veto-bypass Phase-1B unlock into a record-outflow tape); a capitulation squeeze-expiry credit and a graduated worsening-flows-veto half-step (both re-open the "strip points/lift the veto during deepening fear" pattern R1/R2 were rejected for, and both fail their own cited numbers on independent verification); a 1B alternative-breadth path and a gold Phase-2 second key (both capital-neutral over the realized path — the same defect that sank R4/R8/R15 — or would deploy an unthrottled full tranche exactly where the framework's own throttle is supposed to bind); a zone-freeze rule and a zone-placement/band-anchor-lock family (would have frozen a ladder across a confirmed 200-week break, forcing the stop UP and blocking the Jul-2 structure-conditional re-stage the framework correctly executed); a round-down-at-.5 rule (misquotes the corpus — BTC's half-points round up, ETH's round down, a documented asset-specific convention, not an ambiguity). **None of the 41 total rejections this cycle reversed a prior-adopted tune** — the anti-thrash veto held throughout.

**Coverage disclosure:** 39/39 reports extracted cleanly (0 dropped), all 7 framework×asset series graded, all 83 proposed tunes received a full 3-skeptic panel (0 unadjudicated) and a pre-apply audit pass. SOL has zero reports since 2026-05-28 — its prior predictions remain untestable and are carried forward as a coverage gap. Gate 7 (regime-relative capitulation) has now stayed dark through two consecutive full legs down — a watch item, not yet evidence the threshold is unreachable.

**N=1 — re-validate after the framework has weathered a euphoric top, a genuine capitulation flush, and at least one live Flying Rocket cycle.**

### 2026-07-10 — Red-team documentation audit: hardening pass + owner-delegated adjudications

Source: adversarial doc audit (full memo with per-finding evidence: `audits/fallen_knives_doc_audit_20260710.md`). **This is NOT a calibration** — no backtest workflow ran, no probability cell, phase size, stop price, or unlock threshold moved on fitting grounds. Two layers:

**Layer 1 — pure doc-hardening (20 edits, zero decision changes):** stale text corrected (Overview no longer claims macro direction/correlation feed the 0–20; "Rally capped below modal" corrected to the intended "never a majority ≤50%" — Rally is the modal cell at 15–17/18–20 as printed; output path re-pointed from a dead macOS desktop path to repo-relative `reports/` per CLAUDE.md). Definitions pinned to existing rules/practice: adjusted score = rounded raw composite (surcharge touches gates only); the adjudicated per-asset .5 tie-break codified (BTC/Gold half-up, ETH half-down); gate-1 streak counts daily pinned-provider prints; "dark gate" = ❌ or ⚠️; EV sum-check tolerance relative to recomputed EV; Override explicitly requires a deployed base. Structure: Phase 1A/1B/2 rows carry the `ceil(fraction × active denominator)` note and Phase-3-style bracketing ([V]-floor binds to the gate-count branch only — retires the 2026-07-04 retrospective's outstanding item #2); gates heading denominator-aware; /8 board shown to reproduce 3/5/6/7 exactly (root cause of the ETH ceil misprint); vacuity trigger (iii) split into (iii)/(iv); calendar tiebreak labeled the named exception; redundant valuation bands 0.5–1.0/1–2 merged (output-identical); Phase-1A stop row re-labeled CATASTROPHIC (was mislabeled "compound" — contradicting the placement rule and every report's practice) and the coherence-check boolean now names the catastrophic tier as the tested number.

**Layer 2 — owner-delegated adjudications (2026-07-10 mandate: "more agile — miss fewer opportunities — while still managing risk"):**
1. **Compound-stop score line** = asset-named, default 12; structurally-pinned-score assets re-set it below their realized score range via an explicit Stop Migration line. Gold's `<8` (running since 2026-06-17, originally undisclosed) ratified retroactively; BTC/ETH stay 12; no catastrophic tier touched, no live stop moved.
2. **Override definitions:** trailing-period = since the most-recently-deployed tranche's fill; fresh lower-low = a daily-structure swing-low break, NOT a cycle-low break (restores the documented Jun-2026 reachability intent; resolves the Jun-24 "borderline" ambiguity toward the agile-but-structure-gated reading).
3. **Score Interpretation table aligned to the operative §6:** Phase-3 eligibility begins at 17 (bands now 15–16 / 17–19) — the table was the contradicting text; §6's ≥17 was always the unlock.
4. **Band-edge tie-breaks:** momentum/valuation/drawdown/gold bands rewritten as chained ≤/≥ (first match wins), assigning exact edges to the higher-score band — mirrors the pinned sentiment convention (15.0 ≤ 15 → 4); the conservative-only deviation hatch stands.
5. **Phase-2 corr default:** computation mandatory at unlock; documented-failed attempt → PASS with a mandatory "corr unverified" line; unattempted fetch blocks (Hard Rule 1). Surcharge arithmetic pinned: +1 total AND +1 [V]-floor.
6. **Gold gate map codified** (COT-washout gate 1 with two-print confirmation, WGC/GLD gate 4, gate 3 none-by-construction kept in the /8 denominator — conservative; capitulation legs b/c substitutions) — gate-level COT reuse legitimized exactly as gates 2/3/4 reuse their legs' inputs; COT stays banned as the sentiment LEG input.
7. **Rally ≤50% cap binds post-adjustment** unless a realized trend-structure event is cited; ±10 defined as percentage points.
8. **ADR excludes holiday-abbreviated sessions** (disclosed) — prevents understated vol from licensing "likely" adjectives.
9. **Stale-input debt clock generalized** to all legs + gate 6's MA; momentum cold-start NOT-FOUND default = 1 (conservative); single-sourced streak-completing prints = PROVISIONAL (no gate credit/regime claim until corroborated).
10. **Exit-table terms defined:** local peak = campaign-relative; sustained inflow regime = the ≥5-session bar; labeled measurement corrections don't count toward the ≥6-point trim (like-for-like restatement required).
11. **Partial-tranche deployment codified:** an unlock authorizes UP TO nominal size — downsizing permitted, upsizing prohibited.

Guardrail compliance: no stop weakened (gold's ratification documents the standing parameter; the alternative — reverting to a vacuously-true `<12` — would have degraded gold's compound stop to price-only, the anti-pattern the compound design exists to prevent), no phase upsized, no failed [T] gate credited, short side untouched, all 17+41+1 held rejections preserved. Deliberately NOT touched: the Override worsening-flows veto (its time-correlation with the arming condition is ledger-flagged for the next full calibration — an untested reversal was explicitly deferred there and remains deferred). **All Layer-2 items are N=1 adjudications under the agility mandate — the next calibration must re-grade them out-of-sample exactly as it re-grades adopted tunes.**

### 2026-07-10 (second pass) — Deterministic toolchain introduced (`tools/`)

Owner directive: certainty in calculations, data fetching, and performance consistency. Added and mandated the repo toolchain — `tools/fetch.mjs` (live numeric backbone: cross-checked spot, ATH/drawdown, computed weekly Wilder RSI-14, exact 200-week SMA + gate-6 ±8% boolean, ADR sessions, F&G 3-day avg + gate-1 daily-print streaks, FRED real yield / VIX / DXY / Brent macro), `tools/compute.mjs` (band classifiers mirroring §4 letter-for-letter, ceil thresholds, per-asset rounding, EV sum-check, stop coherence, ADR with exclusions), `tools/lint-report.mjs` (validates the new mandatory ```json machine``` block — schema `report-machine/1` — after every save, before every commit; FAIL is fixed, never overridden), `tools/selftest.mjs` (regression vectors, including the ETH ceil(7/9×8)=7 misprint and the gold no-flush valuation cap). **Coupling rule: any band/threshold change to this SKILL changes `tools/lib.mjs` + `tools/selftest.mjs` in the same commit.** Scope honesty: ETF flows, on-chain, COT, and news remain live web fetches (Hard Rule 1) — the toolchain covers the computable series only. No score band, gate, threshold, phase size, or stop moved in this change.
