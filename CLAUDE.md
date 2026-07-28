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
4. **Cold start defaults to all dry powder.** Never assume prior deployment unless a prior report or the user explicitly confirms a position. *(Amended 2026-07-28: this is the rule for the **absence** of evidence. A FRESH ledger snapshot is evidence and supersedes it — see Hard Rule 8. Rule 4 continues to govern the STALE / EXPIRED / NOT_COVERED cases, which is most of them.)*
5. **Cross-validation between frameworks.** Fallen Knives and Flying Rocket scores on the same asset at the same timestamp must be **inversely related**. If both score ≥12 simultaneously, the framework is internally inconsistent — pause, re-examine inputs, and flag this in the report rather than acting on either signal.
6. **Asymmetric humility on shorts** *(re-sited 2026-07-27, owner-directed — see the FR revision log)*. Flying Rocket still pays a strictly larger asymmetry tax than Fallen Knives, but it is now levied on **risk control** rather than on the entry threshold: smaller phase sizes (5/10/15/20, capped at 50% of short book vs 10/15/30/45 for longs), mandatory price **and** time stops on every tranche, a ratchet that lets stops and clocks move toward price only, a 30% sub-cap and no Phase 3 in the bear-continuation channel, a 20% cap and a 14-day clock on analyst-discretion entries, and no short-side Deep-Value Override — ever. The entry spread survives too (Phase 1A: short ≥11 vs long ≥8, a *wider* 3-point gap than the 2 points it replaced). **What may never be relaxed:** a stop, a time stop, a size cap, the ratchet, a cover trigger, the carry veto, or the funding veto gate. Threshold changes are evidence-driven and reversible; those seven are not up for negotiation, and a proposal that unbundles them from a loosening elsewhere is rejected at apply-time.
7. **Sync git before every analysis.** Run `git fetch origin` (and confirm the working tree is in sync with `origin/main`) before producing any report, so each analysis builds on the latest committed state. Pair this with the Auto-push convention below: fetch before, commit + push after — every report, without being asked.
8. **A fresh ledger is the position of record** *(added 2026-07-28)*. `node tools/position.mjs <asset>` reads `position-snapshot.json`, exported from the personal-accounting ledger and derived from actual Binance fills. When **FRESH** (≤12 h), its figures — quantity, ACB cost basis, unrealized and realized PnL, dry powder, open futures positions, fills, closed round-trips and performance stats — are stated as fact and **supersede any narrated position figure carried forward from a prior report.** Hard Rule 4's dry-powder default governs only the absence of fresh evidence.

   Two carve-outs, and only two. **(a) Prices:** snapshot marks are informational and never become canonical spot — Hard Rule 1's independent multi-venue cross-check stands, and sourcing spot from your own database would defeat it. **(b) Phase attribution:** the ledger knows what you hold, not which tranche authorized it; attribution comes from deal tags, and an untagged holding is reported as real-but-`UNTAGGED` rather than guessed into a phase.

   Freshness bands: ≤12 h **FRESH**; 12–72 h **STALE** (descriptive use with an age banner — may not satisfy a phase-dependent unlock precondition or fill a realized ledger column); >72 h or missing **EXPIRED** (cold start per Rule 4, stated explicitly). Age is the **older** of `generated_at` and `holdings_as_of` — `crypto_holding` refreshes only on `POST /link`, so a file written a minute ago can be valuing week-old balances. **Gold reads through a ledger alias** *(2026-07-28)*: the ledger cannot hold bullion, so `gold` resolves onto **PAXG**, tokenized gold tracking XAU ~1:1. The response carries `requested_asset: GOLD`, `ledger_asset: PAXG` and an `alias_note`, and a report states that its gold position is held as PAXG — the alias is disclosed, never silently resolved, because PAXG carries issuer/custody counterparty risk that spot gold does not and can trade at a premium or discount. Canonical gold SPOT still comes from Hard Rule 1 sources; carve-out (a) is untouched. An asset with **no** alias and no ledger counterpart still returns `covered:false` — never read a zero position from a NOT_COVERED response. Every report reading the snapshot prints a **Position Reconciliation** line flagging where prior narrated figures drifted from the ledger; the ledger wins.

   Rule 8 changes no score, band, threshold, stop or cap, and touches none of Hard Rule 6's seven non-negotiables. **Refuse the position claim, never the report** — a missing snapshot routes into Rule 4's safe default, not into a stall.

## Deterministic Tooling (`tools/`)

Node scripts (no deps) that make report numbers computed instead of narrated — see `tools/README.md` and the "Deterministic toolchain" sections in the framework SKILLs (which are authoritative for what is mandatory):

- `node tools/fetch.mjs btc|eth|sol|gold|macro` — live numeric backbone (cross-checked spot, ATH/drawdown, weekly Wilder RSI-14, 200-week SMA, ADR sessions, F&G streaks, real yield/VIX/DXY/Brent), source + timestamp on every block. Does NOT cover ETF flows / on-chain / news — those stay live web fetches (Hard Rule 1).
- `node tools/position.mjs btc|eth|sol|all [--file <path>] [--max-age-min N] [--fills N]` — the Hard Rule 8 position of record: real quantity, ACB cost basis, dry powder, open futures, fill-level history, closed round-trips, per-tag/per-prefix win rate. Exit `0` FRESH/STALE, `1` EXPIRED or missing (cold start per Rule 4), `2` NOT_COVERED (gold — never a zero position). Run it **before** `fetch`: what you already hold changes what the report is deciding.
- `node tools/compute.mjs …` — rubric bands, ceil gate thresholds, per-asset rounding, EV sum-check, stop coherence, ADR, FR funding/cycle-cap.
- `node tools/lint-report.mjs reports/<file>.md` — validates each report's ` ```json machine ` block after save, before commit. A FAIL is fixed, never committed around. **A filled tranche must carry a numeric `entry_price` (or `deployed: true`)** — without it the score unlock line, gate floor, stop band, size cap and ratchet are all skipped. A prose `entry` that reads like a fill warns before 2026-07-29 and errors on/after.
- `node tools/export-signals.mjs` — regenerates `exports/signal-feed.json`, the committed A→B contract read by the personal-accounting ledger. Run after `lint`, before `commit`; it writes only when content actually changed, so a no-op run leaves `git status` clean. `--dry-run` for counts, `--strict` to fail on a post-epoch report with no machine block.
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

**Auto-push:** After saving each report, run `node tools/export-signals.mjs`, then `git add` the new report file **and `exports/signal-feed.json` if it changed**, commit with a descriptive message, and `git push` to `origin/main`. One commit per report. Do this without being asked.

Full per-report workflow: `position` → `fetch` → `compute` → save → `lint` → `export-signals` → commit + push.

## Asset Defaults

- If no asset specified → **BTC**
- If context is ambiguous between BTC and another asset → ask once, then proceed
- Multi-asset reports are allowed but produce one file per asset

