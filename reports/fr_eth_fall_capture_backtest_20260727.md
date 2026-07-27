# Flying Rocket — ETH Fall-Capture Backtest
## Why the framework caught ~0 of twelve ≥10% ETH declines, and what it would take to catch half
### Run: 2026-07-27 | Asset: ETH | Window: 2025-07-27 → 2026-07-27 (+ out-of-sample 2022-06 → 2025-07, + BTC cross-asset)
### Purpose: evidence base for the 2026-07-27 FR revision (owner mandate: "enter at least 50% of falls >10% for ETH, risks still handled")

---

## 0. Method

Deterministic, no lookahead, no discretion. All price data Yahoo Finance daily OHLC (`ETH-USD`, `BTC-USD`), fetched 2026-07-27. Sentiment: alternative.me F&G daily history (800 prints). Indicators computed with `tools/lib.mjs` (`wilderRSI`, `sma`) so the backtest and the live framework share one implementation.

- **Fall definition:** ZigZag with a 10% reversal threshold on daily closes. Each `H → L` leg with depth ≥10% is one fall. Non-overlapping by construction.
- **Scoring reconstruction:** the three legs computable from price + F&G history (Euphoria Sentiment, Momentum Overextension, Valuation-by-ATH-distance) are computed exactly, via `tools/lib.mjs`'s `fr.*` band classifiers — i.e. with the live rubric, including its edge conventions. The two legs that need paid data (**Distribution Evidence**, **Structural Vulnerability**, 3 pts each) are **not** reconstructable historically and are treated as a parameter, `unmeasured ∈ [0,6]`, with results reported across the range.
- **Simulation fills:** signal on close of day *i*, fill at the **open of day _i_+1**. Stops are intraday-high touches. No slippage, no funding — both of which make the results below *optimistic*, and both of which cut against shorts.

**Scope honesty.** One asset, one primary window, n=12 in-sample events. Every parameter below that was read off the in-sample window is re-tested out-of-sample in §4, and one of them failed that test (§4.2). Treat §3's trigger as a coarse regime filter, not a calibrated edge.

---

## 1. The finding: twelve falls, and the framework could not reach an entry on any of them

Twelve ≥10% ETH falls in the trailing year. For each, the state **on the peak day**:

| # | Peak | Depth | Days | % below 1y ATH | Cycle cap | Weekly RSI | F&G 3d | vs 200dma | Legs (s/m/v) |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 2025-07-27 | −12.5% | 6 | 5.6% | none | 70.8 | 73.3 | **+55.9%** | 3/3/3 |
| 1 | 2025-08-13 | −14.4% | 6 | 0.6% | none | 71.8 | 72.0 | **+87.4%** | 3/3/5 |
| 2 | 2025-08-22 | −11.5% | 15 | 1.1% | none | 74.1 | 53.3 | **+86.1%** | 1/3/5 |
| 3 | 2025-09-12 | −18.0% | 13 | 4.8% | none | 68.0 | 54.3 | **+69.6%** | 1/2/5 |
| 4 | 2025-10-06 | −20.0% | 5 | 5.4% | none | 57.8 | 71.7 | **+53.6%** | 3/0/3 |
| 5 | 2025-10-13 | −34.9% | 39 | 14.3% | **14** | 55.1 | 33.3 | **+35.7%** | 0/0/3 |
| 6 | 2025-12-10 | −15.0% | 8 | 32.9% | **8** | 43.5 | 25.7 | −6.4% | 0/0/0 |
| 7 | 2026-01-14 | **−45.7%** | 22 | 32.3% | **8** | 48.5 | 45.0 | −7.9% | 0/0/0 |
| 8 | 2026-02-09 | −11.9% | 15 | 57.5% | **8** | 31.4 | 10.0 | −41.5% | 0/0/0 |
| 9 | 2026-03-16 | −15.7% | 13 | 52.5% | **8** | 35.5 | 22.0 | −27.1% | 0/0/0 |
| 10 | 2026-04-17 | **−35.2%** | 50 | 51.1% | **8** | 42.1 | 23.3 | −15.8% | 0/0/0 |
| 11 | 2026-06-15 | −12.8% | 10 | 63.8% | **8** | 32.7 | 20.3 | −25.3% | 0/0/0 |

**Reachability, assuming both unmeasured legs were _perfect_ (3/3 and 3/3 — a deliberately absurd best case):**

- falls where the adjusted score could even reach the Phase-1A line of 13: **4 / 12 (33%)**
- falls where the **phase-of-cycle hard cap alone** made 13 unreachable regardless of every other input: **7 / 12**
- median % below 1y ATH at the fall's peak: **32.3%**

At *realistic* values for the unmeasured legs (§5), the honest in-sample count of falls the live framework could have entered is **one** — #1, 2025-08-13 — and only at `unmeasured ≥ 2`.

### 1.1 The mechanism

The **phase-of-cycle hard cap** (§4 of the SKILL) caps the adjusted score at 8 when the asset is >20% below its 1-year ATH, and at 14 when it is 10–20% below. Phase 1A needs 13. So:

> **An asset more than 20% off its 1-year high cannot be shorted by this framework at any score, on any evidence, ever.**

That is not a conservative setting. It is a structural exclusion, and it excluded falls #6–#11 — including a **−45.7%** collapse and a **−35.2%** grind — before a single input was scored. Fall #5 (−34.9%) was excluded by the 14-cap the same way.

The cap is not wrong about what it was built for. It correctly encodes "a recovery tape is not a distribution tape." The error is treating *distribution from a top* as the **only** shortable structure in crypto.

---

## 2. The falls are two different animals

Sorting the same twelve by whether the peak was above or below the 200-day MA:

| Population | Falls | vs 200dma at peak | Weekly RSI | F&G 3d | Rally into the peak |
|---|---|---|---|---|---|
| **A — Distribution tops** (#0–#5) | 6 | **+35.7% … +87.4%** | 55.1 – 74.1 | 33.3 – 73.3 | 22–92% off the 40d low |
| **B — Bear-market rallies** (#6–#11) | 6 | **−6.4% … −41.5%** | 31.4 – 48.5 | 10.0 – 45.0 | 19–35% off the 40d low |

Population A is what Flying Rocket was designed for and scores competently. Population B is **half of all the falls** and the framework has no rubric that can see it:

- **Euphoria Sentiment** scores absolute F&G ≥50 → **0 points at all six** B peaks (F&G 10–45).
- **Momentum Overextension** scores *weekly* RSI >60 → **0 points at all six** B peaks (31–49).
- **Valuation** by MVRV-Z or ATH-distance → **0 points at all six** (they are 33–64% off the high; that is the *bull* case, not the bear case).

Yet the B peaks have their own tight, consistent signature, visible on the day:

> a **19–35% counter-trend rally** off a 40-day low, dying **below the 200dma**, with **weekly** RSI still **31–48** and, in four of the six, **daily** RSI-14 recovered to **58–67**.

Four of the six B peaks printed daily RSI 58.0 / 66.1 / 66.6 / 65.0. The framework measures euphoria on an absolute scale in a market that had already repriced 60%. What identifies a bear-rally top is **local, relative** euphoria — F&G 45 against a 30-day mean of 20 is a genuine sentiment extreme *for that regime*; weekly RSI is simply the wrong timeframe for a three-week bounce.

---

## 3. Testing a bear-continuation channel

### 3.1 Breakdown entries do not work

First candidate family — short the breakdown (fresh 20-day low, 50dma<200dma), in a confirmed bear regime (below 200dma, 200dma falling, >20% off the 1y high):

| Rule | Trades | Falls caught | Win rate | Σ PnL | Worst |
|---|---|---|---|---|---|
| breakdown(20d low), stop +8%, time 28d | 4 | 3/12 (25%) | 50% | +11.8% | −8.0% |
| lower-high + loss of 20dma, stop +8%, t28 | 5 | 2/12 (17%) | 20% | −1.0% | −8.0% |
| bounce-to-50dma, stop +8%, t28 | 5 | 2/12 (17%) | 40% | −14.5% | −8.0% |

The breakdown rule's entry dates are the tell: **2026-02-05** and **2026-06-26** — the exact trough days of falls #7 and #11. Shorting fresh lows in a bear market is selling the bottom. Rejected.

### 3.2 Bounce exhaustion

Second family — short the *rally*, not the break. Trigger: in a bear tape (close below 200dma×1.02), price has rallied ≥R% off its 40-day low within ≤30 days, daily RSI ≥ X, and the current bar makes a **lower close and a lower high** than the bounce high (stall confirmation). Stop = min(bounce high +1%, entry +6%). Time stop 21d.

| Variant | Trades | B-falls caught | Win rate | Σ PnL | Avg | Worst |
|---|---|---|---|---|---|---|
| R≥15%, RSI≥45 | 7 | **4/6 (67%)** | 29% | +18.5% | +2.64% | −6.0% |
| R≥15%, RSI≥52 | 4 | 3/6 (50%) | **75%** | **+41.3%** | +10.34% | −4.6% |

The RSI≥52 row is the more attractive one on its face. §4.2 shows it is also the one that does not survive.

---

## 4. Out-of-sample and cross-asset

### 4.1 The population split generalises

| Window | Falls ≥10% | Distribution tops | Bear rallies |
|---|---|---|---|
| ETH in-sample 2025-07 → 2026-07 | 12 | 6 (50%) | **6 (50%)** |
| ETH out-of-sample 2022-06 → 2025-07 | 26 | 13 (50%) | **13 (50%)** |
| BTC out-of-sample 2022-06 → 2026-07 | 24 | 15 (63%) | **9 (37%)** |

**37–50% of every ≥10% fall in crypto begins below the 200-day MA.** This is the most robust number in the study — stable across two assets and five years. It sets a hard ceiling on the current framework: even a perfectly-executed Flying Rocket, catching *every* distribution top, tops out at ~50–63% coverage, and in a bear-dominated year at ~50%.

### 4.2 The trigger partially generalises — and the pretty variant was curve-fit

| Variant | ETH in-sample | ETH OOS (4y) | BTC OOS (4.5y) |
|---|---|---|---|
| **R≥15%, RSI≥45** — catch / win / Σ | 67% / 29% / +18.5 | **62% / 31% / +56.6** | **44% / 54% / +67.1** |
| R≥15%, RSI≥52 — catch / win / Σ | 50% / 75% / +41.3 | 31% / **25%** / +19.9 | 33% / 57% / +38.5 |

The RSI≥52 variant's in-sample 75% win rate collapses to 25% out-of-sample. It was fit to four trades. **Discarded.**

The RSI≥45 variant holds its shape: catch rate 44–67%, positive Σ PnL in all three windows, worst single trade −6.0% in all three (the structure stop binding as designed). But its **win rate is 29–54%** — the expectancy comes from a fat right tail (one +37.8% trade on fall #7 carries the in-sample window), not from being right often.

### 4.3 What that implies about the trigger's role

A signal that fires ~15×/year, is right under half the time, and pays because a minority of trades run 20–38% is **not** an auto-execute rule. It is a **candidate generator**. Its correct use is to open the regime and nominate the bounces; something else has to choose among them — and the "something else" has access to exactly the inputs this price-only backtest does **not** contain: funding flips, ETF flow inflections, borrow, macro turns, catalysts, positioning. That is an argument for a discretion channel with real weight, sized small and stopped tight — not for a mechanical trigger with a bigger allocation.

---

## 5. What it takes to reach 50%

**Channel A — how many of the 6 distribution tops unlock Phase 1A**, by unmeasured-leg assumption and score line:

| Unmeasured (of 6) | ≥13 (current) | ≥12 | **≥11** | ≥10 |
|---|---|---|---|---|
| 2 | 1/6 | 1/6 | 3/6 | 4/6 |
| 3 | 1/6 | 3/6 | **4/6** | 4/6 |
| 4 | 3/6 | 4/6 | **4/6** | 5/6 |
| 5 | 4/6 | 4/6 | 5/6 | 5/6 |

**Combined coverage** (Channel A + Channel B at its OOS-midpoint catch rate of ~55%):

| Channel A line | unmeasured=3 | unmeasured=4 |
|---|---|---|
| ≥13 (current) | 36% | 53% |
| **≥11** | **61%** | **61%** |

Cutting the Phase-1A line from 13 → 11 is what moves Channel A from 1/6 to 4/6 at realistic inputs; it is worth more than any further loosening of Channel B. Going to ≥10 buys one additional fall (#4, 2025-10-06) at `unmeasured=4` and nothing at 3 — a poor trade for the discipline it costs.

**Target configuration: Channel A line ≥11 + Channel B enabled ⇒ ~61% of ≥10% falls, against a 50% mandate.** The 11-point margin is the buffer for slippage, funding, missed reports, and the fact that §4.2's catch rates are the optimistic end.

---

## 6. Conclusions carried into the revision

1. **The binding constraint is the phase-of-cycle cap, not the score thresholds.** It excluded 7 of 12 falls before scoring. Fixing thresholds alone caps coverage at ~53%; fixing the cap is what makes 50%+ reachable at all.
2. **The cap should not be deleted — it should be forked.** It is correct that a >20%-off-ATH tape is not a distribution tape. The fix is a second rubric for what that tape *is*: a bear continuation.
3. **The bear channel must score relative, not absolute, extremes.** Absolute F&G and weekly RSI are structurally blind below the 200dma.
4. **Short the rally, never the breakdown.** Every breakdown entry in §3.1 filled at or near a trough.
5. **The bear trigger is a candidate generator, not an edge.** 29–54% win rate. It earns its place through a fat right tail and a −6% worst case — which means the **stop is the strategy**, and the analyst channel is where the selection actually happens.
6. **Channel A's Phase-1A line moves 13 → 11.** Not 10.
7. **Risk budget must be paid, not assumed.** The bear channel's win rate is *below* Channel A's implied one; it therefore gets smaller size, a tighter mandatory stop, a shorter clock, and no access to the deepest phase.

---

## 7. Falsifiers for this study

Named, so the next calibration can grade them:

- **(Falsifier F1)** If the next 12 months of ETH ≥10% falls split materially away from ~50/50 distribution-top vs bear-rally — say <30% bear-rally — the Channel B build-out is over-weighted for the regime and its size cap should fall.
- **(Falsifier F2)** If Channel B's realised win rate over its first 10 live signals is <25%, the −6% structure stop is not tight enough to carry the tail-dependent expectancy; either the stop tightens or the channel is suspended.
- **(Falsifier F3)** If Channel A at the ≥11 line produces more than 2 unlocks per quarter on ETH, the cut was too deep and reverts to 12.
- **(Falsifier F4)** If ≥3 of the first 10 Channel B entries are stopped out and *then* the fall proceeds ≥10% without re-entry, the stall-confirmation trigger is firing too early in the bounce and needs a lower-high-on-daily-close requirement instead.

**n=12 in-sample, one asset.** Every number in §3 and §5 is a small-sample estimate. The population split in §4.1 is the only finding here robust enough to build a structure on; everything else is a starting parameter awaiting its first live cycle.

---

*Scripts: `bt.mjs` (fall detection + reachability), `bt2.mjs` (breakdown families), `bt3.mjs` (anatomy + bounce exhaustion), `bt4.mjs` (OOS/cross-asset) — session scratchpad. Indicator math via `tools/lib.mjs`. Price: Yahoo Finance daily OHLC, fetched 2026-07-27. Sentiment: alternative.me F&G history, fetched 2026-07-27.*
