# Deterministic Toolchain

Node scripts (no dependencies, Node ≥18) that make the frameworks' numbers computed, not narrated. Introduced 2026-07-10 after the doc audit found the recurring failure modes were exactly the hand-done steps: RSI never computed (4-report NOT-FOUND debt), `ceil(7/9×8)` misprinted as 6 in three reports, EV sum-checks done by eye, ADR silently absorbing a half-session.

| Tool | Purpose |
|---|---|
| `lib.mjs` | Pure math: Wilder RSI-14, SMA, FK/FR rubric band classifiers (edge conventions codified: FK edges → higher-score band, FR edges → lower-score band per Hard Rule 6), `ceil` gate thresholds, per-asset .5 rounding, weighted EV + sum-check, stop coherence, ADR (full sessions only), F&G streaks, FR funding/cycle-cap/squeeze-penalty. **Mirrors SKILL.md rules letter-for-letter — a SKILL band change must change this file in the same commit.** |
| `compute.mjs` | CLI over `lib.mjs`. `node tools/compute.mjs <rsi\|thresholds\|round\|band\|ev\|stop-coherence\|adr\|streak\|fr-funding\|fr-cap\|sma\|drawdown> ...` — every command echoes inputs so the JSON can be pasted into a report's audit trail. |
| `fetch.mjs` | Live numeric backbone: `node tools/fetch.mjs btc\|eth\|sol\|gold\|macro`. Sources: CoinGecko (spot, ATH), Yahoo chart API (weekly/daily candles → RSI-14, 200-week SMA ±8% gate-6 check, ADR sessions, 1-y high for the FR cycle cap), alternative.me (F&G spot / 3-day avg / gate-1 daily-print streaks), FRED DFII10 (10y real yield). Cross-checks spot across sources and flags >1.5% divergence. **Does NOT cover ETF flows (Farside is bot-blocked), on-chain, or news — those stay live web fetches per Hard Rule 1.** |
| `lint-report.mjs` | `node tools/lint-report.mjs reports/<file>.md [--legacy]` — validates the report's ` ```json machine ` block (schema `report-machine/1`): filename convention, legs-sum→raw, per-asset rounding, gate denominator/thresholds/[V]-count, EV recompute (0.5% relative tolerance), Rally ≤50% cap, deployed+dry=100, stop coherence, FR mandatory price+time stops and ≤50% cap. Exit 1 = fix before committing, never override. |
| `selftest.mjs` | Regression vectors for `lib.mjs` (includes the ETH `ceil(7/9×8)=7` misprint and the gold no-flush valuation cap as permanent regressions). Run before calibrations and after any `lib.mjs` edit. |

Workflow per report: `fetch` → score with `compute` outputs → save report ending in the machine block → `lint` → commit. The SKILLs' "Deterministic Toolchain" sections are authoritative for what is mandatory.
