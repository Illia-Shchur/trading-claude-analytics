# 🔪 FALLEN KNIVES ANALYTICS — BTC — 2026-08-06

## THURSDAY LATE SESSION — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Thursday, 2026-08-06, 18:36 EDT (2026-08-06T22:36Z)
### Asset: BTC | Prior Score: 10/20 adjusted (11 mechanical, 2026-08-05) | Current Score: **11/20 adjusted (11 mechanical)**

---

## 1. Headline

Phase 1A is unlocked and spot sits inside its zone. It is the **fourth consecutive report** in which the only thing standing between the framework and a fill is an unreadable ledger — `position.mjs` now returns **EXPIRED at 126.7 hours**, degraded from 94.2h on 08-05. This is a data problem with a one-command fix, not a market judgment, and it is action item #1.

The market itself improved. ETF flows have flipped from "two green sessions" to a **6–7 session inflow streak** — the framework's own ≥5-session sustained-inflow bar, met. That is the single largest change since 08-05 and it is why the discretionary term is cut in half.

---

## 2. Verified Live Data Points

### 2.1 Price — canonical spot reconciliation

| Source | Price (USD) | Venue timestamp | Status |
|---|---|---|---|
| CoinGecko | $64,310.00 | 2026-08-06T22:34:00Z | live |
| Binance BTCUSDT | $64,364.10 | 2026-08-06T22:36:25Z | live |
| Coinbase BTC-USD | $64,310.61 | 2026-08-06T22:36:21Z | live |
| Kraken XBTUSD | $64,306.70 | receipt 22:36Z | live |
| Yahoo BTC-USD | $64,310.61 | last daily bar | **EXCLUDED — frozen bar close, never enters the median** |

**Canonical spot = $64,310.31** — median of the 4 synchronized live quotes (all venue timestamps inside a 2-minute band, well within the 2-hour synchronization window). **Inter-source spread 0.089%**, far below the 0.5% flag. Dispersion is genuine simultaneous venue disagreement and is trivially small; no dual-extreme EV computation required, no low-confidence demotion. Source: `tools/fetch.mjs btc`, fetched 2026-08-06T22:36:24Z.

24h change: −0.44% vs the 2026-08-05 daily close of $64,597.50.

### 2.2 Sentiment

| Metric | Reading | Status |
|---|---|---|
| F&G spot (2026-08-06) | **25** | Extreme Fear |
| F&G 3-day average (25 / 27 / 25) | **25.67** | Extreme Fear — **the scored input** |
| Daily prints ≤15, consecutive | **0 days** | gate 1 dark |
| Last 10 prints | 25, 27, 25, 28, 27, 27, 25, 28, 29, 29 | |
| F&G percentile vs 2y | 27.02 | |

Provider **pinned**: alternative.me raw API daily series. No provider switch, no second-provider divergence ≥10 index points to disclose. Gate-1 streak counts daily prints; the 3-day average is the scored input — both from the same pinned series.

**Proximity note (disclosed context, not a trigger):** 25.67 sits **0.67 index points** above the ≤25 band edge. A single sub-24 print would take the sentiment leg 2 → 3 and the composite 11 → 12. That is the cheapest available upgrade to this book's score, and — see §6 — it is also what would restore the compound stop's second key.

### 2.3 Momentum

Weekly Wilder RSI-14 = **38.84**. Source: Yahoo BTC-USD 5y 1wk, weekly boundary = week-start timestamps UTC, period 14, **261 completed weekly closes**, last completed week = the bar labelled **2026-07-27** (week ending Sunday 2026-08-02). Confidence: ok (≥30 closes).

**Live-week artifact — checked, not assumed.** `tools/fetch.mjs` also reports `rsi14_including_live_week = 40.24`. On 2026-08-05 this series applied a manual correction because the tool's completed set had absorbed the in-progress week; today the tool's completed set correctly ends at 2026-07-27 and returns 38.84, **reproducing the 08-05 corrected print exactly**. The artifact is **not harmless today**: 38.84 lands in the ≤40 → band 2, while 40.24 would land in ≤45 → band 1. Using the completed-week value is what the momentum input rule requires, and it is the value used.

Daily RSI-14 = 51.61.

### 2.4 Valuation

| Metric | Value | As of | Source |
|---|---|---|---|
| MVRV-Z | **0.3918** | 2026-08-05 | bitcoin-data.com `/v1/mvrv-zscore/last` |
| MVRV ratio | 1.2359 | 2026-08-05 | bitcoin-data.com |
| Realized price | $52,328.04 | 2026-08-05 | bitcoin-data.com |
| Drawdown from ATH | **−48.99%** | live | ATH $126,080 (CoinGecko, 2025-10-06) |
| ATH cross-check | $126,198.07 @ 2025-10 | Yahoo max-range monthly highs — independent confirmation of the ATH month and level (+0.09% vs CoinGecko) |

Cross-provider check: Santiment `mvrv_usd_z_score` for bitcoin printed **0.3961 on 2026-07-07** against the bitcoin-data.com series on the same scale — two independent providers agreeing to within ~0.01 at the overlap. Recorded because the ETH companion report is forced onto the Santiment series and needs its scale corroborated on the asset where both are available.

### 2.5 Long-horizon structure

| Metric | Value |
|---|---|
| 200-week SMA | **$63,549.42** — spot **+1.20%** above → **gate 6 LIT** (band $58,465.47–$68,633.37) |
| 200-day MA | $70,508.25, falling **−3.78%** / 20 sessions — spot **−8.79%** below |
| 50-day MA | $63,239.33 — spot **+1.69%** above |
| Campaign low | **$57,747.77** (2026-07-01), **36 sessions** old |
| Bounce off low | **+11.36%** |
| Recent daily lows | 62,233.01 → 62,226.58 → 63,277.68 → 63,829.72 → 64,135.50 — **ascending** |

### 2.6 Spot ETF flows — the material change

| Window | Net flow | Source |
|---|---|---|
| 2026-08-05 | **+$244.4M** — strongest daily inflow since late July | COINOTAG / financefeeds / gncrypto, 2026-08-05 |
| Trailing 3 sessions | **+$626M** | gncrypto 2026-08-05 |
| Consecutive green sessions | **6 straight with zero net outflow days in August**, through 2026-08-04; +$244.4M on 08-05 extends it to **7** | yellow.com 2026-08-04 (multi-source corroborated) |
| 2026-08-03 detail | IBIT +$111M, FBTC +$33M, Franklin +$9M; net ≈ +$102.3M (1,600 BTC) | Lookonchain via yellow.com |
| Custodial holdings | ≈1.3–1.5M BTC | The Block / Benzinga |

**Streak-completion rule satisfied, not waived:** the session that completed the ≥5-session bar is corroborated by four independent outlets (yellow.com, cryptoslate, financefeeds, COINOTAG), so the streak is **CONFIRMED, not PROVISIONAL**, and may carry regime weight.

**Provenance under the metric-history continuity rule:** the 2026-08-05 report in this series printed "two consecutive positive ETF sessions," and 08-03 printed inflows resuming. The 6-session August streak is consistent with that lineage and extends it — no backdating, no contradiction to disclose.

### 2.7 On-chain

| Metric | Value | Source |
|---|---|---|
| Perp funding, mean 45 intervals | **+0.01% / 8h = +5.97% annualized** | Binance fapi fundingRate (BTCUSDT) |
| Negative funding intervals (of 45) | **0** — longest negative run 0 | same |
| Funding percentile vs 167d history | 80.24 | `tools/fetch.mjs` context |
| LTH supply | **Record high, ~16.4M BTC**; rising 30d | Glassnode / news.bitcoin.com 2026-08 |
| Exchange reserves | **~2.67M BTC, multi-year lows**, declining | CryptoQuant / The Block |
| Hash Ribbon | **30d MA 902.03 EH/s BELOW 60d MA 916.59 EH/s** — miner capitulation ongoing, gap **−1.59%** (narrowed from −1.97% on 08-05) | **COMPUTED** from blockchain.info charts/hash-rate, 362 daily points, last 2026-08-05 |
| Hash Ribbon cross date | 2026-06-08 (bearish), **59 days** old | computed |
| 24h liquidations | Quiet — **short liquidations ~$30M**; total crypto mcap ~$2.29T, 24h volume ~$57B | news.bitcoin.com market update 2026-08-06; coingabbar 2026-08-06 |

**Carried warning, still live:** a web search for Hash Ribbon status surfaces a Bitbo/Cointelegraph piece announcing that Hash Ribbons had signalled the *end* of miner capitulation — **that article is dated August 20, 2024**. Scored off the headline, gate 5 would light on two-year-old data. The computed series says the opposite and still does.

**Stale-input disclosure — liquidations.** A full market-wide 24h liquidation aggregate could not be retrieved this cycle: the CoinGlass v4 API requires a key (`401 API key missing`) and its web dashboards render client-side and returned zeros to the fetcher. The sourced short-liquidation figure (~$30M) plus the market-context prints establish the tape is quiet by an order of magnitude relative to any top-decile flush; the capitulation leg's item (a) and gate 7 are scored **dark on that basis**, which is the conservative direction (a missing liquidation figure cannot *credit* a capitulation). Debt clock: report 1.

### 2.8 Macro & equities

| Asset | Level | Δ | Source |
|---|---|---|---|
| S&P 500 | 7,709.96 | **+3.66% / 5 sessions**, at a record | Yahoo ^GSPC, 2026-08-06 |
| VIX | 15.15 | **−11.35% / 5 sessions** | Yahoo ^VIX, 2026-08-06 |
| DXY | 99.96 | −0.05% / 5 sessions | Yahoo DX-Y.NYB |
| Brent | $83.33 | 0.00% / 5 sessions | Yahoo BZ=F |
| 10y real yield (TIPS) | **2.41%** | flat over 5 prints | FRED DFII10, 2026-08-05 |
| HY OAS | 2.75% | **−0.12pp / 5 prints** — credit tightening | FRED BAMLH0A0HYM2, 2026-08-05 |
| NFCI | −0.529 | looser than average | FRED, 2026-07-31 |
| Net liquidity | $5.84T | weekly (Thu) | FRED WALCL+RRP+TGA, 2026-08-05 |
| Stablecoin supply | $183.39B | −0.44% 30d, **−3.31% 90d** | DefiLlama, 2026-08-05 |
| 3m T-bill (dry powder benchmark) | **3.73%** | ^IRX 2026-08-06; FRED DGS3MO cross-check 3.89% (08-05), delta −0.16pp | Yahoo / FRED |

**Fed path:** CME FedWatch shows a **September hike probability in a 62–76% range** — 61.9% as of 2026-08-04 on one read, 76.1% on another, and the two sources also disagree on the meeting date (Sept 10 vs Sept 16). The **disagreement is disclosed rather than resolved**; what is robust is the direction — up from the ~59–63% this series printed on 2026-08-05.

### 2.9 Correlation regime

**30d Pearson correlation of daily log returns, BTC-USD vs ^GSPC = 0.301.** Window 2026-06-25 → 2026-08-06, 30 aligned sessions / 29 return observations, computed from Yahoo closes via `tools/lib.mjs correlationFromCloses`. Regime label: **mild**. Prior report: 0.313.

- Risk-on surcharge (>0.7): **OFF**. No extra gate, no extra [V] floor.
- Phase-2 corr condition (<0.80): **PASS on a computed number.**
- D2 conviction path is therefore **not barred on correlation grounds**.

### 2.10 Context Panel — disclosed context only, never a scored leg or gate

| Metric | Value | Percentile vs own history |
|---|---|---|
| Realized vol 30d | 29.20% | **12.91** vs 2y |
| Realized vol 10d / 90d | 23.53% / 34.73% | — |
| Drawdown vs 2y high | 48.45% | **93.16** |
| Distance to 200dma | −8.79% | 43.52 |
| Weekly RSI-14 | 38.84 | **22.87** |
| Daily RSI-14 | 51.61 | 54.04 |
| F&G | 25 | 27.02 |
| Funding annualized | +5.97% | 80.24 |
| Session volume | $18.72B | **8.63** |
| Deribit DVOL / ATM IV (2026-08-28, 21.4d) | 34.86 / 31.66% | — |
| 90/110 moneyness skew | **+8.40%** (puts richer — downside hedging bid intact) | — |
| Variance risk premium | +2.46pp | — |
| Perp basis | −0.05%, carry +5.97% annualized (longs pay shorts) | — |
| Binance long/short account ratio | 1.1468, falling | **3.45** — near the bottom of its 30d range |
| Open interest | 107,105 BTC, falling | 75.86 |

The read the panel adds: a **93rd-percentile drawdown at 13th-percentile volatility on 9th-percentile volume**, with retail account positioning at the 3rd percentile of its own 30-day range. That is a market that has stopped moving, not one that has stopped falling — and it cuts both ways. It is disclosed, not scored.

---

## 3. Critical Developments

- **ETF flows flipped decisively.** Six consecutive sessions with zero net outflows through 08-04, extended by **+$244.4M on 08-05** — the strongest daily print since late July, $626M over three days. IBIT took $254.5M of it; HODL was the notable outlier at −$14.7M. *(COINOTAG, financefeeds, gncrypto, yellow.com, 2026-08-04/05.)*
- **Coldcard exploit — plateauing but not closed.** Total moved sits at **~1,816 BTC / ~$116–130M from 5,200+ addresses** since 2026-07-30, across five waves. Root cause: a **March 2021 Coinkite firmware build** that routed seed generation to a software PRNG instead of the STM32 hardware RNG, collapsing effective key strength from 128 bits to as little as 40 on older devices. Coinkite has halted shipments and destroyed remaining affected inventory. **No new wave has been reported since ~2026-08-03/04.** *(TechCrunch 2026-08-04; TRM Labs; CoinDesk 2026-08-02; Forbes 2026-08-04; Fortune 2026-08-03; Benzinga 2026-08.)*
- **CLARITY Act is failing on the calendar.** The Senate **made no attempt to bring it to a floor vote on Thursday 2026-08-06**; a procedural vote this weekend is now regarded as not possible. The last scheduled workday is **2026-08-07**, with the state work period beginning **2026-08-10**. Polymarket odds of the bill being **signed into law in 2026 have collapsed to 18%**, from the 28–37% this series printed on 2026-08-05 and an 82% February peak. *(CoinDesk 2026-08-05; coingape LIVE updates 2026-08-06; cryptobriefing; Bitcoin Foundation.)*
- **Fed path tightening into a tier-1 print.** September hike odds 62–76% and rising, ahead of **July payrolls Friday 2026-08-07 08:30 ET**. June NFP printed **57K against a 110K consensus**, with May revised down to 129K — the labour market is the live variable, and it is the one the market will re-price on tomorrow. *(CME FedWatch via centralbank.watch / macromicro, 2026-08-04/06; BLS.)*
- **Structural bid intact.** LTH supply at a record ~16.4M BTC; exchange reserves ~2.67M BTC at multi-year lows, with a documented share of the outflow going to hardware wallets and private addresses. *(Glassnode / CryptoQuant / The Block / Benzinga.)*

---

## 4. Fallen Knives Composite Score — BTC

| Category | Score | Max | Basis |
|---|---|---|---|
| **Sentiment Extreme** | **2** | 5 | 3-day avg F&G **25.67** → >25, ≤35 → 2. *(0.67 points from band 3.)* Verified `compute.mjs band fk-sentiment 25.67 → 2` |
| **Momentum Exhaustion** | **2** | 4 | Weekly Wilder RSI-14 **38.84** (261 completed closes) → ≤40 → 2. Verified `band fk-momentum 38.84 → 2` |
| **Valuation** | **4** | 5 | MVRV-Z **0.3918** → ≤0.5 → 4. Verified `band fk-mvrv 0.3918 → 4` |
| **Capitulation Evidence** | **0** | 3 | 0 of 3. (a) liquidations nowhere near top-decile ✗ · (b) 0 negative funding intervals of 45 ✗ · (c) ETF **inflows**, not ≥2% AUM outflows ✗ |
| **Holder Behavior** | **3** | 3 | Both: LTH supply at a record and rising 30d ✓ · exchange reserves at multi-year lows and declining ✓ |
| **Leg sum** | **11.0** | 20 | |
| **Mechanical score** | **11** | | `round(11.0)`, half-up |
| **D1 discretionary** | **−0.5** | | see §9.3 |
| **Raw composite** | **10.5** | | |
| **[V]-gate surcharge** | none | | corr 0.301 < 0.7 |
| **Adjusted score** | **11** | | `round(10.5)` half-up → 11. Verified `compute.mjs round 10.5 --asset btc → 11` |

**The D1 term changes no integer this report.** Under BTC's half-up convention, −0.5 and 0.0 both produce adjusted 11. It is stated as a directional judgment and logged for grading; it buys and blocks nothing. Said plainly rather than credited with restraint it did not supply.

### 4.1 Confirmation Gates — 3 of 9 ✅ ([V] 2)

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | F&G ≤15 for ≥7 consecutive days | ❌ | Spot 25; needs a ~10-point drop **sustained a week**. Concretely: a risk-off flush breaking the $57,747.77 campaign low. **Reachable in regime** |
| 2 | [V] | Weekly RSI <30 | ❌ | 38.84; requires several consecutive down weeks into roughly the low-$50Ks. Reachable, but a large move |
| 3 | [V] | Valuation cheap (MVRV-Z <1) | ✅ | lit at 0.3918 |
| 4 | [V] | ETF outflows ≥2% AUM trailing month | ❌ | **Moved further away this report.** Needs a full reversal of a 7-session inflow streak into ~$1B+ of monthly outflows. Reachable — flows have flipped before in this cycle |
| 5 | [T] | Hash Ribbon buy signal | ❌ | **Nearest gate to lighting.** 30d/60d gap −1.59%, narrowed from −1.97%. A 30d MA cross back above the 60d MA lights it; capitulation is 59 days old |
| 6 | [T] | Price within ±8% of 200-week MA | ✅ | lit at +1.20% (band $58,465–$68,633) |
| 7 | [V] | Capitulation liquidation spike (top-decile 90d or >3σ of 30d) | ❌ | Needs a real flush; the tape is at 9th-percentile volume. Reachable on any single violent session |
| 8 | [V] | LTH accumulation / holder concentration | ✅ | lit — record LTH supply, reserves at multi-year lows |
| 9 | [T] | Macro catalyst neutral-to-positive | ❌ | Needs a soft NFP (08-07) or CPI (08-12) cutting Sept hike odds below ~50%, **or** a revived CLARITY path in September. Reachable on a single data print |

No gate is tagged **none-in-regime** — gate 6 is lit and every dark gate has a concrete path. Active denominator **9** (BTC is PoW; gate 5 applies). Thresholds verified: `compute.mjs thresholds 9` → 1A 3 / 1B 5 / 2 6 / 3 7, [V] floors 2 / 3 / 3 / 4.

This disclosure is **informational only**. It may not be cited to lower a threshold, credit a gate, or reduce a denominator. The default conclusion stands: dark gates are correctly dark.

### 4.2 Score-line vacuity & binding-constraint audit

**(a) Attainable ceiling = 20/20.** Re-derived this report: sentiment 5 (crypto F&G is available daily from the pinned provider — no NOT-FOUND pin), momentum 4, valuation 5 (MVRV-Z sourced, no fallback), capitulation 3, holder 3. **BTC carries no structural leg pin.** Every score line below is therefore reachable in principle; none is VACUOUS-FALSE.

**(b) Score-line states** — each evaluated against the score that line actually reads, over distinct report dates in this series (07-20, 07-22, 07-23, 07-25, 08-01, 08-03, 08-05, 08-06):

| Line | Reads | Now | State |
|---|---|---|---|
| Phase 1A ≥8 | adjusted | 11 → TRUE | **VACUOUS-PERMISSIVE** — TRUE on all 8 trailing dates |
| Phase 1B ≥11 | adjusted | 11 → TRUE | **LIVE** — FALSE on 08-03/08-05, TRUE on 08-01 and today; the predicate is actually moving |
| Phase 2 ≥15 | adjusted | 11 → FALSE | **VACUOUS-BLOCKING** — FALSE on all 8 |
| Phase 3 ≥17 | **mechanical** | 11 → FALSE | **VACUOUS-BLOCKING** — FALSE on all 8 |
| Deep-Value Override arming ≥15 | **mechanical** | 11 → FALSE | **VACUOUS-BLOCKING** — FALSE on all 8. **EXEMPT from the silencing consequence: evaluated mechanically below regardless** |
| Compound thesis stop, score <12 | **mechanical** | 11 → TRUE | **VACUOUS-PERMISSIVE** — TRUE on 7 consecutive dates (07-22 onward) |

**Compound-stop disclosure, in its correct direction.** The score key has stood **satisfied**, so the stop is **effectively single-key and price-gated at $55,000**. That makes it fire **more** readily, not less. The live exposure is the Jun-6-2026 pattern — ejection on a price break with fear and value still intact — not an under-protected book. It is not a defect claim and it moves nothing: **D6 governs, the line may rise, never fall.**

**(c) Binding axis, per unfilled phase:**

| Phase | Score short by | Gates short by | **Binding** |
|---|---|---|---|
| 1A (10%) | 0 (11 vs 8) | 0 (3/9 vs 3; [V] 2 vs 2) | **Neither — UNLOCKED.** The binding constraint on a *fill* is the EXPIRED ledger, and the entry zone (spot is inside it) |
| 1B (15%) | 0 (11 vs 11) | **2 gates, and 1 [V] gate** | **GATES** |
| 2 (30%) | 4 | 3 gates | both; gates by more |
| 3 (45%) | 6 (mechanical) | 4 gates | both |

Phase 1B's score line **crossed back to TRUE this report** (mechanical 11 was already there; the smaller D1 no longer removes it under half-up rounding). That is a movement on the **non-binding** axis — 1B remains two gates and one [V] gate short — and per the audit's third consequence **no change in posture or conviction is claimed from it.**

This audit is one-directional and informational. It may not lower a line, credit a leg, reduce a requirement, or move a stop.

### 4.3 Companion Flying Rocket score (Hard Rule 5) — COMPUTED

`compute.mjs fr-companion`, same live data, Channel **B** (49.04% below the 1y ATH; 200dma falling −3.78%; price below it):

| FR-B leg | Score |
|---|---|
| Rally extension (bounce +11.36%) | 1 |
| Local momentum (daily RSI 51.61 / weekly 38.84) | 1 |
| Resistance confluence (1 of 4) | 1 |
| Bear structure (2 of 3) | 2 |
| Relative sentiment (1 of 3) | 1 |
| Penalty (squeeze tier none; bounce 36 sessions, no maturity penalty) | 0 |
| **FR composite** | **6 / 20** |

Count derivations: **resistance 1/4** — within 3% of 200dma FALSE (−8.79%); within 3% of 50dma *from below* FALSE (price is +1.69% **above** it); at/below a prior swing high that is itself a lower high TRUE (bounce high $65,658.34 on 2026-07-27); prior breakdown level FALSE. **Structure 2/3** — bounce high is a lower high TRUE; 50dma below 200dma AND gap not narrowed FALSE (gap narrowed); no weekly close above the 200dma in 8 weeks TRUE. **Sentiment 1/3** — F&G 3d ≥1.5× its 30d mean FALSE (25.67 against a 25–30 band); funding flipped positive after ≥5 negative sessions FALSE (zero negative intervals); flow tell TRUE (7-session ETF inflow streak into the rally).

**Cross-validation: consistent ✅.** FK **11** (mechanical) vs FR **6**, inversely related, both <12 → Hard Rule 5's both-≥12 condition is **not** met. The label is **unqualified and carries full evidentiary weight** because the Channel-A phase-of-cycle cap is **not binding** (Channel B is the live channel), so both-≥12 is genuinely falsifiable rather than vacuous by construction.

**Standalone FR report: NOT owed on BTC.** Companion 6 < 9; no FK phase-unlock threshold crossed by the score; short-side liquidation volume ~$30M, far under the $100M trigger; the cap is not binding. The ETH obligation outstanding since 2026-08-01 is **DISCHARGED** — `reports/eth_flying_rocket_20260806_1844.md` was published earlier today.

**Independent corroboration.** A standalone BTC Flying Rocket report also ran today (`reports/btc_flying_rocket_20260806_1844.md`), scoring Channel B at **mechanical 7 → adjusted 7** and standing down by six against the Channel B Phase 1A line of 13. It reaches mechanical 7 where this companion computes 6, on one leg — its rally-extension leg reads 2 against my 1, measured to today's session high rather than to the campaign-low bounce. The divergence is **one sub-criterion, in the more-cautious-on-shorts direction, and changes no verdict on either side**; it is recorded rather than reconciled away. That report independently reproduces this one's FK legs (2/2/4/0/3) and 3/9 gate board exactly.

---

## 5. Probability Matrix — Score-Anchored, Analyst-Set (D4)

Anchor row for adjusted 11 (band 11–14): Rally 30 / Range 35 / Retest 22 / Bear 13.

| Scenario | Probability | Target range | Midpoint | Key trigger |
|---|---|---|---|---|
| **Rally** | **31%** | $66,500 – $71,000 | $68,750 | Soft NFP cuts Sept hike odds; ETF streak extends past 10 sessions; weekly close above $66,500 |
| **Range** | **38%** | $62,000 – $66,500 | $64,250 | The 200-week mean keeps acting as a magnet; vol stays at the 13th percentile |
| **Retest** | **20%** | $57,500 – $62,000 | $59,750 | Hot NFP/CPI; a daily close below $62,226 opens the campaign low |
| **Bear** | **11%** | $50,000 – $57,500 | $53,750 | Campaign low breaks with a funding flush; hike odds above 85% |
| **Sum** | **100%** | | | |

Deviations from the anchor row: Rally +1, Range +3, Retest −2, Bear −2 — all inside the ±10 percentage-point band, none requiring a >10pp reason line. Direction: the 7-session ETF inflow streak, an eased risk backdrop (VIX 15.15, HY OAS 2.75%, SPX at a record), and a 36-session-old campaign low with ascending lows argue for weight in Rally/Range; a rising Sept hike path, a failing CLARITY calendar, and a weekly RSI at the 23rd percentile argue against pushing further.

**Weighted EV = $63,590.00.** Component sum, recomputed from the printed cells as the final step: 0.31 × 68,750 = 21,312.50 · 0.38 × 64,250 = 24,415.00 · 0.20 × 59,750 = 11,950.00 · 0.11 × 53,750 = 5,912.50 → **63,590.00**. Verified `compute.mjs ev` — recomputed 63,590.00 vs stated 63,590.00, rel diff **0.00%**, prob sum 100 ✓, Rally cap ✓.

**EV-vs-spot = −1.12%.**

**Realized trailing-2-week price change: −1.13%** ($65,044.81 close on 2026-07-23 → $64,310.31). The negative EV is printed during a *negative* two-week move — the two agree in sign, so there is no momentum contradiction to flag in either direction this report. Disclosed symmetrically as required.

### 5.1 EV sign attribution (mandatory — EV-vs-spot is negative)

| Cell | p × (mid − spot)/spot | Contribution |
|---|---|---|
| Rally | 0.31 × +6.904% | **+2.140pp** |
| Range | 0.38 × −0.094% | −0.036pp |
| Retest | 0.20 × −7.091% | −1.418pp |
| Bear | 0.11 × −16.421% | −1.806pp |
| **Sum** | | **−1.120pp** ✓ ties to the stated EV-vs-spot |

The modal cell is **Range**, and its midpoint sits **−0.09% from spot** — essentially at spot. The sign is carried by **band distance**, not probability weight: Bear's midpoint sits −16.42% below spot against Rally's +6.90% above, so a 11% Bear weight outweighs a 31% Rally weight. **This is a geometry-driven read — a risk-adjusted number, not a directional forecast.** It may inform sizing **downward only**; its sign alone does not carry this report's stance, which rests on score, gates, zone and the named risks.

**Non-dissolution:** this label is diagnostic. It does not satisfy, weaken or dissolve the EV-floor consistency check, does not lift this report out of the Verdict-Confidence Collar, and does not substitute for the terminal-vs-extreme reconciliation.

### 5.2 EV-floor consistency check

Mechanical score 11 (<15) and 3-day F&G 25.67 (>15) → **the flag condition is not met**. No inconsistency to resolve.

### 5.3 Trend residual — stated as a boolean regardless of how cells were set

**Active downtrend (below a major MA AND making lower lows): NO.**

The MA half is half-satisfied: price is −8.79% below a falling 200dma, but **+1.20% ABOVE the 200-week mean**. The lower-lows half fails outright — the campaign low $57,747.77 has held **36 sessions** and recent lows ascend (62,233.01 → 62,226.58 → 63,277.68 → 63,829.72 → 64,135.50).

Consequences, stated so no guardrail is silently orphaned: **no bearish residual applied**, and the **Deep-Value Override's quarter-size throttle is OFF** — an Override firing would be half-size. (It cannot fire regardless; mechanical 11 < 15.)

**Terminal-vs-extreme reconciliation:** not triggered — the rule binds only when the trend residual is live, and it is not. Recorded so the omission is deliberate rather than silent.

### 5.4 EV Calibration Line — BTC

**Prior report (2026-08-05): EV-vs-spot −2.08%. Realized since: $64,404.43 → $64,310.31 = −0.15%. Sign: CORRECT.**

**Current same-sign streak: 14 consecutive report dates with a negative EV-vs-spot** (2026-07-11 → today). Over the 13 dates that have a realized successor, the sign was right **7** and wrong **6** — a **54% hit rate**. Cumulative spot across the streak: **+0.16%**.

**The tripwire does NOT fire.** It requires a ≥5-date same-sign streak **and** contradiction in the *majority* of them; 6 of 13 is not a majority. **EV retains its stance-carrying status on BTC.**

What the counter does show, and what is disclosed rather than dismissed: the **magnitude** is systematically overstated. Mean |EV-vs-spot| across the streak ≈ 1.7% against mean realized report-to-report moves of ≈ 1.1%, and 13 consecutive negative calls have accompanied a cumulative **+0.16%**. The sign is a coin flip; the size is a bias. That is exactly the geometry effect §5.1 decomposes.

**Provenance:** counted **by hand** from the machine blocks of `reports/btc_fallen_knives_*.md`, not from `exports/signal-feed.json` — the feed's history is not uniformly populated (2026-07-29 standing caveat). Distinct report dates; the two same-day reports on 2026-07-14 and 2026-07-18 each count once. Reports before 2026-07-11 carry no machine-block `vs_spot_pct` and are UNKNOWN — they neither extend nor break the streak.

---

## 6. Deployment Strategy — BTC

**Total dry powder: 100% (cold start, Hard Rule 4).** Splits 10 / 15 / 30 / 45.

### 6.1 Position & Performance (Hard Rule 8)

`node tools/position.mjs btc` → **exit 1, band EXPIRED, age 126.7 hours** (7,604 minutes; driver `holdings_as_of`; `generated_at` 126.7h; expiry threshold 72h). File: `/Users/eternal/.trading-claude/exchange/position-snapshot.json`.

**This report therefore proceeds as a COLD START under Hard Rule 4, stated explicitly.** No quantity, cost basis, unrealized or realized PnL, ROI, or dry-powder dollar figure is asserted as current. No tranche is sized against the last readable snapshot.

**Position Reconciliation.** Prior reports in this series narrated "10% Phase 1A at ~$65,000 blended," carried forward for weeks. The last *readable* snapshot (2026-08-01, STALE when read on 08-03) showed **dust**, `basis.reliable = false` on 5 unbacked disposals (0.03360450 BTC), custody `RECONCILED` with zero withdrawals, and **zero deal tags**. It could neither confirm nor refute the narration. The ledger cannot be consulted at all now. **The narrated tranche is UNVERIFIED in both directions — not confirmed, not refuted, and explicitly not read as flat.** Under Hard Rule 4 the operative default for sizing is 100% dry.

Degradation across this series: **STALE 50.2h (08-03) → EXPIRED 94.2h (08-05) → EXPIRED 126.7h (today)**. Fourth consecutive report blocked on the same defect. The underlying cause — `basis.reliable = false` from unbacked disposals — is a personal-accounting-ledger fill-ingestion defect, not a framework one.

**Dry powder yield benchmark: 3.73%** (3-month T-bill, ^IRX 2026-08-06; FRED DGS3MO 3.89% on 08-05). Cash is a position, and at 100% dry this book is earning it. Note the pool is **shared across the BTC, ETH and gold series** — two reports each sizing "10% of the book" against one balance would double-commit the same dollars, which is a second reason a measured book matters before a fill.

### 6.2 Phases

#### Phase 1A — Initial Entry (10%) — **UNLOCKED**
- **Unlock test:** adjusted **11 ≥ 8** ✅ · gates **3/9 ≥ 3** ✅ · [V] **2 ≥ 2** ✅. Mechanical unlock — **no analyst channel used, so no D5 stop attaches.**
- **Entry zone: $63,000 – $66,500.** Spot $64,310.31 is **INSIDE** the zone, $1,310 above its floor. **Entry-zone ratchet satisfied** — no fill would be above the zone top.
- **Status: AUTHORIZED, NOT FILLED.** The block is the EXPIRED ledger, not the framework. Sizing a 10% tranche requires knowing the book; `dry_powder.stable_balance_usd` is unreadable and the pool is shared with two other series.
- **Conditional authorization (carried and re-affirmed):** *IF* a FRESH snapshot (≤12h) shows Phase 1A unfilled and a stable balance, *THEN* ladder 10% across $63,000–$66,500 in three clips — $66,000 / $64,500 / $63,200 — never at the top of the zone. Ledger tag **`FK-P1A`**, note first line `report=reports/btc_fallen_knives_20260806_1836.md`.
- **Stop:** the catastrophic floor at $50,000 and the compound thesis stop below. No D5 stop — this is a mechanical unlock.

#### Phase 1B — Building (15%) — **BLOCKED on gates**
- Score: adjusted **11 ≥ 11** ✅ — the score line is met.
- Gates: **3/9 against 5 required** (short 2), **[V] 2 against 3** (short 1). **Binding.**
- **D2 conviction path UNAVAILABLE** on two independent grounds: the path opens only at a shortfall of **exactly one** gate and 1B is short two; and D2 substitutes for a *gate*, never for a **[V] floor**.
- **Entry zone: $58,000 – $61,500.** Status: **DRY POWDER.**

#### Phase 2 — Conviction (30%) — **FROZEN**
- Adjusted 11 < 15; gates 3/9 < 6. Corr condition would PASS (0.301 < 0.80) but is not reached.
- **Entry zone: $54,000 – $58,000.** Status: **DRY POWDER.**

#### Phase 3 — Generational (45%) — **DRY**
- Mechanical 11 < 17; gates 3/9 < 7. No analyst channel reaches this tranche.
- Status: **DRY POWDER.**

### 6.3 ⚑ Deep-Value Override — evaluated, does NOT fire

Evaluated mechanically this report regardless of its VACUOUS-BLOCKING score-line tag, per the audit's explicit exemption. An Override nobody tracks is how it shipped decorative in Jun 2026.

| Condition | Required | Actual | Pass |
|---|---|---|---|
| Mechanical score | ≥15 | **11** | ❌ **dispositive** |
| Trailing low / close ≥8% below last tranche's blended cost **AND** fresh lower-low | both | no corroborated deployed tranche exists (EXPIRED ledger) | ❌ |
| 3-day avg sentiment | ≤15 | **25.67** | ❌ |
| Worsening-flows veto | must be OFF | OFF (flows are strongly positive) | ✅ |
| No §7 narrative-break active | — | none active | ✅ |

**Three independent failures.** No near-fire to log; no veto or throttle was reached. The Override presupposes at least one deployed tranche and can never unlock Phase 1A.

### 6.4 Stops — no parameter changed value

| Tier | Level | State |
|---|---|---|
| **CATASTROPHIC** | **$50,000** | unchanged |
| **Compound thesis stop** | **$55,000** price **AND** mechanical score **<12** | unchanged |
| Deepest named buy-zone floor | **$54,000** (Phase 2) | unchanged |
| D5 discretionary stops | **none** — zero analyst-channel tranches have ever been opened in this series | — |

**Coherence check (catastrophic tier): $50,000 strictly below deepest active buy-zone floor $54,000 → PASS.** Verified `compute.mjs stop-coherence --catastrophic 50000 --floor 54000 → pass:true`. The compound line at $55,000 sits inside the Phase 2 zone by design and is *not* the tested number — it cannot fire on price alone.

**Compound-stop score axis:** mechanical 11 < 12, so the score condition **is satisfied** and the stop is effectively price-gated at $55,000 — see §4.2(b) for the direction of that exposure. The D1 −0.5 has **zero** effect here: the compound stop reads the **mechanical** leg sum per the 2026-07-27 governing rule, so a negative discretionary term cannot make the book's stop fire more readily than the evidence warrants, exactly as a positive one could not suppress it. **Checked, not assumed.**

Max drawdown spot-to-compound-line: **−14.47%**. Disclosed; under D6 it purchases no loosening.

**Stop Migration Ledger: empty this report.** No stop parameter moved in either direction. **D6 ratchet: compliant.**

**Checkpoint — 2026-08-09.** Calendar validation performed **before** any distance language: 2026-08-09 is a **Sunday**, a valid weekly-close boundary for a 24/7 venue; no restatement applied. Fires **iff** ≥2 consecutive weekly closes below $55,000 **AND** mechanical score <12. Closes below the line so far: **0** — and one weekly close cannot supply two, so the checkpoint **structurally cannot fire** on 08-09.

Spot is **16.93% above the line** = **7.84 × ADR(5)**. ADR(5) = **$1,187.12**, computed from sessions 2026-08-01 through 08-05; **the 2026-08-06 session is EXCLUDED as in-progress** (not a full session at 22:36Z) and the lookback was extended one session to reach five full ones — exclusion disclosed inline. Verified `compute.mjs adr --exclude 2026-08-06`.

**Tier-1 release before this checkpoint: YES — July payrolls, Friday 2026-08-07 08:30 ET (BLS).** Named in the falsifier: a hot print pushes September hike odds above the current 62–76% and pressures the tape toward Retest; a soft print cuts them and supports Rally. **NFP cannot produce two sub-$55,000 weekly closes by 2026-08-09** — the checkpoint's outcome is determined by arithmetic, not by the release. Next tier-1 after: **CPI, Wednesday 2026-08-12 08:30 ET.**

---

## 7. Exit / Trim Framework — status: NO POSITION OF RECORD

Every score condition below reads the **mechanical** score (leg sum, no D1 term).

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | −6 | Peak since campaign start 12 (2026-07-20); now 11 → **−1** | ❌ not triggered |
| F&G ≥75 sustained 7d AND weekly RSI >70 | both | F&G 25, RSI 38.84 | ❌ |
| MVRV-Z >3 or drawdown <10% with vertical 30d return | either | 0.3918; −48.99% drawdown | ❌ |
| Mechanical score ≤3 AND price ≥40% above blended cost | both | score 11; no verified basis | ❌ |
| ETF outflows ≥3% AUM after a sustained inflow regime | both | flows are strongly **positive**; the ≥5-session inflow bar *is* now met, arming the precondition for a future reading of this row, but the outflow leg is absent | ❌ |
| **Narrative break** | any | **see below** | ❌ |

**Narrative-break re-test — Coldcard exploit, re-evaluated on updated evidence.** ~1,816 BTC / ~$116–130M drained from 5,200+ addresses across five waves since 2026-07-30, root-caused to a March 2021 Coinkite firmware build.

**Determination: NOT a narrative break. No exit, no trim.** Bitcoin's consensus rules, cryptography, issuance and settlement are untouched. Every affected key was weak **at generation**, which is precisely why the sweeps execute offline with no network-level exploit. This is a single vendor's product defect — the same class of event as an exchange failure: severe for the users hit, not a change in what the asset *is*. The §7 trigger is written for "irreparable" and "thesis voided," and this is neither: Coinkite shipped emergency firmware, halted shipments, destroyed affected units, and the remedy (regenerate seed, move coins) is available to every holder. Scaling the determination with the loss total would be a category error — a larger vendor failure is still a vendor failure. What it *does* impair is the **interpretation** of the holder leg's cold-storage premise, which is why it is priced as a D1 term rather than an exit.

**Exit status: no position of record. Remaining position size: unknown — see §6.1.** Nothing is trimmed against a position that cannot be read, in either direction.

---

## 8. Critical Watchlist

**Mandatory tier-1 US calendar enumeration, next 5 trading days (2026-08-07, 08-10, 08-11, 08-12, 08-13):**

| Date / Time (ET) | Event | Verified against | BTC impact |
|---|---|---|---|
| **Fri 2026-08-07, 08:30** | **Employment Situation — July payrolls** | BLS release schedule (`empsit`), confirmed | **Tier-1.** June printed 57K vs 110K consensus, May revised to 129K. Hot → Sept hike odds above 76%, pressure toward Retest. Soft → odds cut, supports Rally |
| Mon 2026-08-10 | *(no tier-1)* Senate state work period begins — **CLARITY Act deadline passes** | CoinDesk / coingape 2026-08-06 | Dated non-macro binary; failure defers market-structure legislation to mid-September |
| Tue 2026-08-11 | *(no tier-1 identified)* | — | — |
| **Wed 2026-08-12, 08:30** | **CPI — July** | BLS CPI release schedule, confirmed | **Tier-1.** The second half of the September-hike repricing |
| Thu 2026-08-13 | *(no tier-1 confirmed; PPI conventionally near this date but not verified this cycle — flagged rather than assumed)* | — | — |

Outside the 5-day window: **PCE** end-August; the **September FOMC** — sources consulted disagreed on the date (Sept 10 vs Sept 16) and the disagreement is disclosed rather than resolved, since it falls well outside this report's horizon.

**No unenumerated tier-1 release sits inside this report's horizon.** This report is not an incomplete-data report on the calendar dimension.

**Additional watch items:**
- Coldcard: a sixth sweep wave, or attacker deposits to exchanges (would re-strengthen the D1 term; seven clean days retires half of it).
- ETF flow streak: extension past 10 sessions, or the first red session.
- Hash Ribbon: 30d/60d gap at −1.59% and narrowing — the closest dark gate to lighting.
- F&G: 0.67 points from the ≤25 band; a sub-24 print takes the composite to 12.

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

Bitcoin has gone quiet in a way that is genuinely hard to score. Realized 30-day volatility sits at the **13th percentile** of two years, session volume at the **9th**, and price has been welded to its 200-week mean — +1.20% today, and inside the ±8% band for well over a month. Meanwhile the drawdown from the October 2025 high is at the **93rd percentile**. That combination — historically deep discount, historically dead tape — is what a base looks like while it is being built, and it is also what a distribution shelf looks like before the next leg. The rubric cannot tell those apart, and neither can I with confidence. What I can say is that the tape has stopped *falling*: the campaign low of $57,747.77 is 36 sessions old and the recent low sequence ascends without exception.

The thing that actually changed since 08-05 is institutional flow. Six sessions with zero net outflows, then $244.4M on 08-05, $626M over three days — that clears this framework's own ≥5-session sustained-inflow bar, and it clears it on four independent sources rather than one. Two reports ago I was describing "two consecutive positive sessions" as a tell; it is now a regime. The framework's Principle 10 calls ETF flows the most important daily signal in the current regime, and the signal has turned. It has not turned the *gates* — gate 4 wants outflows and is now further from lighting than it was — but it has turned the underlying fact the gate was built to detect.

Against that: the Fed path is moving the wrong way (September hike odds 62–76% and rising), the CLARITY Act is dying on the Senate calendar with Polymarket at 18%, and payrolls print in fourteen hours. That is a genuine binary sitting directly on top of a market with no volatility cushion — a 13th-percentile-vol tape is exactly the kind that gaps.

So: unlocked, willing, and structurally blocked on my own bookkeeping. That is an uncomfortable place to be for a fourth consecutive report, and I want to be precise about whose fault it is. It is not the market's, and it is not the framework's — Phase 1A passes its score line by three points and its gate count exactly, spot sits $1,310 inside the zone floor, and the entry-zone ratchet is satisfied. It is that I cannot size a tranche against a book I cannot measure, in a stablecoin pool shared with two other live report series.

### 9.2 What the rubric structurally cannot see

1. **The flow *streak* as opposed to the flow *level*.** Gate 4 and capitulation-(c) both key on outflows — they are fear detectors. Neither leg nor gate can register a seven-session inflow streak as information. **Cuts bullish.**
2. **The Coldcard migration's meaning, not its measurement.** The holder leg scores "exchange reserves declining" as unambiguously bullish. That reading assumes the destination is safe self-custody. A five-year firmware defect that made an unknown cohort of hardware-wallet seeds reproducible offline attacks the *premise*, not the measurement. **Cuts bearish.**
3. **The volatility floor.** Nothing in the rubric reads realized vol. A 13th-percentile-vol market ahead of two tier-1 prints is a coiled spring with no directional information — but it does mean position sizing should assume a larger-than-normal gap. **Cuts neither; it argues for laddering, which §6 already mandates.**
4. **Retail positioning at the 3rd percentile.** The Binance long/short account ratio at 1.1468 is near the bottom of its 30-day range and falling. No leg reads positioning. **Cuts mildly bullish** (fuel), and is disclosed context, not scored.

### 9.3 The D1 term: **−0.5** (reduced from −1.0)

**Direction: negative. Size: 0.5. Third consecutive report with a negative sign; second distinct size.**

**Factor (i) — Coldcard supply and premise overhang, WEAKENING but live.** ~1,816 BTC of zero-cost-basis supply sits with at least a dozen distinct attacker groups, and an unquantified cohort of Coldcard users must now migrate seeds. This contaminates the *interpretation* of the holder leg's 3/3 — half that leg is "exchange reserves declining," whose bullish reading depends on the destination being safe, and a March 2021 build routed seed generation to a deterministic software PRNG instead of the STM32 hardware RNG. The leg's **measurement** is unaffected and still scores 3; the D1 addresses what the migration now *means*, which no leg or gate can express. **Sourced:** TechCrunch 2026-08-04, TRM Labs, Forbes 2026-08-04, CoinDesk 2026-08-02, Fortune 2026-08-03.

**Factor (ii) — the Fed path re-tightening into a tier-1 print, REPLACING the retired non-participation factor.** September hike odds have moved to a **62–76%** range from the ~59–63% this series printed on 08-05, with the 10-year real yield at 2.41% and July payrolls fourteen hours away. Gate 9 is a single boolean that resolves ❌ whether hike odds are 55% or 85% — it cannot express a *rising* probability of policy tightening into a levered asset. This is a **gate**, not a leg, so pricing it in D1 is not the prohibited double-count (adjudicated 2026-08-05: D1(a) prohibits re-weighting a factor a **leg** already scores). **Sourced:** CME FedWatch via centralbank.watch / macromicro 2026-08-04/06; FRED DFII10 2026-08-05; BLS schedule.

**The ORIGINAL factor (ii) — "BTC's non-participation in a record equity melt-up" — is explicitly RETIRED as falsified, and the retirement is flagged rather than buried.** On 08-05 that factor rested on SPX +6.41% against BTC +0.8%. Over the five sessions to today BTC is **+2.47%** (08-01 close $62,763.32 → $64,310.31) against SPX +3.66%, and the flow evidence that non-participation was supposed to detect has inverted outright. Honesty required saying the evidence stopped supporting it. **That retirement is the entire reason the term halves from −1.0 to −0.5.**

**Falsifier (dated).** Retire the remaining −0.5 when **either** (a) seven consecutive days pass with no new Coldcard sweep wave **and** no attacker deposits to exchanges observed on-chain — the last wave was ~2026-08-03/04, so this resolves around **2026-08-10/11**; **or** (b) BTC closes above **$68,875**, above the gate-6 +8% upper edge, which would demonstrate participation outright. **Hard review date: 2026-08-19.**

**Effect: none on any integer.** Under BTC's half-up convention, `round(11 − 0.5) = round(10.5) = 11`, the same adjusted score 0.0 would produce. The term neither buys nor blocks capital this report. Stated so it is graded on whether its directional claim was correct, not credited with a restraint it did not supply.

**Larger considered and DECLINED (−1.0):** the flow evidence has genuinely inverted, and holding a full point of negative adjustment against a confirmed 7-session inflow streak would be conviction outrunning evidence. The decay rule exists for exactly this — an adjustment held unchanged while its supporting facts move is stale conviction.

**Positive considered and DECLINED (+0.5):** it would be built on the ETF inflow streak and the eased risk backdrop. But the *withdrawal* of those same facts from the negative side is what already took the term from −1.0 to −0.5; counting the improvement once as a reduction and again as a bonus is arithmetic laundering. One fact, one effect.

### 9.4 Discretionary actions taken or declined

- **D2 conviction path: UNAVAILABLE, not declined.** Phase 1A needs no D2 — it is unlocked mechanically. Phase 1B is short **two** gates (3 of 5) where the path opens only at exactly one, and its [V] floor also fails (2 of 3), which D2 may never substitute for. Two independent bars.
- **D4 taken:** cells set from the read against the 11–14 anchor row. All four deviations inside ±10pp; EV recomputed from the printed cells as the final step.
- **Entry-zone re-anchor: DECLINED in both directions.** Downward would require running the coherence check against a lower floor for no benefit — spot is already inside the current zone. Upward is the constrained direction and there is no case for chasing a tape that is 1.2% above its own 200-week mean.
- **DECLINED — a Phase 1A fill, for the fourth consecutive report.** The tranche is genuinely unlocked, spot is inside the zone, and the ratchet is satisfied. The reason is a **data blocker with a one-command remedy**, not a market judgment: the ledger is EXPIRED at 126.7h with `basis.reliable = false`, so neither the existing position nor the dry-powder balance is knowable, and the stablecoin pool is shared with the ETH and gold series. **This should be graded as a bookkeeping failure, not as analytical restraint.**

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-03 | D1 | −1.0: Coldcard premise contamination + gate-9 binary cannot price a 60% hike | −1.0 score | n/a (no fill) | 7 clean days OR close >$68,875 | **superseded** — factor (ii) retired 08-05 | — |
| 2026-08-05 | D1 | −1.0: Coldcard escalated + BTC non-participation in equity melt-up | −1.0 score | n/a (no fill) | as above; hard review 08-19 | **superseded** — non-participation factor **falsified** 08-06 | — |
| **2026-08-06** | **D1** | **−0.5: Coldcard overhang (weakening) + Fed path re-tightening into NFP** | **−0.5 score** | n/a (no fill) | 7 clean Coldcard days (~08-10/11) OR close >$68,875; hard review **08-19** | **LIVE** | — |
| 2026-08-05 | D4 | Cells set at Rally 27 / Range 37 / Retest 23 / Bear 13; EV −2.08% | n/a | n/a | realized move | **CLOSED — sign correct** (−0.15%) | — |
| **2026-08-06** | **D4** | **Cells at Rally 31 / Range 38 / Retest 20 / Bear 11; EV −1.12%, geometry-driven** | n/a | n/a | realized move to the next report | **LIVE** | — |

No D2 entries — the channel has never been exercised on BTC. No D5 stops — no analyst-channel tranche has ever been opened in this series. **Non-mechanical capital deployed: 0% of book** (cap 40%; Override sub-cap 25% — neither approached).

### 9.6 What would change my mind

- **Bullish, dated:** a **weekly close above $68,875** (gate-6 upper edge) with the ETF inflow streak intact past 10 sessions. That retires the D1 outright and is the framework's own strong-claim unlock.
- **Bearish, dated:** a **daily close below $62,226.58** (the 2026-08-03 low). That breaks the ascending-low sequence this whole read rests on, opens the campaign low, and would flip the trend residual to active-downtrend — which in turn arms the Override's quarter-size throttle and materially changes what the next tranche looks like.
- **Nearest scheduled resolver:** **July payrolls, Friday 2026-08-07 08:30 ET.**

---

## 10. Bull vs Bear Scorecard

**Bull (✅) — 7**
1. MVRV-Z 0.3918 — market value only 24% above aggregate cost basis; realized price $52,328.
2. Drawdown 48.99% from ATH, at the 93rd percentile of two years.
3. LTH supply at a record ~16.4M BTC and rising.
4. Exchange reserves ~2.67M BTC, multi-year lows.
5. **ETF inflows for 7 consecutive sessions, +$626M over three days** — the ≥5-session sustained-inflow bar, met and multi-sourced.
6. Price welded +1.20% to the 200-week mean; gate 6 lit for over a month.
7. Campaign low 36 sessions old with an unbroken ascending-low sequence.

**Bear (❌) — 6**
1. September hike odds 62–76% and rising; 10y real yield 2.41%.
2. CLARITY Act effectively failing — Polymarket 18%, no floor vote attempted, deadline 08-10.
3. 200dma falling −3.78%/20 sessions with price 8.79% beneath it.
4. Weekly RSI 38.84 at the 23rd percentile — momentum is weak, not washed out.
5. Coldcard: ~1,816 BTC of zero-basis supply with motivated sellers; forced seed migration.
6. Volume at the 9th percentile and stablecoin supply −3.31% over 90 days — the bid is thin.

**Net: +1 bull, marginal.** The scorecard is **within 1 of balanced**, which independently engages the Verdict-Confidence Collar (§12).

---

## 11. Change Log vs 2026-08-05

| Factor | Previous (08-05) | Current (08-06) | Direction |
|---|---|---|---|
| Canonical spot | $64,404.43 | $64,310.31 | −0.15% |
| **ETF flows** | **2 consecutive green sessions** | **7 consecutive; +$244.4M on 08-05; +$626M/3d** | **▲▲ material** |
| CLARITY Act odds | 28–37% (Polymarket, 5-day binary) | **18%** (no floor vote attempted; deadline 08-10) | ▼▼ |
| Sept hike odds | ~59–63% | **62–76%** (sources disagree; disclosed) | ▲ hawkish |
| MVRV-Z | 0.3714 (08-04) | **0.3918** (08-05) | ▲ — band unchanged at 4 |
| F&G 3-day avg | 26.67 | **25.67** | ▼ — 0.67 from band 3 |
| Weekly RSI | 38.84 | 38.84 | flat (same completed week) |
| Hash Ribbon gap | −1.97% | **−1.59%** | ▲ narrowing — nearest dark gate |
| Campaign low age | 35 sessions | 36 sessions | ▲ |
| **D1 term** | **−1.0** | **−0.5** (factor (ii) falsified and replaced) | ▲ |
| **Adjusted score** | **10** | **11** | ▲ +1 |
| Mechanical score | 11 | 11 | flat |
| Gates | 3/9, [V] 2 | 3/9, [V] 2 | flat |
| FR companion | 7 | **6** | ▼ (FR-B momentum leg 2→1) |
| Correlation 30d | 0.313 | 0.301 | ▼ marginally |
| EV-vs-spot | −2.08% | −1.12% | ▲ |
| Ledger band | EXPIRED 94.2h | **EXPIRED 126.7h** | ▼▼ |
| Stops | $50k / $55k+score<12 | unchanged | flat |

---

## 12. Strategic Verdict

**Adjusted score 11/20 · mechanical 11/20 · gates 3/9 ([V] 2) · weighted EV $63,590 · EV-vs-spot −1.12% (geometry-driven) · F&G 3d 25.67, Extreme Fear · stance: PHASE 1A AUTHORIZED, EXECUTION BLOCKED ON THE LEDGER, 100% DRY.**

The score has ticked to 11 and the Score Interpretation table now reads "Building — Phases 1A–1B eligible." I want to be careful not to let that table oversell what the gates authorize, because this is the exact failure the 2026-06-11 reconciliation note was written to prevent: Phase 1B is **two gates and one [V] gate short**, and no amount of score movement changes that. The honest statement is narrower and more useful — **Phase 1A is unlocked, spot is inside its zone, and the framework wants to be long 10% of the book right now.**

What has genuinely changed is institutional flow, and I do not want to undersell it either. Seven consecutive sessions without a net outflow, $626M over three days, corroborated across four independent outlets, is the framework's own definition of a sustained inflow regime. It does not light gate 4 — gate 4 is a fear detector and wants the opposite — but it is the thing gate 4 was built to notice, running in reverse. Set against a market at 13th-percentile volatility and 9th-percentile volume, sitting 1.2% from its 200-week mean with a 36-session-old low and an unbroken ascending-low sequence, the structural read is a base that is being accumulated quietly. I am not going to call it a bottom. The scorecard is +1 of balanced, |EV-vs-spot| is 1.12%, and July payrolls print in fourteen hours into a tape with no volatility cushion — the collar applies, and it applies for good reasons rather than as boilerplate.

The uncomfortable part is that none of this is what is stopping the fill. For the fourth consecutive report, the binding constraint is a position snapshot that is now **126.7 hours stale**, carrying `basis.reliable = false` on five unbacked disposals, in a stablecoin pool shared with two other live report series. I cannot size 10% of a book I cannot measure, and Hard Rule 8's carve-outs exist precisely so that a guess never becomes a fill. That is a bookkeeping failure and it should be graded as one — not as analytical patience. A single ledger refresh converts a blocked report into an executed tranche.

### Action items

1. **Refresh the position snapshot.** `POST /link` in the personal-accounting app, then re-run `node tools/position.mjs btc`. This is the single highest-value action in this report and it is the fourth time it has been item #1.
2. **On a FRESH snapshot showing Phase 1A unfilled:** ladder **10%** of book across **$63,000–$66,500** in three clips — $66,000 / $64,500 / $63,200. Never at the top of the zone. Tag **`FK-P1A`**, note first line `report=reports/btc_fallen_knives_20260806_1836.md`, stating the $50,000 catastrophic floor and the $55,000-plus-score compound line.
3. **Fix the ledger's basis defect.** Five unbacked BTC disposals (0.03360450 BTC) are what set `basis.reliable = false`. Until they are ingested, no report in this series can quote a cost basis or resolve a phase-dependent unlock precondition.
4. **Hold both stops unchanged.** Catastrophic $50,000; compound $55,000 AND mechanical score <12. Checkpoint 2026-08-09 (Sunday) — structurally cannot fire, 0 of 2 required closes exist.
5. **Watch the 08-07 payrolls print** as the near-term resolver, and the Hash Ribbon 30d/60d gap (−1.59%, narrowing) as the closest dark gate to lighting.
6. **Do not trim anything.** No exit trigger is within range, no narrative break is active, and there is no verified position to trim.

> **The Pattern**
>
> **IF** payrolls print soft on 2026-08-07 **AND** the ETF inflow streak extends past 10 sessions **THEN** the Rally cell earns weight it does not yet have and the Hash Ribbon likely lights within weeks — but a *regime* call still waits on a weekly close above $68,875, not on a bounce.
>
> **IF** payrolls print hot **AND** BTC closes below **$62,226.58** **THEN** the ascending-low sequence is broken, the trend residual flips to active downtrend, the Override's quarter-size throttle arms, and the Phase 1B zone at $58,000–$61,500 becomes the live question rather than a theoretical one.
>
> **IF** the ledger is refreshed and shows Phase 1A unfilled **THEN** the tranche fills on the ladder above, at a price the framework has already authorized — and the four-report gap between what this framework decided and what the book actually did finally closes.

---

*Report generated 2026-08-06 18:36 EDT. All figures carry source and timestamp. Position of record: EXPIRED — cold start under Hard Rule 4, stated explicitly. Toolchain: `position.mjs` → `fetch.mjs` → `compute.mjs` → `lint-report.mjs` → `export-signals.mjs`.*

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-BTC-20260806-1836 | LOCKED | crypto |
| 1B | FK-P1B-BTC-20260806-1836 | LOCKED | crypto |
| 2 | FK-P2-BTC-20260806-1836 | LOCKED | crypto |
| 3 | FK-P3-BTC-20260806-1836 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_fallen_knives_20260806_1836.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-08-06",
  "spot": { "value": 64310.31, "source": "median of 4 synchronized live quotes: CoinGecko $64,310.00 / Binance BTCUSDT $64,364.10 / Coinbase BTC-USD $64,310.61 / Kraken XBTUSD $64,306.70 (all venue timestamps within a ~2-minute band, 2026-08-06T22:36Z); spread 0.089%, all live; Yahoo BTC-USD $64,310.61 EXCLUDED as a frozen bar close. SKILL-mandated median used; tool panel canonical 64310.305, rounded to 64310.31" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 4, "capitulation": 0, "holder": 3 },
    "discretionary": -0.5,
    "mechanical": 11,
    "raw": 10.5,
    "adjusted": 11,
    "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 6, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 31, "low": 66500, "high": 71000 },
      { "name": "Range", "p": 38, "low": 62000, "high": 66500 },
      { "name": "Retest", "p": 20, "low": 57500, "high": 62000 },
      { "name": "Bear", "p": 11, "low": 50000, "high": 57500 }
    ],
    "stated_ev": 63590.00,
    "vs_spot_pct": -1.12
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "63000-66500 zone, spot 64310.31 INSIDE it and 1310 above the floor. Unlock conditions MET on the mechanical route (adjusted 11>=8, gates 3/9>=3, [V] 2>=2) so NO D5 stop attaches. Entry-zone ratchet SATISFIED - no fill would sit above the zone top. NO entry_price and NO fill authorized: position.mjs returns band EXPIRED at 126.7h (exit 1), so this report proceeds as a COLD START under Hard Rule 4 and neither the existing position nor the dry-powder balance is knowable; the stablecoin pool is additionally shared with the ETH and gold series. Conditional authorization in section 6.2: IF a FRESH snapshot shows 1A unfilled THEN ladder 10% across 63000-66500 in three clips (66000/64500/63200), tag FK-P1A. FOURTH consecutive report blocked on this same data defect; the snapshot degraded 50.2h -> 94.2h -> 126.7h across three reports", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "58000-61500 BLOCKED ON GATES ONLY. Score line now MET (adjusted 11 >= 11 - the smaller D1 no longer removes the crossing under half-up rounding). Gates 3/9 < 5 required, and [V] 2 < 3 required: short TWO gates and ONE [V] gate. D2 unavailable on two independent grounds: short by TWO not exactly one, and D2 substitutes for a gate never for a [V] floor. Per the section 4.2 binding-axis audit, the score line moving to TRUE is a movement on the NON-binding axis and NO change in posture or conviction is claimed from it", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "54000-58000 frozen (adjusted 11<15, gates 3/9<6; corr condition would PASS at 0.301<0.80 but is not reached)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17, gates 3/9<7; no analyst channel reaches this tranche)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 },
    "note": "NO stop parameter changed value in either direction. Mechanical score 11<12, so the compound stop's score axis IS satisfied - the stop is effectively single-key and price-gated at $55,000, which makes it fire MORE readily, not less (section 4.2 tags this line VACUOUS-PERMISSIVE on 7 consecutive report dates and discloses it in that direction). The D1 -0.5 has ZERO effect on this line: the compound stop reads the MECHANICAL score per the 2026-07-27 governing rule, so a negative discretionary term cannot make the book's stop fire more readily than the evidence warrants, exactly as a positive one could not suppress it. CHECKED, not assumed. Coherence: catastrophic $50,000 strictly below deepest named zone floor $54,000 = PASS (compute.mjs stop-coherence pass:true). No D5 stops - zero analyst-channel tranches have ever been opened in this series, and Phase 1A unlocked mechanically so a fill would carry the compound stop, not a D5 stop. Max drawdown spot-to-compound-line -14.47%, disclosed; purchases no loosening under D6. D6 ratchet: compliant.",
    "migration": [],
    "checkpoint": {
      "date": "2026-08-09",
      "line": 55000,
      "condition": ">=2 consecutive weekly closes <55000 AND mechanical score <12",
      "closes_below": 0,
      "adr": 1187.12,
      "adr_sessions": "2026-08-01, 08-02, 08-03, 08-04, 08-05 - the in-progress 2026-08-06 session EXCLUDED as not a full session at 22:36Z, lookback extended one session to reach five full ones, exclusion disclosed inline (compute.mjs adr --exclude 2026-08-06)",
      "dist_x_adr": 7.84,
      "calendar_validation": "2026-08-09 is a Sunday, a valid weekly-close boundary for a 24/7 venue; no restatement applied; date computed and validated BEFORE any distance language",
      "side": "spot 16.93% above line; structurally cannot fire (0 of 2 required closes exist, and one weekly close cannot supply two). Tier-1 release BEFORE this checkpoint: YES - Employment Situation / July nonfarm payrolls Fri 2026-08-07 08:30 ET (BLS schedule, verified), named in the falsifier: a hot print pushes September hike odds above the current 62-76% and pressures the tape toward Retest; a soft print cuts them and supports Rally. NFP cannot produce two sub-55000 weekly closes by 2026-08-09. Next tier-1 after: CPI Wed 2026-08-12 08:30 ET (BLS CPI schedule, verified)."
    }
  },
  "companion_fr": {
    "score": 6,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 49.04, "ma200_falling": true, "ma200_slope20_pct": -3.78, "price_below_ma200_pct": -8.79 },
    "legs_channel_b": { "rally_extension": 1, "local_momentum": 1, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 57747.77, "low_40s_date": "2026-07-01", "bounce_pct": 11.36, "daily_rsi14": 51.61, "weekly_rsi14": 38.84, "bounce_age_sessions": 36, "funding_annualized_pct": 5.97 },
    "counts_used": { "resistance_count": 1, "structure_count": 2, "sentiment_count": 1 },
    "counts_derivation": "resistance 1/4: (a) within 3% of 200dma FALSE (-8.79%), (b) within 3% of 50dma from below FALSE (price 1.69% ABOVE the 50dma), (c) price at/below a prior swing high that is itself a lower high TRUE (bounce high 65658.34 on 2026-07-27), (d) prior breakdown level FALSE. structure 2/3: (a) bounce high is a lower high TRUE, (b) 50dma below 200dma AND gap NOT narrowed FALSE (gap_narrowed_20=true), (c) no weekly close above the 200dma in 8 weeks TRUE. sentiment 1/3: (a) F&G 3d >=1.5x its 30d mean FALSE (25.67 vs a 25-30 band), (b) funding flipped positive after >=5 negative sessions FALSE (zero negative intervals in 45), (c) flow tell TRUE (7-session ETF inflow streak into the rally, +$244.4M on 2026-08-05).",
    "gates_note": "Channel B Phase 1A line 13 - short by 7. Penalty 0 (squeeze tier none; bounce 36 sessions old so no maturity penalty). Confidence full, no missing inputs. Local-momentum leg fell 2->1 vs 2026-08-05 as daily RSI slipped 52.35 -> 51.61 across a band edge.",
    "cross_validation": "consistent - FK 11 (mechanical) / FR 6, inversely related, both <12 so Hard Rule 5's both->=12 condition is NOT met. Label UNQUALIFIED and carrying full evidentiary weight because the Channel A phase-of-cycle cap is NOT binding (Channel B is the live channel), so the both->=12 check is genuinely falsifiable rather than vacuous by construction.",
    "standalone_report_owed": false,
    "standalone_report_trigger": { "owed": false, "trigger": null, "fired_on": null, "reports_outstanding": 0 },
    "standalone_report_note": "Not owed on BTC: companion 6 < 9; no FK phase-unlock threshold crossed by the score; short-side liquidation volume ~$30M, far under the $100M trigger; the phase-of-cycle cap is not binding. The ETH obligation outstanding since 2026-08-01 is DISCHARGED by reports/eth_flying_rocket_20260806_1844.md, published earlier today.",
    "independent_corroboration": "A standalone BTC Flying Rocket report also ran today (reports/btc_flying_rocket_20260806_1844.md), scoring Channel B at mechanical 7 -> adjusted 7 and standing down by six against the Channel B Phase 1A line of 13. It reaches mechanical 7 where this inline companion computes 6, on ONE leg: its rally-extension leg reads 2 against my 1, measured to today's session high (+12.42% to $64,922.95) rather than to the campaign-low bounce (+11.36%). The divergence is one sub-criterion, in the more-cautious-on-shorts direction, and changes no verdict on either side - recorded rather than reconciled away. That report independently reproduces this report's FK legs (2/2/4/0/3) and 3/9 gate board EXACTLY, and its own correlation read (0.256 over a Jul-8 to Aug-6 window) agrees with this report's 0.301 (Jun-25 to Aug-6) in regime and surcharge state despite the different window."
  },
  "position": {
    "source": "tools/position.mjs btc",
    "exit_code": 1,
    "band": "EXPIRED",
    "age_min": 7604,
    "age_driver": "holdings_as_of",
    "generated_age_min": 7602,
    "expired_after_min": 4320,
    "cold_start": true,
    "cold_start_basis": "Hard Rule 4 - stated explicitly, no fresh ledger was available",
    "qty": null,
    "avg_cost_usd": null,
    "total_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd": null,
    "attribution": "UNKNOWN - ledger unreadable",
    "dry_powder_stable_usd": null,
    "dry_powder_benchmark_pct": 3.73,
    "dry_powder_benchmark_source": "Yahoo ^IRX 3.732% on 2026-08-06; FRED DGS3MO cross-check 3.89% on 2026-08-05, delta -0.16pp",
    "last_readable_snapshot": {
      "as_of": "2026-08-01T15:51:56Z",
      "read_in_report": "reports/btc_fallen_knives_20260803_1411.md",
      "band_then": "STALE",
      "qty": "0.00000184",
      "custody_status": "RECONCILED",
      "withdrawn_qty": "0",
      "basis_reliable": false,
      "oversold_qty": "0.03360450",
      "unbacked_disposal_count": 5,
      "short_qty": null,
      "attribution": "UNTAGGED",
      "untagged_open_deals": 2,
      "performance_by_tag": [],
      "dry_powder_stable_usd": 14408.87,
      "dry_powder_note": "SHARED POOL - the same balance backs the ETH and gold series. Two reports each sizing '10% of the book' against it would double-commit the same dollars.",
      "portfolio_total_usd": 19790.26
    },
    "note": "EXIT 1 / EXPIRED at 126.7h - cold start per Hard Rule 4, stated explicitly. NO quantity, cost basis, PnL, ROI or dry-powder figure is asserted as current, and NO tranche is sized against the last readable snapshot. Position Reconciliation: prior reports narrate '10% Phase 1A at ~$65,000 blended' carried forward for weeks; the last readable (STALE) snapshot showed dust with basis.reliable=false on 5 unbacked disposals and zero deal tags, and could neither confirm nor refute it. The narrated tranche is UNVERIFIED in BOTH directions - not confirmed, not refuted, explicitly not read as flat. Degradation across reports: STALE 50.2h (08-03) -> EXPIRED 94.2h (08-05) -> EXPIRED 126.7h (today). Root cause is a personal-accounting-ledger fill-ingestion defect (unbacked disposals), not a framework one."
  },
  "trend_residual": { "active_downtrend": false, "basis": "MA half only half-satisfied: price is 8.79% below a falling 200dma (-3.78%/20 sessions) and the 50dma sits below the 200dma, but price is +1.20% ABOVE the 200-week mean. Lower-lows half fails outright: the campaign low $57,747.77 (2026-07-01) has held 36 sessions and recent lows ASCEND (62233.01 Aug-1, 62226.58 Aug-3, 63277.68 Aug-4, 63829.72 Aug-5, 64135.50 Aug-6)", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) - stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15. Terminal-vs-extreme reconciliation NOT triggered because the residual is not live - recorded so the omission is deliberate rather than silent." },
  "correlation": { "value_30d_vs_spx": 0.301, "window": "2026-06-25 to 2026-08-06", "method": "Pearson on daily log returns, 30 aligned sessions / 29 return observations, Yahoo BTC-USD vs ^GSPC closes, computed 2026-08-06 via lib.mjs correlationFromCloses", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.301 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds - it is barred on gate arithmetic (1B short by two gates, and the [V] floor fails)" },
  "score_line_audit": {
    "attainable_ceiling": 20,
    "ceiling_derivation": "sentiment 5 (crypto F&G available daily from the pinned provider - no NOT-FOUND pin), momentum 4, valuation 5 (MVRV-Z sourced, no fallback), capitulation 3, holder 3. BTC carries NO structural leg pin, so no score line is VACUOUS-FALSE. Re-derived this report from this report's own pins, not carried forward.",
    "trailing_dates_examined": ["2026-07-20", "2026-07-22", "2026-07-23", "2026-07-25", "2026-08-01", "2026-08-03", "2026-08-05", "2026-08-06"],
    "line_states": [
      { "line": "phase_1a_ge_8", "reads": "adjusted", "value_now": 11, "predicate": true, "state": "VACUOUS-PERMISSIVE", "consecutive_dates": 8 },
      { "line": "phase_1b_ge_11", "reads": "adjusted", "value_now": 11, "predicate": true, "state": "LIVE", "note": "FALSE on 08-03 and 08-05, TRUE on 08-01 and today - the predicate is genuinely moving" },
      { "line": "phase_2_ge_15", "reads": "adjusted", "value_now": 11, "predicate": false, "state": "VACUOUS-BLOCKING", "consecutive_dates": 8 },
      { "line": "phase_3_ge_17", "reads": "mechanical", "value_now": 11, "predicate": false, "state": "VACUOUS-BLOCKING", "consecutive_dates": 8 },
      { "line": "override_arming_ge_15", "reads": "mechanical", "value_now": 11, "predicate": false, "state": "VACUOUS-BLOCKING", "consecutive_dates": 8, "note": "EXEMPT from the silencing consequence - the Override is evaluated mechanically every report regardless of any vacuity tag on its arming line, and was evaluated in section 6.3" },
      { "line": "compound_stop_score_lt_12", "reads": "mechanical", "value_now": 11, "predicate": true, "state": "VACUOUS-PERMISSIVE", "consecutive_dates": 7, "direction_note": "PERMISSIVE: the score key is standing satisfied, so the stop is effectively single-key and price-gated at $55,000 - which makes it fire MORE readily, not less. Not a defect claim; D6 governs and the line may rise, never fall." }
    ],
    "binding_axis": {
      "1A": "neither - UNLOCKED (score +3 over the line, gates exactly at 3/9 with [V] 2/2). The binding constraint on a FILL is the EXPIRED ledger",
      "1B": "GATES - score short 0, gates short 2 and [V] short 1",
      "2": "both - score short 4, gates short 3",
      "3": "both - mechanical score short 6, gates short 4"
    },
    "one_directional_note": "Informational only. May NOT be cited to lower a score line, credit a leg, reduce a gate requirement or denominator, or move any stop. No aggregate 'effective board X/N' denominator is claimed. No posture change is asserted from the Phase 1B score line moving on the non-binding axis."
  },
  "ev_calibration": {
    "prior_report_date": "2026-08-05",
    "prior_vs_spot_pct": -2.08,
    "prior_spot": 64404.43,
    "realized_change_pct": -0.15,
    "sign_correct": true,
    "streak_sign": "negative",
    "streak_dates": 14,
    "streak_start": "2026-07-11",
    "graded_dates": 13,
    "correct": 7,
    "wrong": 6,
    "hit_rate_pct": 54,
    "cumulative_spot_change_over_streak_pct": 0.16,
    "tripwire_fired": false,
    "tripwire_basis": "requires a >=5-date same-sign streak AND contradiction in the MAJORITY of them; 6 of 13 is not a majority. EV retains its stance-carrying status on BTC.",
    "magnitude_disclosure": "Sign is a coin flip but MAGNITUDE is systematically overstated: mean |EV-vs-spot| across the streak ~1.7% against mean realized report-to-report moves ~1.1%, and 13 consecutive negative calls accompanied a cumulative +0.16%. That is the geometry effect decomposed in section 5.1.",
    "provenance": "Counted BY HAND from the machine blocks of reports/btc_fallen_knives_*.md, NOT from exports/signal-feed.json (the feed's history is not uniformly populated - 2026-07-29 standing caveat). Distinct report dates; the two same-day reports on 2026-07-14 and on 2026-07-18 each count once. Reports before 2026-07-11 carry no machine-block vs_spot_pct and count as UNKNOWN - they neither extend nor break the streak."
  },
  "ev_sign_attribution": {
    "sign": "negative",
    "contributions_pp": { "Rally": 2.140, "Range": -0.036, "Retest": -1.418, "Bear": -1.806 },
    "sum_pp": -1.120,
    "ties_to_stated_vs_spot": true,
    "carried_by": "band distance",
    "label": "geometry-driven",
    "label_basis": "modal cell is Range with its midpoint -0.09% from spot (essentially AT spot); the sign is carried by the Retest and Bear bands' DISTANCE from spot - Bear's midpoint sits -16.42% below spot against Rally's +6.90% above, so an 11% Bear weight outweighs a 31% Rally weight.",
    "consequence": "May inform sizing DOWNWARD only; its sign alone does not carry this report's stance, which rests on score, gates, zone and named risks. NON-DISSOLUTION: this label is diagnostic and does NOT satisfy, weaken or dissolve the EV-floor consistency check, does NOT lift the report out of the Verdict-Confidence Collar, and does NOT substitute for the terminal-vs-extreme reconciliation."
  },
  "discretion": {
    "d1_taken": true,
    "d1_value": -0.5,
    "d1_direction": "negative",
    "d1_consecutive_reports_at_this_sign": 3,
    "d1_consecutive_reports_at_this_size": 1,
    "d1_decay_clock": "size changed this report (-1.0 -> -0.5) and both factors were re-argued from fresh evidence; sign has now been negative for 3 consecutive reports and is flagged for grading",
    "d1_factors": [
      "Coldcard supply and premise overhang, WEAKENING but live. ~1,816 BTC of zero-cost-basis supply sits with at least a dozen distinct attacker groups, and an unquantified cohort of Coldcard users must migrate seeds. This contaminates the INTERPRETATION of the holder leg's 3/3 - half that leg is 'exchange reserves declining', whose bullish reading depends on the destination being safe, and a March 2021 Coinkite firmware build routed seed generation to a deterministic software PRNG instead of the STM32 hardware RNG, collapsing effective key strength from 128 bits to as little as 40. The leg's MEASUREMENT is unaffected and still scores 3; the D1 addresses what the migration MEANS, which no leg or gate can express. NO NEW WAVE reported since ~2026-08-03/04, which is why this factor is weakening rather than escalating. Sources: TechCrunch 2026-08-04, TRM Labs, Forbes 2026-08-04, CoinDesk 2026-08-02, Fortune 2026-08-03.",
      "REPLACEMENT FACTOR (ii): the Fed path re-tightening into a tier-1 print. September hike odds have moved to a 62-76% range from the ~59-63% this series printed on 2026-08-05, with the 10y TIPS real yield at 2.41% and July payrolls fourteen hours away. Gate 9 is a single boolean that resolves the same whether hike odds are 55% or 85% - it cannot express a RISING probability of policy tightening into a levered asset. Gate 9 is a GATE, not a leg, so pricing it in D1 is not the prohibited double-count (adjudicated 2026-08-05: D1(a) prohibits re-weighting a factor a LEG already scores). Sources disagree on both the probability (61.9% vs 76.1%) and the meeting date (Sept 10 vs Sept 16); the disagreement is disclosed rather than resolved, and only the DIRECTION is relied on. Sources: CME FedWatch via centralbank.watch / macromicro 2026-08-04/06; FRED DFII10 2026-08-05; BLS."
    ],
    "d1_retired_factor": "The 2026-08-05 factor (ii) - 'BTC's NON-PARTICIPATION in a record equity melt-up' - is explicitly RETIRED AS FALSIFIED, flagged rather than buried. It rested on SPX +6.41% against BTC +0.8%. Over the five sessions to today BTC is +2.47% (08-01 close 62763.32 -> 64310.31) against SPX +3.66%, and the ETF flow evidence that non-participation was meant to detect has inverted outright into a 7-session inflow streak. That retirement is the entire reason the term halves from -1.0 to -0.5.",
    "d1_falsifier": "Retire the remaining -0.5 when EITHER (a) seven consecutive days pass with no new Coldcard sweep wave AND no attacker deposits to exchanges observed on-chain - the last wave was ~2026-08-03/04, so this resolves around 2026-08-10/11 - OR (b) BTC closes above $68,875, above the gate-6 +8% upper edge, which would demonstrate participation outright. HARD REVIEW DATE 2026-08-19.",
    "d1_effect": "NONE on any integer. Under BTC's half-up convention round(11 - 0.5) = round(10.5) = 11, the same adjusted score 0.0 would produce. The term neither buys nor blocks capital this report. Stated plainly so it is graded on whether its directional claim was correct, not credited with a restraint it did not supply.",
    "d1_larger_considered_declined": "-1.0 DECLINED: the flow evidence has genuinely inverted (7-session inflow streak, +$626M over three days, four independent sources), and holding a full point of negative adjustment against that would be conviction outrunning evidence. The decay rule exists for exactly this case - an adjustment held unchanged while its supporting facts move is stale conviction.",
    "d1_positive_considered_declined": "+0.5 DECLINED as arithmetic laundering: it would be built on the ETF inflow streak and the eased risk backdrop, but the WITHDRAWAL of those same facts from the negative side is what already took the term from -1.0 to -0.5. One fact, one effect.",
    "d2_available": false,
    "d2_taken": false,
    "d2_phase": "1B",
    "d2_detail": "UNAVAILABLE on two independent grounds: the path opens only at a shortfall of EXACTLY ONE gate and 1B is short TWO (3 of 5 required); and the [V] floor would fail on lit gates (2 lit vs 3 required), which D2 may never substitute for. Phase 1A needs no D2 - it is already unlocked mechanically, which is also why a 1A fill would carry the compound stop rather than a D5 stop.",
    "override_evaluated": true,
    "override_fired": false,
    "override_detail": "DOES NOT FIRE. Evaluated mechanically despite the VACUOUS-BLOCKING tag on its arming line, per the section 4.2 exemption. Mechanical score 11 < 15 - dispositive on its own. Two further independent failures: 3-day F&G 25.67 is not <=15, and the Override presupposes a corroborated deployed tranche which an EXPIRED ledger cannot supply. The worsening-flows veto is OFF (flows are strongly positive) and no throttle was reached. No near-fire to log.",
    "d4_taken": true,
    "d4_detail": "Cells set from the read against the 11-14 anchor row (adjusted score 11): Rally +1, Range +3, Retest -2, Bear -2 - all inside the +/-10 percentage-point band, none requiring a >10pp reason line. Direction of deviation: a 7-session ETF inflow streak clearing the framework's own >=5-session sustained-inflow bar, an eased risk backdrop (VIX 15.15, HY OAS 2.75%, SPX at a record), and a 36-session-old campaign low with ascending lows - against a rising September hike path, a failing CLARITY calendar, and a weekly RSI at the 23rd percentile. EV recomputed from the printed cells as the final step (compute.mjs ev: rel diff 0.00%).",
    "entry_zone_reanchor": "DECLINED IN BOTH DIRECTIONS. Downward would run the coherence check against a lower floor for no benefit - spot is already inside the current 63000-66500 zone. Upward is the constrained direction and there is no case for chasing a tape sitting 1.20% above its own 200-week mean. Logged per the 2026-08-05 entry-zone ratchet.",
    "declined_action": "A PHASE 1A FILL was declined for the FOURTH consecutive report despite the tranche being genuinely unlocked, spot sitting INSIDE the zone $1,310 above its floor, and the entry-zone ratchet being satisfied. Reason: the ledger is EXPIRED at 126.7h with basis.reliable=false, so neither the existing position nor the dry-powder balance is knowable, and the stablecoin pool is shared with the ETH and gold series - a 10% tranche cannot be sized against a book that has not been measured. This is a DATA blocker with a one-command remedy and should be graded as a bookkeeping failure, NOT as analytical restraint.",
    "non_mechanical_capital_pct": 0
  },
  "narrative_break_evaluation": {
    "event": "Coldcard hardware wallet exploit - ~1,816 BTC / ~$116-130M drained across five waves from 5,200+ addresses since 2026-07-30; root cause a March 2021 Coinkite firmware build that routed seed generation to a deterministic software PRNG instead of the STM32 hardware RNG, collapsing effective key strength from 128 bits to as little as 40 on older devices. Coinkite has halted shipments and destroyed remaining affected inventory. No new wave reported since ~2026-08-03/04.",
    "trigger_tested": "critical security breach (section 7, Exit 100%)",
    "determination": "NOT A NARRATIVE BREAK - no exit, no trim. Re-tested on updated evidence and unchanged.",
    "reasoning": "Bitcoin's consensus rules, cryptography, issuance and settlement are untouched. Every affected key was weak AT GENERATION, which is why the sweeps execute offline with no network-level exploit. This is a single vendor's product defect - the same class of event as an exchange failure: severe for the users hit, not a change in what the asset IS. The section 7 trigger is written for 'irreparable' and 'thesis voided' and this is neither: Coinkite shipped emergency firmware, halted shipments, destroyed affected units, and the remedy (regenerate seed, move coins) is available to every holder. Scaling the determination with the loss total would be a category error - a larger vendor failure is still a vendor failure. What it DOES impair is the INTERPRETATION of the holder leg's cold-storage premise, which is why it is priced as a D1 term rather than an exit.",
    "sources": ["TechCrunch 2026-08-04", "TRM Labs 2026-08", "Forbes 2026-08-04", "CoinDesk 2026-08-02", "Fortune 2026-08-03", "Benzinga 2026-08", "TheHackerNews 2026-08", "Fox Business 2026-08"]
  },
  "key_inputs": {
    "fng_spot": 25,
    "fng_3d_avg": 25.67,
    "fng_streak_le15_days": 0,
    "fng_last_10_prints": [25, 27, 25, 28, 27, 27, 25, 28, 29, 29],
    "fng_percentile_vs_2y": 27.02,
    "fng_provider": "alternative.me raw API daily series (pinned) - no provider switch, no second-provider divergence >=10 points to disclose",
    "fng_band_proximity": "25.67 sits 0.67 index points above the <=25 band edge; a single sub-24 print takes the sentiment leg 2->3 and the composite 11->12, which would also restore the compound stop's second key. DISCLOSED CONTEXT, not a trigger.",
    "weekly_rsi14": 38.84,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_boundary": "Yahoo weekly candles, week-start timestamps UTC; last COMPLETED weekly close = the bar labelled 2026-07-27 (week ending Sunday 2026-08-02)",
    "weekly_rsi_confidence": "ok",
    "weekly_rsi_live_week_artifact": "CHECKED, NOT ASSUMED. tools/fetch.mjs also reports rsi14_including_live_week = 40.24. Today the tool's completed set correctly ends at the 2026-07-27 bar and returns 261 closes / 38.84, reproducing this series' manually-corrected 2026-08-05 print exactly - no manual correction was needed this report. The artifact is NOT harmless today: 38.84 lands in <=40 -> band 2 while 40.24 would land in <=45 -> band 1. The completed-week value is what the momentum input rule requires and is the value used.",
    "weekly_rsi_percentile_vs_2y": 22.87,
    "daily_rsi14": 51.61,
    "sma_200w": 63549.42,
    "pct_vs_sma200w": 1.20,
    "gate6_within_8pct": true,
    "gate6_band": "58,465.47 to 68,633.37",
    "ma200d": 70508.25,
    "ma200d_slope20_pct": -3.78,
    "pct_vs_ma200d": -8.79,
    "ma50d": 63239.33,
    "pct_vs_ma50d": 1.69,
    "campaign_low": 57747.77,
    "campaign_low_date": "2026-07-01",
    "campaign_low_age_sessions": 36,
    "bounce_pct_off_low": 11.36,
    "recent_lows_ascending": [62233.01, 62226.58, 63277.68, 63829.72, 64135.50],
    "mvrv_z": 0.3918,
    "mvrv_z_asof": "2026-08-05",
    "mvrv_z_source": "bitcoin-data.com /v1/mvrv-zscore/last",
    "mvrv_ratio": 1.2359,
    "realized_price": 52328.04,
    "mvrv_z_cross_check": "Santiment mvrv_usd_z_score for bitcoin printed 0.3961 on 2026-07-07 against the bitcoin-data.com series on the same scale - two independent providers within ~0.01 at the overlap. Recorded because the ETH companion report is forced onto the Santiment series and needs its scale corroborated on the asset where both are available.",
    "drawdown_from_ath_pct": -48.99,
    "ath": 126080,
    "ath_date": "2025-10-06",
    "ath_source": "CoinGecko; cross-checked against Yahoo BTC-USD max-range monthly highs which put the all-time high at $126,198.07 in 2025-10 (+0.09%) - same month, same level within rounding",
    "high_1y_pct_below": 49.04,
    "funding_ann_pct": 5.97,
    "funding_mean_per_8h_pct": 0.01,
    "funding_negative_intervals_in_45": 0,
    "funding_longest_negative_run": 0,
    "funding_percentile_vs_history": 80.24,
    "hash_ribbon_status": "MINER CAPITULATION ONGOING - 30d hashrate MA 902.03 EH/s BELOW 60d MA 916.59 EH/s. COMPUTED from blockchain.info charts/hash-rate (362 daily points, last 2026-08-05), recomputed fresh this report and not carried. Gate 5 dark.",
    "hash_ribbon_cross_date": "2026-06-08",
    "hash_ribbon_cross_age_days": 59,
    "hash_ribbon_gap_pct": -1.59,
    "hash_ribbon_gap_prior": -1.97,
    "hash_ribbon_trap_note": "CARRIED WARNING, still live: a web search for Hash Ribbon status surfaces a Bitbo/Cointelegraph piece announcing Hash Ribbons had signalled an END to miner capitulation - the article is dated AUGUST 20, 2024. Scored off the headline, gate 5 would light on two-year-old data. The computed series says the opposite and still does.",
    "liquidations_shorts_usd_m": 30,
    "liquidations_source": "news.bitcoin.com market update 2026-08-06 (short liquidations); coingabbar 2026-08-06 for market cap $2.29T and 24h volume $57B",
    "liquidations_data_gap": "STALE-INPUT DISCLOSURE, debt clock report 1. A full market-wide 24h liquidation aggregate could not be retrieved: the CoinGlass v4 API returned '401 API key missing' and its dashboards render client-side, returning zeros to the fetcher. The sourced short-liquidation figure plus the market-context prints establish the tape is quiet by an order of magnitude relative to any top-decile flush, so capitulation-(a) and gate 7 are scored DARK on that basis - the conservative direction, since a missing liquidation figure cannot CREDIT a capitulation.",
    "lth_supply": "record high, ~16.4M BTC; rising 30d (Glassnode / news.bitcoin.com 2026-08)",
    "exchange_reserves_trend": "~2.67M BTC, multi-year lows, declining (CryptoQuant / The Block); a documented share of outflows going to hardware wallets and private addresses (Benzinga)",
    "etf_flow_2026_08_05_usd_m": 244.4,
    "etf_flow_3d_usd_m": 626,
    "etf_consecutive_green_sessions": 7,
    "etf_streak_corroboration": "CONFIRMED, not PROVISIONAL - the streak-completing session is corroborated by four independent outlets (yellow.com 2026-08-04, cryptoslate, financefeeds 2026-08-05, COINOTAG 2026-08-05), satisfying the single-source streak-completion rule, so the streak may carry regime weight.",
    "etf_custodial_holdings_btc": 1400000,
    "sept_hike_odds_pct": "62-76 (sources disagree: 61.9% per centralbank.watch 2026-08-04, 76.1% per a second FedWatch read; they also disagree on the meeting date, Sept 10 vs Sept 16 - disagreement disclosed, only the DIRECTION relied on; prior report printed ~59-63%)",
    "clarity_act_odds_pct": 18,
    "clarity_act_note": "Polymarket odds of being SIGNED INTO LAW in 2026, down from 28-37% on 2026-08-05 and an 82% February peak. The Senate made NO attempt to bring it to a floor vote on Thursday 2026-08-06; last scheduled workday 2026-08-07; state work period begins 2026-08-10. Sources: CoinDesk 2026-08-05, coingape LIVE 2026-08-06, cryptobriefing, Bitcoin Foundation.",
    "spx_close": 7709.96,
    "spx_delta_5_sessions_pct": 3.66,
    "vix": 15.15,
    "vix_delta_5_sessions_pct": -11.35,
    "dxy": 99.96,
    "brent": 83.33,
    "real_yield_10y_tips_pct": 2.41,
    "hy_oas_pct": 2.75,
    "nfci": -0.529,
    "net_liquidity_usd_trillions": 5.84,
    "stablecoin_supply_usd_bn": 183.39,
    "stablecoin_change_90d_pct": -3.31,
    "tbill_3m_pct": 3.73,
    "context_panel": {
      "note": "DISCLOSED CONTEXT ONLY - not a scored leg or gate",
      "rv10": 23.53, "rv30": 29.20, "rv90": 34.73, "rv30_percentile_vs_2y": 12.91,
      "drawdown_percentile_vs_2y": 93.16,
      "distance_to_200dma_percentile": 43.52,
      "daily_rsi_percentile_vs_2y": 54.04,
      "volume_percentile_vs_2y": 8.63,
      "deribit_dvol": 34.86, "deribit_atm_iv_pct": 31.66, "deribit_skew_90_110_pct": 8.40, "deribit_vrp_pct": 2.46,
      "perp_basis_pct": -0.05,
      "long_short_account_ratio": 1.1468, "long_short_percentile": 3.45,
      "open_interest_btc": 107104.83, "open_interest_percentile": 75.86,
      "read": "93rd-percentile drawdown at 13th-percentile volatility on 9th-percentile volume, with Binance retail account positioning at the 3rd percentile of its own 30-day range. A market that has stopped moving, not one that has stopped falling - and it cuts both ways."
    },
    "tier1_calendar_next_5_sessions": [
      { "date": "2026-08-07", "time_et": "08:30", "event": "Employment Situation - July nonfarm payrolls", "source": "BLS release schedule, verified", "prior": "June 57K vs 110K consensus; May revised to 129K" },
      { "date": "2026-08-10", "event": "no tier-1; Senate state work period begins - CLARITY Act deadline passes", "source": "CoinDesk / coingape 2026-08-06" },
      { "date": "2026-08-11", "event": "no tier-1 identified" },
      { "date": "2026-08-12", "time_et": "08:30", "event": "CPI - July", "source": "BLS CPI release schedule, verified" },
      { "date": "2026-08-13", "event": "no tier-1 confirmed; PPI conventionally near this date but NOT verified this cycle - flagged rather than assumed" }
    ],
    "tier1_completeness": "No unenumerated tier-1 US release sits inside this report's 5-trading-day horizon. This is NOT an incomplete-data report on the calendar dimension."
  },
  "verdict": {
    "stance": "PHASE 1A AUTHORIZED, EXECUTION BLOCKED ON THE LEDGER, 100% DRY",
    "collar_applies": true,
    "collar_basis": "|EV-vs-spot| 1.12% < 2% AND the bull/bear scorecard is within 1 of balanced (7 vs 6). MECHANICAL score 11 is outside the 6-10 band, but two of the three collar conditions are independently met, so the collar is ON. No directional regime resolution is declared; every forward claim in section 12 carries an IF->THEN and a named falsifier. Strong-claim unlock (mechanical >=15 OR a realized trend-structure event) is NOT met.",
    "cross_validation_citable": true,
    "trailing_2w_realized_pct": -1.13,
    "trailing_2w_note": "$65,044.81 close on 2026-07-23 -> $64,310.31. The negative EV is printed during a NEGATIVE two-week move - the two agree in sign, so there is no momentum contradiction to flag in either direction. Disclosed symmetrically per the 2026-08-06 rule."
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_fallen_knives_20260806_1836.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "BTC",
      "report_date": "2026-08-06",
      "report_local_time": "18:36",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-BTC-20260806-1836",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260806_1836.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:36"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-BTC-20260806-1836",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260806_1836.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:36"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-BTC-20260806-1836",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260806_1836.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:36"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-BTC-20260806-1836",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260806_1836.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:36"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "btc_fallen_knives_20260806_1836.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "BTC",
    "report_date": "2026-08-06",
    "report_local_time": "18:36",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-BTC-20260806-1836",
      "FK-P1B-BTC-20260806-1836",
      "FK-P2-BTC-20260806-1836",
      "FK-P3-BTC-20260806-1836"
    ],
    "status": "REGISTERED"
  }
}
```
