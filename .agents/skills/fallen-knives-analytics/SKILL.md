---
name: fallen-knives-analytics
description: "Proprietary crypto accumulation and exit framework for 3–30 day swing trades during fear, using the completed-bar market-flow panel, dynamic technical/macro sentiment scoring, mechanical phase triggers, hard vetoes, risk sizing, and symmetric exits. Works for BTC, ETH, SOL, major alts, and smaller alts with asset-appropriate inputs. Use for Fallen Knives updates, buy/hold/trim decisions, fear readouts, accumulation zones, deployment, or exit planning. MUST fetch live data before analysis; never rely on stale or memorized data."
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
11. **Market-flow structure (crypto only)** — inspect spot CVD, futures taker bid/ask delta + CVD, aggregate OI candles, and OI-weighted funding on completed bars. Preferred source: Coinglass cross-exchange (`Binance,OKX,Bybit`) via `outp.context.market_flow`; keyless fallback: aggregate all active Binance stable-USD spot pairs and USD-M perpetuals discovered for the asset. The fallback builds sampled 4h OI OHLC from 30-minute USD-OI observations and weights each contract's latest settled funding rate by contemporaneous USD OI. Always state interval, completed-bar timestamp, venue/pair scope, whether CVD is window-rebased, and that the keyless result is Binance-only rather than cross-exchange.

Tag every figure with **source + timestamp**.

### JSON-first report publication

Historical reports use immutable `report-machine/2`: author a strict JSON draft,
finalize it under `reports/`, render deterministic `report-markdown/1` full
Markdown, and pair-lint JSON plus Markdown before export. JSON is canonical;
the embedded machine block is its exact compatibility view. `position_controls`
is always present: flat is `required:false/status:NOT_APPLICABLE`, open requires
candidate/veto/selection/venue-order/ladder/PnL/ratchet/liquidation-zone/risk/
execution-audit, and data-limited positions may not invent `HOLD` or `RETAIN`.
Legacy `report-machine/1` Markdown remains a read-compatible adapter.

## Mandatory premise-first research contract (new candidate families)

Every new candidate family begins with an immutable `strategy-precommit/1`
premise and its falsifier; do not start from composite-score thresholds. The
one-page precommit names the phenomenon and forced actor, edge-transfer and
persistence mechanism, direction/expression/horizon, expected frequency,
win-rate and payoff ranges, work/failure regimes, invalidation mechanism,
PIT/availability inputs, replication groups, and simplest falsifying test.
staged order is `CORE_PREMISE -> ENTRY_TIMING -> RISK_LIFECYCLE ->
INDEPENDENT_CONTEXT -> COMPOSITE_SCORE`, with each later stage linked to the
frozen predecessor hash. The score is a later incremental test after a
score-free baseline, and setup evidence may not be double-voted as independent
context. Tradable instruments are crypto spot and crypto derivatives only;
non-crypto markets are PIT-safe context/correlation inputs, never candidates,
holdings, validation markets, or PnL. Follow the reusable
[`strategy-research/RESEARCH-PROTOCOL.md`](../../../strategy-research/RESEARCH-PROTOCOL.md)
through the `strategy-research` skill and use `strategy-research.mjs precommit`
before `generate`.

## Swing-score/1 operating contract (SHADOW until activation)

The swing objective is a 3–30 day trade using completed 4h bars. This section
defines the compact v3 report format and deterministic shadow calculations. It
does not authorize entries yet: activation requires a committed BTC+ETH
walk-forward artifact that passes both long and short holdouts. Until that
artifact exists, legacy report-machine/1–2 decision rules remain operative for
live decisions. Never mix v3 scores with legacy gates. Analyst discretion is
commentary/score context only and can never unlock a phase, clear a veto, or
alter risk.

The calibration path is `node tools/swing-calibrate.mjs`: it joins completed
4h Binance spot/futures taker flow, Binance Data Vision OI samples, Binance
funding, FRED DXY/real-yield, Coin Metrics MVRV, and Alternative.me sentiment.
Funding is an explicitly carried latest-settled event state; macro/sentiment/
valuation use prior completed observations with revision/vintage risk disclosed.
The backfill applies a conservative next-UTC-day availability lag and fetches a
one-year warmup for calendar lookbacks; labels remain restricted to the request
window. The full OHLC label denominator is retained and feature coverage is measured
against it. The
chronological split is 18-month development, quarterly folds, and an untouched
6-month holdout. Non-overlapping 30-day episodes debit round-trip fee/slippage
in R. Activation is fail-closed unless each BTC and ETH FK-long, FR-A-short,
and FR-B-short series has at least 5 holdout episodes, precision ≥45%, positive
net expectancy, early capture >0, signals across ≥3 regimes, and ≥80%
aligned-feature coverage against the full OHLC label universe (excluded labels
remain in the denominator). A passing run writes a hashed
`calibrations/swing-btc-eth.json`; it additionally requires point-in-time-safe
vintages and explicit acceptance of the historical proxy contract. Otherwise
the model remains `SHADOW`.

### Rejected dynamic-router research (2026-08-22)

Do not promote the frozen `fk-frb-percentile-atr-router-v1`. It combined a
70th-percentile completed-bar score gate (current bar excluded; 540-bar prior
window, minimum 180), a 50d/200d EMA direction router, 3×ATR stops clamped to
3–6%, and 5% per-direction caps. Its seven-asset development result was
positive, but the precommitted final unseen AAVE validation failed: 29 completed
trades, 15 wins (51.7%), −0.0480R expectancy, 0.892 R profit factor, −0.43%
return, and −0.189R bootstrap p20; doubled costs produced −0.0906R and 0.802
dollar profit factor. Coverage was 99.62%, derivatives coverage was 100%, and
funding was charged, so this is not a data-quality waiver. The durable decision
is `calibrations/swing-percentile-atr-router-aave-rejection-20260822.json`.

Consequences: the percentile/EMA/ATR combination is research-only, cannot
unlock an FK phase, and may not be tuned on AAVE or replaced by an AAVE
diagnostic. Candidate selection must hard-block nonpositive search-adjusted
expectancy using the declared number of searched hypotheses. Legacy rules
remain responsible for live authorization and v3 remains `SHADOW`.

### Durable strategy-research registry (mandatory for new tests)

Record new grids, reruns, finalists, and validation evidence under
`strategy-research/` with `node tools/strategy-research.mjs`. Definitions are
immutable/versioned; runs are append-only and content-addressed; all-candidate
per-asset metrics and declared/effective search K remain auditable. Raw feature
stores stay outside Git.

Use exact evidence phases (`DEVELOPMENT`, `WALK_FORWARD_OOS`,
`EXPOSED_CONFIRMATION`, `SEALED_CONFIRMATION`, `PROSPECTIVE_LIVE`) and exact
decisions (`REJECTED`, `SHADOW`, `CANDIDATE_REVIEW`, `ACTIVE`) independently per
asset and portfolio. Missing PIT safety or failed gates never authorize a
tranche. `CANDIDATE_REVIEW` is still non-active: registry recording cannot
bypass governed activation. Legacy imports are provenance, not activation evidence. See
`strategy-research/README.md`.

For v2 backtests the authoritative path is `strategy-research.mjs evaluate`:
freeze the experiment/candidate set, hashed feature store and
`strategy-data-manifest/1`, then retain the resulting evidence bundle. The
local swing adapter recomputes trades/metrics, stress and mark-to-market
portfolio risk; narrated or caller-supplied result JSON is external exposed
evidence only. Local `SEALED_CONFIRMATION` and `ACTIVE` are impossible, and
options, multi-leg carry/basis, HFT/queue claims or missing leveraged marks
fail closed.

- Compute `swing-score/1` with six legs: market flow 5, technical structure 4,
  macro impulse 3, sentiment/institutional impulse 3, valuation/cycle 3, and
  structural demand/distribution 2. Scores are mechanical half-points in
  `[0,20]`; an optional analyst term is limited to ±1.0 in 0.5 steps and can
  shade the dashboard but cannot unlock a phase, clear a veto, or alter risk.
- Persist the non-flow legs as explicit state/impulse pairs: technical 2+2,
  macro 1.5+1.5, sentiment/institutional 1.5+1.5, valuation/cycle 2+1, and
  structural demand 1+1. Each completed-bar check contributes 0.5 only when
  it aligns with the long setup; neutral, missing, or opposing checks score
  zero. Technical uses 20/50-EMA location, higher-low/RSI regime for state and
  4h RSI slope, ATR-normalized return, volume, and break/retest for impulse.
  Macro uses the 20-session regime and 3-day change in DXY, real yields, and
  net liquidity. Sentiment uses the level and 3-day change in fear/greed plus
  ETF/stablecoin/funding institutional flow. Valuation uses MVRV, ATH
  drawdown, 200-week multiple, and realized-price location plus their weekly
  direction. Structural demand uses the 30-day level and 3-day change in ETF
  demand, exchange reserves, and stablecoin supply. Store every awarded check
  in provenance; data presence alone never earns a half-point.
- Read `context.market_flow` as one evidence family. Score current state and
  impulse separately; full flow credit requires completed-bar coverage across
  both 24h and 3d. Partial or mismatched Binance aggregate coverage caps the
  flow leg at 2.5 and blocks a new trigger. The panel's five rows are always
  printed, with Binance aggregate explicitly labelled single-venue when
  CoinGlass is unavailable. Spot/futures rows read their signed flow directly;
  funding is setup-inverted (negative/washed-out aligns with FK), while open
  interest must carry an explicit setup-relative interpretation against price
  and CVD because OI direction alone is ambiguous.
- Replace the nine-gate board with three mechanical gates: phase score line,
  a completed 4h price/flow trigger, and no active veto. FK phase lines remain
  8/11/15/17 with 10/15/30/45% caps. Every deeper phase needs a fresh trigger
  at a better price and a two-completed-bar retest window.
- Hard vetoes are incomplete flow, opposing two-horizon flow, regime mismatch,
  exhausted portfolio/asset risk, narrative exit, carry/funding veto, and an
  opposing multi-family macro shock at the rolling 97.5th percentile. Ordinary
  macro releases modify the macro leg; there is no calendar blackout.
- Size as `min(phase cap, 1.5% portfolio equity risk / stop distance, 3% asset
  risk / stop distance)`. Without measured equity or a valid stop, deployment
  is DATA_LIMITED. Tactical FK stops use completed 4h invalidation + 0.25 ATR,
  at least 1 ATR from entry, and never more than 15% away. Deep-capitulation
  compound stops survive only when the slow anchor, extreme fear, and
  liquidation/deleveraging condition all hold.
- Default tranche exits are 40% at 1R, 40% at 2R, and 20% trailing toward 3R
  or the time stop; ratchet to entry after T1 and behind completed 4h structure
  after T2. FK clocks are 7/14/21/30 days, with one re-underwriting extension
  only through the next clock and never past 30 days.

### v3 publication and compact report

Shadow swing reports may use canonical `report-machine/3` sidecars. They retain
sources, provenance, position controls, and phase attribution internally, but
the Markdown view contains only: decision snapshot; five-row market-flow plus
technical/macro/sentiment dashboard; score/trigger/veto/phase state;
entry/stop/targets/expected-R/sizing; position/ratchet/time-stop/exit status;
watchlist and changes. Do not print separate Market/evidence/data-quality,
Substitutions/source-register/provenance, Phase-registry/canonical-tags, or
Canonical-machine-payload sections. The sidecar hash is carried only in the
compact audit footer. A v3 sidecar is not an activation artifact and cannot
authorize an entry until the BTC+ETH walk-forward holdout passes. Use
`tools/finalize-report.mjs`, `tools/render-report.mjs`,
`tools/lint-report.mjs`, and `tools/export-signals.mjs`; v1–2 artifacts remain
read-compatible and are never rewritten.
The sidecar records `model_activation.status`: `SHADOW` and
`CANDIDATE_REVIEW` force `entry_authorized:false`; `ACTIVE` requires a
committed calibration artifact path, SHA-256, and activation timestamp.

### Deterministic toolchain (mandatory, Jul 2026)

The repo ships `tools/` (see `tools/README.md`) so the numeric backbone is **computed, not narrated** — the failure modes it retires were all hand-done steps (the 4-report RSI NOT-FOUND debt, the ETH `ceil(7/9×8)=6` misprint, eyeballed EV sum-checks, ADR absorbing a half-session).

0. **Position (Hard Rule 8):** run `node tools/position.mjs <asset>` first. What you already hold — real quantity, ACB, and above all **real dry powder** — changes what the rest of the report is deciding, and sizing a tranche before checking the cash exists is how a plan spends money it does not have. Exit-code contract per AGENTS.md / `tools/README.md`. See §6 "Position & Performance".
1. **Fetch:** start every report with `node tools/fetch.mjs <asset>` (+ `node tools/fetch.mjs macro`). Returns the live numeric backbone with source + timestamp on every block (spot, RSI, 200-week SMA, ADR, F&G streaks, macro) — see `tools/README.md` for the current field list; the tool prints its own fields, so this SKILL does not re-enumerate them.
2. **Compute:** band assignments, `ceil` gate thresholds, per-asset .5 rounding, EV recomputation, and the stop-coherence boolean come from `tools/compute.mjs` (or the fetch output) — hand arithmetic is a cross-check, never the source of record. The band classifiers in `tools/lib.mjs` mirror §4 letter-for-letter; **any band/threshold change to this SKILL must change `tools/lib.mjs` + `tools/selftest.mjs` in the same commit.**
3. **Scope honesty:** the toolchain covers price/momentum/valuation-input/sentiment/macro series only. ETF flows (Farside is bot-blocked), on-chain (MVRV-Z, LTH, reserves, liquidations), COT, and news remain live web fetches under Hard Rule 1 — a tool-covered field never excuses a missing web-sourced one.
4. **Tool failure:** if a fetch source errors (the tool reports per-source errors instead of dying), fall back to the SKILL's documented NOT-FOUND/fallback rules and disclose the failure — never substitute memory.
5. **Context Panel (market-data-extension plan, 2026-08):** `tools/fetch.mjs`'s `outp.context` block — see the fuller Context Panel entry in §2's live-data section for field names. For crypto, **inspect `context.market_flow` every report**; other context fields remain optional. **LEGACY v1–2 ONLY:** this block is disclosed context, not a scored leg or gate. An activated v3 report instead follows the Swing-score/1 contract above, where only the audited market-flow family is scored; no other context field is promoted. It never substitutes for a Required Data Fetch above. `tools/tripwire.mjs` (run by hand, not part of the mandatory workflow) can flag a scoring-relevant boundary crossing between two stored `tools/snapshot.mjs` runs without a full report.

## Analyst Discretion Layer (added 2026-07-27 — owner agility mandate #2)

The five legs and nine gates are a lower bound on rigor. They cannot see order-book structure, the shape of a liquidation cascade, a policy shift the macro gate has not yet priced, a spot-vs-derivatives divergence, a narrative inflection, or a cross-asset tell — all things an analyst reading a live tape can see and argue. This layer makes that judgment a **first-class, logged, and graded input** instead of something smuggled into prose that changes no number.

**The bargain (non-negotiable):** discretion buys **entries**; it pays for them with **stops**. Discretion may never buy a looser stop, a tranche larger than nominal, a relaxed sourcing rule (Hard Rule 1 is untouched), a weaker data-integrity rule, or anything at all on the short side (Hard Rule 6).

### D1 — Discretionary score adjustment (±2)

The composite carries an explicit analyst term: **`raw = Σ legs + discretionary`**, where `discretionary ∈ [−2.0, +2.0]` in 0.5 steps. Round per the asset's convention; clamp the adjusted score to [0, 20].

> **Governing rule — D1 buys entries, never exits.** Two numbers now exist and every rule must name which it reads:
> - **Mechanical score** = `round(Σ legs)` on the asset's convention, with **no** discretionary term.
> - **Adjusted score** = `round(Σ legs + discretionary)`, clamped to [0, 20].
>
> **The adjusted score is read by deployment/unlock rules ONLY** (§6 phase score lines, the Score Interpretation table) — **except Phase 3 and the 17–19 row, which read the mechanical score; the D1 term may never be Phase 3's sole enabler.**
> **Every protective rule reads the MECHANICAL score, without exception:** Phase 3's ≥17 arming condition, the compound thesis stop's score line, the Deep-Value Override's ≥15 arming condition, every §7 trim/exit trigger (both the ≥6-point drop and the ≤3 line), the §5 EV-floor consistency check, and the Verdict-Confidence Collar's band and strong-claim unlock.
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
- **Context Panel** (market-data-extension plan, 2026-08) — optional, **disclosed context only, never a scored leg or gate**: `tools/fetch.mjs`'s `outp.context` (realized vol + percentile, drawdown/RSI/200dma-distance percentiles, F&G/funding percentiles), `outp.context.deribit` (BTC/ETH options — ATM IV, moneyness skew, VRP), `outp.context.basis` and `.positioning`, and macro-scope `net_liquidity`/`hy_oas`/`nfci`/`stablecoin_supply`. Include when it sharpens the read (e.g. a percentile that contradicts the headline band, or a skew move); a report is complete without this panel. Promoting any field here into the rubric is a `framework-calibration` job, not something this skill does inline.
- **Market Flow Panel** (crypto; mandatory inspection, print the available/unavailable status of all five rows): `context.market_flow.spot_cvd` · `.futures_bid_ask_delta` · `.futures_cvd` · `.open_interest` · `.oi_weighted_funding`. Preferred scope is Coinglass cross-exchange. When `coinglass_api_configured:false`, read `binance_aggregate.spot_symbols_included` / `.perpetual_symbols_included` and label the result **Binance aggregate, single venue**. This is broader than a single-pair proxy but is still not market-wide. CVD is rebased to zero at the first completed bar in the returned window, so compare **24h / 3d / full-window direction, imbalance, and divergence**, never its absolute level across reports.

**How to print and use the keyless Binance aggregate in a report:**

1. Print a compact table with `Metric | Scope/coverage | 24h | 3d | Full window/latest | Read`, captioned with `interval_hours` and `completed_through` (bar close; `as_of` is the bar's opening timestamp). For CVD use `delta_*_usd`, direction, and `imbalance_*_pct`; do not print the rebased cumulative level as if it were comparable with a prior report.
2. For OI print `latest.open/high/low/close`, `change_24h_pct`, and `change_window_pct`. Carry `ohlc_method` verbatim: these are 30-minute sampled extrema, not continuous exchange-native highs/lows. If `sampling_quality.incomplete_bars > 0`, full-contract coverage is missing, `binance_aggregate.errors` is non-empty, or a discovered-symbol list differs from its corresponding included-symbol list, label the affected row/horizon **PARTIAL** and do not use it for D1/D4.
3. For funding, the Binance fallback is genuinely OI-weighted across the listed USD-M perpetuals. Its raw unit is a fraction (`0.0001 = 0.01%`); multiply by 100 only for percent display. Use the latest/24h mean, sign, and relative percentile. Do not annualize the aggregate unless every included contract's funding interval is separately verified; otherwise state `interval-unverified — sign/relative-history use only`.
4. State both boundaries in the table caption: stablecoin quotes are treated as nominal USD, and every Binance row is one venue/provider family. The five rows may provide at most one of D1's required sourced factors and still need an independent family plus a falsifier.
5. If CoinGlass is available, prefer it for cross-exchange claims. Never splice a CoinGlass spot row and Binance derivatives rows into one unnamed “aggregate”; `scope` and each `source` entry must remain visible.
- **Correlation Regime**: 30d corr vs SPX (**sourced + timestamped, or "not computed"**) | regime label (decoupled / mild / risk-on / inverse)

**How Fallen Knives reads the Market Flow Panel (context, not a new rubric):**

- Price down + **spot CVD up** across more than one horizon → potential spot absorption/accumulation.
- Price down + futures CVD down + **OI down** → long deleveraging/capitulation context; do not confuse it with new shorts.
- Price down + futures CVD down + **OI up** → fresh short build/continuation risk; this weakens an early knife-catching read.
- Price up + futures CVD up + **OI down** → short covering, not proven fresh spot demand.
- Negative OI-weighted funding with elevated/rising OI and futures selling absorbed by flat/rising price → squeeze fuel; never a reason to relax an existing stop.

Use completed 4h bars by default. One bar is observation, not structure: a D1/D4 use needs agreement across at least two horizons (normally 24h and 3d), material magnitude stated, and a falsifier. Spot CVD, futures CVD, delta, OI, and weighted funding from this panel are **one provider/derived family**, not five independent facts; they can supply at most one of D1's required sourced factors. A Binance aggregate is additionally single-venue even when it covers several pairs/contracts. Any automatic score/gate/threshold promotion requires `framework-calibration`.

**[R:canonical-spot] Canonical-spot reconciliation rule (Jun 2026, refined Jul 2026).** Do not pick an informal round number for spot. **Canonical spot = median of the primary source + ≥2 others**, timestamped. Label each source-panel row explicitly as "live" or "frozen/stale (age stated, anchored to report-publication time)." The spread computation must state whether dispersion is judged time-ordered/staleness-driven or genuine simultaneous disagreement, with low-confidence demotion applying only to genuine disagreement among synchronized (within 2hr of report-publication time) quotes; a stale quote within 0.5% of the live cluster need not be flagged as excluded, but a stale quote that diverges must be shown in the table with its timestamp/age and an explicit "EXCLUDED — outside 2hr window, divergent" tag rather than silently dropped. If fewer than 3 synchronized quotes are obtainable, say so and apply the low-confidence handling below. If the inter-source spread among synchronized quotes is **>0.5%**, report it and compute EV at both extremes. If the EV *sign flips* across that spread **AND** |median EV-vs-spot| < the spread, mark the read **low-confidence / corroborative-only** and require a *second independent* unlock condition before acting — do **not** mark it INDETERMINATE (that would suppress legitimate near-zero-EV calls, which were the framework's best in-sample). *(Gold Jun 10 carried a ~0.43% inter-source spread while EV was quoted at 0.1%; the May-31 "don't add" call sat at −0.5%, inside the spread. Jul-1 2026 BTC: a flagged 2.0% spread mixed Jul-1 live with Jun-30 prints — time-skew during a fast tape, not venue disagreement; the synchronized Jul-2 panel ran 0.22%.)*

**Metric-history continuity rule (Jul 2026).** Any claim about a metric's history extending beyond the current fetch window (regime start dates, streak lengths, sustained-since claims) must be reconciled against the same metric's print in this series' most recent prior reports. A regime may never be backdated past the last prior report that recorded the opposite state — the in-series provenance is authoritative (regime start = first prior report showing the new state). If a live source's claimed lineage contradicts the series, use the series' dating and disclose the discrepancy. *(ETH Jul-1 2026 backdated sustained-negative funding to ~Jun 3–4 while its own Jun-19/21 reports printed normalized, not sustained-negative — the true flip was ~Jun-25.)*

**Provenance citation for duration/"since [date]" claims on scored inputs (Jul 2026).** Any claim of the form "X has been Y since [date]" (or "for N weeks") about an input that feeds a score leg or gate must cite either (a) the prior report that first printed state Y, or (b) a fresh source covering the full interval. Before printing, grep the asset's own prior reports for the claimed state term; if any prior report printed the contradicting state after the claimed start date, use the latest self-consistent start date and disclose: "sustained since ~[corrected date] (prior reports [dates] printed [contradicting state])". Scope: limited to inputs consumed by name in a scoring-table row or gate-status line (funding sign/persistence, ETF flow streak length, F&G streak length, price-vs-MA structural claims) — not narrative color elsewhere in the report.

**Single-source streak-completion rule (Jul 2026, codifies the ETH Jul-9 practice).** A print that completes a streak/regime bar (e.g. the 5th green flow session) but is single-sourced marks the streak **PROVISIONAL**: disclose it, and no regime-flip claim, gate credit, or unlock keyed to that streak until a second source corroborates (by the next report at latest). If corroboration contradicts, apply the metric-history continuity rule.

**Stale-input debt clock (Jul 2026, generalizes the momentum leg's rule).** Any scored input or gate input carried on NOT FOUND / stale / derived-estimate status for ≥3 consecutive reports must ship a sourced or computed replacement next report, or state explicitly why it cannot be obtained; while carried, the affected leg/gate holds its prior value and is flagged inline. This extends the momentum-leg rule to all legs (valuation, holder, capitulation) and to gate 6's long-horizon MA.

**Mandatory computed companion score (cross-validation — Hard Rule 5).** Every Fallen Knives report must state the **computed** Flying Rocket composite for the same asset/timestamp (number + gate count, from the same live data fetch) — **estimated/eyeballed companion numbers are prohibited.** *(In May–Jun 2026 exactly one FR report existed — May 14, F&G 50, the moment of least signal — and every later FK report asserted inverse "consistency" with a sourceless "~3–4," making the check unfalsifiable precisely as fear deepened.)* If the companion composite is ≥9, append a short informational watch block (it does **not** unlock any short phase — FR Phase 1A still needs ≥11 (Channel A) / ≥13 (Channel B), per Hard Rule 6). If the companion **cannot** be computed (data failure), pause **net-new long deployment only** — never force a trim, relax a stop, or block a hold. The cross-validation inconsistency condition remains **both frameworks ≥12 simultaneously** (never a sum).

**Vacuity labeling + FR≥9 tripwire (Jul 2026).** While the FR phase-of-cycle cap binds (asset >20% below its 1-yr ATH), both-≥12 is unreachable by construction; in that regime the consistency line must read "structurally consistent (cap-bound; both-≥12 unfalsifiable by construction)" — never a bare consistent ✅. FR≥9 while cap-bound is a standing (currently dormant, never yet fired in-sample) heightened-watch condition, NOT a Hard-Rule-5 substitute or a new pause/unlock threshold. A full standalone companion report is additionally required when (i) the primary FK score crosses a phase-unlock threshold, or (ii) the inline FR companion prints ≥9, or (iii) ≥$100M of the day's liquidation volume is on the short side (e.g., via CoinGlass/COINOTAG), or (iv) the FR phase-of-cycle cap stops binding (asset closes within 20% of its 1-yr ATH). **The obligation is dated, tracked, and discharged only by the report itself (2026-08-06).** It is recorded per asset **inside the existing `companion_fr` machine block** as `companion_fr.standalone_report_trigger: {owed, trigger, fired_on, reports_outstanding}` — alongside, never in place of, the linter-enforced `companion_fr.standalone_report_owed` boolean — and carries forward until discharged. It is **discharged only by a standalone Flying Rocket report on that asset dated on or after the firing report**; an inline companion computation, a re-fire of the same trigger, and an FR report predating the trigger all discharge nothing. **If the obligation is still outstanding at the 3rd consecutive FK report on that asset, the Hard Rule 5 line for that asset must read "cross-validation UNVERIFIED — standalone FR obligation outstanding N reports"** in place of any consistent/structurally-consistent label, and the report may not cite cross-validation as supporting evidence **in the §12 Strategic Verdict or in any regime-resolution claim** while it stands.

**Unresolved contradiction, flagged not fixed (C5, 2026-08-07):** this rule requires a standalone FR report when the FK score crosses a phase-unlock threshold. Flying Rocket's own mandatory-re-check trigger list explicitly excludes that same crossing ("a companion framework's (FK's) score threshold crossing does NOT independently trigger this note — it belongs to a different discipline tier under Hard Rule 6"). Left standing per owner decision (2026-08-07 compaction) rather than adjudicated inline; flagged for the next `framework-calibration` run to resolve with reports as evidence — see both REVISION-LOG.md files.

**This is a disclosure and evidentiary-weight rule only, and it is ratcheted one-way (mandatory).** It creates no new pause threshold, no unlock, and no deployment block — and, critically, **withdrawing evidentiary weight from cross-validation may never RAISE the adjusted score.** If dropping a companion-corroborated factor would invalidate a D1 term under the ≥2-sourced-factors rule, **the D1 term is HELD at its prior value** and the report states that it is held under this rule; the term is re-argued or retired on its own evidence under D1's decay rule, never retired by this clock. A D1 factor resting on an independently sourced **market fact** that the companion merely corroborates is not a cross-validation claim and stays admissible. The bar restricts what may be **claimed**, never what may be **bought** — mirroring the Verdict-Confidence Collar's own scope note. *(Without this ratchet the rule would have raised BTC 08-03/08-05 and ETH 08-05 from adjusted 10 to 11 by deleting a deliberately NEGATIVE D1 term — crossing the Phase 1B ≥11 line on the exact reports it was designed for. An accountability clock that buys a tranche is a loosening wearing a disclosure's clothes.)*

**Enforcement:** `lint-report.mjs` validates `companion_fr` but has no validator for the new sub-block, so the tracking ships as **prose discipline**; the natural follow-up is a linter check requiring the owed block whenever `companion_fr.score >= 9` and requiring the `cross_validation` string to contain "UNVERIFIED" once `reports_outstanding >= 3`. When the cap does NOT bind, the plain "cross-validation: consistent ✅" / "inconsistent" label stands unqualified and carries full evidentiary weight — this relabeling is conditional, not a permanent softening, and Hard Rule 5's substantive both-≥12 pause-and-flag mandate remains fully live and reactivates the instant the cap stops binding.

### 3. Critical Developments

Bulleted summary of the highest-impact news/events with cited sources. Cover geopolitics, regulation, market structure, key analyst calls, asset-specific catalysts.

### 4. Fallen Knives Composite Score (X / 20)

| Category | Max | Scoring Rubric |
|---|---|---|
| **Sentiment Extreme** | 5 | **3-day average** F&G or asset-equivalent sentiment — use the smoothed average, NOT the single-day spot print, to avoid score whipsaw (Jun 2026 F&G swung 11→23→17 in three days). ≤10 → 5 · ≤15 → 4 · ≤25 → 3 · ≤35 → 2 · ≤50 → 1 · >50 → 0. **Provider pinning + boundary tie-break (Jul 2026):** the sentiment provider is PINNED per asset series (crypto: Alternative.me raw API daily series) — spot and 3-day average must come from the pinned provider only, never mixed across days or switched between reports; a second provider may be quoted as context, and divergence ≥10 index points must be disclosed inline. Exact-boundary tie-break: a value equal to a band edge belongs to the band whose inequality includes it (15.0 ≤ 15 → 4) as the DEFAULT; an analyst may deviate from the letter only toward the MORE CONSERVATIVE (lower-score) reading, must flag it explicitly with a one-line reason, and must revisit/reconcile it next report. This fixed-boundary tie-break convention applies to all other ≤/≥ rubric bands in this table (momentum, capitulation, valuation) via cross-reference. **"Asset-equivalent" for non-BTC/ETH means a fear instrument with daily resolution, never a positioning report** — COT is PROHIBITED as the sentiment input (it already drives the capitulation leg's washout read; one input may not key two legs). Primary fallback when no asset-native daily fear instrument is reliably sourceable (the expected default for gold/commodities — DSI/HGNSI are typically paywalled/non-daily): score conservatively at 2 and flag NOT FOUND; do not substitute a price-derived or positioning-derived proxy. GVZ/DSI/HGNSI/PHYS may be cited as disclosed regime context only, never as the scored input. **Tested and rejected on evidence (2026-08-05) — the gold fallback is a MEASURED conclusion, not an availability claim:** both GVZ (direction-blind, no contrarian gradient) and PHYS closed-end premium/discount (right shape, but the effect inverts split-half and its qualifying-day count collapses to ~16 distinct episodes) were backtested over 10y and rejected. Full numbers live in `sentimentProxyBlock()` (`tools/lib.mjs`), the live `context.sentiment_proxy` block `node tools/fetch.mjs gold` emits (`scored:false`), and `REVISION-LOG.md`'s 2026-08-05 entry — the leg scores 2 because the available instruments were *measured and carry no signal*, not because none could be found; promoting either into the rubric is a framework-calibration job with adversarial refutation. |
| **Momentum Exhaustion** | 4 | Weekly RSI. <30 → 4 · ≤35 → 3 · ≤40 → 2 · ≤45 → 1 · >45 → 0 *(chained ≤ bands, first match wins — an exact edge (35.0, 40.0, 45.0) belongs to the band whose ≤ includes it, mirroring the pinned sentiment tie-break; deviation permitted only toward the lower score, flagged)*. *(Removed the unreachable <25 → 5 tier — it only fires at generational bottoms and biased the legacy framework toward underdeployment.)* **Input rule (Jul 2026, mirrors the correlation leg's "sourced or not computed"):** the weekly RSI must be (a) sourced with timestamp, or (b) computed as Wilder RSI-14 from ≥15 fetched weekly closes (15–29 closes = a low-confidence read — flag it, and if the value lands within 2 RSI points of a band edge take the lower-score band; ≥30 closes for an unflagged read), with the weekly-close source, weekly boundary (e.g. Mon 00:00 UTC), period (14), and number of closes used stated inline for auditability. An "inferred ~X–Y" eyeball range is NOT a scoring input. If neither sourced nor computable, hold the leg at its prior value and flag NOT FOUND (cold start with no prior value: score the leg 1 — conservative-on-a-buy-signal, mirroring the sentiment leg's fallback-at-2 convention — and start the debt clock at report 1); a leg held ≥3 consecutive reports on NOT FOUND must ship the computed fallback next report or state why computation failed — never fabricate closes. This changes an INPUT only — momentum lighting alone never completes a 1B/2 unlock without the independently-required gate count. |
| **Valuation** | 5 | **Primary (BTC/ETH):** MVRV-Z. **<0.1 → 5** (generational green-zone; any negative MVRV-Z also lands here) · **≤0.5 → 4** · **≤2 → 3** · ≤3 → 2 · ≤5 → 0 · >5 → −2 (trim signal) *(chained ≤ bands, first match wins; an exact edge belongs to the band whose ≤ includes it — 0.50 → 4. The former 0.5–1.0 / 1–2 bands both scored 3 and are merged, output-identical)*. *(Re-banded Jun 2026: the legacy `0–1 → 4` band spanned the entire $79.5K→$66K decline and sat saturated at 4/5 from a local top — MVRV 0.64–0.87 in May scored 4 when it should read 3. The 0.5 breakpoint is where the realized bottom actually sat.)* **Fallback (alts without reliable MVRV):** drawdown from ATH. ≥70% → 5 · ≥60% → 4 · ≥50% → 3 · ≥40% → 2 · ≥30% → 1 · <30% → 0 *(chained ≥ bands, first match wins; an exact edge belongs to the band whose ≥ includes it — 60.0% → 4)*. State which metric was used. **Low-volatility adaptation (gold/commodities, Jul 2026):** may be substituted ONLY when the asset's realized 30-day vol is documented (sourced + timestamped) at ≤½ of BTC's contemporaneous 30-day vol, and the substitution is logged in the Change Log with the historical bear distribution it is anchored to (2008 −30%, 1996–99 −39%, 2011–15 −45%) and the current regime's realized vol/ratio disclosed. Bands: ≥45%→3 (gated behind a CONFIRMED positioning capitulation — a COT non-commercial net-long flush; absent a flush, cap at 2) · ≥36%→2 · ≥28%→2 · ≥20%→2 · ≥12%→1 · <12%→0 *(chained ≥, first match wins)*. This band-set feeds the [V] scoring leg ONLY — it does not relight gate #3, confers no gate credit, and no stop change. Never score a leg off an undocumented "asset-adapted" judgment constant; if the published band-set doesn't fit the asset, publish the asset's band-set and log it in the Change Log, stating which metric AND which band-set was used. |
| **Capitulation Evidence** | 3 | Count of: (a) **24h liquidations in the top decile of the trailing-90-day distribution OR >3σ above the trailing-30-day mean** — state explicitly whether the figure is asset-specific or market-wide and be consistent across reports. *(Regime-relative as of Jun 2026: the legacy fixed `>0.5% of mcap` bar NEVER fired for any asset in the May–Jun 2026 sample — BTC peak ~0.13%, ETH ~0.20%, SOL ~0.014% — an unreachable tier, the same defect removed from the momentum leg. A real flush must register.)* (b) perp funding negative for ≥3 consecutive funding intervals, (c) ETF net outflows ≥2% of AUM over trailing month (BTC/ETH only — for alts, substitute exchange-inflow spike). 3/3 → 3 · 2/3 → 2 · 1/3 → 1 · 0/3 → 0 |
| **Holder Behavior** | 3 | (a) LTH supply rising 30d (BTC/ETH) or top-100 holder concentration stable/rising (alts), (b) exchange reserves declining 30d. Both → 3 · One → 1.5 · Neither → 0 |
| **TOTAL** | **20** | |

**Correlation Regime Treatment** (revised Jun 2026 — demoted from a score multiplier to a sourced gate surcharge + context label):

The legacy multiplier (×0.85 / ×1.00 / ×1.05 / ×1.10) rode on *eyeballed* correlations ("~0.3–0.5") and could flip the gate-driving integer off an unsourced guess — a "modifier technicality." It never changed an unlock in the May–Jun 2026 sample, so removing the bonus side is non-destructive. The only legitimate function — distrusting a "fear" score in a high-beta risk-on regime — is preserved as a **breadth surcharge**, not an arithmetic haircut:

- **[R:corr-surcharge] Risk-on suppressor (kept, hardened):** if a **sourced** 30-day Pearson correlation (CoinMetrics / TradingView / computed from price series, with timestamp) is **>0.7**, require **one additional [V] confirmation gate** for ANY phase unlock — defined precisely (Jul 2026): the phase's required TOTAL gate count increases by 1 AND its [V]-floor increases by 1 (the incremental gate must come from the [V] bucket). A fear signal that is really equity beta deserves more confirmation, not a fractional score cut — and this now protects Phases 1A/1B, which the old ×0.85 covered but the Phase-2 `corr <0.8` gate did not.
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

**Score-line vacuity & binding-constraint audit (2026-08-06).** The disclosure above reads the GATE axis. This one reads the SCORE axis and the per-phase distance, and it inherits the identical one-directional restriction. Substance mandatory, form free — every report states:

(a) **Attainable ceiling** — the sum of each leg's maximum attainable value under its *documented* structural pins, with the pins named, **re-derived each report from that report's own pins and never carried forward** (the same re-derivation rule the gate disclosure above carries). *(Illustrative, never a constant: gold's sentiment leg is pinned at 2 by the 2026-08-05 measured conclusion; its valuation leg is capped at 2 absent a confirmed COT flush and reaches 3 only with one at ≥45% drawdown; momentum 4, capitulation 3, holder 3 are unpinned — so on today's pins the ceiling computes in the 14–15 region. The 2026-08-05 revision log's "near 13 at −30% / 15 at −50%" is that pass's approximation, not an operative constant; where the two disagree the report's own arithmetic governs and the divergence is stated. BTC/ETH: 20, unpinned.)*

(b) **Score-line state** — every score line that governs a decision, tagged **LIVE**, **VACUOUS-FALSE**, **VACUOUS-PERMISSIVE** or **VACUOUS-BLOCKING**, each evaluated against the score that line actually reads (the adjusted score for the deploy lines; the **mechanical** score for Phase 3, the Deep-Value Override's arming bar and the compound thesis stop, per the governing D1 rule): Phase 1A ≥8, Phase 1B ≥11, Phase 2 ≥15, Phase 3 mechanical ≥17, the Deep-Value Override's mechanical ≥15, and the compound thesis stop's score line.
- **VACUOUS-FALSE** = the line exceeds the attainable ceiling, so the predicate cannot be satisfied without a structural change, which must be named.
- **VACUOUS-PERMISSIVE / VACUOUS-BLOCKING** = the predicate has held one truth value across the trailing **≥4 consecutive report dates** in this asset's series — counted in **distinct dates**, never two same-day reports counted as two — *permissive* when it has stood TRUE, *blocking* when it has stood FALSE.

(c) **Binding axis** — for each phase not yet filled, the score points short AND the gates short, with the binding one named.

Three consequences follow, and only these three. **First:** a **VACUOUS-FALSE** phase may not be named as a forward catalyst, conditional trigger, or relight path in §5/§8/§12 — it is stated as structurally out of reach and dropped from the forward narrative until its ceiling changes. **The Deep-Value Override is exempt from this consequence**: it is evaluated mechanically every report regardless of any vacuity tag on its ≥15 arming line, and tagging that line never bars evaluating, reporting, or firing it — an Override nobody tracks is how it shipped decorative in Jun 2026, this framework's costliest documented failure. **Second:** a vacuous compound-stop score line is disclosed beside the stop **in its correct direction**. *Permissive* (the mechanical score has stood below the line, as BTC/ETH have at 10–11 against 12): the score key is standing satisfied, so the stop is **effectively single-key and price-gated** — which makes it fire **more** readily, not less; the live exposure is ejection on a price break with fear and value intact (the Jun-6-2026 pattern, the low within ~1.9% of the stop at score 16 / F&G 9), not an under-protected book. *Blocking* (gold's `<8` against a realized mechanical 8–10): *"the score key cannot turn; the compound stop cannot fire on this leg — the catastrophic floor is the operative protection."* Neither tag is a defect claim against the line and neither may move one: **D6 governs it — the line may rise, never fall.** **Third:** no report may claim a change in posture, conviction, or lock-state from a movement on an axis that is not the binding one.

This audit is informational and one-directional, exactly as the gate-reachability disclosure is: it may **NOT** be cited to lower a score line, credit a leg, reduce a gate requirement or a denominator, or move any stop. A VACUOUS-FALSE finding argues for *silence about that phase*, never for cheapening it — the default conclusion remains that an unreachable line is correctly unreachable. The ban on aggregate "effective board X/N" gate denominators is unchanged; nothing here runs on the gate board.

**Scope pin (2026-08-06).** This clause carries **no fill-time stop label, no new machine-block stop field, and no distance-to-line or ADR figure** — the dedicated compound-stop degeneracy tune carrying those was **rejected** in this same pass (its wording inverted the polarity of the disclosure, it duplicated the remedy already at the compound-stop bullet, and its machine field was unenforceable). Nothing here re-admits it. **Enforcement:** `lint-report.mjs` has no ceiling, vacuity or binding-axis field, so this ships as **prose discipline**, the same standing caveat the 2026-08-05 entry-zone ratchet carries; optional `score.attainable_ceiling` / `score.line_states[]` fields are the natural next tune.

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

**EV Calibration Line (2026-08-06 — the forecast layer must be graded, not merely printed).** The framework grades its analyst (D7) and has never graded itself. Every report therefore carries a one-line running scorecard for **this asset's own series**: the prior report's EV-vs-spot **sign and magnitude**, the **realized spot change since that report**, whether the sign was right or wrong, and the **current same-sign streak length with its hit rate**.

**Inputs, defined machine-side so the counter is not an opinion.** *Realized spot change* = this report's canonical spot vs the prior report's canonical spot, both read from the machine blocks. *Contradicted* = sign(realized change) opposite to sign(prior EV-vs-spot). A report carrying **no** machine-block `vs_spot_pct` counts as **UNKNOWN**: it neither extends nor breaks a streak and is never read as a sign flip. Streaks are counted over **distinct report dates** in this asset's series (two same-day reports are one date). Where the streak is counted by hand rather than off `exports/signal-feed.json`, say so — the feed's history is not uniformly populated (see the 2026-07-29 standing caveat).

When the same sign has run for **≥5 consecutive report dates in this asset's series** *and* realized price has contradicted it in the **majority** of them, the report must state on the record that the EV is running as a **systematic bias, not a forecast**, and must then do exactly one of two things:

- **(b) Demote EV to corroborative-only — this is the DEFAULT.** A demoted EV may still be printed, but it may not carry a stance, may not be cited as the reason for a deploy or a decline, and — one-directionally — **may not lift the report out of the Verdict-Confidence Collar**: an |EV-vs-spot| ≥2% reading that has been demoted does not satisfy the collar's EV branch, so the collar stays ON.
- **(a) Re-derive the target bands from current structure — available ONLY when a realized trend-structure event is cited on the same line** (a major-MA weekly reclaim or a confirmed higher-low — the Verdict-Confidence Collar's own strong-claim unlock, and the same bar the §5 Rally >50% cap already uses). Bands must be measured this report, never carried forward from the prior one, and the report says what changed. Without that realized event the report takes (b). *Rationale: a bear-market bounce produces exactly this signature, and an ungated (a) is a mechanical instruction to raise targets into a bounce — Principle 3 exists because of the May-14-2026 failure.*

**The streak resets only on a genuine sign FLIP.** Re-deriving under (a) does **not** reset it; the counter keeps running and the report prints "streak N, bands re-derived at report K." A self-silencing tripwire would reward the branch (a) is being constrained by.

*(Evidence, corrected to the record: BTC 2026-07-04 → 08-05, EV-vs-spot negative in **15 consecutive machine-block reports** spanning −0.8% to −2.53%, plus 3 earlier reports with no machine-block field, while spot rose +4.3%. ETH: **15 consecutive negative machine-block prints**, 07-11 → 08-05. GOLD: the Rally cell pinned in a **16–22% band across the ten in-window reports carrying a Rally row**, into a realized breakout to $4,260.60. Nothing in the framework noticed, because nothing was counting.)*

**Enforcement and provenance:** this is the framework's first rule requiring cross-report state, and no linter check exists for it — it ships as **prose discipline**. The ≥5 constant is **N=1**, imported from this window and of the same order as D1's >3 decay bar; re-grade it next cycle.

Final matrix:

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|

Probabilities **must** sum to 100%. **Weighted Expected Value** = Σ (probability × midpoint of target range). State EV explicitly, and EV-vs-spot %. **Disclose the realized trailing-2-week price change next to the EV claim, in BOTH directions (symmetrized 2026-08-06)** — a positive EV printed during a −X% two-week move must say so, **and a negative EV printed during a +X% two-week move must say so**, so the reader sees where the EV contradicts realized momentum. The old one-sided wording named only the bullish failure mode and was silent on the bearish one, which is the failure mode that actually ran.

**EV sign attribution (2026-08-06).** Whenever EV-vs-spot is **negative**, print the per-scenario contribution — `probability × (midpoint − spot)/spot` for all four cells, summing to the stated EV-vs-spot — and state in one line which term carries the sign: **probability weight** or **band distance**. If the modal cell is Range with its midpoint at or above spot while the sign is carried by the Retest/Bear bands' *distance* from spot, label the read **geometry-driven — a risk-adjusted number, not a directional forecast**. A geometry-driven EV may be reported and may inform sizing **downward only** (it can never authorize more than the phase's nominal size, which §6 already caps), but its **sign alone may not be the stated basis for a stance**; a stance must rest on score, gates, zone, structure, or a named risk. Nothing here authorizes a fill — score, gate count, [V] floor and the entry-zone ratchet bind unchanged, and **staying dry remains available for any reason**.

**Non-dissolution clause (mandatory).** The geometry-driven label is **diagnostic only**. It may NOT satisfy, weaken, or dissolve the §5 EV-floor consistency check; may NOT lift a report out of the Verdict-Confidence Collar or over its strong-claim bar; and may NOT substitute for the terminal-vs-extreme reconciliation. A flagged EV-floor inconsistency is resolved by **re-examining inputs, never by relabelling the EV** — that check exists precisely because a negative EV at mechanical ≥15 / F&G ≤15 would otherwise fight the Deep-Value Override, and an explanation is not a resolution.

*(Worked case — SOL 07-18: 0.16×86 + 0.34×76 + 0.32×67 + 0.18×56 = 71.12, −5.1% vs $74.94 spot, arithmetic exact. Bear's midpoint sat −25.3% from spot against Rally's +14.8%. Robustness, stated honestly: the sign survives a 5pp Bear→Rally reweight (−3.1%) and a wholesale reallocation of the Bear cell into Range (−0.3%); it flips only if the entire Bear cell is reassigned to Rally (+2.1%). The number was measuring fat-tailed downside geometry and was being read as a price forecast.)*

**Enforcement:** the decomposition is derivable from cells the machine block already carries, but `lint-report.mjs` does not currently check it — this ships as **prose discipline**. Wiring `evCheck` to recompute the per-scenario contributions from the existing `ev.scenarios[]` and having the linter verify they sum to the stated EV-vs-spot is a zero-schema-cost follow-up and the recommended next tune.

### 6. Deployment Strategy

Splits: **10 / 15 / 30 / 45** (front-loaded pyramid — bigger tranches at deeper drawdowns, where reward-to-risk is highest). State **total dry powder %** prominently.

**Partial-tranche deployment (Jul 2026, codifies the BTC Jul-9 practice):** an unlocked phase authorizes UP TO its nominal size; deploying a stated fraction (e.g. a half-size 1B) laddered within the zone is permitted, with the remainder staying assigned to that phase and deployable in the same zone without a fresh unlock. Upsizing beyond nominal is prohibited. (Override firings keep their own half/quarter-size rules.)

**Entry-zone ratchet — an unlock is not a licence to buy at any price (2026-08-05, codifies the ETH Jul-2→Jul-10 and BTC Jul-2 practice).** An unlock authorizes a tranche **inside its named zone only**. No tranche fills above its zone top: an authorized phase whose zone sits below spot is a resting ladder, never a market order, and if price never returns the tranche stays dry and the framework accepts missing it. **A re-anchor is not an unlock** — it moves where a tranche may fill, never whether it may; the phase's score line, gate count and [V] floor bind unchanged.

**Downward re-anchoring is permitted but is not free.** Cheaper is always allowed in principle, but naming a deeper zone lowers the deepest named buy-zone floor, so it runs the **stop-vs-buy-zone coherence check** in full (below), and any resulting move of the catastrophic floor is governed by **D6 exception 1** — a downward re-anchor onto a zone **named in a prior report in this asset's series**, executed atomically and cited. A deeper zone named for the first time in the current report may be published as a *prospective* ladder but may not authorize a stop move; until it is carried forward and activated under exception 1, the report runs the check in both states and carries the "stop realignment owed" flag if the post-activation state fails.

**Upward re-anchoring is the constrained direction** and requires ALL of: **(a)** **≥5 consecutive daily closes above the new shelf** — never on the session of the move that motivates it; a single-day breakout, gap, or squeeze is not a shelf. Five is a **floor**: an analyst may require more and log why, never fewer. **(b)** The new zone **top sits below spot at naming** (ETH Jul-9: top $1,730 named at spot $1,753). **(c)** A stated **void condition** — a daily close back below the shelf cancels the re-anchor and reverts to the prior, deeper zone, which stays named in the report as the fallback for as long as the re-anchor is live. **(d)** Because (c) keeps the prior deeper zone named, the existing coherence-check rule — *"if multiple prospective zones are named with different floors, use the single LOWEST floor"* — already makes that deeper floor the tested number and the `stops.deepest_zone_floor` input. This is a restatement of that rule, not a new stop parameter: **an upward entry re-anchor may never raise the number the coherence check is run against**, and it creates no stop obligation of its own. Log every re-anchor, both directions, in the Discretion Ledger with its trigger and void condition.

**The Deep-Value Override is exempt** (`⚑ Deep-Value Override`, below). Its price condition is anchored to the **most-recently-deployed tranche's blended cost**, not to the next tranche's zone, so an 8%-below-basis firing with a fresh lower-low will routinely print *between* zones — above the next tranche's zone top and far below the last fill. Blocking it there would restore precisely the unreachability the Jun-2026 re-anchor removed (the $59,110 print, 9.1% below a $65K basis, at score 16 / F&G 9). An Override firing is a deepening buy, never a chase: it **names its own entry band at or below the trigger print** in the report that fires it, and that band becomes a named zone for every rule above from that report forward. Its half/quarter sizing, its ≤1-per-5-days throttle and its ≤25% cap are unchanged.

*(This clause is **FK-only**. Flying Rocket entry zones sit **above** spot by design; mirroring "no fill above the zone top" there would be a loosening, not a discipline — do not port it, per Hard Rule 6.)*

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
- **[R:phase2-corr-cap] Unlock gates:** adjusted score ≥15 *(score line unchanged 2026-07-27 — but note both analyst channels do reach this tranche: a D1 term can carry the score across ≥15 and a D2 case can substitute the sixth gate. Any such fill is discretionary and takes the D5 stop.)* AND **[ (≥6 of 9 gates ✅ — or `ceil(2/3 × active denominator)` when any gate is N/A — with ≥3 from the **[V]** bucket) OR (Deep-Value Override fires) OR (D2 Analyst Conviction Path — gate count short by exactly 1, [V] floor met, half nominal, D5 stop) ]** AND correlation regime not "risk-on extreme" (corr <0.8). **Corr input rule (Jul 2026):** when Phase 2 is otherwise unlocked, sourcing/computing the 30d correlation becomes mandatory (it is Required Data Fetch #8); if a documented attempt genuinely fails, the corr condition defaults to PASS (mirror of the surcharge's "defaults OFF — never penalize on a guess"), with the mandatory line "corr unverified — risk-on-extreme untested." An unattempted fetch blocks Phase-2 deployment (Hard Rule 1). *(The "macro neutral-to-positive" condition is now a **[T]** sizing/timing input, not a hard veto — it structurally turns off in every fear spike, which is when this tranche is supposed to fire.)*
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

#### Position & Performance (Hard Rule 8 — read the ledger before sizing anything)

Run `node tools/position.mjs <asset>` **before** writing this section. It reads the position snapshot exported from the personal-accounting ledger — derived from actual Binance fills, not from what a prior report said. This replaces the narrated carry-forward line that used to open §6.

**Exit 0 / band FRESH (event-driven validity; no default age expiry): these figures are the position of record and supersede any number carried forward from a prior report.** The owner exports after every new trade, so elapsed time alone does not make an unchanged position stale. Print, from the snapshot:

- **Position of record** — real quantity, ACB cost basis (`avg_cost_usd`), total cost, unrealized PnL, and each holding's **attribution** (its deal tag, e.g. `FK-P1A`, or `UNTAGGED`).
- **Real dry powder** — `dry_powder.stable_balance_usd`. This is the sharp one. A phase plan sized as "45% of the book" against a book that was never measured is arithmetic, not a plan; check the tranche against an actual balance. Note that `dry_powder` **excludes futures-wallet collateral** (already counted as equity) and **includes** stablecoins locked in resting orders.
- **Realized performance** — closed round trips for this asset with realized PnL and hold time, plus win rate / profit factor / expectancy overall and **per tag** (`performance_by_tag`, and `performance_by_tag_prefix` for the whole framework). State how Phase 1A entries have actually performed; do not assert it.
- **Position Reconciliation** — where the prior report's narrated figures diverge from the ledger, naming the delta. **The ledger wins.** The line survives from the old convention with its meaning inverted: it used to reconcile the ledger against the report, and now it flags where the report drifted.

**Band STALE:** occurs only under an explicit `--max-age-min` strict-time audit or because futures coverage/status is incomplete. It is usable **descriptively** — what is held, what it cost, how past trades performed — but may **not** satisfy a phase-dependent unlock precondition or fill a realized ledger column.

**Exit 1 (missing/invalid snapshot, missing `generated_at`, explicit strict-time expiry, or incomplete required coverage) or exit 2 (NOT_COVERED — an asset with no ledger counterpart and no alias):** say so in one line and proceed as a **cold start under Hard Rule 4**, or carry state forward from the prior report for a not-covered asset. Never expire a valid default snapshot merely because its timestamps are old. **Never read a zero position out of a NOT_COVERED response** — a flat position and an unknown position lead to opposite decisions. Refuse the position claim, never the report.

**Governed verbatim by AGENTS.md Hard Rule 8** (already in context every report) — the gold→PAXG alias, the `custody.status` literals (`RECONCILED` / `EXPLAINED_BY_EXTERNAL_TRANSFER` / `EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE` / `UNEXPLAINED`) and their handling, and `basis.reliable` as a question separate from custody — read `custody.status` before any quantity, and `basis.reliable` separately from it. *(Stage 2 pointer, 2026-08-07 — this paragraph and the enumerated custody-status/basis rules below it were byte-identical to Flying Rocket's copy and both restated Hard Rule 8; kept here only if it ever needs to diverge from AGENTS.md.)*


**Two carve-outs survive even at FRESH, and only two.** (a) Snapshot `mark_price_usd` is **informational** and never becomes this report's canonical spot — Hard Rule 1's independent multi-venue cross-check stands. (b) Phase attribution comes from **deal tags only**; an untagged holding is reported as real-but-`UNTAGGED`, never inferred from quantity or timing, because a guessed phase can unlock the next tranche.

#### Open-position “Absolutely Best Level” rubric (mandatory when any long is open)

Run this audit on **every report with a non-zero open long**, whether framework-authorized, `UNTAGGED`, or `UNFRAMED`. The phrase “absolutely best” is the user's requested label, not a certainty claim: the report must say **“no level is absolutely best; this is the best available control under current evidence.”** Research with fresh instrument and underlying data before carrying forward any prior stop, trim, or take-profit.

**Evaluate the primary action first:** `EXIT NOW` / `TRIM` / `HOLD` / `ADD ONLY IF UNLOCKED`. A protective level or take-profit ladder cannot suppress a live §7 exit/trim trigger, manufacture an add, or replace the score/gate/Override machinery.

Build at least these candidates:

1. Actual live venue stop/order state from the ledger.
2. The prior report's ratcheted catastrophic/compound/D5 controls (the D6 baseline; keep the tiers separate).
3. Contract-native structural invalidation: swing low, failed support, major MA, gap/breakdown level, or the thesis's named floor.
4. Volatility control using current ADR/ATR and distance from ordinary wick noise.
5. Deepest named buy-zone floor and the mandatory stop-vs-buy-zone coherence state.
6. Underlying/reference-market equivalent, cross-checked against the traded instrument. **Execution is anchored to the traded instrument**; never transfer a spot/index level through an assumed ratio without verifying the contract itself.
7. Event/gap/liquidity control through the time stop.
8. `EXIT NOW` / the mechanically required §7 trim at the live executable price.

Score every non-vetoed level on a 10-point board:

| Dimension | Points | Test |
|---|---:|---|
| Thesis invalidation | 0–3 | Does crossing the level actually disprove the long thesis or activate the correct compound/D5/narrative rule? |
| Noise survival | 0–2 | Is the level robust to ordinary ADR/ATR noise and consistent with the stop tier? |
| Execution quality | 0–2 | Correct instrument, trigger/close semantics, quantity coverage, realistic slippage? |
| Capital/ladder coherence | 0–2 | Loss in USD/% book stated; catastrophic floor remains below the deepest named buy zone; D5 remains tranche-specific? |
| Event/liquidity robustness | 0–1 | Does it account for the next tier-1 catalyst, venue calendar and gap risk? |

**Hard veto before scoring:** any control that loosens D6 without one of its three named exceptions; places the catastrophic floor at/above the deepest named buy zone; replaces a mechanical/Override compound stop with an ordinary price-only stop; weakens a D5 stop; uses stale/unmapped pricing as fact; or conflicts with a live §7 exit/trim trigger. A higher point total never rescues a vetoed candidate. Ties resolve toward the **tighter valid protection** on the long side (the higher valid stop / earlier valid checkpoint), subject to stop-tier coherence.

Then research a **trim / take-profit ladder** from contract-native resistance, moving averages, gaps, volume structure, valuation/euphoria thresholds, scenario bands and the time stop. Print each target's price, quantity, position share, expected realized PnL after known fees, and its governing §7/valuation/structure condition. Quantities may not exceed the remaining position. A round-number target alone is not evidence; reject a sole remote target absent from the probability matrix or unlikely inside the stated horizon. Trims remain LIFO and may not be used to claim an exit trigger fired when it did not.

Every live-position report prints an **Open-Position Best-Level Audit** containing: data timestamp; primary action; candidate table with scores/vetoes; selected best-available protective control by tier; current venue order versus recommended order; trim/target ladder; trigger/close semantics; loss at stop; event calendar; D6 ratchet and stop-vs-buy-zone checks; and alternatives rejected. Recommendations are not executions—say explicitly whether any live order was actually changed.

Optional machine encoding (recommended until lint enforcement exists):

```json
"position_controls": {
  "required": true,
  "status": "BEST_AVAILABLE|NO_VALID_LEVEL|DATA_LIMITED",
  "certainty": "best-available-not-absolute",
  "primary_action": "EXIT_NOW|TRIM|HOLD|ADD_ONLY_IF_UNLOCKED",
  "selected_controls": {},
  "current_venue_stop": null,
  "targets": [],
  "ratchet_pass": true,
  "stop_zone_coherence_pass": true,
  "orders_changed": false,
  "data_as_of": null
}
```

This audit changes no score, gate, tranche size, Override rule, stop tier, time stop, trim/exit trigger, D5 tax or D6 ratchet. Those rules remain authoritative.

#### Ledger tag registry (print in every report; activate on fill)

Every Fallen Knives report — crypto or adapted non-crypto derivative — prints the applicable canonical tag registry alongside the phase status table. A **reserved** tag is report metadata, not evidence of a fill. `active_tags` remains empty when no tranche is authorized or filled. When a tranche does fill, activate the exact tag on the corresponding round-trip deal; this is what connects a real position back to the tranche that authorized it. The ledger stores quantity and cost basis, but `crypto_trade` has no tranche dimension and never will.

Non-crypto scope does not remove the tag vocabulary. If the chosen venue supports a derivative expression, an adapted non-crypto tranche uses the canonical `FK-*` tag scheme. If the position snapshot cannot reconcile that instrument, disclose the accounting/size-cap coverage gap separately; do not call the derivative untaggable and do not invent a live position.

An untagged **live** holding is a real position with unknown attribution; it cannot resolve a phase-dependent unlock precondition.

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
- **Single-observation durability:** do not promote one data point to structure. A claim that a **fear regime has ENDED** (e.g., "$80K converted to support," "flows flipped supportive") requires the framework's existing confirmation bars — ≥5-session flow trend for flows, Principle 3's trend-reversal confirmation for levels. Claims that *reinforce* the prevailing fear/down regime may use fewer observations (a correct fast bearish read is not penalized).
- **Tier-1 macro calendar-lock on short-horizon prose (Jul 2026).** The watchlist's fetched tier-1 release line (Required Data Fetches) is checked just-in-time whenever a short-horizon declarative comparative claim ("the more likely outcome", "not a V-recovery", "path of least resistance") is about to be written. Any such claim whose resolution window contains a tier-1 release must name it and use event-conditional phrasing: "pre-[event]; claim resolves conditional on [event], [date/time]". If the calendar fetch fails, the report may still print such claims but must flag them: "calendar-unverified — treat as base case, not primary call."

## Score Interpretation

| Score — adjusted, except the 17+ rows which are mechanical (Phase 3's ≥17 arming) | Phase | Stance |
|---|---|---|
| 0–5 | No Signal | OBSERVE — insufficient fear, or active distribution regime |
| 6–7 | Early Warning | PREPARE — build watchlist, refresh thesis |
| 8–10 | Accumulation Zone | CAUTIOUS ENTRY — Phase 1A eligible |
| 11–14 | Building | Phases 1A–1B eligible |
| 15–16 | Strong Signal | SYSTEMATIC DEPLOY — Phases 1A–2 eligible |
| 17–19 | Historic Opportunity | AGGRESSIVE DEPLOY — Phases 1A–2 on the adjusted score; Phase 3 requires **mechanical** ≥17, separately |
| 20 | Maximum Signal | FULL DEPLOY — all phases, subject to Phase 3's mechanical-≥17 gate |

*(Re-banded 2026-07-27 to track the cut unlock lines — 1A at ≥8, 1B at ≥11; Phases 2/3 unchanged at ≥15/≥17. The table has always been the follower, §6 the operative text. **Corrected 2026-08-07 (C3):** the header and the 17–19 row previously read "Adjusted Score" / "Phases 1A–3 eligible" without qualification, contradicting §6's Phase 3 unlock (mechanical ≥17) and the governing D1 rule above. The table never overrode §6 — §6 was always operative — but a table that reads wrong is a live footgun on the 45% tranche regardless of which text is authoritative. FR's mirror table (§ Score Interpretation) already carried the correct wording; this brings FK in line with it.)*

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
3. **Bounces within a downtrend are suspect** — never declare a fear cycle "closed" on a rally. Require **trend-reversal confirmation** *(named distinctly from the "trend-structure repair" / "realized trend-structure event" bar used at §5's Rally cap and the trend residual — Principle 3 asks for a confirmed **higher-high**, the stricter of the two; do not conflate the names or read one bar as satisfying the other)* — reclaim of a major MA or a confirmed higher-high — before downgrading the accumulation thesis. *(May 14 2026: the framework called the fear window "already closed for this leg" at $79.5K — right before a −23% drop to F&G 9. The bounce was a bull trap; the deeper leg was the real signal.)*
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
| Market flow / CVD / OI | CoinGlass API (cross-exchange; `COINGLASS_API_KEY`) | Binance public spot + USD-M fallback | Coinalyze |
| Oil/Macro | CNBC, Reuters | Bloomberg, Investing.com | Yahoo Finance, Fortune |
| Geopolitical | Reuters, AP, Al Jazeera | CNBC, BBC, FT | Wikipedia (live-updated conflict pages) |
| Correlation | CoinMetrics, TradingView | Computed from price series | — |

## Output

Save the report as a markdown file to:

```
reports/[asset]_fallen_knives_[YYYYMMDD]_[HHMM].md
```

Path is repo-relative, per AGENTS.md's Output Convention (which also governs the post-save commit/push). Filename uses lowercase asset symbol. Example: `btc_fallen_knives_20260514_0930.md`.

**Machine contract + lint (mandatory).** Historical report-machine/1 and report-machine/2 reports retain their fenced machine block and legacy lint rules. New swing reports use report-machine/3: the canonical JSON sidecar is paired with compact Markdown and has **no embedded machine block or visible tag registry**. Run `node tools/finalize-report.mjs`, `node tools/render-report.mjs`, `node tools/lint-report.mjs`, and `node tools/export-signals.mjs`; the v3 linter checks score/trigger/veto/risk arithmetic, the sidecar hash footer, and the removed-section contract. A dry or locked v3 setup retains tags only in its sidecar; no tag registry is printed in Markdown.

**Fill encoding — required on every filled tranche (added 2026-07-29).** A tranche you actually filled carries **numeric `entry_price`** (and, when useful, `deployed: true`). The prose `entry` field keeps its own job — which zone, why blocked, blended MTM — and both are wanted; they answer different questions. This is not bookkeeping: **every mechanical check is gated on the fill predicate**, so a fill written only as prose silently skips its score unlock line, its gate floor, the D5 stop bound, the 40%/25% caps and the ratchet. That was the state of the world until 2026-07-29 — 152/152 tranches across 39 reports were prose, and `deployed: true` had never once appeared, which made all of it unreachable code. The linter now **warns** when a prose `entry` reads like a fill without an `entry_price`, and **errors** on any report dated on/after 2026-07-29.

**Feed export.** Post-save workflow (lint → export-signals → commit + push) per AGENTS.md's per-report workflow and Auto-push convention. Commit the feed alongside the report when it moves.

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

English by default. Russian on explicit user request. Default: English. Ask only if ambiguous.


## Framework Revision Log

**Relocated 2026-08-07 → [`REVISION-LOG.md`](REVISION-LOG.md)** (sibling file, same directory).

It had grown to 30% of this file and was loaded into context on **every report run**, while every one of its readers is calibration-time: `tools/calib-run.mjs` (prior-tune re-validation, pre-apply audit) and the `framework-calibration` skill. No report-time rule lives there — an applied tune edits the operative text above, and the log entry is its provenance receipt. Verified at relocation: zero `every report must` / `the report must` mandates in the log, and the two operative-text citations of it are provenance only (§4's ceiling note says outright that the log's figures are not operative constants and the report's own arithmetic governs).

**Do not re-inline it, and do not compress it into a summary.** The enumerated mechanism lists are what make prior-tune re-validation gradeable — the 2026-08-06 run graded 36 nameable mechanisms out of the 2026-07-04 entry. An entry that loses its mechanism names stops being testable, which is the coverage hole `postCalibrationBoundary` exists to prevent. A calibration **appends** a dated entry to the sibling file; historical entries are never silently renumbered or rewritten (see the 2026-08-06 rejection of exactly that), and corrections are dated in place.
