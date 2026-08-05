# Fallen Knives — Calibration, 2026-08-05

**Scope:** the three structural blind spots raised in §9.2 of `gold_fallen_knives_20260805_1008.md` that would require a **scored leg or gate** to change. Items 4 and 5 of that list were out of scope — item 4 (proximity to a routing change) was closed in the toolchain as unscored context (`proximityPanel()`), item 5 (PAXG issuer/custody risk) is already covered by Hard Rule 8's alias disclosure.

**Corpus:** 49 FK reports, 2026-07-04 → 2026-08-05 — BTC 18, ETH 18, GOLD 12, SOL 1. The window since the 2026-07-04 calibration.

**Result: 1 adopted-with-modification / 5 rejected / 0 withheld / 0 unadjudicated.**

---

## 1. The headline

All three assigned blind spots are **mechanically real**. None of them cost anything on the realized path. Five of the six candidate tunes derived to address them were rejected on mechanism, and the one clause that shipped came from an entirely different surface than any of the three items pointed at.

The most useful sentence in this memo is not about any of the three items. It is this: **three independent auditors, each told to be skeptical, unanimously returned "change nothing" — and all three were wrong about *why*.** The null verdict on the tunes was correct. The reasoning that produced it did not survive an adversarial pass, and the pass that attacked it found a genuine unguarded loosening path that all three had missed because all three searched only the leg-and-gate surface.

---

## 2. What was tested, and what it cost

| §9.2 item | Mechanically true? | Realized cost | Disposition |
|---|---|---|---|
| **1** — no leg/gate scores volume, range resolution or breakout character; gate 7 is a downside flush only; gold's valuation band saturated at 0 across −27.52% → −23.73% | **Yes** | **$0.** Gold's board was 2 of 8 in **all 12** reports against a Phase 1A floor of 3; zero fills in the window | **Insufficient evidence — NOT closed** |
| **2** — gate 9 is one boolean and cannot carry a sign; no leg scores macro | Literally yes; **operatively no** | **$0.** Gate 9 never lit for either asset across 7 paired dates | **Closed on mechanism** |
| **3** — weekly RSI at the 8.67th percentile vs daily RSI 60.68 | **Yes** | **$0.** Across 19 reports and 6 divergent prints, the maximum defensible −1 penalty alters **zero** deployment decisions | **Insufficient evidence — NOT closed** |

### Why "zero cost" is a weak result, and is recorded as such

Zero realized cost over a one-month window in which **not a single tranche filled**, on an asset whose 1A and 1B are already deployed and whose next tranche needs a −27% move, measures *absence of exposure*, not *absence of defect*. Items 1 and 3 are therefore recorded as **insufficient evidence with named re-test triggers**, not as closed:

- **Item 1** — re-test at the first report in any series where the gate count meets its phase floor with mechanical score 8–10 **and** spot above the named entry zone.
- **Item 3** — re-test when gold accumulates ≥10 days at a daily-minus-weekly spread ≥15, or after the next full cycle containing a fill.

The *tune* rejections below are earned on mechanism and should **not** be revisited on more data alone.

### Item 2 is closed, but it leaves a live contradiction

The complaint that FK cannot resolve one macro event to opposite signs across assets is false twice over. Gate 9's "neutral-to-positive" predicate is **asset-relative** and was demonstrably resolved differently for gold (❌, real yields at a cycle high) and BTC (⚠️, contested Hormuz closure) on **2026-07-13**. And D1 is a purpose-built signed, capped, logged macro channel — the very report raising the claim used it to book −1.0 on exactly this transmission, the same day it declared the sign inexpressible.

What *is* a real defect, and is referred to the next pass: **the corpus contradicts itself on whether macro is already counted.** `gold_..._20260801` **declined** a −0.5 D1 as double-counting — *"the Fed stance already keys gate 9"* — while `gold_..._20260805` **took** −1.0 with macro as factor (ii). Both cannot be right. D1(a) prohibits re-weighting a factor **a leg** already scores; gate 9 is not a leg. So 08-05 is textually correct and 08-01 was over-conservative in a way the letter does not require.

---

## 3. The gate-reachability finding — it inverts the framing

All three auditors leaned on "the gates blocked it anyway." That defence is **contingent** at Phase 1A and **circular** at Phases 2/3, and testing it produced the most important structural finding of this pass.

Gold's board, by reachability:

| Gate | Reachable? | What it takes |
|---|---|---|
| 1 [V] COT washout | Yes, weekly | Latest −1.00% WoW vs a ≥15% bar — an order of magnitude short |
| 2 [V] Weekly RSI <30 | Yes, slow | 38.98 now |
| 3 [V] ≥50% drawdown | **Effectively no** | Requires **−34.4%** from spot |
| 4 [V] Physical ETF outflows | Lit | Most fragile input on the board |
| 5 [T] Hash Ribbon | N/A | Denominator 8 |
| 6 [T] ±8% of 200-week MA | **Effectively no** | Requires **−27.6%** from spot — gold fails from *expensiveness* |
| 7 [V] Capitulation flush | Yes | Disorderly break of $3,965 on outsized volume |
| 8 [V] LTH / concentration | Lit (stale-carried) | — |
| 9 [T] Macro neutral-positive | Yes | Dovish inflection |

Ceiling without a >27.6% gold bear: **6 of 8.** Phase 1A (floor 3) was **one gate away** — the board is not unreachable, so the "gates blocked it" defence is contingent, not structural.

But for the deep tranches the binding constraint is the **score**, not the gate count, because the **sentiment leg is permanently pinned at 2** (NOT FOUND fallback — now a *measured* conclusion, see §5). Gold's realistic composite ceilings: **13** at −30% drawdown, **15** at −50%. Phase 2 needs ≥15; Phase 3 needs ≥17.

**This is the better reason all three score-lowering tunes fail.** Each proposed to *subtract* score from a board whose score is already structurally capped near 13 in any non-catastrophic regime.

---

## 4. Adopted — Entry-zone ratchet (§6, entry-side, restrictive)

**The gap:** §6 said only *"Ladder across the FULL zone — never deploy at the top of it."* A `grep` of the entire SKILL confirms there was **no** clause forbidding deployment *above* a zone and **no** rule governing which direction a zone may be moved. The D6 ratchet is scoped to *stop* parameters exclusively. So the framework's entire structural answer to a melt-up — *it does not chase* — lived only in practice.

**That practice was real, and was operated on two incompatible principles in a single batch.** ETH ran a full upward re-anchor correctly (Jul-2/Jul-4 pre-committed a held-shelf rule, Jul-9 fired it at 7-of-5 sessions with a void condition, Jul-10 **refused** a second re-anchor on a +2.6% pop at a four-times-rejected wall — *"the framework pays for the hold, never the poke"*). Gold on Aug-5 declined an upward re-anchor on entirely different reasoning: that it *"would make the coherence check easier."* That is a back-door coupling of an **entry** decision to a **stop** test — and had the check happened to point the other way, gold had no stated reason to decline at all.

**What shipped** authorizes a tranche inside its named zone only; states that a re-anchor is **not** an unlock; permits downward re-anchoring but routes it through the coherence check and D6 exception 1; and constrains upward re-anchoring behind ≥5 consecutive closes above the shelf (a **floor**, never a ceiling on caution), a zone top below spot at naming, a stated void condition, and a prohibition on ever raising the number the coherence check runs against.

### The defect the skeptic panel caught

**The clause as originally proposed would have blocked the Deep-Value Override — an automatic reject.** The Override's price condition is anchored to the **most-recently-deployed tranche's blended cost**, not to the next tranche's zone, so an 8%-below-basis firing with a fresh lower-low routinely prints *between* zones. Walking its own founding case: 1A filled ~$65K, 1B's zone was $53–58K, the Jun-6 low printed $59,110 (−9.1%) at score 16 / F&G 9. The Override fires — and *"no tranche fills above its zone top"* would have made it unexecutable. That is verbatim the failure the Jun-2026 calibration removed, where the Override *"shipped decorative despite score 16 / F&G 9."*

The shipped text exempts the Override explicitly and requires it to name its own entry band at or below the trigger print. Three further modifications were forced: "downward re-anchoring is **free**" was false (it runs the coherence check and D6 exception 1); clause (d) was demoted from a new rule to a cross-reference, because restating it as a new *stop parameter* would have pulled it under D6 and created the very coupling the analysis had just cleared; and a "not-an-unlock" statement plus an FK-only marker were added.

**Decoupling verdict: preserves.** The entry zone was *already* the coherence check's input by design (`"use the single LOWEST floor"`, Jun 2026). The check is a publish-time assertion, not a trigger. Clause (d) *severs* a propagation path — under the status quo an upward re-anchor would raise `deepest_zone_floor` and thereby **loosen** the stop test.

**Honest weaknesses, logged:** reachability on the realized path is **zero** — it changes no decision (ETH already ran it, BTC already refused, gold already declined). It is not declarative in *reasoning*, which is the point. It is **unenforceable by the linter** — `lint-report.mjs` accepts `stops.deepest_zone_floor` as a free-form number with no direction check, so this ships as prose discipline; a linter direction check is the natural next tune. The **≥5 constant is N=1**, imported from one ETH episode, not fitted — though it refused correctly on all three assets in-sample (gold's single-session +4.03% breakout, ETH's +2.6% pop, BTC's categorical no-chase line) with zero false blocks.

**Family-resemblance disclosure.** The 2026-07-04 pass rejected a *"zone-freeze / zone-placement / band-anchor-lock family"* for freezing a ladder across a 200-week break, forcing the stop **up**, and blocking the Jul-2 structure-conditional re-stage. This clause negates all three. **Not a reversal** — but the resemblance is named, because Jul-04 withheld a tune at apply-time on family resemblance alone.

---

## 5. Rejected — and why the rejections are the point

1. **Breakout/volume leg or gate (item 1).** Gate-level it must be an *anti*-gate — subtracting from a count, for which the counting rule has no mechanism. Leg-level it keys volume, which capitulation-(a) and gate 7 already consume: the **double-key** prohibition.
2. **Negative tier on the drawdown fallback.** Gold's deepest print was −28.20% against a first scoring edge at −30%. Fires nowhere, ~9pp away, for a paired `lib.mjs`/`selftest.mjs` change and zero benefit.
3. **Macro leg (item 2).** Raises the composite maximum **20 → 22**, silently loosening *every* absolute threshold at once — 1A ≥8, 1B ≥11, 2 ≥15, 3 ≥17, the Override's ≥15 arming bar, the compound-stop <12 line, the §7 trim rows, the Verdict-Confidence Collar. It would additionally arm the Override one point cheaper at maximum fear.
4. **Signed/graded gate 9.** Any variant letting a positive macro read *add* gate credit raises the gate count and loosens unlocks on the long side, uncapped. On the realized path it lights BTC's 5th gate on 07-04 into a −23%-context tape.
5. **Weekly-vs-daily RSI divergence — both constructions (item 3).** The **spread** version is anti-correlated with weekly RSI *by construction*: at weekly RSI 25, a daily of merely 40 already trips a 15-point spread, so it bites hardest at maximum washout and would knock the score below the Override's mechanical ≥15 arming bar at the lows — verbatim this framework's documented past failure. The **absolute** version is safe but fires on 2 of 19 reports, both already gate-blocked. Out-of-sample over 2y × 4 assets the forward-return gradient is **non-monotone with three sign changes** (<8 → −2.55%; 8–15 → +2.38%; 15–20 → +3.66%; ≥20 → −0.18% at 15d), the widest tail *underperforms* the middle, and per-asset signs disagree (ETH +7.40%, SOL −1.89%). **Gold has exactly one qualifying day in two years — 2026-08-05 itself.** The claim was raised on the one asset with no base rate for it.

Note the direction error in item 3's own framing: the claim called the tension *"bullish."* A hot daily on a washed weekly means **the flush you were waiting for has already been bought** — mildly *bearish* for the next tranche. The Aug-5 gold report reached that conclusion unaided, booking a **negative** D1 of −1.0 with a dated falsifier and an Aug-19 review. And the information is not lost to the system: daily-hot-on-weekly-washed is the Flying Rocket Channel B setup by construction, so the companion framework already prices it.

---

## 6. Toolchain changes that landed alongside (no rubric effect)

- **Gold sentiment instrument — the fallback is now a measured conclusion.** GVZ and the PHYS closed-end premium were backtested over 10y and **both rejected** as scored inputs (GVZ: no gradient, and a volatility index is direction-blind; PHYS: split-half inverts, tail returns 3.67% vs a 3.59% unconditional baseline, effective N ≈ 16). Both now emit every run as unscored context under `context.sentiment_proxy`. The leg still scores 2 — but because the candidates were *measured and carry no signal*, not because none could be found.
- **Verified ATH denominator.** `ath.all_time_verified` — gold's pre-window max was $1,911.60 @ 2011-09 vs a $5,586.20 in-window high, discharging the "10y window high, not a verified ATH" stale-input-debt line every gold report has carried.
- **`proximityPanel()`** — §9.2 item 4, closed as unscored context. Distance-to-boundary, the complement of the tripwire's crossing detection.
- **Weekly-bar artifact fix** — `completedCandles()` now drops *every* trailing incomplete bar. Yahoo's extra live-session stub had left the in-progress week inside the "completed" set, moving gold's weekly RSI to 41.03 (band 1) instead of 38.98 (band 2) and the mechanical score 8 → 7 — which would have satisfied a compound stop's score axis on a bar-count artifact rather than on evidence.

---

## 7. Process disclosure

The packaged multi-agent `Workflow` was **unavailable in this session**. The same adversarial structure was run with individual agents: three independent per-item diagnosers → one adversarial reviewer tasked specifically with attacking the unanimous null → one solo skeptic panel + pre-apply audit on the single surviving tune (capital-deployment tunes get solo panels, never batched). The full Extract/Grade phases over all 49 reports were **not** run as separate agent passes; each diagnoser extracted and graded its own item's evidence directly.

**The adversarial step is what produced the entire adopted result.** All three diagnosers returned zero tunes. Had the pipeline stopped at consensus, this calibration would have shipped nothing and recorded a false "no defect found."

---

## What to preserve

- The **refusals**. Gold declined to chase a 99.4th-percentile volume day; BTC refused to chase above $62K on a squeeze day; ETH refused a second re-anchor on a poke. That restraint is the edge, and the adopted clause exists to make it a *test* rather than an accident.
- **The mechanical/adjusted split**, which proved itself under live load: gold's D1 of −1.0 took adjusted to 7, below the `<8` compound-stop line, but the stop reads the **mechanical** score (8) and correctly did not degrade to price-only.
- **The D1 channel as the pressure valve** for what the rubric cannot see. All three blind spots were expressible through it, and two of them were actually expressed through it before this calibration ran.
- **Every rejection above.** Five of six candidates died on mechanism, and four of the five would have loosened the long side while looking like precision.

**N=1.** One adopted clause with zero in-sample decision impact, an N=1 constant, and no linter enforcement. Re-validate after the next full cycle — and specifically after the next report that actually fills a tranche.
