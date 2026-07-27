# Trading Claude Analytics

Personal workspace for crypto market analysis using the **Fallen Knives** framework — a disciplined system for accumulating during extreme fear and trimming during euphoria, applicable to any crypto asset.

## Skills

- **`fallen-knives-analytics`** (`.claude/skills/fallen-knives-analytics/SKILL.md`) — **long-side / accumulation** framework. Composite scoring, phased deployment, and symmetric exit framework. Triggers: "fallen knives", "update fallen knives [asset]", buy/hold questions on any crypto, accumulation/exit planning, fear readouts. Default asset is BTC; supports ETH, SOL, major alts, smaller alts.
- **`flying-rocket-analytics`** (`.claude/skills/flying-rocket-analytics/SKILL.md`) — **short-side / distribution** framework. Inverse companion to Fallen Knives. Composite scoring with stricter unlock thresholds (shorts demand more confirmation), smaller phase sizes (cap 50% of short book), mandatory price + time stops on every phase, explicit carry-cost ledger, symmetric cover framework. Triggers: "flying rocket", "rocket update", "where/when to short", top-signal assessments, euphoria/blow-off readouts, distribution analysis. Default asset is BTC; supports ETH, SOL, major alts. **Smaller alts require explicit user confirmation** before short recommendations (borrow + squeeze risk).
- **`framework-calibration`** (`.claude/skills/framework-calibration/SKILL.md`) — **meta / self-improvement** framework. Backtests a framework against its OWN prior reports, grades past predictions vs realized outcomes, adversarially refutes every proposed parameter tune, and **auto-applies the survivors** to the target SKILL with a dated revision log. Always runs the exhaustive multi-agent adversarial workflow (`backtest-workflow.template.js`). Triggers: "calibrate/recalibrate/retune the strategy", "backtest our predictions", "were our predictions right", "what parameters to tune", "reanalyse the reports and correct the strategy", any iterative-improvement request. Standing guardrails: never relax short-side discipline (Hard Rule 6), never loosen a capital guardrail into a falling knife, preserve what survived. Every calibration is N=1 — re-validate after the next full cycle.

## Hard Rules

1. **Live data is non-negotiable.** Before producing any Fallen Knives output, fetch fresh data from the internet for price, sentiment, ETF flows (where applicable), macro, on-chain, and breaking news. Memorized data is never acceptable. Every figure must carry source + timestamp.
2. **Symmetric discipline.** The framework is not buy-only. Trim/exit triggers are first-class and must be evaluated every report, especially when positions are held.
3. **Pyramid splits are 10 / 15 / 30 / 45** across Phases 1A / 1B / 2 / 3. Front-loaded — bigger tranches at deeper drawdowns. 
4. **Cold start defaults to all dry powder.** Never assume prior deployment unless a prior report or the user explicitly confirms a position.
5. **Cross-validation between frameworks.** Fallen Knives and Flying Rocket scores on the same asset at the same timestamp must be **inversely related**. If both score ≥12 simultaneously, the framework is internally inconsistent — pause, re-examine inputs, and flag this in the report rather than acting on either signal.
6. **Asymmetric humility on shorts** *(re-sited 2026-07-27, owner-directed — see the FR revision log)*. Flying Rocket still pays a strictly larger asymmetry tax than Fallen Knives, but it is now levied on **risk control** rather than on the entry threshold: smaller phase sizes (5/10/15/20, capped at 50% of short book vs 10/15/30/45 for longs), mandatory price **and** time stops on every tranche, a ratchet that lets stops and clocks move toward price only, a 30% sub-cap and no Phase 3 in the bear-continuation channel, a 20% cap and a 14-day clock on analyst-discretion entries, and no short-side Deep-Value Override — ever. The entry spread survives too (Phase 1A: short ≥11 vs long ≥8, a *wider* 3-point gap than the 2 points it replaced). **What may never be relaxed:** a stop, a time stop, a size cap, the ratchet, a cover trigger, the carry veto, or the funding veto gate. Threshold changes are evidence-driven and reversible; those seven are not up for negotiation, and a proposal that unbundles them from a loosening elsewhere is rejected at apply-time.
7. **Sync git before every analysis.** Run `git fetch origin` (and confirm the working tree is in sync with `origin/main`) before producing any report, so each analysis builds on the latest committed state. Pair this with the Auto-push convention below: fetch before, commit + push after — every report, without being asked.

## Deterministic Tooling (`tools/`)

Node scripts (no deps) that make report numbers computed instead of narrated — see `tools/README.md` and the "Deterministic toolchain" sections in the framework SKILLs (which are authoritative for what is mandatory):

- `node tools/fetch.mjs btc|eth|sol|gold|macro` — live numeric backbone (cross-checked spot, ATH/drawdown, weekly Wilder RSI-14, 200-week SMA, ADR sessions, F&G streaks, real yield/VIX/DXY/Brent), source + timestamp on every block. Does NOT cover ETF flows / on-chain / news — those stay live web fetches (Hard Rule 1).
- `node tools/compute.mjs …` — rubric bands, ceil gate thresholds, per-asset rounding, EV sum-check, stop coherence, ADR, FR funding/cycle-cap.
- `node tools/lint-report.mjs reports/<file>.md` — validates each report's ` ```json machine ` block after save, before commit. A FAIL is fixed, never committed around.
- `node tools/selftest.mjs` — regression vectors; run before calibrations and after any `tools/lib.mjs` change. `tools/lib.mjs` mirrors the SKILL rubrics letter-for-letter: a SKILL band change and its `lib.mjs`+`selftest.mjs` change land in the same commit.

## Output Convention

Reports are saved as markdown to:

```
reports/[asset]_fallen_knives_[YYYYMMDD]_[HHMM].md
reports/[asset]_flying_rocket_[YYYYMMDD]_[HHMM].md
```

- Asset symbol is lowercase (`btc`, `eth`, `sol`)
- Timestamp is local EST/EDT
- Example: `reports/btc_fallen_knives_20260514_0930.md`
- Example: `reports/eth_flying_rocket_20260514_1500.md`

After saving, follow with a ≤6-line conversational summary: adjusted score, top 1–2 changes vs prior, single most actionable item.

**Auto-push:** After saving each report, immediately `git add` the new report file, commit it with a descriptive message, and `git push` to `origin/main`. One commit per report. Do this without being asked.

## Asset Defaults

- If no asset specified → **BTC**
- If context is ambiguous between BTC and another asset → ask once, then proceed
- Multi-asset reports are allowed but produce one file per asset

