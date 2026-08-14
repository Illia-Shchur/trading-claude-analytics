# 🔪 FALLEN KNIVES ANALYTICS — BTC — 2026-08-03

## MONDAY AFTERNOON — ALL DATA LIVE INTERNET-VERIFIED

### Report Generated: Monday, August 3, 2026, 2:11 PM EDT

### Asset: BTC | Prior Score: 11/20 (2026-08-01) | Mechanical: 11/20 | D1: −1.0 | **Adjusted: 10/20**

---

## 1. Executive Frame

BTC sits **on** its 200-week mean — $63,844 against $63,549, a distance of +0.43%. MVRV-Z is 0.347. Long-term-holder supply is at a record. Exchange reserves are falling. Every structural precondition for a bottom is either satisfied or improving.

And the tape will not confirm it. Fear & Greed prints 27–28 rather than 9. Funding is *positive* at +6.12% annualized, in the 82nd percentile of its own history — longs are still paying. Liquidations are ordinary. The Hash Ribbon has been in miner capitulation since June 8 with no recovery cross. Meanwhile a live, unresolved exploit is draining coins out of the very cold storage the holder leg reads as strength, and the September Fed meeting carries a 60% market-implied probability of a **hike**.

Cheap, structurally sound, and unloved by nobody in particular. That is not the same thing as capitulation, and this report does not treat it as one.

---

## 2. Verified Live Data Points — BTC

### 2.1 Price — canonical spot reconciliation

| Source | Symbol | Price (USD) | Timestamp | Status |
|---|---|---|---|---|
| Binance | BTCUSDT | $63,908.00 | 2026-08-03 18:02:58 UTC | live (venue ts) |
| Coinbase | BTC-USD | $63,855.18 | 2026-08-03 18:02:57 UTC | live (venue ts) |
| Kraken | XBTUSD | $63,832.70 | 2026-08-03 18:02:5x UTC | live (receipt ts) |
| CoinGecko | bitcoin | $63,823.00 | 2026-08-03 18:01:30 UTC | live (venue ts) |
| Yahoo BTC-USD | BTC-USD | $63,849.32 | 2026-08-03 daily bar | **EXCLUDED — frozen bar close, never enters the median** |

> **CANONICAL SPOT = $63,843.94** — median of 4 synchronized live quotes, all inside the 2-hour window.
> Inter-source spread **0.133%**, well under the 0.5% flag. Dispersion is **genuine simultaneous venue disagreement** (all four carry live timestamps within ~90 seconds of one another), not staleness — and at 0.133% it is immaterial to every threshold in this report. No low-confidence demotion.

**Method note (2026-08-03).** `tools/fetch.mjs` currently reports `spot.canonical` as *priority-first* ($63,823, CoinGecko) while this SKILL §2 mandates the **median**. The tool discloses the conflict itself (`spot.method_conflict`) and ships the median as `spot.panel.canonical`. **This report uses the median, $63,843.94** — the SKILL is authoritative. The delta between the two conventions is **−0.033%**, which changes no band, no gate boolean, and no cap tier. The tool-side flip is commit 12 of the 2026-08 toolchain-extension plan and is executed immediately after this report is committed.

**24h context:** BTC −0.93% at the CoinGape print of ~$62,520 earlier in the session; the tape has since recovered to ~$63.8K. Trailing **2-week change: −2.12%** (vs $65,230.03 close on 2026-07-20, Yahoo).

### 2.2 Sentiment

| Metric | Reading | Source | Timestamp |
|---|---|---|---|
| F&G spot | **28** ("Fear") | Alternative.me raw API (**pinned provider**) | 2026-08-03 |
| F&G 3-day average | **27.33** | Alternative.me | 2026-08-01 → 08-03 |
| Daily prints (last 10) | 28, 27, 27, 25, 28, 29, 29, 30, 26, 27 | Alternative.me | 2026-07-25 → 08-03 |
| Gate-1 streak (daily prints ≤15) | **0 consecutive days** | Alternative.me | — |
| F&G percentile vs own 2y history | 34.98th | computed, `tools/fetch.mjs` context | 730d history |

**Second-provider context:** COINOTAG published the index at **27** on 2026-08-02. Divergence vs the pinned provider is **1 point**, far under the 10-point disclosure bar. No provider switch; the pinned Alternative.me series governs both the scored 3-day average and the gate-1 daily-print streak.

**Provenance on the streak claim.** "Zero sub-16 prints" is verified against the fetched 10-print window (lowest = 25, on 2026-07-31) *and* against this series' own prior reports: the 2026-08-01 and 2026-07-25 BTC reports both printed `fng_streak_le15_days: 0`. The regime is not backdated.

### 2.3 Momentum — weekly RSI (computed, auditable)

| Field | Value |
|---|---|
| **Wilder RSI-14, weekly** | **38.84** |
| Weekly-close source | Yahoo BTC-USD, 5y 1wk series (262 candles) |
| Weekly boundary | Yahoo week-start timestamps, UTC |
| Period | 14 |
| Completed closes used | **261** (≥30 → unflagged, full confidence) |
| Last completed week | 2026-07-27 |
| RSI including the live (incomplete) week | 39.41 — *not scored* |
| Percentile vs own 2y history | 22.87th |
| Daily RSI-14 | 49.33 |

### 2.4 Valuation — MVRV-Z (sourced decimal)

| Metric | Value | As of | Source |
|---|---|---|---|
| **MVRV-Z** | **0.3469** | 2026-08-02 | bitcoin-data.com `/v1/mvrv-zscore/last` |
| MVRV ratio | 1.2131 | 2026-08-02 | bitcoin-data.com `/v1/mvrv/last` |
| Realized price | **$52,367.36** | 2026-08-02 | bitcoin-data.com `/v1/realized-price/last` |
| Drawdown from ATH ($126,080, 2025-10-06) | **−49.38%** | 2026-08-03 | CoinGecko |
| Distance below 1-yr high ($126,198.07) | −49.43% | 2026-08-03 | Yahoo trailing-1y weekly highs |

**Provider cross-check (methodology, not decoration).** Santiment's `mvrv_usd_z_score` for BTC printed **0.371** on 2026-07-04; bitcoin-data.com printed **0.3315** on the same date. Two independent providers, 0.04 apart on the same scale. This matters for the ETH report published alongside this one, which is forced onto the Santiment series — the scales are shown here to be comparable.

Prior report: 0.41 (as of 2026-07-31). Current 0.347. Same band (`≤0.5 → 4`); the move is BTC drifting fractionally cheaper against a realized price that barely moved ($52,418 → $52,367).

### 2.5 Long-horizon structure

| Metric | Value | Source |
|---|---|---|
| **200-week SMA** | **$63,549.42** | computed, Yahoo 261 weekly closes |
| Spot vs 200-week SMA | **+0.43%** | — |
| **Gate 6 (within ±8%)** | **TRUE ✅** | `tools/fetch.mjs` boolean |
| 200-day MA | $70,966.67 | computed |
| Spot vs 200dma | −10.07% (40.79th percentile of its own 2y history) | computed |
| 200dma 20-session slope | **−3.62% (falling)** | computed |
| 50-day MA | $63,313.99 | computed |
| Spot vs 50dma | +0.84% | — |
| 50dma vs 200dma | 50 **below** 200 (death-cross regime, gap 10.78%, narrowing) | computed |
| 40-session low | **$57,747.77** (2026-07-01) | computed |
| Bounce off that low | +10.52%, 33 sessions old | computed |

### 2.6 On-chain & derivatives

| Metric | Value | Source | Timestamp |
|---|---|---|---|
| Perp funding, mean per 8h | **+0.01%** (**+6.12% annualized**) | Binance fapi `fundingRate`, BTCUSDT, 45 intervals | 2026-08-03 |
| Longest **negative** funding run | **0 intervals** (of 45; 15 sessions) | Binance fapi | 2026-08-03 |
| Funding percentile vs own history | **82.04th** (167d available) | computed | — |
| 24h liquidations, BTC | **$58.01M** | COINOTAG | 2026-08-02/03 |
| 24h liquidations, ETH (comparison) | $87.73M | COINOTAG | 2026-08-02/03 |
| Event-driven liquidations (Iran headline) | ~$280M market-wide | CoinGape via search | 2026-08-01/02 |
| **Hash Ribbon (30d vs 60d hashrate MA)** | **30d $899.7 EH/s BELOW 60d $917.2 EH/s → miner CAPITULATION, no buy signal** | **computed** from blockchain.info `charts/hash-rate` (362 daily points) | last point 2026-08-02 |
| Hash Ribbon last cross into capitulation | **2026-06-08** (~8 weeks sustained) | computed, same series | — |
| LTH supply | Record high; 30d net position change **+1.29M BTC**, strongest in 6 years | CryptoQuant via news.bitcoin.com / crypto-economy | early Aug 2026 |
| Exchange reserves | Falling — **−78,000 BTC over 6 months**; Binance/OKX/Gemini −100,000 BTC since Feb 2026; lowest since late 2023 | CryptoQuant via MEXC/Benzinga | 2026 |
| Long/short account ratio (Binance) | 1.9403 (93.1st pct, **falling**) | Binance fapi, single-venue, ~30d | 2026-08-03 |
| Taker buy/sell ratio | 1.0404 (62.07th pct, rising) | Binance fapi | 2026-08-03 |
| Open interest | 109,126.6 (96.55th pct of a ~30d window, rising) | Binance fapi | 2026-08-03 |

> **Hash Ribbon — stale-input debt DISCHARGED.** The 2026-08-01 report carried gate 5 dark on "no sourced print, report 2 of the debt clock." This report ships a **computed** value from a primary hashrate series rather than an article. It matters that it was computed: a web search for "Hash Ribbon August 2026" surfaced a Bitbo/Cointelegraph piece announcing that Hash Ribbons had "signaled a potential end to miner capitulation" alongside a difficulty ATH of 90.66T — **the article is dated August 20, 2024.** Scored off the headline, gate 5 would have lit on two-year-old data. The computed series says the opposite: the 30d MA sits 1.9% *below* the 60d MA and has since June 8. Gate 5 stays ❌.

### 2.7 Macro & equities

| Metric | Level | Δ 5 sessions | Source | Date |
|---|---|---|---|---|
| S&P 500 | 7,602.56 | **+2.55%** | Yahoo ^GSPC | 2026-08-03 |
| VIX | 15.81 | −15.32% | Yahoo ^VIX | 2026-08-03 |
| DXY | 100.00 | −1.49% | Yahoo DX-Y.NYB | 2026-08-03 |
| Brent | $83.77 | −5.19% | Yahoo BZ=F | 2026-08-03 |
| 10y TIPS real yield | 2.41% | −0.02pp (5 prints) | FRED DFII10 | 2026-07-30 |
| **3-month T-bill** | **3.78%** | — | TradingEconomics | 2026-08-03 |
| Fed funds target | **3.50–3.75%**, held 9–3 (Hammack, Kashkari, Logan dissenting hawkish) | 5th consecutive hold | FOMC statement / CNBC | 2026-07-29 |
| **Sept FOMC market-implied HIKE probability** | **60.1%** (down from 78.8% pre-presser) | CME FedWatch via CNBC | 2026-07-29 |

**Disclosed context (not scored, not a gate):**

| Metric | Value | Source |
|---|---|---|
| Realized vol 30d | 29.38% (**13.05th percentile** vs 2y) | computed |
| Realized vol 10d / 90d | 29.30% / 34.80% | computed |
| Deribit DVOL | 34.81 | Deribit |
| ATM IV (2026-08-28 expiry, 24.6d) | 32.23% | Deribit |
| Moneyness skew (90/110) | +7.79% (puts bid) | Deribit |
| Variance risk premium | +2.85pp (IV over RV) | computed |
| Perp basis | −0.05% | Binance fapi premiumIndex |
| Net liquidity (WALCL − RRP − TGA) | $5.83T | FRED, week of 2026-07-29 |
| HY OAS | 2.84% (+0.07pp / 5 prints) | FRED BAMLH0A0HYM2 |
| NFCI | −0.554 (looser than average) | FRED, 2026-07-24 |
| **Aggregate stablecoin supply** | **$183.20B, −0.49% 30d, −3.33% 90d** (91.32nd pct) | DefiLlama |

Two of these deserve a sentence. **Realized vol at the 13th percentile of two years** while IV sits ~3 points above it: the market is neither pricing nor experiencing stress — this is a quiet, drifting tape, which is exactly what a fear score of 11 out of 20 should look like and exactly what a capitulation does *not* look like. And **stablecoin supply contracting 3.33% over 90 days** is the system's own dry powder shrinking; it is context, not a leg, but it argues against the "cash on the sidelines waiting to buy" story.

### 2.8 Correlation regime

| Metric | Value |
|---|---|
| **30d Pearson correlation, BTC vs SPX** | **0.341** |
| Method | Pearson on daily log returns, overlapping sessions, Yahoo closes |
| Window | 2026-06-18 → 2026-08-03 (30 return pairs) |
| Computed | 2026-08-03 |
| **Regime** | **mild** — not decoupled, not risk-on |
| Risk-on surcharge (>0.7) | **OFF** — no extra [V] gate required |
| Phase-2 corr condition (<0.80) | **PASS on a computed number** |
| D2 availability on correlation grounds | **not barred** (surcharge off) |

---

## 3. Critical Developments

**1. The Coldcard exploit — live, escalating, unresolved.** A firmware build shipped in **March 2021** routed Coldcard seed generation to a predictable software randomizer instead of the secure element's hardware RNG, making five years' worth of keys reproducible offline. The sweeps began 2026-07-30:

| Wave | Date | Drained | Addresses |
|---|---|---|---|
| 1 | 2026-07-30 (41 minutes) | 1,083 BTC | 1,196 |
| 2–3 | weekend 08-01/02 | +284 BTC | 3,389 |
| 4 | 2026-08-03, **ongoing at press** | blocks 960,778–960,792, 218 txs | 462 |
| **Total** | since 2026-07-30 | **~1,816 BTC ≈ $114M** | **>5,200** |

Coinkite has shipped emergency firmware; Galaxy Research is telling every single-sig Coldcard holder to move funds now. The fourth wave uses fresh addresses with no history, making it harder to trace. *(Sources: CoinDesk 2026-08-02 and 2026-08-03; Bloomberg 2026-08-03; Galaxy Research via Crowdfund Insider.)*

**2. CLARITY Act punted past the recess.** Senate Majority Leader Thune confirmed no floor vote before the August 7 recess; leadership prioritized Russia sanctions and nominations. Polymarket's 2026-passage odds hit a **record-low 27%**. The bill has cleared the House and a Senate committee but has no cloture motion and no calendar date, and now faces a September session with less momentum. *(CoinDesk 2026-07-23; crypto.news; cryptonews.com.)*

**3. Fed held at 3.50–3.75% on a 9–3 vote, three hawkish dissents.** Post-presser, CME FedWatch showed a **60.1% probability of a September hike** — down from 78.8% intraday, but the direction under discussion is *up*. Inflation has run above target for five years. Next decision: **September 15–16**. *(FOMC statement 2026-07-29; CNBC.)*

**4. Iran de-escalation.** Trump called off strikes and announced talks resuming Monday; Gulf allies pushed for diplomacy. Brent −5.19% over five sessions to $83.77, SPX +2.55%, VIX to 15.81. Genuine risk-positive — and BTC did not participate.

**5. ETF flows rolled over at the week level while the month stayed green.** July finished **+$172.4M**, breaking a two-month outflow streak. But the week to July 31 was **−$61.53M**, and July 31 alone was **−$265.4M** (IBIT −$122.7M, Fidelity −$54.8M, Bitwise −$17.8M, Ark −$17.5M) — the largest single-day withdrawal since July 13. *(CryptoTimes 2026-08-01, compiling Farside/SoSoValue.)*

---

## 4. Fallen Knives Composite Score — BTC

| Category | Max | Input | Band | **Score** |
|---|---|---|---|---|
| **Sentiment Extreme** | 5 | 3-day avg F&G **27.33** (pinned: Alternative.me) | ≤35 → 2 | **2** |
| **Momentum Exhaustion** | 4 | Weekly Wilder RSI-14 **38.84** (261 closes, full confidence) | ≤40 → 2 | **2** |
| **Valuation** | 5 | **MVRV-Z 0.3469** (bitcoin-data.com, 2026-08-02) | ≤0.5 → 4 | **4** |
| **Capitulation Evidence** | 3 | 0 of 3 — see below | 0/3 → 0 | **0** |
| **Holder Behavior** | 3 | LTH supply at record + reserves falling — **both** | Both → 3 | **3** |
| **LEG SUM** | 20 | | | **11** |

*Band assignments verified: `compute.mjs band fk-sentiment 27.33` → 2; `fk-momentum 38.84` → 2; `fk-mvrv 0.3469` → 4.*

**Capitulation detail (0/3):**
- **(a) Liquidations in the top decile of trailing-90d, or >3σ above the trailing-30d mean** — ❌. BTC 24h liquidations **$58.01M**. The series' comparable-venue band has run $270–320M market-wide; $58M asset-specific is ordinary by any reading. Not close.
- **(b) Funding negative for ≥3 consecutive intervals** — ❌. **Longest negative run: 0 of 45 intervals.** Funding is not merely non-negative, it is *positive at the 82nd percentile*. Longs are paying to be long.
- **(c) ETF net outflows ≥2% of AUM trailing month** — ❌. Trailing month is net **positive** (+$172.4M July). The metric is moving away from the bar, not toward it.

**Score arithmetic:**

| Component | Value |
|---|---|
| Leg sum | 11 |
| **MECHANICAL score** (read by every protective rule) | **11** |
| **D1 discretionary term** | **−1.0** (see §9) |
| Raw composite (legs + D1) | 10.0 |
| Rounding convention (BTC) | half-**up** |
| [V]-gate surcharge | none (corr 0.341 ≤ 0.7) |
| **ADJUSTED score** (read by unlock rules only) | **10 / 20** |

### Confirmation Gates — 3 of 9 ✅

| # | Bucket | Gate | Status | Evidence / relight path |
|---|---|---|---|---|
| 1 | **[V]** | F&G ≤15 for ≥7 consecutive daily prints | ❌ | Streak **0**; lowest of last 10 is 25. Relight: seven straight prints ≤15 — from 27–28 that needs a genuine panic leg, realistically a break of $57.7K. **Reachable, not close.** |
| 2 | **[V]** | Weekly RSI <30 | ❌ | 38.84. Relight: weekly closes roughly ≤$54–56K sustained for 3–4 weeks. **Reachable in regime.** |
| 3 | **[V]** | Valuation cheap (MVRV-Z <1) | ✅ | 0.3469 — lit with wide margin. |
| 4 | **[V]** | ETF outflows ≥2% AUM trailing month | ❌ | Trailing month **net +$172.4M**. Relight: sustained daily outflows at the Jul-31 magnitude (−$265M) for ~3 weeks. **Nearest [V] gate to lighting** — and it moved *further* away in July even as the last week turned red. |
| 5 | **[T]** | Hash Ribbon buy signal | ❌ | **Computed**: 30d hashrate MA $899.7 EH/s < 60d $917.2 EH/s; capitulation since 2026-06-08. Relight: a 30d-over-60d recovery cross — requires ~2% hashrate MA convergence. **Reachable, mechanical, watch weekly.** Debt clock **discharged**. |
| 6 | **[T]** | Price within ±8% of the 200-week MA | ✅ | Spot **+0.43%** vs $63,549.42. Lit with the widest possible margin — price is *on* the line. |
| 7 | **[V]** | Capitulation volume spike (top-decile 90d or >3σ) | ❌ | $58.01M 24h. Relight: a single session ≥~$1B market-wide on the series' comparable-venue convention. **Reachable only on a cascade.** |
| 8 | **[V]** | LTH accumulation / holder concentration stabilizing | ✅ | LTH supply at a record; +1.29M BTC 30d net position change, strongest in 6 years; reserves −78K BTC/6mo. |
| 9 | **[T]** | Macro catalyst neutral-to-positive | ⚠️ | **Genuinely mixed, and improved this week.** Positive: Iran de-escalation is real and dated. Negative: a 60.1% priced September **hike** is the opposite of "Fed pivot priced," and CLARITY is at record-low 27% odds. One of three sub-conditions. ⚠️ does not count. Relight: Sept hike odds <35% **or** a scheduled CLARITY floor vote. |

**Active denominator: 9** (no gate is structurally inapplicable — BTC is PoW, so gate 5 applies and is honestly dark).
**Passed: [3, 6, 8] = 3 gates. [V] among them: {3, 8} = 2.**

Thresholds (`compute.mjs thresholds 9`): 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4).

> **Gate 6 ⇄ §5 cross-reference.** Price sitting on the 200-week mean keeps gate 6 lit (pro-*eligibility*) at the same time as the death-cross regime and the falling 200dma argue for caution on *sizing and edge*. These are not in conflict; they answer different questions. Both are reported.

---

## 5. Probability Matrix — BTC (D4: analyst-set)

**Anchor row.** Adjusted score 10 → baseline row **6–10**: Rally 20 / Range 35 / Retest 30 / Bear 15.

**§5 trend residual — mandatory boolean:**
> **Active downtrend (below a major MA **AND** making lower lows)? → NO.**
> Price is 10.07% below a falling 200dma, but the campaign low of **$57,747.77 (2026-07-01)** has not been breached in 33 sessions; the August lows ($62,233 on Aug-1, $62,235 on Aug-3) sit ~7.8% above it and are flat against each other, not descending. **No bearish residual applied.**
> **Consequence, stated so the guardrail is not orphaned:** the Deep-Value Override's **quarter-size throttle is OFF** — an Override firing would be **half**-size. (It cannot fire this report regardless; mechanical 11 < 15.)

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | **26%** | $66,500 – $71,000 | $68,750 | Reclaim of the 50dma with conviction and a run at the falling 200dma ($70,967); needs NFP soft enough to push Sept hike odds below ~35% |
| **Range** | **36%** | $62,000 – $66,500 | $64,250 | The base case: continued coil around the 200-week mean at 13th-percentile realized vol, with neither fear nor flows resolving |
| **Retest** | **24%** | $57,500 – $62,000 | $59,750 | A retest of the $57,747.77 July low — triggered by a hot NFP/CPI repricing the hike, or by attacker distribution of the ~1,816 stolen BTC |
| **Bear** | **14%** | $50,000 – $57,500 | $53,750 | The 200-week mean breaks and fails to reclaim; a genuine flush that would light gates 1, 2 and 7 together |

**Deviations from the 6–10 anchor row:** Rally +6, Range +1, Retest −6, Bear −1. All within the ±10-percentage-point band; none requires a >10pp reason line. The Rally uplift and the Retest reduction come from the same fact — price is *at* the 200-week mean with the campaign low intact 33 sessions on, which is a materially better structural base than the raw 6–10 score row assumes.

**Weighted EV (recomputed from the printed cells as the final step):**

```
0.26 × 68,750 = 17,875.00
0.36 × 64,250 = 23,130.00
0.24 × 59,750 = 14,340.00
0.14 × 53,750 =  7,525.00
                ─────────
        EV    = 62,870.00     probabilities sum = 100 ✅
```
*Verified: `compute.mjs ev --spot 63843.94` → `ev: 62870, prob_sum_ok: true, vs_spot_pct: -1.53`.*

| | |
|---|---|
| **Weighted EV** | **$62,870.00** |
| Canonical spot | $63,843.94 |
| **EV vs spot** | **−1.53%** |
| **Realized trailing 2-week price change** | **−2.12%** |

The EV is mildly negative and the realized two-week tape is mildly negative. They agree — which is worth saying out loud, because the failure mode this framework was recalibrated to remove in June 2026 was an EV that printed positive by construction while price fell every week.

**Rally cap:** 26% ≤ 50% ✅ (and not modal).
**EV-floor consistency check:** EV-vs-spot is negative, but the **mechanical** score is 11 (not ≥15) and 3-day F&G is 27.33 (not ≤15). **Both arming conditions fail → no inconsistency flag.**
**Terminal-vs-extreme reconciliation:** not owed — the trend residual is not live.

---

## 6. Deployment Strategy — BTC

### 6.1 Position & Performance (Hard Rule 8)

`node tools/position.mjs btc` — **exit 0**, snapshot `position-snapshot/1` at `~/.trading-claude/exchange/position-snapshot.json`.

> ### ⚠️ BAND: **STALE** — age **3,011 minutes (50.2 hours)**, driver `holdings_as_of` (2026-08-01 15:51:56 UTC)
> **Descriptive use only.** It may **not** satisfy a phase-dependent unlock precondition and may **not** fill a realized ledger column. Everything below is read under that banner.

| Field | Value |
|---|---|
| **Live quantity** | **0.00000184 BTC** (dust) |
| `trade_derived_qty` | 0.00000184 — identical |
| **Custody status** | **RECONCILED** — live balance agrees with the fill replay; the position is where the ledger can see it |
| Deposits / withdrawals / net external outflow | 0 / 0 / 0 |
| `off_venue_qty` | null — **this is not a cold-storage case** |
| **`basis.reliable`** | **FALSE** |
| Oversold (unbacked) quantity | **0.03360450 BTC** across **5 unbacked disposals** |
| `short_qty` | **null** — the snapshot states explicitly this is **not** a margin short |
| Average cost / total cost / unrealized PnL / ROI | **NOT REPORTED** — see below |
| Realized PnL | **$1,639.83 — an UPPER BOUND, not a result** |
| **Attribution** | **UNTAGGED** — 2 open deals carry no tag |
| **Dry powder (stablecoins)** | **$14,408.87** (USDT $9,672.96 across CROSS_MARGIN/FUNDING/SPOT; USDC $4,735.91 in EARN_FLEXIBLE) |
| Portfolio total | $19,790.26 · futures equity $0.00 |
| Account-wide margin interest paid | $92.71 (3,248 accruals, BNB) |

**Why no cost basis is quoted.** `basis.reliable = false`: five unbacked disposals exceeded the replayed position by 0.0336 BTC — coins were sold whose *acquisition the ledger never ingested*. Custody is RECONCILED, so this is not cold storage, and `short_qty` is null, so it is not a margin short. The fill history is simply incomplete. Per Hard Rule 8's basis carve-out, **no average cost, cost basis, unrealized PnL or ROI is reported**, and $1,639.83 realized is an upper bound.

**Position Reconciliation — the ledger wins, and it says "unreadable."**

| Figure | Prior reports narrate | Ledger says | Delta |
|---|---|---|---|
| Phase 1A | 10% deployed at **~$65,000 blended** | 0.00000184 BTC, no derivable basis, UNTAGGED | **Unresolvable** |
| Withdrawals | — | 0 | Rules out the off-venue explanation |
| Attribution | `FK-P1A` | none — 2 untagged open deals | Cannot resolve a phase-dependent precondition |

The narrated `~$65,000 blended` has now been retyped across multiple reports without ever being corroborated by a fill. The ledger cannot confirm it and — because the fill history is provably incomplete — cannot refute it either. **Status: UNVERIFIED.** No deployment is sized against it, and no PnL is claimed from it. This is action item 1.

**Realized performance:** `performance_by_tag` and `performance_by_tag_prefix` are both **empty arrays**. Zero tagged deals exist, so there is no per-phase win rate, profit factor, or expectancy to state. Nothing about how Phase 1A entries have "actually performed" can be asserted, and it is not asserted.

**Dry powder yield benchmark:** **3.78%** (3-month T-bill, 2026-08-03). On $14,408.87 that is ~$545/yr, or ~$45/month, of measurable opportunity cost for staying dry. Cash is a position and it is currently a *paid* position.

### 6.2 Phase board

**Total dry powder: ~90–100% (unresolvable — see §6.1). Real deployable stablecoin balance: $14,408.87.**

| Phase | Size | Entry zone | Score condition | Gate condition | Status |
|---|---|---|---|---|---|
| **1A** | 10% | **$63,000 – $66,500** | ≥8 → **10 ✅** | ≥3/9, [V]≥2 → **3/9, [V] 2 ✅** | **UNLOCKED — fill state UNVERIFIED** |
| **1B** | 15% | $58,000 – $61,500 | ≥11 → **10 ❌** (was met at 11 on Aug-01; the D1 term removes it) | ≥5/9, [V]≥3 → **3/9, [V] 2 ❌** | **BLOCKED (both axes)** |
| **2** | 30% | $54,000 – $58,000 | ≥15 → ❌ | ≥6/9, [V]≥3 → ❌ | FROZEN |
| **3** | 45% | requires a weekly capitulation candle | mechanical ≥17 → ❌ (11) | ≥7/9, [V]≥4 → ❌ | DRY |

**Phase 1A — unlocked, and deliberately not funded today.** Spot $63,843.94 sits inside the zone, near its floor. The unlock is real. What is *not* real is knowledge of whether this tranche is already filled. If the narrated 10% is genuine, a new fill is **upsizing beyond nominal — prohibited**. If it is not, the book is ~100% dry against a live unlock. A 50-hour-stale ledger cannot settle it in either direction, and Hard Rule 8 is explicit that the STALE band may not resolve a phase-dependent question.

So the authorization is written as a conditional, which is executable rather than evasive:

> **IF** a FRESH (≤12h) snapshot shows Phase 1A unfilled **THEN** ladder the full 10% across **$63,000–66,500 in three clips** ($66,000 / $64,500 / $63,200), never at the top of the zone.
> **IF** it confirms the narrated ~$65,000 fill **THEN** 1A is complete; deploy nothing and let 1B's gate condition do its work.
> **Ledger tag on any fill: `FK-P1A`**, applied via `PUT /api/investments/deal-note` with first note line `report=reports/btc_fallen_knives_20260803_1411.md`.

**Phase 1B — the D1 term matters here, and it is honest about it.** On Aug-01, BTC's score condition for 1B was met for the first time (mechanical 11 ≥ 11) and the tranche was blocked on gates alone. Today the mechanical score is still 11, but the **adjusted** score is 10 — so on the number the unlock rule actually reads, 1B is once again double-blocked. This changes no capital: the gate count is 3 against a requirement of 5 with a [V] floor of 3 against 2 lit, so 1B was never deployable this report by any route. The D1 term makes the report's stance and its number agree instead of leaving the discomfort in the prose.

**D2 Analyst Conviction Path — evaluated, UNAVAILABLE.** The path opens only at a shortfall of **exactly one** gate. Phase 1B is short **two** (3 of 5). Additionally the [V] floor would fail on lit gates (2 lit vs 3 required), and D2 substitutes for a gate, never for a [V] floor. Not available on two independent grounds.

**⚑ Deep-Value Override — evaluated, DOES NOT FIRE.** **Mechanical score 11 < 15 — dispositive.** Two further independent failures: 3-day F&G 27.33 is not ≤15, and the Override presupposes a corroborated deployed tranche, which the ledger cannot supply. No near-fire to log. Max drawdown from spot to the compound thesis line ($55,000): **−13.85%**, stated as standing disclosure; per the 2026-07-27 supersession it purchases no loosening of anything.

**Non-mechanical capital cap:** 0% of book deployed through D1/D2/Override channels, against the 40% ceiling (Override sub-cap ≤25% inside it). Not binding.

### 6.3 Stops

| Tier | Level | Status |
|---|---|---|
| **Catastrophic floor** | **$50,000** | Unchanged. Strictly below the deepest named buy-zone floor. |
| **Compound thesis stop** | **$55,000 price AND mechanical score <12** | Unchanged. Score line 12 (BTC standard — not a pinned-score asset). |
| Deepest named buy-zone floor | **$54,000** (Phase 2 zone $54,000–58,000) | — |
| D5 discretionary stops | **none** — zero analyst-channel tranches exist | — |

> **COHERENCE CHECK: catastrophic stop $50,000 strictly below deepest active buy-zone floor $54,000? → PASS.**
> Verified: `compute.mjs stop-coherence --catastrophic 50000 --floor 54000` → `pass: true`.
> No prospective ladder is named below $54,000 anywhere in this report, so no post-activation re-stop or atomic activation sequence is owed. No "stop realignment owed" flag.

**Compound stop disclosure (carried, no migration).** Mechanical score is **11**, which is **<12**, so the score axis **is satisfied** and the stop is effectively **price-gated at $55,000** until the score re-crosses 12. Unchanged in kind from Aug-01. Note precisely what the D1 term does *not* do here: the compound stop reads the **mechanical** score, so the −1.0 has **no effect on this line whatsoever**. That is the governing rule working as designed — a discretionary term cannot buy the book protection any more than it can buy it entries, and had this read the adjusted score, a *negative* D1 would have made the stop fire more readily than the evidence warrants.

**Stop Migration Ledger:**

| Parameter | Tier | Old | New | Direction | Rationale |
|---|---|---|---|---|---|
| Checkpoint date | checkpoint date | 2026-08-02 | **2026-08-09** | forward roll | The Aug-02 checkpoint resolved on schedule and did **not** fire (0 of 2 required weekly closes below $55,000 — the weekly close was ~$63.7K). Rolls to the next weekly close. **D6 exception 3** (calendar validity), not a discretionary widening. |

No other stop parameter changed value. **D6 ratchet: compliant** — nothing moved away from price.

**Checkpoint prognosis.** Checkpoint **Sunday, 2026-08-09, 00:00 UTC weekly close** — verified a real weekly boundary on the crypto weekly calendar (week-start UTC, the same boundary used for the RSI computation); crypto venues trade continuously, so no holiday or abbreviated-session correction applies. It **fires iff** ≥2 consecutive weekly closes print below $55,000 **and** the mechanical score is <12. Spot $63,843.94 sits **16.08% above** the line, a distance of **5.53× the 5-day ADR of $1,599.09** (sessions 2026-07-30 → 08-03, none abbreviated, none excluded). Closes below the line: **0 of the 2 required** — so the checkpoint **cannot** fire on Aug-09 regardless of price. That is a structural statement about the condition, not a forecast, and it is the only reason a likelihood word appears anywhere near it.

**Tier-1 US release between this report and the Aug-09 checkpoint: YES — nonfarm payrolls, Friday 2026-08-07, 08:30 ET.** Named as part of the falsifier: a hot print pushes September hike odds up and cuts BTC *down* toward the Retest band; a soft print cuts them and supports the Rally band. Either way it cannot produce two sub-$55,000 weekly closes by Aug-09, so the checkpoint's *unfireable* status is robust to it — but the direction of the tape after Aug-07 is genuinely conditional on that release, and no directional claim here is made independent of it.

---

## 7. Exit / Trim Framework — BTC

Every score condition below reads the **MECHANICAL** score (11), never the adjusted 10. Discretion buys entries; it never touches an exit.

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | peak 16 (2026-06 series) → ≤10 | **11** | ❌ not triggered (5 points off the peak, one short) |
| F&G ≥75 sustained 7d AND weekly RSI >70 | — | F&G 27.33, RSI 38.84 | ❌ nowhere near |
| MVRV-Z >3 **or** drawdown <10% with vertical 30d return | — | MVRV-Z 0.347, drawdown 49.4% | ❌ inverted — this is the buy side |
| Mechanical score ≤3 AND price ≥40% above blended cost | — | score 11; blended cost **unknowable** | ❌ not triggered; second limb is unevaluable while `basis.reliable=false` |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime | — | trailing month **+$172.4M**; and the ≥5-consecutive-green-session bar was never met during any held position's life | ❌ not triggered — the *precondition* fails, not just the trigger |
| **Narrative break** | regulatory ban / founder fraud / **critical security breach** / irreparable tokenomics change | **Coldcard exploit — EVALUATED IN FULL BELOW** | ❌ **not triggered** |

### The narrative-break call on Coldcard — stated explicitly, because it is close enough to deserve it

The §7 exit trigger names "critical security breach" and mandates a 100% exit when the thesis is voided. An ongoing theft of ~1,816 BTC across 5,200+ addresses, still running on its fourth wave, is not a headline to wave past. The determination:

**It is not a narrative break, and the reasoning is the scope of the flaw.** The defect is a **March 2021 Coinkite firmware build** that misrouted seed generation to a software RNG. It is a single vendor's product defect. Bitcoin's consensus rules, cryptography, issuance and settlement are untouched; every affected key was weak from the moment it was generated, which is why the sweeps can be executed offline. A vendor shipping a bad RNG is the same class of event as an exchange failure — severe for the users hit, not a change in what the asset *is*. The §7 trigger is written for "irreparable" and "thesis voided," and this is neither: Coinkite has shipped a fix, and the remedy (regenerate the seed, move the coins) is available to every holder.

**What it *does* do — and this is where it earns a D1 term rather than an exit.** BTC's holder leg scores **3 of 3**, and half of that score is "exchange reserves declining" — a metric whose bullish reading rests entirely on the premise that coins leaving exchanges are going somewhere *safer*. This event says that for five years, a meaningful slice of that destination was reproducible offline by anyone who worked out the range. The leg's measurement is still correct; its *interpretation* is now impaired, and no leg or gate can express that. There is also a mechanical overhang: ~1,816 BTC the attacker did not pay for, with no cost basis and every incentive to sell.

**Exit status: NONE. No trim executed, no partial exit. Remaining position: unresolvable per §6.1.**

---

## 8. Critical Watchlist

| Date / Time (ET) | Event | Tier | BTC impact |
|---|---|---|---|
| **Fri 2026-08-07, 08:30** | **Nonfarm payrolls (July Employment Situation)** | **TIER 1** | The only tier-1 release in the 5-session window. Hot → Sept hike odds up from 60.1% → pressure toward Retest. Soft → odds down → the single cleanest path to gate 9 relighting. Date verified: BLS Employment Situation is the first Friday of the month; `compute.mjs tier1 --from 2026-08-03 --sessions 5` returns exactly this event and no other. |
| Fri 2026-08-07 | Last Senate working day before recess | high | Final theoretical window for a CLARITY floor vote. Odds 27%. Non-event is the base case. |
| Mon 2026-08-10 | Senate state work period begins | high | CLARITY formally dead until September if Aug-07 passes without a vote. |
| **Sun 2026-08-09, 00:00 UTC** | **Weekly close — stop checkpoint** | — | Cannot fire (0 of 2 closes). |
| **Wed 2026-08-12, 08:30** | **CPI (July)** | **TIER 1** | Outside the 5-session window; enumerated because it lands before the *following* checkpoint and is the dominant input to the September hike decision. |
| Thu 2026-08-13 | PPI | tier 2 | — |
| Fri 2026-08-14 | Retail sales | tier 2 | — |
| **Tue–Wed 2026-09-15/16** | **FOMC decision** | **TIER 1** | Currently 60.1% priced for a **hike**. The single largest scheduled risk to the accumulation thesis. |
| Ongoing | **Coldcard sweeps — waves 5+** | high | Watch (i) whether new waves print, (ii) whether attacker addresses deposit to exchanges. Both feed the D1 falsifier. |
| Weekly | Hash Ribbon 30d/60d cross | medium | Gate 5's mechanical relight. Currently 1.9% apart. |
| Daily | ETF flows (Farside/SoSoValue) | high | Gate 4 is the nearest [V] gate. Needs ~3 weeks at the Jul-31 magnitude. |

**Tier-1 completeness statement:** the 5-session window (2026-08-04 → 08-10) contains exactly one tier-1 US release — NFP on 2026-08-07 — and it is enumerated above. This report is **not** an incomplete-data report on the calendar dimension.

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

The bottom's *plumbing* is in place and its *psychology* is absent, and I think the market is going to make you wait for the second one.

Look at what is actually true. Price is on the 200-week mean — not near it, *on* it, +0.43%. MVRV-Z is 0.347 against a realized price of $52,367, meaning the average coin is held at a 22% profit and the marginal holder is close to flat. Long-term holders are accumulating at the fastest 30-day pace in six years and sit at a record share of supply. Coins are leaving exchanges. This is the balance-sheet picture of a market that has finished distributing.

Now look at what is absent. Funding is **positive at the 82nd percentile of its own history**. Not neutral — positive, meaning that after a 49% drawdown, leveraged longs are *still paying to hold risk*. Realized 30-day vol is at the **13th percentile of two years**. Twenty-four-hour liquidations are $58M. The lowest Fear & Greed print in ten sessions is 25. There is no flush here. There is no forced seller. There is a quiet, orderly, slightly bored decline.

That combination — cheap balance sheet, intact leverage, no vol — is the signature of a market that has not yet had its clearing event. It is *also*, honestly, the signature of a market that grinds sideways for two months and then goes up without ever giving you the flush. I do not know which. What I do know is that the framework's gate board is telling me the truth about this: 3 of 9, with the two lit [V] gates being the two that measure *value* (3, 8) and every gate that measures *fear* (1, 2, 4, 7) dark. Value gates lit, fear gates dark. That is a precise description of where we are, and it is why Phase 1A is unlocked and 1B is not.

The one thing that genuinely changed this week is the Coldcard exploit, and it changed something specific rather than something general — which is exactly why it belongs in §9 rather than in a leg.

### 9.2 What the rubric structurally cannot see

1. **The Coldcard exploit contaminates the holder leg's premise.** *(Bearish.)* Half the holder leg is "exchange reserves declining," scored 3/3. The bullish reading of that metric depends on the destination being safe. A five-year RNG defect in one of the most widely-trusted self-custody devices, now being actively harvested, says a slice of that destination was not. The leg's measurement is unaffected; its meaning is. No leg or gate can express that gap. Secondary: ~1,816 BTC of zero-basis supply now sits with a motivated seller. *(CoinDesk 2026-08-02/03; Bloomberg 2026-08-03; Galaxy Research.)*
2. **The macro gate is binary and the macro risk is not.** *(Bearish.)* Gate 9 reads the same "dark" whether the Fed is merely unhelpful or actively about to tighten. It is the latter: a **60.1% market-implied probability of a September hike**, three hawkish dissents at the July meeting, inflation above target for five years. Simultaneously CLARITY passage odds hit a **record-low 27%**. A binary gate cannot distinguish "no catalyst" from "an identified, dated, adverse catalyst," and the difference is worth a point. *(CME FedWatch via CNBC 2026-07-29; Polymarket via crypto.news.)*
3. **BTC did not participate in a real risk-on impulse.** *(Bearish, and it is the tell I trust most.)* SPX +2.55% over five sessions on genuine Iran de-escalation, VIX to 15.81, Brent −5.19%. BTC: −2.12% over two weeks, and it printed its low for the period *today*. At a correlation of 0.341, BTC is not being dragged down by equities — it is being left behind by them. The correlation leg is a context label and cannot score this.
4. **System dry powder is contracting.** *(Bearish, disclosed context.)* Aggregate stablecoin supply −3.33% over 90 days to $183.20B. The "cash waiting on the sidelines" story is running in reverse. *(DefiLlama.)*
5. **The pinned-to-the-200-week-mean setup.** *(Bullish.)* Gate 6 scores this as a boolean, but the *magnitude* — +0.43%, essentially zero — is information the boolean discards. Thirty-three sessions of holding a level this exact, with the campaign low intact, is a base rather than a pause. This is why I did not take a larger negative term.

### 9.3 The D1 term

> ## **D1 = −1.0** (mechanical 11 → raw 10.0 → adjusted **10**)

**Direction:** negative. **Size:** 1.0 of the ±2.0 range.
**Factors:** (1) and (2) above — the Coldcard contamination of the holder leg's premise, and the binary macro gate's inability to express a 60%-priced hike plus a record-low regulatory probability. Both are sourced, dated, and structurally invisible to all five legs. Neither re-weights an already-scored input: the holder leg scores *whether* reserves are falling (still true, still 3/3) while the D1 addresses what that migration now *means*; and no leg scores macro at all, so the macro factor cannot be double-counted through the score.

**Is this a bull tool being used bearishly?** Yes, deliberately. The SKILL requires the term be printed every report including at zero, and explicitly says it is not a bull tool. This is its first non-zero use in the framework's history, and it is negative. A negative D1 is also the *safe* direction structurally: it lowers the adjusted score, which only tightens deployment, and it touches nothing protective because every protective rule reads the mechanical 11.

**What it changes:** Phase 1B's score condition goes from met (11 ≥ 11) to unmet (10 < 11). **Capital effect: none** — 1B is short two gates and one [V] gate independently, so it was never deployable this report. What changes is that the report's number now agrees with its stance.

**Falsifier (dated).** Retire the term when **either**: (a) seven consecutive days pass with no new Coldcard sweep wave **and** on-chain tracing shows no attacker deposits to exchanges — the theft becomes a closed, absorbed event; **or** (b) September hike probability falls below **35%** on CME FedWatch, most plausibly on a soft NFP (2026-08-07) or CPI (2026-08-12). **Hard review date: 2026-08-17.** Per the decay rule, if this term is still −1.0 at the same size three reports on, it must be re-argued from fresh evidence or retired to 0.

**A larger term was considered and declined.** −1.5 or −2.0 would have been constructible on factors 3 and 4 as well. Declined because factor 5 cuts the other way with real force — a market pinned to its 200-week mean for 33 sessions with an intact campaign low is not a market I want to mark down two points on macro anxiety, and because the Coldcard event is a vendor defect with a shipped fix, not a protocol failure. −1.0 is the honest size.

### 9.4 Discretionary actions taken and declined

| Channel | Phase | Available? | Action | Reason |
|---|---|---|---|---|
| **D1** | — | yes | **TAKEN, −1.0** | §9.3 |
| **D2** | 1B | **NO** | n/a | Short **two** gates, not exactly one (3 of 5). Independently, the [V] floor fails on lit gates (2 vs 3) and D2 never substitutes for a [V] floor. |
| **D2** | 1A | not needed | — | 1A is already unlocked mechanically (3/9 ≥3, [V] 2 ≥2). |
| **D4** | — | yes | **TAKEN** | Cells set from the read; all deviations ≤10pp of the 6–10 anchor row; EV recomputed from the printed cells. |
| **Override** | 1B | **NO** | n/a | Mechanical 11 < 15, dispositive. |

**Declined and worth recording: a new Phase 1A fill.** The tranche is unlocked and spot is in the zone near its floor. I declined to authorize capital today because the ledger cannot tell me whether the tranche is already full, and deploying into that ambiguity risks upsizing beyond nominal — which is prohibited, and which would also corrupt the very attribution record needed to fix the problem. The conditional in §6.2 makes the fill executable the moment a fresh snapshot exists. This is a data blocker with a one-command remedy, not a market judgment, and I want it graded as such.

### 9.5 Discretion Ledger (D7)

| Date | Channel | The call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 | 0.0 — a +1.0 was constructible and declined (would have been the sole enabler of a threshold cross) | — | — | — | closed (zero) | — |
| 2026-08-01 | D2 | ETH 1A conviction path evaluated, **declined** | — | — | — | closed (declined) | — |
| **2026-08-03** | **D1** | **−1.0 on BTC: Coldcard contamination of the holder-leg premise + a 60%-priced Sept hike the binary macro gate cannot express** | score term, no capital | n/a (no tranche opened) | 7 clean days + no attacker exchange deposits, **OR** Sept hike odds <35%. Review 2026-08-17 | **LIVE** | — |
| **2026-08-03** | **D4** | Cells set from the read: Rally 26 / Range 36 / Retest 24 / Bear 14; EV −1.53% vs spot | — | — | Realized 2w price change vs EV sign, graded next report | **LIVE** | — |
| 2026-08-03 | D2 | BTC 1B — **unavailable** (short 2 gates, [V] floor fails) | — | — | — | n/a | — |

**No D5 stops are outstanding**: zero analyst-channel tranches have ever been opened, so no price-only stop attaches to anything. The D1 term above opened no position.

### 9.6 What would change my mind

- **Bullish flip:** a soft NFP on **2026-08-07** driving September hike odds under 35%, *plus* a weekly close above **$66,500** (the Phase 1A zone ceiling and the 200dma approach). That combination relights gate 9 and constitutes trend-structure repair — the collar's strong-claim unlock. I would retire the D1 term and reconsider 1B on gates.
- **Bearish flip:** a daily close below **$57,747.77** (the 2026-07-01 campaign low). That prints the fresh lower-low the §5 trend residual currently denies, turns the residual on, throttles any Override to quarter-size, and — importantly — would likely light gates 1, 2 and 7 together, which is the *only* realistic route to a 1B unlock. I would welcome it. That is the whole point of the framework.
- **Neither by 2026-08-17:** the D1 term is re-argued from fresh evidence or retired to zero, per the decay rule.

---

## 10. Bull vs Bear Scorecard — BTC

**Bull (8):**
1. ✅ MVRV-Z **0.347** — deep value; realized price $52,367 vs spot $63,844
2. ✅ Price **on** the 200-week mean (+0.43%) — gate 6 lit at maximum margin
3. ✅ LTH supply at a **record**; +1.29M BTC 30d net position change, strongest in 6 years
4. ✅ Exchange reserves falling — −78,000 BTC/6mo; lowest since late 2023
5. ✅ Weekly RSI **38.84**, 22.87th percentile of its own 2y history — momentum exhausted
6. ✅ July ETF flows **+$172.4M**, breaking a two-month outflow streak
7. ✅ Geopolitical de-escalation real and dated: Iran talks resumed, Brent −5.19%, VIX 15.81
8. ✅ Correlation to SPX **0.341** — asset-specific tape, no risk-on surcharge, Phase-2 corr condition passes

**Bear (10):**
1. ❌ **Coldcard exploit ongoing** — ~1,816 BTC / $114M, 5,200+ addresses, fourth wave live
2. ❌ Fed: **60.1% priced probability of a September hike**; three hawkish dissents 2026-07-29
3. ❌ CLARITY Act punted past recess; 2026 passage odds at a **record-low 27%**
4. ❌ F&G 3d avg **27.33** — fear, not capitulation; **zero** sub-16 prints in 10 sessions
5. ❌ Funding **+6.12% annualized, 82nd percentile** — longs still paying; zero negative intervals in 45
6. ❌ **Hash Ribbon in miner capitulation since 2026-06-08**, no recovery cross (computed)
7. ❌ Weekly ETF flow negative: **−$61.53M** to Jul-31; Jul-31 alone −$265.4M, IBIT −$122.7M
8. ❌ Aggregate stablecoin supply **−3.33% over 90 days** — system dry powder contracting
9. ❌ Price −10.07% below a **falling** 200dma (−3.62%/20 sessions); 50dma below 200dma
10. ❌ BTC did **not** participate in a +2.55% five-session SPX advance

> **Net: 8 bull / 10 bear → net bearish by 2.** The scorecard is not within 1 of balanced, so that collar limb is not met — but the |EV-vs-spot| limb is, and the collar is active regardless.

---

## 11. Change Log vs 2026-08-01 (2 days)

| Factor | 2026-08-01 | 2026-08-03 | Direction |
|---|---|---|---|
| Canonical spot | $63,007.10 | **$63,843.94** | +1.33% |
| Sentiment leg (3d F&G) | 2 (26.67) | **2 (27.33)** | flat |
| Momentum leg (weekly RSI) | 2 (38.22) | **2 (38.84)** | flat |
| Valuation leg (MVRV-Z) | 4 (0.41) | **4 (0.3469)** | flat band, fractionally cheaper |
| Capitulation leg | 0 | **0** | flat |
| Holder leg | 3 | **3** | flat |
| **Mechanical score** | **11** | **11** | **flat — no leg moved** |
| **D1 term** | 0.0 | **−1.0** | **first non-zero D1 in framework history** |
| **Adjusted score** | 11 | **10** | ↓ 1 (discretionary only) |
| Gates | 3/9 [3,6,8], [V] 2 | **3/9 [3,6,8], [V] 2** | flat |
| Gate 5 (Hash Ribbon) | dark, **debt clock report 2** | dark, **debt DISCHARGED by computation** | resolved ✅ |
| Gate 9 (macro) | ❌ | **⚠️** (Iran de-escalation real; Fed/CLARITY adverse) | marginal improvement, still uncounted |
| EV vs spot | −0.80% | **−1.53%** | more negative |
| Realized 2w change | −2.78% | **−2.12%** | less negative |
| 30d corr vs SPX | 0.241 | **0.341** | ↑ still mild |
| 200-week distance | −0.77% | **+0.43%** | crossed **above** the mean |
| FR companion | 7 (Channel B) | **6 (Channel B)** | ↓ short case weakening |
| Position band | STALE 26h | **STALE 50.2h** | ↓ **degrading — 22h from EXPIRED** |
| Dry powder | $14,288.54 | **$14,408.87** | +$120 |
| T-bill benchmark | 3.68% | **3.78%** | ↑ cost of dry powder up |
| **New** | — | **Coldcard exploit ($114M, ongoing)**; **CLARITY punted (27%)**; **Sept hike 60.1% priced** | — |

---

## 12. Companion Flying Rocket Score (Hard Rule 5 — computed, not estimated)

`compute.mjs fr-companion --market {...} --rounding half-up`

| Field | Value |
|---|---|
| **Channel** | **B** (counter-trend bounce) — routing verified: −49.43% below the 1-yr high (>20%), 200dma falling (−3.62%/20 sessions), price 10.07% beneath it |
| Legs (Channel B) | rally_extension 1 · local_exhaustion 1 · resistance 1 · bear_structure 2 · relative_sentiment 1 |
| Penalty | 0 (squeeze tier: none; maturity: none) |
| **FR composite** | **6 / 20** |
| Channel B Phase 1A line | 13 — **short by 7** |
| Phase-of-cycle cap | **not applied** (Channel B is the live channel; cap value would be 8) |
| Confidence | full — no missing inputs |

> **Cross-validation: CONSISTENT ✅ — and the label is UNQUALIFIED.**
> FK **11** (mechanical) / FR **6**. Inversely related, both < 12, so Hard Rule 5's both-≥12 condition is not met. The label carries **full evidentiary weight** because the Channel A phase-of-cycle cap is **not binding** — Channel B is the live channel — so the both-≥12 check is genuinely falsifiable here rather than vacuous by construction.
> **FR≥9 tripwire: NOT fired** (6 < 9). Standalone FR report: **not owed** on BTC. Short-side liquidation volume did not approach the $100M trigger.

---

## 13. Strategic Verdict — BTC

**Adjusted score 10/20 · Mechanical 11/20 · D1 −1.0 · Weighted EV $62,870 · EV vs spot −1.53% · Realized 2w −2.12% · F&G 27.33 (Fear) · Gates 3/9 ([V] 2) · Stance: HOLD, authorize nothing new until the ledger is fixed**

> **Verdict-Confidence Collar: ACTIVE.** |EV-vs-spot| **1.53% < 2%** — the limb is met. (Mechanical score 11 is outside the 6–10 band; the scorecard at 8–10 is outside "within 1 of balanced." One limb suffices.) **No directional regime resolution is claimed anywhere in this report.** Every forward statement below carries a probability or an IF→THEN plus a named falsifier.

Two years of watching this framework grade itself has taught me that its edge was never in calling bottoms — it was in refusing to spend capital on partial evidence. This report is a clean instance of that discipline, in a place where the temptation runs the other way. BTC is objectively cheap: MVRV-Z 0.347, half off the high, sitting on a 200-week mean that has anchored every prior cycle, with long-term holders accumulating at a six-year record and coins leaving exchanges. If you only read the balance sheet you would buy it with both hands. But the framework asks a second question — *is anyone actually afraid?* — and the answer is a clear no. Funding is positive at the 82nd percentile. Realized vol is at the 13th percentile of two years. The lowest fear print in ten sessions is 25. Four of the six [V] fear gates are dark, and the two that are lit are both value gates. This is a cheap market, not a frightened one, and the pyramid's big tranches are reserved for frightened ones.

Two things sharpen that this week. The first is the Coldcard exploit, and I want to be precise about how I have handled it: it is **not** a §7 narrative break — a vendor's 2021 firmware RNG defect, with a shipped fix, does not void the thesis for the asset — but it is not nothing either, because BTC's maximum-scoring holder leg rests on coins migrating into self-custody, and this event says a slice of that custody was reproducible offline for five years. That is a gap between a metric and its meaning, which is exactly what the D1 term exists for. The second is the macro calendar. Gate 9 reads "dark" whether the Fed is merely unhelpful or has a **60%-priced hike** two meetings out with three dissenters already on record; the binary cannot express the difference, and the difference is real. Those two factors, both sourced and both structurally invisible to all five legs, are the entire basis of a **−1.0 D1** — the first non-zero discretionary term this framework has ever taken, and it is negative. I would rather the layer's first entry in the grading ledger be a bearish one, because it establishes that the tool discriminates rather than flatters.

What actually stops capital moving today is neither of those. It is the ledger. Phase 1A is genuinely unlocked — score 10 ≥ 8, gates 3/9 with [V] 2 — and spot sits inside the $63,000–66,500 zone near its floor. But `position.mjs` returns 0.00000184 BTC with `basis.reliable = false` on five unbacked disposals, custody RECONCILED, zero withdrawals, and **zero deal tags on two open deals**, against reports that have narrated "10% Phase 1A at ~$65,000 blended" for weeks. The ledger cannot confirm that figure and cannot refute it, and at 50.2 hours the snapshot is in the STALE band, which Hard Rule 8 explicitly bars from resolving a phase-dependent question. So the choice is between deploying into a tranche that may already be full — prohibited upsizing — and waiting for a refresh that costs one command. I am waiting, and I have written the fill as a conditional so that it executes the moment the data exists. Idle cash is not free: $14,408.87 at a 3.78% T-bill is about $45 a month. That is the correct price to pay for not double-counting your own position.

### Numbered action items

1. **Refresh the position snapshot — before anything else.** `POST /link` in the personal-accounting app, then `node tools/position.mjs btc`. Requires band **FRESH (≤12h)**. At 50.2 hours the snapshot is 22 hours from **EXPIRED**, at which point Hard Rule 4 forces a cold start and the Phase 1A question becomes unanswerable rather than merely unanswered.
2. **Resolve the 1A fill state, then execute the §6.2 conditional.** If unfilled → ladder 10% across $63,000–66,500 in three clips ($66,000 / $64,500 / $63,200), never at the top of the zone, tagged **`FK-P1A`**. If filled at ~$65,000 → 1A is complete, deploy nothing.
3. **Tag the two untagged open deals.** `PUT /api/investments/deal-note` with the `dealKey`, the correct `FK-*` tag (or `UNFRAMED`), and a note whose first line is `report=reports/btc_fallen_knives_20260803_1411.md`. Until this is done, `performance_by_tag` stays empty and no calibration can ever grade a phase against money rather than price.
4. **Fix the unbacked-disposal defect.** Five disposals exceed the replay by 0.0336 BTC (account-wide the figure is far larger — 8.51 ETH on the ETH side). Until acquisitions are ingested, no BTC cost basis, unrealized PnL or ROI can be quoted by this framework, and the §7 "price ≥40% above blended cost" trim limb is permanently unevaluable.
5. **Deploy nothing into Phases 1B / 2 / 3.** 1B is short two gates and one [V] gate; the Override is dead at mechanical 11 < 15; D2 is unavailable. No route exists this report.
6. **Trade NFP (2026-08-07 08:30 ET) as the week's pivot, not as a signal.** Sub-35% September hike odds after the print is the cleanest path to gate 9 relighting. Do not pre-position for it.
7. **Track the Coldcard falsifier daily.** New sweep waves and attacker exchange deposits. Seven clean days plus no exchange deposits retires half the D1 term.
8. **Watch the Hash Ribbon weekly.** 30d $899.7 EH/s vs 60d $917.2 EH/s — 1.9% apart. A recovery cross lights gate 5 and takes the board to 4/9.
9. **Hold the compound stop unchanged** at $55,000 AND mechanical score <12; catastrophic floor $50,000. Checkpoint rolls to **Sunday 2026-08-09 00:00 UTC** and cannot fire (0 of 2 closes).
10. **Run `/flying-rocket-analytics eth`** — a standalone FR report has been owed on ETH since the 2026-08-01 companion printed 9 and has not been discharged. Not owed on BTC (companion 6).

> ## The Pattern
>
> **IF** NFP (2026-08-07) prints soft, September hike odds fall below 35%, **AND** BTC closes a week above $66,500 → **THEN** gate 9 relights, trend-structure repair is on the table, the D1 term retires, and Phase 1B becomes a live question on gates rather than a theoretical one. *Falsifier: no weekly close above $66,500 by 2026-08-23.*
>
> **IF** BTC prints a daily close below $57,747.77 (the 2026-07-01 campaign low) → **THEN** the §5 trend residual turns on, the Override throttles to quarter-size, and gates 1, 2 and 7 come into range together — the only realistic route to unlocking the 15% tranche. *This is the outcome the framework is built to want. Falsifier: the low holds through 2026-08-31.*
>
> **IF** funding stays positive at the 80th percentile and realized vol stays at the 13th while price drifts → **THEN** nothing unlocks, the book stays dry, and the T-bill keeps paying 3.78%. *This is the modal path at 36%, and there is no shame in it.*
>
> **IF** the attacker begins depositing the ~1,816 stolen BTC to exchanges in size → **THEN** treat it as a dated supply overhang, not a thesis change; the §7 narrative-break determination stands. *Falsifier for the overhang: coins remain unmoved for 30 days.*

---

*Cash is a position. Patience is alpha — but idle cash has a measurable yield cost, and at $14,408.87 against a 3.78% bill that cost is about $45 a month. Pay it deliberately, and only until the ledger can tell you what you already own.*

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-BTC-20260803-1411 | UNVERIFIED | crypto |
| 1B | FK-P1B-BTC-20260803-1411 | LOCKED | crypto |
| 2 | FK-P2-BTC-20260803-1411 | LOCKED | crypto |
| 3 | FK-P3-BTC-20260803-1411 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_fallen_knives_20260803_1411.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-08-03",
  "spot": { "value": 63843.94, "source": "median of 4 synchronized live quotes: Binance BTCUSDT $63,908.00 / Coinbase BTC-USD $63,855.18 / Kraken XBTUSD $63,832.70 / CoinGecko $63,823.00 (all 2026-08-03 ~18:02 UTC); spread 0.133%, all live; Yahoo BTC-USD $63,849.32 EXCLUDED as a frozen bar close. SKILL-mandated median used, NOT the tool's priority-first spot.canonical ($63,823) — delta -0.033%, changes no band, gate boolean or cap tier; tool-side flip is commit 12 of the 2026-08 toolchain-extension plan" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 4, "capitulation": 0, "holder": 3 },
    "discretionary": -1.0,
    "mechanical": 11,
    "raw": 10.0,
    "adjusted": 10,
    "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 6, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 26, "low": 66500, "high": 71000 },
      { "name": "Range", "p": 36, "low": 62000, "high": 66500 },
      { "name": "Retest", "p": 24, "low": 57500, "high": 62000 },
      { "name": "Bear", "p": 14, "low": 50000, "high": 57500 }
    ],
    "stated_ev": 62870.00,
    "vs_spot_pct": -1.53
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "63000-66500 zone, spot 63843.94 INSIDE it near the floor. Unlock conditions MET (adjusted 10>=8, gates 3/9>=3, [V] 2>=2). NO entry_price and NO fill authorized this report: the ledger is STALE at 50.2h and basis.reliable=false, so whether the narrated 10% at ~65000 is real cannot be resolved in either direction, and deploying into that ambiguity risks prohibited upsizing beyond nominal. Conditional authorization written in section 6.2: IF a FRESH snapshot shows 1A unfilled THEN ladder 10% across 63000-66500 in three clips (66000/64500/63200), tag FK-P1A. deployed_pct reported as 0 because no fill is corroborated by the position of record; the narrated 10% is UNVERIFIED, not confirmed", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "58000-61500 DOUBLE-BLOCKED: adjusted score 10<11 (the D1 -1.0 removes the threshold cross that mechanical 11 made on 2026-08-01) AND gates 3/9<5 with [V] 2<3. D2 unavailable on two independent grounds — short by TWO gates not exactly one, and D2 substitutes for a gate never for a [V] floor", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "54000-58000 frozen (score 10<15, gates 3/9<6)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 },
    "note": "NO stop parameter changed value. Mechanical score 11<12, so the compound stop's score axis IS satisfied — stop effectively price-gated at $55,000 until the score re-crosses 12 (carried state, same as Aug-01). The D1 -1.0 has ZERO effect on this line: the compound stop reads the MECHANICAL score per the 2026-07-27 governing rule, so a negative discretionary term cannot make the book's stop fire more readily than the evidence warrants, exactly as a positive one could not suppress it. Coherence: catastrophic $50,000 strictly below deepest named zone floor $54,000 = PASS (compute.mjs stop-coherence pass:true). No D5 stops — zero analyst-channel tranches have ever been opened; the D1 term opened no position. Max drawdown spot-to-compound-line -13.85%, disclosed; purchases no loosening. D6 ratchet: compliant, only the checkpoint date moved (exception 3, calendar validity).",
    "migration": [
      { "parameter": "checkpoint date", "tier": "checkpoint date", "old": "2026-08-02", "new": "2026-08-09", "direction": "forward roll", "rationale": "The Aug-02 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below 55000; the weekly close printed ~63.7K). Rolls to the next weekly close. D6 exception 3 — calendar validity, not a discretionary widening." }
    ],
    "checkpoint": {
      "date": "2026-08-09",
      "line": 55000,
      "condition": ">=2 consecutive weekly closes <55000 AND mechanical score <12",
      "closes_below": 0,
      "adr": 1599.09,
      "dist_x_adr": 5.53,
      "side": "spot 16.08% above line; structurally cannot fire (0 of 2 required closes). Tier-1 release BEFORE this checkpoint: YES — nonfarm payrolls Fri 2026-08-07 08:30 ET, named in the falsifier: hot print pushes Sept hike odds above 60.1% and the tape toward Retest, soft print cuts them and supports Rally. NFP cannot produce two sub-55000 weekly closes by Aug-09, so the unfireable status is robust to it. Next after: CPI Wed 2026-08-12 08:30 ET."
    }
  },
  "companion_fr": {
    "score": 6,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 49.43, "ma200_falling": true, "ma200_slope20_pct": -3.62, "price_below_ma200_pct": -10.07 },
    "legs_channel_b": { "rally_extension": 1, "local_momentum": 1, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 57747.77, "low_40s_date": "2026-07-01", "bounce_pct": 10.52, "daily_rsi14": 49.33, "weekly_rsi14": 38.84, "bounce_age_sessions": 33, "funding_annualized_pct": 6.12 },
    "gates_note": "Channel B Phase 1A line 13 — short by 7. Penalty 0 (squeeze tier none, maturity none). Confidence full, no missing inputs.",
    "cross_validation": "consistent — FK 11 (mechanical) / FR 6, inversely related, both <12 so Hard Rule 5's both->=12 condition is NOT met. Label UNQUALIFIED and carrying full evidentiary weight because the Channel A phase-of-cycle cap is NOT binding (Channel B is the live channel), so the both->=12 check is genuinely falsifiable rather than vacuous by construction.",
    "standalone_report_owed": false,
    "standalone_report_note": "Not owed on BTC (companion 6 < 9; no phase-unlock threshold crossed; short-side liquidation volume nowhere near the $100M trigger; the cap is not binding). SEPARATELY OUTSTANDING: the ETH standalone FR report owed since the 2026-08-01 ETH companion printed 9 has NOT been discharged."
  },
  "position": {
    "source": "tools/position.mjs btc",
    "band": "STALE",
    "age_min": 3011,
    "age_driver": "holdings_as_of",
    "holdings_as_of": "2026-08-01T15:51:56Z",
    "generated_at": "2026-08-01T15:54:04Z",
    "custody_status": "RECONCILED",
    "qty": "0.00000184",
    "trade_derived_qty": "0.00000184",
    "off_venue_qty": null,
    "withdrawn_qty": "0",
    "basis_reliable": false,
    "oversold_qty": "0.03360450",
    "unbacked_disposal_count": 5,
    "short_qty": null,
    "avg_cost_usd": null,
    "total_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd_upper_bound": 1639.83,
    "attribution": "UNTAGGED",
    "untagged_open_deals": 2,
    "performance_by_tag": [],
    "performance_by_tag_prefix": [],
    "dry_powder_stable_usd": 14408.87,
    "portfolio_total_usd": 19790.26,
    "futures_equity_usd": 0.0,
    "note": "STALE band at 50.2h (22h from EXPIRED) — descriptive use ONLY; may NOT satisfy a phase-dependent unlock precondition and may NOT fill a realized ledger column. basis.reliable=false: 5 unbacked disposals exceeded the replayed position by 0.0336045 BTC — coins sold whose acquisition was never ingested. The snapshot states explicitly this is NOT a margin short (short_qty null), and custody is RECONCILED with zero withdrawals so it is NOT an off-venue case. No average cost, cost basis, unrealized PnL or ROI reported; realized $1,639.83 is an UPPER BOUND on a partial fill history. Position Reconciliation: prior reports narrate '10% Phase 1A at ~$65,000 blended' carried forward for weeks; the ledger shows dust with no derivable basis and zero deal tags. The ledger can neither confirm nor refute it, so the figure is reported UNVERIFIED and NO deployment is sized against it. performance_by_tag is an EMPTY ARRAY — zero tagged deals exist, so nothing can be asserted about how Phase 1A entries have actually performed, and nothing is."
  },
  "trend_residual": { "active_downtrend": false, "basis": "price is 10.07% below a falling 200dma (-3.62%/20 sessions) and 50dma sits below 200dma, but NOT making lower lows — the campaign low $57,747.77 (2026-07-01) has held 33 sessions and the August lows (62233.01 Aug-1, 62235.13 Aug-3) sit ~7.8% above it and are flat against each other, not descending", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15." },
  "correlation": { "value_30d_vs_spx": 0.341, "window": "2026-06-18 to 2026-08-03", "method": "Pearson on daily log returns, 30 overlapping return pairs, Yahoo closes, computed 2026-08-03", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.341 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds — it is barred on gate arithmetic" },
  "discretion": {
    "d1_taken": true,
    "d1_value": -1.0,
    "d1_direction": "negative",
    "d1_factors": [
      "Coldcard exploit (~1,816 BTC / ~$114M across 5,200+ addresses since 2026-07-30, fourth wave live) contaminates the PREMISE of the holder leg's 3/3 score: half that leg is 'exchange reserves declining', whose bullish reading depends on the destination being safe, and a 2021 firmware RNG defect made five years of Coldcard keys reproducible offline. The leg's MEASUREMENT is unaffected and still scores 3 — the D1 addresses what the migration now MEANS, which no leg or gate can express. Secondary: ~1,816 BTC of zero-basis supply now sits with a motivated seller. Sources: CoinDesk 2026-08-02/03, Bloomberg 2026-08-03, Galaxy Research.",
      "Gate 9 is BINARY and the macro risk is not: a 60.1% market-implied probability of a SEPTEMBER HIKE (CME FedWatch via CNBC 2026-07-29) with three hawkish dissents at the July FOMC, plus CLARITY Act 2026 passage odds at a record-low 27% (Polymarket via crypto.news). A binary gate cannot distinguish 'no catalyst' from 'an identified, dated, adverse catalyst'. No leg scores macro at all, so this cannot be double-counted into the score."
    ],
    "d1_falsifier": "Retire when EITHER (a) seven consecutive days with no new Coldcard sweep wave AND no attacker deposits to exchanges on-chain, OR (b) September hike probability falls below 35% on CME FedWatch (most plausibly on NFP 2026-08-07 or CPI 2026-08-12). HARD REVIEW DATE 2026-08-17; per the decay rule, if still -1.0 at the same size three reports on it must be re-argued from fresh evidence or retired to 0.",
    "d1_effect": "Phase 1B score condition goes from MET (mechanical 11 >= 11) to UNMET (adjusted 10 < 11). CAPITAL EFFECT: NONE — 1B is independently short two gates (3/9 vs 5) and one [V] gate (2 vs 3), so it was never deployable this report by any route. FIRST NON-ZERO D1 IN FRAMEWORK HISTORY, and it is NEGATIVE.",
    "d1_larger_considered_declined": "-1.5 / -2.0 were constructible on two further legs-invisible factors (BTC failing to participate in a +2.55% 5-session SPX advance at corr 0.341; aggregate stablecoin supply -3.33% over 90 days). DECLINED because price pinned to the 200-week mean at +0.43% for 33 sessions with the campaign low intact cuts the other way with real force, and because the Coldcard event is a vendor defect with a shipped fix, not a protocol failure.",
    "d2_available": false,
    "d2_taken": false,
    "d2_phase": "1B",
    "d2_detail": "UNAVAILABLE on two independent grounds: the path opens only at a shortfall of EXACTLY ONE gate and 1B is short TWO (3 of 5 required); and the [V] floor would fail on lit gates (2 lit vs 3 required), which D2 may never substitute for. Phase 1A needs no D2 — it is already unlocked mechanically.",
    "override_evaluated": true,
    "override_fired": false,
    "override_detail": "DOES NOT FIRE. Mechanical score 11 < 15 — dispositive. Two further independent failures: 3-day F&G 27.33 is not <=15, and the Override presupposes a corroborated deployed tranche which the ledger cannot supply. No near-fire to log.",
    "d4_taken": true,
    "d4_detail": "Cells set from the read against the 6-10 anchor row (adjusted score 10): Rally +6, Range +1, Retest -6, Bear -1 — all within the +/-10 percentage-point band, none requiring a >10pp reason line. EV recomputed from the printed cells as the final step.",
    "declined_action": "A NEW PHASE 1A FILL was declined despite the tranche being unlocked and spot sitting inside the zone near its floor. Reason: the ledger cannot tell whether 1A is already full, and deploying into that ambiguity risks prohibited upsizing beyond nominal while corrupting the attribution record needed to fix it. This is a DATA blocker with a one-command remedy, not a market judgment, and should be graded as such. Section 6.2 carries the executable conditional.",
    "non_mechanical_capital_pct": 0
  },
  "narrative_break_evaluation": {
    "event": "Coldcard hardware wallet exploit — ~1,816 BTC / ~$114M drained from 5,200+ addresses in four waves since 2026-07-30; root cause a March 2021 Coinkite firmware build that routed seed generation to a predictable software RNG instead of the secure element's hardware RNG, making five years of keys reproducible offline",
    "trigger_tested": "critical security breach (section 7, Exit 100%)",
    "determination": "NOT A NARRATIVE BREAK — no exit, no trim",
    "reasoning": "The defect is a single vendor's product flaw. Bitcoin's consensus rules, cryptography, issuance and settlement are untouched; every affected key was weak at generation, which is why the sweeps execute offline. Same class of event as an exchange failure — severe for the users hit, not a change in what the asset is. The section 7 trigger is written for 'irreparable' and 'thesis voided' and this is neither: Coinkite shipped emergency firmware and the remedy (regenerate seed, move coins) is available to every holder. What it DOES do is impair the INTERPRETATION of the holder leg's cold-storage premise, which is why it is priced as a D1 term rather than an exit.",
    "sources": ["CoinDesk 2026-08-02", "CoinDesk 2026-08-03", "Bloomberg 2026-08-03", "Galaxy Research via Crowdfund Insider", "TheHackerNews 2026-08"]
  },
  "key_inputs": {
    "fng_spot": 28,
    "fng_3d_avg": 27.33,
    "fng_streak_le15_days": 0,
    "fng_lowest_of_last_10_prints": 25,
    "fng_percentile_vs_2y": 34.98,
    "fng_second_provider_context": "COINOTAG 27 on 2026-08-02 — 1 point from the pinned provider, far under the 10-point disclosure bar; no provider switch",
    "weekly_rsi14": 38.84,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_confidence": "ok",
    "weekly_rsi_percentile_vs_2y": 22.87,
    "daily_rsi14": 49.33,
    "sma_200w": 63549.42,
    "pct_vs_sma200w": 0.43,
    "gate6_within_8pct": true,
    "ma200d": 70966.67,
    "ma200d_slope20_pct": -3.62,
    "pct_vs_ma200d": -10.07,
    "ma50d": 63313.99,
    "pct_vs_ma50d": 0.84,
    "campaign_low": 57747.77,
    "campaign_low_date": "2026-07-01",
    "bounce_pct_off_low": 10.52,
    "bounce_age_sessions": 33,
    "mvrv_z": 0.3469,
    "mvrv_z_asof": "2026-08-02",
    "mvrv_z_source": "bitcoin-data.com /v1/mvrv-zscore/last",
    "mvrv_z_cross_check": "Santiment mvrv_usd_z_score for BTC printed 0.371 on 2026-07-04 vs bitcoin-data.com 0.3315 same date — two independent providers 0.04 apart on the same scale. Recorded because the companion ETH report is forced onto the Santiment series.",
    "mvrv_ratio": 1.2131,
    "realized_price": 52367.36,
    "drawdown_from_ath_pct": -49.38,
    "ath": 126080,
    "ath_date": "2025-10-06",
    "funding_ann_pct": 6.12,
    "funding_mean_per_8h_pct": 0.01,
    "funding_negative_intervals_in_45": 0,
    "funding_longest_negative_run": 0,
    "funding_percentile_vs_history": 82.04,
    "hash_ribbon_status": "MINER CAPITULATION — 30d hashrate MA 899.7 EH/s BELOW 60d MA 917.2 EH/s, since 2026-06-08. COMPUTED from blockchain.info charts/hash-rate (362 daily points, last 2026-08-02). Gate 5 stale-input debt clock DISCHARGED at report 3.",
    "hash_ribbon_trap_note": "A web search for 'Hash Ribbon August 2026' surfaced a Bitbo/Cointelegraph piece announcing Hash Ribbons had 'signaled a potential end to miner capitulation' with a 90.66T difficulty ATH — the article is dated AUGUST 20, 2024. Scored off the headline, gate 5 would have lit on two-year-old data. Computed value says the opposite.",
    "liquidations_btc_24h_usd_m": 58.01,
    "liquidations_eth_24h_usd_m": 87.73,
    "liquidations_source": "COINOTAG 2026-08-02/03",
    "liquidations_event_driven_usd_m": 280,
    "lth_supply": "record high; 30d net position change +1.29M BTC, strongest accumulation in more than six years (CryptoQuant)",
    "exchange_reserves_trend": "-78,000 BTC over 6 months; Binance/OKX/Gemini -100,000 BTC since Feb 2026; lowest since late 2023 (CryptoQuant)",
    "etf_flow_july_usd_m": 172.4,
    "etf_flow_week_to_jul31_usd_m": -61.53,
    "etf_flow_jul31_usd_m": -265.4,
    "etf_flow_note": "July net POSITIVE +$172.4M, breaking a two-month outflow streak — gate 4 moving AWAY from its 2%-of-AUM bar. But the week to Jul-31 was -$61.53M and Jul-31 alone -$265.4M (IBIT -$122.7M, Fidelity -$54.8M, Bitwise -$17.8M, Ark -$17.5M), the largest single-day withdrawal since Jul-13. Gate 4 remains the nearest [V] gate.",
    "adr5": 1599.09,
    "adr5_sessions": "2026-07-30 to 2026-08-03, none abbreviated, none excluded",
    "realized_2w_change_pct": -2.12,
    "realized_2w_basis": "vs 2026-07-20 close $65,230.03 (Yahoo)",
    "tbill_3m_pct": 3.78,
    "real_yield_10y_tips_pct": 2.41,
    "vix": 15.81,
    "dxy": 100.00,
    "brent": 83.77,
    "spx": 7602.56,
    "spx_5session_change_pct": 2.55,
    "fed_funds_target": "3.50-3.75%, held 9-3 on 2026-07-29 with Hammack/Kashkari/Logan dissenting hawkish",
    "sept_fomc_hike_probability_pct": 60.1,
    "clarity_act_2026_passage_odds_pct": 27,
    "rv30_pct": 29.38,
    "rv30_percentile_vs_2y": 13.05,
    "deribit_dvol": 34.81,
    "deribit_atm_iv_pct": 32.23,
    "deribit_skew_90_110_pct": 7.79,
    "deribit_vrp_pct": 2.85,
    "net_liquidity_usd_t": 5.83,
    "hy_oas_pct": 2.84,
    "nfci": -0.554,
    "stablecoin_supply_usd_b": 183.20,
    "stablecoin_change_30d_pct": -0.49,
    "stablecoin_change_90d_pct": -3.33,
    "tier1_next_5_sessions": ["Nonfarm payrolls (July Employment Situation) Fri 2026-08-07 08:30 ET"],
    "tier1_window_verified": "compute.mjs tier1 --from 2026-08-03 --sessions 5 → window 2026-08-04..2026-08-10, returns exactly one tier-1 event (NFP 2026-08-07) and zero warnings. Report is NOT an incomplete-data report on the calendar dimension.",
    "tier1_beyond_window": ["CPI (July) Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14", "FOMC decision Tue-Wed 2026-09-15/16"],
    "stale_input_debt": []
  },
  "collar": {
    "band_triggered": true,
    "reasons": ["|EV-vs-spot| 1.53% < 2%"],
    "mechanical_score_in_6_10_band": false,
    "scorecard_within_1_of_balanced": false,
    "scorecard": "8 bull / 10 bear — net bearish by 2, outside the within-1 limb",
    "effect": "no directional regime resolution claimed anywhere in the report; every forward statement carries a probability or an IF->THEN plus a named falsifier"
  },
  "verdict": "HOLD; authorize nothing new until the ledger is refreshed. Mechanical 11/20 — NO LEG MOVED from Aug-01. D1 = -1.0, the FIRST NON-ZERO DISCRETIONARY TERM IN FRAMEWORK HISTORY, and it is NEGATIVE; adjusted 10/20. THE READ: the bottom's plumbing is in place and its psychology is absent. Price sits ON the 200-week mean (+0.43%, $63,549), MVRV-Z 0.3469 against a realized price of $52,367, LTH supply at a record with the strongest 30d accumulation in six years, reserves -78K BTC/6mo. Against that: funding POSITIVE at the 82nd percentile (zero negative intervals in 45), realized 30d vol at the 13th percentile of two years, 24h liquidations $58M, lowest F&G print in ten sessions 25. Value gates lit (3, 8), every fear gate dark (1, 2, 4, 7) — a cheap market, not a frightened one, and the pyramid's big tranches are reserved for frightened ones. D1 -1.0 RATIONALE: (i) the Coldcard exploit (~1,816 BTC/$114M, 5,200+ addresses, fourth wave live) contaminates the PREMISE of the holder leg's 3/3 — its bullish reading assumes coins leaving exchanges go somewhere safer, and a 2021 firmware RNG defect made five years of keys reproducible offline; (ii) gate 9 is binary and cannot express a 60.1%-priced SEPTEMBER HIKE with three hawkish dissents plus CLARITY at record-low 27% odds. NARRATIVE-BREAK CALL MADE EXPLICITLY: Coldcard is NOT a section 7 break — a vendor firmware defect with a shipped fix does not void the asset thesis — which is exactly why it is priced as a D1 term rather than an exit. GATE 5 DEBT DISCHARGED BY COMPUTATION, and it mattered: the top search result announcing a Hash Ribbon recovery was dated AUGUST 2024; the computed series (blockchain.info, 30d 899.7 EH/s vs 60d 917.2 EH/s) says miner capitulation has run since 2026-06-08. WHAT ACTUALLY BLOCKS CAPITAL is neither market nor macro: Phase 1A is genuinely unlocked (10>=8, gates 3/9, [V] 2) with spot inside the $63,000-66,500 zone, but position.mjs returns 0.00000184 BTC, basis.reliable=false on 5 unbacked disposals, custody RECONCILED with ZERO withdrawals, ZERO deal tags on two open deals, against a narrated '10% at ~$65,000 blended' retyped for weeks. At 50.2h the snapshot is STALE — barred from resolving a phase-dependent question — and 22h from EXPIRED. Fill written as an executable conditional instead of guessed. Dry powder $14,408.87 at a 3.78% T-bill = ~$45/month, the correct price for not double-counting your own position. FR COMPANION 6/20 Channel B against a line of 13 — cross-validation consistent and UNQUALIFIED (cap not binding). Collar ACTIVE (|EV-vs-spot| 1.53% < 2%): no directional regime resolution claimed.",
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_fallen_knives_20260803_1411.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "BTC",
      "report_date": "2026-08-03",
      "report_local_time": "14:11",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-BTC-20260803-1411",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-BTC-20260803-1411",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-BTC-20260803-1411",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-BTC-20260803-1411",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260803_1411.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-03",
          "report_local_time": "14:11"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "btc_fallen_knives_20260803_1411.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "BTC",
    "report_date": "2026-08-03",
    "report_local_time": "14:11",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-BTC-20260803-1411",
      "FK-P1B-BTC-20260803-1411",
      "FK-P2-BTC-20260803-1411",
      "FK-P3-BTC-20260803-1411"
    ],
    "status": "REGISTERED"
  }
}
```
