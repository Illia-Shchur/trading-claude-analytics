# Trading Claude Analytics

Personal workspace for crypto market analysis using the **Fallen Knives** framework — a disciplined system for accumulating during extreme fear and trimming during euphoria, applicable to any crypto asset.

## Skills

- **`fallen-knives-analytics`** (`.claude/skills/fallen-knives-analytics/SKILL.md`) — **long-side / accumulation** framework. Composite scoring, phased deployment, and symmetric exit framework. Triggers: "fallen knives", "update fallen knives [asset]", buy/hold questions on any crypto, accumulation/exit planning, fear readouts. Default asset is BTC; supports ETH, SOL, major alts, smaller alts.
- **`flying-rocket-analytics`** (`.claude/skills/flying-rocket-analytics/SKILL.md`) — **short-side / distribution** framework. Inverse companion to Fallen Knives. Composite scoring with stricter unlock thresholds (shorts demand more confirmation), smaller phase sizes (cap 50% of short book), mandatory price + time stops on every phase, explicit carry-cost ledger, symmetric cover framework. Triggers: "flying rocket", "rocket update", "where/when to short", top-signal assessments, euphoria/blow-off readouts, distribution analysis. Default asset is BTC; supports ETH, SOL, major alts. **Smaller alts require explicit user confirmation** before short recommendations (borrow + squeeze risk).

## Hard Rules

1. **Live data is non-negotiable.** Before producing any Fallen Knives output, fetch fresh data from the internet for price, sentiment, ETF flows (where applicable), macro, on-chain, and breaking news. Memorized data is never acceptable. Every figure must carry source + timestamp.
2. **Symmetric discipline.** The framework is not buy-only. Trim/exit triggers are first-class and must be evaluated every report, especially when positions are held.
3. **Pyramid splits are 10 / 15 / 30 / 45** across Phases 1A / 1B / 2 / 3. Front-loaded — bigger tranches at deeper drawdowns. 
4. **Cold start defaults to all dry powder.** Never assume prior deployment unless a prior report or the user explicitly confirms a position.
5. **Cross-validation between frameworks.** Fallen Knives and Flying Rocket scores on the same asset at the same timestamp must be **inversely related**. If both score ≥12 simultaneously, the framework is internally inconsistent — pause, re-examine inputs, and flag this in the report rather than acting on either signal.
6. **Asymmetric humility on shorts.** Flying Rocket uses stricter thresholds (Phase 1A unlocks at score ≥13 vs ≥10 for longs), smaller phase sizes (5/10/15/20, capped at 50% of short book vs 10/15/30/45 for longs), and mandatory price + time stops on every tranche. This is the framework's asymmetry tax — never relax it.
7. **Sync git before every analysis.** Run `git fetch origin` (and confirm the working tree is in sync with `origin/main`) before producing any report, so each analysis builds on the latest committed state. Pair this with the Auto-push convention below: fetch before, commit + push after — every report, without being asked.

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

