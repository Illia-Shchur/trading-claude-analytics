# 🔪 FALLEN KNIVES ANALYTICS — ETH — 2026-08-05

## WEDNESDAY MID-MORNING — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Wednesday, 2026-08-05, 10:08 EDT
### Asset: ETH | Prior Score: 11 mechanical / 11 adjusted (2026-08-03) | Current Score: 11 mechanical / 10 adjusted

---

## 1. What this report decides

Hold. Authorize nothing. Not one leg moved.

Two things did move, and they pull in the same direction. The CLARITY Act — the single regulatory item that bears more on ETH than on any other major — went from a diffuse annual probability to a **hard five-day Senate cliff** at 28–37% odds. And the computed Flying Rocket companion crossed from 8 to **9**, because the same +21.23% bounce that ETH's Fallen Knives legs read as benign structure is what a bear-continuation rubric reads as *extension*. Neither is visible to any of the five legs. Both are priced through a −0.5 discretionary term, which reverses a decline made two days ago — flagged loudly rather than quietly, because a reversal is exactly the kind of thing a calibration should grade.

The D2 conviction path is available on Phase 1A for the **third consecutive report**, and declined for the third time. That pattern is now itself a finding.

---

## 2. Verified Live Data Points — ETH

### 2.1 Canonical spot reconciliation

| Source | Symbol | Price (USD) | Timestamp (UTC) | Status |
|---|---|---|---|---|
| Binance | ETHUSDT | 1,878.73 | 2026-08-05 14:06 | live |
| Kraken | ETHUSD | 1,877.78 | 2026-08-05 14:06 | live |
| Coinbase | ETH-USD | 1,877.29 | 2026-08-05 14:06 | live |
| CoinGecko | ethereum | 1,874.37 | 2026-08-05 14:05 | live |
| Yahoo | ETH-USD | 1,877.18 | 2026-08-05 | **EXCLUDED — frozen bar close, never enters the median** |

**Canonical spot = $1,877.535** (median of 4 synchronized live quotes, all inside a 120-minute window; all four venue timestamps within ~1 minute of each other). Inter-source spread **0.233%**, below the 0.5% bar — no dual-extreme EV computation, no low-confidence demotion. Dispersion is venue microstructure, not time-skew.

SKILL-mandated median used; `tools/fetch.mjs` also reports a priority-first canonical of $1,874.37 (CoinGecko), delta −0.169%. Changes no band, gate boolean, or cap tier.

### 2.2 Sentiment (pinned provider: Alternative.me raw API daily series)

| Metric | Value | Source |
|---|---|---|
| F&G spot | **27** ("Fear") | Alternative.me, 2026-08-05 |
| F&G 3-day average | **26.67** | prints 2026-08-03/04/05 (28 / 25 / 27) |
| Daily prints ≤15, consecutive | **0** | gate-1 streak input |
| Lowest of last 10 prints | 25 | — |
| F&G percentile vs trailing 2y | 32.58 | disclosed context |

The crypto F&G index serves as the proxy for ETH per the Asset Generalization table. Ten prints: 27, 25, 28, 27, 27, 25, 28, 29, 29, 30 — a 25–30 band for ten sessions. No provider switch; no second-provider divergence ≥10 points to disclose.

### 2.3 Momentum — weekly RSI, with the same toolchain artifact flagged in the BTC companion

| Input | Value |
|---|---|
| Weekly Wilder RSI-14 | **41.96** |
| Weekly-close source | Yahoo ETH-USD, 5y 1wk candles |
| Weekly boundary | Yahoo week-start timestamps, UTC |
| Period | 14 |
| Completed closes used | **261**, through the week ending Sunday 2026-08-02 |
| Confidence | ok (≥30 closes) |

**Disclosure.** Yahoo is currently emitting an *extra* weekly bar for the live session (bars: 2026-07-20, 2026-07-27, 2026-08-03, 2026-08-05). `tools/fetch.mjs` drops only the final bar, so its "completed" set today silently includes the **in-progress** week beginning 2026-08-03 and returns 262 closes / RSI **41.58**. The last genuinely completed weekly close is the bar labelled 2026-07-27 (week ending 2026-08-02) → 261 closes, RSI **41.96**, reproducing this series' 2026-08-03 print exactly.

On ETH the artifact is harmless — 41.58 and 41.96 both land in the >40, ≤45 → **1** band, and the direction happens to be *downward* here where it is upward on BTC and gold. Flagged for the toolchain. (On gold the same artifact would have moved a leg and degraded a live stop; see that report.)

`compute.mjs band fk-momentum 41.96` → **1**. Still **one band edge** from a 2: a weekly print below 40.0 would take the leg to 2 and, more importantly, restore the compound stop's second key (§6.3).

### 2.4 Valuation — the debt stays discharged, and the anchor advanced two days

| Metric | Value | As of | Source |
|---|---|---|---|
| MVRV-Z (sourced anchor) | **−1.1144** | **2026-07-06** | Santiment `getMetric(mvrv_usd_z_score)`, slug `ethereum` |
| MVRV ratio (sourced anchor) | 0.7895 | 2026-07-06 | Santiment `getMetric(mvrv_usd)` |
| ETH close on the anchor date | $1,797.57 | 2026-07-06 | Yahoo ETH-USD |
| Implied realized price | **$2,276.85** | derived: $1,797.57 / 0.7895 | — |
| MVRV ratio today (realized held constant) | 0.8246 | derived: $1,877.535 / $2,276.85 | — |
| **MVRV-Z scaled estimate today** | **≈ −0.93** | 2026-08-05 | derived, see below |
| Drawdown from ATH | −62.04% (ATH $4,946.05, 2025-08-24) | 2026-08-05 | CoinGecko |

**Why the anchor is dated 2026-07-06 and why that is disclosed rather than papered over.** Santiment's free tier caps this metric's query window at roughly 30 days behind the present. On 2026-08-03 the cap sat at 2026-07-04; today it sits at **2026-07-06**, so the anchor advanced two days with the calendar. The staleness is bounded and stated, not waved through.

**The sign is arithmetic, not estimate.** MVRV-Z = (market cap − realized cap) / σ(market cap), and σ > 0 by construction, so market value below realized value **forces** the sign negative. Spot would need to exceed ~**$2,277** — **+21.27% above today's $1,877.535** — merely to reach zero. Realized price is a slow-moving aggregate cost basis that rises only when coins move at higher prices, and ETH has spent the entire interval below the anchor-date level. The sign is robust to the staleness by a 21% margin.

**Provider scale cross-check:** Santiment's `mvrv_usd_z_score` for *bitcoin* printed **0.3709** on 2026-07-06 against the independent bitcoin-data.com series on the same scale — two providers within ~0.01 at the overlap, on the asset where both are available. That agreement is what makes the ETH figure usable.

**Still declined, under the provenance-citation rule:** the widely-circulated "ETH MVRV-Z −0.7 / seven-year low" figure traces to a single 2026-06-08 BeInCrypto/Phemex article citing Glassnode at ETH $1,684 — now two months stale at a materially different price. The Santiment series replaces it: a queryable API with a stated methodology and a verifiable cross-provider scale.

`compute.mjs band fk-mvrv -0.9285` → **5** (<0.1 band, and any negative MVRV-Z lands here). The alt fallback band (drawdown −62.04%) would give 4; the primary metric gives 5, which is why the derivation above is shown in full rather than asserted.

**The economic statement, plainly:** the median ETH holder is roughly **18% underwater** on aggregate cost basis. That is the cheapest this metric has read since December 2018.

### 2.5 On-chain, derivatives, flows

| Metric | Value | Source |
|---|---|---|
| Perp funding, mean per 8h | 0.00% (annualized **+3.22%**) | Binance fapi, 45 intervals / 16 sessions |
| Negative funding intervals in 45 | **0** (longest negative run: 0) | Binance fapi |
| Funding percentile vs available history | 61.68 | disclosed context, 167d history |
| ETH 24h liquidations | **~$7.27M** (longs $2.97M / shorts $4.30M) | CoinGlass via KuCoin, 2026-08-05 |
| Market-wide 24h liquidations | ~$78.70M, 55,823 traders | CoinGlass, 2026-08-05 |
| Exchange reserves | **~14.5–14.9M ETH — lowest ever recorded** (down from >33M in 2021); >6M ETH withdrawn since late 2023 | CryptoQuant via Phemex / CryptoTimes / KuCoin |
| Staking ratio | **32.4% of supply — record**; ~38.9M ETH staked across ~897,000 validators | CCN / KuCoin / AMBCrypto |
| Validator entry queue | **~3.5M ETH backlog, ~62-day wait** (up from ~3M on 2026-08-03) | CCN / KuCoin |
| Validator exit queue | **ZERO** | CCN / KuCoin |
| Native staking APR | 2.78% | KuCoin |

**Capitulation-(b) is cleanly zero, not reverting.** The Jul-23/24 four-interval negative-funding episode — flagged on 2026-07-25 as *fired but not standing* — has not recurred, and the isolated negatives noted on 2026-08-01 have rolled out of the 45-interval window entirely.

### 2.6 Spot ETF flows

| Window | Net flow | Source |
|---|---|---|
| 2026-08-04 (Tue) | **+$53.1M** (ETHA +$42.5M, FETH +$9.3M) — rebound after a one-day outflow | Farside via cryptonews.net / bitcoinworld |
| Week to 2026-07-31 | +$27.42M — **fourth consecutive weekly inflow week** | Farside via news.bitcoin.com |
| July 2026 total | **+$365.2M** | Farside |
| YTD 2026 | ~−$1.1B net | Farside |

**Conflicting print disclosed rather than suppressed:** one aggregator (KuCoin flash) reported a **−6,558 ETH / −$12.27M outflow** for 2026-08-04 against Farside's +$53.1M. The discrepancy most likely reflects scope (US spot ETFs vs a broader global/category basket). Per the single-source streak-completion rule this does **not** mark the streak PROVISIONAL — the four-week inflow regime is corroborated by two independent framings (weekly nets and the July monthly total) and by this series' own prior prints, so it does not rest on one streak-completing datum. But the discrepancy is stated.

**Direction of travel for scoring:** gate 4 and capitulation-(c) both require **outflows**. Every piece of this evidence moves them *further* from lighting.

### 2.7 Macro & equities

| Asset | Level | Δ 5 sessions | Source |
|---|---|---|---|
| S&P 500 | **7,785.30** (record) | **+6.41%** | Yahoo ^GSPC, 2026-08-05 |
| Nasdaq Composite | 26,684.81 | +9.17% | Yahoo ^IXIC, 2026-08-05 |
| VIX | 16.95 | **−17.96%** | Yahoo ^VIX, 2026-08-05 |
| Brent crude | **$79.48** | **−12.41%** | Yahoo BZ=F, 2026-08-05 |
| Gold (COMEX front) | $4,260.60 | +5.61% | Yahoo GC=F, 2026-08-05 |
| DXY | 99.73 | −1.06% | Yahoo DX-Y.NYB, 2026-08-05 |
| US 10y nominal | 4.64% | +0.32% | Yahoo ^TNX, 2026-08-05 |
| 10y TIPS real yield | 2.43% | −0.01pp | FRED DFII10, 2026-08-03 |
| 3m T-bill (dry-powder benchmark) | **3.73%** | +1.97% | Yahoo ^IRX, 2026-08-05 (FRED DGS3MO cross-check 3.91%) |
| Fed funds target | 3.50–3.75%, held 9–3 on 2026-07-29, three hawkish dissents | — | CME / CNBC |
| September FOMC (2026-09-16) hike odds | **~59–63%** | CME FedWatch via Reuters/Bloomberg coverage, 2026-08-05 | |
| CLARITY Act 2026 passage odds | **~28–37%**, hard **2026-08-10** Senate cliff | Polymarket via Yahoo Finance / cryptonews.com | |

**Context Panel** (disclosed context only — never a scored leg, gate, threshold, size, stop or cap):

| Metric | Value | Percentile vs 2y |
|---|---|---|
| Realized vol 30d | 40.87% | **4.07** |
| Realized vol 10d / 90d | 34.05% / 49.53% | — |
| Drawdown vs 2y high | 61.15% | 91.93 |
| Distance to 200dma | −9.46% | 62.50 |
| Weekly RSI-14 | — | 29.23 |
| Volume (last session) | $8.18B | **5.07** |
| Deribit DVOL | 48.24 | — |
| Perp basis | −0.04% (annualized carry +3.22%) | — |
| Binance long/short account ratio | 2.296, falling | 75.86 |
| Binance taker buy/sell | 1.0698, rising | 86.21 |
| Binance open interest | rising | 75.86 |
| Net liquidity (FRED, weekly) | $5.83T | as of 2026-07-29 |
| HY OAS | 2.78% | FRED, 2026-08-03 |
| NFCI | −0.529 | FRED, 2026-07-31 |
| Stablecoin aggregate supply | $183.10B, −0.59% 30d, **−3.43% 90d** | DefiLlama |

Two panel readings deserve a sentence. Realized 30-day vol sits at the **4th percentile of two years** against a drawdown at the **92nd** — ETH is even more deeply repriced and even quieter than BTC. And session volume at the **5th percentile** says the bounce is being carried on thin participation, which is a fact about the rally, not about the value.

### 2.8 Correlation regime

| Metric | Value |
|---|---|
| 30d Pearson correlation vs SPX | **0.267** |
| Window | 2026-06-24 → 2026-08-05 (30 overlapping daily log-return pairs) |
| Method | Pearson on daily log returns, Yahoo ETH-USD vs ^GSPC closes, computed 2026-08-05 |
| Regime label | **mild** |
| Risk-on surcharge (>0.7) | **OFF** — not applied |
| Phase-2 corr condition (<0.8) | **PASS** on a computed number |

Surcharge off ⇒ the D2 conviction path is **not** barred on correlation grounds. It is available; it is declined on other grounds (§9.4).

### 2.9 Companion Flying Rocket score (Hard Rule 5, computed — not estimated)

`compute.mjs fr-companion`, same live data fetch:

- **Routing:** 62.10% below the 1-year high with a **falling** 200dma (−5.75%/20 sessions) → **Channel B — Bear Continuation**. Channel A's phase-of-cycle cap does **not** bind.
- **Channel B legs:** rally extension **3** (bounce +21.23% off the 40-session low — the >18 band) · local momentum 2 (daily RSI 52.45; weekly RSI 41.58 < 50, so the qualifier does not void the leg) · resistance confluence 1 · bear structure 2 · relative sentiment 1.
- **Penalty:** 0 (squeeze tier none; bounce 38 sessions old, no maturity penalty). **Confidence: full.**
- **FR composite: 9 / 20.** Channel B Phase 1A line is 13 — short by four.

**Cross-validation: consistent.** FK 11 (mechanical) / FR 9, both below 12, so Hard Rule 5's both-≥12 condition is **not** met. The label is **unqualified** — the Channel A cap is not binding, so the check is genuinely falsifiable.

**Disclosed honestly, as on 2026-08-01 and 2026-08-03:** 11 and 9 are **not strongly inverse**, and the gap has narrowed again. Per the FR skill's own Channel B note this is expected rather than anomalous — the two frameworks score different objects on different horizons. FK measures accumulation value; FR-B measures whether a specific counter-trend bounce is dying into resistance. **ETH's rally-extension leg scores 3 precisely because the +21.23% bounce that helps FK's structural read is what FR-B measures as extension.** That is not a contradiction; it is the same fact seen from two sides, and it is the second factor behind this report's D1 term.

**STANDALONE FR REPORT: OWED, RE-FIRED, AND STILL UNDISCHARGED.**
- The companion prints **9**, firing vacuity trigger (ii) — companion ≥9 — for the **second time**.
- The report owed since the 2026-08-01 ETH companion printed 9 has **never been run**. The newest ETH FR report on disk is `eth_flying_rocket_20260731_0426.md`, which **predates** that trigger.
- **Action: run `/flying-rocket-analytics eth`.** This is the second-highest-priority open item in this series after the ledger refresh.

---

## 3. Critical Developments

- **CLARITY Act is on a five-day clock, and this is the ETH-weighted catalyst.** 2026-08-10 begins the Senate state work period — the final date to vote the bill into law before recess; failure defers it to mid-September. The bill still requires floor debate, an amendment process, and a 60-vote cloture threshold, a sequence that cannot realistically be compressed. The Senate's pre-recess schedule has been consumed by a Russia sanctions package and a nominations backlog. Polymarket odds ~28–37%, down from an 82% February peak. Sources: Yahoo Finance, cryptonews.com, Bitcoin Foundation, CoinDesk 2026-07-09, Coinage.
- **Equities at a record; the Strait of Hormuz is still closed.** SPX +6.41% over five sessions to 7,785.30, its longest winning streak since early June, on ~90% earnings beats (~27% y/y Q2 growth) and optimism about a Hormuz reopening deal. But the Strait remains effectively closed — ~2 transits on 2026-08-02 against ~73/day normal, convoys under naval escort. The rally trades the *deal*, not the reopening. Sources: CNBC 2026-08-04/05, CNN, Axios, Washington Times, Al Jazeera.
- **ETH supply lock-up deepened again.** Validator entry backlog grew to ~3.5M ETH with a ~62-day wait (from ~3M two days ago); exit queue **zero**; staking 32.4% of supply; exchange reserves at ~14.5–14.9M ETH, the lowest ever recorded. Sources: CCN, KuCoin, Phemex, CryptoTimes.
- **Coldcard exploit escalated to ~$130M — and implicates no ETH keys.** Five waves, 5,200+ addresses, a dozen attacker groups, Coinkite halting shipments and destroying inventory. Coldcard is a **Bitcoin-only** device; ETH's holder leg (staking + exchange withdrawal) is unimpaired. Recorded here because it is the basis of the −1.0 D1 taken in the BTC companion report, and its **absence** from ETH's risk set is a material part of why ETH's term is smaller. Sources: TechCrunch 2026-08-04, CoinDesk, Bloomberg, Fortune.

---

## 4. Fallen Knives Composite Score — ETH

| Category | Max | Input (sourced) | Band logic | Score |
|---|---|---|---|---|
| **Sentiment Extreme** | 5 | F&G 3-day avg **26.67** (Alternative.me, pinned) | >25 → ≤35 band | **2** |
| **Momentum Exhaustion** | 4 | Weekly Wilder RSI-14 **41.96** (261 completed closes through week ending 2026-08-02) | >40, ≤45 band | **1** |
| **Valuation** | 5 | MVRV-Z **≈ −0.93** (Santiment anchor −1.1144 @ 2026-07-06, scaled; sign arithmetically forced) | <0.1 band | **5** |
| **Capitulation Evidence** | 3 | (a) liquidations $7.27M — nowhere near top-decile/3σ ❌ · (b) 0 negative funding intervals in 45 ❌ · (c) ETF flows +$365.2M in July, 4th weekly inflow week ❌ | 0 of 3 | **0** |
| **Holder Behavior** | 3 | (a) staking 32.4% record, exit queue zero, entry backlog ~3.5M ETH ✅ · (b) exchange reserves at the lowest ever recorded ✅ | both | **3** |
| **Leg sum** | | | | **11** |

- **Leg sum: 11**
- **Mechanical score: 11** — `round(Σ legs)`, no discretionary term. *Read by every protective rule.*
- **D1 discretionary term: −0.5** (see §9)
- **Raw composite: 10.5**
- **[V]-gate surcharge: none** (corr 0.267 < 0.7)
- **Adjusted score: 10** — `round(10.5)`, ETH convention half-**down** (estimate-heavy input set → conservative on a buy signal). *Read by deployment/unlock rules only.*

No leg moved from 2026-08-03.

### Confirmation Gates — 2 of 8 (gate 5 N/A for PoS, denominator reduced)

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | Sentiment ≤15 for ≥7 consecutive daily prints | ❌ | Streak = 0; last 10 prints 25–30, lowest 25. Needs a ~40% collapse in the index — a genuine panic leg, not drift. **Reachable on a shock**; this gate lit at F&G 9 in June 2026. |
| 2 | [V] | Weekly RSI <30 | ❌ | 41.96. Needs a sustained multi-week decline — roughly a break below the $1,548.76 campaign low held for several weeks. **Reachable on a real leg down.** |
| 3 | [V] | Valuation cheap (MVRV-Z <1) | ✅ | Lit at ≈ −0.93 and not close to darkening — spot would need +21.27% just to reach MVRV-Z 0, and far more to reach 1. |
| 4 | [V] | ETF outflows ≥2% of AUM trailing month | ❌ | Needs sustained monthly **outflows** against a July of **+$365.2M** and a fourth consecutive weekly inflow week. **Reachable, but moving away at speed** — this is the [V] gate most likely to light on a risk-off turn and currently the furthest from doing so. |
| 5 | [T] | Hash Ribbon | **N/A** | Structurally inapplicable to a PoS asset — denominator reduced to 8, never scored ❌. |
| 6 | [T] | Price within ±8% of the 200-week MA | ❌ | 200-week SMA **$2,484.53**; spot is **24.43% below** it. **"none-in-regime"** — requires a ~+18% rally just to reach the band's lower edge (~$2,285.77). ETH fails this gate from *cheapness*, the mirror image of gold's failure from expensiveness. |
| 7 | [V] | Capitulation volume spike (top-decile trailing-90d liquidations or >3σ vs trailing-30d mean) | ❌ | $7.27M ETH / $78.70M market-wide, on session volume at the **5th percentile** of two years. Needs an order-of-magnitude flush. **Reachable on a disorderly break of $1,548.76.** |
| 8 | [V] | LTH accumulation / holder concentration stabilizing | ✅ | Lit — record staking share, zero exit queue, reserves at an all-time low. Would darken on a sustained reserve rebuild or a exit-queue reopening. |
| 9 | [T] | Macro catalyst neutral-to-positive | ⚠️ | Mixed, upgraded from ❌. Pro: Brent −12.41%, VIX −17.96%, HY OAS 2.78%, equities at a record, real Hormuz de-escalation progress. Con: Strait still closed, ~59–63% September hike priced, and **CLARITY on a five-day cliff at 28–37% — the con that weighs most on ETH specifically**. ⚠️ does not count. |

**Passed: 3, 8 → 2 of 8. [V] count: 2** (gates 3, 8).

`compute.mjs thresholds 8` → 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4). Note the /8 board reproduces the /9 numbers exactly — the N/A reduction lowers no requirement until the denominator falls to ≤7. (`ceil(7/9 × 8) = 7`, not 6 — the value ETH misprinted in three June-2026 reports.)

Gate 6 carries the "none-in-regime" tag; it is neither ⚠️ nor was it within its trigger band in the trailing window, so the tag is properly applied. It is not cited anywhere below to lower a threshold, reduce the denominator, or credit a gate. The default conclusion stands: dark gates are correctly dark.

**The diagnosis, unchanged and now four reports old: cheap, but not feared.** Both lit gates are *value* gates. Every gate that measures *fear* — 1, 2, 4, 7 — is dark, and gate 4 is receding on a fourth consecutive weekly inflow week. **The binding constraint sits on the gate axis, not the score axis**, and no amount of further cheapness fixes it.

---

## 5. Probability Matrix — Score-Anchored, Analyst-Set (D4)

**Trend residual — stated as a boolean regardless of how the cells were set:**
> **Active downtrend (below a major MA AND making lower lows): NO.**
> Price is 24.43% below the 200-week mean and 9.46% below a falling 200dma (−5.75%/20 sessions) — the MA half is satisfied twice over. The lower-lows half is not: the 40-session low **$1,548.76 (2026-06-26)** has held **38 sessions**, price has rallied **+21.23%** off it, and spot sits **+5.09% above the 50dma** in a higher-low sequence.
> **Consequence:** no bearish residual applied. The Deep-Value Override's **quarter-size throttle is OFF** (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15.

**D4 taken.** Adjusted score 10 → baseline anchor row 6–10 (Rally 20 / Range 35 / Retest 30 / Bear 15). Cells set from the read: Rally **+7**, Range **+2**, Retest **−7**, Bear **−2**. All inside the ±10 percentage-point band; no cell requires a >10pp reason line. The deviations reflect a structurally intact 38-session base with a deep valuation floor and an eased risk backdrop, against a regulatory cliff and a bounce whose participation is thin.

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | 27% | $1,950 – $2,150 | $2,050 | CLARITY clears the Senate before recess; soft NFP 2026-08-07 → hike odds below 40%; the 200dma at $2,073.62 comes into play |
| **Range** | 37% | $1,800 – $1,950 | $1,875 | Status quo — the base holds, realized vol stays at the 4th percentile, no macro or regulatory resolution before CPI |
| **Retest** | 23% | $1,650 – $1,800 | $1,725 | CLARITY fails before 2026-08-10; hot NFP; the 50dma at $1,786.58 gives way and the bounce unwinds |
| **Bear** | 13% | $1,450 – $1,650 | $1,550 | A fresh 40-session low below $1,548.76 — the counter-trend-rally read resolves down; broad risk-off unwind |

**Sum: 27 + 37 + 23 + 13 = 100% ✅**

**Weighted EV recomputation from the printed cells (final step, cells → EV, never the reverse):**
> 0.27 × 2,050 = 553.50
> 0.37 × 1,875 = 693.75
> 0.23 × 1,725 = 396.75
> 0.13 × 1,550 = 201.50
> **Σ = 1,845.50**

**Stated EV = $1,845.50. EV-vs-spot = −1.71%.** (`compute.mjs ev` returns 1845.5 / −1.71%; stated matches recomputed exactly.)

**Realized trailing 2-week price change: −2.86%** (vs 2026-07-22 close $1,933.49, Yahoo). EV and realized momentum point the same direction.

**Rally cap check:** 27% ≤ 50% ✅, not the modal cell.

**EV-floor consistency check:** requires EV negative **AND mechanical score ≥15 AND** 3-day F&G ≤15. Mechanical **11**, F&G **26.67**. **Not triggered.**

**Terminal-vs-extreme reconciliation:** not required — the §5 trend residual is not live.

---

## 6. Deployment Strategy — ETH

**Splits: 10 / 15 / 30 / 45.**

### 6.1 Position & Performance (Hard Rule 8)

`node tools/position.mjs eth` → **exit 1, band EXPIRED.**

| Field | Value |
|---|---|
| File | `~/.trading-claude/exchange/position-snapshot.json` |
| Band | **EXPIRED** |
| Age | **5,654 minutes (94.2 hours)** — past the 4,320-minute (72h) expiry |
| Age driver | `holdings_as_of` (5,654 min); `generated_at` 5,652 min |
| Instruction returned | *"Cold start per Hard Rule 4, stated explicitly. The ledger is too old to be the position of record."* |

**Stated explicitly: no fresh ledger was available, and this report proceeds as a cold start under Hard Rule 4.** The snapshot degraded from STALE (50.2h) on 2026-08-03 to EXPIRED (94.2h) today.

**Position Reconciliation.** Prior reports narrate *"~5% Phase 1A filled at ~$1,844 plus ~5% laddered $1,800–1,825 working."* The last readable (STALE) snapshot on 2026-08-03 showed **0.00006517 ETH** — dust — with `basis.reliable = false` on **8.50642325 ETH across 24 unbacked disposals** (the account's largest gap by a wide margin; BTC's is 0.0336), custody **RECONCILED** with zero withdrawals, `short_qty` explicitly null, and **zero deal tags** on one open deal. The ledger could neither confirm nor refute the narration then and cannot be consulted now.

Applied strictly:
- The narrated Phase 1A fills remain **UNVERIFIED**, in both directions — not confirmed, not refuted, and explicitly not read as flat.
- **No average cost, cost basis, unrealized PnL or ROI is quoted.** The last reading of `basis.reliable` was false; 24 unbacked disposals is not a rounding error.
- **No realized-performance column is filled.** The last snapshot's `performance_by_tag` was an **empty array** — zero tagged deals exist, so nothing is asserted about how ETH Phase 1A entries have actually performed, and nothing is.
- **Real dry powder is unknown.** Last readable: $14,408.87 stablecoins against a $19,790.26 portfolio — a **shared pool** with the BTC and gold reports published at this timestamp. Two reports each sizing "10% of the book" against it would double-commit the same dollars. No tranche is sized against it.
- **Dry-powder yield benchmark:** 3.73% (^IRX, 2026-08-05).

### 6.2 Phase status

| Phase | Size | Zone | Score condition | Gate condition | Status |
|---|---|---|---|---|---|
| **1A** | 10% | **$1,800 – $1,880** | adjusted 10 ≥ 8 ✅ | **2/8 < 3 ❌**, [V] 2 ≥ 2 ✅ | **GATE-BLOCKED** — short by exactly one gate ⇒ D2 available, declined |
| **1B** | 15% | $1,600 – $1,750 | adjusted 10 < 11 ❌ | 2/8 < 5 ❌, [V] 2 < 3 ❌ | **DOUBLE-BLOCKED** |
| **2** | 30% | $1,450 – $1,600 | adjusted 10 < 15 ❌ | 2/8 < 6 ❌ | **FROZEN** |
| **3** | 45% | requires a weekly capitulation candle | mechanical 11 < 17 ❌ | 2/8 < 7 ❌ | **DRY** |

Spot **$1,877.535** sits inside the Phase 1A zone, near its top. **No new Phase 1A authorization** is available — the gate count is 2 of a required 3.

**What is *not* blocked:** under the partial-tranche rule, an unlocked phase authorizes *up to* its nominal size, and a pre-assigned remainder may be worked in the same zone **without a fresh unlock**. Prior reports assigned roughly half of Phase 1A and left the remainder working the **$1,800–1,825** ladder. That remainder is the only capital movement this report contemplates — and it is contingent on the ledger, because with an EXPIRED snapshot and `basis.reliable = false` there is no way to establish how much of 1A is actually assigned. **In practice: no fill this report.**

**Phase 1B: the score line closed, and I want to be precise about what did it.** Mechanical 11 clears ≥11 — the crossing this series achieved for the first time on 2026-08-03 and holds today on the legs. The **D1 −0.5 removes it** (adjusted 10 < 11). But 1B is *independently* short **three gates** (2 of 5 required) and **one [V] gate** (2 of 3). **The discretionary term has zero capital effect.** Said plainly so the term is graded on its merits rather than credited with restraint the gates already supplied.

**Deep-Value Override: DOES NOT FIRE.** Mechanical 11 < 15 is dispositive; 3-day F&G 26.67 is not ≤15; and the Override presupposes a corroborated deployed tranche an EXPIRED ledger cannot supply. No near-fire to log.

**Ledger tags if a fill occurs:** `FK-P1A` for the pre-assigned remainder. A D2 unlock would carry `FK-D2` and a D5 stop — neither exists.

**Non-mechanical capital: 0% of book.** No D1 cross, no D2 path, no Override firing has ever executed on ETH. The 40% cap and 25% Override sub-cap are untouched.

### 6.3 Stops

**No stop parameter changed value this report.**

| Tier | Level | Condition |
|---|---|---|
| **Catastrophic floor** | **$1,300** | Strictly below the deepest defined buy zone |
| **Compound thesis stop** | **$1,350** price line + **mechanical** score line **<12** | ≥2 consecutive weekly closes below $1,350 **AND** mechanical score <12 |
| **Time stop / checkpoint** | 2026-08-09 weekly close | See below |
| **D5 discretionary stops** | **none** | Zero analyst-channel tranches; the available D2 unlock was declined, so no D5 stop attaches |

**Score axis status:** mechanical **11 < 12**, so the score axis **is satisfied** — the compound stop is effectively **price-gated at $1,350** until the composite re-crosses 12. **The D1 −0.5 has zero effect on this line:** the compound stop reads the **mechanical** score per the 2026-07-27 governing rule. Checked explicitly, not assumed — this is the single most important application of "D1 buys entries, never exits," and it works symmetrically: a negative term cannot make the book's stop fire more readily than the evidence warrants, exactly as a positive one could not suppress it.

**Carried forward and still true: ETH is ONE point from restoring the second key.** A weekly RSI print below **40.0** would take the momentum leg 1 → 2, the composite 11 → 12, and the score axis from satisfied to unsatisfied — restoring full two-key protection. The current print is **41.96**, one band edge away. That is the cheapest available upgrade to this book's stop quality, and it arrives on *weakness*, which is worth noticing: the stop gets better precisely when the accumulation case gets stronger.

**Stop-vs-buy-zone coherence check (mandatory):**
> Deepest buy-zone floor named anywhere in this report: **$1,450** (Phase 2). Catastrophic stop **$1,300**.
> **CATASTROPHIC stop $1,300 strictly below deepest active buy-zone floor $1,450? → PASS.**
> (`compute.mjs stop-coherence --catastrophic 1300 --floor 1450` → `pass: true`.)
> The compound line ($1,350) is not the tested number. No D5 lines exist to print separately.

**Max drawdown spot-to-compound-line: −28.09%** ($1,877.535 → $1,350). Disclosed; purchases no loosening under D6.

**D6 ratchet: compliant.** No parameter moved in either direction.

**Stop Migration Ledger: EMPTY this report.** The 2026-08-09 checkpoint set on 2026-08-03 has not resolved, so no forward roll applies.

**Checkpoint prognosis (calendar-locked):**
> **Checkpoint 2026-08-09** — a Sunday, a valid weekly-close boundary for a 24/7 venue; no restatement applied; date computed and validated before any distance language.
> **Fires iff** ≥2 consecutive weekly closes below $1,350 **AND** mechanical score <12. Current consecutive closes below the line: **0**. The 2026-08-03 weekly close printed $1,868.39.
> Spot is **39.08% above** the line — **13.13× the 5-day ADR** of **$55.94** (mean |high−low| over 2026-07-31, 08-01, 08-02, 08-03, 08-04; the in-progress 2026-08-05 session **EXCLUDED** as not a full session, disclosed inline, lookback extended one session to reach five full ones).
> **It structurally cannot fire on 2026-08-09** — zero of the two required closes exist, and one weekly close cannot supply two.
> **Tier-1 release before the checkpoint: YES — Nonfarm Payrolls, Friday 2026-08-07, 08:30 ET.** Direction of effect: a hot print raises September hike odds above the current ~59–63% and pressures ETH toward the 50dma at $1,786.58; a soft print cuts them and puts the 200dma at $2,073.62 in play. NFP cannot produce two sub-$1,350 weekly closes by 2026-08-09. Next tier-1 after: CPI, Wednesday 2026-08-12, 08:30 ET. **Non-macro dated binary inside the same window: the CLARITY Act's 2026-08-10 Senate cliff** — named here because it is the more ETH-relevant of the two.

---

## 7. Exit / Trim Framework — ETH

**Every score condition reads the MECHANICAL score (11), never the adjusted 10.**

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score ≥6 points below local peak | Local peak this campaign: **13** (2026-07-11/12) | 11 → drop of **2** | ❌ not triggered |
| F&G ≥75 sustained 7d **AND** weekly RSI >70 | — | 26.67 / 41.96 | ❌ |
| MVRV-Z >3, or drawdown <10% with a vertical 30d return | — | ≈ −0.93 / −62.04% | ❌ |
| Mechanical score ≤3 **AND** price ≥40% above blended cost | — | 11; no verifiable cost basis | ❌ |
| ETF outflows ≥3% AUM after a sustained inflow regime | — | +$365.2M July, 4th inflow week | ❌ |
| **Narrative break** | — | none. Coldcard is Bitcoin-only and implicates **no ETH keys** | ❌ |

**Current exit status: NONE.** No trim executed, no exit triggered. Remaining position: unverifiable (§6.1), so no position size is asserted in either direction.

---

## 8. Critical Watchlist

| Date / Time (ET) | Event | ETH impact |
|---|---|---|
| **Fri 2026-08-07, 08:30** | **Nonfarm Payrolls (July Employment Situation)** — the only tier-1 US release in the next 5 trading sessions | **HIGH.** Hot → hike odds above ~63%, pressure toward the 50dma $1,786.58. Soft → 200dma $2,073.62 in play. Named in the checkpoint falsifier. |
| **Mon 2026-08-10** | **CLARITY Act — Senate state work period begins.** Final vote date before recess; failure defers to mid-September | **HIGHEST ETH-SPECIFIC.** 28–37% odds. Market-structure classification bears more on ETH and the alt complex than on BTC. This is factor (i) of the D1. |
| Ongoing | Hormuz reopening agreement — signature and first convoy volumes | HIGH. Drives Brent, inflation expectations, the Fed path. Strait still closed. |
| Ongoing | Validator entry queue (~3.5M ETH, ~62-day wait) and exit queue (zero) | MEDIUM. The holder leg's engine; a reopening exit queue would be the first crack. |
| Daily | Spot ETH ETF flows (Farside) | HIGH. Gate 4 is receding on a fourth consecutive inflow week. Watch for the KuCoin/Farside methodology gap to resolve. |
| **Wed 2026-08-12, 08:30** | **CPI (July)** | HIGH. Brent at −12.41% should begin showing in headline. |
| Thu 2026-08-13 / Fri 2026-08-14 | PPI / Retail Sales | MEDIUM |
| **Wed 2026-09-16** | **FOMC decision** | HIGH. ~59–63% hike priced. |
| **Owed now** | **Standalone Flying Rocket report for ETH** — companion printed 9 on both 2026-08-01 and today | Process item, second-highest priority after the ledger refresh. |

**Tier-1 calendar verification:** `compute.mjs tier1 --from 2026-08-05 --sessions 5` → window 2026-08-06 … 2026-08-12, exactly one tier-1 event (NFP 2026-08-07), **zero warnings**. **Not an incomplete-data report on the calendar dimension.**

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

ETH is the cheapest asset in this book and the least frightened market in it, and after four reports of saying that, the interesting question is no longer *whether* that is true but *what it means*.

The value case is not marginal. MVRV-Z near −0.93 puts the median holder roughly 18% underwater against aggregate cost basis — the deepest reading since December 2018. Spot would have to rise **21.27%** just to reach MVRV-Z zero. Exchange reserves are at the lowest level ever recorded. Staking is at a record 32.4% with a 3.5M-ETH entry backlog, a 62-day wait, and an exit queue of exactly zero. Structurally, the float is being removed.

And yet: F&G has printed 25–30 for ten sessions. Funding is positive at the 62nd percentile with zero negative intervals in 45. Liquidations are $7.3M. Session volume sits at the **5th percentile of two years** and realized 30-day vol at the **4th**. Nobody is selling ETH in a panic. Nobody is buying it with conviction either. The +21.23% bounce off the June low has been carried on some of the thinnest participation in two years.

That last observation is the one I want to press on, because it is where the two frameworks in this workspace disagree productively. Fallen Knives looks at a 38-session base with a higher-low sequence above a rising floor and sees structure that supports accumulation. The computed Flying Rocket Channel-B companion looks at the *same* bounce and scores its rally-extension leg **3 of 5** — a counter-trend rally in a bear-continuation regime, 38 sessions old, into a falling 200dma. **Both readings are correct.** The composite is a coincident fear gauge; it cannot see that its own supportive structure is, from a different angle, an aging rally on thin volume. That is not a flaw to be argued away. It is precisely the sort of thing the discretionary layer exists to price.

Add the calendar. The CLARITY Act — market structure, token classification, the thing that determines whether the ETH ecosystem's applications have a legal home in the United States — needs Senate floor time, an amendment process, and 60 votes before **2026-08-10**, and the market prices that at 28–37%, down from 82% in February. That is a five-day binary with asymmetric ETH consequences, and gate 9 is a single boolean that cannot express it.

So: cheap, structurally tightening, unfeared, on thin participation, into a regulatory cliff. That combination is a **hold with a hand on the ladder**, not a deployment. The gates agree; they have been agreeing for four reports.

### 9.2 What the rubric structurally cannot see

1. **Bounce maturity and extension.** No FK leg scores how old a rally is, how far it has run off its low, or how thin its volume is. FK's structural read (higher lows, base holding) and FR-B's extension read (+21.23%, 38 sessions, into a falling 200dma) are the same fact from two sides. **Bearish for accumulation timing.**
2. **Dated regulatory binaries with asymmetric asset weight.** Gate 9 cannot distinguish "no catalyst" from "a five-day cliff at 28–37% odds that matters more to this asset than to its peer." **Bearish.**
3. **The 200-week distance asymmetry.** Gate 6 is a boolean: ETH is 24.43% below its 200-week mean, BTC is 0.99% above. The boolean discards a 25-point spread that says something real about where each asset sits in its own cycle. **Ambiguous** — it is simultaneously ETH's deepest value argument and the reason its trend structure is worse.
4. **Participation quality.** Session volume at the 5th percentile and realized vol at the 4th are disclosed context, not scored. A rally nobody is trading is different from a rally being bought. **Mildly bearish.**
5. **Supply-lock acceleration.** The entry backlog grew from ~3M to ~3.5M ETH in two days. The holder leg already scores 3/3 and cannot register the *rate*. **Bullish — and explicitly not taken**, see below.

### 9.3 The D1 term — **−0.5** (negative), and it reverses a decline made two days ago

**Value: −0.5. Direction: negative. First non-zero D1 in the ETH series.**

**This reverses the 2026-08-03 decision to decline a −0.5, and I am flagging the reversal at the top rather than burying it in a rationale.** That report declined on two grounds: (a) the −0.5 would erase, under half-down rounding, a Phase 1B score-line crossing that the legs had just earned on a sourced decimal; and (b) the macro argument was shared with BTC, which was already pricing it at −1.0, so counting it twice would double-count one macro across two reports.

**Ground (b) is respected in full and is why this term is built differently.** Neither factor below is the shared macro. The September hike path, the oil collapse, the equity melt-up — none of it appears here. BTC prices those; ETH does not price them again.

**Ground (a) is overridden on fresh evidence, and here is the honest accounting.** The objection was that discretion should not erase a mechanical improvement the data genuinely produced. But the improvement was a *valuation* reading, and the factors below are *regulatory* and *structural* — different objects, not a re-litigation of the same one. And the crossing it erases is **inert**: Phase 1B is short three gates and one [V] gate, so nothing is lost but a number. Erasing an inert crossing to state an honest directional view costs nothing and is more useful to a future calibration than preserving a milestone that authorizes no capital.

**Factor (i) — CLARITY became dated.** On 2026-08-03 this was a diffuse "27% passage odds for 2026." Today it is a **hard 2026-08-10 Senate deadline** — the state work period begins, the bill needs floor debate, amendments and 60-vote cloture before then, the pre-recess calendar is consumed by a sanctions package and a nominations backlog, and failure defers everything to mid-September. Odds 28–37% (Polymarket via Yahoo Finance / cryptonews.com, 2026-08-05). **A five-day binary is a categorically different object from a six-month probability**, and it is ETH-weighted: market-structure classification determines the legal footing of the application layer that gives ETH its non-monetary demand. No leg scores regulation; gate 9 is one boolean.

**Factor (ii) — bounce maturity, corroborated by the computed companion.** The rally is **+21.23% off the 40-session low, 38 sessions old**, price sits **+5.09% above the 50dma** and **−9.46% below a 200dma falling at −5.75%/20 sessions**, on session volume at the **5th percentile of two years**. The computed FR Channel-B companion scores its rally-extension leg **3/5** and prints **9/20** overall. No FK leg or gate scores rally extension, bounce age, or participation quality — the board reads this structure as neutral-to-supportive. I read it as a mature counter-trend rally on thin volume, which is a worse place to add than a fresh base.

**Explicitly not double-counting:** the FR companion is cited as *corroboration* of an underlying market fact (extension, age, volume), not as the factor itself. And Hard Rule 5 is untouched — this term pushes FK *down*, so no both-≥12 state is manufactured or dissolved.

**Falsifier (dated):** retire the term when **either** (a) the CLARITY binary resolves — the Senate passes the bill, or the 2026-08-10 deadline passes and odds re-rate above 55% on a credible September path — **or** (b) ETH prints a **fresh 40-session low below $1,548.76**, which would end the counter-trend-rally read entirely and replace it with the deep-fear leg this framework wants to buy. **Hard review date: 2026-08-19.**

**Was a larger negative considered?** Yes, −1.0. **Declined.** The valuation floor is genuinely extraordinary and arithmetically robust — the sign cannot flip without a 21% rally — and the supply lock-up is accelerating, not stalling. A −1.0 would take adjusted to 10 anyway under half-down (10.0 → 10), so it would buy no additional consequence while overstating conviction.

**Was a positive adjustment considered?** Yes, +0.5, on the validator entry backlog growing from ~3M to ~3.5M ETH in two days with the exit queue at zero — a *rate* the holder leg's binary 3/3 cannot register. **Declined as prohibited double-counting:** the holder leg already scores exactly this evidence at maximum, and re-weighting a factor a leg already scores is the one thing D1 explicitly may not do.

**Effect:** removes the Phase 1B score-line crossing (mechanical 11 ≥ 11 → adjusted 10 < 11). **Capital effect: NONE** — 1B is independently short three gates and one [V] gate.

### 9.4 Discretionary actions taken and declined

**D2 conviction path — AVAILABLE on Phase 1A for the THIRD consecutive report, and DECLINED for the third time.** All six conditions are met: adjusted score 10 ≥ 8; gate count short by **exactly one** (2 of 3 required); [V] floor met on lit gates (2 ≥ 2); risk-on surcharge OFF (corr 0.267); phase eligible (1A, not 3); no D5 stop-out within 10 days.

Declined on three grounds, in order of weight:

1. **A non-discretionary path to the same capital already exists.** The partial-tranche rule authorizes the pre-assigned Phase 1A remainder to work the $1,800–1,825 ladder with **no fresh unlock**. A D2 unlock would buy already-authorized dollars at the price of a hard price-only D5 stop and a 10-day analyst-channel bar on the phase. That is a strictly worse trade for the same money.
2. **The gate D2 would substitute for is a *fear* gate** (1 or 7), and ETH's entire diagnosis is "cheap but not feared." Writing a conviction case to supply the exact missing fear evidence *because* it is missing is the pattern the D5 stop exists to punish. It would be arguing my way past the one thing the board is correctly telling me.
3. **A D5 stop line could not be honestly set.** The line is defined relative to **the fill**, and with the snapshot EXPIRED at 94.2h and `basis.reliable = false` on 24 unbacked disposals, no fill price is corroborable. A stop you cannot anchor is not a stop.

**Logged explicitly for grading:** this channel has now been available and unused on three consecutive reports. If a channel is repeatedly available and never used, a calibration should say so — either the conditions are mis-specified, or the analyst is using the framework's own alternatives correctly. I believe it is the latter, but that is exactly the claim a calibration should test rather than accept.

**Deep-Value Override — evaluated, does not fire.** Mechanical 11 < 15 dispositive; F&G 26.67 not ≤15; no corroborated deployed tranche. No near-fire.

**D4 — taken.** Cells set near the 6–10 anchor row, all deviations inside ±10pp, EV recomputed from the printed cells as the final step.

**Declined action of note:** working the pre-assigned Phase 1A remainder in the $1,800–1,825 ladder. Declined not on market grounds but because an EXPIRED ledger with an unreliable basis cannot establish how much of 1A is assigned, and filling into that would risk prohibited upsizing beyond nominal.

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 (ETH) | Declined +1.0 that would have lifted ETH to 11 on discretion alone | — | — | ETH reaches 11 mechanically | **RETIRED — VINDICATED** (2026-08-03: reached 11 on a sourced decimal) | n/a |
| 2026-08-01 | D2 (ETH 1A) | Available, declined | half nominal | — | — | RETIRED — declined | n/a |
| 2026-08-03 | D1 (ETH) | Declined −0.5 (shared macro; would erase an earned crossing) | — | — | — | **RETIRED — REVERSED 2026-08-05**, see below | n/a |
| 2026-08-03 | D1 (ETH) | Declined +0.5 (double-counts holder leg) | — | — | — | RETIRED — declined | n/a |
| 2026-08-03 | D2 (ETH 1A) | Available, declined (2nd consecutive) | half nominal | — | — | RETIRED — declined | n/a |
| **2026-08-05** | **D1 (ETH)** | **−0.5 TAKEN — first non-zero D1 in the ETH series.** (i) CLARITY became a dated 2026-08-10 Senate cliff at 28–37%, ETH-weighted; (ii) bounce maturity — +21.23% / 38 sessions / 5th-percentile volume, FR-B companion 9/20 with rally-extension 3/5. **Explicitly NOT the shared macro BTC prices.** | score-only, no position | none (opened no tranche) | CLARITY resolves (passes, or deadline passes with odds >55%) **OR** a fresh 40-session low below $1,548.76 | **LIVE** — hard review 2026-08-19 | n/a |
| **2026-08-05** | **D1 (ETH)** | **REVERSAL FLAG:** this −0.5 reverses the 2026-08-03 decline of a −0.5. Grounds for reversal: CLARITY moved from diffuse to dated; the FR companion crossed 8 → 9; and the erased 1B crossing is inert (short three gates). Ground (b) of the original decline — shared-macro double-counting — is **respected**, not overridden. | — | — | — | **LOGGED FOR CALIBRATION** — grade whether this reversal was signal or drift | n/a |
| **2026-08-05** | **D1 (ETH)** | Declined **−1.0** | — | — | — | RETIRED — declined (valuation floor arithmetically robust; buys no extra consequence under half-down) | n/a |
| **2026-08-05** | **D1 (ETH)** | Declined **+0.5** on validator-backlog acceleration (3M → 3.5M ETH in two days) | — | — | — | RETIRED — declined (prohibited double-count of the holder leg's 3/3) | n/a |
| **2026-08-05** | **D2 (ETH 1A)** | **Available, declined — THIRD consecutive report** | half nominal | would-be D5 | — | **LIVE as a pattern for calibration** | n/a |

No D1 or D2 tranche has ever been opened on ETH, so no D5 stop exists and no analyst-channel bar is running. Non-mechanical capital: **0%** of book.

### 9.6 What would change my mind

- **Bullish, dated:** a weekly RSI print below **40.0** (currently 41.96, one band edge away). It takes the momentum leg to 2, the composite to 12, and restores the compound stop's second key — the cheapest available upgrade to this book's protection, and it arrives on weakness. Or the CLARITY Act clearing the Senate before 2026-08-10, which retires half the D1 outright.
- **Bearish and *wanted*, dated:** a daily close below **$1,548.76**, the 2026-06-26 campaign low. It would flip the trend residual to an active downtrend, arm the Override's quarter-size throttle, put gates 2 and 7 in play within weeks, and — this is the point — replace a mature counter-trend rally with the deep-fear leg that unlocks the pyramid's large tranches at a genuinely cheap price. ETH at MVRV-Z −0.93 with a panic bid underneath it is the setup this framework was written for. It does not exist yet.
- **Process, not market:** refresh `position-snapshot.json`, and run the **owed standalone Flying Rocket report for ETH** — the companion has printed 9 twice now and the trigger has never been discharged.

---

## 10. Bull vs Bear Scorecard — ETH

**Bull (9):**
1. ✅ MVRV-Z ≈ **−0.93** — the maximum valuation band; median holder ~18% underwater, cheapest since December 2018
2. ✅ Implied realized price **$2,276.85** vs spot $1,877.535 — spot needs **+21.27%** merely to reach MVRV-Z zero
3. ✅ Exchange reserves **~14.5–14.9M ETH, lowest ever recorded**; >6M withdrawn since late 2023
4. ✅ Staking **32.4% of supply — record**; entry backlog ~3.5M ETH / 62-day wait; **exit queue zero**
5. ✅ ETF flows: **fourth consecutive weekly inflow week**, +$53.1M on 2026-08-04, July +$365.2M
6. ✅ Campaign low **$1,548.76** has held **38 sessions**; +21.23% off it in a higher-low sequence
7. ✅ Spot **+5.09% above the 50dma**
8. ✅ Risk backdrop eased hard: VIX −17.96%, Brent −12.41%, HY OAS 2.78%, SPX record
9. ✅ Drawdown −62.04% from ATH — a deep repricing already absorbed

**Bear (11):**
1. ❌ **Every fear gate is dark** (1, 2, 4, 7); both lit gates are value gates
2. ❌ F&G 3-day 26.67; ≤15 streak **zero days**; ten sessions stuck at 25–30
3. ❌ Funding positive (+3.22% annualized), **zero negative intervals in 45**
4. ❌ 24h ETH liquidations **$7.27M** — no flush
5. ❌ Gate 6 fails badly: spot **24.43% below** the 200-week mean, "none-in-regime"
6. ❌ 200dma falling **−5.75%/20 sessions**; price 9.46% beneath it; 50dma below 200dma
7. ❌ **CLARITY Act on a five-day cliff** (2026-08-10) at 28–37% — the most ETH-weighted catalyst on the board
8. ❌ FR Channel-B companion **9/20** with rally-extension **3/5** — the bounce reads as extension from the other side
9. ❌ Session volume at the **5th percentile of two years** — the rally is thinly carried
10. ❌ ~59–63% September hike priced; real 10y yield 2.43%
11. ❌ Realized 2-week change **−2.86%**; gate 4 receding on inflows

**Net: 9 bull / 11 bear — bear by 2.**

---

## 11. Change Log vs 2026-08-03

| Factor | 2026-08-03 | 2026-08-05 | Direction |
|---|---|---|---|
| Spot | $1,868.21 | **$1,877.535** | +0.50% |
| Sentiment leg | 2 (F&G 3d 27.33) | 2 (F&G 3d 26.67) | flat |
| Momentum leg | 1 (wRSI 41.96) | 1 (wRSI 41.96) | flat |
| Valuation leg | 5 (MVRV-Z −0.92) | 5 (MVRV-Z ≈ **−0.93**, anchor advanced to 2026-07-06) | flat |
| Capitulation leg | 0 | 0 | flat |
| Holder leg | 3 | 3 | flat |
| **Mechanical score** | **11** | **11** | **flat** |
| D1 discretionary | 0 (−0.5 considered, declined) | **−0.5 TAKEN — reverses that decline** | **first non-zero D1 in the ETH series** |
| **Adjusted score** | **11** | **10** | **−1** |
| Gates | 2/8 (3, 8), [V] 2 | 2/8 (3, 8), [V] 2 | flat — gate 9 ❌ → ⚠️ (no count change) |
| Phase 1B score line | **MET** (11 ≥ 11), gate-blocked | **UNMET** (adjusted 10 < 11), still gate-blocked by three | closed — capital effect nil |
| Validator entry queue | ~3M ETH | **~3.5M ETH, ~62-day wait** | deeper lock-up |
| ETF flows | 4th weekly inflow week, +$27.42M | **+$53.1M on 2026-08-04**; conflicting −$12.27M print disclosed | inflows continue |
| Weighted EV | $1,847.25 (−1.12%) | **$1,845.50 (−1.71%)** | wider vs a higher spot |
| Realized 2-week | −1.87% | **−2.86%** | more negative |
| Correlation vs SPX | 0.356 | **0.267** | flat, mild |
| **FR companion** | **8** (Channel B) | **9** (Channel B) | **+1 — trigger (ii) re-fires** |
| CLARITY odds | 27% (diffuse annual) | **28–37% with a 2026-08-10 cliff** | **now dated — D1 factor (i)** |
| Position band | STALE (50.2h) | **EXPIRED (94.2h)** | **degraded — now a cold start** |
| Collar | ACTIVE (\|EV\| 1.12%, scorecard 8–8) | **ACTIVE** (\|EV\| 1.71% < 2%) | still binding |

---

## 12. Strategic Verdict — ETH

**Adjusted score 10/20 · Mechanical 11/20 · D1 −0.5 · Weighted EV $1,845.50 · EV-vs-spot −1.71% · F&G 3-day 26.67 (Fear) · Stance: HOLD, authorize nothing, work nothing until the ledger is readable.**

Not one leg moved. The valuation floor remains the most striking number in this workspace: MVRV-Z near −0.93, an implied realized price of $2,276.85 against a spot of $1,877.535, the median holder roughly 18% underwater on aggregate cost basis, and a sign that is arithmetically forced rather than estimated — spot would need to rise 21.27% merely to reach zero. Underneath it, the float is being removed on a schedule: exchange reserves at the lowest level ever recorded, staking at a record 32.4%, an entry backlog that grew from 3M to 3.5M ETH in two days against an exit queue of exactly zero. If the question were *is ETH cheap and structurally tightening*, the answer would be yes, emphatically, and the answer has been yes for four reports.

The question the gates keep asking instead is whether anyone is *frightened*, and the answer keeps coming back no. Fear-and-greed has printed between 25 and 30 for ten consecutive sessions. Funding is positive with zero negative intervals in forty-five. Liquidations are $7.3M. And the numbers I keep returning to are the participation ones: session volume at the **5th percentile of two years**, realized 30-day vol at the **4th**. The +21.23% bounce off the June low has been carried on some of the thinnest trade in two years. That is the fact the composite structurally cannot express — its legs read a 38-session base with higher lows as supportive structure, while the computed Flying Rocket Channel-B companion looks at the identical tape and scores rally extension **3 of 5** on its way to a **9/20**. Both are right. A base and an aging counter-trend rally on thin volume are the same object seen from two angles, and only one of those angles is in the rubric.

That, plus a five-day regulatory cliff, is what the −0.5 prices — and it reverses a decline I made two days ago, which I would rather flag loudly than let pass. The reversal is not a change of mood. CLARITY moved from a diffuse annual probability to a hard 2026-08-10 Senate deadline at 28–37%, with the pre-recess calendar consumed and 60-vote cloture still unstarted; the companion crossed 8 to 9. Both are new. What is *not* new, and what I have deliberately kept out of this term, is the shared macro the BTC report prices at −1.0 — no September-hike argument, no oil, no equity melt-up appears here, because counting one macro twice across two reports is exactly the error the 2026-08-03 decline warned about, and that warning was right. The term closes ETH's Phase 1B score-line crossing, and I will say plainly that this costs nothing: 1B is short three gates and one [V] gate and was never deployable by any route. Discretion should be graded on whether its directional claim was correct, not credited with restraint the gates already supplied.

Two process items outrank every market judgment in this report. The ledger is EXPIRED at 94.2 hours, so this is a cold start under Hard Rule 4 — the narrated Phase 1A fills are unverified in both directions, no cost basis or PnL is quotable against 24 unbacked disposals, and nothing can be sized. And the standalone Flying Rocket report owed since the 2026-08-01 companion printed 9 has still never been run; the companion printed 9 again today. Neither is a market call. Both are cheap. Both have now blocked or shadowed three consecutive reports.

### Action Items

1. **Refresh the position ledger.** Export a new `position-snapshot.json` and re-run `node tools/position.mjs eth`. Until then no fill — not even the pre-assigned Phase 1A remainder — can be sized honestly.
2. **Run the owed standalone Flying Rocket report: `/flying-rocket-analytics eth`.** Trigger (ii) has now fired twice (2026-08-01 and today) and has never been discharged. The newest ETH FR report on disk predates the first trigger.
3. **Fix the ETH basis defect.** 24 unbacked disposals totalling 8.50642325 ETH is the account's largest gap by a wide margin and makes cost basis, unrealized PnL and ROI unquotable. Fix gold's single-disposal defect first as the tractable one, then this.
4. **Authorize no new Phase 1A.** The gate count is 2 of a required 3. On a FRESH ledger, the only permitted action is working the **pre-assigned** 1A remainder in the **$1,800–1,825** ladder under the partial-tranche rule — no fresh unlock, tag `FK-P1A`.
5. **Hold every stop.** Catastrophic $1,300, compound $1,350 + mechanical score <12, checkpoint 2026-08-09. Coherence PASS. Nothing moved.
6. **Watch the weekly RSI for a sub-40.0 print.** It is one band edge away (41.96) and it restores the compound stop's second key by itself — the cheapest protection upgrade available, and it arrives on weakness.
7. **Watch 2026-08-10 (CLARITY) and NFP 2026-08-07.** Both sit inside the D1's falsifier window; neither can fire the checkpoint.

> **The Pattern**
>
> **IF** ETH prints a weekly close with RSI below **40.0** → **THEN** the momentum leg goes 1 → 2, the composite reaches 12, the compound stop's score axis flips from satisfied to unsatisfied, and the book regains full two-key protection — the stop improves precisely as the accumulation case does. Watch for it on weakness, not on strength.
>
> **IF** ETH closes below **$1,548.76** on a daily basis → **THEN** the counter-trend-rally read is over, the trend residual flips to an active downtrend, the Override's quarter-size throttle arms, and gates 2 and 7 come into play within weeks. That is the deep-fear leg this framework's large tranches are written for, at a valuation already the cheapest since 2018. It would be unpleasant and it would be the opportunity.
>
> **IF** the CLARITY Act clears the Senate before **2026-08-10** → **THEN** half the D1 retires on its own falsifier, the most ETH-weighted overhang on the board lifts, and the $2,073.62 200dma becomes a live target — but note what that does to the *accumulation* case: gate 4 recedes further, fear recedes with it, and cheap-and-unfeared becomes cheap-and-liked. Good for the position; worse for the next tranche.

---

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-08-05",
  "spot": { "value": 1877.535, "source": "median of 4 synchronized live quotes: Binance ETHUSDT $1,878.73 / Kraken ETHUSD $1,877.78 / Coinbase ETH-USD $1,877.29 / CoinGecko $1,874.37 (all 2026-08-05 ~14:06 UTC, venue timestamps within ~1 minute); spread 0.233%, all live; Yahoo ETH-USD $1,877.18 EXCLUDED as a frozen bar close. SKILL-mandated median used, NOT the tool's priority-first canonical ($1,874.37) — delta -0.169%, changes no band, gate boolean or cap tier" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 5, "capitulation": 0, "holder": 3 },
    "discretionary": -0.5,
    "mechanical": 11,
    "raw": 10.5,
    "adjusted": 10,
    "rounding": "half-down"
  },
  "gates": { "active": 8, "na": [5], "passed": [3, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 27, "low": 1950, "high": 2150 },
      { "name": "Range", "p": 37, "low": 1800, "high": 1950 },
      { "name": "Retest", "p": 23, "low": 1650, "high": 1800 },
      { "name": "Bear", "p": 13, "low": 1450, "high": 1650 }
    ],
    "stated_ev": 1845.50,
    "vs_spot_pct": -1.71
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "1800-1880 zone, spot 1877.535 INSIDE it near the top. NO NEW AUTHORIZATION — gate count 2/8 < 3 blocks a fresh 1A unlock (short by EXACTLY ONE, so D2 is available and was DECLINED for the third consecutive report). NO entry_price asserted: position.mjs returns EXIT 1 / band EXPIRED at 94.2h so this report is a COLD START under Hard Rule 4, and the narrated '~5% filled at ~1844 plus ~5% laddered 1800-1825 working' is UNVERIFIED in BOTH directions. The pre-assigned remainder needs no fresh unlock under the partial-tranche rule and is the only capital movement contemplated — but it cannot be sized against an EXPIRED ledger with basis.reliable=false, so NO fill this report", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "1600-1750 DOUBLE-BLOCKED. Score: mechanical 11 clears the >=11 line but the D1 -0.5 removes the crossing (adjusted 10 < 11). Gates: 2/8 < 5 and [V] 2 < 3 — short by THREE gates and one [V] gate independently, so the D1 term has ZERO capital effect. D2 unavailable (short by 3, not exactly 1; [V] floor also fails)", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "1450-1600 frozen (adjusted 10<15, gates 2/8<6)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17, gates 2/8<7)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1450,
    "compound": { "price": 1350, "score_line": 12 },
    "note": "NO stop parameter changed value. Mechanical score 11<12, so the compound stop's score axis IS satisfied — stop effectively price-gated at $1,350 until the composite re-crosses 12 (carried state). The D1 -0.5 has ZERO effect on this line: the compound stop reads the MECHANICAL score per the 2026-07-27 governing rule. CHECKED, not assumed — the symmetry works in both directions, a negative term cannot make the book's stop fire more readily than the evidence warrants any more than a positive one could suppress it. CARRIED AND STILL TRUE: ETH is ONE point from restoring the second key — a weekly RSI print below 40.0 (currently 41.96, one band edge away) takes the momentum leg 1->2, the composite 11->12, and the score axis from satisfied to unsatisfied. That is the cheapest available upgrade to this book's stop quality and it arrives on WEAKNESS. Coherence: catastrophic $1,300 strictly below deepest named zone floor $1,450 = PASS (compute.mjs stop-coherence pass:true). No D5 stops — zero analyst-channel tranches; the available D2 unlock was DECLINED so no D5 stop attaches. Max drawdown spot-to-compound-line -28.09%, disclosed; purchases no loosening. D6 ratchet: compliant, no parameter moved in either direction.",
    "migration": [],
    "checkpoint": {
      "date": "2026-08-09",
      "line": 1350,
      "condition": ">=2 consecutive weekly closes <1350 AND mechanical score <12",
      "closes_below": 0,
      "adr": 55.94,
      "adr_sessions": "2026-07-31, 08-01, 08-02, 08-03, 08-04 — the in-progress 2026-08-05 session EXCLUDED as not a full session, lookback extended one session to reach five full ones, exclusion disclosed inline",
      "dist_x_adr": 13.13,
      "calendar_validation": "2026-08-09 is a Sunday, a valid weekly-close boundary for a 24/7 venue; no restatement applied; date computed and validated BEFORE any distance language",
      "side": "spot 39.08% above line; structurally cannot fire (0 of 2 required closes exist, and one weekly close cannot supply two). Tier-1 release BEFORE this checkpoint: YES — nonfarm payrolls Fri 2026-08-07 08:30 ET, named in the falsifier: hot print raises Sept hike odds above the current ~59-63% and pressures ETH toward the 50dma at 1786.58; soft print cuts them and puts the 200dma at 2073.62 in play. NFP cannot produce two sub-1350 weekly closes by 2026-08-09. Next tier-1 after: CPI Wed 2026-08-12 08:30 ET. NON-MACRO DATED BINARY inside the same window and more ETH-relevant: the CLARITY Act's 2026-08-10 Senate cliff."
    }
  },
  "companion_fr": {
    "score": 9,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 62.10, "ma200_falling": true, "ma200_slope20_pct": -5.75, "price_below_ma200_pct": -9.46 },
    "legs_channel_b": { "rally_extension": 3, "local_momentum": 2, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 1548.76, "low_40s_date": "2026-06-26", "bounce_pct": 21.23, "daily_rsi14": 52.45, "weekly_rsi14": 41.58, "bounce_age_sessions": 38, "funding_annualized_pct": 3.22 },
    "counts_used": { "resistance_count": 1, "structure_count": 2, "sentiment_count": 1 },
    "counts_derivation": "resistance 1/4: (a) within 3% of 200dma FALSE (-9.46%), (b) within 3% of 50dma from below FALSE (price 5.09% ABOVE the 50dma), (c) price at/below a prior swing high that is itself a lower high TRUE, (d) prior breakdown level FALSE. structure 2/3: (a) bounce high is a lower high TRUE, (b) 50dma below 200dma AND gap NOT narrowed FALSE (gap_narrowed_20=true), (c) no weekly close above the 200dma in 8 weeks TRUE. sentiment 1/3: (a) F&G 3d >=1.5x its 30d mean FALSE, (b) funding flipped positive after >=5 negative sessions FALSE (zero negative intervals in 45), (c) flow tell TRUE (ETF inflows resuming into the rally — fourth consecutive weekly inflow week, +$53.1M on 2026-08-04).",
    "gates_note": "Channel B Phase 1A line 13 — short by 4. Penalty 0 (squeeze tier none; bounce 38 sessions old, no maturity penalty). Confidence full, no missing inputs.",
    "cross_validation": "consistent — FK 11 (mechanical) / FR 9, both <12 so Hard Rule 5's both->=12 condition is NOT met. Label UNQUALIFIED because the Channel A cap is not binding (Channel B is the live channel). DISCLOSED HONESTLY, as on 2026-08-01 and 2026-08-03: 11 and 9 are NOT strongly inverse and the gap NARROWED again. Per the FR skill's own Channel B note this is expected rather than anomalous — the frameworks score different objects on different horizons (FK: accumulation value; FR-B: whether a specific counter-trend bounce is dying into resistance). ETH's rally_extension leg scores 3 precisely because the +21.23% bounce that helps FK's structural read is what FR-B measures as extension. That identity is the SECOND FACTOR behind this report's D1 term — cited as corroboration of an underlying market fact (extension, age, volume), never as the factor itself, and pushing FK DOWN so no Hard Rule 5 state is manufactured or dissolved.",
    "standalone_report_owed": true,
    "standalone_report_trigger": "RE-FIRED AND STILL UNDISCHARGED. The companion prints 9, firing vacuity trigger (ii) for the SECOND time — the first was the 2026-08-01 ETH companion, also 9. That report was flagged OWED and NOT DISCHARGED on 2026-08-01 and again on 2026-08-03, and has never been run: the newest ETH FR report on disk is eth_flying_rocket_20260731_0426.md, which PREDATES the first trigger. Action: run /flying-rocket-analytics eth. Second-highest priority open item in this series after the ledger refresh."
  },
  "position": {
    "source": "tools/position.mjs eth",
    "exit_code": 1,
    "band": "EXPIRED",
    "age_min": 5654,
    "age_driver": "holdings_as_of",
    "generated_age_min": 5652,
    "expired_after_min": 4320,
    "cold_start": true,
    "cold_start_basis": "Hard Rule 4 — stated explicitly, no fresh ledger was available",
    "qty": null,
    "avg_cost_usd": null,
    "total_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd": null,
    "attribution": "UNKNOWN — ledger unreadable",
    "dry_powder_stable_usd": null,
    "dry_powder_benchmark_pct": 3.73,
    "dry_powder_benchmark_source": "Yahoo ^IRX 2026-08-05; FRED DGS3MO cross-check 3.91%",
    "last_readable_snapshot": {
      "as_of": "2026-08-01T15:51:56Z",
      "read_in_report": "reports/eth_fallen_knives_20260803_1411.md",
      "band_then": "STALE",
      "qty": "0.00006517",
      "custody_status": "RECONCILED",
      "withdrawn_qty": "0",
      "basis_reliable": false,
      "oversold_qty": "8.50642325",
      "unbacked_disposal_count": 24,
      "short_qty": null,
      "attribution": "UNTAGGED",
      "untagged_open_deals": 1,
      "performance_by_tag": [],
      "dry_powder_stable_usd": 14408.87,
      "dry_powder_note": "SHARED POOL — the same balance backs the BTC and gold reports published at this timestamp. Two reports each sizing '10% of the book' against it would double-commit the same dollars.",
      "portfolio_total_usd": 19790.26
    },
    "note": "EXIT 1 / EXPIRED at 94.2h — cold start per Hard Rule 4, stated explicitly. NO quantity, cost basis, PnL, ROI or dry-powder figure is asserted as current, and NO tranche is sized against the last readable snapshot. Position Reconciliation: prior reports narrate '~5% Phase 1A filled at ~$1,844 plus ~5% laddered 1800-1825 working'; the last readable (STALE) snapshot showed DUST with basis.reliable=false on 8.50642325 ETH across 24 unbacked disposals — the account's largest gap by a wide margin (BTC's is 0.0336) — custody RECONCILED with zero withdrawals, short_qty explicitly null, and ZERO deal tags on one open deal. The narration is UNVERIFIED in BOTH directions: not confirmed, not refuted, explicitly not read as flat. performance_by_tag was an EMPTY ARRAY — zero tagged deals exist, so nothing is asserted about how ETH Phase 1A entries have actually performed. Degradation across reports: STALE 50.2h on 2026-08-03 -> EXPIRED 94.2h today."
  },
  "trend_residual": { "active_downtrend": false, "basis": "price is 24.43% below the 200-week mean and 9.46% below a falling 200dma (-5.75%/20 sessions), so the MA half is satisfied twice over — but NOT making lower lows: the 40-session low $1,548.76 (2026-06-26) has held 38 sessions, price has rallied +21.23% off it and sits +5.09% above the 50dma in a higher-low sequence", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15." },
  "correlation": { "value_30d_vs_spx": 0.267, "window": "2026-06-24 to 2026-08-05", "method": "Pearson on daily log returns, 30 overlapping return pairs, Yahoo ETH-USD vs ^GSPC closes, computed 2026-08-05", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.267 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds — it was AVAILABLE on Phase 1A and declined on other grounds" },
  "discretion": {
    "d1_taken": true,
    "d1_value": -0.5,
    "d1_direction": "negative",
    "d1_first_nonzero_in_series": true,
    "d1_consecutive_reports_at_this_size": 1,
    "d1_reverses_prior_decline": true,
    "d1_reversal_disclosure": "This -0.5 REVERSES the 2026-08-03 decision to decline a -0.5, and the reversal is flagged at the top of section 9.3 rather than buried. The original decline rested on two grounds. Ground (b) — the macro argument is shared with BTC, which prices it at -1.0, so counting it twice double-counts one macro across two reports — is RESPECTED IN FULL and is why this term is built differently: neither factor below is the shared macro, and no September-hike, oil, or equity-melt-up argument appears in it. Ground (a) — the -0.5 would erase a Phase 1B score-line crossing the legs had just earned on a sourced decimal — is OVERRIDDEN on fresh evidence: the crossing being erased is a VALUATION reading while the factors are REGULATORY and STRUCTURAL (different objects, not a re-litigation of the same one), and the crossing is INERT because 1B is short three gates and one [V] gate. Erasing an inert crossing to state an honest directional view costs nothing and is more useful to a calibration than preserving a milestone that authorizes no capital. LOGGED FOR GRADING: a calibration should test whether this reversal was signal or drift.",
    "d1_factors": [
      "CLARITY Act BECAME DATED. On 2026-08-03 this was a diffuse '27% passage odds for 2026'. Today it is a HARD 2026-08-10 Senate deadline — the state work period begins, the bill still needs floor debate, an amendment process and 60-vote cloture before then, the pre-recess calendar is consumed by a Russia sanctions package and a nominations backlog, and failure defers everything to mid-September. Odds 28-37% (Polymarket via Yahoo Finance / cryptonews.com, 2026-08-05), down from an 82% February peak. A FIVE-DAY BINARY is a categorically different object from a six-month probability, and it is ETH-WEIGHTED: market-structure classification determines the legal footing of the application layer that gives ETH its non-monetary demand. No leg scores regulation; gate 9 is a single boolean.",
      "BOUNCE MATURITY / EXTENSION, corroborated by the computed companion. The rally is +21.23% off the 40-session low and 38 sessions old; price sits +5.09% above the 50dma and -9.46% below a 200dma falling at -5.75%/20 sessions; session volume is at the 5th PERCENTILE of two years and realized 30d vol at the 4th. The computed FR Channel-B companion scores rally-extension 3/5 and prints 9/20. No FK leg or gate scores rally extension, bounce age, or participation quality — the board reads this structure as neutral-to-supportive. The read is a mature counter-trend rally on thin volume, which is a worse place to add than a fresh base. The FR companion is cited as CORROBORATION of an underlying market fact, never as the factor itself."
    ],
    "d1_falsifier": "Retire when EITHER (a) the CLARITY binary resolves — the Senate passes the bill, OR the 2026-08-10 deadline passes and odds re-rate above 55% on a credible September path — OR (b) ETH prints a FRESH 40-session low below $1,548.76, which would end the counter-trend-rally read entirely and replace it with the deep-fear leg this framework wants to buy. HARD REVIEW DATE 2026-08-19.",
    "d1_effect": "Removes the Phase 1B score-line crossing (mechanical 11 >= 11 becomes adjusted 10 < 11) — the crossing this series achieved for the first time on 2026-08-03. CAPITAL EFFECT: NONE — 1B is independently short THREE gates (2/8 vs 5) and one [V] gate (2 vs 3). Stated plainly so the term is graded on whether its directional claim was correct, not credited with restraint the gates already supplied.",
    "d1_larger_considered_declined": "-1.0 DECLINED: the valuation floor is genuinely extraordinary and arithmetically robust — the MVRV-Z sign cannot flip without a 21.27% rally — and the supply lock-up is accelerating rather than stalling. Under half-down rounding a -1.0 takes 10.0 -> 10, the SAME adjusted score as -0.5, so it would buy no additional consequence while overstating conviction.",
    "d1_positive_considered_declined": "+0.5 DECLINED as prohibited double-counting: it would be built on the validator entry backlog growing from ~3M to ~3.5M ETH in two days with the exit queue at zero — a RATE the holder leg's binary 3/3 cannot register — but the holder leg already scores exactly this evidence at maximum, and re-weighting a factor a leg already scores is the one thing D1 explicitly may not do.",
    "d1_asymmetry_note": "THIRD report exercising D1 on both majors simultaneously, and the two remain DIFFERENT in size and in construction: -1.0 on BTC, -0.5 on ETH, with ZERO overlapping factors. BTC's rests on the Coldcard exploit (Bitcoin-only device, no ETH keys implicated) and BTC's own non-participation in the equity melt-up. ETH's rests on the ETH-weighted CLARITY cliff and its own bounce maturity. The shared macro appears in exactly one of the two reports, by design. A layer producing the same number for both assets would be measuring the analyst's mood; a layer that discriminates on asset-specific evidence is measuring something.",
    "d2_available": true,
    "d2_taken": false,
    "d2_phase": "1A",
    "d2_detail": "ALL SIX CONDITIONS MET for the THIRD consecutive report: adjusted score 10>=8; gate count short by EXACTLY ONE (2 of 3 required); [V] floor met on lit gates (2>=2); risk-on surcharge OFF (corr 0.267); phase eligible (1A, not 3); no D5 stop-out within 10 days. DECLINED on three grounds in order of weight: (1) a NON-DISCRETIONARY path to the same capital already exists — the partial-tranche rule authorizes the pre-assigned 1A remainder to work the 1800-1825 ladder with no fresh unlock, so a D2 unlock would buy already-authorized dollars at the price of a hard price-only D5 stop and a 10-day analyst-channel bar on the phase, a strictly worse trade for the same money; (2) the gate D2 would substitute for is a FEAR gate (1 or 7), and ETH's entire diagnosis is 'cheap but not feared' — writing a conviction case to supply the exact missing fear evidence BECAUSE it is missing is the pattern the D5 stop exists to punish; (3) a D5 stop line could not be honestly set, because the line is defined relative to THE FILL and with the snapshot EXPIRED at 94.2h and basis.reliable=false on 24 unbacked disposals no fill price is corroborable — a stop you cannot anchor is not a stop. LOGGED EXPLICITLY FOR GRADING: this channel has now been available and unused on THREE consecutive reports. If a channel is repeatedly available and never used, a calibration should say so — either the conditions are mis-specified, or the analyst is correctly using the framework's own alternatives. The analyst believes the latter; that is exactly the claim a calibration should test rather than accept.",
    "override_evaluated": true,
    "override_fired": false,
    "override_detail": "DOES NOT FIRE. Mechanical score 11 < 15 — dispositive. Two further independent failures: 3-day F&G 26.67 is not <=15, and the Override presupposes a corroborated deployed tranche which an EXPIRED ledger cannot supply. No near-fire to log.",
    "d4_taken": true,
    "d4_detail": "Cells set from the read against the 6-10 anchor row (adjusted score 10): Rally +7, Range +2, Retest -7, Bear -2 — all inside the +/-10 percentage-point band, none requiring a >10pp reason line. Deviations reflect a structurally intact 38-session base with an extraordinary valuation floor and an eased risk backdrop, against a regulatory cliff and a thinly-carried bounce. EV recomputed from the printed cells as the final step.",
    "declined_action": "Working the PRE-ASSIGNED Phase 1A remainder in the $1,800-1,825 ladder was declined — not on market grounds, but because an EXPIRED ledger with basis.reliable=false cannot establish how much of 1A is actually assigned, and filling into that would risk prohibited upsizing beyond nominal.",
    "non_mechanical_capital_pct": 0
  },
  "key_inputs": {
    "fng_spot": 27,
    "fng_3d_avg": 26.67,
    "fng_streak_le15_days": 0,
    "fng_lowest_of_last_10_prints": 25,
    "fng_last_10_prints": [27, 25, 28, 27, 27, 25, 28, 29, 29, 30],
    "fng_percentile_vs_2y": 32.58,
    "fng_provider": "alternative.me raw API daily series (pinned) — crypto F&G as the ETH proxy per the Asset Generalization table; no provider switch, no divergence >=10 points to disclose",
    "weekly_rsi14": 41.96,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_boundary": "Yahoo weekly candles, week-start timestamps UTC; last COMPLETED weekly close = bar labelled 2026-07-27 (week ending Sunday 2026-08-02)",
    "weekly_rsi_confidence": "ok",
    "weekly_rsi_tool_artifact": "MANUAL CORRECTION APPLIED AND DISCLOSED. Yahoo is emitting an EXTRA weekly bar for the live session (bars 2026-07-20, 2026-07-27, 2026-08-03, 2026-08-05). tools/fetch.mjs drops only the FINAL bar, so its completed set today includes the in-progress week beginning 2026-08-03 and returns 262 closes / RSI 41.58. The last genuinely completed weekly close is the 2026-07-27 bar -> 261 closes / RSI 41.96, reproducing this series' 2026-08-03 print exactly. HARMLESS on ETH — 41.58 and 41.96 both land in the >40,<=45 -> band 1 — and note the artifact's direction is DOWNWARD here where it is upward on BTC and gold. Flagged for the toolchain. On GOLD the same artifact would have moved a leg and degraded a live stop; see that report.",
    "weekly_rsi_percentile_vs_2y": 29.23,
    "weekly_rsi_distance_to_band_2": "one band edge — a print below 40.0 takes the leg 1->2 and the composite 11->12, restoring the compound stop's second key",
    "daily_rsi14": 52.45,
    "sma_200w": 2484.53,
    "pct_vs_sma200w": -24.43,
    "gate6_within_8pct": false,
    "gate6_lower_edge": 2285.77,
    "gate6_rally_needed_pct": 21.75,
    "ma200d": 2073.62,
    "ma200d_slope20_pct": -5.75,
    "pct_vs_ma200d": -9.46,
    "ma50d": 1786.58,
    "pct_vs_ma50d": 5.09,
    "campaign_low": 1548.76,
    "campaign_low_date": "2026-06-26",
    "campaign_low_age_sessions": 38,
    "bounce_pct_off_low": 21.23,
    "mvrv_z": -0.9285,
    "mvrv_z_method": "scaled from a sourced anchor: z_now = z_anchor x ((r_now - 1)/(r_anchor - 1)) with realized cap held constant",
    "mvrv_z_sourced_anchor": -1.1144,
    "mvrv_z_sourced_anchor_date": "2026-07-06",
    "mvrv_ratio_sourced_anchor": 0.7895,
    "mvrv_z_source": "Santiment getMetric(mvrv_usd_z_score) and getMetric(mvrv_usd), slug ethereum — free tier caps this metric's query window at ~30 days behind the present; the cap sat at 2026-07-04 on 2026-08-03 and at 2026-07-06 today, so the ANCHOR ADVANCED TWO DAYS with the calendar. Staleness disclosed and bounded rather than waved through.",
    "eth_close_on_anchor_date": 1797.57,
    "implied_realized_price": 2276.85,
    "mvrv_ratio_now_derived": 0.8246,
    "mvrv_z_sign_bound": "MVRV-Z = (market cap - realized cap) / sigma(market cap), sigma > 0 by construction, so market value below realized value FORCES the sign negative. Spot would need to exceed ~$2,277 (+21.27% above today's $1,877.535) merely to reach zero. Realized price is a slow-moving aggregate cost basis that rises only when coins move at higher prices, and ETH spent the entire interval BELOW the anchor-date level. The sign is robust to the staleness by a 21% margin.",
    "mvrv_z_provider_cross_check": "Santiment mvrv_usd_z_score for BITCOIN printed 0.3709 on 2026-07-06 against the independent bitcoin-data.com series on the same scale — two providers within ~0.01 at the overlap, on the asset where both are available. That agreement is what makes the ETH figure usable.",
    "mvrv_z_declined_source": "The circulating 'ETH MVRV-Z -0.7 / seven-year low' still traces to a single 2026-06-08 BeInCrypto/Phemex article citing Glassnode at ETH $1,684 — two months stale at a materially different price. REMAINS DECLINED under the provenance-citation rule.",
    "valuation_fallback_note": "The alt fallback band (drawdown from ATH -62.04%) would give 4. The primary metric gives 5. The upgrade rests entirely on the MVRV-Z sign, which is why the derivation is shown in full rather than asserted.",
    "median_holder_underwater_pct": 17.5,
    "drawdown_from_ath_pct": -62.04,
    "ath": 4946.05,
    "ath_date": "2025-08-24",
    "high_1y_pct_below": 62.10,
    "funding_ann_pct": 3.22,
    "funding_mean_per_8h_pct": 0.00,
    "funding_negative_intervals_in_45": 0,
    "funding_longest_negative_run": 0,
    "funding_percentile_vs_history": 61.68,
    "funding_note": "capitulation-(b) requires >=3 CONSECUTIVE negative intervals. The Jul-23/24 four-interval episode flagged on 2026-07-25 as FIRED BUT NOT STANDING has not recurred, and the isolated negatives noted on 2026-08-01 have rolled out of the 45-interval window entirely. The leg is cleanly zero, not reverting.",
    "liquidations_eth_24h_usd_m": 7.27,
    "liquidations_eth_longs_usd_m": 2.97,
    "liquidations_eth_shorts_usd_m": 4.30,
    "liquidations_market_24h_usd_m": 78.70,
    "liquidations_traders_affected": 55823,
    "liquidations_source": "CoinGlass via KuCoin, 2026-08-05",
    "exchange_reserves": "~14.5-14.9M ETH — the lowest level ever recorded, down from >33M in 2021; >6M ETH withdrawn since late 2023 (CryptoQuant via Phemex / CryptoTimes / KuCoin)",
    "staking_ratio_pct": 32.4,
    "staked_eth_m": 38.9,
    "active_validators": 897000,
    "staking_apr_pct": 2.78,
    "validator_entry_queue_eth_m": 3.5,
    "validator_entry_queue_wait_days": 62,
    "validator_exit_queue": "ZERO",
    "validator_queue_note": "entry backlog grew from ~3M ETH on 2026-08-03 to ~3.5M today — a RATE the holder leg's binary 3/3 cannot register, which is why a +0.5 D1 built on it was DECLINED as double-counting",
    "etf_flow_aug04_usd_m": 53.1,
    "etf_flow_aug04_detail": "ETHA +$42.5M, FETH +$9.3M; rebound after a one-day outflow",
    "etf_flow_aug04_conflicting_print": "One aggregator (KuCoin flash) reported -6,558 ETH / -$12.27M for the same session against Farside's +$53.1M. Most likely a scope difference (US spot ETFs vs a broader global/category basket). DISCLOSED rather than suppressed. Per the single-source streak-completion rule this does NOT mark the streak PROVISIONAL — the four-week inflow regime is corroborated by two independent framings (weekly nets and the July monthly total) and by this series' own prior prints, so it does not rest on one streak-completing datum.",
    "etf_flow_week_to_jul31_usd_m": 27.42,
    "etf_flow_streak_weeks": 4,
    "etf_flow_july_usd_m": 365.2,
    "etf_flow_ytd_2026_usd_b": -1.1,
    "gate4_note": "Gate 4 and capitulation-(c) both require OUTFLOWS. Every piece of current evidence — a fourth consecutive weekly inflow week, +$53.1M on 2026-08-04, July +$365.2M — moves them FURTHER from lighting. This is the [V] gate most likely to light on a risk-off turn and currently the furthest from doing so.",
    "adr5": 55.94,
    "adr5_sessions": "2026-07-31, 2026-08-01, 2026-08-02, 2026-08-03, 2026-08-04 — in-progress 2026-08-05 EXCLUDED as not a full session, lookback extended one session, exclusion disclosed",
    "realized_2w_change_pct": -2.86,
    "realized_2w_basis": "vs 2026-07-22 close $1,933.49 (Yahoo)",
    "rv30_pct": 40.87,
    "rv30_percentile_vs_2y": 4.07,
    "rv10_pct": 34.05,
    "rv90_pct": 49.53,
    "drawdown_percentile_vs_2y": 91.93,
    "volume_last_session_usd_b": 8.18,
    "volume_percentile_vs_2y": 5.07,
    "volume_note": "session volume at the 5th percentile of two years and realized 30d vol at the 4th — the +21.23% bounce is being carried on some of the thinnest participation in two years. DISCLOSED CONTEXT, not scored; cited in the D1's second factor as an underlying market fact.",
    "deribit_dvol": 48.24,
    "perp_basis_pct": -0.04,
    "long_short_account_ratio": 2.296,
    "long_short_account_ratio_percentile": 75.86,
    "taker_buy_sell_ratio": 1.0698,
    "taker_buy_sell_percentile": 86.21,
    "open_interest_direction": "rising",
    "tbill_3m_pct": 3.73,
    "tbill_3m_cross_check_dgs3mo_pct": 3.91,
    "real_yield_10y_tips_pct": 2.43,
    "vix": 16.95,
    "vix_5session_change_pct": -17.96,
    "dxy": 99.73,
    "dxy_5session_change_pct": -1.06,
    "brent": 79.48,
    "brent_5session_change_pct": -12.41,
    "spx": 7785.30,
    "spx_5session_change_pct": 6.41,
    "ndx_composite": 26684.81,
    "ndx_5session_change_pct": 9.17,
    "us10y_nominal_pct": 4.64,
    "net_liquidity_usd_t": 5.83,
    "hy_oas_pct": 2.78,
    "nfci": -0.529,
    "stablecoin_supply_usd_b": 183.10,
    "stablecoin_change_30d_pct": -0.59,
    "stablecoin_change_90d_pct": -3.43,
    "fed_funds_target": "3.50-3.75%, held 9-3 on 2026-07-29 with three hawkish dissents",
    "next_fomc": "2026-09-16",
    "sept_fomc_hike_probability_pct": "~59-63 (CME FedWatch via Reuters/Bloomberg coverage 2026-08-05; range across sources disclosed rather than a single point estimate)",
    "clarity_act_2026_passage_odds_pct": "28-37 (Polymarket via Yahoo Finance / cryptonews.com 2026-08-05), down from an 82% February peak",
    "clarity_act_deadline": "2026-08-10 — Senate state work period begins; final date to vote before recess; failure defers to mid-September. Still needs floor debate, an amendment process and 60-vote cloture; the pre-recess calendar is consumed by a Russia sanctions package and a nominations backlog. THE MOST ETH-WEIGHTED CATALYST ON THE BOARD and factor (i) of this report's D1.",
    "coldcard_exploit_note": "Bitcoin-only event (~$130M across five waves from 5,200+ Coldcard-generated addresses since 2026-07-30; root cause a March 2021 Coinkite firmware RNG defect; shipments halted, remaining affected inventory destroyed; at least a dozen attacker groups). Coldcard is a Bitcoin-only device; NO ETH keys implicated and ETH's holder leg (staking + exchange withdrawal) is unimpaired. Recorded because it is the basis of the -1.0 D1 taken on BTC, and its ABSENCE from ETH's risk set is a material part of why ETH's term is smaller and differently constructed.",
    "hormuz_status": "STILL CLOSED as of 2026-08-05 — ~2 transits on 2026-08-02 against ~73/day normal. The equity rally trades the DEAL, not the reopening.",
    "tier1_next_5_sessions": ["Nonfarm payrolls (July Employment Situation) Fri 2026-08-07 08:30 ET"],
    "tier1_window_verified": "compute.mjs tier1 --from 2026-08-05 --sessions 5 → window 2026-08-06..2026-08-12, returns exactly one tier-1 event (NFP 2026-08-07) and zero warnings. Report is NOT an incomplete-data report on the calendar dimension.",
    "tier1_beyond_window": ["CPI (July) Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14", "FOMC decision Wed 2026-09-16"],
    "non_tier1_dated_catalyst": "CLARITY Act Senate recess deadline Mon 2026-08-10 — not a macro release, but a dated binary inside the checkpoint window and named in the D1",
    "stale_input_debt": []
  },
  "collar": {
    "band_triggered": true,
    "reasons": ["|EV-vs-spot| 1.71% < 2%"],
    "mechanical_score_in_6_10_band": false,
    "scorecard_within_1_of_balanced": false,
    "scorecard": "9 bull / 11 bear — net bearish by 2, outside the within-1 limb",
    "effect": "no directional regime resolution claimed anywhere in the report; every forward statement carries a probability or an IF->THEN plus a named falsifier"
  },
  "verdict": "HOLD; authorize nothing; work nothing until the ledger is readable. Mechanical 11/20 — NO LEG MOVED from 2026-08-03. D1 = -0.5, the FIRST NON-ZERO DISCRETIONARY TERM IN THE ETH SERIES, and it REVERSES a decline made two days ago — flagged loudly, logged for calibration. Adjusted 10/20. THE READ: ETH is the cheapest asset in this book and the least frightened market in it. MVRV-Z ~-0.93 against an implied realized price of $2,276.85 puts the median holder ~18% underwater, the cheapest since December 2018, and the SIGN is arithmetically forced — spot needs +21.27% merely to reach zero. Underneath it the float is being removed on a schedule: exchange reserves at the lowest level EVER recorded, staking at a record 32.4%, an entry backlog that grew 3M -> 3.5M ETH in two days against an exit queue of exactly ZERO. Against that, nobody is frightened and almost nobody is trading: F&G stuck at 25-30 for ten sessions, funding positive with zero negative intervals in 45, $7.27M of liquidations, SESSION VOLUME AT THE 5TH PERCENTILE OF TWO YEARS and realized 30d vol at the 4TH. THE CENTRAL OBSERVATION: FK's legs read a 38-session base with higher lows as supportive structure while the computed FR Channel-B companion reads the IDENTICAL tape as rally-extension 3/5 on its way to 9/20. Both are right — a base and an aging counter-trend rally on thin volume are the same object from two angles, and only one angle is in the rubric. D1 RATIONALE: (i) CLARITY moved from a diffuse 27% annual probability to a HARD 2026-08-10 Senate cliff at 28-37% with cloture unstarted — a five-day binary is a different object from a six-month one, and it is ETH-weighted; (ii) bounce maturity, +21.23% / 38 sessions / 5th-percentile volume, corroborated by the companion. EXPLICITLY NOT the shared macro BTC prices at -1.0 — no hike-path, oil or melt-up argument appears here, because ground (b) of the 2026-08-03 decline was right and is respected. Ground (a) is overridden: the erased 1B crossing is INERT (short three gates and one [V] gate) and the objects differ (valuation vs regulatory/structural). Declined -1.0 (buys no extra consequence under half-down) and +0.5 (double-counts the holder leg's 3/3). D2 AVAILABLE ON 1A AND DECLINED FOR THE THIRD CONSECUTIVE REPORT — the partial-tranche rule already authorizes the same capital without a D5 stop or a 10-day phase bar; the gate D2 would substitute for is a FEAR gate and ETH's whole diagnosis is 'cheap but not feared'; and no D5 line could be anchored to a fill an EXPIRED ledger cannot corroborate. That three-report pattern is logged as a finding for calibration. STOP: mechanical 11 < 12 so the score axis IS satisfied and the compound stop is price-gated at $1,350 — but ETH remains ONE BAND EDGE from restoring the second key (weekly RSI below 40.0, currently 41.96), the cheapest protection upgrade available and one that arrives on WEAKNESS. Coherence PASS ($1,300 < $1,450). Nothing moved; D6 compliant. TOOLCHAIN CATCH: Yahoo is emitting an extra live weekly bar so fetch.mjs's 'completed' set silently includes the IN-PROGRESS week; corrected 261-close RSI 41.96 vs the tool's 41.58 — harmless here (same band), decisive on GOLD. POSITION (Hard Rule 8): EXIT 1 / EXPIRED at 94.2h, degraded from STALE 50.2h. Cold start per Hard Rule 4, stated explicitly; narrated fills UNVERIFIED IN BOTH DIRECTIONS; no basis or PnL quotable against 24 unbacked disposals (8.5064 ETH, the account's largest gap); dry powder a SHARED pool with BTC and gold. FR COMPANION 9/20 — trigger (ii) RE-FIRES and the standalone report owed since 2026-08-01 has STILL never been run. Collar ACTIVE (|EV| 1.71% < 2%): no directional regime resolution claimed."
}
```
