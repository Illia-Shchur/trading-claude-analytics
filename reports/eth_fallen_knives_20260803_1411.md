# 🔪 FALLEN KNIVES ANALYTICS — ETH — 2026-08-03

## MONDAY AFTERNOON — ALL DATA LIVE INTERNET-VERIFIED

### Report Generated: Monday, August 3, 2026, 2:11 PM EDT

### Asset: ETH | Prior Score: 10/20 (2026-08-01) | Mechanical: 11/20 | D1: 0.0 | **Adjusted: 11/20**

---

## 1. Executive Frame

The headline of this report is a **sourced decimal**, not a price move.

For three consecutive reports the ETH valuation leg has been carried on a proxy while the MVRV-Z decimal sat on the stale-input debt clock, because the only circulating figure — a "−0.7 / seven-year low" — traced to a single June 8 article written at ETH $1,684. That debt is **discharged today**. Santiment's `mvrv_usd_z_score` for ETH printed **−1.121** on 2026-07-04, and the accompanying MVRV ratio of **0.781** pins an implied realized price of **$2,277**. Spot is $1,868. The average ETH holder is roughly 18% underwater, and the sign of the Z-score is not an estimate — it is arithmetic.

That takes the valuation leg from 4 to its maximum of **5**, and the composite from 10 to **11** — which clears the Phase 1B score line for the first time in this series. It also changes nothing about deployment, because ETH holds **2 of 8** gates against a 1B requirement of 5, with a [V] floor of 3 against 2 lit. The score condition and the gate condition have swapped which one is binding, and the gate condition is binding by a mile.

ETH is the cheapest it has been on this metric since December 2018. It is also not remotely frightened.

---

## 2. Verified Live Data Points — ETH

### 2.1 Price — canonical spot reconciliation

| Source | Symbol | Price (USD) | Timestamp | Status |
|---|---|---|---|---|
| Binance | ETHUSDT | $1,870.08 | 2026-08-03 18:03:01 UTC | live (venue ts) |
| Coinbase | ETH-USD | $1,868.48 | 2026-08-03 18:02:58 UTC | live (venue ts) |
| Kraken | ETHUSD | $1,867.94 | 2026-08-03 18:02:5x UTC | live (receipt ts) |
| CoinGecko | ethereum | $1,866.95 | 2026-08-03 18:00:40 UTC | live (venue ts) |
| Yahoo ETH-USD | ETH-USD | $1,868.34 | 2026-08-03 daily bar | **EXCLUDED — frozen bar close, never enters the median** |

> **CANONICAL SPOT = $1,868.21** — median of 4 synchronized live quotes, all inside the 2-hour window.
> Inter-source spread **0.168%**, under the 0.5% flag. Dispersion is **genuine simultaneous venue disagreement** (four live timestamps within ~2.5 minutes), not staleness. At 0.168% it moves no band, no gate boolean and no cap tier. No low-confidence demotion.

**Method note.** `tools/fetch.mjs` still reports `spot.canonical` as priority-first ($1,866.95, CoinGecko) while this SKILL §2 mandates the **median**; the tool discloses the conflict itself. **This report uses the median, $1,868.21** (delta −0.067%). The tool-side flip is commit 12 of the 2026-08 toolchain-extension plan, executed immediately after this report is committed.

**24h context:** ETH −1.50% to ~$1,838 at the CoinGape print earlier in the session, recovered to ~$1,868. Trailing **2-week change: −1.87%** (vs $1,903.76 close on 2026-07-20, Yahoo).

### 2.2 Sentiment

| Metric | Reading | Source | Timestamp |
|---|---|---|---|
| F&G spot | **28** ("Fear") | Alternative.me raw API (**pinned provider**) | 2026-08-03 |
| F&G 3-day average | **27.33** | Alternative.me | 2026-08-01 → 08-03 |
| Daily prints (last 10) | 28, 27, 27, 25, 28, 29, 29, 30, 26, 27 | Alternative.me | 2026-07-25 → 08-03 |
| Gate-1 streak (daily prints ≤15) | **0 consecutive days** | Alternative.me | — |
| ETH perp funding (asset-specific sentiment overlay) | **+3.03% annualized** — half BTC's | Binance fapi | 2026-08-03 |

The crypto F&G index is a market-wide instrument used here as the large-cap proxy per §Asset Generalization; the ETH-specific overlay is funding, reported in §2.6. Second-provider context: COINOTAG published 27 on 2026-08-02 — 1 point from the pinned provider, far under the 10-point disclosure bar.

### 2.3 Momentum — weekly RSI (computed, auditable)

| Field | Value |
|---|---|
| **Wilder RSI-14, weekly** | **41.96** |
| Weekly-close source | Yahoo ETH-USD, 5y 1wk series (262 candles) |
| Weekly boundary | Yahoo week-start timestamps, UTC |
| Period | 14 |
| Completed closes used | **261** (≥30 → unflagged, full confidence) |
| Last completed week | 2026-07-27 |
| RSI including the live (incomplete) week | 41.57 — *not scored* |
| Daily RSI-14 | 51.37 |

Band `≤45 → 1`. Note the direction: 41.40 on Aug-01 → **41.96** today. ETH momentum is repairing, not exhausting — which costs the leg a point relative to a deeper washout and is the honest reading.

### 2.4 Valuation — MVRV-Z: the debt clock is discharged

This leg was carried on a proxy for three reports. Here is the full derivation, because the leg moved and the move crosses a score threshold.

**Sourced anchor (a real decimal, from a named provider, with a documented methodology):**

| Metric | Value | Date | Source |
|---|---|---|---|
| **ETH MVRV-Z** | **−1.121278** | 2026-07-04 | Santiment `getMetric(mvrv_usd_z_score)`, slug `ethereum` |
| ETH MVRV ratio | **0.781315** | 2026-07-04 | Santiment `getMetric(mvrv_usd)` |
| ETH close that day | $1,779.03 | 2026-07-04 | Yahoo ETH-USD |
| **Implied realized price** | **$2,277.11** | 2026-07-04 | computed: 1,779.03 ÷ 0.781315 |

**Why 2026-07-04 and not today:** the Santiment free tier caps this metric's query window at that date. The staleness is disclosed rather than hidden, and it is handled below rather than waved through.

**Provider-scale cross-check (this is what makes the number usable, not the article).** Santiment's `mvrv_usd_z_score` for **BTC** printed **0.371** on 2026-07-04; the independent bitcoin-data.com series printed **0.3315** on the same date. Two providers, **0.04 apart on the same scale**, on the asset where both are available. Santiment's ETH figure is therefore on a scale this framework can read.

**Live bound on the sign — the part that is arithmetic, not estimate:**

| Step | Value |
|---|---|
| Implied realized price (Jul-04 anchor) | $2,277.11 |
| Canonical spot today | $1,868.21 |
| **Live MVRV ratio** | **0.820** |
| Market value vs realized value | **−18.0%** |
| **Spot required to flip MVRV-Z positive** | **> $2,277** — i.e. **+21.9% above spot** |
| Scaled Z estimate | ≈ **−0.92** (−1.121 × 0.1796/0.2187) |

Since MVRV-Z = (market cap − realized cap) ÷ σ(market cap) and σ > 0 by construction, **market value below realized value forces the Z-score negative**. Realized price is a slow-moving aggregate cost basis that rises only when coins move at higher prices, and ETH has spent the interval *below* the Jul-04 level for most of it. The sign is robust to the staleness by a 22% margin.

| | |
|---|---|
| **Band applied** | `<0.1 → 5` — *"any negative MVRV-Z also lands here"* |
| **Score** | **5 / 5** — verified `compute.mjs band fk-mvrv -0.92` → band 5 |
| Prior report | 4, on a ratio proxy of 0.81, explicitly flagged as possibly one point too low |
| **Stale-input debt clock** | **DISCHARGED at report 4** |

**What was declined, again.** The circulating "ETH MVRV-Z −0.7, seven-year low" figure still traces to a single 2026-06-08 BeInCrypto/Phemex article citing Glassnode at ETH $1,684 — now two months stale at a price ~11% lower. It remains **DECLINED** under the provenance-citation rule. The Santiment series replaces it: a queryable API with a stated methodology and a verifiable cross-provider scale, not a headline.

**Supporting valuation context:**

| Metric | Value | Source |
|---|---|---|
| Drawdown from ATH ($4,946.05, 2025-08-24) | **−62.25%** | CoinGecko |
| Distance below 1-yr high ($4,953.73) | −62.31% | Yahoo |
| *(Drawdown fallback band, not used — MVRV-Z is primary for ETH)* | *would be `≥60% → 4`* | — |

Worth stating plainly: the fallback the framework would use for an alt gives **4**, and the primary metric gives **5**. The upgrade rests on the MVRV-Z sign, which is why the derivation above is shown in full rather than asserted.

### 2.5 Long-horizon structure

| Metric | Value | Source |
|---|---|---|
| **200-week SMA** | **$2,481.80** | computed, Yahoo 261 weekly closes |
| Spot vs 200-week SMA | **−24.77%** | — |
| **Gate 6 (within ±8%)** | **FALSE ❌** | `tools/fetch.mjs` boolean |
| 200-day MA | $2,087.97 | computed |
| Spot vs 200dma | −10.52% | computed |
| 200dma 20-session slope | **−5.55% (falling, steeper than BTC's −3.62%)** | computed |
| 50-day MA | $1,783.58 | computed |
| Spot vs 50dma | **+4.74%** | — |
| 50dma vs 200dma | 50 **below** 200 (gap 14.58%, narrowing) | computed |
| 40-session low | **$1,510.51** (2026-06-26) | computed |
| Bounce off that low | **+23.60%**, 38 sessions old | computed |

The structural asymmetry versus BTC is the single most important line in this table. BTC sits **on** its 200-week mean; ETH sits **24.77% below** it. Gate 6 is not merely dark for ETH — it is dark by a margin that no plausible near-term rally closes.

### 2.6 On-chain & derivatives

| Metric | Value | Source | Timestamp |
|---|---|---|---|
| Perp funding, mean per 8h | **+0.00%** (**+3.03% annualized**) | Binance fapi `fundingRate`, ETHUSDT, 45 intervals | 2026-08-03 |
| Longest **negative** funding run | **0 intervals** (of 45; 15 sessions) | Binance fapi | 2026-08-03 |
| 24h liquidations, ETH | **$87.73M** — the largest of the majors, ahead of BTC's $58.01M | COINOTAG | 2026-08-02/03 |
| **Exchange reserves** | **~14.5M ETH — a decade low**, lowest since ~2016; >6M ETH withdrawn since late 2023 | CryptoQuant via crypto-economy / cryptorank | 2026-08 |
| **Staking ratio** | **32.4% — a record**; ~3M ETH in the entry queue, **exit queue at zero** | Phemex / KuCoin / CCN | 2026-08 |
| Spot ETF flows, July | **+$365.2M** — 2nd positive month of 2026 | CryptoTimes, compiling Farside/SoSoValue | 2026-08-01 |
| Spot ETF flows, week to Jul-31 | **+$27.42M** — **4th consecutive weekly inflow week** | CryptoTimes | 2026-08-01 |
| Only outflow session that week | −$32.9M on 2026-07-29 | CryptoTimes | — |
| Jul-31 session | ~+$9M, led by ETHB +$15.4M | CryptoTimes | — |
| ETF flows YTD 2026 | **still ~−$1.1B net outflows** | CryptoTimes | — |

**Metric-history continuity check on the flow streak.** The four-week inflow streak is corroborated by two independent framings in the same source set (weekly net flows and the monthly July total) and is consistent with this series' own prior prints: the Aug-01 ETH report recorded July at +$365.17M and the week ending Jul-24 at +$103.9M. The streak is **not** marked PROVISIONAL — it does not rest on a single streak-completing print. Note the direction of travel for scoring purposes: gate 4 and capitulation-(c) both require *outflows*, and this evidence moves them **further from lighting**.

### 2.7 Macro & equities

Identical to the BTC report published at this timestamp (shared macro backbone, `tools/fetch.mjs macro`, 2026-08-03 18:03:07 UTC):

| Metric | Level | Δ 5 sessions | Source |
|---|---|---|---|
| S&P 500 | 7,602.56 | +2.55% | Yahoo ^GSPC |
| VIX | 15.81 | −15.32% | Yahoo ^VIX |
| DXY | 100.00 | −1.49% | Yahoo DX-Y.NYB |
| Brent | $83.77 | −5.19% | Yahoo BZ=F |
| 10y TIPS real yield | 2.41% | −0.02pp | FRED DFII10 (2026-07-30) |
| **3-month T-bill** | **3.78%** | — | TradingEconomics |
| Fed funds target | **3.50–3.75%**, held 9–3, three hawkish dissents | 5th consecutive hold | FOMC 2026-07-29 |
| **Sept FOMC market-implied HIKE probability** | **60.1%** | from 78.8% pre-presser | CME FedWatch via CNBC |
| Net liquidity | $5.83T | — | FRED, week of 2026-07-29 |
| HY OAS / NFCI | 2.84% / −0.554 | +0.07pp / — | FRED |
| **Aggregate stablecoin supply** | **$183.20B, −0.49% 30d, −3.33% 90d** | — | DefiLlama |

**Disclosed context (not scored, not a gate):** ETH options surface via Deribit was fetched but is reported in the BTC companion; for ETH the relevant context is that realized vol regimes across the complex sit near two-year lows while ETH's own 24h liquidations lead the majors — a market with no volatility and, this session, the most forced closures. Neither is a scored input.

### 2.8 Correlation regime

| Metric | Value |
|---|---|
| **30d Pearson correlation, ETH vs SPX** | **0.356** |
| Method | Pearson on daily log returns, overlapping sessions, Yahoo closes |
| Window | 2026-06-18 → 2026-08-03 (30 return pairs) |
| Computed | 2026-08-03 |
| **Regime** | **mild** |
| Risk-on surcharge (>0.7) | **OFF** — no extra [V] gate required |
| Phase-2 corr condition (<0.80) | **PASS on a computed number** |
| D2 availability on correlation grounds | **not barred** (surcharge off) |

---

## 3. Critical Developments

**1. Ethereum's supply side keeps tightening, uninterrupted.** Exchange reserves at **~14.5M ETH** — a decade low, back to levels last seen around 2016 — with more than 6M ETH withdrawn since late 2023. Staking hit a record **32.4%** of supply, with roughly **3M ETH queued to enter** and the **exit queue at zero**. This is not a fear signal; it is a structural float reduction, and it has continued through both rallies and corrections.

**2. ETH ETFs extended a four-week inflow streak while BTC's turned red.** ETH: +$27.42M for the week to July 31, +$365.2M for July, a fourth consecutive positive week. BTC: −$61.53M for the same week. This is the cleanest asset-specific divergence in the complex right now, and it cuts against ETH's fear-gate evidence rather than for it. *(CryptoTimes 2026-08-01.)*

**3. CLARITY Act punted past the recess.** Thune confirmed no floor vote before the August 7 recess; Polymarket 2026-passage odds at a **record-low 27%**. Market-structure legislation is the single largest regulatory catalyst for ETH's application layer, and it is now a September-at-earliest story. *(CoinDesk 2026-07-23; crypto.news.)*

**4. Fed held at 3.50–3.75% on a 9–3 vote with three hawkish dissents; September carries a 60.1% market-implied probability of a hike.** Next decision September 15–16. *(FOMC 2026-07-29; CNBC.)*

**5. Iran de-escalation.** Strikes called off, talks resumed Monday. Brent −5.19%, SPX +2.55%, VIX 15.81. ETH did not participate: −1.87% over two weeks.

**6. The Coldcard exploit is a Bitcoin-only event.** ~1,816 BTC / ~$114M drained from 5,200+ Coldcard-generated addresses since July 30, root-caused to a 2021 Coinkite firmware RNG defect. Coldcard is a Bitcoin-only device and no ETH keys are implicated. It is noted here for completeness and for one indirect reason: it is the entire basis of the **−1.0 D1 term taken on BTC in the companion report**, and its absence from ETH's risk set is a material part of why ETH's D1 is 0.0. *(CoinDesk 2026-08-02/03; Bloomberg 2026-08-03.)*

---

## 4. Fallen Knives Composite Score — ETH

| Category | Max | Input | Band | **Score** |
|---|---|---|---|---|
| **Sentiment Extreme** | 5 | 3-day avg F&G **27.33** (pinned: Alternative.me) | ≤35 → 2 | **2** |
| **Momentum Exhaustion** | 4 | Weekly Wilder RSI-14 **41.96** (261 closes, full confidence) | ≤45 → 1 | **1** |
| **Valuation** | 5 | **MVRV-Z negative** (−1.121 sourced 2026-07-04; live ratio 0.820 forces the sign, ≈ −0.92) | <0.1 → 5 | **5** ⬆ |
| **Capitulation Evidence** | 3 | 0 of 3 — see below | 0/3 → 0 | **0** |
| **Holder Behavior** | 3 | Reserves at a decade low + staking at a record 32.4% — **both** | Both → 3 | **3** |
| **LEG SUM** | 20 | | | **11** |

*Band assignments verified: `compute.mjs band fk-sentiment 27.33` → 2; `fk-momentum 41.96` → 1; `fk-mvrv -0.92` → 5.*

**Capitulation detail (0/3):**
- **(a) Liquidations in the top decile of trailing-90d, or >3σ above the trailing-30d mean** — ❌. ETH 24h liquidations **$87.73M**, the largest of the majors this session but nowhere near the series' comparable-venue flush band. Leading a quiet field is not a flush.
- **(b) Funding negative for ≥3 consecutive intervals** — ❌. **Longest negative run 0 of 45 intervals.** The Jul-23/24 four-interval episode that briefly scored this leg on Jul-25 — flagged then as *fired but not standing* — has not recurred, and the two isolated negatives noted on Aug-01 have rolled out of the window entirely. The leg is now cleanly zero rather than reverting.
- **(c) ETF net outflows ≥2% of AUM trailing month** — ❌. Trailing month is **+$365.2M inflow** across four consecutive green weeks. This is the furthest from lighting of any capitulation sub-condition on either asset.

**Score arithmetic:**

| Component | Value |
|---|---|
| Leg sum | 11 |
| **MECHANICAL score** (read by every protective rule) | **11** |
| **D1 discretionary term** | **0.0** (see §9) |
| Raw composite | 11.0 |
| Rounding convention (ETH) | half-**down** (estimate-heavy input set → conservative on a buy signal) |
| [V]-gate surcharge | none (corr 0.356 ≤ 0.7) |
| **ADJUSTED score** | **11 / 20** |

### Confirmation Gates — 2 of 8 ✅ (gate 5 N/A — PoS)

| # | Bucket | Gate | Status | Evidence / relight path |
|---|---|---|---|---|
| 1 | **[V]** | F&G ≤15 for ≥7 consecutive daily prints | ❌ | Streak **0**; lowest of last 10 is 25. Relight: seven straight prints ≤15 — needs a genuine panic leg, realistically a break of the $1,510 June low. **Reachable, not close, and this is the gate nearest to mattering.** |
| 2 | **[V]** | Weekly RSI <30 | ❌ | 41.96, and **rising** (41.40 → 41.96). Relight: weekly closes near ~$1,500–1,550 sustained several weeks. **Reachable in regime, moving away.** |
| 3 | **[V]** | Valuation cheap (MVRV-Z <1) | ✅ | **Negative** — lit by the widest margin available; ~22% of upside would be needed just to reach zero. |
| 4 | **[V]** | ETF outflows ≥2% AUM trailing month | ❌ | Trailing month **+$365.2M**, four consecutive inflow weeks. Relight: a full reversal to sustained daily outflows for ~3 weeks. **Moving decisively away.** |
| 5 | — | Hash Ribbon buy signal | **N/A** | ETH is PoS. **Denominator reduced 9 → 8** per the counting rule. Never marked ❌ (that would be false-negative bias). |
| 6 | **[T]** | Price within ±8% of the 200-week MA | ❌ | **−24.77%** vs $2,481.80. Relight would require ~+22% to reach the −8% edge. **"none-in-regime"** — a large, slow-moving change, correctly tagged; and per the disclosure rule this finding may **not** be cited to lower a threshold, reduce the denominator, or credit any gate. |
| 7 | **[V]** | Capitulation volume spike (top-decile 90d or >3σ) | ❌ | $87.73M 24h. Relight: a cascade session an order of magnitude larger. **Reachable only on a flush.** |
| 8 | **[V]** | LTH accumulation / holder concentration stabilizing | ✅ | Reserves at a decade low (~14.5M ETH), staking at a record 32.4%, exit queue zero, ~3M ETH queued to enter. |
| 9 | **[T]** | Macro catalyst neutral-to-positive | ⚠️ | Mixed and improved: Iran de-escalation is real and dated; against it, a 60.1%-priced September **hike** and CLARITY at record-low 27%. ⚠️ does not count. Relight: Sept hike odds <35% **or** a scheduled CLARITY floor vote. |

**Active denominator: 8** (gate 5 N/A). **Passed: [3, 8] = 2 gates. [V] among them: {3, 8} = 2.**

Thresholds (`compute.mjs thresholds 8`): 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4).
Note the /8 board reproduces 3/5/6/7 exactly — `ceil(7/9 × 8) = 7`, **not** 6. The reduction lowers no phase requirement.

> **A structural observation worth stating.** Of ETH's six [V] gates, the two that are lit (3, 8) are the two that measure **value and supply**. All four that measure **fear** (1, 2, 4, 7) are dark, and gate 4 is actively moving *away*. ETH's problem is not that it is expensive. It is that nobody is panicking about it.

---

## 5. Probability Matrix — ETH (D4: analyst-set)

**Anchor row.** Adjusted score 11 → baseline row **11–14**: Rally 30 / Range 35 / Retest 22 / Bear 13.

**§5 trend residual — mandatory boolean:**
> **Active downtrend (below a major MA **AND** making lower lows)? → NO.**
> ETH is 24.77% below the 200-week mean and 10.52% below a falling 200dma — but it is **not** making lower lows. The 40-session low of **$1,510.51 (2026-06-26)** has held for 38 sessions, price has rallied **+23.60%** off it, and spot sits **+4.74% above the 50dma** in a higher-low sequence.
> **No bearish residual applied.**
> **Consequence, stated so the guardrail is not orphaned:** the Deep-Value Override's **quarter-size throttle is OFF** — an Override firing would be **half**-size. (It cannot fire this report: mechanical 11 < 15.)

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | **28%** | $1,950 – $2,150 | $2,050 | The 200dma at $2,087.97 comes into range; needs a soft NFP cutting Sept hike odds, plus the ETF inflow streak extending to six weeks |
| **Range** | **36%** | $1,800 – $1,950 | $1,875 | The base case: continued coil above the 50dma with supply tightening and no fear event to resolve the tape either way |
| **Retest** | **23%** | $1,650 – $1,800 | $1,725 | 50dma ($1,783.58) fails and the bounce structure gives way; triggered by a hot NFP/CPI or a broad risk-off |
| **Bear** | **13%** | $1,450 – $1,650 | $1,550 | The $1,510.51 June low breaks — an ETH-specific liquidation flush that would light gates 1, 2 and 7 together |

**Deviations from the 11–14 anchor row:** Rally −2, Range +1, Retest +1, Bear 0. All well within the ±10-percentage-point band; none requires a >10pp reason line. This is close to the anchor deliberately — ETH's read is genuinely near the score row, and the D4 layer should not manufacture a view where the rubric already has one.

**Weighted EV (recomputed from the printed cells as the final step):**

```
0.28 × 2,050 =   574.00
0.36 × 1,875 =   675.00
0.23 × 1,725 =   396.75
0.13 × 1,550 =   201.50
                ────────
        EV   = 1,847.25     probabilities sum = 100 ✅
```
*Verified: `compute.mjs ev --spot 1868.21` → `ev: 1847.25, prob_sum_ok: true, vs_spot_pct: -1.12`.*

| | |
|---|---|
| **Weighted EV** | **$1,847.25** |
| Canonical spot | $1,868.21 |
| **EV vs spot** | **−1.12%** |
| **Realized trailing 2-week price change** | **−1.87%** |

EV mildly negative, realized two-week tape mildly negative. They agree in sign and are close in magnitude.

**Rally cap:** 28% ≤ 50% ✅ (and not modal).
**EV-floor consistency check:** EV-vs-spot is negative, but the **mechanical** score is 11 (not ≥15) and 3-day F&G is 27.33 (not ≤15). **Both arming conditions fail → no inconsistency flag.**
**Terminal-vs-extreme reconciliation:** not owed — the trend residual is not live.

---

## 6. Deployment Strategy — ETH

### 6.1 Position & Performance (Hard Rule 8)

`node tools/position.mjs eth` — **exit 0**, snapshot `position-snapshot/1`.

> ### ⚠️ BAND: **STALE** — age **3,011 minutes (50.2 hours)**, driver `holdings_as_of` (2026-08-01 15:51:56 UTC)
> **Descriptive use only.** It may **not** satisfy a phase-dependent unlock precondition and may **not** fill a realized ledger column.

| Field | Value |
|---|---|
| **Live quantity** | **0.00006517 ETH** (dust) |
| `trade_derived_qty` | 0.00006517 — identical |
| **Custody status** | **RECONCILED** — live balance agrees with the fill replay |
| Deposits / withdrawals / net external outflow | 0 / 0 / 0 |
| `off_venue_qty` | null — **not a cold-storage case** |
| **`basis.reliable`** | **FALSE** |
| Oversold (unbacked) quantity | **8.50642325 ETH** across **24 unbacked disposals** |
| `short_qty` | **null** — the snapshot states explicitly this is **not** a margin short |
| Average cost / total cost / unrealized PnL / ROI | **NOT REPORTED** |
| Realized PnL | **$447.02 — an UPPER BOUND, not a result** |
| **Attribution** | **UNTAGGED** — 1 open deal carries no tag |
| **Dry powder (stablecoins)** | **$14,408.87** (shared account balance) |
| Portfolio total | $19,790.26 · futures equity $0.00 |

**Why no cost basis is quoted.** `basis.reliable = false`: **24** unbacked disposals exceeded the replayed position by **8.5064 ETH** — coins sold whose acquisition the ledger never ingested. Custody is RECONCILED with zero withdrawals, so this is not cold storage; `short_qty` is null, so it is not a margin short. The fill history is incomplete. Per Hard Rule 8's basis carve-out, **no average cost, cost basis, unrealized PnL or ROI is reported**, and $447.02 realized is an upper bound. ETH carries the account's largest unbacked gap by a wide margin — 8.51 ETH against BTC's 0.034.

**Position Reconciliation — the ledger wins, and it says "unreadable."**

| Figure | Prior reports narrate | Ledger says | Delta |
|---|---|---|---|
| Phase 1A | ~5% filled at **~$1,844**, plus ~5% laddered $1,800–1,825 working | 0.00006517 ETH, no derivable basis, UNTAGGED | **Unresolvable** |
| Withdrawals | — | 0 | Rules out the off-venue explanation |
| Attribution | `FK-P1A` | none — 1 untagged open deal | Cannot resolve a phase-dependent precondition |

**Status: UNVERIFIED.** No deployment is sized against it and no PnL is claimed from it.

**Realized performance:** `performance_by_tag` is an **empty array**. Zero tagged deals exist. Nothing can be asserted about how ETH Phase 1A entries have actually performed, and nothing is.

**Dry powder yield benchmark:** **3.78%** (3-month T-bill). The $14,408.87 stablecoin balance is shared across the book with the BTC report published at this timestamp — it is **one pool, not two**. Any ETH tranche and any BTC tranche compete for the same dollars, which is worth stating because two reports each sizing "10% of the book" against the same $14.4K would double-commit it.

### 6.2 Phase board

**Total dry powder: ~95–100% (unresolvable — see §6.1). Real deployable stablecoin balance: $14,408.87, shared with BTC.**

| Phase | Size | Entry zone | Score condition | Gate condition | Status |
|---|---|---|---|---|---|
| **1A** | 10% | **$1,800 – $1,880** | ≥8 → **11 ✅** | ≥3/8, [V]≥2 → **2/8 ❌, [V] 2 ✅** | **GATE-BLOCKED by exactly 1 — D2 available, declined** |
| **1B** | 15% | $1,600 – $1,750 | ≥11 → **11 ✅ FIRST TIME** | ≥5/8, [V]≥3 → **2/8 ❌, [V] 2 ❌** | **BLOCKED on gates (short 3)** |
| **2** | 30% | $1,450 – $1,600 | ≥15 → ❌ (11) | ≥6/8, [V]≥3 → ❌ | FROZEN |
| **3** | 45% | requires a weekly capitulation candle | mechanical ≥17 → ❌ (11) | ≥7/8, [V]≥4 → ❌ | DRY |

**The headline mechanical event: Phase 1B's score condition is met for the first time in this series.** The 2026-07-27 calibration cut the 1B line from ≥13 to ≥11; ETH printed 10 on Aug-01 and 11 today. What moved was the valuation leg, on a *sourced decimal replacing a proxy* — not on a price move, not on a discretionary term. That is the cleanest possible way for a threshold to be crossed.

**And it changes nothing, which is the point.** 1B needs 5 of 8 gates with ≥3 from the [V] bucket. ETH has **2** gates and **2** [V]. Short by three. The binding constraint has simply moved from the score axis to the gate axis, and the gate axis is not close.

**Note the contrast with BTC, published at this timestamp.** BTC's mechanical 11 also clears the 1B line, but BTC carries a **−1.0 D1** that removes the cross on the adjusted score, and BTC holds 3 of 9 gates. Both assets are blocked; neither is blocked for the same reason. ETH is blocked purely by an absence of fear.

**D2 Analyst Conviction Path — AVAILABLE on Phase 1A, and DECLINED.**

| Condition | Requirement | ETH | Met? |
|---|---|---|---|
| Score condition | ≥8 | 11 | ✅ |
| Gate shortfall | **exactly 1** | 2 of 3 required | ✅ |
| [V] floor on **lit** gates | ≥2 | 2 lit | ✅ |
| Risk-on surcharge live? | must be OFF | corr 0.356 → OFF | ✅ |
| Phase eligible | 1A/1B/2 only | 1A | ✅ |
| Prior D5 stop-out in this phase within 10d | none | none | ✅ |

Every condition is met. It is declined, for three reasons stated in order of weight:

1. **The un-discretionary path to the same capital already exists.** Prior reports assign ~5% of the 1A tranche to a working ladder at $1,800–1,825. Under the partial-tranche rule, a pre-assigned remainder deploys in its own zone **without a fresh unlock**. A D2 unlock would purchase half-nominal authorization for capital that is already authorized — and pay for it with a hard price-only D5 stop and a 10-day bar on analyst channels in that phase. That is a strictly worse trade for the same dollars.
2. **The gate D2 would substitute for is a *fear* gate.** The only realistic substitution targets are gate 1 (sentiment streak) or gate 7 (capitulation). ETH's entire diagnosis is *cheap but not feared*. Writing a conviction case to supply the exact fear gate that is missing — because it is missing — is precisely the reasoning the D5 stop exists to punish.
3. **The position of record is unreadable.** With `basis.reliable = false` on 8.51 ETH of unbacked disposals and the snapshot STALE at 50.2 hours, a D5 stop line — defined relative to *the fill* — could not be set against a corroborated fill price.

This is the second consecutive report in which the conviction path has been available and declined. Both declines are logged in §9.5 for grading. If the channel is never used it should eventually be graded as dead weight; that judgment belongs to a calibration, not to this report.

**⚑ Deep-Value Override — evaluated, DOES NOT FIRE.** **Mechanical score 11 < 15 — dispositive.** Independently: 3-day F&G 27.33 is not ≤15, and the Override presupposes a corroborated deployed tranche the ledger cannot supply. No near-fire to log. Max drawdown from spot to the compound thesis line ($1,350): **−27.74%**, stated as standing disclosure; it purchases no loosening.

**Non-mechanical capital cap:** 0% deployed through D1/D2/Override channels against the 40% ceiling. Not binding.

### 6.3 Stops

| Tier | Level | Status |
|---|---|---|
| **Catastrophic floor** | **$1,300** | Unchanged. Strictly below the deepest named buy-zone floor. |
| **Compound thesis stop** | **$1,350 price AND mechanical score <12** | Unchanged. Score line 12 (ETH standard — not a pinned-score asset). |
| Deepest named buy-zone floor | **$1,450** (Phase 2 zone $1,450–1,600) | **corrected — see below** |
| D5 discretionary stops | **none** — zero analyst-channel tranches exist | — |

**Labeled measurement correction (not a stop migration).** Prior reports in this series ran the coherence check against a deepest-zone floor of **$1,470**, described as "per the prior report's convention," while the Phase 2 zone has been consistently printed as **$1,450–1,600**. The floor of that zone is **$1,450**. The coherence rule is explicit that where multiple zones are named with different floors, the **single lowest** governs. This report uses **$1,450**. **No stop parameter moved** — the catastrophic floor is $1,300 in both readings, and the corrected (lower) reference makes the test *stricter*, not looser. It is labeled here so the change is not silent and so the comparison stays like-for-like in any future calibration.

> **COHERENCE CHECK: catastrophic stop $1,300 strictly below deepest active buy-zone floor $1,450? → PASS.**
> Verified: `compute.mjs stop-coherence --catastrophic 1300 --floor 1450` → `pass: true`.
> No prospective ladder is named below $1,450 anywhere in this report, so no post-activation re-stop or atomic activation sequence is owed. No "stop realignment owed" flag.

**Compound stop disclosure (carried, no migration).** Mechanical score is **11**, which is **<12**, so the score axis **is satisfied** and the stop is effectively **price-gated at $1,350** until the score re-crosses 12. This is a **genuine improvement over Aug-01**, and it should be recorded as one: the Aug-01 report disclosed an *erosion* — the score had fallen 11 → 10, so restoring two-key protection required regaining two points instead of one. The valuation leg's upgrade regains one of them. ETH now sits **one point** from restoring the compound stop's second key. The concrete path: any of gate-adjacent moves that lift a leg — a weekly RSI print below 40 (currently 41.96, one band edge away, worth +1) would do it on its own.

**Stop Migration Ledger:**

| Parameter | Tier | Old | New | Direction | Rationale |
|---|---|---|---|---|---|
| Checkpoint date | checkpoint date | 2026-08-02 | **2026-08-09** | forward roll | The Aug-02 checkpoint resolved on schedule and did **not** fire (0 of 2 required weekly closes below $1,350). Rolls to the next weekly close. **D6 exception 3** (calendar validity), not a discretionary widening. |

No other stop parameter changed value. **D6 ratchet: compliant** — nothing moved away from price; the one reference figure that changed ($1,470 → $1,450) moved the coherence test *toward* strictness.

**Checkpoint prognosis.** Checkpoint **Sunday, 2026-08-09, 00:00 UTC weekly close** — verified a real weekly boundary on the crypto weekly calendar (week-start UTC, the same boundary used for the RSI computation); crypto venues trade continuously, so no holiday or abbreviated-session correction applies. It **fires iff** ≥2 consecutive weekly closes print below $1,350 **and** the mechanical score is <12. Spot $1,868.21 sits **38.39% above** the line, a distance of **8.99× the 5-day ADR of $57.62** (sessions 2026-07-30 → 08-03, none abbreviated, none excluded). Closes below the line: **0 of the 2 required** — the checkpoint therefore **cannot** fire on Aug-09 regardless of price. Structural statement, not forecast.

**Tier-1 US release between this report and the Aug-09 checkpoint: YES — nonfarm payrolls, Friday 2026-08-07, 08:30 ET.** Named as part of the falsifier: a hot print raises September hike odds above 60.1% and pressures ETH toward the Retest band and the $1,783.58 50dma; a soft print cuts them and puts the $2,087.97 200dma in play. Neither can produce two sub-$1,350 weekly closes by Aug-09, so the checkpoint's unfireable status is robust to it.

---

## 7. Exit / Trim Framework — ETH

Every score condition below reads the **MECHANICAL** score (11). D1 is 0.0 here in any case, but the rule is stated because it binds regardless.

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | peak 13 (2026-06 series) → ≤7 | **11** | ❌ not triggered — and the score just moved **up** |
| F&G ≥75 sustained 7d AND weekly RSI >70 | — | F&G 27.33, RSI 41.96 | ❌ nowhere near |
| MVRV-Z >3 or drawdown <10% with vertical 30d return | — | MVRV-Z **negative**, drawdown 62.3% | ❌ maximally inverted — this is the buy side |
| Mechanical score ≤3 AND price ≥40% above blended cost | — | score 11; blended cost **unknowable** | ❌ not triggered; second limb unevaluable while `basis.reliable=false` |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime | — | trailing month **+$365.2M**; and the ≥5-consecutive-green-session precondition was not met during any held position's life | ❌ not triggered — the precondition fails, not just the trigger |
| Narrative break | regulatory ban / founder fraud / critical security breach / irreparable tokenomics change | none — the Coldcard exploit is Bitcoin-only and implicates no ETH keys | ❌ not triggered |

**Exit status: NONE. No trim executed, no partial exit. Remaining position: unresolvable per §6.1.**

---

## 8. Critical Watchlist

| Date / Time (ET) | Event | Tier | ETH impact |
|---|---|---|---|
| **Fri 2026-08-07, 08:30** | **Nonfarm payrolls (July Employment Situation)** | **TIER 1** | The only tier-1 release in the 5-session window. Hot → Sept hike odds up from 60.1%, pressure toward the 50dma at $1,783.58. Soft → odds down, the $2,087.97 200dma comes into range. Date verified: BLS Employment Situation is the first Friday of the month; `compute.mjs tier1 --from 2026-08-03 --sessions 5` returns exactly this event and no other. |
| Fri 2026-08-07 | Last Senate working day before recess | high | Final theoretical CLARITY window. Odds 27%. Non-event is the base case; market structure matters more to ETH's application layer than to BTC. |
| Mon 2026-08-10 | Senate state work period begins | high | CLARITY formally dead until September. |
| **Sun 2026-08-09, 00:00 UTC** | **Weekly close — stop checkpoint** | — | Cannot fire (0 of 2 closes). Also the weekly RSI print — **below 40 restores the compound stop's second key**. |
| **Wed 2026-08-12, 08:30** | **CPI (July)** | **TIER 1** | Outside the 5-session window; enumerated because it lands before the following checkpoint and dominates the September hike decision. |
| Thu 2026-08-13 / Fri 2026-08-14 | PPI / Retail sales | tier 2 | — |
| **Tue–Wed 2026-09-15/16** | **FOMC decision** | **TIER 1** | 60.1% priced for a hike. Largest scheduled risk to the thesis. |
| Weekly | ETH spot ETF flows (Farside/SoSoValue) | high | A 5th consecutive inflow week extends the divergence vs BTC and pushes gate 4 further from lighting. A reversal is the only path to gate 4. |
| Weekly | Exchange reserves / staking ratio | medium | Confirms the holder leg's 3/3. Watch whether the entry queue drains. |
| Weekly | Validator entry queue (~3M ETH) / exit queue (zero) | medium | An exit-queue reopening would be the first genuine crack in the supply story. |

**Tier-1 completeness statement:** the 5-session window (2026-08-04 → 08-10) contains exactly one tier-1 US release — NFP on 2026-08-07 — and it is enumerated. This report is **not** an incomplete-data report on the calendar dimension.

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

ETH is cheap in a way that is now *measured* rather than asserted, and the market does not care.

Take the valuation finding seriously for a moment, because it is the only thing in this report that genuinely changed. MVRV-Z at −1.12 on the last sourceable date means the aggregate cost basis of every ETH in existence sits around **$2,277** against a spot of $1,868. The median holder is 18% underwater. Historically ETH has been here three times — late 2018, mid-2022, and now — and each prior visit preceded a major recovery. It is also true, and the same sources say so, that the metric stayed negative for *months* before price turned. Cheapness is a condition, not a catalyst.

What sits alongside it is a supply picture that is close to remarkable: exchange reserves at a decade low, staking at a record 32.4% of supply, an exit queue at zero with three million ETH waiting to get in, and — unlike Bitcoin — a fourth consecutive week of ETF inflows. The float is shrinking through every regime, rallies and corrections alike.

And here is the problem, which is the same problem this series has been writing for a month and which the data keeps confirming: **that is a bullish supply story, not a fear signal, and this framework buys fear.** Funding is positive. Liquidations are ordinary. The lowest fear print in ten sessions is 25. ETH is 23.6% off its June low and sitting above its 50-day average in a higher-low sequence. Four of six [V] gates are dark and one of them — ETF flows — is moving *away* from lighting at speed.

So the composite reaches 11, unlocks 1B on score for the first time, and 1B stays shut because two gates is not five. I think that is the correct answer rather than a frustrating one. The framework's entire edge across the May–June cascade was refusing to spend the 15/30/45 tranches on partial evidence, and "cheap with no panic" is partial evidence by construction.

The honest bear case for ETH is not that it is expensive. It is that **24.77% below its 200-week mean** is a very long way from the structural anchor BTC is currently sitting on, that the 200dma is falling at −5.55% per 20 sessions (steeper than BTC's −3.62%), and that ETH still carries ~$1.1B of net ETF outflows for the year even after July's inflows. Cheap assets in falling structures can stay cheap for a long time, and ETH has the weaker floor of the two majors.

### 9.2 What the rubric structurally cannot see

1. **The supply squeeze is a *conditional* bull factor and the rubric scores it unconditionally.** *(Ambiguous, leaning bullish.)* The holder leg gives 3/3 for reserves falling and staking rising. What it cannot express is that a shrinking float only produces price when demand arrives — and demand, measured as ETF flows, is +$365M for July against **−$1.1B for the year**. The supply story is real and the demand story is a four-week-old recovery from a much larger hole.
2. **The macro gate is binary and the macro risk is dated.** *(Bearish, shared with BTC.)* A 60.1% priced September hike and CLARITY at a record-low 27% read identically to "no catalyst" through gate 9. Market structure legislation matters *more* to ETH than to BTC, because ETH's investment case runs through applications that need regulatory definition.
3. **ETH's absence from the Coldcard risk set.** *(Bullish, relatively.)* The companion BTC report carries a −1.0 D1 principally because a live custody exploit contaminates the premise of BTC's holder leg. ETH's holder leg rests on **staking and exchange withdrawal**, and Coldcard is a Bitcoin-only device. Nothing in ETH's holder evidence is impaired. This is a *relative* factor and it is the reason the two assets' D1 terms differ.
4. **The 200-week distance asymmetry.** *(Bearish.)* Gate 6 is a boolean, so it reads the same "dark" for a price 9% below the mean and a price 25% below it. ETH is 24.77% below; BTC is 0.43% above. The boolean discards the magnitude, and the magnitude is the difference between a floor you can see and one you cannot.
5. **System dry powder contracting.** *(Bearish, shared.)* Aggregate stablecoin supply −3.33% over 90 days.

### 9.3 The D1 term

> ## **D1 = 0.0** (mechanical 11 → raw 11.0 → adjusted **11**)

**A negative term was constructed and declined.** Factors 2, 4 and 5 above are all real, all sourced, and all structurally invisible to the legs; on the companion BTC report, factors of that character carried a −1.0. The case for −0.5 here was genuine and it is worth recording precisely why it was not taken.

Under ETH's half-**down** rounding convention, a −0.5 term takes 11.0 → 10.5 → **10**, which would delete the Phase 1B score-line crossing this report just achieved. That crossing was earned by a **sourced decimal replacing a three-report-old proxy** — the single cleanest kind of evidence this framework can receive. Using discretion to immediately erase a mechanical improvement that the tape and the data genuinely produced is discretion *overriding* evidence rather than supplementing it, which is the one thing the D1 layer is explicitly not for. The macro overhang is shared with BTC and is already expressed there; ETH's asset-specific evidence moved the other way this week (valuation upgrade, fourth inflow week, staking record), and marking it down on someone else's risk factors would be double-counting a shared macro across two reports.

**A positive term was also considered and declined,** for the reason the Aug-01 report gave and which still holds: any +0.5 or more would be built from the supply-squeeze evidence the holder leg already scores 3/3, which is prohibited double-counting.

**Symmetry note for the record.** This is the second report in which the D1 layer has been exercised on both majors simultaneously, and the two came out different: **−1.0 on BTC, 0.0 on ETH.** The difference is entirely attributable to one asset-specific factor (the Coldcard custody exploit) plus the rounding-convention asymmetry. A layer that produced the same number for both assets would be measuring the analyst's mood; a layer that discriminates is measuring something.

### 9.4 Discretionary actions taken and declined

| Channel | Phase | Available? | Action | Reason |
|---|---|---|---|---|
| **D1** | — | yes | **0.0 — declined both directions** | §9.3; both the −0.5 and the +0.5 cases are stated |
| **D2** | 1A | **YES — all six conditions met** | **DECLINED** | §6.2: the partial-tranche rule already authorizes the same capital without a D5 stop or a 10-day phase bar; the gate D2 would substitute for is a *fear* gate and ETH's diagnosis is *no fear*; and the position of record cannot supply a corroborated fill against which to set the D5 line |
| **D2** | 1B | **NO** | n/a | Short **three** gates (2 of 5), not exactly one; [V] floor also fails (2 vs 3) |
| **D4** | — | yes | **TAKEN, minimally** | Cells within 2pp of the 11–14 anchor row; EV recomputed from the printed cells |
| **Override** | — | **NO** | n/a | Mechanical 11 < 15, dispositive |

### 9.5 Discretion Ledger (D7)

| Date | Channel | The call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 | ETH 0.0 — a +1.0 declined (sole enabler of a threshold cross; and 1B gate-blocked regardless) | — | — | — | **RESOLVED — vindicated** | — |
| 2026-08-01 | D2 | ETH 1A conviction path evaluated, **declined** (first in framework history) | — | — | — | closed (declined) | — |
| **2026-08-03** | **D1** | **ETH 0.0** — a −0.5 constructed on shared macro and declined (would erase a mechanically-earned threshold cross under half-down rounding); a +0.5 declined as double-counting the holder leg | — | — | — | **closed (zero, both directions argued)** | — |
| **2026-08-03** | **D2** | **ETH 1A available for the second consecutive report, declined again** — un-discretionary path to the same capital exists; the substituted gate would be a *fear* gate | — | — | If ETH's 1A remainder is never deployed and price rallies past $1,950 without the ladder filling, this decline graded a miss | **LIVE (decline logged for grading)** | — |
| **2026-08-03** | **D4** | Cells set near-anchor: Rally 28 / Range 36 / Retest 23 / Bear 13; EV −1.12% vs spot | — | — | Realized 2w price change vs EV sign, graded next report | **LIVE** | — |
| 2026-08-03 | D1 | *(cross-reference)* BTC **−1.0** taken in the companion report — see `btc_fallen_knives_20260803_1411.md` | — | — | — | LIVE | — |

**The Aug-01 D1 decline is now gradeable and it graded correctly.** That report declined a +1.0 that would have lifted ETH to 11 on discretion, reasoning that a discretionary term should not be the sole enabler of a threshold cross. Two days later the score reached 11 **mechanically**, on sourced data. The decline cost nothing (1B was and remains gate-blocked) and preserved the distinction between an earned threshold and a bought one. This is the first entry in the Discretion Ledger to resolve, and it resolved in favor of restraint.

**No D5 stops are outstanding**: zero analyst-channel tranches have ever been opened.

### 9.6 What would change my mind

- **Bullish flip:** a weekly close above **$1,950** *plus* a fifth consecutive ETF inflow week. That is trend-structure repair with a demand confirmation, and it would put the $2,087.97 200dma in play. *Falsifier: no weekly close above $1,950 by 2026-08-31.*
- **Bearish flip / the one I actually want:** a daily close below **$1,510.51** (the 2026-06-26 campaign low). That prints the fresh lower-low the trend residual currently denies and is the only realistic route to lighting gates 1, 2 and 7 together — which is the only realistic route to a 1B unlock. *Falsifier: the low holds through 2026-08-31.*
- **The signal I would take most seriously that isn't in the rubric:** the ETH validator **exit queue reopening** from zero while the entry queue drains. That would be the first crack in a supply story that has held through every regime this year, and it would arrive well before price reflected it.

---

## 10. Bull vs Bear Scorecard — ETH

**Bull (8):**
1. ✅ **MVRV-Z negative** (−1.121 sourced 2026-07-04; live ratio 0.820) — cheapest since December 2018; sign robust by a 22% margin
2. ✅ Implied realized price **$2,277** vs spot $1,868 — the median holder is ~18% underwater
3. ✅ Exchange reserves at a **decade low (~14.5M ETH)**; >6M ETH withdrawn since late 2023
4. ✅ Staking at a **record 32.4%**; ~3M ETH in the entry queue, **exit queue at zero**
5. ✅ ETF flows: July **+$365.2M**, **four consecutive weekly inflow weeks** — while BTC's turned red
6. ✅ **+23.60%** off the 2026-06-26 campaign low, **+4.74%** above the 50dma, higher-low sequence intact
7. ✅ Funding **+3.03% annualized** — half BTC's leverage; a cleaner book
8. ✅ Drawdown **−62.25%** from ATH — the deepest of the majors

**Bear (8):**
1. ❌ **−24.77% below the 200-week mean** — gate 6 "none-in-regime"; BTC is +0.43% above its own
2. ❌ 200dma falling at **−5.55%/20 sessions**, steeper than BTC's −3.62%; 50dma below 200dma
3. ❌ Fed: **60.1% priced probability of a September hike**; three hawkish dissents
4. ❌ CLARITY Act punted; **record-low 27%** 2026 odds — and market structure matters more to ETH
5. ❌ F&G 3d avg **27.33**, gate-1 streak **0** — fear, not panic
6. ❌ Weekly RSI **41.96 and rising** — momentum repairing, not exhausting; the leg scores only 1
7. ❌ ETF flows **still ~−$1.1B net for 2026** despite the July recovery
8. ❌ Aggregate stablecoin supply **−3.33% over 90 days**; liquidations ordinary at $87.7M

> **Net: 8 bull / 8 bear → exactly balanced.** The collar's "within 1 of balanced" limb is met, as is the |EV-vs-spot| limb.

---

## 11. Change Log vs 2026-08-01 (2 days)

| Factor | 2026-08-01 | 2026-08-03 | Direction |
|---|---|---|---|
| Canonical spot | $1,870.40 | **$1,868.21** | −0.12% |
| Sentiment leg | 2 (26.67) | **2 (27.33)** | flat |
| Momentum leg | 1 (RSI 41.40) | **1 (RSI 41.96)** | flat band, RSI rising |
| **Valuation leg** | **4** (ratio proxy 0.81, decimal UNOBTAINABLE) | **5** (MVRV-Z **negative**, sourced) | **⬆ +1 — the only leg that moved** |
| Capitulation leg | 0 | **0** | flat |
| Holder leg | 3 | **3** | flat |
| **Mechanical score** | **10** | **11** | **⬆ +1** |
| D1 term | 0.0 | **0.0** | flat (both directions argued and declined) |
| **Adjusted score** | 10 | **11** | **⬆ crosses the Phase 1B score line, first time** |
| Gates | 2/8 [3,8], [V] 2 | **2/8 [3,8], [V] 2** | flat |
| **MVRV-Z debt clock** | **report 3, UNOBTAINABLE** | **DISCHARGED — sourced decimal** | **resolved ✅** |
| Phase 1B block | score **AND** gates | **gates only** | binding constraint moved |
| Compound stop 2nd key | 2 points from restoration | **1 point** | ⬆ improved |
| Deepest-zone-floor reference | $1,470 | **$1,450** (labeled correction) | stricter test, no stop moved |
| EV vs spot | −2.37% | **−1.12%** | less negative |
| Realized 2w change | +0.45% | **−1.87%** | ↓ |
| 30d corr vs SPX | 0.317 | **0.356** | ↑ still mild |
| FR companion | 9 (Channel B) — tripwire **FIRED** | **8 (Channel B)** | ↓ below the tripwire; **the Aug-01 debt remains undischarged** |
| Position band | STALE 26h | **STALE 50.2h** | ↓ **degrading — 22h from EXPIRED** |
| Dry powder | $14,288.54 | **$14,408.87** | +$120 (shared with BTC) |
| T-bill benchmark | 3.68% | **3.78%** | ↑ |

---

## 12. Companion Flying Rocket Score (Hard Rule 5 — computed, not estimated)

`compute.mjs fr-companion --market {...} --rounding half-down`

| Field | Value |
|---|---|
| **Channel** | **B** (counter-trend bounce) — routing verified: −62.31% below the 1-yr high (>20%), 200dma falling (−5.55%/20 sessions), price 10.52% beneath it |
| Legs (Channel B) | rally_extension 3 · local_exhaustion 1 · resistance 1 · bear_structure 2 · relative_sentiment 1 |
| Penalty | 0 (squeeze tier: none; maturity: none) |
| **FR composite** | **8 / 20** |
| Channel B Phase 1A line | 13 — **short by 5** |
| Phase-of-cycle cap | **not applied** (Channel B is the live channel; cap value would be 8) |
| Confidence | full — no missing inputs |

> **Cross-validation: CONSISTENT ✅ — label UNQUALIFIED.**
> FK **11** (mechanical) / FR **8**. Both < 12, so Hard Rule 5's both-≥12 condition is not met. The label carries full evidentiary weight because the Channel A phase-of-cycle cap is **not binding** — Channel B is the live channel.
> **Disclosed honestly, as the Aug-01 report did:** 11 and 8 are *not strongly inverse*. Per the FR skill's own Channel B note this is expected rather than anomalous — the two frameworks score different objects on different horizons (FK: accumulation value; FR-B: whether a specific counter-trend bounce is dying into resistance). ETH's rally_extension leg scores 3 precisely because the +23.6% bounce that helps FK's structural read is what FR-B measures as extension.
> **FR≥9 tripwire: NOT fired this report** (8 < 9).

> ### ⚠️ OUTSTANDING DEBT — a standalone Flying Rocket report on ETH is still OWED
> The 2026-08-01 ETH companion printed **exactly 9**, firing trigger (ii). That report flagged the obligation as **OWED and NOT DISCHARGED**, and it has not been run since. Today's companion prints **8**, which creates **no new** trigger — but it does not retire the old one. **Action item: run `/flying-rocket-analytics eth`.** The most recent ETH FR report on disk is `eth_flying_rocket_20260731_0426.md`, which predates the trigger.

---

## 13. Strategic Verdict — ETH

**Adjusted score 11/20 · Mechanical 11/20 · D1 0.0 · Weighted EV $1,847.25 · EV vs spot −1.12% · Realized 2w −1.87% · F&G 27.33 (Fear) · Gates 2/8 ([V] 2) · Stance: HOLD, work the pre-assigned 1A remainder, authorize nothing new**

> **Verdict-Confidence Collar: ACTIVE.** |EV-vs-spot| **1.12% < 2%** and the scorecard is **8–8, exactly balanced** — two limbs met. (Mechanical 11 is outside the 6–10 band.) **No directional regime resolution is claimed anywhere in this report.** Every forward statement carries a probability or an IF→THEN plus a named falsifier.

The valuable thing that happened this week was epistemic, not financial. For three reports this framework carried ETH's valuation leg on a proxy and told you so, refusing a widely-circulated "−0.7, seven-year low" figure because it traced to one article written two months earlier at a materially different price. Today the decimal exists: **−1.121 on 2026-07-04**, from a queryable series whose scale was verified against an independent provider on BTC to within 0.04. The implied aggregate cost basis is **$2,277** against a spot of **$1,868**, and the sign of the Z-score is arithmetic rather than estimate — it would take a 22% rally just to reach zero. The leg goes to its maximum of 5, the composite reaches 11, and Phase 1B's score line is cleared for the first time in this series. That is exactly how a threshold should be crossed: by data replacing a placeholder, not by a discretionary term and not by a price move.

It buys nothing, and I want to be direct about why that is the right outcome rather than a bureaucratic one. Phase 1B needs five of eight gates with three from the fear/value bucket; ETH holds **two**, both of them value gates. Every gate that measures actual fear is dark — sentiment streak zero, weekly RSI rising, capitulation liquidations ordinary, and ETF flows running *four consecutive inflow weeks* in the exact opposite direction from the outflows gate 4 requires. ETH is cheap, its float is shrinking through every regime, and nobody is panicking about any of it. This framework deploys its 15/30/45 tranches into panic, and it survived the May–June cascade precisely by refusing to spend them on half the evidence. "Cheap without fear" is half the evidence. The binding constraint has simply moved from the score axis to the gate axis, and on the gate axis ETH is not close.

The Analyst Conviction Path was available on Phase 1A for the second consecutive report — every one of its six conditions met — and I declined it again, for a reason I think is the most defensible one available: **the gate D2 would substitute for is a fear gate.** Writing a conviction case to supply the exact missing evidence, *because* it is missing, is the pattern the D5 hard stop exists to punish. And the un-discretionary route to the same dollars already exists — the partial-tranche rule lets the pre-assigned 1A remainder work the $1,800–1,825 ladder with no fresh unlock, no price-only stop and no ten-day phase bar. A D2 unlock would buy already-authorized capital at a strictly worse price. Both declines are logged for grading, and if this channel is never used, some future calibration should say so plainly. Meanwhile the real obstacle is the same one as on BTC and it is not a market judgment: the ledger returns dust with `basis.reliable = false` on **8.51 ETH of unbacked disposals**, zero deal tags, and a snapshot 50.2 hours old — inside the STALE band, which Hard Rule 8 bars from resolving any phase-dependent question, and 22 hours from expiring into a forced cold start.

### Numbered action items

1. **Refresh the position snapshot — before anything else.** `POST /link`, then `node tools/position.mjs eth`. Band must be **FRESH (≤12h)**. At 50.2 hours it is 22 hours from EXPIRED, after which Hard Rule 4 forces a cold start and the ETH 1A question becomes unanswerable rather than merely unanswered.
2. **Work the pre-assigned Phase 1A remainder in the $1,800–1,825 ladder — no fresh unlock required, no new authorization granted.** This is the only capital movement this report contemplates. Tag **`FK-P1A`** via `PUT /api/investments/deal-note`, first note line `report=reports/eth_fallen_knives_20260803_1411.md`. Remember the **$14,408.87 stablecoin pool is shared with BTC** — do not size an ETH tranche and a BTC tranche against the same dollars.
3. **Run `/flying-rocket-analytics eth`.** A standalone FR report has been owed since the 2026-08-01 companion printed 9 and has not been discharged. Today's 8 creates no new trigger but retires no old one.
4. **Tag the untagged open deal and fix the unbacked-disposal defect.** 24 disposals exceed the replay by 8.5064 ETH — the account's largest gap by a wide margin. Until acquisitions are ingested, this framework can quote no ETH cost basis, no unrealized PnL and no ROI, and the §7 "price ≥40% above blended cost" trim limb is permanently unevaluable.
5. **Deploy nothing into 1B / 2 / 3.** 1B is short three gates and one [V] gate; the Override is dead at mechanical 11 < 15; D2 is unavailable on 1B.
6. **Watch the weekly RSI print on 2026-08-09.** Currently 41.96 — one band edge (below 40) from a +1 momentum leg, which would take the score to 12 and **restore the compound stop's second key**. This is the single cheapest available improvement to the book's protection.
7. **Watch the ETF flow streak.** A fifth consecutive inflow week extends the divergence vs BTC and pushes gate 4 further from lighting; a decisive reversal is the *only* realistic route to gate 4 relighting.
8. **Watch the validator exit queue.** It is at zero with ~3M ETH waiting to enter. A reopening would be the first crack in the supply story and would arrive before price reflected it.
9. **Hold stops unchanged** — compound $1,350 AND mechanical score <12; catastrophic floor $1,300; coherence PASS against the corrected $1,450 floor. Checkpoint rolls to **Sunday 2026-08-09 00:00 UTC** and cannot fire (0 of 2 closes).
10. **Treat NFP (2026-08-07 08:30 ET) as the week's pivot, not a signal.** Do not pre-position.

> ## The Pattern
>
> **IF** ETH closes a week above **$1,950** **AND** the ETF inflow streak extends to a fifth week → **THEN** trend-structure repair is confirmed with a demand leg behind it, the $2,087.97 200dma comes into range, and the accumulation thesis shifts from *waiting for fear* to *riding a repair*. *Falsifier: no weekly close above $1,950 by 2026-08-31.*
>
> **IF** ETH prints a daily close below **$1,510.51** → **THEN** the trend residual turns on, the Override throttles to quarter-size, and gates 1, 2 and 7 come into range together — the only realistic route to unlocking the 15% tranche. *This is the outcome the framework is built to want. Falsifier: the low holds through 2026-08-31.*
>
> **IF** the supply squeeze continues and demand does not arrive → **THEN** ETH stays cheap and stays quiet, no gate lights, nothing unlocks, and the dry powder keeps earning 3.78%. *This is the modal path at 36%.*
>
> **IF** the weekly RSI prints below 40 on 2026-08-09 → **THEN** the momentum leg goes to 2, the score to 12, and the compound stop's second key is restored — a protective improvement that costs nothing and requires no decision.

---

*Cheap is a condition, not a catalyst. ETH's aggregate holder is 18% underwater and its float is shrinking through every regime — and the framework still will not buy the big tranches, because two of eight gates is two of eight gates. Patience is alpha; at $14,408.87 against a 3.78% bill, it costs about $45 a month, shared with Bitcoin.*

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-ETH-20260803-1411 | UNVERIFIED | crypto |
| 1B | FK-P1B-ETH-20260803-1411 | LOCKED | crypto |
| 2 | FK-P2-ETH-20260803-1411 | LOCKED | crypto |
| 3 | FK-P3-ETH-20260803-1411 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: eth_fallen_knives_20260803_1411.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "ETH",
  "date": "2026-08-03",
  "spot": { "value": 1868.21, "source": "median of 4 synchronized live quotes: Binance ETHUSDT $1,870.08 / Coinbase ETH-USD $1,868.48 / Kraken ETHUSD $1,867.94 / CoinGecko $1,866.95 (all 2026-08-03 ~18:03 UTC); spread 0.168%, all live; Yahoo ETH-USD $1,868.34 EXCLUDED as a frozen bar close. SKILL-mandated median used, NOT the tool's priority-first spot.canonical ($1,866.95) — delta -0.067%, changes no band, gate boolean or cap tier" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 1, "valuation": 5, "capitulation": 0, "holder": 3 },
    "discretionary": 0,
    "mechanical": 11,
    "raw": 11,
    "adjusted": 11,
    "rounding": "half-down"
  },
  "gates": { "active": 8, "na": [5], "passed": [3, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 28, "low": 1950, "high": 2150 },
      { "name": "Range", "p": 36, "low": 1800, "high": 1950 },
      { "name": "Retest", "p": 23, "low": 1650, "high": 1800 },
      { "name": "Bear", "p": 13, "low": 1450, "high": 1650 }
    ],
    "stated_ev": 1847.25,
    "vs_spot_pct": -1.12
  },
  "deployment": {
    "deployed_pct": 5,
    "dry_pct": 95,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "1800-1880 zone, spot 1868.21 INSIDE it; prior reports narrate ~5% filled plus ~5% laddered 1800-1825 working. NO entry_price is asserted: basis.reliable=false (24 unbacked disposals exceed the replay by 8.5064 ETH) and the live quantity is dust, so encoding a numeric fill would assert a cost basis this report explicitly declines to state. Status UNVERIFIED pending a FRESH snapshot. Gate count 2/8<3 blocks NEW 1A authorization; the pre-assigned remainder needs no fresh unlock (partial-tranche rule) and is the only capital movement this report contemplates", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "1600-1750 — SCORE CONDITION MET FOR THE FIRST TIME IN THIS SERIES (11>=11 under the 2026-07-27 cut line), on a SOURCED MVRV-Z decimal replacing a three-report-old proxy, not on a price move and not on discretion. Blocked on GATES alone: 2/8<5 and [V] 2<3, short by three. D2 unavailable (short by 3, not exactly 1; [V] floor also fails)", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "1450-1600 frozen (score 11<15, gates 2/8<6)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 1300,
    "deepest_zone_floor": 1450,
    "compound": { "price": 1350, "score_line": 12 },
    "note": "NO stop parameter changed value. Mechanical score 11<12, so the compound stop's score axis IS satisfied — stop effectively price-gated at $1,350 until the score re-crosses 12. This is a GENUINE IMPROVEMENT over Aug-01, recorded as such: that report disclosed an EROSION (score 11->10 meant restoring two-key protection required regaining two points instead of one); the valuation leg's upgrade regains one, so ETH is now ONE point from restoring the second key — a weekly RSI print below 40 (currently 41.96, one band edge away) would do it alone. LABELED MEASUREMENT CORRECTION, not a stop migration: prior reports ran the coherence check against a deepest-zone floor of $1,470 'per the prior report's convention' while the Phase 2 zone has consistently printed $1,450-1,600. The coherence rule mandates the single LOWEST named floor, so $1,450 governs. No stop parameter moved and the corrected reference makes the test STRICTER, not looser. Coherence: catastrophic $1,300 strictly below $1,450 = PASS (compute.mjs stop-coherence pass:true). No D5 stops — zero analyst-channel tranches exist; the available D2 unlock was DECLINED so no D5 stop attaches. Max drawdown spot-to-compound-line -27.74%, disclosed; purchases no loosening. D6 ratchet: compliant.",
    "migration": [
      { "parameter": "checkpoint date", "tier": "checkpoint date", "old": "2026-08-02", "new": "2026-08-09", "direction": "forward roll", "rationale": "The Aug-02 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below 1350). Rolls to the next weekly close. D6 exception 3 — calendar validity, not a discretionary widening." }
    ],
    "checkpoint": {
      "date": "2026-08-09",
      "line": 1350,
      "condition": ">=2 consecutive weekly closes <1350 AND mechanical score <12",
      "closes_below": 0,
      "adr": 57.62,
      "dist_x_adr": 8.99,
      "side": "spot 38.39% above line; structurally cannot fire (0 of 2 required closes). Tier-1 release BEFORE this checkpoint: YES — nonfarm payrolls Fri 2026-08-07 08:30 ET, named in the falsifier: hot print raises Sept hike odds above 60.1% and pressures ETH toward the 50dma at 1783.58; soft print cuts them and puts the 200dma at 2087.97 in play. NFP cannot produce two sub-1350 weekly closes by Aug-09. Next after: CPI Wed 2026-08-12 08:30 ET."
    }
  },
  "companion_fr": {
    "score": 8,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 62.31, "ma200_falling": true, "ma200_slope20_pct": -5.55, "price_below_ma200_pct": -10.52 },
    "legs_channel_b": { "rally_extension": 3, "local_momentum": 1, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 1510.51, "low_40s_date": "2026-06-26", "bounce_pct": 23.60, "daily_rsi14": 51.37, "weekly_rsi14": 41.96, "bounce_age_sessions": 38, "funding_annualized_pct": 3.03 },
    "gates_note": "Channel B Phase 1A line 13 — short by 5. Penalty 0 (squeeze tier none, maturity none). Confidence full, no missing inputs.",
    "cross_validation": "consistent — FK 11 (mechanical) / FR 8, both <12 so Hard Rule 5's both->=12 condition is NOT met. Label UNQUALIFIED because the Channel A cap is not binding (Channel B is the live channel). DISCLOSED HONESTLY, as on Aug-01: 11 and 8 are NOT strongly inverse. Per the FR skill's own Channel B note this is expected rather than anomalous — the frameworks score different objects on different horizons (FK: accumulation value; FR-B: whether a specific counter-trend bounce is dying into resistance). ETH's rally_extension leg scores 3 precisely because the +23.6% bounce that helps FK's structural read is what FR-B measures as extension.",
    "standalone_report_owed": true,
    "standalone_report_trigger": "CARRIED FORWARD, NOT NEW: the 2026-08-01 ETH companion printed exactly 9, firing trigger (ii). That report flagged it OWED and NOT DISCHARGED and it has not been run since — the newest ETH FR report on disk is eth_flying_rocket_20260731_0426.md, which PREDATES the trigger. Today's companion prints 8, which creates NO new trigger but retires no old one. Action: run /flying-rocket-analytics eth."
  },
  "position": {
    "source": "tools/position.mjs eth",
    "band": "STALE",
    "age_min": 3011,
    "age_driver": "holdings_as_of",
    "holdings_as_of": "2026-08-01T15:51:56Z",
    "generated_at": "2026-08-01T15:54:04Z",
    "custody_status": "RECONCILED",
    "qty": "0.00006517",
    "trade_derived_qty": "0.00006517",
    "off_venue_qty": null,
    "withdrawn_qty": "0",
    "basis_reliable": false,
    "oversold_qty": "8.50642325",
    "unbacked_disposal_count": 24,
    "short_qty": null,
    "avg_cost_usd": null,
    "total_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd_upper_bound": 447.02,
    "attribution": "UNTAGGED",
    "untagged_open_deals": 1,
    "performance_by_tag": [],
    "dry_powder_stable_usd": 14408.87,
    "dry_powder_note": "SHARED POOL — the same $14,408.87 backs the BTC report published at this timestamp. Two reports each sizing '10% of the book' against it would double-commit the same dollars.",
    "portfolio_total_usd": 19790.26,
    "futures_equity_usd": 0.0,
    "note": "STALE band at 50.2h (22h from EXPIRED) — descriptive use ONLY; may NOT satisfy a phase-dependent unlock precondition and may NOT fill a realized ledger column. basis.reliable=false: 24 unbacked disposals exceeded the replayed position by 8.50642325 ETH — coins sold whose acquisition was never ingested; the account's largest gap by a wide margin (BTC's is 0.0336). The snapshot states explicitly this is NOT a margin short (short_qty null), and custody is RECONCILED with zero withdrawals so it is NOT an off-venue case. No average cost, cost basis, unrealized PnL or ROI reported; realized $447.02 is an UPPER BOUND on a partial fill history. Position Reconciliation: prior reports narrate ~5% Phase 1A filled at ~$1,844 plus ~5% laddered 1800-1825 working; the ledger shows dust with no derivable basis and zero deal tags. Reported UNVERIFIED; no deployment sized against it. performance_by_tag is an EMPTY ARRAY — zero tagged deals exist, so nothing is asserted about how ETH Phase 1A entries have actually performed."
  },
  "trend_residual": { "active_downtrend": false, "basis": "24.77% below the 200-week mean and 10.52% below a falling 200dma (-5.55%/20 sessions), but NOT making lower lows — the 40-session low $1,510.51 (2026-06-26) has held 38 sessions, price has rallied +23.60% off it and sits +4.74% above the 50dma in a higher-low sequence", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15." },
  "correlation": { "value_30d_vs_spx": 0.356, "window": "2026-06-18 to 2026-08-03", "method": "Pearson on daily log returns, 30 overlapping return pairs, Yahoo closes, computed 2026-08-03", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.356 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds — it was available on Phase 1A and declined on other grounds" },
  "discretion": {
    "d1_taken": false,
    "d1_value": 0,
    "d1_negative_considered_declined": {
      "value": -0.5,
      "constructed_from": "shared macro overhang the legs cannot see: a 60.1%-priced September hike, CLARITY at record-low 27% odds (and market structure matters MORE to ETH than BTC), the 200-week distance asymmetry (-24.77% vs BTC's +0.43%, which gate 6's boolean discards), and aggregate stablecoin supply -3.33% over 90 days",
      "reason_declined": "Under ETH's half-DOWN rounding, -0.5 takes 11.0 -> 10.5 -> 10, deleting the Phase 1B score-line crossing this report just achieved. That crossing was earned by a SOURCED DECIMAL replacing a three-report-old proxy — the cleanest evidence this framework can receive. Using discretion to erase a mechanical improvement the data genuinely produced is discretion OVERRIDING evidence rather than supplementing it, which is what the layer is explicitly not for. The macro overhang is shared with BTC and already expressed there as a -1.0; marking ETH down on it too would double-count one shared macro across two reports."
    },
    "d1_positive_considered_declined": {
      "value": 0.5,
      "reason_declined": "Any positive term would be built from the supply-squeeze evidence (reserves at a decade low, staking record 32.4%) that the holder leg already scores 3/3 — prohibited double-counting. Same reasoning as the Aug-01 decline."
    },
    "d1_asymmetry_note": "SECOND report exercising D1 on both majors simultaneously, and the two came out DIFFERENT: -1.0 on BTC, 0.0 on ETH. The difference is attributable to one asset-specific factor (the Coldcard custody exploit, which is Bitcoin-only and contaminates BTC's holder-leg premise while implicating no ETH keys) plus the rounding-convention asymmetry. A layer producing the same number for both assets would be measuring the analyst's mood; a layer that discriminates is measuring something.",
    "d2_available": true,
    "d2_taken": false,
    "d2_phase": "1A",
    "d2_detail": "ALL SIX CONDITIONS MET for the SECOND consecutive report: score 11>=8; gate count short by EXACTLY ONE (2 of 3 required); [V] floor met on lit gates (2>=2); risk-on surcharge OFF (corr 0.356); phase eligible (1A); no D5 stop-out within 10 days. DECLINED on three grounds in order of weight: (1) the un-discretionary path to the same capital already exists — the partial-tranche rule authorizes the pre-assigned 1A remainder to work the 1800-1825 ladder with no fresh unlock, so a D2 unlock would buy already-authorized capital at the price of a hard price-only D5 stop and a 10-day analyst-channel bar, a strictly worse trade for the same dollars; (2) the gate D2 would substitute for is a FEAR gate (1 or 7), and ETH's entire diagnosis is 'cheap but not feared' — writing a conviction case to supply the exact missing fear evidence BECAUSE it is missing is the pattern the D5 stop exists to punish; (3) with basis.reliable=false and the snapshot STALE at 50.2h, a D5 stop line — defined relative to THE FILL — could not be set against a corroborated fill price. Logged for grading: if this channel is available repeatedly and never used, a calibration should say so.",
    "d4_taken": true,
    "d4_detail": "Cells set near the 11-14 anchor row deliberately: Rally -2, Range +1, Retest +1, Bear 0 — all far inside the +/-10 percentage-point band. The D4 layer should not manufacture a view where the rubric already has one. EV recomputed from the printed cells as the final step.",
    "aug01_d1_decline_resolved": "The 2026-08-01 report declined a +1.0 that would have lifted ETH to 11 on DISCRETION, reasoning a discretionary term should not be the sole enabler of a threshold cross. Two days later the score reached 11 MECHANICALLY on sourced data. The decline cost nothing (1B was and remains gate-blocked) and preserved the distinction between an earned threshold and a bought one. FIRST Discretion Ledger entry to resolve; it resolved in favour of restraint.",
    "non_mechanical_capital_pct": 0
  },
  "key_inputs": {
    "fng_spot": 28,
    "fng_3d_avg": 27.33,
    "fng_streak_le15_days": 0,
    "fng_lowest_of_last_10_prints": 25,
    "fng_second_provider_context": "COINOTAG 27 on 2026-08-02 — 1 point from the pinned provider, under the 10-point disclosure bar",
    "weekly_rsi14": 41.96,
    "weekly_rsi_prior_report": 41.40,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_confidence": "ok",
    "daily_rsi14": 51.37,
    "sma_200w": 2481.80,
    "pct_vs_sma200w": -24.77,
    "gate6_within_8pct": false,
    "ma200d": 2087.97,
    "ma200d_slope20_pct": -5.55,
    "pct_vs_ma200d": -10.52,
    "ma50d": 1783.58,
    "pct_vs_ma50d": 4.74,
    "campaign_low": 1510.51,
    "campaign_low_date": "2026-06-26",
    "bounce_pct_off_low": 23.60,
    "bounce_age_sessions": 38,
    "mvrv_z": -0.92,
    "mvrv_z_status": "STALE-INPUT DEBT CLOCK DISCHARGED AT REPORT 4",
    "mvrv_z_sourced_anchor": -1.121278,
    "mvrv_z_sourced_anchor_date": "2026-07-04",
    "mvrv_z_source": "Santiment getMetric(mvrv_usd_z_score), slug ethereum — free tier caps this metric's query window at 2026-07-04; staleness disclosed and bounded rather than waved through",
    "mvrv_ratio_sourced": 0.781315,
    "implied_realized_price": 2277.11,
    "mvrv_ratio_live": 0.820,
    "mvrv_z_sign_bound": "MVRV-Z = (market cap - realized cap) / sigma(market cap), sigma > 0 by construction, so market value below realized value FORCES the sign negative. Spot would need to exceed ~$2,277 (+21.9% above today's $1,868.21) to flip it. Realized price is a slow-moving aggregate cost basis that rises only when coins move at higher prices, and ETH spent most of the interval BELOW the Jul-04 level. The sign is robust to the staleness by a 22% margin; the -0.92 scaled estimate is -1.121 x (0.1796/0.2187).",
    "mvrv_z_provider_cross_check": "Santiment mvrv_usd_z_score for BTC printed 0.371 on 2026-07-04 vs the independent bitcoin-data.com series at 0.3315 the same date — two providers 0.04 apart on the same scale, on the asset where both are available. This is what makes the ETH figure usable.",
    "mvrv_z_declined_source": "The circulating 'ETH MVRV-Z -0.7 / seven-year low' still traces to a single 2026-06-08 BeInCrypto/Phemex article citing Glassnode at ETH $1,684 — two months stale at a price ~11% lower. REMAINS DECLINED under the provenance-citation rule. The Santiment series replaces it: a queryable API with a stated methodology and a verifiable cross-provider scale.",
    "valuation_fallback_note": "The alt fallback band (drawdown from ATH -62.25%) would give 4. The primary metric gives 5. The upgrade rests entirely on the MVRV-Z sign, which is why the derivation is shown in full rather than asserted.",
    "drawdown_from_ath_pct": -62.25,
    "ath": 4946.05,
    "ath_date": "2025-08-24",
    "funding_ann_pct": 3.03,
    "funding_mean_per_8h_pct": 0.00,
    "funding_negative_intervals_in_45": 0,
    "funding_longest_negative_run": 0,
    "funding_note": "capitulation-(b) requires >=3 CONSECUTIVE negative intervals. The Jul-23/24 four-interval episode that scored this leg on Jul-25 — flagged then as FIRED BUT NOT STANDING — has not recurred, and the two isolated negatives noted on Aug-01 have rolled out of the 45-interval window entirely. The leg is now cleanly zero rather than reverting.",
    "liquidations_eth_24h_usd_m": 87.73,
    "liquidations_btc_24h_usd_m": 58.01,
    "liquidations_source": "COINOTAG 2026-08-02/03",
    "liquidations_note": "ETH led the majors this session and is still nowhere near the series' comparable-venue flush band. Leading a quiet field is not a flush.",
    "exchange_reserves": "~14.5M ETH — a decade low, lowest since ~2016; >6M ETH withdrawn since late 2023 (CryptoQuant)",
    "staking_ratio_pct": 32.4,
    "staking_note": "record share of supply; ~3M ETH in the entry queue, exit queue at ZERO",
    "etf_flow_july_usd_m": 365.2,
    "etf_flow_week_to_jul31_usd_m": 27.42,
    "etf_flow_streak_weeks": 4,
    "etf_flow_ytd_2026_usd_b": -1.1,
    "etf_flow_note": "Fourth consecutive weekly inflow week; only outflow session that week was -$32.9M on 2026-07-29; Jul-31 ~+$9M led by ETHB +$15.4M. NOT marked PROVISIONAL — corroborated by two independent framings (weekly nets and the monthly total) and consistent with this series' own Aug-01 prints (July +$365.17M, week ending Jul-24 +$103.9M), so it does not rest on a single streak-completing print. Direction of travel for scoring: gate 4 and capitulation-(c) both require OUTFLOWS, so this evidence moves them FURTHER from lighting. Still ~-$1.1B net for 2026.",
    "adr5": 57.62,
    "adr5_sessions": "2026-07-30 to 2026-08-03, none abbreviated, none excluded",
    "realized_2w_change_pct": -1.87,
    "realized_2w_basis": "vs 2026-07-20 close $1,903.76 (Yahoo)",
    "tbill_3m_pct": 3.78,
    "real_yield_10y_tips_pct": 2.41,
    "vix": 15.81,
    "dxy": 100.00,
    "brent": 83.77,
    "spx": 7602.56,
    "spx_5session_change_pct": 2.55,
    "fed_funds_target": "3.50-3.75%, held 9-3 on 2026-07-29 with three hawkish dissents",
    "sept_fomc_hike_probability_pct": 60.1,
    "clarity_act_2026_passage_odds_pct": 27,
    "net_liquidity_usd_t": 5.83,
    "hy_oas_pct": 2.84,
    "nfci": -0.554,
    "stablecoin_supply_usd_b": 183.20,
    "stablecoin_change_30d_pct": -0.49,
    "stablecoin_change_90d_pct": -3.33,
    "coldcard_exploit_note": "Bitcoin-only event (~1,816 BTC / ~$114M from 5,200+ Coldcard-generated addresses since 2026-07-30, root cause a 2021 Coinkite firmware RNG defect). Coldcard is a Bitcoin-only device; NO ETH keys implicated and ETH's holder leg (staking + exchange withdrawal) is unimpaired. Recorded here because it is the basis of the -1.0 D1 taken on BTC in the companion report, and its ABSENCE from ETH's risk set is a material part of why ETH's D1 is 0.0.",
    "tier1_next_5_sessions": ["Nonfarm payrolls (July Employment Situation) Fri 2026-08-07 08:30 ET"],
    "tier1_window_verified": "compute.mjs tier1 --from 2026-08-03 --sessions 5 → window 2026-08-04..2026-08-10, returns exactly one tier-1 event (NFP 2026-08-07) and zero warnings. Report is NOT an incomplete-data report on the calendar dimension.",
    "tier1_beyond_window": ["CPI (July) Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14", "FOMC decision Tue-Wed 2026-09-15/16"],
    "stale_input_debt": []
  },
  "collar": {
    "band_triggered": true,
    "reasons": ["|EV-vs-spot| 1.12% < 2%", "bull/bear scorecard 8-8, exactly balanced"],
    "mechanical_score_in_6_10_band": false,
    "scorecard": "8 bull / 8 bear — exactly balanced",
    "effect": "no directional regime resolution claimed anywhere in the report; every forward statement carries a probability or an IF->THEN plus a named falsifier"
  },
  "verdict": "HOLD; work the pre-assigned Phase 1A remainder in the $1,800-1,825 ladder; authorize nothing new. SCORE UP 10 -> 11 on the VALUATION leg, the only leg that moved, and it moved because a SOURCED DECIMAL replaced a three-report-old proxy — not on a price move and not on discretion. THE FINDING: Santiment mvrv_usd_z_score for ETH printed -1.121 on 2026-07-04 (free-tier query cap), with an accompanying MVRV ratio of 0.781 pinning an implied realized price of $2,277 against a spot of $1,868 — the median holder is ~18% underwater, the cheapest since December 2018. The SIGN is arithmetic, not estimate: market value below realized value forces MVRV-Z negative, and spot would need +21.9% just to reach zero. Provider scale verified against bitcoin-data.com on BTC to within 0.04. The widely-circulated '-0.7 / seven-year low' figure REMAINS DECLINED — it still traces to one 2026-06-08 article at ETH $1,684. Leg 4 -> 5, debt clock DISCHARGED at report 4. CONSEQUENCE: Phase 1B's score line (>=11 under the 2026-07-27 cut) is cleared FOR THE FIRST TIME IN THIS SERIES — and it buys nothing, because 1B needs 5 of 8 gates with 3 [V] and ETH holds 2 gates, both of them VALUE gates. Every gate that measures FEAR is dark, and gate 4 is moving away at speed on a FOURTH consecutive weekly ETF inflow week (+$27.42M; July +$365.2M) while BTC's turned red (-$61.53M). The binding constraint moved from the score axis to the gate axis. D2 AVAILABLE ON 1A FOR THE SECOND CONSECUTIVE REPORT AND DECLINED AGAIN: the partial-tranche rule already authorizes the same capital without a D5 stop or a 10-day phase bar, and the gate D2 would substitute for is a FEAR gate — writing a conviction case to supply the exact missing fear evidence BECAUSE it is missing is what D5 exists to punish. D1 = 0.0 with BOTH directions argued: a -0.5 on shared macro was declined because under half-down rounding it would erase a mechanically-earned threshold cross; a +0.5 was declined as double-counting the holder leg. The Aug-01 +1.0 decline RESOLVED VINDICATED — the score reached 11 mechanically two days later. STOP QUALITY IMPROVED: ETH is now ONE point from restoring the compound stop's second key (a weekly RSI below 40 — currently 41.96, one band edge away — does it alone). Labeled correction: coherence now tests against the deepest NAMED floor $1,450, not the prior series' $1,470; stricter, no stop moved. FR COMPANION 8/20 Channel B — no new tripwire, but the 2026-08-01 standalone FR report (companion printed 9) is STILL OWED AND UNDISCHARGED. POSITION (Hard Rule 8, STALE at 50.2h, 22h from EXPIRED): dust, custody RECONCILED, basis.reliable=false on 8.5064 ETH across 24 unbacked disposals — the account's largest gap — UNTAGGED; no PnL or cost basis quoted, nothing sized against it. Dry powder $14,408.87 is a SHARED pool with the BTC report. Collar ACTIVE (|EV-vs-spot| 1.12% < 2%; scorecard 8-8): no directional regime resolution claimed.",
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "eth_fallen_knives_20260803_1411.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "ETH",
      "report_date": "2026-08-03",
      "report_local_time": "14:11",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-ETH-20260803-1411",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-ETH-20260803-1411",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-ETH-20260803-1411",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-ETH-20260803-1411",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "eth_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "ETH",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "eth_fallen_knives_20260803_1411.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "ETH",
    "report_date": "2026-08-03",
    "report_local_time": "14:11",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-ETH-20260803-1411",
      "FK-P1B-ETH-20260803-1411",
      "FK-P2-ETH-20260803-1411",
      "FK-P3-ETH-20260803-1411"
    ],
    "status": "REGISTERED"
  }
}
```
