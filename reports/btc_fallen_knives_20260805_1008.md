# 🔪 FALLEN KNIVES ANALYTICS — BTC — 2026-08-05

## WEDNESDAY MID-MORNING — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Wednesday, 2026-08-05, 10:08 EDT
### Asset: BTC | Prior Score: 11 mechanical / 10 adjusted (2026-08-03) | Current Score: 11 mechanical / 10 adjusted

---

## 1. What this report decides

Nothing new gets bought, and the reason is the same one that blocked the last report — only worse. The ledger has aged out of every usable band.

The market half of the answer is genuinely constructive relative to Friday: the risk backdrop has eased hard (VIX −17.96%, Brent −12.41%, S&P 500 at a record), spot ETF flows turned positive for two consecutive sessions, and BTC is pinned to its 200-week mean. The score did not move a single leg. What moved is everything around it — and BTC did not move with it. That non-participation is the report's central observation and it is priced through the discretionary term, not smuggled into prose.

---

## 2. Verified Live Data Points — BTC

### 2.1 Canonical spot reconciliation

| Source | Symbol | Price (USD) | Timestamp (UTC) | Status |
|---|---|---|---|---|
| Binance | BTCUSDT | 64,460.01 | 2026-08-05 14:06 | live |
| Kraken | XBTUSD | 64,404.70 | 2026-08-05 14:06 | live |
| Coinbase | BTC-USD | 64,404.16 | 2026-08-05 14:06 | live |
| CoinGecko | bitcoin | 64,311.00 | 2026-08-05 14:04 | live |
| Yahoo | BTC-USD | 64,420.87 | 2026-08-05 | **EXCLUDED — frozen bar close, never enters the median** |

**Canonical spot = $64,404.43** (median of 4 synchronized live quotes, all inside a 120-minute window). Inter-source spread **0.232%**, below the 0.5% bar — no dual-extreme EV computation required, no low-confidence demotion. Dispersion is venue microstructure, not time-skew: all four venue timestamps sit within ~2 minutes of each other.

Note on method: the SKILL mandates the **median**; `tools/fetch.mjs` also reports a priority-first canonical of $64,311.00 (CoinGecko). Delta −0.145%. It changes no band, no gate boolean, and no cap tier. Median used, as in every prior report in this series.

### 2.2 Sentiment (pinned provider: Alternative.me raw API daily series)

| Metric | Value | Source |
|---|---|---|
| F&G spot | **27** ("Fear") | Alternative.me, 2026-08-05 |
| F&G 3-day average | **26.67** | Alternative.me, prints 2026-08-03/04/05 (28 / 25 / 27) |
| Daily prints ≤15, consecutive | **0** | gate-1 streak input |
| Lowest of last 10 prints | 25 (2026-07-31, 2026-08-04) | — |
| F&G percentile vs trailing 2y | 32.58 | disclosed context |

Ten-print series (newest first): 27, 25, 28, 27, 27, 25, 28, 29, 29, 30. The range has been 25–30 for ten sessions. This is a *stable* fear reading, not a deepening one — and stability at 27 is not what a capitulation looks like.

No provider switch. No second-provider divergence ≥10 points to disclose.

### 2.3 Momentum — weekly RSI, and a toolchain artifact that had to be caught

| Input | Value |
|---|---|
| Weekly Wilder RSI-14 | **38.84** |
| Weekly-close source | Yahoo BTC-USD, 5y 1wk candles |
| Weekly boundary | Yahoo week-start timestamps, UTC |
| Period | 14 |
| Completed closes used | **261**, through the week ending Sunday 2026-08-02 |
| Confidence | ok (≥30 closes) |

**Disclosure — this number required a manual correction to the tool output, and it is worth stating plainly.** Yahoo is currently emitting an *extra* weekly bar for the live session (bars: 2026-07-20, 2026-07-27, 2026-08-03, 2026-08-05). `tools/fetch.mjs` drops only the final bar, so its "completed closes" set today includes the **in-progress** week beginning 2026-08-03 and returns 262 closes / RSI **39.73**. The last genuinely *completed* weekly close is the bar labelled 2026-07-27 (week ending 2026-08-02), giving 261 closes and RSI **38.84** — which reproduces this series' 2026-08-03 print exactly.

On BTC the artifact is harmless: 38.84 and 39.73 both land in the ≤40 → **2** band. On **gold** the same artifact would have moved a leg and degraded a live stop; see that report. Flagged here for the toolchain, not for the score.

`compute.mjs band fk-momentum 38.84` → **2**.

### 2.4 Valuation

| Metric | Value | As of | Source |
|---|---|---|---|
| MVRV-Z score | **0.3714** | 2026-08-04 | bitcoin-data.com `/v1/mvrv-zscore/last` |
| MVRV ratio | 1.2254 | 2026-08-04 | bitcoin-data.com `/v1/mvrv/last` |
| Implied realized price | ~$52,558 | derived: $64,404.43 / 1.2254 | — |
| Drawdown from ATH | −48.92% (ATH $126,080, 2025-10-06) | 2026-08-05 | CoinGecko |

Cross-provider scale check: Santiment `mvrv_usd_z_score` for bitcoin printed **0.3709** on 2026-07-06 against bitcoin-data.com's series on the same scale — two independent providers agreeing to within ~0.01 at the overlap. Recorded because the ETH companion report is forced onto the Santiment series and needs its scale corroborated somewhere.

`compute.mjs band fk-mvrv 0.3714` → **4** (≤0.5 band). Unchanged from 0.3469 on 2026-08-03.

### 2.5 On-chain, derivatives, flows

| Metric | Value | Source |
|---|---|---|
| Perp funding, mean per 8h | +0.01% (annualized **+5.85%**) | Binance fapi, 45 intervals / 16 sessions |
| Negative funding intervals in 45 | **0** (longest negative run: 0) | Binance fapi |
| Funding percentile vs available history | 78.44 | disclosed context, 167d history |
| BTC 24h liquidations | **~$7.54M** (longs $3.32M / shorts $4.22M) | CoinGlass via KuCoin, 2026-08-05 |
| Market-wide 24h liquidations | ~$78.70M (longs $42.65M / shorts $36.05M), 55,823 traders | CoinGlass, 2026-08-05 |
| LTH supply | Record high; strongest 30d accumulation in 6+ years; ~16.3M BTC | CryptoQuant / Glassnode via news.bitcoin.com |
| Exchange reserves | ~2.67M BTC, lowest since late 2023; −78K BTC over 6 months | CryptoQuant / The Block |
| ETF custodial holdings | ~1.3M BTC (~6.7% of supply) | Glassnode |
| Hash Ribbon | **MINER CAPITULATION** — 30d MA 898.06 EH/s < 60d MA 915.78 EH/s | **computed**, blockchain.info charts/hash-rate, 362 daily points, last 2026-08-04 |
| Hash Ribbon cross date | 30d crossed below 60d on **2026-06-08** (58 days) | computed, same series |

Hash Ribbon is computed fresh again this report, not carried. The gate-5 stale-input debt stays discharged. The 2026-08-03 report documented why this matters: a top web search result announcing a Hash Ribbon *recovery* was dated **August 2024**. The computed series says the opposite and still does.

### 2.6 Spot ETF flows (the [V] gate nearest to lighting, and it is moving away)

| Window | Net flow | Source |
|---|---|---|
| 2026-08-04 (Tue) | **+$211.5M** (IBIT +$170.3M, FBTC +$19.6M) — second consecutive inflow day | Farside via cryptonews.net |
| 2026-08-03 (Mon) | positive (second-day framing corroborated) | Farside via cryptonews.net |
| Week to 2026-07-31 | **−$61.53M** — ended a three-week inflow streak | Farside via COINOTAG / Bloomingbit |
| 2026-07-31 (Fri) | −$265.4M — largest single-day withdrawal since 2026-07-13 | Farside |
| July 2026 total | **+$172.4M** (net positive, broke a two-month outflow streak) | Farside |
| Total net assets (AUM) | **$76.29B**; cumulative net inflows since launch $51.32B | Farside via Bloomingbit |

**Gate 4 arithmetic, stated so it is not hand-waved:** the gate needs trailing-month outflows ≥2% of AUM = **≥$1.53B out**. July printed **+$172.4M in**. The gate is not near-miss; it is roughly $1.7B on the wrong side, and the last two sessions moved it further. Capitulation-(c) fails on the same evidence.

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
| 10y TIPS real yield | 2.43% | −0.01pp over 5 prints | FRED DFII10, 2026-08-03 |
| 3m T-bill (dry-powder benchmark) | **3.73%** | +1.97% | Yahoo ^IRX, 2026-08-05 (FRED DGS3MO cross-check 3.91%, Δ −0.18pp) |
| Fed funds target | 3.50–3.75%, held 9–3 on 2026-07-29 with three hawkish dissents | — | CME / CNBC |
| September FOMC (2026-09-16) hike probability | **~59–63%** | CME FedWatch via Reuters/Bloomberg coverage, 2026-08-05 | |
| CLARITY Act 2026 passage odds | **~28–37%** (down from 82% in February) | Polymarket via Yahoo Finance / cryptonews.com, 2026-08-05 | |

**Context Panel** (disclosed context only — never a scored leg, gate, threshold, size, stop or cap):

| Metric | Value | Percentile vs 2y |
|---|---|---|
| Realized vol 30d | 29.35% | **13.20** |
| Drawdown vs 2y high | 48.37% | 93.09 |
| Distance to 200dma | −8.87% | 43.42 |
| Weekly RSI-14 | — | 24.80 |
| Volume (last session) | $23.49B | 14.25 |
| Deribit DVOL | 34.73 | — |
| Deribit ATM IV (2026-08-28, 22.75d) | 31.24% | — |
| Deribit 90/110 moneyness skew | **+8.65%** (puts richer — downside hedging bid) | — |
| Variance risk premium | +1.89pp | — |
| Perp basis | −0.05% (annualized carry +5.85%) | — |
| Binance long/short account ratio | 1.2784, falling | 27.59 |
| Binance taker buy/sell | 1.093, rising | 86.21 |
| Net liquidity (FRED, weekly) | $5.83T | as of 2026-07-29 |
| HY OAS | 2.78% (−0.03pp/5 prints) | FRED, 2026-08-03 |
| NFCI | −0.529 | FRED, 2026-07-31 |
| Stablecoin aggregate supply | $183.10B, −0.59% 30d, **−3.43% 90d** | DefiLlama |

The panel's own contradiction is worth a line: realized 30-day vol at the **13th percentile** of two years, against a drawdown at the **93rd**. BTC is deeply repriced and extremely quiet at the same time. That is a coiled tape, not a capitulating one — and it is exactly why the fear gates are dark.

### 2.8 Correlation regime

| Metric | Value |
|---|---|
| 30d Pearson correlation vs SPX | **0.313** |
| Window | 2026-06-24 → 2026-08-05 (30 overlapping daily log-return pairs) |
| Method | Pearson on daily log returns, Yahoo BTC-USD vs ^GSPC closes, computed 2026-08-05 |
| Regime label | **mild** |
| Risk-on surcharge (>0.7) | **OFF** — not applied |
| Phase-2 corr condition (<0.8) | **PASS** on a computed number |

Because the surcharge is off, the D2 conviction path is not barred on correlation grounds.

### 2.9 Companion Flying Rocket score (Hard Rule 5, computed — not estimated)

`compute.mjs fr-companion`, same live data fetch:

- **Routing:** 48.95% below the 1-year high with a **falling** 200dma (−3.73%/20 sessions) → **Channel B — Bear Continuation**. Channel A's phase-of-cycle cap does **not** bind.
- **Channel B legs:** rally extension 1 (bounce +11.56%) · local momentum 2 (daily RSI 52.35, weekly RSI 39.73 < 50 so the qualifier does not void it) · resistance confluence 1 · bear structure 2 · relative sentiment 1.
- **Penalty:** 0 (squeeze tier none; bounce 35 sessions old so no maturity penalty). **Confidence: full**, no missing inputs.
- **FR composite: 7 / 20.** Channel B Phase 1A line is 13 — short by six.

**Cross-validation: consistent.** FK 11 (mechanical) / FR 7, inversely related, both below 12, so Hard Rule 5's both-≥12 condition is not met. The label is **unqualified and carries full evidentiary weight** — the Channel A cap is not binding, so the check is genuinely falsifiable rather than vacuous by construction.

**Standalone FR report: not owed on BTC** (companion 7 < 9; no FK phase-unlock threshold crossed; short-side liquidation volume $36.05M market-wide, far under the $100M trigger; cap not binding). **Separately outstanding and now re-fired:** the ETH standalone FR report. See §9.

---

## 3. Critical Developments

- **Coldcard exploit escalated, and it is still running.** Losses have grown from ~$114M on 2026-08-03 to **~$130M** (TechCrunch, 2026-08-04), across **five waves** and **5,200+ addresses**; the newest wave took 388.93 BTC (~$24.5M) from 462 addresses in 218 transactions. Root cause remains a March 2021 Coinkite firmware build that routed seed generation to a deterministic software PRNG instead of the STM32 hardware RNG, making five years of keys reproducible offline. Coinkite has **halted shipments and destroyed remaining affected inventory**. Researchers describe **at least a dozen distinct attacker groups**. Sources: CoinDesk 2026-07-31 / 2026-08-02, Bloomberg 2026-08-03, Fortune 2026-08-03, TechCrunch 2026-08-04, PYMNTS, TheHackerNews.
- **Equities at a record on Hormuz optimism plus earnings.** The S&P 500 closed at a record 7,737 Tuesday (+1.79%), its first record in two months, and extended again Wednesday to 7,785.30 — a fifth straight advance, the longest streak since early June. ~90% of reporting companies have beaten estimates; Q2 earnings growth tracking ~27% y/y. Sources: CNBC 2026-08-04/05, CNN, Axios, Bloomberg.
- **The Strait of Hormuz is still closed — the trade is on the *deal*, not the reopening.** Convoys move under naval escort; ~2 transits on 2026-08-02 against ~73/day normal. US and regional officials were "zeroing in" on an agreement 2026-08-04; Treasury Secretary Bessent said he expected a deal by midweek; Trump said the Strait would reopen "very soon" or Iran would be "hit very hard." Brent fell below $80. An MoU signed days earlier was followed by Iran re-closing the gateway. Sources: Washington Times 2026-08-03/04, Al Jazeera, Reuters/CNBC coverage.
- **CLARITY Act on a five-day clock.** 2026-08-10 begins the Senate state work period — the final date to vote the bill into law before recess; failure pushes it to mid-September. The bill still needs floor debate, amendments, and a 60-vote cloture threshold. Polymarket odds ~28–37%, down from an 82% February peak. Sources: Yahoo Finance, cryptonews.com, Bitcoin Foundation, CoinDesk.
- **Fed pricing roughly unchanged, composition shifted.** ~59–63% implied probability of a September hike (next FOMC 2026-09-16), with the oil collapse trimming inflation expectations and, with them, some tightening odds. Real 10y yield 2.43%.

---

## 4. Fallen Knives Composite Score — BTC

| Category | Max | Input (sourced) | Band logic | Score |
|---|---|---|---|---|
| **Sentiment Extreme** | 5 | F&G 3-day avg **26.67** (Alternative.me, pinned) | >25 → ≤35 band | **2** |
| **Momentum Exhaustion** | 4 | Weekly Wilder RSI-14 **38.84** (261 completed closes through week ending 2026-08-02) | ≤40 band | **2** |
| **Valuation** | 5 | MVRV-Z **0.3714** (bitcoin-data.com, 2026-08-04) | ≤0.5 band | **4** |
| **Capitulation Evidence** | 3 | (a) liquidations $7.54M — nowhere near top-decile/3σ ❌ · (b) 0 negative funding intervals in 45 ❌ · (c) ETF flows +$172.4M in July vs a −$1.53B bar ❌ | 0 of 3 | **0** |
| **Holder Behavior** | 3 | (a) LTH supply at a record, strongest 30d accumulation in 6+ years ✅ · (b) reserves −78K BTC/6mo, lowest since late 2023 ✅ | both | **3** |
| **Leg sum** | | | | **11** |

- **Leg sum: 11**
- **Mechanical score: 11** — `round(Σ legs)`, no discretionary term. *This is the number every protective rule reads.*
- **D1 discretionary term: −1.0** (see §9)
- **Raw composite: 10.0**
- **[V]-gate surcharge: none** (corr 0.313 < 0.7)
- **Adjusted score: 10** — `round(10.0)`, BTC convention half-**up**. *Read by deployment/unlock rules only.*

No leg moved from 2026-08-03. The composite has now printed mechanical 11 for five consecutive reports.

### Confirmation Gates — 3 of 9 (PoW: no N/A, denominator stays 9)

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | Sentiment ≤15 for ≥7 consecutive daily prints | ❌ | Streak = 0; the last 10 prints ran 25–30 and the lowest was 25. Needs a −40% collapse in the index from here, i.e. a genuine panic leg. **Reachable** — this gate lit at F&G 9 in June 2026 — but requires a shock, not drift. |
| 2 | [V] | Weekly RSI <30 | ❌ | 38.84. Needs roughly a sustained break below the $57,748 campaign low to drag the weekly series down ~9 RSI points. **Reachable on a real leg down.** |
| 3 | [V] | Valuation cheap (MVRV-Z <1) | ✅ | Lit at 0.3714. Would go dark above MVRV-Z 1.0, ~$64,400 × (2.0/1.2254) ≈ $105K — not a near-term risk. |
| 4 | [V] | ETF outflows ≥2% of AUM trailing month | ❌ | Needs **≥$1.53B** of trailing-month outflows against a July print of **+$172.4M**, with the last two sessions adding inflows. **Reachable but currently moving away at speed** — this is the nearest [V] gate and it is receding. |
| 5 | [T] | Hash Ribbon buy signal | ❌ | Computed: 30d MA 898.06 EH/s **below** 60d MA 915.78 EH/s — miner capitulation since 2026-06-08, no buy signal. Relights when the 30d crosses back **above** the 60d, i.e. hashrate recovery. Gap is ~2.0%; **reachable within weeks** if difficulty stabilizes. |
| 6 | [T] | Price within ±8% of the 200-week MA | ✅ | Lit: 200-week SMA **$63,772.47**, spot **+0.99%** above it. Would go dark below ~$58,670 or above ~$68,875. |
| 7 | [V] | Capitulation volume spike (top-decile trailing-90d liquidations or >3σ vs trailing-30d mean) | ❌ | $7.54M BTC / $78.70M market-wide in 24h. Needs an order-of-magnitude flush. **Reachable on a disorderly break of $57,748.** Standing watch item: this gate has now stayed dark through three consecutive legs down. |
| 8 | [V] | LTH accumulation / holder concentration stabilizing | ✅ | Lit — record LTH supply, strongest 30d accumulation in six years. Would go dark on a sustained LTH distribution impulse. |
| 9 | [T] | Macro catalyst neutral-to-positive | ⚠️ | **Genuinely mixed and upgraded from ❌.** Pro: Brent −12.41%, VIX −17.96%, HY OAS 2.78%, real Hormuz de-escalation *progress*, equities at a record. Con: the Strait is still closed (2 transits vs ~73/day), ~59–63% September hike priced, CLARITY on a five-day cliff at 28–37%. ⚠️ does not count. Relights on a signed-and-executed Hormuz reopening **plus** September hike odds below ~35%. |

**Passed: 3, 6, 8 → 3 of 9. [V] count: 2** (gates 3, 8).

`compute.mjs thresholds 9` → 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4).

Gate 9 moved ❌ → ⚠️ on fresh evidence; it counts the same (not at all). No gate carries a "none-in-regime" tag this report — every dark gate has a concrete, stated relight path. None of these disclosures is cited anywhere below to lower a threshold, reduce the denominator, or credit a gate.

**The shape of the board is the whole story.** Both lit [V] gates are *value* gates (3, 8). Every gate that measures *fear* — 1, 2, 4, 7 — is dark, and gate 4 is actively receding. BTC is a cheap market, not a frightened one, and the pyramid reserves its big tranches for frightened ones.

---

## 5. Probability Matrix — Score-Anchored, Analyst-Set (D4)

**Trend residual — stated as a boolean regardless of how the cells were set:**
> **Active downtrend (below a major MA AND making lower lows): NO.**
> Price is 8.87% below a falling 200dma (−3.73%/20 sessions) and the 50dma sits below the 200dma — the MA half is satisfied. But the lower-lows half is not: the campaign low **$57,747.77 (2026-07-01)** has held **35 sessions**, and the recent lows ascend rather than descend — $62,233.01 (Aug-1), $62,226.58 (Aug-3), $63,277.68 (Aug-4), $63,869.52 (Aug-5).
> **Consequence:** no bearish residual applied. The Deep-Value Override's **quarter-size throttle is OFF** (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless; see §6.

**D4 taken.** Adjusted score 10 → baseline anchor row 6–10 (Rally 20 / Range 35 / Retest 30 / Bear 15). Cells set from the read: Rally **+7**, Range **+2**, Retest **−7**, Bear **−2**. Every deviation is inside the ±10 percentage-point band, so no cell requires a >10pp reason line. The direction of the deviation is the eased risk backdrop (VIX −17.96%, Brent −12.41%, HY OAS 2.78%, a record equity tape), two consecutive positive ETF sessions, and a 35-session-old campaign low with ascending recent lows.

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | 27% | $66,500 – $71,000 | $68,750 | Hormuz reopening signed + soft NFP 2026-08-07 → September hike odds below 40%; ETF inflow streak extends past 5 sessions |
| **Range** | 37% | $62,000 – $66,500 | $64,250 | Status quo — 200-week mean holds as a pivot, realized vol stays at the 13th percentile, no macro resolution before CPI |
| **Retest** | 23% | $57,500 – $62,000 | $59,750 | Hot NFP → hike odds above 70%; CLARITY fails before the 2026-08-10 recess; Coldcard proceeds hit exchanges |
| **Bear** | 13% | $50,000 – $57,500 | $53,750 | Hormuz talks collapse and Brent re-rates above $100; a broad risk-off unwind of the melt-up |

**Sum: 27 + 37 + 23 + 13 = 100% ✅**

**Weighted EV recomputation from the printed cells (final step, flowing cells → EV, never the reverse):**
> 0.27 × 68,750 = 18,562.50
> 0.37 × 64,250 = 23,772.50
> 0.23 × 59,750 = 13,742.50
> 0.13 × 53,750 = 6,987.50
> **Σ = 63,065.00**

**Stated EV = $63,065.00. EV-vs-spot = −2.08%.** (`compute.mjs ev` returns 63065 / −2.08%; stated matches recomputed exactly.)

**Realized trailing 2-week price change: −2.58%** (vs 2026-07-22 close $66,100.80, Yahoo). The negative EV does **not** contradict realized momentum this time — both point the same direction, which is unusual for this series and worth noting: for most of May–June 2026 the EV read up while price fell.

**Rally cap check:** 27% ≤ 50% ✅, and it is not the modal cell.

**EV-floor consistency check:** requires EV-vs-spot negative **AND mechanical score ≥15 AND** 3-day F&G ≤15. Mechanical is **11**, F&G 3-day is **26.67**. **Not triggered** — no inconsistency flag.

**Terminal-vs-extreme reconciliation:** not required — the §5 trend residual is not live (active downtrend = NO).

---

## 6. Deployment Strategy — BTC

**Splits: 10 / 15 / 30 / 45.** Front-loaded pyramid.

### 6.1 Position & Performance (Hard Rule 8)

`node tools/position.mjs btc` → **exit 1, band EXPIRED.**

| Field | Value |
|---|---|
| File | `~/.trading-claude/exchange/position-snapshot.json` |
| Band | **EXPIRED** |
| Age | **5,654 minutes (94.2 hours)** — past the 4,320-minute (72h) expiry |
| Age driver | `holdings_as_of` (5,654 min); `generated_at` 5,652 min |
| Instruction returned | *"Cold start per Hard Rule 4, stated explicitly. The ledger is too old to be the position of record."* |

**Stated explicitly, as the rule requires: no fresh ledger was available, and this report proceeds as a cold start under Hard Rule 4.** The snapshot has now degraded across two reports — STALE at 50.2h on 2026-08-03, EXPIRED at 94.2h today.

**Position Reconciliation.** Prior reports in this series narrate *"10% Phase 1A at ~$65,000 blended,"* a figure that was retyped for weeks and has never been corroborated by a fill. The last readable (STALE) snapshot on 2026-08-03 showed **0.00000184 BTC**, `basis.reliable = false` on 5 unbacked disposals totalling 0.03360450 BTC, custody **RECONCILED** with zero withdrawals, and **zero deal tags** on two open deals. The ledger could neither confirm nor refute the narrated tranche then; it cannot be consulted at all now.

Consequences, applied strictly:
- The narrated 10% Phase 1A remains **UNVERIFIED**. It is not asserted as filled and not asserted as absent.
- **No cost basis, average cost, unrealized PnL or ROI is quoted.** The last reading of `basis.reliable` was false; nothing has happened since to repair it.
- **No realized-performance column is filled.** `performance_by_tag` was an empty array — zero tagged deals exist — so nothing is asserted about how Phase 1A entries have actually performed.
- **Real dry powder is unknown.** The last readable figure was $14,408.87 in stablecoins against a $19,790.26 portfolio, shared with the ETH and gold reports. At 94 hours it may not fill a ledger column, and no tranche is sized against it.
- **Dry-powder yield benchmark:** 3.73% (^IRX, 2026-08-05) — on a ~$14.4K stable balance that is roughly **$45/month** of opportunity cost. That is the price of not double-counting your own position, and it remains a cheap price.

### 6.2 Phase status

| Phase | Size | Zone | Score condition | Gate condition | Status |
|---|---|---|---|---|---|
| **1A** | 10% | **$63,000 – $66,500** | adjusted 10 ≥ 8 ✅ | 3/9 ≥ 3 ✅, [V] 2 ≥ 2 ✅ | **UNLOCKED — no fill authorized (data blocker)** |
| **1B** | 15% | $58,000 – $61,500 | adjusted 10 < 11 ❌ | 3/9 < 5 ❌, [V] 2 < 3 ❌ | **DOUBLE-BLOCKED** |
| **2** | 30% | $54,000 – $58,000 | adjusted 10 < 15 ❌ | 3/9 < 6 ❌ | **FROZEN** |
| **3** | 45% | requires a weekly capitulation candle | mechanical 11 < 17 ❌ | 3/9 < 7 ❌ | **DRY** |

**Phase 1A is genuinely unlocked and spot ($64,404.43) sits inside the zone.** It is not filled, for the third consecutive report, and the reason is a data defect rather than a market judgment:

> **Conditional authorization (carried forward, unchanged, executable on sight of a fresh snapshot):**
> **IF** `node tools/position.mjs btc` returns band **FRESH** (≤12h) **AND** shows Phase 1A unfilled or partially filled,
> **THEN** ladder up to 10% of book across **$63,000 – $66,500** in three clips — **$66,000 / $64,500 / $63,200** — laddering the full zone rather than the top of it, tag **`FK-P1A`**, note first line `report=reports/btc_fallen_knives_20260805_1008.md`.
> **ELSE** no fill. Deploying into the current ambiguity risks prohibited upsizing beyond nominal and corrupts the very attribution record needed to fix it.

**Phase 1B is double-blocked**, and it is worth being precise about which lock the D1 term controls. Mechanical 11 clears the ≥11 score line; the −1.0 removes that crossing (adjusted 10). But 1B is *independently* short **two gates** (3 of 5 required) and **one [V] gate** (2 of 3), so it was never deployable this report by any route. **The discretionary term has zero capital effect.** Stated so it can be graded honestly rather than credited with a restraint the gates already supplied.

**Deep-Value Override: DOES NOT FIRE.** Evaluated in full:
- Mechanical score 11 < 15 — **dispositive on its own**.
- 3-day F&G 26.67 is not ≤15.
- The Override presupposes a corroborated deployed tranche, which an EXPIRED ledger cannot supply.
No near-fire to log; no veto or throttle was reached.

**Ledger tag for any 1A fill: `FK-P1A`.** Applied via `PUT /api/investments/deal-note` with the dealKey, the tag, and a note whose first line is `report=reports/btc_fallen_knives_20260805_1008.md`.

**Non-mechanical capital: 0% of book.** No D1 cross, no D2 path, no Override firing has ever been executed on this asset. The 40% cap and the 25% Override sub-cap are both untouched.

### 6.3 Stops

**No stop parameter changed value this report.**

| Tier | Level | Condition |
|---|---|---|
| **Catastrophic floor** | **$50,000** | Placed strictly below the deepest defined buy zone |
| **Compound thesis stop** | **$55,000** price line + **mechanical** score line **<12** | Fires only on ≥2 consecutive weekly closes below $55,000 **AND** mechanical score <12 |
| **Time stop / checkpoint** | 2026-08-09 weekly close | See below |
| **D5 discretionary stops** | **none** | Zero analyst-channel tranches have ever been opened; the D1 term opened no position |

**Score axis status:** mechanical score is **11**, and the condition is score **<12**. 11 **is** <12, so the score axis **is satisfied** — the compound stop is effectively **price-gated at $55,000** until the composite re-crosses 12. This is carried state, unchanged since 2026-08-01. **The D1 −1.0 has zero effect on this line:** the compound stop reads the **mechanical** score per the 2026-07-27 governing rule, so a negative discretionary term cannot make the book's stop fire more readily than the evidence warrants, exactly as a positive one could not suppress it. That symmetry is the single most important application of "D1 buys entries, never exits," and it is checked here rather than assumed.

**Stop-vs-buy-zone coherence check (mandatory):**
> Deepest buy-zone floor named anywhere in this report: **$54,000** (Phase 2). Catastrophic stop **$50,000**.
> **CATASTROPHIC stop $50,000 strictly below deepest active buy-zone floor $54,000? → PASS.**
> (`compute.mjs stop-coherence --catastrophic 50000 --floor 54000` → `pass: true`.)
> The compound line ($55,000) is *not* the tested number — it may legitimately sit inside deeper zones because it cannot fire on price alone. No D5 lines exist to print separately.

**Max drawdown spot-to-compound-line: −14.60%** ($64,404.43 → $55,000). Disclosed as useful information; under the D6 ratchet it purchases no loosening whatsoever.

**D6 ratchet: compliant.** No parameter moved toward or away from price. No migration line is owed this report.

**Stop Migration Ledger: EMPTY this report.** The 2026-08-09 checkpoint set on 2026-08-03 has not yet resolved, so no forward roll applies.

**Checkpoint prognosis (calendar-locked):**
> **Checkpoint 2026-08-09** — a **Sunday**, which is a valid weekly-close boundary for a 24/7 venue; no restatement applied, and the date was computed and validated before any distance language.
> **Fires iff** ≥2 consecutive weekly closes below $55,000 **AND** mechanical score <12. Current consecutive closes below the line: **0**. The 2026-08-03 weekly close printed $64,055.95.
> Spot is **17.10% above** the line — **7.11× the 5-day ADR** of **$1,545.65** (mean |high−low| over 2026-07-31, 08-01, 08-02, 08-03, 08-04; the in-progress 2026-08-05 session is **EXCLUDED** as not a full session, disclosed inline, and the lookback extended one session to reach five full ones).
> **It structurally cannot fire on 2026-08-09** — zero of the two required closes exist, and one weekly close cannot supply two.
> **Tier-1 release before the checkpoint: YES — Nonfarm Payrolls, Friday 2026-08-07, 08:30 ET**, named here per the calendar-lock rule. Direction of effect: a hot print pushes September hike odds above the current ~59–63% and pressures the tape toward Retest; a soft print cuts them and supports Rally. NFP cannot produce two sub-$55,000 weekly closes by 2026-08-09, so the unfireable status is robust to it. Next tier-1 after: CPI, Wednesday 2026-08-12, 08:30 ET.

---

## 7. Exit / Trim Framework — BTC

**Every score condition below reads the MECHANICAL score (11), never the adjusted 10.** Discretion buys entries and never touches an exit.

| Trigger | Threshold | Current | Status |
|---|---|---|---|
| Mechanical score ≥6 points below local peak | Local peak this campaign: **13** (2026-07-11/12/16) | 11 → drop of **2** | ❌ not triggered |
| F&G ≥75 sustained 7d **AND** weekly RSI >70 | — | 26.67 / 38.84 | ❌ not remotely |
| MVRV-Z >3, or drawdown <10% with a vertical 30d return | — | 0.3714 / −48.92% | ❌ |
| Mechanical score ≤3 **AND** price ≥40% above blended cost | — | 11; no verifiable cost basis | ❌ |
| ETF outflows ≥3% AUM after a sustained inflow regime | Needs ≥$2.29B out | +$172.4M in (July) | ❌ |
| **Narrative break** (regulatory ban, founder fraud, critical security breach, irreparable tokenomics change) | — | **Coldcard exploit — evaluated in full below** | ❌ **NOT a break** |

### Narrative-break evaluation — Coldcard, re-tested on escalated evidence

The 2026-08-03 report determined this was not a §7 break at ~$114M. Losses are now ~$130M across five waves, with a dozen attacker groups and Coinkite destroying inventory. **The determination is unchanged, and here is why the escalation does not change it:**

Bitcoin's consensus rules, cryptography, issuance and settlement are untouched. Every affected key was **weak at generation** — which is precisely why the sweeps execute offline, with no network-level exploit. This is a single vendor's product defect, the same class of event as an exchange failure: severe for the users hit, not a change in what the asset *is*. The §7 trigger is written for "irreparable" and "thesis voided," and it is neither: Coinkite shipped emergency firmware, halted shipments, destroyed affected units, and the remedy (regenerate seed, move coins) is available to every holder.

What the event *does* do is impair the **interpretation** of the holder leg's cold-storage premise — which is why it is priced as a D1 term (§9) rather than an exit. Scaling with the loss total would be a category error: a bigger vendor failure is still a vendor failure.

**Current exit status: NONE. No trim executed, no exit triggered.** Remaining position: unverifiable (see §6.1), so no position size is asserted in either direction.

---

## 8. Critical Watchlist

| Date / Time (ET) | Event | BTC impact |
|---|---|---|
| **Fri 2026-08-07, 08:30** | **Nonfarm Payrolls (July Employment Situation)** — the only tier-1 US release in the next 5 trading sessions | **HIGH.** Hot → September hike odds above ~63%, pressure toward Retest. Soft → odds fall, supports Rally. Named in the checkpoint falsifier. |
| **Mon 2026-08-10** | **CLARITY Act — Senate state work period begins.** Final date to vote before recess; failure defers to mid-September | **HIGH, and specific to crypto.** Odds 28–37%. A binary that gate 9 cannot express and no leg scores. |
| Ongoing | Hormuz reopening agreement — signature and first convoy volumes | HIGH. Drives Brent, inflation expectations, and the Fed path. The Strait remains closed as of today. |
| Ongoing | Coldcard sweep waves 6+ and any attacker deposits to exchanges | MEDIUM. Directly governs the D1 falsifier. ~1,816+ BTC of zero-basis supply now sits with motivated sellers. |
| **Wed 2026-08-12, 08:30** | **CPI (July)** — first tier-1 beyond the 5-session window | HIGH. Oil at −12.41% should start showing in headline. |
| Thu 2026-08-13 | PPI | MEDIUM |
| Fri 2026-08-14 | Retail Sales | MEDIUM |
| **Tue–Wed 2026-09-15/16** | **FOMC decision** | HIGH. ~59–63% hike priced. |
| Daily | Spot ETF flows (Farside) | HIGH. Gate 4 is the nearest [V] gate and is currently receding. |

**Tier-1 calendar verification:** `compute.mjs tier1 --from 2026-08-05 --sessions 5` → window 2026-08-06 … 2026-08-12, returns exactly one tier-1 event (NFP 2026-08-07) and **zero warnings**. **This report is NOT an incomplete-data report on the calendar dimension.**

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

The most informative thing that happened since Friday is not on BTC's chart. It is on everyone else's.

The S&P 500 put on **+6.41% in five sessions** to a record. The Nasdaq did **+9.17%**. VIX fell **18%**. Brent collapsed **12.4%**. High-yield spreads tightened to 2.78%. That is not a rotation; that is a full-throated risk-appetite impulse, driven by ~90% earnings beats and a Hormuz reopening that the market is willing to trade before it exists.

Bitcoin, over the same five sessions, went from roughly $63,900 to $64,421. **Call it +0.8%.** At a 30-day correlation of 0.313, BTC does not owe equities a beta — but a market that is genuinely coiled and genuinely cheap ought to catch *some* bid when the risk premium collapses around it. It did not. It sat on its 200-week mean and did nothing, which is what it has done for 36 sessions.

I read that as the tape telling you something the five legs cannot: the marginal crypto buyer is absent for reasons that are not about the price of crypto. Two candidates, both dated and both sourced. The first is that self-custody itself is under a live attack — the Coldcard exploit has run from $38M to ~$130M in five days across five waves and a dozen attacker groups, and every wave that lands makes the "just take it off the exchange" reflex fractionally harder to execute in good conscience. The second is that the one regulatory catalyst the market actually cared about now has a five-day cliff: CLARITY needs Senate floor time, amendments, and 60 votes before 2026-08-10, and the market prices that at 28–37%.

Against all of that: the plumbing of a bottom is genuinely in place. MVRV-Z 0.3714 against a realized price near $52,558. LTH supply at a record with the strongest 30-day accumulation in six years. Exchange reserves at multi-year lows. A campaign low 35 sessions old with ascending recent lows. Price welded to the 200-week mean. Two consecutive sessions of positive ETF flow.

The synthesis, and it is the same one as Monday: **the plumbing of the bottom is in place and its psychology is absent.** F&G has printed 25–30 for ten straight sessions. Funding is positive at the 78th percentile with zero negative intervals in 45. Liquidations are $7.5M. Realized 30-day vol sits at the **13th percentile of two years** while drawdown sits at the **93rd**. Nobody is panicking; a lot of people have simply stopped caring. That is a real market state, and it is the one the pyramid's *small* tranche is for.

### 9.2 What the rubric structurally cannot see

1. **Cross-asset non-participation.** No leg scores relative performance. Correlation enters only as a >0.7 surcharge (0.313 — nowhere near). BTC's failure to join a record risk-on tape is invisible to the board. **Bearish.**
2. **Custody-channel risk.** The holder leg *measures* reserves falling and LTH rising, and both readings are correct — but its bullish *interpretation* assumes the destination is safer than the exchange. A firmware defect that made five years of keys reproducible offline is an argument about that assumption, not about the measurement. **Bearish.**
3. **Dated regulatory binaries.** Gate 9 is a single boolean; it cannot distinguish "no catalyst" from "an identified catalyst resolving in five days at 28–37%." **Bearish.**
4. **Vol-regime coiling.** Realized vol at the 13th percentile with a +8.65% put skew and a +1.89pp variance risk premium says the options market is paying up for downside into a quiet tape. Disclosed context only, not scored. **Mildly bearish.**
5. **Miner economics.** Gate 5 is a boolean on the Hash Ribbon cross; it cannot express that capitulation is 58 days old and the 30d/60d gap is only ~2%, i.e. close to resolving. **Mildly bullish, and not taken** — see below.

### 9.3 The D1 term — **−1.0** (negative)

**Value: −1.0. Direction: negative. Second consecutive report at this size** (decay clock: must be re-argued from fresh evidence or retired to 0 if still −1.0 after three).

The rule requires re-argument from *fresh* evidence, not restatement — so both factors are rebuilt here, and one of them was replaced outright because its original evidence weakened.

**Factor (i) — the Coldcard exploit, materially worse than on 2026-08-03.** Then: ~$114M, four waves, 5,200+ addresses. Now: **~$130M**, five waves, a fifth wave taking 388.93 BTC from 462 addresses in 218 transactions, **at least a dozen distinct attacker groups**, and Coinkite **halting shipments and destroying remaining affected inventory** — a vendor response that concedes the scale. This contaminates the *premise* of the holder leg's 3/3, half of which is "exchange reserves declining," a reading that is only bullish if the destination is safe. The leg's **measurement is unaffected and still scores 3**; the D1 addresses what the migration now *means*, which no leg or gate can express. Secondary: ~1,816+ BTC of zero-basis supply now sits with motivated sellers. Sources: TechCrunch 2026-08-04, CoinDesk 2026-08-02, Bloomberg 2026-08-03, Fortune 2026-08-03, PYMNTS.

**Factor (ii) — REPLACED, and I am flagging the replacement rather than burying it.** On 2026-08-03 factor (ii) was "gate 9 is binary and cannot express a 60.1%-priced September hike." That evidence has **weakened**: Brent is down 12.41%, VIX down 17.96%, HY OAS tightened to 2.78%, and the oil collapse has trimmed inflation expectations, with September hike odds roughly flat at ~59–63%. Honesty requires saying that the original factor no longer carries −0.5 of weight on its own.

It is replaced by a genuinely new one: **BTC's non-participation in a record equity melt-up.** SPX +6.41% and NDX +9.17% over five sessions against BTC +0.8%, at a computed 30-day correlation of 0.313. No leg scores cross-asset relative performance; the correlation input is a gate surcharge that only engages above 0.7. Reinforcing it, and dated: **CLARITY now faces a hard 2026-08-10 Senate deadline at 28–37% odds** — on 2026-08-03 this was a diffuse "27% passage odds for the year," and a five-day binary is a different object from a six-month one.

**Falsifier (dated):** retire the term when **either** (a) seven consecutive days pass with no new Coldcard sweep wave **and** no attacker deposits to exchanges observed on-chain, **or** (b) BTC closes above **$68,875** (the upper edge of the gate-6 ±8% band around the 200-week mean), which would demonstrate the participation whose absence is the second factor. **Hard review date: 2026-08-19.**

**Was a larger negative considered?** Yes, −1.5. **Declined.** Price welded to the 200-week mean at +0.99% for 36 sessions with the campaign low intact and ascending recent lows cuts the other way with real force, and the Coldcard event is a vendor defect with a shipped fix, not a protocol failure. Two sessions of positive ETF flow also argue against deepening the mark.

**Was a positive adjustment considered?** Yes, +0.5, built on the Hash Ribbon gap narrowing to ~2% (capitulation 58 days old and close to resolving) and the eased macro backdrop. **Declined on two grounds:** the Hash Ribbon is *already* gate 5's subject, so crediting its near-resolution is re-weighting something the board scores — prohibited double-counting; and the eased macro is the same evidence that weakened factor (ii), so counting it twice, once as a reduction and once as a bonus, would be arithmetic laundering.

**Effect of the term:** removes the Phase 1B score-line crossing (mechanical 11 ≥ 11 → adjusted 10 < 11). **Capital effect: NONE** — 1B is independently short two gates and one [V] gate.

### 9.4 Discretionary actions taken and declined

**D2 conviction path — UNAVAILABLE, on two independent grounds.** The path opens only at a shortfall of **exactly one** gate; Phase 1B is short **two** (3 of 5 required). And the [V] floor would fail on lit gates (2 lit vs 3 required), which D2 may never substitute for. Phase 1A needs no D2 — it is already unlocked mechanically.

**Deep-Value Override — evaluated, does not fire.** Mechanical 11 < 15 is dispositive; F&G 26.67 is not ≤15; and the Override presupposes a corroborated deployed tranche an EXPIRED ledger cannot supply. No near-fire, no veto reached, nothing to log.

**D4 — taken.** Cells set near the 6–10 anchor row, all deviations inside ±10pp. EV recomputed from the printed cells as the final step.

**The action I declined, and the one that matters: a Phase 1A fill.** The tranche is genuinely unlocked, spot sits inside the zone at $64,404.43, and the zone floor is $1,400 below. I declined it because the ledger cannot tell me whether 1A is already full, and deploying into that ambiguity risks prohibited upsizing beyond nominal while further corrupting the attribution record needed to fix it. **This is a data blocker with a one-command remedy, not a market judgment, and it should be graded as such.** It is now the third consecutive report blocked this way, and the snapshot has degraded from STALE to EXPIRED while the reports piled up. If the next calibration finds that BTC bottomed in this window, the cause will not have been the framework's caution — it will have been an un-refreshed JSON file.

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 (ETH) | Declined +1.0 that would have lifted ETH to 11 on discretion alone | — | — | ETH reaches 11 mechanically | **RETIRED — VINDICATED** (2026-08-03: reached 11 on sourced data) | n/a |
| 2026-08-03 | D1 (BTC) | **−1.0** on Coldcard premise-contamination + binary gate-9 macro | score-only, no position | none (opened no tranche) | 7 clean Coldcard days OR Sept hike odds <35% | **LIVE — re-argued 2026-08-05**, factor (ii) replaced | n/a |
| 2026-08-03 | D1 (BTC) | Declined −1.5 / −2.0 | — | — | — | RETIRED — declined | n/a |
| 2026-08-03 | — | Declined a Phase 1A fill on ledger ambiguity (STALE 50.2h) | 10% | — | fresh snapshot resolves 1A state | **LIVE — unresolved, ledger now EXPIRED** | n/a |
| **2026-08-05** | **D1 (BTC)** | **−1.0 held**, re-argued: Coldcard escalation to ~$130M/5 waves + BTC's non-participation in a +6.41% SPX melt-up + CLARITY's 2026-08-10 cliff | score-only, no position | none (opened no tranche) | 7 clean Coldcard days **OR** BTC closes >$68,875 | **LIVE** — hard review 2026-08-19 | n/a |
| **2026-08-05** | **D1 (BTC)** | Declined **+0.5** on Hash Ribbon near-resolution + eased macro | — | — | — | RETIRED — declined (double-counting gate 5; macro already netted) | n/a |
| **2026-08-05** | **D1 (BTC)** | Declined **−1.5** | — | — | — | RETIRED — declined | n/a |
| **2026-08-05** | — | Declined a Phase 1A fill — **third consecutive report**, ledger now EXPIRED at 94.2h | 10% | — | fresh snapshot resolves 1A state | **LIVE** | n/a |

No D1 or D2 tranche has ever been opened on BTC, so no D5 stop exists and no 10-day analyst-channel bar is running. Non-mechanical capital: **0%** of book.

### 9.6 What would change my mind

- **Bullish, dated:** a weekly close above **$68,875** — the top of the gate-6 band — which would simultaneously retire the D1's second factor and constitute the trend-structure repair the collar requires for strong claims. Or five consecutive sessions of net ETF inflows, which would begin repairing the flow picture even as it pushes gate 4 further away.
- **Bearish, dated:** a daily close below **$57,748** (the 2026-07-01 campaign low). That would flip the trend residual to an active downtrend, re-arm the Override's quarter-size throttle, and put gates 2 and 7 in play within weeks — the deep-fear leg this framework is built to buy. I would want it.
- **The genuinely decisive one is neither:** refresh `position-snapshot.json`. Every other question in this report is answerable and answered; that one is not, and it is the only one blocking capital.

---

## 10. Bull vs Bear Scorecard — BTC

**Bull (8):**
1. ✅ Price welded to the 200-week mean at **+0.99%** ($63,772.47) for 36 sessions — gate 6 lit
2. ✅ MVRV-Z **0.3714** against a realized price of ~$52,558 — gate 3 lit, cheap by the primary metric
3. ✅ LTH supply at a **record high**, strongest 30-day accumulation in six-plus years — gate 8 lit
4. ✅ Exchange reserves ~2.67M BTC, lowest since late 2023; −78K BTC over six months
5. ✅ ETF flows positive **two consecutive sessions** (+$211.5M on 2026-08-04, IBIT +$170.3M)
6. ✅ Campaign low **$57,747.77** has held **35 sessions**, with ascending recent lows (62,233 → 62,227 → 63,278 → 63,870)
7. ✅ Risk backdrop eased hard: VIX −17.96%, Brent −12.41%, HY OAS 2.78%, NFCI −0.529
8. ✅ Weekly RSI 38.84 — momentum still in the exhausted band, at the 24.8th percentile of two years

**Bear (11):**
1. ❌ **Every fear gate is dark** (1, 2, 4, 7) — both lit [V] gates are value gates
2. ❌ F&G 3-day 26.67; ≤15 streak **zero days**; prints stuck at 25–30 for ten sessions
3. ❌ Funding positive at the **78th percentile**, zero negative intervals in 45
4. ❌ 24h liquidations **$7.54M** BTC / $78.70M market-wide — no flush anywhere in sight
5. ❌ Hash Ribbon in **miner capitulation** since 2026-06-08; gate 5 dark, no buy signal
6. ❌ Coldcard exploit escalated to **~$130M**, five waves, a dozen attacker groups, shipments halted
7. ❌ **BTC did not participate** in a +6.41% SPX / +9.17% NDX five-session melt-up
8. ❌ CLARITY Act on a **five-day cliff** (2026-08-10) at 28–37% odds
9. ❌ ~59–63% September hike priced; real 10y yield 2.43%
10. ❌ 200dma falling −3.73%/20 sessions, price 8.87% beneath it, 50dma below 200dma
11. ❌ Realized 2-week change **−2.58%**; gate 4 receding as flows turn positive

**Net: 8 bull / 11 bear — bear by 3.**

---

## 11. Change Log vs 2026-08-03

| Factor | 2026-08-03 | 2026-08-05 | Direction |
|---|---|---|---|
| Spot | $63,843.94 | **$64,404.43** | +0.88% |
| Sentiment leg | 2 (F&G 3d 27.33) | 2 (F&G 3d 26.67) | flat |
| Momentum leg | 2 (wRSI 38.84) | 2 (wRSI 38.84) | flat |
| Valuation leg | 4 (MVRV-Z 0.3469) | 4 (MVRV-Z **0.3714**) | flat (band), value slightly richer |
| Capitulation leg | 0 | 0 | flat |
| Holder leg | 3 | 3 | flat |
| **Mechanical score** | **11** | **11** | **flat — 5th consecutive report** |
| D1 discretionary | −1.0 | **−1.0 (re-argued, factor ii replaced)** | flat in size, refreshed in evidence |
| **Adjusted score** | **10** | **10** | flat |
| Gates | 3/9 (3, 6, 8), [V] 2 | 3/9 (3, 6, 8), [V] 2 | flat — gate 9 ❌ → ⚠️ (no count change) |
| 200-week SMA | $63,549.42 (+0.43%) | **$63,772.47 (+0.99%)** | mean rising under price |
| Weighted EV | $62,870 (−1.53%) | **$63,065 (−2.08%)** | EV up in dollars, wider vs a higher spot |
| Realized 2-week | −2.12% | **−2.58%** | more negative |
| ETF flows | week −$61.53M, Jul-31 −$265.4M | **2 consecutive inflow days, +$211.5M Aug-4** | **improved — and gate 4 further away** |
| VIX / Brent / SPX | 15.81 / $83.77 / 7,602.56 | **16.95 / $79.48 / 7,785.30 (record)** | risk premium collapsed |
| Sept hike odds | 60.1% | ~59–63% | flat |
| CLARITY odds | 27% (diffuse) | **28–37% with a 2026-08-10 cliff** | now dated |
| Coldcard losses | ~$114M, 4 waves | **~$130M, 5 waves, shipments halted** | **worse** |
| Correlation vs SPX | 0.341 | **0.313** | flat, mild |
| FR companion | 6 (Channel B) | **7 (Channel B)** | +1, still short of every trigger |
| Position band | STALE (50.2h) | **EXPIRED (94.2h)** | **degraded — now a cold start** |
| Collar | ACTIVE (\|EV\| 1.53%) | **NOT triggered** (\|EV\| 2.08% ≥2, mech 11 outside 6–10, scorecard bear by 3) | released |

---

## 12. Strategic Verdict — BTC

**Adjusted score 10/20 · Mechanical 11/20 · D1 −1.0 · Weighted EV $63,065 · EV-vs-spot −2.08% · F&G 3-day 26.67 (Fear) · Stance: HOLD, Phase 1A unlocked but unfilled on a data blocker.**

Five reports now at mechanical 11, and not one leg has moved in the last two. That stability is itself the finding. Bitcoin is priced like a market that has been through something — MVRV-Z 0.37, realized price near $52.5K, LTH supply at an all-time high, reserves at multi-year lows, spot welded to a 200-week mean that is now rising underneath it. And it is *behaving* like a market nobody is thinking about: realized vol at the 13th percentile of two years, funding positive at the 78th, $7.5M of liquidations in a day, and a fear index that has printed between 25 and 30 for ten consecutive sessions. Cheap and quiet is a real state. It is the state the 10% tranche exists for, and emphatically not the state the 15/30/45 tranches exist for. Both lit [V] gates are value gates; every gate that measures fear is dark, and the closest one — ETF outflows — needs $1.53B of redemptions against a July that printed $172M of *inflows* and two green sessions on top.

The week's genuine new information came from outside crypto and it cut against us. The S&P added 6.41% in five sessions to a record, the Nasdaq 9.17%, VIX fell 18%, Brent fell 12.4% on a Hormuz deal the market is happy to trade before it is signed — and Bitcoin, at a 0.31 correlation and a 49% drawdown, went up 0.8%. I do not think a fear-accumulation framework should shrug at that. When the risk premium collapses around an asset that is genuinely cheap and the asset does not move, the marginal buyer is absent for reasons that are not about price. Two of those reasons are dated and sourced: self-custody itself is under a live and escalating attack, with the Coldcard exploit running from $38M to ~$130M in five days across five waves and a dozen groups; and the one regulatory catalyst that mattered now has a hard 2026-08-10 Senate cliff at 28–37% odds. That is what the −1.0 prices, and I have replaced the macro half of Monday's argument outright rather than restate it, because the macro half genuinely got better while the tape did not.

What actually blocks capital, though, is neither market nor macro, and this is the third consecutive report I have written that sentence. Phase 1A is legitimately unlocked — adjusted 10 ≥ 8, gates 3/9 ≥ 3, [V] 2 ≥ 2 — with spot inside the $63,000–66,500 zone and $1,400 of room to the floor. It is not filled because `position.mjs` has now aged past 94 hours into **EXPIRED**, the last readable snapshot showed dust with `basis.reliable = false` and zero deal tags against a narrated "10% at ~$65,000" that has never been checked against a fill, and deploying into that ambiguity risks upsizing beyond nominal while corrupting the attribution record needed to fix it. The fill is written below as an executable conditional rather than guessed. Dry powder earns 3.73% while it waits — about $45 a month on the last-known balance — and that is a cheap price for not double-counting your own position. But it is not free forever, and if this window turns out to have been the entry, the framework will not have been wrong; the file will have been stale.

### Action Items

1. **Refresh the position ledger — the single highest-value action in this report.** Export a new `position-snapshot.json` and re-run `node tools/position.mjs btc`. Nothing else in this report is unanswerable; this is.
2. **On a FRESH snapshot showing 1A unfilled, execute the conditional fill:** ladder up to 10% of book across **$63,000–66,500** in three clips at **$66,000 / $64,500 / $63,200**, tag **`FK-P1A`**, note first line `report=reports/btc_fallen_knives_20260805_1008.md`. Ladder the full zone. Do not fill at the top of it.
3. **Fix the basis defect while you are in there.** Five unbacked disposals totalling 0.03360450 BTC make cost basis, unrealized PnL and ROI unquotable. Gold's defect is smaller (1 disposal) and should be fixed first, but BTC's is second.
4. **Hold every stop where it is.** Catastrophic $50,000, compound $55,000 + mechanical score <12, checkpoint 2026-08-09. Coherence PASS. Nothing moved and nothing should.
5. **Watch NFP Friday 2026-08-07 08:30 ET** as the near-term driver of the September hike path, and **2026-08-10** as the CLARITY cliff. Neither can fire the checkpoint.
6. **Track the Coldcard waves against the D1 falsifier.** Seven clean days with no new wave and no attacker exchange deposits retires half the term. Hard review 2026-08-19.
7. **Do not authorize Phases 1B / 2 / 3.** 1B is short two gates and one [V] gate; 2 and 3 are score-blocked besides.

> **The Pattern**
>
> **IF** the ledger refreshes FRESH and shows Phase 1A unfilled with spot still inside $63,000–66,500 → **THEN** ladder the 10% tranche across the full zone in three clips, tag `FK-P1A`, and stop worrying about the entry — a 200-week mean with MVRV-Z at 0.37 and LTH supply at a record is a defensible place to own a first tranche.
>
> **IF** BTC closes below **$57,748** on a daily basis → **THEN** the trend residual flips to an active downtrend, the Override's quarter-size throttle arms, gates 2 and 7 come into play within weeks, and the framework finally gets the frightened market its big tranches are written for. That is not a threat to the thesis; it is the thesis.
>
> **IF** BTC closes above **$68,875** (the top of the gate-6 band) → **THEN** the D1's second factor is falsified by the participation whose absence created it, the term retires toward 0, and the accumulation window narrows rather than widens. Cheap does not last through a repricing.

---

### Immutable report-phase registry

| Phase | Canonical tag | Decision | Instrument class |
|---|---|---|---|
| 1A | FK-P1A-BTC-20260805-1008 | UNVERIFIED | crypto |
| 1B | FK-P1B-BTC-20260805-1008 | LOCKED | crypto |
| 2 | FK-P2-BTC-20260805-1008 | LOCKED | crypto |
| 3 | FK-P3-BTC-20260805-1008 | LOCKED | crypto |

Registry schema: report-phase-registry/1; version: 1; origin: btc_fallen_knives_20260805_1008.md (report-machine/1).
```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-08-05",
  "spot": { "value": 64404.43, "source": "median of 4 synchronized live quotes: Binance BTCUSDT $64,460.01 / Kraken XBTUSD $64,404.70 / Coinbase BTC-USD $64,404.16 / CoinGecko $64,311.00 (all 2026-08-05 ~14:06 UTC); spread 0.232%, all live, all venue timestamps within ~2 minutes; Yahoo BTC-USD $64,420.87 EXCLUDED as a frozen bar close. SKILL-mandated median used, NOT the tool's priority-first canonical ($64,311.00) — delta -0.145%, changes no band, gate boolean or cap tier" },
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
      { "name": "Rally", "p": 27, "low": 66500, "high": 71000 },
      { "name": "Range", "p": 37, "low": 62000, "high": 66500 },
      { "name": "Retest", "p": 23, "low": 57500, "high": 62000 },
      { "name": "Bear", "p": 13, "low": 50000, "high": 57500 }
    ],
    "stated_ev": 63065.00,
    "vs_spot_pct": -2.08
  },
  "deployment": {
    "deployed_pct": 0,
    "dry_pct": 100,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "63000-66500 zone, spot 64404.43 INSIDE it. Unlock conditions MET (adjusted 10>=8, gates 3/9>=3, [V] 2>=2). NO entry_price and NO fill authorized: position.mjs returns band EXPIRED at 94.2h (exit 1) so this report proceeds as a COLD START under Hard Rule 4, and the narrated '10% Phase 1A at ~65000' from prior reports remains UNVERIFIED in both directions. Deploying into that ambiguity risks prohibited upsizing beyond nominal. Conditional authorization written in section 6.2: IF a FRESH snapshot shows 1A unfilled THEN ladder 10% across 63000-66500 in three clips (66000/64500/63200), tag FK-P1A. THIRD consecutive report blocked on this same data defect; the snapshot degraded from STALE to EXPIRED in the interval", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "58000-61500 DOUBLE-BLOCKED: adjusted score 10<11 (the D1 -1.0 removes the crossing mechanical 11 makes) AND gates 3/9<5 with [V] 2<3. The D1 term has ZERO capital effect — 1B is independently short two gates and one [V] gate. D2 unavailable on two independent grounds: short by TWO gates not exactly one, and D2 substitutes for a gate never for a [V] floor", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "54000-58000 frozen (adjusted 10<15, gates 3/9<6)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17, gates 3/9<7)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 },
    "note": "NO stop parameter changed value. Mechanical score 11<12, so the compound stop's score axis IS satisfied — stop effectively price-gated at $55,000 until the composite re-crosses 12 (carried state, unchanged since 2026-08-01). The D1 -1.0 has ZERO effect on this line: the compound stop reads the MECHANICAL score per the 2026-07-27 governing rule, so a negative discretionary term cannot make the book's stop fire more readily than the evidence warrants, exactly as a positive one could not suppress it. CHECKED, not assumed. Coherence: catastrophic $50,000 strictly below deepest named zone floor $54,000 = PASS (compute.mjs stop-coherence pass:true). No D5 stops — zero analyst-channel tranches have ever been opened; the D1 term opened no position. Max drawdown spot-to-compound-line -14.60%, disclosed; purchases no loosening under D6. D6 ratchet: compliant, no parameter moved in either direction.",
    "migration": [],
    "checkpoint": {
      "date": "2026-08-09",
      "line": 55000,
      "condition": ">=2 consecutive weekly closes <55000 AND mechanical score <12",
      "closes_below": 0,
      "adr": 1545.65,
      "adr_sessions": "2026-07-31, 08-01, 08-02, 08-03, 08-04 — the in-progress 2026-08-05 session EXCLUDED as not a full session, lookback extended one session to reach five full ones, exclusion disclosed inline",
      "dist_x_adr": 7.11,
      "calendar_validation": "2026-08-09 is a Sunday, a valid weekly-close boundary for a 24/7 venue; no restatement applied; date computed and validated BEFORE any distance language",
      "side": "spot 17.10% above line; structurally cannot fire (0 of 2 required closes exist, and one weekly close cannot supply two). Tier-1 release BEFORE this checkpoint: YES — nonfarm payrolls Fri 2026-08-07 08:30 ET, named in the falsifier: hot print pushes Sept hike odds above the current ~59-63% and pressures the tape toward Retest; soft print cuts them and supports Rally. NFP cannot produce two sub-55000 weekly closes by 2026-08-09. Next tier-1 after: CPI Wed 2026-08-12 08:30 ET."
    }
  },
  "companion_fr": {
    "score": 7,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 48.95, "ma200_falling": true, "ma200_slope20_pct": -3.73, "price_below_ma200_pct": -8.87 },
    "legs_channel_b": { "rally_extension": 1, "local_momentum": 2, "resistance_confluence": 1, "bear_structure": 2, "relative_sentiment": 1 },
    "inputs": { "low_40s": 57747.77, "low_40s_date": "2026-07-01", "bounce_pct": 11.56, "daily_rsi14": 52.35, "weekly_rsi14": 39.73, "bounce_age_sessions": 35, "funding_annualized_pct": 5.85 },
    "counts_used": { "resistance_count": 1, "structure_count": 2, "sentiment_count": 1 },
    "counts_derivation": "resistance 1/4: (a) within 3% of 200dma FALSE (-8.87%), (b) within 3% of 50dma from below FALSE (price 1.87% ABOVE the 50dma), (c) price at/below a prior swing high that is itself a lower high TRUE (bounce high 65658.34 on 2026-07-27), (d) prior breakdown level FALSE. structure 2/3: (a) bounce high is a lower high TRUE, (b) 50dma below 200dma AND gap NOT narrowed FALSE (gap_narrowed_20=true), (c) no weekly close above the 200dma in 8 weeks TRUE. sentiment 1/3: (a) F&G 3d >=1.5x its 30d mean FALSE (26.67 vs a 25-30 band), (b) funding flipped positive after >=5 negative sessions FALSE (zero negative intervals in 45), (c) flow tell TRUE (ETF inflows resuming into the rally, two consecutive sessions, +$211.5M on 2026-08-04).",
    "gates_note": "Channel B Phase 1A line 13 — short by 6. Penalty 0 (squeeze tier none; bounce 35 sessions old so no maturity penalty). Confidence full, no missing inputs.",
    "cross_validation": "consistent — FK 11 (mechanical) / FR 7, inversely related, both <12 so Hard Rule 5's both->=12 condition is NOT met. Label UNQUALIFIED and carrying full evidentiary weight because the Channel A phase-of-cycle cap is NOT binding (Channel B is the live channel), so the both->=12 check is genuinely falsifiable rather than vacuous by construction.",
    "standalone_report_owed": false,
    "standalone_report_note": "Not owed on BTC (companion 7 < 9; no FK phase-unlock threshold crossed; short-side liquidation volume $36.05M market-wide, far under the $100M trigger; cap not binding). SEPARATELY OUTSTANDING AND RE-FIRED: the ETH standalone FR report — the ETH companion prints 9 again today, and the report owed since the 2026-08-01 ETH companion printed 9 has still NOT been discharged."
  },
  "position": {
    "source": "tools/position.mjs btc",
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
    "dry_powder_benchmark_source": "Yahoo ^IRX 2026-08-05; FRED DGS3MO cross-check 3.91%, delta -0.18pp",
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
      "portfolio_total_usd": 19790.26
    },
    "note": "EXIT 1 / EXPIRED at 94.2h — cold start per Hard Rule 4, stated explicitly. NO quantity, cost basis, PnL, ROI or dry-powder figure is asserted as current, and NO tranche is sized against the last readable snapshot. Position Reconciliation: prior reports narrate '10% Phase 1A at ~$65,000 blended' carried forward for weeks; the last readable (STALE) snapshot showed dust with basis.reliable=false on 5 unbacked disposals and zero deal tags, and could neither confirm nor refute it. The ledger cannot be consulted at all now. The narrated tranche is UNVERIFIED in BOTH directions — not confirmed, not refuted, and explicitly not read as flat. Degradation across reports: STALE 50.2h on 2026-08-03 -> EXPIRED 94.2h today."
  },
  "trend_residual": { "active_downtrend": false, "basis": "price is 8.87% below a falling 200dma (-3.73%/20 sessions) and the 50dma sits below the 200dma, so the MA half is satisfied — but NOT making lower lows: the campaign low $57,747.77 (2026-07-01) has held 35 sessions and recent lows ASCEND (62233.01 Aug-1, 62226.58 Aug-3, 63277.68 Aug-4, 63869.52 Aug-5)", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned. The Override cannot fire this report regardless: mechanical 11 < 15." },
  "correlation": { "value_30d_vs_spx": 0.313, "window": "2026-06-24 to 2026-08-05", "method": "Pearson on daily log returns, 30 overlapping return pairs, Yahoo BTC-USD vs ^GSPC closes, computed 2026-08-05", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.313 < 0.80)", "d2_availability_note": "surcharge OFF, so the D2 conviction path is NOT barred on correlation grounds — it is barred on gate arithmetic (short by two, and the [V] floor fails)" },
  "discretion": {
    "d1_taken": true,
    "d1_value": -1.0,
    "d1_direction": "negative",
    "d1_consecutive_reports_at_this_size": 2,
    "d1_decay_clock": "re-argue from fresh evidence or retire to 0 if still -1.0 after three consecutive reports; re-argued this report with factor (ii) REPLACED",
    "d1_factors": [
      "Coldcard exploit, MATERIALLY WORSE than on 2026-08-03: ~$114M/4 waves then, ~$130M/5 waves now, a fifth wave taking 388.93 BTC from 462 addresses in 218 transactions, at least a dozen distinct attacker groups, and Coinkite HALTING SHIPMENTS and DESTROYING remaining affected inventory. This contaminates the PREMISE of the holder leg's 3/3 — half that leg is 'exchange reserves declining', whose bullish reading depends on the destination being safe, and a March 2021 firmware build routed seed generation to a deterministic software PRNG instead of the STM32 hardware RNG, making five years of keys reproducible offline. The leg's MEASUREMENT is unaffected and still scores 3; the D1 addresses what the migration now MEANS, which no leg or gate can express. Secondary: ~1,816+ BTC of zero-basis supply now sits with motivated sellers. Sources: TechCrunch 2026-08-04, CoinDesk 2026-08-02, Bloomberg 2026-08-03, Fortune 2026-08-03, PYMNTS.",
      "REPLACED FACTOR (ii), flagged rather than buried: BTC's NON-PARTICIPATION in a record equity melt-up. SPX +6.41% and NDX +9.17% over five sessions to a record, VIX -17.96%, Brent -12.41%, HY OAS 2.78% — against BTC +0.8% over the same window, at a COMPUTED 30-day correlation of 0.313. No leg scores cross-asset relative performance and the correlation input is a gate surcharge that only engages above 0.7, so this is structurally invisible to the board. Reinforced by a DATED regulatory binary: the CLARITY Act now faces a hard 2026-08-10 Senate deadline (state work period begins; failure defers to mid-September) at 28-37% Polymarket odds, where on 2026-08-03 the same item was a diffuse '27% passage odds for the year'. The ORIGINAL factor (ii) — 'gate 9 is binary and cannot express a 60.1%-priced September hike' — is explicitly retired as WEAKENED: oil collapsed 12.41%, VIX fell 17.96%, credit tightened, and hike odds are roughly flat at ~59-63%. Honesty required saying the old evidence no longer carries -0.5 on its own."
    ],
    "d1_falsifier": "Retire when EITHER (a) seven consecutive days pass with no new Coldcard sweep wave AND no attacker deposits to exchanges observed on-chain, OR (b) BTC closes above $68,875 — the upper edge of the gate-6 +/-8% band around the 200-week mean — which would demonstrate the participation whose absence is the second factor. HARD REVIEW DATE 2026-08-19.",
    "d1_effect": "Removes the Phase 1B score-line crossing (mechanical 11 >= 11 becomes adjusted 10 < 11). CAPITAL EFFECT: NONE — 1B is independently short two gates (3/9 vs 5) and one [V] gate (2 vs 3), so it was never deployable this report by any route. Stated so the term is graded honestly rather than credited with a restraint the gates already supplied.",
    "d1_larger_considered_declined": "-1.5 DECLINED: price welded to the 200-week mean at +0.99% for 36 sessions with the campaign low intact and ascending recent lows cuts the other way with real force; the Coldcard event is a vendor defect with a shipped fix, not a protocol failure; and two consecutive sessions of positive ETF flow argue against deepening the mark.",
    "d1_positive_considered_declined": "+0.5 DECLINED on two grounds: it would be built on the Hash Ribbon gap narrowing to ~2% (capitulation 58 days old, close to resolving), which is ALREADY gate 5's subject — prohibited double-counting; and on the eased macro backdrop, which is the same evidence that weakened factor (ii), so counting it once as a reduction and again as a bonus would be arithmetic laundering.",
    "d2_available": false,
    "d2_taken": false,
    "d2_phase": "1B",
    "d2_detail": "UNAVAILABLE on two independent grounds: the path opens only at a shortfall of EXACTLY ONE gate and 1B is short TWO (3 of 5 required); and the [V] floor would fail on lit gates (2 lit vs 3 required), which D2 may never substitute for. Phase 1A needs no D2 — it is already unlocked mechanically.",
    "override_evaluated": true,
    "override_fired": false,
    "override_detail": "DOES NOT FIRE. Mechanical score 11 < 15 — dispositive on its own. Two further independent failures: 3-day F&G 26.67 is not <=15, and the Override presupposes a corroborated deployed tranche which an EXPIRED ledger cannot supply. No near-fire to log; no veto or throttle was reached.",
    "d4_taken": true,
    "d4_detail": "Cells set from the read against the 6-10 anchor row (adjusted score 10): Rally +7, Range +2, Retest -7, Bear -2 — all inside the +/-10 percentage-point band, none requiring a >10pp reason line. Direction of deviation: eased risk backdrop (VIX -17.96%, Brent -12.41%, HY OAS 2.78%, record equity tape), two consecutive positive ETF sessions, and a 35-session-old campaign low with ascending recent lows. EV recomputed from the printed cells as the final step.",
    "declined_action": "A PHASE 1A FILL was declined for the THIRD consecutive report despite the tranche being genuinely unlocked and spot sitting inside the zone $1,400 above its floor. Reason: the ledger cannot tell whether 1A is already full, and deploying into that ambiguity risks prohibited upsizing beyond nominal while corrupting the attribution record needed to fix it. This is a DATA blocker with a one-command remedy, not a market judgment, and should be graded as such. The snapshot degraded from STALE (50.2h) to EXPIRED (94.2h) across the interval.",
    "non_mechanical_capital_pct": 0
  },
  "narrative_break_evaluation": {
    "event": "Coldcard hardware wallet exploit — ~$130M drained across five waves from 5,200+ addresses since 2026-07-30; root cause a March 2021 Coinkite firmware build that routed seed generation to a deterministic software PRNG instead of the STM32 hardware RNG, making five years of keys reproducible offline. Coinkite has halted shipments and destroyed remaining affected inventory; at least a dozen distinct attacker groups identified.",
    "trigger_tested": "critical security breach (section 7, Exit 100%)",
    "determination": "NOT A NARRATIVE BREAK — no exit, no trim. Re-tested on escalated evidence and unchanged.",
    "reasoning": "Bitcoin's consensus rules, cryptography, issuance and settlement are untouched. Every affected key was weak AT GENERATION, which is why the sweeps execute offline with no network-level exploit. This is a single vendor's product defect — the same class of event as an exchange failure: severe for the users hit, not a change in what the asset IS. The section 7 trigger is written for 'irreparable' and 'thesis voided' and this is neither: Coinkite shipped emergency firmware, halted shipments, destroyed affected units, and the remedy (regenerate seed, move coins) is available to every holder. Scaling the determination with the loss total would be a category error — a bigger vendor failure is still a vendor failure. What it DOES do is impair the INTERPRETATION of the holder leg's cold-storage premise, which is why it is priced as a D1 term rather than an exit.",
    "sources": ["TechCrunch 2026-08-04", "CoinDesk 2026-07-31", "CoinDesk 2026-08-02", "Bloomberg 2026-08-03", "Fortune 2026-08-03", "PYMNTS 2026-08", "TheHackerNews 2026-08", "Benzinga 2026-08"]
  },
  "key_inputs": {
    "fng_spot": 27,
    "fng_3d_avg": 26.67,
    "fng_streak_le15_days": 0,
    "fng_lowest_of_last_10_prints": 25,
    "fng_last_10_prints": [27, 25, 28, 27, 27, 25, 28, 29, 29, 30],
    "fng_percentile_vs_2y": 32.58,
    "fng_provider": "alternative.me raw API daily series (pinned) — no provider switch, no second-provider divergence >=10 points to disclose",
    "weekly_rsi14": 38.84,
    "weekly_rsi_closes_used": 261,
    "weekly_rsi_boundary": "Yahoo weekly candles, week-start timestamps UTC; last COMPLETED weekly close = bar labelled 2026-07-27 (week ending Sunday 2026-08-02)",
    "weekly_rsi_confidence": "ok",
    "weekly_rsi_tool_artifact": "MANUAL CORRECTION APPLIED AND DISCLOSED. Yahoo is currently emitting an EXTRA weekly bar for the live session (bars 2026-07-20, 2026-07-27, 2026-08-03, 2026-08-05). tools/fetch.mjs drops only the FINAL bar, so its completed set today INCLUDES the in-progress week beginning 2026-08-03 and returns 262 closes / RSI 39.73. The last genuinely completed weekly close is the 2026-07-27 bar, giving 261 closes / RSI 38.84 — which reproduces this series' 2026-08-03 print exactly. On BTC the artifact is HARMLESS (38.84 and 39.73 both land in the <=40 -> band 2). On GOLD the same artifact would have moved a leg and degraded a live stop — see the gold report. Flagged for the toolchain, not for this score.",
    "weekly_rsi_percentile_vs_2y": 24.80,
    "daily_rsi14": 52.35,
    "sma_200w": 63772.47,
    "pct_vs_sma200w": 0.99,
    "gate6_within_8pct": true,
    "gate6_band": "58,670 to 68,875",
    "ma200d": 70653.97,
    "ma200d_slope20_pct": -3.73,
    "pct_vs_ma200d": -8.87,
    "ma50d": 63237.89,
    "pct_vs_ma50d": 1.87,
    "campaign_low": 57747.77,
    "campaign_low_date": "2026-07-01",
    "campaign_low_age_sessions": 35,
    "bounce_pct_off_low": 11.56,
    "recent_lows_ascending": [62233.01, 62226.58, 63277.68, 63869.52],
    "mvrv_z": 0.3714,
    "mvrv_z_asof": "2026-08-04",
    "mvrv_z_source": "bitcoin-data.com /v1/mvrv-zscore/last",
    "mvrv_ratio": 1.2254,
    "realized_price": 52558,
    "mvrv_z_cross_check": "Santiment mvrv_usd_z_score for bitcoin printed 0.3709 on 2026-07-06 against the bitcoin-data.com series on the same scale — two independent providers within ~0.01 at the overlap. Recorded because the ETH companion report is forced onto the Santiment series and needs its scale corroborated somewhere.",
    "drawdown_from_ath_pct": -48.92,
    "ath": 126080,
    "ath_date": "2025-10-06",
    "high_1y_pct_below": 48.95,
    "funding_ann_pct": 5.85,
    "funding_mean_per_8h_pct": 0.01,
    "funding_negative_intervals_in_45": 0,
    "funding_longest_negative_run": 0,
    "funding_percentile_vs_history": 78.44,
    "hash_ribbon_status": "MINER CAPITULATION — 30d hashrate MA 898.06 EH/s BELOW 60d MA 915.78 EH/s. COMPUTED from blockchain.info charts/hash-rate (362 daily points, last 2026-08-04). Gate 5 debt remains DISCHARGED; recomputed fresh this report, not carried.",
    "hash_ribbon_cross_date": "2026-06-08",
    "hash_ribbon_cross_age_days": 58,
    "hash_ribbon_gap_pct": 1.97,
    "hash_ribbon_trap_note": "CARRIED WARNING from 2026-08-03: a web search for Hash Ribbon status surfaces a Bitbo/Cointelegraph piece announcing Hash Ribbons had signaled an end to miner capitulation — the article is dated AUGUST 20, 2024. Scored off the headline, gate 5 would light on two-year-old data. The computed series says the opposite and still does.",
    "liquidations_btc_24h_usd_m": 7.54,
    "liquidations_btc_longs_usd_m": 3.32,
    "liquidations_btc_shorts_usd_m": 4.22,
    "liquidations_market_24h_usd_m": 78.70,
    "liquidations_market_longs_usd_m": 42.65,
    "liquidations_market_shorts_usd_m": 36.05,
    "liquidations_traders_affected": 55823,
    "liquidations_source": "CoinGlass via KuCoin, 2026-08-05",
    "lth_supply": "record high; ~16.3M BTC; strongest 30d accumulation in more than six years (CryptoQuant / Glassnode)",
    "exchange_reserves_trend": "~2.67M BTC, lowest since late 2023; -78,000 BTC over 6 months (CryptoQuant / The Block)",
    "etf_custodial_holdings_btc": 1300000,
    "etf_flow_aug04_usd_m": 211.5,
    "etf_flow_aug04_detail": "IBIT +$170.3M, FBTC +$19.6M; second consecutive inflow day",
    "etf_flow_week_to_jul31_usd_m": -61.53,
    "etf_flow_jul31_usd_m": -265.4,
    "etf_flow_july_usd_m": 172.4,
    "etf_total_net_assets_usd_b": 76.29,
    "etf_cumulative_net_inflows_usd_b": 51.32,
    "gate4_bar_usd_b": 1.53,
    "gate4_note": "Gate 4 needs trailing-month OUTFLOWS >= 2% of $76.29B AUM = >=$1.53B. July printed +$172.4M IN. Roughly $1.7B on the wrong side and the last two sessions moved it further. Capitulation-(c) fails on identical evidence. This is the nearest [V] gate and it is RECEDING.",
    "adr5": 1545.65,
    "adr5_sessions": "2026-07-31, 2026-08-01, 2026-08-02, 2026-08-03, 2026-08-04 — in-progress 2026-08-05 EXCLUDED as not a full session, lookback extended one session, exclusion disclosed",
    "realized_2w_change_pct": -2.58,
    "realized_2w_basis": "vs 2026-07-22 close $66,100.80 (Yahoo)",
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
    "gold_gcf": 4260.60,
    "gold_5session_change_pct": 5.61,
    "fed_funds_target": "3.50-3.75%, held 9-3 on 2026-07-29 with three hawkish dissents",
    "next_fomc": "2026-09-16",
    "sept_fomc_hike_probability_pct": "~59-63 (CME FedWatch via Reuters/Bloomberg coverage 2026-08-05; range across sources disclosed rather than a single point estimate)",
    "clarity_act_2026_passage_odds_pct": "28-37 (Polymarket via Yahoo Finance / cryptonews.com 2026-08-05), down from an 82% February peak",
    "clarity_act_deadline": "2026-08-10 — Senate state work period begins; final date to vote before recess; failure defers to mid-September. Bill still needs floor debate, amendments and 60-vote cloture.",
    "hormuz_status": "STILL CLOSED as of 2026-08-05 — ~2 transits on 2026-08-02 against ~73/day normal, convoys under naval escort. US and regional officials 'zeroing in' on a deal 2026-08-04; Bessent expected agreement by midweek; an earlier MoU was followed by Iran re-closing the gateway. The equity rally trades the DEAL, not the reopening. Sources: Washington Times 2026-08-03/04, Al Jazeera, CNBC.",
    "rv30_pct": 29.35,
    "rv30_percentile_vs_2y": 13.20,
    "rv10_pct": 27.89,
    "rv90_pct": 34.71,
    "drawdown_percentile_vs_2y": 93.09,
    "deribit_dvol": 34.73,
    "deribit_atm_iv_pct": 31.24,
    "deribit_expiry_used": "2026-08-28 (22.75 days out)",
    "deribit_skew_90_110_pct": 8.65,
    "deribit_skew_convention": "POSITIVE = the ~10% OTM put is richer than the ~10% OTM call = a downside hedging bid",
    "deribit_vrp_pct": 1.89,
    "perp_basis_pct": -0.05,
    "long_short_account_ratio": 1.2784,
    "long_short_account_ratio_percentile": 27.59,
    "taker_buy_sell_ratio": 1.093,
    "taker_buy_sell_percentile": 86.21,
    "net_liquidity_usd_t": 5.83,
    "net_liquidity_as_of": "2026-07-29",
    "hy_oas_pct": 2.78,
    "nfci": -0.529,
    "stablecoin_supply_usd_b": 183.10,
    "stablecoin_change_30d_pct": -0.59,
    "stablecoin_change_90d_pct": -3.43,
    "tier1_next_5_sessions": ["Nonfarm payrolls (July Employment Situation) Fri 2026-08-07 08:30 ET"],
    "tier1_window_verified": "compute.mjs tier1 --from 2026-08-05 --sessions 5 → window 2026-08-06..2026-08-12, returns exactly one tier-1 event (NFP 2026-08-07) and zero warnings. Report is NOT an incomplete-data report on the calendar dimension.",
    "tier1_beyond_window": ["CPI (July) Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14", "FOMC decision Wed 2026-09-16"],
    "non_tier1_dated_catalyst": "CLARITY Act Senate recess deadline Mon 2026-08-10 — not a macro release, but a dated binary named in the D1 and the watchlist",
    "stale_input_debt": []
  },
  "collar": {
    "band_triggered": false,
    "limbs_tested": ["|EV-vs-spot| 2.08% is NOT < 2%", "mechanical score 11 is NOT in the 6-10 band", "scorecard 8 bull / 11 bear is bear by 3, NOT within 1 of balanced"],
    "scorecard": "8 bull / 11 bear — net bearish by 3",
    "effect": "The collar is NOT triggered this report — the first time in this series it has released. That does not license loose language and none is used: every forward and regime-resolution claim in this report still carries a probability or an IF->THEN plus a named falsifier, per Analytical Principle 4, which applies unconditionally. No directional regime is declared resolved."
  },
  "verdict": "HOLD; Phase 1A genuinely UNLOCKED and genuinely UNFILLED, for the THIRD consecutive report, on a data blocker rather than a market judgment. Mechanical 11/20 — FIFTH consecutive report at 11, and NO LEG MOVED from 2026-08-03. D1 = -1.0 held but RE-ARGUED with factor (ii) REPLACED OUTRIGHT; adjusted 10/20. THE READ: the most informative event since Friday is not on BTC's chart. SPX +6.41% and NDX +9.17% over five sessions to a record, VIX -17.96%, Brent -12.41%, HY OAS 2.78% — and BTC went +0.8%, at a computed correlation of 0.313. A genuinely cheap, genuinely coiled market did not catch a bid when the risk premium collapsed around it. The plumbing of a bottom is in place and its psychology is absent: MVRV-Z 0.3714 against a realized price of ~$52,558, LTH supply at a record with the strongest 30d accumulation in six years, reserves at multi-year lows, spot welded to a RISING 200-week mean at +0.99% for 36 sessions, campaign low 35 sessions old with ASCENDING recent lows — against F&G stuck at 25-30 for ten sessions, funding positive at the 78th percentile with zero negative intervals in 45, $7.54M of BTC liquidations, and realized 30d vol at the 13th PERCENTILE of two years while drawdown sits at the 93rd. Value gates lit (3, 6, 8), every FEAR gate dark (1, 2, 4, 7), and gate 4 is receding: it needs $1.53B of outflows against a July that printed +$172.4M and two consecutive green sessions (+$211.5M on Aug-4). D1 RATIONALE, re-argued as the decay rule demands: (i) Coldcard escalated from ~$114M/4 waves to ~$130M/5 waves with a dozen attacker groups and Coinkite destroying inventory — contaminating the PREMISE of the holder leg's 3/3 while its MEASUREMENT still scores 3; (ii) the original gate-9 macro factor is RETIRED AS WEAKENED (oil -12.41%, VIX -17.96%, hike odds flat at ~59-63%) and REPLACED by BTC's non-participation in the melt-up plus CLARITY's hard 2026-08-10 Senate cliff at 28-37%. Declined +0.5 on the Hash Ribbon narrowing to a ~2% gap (double-counts gate 5) and -1.5 (the 200-week pin and ascending lows cut back hard). NARRATIVE-BREAK RE-TESTED on escalated evidence and UNCHANGED: a vendor firmware defect with a shipped fix does not void the asset thesis, and scaling that call with the loss total would be a category error. TOOLCHAIN CATCH: Yahoo is emitting an extra live weekly bar, so fetch.mjs's 'completed' set silently includes the IN-PROGRESS week; the corrected 261-close RSI is 38.84 (tool: 39.73). Harmless on BTC — both land in band 2 — but on GOLD the same artifact would have moved a leg and degraded a live stop. WHAT ACTUALLY BLOCKS CAPITAL: position.mjs returns EXIT 1 / EXPIRED at 94.2h, degraded from STALE 50.2h on Aug-03. Cold start per Hard Rule 4, stated explicitly. The narrated '10% at ~$65,000' is UNVERIFIED IN BOTH DIRECTIONS — not confirmed, not refuted, explicitly not read as flat — and no quantity, basis, PnL or dry-powder figure is asserted. Fill written as an executable conditional (ladder 10% across 63000-66500 at 66000/64500/63200, tag FK-P1A) rather than guessed. FR COMPANION 7/20 Channel B against a line of 13 — cross-validation consistent and UNQUALIFIED (cap not binding). The ETH standalone FR report remains OWED and UNDISCHARGED. Collar NOT triggered for the first time in this series (|EV| 2.08% >= 2, mechanical 11 outside 6-10, scorecard bear by 3) — and no directional regime is declared resolved anyway.",
  "tagging": {
    "mode": "phase_registry",
    "registry": {
      "schema": "report-phase-registry/1",
      "version": 1,
      "report_file": "btc_fallen_knives_20260805_1008.md",
      "report_version": "report-machine/1",
      "framework": "fallen_knives",
      "channel": null,
      "asset": "BTC",
      "report_date": "2026-08-05",
      "report_local_time": "10:08",
      "report_zone": "America/New_York",
      "instrument_class": "crypto",
      "entries": [
        {
          "phase": "1A",
          "canonical_tag": "FK-P1A-BTC-20260805-1008",
          "decision": "UNVERIFIED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        },
        {
          "phase": "1B",
          "canonical_tag": "FK-P1B-BTC-20260805-1008",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        },
        {
          "phase": "2",
          "canonical_tag": "FK-P2-BTC-20260805-1008",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        },
        {
          "phase": "3",
          "canonical_tag": "FK-P3-BTC-20260805-1008",
          "decision": "LOCKED",
          "instrument_class": "crypto",
          "report_file": "btc_fallen_knives_20260805_1008.md",
          "report_version": "report-machine/1",
          "asset": "BTC",
          "report_date": "2026-08-05",
          "report_local_time": "10:08"
        }
      ]
    },
    "instrument_class": "crypto",
    "report_file": "btc_fallen_knives_20260805_1008.md",
    "report_version": "report-machine/1",
    "framework": "fallen_knives",
    "channel": null,
    "report_asset": "BTC",
    "report_date": "2026-08-05",
    "report_local_time": "10:08",
    "active_tags": [],
    "reserved_tags": [
      "FK-P1A-BTC-20260805-1008",
      "FK-P1B-BTC-20260805-1008",
      "FK-P2-BTC-20260805-1008",
      "FK-P3-BTC-20260805-1008"
    ],
    "status": "REGISTERED"
  }
}
```
