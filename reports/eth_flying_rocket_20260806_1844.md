# 🚀 FLYING ROCKET ANALYTICS — ETH — August 6, 2026

## THE RALLY AGED PAST ITS OWN GATE WINDOW — SCORE FALLS 9 → 7, STAND DOWN BY SIX

### Report Generated: Thursday, August 6, 2026, 6:44 PM EDT
### Channel: **B — Bear Continuation** (−61.59% off 1y high · 200dma falling −5.82%/20 sessions · price −7.89% below it)
### Asset: ETH | Prior Score: 9 / 20 (2026-07-31, Channel B) | Mechanical: **7 / 20** | Discretionary: **0.0** | Adjusted: **7 / 20**
### Cross-Check: Fallen Knives (ETH, same fetch): **11 / 20 mechanical computed, 2/8 gates** · published Aug-5: **11 mechanical / 10 adjusted, 2/8 gates** — both **<12**, force-cover does **NOT** fire

> **Read this first.** The setup did not improve — it decayed, and it decayed in the specific way Channel B is built to detect. ETH's bounce high is now **ten sessions** behind us at $1,976.46 and the low it sprang from has **rolled out of the trailing 40-session window**, so the measured rally shortens from 28.0% to **23.7%** and the rally-extension leg drops 4 → 3. The bounce is now **39 sessions old measured from the low** — past the upper edge of gate 2's own 8-to-35-session maturity window. Mechanical score **7**, six points short of Channel B's Phase-1A line of 13. This is the framework's modal and correct output.

---

## 0. Prior FR Forecast Check & Falsifier Status (mandatory)

Grading `reports/eth_flying_rocket_20260731_0426.md`, the last published ETH FR:

| Prior (Jul 31) claim | Realized (Jul 31 → Aug 6) | Grade |
|---|---|---|
| **EV_price = $1,880.90** | Spot **$1,902.68** — realized **+1.16% ABOVE** the prior EV_price | **HELD** (inside the corroborative tolerance; the call under-shot by ~1.2%) |
| **Modal band: "Range grinds" 36%, $1,820–1,960** | Every daily close in the window sat inside it: 1,860.35 / 1,843.42 / 1,882.52 / 1,858.26 / 1,868.39 / 1,906.53 / 1,902.68 | **HIT** — the modal band contained the entire path, and the 32% "Bounce resumes $1,960–2,130" mode never printed |
| **(Falsifier: a weekly close ABOVE the 200dma, which voids Channel B outright)** | Last completed weekly close $1,858.26 (week of Jul-27) vs 200dma $2,066.88 — **−10.10% below** | **STANDING** |
| **(Falsifier: a held close below $1,757 followed by a lower high beneath it)** | Lowest close in the window $1,843.42; ETH never approached $1,757 | **STANDING** |
| **(Falsifier: none — FK ≥12 makes Channel B unavailable; stated as a rule, not a forecast)** | FK computed **11 mechanical**, published Aug-5 **10 adjusted / 11 mechanical** — both below 12 | **STANDING (rule, unfired)** |

**Trailing EV calibration (n=3, the last three published ETH FR EVs):** Jul-16 over-called **−3.22%**; Jul-28 under-called **+0.68%**; Jul-31 under-called **+1.16%**. Mean signed error **−0.46%**, errors straddling zero. No directional bias worth correcting, and none of these three would have changed a verdict.

**A note on the first mode's persistent failure, because it matters more than the EV.** Three consecutive ETH FR reports have put 26–38% of probability mass on a "bounce resumes / 200dma test" scenario, and it has **not printed once**. The 200dma has been 7–11% overhead for the whole series and price has never closed within 6% of it. That is a real, repeated forecast miss in the direction that *helps* the short case, and the honest reading is that this framework has been persistently over-weighting an upside resolution that the tape keeps declining to deliver. I have trimmed that mode to **26%** below and I am flagging the pattern here rather than letting a fourth report quietly repeat it. Note the direction of the correction: trimming an upside mode makes the short case look *better*, which is exactly the kind of adjustment Hard Rule 6 requires me to justify rather than assert — the justification is three consecutive non-prints against a falling 200dma, not a view.

**Re-check triggers:** none of §6.6(a)–(e) fired since Jul-31. The FK companion is 10–11, below the ≥9 inline threshold's re-run relevance (it has been ≥9 for the whole series and is graded here); no shorts-dominated liquidation day occurred; no named falsifier moved materially closer; ETH was already in the Channel B regime. **This is a user-requested run.** Grading does not re-arm a short — the full §2.5/§4B stack was re-run from scratch below.

---

## 1. Channel Determination (§2.5)

| Test | Measurement | Source |
|---|---|---|
| >20% below 1-year high | **−61.59%** (spot $1,902.68 vs $4,953.73, 2025-08-18) | `tools/fetch.mjs eth`, Yahoo ETH-USD trailing-1y weekly highs, Aug-6 22:36 UTC |
| 200dma falling over trailing 20 sessions | **−5.82%** ($2,066.88 today) | computed, Yahoo ETH-USD 2y daily |
| Price below the 200dma | **−7.89%** | derived |

All three hold → **Channel B — Bear Continuation is LIVE**, third consecutive report. Channel A is not scored: at a 62% drawdown its rubric returns a false negative, not a finding.

**Other §2.5 rows, checked:**

- **Smaller-alt row:** N/A. ETH is top-2 by mcap with deep borrow (Bitfinex fETH annualized 0.38%, ask size 6,092 ETH — a real book, not a thin quote).
- **Altseason squeeze-trap row (alt short during falling dominance):** **does NOT fire.** It requires BTC dominance *falling* with the Altcoin Season Index **>75**. Live readings are the opposite: dominance **56.62%** and *rising*, ASI **37**. No +3-gate override is levied.
- **Source conflict, disclosed:** two secondary outlets reported BTC dominance at **60.3–60.66%** this week ([BeInCrypto](https://beincrypto.com/bitcoin-dominance-explodes-to-60-66-and-buries-altseason-hopes-for-2026/), [AInvest](https://www.ainvest.com/news/bitcoin-dominance-60-3-flow-analysis-capital-rotation-altcoins-2605/)). The CoinGecko `/global` primary endpoint returns **56.617%** at Aug-6 22:35 UTC, and [CoinGabbar's Aug-6 wrap](https://www.coingabbar.com/en/crypto-currency-news/crypto-news-today-august-6-bitcoin-gains-defi-stablecoin-drops) independently prints 56.5%. **The primary is used; the 60%+ figures are rejected as unreconcilable.** This matters for the BTC companion report, where the difference decides whether the wrong-asset row fires — see that report's §2.5.

---

## 2. Verified Live Data

### Canonical spot reconciliation (median of 3 synchronized venue quotes)

| Source | Price | Timestamp (UTC) |
|---|---|---|
| Binance ETHUSDT | $1,904.10 | Aug-6 22:36:41 |
| Coinbase ETH-USD | $1,902.68 | Aug-6 22:36:37 |
| Kraken ETHUSD | $1,902.30 | Aug-6 22:36 (receipt) |
| **Canonical (median)** | **$1,902.68** | inter-source spread **0.095%** |

Yahoo's ETH-USD daily close is excluded from the median as a frozen bar. Spread **0.095% < 0.5%** → no dual-extreme EV computation required; the read does not sit inside price-source noise.

### Price, structure, and the two measurements that moved

| Metric | Value | Prior (Jul 31) | Source |
|---|---|---|---|
| Spot | $1,902.68 | $1,888.35 | median of 3 venues, Aug-6 22:36 UTC |
| 1-year high | $4,953.73 (2025-08-18) | same | Yahoo ETH-USD |
| % below 1y high | −61.59% | −61.88% | computed |
| **Trailing 40-session low** | **$1,548.76 (2026-06-28)** | $1,510.51 (2026-06-26) | computed — *the Jun-26 low rolled out of the window* |
| Bounce high | $1,976.46 (2026-07-27) | same | computed |
| **Rally to today's session high ($1,915.93)** | **+23.71%** | +28.02% | computed |
| Rally to the bounce high | +27.62% | +30.85% | computed |
| **Sessions since the 40-session low** | **39** | 35 | computed |
| Sessions since the bounce high | 10 | 3 | computed |
| 200dma | $2,066.88 (falling −5.82%/20s) | $2,110.10 (−5.26%) | computed |
| 50dma | $1,790.26 — price **+6.34% ABOVE** | $1,773.62 (+6.5% above) | computed |
| 50/200 gap | −13.38% (was −20.69% 20 sessions ago → **narrowed**) | −15.95% (from −20.65%) | computed |
| 200-week SMA | $2,481.80 — spot **−23.33%** below | $2,481.97 | computed |
| Weekly RSI-14 (last completed week Jul-27) | **41.96** | 42.85 | Wilder, 261 completed closes |
| Weekly RSI incl. live week | 42.73 | 42.04 | disclosed, not scored |
| Daily RSI-14 | **55.27** | 53.74 | Wilder |
| Daily RSI-14 at the bounce high | 55.52 | 55.52 | Wilder |
| ADR(5) | $46.98 = **2.47%** | $62.34 = 3.30% | 5 full sessions, none excluded |

**Weekly-RSI hygiene (memory note applied):** the scored figure **41.96** is the last *completed* weekly bar (week of Jul-27). The live-week-inclusive print is 42.73. Both are below the §4B hard qualifier of 50, so the distinction is disclosed but not load-bearing this report.

### Sentiment

| Source | Reading | Status |
|---|---|---|
| Alternative.me F&G, spot (Aug-6) | **25** | Extreme Fear |
| F&G 3-day average | **25.67** | Extreme Fear |
| F&G 30-day mean (trailing) | ≈**26.0** | — |
| §4B leg (a) threshold (1.5× the 30d mean) | ≈**39.0** | 25.67 is **13 points short** |
| F&G percentile vs 2y | 27.02 | bottom third |
| Last 10 prints | 25, 27, 25, 28, 27, 27, 25, 28, 29, 29 | flat, no local greed |

### ETF flows (Hard Rule 1 — live web fetch, not tool-computed)

| Window | Net Flow | Inflection? |
|---|---|---|
| ETH spot ETFs, Jul-31 | **−$6.40M** | outflow |
| ETH spot ETFs, Aug-3 | **−$11.9M** (BlackRock staking ETHB +$5.8M against the trend) | outflow, reversing two prior inflow days |
| ETH spot ETFs, Aug-4 | positive (second-straight-session framing) | inflow |
| ETH spot ETFs, Aug-5 | **+$60.8M**, led by ETHA — "second straight session" | inflow |
| ETH spot ETFs, Aug-6 | **+$60M**, reported as the highest since Jul-22 | inflow |
| ETH 30-day cumulative | **−$8M** | net negative, modest |

Sources: [BloomingBit (Aug-5, $60.8M)](https://en.bloomingbit.io/feed/news/117795), [CryptoRank (Aug-3, −$11.9M)](https://cryptorank.io/news/feed/8edc1-us-spot-ethereum-etfs-net-outflow-aug-3), [CoinGape (Aug-6, $60M)](https://coingape.com/markets/crypto-market-update-august-6-bitcoin-nears-65k-as-eth-pi-network-top-gains/), [TheBlock ETH ETF flows](https://www.theblock.co/data/etfs/ethereum-etf/spot-ethereum-etf-flows).

**Principle 13 durability lock applied, and it decides a leg.** The inflow run is **at most 3 sessions** (Aug-4/5/6), and the two headline $60M figures carry conflicting date attribution across sources — plausibly one settlement print reported twice. Three sessions is short of the five the durability lock requires for a flow-inflection claim, so §4B leg (a)(c) — "ETF net inflows resuming into the rally" — **scores as the prior regime, i.e. ❌**, and is disclosed as one-to-three sessions old rather than counted. Note the asymmetry is working correctly here: this is a claim that would make the short *easier*, and it gets no relaxation.

### Derivatives, carry, and positioning

| Metric | Value | Source |
|---|---|---|
| Perp funding, mean per 8h (45 intervals / 15 sessions) | **0.00%** | Binance fapi fundingRate, ETHUSDT |
| Perp funding annualized | **+3.05%** (longs pay shorts → carry **INCOME** to a short) | computed, `fr-funding` convention |
| Longest sustained run below −5% annualized | **0 intervals** | — |
| Deepest single interval | −4.00% annualized (inside the −5% trigger) | — |
| Single interval below −7% | **No** | — |
| Funding percentile vs 167d history | 61.08 | disclosed context |
| Perp basis | −0.05% → annualized carry +3.05% | Binance premiumIndex |
| Binance long/short account ratio | 1.955 (31st pct, **falling**) | single-venue, ~30d history |
| Taker buy/sell ratio | 1.020 (59th pct, falling) | single-venue |
| Open interest | 2,311,975 (55th pct, falling); 90d high **unavailable** | single-venue |
| Spot borrow (Bitfinex fETH) | 0.383% annualized, ask size 6,092 ETH | single-venue lending book, disclosed context |
| Implied carry to a short over 21d | **+0.18%** (income) | computed |

**Context Panel (disclosed context only — never a scored leg or gate):** Deribit ETH ATM IV **43.98%**, DVOL 47.98, RV30 **40.88%**, VRP **+3.10pp**. Moneyness skew (90/110) **+4.69%** — positive, meaning the ~10% OTM put is **richer** than the ~10% OTM call: a **downside hedging bid**. Per the 2026-08-05 sign convention, a distribution blow-off *compresses or inverts* this skew; a richened put is the opposite of a top signature and is context **against** the short, not for it.

### Macro risk regime

| Metric | Level | Δ5 sessions | Source |
|---|---|---|---|
| VIX | 15.15 | **−11.35%** | Yahoo ^VIX, Aug-6 |
| DXY | 99.96 | −0.05% | Yahoo DX-Y.NYB |
| US 10y | 4.67% | +0.15% | Yahoo ^TNX |
| 10y TIPS real yield | 2.41% | 0.00 | FRED DFII10, Aug-5 |
| S&P 500 | 7,709.96 | **+3.66%** | Yahoo ^GSPC |
| Nasdaq Composite | 26,348.35 | **+4.88%** | Yahoo ^IXIC |
| Gold | $4,300.50 | +4.89% | Yahoo GC=F |
| Brent | $83.33 | 0.00% | Yahoo BZ=F |
| HY OAS | 2.75% | −0.12pp (tightening) | FRED BAMLH0A0HYM2 |
| NFCI | −0.529 (looser than average) | — | FRED, Jul-31 |
| Net liquidity | $5.84T | — | FRED WALCL−RRP−TGA, Aug-5 |
| Stablecoin supply | $183.39B, −0.44% 30d, −3.31% 90d | 92nd pct | DefiLlama |

**Sourced 30-day correlation to SPX: 0.284** (30 daily log-return pairs, Jul-8 → Aug-6, Yahoo). Below 0.70 → the **risk-on surcharge is OFF on a measured number**, not on a default. SPX is −0.34% off its 6-month high (full risk-on on the equity limb), but the correlation conjunct fails, so §4A's suppressor does not apply.

**This is a risk-on macro tape.** VIX collapsing 11% in five sessions, equities melting up 3.7–4.9%, credit tightening, financial conditions loose. Every one of those is a headwind to a crypto short.

---

## 3. Critical Developments

- **CLARITY Act odds collapsed to ~16%** of passing before the Senate recess on Aug-7, per [CoinGape's Aug-6 market update](https://coingape.com/markets/crypto-market-update-august-6-bitcoin-nears-65k-as-eth-pi-network-top-gains/). This is the single largest crypto-specific event in the window and it is **bearish** — a regulatory disappointment, not an upside narrative break. It does not fire the §7 upside-narrative-break cover trigger; it argues mildly *for* the short and is already reflected in a tape that has not rallied.
- **Hormuz/Iran de-escalation.** Qatar signalled a draft peace framework; risk assets firmed on it ([Yahoo Finance, Aug-6](https://finance.yahoo.com/personal-finance/investing/article/bitcoin-and-ethereum-prices-today-thursday-august-6-2026-prices-firm-as-hormuz-negotiations-continue-and-adp-jobs-report-misses-134510256.html)). Combined with a soft ADP print, this is the macro tailwind behind the VIX collapse. **Squeeze-relevant.**
- **ETH ETF flows turned positive into the week's close** — $60M-scale sessions, the largest since Jul-22. Durability-locked out of the score (above) but live as squeeze fuel.
- **DeFi TVL −38.7% and stablecoin activity −5%** on Aug-6 per [CoinGabbar](https://www.coingabbar.com/en/crypto-currency-news/crypto-news-today-august-6-bitcoin-gains-defi-stablecoin-drops) — ETH-native activity deteriorating even as price firms. A genuine bear-structure tell, and it is *not* in the rubric.
- **ETH dominance 10.07%**, market cap $2.279T total. ETH is holding relative share while BTC dominance grinds up.

---

## 4. Flying Rocket Composite Score — §4B (Channel B rubric)

**Regime precondition re-verified at scoring time** (not inherited): close $1,902.68 below the 200dma $2,066.88 ✓ · 200dma falling −5.82%/20 sessions ✓ · −61.59% below the 1-year high ✓. Channel B stands.

| Category | Max | Measurement | Score |
|---|---|---|---|
| **Rally Extension** | 5 | Bounce off the trailing 40-session low $1,548.76 (Jun-28) **to today's session high $1,915.93 = +23.71%** → band >18% | **3** |
| **Local Momentum Exhaustion** | 4 | Daily Wilder RSI-14 **55.27** → band >52. Hard qualifier: weekly RSI **41.96 < 50** ✓ so the leg is scoreable | **2** |
| **Resistance Confluence** | 5 | 1 of 4 — see below | **1** |
| **Bear Structure Integrity** | 3 | 1 of 3 — see below | **1** |
| **Relative Sentiment / Positioning** | 3 | 0 of 3 — see below | **0** |
| **RAW TOTAL** | **20** | | **7** |

**Rally Extension — the measurement that moved, stated in full.** The 40-session window now begins Jun-11, which places the June-26 spike low of $1,510.51 **outside** it; the in-window low is $1,548.76 on Jun-28. Measured to today's session high $1,915.93 the rally is **+23.71%** (band >18% → 3); measured to the Jul-27 bounce high $1,976.46 it is **+27.62%** (band >25% → 4). The rubric says "measured to the **current session high**," which is the literal reading, the one the prior report used, and — per the Hard Rule 6 edge convention — the lower-score, harder-to-short one. **Leg = 3.** Both readings are printed; the verdict is identical either way.

**Resistance Confluence — 1/4:**
- (a) within 3% of, or rejected from, the 200dma — **❌**. Price is **−7.89%** below it; the bounce high itself topped 7.37% below the then-200dma. ETH has never touched this level in the entire bounce.
- (b) within 3% of the 50dma from below, or has just lost it — **❌**. Price is **+6.34% ABOVE** the 50dma. Wrong side, wrong distance.
- (c) at/below a prior swing high that is itself a lower high — **✅**. Spot $1,902.68 sits beneath the Jul-15 pivot $1,944.16 and the Jul-27 bounce high $1,976.46, both of which are lower highs against the May-21 $2,154.22 → Apr-17 $2,464.78 sequence.
- (d) into a prior breakdown level / gap — **❌**. The relevant overhead shelf is $1,944–1,976; price is below it, not in it.

**Bear Structure Integrity — 1/3** *(and note this leg was rewritten 2026-07-27 precisely so it stops handing out free points)*:
- (a) the bounce high is a **lower high** than the prior swing high — **❌**. The last pivot high before the 40-session low is **$1,847.77 (Jun-15)**; the bounce high $1,976.46 **exceeded** it by 7.0%. It also exceeded the intra-bounce Jul-15 pivot $1,944.16. Fails on both readings.
- (b) 50dma below the 200dma **and the gap has not narrowed** over 20 sessions — **❌**. The 50/200 gap went from **−20.69% to −13.38%** — narrowing for a third consecutive report, and materially faster than last time.
- (c) no weekly close above the 200dma in the trailing 8 weeks — **✅**. Last completed weekly close $1,858.26, ten percent beneath it; no reclaim in the window.

At **1/3** the channel is not void (0/3 would force-cover 100%), but this is the leg to watch: it is **one criterion from voiding Channel B on ETH outright**, and criterion (b) is the one actively deteriorating.

**Relative Sentiment / Positioning — 0/3:**
- (a) F&G 3d ≥1.5× its trailing 30d mean, or ≥45 in a sub-30 regime — **❌**. 25.67 against a ~39.0 threshold; and 25.67 is not ≥45. No local greed anywhere.
- (b) funding flipped positive after ≥5 sessions negative — **❌**. There is no negative run to flip from: the longest negative run in the sampled window is **0 intervals**.
- (c) a flow tell — ETF inflows resuming, or exchange outflows stalling — **❌ by the durability lock**. Three sessions of inflows with conflicting date attribution is not the five-session run Principle 13 requires. Disclosed, not counted.

**Modifiers, in the mandated order:**

| Step | Value | Note |
|---|---|---|
| Raw legs | **7** | 3 + 2 + 1 + 1 + 0 |
| Squeeze-trap penalty | **0** | NOT active. `sustained3_below_minus5 = false`; deepest interval −4.00% ann., inside the −5% trigger; no single print below −7%. `compute squeeze --funding-annualized 3.05` → tier `none` |
| Bounce-maturity floor | **0** | Rally is 39 sessions old, ≥8 |
| Gate surcharges | **0** | Correlation **sourced** at 0.284 < 0.70 → risk-on surcharge OFF. Squeeze surcharge 0. *(These move the gate floor, not the score.)* |
| Cap | **none** | Channel B has no phase-of-cycle cap |
| **MECHANICAL** | **7 / 20** | `fr-composite --channel B` verified |
| Discretionary (S1) | **0.0** | See §9 — a positive assertion, not an omission |
| Cap re-applied | n/a | |
| **ADJUSTED** | **7 / 20** | |

**Channel B Phase-1A line is 13. Adjusted 7 misses it by six points.** Score band 6–8 → **Watch List / PREPARE**.

### Confirmation Gates — Channel B set (X / 9)

Denominator **9**, no N/A. Channel B's gates carry no [TOP]/[FLOW] classification and no reachable-ceiling disclosure (they are all evaluable inside a downtrend by construction), and gate 8 may never be marked N/A.

| # | Gate | Reading | Status |
|---|---|---|---|
| 1 | Rally ≥15% off the trailing 40-session low | **+23.71%** to today's high · +27.62% to the bounce high | ✅ |
| 2 | Bounce age ≥8 and ≤35 sessions | **39 sessions** from the low (`bounce_age_sessions`) · 29 low-to-bounce-high | **❌** |
| 3 | Daily RSI-14 ≥52 at the bounce high | **55.52** | ✅ |
| 4 | Weekly RSI-14 <50 | **41.96** | ✅ |
| 5 | Price rejected from, or within 3% of, the 200dma | **−7.89%** below; never reached | ❌ |
| 6 | 50dma below 200dma | $1,790.26 < $2,066.88 | ✅ |
| 7 | Last completed weekly close did not reclaim the 200dma | $1,858.26 vs $2,066.88 | ✅ |
| 8 | **Funding not sustained-negative (veto gate)** | 0 intervals below −5% ann.; funding +3.05% ann. | ✅ **GREEN** |
| 9 | F&G 3d ≥1.5× 30d mean, OR funding flipped positive after ≥5 negative | 25.67 vs ~39.0; no negative run to flip from | ❌ |

**GATES 6 / 9.** Floors, arithmetic shown: 1A `ceil(3/9 × 9) = 3` [legacy 3] · 1B `ceil(5/9 × 9) = 5` [legacy 5] · P2 `ceil(6/9 × 9) = 6` [legacy 6] · **Phase 3 unreachable in Channel B at any score**.

**Gate 2 methodology, disclosed rather than buried.** The prior two ETH reports measured bounce age **low-to-bounce-high** (29 sessions today, which passes). `tools/fetch.mjs` names its own field `bounce_age_sessions` and populates it **low-to-today** (39, which fails). I take the tool's field as primary for two converging reasons: it is what the deterministic toolchain actually computes, and it is the harder-to-short reading Hard Rule 6 requires when an edge is ambiguous. **This is a methodology change against the prior report and I am flagging it as one.** Under the prior convention the board reads **7/9**; under this one, **6/9**. The verdict is identical either way — the score misses by six, and the gate board has cleared Phase 2's floor for four consecutive reports without ever mattering.

**The inversion persists for a third report: the gate board clears through Phase 2 while the score does not clear Phase 1A.** That is the framework working as designed. The *setup* is present; the *quality* is not, and this report it got worse.

---

## 5. Probability Matrix

**Baseline grid departure (S1-authorized, §5).** The top-centric default scenario set does not describe a Channel B tape. I replace it with the four modes that actually partition this setup, per the 2026-07-27 analyst-override provision. Probabilities sum to 100%, the EV recomputation runs below, and the reasoning is in §9. **Direction of the departure: net bearish-for-the-short relative to my own prior report** — I have cut the upside mode from 32% to 26% on the three-report non-print record documented in §0, and that is the change that must name its evidence, which it does.

**Trend term / confirmation throttle (§5):** ETH is >20% off its 1-year high, so a bounce does **not** qualify as a confirmed uptrend without ≥15 sessions of higher-high/higher-low structure on weekly closes. It does not have that — the bounce high is **10 sessions behind** and the sequence since is lower highs. **No mass is shifted toward Continued Rally.** The post-shift Continued Rally cell may never exceed 55%; at 26% it is nowhere near the ceiling.

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|
| Bounce resumes / 200dma test | **26%** | $1,976 – $2,090 | Daily close above $1,976.46 on expanding volume; ETF inflow run extends past 5 sessions |
| Range grinds | **38%** | $1,830 – $1,976 | Neither $1,976 nor $1,830 closes; the 39-session pattern simply continues |
| Bounce fails, 50dma test | **24%** | $1,700 – $1,830 | Loss of $1,830 on a daily close; 50dma $1,790 is the first shelf |
| Breakdown leg resumes | **12%** | $1,520 – $1,700 | Daily close below $1,700 → the Jun-28 low $1,548.76 comes into play |

**EV recomputation from the printed cells (mandatory sum-check):**

```
0.26 × 2,033 =   528.58
0.38 × 1,903 =   723.14
0.24 × 1,765 =   423.60
0.12 × 1,610 =   193.20
                --------
EV_price     = $1,868.52     probabilities sum = 100% ✓
```

`tools/compute.mjs ev` reproduces **$1,868.52** exactly; stated EV and recomputed EV agree to the cent.

**Short EV decomposition (sign-aware):**

| Component | Value |
|---|---|
| Spot | $1,902.68 |
| EV_price | $1,868.52 |
| **Directional EV** | **+1.80%** |
| Expected hold | 21 days (Channel B Phase 1A clock) |
| Annualized funding | **+3.05%** (POSITIVE = longs pay shorts = carry **INCOME** to a short) |
| Carry EV, **true signed** | **+0.18%** |
| Carry EV, **floored for gating** | **0.00%** (zero-floor: income may never help a short clear a filter) |
| **Total Short EV, true** | **+1.98%** |
| **Total Short EV, for gates** | **+1.80%** |
| Minimum-edge filter (+3%) | **FAILS by 1.20 points** |
| Carry as % of an 8% target gain | 0.0% → **carry veto does NOT fire** |

**EV voice: LOAD-BEARING, not corroborative-only.** The demotion clause applies where the phase-of-cycle cap or a 0-gate state is the sole/dominant veto. Channel B has no cap and the gate board reads 6/9 — well above every floor. **The score is the binding veto here, and the EV is a real, if thin, secondary read.** Trailing calibration of the last three ETH FR EVs: −3.22%, +0.68%, +1.16%; mean signed error −0.46%.

---

## 6. Short Deployment Strategy

**Position of record (Hard Rule 8): EXPIRED — cold start per Hard Rule 4, stated explicitly.**

`node tools/position.mjs eth` → exit **1**, band **EXPIRED**, age **7,604 minutes (5.28 days)**, driver `holdings_as_of`, against a 4,320-minute expiry. The ledger is too old to be the position of record. Per Hard Rule 8 this **refuses the position claim, not the report**: I proceed as a cold start with **all phases DRY POWDER** and **no** narrated quantity, cost basis, PnL, custody status, or dry-powder figure carried forward from the Jul-31 report. The Jul-31 snapshot's ETH readings (dust quantity, `basis.reliable = false` on 24 unbacked disposals, an `UNTAGGED` open deal contradicting the reconciled balance) are **not** restated as fact here — they were FRESH then and are not evidence now.

**Practical consequence:** nothing in this report depends on a position figure, because nothing unlocks. But had a phase unlocked, an EXPIRED snapshot could **not** have satisfied a phase-dependent precondition, and the sizing question would have had to wait for a ledger refresh.

**Per-channel realized performance:** unreadable this run (the snapshot that carries `performance_by_tag_prefix` is expired). As of the last FRESH read on Jul-31, `FR-A-` and `FR-B-` were both empty — **the framework remains N=0 on realized money.**

### Phase board

| Phase | Size | Score line (Ch. B) | Gate floor | Score now | Gates now | Status |
|---|---|---|---|---|---|---|
| 1A — Probe | 5% | ≥13 | 3 / 9 | 7 ❌ | 6 ✅ | **DRY POWDER** |
| 1B — Add | 10% | ≥15 | 5 / 9 | 7 ❌ | 6 ✅ | **DRY POWDER** |
| 2 — Conviction | 15% | ≥17 | 6 / 9 | 7 ❌ | 6 ✅ | **DRY POWDER** |
| 3 — Generational | — | — | — | — | — | **UNREACHABLE — Channel B has no Phase 3 at any score** |

**Deployed: 0%** of the 50% book cap · **0%** of the 30% per-asset cap · **0%** of the 20% analyst-channel cap.

**Analyst channels, both checked and both unavailable:**
- **S1** — the maximum +2.0 term reaches an adjusted 9, still **four points** short of 13. Load-bearing discretion is arithmetically impossible.
- **S2 Conviction Path** — structurally unavailable on two independent grounds: the Phase-1A score line is missed by six (S2 requires the line be *met*), and the gate count is **over**-satisfied at 6/9 rather than short by exactly one.

### §7 Cover-Trigger Preflight — Channel B set (five triggers)

Evaluated even though nothing unlocks, per §6.

| Trigger | Reading | Result |
|---|---|---|
| Published FK ≥12 on ETH | Published Aug-5: **10 adjusted / 11 mechanical**; computed today **11 mechanical** | PASS |
| Funding negative ≥3 consecutive intervals | 0 intervals; funding +3.05% ann. | PASS |
| Upside narrative break | CLARITY at ~16% is a *downside* regulatory event; Hormuz de-escalation is macro risk-on, not a crypto-specific adoption/regulatory win | PASS |
| Last weekly close reclaimed the 200dma | $1,858.26 vs $2,066.88 — no | PASS |
| Bear Structure Integrity 0/3 | **1/3** | PASS |

**Preflight PASSES clean, no borderline WARNING** — but Bear Structure at 1/3 is **one criterion from the 0/3 that voids Channel B and force-covers 100%**, and criterion (b) is deteriorating on a trend. That is the closest thing to a live risk in this report.

### Stop band (computed for the record; nothing fills)

`frStopBand(fill 1902.68, adr5 46.98, channel B, phase 1a)`:

| Element | Value |
|---|---|
| Hypothetical fill | $1,902.68 |
| ADR(5) | $46.98 = **2.47%** |
| **Noise floor (1.5 × ADR)** | **+3.70% → $1,973.08** |
| **Phase ceiling (Ch. B 1A)** | **+6.00% → $2,016.84** |
| Structure level (bounce high +1%) | $1,996.22 — **inside the band** |
| `ok` | **true** |

The floor does not exceed the ceiling, so the tape is not too volatile for the phase — ADR(5) actually *compressed* from 3.30% to 2.47% since Jul-31, widening the workable band. **The tape is tradeable; the score is what fails.** Tradeability and opportunity are different things.

### Carry Cost Ledger

**Not applicable — no live tranche.** For reference at a hypothetical 21-day Phase-1A hold: funding +3.05% annualized is **income** of ~+0.18%, floored to 0.00% for both the minimum-edge filter and the 40%-of-target carry veto. Carry is **0.0%** of an 8% target gain; the veto does not fire and could not, since income cannot trip a cost veto.

### Leverage / liquidation

**Not applicable — no live tranche.** For the record: all phase sizes are percentages of a dedicated short book **assumed unlevered**. Were a tranche opened on a levered perp, a stated liquidation price would be mandatory, and one sitting at or inside the stop distance would be **prohibited** — the snapshot's `liquidation_price_usd` is always null and that is not permission to omit it.

### §6.5 Asset rotation screen

**Skipped, correctly.** §6.5's cohort screen requires candidates within 10% of their own 1-year ATH — a **Channel A** precondition. In Channel B the screen can only return names the active channel cannot trade, and the SKILL says to skip it entirely here. For the record, the regime is hostile to alt shorts anyway: BTC dominance rising at 56.62%, ASI 37, altcoins broadly failing to outperform BTC over 90 days.

---

## 7. Cover / Exit Framework

**No position. No triggers can fire.** Status of each Channel B trigger is recorded in the preflight table above; all five are PASS. Remaining position: **0%**.

For the record, the two Channel B **100%-cover** regime tests and their current distance:

| Cover trigger | Current | Distance |
|---|---|---|
| Weekly close reclaims the 200dma | $1,858.26 vs $2,066.88 | **−10.10%** — a very long way |
| Bear Structure Integrity 0/3 | **1/3** | **one criterion** |
| FK ≥12 force-cover | 10 adjusted / 11 mechanical | **one point on the mechanical reading** |

**Hard Rule 5:** Channel B — level-based inverse consistency **not evaluable** (the two frameworks score different objects on different horizons); the **FK ≥12 force-cover governs instead**, and does not fire.

---

## 8. Critical Watchlist

| Time (EST) | Event | Asset Impact | Short Implication |
|---|---|---|---|
| **Fri Aug 7** | **US Senate breaks for recess** — CLARITY Act binary resolves by default at ~16% odds | High | A failure is already ~84% priced; the *surprise* risk is a late passage, which would gap shorts. **Asymmetric against a short.** |
| Fri Aug 7 | US employment data follow-through after the soft ADP miss | Medium | A weak print extends the risk-on/rate-cut bid → squeeze fuel |
| Ongoing | Hormuz / Iran draft peace framework | Medium-High | Further de-escalation compresses VIX further; risk-on. **Against a short.** |
| Rolling | ETH ETF flow prints Aug-7 onward | Medium | A 5th consecutive inflow session would satisfy the durability lock and *raise* the score — a reminder that the leg is scored as squeeze fuel, not as comfort |
| Late Aug | Aug-28 Deribit expiry (21.4 days out, ATM IV 43.98%) | Medium | Pinning risk around a large expiry |
| Rolling | **$1,548.76** — the Jun-28 40-session low | High | A break ends the counter-trend-rally read entirely and voids the Channel B thesis in the *other* direction (Principle 14: this framework shorts exhausted rallies, never breakdowns) |

---

## 9. Analyst Read

**The score fell four points in six days and almost none of it was price.** ETH is $14 higher than at the last report. What changed is that the *window moved*: the June-26 spike low rolled out of the trailing 40 sessions, shortening the measured rally by four points of percentage, and the bounce aged from 35 to 39 sessions past its origin. Two of the four points came from the rally-extension band, and the other two from a bear-structure leg that keeps eroding — the 50/200 gap has now narrowed from −20.7% to −13.4% in twenty sessions.

That last number is the one I would put in front of anyone who wanted to short ETH here. **A narrowing 50/200 gap in a confirmed downtrend is the tape repairing itself.** It is the third consecutive report in which it narrowed, and it is accelerating. The 200dma is still falling at −5.8%/20 sessions and price is still 7.9% beneath it, so Channel B's regime test holds — but the structure I would be shorting *into* is measurably less broken than it was a month ago. If criterion (b) is joined by criterion (a) or (c) failing, Bear Structure goes 0/3, and that is not a score decline — it is a **100% force-cover and the channel voiding outright.** That is one criterion away.

**S1 discretionary term: 0.0 — and that is an assertion, not a shrug.** I looked for a term in both directions and declined both. The bullish-for-shorts case would be the DeFi TVL collapse and the stablecoin drawdown, which the rubric genuinely cannot see. The bearish-for-shorts case would be the ETF inflow turn and the VIX collapse. But a term here would be decorative: at 7 against a line of 13, ±2 changes nothing, and the S7 ledger is more useful if it records *load-bearing* discretion than if it accumulates ornamental half-points. If I were closer to the line I would take a **negative** term for the risk-on macro; I am not, so I take zero and say why.

**What the rubric is missing, in both directions.** Against the short: the rubric has no representation of the *macro* tape at all in Channel B — VIX −11.35% in five sessions with equities up 4.9% is a squeeze environment, and nothing in §4B touches it. It also cannot see that funding has been positive for the entire sampled window with the long/short account ratio *falling* to the 31st percentile — the crowd is not long ETH, which means there is no positioning to liquidate on the way down and plenty of room for a bid. For the short: the rubric gives no credit for a 38.7% one-day DeFi TVL collapse or for a stablecoin supply that has shrunk 3.31% in 90 days. ETH's *usage* is deteriorating while its price firms. That divergence is real and it is not in any leg.

**Which single input would change the verdict.** A rejection at the 200dma. The path to a live ETH short runs **higher, not lower** — ETH would have to rally ~8.6% into $2,067 and be turned away there. That single event lights gate 5, plausibly takes Resistance Confluence from 1/4 to 3/4 (criteria a and d both), and pushes Rally Extension back into the >25% band. Mechanical would land around **12–13** — *at* the line, not comfortably past it. Everything else in this report is noise by comparison.

**The strongest argument against my current stance** is that I am standing down on an asset 62% off its high, under a falling 200dma, with a bounce that has visibly stalled ten sessions ago and is making lower highs. That is a textbook bear-continuation picture and the framework is refusing to trade it. The counter is Principle 14: Channel B shorts an *exhausted rally into resistance*, and there is no resistance here — the 200dma is 8% overhead and was never reached, the 50dma is 6% *below*, and price is in the middle of nothing. A short entered here has its stop in open air and its thesis resting on drift. That is the trade that fills at the trough, which is exactly what the backtest found and exactly what Principle 14 exists to prevent.

**On the §5 departure.** I cut the upside mode from 32% to 26%. The justification is documented in §0 — three consecutive reports have assigned 26–38% to a 200dma test that has not printed once, against a level price has never closed within 6% of. I am aware that trimming an upside mode makes the short case look better and that Hard Rule 6 requires me to name the evidence rather than assert a view; the evidence is the three-report non-print record. I redistributed to Range (36→38) and Bounce-fails (22→24), not to the tail. **The change moves Total Short EV by roughly +0.5pp and it still fails the +3% filter by 1.2 points.** The departure changes nothing that matters, which is the honest test of whether it was self-serving.

**One correction to carry forward.** The gate-2 measurement basis changed this report (low-to-today rather than low-to-bounce-high), moving the board from a would-be 7/9 to 6/9. I flagged it in §4 rather than letting the count drift silently, and I chose the reading that both matches the deterministic tool's own field and resolves harder-to-short. A future calibration should decide this properly rather than leaving it to per-report judgment — it is exactly the kind of undeclared convention that produced four different gate denominators in the non-crypto corpus.

### Discretion Ledger (S7)

| Date | Channel | S1 term | S2 used? | Load-bearing? | Reason | Outcome to date |
|---|---|---|---|---|---|---|
| 2026-07-28 | B | 0.0 | No | No | Rubric captured the tape | Stand down — correct (price −0.8% over 9 days) |
| 2026-07-31 | B | 0.0 | No | No | Rubric captured the tape | Stand down — correct (price +0.76% over 6 days) |
| **2026-08-06** | **B** | **0.0** | **No** | **No** | Terms available in both directions; declined as decorative at 7 vs a line of 13. Would take a negative term near the line for the risk-on macro | **Open** |

### Stop Migration Ledger

| Date | Tranche | Old stop | New stop | Direction | Trigger |
|---|---|---|---|---|---|
| — | — | — | — | — | **No tranche has ever filled on ETH under Flying Rocket. Ledger empty.** |

---

## 10. Bull vs Bear Scorecard (for ETH price — bull signals are bad for shorts)

**Bull (✅ — hostile to a short):**
1. ✅ VIX 15.15, −11.35% in five sessions — volatility collapsing into a risk-on macro
2. ✅ SPX +3.66% and Nasdaq +4.88% over five sessions, SPX −0.34% off its 6-month high
3. ✅ ETH ETF inflows turned: ~$60M sessions Aug-5 and Aug-6, largest since Jul-22
4. ✅ 50/200 gap narrowed −20.69% → −13.38% — the downtrend structure is repairing
5. ✅ Bounce high $1,976.46 **exceeded** the prior swing high $1,847.77 — a higher high, not a lower one
6. ✅ Funding positive across the entire sampled window; no crowded-long positioning to unwind
7. ✅ Deribit skew +4.69% — puts richer than calls, i.e. hedging demand already paid for
8. ✅ HY OAS tightening −0.12pp, NFCI −0.529 (loose) — credit and financial conditions supportive
9. ✅ Companion Fallen Knives at 11 mechanical with an extraordinary valuation floor (MVRV-Z ≈ −0.93)
10. ✅ Long/short account ratio falling to the 31st percentile — the crowd is *not* long

**Bear (❌ — supportive of a short):**
1. ❌ −61.59% below the 1-year high; 200dma falling −5.82%/20 sessions; price −7.89% beneath it
2. ❌ Bounce high 10 sessions behind, lower highs since — the rally has stalled
3. ❌ F&G 25.67, extreme fear with no local greed — no euphoria to sell, but also no bid
4. ❌ No weekly close above the 200dma in 8 weeks
5. ❌ DeFi TVL −38.7% and stablecoin activity −5% on Aug-6 — ETH-native usage deteriorating
6. ❌ Stablecoin supply −3.31% over 90 days — the capital-flow tell is negative
7. ❌ CLARITY Act at ~16% before recess — the regulatory catalyst disappointed
8. ❌ 30d cumulative ETF flow still **−$8M** despite the two green sessions
9. ❌ Spot −23.33% below the 200-week SMA — no long-cycle support underfoot

---

## 11. Change Log

| Factor | Previous (Jul 31) | Current (Aug 6) | Direction | Short Impact |
|---|---|---|---|---|
| Spot | $1,888.35 | $1,902.68 | +0.76% | Neutral |
| **Rally Extension leg** | **4** (+28.02%) | **3** (+23.71%) | ↓ | **Negative** — 40-session window rolled forward |
| Local Momentum leg | 2 (daily RSI 53.74) | 2 (55.27) | flat | Neutral |
| Resistance Confluence leg | 1 (1/4) | 1 (1/4) | flat | Neutral |
| **Bear Structure leg** | **1** (1/3) | **1** (1/3) | flat, but (b) worse | **Negative** — 50/200 gap −15.95% → −13.38% |
| Relative Sentiment leg | 1 (1/3, ETF inflow regime) | **0** (0/3) | ↓ | **Negative** — durability lock voided the flow tell |
| **Mechanical score** | **9** | **7** | ↓ 2 | **Negative** |
| Gate board | 7/9 (prior convention) | **6/9** (tool convention; 7/9 on prior basis) | ↓ / flat | Neutral — over floor either way |
| 40-session low | $1,510.51 (Jun-26) | **$1,548.76 (Jun-28)** | rolled | Negative for the short |
| ADR(5) | $62.34 = 3.30% | $46.98 = **2.47%** | ↓ | **Positive** — wider workable stop band |
| Funding annualized | +4.26% | +3.05% | ↓ | Slightly negative (less carry income) |
| VIX | 16.85 | **15.15** | ↓ 10% | **Negative** — risk-on |
| Correlation to SPX (sourced) | 0.398 | 0.284 | ↓ | Neutral — surcharge stays OFF |
| Companion FK (mechanical) | 10 computed / 11 published | **11 computed / 11 published** | ↑ | Negative — one point from force-cover |
| Position snapshot | FRESH (665 min) | **EXPIRED (7,604 min)** | ↓ | Cold start per Hard Rule 4 |

---

## 12. Strategic Verdict

**Mechanical 7 / 20 · Discretionary 0.0 · Adjusted 7 / 20** · EV_price **$1,868.52** · Total Short EV **+1.80% for gates / +1.98% true** against a **+3%** minimum-edge filter · F&G 3-day **25.67** · Channel **B** · **STAND DOWN — no ETH short, eighth consecutive report.**

The score did not fail because the market rallied. It failed because the setup **aged out**. The June low that anchored a 28% rally is no longer inside the trailing 40-session window, so the same tape now measures as a 23.7% bounce that is 39 sessions old — past the upper edge of the framework's own maturity gate. This is the mechanism working: Channel B is written to short an exhausted rally *into resistance*, and what is in front of me is an exhausted rally into **nothing**. The 200dma sits 7.9% overhead and has not been touched once in 39 sessions. The 50dma sits 6.3% below. Price is in the middle of an empty room, and a short entered in an empty room has a stop in open air and a thesis resting on drift. That is the trade Principle 14 was written after — it filled at the trough of two of the three falls it caught.

The more uncomfortable finding is in Bear Structure. The 50/200 gap has narrowed for three consecutive reports, from −20.7% to −13.4%, and the bounce high **exceeded** the prior swing high rather than making a lower one. Two of that leg's three criteria are already failing. If the third goes, Bear Structure prints 0/3, which is not a scoring event — it is a **100% force-cover and Channel B voiding on ETH outright.** Separately, the companion Fallen Knives score is at **11 mechanical**, one point from the ≥12 that makes Channel B unavailable here by rule. Both of those are *one increment away*, and both point the same direction: the framework's own machinery is closer to telling me not to short ETH at all than it is to letting me short it.

I have been standing down on ETH for eight consecutive reports and I want to be honest about what that streak is and is not. It is **not** a framework that is broken or too conservative — the score has moved 9 → 9 → 7 across the series in response to real changes, the gate board has moved, and the EV has stayed in a tight, well-calibrated band around spot with a mean signed error of −0.46% over the last three. It **is** a framework correctly reporting that a 62%-drawdown tape with extreme fear, positive funding, an uncrowded long book and a collapsing VIX is not where you sell. Shorts have a clock and longs have time; there is nothing here worth putting a clock on. The one thing that would change my mind is a rally I would have to sit through first — an 8.6% move into $2,067 that gets rejected — and I would rather wait for it than manufacture a reason to be early.

**Action items:**

1. **Stand down. No ETH short.** No tranche unlocks: adjusted 7 against a Channel B Phase-1A line of 13, missed by six. All phases DRY POWDER.
2. **Do not treat the 6/9 gate board as progress.** It has cleared Phase 2's floor for four consecutive reports and authorized nothing, because the score gates deployment and the gates do not.
3. **Watch Bear Structure criterion (b) — the 50/200 gap — as the single most important number on ETH.** It has narrowed three reports running. A third failing criterion voids Channel B and force-covers, and would end FR coverage of ETH until the regime re-forms.
4. **Watch the FK companion at 11 mechanical.** A one-point rise to 12 makes Channel B unavailable on ETH by rule, and the force-cover would be the reason for the stand-down rather than the score.
5. **Refresh the position ledger.** The snapshot is 5.3 days expired. Nothing in this report needed it, but a phase-dependent precondition could not have been satisfied if one had unlocked.
6. **Re-run on a 200dma rejection, not on a decline.** The trigger to watch is ETH reaching $2,010–2,070 and being turned away. That is the only realistic path from 7 to a probe-eligible score, and it requires the market to rally 5.6–8.6% first.
7. **Flag for the next calibration:** the gate-2 bounce-age measurement basis (low-to-today vs low-to-bounce-high), which changed the board by one gate this report and has no declared convention.

> **The Pattern**
>
> **IF** ETH rallies into **$2,010–2,070** and is rejected at the falling 200dma on a daily close back below $1,976 → **THEN** gate 5 lights, Resistance Confluence plausibly moves 1/4 → 3/4, Rally Extension returns to the >25% band, and mechanical lands near **12–13** — *at* Channel B's Phase-1A line, not past it. That is the first genuine probe setup this series has produced. *(Falsifier: a **weekly close above the 200dma at $2,066.88**, which voids Channel B outright and force-covers 100%.)*
>
> **IF** the 50/200 gap narrows through roughly **−10%** while the bounce high stands unbroken and no weekly close reclaims the 200dma → **THEN** Bear Structure Integrity prints **0/3**, Channel B **voids on ETH**, and this framework goes dark on the asset until a new bear structure forms. *(Falsifier: the gap re-widening past −16% on a 20-session basis, which would restore criterion (b).)*
>
> **IF** ETH prints a daily close below **$1,548.76**, the Jun-28 40-session low → **THEN** this is *not* a Flying Rocket signal. It is a fresh low, not a stalling bounce, and Principle 14 says Channel B has no trade there whatever the score reads. It is the Fallen Knives setup, and that framework's own falsifier already names the level. *(Falsifier: none — this is a rule, not a forecast.)*

---

```json machine
{
  "schema": "report-machine/1",
  "framework": "flying_rocket",
  "asset": "ETH",
  "date": "2026-08-06",
  "spot": { "value": 1902.68, "source": "median of 3 synchronized live venue quotes: Binance ETHUSDT $1,904.10 / Coinbase ETH-USD $1,902.68 / Kraken ETHUSD $1,902.30 (Aug-6 22:36 UTC); spread 0.095%; Yahoo daily close excluded as a frozen bar" },
  "channel": "B",
  "regime": {
    "pct_below_1y_ath": 61.59,
    "ma200_falling": true,
    "price_below_ma200": true
  },
  "score": {
    "legs": {
      "euphoria": 3,
      "momentum": 2,
      "valuation": 1,
      "distribution": 1,
      "vulnerability": 0
    },
    "penalty": 0,
    "discretionary": 0,
    "mechanical": 7,
    "raw": 7,
    "adjusted": 7,
    "rounding": "half-down"
  },
  "gates": {
    "active": 9,
    "na": [],
    "passed": [1, 3, 4, 6, 7, 8]
  },
  "ev": {
    "scenarios": [
      { "name": "Bounce resumes / 200dma test", "p": 26, "low": 1976, "high": 2090 },
      { "name": "Range grinds", "p": 38, "low": 1830, "high": 1976 },
      { "name": "Bounce fails, 50dma test", "p": 24, "low": 1700, "high": 1830 },
      { "name": "Breakdown leg resumes", "p": 12, "low": 1520, "high": 1700 }
    ],
    "stated_ev": 1868.52,
    "vs_spot_pct": -1.80
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": []
  },
  "verdict": "STAND DOWN -- no ETH short, eighth consecutive. Channel B live (-61.59% off the 1y high $4,953.73, 200dma $2,066.88 falling -5.82%/20 sessions, price -7.89% beneath it). Mechanical 7/20, discretionary 0.0, adjusted 7/20 against a Channel B Phase-1A line of 13 -- SHORT BY 6, and DOWN 2 from Jul-31's 9. THE SCORE FELL ON WINDOW MECHANICS, NOT ON PRICE: spot is +0.76% higher, but the Jun-26 spike low $1,510.51 ROLLED OUT of the trailing 40-session window, so the in-window low is now $1,548.76 (Jun-28) and the measured rally shortens from +28.02% to +23.71% (to today's session high $1,915.93), dropping rally extension 4 -> 3. Legs on the FR-B/1 rubric (leg keys are Channel A names carrying Channel B meanings): rally extension 3 (+23.71% to today's high, band >18%; +27.62% to the Jul-27 bounce high $1,976.46 would band 4 -- literal text says 'current session high', which is also the Hard-Rule-6 harder-to-short reading, both printed, verdict identical), local exhaustion 2 (daily RSI 55.27 band >52; weekly RSI 41.96 <50 so the hard qualifier passes; live-week-inclusive weekly 42.73 disclosed not scored), resistance confluence 1 (1/4 -- only the Jul-15 lower-high shelf $1,944.16; the 200dma is 7.89% OVERHEAD and was NEVER touched in 39 sessions, the 50dma $1,790.26 is 6.34% BELOW so price is on the wrong side of it), bear structure 1 (1/3 -- bounce high $1,976.46 EXCEEDED the pre-low pivot $1,847.77 (Jun-15) by 7.0% and also the intra-bounce Jul-15 pivot, so (a) fails on both readings; the 50/200 gap NARROWED again from -20.69% to -13.38%, third consecutive report, so (b) fails; only 'no weekly close above the 200dma in 8 weeks' passes), relative sentiment 0 (0/3, down from 1/3 -- F&G 3d 25.67 vs a ~39.0 threshold; NO funding flip because the longest negative run is 0 intervals; and the ETF flow tell is VOIDED BY PRINCIPLE 13's DURABILITY LOCK -- the inflow run is at most 3 sessions (Aug-4/5/6, ~$60M scale) with conflicting date attribution between sources, short of the 5 required, so it scores as the prior regime and is disclosed rather than counted). Squeeze-trap penalty NOT active: sustained3_below_minus5 false, deepest interval -4.00% annualized inside the -5% trigger, no single print below -7%, tier none. Bounce-maturity floor 0 (39 sessions from the low, >=8). No cap -- Channel B has none. Correlation SOURCED 0.284 (30 daily log-return pairs vs SPX, Jul-8 to Aug-6) <0.70 -> risk-on surcharge OFF on a measured number, despite SPX sitting -0.34% off its 6-month high. GATES 6/9 (passed 1,3,4,6,7,8; failed 2 bounce age, 5 the 200dma was never reached, 9 no local greed and no funding flip), no N/A, denominator 9, floors ceil(3/9x9)=3 [legacy 3] / ceil(5/9x9)=5 [legacy 5] / ceil(6/9x9)=6 [legacy 6], Phase 3 unreachable in Channel B. DISCLOSED METHODOLOGY CHANGE ON GATE 2: the prior two ETH reports measured bounce age low-to-bounce-high (29 today, PASSES); tools/fetch.mjs names its own field bounce_age_sessions and populates it low-to-today (39, FAILS the <=35 bar). The tool's field is taken as primary because it is what the deterministic toolchain computes AND it is the harder-to-short reading. Under the prior convention the board reads 7/9. Verdict identical either way -- the score misses by six. THE INVERSION PERSISTS FOR A THIRD REPORT: the gate board clears through Phase 2's floor while the score does not clear Phase 1A's. Gate 8 (funding veto, the only true veto in either framework) GREEN. Section 7 Channel B preflight PASSES clean on all five triggers with no borderline WARNING -- but TWO protective triggers are ONE INCREMENT AWAY: Bear Structure at 1/3 is one criterion from the 0/3 that voids Channel B and force-covers 100%, and the companion FK at 11 MECHANICAL is one point from the >=12 that makes Channel B unavailable on ETH by rule. S1 unavailable in effect (max +2.0 reaches 9, still 4 short). S2 structurally unavailable on two independent grounds (score line missed by 6 when S2 requires it MET, and gate count OVER-satisfied at 6/9 rather than short by exactly one). Total Short EV +1.80% floored / +1.98% true (EV_price $1,868.52 vs spot $1,902.68, directional +1.80%, carry +0.18% true floored to 0.00% for gating at +3.05% annualized funding over 21d) -- FAILS the +3% minimum-edge filter by 1.20 points. Carry is 0.0% of an 8% target so the 40% carry veto does not fire and structurally cannot, income being floored. EV is LOAD-BEARING, not corroborative-only: Channel B has no cap and gates read 6/9, so the demotion clause does not apply. Collar ON (>20% off the 1y high in Channel B -- always on there -- and |EV|<3%). SECTION 5 BASELINE-GRID DEPARTURE TAKEN under the 2026-07-27 analyst-override provision, with the four Channel-B-appropriate modes replacing the top-centric default; the upside mode was CUT 32% -> 26% on the documented three-report record of that mode never printing (26-38% assigned across three reports to a 200dma test against a level price has never closed within 6% of). Direction disclosed: trimming an upside mode STRENGTHENS the short case, so the evidence is named rather than asserted per Principle 11; it moves Total Short EV by ~+0.5pp and the trade still fails the filter by 1.20 points. Stop band computed though nothing fills: fill $1,902.68, ADR(5) $46.98 = 2.47% (COMPRESSED from 3.30% on Jul-31), noise floor 1.5xADR = +3.70% -> $1,973.08, Channel B 1A ceiling +6% -> $2,016.84, structure level (bounce high +1%) $1,996.22 sits INSIDE the band -- frStopBand ok:true. The tape is tradeable on the noise floor; the SCORE is what fails. Cross-check: FK computed 11/20 MECHANICAL from the same fetch (legs 2/1/5/0/3 -- sentiment F&G 3d 25.67 -> 2, momentum weekly RSI 41.96 -> 1, valuation MVRV-Z ~-0.93 -> 5, capitulation 0, holder 3), 2/8 gates, vs PUBLISHED Aug-5 11 mechanical / 10 adjusted, 2/8 gates -- both readings <12 on the mechanical axis, same side of the boundary, no strictest-wins conflict, force-cover does NOT fire. Hard Rule 5: Channel B -- level-based inverse consistency not evaluable (different scored objects on different horizons); FK >=12 force-cover governs instead. POSITION (Hard Rule 8): EXPIRED at 7,604 minutes (5.28 days), driver holdings_as_of, against a 4,320-minute expiry -- exit 1. COLD START PER HARD RULE 4, STATED EXPLICITLY. No quantity, cost basis, PnL, custody status or dry-powder figure is carried forward from the Jul-31 report; those were FRESH then and are not evidence now. All phases DRY POWDER by default rather than by measurement. Per-channel realized performance unreadable this run; as of the last FRESH read FR-A- and FR-B- were both empty -- the framework remains N=0 on realized money. Section 6.5 rotation screen correctly SKIPPED: its cohort precondition (within 10% of own 1y ATH) is Channel A by construction and can only return names Channel B cannot trade. Section 2.5 rows checked: smaller-alt N/A (ETH top-2, Bitfinex fETH borrow 0.383% annualized on a 6,092-ETH ask); altseason squeeze-trap row does NOT fire (it needs FALLING dominance with ASI >75; live is dominance 56.62% RISING and ASI 37). SOURCE CONFLICT DISCLOSED AND RESOLVED: two secondary outlets printed BTC dominance at 60.3-60.66% this week; the CoinGecko /global primary returns 56.617% at Aug-6 22:35 UTC and CoinGabbar's Aug-6 wrap independently prints 56.5%, so the 60%+ figures are REJECTED as unreconcilable -- this decides whether the wrong-asset row fires in the BTC companion report. Prior Jul-31 FR graded: EV_price $1,880.90 HELD (realized $1,902.68, +1.16% above); modal band 'Range grinds' 36% $1,820-1,960 HIT OUTRIGHT (all seven daily closes inside it) while the 32% 'Bounce resumes' mode never printed; both named falsifiers STANDING (no weekly close above the 200dma -- last completed weekly close $1,858.26 is -10.10% below; no held close below $1,757 -- lowest close $1,843.42); FK>=12 rule STANDING unfired. Trailing EV calibration n=3 (-3.22%, +0.68%, +1.16%), mean signed error -0.46%, errors straddle zero, no bias worth correcting. FLAGGED FOR THE NEXT CALIBRATION: (1) the gate-2 bounce-age measurement basis has no declared convention and moved the board by one gate this report; (2) three consecutive reports have over-weighted a 200dma-test upside mode that has never printed. The realistic path to a live ETH short runs HIGHER not lower: a 5.6-8.6% rally into $2,010-2,070 rejected at the falling 200dma lights gate 5, plausibly takes resistance confluence 1/4 -> 3/4, returns rally extension to the >25% band, and lands mechanical near 12-13 -- AT the line, not past it. All phases DRY POWDER; 0% of the 50% book, 0% of the 30% per-asset cap, 0% of the 20% analyst-channel cap.",
  "inputs": {
    "weekly_rsi": 41.96,
    "weekly_rsi_incl_live": 42.73,
    "weekly_rsi_last_completed_week": "2026-07-27",
    "daily_rsi": 55.27,
    "daily_rsi_at_bounce_high": 55.52,
    "rsi_closes": 261,
    "mvrv_z": null,
    "fng_spot": 25,
    "fng_3d": 25.67,
    "fng_30d_mean_approx": 26.0,
    "fng_leg_threshold_approx": 39.0,
    "fng_percentile_vs_2y": 27.02,
    "drawdown_pct_1y": 61.59,
    "high_1y": 4953.73,
    "high_1y_date": "2025-08-18",
    "ma200": 2066.88,
    "ma200_slope_20d_pct": -5.82,
    "ma50": 1790.26,
    "pct_vs_ma200": -7.89,
    "pct_vs_ma50": 6.34,
    "ma50_200_gap_pct_now": -13.38,
    "ma50_200_gap_pct_20ago": -20.69,
    "ma50_200_gap_narrowed": true,
    "sma_200w": 2481.80,
    "pct_vs_sma_200w": -23.33,
    "low_40_session": 1548.76,
    "low_40_session_date": "2026-06-28",
    "low_40_session_prior_report": 1510.51,
    "low_40_session_rolled_out_of_window": true,
    "bounce_high": 1976.46,
    "bounce_high_date": "2026-07-27",
    "sessions_since_bounce_high": 10,
    "prior_swing_high_pre_low": 1847.77,
    "prior_swing_high_pre_low_date": "2026-06-15",
    "prior_swing_high_intra_bounce": 1944.16,
    "prior_swing_high_intra_bounce_date": "2026-07-15",
    "bounce_age_sessions": 39,
    "sessions_low_to_bounce_high": 29,
    "rally_pct_to_current_high": 23.71,
    "rally_pct_to_bounce_high": 27.62,
    "current_session_high": 1915.93,
    "adr5": 46.98,
    "adr5_pct": 2.47,
    "last_weekly_close": 1858.26,
    "stall_confirmation": true,
    "stall_lower_close": true,
    "stall_lower_high": true,
    "funding_per8h_mean_pct": 0.00,
    "funding_ann_pct": 3.05,
    "funding_intervals_sampled": 45,
    "funding_sessions_sampled": 15,
    "funding_longest_negative_run_intervals": 0,
    "funding_sustained3_below_minus5": false,
    "funding_min_interval_ann_pct": -4.00,
    "funding_single_interval_below_minus7": false,
    "funding_percentile_vs_history": 61.08,
    "perp_basis_pct": -0.05,
    "long_short_acct_ratio": 1.9551,
    "long_short_acct_ratio_percentile": 31.03,
    "taker_buy_sell_ratio": 1.0203,
    "oi_percentile_vs_30d": 55.17,
    "oi_within_5pct_of_90d_high": null,
    "borrow_annualized_pct": 0.3829,
    "deribit_atm_iv_pct": 43.98,
    "deribit_dvol": 47.98,
    "deribit_skew_90_110_moneyness_pct": 4.69,
    "deribit_rv30_pct": 40.88,
    "deribit_vrp_pct": 3.10,
    "corr_spx_30d": 0.284,
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
    "btc_dominance_conflicting_secondary_reports_rejected": [60.3, 60.66],
    "altcoin_season_index": 37,
    "eth_dominance_pct": 10.07,
    "eth_etf_flows_usd_m": { "jul_31": -6.40, "aug_3": -11.9, "aug_5": 60.8, "aug_6": 60.0, "cumulative_30d": -8.0 },
    "eth_etf_inflow_run_sessions": 3,
    "eth_etf_durability_lock_satisfied": false,
    "clarity_act_pass_odds_pct": 16,
    "companion_fk": {
      "computed_mechanical": 11,
      "gates": 2,
      "gates_active": 8,
      "published_mechanical": 11,
      "published_adjusted": 10,
      "published_date": "2026-08-05",
      "force_cover_fires": false,
      "legs": { "sentiment": 2, "momentum": 1, "valuation": 5, "capitulation": 0, "holder": 3 }
    },
    "position": {
      "band": "EXPIRED",
      "age_min": 7604,
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
      "fill": 1902.68,
      "floor": 1973.08,
      "floor_pct": 3.70,
      "ceiling": 2016.84,
      "ceiling_pct": 6.00,
      "structure_level": 1996.22,
      "ok": true
    },
    "prior_fr_grade": {
      "report": "eth_flying_rocket_20260731_0426.md",
      "ev_price_prior": 1880.90,
      "ev_price_grade": "HELD (realized $1,902.68, +1.16% above)",
      "modal_band_prior": "Range grinds 36%, $1,820-1,960",
      "modal_grade": "HIT -- all seven daily closes inside the band; the 32% 'Bounce resumes' mode never printed",
      "falsifiers": [
        { "text": "a weekly close ABOVE the 200dma", "grade": "STANDING (last completed weekly close $1,858.26, -10.10% below $2,066.88)" },
        { "text": "a held close below $1,757 followed by a lower high beneath it", "grade": "STANDING (lowest close $1,843.42)" },
        { "text": "FK >=12 makes Channel B unavailable (rule, not forecast)", "grade": "STANDING (unfired; FK 11 mechanical)" }
      ],
      "ev_calibration_n3_signed_errors": [-3.22, 0.68, 1.16],
      "ev_calibration_mean": -0.46,
      "ev_calibration_note": "errors straddle zero; no directional bias worth correcting at n=3",
      "repeated_forecast_miss": "three consecutive reports assigned 26-38% to a 'bounce resumes / 200dma test' mode that has NOT printed once; the 200dma has been 7-11% overhead for the whole series and price has never closed within 6% of it"
    },
    "recheck_triggers": {
      "fired": [],
      "note": "none of 6.6(a)-(e) fired since Jul-31; this is a user-requested run. Grading does not re-arm a short -- the full 2.5/4B stack was re-run from scratch."
    },
    "methodology_changes": [
      "gate 2 bounce age now measured low-to-today (39, tools/fetch.mjs bounce_age_sessions) rather than low-to-bounce-high (29) as in the prior two reports; chosen as both the toolchain-consistent and the harder-to-short reading; board reads 7/9 under the prior convention, 6/9 under this one; verdict identical",
      "section 5 baseline grid replaced with four Channel-B-appropriate modes under the 2026-07-27 analyst-override provision; upside mode cut 32% -> 26% on a documented three-report non-print record"
    ]
  }
}
```
