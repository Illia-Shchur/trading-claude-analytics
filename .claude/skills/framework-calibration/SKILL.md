---
name: framework-calibration
description: "Backtest a report-based forecasting framework against its OWN prior reports, grade past predictions vs realized outcomes, adversarially derive parameter tunes, and PROPOSE the survivors for user review (applied to the framework's SKILL file via a branch + PR only after approval — never auto-pushed). The iterative self-improvement loop. Works on any framework in this workspace (Fallen Knives, Flying Rocket, and future ones). Use whenever the user asks to calibrate / recalibrate / retune / improve a strategy, backtest predictions, audit forecast accuracy, 'reanalyse the reports and correct the strategy', 'were our predictions right', 'what parameters to tune', or any framework self-improvement / iterative-improvement request. Always runs the exhaustive multi-agent adversarial workflow."
---

# Framework Calibration — Backtest-Driven Iterative Improvement

## Overview

This skill turns a framework's track record into a calibrated upgrade. It reads every prior report a framework produced, grades what it predicted against what actually happened, diagnoses where the framework's parameters mislead, **adversarially refutes every proposed change** (so overfit/dangerous tunes are killed before they ship), then **auto-applies the survivors** to the framework's `SKILL.md` with a dated revision log, and saves + commits an authoritative retrospective.

It is the meta-framework: *Fallen Knives* and *Flying Rocket* analyze markets; **this** analyzes *them*.

**Operating mode (fixed for this workspace):**
- **Scope:** general — point it at any framework's `SKILL.md` + that framework's reports.
- **Apply mode:** **propose-then-review** — run the analysis, adjudicate the tunes, and prepare the exact surgical edits, then **STOP and present them for the user to review**. **Never edit a target `SKILL.md` or push a framework rule-change without explicit approval.** On approval, apply on a **branch + PR** (never a direct push to `main`). The standing guardrails below are hard vetoes regardless.
- **Depth:** **always exhaustive** — every run launches the multi-agent adversarial `Workflow`. Because the run is large and billed, **state a pre-flight scale estimate before invoking it** (≈ agents = reports + assets + dimensions + candidate-tunes + skeptic-votes; a 19-report corpus is ~50–80 agents) and note that results return for review before anything is applied. (A skill instructing `Workflow` is the user's opt-in to *orchestration* — it is **not** opt-in to editing files; that gate is separate and explicit.)

## Inputs — resolve these before running

1. **Target framework(s).** Which `SKILL.md` is being calibrated (default: infer from the user's words — "fallen knives" → `fallen-knives-analytics`, "rocket"/"short" → `flying-rocket-analytics`; if they say "the strategy" / "our predictions" and both have reports, calibrate the one with the richest series and note the other).
2. **Report corpus.** `git fetch origin` first (Hard Rule 7), then `ls reports/` and select every report the target framework produced. More reports = more grading power; a single report is too thin — say so.
3. **Ground-truth anchors.** Fetch **live** current price / sentiment / key levels for each asset in the corpus (Hard Rule 1 — live data non-negotiable). The realized path is mostly *inside* the later reports; the live anchors pin the end-state. Pass both to the workflow.
4. **Companion framework.** If calibrating a long framework that has an inverse (Fallen Knives ⇄ Flying Rocket), the cross-validation dimension must check both.

## The pipeline (5 phases — `backtest-workflow.template.js`)

A generalized, runnable workflow script ships alongside this file: **`backtest-workflow.template.js`**. Each run: read it, fill the `REPORTS` list (from the `ls`), the `ANCHORS` string (from the live fetch), and the SKILL paths, then invoke `Workflow({script})`. Phases:

1. **Extract** — one agent per report → structured predictions (score, gates, EV, probability scenarios, `IF→THEN` Pattern conditionals, falsifiable claims, deployment/stop state). Barrier (grading needs the whole asset series).
2. **Grade** — one agent per asset reconstructs the realized path and marks every prediction `correct / partial / wrong / untested` with evidence; quantifies EV-calibration bias, deployment quality (was capital locked out of cheaper zones?), and stop near-misses. Plus a cross-validation grader (companion-framework inverse-consistency).
3. **Diagnose** — one agent per framework **dimension** (scoring rubric, confirmation gates, deployment pyramid, stops, probability/EV, data-quality/cross-asset, voice/judgment) → flaws-with-evidence + proposed tunes (exact before→after).
4. **Verify (adversarial)** — one **skeptic per tune**, default-to-refute: overfit check, counterfactual over the realized path, unintended-consequence scan, Hard-Rule-6 / guardrail collision. Verdict: `adopt / adopt_with_modification / reject`. **Plus an applied-edits audit** agent that reads the *current* target SKILL and checks any prior calibration's edits for internal consistency, reachability, and runaway risk.
5. **Synthesize** — the authoritative memo: realized-path scorecard, prediction-accuracy analysis, ranked structural flaws, the **verified tuning set** (adopted vs rejected, with *why the rejections matter*), remaining edits, and an explicit "what to preserve."

Scale the finder/skeptic counts to the corpus: a 5-report series needs fewer extractors than a 19-report one; a "thorough audit" request widens the skeptic pool to 3–5 votes per tune.

## Adjudication → propose-for-review policy

After the workflow returns, **prepare** (do **not** yet apply) the edit set from every tune whose verdict is `adopt` or `adopt_with_modification`; **never include a `reject`.** Select the text to apply per tune:

- **`adopt`** → use the diagnosis's original `after` text.
- **`adopt_with_modification`** → use the skeptic's **`modification`** text (the adjudicated form), never the original `after`.
- **`adopt_with_modification` with an empty/blank `modification`** → **demote to needs-review**; do not fall back to the original (the policy explicitly distrusts the un-modified proposal here).

Then:

1. **Reconcile overlaps first.** Group prepared edits by the exact target text/location in the SKILL. If two adopted tunes touch the same line/clause, **merge** compatible ones into a single edit or **sequence** them deterministically; if they genuinely conflict, **escalate that pair to needs-review** rather than applying either. Never fire two overlapping `Edit`s against the same text (the second silently fails or clobbers the first).
2. **Present for review.** Show the user the adjudicated tuning set (adopted / rejected / needs-review), each precise **before→after**, and the draft retrospective. **Wait for explicit approval. Do not edit any target SKILL before this.**
3. **On approval, apply on a branch.** For each approved tune, **Read the target SKILL, locate the exact text, and Edit** it to the adjudicated form — surgical, one mapped before→after each. Commit on a new branch and **open a PR** (`gh pr create`); do **not** push to `main`.
4. **Run a validation pass** after editing: grep for duplicate list numbering, broken cross-references, stale text (old thresholds, removed multipliers), operator-precedence ambiguity in compound unlock clauses. **grep is necessary but not sufficient** — pair it with the §Reachability checks below; **any failure there or a guardrail miss hard-blocks the PR** until fixed.
5. **Add a dated `## Framework Revision Log` entry** (create the section if absent) summarizing: each applied tune (before→after, one line), the rejected tunes **with why** (the rejections are half the value — they document what *not* to do), any needs-review escalations, and the standing N=1 caveat.
6. **Mirror to a companion framework only with the asymmetry filter** below.

## Standing guardrails — hard vetoes no apply may cross (even with user approval)

Even a tune the skeptics passed — and even one the user approved in the review step — is **withheld at apply-time** if it would:

- **Relax short-side discipline** (Flying Rocket): no deep-greed/override-style loosening, no stop loosening, no gate softening, no threshold reduction. Per Hard Rule 6, the short side only ever gets *tightening* or direction-neutral mirrors. When mirroring a long-side tune to the short framework, apply it **only if it cannot make a short easier**; otherwise withhold it and log the withholding as deliberate.
- **Loosen a capital guardrail into a falling knife** — front-load size at shallow drawdown, deploy more on a thin score, credit *failed* trend gates toward an unlock, or remove a stop entirely. (These were the dominant rejection reasons in the Jun 2026 calibration.)
- **Damage a documented "what to preserve" behavior** — the retrospective's preserve-list (e.g., the framework's correct *refusals* to deploy, conservative sizing, "honor the close" stops). A tune that improves one metric by breaking a survival behavior is rejected.
- **Break its own central mechanism** — e.g., a score penalty that knocks the score below a deployment-override threshold at the lows, disarming the override exactly when it should fire. Check every score-touching tune against the unlock thresholds it could cross.

If an otherwise-adopted tune trips a guardrail, **do not apply it**; record it in the revision log under "withheld at apply-time (guardrail)" with the reason.

## Reachability & internal-consistency checks (apply-time, mandatory)

The Jun 2026 pass shipped a Deep-Value Override that was **mathematically unreachable** (its trigger sat below the deepest realized price, so it never fired). Before declaring any new/changed *trigger* applied, prove it:

- **Reachability:** would the new trigger have fired somewhere on the realized path it's meant to handle? If not, it's decorative — re-anchor it (e.g., to a trailing low / close, not just report-time spot) until it would.
- **Throttle:** any rule that *adds* exposure must have a cap and an interaction rule with any opposing modifier, so it can't chain-runaway through an uninterrupted cascade.
- **Decoupling:** a deploy-trigger and a stop-trigger must never key off the same number.
- **Denominator/N/A:** if gates can be N/A for some asset class (e.g., PoW-only signals on PoS assets), reduce the denominator — never leave an unfillable gate inflating the count.

## Output & git (Hard Rule 7 + review gate)

- Save the memo to `reports/strategy_retrospective_[YYYYMMDD].md` (or `[framework]_calibration_[YYYYMMDD].md` for a single-framework run).
- Append a one-line entry to **`reports/calibration_ledger.md`** (create if absent): date · framework(s) · #adopted/#rejected/#needs-review · headline change · "N=1 — re-validate after next full cycle." This is the running tuning history across calibrations.
- **The retrospective + ledger are analysis outputs** — they may be saved + committed normally (Auto-push convention). **The target-SKILL edits are NOT** — they require user approval and ship via a **branch + PR** (one PR per calibration; message: what was calibrated, top adopted/rejected, evidence window). **Never push a framework rule-change directly to `main`**, and never sweep unrelated working-tree changes into either commit.
- Post a ≤8-line summary: predictions-right-vs-wrong tally, the 1–2 highest-leverage tunes **proposed**, the most important *rejection*, the N=1 caveat — and the **pending PR / review** the user must approve before anything changes the framework.

## Principles

1. **The track record is the test set.** A framework's own prior reports are a closed, honest dataset — its later reports + live data are the ground truth for its earlier predictions. Grade against them mercilessly.
2. **Rejections are as valuable as adoptions.** Most "improvements" that touch a score or loosen a gate make the realized outcome worse. Document why each rejected tune was rejected so the next calibration doesn't re-propose it.
3. **Preserve what survived.** Separate *forecasting honesty* (fix freely) from *risk discipline* (the actual edge — touch only to harden). The frameworks usually survive *because* they doubted their forecasts enough to stay disciplined; make that doubt structural without relaxing the discipline.
4. **Every calibration is N=1.** Tunes fit to one regime are provisional. Strip the most path-fitted parameters, prefer direction-of-effect over point-precision, and flag thresholds for re-validation after the next full cycle.
5. **Asymmetric humility carries over.** When a long-side fix has a short-side mirror, the mirror is applied only if it can't make the short easier. The withholding is itself a logged decision.

## Voice

Calm, quantitative, unsentimental — the same allocator voice as the frameworks it calibrates. Lead with the honest verdict (what was right, what was wrong), then the evidence, then the change. Never oversell a tune; always state the uncertainty.
