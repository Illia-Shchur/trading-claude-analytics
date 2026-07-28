---
name: fallen-knives-analytics
description: "Proprietary crypto market analysis framework for identifying optimal accumulation points during periods of extreme fear — and trim/exit points during euphoria. Works for ANY crypto asset (BTC, ETH, SOL, major alts, smaller alts) with asset-appropriate metric substitution. Use whenever the user asks for a Fallen Knives update/score/analytics, a buy/sell/hold assessment on a crypto asset, a fear-or-euphoria readout, accumulation-zone analysis, deployment strategy, exit planning, or any variant of 'update fallen knives [asset]'. This skill MUST fetch live data from the internet before any analysis — never rely on stale or memorized data. Output is a structured multi-section report with composite scoring, derived probability matrix, phased deployment gates, and a symmetric exit framework."
---

# Fallen Knives Analytics — Crypto Accumulation & Exit Framework

## Overview

Fallen Knives is a proprietary framework for two symmetric tasks:

1. **Accumulating** crypto exposure during periods of extreme fear, in disciplined phases, with cold-start support
2. **Trimming/exiting** that exposure during euphoria or narrative breaks

It synthesizes five scored legs — sentiment, momentum, valuation, capitulation evidence, holder behavior — into a single composite score (0–20), alongside a nine-gate confirmation board (which carries macro/catalyst direction as gate 9 and the correlation regime as a sourced gate surcharge — neither feeds the 0–20 number). The score anchors a probability matrix, drives deployment gates, and feeds a symmetric exit framework.

**The rubric is a floor for rigor, not a ceiling on judgment.** As of 2026-07-27 the framework carries an explicit **Analyst Discretion Layer** (see that section): the analyst's own read can move the score ±2, unlock a near-miss phase, and set the scenario probabilities directly — because a live tape carries structure, flow, and narrative information the five legs structurally cannot see. Discretion buys **entries** and pays for them with **stops**: every position opened on the analyst's judgment carries a hard, price-only stop, and every stop is ratcheted one-way toward price. Freedom on the way in, no forgiveness on the way out.

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

## Analyst Discretion Layer (added 2026-07-27 — owner agility mandate #2)

The five legs and nine gates are a lower bound on rigor. They cannot see order-book structure, the shape of a liquidation cascade, a policy shift the macro gate has not yet priced, a spot-vs-derivatives divergence, a narrative inflection, or a cross-asset tell — all things an analyst reading a live tape can see and argue. This layer makes that judgment a **first-class, logged, and graded input** instead of something smuggled into prose that changes no number.

**The bargain (non-negotiable):** discretion buys **entries**; it pays for them with **stops**. Discretion may never buy a looser stop, a tranche larger than nominal, a relaxed sourcing rule (Hard Rule 1 is untouched), a weaker data-integrity rule, or anything at all on the short side (Hard Rule 6).

### D1 — Discretionary score adjustment (±2)

The composite carries an explicit analyst term: **`raw = Σ legs + discretionary`**, where `discretionary ∈ [−2.0, +2.0]` in 0.5 steps. Round per the asset's convention; clamp the adjusted score to [0, 20].

> **Governing rule — D1 buys entries, never exits.** Two numbers now exist and every rule must name which it reads:
> - **Mechanical score** = `round(Σ legs)` on the asset's convention, with **no** discretionary term.
> - **Adjusted score** = `round(Σ legs + discretionary)`, clamped to [0, 20].
>
> **The adjusted score is read by deployment/unlock rules ONLY** (§6 phase score lines, the Score Interpretation table).
> **Every protective rule reads the MECHANICAL score, without exception:** the compound thesis stop's score line, the Deep-Value Override's ≥15 arming condition, every §7 trim/exit trigger (both the ≥6-point drop and the ≤3 line), the §5 EV-floor consistency check, and the Verdict-Confidence Collar's band and strong-claim unlock.
>
> Without this split, a +2 could suppress the book's own stop, arm the Override, dissolve an inconsistency flag, or license a strong claim — discretion buying itself protection instead of paying for it. Print both numbers in every report.

- **To apply a non-zero value:** (a) name **≥2 specific, sourced factors the legs structurally cannot capture** — re-weighting a factor a leg already scores is double-counting and is prohibited; (b) state direction and size; (c) name a **falsifier** — the observation that retires the adjustment; (d) log it in the Discretion Ledger and in the machine block (`score.discretionary`).
- **Symmetry:** this is not a bull tool. Print the value every report even when 0, and when a *negative* adjustment was considered and declined, say so in one line.
- **Decay:** an adjustment held at the same sign and size for **>3 consecutive reports** must be re-argued from fresh evidence or retired to 0. Stale conviction is not conviction.
- **Unlock limits:** the adjustment may lift the score across **at most one** phase-unlock threshold per report, and may **never** be the sole enabler of Phase 3 — the 45% tranche requires mechanical score ≥17 on the legs alone. Any unlock it enables is a **discretionary unlock** → the D5 stop applies to that tranche.
- **Prohibited uses (all follow from the governing rule):** satisfying the §5 EV-floor consistency check, manufacturing or dissolving a Hard Rule 5 cross-validation state (if discretion is what pushes FK ≥12 against FR ≥12, strip it and re-read), clearing the Deep-Value Override's ≥15 arming condition, moving any stop or trim trigger, or lifting the report out of the Verdict-Confidence Collar band / over its strong-claim bar. Two loosenings never stack, and discretion never touches the exit side.

### D2 — Analyst Conviction Path (near-miss unlock)

When a phase's **score** condition is met but the **gate count falls short by exactly 1**, the analyst may unlock it on a written conviction case:

- The phase's **[V] floor must still be met on actually-lit gates** — this path substitutes for a gate, never for the [V] floor, and never for two gates.
- The case cites **≥3 live, sourced data points**, names **which dark gate it substitutes for** and that gate's relight path, and states a **dated falsifier** (what, by when, retires the thesis).
- Available for **Phases 1A / 1B / 2 only. Never Phase 3.**
- **Unavailable while the risk-on correlation surcharge is live** (sourced 30d corr >0.7). That surcharge exists to demand *more* confirmation when a fear signal is really equity beta; letting a written case supply the exact +1 gate it adds would cancel it silently. When corr >0.7, gates are earned or the phase stays shut.
- **Size: half nominal**, laddered across the zone; the remainder waits for the gate to actually light.
- **Frequency:** at most one conviction-path unlock per report; a phase may not be re-unlocked through an **analyst** channel (D1 or D2) within **10 calendar days** of a D5 stop-out in that phase.
- The D5 stop applies.

### D3 — Analyst Read (mandatory report section)

Every report carries a free-form **Analyst Read** (report §9) — the analyst's own argument in their own structure. See that section for what it must cover.

### D4 — Discretionary probability layer

The §5 grid is now an **anchor, not a constraint**. The analyst may set the four scenario probabilities directly from their own read of the tape rather than deriving them from the score row. What still binds, without exception:

- Probabilities sum to **100%**.
- **Rally ≤50%** post-adjustment unless a realized trend-structure event is cited on the same line.
- EV is recomputed **from the printed cells** as the final step — never adjust cells to reach a preferred EV.
- EV-vs-spot is printed next to the **realized trailing-2-week price change**.
- The EV-floor consistency check and the terminal-vs-extreme honesty requirement still bind.
- Any cell deviating **>10 percentage points** from the score-derived baseline row carries a one-line reason.
- **The §5 trend-residual state must still be stated as a boolean regardless of how the cells were set** — "active downtrend (below major MA AND making lower lows): YES/NO", with its direction. Two downstream rules key off it and would otherwise be silently orphaned: the Deep-Value Override's **quarter-size throttle** and the terminal-vs-extreme reconciliation. Setting cells by hand never deletes a sizing guardrail.

### D5 — The Discretion Tax (hard stop on every analyst-channel tranche)

Any tranche opened through an **analyst** channel — a D2 conviction path, or a D1 discretion-enabled threshold cross (one that the mechanical score alone would not have unlocked) — carries a **hard, price-only stop stated at fill**:

> **The Deep-Value Override is NOT a discretionary channel and does NOT take the D5 stop.** It is a fully mechanical rule with its own trigger, veto, throttle, and sub-cap, protected by the 2026-06-11 calibration and explicitly deferred from re-tuning. Attaching a price-only stop to Override fills would re-create the exact anti-pattern the Stop Philosophy documents — the Override arms only at score ≥15 with F&G ≤15, i.e. at maximum fear, which is precisely where a price stop sells the bottom (Jun 6 2026 came ~1.9% from doing so). Override tranches keep the compound stop. D5 taxes *analyst* judgment, not the framework's own deep-value machinery.

- **Line** = the structural invalidation the thesis rests on (the swing low / level named in the case), and **in no case more than 15% below the fill**.
- **Fires on a single daily close below the line.** No score condition. No second-close requirement. No compound-stop patience — gate-earned tranches earned that patience; discretionary ones did not.
- It is a **per-tranche** stop and may legitimately sit **above** deeper planned buy zones. That is intended, not a coherence violation: it is **excluded from the §6 stop-vs-buy-zone coherence check**, which tests the catastrophic tier only.
- **On a hit:** exit that tranche in full, return the capital to dry powder, log the realized loss in the Discretion Ledger, and bar **the analyst channels (D1 and D2)** for that phase for **10 calendar days**. *(Scope pinned 2026-07-27: the bar is on analyst judgment, so the mechanical Deep-Value Override may still fire into that phase inside the window — it has its own trigger, veto, and 5-day throttle, and a mechanical rule is not disciplined for an analyst's stop-out.)*
- The catastrophic floor and the §7 narrative-break exit continue to govern the whole book independently.
- **Non-mechanical capital cap:** capital deployed through **all channels other than a plain score-plus-gate-count unlock — D1 crosses, D2 conviction paths, and Override firings — may not exceed 40% of the book** until a [T] gate relights or a confirmed higher-low prints. The Deep-Value Override keeps its own tighter **≤25% sub-cap, counted inside that 40%** (unchanged, not additive to it). *(The Override is mechanical for **stop** purposes — it keeps the compound stop and is exempt from D5 — and non-mechanical for **capital-cap** purposes. Two different questions, two different answers; do not collapse them.)*
- **Machine-block encoding (mandatory, so the caps cannot fail open):** every tranche that is not a plain score-plus-gate unlock is written **`discretionary: true`** with its `channel` (`"D1"` / `"D2"` / `"override"`). **Override fills are `discretionary: true, channel: "override"`** — the flag means "counts toward the caps," while the D5 stop and the Phase-3 bar key off `channel` and skip `"override"`. Writing an Override fill as `discretionary: false` would drop its capital out of both caps silently; the linter therefore counts any tranche whose `channel` is `"override"` toward the caps regardless of the flag, and flags the inconsistency.

### D6 — Ratchet rule (stops never widen)

Every stop parameter is **monotonic toward price**: the catastrophic floor may only rise, the compound score line may only rise (a higher line fires more readily), checkpoint dates may only move earlier, discretionary stop lines may only rise. **A change that makes any stop less likely to fire is prohibited — not merely disclosable.**

**Three narrow exceptions, and no others:**
1. **Named-zone re-anchor.** The catastrophic floor may be re-anchored *downward* when activating a deeper buy zone that was **named in a prior report in this asset's series** — executed atomically (stop re-set before the first fill), logged in the Migration Ledger citing the prior report that named the zone. A re-anchor to an unnamed zone is a publishing FAIL.
2. **First-time calibration is not a migration.** Setting a parameter for the first time in a **campaign** — including the per-asset compound score line for a structurally-pinned-score asset, which must be re-set below that asset's realized score range at stop-set time — is an initial calibration, not a widening. Once set, it ratchets for the life of that campaign. *(Campaign boundary pinned 2026-07-27: a new campaign begins only at a genuine cold start — a flat book after a full exit or stop-out, the same reset the §7 local-peak rule uses. A partial trim, a frozen phase, or an un-filled deeper zone does **not** start a new campaign, so the ratchet cannot be reset by trimming into it.)*
3. **Calendar corrections are not migrations.** The calendar-lock rule may move a checkpoint *later* within its week when the venue calendar dictates. A correction to a real trading session is a validity fix; the ratchet governs only *discretionary* date changes, which may move earlier only.
- This supersedes the Migration Ledger's disclosure-only treatment of loosening. The Ledger still records every change; the ratchet decides which changes are permitted at all.

### D7 — Discretion Ledger and grading

Every report carries a **Discretion Ledger** table (inside §9): date · channel (D1/D2/D4) · the call · size · the stop line · the falsifier · status (live / retired / stopped / vindicated) · realized P&L when closed. Carry open entries forward across reports.

Every discretionary call is a **testable prediction**. The Ledger is the primary evidence the next calibration grades, exactly as it grades adopted tunes — a channel that grades badly gets tightened or withdrawn. Discretion is licensed on the condition that it is scored.

### Form is free, substance is mandatory

The framework's rules divide into two classes, and only one is a template.

**Substance-mandatory (unchanged, never relaxed by discretion):** live-data sourcing with source + timestamp (Hard Rule 1); the metric-history continuity, provenance-citation, and single-source-streak rules; canonical-spot reconciliation; probabilities summing to 100 and the EV recomputation; the Rally cap; the machine block and a clean `lint-report.mjs` run; every stop rule including D5/D6; the computed companion FR score.

**Form-free (write it your own way):** section order and grouping, narration templates, the exact sentences prescribed for checkpoint prognosis, terminal-vs-extreme reconciliation, gate reachability, and vacuity labeling. These rules specify **what must be true and disclosed**, not the words that disclose it. Cover the substance in whatever prose serves the argument; do not pad a report with boilerplate to satisfy a form that no longer binds.

## Report Structure

The order below is the **recommended default, not a fixed template**. Reorder, merge, split, or add sections when it makes the argument clearer, and drop sections that are genuinely inapplicable (say which and why). What may never be dropped: the live-data section (§2), the composite score (§4), the probability/EV layer (§5 — the machine block and linter both require it), the deployment + stop state (§6), the exit/trim status (§7 — Hard Rule 2 makes it first-class every report), the watchlist (§8 — it carries the mandatory tier-1 calendar enumeration), the Analyst Read (§9), and the closing machine block. Asset name appears in every section header.

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

Round to nearest integer. **Adjusted score = round(Σ legs + the D1 discretionary term) — no multiplicative modifier remains (Jun 2026); the [V]-gate surcharge changes gate requirements only, never the score number.** The D1 term is the framework's only additive analyst input: state it every report (0 counts), with its rationale, falsifier, and Ledger entry per the Analyst Discretion Layer. Print the leg sum, **the mechanical score** (`round(Σ legs)` — the number five protective rules read, so it is never left implicit), the D1 term, the raw composite, any [V]-gate surcharge, and the adjusted score as separate numbers so the mechanical and discretionary halves stay auditable. Half-point ties follow the established per-asset convention (adjudicated in the 2026-07-04 calibration — see the "round-down-at-.5" rejection): BTC and Gold round .5 **up**; ETH rounds .5 **down** (estimate-heavy input set → conservative on a buy signal). For a new asset, declare a convention with a one-line rationale at its first .5 occurrence and hold it constant for the series. State raw score, any [V]-gate surcharge applied, and adjusted score. *(The Flying Rocket ×1.05 decoupled modifier is demoted symmetrically — see that skill's revision log.)*

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

**Gate reachability disclosure (Jul 2026; form-freed 2026-07-27, documentation-only).** Every dark gate — a *dark gate* is any gate not counting toward the unlock, i.e. marked ❌ or ⚠️ — needs a **concrete relight path**, re-derived each report (never carried over from a prior report). The substance is mandatory; the presentation is not: annotate per gate, group gates that share a path, or fold it into prose — whatever reads best. Reserve the tag "none-in-regime" ONLY for a gate that is structurally unreachable without a large, slow-moving change (e.g., gate 6: price 31%+ below the long-horizon mean) — forbidden to tag any gate "none-in-regime" while it is ⚠️ or was within its trigger band in the trailing window (= since the prior report in this asset's series). *(Sole exception: a gate that is permanently un-creditable by construction — currently only gate 3 under the gold low-vol adaptation — may carry "none-by-construction" regardless of its ✅/⚠️/❌ mark.)* This disclosure is informational only; a "none-in-regime" finding is NOT evidence a gate is mis-specified and may NOT be cited to lower a threshold, reduce the denominator, or credit a gate — the default conclusion remains that dark gates are correctly dark. No aggregate "effective board X/N" denominator, no "requires perfection" framing, and no threshold/score/unlock change may accompany this disclosure. Any threshold change requires a separate, independently-graded tune.

### 5. Probability Matrix — Score-Anchored, Analyst-Set

Use this baseline grid (**re-flattened Jun 2026** — see below) as the **anchor**, then either adjust each cell by up to ±10 **percentage points** for idiosyncratic catalysts, or — under **D4 of the Analyst Discretion Layer** — set the cells directly from your own read of the tape. Either way, state every deviation, note the reason for any cell >10 points off the baseline row, and re-normalize to a 100% sum:

| Adj. Score | Rally | Range | Retest | Bear |
|---|---|---|---|---|
| 0–5 | 10% | 30% | 35% | 25% |
| 6–10 | 20% | 35% | 30% | 15% |
| 11–14 | 30% | 35% | 22% | 13% |
| 15–17 | **38%** | **33%** | **19%** | **10%** |
| 18–20 | **50%** | **28%** | **14%** | **8%** |

> **Why flattened:** the legacy grid put Rally at **50% (scores 15–17) / 65% (18–20)** — so deepening fear *mechanically manufactured* a majority rally weight, and because **EV = Σ(probability × midpoint)** with targets anchored above a falling spot, EV came out **positive by construction at maximum fear**. In May–Jun 2026 this produced ~5 of 7 BTC EV reads pointing up while price fell every time, Rally staying *modal* on Jun 6 and Jun 10 at the cycle's deepest fear. The grid is a **fear map, not a direction forecast** — Rally is now capped at ≤50% — never a *majority* weight — at every tier (it may still be the single largest cell, as at 15–17 and 18–20; what the flattening removed is the manufactured >50% rally mass). This cap binds POST-adjustment as well: after the ±-point adjustments and the trend residual, Rally may not exceed 50% at any tier unless a realized trend-structure event (major-MA weekly reclaim / confirmed higher-low — the collar's strong-claim unlock) is cited on the same line.

**Trend/regime modifier (residual, Jun 2026).** With the grid flattened, the trend term is now a small residual to avoid double-counting: when price is **below a major MA AND making lower lows** (active downtrend), shift a further small residual — **at most 5–7 percentage points of mass from Rally → Retest + Bear** and widen downside target ranges; apply the mirror toward Rally when the trend repairs (major-MA reclaim / confirmed higher-low). State the shift. (See the gate-6 ⇄ §5 cross-reference in §4.)

**EV-floor consistency check (Jun 2026).** After computing EV, if **EV-vs-spot is negative while the MECHANICAL score ≥15 AND 3-day F&G ≤15** *(mechanical, pinned 2026-07-27 — a −0.5 discretionary term must not be able to dissolve an inconsistency flag instead of triggering the re-examination)*, flag it as an internal inconsistency and re-examine inputs — a genuine deep-value, extreme-fear zone should not simultaneously show a *negative* accumulation edge (that pattern usually means the targets or the trend shift were set too pessimistically, and it would perversely fight the Deep-Value Override). Do not deploy *or* refuse to deploy on a flagged-inconsistent EV; resolve it first.

**Terminal-vs-extreme reconciliation (Jul 2026; form-freed 2026-07-27).** Whenever the §5 trend residual is live (active downtrend) AND the modal scenario is Range/base-building, the report must make explicit — in whatever wording you choose — that the modal band expresses the **terminal** expectation while the expected **path extreme** sits in a stated direction-matched band, and that base-building labels do not claim the low is in. The reconciliation must point to whichever combined direction (Rally vs Retest+Bear) matches the sign of the live §5 trend-residual shift, not merely the second-highest-probability scenario; in a near-tie (within 2 percentage points), name both adjacent bands or default to whichever is more bearish/further from spot. The words "base-building"/"floor" may not appear on the modal row without this line.

**Mandatory EV recomputation/sum-check (Jul 2026).** Probabilities must sum to 100%. Self-check: recompute the Weighted EV from the printed cells as the final step, flowing from the printed probability/midpoint cells to EV only (never the reverse — never adjust cells to hit a preferred EV); if the stated EV differs from the recomputation by >0.5% (measured relative to the recomputed EV), correct before publishing. Show the component sum.

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value** = Σ (probability × midpoint of target range). State EV explicitly, and EV-vs-spot %. **Disclose the realized trailing-2-week price change next to the EV claim** — a positive EV printed during a −X% two-week move must say so, so the reader sees the EV contradicts realized momentum.

### 6. Deployment Strategy

Splits: **10 / 15 / 30 / 45** (front-loaded pyramid — bigger tranches at deeper drawdowns, where reward-to-risk is highest). State **total dry powder %** prominently.

**Partial-tranche deployment (Jul 2026, codifies the BTC Jul-9 practice):** an unlocked phase authorizes UP TO its nominal size; deploying a stated fraction (e.g. a half-size 1B) laddered within the zone is permitted, with the remainder staying assigned to that phase and deployable in the same zone without a fresh unlock. Upsizing beyond nominal is prohibited. (Override firings keep their own half/quarter-size rules.)

**⚑ Deep-Value Override (knife-deepening rule — added Jun 2026, recalibrated Jun 2026).** The pyramid's whole purpose is *bigger tranches at deeper drawdowns*. In May–Jun 2026 the gate system did the opposite: BTC deployed only its smallest 10% tranche at ~$65K, then was locked out of the 15/30/45 tranches as price fell to $59K and score *rose* to 16 at F&G 9 — maximum signal, zero incremental deployment. The override defeats that inversion. The **next** tranche unlocks **regardless of [T] gate count** when ALL of the following hold (the Override presupposes at least one deployed tranche — it unlocks the tranche AFTER the most-recently-deployed one and can never unlock Phase 1A; with zero tranches deployed it is N/A):

- **Mechanical** score ≥15 — the leg sum without the D1 discretionary term (pinned 2026-07-27 so analyst discretion can never arm the Override), **AND**
- **Price condition (reachability-fixed):** the trailing-period **low or a daily close** is **≥8% below the blended cost of the most-recently-deployed tranche** AND a **fresh lower-low** has printed since that tranche. **Definitions (Jul 2026):** *trailing-period* = the window since the most-recently-deployed tranche's fill. *Fresh lower-low* = a daily low breaking below the most recent prior swing low in daily structure since that fill — a structure break, NOT a break of the cycle low (the cycle-low reading would restore the unreachability the Jun-2026 re-anchor removed — the exact ambiguity that left the Jun-24 evaluation "borderline"; the swing-low reading still refuses shallow-dip chop). *(The original "spot ≥10% below basis" was evaluated only at report-time spot — in Jun 2026 the deepest print was −9.1% off the $65K basis, so the 10% trigger NEVER fired despite score 16 / F&G 9; it shipped decorative. Trailing-low + 8% makes it actually reachable without firing on a shallow dip.)* **AND**
- 3-day avg sentiment in the extreme band (F&G ≤15 or asset-equivalent), **AND**
- **Worsening-flows veto is OFF:** do NOT fire if ETF outflows are re-accelerating AND the major MA broke within the last 5 sessions (don't add into an actively worsening institutional exodus), **AND**
- No §7 narrative-break trigger active.

**Sizing + throttle (prevents chain-runaway):**
- Default fire size = **half** the tranche's nominal size (remaining half waits for [T] confirmation or a still-deeper *pre-named* zone).
- **While the §5 active-downtrend shift is live, fire at quarter-size, not half** — the override (deploy more) and the trend modifier (expect lower) must be wired together, not pull against each other unthrottled.
- **At most one override unlock per report / per 5 calendar days**, and **override-deployed capital may not exceed 25% of the book** until at least one [T] gate relights OR a confirmed higher-low prints. *(Unthrottled, repeated 8–10% steps could otherwise walk the book to ~45% deployed through a single uninterrupted −27% cascade with zero trend confirmation — a softer version of the knife-catching the framework exists to avoid.)*
- The override governs **deployment only** and is **independent of the stop** (§ Stop Philosophy) — the two must never be evaluated off the same trigger. *(Reaffirmed 2026-07-27: the Override is mechanical, not an analyst channel — it does **not** take the D5 price-only stop, and its trigger, veto, and throttle are unchanged and remain deferred to a full calibration. Its capital does count inside the 40% non-mechanical cap.)*

Log every override firing (and every blocked-by-veto/throttle near-fire) in the report.

For each phase show: capital share, trigger zone, gates required, current status.

**Cold start:** if no prior phases are deployed, every phase below begins as `DRY POWDER` with its gate conditions. Do not assume continuity.

#### Phase 1A — Initial Entry (10%)
- **Unlock gates:** adjusted score **≥8** *(cut from ≥10, 2026-07-27 — the 10 bar kept the book fully dry through fear legs that later graded as entries)* AND ≥3 of 9 confirmation gates ✅ — or `ceil(1/3 × active denominator)` when any gate is N/A — (with ≥2 from the **[V]** bucket) — **OR the D2 Analyst Conviction Path** (gate count short by exactly 1, [V] floor met, half nominal size, D5 stop)
- **Entry zone:** ASSET-SPECIFIC. State price range. **Ladder across the FULL zone — never deploy at the top of it** (May 31 2026 instructed a top-of-zone entry at $70–73K; the better fill came from laddering into $64–67K days later).
- **Stop:** governed by **Stop Philosophy** below (the CATASTROPHIC stop is the line placed *below the deepest planned buy zone*, per the placement rule; the compound thesis stop is a separate price-AND-mechanical-score line and may sit at or inside deeper zones by design — it cannot fire on price alone; run the mandatory stop-vs-buy-zone coherence check). **If this tranche was unlocked through an analyst channel (D1 cross or D2 conviction path), it additionally carries the D5 hard price-only stop, which *can* fire on price alone — on a single daily close.**
- **Status:** DRY POWDER / DEPLOYED ([entry avg]) / STOPPED OUT

#### Phase 1B — Building (15%)
- **Unlock gates:** adjusted score **≥11** *(cut from ≥13, 2026-07-27)* AND **[ (≥5 of 9 gates ✅ — or `ceil(5/9 × active denominator)` when any gate is N/A — with ≥3 from the **[V]** bucket) OR (Deep-Value Override fires) OR (D2 Analyst Conviction Path — gate count short by exactly 1, [V] floor met, half nominal, D5 stop) ]** AND Phase 1A entered *(bracketing mirrors Phase 3: the [V]-floor binds only to the gate-count branch, never to the Override branch)*
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 2 — Conviction (30%)
- **Unlock gates:** adjusted score ≥15 *(score line unchanged 2026-07-27 — but note both analyst channels do reach this tranche: a D1 term can carry the score across ≥15 and a D2 case can substitute the sixth gate. Any such fill is discretionary and takes the D5 stop.)* AND **[ (≥6 of 9 gates ✅ — or `ceil(2/3 × active denominator)` when any gate is N/A — with ≥3 from the **[V]** bucket) OR (Deep-Value Override fires) OR (D2 Analyst Conviction Path — gate count short by exactly 1, [V] floor met, half nominal, D5 stop) ]** AND correlation regime not "risk-on extreme" (corr <0.8). **Corr input rule (Jul 2026):** when Phase 2 is otherwise unlocked, sourcing/computing the 30d correlation becomes mandatory (it is Required Data Fetch #8); if a documented attempt genuinely fails, the corr condition defaults to PASS (mirror of the surcharge's "defaults OFF — never penalize on a guess"), with the mandatory line "corr unverified — risk-on-extreme untested." An unattempted fetch blocks Phase-2 deployment (Hard Rule 1). *(The "macro neutral-to-positive" condition is now a **[T]** sizing/timing input, not a hard veto — it structurally turns off in every fear spike, which is when this tranche is supposed to fire.)*
- **Entry zone:** [range]
- **Status:** DRY POWDER / LIVE / FROZEN

#### Phase 3 — Generational (45%)
*(No **analyst** channel reaches this tranche: the D2 Conviction Path is barred from Phase 3, and the D1 term may never be its sole enabler — mechanical score ≥17 on the legs alone. The pre-existing Deep-Value-Override-plus-capitulation-candle branch below is **mechanical** and survives untouched; an Override-fired Phase 3 keeps the compound stop, not the D5 stop. The 45% tranche stays fully earned.)*
- **Unlock gates:** **mechanical** score ≥17 — the leg sum alone, since no analyst channel reaches this tranche (pinned 2026-07-27; every other phase reads the adjusted score) — AND **[ (≥7 of 9 gates ✅ — or `ceil(7/9 × active denominator)` for PoS assets where gate 5 is N/A — with ≥4 from the [V] bucket) OR (Deep-Value Override fires AND a weekly capitulation candle has printed) ]** AND LTH selling has collapsed (or alt equivalent). *(Parentheses are load-bearing: the capitulation-candle requirement binds ONLY to the override branch, never to the normal gate path — this is the generational 45% tranche, the most expensive place for an operator-precedence ambiguity. Sustained ETF inflows ≥5 sessions are a **[T]** confirmation — strong when present, but this tranche may fire on a capitulation candle into max fear rather than waiting for inflows that only appear after the recovery begins.)*
- **Entry zone:** [range — typically requires capitulation candle on weekly]
- **Status:** DRY POWDER / LIVE / FROZEN

**Stop Philosophy (revised Jun 2026).** A fear-accumulation strategy adds *into* lower prices; a hard per-tranche price stop that sits **above** a deeper planned buy zone is self-contradictory — it ejects you before your own Phase 1B/2/3 entries. In Jun 2026 the BTC Phase 1A stop ($58K) sat *inside* the Phase 1B zone ($53–58K), and the Jun 6 low of $59,110 came within ~1.9% of stopping the position out **at maximum fear** (score 16, F&G 9) — selling the exact bottom the framework exists to buy. Gold already shipped the coherent design; crypto now inherits it:

- **Two stop regimes (2026-07-27).** Tranches that unlocked **mechanically** — score + gate count, *and Deep-Value Override firings, which are mechanical* — keep the forgiving **compound** stop below. Tranches opened through an **analyst channel** (D1 threshold cross, D2 conviction path) carry the **hard, price-only D5 stop** — a single daily close below a line no more than 15% under the fill, no score condition. This is the price of the looser entry, and it is not negotiable at fill time. **All stop parameters are additionally bound by the D6 ratchet — they may only move toward price.**
- **Placement rule (mandatory):** the catastrophic stop sits **strictly below the deepest defined buy zone**. If the deepest zone is open-ended, anchor the stop a defined margin below the deepest *named* price reference. There is **no "no-stop" option** — every deployed position carries a stop placed below the ladder (an un-stopped position alongside an override that adds into weakness is how books blow up).
- **Compound thesis stop (price AND score):** invalidation fires only on a **sustained weekly close below the structural floor (≥2 consecutive weekly closes) AND the MECHANICAL composite score back below the asset's named score line (default 12)**. **The score condition reads the mechanical leg sum, never the D1-adjusted score (pinned 2026-07-27)** — otherwise a mechanical 10 plus a +2 discretionary term would hold the condition false and suppress the whole book's stop, letting discretion buy itself protection. This is the governing rule's single most important application. **Score-line calibration (Jul 2026):** for an adapted asset whose composite is structurally pinned below 12 — which would make the score condition vacuous and degrade this stop to price-only, the exact failure the compound design exists to prevent — the line must be re-set at stop-set time to sit meaningfully below the asset's realized score range, via an explicit Stop Migration line. (Gold runs <8, set 2026-06-17 and ratified here retroactively — the original change shipped before the Migration Ledger existed; BTC/ETH remain at 12.) A pure-price line that would have fired at score 16 / F&G 9 is self-contradictory; requiring the score to *also* have rolled over means the stop fires only when fear AND value have genuinely deteriorated — it can never fire *more* often than a price-only stop, only less. (Would NOT have fired Jun 6, correctly keeping the holder in the position that recovered.)
- **Mandatory time stop:** every tranche also carries a max-hold/decay limit; an accumulation thesis that hasn't worked after a stated horizon is reassessed, not held indefinitely.
- **§7 narrative-break exit is independent** — the 100%-exit on a broken thesis stands on its own, separate from the price/score stop.
- **Stop-vs-buy-zone coherence check (mandatory, every report — long side only, re-scoped Jul 2026):** evaluate against the deepest buy zone or ladder named ANYWHERE in the report — any Phase 1A/1B/2/3 row carrying an explicit price/entry-zone, or any prose labeled a "prospective ladder"/"future ladder" (excludes probability-matrix scenario bands, which are forecast ranges, not buy commitments). If a Phase row's price is omitted in a given report, fall back to the most recent report's named price for that phase. If multiple prospective zones are named with different floors, use the single LOWEST floor. Frozen deployment does NOT pass by construction — test against the deepest prospective ladder floor. Print the two numbers and the boolean — *"CATASTROPHIC stop [X] strictly below deepest active buy-zone floor [Y]? PASS/FAIL."* (The check tests the catastrophic tier — the placement rule's subject. The compound thesis line is not the tested number; it may legitimately sit inside deeper zones because it cannot fire on price alone. **D5 discretionary-tranche stops are likewise excluded** — they are per-tranche invalidation lines that are *meant* to sit above deeper zones and to eject that tranche while the ladder below stays live; print them separately and never let one substitute for the catastrophic floor.) If any prospective ladder floor sits at or below the current stop, print the paired post-activation stop level and the atomic activation sequence (re-set stop to [Z] BEFORE the first fill) and run the boolean in both states; if post-activation genuinely fails, the report may still publish but must carry an explicit "stop realignment owed" flag and cannot authorize new deployment until resolved. *(Superseded 2026-07-27: this check formerly allowed "widening or removing a stop while the Override is armed, on disclosure of the max-drawdown-to-thesis-stop figure." The **D6 ratchet prohibits widening outright** and the placement rule already bars removal, so that hatch is closed. State the max-drawdown-to-thesis-stop figure anyway whenever the Override is armed — it is useful disclosure — but it no longer purchases a loosening.)* *(This check is FK-only; Flying Rocket's stop-above-entry is correct by design — do not port it there, per Hard Rule 6.)*
- **Stop Migration Ledger (Jul 2026).** Any change to any stop parameter (price line, score condition, checkpoint date, catastrophic anchor) prints a Stop Migration line: old → new, direction (toward/away from price), which TIER changed (compound-line vs catastrophic-floor vs score-condition vs checkpoint date — named explicitly), and one-line rationale. Each parameter that changes value gets its own line even when multiple move in the same report. This applies to every migration regardless of direction — "silent" is defined as any un-annotated numeric change to a stop parameter. Silent migrations are a publishing FAIL. **Superseded in part 2026-07-27:** the Ledger still *records* every change, but the **D6 ratchet** now decides which changes are *permitted at all* — a migration away from price is prohibited outright (not merely disclosable), save D6's **three** named exceptions — the named-zone catastrophic re-anchor, a first-time parameter calibration, and a calendar-validity correction. A loosening that reverses a prior tightening is no longer a disclosable event; it is a rejected one.
- **Calendar-lock on dated stop checkpoints (Jul 2026).** Every dated stop checkpoint must be validated against the venue trading calendar at write time: print the exact session date, close time, and any holiday/abbreviated-session note. If a checkpoint date resolves to a non-trading day, restate it to the nearest ACTUAL trading session's close for that same calendar week using the venue's real calendar (direction — earlier or later within the week — follows whatever the calendar dictates, never a fixed backward-only rule), and disclose the correction explicitly. Tiebreak (the single named exception to "direction follows the calendar"): a checkpoint naming a non-trading day immediately preceding a multi-day holiday weekend defaults to the last close BEFORE that non-trading day.
- **Checkpoint prognosis discipline (Jul 2026, addendum to Principle 4 / Verdict-Confidence Collar).** A stop-checkpoint prognosis is a directional forward claim subject to the forward-claim hedge rule (Principle 4). **Form-freed 2026-07-27:** the fixed template below is now a **content checklist, not a sentence to copy** — cover every element in your own prose, in any order. Required content: "Checkpoint [weekday-verified date, cross-checked against a named calendar source — computed BEFORE any distance/likelihood language]: fires iff [condition]; spot is [X]% [above/below] the line ([Y]× the 5-day average daily range, computed as mean of |daily high − daily low| over the last 5 FULL trading sessions from the same canonical spot source used for the report's price reconciliation — holiday-abbreviated sessions are EXCLUDED and the lookback extended to reach 5 full sessions, with the exclusion disclosed inline; an abbreviated session shrinks the ADR and flatters the distance ratio); next tier-1 release before the checkpoint: [event/none]." A likelihood adjective ("likely"/"probable") is permitted ONLY when traced to a named quantity (the stated distance-vs-ADR ratio and/or a specific just-printed data point with direction and magnitude) AND no unpriced tier-1 release sits between report and checkpoint. Additionally, every report's Watchlist/Critical Watchlist section must explicitly check the calendar for tier-1 US macro releases (payrolls/CPI/FOMC/PCE) landing between the report date and the next stop checkpoint, and flag if the pending checkpoint has NOT priced one in; when a tier-1 release lands before the checkpoint, the prognosis must explicitly name that release and its expected direction of effect (up/down/neutral) as part of the falsifier — omitting it is a violation of the hedge rule.

**Dry powder yield benchmark:** state assumed opportunity cost (current T-bill yield or USDC/sDAI yield). Cash is a position; idle cash has a measurable cost.

#### Ledger tag (print on every tranche that fills)

When a report authorizes a fill, print the **ledger tag** for that tranche alongside the status line. The tag is what the personal-accounting ledger records against the round-trip deal, and it is the *only* thing that connects a real Binance position back to the tranche that authorized it — the ledger stores quantity and cost basis, but `crypto_trade` has no tranche dimension and never will. An untagged holding is a real position with unknown attribution; it cannot resolve a phase-dependent unlock precondition.

| Tranche | Tag |
|---|---|
| Phase 1A / 1B / 2 / 3 | `FK-P1A` · `FK-P1B` · `FK-P2` · `FK-P3` |
| Deep-Value Override firing | `FK-OVR` |
| D1 threshold cross / D2 conviction path | `FK-D1` · `FK-D2` — these carry the D5 stop |
| Traded outside the framework | `UNFRAMED` |

Apply it in the app via `PUT /api/investments/deal-note` with the `dealKey`, the tag, and a note whose **first line is `report=reports/<this report's filename>`** and whose remaining lines state the stop, the clock, and the sizing rationale. Per-phase performance then reads back as `GET /api/investments/deals/stats?tag=FK-P1A`, and the whole framework as `?tagPrefix=FK-`. Tagging is manual and deliberately so: inferring a phase from quantity or timing is a guess, and a guessed phase can unlock the next tranche.

### 7. Exit / Trim Framework (Symmetric)

The framework de-risks as conviction signals invert. Track cost basis per phase. Trims execute against most-recently-deployed tranches first (LIFO).

**Every score condition in this table reads the MECHANICAL score** (leg sum, no D1 term) — per the governing rule, discretion buys entries and never touches an exit. This covers both the ≥6-point-drop row and the ≤3 row.

| Trigger | Action | Rationale |
|---|---|---|
| **Mechanical** score drops ≥6 points from local peak — *local peak* = the highest **mechanical** score printed since the current campaign's first fill (a cold start resets it). **Both ends of this comparison read the mechanical leg sum, excluding the D1 discretionary term (2026-07-27)** — mixing an adjusted peak against a mechanical reading would let a retired +2 supply a third of the drop, firing a 25% trim on a 4-point decline. Labeled measurement/input-honesty corrections do NOT count toward the drop: restate the peak on corrected inputs and compare like-for-like (carve-out valid only when the correction was labeled in the report where it was taken) | Trim 25% | Signal exhaustion in progress |
| F&G ≥75 sustained 7d **AND** weekly RSI >70 | Trim 25% | Sentiment + momentum euphoria |
| MVRV-Z >3 (BTC/ETH) or drawdown from ATH <10% with vertical 30d return | Trim 50% | Valuation extreme |
| Mechanical score ≤3 **AND** price ≥40% above blended cost | Trim 25% | Score cycle complete |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime — *sustained inflow regime* = the framework's own ≥5-consecutive-green-session flow-flip bar was met during the held position's life | Trim 25% | Institutional conviction breaking |
| Narrative break (regulatory ban in major jurisdiction, founder fraud, critical security breach, irreparable tokenomics change) | **Exit 100%** | Thesis voided — price is downstream of narrative |

State current exit-trigger status (none / partial trim executed / full exit) and remaining position size.

### 8. Critical Watchlist

| Time (EST) | Event | Asset Impact |
|---|---|---|

Include macro releases, ETF deadlines, asset-specific events (unlocks, forks, governance votes), geopolitical milestones.

### 9. Analyst Read — Discretionary Layer (mandatory, free-form)

**This section is the analyst's own voice, not the rubric's.** Write it in whatever structure serves the argument. It exists because the composite is a coincident fear gauge built from five legs, and the person reading the tape sees more than five things. What it must contain:

1. **The read.** What is actually happening in this market right now, in your judgment — the thesis you would defend to a skeptical partner. Argue it; do not summarize the tables above.
2. **What the rubric is missing.** Name the specific factors the legs and gates structurally cannot see this cycle (structure, positioning, flow mechanics, policy, cross-asset, narrative). Say whether each cuts bullish or bearish.
3. **The D1 term.** State the discretionary adjustment (**including 0**), its direction, its size, the ≥2 sourced factors behind it, and its falsifier. If a negative adjustment was considered and declined, say so.
4. **Discretionary actions taken or declined.** Any D2 conviction-path unlock (with the substituted gate and its relight path), any D4 deviation from the baseline probability row, and — just as important — any discretionary action you *considered and declined*, with the reason. Near-misses are evidence too.
5. **The Discretion Ledger (D7).** The running table: date · channel · call · size · stop line · falsifier · status · realized P&L. Carry open entries forward; close out resolved ones honestly, including the ones that lost.
6. **What would change your mind.** A concrete, dated falsifier for the overall read — not a hedge, a trigger.

The Verdict-Confidence Collar applies here in full: this section may argue hard, but a forward or regime-resolution claim still carries a probability **or** an `IF→THEN` plus a named falsifier. Conviction is licensed; unfalsifiable confidence is not.

### 10. Bull vs Bear Scorecard

Numbered bull signals (✅) and bear signals (❌) with one-line rationale each. Count both. State net direction and magnitude.

### 11. Change Log

If a prior report exists for this asset, show what changed:

| Factor | Previous | Current | Direction |
|---|---|---|---|

If cold start, state "first report — no prior comparison."

### 12. Strategic Verdict

- Restate: adjusted score, weighted EV, EV-vs-spot %, sentiment reading, current stance
- 2–3 paragraph synthesis in the voice of a **seasoned macro allocator with deep crypto fluency** (multiple equity cycles + cycles since 2013–2017 crypto)
- Numbered action items — specific, executable
- Closing "The Pattern" block quote with 2–3 conditional scenarios (`IF X → THEN Y`)

> **Verdict-Confidence Collar (Jun 2026 — anti-overstatement guardrail).** The prose must not be more confident than the quant layer supports. When **|EV-vs-spot| < 2% OR the bull/bear scorecard is within 1 of balanced OR the MECHANICAL score is 6–10** *(mechanical throughout this collar, including its strong-claim unlock below — pinned 2026-07-27 so a +2 can neither lift a report out of the collar band nor over the ≥15 strong-claim bar; discretion argues, it does not license)*, you are **prohibited from declaring a directional regime resolved** ("the fear window has closed," "the bottom is in," "the top is set"). *(Note, 2026-07-27: the 6–10 band now overlaps Phase-1A eligibility (≥8). That is deliberate and not a conflict — the collar governs what you may **claim**, not what you may **buy**. Deploy the first tranche into an unresolved tape if the gates allow; just do not narrate it as a resolved bottom.)* The discipline is structural, not lexical: separate **realized-data statements** (which may use strong language — "flows turned net-negative this week" is a fact) from **forward / regime-resolution claims** (which must carry a probability **or** an `IF→THEN` **and** a named falsifier). Negations and conditionals are permitted. *(This collar blocks the May-14 "fear window already closed for this leg" sentence — written at EV +0.6%, a 7/7 scorecard, score 8, immediately before a −23% drop to F&G 9 — while leaving that report's correct 100%-dry stance and well-hedged May-28 verdict untouched. The strong-claim unlock is "score ≥15 OR a realized trend-structure event," not a feeling.)*
- **Single-observation durability:** do not promote one data point to structure. A claim that a **fear regime has ENDED** (e.g., "$80K converted to support," "flows flipped supportive") requires the framework's existing confirmation bars — ≥5-session flow trend for flows, trend-structure repair for levels. Claims that *reinforce* the prevailing fear/down regime may use fewer observations (a correct fast bearish read is not penalized).
- **Tier-1 macro calendar-lock on short-horizon prose (Jul 2026).** The watchlist's fetched tier-1 release line (Required Data Fetches) is checked just-in-time whenever a short-horizon declarative comparative claim ("the more likely outcome", "not a V-recovery", "path of least resistance") is about to be written. Any such claim whose resolution window contains a tier-1 release must name it and use event-conditional phrasing: "pre-[event]; claim resolves conditional on [event], [date/time]". If the calendar fetch fails, the report may still print such claims but must flag them: "calendar-unverified — treat as base case, not primary call."

## Score Interpretation

| Adjusted Score | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — insufficient fear, or active distribution regime |
| 6–7 | Early Warning | PREPARE — build watchlist, refresh thesis |
| 8–10 | Accumulation Zone | CAUTIOUS ENTRY — Phase 1A eligible |
| 11–14 | Building | Phases 1A–1B eligible |
| 15–16 | Strong Signal | SYSTEMATIC DEPLOY — Phases 1A–2 eligible |
| 17–19 | Historic Opportunity | AGGRESSIVE DEPLOY — Phases 1A–3 eligible |
| 20 | Maximum Signal | FULL DEPLOY — all phases |

*(Re-banded 2026-07-27 to track the cut unlock lines — 1A at ≥8, 1B at ≥11; Phases 2/3 unchanged at ≥15/≥17. The table has always been the follower, §6 the operative text.)*

> **Reconciliation note (Jun 2026):** these stances describe **score eligibility only** — actual deployment is gate-gated (§6), now with the D2 conviction path as a documented near-miss route. A high score is *necessary but not sufficient*. In Jun 2026 BTC sat at score 15–16 ("SYSTEMATIC DEPLOY — Phases 1A–2 eligible") for four straight reports while only Phase 1A (10%) was ever deployed, because the gates blocked the rest. Do not let this table imply more deployment than the gates + Deep-Value Override (§6) actually authorize; state the gate-limited reality explicitly in every verdict.

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
13. **The rubric is a floor, the analyst is the ceiling** *(added 2026-07-27)* — five legs and nine gates are a discipline against self-deception, not a substitute for reading the tape. When your judgment and the rubric disagree, say so out loud, move the score with the D1 term, size it down, stop it hard, and **log it** so the next calibration can grade you. What is forbidden is not disagreeing with the model — it is disagreeing with it silently, or in prose that changes no number.
14. **Freedom on entry, none on exit** *(added 2026-07-27)* — a wrong entry costs a stop; a wrong exit rule costs the book. Every loosening in this framework is on the entry side, and every one of them is paid for by the D5 hard stop and the D6 ratchet. Stops move toward price only, forever.

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

**Machine block + lint (mandatory, Jul 2026; extended 2026-07-27).** Every report ends with a fenced ` ```json machine ` block (schema `report-machine/1` — field list in the header of `tools/lint-report.mjs`) carrying the structured facts: spot, score legs/**discretionary**/**mechanical**/raw/adjusted/rounding, gates (active/na/passed), EV scenarios + stated EV, deployment (with each tranche's `discretionary` flag, `channel`, and D5 `stop` — see D5's encoding rule: Override fills are `discretionary: true, channel: "override"`), stops, verdict, key inputs. The `score.discretionary` field is **required** — write `0` when no adjustment was taken, so a silent omission is never mistaken for a deliberate zero. The linter enforces the ±2 bound, the 0.5 step, the leg-sum-plus-discretion arithmetic, the cut unlock lines, and a D5 stop on every discretionary tranche. After saving and BEFORE committing, run `node tools/lint-report.mjs reports/<file>.md` — it recomputes the arithmetic (legs sum, rounding convention, gate denominator + ceil thresholds + [V] count, EV within the §5 0.5% tolerance, Rally ≤50% cap, stop coherence). **A FAIL is fixed, never overridden or committed around.** The block also makes future calibrations cheaper and more accurate: extraction agents read it instead of re-deriving numbers from prose.

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

### 2026-07-27 — Analyst Discretion Layer (owner agility mandate #2)

**This is NOT a calibration.** No backtest ran; nothing here is fitted to a realized path. It is an owner directive — *"less strict structure, more freedom for agent analysis, entries more often, stops more strict, the analyst's individual opinion more valuable"* — implemented as an explicit, bounded, and **graded** discretion layer rather than as a quiet loosening of the rubric.

**Loosened (entry side only):**
1. **D1 discretionary score term (±2, 0.5 steps)** — `raw = Σ legs + discretionary`, governed by **"D1 buys entries, never exits"**: the adjusted score is read by unlock rules only, while every protective rule (compound stop score line, Override arming, §7 trims, the EV-floor check, the Verdict-Confidence Collar) reads the **mechanical** leg sum. Requires ≥2 sourced factors the legs structurally cannot capture, a falsifier, and a Ledger entry; prints every report including at 0; decays after 3 unchanged reports; may cross at most one unlock threshold per report; may never solely enable Phase 3, arm the Deep-Value Override, satisfy the EV-floor check, or manufacture a Hard Rule 5 state.
2. **D2 Analyst Conviction Path** — a phase whose score condition is met but whose gate count is short **by exactly one** may unlock on a written case (≥3 sourced data points, named substituted gate + its relight path, dated falsifier). [V] floor still binds on lit gates. Half nominal size. Phases 1A/1B/2 only; **never Phase 3**; one per report; **unavailable while the risk-on correlation surcharge is live** (corr >0.7), so a written case can never cancel the +1 gate that surcharge exists to add.
3. **Unlock lines cut:** Phase 1A **≥10 → ≥8**, Phase 1B **≥13 → ≥11**. Phase 2 (≥15) and Phase 3 (≥17) score lines unchanged. Note precisely what that does and does not mean: **Phase 2 is still reachable by both analyst channels** (a D1 term can carry the score to 15; a D2 case can substitute its sixth gate) — only **Phase 3 is closed to analyst discretion entirely**. Score Interpretation table re-banded to follow (6–7 / 8–10 / 11–14).
4. **D4 discretionary probability layer** — the §5 grid is demoted to an anchor; the analyst may set scenario cells directly. Sum-to-100, the Rally ≤50% cap, EV-recomputed-from-cells, the realized-2-week disclosure, and the EV-floor/terminal-vs-extreme honesty checks all still bind, and the **active-downtrend boolean must still be printed** so the Override's quarter-size throttle keeps its trigger.
5. **Form-freed narration** — section order is now a default rather than a template, and the checkpoint-prognosis, terminal-vs-extreme, gate-reachability, and vacuity rules became **content requirements in free prose**. A "Form is free, substance is mandatory" clause enumerates what stayed hard: all sourcing/continuity/provenance rules, spot reconciliation, EV arithmetic, the machine block + lint, the companion FR score, and every stop rule.
6. **§9 Analyst Read** — a new mandatory free-form section: the analyst's own thesis, what the rubric is missing, the D1 term and its rationale, discretionary actions taken **and declined**, the Discretion Ledger, and a dated falsifier. The Verdict-Confidence Collar applies in full inside it.

**Tightened (exit side — the price of the above):**
7. **D5 Discretion Tax** — every **analyst-channel** tranche (D1 cross, D2 path) carries a **hard, price-only stop**: the thesis's structural invalidation, never more than 15% below fill, firing on a **single daily close**. No score condition, no second-close patience. Excluded from the buy-zone coherence check by design (it is meant to sit above deeper zones). On a hit: full tranche exit, capital back to dry powder, logged loss, and a 10-day discretionary bar on that phase. Non-mechanical capital (D1 + D2 + Override) capped at **40% of book** until a [T] gate relights or a higher-low prints, with the Override's ≤25% sub-cap counted inside it. **The Deep-Value Override is explicitly NOT an analyst channel and does not take the D5 stop** — an earlier draft classified it as one, which would have re-created the price-stop-at-maximum-fear anti-pattern the 2026-06-11 calibration removed (Jun 6 came ~1.9% from selling the bottom). Override fills keep the compound stop; its trigger, veto, and throttle remain deferred to a full calibration.
8. **D6 ratchet** — every stop parameter is monotonic **toward price**. Widening is **prohibited**, not merely disclosable — superseding the Migration Ledger's disclosure-only treatment of loosening, and closing the coherence check's "widen on disclosure while the Override is armed" hatch. Three narrow exceptions only: re-anchoring the catastrophic floor downward onto a zone **named in a prior report** (executed atomically and cited — this preserves the Jul-2 structure-conditional re-stage the Jul-04 calibration explicitly protected); **first-time calibration** of a parameter in a series, including the pinned-score-asset compound line the Jul-10 adjudication requires be set below the asset's realized range; and **calendar corrections**, which may move a checkpoint later within its week when the venue calendar dictates.

**Post-audit hardening (same day, two adversarial contradiction passes over the draft).** The first pass caught 16 conflicts, the second 8 residuals; all were fixed before publication. Two were substantive design errors, not wording: (i) the draft classified the **Deep-Value Override as an analyst channel**, which would have attached a price-only stop to tranches that fire only at maximum fear — the exact anti-pattern the 2026-06-11 calibration removed; and (ii) the compound stop's score condition read the **adjusted** score, so a +2 could have held it false and suppressed the whole book's stop. The governing "D1 buys entries, never exits" rule exists because of (ii). Also pinned here: the machine-block **encoding rule** (Override fills are `discretionary: true, channel: "override"` — the linter counts `channel: "override"` toward the caps regardless of the flag, so the 40%/25% caps fail *closed*), a required **`score.mechanical`** field, the §7 local-peak comparison made mechanical on *both* ends, Phase 3's unlock line read against the mechanical score, and the D6 campaign boundary (a new campaign begins only at a flat book, so the ratchet cannot be reset by trimming).

**Deliberately not touched:** Hard Rule 6 and the entire short side; the 10/15/30/45 pyramid; phase sizes; the Deep-Value Override's trigger, throttle, and worsening-flows veto (still deferred to a full calibration); every one of the 17+41+1 held rejections; all data-integrity rules. **Declined from the proposal:** a book-level drawdown circuit breaker and hard numeric per-tranche time stops — the existing mandatory time-stop language stands unchanged.

**Grading obligation:** every D1/D2/D4 call is a testable prediction recorded in the Discretion Ledger, and the next calibration must grade them exactly as it grades adopted tunes — including the counterfactual: *did the cut unlock lines and the conviction path buy better entries than the 10/13 bars would have, net of D5 stop-outs?* If a channel grades badly it is tightened or withdrawn. **This entire layer is N=0 — it has never been exercised on a live report.** Treat its first cycle as an experiment with a hard stop, which is precisely how it is designed.

### 2026-07-10 (second pass) — Deterministic toolchain introduced (`tools/`)

Owner directive: certainty in calculations, data fetching, and performance consistency. Added and mandated the repo toolchain — `tools/fetch.mjs` (live numeric backbone: cross-checked spot, ATH/drawdown, computed weekly Wilder RSI-14, exact 200-week SMA + gate-6 ±8% boolean, ADR sessions, F&G 3-day avg + gate-1 daily-print streaks, FRED real yield / VIX / DXY / Brent macro), `tools/compute.mjs` (band classifiers mirroring §4 letter-for-letter, ceil thresholds, per-asset rounding, EV sum-check, stop coherence, ADR with exclusions), `tools/lint-report.mjs` (validates the new mandatory ```json machine``` block — schema `report-machine/1` — after every save, before every commit; FAIL is fixed, never overridden), `tools/selftest.mjs` (regression vectors, including the ETH ceil(7/9×8)=7 misprint and the gold no-flush valuation cap). **Coupling rule: any band/threshold change to this SKILL changes `tools/lib.mjs` + `tools/selftest.mjs` in the same commit.** Scope honesty: ETF flows, on-chain, COT, and news remain live web fetches (Hard Rule 1) — the toolchain covers the computable series only. No score band, gate, threshold, phase size, or stop moved in this change.

### 2026-07-28 — Ledger tag vocabulary (§6, additive)

Every tranche that fills now prints a **ledger tag** (`FK-P1A`/`FK-P1B`/`FK-P2`/`FK-P3`, `FK-OVR`, `FK-D1`/`FK-D2`, `UNFRAMED`), applied by hand in the personal-accounting app via `PUT /api/investments/deal-note`. The app gained a matching `tagPrefix` filter, so `?tag=FK-P1A` is per-phase realized performance and `?tagPrefix=FK-` is the whole framework — win rate, profit factor, expectancy and hold-time skew on **money**, not on price. Every calibration to date has graded predictions against price; nothing has ever graded them against the P&L of the fills they authorized, because no join existed between a report and a position.

The tag is load-bearing for one reason: the ledger records quantity and cost basis but has no tranche dimension, so nothing except the tag can say which tranche authorized a holding. An untagged position is therefore reported as real-but-`UNTAGGED` and may not resolve a phase-dependent unlock precondition. Tagging stays **manual** — inferring a phase from quantity or timing is a guess, and a guessed phase unlocks the next tranche.

**No score, band, threshold, gate, phase size, stop, or cap moved.** `tools/lib.mjs` and `tools/selftest.mjs` are untouched by design (no rubric changed); `selftest.mjs` passes.
