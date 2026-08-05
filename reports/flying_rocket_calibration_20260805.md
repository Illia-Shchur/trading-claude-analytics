# Flying Rocket Calibration — Non-Crypto Asset Support

### Run: 2026-08-05 · Target: `flying-rocket-analytics` · Corpus: the framework's entire non-crypto track record (4 reports)

---

## 0. Verdict in one paragraph

The motivating premise was wrong, and finding that out was the run's most valuable output. The SPX report's claim that the Valuation leg over-credits an equity index by ~4 points rests on forward P/E; the measure that actually mirrors MVRV-Z is CAPE, and on CAPE the leg was correctly scored. What *is* broken is not the valuation number but the **absence of a pinned schema around it**: four non-crypto reports produced four gate denominators, two rounding conventions, and two mutually contradictory valuation readings on the same asset on the same day. Six tunes were adopted, all tightening or disclosure; one — the forward-P/E substitution — was rejected on three independent grounds; and the framework's **scope was deliberately not widened**. Both gold stand-downs graded CORRECT against realized price. Both SPX reports are untested.

---

## 1. Prior-calibration re-validation

The last market calibration was **2026-07-04**. Its FR-side tunes shipped with the explicit caveat "untested until FR runs again — treat every one as `not_exercised`." FR has since produced live reports, so a first out-of-sample read is possible on the ones this corpus exercises:

| Prior tune (2026-07-04) | Grade | Evidence |
|---|---|---|
| Fixed non-crypto gate schema + mandatory printed `ceil(fraction × active)` conversion | **harmful in effect — not in intent** | The rule required a fixed schema and specified no mechanism to fix it. Four reports, four denominators. Repaired this run by pinning the sets in code. |
| Zero-floor on carry income for the +3% filter and the 40% veto | **validated** | Did exactly its job on an asset class it was not written for: SPX's `r − q` = +2.67% annualized carry *income* was correctly barred from helping the short clear the filter. |
| Single-observation durability lock (Principle 13) | **validated** | Both SPX reports refused to score the single-week −$26.6B equity-ETF outflow as an inflection inside a record inflow regime. Correct, and it bound. |
| Cap-regime vacuity disclosure / EV-voice demotion | **not_exercised** | SPX was uncapped (0.28% off the high); gold was capped but predates the tune. |
| §7 Cover-Trigger Preflight veto | **validated** | Fired on SPX-1642 (upside narrative break, Iran/Hormuz), correctly refusing to open a short into the event that would force a 100% cover. |
| Verdict-Confidence Collar (symmetrized) | **validated** | On in both SPX reports via the \|EV\| < 3% limb; neither declared a top. |

No prior rejection was re-proposed, and no prior adopted tune is reversed.

---

## 2. Realized-path scorecard

**Ground truth fetched live 2026-08-05.**

| Report | Call | Realized | Grade |
|---|---|---|---|
| `gold_flying_rocket_20260616_0920` | STAND DOWN, 1/20; re-arm only within 10% of ATH (~$5,030) with weekly RSI >70 | $4,340 → **$4,225.40** (−2.64%); still 24.4% below the 1y high, weekly RSI 41.0 | **CORRECT** |
| `gold_flying_rocket_20260618_0020` | STAND DOWN, 1/20; Total Short EV ≈ −1.7%, fails the +3% filter | $4,258 → **$4,225.40** (−0.77% over ~7 weeks) | **CORRECT on the verdict, PARTIAL on the forecast** |
| `sp500_flying_rocket_20260804_1642` | STAND DOWN, adj 6/20, three independent vetoes | ~7,737, one session | **UNTESTED** |
| `sp500_flying_rocket_20260804_2234` | STAND DOWN, adj 6/20 | ~7,737, one session | **UNTESTED** |

**Forecast calibration, gold Jun-18:** EV_price $4,330 against a realized $4,225.40 — **2.48% too bullish**. The modal scenario (Bounce 42%, $4,400–4,600) never printed; the second-ranked Range band ($4,150–4,400) contained the outcome. The directional EV had the wrong *sign* (it expected price up; price fell) but the magnitude was immaterial: a short would have earned **+0.77% gross over seven weeks**, comfortably inside the +3% minimum-edge filter that produced the no-trade. **The framework was right for the right reason** — it declined a trade whose realized edge was below its own bar — while its point forecast was mediocre. That distinction is the one worth preserving.

The third of that report's conditionals also **fired**: gold traded into the named $3,950–4,150 zone (40-session low $3,962.50), and the report had pre-labelled that zone "a Fallen Knives accumulation zone, not a short entry." Correct.

---

## 3. Verified defects in the corpus

Re-computed independently, not taken from the reports.

1. **Denominator drift.** gold-0616 used a custom 7-gate list; gold-0618 used "~6" (approximate denominators are explicitly prohibited by §4); SPX-1642 used 7 (N/A 4, 6); SPX-2234 used 6 (N/A 4, 6, 9). The denominator scales every phase floor through the ceil conversion — a per-report knob on a protective threshold.
2. **Arithmetic error, SPX-1642 gate 7.** "$77.6B over H1 ≈ $600M/day" ✅. On calendar days: **$428.7M/day**, which *fails* the >$500M bar; $600M implies ~129 trading days. Correct reading: **2/7 gates, Distribution leg 0, mechanical 7**. On the mcap-pro-rated bar (~$15–25B/day) it fails by 35–58× either way. Verdict unaffected. The companion report reached the correct figure independently.
3. **Contradictory valuation facts.** 1642: forward P/E 19.6 → "band 1." 2234: forward P/E 21.5, CAPE 40.5 → "the leg survives." Live check: **forward P/E 19.6 is right, 21.5 is wrong; CAPE is 41.9.** Both readings are simultaneously true and point in opposite directions.
4. **Rounding drift.** `ROUNDING` pinned only btc/gold/eth; 1642 declared half-down "as a disclosure, not a precedent," 2234 used half-up. Worth a full point on a 6.5 raw.
5. **Correlation degeneracy.** corr(SPX, SPX) = 1.00 by construction; the suppressor fires tautologically. Disclosed by both reports; only ever makes the short harder.
6. **Three unenforced code paths**, all confirmed by reading the source: `gates.na` *content* was never validated (only `active === 9 − na.length`); `S.rounding` silently overrode `ROUNDING[asset]`, making any pin advisory; and `compute.mjs thresholds` is the **Fallen Knives** converter (p3 = 7/9) whose output an FR report is required to publish — understating FR's deepest floor by one at active 8 and 9. The FR linter always read `FR_GATE_FLOORS` directly and was never wrong.

---

## 4. The adjudicated tuning set

Two adversarial skeptic panels, strictest-wins merge. Both panels independently reached the same verdict on the two decisions that mattered (reject forward P/E; do not widen scope).

### Adopted — 6

| # | Tune | Effect |
|---|---|---|
| **T1** | Valuation: ATH-distance fallback **capped at 3** for non-crypto classes unless a pinned long-run measure (equity → Shiller CAPE; metals → CPI-deflated real price) is **≥90th percentile of ≥50 years**, sourced and dated. Unsourced → cap applies. | **Zero change on 2026-08-04** (CAPE 41.9 clears the bar, so the 5 stands). **−2 on the same record high in a median-CAPE regime.** Can never score above the fallback in any state. |
| **T2** | Gate schema **frozen per class**: N/A {4, 6, 9} → denominator 6, equity indices and metals alike. Substitute-independence resolves to N/A; measurability resolves to ⚠️-and-active. | Kills the drift. Linter-enforced. |
| **T3** | Degenerate correlation suppressor declared **structurally ON** within 3% of ATH; machine block records `corr: null` + `corr_degenerate: true`. May never act as an off-switch. | Mechanically a no-op; forecloses a future "the correlation is an artifact, so no surcharge" argument, and stops a tautology entering a committed data contract. |
| **T4** | `ROUNDING` pinned for `spx`/`sp500`/`ndx`/`nasdaq` → **half-down**; a report may no longer override a pinned convention. | Worth one full point at a .5 raw. Conservative on both frameworks. |
| **T5** | Untransferable crypto-native thresholds → **NOT MET** (❌-and-active, never N/A). Per-day rates on **calendar** days. | The only tune that changes a corpus outcome: SPX-1642 gate 7 ✅→❌. |
| **T6** | **Restriction-only annex**: Channel B unavailable, Phase 3 unreachable (ceiling 18 < 19), squeeze-trap printed as MISSING PROTECTION not `penalty: 0`, explicit user acknowledgement required, mandatory Hard-Rule-6-inert disclosure line. | All tightening. Scope **not** widened. |

### Rejected — 1, and it is the one that motivated the run

**T1-alt — forward 12-month P/E as the equity valuation substitute. REJECTED**, unanimously, on three independent grounds:

1. **It is the wrong analogue.** MVRV-Z measures price against a smoothed cost basis. Forward P/E measures price against analyst estimates that track price — it is reflexive and mean-reverts by construction, and its 5-year comparison average *contains the bubble being detected*. At the October-2007 top forward P/E was ~15 against a ~16 average: it would have scored the leg **1 at a cyclical top**, where CAPE (~27) lands near its 90th percentile.
2. **Reachability collapse.** Distribution sub-leg (a) and Vulnerability sub-leg (a) are structurally absent for an index, capping both legs at 2. Under T1-alt the maximum attainable mechanical score is 5 + 4 + **1** + 2 + 2 = **12**, against unlock lines of 11 / 13 / 15. A rubric that clears 1A only when four legs simultaneously max out and **can never reach Phase 1B** has no resolution in the 6–11 band where every real decision lives. Under T1 the ceiling is 18 and 1A/1B/2 stay live.
3. **Data integrity.** It is the single input this corpus has already gotten factually wrong (21.5 vs the verified 19.6), while the CAPE print (40.5 vs 41.9) was approximately right.

**Why the rejection matters:** the premise that the Valuation leg over-credited SPX by ~4 points on 2026-08-04 is **not supported**. CAPE at 41.9 corroborates the extreme. The leg's real defect is that it would pay the same 5/5 at a *median* CAPE — a mechanism problem, which T1 fixes without touching the 2026-08-04 score. Selecting a metric for the answer it produces today is curve-fitting, and the calibration declined to do it even though the result would have been a tightening.

### Refused at the design level — coverage itself

The counter-case ("non-crypto should not be covered at all") **largely won**, and both panels arrived there independently. For an equity index, **four of Hard Rule 6's seven never-relax items are inert or unenforceable by construction**: the funding veto gate (no instrument), the carry veto (carry is income; the zero-floor caps its contribution at zero), the size cap (no ledger counterpart → `UNTAGGED` → cannot resolve a phase-dependent precondition), and the cover-trigger set (two of six preflight triggers dead, plus an FK≥12 force-cover computed from a mostly-absent companion rubric). Formalizing coverage would grant the appearance of protection where the protection does not run.

But "declare out of scope and refuse" does not survive contact either, because refusing to write the rules down is what produced four denominators and two contradictory valuation readings. **Resolution: formalize the restrictions, refuse the coverage.** The `description`, scope line and Asset Generalization table are unchanged; the annex is restriction-only; and CLAUDE.md's FR scope line is **surfaced as an owner decision, never edited** — Hard Rules are outside this skill's edit surface.

---

## 5. Surfaced, not fixed

- **The ceil conversion has flat spots.** At Phase 1B the floor is 4 at both active 6 and active 7, so activating a scoreable gate enlarges the pool without raising the floor. This is general — active 8→9 is flat at *all four* phases — so it is a live property of the crypto path too, not a non-crypto artifact. Fixing it requires new floor arithmetic that a 4-report corpus cannot justify. **Next calibration.**
- **No substitute is pinned for the squeeze-trap penalty** on an asset class with no funding rate. Short interest, hard-to-borrow status and skew inversion all exist; none is calibrated. The gap runs in the short-easier direction and is now at least *printed* as a missing protection rather than as a zero.
- **§6.5 cross-asset screening** has no non-crypto form; both SPX reports improvised sensibly and said so. Not worth a rule yet.

---

## 6. What to preserve

- **The refusals.** All four non-crypto reports stood down; both gradeable ones are correct against realized price. The framework's willingness to produce a no-trade verdict on an out-of-scope asset is the behaviour that kept it safe here.
- **The §7 preflight VOID** on SPX-1642 — refusing to open into a live upside narrative catalyst 24 hours old — and the fact that it was decisive independently of the score.
- **Inline substitution flagging.** Every non-crypto report named every substitution at the point of use. That discipline is why this calibration had anything to grade.
- **The zero-floor on carry income**, which did precisely its job on an asset class it was never written for.
- **Disagreeing reports.** The two SPX reports contradicting each other on the same day is what made the drift visible. The fix is a pinned schema, not a suppressed second opinion.

---

## 7. Coverage disclosure

4 of 4 reports extracted and graded; 0 dropped. 7 tunes proposed, 6 adopted (5 with modification from the skeptic panels), 1 rejected, 0 withheld at apply-time, **0 unadjudicated**. Both panels returned complete per-tune verdicts. Two panel findings were themselves rejected on verification: the claim that pinning denominator 7 loosens Phase 1B is measured against an ad hoc report choice rather than the framework's legacy /9 baseline (recorded instead as the general flat-spot finding above), and a claimed arithmetic impossibility in a cited CAPE long-run average was not a valid bound — the underlying concern (an unpinned percentile window) was adopted as the source/window/as-of requirement in T1.

**N=1, and weaker than usual.** Four reports, **zero trades, zero realized P&L** on this asset class — and this framework remains N=0 on realized money on its *native* class. Two of the four reports are one day old. Nothing here has been tested by a non-crypto report that reached a non-stand-down score, because there has never been one. Re-grade at the first.
