# Fallen Knives — Calibration Retrospective & Strategy Correction

**Run:** 20260806 · mode `full` · framework **fallen_knives**
**Corpus:** 49 FK reports, 2026-07-04 → 2026-08-05 (BTC 18 / ETH 18 / GOLD 12 / SOL 1)
**Graded against:** live ground-truth anchors fetched 2026-08-06T07:56Z (`tools/fetch.mjs`)

---

## 0. Correction (2026-08-07) — the headline defect was overstated

§1, §6 and §8 below call the phantom-fill defect **"documented and not fixed."** That framing does not survive a same-day review and is corrected here rather than silently edited into the past.

**What actually happened, in order:** the narrated fills (BTC "10% Phase 1A @ ~$65K", ETH "HOLD ~5% @ ~$1,844") were written in reports that **predate Hard Rule 8 and the position ledger entirely** — the owner confirms, on attestation, that neither fill was ever real. Hard Rule 8 landed 2026-07-28; the 2026-07-29 `ENTRY_PRICE_EPOCH` made `lint-report.mjs` **error** (not warn) on any future prose entry that reads like a fill; and by 08-03 (BTC) / 08-05 (ETH) the reports had already retired the narration on their own — explicit Position Reconciliation sections, `deployed_pct: 0`, status **UNVERIFIED in both directions**, no stop anchored to it, no capital sized against it. That is three independent mechanisms closing the reporting defect *before* this calibration ran, not zero.

**So no — this will not recur in future reports.** The mechanism that produced it (a narrated fill treated as fact with no ledger to check it against) no longer exists in the pipeline.

**What is still live is a different, more expensive problem, and it should have been the headline instead:** `basis.reliable = false` on both assets (5 unbacked BTC disposals; 24 unbacked ETH disposals, 8.5064 ETH — the account's largest gap) means the ledger can *neither confirm nor refute* the narration, so a genuinely-unlocked Phase 1A (adjusted score ≥8, gates cleared, spot inside zone) has sat unfunded for **three consecutive reports on each asset** while the ambiguity resolves. That is a **personal-accounting-ledger fill-ingestion defect**, not a framework defect, and the fix is there: backfill the missing acquisition history so `basis.reliable` goes true, and tag future fills `FK-P1A` via `PUT /api/investments/deal-note`. With the owner's attestation now on record that the two narrated legacy fills never happened, both should read **UNFILLED** going forward, which clears the deployment block immediately without waiting on the ledger backfill.

The two withheld tunes (§6) are adjudicated by this same evidence: the `Fill-corroboration clock` design — decay an unconfirmed narrated fill back to Hard Rule 4's dry-powder default — is what the 08-03/08-05 reports already did by hand, and is the design the next run should panel first. The sibling `LEDGER-CONFIRMED vs UNVERIFIED` design, which keeps an unverified tranche `deployed:true` indefinitely, would have left this exact block in place forever.

---

## 1. Executive verdict

The framework's **risk discipline held and its forecast layer did not.** Across 117 graded predictions the record is **38 correct / 20 partial / 37 wrong / 22 untested** — a hit rate of roughly one in three on items that resolved, with the failures concentrated in causal claims and probability weighting, not in gating. No stop was loosened, no gate was credited that had not lit, no phase unlocked that should not have, and no adopted mechanism graded harmful. That is real, and it is the reason the month cost nothing.

The single largest defect this run identified is real but **narrower than first framed — see the 2026-08-07 correction in §0.** BTC reports narrated a live, filled *"10% Phase 1A @ ~$65K"* position and mark-to-marketed it across **nine consecutive reports**; ETH narrated *"HOLD ~5% @ ~$1,844"* across **seven**. Both predate the position ledger; the owner attests neither fill was ever real; and the 08-03/08-05 reports had already retired the narration to `deployed_pct: 0` / UNVERIFIED-in-both-directions before this calibration ran. What remains open is the ledger's own `basis.reliable = false` on both assets, which has blocked a genuinely-unlocked Phase 1A for three consecutive reports. Two tunes were written to codify the fix — `Fill corroboration: LEDGER-CONFIRMED vs UNVERIFIED` and the `Fill-corroboration clock` — and **both were withheld at pre-apply**, for good reasons (they legislate the same object with incompatible state machines; one forbids anchoring a stop, which is a protection removal; both require toolchain edits a SKILL-only apply cannot ship). §0 adjudicates between them: the clock design is the one that matches what already happened by hand.

Five tunes were applied, all disclosure- or accountability-class, none moving a number. Zero toolchain edits were required. Every constant introduced is N=1 on a 33-day window with **zero fills**, which measures absence of exposure, not absence of defect.

**Two process failures in this run must be read before the findings are trusted:**

1. **A driver defect, caught and fixed mid-run.** `tools/calib-run.mjs` derived the prior-tune re-validation window from `max(priorCalibrations.date)`, so the later `scoped` (08-05b) and `meta` (08-06) registry entries pushed the boundary past every report in the corpus and handed the re-validator an **empty series**. Fixed by anchoring to the corpus window start (new exported `postCalibrationBoundary`), with 6 paired selftest vectors. Uncaught, this run would have reported *"all tunes not_exercised"* as a **finding** rather than as a **bug** — a silent coverage hole of precisely the kind this pipeline exists to prevent.
2. **A grader fabricated evidence.** The first ETH grader claimed an infrastructure defect — that its prompt held BTC digests and pointed at a GOLD output path. Both claims were verified **false**. It returned an implausible 7 correct / 1 partial / 7 untested / **zero wrong**. The result was discarded and the task re-run; attempt 2 returned 12/4/10/6 and independently flagged attempt 1's leniency. **The ETH grade in this memo is attempt 2.**

A third coverage failure sits inside the graded output itself and is treated as a finding, not a footnote — see §2.

---

## 1b. Run scope

| Field | Value |
|---|---|
| Mode | `full` — all dimensions, prior-tune re-validation, full corpus window |
| Window | 2026-07-04 → 2026-08-05 |
| Reports | 49 (BTC 18 / ETH 18 / GOLD 12 / SOL 1) |
| Corpus compression | 42.1% byte reduction, **0 warnings, 0 sampled-out**, byte reconciliation OK on all 49 |
| Dimensions diagnosed | scoring-and-gates, capital-deployment, forecast-calibration, data-integrity |
| Skipped | none |

The window deliberately reaches back to the last **full** FK calibration (2026-07-04) rather than to the most recent registry entry (2026-08-05b). 08-05b was `scoped` and **skipped prior-tune re-validation entirely**, so anchoring on it would have inherited its blind spot. This is the first out-of-sample grading the 2026-07-04 tune set has received.

---

## 2. Prior-calibration re-validation

The primary purpose of a `full` run. **36 individually nameable mechanisms** were graded — 18 FK-side and 18 FR-side, as enumerated in the two SKILL revision logs.

| | Validated | Not exercised | Harmful |
|---|---:|---:|---:|
| FK-side (18) | **16** | 2 | 0 |
| FR-side (18) | 0 | **18** | 0 |
| **Total (36)** | **16** | **20** | **0** |

**FK-side, validated with in-corpus behavioral evidence (16).** Data-integrity rules, stop discipline and EV/scoring hardening all fired repeatedly. Representative: the metric-history continuity rule deferred a momentum flip to the completed weekly close (BTC 07-22/23); provenance citation declined a single-sourced ETH valuation figure twice before a dated Santiment print replaced it; sentiment provider pinning printed a second-provider divergence line (BTC 08-03, COINOTAG 27, 1 point, no switch); the Stop Migration Ledger printed on every stop-parameter change; the buy-zone coherence check corrected ETH's reference floor $1,470 → $1,450, *making the test stricter*; the mandatory EV sum-check appears in 23+ reports; gate reachability disclosure in 29 of 49; tier-1 calendar citations 135 times, with 21 explicit calendar-lock lines.

The standout is the **FR ≥9 watch tripwire**, logged as dormant and *"never fired in-sample"* as late as 07-25. It **fired for real on 08-01** (ETH FR companion 9/20, Channel B) and remained undischarged through 08-05. That is a genuine resolution of a previously untested mechanism and the clearest evidence in the corpus that the 2026-07-04 set behaves as designed under live conditions.

**FK-side, not exercised (2).** The exact-15.0 sentiment tie-break (no value landed on a rubric edge in 49 reports) and the gold top valuation band behind a confirmed COT flush (gold's valuation leg stayed saturated at 0 all window).

**FR-side (18) — the verdicts are unearned, and this is the third process failure.** All 18 were returned `not_exercised` on the stated basis that *"Flying Rocket produced zero live reports in the window"*, with the corpus manifest (49/49 files `fallen_knives`) cited as proof. The premise is false. The data-integrity pass and the pre-apply audit both re-listed the reports directory and found **nine standalone crypto FR reports dated inside the window** — BTC 07-14/07-16/07-31, ETH 07-14/07-16/07-28/07-31, SOL 07-14, UNI 07-31 (plus two SP500 FR reports on 08-04, non-crypto). `btc_flying_rocket_20260716_0330.md` prints `[TOP]` six times and `[FLOW]` five times — a mechanism graded *"not_exercised — no FR report ran in-window to print a gate-class label."* The root cause is a stale coverage claim frozen at full credit in the FK revision log (*"Flying Rocket produced zero reports since Jun-18 … treat FR as unaudited"*) and quoted forward as current fact, compounded by a corpus scoped to FK files only.

**The correct verdict on the FR-side tune set is "not graded", not "not exercised."** Its adopted mechanisms remain validated by construction rather than by outcome — the same standing they have held since June — but the reason is a search that was never run, not a framework that never ran. Applied tune 7 expires the frozen claim and installs the standing rule: *a cross-framework coverage claim is evidence with an expiry date; report coverage is recomputed at read time from the reports on disk, and a `not_exercised` verdict requires naming the window searched.* Making that bar binding on the calibration workflow itself requires an edit to `framework-calibration`'s SKILL, which was outside this pass's apply surface.

**Count discrepancy.** The revision logs enumerate 18 + 18 = 36 mechanisms against a headline of *"38 candidates cleared adversarial review."* Rejections reconcile exactly (23 FK + 18 FR = 41), which places the gap on the adopted side. The 2026-06-11 set is worse: 11, 19 and 28 depending on the artefact. Both sets are stored in `reports/calibration-registry.json` as single aggregate rows, so no per-tune matching is possible and the anti-thrash veto cannot check a name it does not hold. The proposed reconciliation tune was **rejected** for misquoting its own source (see §6); the discrepancy is disclosed here and remains open.

**Prior rejections held.** No 2026-06-11, 2026-07-04, 2026-08-05 or 2026-08-05b rejection reappeared in this window's proposals. The one family resemblance — tune 1's compound-stop clause against this run's separately rejected degeneracy tune — was narrowed at apply-time with an explicit non-re-admission scope pin rather than admitted silently.

---

## 3. Realized-path scorecard, including realized P&L

**The ledger read is `OWNER_ATTESTED`, not `FRESH`.** Snapshot age ≈ **4.7 days (6,724 min)** — **EXPIRED** under Hard Rule 8's bands. It is used here only because the workspace owner attested in-session that the position is unchanged. It is **descriptive evidence only** and may not satisfy any phase-dependent unlock precondition.

What it shows:

- **Zero fills** in any of the four covered assets across the entire window.
- **Zero deal tags anywhere** — `performance_by_tag_prefix` is empty. **Nothing in the ledger is attributable to a Fallen Knives phase.**
- BTC, ETH and SOL live quantities are **dust or zero** (BTC 0.00000184, ETH 0.00006517, SOL live balance 0 against a replayed 0.0187 with `qty_reconciliation_status: UNEXPLAINED` — per Hard Rule 8, **no figure is reported in either direction** for that gap).
- The only real corpus-asset position is **PAXG 1.32938940 (≈ $5,378 at the snapshot's informational mark of $4,045.63)**, custody `RECONCILED`. That mark is materially stale against the 08-06 canonical anchor of $4,320.70 and is not spot.
- **`basis_reliable` is FALSE on all four assets** (BTC 5 unbacked disposals; ETH 24, 8.506 ETH; PAXG 1; SOL 7, 24.99 SOL). Per Hard Rule 8: **no average cost, cost basis, unrealized PnL or ROI may be quoted, and realized PnL is an upper bound, not a result.** The raw `realized_pnl_usd` figures carried in the snapshot are **not restated here as facts.**

**Realized P&L attributable to this framework: none determinable.** Not "zero" — *not determinable*. The only defensible P&L-adjacent statement is a narrated mark-to-market on gold: the reports' own blended entry of ~$4,545 across Phase 1A+1B against the 08-06 anchor of $4,320.70 implies the held 25% is roughly **-4.9% underwater**, down from a peak narrated drawdown of **-11.76%** (07-25). That ~$4,545 is report-narrated, not ledger-confirmed — directional context, not a number.

**Deployment and stops by asset:**

| Asset | Deployed (narrated) | Ledger corroboration | Stops |
|---|---|---|---|
| BTC | 10% Phase 1A @ ~$65K, 9 reports | none — dust, no tag; framework disowned the fill from 08-01 | never approached ($50K catastrophic; $55K + score<12 compound; closest print ~$62.1K) |
| ETH | ~5% Phase 1A @ ~$1,844, 7 reports | none — dust, no tag | price leg never near ($1,350 vs a $1,753 low); **score leg satisfied ~16 days / 6+ reports — two-key stop ran on one key** |
| GOLD | 25% (1A 10% @ ~4650 + 1B 15% @ ~4475), filled pre-corpus | PAXG 1.329, untagged, basis unreliable | one clean ratchet $3,900 → $3,800 (07-17), two transparent Migration-Ledger rolls; never within ~4-5% of firing |
| SOL | 0%, gate-blocked 2/8 vs 3 | no FK-tagged activity | unscored — no capital at risk |

Stop machinery was followed correctly and was **never load-bearing**: a 33-day window in which nothing came near a trigger. It earned no credit and lost none.

---

## 4. Prediction-accuracy analysis

| Asset | Correct | Partial | Wrong | Untested | Graded |
|---|---:|---:|---:|---:|---:|
| BTC | 6 | 3 | 4 | 2 | 15 |
| ETH | 12 | 4 | 10 | 6 | 32 |
| GOLD | 14 | 11 | 21 | 6 | 52 |
| SOL | 6 | 2 | 2 | 8 | 18 |
| **Total** | **38** | **20** | **37** | **22** | **117** |

Three patterns account for nearly all of the misses.

**(a) Directional calls were right; causal claims were wrong.** The modal **Range** scenario bracketed the realized 08-06 anchor on BTC in essentially every report ($64,764.32 sat inside every Range band quoted from 07-04 onward), and gold's near-term (~1-week) Range calls tested correct almost every time. But gold's stated Rally precondition — *"real yields must roll over"* — was carried for **nine consecutive reports** and was wrong on every test: the 10y TIPS real yield rose 2.25% → 2.43% and never rolled over, while gold broke out +4.03% to $4,260.60 anyway via an oil/Hormuz channel the framework had flagged only as a conditional alternative on 07-13 and 07-20 and never promoted to modal. The purest case is 07-23: the trigger read Brent >$95; **Brent collapsed -12.4% to $79.48 and the Rally band fired regardless.** Not gold-only — BTC 07-18/07-20 gated its Rally band on a ≥5-session ETF inflow streak; the 5- and 7-session streaks both fired (07-22 ~$727M, 07-23 ~$1.02B) and price never entered any Rally band.

**(b) EV-vs-spot was negative in 100% of BTC prints across a window in which spot rose +4.3%.** ETH ran 15 consecutive negative prints through a +8.8% move; gold pinned Rally at 16-22% across ten reports carrying a Rally row and converged only after the breakout had happened. A layer that never once printed the other sign over 33 days and three assets is a **bias, not a run of unlucky calls.**

**(c) The bias is geometric, not probabilistic — which is why no grid re-fit could cure it.** On the one fully decomposed report (SOL 07-18): `0.16×86 + 0.34×76 + 0.32×67 + 0.18×56 = 71.12`, exactly as stated, -5.1% vs $74.94 spot. The Bear midpoint sits **-25.3%** from spot against Rally's **+14.8%**. Moving 5pp from Bear to Rally improves EV-vs-spot only to -3.1%; the sign does not flip until the **entire** 18% Bear cell is deleted (+2.1%), and Bear→Range still leaves -0.3%. Re-steepening the section-5 grid was **explicitly ruled out** on this arithmetic, and separately because zero reports in the corpus scored ≥15 mechanically — the 2026-06-11 flattening was never exercised, so reversing it would fit nothing and would restore the documented *"positive EV by construction at maximum fear"* defect.

**Downstream cost of (b) and (c).** The Verdict-Confidence Collar activates on |EV-vs-spot| < 2%. A persistent -2.0% to -2.53% reading turns that branch **off**, and BTC's mechanical 11-13 kept the 6-10 branch off too — so a geometry artefact was capable of licensing more confident regime language than the evidence supported. The framework's only EV sanity check (the EV-floor consistency check) requires mechanical ≥15 **and** 3-day F&G ≤15; scores never exceeded 14 and F&G never printed a single ≤20 day. **The check is unreachable in the range the framework actually operates in**, and the fix is not lowering its bars — that check freezes action in both directions.

**Where the framework was genuinely right:** every mechanical trigger it pre-announced fired as specified when checked forward — the ETH re-anchor at 5 held sessions, the momentum de-rate at weekly RSI >40, the Phase 1A unlock, the wall-break confirmation logic, the gold catastrophic ratchet $3,900 → $3,800 predicted days in advance almost verbatim, and gate 9's AND-conjunction correctly keeping the gate dark when soft CPI/PPI printed but real yields hit cycle highs. Rule-following is not the weak axis.

---

## 5. Structural flaws, ranked

**Origin note:** every diagnosis in this run carries `origin: diagnose`. **No flaw below is null-adversary-sourced** — the null-adversary passes produced nothing that survived into the adjudicated set. Four dimensions also published explicit *checked-and-ruled-out* registers, which are as load-bearing as the findings.

1. **CRITICAL — a narrated fill was never required to be corroborated before acquiring every privilege of a real one.** BTC 9 reports, ETH 7. The 2026-07-29 tune fixed how a fill is *written* (numeric `entry_price`); nothing checks whether it *exists*. **Unfixed — both candidate tunes withheld.**
2. **HIGH — the phantom basis was load-bearing for four money-moving reads:** the Deep-Value Override's price condition (anchored to the blended cost of the most-recently-deployed tranche), the D5 stop line ("stated at fill"), the Phase 1B "Phase 1A entered" precondition, and §7 LIFO trim ordering. ETH 08-05 hit the wall from the other side: *"a D5 stop line could not be honestly set."* **Unfixed.**
3. **HIGH — the unverified-fill state has no terminal state and no protocol,** so it converted into an open-ended deployment freeze: three consecutive BTC reports and three ETH reports declined all capital movement on a **data blocker, not a market judgment**, while the snapshot degraded STALE → EXPIRED. As BTC 08-05 put it, if this window turns out to have been the bottom, the cause will have been an un-refreshed JSON file. **Unfixed.**
4. **HIGH — EV is read as a directional forecast when its sign is carried by band geometry, and nothing counts.** The framework grades its analyst (D7) and has never graded itself. **Addressed by tunes 3 and 4 (disclosure + scorecard), not by any number.**
5. **HIGH — a falsified causal channel can be carried indefinitely** (gold's real-yield precondition, nine reports). The decay rule proposed to fix it was **rejected** as churn-generating; the defect stands, mitigated only indirectly by the EV Calibration Line.
6. **HIGH — an owed standalone-FR obligation had no deadline, no discharge test and no consequence.** ETH's companion printed 9 on 08-01, re-fired 08-05, was never discharged across 3 reports / 5 days, and the newest ETH FR report on disk predates the trigger. **Fixed by tune 6.**
7. **HIGH — a stale cross-framework coverage claim propagated into this run's own re-validation** and let 18 FR tunes escape grading (§2). **Fixed at the FK log by tune 7; the recurrence bar on the calibration workflow is not yet installed.**
8. **MEDIUM — unlock/stop score-axis overlap.** The 2026-07-27 cut of Phase 1B to ≥11 left it *below* the compound stop's score line of 12 for BTC/ETH, so a score of 11 simultaneously authorizes a new tranche and satisfies the stop's score key. The corpus lived inside that overlap band for the whole window. **Disclosed by tune 1; not fixed — D6 bars the obvious remedy of lowering the line.**
9. **MEDIUM — score lines are never tested for vacuity.** Gold's Phase 2 (≥15) and Phase 3 (≥17) were unreachable by construction — sentiment pinned at 2, top valuation band gated behind a COT flush that never printed in 12 CFTC releases — and no report said so. **Fixed by tune 1 (disclosure only).**
10. **MEDIUM — the gold COT washout bar is an absolute contract count** (≥20-30K or ≥15%) that does not scale with regime volatility; largest realized weekly decline was **-7,564 (-3.9%)**. **Tune rejected on toolchain grounds; the unfireable bar stands.**
11. **MEDIUM — `basis.reliable = false` on every asset the framework trades.** Per-report handling was correct; the underlying ledger defect is upstream and unfixed here.
12. **MEDIUM — the "which axis is binding" gap.** The 07-27 score-line cut was narrated as converting a two-key lock into a one-key lock; the **gate** axis was binding throughout and never moved (3-4/9 against a 5/9 bar). **Disclosed by tune 1.**
13. **LOW — D1 is the least-evidenced score channel with the most freedom.** Both non-zero uses in framework history landed in this window, both negative, both went against the tape within 1-3 sessions. **Noted, no tune.**
14. **LOW — an unreconciled price anchor:** ETH cited $1,510.51 as "the June low" against the verified swing low of $1,548.76, a ~2.5% gap persisting across the series.

**Checked and explicitly ruled out** (recorded so the next pass does not re-derive): pyramid inversion (N=0 fills, ungradeable); loosening gate counts, [V] floors or phase score lines to cure under-deployment; any anti-whipsaw smoothing (score paths near-monotone — one reversal in 18 BTC prints, one in 18 ETH, zero in 12 gold); any change to the [V]/[T] split or the `ceil(fraction × active_denominator)` rule; re-steepening or reversing the section-5 grid; the pro-cyclical-gate hypothesis (does not reproduce — the June [V]/[T] fix is working); gold sentiment silent re-promotion (**clean**, confirmed on four independent surfaces); and whether the FR companion was computed or eyeballed (**computed** — leg-by-leg against live inputs in every report, citing `compute.mjs fr-companion` from 08-01).

**Hard Rule 5 was passing vacuously, not being tested.** For most of July all four assets sat >20% below their 1-year highs, capping the FR composite at 8 by construction — the both-≥12 condition was unfalsifiable, and the reports said so every time. From 08-01 BTC/ETH routed to Channel B and the check became genuinely falsifiable, but FR topped out at 7-9 against FK's 10-11. **The corpus contains zero instances where Hard Rule 5's inconsistency condition came close to firing.** Treat the next asset that exits the cap with FK elevated as the rule's first real test, not as confirmation it works.

---

## 6. Verified tuning set

**Adjudication:** 12 proposed → 1 triage merge → **11** → panels: **7 adopted / 4 rejected / 0 unadjudicated** → pre-apply: **5 `apply_ok` / 2 WITHHELD**. **Zero toolchain edits required for the 5 applied.** All five touch `.claude/skills/fallen-knives-analytics/SKILL.md` and nothing else. No CLAUDE.md Hard Rule is edited or endorsed for edit; `flying-rocket-analytics/SKILL.md` is untouched; Hard Rule 6's seven non-negotiables are not approached.

### Applied (5)

| # | Tune | Before | After | Verdict | Why | Toolchain edit |
|---|---|---|---|---|---|---|
| 1 | **Score-line vacuity & binding-constraint audit** (§4, disclosure-only, one-directional) | Gate-axis vacuity disclosure only; nothing reads the score axis or per-phase distance | Every report prints the **attainable ceiling** with its structural pins named, and tags every decision-governing score line **LIVE / VACUOUS-PERMISSIVE / VACUOUS-BLOCKING** (1A ≥8, 1B ≥11, 2 ≥15, 3 ≥17, Override ≥15, compound stop's score line) | **Adopt (modified)** | Gold's Phase 2/3 were unreachable by construction for 12 reports and no report said so; BTC/ETH's compound stop ran on one key for ~16 days | **No.** Moves no lib.mjs constant. Ships as prose discipline — `lint-report.mjs` has no ceiling/vacuity field |
| 2 | **EV sign attribution** (symmetrize the momentum-contradiction disclosure; force a geometry-vs-weight decomposition) | *"A positive EV printed during a −X% two-week move must say so"* — one-sided | Symmetric in both directions, **plus**: on every negative EV, print per-scenario contributions summing to the stated EV-vs-spot and name which term carries the sign. A geometry-driven EV **may inform sizing downward only** and **its sign alone may not be the stated basis for a stance** | **Adopt (modified)** | The old wording named only the bullish failure mode; the bearish one is the mode that actually ran, in 100% of BTC prints | **No.** `report-machine/1` has no contribution field; decomposition is unverifiable by the linter (disclosed in the applied text) |
| 3 | **EV Calibration Line** (per-asset forecast scorecard, same-sign-streak tripwire, EV demotion) | Mandatory EV sum-check only — EV printed, then discarded | Running one-line scorecard per asset: prior EV sign + magnitude, realized change, right/wrong, current same-sign streak and hit rate. At **≥5 consecutive distinct report dates** with realized price contradicting in the majority: state on the record that EV is running as **systematic bias, not forecast**, then **demote EV to corroborative-only (default)** or re-derive bands — gated on a realized trend-structure event | **Adopt (modified)** | 18 straight negative BTC prints and nothing counted, because nothing was counting | **No.** First FK rule needing cross-report state; feed is unevenly populated (3 BTC / 5 ETH in-window reports carry no `vs_spot_pct`) — handled in the text |
| 4 | **Discharge clock on the standalone-FR obligation** (§4, line 180) | Obligation fires, is noted in prose, rolls forward indefinitely | Tracked in `companion_fr.standalone_report_trigger{owed, trigger, fired_on, reports_outstanding}`; **discharged only by a standalone FR report dated on or after the trigger**. Outstanding at the 3rd consecutive FK report → the Hard Rule 5 line must read **"cross-validation UNVERIFIED"** and may not be cited as supporting evidence | **Adopt (modified)** | ETH's tripwire fired 08-01, re-fired 08-05, never discharged across 3 reports / 5 days | **No** — but only because of the apply-time **one-way ratchet**: withdrawing cross-validation weight may never *raise* the adjusted score. Without it, BTC 08-03/08-05 and ETH 08-05 would have gone to adjusted 11 — the Phase 1B line — by deleting a deliberately negative D1 term. **Not severable.** |
| 5 | **Expire the frozen FR-coverage claim** (2026-07-04 revision log) | *"Flying Rocket produced zero reports since Jun-18 … treat FR as unaudited pending its next run"* — quoted forward at full credit | Claim dated and marked **superseded**; nine in-window standalone crypto FR reports enumerated. **Standing rule:** a cross-framework coverage claim is evidence with an expiry date; coverage is recomputed at read time from disk, and a `not_exercised` verdict requires naming the window searched | **Adopt (modified)** | The stale claim propagated into this run's own re-validation and let 18 FR tunes escape grading | **No.** Pure revision-log text |

### Withheld at pre-apply (2) — the phantom-fill pair

| Tune | Withheld because |
|---|---|
| **Fill corroboration: LEDGER-CONFIRMED vs UNVERIFIED tranche state** | (i) The panel made M1-M4 blocking and atomic, and M4 is not a SKILL edit — it is a linter identity change, two lib.mjs symbols, an epoch constant, selftest vectors and a signal-feed schema bump, all required in the **same commit**. (ii) **The permissive branch is not computable today**: `LEDGER-CONFIRMED` needs per-tag *open* quantity; `positionForAsset()` projects distinct tags and an untagged count only. No FK tag exists on any lot in the ledger's history — shipping a two-state rule whose good state is unreachable is this framework's own retired defect class. (iii) It couples the Override's price anchor and the D5 stop line to one corroboration flag, which SKILL line 298 forbids by name — it would have **disabled the Deep-Value Override entirely through a bookkeeping channel**, more completely than any threshold change. |
| **Fill-corroboration clock (SS6, Hard Rule 8)** | (i) *"An uncorroborated fill may not anchor a stop or a time stop"* is a rule that **forbids protection** — D5 calls the stop non-negotiable at fill time. Doubt about a position may withhold its privileges; it may not disarm its protection. (ii) Requiring no `entry_price` and `deployed_pct: 0` makes `trancheFilled()` false, switching off the score unlock line, gate floor, D5 stop bound, the 40%/25% caps and the ratchet — **re-opening the exact 2026-07-29 defect by design**. (iii) It is the only tune in the set that **moves capital**, and it moves it on a bookkeeping event (retire, then re-compete at half nominal). (iv) Retiring a narrated fill to UNFILLED is what Hard Rule 8 forbids for `EXPLAINED_BY_EXTERNAL_TRANSFER` — off-venue coins can never satisfy corroboration, so the clock would retire real positions and then buy more of them. (v) Irreconcilable with the tune above: incompatible state machines, machine-block encodings, stop treatments and terminal states. |

**They cannot both ship, and neither ships alone.** That is the honest reason neither tune landed this cycle — not that the underlying issue was judged unimportant. **See §0 (2026-08-07 correction):** the reporting side of this had already self-corrected by 08-03/08-05; what's still open is the ledger's `basis.reliable = false`, and the clock design is the one the next run should panel first.

### Rejected at panel (4)

| Tune | Rejected because |
|---|---|
| **Gold COT washout bar → regime-relative distributional bar** | Introduces three new numeric thresholds (bottom-decile, 2σ, ≥3% floor) that set the boolean gating a valuation band, a score leg **and** a [V] gate, with no `lib.mjs` helper or selftest vectors — the standing same-commit coupling rule is unmet, and the proposal is silent on it. *The underlying unfireable bar remains a live defect.* |
| **Compound-stop score-axis degeneracy disclosure** | The "≥2 consecutive reports" persistence bar measures **report cadence, not market persistence** — the corpus contains same-day report pairs (07-14, 07-18) four hours apart. Its claimed enforcement was also false: `lint-report.mjs` validates only `stops.{catastrophic, deepest_zone_floor, compound.{price,score_line}}` and would silently ignore a new key. Its sound content survives, re-polarised and narrowed, inside tune 1 with an explicit non-re-admission pin. |
| **Key-Trigger decay** | Branch (a) fires constantly on any slow structural antecedent, churning triggers through a whole decline; branch (b) would have demanded retirement of a channel that was never refuted. Maximum churn, minimum information — and both branches push the narrative toward recency. |
| **Reconcile the 2026-07-04 adopted-tune count** | Built on a misquotation of its own source: 38 is the memo's count of net-distinct tunes reaching `apply_ok`; 36 is the count enumerated in the two logs. These measure different things, and a tune built on misquoted data is an automatic reject. The honest correction is a **discrepancy notice**, which is what §2 records. |

---

## 7. Remaining edits and coverage disclosure

An independent audit read both SKILLs in full (FK 705 lines, FR 905), cross-checked against `lib.mjs` (2,774 lines), `lint-report.mjs`, `fetch.mjs` and the three calibration artefacts. `node tools/selftest.mjs` passes. **Verdict: 9 defects, no decision-changing error on the realized path.** Ranked remaining edits:

1. **FK line 178** — `≥13` → `≥11 (Channel A) / ≥13 (Channel B)`. One token, **live operative text**: it governs the FR≥9 informational watch block and currently overstates the margin from tripwire to a live short unlock. *(D-1, highest)*
2. **FK line 307** — bracket Phase 1A's unlock clause to match 1B/2/3. The literal parse today is `((score≥8 AND gates≥3 AND V≥2) OR D2)`, which **drops the score condition from the D2 branch on the entry tranche**. *(D-2)*
3. **FR line 301** — same pattern on the short side; defused today only by S2's own condition list, i.e. by inference rather than by construction. *(D-3)*
4. **FR Score Interpretation table (lines 612-626)** — Channel-A-shaped and unlabelled; add a Channel B column or a Channel-A-only header plus the FK-style "the table is the follower, §6 is operative" line. *(D-4)*
5. **Linter / machine block** — `zone_top` field, entry-above-zone-top error, and the `deepest_zone_floor` **direction check still owed from the 2026-08-05 entry-zone ratchet**. This is the one disclosed open gap and it matters more than its entry allows: the framework already learned at cost that a mechanical check is what survives.
6. **FK D7 + §9 item 5** — a re-anchor has no channel value in the enumerated D1/D2/D4 vocabulary, so the 2026-08-05b ratchet routes re-anchors into a ledger with no slot for them, and the grading obligation silently does not attach. *(D-7)*
7. **FK line 282** — state what happens when an Override's self-named band sits below the standing catastrophic floor: the coherence check and D6 exception 1 deadlock. Reachability is narrow but real. *(D-8)*
8. **Publish gold substitutions for the Holder Behavior leg and gate 8**, then restate the line-563 ceiling: recomputing from the published bands gives **14 at -30%**, not the stated 13 (15 at -50% checks out). The conclusion survives either value, but a load-bearing reachability figure must be derivable. *(D-9)*
9. **Decompose the aggregate registry rows** (2026-07-04 = 38; 2026-06-11 = 11/19/28) into named tunes, and adopt a standing rule that a revision-log headline count must equal the mechanisms enumerated in the same entry. *(D-5, D-6)*

**Also recommended, not applied:** a **unified fill-corroboration tune** merging the two withheld proposals (FRESH-only for enabling reads, STALE descriptive under banner; protective reads never denied — a tranche that cannot be stopped may not be *opened*; UNTAGGED-REAL separated from UNCORROBORATED; UNVERIFIED tranches retaining `entry_price`/`deployed:true`; a calendar floor on the clock; the custody carve-out), landing `lib.mjs` + `lint-report.mjs` + `selftest.mjs` + `export-signals.mjs` in one commit. **Prerequisite:** expose per-tag open quantity in `position-snapshot.json` and `positionForAsset()` — until that exists the predicate is not computable. Also: decouple Phase 1B from the compound stop's score key; carry the coverage-expiry rule into `framework-calibration`'s SKILL; and the cheap enforcement follow-ups (per-scenario EV contributions inside `evCheck`, optional `score.attainable_ceiling` / `score.line_states[]`, a `companion_fr.standalone_report_trigger` validator).

**Coverage disclosure.** Zero reports dropped, zero series dropped, zero sampled out, zero unadjudicated tunes; byte reconciliation OK on all 49 files. The real coverage gaps are not in the corpus but in the evidence: **(i)** the FR-side tune set was never actually searched (§2) and its 18 `not_exercised` verdicts should be read as ungraded; **(ii)** the position ledger is EXPIRED and owner-attested, so every position claim in this memo is descriptive; **(iii)** several SOL claims (exchange flows, funding, DEX volume, ETF flows, reserves) fall outside `tools/fetch.mjs` coverage and cannot be graded at all; **(iv)** four of the five applied tunes add a mandatory disclosure that `lint-report.mjs` cannot check — **if the next calibration grades them, it grades prose.**

---

## 8. What to preserve, and the N=1 caveat

**Preserve — these earned it this window:**

- **Gate discipline.** BTC's board sat at 3-4/9 against a 5/9 bar for the whole window and Phase 1B never unlocked; SOL stayed dry at 2/8. Price never reached the zones those tranches were waiting on, so the restraint cost nothing. **Do not loosen a gate count, a [V] floor or a phase score line to cure under-deployment** — every non-deployment traced to a correctly dark board, an unreached zone, or an absent catalyst.
- **Stop discipline and the D6 one-way ratchet.** One clean ratchet ($3,900 → $3,800, predicted in advance), two transparent Migration-Ledger rolls, zero discretionary loosening — including while gold sat at its deepest drawdown directly under a live checkpoint.
- **The Deep-Value Override's arming bar.** Never armed, correctly (score never reached 15). N=1 on outcome still stands.
- **The 16 validated 2026-07-04 FK mechanisms**, especially the data-integrity family: metric-history continuity, provenance citation, provider pinning, sourced-or-computed momentum, canonical-spot reconciliation, the Stop Migration Ledger, the calendar lock, and the EV sum-check. These are the reason this corpus can be graded at all.
- **The framework's own self-correction.** From 08-01 the reports disowned the BTC phantom fill and declined new capital pending a ledger refresh, for three consecutive reports. That is Hard Rule 8 working — **three weeks late, because no report consulted `position.mjs` while the tool existed the whole time.**
- **The refusals.** Repeated explicit declines to add on rates-driven non-flush dips, to loosen a score line to fatten margin, or to re-anchor Phase 2 upward by a back door.

**N=1 caveat — and it is heavier than usual this run.** Every finding here rests on **one 33-day window in which all four assets were in a simultaneous bounce off a swing low, with zero fills and zero stop tests.** The window contained no capitulation, no phase unlock beyond 1A, no report scoring ≥15 mechanically, and not one day of F&G ≤20. Both new persistence constants (≥4 and ≥5 distinct report dates) are fitted to that single window. **Zero realized cost over a window with zero fills measures absence of exposure, not absence of defect** — the 2026-08-05 "dispositions corrected" language applies verbatim to this set.

Three specific things must be re-graded next cycle before any of this is treated as settled: **(1)** all five applied tunes, once a report has actually run under them; **(2)** the entire FR-side tune set, on a corpus that includes FR reports — it has now gone two nominal cycles without a real out-of-sample grade, and this run's `not_exercised` verdicts are an artefact, not a result; **(3)** Hard Rule 5's both-≥12 branch, which has never been exercised on real data and should be read as unvalidated until an asset exits the phase-of-cycle cap with FK still elevated.

And the sentence that should survive the rest of this memo, corrected 2026-08-07: **the largest defect this run found — nine BTC reports and seven ETH reports mark-to-marketing a position the ledger cannot see — had already self-corrected by 08-03/08-05, predates the ledger, and is owner-attested never to have been real. What is still open, and still costing deployment, is the ledger's own incomplete fill history (`basis.reliable = false`) — see §0.**
