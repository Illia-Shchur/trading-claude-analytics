# 🔪 FALLEN KNIVES ANALYTICS — BTC — 2026-08-01

## SATURDAY MIDDAY — ALL DATA LIVE INTERNET-VERIFIED
### Report Generated: Saturday, August 1, 2026, 11:17 EDT (15:17 UTC)
### Asset: BTC | Prior Score: 11/20 (2026-07-25) | Mechanical Score: 11/20 | D1: 0.0 | Adjusted Score: 11/20

---

## 1. What this report is deciding

Phase 1A is held at 10% of book. The question on the table is whether anything unlocks Phase 1B — and for the first time in this series the answer is *not* "the score is too low." The 2026-07-27 revision cut the 1B line from ≥13 to ≥11. BTC prints exactly 11. **The score condition for Phase 1B is met for the first time in the campaign.** The gate board is what blocks it now, and it blocks it by two gates, not one — which also closes the D2 conviction path, available only at a shortfall of exactly one.

That is the whole report in three sentences. Everything below is the evidence.

---

## 2. Verified Live Data Points — BTC

### 2.1 Canonical spot reconciliation

| Source | Price | Timestamp (UTC) | Status |
|---|---|---|---|
| Binance BTCUSDT | $63,060.00 | 2026-08-01 15:27 | live |
| CoinGecko | $63,012.00 | 2026-08-01 15:27 | live |
| Kraken XXBTZUSD | $63,002.20 | 2026-08-01 15:27 | live |
| Coinbase BTC-USD | $62,997.87 | 2026-08-01 15:27 | live |

**Canonical spot = $63,007.10** (median of 4 synchronized live quotes, all within a 60-second window).
Inter-source spread **0.099%** — below the 0.5% flag, no dual-extreme EV required, no low-confidence demotion. All four quotes are live; no stale quote excluded.

Cross-check: `tools/fetch.mjs btc` at 15:17 UTC returned canonical 63,055.00 (CoinGecko 63,055 / Yahoo last daily close 63,034.74, divergence 0.03%). The ten-minute drift is time-ordering, not venue disagreement.

**Trailing 2-week realized price change: −2.78%** (Yahoo BTC-USD, Jul-18 $64,796.60 → Aug-01 $62,995.27). Stated here so the EV claim in §5 is read against realized momentum, not instead of it.

### 2.2 Sentiment

| Metric | Reading | Source | Timestamp |
|---|---|---|---|
| F&G spot | **27** (Fear) | Alternative.me raw API (PINNED provider) | 2026-08-01 |
| F&G 3-day average | **26.67** → scored band | Alternative.me | Jul-30/31, Aug-01 |
| Last 10 daily prints | 27, 25, 28, 29, 29, 30, 26, 27, 28, 31 | Alternative.me | Jul-23 → Aug-01 |
| Gate-1 streak ≤15 | **0 consecutive days** (`compute.mjs streak --threshold 15` = 0 of 10 counted) | computed | — |

Fear, not extreme fear. The series has not printed a sub-20 daily since the June cascade. No second provider quoted; no ≥10-point divergence to disclose.

### 2.3 Momentum, valuation, structure

| Metric | Value | Source |
|---|---|---|
| **Weekly Wilder RSI-14** | **38.22** (262 completed weekly closes, period 14, Yahoo weekly boundary UTC week-start, last completed week 2026-07-27) | computed, `tools/fetch.mjs btc` 15:17 UTC |
| Weekly RSI incl. live week | 38.57 | same |
| **200-week SMA** | **$63,546.08**; spot **−0.77%** vs it → **within ±8% = TRUE** | computed, exact |
| 200-day MA | $71,293.62, falling −3.48% over trailing 20 sessions; spot −11.63% beneath | computed from Yahoo BTC-USD |
| ATH | $126,080 (2025-10-06); **drawdown −49.99%** | CoinGecko |
| 1-yr high | $126,198.07 (2025-10-06); **−50.03%** | Yahoo weekly highs |
| **MVRV-Z** | **0.41** | AhaSignals, as of 2026-07-31 |
| Realized price | $52,418 | AhaSignals, 2026-07-31 |
| MVRV ratio (raw) | 1.20 | AhaSignals, 2026-07-31 |
| LTH MVRV / STH MVRV | 1.27 / 0.93 | AhaSignals, 2026-07-31 |
| 5-day ADR | **$1,492.24** (Jul-28 1,312.12 / Jul-29 1,432.49 / Jul-30 1,537.54 / Jul-31 2,917.31 / Aug-01 261.72); no abbreviated session excluded — crypto trades continuously | computed |

The MVRV-Z print is one day old and materially fresher than the Jul-13 reading (0.37) that most aggregators still surface. It retires the stale-input debt clock on the valuation leg: this series has now carried a **sourced decimal** two reports running.

### 2.4 Derivatives — live, primary-source

Binance USDT-M perpetual `BTCUSDT`, last 15 funding intervals (fapi/v1/fundingRate, fetched 2026-08-01 15:2x UTC):

```
Jul-27 16:00  +0.00504%   Jul-30 00:00  +0.00846%
Jul-28 00:00  +0.00421%   Jul-30 08:00  +0.00728%
Jul-28 08:00  +0.00013%   Jul-30 16:00  +0.00903%
Jul-28 16:00  +0.00669%   Jul-31 00:00  +0.01000%
Jul-29 00:00  +0.01000%   Jul-31 08:00  +0.00941%
Jul-29 08:00  +0.01000%   Jul-31 16:00  +0.00566%
Jul-29 16:00  +0.00367%   Aug-01 00:00  +0.00412%
                          Aug-01 08:00  +0.00316%
```

**Negative intervals in window: 0. Longest consecutive negative run: 0.** Trailing-3-interval annualized **+4.73%**.

**Provenance correction (metric-history continuity rule).** A widely-surfaced Phemex piece — "BTC perpetual funding rates negative for 46 consecutive days" — is dated **2026-04-16** and references BTC at **$74,287**, with the streak running from ~Mar-01 to Apr-15 2026. It describes a regime **three and a half months stale** and is not evidence about today's tape. This series' own Jul-25 report printed funding at +6.x% annualized; the live primary source above prints +4.73%. Funding has not been negative in this campaign, and no report may claim otherwise.

### 2.5 Liquidations

| Window | Figure | Source |
|---|---|---|
| Jul-31 24h, market-wide (selective venue set) | ~$360M, longs ~$235M (~65%) | market trackers, Jul-31 |
| Jul-31 24h, widest venue set (17+ exchanges) | $1.45B ($1.25B long / $196.19M short) | Loris Tools, Jul-31 |
| Jul-30 24h, network | $286M | CoinDesk, Jul-30 |
| Jul-27 24h, network | $313M | Binance Square, Jul-27 |
| Jul-24 24h, network / BTC-specific | $312M / $87M | prior report, Jul-25 |

**Methodology disclosure, and it is load-bearing.** The $1.45B Loris figure covers a materially wider exchange set than every prior print in this series, all of which sat in the $270–320M network band. Crediting a top-decile capitulation off a source whose denominator changed would be a measurement artefact masquerading as a flush. **This series' convention is held: network-total on the comparable venue set, plus a BTC-specific figure.** On that basis Jul-31 (~$360M) is ordinary — roughly 15% above the trailing-week run rate, not a decile event and not 3σ. The Loris figure is disclosed, not scored, and flagged **PROVISIONAL** pending a second source on a matched venue set.

### 2.6 ETF flows

| Window | Net flow | Source |
|---|---|---|
| **Jul-31 (single session)** | **−$265.4M** | Farside Investors, via Bitcoin World / CryptoNews, Jul-31 |
| Jul-31 breakdown | IBIT −$122.7M · FBTC −$54.8M · GBTC −$52.6M · BITB −$17.8M · ARKB −$17.5M | Farside, Jul-31 |
| Jul-29/30 (two sessions) | +$123M combined | Farside, Jul-30 |
| Jul-23/24 | −$465.3M combined | prior report, Jul-25 |
| Jul-14–22 (7 sessions) | +$999.38M — longest green streak since April, **broken Jul-23** | prior report, Jul-25 |

Trailing-month balance remains **net positive**. The Jul-31 outflow ends a two-session inflow blip and is ~0.3% of AUM against the framework's 2% bar. **Gate 4 stays dark and capitulation-(c) stays off** — but the pattern since Jul-23 is now four outflow sessions against two inflow sessions, and gate 4 remains the nearest [V] gate to lighting.

### 2.7 On-chain holder behaviour

| Metric | Value | Source |
|---|---|---|
| LTH MVRV | 1.27 (above cost basis; LTH cohort profitable and not distributing) | AhaSignals, Jul-31 |
| LTH supply 30d | rising — long-term holders absorbing supply | on-chain aggregators, Jul-31 |
| Exchange reserves 30d | continuing decline | on-chain aggregators, Jul-31 |
| STH MVRV | 0.93 (short-term holders underwater — the marginal seller is already at a loss) | AhaSignals, Jul-31 |

Both holder sub-conditions lit → leg scores 3/3.

### 2.8 Correlation regime — **computed this cycle**

**30-day Pearson correlation of BTC daily log returns vs ^GSPC = +0.241**, window 2026-06-18 → 2026-07-31, overlapping sessions only, computed from Yahoo daily closes 2026-08-01 15:2x UTC.

Regime label: **mild**. Well below the 0.7 risk-on surcharge trigger — **no [V]-gate surcharge applied**, no additional gate or [V]-floor. Also well below the Phase-2 `corr <0.8` bar, so that condition passes on a computed number rather than on the documented-failure default. For context: ETH 0.317 and gold 0.240 over the identical window.

This matters more than it usually does. BTC finished July **up ~7.5%** while the AI complex melted down and Coinbase dropped >10% on an earnings miss. That is not a high-beta risk asset tracking equities into a drawdown; it is an asset that decoupled and outperformed through one.

### 2.9 Macro

| Metric | Level | Δ | Source |
|---|---|---|---|
| 10y TIPS real yield | 2.41% | −0.02 over 5 prints | FRED DFII10, Jul-30 |
| 10y nominal | 4.67% | — | AhaSignals, Jul-31 |
| 3m T-bill (^IRX) | **3.68%** | −0.67% over 2w | Yahoo, Jul-31 |
| VIX | 15.99 | −13.94% over 5 sessions | Yahoo ^VIX, Jul-31 |
| DXY | 99.80 | −1.65% over 5 sessions | Yahoo DX-Y.NYB, Jul-31 |
| Brent | $90.12 | — | Yahoo BZ=F, Jul-31 |
| Fed | Post-FOMC Jul-29; read hawkish; growing hawkish contingent | — | CoinDesk / market commentary, Jul-31 |

A real yield at 2.41% with a hawkish Fed is a headwind to a zero-yield asset, and it is why gate 9 is dark. VIX at 16 and falling is not a stress tape.

---

## 3. Critical Developments

- **Bitcoin closed July up ~7.5%** despite a hawkish FOMC, an AI-driven equity unwind, and the Coldcard fallout. ([CoinDesk, Jul-31](https://www.coindesk.com/markets/2026/07/31/bitcoin-holds-onto-july-gain-as-forced-selling-fuel-was-already-spent-analysts-say))
- **The forced-selling fuel argument.** Analysts attribute BTC's relative resilience to leveraged positioning having been flushed in late June — limiting forced selling during the latest volatility. This is the direct counterpart to the prior report's DAT-overhang concern: the seller cohort may be closer to exhausted than to beginning.
- **Jul-31 risk-off session.** BTC −2.9% to $62,929 at the 16:30 ET mark, ETH −2.8%, SOL −2.0%. Coinbase −10%+ on a trading-revenue miss. ([Motley Fool, Jul-31](https://www.fool.com/coverage/stock-market-today/2026/07/31/crypto-market-today-july-31-bitcoin-slides-below-usd63-000-and-coinbase-tumbles-10/))
- **Post-FOMC drift.** BTC −2.8% since the Jul-29 decision, consistent with the historical post-FOMC pattern.
- **Clarity Act window narrowing** in the Senate — a regulatory catalyst that has been slipping rather than resolving.
- **ETF flows resumed net-negative Jul-31** (−$265.4M), IBIT's largest single-day withdrawal in recent weeks.

---

## 4. Fallen Knives Composite Score — BTC

| Category | Max | Input | Band | Score |
|---|---|---|---|---|
| Sentiment Extreme | 5 | F&G 3-day avg **26.67** | ≤35 → 2 (`compute.mjs band fk-sentiment 26.67` = 2) | **2** |
| Momentum Exhaustion | 4 | Weekly Wilder RSI-14 **38.22** | ≤40 → 2 (`fk-momentum 38.22` = 2) | **2** |
| Valuation | 5 | **MVRV-Z 0.41** (primary metric) | ≤0.5 → 4 (`fk-mvrv 0.41` = 4) | **4** |
| Capitulation Evidence | 3 | (a) liquidation decile **NO** · (b) funding negative ≥3 intervals **NO** (0 in 15) · (c) ETF outflows ≥2% AUM **NO** (~0.3%) | 0/3 → 0 | **0** |
| Holder Behavior | 3 | (a) LTH supply rising ✓ · (b) exchange reserves declining ✓ | Both → 3 | **3** |
| **Leg sum** | 20 | | | **11** |

- **Leg sum: 11.0**
- **Mechanical score: 11** — the number read by the compound stop's score line, the Deep-Value Override's arming condition, every §7 trim trigger, the EV-floor check, and the Verdict-Confidence Collar.
- **D1 discretionary term: 0.0** (see §9)
- **Raw composite: 11.0**
- **[V]-gate surcharge: NOT applied** (30d corr 0.241 < 0.70, sourced and computed)
- **Rounding convention: BTC half-up** (no half-point arose)
- **Adjusted score: 11/20** → *Building — Phases 1A–1B eligible on score*

### Confirmation Gates — 3 / 9

| # | Bucket | Gate | Status | Relight path (re-derived this report) |
|---|---|---|---|---|
| 1 | [V] | Sentiment ≤15 for ≥7 consecutive daily prints | ❌ | 0-day streak; lowest print in 10 sessions is 25. Needs a fear leg roughly the depth of June's — a ~7-session run of sub-16 prints. Reachable but requires a genuine cascade, not drift. |
| 2 | [V] | Weekly RSI <30 | ❌ | 38.22. Needs a sustained multi-week decline; a single week cannot carry a Wilder-14 from 38 to below 30. Roughly a move toward the low $50Ks held for several weeks. |
| 3 | [V] | Valuation cheap — MVRV-Z <1 | ✅ | Lit at 0.41. |
| 4 | [V] | ETF outflows ≥2% AUM trailing month | ❌ | **Nearest [V] gate.** Trailing month still net positive. Needs roughly 8–10 more sessions at the Jul-23/24/31 run rate (−$225M to −$265M/session) with no offsetting inflows. |
| 5 | [T] | Hash Ribbon buy signal | ❌ | **Stale-input flag, report 2 of the debt clock.** No fresh sourced print obtained this cycle; carried dark, not credited. A sourced Hash Ribbon read is owed by the next report or an explicit statement of why it cannot be obtained. |
| 6 | [T] | Price within ±8% of the 200-week MA | ✅ | Lit at −0.77% — price is effectively *on* the 200-week mean. |
| 7 | [V] | Capitulation volume spike (top-decile 90d OR >3σ 30d) | ❌ | Jul-31 ~$360M network is ordinary. Needs a print several multiples of that on a matched venue set — realistically a disorderly break of $60K. |
| 8 | [V] | LTH accumulation / holder concentration stabilizing | ✅ | Lit — LTH supply rising, reserves declining, LTH MVRV 1.27. |
| 9 | [T] | Macro catalyst neutral-to-positive | ❌ | Hawkish post-FOMC read, real yield 2.41%, Clarity Act window narrowing. Relight: a dovish inflection at the Sep FOMC, a soft Aug-07 payrolls / Aug-12 CPI pair, or Clarity Act passage. |

**Passed: 3, 6, 8 → 3 of 9. [V] count: 2** (gates 3, 8). Denominator 9 — BTC is PoW, gate 5 applies and is **not** N/A; it is a measurable-but-unmeasured signal carried dark, which the counting rule explicitly forbids converting to N/A.

Thresholds (`compute.mjs thresholds 9`): 1A ≥3 ([V]≥2) · 1B ≥5 ([V]≥3) · 2 ≥6 ([V]≥3) · 3 ≥7 ([V]≥4).

No dark gate is tagged "none-in-regime": gates 1, 2, 4, 7 and 9 all have concrete, reachable paths, and gate 5 is a sourcing gap rather than a regime finding. This disclosure is informational and is not cited anywhere below to lower a threshold or credit a gate.

### Companion Flying Rocket score (Hard Rule 5) — **computed, not estimated**

**Channel routing (FR §2.5, re-verified on today's data):** BTC is −50.03% below its 1-year high **and** the 200dma is falling (−3.48% over 20 sessions, $73,865.47 → $71,293.62) with price −11.63% beneath it → **Channel B — Bear Continuation**. The Channel A phase-of-cycle cap does **not** bind, because Channel A is not the live channel.

Channel B legs, computed from the same live fetch:

| Leg | Input | Score |
|---|---|---|
| Rally Extension | 40-session low $57,747.77 (Jul-01) → high since $66,910.06 = **+15.87%** | >12% → **2** |
| Local Momentum Exhaustion | **Daily** Wilder RSI-14 **44.96**; weekly RSI 38.22 < 50 so the hard qualifier passes | not >45 → **0** |
| Resistance Confluence | (b) just lost the 50dma ($63,374.73, spot −0.59%) ✓ · (c) below the Jul-21 lower high $66,910 ✓ · (a) 200dma −11.6% away ✗ · (d) not credited | 2/4 → **3** |
| Bear Structure Integrity | (a) bounce high is a lower high ✓ · (c) no weekly close above the 200dma in 8 weeks ✓ · (b) 50–200 gap **narrowed** (−8,989 → −7,919) ✗ | 2/3 → **2** |
| Relative Sentiment / Positioning | (a) F&G 3d avg 26.67 not ≥1.5× its 30d mean and not ≥45 ✗ · (b) funding has not flipped positive after ≥5 negative sessions ✗ · (c) ETF inflows are *not* resuming into the rally — Jul-31 −$265.4M ✗ | 0/3 → **0** |
| **Raw** | squeeze-trap penalty 0 (funding positive) · bounce-maturity floor N/A (rally 32 sessions old) · corr surcharge off | **7 / 20** |

**FR BTC = 7/20** against a Channel B Phase-1A line of 13 — **short by 6**. Stall confirmation also fails: Aug-01 closed $63,000 vs Jul-31 $62,814, a *higher* close. **STAND DOWN on the short side.**

Yesterday's standalone `btc_flying_rocket_20260731_0426.md` computed 6/20 on the same channel; today's 7 reflects the freshly-lost 50dma adding a resistance-confluence point. The series is internally consistent.

**Cross-validation: FK 11 / FR 7 — inversely related, both <12, consistent ✅.** The label stands **unqualified**: the phase-of-cycle cap is not binding (Channel B is live), so the vacuity relabeling does not apply and Hard Rule 5's both-≥12 check is genuinely falsifiable here. FR is 7, below the ≥9 tripwire, so no standalone companion report is owed on that trigger. Day-of short-side liquidation volume ($196.19M on the widest venue set) does exceed $100M — but that figure comes from the same methodology-mismatched Loris source flagged in §2.5; on the series' comparable venue set the short-side share of ~$360M is well under $100M. Disclosed; no standalone report triggered on a source this report declined to score.

---

## 5. Probability Matrix — BTC

**Baseline row (adjusted score 11 → the 11–14 band): Rally 30 / Range 35 / Retest 22 / Bear 13.**

**§5 trend-residual state — stated as a boolean regardless of how cells were set:**
> **Active downtrend (below a major MA **AND** making lower lows): NO.**
> Price is below the 200dma (−11.63%) and just lost the 50dma — the MA half is satisfied. The lower-lows half is **not**: the campaign low is $57,747.77 (Jul-01) and every subsequent low is above it, including Jul-31's. The structure since Jul-01 is a higher-low sequence inside a broader bear, not a fresh leg down.
> **Consequence, stated so it cannot be silently orphaned:** no bearish residual shift is applied, **and** if the Deep-Value Override were to fire it would do so at **half** the tranche's nominal size, not quarter — the quarter-size throttle is keyed to this boolean and it is OFF.

**D4 adjustments from baseline** (all within 10 percentage points, so no per-cell reason is compelled; given anyway):

| Cell | Baseline | Set | Δ | Reason |
|---|---|---|---|---|
| Rally | 30 | **28** | −2 | ETF flows turned net-negative again Jul-31; the two-session inflow blip did not hold. |
| Range | 35 | **34** | −1 | — |
| Retest | 22 | **24** | +2 | Price sits *on* the 200-week mean; the level resolves rather than absorbs. |
| Bear | 13 | **14** | +1 | Post-FOMC hawkish drift and a narrowing Clarity Act window. |

| Scenario | Probability | Target Range | Midpoint | Key Trigger |
|---|---|---|---|---|
| **Rally** | 28% | $65,500 – $71,000 | $68,250 | Weekly reclaim of the 50dma ($63,375) then the Jul-21 lower high $66,910; a dovish Aug-07 payrolls |
| **Range** | 34% | $61,500 – $65,500 | $63,500 | 200-week MA ($63,546) continues to absorb; funding stays modestly positive; flows stay mixed |
| **Retest** | 24% | $57,500 – $61,500 | $59,500 | Decisive weekly close below the 200-week MA; ETF outflows extend past 5 sessions; retest of the $57,748 Jul-01 low |
| **Bear** | 14% | $50,000 – $57,500 | $53,750 | Jul-01 low breaks on volume; a genuine liquidation cascade lights gates 1/2/7 together |

Sum = **100%** ✓ · Rally 28% ≤ 50% cap ✓

**Weighted EV recomputation (final step, from the printed cells only):**
```
0.28 × 68,250 = 19,110.00
0.34 × 63,500 = 21,590.00
0.24 × 59,500 = 14,280.00
0.14 × 53,750 =  7,525.00
                ---------
EV            = 62,505.00
```
Verified: `compute.mjs ev --spot 63007.10 --stated 62505` → `rel_diff_pct 0, within_tolerance true, prob_sum_ok true, rally_cap_ok true`.

**Weighted EV = $62,505.00. EV-vs-spot = −0.80%.**
**Realized trailing-2-week price change: −2.78%.** The EV is mildly negative and realized momentum is more negative still — the two agree in sign. This is not the "positive EV printed into a falling tape" pattern the Jun-2026 calibration removed.

**EV-floor consistency check:** EV-vs-spot is negative, but the trigger requires **mechanical score ≥15 AND 3-day F&G ≤15**. Mechanical is 11 and F&G 3d is 26.67 — **neither limb met, no inconsistency flag.** Nothing to resolve.

**Terminal-vs-extreme:** not compelled — the §5 trend residual is not live (boolean NO above), so the reconciliation requirement does not attach. Said plainly anyway: Range being modal describes where I expect price to *end* the horizon, not a claim that $57,748 is the low. The path extreme, if Retest resolves, sits in the $57,500–61,500 band.

---

## 6. Deployment Strategy — BTC

**Total dry powder: 90% of the BTC book.**
**Dry-powder yield benchmark: 3-month T-bill 3.68%** (Yahoo ^IRX, Jul-31) — roughly 31 bp/month of measurable opportunity cost on idle cash. Cash is a position and it is currently being paid to wait.

### Position & Performance (Hard Rule 8)

`node tools/position.mjs btc` — **band STALE**, age **1,561 minutes (26.0 h)**, driver `holdings_as_of` (2026-07-31 13:15 UTC; file written 13:28 UTC).

> ⚠️ **AGE BANNER — STALE (12–72 h).** These figures are usable **descriptively** only. Under Hard Rule 8 a STALE snapshot **may not satisfy a phase-dependent unlock precondition** and **may not fill a realized ledger column**. Nothing below is used to authorize a fill.

| Field | Value | Note |
|---|---|---|
| Custody status | **RECONCILED** | live balance agrees with the fill replay; deposits 0, withdrawals 0, off-venue 0 |
| Live quantity | **0.00000184 BTC** | position of record — **dust** |
| Trade-derived quantity | 0.00000184 BTC | agrees |
| `basis.reliable` | **FALSE** | — |
| Average cost / cost basis / unrealized PnL / ROI | **NOT REPORTED** | see below |
| Realized PnL | $1,639.83 | **upper bound, not a result** |
| Mark (informational only) | — | never this report's canonical spot (carve-out (a)) |
| Attribution | **UNTAGGED** | `performance_by_tag_prefix` is empty — no `FK-` tag exists on this account |

**`basis.reliable` is FALSE and it is read separately from custody.** Custody is clean; the ledger simply does not know what these coins cost. Its replay disposed of more than it ever saw acquired — coins were sold whose acquisition was never ingested. Per Hard Rule 8 I quote **no** average cost, cost basis, unrealized PnL or ROI for BTC, and I treat the $1,639.83 realized figure as an **upper bound** rather than a result. The **quantity is the position of record**, and nothing is sized against a cost basis that does not exist.

**Real dry powder: $14,288.54** account-wide (`dry_powder.stable_balance_usd` — USDT $9,552.96 across CROSS_MARGIN/SPOT, USDC $4,735.58 across EARN_FLEXIBLE/SPOT). Excludes futures collateral (futures equity is $0.00); includes stablecoins resting in open orders. Total portfolio $19,665.31.

**Realized performance, account-wide** (91 deals, 13 open): win rate **67.03%**, profit factor **4.94**, expectancy **$59.01**, avg win $110.40 / avg loss −$47.04, best $1,006.51 / worst −$459.59, avg hold 95.9d on winners vs 105.7d on losers. **Per-tag performance is unavailable** — `performance_by_tag_prefix` is empty, so I cannot state how Phase 1A entries have actually performed. I will not assert it.

#### Position Reconciliation — the ledger wins

This is the sharpest line in the report and it needs to be stated without softening.

| Figure | Prior report (Jul-25) narrated | Ledger (STALE, Jul-31 13:15 UTC) | Delta |
|---|---|---|---|
| Phase 1A status | DEPLOYED, 10% of book | **0.00000184 BTC — dust** | The narrated 10% tranche has no counterpart in the live balance |
| Blended cost | "~$65,000" | **not derivable** (`basis.reliable` false) | The $65,000 figure has never once been checked against a fill |
| Book size | "10% of book" | **$19,665.31 total, $14,288.54 stable** | "10%" was 10% of an unstated notional |

**What I can and cannot conclude.** Custody is `RECONCILED` with zero recorded withdrawals, so this is *not* an off-venue-custody case where coins moved to cold storage. The live balance is genuinely dust. But `basis.reliable` is FALSE precisely because this asset's fill history is **incomplete** — 24 unbacked disposals exceed the replay by 8.51 units account-wide — so I cannot responsibly assert "the position was sold" either. What is defensible: **the ledger has no evidence of a held BTC position, and no evidence of what one would have cost.**

Accordingly, and per Hard Rule 4 governing the absence of usable evidence: **the deployment table below carries the prior report's narrated 10% Phase 1A forward as the reported state, flagged UNVERIFIED, and the report authorizes no new deployment against it.** The snapshot is STALE and cannot resolve a phase-dependent question in either direction. **Resolving this is the single most actionable item in the report** (§12, item 1) and it outranks every market call here — a framework that cannot say what it owns cannot size what it buys next.

### Phases

| Phase | Size | Entry zone | Score line | Gates required | Status |
|---|---|---|---|---|---|
| **1A** | 10% | $63,000–66,500 | ≥8 ✅ (11) | ≥3/9, [V]≥2 ✅ (3, [V] 2) | **DEPLOYED per prior report — ⚠️ UNVERIFIED against a STALE, basis-unreliable ledger.** Unlock conditions are *met*, so were this a cold start 1A would authorize today. |
| **1B** | 15% | $58,000–61,500 | **≥11 ✅ (11) — MET FOR THE FIRST TIME** | ≥5/9, [V]≥3 ❌ (3/9, [V] 2) | **BLOCKED ON GATES ALONE.** Short by **2** gates and by 1 on the [V] floor. |
| **2** | 30% | $54,000–58,000 | ≥15 ❌ (11) | ≥6/9, [V]≥3 ❌ | FROZEN — double-blocked |
| **3** | 45% | requires weekly capitulation candle | mech ≥17 ❌ (11) | ≥7/9, [V]≥4 ❌ | DRY POWDER |

**The Phase 1B change is the headline mechanical event of this report.** The 2026-07-27 revision cut the 1B score line from ≥13 to ≥11. BTC has printed 11 in both of the last two reports; under the old line it was "double-blocked (score 11<13; gates 3/9<5)," and it is now **single-blocked on gates**. That is a real, documented loosening working exactly as designed — it converted a two-key lock into a one-key lock, and the remaining key is the one that requires actual market confirmation.

**D2 Analyst Conviction Path — evaluated and UNAVAILABLE.** D2 requires the gate count to fall short by **exactly one**. Phase 1B needs 5 gates and has 3 — short by **two**. The path does not open, and the [V] floor is independently short (2 lit vs 3 required). I note this explicitly because a reader watching the score cross 11 will reasonably ask whether a written case could bridge the rest; it cannot, and no amount of conviction substitutes for two gates.

**Deep-Value Override — evaluated, DOES NOT FIRE.**
- Mechanical score ≥15: **FAILS at 11.** This alone is dispositive; the Override cannot arm.
- (For completeness: 3-day F&G is 26.67, not ≤15 — a second independent failure. The price condition and the worsening-flows veto were not reached.)
- Max drawdown from spot to the compound thesis line ($55,000): **−12.71%.** Stated as standing disclosure; per the 2026-07-27 supersession it purchases no loosening of anything.

**Non-mechanical capital cap:** 0% of book deployed through D1 crosses, D2 paths or Override firings. The 40% cap and the 25% Override sub-cap are both untouched.

### Stops

**No stop parameter changed value this report. Stop Migration Ledger: one line, checkpoint date only.**

| Tier | Level | Note |
|---|---|---|
| **Catastrophic floor** | **$50,000** | Unchanged. Sits strictly below the deepest named buy-zone floor. |
| **Compound thesis stop** | **$55,000 price AND mechanical score <12** | Unchanged. Score line 12 (BTC standard, not a pinned-score asset). |
| Deepest named buy-zone floor | $54,000 (Phase 2 zone) | — |
| D5 discretionary stops | **none — no analyst-channel tranche exists** | — |

**Compound stop disclosure (carried, no migration).** Mechanical score is **11**, which is **<12** — so the score axis of the compound stop **is satisfied**, and the stop is effectively **price-gated at $55,000** until the score re-crosses 12. This is a live reduction in two-key protection and it is the same state the Jul-25 report disclosed. It is not a widening: no parameter moved, the condition simply remains met.

**Stop-vs-buy-zone coherence check (catastrophic tier):**
> **CATASTROPHIC stop $50,000 strictly below deepest active buy-zone floor $54,000? → PASS.**
> Verified: `compute.mjs stop-coherence --catastrophic 50000 --floor 54000` → `pass: true`.
> No prospective ladder is named below $54,000 anywhere in this report, so no post-activation re-stop or atomic activation sequence is owed. No "stop realignment owed" flag.

**D6 ratchet compliance:** every parameter either held or moved toward price. The only change is the checkpoint date, treated below.

**Stop Migration Ledger:**

| Parameter | Tier | Old | New | Direction | Rationale |
|---|---|---|---|---|---|
| Checkpoint date | checkpoint date | 2026-07-26 | **2026-08-02** | forward roll | The Jul-26 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below $55,000). Rolls to the next weekly close. |

**Checkpoint prognosis (calendar-locked).**
Checkpoint **Sunday, 2026-08-02, 00:00 UTC weekly close** — verified a real weekly boundary on the Yahoo/crypto weekly calendar (week-start UTC, matching the boundary used for the RSI computation); crypto venues trade continuously so no holiday or abbreviated-session correction applies. It **fires iff** ≥2 consecutive weekly closes print below $55,000 **and** the mechanical score is <12. Spot $63,007.10 sits **14.56% above** the $55,000 line, a distance of **9.66× the 5-day ADR of $1,492.24**. Closes below the line so far: **0 of the 2 required** — the checkpoint therefore **cannot** fire on Aug-02 regardless of price, which is a structural statement about the condition, not a forecast. **Tier-1 US releases between this report and the Aug-02 checkpoint: NONE** — the next is nonfarm payrolls, **Friday 2026-08-07, 08:30 ET** (BLS; first Friday of the month), followed by CPI **Wednesday 2026-08-12, 08:30 ET**, PPI Aug-13 and retail sales Aug-14. Because the checkpoint structurally cannot fire and no unpriced tier-1 release precedes it, the "cannot fire" statement is traced to a named quantity (0 of 2 closes) rather than to an adjective.

**Time stop:** Phase 1A carries a 90-day reassessment horizon from its stated fill. Given that the fill itself is now UNVERIFIED against the ledger, the honest formulation is: **the time stop is unenforceable until the position is verified**, which is a further reason item 1 in §12 outranks the market call.

### Ledger tag

No tranche fills this report, so no tag is issued. If Phase 1B later fills it carries **`FK-P1B`**, applied via `PUT /api/investments/deal-note` with a note whose first line is `report=reports/btc_fallen_knives_20260801_1117.md`. **The account currently carries no `FK-` tags at all** (`performance_by_tag_prefix` empty) — every existing holding is `UNTAGGED` and therefore cannot resolve a phase-dependent unlock precondition.

---

## 7. Exit / Trim Framework — BTC

Hard Rule 2: evaluated in full every report, positions held or not. **Every score condition here reads the MECHANICAL score (11).**

| Trigger | Threshold | Current | Fires? |
|---|---|---|---|
| Mechanical score drops ≥6 from campaign local peak | peak **16** (Jun-2026, mechanical) → trim at ≤10 | **11** | **NO** — 5 points off peak, one point from the trigger |
| F&G ≥75 sustained 7d AND weekly RSI >70 | — | F&G 27, RSI 38.22 | NO |
| MVRV-Z >3 or drawdown <10% with vertical 30d return | — | MVRV-Z 0.41, drawdown −49.99% | NO |
| Mechanical score ≤3 AND price ≥40% above blended cost | — | score 11; blended cost **not derivable** | NO — and note the second limb is **untestable** while `basis.reliable` is false |
| ETF outflows ≥3% AUM trailing month after a sustained inflow regime | the ≥5-session green bar **was** met (Jul-14–22, 7 sessions) so the regime precondition is satisfied | trailing month still **net positive**; Jul-31 alone ~0.3% of AUM | **NO — but this is the live one to watch.** The precondition is already met; only the magnitude is missing. |
| Narrative break | — | none — no regulatory ban, no founder fraud, no critical breach, no tokenomics change | NO |

**Current exit status: NONE. No trim executed, no exit. Position (as narrated) 10% Phase 1A, unverified.**

Two observations worth carrying forward. First, the ≥6-point drop trigger is **one point away**: a single leg losing 1 point — say valuation slipping from 4 to 3 on an MVRV-Z above 0.5 — fires a 25% trim. Second, the ETF-outflow trim's *regime precondition is already satisfied*, which means it is now a pure magnitude question. Both are live, both are mechanical, and neither is something discretion may touch.

---

## 8. Critical Watchlist — BTC

**Mandatory tier-1 US enumeration, next 5 trading days (Aug-03 through Aug-07), verified against the BLS/CME release schedule:**

| Date (ET) | Time | Event | Tier | BTC impact |
|---|---|---|---|---|
| Mon Aug-03 | — | *no tier-1 release* | — | — |
| Tue Aug-04 | — | *no tier-1 release* | — | — |
| Wed Aug-05 | — | *no tier-1 release* | — | — |
| Thu Aug-06 | — | *no tier-1 release* | — | — |
| **Fri Aug-07** | **08:30** | **Nonfarm Payrolls / Employment Situation** | **TIER 1** | The horizon's dominant macro event. A soft print is the most direct available path to relighting gate 9; a hot print extends the post-FOMC hawkish drift. |

**Beyond the 5-day window, dated for the checkpoint rule:** CPI **Wed Aug-12 08:30 ET**, PPI Thu Aug-13, Advance Retail Sales Fri Aug-14.

**This report's horizon contains no unenumerated tier-1 release** — it is not an incomplete-data report on the calendar dimension.

| Ongoing | Event | Impact |
|---|---|---|
| Rolling | Daily ETF flow prints (Farside/SoSoValue) | Gate 4 is the nearest [V] gate; ~8–10 more sessions at the current outflow rate lights it |
| Rolling | Senate Clarity Act window | Passage would relight gate 9; expiry extends the regulatory overhang |
| Rolling | DAT/treasury-company disposals | The prior report's ~20-company exit count vs the "forced-selling fuel already spent" thesis |
| Weekly | Sun Aug-02 00:00 UTC weekly close | Stop checkpoint (structurally cannot fire) |

---

## 9. Analyst Read — Discretionary Layer

### 9.1 The read

Bitcoin is sitting on the 200-week moving average — $63,546 against a spot of $63,007, a distance of eight tenths of one percent. In this framework that is not one input among nine; it is *the* structural level, and the tape has spent the last several sessions grinding along it rather than breaking through it in either direction. Everything else in this report is a description of the conditions under which that level holds or fails.

The case that it holds rests on three things the price action has already demonstrated. BTC finished July up roughly 7.5% through a hawkish FOMC, an AI-led equity unwind, and a 10%+ drop in Coinbase — while its 30-day correlation to the S&P sat at 0.241. That is a decoupling with relative strength attached, and it is the opposite of the May–June configuration where crypto was simply high-beta equity exposure with worse liquidity. Underneath, the holder base is doing what it does at durable lows: LTH MVRV at 1.27 with supply rising, exchange reserves falling, and short-term holders at 0.93 — meaning the marginal seller is already underwater and selling into a bid that long-term holders are providing. And the leverage that would fuel a cascade has already been drained; funding sits at +4.73% annualized, roughly a third of a normal bull-market carry, with no negative interval in fifteen.

The case that it fails is simpler and I do not want to underweight it. Institutional flow has stopped being a tailwind. Since Jul-23 the ETF complex has printed four outflow sessions against two inflow ones, and Jul-31's −$265.4M was IBIT's largest single-day withdrawal in weeks. Real yields at 2.41% with a hawkish Fed impose a continuous, mechanical cost on holding a zero-yield asset, and the Clarity Act — the one clean regulatory catalyst on the board — is running out of Senate calendar rather than converging. Price is below a falling 200-day and has just lost the 50-day.

Where I come out: this is a market that has done the *structural* work of a bottom — cheap on realized value, holders accumulating, leverage flushed — without the *emotional* work. Gate 1 wants a seven-day run of sub-16 fear prints and we have not printed below 25 in ten sessions. Gate 7 wants a liquidation flush and Jul-31 delivered an ordinary $360M. The framework's five legs score this an 11 because 11 is what "cheap and structurally sound but not yet capitulated" is supposed to score. I think the score is right, and I think the gates are right to block Phase 1B. My honest expectation is that the next 15% of BTC's move is roughly a coin flip and the *following* 30% is up — which is an argument for having a plan at $58–61.5K, not for spending money at $63K.

### 9.2 What the rubric structurally cannot see

1. **The composition of the ETF outflow.** The legs see a magnitude against a 2%-of-AUM bar. They cannot see that IBIT accounted for ~89% of the Jul-23/24 reversal and 46% of Jul-31 — that this is concentrated redemption at one issuer rather than broad institutional exodus. *Cuts bullish*, mildly.
2. **The DAT seller cohort's position in its own lifecycle.** The prior report counted ~20 public treasury companies exiting or reducing. The legs score LTH supply, which is an aggregate — it cannot distinguish a forced structural seller working through inventory from organic distribution. CoinDesk's Jul-31 framing ("forced selling fuel was already spent") is the bullish reading of the same fact. *Direction genuinely ambiguous*, which is why it does not move a number.
3. **Cross-asset relative strength.** Up 7.5% in a month when the AI complex broke, at correlation 0.241. The correlation regime is a *gate surcharge* input in this framework — it can only make gates harder, never credit relative performance. *Cuts bullish.*
4. **The 200-week level as a decision point rather than a state.** Gate 6 is a boolean: within ±8%, lit. It scores −0.77% and −7.9% identically. It cannot express that price is *at* the level and that the level is about to resolve. *Cuts neither way; it raises variance*, which is why I moved 3 points of probability mass from Rally/Range into Retest/Bear.
5. **Hash Ribbon (gate 5) is dark for want of a source, not for want of a signal.** The rubric treats an unmeasured gate identically to a failed one. *Unknown direction* — and a sourcing debt I owe next report.

### 9.3 The D1 term

**D1 = 0.0.**

I considered **+0.5**, and I want to record why I declined it rather than quietly not mentioning it. Two factors above qualify on the letter of the rule — cross-asset relative strength through an equity drawdown at correlation 0.241 (§9.2 item 3), and the concentration profile of the ETF outflow (item 1) — both sourced, both structurally invisible to the legs, neither double-counting a scored input. A +0.5 would take the raw composite to 11.5 and, on BTC's half-up convention, the adjusted score to 12.

I declined it because **12 unlocks nothing**. The next threshold is Phase 2 at ≥15. A discretionary adjustment that crosses no threshold, authorizes no fill, and changes no stop is not analysis — it is decoration, and it would start a decay clock on a position I would have to re-argue for three reports to no operational end. The framework licenses discretion on the condition that it is *scored*; a call that cannot be graded because it changed nothing is worse than no call.

I also considered **−0.5** on the ETF flow deterioration and declined that too, for a cleaner reason: it would be double-counting. ETF flows already key capitulation-(c) and gate 4 by name, and the rule prohibits re-weighting a factor a leg already scores.

**Falsifier for the zero:** if BTC closes a week above $66,910 (the Jul-21 lower high) while ETF flows print ≥3 consecutive green sessions, the relative-strength argument stops being a decoration and becomes a trend-structure event — at which point it belongs in the score, not in a footnote. Conversely, if the 200-week MA breaks on a weekly close with flows still negative, the bullish factors I listed are refuted on their own terms and a negative term becomes arguable.

### 9.4 Discretionary actions taken and declined

| Action | Channel | Disposition | Reason |
|---|---|---|---|
| Score adjustment +0.5 | D1 | **DECLINED** | Crosses no unlock threshold; would be undecorated noise carrying a decay obligation |
| Score adjustment −0.5 | D1 | **DECLINED** | Double-counts ETF flows, already scored by capitulation-(c) and gate 4 |
| Phase 1B conviction unlock | D2 | **UNAVAILABLE** | Requires a shortfall of exactly 1 gate; 1B is short 2 (3/9 vs 5) and short 1 on the [V] floor |
| Probability cells set by hand | D4 | **TAKEN** | 3 points of mass moved Rally/Range → Retest/Bear; all cells within 10pp of baseline; reasons tabled in §5 |
| Deep-Value Override | mechanical, not discretionary | **DOES NOT ARM** | Mechanical score 11 < 15 |

### 9.5 Discretion Ledger (D7)

| Date | Channel | Call | Size | Stop line | Falsifier | Status | Realized P&L |
|---|---|---|---|---|---|---|---|
| 2026-08-01 | D1 | Term set to **0.0**; +0.5 and −0.5 both considered and declined | n/a | n/a | Weekly close >$66,910 with ≥3 green flow sessions (would justify positive); weekly close <200w MA with negative flows (would justify negative) | **live** | n/a |
| 2026-08-01 | D4 | Cells set by hand: 28/34/24/14 vs baseline 30/35/22/13 | n/a | n/a | A weekly close outside $61,500–65,500 retires the Range-modal read | **live** | n/a |

No prior open discretionary entries exist to carry forward — the Analyst Discretion Layer shipped 2026-07-27 and this is BTC's first report under it. **The layer is N=1 on this asset as of today.**

### 9.6 What would change my mind

**Bullish flip, dated:** two consecutive weekly closes above $66,910 by **Sun 2026-08-30**, accompanied by ≥5 consecutive green ETF sessions. That is the framework's own trend-structure-repair bar plus its own flow-flip bar, met together. It would retire the accumulation posture in favour of a hold-and-let-it-run posture.

**Bearish flip, dated:** a weekly close below **$61,000** — decisively through the 200-week mean rather than oscillating around it — by **Sun 2026-08-16**, with the trailing-month ETF balance turned net negative. That activates the §5 trend residual (a fresh lower low would print), throttles any future Override to quarter-size, and puts the $58,000–61,500 Phase 1B zone in play as a live ladder rather than a plan.

**What would *not* change my mind:** one more red ETF session, one more −3% day, or a single sub-25 F&G print. None of those is durable enough to promote to structure, and Principle 3 exists because this framework once called a fear window closed on a bounce and ate a −23% leg for it.

---

## 10. Bull vs Bear Scorecard — BTC

**Bull (✅) — 7**
1. MVRV-Z 0.41 with realized price $52,418 — market value only 20% above aggregate cost basis
2. Price on the 200-week MA (−0.77%), the framework's core structural level, holding so far
3. LTH supply rising, LTH MVRV 1.27 — long-term holders accumulating, not distributing
4. Exchange reserves declining on a 30-day basis
5. Funding +4.73% annualized with zero negative intervals in 15 — leverage drained, no cascade fuel
6. July closed +7.5% through an AI-led equity unwind at 30d correlation 0.241 — genuine decoupling with relative strength
7. Trailing-month ETF balance still net positive despite four outflow sessions since Jul-23

**Bear (❌) — 7**
1. ETF flows resumed net-negative Jul-31 (−$265.4M), IBIT's largest single-day withdrawal in weeks
2. Real yield 2.41% with a hawkish post-FOMC read — continuous carry cost on a zero-yield asset
3. Price below a falling 200-day MA (−11.63%, slope −3.48%/20 sessions)
4. Just lost the 50-day MA (−0.59%)
5. Senate Clarity Act window narrowing rather than converging
6. Gate board at 3/9 with five [V] gates dark — the fear evidence is thin for a −50% drawdown
7. DAT/treasury cohort net seller, ~$62bn of DAT market value gone (prior report, unretired)

**Net: 7–7, exactly balanced.** This triggers the Verdict-Confidence Collar independently of the score. Two of the three collar conditions are live: the scorecard is within 1 of balanced (it *is* balanced), and |EV-vs-spot| = 0.80% < 2%. The mechanical score of 11 sits outside the 6–10 band, so that limb is not met — but one limb is sufficient and two are met. **No directional regime resolution may be claimed in §12.**

---

## 11. Change Log vs 2026-07-25

| Factor | Previous (Jul-25) | Current (Aug-01) | Direction |
|---|---|---|---|
| Canonical spot | $63,968.80 | $63,007.10 | −1.50% |
| Mechanical score | 11 | **11** | flat |
| — sentiment leg | 2 (3d avg ~27) | 2 (3d avg 26.67) | flat |
| — momentum leg | 2 | 2 (RSI 38.22) | flat |
| — valuation leg | 4 | 4 (MVRV-Z **0.41**, sourced decimal) | flat, better sourced |
| — capitulation leg | 0 | 0 | flat |
| — holder leg | 3 | 3 | flat |
| D1 discretionary | n/a (pre-layer) | **0.0**, logged | new |
| Gates | 3/9 (3, 6, 8) | 3/9 (3, 6, 8) | flat |
| **Phase 1B blocker** | **double-blocked** (score 11<13 AND gates 3<5) | **single-blocked** (score 11 ≥11 ✅, gates 3<5) | **score condition now MET** |
| Weekly RSI | ~38 | 38.22 | flat |
| 200-week MA distance | ~−1% | −0.77% | flat |
| MVRV-Z | 0.37–0.4 band | 0.41 (Jul-31) | flat |
| ETF flows | 2 outflow sessions (−$465.3M) | 4 outflow / 2 inflow since Jul-23; Jul-31 −$265.4M | worse |
| Funding | +6.x% ann | +4.73% ann, 0 negatives in 15 | slightly lower, same regime |
| 30d corr vs SPX | not stated | **0.241, computed** | newly sourced |
| FR companion | n/a | **7/20 Channel B** (6/20 on Jul-31) | consistent, stand down |
| EV-vs-spot | −1.44% | −0.80% | less negative |
| Position (ledger) | narrated 10% @ ~$65,000 | **dust, basis unreliable, STALE** | **material divergence flagged** |

---

## 12. Strategic Verdict — BTC

**Adjusted score 11/20 · Mechanical 11/20 · D1 0.0 · Weighted EV $62,505.00 · EV-vs-spot −0.80% · realized 2-week −2.78% · F&G 27 (3d avg 26.67, Fear) · Gates 3/9 ([V] 2) · Stance: HOLD, deploy nothing new.**

Bitcoin has arrived at the level this framework cares about most and has not yet told us what it intends to do there. Spot sits eight tenths of a percent from the 200-week mean, at an MVRV-Z of 0.41 against a realized price of $52,418, with long-term holders adding and short-term holders underwater. Those are the conditions that precede durable lows. They are not, on their own, the conditions that *make* one — and the gate board is unusually honest about the distinction: five of six [V] gates are dark, because a −50% drawdown has so far produced fear at 27 rather than capitulation at 12, and a $360M liquidation day rather than a flush. The structural work of a bottom has been done. The emotional work has not.

What changed mechanically this week is worth stating precisely, because it is the first time a 2026-07-27 loosening has done real work on this asset. Phase 1B's score line fell from ≥13 to ≥11, and BTC prints exactly 11 — so a tranche that was double-locked on Jul-25 is now single-locked, waiting on gates rather than on the composite. That is the agility mandate functioning as designed: it removed the redundant lock and left the one that requires the market to actually confirm something. It did not, and should not, hand over $58–61.5K exposure on the strength of a rubric change. Two gates short is two gates short; the D2 conviction path opens at a shortfall of one and is unavailable here, which is the correct answer rather than an obstacle.

The thing that genuinely worries me is not in the market data at all. The ledger says this account holds 0.00000184 BTC with an unreliable cost basis and no `FK-` tag anywhere on it, while this report series has carried "Phase 1A deployed, 10% of book, ~$65,000 blended" forward across multiple reports. Hard Rule 8 exists for exactly this, and its verdict is unambiguous even at STALE: the ledger has no evidence of the position the narration describes, and no evidence of what it would have cost. I have not resolved that in this report — a 26-hour-old snapshot cannot resolve a phase-dependent question in either direction, and I will not guess. But a framework planning a 45% Phase 3 against a book it has never measured is doing arithmetic rather than allocating capital, and until the snapshot is refreshed and the holdings tagged, every percentage in the deployment table is a percentage of an unstated notional. That is the first action item, and it is not close.

### Action items

1. **Refresh the position snapshot to FRESH (≤12 h) before the next report.** The current file is 26 h old on `holdings_as_of` — the ledger's live balances refresh only on an explicit `POST /link`, so a re-link is required, not merely a re-export. Until then no phase-dependent unlock precondition can be satisfied from the ledger in either direction.
2. **Resolve the BTC position discrepancy.** Determine whether the narrated 10% Phase 1A tranche exists. Custody is `RECONCILED` with zero withdrawals, so this is not a cold-storage case; but `basis.reliable` is false because 24 unbacked disposals exceed the replay by 8.51 units account-wide, so the fill history is incomplete and "it was sold" is not a supportable conclusion either. Reconcile against Binance trade history directly.
3. **Tag existing holdings.** `performance_by_tag_prefix` is empty — no `FK-` tag exists on this account, so the framework cannot read back its own realized performance per phase. Apply tags via `PUT /api/investments/deal-note` with `report=` provenance on the first line. Until this is done, no report may state how Phase 1A entries have performed.
4. **Hold Phase 1B. Do not deploy.** Score condition met (11 ≥ 11); gate condition short by 2 (3/9 vs 5) and [V] floor short by 1. Re-evaluate on the gate board, not on the score.
5. **Watch gate 4 as the nearest [V] gate.** Trailing-month ETF flows need roughly 8–10 more sessions at the Jul-23/24/31 run rate to clear the 2%-of-AUM bar. That single gate would take the board to 4/9 — still short of 1B, but it is the one moving.
6. **Clear the gate-5 sourcing debt.** Hash Ribbon has been carried dark for two consecutive reports without a sourced print. Ship a sourced read next report or state explicitly why it cannot be obtained.
7. **Mark the Aug-07 payrolls print (08:30 ET) as the horizon's one tier-1 event.** No release falls between now and the Aug-02 checkpoint, which structurally cannot fire (0 of 2 required weekly closes).

> ### The Pattern
>
> **IF** BTC holds two consecutive weekly closes above the 200-week MA ($63,546) while ETF flows print ≥5 consecutive green sessions → **THEN** the flow-flip and trend-structure bars are met together, gate 9's relight becomes plausible on a macro turn, and the accumulation posture converts from waiting to holding-with-conviction. Falsifier: either condition failing by Sun 2026-08-30.
>
> **IF** BTC closes a week below $61,000 with the trailing-month ETF balance net negative → **THEN** a fresh lower low prints, the §5 trend residual activates, any future Override throttles to quarter-size, and the $58,000–61,500 Phase 1B zone becomes a live ladder to work rather than a plan to hold. Falsifier: a weekly close back above $63,546 by Sun 2026-08-16.
>
> **IF** F&G prints ≤15 for 7 consecutive days *and* a liquidation event lands in the top decile of the trailing 90 days → **THEN** gates 1 and 7 light together, the board reaches 5/9 with a [V] count of 4, and Phase 1B unlocks on the gate path with no discretion required. That is the configuration this framework is built to buy, and it is the one worth waiting for.
>
> The 200-week moving average does not negotiate. It either holds and this was the bottom's foundation, or it breaks and $58K was always the real zone. Ninety percent dry at a 3.68% carry is a cheap ticket to find out.

---

```json machine
{
  "schema": "report-machine/1",
  "framework": "fallen_knives",
  "asset": "BTC",
  "date": "2026-08-01",
  "spot": { "value": 63007.10, "source": "median of 4 synchronized live quotes: Binance BTCUSDT $63,060.00 / CoinGecko $63,012.00 / Kraken XXBTZUSD $63,002.20 / Coinbase BTC-USD $62,997.87 (all 2026-08-01 15:27 UTC); spread 0.099%, all live, no staleness exclusion" },
  "score": {
    "legs": { "sentiment": 2, "momentum": 2, "valuation": 4, "capitulation": 0, "holder": 3 },
    "discretionary": 0,
    "mechanical": 11,
    "raw": 11,
    "adjusted": 11,
    "rounding": "half-up"
  },
  "gates": { "active": 9, "na": [], "passed": [3, 6, 8] },
  "ev": {
    "scenarios": [
      { "name": "Rally", "p": 28, "low": 65500, "high": 71000 },
      { "name": "Range", "p": 34, "low": 61500, "high": 65500 },
      { "name": "Retest", "p": 24, "low": 57500, "high": 61500 },
      { "name": "Bear", "p": 14, "low": 50000, "high": 57500 }
    ],
    "stated_ev": 62505.00,
    "vs_spot_pct": -0.80
  },
  "deployment": {
    "deployed_pct": 10,
    "dry_pct": 90,
    "tranches": [
      { "phase": "1A", "pct": 10, "entry": "63000-66500 zone assigned; unlock conditions met (score 11>=8, gates 3/9>=3, [V] 2>=2). NO entry_price is asserted: the prior series carried a narrated cost figure that the ledger cannot corroborate (basis.reliable=false, custody RECONCILED, quantity dust) — encoding it as a numeric fill would assert a cost basis this report explicitly declines to state. Status UNVERIFIED pending a FRESH snapshot; see position.note", "discretionary": false },
      { "phase": "1B", "pct": 15, "entry": "58000-61500 — SCORE CONDITION MET FOR THE FIRST TIME (11>=11 under the 2026-07-27 cut line); blocked on gates alone (3/9<5, [V] 2<3); D2 unavailable (short by 2, not exactly 1)", "discretionary": false },
      { "phase": "2", "pct": 30, "entry": "54000-58000 frozen (score 11<15, gates 3/9<6)", "discretionary": false },
      { "phase": "3", "pct": 45, "entry": "dry (mechanical 11<17)", "discretionary": false }
    ]
  },
  "stops": {
    "catastrophic": 50000,
    "deepest_zone_floor": 54000,
    "compound": { "price": 55000, "score_line": 12 },
    "note": "NO stop parameter changed value. Mechanical score 11<12, so the compound stop's score axis IS satisfied — stop effectively price-gated at $55,000 until score re-crosses 12 (carried state, same as Jul-25, not a widening). Coherence: catastrophic $50,000 strictly below deepest named zone floor $54,000 = PASS (compute.mjs stop-coherence pass:true). No D5 stops — zero analyst-channel tranches exist. Max drawdown spot-to-compound-line -12.71%, disclosed; purchases no loosening per the 2026-07-27 supersession. D6 ratchet: compliant, only the checkpoint date moved.",
    "migration": [
      { "parameter": "checkpoint date", "tier": "checkpoint date", "old": "2026-07-26", "new": "2026-08-02", "direction": "forward roll", "rationale": "Jul-26 checkpoint resolved on schedule and did not fire (0 of 2 required weekly closes below 55000); rolls to the next weekly close" }
    ],
    "checkpoint": {
      "date": "2026-08-02",
      "line": 55000,
      "condition": ">=2 consecutive weekly closes <55000 AND mechanical score <12",
      "closes_below": 0,
      "adr": 1492.24,
      "dist_x_adr": 9.66,
      "side": "spot 14.56% above line; structurally cannot fire (0 of 2 required closes); no tier-1 release before the checkpoint — next is NFP Fri 2026-08-07 08:30 ET, then CPI Wed 2026-08-12"
    }
  },
  "companion_fr": {
    "score": 7,
    "channel": "B",
    "regime": { "pct_below_1y_ath": 50.03, "ma200_falling": true, "ma200_slope20_pct": -3.48, "price_below_ma200_pct": -11.63 },
    "gates_note": "Channel B Phase 1A line 13 — short by 6; stall confirmation FAILS (Aug-01 close 63000 > Jul-31 close 62814)",
    "cross_validation": "consistent — FK 11 / FR 7, inversely related, both <12; label UNQUALIFIED because the Channel A phase-of-cycle cap is not binding (Channel B is the live channel), so the both->=12 check is genuinely falsifiable",
    "standalone_report_owed": false
  },
  "position": {
    "source": "tools/position.mjs btc",
    "band": "STALE",
    "age_min": 1561,
    "age_driver": "holdings_as_of",
    "custody_status": "RECONCILED",
    "qty": "0.00000184",
    "basis_reliable": false,
    "avg_cost_usd": null,
    "unrealized_pnl_usd": null,
    "realized_pnl_usd_upper_bound": 1639.83,
    "attribution": "UNTAGGED",
    "dry_powder_stable_usd": 14288.54,
    "portfolio_total_usd": 19665.31,
    "note": "STALE band — descriptive use only; may NOT satisfy a phase-dependent unlock precondition and may NOT fill a realized ledger column. basis.reliable=false (24 unbacked disposals exceed the replay by 8.51 units account-wide) so no average cost, cost basis, unrealized PnL or ROI is reported; realized PnL is an upper bound. Position Reconciliation: prior reports narrate a 10% Phase 1A at ~$65,000 blended; the ledger shows dust with no derivable basis and zero recorded withdrawals. Custody is RECONCILED so this is not an off-venue case, but the incomplete fill history bars concluding 'sold' either. Reported as UNVERIFIED; no deployment authorized against it."
  },
  "trend_residual": { "active_downtrend": false, "basis": "below 200dma (-11.63%) and just lost the 50dma, but NOT making lower lows — campaign low $57,747.77 (Jul-01) has not been breached; every subsequent low is higher", "consequence": "no bearish residual applied; Deep-Value Override quarter-size throttle is OFF (an Override firing would be half-size) — stated so the sizing guardrail is not silently orphaned" },
  "correlation": { "value_30d_vs_spx": 0.241, "window": "2026-06-18 to 2026-07-31", "method": "Pearson on daily log returns, overlapping sessions, Yahoo closes, computed 2026-08-01", "regime": "mild", "surcharge_applied": false, "phase2_corr_condition": "PASS on a computed number (0.241 < 0.80)" },
  "key_inputs": {
    "fng_spot": 27,
    "fng_3d_avg": 26.67,
    "fng_streak_le15_days": 0,
    "weekly_rsi14": 38.22,
    "weekly_rsi_closes_used": 262,
    "sma_200w": 63546.08,
    "pct_vs_sma200w": -0.77,
    "ma200d": 71293.62,
    "ma200d_slope20_pct": -3.48,
    "ma50d": 63374.73,
    "mvrv_z": 0.41,
    "mvrv_z_asof": "2026-07-31",
    "realized_price": 52418,
    "lth_mvrv": 1.27,
    "sth_mvrv": 0.93,
    "drawdown_from_ath_pct": -49.99,
    "funding_ann_pct": 4.73,
    "funding_negative_intervals_in_15": 0,
    "etf_flow_jul31_usd_m": -265.4,
    "etf_trailing_month": "net positive; 4 outflow / 2 inflow sessions since Jul-23",
    "liquidations_jul31_network_usd_m": 360,
    "liquidations_jul31_widest_venue_set_usd_m": 1450,
    "liquidations_methodology_note": "the $1.45B Loris figure covers a materially wider exchange set than every prior print in this series ($270-320M band) — disclosed and flagged PROVISIONAL, NOT scored; the series' comparable-venue convention is held and Jul-31 reads ordinary",
    "adr5": 1492.24,
    "realized_2w_change_pct": -2.78,
    "tbill_3m_pct": 3.68,
    "real_yield_10y_tips_pct": 2.41,
    "vix": 15.99,
    "dxy": 99.80,
    "brent": 90.12,
    "tier1_next_5_sessions": ["NFP Fri 2026-08-07 08:30 ET"],
    "tier1_beyond_window": ["CPI Wed 2026-08-12 08:30 ET", "PPI Thu 2026-08-13", "Retail Sales Fri 2026-08-14"],
    "stale_input_debt": ["gate 5 Hash Ribbon — no sourced print, report 2 of the debt clock, carried dark and NOT credited"]
  },
  "collar": { "band_triggered": true, "reasons": ["|EV-vs-spot| 0.80% < 2%", "bull/bear scorecard 7-7, balanced"], "mechanical_score_in_6_10_band": false, "effect": "no directional regime resolution claimed" },
  "verdict": "HOLD; deploy nothing new. 90% dry at a 3.68% T-bill carry. Mechanical 11/20, D1 0.0, adjusted 11/20 — no leg moved from Jul-25. HEADLINE MECHANICAL EVENT: the 2026-07-27 cut of the Phase 1B score line from >=13 to >=11 lands for the first time on BTC — 1B goes from double-blocked (score AND gates) to SINGLE-blocked on gates alone (3/9 vs 5 required, [V] 2 vs 3). D2 conviction path evaluated and UNAVAILABLE: it opens at a shortfall of exactly one gate and 1B is short two. STRUCTURE: spot sits -0.77% from the 200-week MA ($63,546), MVRV-Z 0.41 against a realized price of $52,418, LTH MVRV 1.27 with supply rising and reserves falling, funding +4.73% ann with zero negative intervals in 15 — the structural work of a bottom is done, the emotional work is not (F&G 27, no sub-16 print in 10 sessions, gate 1 streak 0, Jul-31 liquidations ordinary at ~$360M). FLOW DETERIORATION: ETF flows resumed net-negative Jul-31 at -$265.4M (IBIT -$122.7M, its largest single-day withdrawal in weeks) — 4 outflow vs 2 inflow sessions since Jul-23, trailing month still net positive at ~0.3% of AUM vs the 2% bar; gate 4 stays dark but is the nearest [V] gate. DECOUPLING: July closed +7.5% through an AI-led equity unwind at a COMPUTED 30d SPX correlation of 0.241 — no risk-on surcharge, Phase-2 corr condition passes on a real number. FR COMPANION computed at 7/20 Channel B (routing re-verified: -50.03% off the 1y high, 200dma falling -3.48%/20 sessions, price -11.63% beneath) against a Channel B 1A line of 13 — stand down; cross-validation consistent and UNQUALIFIED since the cap is not binding. POSITION OF RECORD (Hard Rule 8, STALE at 26h): the ledger shows 0.00000184 BTC — dust — with basis.reliable=false, custody RECONCILED, zero withdrawals, UNTAGGED, against a narrated '10% Phase 1A at ~$65,000 blended' carried forward across multiple reports. No average cost, cost basis, unrealized PnL or ROI reported; realized $1,639.83 is an upper bound. The discrepancy is flagged, not resolved — a STALE snapshot cannot settle a phase-dependent question in either direction — and resolving it is action item 1, ahead of every market call here. Collar ACTIVE (|EV-vs-spot| 0.80% < 2%; scorecard 7-7): no directional regime resolution claimed."
}
```
