# 🚀 FLYING ROCKET ANALYTICS — BTC — August 6, 2026

## $626M OF ETF INFLOWS IN THREE DAYS INTO A STALLED BOUNCE — THE ONLY LEG THAT IMPROVED IS THE ONE I TRUST LEAST

### Report Generated: Thursday, August 6, 2026, 6:44 PM EDT
### Channel: **B — Bear Continuation** (−49.04% off 1y high · 200dma falling −3.78%/20 sessions · price −8.77% below it)
### Asset: BTC | Prior Score: 6 / 20 (2026-07-31, Channel B) | Mechanical: **7 / 20** | Discretionary: **−0.5** | Adjusted: **7 / 20**
### Cross-Check: Fallen Knives (BTC, same fetch): **11 / 20 mechanical computed, 3/9 gates** · published **same trading day** (`btc_fallen_knives_20260806_1836`): **11 mechanical / 11 adjusted, 3/9 gates** — legs agree **exactly** (2/2/4/0/3); both **<12**, force-cover does **NOT** fire

> **Read this first.** BTC's mechanical score rose one point, 6 → 7, and the entire increment came from a single sub-criterion: ETF flows resuming. The rubric scores that as *"longs re-crowding into the bounce"* — evidence for a short. Six consecutive inflow sessions totalling **$626M in three days** does not read that way to me; at that scale it is absorption, not froth. I have taken a **−0.5 discretionary term** against my own score improvement and explained it in full in §9. Adjusted **7**, six points short of Channel B's Phase-1A line of 13. **STAND DOWN.**

---

## 0. Prior FR Forecast Check & Falsifier Status (mandatory)

Grading `reports/btc_flying_rocket_20260731_0426.md`, the last published BTC FR:

| Prior (Jul 31) claim | Realized (Jul 31 → Aug 6) | Grade |
|---|---|---|
| **EV_price = $63,419** | Spot **$64,309.93** — realized **+1.40% ABOVE** the prior EV_price | **HELD** (the call under-shot by ~1.4%) |
| **Modal band: "Range around the 50dma / 200-week" 38%, $62,000–66,000** | Every daily close in the window sat inside it: 62,813.75 / 62,763.32 / 63,482 / 63,460.90 / 64,055.95 / 64,597.50 / 64,309.93 | **HIT** — the modal band contained the entire path; the 28% "Bounce resumes $66,000–71,500" mode never printed |
| **Prior falsifier (from Jul-16): "a held trend-repair rally"** — graded PARTIAL FIRE on Jul-31 (weekly closes above the 200-**week** SMA, ETF limb refuted) | The weekly-close limb **holds and extended**: spot $64,309.93 is **+1.20%** above the 200-week SMA $63,549.42. But the **ETF limb has now reversed** — the record-low July and the −$526.5M four-session outflow gave way to six consecutive inflow sessions and +$626M in three days. The 200-**day** structure remains unrepaired (price −8.77% below a falling 200dma). | **PARTIAL FIRE, now on BOTH limbs** — upgraded from one limb to two. The 200-day structure is the only limb still refuting it. |

**Trailing EV calibration (n=3, the last three published BTC FR EVs):** Jul-14 **+0.2%**; Jul-16 **−2.41%**; Jul-31 **+1.40%**. Mean signed error **−0.27%**.

**The Jul-31 bias flag is now retired, and I want to record the retirement explicitly.** That report flagged three consecutive same-signed errors (−1.1%, +0.2%, −2.41%) as a candidate downward bias and declined to correct it, on the correct ground that correcting a forecast downward *strengthens* a short case and Hard Rule 6 does not permit that at n=3. The next observation came in **+1.40%** — the opposite sign. **The apparent bias was noise.** Declining to act on it was right, and this is what the discipline buys: a rule that stopped me from tuning a forecast toward the answer I wanted, on evidence that turned out not to be evidence.

**The upside-mode miss, same as ETH.** Three consecutive BTC FR reports have assigned 27–45% to a "bounce resumes toward the 200dma" scenario. It has printed **once, partially** (the Jul-21 $66,910.06 touch) and never held. The 200dma has been 8–11% overhead throughout. I am holding this mode at **27%** rather than cutting it — unlike ETH, BTC's bounce did at least reach the lower edge of its band once — but the pattern is logged for calibration.

**Re-check triggers:** none of §6.6(a)–(e) fired since Jul-31. Trigger (b) was checked specifically and did **not** fire: the August liquidation tape has been small and two-sided, with no ≥$100M shorts-dominated day and no squeeze led by BTC. **This is a user-requested run.** The full §2.5/§4B stack was re-run from scratch.

---

## 1. Channel Determination (§2.5)

| Test | Measurement | Source |
|---|---|---|
| >20% below 1-year high | **−49.04%** (spot $64,309.93 vs $126,198.07, 2025-10-06) | `tools/fetch.mjs btc`, Yahoo BTC-USD trailing-1y weekly highs, Aug-6 22:36 UTC |
| 200dma falling over trailing 20 sessions | **−3.78%** ($70,508.25 today) | computed, Yahoo BTC-USD 2y daily |
| Price below the 200dma | **−8.77%** | derived |

All three hold → **Channel B — Bear Continuation is LIVE**, second consecutive report.

### The wrong-asset row — checked in detail, and it does NOT fire

This is the most consequential §2.5 determination in this report, and it turns on a data conflict.

The row fires when BTC dominance is in a **confirmed uptrend** (30d trend up, dominance >55%, **broke out of a multi-month range**) **AND** the Altcoin Season Index is **<40**.

| Conjunct | Reading | Met? |
|---|---|---|
| Dominance >55% | **56.617%** | ✅ |
| 30d trend up | 56.3% (Jul-31) → 56.617% (Aug-6) — up, but by 0.3pp | ✅ marginal |
| **Broke out of a multi-month range** | 56.6% is **inside** the range BTC dominance has occupied for months; no breakout | **❌** |
| Altcoin Season Index <40 | **37** | ✅ |

**Three of four conjuncts hold; the breakout conjunct fails, so the row does not fire.** No screening run is mandated before this report's conclusions stand.

**The data conflict, disclosed in full because it decides the row.** Two secondary outlets reported BTC dominance at **60.3–60.66%** this week — [BeInCrypto](https://beincrypto.com/bitcoin-dominance-explodes-to-60-66-and-buries-altseason-hopes-for-2026/) ("hits 60.66%, killing altseason hopes") and [AInvest](https://www.ainvest.com/news/bitcoin-dominance-60-3-flow-analysis-capital-rotation-altcoins-2605/) ("60.3%, the highest in 2026"). **At 60.66% the breakout conjunct would arguably hold and this row would fire.** I reject those figures: the CoinGecko `/global` primary endpoint returns **56.617%** at Aug-6 22:35 UTC, and [CoinGabbar's Aug-6 daily wrap](https://www.coingabbar.com/en/crypto-currency-news/crypto-news-today-august-6-bitcoin-gains-defi-stablecoin-drops) independently prints **56.5%** on the same day. Two independent sources agreeing at ~56.6% against two secondaries at ~60.5% is not a close call, and the primary governs per the Data Source Priority table. The 60%+ figures likely reference a different denominator (BTC dominance excluding stablecoins is a commonly published variant that runs several points higher).

**Stated plainly so a future calibration can grade it:** had the 60.66% figure been accepted, the wrong-asset row would have fired and this report would have been required to recommend a §6.5 lagging-alt screen before any BTC short. The verdict — STAND DOWN — is unchanged either way, because the score fails by six points regardless.

**Other §2.5 rows:** smaller-alt row N/A (BTC, deepest borrow available — Bitfinex fBTC 0.0012% annualized on a 1,217-BTC ask). The altseason squeeze-trap row is inapplicable (that row governs *alt* shorts during falling dominance; BTC is not an alt and dominance is rising).

---

## 2. Verified Live Data

### Canonical spot reconciliation (median of 3 synchronized venue quotes)

| Source | Price | Timestamp (UTC) |
|---|---|---|
| Binance BTCUSDT | $64,364.10 | Aug-6 22:36:42 |
| Coinbase BTC-USD | $64,309.93 | Aug-6 22:36:41 |
| Kraken XBTUSD | $64,308.90 | Aug-6 22:36 (receipt) |
| **Canonical (median)** | **$64,309.93** | inter-source spread **0.086%** |

Spread **0.086% < 0.5%** → no dual-extreme EV computation required.

### Price, structure, and what moved

| Metric | Value | Prior (Jul 31) | Source |
|---|---|---|---|
| Spot | $64,309.93 | $63,825.45 | median of 3 venues, Aug-6 22:36 UTC |
| 1-year high | $126,198.07 (2025-10-06) | same | Yahoo BTC-USD |
| % below 1y high | −49.04% | −49.43% | computed |
| Trailing 40-session low | **$57,747.77 (2026-07-01)** | same | computed |
| Bounce high | $66,910.06 (2026-07-21) | same | computed |
| **Rally to today's session high ($64,922.95)** | **+12.42%** | +13.03% | computed |
| Rally to the bounce high | +15.87% | +15.87% | computed |
| **Sessions since the 40-session low** | **36** | 30 | computed |
| Sessions since the bounce high | **16** | 10 | computed |
| 200dma | $70,508.25 (falling −3.78%/20s) | $71,460.20 (−3.41%) | computed |
| 50dma | $63,239.31 — price **+1.71% ABOVE** | $63,405.59 (+0.64% above) | computed |
| 50/200 gap | −10.31% (was −13.06% 20 sessions ago → **narrowed**) | −11.27% (from −11.96%) | computed |
| **200-week SMA** | **$63,549.42 — spot +1.20% ABOVE** | $63,555.64 (+0.55%) | computed |
| Weekly RSI-14 (last completed week Jul-27) | **38.84** | 40.04 | Wilder, 261 completed closes |
| Weekly RSI incl. live week | 40.24 | 39.09 | disclosed, not scored |
| Daily RSI-14 | **51.67** | 48.08 | Wilder |
| Daily RSI-14 at the bounce high | 61.56 | 61.56 | Wilder |
| ADR(5) | $1,172.89 = **1.82%** | $1,567.16 = 2.46% | 5 full sessions, none excluded |

**Weekly-RSI hygiene (memory note applied):** the scored figure **38.84** is the last completed weekly bar; the live-week-inclusive print is 40.24. This one matters more than ETH's — 38.84 vs 40.24 straddles the FK momentum band edge at 40 and is flagged in `fetch.mjs`'s own proximity block as the nearest boundary in the whole dataset (gap 1.16 RSI points). For **this** report both are comfortably below §4B's hard qualifier of 50, so nothing scored moves.

### Sentiment

| Source | Reading | Status |
|---|---|---|
| Alternative.me F&G, spot (Aug-6) | **25** | Extreme Fear |
| F&G 3-day average | **25.67** | Extreme Fear |
| F&G 30-day mean (trailing) | ≈**26.0** | — |
| §4B leg (a) threshold (1.5× the 30d mean) | ≈**39.0** | 25.67 is **13 points short** |
| Last 10 prints | 25, 27, 25, 28, 27, 27, 25, 28, 29, 29 | flat, no local greed |

### ETF flows (Hard Rule 1 — live web fetch, not tool-computed) — **the input that moved the score**

| Session | Net Flow | Detail |
|---|---|---|
| Aug-3 | **+$170.1M** | per Farside |
| Aug-4 | **+$211.5M** | IBIT +$170.3M, FBTC +$19.6M, ARKB +$9.2M, BITB +$8.7M, MSBT +$3.7M |
| Aug-5 | **+$244.4M** | IBIT +$196.8M, ARKB +$37.6M, FBTC +$11.3M, BITB +$10.6M, MSBT +$2.8M — strongest daily inflow since late July |
| **3-day total** | **+$626M** | — |
| **Streak** | **Six consecutive inflow sessions** | — |

Sources: [Blockchain.News (Aug-4)](https://blockchain.news/flashnews/bitcoin-etf-211-5m-net-inflows-aug-4), [FinanceFeeds (Aug-5)](https://financefeeds.com/crypto-etfs-extend-recovery-on-august-5-as-bitcoin-funds-attract-244-million-and-ethereum-returns-to-inflows/), [COINOTAG (Aug-5)](https://en.coinotag.com/bitcoin-spot-etf-funds-aug-5-add-244m), [Yellow (six-day streak)](https://yellow.com/news/bitcoin-etfs-six-day-inflows), [CryptoTicker ($626M/3d)](https://cryptoticker.io/en/bitcoin-etf-inflows-august-2026/), [KuCoin (Aug-3, $170.1M)](https://www.kucoin.com/news/flash/us-spot-bitcoin-etf-sees-170-1m-net-inflow-ethereum-etf-records-11-9m-net-outflow).

**Principle 13 durability lock: SATISFIED.** Six consecutive sessions clears the five-session bar. This is a genuine flow regime change from the July picture (record-low +$205M month, −$526.5M over four sessions) and it is scored — see §4 leg 5(c) and the S1 term in §9 that argues against how the rubric reads it.

*Direct comparison to Farside's own primary table was blocked: `farside.co.uk/btc/` returned HTTP 403 and CoinGlass's and TheBlock's dashboards render their tables client-side. Six independent secondary reports of the same Farside figures are used instead, and they agree on every daily print.*

### On-chain

| Metric | Value | Source |
|---|---|---|
| **MVRV-Z** | **0.3918** (Aug-5) | bitcoin-data.com / BGeometrics API, direct fetch |
| MVRV ratio | 1.2359 (Aug-5) | same |
| Realized price (implied) | ≈$52,000 | derived |
| Cross-check | 0.37 on Aug-4 per [AhaSignals](https://ahasignals.com/current-bitcoin-mvrv-z-score/) | agrees |
| **Coinbase Premium Index** | **−0.1145%**, negative for **78 consecutive days** — the longest streak on record since May 19 | [Bloomingbit](https://en.bloomingbit.io/feed/news/117638), [Cryptonomist](https://en.cryptonomist.ch/2026/08/04/coinbase-bitcoin-premium-negative/), CoinGlass |
| **LTH supply** | At a **fresh all-time high** — long-term holders own more of the float than at any point on record | [News.Bitcoin.com](https://news.bitcoin.com/bitcoin-long-term-holder-supply-all-time-high-2026/) |
| Exchange reserves | ~2.67M BTC, multi-year lows | CryptoQuant / [TheBlock](https://www.theblock.co/post/286440/bitcoin-exchange-reserves-drop-to-new-lows-cryptoquant) |
| ETF custodial holdings | ~1.3–1.5M BTC (6.7–7.1% of supply) | same |

**LTH supply at a record high is the single most short-hostile on-chain fact in this report.** §4A's Distribution leg asks for LTH supply *declining* with a heavy profit-taking rate; the actual reading is the exact opposite, and it is not a Channel B scored input at all. It belongs in §9, and it is there.

### Derivatives, carry, and positioning

| Metric | Value | Source |
|---|---|---|
| Perp funding, mean per 8h (45 intervals / 15 sessions) | **+0.01%** | Binance fapi fundingRate, BTCUSDT |
| Perp funding annualized | **+5.97%** (longs pay shorts → carry **INCOME** to a short) | computed |
| Longest sustained run below −5% annualized | **0 intervals** | — |
| Deepest single interval | **−0.38%** annualized — barely negative at all | — |
| Single interval below −7% | **No** | — |
| Funding percentile vs 167d history | **80.24** | disclosed context — funding is historically *elevated*, not depressed |
| Perp basis | −0.05% → annualized carry +5.97% | Binance premiumIndex |
| Binance long/short account ratio | **1.147 (3rd percentile**, falling) | single-venue, ~30d history |
| Taker buy/sell ratio | 1.068 (69th pct, falling) | single-venue |
| Open interest | 107,105 (**76th pct**, falling); 90d high **unavailable** | single-venue |
| Spot borrow (Bitfinex fBTC) | 0.0012% annualized, ask size 1,217 BTC | single-venue lending book, disclosed context |
| Implied carry to a short over 21d | **+0.34%** (income) | computed |

**The long/short account ratio at the 3rd percentile is worth pausing on.** The retail long book on Binance is emptier than it has been in essentially the entire 30-day sample, while funding sits at the 80th percentile. Longs are paying to be long and there are very few of them. Neither of those is a distribution signature — a top has crowded longs paying elevated funding, not a vacant book paying elevated funding.

**Context Panel (disclosed context only — never a scored leg or gate):** Deribit BTC ATM IV **31.66%**, DVOL 34.86, RV30 **29.20%**, VRP **+2.46pp**. Moneyness skew (90/110) **+8.40%** — strongly positive, i.e. the ~10% OTM put ($58,000) is materially richer than the ~10% OTM call ($71,000). Per the 2026-08-05 sign convention, positive skew is a **downside hedging bid**, and a distribution blow-off *compresses or inverts* it. BTC's skew is nearly **double ETH's (+4.69%)** — the options market is paying up for downside protection here, which means the move this framework would be positioning for is already partly bought. That is context **against** the short.

### Macro risk regime

Identical tape to the ETH companion: VIX **15.15 (−11.35% in 5 sessions)**, DXY 99.96, US 10y 4.67%, 10y TIPS real 2.41%, SPX **7,709.96 (+3.66%, −0.34% off its 6-month high)**, Nasdaq **+4.88%**, gold $4,300.50 (+4.89%), Brent $83.33 (flat), HY OAS 2.75% (−0.12pp, tightening), NFCI −0.529 (loose), net liquidity $5.84T, stablecoin supply $183.39B (−0.44% 30d, −3.31% 90d).

**Sourced 30-day correlation to SPX: 0.256** (30 daily log-return pairs, Jul-8 → Aug-6, Yahoo). Below 0.70 → the **risk-on surcharge is OFF on a measured number**. SPX is within 3% of its high, but the correlation conjunct fails.

---

## 3. Critical Developments

- **CLARITY Act odds collapsed to ~16%** before the Aug-7 Senate recess ([CoinGape](https://coingape.com/markets/crypto-market-update-august-6-bitcoin-nears-65k-as-eth-pi-network-top-gains/)). A regulatory *disappointment* — bearish, and it does not fire the §7 upside-narrative-break cover trigger. Notable that BTC rallied 2.4% off the Aug-1 low **into** this news.
- **Six consecutive ETF inflow sessions, +$626M in three days** — a clean reversal of July's record-low +$205M month and its −$526.5M four-session outflow. The largest single change in the input set since the last report.
- **Coinbase Premium negative for 78 straight days**, an all-time record streak. US institutions have been persistent net sellers on the venue for two and a half months — *while* the ETF complex absorbs $626M in three days. Those two facts sit awkwardly together and I address the tension in §9.
- **LTH supply at a record high**; exchange reserves at multi-year lows (~2.67M BTC). Float is being removed, not distributed.
- **Hormuz/Iran de-escalation** plus a soft ADP print drove the VIX collapse and the equity melt-up ([Yahoo Finance](https://finance.yahoo.com/personal-finance/investing/article/bitcoin-and-ethereum-prices-today-thursday-august-6-2026-prices-firm-as-hormuz-negotiations-continue-and-adp-jobs-report-misses-134510256.html)). Squeeze-relevant.
- **BTC held its 200-week SMA.** Spot is +1.20% above $63,549.42 and the weekly closes have stayed above it. This is now a two-report pattern and it is the strongest technical argument against a BTC short.

---

## 4. Flying Rocket Composite Score — §4B (Channel B rubric)

**Regime precondition re-verified at scoring time:** close $64,309.93 below the 200dma $70,508.25 ✓ · 200dma falling −3.78%/20 sessions ✓ · −49.04% below the 1-year high ✓. Channel B stands.

| Category | Max | Measurement | Score |
|---|---|---|---|
| **Rally Extension** | 5 | Bounce off the trailing 40-session low $57,747.77 (Jul-1) **to today's session high $64,922.95 = +12.42%** → band >12% | **2** |
| **Local Momentum Exhaustion** | 4 | Daily Wilder RSI-14 **51.67** → band >45. Hard qualifier: weekly RSI **38.84 < 50** ✓ | **1** |
| **Resistance Confluence** | 5 | 1 of 4 — see below | **1** |
| **Bear Structure Integrity** | 3 | 2 of 3 — see below | **2** |
| **Relative Sentiment / Positioning** | 3 | 1 of 3 — see below | **1** |
| **RAW TOTAL** | **20** | | **7** |

**Rally Extension.** Measured to today's session high $64,922.95 the rally is **+12.42%** (band >12% → 2); to the Jul-21 bounce high $66,910.06 it is **+15.87%** (also band >12% → 2). **Same band on both readings** — no ambiguity here, unlike ETH. Note how close +12.42% sits to the >12% band floor: a 0.4% lower session high would drop this leg to 1 and the score to 6.

**Local Momentum Exhaustion — and the timing problem.** Daily RSI is **51.67** today, but it was **61.56** at the bounce high **sixteen sessions ago**. The exhaustion this leg is designed to catch already happened, and it happened two and a half weeks back. Scoring the current print at 1 is correct by the rubric; the honest gloss is that BTC is not at an exhaustion point, it is well past one.

**Resistance Confluence — 1/4:**
- (a) within 3% of, or rejected from, the 200dma — **❌**. Price is **−8.77%** below. The bounce high topped 8.10% below the then-200dma. Never reached.
- (b) within 3% of the 50dma from below, or has just lost it — **❌**. Price is **+1.71% ABOVE** the 50dma $63,239.31. Within 3% in magnitude but from the **wrong side**, and BTC has not lost it. The rubric says *"from below"*; the strict reading is the harder-to-short one and I take it. **Disclosed:** a loose reading of "within 3% of the 50dma" would credit this and move the leg to 3, and the score to 9 — still four short of 13.
- (c) at/below a prior swing high that is itself a lower high — **✅**. Spot $64,309.93 sits beneath the Jul-21 bounce high $66,910.06, which is itself a lower high against the Jun-15 pivot $67,248.13.
- (d) into a prior breakdown level / gap — **❌**.

**Bear Structure Integrity — 2/3:**
- (a) the bounce high is a **lower high** than the prior swing high — **✅**. $66,910.06 < **$67,248.13 (Jun-15)**. BTC's structure is genuinely cleaner than ETH's here, where the equivalent test fails.
- (b) 50dma below the 200dma **and the gap has not narrowed** over 20 sessions — **❌**. The gap went **−13.06% → −10.31%**, narrowing, and faster than last report's −11.96% → −11.27%.
- (c) no weekly close above the 200dma in the trailing 8 weeks — **✅**. Weekly closes have run $62–66k against a 200-**day** MA of $70,508.25.

**Relative Sentiment / Positioning — 1/3 (up from 0/3, and this is the whole score increment):**
- (a) F&G 3d ≥1.5× its trailing 30d mean, or ≥45 in a sub-30 regime — **❌**. 25.67 against ~39.0.
- (b) funding flipped positive after ≥5 sessions negative — **❌**. No negative run exists to flip from; the deepest interval in the sample is −0.38% annualized.
- (c) a flow tell — **✅**. **Six consecutive ETF inflow sessions, +$626M in three days**, reversing a record-low July. The durability lock is satisfied at 6 ≥ 5 sessions. Scored as *"longs re-crowding into the bounce."* **I disagree with that reading and price the disagreement in §9's S1 term rather than by refusing the leg** — the leg is scored honestly and the objection is booked where it can be graded.

**Modifiers, in the mandated order:**

| Step | Value | Note |
|---|---|---|
| Raw legs | **7** | 2 + 1 + 1 + 2 + 1 |
| Squeeze-trap penalty | **0** | NOT active. `sustained3_below_minus5 = false`; deepest interval −0.38% ann., nowhere near the −5% trigger; no single print below −7%. `compute squeeze --funding-annualized 5.97` → tier `none` |
| Bounce-maturity floor | **0** | Rally is 36 sessions old, ≥8 |
| Gate surcharges | **0** | Correlation **sourced** at 0.256 < 0.70 → risk-on surcharge OFF. Squeeze surcharge 0 |
| Cap | **none** | Channel B has no phase-of-cycle cap |
| **MECHANICAL** | **7 / 20** | `fr-composite --channel B` verified |
| Discretionary (S1) | **−0.5** | Flow-structure blind spot — see §9. **Half-up rounding: 6.5 → 7** |
| Cap re-applied | n/a | |
| **ADJUSTED** | **7 / 20** | |

**Channel B Phase-1A line is 13. Adjusted 7 misses it by six points.** Score band 6–8 → **Watch List / PREPARE**.

### Confirmation Gates — Channel B set (X / 9)

Denominator **9**, no N/A.

| # | Gate | Reading | Status |
|---|---|---|---|
| 1 | Rally ≥15% off the trailing 40-session low | **+12.42%** to today's high · **+15.87%** to the bounce high | **❌** |
| 2 | Bounce age ≥8 and ≤35 sessions | **36 sessions** from the low (`bounce_age_sessions`) · 20 low-to-bounce-high | **❌** |
| 3 | Daily RSI-14 ≥52 at the bounce high | **61.56** | ✅ |
| 4 | Weekly RSI-14 <50 | **38.84** | ✅ |
| 5 | Price rejected from, or within 3% of, the 200dma | **−8.77%** below; never reached | ❌ |
| 6 | 50dma below 200dma | $63,239.31 < $70,508.25 | ✅ |
| 7 | Last completed weekly close did not reclaim the 200dma | $64,055.95 vs $70,508.25 | ✅ |
| 8 | **Funding not sustained-negative (veto gate)** | 0 intervals below −5% ann.; funding +5.97% ann. at the 80th percentile | ✅ **GREEN** |
| 9 | F&G 3d ≥1.5× 30d mean, OR funding flipped positive after ≥5 negative | 25.67 vs ~39.0; no negative run | ❌ |

**GATES 5 / 9.** Floors, arithmetic shown: 1A `ceil(3/9 × 9) = 3` [legacy 3] · 1B `ceil(5/9 × 9) = 5` [legacy 5] · P2 `ceil(6/9 × 9) = 6` [legacy 6] · **Phase 3 unreachable in Channel B**.

**Two gate measurements changed against the prior report, both disclosed:**

- **Gate 1** was ✅ on Jul-31, credited on the +15.87% bounce-high reading with the +13.03% today's-high reading disclosed alongside. I now score it **❌** on today's-high (+12.42%), consistent with how the Rally Extension leg is measured in the same report and with the Hard Rule 6 harder-to-short convention. On the bounce-high basis it would still be ✅.
- **Gate 2** was ✅ on Jul-31 (20 sessions low-to-bounce-high). I now score it **❌** on `tools/fetch.mjs`'s own `bounce_age_sessions` field, which is low-to-today = **36**, past the ≤35 bar.

**Under the prior report's conventions the board reads 7/9; under the tool-consistent, harder-to-short conventions it reads 5/9.** Both clear the Phase-1A gate floor of 3 and the 1B floor of 5; 7/9 would additionally clear P2's 6. **The verdict is identical under either — the score misses by six, and no gate board rescues a score.** The convention needs a calibration decision rather than per-report judgment, and it is flagged in §9 for exactly that.

**Gate 8 (the funding veto — the only gate in either framework that voids an unlock on its own) is emphatically GREEN**, and by a wider margin than ETH's: funding at the 80th percentile of its own 167-day history with a deepest negative interval of −0.38%.

---

## 5. Probability Matrix

**Baseline grid departure (S1-authorized, §5).** The top-centric default does not describe a Channel B tape; I use the four modes that partition this setup. Probabilities sum to 100%, the recomputation runs below, reasoning in §9.

**Trend term / confirmation throttle (§5):** BTC is >20% off its 1-year high, so a bounce does not qualify as a confirmed uptrend without ≥15 sessions of higher-high/higher-low structure on weekly closes. It does not have that — the bounce high is **16 sessions behind** and the sequence since is lower highs. **No mass is shifted toward Continued Rally.**

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|
| Bounce resumes toward the 200dma | **27%** | $66,900 – $70,500 | Daily close above $66,910.06; ETF inflow streak extends past ten sessions |
| Range around the 50dma / 200-week SMA | **39%** | $62,500 – $66,900 | Neither $66,900 nor $62,500 closes; the 36-session pattern continues, anchored on the 200-week SMA $63,549 |
| 50dma lost, July-low retest | **24%** | $57,700 – $62,500 | Daily close below the 200-week SMA $63,549 and the 50dma $63,239 together |
| Fresh cycle lows | **10%** | $53,000 – $57,700 | Daily close below $57,747.77, the Jul-1 low |

**EV recomputation from the printed cells (mandatory sum-check):**

```
0.27 × 68,700 = 18,549
0.39 × 64,700 = 25,233
0.24 × 60,100 = 14,424
0.10 × 55,350 =  5,535
                -------
EV_price      = $63,741     probabilities sum = 100% ✓
```

`tools/compute.mjs ev` reproduces **$63,741** exactly.

**Short EV decomposition (sign-aware):**

| Component | Value |
|---|---|
| Spot | $64,309.93 |
| EV_price | $63,741 |
| **Directional EV** | **+0.88%** |
| Expected hold | 21 days (Channel B Phase 1A clock) |
| Annualized funding | **+5.97%** (POSITIVE = longs pay shorts = carry **INCOME** to a short) |
| Carry EV, **true signed** | **+0.34%** |
| Carry EV, **floored for gating** | **0.00%** |
| **Total Short EV, true** | **+1.22%** |
| **Total Short EV, for gates** | **+0.88%** |
| Minimum-edge filter (+3%) | **FAILS by 2.12 points** |
| Carry as % of an 8% target gain | 0.0% → **carry veto does NOT fire** |

**EV voice: LOAD-BEARING, not corroborative-only** — Channel B has no cap and the gate board is above every relevant floor, so the demotion clause does not apply. Trailing calibration of the last three BTC FR EVs: +0.2%, −2.41%, +1.40%; mean signed error **−0.27%**, and the previously-flagged same-sign run is now broken.

---

## 6. Short Deployment Strategy

**Position of record (Hard Rule 8): EXPIRED — cold start per Hard Rule 4, stated explicitly.**

`node tools/position.mjs btc` → exit **1**, band **EXPIRED**, age **7,605 minutes (5.28 days)**, driver `holdings_as_of`, against a 4,320-minute expiry. Per Hard Rule 8 this **refuses the position claim, not the report**: all phases are DRY POWDER by default rather than by measurement, and **no** quantity, cost basis, PnL, custody status or dry-powder figure is carried forward from the Jul-31 report. That report's BTC readings — dust quantity, `basis.reliable = false` on 5 unbacked disposals, and two `UNTAGGED` open long deals claiming ~0.0199 BTC against a reconciled balance of 0.00000184 — were FRESH then and are **not evidence now**. The data defect they describe presumably still exists, but this report cannot assert it.

**Per-channel realized performance:** unreadable this run. As of the last FRESH read, `FR-A-` and `FR-B-` were both empty — **N=0 on realized money.**

### Phase board

| Phase | Size | Score line (Ch. B) | Gate floor | Score now | Gates now | Status |
|---|---|---|---|---|---|---|
| 1A — Probe | 5% | ≥13 | 3 / 9 | 7 ❌ | 5 ✅ | **DRY POWDER** |
| 1B — Add | 10% | ≥15 | 5 / 9 | 7 ❌ | 5 ✅ | **DRY POWDER** |
| 2 — Conviction | 15% | ≥17 | 6 / 9 | 7 ❌ | 5 ❌ | **DRY POWDER** |
| 3 — Generational | — | — | — | — | — | **UNREACHABLE — Channel B has no Phase 3 at any score** |

**Deployed: 0%** of the 50% book cap · **0%** of the 30% per-asset cap · **0%** of the 20% analyst-channel cap.

**Analyst channels, both unavailable:**
- **S1** — the maximum +2.0 term reaches an adjusted 9, still **four points** short. (The term actually taken is *negative*.) Load-bearing discretion is arithmetically impossible.
- **S2 Conviction Path** — unavailable on two independent grounds: the Phase-1A score line is missed by six (S2 requires it *met*), and the gate count is short by **four**, not by exactly one.

### §7 Cover-Trigger Preflight — Channel B set (five triggers)

| Trigger | Reading | Result |
|---|---|---|
| Published FK ≥12 on BTC | Published **same trading day**: **11 adjusted / 11 mechanical**; computed today **11 mechanical**, legs identical | PASS |
| Funding negative ≥3 consecutive intervals | 0 intervals; funding +5.97% ann. at the 80th percentile | PASS |
| Upside narrative break | CLARITY at ~16% is a *downside* regulatory event; Hormuz de-escalation is macro risk-on, not a crypto-specific adoption or regulatory win. **Six ETF inflow sessions is a flow event, not a narrative break** | PASS |
| Last weekly close reclaimed the 200dma | $64,055.95 vs $70,508.25 — no | PASS |
| Bear Structure Integrity 0/3 | **2/3** | PASS |

**Preflight PASSES clean, no borderline WARNING**, and more comfortably than ETH on two axes: Bear Structure 2/3 vs 1/3, and funding at the 80th percentile vs ETH's 61st.

### Stop band (computed for the record; nothing fills)

`frStopBand(fill 64309.93, adr5 1172.89, channel B, phase 1a)`:

| Element | Value |
|---|---|
| Hypothetical fill | $64,309.93 |
| ADR(5) | $1,172.89 = **1.82%** |
| **Noise floor (1.5 × ADR)** | **+2.74% → $66,072.02** |
| **Phase ceiling (Ch. B 1A)** | **+6.00% → $68,168.53** |
| Structure level (bounce high +1%) | $67,579.16 — **inside the band** |
| `ok` | **true** |

ADR(5) compressed from 2.46% to **1.82%**, the tightest realized range in the series, which widens the workable band further. **BTC again has the most tradeable stop geometry of the assets covered and the least reason to trade it.**

### Carry Cost Ledger

**Not applicable — no live tranche.** At a hypothetical 21-day Phase-1A hold, funding +5.97% annualized is **income** of ~+0.34%, floored to 0.00% for both the minimum-edge filter and the carry veto. Carry is **0.0%** of an 8% target gain; the veto does not fire and structurally cannot.

### Leverage / liquidation

**Not applicable — no live tranche.** All phase sizes are percentages of a dedicated short book assumed **unlevered**. A levered tranche would require a stated liquidation price, and one at or inside the stop distance is **prohibited**; the snapshot's `liquidation_price_usd` is always null and that is not permission to omit it.

### §6.5 Asset rotation screen

**Skipped, correctly** — §6.5's cohort precondition (within 10% of own 1-year ATH) is Channel A by construction. Noted for completeness: had the rejected 60.66% dominance figure been accepted, §2.5's wrong-asset row would have fired and *recommended* such a screen, but §6.5 would still have had nothing tradeable to return in Channel B.

---

## 7. Cover / Exit Framework

**No position. No triggers can fire.** All five Channel B preflight triggers PASS. Remaining position: **0%**.

Distance to the Channel B **100%-cover** regime tests:

| Cover trigger | Current | Distance |
|---|---|---|
| Weekly close reclaims the 200dma | $64,055.95 vs $70,508.25 | **−9.15%** |
| Bear Structure Integrity 0/3 | **2/3** | **two criteria** — comfortable |
| FK ≥12 force-cover | **11 adjusted / 11 mechanical** (same-day published) | **one point on both readings** |

**Hard Rule 5:** Channel B — level-based inverse consistency **not evaluable** (different scored objects, different horizons); the **FK ≥12 force-cover governs instead**, and does not fire.

---

## 8. Critical Watchlist

| Time (EST) | Event | Asset Impact | Short Implication |
|---|---|---|---|
| **Fri Aug 7** | **US Senate recess — CLARITY Act binary resolves by default at ~16%** | High | Failure is ~84% priced; a late passage would gap shorts. **Asymmetric against a short.** |
| Fri Aug 7 | Employment data after the soft ADP miss | Medium | A weak print extends the rate-cut bid → squeeze fuel |
| Ongoing | Hormuz / Iran draft peace framework | Medium-High | Further de-escalation compresses VIX; risk-on. **Against a short.** |
| Daily | **ETF flow prints** | **High** | The streak is at six sessions. Ten-plus would make the "absorption not froth" read in §9 harder to sustain — and would *raise* the score, which is exactly the tension I flag there |
| Rolling | **$63,549 — the 200-week SMA** | **High** | Spot is +1.20% above it. A weekly close beneath it is the first genuine structural crack in the bull case since the bounce began |
| Late Aug | Aug-28 Deribit expiry (21.4 days out, ATM IV 31.66%, skew +8.40%) | Medium | Heavy put positioning into the expiry; pinning risk |
| Rolling | **$57,747.77** — the Jul-1 40-session low | High | A break is a *fresh low*, not a stalling bounce. Principle 14: Channel B has no trade there |

---

## 9. Analyst Read

**The score went up and I trust it less than when it was lower.**

BTC's mechanical reading moved 6 → 7, and every point of that increment is one sub-criterion: §4B leg 5(c), the flow tell. The rubric asks whether ETF inflows are "resuming into the rally," reads six consecutive green sessions and $626M in three days, and credits it as evidence that **longs are re-crowding** — a distribution signature. I have scored it that way because that is what the rubric says and the durability lock is genuinely satisfied at six sessions. But I do not believe the interpretation, and here is why.

The criterion was written for *retail-flavoured* re-crowding — the kind that shows up alongside a funding spike, a rising long/short ratio, and a positioning book that gets liquidated on the way down. **None of that is present.** Funding is elevated at the 80th percentile but the Binance long/short account ratio is at the **3rd percentile** and falling: there are almost no longs to squeeze. What is buying is the ETF complex, at institutional scale, into a 49%-drawdown tape, while Coinbase Premium has been negative for a **record 78 consecutive days** and long-term-holder supply is at an **all-time high**. That is not a crowd piling into a bounce. That is float being absorbed by structurally sticky hands, at a price a long-term allocator considers cheap — which is precisely what the companion Fallen Knives framework is scoring 11 on.

**S1 discretionary term: −0.5, load-bearing on nothing, and taken anyway.** The specific factor: the rubric's flow criterion cannot distinguish *institutional absorption* from *retail re-crowding*, and the three corroborating positioning tells (long/short ratio at the 3rd percentile, record LTH supply, record negative Coinbase Premium streak) all point at absorption. The rubric sees the flow number and not its character. Direction: **against the short**. Step: 0.5, the minimum, because one report is a thin basis for overriding a leg. Under BTC's pinned half-up rounding, 7 − 0.5 = 6.5 → **7**, so the term changes nothing arithmetically — I take it regardless, because S7 grades the *claim*, and I would rather be on record disagreeing with a score increment than silently accept one I think is mis-signed. **Falsifier for this term:** if the inflow streak extends past ten sessions while the long/short ratio climbs above its 30-day median and funding pushes past the 90th percentile, the re-crowding reading becomes the better one and I retire the term.

Note the direction carefully: this term makes the short case **weaker**, which is always permissible and requires no special justification under Hard Rule 6. I have given the justification anyway because a future calibration should be able to grade whether the analyst channel added value, and it cannot grade a term whose reasoning was not written down.

**What the rubric is missing, in both directions.** Against the short: the whole on-chain supply picture. LTH supply at a record high and exchange reserves at multi-year lows are the *inverse* of §4A's Distribution leg, and Channel B does not score them at all — so a structurally tightening float is invisible to this report's number. Add the macro: VIX −11.35% in five sessions with Nasdaq +4.88% is a squeeze environment that §4B has no representation of. And the skew: a +8.40% moneyness skew means the options market has already paid up for the downside this framework would be positioning for. For the short: the 78-day record Coinbase Premium streak is real, sustained US institutional selling on the spot venue, and it does not appear in the Channel B gate set at all (it is Channel A's gate 6). ETH-side DeFi collapse and a −3.31% 90-day stablecoin drawdown are also genuinely bearish and unscored.

**The tension I have not resolved.** Record ETF inflows and a record negative Coinbase Premium streak are simultaneously true, and they are not obviously reconcilable — one says US institutions are buying hard, the other says US institutions are selling hard on the largest US spot venue. The most likely reconciliation is that they are *different* institutions, or the same ones expressing through the wrapper rather than the venue while unwinding direct spot. I do not know which, and I am flagging it rather than picking the reading that suits my stance. **If someone showed me that the ETF inflows are being hedged with offsetting spot sales — basis trade, not directional demand — my S1 term would flip sign immediately**, because that would make the flow genuinely non-informational rather than bullish.

**Which single input would change the verdict.** A 200dma rejection, same as ETH but further away: BTC would need to rally **~9.6%** into $70,508 and be turned back. That lights gate 5, plausibly takes Resistance Confluence to 3/4, and pushes Rally Extension into the >18% band — landing mechanical around **11–12**, which is *still short of 13*. **BTC needs the rejection AND a second independent improvement to reach a probe.** That was true on Jul-31 and it remains true; the distance has not closed.

**The strongest argument against my stance** is that BTC is 49% off its high, under a falling 200dma, with a bounce that topped 16 sessions ago and has made lower highs since, and with the cleanest bear structure of the two assets covered (2/3, and the bounce high *is* a lower high). If you were going to short a bear rally anywhere, the structure argues for here rather than ETH. The counter is threefold and I find it decisive: the rally never reached resistance (200dma 8.8% overhead, untouched), the exhaustion is 16 sessions stale, and price is sitting **on** the 200-week SMA at +1.20% — a level that has held every weekly close through the bounce. Shorting into a held long-cycle support with no overhead resistance nearby is the trade that fills at the trough. Principle 14 again.

**On the §5 grid.** I held the upside mode at 27% rather than cutting it as I did on ETH, because BTC's equivalent mode *did* print once (the Jul-21 $66,910 touch) even though it did not hold. The asymmetry is deliberate: I cut ETH's on three clean non-prints and held BTC's on a partial hit. Both decisions are recorded so a calibration can check whether I applied the same standard.

**Flagged for calibration, and this is the second report in a row it matters.** Gates 1 and 2 both changed status this report purely on measurement convention — rally measured to today's high vs the bounce high, and bounce age measured low-to-today vs low-to-bounce-high. That is a **two-gate swing** on an undeclared convention, and it is exactly the failure mode the 2026-08-05 non-crypto calibration formalized against when it froze the gate schema. The crypto path has the same hole. I have chosen the tool-consistent and harder-to-short readings both times and printed both, but a per-report judgment call on a protective threshold is precisely what a framework should not have.

### Discretion Ledger (S7)

| Date | Channel | S1 term | S2 used? | Load-bearing? | Reason | Outcome to date |
|---|---|---|---|---|---|---|
| 2026-07-31 | B | 0.0 | No | No | Rubric captured the tape | Stand down — correct (price +0.76% over 6 days) |
| **2026-08-06** | **B** | **−0.5** | **No** | **No** (adjusted 7 either way under half-up) | Flow criterion cannot distinguish institutional absorption from retail re-crowding; long/short ratio at the 3rd percentile, record LTH supply, and a record 78-day negative Coinbase Premium all argue absorption. **Falsifier: inflow streak >10 sessions with long/short ratio above its 30d median and funding >90th percentile → retire the term** | **Open** |

### Stop Migration Ledger

| Date | Tranche | Old stop | New stop | Direction | Trigger |
|---|---|---|---|---|---|
| — | — | — | — | — | **No tranche has ever filled on BTC under Flying Rocket. Ledger empty.** |

---

## 10. Bull vs Bear Scorecard (for BTC price — bull signals are bad for shorts)

**Bull (✅ — hostile to a short):**
1. ✅ **Six consecutive ETF inflow sessions, +$626M in three days**, reversing a record-low July
2. ✅ **LTH supply at an all-time high**; exchange reserves ~2.67M BTC at multi-year lows — float being removed
3. ✅ **Spot +1.20% above the 200-week SMA $63,549**, which has held every weekly close through the bounce
4. ✅ VIX 15.15, −11.35% in five sessions; SPX +3.66%, Nasdaq +4.88%
5. ✅ 50/200 gap narrowed −13.06% → −10.31% — downtrend structure repairing
6. ✅ Funding positive at the **80th percentile** with the long/short account ratio at the **3rd** — nobody to liquidate
7. ✅ Deribit skew **+8.40%** — the downside is already well hedged and paid for
8. ✅ HY OAS tightening, NFCI −0.529 loose, net liquidity $5.84T
9. ✅ ETF custodians hold ~1.3–1.5M BTC, 6.7–7.1% of supply — a structural bid
10. ✅ BTC dominance rising to 56.62% with ASI at 37 — capital rotating *into* BTC

**Bear (❌ — supportive of a short):**
1. ❌ −49.04% below the 1-year high; 200dma falling −3.78%/20 sessions; price −8.77% beneath it
2. ❌ Bounce high 16 sessions behind, and it **is** a lower high vs Jun-15 $67,248.13
3. ❌ **Coinbase Premium negative for 78 consecutive days — an all-time record streak.** US institutions net-selling the primary US spot venue
4. ❌ Daily RSI has faded 61.56 → 51.67 since the bounce high — momentum spent
5. ❌ No weekly close above the 200-day MA in 8 weeks
6. ❌ F&G 25.67 — no bid, and no euphoria to sell either
7. ❌ CLARITY Act at ~16% before recess — the regulatory catalyst disappointed
8. ❌ Stablecoin supply −3.31% over 90 days — capital-flow tell negative
9. ❌ MVRV-Z 0.3918 — no valuation extreme in either direction, offering no support level

---

## 11. Change Log

| Factor | Previous (Jul 31) | Current (Aug 6) | Direction | Short Impact |
|---|---|---|---|---|
| Spot | $63,825.45 | $64,309.93 | +0.76% | Neutral |
| Rally Extension leg | 2 (+13.03%) | 2 (+12.42%) | flat | Neutral — but 0.4% from dropping to 1 |
| Local Momentum leg | 1 (daily RSI 48.08) | 1 (51.67) | flat | Neutral |
| Resistance Confluence leg | 1 (1/4) | 1 (1/4) | flat | Neutral |
| Bear Structure leg | 2 (2/3) | 2 (2/3) | flat, (b) worse | Slightly negative — gap −11.27% → −10.31% |
| **Relative Sentiment leg** | **0** (0/3) | **1** (1/3) | ↑ | **Positive — the only leg that improved** |
| **Mechanical score** | **6** | **7** | ↑ 1 | Positive |
| **Discretionary** | 0.0 | **−0.5** | ↓ | Analyst disagreement with the leg above |
| Adjusted score | 6 | **7** | ↑ 1 | half-up: 6.5 → 7 |
| Gate board | 7/9 (prior convention) | **5/9** (tool convention; 7/9 on prior basis) | ↓ | Neutral — 1A/1B floors clear either way |
| **ETF flows** | **−$526.5M over 4 sessions; record-low +$205M July** | **+$626M over 3 sessions; six-session streak** | **reversed** | **Strongly negative for a short** |
| Coinbase Premium | −0.062, negative streak running | **−0.1145, 78 days — record** | ↑ streak | Positive for a short |
| MVRV-Z | 0.3833 | 0.3918 | flat | Neutral |
| ADR(5) | $1,567.16 = 2.46% | $1,172.89 = **1.82%** | ↓ | Positive — wider workable stop band |
| Funding annualized | +9.07% | +5.97% (80th pct) | ↓ | Slightly negative (less carry income) |
| Long/short acct ratio | 1.25 | **1.147 (3rd pct)** | ↓ | Negative — no longs to squeeze |
| VIX | 16.85 | **15.15** | ↓ 10% | Negative — risk-on |
| Correlation to SPX (sourced) | 0.331 | 0.256 | ↓ | Neutral — surcharge stays OFF |
| Position snapshot | FRESH (665 min) | **EXPIRED (7,605 min)** | ↓ | Cold start per Hard Rule 4 |

---

## 12. Strategic Verdict

**Mechanical 7 / 20 · Discretionary −0.5 · Adjusted 7 / 20** · EV_price **$63,741** · Total Short EV **+0.88% for gates / +1.22% true** against a **+3%** minimum-edge filter · F&G 3-day **25.67** · Channel **B** · **STAND DOWN — no BTC short.**

The one-point improvement in this score is an artefact of a criterion I think is mis-reading its own input, and I have said so with a discretionary term rather than quietly banking the increment. Six consecutive ETF inflow sessions and $626M in three days is scored by §4B as longs re-crowding into a bounce. But the Binance long/short account ratio is at the **3rd percentile** of its 30-day range, long-term-holder supply is at an **all-time high**, exchange reserves are at multi-year lows, and Coinbase Premium has been negative for a **record 78 days**. Every one of those says float is being absorbed by hands that do not sell into weakness. A distribution top has crowded longs paying elevated funding; this tape has *nobody* long, paying elevated funding. Those are opposite conditions and only one of them is shortable.

Set the flow argument aside entirely and the geometry still refuses the trade. BTC's bounce topped **sixteen sessions ago** at $66,910.06 — a genuine lower high against Jun-15's $67,248.13, and the cleanest bear structure in this pair — but it never reached resistance. The 200dma is **8.8% overhead and was never touched** in 36 sessions. The 50dma is *below* price. And spot is sitting **+1.20% above the 200-week SMA at $63,549**, a level that has held every weekly close through the entire bounce. A short here has no overhead level to lean its stop against and a major long-cycle support directly underneath it. That is the configuration Principle 14 was written after: short the exhausted rally into resistance, never the drift in the middle of the range. The gates say the same thing more bluntly — gate 5 fails because the 200dma was never reached, and gates 1 and 2 fail because the rally is too small and too old.

What I would say to someone who wanted to be short BTC anyway: you may well be right about direction. A 49% drawdown under a falling 200dma with a record-negative Coinbase Premium is not a bull market, and the CLARITY Act just disappointed. But the framework's job is not to have a view — it is to determine whether *this entry, at this price, with this stop and this clock* has positive expectancy, and the answer is +0.88%, which fails the minimum-edge filter by more than two points. The carry is income and cannot rescue it; the zero-floor sees to that. Meanwhile VIX has fallen 11% in five sessions, equities are melting up, and the ETF complex is bidding $200M+ a day. **Shorts have a clock and longs have time.** There is nothing here worth putting a clock on, and there will not be until either the market rallies into $70,500 and is rejected, or the 200-week SMA gives way.

**Action items:**

1. **Stand down. No BTC short.** Adjusted 7 against a Channel B Phase-1A line of 13, missed by six. All phases DRY POWDER.
2. **Do not read the score increment as progress.** It came entirely from a flow criterion I have disagreed with on the record; the S1 term and its falsifier are logged in S7 for grading.
3. **Watch $63,549, the 200-week SMA.** Spot is +1.20% above it and it has held every weekly close of the bounce. **A weekly close beneath it is the first genuine structural crack** and would be more informative than any single leg in this rubric.
4. **Watch the ETF streak.** Past ten sessions with a rising long/short ratio and funding above the 90th percentile, my S1 term retires and the re-crowding reading becomes the better one — which would *raise* the score honestly rather than artificially.
5. **Re-run on a 200dma rejection, not on a decline.** BTC must rally ~9.6% into $70,500 and be turned away — and even then mechanical lands around 11–12, still short of 13. **BTC needs that rejection AND a second independent improvement** to reach a probe. That has not changed since Jul-31.
6. **Refresh the position ledger.** The snapshot is 5.3 days expired. The BTC deal-layer defect documented on Jul-31 (two open long deals claiming ~0.0199 BTC against a reconciled balance of 0.00000184) could not be re-asserted here and should be fixed rather than re-observed.
7. **Flag for the next calibration, second consecutive report:** gates 1 and 2 swung status purely on an undeclared measurement convention — a **two-gate** swing on a protective threshold. The 2026-08-05 calibration froze the non-crypto gate schema for exactly this reason; the crypto path has the same hole.

> **The Pattern**
>
> **IF** BTC rallies into **$69,000–70,500** and is rejected at the falling 200dma on a daily close back below $66,910 → **THEN** gate 5 lights, Resistance Confluence plausibly moves 1/4 → 3/4, Rally Extension enters the >18% band, and mechanical lands near **11–12** — *still short* of Channel B's Phase-1A line of 13. BTC requires that rejection **and** a second independent improvement (a funding flip, or local greed in F&G) to reach a probe. *(Falsifier: a **weekly close above the 200dma at $70,508.25**, which voids Channel B outright and force-covers 100%.)*
>
> **IF** BTC prints a **weekly close below the 200-week SMA at $63,549.42** → **THEN** the strongest structural support of this entire bounce has failed, the 50dma at $63,239 goes with it, and the "50dma lost, July-low retest" mode at 24% becomes the modal path rather than the third one. That is the single event that would most change this report, and it is worth more than any score movement. *(Falsifier: two consecutive weekly closes above $65,000, which would confirm the 200-week SMA as accepted support rather than a level being tested.)*
>
> **IF** the ETF inflow streak extends past **ten sessions** while the Binance long/short ratio climbs above its 30-day median and funding pushes past its 90th percentile → **THEN** the re-crowding reading beats my absorption reading, the S1 term retires, and the Relative Sentiment leg's credit is earned rather than assumed. *(Falsifier for the term: exactly the above. It is dated to the next report on this asset.)*

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FR-B-1A-BTC-20260806-1844 | STAND_DOWN | crypto |
| 1B | FR-B-1B-BTC-20260806-1844 | STAND_DOWN | crypto |
| 2 | FR-B-2-BTC-20260806-1844 | STAND_DOWN | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_flying_rocket_20260806_1844.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "flying_rocket",
  "asset": "BTC",
  "date": "2026-08-06",
  "spot": { "value": 64309.93, "source": "median of 3 synchronized live venue quotes: Binance BTCUSDT $64,364.10 / Coinbase BTC-USD $64,309.93 / Kraken XBTUSD $64,308.90 (Aug-6 22:36 UTC); spread 0.086%; Yahoo daily close excluded as a frozen bar" },
  "channel": "B",
  "regime": {
    "pct_below_1y_ath": 49.04,
    "ma200_falling": true,
    "price_below_ma200": true
  },
  "score": {
    "legs": {
      "euphoria": 2,
      "momentum": 1,
      "valuation": 1,
      "distribution": 2,
      "vulnerability": 1
    },
    "penalty": 0,
    "discretionary": -0.5,
    "mechanical": 7,
    "raw": 6.5,
    "adjusted": 7,
    "rounding": "half-up"
  },
  "gates": {
    "active": 9,
    "na": [],
    "passed": [3, 4, 6, 7, 8]
  },
  "ev": {
    "scenarios": [
      { "name": "Bounce resumes toward the 200dma", "p": 27, "low": 66900, "high": 70500 },
      { "name": "Range around the 50dma / 200-week SMA", "p": 39, "low": 62500, "high": 66900 },
      { "name": "50dma lost, July-low retest", "p": 24, "low": 57700, "high": 62500 },
      { "name": "Fresh cycle lows", "p": 10, "low": 53000, "high": 57700 }
    ],
    "stated_ev": 63741,
    "vs_spot_pct": -0.88
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": []
  },
  "verdict": "STAND DOWN -- no BTC short. Channel B live (-49.04% off the 1y high $126,198.07, 200dma $70,508.25 falling -3.78%/20 sessions, price -8.77% beneath it), second consecutive report. Mechanical 7/20 (UP 1 from Jul-31's 6), discretionary -0.5, adjusted 7/20 under half-up rounding (6.5 -> 7) against a Channel B Phase-1A line of 13 -- SHORT BY 6. THE ENTIRE SCORE INCREMENT IS ONE SUB-CRITERION AND I HAVE TAKEN A DISCRETIONARY TERM AGAINST IT: leg 5(c), the ETF flow tell, went 0/3 -> 1/3 on SIX consecutive inflow sessions and +$626M in three days (Aug-3 +$170.1M, Aug-4 +$211.5M, Aug-5 +$244.4M, per six independent secondary reports of the Farside table -- farside.co.uk returned HTTP 403 and CoinGlass/TheBlock render client-side). Principle 13's durability lock is SATISFIED at 6 >= 5 sessions, so the leg is scored honestly; the objection is booked as S1 rather than by refusing the leg. Legs on the FR-B/1 rubric (leg keys are Channel A names carrying Channel B meanings): rally extension 2 (+12.42% to today's session high $64,922.95, band >12%; +15.87% to the Jul-21 bounce high $66,910.06 -- SAME band on both readings, no ambiguity, but note +12.42% sits 0.4% above the band floor), local exhaustion 1 (daily RSI 51.67 band >45; weekly RSI 38.84 <50 so the hard qualifier passes; the daily RSI was 61.56 at the bounce high SIXTEEN sessions ago -- the exhaustion is stale), resistance confluence 1 (1/4 -- only the Jul-21 lower-high shelf; the 200dma is 8.77% OVERHEAD and was NEVER touched in 36 sessions, and price is +1.71% ABOVE the 50dma $63,239.31 so criterion (b) fails on the 'from below' clause -- a loose reading would credit it and move the leg to 3 and the score to 9, still four short of 13), bear structure 2 (2/3 -- bounce high $66,910.06 IS a lower high vs the Jun-15 pivot $67,248.13, and no weekly close above the 200dma in 8 weeks; but the 50/200 gap NARROWED from -13.06% to -10.31%, faster than last report), relative sentiment 1 (1/3 -- F&G 3d 25.67 vs a ~39.0 threshold fails; no funding flip because the deepest interval is -0.38% annualized with no negative run to flip from; the flow tell PASSES on the six-session streak). Squeeze-trap penalty NOT active: sustained3_below_minus5 false, deepest interval -0.38% annualized, no single print below -7%, tier none. Bounce-maturity floor 0 (36 sessions from the low, >=8). No cap. Correlation SOURCED 0.256 (30 daily log-return pairs vs SPX, Jul-8 to Aug-6) <0.70 -> risk-on surcharge OFF on a measured number. GATES 5/9 (passed 3,4,6,7,8; failed 1 rally, 2 bounce age, 5 the 200dma was never reached, 9 no local greed and no funding flip), no N/A, denominator 9, floors ceil(3/9x9)=3 [legacy 3] / ceil(5/9x9)=5 [legacy 5] / ceil(6/9x9)=6 [legacy 6], Phase 3 unreachable in Channel B. TWO GATE MEASUREMENTS CHANGED AGAINST THE PRIOR REPORT, BOTH DISCLOSED: gate 1 was credited on Jul-31 via the +15.87% bounce-high reading and is now scored on today's-high (+12.42%, fails the >=15% bar) for consistency with how the rally leg is measured in the same report and per the Hard-Rule-6 harder-to-short convention; gate 2 was credited on Jul-31 via low-to-bounce-high (20) and is now scored on tools/fetch.mjs's own bounce_age_sessions field, low-to-today (36, past the <=35 bar). Under the prior conventions the board reads 7/9; under the tool-consistent ones, 5/9. Both clear the 1A floor of 3 and the 1B floor of 5. VERDICT IDENTICAL EITHER WAY -- no gate board rescues a score. FLAGGED FOR CALIBRATION, SECOND CONSECUTIVE REPORT: this is a TWO-GATE swing on an undeclared measurement convention applied to a protective threshold, the same failure mode the 2026-08-05 calibration froze the non-crypto gate schema against; the crypto path has the same hole. Gate 8 (funding veto, the only true veto in either framework) emphatically GREEN -- funding at the 80th PERCENTILE of its own 167-day history. S1 unavailable in effect (max +2.0 reaches 9, four short; the term actually taken is NEGATIVE). S2 structurally unavailable (score line missed by 6 when S2 requires it MET, and gate count short by FOUR rather than exactly one). S1 TERM -0.5, NOT load-bearing (adjusted is 7 either way under half-up), taken deliberately: the flow criterion cannot distinguish INSTITUTIONAL ABSORPTION from RETAIL RE-CROWDING, and three corroborating positioning tells all point at absorption -- the Binance long/short account ratio is at the 3rd PERCENTILE and falling (there are almost no longs to squeeze) while funding sits at the 80th, LTH supply is at an ALL-TIME HIGH with exchange reserves at multi-year lows (~2.67M BTC), and Coinbase Premium has been negative for a RECORD 78 CONSECUTIVE DAYS since May 19 (-0.1145%). A distribution top has crowded longs paying elevated funding; this tape has nobody long, paying elevated funding. TERM FALSIFIER, DATED TO THE NEXT REPORT: if the inflow streak extends past TEN sessions while the long/short ratio climbs above its 30-day median and funding pushes past the 90th percentile, the re-crowding reading wins and the term retires. UNRESOLVED TENSION FLAGGED RATHER THAN PAPERED OVER: record ETF inflows and a record-negative Coinbase Premium streak are simultaneously true and not obviously reconcilable; if the inflows prove to be basis-trade hedged against offsetting spot sales, the S1 term flips sign. Total Short EV +0.88% floored / +1.22% true (EV_price $63,741 vs spot $64,309.93, directional +0.88%, carry +0.34% true floored to 0.00% for gating at +5.97% annualized funding over 21d) -- FAILS the +3% minimum-edge filter by 2.12 points. Carry is 0.0% of an 8% target so the 40% carry veto does not fire and structurally cannot, income being floored. EV is LOAD-BEARING, not corroborative-only: Channel B has no cap and the gate board clears the 1A and 1B floors. Collar ON (>20% off the 1y high in Channel B -- always on there -- and |EV|<3%). SECTION 5 BASELINE-GRID DEPARTURE TAKEN under the 2026-07-27 analyst-override provision; the upside mode was HELD at 27% rather than cut, unlike the ETH companion which was cut 32% -> 26%, because BTC's equivalent mode DID print once (the Jul-21 $66,910.06 touch) though it did not hold -- the asymmetry is deliberate and recorded so a calibration can check the standard was applied consistently. Stop band computed though nothing fills: fill $64,309.93, ADR(5) $1,172.89 = 1.82% (COMPRESSED from 2.46% on Jul-31, the tightest in the series), noise floor 1.5xADR = +2.74% -> $66,072.02, Channel B 1A ceiling +6% -> $68,168.53, structure level (bounce high +1%) $67,579.16 sits INSIDE the band -- frStopBand ok:true. BTC again has the most tradeable stop geometry and the least reason to trade it. Section 7 Channel B preflight PASSES clean on all five triggers with no borderline WARNING, and more comfortably than ETH on two axes (bear structure 2/3 vs 1/3, funding 80th percentile vs 61st). Cross-check: FK computed 11/20 MECHANICAL from the same fetch (legs 2/2/4/0/3 -- sentiment F&G 3d 25.67 -> 2, momentum weekly RSI 38.84 -> 2, valuation MVRV-Z 0.3918 -> 4, capitulation 0, holder 3), 3/9 gates, vs the PUBLISHED SAME-TRADING-DAY FK (btc_fallen_knives_20260806_1836.md) at 11 mechanical / 11 adjusted, 3/9 gates -- the published legs (2/2/4/0/3) and gate board match this report's independently computed companion EXACTLY, so there is no strictest-wins conflict and no divergence to reconcile next report. Both readings <12, force-cover does NOT fire, though it now sits ONE POINT away on BOTH the mechanical and adjusted axes rather than only the mechanical. Hard Rule 5: Channel B -- level-based inverse consistency not evaluable (different scored objects on different horizons); FK >=12 force-cover governs instead. SECTION 2.5 WRONG-ASSET ROW CHECKED IN DETAIL AND DOES NOT FIRE, ON A RESOLVED DATA CONFLICT: the row needs dominance >55% AND a 30d uptrend AND a MULTI-MONTH RANGE BREAKOUT AND ASI <40. Live dominance is 56.617% (CoinGecko /global primary, Aug-6 22:35 UTC), up from ~56.3% on Jul-31, with ASI 37 -- three conjuncts hold but 56.6% is INSIDE the range BTC dominance has occupied for months, so the BREAKOUT conjunct FAILS. Two secondary outlets (BeInCrypto, AInvest) reported dominance at 60.3-60.66% this week, and AT 60.66% THE BREAKOUT CONJUNCT WOULD ARGUABLY HOLD AND THE ROW WOULD FIRE, mandating a section 6.5 lagging-alt screen before any BTC short. Those figures are REJECTED: the CoinGecko primary reads 56.617% and CoinGabbar's Aug-6 daily wrap independently prints 56.5%, two independent sources against two secondaries, and the primary governs per the Data Source Priority table (the 60%+ variant most likely excludes stablecoins from the denominator). Recorded explicitly so a calibration can grade the call; the STAND DOWN verdict is unchanged either way because the score fails by six. POSITION (Hard Rule 8): EXPIRED at 7,605 minutes (5.28 days), driver holdings_as_of, against a 4,320-minute expiry -- exit 1. COLD START PER HARD RULE 4, STATED EXPLICITLY. No quantity, cost basis, PnL, custody status or dry-powder figure is carried forward; the Jul-31 BTC readings (dust quantity, basis.reliable false on 5 unbacked disposals, two UNTAGGED open long deals claiming ~0.0199 BTC against a reconciled 0.00000184) were FRESH then and are NOT evidence now -- the data defect presumably persists but this report cannot assert it. Per-channel realized performance unreadable this run; as of the last FRESH read FR-A- and FR-B- were both empty -- N=0 on realized money. Section 6.5 rotation screen correctly SKIPPED (its within-10%-of-own-ATH cohort precondition is Channel A by construction). Prior Jul-31 FR graded: EV_price $63,419 HELD (realized $64,309.93, +1.40% above); modal band 'Range around the 50dma / 200-week' 38% $62,000-66,000 HIT OUTRIGHT (all seven daily closes inside it) while the 28% 'Bounce resumes' mode never printed; the Jul-16 'held trend-repair rally' falsifier is upgraded from PARTIAL FIRE ON ONE LIMB to PARTIAL FIRE ON BOTH -- the weekly-close limb holds and extended (spot +1.20% above the 200-WEEK SMA $63,549.42) and the ETF limb has now REVERSED from -$526.5M/4 sessions to six consecutive inflow sessions, leaving only the 200-DAY structure refuting it. Trailing EV calibration n=3 (+0.2%, -2.41%, +1.40%), mean signed error -0.27%. THE JUL-31 SAME-SIGN BIAS FLAG IS RETIRED: that report flagged three consecutive same-signed errors as a candidate downward bias and DECLINED to correct it because correcting a forecast downward strengthens a short case and Hard Rule 6 forbids that at n=3; the next observation came in +1.40%, the opposite sign, so the apparent bias was NOISE and the discipline that stopped the tune was correct. Realistic re-arm distance restated and UNCHANGED from Jul-31: a 200dma rejection at $69,000-70,500 (a ~9.6% rally away) takes mechanical 7 -> ~11-12, STILL SHORT of 13; BTC needs the rejection AND a second independent improvement to reach a probe. The single most informative level is the 200-WEEK SMA at $63,549.42, +1.20% beneath spot, which has held every weekly close through the bounce -- a weekly close below it is the first genuine structural crack and would matter more than any score movement. All phases DRY POWDER; 0% of the 50% book, 0% of the 30% per-asset cap, 0% of the 20% analyst-channel cap.",
  "inputs": {
    "weekly_rsi": 38.84,
    "weekly_rsi_incl_live": 40.24,
    "weekly_rsi_last_completed_week": "2026-07-27",
    "weekly_rsi_proximity_note": "1.16 RSI points below the FK momentum band edge at 40 -- the nearest boundary in the whole dataset per fetch.mjs proximity block; both readings are far below FR-B's hard qualifier of 50, so nothing scored moves",
    "daily_rsi": 51.67,
    "daily_rsi_at_bounce_high": 61.56,
    "rsi_closes": 261,
    "mvrv_z": 0.3918,
    "mvrv_z_source": "bitcoin-data.com (BGeometrics) API, 2026-08-05; cross-checked 0.37 on Aug-4 per AhaSignals",
    "mvrv_ratio": 1.2359,
    "fng_spot": 25,
    "fng_3d": 25.67,
    "fng_30d_mean_approx": 26.0,
    "fng_leg_threshold_approx": 39.0,
    "fng_percentile_vs_2y": 27.02,
    "drawdown_pct_1y": 49.04,
    "high_1y": 126198.07,
    "high_1y_date": "2025-10-06",
    "ma200": 70508.25,
    "ma200_slope_20d_pct": -3.78,
    "ma50": 63239.31,
    "pct_vs_ma200": -8.77,
    "pct_vs_ma50": 1.71,
    "ma50_200_gap_pct_now": -10.31,
    "ma50_200_gap_pct_20ago": -13.06,
    "ma50_200_gap_narrowed": true,
    "sma_200w": 63549.42,
    "pct_vs_sma_200w": 1.20,
    "low_40_session": 57747.77,
    "low_40_session_date": "2026-07-01",
    "bounce_high": 66910.06,
    "bounce_high_date": "2026-07-21",
    "sessions_since_bounce_high": 16,
    "prior_swing_high_pre_low": 67248.13,
    "prior_swing_high_pre_low_date": "2026-06-15",
    "bounce_is_lower_high": true,
    "bounce_age_sessions": 36,
    "sessions_low_to_bounce_high": 20,
    "rally_pct_to_current_high": 12.42,
    "rally_pct_to_bounce_high": 15.87,
    "current_session_high": 64922.95,
    "adr5": 1172.89,
    "adr5_pct": 1.82,
    "last_weekly_close": 64055.95,
    "stall_confirmation": true,
    "stall_lower_close": true,
    "stall_lower_high": true,
    "funding_per8h_mean_pct": 0.01,
    "funding_ann_pct": 5.97,
    "funding_intervals_sampled": 45,
    "funding_sessions_sampled": 15,
    "funding_longest_negative_run_intervals": 0,
    "funding_sustained3_below_minus5": false,
    "funding_min_interval_ann_pct": -0.38,
    "funding_single_interval_below_minus7": false,
    "funding_percentile_vs_history": 80.24,
    "perp_basis_pct": -0.05,
    "long_short_acct_ratio": 1.1468,
    "long_short_acct_ratio_percentile": 3.45,
    "taker_buy_sell_ratio": 1.0676,
    "oi_percentile_vs_30d": 75.86,
    "oi_within_5pct_of_90d_high": null,
    "borrow_annualized_pct": 0.0012,
    "deribit_atm_iv_pct": 31.66,
    "deribit_dvol": 34.86,
    "deribit_skew_90_110_moneyness_pct": 8.40,
    "deribit_skew_legs": { "put_strike": 58000, "call_strike": 71000 },
    "deribit_rv30_pct": 29.20,
    "deribit_vrp_pct": 2.46,
    "coinbase_premium_index": -0.1145,
    "coinbase_premium_negative_streak_days": 78,
    "coinbase_premium_streak_start": "2026-05-19",
    "lth_supply_status": "all-time high",
    "exchange_reserves_btc_m": 2.67,
    "etf_custodial_holdings_btc_m": 1.4,
    "corr_spx_30d": 0.256,
    "corr_pairs": 30,
    "corr_window": "2026-07-08 to 2026-08-06",
    "spx": 7709.96,
    "spx_pct_below_6mo_high": -0.34,
    "spx_delta_5_sessions_pct": 3.66,
    "ndx_delta_5_sessions_pct": 4.88,
    "vix": 15.15,
    "vix_delta_5_sessions_pct": -11.35,
    "dxy": 99.96,
    "us10y": 4.67,
    "real_yield_10y": 2.41,
    "hy_oas": 2.75,
    "nfci": -0.529,
    "net_liquidity_usd_t": 5.84,
    "stablecoin_supply_usd_b": 183.39,
    "stablecoin_supply_30d_pct": -0.44,
    "stablecoin_supply_90d_pct": -3.31,
    "btc_dominance_pct": 56.617,
    "btc_dominance_source": "CoinGecko /global, 2026-08-06 22:35 UTC",
    "btc_dominance_prior_report": 56.3,
    "btc_dominance_conflicting_secondary_reports_rejected": [60.3, 60.66],
    "altcoin_season_index": 37,
    "eth_dominance_pct": 10.07,
    "btc_etf_flows_usd_m": { "aug_3": 170.1, "aug_4": 211.5, "aug_5": 244.4, "three_day_total": 626 },
    "btc_etf_inflow_streak_sessions": 6,
    "btc_etf_durability_lock_satisfied": true,
    "btc_etf_july_mtd_usd_m_prior": 205,
    "btc_etf_prior_4session_outflow_usd_m": -526.5,
    "clarity_act_pass_odds_pct": 16,
    "companion_fk": {
      "computed_mechanical": 11,
      "gates": 3,
      "gates_active": 9,
      "published_mechanical": 11,
      "published_adjusted": 11,
      "published_date": "2026-08-06",
      "published_report": "btc_fallen_knives_20260806_1836.md",
      "published_is_same_trading_day": true,
      "force_cover_fires": false,
      "legs": { "sentiment": 2, "momentum": 2, "valuation": 4, "capitulation": 0, "holder": 3 },
      "agreement_note": "the same-day published FK legs (2/2/4/0/3) and gate board (3/9) match this report's independently computed companion EXACTLY -- no strictest-wins conflict, no divergence to reconcile next report"
    },
    "position": {
      "band": "EXPIRED",
      "age_min": 7605,
      "age_driver": "holdings_as_of",
      "expired_after_min": 4320,
      "exit_code": 1,
      "treatment": "cold start per Hard Rule 4, stated explicitly; no quantity, basis, PnL, custody or dry-powder figure carried forward"
    },
    "preflight_channel_b": {
      "fk_ge_12": false,
      "funding_negative_3i": false,
      "upside_narrative_break": false,
      "weekly_close_reclaimed_ma200": false,
      "bear_structure_0_of_3": false,
      "result": "PASS"
    },
    "stop_band_informational": {
      "fill": 64309.93,
      "floor": 66072.02,
      "floor_pct": 2.74,
      "ceiling": 68168.53,
      "ceiling_pct": 6.00,
      "structure_level": 67579.16,
      "ok": true
    },
    "s1_term": {
      "value": -0.5,
      "load_bearing": false,
      "direction": "against the short",
      "factor": "the section 4B flow criterion cannot distinguish institutional absorption from retail re-crowding; long/short account ratio at the 3rd percentile, LTH supply at an all-time high, and a record 78-day negative Coinbase Premium streak all argue absorption",
      "falsifier": "inflow streak >10 sessions WITH long/short ratio above its 30-day median AND funding above the 90th percentile -> the re-crowding reading wins and the term retires. Dated to the next BTC FR report.",
      "arithmetic_note": "half-up rounding makes 7 - 0.5 = 6.5 -> 7, so the term changes no number; taken anyway so S7 can grade the claim"
    },
    "prior_fr_grade": {
      "report": "btc_flying_rocket_20260731_0426.md",
      "ev_price_prior": 63419,
      "ev_price_grade": "HELD (realized $64,309.93, +1.40% above)",
      "modal_band_prior": "Range around the 50dma / 200-week 38%, $62,000-66,000",
      "modal_grade": "HIT -- all seven daily closes inside the band; the 28% 'Bounce resumes' mode never printed",
      "falsifier": "a held trend-repair rally (from the Jul-16 report)",
      "falsifier_grade": "PARTIAL FIRE, UPGRADED FROM ONE LIMB TO BOTH -- the weekly-close limb holds and extended (spot +1.20% above the 200-week SMA $63,549.42), the ETF limb has REVERSED from -$526.5M/4 sessions and a record-low July to six consecutive inflow sessions and +$626M/3 days; only the 200-DAY structure still refutes it (price -8.77% below a falling 200dma)",
      "ev_calibration_n3_signed_errors": [0.2, -2.41, 1.40],
      "ev_calibration_mean": -0.27,
      "ev_calibration_bias_flag_retired": "Jul-31 flagged three consecutive same-signed errors (-1.1, +0.2, -2.41) as a candidate downward bias and DECLINED to correct it because correcting downward strengthens a short case and Hard Rule 6 forbids that at n=3. The next observation came in +1.40%, the OPPOSITE sign. The apparent bias was noise; declining to tune was correct.",
      "repeated_forecast_miss": "three consecutive reports assigned 27-45% to a 'bounce resumes toward the 200dma' mode; it has printed ONCE and partially (the Jul-21 $66,910.06 touch) and never held. Held at 27% rather than cut, unlike ETH's, because BTC's did at least reach its band floor."
    },
    "recheck_triggers": {
      "fired": [],
      "not_fired": ["6.6(b) -- the August liquidation tape has been small and two-sided, with no >=$100M shorts-dominated day and no BTC-led squeeze"],
      "note": "user-requested run; the full 2.5/4B stack was re-run from scratch"
    },
    "methodology_changes": [
      "gate 1 rally now measured to today's session high (+12.42%, FAILS the >=15% bar) rather than to the bounce high (+15.87%, passes) as credited on Jul-31; chosen for consistency with the rally-extension leg's own measurement in the same report and as the harder-to-short reading",
      "gate 2 bounce age now measured low-to-today (36, tools/fetch.mjs bounce_age_sessions) rather than low-to-bounce-high (20) as on Jul-31; board reads 7/9 under the prior conventions and 5/9 under these; verdict identical",
      "section 5 baseline grid replaced with four Channel-B-appropriate modes under the 2026-07-27 analyst-override provision; upside mode HELD at 27% (not cut as in the ETH companion) because it printed once partially"
    ]
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_flying_rocket_20260806_1844.md",
      "report_version": "report-machine/1",
      "framework": "flying_rocket",
      "channel": "B",
      "asset": "BTC",
      "report_date": "2026-08-06",
      "report_local_time": "18:44",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FR-B-1A-BTC-20260806-1844",
          "decision": "STAND_DOWN",
          "instrument_class": "crypto",
          "report_file": "btc_flying_rocket_20260806_1844.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:44"
        },
        {
          "phase": "1B",
          "canonical_tag": "FR-B-1B-BTC-20260806-1844",
          "decision": "STAND_DOWN",
          "instrument_class": "crypto",
          "report_file": "btc_flying_rocket_20260806_1844.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:44"
        },
        {
          "phase": "2",
          "canonical_tag": "FR-B-2-BTC-20260806-1844",
          "decision": "STAND_DOWN",
          "instrument_class": "crypto",
          "report_file": "btc_flying_rocket_20260806_1844.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-06",
          "report_local_time": "18:44"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "btc_flying_rocket_20260806_1844.md",
    "report_version": "report-machine/1",
    "framework": "flying_rocket",
    "channel": "B",
    "report_asset": "BTC",
    "report_date": "2026-08-06",
    "report_local_time": "18:44",
    "active_tags": [],
    "reserved_tags": [
      "FR-B-1A-BTC-20260806-1844",
      "FR-B-1B-BTC-20260806-1844",
      "FR-B-2-BTC-20260806-1844"
    ],
    "status": "REGISTERED"
  }
}
```
