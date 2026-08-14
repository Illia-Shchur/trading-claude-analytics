# 🔪 FALLEN KNIVES ANALYTICS — BTC — July 14, 2026

## TUESDAY, POST-CPI AFTERNOON — ALL DATA LIVE INTERNET-VERIFIED

### Report Generated: Tuesday, July 14, 2026, 2:30 PM EDT

### Asset: BITCOIN (BTC) | Prior Score: 12/20 (Jul 14, 8:45 AM) | Current Score: 12/20

> **Post-CPI update.** This morning's 8:45 AM report held every CPI-conditional claim event-conditional. **The print is now out and it was soft** — June headline CPI **−0.4% MoM / +3.5% YoY** (vs −0.1% / +3.8% consensus; May +0.5% / +4.2%), core **flat 0.0% MoM / +2.6% YoY** (vs +0.2% / +2.8% consensus). Both beat to the downside. July-FOMC hike odds collapsed from ~42% to a Fed-hold ~85%, and BTC squeezed higher (+4.18% 24h). The **score is unchanged at 12** — the daily fear gauge was fixed pre-rally, and the rally does not create accumulation signal; it prices it out.

---

## 2. Verified Live Data Points — BTC

### Price (canonical reconciliation)

Canonical spot = **median of the Jul-14 ~2:25 PM ET synchronized live cluster = ~$64,500.** Hard-API prints from three independent venues agree to $80. The morning's ~$62,855 relief-rallied through the day post-CPI.

| Source | Price | Timestamp | Note |
|---|---|---|---|
| Coinbase (spot API) | $64,501.82 | Jul 14 ~18:25 UTC (live) | — |
| **Canonical (median of 3 venues)** | **~$64,500** | Jul 14 ~2:25 PM ET | +4.18% 24h |
| Binance (BTCUSDT) | $64,535.00 | Jul 14 ~18:25 UTC (live) | — |
| CoinGecko (simple/price API) | $64,455 | Jul 14 ~18:25 UTC (live) | +4.18% 24h |
| CoinGecko (`fetch.mjs`) | $64,475 | Jul 14 18:22 UTC (live) | same source, corroborating |
| CoinDesk (article body) | $63,400 | Jul 14 ~12:33 PM (article-body figure) | **EXCLUDED — stale, −1.7% below the live 3-venue cluster** |
| Yahoo BTC-USD (last daily close) | $62,239.12 | Jul 13 close | **stale — excluded (Jul-13 daily close, by design)** |

Synchronized-cluster spread $64,455–$64,535 = **0.12%** < 0.5% → genuine simultaneous agreement, no low-confidence demotion. The `fetch.mjs` divergence flag (3.59%) was a **staleness artifact** — live CoinGecko $64,475 vs Yahoo's stale Jul-13 daily close $62,239, not venue disagreement. The CoinDesk $63,400 article-body number (written ~12:33 PM, not refreshed at the 3:00 PM update) sits 1.7% below the tight live cluster → excluded per the reconciliation rule.

### Sentiment (shared crypto F&G, Alternative.me — pinned provider)

| Reading | Value | Status |
|---|---|---|
| Spot (Jul-14 daily print) | **22** | Extreme Fear |
| 3-day average (scored) | **25.33** | ≤35 band → leg 2 — on the ≤25 edge |
| Daily prints ≤15 streak | **0** | gate 1 dark |

Last 5 daily prints: 22 (Jul-14) · 28 (Jul-13) · 26 (Jul-12) · 26 (Jul-11) · 23 (Jul-10). 3-day avg = (22+28+26)/3 = **25.33** (`tools/compute.mjs band fk-sentiment` → 2). **Note:** the Jul-14 print of 22 was set at 00:00 UTC, *before* the CPI rally — it does not yet reflect today's relief. Tomorrow's print will likely lift, which would push this leg *lower*, not higher. Context provider CoinMarketCap F&G read **29 ("Fear")** post-rally — 7 points above the pinned Alternative.me 22, < the 10-point mandatory-disclosure threshold.

### Spot BTC ETF Flows — green week reversed, Jul-14 pending

| Window | Net Flow | Source | Timestamp |
|---|---|---|---|
| **Jul 13 (daily)** | **−$424.63M NET OUTFLOW** (FBTC −245.62, IBIT −185.47, GBTC −53.06, HODL +6.14, Mini +53.38) | Bloomingbit / BitcoinWorld / KuCoin | Jul 14 (for Jul-13) |
| Jul 14 (daily) | **NOT FOUND (pending)** — finalizes after today's 4 PM ET US close | — | — |
| July MTD | **≈ −$300M (negative)** — prior +$124M through Jul-11 + Jul-13's −$424.63M | derived (SoSoValue/Farside 403) | Jul 14 |
| YTD 2026 | ≈ **−$5.4B** (June alone ≈ −$4.5B, worst month since launch) | spotedcrypto | Jul 2026 |

> The "first green week in 9 (+$197M)" the Jul-13 report flagged reversed in a single Monday session (−$424.63M). Today's soft CPI could revive the bid, but **the daily flow is still pending** and the institutional bid remains unconfirmed. A green Jul-14 print tonight would be the first evidence the CPI relief pulled institutional money back.

### On-Chain

| Metric | Value | Source | Timestamp |
|---|---|---|---|
| MVRV-Z | **0.35** (ratio 1.22, realized ~$52,582) | ahasignals / BGeometrics | Jul 10 UTC |
| Funding | **neutral-to-positive** (exact value JS-gated, NOT FOUND); the CPI move was a **short squeeze**, so no negative flip | CoinGlass (dir.) | Jul 14 |
| 24h liquidations | **$60.21M crypto liquidated in one hour post-CPI — 93% SHORTS** ($56.27M short vs $3.94M long) | CryptoBriefing (CoinGlass) | Jul 14 |
| LTH supply (30d) | **RISING** (~50–100K BTC net accumulation) | Glassnode/CoinDesk | Jul 2 |
| Exchange reserves (30d) | **FALLING** (~7-yr-low level ~2.40–2.43M BTC) | CoinGlass/CryptoQuant | Jul 14 (dir.) |
| Coinbase premium | **negative, shallow** (~55–56 days negative — record streak, extrapolated from 50 confirmed Jul-7) | CryptoBriefing | Jul 7 (count inferred) |
| MSTR / Strategy | **843,775 BTC — no buy/sale Jul 12–14** (~$13B underwater at ~$75,476 avg) | Daily Hodl / CryptoBriefing | Jul 13 |

The liquidation flush today was **the opposite of capitulation** — shorts, not longs, got carried out. That is squeeze fuel spent, a relief signal, not a fear washout (gate 7 stays dark by design).

### Correlation Regime

30-day BTC–SPX correlation **not computed live this cycle** (The Block/CoinGlass JS-gated) → risk-on surcharge defaults **OFF**. Stale context only (not current): ~0.48 end-May.

### Macro (post-CPI)

June CPI **−0.4% MoM / 3.5% YoY headline, flat / 2.6% core** (both soft) · July-FOMC **hike odds collapsed ~42% → Fed-hold ~85.6%** (HousingWire/Redfin) · Brent **$84.39** (−2.6% off the $86.61 AM level; Trump **abandoned the 20% Hormuz toll**, replaced with trade deals — but fighting continued a 3rd day) · real 10y TIPS 2.32% (Jul-10, cycle high, pre-CPI) · VIX **16.4** (down from 17.52 AM) · DXY 100.92 · S&P 7,553.43 (+0.66%/5 sess) · Nasdaq 26,163.91 · gold $4,067.

---

## 3. Critical Developments — BTC

- **June CPI came in soft across the board.** Headline fell −0.4% on the month (biggest drop since April 2020; +3.5% YoY vs 4.2% May), and **core was flat at 0.0% MoM / +2.6% YoY** — below the +0.2% / +2.8% consensus. The energy index sank 5.7% (gasoline back below $4). This is the bull path from the morning report's Pattern block ([CoinDesk Jul 14](https://www.coindesk.com/markets/2026/07/14/u-s-june-cpi-fell-0-4-likely-cooling-move-toward-fed-rate-hikes), [CNBC Jul 14](https://www.cnbc.com/2026/07/14/consumer-price-index-inflation-report-june-2026.html)).
- **The July-hike scare collapsed.** Odds that reached ~42–50% pre-print faded to a Fed-hold ~85.6% ([HousingWire Jul 14](https://www.housingwire.com/articles/june-cpi-fed-hold/)). The single most hostile near-term catalyst on this morning's board resolved favorably.
- **BTC squeezed higher, reclaiming the 200-week.** Spot ~$64,500 (+4.18% 24h) — from $62,855 this morning — driven by a short squeeze ($56.27M short liquidations vs $3.94M long in one hour). Spot now sits **+2.60% above** the $62,868 200-week SMA, a firmer reclaim than the morning's −0.02% "on the line" — but **intraday, not a weekly-confirmed close** ([CryptoBriefing Jul 14](https://cryptobriefing.com/us-cpi-rises-4-percent-bitcoin-crypto-rally/)).
- **The oil tail-risk eased, not resolved.** Trump withdrew the 20% Strait-of-Hormuz transit toll (replacing it with Gulf trade/investment commitments), pulling Brent off its 1-month high — but US–Iran fighting continued a third day and Brent still finished up ~2% on the session ([CNBC Jul 14](https://www.cnbc.com/2026/07/14/oil-prices-today-brent-wti-hormuz-trump-toll-iran.html)).
- **The on-chain base is unchanged.** MVRV-Z 0.35 (deep-value), LTH accumulating, reserves at a 7-year low, funding never flipped negative, MSTR static at 843,775 BTC. The structure that held this morning still holds; the news today is macro and price, not on-chain.

---

## 4. Fallen Knives Composite Score (BTC) — 12 / 20

| Category | Max | Score | Rubric Basis |
|---|---|---|---|
| **Sentiment Extreme** | 5 | **2** | 3-day F&G **25.33** → ≤35 band → 2 (spot 22 Extreme Fear; the 3d avg sits on the ≤25 edge — but the Jul-14 print predates the rally, and a rising print would push this leg *lower*, not higher). |
| **Momentum Exhaustion** | 4 | **2** | Weekly Wilder RSI-14 = **38.47** (261 completed closes, Yahoo weekly, last completed week Jul-6; live-week 38.68) → ≤40 band → 2. |
| **Valuation** | 5 | **4** | MVRV-Z **0.35** (ratio 1.22, realized ~$52,582) → ≤0.5 band → 4. Drawdown now −48.84% (the rally pulled it back above the −50% line; MVRV, not drawdown, is BTC's scored metric). |
| **Capitulation Evidence** | 3 | **1** | 1/3: (a) liquidations ❌ (today's flush was 93% SHORTS — a squeeze, not a long capitulation); (b) funding ❌ (neutral-to-positive, no negative flip); (c) **ETF outflows ✅ — trailing-month ≥2% AUM (June −$4.5B; MTD negative after Jul-13 −$424.63M).** |
| **Holder Behavior** | 3 | **3** | Both sub-legs ✅: LTH supply rising 30d (~50–100K BTC) + exchange reserves falling to a 7-yr low. |
| **TOTAL** | **20** | **12** | Raw 12 → **adjusted 12** (half-up; no surcharge). |

#### Confirmation Gates (4 / 9 — full board, no N/A)

| # | Gate | Bucket | Status |
|---|---|---|---|
| 1 | Sentiment ≤15 × ≥7 daily prints | [V] | ❌ (≤15 streak = 0; deepest recent 20) · *relight: F&G daily prints ≤15 for 7 days* |
| 2 | Weekly RSI <30 | [V] | ❌ (38.47) · *relight: RSI <30, ~8.5 pts away — and moving the wrong way as price rises* |
| 3 | Valuation cheap (MVRV-Z <1) | [V] | **✅** (0.35) |
| 4 | ETF outflows ≥2% AUM trailing month | [V] | **✅** (June −$4.5B; MTD negative after Jul-13 −$424.63M) |
| 5 | Hash Ribbon buy signal | [T] | ❌ (no confirmed buy cross) · *relight: miner-capitulation recovery / hash-ribbon buy cross* |
| 6 | Price within ±8% of 200-week | [T] | **✅** (+2.60% above $62,868 — reclaimed intraday) |
| 7 | Capitulation volume spike (top-decile/>3σ) | [V] | ❌ (today's flush was SHORT liquidations — the opposite of a long-capitulation flush) · *relight: a >3σ/top-decile LONG-liquidation flush* |
| 8 | LTH accumulation / holder stabilizing | [V] | **✅** (LTH accumulating, reserves falling) |
| 9 | Macro catalyst neutral-to-positive | [T] | ⚠️ **(upgraded ❌→⚠️)** — the July-hike scare collapsed (big positive), but oil is still ~$84 with an active 3rd-day Iran conflict and real yields are still 2.32% cycle-high; not yet a clean pass · *relight: a soft PPI (Jul-15) + further oil de-escalation + a real-yield roll-over lights this fully* |

**Count: 4 ✅ (gates 3, 4, 8 = [V]; gate 6 = [T]).** Unchanged from this morning. **Gate 9 upgraded ❌→⚠️** — one step from lighting, but ⚠️ does not count. Three [V] + one [T].

**Companion Flying Rocket (computed, Hard Rule 5):** FR composite ≈ **1/20, 0 unlock gates** — euphoria 0 (Extreme Fear F&G 22), momentum 0 (RSI 38.47), valuation-extreme 0 (−48.8%), distribution 1 (ETF outflows), structural 0; phase-of-cycle hard cap (>20% below 1-yr high → cap 8) **binding.** Per the vacuity rule: **cross-validation structurally consistent (cap-bound; both-≥12 unfalsifiable by construction).** FR 1 < 9 → no watch tripwire, no standalone FR report triggered.

---

## 5. Probability Matrix — Derived From Score (Adjusted 12 → 11–14 band)

Baseline 11–14: Rally 30 / Range 35 / Retest 22 / Bear 13. **Trend residual — the downtrend residual is LIFTED this report:** BTC reclaimed the $62,868 200-week intraday (+2.60%) and printed a higher high on the soft-CPI relief, so the §5 "apply the mirror toward Rally when the trend repairs" clause applies — a *modest* +4 Rally shift (from Retest −2, Bear −2), held small because the reclaim is intraday-only (Sunday's weekly close is the real test) and the ETF bid + oil/Iran remain unresolved. Canonical $64,500.

| Scenario | Probability | Target Range | Key Trigger |
|---|---|---|---|
| **Rally** | **34%** | $65,000 – $71,000 (mid $68,000) | A weekly-confirmed 200-week reclaim; ETF flows flip green ≥5 sessions; oil de-escalates further |
| **Range** | **35%** | $62,000 – $65,000 (mid $63,500) | Chops around the reclaimed 200-week while the ETF bid and oil resolve; no directional break |
| **Retest** | **20%** | $58,000 – $62,000 (mid $60,000) | Hot PPI revives the rate scare; the ETF exodus resumes; a weekly close back below $62,868 |
| **Bear** | **11%** | $50,000 – $58,000 (mid $54,000) | Forced liquidation through $58K on an oil re-escalation into a hawkish Fed |

Sum 100%. **Weighted EV = $63,285** (`tools/compute.mjs ev`). Spot ~$64,500 → **EV-vs-spot ≈ −1.88%.** **Realized trailing-2-week: ≈ +6.1%** (~$60.8K late-Jun → $64,500). The positive realized tape **contradicts** the negative EV: the relief rally priced the edge out — the remaining edge lives in the $58–62K retest ladder, not at spot. **Do not chase.**

**Sum-check (mandatory):** 34+35+20+11 = 100 ✅; EV recomputed from printed cells = **$63,285**, matches (Δ 0%) ✅. Rally 34% < modal Range 35% and ≤50% ✅ (the intraday 200-week reclaim + higher high is the cited trend-structure event permitting the above-baseline Rally weight; it stays below modal because the reclaim is not weekly-confirmed).
**EV-floor consistency:** binds only at score ≥15 + extreme fear → **N/A** (score 12). Clean.
**Terminal-vs-extreme reconciliation:** modal = **Range** ($62–65K); the §5 residual now leans mildly toward Rally (trend repairing), so the path EXTREME points UP toward the **$65,000–71,000 Rally band** — but Range expresses the terminal expectation and does not claim the fear cycle is resolved (collar engaged). Post-CPI: PPI (Jul-15) is the next in-window tier-1.

---

## 6. Deployment Strategy — BTC

**Confirmed deployed: 10% (Phase 1A @ ~$65K, MTM −0.77% at $64,500 — recovered from −3.30% this morning). Dry powder: 90%.** Splits 10 / 15 / 30 / 45.

**⚑ Deep-Value Override status: NOT ARMED.** Score 12 < 15; 3-day F&G 25.33 > 15; **and price is rising, not making fresh lower-lows** — the override's price condition (trailing-low ≥8% below basis + a fresh lower-low) is not met and is moving *away*. No firing, no near-fire.

| Phase | Size | Trigger / Gates (denominator 9) | Status |
|---|---|---|---|
| **1A** | 10% | score ≥10 + ≥3/9 gates (≥2 [V]) | **DEPLOYED ~$65K** (MTM −0.77%) |
| **1B** | 15% | score ≥13 + ≥5/9 gates (≥3 [V]) OR Override | **SCORE-BLOCKED (12 < 13)** — zone $58,000–61,500, half-size on re-qualification + a gate-9 relight |
| **2 — Conviction** | 30% | score ≥15 + ≥6/9 (≥3 [V]) + corr <0.8 | **FROZEN** (score 12) |
| **3 — Generational** | 45% | score ≥17 + ≥7/9 (≥4 [V]) OR (Override + weekly capitulation candle) | **DRY POWDER** |

At 12, Phase 1B is **SCORE-blocked** (needs ≥13). And the honest structural read: **the relief rally makes a rise to 13 harder, not easier.** The path to 13 runs through a *new* capitulation signal (a long-liquidation flush or a sustained negative-funding flip), and today's tape delivered the opposite — a short squeeze into a firming market. As fear lifts, the sentiment and momentum legs erode. **HOLD 1A, no add, no chase.** The rally is good for the held position's MTM; it is not a buy signal.

**Stop Philosophy.** Compound thesis stop: **≥2 consecutive weekly closes below $55,000 AND score back below 12.** Time stop: reassess Phase 1A if the thesis hasn't worked through Q3 2026. Narrative-break exit independent.

**Stop Migration Ledger:** **no migration this report** — all parameters UNCHANGED (compound $55K/score<12; catastrophic $50K; deepest-zone floor $54K). The 1A MTM improving from −3.30% → −0.77% is a *market move*, not a stop change (distance from spot to the compound $55K line widened from +14% to +17%).

**Stop-vs-buy-zone coherence check (mandatory):** deepest named buy-zone floor = **$54,000** (the 1B/contingency band). Catastrophic **$50,000 < $54,000 → PASS** (`tools/compute.mjs stop-coherence`). The compound line $55K sits *inside* deeper zones by design (it cannot fire on price alone — requires score <12 too). Max-drawdown-to-thesis-stop from the ~$65K 1A basis: at $55K = −15.4%.

**Checkpoint (structural, gate 6):** **Sun Jul-19** — the Jul-13→Jul-19 weekly candle closes Sun 24:00 UTC (crypto trades 24/7; a real close). Gate 6 holds iff the weekly close is within ±8% of $62,868 (currently +2.60%, well inside). The structural read (weekly-confirmed reclaim vs rejection) turns on whether the close holds above $62,868 — spot is **+2.60% above ($1,632 = 1.10× the $1,480 5-day ADR)**. **Next tier-1 before the checkpoint: PPI (Wed Jul-15, 8:30 ET)** — a soft PPI confirms the CPI disinflation (supportive of a held reclaim); a hot PPI revives the rate scare (hostile). No likelihood adjective attached — an unpriced tier-1 (PPI) sits between report and checkpoint.

**Dry powder yield benchmark:** ~4.3% (3-month T-bill) / ~4.5% (USDC). 90% dry earns the benchmark.

---

## 7. Exit / Trim Framework — BTC

Track cost basis per phase; trims LIFO. Local campaign peak = highest adjusted score since first fill = **16**; now 12 → drop of **−4** (a further −2 fade to ≤10 arms the ≥6-point 25% trim).

| Trigger | Status |
|---|---|
| Score drops ≥6 from local peak (16) | **No** (−4; arms at score ≤10) |
| F&G ≥75 7d AND weekly RSI >70 | **No** (Extreme Fear; RSI 38) |
| MVRV-Z >3 or drawdown <10% + vertical | **No** (MVRV-Z 0.35; −48.8%) |
| Score ≤3 AND price ≥40% above cost | **No** (score 12; MTM −0.8%) |
| ETF outflows ≥3% AUM after a sustained-inflow regime | **No** (no prior ≥5-session inflow regime this campaign — the one green week never met the bar) |
| Narrative break | **No** |

**Status: no trim. Remaining position 10% (Phase 1A).**

---

## 8. Critical Watchlist — BTC

| Time (EST) | Event | Impact |
|---|---|---|
| **Tue Jul 14, 8:30 ✓** | **June CPI — SOFT (−0.4%/3.5% headline, flat/2.6% core)** | **Resolved bullish.** Hike scare collapsed; relief rally |
| Tue Jul 14 (tonight) | Jul-14 BTC ETF daily flow | First read on whether the CPI relief pulled the institutional bid back |
| **Wed Jul 15, 8:30** | **June PPI** | Confirms/denies the CPI disinflation; the last in-window tier-1 |
| **Sun Jul 19** | **Weekly close vs $62,868 200-week** | The real gate-6 test — intraday proximity ≠ a held weekly reclaim |
| Jul 28–29 | FOMC (Jul 29, 2:00 pm) — now Fed-hold ~85% | Post-window rate-path event |
| ~end-July | CLARITY Act text / Senate action | Regulatory clarity (gate-9 adjacent) |

**Tier-1 calendar lock:** in-window tier-1 = **CPI (Jul-14, released — soft), PPI (Jul-15)**; PCE (~Jul 30) + FOMC (Jul 28–29) outside the 5-day window; next NFP early August. CPI enumerated and now realized; PPI is the one remaining in-window tier-1.

---

## 9. Bull vs Bear Scorecard — BTC

**Bull:**
1. ✅ Soft June CPI (−0.4%/3.5% headline, flat/2.6% core) collapsed the July-hike scare (Fed-hold ~85%) — the dominant hostile catalyst cleared
2. ✅ BTC reclaimed the $62,868 200-week intraday (+2.60%) with a higher high
3. ✅ MVRV-Z 0.35 — deep-value, ~23% above realized cost basis
4. ✅ LTH accumulating; exchange reserves at a 7-year low — structural floor intact
5. ✅ Today's flush was 93% SHORTS ($56M short liq) — no long capitulation; funding never flipped negative
6. ✅ Trump withdrew the 20% Hormuz toll — the acute oil tail-risk eased

**Bear:**
1. ❌ ETF bid unconfirmed — the "green week" reversed (Jul-13 −$424.63M; MTD negative), and tonight's flow is still pending
2. ❌ The move is a short-squeeze relief bounce (Principle 3: bounces within a downtrend are suspect); no weekly-confirmed reclaim
3. ❌ Real yields still 2.32% cycle high; oil still ~$84 with an active 3rd-day Iran conflict
4. ❌ Score pinned at 12 and the rally makes a rise to 13 *structurally harder* — the fear that powers the accumulation edge is lifting
5. ❌ Coinbase premium negative ~55 days (record) — persistent weak US spot demand

**Net: 6 bull / 5 bear — bull-leaning but within 1 of balanced.** With |EV-vs-spot| 1.88% < 2%, the **Verdict-Confidence Collar is engaged**: no regime-resolution claims.

---

## 10. Change Log — BTC (vs Jul 14, 8:45 AM)

| Factor | Previous (8:45 AM) | Current (2:30 PM) | Direction |
|---|---|---|---|
| Canonical spot | ~$62,855 | ~$64,500 (median live cluster) | ↑ +2.6% (post-CPI squeeze) |
| Adjusted score | 12 | 12 | → |
| **June CPI** | pending, event-conditional | **SOFT: −0.4%/3.5% headline, flat/2.6% core** | ↓ inflation (bullish resolution) |
| **July-hike odds** | ~42–50% | **collapsed → Fed-hold ~85.6%** | ↓↓ **hostile catalyst cleared — biggest change** |
| Sentiment 3d avg | 25.33 (spot 22) | 25.33 (spot 22, pre-rally print) | → (leg 2) |
| Weekly RSI | 38.47 (live 38.68) | 38.47 (live 38.68) | → |
| 200-week vs spot | −0.02% (on the line) | +2.60% (reclaimed intraday) | ↑ firmer reclaim |
| Brent | $86.61 | $84.39 (Hormuz toll withdrawn) | ↓ eased (not resolved) |
| VIX | 17.52 | 16.40 | ↓ risk-on |
| 24h liquidations | $283M (74% long) | $60.21M/hr post-CPI, **93% SHORT** | flipped to a short squeeze |
| MVRV-Z | 0.37 | 0.35 (Jul-10 source) | → deep value |
| ETF flows | Jul-13 −$424.63M; MTD ~−$300M | same; Jul-14 pending | → (unconfirmed) |
| Gate 9 (macro) | ❌ | ⚠️ (upgraded) | ↑ improving |
| Stops | $55K/<12 · $50K cat · $54K floor | identical | → no migration |
| Companion FR | 1/20, cap-bound | 1/20, cap-bound | → |

---

## 11. Strategic Verdict — BTC

**Adjusted score 12/20 · Weighted EV $63,285 · EV-vs-spot −1.88% · sentiment 3-day F&G 25.33 (Extreme Fear, spot 22) · stance: HOLD Phase 1A (10%), 90% dry, no chase.**

The morning's coin-flip resolved to the bull side of the print, and it resolved cleanly: June inflation fell four-tenths on the month with core flat, the July-hike scare that had traders pricing a one-in-two chance of tightening collapsed to a Fed-hold in the mid-eighties, and Bitcoin squeezed four percent higher to reclaim its two-hundred-week line intraday. On the surface that is everything the accumulation thesis wanted. Underneath, the number on the scorecard did not move, and the reason it did not move is the whole discipline of this framework: the score is a *fear* gauge, and today drained fear rather than deepening it. The daily reading was fixed at twenty-two before the rally, the flush that accompanied the move was short-sellers being carried out rather than longs capitulating, and every path to the thirteen that would unlock Phase 1B runs *through* a capitulation signal that a relief rally makes less likely, not more. A higher price on soft CPI is good for the tranche I already hold; it is not a new reason to buy.

So this stays a hold, and the posture is unchanged and correct. I am not adding — 1B is score-blocked at twelve, and chasing a short-squeeze reclaim on the afternoon of the print is precisely the bull-trap the framework was rebuilt to refuse after May 14. I am not trimming — the score is four points off its peak of sixteen, the position is now essentially flat, and the on-chain base never cracked: MVRV is still deep-value at 0.35, holders are still accumulating, reserves are still at a seven-year low. The two things I actually want to see confirm are downstream of today: tonight's ETF flow, which tells me whether soft CPI pulled the institutional bid back after a week where the "green week" reversed in one Monday session; and Sunday's weekly close, which tells me whether the two-hundred-week reclaim is real or a Tuesday-afternoon poke.

The expected value at spot is mildly negative, the scorecard is a hair better than balanced, and the collar is engaged — I will not call the fear window closed on one soft print and a squeeze. Real yields are still at cycle highs, an active Iran conflict still sits under the oil price, and a bounce inside a downtrend is suspect until the trend structure repairs on a weekly basis. The honest read: a genuine, welcome relief that improved the macro backdrop and the held position's mark, sitting on top of a structurally cheap asset whose institutional bid and long-horizon trend both remain unconfirmed.

**Action items:**
1. **HOLD Phase 1A (10%, ~$65K, MTM −0.8%).** No add (1B score-blocked at 12<13; the rally makes 13 *harder*). No trim (−4 from peak; arms at −6). 90% dry.
2. **Do not chase the reclaim.** The EV edge is negative at spot (−1.88%) and lives in the $58–62K retest ladder, not here.
3. **Watch tonight's Jul-14 ETF daily flow** — a green print is the first evidence the CPI relief pulled the institutional bid back; another outflow keeps the score pinned.
4. **Sunday Jul-19 weekly close vs $62,868** is the gate-6 decider — a held weekly reclaim is bullish structure; an intraday poke that closes back below re-opens the retest ladder. PPI (Jul-15) sits between now and then.
5. Keep stops unchanged: compound **$55K/score<12**, catastrophic **$50K** (< $54K deepest-zone floor, PASS).

> **The Pattern:**
> **IF** tonight's ETF flow prints green **AND** BTC holds a Sunday weekly close above $62,868 **→ THEN** the two-hundred-week reclaim confirms and the $65–71K Rally band opens — but a durable turn still needs ≥5 green ETF sessions, not one.
> **IF** PPI comes in hot Wednesday **AND** the ETF exodus resumes **→ THEN** BTC loses the 200-week again on the weekly close and the $58–62K retest ladder is live — where the EV edge actually sits.
> **IF** the score fades two more points to ≤10 as fear keeps lifting **→ THEN** the ≥6-from-peak trim arms on the 10% Phase-1A tranche.

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-BTC-20260714-1430 | UNVERIFIED | crypto |
| 1B | FK-P1B-BTC-20260714-1430 | LOCKED | crypto |
| 2 | FK-P2-BTC-20260714-1430 | LOCKED | crypto |
| 3 | FK-P3-BTC-20260714-1430 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_fallen_knives_20260714_1430.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-07-14",
  "spot": { "value": 64500, "source": "median live 3-venue cluster Coinbase $64,501.82 / Binance $64,535 / CoinGecko $64,455 (fetch.mjs $64,475 corroborating), Jul 14 ~2:25 PM ET; spread 0.12%. CoinDesk $63,400 article-body figure excluded (stale, -1.7%); Yahoo $62,239 Jul-13 close excluded" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 4, "capitulation": 1, "holder": 3 },
    "raw": 12, "adjusted": 12, "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 4, 6, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 34, "low": 65000, "high": 71000 },
      { "name": "Range", "p": 35, "low": 62000, "high": 65000 },
      { "name": "Retest", "p": 20, "low": 58000, "high": 62000 },
      { "name": "Bear", "p": 11, "low": 50000, "high": 58000 }
    ],
    "stated_ev": 63285, "vs_spot_pct": -1.88
  },
  "deployment": {
    "deployed_pct": 10, "dry_pct": 90,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "~65000 (MTM -0.77%)" },
      { "phase": "1B", "pct": 15, "entry": "58000-61500 (score-blocked: 12<13; half-size on re-qualification + gate-9 relight)" },
      { "phase": "2", "pct": 30, "entry": "frozen" },
      { "phase": "3", "pct": 45, "entry": "dry" }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 }
  },
  "verdict": "HOLD Phase 1A (10%, ~$65K, MTM -0.77% at $64,500); 90% dry. Score 12 HELD post-CPI. June CPI came in SOFT (headline -0.4% MoM / 3.5% YoY vs -0.1%/3.8% cons; core flat / 2.6% YoY vs 0.2%/2.8%); July-FOMC hike odds collapsed ~42% -> Fed-hold ~85.6%. BTC squeezed +4.18% 24h to ~$64,500 (short squeeze: $56.27M short liq vs $3.94M long in 1hr), reclaiming the $62,868 200-week INTRADAY (+2.60%; Sun Jul-19 weekly close is the real test). Score UNCHANGED because the daily fear print (22) was fixed pre-rally and a relief rally drains fear rather than creating accumulation signal — the path to 13 runs through a capitulation signal the rally makes LESS likely. Gate 9 (macro) upgraded ❌->⚠️ (hike scare cleared; oil ~$84 + 3rd-day Iran conflict + real yields 2.32% cycle-high keep it short of a clean pass). On-chain base intact (MVRV-Z 0.35, LTH accumulating, reserves 7-yr low, funding no negative flip). ETF bid STILL unconfirmed (Jul-13 -$424.63M green-week reversal; Jul-14 pending). 1B SCORE-blocked (12<13). Override NOT ARMED (score 12<15; price rising, no fresh lower-low). EV -1.88% at spot vs +6.1% realized 2wk (bounce priced the edge out; edge lives in $58-62K ladder). No trim (peak 16->12=-4; arms at -6). Gates 4/9 (3,4,8 [V] + 6 [T]). Companion FR 1/20 cap-bound. Scorecard 6-5 + |EV|<2% -> Collar engaged; no regime-resolution claims.",
  "inputs": {
    "weekly_rsi": 38.47, "rsi_closes": 261, "rsi_source": "tools/fetch.mjs Wilder-14, Yahoo weekly, last completed week 2026-07-06; live-week 38.68",
    "mvrv_z": 0.35, "mvrv_ratio": 1.22, "realized_price": 52582, "fng_3d": 25.33, "fng_spot": 22, "fng_le15_streak": 0, "fng_cmc_context": 29,
    "drawdown_pct": -48.84, "sma_200w": 62868.19, "sma_200w_vs_spot_pct": 2.60, "adr5": 1480.43,
    "cpi_june": { "headline_mom": -0.4, "headline_yoy": 3.5, "core_mom": 0.0, "core_yoy": 2.6, "cons_headline_mom": -0.1, "cons_core_mom": 0.2, "verdict": "soft, both beat to the downside" },
    "july_hike_odds_post_cpi": "collapsed ~42% -> Fed-hold ~85.6%",
    "etf_btc_daily_jul13": -424630000, "etf_july_mtd": -300000000, "etf_ytd": -5400000000, "etf_jul14": "pending (after US close)", "etf_green_week_reversed": true, "coinbase_premium": "negative ~55d record, shallow",
    "liquidations_1hr_post_cpi": 60210000, "liquidations_short": 56270000, "liquidations_long": 3940000, "funding": "neutral-to-positive, no negative flip (exact JS-gated NOT FOUND)",
    "lth_30d": "rising ~50-100K BTC accumulation", "exchange_reserves": "falling, ~7yr low ~2.40-2.43M",
    "iran_oil": "Trump WITHDREW the 20% Hormuz toll (replaced with Gulf trade deals); fighting continued a 3rd day; Brent $84.39 (-2.6% off $86.61 AM)",
    "real_yield_10y_tips": 2.32, "vix": 16.40, "dxy": 100.92, "brent": 84.39, "spx": 7553.43, "ndx": 26163.91, "gold": 4067.2,
    "mstr_btc": 843775, "mstr_note": "no BTC change Jul 12-14", "clarity": "Senate back Jul-13, ~3 weeks, still stalled",
    "corr_spx_30d": "not computed this cycle -> risk-on surcharge OFF",
    "companion_fr": { "composite": 1, "gates": 0, "cap_bound": true }
  },
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_fallen_knives_20260714_1430.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "BTC",
      "report_date": "2026-07-14",
      "report_local_time": "14:30",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-BTC-20260714-1430",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260714_1430.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-07-14",
          "report_local_time": "14:30"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-BTC-20260714-1430",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260714_1430.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-07-14",
          "report_local_time": "14:30"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-BTC-20260714-1430",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260714_1430.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-07-14",
          "report_local_time": "14:30"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-BTC-20260714-1430",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260714_1430.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-07-14",
          "report_local_time": "14:30"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "btc_fallen_knives_20260714_1430.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "BTC",
    "report_date": "2026-07-14",
    "report_local_time": "14:30",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-BTC-20260714-1430",
      "FK-P1B-BTC-20260714-1430",
      "FK-P2-BTC-20260714-1430",
      "FK-P3-BTC-20260714-1430"
    ],
    "status": "REGISTERED"
  }
}
```
