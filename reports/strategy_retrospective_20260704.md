# Framework Calibration Retrospective — 2026-07-04

**Corpus:** 39 reports, 2026-06-11 → 2026-07-02 (BTC/ETH/Gold/UNI; 29 Fallen Knives + 9 Flying Rocket + 1 combined-parse). **Workflow:** 314-agent adversarial backtest (extract → grade → prior-tune re-validation → diagnose → 3-skeptic-panel verify → pre-apply audit → synthesize). Thorough-audit mode: 3 skeptics per tune, distinct lenses (overfit/counterfactual, guardrail-collision, evidence-verification).

**Adjudication:** 83 candidate tunes proposed across 14 framework×dimension diagnoses → 39 net-distinct edits after de-duplication (2 pairs of duplicate proposals folded together) → **38 adopted-with-modification, 1 rejected at the pre-apply stage** (all 39 received a full 3-skeptic panel — 0 unadjudicated). Of the 41 raw panel rejections (before dedup collapsed some duplicates into their adopted siblings), 23 were Fallen-Knives-side and 18 Flying-Rocket-side.

---

## 1. Executive Verdict

The 2026-06-11 calibration **broadly survives its first out-of-sample test**. Across 38 post-calibration reports, **15 of 19 adopted mechanisms validated cleanly, 4 were not exercised (no harmful outcomes), 0 were harmful, and all 17 prior rejections held** — none crept back in.

The headline correction from this pass: the diagnosed core defect — **pyramid inversion** (biggest tranches should land at the deepest fear; the framework kept doing the opposite) — **recurred in milder form**. BTC hit its cycle-high score (16) at the cycle low ($57,779, Jun-30), with the Deep-Value Override's arithmetic finally arming for the first time in the framework's history — and then getting vetoed both times it mattered. Net: **zero new capital deployed at the actual bottom**, for a foregone gain of roughly +0.2% of book. The veto's logic was individually defensible (a record −$4.5B ETF-outflow month, an unbroken exodus chain), but this cycle's applied-edits audit surfaced a structural finding: **the veto and the arming condition are correlated in time** — in a sharp macro-driven reversal (exactly what happened, NFP miss → 2.6%/5.2% squeeze), fear exits before the outflow chain breaks, so the Override can only ever fire in a slow grind, never in the V-shaped recovery where catching the last tranche matters most. This is a newly-surfaced design gap for the next calibration, not a bug in what shipped in June.

Second correction, more serious: **Flying Rocket has not produced a single report since June 18.** Every FR-side tune adopted in June — the mirrored trend term, the anti-bear-trap principle, the demoted correlation modifier, the mandatory computed FK companion, the verdict-confidence collar — is validated only by construction, never by a live report. The July window included exactly the tape (sustained-negative funding, a ~$200M shorts-dominated squeeze) that the squeeze-trap penalty and cover-trigger exist to catch, and FR was silent for all of it. This is graded `not_exercised`, not `validated`.

## 2. Prior-Calibration Re-Validation

| Tune | Verdict | One-line evidence |
|---|---|---|
| #1 Valuation re-band (MVRV-Z breakpoints) | **validated** | BTC's +1 score move (12→13) driven by MVRV-Z deepening into 0.1–0.5 band, no collision |
| #2 Correlation modifier demoted to sourced/declared | **validated** | Zero eyeballed multipliers in 38 reports; Jul-2 explicitly "corr not sourced" |
| #3 Mandatory computed FR/FK companion | **validated** | 9 real FR reports ran (through Jun-18); every FK report since carries a computed companion |
| #4 Canonical-spot reconciliation (spread >0.5% → EV at both extremes) | **validated** | Fired correctly 3+ times, never suppressed a legitimate call |
| #5 Stop strictly below deepest buy zone | **validated** | Zero violations in 29 FK reports vs 5/5 pre-calibration |
| #6 Compound thesis stop (weekly closes + score) | **validated** | Correctly stayed silent through the BTC $57,779 wick (0.4% above the retired legacy stop) |
| #7 Stop-vs-buy-zone coherence check | **validated** | Drove a real stop re-placement ($52K→$50K) when the ladder floor moved |
| #8 Flattened Rally grid + EV-floor check | **validated** | EV range compressed from +0.5%→+5.1% (pre-cal, into a −23% slide) to −1.8%→+0.5% (post-cal); floor check bound 4 times, passed |
| #9 Verdict-Confidence Collar | **validated** | Blocked a premature Gold bottom call (Jun-15) that preceded a further −9% |
| #10 Single-observation durability lock | **validated** | Blocked ETH's first green ETF-flow day from being read as a trend reversal |
| #11 Forward-claim hedge / two-tier certainty | **validated** | Zero settled-fact assertions falsified in-window (vs 3 within 14 days pre-calibration) |
| §6.4 Deep-Value Override re-anchor | **validated (mechanism), N=1 (outcome)** | Armed for the first time ever (3/4, then 3/3 conditions) — proving reachability — but vetoed both times |
| §6.3 Override throttle (quarter-size, ≤1/5-days, ≤25% cap) | **not exercised** | Never sized a tranche because the Override never fired |
| §6.5 N/A-denominator → `ceil(fraction × denom)` | **validated, with a self-caught slip** | Correct in Gold/PoS gates; ETH misprinted `ceil(7/9×8)=6` (should be 7) in 3 reports, self-corrected Jul-2, zero practical effect |
| §6.6 Gate-7 regime-relative capitulation | **not exercised** | Never fired — no genuine flush occurred in either leg down |
| §6.1/6.2/6.7 mechanical fixes | **validated** | Phase-3 parenthesization and gate-6↔§5 cross-reference both exercised correctly |
| FR symmetric mirrors (trend term, anti-bear-trap, collar) | **not exercised** | No FR report has run since Jun-18 |
| FR withheld loosenings (no deep-greed override, no stop/gate softening) | **validated** | All withheld guardrails held; directly avoided the Jul-2 squeeze that mauled the crowded short book |
| Jun-10 3-day sentiment smoothing | **validated** | Held the sentiment leg honestly at exactly 15.0 on Jul-2 while the spot print whipsawed to 19 |

**All 17 prior rejections held — none re-adopted.** Most notably vindicated: **R2** (a −2 downtrend penalty would have dropped BTC to 14/13 at the Jun-30/Jul-1 lows, disarming the Override two sessions before the +2.4%/+5.2% squeeze) and **R13** (hysteresis would have delayed the Jul-1 sentiment upgrade at the point of maximum fear).

## 3. Realized-Path Scorecard

| Asset/Framework | Reports | Score range | Deployed capital | MTM at sample end |
|---|---|---|---|---|
| BTC Fallen Knives | 10 | 12→16 | 10% (unchanged) | −4.9% (from −9.7% trough) |
| ETH Fallen Knives | 9 | 12→16 | 0% (Hard Rule 4, cold start) | N/A — no position |
| Gold Fallen Knives | 10 | 10.0 flat × 7 straight | 25% (unchanged) | −9.2% (from −12.5% trough) |
| BTC/ETH/Gold/UNI Flying Rocket | 9 | 0–1/20 | 0% throughout | N/A |

Correct/wrong tally across the 38 reports' major calls: 5/5 scorecard balance held on every BTC report graded; the framework never once let both FK and FR score ≥12 simultaneously (Hard Rule 5, 38/38 clean).

## 4. Prediction-Accuracy Analysis

- **EV-calibration bias**: pre-calibration range was +0.5%→+5.1% (systematically bullish, into a falling market); post-calibration range is −1.8%→+0.5%. The optimism bias is measurably gone.
- **Matrix hit-rate**: the flattened Rally grid correctly avoided assigning majority probability to a rally at the exact moment of maximum fear (Jul-1: score 16, 3-day F&G 12.7, EV +0.2% — marginally positive, not manufactured, and directionally correct ahead of the Jul-2 squeeze).
- **Biggest thesis miss**: none of the settled-fact type — the biggest *structural* miss is the pyramid-inversion recurrence described in §1, a design gap, not a wrong call.
- **Stop near-misses**: BTC's Jun-30 wick to $57,779 came within 0.4% of the retired legacy $58K stop and did not trigger the live compound stop (score never dropped below 12) — the compound design worked exactly as intended under real stress.

## 5. Structural Flaws, Ranked

1. **Override/veto time-correlation** — the Override can only fire in a slow grind, not a sharp reversal, which is precisely when the last tranche matters most.
2. **FR internal-consistency bug** (fixed this cycle): the N/A-denominator rule was never ported to Flying Rocket with FK's `ceil(fraction × denom)` fix — combined with FR's fixed Phase-3 threshold (≥8 of 9) and round-down convention, Phase 3 was unreachable the moment any gate went N/A.
3. **FK Phase-2/1B operator-precedence ambiguity**: the `(≥N gates AND ≥3 [V]) OR Override` clause was explicitly parenthesized for Phase 3 in June but never applied to Phase 2 or 1B.
4. **Sentiment-leg cliff, no hysteresis**: the whole Jul-2 score/Override state balanced on 3-day F&G reading exactly 15.0 vs 15.1 — fixed this cycle with a letter-of-the-rule tie-break default plus a disclosed conservative-only exception.
5. **FR total silence since Jun-18** — not a code defect, but a coverage gap that means the framework's short-side machinery is unaudited through the one stretch (sustained-negative funding, live squeeze) built to test it.

## 6. Verified Tuning Set (2026-07-04 cycle)

**39 net-distinct tunes went to adversarial review (83 raw proposals, deduplicated); 38 reached apply_ok=true, 1 rejected at the pre-apply audit.** Exact before→after text for every adopted tune is in the two SKILL.md Framework Revision Log entries dated 2026-07-04. Summary by category:

| Category | FK tunes adopted | FR tunes adopted |
|---|---|---|
| Data integrity (provenance, sentiment/momentum input rules, calendar locks) | 8 | — |
| Gold/low-vol asset adaptation | 2 | — |
| Stop discipline (migration ledger, calendar-lock, checkpoint discipline, coherence re-scope) | 4 | — |
| Scoring/EV (reconciliation lines, sum-checks, vacuity labels) | 4 | — |
| Squeeze-trap / gate mechanics (decoupling, regime-attribution, gate-class labels) | — | 5 |
| Sign-convention fix + carry zero-floor | — | 1 |
| Preflight veto / stand-down accountability | — | 3 |
| Collar symmetrization + single-observation durability port | — | 2 |
| Cross-framework reconciliation (FK≥12 force-cover, canonical-spot extension, EV sum-check) | — | 3 |
| Trend-term confirmation throttle + cap-regime vacuity/EV-voice demotion | — | 4 |

**Rejected (why it matters):**
- **FK (23 held):** a gate-1 sentiment-streak tolerance and a "durability-symmetric" streak reset would both have lit gate 1 at the exact BTC cycle high/low, arming a veto-bypass Phase-1B unlock into a record-outflow tape. A capitulation squeeze-expiry credit and a graduated worsening-flows-veto half-step both re-open the "strip points/lift the veto during deepening fear" pattern R1/R2 were rejected for — and both misquote their own cited numbers under independent verification. A 1B alternative-breadth path and a gold Phase-2 second key are both capital-neutral over the realized path (the R4/R8/R15 defect) or deploy an unthrottled full tranche exactly where the throttle is supposed to bind. A zone-freeze rule and a zone-placement/band-anchor-lock family would have frozen a ladder across a confirmed 200-week break, forcing the stop up and blocking the Jul-2 structure-conditional re-stage the framework correctly executed. A round-down-at-.5 rounding rule misquotes the corpus (BTC rounds up, ETH rounds down — a documented asset-specific convention).
- **FR (18 held):** the dominant pattern was duplicate/inferior proposals of tunes that survived in stronger form elsewhere (three "published-score-governs" variants collapsed into one; three single-observation-durability mirrors collapsed into Principle 13). Genuinely rejected: a carry-ledger sign-attestation line (redundant with the corrected sign convention itself); a non-crypto score-floor/baseline-conditioning family (encodes an unverified qualitative judgment call); a falsifier-anchoring rule requiring falsifiers sit exactly at a modal-band floor (over-rigid against the framework's disclosed-estimate convention).
- **Withheld at apply-time (guardrail):** one FK tune — a Cross-Asset Conviction-vs-Capital Ledger — rejected as a family resemblance to the already-rejected R5.

**No rejection this cycle reversed a prior-adopted (2026-06-11) tune** — the anti-thrash veto held throughout.

## 7. Remaining Edits Required + Coverage Disclosure

Outstanding, not yet shipped:
1. The Override/veto time-correlation structural gap (§5 above) needs a dedicated fix proposal next cycle — likely a decaying veto or an explicit "grind-only" disclosure, not attempted this pass to avoid shipping an untested reversal of the Jun-11 veto design.
2. FK Phase-2/1B unlock clauses still lack the explicit parenthesization Phase 3 received in June (precedence is unambiguous in practice but not on paper).

**Coverage disclosure, stated plainly:** all 39 reports extracted cleanly, all 7 framework×asset series graded, all 83 proposed tunes received a full 3-skeptic panel (0 unadjudicated) and a pre-apply audit pass. **SOL has zero reports since 2026-05-28** — its prior predictions remain untestable and are carried forward as a coverage gap. **Flying Rocket has zero reports since 2026-06-18** — every FR-side verdict above is "validated by construction" or "not exercised," never "validated by outcome." Gate 7 (regime-relative capitulation) has now stayed dark through two consecutive full legs down — a watch item, not yet evidence the threshold is unreachable.

## 8. What to Preserve, and an Honest Statement of Uncertainty

Preserve without modification: the flattened Rally grid, the (now two-sided) Verdict-Confidence Collar, the compound stop design, the mandatory computed companion score, and every one of the 17+41 held rejections across both calibration cycles — none should be revisited absent new out-of-sample evidence of harm.

This is a single out-of-sample cycle (N=1) covering one bear-market grind into one macro-driven squeeze. It has not seen a euphoria regime, a genuine capitulation flush (gate 7 has now stayed dark through two consecutive textbook lows), or a single live Flying Rocket report since this cycle's tunes were designed. The validations above are real, but they are validations of *this* regime shape only, and the 38 newly-adopted tunes are additionally untested by definition — they are corrections derived from this cycle's own grading, not yet exercised on a subsequent report. Re-validate after the framework has been tested against a euphoric top and at least one live short cycle before treating any of today's "adopted" tunes as durable across regimes.
