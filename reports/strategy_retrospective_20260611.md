# Fallen Knives / Flying Rocket — Strategy Retrospective & Correction

**Backtest window:** 14 May – 10 June 2026 · **Generated:** 11 June 2026 (EDT)
**Assets:** BTC, ETH, Gold (XAU/USD), SOL · **Reports analyzed:** 19

**Method:** 66-agent adversarial backtest workflow — 19 reports extracted to structured predictions, per-asset prediction grading against the realized path, 7-dimension framework diagnosis, adversarial refutation of 33 candidate tunes (→ **11 adopted-with-modification, 17 rejected**), plus a forensic audit of the parameter edits applied to the live skill files.

**Status:** All adopted tunes have been applied to `.claude/skills/fallen-knives-analytics/SKILL.md` and `.claude/skills/flying-rocket-analytics/SKILL.md`. See each file's **Framework Revision Log** (2026-06-10 first pass, 2026-06-11 adversarial calibration pass).

**One-line conclusion:** *The frameworks' edge is risk discipline, not price forecasting. They preserved capital admirably through a −23% BTC / −27% SOL leg while forecasting direction poorly. The correction makes the forecasting honesty structural — in the EV machine, the probability grid, and the verdict's voice — without ever relaxing the gates that did the surviving.*

---

# Fallen Knives / Flying Rocket — Authoritative Retrospective & Strategy-Correction Memo
### Coverage window: 14 May – 10 June 2026 · Assets: BTC, ETH, Gold (XAU/USD), SOL
### Author: Lead Allocator · Status: Final

---

## 1. Executive Verdict

This was a four-week cycle in which the frameworks **preserved capital admirably and forecast direction poorly** — and survived the gap between the two on the strength of their gates and their dry-powder discipline, not their views.

**What we got right.**
- **Capital preservation through a –23% BTC / –21% ETH / –27% SOL / –11% Gold leg.** Total BTC book finished ~**–0.5% MTM** (10% deployed at –5.4%, 90% in ~4.5% T-bills). ETH deployed only a 10% toe; SOL deployed **nothing**; Gold deployed 25%→35% but the front-loaded 10/15/30/45 pyramid held its drawdown to **–9.1% on a –11% move** while keeping 65% dry.
- **The "gates win over score" override.** The single best judgment call of the cycle. A naive score-driven system would have deployed Phase 1B (and Phase 2 on Jun 10, where score 16 formally qualified) into a falling knife with a broken 200-week and re-accelerating CPI (4.2%). The gate veto kept 90% of the BTC book dry.
- **Stop discipline ("honor the close, not the wick").** BTC's $58K daily-close stop survived the Jun 6 low of **$59,110** (+1.9% margin) without firing near the exact bottom. ETH's $1,560 weekly-close stop survived its $1,599 test (+2.5%). SOL's $60 close-stop survived a $60.20 low ($0.20 margin). Three near-misses, zero whipsaw stop-outs.
- **Self-correction speed.** The May 28 report flipped the May 14 flow thesis within 14 days and was vindicated completely (flows kept bleeding, $80K stayed overhead, recovery kept failing).
- **Cross-framework non-conflict** (the *letter* of Hard Rule 5): FK rose 8→16 while FR stayed an estimated 3–6; never both ≥12.

**What we got wrong.**
- **Directional optimism, systematically.** Forward EV-vs-spot pointed **up on ~5 of 7 testable BTC reads** (and 4 of 5 on ETH) while price fell every time. The one negative EV read of each series (BTC May 28 –3.8%, Gold May 31 –0.5%) was the one that nailed the subsequent collapse — proof the model *can* read weakness but defaults to optimism once "value" (oversold RSI, low MVRV-Z) appears, mistaking cheapness for imminent reversal.
- **The probability matrix leaned bullish into a tape making lows.** A persistent **35–40% Rally weighting** that never paid; Rally remained the *modal* BTC scenario on Jun 6 and Jun 10 — at the cycle's deepest fear, with the 200-week freshly broken.
- **The flagship thesis error — "the fear window has already closed for this leg" (May 14).** Declared the cycle over and reframed BTC as a momentum trade at $79.5K, with EV +0.6%, scorecard 7/7, score 8 — immediately before the deepest fear print of the cycle (F&G 9) and a –23% decline.
- **Under-calibrated speed and tail probability.** Every retest band was *reached* but the moves the IF/THEN conditionals dated at "4–6 weeks" happened in ~1 week, and the realized path repeatedly delivered the 5–15%-probability Bear tail.
- **Cross-validation was satisfied by an eyeballed placeholder, not a measurement.** Exactly one Flying Rocket report exists (May 14, at the moment of *least* fear); all subsequent FK reports asserted inverse consistency with sourceless "~3–4" estimates. The check became unfalsifiable precisely when extreme fear made it most informative.

**Headline correction.** The framework's edge is **risk discipline, not price forecasting.** Every tuning decision below is therefore filtered through one rule: *fix the forecasting honesty without weakening the discipline that actually preserved capital.* The two highest-value structural fixes — already partially applied on Jun 10 — are (i) decoupling phase unlocks from the pro-cyclical [T] gates so deep fear can deploy, and (ii) a Deep-Value Override to defeat the pyramid inversion. Both are correct in direction but, as the adversarial review and the applied-edits audit show, **both are mis-calibrated as shipped** (the override is unreachable, the gate fix doesn't unlock the next tranche, the trend modifier is too weak to dethrone a 50% baseline). The work below is the calibration pass.

---

## 2. Realized-Path Scorecard

### BTC — score 8→16, $79.5K→$61.5K, F&G 50→9

| Prediction (date) | Predicted | Actual | Verdict |
|---|---|---|---|
| Prob matrix (May 14) | Modal Range $75–85K; Bear $60–70K only 15% | Range at 2wk, then straight to Bear band ($61.5K) | partial |
| Prob matrix (May 28) | Co-modal Range/Retest; Bear 15% | Traversed Retest cleanly, into Bear band | partial |
| Prob matrix (May 31) | Modal Range $70–78K; Bear 10% | $66K in 3 days, $60K in 6 → Bear band | **wrong** |
| Prob matrix (Jun 3) | Co-modal Rally/Range $62–85K; Retest 22% | Fell into Retest band; Rally never close | partial |
| Prob matrix (Jun 4–6) | Rally modal 38–40% $68–82K | Opposite; never reclaimed $68K | **wrong** |
| Pattern (May 14): retest→Phase 1A in 2–4wk | $70–75K, deploy 10% | $74K at 17 days, 1A deployed | **correct** |
| Pattern (May 14): $60–68K, 1A+1B fire | 25% deployed | Hit faster; only 1A fired | partial |
| Pattern (Jun 4): dovish FOMC flips gate 9 | Phase 1B unlocks | Macro hardened (CPI 4.2%); never fired | **wrong** |
| Pattern (Jun 6/10): close <$58K stops out | Stop fires | Low $59,110, no close below; intact | **correct** |
| Falsifiable (May 14): "fear window closed" | Cycle over at $66K | F&G→9, –23% further | **wrong** |
| Falsifiable (May 14): $80K = support | Durable support | Broke in 14 days | **wrong** |
| Falsifiable (May 14): ETF flow inflected + | Institutional floor | Record 11–13 session outflow | **wrong** |
| Falsifiable (May 28): flows reversed, recovery failing | Bearish confirmed | All three confirmed | **correct** |
| Falsifiable (Jun 6): 200-week structural break | Confirmed break | Held below through anchor | **correct** |
| Falsifiable (Jun 6/10): "gates win over score" | Withhold past 1A | Vindicated; lower bands never reached | **correct** |
| Falsifiable (Jun 5): Saylor sale = dent not break | No exit trigger | Net buyer +24,869 BTC; noise | **correct** |
| Deployment: 10% at ~$65K, $58K stop, 90% dry | — | –5.4% MTM; stop intact; 90% dry | partial |
| Cross-validation: FR inverse throughout | Held | Held all 8 reports | **correct** |

**BTC tally (testable):** correct 8 · partial 5 · wrong 6. **Direction of conditionals: strong. Magnitude/speed: weak. Tail probability: badly under-weighted.**

### ETH — score 10→14, $2,078→$1,634, F&G 25→9

| Prediction (date) | Predicted | Actual | Verdict |
|---|---|---|---|
| Prob matrix (May 28) | Modal Range $1,900–2,300 | Sliced through to $1,634 | **wrong** |
| Prob matrix (Jun 4) | Modal Range $1,650–2,000; Rally 30% | Endpoint barely in band; Rally clean miss | partial |
| Prob matrix (Jun 5) | Modal Range $1,650–1,950 | Finished at Range/Retest seam | partial |
| Prob matrix (Jun 6) | Modal Range $1,500–1,800, hold $1,500 | Dead-center; $1,500 held | **correct** |
| Falsifiable (Jun 4): +3.5% EV, "most pain taken" | Cheap, edge up | Fell another 8.5% to $1,599 | **wrong** |
| Falsifiable (Jun 6): macro inverted hawkish | Dovish path dead | CPI 4.2%, gate 9 bolted shut | **correct** |
| Falsifiable (recurring): ETH lags BTC on drawdown | Underperforms | –67% vs BTC –51% | **correct** |
| Deployment: only Phase 1A toe; 90%+ dry | 1B stays locked | Gates pinned 3/9; correct | **correct** |
| Stop: weekly close <$1,560 | — | $1,599 low, no weekly close below | **correct** |

**ETH tally:** correct 5 · partial 2 · wrong 2 (+5 conditionals correctly non-fired). **Modal matrix wrong in 3 of first 4 reports until band dropped to $1,500–1,800.** B-minus series: excellent macro + discipline, mediocre EV.

### Gold (XAU/USD) — score 12→12 (soft→hard), $4,650→$4,130

| Prediction (date) | Predicted | Actual | Verdict |
|---|---|---|---|
| Prob matrix (May 15): 40% modal rally $5,000–5,200 | Up | Fell –11% | **wrong** |
| Prob matrix (May 15): $4,000–4,200 only 20% | Tail | Exactly what happened (26 days) | **correct** |
| Prob matrix (May 28): Range 35% modal; Retest 25% | Range | Bounced into range, then Retest realized | partial |
| Prob matrix (May 31): raise Range to 40% at $4,540 | Up-confidence | –9% crash within 10 days | **wrong** |
| Falsifiable (May 28): COT washout = bottom | Limited downside | COT rebuilt to 176K, trapped | **wrong** |
| Falsifiable (May 28/31): $4,423 defended | Holds | Broke decisively | **wrong** |
| Falsifiable (May 31): **EV below spot, don't add $4,540** | Patience | **–9% over next 10 days** | **correct** |
| Falsifiable (May 15): haven/war-premium support | Supports | Fell with equities | **wrong** |
| Deployment (May 15): 25% across $4,475–4,650 | — | –9.1% MTM; locked above $4,130 | partial |
| Stop: weekly close <$4,000 w/o score recovery | — | Low $4,119; ~3% above; intact | **correct** |
| Cross-validation: FR 3–7 inverse | Held | Held all 5 reports | **correct** |

**Gold tally:** correct 5 · partial 2 · wrong 5. **Destination right ($4,130 = the 20% tail), path and probabilities wrong; the EV gate redeemed the series.**

### SOL — single report (May 28), score 9, $83→$63.21 (low $60.20), F&G 33→9

| Prediction | Predicted | Actual | Verdict |
|---|---|---|---|
| Modal RANGE $80–100 (40%) | Holds | Broke in 6 days | **wrong** |
| RETEST $60–80 (30%) | On $80 break + hawkish | Exactly realized; low $60.20 | **correct** |
| BEAR $45–60 (15%) | Deep capitulation | Kissed $60.20, no close below | partial |
| RALLY $100–130 (15%) | — | Never near $100 | **wrong** |
| Beta: BTC tests zone → SOL sees $60s | $60s | $60.20 same day BTC bottomed | **correct** |
| "–72% is not a bottom; don't buy cheapness" | No buy | Fell to –79.6% | **correct** |
| EV $82.13 ≈ spot (–1.0%, "fairly priced") | Flat | –24% in 13 days | **wrong** |
| Deployment: **100% dry powder, do not deploy** | Hold | Avoided ~24% drawdown | **correct** |
| Entry zones $72–82 / $60–72 / $45–60 | Map | Traversed all three in order | **correct** |
| Stop: daily close <$60 | — | Low $60.20, never armed | untested |

**SOL tally:** correct 5 · partial 1 · wrong 3. **A for narrative + capital preservation; D for EV; incomplete on whether the >0.5%-mcap liquidation gate (actual flush ~0.27%) would ever have let us buy the bottom it pinpointed.**

---

## 3. Prediction Accuracy Analysis

**EV calibration: a systematic optimism bias.** Tallying the seven testable BTC EV-vs-spot reads: **~2 right (May 14 flat, May 28 –3.8%), ~5 wrong/over-optimistic.** Every positive-EV read from May 31 onward (+0.5%, +3.9%, +5.1%, +1.7%, +4.0%, +3.1%) pointed up while price went sideways-to-down. The Jun 4 read was billed "the widest accumulator's edge of the series" (+5.1%) immediately before the move to $59K. ETH: 4 of 5 positive through a –21% leg, with the two wrong reads (+3.5%, +4.0%) at *peak conviction* right before the $1,600 break. Gold and SOL show the identical signature.

The mechanism is structural, not stylistic. **EV = Σ(probability × midpoint of target range)**, and the score→grid mapping pushes Rally to a **50% baseline at scores 15–17**. With targets anchored above a falling spot and the heaviest weight on Rally, EV is *positive by construction* whenever fear is extreme. There is no term that discounts upside midpoints when price is below its MA making lower lows. The one time the matrix happened to lean bearish (Gold May 31, –0.5%), it produced "the single best call in the series."

**Probability-matrix hit rate.** Across all four assets, the **modal scenario was wrong or only partially right far more often than right.** It resolved cleanly only after the band was manually dragged down to meet reality (ETH on Jun 6, $1,500–1,800; Gold's terminal "downward tilt" on Jun 10). The IF/THEN *conditionals* were the framework's strength — every retest band was reached and the trigger conditions (hot CPI, ETF outflows, $80K break, $72K break, BTC→$60s beta) materialized as specified — but the paired *deployment promises* ("Phase 1B fills") consistently failed because the gates vetoed them, and the *speed* was badly under-called (4–6-week moves in ~1 week).

**The "fear window closed" miss (May 14).** This is the largest single thesis error of the cycle, and it is instructive precisely because the *stance* was correct (100% dry powder) while the *narrative* was catastrophically wrong. Three falsifiable claims were stated as settled fact and all falsified within 14 days: the fear window closed (F&G then fell to 9), $80K "converted to foundational support" (broke; one bounce elevated to structure), and ETF flow "inflected positive — the most important institutional barometer flipped supportive" (a single 30-day-MA cross, reversed into a record outflow streak). The error was an *epistemic* one — high-confidence forward assertions decoupled from a neutral quantitative layer (EV +0.6%, scorecard 7/7) — not a deployment error.

**Stop near-misses — the cycle's most important risk events.** All three crypto stops came within ~2% of firing at the exact local low and survived only because they were specified as *closes*, not wicks:
- BTC: $59,110 low vs $58,000 daily-close stop = **+1.9%**.
- ETH: $1,599 low vs $1,560 weekly-close stop = **+2.5%**.
- SOL: $60.20 low vs $60 daily-close stop = **+0.3%** (and never armed — Phase 1A never deployed).

The "honor the close" design is vindicated. But the near-misses exposed a latent design flaw, never stress-tested only by luck: **every crypto stop sat inside or above a lower planned buy zone.** A $58K BTC close would have stopped out the Phase 1A tranche at the precise price the same report instructs deploying Phase 1B ($53–58K). That contradiction is addressed in §4(c).

---

## 4. The Structural Flaws, Ranked

### (a) [CRITICAL] Inverted pyramid via score/gate divergence

The pyramid promises "bigger tranches at deeper drawdowns." It delivered the opposite. BTC deployed its **smallest tranche (10%) at the highest price (~$65K)**, then was mechanically locked out of the 15/30/45 tranches as price fell to $59K and the score rose to its cycle peak of **16 at F&G 9**.

*Evidence:* Jun 10 — "a 16 score against a 3/9 gate count… Phase 1B remains two gates away." The two missing gates were exactly the [T] gates that "structurally turn off in a fear cascade" — gate 6 (200-week broke) and gate 9 (CPI 4.2%). Phase 1B's hard floor (≥5 of 9) cannot be met when the gates that fail are the ones that fail *because* price is falling.

*The cross-asset proof:* the **lowest-conviction asset deployed the most.** Gold (peak score 12) deployed 35%; BTC (peak score 16, deepest F&G of the cycle) deployed 10%. Both had identical 3/9 gate breadth. The differentiator was not conviction — it was that Gold's Phase 2 carried a pre-committed **price-OR-score** unlock while BTC's required a hard gate count. The framework rewarded whichever asset happened to stay near its chosen MA and punished the asset showing the most fear/value. Worse, the inversion also produced the worse *outcome*: Gold caught the knife (–9.1% on 25%) while BTC's lockout perversely preserved capital.

### (b) [CRITICAL] Pro-cyclical gates

Gate 7 (capitulation: 24h liquidations >0.5% of mcap) **never fired for any asset in the entire sample** — BTC's largest day was ~$1.57B (0.13% of mcap), SOL's biggest flush ~0.27%, ETH peaked at exactly 0.20%. A capitulation gate that stays dark through two textbook capitulations (F&G 9, –79.6% SOL drawdown) is mis-calibrated — the identical "unreachable tier" defect the framework already removed from momentum (the <25 RSI → 5 tier). Because gate 7 is a [V] gate, its permanent dead state structurally suppresses the very [V] breadth the system needs.

Compounding it: Fallen Knives **lacks the N/A-denominator-reduction mechanism that Flying Rocket already has.** Gate 5 (Hash Ribbon) is PoW-only; for ETH/SOL it is N/A but the denominator stays /9, so PoS assets carry a permanently unfillable gate — biasing the count toward false-negatives, the exact bias the FR skill explicitly warns against.

### (c) [HIGH] Incoherent stops

Per-tranche price stops sat **above or inside the framework's own lower buy zones in every report from May 31 onward** — a persistent structural defect, not an edge case:
- May 31: stop $65K vs Phase 1B zone $64–70K (stop *inside* the zone)
- Jun 4/5: stop $58K vs Phase 1B $56–62K (inside)
- Jun 6/10: stop $58K vs Phase 1B **$53–58K** (stop at the *exact top* of the buy zone)

This is the stop-vs-DCA contradiction in a framework whose thesis is "buy deeper, in bigger tranches." A $58K close would eject the 10% tranche at the price the same report says deploy the next 15%. The stop is also a *pure price line* with no thesis/score condition — so on Jun 6 it would have abandoned the position at the cycle's *highest* score (16) and *lowest* F&G (9), the strongest accumulate signal. **Gold already shows the coherent design** — a compound stop ("sustained weekly close <$4,000 *without score recovering above 12*") placed *below* the deepest buy ladder. Crypto simply never received the port.

### (d) [HIGH] Optimism-biased probability matrix

Detailed in §3. Root cause: the **monotonic fear→Rally baseline grid** (50% Rally at scores 15–17) forces Rally to modal weight precisely when fear is deepest, and the Jun-10 trend modifier (a 10–15% mass shift) is mathematically too weak to dethrone a 50% baseline cell. Proven the same day it was written: Jun 10, with the 200-week unreclaimed after a fresh cycle low, the modifier applied –10 and still printed **Rally 40% modal** with a $66–76K target while spot was $61.5K.

### Additional flaws surfaced by the diagnoses

- **(e) [HIGH] Unfalsifiable cross-validation (Hard Rule 5).** Only one FR report exists (May 14, F&G 50 — the moment of *least* signal). Every subsequent inverse "consistency" claim used an eyeballed, sourceless "~3–4" — produced by the same analyst writing the high FK score, so the check cannot catch an inconsistency by construction. It violates Hard Rule 1 (source + timestamp) and goes unfalsifiable from May 28 onward. (On Jun 3, a combined report even invented an S&P "Flying Rocket ~13–15" with no FR run — proof the numbers are fitted to narrative.)
- **(f) [HIGH] Score is a coincident fear gauge, not a forecast.** It rose monotonically 8→16 *as price fell –23%*, peaking at the lowest-confidence-in-a-bottom moment. Because it drives the matrix and EV, it actively manufactured optimism into weakness.
- **(g) [MEDIUM] Sentiment-leg whipsaw.** The 5-point sentiment leg (largest single weight) keyed off single-day F&G that swung 11→23→17→12→9 across five sessions; the Jun 4 report itself called a 2-point score move "a noisy daily sentiment print." (The 3-day average added Jun 10 helps the *score* but the deploy-gate band test still flips on the smoothed crossing.)
- **(h) [MEDIUM] Correlation modifier whipsaw.** A multiplicative band-edge step (×0.85/×1.00/×1.05/×1.10) on a near-integer raw score, riding on *admitted eyeballed* correlations ("~0.3–0.5") — can flip the gate-driving integer off an unsourced guess. The Jun 4 report called its effect "a modifier technicality."
- **(i) [LOW] Cross-asset sentiment non-comparability.** BTC/ETH/SOL use crypto F&G; Gold's sentiment leg is free-form prose ("fear deepening → 2.0/2.0") — the least falsifiable input in the series, scored at maximum at the low.

---

## 5. VERIFIED Tuning Set

Every proposed tune was run through adversarial review (refutation attempt, overfit check, unintended-consequence scan, counterfactual over the realized path). The verdicts below are the *adjudicated* outcomes, not the proposals as written.

### ADOPTED (with modifications as noted)

| # | Tune | Before | After (adopted form) | Verdict | Why |
|---|---|---|---|---|---|
| 1 | **Re-band valuation leg** | MVRV-Z `0–1 → 4` (one band spans $79.5K→$66K) | Single evidence-based breakpoint: `<0→5 · 0–0.5→4 · 0.5–1.0→3`; **drop** the speculative high-end rewrite; keep `1–2→3 · 2–3→2 · 3–5→0 · >5→−2` | **adopt-mod** | Leg was frozen at 4 across a –23% move and already 4/5 at a local top. One clean breakpoint scores the May top-zone (MVRV 0.64–0.87) at 3, not 4 — preserves the matched FR seam and avoids §7 trim-table collision. Re-run the May-14 cold-start floor to confirm the Phase 1A unlock isn't silently weakened. |
| 2 | **Demote correlation modifier** | Multiplier ×0.85/×1.00/×1.05/×1.10 on summed score | Decoupling/bonus branches → **context label only** (no multiplier). **Preserve the ×0.85 risk-on suppressor as a gate**: when sourced 30d corr >0.7, require one additional [V] confirmation gate for any unlock. Require sourced corr or "not computed." Mirror in FR. | **adopt-mod** | The multiplier rode on eyeballed correlations and was self-described "a modifier technicality"; it never changed a gate-driving integer in-sample, so removal is non-destructive. Keeping the suppressor as a breadth surcharge retains its only legitimate function (a "fear" score in a high-beta regime is less trustworthy) and extends it to Phases 1A/1B. |
| 3 | **Mandatory computed companion score** (kill eyeballed FR/FK estimates) | "Est. Flying Rocket ~3–4" with no FR run, no source | **Computed** companion composite (number + gate count) from the same live data fetch, every report. Estimated numbers **prohibited**. Full companion report only when companion ≥9 (informational watch; does **not** unlock any short phase). "Not computed" pauses **only net-new long deployment** — never forces a trim or relaxes a stop. Drop the "within 6 points" trigger. | **adopt-mod** | Closes the loophole that let a stale May-14 FR=0 stand in as "consistency" through a 23% drawdown. Near-free (inputs already fetched). Scoped block prevents a data-plumbing failure from doing the opposite of intent; ≥9-is-informational preserves Hard Rule 6 (FR Phase 1A still needs ≥13). |
| 4 | **Canonical-spot reconciliation** | No rule; reports pick an informal round number ("~$4,130") | Canonical spot = **median of primary + ≥2 sources**, timestamped. If spread >0.5%, report it and compute EV at both extremes. If the EV-sign flips *and* |median EV| < spread: mark **low-confidence/corroborative-only** and require a second independent unlock — **do NOT** mark INDETERMINATE. | **adopt-mod** | Gold Jun 10 had a ~0.43% inter-source spread while EV was quoted at 0.1%; the May-31 "don't add" call sat at –0.5%, *inside* the spread. The median+report clauses are strict improvements; the auto-INDETERMINATE clause was cut because it would have suppressed the framework's single best near-zero EV call. |
| 5 | **Stop must sit below the deepest buy zone** | $58K stop while Phase 1B zone is $53–58K | Hard placement rule: Phase 1A stop strictly **below the deepest defined buy zone**. **Delete** the "no price stop at all" escape clause; if the deepest zone is open-ended, anchor a defined margin below the deepest *named* reference. When the Deep-Value Override is armed, a below-ladder catastrophic stop is **mandatory**. | **adopt-mod** | Removes the stop-vs-DCA contradiction present in 5/5 BTC reports and both SOL ladders. The escape clause was removed because, with Phase 3 open-ended, it would have stripped the stop entirely — dangerous alongside an override that adds into weakness. |
| 6 | **Compound thesis stops (price AND score)** | "Daily close <$58K → stop fires" (pure price, fires at score 16) | Port Gold's template to all crypto: invalidation = sustained **weekly** close below the structural floor **AND** composite score back below 12. Pair with a **mandatory time stop** (Gold-style). Keep §7 narrative-break as the independent 100%-exit. Pin "sustained" (e.g., 2 consecutive weekly closes). | **adopt-mod** | A pure-price stop that fires at score 16 / F&G 9 is self-contradictory. A conjunction is strictly *more* conservative about firing — it cannot newly fire where the price stop wouldn't. Would NOT have fired Jun 6 (score rising) — correctly keeping the holder in the position that recovered. Time stop + narrative-break close the "never-fires in a slow bleed" gap. |
| 7 | **Stop-vs-buy-zone coherence check every report** | No mandated check; overlap silent in 5/5 reports | Mandatory one-line gate (printing the two numbers): "Stop [X] below deepest active buy-zone floor [Y]? PASS/FAIL." **Long-side skill only.** Escape-hatch loophole closed: widening/removing a stop while an override is armed requires a stated max-drawdown-to-thesis-stop figure. | **adopt-mod** | The defect was invisible because nothing forced the comparison. A cheap boolean would have flagged all 5 BTC reports. Scoped to FK only — FR's stop-above-entry is *correct*, so the check is inapplicable there (and porting it would breach Hard Rule 6). |
| 8 | **Flatten the score→grid Rally bias** | `15–17 → Rally 50%`, `18–20 → 65%` | `15–17 → Rally 38/Range 33/Retest 19/Bear 10`; `18–20 → 50/28/14/8`; footnote: grid is a FEAR map, not a direction forecast; Rally capped below modal at every tier. **Reconcile with §5**: reduce the §5 trend shift to a residual ≤5–7% to avoid double-counting; add an **EV floor check** — if EV-vs-spot is negative at score ≥15 + F&G ≤15, flag as internal inconsistency (a deep-value zone should not show negative edge). | **adopt-mod** | Removes the structural source of optimism (50% Rally manufactured by deepening fear). The flatten alone is sound; the modification prevents it stacking with §5 into a perverse *negative* edge at maximum fear, which would fight the Deep-Value Override. |
| 9 | **Verdict-confidence collar** | §11 lists content only; no link between prose assertiveness and the quant layer | When |EV|<2% OR scorecard within 1 of balanced OR score 6–10: **prohibit** declaring a directional regime resolved. Replace the literal banned-word list with an **assertion-vs-conditional** distinction (IF/THEN and negations permitted). Cross-reference Principle 3 + §11 "The Pattern." Mirror to FR. Soften the strong-claim unlock to "score ≥15 OR a realized trend-structure event." | **adopt-mod** | Mechanically blocks the May-14 "fear window closed" sentence (EV +0.6%, 7/7, score 8) while leaving the correct 100%-dry stance untouched. The May-28 well-hedged verdict passes unchanged — proving it catches over-statement, not good writing. Lexical fix prevents flagging the correct conditional "knife has landed" tells in Jun 6/10. |
| 10 | **Single-observation durability lock** | One bounce → "$80K converted to support"; one MA cross → "flows flipped supportive" | Adopt as a **principle, asymmetric**: keep the ≥5-session flow bar (reuses the existing Phase 3 [T] ETF gate); for levels, use the existing "trend-structure repair" standard rather than an invented "2 closes." High-conviction claims that *reinforce* the prevailing down/fear regime may use fewer observations; the bar applies to claims that a fear regime has *ended*. Soft annotation, not a mandatory tag. | **adopt-mod** | Targets the two May-14 over-statements falsified in 14 days. Made asymmetric so it cannot muzzle a *correct* fast bearish call (the May-28 "decisively reversed" read was right on one week of data). |
| 11 | **Forward-claim hedge (two-tier certainty)** | Blanket "use probability ranges, not certainties" | Adopt the **principle, drop the word-list**: realized-data statements may use strong language; forward/regime-resolution claims must carry a probability OR an IF/THEN **and** a named falsifier — enforced on *structure*, not vocabulary. Cross-reference Principle 3 + §11. Mirror to FR in the same change (FR ≥ as strict). | **adopt-mod** | The two-tier distinction is sound and general; the banned-adverb list was overfit (it would flag the correctly-hedged negated "decisively" in the Jun-10 report and miss "the bottom is in," which uses no banned word). |

### REJECTED (dropped — and why the rejection matters)

| # | Tune | Verdict | Why dropped |
|---|---|---|---|
| R1 | **Split sentiment into LEVEL + STABILIZATION (+2 on reclaim)** | **reject** | Pays its +2 *on a reclaim* — rewarding the exact bull-trap condition Principle 3 forbids. Strips 2 points during deepening fear, pushing Jun 6/10 below 15 and **disarming the Deep-Value Override at F&G 9** — the moment it exists to exploit. Helps nothing on the grind, actively hurts on a V-bounce. Reverse-engineered to output 0 on exactly the Jun-6/10 rows. The defect it targets is already neutralized at the *gate* layer. |
| R2 | **Composite downtrend penalty (−2 to score in active downtrend)** | **reject** | Triple-counts the 200-week (already costs gate 6 and shifts the §5 matrix). Turns Jun 6/10 scores 15/16 into 13/14 — **breaks the override** at the cheapest prices. On ETH, collapses Strong Signal into Accumulation. Makes the §7 –6-point trim trigger reachable on trend noise, risking a forced trim of an accumulation position at the lows. Protects zero capital in-sample (1A deployed before it triggers; nothing past 1A deployed either way). If a trend signal on the score is ever wanted, keep it a non-numeric label only. |
| R3 | **Lower gate-7 to 0.2%-of-mcap multi-trigger** | **reject** | Built on misremembered data ("SOL 0.27%" — the real print was **0.014%**; "$90M flush" and a post-May-28 SOL report that doesn't exist). Even at 0.2%, the BTC $1.57B flush (0.13%) still doesn't clear it, and SOL's 0.014% scores zero — the tune *fails its own cited example*. Sub-conditions (b)/(c) (–8% day, 2× weekly range) recur mid-downtrend, manufacturing false "capitulation confirmed" reads. **Replace with a regime-relative trigger** (top-decile of trailing-90-day liquidations OR >3σ above trailing mean), single conservative condition, requiring price stabilization — but the *fixed-threshold* version is rejected. |
| R4 | **Port FR N/A-denominator rule into FK (as written)** | **reject** | False premise that unlocks count "[V]-only" — every phase still carries a hard absolute "/9" floor. Gate 5 is a [T] gate, so removing it does nothing for the binding [V] count. Capital-neutral over the sample (numerator unchanged). Worse, leaves the threshold-rescale "unspecified," risking a silent loosening of the real-money unlock. **A narrow, threshold-as-proportion version** (require ceil(fraction × active_denominator); forbid converting ambiguous ⚠️ to N/A) is acceptable — see §6 — but the open-ended port is rejected. |
| R5 | **Sustained-divergence gate-set audit trigger** | **reject** | Manufactures deployment pressure into a falling knife: "presumed mis-specified → re-examine" is a soft mandate to *loosen* gates, exactly when 200-week broke + CPI 4.2%. Score-high/gates-dark is the *designed, healthy* behavior of a fear gauge + floor gate in a downtrend — **not** a logical contradiction like Hard Rule 5's both-≥12 (which is genuinely impossible for true inverses). False equivalence trains the analyst to treat a normal bottom-approach as an error. Replace with a documentation-only convention that *defaults to vindicating the gates*. |
| R6 | **Lower override trigger 10% → 7% below basis** | **reject (as written)** | $61,750 trigger is a $219 (0.36%) margin from the realized $61,531 — fitted to one bounce. Helps +0.19% of book on the path that bounced; costs –0.6% to –1.6% in the framework's own 35%-weighted retest/bear scenarios. One-way loosening of a long-side confirmation gate (Hard Rule 6 spirit). **Fix the real defect instead** (see §6): evaluate the price condition against the trailing-period low/daily close so a –9% wick the report misses can still arm it; or step to 8% paired with a fresh lower-low requirement. |
| R7 | **Override off drawdown-OR-next-zone, 5% threshold** | **reject (as written)** | Fires the 1B half-tranche at ~$60K on Jun 6 — *above* the report's own pre-named $53–58K zone, on the day price wicked to within 1.9% of the stop. The migrating-zone OR-clause is satisfied trivially during the descent (pulls deployment *forward* into shallower drawdowns). Fights two other Jun-10 fixes. **Replace** with: fire only when spot has *closed inside* the pre-named deep zone, plus a worsening-flows veto (ETF outflows re-accelerating + MA broken in last 5 sessions blocks it). |
| R8 | **Symmetric price-trigger escape hatch for BTC/ETH phases** | **reject** | The Gold-vs-BTC gap was driven by *realized price path*, not clause asymmetry: Gold crashed *into* its zone ($4,130 in $4,000–4,200); BTC/ETH held *above* theirs. Over the actual path the hatch fires on **neither** BTC nor ETH — 0% deployment change, refuting the rationale. Where it would fire (BTC grinding to $55K) it deploys a *full* 15% on a price tag alone into a broken-200-week, hot-CPI tape — double the override's half-size. If the commitment-device is wanted, route it *through* the override (substitute the in-zone close for the 10%-below leg, keep half-size + score ≥15 + no narrative break). |
| R9 | **Lower gate floors to 1B≥4 / 2≥5 + half-weight failed [T] gates** | **reject** | The half-weight clause makes [T]-gate *failure* additive to the unlock count — the worse the trend breaks, the closer Phase 2 gets. Perverse. Deploys 25% at ~$63.5K into a –6.9% drawdown for no realized gain; the count was binding, not size. Free parameter (0.5) tuned so Jun 3 unlocks. **Fix instead** by nudging the override's discount threshold (10%→8%) — captures "deploy more as it gets cheaper" with protection, without crediting broken gates. |
| R10 | **Re-weight phase sizes 10/15/30/45 → 15/25/30/30** | **reject** | Resizing a *frozen* tranche doesn't open the gate — pure size change is inert (still 10–15% deployed). It only reaches "40% authorized" via a *smuggled* second change (1B inherits 1A's gate logic) that deploys 40% at $65K into a knife that fell to $59,110 — **quadrupling the deployed loss** in-sample. Also cuts Phase 3 from 45% to 30% (shrinks the generational tranche — contradicts the front-load thesis and Hard Rule 3). The legitimate lockout concern is already served by the override. |
| R11 | **Cap Rally cell hard in a downtrend (Rally ≤ Range, Rally+Range ≤60%)** | **reject** | Mis-timed to its own sample. The real directional miss was **May 31–Jun 5** (where the grid was *already* Range-modal). The cap only bites Jun 6/10 — exactly where Rally-leaning was *right* (BTC bounced +2.5% off $59,110). Flips EV from +4.0% to –1.5% into a bounce; forces ≥40% mass into $46–58K bands that never printed. **Adopt the milder half** (widen the §5 shift 10–15% → 15–20%, scaled to depth) — but the hard cap is rejected. |
| R12 | **Trend term in the EV readout (counter-trend disclosure + N-report suppression)** | **reject** | Redundant with the §5 modifier (which fixes EV at the source by pulling targets down). The "EV has lagged N reports → may not justify accumulation" clause is **anti-bottom and pro-cyclical** — it suppresses the buy-the-fear thesis hardest at the deepest, most-feared prices, because the bottom is *by definition* when EV has been "wrong" longest. **Keep only** the disclosure of realized 2-week price change next to the EV claim, folded into §5 — drop the suppression clause. |
| R13 | **Hysteresis band on the deploy-trigger sentiment test (arm ≤12 / disarm >18)** | **reject** | Premise already solved by the 3-day average (on which the 11→23→17 window is 11/17/17 — no whipsaw). Worse, arm-at-12 is *stricter* than the current ≤15, so on the realized monotonic decline it **delays** deployment one day / $1–2K lower at maximum fear — the opposite of intent, and it pushes the same direction the backtest condemned (under-deployment). If hysteresis is wanted, re-center *above* the trigger (arm ≤15, disarm >25). |
| R14 | **Cross-asset sentiment percentile normalization** | **reject** | Built on a misattribution — the "VIX ~16 → 0.5/5" line scored the **S&P 500**, not Gold; Gold was scored 1.5–2.0/5 on gold-native reads in every report. The "40% rally-modal optimism" claim is false (Gold Rally was 28–30%, Range was modal). A trailing-1yr fear percentile is **procyclical**: a –26% asset prints new "fear lows" for months, pinning sentiment to 5/5 and — via the override's "extreme band" gate — authorizing deeper deployment into a falling knife. **Keep only** a one-line guardrail: sentiment must use an asset-*native* fear instrument; equity VIX is a regime data point, never the scored input. |
| R15 | **Score-conditioned cumulative deployment cap (25% needs ≥14, etc.)** | **reject** | Misdiagnoses the Gold over-deploy: Gold's 1B fired on a **score-threshold in a substituted gold rubric** (score ≥12, no gate count), not on crypto gates clearing at a mid score. A crypto-side cap wouldn't bind it. Inert on BTC (gate-locked at 10% anyway). Blocks the Jun-10 Gold Phase-2 add at the *local low* ($4,130, the best-priced entry). Strands Phase 3's 45% behind a score-≥16 wall in a real washout. **Fix locally** in the gold adaptation instead (require breadth confirmation + cold-start ≥13 for adapted 1B). |
| R16 | **Mechanical thesis-downgrade gate (keyed to MA position)** | **reject** | Doesn't catch its own target. The gate keys on the **200-week** (BTC's gate-6 MA); on May 14 price was ~40–55% *above* the 200-week, so the gate **passes** and green-lights the very "missed-fear cycle" language it exists to block. The cited "3.5% below" was the *200-day*, a line the gate doesn't reference. The real May-14 error was calling a *neutral* tape (F&G 50) a closed fear cycle — a sentiment misjudgment, not an MA one. Collides with §7 exits (the thesis *can* close below the MA on a narrative break). Handled instead by the Verdict-Confidence Collar (#9). |
| R17 | **Regime-tagged "do not chase" (with score-≥15 deploy-check pairing)** | **reject** | The reports already produce regime-typed variants organically ("chase the bounce," "chase the first green candle"). May 14 had *zero* "chase" instances — you can't fix a declarative thesis error by typing a phrase that wasn't there. The dangerous half ("at ≥15, pair with 'is the next tranche unlockable?'") **biases toward deploying into maximum fear** on a descending knife — the exact behavior the gates correctly blocked. **Keep only** a minimal note to name the regime risk; drop the score-scaling deploy-check. |

**Net: 11 adopted (all with modification), 17 rejected.** The rejection pattern is consistent and important: nearly every rejected long-side tune either (i) broke the Deep-Value Override by knocking scores below 15 at the lows, (ii) was overfit to a single bounce/print, or (iii) loosened a capital guardrail into a falling knife. The frameworks' restraint was right; most "improvements" that touched the score or loosened a gate would have made the realized outcome *worse*.

---

## 6. Remaining Edits Required (Applied-Edits Audit)

The Jun-10 edits are **well-motivated and factually anchored** — the score-rose-to-16-while-gates-fell-to-3 diagnosis is real, not a strawman. But the audit found the shipped edits are mis-calibrated and incomplete. Concrete fixes:

### Fallen Knives (`/.claude/skills/fallen-knives-analytics/SKILL.md`)

1. **Fix the §Analytical Principles numbering bug.** The anti-bull-trap insert created two "4." entries — the list reads 1,2,3,4,4,5,…,10 (eleven items). Mechanical renumber from the insert onward. *(Confirmed in the audit.)*

2. **Parenthesize the Phase 3 gate clause (line 157).** As written, `…≥7 of 9 with ≥4 [V] OR Override fires AND a weekly capitulation candle…` is ambiguous. Make it unambiguously **`(≥7 of 9 with ≥4 [V]) OR (Override fires AND weekly capitulation candle)`**. This is the generational 45% tranche — the most expensive place to leave an operator-precedence bug.

3. **Add an override throttle / §5 interaction rule.** The override and the trend modifier were written independently and are not wired together. Per single firing the override is safe; **as a chain it can walk the book to ~45% deployed through an uninterrupted –27% cascade with zero trend confirmation.** Add either: "while the §5 active-downtrend shift is live, the override deploys at **quarter-size**, not half," or "at most one override unlock per report / N days," or a cap on total override-deployed capital until one [T] gate or a confirmed higher-low repairs. **This is the highest-priority remaining safety fix.**

4. **Fix the override's reachability** (the real defect behind R6/R7). The shipped 10%-below-basis trigger ($58,500 off a $65K basis) was **never reachable** — the deepest print was $59,110 (–9.1%), so the override **never fired despite score 16 / F&G 9.** It is decorative as written. Re-anchor the price condition to the **trailing-period low or a daily close** (so a –9% wick the report cadence misses can still arm it), OR step to **8% paired with a fresh lower-low requirement** and a worsening-flows veto. Keep half-size, score ≥15, F&G ≤15, no narrative break.

5. **Reconcile [V]/[T] buckets with N/A gates for PoS assets.** Gate 5 (Hash Ribbon) is N/A for ETH/SOL, leaving only two [T] gates — which makes **Phase 3's "≥7 of 9" nearly unreachable** for PoS assets. Import the *threshold-as-proportion* version (R4-modified): define unlocks as fractions and require `ceil(fraction × active_denominator)`; permit N/A-with-denominator-reduction **only** for structurally inapplicable gates (Hash Ribbon on PoS); **forbid** converting an ambiguous ⚠️ to N/A.

6. **Recalibrate gate 7 to a regime-relative trigger** (R3-modified): top-decile of trailing-90-day liquidations OR >3σ above trailing-30-day mean, with explicit asset-specific-vs-mcap denominator stated, requiring price stabilization. The 0.5%-of-mcap fixed bar is unreachable for mega-caps and must go.

7. **Add a one-line cross-reference** noting reframed gate 6 (eligibility) and the §5 trend modifier (EV/sizing) respond to the same 200-week break in opposite directions and are *not* meant to cancel.

### Flying Rocket (`/.claude/skills/flying-rocket-analytics/SKILL.md`)

The audit's most important finding is **good news: the dangerous tunes were correctly withheld.** No deep-greed override, no stop loosening, no gate softening, mandatory price+time stops intact, 50%-book cap intact, squeeze penalty intact — Hard Rule 6 honored. The one safe symmetric tune (3-day sentiment smoothing) was correctly mirrored. **Three safe symmetric tunes are still missing and should be added:**

8. **Trend term in the §5 probability matrix.** FR's grid has the mirror flaw — euphoria→mean-revert monotonically, reading persistently bearish in an active *uptrend* (the short-side falling-knife). When price is above a major MA and making higher highs, shift 10–15% of mass from Mean-Revert/Bear-Reversal → **Continued Rally** (respect the trend you're shorting into). This is the short-side directional-humility tune.

9. **Interpretation-table reconciliation footnote.** FR's stance table has the identical latent over-promise ("Phases 1A–2 eligible" reads as authorization, but FR deployment is *additionally* gated by gate count + phase-of-cycle cap + squeeze penalty + carry). Mirror the FK footnote: stance = score eligibility only.

10. **Anti-bear-trap analytical principle.** Mirror of FK's anti-bull-trap: "pullbacks within an uptrend are suspect — never declare a top confirmed on a single down-week; require trend-structure breakdown (loss of a major MA or a confirmed lower-high) before deploying the distribution thesis."

11. **Preserve explicitly:** no deep-greed override, no stop loosening, no gate softening — and note in the revision log that this withholding is *deliberate*, per Hard Rule 6.

### Cross-cutting (lower priority)

12. The May-14 FR report frames cross-validation as "sum = 8 vs a 24 threshold." Hard Rule 5 defines inconsistency as **both frameworks ≥12 simultaneously**, *not* a sum. The "24" appears nowhere in either skill. If an explicit cross-validation arithmetic is ever codified, align it to "both ≥12" — never a sum.

---

## 7. What to Preserve

These behaviors *worked* and must survive the tuning. Several rejected tunes were rejected precisely because they would have damaged one of these:

1. **Conservative sizing / dry-powder discipline.** 10% tranches kept the BTC book ~flat through a –23% asset decline; SOL's 100%-dry call avoided a ~24% drawdown. This is the framework's core edge. *Do not let any score/size tune front-load capital into an unconfirmed downtrend.*

2. **"Gates win over score."** The judgment that kept the book from blindly deploying Phase 1B/2 into a falling knife. The fixes in §6 make the gates *reachable at genuine deep value* — they must not make them *easy*. Every rejected loosening tune (R6–R11) threatened this.

3. **"Honor the close, not the wick."** Three sub-2% stop near-misses, zero whipsaw stop-outs. The close-based (and now compound/weekly) design is non-negotiable.

4. **The correct "wait" calls at the top.** May 14 / May 28 at $79.5K/$76K — the *stance* (100% dry) was right even when the *narrative* was wrong. The Verdict-Confidence Collar (#9) fixes the narrative without touching the stance.

5. **The front-loaded pyramid splits (10/15/30/45, Hard Rule 3).** R10 was rejected partly for shrinking Phase 3. The deepest tranche stays the biggest.

6. **Asymmetric humility on shorts (Hard Rule 6).** FR's stricter thresholds, smaller phases, mandatory price+time stops, and the *absence* of a deep-greed override. The correct withholding of dangerous mirrors is itself a behavior to preserve.

7. **The Deep-Value Override's *intent*** — deploy more as it gets genuinely cheaper and more feared. It is mis-calibrated (unreachable, un-throttled), but the concept is the right answer to the pyramid inversion. Fix the calibration (§6.3, §6.4); keep the mechanism.

8. **Refusal to over-react to headline noise.** The Saylor "32-BTC sale" was correctly called a dent, not a thesis break (Strategy was a net buyer +24,869 BTC). The §7 narrative-break exit correctly stayed dormant all cycle.

---

### Honest statement of uncertainty

Three things this retrospective **cannot** resolve from the sample:

- **Whether the recalibrated gates would actually let us buy the bottom.** SOL pinpointed the $60.20 low to the dollar but its liquidation gate never tripped (~0.27% vs 0.5%); with no follow-up SOL report, we do not know whether even the *fixed* gates would have authorized that entry. The regime-relative gate-7 fix (§6.6) is the best available answer, but it is untested in-sample.
- **Override chain safety.** The runaway-to-45% scenario (§6.3) is a *plausible* path, not a *realized* one — BTC bounced off $59,110 before chaining. The throttle is prudential, sized to a risk we did not actually run.
- **N=1 on every tune.** Every calibration here is fit to one four-week, single-regime cascade. The adopted tunes were filtered for *generality* and *direction-of-effect* rather than point-precision, and the modifications strip the most path-fitted parameters — but a V-bounce regime or a deeper washout (MVRV-Z <0, liquidations >1% of mcap) would test bands this sample never reached. Re-validate after the next full fear cycle before treating any threshold as settled.

**The frameworks survived because they doubted their own forecasts enough to keep the powder dry. The correction is to make that doubt structural — in the EV machine, the probability grid, and the verdict's voice — without ever letting it relax the gates that did the surviving.**