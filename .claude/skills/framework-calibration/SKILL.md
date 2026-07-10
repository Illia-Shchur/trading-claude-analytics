---
name: framework-calibration
description: "Backtest a report-based forecasting framework against its OWN prior reports, grade past predictions vs realized outcomes, adversarially derive parameter tunes, and AUTO-APPLY the survivors to the framework's SKILL file. The iterative self-improvement loop. Works on any framework in this workspace (Fallen Knives, Flying Rocket, and future ones). Use whenever the user asks to calibrate / recalibrate / retune / improve a strategy, backtest predictions, audit forecast accuracy, 'reanalyse the reports and correct the strategy', 'were our predictions right', 'did the last calibration help', 'what parameters to tune', or any framework self-improvement / iterative-improvement request. From the second run on, every calibration first re-grades the PRIOR calibration's adopted/rejected tunes out-of-sample, then runs the exhaustive multi-agent adversarial workflow."
---

# Framework Calibration — Backtest-Driven Iterative Improvement

## Overview

This skill turns a framework's track record into a calibrated upgrade. It reads every prior report a framework produced, grades what it predicted against what actually happened, diagnoses where the framework's parameters mislead, **adversarially refutes every proposed change** (so overfit/dangerous tunes are killed before they ship), then **auto-applies the survivors** to the framework's `SKILL.md` with a dated revision log, and saves + commits an authoritative retrospective. From the second run onward it also grades the previous calibration itself — every adopted tune's expected effect is a prediction, and it gets the same merciless out-of-sample grading as the framework's own calls.

It is the meta-framework: *Fallen Knives* and *Flying Rocket* analyze markets; **this** analyzes *them*.

**Operating mode (fixed for this workspace):**
- **Scope:** general — point it at any framework's `SKILL.md` + that framework's reports.
- **Apply mode:** **auto-apply** every adversarially-survived tune (with revision-log entry + commit), then report. No per-tune confirmation gate — but the standing guardrails below are hard vetoes that auto-apply may never cross.
- **Depth:** **always exhaustive** — every run launches the multi-agent adversarial `Workflow`. (A skill instructing `Workflow` is itself the user opt-in.)

## Inputs — resolve these before running

1. **Target framework(s).** Which `SKILL.md` is being calibrated (default: infer from the user's words — "fallen knives" → `fallen-knives-analytics`, "rocket"/"short" → `flying-rocket-analytics`; if they say "the strategy" / "our predictions" and both have reports, calibrate the one with the richest series and note the other).
2. **Prior adjudications.** Read `reports/calibration_ledger.md`, the retrospective(s) it points to, and the target SKILL's `## Framework Revision Log`. These carry the prior adopted/rejected tuning set: the workflow re-grades the adopted tunes out-of-sample and refuses to re-litigate the rejected ones without new evidence. Fill the template's `PRIOR_CALIBRATIONS` block from the ledger (leave it `[]` on a first run).
3. **Report corpus.** `git fetch origin` first (Hard Rule 7), then `ls reports/` and select the target framework's reports. **Default window: reports since the last calibration** (the ledger's latest entry) — earlier reports were already graded, and a clean window keeps each calibration an honest out-of-sample test of the prior tunes. It is also the primary cost control: extraction is the workflow's linear term, and re-extracting already-graded reports is pure waste. Pull in older reports only for still-open positions, for prior predictions graded `untested`, or when the user asks for a full-history re-run. Exclude retrospectives and the ledger itself — they are calibration artifacts, not framework output (the template's filename parser rejects them loudly). More reports = more grading power; a single report is too thin — say so.
4. **Ground-truth anchors.** Fetch **live** current price / sentiment / key levels for each asset in the corpus (Hard Rule 1 — live data non-negotiable). The realized path is mostly *inside* the later reports; the live anchors pin the end-state. Pass both to the workflow.
5. **Companion framework.** If calibrating a long framework that has an inverse (Fallen Knives ⇄ Flying Rocket), the cross-validation dimension must check both.

## The pipeline (5 phases — `backtest-workflow.template.js`)

A generalized, runnable workflow script ships alongside this file: **`backtest-workflow.template.js`**. Each run: read it, fill `REPORT_FILES` (plain filenames — asset/framework/date parse automatically from the naming convention), `ANCHORS` (from the live fetch), `PRIOR_CALIBRATIONS` (from the ledger), and the SKILL paths, then invoke `Workflow({script})`. Phases:

1. **Extract** — one agent per chronological same-series **chunk of ~6 reports** (`EXTRACT_CHUNK`, hard ceiling 8) → one full structured extraction *per report* (score, gates, EV, probability scenarios, `IF→THEN` Pattern conditionals, falsifiable claims, deployment/stop state). Barrier (grading needs the whole series). Identity fields (file/asset/framework/date) are joined **in code by chunk position** (within-chunk filename match as the mismatch fallback), never trusted from the agent; failed chunks are logged as uncovered, not silently dropped.
2. **Grade** — one agent per **framework × asset series** (a long and a short framework on the same asset are different track records) reconstructs the realized path and marks every prediction `correct / partial / wrong / untested` with evidence; quantifies EV-calibration bias, deployment quality (was capital locked out of cheaper zones?), and stop near-misses; independently re-verifies the most load-bearing path numbers because reports occasionally carry invented figures. Plus a cross-validation grader (companion-framework inverse-consistency). Plus, from the second calibration on, a **prior-tune re-validator**: every tune the last calibration adopted is graded `validated / harmful / not_exercised / indeterminate` on the post-calibration path, prior `untested` predictions are re-resolved, and the prior rejection list is extracted so later phases can hold the line on it.
3. **Diagnose** — one agent per **framework × merged dimension** (4: scoring-and-gates, capital-deployment [pyramid + stops], forecast-calibration [probability/EV + voice/judgment], data-integrity [data-quality + cross-asset + cross-framework]) → flaws-with-evidence + proposed tunes (exact before→after); fewer, stronger tunes are explicitly preferred — every proposal costs an adversarial panel downstream. Diagnosers see the prior adjudications: re-proposing a rejected tune requires naming it and citing new out-of-sample evidence; reversing an adopted one requires a `harmful` re-validation grade.
4. **Verify (adversarial)** — first a **triage** agent clusters near-duplicate tunes by name (runs only when >8 proposed; code rebuilds the kept list from the originals, so triage never rewrites tune text and can drop nothing silently). Then **skeptic panels, batched**: ~5 tunes per skeptic agent (`VERIFY_CHUNK`), per-tune verdicts, default-to-refute — except tunes from the **capital-deployment dimension, which always get per-tune solo panels** (they move money; batching scrutiny there is a false economy). `SKEPTICS_PER_TUNE` (default 1; 2–3 on a thorough audit, each pass taking a different lens: overfit/counterfactual, guardrail-collision, evidence-verification) multiplies only this phase. Votes merge **strictest-wins** (any reject vote rejects; a tune with no surviving verdict is *unadjudicated*, which is not an adoption). Verdict: `adopt / adopt_with_modification / reject`. **Plus an applied-edits audit** of the *previous* calibration's edits in the live SKILLs, and a **pre-apply audit** that takes the whole adopted set together and checks mutual consistency, reachability, throttle, decoupling, threshold crossings, and scope before anything ships — its `final_text` is the form that gets applied.
5. **Synthesize** — the authoritative memo: prior-calibration re-validation, realized-path scorecard, prediction-accuracy analysis, ranked structural flaws, the **verified tuning set** (adopted vs rejected, with *why the rejections matter*), remaining edits, coverage disclosure (dropped reports, unadjudicated tunes), and an explicit "what to preserve."

**Cost is bounded structurally, never by skipping coverage.** The corpus window (since the last calibration — input #3) bounds the report count; extraction chunking bounds the linear term (~R/6 agents, not R); merged dimensions and batched panels bound the rest. Approximate agent count: `Σceil(series/6) + (series+2) + 4×frameworks + 1 triage + skeptics×(ceil(tunes/5) + capital_tunes) + 3` — a 90-report / 3-series month runs ~30 agents where the v1 design ran ~125. "Exhaustive" means every report extracted, every prediction graded, every tune adversarially refuted — it has never meant one agent per datum. A "thorough audit" request widens `SKEPTICS_PER_TUNE` to 2–3 (multiplies only the Verify phase).

## Adjudication → auto-apply policy

After the workflow returns, **apply every tune whose panel verdict is `adopt` or `adopt_with_modification` AND whose pre-apply audit says `apply_ok`** — using the audit's **`final_text`**, not the original proposal (that is the adjudicated form). **Never apply a `reject`.** A tune returned in `unadjudicated_tunes` (its skeptic panel died mid-run) is **not** an adoption: list it in the memo as uncovered and re-run its panel before it may ever be applied. Then:

1. For each adopted tune, **Read the target SKILL, locate the exact text, and Edit** it to the adjudicated form. Map every before→after precisely — these are surgical edits to a live framework.
2. **Run a validation pass** (grep): no duplicate list numbering, no broken cross-references, no stale text left behind (old thresholds, removed multipliers), no operator-precedence ambiguity in compound unlock clauses. Fix any defect the audit flagged in a prior calibration's edits.
3. **Add a dated `## Framework Revision Log` entry** (create the section if absent) summarizing: each applied tune (before→after, one line), the rejected tunes **with why** (the rejections are half the value — they document what *not* to do), and the standing N=1 caveat.
4. **Mirror to a companion framework only with the asymmetry filter** below.

## Standing guardrails — hard vetoes that auto-apply may NEVER cross

Even a tune the skeptics passed is **rejected at apply-time** if it would:

- **Relax short-side discipline** (Flying Rocket): no deep-greed/override-style loosening, no stop loosening, no gate softening, no threshold reduction. Per Hard Rule 6, the short side only ever gets *tightening* or direction-neutral mirrors. When mirroring a long-side tune to the short framework, apply it **only if it cannot make a short easier**; otherwise withhold it and log the withholding as deliberate.
- **Loosen a capital guardrail into a falling knife** — front-load size at shallow drawdown, deploy more on a thin score, credit *failed* trend gates toward an unlock, or remove a stop entirely. (These were the dominant rejection reasons in the Jun 2026 calibration.)
- **Damage a documented "what to preserve" behavior** — the retrospective's preserve-list (e.g., the framework's correct *refusals* to deploy, conservative sizing, "honor the close" stops). A tune that improves one metric by breaking a survival behavior is rejected.
- **Break its own central mechanism** — e.g., a score penalty that knocks the score below a deployment-override threshold at the lows, disarming the override exactly when it should fire. Check every score-touching tune against the unlock thresholds it could cross.
- **Cross the edit-surface boundary** — auto-apply may edit the target framework SKILL file(s) only. Workspace Hard Rules (`CLAUDE.md`) are read-only context: a tune that needs one changed (pyramid splits, cross-validation law, short-side asymmetry) is surfaced to the user as a recommendation, never applied.
- **Reverse a prior calibration's adopted tune without out-of-sample evidence of harm.** Reversal is a tune like any other, but its evidence bar is the prior-tune re-validator grading it `harmful` on the post-calibration path. Re-arguing the original debate on the same evidence is thrash, not calibration — oscillating parameters are worse than imperfect stable ones.

If an otherwise-adopted tune trips a guardrail, **do not apply it**; record it in the revision log under "withheld at apply-time (guardrail)" with the reason.

## Reachability & internal-consistency checks (apply-time, mandatory)

The Jun 2026 pass shipped a Deep-Value Override that was **mathematically unreachable** (its trigger sat below the deepest realized price, so it never fired). The workflow's pre-apply audit now runs these checks with full backtest context *before* adjudication ships; re-verify them at apply-time anyway — they are cheap, and they are exactly the checks that once failed silently:

- **Reachability:** would the new trigger have fired somewhere on the realized path it's meant to handle? If not, it's decorative — re-anchor it (e.g., to a trailing low / close, not just report-time spot) until it would.
- **Throttle:** any rule that *adds* exposure must have a cap and an interaction rule with any opposing modifier, so it can't chain-runaway through an uninterrupted cascade.
- **Decoupling:** a deploy-trigger and a stop-trigger must never key off the same number.
- **Denominator/N/A:** if gates can be N/A for some asset class (e.g., PoW-only signals on PoS assets), reduce the denominator — never leave an unfillable gate inflating the count.

## Output & git (Hard Rule 7 + Auto-push)

- Save the memo to `reports/strategy_retrospective_[YYYYMMDD].md` (or `[framework]_calibration_[YYYYMMDD].md` for a single-framework run).
- Append a one-line entry to **`reports/calibration_ledger.md`** (create if absent): date · framework(s) · corpus window · #adopted/#rejected (+#withheld/#unadjudicated if any) · prior-tune re-validation tally (validated/harmful/not-exercised) · headline change · "N=1 — re-validate after next full cycle." This ledger is the running tuning history across calibrations **and the input to the next run's `PRIOR_CALIBRATIONS` block** — keep it accurate.
- **Commit + push** the changed SKILL(s) + retrospective + ledger in one commit (message: what was calibrated, top adopted/rejected, evidence window). Do not sweep unrelated working-tree changes into it.
- Post a ≤8-line summary: predictions-right-vs-wrong tally, the 1–2 highest-leverage tunes applied, the most important *rejection*, and the N=1 caveat.

## Principles

1. **The track record is the test set.** A framework's own prior reports are a closed, honest dataset — its later reports + live data are the ground truth for its earlier predictions. Grade against them mercilessly.
2. **Rejections are as valuable as adoptions.** Most "improvements" that touch a score or loosen a gate make the realized outcome worse. Document why each rejected tune was rejected so the next calibration doesn't re-propose it.
3. **Preserve what survived.** Separate *forecasting honesty* (fix freely) from *risk discipline* (the actual edge — touch only to harden). The frameworks usually survive *because* they doubted their forecasts enough to stay disciplined; make that doubt structural without relaxing the discipline.
4. **Every calibration is N=1.** Tunes fit to one regime are provisional. Strip the most path-fitted parameters, prefer direction-of-effect over point-precision, and flag thresholds for re-validation after the next full cycle.
5. **Asymmetric humility carries over.** When a long-side fix has a short-side mirror, the mirror is applied only if it can't make the short easier. The withholding is itself a logged decision.
6. **Grade the calibrator before the framework.** From the second run on, the first question is "did the last calibration's tunes survive contact?" — adopted tunes graded on whether they fired/bound/helped, rejections checked for vindication, `untested` predictions re-resolved. A calibration loop that never audits itself just accumulates plausible edits; this re-validation is what makes "N=1 — re-validate after next full cycle" an executed step instead of a disclaimer.

## Calibrating the calibrator

When the user asks to improve **this skill itself** ("improve the calibration skill", "look into this skill") rather than a market framework: do **not** launch the market backtest. The deliverable is edits to this `SKILL.md` and `backtest-workflow.template.js`, grounded in this skill's own track record — the calibration ledger, the retrospectives, the target SKILLs' revision logs, and any process defects they document (edits that shipped broken, checks that ran too late, corpus/adjudication gaps). Apply the same standards this skill applies to the frameworks: evidence over speculation, preserve what worked, reject speculative complexity, and log the change (a `meta` line in `reports/calibration_ledger.md` + commit).

## Voice

Calm, quantitative, unsentimental — the same allocator voice as the frameworks it calibrates. Lead with the honest verdict (what was right, what was wrong), then the evidence, then the change. Never oversell a tune; always state the uncertainty.
