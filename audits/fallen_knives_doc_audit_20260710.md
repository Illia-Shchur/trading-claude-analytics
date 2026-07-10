# Red-Team Documentation Audit — fallen-knives-analytics — 2026-07-10

**Scope:** adversarial logic audit (Pass A) + document hardening (Pass B) of `.claude/skills/fallen-knives-analytics/SKILL.md`, checked against CLAUDE.md, the Framework Revision Log, `reports/calibration_ledger.md`, `reports/strategy_retrospective_20260704.md`, and the BTC/ETH/Gold report series Jun-11 → Jul-9 2026.

**Status:** ALL items applied 2026-07-10. The owner delegated the needs-owner-decision list with the mandate: *"more agile in our deals … miss less opportunities but still managing risks."* Layer-1 (pure doc-hardening) and Layer-2 (delegated adjudications) are both live in SKILL.md; exact applied text is in the SKILL's `### 2026-07-10` revision-log entry. This file preserves the evidence and rationale.

**This was NOT a calibration.** No backtest workflow ran; no parameter moved on fitting grounds. Every Layer-2 adjudication is N=1 and must be re-graded out-of-sample by the next `framework-calibration` run exactly as adopted tunes are.

---

## 1. Executive summary

One blocker, seven majors, and a tail of minors, in three clusters: (1) a live doc-vs-practice divergence on a stop parameter (gold compound-stop score condition `<8` since Jun-17 vs the SKILL's universal `<12`); (2) undefined terms at the most expensive decision points (Override "trailing-period"/"fresh lower-low"; rubric band edges unparseable by the tie-break rule; Phase-2 corr default; score-17 Phase-3 contradiction); (3) stale/self-contradictory wording left by prior edits ("Rally capped below modal" false against the printed grid; Phase-1A row mislabeling the below-ladder stop "compound"; Overview claiming correlation feeds the score; dead macOS output path).

## 2. Pass-A findings (evidence)

**A1 (BLOCKER) — Compound-stop score condition: SKILL said 12, gold runs 8.** Gold reports printed `<12` through Jun-16, `<8` from Jun-17 (gold_fallen_knives_20260617_1715.md:138) through Jul-9. The 2026-07-04 revision log itself names "the undisclosed Gold `<12→<8` score-condition halving" as the defect that motivated the Stop Migration Ledger — but the halving was never reverted or codified. With gold's score structurally pinned ~10, the doc-as-written (`<12`, vacuously true) would have made gold's compound stop price-only — two weekly closes < $3,850 would fire it — while the live series would hold.

**A2 (major) — Override price condition, two undefined terms.** "Trailing-period" had no window; "fresh lower-low" no reference series. Live cost: btc_fallen_knives_20260624_1330.md:158 — "borderline-MET intraday … Whether it cleanly broke the June-6 $59,110 cycle low is ambiguous." The cycle-low reading would restore the unreachability the Jun-2026 re-anchor explicitly removed.

**A3 (major) — Score Interpretation table vs §6 Phase 3 contradict at exactly 17.** Table: "15–17 … Phases 1A–2"; §6: "adjusted score ≥17" unlocks Phase 3. A 45% tranche boundary; latent (max realized score 16).

**A4 (major) — Boundary tie-break unparseable on momentum/valuation bands.** The Jul-2026 tie-break ("the band whose inequality includes it") keys on ≤/≥ markers; the momentum (30–35/35–40/40–45), MVRV-Z (0.1–0.5/0.5–1.0), drawdown (60–70/50–60…) and gold bands were dash-ranges with no markers. RSI exactly 35.0/40.0 or MVRV-Z 0.50 had no defined band; one point flips 13/15/17 unlocks. Series computed 36.5 (BTC) and 38.8 (gold) — living one band from these edges.

**A5 (major) — Phase-2 `corr <0.8` had no default when correlation is "not computed"** — its state in essentially every report (all three Jul-9 reports: "not computed this cycle").

**A6 (major, wording-fixable) — Phase-1A row called the below-ladder stop the "compound" stop; the coherence boolean named no tier.** Placement rule and uniform practice both use the CATASTROPHIC tier: btc_fallen_knives_20260709_0530.md:181 runs the check as "Catastrophic stop $50K strictly below $54K? PASS" while the compound line $55K sits *inside* the $54–58K contingency band by design. A literal reading of the old 1A row would print a false FAIL — or pressure the compound line downward.

**A7 (major) — Gold gate board un-codified; practiced gate-1 (COT washout) collided with the double-key prohibition's wording; "≥7 consecutive days" uncountable on a weekly instrument.** gold_fallen_knives_20260709_0530.md:110 keys gate 1 and capitulation leg (b) off the same COT print. Resolution: gate-level reuse is structurally identical to gates 2/3/4 reusing their legs' inputs — the prohibition bars one input keying two *score legs*, which practice never did.

**A8 (major) — "Rally capped below modal" false against the printed grid (Rally IS modal at 15–17: 38>33, and 18–20: 50>28); intended property = never a majority (>50%). The ±10% adjustment could silently manufacture a majority at 18–20 (50+10=60).**

**Minors:** gate-1 streak basis unstated (practice: daily pinned-provider prints — btc…0709:125); .5 rounding convention only in reports (BTC/Gold half-up, ETH half-down — eth…0615:97, btc…0615:103, gold May-31); "adjusted score" a stale term (nothing adjusts it since the multiplier was removed); Overview misdescribed the composite; output path stale (macOS desktop); phase rows hardcoded "of 9" (root cause of ETH's ceil(7/9×8)=6 misprint, 3 reports); "dark gate" undefined + gold Jul-9 tagged a ⚠️ gate "none-in-regime" (forbidden — practice violation to correct next gold report); 5-day ADR silently absorbed the Jul-3 half-session (gold Jul-9, $81.6 "Jul 1–8"); vacuity trigger (iii) hid a fourth disjunct; redundant valuation bands (0.5–1.0/1–2 both →3); calendar tiebreak read as contradicting "never backward-only"; Override's deployed-base precondition implicit; EV sum-check tolerance unit unstated; input-rule asymmetry (momentum had a NOT-FOUND debt clock; valuation/holder/gate-6 MA did not — ETH ran 4 reports on a derived MVRV, BTC's holder leg carried a stale reserve print at full credit); momentum NOT-FOUND had no cold-start default; exit-table terms undefined ("local peak," "sustained inflow regime," no carve-out for measurement-correction score drops — BTC Jul-9 took a labeled input-honesty −1); BTC Jul-9 pre-staged a "half-size" 1B on the normal path (conservative but unauthorized); ETH Jul-9 improvised a "PROVISIONAL" status for a single-sourced streak-completing print.

**Verified clean:** FK's paraphrases of Flying Rocket rules (Phase 1A ≥13, phase-of-cycle cap >20% off 1-yr ATH, both-≥12) match the FR SKILL — no cross-framework drift. No realizable double-key found between deploy triggers and the compound stop (3-point dead zone, opposite directions, explicit decoupling).

## 3. Layer 1 — doc-hardening applied (20 edits, zero decision changes)

Overview composite description corrected; adjusted-score definition + per-asset .5 convention codified; gates heading and counting rule made denominator-aware (with the /8 ⇒ 3/5/6/7 worked check); gate-1 daily-print basis codified; dark-gate definition added; "below modal" → "never a majority (≤50%)"; trend-residual phrasing → percentage points; EV sum-check unit pinned; Override deployed-base precondition made explicit; Phase-1A stop row re-labeled CATASTROPHIC; Phase-1B/2 rows given Phase-3-style bracketing + ceil notes (retires retrospective outstanding item #2); coherence boolean names the catastrophic tier; calendar tiebreak labeled the named exception; ADR rule hardened (see Layer 2 #8); vacuity (iii)/(iv) split; output path re-pointed to repo-relative `reports/`; redundant valuation bands merged (output-identical).

## 4. Layer 2 — delegated adjudications (mandate: agility with managed risk)

| # | Decision taken | Direction & guardrail check |
|---|---|---|
| 1 | Compound-stop score line = asset-named, default 12; structurally-pinned-score assets re-set below their realized range via Stop Migration line; gold `<8` ratified retroactively, BTC/ETH stay 12 | Documents the standing parameter; reverting to a vacuously-true `<12` would have degraded gold to a price-only stop (the anti-pattern the compound design prevents). Catastrophic tiers untouched; no live stop moved |
| 2 | Override: trailing-period = since last fill; fresh lower-low = daily-structure swing-low break, not cycle-low break | Agile: restores documented Jun-2026 reachability intent; still refuses shallow-dip chop; all other Override conditions + throttle unchanged |
| 3 | Interpretation table aligned to §6: Phase-3 eligibility at 17 (15–16 / 17–19) | §6 was always operative; table was the contradicting text |
| 4 | Band edges: chained ≤/≥, exact edge → higher-score band, mirroring the pinned sentiment convention (15.0 ≤ 15 → 4); conservative-only deviation hatch stands | Agile at edges; consistent with the already-adjudicated sentiment default |
| 5 | Phase-2 corr: computation mandatory at unlock; documented-failed attempt → PASS + "corr unverified" line; unattempted → blocks. Surcharge = +1 total AND +1 [V]-floor | Agile on genuine data failure, Hard-Rule-1-enforcing otherwise; surcharge pinned to the stricter reading |
| 6 | Gold gate map codified (COT gate 1 w/ two-print confirmation, WGC/GLD gate 4, gate 3 none-by-construction kept in /8 denominator, capitulation legs b/c) | Denominator NOT reduced (would have lowered gold's unlock bars — vetoed); gate-level COT reuse legitimized, sentiment-LEG ban intact |
| 7 | Rally ≤50% cap binds post-adjustment unless a realized trend-structure event is cited; ±10 = percentage points | Closes the manufactured-optimism loophole; evidence-gated exception preserves agility on genuine repair |
| 8 | ADR excludes holiday-abbreviated sessions (disclosed) | Prevents understated vol licensing "likely" adjectives — risk-managing |
| 9 | Stale-input debt clock generalized to all legs + gate-6 MA; momentum cold-start default = 1; single-sourced streak completions = PROVISIONAL | Data integrity both ways |
| 10 | Exit terms: local peak = campaign-relative; sustained inflow regime = the ≥5-session bar; labeled measurement corrections excluded from the ≥6-point trim (like-for-like restatement required) | Prevents bookkeeping-driven trims (agile hold) without weakening any genuine trim trigger |
| 11 | Partial-tranche deployment: unlock authorizes UP TO nominal; downsizing OK, upsizing prohibited | Legitimizes the conservative BTC Jul-9 practice; no loosening |

**Deliberately NOT touched:** the Override worsening-flows veto and its time-correlation with the arming condition — ledger-flagged for the next full calibration; an untested reversal was explicitly deferred there and remains deferred. Also untouched: all phase sizes (10/15/30/45), all probability cells, all unlock score thresholds, Flying Rocket (Hard Rule 6), CLAUDE.md.

## 5. What to preserve + N=1

Preserve: the refusals to deploy (gold's nine-report freeze vindicated in writing by the Jul-6 COT; ETH's no-chase discipline), the flattened grid and negative-EV honesty at spot, the compound stop's silence through the $57,779 wick, the atomic re-stop-before-fill mechanics, conservative ETH rounding, the computed-companion mandate, and every held rejection (17+41+1) across both calibration cycles.

Every observation here is N=1 on one regime shape (bear grind → macro squeeze; no euphoria, no flush, no live FR cycle). Layer-1 edits are regime-independent. Layer-2 adjudications were taken under an explicit owner agility mandate on 2026-07-10 and **must be re-graded out-of-sample by the next calibration**; practice items to correct in the next reports: (a) gold's ⚠️ gate tagged "none-in-regime" (now covered by the none-by-construction exception only for gate 3), (b) any ADR window spanning an abbreviated session now excludes it.
